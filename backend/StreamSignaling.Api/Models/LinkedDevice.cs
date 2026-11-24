using System;

namespace StreamSignaling.Api.Models
{
    /// <summary>
    /// Dispositivo supervisado (SENDER) vinculado a una cuenta de Firebase.
    /// DeviceHardwareId es único por usuario y FcmToken permite despertarlo remotamente.
    /// </summary>
    public class LinkedDevice
    {
        public Guid Id { get; set; } = Guid.NewGuid();

        public string FirebaseUid { get; set; } = string.Empty;

        public string DeviceHardwareId { get; set; } = string.Empty;

        public string DeviceName { get; set; } = string.Empty;

        public string FcmToken { get; set; } = string.Empty;

        /// <summary>Rol del dispositivo dentro de la arquitectura parental ("SENDER").</summary>
        public string Role { get; set; } = "SENDER";

        public bool IsOnline { get; set; }

        public DateTime LastSeenUtc { get; set; } = DateTime.UtcNow;

        public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;

        public AppUser? User { get; set; }
    }
}
