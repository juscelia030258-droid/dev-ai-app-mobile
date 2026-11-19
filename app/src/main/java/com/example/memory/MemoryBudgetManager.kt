package com.example.memory

import com.example.core.hardware.DeviceProfiler
import com.example.core.model.DeviceHardwareProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LoadedModelSnapshot(
    val modelId: String,
    val modelName: String,
    val roleId: String,
    val runtimeBackend: String,
    val estimatedFootprintMb: Long,
    val loadedAt: Long = System.currentTimeMillis()
)

data class MemoryStatusState(
    val profile: DeviceHardwareProfile,
    val loadedModels: List<LoadedModelSnapshot> = emptyList(),
    val estimatedModelsRamMb: Long = 0L,
    val userConfiguredRamLimitMb: Long = 4096L,
    val lastEvictionReason: String? = null
)

class MemoryBudgetManager(
    private val deviceProfiler: DeviceProfiler
) {
    private val mutex = Mutex()
    private var ramLimitMb: Long = 4096L

    private val _state = MutableStateFlow(
        MemoryStatusState(
            profile = deviceProfiler.captureProfile(ramLimitMb),
            userConfiguredRamLimitMb = ramLimitMb
        )
    )
    val state: StateFlow<MemoryStatusState> = _state.asStateFlow()

    fun refreshProfile() {
        val profile = deviceProfiler.captureProfile(ramLimitMb)
        _state.value = _state.value.copy(
            profile = profile,
            userConfiguredRamLimitMb = ramLimitMb
        )
    }

    fun updateUserRamLimitMb(limitMb: Long) {
        ramLimitMb = limitMb.coerceIn(1024L, 4096L)
        refreshProfile()
    }

    suspend fun checkCanFitModel(
        requestedFootprintMb: Long,
        currentlyLoadedMb: Long,
        willUnloadOthers: Boolean
    ): Pair<Boolean, String?> = mutex.withLock {
        val profile = deviceProfiler.captureProfile(ramLimitMb)
        val effectiveBudget = minOf(ramLimitMb, profile.modelSafeBudgetMb)
        // Sequential inference: when switching agents, the previous runtime is unloaded
        // before this check. Only the incoming active model consumes the model budget.
        // Keep the additive path only for any future runtime that explicitly keeps models resident.
        val projectedMb = if (willUnloadOthers) {
            requestedFootprintMb
        } else {
            currentlyLoadedMb + requestedFootprintMb
        }

        when {
            projectedMb > effectiveBudget -> false to "O modelo exige ~${requestedFootprintMb} MB, acima do orçamento exclusivo do modelo (${effectiveBudget} MB)."
            profile.availableRamMb < requestedFootprintMb + SYSTEM_MEMORY_HEADROOM_MB ->
                false to "Pressão de memória do Android: RAM física disponível ${profile.availableRamMb} MB; são necessários ${requestedFootprintMb + SYSTEM_MEMORY_HEADROOM_MB} MB livres para carregar com ${SYSTEM_MEMORY_HEADROOM_MB} MB de margem. Feche apps externos ou tente novamente."
            else -> true to null
        }
    }

    fun recordLoadedModel(snapshot: LoadedModelSnapshot?, evictionReason: String? = null) {
        val list = if (snapshot != null) listOf(snapshot) else emptyList()
        val profile = deviceProfiler.captureProfile(ramLimitMb)
        _state.value = MemoryStatusState(
            profile = profile,
            loadedModels = list,
            estimatedModelsRamMb = list.sumOf { it.estimatedFootprintMb },
            userConfiguredRamLimitMb = ramLimitMb,
            lastEvictionReason = evictionReason ?: _state.value.lastEvictionReason
        )
    }

    fun estimateGgufFootprintMb(
        fileSizeBytes: Long,
        contextLength: Int,
        mmprojSizeBytes: Long = 0L
    ): Long {
        val weightsMb = (fileSizeBytes / (1024L * 1024L)).coerceAtLeast(64L)
        val mmprojMb = (mmprojSizeBytes / (1024L * 1024L)).coerceAtLeast(0L)
        // Keep runtime estimates aligned with the validator's base overhead,
        // then add KV-cache growth and the actual multimodal projector size.
        val kvCacheMb = ((contextLength.toLong() / 1024L) * 48L).coerceAtLeast(128L)
        return (weightsMb * 1.15).toLong() + kvCacheMb + 256L + mmprojMb
    }
}
