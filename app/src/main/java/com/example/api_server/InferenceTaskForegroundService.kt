package com.example.api_server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
            ACTION_START_TASK -> activeTaskCount += 1
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
            if (wakeLock == null) wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDevAI::InferenceTask")
            if (wakeLock?.isHeld == false) wakeLock?.acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "local_dev_ai_inference_tasks"
        private const val NOTIFICATION_ID = 4042
        private const val MAX_WAKE_LOCK_MS = 2 * 60 * 60 * 1000L
        private const val ACTION_START_TASK = "com.example.action.START_INFERENCE_TASK"
        private const val ACTION_FINISH_TASK = "com.example.action.FINISH_INFERENCE_TASK"

        fun startTask(context: Context) {
            val intent = Intent(context, InferenceTaskForegroundService::class.java).setAction(ACTION_START_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun finishTask(context: Context) {
            val intent = Intent(context, InferenceTaskForegroundService::class.java).setAction(ACTION_FINISH_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}