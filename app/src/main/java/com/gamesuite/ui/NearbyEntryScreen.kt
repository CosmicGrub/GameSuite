package com.gamesuite.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamesuite.core.GameSessionManager
import com.gamesuite.transport.NearbyConnectionsTransport

/**
 * Gates entry into Nearby multiplayer behind (1) the runtime permissions Nearby
 * Connections needs and (2) both radios actually being on — see NearbyPermissions.kt
 * and DEVICE_SPECIFIC_PLAN.md §2's radio-auto-enable-removal note. Once both are
 * satisfied, offers Host vs. Join.
 */
@Composable
fun NearbyEntryScreen(
    sessionManager: GameSessionManager,
    onNavigateToHostLobby: () -> Unit,
    onNavigateToJoinLobby: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(hasAllNearbyPermissions(context)) }
    var bluetoothOn by remember { mutableStateOf(isBluetoothEnabled(context)) }
    var wifiOn by remember { mutableStateOf(isWifiEnabled(context)) }

    // Re-check radio state whenever this screen resumes — covers the user bouncing out
    // to Settings (or the system Bluetooth-enable dialog) and back, since there's no
    // push notification for "the user just flipped a radio on" to react to instead.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = hasAllNearbyPermissions(context)
                bluetoothOn = isBluetoothEnabled(context)
                wifiOn = isWifiEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { bluetoothOn = isBluetoothEnabled(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Play Nearby", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect to another device over Bluetooth/Wi-Fi Direct — no internet needed. Both devices need this screen.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        when {
            !permissionsGranted -> {
                Text("GameSuite needs Nearby devices permission to find and connect to other players.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(requiredNearbyPermissions()) }) {
                    Text("Grant permission")
                }
            }
            !bluetoothOn -> {
                Text("Bluetooth is off — turn it on to continue.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                    Text("Enable Bluetooth")
                }
            }
            !wifiOn -> {
                Text("Wi-Fi is off — turn it on to continue.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Intent(Settings.Panel.ACTION_WIFI)
                    } else {
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Open Wi-Fi settings")
                }
            }
            else -> {
                Button(onClick = {
                    sessionManager.pendingNearbyTransport = NearbyConnectionsTransport(context)
                    onNavigateToHostLobby()
                }) {
                    Text("Host a game")
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    sessionManager.pendingNearbyTransport = NearbyConnectionsTransport(context)
                    onNavigateToJoinLobby()
                }) {
                    Text("Join a game")
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}
