using System.Text.Json.Serialization;

namespace StreamSignaling.Api.DTOs
{
    /// <summary>
    /// Solicitud para manejar una oferta SDP en el hub de señalización.
    /// </summary>
    public class HandleOfferRequest
    {
        [JsonPropertyName("session_code")]
        public string SessionCode { get; set; } = string.Empty;

        [JsonPropertyName("offer_sdp")]
        public string OfferSdp { get; set; } = string.Empty;
    }
}