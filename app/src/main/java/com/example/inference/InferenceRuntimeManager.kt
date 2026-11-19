package com.example.inference

import android.content.Context
import com.example.core.hardware.DeviceProfiler
import com.example.core.model.AgentRole
import com.example.core.model.AgentRuntimeState
import com.example.core.model.InferenceParameters
import com.example.core.model.ModelFormat
import com.example.core.model.RuntimeBackend
import com.example.memory.LoadedModelSnapshot
import com.example.memory.MemoryBudgetManager
import com.example.monitoring.data.LocalModelDao
import com.example.monitoring.data.LocalModelEntity
import com.example.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InferenceExecutionResult(
    val text: String,
    val durationMs: Long,
    val approxTokens: Int,
    val modelName: String,
    val runtimeBackend: String
)

class InferenceRuntimeManager(
    context: Context,
    private val localModelDao: LocalModelDao,
    private val memoryBudgetManager: MemoryBudgetManager,
    private val deviceProfiler: DeviceProfiler,
    private val settingsRepository: SettingsRepository
) {
    private val executionMutex = Mutex()

    private val llamaRuntime = LlamaCppRuntime(context)
    private val liteRtCpuRuntime = LiteRtLmRuntime(context, RuntimeBackend.LITERT_LM_CPU)
    private val liteRtGpuRuntime = LiteRtLmRuntime(context, RuntimeBackend.LITERT_LM_GPU)

    private var activeRuntime: LocalInferenceRuntime? = null
    private var activeModelEntity: LocalModelEntity? = null
    private var activeContextLength: Int = 0
    @Volatile
    private var lastActivityMs: Long = 0L

    private val _agentStates = MutableStateFlow(
        AgentRole.entries.associateWith { AgentRuntimeState.IDLE }
    )
    val agentStates: StateFlow<Map<AgentRole, AgentRuntimeState>> = _agentStates.asStateFlow()

    private val _activeQueueDepth = MutableStateFlow(0)
    val activeQueueDepth: StateFlow<Int> = _activeQueueDepth.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun setAgentDisabledState(role: AgentRole, enabled: Boolean) {
        updateAgentState(role, if (enabled) AgentRuntimeState.IDLE else AgentRuntimeState.DISABLED)
    }

    suspend fun preloadModelForRole(
        role: AgentRole,
        modelId: String,
        preferredBackend: RuntimeBackend,
        params: InferenceParameters
    ): Result<LoadedModelSnapshot> = executionMutex.withLock {
        val model = localModelDao.getById(modelId)
            ?: return@withLock Result.failure(IllegalStateException("Modelo não encontrado no catálogo local."))
        return@withLock ensureModelLoadedLocked(role, model, preferredBackend, params)
    }

    suspend fun unloadAllModels(reason: String = "Descarregamento manual") {
        executionMutex.withLock {
            unloadCurrentLocked(reason)
        }
    }

    suspend fun unloadIfIdleFor(maxIdleMs: Long) {
        val loadedAtOrUsed = lastActivityMs
        if (loadedAtOrUsed <= 0L || activeModelEntity == null || _activeQueueDepth.value > 0) return
        if (System.currentTimeMillis() - loadedAtOrUsed < maxIdleMs) return
        if (executionMutex.tryLock()) {
            try {
                if (
                    activeModelEntity != null &&
                    _activeQueueDepth.value == 0 &&
                    System.currentTimeMillis() - lastActivityMs >= maxIdleMs
                ) {
                    unloadCurrentLocked("Descarregamento automático após inatividade")
                }
            } finally {
                executionMutex.unlock()
            }
        }
    }

    fun streamInference(
        role: AgentRole,
        model: LocalModelEntity,
        preferredBackend: RuntimeBackend,
        systemPrompt: String,
        messages: List<ChatTurn>,
        params: InferenceParameters
    ): Flow<InferenceStreamEvent> = flow {
        _activeQueueDepth.value += 1
        try {
            executionMutex.withLock {
                val settings = settingsRepository.settingsFlow.first()
                if (settings.thermalGuardEnabled && deviceProfiler.isThermalSevereOrCritical()) {
                    val thermalMsg = "Proteção térmica ativa: temperatura do dispositivo elevada (severa/crítica). Aguarde resfriar ou desative a proteção nas Configurações."
                    _lastError.value = thermalMsg
                    updateAgentState(role, AgentRuntimeState.ERROR)
                    emit(InferenceStreamEvent.Failed(thermalMsg))
                    return@withLock
                }

                val loadResult = ensureModelLoadedLocked(role, model, preferredBackend, params)
                if (loadResult.isFailure) {
                    val errMsg = loadResult.exceptionOrNull()?.message ?: "Falha ao carregar modelo local"
                    _lastError.value = errMsg
                    updateAgentState(role, AgentRuntimeState.ERROR)
                    emit(InferenceStreamEvent.Failed(errMsg))
                    return@withLock
                }

                updateAgentState(role, AgentRuntimeState.RUNNING)
                val runtime = activeRuntime
                if (runtime == null) {
                    updateAgentState(role, AgentRuntimeState.ERROR)
                    emit(InferenceStreamEvent.Failed("Runtime de inferência indisponível após carregamento."))
                    return@withLock
                }
                try {
                    runtime.streamCompletion(systemPrompt, messages, params).collect { ev ->
                        lastActivityMs = System.currentTimeMillis()
                        when (ev) {
                            is InferenceStreamEvent.Failed -> {
                                _lastError.value = ev.errorMessage
                                updateAgentState(role, AgentRuntimeState.ERROR)
                            }
                            is InferenceStreamEvent.Completed -> {
                                _lastError.value = null
                                updateAgentState(role, AgentRuntimeState.IDLE)
                            }
                            else -> Unit
                        }
                        emit(ev)
                    }
                } finally {
                    if (_agentStates.value[role] == AgentRuntimeState.RUNNING) {
                        updateAgentState(role, AgentRuntimeState.IDLE)
                    }
                    lastActivityMs = System.currentTimeMillis()
                }
            }
        } finally {
            _activeQueueDepth.value = (_activeQueueDepth.value - 1).coerceAtLeast(0)
            memoryBudgetManager.refreshProfile()
        }
    }

    suspend fun runBlockingInference(
        role: AgentRole,
        model: LocalModelEntity,
        preferredBackend: RuntimeBackend,
        systemPrompt: String,
        messages: List<ChatTurn>,
        params: InferenceParameters
    ): Result<InferenceExecutionResult> {
        var finalResult: InferenceExecutionResult? = null
        var failureMsg: String? = null

        streamInference(role, model, preferredBackend, systemPrompt, messages, params).collect { ev ->
            when (ev) {
                is InferenceStreamEvent.Completed -> {
                    finalResult = InferenceExecutionResult(
                        text = ev.fullText,
                        durationMs = ev.durationMs,
                        approxTokens = ev.approxTokens,
                        modelName = model.name,
                        runtimeBackend = activeRuntime?.backend?.label ?: preferredBackend.label
                    )
                }
                is InferenceStreamEvent.Failed -> {
                    failureMsg = ev.errorMessage
                }
                else -> Unit
            }
        }

        return finalResult?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException(failureMsg ?: "Inferência finalizada sem saída."))
    }

    private suspend fun ensureModelLoadedLocked(
        role: AgentRole,
        model: LocalModelEntity,
        preferredBackend: RuntimeBackend,
        params: InferenceParameters
    ): Result<LoadedModelSnapshot> {
        val format = runCatching { ModelFormat.valueOf(model.format) }.getOrDefault(ModelFormat.GGUF)
        val targetRuntime = selectCompatibleRuntime(format, preferredBackend)
        val requestedCtx = params.contextLength.coerceIn(512, 8192)
        var targetCtx = requestedCtx
        val contextCandidates = if (format == ModelFormat.GGUF) {
            listOf(requestedCtx, 3072, 2048, 1536, 1024, 512)
                .filter { it <= requestedCtx }
                .distinct()
        } else {
            listOf(requestedCtx)
        }

        if (
            activeModelEntity?.id == model.id &&
            activeModelEntity?.mmprojPath == model.mmprojPath &&
            activeContextLength > 0 &&
            activeContextLength >= requestedCtx &&
            activeRuntime == targetRuntime
        ) {
            val existing = memoryBudgetManager.state.value.loadedModels.firstOrNull()
            if (existing != null) {
                lastActivityMs = System.currentTimeMillis()
                return Result.success(existing)
            }
        }

        val mmprojBytes = model.mmprojPath?.let { java.io.File(it).takeIf { file -> file.exists() }?.length() } ?: 0L

        // Unload first, then measure free RAM. Measuring before eviction makes the
        // old model reduce the new model's budget even though it is about to be freed.
        if (activeModelEntity != null) {
            val prevName = activeModelEntity?.name ?: "anterior"
            unloadCurrentLocked("Descarregado '$prevName' para dar lugar a '${model.name}'")
            memoryBudgetManager.refreshProfile()
        }

        var footprintMb = 0L
        var lastMemoryReason: String? = null
        var selectedContext = false
        for (candidateContext in contextCandidates) {
            val candidateFootprint = memoryBudgetManager.estimateGgufFootprintMb(
                fileSizeBytes = model.sizeBytes,
                contextLength = candidateContext,
                mmprojSizeBytes = mmprojBytes
            )
            val (fitsCandidate, reason) = memoryBudgetManager.checkCanFitModel(
                requestedFootprintMb = candidateFootprint,
                currentlyLoadedMb = 0L,
                willUnloadOthers = true
            )
            if (fitsCandidate) {
                targetCtx = candidateContext
                footprintMb = candidateFootprint
                selectedContext = true
                break
            }
            lastMemoryReason = reason
        }
        if (!selectedContext) {
            return Result.failure(
                IllegalStateException(
                    lastMemoryReason ?: "Não há memória suficiente para carregar o modelo, mesmo com contexto reduzido."
                )
            )
        }

        updateAgentState(role, AgentRuntimeState.LOADING_MODEL)

        var loadResult = try {
            targetRuntime.loadModel(model, params.copy(contextLength = targetCtx))
        } catch (oom: OutOfMemoryError) {
            runCatching { targetRuntime.unloadModel() }
            Result.failure(IllegalStateException("Falta de memória (OOM) ao carregar ${model.name}."))
        }

        // Native allocation can exceed the estimate. Retry GGUF with progressively
        // smaller contexts before reporting OOM; model weights and quantization stay unchanged.
        if (loadResult.isFailure && format == ModelFormat.GGUF) {
            for (fallbackContext in contextCandidates.filter { it < targetCtx }) {
                runCatching { targetRuntime.unloadModel() }
                val fallbackFootprint = memoryBudgetManager.estimateGgufFootprintMb(
                    fileSizeBytes = model.sizeBytes,
                    contextLength = fallbackContext,
                    mmprojSizeBytes = mmprojBytes
                )
                val (fitsFallback, _) = memoryBudgetManager.checkCanFitModel(
                    requestedFootprintMb = fallbackFootprint,
                    currentlyLoadedMb = 0L,
                    willUnloadOthers = true
                )
                if (!fitsFallback) continue
                loadResult = try {
                    targetRuntime.loadModel(model, params.copy(contextLength = fallbackContext))
                } catch (oom: OutOfMemoryError) {
                    runCatching { targetRuntime.unloadModel() }
                    Result.failure(IllegalStateException("OOM ao tentar contexto de ${fallbackContext} tokens."))
                }
                if (loadResult.isSuccess) {
                    targetCtx = fallbackContext
                    footprintMb = fallbackFootprint
                    break
                }
            }
        }

        // A failed native initialization may still hold partial resources. Release
        // them before attempting another backend or returning an error.
        if (loadResult.isFailure) {
            runCatching { targetRuntime.unloadModel() }
        }

        // Automatic fallback from LiteRT-LM GPU to CPU if OpenCL fails on device
        if (loadResult.isFailure && targetRuntime == liteRtGpuRuntime) {
            // CPU fallback can also fail on memory-constrained devices. Keep that
            // failure inside the Result pipeline instead of letting an OOM crash the task.
            val fallbackRes = try {
                // Preserve the context that passed the memory-budget checks.
                liteRtCpuRuntime.loadModel(model, params.copy(contextLength = targetCtx))
            } catch (oom: OutOfMemoryError) {
                runCatching { liteRtCpuRuntime.unloadModel() }
                Result.failure(
                    IllegalStateException(
                        "Falta de memória (OOM) também no fallback CPU para ${model.name}. Reduza o contexto ou use uma quantização menor.",
                        oom
                    )
                )
            }

            if (fallbackRes.isSuccess) {
                activeRuntime = liteRtCpuRuntime
                activeModelEntity = model
                activeContextLength = targetCtx
                lastActivityMs = System.currentTimeMillis()
                val snap = LoadedModelSnapshot(
                    modelId = model.id,
                    modelName = model.name,
                    roleId = role.id,
                    runtimeBackend = liteRtCpuRuntime.backend.label,
                    estimatedFootprintMb = footprintMb
                )
                memoryBudgetManager.recordLoadedModel(snap, "Fallback automático GPU -> CPU LiteRT-LM")
                updateAgentState(role, AgentRuntimeState.IDLE)
                return Result.success(snap)
            }

            runCatching { liteRtCpuRuntime.unloadModel() }
            val gpuError = loadResult.exceptionOrNull()?.message ?: "erro desconhecido"
            val cpuError = fallbackRes.exceptionOrNull()?.message ?: "erro desconhecido"
            updateAgentState(role, AgentRuntimeState.ERROR)
            return Result.failure(
                IllegalStateException(
                    "LiteRT-LM GPU falhou ($gpuError). O fallback CPU também falhou ($cpuError)."
                )
            )
        }

        if (loadResult.isFailure) {
            updateAgentState(role, AgentRuntimeState.ERROR)
            return Result.failure(loadResult.exceptionOrNull() ?: IllegalStateException("Erro ao inicializar modelo."))
        }

        activeRuntime = targetRuntime
        activeModelEntity = model
        activeContextLength = targetCtx
        lastActivityMs = System.currentTimeMillis()
        val snapshot = LoadedModelSnapshot(
            modelId = model.id,
            modelName = model.name,
            roleId = role.id,
            runtimeBackend = targetRuntime.backend.label,
            estimatedFootprintMb = footprintMb
        )
        memoryBudgetManager.recordLoadedModel(snapshot)
        updateAgentState(role, AgentRuntimeState.IDLE)
        return Result.success(snapshot)
    }

    private suspend fun unloadCurrentLocked(reason: String) {
        val runtime = activeRuntime
        activeRuntime = null
        activeModelEntity = null
        activeContextLength = 0
        lastActivityMs = 0L
        if (runtime != null) {
            runCatching { runtime.unloadModel() }
        }
        memoryBudgetManager.recordLoadedModel(null, reason)
    }

    private fun selectCompatibleRuntime(
        format: ModelFormat,
        preferred: RuntimeBackend
    ): LocalInferenceRuntime {
        return when (format) {
            ModelFormat.GGUF -> llamaRuntime
            ModelFormat.LITERT_LM -> when (preferred) {
                RuntimeBackend.LITERT_LM_GPU -> liteRtGpuRuntime
                else -> liteRtCpuRuntime
            }
            ModelFormat.UNKNOWN -> llamaRuntime
        }
    }

    private fun updateAgentState(role: AgentRole, newState: AgentRuntimeState) {
        _agentStates.value = _agentStates.value.toMutableMap().apply {
            this[role] = newState
        }
    }
}
