using System;
using System.Text.Json.Serialization;

namespace StreamSignaling.Api.DTOs
{
    /// <summary>
    /// Mensaje para crear una nueva sesión de transmisión (emisor).
    /// </summary>
    public class CreateSessionRequest
    {
        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("role")]
        public string Role { get; set; } = string.Empty; // "sender" or "receiver"
    }

    /// <summary>
    /// Respuesta al crear una sesión.
    /// </summary>
    public class CreateSessionResponse
    {
        [JsonPropertyName("success")]
        public bool Success { get; set; }

        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("message")]
        public string? Message { get; set; }
    }

    /// <summary>
    /// Mensaje para unirse a una sesión existente (receptor).
    /// </summary>
    public class JoinSessionRequest
    {
        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("device_id")]
        public string DeviceId { get; set; } = string.Empty;
    }

    /// <summary>
    /// Respuesta al unirse a una sesión.
    /// </summary>
    public class JoinSessionResponse
    {
        [JsonPropertyName("success")]
        public bool Success { get; set; }

        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("offer_sdp")]
        public string? OfferSdp { get; set; }

        [JsonPropertyName("message")]
        public string? Message { get; set; }
    }

    /// <summary>
    /// Respuesta para candidatos ICE.
    /// </summary>
    public class IceCandidateResponse
    {
        [JsonPropertyName("success")]
        public bool Success { get; set; }

        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("candidate")]
        public string? Candidate { get; set; }

        [JsonPropertyName("sdpMid")]
        public string? SdpMid { get; set; }

        [JsonPropertyName("sdpMLineIndex")]
        public int? SdpMLineIndex { get; set; }

        [JsonPropertyName("label")]
        public string? Label { get; set; }
    }

    /// <summary>
    /// Mensaje para finalizar una sesión.
    /// </summary>
    public class EndSessionRequest
    {
        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("device_id")]
        public string DeviceId { get; set; } = string.Empty;
    }

    /// <summary>
    /// Respuesta al finalizar sesión.
    /// </summary>
    public class EndSessionResponse
    {
        [JsonPropertyName("success")]
        public bool Success { get; set; }

        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("message")]
        public string? Message { get; set; }
    }

    /// <summary>
    /// Estado de la sesión para reportar al cliente.
    /// </summary>
    public class SessionStatus
    {
        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("emisor_active")]
        public bool EmisorActive { get; set; }

        [JsonPropertyName("connected_receptors")]
        public int ConnectedReceptors { get; set; }

        [JsonPropertyName("status")]
        public string Status { get; set; } = string.Empty; // "active", "ended", "full"
    }
}