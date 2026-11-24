using System;
using System.Text.Json.Serialization;

namespace StreamSignaling.Api.DTOs
{
    public class RegisterDeviceRequest
    {
        [JsonPropertyName("deviceHardwareId")]
        public string DeviceHardwareId { get; set; } = string.Empty;

        [JsonPropertyName("deviceName")]
        public string DeviceName { get; set; } = string.Empty;

        [JsonPropertyName("fcmToken")]
        public string? FcmToken { get; set; }
    }

    public class DeviceDto
    {
        [JsonPropertyName("id")]
        public Guid Id { get; set; }

        [JsonPropertyName("deviceName")]
        public string DeviceName { get; set; } = string.Empty;

        [JsonPropertyName("deviceHardwareId")]
        public string DeviceHardwareId { get; set; } = string.Empty;

        [JsonPropertyName("isOnline")]
        public bool IsOnline { get; set; }

        [JsonPropertyName("lastSeenUtc")]
        public DateTime LastSeenUtc { get; set; }

        [JsonPropertyName("createdAtUtc")]
        public DateTime CreatedAtUtc { get; set; }
    }

    public class RequestStreamResponse
    {
        [JsonPropertyName("sessionId")]
        public string SessionId { get; set; } = string.Empty;

        [JsonPropertyName("deviceId")]
        public Guid DeviceId { get; set; }

        [JsonPropertyName("signalRNotified")]
        public bool SignalRNotified { get; set; }

        [JsonPropertyName("fcmSent")]
        public bool FcmSent { get; set; }

        [JsonPropertyName("message")]
        public string? Message { get; set; }
    }
}
