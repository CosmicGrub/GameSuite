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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport

/**
 * The Compose Multiplatform Desktop entry point called for by
 * docs/ENGINE_DECISION.md Action Item 1 -- "stand up a minimal Compose
 * Multiplatform Desktop window around it [the ported game]. This is the
 * actual test of the ADR's central claim." Everything below is genuinely
 * desktop-only wiring (a JVM main function, an AWT-backed Window); the game
 * rules it renders are the exact same commonMain TicTacToeGame the Android
 * app's own TicTacToeScreen would drive, unmodified for this target.
 *
 * Deliberately not shared into commonMain: the ADR's pilot scope is proving
 * the LOGIC layer ports cleanly, not building a second, parallel UI layer.
 * A real shared UI (letting :app's TicTacToeScreen and this window converge
 * on one Composable) is exactly the kind of larger commitment the ADR says
 * to defer until after this pilot's compile-and-run result is in.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "GameSuite Desktop Pilot — Tic-Tac-Toe") {
        TicTacToeDesktopApp()
    }
}

@Composable
private fun TicTacToeDesktopApp() {
    // Single-player-vs-bot, HARD difficulty (perfect minimax -- see
    // TicTacToeGame's own KDoc on chooseBotMove) so this pilot window
    // exercises the game's most interesting logic path, not just board
    // rendering. remember{} runs the init() call exactly once, on first
    // composition, matching how TicTacToeScreen's own LaunchedEffect(Unit)
    // calls init() a single time per match on Android.
    val game = remember {
        TicTacToeGame().apply {
            difficulty = CpuDifficulty.HARD
            init(
                GameContext(
                    activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "human", displayName = "You", isBot = false),
                        PlayerInfo(playerId = "bot", displayName = "Bot", isBot = true)
                    ),
                    localPlayerIndex = 0,
                    transport = LocalPassAndPlayTransport()
                )
            )
        }
    }

    // Mirrors TicTacToeScreen's own LaunchedEffect keyed on currentPlayer
    // (see TicTacToeGame.playBotTurn's KDoc) -- fires whenever it becomes a
    // new player's turn or a new round starts, and playBotTurn() is itself
    // a safe no-op whenever the player on the move isn't actually a bot.
    LaunchedEffect(game.currentPlayer.value, game.roundOver.value) {
        game.playBotTurn()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("GameSuite KMP Pilot: Tic-Tac-Toe (You vs HARD bot)", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Score  You: ${game.scoreP1.value}   Bot: ${game.scoreP2.value}   Draws: ${game.draws.value}")
                Spacer(Modifier.height(16.dp))
                Board(game)
                Spacer(Modifier.height(16.dp))
                if (game.roundOver.value) {
                    val outcome = when {
                        game.winningLine.value == null -> "Draw."
                        game.scoreP1.value > game.scoreP2.value -> "You win this round!"
                        else -> "Bot wins this round."
                    }
                    Text(outcome)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { game.playAgain() }) { Text("Play Again") }
                }
            }
        }
    }
}

@Composable
private fun Board(game: TicTacToeGame) {
    Column {
        for (row in 0 until 3) {
            Row {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    Cell(
                        value = game.board.value[index],
                        highlighted = game.winningLine.value?.contains(index) == true,
                        enabled = !game.roundOver.value &&
                            game.board.value[index] == 0 &&
                            game.currentPlayer.value == 1,
                        onClick = { game.cellClicked(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(value: Int, highlighted: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
                if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.headlineMedium)
    }
}
