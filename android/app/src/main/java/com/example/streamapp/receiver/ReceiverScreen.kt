package com.example.streamapp.receiver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(
    initialSessionCode: String = "",
    onBackPressed: () -> Unit,
    onDisconnected: () -> Unit = onBackPressed,
    viewModel: ReceiverViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsState()
    var code by remember { mutableStateOf(initialSessionCode) }

    LaunchedEffect(initialSessionCode) {
        if (initialSessionCode.isNotBlank() && !state.isConnected && !state.isConnecting) {
            viewModel.joinSession(initialSessionCode)
        }
    }

    val rendererHolder = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // Sincronización reactiva del VideoTrack remoto y el SurfaceViewRenderer
    LaunchedEffect(remoteVideoTrack, rendererHolder.value) {
        val track = remoteVideoTrack
        val renderer = rendererHolder.value
        if (track != null && renderer != null) {
            try {
                track.removeSink(renderer)
            } catch (_: Throwable) {}
            try {
                track.setEnabled(true)
            } catch (_: Throwable) {}
            track.addSink(renderer)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            rendererHolder.value?.let { renderer ->
                viewModel.remoteVideoTrack.value?.let { track ->
                    try {
                        track.removeSink(renderer)
                    } catch (_: Throwable) {}
                }
                viewModel.detachRemoteRenderer(renderer)
                try {
                    renderer.release()
                } catch (_: Throwable) {}
            }
            rendererHolder.value = null
            // El EglBase del ViewModel se libera en onCleared(), no aquí:
            // así se puede reconectar sin recrear el ViewModel.
        }
    }

    val leaveScreen: () -> Unit = {
        rendererHolder.value?.let { renderer ->
            viewModel.remoteVideoTrack.value?.let { track ->
                try {
                    track.removeSink(renderer)
                } catch (_: Throwable) {}
            }
            viewModel.detachRemoteRenderer(renderer)
            try {
                renderer.release()
            } catch (_: Throwable) {}
        }
        rendererHolder.value = null
        viewModel.disconnect { onDisconnected() }
    }

    BackHandler { leaveScreen() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receptor") },
                navigationIcon = {
                    IconButton(onClick = leaveScreen) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!state.isConnected) {
                Text(
                    text = "Ver transmisión",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = input.uppercase()
                            .filter { it.isLetterOrDigit() }
                            .take(6)
                    },
                    label = { Text("Código de sesión (6 caracteres)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.joinSession(code) },
                    enabled = code.length == 6 && !state.isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Conectar")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                state.error?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                setZOrderMediaOverlay(true)
                                setEnableHardwareScaler(true)
                                init(viewModel.rendererEglBase.eglBaseContext, null)
                                setMirror(false)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                rendererHolder.value = this
                                viewModel.attachRemoteRenderer(this)
                            }
                        },
                        update = { view ->
                            rendererHolder.value = view
                        }
                    )

                    if (state.cameraMode == "OFF") {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.88f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Cámara apagada — Solo audio",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(12.dp))

                // Controles independientes del Emisor (Fase 14)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CameraModeButton(
                        label = "Cámara Trasera",
                        selected = state.cameraMode == "BACK",
                        onClick = { viewModel.selectCameraMode("BACK") },
                        modifier = Modifier.weight(1f)
                    )
                    CameraModeButton(
                        label = "Cámara Frontal",
                        selected = state.cameraMode == "FRONT",
                        onClick = { viewModel.selectCameraMode("FRONT") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CameraModeButton(
                        label = "Apagar Cámara",
                        selected = state.cameraMode == "OFF",
                        onClick = { viewModel.selectCameraMode("OFF") },
                        modifier = Modifier.weight(1f)
                    )

                    if (state.isMicEnabled) {
                        OutlinedButton(
                            onClick = { viewModel.toggleMicrophone() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Micrófono activo")
                            Spacer(Modifier.width(4.dp))
                            Text("Desactivar Micrófono", fontSize = 12.sp, maxLines = 1)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.toggleMicrophone() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MicOff, contentDescription = "Micrófono desactivado")
                            Spacer(Modifier.width(4.dp))
                            Text("Activar Micrófono", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = leaveScreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Desconectar")
                    Spacer(Modifier.width(6.dp))
                    Text("Desconectar")
                }
            }
        }
    }
}

@Composable
private fun CameraModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    }
}