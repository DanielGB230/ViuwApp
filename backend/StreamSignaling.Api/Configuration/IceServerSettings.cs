using System.Text.Json.Serialization;

namespace StreamSignaling.Api.Configuration
{
    /// <summary>
    /// Configuración de servidores STUN/TURN leída de appsettings.json.
    /// </summary>
    public class IceServerSettings
    {
        [JsonPropertyName("IceServers")]
        public List<IceServer> IceServers { get; set; } = new();

        public record IceServer
        {
            [JsonPropertyName("Urls")]
            public string Urls { get; set; } = string.Empty;

            [JsonPropertyName ("Username")]
            public string? Username { get; set; }

            [JsonPropertyName ("Credential")]
            public string? Credential { get; set; }
        }
    }
}