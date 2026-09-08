package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.OnlineLobbyMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Opens a room on the relay server and shows the room code prominently —
 * that's the whole "discovery" step for online play, standing in for
 * Nearby's Bluetooth/Wi-Fi advertising since there's nothing to physically
 * discover over the internet. Otherwise the exact same shape as
 * [NearbyHostLobbyScreen]: show joined players as they arrive, Start once at
 * least one has, broadcast [OnlineLobbyMessage.GameStart].
 *
 * [gameRoute]/[gameDisplayName] parameterize this for any ONLINE-capable
 * game, same as Nearby's lobby — this pass wires only UNO in.
 */
@Composable
fun OnlineHostLobbyScreen(
    sessionManager: GameSessionManager,
    gameRoute: String,
    gameDisplayName: String,
    onGameStarted: (route: String) -> Unit,
    onBack: () -> Unit
) {
    val transport = sessionManager.pendingOnlineTransport ?: run {
        // Defensive: got here without a transport (e.g. process death mid-lobby) — bounce back.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val hostPlayerId = remember { "p-" + UUID.randomUUID().toString() }
    var roomCode by remember { mutableStateOf<String?>(null) }
    val connectedPlayers by transport.connectedPlayers.collectAsState()
    val connectionError by transport.connectionError.collectAsState()

    DisposableEffect(transport) {
        transport.onHosted { code -> roomCode = code }
        transport.hostRoom(hostPlayerId, "Host (${Build.MODEL})")
        onDispose {
            // Only tear down if the game never actually launched — once launchGame() has
            // captured this transport inside GameContext, its lifecycle belongs to the
            // active match, not this screen.
            if (sessionManager.activeContext.value?.transport !== transport) {
                transport.disconnect()
            }
        }
    }

    // verticalScroll: see NearbyEntryScreen's KDoc comment on the same fix — this
    // screen's connected-players list can grow with every joiner, same genuinely
    // unbounded content height as NearbyHostLobbyScreen.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Hosting $gameDisplayName", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when {
            connectionError != null -> {
                Text(
                    connectionError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            roomCode == null -> {
                Text("Connecting to the server...", style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Text("Room code", style = MaterialTheme.typography.labelLarge)
                Text(roomCode ?: "", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(8.dp))
                Text("Share this with other players", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))

                if (connectedPlayers.isEmpty()) {
                    Text("No one has joined yet.")
                } else {
                    Text("Joined:")
                    connectedPlayers.forEach { player -> Text("• ${player.displayName}") }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    enabled = connectedPlayers.isNotEmpty(),
                    onClick = {
                        val players = listOf(PlayerInfo(playerId = hostPlayerId, displayName = "Host")) +
                            connectedPlayers.map { PlayerInfo(playerId = it.playerId, displayName = it.displayName) }

                        val startMessage: OnlineLobbyMessage = OnlineLobbyMessage.GameStart(gameRoute, players)
                        transport.sendRaw(null, Json.encodeToString(startMessage).toByteArray(Charsets.UTF_8))

                        sessionManager.launchGame(
                            mode = PlayMode.ONLINE,
                            players = players,
                            localPlayerIndex = 0,
                            transport = transport
                        )
                        onGameStarted(gameRoute)
                    }
                ) {
                    Text("Start Game (${1 + connectedPlayers.size} players)")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Cancel") }
    }
}
