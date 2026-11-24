using System;
using System.Text.Json.Serialization;

namespace StreamSignaling.Api.Models
{
    /// <summary>
    /// Representa una sesión activa de transmisión entre emisor y receptor.
    /// Almacenada en memoria usando ConcurrentDictionary en SignalingHub.
    /// </summary>
    public class StreamSession
    {
        public string SessionCode { get; set; } = string.Empty;

        /// <summary>ConnectionId del emisor (también guardado en EmisorDeviceId por compatibilidad).</summary>
        public string EmisorDeviceId { get; set; } = string.Empty;

        public bool EmisorActive { get; set; }

        public string? ReceptorConnectionId { get; set; }

        public string? ReceptorDeviceId { get; set; }

        public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

        public string? OfferSdp { get; set; }

        public string? AnswerSdp { get; set; }
    }
}
