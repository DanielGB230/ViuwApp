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
import com.example.streamapp.network.SignalRClient
import com.example.streamapp.webrtc.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Servicio en primer plano que mantiene viva la transmisión del Emisor:
 *  - Publica la notificación persistente con tipos cámara|micrófono.
 *  - Crea el WebRtcClient (cámara + micrófono) y la PeerConnection.
 *  - Se conecta al Hub /signaling, llama a CreateSession(sessionId) y envía la
 *    oferta SDP en cuanto hay un Receptor en la sala.
 *  - Atiende los controles remotos (cambiar cámara y silenciar micrófono).
 *
 * Debe iniciarse únicamente después de conceder CAMERA y RECORD_AUDIO
 * (los permisos se solicitan desde la Activity, nunca desde el servicio).
 */
class StreamingForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "StreamingFGS"
        // Etiquetas de log unificadas para diagnóstico de flujo (Fase 19)
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

    private var signalR: SignalRClient? = null
    private var webRtc: WebRtcClient? = null

    private var pendingSessionCode: String = ""
    private var sessionCode: String = ""
    private var streamingStarted = false
    private var offerSent = false
    private var presenceStarted = false
    private var standbyMode = false
    private var handlersRegistered = false

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
                startStreaming(sessionCode)
            }

            else -> startAsForeground()
        }

        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // Streaming
    // ---------------------------------------------------------------------

    private fun startStreaming(sessionCode: String) {
        if (sessionCode.isNotBlank()) {
            pendingSessionCode = sessionCode
        }

        if (streamingStarted) {
            Log.d(LOG_TAG, "startStreaming: ya iniciado, ignorando duplicado")
            return
        }
        streamingStarted = true

        // Si venimos de standby, cambiamos el tipo de foreground a cámara|micrófono.
        if (standbyMode) {
            standbyMode = false
            startAsForeground()
        }

        serviceScope.launch {
            try {
                if (webRtc == null) {
                    val client = WebRtcClient(
                        context = applicationContext,
                        isSender = true,
                        listener = senderListener
                    )
                    webRtc = client
                    // Fase 15: se negocian transceptores SEND_ONLY sin abrir
                    // cámara ni micrófono (sin punto verde de Android).
                    client.createPeerConnection()
                    Log.i(LOG_TAG, "startStreaming: WebRtcClient creado y PeerConnection negociada (cámara/mic OFF)")
                }

                val client = signalR ?: SignalRClient().also { signalR = it }
                // Esperar a que SignalR esté CONNECTED antes de joinSession/createSession.
                if (!client.isConnected) {
                    Log.i(LOG_TAG, "startStreaming: conectando SignalR...")
                    client.connect()
                    registerSignalRHandlers(client)
                    Log.i(LOG_TAG, "startStreaming: SignalR CONNECTED")
                } else {
                    // Ya estaba conectado (venimos de standby): asegurar handlers.
                    registerSignalRHandlers(client)
                }

                val hardwareId = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ANDROID_ID
                ).orEmpty()

                if (pendingSessionCode.isNotBlank() && hardwareId.isNotBlank()) {
                    // FCM despertó al dispositivo: la sesión ya existe en el servidor.
                    // Unirse directamente en lugar de crear una nueva.
                    Log.i(LOG_TAG, "startStreaming: JoinSession($pendingSessionCode, $hardwareId)")
                    client.joinSession(pendingSessionCode, hardwareId)
                } else {
                    // Flujo legacy: crear sesión nueva.
                    Log.i(LOG_TAG, "startStreaming: CreateSession($pendingSessionCode)")
                    client.createSession(pendingSessionCode)
                }
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "No se pudo iniciar el streaming", t)
                stopStreamingAndSelf()
            }
        }
    }

    /**
     * Entra en standby: garantiza que NO quede WebRtcClient, cámara ni micrófono
     * activos, mantiene SignalR conectado y la presencia del dispositivo.
     */
    private fun enterStandbyMode() {
        standbyMode = true

        if (webRtc != null) {
            try {
                webRtc?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Error cerrando WebRTC al entrar en standby: ${t.message}")
            }
            webRtc = null
        }

        sessionCode = ""
        pendingSessionCode = ""
        streamingStarted = false
        offerSent = false

        startAsForeground(standby = true)
        startStandbyPresence()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Red disponible -> reconectando SignalR y presencia")
            serviceScope.launch {
                try {
                    val client = ensureSignalRReady()
                    registerPresence(client)
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

    /**
     * Modo standby (arranque tras reinicio): conecta SignalR y registra la
     * presencia del dispositivo, sin abrir cámara ni micrófono.
     */
    private fun startStandbyPresence() {
        if (presenceStarted) {
            Log.d(LOG_TAG, "Presencia ya iniciada; se ignora solicitud duplicada")
            return
        }
        presenceStarted = true

        serviceScope.launch {
            try {
                ensureSignalRReady() // ya registra presencia internamente
                Log.i(LOG_TAG, "startStandbyPresence: presencia registrada")
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "No se pudo iniciar la presencia en standby", t)
                presenceStarted = false
                stopStreamingAndSelf()
            }
        }
    }

    /** Conecta SignalR (si hace falta), registra handlers y presencia. */
    private suspend fun ensureSignalRReady(): SignalRClient {
        val client = signalR ?: SignalRClient().also { signalR = it }
        if (!client.isConnected) {
            client.connect()
        }
        registerSignalRHandlers(client)
        // RegisterDevicePresence suscribe el ConnectionId a los grupos por DeviceHardwareId
        // y LinkedDevice.Id en SignalingHub (clave para recibir StartStream).
        registerPresence(client)
        return client
    }

    /** Actualiza la presencia del dispositivo en el backend. */
    private fun registerPresence(client: SignalRClient) {
        val hardwareId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        if (hardwareId.isNotBlank()) {
            client.registerDevicePresence(hardwareId)
            Log.d(TAG, "Presencia registrada para $hardwareId")
        } else {
            Log.w(TAG, "ANDROID_ID vacío; no se pudo registrar presencia")
        }
    }

    private fun registerSignalRHandlers(client: SignalRClient) {
        if (handlersRegistered) return
        handlersRegistered = true

        client.onSessionCreated { code ->
            sessionCode = code
            Log.i(LOG_TAG, "SessionCreated: $code (esperando OnPeerJoined para ofertar)")
        }

        // Ambos eventos llegan cuando el Receptor entra en la sala.
        client.onPeerJoined { code ->
            if (code.isNotBlank()) sessionCode = code
            Log.i(LOG_TAG, "PeerJoined: $code -> enviando oferta SDP")
            sendOfferIfPossible()
        }

        client.onReceiverJoined { code ->
            if (code.isNotBlank()) sessionCode = code
            Log.i(LOG_TAG, "ReceiverJoined: $code -> enviando oferta SDP")
            sendOfferIfPossible()
        }

        client.onAnswerReceived { sdp ->
            webRtc?.setRemoteDescription(sdp, SessionDescription.Type.ANSWER) {
                Log.i(LOG_TAG, "Respuesta SDP remota aplicada")
            }
        }

        client.onIceCandidate { candidate, sdpMid, sdpMLineIndex ->
            webRtc?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        }

        client.onReceiverLeft {
            Log.i(LOG_TAG, "El Receptor se desconectó; se podrá reofertar")
            offerSent = false
        }

        client.onSessionEnded { message ->
            Log.i(LOG_TAG, "SessionEnded: $message")
            stopStreamingAndSelf()
        }

        client.onSessionError { message ->
            Log.e(LOG_TAG, "SessionError: $message")
        }

        // ---- Arranque de streaming (desde standby o FCM) ----
        client.onStartStream { sessionId ->
            Log.i(LOG_TAG, "onStartStream recibido: sessionId=$sessionId (SignalR connected=${signalR?.isConnected})")
            // Asegurar que el PeerConnection exista (cámara/mic OFF) antes de que el receptor envíe offer.
            serviceScope.launch {
                try {
                    if (webRtc == null) {
                        val client = WebRtcClient(
                            context = applicationContext,
                            isSender = true,
                            listener = senderListener
                        )
                        webRtc = client
                        client.createPeerConnection()
                        Log.i(LOG_TAG, "onStartStream: WebRtcClient creado bajo demanda (cámara/mic OFF)")
                    }
                    startStreaming(sessionId)
                } catch (t: Throwable) {
                    Log.e(LOG_TAG, "onStartStream: error preparando WebRTC", t)
                }
            }
        }

        // ---- Controles remotos desde el Receptor ----
        client.onSwitchCameraRequested {
            Log.i(LOG_TAG, "OnSwitchCameraRequested -> switchCamera()")
            webRtc?.switchCamera()
        }

        client.onToggleMuteRequested { isMuted ->
            Log.i(LOG_TAG, "OnToggleMuteRequested -> setAudioEnabled(${!isMuted})")
            webRtc?.setAudioEnabled(!isMuted)
        }

        // Fase 14: controles independientes de cámara y micrófono.
        client.onCameraModeChanged { mode ->
            Log.i(LOG_TAG, "OnCameraModeChanged -> setCameraMode($mode)")
            webRtc?.setCameraMode(mode)
        }

        client.onMicrophoneEnabledChanged { enabled ->
            Log.i(LOG_TAG, "OnMicrophoneEnabledChanged -> setAudioEnabled($enabled)")
            webRtc?.setAudioEnabled(enabled)
        }

        // El Receptor pidió desconectar: liberar hardware y volver a standby
        // sin cerrar SignalR ni la sesión.
        client.onStreamStopped {
            Log.i(LOG_TAG, "OnStreamStopped -> liberando WebRTC y volviendo a standby")
            returnToStandby()
        }
    }

    /**
     * Libera cámara/micrófono/PeerConnection y regresa al modo standby,
     * manteniendo SignalR y la presencia activos.
     */
    private fun returnToStandby() {
        if (webRtc != null) {
            try {
                webRtc?.close()
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "Error cerrando WebRTC al volver a standby: ${t.message}")
            }
            webRtc = null
        }

        sessionCode = ""
        pendingSessionCode = ""
        streamingStarted = false
        offerSent = false
        standbyMode = true

        startAsForeground(standby = true)

        serviceScope.launch {
            try {
                ensureSignalRReady() // ya registra presencia internamente
                Log.i(LOG_TAG, "returnToStandby: presencia registrada")
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo mantener la presencia tras OnStreamStopped", t)
            }
        }
    }

    /** Crea la PeerConnection (una sola vez) y envía la oferta SDP. */
    private fun sendOfferIfPossible() {
        if (offerSent) return
        if (sessionCode.isBlank()) {
            Log.w(TAG, "Aún no hay sessionCode; no se puede ofertar")
            return
        }

        val client = webRtc ?: run {
            Log.w(TAG, "No hay WebRtcClient para ofertar")
            return
        }

        try {
            client.createPeerConnection()
            client.createOffer { sdp ->
                offerSent = true
                signalR?.sendOffer(sessionCode, sdp)
                Log.d(TAG, "Oferta SDP enviada para la sesión $sessionCode")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo crear/enviar la oferta SDP", t)
        }
    }

    private val senderListener = object : WebRtcClient.Listener {
        override fun onIceCandidate(candidate: IceCandidate) {
            val code = sessionCode.ifBlank { pendingSessionCode }
            if (code.isNotBlank()) {
                signalR?.sendIceCandidate(
                    code,
                    candidate.sdp,
                    candidate.sdpMid ?: "",
                    candidate.sdpMLineIndex
                )
            }
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            // El Emisor no recibe vídeo.
        }

        override fun onConnectionState(state: String) {
            Log.d(TAG, "WebRTC connectionState=$state")
        }

        override fun onError(message: String) {
            Log.e(TAG, "WebRTC error: $message")
        }
    }

    private fun stopStreamingAndSelf() {
        cleanupStreaming()
        stopForegroundNow()
        stopSelf()
    }

    private fun cleanupStreaming() {
        val client = signalR
        signalR = null

        // Al cerrar la conexión, el Hub dispara OnDisconnectedAsync y notifica
        // al Receptor (SessionEnded), así que no hace falta esperar un ack.
        try {
            client?.closeConnection()
        } catch (_: Throwable) {
        }

        try {
            webRtc?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error cerrando WebRTC: ${t.message}")
        }
        webRtc = null

        sessionCode = ""
        pendingSessionCode = ""
        streamingStarted = false
        offerSent = false
    }

    // ---------------------------------------------------------------------
    // Foreground service / notificación
    // ---------------------------------------------------------------------

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
        cleanupStreaming()
        stopForegroundNow()
        serviceScope.cancel()
        unregisterNetworkCallback()
        super.onDestroy()
    }
}
