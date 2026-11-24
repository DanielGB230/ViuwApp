package com.example.streamapp.network

import com.microsoft.signalr.Action
import com.microsoft.signalr.Action1
import com.microsoft.signalr.Action3
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import com.viuwapp.stream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cliente SignalR alineado 1:1 con SignalingHub.cs del backend.
 *
 * Métodos invocados (cliente -> servidor):
 *   CreateSession(sessionCode), JoinSession(sessionCode, deviceId),
 *   SendOffer(sessionCode, sdp), SendAnswer(sessionCode, sdp),
 *   SendIceCandidate(sessionCode, candidate, sdpMid, sdpMLineIndex),
 *   EndSession(sessionCode, deviceId),
 *   RegisterDevicePresence(deviceId),
 *   RequestSwitchCamera(sessionCode), RequestToggleMute(sessionCode, isMuted)
 *
 * Eventos recibidos (servidor -> cliente):
 *   SessionCreated(code), SessionJoined(code), ReceiverJoined(code),
 *   OnPeerJoined(code), PeerJoined(code), ReceiverLeft(),
 *   OfferReceived(sdp), AnswerReceived(sdp),
 *   IceCandidateReceived(candidate, sdpMid, sdpMLineIndex),
 *   SessionEnded(message), SessionError(message),
 *   StartStream(sessionId) | OnStartStreamRequested(sessionId),
 *   OnSwitchCameraRequested, OnToggleMuteRequested(isMuted)
 */
class SignalRClient(
    baseUrl: String = BuildConfig.SIGNALING_BASE_URL
) {
    private val hubUrl: String = if (baseUrl.endsWith("/signaling")) baseUrl else "${baseUrl.trimEnd('/')}/signaling"
    private var connection: HubConnection? = null

    val isConnected: Boolean
        get() = connection?.connectionState == HubConnectionState.CONNECTED

    suspend fun connect() = withContext(Dispatchers.IO) {
        val current = connection
        if (current != null && current.connectionState == HubConnectionState.CONNECTED) {
            return@withContext
        }

        val conn = HubConnectionBuilder.create(hubUrl)
            .withHeader("X-Tunnel-Skip-AntiPhishing-Page", "true")
            .build()
        connection = conn
        conn.start().blockingAwait()
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            connection?.stop()?.blockingAwait()
        } catch (_: Throwable) {
            // La conexión ya estaba cerrada.
        } finally {
            connection = null
        }
    }

    /** Cierre inmediato sin esperar al servidor (usado al liberar el ViewModel/Servicio). */
    fun closeConnection() {
        try {
            connection?.close()
        } catch (_: Throwable) {
        } finally {
            connection = null
        }
    }

    // ---- Eventos del emisor ----
    fun onSessionCreated(handler: (String) -> Unit) = subscribe("SessionCreated", handler)
    fun onReceiverJoined(handler: (String) -> Unit) = subscribe("ReceiverJoined", handler)
    fun onAnswerReceived(handler: (String) -> Unit) = subscribe("AnswerReceived", handler)

    /** El Hub emite ambos nombres cuando el Receptor entra en la sala. */
    fun onPeerJoined(handler: (String) -> Unit) {
        subscribe("OnPeerJoined", handler)
        subscribe("PeerJoined", handler)
    }

    fun onReceiverLeft(handler: () -> Unit) {
        try {
            connection?.on("ReceiverLeft", Action { handler() })
        } catch (_: Throwable) {
        }
    }

    // ---- Eventos del receptor ----
    fun onSessionJoined(handler: (String) -> Unit) = subscribe("SessionJoined", handler)
    fun onOfferReceived(handler: (String) -> Unit) = subscribe("OfferReceived", handler)

    // ---- Eventos comunes ----
    fun onSessionEnded(handler: (String) -> Unit) = subscribe("SessionEnded", handler)
    fun onSessionError(handler: (String) -> Unit) = subscribe("SessionError", handler)

    fun onIceCandidate(handler: (candidate: String, sdpMid: String, sdpMLineIndex: Int) -> Unit) {
        try {
            connection?.on(
                "IceCandidateReceived",
                Action3<String, String, String> { candidate, sdpMid, sdpMLineIndex ->
                    handler(candidate, sdpMid, sdpMLineIndex.toIntOrNull() ?: 0)
                },
                String::class.java,
                String::class.java,
                String::class.java
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Orden de arranque de transmisión. El backend puede enviar cualquiera de los
     * dos nombres y el payload puede ser el string plano o un JSON { "sessionId": ... }.
     */
    fun onStartStream(handler: (String) -> Unit) {
        subscribe("StartStream") { raw -> handler(extractSessionId(raw)) }
        subscribe("OnStartStreamRequested") { raw -> handler(extractSessionId(raw)) }
    }

    /** Alias de compatibilidad con llamadas anteriores. */
    fun onStartStreamRequested(handler: (String) -> Unit) = onStartStream(handler)

    // ---- Controles remotos ----
    fun onSwitchCameraRequested(handler: () -> Unit) {
        try {
            connection?.on("OnSwitchCameraRequested", Action { handler() })
        } catch (_: Throwable) {
        }
    }

    fun onToggleMuteRequested(handler: (Boolean) -> Unit) {
        try {
            connection?.on(
                "OnToggleMuteRequested",
                Action1<java.lang.Boolean> { value -> handler(value == java.lang.Boolean.TRUE) },
                java.lang.Boolean::class.java
            )
        } catch (_: Throwable) {
        }
    }

    // ---- Invocaciones al hub ----
    fun createSession(sessionCode: String = "") = send("CreateSession", sessionCode)

    fun joinSession(sessionCode: String, deviceId: String) = send("JoinSession", sessionCode, deviceId)

    fun sendOffer(sessionCode: String, sdp: String) = send("SendOffer", sessionCode, sdp)

    fun sendAnswer(sessionCode: String, sdp: String) = send("SendAnswer", sessionCode, sdp)

    fun sendIceCandidate(
        sessionCode: String,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int
    ) = send("SendIceCandidate", sessionCode, candidate, sdpMid, sdpMLineIndex.toString())

    fun endSession(sessionCode: String, deviceId: String) = send("EndSession", sessionCode, deviceId)

    /** Invoca RegisterDevicePresence en el backend para marcar IsOnline = true. */
    fun registerDevicePresence(deviceId: String) = send("RegisterDevicePresence", deviceId)

    /** Solicita al Emisor cambiar de cámara. */
    suspend fun requestSwitchCamera(sessionCode: String) = withContext(Dispatchers.IO) {
        try {
            connection?.invoke("RequestSwitchCamera", sessionCode)?.blockingAwait()
        } catch (_: Throwable) {
        }
    }

    /** Solicita al Emisor activar/desactivar el micrófono. */
    suspend fun requestToggleMute(sessionCode: String, isMuted: Boolean) = withContext(Dispatchers.IO) {
        try {
            connection?.invoke("RequestToggleMute", sessionCode, isMuted)?.blockingAwait()
        } catch (_: Throwable) {
        }
    }

    private fun subscribe(event: String, handler: (String) -> Unit) {
        try {
            connection?.on(event, Action1<String> { value -> handler(value ?: "") }, String::class.java)
        } catch (_: Throwable) {
        }
    }

    private fun send(method: String, vararg args: Any) {
        try {
            connection?.send(method, *args)
        } catch (_: Throwable) {
            // Sin conexión: el estado de UI refleja el fallo.
        }
    }

    /**
     * Acepta "ABC123", "\"ABC123\"" o "{ \"sessionId\": \"ABC123\", \"sessionCode\": \"ABC123\" }".
     */
    private fun extractSessionId(raw: String?): String {
        val value = (raw ?: "").trim().trim('"')
        if (value.isEmpty()) return ""

        if (value.startsWith("{")) {
            return try {
                val json = JSONObject(value)
                json.optString("sessionId").ifEmpty {
                    json.optString("sessionCode").ifEmpty {
                        json.optString("code")
                    }
                }
            } catch (_: Throwable) {
                value
            }
        }

        return value
    }
}
