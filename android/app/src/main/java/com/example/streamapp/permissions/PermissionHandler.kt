package com.example.streamapp.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Permisos necesarios para transmitir.
 * CAMERA y RECORD_AUDIO son imprescindibles; POST_NOTIFICATIONS (Android 13+)
 * solo afecta a la visibilidad de la notificación del servicio en primer plano.
 */
object PermissionHandler {

    fun capturePermissions(): Array<String> = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    fun requiredPermissions(): Array<String> {
        val permissions = capturePermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    fun hasCapturePermissions(context: Context): Boolean =
        capturePermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    fun isGranted(result: Map<String, Boolean>): Boolean =
        result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.RECORD_AUDIO] == true
}
