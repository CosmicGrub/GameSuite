package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerNameStore
import com.gamesuite.transport.OnlineLobbyMessage
import com.gamesuite.transport.isFromHost
import com.gamesuite.transport.lobbyGameProblem
import com.gamesuite.transport.lobbyRejectionText
import com.gamesuite.transport.localSeatIndex
import com.gamesuite.transport.LobbyJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Room codes are exactly this many characters (see server/index.js's ROOM_CODE_LENGTH). */
private const val ROOM_CODE_LENGTH = 4

/**
 * Joins a room by code (typed in, since there's nothing to discover over
 * the internet the way Nearby scans for nearby advertisers), announces
 * itself, then waits for the host's [OnlineLobbyMessage.GameStart] before
 * launching into the game — otherwise the same shape as
 * [NearbyJoinLobbyScreen].
 */
@Composable
fun OnlineJoinLobbyScreen(
    sessionManager: GameSessionManager,
    onGameStarted: (route: String) -> Unit,
    onBack: () -> Unit,
    /** The game this guest opened the lobby for (its Hub). A room playing a different or unplayable game is refused. */
    gameId: String? = null,
    gameDisplayName: String? = null
) {
    val transport = sessionManager.pendingOnlineTransport ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val title = gameDisplayName ?: gameId?.let { GameHubSpecs.byId(it)?.title }
    // Set when the host turned us away (room full) or the room plays a game we must not start into. Written from
    // OkHttp's thread (a Compose state write is thread-safe); the disconnect below runs on the main thread.
    var refusal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refusal) {
        if (refusal != null) transport.disconnect()
    }

    val context = LocalContext.current
    val guestName = remember(context) { PlayerNameStore(context).lobbyName("Guest (${Build.MODEL})") }
    val clientPlayerId = remember { "p-" + UUID.randomUUID().toString() }
    var roomCodeInput by remember { mutableStateOf("") }
    var hasJoined by remember { mutableStateOf(false) }
    // Set only by the Watch button below -- distinguishes a deliberate spectator join (where
    // this client is legitimately absent from GameStart's own roster, see server/index.js's
    // playerList()/broadcastToRoom KDoc: "real seats only, never spectators") from the
    // pre-existing "roster didn't include us" guard below, which exists for a genuine bug, not
    // this. OnlineTransport.joinRoom's own spectator param and GameSessionManager's
    // localPlayerIndex == -1 convention (see its localOutcomeFor KDoc) already fully support
    // this end-to-end -- audited finding: only this screen never offered the option.
    var joinedAsSpectator by remember { mutableStateOf(false) }
    // Set from OkHttp's WebSocket callback thread (see the raw-message listener below)
    // -- deliberately NOT acted on there. A plain Compose state write is thread-safe
    // (the same reason `hasJoined` above already worked correctly from that thread),
    // but the navigation this eventually triggers is NOT: NavController.navigate
    // requires the main thread, and WebSocketListener.onMessage runs on OkHttp's own
    // dispatcher, never Main. OnlineHostLobbyScreen's identical onGameStarted call only
    // ever worked because it fires from a Button's onClick, which Compose always runs
    // on Main -- there is no such guarantee here. Calling navigate() off-thread doesn't
    // crash or log anything; it just silently never happens, which is exactly what left
    // every guest stuck on "Connected! Waiting for the host to start the game..."
    // forever. The LaunchedEffect below performs the actual navigation from the
    // composition's own main-thread-bound coroutine scope instead.
    var pendingGameStart by remember { mutableStateOf<OnlineLobbyMessage.GameStart?>(null) }
    val connectionError by transport.connectionError.collectAsState()

    LaunchedEffect(pendingGameStart) {
        val message = pendingGameStart ?: return@LaunchedEffect
        // A room playing a game we did not open, or one this build can't play over a network: say so, don't start.
        val problem = lobbyGameProblem(message.gameRoute, message.gameDisplayName, gameId)
        if (problem != null) {
            refusal = problem
            return@LaunchedEffect
        }
        // localSeatIndex: -1 for a spectator (legitimately absent from the roster, server-side playerList()
        // excludes spectators by design, and never seated even if a roster claims to), the seat for a
        // validly seated player, and null for a non-spectator genuinely missing or not validly seated,
        // which would be a real bug, not this.
        val myIndex = message.localSeatIndex(clientPlayerId, joinedAsSpectator)
            ?: return@LaunchedEffect // roster didn't (validly) include us — ignore rather than crash
        sessionManager.launchGame(
            mode = PlayMode.ONLINE,
            players = message.players,
            // -1 for a spectator -- GameSessionManager.localOutcomeFor and every GameModule's
            // own humanIndex() (see e.g. UnoScreen.kt) already treat that as "no seat, watch
            // only," so no further plumbing is needed past this call.
            localPlayerIndex = myIndex,
            transport = transport
        )
        onGameStarted(message.gameRoute)
    }

    DisposableEffect(transport) {
        transport.onJoined { _, _ -> hasJoined = true }
        transport.onRawMessageReceived { fromPlayerId, payload ->
            val message = runCatching {
                LobbyJson.decodeFromString<OnlineLobbyMessage>(String(payload, Charsets.UTF_8))
            }.getOrNull()
            // Any room member can relay a GameStart-shaped message; only the host's is real.
            if (message is OnlineLobbyMessage.GameStart && message.isFromHost(fromPlayerId, transport.hostPlayerId)) {
                pendingGameStart = message
            }
            // Only the host can turn a guest away (the room holds more guests than the game has seats).
            if (message is OnlineLobbyMessage.Rejected && message.isFromHost(fromPlayerId, transport.hostPlayerId)) {
                refusal = lobbyRejectionText(message.reason)
            }
        }
        onDispose {
            if (sessionManager.activeContext.value?.transport !== transport) {
                // Leaving the lobby: stop listening, leave the room, forget the dead transport.
                transport.onRawMessageReceived { _, _ -> }
                transport.disconnect()
                if (sessionManager.pendingOnlineTransport === transport) sessionManager.pendingOnlineTransport = null
            }
        }
    }

    // verticalScroll: see NearbyEntryScreen's KDoc comment on the same fix — also
    // ensures the room-code text field stays reachable above the IME instead of being
    // pinned under a keyboard that has nowhere else to push a non-scrolling layout.
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
            hasJoined -> {
                Text(
                    when {
                        joinedAsSpectator -> if (title != null) "Watching $title" else "Watching!"
                        else -> if (title != null) "Joining $title" else "Connected!"
                    },
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (joinedAsSpectator) {
                        "Waiting for the host to start the game — you'll watch, not play."
                    } else {
                        "Waiting for the host to start the game..."
                    }
                )
            }
            else -> {
                Text(
                    if (title != null) "Join an online $title game" else "Join an online game",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = roomCodeInput,
                    onValueChange = { roomCodeInput = it.uppercase().take(ROOM_CODE_LENGTH) },
                    label = { Text("Room code") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    )
                )
                if (connectionError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        connectionError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = roomCodeInput.length == ROOM_CODE_LENGTH,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        onClick = {
                            transport.clearError()
                            joinedAsSpectator = false
                            transport.joinRoom(roomCodeInput, clientPlayerId, guestName)
                        }
                    ) {
                        Text("Join")
                    }
                    // Read-only seat -- OnlineTransport.joinRoom's own spectator param and the
                    // relay's dedicated room.spectators roster (server/index.js) already fully
                    // support this; this button and the spectator-aware LaunchedEffect above are
                    // what actually let a player reach it (audited finding: the option existed
                    // everywhere except this screen).
                    if (gameId == null || gameId in com.gamesuite.core.GameHubSpecs.spectatorGames) OutlinedButton(
                        enabled = roomCodeInput.length == ROOM_CODE_LENGTH,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        onClick = {
                            transport.clearError()
                            joinedAsSpectator = true
                            transport.joinRoom(roomCodeInput, clientPlayerId, "Spectator (${Build.MODEL})", spectator = true)
                        }
                    ) {
                        Text("Watch")
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
