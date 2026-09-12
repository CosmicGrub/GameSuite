import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.transport.LanHostInfo
import com.gamesuite.transport.LanMultiplayerTransport

/**
 * The actual playable demonstration of LanMultiplayerTransport (docs/ENGINE_DECISION.md
 * Action Item 8's "wire the LAN transport into a playable game session" follow-up): a real
 * host/join lobby, backed by real UDP discovery + TCP connection, driving two real
 * TicTacToeGame instances -- the exact same commonMain class TicTacToeScreen.kt uses on
 * Android -- kept in sync purely through TicTacToeGame's own host-authoritative networked
 * play (see TicTacToeNetMessage.kt's KDoc), not any special-cased desktop-only logic.
 *
 * Deliberately a minimal 2-player pilot, not a general N-player lobby: fixed playerIds
 * ("host"/"guest") rather than a real identity/matchmaking system, and no reconnect-after-
 * drop UI (TicTacToeGame's own RequestState/StateSync flow would resync a reconnecting
 * guest's STATE correctly, but this pilot doesn't attempt to resume the actual socket
 * connection after one drops). Real production LAN play for other games is a separate,
 * later, per-game decision -- this exists to prove (and let you actually SEE) that the
 * wiring genuinely works end-to-end, exactly as Action Items 1/3's own desktop pilots
 * proved the KMP port itself.
 */
private sealed class LanLobbyState {
    data object ChooseRole : LanLobbyState()
    data class Hosting(val port: Int) : LanLobbyState()
    data object Discovering : LanLobbyState()
    data object Connecting : LanLobbyState()
    data class Playing(val game: TicTacToeGame, val localPlayerIndex: Int) : LanLobbyState()
    data class Failed(val message: String) : LanLobbyState()
}

@Composable
fun TicTacToeLanDesktopApp(onBack: () -> Unit) {
    val transport = remember { LanMultiplayerTransport() }
    var lobbyState by remember { mutableStateOf<LanLobbyState>(LanLobbyState.ChooseRole) }
    // Disconnects real sockets/coroutine loops the moment this screen goes away, whether via
    // Back or by navigating elsewhere -- the same cleanup discipline LanMultiplayerTransport's
    // own tests give every instance they create in a `finally` block.
    DisposableEffect(Unit) { onDispose { transport.disconnect() } }

    fun startPlaying(localPlayerIndex: Int) {
        val game = TicTacToeGame().apply {
            // No difficulty set: LOCAL_AD_HOC never has a bot seat, so it's never read.
            init(
                GameContext(
                    activeMode = PlayMode.LOCAL_AD_HOC,
                    players = listOf(
                        PlayerInfo(playerId = "host", displayName = "Host", isBot = false),
                        PlayerInfo(playerId = "guest", displayName = "Guest", isBot = false)
                    ),
                    localPlayerIndex = localPlayerIndex,
                    transport = transport
                )
            )
            startMatch()
        }
        lobbyState = LanLobbyState.Playing(game, localPlayerIndex)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Tic-Tac-Toe -- LAN Multiplayer", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when (val state = lobbyState) {
            is LanLobbyState.ChooseRole -> {
                Text("Host a game on this machine, or search for one already running on the same network.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    transport.onHosted { port -> lobbyState = LanLobbyState.Hosting(port) }
                    transport.onPlayerJoined { startPlaying(localPlayerIndex = 0) }
                    transport.hostGame(playerId = "host", displayName = "Host")
                }) { Text("Host a Game") }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    transport.startDiscovery()
                    lobbyState = LanLobbyState.Discovering
                }) { Text("Join a Game") }
            }

            is LanLobbyState.Hosting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Hosting on port ${state.port} -- waiting for another player to join over the same network...")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { transport.disconnect(); lobbyState = LanLobbyState.ChooseRole }) { Text("Cancel") }
            }

            is LanLobbyState.Discovering -> {
                val discovered by transport.discoveredHosts.collectAsState()

                Text("Searching for hosts on the local network...")
                Spacer(Modifier.height(12.dp))
                if (discovered.isEmpty()) {
                    CircularProgressIndicator()
                } else {
                    LazyColumn(modifier = Modifier.size(width = 360.dp, height = 160.dp)) {
                        items(discovered) { host: LanHostInfo ->
                            Button(
                                onClick = {
                                    transport.stopDiscovery()
                                    lobbyState = LanLobbyState.Connecting
                                    transport.onJoinedLobby { startPlaying(localPlayerIndex = 1) }
                                    transport.joinHost(host.address, host.port, playerId = "guest", displayName = "Guest")
                                },
                                modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)
                            ) { Text("${host.displayName} (${host.address}:${host.port})") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { transport.stopDiscovery(); lobbyState = LanLobbyState.ChooseRole }) { Text("Cancel") }
            }

            is LanLobbyState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Connecting...")
            }

            is LanLobbyState.Playing -> {
                LanTicTacToeBoard(state.game, state.localPlayerIndex)
            }

            is LanLobbyState.Failed -> {
                Text("Error: ${state.message}")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { transport.disconnect(); lobbyState = LanLobbyState.ChooseRole }) { Text("Try Again") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Back to Menu") }
    }

    // Surfaces a real connection failure (e.g. a bad address, or the host process having
    // already exited) as LanLobbyState.Failed instead of leaving the lobby silently stuck
    // on "Connecting...".
    val connectionError by transport.connectionError.collectAsState()
    LaunchedEffect(connectionError) {
        val message = connectionError ?: return@LaunchedEffect
        if (lobbyState !is LanLobbyState.Playing) lobbyState = LanLobbyState.Failed(message)
    }
}

@Composable
private fun LanTicTacToeBoard(game: TicTacToeGame, localPlayerIndex: Int) {
    val board by game.board
    val currentPlayer by game.currentPlayer
    val roundOver by game.roundOver
    val winningLine by game.winningLine
    val scoreP1 by game.scoreP1
    val scoreP2 by game.scoreP2
    val matchOver by game.matchOver

    val isMyTurn = currentPlayer - 1 == localPlayerIndex
    val myLabel = if (localPlayerIndex == 0) "Host (X)" else "Guest (O)"

    Text("You are: $myLabel")
    Spacer(Modifier.height(8.dp))
    Text("Score  Host: $scoreP1   Guest: $scoreP2")
    Spacer(Modifier.height(8.dp))
    Text(if (matchOver) "" else if (roundOver) "Round over" else if (isMyTurn) "Your turn" else "Waiting for the other player...")
    Spacer(Modifier.height(16.dp))

    Column {
        for (row in 0 until 3) {
            Row {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    val value = board[index]
                    val label = when (value) {
                        1 -> "X"
                        2 -> "O"
                        else -> ""
                    }
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .padding(4.dp)
                            .background(
                                if (winningLine?.contains(index) == true) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable(
                                enabled = !roundOver && value == 0 && isMyTurn,
                                onClick = { game.cellClicked(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    if (roundOver) {
        Button(onClick = { game.playAgain() }) { Text("Play Again") }
    }
}
