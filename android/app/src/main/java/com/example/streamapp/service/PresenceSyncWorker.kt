package com.example.streamapp.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.streamapp.AuthRepository
import com.example.streamapp.data.SessionPreferences
import com.example.streamapp.network.SignalRClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Worker que, en cuanto hay Internet, asegura la presencia del dispositivo
 * supervisado (SENDER) en el backend y deja el coordinador en standby.
 *
 * Se encola desde el arranque del teléfono, la actualización del APK o FCM.
 */
class PresenceSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        val user = try {
            FirebaseAuth.getInstance().currentUser
        } catch (t: Throwable) {
            Log.w(TAG, "FirebaseAuth no disponible en el worker: ${t.message}")
            null
        }
        val role = try {
            SessionPreferences(context).getSavedRole()
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo leer el rol persistido: ${t.message}")
            null
        }

        if (user == null || role != "SENDER") {
            Log.d(TAG, "Sin sesión SENDER; no hay presencia que sincronizar")
            return Result.success()
        }

        return try {
            // Fuerza un ID token fresco antes del registro REST.
            user.getIdToken(true).await()

            val fcmToken = try {
                FirebaseMessaging.getInstance().token.await()
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo obtener el token FCM: ${t.message}")
                ""
            }

            val hardwareId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "device_${Build.MODEL}"
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

            val result = AuthRepository(context).registerDevice(
                deviceHardwareId = hardwareId,
                deviceName = deviceName,
                fcmToken = fcmToken,
                role = "SENDER"
            )

            if (!result.success) {
                Log.w(TAG, "Registro falló (HTTP ${result.httpCode}): ${result.message}")
                // httpCode nulo => fallo de red/conexión: reintentar con backoff.
                return if (result.httpCode == null) Result.retry() else Result.success()
            }

            Log.i(TAG, "Presencia sincronizada para $hardwareId (IsOnline=true)")
            startStandby(context)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Sin red/token al sincronizar presencia: ${t.message}")
            Result.retry()
        }
    }

    /** Inicia el coordinador en modo standby (SignalR + presencia, sin WebRTC). */
    private fun startStandby(context: Context) {
        val intent = Intent(context, StreamingForegroundService::class.java).apply {
            action = StreamingForegroundService.ACTION_STANDBY
        }
        try {
            ContextCompat.startForegroundService(context, intent)
            // Si el FGS arranca, liberar cualquier StandbySignalR previo para evitar duplicados.
            StreamSessionCoordinatorProvider.release()
        } catch (t: Throwable) {
            Log.w(TAG, "FGS no permitido en background (${t.message}); activando coordinador en standby directo")
            // Fallback: usar el coordinador compartido sin ForegroundService.
            val coordinator = StreamSessionCoordinatorProvider.get(context)
            // El coordinador ya mantiene SignalR y presencia internamente al crearse.
            // No hace falta llamar a startSession (sin sessionCode).
            Log.i(TAG, "Coordinador activado en modo standby (sin FGS)")
        }
    }

    companion object {
        private const val TAG = "PresenceSyncWorker"
    }
}

/**
 * Programa la sincronización de presencia: un intento único apenas haya red
 * (con backoff exponencial) y un chequeo periódico cada 15 minutos.
 */
object PresenceScheduler {

    private const val TAG = "PresenceScheduler"
    private const val UNIQUE_ONE_TIME = "viuwapp_presence_sync_once"
    private const val UNIQUE_PERIODIC = "viuwapp_presence_sync_periodic"

    fun scheduleWhenOnline(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context.applicationContext)
            val networkConstraint = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTime = OneTimeWorkRequestBuilder<PresenceSyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            workManager.enqueueUniqueWork(
                UNIQUE_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                oneTime
            )

            val periodic = PeriodicWorkRequestBuilder<PresenceSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )

            Log.d(TAG, "Presencia programada al recuperar Internet (once + cada 15 min)")
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo programar PresenceSyncWorker: ${t.message}")
        }
    }
}