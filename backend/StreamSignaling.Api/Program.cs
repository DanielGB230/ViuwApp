using Microsoft.EntityFrameworkCore;
using StreamSignaling.Api.Auth;
using StreamSignaling.Api.Data;
using StreamSignaling.Api.Hubs;
using StreamSignaling.Api.Services;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddControllers();
builder.Services.AddSignalR(options =>
{
    options.MaximumReceiveMessageSize = 64 * 1024; // 64 KB limit to prevent sending large audio/video frames through signaling channel
});
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAndroid", builder =>
    {
        builder.WithOrigins("http://10.0.2.2:5000", "http://10.0.0.2:5000",
                           "http://localhost:5000", "http://192.168.1.*:5000")
               .AllowAnyMethod()
               .AllowAnyHeader()
               .AllowCredentials();
    });
});

// SQL Server + Entity Framework Core.
var connectionString = builder.Configuration.GetConnectionString("DefaultConnection")
    ?? "Server=.\\SQLEXPRESS;Database=ViuwAppDb;Trusted_Connection=True;TrustServerCertificate=True;MultipleActiveResultSets=True;";

builder.Services.AddDbContext<AppDbContext>(options =>
    options.UseSqlServer(connectionString, sqlOptions =>
    {
        sqlOptions.EnableRetryOnFailure(maxRetryCount: 3);
    }));

builder.Services.AddSingleton<FcmNotificationService>();

var app = builder.Build();

// Firebase Admin: valida id tokens de Google/Firebase y envía mensajes FCM.
// Si falta la credencial, la app arranca igual y /api responde 503.
FirebaseAdminState.Initialize(app.Configuration, app.Environment, app.Logger);

// Configure the HTTP request pipeline.
app.UseCors("AllowAndroid");
app.UseHttpsRedirection();

// Autenticación Bearer de Firebase solo para /api/* (el Hub conserva su contrato actual).
app.UseFirebaseBearerAuthentication();

app.UseAuthorization();
app.MapControllers();
app.MapHub<SignalingHub>("/signaling");

await ApplyMigrationsAsync(app);

app.Run();

static async Task ApplyMigrationsAsync(WebApplication app)
{
    using var scope = app.Services.CreateScope();
    var logger = scope.ServiceProvider.GetRequiredService<ILogger<Program>>();

    try
    {
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        await db.Database.MigrateAsync();
        logger.LogInformation("Base de datos de control parental migrada correctamente.");
    }
    catch (Exception ex)
    {
        // No impedir el arranque si SQL Server no está disponible; se reintentará
        // en el siguiente arranque y el motor WebRTC/SignalR sigue operativo.
        logger.LogError(
            ex,
            "No se pudieron aplicar las migraciones de SQL Server. " +
            "La señalización WebRTC continuará funcionando sin persistencia.");
    }
}
