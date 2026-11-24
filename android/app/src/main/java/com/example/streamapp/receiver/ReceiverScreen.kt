package com.example.streamapp.receiver

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Cameraswitch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(
    initialSessionCode: String = "",
    onBackPressed: () -> Unit,
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
            viewModel.releaseRendererEgl()
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
        viewModel.leaveSession()
        onBackPressed()
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

                // Controles remotos del Emisor
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.switchCamera() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Cambiar cámara"
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Cambiar Cámara")
                    }

                    OutlinedButton(
                        onClick = { viewModel.toggleMute() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (state.isMuted) "Micrófono silenciado" else "Micrófono activo"
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.isMuted) "Silenciado" else "Micrófono")
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = leaveScreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Desconectar")
                }
            }
        }
    }
}