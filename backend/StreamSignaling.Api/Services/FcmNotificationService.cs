using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using FirebaseAdmin.Messaging;
using Microsoft.Extensions.Logging;
using StreamSignaling.Api.Auth;

namespace StreamSignaling.Api.Services
{
    /// <summary>
    /// Envía mensajes de datos de alta prioridad por FCM para despertar al
    /// dispositivo supervisado aunque la app esté cerrada o recién reiniciada.
    /// </summary>
    public class FcmNotificationService
    {
        private readonly ILogger<FcmNotificationService> _logger;

        public FcmNotificationService(ILogger<FcmNotificationService> logger)
        {
            _logger = logger;
        }

        public async Task<bool> SendStartStreamAsync(
            string? fcmToken,
            string sessionId,
            CancellationToken cancellationToken = default)
        {
            if (!FirebaseAdminState.IsInitialized)
            {
                _logger.LogWarning("FCM omitido: Firebase Admin no está inicializado.");
                return false;
            }

            if (string.IsNullOrWhiteSpace(fcmToken))
            {
                _logger.LogWarning("FCM omitido: el dispositivo no tiene FcmToken registrado.");
                return false;
            }

            try
            {
                var message = new Message
                {
                    Token = fcmToken,
                    Data = new Dictionary<string, string>
                    {
                        ["action"] = "START_STREAM",
                        ["type"] = "START_STREAM",
                        ["sessionCode"] = sessionId,
                        ["sessionId"] = sessionId
                    },
                    Android = new AndroidConfig
                    {
                        Priority = Priority.High,
                        TimeToLive = TimeSpan.FromMinutes(10),
                        CollapseKey = "viuw_start_stream"
                    }
                };

                var messageId = await FirebaseMessaging.DefaultInstance.SendAsync(message, cancellationToken);
                _logger.LogInformation(
                    "FCM START_STREAM enviado a {TokenPrefix}... (session {SessionId}, message {MessageId}).",
                    fcmToken.Length > 12 ? fcmToken[..12] : fcmToken,
                    sessionId,
                    messageId);

                return true;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(
                    ex,
                    "No se pudo enviar FCM START_STREAM para la sesión {SessionId}. " +
                    "El token puede haber caducado; el dispositivo lo actualizará al abrir la app.",
                    sessionId);
                return false;
            }
        }
    }
}
