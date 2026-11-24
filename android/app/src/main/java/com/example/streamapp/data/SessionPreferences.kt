package com.example.streamapp.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistencia local de la sesión y del rol elegido (Fase 12).
 *
 * Usa SharedPreferences para que el arranque de la app y el BootReceiver puedan
 * leer el rol de forma síncrona, sin esperar a corrutinas/DataStore.
 *
 * Solo debe borrarse cuando el usuario pulse explícitamente "Salir".
 */
class SessionPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val savedUserUid: String?
        get() = prefs.getString(KEY_USER_UID, null)

    val savedUserEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)

    val savedUserName: String?
        get() = prefs.getString(KEY_USER_NAME, null)

    fun getSavedRole(): String? = prefs.getString(KEY_ROLE, null)

    /** Guarda el rol elegido ("SENDER" o "RECEIVER"). */
    fun saveRole(role: String) {
        prefs.edit().putString(KEY_ROLE, role).commit()
    }

    /**
     * Borra únicamente el rol guardado, conservando la sesión del usuario.
     * Se usa al pulsar "Salir" para volver a la selección de rol sin cerrar
     * la cuenta de Google/Firebase.
     */
    fun clearRole() {
        prefs.edit().remove(KEY_ROLE).commit()
    }

    /** Guarda los datos básicos del usuario autenticado. */
    fun saveUser(uid: String, email: String?, name: String?) {
        prefs.edit()
            .putString(KEY_USER_UID, uid)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_NAME, name)
            .commit()
    }

    /** Borra toda la sesión persistida (solo al pulsar "Salir"). */
    fun clearAll() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS_NAME = "viuwapp_session_prefs"
        private const val KEY_ROLE = "saved_role"
        private const val KEY_USER_UID = "saved_user_uid"
        private const val KEY_USER_EMAIL = "saved_user_email"
        private const val KEY_USER_NAME = "saved_user_name"
    }
}
