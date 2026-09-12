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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
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
import com.gamesuite.games.checkers.CheckersGame
import com.gamesuite.games.checkers.CheckersPiece
import com.gamesuite.games.checkers.PieceKind
import com.gamesuite.games.chess.ChessGame
import com.gamesuite.games.chess.ChessResult
import com.gamesuite.games.chess.Piece
import com.gamesuite.games.chess.PieceColor
import com.gamesuite.games.chess.PieceType
import com.gamesuite.games.chess.playerIndexForColor
import com.gamesuite.games.mancala.MancalaGame
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport

/**
 * The Compose Multiplatform Desktop entry point called for by
 * docs/ENGINE_DECISION.md Action Items 1, 3, and the follow-up Chess pilot
 * that Action Item 4's persistence untangling unblocked -- "stand up a
 * minimal Compose Multiplatform Desktop window around it [the ported
 * game]. This is the actual test of the ADR's central claim." A small
 * in-window menu picks between the three pilots landed so far (Tic-Tac-Toe,
 * the "it just ports" baseline; Air Hockey, the "harder pilot" with
 * real-time physics and a continuous per-frame loop; Chess, the largest --
 * 1022 lines -- and the one that needed a real preparatory refactor before
 * it could port at all) rather than each needing its own launch config --
 * every game's rules rendered here are the exact same commonMain classes
 * the Android app's own TicTacToeScreen/AirHockeyScreen/ChessScreen would drive.
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

private enum class PilotScreen { MENU, TIC_TAC_TOE, AIR_HOCKEY, CHESS, MANCALA, CHECKERS, TIC_TAC_TOE_LAN }

@Composable
private fun App() {
    var screen by remember { mutableStateOf(PilotScreen.MENU) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                PilotScreen.MENU -> MenuScreen(onSelect = { screen = it })
                PilotScreen.TIC_TAC_TOE -> TicTacToeDesktopApp(onBack = { screen = PilotScreen.MENU })
                PilotScreen.AIR_HOCKEY -> AirHockeyDesktopApp(onBack = { screen = PilotScreen.MENU })
                PilotScreen.CHESS -> ChessDesktopApp(onBack = { screen = PilotScreen.MENU })
                PilotScreen.MANCALA -> MancalaDesktopApp(onBack = { screen = PilotScreen.MENU })
                PilotScreen.CHECKERS -> CheckersDesktopApp(onBack = { screen = PilotScreen.MENU })
                PilotScreen.TIC_TAC_TOE_LAN -> TicTacToeLanDesktopApp(onBack = { screen = PilotScreen.MENU })
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
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSelect(PilotScreen.CHESS) }) { Text("Chess (Action Item 4 pilot)") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSelect(PilotScreen.MANCALA) }) { Text("Mancala") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSelect(PilotScreen.CHECKERS) }) { Text("Checkers") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSelect(PilotScreen.TIC_TAC_TOE_LAN) }) { Text("Tic-Tac-Toe -- LAN Multiplayer (Action Item 8)") }
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
        // A fixed board size (unlike AirHockeyScreen's dynamic BoxWithConstraints-derived
        // boardSizeDp) since this pilot window doesn't need to adapt to arbitrary layouts --
        // the shine-overlay math below uses the same size for both the Canvas and the puck
        // overlay Box, so it stays correct even though it's a constant here.
        val boardSizeDp = 420.dp
        Box {
            Canvas(
                modifier = Modifier
                    .size(boardSizeDp)
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

                // Ball on top of everything else -- this baseline circle is the whole
                // effect if the shader overlay below fails to compile for any reason.
                drawCircle(Color.White, radius = AirHockeyGame.BALL_RADIUS * w, center = Offset(state.ballPos.x * w, state.ballPos.y * h))
            }

            // Desktop shader parity (docs/ENGINE_DECISION.md Action Item 8): the same
            // specular-sweep shine AirHockeyScreen.kt layers on the puck via Android's AGSL
            // RuntimeShader, reimplemented here in real Skia SkSL for Compose Desktop's own
            // Skiko renderer -- see DesktopShaders.kt's own top comment for exactly why the
            // shader source itself had to be re-authored, not copy-pasted, between the two
            // platforms. A pure no-op overlay (nothing drawn) if shader compilation ever
            // fails, matching the Android version's own defensive fallback.
            val puckDiameterDp = boardSizeDp * (AirHockeyGame.BALL_RADIUS * 2f)
            Box(
                modifier = Modifier
                    .offset(
                        x = boardSizeDp * state.ballPos.x - puckDiameterDp / 2f,
                        y = boardSizeDp * state.ballPos.y - puckDiameterDp / 2f
                    )
                    .size(puckDiameterDp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .specularSweep(tint = Color.White.copy(alpha = 0.85f), periodMs = 1400)
            )
        }
        Spacer(Modifier.height(16.dp))
        if (state.matchOver) {
            Text(if (state.winnerIsPlayer) "You win the match!" else "Bot wins the match.")
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = onBack) { Text(if (state.matchOver) "Back to Menu" else "Quit to Menu") }
    }
}

/**
 * Minimal Compose Desktop rendering of the ported ChessGame -- a plain 8x8
 * click-to-select-then-move grid with Unicode piece glyphs, deliberately
 * nowhere near ChessScreen.kt's real board (silhouette-path piece art,
 * check/checkmate hit-stop, captured-piece tray, evaluation bar). Tap a
 * piece of the side to move to see its legal destinations highlighted (via
 * the shared ChessGame.legalDestinationsFor -- the exact same call
 * ChessScreen makes), then tap a highlighted square to play it through the
 * shared ChessGame.playMove. You always play White; the HARD bot (real
 * minimax/alpha-beta search, see ChessGame's own KDoc) plays Black.
 */
@Composable
private fun ChessDesktopApp(onBack: () -> Unit) {
    val game = remember {
        ChessGame().apply {
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
    var selected by remember { mutableStateOf<Int?>(null) }

    // Bot-turn trigger, mirrors ChessScreen's own LaunchedEffect keyed on sideToMove/roundOver
    // (see ChessGame.playBotTurn's KDoc) -- a safe no-op whenever it isn't actually a bot's turn.
    LaunchedEffect(state?.sideToMove, state?.roundOver) {
        game.playBotTurn()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("GameSuite KMP Pilot: Chess (You = White vs HARD bot = Black)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Wins  You: ${game.scoreP1.value}   Bot: ${game.scoreP2.value}   Draws: ${game.draws.value}")
        Spacer(Modifier.height(8.dp))
        val s = state
        if (s == null) {
            Text("Setting up the board...")
        } else {
            Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            ChessBoard(
                board = s.board,
                selected = selected,
                legalDestinations = selected?.let { game.legalDestinationsFor(it) } ?: emptySet(),
                onSquareClick = { square ->
                    val from = selected
                    when {
                        // A destination is already highlighted -- try to play it. playMove()
                        // itself silently no-ops on anything not actually legal, so this can
                        // never desync from the real rules even if this UI's own bookkeeping
                        // were somehow stale.
                        from != null && square in game.legalDestinationsFor(from) -> {
                            game.playMove(playerIndexForColor(s.sideToMove), from, square)
                            selected = null
                        }
                        // Otherwise, selecting is only meaningful for the human's own side
                        // (White) and only when it's actually White's move.
                        s.board.getOrNull(square)?.color == PieceColor.WHITE && s.sideToMove == PieceColor.WHITE ->
                            selected = square
                        else -> selected = null
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
            if (s.roundOver) {
                val outcome = when (s.result) {
                    ChessResult.WHITE_WINS -> "Checkmate -- you win!"
                    ChessResult.BLACK_WINS -> "Checkmate -- bot wins."
                    ChessResult.DRAW_STALEMATE -> "Stalemate -- draw."
                    ChessResult.DRAW_REPETITION -> "Draw by repetition."
                    ChessResult.IN_PROGRESS -> "" // unreachable when roundOver is true
                }
                Text(outcome)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { game.playAgain(); selected = null }) { Text("Play Again") }
                Spacer(Modifier.height(8.dp))
            }
        }
        Button(onClick = onBack) { Text("Back to Menu") }
    }
}

@Composable
private fun ChessBoard(board: List<Piece?>, selected: Int?, legalDestinations: Set<Int>, onSquareClick: (Int) -> Unit) {
    Column {
        // Rank 8 (Black's back rank) at the top, rank 1 (White's) at the bottom -- White's own
        // point of view, matching how this pilot always seats the human as White. square index
        // is rank*8+file (a1=0), the exact same convention ChessGame.kt's own top comment documents.
        for (displayRow in 7 downTo 0) {
            Row {
                for (col in 0..7) {
                    val square = displayRow * 8 + col
                    ChessSquare(
                        piece = board.getOrNull(square),
                        dark = (displayRow + col) % 2 == 0,
                        selected = selected == square,
                        legalDestination = square in legalDestinations,
                        onClick = { onSquareClick(square) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChessSquare(piece: Piece?, dark: Boolean, selected: Boolean, legalDestination: Boolean, onClick: () -> Unit) {
    val base = if (dark) Color(0xFF769656) else Color(0xFFEEEED2)
    val background = when {
        selected -> Color(0xFFF6F669)
        legalDestination -> if (dark) Color(0xFF5E8C4A) else Color(0xFFCFE8A8)
        else -> base
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (piece != null) {
            Text(pieceGlyph(piece), style = MaterialTheme.typography.headlineMedium)
        } else if (legalDestination) {
            // Empty legal-destination square: a small dot, same convention most chess UIs use
            // (a full highlight tile would be indistinguishable from "square just moved from").
            Box(modifier = Modifier.size(12.dp).background(Color.Black.copy(alpha = 0.25f)))
        }
    }
}

/** Standard Unicode chess glyphs (U+2654-265F) -- solid white-outline glyphs for White so both
 *  colors stay readable against either light or dark square backgrounds; a real desktop font
 *  fallback stack covers these on every platform this pilot targets. */
private fun pieceGlyph(piece: Piece): String {
    val whiteGlyphs = mapOf(
        PieceType.KING to "♔", PieceType.QUEEN to "♕", PieceType.ROOK to "♖",
        PieceType.BISHOP to "♗", PieceType.KNIGHT to "♘", PieceType.PAWN to "♙"
    )
    val blackGlyphs = mapOf(
        PieceType.KING to "♚", PieceType.QUEEN to "♛", PieceType.ROOK to "♜",
        PieceType.BISHOP to "♝", PieceType.KNIGHT to "♞", PieceType.PAWN to "♟"
    )
    val glyphs = if (piece.color == PieceColor.WHITE) whiteGlyphs else blackGlyphs
    return glyphs.getValue(piece.type)
}

/**
 * Minimal Compose Desktop rendering of the ported MancalaGame -- pit indices 0-13 per
 * MancalaGame.kt's own top-comment layout (0-5 = player 0's pits, 6 = player 0's store,
 * 7-12 = player 1's pits, 13 = player 1's store). You play player 0 (bottom row, left to
 * right); the HARD bot plays player 1 (top row, rendered right to left so both rows read
 * in the same counter-clockwise sowing direction the real rules use). Tapping a pit calls
 * the identical shared MancalaGame.sow() ChessScreen/MancalaScreen would call.
 */
@Composable
private fun MancalaDesktopApp(onBack: () -> Unit) {
    val game = remember {
        MancalaGame().apply {
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

    // Bot-turn trigger, mirrors the other pilots' LaunchedEffect keyed on whatever
    // signals a new turn -- playBotTurn() is itself a safe no-op when it isn't the bot's turn.
    LaunchedEffect(state?.currentPlayerIndex, state?.roundOver) {
        game.playBotTurn()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("GameSuite KMP Pilot: Mancala (You vs HARD bot)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Wins  You: ${game.scoreP1.value}   Bot: ${game.scoreP2.value}   Draws: ${game.draws.value}")
        Spacer(Modifier.height(8.dp))
        val s = state
        if (s == null) {
            Text("Setting up the board...")
        } else {
            Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            MancalaBoard(
                pits = s.pits,
                captureCandidates = if (s.currentPlayerIndex == 0) game.captureCandidates(0) else emptySet(),
                enabled = s.currentPlayerIndex == 0 && !s.roundOver,
                onPitClick = { pit -> game.sow(0, pit) }
            )
            Spacer(Modifier.height(16.dp))
            if (s.roundOver) {
                val outcome = when {
                    s.winnerPlayerId == "human" -> "You win this round!"
                    s.winnerPlayerId == "bot" -> "Bot wins this round."
                    else -> "Draw."
                }
                Text(outcome)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { game.playAgain() }) { Text("Play Again") }
                Spacer(Modifier.height(8.dp))
            }
        }
        Button(onClick = onBack) { Text("Back to Menu") }
    }
}

@Composable
private fun MancalaBoard(pits: List<Int>, captureCandidates: Set<Int>, enabled: Boolean, onPitClick: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Player 1's row (pits 7-12), rendered right-to-left (12 down to 7) so it reads in
        // the same counter-clockwise direction as the bottom row -- the top of a real board.
        Row {
            for (pit in 12 downTo 7) {
                MancalaPit(count = pits[pit], highlighted = false, enabled = false, onClick = {})
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MancalaStore(count = pits[13], label = "Bot")
            Spacer(Modifier.width(16.dp))
            Column {
                // Player 0's row (pits 0-5), left to right.
                Row {
                    for (pit in 0..5) {
                        MancalaPit(
                            count = pits[pit],
                            highlighted = pit in captureCandidates,
                            enabled = enabled && pits[pit] > 0,
                            onClick = { onPitClick(pit) }
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            MancalaStore(count = pits[6], label = "You")
        }
    }
}

@Composable
private fun MancalaPit(count: Int, highlighted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .padding(4.dp)
            .background(
                if (highlighted) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("$count")
    }
}

@Composable
private fun MancalaStore(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(128.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("$count", style = MaterialTheme.typography.headlineSmall)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Minimal Compose Desktop rendering of the ported CheckersGame -- a plain 8x8 click-to-
 * select-then-move grid with colored-disc pieces, deliberately nowhere near
 * CheckersScreen.kt's real board (procedural wood/felt material, lift/hop/promotion motion,
 * captured-piece tray). Tap a piece of the side to move to see its legal destinations
 * highlighted (via the shared CheckersGame.legalDestinationsFrom -- the exact same call
 * CheckersScreen makes), then tap a highlighted square to play it through the shared
 * CheckersGame.playMove -- mandatory capture and forced multi-jump continuation are already
 * folded into what legalDestinationsFrom offers, so this UI needs no rules knowledge of its
 * own, same as the real screen. You play side 1 (the ruleset's own "moves first" side, see
 * CheckersGame.kt's own top comment); the HARD bot (real minimax/alpha-beta search) plays
 * side 0.
 */
@Composable
private fun CheckersDesktopApp(onBack: () -> Unit) {
    val game = remember {
        CheckersGame().apply {
            difficulty = CpuDifficulty.HARD
            init(
                GameContext(
                    activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                    players = listOf(
                        PlayerInfo(playerId = "bot", displayName = "Bot", isBot = true),
                        PlayerInfo(playerId = "human", displayName = "You", isBot = false)
                    ),
                    localPlayerIndex = 1,
                    transport = LocalPassAndPlayTransport()
                )
            )
            startMatch()
        }
    }
    val state by game.state
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Bot-turn trigger, mirrors ChessDesktopApp's own LaunchedEffect (see
    // CheckersGame.playBotTurn's KDoc) -- a safe no-op whenever it isn't actually the bot's turn.
    LaunchedEffect(state?.currentPlayerIndex, state?.gameOver) {
        game.playBotTurn()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("GameSuite KMP Pilot: Checkers (You = side 1 vs HARD bot = side 0)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Wins  Bot: ${game.scoreP1.value}   You: ${game.scoreP2.value}")
        Spacer(Modifier.height(12.dp))
        val s = state
        if (s == null) {
            Text("Setting up the board...")
        } else {
            CheckersBoard(
                board = s.board,
                selected = selected,
                legalDestinations = selected?.let { (r, c) -> game.legalDestinationsFrom(r, c) } ?: emptySet(),
                onSquareClick = { row, col ->
                    val from = selected
                    when {
                        // A destination is already highlighted -- try to play it. playMove()
                        // itself silently no-ops on anything not actually legal, so this can
                        // never desync from the real rules even if this UI's own bookkeeping
                        // were somehow stale.
                        from != null && (row to col) in game.legalDestinationsFrom(from.first, from.second) -> {
                            game.playMove(1, from.first, from.second, row, col)
                            selected = null
                        }
                        // Otherwise, selecting is only meaningful for the human's own side (1)
                        // and only when it's actually side 1's move -- hasLegalMoveFrom already
                        // folds in mandatory-capture/forced-continuation, so it also correctly
                        // refuses a non-forced piece mid-chain.
                        s.currentPlayerIndex == 1 && game.hasLegalMoveFrom(row, col) -> selected = row to col
                        else -> selected = null
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
            if (s.gameOver) {
                val outcome = if (s.winnerPlayerId == "human") "You win!" else "Bot wins."
                Text(outcome)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { game.playAgain(); selected = null }) { Text("Play Again") }
                Spacer(Modifier.height(8.dp))
            }
        }
        Button(onClick = onBack) { Text("Back to Menu") }
    }
}

@Composable
private fun CheckersBoard(
    board: List<CheckersPiece?>,
    selected: Pair<Int, Int>?,
    legalDestinations: Set<Pair<Int, Int>>,
    onSquareClick: (row: Int, col: Int) -> Unit
) {
    Column {
        for (row in 0..7) {
            Row {
                for (col in 0..7) {
                    CheckersSquare(
                        piece = board.getOrNull(row * 8 + col),
                        dark = (row + col) % 2 == 1,
                        selected = selected == row to col,
                        legalDestination = (row to col) in legalDestinations,
                        onClick = { onSquareClick(row, col) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckersSquare(piece: CheckersPiece?, dark: Boolean, selected: Boolean, legalDestination: Boolean, onClick: () -> Unit) {
    val base = if (dark) Color(0xFF3E2723) else Color(0xFFD7B899)
    val background = when {
        selected -> Color(0xFFF6F669)
        legalDestination -> if (dark) Color(0xFF5E8C4A) else Color(0xFFCFE8A8)
        else -> base
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (piece != null) {
            // Side 0 = warm amber disc (the C++ engine's own "AI" side), side 1 = charcoal
            // disc (its "human" side) -- distinct from Chess's own White/Black palette so the
            // two board-game pilots never look interchangeable at a glance.
            val discColor = if (piece.owner == 0) Color(0xFFD98A34) else Color(0xFF37474F)
            Box(
                modifier = Modifier.size(40.dp).background(discColor, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (piece.kind == PieceKind.KING) {
                    Text("K", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        } else if (legalDestination) {
            Box(modifier = Modifier.size(12.dp).background(Color.Black.copy(alpha = 0.25f)))
        }
    }
}
