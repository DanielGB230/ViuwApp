package com.example.streamapp.ui.devices

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.streamapp.DeviceListItem
import com.example.streamapp.ui.theme.Emerald
import com.example.streamapp.ui.theme.Slate400

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverDevicesScreen(
    devices: List<DeviceListItem>,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onDeviceSelected: (deviceId: String) -> Unit,
    onManualCodeConnect: (code: String) -> Unit,
    onSignOutClicked: () -> Unit
) {
    var manualCode by remember { mutableStateOf("") }
    var showManualCode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mis Dispositivos", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${devices.count { it.isOnline }} en línea · ${devices.size} vinculados",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                    }
                    TextButton(onClick = onSignOutClicked) {
                        Text("Salir")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (isLoading && devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Slate400,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No hay dispositivos supervisados todavía.\nConfigura un teléfono en modo Emisor con tu misma cuenta.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            enabled = !isLoading,
                            onViewCamera = { onDeviceSelected(device.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Sección secundaria: conexión manual por código de sesión.
            TextButton(
                onClick = { showManualCode = !showManualCode },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (showManualCode) "Ocultar conexión por código" else "¿Tienes un código? Conectar manualmente"
                )
            }

            if (showManualCode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Conectar por código de sesión",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualCode,
                                onValueChange = { manualCode = it.uppercase().take(6) },
                                placeholder = { Text("Código (ej. AB12CD)") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { onManualCodeConnect(manualCode) },
                                enabled = manualCode.length == 6,
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Unirse")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceListItem,
    enabled: Boolean,
    onViewCamera: () -> Unit
) {
    val statusColor = if (device.isOnline) Emerald else Slate400
    val statusText = if (device.isOnline) "En línea" else "Desconectado"
    val lastSeen = device.lastSeen?.takeIf { it.isNotBlank() }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )

                    Spacer(Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (lastSeen != null) {
                    "Última conexión: ${formatLastSeen(lastSeen)}"
                } else {
                    "Sin conexiones registradas"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onViewCamera,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ver Cámara")
            }
        }
    }
}

/** Presenta el ISO-8601 del backend de forma legible y compacta. */
private fun formatLastSeen(raw: String): String {
    return try {
        val datePart = raw.substringBefore('T')
        val timePart = raw.substringAfter('T', "").take(5)
        if (timePart.isNotBlank()) "$datePart · $timePart" else datePart
    } catch (_: Throwable) {
        raw
    }
}
