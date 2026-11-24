using System;
using System.Threading.Tasks;
using StreamSignaling.Api.DTOs;
using StreamSignaling.Api.Models;

using System.Collections.Concurrent;

namespace StreamSignaling.Api.Services
{
    /// <summary>
    /// Servicio de gestión de sesiones en memoria.
    /// Proporciona métodos para crear, obtener y destruir sesiones StreamSession.
    /// </summary>
    public interface ISessionManagerService
    {
        Task<CreateSessionResponse> CreateSessionAsync(string? sessionCode = null);
        Task<JoinSessionResponse> JoinSessionAsync(JoinSessionRequest request, string connectionId);
        Task<EndSessionResponse> EndSessionAsync(EndSessionRequest request);
        Task<SessionStatus> GetSessionStatusAsync(string sessionCode);
    }

    /// <summary>
    /// Implementación del servicio de gestión de sesiones que utiliza ConcurrentDictionary para almacenar sesiones en memoria.
    /// </summary>
    public class SessionManagerService : ISessionManagerService
    {
        private readonly ConcurrentDictionary<string, StreamSession> _sessions = new();

        public async Task<CreateSessionResponse> CreateSessionAsync(string? sessionCode = null)
        {
            string code;
            if (!string.IsNullOrEmpty(sessionCode))
            {
                if (_sessions.TryGetValue(sessionCode, out var existing) && existing.EmisorActive)
                {
                    return new CreateSessionResponse
                    {
                        Success = false,
                        Message = "El código de sesión ya está en uso por otro emisor."
                    };
                }
                code = sessionCode;
            }
            else
            {
                code = GenerateSessionCode();
            }

            var session = new StreamSession
            {
                SessionCode = code,
                EmisorActive = true,
                CreatedAt = DateTime.UtcNow
            };

            _sessions.TryAdd(code, session);

            return new CreateSessionResponse
            {
                Success = true,
                SessionCode = code
            };
        }

        public async Task<JoinSessionResponse> JoinSessionAsync(JoinSessionRequest request, string connectionId)
        {
            if (!_sessions.TryGetValue(request.SessionCode, out var session))
            {
                return new JoinSessionResponse
                {
                    Success = false,
                    Message = "Código de sesión no encontrado.",
                    SessionCode = request.SessionCode
                };
            }

            if (!session.EmisorActive)
            {
                return new JoinSessionResponse
                {
                    Success = false,
                    Message = "La sesión ya ha sido finalizada por el emisor.",
                    SessionCode = request.SessionCode
                };
            }

            // Registrar conexión del receptor
            if (string.IsNullOrEmpty(session.ReceptorConnectionId))
            {
                session.ReceptorConnectionId = connectionId;
            }

            return new JoinSessionResponse
            {
                Success = true,
                SessionCode = request.SessionCode
            };
        }

        public async Task<EndSessionResponse> EndSessionAsync(EndSessionRequest request)
        {
            if (!_sessions.TryGetValue(request.SessionCode, out var session))
            {
                return new EndSessionResponse
                {
                    Success = false,
                    Message = "Sesión no encontrada.",
                    SessionCode = request.SessionCode
                };
            }

            session.EmisorActive = false;

            return new EndSessionResponse
            {
                Success = true,
                SessionCode = request.SessionCode,
                Message = "Sesión finalizada correctamente."
            };
        }

        public async Task<SessionStatus> GetSessionStatusAsync(string sessionCode)
        {
            if (!_sessions.TryGetValue(sessionCode, out var session))
            {
                return new SessionStatus
                {
                    SessionCode = sessionCode,
                    Status = "not_found"
                };
            }

            return new SessionStatus
            {
                SessionCode = session.SessionCode,
                EmisorActive = session.EmisorActive,
                ConnectedReceptors = session.ReceptorConnectionId != null ? 1 : 0,
                Status = session.EmisorActive ? "active" : "ended"
            };
        }

        private static string GenerateSessionCode()
        {
            const string validChars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
            var bytes = new byte[3];
            using var rng = System.Security.Cryptography.RandomNumberGenerator.Create();
            rng.GetBytes(bytes);

            var code = new char[6];
            for (int i = 0; i < 6; i++)
            {
                code[i] = validChars[bytes[i % 3] % validChars.Length];
            }

            return new string(code);
        }
    }
}