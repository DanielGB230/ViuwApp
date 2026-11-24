package com.example.streamapp.receiver

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.streamapp.network.SignalRClient
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

data class ReceiverUiState(
    val sessionCode: String = "",
    val status: String = "Ingresa el código de 6 caracteres",
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isMuted: Boolean = false,
    val error: String? = null
)

class ReceiverViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReceiverUiState())
    val uiState: StateFlow<ReceiverUiState> = _uiState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    val rendererEglBase: EglBase = EglBase.create()
    private val rendererEglReleased = AtomicBoolean(false)

    private val signalR = SignalRClient()
    private var webRtc: WebRtcClient? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteTrack: VideoTrack? = null
    private var sessionCode = ""

    private val deviceId: String by lazy {
        Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "android-device"
    }

    fun joinSession(code: String) {
        if (_uiState.value.isConnecting || _uiState.value.isConnected) return

        _uiState.update {
            it.copy(
                sessionCode = code,
                status = "Conectando al servidor...",
                isConnecting = true,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                signalR.disconnect()
                signalR.connect()
                registerSignalRHandlers()
                signalR.joinSession(code, deviceId)
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        status = "No se pudo conectar",
                        isConnecting = false,
                        error = t.message ?: t.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun registerSignalRHandlers() {
        signalR.onSessionJoined { code ->
            sessionCode = code
            _uiState.update {
                it.copy(
                    sessionCode = code,
                    isConnecting = false,
                    isConnected = true,
                    status = "Esperando al emisor..."
                )
            }
        }

        signalR.onOfferReceived { offerSdp -> handleOffer(offerSdp) }

        signalR.onIceCandidate { candidate, sdpMid, sdpMLineIndex ->
            webRtc?.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        }

        signalR.onSessionEnded { message ->
            _uiState.update { it.copy(status = message, isConnected = false) }
            cleanup()
        }

        signalR.onSessionError { message ->
            _uiState.update {
                it.copy(
                    status = "Error",
                    isConnecting = false,
                    isConnected = false,
                    error = message
                )
            }
        }
    }

    private fun handleOffer(offerSdp: String) {
        try {
            val client = webRtc ?: WebRtcClient(
                context = getApplication(),
                isSender = false,
                listener = receiverListener,
                externalEglBase = rendererEglBase
            ).also { webRtc = it }

            client.createPeerConnection()
            _uiState.update { it.copy(status = "Estableciendo conexión de vídeo...") }

            client.setRemoteDescription(offerSdp, SessionDescription.Type.OFFER) {
                client.createAnswer { answer -> signalR.sendAnswer(sessionCode, answer) }
            }
        } catch (t: Throwable) {
            _uiState.update {
                it.copy(status = "Error al procesar la oferta", error = t.message)
            }
        }
    }

    private val receiverListener = object : WebRtcClient.Listener {
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
            try {
                track.setEnabled(true)
            } catch (_: Throwable) {}
            remoteTrack = track
            _remoteVideoTrack.value = track
            remoteRenderer?.let { renderer ->
                try {
                    track.removeSink(renderer)
                } catch (_: Throwable) {}
                track.addSink(renderer)
            }
            _uiState.update { it.copy(status = "Recibiendo transmisión") }
        }

        override fun onConnectionState(state: String) {
            if (state == "CONNECTED") {
                _uiState.update { it.copy(status = "Recibiendo transmisión en vivo") }
            }
        }

        override fun onError(message: String) {
            _uiState.update { it.copy(error = message) }
        }
    }

    fun leaveSession() {
        cleanup()
    }

    /** Pide al Emisor que cambie de cámara (frontal/trasera). */
    fun switchCamera() {
        val code = sessionCode
        if (code.isEmpty()) return
        viewModelScope.launch { signalR.requestSwitchCamera(code) }
    }

    /** Alterna el micrófono remoto del Emisor. */
    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        _uiState.update { it.copy(isMuted = newMuted) }
        val code = sessionCode
        if (code.isEmpty()) return
        viewModelScope.launch { signalR.requestToggleMute(code, newMuted) }
    }

    private fun cleanup() {
        remoteRenderer?.let { renderer ->
            remoteTrack?.let { track ->
                try {
                    track.removeSink(renderer)
                } catch (_: Throwable) {}
            }
        }
        _remoteVideoTrack.value = null
        remoteTrack = null
        remoteRenderer = null
        val code = sessionCode
        sessionCode = ""
        viewModelScope.launch {
            try {
                if (code.isNotEmpty()) {
                    signalR.endSession(code, deviceId)
                }
            } catch (_: Throwable) {}
            try {
                signalR.disconnect()
            } catch (_: Throwable) {}
        }
        webRtc?.close()
        webRtc = null
        _uiState.update {
            ReceiverUiState(status = "Desconectado", isConnected = false)
        }
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteTrack?.let { track ->
            try {
                track.removeSink(renderer)
            } catch (_: Throwable) {}
            try {
                track.setEnabled(true)
            } catch (_: Throwable) {}
            track.addSink(renderer)
        }
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteTrack?.let { track ->
            try {
                track.removeSink(renderer)
            } catch (_: Throwable) {}
        }
        if (remoteRenderer === renderer) {
            remoteRenderer = null
        }
    }

    fun releaseRendererEgl() {
        if (rendererEglReleased.compareAndSet(false, true)) {
            try {
                rendererEglBase.release()
            } catch (_: Throwable) {}
        }
    }

    override fun onCleared() {
        cleanup()
        signalR.closeConnection()
        releaseRendererEgl()
        super.onCleared()
    }
}
