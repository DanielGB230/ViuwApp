using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using FirebaseAdmin;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace StreamSignaling.Api.Auth
{
    /// <summary>
    /// Inicializa Firebase Admin SDK con la cuenta de servicio del proyecto.
    /// Si el archivo de credenciales no existe, la aplicación sigue arrancando
    /// (útil para compilar/migrar sin credenciales) pero los endpoints de /api
    /// responderán 503 hasta que se configure.
    /// </summary>
    public static class FirebaseAdminState
    {
        public static bool IsInitialized { get; private set; }

        public static string? ProjectId { get; private set; }

        public static void Initialize(
            IConfiguration configuration,
            IHostEnvironment environment,
            ILogger logger)
        {
            if (IsInitialized)
            {
                return;
            }

            var candidates = new List<string>();

            var configuredPath = configuration["Firebase:CredentialPath"];
            if (!string.IsNullOrWhiteSpace(configuredPath))
            {
                candidates.Add(Path.IsPathRooted(configuredPath)
                    ? configuredPath
                    : Path.GetFullPath(Path.Combine(environment.ContentRootPath, configuredPath)));
            }

            // Ruta por defecto solicitada: D:\ViuwApp\backend\firebase-adminsdk.json
            candidates.Add(Path.GetFullPath(Path.Combine(environment.ContentRootPath, "..", "firebase-adminsdk.json")));
            candidates.Add(Path.GetFullPath(Path.Combine(environment.ContentRootPath, "firebase-adminsdk.json")));

            var credentialPath = candidates.FirstOrDefault(File.Exists);
            if (credentialPath == null)
            {
                logger.LogWarning(
                    "Firebase Admin no inicializado: no se encontró firebase-adminsdk.json. " +
                    "Rutas probadas: {Paths}. Los endpoints /api devolverán 503.",
                    string.Join(", ", candidates));
                return;
            }

            try
            {
                var credential = GoogleCredential.FromFile(credentialPath);
                var app = FirebaseApp.Create(new AppOptions
                {
                    Credential = credential
                });

                ProjectId = app.Options.ProjectId;
                IsInitialized = true;

                logger.LogInformation(
                    "Firebase Admin inicializado correctamente (proyecto: {ProjectId}, credencial: {CredentialPath}).",
                    ProjectId,
                    credentialPath);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "No se pudo inicializar Firebase Admin desde {CredentialPath}.", credentialPath);
            }
        }
    }
}
