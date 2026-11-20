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
    private val _calibrationDurations = MutableStateFlow(
        mapOf(AgentRole.CODING to 60, AgentRole.REASONING to 45, AgentRole.VISION to 20)
    )
    private val _calibrationModes = MutableStateFlow(
        mapOf(
            AgentRole.CODING to CalibrationMode.NORMAL,
            AgentRole.REASONING to CalibrationMode.NORMAL,
            AgentRole.VISION to CalibrationMode.NORMAL
        )
    )
    val calibrationModes: StateFlow<Map<AgentRole, CalibrationMode>> = _calibrationModes.asStateFlow()

    fun setCalibrationMode(role: AgentRole, mode: CalibrationMode) {
        if (_calibrationState.value.isRunning) return
        _calibrationModes.value = _calibrationModes.value.toMutableMap().apply { put(role, mode) }
    }
    val calibrationDurations: StateFlow<Map<AgentRole, Int>> = _calibrationDurations.asStateFlow()

    fun setCalibrationDuration(role: AgentRole, minutes: Int) {
        if (_calibrationState.value.isRunning) return
        _calibrationDurations.value = _calibrationDurations.value.toMutableMap().apply {
            put(role, minutes.coerceIn(1, 120))
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
            val thermal = profile.thermalStatusLabel
            val severeThermal = container.deviceProfiler.isThermalSevereOrCritical()
            val lowMemory = container.deviceProfiler.isSystemLowMemory() || profile.availableRamMb < 760L
            if (!severeThermal && !lowMemory) {
                _calibrationState.value = _calibrationState.value.copy(thermalMessage = null)
                if (thermal.contains("leve", ignoreCase = true) || thermal.contains("Moderado", ignoreCase = true)) delay(4_000L)
                return
            }
            val reason = buildList {
                if (severeThermal) add("temperatura elevada ($thermal)")
                if (lowMemory) add("pouca RAM livre (${profile.availableRamMb} MB)")
            }.joinToString(" e ")
            _calibrationState.value = _calibrationState.value.copy(thermalMessage = "Treinamento em espera por segurança: $reason.")
            if (System.currentTimeMillis() - startedWaiting >= 180_000L) {
                throw IllegalStateException("SACE pausado automaticamente: $reason. Aguarde o aparelho esfriar/liberar memória e retome.")
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
        if (_calibrationState.value.isRunning) return
        calibrationJob?.cancel()
        pauseRequested = false
        calibrationJob = viewModelScope.launch {
            val savedCheckpoint = if (resume) saceProgressStore.readCheckpoint() else null
            val roles = listOf(AgentRole.CODING, AgentRole.REASONING, AgentRole.VISION)
            val cases = mapOf(
                AgentRole.CODING to listOf(
                    "Firmware ESP32: planeje um firmware C++ para ler um sensor, controlar uma saída e tratar falha de leitura. Inclua estados e critérios de teste.",
                    "Menu funcional: crie a estrutura de um menu para OLED com opções, navegação por encoder e retorno. Explique como evitar bloqueios no loop principal.",
                    "Configurações persistentes: implemente em Kotlin uma tela de opções que salva preferências, valida valores e restaura padrões.",
                    "Depuração Kotlin: fun dividir(a: Int, b: Int): Int { return a / b }; println(dividir(10, 0)). Identifique e corrija a falha.",
                    "Depuração Python: items = [1, 2, 3]; for i in range(len(items) + 1): print(items[i]). Corrija o limite e proponha um teste.",
                    "API: projete uma rota HTTP que recebe configuração, valida os campos, responde com erros claros e não salva dados inválidos.",
                    "Projeto modular: separe um aplicativo em interface, regras de negócio, armazenamento e acesso à rede. Mostre arquivos e responsabilidades.",
                    "Linux: analise um serviço que falha ao iniciar. Liste comandos de leitura seguros para conferir status, logs, permissões e dependências; não alegue execução.",
                    "Linux: receba um projeto de laboratório com scripts, unidades systemd e arquivos de configuração. Aponte falhas, proponha melhorias e escreva testes sem alterar o sistema real.",
                    "ESP32 e sensores: crie um firmware C++ com leitura periódica, filtro simples, timeout de sensor e estado seguro quando a leitura falhar. Inclua testes.",
                    "ESP32 e comunicação: projete uma troca de mensagens entre dois dispositivos usando um protocolo definido. Trate perda, repetição e dados inválidos.",
                    "OLED e encoder: implemente a lógica de um menu com páginas, seleção, retorno, edição de valores e atualização sem bloquear o loop.",
                    "Persistência: crie uma camada para salvar configurações com versão, valores padrão e recuperação quando os dados salvos estiverem inválidos.",
                    "Kotlin Android: implemente uma tela de configurações com estado observável, validação, salvamento assíncrono e tratamento de erro.",
                    "Java: analise uma classe com responsabilidades misturadas e proponha uma divisão em módulos sem alterar o comportamento público.",
                    "Python: crie uma ferramenta de linha de comando que leia um arquivo, valide os dados e gere um relatório; trate arquivo ausente e conteúdo malformado.",
                    "Build: analise um erro Gradle com dependências incompatíveis. Liste os arquivos e versões que precisa conferir antes de sugerir uma correção.",
                    "Concorrência: revise um código que compartilha estado entre tarefas. Identifique condições de corrida e proponha sincronização adequada.",
                    "Testes: transforme requisitos de um menu de firmware em casos de teste, incluindo limites, entradas inválidas e recuperação após reinicialização.",
                    "Refatoração: receba um módulo grande e proponha uma divisão em arquivos, interfaces e responsabilidades, mantendo a compatibilidade.",
                    "Revisão de segurança: analise uma API que recebe configurações e identifique validação ausente, exposição de segredos e operações perigosas."
                ),
                AgentRole.REASONING to listOf(
                    "Lógica: Ana é mais velha que Bia. Bia é mais velha que Caio. Diego é mais novo que Caio. Ordene as quatro pessoas e explique a dedução.",
                    "Diagnóstico tecnológico: um celular fecha um modelo local ao abrir outro aplicativo. Analise RAM, memória livre, contexto do modelo e descarregamento; separe fatos de hipóteses.",
                    "Hardware: explique como CPU, RAM, armazenamento e sistema operacional trabalham juntos ao abrir um modelo local de IA.",
                    "Redes: um computador conecta ao Wi-Fi, mas não abre sites. Compare hipóteses envolvendo IP, DNS, gateway e conexão externa.",
                    "Sistemas: compare duas arquiteturas para um app móvel com três modelos de IA e pouca RAM. Considere execução sequencial, tempo e custo de recarga.",
                    "Investigação: receba três relatos que se contradizem sobre uma falha. Liste o que está confirmado, o que falta medir e qual teste distingue as hipóteses.",
                    "Planejamento: divida a criação de um firmware ESP32 com menu, configurações salvas e comunicação Wi-Fi em tarefas, dependências e critérios de aceite.",
                    "Colaboração: revise uma resposta da Coding AI que afirma ter compilado um projeto sem mostrar logs. Identifique o que pode e o que não pode ser confirmado.",
                    "Conhecimento tecnológico: explique a diferença entre processo, serviço, thread, memória virtual e armazenamento, usando exemplos simples.",
                    "Análise de desempenho: um app usa muita RAM depois de alternar entre modelos. Separe possíveis causas e proponha medições que confirmem cada uma.",
                    "Arquitetura: compare execução sequencial e paralela de três modelos em um celular com memória limitada. Explique os custos de cada opção.",
                    "Investigação: um firmware reinicia aleatoriamente. Organize hipóteses sobre alimentação, watchdog, memória e sensores e escolha uma ordem de testes.",
                    "Dados: analise uma tabela pequena com valores ausentes e duplicados. Explique como limpar os dados sem apagar informação válida.",
                    "Probabilidade: estime a chance de pelo menos uma falha em várias tentativas independentes e explique as suposições usadas.",
                    "Pensamento crítico: avalie uma afirmação técnica sem fonte. Diga quais evidências seriam suficientes para aceitá-la ou rejeitá-la.",
                    "Planejamento: organize um projeto com prazo curto, três módulos dependentes e recursos limitados. Defina ordem, riscos e critérios de conclusão.",
                    "Conflito entre agentes: duas IAs dão soluções incompatíveis. Compare as evidências, identifique o ponto de discordância e proponha um teste decisivo.",
                    "Sistema operacional: explique como um processo inicia, abre arquivos, usa memória e encerra. Diferencie o que é comum e o que varia entre Android, Linux e Windows.",
                    "Limites de conhecimento: responda a um problema técnico com dados insuficientes. Mostre o que pode concluir e faça a pergunta mínima necessária."
                ),
                AgentRole.VISION to listOf(
                    "MAPA DE INTERFACE: identifique no máximo cinco componentes importantes, diga em que região estão e explique como se agrupam. Não descreva o fundo inteiro.",
                    "LEITURA DE TEXTO: transcreva somente os rótulos realmente legíveis. Marque texto incerto como [ilegível]; não complete palavras por contexto.",
                    "GEOMETRIA: compare alinhamento, largura relativa, espaçamento e posição de cartões e painéis. Use relações como maior/menor e esquerda/direita, sem inventar pixels.",
                    "ESTADO VISUAL: localize controles selecionados, desativados, alertas e indicadores de carregamento. Para cada estado, cite a evidência visual.",
                    "AUDITORIA DE UI: encontre até três inconsistências de cor, tipografia, borda, alinhamento ou espaçamento. Se não houver evidência, diga que não encontrou.",
                    "ESPECIFICAÇÃO PARA CODING AI: transforme a tela em uma lista curta de componentes, posição relativa, estilo aproximado e comportamento que a imagem comprova.",
                    "CONTAGEM E LOCALIZAÇÃO: conte apenas os cartões, botões ou painéis que conseguir distinguir. Informe quando algum elemento estiver parcialmente oculto.",
                    "COMPARAÇÃO: compare duas áreas da imagem e indique uma semelhança e uma diferença concreta, sem repetir a descrição geral.",
                    "DIAGNÓSTICO VISUAL: aponte o problema visual mais importante, a evidência e uma correção específica. Não suponha falhas de funcionamento.",
                    "RESUMO COM EVIDÊNCIAS: responda à pergunta principal em até cinco tópicos; cada tópico deve conter uma observação visível, não uma suposição.",
                    "FORMULÁRIO: identifique campos, rótulos, botões e agrupamentos. Informe a ordem de leitura e não suponha validações que não aparecem.",
                    "TELA DE CONFIGURAÇÕES: agrupe opções por finalidade e destaque controles que parecem selecionados ou desativados.",
                    "DASHBOARD: compare a hierarquia visual entre título, indicadores, gráficos e cartões. Cite somente elementos visíveis.",
                    "ERRO VISUAL: procure sobreposição, corte, desalinhamento ou contraste insuficiente. Para cada achado, indique a região e a evidência.",
                    "DIAGRAMA: identifique caixas, setas e relações visíveis. Se uma ligação estiver ambígua, marque como incerta.",
                    "FOTO DE COMPONENTES: descreva somente componentes que conseguir distinguir e diferencie identificação provável de identificação confirmada.",
                    "OCR: leia os textos mais importantes da imagem e preserve a escrita original. Não complete partes ilegíveis.",
                    "COMPARAÇÃO DE TELAS: compare duas interfaces fornecidas, apontando mudanças concretas de posição, tamanho e estado.",
                    "ACESSIBILIDADE VISUAL: avalie contraste aparente, tamanho relativo dos textos e clareza dos controles sem inferir recursos invisíveis.",
                    "ESPECIFICAÇÃO: transforme a imagem em uma lista curta de componentes e relações que a Coding AI possa implementar."
                )
            )
            val restoredModes = savedCheckpoint?.selectedModes?.mapNotNull { (roleId, modeName) ->
                val role = roles.firstOrNull { it.id == roleId }
                val mode = runCatching { CalibrationMode.valueOf(modeName) }.getOrNull()
                if (role != null && mode != null) role to mode else null
            }?.toMap().orEmpty()
            val restoredDurations = savedCheckpoint?.selectedDurations?.mapNotNull { (roleId, minutes) ->
                roles.firstOrNull { it.id == roleId }?.let { it to minutes.coerceIn(1, 120) }
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
            val hasUltimate = selectedModes.values.any { it == CalibrationMode.ULTIMATE }
            val total = if (hasUltimate) 0 else roles.sumOf { role ->
                val mode = selectedModes[role] ?: CalibrationMode.NORMAL
                when (mode) {
                    CalibrationMode.NORMAL -> 3
                    CalibrationMode.ADVANCED -> 6
                    CalibrationMode.ULTIMATE -> 0
                }
            } + if (isSurpriseSession) roles.size else 0
            _calibrationState.value = CalibrationUiState(isRunning = true, totalCases = total)
            InferenceTaskForegroundService.startTask(getApplication())
            try {
                val resumeRoleIndex = savedCheckpoint?.let { cp -> roles.indexOfFirst { it.id == cp.roleId }.coerceAtLeast(0) } ?: 0
                for (role in roles.drop(resumeRoleIndex)) {
                    val isResumingThisRole = savedCheckpoint?.roleId == role.id
                    val mode = if (isResumingThisRole) runCatching { CalibrationMode.valueOf(savedCheckpoint!!.mode) }.getOrDefault(selectedModes[role] ?: CalibrationMode.NORMAL) else (selectedModes[role] ?: CalibrationMode.NORMAL)
                    val durationMinutes = if (mode == CalibrationMode.ULTIMATE) 300 else selectedDurations.getValue(role)
                    val roleStartedAt = if (isResumingThisRole) savedCheckpoint!!.startedAt else System.currentTimeMillis()
                    val roleDeadline = roleStartedAt + durationMinutes * 60_000L
                    report.append("\n\n").append(role.displayName).append(" — ").append(mode.title)
                        .append(": limite de ").append(durationMinutes).append(" min.")
                    _calibrationState.value = _calibrationState.value.copy(
                        currentRole = role, currentCase = "Preparando " + role.displayName
                    )
                    val roleNotes = StringBuilder()
                    val pool = cases.getValue(role)
                    val selectedPool = when (mode) {
                        CalibrationMode.NORMAL -> pool.take(3)
                        CalibrationMode.ADVANCED -> pool.take(6)
                        CalibrationMode.ULTIMATE -> pool
                    }
                    val initialOffset = sacePrefs.getInt("next_${role.id}", 0).coerceAtLeast(0)
                    var attempt = if (isResumingThisRole) savedCheckpoint!!.nextAttempt else 0
                    val exerciseBaseOffset = if (isResumingThisRole) initialOffset - attempt else initialOffset
                    while (mode == CalibrationMode.ULTIMATE || attempt < selectedPool.size) {
                        if (System.currentTimeMillis() >= roleDeadline) break
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
                        val prompt = if (isLinuxFinalHour) {
                            "DESAFIO FINAL LINUX: use somente o projeto de laboratório e os arquivos que forem fornecidos nesta tarefa. " +
                                "Mapeie a estrutura, analise scripts, serviços, permissões e dependências; encontre erros; proponha melhorias; " +
                                "reescreva apenas módulos selecionados e crie testes. Não afirme que analisou uma distribuição inteira nem que executou comandos. " +
                                "Tarefa: " + basePrompt
                        } else if (cycle == 0) stagePrefix + basePrompt else {
                            stagePrefix + basePrompt + "\n\nCrie uma variação nova deste desafio, mudando dados, nomes, restrições ou cenário. Resolva a nova versão; não copie a resposta anterior."
                        }
                        _calibrationState.value = _calibrationState.value.copy(
                            currentCase = role.displayName + " (" + mode.title + "): exercício " + (attempt + 1) +
                                if (mode == CalibrationMode.ULTIMATE) " — ciclo " + (cycle + 1) else "",
                            latestOutput = "", error = null
                        )
                        saceProgressStore.saveCheckpoint(
                            roleId = role.id, mode = mode.name, nextAttempt = attempt,
                            startedAt = roleStartedAt, sessionNumber = sessionNumber,
                            selectedModes = selectedModes.mapKeys { it.key.id }.mapValues { it.value.name },
                            selectedDurations = selectedDurations.mapKeys { it.key.id }
                        )
                        awaitSafeSaceConditions()
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
                            selectedDurations = selectedDurations.mapKeys { it.key.id }
                        )
                        if (failure != null || output.isBlank()) {
                            report.append("\n• ").append(role.displayName).append(" — falha: ").append(failure)
                            if (mode != CalibrationMode.ULTIMATE) break
                        } else {
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
                        _calibrationState.value = _calibrationState.value.copy(completedCases = completed)
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
                container.inferenceRuntimeManager.unloadAllModels("Fim da sessão de calibração")
                InferenceTaskForegroundService.finishTask(getApplication())
            }
        }
    }


    /**
     * Runs a fixed evaluation set kept separate from calibration/adaptive history.
     * Scores are second-pass estimates from the same agent, not independent ground truth.
     */
    fun runSaceBenchmark() {
        if (_calibrationState.value.isRunning || _calibrationState.value.isBenchmarkRunning) return
        benchmarkJob?.cancel()
        benchmarkJob = viewModelScope.launch {
            val cases = SaceBenchmarkDataset.cases
            val benchmarkRunId = saceBenchmarkStore.beginRun()
            _calibrationState.value = _calibrationState.value.copy(
                isBenchmarkRunning = true, benchmarkProgress = 0,
                benchmarkReport = "", benchmarkError = null, thermalMessage = null
            )
            InferenceTaskForegroundService.startTask(getApplication())
            try {
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
                _calibrationState.value = _calibrationState.value.copy(
                    isBenchmarkRunning = false, benchmarkReport = saceBenchmarkStore.summary()
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _calibrationState.value = _calibrationState.value.copy(
                    isBenchmarkRunning = false, benchmarkError = "Benchmark interrompido."
                )
                throw cancelled
            } catch (error: Exception) {
                _calibrationState.value = _calibrationState.value.copy(
                    isBenchmarkRunning = false,
                    benchmarkError = error.message ?: "Falha ao executar benchmark."
                )
            } finally {
                container.inferenceRuntimeManager.unloadAllModels("Fim do SACE Benchmark")
                InferenceTaskForegroundService.finishTask(getApplication())
            }
        }
    }

    fun pauseCalibration() {
        if (!_calibrationState.value.isRunning) return
        pauseRequested = true
        calibrationJob?.cancel()
        calibrationJob = null
        viewModelScope.launch {
            container.inferenceRuntimeManager.unloadAllModels("SACE pausado pelo usuário")
        }
    }

    fun resumeCalibration() {
        if (_calibrationState.value.isRunning || saceProgressStore.readCheckpoint() == null) return
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
