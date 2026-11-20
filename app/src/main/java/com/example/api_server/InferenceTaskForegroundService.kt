package com.example.api_server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R

/** Keeps user-started local inference work eligible to continue with the screen off. */
class InferenceTaskForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeTaskCount = 0
    private var wakeLockSessionStartedAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeLockRenewal = object : Runnable {
        override fun run() {
            if (activeTaskCount <= 0) return
            if (System.currentTimeMillis() - wakeLockSessionStartedAt >= MAX_WAKE_LOCK_SESSION_MS) {
                releaseWakeLock()
                return
            }
            runCatching {
                wakeLock?.let { lock ->
                    if (lock.isHeld) lock.release()
                    lock.acquire(MAX_WAKE_LOCK_MS)
                }
            }
            if (activeTaskCount > 0) mainHandler.postDelayed(this, WAKE_LOCK_RENEW_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Inferência local", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FINISH_TASK -> {
                activeTaskCount = (activeTaskCount - 1).coerceAtLeast(0)
                if (activeTaskCount == 0) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START_TASK -> {
                if (activeTaskCount == 0) wakeLockSessionStartedAt = System.currentTimeMillis()
                activeTaskCount += 1
            }
            else -> return START_NOT_STICKY
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 202, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LOCAL DEV AI está trabalhando")
            .setContentText("A tarefa continuará enquanto o Android permitir.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDevAI::InferenceTask").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(MAX_WAKE_LOCK_MS)
        }
        mainHandler.removeCallbacks(wakeLockRenewal)
        if (activeTaskCount > 0) mainHandler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_INTERVAL_MS)
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRenewal)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        if (activeTaskCount <= 0) wakeLockSessionStartedAt = 0L
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "local_dev_ai_inference_tasks"
        private const val NOTIFICATION_ID = 4042
        private const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 5 * 60 * 60 * 1000L
        private const val MAX_WAKE_LOCK_SESSION_MS = 18 * 60 * 60 * 1000L
        private const val ACTION_START_TASK = "com.example.action.START_INFERENCE_TASK"
        private const val ACTION_FINISH_TASK = "com.example.action.FINISH_INFERENCE_TASK"

        fun startTask(context: Context) {
            val intent = Intent(context, InferenceTaskForegroundService::class.java).setAction(ACTION_START_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun finishTask(context: Context) {
            val intent = Intent(context, InferenceTaskForegroundService::class.java).setAction(ACTION_FINISH_TASK)
            context.startService(intent)
        }
    }
}