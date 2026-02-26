package fi.fips.node

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fi.fips.node.service.FipsService
import fi.fips.node.transport.TransportManager
import fi.fips.node.ui.FipsViewModel
import fi.fips.node.ui.navigation.FipsScreen
import fi.fips.node.ui.screens.*
import fi.fips.node.ui.theme.FipsTheme

class MainActivity : ComponentActivity() {

    private val viewModel: FipsViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            initializeNode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val transportManager = TransportManager(this, viewModel.core)
        viewModel.initTransportManager(transportManager)

        requestPermissions()

        setContent {
            FipsTheme {
                FipsApp(viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            viewModel.shutdown()
            stopService(Intent(this, FipsService::class.java))
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            initializeNode()
        }
    }

    private fun initializeNode() {
        val prefs = getSharedPreferences("fips_prefs", MODE_PRIVATE)
        val nsec = prefs.getString("nsec", null)
        if (nsec != null && nsec.startsWith("nsec1")) {
            viewModel.initNode(nsec)
            startFipsService()
        }
    }

    private fun startFipsService() {
        val intent = Intent(this, FipsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun FipsApp(viewModel: FipsViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomTabs = listOf(
        FipsScreen.Dashboard,
        FipsScreen.Peers,
        FipsScreen.Transports,
        FipsScreen.Identity,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomTabs.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(FipsScreen.Dashboard.route) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = FipsScreen.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(FipsScreen.Dashboard.route) { DashboardScreen(viewModel) }
            composable(FipsScreen.Peers.route) { PeersScreen(viewModel) }
            composable(FipsScreen.Transports.route) { TransportsScreen(viewModel) }
            composable(FipsScreen.Identity.route) { IdentityScreen(viewModel) }
            composable(FipsScreen.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}
