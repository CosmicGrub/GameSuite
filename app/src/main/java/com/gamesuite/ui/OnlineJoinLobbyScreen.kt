package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.stringResource
import com.gamesuite.R
import com.gamesuite.transport.LOBBY_FULL_MESSAGE
import com.gamesuite.transport.LOBBY_KICKED_MESSAGE
import com.gamesuite.transport.LOBBY_STARTED_MESSAGE
import com.gamesuite.transport.LobbyJson
import com.gamesuite.transport.SeatView
import com.gamesuite.ui.club.LobbySeatGrid
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
import com.gamesuite.core.GuestStartDecision
import com.gamesuite.core.OnlineMatchStarter
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerNameStore
import com.gamesuite.transport.OnlineLobbyMessage
import com.gamesuite.transport.onlineHelloPayload
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
@OptIn(ExperimentalLayoutApi::class)
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
    // The host's description of the table (seat grid) and whether this guest has told the host it is ready.
    var seats by remember { mutableStateOf<List<SeatView>>(emptyList()) }
    var iAmReady by remember { mutableStateOf(false) }
    val connectionError by transport.connectionError.collectAsState()
    // The host leaving (or dropping) while we wait in the lobby: say so, instead of waiting for a start that cannot come.
    val sessionEnd by transport.sessionEnd.collectAsState()
    val awayPlayers by transport.awayPlayers.collectAsState()
    val hostLeft = sessionEnd != null
    val hostAway = !hostLeft && transport.sessionHostId?.let { it in awayPlayers } == true

    LaunchedEffect(pendingGameStart) {
        val message = pendingGameStart ?: return@LaunchedEffect
        // A room playing a game we did not open, or one this build can't play over a network: say so, don't start.
        // localSeatIndex inside: -1 for a spectator (legitimately absent from the roster), the seat for a validly
        // seated player, and Ignore for a non-spectator genuinely missing or not validly seated (a real bug, not this).
        when (val decision = OnlineMatchStarter.guestDecision(message, gameId, clientPlayerId, joinedAsSpectator)) {
            is GuestStartDecision.Refused -> refusal = decision.reason
            GuestStartDecision.Ignore -> Unit // roster didn't (validly) include us: ignore rather than crash
            is GuestStartDecision.Launch -> {
                sessionManager.launchGame(
                    mode = PlayMode.ONLINE,
                    players = decision.message.players,
                    // -1 for a spectator -- GameSessionManager.localOutcomeFor and every GameModule's
                    // own humanIndex() already treat that as "no seat, watch only".
                    localPlayerIndex = decision.localPlayerIndex,
                    transport = transport
                )
                onGameStarted(decision.message.gameRoute)
            }
        }
    }

    DisposableEffect(transport) {
        // Say hello to the host: the relay never tells it about a spectator, and a host whose game is already
        // running re-sends GameStart (or refuses a late seat) only to someone who asks. Before Start the host
        // ignores it and its Start broadcast reaches us as usual. Also after a reconnect, in case Start was missed.
        val sayHello = {
            transport.hostPlayerId?.let { host -> transport.sendRaw(host, onlineHelloPayload(joinedAsSpectator)) }
            Unit
        }
        transport.onJoined { _, _ ->
            hasJoined = true
            sayHello()
        }
        transport.onReconnected { sayHello() }
        // Any room member can relay a GameStart-shaped message; only the host's is real (and only the host
        // can turn a guest away: the room holds more guests than the game has seats).
        transport.onRawMessageReceived(
            OnlineMatchStarter.guestListener(
                hostPlayerId = { transport.hostPlayerId },
                onGameStart = { pendingGameStart = it },
                onRejected = { refusal = it },
                onRoster = { seats = it }
            )
        )
        onDispose {
            if (sessionManager.activeContext.value?.transport !== transport) {
                // Leaving the lobby: stop listening, leave the room, forget the dead transport.
                transport.onRawMessageReceived { _, _ -> }
                transport.disconnect()
                if (sessionManager.pendingOnlineTransport === transport) sessionManager.pendingOnlineTransport = null
            }
        }
    }

    // The table scrolls (and stays above the IME); Ready / Join / Cancel are pinned so they are always reachable.
    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                refusal != null -> {
                    Text(stringResource(R.string.ls_cant_join), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(localizedRefusal(refusal ?: ""), color = MaterialTheme.colorScheme.error)
                }
                hasJoined -> {
                    Text(
                        when {
                            joinedAsSpectator -> if (title != null) stringResource(R.string.ls_watching_game, title) else stringResource(R.string.ls_watching)
                            else -> if (title != null) stringResource(R.string.ls_joining_game, title) else stringResource(R.string.ls_connected)
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    if (hostLeft) {
                        Text(stringResource(R.string.sh_host_left_title), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.sh_host_left_body), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            stringResource(if (joinedAsSpectator) R.string.ls_waiting_host_spectator else R.string.ls_waiting_host),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (hostAway) Text(stringResource(R.string.sh_host_away), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (seats.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        LobbySeatGrid(seats = seats, localPlayerId = clientPlayerId)
                    }
                }
                else -> {
                    Text(
                        if (title != null) stringResource(R.string.ls_join_title_game, title) else stringResource(R.string.ls_join_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = roomCodeInput,
                        onValueChange = { roomCodeInput = it.uppercase().take(ROOM_CODE_LENGTH) },
                        label = { Text(stringResource(R.string.ls_room_code_field)) },
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
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            ) { Text(stringResource(R.string.ls_cancel)) }
            when {
                refusal != null -> Unit
                hasJoined && !joinedAsSpectator -> {
                    // Tell the host whether this player is ready; Start waits for everyone (see OnlineHostLobbyState).
                    Button(
                        enabled = !hostLeft,
                        modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                        onClick = {
                            iAmReady = !iAmReady
                            transport.hostPlayerId?.let { host ->
                                val ready: OnlineLobbyMessage = OnlineLobbyMessage.Ready(iAmReady)
                                transport.sendRaw(host, LobbyJson.encodeToString(OnlineLobbyMessage.serializer(), ready).toByteArray(Charsets.UTF_8))
                            }
                        }
                    ) { Text(stringResource(if (iAmReady) R.string.ls_not_ready_yet else R.string.ls_im_ready)) }
                }
                !hasJoined -> {
                    // Read-only seat for spectator-capable games: OnlineTransport.joinRoom's own spectator param and the
                    // relay's dedicated room.spectators roster fully support it.
                    if (gameId == null || gameId in GameHubSpecs.spectatorGames) OutlinedButton(
                        enabled = roomCodeInput.length == ROOM_CODE_LENGTH,
                        modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                        onClick = {
                            transport.clearError()
                            joinedAsSpectator = true
                            transport.joinRoom(roomCodeInput, clientPlayerId, "Spectator (${Build.MODEL})", spectator = true)
                        }
                    ) { Text(stringResource(R.string.ls_watch)) }
                    Button(
                        enabled = roomCodeInput.length == ROOM_CODE_LENGTH,
                        modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                        onClick = {
                            transport.clearError()
                            joinedAsSpectator = false
                            transport.joinRoom(roomCodeInput, clientPlayerId, guestName)
                        }
                    ) { Text(stringResource(R.string.ls_join)) }
                }
            }
        }
    }
}

/** The host's refusal text, in the viewer's language when it is one of the host's standard refusals. */
@Composable
private fun localizedRefusal(raw: String): String = when (raw) {
    LOBBY_FULL_MESSAGE -> stringResource(R.string.ls_refusal_full)
    LOBBY_STARTED_MESSAGE -> stringResource(R.string.ls_refusal_started)
    LOBBY_KICKED_MESSAGE -> stringResource(R.string.ls_refusal_kicked)
    else -> raw
}
