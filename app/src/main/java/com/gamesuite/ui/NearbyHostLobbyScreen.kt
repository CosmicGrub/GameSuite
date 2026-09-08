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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.NearbyLobbyMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Advertises this device, shows connected guests as they join (matched against their
 * self-declared [NearbyLobbyMessage.JoinRequest] — see NearbyLobbyProtocol.kt's KDoc for
 * why the host can't just use raw endpoint ids as player ids), and starts the match once
 * the host taps Start.
 *
 * [gameRoute]/[gameDisplayName] parameterize this for any LOCAL_AD_HOC-capable game —
 * this pass wires only UNO in, but the lobby itself isn't UNO-specific.
 */
@Composable
fun NearbyHostLobbyScreen(
    sessionManager: GameSessionManager,
    gameRoute: String,
    gameDisplayName: String,
    onGameStarted: (route: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val transport = sessionManager.pendingNearbyTransport ?: run {
        // Defensive: got here without a transport (e.g. process death mid-lobby) — bounce back.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // endpointId -> the guest's self-declared JoinRequest, keyed as it arrives.
    var joinedGuests by remember { mutableStateOf<Map<String, NearbyLobbyMessage.JoinRequest>>(emptyMap()) }
    val connectedPeers by transport.connectedPeers.collectAsState()

    DisposableEffect(transport) {
        transport.onRawMessageReceived { endpointId, payload ->
            val message = try {
                Json.decodeFromString<NearbyLobbyMessage>(String(payload, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
            if (message is NearbyLobbyMessage.JoinRequest) {
                joinedGuests = joinedGuests + (endpointId to message)
            }
        }
        transport.startHosting("GameSuite Host (${Build.MODEL})")
        onDispose {
            // Only tear down if the game never actually launched — once launchGame() has
            // captured this transport inside GameContext, its lifecycle belongs to the
            // active match, not this screen.
            if (sessionManager.activeContext.value?.transport !== transport) {
                transport.disconnect()
            }
        }
    }

    // A guest that disconnects before Start Game is tapped should drop out of the roster too.
    LaunchedEffect(connectedPeers) {
        joinedGuests = joinedGuests.filterKeys { endpointId -> connectedPeers.any { it.endpointId == endpointId } }
    }

    // verticalScroll: see NearbyEntryScreen's KDoc comment on the same fix — this screen's
    // joined-guest list can grow (host + up to 3-4 guests per DEVICE_SPECIFIC_PLAN.md §2),
    // so unlike the entry screen this one's content height is genuinely unbounded, not
    // just theoretically close to the ~344dp cover-screen-landscape ceiling.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Hosting $gameDisplayName", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Waiting for players to join...", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        if (joinedGuests.isEmpty()) {
            Text("No one has joined yet.")
        } else {
            Text("Joined:")
            joinedGuests.values.forEach { guest -> Text("• ${guest.displayName}") }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            enabled = joinedGuests.isNotEmpty(),
            onClick = {
                val players = listOf(PlayerInfo(playerId = "host", displayName = "Host")) +
                    joinedGuests.values.map { PlayerInfo(playerId = it.clientPlayerId, displayName = it.displayName) }

                transport.setPlayerIdMapping(joinedGuests.mapValues { it.value.clientPlayerId })
                transport.stopAdvertising()

                val startMessage: NearbyLobbyMessage = NearbyLobbyMessage.GameStart(gameRoute, players)
                transport.sendRawToAll(Json.encodeToString(startMessage).toByteArray(Charsets.UTF_8))

                sessionManager.launchGame(
                    mode = PlayMode.LOCAL_AD_HOC,
                    players = players,
                    localPlayerIndex = 0,
                    transport = transport
                )
                onGameStarted(gameRoute)
            }
        ) {
            Text("Start Game (${1 + joinedGuests.size} players)")
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Cancel") }
    }
}
