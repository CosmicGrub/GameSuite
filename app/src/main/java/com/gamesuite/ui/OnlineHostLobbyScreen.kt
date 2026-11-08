package com.gamesuite.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.core.CpuLevel
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.LaunchOptions
import com.gamesuite.core.OnlineHostLobbyState
import com.gamesuite.core.OnlineMatchStarter
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerNameStore
import com.gamesuite.transport.LOBBY_KICKED_MESSAGE
import com.gamesuite.transport.LobbyJson
import com.gamesuite.transport.LobbySeatLimits
import com.gamesuite.transport.OnlineCpuFill
import com.gamesuite.transport.OnlineLobbyMessage
import com.gamesuite.transport.cpuSeatsFor
import com.gamesuite.transport.networkedGameIdForRoute
import com.gamesuite.ui.club.LobbySeatGrid
import com.gamesuite.ui.club.SeatGridActions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Opens a room on the relay server and shows the room code prominently, then lets the host build the table:
 * a seat grid (host, guests as they arrive, computer seats, empty seats), "fill with computers" for games whose engine
 * drives computer seats online (see [OnlineCpuFill]), a ready check, and removing a guest. All of the decisions live in
 * [OnlineHostLobbyState] and [OnlineMatchStarter]; this screen renders them and applies the results.
 *
 * [gameRoute]/[gameDisplayName] parameterize this for any ONLINE-capable game, same as Nearby's lobby.
 *
 * Layout: the table scrolls, the primary actions (Start / Cancel) are pinned to the bottom so they stay reachable in
 * landscape, on the Fold cover screen and at large font sizes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnlineHostLobbyScreen(
    sessionManager: GameSessionManager,
    gameRoute: String,
    gameDisplayName: String,
    onGameStarted: (route: String) -> Unit,
    onBack: () -> Unit,
    minPlayers: Int = 2,
    maxPlayers: Int = 10
) {
    val transport = sessionManager.pendingOnlineTransport ?: run {
        // Defensive: got here without a transport (e.g. process death mid-lobby) — bounce back.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val limits = remember(minPlayers, maxPlayers) { LobbySeatLimits.of(minPlayers, maxPlayers) }
    val gameId = remember(gameRoute) { networkedGameIdForRoute(gameRoute) }
    val cpuFill = remember(gameId) { gameId != null && OnlineCpuFill.isSupported(gameId, HubModeKind.ONLINE) }
    val context = LocalContext.current
    val nameStore = remember(context) { PlayerNameStore(context) }
    val hostPlayerId = remember { "p-" + UUID.randomUUID().toString() }
    val hostName = remember(nameStore) { nameStore.lobbyName("Host (${Build.MODEL})") }
    var roomCode by remember { mutableStateOf<String?>(null) }
    val connectedPlayers by transport.connectedPlayers.collectAsState()
    val connectionError by transport.connectionError.collectAsState()

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var lobby by remember { mutableStateOf(OnlineHostLobbyState(limits, changedAtMs = SystemClock.elapsedRealtime())) }
    var cpuLevel by remember { mutableStateOf(CpuLevel.MEDIUM) }

    // Guests' Ready arrives on OkHttp's thread; applying it is a read-modify-write of [lobby], so it is queued and
    // applied on the main thread, against the table as it is then (and only for a seated guest's relay-stamped id).
    val readyEvents = remember { Channel<Pair<String, Boolean>>(Channel.UNLIMITED) }

    DisposableEffect(transport) {
        transport.onHosted { code -> roomCode = code }
        transport.onRawMessageReceived { from, bytes ->
            if (bytes.size > 512) return@onRawMessageReceived // game traffic, not a lobby message
            val message = runCatching {
                LobbyJson.decodeFromString(OnlineLobbyMessage.serializer(), String(bytes, Charsets.UTF_8))
            }.getOrNull()
            if (message is OnlineLobbyMessage.Ready) readyEvents.trySend(from to message.ready)
        }
        transport.hostRoom(hostPlayerId, hostName)
        onDispose {
            // Only tear down if the game never actually launched — once launchGame() has
            // captured this transport inside GameContext, its lifecycle belongs to the
            // active match, not this screen.
            if (sessionManager.activeContext.value?.transport !== transport) {
                // Leaving the lobby: stop listening, close the room's socket, forget the dead transport.
                transport.onRawMessageReceived { _, _ -> }
                transport.disconnect()
                if (sessionManager.pendingOnlineTransport === transport) sessionManager.pendingOnlineTransport = null
            }
        }
    }

    // The relay seats up to 8 per room whatever the game is, so THIS host enforces the game's seat count: the
    // first (maxPlayers - 1) guests in join order are seated, later ones are told the room is full and leave.
    val seating = lobby.seating(connectedPlayers)
    val seated = seating.seated
    val cpus = lobby.cpus(seated.size)
    val turnedAway = remember { mutableSetOf<String>() }
    LaunchedEffect(seating.overflow) {
        OnlineMatchStarter.rejectOverflow(transport, seating.overflow, turnedAway)
    }

    // A ticking clock for the "start anyway" delay, and the table noting who is seated (restarts that delay).
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(seated.map { it.playerId }) { lobby = lobby.observe(seated, SystemClock.elapsedRealtime()) }

    val currentSeated by rememberUpdatedState(seated)
    LaunchedEffect(readyEvents) {
        for ((from, isReady) in readyEvents) lobby = lobby.withReady(from, isReady, currentSeated)
    }

    val cpuName: (Int) -> String = { n -> context.getString(R.string.ls_computer_name, n) }
    val seatViews = lobby.seatViews(hostPlayerId, hostName, seated, cpuName)

    // Tell every guest what the table looks like whenever it changes.
    LaunchedEffect(seatViews, roomCode) {
        if (roomCode == null) return@LaunchedEffect
        val update: OnlineLobbyMessage = OnlineLobbyMessage.RosterUpdate(seatViews)
        transport.sendRaw(null, LobbyJson.encodeToString(OnlineLobbyMessage.serializer(), update).toByteArray(Charsets.UTF_8))
    }

    val stillNeeded = limits.seatsStillNeeded(seated.size, cpus)
    val allReady = lobby.allReady(seated)
    // A dead connection (the error replaces the table) can start nothing: Start stays off until the host cancels and re-opens.
    val connected = connectionError == null
    val startAnyway = connected && !allReady && lobby.startAnywayAvailable(now)
    val canStart = connected && roomCode != null && lobby.canStartNow(seated, now)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ls_hosting_title, gameDisplayName), style = MaterialTheme.typography.headlineSmall)
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
                    Text(stringResource(R.string.ls_connecting), style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    val code = roomCode ?: ""
                    val codeDescription = stringResource(R.string.ls_room_code_cd, code.toCharArray().joinToString(" "))
                    Text(stringResource(R.string.ls_room_code), style = MaterialTheme.typography.labelLarge)
                    Text(
                        code,
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.semantics { contentDescription = codeDescription }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.ls_share_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            stillNeeded > 0 && cpuFill ->
                                pluralStringResource(R.plurals.ls_status_need_players_or_computers, stillNeeded, stillNeeded)
                            stillNeeded > 0 -> pluralStringResource(R.plurals.ls_status_need_players, stillNeeded, stillNeeded)
                            startAnyway -> stringResource(R.string.ls_status_start_anyway_hint)
                            !allReady -> stringResource(R.string.ls_status_waiting_ready)
                            limits.isFull(seated.size + cpus) -> stringResource(R.string.ls_status_full, limits.maxPlayers)
                            else -> stringResource(R.string.ls_status_ready_to_start)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))

                    LobbySeatGrid(
                        seats = seatViews,
                        localPlayerId = hostPlayerId,
                        actions = SeatGridActions(
                            onAddComputer = if (cpuFill) ({ lobby = lobby.withCpuAdded(seated.size) }) else null,
                            onRemoveComputer = if (cpuFill) ({ lobby = lobby.withCpuRemoved(seated.size) }) else null,
                            onRemovePlayer = { id ->
                                lobby = lobby.withKicked(id)
                                val rejected: OnlineLobbyMessage = OnlineLobbyMessage.Rejected(LOBBY_KICKED_MESSAGE)
                                transport.sendRaw(
                                    id,
                                    LobbyJson.encodeToString(OnlineLobbyMessage.serializer(), rejected).toByteArray(Charsets.UTF_8)
                                )
                            }
                        )
                    )

                    if (cpuFill) {
                        Spacer(Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                enabled = limits.maxCpus(seated.size) > cpus,
                                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                                onClick = { lobby = lobby.withAllFilled(seated.size) }
                            ) { Text(stringResource(R.string.ls_fill_computers)) }
                            if (cpus > 0) {
                                OutlinedButton(
                                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                                    onClick = { lobby = lobby.withNoCpus() }
                                ) { Text(stringResource(R.string.ls_clear_computers)) }
                            }
                        }
                        if (cpus > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.club_cpu_title), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CpuLevel.values().filter { it != CpuLevel.EXPERT }.forEach { level ->
                                    FilterChip(
                                        selected = level == cpuLevel,
                                        onClick = { cpuLevel = level },
                                        label = {
                                            Text(
                                                stringResource(
                                                    when (level) {
                                                        CpuLevel.EASY -> R.string.club_cpu_easy
                                                        CpuLevel.MEDIUM -> R.string.club_cpu_medium
                                                        CpuLevel.HARD -> R.string.club_cpu_hard
                                                        CpuLevel.EXPERT -> R.string.club_cpu_expert
                                                    }
                                                )
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Pinned: always reachable, whatever the table's height or the font size.
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
            Button(
                enabled = canStart,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                onClick = {
                    val cpuSeats = cpuSeatsFor(cpus, cpuName)
                    // Host = seat 0 (localPlayerIndex 0); seated guests follow in join order, then computer seats.
                    // Anyone beyond the game's seat count was already turned away and is not in the announced roster.
                    OnlineMatchStarter.startAsHost(
                        channel = transport,
                        hostPlayerId = hostPlayerId,
                        hostName = nameStore.lobbyName("Host"),
                        connected = connectedPlayers.filterNot { it.playerId in lobby.kicked },
                        limits = limits,
                        gameRoute = gameRoute,
                        gameDisplayName = gameDisplayName,
                        cpuSeats = cpuSeats,
                    ) { players ->
                        sessionManager.launchGame(
                            mode = PlayMode.ONLINE,
                            players = players,
                            localPlayerIndex = 0,
                            transport = transport,
                            options = if (cpuSeats.isEmpty()) LaunchOptions() else cpuLevel.applyTo(LaunchOptions())
                        )
                        onGameStarted(gameRoute)
                    }
                }
            ) {
                Text(
                    when {
                        startAnyway -> stringResource(R.string.ls_start_anyway)
                        cpus > 0 -> stringResource(R.string.ls_start_with_computers, 1 + seated.size, cpus)
                        else -> stringResource(R.string.ls_start, 1 + seated.size)
                    }
                )
            }
        }
    }
}
