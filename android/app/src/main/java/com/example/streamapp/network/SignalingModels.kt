package com.example.streamapp.network

/// <summary>
/// Modelos de señalización para el intercambio de SDP y candidatos ICE.
/// </summary>
data class SessionOffer(
    val sdp: String,
    val type: String = "offer"
)

data class SessionAnswer(
    val sdp: String,
    val type: String = "answer"
)

data class IceCandidateDto(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?,
    val label: String?
)

/// <summary>
/// Eventos del lado del cliente de SignalR.
/// </summary>
sealed class SignalREvent {
    data class SessionCreated(val sessionCode: String) : SignalREvent()
    data class JoinedSession(val sessionCode: String) : SignalREvent()
    data class SessionEnded(val message: String) : SignalREvent()
    data class IceCandidateReceived(val candidate: IceCandidateDto) : SignalREvent()
    data class Error(val message: String) : SignalREvent()
}