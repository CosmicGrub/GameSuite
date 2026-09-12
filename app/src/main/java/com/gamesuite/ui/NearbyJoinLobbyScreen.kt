package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.transport.NearbyLobbyMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Discovers nearby hosts, connects on tap, announces itself (a [NearbyLobbyMessage.JoinRequest]
 * carrying a self-minted, stable id — see NearbyLobbyProtocol.kt's KDoc for why), then
 * waits for the host's [NearbyLobbyMessage.GameStart] before launching into the game.
 */
@Composable
fun NearbyJoinLobbyScreen(
    sessionManager: GameSessionManager,
    onGameStarted: (route: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val transport = sessionManager.pendingNearbyTransport ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val clientPlayerId = remember { "p-" + UUID.randomUUID().toString() }
    val discoveredHosts by transport.discoveredHosts.collectAsState()
    var connectedHostEndpointId by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }

    DisposableEffect(transport) {
        transport.onConnected { endpointId, _ ->
            connectedHostEndpointId = endpointId
            val joinMessage: NearbyLobbyMessage = NearbyLobbyMessage.JoinRequest(clientPlayerId, "Guest (${Build.MODEL})")
            transport.sendRaw(endpointId, Json.encodeToString(joinMessage).toByteArray(Charsets.UTF_8))
        }
        transport.onRawMessageReceived { endpointId, payload ->
            val message = try {
                Json.decodeFromString<NearbyLobbyMessage>(String(payload, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
            if (message is NearbyLobbyMessage.GameStart) {
                val myIndex = message.players.indexOfFirst { it.playerId == clientPlayerId }
                if (myIndex < 0) return@onRawMessageReceived // roster didn't include us — ignore rather than crash
                transport.setPlayerIdMapping(mapOf(endpointId to "host"))
                transport.stopDiscovery()
                sessionManager.launchGame(
                    mode = PlayMode.LOCAL_AD_HOC,
                    players = message.players,
                    localPlayerIndex = myIndex,
                    transport = transport
                )
                onGameStarted(message.gameRoute)
            }
        }
        transport.startDiscovery("Guest (${Build.MODEL})")
        onDispose {
            if (sessionManager.activeContext.value?.transport !== transport) {
                transport.disconnect()
            }
        }
    }

    // verticalScroll: see NearbyEntryScreen's KDoc comment on the same fix — this
    // screen's discovered-hosts list can grow with every nearby advertiser found, so
    // unlike the entry screen this one's content height is genuinely unbounded, not
    // just theoretically close to the ~344dp cover-screen-landscape ceiling.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            connectedHostEndpointId != null -> {
                Text("Connected!", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Waiting for the host to start the game...")
            }
            else -> {
                Text("Nearby games", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                if (discoveredHosts.isEmpty()) {
                    Text("Searching...")
                } else {
                    // focusGroup() (§4c): the discovered-host list is a dynamic, clue-list-style
                    // set of tappable rows — Tab moves through them as one cluster, then on to
                    // Cancel below, rather than treating each as an unrelated stop.
                    Column(modifier = Modifier.focusGroup()) {
                        discoveredHosts.forEach { host ->
                            Button(
                                enabled = !connecting,
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                onClick = {
                                    connecting = true
                                    transport.requestConnectionTo(host.endpointId)
                                }
                            ) {
                                Text(host.displayName)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        ) { Text("Cancel") }
    }
}
