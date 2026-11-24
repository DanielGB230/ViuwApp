package com.example.streamapp.webrtc

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Envoltorio de WebRTC para captura local (emisor) y recepción remota (receptor).
 * Mantiene PeerConnectionFactory, capturador de cámara, pistas locales y la PeerConnection.
 */
class WebRtcClient(
    private val context: Context,
    private val isSender: Boolean,
    private val listener: Listener,
    /** EglBase compartido externamente (ViewModel). Si se pasa, NO se libera en close(). */
    externalEglBase: EglBase? = null
) {
    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onConnectionState(state: String)
        fun onError(message: String)
    }

    companion object {
        private val initialized = AtomicBoolean(false)

        @Synchronized
        private fun ensureInitialized(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }
    }

    /** Si se inyecta un EglBase externo, se usa ese; de lo contrario se crea uno propio. */
    private val ownsEglBase: Boolean = externalEglBase == null
    val eglBase: EglBase = externalEglBase ?: EglBase.create()

    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    init {
        ensureInitialized(context)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun localVideoTrack(): VideoTrack? = localVideoTrack

    private val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            Log.e("WebRtcClient", "onCameraError: $errorDescription")
            listener.onError("Error de cámara: $errorDescription")
        }

        override fun onCameraDisconnected() {
            Log.w("WebRtcClient", "onCameraDisconnected")
            listener.onError("Cámara desconectada")
        }

        override fun onCameraFreezed(errorDescription: String?) {
            Log.w("WebRtcClient", "onCameraFreezed: $errorDescription")
            listener.onError("Cámara congelada: $errorDescription")
        }

        override fun onCameraOpening(cameraName: String?) {
            Log.i("WebRtcClient", "Iniciando apertura de cámara: $cameraName")
        }

        override fun onFirstFrameAvailable() {
            Log.i("WebRtcClient", "Primer frame de cámara disponible (captura activa)")
        }

        override fun onCameraClosed() {
            Log.i("WebRtcClient", "Cámara cerrada")
        }
    }

    /** Inicia cámara (frontal por defecto, con fallback a trasera) y micrófono. */
    fun startLocalMedia(preferFrontCamera: Boolean = true) {
        if (!isSender) return

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            listener.onError("Permiso de cámara no concedido al intentar iniciar captura")
            return
        }

        val audioConstraints = MediaConstraints()
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("audio0", audioSource)

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        val source = factory.createVideoSource(false)
        videoSource = source

        val capturer = createCameraCapturer(preferFrontCamera)
            ?: throw IllegalStateException("No se encontró ninguna cámara disponible")

        videoCapturer = capturer
        capturer.initialize(helper, context, source.capturerObserver)
        try {
            capturer.startCapture(1280, 720, 30)
            Log.i("WebRtcClient", "startCapture solicitado a 1280x720@30fps")
        } catch (t: Throwable) {
            Log.w("WebRtcClient", "Fallo captura a 720p, intentando a 640x480: ${t.message}")
            capturer.startCapture(640, 480, 30)
        }

        localVideoTrack = factory.createVideoTrack("video0", source)
    }

    fun restartCamera(preferFrontCamera: Boolean = true) {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Throwable) {}
        try {
            videoCapturer?.dispose()
        } catch (_: Throwable) {}
        val source = videoSource ?: return
        val helper = surfaceTextureHelper ?: return
        val capturer = createCameraCapturer(preferFrontCamera) ?: run {
            listener.onError("No se pudo recrear capturador de cámara")
            return
        }
        videoCapturer = capturer
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
    }

    fun switchCamera() {
        try {
            videoCapturer?.switchCamera(null)
        } catch (t: Throwable) {
            listener.onError("No se pudo cambiar de cámara: ${t.message}")
        }
    }

    /** Activa o silencia la pista de audio local (micrófono) sin renegociar. */
    fun setAudioEnabled(enabled: Boolean) {
        try {
            localAudioTrack?.setEnabled(enabled)
            Log.i("WebRtcClient", "Micrófono ${if (enabled) "activado" else "silenciado"}")
        } catch (t: Throwable) {
            listener.onError("No se pudo cambiar el micrófono: ${t.message}")
        }
    }

    private fun createCameraCapturer(preferFrontCamera: Boolean): CameraVideoCapturer? {
        try {
            val enumerator = Camera2Enumerator(context)
            val names = enumerator.deviceNames
            if (names.isNotEmpty()) {
                val preferred = names.firstOrNull {
                    if (preferFrontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
                }
                val fallback = names.firstOrNull {
                    if (preferFrontCamera) enumerator.isBackFacing(it) else enumerator.isFrontFacing(it)
                }
                val chosen = preferred ?: fallback ?: names.first()
                enumerator.createCapturer(chosen, cameraEventsHandler)?.let { return it }
            }
        } catch (t: Throwable) {
            listener.onError("Camera2 no disponible (${t.message}); probando Camera1")
        }

        return try {
            val enumerator = Camera1Enumerator(true)
            val names = enumerator.deviceNames
            if (names.isEmpty()) {
                null
            } else {
                val preferred = names.firstOrNull {
                    if (preferFrontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
                }
                val fallback = names.firstOrNull {
                    if (preferFrontCamera) enumerator.isBackFacing(it) else enumerator.isFrontFacing(it)
                }
                enumerator.createCapturer(preferred ?: fallback ?: names.first(), cameraEventsHandler)
            }
        } catch (t: Throwable) {
            listener.onError("Camera1 no disponible: ${t.message}")
            null
        }
    }

    fun createPeerConnection() {
        if (peerConnection != null) return

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(config, peerConnectionObserver)
            ?: throw IllegalStateException("No se pudo crear la PeerConnection")

        peerConnection = pc

        localVideoTrack?.let { pc.addTrack(it, listOf("stream0")) }
        localAudioTrack?.let { pc.addTrack(it, listOf("stream0")) }

        preferVp8Codec()
    }

    private fun preferVp8Codec() {
        val pc = peerConnection ?: return
        try {
            val receiverCapabilities = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            val sortedCodecs = receiverCapabilities.codecs.sortedByDescending {
                it.name.equals("VP8", ignoreCase = true)
            }
            pc.transceivers.forEach { transceiver ->
                if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                    transceiver.setCodecPreferences(sortedCodecs)
                }
            }
            Log.i("WebRtcClient", "Preferencia de códec VP8 establecida correctamente")
        } catch (t: Throwable) {
            Log.w("WebRtcClient", "No se pudo fijar setCodecPreferences: ${t.message}")
        }
    }

    private fun preferVp8InSdp(sdp: String): String {
        return try {
            val lines = sdp.split("\r\n").toMutableList()
            val mVideoIndex = lines.indexOfFirst { it.startsWith("m=video ") }
            if (mVideoIndex == -1) return sdp

            val vp8Regex = Regex("^a=rtpmap:(\\d+)\\s+VP8/90000", RegexOption.IGNORE_CASE)
            val vp8Payload = lines.mapNotNull { line ->
                vp8Regex.find(line)?.groupValues?.get(1)
            }.firstOrNull() ?: return sdp

            val mVideoLine = lines[mVideoIndex]
            val parts = mVideoLine.split(" ").toMutableList()
            if (parts.size <= 3) return sdp

            val header = parts.take(3)
            val payloads = parts.drop(3).toMutableList()
            if (payloads.remove(vp8Payload)) {
                payloads.add(0, vp8Payload)
                lines[mVideoIndex] = (header + payloads).joinToString(" ")
                lines.joinToString("\r\n")
            } else {
                sdp
            }
        } catch (_: Throwable) {
            sdp
        }
    }

    fun createOffer(onSdpReady: (String) -> Unit) = createSdp(isOffer = true, onSdpReady)

    fun createAnswer(onSdpReady: (String) -> Unit) = createSdp(isOffer = false, onSdpReady)

    private fun createSdp(isOffer: Boolean, onSdpReady: (String) -> Unit) {
        val pc = peerConnection ?: run {
            listener.onError("PeerConnection no inicializada")
            return
        }

        val observer = object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val modifiedSdp = SessionDescription(sdp.type, preferVp8InSdp(sdp.description))
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        onSdpReady(modifiedSdp.description)
                    }

                    override fun onSetFailure(error: String) {
                        listener.onError("Error al fijar la descripción local: $error")
                    }
                }, modifiedSdp)
            }

            override fun onCreateFailure(error: String) {
                listener.onError("Error al crear SDP: $error")
            }
        }

        if (isOffer) {
            pc.createOffer(observer, MediaConstraints())
        } else {
            pc.createAnswer(observer, MediaConstraints())
        }
    }

    fun setRemoteDescription(
        sdp: String,
        type: SessionDescription.Type,
        onDone: () -> Unit = {}
    ) {
        val pc = peerConnection ?: run {
            listener.onError("PeerConnection no inicializada")
            return
        }

        val modifiedSdp = preferVp8InSdp(sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                preferVp8Codec()
                remoteDescriptionSet = true
                flushPendingCandidates()
                onDone()
            }

            override fun onSetFailure(error: String) {
                listener.onError("Error al fijar la descripción remota: $error")
            }
        }, SessionDescription(type, modifiedSdp))
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc == null || !remoteDescriptionSet) {
            pendingCandidates.add(candidate)
            return
        }
        pc.addIceCandidate(candidate)
    }

    private fun flushPendingCandidates() {
        val pc = peerConnection ?: return
        pendingCandidates.forEach { pc.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            listener.onConnectionState(state?.name ?: "UNKNOWN")
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            listener.onConnectionState(newState?.name ?: "UNKNOWN")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let { listener.onIceCandidate(it) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { listener.onRemoteVideoTrack(it) }
        }

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(channel: DataChannel?) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            (receiver?.track() as? VideoTrack)?.let { listener.onRemoteVideoTrack(it) }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            (transceiver?.receiver?.track() as? VideoTrack)?.let { listener.onRemoteVideoTrack(it) }
        }
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Throwable) {
        }

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        videoCapturer?.dispose()
        videoCapturer = null

        videoSource?.dispose()
        videoSource = null

        audioSource?.dispose()
        audioSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localVideoTrack?.dispose()
        localVideoTrack = null

        localAudioTrack?.dispose()
        localAudioTrack = null

        pendingCandidates.clear()

        try {
            factory.dispose()
        } catch (_: Throwable) {
        }

        // Solo liberar EglBase si es propio (no inyectado externamente).
        if (ownsEglBase) {
            try {
                eglBase.release()
            } catch (_: Throwable) {
            }
        }
    }
}
