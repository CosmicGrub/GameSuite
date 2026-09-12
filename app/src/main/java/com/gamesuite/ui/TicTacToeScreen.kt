package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Renders TicTacToeGame's state reactively. All game rules live in
 * TicTacToeGame itself (a GameModule) — this composable only draws the
 * board and forwards taps to it. Fidelity pass: each mark scales in when
 * placed, with a tap sound + haptic — the same "reusable feedback" pattern
 * as the card engine's playPlace(), applied to a board game instead.
 *
 * Research pass (README item 9a) added: the winning line highlights instead
 * of the match silently ending, and a round-over panel with a running
 * session score + Play Again — previously a win/draw kicked the player
 * straight back to the main menu with no chance to see the result or play
 * another round. The CPU's difficulty is pre-set from Settings' own
 * "Default CPU difficulty" (`settingsViewModel`) — the first game in this
 * suite to actually honor that setting rather than just storing it.
 *
 * Variants pass (README item 13s) added [misere]: TicTacToeGame has no
 * per-launch config channel besides GameContext, so a separate
 * "tic-tac-toe-misere" route reuses this exact composable with the flag set
 * to true — see MainActivity's NavHost. Defaults to false so the existing
 * "tic-tac-toe" route (both pass-and-play and vs-CPU) is unaffected.
 *
 * The same pass added [wild] alongside it, threaded through exactly the
 * same way (a param defaulting to false, forwarded to the game module
 * before startMatch(), presumably wired up behind its own route the same
 * way misere is). The only rendering change it needs beyond the indicator
 * text is the X/O toggle just above the board — see the `wild` branch below
 * — since [TicTacToeGame.cellClicked] reads the symbol to place from
 * whatever that toggle last set.
 *
 * PREMIUM-2026-VISION PASS ADDITIONS (kept deliberately minimal per that
 * pitch's own call-out — this game's gap was never as wide as, say,
 * Checkers' flat-Color board):
 *  - Placement is a real 3-keyframe "stamp" hit-stop (snap down, overshoot,
 *    settle — see `stampScale` below) instead of a plain scaleIn spring,
 *    paired with a radial "ink ring" drawn on the same Canvas already
 *    hosting the winning line (see `lastTap`/`inkRingProgress`).
 *  - Haptics now go through [com.gamesuite.haptics.rememberHaptics]'s real
 *    vocabulary instead of one generic `HapticFeedbackType.LongPress` for
 *    every placement — LIGHT_TICK for an ordinary placement, STRONG_ACTION
 *    for the line-completing one (win OR a misere self-loss — either way
 *    it's the "something just concluded" moment), NORMAL_ACTION for a draw.
 *  - Play Again no longer swaps to a fresh board in the same frame the
 *    button is tapped: the old marks scale-out+fade in a stagger ordered
 *    outward from the winning line's center cell (or the board's true
 *    center on a draw — see `clearOrder`), THEN the engine actually resets,
 *    THEN the fresh (empty) grid plays its own center-out staggered
 *    entrance (see `handlePlayAgain`/the `roundNumber`-keyed
 *    [LaunchedEffect]). The round-over panel itself is wrapped in
 *    [AnimatedVisibility] (scale+fade from 0.9) rather than popping in/out
 *    instantly.
 *  - Deliberately NOT added: a motion-intensity tier, an idle/attract
 *    loop, table-material identity, or any shader — none of those fit this
 *    particular game, per the pitch's own reasoning.
 *
 * TAB S9 INPUT PASS (DEVICE_SPECIFIC_PLAN.md §4c) additions: every tappable
 * board cell and the wild-mode/round-over control buttons now also carry
 * `Modifier.pointerHoverIcon(PointerIcon.Hand)`, so a mouse or the Tab S9
 * trackpad shows a hand cursor over them (DeX windowed mode, keyboard-cover
 * scenario) — zero effect on touch, purely additive. This is an in-game
 * board per §4c's own scoping, so no keyboard-focus/Tab-traversal work was
 * added here.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun TicTacToeScreen(
    sessionManager: GameSessionManager,
    settingsViewModel: SettingsViewModel,
    misere: Boolean = false,
    wild: Boolean = false,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val game = remember { TicTacToeGame() }
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val reducedMotion = LocalReducedMotion.current
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Audio infra wiring (audio pass): ambient music respects BOTH the
    // ambient-music setting AND the master sound toggle, same AND pattern
    // every other feature-specific gate in this codebase already follows.
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.TIC_TAC_TOE, enabled = musicEnabled)
    val playSfx = rememberProceduralSfx()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.misere = misere
        game.wild = wild
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
    }

    val board = game.board.value
    val currentPlayer = game.currentPlayer.value
    val roundOver = game.roundOver.value
    val winningLine = game.winningLine.value
    val scoreP1 = game.scoreP1.value
    val scoreP2 = game.scoreP2.value
    val draws = game.draws.value
    val selectedSymbol = game.selectedSymbol.value
    val roundNumber = game.roundNumber.value

    // Winning-line draw-on animation (animation/physics pitch): the three
    // winning cells used to just swap background color the instant the round
    // ended, with no connecting visual. This grows a line from the first
    // winning cell's center to the last winning cell's center (WIN_LINES'
    // first/last entries are always the two endpoints of the line, the
    // middle one its center cell) over ~220ms, snapping under reduced
    // motion. Cell highlighting itself is untouched — this is additive.
    val winningLineProgress = remember { Animatable(0f) }
    LaunchedEffect(winningLine) {
        if (winningLine != null) {
            winningLineProgress.snapTo(0f)
            winningLineProgress.animateTo(1f, animationSpec = if (reducedMotion) snap() else tween(220))
        } else {
            winningLineProgress.snapTo(0f)
        }
    }

    // Round-end sting (audio pass, real SFX gap): a completed line previously had
    // no sound of its own beyond the coincidental sounds.playTap() a human's own
    // winning tap already makes below — and when the BOT'S move completes the
    // line, nothing at all plays, since sounds.playTap() only ever fires from the
    // human clickable. Fires once per completed line regardless of who/what moved;
    // misere inverts the stakes (completing a line loses), so it gets the buzz
    // instead of the chime. A draw stays quiet on purpose — the round-over panel's
    // text already reads clearly and a draw isn't a distinct "event" the way a
    // completed line is.
    LaunchedEffect(roundOver, winningLine) {
        if (!roundOver || winningLine == null) return@LaunchedEffect
        playSfx(if (misere) SfxKind.INVALID_BUZZ else SfxKind.SUCCESS_CHIME)
    }

    // Board-clear / fresh-grid entrance (premium-2026-vision pass) — one
    // Animatable per cell for each half of the transition: cellExitScale
    // (driven by handlePlayAgain below) shrinks+fades the OLD marks out in a
    // stagger; cellEntranceScale (driven by this LaunchedEffect, keyed on
    // TicTacToeGame's own roundNumber so it fires exactly once per fresh
    // board) pops the empty grid back in, also staggered, once the engine
    // has actually reset. Both default to 1f (fully visible, no-op) so a
    // cell never renders wrong before either effect has run.
    val cellExitScale = remember { List(9) { Animatable(1f) } }
    val cellEntranceScale = remember { List(9) { Animatable(1f) } }
    LaunchedEffect(roundNumber) {
        if (roundNumber <= 1 || reducedMotion) {
            cellEntranceScale.forEach { it.snapTo(1f) }
        } else {
            cellEntranceScale.forEach { it.snapTo(0f) }
            val order = (0..8).sortedBy { gridDistanceFromCenter(it) }
            order.forEachIndexed { i, cellIndex ->
                launch {
                    delay(i * TTT_STAGGER_MS)
                    cellEntranceScale[cellIndex].animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
            }
        }
    }

    var isClearingRound by remember { mutableStateOf(false) }

    /** Play Again's real handler: stagger the OLD marks out (ordered from the
     *  winning line's center outward, or the board's true center on a draw —
     *  see [clearOrder]) before the engine actually resets, instead of
     *  [TicTacToeGame.playAgain] wiping the board in the very same frame the
     *  button is tapped. `isClearingRound` also drives the round-over panel's
     *  own [AnimatedVisibility] exit (see the call site) so the panel and the
     *  old marks dismiss together. */
    fun handlePlayAgain() {
        if (isClearingRound) return
        if (reducedMotion) {
            game.playAgain()
            return
        }
        val order = clearOrder(board, winningLine)
        if (order.isEmpty()) {
            game.playAgain()
            return
        }
        isClearingRound = true
        scope.launch {
            val jobs = order.mapIndexed { i, cellIndex ->
                launch {
                    delay(i * TTT_STAGGER_MS)
                    cellExitScale[cellIndex].animateTo(0f, animationSpec = tween(160))
                }
            }
            jobs.joinAll()
            game.playAgain()
            cellExitScale.forEach { it.snapTo(1f) }
            isClearingRound = false
        }
    }

    // Drive the bot's turn automatically, mirroring MancalaScreen's pattern:
    // once it becomes a bot player's turn, wait a beat and let it move itself.
    LaunchedEffect(currentPlayer, roundOver) {
        val ctx = context ?: return@LaunchedEffect
        if (roundOver) return@LaunchedEffect
        if (ctx.players.getOrNull(currentPlayer - 1)?.isBot == true) {
            delay(500)
            game.playBotTurn()
            // Real SFX gap: the bot's own placement was completely silent before —
            // sounds.playTap() only ever fires from the human clickable below. A
            // light tick gives the CPU's move the same "something happened"
            // acknowledgment a human's tap already gets, without competing with it.
            playSfx(SfxKind.LIGHT_TICK)
        }
    }

    val isBotTurn = context?.players?.getOrNull(currentPlayer - 1)?.isBot == true
    val vsCpu = context?.players?.any { it.isBot } == true
    val p1Name = context?.players?.getOrNull(0)?.displayName ?: "Player 1"
    val p2Name = context?.players?.getOrNull(1)?.displayName ?: "Player 2"

    // Last human tap, for the ink-ring effect below — `token` (not just the
    // cell index) so retapping the SAME cell index in a later round still
    // retriggers the LaunchedEffect keyed on it (a plain index key wouldn't
    // change if the same cell happens to be tapped again next round).
    var tapCounter by remember { mutableStateOf(0L) }
    var lastTap by remember { mutableStateOf<InkTap?>(null) }
    val inkRingProgress = remember { Animatable(1f) }
    LaunchedEffect(lastTap) {
        if (lastTap == null) return@LaunchedEffect
        if (reducedMotion) {
            inkRingProgress.snapTo(1f)
        } else {
            inkRingProgress.snapTo(0f)
            inkRingProgress.animateTo(1f, animationSpec = tween(180))
        }
    }

    // Primary-only (no secondary/hand content in this game). BoxWithConstraints
    // gives real, bounded width/height for the board math below — the actual
    // CRITICAL SIZING PRINCIPLE bug this screen had: the board used to be a
    // hardcoded Modifier.size(300.dp), sized from neither dimension of the
    // real window. That was invisible in ordinary portrait use (plenty of
    // height there) but on the Fold 5 cover screen rotated to landscape
    // (~344dp tall) the LazyVerticalGrid below — userScrollEnabled = false —
    // simply ran out of room and silently clipped its bottom row(s) instead
    // of scrolling, making those cells untappable. Fixed here: the board now
    // sizes itself from min(maxWidth, maxHeight) of its own genuinely bounded
    // box (weight(1f) inside a fillMaxSize Column/Row — see the two branches
    // below — is what actually makes maxHeight real rather than effectively
    // unbounded), with the header chrome reflowing to sit BESIDE the board
    // instead of above it whenever the window is wider than it is tall, so a
    // short/wide window doesn't have to fight the header for vertical space
    // at all. On a Tab S9 or unfolded Fold this still just caps + centers the
    // board instead of it sitting tiny-and-off-center in a huge stretched
    // pane; on everything else it's the same fillMaxSize().padding(24.dp)
    // layout as before.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(24.dp),
        primary = {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWide = maxWidth > maxHeight

                val header: @Composable () -> Unit = {
                    Text(
                        "$p1Name: $scoreP1 · $p2Name: $scoreP2" + if (draws > 0) " · Draws: $draws" else "",
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (vsCpu) {
                        Text(
                            "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (misere) {
                        Text(
                            "Misere mode: completing a line loses!",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (wild) {
                        Text(
                            "Wild mode: choose X or O each turn",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        when {
                            isBotTurn -> "Bot is thinking..."
                            currentPlayer == 1 -> "${possessive(p1Name)} turn"
                            else -> "${possessive(p2Name)} turn"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Wild-only symbol picker: which mark the acting player's next
                    // tap will place (TicTacToeGame.selectedSymbol) — standard
                    // rules fix that to the player's own mark, so there's nothing
                    // to choose. Hidden during the bot's turn/round-over the same
                    // way the board itself is disabled then.
                    if (wild && !isBotTurn && !roundOver) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "X", 2 to "O").forEach { (symbol, label) ->
                                // Wild-mode symbol picker (in-screen control button, §4c) —
                                // mouse/trackpad hover cursor only, additive over touch.
                                if (selectedSymbol == symbol) {
                                    Button(
                                        onClick = { game.chooseSymbol(symbol) },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                    ) { Text("Place $label") }
                                } else {
                                    OutlinedButton(
                                        onClick = { game.chooseSymbol(symbol) },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                    ) { Text("Place $label") }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                val board: @Composable () -> Unit = {
                    // Board sizing from BOTH available width AND height — see this
                    // block's own KDoc above. cellGap matches the original fixed
                    // layout's 4.dp-per-side spacing; minCellSize is the real
                    // touch-target floor (matches CardScale.kt's own floor
                    // pattern) — never shrink a cell below a usable tap size even
                    // if that means the board ends up slightly larger than the
                    // strictly available space on an absurdly cramped window.
                    // boardCap mirrors CheckersScreen's own board cap so a big
                    // tablet/landscape window doesn't stretch this into a
                    // needlessly huge board.
                    val cellGap = 4.dp
                    val boardCap = 480.dp
                    val minCellSize = 48.dp
                    val availableBoardSpace = minOf(maxWidth, maxHeight).coerceAtMost(boardCap)
                    val cellSize = ((availableBoardSpace - cellGap * 6) / 3).coerceAtLeast(minCellSize)
                    val boardSize = cellSize * 3 + cellGap * 6
                    val markFontSize = (cellSize.value * 0.43f).coerceAtLeast(20f).sp

                    Box(contentAlignment = Alignment.Center) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.size(boardSize),
                            userScrollEnabled = false
                        ) {
                            items(9) { index ->
                                val cellValue = board[index]
                                val isWinningCell = winningLine?.contains(index) == true
                                // Screen-reader label: 1-indexed row/column (index is
                                // 0-8 row-major over the 3x3 grid) plus what's there,
                                // since sighted players read the mark directly off
                                // the Text below and the highlight color for a win.
                                val cellContent = when (cellValue) {
                                    1 -> "X"
                                    2 -> "O"
                                    else -> "empty"
                                }
                                val cellDescription = "Row ${index / 3 + 1}, Column ${index % 3 + 1}, $cellContent" +
                                    if (isWinningCell) ", winning line" else ""

                                // "Stamp" placement (premium-2026-vision pass): a real
                                // 3-keyframe hit-stop instead of a plain scaleIn spring
                                // — snap down to 0.85 over ~40ms, overshoot to 1.12,
                                // settle to 1.0. Keyed on cellValue itself so it plays
                                // exactly once per placement and resets cleanly the
                                // instant a fresh board clears this cell back to 0.
                                val stampScale = remember { Animatable(if (cellValue != 0) 1f else 0f) }
                                LaunchedEffect(cellValue) {
                                    when {
                                        cellValue == 0 -> stampScale.snapTo(0f)
                                        reducedMotion -> stampScale.snapTo(1f)
                                        else -> {
                                            stampScale.snapTo(0f)
                                            stampScale.animateTo(0.85f, animationSpec = tween(40))
                                            stampScale.animateTo(1.12f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                                            stampScale.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium))
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .padding(cellGap)
                                        .size(cellSize)
                                        // Board-clear exit + fresh-grid entrance (premium-
                                        // 2026-vision pass) — see cellExitScale/
                                        // cellEntranceScale's own comments above. Both are
                                        // 1f outside a transition, so this is a no-op the
                                        // overwhelming majority of the time.
                                        .graphicsLayer {
                                            val s = cellExitScale[index].value * cellEntranceScale[index].value
                                            scaleX = s
                                            scaleY = s
                                            alpha = s
                                        }
                                        .background(if (isWinningCell) Color(0xFFFFD54F) else Color.LightGray)
                                        // Mouse/trackpad hover cursor (§4c) — tappable board
                                        // cell; no effect on touch input.
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(enabled = cellValue == 0 && !isBotTurn && !roundOver) {
                                            lastTap = InkTap(index, tapCounter)
                                            tapCounter += 1
                                            game.cellClicked(index)
                                            sounds.playTap()
                                            // Haptics via the shared vocabulary (premium-
                                            // 2026-vision pass) — read game.*.value fresh
                                            // here rather than the `roundOver`/`winningLine`
                                            // vals above, which were captured at the START
                                            // of this composition and are stale by now:
                                            // cellClicked() just updated them synchronously.
                                            when {
                                                game.winningLine.value != null -> haptics(HapticSignal.STRONG_ACTION)
                                                game.roundOver.value -> haptics(HapticSignal.NORMAL_ACTION)
                                                else -> haptics(HapticSignal.LIGHT_TICK)
                                            }
                                        }
                                        .semantics { contentDescription = cellDescription },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (cellValue != 0) {
                                        val mark = if (cellValue == 1) "X" else "O"
                                        val color = if (cellValue == 1) Color(0xFF1976D2) else Color(0xFFD32F2F)
                                        Text(
                                            mark,
                                            modifier = Modifier.graphicsLayer {
                                                scaleX = stampScale.value
                                                scaleY = stampScale.value
                                            },
                                            fontSize = markFontSize,
                                            fontWeight = FontWeight.Bold,
                                            color = color
                                        )
                                    }
                                }
                            }
                        }

                        // Draw-on winning line (unchanged) plus the radial "ink ring"
                        // (premium-2026-vision pass) on the SAME Canvas — both keyed
                        // off the same boardSize area the grid above occupies
                        // (contentAlignment = Center on this Box centers both
                        // identically). Named cellSizePx (not cellSize) to avoid
                        // shadowing the outer Dp cellSize computed above.
                        Canvas(modifier = Modifier.size(boardSize)) {
                            val cellSizePx = size.width / 3f
                            fun cellCenter(index: Int) = Offset(
                                (index % 3 + 0.5f) * cellSizePx,
                                (index / 3 + 0.5f) * cellSizePx
                            )
                            if (winningLine != null) {
                                val start = cellCenter(winningLine.first())
                                val end = cellCenter(winningLine.last())
                                val progress = winningLineProgress.value
                                val current = Offset(
                                    start.x + (end.x - start.x) * progress,
                                    start.y + (end.y - start.y) * progress
                                )
                                drawLine(
                                    color = Color(0xFFE65100),
                                    start = start,
                                    end = current,
                                    strokeWidth = 10f,
                                    cap = StrokeCap.Round
                                )
                            }
                            val tap = lastTap
                            if (tap != null) {
                                val ringProgress = inkRingProgress.value
                                if (ringProgress < 1f) {
                                    val center = cellCenter(tap.index)
                                    val maxRadius = cellSizePx * 0.42f
                                    drawCircle(
                                        color = Color(0xFF37474F).copy(alpha = (1f - ringProgress) * 0.55f),
                                        radius = (maxRadius * (0.15f + 0.85f * ringProgress)).coerceAtLeast(1f),
                                        center = center,
                                        style = Stroke(width = 6f)
                                    )
                                }
                            }
                        }

                        // Round-over panel (premium-2026-vision pass): wrapped in
                        // AnimatedVisibility (scale+fade from 0.9) instead of an
                        // instant pop, and dismissed the moment Play Again is
                        // tapped (isClearingRound) rather than staying up until
                        // the engine's roundOver flips false — see handlePlayAgain.
                        // Sized off the board's own Box (contentAlignment = Center),
                        // not a hardcoded portrait aspect, so this reads fine
                        // centered over a wide-short landscape board too.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = roundOver && !isClearingRound,
                            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)),
                            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.9f, animationSpec = tween(180))
                        ) {
                            // currentPlayer is still whoever just moved — cellClicked()
                            // returns before flipping it — so on a completed line it
                            // names the mover: the winner in standard rules, the loser
                            // in misere (see TicTacToeGame's KDoc on the variant).
                            val moverName = if (currentPlayer == 1) p1Name else p2Name
                            val moverIsLocalPlayer = vsCpu && currentPlayer == 1
                            RoundOverPanel(
                                resultText = when {
                                    winningLine == null -> "It's a draw!"
                                    misere && moverIsLocalPlayer -> "You made a line - you lose!"
                                    misere && vsCpu -> "CPU made a line - you win!"
                                    misere -> "$moverName made a line and loses!"
                                    moverIsLocalPlayer -> "You win!"
                                    vsCpu -> "CPU wins!"
                                    else -> "$moverName wins!"
                                },
                                onPlayAgain = ::handlePlayAgain,
                                onBackToMenu = game::leaveSession
                            )
                        }
                    }
                }

                if (isWide) {
                    // Wide/short window (Fold 5 cover screen rotated to landscape,
                    // e.g. ~882x344dp): put the header chrome BESIDE the board
                    // instead of above it — reflowing sideways buys the board its
                    // own full height instead of losing it to the score/turn text
                    // sitting above, which is exactly what used to starve the old
                    // fixed-size board on this window shape.
                    Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.Center
                        ) { header() }
                        Spacer(modifier = Modifier.width(16.dp))
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) { board() }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        header()
                        // weight(1f) here is what actually gives the
                        // BoxWithConstraints-derived board() a genuinely BOUNDED
                        // maxHeight to size from, rather than the effectively
                        // unbounded height a non-weighted Column child can get in
                        // some parent chains — see the CRITICAL SIZING PRINCIPLE
                        // note on this whole block.
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) { board() }
                    }
                }
            }
        }
    )
}

/** "You" -> "Your" (not "You's"); any other display name -> "Name's". */
private fun possessive(name: String): String = if (name == "You") "Your" else "$name's"

/** One human tap, for the ink-ring effect — [token] (not just [index]) so a later
 *  round retapping the exact same cell index still counts as a new, distinct tap for
 *  [LaunchedEffect]'s key comparison (a plain `Int` index could repeat across rounds
 *  and would otherwise silently fail to retrigger the ring). */
private data class InkTap(val index: Int, val token: Long)

private const val TTT_STAGGER_MS = 35L

/** Squared grid distance of cell [index] (0-8, row-major over the 3x3 board) from the
 *  board's true center cell (index 4) — used to order the fresh grid's staggered
 *  entrance center-out after Play Again resets the board. */
private fun gridDistanceFromCenter(index: Int): Int {
    val dx = index % 3 - 1
    val dy = index / 3 - 1
    return dx * dx + dy * dy
}

/** Occupied cells of [board], ordered outward from the round's own center cell for
 *  the Play Again board-clear stagger: the winning line's center square (WIN_LINES'
 *  middle entry is always its center cell, per the winning-line draw-on code above)
 *  on a win, or the board's true center (index 4) on a draw. Ties (equal distance)
 *  keep natural board order, which reads as a fine symmetric ripple either way. */
private fun clearOrder(board: IntArray, winningLine: List<Int>?): List<Int> {
    val centerIndex = winningLine?.getOrNull(1) ?: 4
    val cx = centerIndex % 3
    val cy = centerIndex / 3
    return board.indices.filter { board[it] != 0 }.sortedBy { idx ->
        val dx = idx % 3 - cx
        val dy = idx / 3 - cy
        dx * dx + dy * dy
    }
}

@Composable
private fun RoundOverPanel(
    resultText: String,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit
) {
    Card(modifier = Modifier.padding(16.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(resultText, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))
            // In-screen game control buttons (§4c) — hover cursor only, additive.
            Button(
                onClick = onPlayAgain,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Play Again")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Back to Menu")
            }
        }
    }
}
