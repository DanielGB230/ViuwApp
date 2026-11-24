using System.Security.Cryptography;

namespace StreamSignaling.Api.Services
{
    /// <summary>
    /// Genera códigos de sala de 6 caracteres compatibles con SignalingHub
    /// (sin 0, O, 1, I, L para evitar ambigüedad).
    /// </summary>
    public static class SessionCodeGenerator
    {
        private const string ValidChars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

        public static string Generate()
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
