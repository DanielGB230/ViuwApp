package com.example.streamapp.service

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.streamapp.MainApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * ViuwFirebaseMessagingService: Maneja tokens FCM y eventos de despertar remoto.
 */
class ViuwFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "ViuwFCMService"
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Token FCM renovado: ${token.take(15)}...")

        val app = application as? MainApplication ?: return
        serviceScope.launch {
            try {
                val role = app.authRepository.cachedRole.firstOrNull()
                if (role == "SENDER" && app.authRepository.currentUser != null) {
                    val hardwareId = Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ANDROID_ID
                    ) ?: "device_${Build.MODEL}"
                    val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
                    app.authRepository.registerDevice(hardwareId, deviceName, token)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo actualizar token en backend", e)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        Log.d(TAG, "Mensaje FCM recibido: $data")

        val streamAction = data["action"] ?: data["type"]

        if (streamAction == "START_STREAM" || streamAction == "WAKE_AND_STREAM") {
            Log.i(TAG, "Acción $streamAction recibida por FCM: $data")
            val sessionCode = data["sessionCode"] ?: data["sessionId"] ?: ""
            if (sessionCode.isNotBlank()) {
                val intent = Intent(this, StreamingForegroundService::class.java).apply {
                    action = StreamingForegroundService.ACTION_START_STREAM
                    putExtra(StreamingForegroundService.EXTRA_SESSION_CODE, sessionCode)
                }
                try {
                    ContextCompat.startForegroundService(this, intent)
                    // Si el FGS arranca, liberar cualquier coordinador standby previo.
                    StreamSessionCoordinatorProvider.release()
                } catch (e: Exception) {
                    Log.w(TAG, "ForegroundService no permitido en background (${e.message}); delegando en coordinador compartido para $sessionCode")
                    handleStreamInCoordinator(sessionCode)
                }
            } else {
                Log.w(TAG, "FCM START_STREAM sin sessionCode/sessionId: $data")
            }
        }
    }

    /**
     * Fallback cuando el sistema impide iniciar el ForegroundService (Android 12+ background).
     * Usa el StreamSessionCoordinator compartido para mantener SignalR + WebRTC listos.
     */
    private fun handleStreamInCoordinator(sessionCode: String) {
        serviceScope.launch {
            try {
                val coordinator = StreamSessionCoordinatorProvider.get(applicationContext)
                coordinator.startSession(sessionCode)
                Log.i(TAG, "Coordinador activado para sesión $sessionCode (sin FGS)")
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo activar coordinador desde FCM: ${t.message}")
            }
        }
    }
}