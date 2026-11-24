using System;
using System.Threading.Tasks;
using FirebaseAdmin.Auth;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace StreamSignaling.Api.Auth
{
    /// <summary>
    /// Middleware que valida "Authorization: Bearer &lt;FirebaseIdToken&gt;" en /api/*
    /// y publica FirebaseUid/Email/Name en HttpContext.Items.
    /// </summary>
    public static class FirebaseAuthenticationMiddleware
    {
        public const string FirebaseUidKey = "FirebaseUid";
        public const string FirebaseEmailKey = "FirebaseEmail";
        public const string FirebaseNameKey = "FirebaseName";

        public static IApplicationBuilder UseFirebaseBearerAuthentication(this IApplicationBuilder app)
        {
            return app.Use(async (context, next) =>
            {
                if (!context.Request.Path.StartsWithSegments("/api"))
                {
                    await next();
                    return;
                }

                if (HttpMethods.IsOptions(context.Request.Method))
                {
                    await next();
                    return;
                }

                if (!FirebaseAdminState.IsInitialized)
                {
                    await WriteErrorAsync(
                        context,
                        StatusCodes.Status503ServiceUnavailable,
                        "firebase_not_configured",
                        "Firebase Admin no está configurado en el servidor.");
                    return;
                }

                var header = context.Request.Headers.Authorization.ToString();
                if (string.IsNullOrWhiteSpace(header) ||
                    !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
                {
                    await WriteErrorAsync(
                        context,
                        StatusCodes.Status401Unauthorized,
                        "missing_token",
                        "Falta el encabezado Authorization: Bearer <FirebaseIdToken>.");
                    return;
                }

                try
                {
                    var idToken = header["Bearer ".Length..].Trim();
                    var decoded = await FirebaseAuth.DefaultInstance.VerifyIdTokenAsync(
                        idToken,
                        context.RequestAborted);

                    context.Items[FirebaseUidKey] = decoded.Uid;

                    if (decoded.Claims.TryGetValue("email", out var email) && email != null)
                    {
                        context.Items[FirebaseEmailKey] = email.ToString();
                    }

                    if (decoded.Claims.TryGetValue("name", out var name) && name != null)
                    {
                        context.Items[FirebaseNameKey] = name.ToString();
                    }

                    await next();
                }
                catch (FirebaseAuthException ex)
                {
                    await WriteErrorAsync(
                        context,
                        StatusCodes.Status401Unauthorized,
                        "invalid_token",
                        $"Token de Firebase inválido: {ex.Message}");
                }
                catch (OperationCanceledException)
                {
                    // El cliente cerró la conexión.
                }
                catch (Exception ex)
                {
                    await WriteErrorAsync(
                        context,
                        StatusCodes.Status500InternalServerError,
                        "auth_error",
                        $"Error validando el token: {ex.Message}");
                }
            });
        }

        private static async Task WriteErrorAsync(
            HttpContext context,
            int statusCode,
            string code,
            string message)
        {
            if (context.Response.HasStarted)
            {
                return;
            }

            context.Response.StatusCode = statusCode;
            context.Response.ContentType = "application/json";
            await context.Response.WriteAsJsonAsync(new { error = code, message });
        }
    }

    public static class HttpContextAuthExtensions
    {
        public static string GetFirebaseUid(this HttpContext context)
        {
            if (context.Items.TryGetValue(FirebaseAuthenticationMiddleware.FirebaseUidKey, out var value) &&
                value is string uid &&
                !string.IsNullOrWhiteSpace(uid))
            {
                return uid;
            }

            throw new InvalidOperationException("La petición no tiene un usuario de Firebase autenticado.");
        }

        public static string? GetFirebaseEmail(this HttpContext context)
            => context.Items.TryGetValue(FirebaseAuthenticationMiddleware.FirebaseEmailKey, out var value)
                ? value as string
                : null;

        public static string? GetFirebaseName(this HttpContext context)
            => context.Items.TryGetValue(FirebaseAuthenticationMiddleware.FirebaseNameKey, out var value)
                ? value as string
                : null;
    }
}
