using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Threading.Tasks;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using StreamSignaling.Api.Data;
using StreamSignaling.Api.Models;

namespace StreamSignaling.Api.Hubs
{
    /// <summary>
    /// Hub de SignalR para la señalización WebRTC entre emisor y receptor.
    /// Estado en memoria (ConcurrentDictionary) y enrutamiento por ConnectionId.
    ///
    /// Contrato (cliente -> servidor):
    ///   CreateSession(sessionCode)                  -> evento "SessionCreated" (code) | "SessionError"
    ///   JoinSession(sessionCode, deviceId)          -> evento "SessionJoined" (code)  | "SessionError"
    ///   SendOffer(sessionCode, offerSdp)            -> evento "OfferReceived" (sdp) al receptor
    ///   SendAnswer(sessionCode, answerSdp)          -> evento "AnswerReceived" (sdp) al emisor
    ///   SendIceCandidate(code, candidate, mid, idx) -> evento "IceCandidateReceived" al par
    ///   EndSession(sessionCode, deviceId)           -> evento "SessionEnded" a ambos
    ///   RegisterDevicePresence(deviceId)            -> evento "PresenceRegistered" (deviceId)
    ///   SetCameraMode(sessionCode, mode)            -> "OnCameraModeChanged" (mode)
    ///   SetMicrophoneEnabled(sessionCode, enabled)  -> "OnMicrophoneEnabledChanged" (enabled)
    ///   StopStream(sessionCode)                     -> "OnStreamStopped"
    ///
    /// Eventos adicionales del servidor:
    ///   ReceiverJoined(code)  -> al emisor cuando el receptor se une
    ///   ReceiverLeft()        -> al emisor cuando el receptor se desconecta
    ///   OnStartStreamRequested(sessionId) -> al dispositivo SENDER conectado
    /// </summary>
    public class SignalingHub : Hub
    {
        private static readonly ConcurrentDictionary<string, StreamSession> Sessions = new();

        // deviceHardwareId -> connectionId y viceversa, para despertar/ordenar por REST.
        private static readonly ConcurrentDictionary<string, string> DeviceConnections = new(StringComparer.Ordinal);
        private static readonly ConcurrentDictionary<string, string> ConnectionDevices = new(StringComparer.Ordinal);

        // Códigos de 6 caracteres sin ambigüedad: sin 0, O, 1, I, L.
        private const string ValidChars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

        private readonly IServiceScopeFactory _scopeFactory;
        private readonly ILogger<SignalingHub> _logger;

        public SignalingHub(IServiceScopeFactory scopeFactory, ILogger<SignalingHub> logger)
        {
            _scopeFactory = scopeFactory;
            _logger = logger;
        }

        /// <summary>
        /// Registra la conexión SignalR del dispositivo SENDER y actualiza su
        /// presencia (IsOnline / LastSeenUtc) en SQL Server.
        /// Suscribe la conexión al grupo por DeviceHardwareId para que
        /// DevicesController.RequestStream siempre encuentre la conexión.
        /// </summary>
        public async Task RegisterDevicePresence(string deviceId)
        {
            var hardwareId = (deviceId ?? string.Empty).Trim().ToLowerInvariant();
            if (hardwareId.Length == 0)
            {
                return;
            }

            // Suscribir al grupo por DeviceHardwareId (compatibilidad)
            await Groups.AddToGroupAsync(Context.ConnectionId, hardwareId);

            // Suscribir también al grupo por cada LinkedDevice.Id (GUID en minúsculas),
            // que es el otro identificador que usa DevicesController.RequestStream.
            try
            {
                using var scope = _scopeFactory.CreateScope();
                var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
                var devices = await db.LinkedDevices
                    .AsNoTracking()
                    .Select(d => new { d.Id, d.DeviceHardwareId })
                    .ToListAsync();

                foreach (var device in devices)
                {
                    if (!string.IsNullOrWhiteSpace(device.DeviceHardwareId) &&
                        string.Equals(device.DeviceHardwareId.Trim(), hardwareId, StringComparison.OrdinalIgnoreCase))
                    {
                        await Groups.AddToGroupAsync(
                            Context.ConnectionId,
                            device.Id.ToString().ToLowerInvariant());
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "No se pudieron suscribir los grupos por LinkedDevice.Id.");
            }

            if (ConnectionDevices.TryGetValue(Context.ConnectionId, out var previous) &&
                !string.Equals(previous, hardwareId, StringComparison.OrdinalIgnoreCase))
            {
                if (DeviceConnections.TryGetValue(previous, out var previousConnection) &&
                    previousConnection == Context.ConnectionId)
                {
                    DeviceConnections.TryRemove(previous, out _);
                }
            }

            DeviceConnections[hardwareId] = Context.ConnectionId;
            ConnectionDevices[Context.ConnectionId] = hardwareId;

            await UpdatePresenceAsync(hardwareId, isOnline: true);

            await Clients.Caller.SendAsync("PresenceRegistered", hardwareId);
        }

        /// <summary>Crea una sesión. Si sessionCode viene vacío se genera uno nuevo.</summary>
        public async Task CreateSession(string? sessionCode = null)
        {
            var code = string.IsNullOrWhiteSpace(sessionCode)
                ? GenerateSessionCode()
                : Normalize(sessionCode);

            if (Sessions.TryGetValue(code, out var existing) &&
                existing.EmisorActive &&
                !string.Equals(existing.EmisorDeviceId, Context.ConnectionId, StringComparison.Ordinal))
            {
                await Clients.Caller.SendAsync("SessionError", "El código de sesión ya está en uso.");
                return;
            }

            var session = existing ?? new StreamSession
            {
                SessionCode = code,
                CreatedAt = DateTime.UtcNow
            };

            session.SessionCode = code;
            session.EmisorActive = true;
            session.EmisorDeviceId = Context.ConnectionId;
            if (session.CreatedAt == default)
            {
                session.CreatedAt = DateTime.UtcNow;
            }

            Sessions[code] = session;

            // Necesario para que los controles remotos lleguen al grupo de la sala.
            await Groups.AddToGroupAsync(Context.ConnectionId, code);

            await Clients.Caller.SendAsync("SessionCreated", code);

            // Si el receptor llegó antes (flujo FCM + request-stream), avisamos
            // al emisor para que cree y envíe la oferta WebRTC inmediatamente.
            if (!string.IsNullOrEmpty(session.ReceptorConnectionId))
            {
                await Clients.Client(Context.ConnectionId).SendAsync("ReceiverJoined", session.SessionCode);
                await Clients.Client(Context.ConnectionId).SendAsync("OnPeerJoined", session.SessionCode);
                await Clients.Client(Context.ConnectionId).SendAsync("PeerJoined", session.SessionCode);
            }
        }

        /// <summary>Une al receptor a una sesión existente y avisa al emisor.</summary>
        public async Task JoinSession(string sessionCode, string deviceId)
        {
            var code = Normalize(sessionCode);

            if (!Sessions.TryGetValue(code, out var session))
            {
                // El receptor puede llegar antes que el emisor (despertar por FCM).
                // Creamos una sala pendiente; CreateSession la completará.
                session = new StreamSession
                {
                    SessionCode = code,
                    EmisorActive = false,
                    CreatedAt = DateTime.UtcNow
                };
                Sessions[code] = session;
            }

            session.ReceptorConnectionId = Context.ConnectionId;
            session.ReceptorDeviceId = deviceId;

            // Necesario para que los controles remotos lleguen al grupo de la sala.
            await Groups.AddToGroupAsync(Context.ConnectionId, code);

            await Clients.Caller.SendAsync("SessionJoined", session.SessionCode);

            if (session.EmisorActive && !string.IsNullOrEmpty(session.EmisorDeviceId))
            {
                await Clients.Client(session.EmisorDeviceId).SendAsync("ReceiverJoined", session.SessionCode);
                await Clients.Client(session.EmisorDeviceId).SendAsync("OnPeerJoined", session.SessionCode);
                await Clients.Client(session.EmisorDeviceId).SendAsync("PeerJoined", session.SessionCode);
            }
        }

        /// <summary>Emisor envía su oferta SDP al receptor.</summary>
        public async Task SendOffer(string sessionCode, string offerSdp)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryGetValue(code, out var session))
            {
                return;
            }

            session.OfferSdp = offerSdp;

            if (!string.IsNullOrEmpty(session.ReceptorConnectionId))
            {
                await Clients.Client(session.ReceptorConnectionId).SendAsync("OfferReceived", offerSdp);
            }
        }

        /// <summary>Receptor envía su respuesta SDP al emisor.</summary>
        public async Task SendAnswer(string sessionCode, string answerSdp)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryGetValue(code, out var session))
            {
                return;
            }

            session.AnswerSdp = answerSdp;

            if (!string.IsNullOrEmpty(session.EmisorDeviceId))
            {
                await Clients.Client(session.EmisorDeviceId).SendAsync("AnswerReceived", answerSdp);
            }
        }

        /// <summary>Reenvía un candidato ICE al otro extremo de la sesión.</summary>
        public async Task SendIceCandidate(string sessionCode, string candidate, string sdpMid, string sdpMLineIndex)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryGetValue(code, out var session))
            {
                return;
            }

            var isEmisor = Context.ConnectionId == session.EmisorDeviceId;
            var target = isEmisor ? session.ReceptorConnectionId : session.EmisorDeviceId;

            if (string.IsNullOrEmpty(target))
            {
                return;
            }

            await Clients.Client(target).SendAsync(
                "IceCandidateReceived",
                candidate,
                sdpMid ?? string.Empty,
                sdpMLineIndex ?? "0");
        }

        /// <summary>Finaliza la sesión y notifica a ambos extremos.</summary>
        public async Task EndSession(string sessionCode, string deviceId)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryRemove(code, out var session))
            {
                return;
            }

            session.EmisorActive = false;

            var targets = new List<string>();
            if (!string.IsNullOrEmpty(session.EmisorDeviceId)) targets.Add(session.EmisorDeviceId);
            if (!string.IsNullOrEmpty(session.ReceptorConnectionId)) targets.Add(session.ReceptorConnectionId);

            foreach (var connectionId in targets.Distinct())
            {
                await Clients.Client(connectionId).SendAsync("SessionEnded", "La transmisión ha finalizado.");
            }
        }

        /// <summary>Solicita cambiar de cámara (frontal/trasera) en la sesión.</summary>
        public async Task RequestSwitchCamera(string sessionCode)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryGetValue(code, out var session))
            {
                return;
            }

            if (!string.IsNullOrEmpty(session.EmisorDeviceId))
            {
                await Clients.Client(session.EmisorDeviceId).SendAsync("OnSwitchCameraRequested");
            }

            if (!string.IsNullOrEmpty(session.ReceptorConnectionId))
            {
                await Clients.Client(session.ReceptorConnectionId).SendAsync("OnSwitchCameraRequested");
            }
        }

        /// <summary>Solicita activar/desactivar el micrófono en la sesión.</summary>
        public async Task RequestToggleMute(string sessionCode, bool isMuted)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryGetValue(code, out var session))
            {
                return;
            }

            if (!string.IsNullOrEmpty(session.EmisorDeviceId))
            {
                await Clients.Client(session.EmisorDeviceId).SendAsync("OnToggleMuteRequested", isMuted);
            }

            if (!string.IsNullOrEmpty(session.ReceptorConnectionId))
            {
                await Clients.Client(session.ReceptorConnectionId).SendAsync("OnToggleMuteRequested", isMuted);
            }
        }

        /// <summary>
        /// Cambia el modo de cámara del Emisor ("BACK", "FRONT" u "OFF").
        /// Solo miembros de la sala pueden invocarlo; se reenvía al otro extremo.
        /// </summary>
        public async Task SetCameraMode(string sessionCode, string mode)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryGetValue(code, out var session) || !IsSessionMember(session))
            {
                return;
            }

            var normalizedMode = (mode ?? string.Empty).Trim().ToUpperInvariant();
            if (normalizedMode != "BACK" && normalizedMode != "FRONT" && normalizedMode != "OFF")
            {
                return;
            }

            await Clients.OthersInGroup(code).SendAsync("OnCameraModeChanged", normalizedMode);
        }

        /// <summary>
        /// Activa/desactiva el micrófono del Emisor. Solo miembros de la sala.
        /// </summary>
        public async Task SetMicrophoneEnabled(string sessionCode, bool enabled)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryGetValue(code, out var session) || !IsSessionMember(session))
            {
                return;
            }

            await Clients.OthersInGroup(code).SendAsync("OnMicrophoneEnabledChanged", enabled);
        }

        /// <summary>
        /// Detiene el streaming del Emisor sin destruir la sesión.
        /// Solo miembros de la sala.
        /// </summary>
        public async Task StopStream(string sessionCode)
        {
            var code = Normalize(sessionCode);
            if (!Sessions.TryGetValue(code, out var session) || !IsSessionMember(session))
            {
                return;
            }

            await Clients.OthersInGroup(code).SendAsync("OnStreamStopped");
        }

        /// <summary>Comprueba que la conexión actual pertenece a la sesión.</summary>
        private bool IsSessionMember(StreamSession session) =>
            session.EmisorDeviceId == Context.ConnectionId ||
            session.ReceptorConnectionId == Context.ConnectionId;

        public override async Task OnDisconnectedAsync(Exception? exception)
        {
            var connectionId = Context.ConnectionId;

            if (ConnectionDevices.TryRemove(connectionId, out var hardwareId) &&
                DeviceConnections.TryGetValue(hardwareId, out var mappedConnection) &&
                mappedConnection == connectionId)
            {
                DeviceConnections.TryRemove(hardwareId, out _);
                await UpdatePresenceAsync(hardwareId, isOnline: false);
            }

            foreach (var pair in Sessions.ToArray())
            {
                var session = pair.Value;

                if (session.EmisorDeviceId == connectionId)
                {
                    session.EmisorActive = false;
                    if (!string.IsNullOrEmpty(session.ReceptorConnectionId))
                    {
                        await Clients.Client(session.ReceptorConnectionId)
                            .SendAsync("SessionEnded", "El emisor se ha desconectado.");
                    }
                    Sessions.TryRemove(pair.Key, out _);
                }
                else if (session.ReceptorConnectionId == connectionId)
                {
                    session.ReceptorConnectionId = null;
                    if (session.EmisorActive && !string.IsNullOrEmpty(session.EmisorDeviceId))
                    {
                        await Clients.Client(session.EmisorDeviceId).SendAsync("ReceiverLeft");
                    }
                    else if (!session.EmisorActive)
                    {
                        // Sala pendiente sin emisor: no dejar sesiones huérfanas en memoria.
                        Sessions.TryRemove(pair.Key, out _);
                    }
                }
            }

            await base.OnDisconnectedAsync(exception);
        }

        /// <summary>Busca la conexión SignalR activa de un dispositivo SENDER.</summary>
        public static bool TryGetDeviceConnection(string deviceHardwareId, out string? connectionId)
            => DeviceConnections.TryGetValue(deviceHardwareId, out connectionId);

        /// <summary>Indica si un código de sala ya existe en memoria.</summary>
        public static bool SessionExists(string sessionCode)
            => Sessions.ContainsKey(Normalize(sessionCode));

        private async Task UpdatePresenceAsync(string deviceHardwareId, bool isOnline)
        {
            try
            {
                using var scope = _scopeFactory.CreateScope();
                var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();

                var devices = await db.LinkedDevices
                    .Where(d => d.DeviceHardwareId == deviceHardwareId)
                    .ToListAsync();

                if (devices.Count == 0)
                {
                    return;
                }

                var now = DateTime.UtcNow;
                foreach (var device in devices)
                {
                    device.IsOnline = isOnline;
                    device.LastSeenUtc = now;
                }

                await db.SaveChangesAsync();
            }
            catch (Exception ex)
            {
                // La presencia en memoria sigue funcionando aunque SQL Server no esté disponible.
                _logger.LogWarning(
                    ex,
                    "No se pudo actualizar la presencia del dispositivo {DeviceHardwareId}.",
                    deviceHardwareId);
            }
        }

        private static string Normalize(string? sessionCode) =>
            (sessionCode ?? string.Empty).Trim().ToUpperInvariant();

        private static string GenerateSessionCode()
        {
            var chars = new char[6];
            for (var i = 0; i < chars.Length; i++)
            {
                chars[i] = ValidChars[RandomNumberGenerator.GetInt32(ValidChars.Length)];
            }
            return new string(chars);
        }
    }
}
