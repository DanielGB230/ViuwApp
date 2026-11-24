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

/**
 * Cliente SignalR alineado 1:1 con SignalingHub.cs del backend.
 *
 * Métodos invocados (cliente -> servidor):
 *   CreateSession(sessionCode), JoinSession(sessionCode, deviceId),
 *   SendOffer(sessionCode, sdp), SendAnswer(sessionCode, sdp),
 *   SendIceCandidate(sessionCode, candidate, sdpMid, sdpMLineIndex),
 *   EndSession(sessionCode, deviceId)
 *
 * Eventos recibidos (servidor -> cliente):
 *   SessionCreated(code), SessionJoined(code), ReceiverJoined(code), ReceiverLeft(),
 *   OfferReceived(sdp), AnswerReceived(sdp),
 *   IceCandidateReceived(candidate, sdpMid, sdpMLineIndex),
 *   SessionEnded(message), SessionError(message)
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

        val conn = HubConnectionBuilder.create(hubUrl).build()
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

    /** Cierre inmediato sin esperar al servidor (usado al liberar el ViewModel). */
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

    fun onReceiverLeft(handler: () -> Unit) {
        connection?.on("ReceiverLeft", Action { handler() })
    }

    // ---- Eventos del receptor ----
    fun onSessionJoined(handler: (String) -> Unit) = subscribe("SessionJoined", handler)
    fun onOfferReceived(handler: (String) -> Unit) = subscribe("OfferReceived", handler)

    // ---- Eventos comunes ----
    fun onSessionEnded(handler: (String) -> Unit) = subscribe("SessionEnded", handler)
    fun onSessionError(handler: (String) -> Unit) = subscribe("SessionError", handler)

    fun onIceCandidate(handler: (candidate: String, sdpMid: String, sdpMLineIndex: Int) -> Unit) {
        connection?.on(
            "IceCandidateReceived",
            Action3<String, String, String> { candidate, sdpMid, sdpMLineIndex ->
                handler(candidate, sdpMid, sdpMLineIndex.toIntOrNull() ?: 0)
            },
            String::class.java,
            String::class.java,
            String::class.java
        )
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

    /** Escucha el evento OnStartStreamRequested(sessionId: String) enviado por el backend. */
    fun onStartStreamRequested(handler: (String) -> Unit) = subscribe("OnStartStreamRequested", handler)

    private fun subscribe(event: String, handler: (String) -> Unit) {
        connection?.on(event, Action1<String> { value -> handler(value) }, String::class.java)
    }

    private fun send(method: String, vararg args: Any) {
        try {
            connection?.send(method, *args)
        } catch (_: Throwable) {
            // Sin conexión: el estado de UI refleja el fallo.
        }
    }
}
