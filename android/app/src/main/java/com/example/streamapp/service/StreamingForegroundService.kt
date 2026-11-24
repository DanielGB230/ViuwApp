package com.example.streamapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.example.streamapp.MainActivity

/**
 * Servicio en primer plano para la transmisión con cámara y micrófono.
 * Debe iniciarse únicamente después de conceder CAMERA y RECORD_AUDIO
 * (los permisos se solicitan desde la Activity, nunca desde el servicio).
 */
class StreamingForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "StreamingFGS"

        const val ACTION_START = "com.example.streamapp.action.START_STREAM"
        const val ACTION_STOP = "com.example.streamapp.action.STOP_STREAM"

        const val EXTRA_SESSION_ID = "sessionId"

        const val NOTIFICATION_CHANNEL_ID = "viuwapp_stream_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundNow()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                Log.d(TAG, "ACTION_START recibida (sessionId=$sessionId)")
                startAsForeground()
            }

            else -> startAsForeground()
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (t: Throwable) {
            // Si el sistema rechaza el FGS (p. ej. permisos revocados), detenemos el servicio.
            Log.e(TAG, "No se pudo iniciar el foreground service", t)
            stopSelf()
        }
    }

    private fun stopForegroundNow() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ViuwApp Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal de notificación para la transmisión activa"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Transmisión activa")
            .setContentText("Tu cámara y micrófono están transmitiendo")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopForegroundNow()
        super.onDestroy()
    }
}
