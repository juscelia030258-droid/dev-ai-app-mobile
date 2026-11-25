package com.example.api_server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.LocalDevAiApplication
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LocalApiForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monitorJob: Job? = null
    private var memoryMaintenanceJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeLockRenewal = object : Runnable {
        override fun run() {
            val lock = wakeLock ?: return
            if (!lock.isHeld) return
            runCatching {
                lock.release()
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            if (wakeLock?.isHeld == true) {
                mainHandler.postDelayed(this, WAKE_LOCK_RENEW_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) {
            serviceScope.launch {
                val container = (application as LocalDevAiApplication).container
                container.openAiApiServer.stop()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 }
        val initialNotif = buildNotification("Iniciando servidor local...", "Preparando porta HTTP na rede Wi-Fi")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        val container = (application as LocalDevAiApplication).container
        container.networkDiscovery.startMonitoring()
        acquireWakeLock()

        serviceScope.launch {
            val res = container.openAiApiServer.start(port)
            if (res.isFailure) {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            startNotificationUpdater(container)
            startMemoryMaintenance(container)
        }

        return START_STICKY
    }

    private fun startNotificationUpdater(container: com.example.core.di.AppContainer) {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            combine(
                container.openAiApiServer.status,
                container.networkDiscovery.state
            ) { serverStatus, netState ->
                serverStatus to netState
            }.collect { (serverStatus, netState) ->
                if (!serverStatus.isRunning) return@collect
                val baseUrl = netState.buildBaseUrl(serverStatus.port)
                val title = "Servidor API Ativo • :${serverStatus.port}"
                val text = "$baseUrl • ${serverStatus.totalRequestsHandled} reqs • ${serverStatus.activeConnections} ativas"
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(title, text))
            }
        }
    }

    // This maintenance loop belongs to the foreground service, not the UI,
    // so idle-model cleanup also runs while the screen is off.
    private fun startMemoryMaintenance(container: com.example.core.di.AppContainer) {
        memoryMaintenanceJob?.cancel()
        memoryMaintenanceJob = serviceScope.launch(Dispatchers.IO) {
            while (true) {
                delay(MEMORY_MAINTENANCE_INTERVAL_MS)
                val settings = container.settingsRepository.settingsFlow.first()
                if (settings.autoUnloadIdleModels) {
                    val timeoutMs = settings.idleUnloadMinutes.coerceIn(1, 30) * 60_000L
                    container.inferenceRuntimeManager.unloadIfIdleFor(timeoutMs)
                }
            }
        }
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocalApiForegroundService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Parar Servidor", stopPending)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.fgs_channel_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDevAI::ApiServerWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        mainHandler.removeCallbacks(wakeLockRenewal)
        if (wakeLock?.isHeld == true) {
            mainHandler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_INTERVAL_MS)
        }
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRenewal)
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        memoryMaintenanceJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "local_dev_ai_api_server"
        const val NOTIFICATION_ID = 4041
        const val ACTION_START_SERVER = "com.example.action.START_API_SERVER"
        const val ACTION_STOP_SERVER = "com.example.action.STOP_API_SERVER"
        const val EXTRA_PORT = "extra_port"
        private const val MEMORY_MAINTENANCE_INTERVAL_MS = 15_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 5 * 60 * 60 * 1000L

        fun startServerService(context: Context, port: Int? = null) {
            val intent = Intent(context, LocalApiForegroundService::class.java).apply {
                action = ACTION_START_SERVER
                if (port != null) putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopServerService(context: Context) {
            val intent = Intent(context, LocalApiForegroundService::class.java).apply {
                action = ACTION_STOP_SERVER
            }
            context.startService(intent)
        }
    }
}
