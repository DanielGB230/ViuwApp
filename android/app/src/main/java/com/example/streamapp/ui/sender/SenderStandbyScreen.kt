package com.example.streamapp.ui.sender

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.streamapp.AuthRepository
import com.example.streamapp.network.SignalRClient
import com.example.streamapp.permissions.PermissionHandler
import com.example.streamapp.service.StreamingForegroundService
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ViuwSender"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderStandbyScreen(
    authRepository: AuthRepository,
    onSignOutClicked: () -> Unit
) {
    val context = LocalContext.current
    var isRegistered by remember { mutableStateOf(false) }
    var isRegistering by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("Verificando permisos y registro...") }
    var retryTrigger by remember { mutableStateOf(0) }
    var streamHandlerRegistered by remember { mutableStateOf(false) }
    var batteryOptimizationIgnored by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }
    val signalR = remember { SignalRClient() }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptimizationIgnored = isBatteryOptimizationIgnored(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (PermissionHandler.isGranted(result)) {
            statusMessage = "Permisos concedidos. Registrando en la nube..."
            Log.d(TAG, "Permisos de cámara y micrófono concedidos")
        } else {
            statusMessage = "Se requieren permisos de cámara y micrófono para funcionar en segundo plano."
            isRegistering = false
            Log.w(TAG, "Permisos de captura denegados: $result")
        }
    }

    LaunchedEffect(retryTrigger) {
        if (!PermissionHandler.hasCapturePermissions(context)) {
            permissionLauncher.launch(PermissionHandler.requiredPermissions())
        }

        val hardwareId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "device_${Build.MODEL}"
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        Log.d(TAG, "Iniciando registro -> hardwareId=$hardwareId, deviceName=$deviceName")

        // El token FCM puede tardar o fallar en algunos dispositivos (sin Google
        // Play Services, red lenta...). Nunca bloqueamos el registro más de 4 s:
        // si no llega, registramos con fcmToken vacío.
        isRegistering = true
        statusMessage = "Obteniendo token de notificaciones..."
        val fcmToken = withTimeoutOrNull(4000L) {
            try {
                FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo obtener token FCM: ${e.localizedMessage}", e)
                null
            }
        } ?: ""
        Log.d(
            TAG,
            "Token FCM: ${if (fcmToken.isBlank()) "<vacío, se registra igual>" else fcmToken.take(16) + "..."}"
        )

        statusMessage = "Registrando en la nube..."

        var registered = false
        var lastError: String? = null
        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts && !registered) {
            attempt++
            val result = authRepository.registerDevice(
                deviceHardwareId = hardwareId,
                deviceName = deviceName,
                fcmToken = fcmToken,
                role = "SENDER"
            )

            if (result.success) {
                registered = true
                Log.d(TAG, "Registro exitoso en intento $attempt (HTTP ${result.httpCode})")
            } else {
                lastError = "HTTP ${result.httpCode ?: "sin conexión"}"
                Log.e(TAG, "Registro falló en intento $attempt: $lastError -> ${result.message}")
                statusMessage =
                    "No se pudo registrar ($lastError). Reintentando ($attempt/$maxAttempts)..."
                if (attempt < maxAttempts) delay(2000L * attempt)
            }
        }

        isRegistering = false

        if (!registered) {
            statusMessage = "No se pudo registrar en la nube. $lastError. Revisa la conexión y vuelve a abrir la app."
            return@LaunchedEffect
        }

        isRegistered = true
        statusMessage = "En línea y listo"

        // Conectar SignalR al Hub /signaling y registrar presencia (IsOnline = true),
        // para que el Receptor pueda despertar este dispositivo por REST.
        try {
            signalR.connect()
            Log.d(TAG, "SignalR conectado a /signaling")

            // Registrar el handler ANTES de marcar presencia para no perder el evento.
            // Se registra una sola vez aunque el usuario reintente el registro.
            if (!streamHandlerRegistered) {
                // Escucha "StartStream" y "OnStartStreamRequested" (string o JSON).
                signalR.onStartStream { sessionId ->
                    Log.d(TAG, "StartStream recibido: sessionId=$sessionId")
                    val intent = Intent(context, StreamingForegroundService::class.java).apply {
                        action = StreamingForegroundService.ACTION_START_STREAM
                        putExtra("sessionId", sessionId)
                    }
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error iniciando StreamingForegroundService: ${e.localizedMessage}", e)
                    }
                }
                streamHandlerRegistered = true
            }

            signalR.registerDevicePresence(hardwareId)
            Log.d(TAG, "Presencia registrada para $hardwareId")
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando SignalR: ${e.localizedMessage}", e)
            statusMessage = "Registrado, pero sin conexión en tiempo real. Reintentando al reabrir."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ViuwApp") },
                actions = {
                    TextButton(onClick = onSignOutClicked) {
                        Text("Salir")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusIndicator(
                isReady = isRegistered,
                isWorking = isRegistering,
                statusMessage = statusMessage
            )

            Spacer(Modifier.height(28.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Dispositivo Supervisado",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "La transmisión se activará automáticamente cuando el tutor la solicite. Este equipo permanece en espera sin usar cámara ni micrófono.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (!isRegistering && !isRegistered) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        isRegistered = false
                        isRegistering = true
                        statusMessage = "Reintentando registro..."
                        retryTrigger++
                    }
                ) {
                    Text("Reintentar registro")
                }
            }

            if (!batteryOptimizationIgnored) {
                Spacer(Modifier.height(20.dp))
                BatteryOptimizationChip(
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        try {
                            batteryLauncher.launch(intent)
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo abrir optimización de batería: ${e.message}")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    isReady: Boolean,
    isWorking: Boolean,
    statusMessage: String
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = when {
                isReady -> MaterialTheme.colorScheme.primaryContainer
                isWorking -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.errorContainer
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (isReady) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(if (isWorking) pulse else 1f)
                    .background(
                        color = if (isReady) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        shape = CircleShape
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    isReady -> "En línea y listo"
                    isWorking -> "Sincronizando"
                    else -> "Sin conexión"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BatteryOptimizationChip(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50)
    ) {
        Icon(
            imageVector = Icons.Default.BatteryAlert,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Desactivar optimización de batería",
            fontSize = 12.sp
        )
    }
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (t: Throwable) {
        Log.w(TAG, "No se pudo consultar optimización de batería: ${t.message}")
        true
    }
}
