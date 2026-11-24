package com.example.streamapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver: se despierta al iniciar el sistema Android.
 * Si el rol guardado en DataStore es SENDER, reactiva el servicio.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val receivedAction = intent.action
        when (receivedAction) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT -> {
                Log.i("BootReceiver", "Boot recibido: $receivedAction")
                val startIntent = Intent(context, StreamingForegroundService::class.java).apply {
                    action = StreamingForegroundService.ACTION_START
                }
                try {
                    context.startService(startIntent)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error iniciando servicio en boot", e)
                }
            }
        }
    }
}
