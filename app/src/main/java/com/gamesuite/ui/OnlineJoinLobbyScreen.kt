package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.transport.OnlineLobbyMessage
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
    onBack: () -> Unit
) {
    val transport = sessionManager.pendingOnlineTransport ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val clientPlayerId = remember { "p-" + UUID.randomUUID().toString() }
    var roomCodeInput by remember { mutableStateOf("") }
    var hasJoined by remember { mutableStateOf(false) }
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
        val myIndex = message.players.indexOfFirst { it.playerId == clientPlayerId }
        if (myIndex < 0) return@LaunchedEffect // roster didn't include us — ignore rather than crash
        sessionManager.launchGame(
            mode = PlayMode.ONLINE,
            players = message.players,
            localPlayerIndex = myIndex,
            transport = transport
        )
        onGameStarted(message.gameRoute)
    }

    DisposableEffect(transport) {
        transport.onJoined { _, _ -> hasJoined = true }
        transport.onRawMessageReceived { _, payload ->
            val message = runCatching {
                Json.decodeFromString<OnlineLobbyMessage>(String(payload, Charsets.UTF_8))
            }.getOrNull()
            if (message is OnlineLobbyMessage.GameStart) {
                pendingGameStart = message
            }
        }
        onDispose {
            if (sessionManager.activeContext.value?.transport !== transport) {
                transport.disconnect()
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
            hasJoined -> {
                Text("Connected!", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Waiting for the host to start the game...")
            }
            else -> {
                Text("Join an online game", style = MaterialTheme.typography.headlineSmall)
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
                Button(
                    enabled = roomCodeInput.length == ROOM_CODE_LENGTH,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    onClick = {
                        transport.clearError()
                        transport.joinRoom(roomCodeInput, clientPlayerId, "Guest (${Build.MODEL})")
                    }
                ) {
                    Text("Join")
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
