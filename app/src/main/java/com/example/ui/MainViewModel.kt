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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class AgentConsoleState(
    val isGenerating: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val selectedImageUri: Uri? = null,
    val selectedImageTempPath: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as LocalDevAiApplication).container

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
        if (prompt.isBlank()) return
        consoleJobs.remove(role)?.cancel()
        val job = viewModelScope.launch {
            val currentConsole = _agentConsoles.value[role] ?: AgentConsoleState()
            updateConsole(role) { it.copy(isGenerating = true, output = "", error = null) }
            InferenceTaskForegroundService.startTask(getApplication())

            try {
                val turns = listOf(
                    ChatTurn(
                        role = "user",
                        text = prompt.trim(),
                        imagePath = if (role == AgentRole.VISION) currentConsole.selectedImageTempPath else null
                    )
                )
                val stream = when (role) {
                    AgentRole.CODING -> container.codingAgent.streamExecute(turns, "UI")
                    AgentRole.VISION -> container.visionAgent.streamExecute(turns, "UI")
                    AgentRole.REASONING -> container.reasoningAgent.streamExecute(turns, "UI")
                }
                stream.collect { ev ->
                    when (ev) {
                        is InferenceStreamEvent.Token -> {
                            updateConsole(role) { it.copy(output = it.output + ev.text) }
                        }
                        is InferenceStreamEvent.Completed -> {
                            updateConsole(role) {
                                it.copy(isGenerating = false, output = ev.fullText.ifBlank { it.output })
                            }
                        }
                        is InferenceStreamEvent.Failed -> {
                            updateConsole(role) { it.copy(isGenerating = false, error = ev.errorMessage) }
                        }
                        else -> Unit
                    }
                }
            } finally {
                updateConsole(role) { it.copy(isGenerating = false) }
                InferenceTaskForegroundService.finishTask(getApplication())
            }
        }
        consoleJobs[role] = job
    }

    fun stopAgentConsole(role: AgentRole) {
        consoleJobs.remove(role)?.cancel()
        updateConsole(role) { it.copy(isGenerating = false) }
    }


    // --- AI Calibration Lab ---
    private val _calibrationState = MutableStateFlow(CalibrationUiState())
    val calibrationState: StateFlow<CalibrationUiState> = _calibrationState.asStateFlow()
    private var calibrationJob: Job? = null

    fun startCalibration() {
        if (_calibrationState.value.isRunning) return
        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            val roles = listOf(AgentRole.VISION, AgentRole.REASONING, AgentRole.CODING)
            val cases = mapOf(
                AgentRole.VISION to listOf(
                    "Analise a captura de uma interface. Identifique regiões, hierarquia visual, transparência aparente, sombras e cantos. Separe observações de hipóteses.",
                    "Descreva a posição relativa da barra superior, painel lateral, área principal e cartões. Não invente coordenadas nem texto ilegível.",
                    "Compare a linguagem visual desta interface com macOS, Windows, GNOME e KDE. Use sinais concretos e indique o nível de confiança."
                ),
                AgentRole.REASONING to listOf(
                    "Teste lógico: Ana é mais velha que Bia. Bia é mais velha que Caio. Diego é mais novo que Caio. Ordene as quatro pessoas da mais velha para a mais nova e explique brevemente.",
                    "Planejamento: um app precisa importar um arquivo, validar seu formato, salvar com segurança e mostrar o resultado. Proponha uma sequência mínima e três casos de erro.",
                    "Revisão crítica: uma solução funciona no caso comum, mas falha com entrada vazia e arquivo duplicado. Explique como testar e corrigir sem presumir detalhes."
                ),
                AgentRole.CODING to listOf(
                    "Encontre e corrija os erros neste Kotlin: fun dividir(a: Int, b: Int): Int { return a / b }; println(dividir(10, 0)). Explique a causa e apresente uma versão segura.",
                    "Revise este Python: items = [1, 2, 3]; for i in range(len(items) + 1): print(items[i]). Identifique o bug, corrija e explique o limite do índice.",
                    "Ambiente de desenvolvimento: explique como identificar Windows/PowerShell, macOS/zsh ou Linux/bash antes de escolher comandos. Dê um exemplo seguro de inspeção para cada um e não alegue execução."
                )
            )
            val report = StringBuilder()
            var completed = 0
            val total = cases.values.sumOf { it.size }
            _calibrationState.value = CalibrationUiState(isRunning = true, totalCases = total)
            try {
                val visionImage = createCalibrationImage()
                for (role in roles) {
                    _calibrationState.value = _calibrationState.value.copy(
                        currentRole = role, currentCase = "Preparando " + role.displayName
                    )
                    val roleNotes = StringBuilder()
                    for ((index, prompt) in cases.getValue(role).withIndex()) {
                        _calibrationState.value = _calibrationState.value.copy(
                            currentCase = role.displayName + ": exercício " + (index + 1) + "/3",
                            latestOutput = "", error = null
                        )
                        val turns = listOf(ChatTurn(
                            role = "user", text = prompt,
                            imagePath = if (role == AgentRole.VISION) visionImage else null
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
                        if (failure != null) {
                            report.append("\n• ").append(role.displayName).append(" — falha: ").append(failure)
                        } else {
                            roleNotes.append("\n[Exemplo ").append(index + 1).append("] ")
                                .append(prompt.take(160)).append("\n[Resposta] ")
                                .append(output.take(360)).append("\n")
                            report.append("\n• ").append(role.displayName).append(" — exercício ")
                                .append(index + 1).append(": concluído.")
                        }
                        completed++
                        _calibrationState.value = _calibrationState.value.copy(completedCases = completed)
                    }
                    if (roleNotes.isNotBlank()) {
                        val dao = container.database.agentConfigDao()
                        val cfg = dao.getByRole(role.id) ?: AgentConfigEntity(roleId = role.id)
                        val prior = cfg.systemPromptOverride.orEmpty().substringBefore("\n\n[AI CALIBRATION EXAMPLES]")
                        val block = roleNotes.toString().take(1250)
                        val updated = (prior + "\n\n[AI CALIBRATION EXAMPLES]\nUse os exemplos anteriores como referências, sem copiar respostas cegamente; priorize correção e evidências na tarefa atual.\n" + block).trim()
                        dao.upsert(cfg.copy(systemPromptOverride = updated.take(5000)))
                    }
                }
                _calibrationState.value = _calibrationState.value.copy(
                    isRunning = false, currentRole = null, currentCase = "Calibração concluída",
                    report = "Sessão concluída. Exemplos bem-sucedidos foram anexados aos prompts especializados.\n" + report
                )
                _uiBannerMessage.value = "AI Calibration Lab: sessão concluída. Perfis de referência atualizados."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _calibrationState.value = _calibrationState.value.copy(isRunning = false, currentCase = "Sessão interrompida")
                throw cancelled
            } catch (error: Exception) {
                _calibrationState.value = _calibrationState.value.copy(
                    isRunning = false, error = error.message ?: "Falha inesperada na calibração."
                )
            } finally {
                container.inferenceRuntimeManager.unloadAllModels("Fim da sessão de calibração")
            }
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        viewModelScope.launch {
            container.inferenceRuntimeManager.unloadAllModels("Calibração interrompida pelo usuário")
        }
        _calibrationState.value = _calibrationState.value.copy(isRunning = false, currentCase = "Sessão interrompida")
    }

    private fun createCalibrationImage(): String {
        val file = File(getApplication<Application>().cacheDir, "calibration_ui_sample.png")
        if (file.exists() && file.length() > 0) return file.absolutePath
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(24, 28, 38))
        paint.color = Color.rgb(37, 43, 57); canvas.drawRoundRect(18f, 18f, 622f, 342f, 18f, 18f, paint)
        paint.color = Color.rgb(54, 62, 80); canvas.drawRoundRect(18f, 18f, 622f, 66f, 18f, 18f, paint)
        paint.color = Color.WHITE; paint.textSize = 18f; canvas.drawText("NEBULA • Workspace", 42f, 48f, paint)
        paint.color = Color.rgb(47, 55, 72); canvas.drawRoundRect(32f, 82f, 174f, 326f, 12f, 12f, paint)
        paint.color = Color.rgb(110, 170, 245); canvas.drawRoundRect(46f, 102f, 160f, 136f, 8f, 8f, paint)
        paint.color = Color.LTGRAY; paint.textSize = 13f; canvas.drawText("Dashboard", 52f, 124f, paint)
        canvas.drawText("Projects", 52f, 164f, paint); canvas.drawText("Settings", 52f, 194f, paint)
        paint.color = Color.rgb(62, 73, 94); canvas.drawRoundRect(192f, 84f, 606f, 326f, 14f, 14f, paint)
        paint.color = Color.WHITE; paint.textSize = 21f; canvas.drawText("Welcome back", 218f, 124f, paint)
        paint.color = Color.rgb(93, 161, 226); canvas.drawRoundRect(218f, 150f, 568f, 224f, 12f, 12f, paint)
        paint.color = Color.WHITE; paint.textSize = 16f; canvas.drawText("System overview", 238f, 180f, paint)
        paint.color = Color.rgb(180, 195, 220); canvas.drawText("3 agents online • 2 tasks", 238f, 204f, paint)
        paint.color = Color.rgb(82, 96, 120); canvas.drawRoundRect(218f, 242f, 380f, 302f, 10f, 10f, paint)
        canvas.drawRoundRect(396f, 242f, 568f, 302f, 10f, 10f, paint)
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
