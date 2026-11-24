package com.example.streamapp

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.viuwapp.stream.BuildConfig
import com.viuwapp.stream.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "viuw_app_prefs")

/** Elemento de lista para mostrar un dispositivo en ReceiverDevicesScreen. */
data class DeviceListItem(
    val id: String,
    val deviceName: String,
    val isOnline: Boolean,
    val lastSeen: String? = null
)

/**
 * AuthRepository: gestión de sesión (FirebaseAuth + Credential Manager),
 * persistencia de rol (Jetpack DataStore) y llamadas REST con OkHttp.
 */
class AuthRepository(
    private val context: Context,
    val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val TAG = "AuthRepository"
    private val baseUrl = BuildConfig.SIGNALING_BASE_URL.trimEnd('/')
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        val ROLE_KEY = stringPreferencesKey("device_role")
    }

    // --- Persistencia con DataStore Preferences ---
    val cachedRole: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[ROLE_KEY] }
        .distinctUntilChanged()

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    /** Guarda el rol elegido localmente en DataStore (SENDER o RECEIVER). */
    suspend fun saveRoleLocally(role: String) {
        context.dataStore.edit { preferences ->
            preferences[ROLE_KEY] = role
        }
    }

    /** Cierra sesión en Firebase y borra el rol en DataStore. */
    suspend fun signOut() {
        context.dataStore.edit { preferences ->
            preferences.remove(ROLE_KEY)
        }
        firebaseAuth.signOut()
    }

    /**
     * Inicia sesión con Google usando Jetpack Credential Manager + Firebase Auth.
     */
    suspend fun loginWithGoogle(activityContext: Context): Result<FirebaseUser> = withContext(Dispatchers.IO) {
        try {
            val credentialManager = CredentialManager.create(activityContext)
            val serverClientId = context.getString(R.string.default_web_client_id)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)

            val authCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            val authResult = firebaseAuth.signInWithCredential(authCredential).await()
            val user = authResult.user ?: throw IOException("Firebase no retornó usuario")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Error en loginWithGoogle", e)
            Result.failure(e)
        }
    }

    /** Obtiene el Firebase ID Token actual para Authorization Bearer. */
    suspend fun getCurrentIdToken(): String = withContext(Dispatchers.IO) {
        val user = firebaseAuth.currentUser ?: throw IOException("No hay usuario autenticado")
        val tokenResult = user.getIdToken(false).await()
        tokenResult.token ?: throw IOException("Token nulo recibido de Firebase")
    }

    /**
     * Resultado del registro del dispositivo en el backend.
     * Incluye el código HTTP para poder mostrar errores exactos en pantalla.
     */
    data class RegisterDeviceResult(
        val success: Boolean,
        val httpCode: Int? = null,
        val message: String? = null
    )

    /**
     * Registra el dispositivo SENDER en el backend: POST /api/devices/register
     * Se ejecuta en Dispatchers.IO con Authorization: Bearer <idToken>.
     */
    suspend fun registerDevice(
        deviceHardwareId: String,
        deviceName: String,
        fcmToken: String,
        role: String = "SENDER"
    ): RegisterDeviceResult = withContext(Dispatchers.IO) {
        val logTag = "ViuwSender"
        try {
            val token = getCurrentIdToken()
            val json = JSONObject().apply {
                put("deviceHardwareId", deviceHardwareId)
                put("deviceName", deviceName)
                put("fcmToken", fcmToken)
                put("role", role)
            }

            Log.d(
                logTag,
                "POST $baseUrl/api/devices/register -> hardwareId=$deviceHardwareId, " +
                    "deviceName=$deviceName, fcmToken=${if (fcmToken.isBlank()) "<vacío>" else "presente"}, role=$role"
            )

            val request = Request.Builder()
                .addHeader("X-Tunnel-Skip-AntiPhishing-Page", "true")
                .url("$baseUrl/api/devices/register")
                .header("Authorization", "Bearer $token")
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                Log.d(logTag, "POST /api/devices/register <- HTTP ${response.code} body=$body")
                RegisterDeviceResult(
                    success = response.isSuccessful,
                    httpCode = response.code,
                    message = body
                )
            }
        } catch (e: Exception) {
            Log.e(logTag, "Excepción en POST /api/devices/register: ${e.localizedMessage}", e)
            RegisterDeviceResult(success = false, httpCode = null, message = e.localizedMessage)
        }
    }

    /**
     * Consulta la lista de dispositivos vinculados del usuario: GET /api/devices/mine
     */
    suspend fun fetchMyDevices(): List<DeviceListItem> = withContext(Dispatchers.IO) {
        try {
            val token = getCurrentIdToken()
            val request = Request.Builder()
                .addHeader("X-Tunnel-Skip-AntiPhishing-Page", "true")
                .url("$baseUrl/api/devices/mine")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchMyDevices error HTTP: ${response.code}")
                    return@use emptyList()
                }

                val bodyStr = response.body?.string() ?: return@use emptyList()
                Log.d(TAG, "GET /api/devices/mine <- HTTP ${response.code} body=$bodyStr")
                val list = mutableListOf<DeviceListItem>()
                val jsonArray = JSONArray(bodyStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        DeviceListItem(
                            id = obj.optString("id", obj.optString("deviceId", "")),
                            deviceName = obj.optString("deviceName", "Dispositivo"),
                            isOnline = obj.optBoolean("isOnline", false),
                            lastSeen = if (obj.has("lastSeenUtc")) obj.optString("lastSeenUtc")
                            else if (obj.has("lastSeen")) obj.optString("lastSeen") else null
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en fetchMyDevices", e)
            emptyList()
        }
    }

    /**
     * Solicita iniciar stream a un dispositivo: POST /api/devices/{deviceId}/request-stream
     * Retorna el sessionId para conectarse en ReceiverScreen.
     */
    suspend fun requestStreamToDevice(deviceId: String): String? = withContext(Dispatchers.IO) {
        try {
            val token = getCurrentIdToken()
            val request = Request.Builder()
                .addHeader("X-Tunnel-Skip-AntiPhishing-Page", "true")
                .url("$baseUrl/api/devices/$deviceId/request-stream")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "requestStreamToDevice error HTTP: ${response.code}")
                    return@use null
                }
                val bodyStr = response.body?.string() ?: return@use null
                val json = JSONObject(bodyStr)
                json.optString("sessionId").ifEmpty {
                    json.optString("sessionCode").ifEmpty { null }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en requestStreamToDevice", e)
            null
        }
    }
}
