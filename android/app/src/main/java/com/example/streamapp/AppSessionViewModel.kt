package com.example.streamapp

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    // Estado reactivo de usuario de Firebase
    private val _userState = MutableStateFlow<FirebaseUser?>(authRepository.currentUser)

    init {
        authRepository.firebaseAuth.addAuthStateListener { auth ->
            _userState.value = auth.currentUser
        }
    }

    val uiState: StateFlow<AppUiState> = combine(
        _userState,
        authRepository.cachedRole,
        _activeStreamMode
    ) { user, role, activeStream ->
        if (activeStream != null) {
            activeStream
        } else if (user == null) {
            AppUiState.Login
        } else if (role.isNullOrBlank()) {
            AppUiState.RoleSetup
        } else if (role == "SENDER") {
            AppUiState.SenderStandby
        } else {
            AppUiState.ReceiverDevices
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppUiState.Loading
    )

    fun loginWithGoogle(activityContext: Context) {
        if (_isLoggingIn.value) return
        _isLoggingIn.value = true
        _loginError.value = null
        viewModelScope.launch {
            val result = authRepository.loginWithGoogle(activityContext)
            _isLoggingIn.value = false
            result.onFailure { error ->
                _loginError.value = when {
                    error is GetCredentialCancellationException -> null
                    error is NoCredentialException || error.message?.contains("No credentials available", ignoreCase = true) == true ->
                        "No hay una cuenta de Google activa en este dispositivo. Abre Ajustes > Contraseñas y cuentas en el emulador y completa la sesión."
                    else -> error.localizedMessage ?: "Error al iniciar sesión con Google"
                }
            }
        }
    }

    fun selectSenderRole() {
        viewModelScope.launch {
            authRepository.saveRoleLocally("SENDER")
        }
    }

    fun selectReceiverRole() {
        viewModelScope.launch {
            authRepository.saveRoleLocally("RECEIVER")
            refreshDevices()
        }
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
                onSessionReady(sessionId)
            } else {
                _devicesError.value = "No se pudo iniciar la transmisión con el dispositivo."
            }
        }
    }

    fun startManualTransmission() {
        _activeStreamMode.value = AppUiState.ActiveSender()
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
    }

    fun stopStreaming() {
        _activeStreamMode.value = null
    }

    fun signOut() {
        viewModelScope.launch {
            _activeStreamMode.value = null
            authRepository.signOut()
        }
    }
}
