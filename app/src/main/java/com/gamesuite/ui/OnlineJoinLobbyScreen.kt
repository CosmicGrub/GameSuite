package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val connectionError by transport.connectionError.collectAsState()

    DisposableEffect(transport) {
        transport.onJoined { _, _ -> hasJoined = true }
        transport.onRawMessageReceived { _, payload ->
            val message = runCatching {
                Json.decodeFromString<OnlineLobbyMessage>(String(payload, Charsets.UTF_8))
            }.getOrNull()
            if (message is OnlineLobbyMessage.GameStart) {
                val myIndex = message.players.indexOfFirst { it.playerId == clientPlayerId }
                if (myIndex < 0) return@onRawMessageReceived // roster didn't include us — ignore rather than crash
                sessionManager.launchGame(
                    mode = PlayMode.ONLINE,
                    players = message.players,
                    localPlayerIndex = myIndex,
                    transport = transport
                )
                onGameStarted(message.gameRoute)
            }
        }
        onDispose {
            if (sessionManager.activeContext.value?.transport !== transport) {
                transport.disconnect()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
        Button(onClick = onBack) { Text("Cancel") }
    }
}
