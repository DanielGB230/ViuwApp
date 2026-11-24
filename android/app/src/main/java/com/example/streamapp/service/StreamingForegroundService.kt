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

        const val ACTION_START = "com.example.streamapp.action.START_STREAM"
        const val ACTION_STOP = "com.example.streamapp.action.STOP_STREAM"

        /** Alias con el nombre usado por el contrato de la Fase 11. */
        const val ACTION_START_STREAM = ACTION_START

        const val EXTRA_SESSION_ID = "sessionId"

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP recibida")
                stopStreamingAndSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                Log.d(TAG, "ACTION_START recibida (sessionId=$sessionId)")
                // El foreground debe declararse cuanto antes (límite de 5 s).
                startAsForeground()
                startStreaming(sessionId)
            }

            else -> startAsForeground()
        }

        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // Streaming
    // ---------------------------------------------------------------------

    private fun startStreaming(sessionId: String) {
        if (sessionId.isNotBlank()) {
            pendingSessionCode = sessionId
        }

        if (streamingStarted) {
            Log.d(TAG, "Streaming ya iniciado; se ignora ACTION_START duplicada")
            return
        }
        streamingStarted = true

        serviceScope.launch {
            try {
                if (webRtc == null) {
                    val client = WebRtcClient(
                        context = applicationContext,
                        isSender = true,
                        listener = senderListener
                    )
                    webRtc = client
                    client.startLocalMedia(preferFrontCamera = true)
                }

                val client = signalR ?: SignalRClient().also { signalR = it }
                client.connect()
                registerSignalRHandlers(client)
                Log.d(TAG, "SignalR conectado. CreateSession($pendingSessionCode)")
                client.createSession(pendingSessionCode)
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar el streaming", t)
                stopStreamingAndSelf()
            }
        }
    }

    private fun registerSignalRHandlers(client: SignalRClient) {
        client.onSessionCreated { code ->
            sessionCode = code
            Log.d(TAG, "SessionCreated: $code (esperando OnPeerJoined para ofertar)")
        }

        // Ambos eventos llegan cuando el Receptor entra en la sala.
        client.onPeerJoined { code ->
            if (code.isNotBlank()) sessionCode = code
            Log.d(TAG, "PeerJoined: $code -> enviando oferta SDP")
            sendOfferIfPossible()
        }

        client.onReceiverJoined { code ->
            if (code.isNotBlank()) sessionCode = code
            sendOfferIfPossible()
        }

        client.onAnswerReceived { sdp ->
            webRtc?.setRemoteDescription(sdp, SessionDescription.Type.ANSWER) {
                Log.d(TAG, "Respuesta SDP remota aplicada")
            }
        }

        client.onIceCandidate { candidate, sdpMid, sdpMLineIndex ->
            webRtc?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        }

        client.onReceiverLeft {
            Log.d(TAG, "El Receptor se desconectó; se podrá reofertar")
            offerSent = false
        }

        client.onSessionEnded { message ->
            Log.d(TAG, "SessionEnded: $message")
            stopStreamingAndSelf()
        }

        client.onSessionError { message ->
            Log.e(TAG, "SessionError: $message")
        }

        // ---- Controles remotos desde el Receptor ----
        client.onSwitchCameraRequested {
            Log.d(TAG, "OnSwitchCameraRequested -> switchCamera()")
            webRtc?.switchCamera()
        }

        client.onToggleMuteRequested { isMuted ->
            Log.d(TAG, "OnToggleMuteRequested -> setAudioEnabled(${!isMuted})")
            webRtc?.setAudioEnabled(!isMuted)
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
        cleanupStreaming()
        stopForegroundNow()
        serviceScope.cancel()
        super.onDestroy()
    }
}
