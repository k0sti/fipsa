package fi.fips.node.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class FipsScreen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    Dashboard("dashboard", "Dashboard", Icons.Default.Dashboard),
    Peers("peers", "Peers", Icons.Default.People),
    Transports("transports", "Transports", Icons.Default.SettingsInputAntenna),
    Identity("identity", "Identity", Icons.Default.Fingerprint),
    Settings("settings", "Settings", Icons.Default.Settings),
}
