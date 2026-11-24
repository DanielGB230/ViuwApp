package com.example.streamapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.streamapp.data.SessionPreferences
import com.google.firebase.auth.FirebaseAuth

/**
 * BootReceiver: se despierta al iniciar el sistema Android, al desbloquear y
 * cuando el APK se actualiza.
 *
 * Además de un intento inmediato (que puede fallar si aún no hay red), programa
 * PresenceSyncWorker con restricción de red: en cuanto el teléfono tenga Wi-Fi o
 * datos, se registrará la presencia y el servicio quedará en standby.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val receivedAction = intent.action
        when (receivedAction) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("BootReceiver", "Evento de arranque/actualización: $receivedAction")

                val currentUser = try {
                    FirebaseAuth.getInstance().currentUser
                } catch (e: Exception) {
                    Log.e("BootReceiver", "FirebaseAuth no disponible en boot", e)
                    null
                }
                val savedRole = try {
                    SessionPreferences(context).getSavedRole()
                } catch (e: Exception) {
                    Log.w("BootReceiver", "No se pudo leer el rol persistido: ${e.message}")
                    null
                }

                if (currentUser != null && savedRole == "SENDER") {
                    Log.i("BootReceiver", "Sesión SENDER persistida; intentando presencia inmediata")
                    val startIntent = Intent(context, StreamingForegroundService::class.java).apply {
                        action = StreamingForegroundService.ACTION_STANDBY
                    }
                    try {
                        ContextCompat.startForegroundService(context, startIntent)
                    } catch (e: Exception) {
                        Log.w("BootReceiver", "FGS no permitido al arrancar: ${e.message}")
                    }
                } else {
                    Log.i(
                        "BootReceiver",
                        "Sin sesión SENDER persistida (user=${currentUser != null}, role=$savedRole); se programa igualmente el worker"
                    )
                }

                // Clave: el worker espera a tener Internet antes de registrar presencia.
                PresenceScheduler.scheduleWhenOnline(context)
            }
        }
    }
}
