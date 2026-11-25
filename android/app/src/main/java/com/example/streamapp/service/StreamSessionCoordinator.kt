package com.example.streamapp.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.streamapp.network.SignalRClient
import com.example.streamapp.webrtc.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Coordina una sesión WebRTC activa del Emisor (SENDER).
 *
 * Se usa desde:
 *  - StreamingForegroundService (cuando el servicio está vivo en foreground)
 *  - StandbySignalR (cuando Android no permite ForegroundService en background)
 *  - ViuwFirebaseMessagingService (fallback si FGS bloqueado tras FCM)
 *
 * Encapsula:
 *  - SignalRClient + registro de presencia + handlers
 *  - WebRtcClient (PeerConnection con tracks OFF por defecto)
 *  - Lógica de joinSession / createSession / offer / answer / ICE
 *  - Controles remotos: cameraMode, microphoneEnabled, streamStopped
 *  - Protección contra duplicados por sessionCode
 */
class StreamSessionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val TAG = "ViuwStream"

    // Estado de sesión
    @Volatile
    private var currentSessionCode: String = ""
    @Volatile
    private var isJoiningOrActive = false

    // Componentes
    private var signalR: SignalRClient? = null
    private var webRtc: WebRtcClient? = null
    private var handlersRegistered = false

    // Hardware ID del dispositivo
    private val hardwareId: String
        get() = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

    /** Inicia o reanuda una sesión con el sessionCode dado. */
    fun startSession(sessionCode: String) {
        if (sessionCode.isBlank()) {
            Log.w(TAG, "startSession: sessionCode vacío")
            return
        }

        // Protección contra duplicados: mismo sessionCode ya uniéndose/activo
        synchronized(this) {
            if (isJoiningOrActive && currentSessionCode == sessionCode) {
                Log.d(TAG, "startSession: ya uniéndose/activo en $sessionCode, ignorando duplicado")
                return
            }
            currentSessionCode = sessionCode
            isJoiningOrActive = true
        }

        scope.launch {
            try {
                ensureWebRtcReady()

                val client = ensureSignalRReady()
                
                if (hardwareId.isNotBlank()) {
                    Log.i(TAG, "startSession: JoinSession($sessionCode, $hardwareId)")
                    client.joinSession(sessionCode, hardwareId)
                } else {
                    Log.w(TAG, "startSession: hardwareId vacío, creando sesión nueva")
                    client.createSession(sessionCode)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startSession: error", t)
                resetSessionState()
            }
        }
    }

    /** Detiene la sesión activa y libera recursos WebRTC (mantiene SignalR para presencia). */
    fun stopSession() {
        scope.launch {
            cleanupWebRtc()
            resetSessionState()
            Log.i(TAG, "stopSession: WebRTC liberado, SignalR mantenido para presencia")
        }
    }

    /** Cierra completamente (WebRTC + SignalR). */
    fun close() {
        scope.launch {
            cleanupWebRtc()
            cleanupSignalR()
            resetSessionState()
            Log.i(TAG, "close: todo liberado")
        }
    }

    /** Indica si hay una sesión activa o en proceso. */
    fun isActive(): Boolean = isJoiningOrActive

    /** Obtiene el sessionCode actual. */
    fun getCurrentSessionCode(): String = currentSessionCode

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun ensureWebRtcReady() {
        if (webRtc == null) {
            val client = WebRtcClient(
                context = context,
                isSender = true,
                listener = senderListener
            )
            webRtc = client
            client.createPeerConnection()
            Log.i(TAG, "ensureWebRtcReady: WebRtcClient creado (cámara/mic OFF)")
        }
    }

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

    private fun registerPresence(client: SignalRClient) {
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
            currentSessionCode = code
            Log.i(TAG, "SessionCreated: $code (esperando OnPeerJoined para ofertar)")
        }

        // Ambos eventos llegan cuando el Receptor entra en la sala.
        client.onPeerJoined { code ->
            if (code.isNotBlank()) currentSessionCode = code
            Log.i(TAG, "PeerJoined: $code -> enviando oferta SDP")
            sendOfferIfPossible()
        }

        client.onReceiverJoined { code ->
            if (code.isNotBlank()) currentSessionCode = code
            Log.i(TAG, "ReceiverJoined: $code -> enviando oferta SDP")
            sendOfferIfPossible()
        }

        client.onAnswerReceived { sdp ->
            webRtc?.setRemoteDescription(sdp, SessionDescription.Type.ANSWER) {
                Log.i(TAG, "Respuesta SDP remota aplicada")
            }
        }

        client.onIceCandidate { candidate, sdpMid, sdpMLineIndex ->
            webRtc?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        }

        client.onReceiverLeft {
            Log.i(TAG, "El Receptor se desconectó; se podrá reofertar")
            offerSent = false
        }

        client.onSessionEnded { message ->
            Log.i(TAG, "SessionEnded: $message")
            cleanupWebRtc()
            resetSessionState()
        }

        client.onSessionError { message ->
            Log.e(TAG, "SessionError: $message")
        }

        // ---- Arranque de streaming (desde standby o FCM) ----
        client.onStartStream { sessionId ->
            Log.i(TAG, "onStartStream recibido: sessionId=$sessionId")
            // Asegurar que el PeerConnection exista (cámara/mic OFF) antes de que el receptor envíe offer.
            scope.launch {
                try {
                    ensureWebRtcReady()
                    startSession(sessionId)
                } catch (t: Throwable) {
                    Log.e(TAG, "onStartStream: error preparando WebRTC", t)
                }
            }
        }

        // ---- Controles remotos desde el Receptor ----
        client.onSwitchCameraRequested {
            Log.i(TAG, "OnSwitchCameraRequested -> switchCamera()")
            webRtc?.switchCamera()
        }

        client.onToggleMuteRequested { isMuted ->
            Log.i(TAG, "OnToggleMuteRequested -> setAudioEnabled(${!isMuted})")
            webRtc?.setAudioEnabled(!isMuted)
        }

        // Fase 14: controles independientes de cámara y micrófono.
        client.onCameraModeChanged { mode ->
            Log.i(TAG, "OnCameraModeChanged -> setCameraMode($mode)")
            webRtc?.setCameraMode(mode)
        }

        client.onMicrophoneEnabledChanged { enabled ->
            Log.i(TAG, "OnMicrophoneEnabledChanged -> setAudioEnabled($enabled)")
            webRtc?.setAudioEnabled(enabled)
        }

        // El Receptor pidió desconectar: liberar hardware y volver a standby
        // sin cerrar SignalR ni la sesión.
        client.onStreamStopped {
            Log.i(TAG, "OnStreamStopped -> liberando WebRTC, SignalR mantenido")
            cleanupWebRtc()
            resetSessionState()
        }
    }

    @Volatile
    private var offerSent = false

    private fun sendOfferIfPossible() {
        if (offerSent) return
        if (currentSessionCode.isBlank()) {
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
                signalR?.sendOffer(currentSessionCode, sdp)
                Log.i(TAG, "Oferta SDP enviada para la sesión $currentSessionCode")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo crear/enviar la oferta SDP", t)
        }
    }

    private fun cleanupWebRtc() {
        webRtc?.let { client ->
            try {
                client.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Error cerrando WebRTC: ${t.message}")
            }
        }
        webRtc = null
        offerSent = false
    }

    private fun cleanupSignalR() {
        signalR?.let { client ->
            try {
                client.closeConnection()
            } catch (_: Throwable) {
            }
        }
        signalR = null
        handlersRegistered = false
    }

    private fun resetSessionState() {
        synchronized(this) {
            currentSessionCode = ""
            isJoiningOrActive = false
        }
    }

    private val senderListener = object : WebRtcClient.Listener {
        override fun onIceCandidate(candidate: IceCandidate) {
            val code = currentSessionCode
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
}

/**
 * Fábrica/singleton para obtener un StreamSessionCoordinator compartido
 * desde cualquier componente (FGS, StandbySignalR, FCM fallback).
 * Mantiene una única instancia por proceso para evitar conexiones duplicadas.
 */
object StreamSessionCoordinatorProvider {

    private const val TAG = "ViuwStream"

    @Volatile
    private var instance: StreamSessionCoordinator? = null
    @Volatile
    private var instanceScope: CoroutineScope? = null

    /** Obtiene (o crea) la instancia singleton del coordinador. */
    fun get(context: Context): StreamSessionCoordinator {
        val appContext = context.applicationContext
        return synchronized(this) {
            instance ?: StreamSessionCoordinator(appContext, getOrCreateScope(appContext)).also { instance = it }
        }
    }

    /** Libera la instancia singleton (ej. cuando FGS arranca correctamente). */
    fun release() {
        synchronized(this) {
            instance?.close()
            instance = null
            instanceScope?.let { scope ->
                val job = scope.coroutineContext[Job]
                job?.children?.forEach { it.cancel() }
            }
            instanceScope = null
            Log.i(TAG, "StreamSessionCoordinatorProvider: instancia liberada")
        }
    }

    private fun getOrCreateScope(appContext: Context): CoroutineScope {
        return instanceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { instanceScope = it }
    }
}