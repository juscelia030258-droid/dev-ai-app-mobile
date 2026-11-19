package com.example.core.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.example.core.model.DeviceHardwareProfile
import kotlin.math.max

private const val MAX_SAFE_INFERENCE_BUDGET_MB = 3584L

class DeviceProfiler(private val context: Context) {

    fun captureProfile(userRamLimitMb: Long? = null): DeviceHardwareProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(1024L)
        val availRamMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(256L)

        // Never assume 100% of physical RAM (e.g. 8 GB on Galaxy A56) is available for LLM weights.
        // Reserve OS, SystemUI, Dalvik heap, and Ktor server headroom (~45% of total RAM or 2.8 GB min).
        val osReserveMb = max(2560L, (totalRamMb * 40L) / 100L)
        val hardwareSafeMaxMb = max(1024L, totalRamMb - osReserveMb)
        // Model budget depends on physical device capacity and the user's model-only cap.
        // Free RAM from Android/background apps is tracked separately as system pressure.
        val hardwareModelBudgetMb = minOf(hardwareSafeMaxMb, MAX_SAFE_INFERENCE_BUDGET_MB)
        val finalModelBudgetMb = if (userRamLimitMb != null && userRamLimitMb > 512L) {
            minOf(userRamLimitMb, hardwareModelBudgetMb)
        } else {
            hardwareModelBudgetMb
        }
        val systemMemoryPressureLabel = when {
            availRamMb < 1024L -> "Crítica"
            availRamMb < 2048L -> "Alta"
            availRamMb < 3072L -> "Moderada"
            else -> "Normal"
        }

        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        val runtime = Runtime.getRuntime()
        val jvmUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val processRssMb = nativeHeapMb + jvmUsedMb

        val statFs = StatFs(context.filesDir.absolutePath)
        val freeStorageMb = (statFs.availableBlocksLong * statFs.blockSizeLong) / (1024L * 1024L)
        val totalStorageMb = (statFs.blockCountLong * statFs.blockSizeLong) / (1024L * 1024L)

        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
        val primaryAbi = supportedAbis.firstOrNull() ?: "unknown"
        val isArm64 = supportedAbis.any { it.contains("arm64", ignoreCase = true) }

        val thermalLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (powerManager?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Aquecimento leve"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderado"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severo (reduzir carga)"
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Crítico"
                else -> "Normal"
            }
        } else {
            "Indisponível (API < 29)"
        }

        return DeviceHardwareProfile(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Android" },
            modelName = Build.MODEL.orEmpty().ifBlank { "Device" },
            abi = primaryAbi,
            isArm64 = isArm64,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            androidVersionLabel = "Android ${Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT}",
            sdkInt = Build.VERSION.SDK_INT,
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            modelSafeBudgetMb = finalModelBudgetMb,
            systemMemoryPressureLabel = systemMemoryPressureLabel,
            processRssMb = processRssMb,
            freeStorageMb = freeStorageMb,
            totalStorageMb = totalStorageMb,
            thermalStatusLabel = thermalLabel
        )
    }

    fun isThermalSevereOrCritical(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status = powerManager?.currentThermalStatus ?: return false
        return status >= PowerManager.THERMAL_STATUS_SEVERE
    }

    fun recommendedThreadCount(cpuCores: Int): Int {
        // On 8-core ARM64 SoCs like Exynos 1580 (Galaxy A56), 4 performance threads avoid thermal throttling.
        return when {
            cpuCores >= 8 -> 4
            cpuCores >= 4 -> 3
            else -> 2
        }
    }
}
