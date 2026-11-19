package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.example.core.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocalDevAiApplication : Application(), ComponentCallbacks2 {
    lateinit var container: AppContainer
        private set

    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    // Smart mode: never unload a model while inference is queued or running.
    // When the UI disappears, allow a short grace period, then free an idle model.
    // Under Android memory pressure, free an idle model immediately.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::container.isInitialized) return
        when {
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                maintenanceScope.launch {
                    delay(BACKGROUND_IDLE_GRACE_MS)
                    container.inferenceRuntimeManager.unloadIfIdleFor(0L)
                }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                maintenanceScope.launch {
                    container.inferenceRuntimeManager.unloadIfIdleFor(0L)
                }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::container.isInitialized) {
            maintenanceScope.launch {
                container.inferenceRuntimeManager.unloadIfIdleFor(0L)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onTerminate() {
        maintenanceScope.cancel()
        super.onTerminate()
    }

    companion object {
        private const val BACKGROUND_IDLE_GRACE_MS = 120_000L
    }
}