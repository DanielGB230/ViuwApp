using System;
using System.Collections.Generic;

namespace StreamSignaling.Api.Models
{
    /// <summary>
    /// Usuario autenticado con Firebase Auth (Google). Se identifica por FirebaseUid.
    /// </summary>
    public class AppUser
    {
        public Guid Id { get; set; } = Guid.NewGuid();

        public string FirebaseUid { get; set; } = string.Empty;

        public string Email { get; set; } = string.Empty;

        public string DisplayName { get; set; } = string.Empty;

        public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;

        public ICollection<LinkedDevice> LinkedDevices { get; set; } = new List<LinkedDevice>();
    }
}
