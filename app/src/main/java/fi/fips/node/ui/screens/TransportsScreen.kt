package fi.fips.node.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import fi.fips.node.transport.TransportState
import fi.fips.node.transport.TransportType
import fi.fips.node.ui.FipsViewModel
import fi.fips.node.ui.TransportInfo

@Composable
fun TransportsScreen(viewModel: FipsViewModel) {
    val transports by viewModel.transports.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "Transports",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        items(transports) { transport ->
            TransportCard(
                transport = transport,
                onToggle = { viewModel.toggleTransport(transport.id) },
            )
        }
    }
}

@Composable
private fun TransportCard(transport: TransportInfo, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = transportIcon(transport.type),
                contentDescription = null,
                tint = if (transport.state == TransportState.RUNNING)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transport.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when (transport.state) {
                        TransportState.RUNNING -> "Active"
                        TransportState.STARTING -> "Starting..."
                        TransportState.ERROR -> "Error"
                        TransportState.STOPPED -> if (transport.available) "Stopped" else "Unavailable"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (transport.state) {
                        TransportState.RUNNING -> Color(0xFF4CAF50)
                        TransportState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Switch(
                checked = transport.state == TransportState.RUNNING,
                onCheckedChange = { onToggle() },
                enabled = transport.available,
            )
        }
    }
}

private fun transportIcon(type: TransportType): ImageVector {
    return when (type) {
        TransportType.UDP -> Icons.Default.Language
        TransportType.BLE -> Icons.Default.Bluetooth
        TransportType.WIFI_DIRECT -> Icons.Default.Wifi
        TransportType.NFC -> Icons.Default.Nfc
        TransportType.AUDIO -> Icons.Default.GraphicEq
        TransportType.VIDEO -> Icons.Default.QrCode2
    }
}
