package fi.fips.node.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.fips.node.ui.FipsViewModel

@Composable
fun IdentityScreen(viewModel: FipsViewModel) {
    val status by viewModel.status.collectAsState()
    val nsec by viewModel.nsec.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val clipboard = LocalClipboardManager.current

    var showImportDialog by remember { mutableStateOf(false) }
    var nsecInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Identity",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (status.npub.isNotEmpty()) {
            // QR code of npub
            val qrBitmap = remember(status.npub) {
                generateQrBitmap(status.npub, 300)
            }
            if (qrBitmap != null) {
                Card {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "npub QR code",
                        modifier = Modifier
                            .size(250.dp)
                            .padding(16.dp),
                    )
                }
            }

            // npub display
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Public Key (npub)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = status.npub,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(status.npub))
                        }) {
                            Icon(Icons.Default.ContentCopy, "Copy npub")
                        }
                    }
                }
            }

            // Node address
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Node Address",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status.nodeAddr,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (!isRunning) {
            // Import nsec
            Button(
                onClick = { showImportDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import Identity (nsec)")
            }

            // Generate new identity hint
            Text(
                text = "Or start the node to generate a new identity",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import nsec") },
            text = {
                OutlinedTextField(
                    value = nsecInput,
                    onValueChange = { nsecInput = it },
                    label = { Text("nsec1...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.initNode(nsecInput)
                        showImportDialog = false
                    },
                    enabled = nsecInput.startsWith("nsec1"),
                ) {
                    Text("Import & Start")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
