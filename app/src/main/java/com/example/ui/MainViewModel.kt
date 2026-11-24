package com.example.ui

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LocalDevAiApplication
import com.example.agent_engine.MultiAgentLiveState
import com.example.api_server.ApiServerStatus
import com.example.api_server.LocalApiForegroundService
import com.example.api_server.InferenceTaskForegroundService
import com.example.api_server.NetworkAddressState
import com.example.extensions.ExtensionCatalog
import com.example.extensions.LocalExtension
import com.example.core.model.AgentRole
import com.example.core.model.AgentRuntimeState
import com.example.core.model.DiagnosticCheckResult
import com.example.core.model.DownloadProgressState
import com.example.core.model.RecommendedModelPreset
import com.example.core.model.RuntimeBackend
import com.example.inference.ChatTurn
import com.example.inference.InferenceStreamEvent
import com.example.memory.MemoryStatusState
import com.example.monitoring.SystemDiagnosticsRunner
import com.example.monitoring.data.AgentConfigEntity
import com.example.monitoring.data.AgentHistoryEntity
import com.example.monitoring.data.ApiKeyEntity
import com.example.monitoring.data.ApiRequestLogEntity
import com.example.monitoring.data.ConnectedDeviceEntity
import com.example.monitoring.data.LocalModelEntity
import com.example.monitoring.data.MultiAgentRunEntity
import com.example.security.KeyPermission
import com.example.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class AgentConsoleState(
    val isGenerating: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val status: String? = null,
    val lastPrompt: String = "",
    val selectedImageUri: Uri? = null,
    val selectedImageTempPath: String? = null,
    val conversation: List<ChatTurn> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as LocalDevAiApplication).container
    private val saceProgressStore = SaceProgressStore(application)
    private val saceBenchmarkStore = SaceBenchmarkStore(application)

    private val diagnosticsRunner = SystemDiagnosticsRunner(
        deviceProfiler = container.deviceProfiler,
        memoryBudgetManager = container.memoryBudgetManager,
        apiKeyManager = container.apiKeyManager,
        openAiApiServer = container.openAiApiServer,
        cacheDir = application.cacheDir
    )

    val memoryState: StateFlow<MemoryStatusState> = container.memoryBudgetManager.state
    val networkState: StateFlow<NetworkAddressState> = container.networkDiscovery.state
    val serverStatus: StateFlow<ApiServerStatus> = container.openAiApiServer.status
    val agentStates: StateFlow<Map<AgentRole, AgentRuntimeState>> = container.inferenceRuntimeManager.agentStates
    val queueDepth: StateFlow<Int> = container.inferenceRuntimeManager.activeQueueDepth
    val downloadState: StateFlow<DownloadProgressState?> = container.modelRepository.downloadState
    val multiAgentLiveState: StateFlow<MultiAgentLiveState> = container.multiAgentOrchestrator.liveState

    val settings: StateFlow<AppSettings> = container.settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val models: StateFlow<List<LocalModelEntity>> = container.modelRepository.modelsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agentConfigs: StateFlow<List<AgentConfigEntity>> = container.database.agentConfigDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agentHistory: StateFlow<List<AgentHistoryEntity>> = container.database.agentHistoryDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val multiAgentRuns: StateFlow<List<MultiAgentRunEntity>> = container.multiAgentOrchestrator.recentRunsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val apiKeys: StateFlow<List<ApiKeyEntity>> = container.database.apiKeyDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedDevices: StateFlow<List<ConnectedDeviceEntity>> = container.deviceAccessRepository.devicesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val apiLogs: StateFlow<List<ApiRequestLogEntity>> = container.database.apiRequestLogDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recommendedPresets: List<RecommendedModelPreset> = container.modelRepository.recommendedPresets
    val extensions: List<LocalExtension> = ExtensionCatalog.all

    fun toggleExtension(id: String, enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.updateExtensionEnabled(id, enabled) }
    }

    private val _primaryKeyPlaintext = MutableStateFlow<String?>(null)
    val primaryKeyPlaintext: StateFlow<String?> = _primaryKeyPlaintext.asStateFlow()

    private val _newlyCreatedDeviceKey = MutableStateFlow<Pair<String, String>?>(null)
    val newlyCreatedDeviceKey: StateFlow<Pair<String, String>?> = _newlyCreatedDeviceKey.asStateFlow()

    private val _agentConsoles = MutableStateFlow(
        AgentRole.entries.associateWith { AgentConsoleState() }
    )
    val agentConsoles: StateFlow<Map<AgentRole, AgentConsoleState>> = _agentConsoles.asStateFlow()

    private val _multiAgentImagePath = MutableStateFlow<String?>(null)
    val multiAgentImagePath: StateFlow<String?> = _multiAgentImagePath.asStateFlow()

    private val _multiAgentImageUri = MutableStateFlow<Uri?>(null)
    val multiAgentImageUri: StateFlow<Uri?> = _multiAgentImageUri.asStateFlow()

    private val _diagnosticsResults = MutableStateFlow<List<DiagnosticCheckResult>>(emptyList())
    val diagnosticsResults: StateFlow<List<DiagnosticCheckResult>> = _diagnosticsResults.asStateFlow()

    private val _isRunningDiagnostics = MutableStateFlow(false)
    val isRunningDiagnostics: StateFlow<Boolean> = _isRunningDiagnostics.asStateFlow()

    private val _uiBannerMessage = MutableStateFlow<String?>(null)
    val uiBannerMessage: StateFlow<String?> = _uiBannerMessage.asStateFlow()

    private val consoleJobs = mutableMapOf<AgentRole, Job>()

    init {
        viewModelScope.launch {
            val primary = container.apiKeyManager.ensurePermanentPrimaryKey()
            _primaryKeyPlaintext.value = container.apiKeyManager.decryptKeyForDisplay(primary)
            val initialSettings = container.settingsRepository.settingsFlow.first()
            container.memoryBudgetManager.updateUserRamLimitMb(initialSettings.ramLimitMb)
            container.memoryBudgetManager.updateSystemRamReserveMb(initialSettings.systemRamReserveMb)
            if (initialSettings.autoStartServerOnLaunch && !serverStatus.value.isRunning) {
                LocalApiForegroundService.startServerService(getApplication(), initialSettings.serverPort)
                container.networkDiscovery.refreshNow()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(4000L)
                container.memoryBudgetManager.refreshProfile()
                val currentSettings = settings.value
                if (currentSettings.autoUnloadIdleModels) {
                    val timeoutMs = currentSettings.idleUnloadMinutes.coerceIn(1, 30) * 60_000L
                    container.inferenceRuntimeManager.unloadIfIdleFor(timeoutMs)
                }
            }
        }
    }

    val securityBackendName: String get() = container.apiKeyManager.securityBackendName

    fun clearBanner() {
        _uiBannerMessage.value = null
    }

    fun showBanner(msg: String) {
        _uiBannerMessage.value = msg
    }

    // --- Server Controls ---
    fun startApiServer() {
        val port = settings.value.serverPort
        LocalApiForegroundService.startServerService(getApplication(), port)
        container.networkDiscovery.refreshNow()
    }

    fun stopApiServer() {
        LocalApiForegroundService.stopServerService(getApplication())
    }

    fun refreshNetworkIp() {
        container.networkDiscovery.refreshNow()
    }

    // --- Primary & Device API Keys ---
    fun regeneratePrimaryKey() {
        viewModelScope.launch {
            val updated = container.apiKeyManager.regeneratePrimaryKey()
            _primaryKeyPlaintext.value = container.apiKeyManager.decryptKeyForDisplay(updated)
            _uiBannerMessage.value = "Chave API principal regenerada com segurança."
        }
    }

    fun revokePrimaryKey() {
        viewModelScope.launch {
            container.apiKeyManager.revokePrimaryKey()
            _uiBannerMessage.value = "Chave API principal revogada. Clientes externos não poderão usá-la até você regenerar."
        }
    }

    fun createAdditionalKey(name: String, permission: KeyPermission) {
        viewModelScope.launch {
            val (entity, rawSecret) = container.apiKeyManager.createDeviceKey(name, permission)
            _newlyCreatedDeviceKey.value = entity.name to rawSecret
        }
    }

    fun dismissNewlyCreatedDeviceKey() {
        _newlyCreatedDeviceKey.value = null
    }

    fun revealKeySecret(entity: ApiKeyEntity, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(container.apiKeyManager.decryptKeyForDisplay(entity))
        }
    }

    fun toggleKeyActive(entity: ApiKeyEntity) {
        viewModelScope.launch {
            if (entity.isActive) container.apiKeyManager.revokeKey(entity.id)
            else container.apiKeyManager.reactivateKey(entity.id)
        }
    }

    fun deleteAdditionalKey(keyId: String) {
        viewModelScope.launch {
            container.apiKeyManager.deleteAdditionalKey(keyId)
        }
    }

    fun toggleDeviceBlocked(device: ConnectedDeviceEntity) {
        viewModelScope.launch {
            container.deviceAccessRepository.setDeviceBlocked(device.deviceFingerprint, !device.isBlocked)
        }
    }

    // --- Model Management ---
    fun importLocalModel(
        uri: Uri,
        mmprojUri: Uri?,
        customName: String?,
        assignRole: AgentRole?,
        supportsVision: Boolean
    ) {
        viewModelScope.launch {
            _uiBannerMessage.value = "Importando e validando modelo local..."
            val res = container.modelRepository.importModelFromUri(
                modelUri = uri,
                mmprojUri = mmprojUri,
                customName = customName,
                assignToRole = assignRole,
                supportsVisionHint = supportsVision
            )
            _uiBannerMessage.value = res.fold(
                onSuccess = { "Modelo '${it.name}' importado (${it.compatibilityDetail})." },
                onFailure = { "Falha ao importar: ${it.message}" }
            )
        }
    }

    fun attachMmproj(modelId: String, mmprojUri: Uri) {
        viewModelScope.launch {
            val res = container.modelRepository.attachMmprojToModel(modelId, mmprojUri)
            _uiBannerMessage.value = res.fold(
                onSuccess = { "Projetor multimodal anexado a '${it.name}'." },
                onFailure = { "Erro ao anexar mmproj: ${it.message}" }
            )
        }
    }

    fun downloadPreset(preset: RecommendedModelPreset) {
        viewModelScope.launch {
            val res = container.modelRepository.downloadModelFromUrl(
                presetId = preset.id,
                displayName = preset.name,
                url = preset.downloadUrl,
                mmprojUrl = preset.mmprojDownloadUrl,
                assignToRole = preset.recommendedRole,
                supportsVision = preset.supportsVision
            )
            _uiBannerMessage.value = res.fold(
                onSuccess = { "Modelo '${it.name}' baixado e associado a ${preset.recommendedRole.displayName}." },
                onFailure = { "Erro no download: ${it.message}" }
            )
        }
    }

    fun downloadCustomUrl(
        name: String,
        url: String,
        mmprojUrl: String?,
        role: AgentRole?,
        supportsVision: Boolean
    ) {
        viewModelScope.launch {
            val res = container.modelRepository.downloadModelFromUrl(
                presetId = "custom_${System.currentTimeMillis()}",
                displayName = name.ifBlank { url.substringAfterLast('/').substringBefore('.') },
                url = url.trim(),
                mmprojUrl = mmprojUrl?.trim()?.takeIf { it.isNotBlank() },
                assignToRole = role,
                supportsVision = supportsVision
            )
            _uiBannerMessage.value = res.fold(
                onSuccess = { "Modelo '${it.name}' baixado com sucesso." },
                onFailure = { "Falha no download: ${it.message}" }
            )
        }
    }

    fun cancelModelDownload() {
        container.modelRepository.cancelDownload()
        _uiBannerMessage.value = "Download cancelado."
    }

    fun assignModelToAgent(role: AgentRole, modelId: String?) {
        viewModelScope.launch {
            container.modelRepository.assignModelToAgent(role, modelId)
        }
    }

    fun preloadModel(role: AgentRole, model: LocalModelEntity) {
        viewModelScope.launch {
            val cfg = container.database.agentConfigDao().getByRole(role.id)
                ?: AgentConfigEntity(roleId = role.id)
            val backend = runCatching { RuntimeBackend.valueOf(cfg.runtimeBackend) }
                .getOrDefault(RuntimeBackend.LLAMA_CPP)
            val params = com.example.core.model.InferenceParameters(
                contextLength = cfg.contextLength,
                maxTokens = cfg.maxTokens,
                temperature = cfg.temperature,
                topP = cfg.topP,
                topK = cfg.topK,
                threads = cfg.threads
            )
            val res = container.inferenceRuntimeManager.preloadModelForRole(role, model.id, backend, params)
            _uiBannerMessage.value = res.fold(
                onSuccess = { "Modelo '${it.modelName}' carregado em memória (~${it.estimatedFootprintMb} MB)." },
                onFailure = { "Não foi possível carregar: ${it.message}" }
            )
        }
    }

    fun unloadAllModels() {
        viewModelScope.launch {
            container.inferenceRuntimeManager.unloadAllModels("Descarregamento solicitado pelo usuário")
            _uiBannerMessage.value = "Modelos descarregados da memória."
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            if (container.memoryBudgetManager.state.value.loadedModels.any { it.modelId == modelId }) {
                container.inferenceRuntimeManager.unloadAllModels("Modelo removido")
            }
            container.modelRepository.deleteModel(modelId)
        }
    }

    // --- Agent Configuration & Local Console ---
    fun toggleAgentEnabled(role: AgentRole, enabled: Boolean) {
        viewModelScope.launch {
            val existing = container.database.agentConfigDao().getByRole(role.id)
                ?: AgentConfigEntity(roleId = role.id)
            container.database.agentConfigDao().upsert(existing.copy(enabled = enabled))
            container.inferenceRuntimeManager.setAgentDisabledState(role, enabled)
        }
    }

    fun updateAgentConfig(config: AgentConfigEntity) {
        viewModelScope.launch {
            container.database.agentConfigDao().upsert(config)
        }
    }

    fun setVisionImageUri(uri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = uri?.let { copyImageToCache(it, "vision_input") }
            updateConsole(AgentRole.VISION) {
                it.copy(selectedImageUri = uri, selectedImageTempPath = path, error = null)
            }
        }
    }

    fun runAgentConsolePrompt(role: AgentRole, prompt: String) {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isBlank()) {
            updateConsole(role) { it.copy(error = "Digite uma mensagem antes de executar.", status = null) }
            return
        }

        consoleJobs.remove(role)?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val thisJob = currentCoroutineContext()[Job]
            val currentConsole = _agentConsoles.value[role] ?: AgentConsoleState()
            val streamedOutput = StringBuilder()
            var receivedCompletion = false
            var failureMessage: String? = null
            var taskStarted = false

            updateConsole(role) {
                it.copy(
                    isGenerating = true,
                    output = "",
                    error = null,
                    status = "Mensagem preparada. Iniciando inferência local...",
                    lastPrompt = normalizedPrompt
                )
            }

            try {
                InferenceTaskForegroundService.startTask(getApplication())
                taskStarted = true
                val userTurn = ChatTurn(
                    role = "user",
                    text = normalizedPrompt,
                    imagePath = if (role == AgentRole.VISION) currentConsole.selectedImageTempPath else null
                )
                // Retain at most 10 exchanges (20 turns) to avoid unbounded memory growth.
                val turns = currentConsole.conversation.takeLast(20) + userTurn
                val stream = when (role) {
                    AgentRole.CODING -> container.codingAgent.streamExecute(turns, "UI")
                    AgentRole.VISION -> container.visionAgent.streamExecute(turns, "UI")
                    AgentRole.REASONING -> container.reasoningAgent.streamExecute(turns, "UI")
                }

                stream.collect { event ->
                    when (event) {
                        is InferenceStreamEvent.Status -> {
                            updateConsole(role) { it.copy(status = event.message) }
                        }
                        is InferenceStreamEvent.Token -> {
                            if (event.text.isNotEmpty()) {
                                streamedOutput.append(event.text)
                                updateConsole(role) {
                                    it.copy(output = streamedOutput.toString(), error = null)
                                }
                            }
                        }
                        is InferenceStreamEvent.Completed -> {
                            receivedCompletion = true
                            val finalText = event.fullText.takeIf { it.isNotBlank() }
                                ?: streamedOutput.toString()
                            if (finalText.isBlank()) {
                                failureMessage = "A inferência terminou, mas o modelo não gerou texto. Verifique o modelo associado, o backend e os parâmetros de saída."
                            } else {
                                streamedOutput.clear()
                                streamedOutput.append(finalText)
                                updateConsole(role) {
                                    it.copy(
                                        output = finalText,
                                        error = null,
                                        status = "Resposta recebida do modelo local.",
                                        conversation = (currentConsole.conversation + userTurn + ChatTurn(role = "assistant", text = finalText)).takeLast(20)
                                    )
                                }
                            }
                        }
                        is InferenceStreamEvent.Failed -> {
                            failureMessage = event.errorMessage
                            updateConsole(role) {
                                it.copy(error = event.errorMessage, status = "A inferência foi encerrada com erro.")
                            }
                        }
                        else -> Unit
                    }
                }

                when {
                    failureMessage != null -> updateConsole(role) {
                        it.copy(error = failureMessage, status = "Não foi possível concluir a resposta.")
                    }
                    !receivedCompletion -> updateConsole(role) {
                        it.copy(
                            error = "O fluxo de inferência foi encerrado sem evento de conclusão. A mensagem foi enviada pelo console, mas não houve confirmação de resposta do runtime.",
                            status = "Resposta não confirmada."
                        )
                    }
                    streamedOutput.isBlank() -> updateConsole(role) {
                        it.copy(
                            error = "O modelo concluiu sem conteúdo. Isso não será tratado como uma resposta válida.",
                            status = "Resposta vazia detectada."
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
                updateConsole(role) {
                    it.copy(error = "Falha no Console Direto: $detail", status = "Erro durante a inferência.")
                }
            } finally {
                if (consoleJobs[role] === thisJob) {
                    updateConsole(role) { it.copy(isGenerating = false) }
                }
                if (taskStarted) {
                    InferenceTaskForegroundService.finishTask(getApplication())
                }
            }
        }
        consoleJobs[role] = job
        job.start()
    }

    fun stopAgentConsole(role: AgentRole) {
        consoleJobs.remove(role)?.cancel()
        updateConsole(role) {
            it.copy(isGenerating = false, status = "Execução interrompida pelo usuário.")
        }
    }

    fun clearAgentConsole(role: AgentRole) {
        consoleJobs.remove(role)?.cancel()
        updateConsole(role) {
            it.copy(
                isGenerating = false,
                output = "",
                error = null,
                status = "Conversa limpa.",
                lastPrompt = "",
                conversation = emptyList(),
                selectedImageUri = null,
                selectedImageTempPath = null
            )
        }
    }


    // --- AI Calibration Lab ---
    private val calibrationPreferences = application.getSharedPreferences("sace_progress", 0)
    private val _selectedCalibrationRoles = MutableStateFlow(
        calibrationPreferences.getStringSet("selected_agent_roles_v1", null)
            ?.mapNotNull { id -> AgentRole.entries.firstOrNull { it.id == id } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: AgentRole.entries.toSet()
    )
    val selectedCalibrationRoles: StateFlow<Set<AgentRole>> = _selectedCalibrationRoles.asStateFlow()

    fun setCalibrationAgentSelected(role: AgentRole, selected: Boolean) {
        if (_calibrationState.value.isRunning || _calibrationState.value.isBenchmarkRunning ||
            _calibrationState.value.canResume) return
        val updated = _selectedCalibrationRoles.value.toMutableSet().apply {
            if (selected) add(role) else remove(role)
        }
        _selectedCalibrationRoles.value = updated
        calibrationPreferences.edit()
            .putStringSet("selected_agent_roles_v1", updated.map { it.id }.toSet())
            .apply()
    }

    private val _calibrationDurations = MutableStateFlow(
        mapOf(AgentRole.CODING to 60, AgentRole.REASONING to 45, AgentRole.VISION to 20)
    )
    private val _calibrationModes = MutableStateFlow(
        AgentRole.entries.associateWith { role ->
            val savedMode = calibrationPreferences.getString("calibration_mode_${role.id}", null)
            runCatching { CalibrationMode.valueOf(savedMode ?: CalibrationMode.NORMAL.name) }
                .getOrDefault(CalibrationMode.NORMAL)
        }
    )
    val calibrationModes: StateFlow<Map<AgentRole, CalibrationMode>> = _calibrationModes.asStateFlow()

    fun setCalibrationMode(role: AgentRole, mode: CalibrationMode) {
        if (_calibrationState.value.isRunning || _calibrationState.value.isBenchmarkRunning) return
        _calibrationModes.value = _calibrationModes.value.toMutableMap().apply { put(role, mode) }
        calibrationPreferences.edit().putString("calibration_mode_${role.id}", mode.name).apply()
    }
    val calibrationDurations: StateFlow<Map<AgentRole, Int>> = _calibrationDurations.asStateFlow()

    fun setCalibrationDuration(role: AgentRole, minutes: Int) {
        if (_calibrationState.value.isRunning || _calibrationState.value.isBenchmarkRunning) return
        _calibrationDurations.value = _calibrationDurations.value.toMutableMap().apply {
            put(role, minutes.coerceIn(10, 120))
        }
    }

    private val _calibrationState = MutableStateFlow(CalibrationUiState())
    val calibrationState: StateFlow<CalibrationUiState> = _calibrationState.asStateFlow()
    private var calibrationJob: Job? = null
    private var benchmarkJob: Job? = null
    private var pauseRequested = false

    init {
        if (saceProgressStore.readCheckpoint() != null) {
            _calibrationState.value = _calibrationState.value.copy(
                canResume = true,
                currentCase = "Sessão anterior encontrada — pronta para continuar."
            )
        }
    }

    private suspend fun awaitSafeSaceConditions() {
        val startedWaiting = System.currentTimeMillis()
        while (true) {
            val profile = container.deviceProfiler.captureProfile()
            val settings = container.settingsRepository.settingsFlow.first()
            val thermal = profile.thermalStatusLabel
            // The app-level thermal guard is optional; Android firmware thermal protection remains active.
            val severeThermal = settings.thermalGuardEnabled && container.deviceProfiler.isThermalSevereOrCritical()
            val lowMemory = container.deviceProfiler.isSystemLowMemory() ||
                profile.availableRamMb < settings.systemRamReserveMb + 64L
            if (!severeThermal && !lowMemory) {
                _calibrationState.value = _calibrationState.value.copy(thermalMessage = null)
                if (settings.thermalGuardEnabled && (thermal.contains("leve", ignoreCase = true) || thermal.contains("Moderado", ignoreCase = true))) delay(4_000L)
                return
            }
            val reason = buildList {
                if (severeThermal) add("temperatura elevada ($thermal)")
                if (lowMemory) add("RAM livre abaixo da reserva configurada (${settings.systemRamReserveMb} MB)")
            }.joinToString(" e ")
            _calibrationState.value = _calibrationState.value.copy(thermalMessage = "Treinamento em espera: $reason.")
            if (System.currentTimeMillis() - startedWaiting >= 180_000L) {
                throw IllegalStateException("SACE pausado automaticamente: $reason. Libere memória ou aguarde o aparelho estabilizar e retome.")
            }
            delay(15_000L)
        }
    }

    private suspend fun evaluateCalibrationAnswer(
        role: AgentRole,
        exercise: String,
        answer: String,
        imagePath: String? = null
    ): SaceEvaluation? {
        val reviewPrompt = SaceAnswerEvaluator.buildPrompt(role.displayName, exercise, answer)
        val reviewTurns = listOf(ChatTurn("user", reviewPrompt, imagePath = if (role == AgentRole.VISION) imagePath else null))
        val reviewResult = when (role) {
            AgentRole.CODING -> container.codingAgent.executeBlocking(reviewTurns, "SACE_SECOND_PASS_REVIEW")
            AgentRole.REASONING -> container.reasoningAgent.executeBlocking(
                messages = reviewTurns,
                source = "SACE_SECOND_PASS_REVIEW",
                temperatureOverride = 0.0f,
                maxTokensOverride = 180
            )
            AgentRole.VISION -> container.visionAgent.executeBlocking(reviewTurns, "SACE_SECOND_PASS_REVIEW")
        }
        val review = reviewResult.getOrNull()?.text ?: return null
        return SaceAnswerEvaluator.parse(review)
    }

    fun startCalibration(resume: Boolean = false) {
        if (_calibrationState.value.isRunning || _calibrationState.value.isBenchmarkRunning) return
        if (calibrationJob?.isCompleted == false || benchmarkJob?.isCompleted == false) return
        pauseRequested = false
        calibrationJob = viewModelScope.launch {
            val savedCheckpoint = if (resume) saceProgressStore.readCheckpoint() else null
            val roles = _selectedCalibrationRoles.value.sortedBy { it.ordinal }
            if (roles.isEmpty()) {
                _calibrationState.value = _calibrationState.value.copy(
                    isRunning = false,
                    error = "Selecione pelo menos uma IA para iniciar a calibração."
                )
                return@launch
            }
            val cases = mapOf(
                AgentRole.CODING to listOf(
                    "NEBULA OS — Arquitetura do sistema: proponha a divisão entre kernel Linux, serviços de sistema, daemon de sessão, interface gráfica e aplicativos. Defina responsabilidades e limites entre módulos.",
                    "NEBULA OS — Central de comando: projete um painel administrativo para iniciar/parar serviços, consultar status, visualizar logs e confirmar ações críticas. Escreva a estrutura de componentes.",
                    "NEBULA OS — GUI desktop: implemente em Kotlin/Compose uma janela com barra superior, dock, painel de configurações e navegação por teclado. Separe estado e componentes.",
                    "NEBULA OS — Aparência: crie um sistema de temas com modo claro/escuro, transparência configurável, escala, tipografia e persistência de preferências.",
                    "NEBULA OS — Gerenciador de arquivos: escreva em Kotlin a lógica de navegação por diretórios, seleção, operações assíncronas e tratamento de permissões/erros.",
                    "NEBULA OS — Serviço de sistema: escreva um serviço Linux em C que leia uma configuração, valide entradas, registre eventos e encerre corretamente. Inclua tratamento de erro.",
                    "NEBULA OS — Comunicação: crie um contrato IPC entre a GUI e um serviço de sistema. Defina mensagens, códigos de erro, timeout e validação.",
                    "NEBULA OS — Inicialização: desenhe o fluxo de boot desde o kernel até a sessão gráfica. Implemente um verificador simples de dependências em C.",
                    "NEBULA OS — Gerenciador de processos: implemente em C um utilitário que leia informações de processos no Linux via /proc, trate arquivos ausentes e apresente uso de recursos.",
                    "NEBULA OS — Atualizações: projete um atualizador que verifica versão, integridade, espaço disponível e possibilidade de rollback antes de aplicar mudanças.",
                    "NEBULA OS — Configurações: implemente armazenamento versionado de preferências, migração de configuração antiga e recuperação de valores inválidos.",
                    "NEBULA OS — Terminal: construa a arquitetura de um terminal integrado à GUI, com execução isolada de comandos permitidos, histórico e tratamento de saída.",
                    "NEBULA OS — Segurança: revise permissões de serviços, separação de privilégios e validação de entradas. Proponha testes sem executar ações destrutivas.",
                    "NEBULA OS — Desempenho: analise uma GUI lenta em hardware limitado. Separe custo de renderização, I/O, processos em segundo plano e consumo de RAM.",
                    "NEBULA OS — Testes: escreva testes unitários para um módulo de configurações e testes de integração para GUI ↔ serviço, incluindo falhas e timeout.",
                    "NEBULA OS — Integração realista: receba requisitos para uma central de comando e entregue árvore de arquivos, interfaces Kotlin, serviço C e critérios de aceitação.",
                    "GITHUB — Identifique se uma URL pertence realmente a github.com ou é um domínio parecido. Explique por que domínio reconhecido não garante que o arquivo ou repositório seja seguro.",
                    "GITHUB — Prepare um git clone de um repositório público oficial para uma pasta nova no macOS, sem executar scripts nem sobrescrever arquivos existentes. Informe diretório, comando e resultado esperado.",
                    "GITHUB — Prepare o download de um asset de release usando GitHub CLI, indicando owner/repo, tag, nome do arquivo e pasta de destino. Não use download seguido de execução.",
                    "GITHUB — Após baixar um arquivo, proponha verificação SHA-256 e, quando disponível, verificação de release, asset ou atestação. Explique o que cada verificação comprova e o que não comprova.",
                    "GITHUB — Analise um projeto com README, scripts de instalação e dependências. Liste o que precisa ser inspecionado antes de executar qualquer arquivo baixado.",
                    "TERMINAL — Diferencie comando sugerido, comando realmente executado e resultado confirmado. Se não houver retorno do terminal do computador, não declare o download concluído."
                ),
                AgentRole.VISION to listOf(
                    "NEBULA OS — Reconhecimento de desktop: analise a captura e identifique barra superior, dock, menu de aplicativos, janelas, painel e área de trabalho. Separe observação de hipótese.",
                    "NEBULA OS — Direção visual: avalie se a interface se aproxima visualmente de Linux desktop, macOS ou Windows. Justifique por elementos visíveis, sem afirmar que é um desses sistemas.",
                    "NEBULA OS — Design de central de comando: avalie hierarquia de status, serviços, logs, alertas e controles. Proponha uma organização visual mais clara.",
                    "NEBULA OS — GUI de configurações: examine agrupamento de categorias, navegação, controles, estados selecionados e consistência entre telas.",
                    "NEBULA OS — Linguagem visual: compare transparência, sombras, bordas, cantos, tipografia, ícones e espaçamento. Diferencie o que está visível do que é inferido.",
                    "NEBULA OS — Gerenciador de arquivos: avalie árvore lateral, breadcrumbs, lista/grade de arquivos, ações e estados vazios. Proponha melhorias específicas.",
                    "NEBULA OS — Gerenciador de janelas: analise barra de título, botões de janela, foco, sobreposição e organização de múltiplas janelas.",
                    "NEBULA OS — Tela de boot e login: avalie legibilidade, identidade visual, hierarquia e clareza dos estados de inicialização.",
                    "NEBULA OS — Dashboard: avalie gráficos de CPU, RAM, armazenamento e rede. Verifique se unidades, rótulos e estados são compreensíveis.",
                    "NEBULA OS — Acessibilidade: procure baixo contraste, textos pequenos, controles difíceis de distinguir e dependência exclusiva de cor.",
                    "NEBULA OS — Comparação de versões: compare duas capturas e liste somente alterações visuais comprováveis de layout, tamanho, cor e estado.",
                    "NEBULA OS — Especificação para Coding AI: transforme a captura em uma descrição implementável com componentes, relações espaciais, estilo e comportamento visível.",
                    "NEBULA OS — Consistência: compare menu, dock e painel de configurações e identifique divergências na linguagem visual.",
                    "NEBULA OS — Diagnóstico visual: encontre sobreposição, corte, desalinhamento ou excesso de elementos e indique região e evidência.",
                    "NEBULA OS — Protótipo: descreva como reconstruir a tela em componentes reutilizáveis, incluindo estados normal, selecionado, desativado e carregando.",
                    "NEBULA OS — Revisão final: produza uma auditoria visual da interface como produto de sistema operacional, priorizando os três problemas mais importantes."
                ),
                AgentRole.REASONING to listOf(
                    "NEBULA OS — Pensamento em fases: decomponha a criação de um sistema operacional em requisitos, arquitetura, protótipo, implementação, integração e validação. Mostre dependências.",
                    "NEBULA OS — Fundamentos: explique em linguagem simples a diferença entre kernel, processo, thread, serviço, driver, shell e ambiente desktop.",
                    "NEBULA OS — Código básico: analise um programa C que lê um arquivo de configuração. Identifique riscos de ponteiro, validação, memória e retorno de erro.",
                    "NEBULA OS — Fluxo de inicialização: raciocine sobre a sequência firmware, bootloader, kernel, init, serviços e sessão gráfica. Aponte o que varia entre distribuições.",
                    "NEBULA OS — Diagnóstico: a central de comando mostra um serviço como ativo, mas a GUI não recebe dados. Liste hipóteses e escolha testes que diferenciem as causas.",
                    "NEBULA OS — Arquitetura: compare implementar a GUI separada dos serviços versus concentrar tudo em um processo. Considere isolamento, manutenção e falhas.",
                    "NEBULA OS — Memória: explique RAM, memória virtual, swap, cache e OOM. Analise como um desktop deve reagir sob pouca memória.",
                    "NEBULA OS — Sistemas de arquivos: compare diretórios, permissões, links, montagem e espaço disponível. Resolva um cenário de erro de acesso.",
                    "NEBULA OS — Redes: planeje como o sistema exibirá estado Wi-Fi, IP, DNS e conectividade sem confundir conexão local com acesso à internet.",
                    "NEBULA OS — Raciocínio com evidências: receba logs incompletos de falha no boot. Separe fatos, hipóteses e informações ainda necessárias.",
                    "NEBULA OS — Código C: explique passo a passo um programa pequeno com structs, funções, ponteiros e tratamento de erro; depois proponha testes de limite.",
                    "NEBULA OS — Segurança: avalie por que a GUI não deve executar qualquer ação privilegiada diretamente. Proponha separação de privilégios e confirmação.",
                    "NEBULA OS — Planejamento técnico: transforme uma ideia de dock, menu e central de comando em tarefas pequenas, ordenadas e verificáveis.",
                    "NEBULA OS — Integração: Coding AI propõe uma API e Vision AI identifica um componente visual incompatível. Compare contratos e determine o que precisa ser ajustado.",
                    "NEBULA OS — Pensamento crítico: uma tela parece indicar que o serviço iniciou, mas não há log. Explique por que aparência não comprova funcionamento e como verificar.",
                    "NEBULA OS — Revisão de projeto: avalie uma proposta de OS com kernel Linux, serviços em C e GUI Kotlin. Identifique riscos, dependências, etapas e critérios de conclusão.",
                    "SEGURANÇA DE LINKS — Compare github.com, github.com.exemplo.net e um link encurtado. Identifique o hostname real e classifique o nível de confiança sem declarar o conteúdo seguro.",
                    "GITHUB — Avalie se um repositório público é uma fonte adequada para uma dependência. Considere proprietário, documentação, histórico, releases, tags e sinais de manutenção.",
                    "GITHUB — Diferencie checksum SHA-256, assinatura digital e atestação de proveniência. Explique por que um hash publicado no mesmo local do arquivo não prova sozinho a identidade do autor.",
                    "DOWNLOAD — Planeje um fluxo seguro para obter um asset de release: validar URL, confirmar projeto e versão, baixar sem executar, verificar integridade/proveniência e inspecionar o conteúdo.",
                    "WIKIPÉDIA — Use a Wikipédia como ponto de partida para conhecimento geral, mas procure referências primárias para decisões técnicas, segurança, compatibilidade e versões.",
                    "TERMINAL REMOTO — Determine se existe uma ferramenta real conectada ao computador. Se não existir, diferencie instruções de terminal de execução comprovada e não invente caminho, hash ou saída."
                )
            )
            val restoredModes = savedCheckpoint?.selectedModes?.mapNotNull { (roleId, modeName) ->
                val role = roles.firstOrNull { it.id == roleId }
                val mode = runCatching { CalibrationMode.valueOf(modeName) }.getOrNull()
                if (role != null && mode != null) role to mode else null
            }?.toMap().orEmpty()
            val restoredDurations = savedCheckpoint?.selectedDurations?.mapNotNull { (roleId, minutes) ->
                roles.firstOrNull { it.id == roleId }?.let { it to minutes.coerceIn(10, 120) }
            }?.toMap().orEmpty()
            val selectedDurations = _calibrationDurations.value.toMutableMap().apply { putAll(restoredDurations) }
            val selectedModes = _calibrationModes.value.toMutableMap().apply { putAll(restoredModes) }
            if (savedCheckpoint != null) {
                _calibrationDurations.value = selectedDurations
                _calibrationModes.value = selectedModes
            }
            val sacePrefs = getApplication<Application>().getSharedPreferences("sace_progress", 0)
            val sessionNumber = savedCheckpoint?.sessionNumber?.takeIf { it > 0 } ?: saceProgressStore.beginSession()
            val isSurpriseSession = sessionNumber % 5 == 0
            val report = StringBuilder("SACE — sessão #$sessionNumber")
            var completed = 0
            val total = 0 // Time-based calibration: exercises repeat until each selected agent reaches its target.
            _calibrationState.value = CalibrationUiState(isRunning = true, totalCases = total)
            var taskStarted = false
            try {
                InferenceTaskForegroundService.startTask(getApplication())
                taskStarted = true
                val resumeRoleIndex = savedCheckpoint?.let { cp -> roles.indexOfFirst { it.id == cp.roleId }.coerceAtLeast(0) } ?: 0
                for (role in roles.drop(resumeRoleIndex)) {
                    val isResumingThisRole = savedCheckpoint?.roleId == role.id
                    val mode = if (isResumingThisRole) runCatching { CalibrationMode.valueOf(savedCheckpoint!!.mode) }.getOrDefault(selectedModes[role] ?: CalibrationMode.NORMAL) else (selectedModes[role] ?: CalibrationMode.NORMAL)
                    val durationMinutes = if (mode == CalibrationMode.ULTIMATE) 300 else selectedDurations.getValue(role)
                    // Only active calibration time counts; time while the app is paused/offline is excluded.
                    var roleStartedAt = if (isResumingThisRole) {
                        System.currentTimeMillis() - savedCheckpoint!!.elapsedMs.coerceAtLeast(0L)
                    } else {
                        System.currentTimeMillis()
                    }
                    report.append("\n\n").append(role.displayName).append(" — ").append(mode.title)
                        .append(": meta de ").append(durationMinutes).append(" min.")
                    _calibrationState.value = _calibrationState.value.copy(
                        currentRole = role, currentCase = "Preparando " + role.displayName,
                        currentElapsedMs = (System.currentTimeMillis() - roleStartedAt).coerceAtLeast(0L),
                        targetDurationMs = durationMinutes * 60_000L
                    )
                    val roleNotes = StringBuilder()
                    val pool = cases.getValue(role)
                    // Every mode rotates through the complete role-specific curriculum.
                    // Difficulty is expressed in the task prompt, not by silently dropping most topics.
                    val selectedPool = pool
                    val initialOffset = sacePrefs.getInt("next_${role.id}", 0).coerceAtLeast(0)
                    var attempt = if (isResumingThisRole) savedCheckpoint!!.nextAttempt else 0
                    val exerciseBaseOffset = if (isResumingThisRole) initialOffset - attempt else initialOffset
                    var consecutiveFailures = 0
                    while (System.currentTimeMillis() - roleStartedAt < durationMinutes * 60_000L) {
                        val cycle = attempt / selectedPool.size
                        val exerciseIndex = saceProgressStore.chooseExerciseIndex(
                            role.id, selectedPool.size, exerciseBaseOffset + attempt
                        )
                        val basePrompt = selectedPool[exerciseIndex]
                        val isLinuxFinalHour = role == AgentRole.CODING &&
                            mode == CalibrationMode.ULTIMATE &&
                            System.currentTimeMillis() - roleStartedAt >= 4 * 60 * 60 * 1000L
                        val ultimateHour = ((System.currentTimeMillis() - roleStartedAt) / 3_600_000L).toInt().coerceIn(0, 4)
                        val reasoningStage = listOf(
                            "ETAPA 1/5 — lógica, dedução, padrões e hipóteses.",
                            "ETAPA 2/5 — tecnologia: hardware, CPU, RAM, redes e sistemas operacionais.",
                            "ETAPA 3/5 — investigação de falhas e análise de sistemas.",
                            "ETAPA 4/5 — colaboração: dividir tarefas e revisar resultados da Coding AI e Vision AI sem concordar automaticamente.",
                            "ETAPA 5/5 — desafio final: analisar um projeto complexo, apontar incertezas e montar um plano verificável."
                        )
                        val visionStage = listOf(
                            "ETAPA 1/5 — reconhecer elementos e componentes que estejam realmente visíveis.",
                            "ETAPA 2/5 — analisar interfaces, hierarquia e organização.",
                            "ETAPA 3/5 — leitura de texto legível e relações espaciais; não adivinhe conteúdo ilegível.",
                            "ETAPA 4/5 — comparar regiões e identificar erros visuais.",
                            "ETAPA 5/5 — desafio final: produzir uma especificação visual clara para a Coding AI."
                        )
                        val codingStage = listOf(
                            "ETAPA 1/5 — firmware: C/C++, ESP32, sensores, GPIO, comunicação e estados seguros.",
                            "ETAPA 2/5 — interfaces: menus navegáveis, opções, configurações persistentes e validação.",
                            "ETAPA 3/5 — depuração: localizar causas, corrigir erros e criar testes de regressão.",
                            "ETAPA 4/5 — integração: organizar um projeto completo em módulos e verificar os contratos entre eles.",
                            "ETAPA 5/5 — Linux: analisar somente o projeto de laboratório fornecido, revisar scripts e serviços e testar melhorias em ambiente isolado."
                        )
                        val stagePrefix = when {
                            mode != CalibrationMode.ULTIMATE -> ""
                            role == AgentRole.CODING -> codingStage[ultimateHour] + " "
                            role == AgentRole.REASONING -> reasoningStage[ultimateHour] + " "
                            role == AgentRole.VISION -> visionStage[ultimateHour] + " "
                            else -> ""
                        }
                        val modeGuidance = when (mode) {
                            CalibrationMode.NORMAL ->
                                "MODO NORMAL: resolva o exercício de forma gradual. Mostre os fundamentos necessários, use palavras simples e explique somente o que ajuda a entender a solução."
                            CalibrationMode.ADVANCED -> when (role) {
                                AgentRole.CODING ->
                                    "MODO AVANÇADO: trate o exercício como parte de um projeto real. Considere arquitetura, módulos, compatibilidade, tratamento de erros, estados de borda, testes e manutenção. Entregue uma solução completa, mas sem criar complexidade desnecessária."
                                AgentRole.REASONING ->
                                    "MODO AVANÇADO: compare pelo menos duas abordagens viáveis, avalie dependências, riscos e consequências. Escolha uma opção com justificativa e defina como verificar se ela funcionou."
                                AgentRole.VISION ->
                                    "MODO AVANÇADO: faça uma auditoria detalhada da interface. Avalie hierarquia, alinhamento, espaçamento, tipografia, contraste, estados e consistência; transforme os achados em mudanças visuais implementáveis."
                            }
                            CalibrationMode.ANALYSIS -> when (role) {
                                AgentRole.CODING ->
                                    "MODO ANÁLISE: não comece escrevendo a solução. Investigue requisitos, fluxo de dados, estrutura, dependências e possíveis falhas. Separe fatos de hipóteses, indique a causa mais provável e proponha testes que confirmem ou descartem cada hipótese."
                                AgentRole.REASONING ->
                                    "MODO ANÁLISE: decomponha o problema em fatos, incógnitas, hipóteses e relações causais. Procure contradições, compare evidências e explique qual informação ou teste reduziria mais a incerteza. Não aceite a primeira hipótese automaticamente."
                                AgentRole.VISION ->
                                    "MODO ANÁLISE: examine a imagem por regiões. Registre apenas elementos realmente visíveis, relações espaciais, estados e inconsistências. Separe observação, interpretação e dúvida; não proponha mudanças antes de identificar evidências."
                            }
                            CalibrationMode.CORRECTION -> when (role) {
                                AgentRole.CODING ->
                                    "MODO CORREÇÃO: trate a proposta, código ou arquitetura como possivelmente defeituosa. Localize erros concretos, explique a causa, apresente a versão corrigida e inclua testes de regressão. Não altere partes sem relação com o defeito."
                                AgentRole.REASONING ->
                                    "MODO CORREÇÃO: revise criticamente a conclusão ou plano. Encontre saltos lógicos, premissas sem prova, cálculos ou prioridades incorretos; corrija cada ponto e apresente uma conclusão coerente com as evidências."
                                AgentRole.VISION ->
                                    "MODO CORREÇÃO: identifique defeitos visuais comprováveis, localize cada um na tela e descreva a correção específica. Preserve os elementos que já funcionam e não invente defeitos que a imagem não mostra."
                            }
                            CalibrationMode.ULTIMATE ->
                                "MODO ULTIMATE: trabalhe como um desafio de projeto em etapas. Integre os conhecimentos da etapa atual, confira dependências e apresente critérios verificáveis."
                        }
                        val repeatedChallenge = if (cycle == 0) "" else
                            "Crie uma variação nova deste desafio, mudando dados, nomes, restrições ou cenário. Resolva a nova versão; não copie a resposta anterior."
                        val taskPrompt = if (isLinuxFinalHour) {
                            "DESAFIO FINAL LINUX: use somente o projeto de laboratório e os arquivos que forem fornecidos nesta tarefa. " +
                                "Mapeie a estrutura, analise scripts, serviços, permissões e dependências; encontre erros; proponha melhorias; " +
                                "reescreva apenas módulos selecionados e crie testes. Não afirme que analisou uma distribuição inteira nem que executou comandos. " +
                                "Tarefa: " + basePrompt
                        } else {
                            basePrompt
                        }
                        val prompt = listOf(stagePrefix.trim(), modeGuidance, taskPrompt, repeatedChallenge)
                            .filter { it.isNotBlank() }
                            .joinToString("\n\n")
                        _calibrationState.value = _calibrationState.value.copy(
                            currentCase = role.displayName + " (" + mode.title + "): exercício " + (attempt + 1) +
                                if (mode == CalibrationMode.ULTIMATE) " — ciclo " + (cycle + 1) else "",
                            latestOutput = "", error = null
                        )
                        saceProgressStore.saveCheckpoint(
                            roleId = role.id, mode = mode.name, nextAttempt = attempt,
                            startedAt = roleStartedAt, sessionNumber = sessionNumber,
                            selectedModes = selectedModes.mapKeys { it.key.id }.mapValues { it.value.name },
                            selectedDurations = selectedDurations.mapKeys { it.key.id },
                            elapsedMs = (System.currentTimeMillis() - roleStartedAt).coerceAtLeast(0L)
                        )
                        val safeWaitStartedAt = System.currentTimeMillis()
                        awaitSafeSaceConditions()
                        // Safety waits do not count toward the requested training duration.
                        roleStartedAt += System.currentTimeMillis() - safeWaitStartedAt
                        if (System.currentTimeMillis() - roleStartedAt >= durationMinutes * 60_000L) break
                        if (pauseRequested) throw kotlinx.coroutines.CancellationException("Pausa solicitada pelo usuário")
                        val calibrationImagePath = if (role == AgentRole.VISION) createCalibrationImage(attempt) else null
                        val turns = listOf(ChatTurn(
                            role = "user", text = prompt,
                            imagePath = calibrationImagePath
                        ))
                        var output = ""
                        var failure: String? = null
                        val stream = when (role) {
                            AgentRole.VISION -> container.visionAgent.streamExecute(turns, "CALIBRATION", maxTokensOverride = 240)
                            AgentRole.REASONING -> container.reasoningAgent.streamExecute(turns, "CALIBRATION", maxTokensOverride = 320)
                            AgentRole.CODING -> container.codingAgent.streamExecute(turns, "CALIBRATION", maxTokensOverride = 512)
                        }
                        stream.collect { event ->
                            when (event) {
                                is InferenceStreamEvent.Token -> output += event.text
                                is InferenceStreamEvent.Completed -> output = event.fullText.ifBlank { output }
                                is InferenceStreamEvent.Failed -> failure = event.errorMessage
                                else -> Unit
                            }
                            _calibrationState.value = _calibrationState.value.copy(
                                latestOutput = output.takeLast(3500), error = failure
                            )
                        }
                        val attemptSucceeded = failure == null && output.isNotBlank()
                        val evaluation = if (attemptSucceeded) {
                            try {
                                evaluateCalibrationAnswer(role, prompt, output, calibrationImagePath)
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                        val skillLabel = basePrompt.substringBefore(":").takeIf { it.length <= 80 } ?: "Geral"
                        saceProgressStore.recordAttempt(
                            roleId = role.id,
                            exerciseIndex = exerciseIndex,
                            skill = skillLabel,
                            completed = attemptSucceeded,
                            outputChars = output.length,
                            error = failure ?: if (output.isBlank()) "Resposta vazia" else null,
                            qualityScore = evaluation?.score,
                            evaluationConfidence = evaluation?.confidence,
                            evaluationFeedback = evaluation?.feedback
                        )
                        saceProgressStore.saveCheckpoint(
                            roleId = role.id,
                            mode = mode.name,
                            nextAttempt = attempt + 1,
                            startedAt = roleStartedAt,
                            sessionNumber = sessionNumber,
                            selectedModes = selectedModes.mapKeys { it.key.id }.mapValues { it.value.name },
                            selectedDurations = selectedDurations.mapKeys { it.key.id },
                            elapsedMs = (System.currentTimeMillis() - roleStartedAt).coerceAtLeast(0L)
                        )
                        if (failure != null || output.isBlank()) {
                            report.append("\n• ").append(role.displayName).append(" — falha: ").append(failure ?: "resposta vazia")
                            consecutiveFailures++
                            if (consecutiveFailures >= 3) {
                                throw IllegalStateException("A calibração de ${role.displayName} falhou três vezes seguidas. Confira se há um modelo compatível associado e tente novamente.")
                            }
                            delay(1_500L)
                        } else {
                            consecutiveFailures = 0
                            report.append("\n• ").append(role.displayName).append(" — exercício ")
                                .append(attempt + 1).append(": inferência concluída")
                            if (evaluation != null) {
                                report.append(", avaliação estimada ").append(evaluation.score).append("/100")
                                    .append(" (confiança ").append(evaluation.confidence).append("%): ")
                                    .append(evaluation.feedback)
                                if (evaluation.score >= 70 && evaluation.confidence >= 55) {
                                    roleNotes.append("\n[Exemplo aprovado em segunda revisão — não é prova de correção] ")
                                        .append(prompt.take(180)).append("\n[Resposta] ")
                                        .append(output.take(360)).append("\n")
                                }
                            } else {
                                report.append("; avaliação indisponível, sem nota")
                            }
                            report.append(".")
                        }
                        if (attemptSucceeded) completed++
                        attempt++
                        sacePrefs.edit().putInt("next_${role.id}", (exerciseBaseOffset + attempt) % selectedPool.size).apply()
                        _calibrationState.value = _calibrationState.value.copy(
                            completedCases = completed,
                            currentElapsedMs = (System.currentTimeMillis() - roleStartedAt).coerceAtLeast(0L),
                            targetDurationMs = durationMinutes * 60_000L
                        )
                        if (mode == CalibrationMode.ULTIMATE && attempt % 10 == 0) kotlinx.coroutines.delay(20_000L)
                    }
                    report.append("\n• ").append(role.displayName).append(": ")
                        .append(attempt).append(" tentativas nesta sessão.")
                    report.append("\n").append(saceProgressStore.summaryForRole(role.id))

                    if (roleNotes.isNotBlank()) {
                        val dao = container.database.agentConfigDao()
                        val cfg = dao.getByRole(role.id) ?: AgentConfigEntity(roleId = role.id)
                        val prior = cfg.systemPromptOverride.orEmpty().substringBefore("\n\n[AI CALIBRATION EXAMPLES]")
                        val block = roleNotes.toString().take(1250)
                        val updated = (prior + "\n\n[AI CALIBRATION EXAMPLES]\nUse os exemplos anteriores como referências, sem copiar respostas cegamente; priorize correção e evidências na tarefa atual.\n" + block).trim()
                        dao.upsert(cfg.copy(systemPromptOverride = updated.take(5000)))
                    }
                }
                if (isSurpriseSession) {
                    report.append("\n\nTESTE SURPRESA — sessão múltipla de 5")
                    val surprisePrompts = mapOf(
                        AgentRole.CODING to "Desafio inédito: crie uma máquina de estados simples para um firmware ESP32 que controla um menu, salva uma configuração e lida com falha de sensor. Defina testes de aceitação.",
                        AgentRole.REASONING to "Desafio inédito: dois relatos sobre um computador discordam sobre quando a falha começou. Monte hipóteses, diga quais dados faltam e escolha o teste mais útil sem tratar suposições como fatos.",
                        AgentRole.VISION to "Analise a captura fornecida e transforme a organização visual em uma descrição curta de componentes, agrupamentos e alinhamentos. Informe claramente o que a imagem não permite confirmar."
                    )
                    for (role in roles) {
                        _calibrationState.value = _calibrationState.value.copy(
                            currentRole = role,
                            currentCase = role.displayName + ": teste surpresa sem exemplos prévios",
                            latestOutput = "",
                            error = null
                        )
                        val turns = listOf(ChatTurn(
                            role = "user",
                            text = surprisePrompts.getValue(role),
                            imagePath = if (role == AgentRole.VISION) createCalibrationImage(sessionNumber + 100) else null
                        ))
                        var output = ""
                        var failure: String? = null
                        val stream = when (role) {
                            AgentRole.VISION -> container.visionAgent.streamExecute(turns, "SACE_SURPRISE", maxTokensOverride = 220)
                            AgentRole.REASONING -> container.reasoningAgent.streamExecute(turns, "SACE_SURPRISE", maxTokensOverride = 280)
                            AgentRole.CODING -> container.codingAgent.streamExecute(turns, "SACE_SURPRISE", maxTokensOverride = 400)
                        }
                        stream.collect { event ->
                            when (event) {
                                is InferenceStreamEvent.Token -> output += event.text
                                is InferenceStreamEvent.Completed -> output = event.fullText.ifBlank { output }
                                is InferenceStreamEvent.Failed -> failure = event.errorMessage
                                else -> Unit
                            }
                            _calibrationState.value = _calibrationState.value.copy(
                                latestOutput = output.takeLast(2500), error = failure
                            )
                        }
                        val surpriseSucceeded = failure == null && output.isNotBlank()
                        saceProgressStore.recordAttempt(
                            roleId = role.id,
                            exerciseIndex = 1000 + sessionNumber,
                            skill = "Teste surpresa",
                            completed = surpriseSucceeded,
                            outputChars = output.length,
                            error = failure ?: if (output.isBlank()) "Resposta vazia" else null
                        )
                        sacePrefs.edit()
                            .putString("surprise_${role.id}", output.take(600))
                            .putLong("surprise_time_${role.id}", System.currentTimeMillis())
                            .apply()
                        report.append("\n• ").append(role.displayName)
                            .append(if (surpriseSucceeded) ": resposta registrada para avaliação." else ": teste não concluído — " + failure)
                        if (surpriseSucceeded) completed++
                        _calibrationState.value = _calibrationState.value.copy(completedCases = completed)
                    }
                }
                saceProgressStore.clearCheckpoint()
                _calibrationState.value = _calibrationState.value.copy(
                    isRunning = false, currentRole = null, currentCase = "Calibração concluída", canResume = false, isPaused = false,
                    report = "Sessão concluída. Respostas geradas sem erro de inferência foram registradas como exemplos. Isso não significa que cada solução foi validada como correta.\n" + report
                )
                _uiBannerMessage.value = "AI Calibration Lab: sessão concluída. Perfis de referência atualizados."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _calibrationState.value = _calibrationState.value.copy(
                    isRunning = false, isPaused = true, canResume = saceProgressStore.readCheckpoint() != null,
                    currentCase = "Sessão pausada — o próximo exercício foi salvo."
                )
                throw cancelled
            } catch (error: Exception) {
                _calibrationState.value = _calibrationState.value.copy(
                    isRunning = false, isPaused = false, canResume = saceProgressStore.readCheckpoint() != null,
                    currentCase = "Sessão interrompida com checkpoint salvo.",
                    error = error.message ?: "Falha inesperada na calibração."
                )
            } finally {
                withContext(NonCancellable) {
                    runCatching { container.inferenceRuntimeManager.unloadAllModels("Fim da sessão de calibração") }
                    if (taskStarted) runCatching { InferenceTaskForegroundService.finishTask(getApplication()) }
                }
            }
        }
    }


    /**
     * Runs a fixed evaluation set kept separate from calibration/adaptive history.
     * Scores are second-pass estimates from the same agent, not independent ground truth.
     */
    fun runSaceBenchmark() {
        if (_calibrationState.value.isRunning || _calibrationState.value.isBenchmarkRunning) return
        if (calibrationJob?.isCompleted == false || benchmarkJob?.isCompleted == false) return
        benchmarkJob = viewModelScope.launch {
            val cases = SaceBenchmarkDataset.cases
            val benchmarkRunId = saceBenchmarkStore.beginRun()
            _calibrationState.value = _calibrationState.value.copy(
                isBenchmarkRunning = true, benchmarkProgress = 0,
                benchmarkReport = "", benchmarkError = null, thermalMessage = null
            )
            var taskStarted = false
            try {
                InferenceTaskForegroundService.startTask(getApplication())
                taskStarted = true
                cases.forEachIndexed { index, testCase ->
                    awaitSafeSaceConditions()
                    val role = when (testCase.roleId) {
                        "coding" -> AgentRole.CODING
                        "reasoning" -> AgentRole.REASONING
                        else -> AgentRole.VISION
                    }
                    val imagePath = if (role == AgentRole.VISION) createCalibrationImage(index + 40) else null
                    val turns = listOf(ChatTurn("user", testCase.prompt, imagePath = imagePath))
                    var output = ""
                    var failure: String? = null
                    val stream = when (role) {
                        AgentRole.CODING -> container.codingAgent.streamExecute(turns, "SACE_BENCHMARK", maxTokensOverride = 420)
                        AgentRole.REASONING -> container.reasoningAgent.streamExecute(turns, "SACE_BENCHMARK", maxTokensOverride = 300)
                        AgentRole.VISION -> container.visionAgent.streamExecute(turns, "SACE_BENCHMARK", maxTokensOverride = 240)
                    }
                    stream.collect { event ->
                        when (event) {
                            is InferenceStreamEvent.Token -> output += event.text
                            is InferenceStreamEvent.Completed -> output = event.fullText.ifBlank { output }
                            is InferenceStreamEvent.Failed -> failure = event.errorMessage
                            else -> Unit
                        }
                    }
                    val succeeded = failure == null && output.isNotBlank()
                    val evaluation = if (succeeded) {
                        runCatching { evaluateCalibrationAnswer(role, testCase.prompt, output, imagePath) }.getOrNull()
                    } else null
                    saceBenchmarkStore.record(
                        SaceBenchmarkResult(
                            caseId = testCase.id, roleId = testCase.roleId, skill = testCase.skill,
                            completed = succeeded, score = evaluation?.score, confidence = evaluation?.confidence,
                            feedback = evaluation?.feedback ?: failure.orEmpty(),
                            outputPreview = output.take(400), timestamp = System.currentTimeMillis(), runId = benchmarkRunId
                        )
                    )
                    _calibrationState.value = _calibrationState.value.copy(
                        benchmarkProgress = index + 1,
                        benchmarkReport = saceBenchmarkStore.summary()
                    )
                }
                saceBenchmarkStore.completeRun(benchmarkRunId)
                _calibrationState.value = _calibrationState.value.copy(
                    isBenchmarkRunning = false, benchmarkReport = saceBenchmarkStore.summary(),
                    benchmarkError = null, thermalMessage = null
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _calibrationState.value = _calibrationState.value.copy(
                    isBenchmarkRunning = false, benchmarkError = "Benchmark interrompido."
                )
                throw cancelled
            } catch (error: Exception) {
                _calibrationState.value = _calibrationState.value.copy(
                    isBenchmarkRunning = false,
                    benchmarkReport = saceBenchmarkStore.summary(),
                    benchmarkError = error.message ?: "Falha ao executar benchmark."
                )
            } finally {
                withContext(NonCancellable) {
                    runCatching { container.inferenceRuntimeManager.unloadAllModels("Fim do SACE Benchmark") }
                    if (taskStarted) runCatching { InferenceTaskForegroundService.finishTask(getApplication()) }
                }
            }
        }
    }

    fun pauseCalibration() {
        if (!_calibrationState.value.isRunning) return
        pauseRequested = true
        calibrationJob?.cancel()
        // The cancelled job performs model/service cleanup in a NonCancellable finally block.
        // Keeping its Job reference prevents a new session from racing that cleanup.
    }

    fun resumeCalibration() {
        if (_calibrationState.value.isRunning || calibrationJob?.isCompleted == false ||
            benchmarkJob?.isCompleted == false || saceProgressStore.readCheckpoint() == null) return
        startCalibration(resume = true)
    }

    fun stopCalibration() = pauseCalibration()

    fun discardCalibrationCheckpoint() {
        if (_calibrationState.value.isRunning) return
        saceProgressStore.clearCheckpoint()
        _calibrationState.value = _calibrationState.value.copy(canResume = false, isPaused = false, currentCase = "Checkpoint descartado.")
    }

    private fun createCalibrationImage(variant: Int = 0): String {
        val safeVariant = ((variant % 6) + 6) % 6
        val file = File(getApplication<Application>().cacheDir, "calibration_ui_sample_$safeVariant.png")
        if (file.exists() && file.length() > 0) return file.absolutePath
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val pageTitles = listOf("Workspace", "Device monitor", "Project files", "Network status", "Preferences", "Task manager")
        val navItems = listOf("Dashboard", "Devices", "Files", "Network", "Settings", "Tasks")
        val cardTitles = listOf("System overview", "Device health", "Recent files", "Connection", "Display options", "Active tasks")
        val statusLines = listOf("3 agents online • 2 tasks", "CPU normal • RAM 68%", "12 files • 3 folders", "Wi-Fi connected • DNS ready", "Dark theme • Scale 100%", "2 running • 1 queued")
        val active = safeVariant % 3
        canvas.drawColor(Color.rgb(24, 28, 38))
        paint.color = Color.rgb(37, 43, 57); canvas.drawRoundRect(18f, 18f, 622f, 342f, 18f, 18f, paint)
        paint.color = Color.rgb(54, 62, 80); canvas.drawRoundRect(18f, 18f, 622f, 66f, 18f, 18f, paint)
        paint.color = Color.WHITE; paint.textSize = 18f; canvas.drawText("NEBULA • " + pageTitles[safeVariant], 42f, 48f, paint)
        paint.color = Color.rgb(47, 55, 72); canvas.drawRoundRect(32f, 82f, 174f, 326f, 12f, 12f, paint)
        paint.color = Color.rgb(110, 170, 245); canvas.drawRoundRect(46f, (102 + active * 42).toFloat(), 160f, (136 + active * 42).toFloat(), 8f, 8f, paint)
        paint.color = Color.LTGRAY; paint.textSize = 13f
        val navLabels = mutableListOf("Dashboard", "Projects", "Settings")
        navLabels[active] = navItems[safeVariant]
        navLabels.forEachIndexed { index, label ->
            canvas.drawText(label, 52f, (124 + index * 42).toFloat(), paint)
        }
        paint.color = Color.rgb(62, 73, 94); canvas.drawRoundRect(192f, 84f, 606f, 326f, 14f, 14f, paint)
        paint.color = Color.WHITE; paint.textSize = 21f; canvas.drawText("Welcome back", 218f, 124f, paint)
        paint.color = Color.rgb(93, 161, 226); canvas.drawRoundRect(218f, 150f, 568f, 224f, 12f, 12f, paint)
        paint.color = Color.WHITE; paint.textSize = 16f; canvas.drawText(cardTitles[safeVariant], 238f, 180f, paint)
        paint.color = Color.rgb(180, 195, 220); paint.textSize = 13f; canvas.drawText(statusLines[safeVariant], 238f, 204f, paint)
        paint.color = Color.rgb(82, 96, 120); canvas.drawRoundRect(218f, 242f, 380f, 302f, 10f, 10f, paint)
        canvas.drawRoundRect(396f, 242f, 568f, 302f, 10f, 10f, paint)
        if (safeVariant % 2 == 1) {
            paint.color = Color.rgb(235, 170, 90)
            canvas.drawCircle(548f, 104f, 7f, paint)
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    // --- Multi-Agent Orchestrator ---
    fun setMultiAgentImageUri(uri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            _multiAgentImageUri.value = uri
            _multiAgentImagePath.value = uri?.let { copyImageToCache(it, "multiagent_input") }
        }
    }

    fun runMultiAgentTask(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            InferenceTaskForegroundService.startTask(getApplication())
            try {
                container.multiAgentOrchestrator.executeMultiAgentTask(
                    userRequest = prompt.trim(),
                    imagePath = _multiAgentImagePath.value,
                    source = "UI"
                )
            } finally {
                InferenceTaskForegroundService.finishTask(getApplication())
            }
        }
    }

    fun cancelMultiAgentTask() {
        container.multiAgentOrchestrator.cancelActiveTask()
        _uiBannerMessage.value = "Cancelando orquestração multiagente..."
    }

    // --- Settings & Diagnostics ---
    fun updateServerPort(port: Int) {
        if (port !in 1024..65535) {
            _uiBannerMessage.value = "Porta inválida. Escolha entre 1024 e 65535."
            return
        }
        viewModelScope.launch {
            container.settingsRepository.updateServerPort(port)
            val currentStatus = serverStatus.value
            if (currentStatus.isRunning && currentStatus.port != port) {
                LocalApiForegroundService.stopServerService(getApplication())
                delay(500L)
                LocalApiForegroundService.startServerService(getApplication(), port)
                _uiBannerMessage.value = "Servidor reiniciado na porta $port."
            } else {
                _uiBannerMessage.value = "Porta HTTP atualizada para $port."
            }
        }
    }

    fun updateRamLimitMb(limitMb: Long) {
        viewModelScope.launch {
            container.settingsRepository.updateRamLimitMb(limitMb)
            container.memoryBudgetManager.updateUserRamLimitMb(limitMb)
            container.modelRepository.revalidateAllModels()
        }
    }

    fun updateSystemRamReserveMb(reserveMb: Long) {
        viewModelScope.launch {
            container.settingsRepository.updateSystemRamReserveMb(reserveMb)
            container.memoryBudgetManager.updateSystemRamReserveMb(reserveMb)
        }
    }

    fun updateLanOnlyMode(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.updateLanOnlyMode(enabled) }
    }

    fun updateRateLimit(perMin: Int) {
        viewModelScope.launch { container.settingsRepository.updateRateLimit(perMin) }
    }

    fun updateMaxRevisions(revisions: Int) {
        viewModelScope.launch { container.settingsRepository.updateMaxRevisions(revisions) }
    }

    fun updateMultiAgentTimeout(sec: Int) {
        viewModelScope.launch { container.settingsRepository.updateMultiAgentTimeoutSec(sec) }
    }

    fun updateAutoUnload(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.updateAutoUnload(enabled) }
    }

    fun updateIdleUnloadMinutes(minutes: Int) {
        viewModelScope.launch { container.settingsRepository.updateIdleUnloadMinutes(minutes) }
    }

    fun updateThermalGuard(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.updateThermalGuard(enabled) }
    }

    fun updateDefaultMaxTokens(tokens: Int) {
        viewModelScope.launch { container.settingsRepository.updateDefaultMaxTokens(tokens) }
    }

    fun updateAutoStartServer(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.updateAutoStartServer(enabled) }
    }

    fun updateDarkTheme(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.updateDarkTheme(enabled) }
    }

    fun clearTemporaryCaches() {
        viewModelScope.launch(Dispatchers.IO) {
            val cacheRoot = getApplication<Application>().cacheDir
            val dirs = listOf(File(cacheRoot, "api_images"), File(cacheRoot, "ui_images"))
            var deletedCount = 0
            dirs.forEach { dir ->
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.delete()) deletedCount++
                }
            }
            _uiBannerMessage.value = if (deletedCount > 0) {
                "$deletedCount arquivo(s) temporário(s) removidos do cache."
            } else {
                "Cache temporário já está limpo."
            }
        }
    }

    fun resetSettingsToDefaults() {
        viewModelScope.launch {
            container.settingsRepository.resetToDefaults()
            val defaults = AppSettings()
            container.memoryBudgetManager.updateUserRamLimitMb(defaults.ramLimitMb)
            container.memoryBudgetManager.updateSystemRamReserveMb(defaults.systemRamReserveMb)
            container.modelRepository.revalidateAllModels()
            _uiBannerMessage.value = "Configurações restauradas para o padrão de fábrica."
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            container.database.apiRequestLogDao().clearAll()
        }
    }

    fun runDiagnostics() {
        if (_isRunningDiagnostics.value) return
        viewModelScope.launch {
            _isRunningDiagnostics.value = true
            _diagnosticsResults.value = diagnosticsRunner.runFullDiagnostics()
            _isRunningDiagnostics.value = false
        }
    }

    private fun copyImageToCache(uri: Uri, prefix: String): String? {
        return runCatching {
            val dir = File(getApplication<Application>().cacheDir, "ui_images").apply { mkdirs() }
            val dest = File(dir, "${prefix}_${UUID.randomUUID().toString().take(8)}.jpg")
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        }.getOrNull()
    }

    private inline fun updateConsole(role: AgentRole, transform: (AgentConsoleState) -> AgentConsoleState) {
        val mutable = _agentConsoles.value.toMutableMap()
        val prev = mutable[role] ?: AgentConsoleState()
        mutable[role] = transform(prev)
        _agentConsoles.value = mutable
    }
}
