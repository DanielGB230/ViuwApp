using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using StreamSignaling.Api.Auth;
using StreamSignaling.Api.Data;
using StreamSignaling.Api.DTOs;
using StreamSignaling.Api.Hubs;
using StreamSignaling.Api.Models;
using StreamSignaling.Api.Services;

namespace StreamSignaling.Api.Controllers
{
    [ApiController]
    [Route("api/devices")]
    public class DevicesController : ControllerBase
    {
        private readonly AppDbContext _db;
        private readonly IHubContext<SignalingHub> _hub;
        private readonly FcmNotificationService _fcm;
        private readonly ILogger<DevicesController> _logger;

        public DevicesController(
            AppDbContext db,
            IHubContext<SignalingHub> hub,
            FcmNotificationService fcm,
            ILogger<DevicesController> logger)
        {
            _db = db;
            _hub = hub;
            _fcm = fcm;
            _logger = logger;
        }

        /// <summary>
        /// Crea o actualiza el AppUser y vincula/actualiza el dispositivo SENDER
        /// con su hardware id y token FCM.
        /// </summary>
        [HttpPost("register")]
        public async Task<IActionResult> Register(
            [FromBody] RegisterDeviceRequest request,
            CancellationToken cancellationToken)
        {
            if (request == null || string.IsNullOrWhiteSpace(request.DeviceHardwareId))
            {
                return BadRequest(new
                {
                    error = "invalid_hardware_id",
                    message = "DeviceHardwareId es obligatorio."
                });
            }

            var firebaseUid = HttpContext.GetFirebaseUid();
            var email = HttpContext.GetFirebaseEmail() ?? string.Empty;
            var displayName = HttpContext.GetFirebaseName();

            var user = await _db.Users
                .FirstOrDefaultAsync(u => u.FirebaseUid == firebaseUid, cancellationToken);

            if (user == null)
            {
                user = new AppUser { FirebaseUid = firebaseUid };
                _db.Users.Add(user);
            }

            if (!string.IsNullOrWhiteSpace(email))
            {
                user.Email = email;
            }

            if (!string.IsNullOrWhiteSpace(displayName))
            {
                user.DisplayName = displayName;
            }

            var hardwareId = request.DeviceHardwareId.Trim();
            var device = await _db.LinkedDevices.FirstOrDefaultAsync(
                d => d.FirebaseUid == firebaseUid && d.DeviceHardwareId == hardwareId,
                cancellationToken);

            if (device == null)
            {
                device = new LinkedDevice
                {
                    FirebaseUid = firebaseUid,
                    DeviceHardwareId = hardwareId,
                    Role = "SENDER"
                };
                _db.LinkedDevices.Add(device);
            }

            device.DeviceName = string.IsNullOrWhiteSpace(request.DeviceName)
                ? "Dispositivo Android"
                : request.DeviceName.Trim();
            device.Role = "SENDER";
            device.LastSeenUtc = DateTime.UtcNow;

            if (!string.IsNullOrWhiteSpace(request.FcmToken))
            {
                device.FcmToken = request.FcmToken.Trim();
            }

            await _db.SaveChangesAsync(cancellationToken);

            _logger.LogInformation(
                "Dispositivo {DeviceId} ({DeviceName}) registrado para FirebaseUid {FirebaseUid}.",
                device.Id,
                device.DeviceName,
                firebaseUid);

            return Ok(ToDto(device));
        }

        /// <summary>Lista los dispositivos vinculados al usuario autenticado.</summary>
        [HttpGet("mine")]
        public async Task<IActionResult> Mine(CancellationToken cancellationToken)
        {
            var firebaseUid = HttpContext.GetFirebaseUid();

            var devices = await _db.LinkedDevices
                .AsNoTracking()
                .Where(d => d.FirebaseUid == firebaseUid)
                .OrderBy(d => d.CreatedAtUtc)
                .Select(d => new DeviceDto
                {
                    Id = d.Id,
                    DeviceName = d.DeviceName,
                    DeviceHardwareId = d.DeviceHardwareId,
                    IsOnline = d.IsOnline,
                    LastSeenUtc = d.LastSeenUtc,
                    CreatedAtUtc = d.CreatedAtUtc
                })
                .ToListAsync(cancellationToken);

            return Ok(devices);
        }

        /// <summary>
        /// Ordena al dispositivo supervisado iniciar una transmisión.
        /// Genera el sessionId, avisa por SignalR si está conectado y envía
        /// un FCM data message de alta prioridad para despertarlo.
        /// </summary>
        [HttpPost("{deviceId:guid}/request-stream")]
        public async Task<IActionResult> RequestStream(
            Guid deviceId,
            CancellationToken cancellationToken)
        {
            var firebaseUid = HttpContext.GetFirebaseUid();

            var device = await _db.LinkedDevices.FirstOrDefaultAsync(
                d => d.Id == deviceId && d.FirebaseUid == firebaseUid,
                cancellationToken);

            if (device == null)
            {
                return NotFound(new
                {
                    error = "device_not_found",
                    message = "El dispositivo no existe o no pertenece al usuario autenticado."
                });
            }

            var sessionId = GenerateUniqueSessionCode();

            var signalRNotified = false;
            if (SignalingHub.TryGetDeviceConnection(device.DeviceHardwareId, out var connectionId) &&
                !string.IsNullOrWhiteSpace(connectionId))
            {
                try
                {
                    await _hub.Clients
                        .Client(connectionId)
                        .SendAsync("OnStartStreamRequested", sessionId, cancellationToken);
                    signalRNotified = true;
                }
                catch (Exception ex)
                {
                    _logger.LogWarning(
                        ex,
                        "No se pudo notificar por SignalR al dispositivo {DeviceId}.",
                        device.Id);
                }
            }

            var fcmSent = await _fcm.SendStartStreamAsync(device.FcmToken, sessionId, cancellationToken);

            device.LastSeenUtc = DateTime.UtcNow;
            await _db.SaveChangesAsync(cancellationToken);

            return Ok(new RequestStreamResponse
            {
                SessionId = sessionId,
                DeviceId = device.Id,
                SignalRNotified = signalRNotified,
                FcmSent = fcmSent,
                Message = signalRNotified || fcmSent
                    ? "Orden de transmisión enviada."
                    : "No hay conexión activa ni token FCM; el dispositivo deberá abrir la app."
            });
        }

        private static string GenerateUniqueSessionCode()
        {
            for (var attempt = 0; attempt < 25; attempt++)
            {
                var code = SessionCodeGenerator.Generate();
                if (!SignalingHub.SessionExists(code))
                {
                    return code;
                }
            }

            return SessionCodeGenerator.Generate();
        }

        private static DeviceDto ToDto(LinkedDevice device) => new()
        {
            Id = device.Id,
            DeviceName = device.DeviceName,
            DeviceHardwareId = device.DeviceHardwareId,
            IsOnline = device.IsOnline,
            LastSeenUtc = device.LastSeenUtc,
            CreatedAtUtc = device.CreatedAtUtc
        };
    }
}
