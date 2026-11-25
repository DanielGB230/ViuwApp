package com.example.streamapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.example.streamapp.MainActivity
import com.example.streamapp.data.SessionPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene viva la transmisión del Emisor:
 *  - Publica la notificación persistente.
 *  - Delegua la sesión WebRTC/SignalR a StreamSessionCoordinator.
 *  - Gestiona el tipo de ForegroundService: SPECIAL_USE en standby (cámara/mic OFF),
 *    promueve a CAMERA|MICROPHONE solo cuando se activan realmente.
 *
 * Debe iniciarse únicamente después de conceder CAMERA y RECORD_AUDIO
 * (los permisos se solicitan desde la Activity, nunca desde el servicio).
 */
class StreamingForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "StreamingFGS"
        // Etiquetas de log unificadas para diagnóstico de flujo (Fase 19/20)
        private const val LOG_TAG = "ViuwStream"

        const val ACTION_START = "com.example.streamapp.action.START_STREAM"
        const val ACTION_STOP = "com.example.streamapp.action.STOP_STREAM"

        /** Standby: mantiene la presencia SignalR sin abrir cámara/micrófono. */
        const val ACTION_STANDBY = "com.example.streamapp.action.STANDBY"

        /** Alias con el nombre usado por el contrato de la Fase 11. */
        const val ACTION_START_STREAM = ACTION_START

        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_SESSION_CODE = "sessionCode"

        const val NOTIFICATION_CHANNEL_ID = "viuwapp_stream_channel"
        const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Coordinador de sesión (singleton compartido con StandbySignalR/FCM fallback)
    private val sessionCoordinator = StreamSessionCoordinatorProvider.get(this)

    private var standbyMode = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(LOG_TAG, "onStartCommand: ACTION_STOP")
                stopStreamingAndSelf()
                return START_NOT_STICKY
            }

            ACTION_STANDBY -> {
                Log.i(LOG_TAG, "onStartCommand: ACTION_STANDBY (solo presencia)")
                enterStandbyMode()
            }

            ACTION_START -> {
                // Leer sessionCode (nuevo) o sessionId (legacy) para compatibilidad.
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                    .orEmpty()
                    .ifBlank { intent.getStringExtra(EXTRA_SESSION_ID).orEmpty() }
                Log.i(LOG_TAG, "onStartCommand: ACTION_START sessionCode=$sessionCode")
                standbyMode = false
                // El foreground debe declararse cuanto antes (límite de 5 s).
                startAsForeground()
                startSession(sessionCode)
            }

            else -> startAsForeground()
        }

        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // Gestión de sesión delegada al coordinador
    // ---------------------------------------------------------------------

    private fun startSession(sessionCode: String) {
        if (sessionCode.isBlank()) {
            Log.w(LOG_TAG, "startSession: sessionCode vacío, creando sesión nueva")
            // Sin sessionCode: el coordinador hará createSession internamente
        }
        // Si venimos de standby, actualizar tipo de foreground
        if (standbyMode) {
            standbyMode = false
            startAsForeground()
        }
        sessionCoordinator.startSession(sessionCode)
    }

    /**
     * Entra en standby: libera WebRTC, mantiene SignalR conectado y la presencia del dispositivo.
     */
    private fun enterStandbyMode() {
        standbyMode = true

        sessionCoordinator.stopSession()

        startAsForeground(standby = true)
        // La presencia ya la gestiona el coordinador internamente (SignalR sigue vivo)
        Log.i(LOG_TAG, "enterStandbyMode: WebRTC liberado, SignalR en presencia")
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Red disponible -> reconectando SignalR y presencia")
            serviceScope.launch {
                try {
                    // Asegurar que el coordinador tiene SignalR conectado y presencia registrada
                    if (!sessionCoordinator.isActive()) {
                        // Si no hay sesión activa, el coordinador ya mantiene SignalR para presencia
                        Log.d(LOG_TAG, "NetworkCallback: SignalR ya mantenido por coordinador")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "No se pudo reconectar tras red disponible: ${t.message}")
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.registerDefaultNetworkCallback(networkCallback)
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo registrar NetworkCallback: ${t.message}")
            }
        }
    }

    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.unregisterNetworkCallback(networkCallback)
            } catch (_: Throwable) {}
        }
    }

    private fun stopStreamingAndSelf() {
        sessionCoordinator.close()
        stopForegroundNow()
        stopSelf()
    }

    // ---------------------------------------------------------------------
    // Foreground service / notificación
    // ---------------------------------------------------------------------

    /**
     * Inicia el ForegroundService.
     * - standby=true  -> SPECIAL_USE (API 34+) / DATA_SYNC (API < 34) porque cámara y mic están OFF.
     * - standby=false -> CAMERA | MICROPHONE (streaming activo).
     */
    private fun startAsForeground(standby: Boolean = false) {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                !standby -> {
                    // Streaming activo: cámara + micrófono
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                // Standby: cámara y micrófono APAGADOS.
                // En Android 14+ (API 34) usamos SPECIAL_USE (parental_control_presence).
                // En Android 10-13 usamos DATA_SYNC como fallback.
                Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(standby), types)
            Log.d(LOG_TAG, "startAsForeground: standby=$standby, types=$types")
        } catch (t: Throwable) {
            // Si el sistema rechaza el FGS (p. ej. permisos revocados), detenemos el servicio.
            Log.e(LOG_TAG, "No se pudo iniciar el foreground service", t)
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

    private fun buildNotification(standby: Boolean = false): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (standby) "Dispositivo en línea" else "Transmisión activa"
        val text = if (standby) {
            "Listo para recibir una solicitud de transmisión"
        } else {
            "Tu cámara y micrófono están transmitiendo"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved - app cerrada de recientes")

        val prefs = SessionPreferences(applicationContext)
        if (prefs.getSavedRole() == "SENDER") {
            Log.i(TAG, "Rol SENDER persistido; programando reconexión automática")
            PresenceScheduler.scheduleWhenOnline(applicationContext)

            // AlarmManager para forzar re-inicio del servicio en ~2s aunque el sistema lo mate.
            val alarmIntent = Intent(applicationContext, BootReceiver::class.java)
                .setAction(Intent.ACTION_MY_PACKAGE_REPLACED)
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                0,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            val triggerAt = System.currentTimeMillis() + 2000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.d(TAG, "AlarmManager programado para reactivar presencia en 2s")
        }
    }

    override fun onDestroy() {
        sessionCoordinator.close()
        stopForegroundNow()
        serviceScope.cancel()
        unregisterNetworkCallback()
        super.onDestroy()
    }
}