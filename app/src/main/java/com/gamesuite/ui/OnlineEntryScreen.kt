package com.gamesuite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.transport.OnlineTransport

/**
 * Gates entry into real internet multiplayer (roadmap item 12) behind a
 * configured relay server address (Settings' "Online server" — see
 * server/README.md), the same shape [NearbyEntryScreen] uses to gate on
 * radios/permissions, just a config check instead of a hardware one. Once
 * satisfied, offers Host vs. Join, mirroring Nearby's entry screen.
 */
@Composable
fun OnlineEntryScreen(
    sessionManager: GameSessionManager,
    settingsViewModel: SettingsViewModel,
    onNavigateToHostLobby: () -> Unit,
    onNavigateToJoinLobby: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onBack: () -> Unit
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val serverUrl = settings.onlineServerUrl

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Play Online", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect to another player over the internet through a relay server — see server/README.md.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        if (serverUrl.isBlank()) {
            Text(
                "No online server configured yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNavigateToSettings) { Text("Set server address in Settings") }
        } else {
            Text("Server: $serverUrl", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                sessionManager.pendingOnlineTransport = OnlineTransport(serverUrl)
                onNavigateToHostLobby()
            }) { Text("Host") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                sessionManager.pendingOnlineTransport = OnlineTransport(serverUrl)
                onNavigateToJoinLobby()
            }) { Text("Join") }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
