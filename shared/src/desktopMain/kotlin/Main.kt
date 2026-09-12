import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.airhockey.AirHockeyGame
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport

/**
 * The Compose Multiplatform Desktop entry point called for by
 * docs/ENGINE_DECISION.md Action Items 1 and 3 -- "stand up a minimal
 * Compose Multiplatform Desktop window around it [the ported game]. This is
 * the actual test of the ADR's central claim." A small in-window menu picks
 * between the two pilots landed so far (Tic-Tac-Toe, the "it just ports"
 * baseline; Air Hockey, the "harder pilot" with real-time physics and a
 * continuous per-frame loop) rather than each needing its own launch
 * config -- both game rules rendered are the exact same commonMain classes
 * the Android app's own TicTacToeScreen/AirHockeyScreen would drive.
 *
 * Deliberately not shared into commonMain: the ADR's pilot scope is proving
 * the LOGIC layer ports cleanly, not building a second, parallel UI layer.
 * A real shared UI (letting :app's screens and these windows converge on
 * one Composable) is exactly the kind of larger commitment the ADR says to
 * defer until after these pilots' compile-and-run results are in.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "GameSuite Desktop Pilot") {
        App()
    }
}

private enum class PilotScreen { MENU, TIC_TAC_TOE, AIR_HOCKEY }

@Composable
private fun App() {
    var screen by remember { mutableStateOf(PilotScreen.MENU) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                PilotScreen.MENU -> MenuScreen(onSelect = { screen = it })
                PilotScreen.TIC_TAC_TOE -> TicTacToeDesktopApp(onBack = { screen = PilotScreen.MENU })
                PilotScreen.AIR_HOCKEY -> AirHockeyDesktopApp(onBack = { screen = PilotScreen.MENU })
            }
        }
    }
}

@Composable
private fun MenuScreen(onSelect: (PilotScreen) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("GameSuite Kotlin Multiplatform Pilots", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onSelect(PilotScreen.TIC_TAC_TOE) }) { Text("Tic-Tac-Toe (Action Item 1)") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSelect(PilotScreen.AIR_HOCKEY) }) { Text("Air Hockey (Action Item 3)") }
    }
}

@Composable
private fun TicTacToeDesktopApp(onBack: () -> Unit) {
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
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = onBack) { Text("Back to Menu") }
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

/**
 * Minimal Compose Desktop rendering of the ported AirHockeyGame -- deliberately
 * a much smaller slice of AirHockeyScreen's real UI (728 lines on Android, with
 * sound/haptics/camera-shake/particles/shockwave rings, none of which are part
 * of what this pilot needs to prove): a table Canvas, one drag zone for the
 * player's own paddle, and the same real-time frame loop the Android screen
 * uses, driving the identical commonMain AirHockeyGame.tick(dt).
 */
@Composable
private fun AirHockeyDesktopApp(onBack: () -> Unit) {
    val game = remember {
        AirHockeyGame().apply {
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
            startMatch()
        }
    }
    val state by game.state

    // The real-time physics frame loop -- mirrors AirHockeyScreen's own
    // "LaunchedEffect(state.matchOver) { while (...) withFrameNanos { ... game.tick(dt) } }"
    // on Android, using the same withFrameNanos frame clock: a genuine
    // Compose Multiplatform runtime API (part of the compose.runtime
    // artifact already a commonMain dependency), not an Android-specific one.
    LaunchedEffect(state.matchOver) {
        if (state.matchOver) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (!game.state.value.matchOver) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    val dt = (nanos - lastFrameNanos) / 1_000_000_000f
                    game.tick(dt)
                }
                lastFrameNanos = nanos
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("GameSuite KMP Pilot: Air Hockey (You vs HARD bot)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Score  You: ${state.playerScore}   Bot: ${state.cpuScore}   (first to ${AirHockeyGame.WIN_SCORE})")
        Spacer(Modifier.height(16.dp))
        Canvas(
            modifier = Modifier
                .size(420.dp)
                .background(Color(0xFF0B2545))
                // Single drag zone driving the player's own (bottom-half) paddle --
                // AirHockeyScreen's real dual-pointer awaitPointerEventScope loop
                // (for local pass-and-play's second paddle) is deliberately not
                // reproduced here; this pilot only needs to prove the ported
                // vs-bot physics/AI path renders and responds to input live.
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                        game.movePlayerPaddle(nx, ny)
                    }
                }
        ) {
            val w = this.size.width
            val h = this.size.height

            // Center line + faceoff circle, purely cosmetic table markings.
            drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 2f)
            drawCircle(Color.White.copy(alpha = 0.35f), radius = h * 0.12f, center = Offset(w / 2, h / 2), style = Stroke(2f))

            // Goal mouths, top (bot's goal) and bottom (player's goal).
            val goalHalfWidthPx = AirHockeyGame.GOAL_HALF_WIDTH * w
            drawLine(Color(0xFFEF4444), Offset(w / 2 - goalHalfWidthPx, 0f), Offset(w / 2 + goalHalfWidthPx, 0f), strokeWidth = 6f)
            drawLine(Color(0xFF3B82F6), Offset(w / 2 - goalHalfWidthPx, h), Offset(w / 2 + goalHalfWidthPx, h), strokeWidth = 6f)

            // Ball trail (real recent-position/speed history the shared game already
            // tracks in AirHockeyState.ballTrail -- rendered here, not recomputed).
            state.ballTrail.forEachIndexed { i, point ->
                val alpha = (i + 1f) / (state.ballTrail.size + 1f) * 0.5f
                drawCircle(
                    Color.White.copy(alpha = alpha),
                    radius = AirHockeyGame.BALL_RADIUS * w * 0.7f,
                    center = Offset(point.pos.x * w, point.pos.y * h)
                )
            }

            // Paddles: blue = player (bottom half), red = bot (top half).
            drawCircle(Color(0xFF3B82F6), radius = AirHockeyGame.PADDLE_RADIUS * w, center = Offset(state.playerPaddle.x * w, state.playerPaddle.y * h))
            drawCircle(Color(0xFFEF4444), radius = AirHockeyGame.PADDLE_RADIUS * w, center = Offset(state.cpuPaddle.x * w, state.cpuPaddle.y * h))

            // Ball on top of everything else.
            drawCircle(Color.White, radius = AirHockeyGame.BALL_RADIUS * w, center = Offset(state.ballPos.x * w, state.ballPos.y * h))
        }
        Spacer(Modifier.height(16.dp))
        if (state.matchOver) {
            Text(if (state.winnerIsPlayer) "You win the match!" else "Bot wins the match.")
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = onBack) { Text(if (state.matchOver) "Back to Menu" else "Quit to Menu") }
    }
}
