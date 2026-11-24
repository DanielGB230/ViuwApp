package com.example.streamapp.domain

/// <summary>
/// Modelo de datos para el estado de la sesión de transmisión.
/// </summary>
data class StreamSessionState(
    val isTransmitting: Boolean = false,
    val sessionCode: String = "",
    val connectedPeers: Int = 0,
    val cameraActive: Boolean = false,
    val microphoneActive: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Disconnected
)

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
    Error
}

/// <summary>
/// DTO para mensajes de señalización entre cliente y servidor.
/// </summary>
data class SignalingMessage(
    val type: MessageType,
    val payload: String,
    val sessionCode: String?
)

enum class MessageType {
    CreateSession,
    JoinSession,
    Offer,
    Answer,
    IceCandidate,
    EndSession,
    SessionEnded
}