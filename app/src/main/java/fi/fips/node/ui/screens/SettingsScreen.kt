package fi.fips.node.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.fips.node.ui.AppSettings
import fi.fips.node.ui.FipsViewModel

@Composable
fun SettingsScreen(viewModel: FipsViewModel) {
    val settings by viewModel.settings.collectAsState()

    var udpPort by remember(settings) { mutableStateOf(settings.udpPort.toString()) }
    var bootstrapPeers by remember(settings) { mutableStateOf(settings.bootstrapPeers) }
    var audioUltrasonic by remember(settings) { mutableStateOf(settings.audioUltrasonic) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
        )

        // Network settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Network",
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = udpPort,
                    onValueChange = { udpPort = it },
                    label = { Text("UDP Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = bootstrapPeers,
                    onValueChange = { bootstrapPeers = it },
                    label = { Text("Bootstrap Peers") },
                    supportingText = { Text("Comma-separated host:port entries") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        }

        // Audio settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Audio Transport",
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Ultrasonic Mode")
                        Text(
                            text = if (audioUltrasonic) "18-20 kHz (inaudible)" else "1-4 kHz (audible)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = audioUltrasonic,
                        onCheckedChange = { audioUltrasonic = it },
                    )
                }
            }
        }

        // About
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text("FIPS Android v0.1.0")
                Text(
                    text = "Free Internetworking Peering System",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "A distributed mesh networking protocol using Nostr identities",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Save button
        Button(
            onClick = {
                viewModel.updateSettings(AppSettings(
                    udpPort = udpPort.toIntOrNull() ?: 4000,
                    bootstrapPeers = bootstrapPeers,
                    audioUltrasonic = audioUltrasonic,
                ))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save Settings")
        }
    }
}
