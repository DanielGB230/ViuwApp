package com.example.streamapp

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.streamapp.receiver.ReceiverScreen
import com.example.streamapp.ui.auth.LoginScreen
import com.example.streamapp.ui.devices.ReceiverDevicesScreen
import com.example.streamapp.ui.roles.RoleSetupScreen
import com.example.streamapp.ui.sender.SenderStandbyScreen
import com.example.streamapp.ui.theme.ViuwAppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        val app = application as MainApplication
        val viewModel = app.appSessionViewModel

        setContent {
            ViuwAppTheme {
                val uiState by viewModel.uiState.collectAsState()
                val isLoggingIn by viewModel.isLoggingIn.collectAsState()
                val loginError by viewModel.loginError.collectAsState()

                when (val state = uiState) {
                    is AppUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    modifier = Modifier.size(88.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(44.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    text = "ViuwApp",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Control parental seguro",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(28.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp
                                )
                            }
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
                            onReceiverSelected = { viewModel.selectReceiverRole() },
                            onSignOutClicked = { viewModel.signOut() }
                        )
                    }

                    is AppUiState.SenderStandby -> {
                        SenderStandbyScreen(
                            authRepository = app.authRepository,
                            // "Salir" -> selección de rol (no cierra la cuenta).
                            onSignOutClicked = { viewModel.exitToRoleSelection() }
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
                            // "Salir" -> selección de rol (no cierra la cuenta).
                            onSignOutClicked = { viewModel.exitToRoleSelection() }
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
