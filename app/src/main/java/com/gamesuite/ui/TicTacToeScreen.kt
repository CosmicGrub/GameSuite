package com.gamesuite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Renders TicTacToeGame's state reactively. All game rules live in
 * TicTacToeGame itself (a GameModule) — this composable only draws the
 * board and forwards taps to it. Classic, Misere and Wild all use this one
 * screen: the `tic-tac-toe`, `tic-tac-toe-misere` and `tic-tac-toe-wild`
 * routes differ only in the [misere]/[wild] flags (forwarded to the game
 * before startMatch()). Wild adds the X/O picker above the board, since
 * [TicTacToeGame.cellClicked] places whichever symbol that picker last set.
 *
 * LAYOUT: the root is wrapped in the shared [GameChrome] (corner menu, system
 * back, abort-confirm, How to Play). A window wider than it is tall puts the
 * status block beside the board, otherwise above it. The board is sized with
 * [fitBoard] against the measured space of the slot it actually lives in (a
 * nested BoxWithConstraints), never the outer pane: the old code sized from the
 * whole pane, so in the two-column layout the board was clamped in width only
 * and its cells went non-square, which also pulled the winning line off the
 * marks. Cells are capped at a 480dp board; if the slot can't give a cell at
 * least 28dp the board stops shrinking and scrolls both ways instead. The
 * status block reserves [GameChromeEndInset] at its end (and the board slot
 * does in the wide layout) so the corner button never overlaps content.
 *
 * ROUND RESULT: the round-over panel sits below the board (portrait) or under the
 * status block (wide), not on top of the board, so the final position and the
 * winning line stay visible. It appears after a short hold (350ms, none under
 * reduced motion) so the line finishes drawing first. Result wording and the
 * sound/haptic outcome come from the LOCAL seat: a win chimes and celebrates,
 * a loss buzzes, and in pass-and-play (no single local seat) either win gets
 * the neutral chime. Misere inverts who wins; a draw is its own message with
 * no chime. Network play (LOCAL_AD_HOC / ONLINE) gates the cells on the local
 * seat and shows "You are X" / "Waiting for <name>" instead of letting a guest
 * tap on the host's turn.
 *
 * LEAVING: Back to Menu on the finished panel and the corner menu's "Back to
 * Menu" on a finished round both call [TicTacToeGame.leaveSession], which scores
 * the session. Leaving mid-round discards only that round: if earlier rounds
 * were already won or drawn it also goes through leaveSession so they still
 * count, otherwise it is a pure [TicTacToeGame.abortMatch] (never a win/loss).
 *
 * ACCESSIBILITY: every cell is a Role.Button with a "Row r, Column c, X|O|empty"
 * description (1-indexed; the phrasing is pinned by TicTacToeCheckersRotationTest,
 * so it stays "Row first" rather than the Reversi-style "state first"). Marks are
 * drawn on a Canvas, so the description is the only thing a screen reader sees.
 * The winning cells carry a heavy border under [LocalColorblindMode] in addition
 * to the amber fill and the drawn line. The turn text and the result are live
 * regions, the Wild picker exposes its selected state, and non-grid buttons are
 * at least 48dp tall. Mark/line colors were darkened one step so they hold 3:1
 * against the light-gray and amber cells.
 *
 * MOTION: each mark "stamps" in (snap down, overshoot, settle) with a radial ink
 * ring on the same overlay Canvas as the winning line (which draws on over
 * 220ms); Play Again scale-fades the old marks out in a stagger from the winning
 * line's center and then the fresh grid pops in center-out. Everything is
 * snapped under [LocalReducedMotion]. Every tappable cell and control also
 * shows a hand cursor for mouse/trackpad (Tab S9 DeX).
 *
 * The CPU's difficulty comes from Settings' "Default CPU difficulty". Not added
 * on purpose: a motion-intensity tier, an idle loop, table-material identity or
 * any shader — none fit this game.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun TicTacToeScreen(
    sessionManager: GameSessionManager,
    game: TicTacToeGame,
    settingsViewModel: SettingsViewModel,
    misere: Boolean = false,
    wild: Boolean = false,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Audio infra wiring (audio pass): ambient music respects BOTH the
    // ambient-music setting AND the master sound toggle, same AND pattern
    // every other feature-specific gate in this codebase already follows.
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.TIC_TAC_TOE, enabled = musicEnabled)
    val playSfx = rememberProceduralSfx()

    // False until game.init() has run: TicTacToeGame's context is a lateinit, so a tap (or a
    // bot move) before init would throw. The cells and the bot effect both wait on this.
    var gameReady by remember { mutableStateOf(false) }
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
        gameReady = true
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

    // Who is who. A null context only happens before launch / after the match has ended; the
    // fallbacks keep the screen drawable for those frames.
    val ctx = context
    val players = ctx?.players.orEmpty()
    val networked = ctx?.activeMode == PlayMode.LOCAL_AD_HOC || ctx?.activeMode == PlayMode.ONLINE
    val vsCpu = players.any { it.isBot }
    val localSeat = localSeatFor(ctx?.activeMode, players, ctx?.localPlayerIndex ?: -1)
    val p1Name = players.getOrNull(0)?.displayName ?: "Player 1"
    val p2Name = players.getOrNull(1)?.displayName ?: "Player 2"
    val currentName = if (currentPlayer == 1) p1Name else p2Name
    val isBotTurn = players.getOrNull(currentPlayer - 1)?.isBot == true
    // Whether THIS device may place a mark right now. Network play keys on the local seat (a
    // guest used to be able to tap on the host's turn); local play only blocks the CPU's turn.
    val canAct = gameReady && ctx != null && !roundOver &&
        (if (networked) localSeat == currentPlayer else !isBotTurn)
    val finishedRounds = scoreP1 + scoreP2 + draws

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

    // Round-end sting, keyed on the STATE (not on a tap) so a CPU or remote opponent's
    // line-completing move gets feedback too, and decided from the local seat: only the local
    // winner hears the chime and feels the celebration, a local loss gets the buzz and the
    // failure pattern. Pass-and-play has no single local seat, so either win is a neutral
    // chime + strong tap. A draw stays quiet on purpose — the result panel's text already
    // reads clearly — with a single normal tap. currentPlayer is still the mover at this
    // point (the engine returns before flipping it), which roundWinnerSeat relies on.
    LaunchedEffect(roundOver, winningLine) {
        if (!roundOver) return@LaunchedEffect
        val winner = roundWinnerSeat(winningLine, currentPlayer, misere)
        when {
            winner == null -> haptics(HapticSignal.NORMAL_ACTION)
            localSeat == null -> {
                playSfx(SfxKind.SUCCESS_CHIME)
                haptics(HapticSignal.STRONG_ACTION)
            }
            winner == localSeat -> {
                playSfx(SfxKind.SUCCESS_CHIME)
                haptics(HapticSignal.CELEBRATION)
            }
            else -> {
                playSfx(SfxKind.INVALID_BUZZ)
                haptics(HapticSignal.FAILURE)
            }
        }
    }

    // Short hold before the result panel appears, so the winning line finishes drawing
    // (220ms) before anything else claims attention. No hold for a draw (nothing is drawing)
    // or under reduced motion.
    var resultReady by remember { mutableStateOf(false) }
    LaunchedEffect(roundOver, winningLine) {
        if (!roundOver) {
            resultReady = false
        } else {
            if (winningLine != null && !reducedMotion) delay(RESULT_HOLD_MS)
            resultReady = true
        }
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
    LaunchedEffect(currentPlayer, roundOver, gameReady) {
        val c = context ?: return@LaunchedEffect
        if (!gameReady || roundOver) return@LaunchedEffect
        if (c.players.getOrNull(currentPlayer - 1)?.isBot == true) {
            delay(500)
            game.playBotTurn()
            // The bot's placement is otherwise silent (sounds.playTap() only fires from the
            // human's tap). A light tick + haptic gives the CPU's move the same "something
            // happened" acknowledgment — but not when the move ended the round, where the
            // round-end effect above already plays the outcome and a tick would stack on it.
            if (!game.roundOver.value) {
                playSfx(SfxKind.LIGHT_TICK)
                haptics(HapticSignal.LIGHT_TICK)
            }
        }
    }

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

    val scoreText = "$p1Name: $scoreP1 · $p2Name: $scoreP2" + if (draws > 0) " · Draws: $draws" else ""
    val infoText = buildList<String> {
        if (!wild && localSeat != null) add("You are ${if (localSeat == 1) "X" else "O"}")
        if (vsCpu) {
            add("CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}")
        }
        if (misere) add("Misere mode: completing a line loses!")
        if (wild) add("Wild mode: choose X or O each turn")
    }.joinToString(" · ")
    val turnText = when {
        roundOver -> "Round over"
        networked -> if (canAct) "Your turn" else "Waiting for $currentName..."
        isBotTurn -> "$currentName is thinking..."
        else -> "${possessive(currentName)} turn"
    }

    GameChrome(
        helpTitle = helpTitleFor(misere, wild),
        helpText = helpTextFor(misere, wild),
        matchInProgress = !roundOver,
        onLeave = game::leaveSession,
        // Leaving mid-round discards ONLY the unfinished round: if earlier rounds were already
        // won or drawn, the session-ending path (the same one the finished panel uses) runs so
        // they still count; only a session with nothing finished is a pure abort.
        onAbort = { if (finishedRounds > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = MaterialTheme.colorScheme.background,
        buttonContent = MaterialTheme.colorScheme.onBackground,
        leaveTitle = "Leave this round?",
        leaveBody = if (finishedRounds > 0) {
            "This round is still in progress and won't count, but the rounds you've already finished stay on your record."
        } else {
            "This round is still in progress. Leaving now won't count it as a win or a loss."
        }
    ) {
        // BoxWithConstraints gives real, bounded width/height for the layout choice below.
        // Fold 5 cover screen rotated to landscape (~344dp tall) used to starve a stacked
        // layout of height, so a window wider than it is tall puts the status block BESIDE
        // the board instead of above it.
        AdaptiveTwoPane(
            foldState = LocalFoldState.current,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            primary = {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWide = maxWidth > maxHeight

                    val header: @Composable () -> Unit = {
                        Text(scoreText, style = MaterialTheme.typography.labelLarge)
                        if (infoText.isNotEmpty()) {
                            Text(infoText, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            turnText,
                            style = MaterialTheme.typography.titleMedium,
                            // A screen-reader user otherwise has to re-explore to learn whose
                            // turn it is after every move.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )

                        // Wild-only symbol picker: which mark the acting player's next
                        // tap will place (TicTacToeGame.selectedSymbol) — standard
                        // rules fix that to the player's own mark, so there's nothing
                        // to choose. Stays on screen (disabled off-turn) rather than
                        // appearing and disappearing, so the board doesn't jump.
                        if (wild) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1 to "X", 2 to "O").forEach { (symbol, label) ->
                                    val picked = selectedSymbol == symbol
                                    // Wild-mode symbol picker (in-screen control button, §4c) —
                                    // mouse/trackpad hover cursor only, additive over touch.
                                    val pickerModifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .semantics { selected = picked }
                                    if (picked) {
                                        Button(
                                            onClick = { game.chooseSymbol(symbol) },
                                            enabled = canAct,
                                            modifier = pickerModifier
                                        ) { Text("Place $label") }
                                    } else {
                                        OutlinedButton(
                                            onClick = { game.chooseSymbol(symbol) },
                                            enabled = canAct,
                                            modifier = pickerModifier
                                        ) { Text("Place $label") }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // The board sizes itself from ITS OWN slot (this nested
                    // BoxWithConstraints sits inside the weighted Box below), never from the
                    // outer pane: `maxWidth`/`maxHeight` here are the slot's, so cells always
                    // stay square and the overlay Canvas lines up with them.
                    val boardArea: @Composable () -> Unit = {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val slotWidth = maxWidth
                            val slotHeight = maxHeight
                            // One "pitch" is a cell plus its CellGap on each side. fitBoard
                            // never returns a footprint larger than the slot; the cap keeps
                            // a tablet from sprawling into a 480dp+ board.
                            val fit = remember(slotWidth, slotHeight) {
                                fitBoard(
                                    availableWidthPx = slotWidth.value,
                                    availableHeightPx = slotHeight.value,
                                    columns = 3,
                                    rows = 3,
                                    minCellPx = MIN_PITCH_DP,
                                    maxCellPx = MAX_PITCH_DP
                                )
                            }
                            // Under the minimum a cell would be too small to hit, so stop
                            // shrinking and let the board pan instead of overflowing.
                            val scrolls = !fit.meetsMinimum
                            val pitch = (if (scrolls) MIN_PITCH_DP else fit.cellPx).dp
                            val boardSize = pitch * 3
                            val verticalState = rememberScrollState()
                            val horizontalState = rememberScrollState()

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (scrolls) {
                                            Modifier.verticalScroll(verticalState).horizontalScroll(horizontalState)
                                        } else {
                                            Modifier
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.size(boardSize)) {
                                    Column {
                                        for (row in 0 until 3) {
                                            Row {
                                                for (col in 0 until 3) {
                                                    val index = row * 3 + col
                                                    TttCell(
                                                        index = index,
                                                        cellValue = board[index],
                                                        isWinning = winningLine?.contains(index) == true,
                                                        enabled = canAct && board[index] == 0,
                                                        pitch = pitch,
                                                        colorblind = colorblind,
                                                        reducedMotion = reducedMotion,
                                                        // Board-clear exit + fresh-grid entrance —
                                                        // see cellExitScale/cellEntranceScale's own
                                                        // comments above. Both are 1f outside a
                                                        // transition, so this is a no-op almost
                                                        // always.
                                                        transitionScale = {
                                                            cellExitScale[index].value * cellEntranceScale[index].value
                                                        },
                                                        onTap = {
                                                            lastTap = InkTap(index, tapCounter)
                                                            tapCounter += 1
                                                            game.cellClicked(index)
                                                            sounds.playTap()
                                                            // Read game.*.value fresh: cellClicked()
                                                            // just updated it synchronously and the
                                                            // vals above are from the start of this
                                                            // composition. A line-completing or
                                                            // board-filling tap gets its haptic from
                                                            // the round-end effect instead, so a
                                                            // tick here would stack on it.
                                                            if (!game.roundOver.value) haptics(HapticSignal.LIGHT_TICK)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Draw-on winning line plus the radial "ink ring" on one
                                    // overlay Canvas covering exactly the grid above. Stroke
                                    // widths are fractions of the pitch so they scale with the
                                    // board instead of being raw pixels.
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val pitchPx = size.width / 3f
                                        fun cellCenter(index: Int) = Offset(
                                            (index % 3 + 0.5f) * pitchPx,
                                            (index / 3 + 0.5f) * pitchPx
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
                                                color = WinLineColor,
                                                start = start,
                                                end = current,
                                                strokeWidth = pitchPx * 0.06f,
                                                cap = StrokeCap.Round
                                            )
                                        }
                                        val tap = lastTap
                                        if (tap != null) {
                                            val ringProgress = inkRingProgress.value
                                            if (ringProgress < 1f) {
                                                val center = cellCenter(tap.index)
                                                val maxRadius = pitchPx * 0.42f
                                                drawCircle(
                                                    color = InkRingColor.copy(alpha = (1f - ringProgress) * 0.55f),
                                                    radius = (maxRadius * (0.15f + 0.85f * ringProgress)).coerceAtLeast(1f),
                                                    center = center,
                                                    style = Stroke(width = pitchPx * 0.03f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Round-over panel. Lives beside/below the board, not over it, so the final
                    // position and the winning line stay visible; AnimatedVisibility (scale+fade
                    // from 0.9, none under reduced motion) instead of an instant pop, dismissed
                    // the moment Play Again is tapped (isClearingRound) rather than staying up
                    // until the engine's roundOver flips false — see handlePlayAgain.
                    val resultPanel: @Composable () -> Unit = {
                        AnimatedVisibility(
                            visible = roundOver && resultReady && !isClearingRound,
                            enter = if (reducedMotion) {
                                EnterTransition.None
                            } else {
                                fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200))
                            },
                            exit = if (reducedMotion) {
                                ExitTransition.None
                            } else {
                                fadeOut(tween(180)) + scaleOut(targetScale = 0.9f, animationSpec = tween(180))
                            }
                        ) {
                            RoundOverPanel(
                                resultText = roundResultText(winningLine, currentPlayer, misere, localSeat, p1Name, p2Name),
                                onPlayAgain = ::handlePlayAgain,
                                onBackToMenu = game::leaveSession
                            )
                        }
                    }

                    if (isWide) {
                        // Wide/short window (Fold 5 cover screen rotated to landscape,
                        // e.g. ~882x344dp): put the status block BESIDE the board
                        // instead of above it — reflowing sideways buys the board its
                        // own full height instead of losing it to the score/turn text
                        // sitting above. The status column scrolls rather than clipping if
                        // the result panel makes it taller than the window; the board slot
                        // reserves the corner button's width at its end because here the
                        // board, not the status block, is what reaches the top-right.
                        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.Center
                            ) {
                                header()
                                resultPanel()
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1.4f)
                                    .fillMaxHeight()
                                    .padding(end = GameChromeEndInset)
                            ) { boardArea() }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // The status block is what sits under the corner button in
                            // portrait, so it reserves the button's width at its end.
                            Column(modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset)) {
                                header()
                            }
                            // weight(1f) is what gives the board slot a genuinely BOUNDED
                            // height to measure.
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { boardArea() }
                            resultPanel()
                        }
                    }
                }
            }
        )
    }
}

// Board colors. X/O are one step darker than the original 1976D2/D32F2F so a mark holds 3:1
// against the light-gray cell (the old blue was 2.9:1); the line is darkened the same way
// against the amber winning cell (E65100 was 2.7:1). Hues are unchanged.
private val EmptyCellColor = Color.LightGray
private val WinningCellColor = Color(0xFFFFD54F)
private val WinningBorderColor = Color(0xFF263238)
private val MarkXColor = Color(0xFF1565C0)
private val MarkOColor = Color(0xFFC62828)
private val WinLineColor = Color(0xFFD84315)
private val InkRingColor = Color(0xFF37474F)

/** Space on each side of a cell inside its slot (so cells sit 8dp apart). */
private val CellGap = 4.dp

/** Smallest cell pitch (28dp cell + 2 x [CellGap]) before the board scrolls instead of shrinking. */
private const val MIN_PITCH_DP = 36f

/** Largest cell pitch: a 480dp board, so a tablet doesn't stretch it. */
private const val MAX_PITCH_DP = 160f

/** Pause between a round ending and its result panel appearing, so the winning line finishes first. */
private const val RESULT_HOLD_MS = 350L

private const val TTT_STAGGER_MS = 35L

/** "You" -> "Your" (not "You's"); any other display name -> "Name's". */
private fun possessive(name: String): String = if (name == "You") "Your" else "$name's"

/** One human tap, for the ink-ring effect — [token] (not just [index]) so a later
 *  round retapping the exact same cell index still counts as a new, distinct tap for
 *  [LaunchedEffect]'s key comparison (a plain `Int` index could repeat across rounds
 *  and would otherwise silently fail to retrigger the ring). */
private data class InkTap(val index: Int, val token: Long)

/**
 * The seat (1 or 2) this device plays, or null when there isn't a single one: pass-and-play
 * shares the device between both seats, and a spectator (`localPlayerIndex == -1`) has none.
 * Network play (LOCAL_AD_HOC / ONLINE) uses [localPlayerIndex]; a vs-CPU game is the first
 * non-bot player.
 */
internal fun localSeatFor(mode: PlayMode?, players: List<PlayerInfo>, localPlayerIndex: Int): Int? = when {
    mode == PlayMode.LOCAL_AD_HOC || mode == PlayMode.ONLINE ->
        if (localPlayerIndex in 0..1) localPlayerIndex + 1 else null
    players.any { it.isBot } ->
        players.indexOfFirst { !it.isBot }.let { if (it in 0..1) it + 1 else null }
    else -> null
}

/**
 * The seat (1 or 2) that WON the round that just ended, or null on a draw (no
 * [winningLine]). [moverSeat] is [TicTacToeGame.currentPlayer] at that moment, which is
 * still whoever placed the last mark because the engine returns before flipping it. Standard
 * and Wild: completing the line wins. Misere: completing it loses, so the other seat wins.
 */
internal fun roundWinnerSeat(winningLine: List<Int>?, moverSeat: Int, misere: Boolean): Int? {
    if (winningLine == null) return null
    return if (misere) 3 - moverSeat else moverSeat
}

/**
 * The round-over headline. Worded from the local seat when there is one ("You win!", "CPU
 * wins!") and by name otherwise (pass-and-play). Misere describes the mover's self-inflicted
 * loss ("You made a line - you lose!"). A draw is always "It's a draw!".
 */
internal fun roundResultText(
    winningLine: List<Int>?,
    moverSeat: Int,
    misere: Boolean,
    localSeat: Int?,
    p1Name: String,
    p2Name: String
): String {
    if (winningLine == null) return "It's a draw!"
    val moverName = if (moverSeat == 1) p1Name else p2Name
    val moverIsLocal = localSeat != null && moverSeat == localSeat
    return when {
        misere && moverIsLocal -> "You made a line - you lose!"
        misere && localSeat != null -> "$moverName made a line - you win!"
        misere -> "$moverName made a line and loses!"
        moverIsLocal -> "You win!"
        else -> "$moverName wins!"
    }
}

private fun helpTitleFor(misere: Boolean, wild: Boolean): String = when {
    misere && wild -> "How to Play Wild Misere Tic-Tac-Toe"
    misere -> "How to Play Misere Tic-Tac-Toe"
    wild -> "How to Play Wild Tic-Tac-Toe"
    else -> "How to Play Tic-Tac-Toe"
}

private fun helpTextFor(misere: Boolean, wild: Boolean): String {
    val turns = if (wild) {
        "Take turns marking an empty square of the 3x3 grid. On your turn pick X or O with the " +
            "Place X / Place O buttons; either player may use either mark."
    } else {
        "Take turns marking an empty square of the 3x3 grid; one player is X and the other is O."
    }
    val line = if (misere) {
        "Three matching marks in a row, across, down or diagonally, end the round, and the " +
            "player who completes that line loses, so make your opponent do it."
    } else {
        "Three matching marks in a row, across, down or diagonally, end the round, and the " +
            "player who completes that line wins."
    }
    return "$turns $line If the grid fills up with no line, the round is a draw. " +
        "Scores carry across rounds, and Play Again swaps who moves first."
}

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

/**
 * One board cell: a [pitch]-sized tap target (cell plus [CellGap] all round, so there is no
 * dead strip between cells) wrapping the drawn square. The semantics live on the outer box;
 * the mark itself is a Canvas and contributes none, so a screen reader hears the description
 * once. The description keeps the "Row r, Column c, X|O|empty" phrasing that the on-device
 * rotation test pins.
 */
@Composable
private fun TttCell(
    index: Int,
    cellValue: Int,
    isWinning: Boolean,
    enabled: Boolean,
    pitch: Dp,
    colorblind: Boolean,
    reducedMotion: Boolean,
    transitionScale: () -> Float,
    onTap: () -> Unit
) {
    // 1-indexed row/column (index is 0-8 row-major over the 3x3 grid) plus what's there.
    val markName = when (cellValue) {
        1 -> "X"
        2 -> "O"
        else -> "empty"
    }
    val description = "Row ${index / 3 + 1}, Column ${index % 3 + 1}, $markName" +
        if (isWinning) ", winning line" else ""

    // "Stamp" placement (premium-2026-vision pass): a real 3-keyframe hit-stop instead of a
    // plain scaleIn spring — snap down to 0.85 over ~40ms, overshoot to 1.12, settle to 1.0.
    // Keyed on cellValue itself so it plays exactly once per placement and resets cleanly the
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
            .size(pitch)
            // Mouse/trackpad hover cursor (§4c) — only over a cell that can be tapped;
            // no effect on touch input.
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(enabled = enabled, onClickLabel = "Place mark", role = Role.Button, onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .padding(CellGap)
                .fillMaxSize()
                .graphicsLayer {
                    val s = transitionScale()
                    scaleX = s
                    scaleY = s
                    alpha = s
                }
                .background(if (isWinning) WinningCellColor else EmptyCellColor)
                // Non-color cue for the winning cells (amber vs gray is color alone): a heavy
                // border when Settings' colorblind-safe mode is on. The drawn line is the
                // other cue, for everyone.
                .then(if (isWinning && colorblind) Modifier.border(3.dp, WinningBorderColor) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (cellValue != 0) {
                MarkGlyph(
                    symbol = cellValue,
                    color = if (cellValue == 1) MarkXColor else MarkOColor,
                    scale = { stampScale.value }
                )
            }
        }
    }
}

/**
 * An X (two round-capped strokes) or an O (a ring), drawn as fractions of the cell so it is
 * exactly centered at every size and independent of font metrics. Purely visual: it adds no
 * semantics — the cell's description already says what is there. [scale] is read in the
 * graphics layer so the stamp animation never recomposes anything.
 */
@Composable
private fun MarkGlyph(symbol: Int, color: Color, scale: () -> Float) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            }
    ) {
        val side = size.minDimension
        val strokeWidth = side * 0.13f
        val inset = side * 0.24f
        if (symbol == 1) {
            drawLine(
                color = color,
                start = Offset(inset, inset),
                end = Offset(size.width - inset, size.height - inset),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(size.width - inset, inset),
                end = Offset(inset, size.height - inset),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        } else {
            drawCircle(
                color = color,
                radius = side * 0.27f,
                center = center,
                style = Stroke(width = strokeWidth)
            )
        }
    }
}

@Composable
private fun RoundOverPanel(
    resultText: String,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                resultText,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(modifier = Modifier.height(12.dp))
            // In-screen game control buttons (§4c) — hover cursor only, additive; 48dp min
            // height for the touch-target floor.
            Button(
                onClick = onPlayAgain,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Play Again")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Back to Menu")
            }
        }
    }
}
