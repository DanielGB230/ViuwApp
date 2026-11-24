package com.example.streamapp

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.streamapp.receiver.ReceiverScreen
import com.example.streamapp.sender.SenderScreen
import com.example.streamapp.ui.auth.LoginScreen
import com.example.streamapp.ui.devices.ReceiverDevicesScreen
import com.example.streamapp.ui.roles.RoleSetupScreen
import com.example.streamapp.ui.sender.SenderStandbyScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        val app = application as MainApplication
        val viewModel = app.appSessionViewModel

        setContent {
            MaterialTheme {
                val uiState by viewModel.uiState.collectAsState()
                val isLoggingIn by viewModel.isLoggingIn.collectAsState()
                val loginError by viewModel.loginError.collectAsState()

                when (val state = uiState) {
                    is AppUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    is AppUiState.Login -> {
                        LoginScreen(
                            isLoggingIn = isLoggingIn,
                            errorMessage = loginError,
                            onGoogleSignInClicked = { context ->
                                viewModel.loginWithGoogle(context)
                            }
                        )
                    }

                    is AppUiState.RoleSetup -> {
                        RoleSetupScreen(
                            onSenderSelected = { viewModel.selectSenderRole() },
                            onReceiverSelected = { viewModel.selectReceiverRole() }
                        )
                    }

                    is AppUiState.SenderStandby -> {
                        SenderStandbyScreen(
                            authRepository = app.authRepository,
                            onSignOutClicked = { viewModel.signOut() }
                        )
                    }

                    is AppUiState.ReceiverDevices -> {
                        val devices by viewModel.devicesList.collectAsState()
                        val isLoadingDevices by viewModel.isLoadingDevices.collectAsState()
                        val devicesError by viewModel.devicesError.collectAsState()

                        ReceiverDevicesScreen(
                            devices = devices,
                            isLoading = isLoadingDevices,
                            errorMessage = devicesError,
                            onRefresh = { viewModel.refreshDevices() },
                            onDeviceSelected = { deviceId ->
                                viewModel.requestStreamToDevice(deviceId) { _ -> }
                            },
                            onManualCodeConnect = { code ->
                                viewModel.joinSessionManually(code)
                            },
                            onSignOutClicked = { viewModel.signOut() }
                        )
                    }

                    is AppUiState.ActiveSender -> {
                        SenderScreen(
                            onBackPressed = { viewModel.stopStreaming() }
                        )
                    }

                    is AppUiState.ActiveReceiver -> {
                        // Red de seguridad: atrás/desconectar siempre vuelven a
                        // ReceiverDevices, nunca a Login.
                        BackHandler { viewModel.returnToReceiverHome() }
                        ReceiverScreen(
                            initialSessionCode = state.sessionCode,
                            onBackPressed = { viewModel.returnToReceiverHome() },
                            onDisconnected = { viewModel.returnToReceiverHome() }
                        )
                    }
                }
            }
        }
    }
}
