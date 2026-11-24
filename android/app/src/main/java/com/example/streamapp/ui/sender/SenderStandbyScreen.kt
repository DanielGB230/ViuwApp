package com.example.streamapp.ui.sender

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val signalR = remember { SignalRClient() }

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
                title = { Text("Dispositivo Supervisado") },
                actions = {
                    OutlinedButton(onClick = onSignOutClicked) {
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
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isRegistering) {
                CircularProgressIndicator(modifier = Modifier.size(56.dp))
            } else if (isRegistered) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Activo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Modo Emisor Activo",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = statusMessage,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            Text(
                text = "El stream comenzará automáticamente cuando el tutor inicie la transmisión.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (!isRegistering && !isRegistered) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    isRegistered = false
                    isRegistering = true
                    statusMessage = "Reintentando registro..."
                    retryTrigger++
                }) {
                    Text("Reintentar registro")
                }
            }
        }
    }
}
