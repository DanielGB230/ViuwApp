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

        if (streamAction == "START_STREAM") {
            Log.i(TAG, "Acción START_STREAM recibida por FCM: $data")
            val sessionId = data["sessionId"].orEmpty()
            val intent = Intent(this, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_START_STREAM
                putExtra(StreamingForegroundService.EXTRA_SESSION_ID, sessionId)
            }
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando servicio en primer plano desde FCM", e)
            }
        }
    }
}
