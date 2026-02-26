package fi.fips.node.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fi.fips.node.transport.TransportType
import fi.fips.node.ui.FipsViewModel
import fi.fips.node.ui.PeerInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(viewModel: FipsViewModel) {
    val peers by viewModel.peers.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with add button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${peers.size} Peers",
                style = MaterialTheme.typography.headlineSmall,
            )
            FilledTonalIconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add peer")
            }
        }

        if (peers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No peers connected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(peers) { peer ->
                    PeerCard(peer)
                }
            }
        }
    }

    if (showAddDialog) {
        AddPeerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { addr ->
                showAddDialog = false
                // Manual peer add would initiate connection here
            }
        )
    }
}

@Composable
private fun PeerCard(peer: PeerInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Circle,
                    contentDescription = null,
                    tint = if (peer.connected) Color(0xFF4CAF50) else Color(0xFFFF5722),
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = peer.npub.take(16) + "..." + peer.npub.takeLast(6),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = peer.remoteAddr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TransportBadge(peer.transportId)
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "RX: ${peer.packetsRx} pkts / ${formatBytes(peer.bytesRx)}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = "TX: ${peer.packetsTx} pkts / ${formatBytes(peer.bytesTx)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (peer.lastSeenMs > 0) {
                Text(
                    text = "Last seen: ${peer.lastSeenMs / 1000}s ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransportBadge(transportId: Int) {
    val name = when (transportId) {
        1 -> "UDP"
        2 -> "BLE"
        3 -> "P2P"
        4 -> "NFC"
        5 -> "Audio"
        6 -> "QR"
        else -> "?"
    }
    SuggestionChip(
        onClick = {},
        label = { Text(name, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.height(24.dp),
    )
}

@Composable
private fun AddPeerDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var address by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Peer") },
        text = {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address (host:port)") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(address) },
                enabled = address.contains(":"),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }
}
