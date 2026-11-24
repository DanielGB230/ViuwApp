package com.example.streamapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.streamapp.data.SessionPreferences
import com.example.streamapp.service.StreamingForegroundService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface AppUiState {
    object Loading : AppUiState
    object Login : AppUiState
    object RoleSetup : AppUiState
    object SenderStandby : AppUiState
    object ReceiverDevices : AppUiState
    data class ActiveSender(val sessionCode: String = "") : AppUiState
    data class ActiveReceiver(val sessionCode: String) : AppUiState
}

class AppSessionViewModel(
    application: Application,
    val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val TAG = "AppSessionVM"

    /** Persistencia local de sesión/rol (sobrevive a reinicios de la app y del teléfono). */
    private val sessionPreferences = SessionPreferences(application.applicationContext)

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _devicesList = MutableStateFlow<List<DeviceListItem>>(emptyList())
    val devicesList: StateFlow<List<DeviceListItem>> = _devicesList.asStateFlow()

    private val _isLoadingDevices = MutableStateFlow(false)
    val isLoadingDevices: StateFlow<Boolean> = _isLoadingDevices.asStateFlow()

    private val _devicesError = MutableStateFlow<String?>(null)
    val devicesError: StateFlow<String?> = _devicesError.asStateFlow()

    // Control de pantallas de streaming activas
    private val _activeStreamMode = MutableStateFlow<AppUiState?>(null)

    // Estado inicial: splash mientras se evalúa la sesión persistida.
    private val _uiState = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        resolveInitialDestination()

        authRepository.firebaseAuth.addAuthStateListener { auth ->
            val user = auth.currentUser
            if (user == null) {
                if (_activeStreamMode.value == null) {
                    _uiState.value = AppUiState.Login
                }
                return@addAuthStateListener
            }

            persistUser(user)

            // Solo re-evaluamos si el usuario acaba de autenticarse; nunca
            // interrumpimos una pantalla de streaming activa.
            if (_activeStreamMode.value == null && shouldResolveRole()) {
                _uiState.value = destinationForRole(sessionPreferences.getSavedRole())
            }
        }
    }

    /** Evalúa sesión + rol guardados y decide la pantalla de arranque. */
    private fun resolveInitialDestination() {
        val currentUser = authRepository.firebaseAuth.currentUser
        if (currentUser == null) {
            _uiState.value = AppUiState.Login
            return
        }

        persistUser(currentUser)

        viewModelScope.launch {
            // Migración desde versiones previas que solo guardaban el rol en DataStore.
            if (sessionPreferences.getSavedRole() == null) {
                val legacyRole = try {
                    authRepository.cachedRole.first()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo leer el rol antiguo de DataStore", e)
                    null
                }
                if (!legacyRole.isNullOrBlank()) {
                    sessionPreferences.saveRole(legacyRole)
                }
            }

            _uiState.value = destinationForRole(sessionPreferences.getSavedRole())
            Log.d(TAG, "Arranque resuelto -> ${_uiState.value} (rol=${sessionPreferences.getSavedRole()})")
        }
    }

    private fun shouldResolveRole(): Boolean =
        _uiState.value is AppUiState.Login ||
            _uiState.value is AppUiState.RoleSetup

    private fun destinationForRole(role: String?): AppUiState = when (role) {
        "SENDER" -> AppUiState.SenderStandby
        "RECEIVER" -> {
            refreshDevices()
            AppUiState.ReceiverDevices
        }
        else -> AppUiState.RoleSetup
    }

    private fun persistUser(user: FirebaseUser) {
        sessionPreferences.saveUser(user.uid, user.email, user.displayName)
    }

    fun loginWithGoogle(activityContext: Context) {
        if (_isLoggingIn.value) return
        _isLoggingIn.value = true
        _loginError.value = null
        viewModelScope.launch {
            val result = authRepository.loginWithGoogle(activityContext)
            _isLoggingIn.value = false
            result.onSuccess { user ->
                persistUser(user)
            }.onFailure { error ->
                _loginError.value = when {
                    error is GetCredentialCancellationException -> null
                    error is NoCredentialException || error.message?.contains("No available credentials", ignoreCase = true) == true ->
                        "No hay una cuenta de Google activa en este dispositivo. Abre Ajustes > Contraseñas y cuentas en el emulador y completa la sesión."
                    error.message?.contains("No credentials available", ignoreCase = true) == true ->
                        "No hay una cuenta de Google activa en este dispositivo. Abre Ajustes > Contraseñas y cuentas en el emulador y completa la sesión."
                    else -> error.localizedMessage ?: "Error al iniciar sesión con Google"
                }
            }
        }
    }

    fun selectSenderRole() {
        sessionPreferences.saveRole("SENDER")
        viewModelScope.launch { authRepository.saveRoleLocally("SENDER") }
        _activeStreamMode.value = null
        _uiState.value = AppUiState.SenderStandby
        Log.d(TAG, "Rol persistido: SENDER")
    }

    fun selectReceiverRole() {
        sessionPreferences.saveRole("RECEIVER")
        viewModelScope.launch { authRepository.saveRoleLocally("RECEIVER") }
        _activeStreamMode.value = null
        _uiState.value = AppUiState.ReceiverDevices
        refreshDevices()
        Log.d(TAG, "Rol persistido: RECEIVER")
    }

    fun refreshDevices() {
        _isLoadingDevices.value = true
        _devicesError.value = null
        viewModelScope.launch {
            val devices = authRepository.fetchMyDevices()
            _devicesList.value = devices
            _isLoadingDevices.value = false
        }
    }

    fun requestStreamToDevice(deviceId: String, onSessionReady: (String) -> Unit) {
        viewModelScope.launch {
            _isLoadingDevices.value = true
            val sessionId = authRepository.requestStreamToDevice(deviceId)
            _isLoadingDevices.value = false
            if (!sessionId.isNullOrBlank()) {
                _activeStreamMode.value = AppUiState.ActiveReceiver(sessionId)
                _uiState.value = AppUiState.ActiveReceiver(sessionId)
                onSessionReady(sessionId)
            } else {
                _devicesError.value = "No se pudo iniciar la transmisión con el dispositivo."
            }
        }
    }

    fun startManualTransmission() {
        _activeStreamMode.value = AppUiState.ActiveSender()
        _uiState.value = AppUiState.ActiveSender()
    }

    /** Conexión manual del Receptor mediante un código de sesión de 6 caracteres. */
    fun joinSessionManually(code: String) {
        val normalized = code.trim().uppercase()
        if (normalized.length != 6) {
            _devicesError.value = "El código de sesión debe tener 6 caracteres."
            return
        }
        _devicesError.value = null
        _activeStreamMode.value = AppUiState.ActiveReceiver(normalized)
        _uiState.value = AppUiState.ActiveReceiver(normalized)
    }

    fun stopStreaming() {
        _activeStreamMode.value = null
        _uiState.value = destinationForRole(sessionPreferences.getSavedRole())
    }

    /** "Salir": detiene el servicio, borra la sesión local y cierra Firebase/Google. */
    fun signOut() {
        val context = getApplication<Application>()

        try {
            context.stopService(Intent(context, StreamingForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo detener StreamingForegroundService: ${e.localizedMessage}", e)
        }

        _activeStreamMode.value = null
        sessionPreferences.clearAll()
        _uiState.value = AppUiState.Login

        try {
            GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN)
                .signOut()
                .addOnCompleteListener {
                    Log.d(TAG, "GoogleSignIn signOut completado")
                }
        } catch (e: Exception) {
            Log.w(TAG, "GoogleSignIn.signOut falló: ${e.localizedMessage}", e)
        }

        viewModelScope.launch {
            authRepository.signOut()
        }
    }
}
