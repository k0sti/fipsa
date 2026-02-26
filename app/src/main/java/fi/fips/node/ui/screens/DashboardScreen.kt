package fi.fips.node.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fi.fips.node.transport.TransportState
import fi.fips.node.ui.FipsViewModel

@Composable
fun DashboardScreen(viewModel: FipsViewModel) {
    val status by viewModel.status.collectAsState()
    val transports by viewModel.transports.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

    val activeTransports = transports.count { it.state == TransportState.RUNNING }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Node status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isRunning) "Node Online" else "Node Offline",
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (status.npub.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = status.npub.take(20) + "..." + status.npub.takeLast(8),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "Peers",
                value = status.peerCount.toString(),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = "Transports",
                value = "$activeTransports / ${transports.size}",
            )
        }

        // Metrics row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "RX Packets",
                value = status.totalPacketsRx.toString(),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = "TX Packets",
                value = status.totalPacketsTx.toString(),
            )
        }

        // Uptime
        if (isRunning) {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                label = "Uptime",
                value = formatUptime(status.uptimeSecs),
            )
        }

        // Transport status list
        Text(
            text = "Active Transports",
            style = MaterialTheme.typography.titleMedium,
        )
        transports.filter { it.state == TransportState.RUNNING }.forEach { transport ->
            SuggestionChip(
                onClick = {},
                label = { Text(transport.displayName) },
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatUptime(secs: Long): String {
    val hours = secs / 3600
    val minutes = (secs % 3600) / 60
    val seconds = secs % 60
    return if (hours > 0) "${hours}h ${minutes}m ${seconds}s"
    else if (minutes > 0) "${minutes}m ${seconds}s"
    else "${seconds}s"
}
