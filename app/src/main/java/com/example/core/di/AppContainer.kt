package com.example.core.di

import android.content.Context
import androidx.room.Room
import com.example.agent_engine.MultiAgentOrchestrator
import com.example.browser.PrivateBrowser
import com.example.api_server.LocalNetworkDiscovery
import com.example.api_server.OpenAiApiServer
import com.example.coding_agent.CodingAgent
import com.example.core.hardware.DeviceProfiler
import com.example.core.model.AgentRole
import com.example.device_manager.DeviceAccessRepository
import com.example.inference.InferenceRuntimeManager
import com.example.memory.MemoryBudgetManager
import com.example.model_manager.ModelRepository
import com.example.monitoring.data.AgentConfigEntity
import com.example.monitoring.data.AppDatabase
import com.example.reasoning_agent.ReasoningAgent
import com.example.security.ApiKeyManager
import com.example.settings.SettingsRepository
import com.example.vision_agent.VisionAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "local_dev_ai_db"
    ).fallbackToDestructiveMigration(true).build()

    val deviceProfiler = DeviceProfiler(appContext)
    val settingsRepository = SettingsRepository(appContext)
    val memoryBudgetManager = MemoryBudgetManager(deviceProfiler)
    val networkDiscovery = LocalNetworkDiscovery(appContext)
    val privateBrowser = PrivateBrowser(appContext)

    val apiKeyManager = ApiKeyManager(database.apiKeyDao())
    val deviceAccessRepository = DeviceAccessRepository(database.connectedDeviceDao())

    val modelRepository = ModelRepository(
        context = appContext,
        localModelDao = database.localModelDao(),
        agentConfigDao = database.agentConfigDao(),
        memoryBudgetManager = memoryBudgetManager
    )

    val inferenceRuntimeManager = InferenceRuntimeManager(
        context = appContext,
        localModelDao = database.localModelDao(),
        memoryBudgetManager = memoryBudgetManager,
        deviceProfiler = deviceProfiler,
        settingsRepository = settingsRepository
    )

    val codingAgent = CodingAgent(
        runtimeManager = inferenceRuntimeManager,
        agentConfigDao = database.agentConfigDao(),
        localModelDao = database.localModelDao(),
        historyDao = database.agentHistoryDao()
    )

    val visionAgent = VisionAgent(
        runtimeManager = inferenceRuntimeManager,
        agentConfigDao = database.agentConfigDao(),
        localModelDao = database.localModelDao(),
        historyDao = database.agentHistoryDao()
    )

    val reasoningAgent = ReasoningAgent(
        runtimeManager = inferenceRuntimeManager,
        agentConfigDao = database.agentConfigDao(),
        localModelDao = database.localModelDao(),
        historyDao = database.agentHistoryDao()
    )

    val multiAgentOrchestrator = MultiAgentOrchestrator(
        reasoningAgent = reasoningAgent,
        codingAgent = codingAgent,
        visionAgent = visionAgent,
        agentConfigDao = database.agentConfigDao(),
        localModelDao = database.localModelDao(),
        multiAgentRunDao = database.multiAgentRunDao(),
        settingsRepository = settingsRepository,
        privateBrowser = privateBrowser
    )

    val openAiApiServer = OpenAiApiServer(
        context = appContext,
        apiKeyManager = apiKeyManager,
        deviceAccessRepository = deviceAccessRepository,
        codingAgent = codingAgent,
        visionAgent = visionAgent,
        reasoningAgent = reasoningAgent,
        multiAgentOrchestrator = multiAgentOrchestrator,
        agentConfigDao = database.agentConfigDao(),
        localModelDao = database.localModelDao(),
        requestLogDao = database.apiRequestLogDao(),
        settingsRepository = settingsRepository
    )

    init {
        networkDiscovery.startMonitoring()
        appScope.launch {
            // Ensure permanent primary API key is generated once and preserved forever until rotated/revoked
            apiKeyManager.ensurePermanentPrimaryKey()
            // Ensure default configurations exist for all 3 distinct agents
            val threads = deviceProfiler.recommendedThreadCount(deviceProfiler.captureProfile().cpuCores)
            AgentRole.entries.forEach { role ->
                if (database.agentConfigDao().getByRole(role.id) == null) {
                    database.agentConfigDao().upsert(
                        AgentConfigEntity(
                            roleId = role.id,
                            enabled = true,
                            threads = threads,
                            temperature = when (role) {
                                AgentRole.CODING -> 0.2f
                                AgentRole.VISION -> 0.25f
                                AgentRole.REASONING -> 0.15f
                            }
                        )
                    )
                }
            }
            val settings = settingsRepository.settingsFlow.first()
            memoryBudgetManager.updateUserRamLimitMb(settings.ramLimitMb)
            modelRepository.revalidateAllModels()
        }
    }
}
