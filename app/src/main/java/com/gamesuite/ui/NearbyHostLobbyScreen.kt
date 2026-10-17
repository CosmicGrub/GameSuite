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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerNameStore
import com.gamesuite.transport.LobbySeatLimits
import com.gamesuite.transport.NearbyLobbyMessage
import com.gamesuite.transport.NearbyRoster
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Advertises this device, shows connected guests as they join (their
 * [NearbyLobbyMessage.JoinRequest]s go into a [NearbyRoster], which — not the guest — decides
 * each guest's player id; see NearbyLobbyProtocol.kt's KDoc), and starts the match once the
 * host taps Start.
 *
 * [gameRoute]/[gameDisplayName] parameterize this for any LOCAL_AD_HOC-capable game.
 * [minPlayers]/[maxPlayers] are the game's TOTAL seats including the host (UNO 2..10, a two-seat
 * game 2..2): the roster turns away a guest beyond [maxPlayers] - 1 with a "room is full"
 * [NearbyLobbyMessage.Rejected], and Start stays disabled until [minPlayers] - 1 guests have joined.
 *
 * Start launches with the host as seat 0 (`localPlayerIndex` 0, playerId "host") and the guests
 * after it in join order, and announces the SAME player list (host's real name included) to every guest.
 */
@Composable
fun NearbyHostLobbyScreen(
    sessionManager: GameSessionManager,
    gameRoute: String,
    gameDisplayName: String,
    onGameStarted: (route: String) -> Unit,
    onBack: () -> Unit,
    minPlayers: Int = 2,
    maxPlayers: Int = 10
) {
    val context = LocalContext.current
    val transport = sessionManager.pendingNearbyTransport ?: run {
        // Defensive: got here without a transport (e.g. process death mid-lobby) — bounce back.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val limits = remember(minPlayers, maxPlayers) { LobbySeatLimits.of(minPlayers, maxPlayers) }

    // Guests in join order, each with the seat id THIS host assigned it (never a raw client value).
    var roster by remember { mutableStateOf(NearbyRoster(limits = limits)) }
    val connectedPeers by transport.connectedPeers.collectAsState()

    DisposableEffect(transport) {
        transport.onRawMessageReceived { endpointId, payload ->
            val message = try {
                Json.decodeFromString<NearbyLobbyMessage>(String(payload, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
            if (message is NearbyLobbyMessage.JoinRequest) {
                // Full room / already started: tell that guest why instead of seating it (an endpoint that
                // already has a seat is never refused). The guest leaves on its own after reading this.
                val refusal = roster.rejectionFor(endpointId)
                if (refusal != null) {
                    val refusalMessage: NearbyLobbyMessage = refusal
                    transport.sendRaw(endpointId, Json.encodeToString(refusalMessage).toByteArray(Charsets.UTF_8))
                } else {
                    roster = roster.withJoinRequest(endpointId, message)
                }
            }
        }
        transport.startHosting("$gameDisplayName (${Build.MODEL})")
        onDispose {
            // Only tear down if the game never actually launched — once launchGame() has
            // captured this transport inside GameContext, its lifecycle belongs to the
            // active match, not this screen.
            if (sessionManager.activeContext.value?.transport !== transport) {
                // Leaving the lobby: stop listening, then disconnect (stops advertising, drops every
                // endpoint, clears the identity mapping) and forget the now-dead transport.
                transport.onRawMessageReceived { _, _ -> }
                transport.disconnect()
                if (sessionManager.pendingNearbyTransport === transport) sessionManager.pendingNearbyTransport = null
            }
        }
    }

    // A guest that disconnects before Start Game is tapped should drop out of the roster too.
    LaunchedEffect(connectedPeers) {
        roster = roster.retainingEndpoints(connectedPeers.map { it.endpointId })
    }

    val guestCount = roster.guests.size
    val waiting = limits.waitingMessage(guestCount)

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
        Text(
            when {
                limits.isFull(guestCount) -> "Room is full (${limits.maxPlayers} players)."
                else -> waiting?.let { "$it..." } ?: "Ready to start. More players can still join."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))

        if (roster.guests.isEmpty()) {
            Text("No one has joined yet.")
        } else {
            Text("Joined:")
            roster.guests.forEach { guest -> Text("• ${guest.displayName}") }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            enabled = roster.canStart,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            onClick = {
                if (!roster.canStart) return@Button
                // launch() freezes the roster (a JoinRequest that lands after this can't add a seat) and
                // decides the player list, the mapping to install and each guest's GameStart together, so
                // what is installed is exactly what is announced. This screen only applies the plan.
                val hostName = PlayerNameStore(context).lobbyName("Host")
                val launch = roster.launch(gameRoute, hostDisplayName = hostName, gameDisplayName = gameDisplayName)
                roster = launch.roster
                // launch.players: the host (seat 0, id "host", the remembered name) then guests in join order.
                val players = launch.players

                transport.setPlayerIdMapping(launch.endpointToPlayerId)
                transport.stopAdvertising()

                launch.startMessages.forEach { (endpointId, start) ->
                    val startMessage: NearbyLobbyMessage = start
                    transport.sendRaw(endpointId, Json.encodeToString(startMessage).toByteArray(Charsets.UTF_8))
                }

                sessionManager.launchGame(
                    mode = PlayMode.LOCAL_AD_HOC,
                    players = players,
                    localPlayerIndex = 0,
                    transport = transport
                )
                onGameStarted(gameRoute)
            }
        ) {
            Text("Start Game (${1 + guestCount} players)")
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        ) { Text("Cancel") }
    }
}
