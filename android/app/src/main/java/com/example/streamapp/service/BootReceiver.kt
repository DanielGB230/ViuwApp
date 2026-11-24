package com.example.streamapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.streamapp.data.SessionPreferences
import com.google.firebase.auth.FirebaseAuth

/**
 * BootReceiver: se despierta al iniciar el sistema Android.
 *
 * Si el usuario sigue autenticado y el rol persistido es SENDER, reactiva el
 * servicio en modo standby/presencia para que el dispositivo supervisado vuelva
 * a quedar "En línea y listo" sin intervención manual.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val receivedAction = intent.action
        when (receivedAction) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT -> {
                Log.i("BootReceiver", "Boot recibido: $receivedAction")

                val currentUser = try {
                    FirebaseAuth.getInstance().currentUser
                } catch (e: Exception) {
                    Log.e("BootReceiver", "FirebaseAuth no disponible en boot", e)
                    null
                }
                val savedRole = SessionPreferences(context).getSavedRole()

                if (currentUser != null && savedRole == "SENDER") {
                    Log.i("BootReceiver", "Sesión SENDER persistida; reactivando presencia")
                    val startIntent = Intent(context, StreamingForegroundService::class.java).apply {
                        action = StreamingForegroundService.ACTION_STANDBY
                    }
                    try {
                        ContextCompat.startForegroundService(context, startIntent)
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Error iniciando servicio en boot", e)
                    }
                } else {
                    Log.i(
                        "BootReceiver",
                        "Sin sesión SENDER persistida (user=${currentUser != null}, role=$savedRole); no se reactiva el servicio"
                    )
                }
            }
        }
    }
}
