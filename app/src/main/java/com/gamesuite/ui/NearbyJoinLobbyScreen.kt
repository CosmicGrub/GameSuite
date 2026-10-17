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
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerNameStore
import com.gamesuite.transport.NearbyLobbyMessage
import com.gamesuite.transport.lobbyGameProblem
import com.gamesuite.transport.lobbyRejectionText
import com.gamesuite.transport.resolveForGuest
import com.gamesuite.transport.LobbyJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Discovers nearby hosts, connects on tap, announces itself (a [NearbyLobbyMessage.JoinRequest]
 * proposing a self-minted id — the host assigns the real one, see NearbyLobbyProtocol.kt's
 * KDoc), then waits for the host's [NearbyLobbyMessage.GameStart] before launching into the game.
 * A GameStart is only believed from the endpoint this guest connected to.
 */
@Composable
fun NearbyJoinLobbyScreen(
    sessionManager: GameSessionManager,
    onGameStarted: (route: String) -> Unit,
    onBack: () -> Unit,
    /** The game this guest opened the lobby for (its Hub). A host announcing a different or unplayable game is refused. */
    gameId: String? = null,
    gameDisplayName: String? = null
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
    // Set (from the Nearby callback thread; a Compose state write is thread-safe) when the host turned us away
    // (room full / already started) or announced a game we must not start into. Shown instead of the lobby.
    var refusal by remember { mutableStateOf<String?>(null) }
    val guestName = remember(context) { PlayerNameStore(context).lobbyName("Guest (${Build.MODEL})") }
    val title = gameDisplayName ?: gameId?.let { GameHubSpecs.byId(it)?.title }

    // Disconnect on the main thread once refused, so the host stops counting us and nothing stays connected.
    LaunchedEffect(refusal) {
        if (refusal != null) transport.disconnect()
    }

    DisposableEffect(transport) {
        transport.onConnected { endpointId, _ ->
            connectedHostEndpointId = endpointId
            val joinMessage: NearbyLobbyMessage = NearbyLobbyMessage.JoinRequest(clientPlayerId, guestName)
            transport.sendRaw(endpointId, Json.encodeToString(joinMessage).toByteArray(Charsets.UTF_8))
        }
        transport.onRawMessageReceived { endpointId, payload ->
            val message = try {
                LobbyJson.decodeFromString<NearbyLobbyMessage>(String(payload, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
            if (message is NearbyLobbyMessage.Rejected) {
                // Only the host we connected to can turn us away.
                if (connectedHostEndpointId != null && endpointId == connectedHostEndpointId) {
                    refusal = lobbyRejectionText(message.reason)
                }
            }
            if (message is NearbyLobbyMessage.GameStart) {
                // Only the host we connected to may start us, and only with a roster that validly seats us;
                // anything else is ignored rather than crashed on (resolveForGuest holds every rule).
                val start = message.resolveForGuest(endpointId, connectedHostEndpointId, clientPlayerId)
                    ?: return@onRawMessageReceived
                // A host playing a game we did not open, or one this build cannot play over a network: say so
                // and leave instead of starting into it.
                val problem = lobbyGameProblem(message.gameRoute, message.gameDisplayName, gameId)
                if (problem != null) {
                    refusal = problem
                    return@onRawMessageReceived
                }
                transport.setPlayerIdMapping(start.hostEndpointToPlayerId)
                transport.stopDiscovery()
                sessionManager.launchGame(
                    mode = PlayMode.LOCAL_AD_HOC,
                    players = message.players,
                    localPlayerIndex = start.localPlayerIndex,
                    transport = transport
                )
                onGameStarted(message.gameRoute)
            }
        }
        transport.startDiscovery(guestName)
        onDispose {
            if (sessionManager.activeContext.value?.transport !== transport) {
                // Leaving the lobby: stop listening, then disconnect (stops discovery, drops the host
                // endpoint) and forget the now-dead transport.
                transport.onConnected { _, _ -> }
                transport.onRawMessageReceived { _, _ -> }
                transport.disconnect()
                if (sessionManager.pendingNearbyTransport === transport) sessionManager.pendingNearbyTransport = null
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
            refusal != null -> {
                Text("Can't join", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(refusal ?: "", color = MaterialTheme.colorScheme.error)
            }
            connectedHostEndpointId != null -> {
                Text(
                    if (title != null) "Joining $title" else "Connected!",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text("Connected. Waiting for the host to start the game...")
            }
            else -> {
                Text(
                    if (title != null) "Nearby $title games" else "Nearby games",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                if (discoveredHosts.isEmpty()) {
                    Text("Searching...")
                } else {
                    // focusGroup() (§4c): the discovered-host list is a dynamic, clue-list-style
                    // set of tappable rows — Tab moves through them as one cluster, then on to
                    // Cancel below, rather than treating each as an unrelated stop.
                    Column(modifier = Modifier.focusGroup()) {
                        discoveredHosts.forEach { host ->
                            // A host advertises "<Game title> (<device model>)": one hosting a different game is
                            // listed but cannot be tapped, so a guest never joins a room it can't play.
                            val sameGame = title == null || host.displayName.startsWith("$title (")
                            Button(
                                enabled = !connecting && sameGame,
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                onClick = {
                                    connecting = true
                                    transport.requestConnectionTo(host.endpointId)
                                }
                            ) {
                                Text(if (sameGame) host.displayName else "${host.displayName} - different game")
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
