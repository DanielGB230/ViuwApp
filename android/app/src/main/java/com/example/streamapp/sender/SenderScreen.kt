package com.example.streamapp.sender

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.streamapp.permissions.PermissionHandler
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(
    onBackPressed: () -> Unit,
    viewModel: SenderViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (PermissionHandler.isGranted(result)) {
            viewModel.startTransmission()
        } else {
            viewModel.onPermissionsDenied()
        }
    }

    LaunchedEffect(Unit) {
        if (PermissionHandler.hasCapturePermissions(context)) {
            viewModel.startTransmission()
        } else {
            permissionLauncher.launch(PermissionHandler.requiredPermissions())
        }
    }

    val rendererHolder = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            rendererHolder.value?.let { renderer ->
                viewModel.detachLocalRenderer(renderer)
                renderer.release()
            }
            rendererHolder.value = null
            viewModel.releaseRendererEgl()
        }
    }

    val leaveScreen: () -> Unit = {
        viewModel.stopTransmission()
        onBackPressed()
    }

    BackHandler { leaveScreen() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emisor") },
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
                            setMirror(true)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            rendererHolder.value = this
                            viewModel.attachLocalRenderer(this)
                        }
                    },
                    update = { view ->
                        rendererHolder.value = view
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Código de sala", style = MaterialTheme.typography.labelLarge)
            Text(
                text = state.sessionCode.ifEmpty { "------" },
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(state.status, style = MaterialTheme.typography.bodyMedium)

            state.error?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { viewModel.retryCamera() }
                ) {
                    Text("Reintentar cámara")
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.switchCamera() },
                    enabled = state.isStreaming,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cambiar cámara")
                }
                Button(
                    onClick = leaveScreen,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Detener")
                }
            }
        }
    }
}
