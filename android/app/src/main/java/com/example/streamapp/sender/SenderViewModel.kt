package com.example.streamapp.sender

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.streamapp.network.SignalRClient
import com.example.streamapp.service.StreamingForegroundService
import com.example.streamapp.webrtc.WebRtcClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

data class SenderUiState(
    val sessionCode: String = "",
    val status: String = "Solicitando permisos...",
    val isStreaming: Boolean = false,
    val isReceiverConnected: Boolean = false,
    val error: String? = null
)

class SenderViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SenderUiState())
    val uiState: StateFlow<SenderUiState> = _uiState.asStateFlow()

    /** EglBase compartido con los SurfaceViewRenderer de la UI. */
    val rendererEglBase: EglBase = EglBase.create()
    private val rendererEglReleased = AtomicBoolean(false)

    private val signalR = SignalRClient()
    private var webRtc: WebRtcClient? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var sessionCode = ""
    private var started = false

    private val deviceId: String by lazy {
        Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "android-device"
    }

    fun startTransmission() {
        if (started) return
        started = true

        startForegroundService()
        _uiState.update {
            it.copy(
                status = "Iniciando cámara y micrófono...",
                isStreaming = true,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                val client = WebRtcClient(
                    context = getApplication(),
                    isSender = true,
                    listener = senderListener,
                    externalEglBase = rendererEglBase
                )
                webRtc = client
                client.startLocalMedia(preferFrontCamera = true)
                localRenderer?.let { client.localVideoTrack()?.addSink(it) }

                _uiState.update { it.copy(status = "Conectando al servidor...") }
                // Cada sesión usa una conexión nueva para no duplicar manejadores de eventos.
                signalR.disconnect()
                signalR.connect()
                registerSignalRHandlers()
                signalR.createSession("")
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        status = "No se pudo iniciar la transmisión",
                        error = t.message ?: t.javaClass.simpleName
                    )
                }
                teardown(notifyServer = false)
            }
        }
    }

    private val senderListener = object : WebRtcClient.Listener {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (sessionCode.isNotEmpty()) {
                signalR.sendIceCandidate(
                    sessionCode,
                    candidate.sdp,
                    candidate.sdpMid ?: "",
                    candidate.sdpMLineIndex
                )
            }
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            // El emisor no recibe vídeo.
        }

        override fun onConnectionState(state: String) {
            if (state == "CONNECTED") {
                _uiState.update { it.copy(status = "Transmitiendo en vivo") }
            }
        }

        override fun onError(message: String) {
            _uiState.update { it.copy(error = message) }
        }
    }

    private fun registerSignalRHandlers() {
        signalR.onSessionCreated { code ->
            sessionCode = code
            _uiState.update {
                it.copy(sessionCode = code, status = "Esperando al receptor...")
            }
        }

        signalR.onReceiverJoined { _ ->
            _uiState.update {
                it.copy(
                    isReceiverConnected = true,
                    status = "Receptor conectado. Negociando vídeo..."
                )
            }
            try {
                webRtc?.createPeerConnection()
                webRtc?.createOffer { sdp -> signalR.sendOffer(sessionCode, sdp) }
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = t.message) }
            }
        }

        signalR.onReceiverLeft {
            _uiState.update {
                it.copy(
                    isReceiverConnected = false,
                    status = "El receptor se desconectó. Esperando de nuevo..."
                )
            }
        }

        signalR.onAnswerReceived { sdp ->
            webRtc?.setRemoteDescription(sdp, SessionDescription.Type.ANSWER) {
                _uiState.update { it.copy(status = "Transmitiendo en vivo") }
            }
        }

        signalR.onIceCandidate { candidate, sdpMid, sdpMLineIndex ->
            webRtc?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        }

        signalR.onSessionEnded { message ->
            _uiState.update { it.copy(status = message) }
            teardown(notifyServer = false)
            viewModelScope.launch {
                try {
                    signalR.disconnect()
                } catch (_: Throwable) {
                }
            }
        }

        signalR.onSessionError { message ->
            _uiState.update { it.copy(status = "Error del servidor", error = message) }
        }
    }

    fun stopTransmission() {
        teardown(notifyServer = true)
        viewModelScope.launch { signalR.disconnect() }
    }

    private fun teardown(notifyServer: Boolean) {
        val code = sessionCode
        sessionCode = ""

        if (notifyServer && code.isNotEmpty()) {
            signalR.endSession(code, deviceId)
        }

        webRtc?.close()
        webRtc = null

        stopForegroundService()
        started = false

        _uiState.update {
            SenderUiState(status = "Transmisión detenida", isStreaming = false)
        }
    }

    fun onPermissionsDenied() {
        _uiState.update {
            it.copy(
                status = "Permisos denegados",
                error = "Se necesitan permisos de cámara y micrófono para transmitir."
            )
        }
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        webRtc?.localVideoTrack()?.addSink(renderer)
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        webRtc?.localVideoTrack()?.removeSink(renderer)
        if (localRenderer === renderer) {
            localRenderer = null
        }
    }

    fun switchCamera() {
        webRtc?.switchCamera()
    }

    fun retryCamera() {
        _uiState.update { it.copy(error = null, status = "Reiniciando cámara...") }
        try {
            webRtc?.restartCamera()
            localRenderer?.let { webRtc?.localVideoTrack()?.addSink(it) }
            _uiState.update { it.copy(status = "Cámara reiniciada") }
        } catch (t: Throwable) {
            _uiState.update { it.copy(error = "Fallo al reiniciar: ${t.message}") }
        }
    }

    fun releaseRendererEgl() {
        if (rendererEglReleased.compareAndSet(false, true)) {
            try {
                rendererEglBase.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun startForegroundService() {
        val intent = Intent(getApplication(), StreamingForegroundService::class.java)
            .setAction(StreamingForegroundService.ACTION_START)
        try {
            ContextCompat.startForegroundService(getApplication(), intent)
        } catch (_: Throwable) {
        }
    }

    private fun stopForegroundService() {
        val app = getApplication<Application>()
        val intent = Intent(app, StreamingForegroundService::class.java)
            .setAction(StreamingForegroundService.ACTION_STOP)
        try {
            app.startService(intent)
        } catch (_: Throwable) {
            app.stopService(Intent(app, StreamingForegroundService::class.java))
        }
    }

    override fun onCleared() {
        if (started) {
            teardown(notifyServer = true)
        } else {
            webRtc?.close()
            webRtc = null
        }
        signalR.closeConnection()
        super.onCleared()
    }
}
