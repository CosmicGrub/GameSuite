package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gamesuite.R
import com.gamesuite.core.CpuLevel
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.LaunchOptions
import com.gamesuite.core.buildSeatViews
import com.gamesuite.transport.NEARBY_HOST_PLAYER_ID
import com.gamesuite.transport.OnlineCpuFill
import com.gamesuite.transport.cpuSeatsFor
import com.gamesuite.transport.networkedGameIdForRoute
import com.gamesuite.ui.club.LobbySeatGrid
import com.gamesuite.ui.club.SeatGridActions
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
import com.gamesuite.transport.LobbyJson
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
@OptIn(ExperimentalLayoutApi::class)
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
    // Computer seats the host wants beside the guests (see OnlineCpuFill: only games whose engine drives them).
    val gameId = remember(gameRoute) { networkedGameIdForRoute(gameRoute) }
    val cpuFill = remember(gameId) { gameId != null && OnlineCpuFill.isSupported(gameId, HubModeKind.NEARBY) }
    var cpuWanted by remember { mutableStateOf(0) }
    var cpuLevel by remember { mutableStateOf(CpuLevel.MEDIUM) }
    val connectedPeers by transport.connectedPeers.collectAsState()

    DisposableEffect(transport) {
        transport.onRawMessageReceived { endpointId, payload ->
            val message = try {
                LobbyJson.decodeFromString<NearbyLobbyMessage>(String(payload, Charsets.UTF_8))
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
    val cpus = cpuWanted.coerceIn(0, limits.maxCpus(guestCount))
    val cpuName: (Int) -> String = { n -> context.getString(R.string.ls_computer_name, n) }
    val hostLobbyName = remember(context) { PlayerNameStore(context).lobbyName("Host") }
    // Nearby has no ready check: a connected guest is simply there, shown as ready.
    val seatViews = buildSeatViews(
        limits, NEARBY_HOST_PLAYER_ID, hostLobbyName,
        guests = roster.guests.map { it.playerId to it.displayName },
        isReady = { true }, cpuCount = cpus, cpuName = cpuName
    )
    val stillNeeded = limits.seatsStillNeeded(guestCount, cpus)
    val canStart = roster.canStartWithCpus(cpus)

    // The table scrolls; Start and Cancel are pinned so they stay reachable in landscape, on the Fold cover screen and
    // at large font sizes (this lobby can hold the host plus up to nine guests and computers).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ls_hosting_title, gameDisplayName), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    stillNeeded > 0 && cpuFill ->
                        pluralStringResource(R.plurals.ls_status_need_players_or_computers, stillNeeded, stillNeeded)
                    stillNeeded > 0 -> pluralStringResource(R.plurals.ls_status_need_players, stillNeeded, stillNeeded)
                    limits.isFull(guestCount + cpus) -> stringResource(R.string.ls_status_full, limits.maxPlayers)
                    else -> stringResource(R.string.ls_status_ready_to_start)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))

            LobbySeatGrid(
                seats = seatViews,
                localPlayerId = NEARBY_HOST_PLAYER_ID,
                actions = SeatGridActions(
                    onAddComputer = if (cpuFill) ({ cpuWanted = (cpus + 1).coerceAtMost(limits.maxCpus(guestCount)) }) else null,
                    onRemoveComputer = if (cpuFill) ({ cpuWanted = (cpus - 1).coerceAtLeast(0) }) else null,
                    // No remove-guest on Nearby: dropping an endpoint cannot be exercised on an emulator, and a guest
                    // who wants out simply leaves. See docs/roadmap/WAVE-5-MULTIPLAYER-DESIGN.md (S4 results).
                    onRemovePlayer = null
                )
            )

            if (cpuFill) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = limits.maxCpus(guestCount) > cpus,
                        modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                        onClick = { cpuWanted = limits.maxCpus(guestCount) }
                    ) { Text(stringResource(R.string.ls_fill_computers)) }
                    if (cpus > 0) {
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                            onClick = { cpuWanted = 0 }
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
            Spacer(Modifier.height(8.dp))
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
            Button(
                enabled = canStart,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                onClick = {
                    if (!roster.canStartWithCpus(cpus)) return@Button
                    // launch() freezes the roster (a JoinRequest that lands after this can't add a seat) and
                    // decides the player list, the mapping to install and each guest's GameStart together, so
                    // what is installed is exactly what is announced. This screen only applies the plan.
                    val hostName = PlayerNameStore(context).lobbyName("Host")
                    val cpuSeats = cpuSeatsFor(cpus, cpuName)
                    val launch = roster.launch(gameRoute, hostDisplayName = hostName, gameDisplayName = gameDisplayName, cpuSeats = cpuSeats)
                    roster = launch.roster
                    // launch.players: the host (seat 0, id "host", the remembered name), guests in join order, then computers.
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
                        transport = transport,
                        options = if (cpuSeats.isEmpty()) LaunchOptions() else cpuLevel.applyTo(LaunchOptions())
                    )
                    onGameStarted(gameRoute)
                }
            ) {
                Text(
                    if (cpus > 0) stringResource(R.string.ls_start_with_computers, 1 + guestCount, cpus)
                    else stringResource(R.string.ls_start, 1 + guestCount)
                )
            }
        }
    }
}
