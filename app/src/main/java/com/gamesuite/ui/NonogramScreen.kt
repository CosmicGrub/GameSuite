package com.gamesuite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.nonogram.NonogramCellState
import com.gamesuite.games.nonogram.NonogramGame
import com.gamesuite.games.nonogram.NonogramState
import com.gamesuite.games.nonogram.NonogramStatsStore
import com.gamesuite.games.nonogram.NonogramTool
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlin.math.floor

/**
 * Below this cell size (dp) the board stops shrinking and scrolls/pans instead. A nonogram cell is
 * tapped and dragged on, so this is the tappable-grid floor (the same 28dp Edge Match uses), not the
 * lower display-only floors of Color Flood and Word Search. It is raised further when a column clue
 * number is wider than a cell (large font scale), so a clue never spills over its neighbour.
 */
private const val NG_MIN_CELL_DP = 28f

/** Upper bound so a small EASY grid does not sprawl across a tablet. */
private const val NG_MAX_CELL_DP = 56f

/** A heavier separator line is drawn every this many cells, and around the board. */
private const val NG_BLOCK = 5

/** Spare room added to the measured row-clue width: the end padding plus a few dp of font-metric slack. */
private const val NG_GUTTER_SLACK_DP = 12f

/** The solved board's reveal (cross marks and thin grid lines fade out). Off under reduced motion. */
private const val NG_REVEAL_MS = 320

/** How long the solved board is left alone before the result panel appears over it. */
private const val NG_RESULT_HOLD_MS = 450L

/** The result panel's fade-in. Instant under reduced motion. */
private const val NG_RESULT_FADE_MS = 250

private const val NONOGRAM_HELP =
    "Each number beside a row or above a column is the length of one run of filled squares in that " +
        "line, in order, with at least one empty square between runs; a 0 means the line is empty. " +
        "Pick Fill or Cross, then tap a square, or drag along a row or column, to mark it; start on " +
        "a square that already holds that mark to erase that mark instead. If the board is bigger " +
        "than the window it scrolls, so touch and hold a square before dragging to paint.\n\n" +
        "A cross is only your own note and never counts for or against you. A filled square that is " +
        "not part of the solution turns red with a slash and counts as a mistake; Undo takes back " +
        "your last stroke but not the mistake count.\n\n" +
        "The puzzle is solved when your filled squares match the picture exactly (crosses don't " +
        "matter); a clue is struck through once its line already matches, your clock starts on " +
        "your first mark, and Easy, Medium and Hard are 5x5, 10x10 and 15x15."

private const val NONOGRAM_DAILY_HELP =
    "\n\nThe Daily puzzle is the same for every player on the same difficulty today; switching " +
        "difficulty or starting a new puzzle leaves it."

/**
 * Renders NonogramGame's state reactively: tier chips, a live time / mistakes status block, the
 * board with its clues, a Fill/Cross tool toggle with Undo, and a solved-puzzle panel. Row clue
 * numbers sit to the LEFT of the grid and column clue numbers ABOVE it (the classic Picross
 * layout), because the clues themselves, not a separately-displayed hint, are the entire puzzle.
 *
 * INPUT: pick a tool (Fill or Cross), then tap a square or drag along a row or column. The first
 * square a finger touches decides the stroke: one that already holds the tool's mark makes the
 * stroke an ERASE, anything else a PAINT, and a drag locks to the row or column it started on. The
 * old three-state tap cycle (fill, then cross, then blank) turned a mistap on a small cell into a
 * silent fill-to-cross change; [NonogramGame.tapCell] still exists but the screen no longer uses it.
 * A FILLED square that is not in the solution renders in [NonogramPalette.danger] immediately: this
 * app's established full-information puzzle culture (Sudoku flags a wrong entry at once, Edge Match
 * live-highlights matches), not a genre convention this screen invents. [NonogramState.solution] is
 * on state precisely so that feedback needs no separate solver.
 *
 * SHAPES, NOT COLOR ALONE: a fill is a solid square, a cross is an X drawn on an empty square, and
 * a mistake is a red square with a diagonal slash through it (heavier, and outlined, under
 * [LocalColorblindMode]). A satisfied clue is struck through as well as dimmed (the dimmed ink is
 * 4.5:1 or better, not the old 2.2:1 alpha fade).
 *
 * BOARD: one [Canvas] (not 225 boxes) with a heavier line every 5 cells, so counting across a
 * 10x10 or 15x15 grid is aided. The clue gutters are sized from MEASURED text (a text measurer, the
 * widest row clue and the tallest column clue), at a label style that follows both the system font
 * scale and the app's text-size setting, never a fixed 11sp that wraps into the next row. The board
 * is sized with [fitBoard] against what is left of its own slot after the gutters, clamped to
 * [NG_MIN_CELL_DP]..[NG_MAX_CELL_DP]; when the slot cannot give a cell [NG_MIN_CELL_DP] the board
 * keeps that size and pans in the short axis (or both), with the clue gutters pinned to the pan
 * (they translate with the scroll offset inside clipped boxes), so the clue for the row or column
 * under your finger is always visible. The Canvas draws AND hit-tests with the same cell edge,
 * `min(width, height) / n` of its own measured size, so a touch lands on the cell drawn under it.
 * While the board pans, a plain drag pans, a tap marks one square, and touch-and-hold then drag
 * paints (a drag-consuming surface inside a scroll container could never be panned otherwise).
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, Restart this puzzle, New puzzle, Back to
 * Menu) plus its BackHandler. Leaving mid-puzzle discards ONLY the unfinished puzzle: with nothing
 * solved yet it is a pure `abortMatch()` (never recorded), but once the session has solved puzzles
 * it goes through `leaveSession()`, the same call the solved panel's "Back to Menu" makes, so those
 * results still count (the confirm dialog says so). The status block reserves [GameChromeEndInset]
 * so the corner button never covers it. Restart, New puzzle and a tier switch all throw the current
 * marks away, so each asks first once the board has any mark on it.
 *
 * DAILY: a "Daily puzzle" tag shows while today's seeded puzzle is on screen. A tier switch or New
 * puzzle deals a random puzzle again and the confirm dialog says so.
 *
 * ACCESSIBILITY: every cell is a button described by its state plus 1-indexed row and column (in a
 * layer under the Canvas, like Word Search, so touch still reaches the Canvas); each clue is one
 * node ("Row 3 clue 2 1, matched"); the mistakes counter is a polite live region; tier and tool
 * chips are radio buttons; every non-grid control is at least 48dp.
 *
 * MOTION: the solved board's cross marks and thin grid lines fade out over 320ms so the finished
 * picture reads on its own, then the result panel fades in over 250ms and sits OVER the lower part
 * of the screen instead of re-measuring the board at the win frame. Both are off under
 * [LocalReducedMotion]. A solve always celebrates (a human solve is the only kind there is):
 * celebration haptic and success chime.
 *
 * Music: [MusicProfiles.PUZZLE_FOCUS] directly, same as EdgeMatchScreen's own choice.
 *
 * VISUAL IDENTITY: chrome shares this batch's warm tokens (see [nonogramPalette]); no bespoke
 * play-element colors beyond the standard filled/marked/mistake states. Grid and cross-mark inks
 * were darkened (they were 1.5:1 and 2.5:1) without changing the cream-and-ink look.
 *
 * LIVE CLUE STRIKETHROUGH (docs/NONOGRAM_DESIGN.md's own Feedback section): a line's clue numbers
 * strike through once that line's CURRENT fill pattern (FILLED cells only, MARKED_EMPTY counts
 * either way) already matches its clue. [nonogramCluesOf] is the same run-length derivation
 * [NonogramGame] uses to build the clues, re-run against the player's live fill state. It is a
 * duplicated small pure function rather than a new public engine method, mirroring the idiom
 * NonogramGameTest's `independentCluesOf` established. This does NOT gate winning or mistakes; the
 * real win check still compares every cell against the solution.
 */
@Composable
fun NonogramScreen(
    sessionManager: GameSessionManager,
    game: NonogramGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's puzzle for every player — see NonogramGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { NonogramStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = nonogramPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current

    // True while the puzzle on screen is the seeded daily one. Any later fresh puzzle (a tier
    // switch, "New puzzle") is random again, and the screen says so.
    var dailyBoardActive by remember { mutableStateOf(dailySeed != null) }
    var tool by remember { mutableStateOf(NonogramTool.FILL) }
    var pendingConfirm by remember { mutableStateOf<NonogramPendingAction?>(null) }
    val strokeFeedback = remember { NonogramStrokeFeedback() }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        dailyBoardActive = dailySeed != null
        game.startMatch(dailySeed)
    }

    val s = state ?: return

    val allBestTimes by statsStore.bestTimesMillis.collectAsState(initial = emptyMap())
    val bestTimeMillis = allBestTimes[game.difficulty.name]
    // Keyed on s.solution (unique per puzzle instance), same convention Sudoku/Kakuro/KenKen
    // already use for their own reported result -- s.size alone doesn't change between two
    // rounds of the same difficulty, which let round 1's result silently keep showing on every
    // later round. Null means "this puzzle's win has not been recorded yet".
    var reportedNewBest by remember(s.solution) { mutableStateOf<Boolean?>(null) }

    // The result panel waits for the reveal to land before it covers the lower part of the screen.
    var showResult by remember { mutableStateOf(false) }
    LaunchedEffect(s.won) {
        if (s.won) {
            if (!reducedMotion) delay(NG_RESULT_HOLD_MS)
            showResult = true
        } else {
            showResult = false
        }
    }

    LaunchedEffect(s.won) {
        if (!s.won) {
            // Any unsolved board (fresh or re-dealt) forgets the last solve's record, so a re-dealt
            // puzzle with an identical solution still records its own solve.
            reportedNewBest = null
            return@LaunchedEffect
        }
        // A solve is always the human's (there is no opponent), so the celebration is unconditional.
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        if (reportedNewBest == null) {
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            reportedNewBest = statsStore.recordWin(game.difficulty, finalTime)
        }
    }

    // "Finished units" for the abort policy: puzzles already solved this session. If any exist,
    // leaving mid-puzzle must still score them -- see onAbort below.
    val finishedPuzzles = game.puzzlesSolved.value
    val lineStatus = remember(s.cells) { nonogramLineStatus(s) }
    val hasProgress = remember(s.cells) { s.cells.any { it != NonogramCellState.UNDETERMINED } }
    val canUndo = game.undoDepth.value > 0 && !s.won

    fun performAction(action: NonogramPendingAction) {
        if (game.matchOver.value) return
        when (action) {
            NonogramPendingAction.Restart -> game.restartPuzzle()
            NonogramPendingAction.NewPuzzle -> {
                dailyBoardActive = false
                game.playAgain()
            }
            is NonogramPendingAction.SwitchTier -> if (action.tier != game.difficulty) {
                dailyBoardActive = false
                game.difficulty = action.tier
                game.startMatch()
            }
        }
    }

    // Restart, New puzzle and a tier switch all discard the marks on the board: ask first, but only
    // when there is something to lose (a solved or untouched board just does it).
    fun requestAction(action: NonogramPendingAction) {
        if (s.won || !hasProgress) performAction(action) else pendingConfirm = action
    }

    // One cue per change, so a drag that paints ten wrong squares buzzes once, not ten times.
    fun reportChange(changed: Boolean, beforeMistakes: Int, beforeLines: Int, tick: Boolean) {
        if (!changed) return
        val after = game.state.value ?: return
        when {
            after.won -> Unit // the win effect plays the celebration
            after.mistakes > beforeMistakes -> if (!strokeFeedback.failedThisStroke) {
                strokeFeedback.failedThisStroke = true
                haptics(HapticSignal.FAILURE)
                playSfx(SfxKind.INVALID_BUZZ)
            }
            nonogramLineStatus(after).count > beforeLines -> haptics(HapticSignal.SUCCESS)
            tick -> haptics(HapticSignal.LIGHT_TICK)
        }
    }

    // Re-reads the live state: the cell a touch landed on can be a frame stale, and a touch on a
    // solved board (or after the session ended) must not buzz, click or count.
    fun runMark(playClick: Boolean, tick: Boolean, resetFailure: Boolean, change: () -> Boolean) {
        val before = game.state.value ?: return
        if (before.won || game.matchOver.value) return
        if (resetFailure) strokeFeedback.failedThisStroke = false
        val linesBefore = nonogramLineStatus(before).count
        val changed = change()
        if (changed && playClick) sounds.playTap()
        reportChange(changed, before.mistakes, linesBefore, tick)
    }

    val onStrokeStart: (Int) -> Unit = { index ->
        runMark(playClick = true, tick = true, resetFailure = true) { game.beginStroke(index, tool) }
    }
    val onStrokeMove: (Int, Int) -> Unit = { row, col ->
        runMark(playClick = false, tick = false, resetFailure = false) { game.dragStrokeTo(row, col) }
    }
    val onStrokeEnd: () -> Unit = { game.endStroke() }
    val onToggleCell: (Int) -> Unit = { index ->
        runMark(playClick = true, tick = true, resetFailure = true) { game.toggleCell(index, tool) }
    }
    val onUndo: () -> Unit = {
        if (game.undo()) {
            sounds.playTap()
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val onSelectTier: (CpuDifficulty) -> Unit = { tier ->
        if (tier != game.difficulty) requestAction(NonogramPendingAction.SwitchTier(tier))
    }

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-puzzle discards
    // ONLY the unfinished puzzle: if puzzles were already solved this session, leaving goes through
    // leaveSession() (the same call the solved panel's "Back to Menu" makes) so they still count;
    // with nothing solved it is a pure abort (never a win or loss). A solved puzzle leaves through
    // leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Nonogram",
        helpText = if (dailySeed != null) NONOGRAM_HELP + NONOGRAM_DAILY_HELP else NONOGRAM_HELP,
        matchInProgress = !s.won,
        onLeave = game::leaveSession,
        onAbort = { if (finishedPuzzles > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this puzzle?",
        leaveBody = if (finishedPuzzles > 0) {
            "This puzzle is still unsolved and won't count, but the puzzles you've already solved stay on your record."
        } else {
            "This puzzle is still unsolved. Leaving now won't count it as a win or a loss."
        },
        extraItems = { dismiss ->
            DropdownMenuItem(
                text = { Text("Restart this puzzle") },
                enabled = hasProgress && !s.won,
                onClick = {
                    dismiss()
                    requestAction(NonogramPendingAction.Restart)
                }
            )
            DropdownMenuItem(
                text = { Text(if (dailyBoardActive) "New puzzle (leaves the daily)" else "New puzzle") },
                onClick = {
                    dismiss()
                    requestAction(NonogramPendingAction.NewPuzzle)
                }
            )
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(12.dp)
        ) {
            // Board left + controls right only when the window is genuinely wide; every phone
            // portrait, the Fold cover screen included, takes the stacked layout.
            val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight * 1.2f
            // Read here: inside the Column below, this scope's maxHeight is not implicitly reachable.
            val areaMaxHeight = maxHeight

            val statusBlock: @Composable (Modifier) -> Unit = { statusModifier ->
                NonogramStatus(
                    mistakes = s.mistakes,
                    daily = dailyBoardActive,
                    game = game,
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val tiersRow: @Composable () -> Unit = {
                NonogramTiers(current = game.difficulty, palette = palette, onSelect = onSelectTier)
            }
            val controlsRow: @Composable () -> Unit = {
                NonogramControls(
                    tool = tool,
                    onToolChange = { tool = it },
                    canUndo = canUndo,
                    onUndo = onUndo,
                    palette = palette
                )
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                NonogramBoardSlot(
                    s = s,
                    lineStatus = lineStatus,
                    tool = tool,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    onStrokeStart = onStrokeStart,
                    onStrokeMove = onStrokeMove,
                    onStrokeEnd = onStrokeEnd,
                    onToggleCell = onToggleCell,
                    modifier = slotModifier
                )
            }
            val resultPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                NonogramResultPanel(
                    timeMillis = game.finishedElapsedMillis.value,
                    mistakes = s.mistakes,
                    bestTimeMillis = bestTimeMillis,
                    isNewBest = reportedNewBest == true,
                    daily = dailyBoardActive,
                    onNewPuzzle = { requestAction(NonogramPendingAction.NewPuzzle) },
                    onBackToMenu = { game.leaveSession() },
                    palette = palette,
                    modifier = panelModifier
                )
            }

            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    boardSlot(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        tiersRow()
                        controlsRow()
                        if (s.won && showResult) resultPanel(Modifier.fillMaxWidth())
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // The status block is the top-right occupant of this layout, so it keeps the
                    // corner button's width free at its end.
                    statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                    Spacer(Modifier.height(4.dp))
                    tiersRow()
                    Spacer(Modifier.height(8.dp))
                    boardSlot(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    controlsRow()
                }
                // An overlay on the stack rather than a sibling in it: a sibling re-measured the
                // board at the win frame. It lands over the (by then idle) controls row first; the
                // panel scrolls inside its own cap.
                AnimatedVisibility(
                    visible = s.won && showResult,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = if (reducedMotion) EnterTransition.None else fadeIn(animationSpec = tween(NG_RESULT_FADE_MS)),
                    exit = ExitTransition.None
                ) {
                    resultPanel(
                        Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .heightIn(max = areaMaxHeight * 0.7f)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }

    pendingConfirm?.let { pending ->
        NonogramConfirmDialog(
            action = pending,
            leavesDaily = dailyBoardActive && pending !is NonogramPendingAction.Restart,
            onConfirm = {
                pendingConfirm = null
                performAction(pending)
            },
            onDismiss = { pendingConfirm = null }
        )
    }
}

/** What a confirm dialog is about to do (each of these discards the marks on the current board). */
private sealed class NonogramPendingAction {
    object Restart : NonogramPendingAction()
    object NewPuzzle : NonogramPendingAction()
    class SwitchTier(val tier: CpuDifficulty) : NonogramPendingAction()
}

/** Remembers whether the current stroke already buzzed for a mistake, so a drag buzzes once. */
private class NonogramStrokeFeedback {
    var failedThisStroke = false
}

/** Which row and column clues already match the player's current fills (display-only, see the class KDoc). */
private class NonogramLineStatus(val rows: List<Boolean>, val cols: List<Boolean>) {
    val count: Int get() = rows.count { it } + cols.count { it }
}

private fun nonogramLineStatus(s: NonogramState): NonogramLineStatus {
    val n = s.size
    val rows = List(n) { r ->
        nonogramCluesOf(List(n) { c -> s.cells[r * n + c] == NonogramCellState.FILLED }) == s.rowClues[r]
    }
    val cols = List(n) { c ->
        nonogramCluesOf(List(n) { r -> s.cells[r * n + c] == NonogramCellState.FILLED }) == s.colClues[c]
    }
    return NonogramLineStatus(rows, cols)
}

/** Same run-length derivation [NonogramGame] itself uses to build clues from the solution — see
 *  this file's own class KDoc's LIVE CLUE STRIKETHROUGH section for why this is its own small
 *  copy rather than a call into the engine. */
private fun nonogramCluesOf(line: List<Boolean>): List<Int> {
    val result = mutableListOf<Int>()
    var run = 0
    for (cell in line) {
        if (cell) {
            run++
        } else if (run > 0) {
            result += run
            run = 0
        }
    }
    if (run > 0) result += run
    return result
}

private fun nonogramTierLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

private fun nonogramFormatClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

// ---------------------------------------------------------------------------
// Tier chips, tool chips, status block, clock, dialogs
// ---------------------------------------------------------------------------

@Composable
private fun NonogramTiers(current: CpuDifficulty, palette: NonogramPalette, onSelect: (CpuDifficulty) -> Unit) {
    // horizontalScroll is the fallback for a window too narrow for three chips plus large text, so
    // the row is simply scrolled into view instead of a chip being clipped.
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (tier in CpuDifficulty.entries) {
            NonogramChip(
                label = nonogramTierLabel(tier),
                glyph = null,
                selected = tier == current,
                palette = palette,
                onClick = { onSelect(tier) }
            )
        }
    }
}

/**
 * The Fill / Cross toggle and Undo. The two tool chips are radio buttons whose glyph (a solid
 * square, an X) matches the mark they paint; the glyph is decoration, the label carries the name.
 */
@Composable
private fun NonogramControls(
    tool: NonogramTool,
    onToolChange: (NonogramTool) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    palette: NonogramPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NonogramChip(
            label = "Fill",
            glyph = "■",
            selected = tool == NonogramTool.FILL,
            palette = palette,
            onClick = { onToolChange(NonogramTool.FILL) }
        )
        NonogramChip(
            label = "Cross",
            glyph = "✕",
            selected = tool == NonogramTool.CROSS,
            palette = palette,
            onClick = { onToolChange(NonogramTool.CROSS) }
        )
        OutlinedButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) { Text("Undo") }
    }
}

/** A pill chip with a 48dp-tall touch target around a 36dp-tall pill, announced as a radio button. */
@Composable
private fun NonogramChip(label: String, glyph: String?, selected: Boolean, palette: NonogramPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected) palette.accent else palette.chipBackground)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ink = if (selected) palette.textOnAccent else palette.textPrimary
            if (glyph != null) {
                // Decoration: the label already names the tool.
                Text(glyph, color = ink, modifier = Modifier.clearAndSetSemantics {})
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                color = ink,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/**
 * The clock and the mistake count on one line, plus a "Daily puzzle" tag when today's seeded
 * puzzle is the one on screen. At least 48dp tall so the layout below it clears the corner button.
 * Callers give it `padding(end = GameChromeEndInset)`.
 */
@Composable
private fun NonogramStatus(
    mistakes: Int,
    daily: Boolean,
    game: NonogramGame,
    palette: NonogramPalette,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        if (daily) {
            Text(
                "Daily puzzle",
                color = palette.textPrimary.copy(alpha = 0.75f),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NonogramClock(game = game, palette = palette)
            Text(
                "Mistakes: $mistakes",
                color = palette.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A polite live region so a screen reader announces a new mistake.
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (mistakes == 1) "1 mistake" else "$mistakes mistakes"
                }
            )
        }
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the screen and the board. Reads [NonogramGame.activeElapsedMillis], so it freezes while the engine
 * is paused (app backgrounded) and ends on exactly the recorded solve time.
 */
@Composable
private fun NonogramClock(game: NonogramGame, palette: NonogramPalette) {
    val startedAt = game.timerStartElapsedRealtime.value
    val finishedMillis = game.finishedElapsedMillis.value
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAt, finishedMillis) {
        if (finishedMillis != null) {
            elapsed = finishedMillis
        } else if (startedAt == null) {
            elapsed = 0L
        } else {
            while (true) {
                elapsed = game.activeElapsedMillis() ?: 0L
                delay(200)
            }
        }
    }
    Text(
        "Time: ${nonogramFormatClock(elapsed)}",
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1
    )
}

@Composable
private fun NonogramConfirmDialog(
    action: NonogramPendingAction,
    leavesDaily: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = when (action) {
        NonogramPendingAction.Restart -> "Restart this puzzle?"
        NonogramPendingAction.NewPuzzle -> "Start a new puzzle?"
        is NonogramPendingAction.SwitchTier -> "Switch to ${nonogramTierLabel(action.tier)}?"
    }
    val confirmLabel = when (action) {
        NonogramPendingAction.Restart -> "Restart"
        NonogramPendingAction.NewPuzzle -> "New Puzzle"
        is NonogramPendingAction.SwitchTier -> "Switch"
    }
    val body = "This puzzle is unsolved, and the marks on it will be lost." +
        (if (leavesDaily) " It also leaves today's daily puzzle." else "")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep Playing") } }
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/** Edge length of one cell, in px, for a square board drawn into a [widthPx] x [heightPx] Canvas.
 *  Drawing AND hit-testing both call this with the Canvas's own measured size, so they cannot
 *  disagree about where a cell is. */
private fun ngCellEdgePx(widthPx: Float, heightPx: Float, n: Int): Float = minOf(widthPx, heightPx) / n

/** The row or column (clamped onto the board) a coordinate along one axis falls in. */
private fun ngCellAt(coord: Float, edgePx: Float, n: Int): Int = (coord / edgePx).toInt().coerceIn(0, n - 1)

/** "Filled, row 4, column 7": the same state-then-1-indexed-position phrasing as ReversiScreen. */
private fun nonogramCellDescription(cell: NonogramCellState, inSolution: Boolean, row: Int, col: Int): String {
    val base = when (cell) {
        NonogramCellState.FILLED -> if (inSolution) "Filled" else "Filled, wrong"
        NonogramCellState.MARKED_EMPTY -> "Crossed out"
        NonogramCellState.UNDETERMINED -> "Empty"
    }
    return "$base, row ${row + 1}, column ${col + 1}"
}

private fun nonogramClickLabel(tool: NonogramTool, cell: NonogramCellState): String = when (tool) {
    NonogramTool.FILL -> if (cell == NonogramCellState.FILLED) "Clear square" else "Fill square"
    NonogramTool.CROSS -> if (cell == NonogramCellState.MARKED_EMPTY) "Clear cross" else "Cross out square"
}

/**
 * The board slot: row clues, column clues and the cell grid. Sizes from THIS slot's own measured
 * space (not an outer scope) with [fitBoard], after subtracting the clue gutters, which are
 * themselves measured from the real clue text. When the slot cannot give a cell the minimum, the
 * board keeps that size and pans in whichever axis overflows; the gutters stay pinned to the pan.
 */
@Composable
private fun NonogramBoardSlot(
    s: NonogramState,
    lineStatus: NonogramLineStatus,
    tool: NonogramTool,
    palette: NonogramPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    onStrokeStart: (Int) -> Unit,
    onStrokeMove: (Int, Int) -> Unit,
    onStrokeEnd: () -> Unit,
    onToggleCell: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val n = s.size

    // A new puzzle starts at its top-left corner, where the first clues are.
    LaunchedEffect(s.solution) {
        hScroll.scrollTo(0)
        vScroll.scrollTo(0)
    }

    // Solving fades the cross marks and thin grid lines out so the finished picture reads alone.
    // Read inside the Canvas draw block, so a frame of the fade redraws without recomposing.
    val revealState = animateFloatAsState(
        targetValue = if (s.won && !reducedMotion) 1f else 0f,
        animationSpec = tween(NG_REVEAL_MS),
        label = "nonogramReveal"
    )

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val slotWidth = maxWidth
        val slotHeight = maxHeight

        // Clue text: a label style (follows the app's text-size setting; sp follows the system font
        // scale), a little larger when the slot is roomy. Plain letterSpacing so the measured width
        // is the rendered width.
        val clueBase = if (slotWidth >= 480.dp) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium
        val clueStyle = clueBase.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
            lineHeight = clueBase.fontSize * 1.2f
        )
        val rowTexts = remember(s.rowClues) { s.rowClues.map { if (it.isEmpty()) "0" else it.joinToString(" ") } }
        val rowTextPx = remember(rowTexts, clueStyle, measurer) {
            rowTexts.maxOf { measurer.measure(text = it, style = clueStyle).size.width }
        }
        val colNumberPx = remember(s.colClues, clueStyle, measurer) {
            val widest = s.colClues.flatten().maxOrNull() ?: 0
            measurer.measure(text = widest.toString(), style = clueStyle).size.width
        }
        val lineHeightPx = remember(clueStyle, measurer) {
            measurer.measure(text = "0", style = clueStyle).size.height
        }
        val maxColLines = remember(s.colClues) { s.colClues.maxOf { it.size.coerceAtLeast(1) } }

        val gutterWidth: Dp = with(density) { rowTextPx.toDp() } + NG_GUTTER_SLACK_DP.dp
        val lineHeightDp: Dp = with(density) { lineHeightPx.toDp() }
        val gutterHeight: Dp = lineHeightDp * maxColLines + 6.dp
        val minCellDp = maxOf(NG_MIN_CELL_DP, with(density) { colNumberPx.toDp() }.value + 8f)
            .coerceAtMost(NG_MAX_CELL_DP)

        val availWidthDp = (slotWidth - gutterWidth).value.coerceAtLeast(0f)
        val availHeightDp = (slotHeight - gutterHeight).value.coerceAtLeast(0f)
        val fit = fitBoard(
            availableWidthPx = availWidthDp,
            availableHeightPx = availHeightDp,
            columns = n,
            rows = n,
            minCellPx = minCellDp,
            maxCellPx = NG_MAX_CELL_DP
        )
        val scrolls = !fit.meetsMinimum
        // Cells are whole pixels, so n of them never add up to more than the space they were fitted to.
        val cellPx: Int = if (scrolls) {
            with(density) { minCellDp.dp.roundToPx() }
        } else {
            floor(fit.cellPx * density.density).toInt().coerceAtLeast(1)
        }
        val boardPx = cellPx * n
        val availWidthPx = floor(availWidthDp * density.density).toInt().coerceAtLeast(0)
        val availHeightPx = floor(availHeightDp * density.density).toInt().coerceAtLeast(0)
        val viewportWidthPx = minOf(boardPx, availWidthPx)
        val viewportHeightPx = minOf(boardPx, availHeightPx)
        val scrollX = boardPx > viewportWidthPx
        val scrollY = boardPx > viewportHeightPx

        val cellDp = with(density) { cellPx.toDp() }
        val viewportWidth = with(density) { viewportWidthPx.toDp() }
        val viewportHeight = with(density) { viewportHeightPx.toDp() }

        val scrollModifier = Modifier
            .then(if (scrollX) Modifier.horizontalScroll(hScroll) else Modifier)
            .then(if (scrollY) Modifier.verticalScroll(vScroll) else Modifier)

        Column {
            Row {
                Spacer(Modifier.size(width = gutterWidth, height = gutterHeight))
                // The column clues sit in a clipped box and translate with the board's horizontal
                // pan, so they stay above the columns they describe.
                Box(modifier = Modifier.width(viewportWidth).height(gutterHeight).clipToBounds()) {
                    NonogramColClues(
                        clues = s.colClues,
                        satisfied = lineStatus.cols,
                        cellDp = cellDp,
                        heightDp = gutterHeight,
                        lineHeightDp = lineHeightDp,
                        style = clueStyle,
                        palette = palette,
                        // wrapContentWidth(Start, unbounded) lets the row take its full board width
                        // and pins its left edge to the box's; requiredWidth would instead CENTER an
                        // oversized row on the box and misalign every clue by half the overflow.
                        modifier = Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .graphicsLayer { translationX = if (scrollX) -hScroll.value.toFloat() else 0f }
                    )
                }
            }
            Row {
                Box(modifier = Modifier.width(gutterWidth).height(viewportHeight).clipToBounds()) {
                    NonogramRowClues(
                        clues = s.rowClues,
                        satisfied = lineStatus.rows,
                        cellDp = cellDp,
                        widthDp = gutterWidth,
                        style = clueStyle,
                        palette = palette,
                        modifier = Modifier
                            .wrapContentHeight(Alignment.Top, unbounded = true)
                            .graphicsLayer { translationY = if (scrollY) -vScroll.value.toFloat() else 0f }
                    )
                }
                Box(modifier = Modifier.size(width = viewportWidth, height = viewportHeight).then(scrollModifier)) {
                    NonogramBoardGrid(
                        s = s,
                        cellPx = cellPx,
                        tool = tool,
                        selectOnLongPress = scrolls,
                        palette = palette,
                        colorblind = colorblind,
                        revealState = revealState,
                        onStrokeStart = onStrokeStart,
                        onStrokeMove = onStrokeMove,
                        onStrokeEnd = onStrokeEnd,
                        onToggleCell = onToggleCell
                    )
                }
            }
        }
    }
}

/** The row clues, one per row, right-aligned against the grid. Each is one screen-reader node. */
@Composable
private fun NonogramRowClues(
    clues: List<List<Int>>,
    satisfied: List<Boolean>,
    cellDp: Dp,
    widthDp: Dp,
    style: TextStyle,
    palette: NonogramPalette,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.width(widthDp)) {
        for (r in clues.indices) {
            val clue = clues[r]
            val done = satisfied.getOrElse(r) { false }
            val text = if (clue.isEmpty()) "0" else clue.joinToString(" ")
            val spoken = "Row ${r + 1} clue " + (if (clue.isEmpty()) "0, empty line" else text) +
                (if (done) ", matched" else "")
            Box(
                modifier = Modifier
                    .width(widthDp)
                    .height(cellDp)
                    .padding(end = 6.dp)
                    .clearAndSetSemantics { contentDescription = spoken },
                contentAlignment = Alignment.CenterEnd
            ) {
                // Satisfied = struck through AND dimmer ink (4.5:1 or better): the strike is the
                // non-color cue. softWrap off plus unbounded width: a clue never wraps into the next row.
                Text(
                    text,
                    style = style,
                    color = if (done) palette.clueDone else palette.textPrimary,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.wrapContentWidth(Alignment.End, unbounded = true)
                )
            }
        }
    }
}

/** The column clues, one stack of numbers per column, bottom-aligned against the grid. */
@Composable
private fun NonogramColClues(
    clues: List<List<Int>>,
    satisfied: List<Boolean>,
    cellDp: Dp,
    heightDp: Dp,
    lineHeightDp: Dp,
    style: TextStyle,
    palette: NonogramPalette,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier) {
        for (c in clues.indices) {
            val clue = clues[c]
            val done = satisfied.getOrElse(c) { false }
            val numbers = if (clue.isEmpty()) listOf(0) else clue
            val spoken = "Column ${c + 1} clue " + (if (clue.isEmpty()) "0, empty line" else clue.joinToString(" ")) +
                (if (done) ", matched" else "")
            Column(
                modifier = Modifier
                    .width(cellDp)
                    .height(heightDp)
                    .padding(bottom = 3.dp)
                    .clearAndSetSemantics { contentDescription = spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                for (number in numbers) {
                    Box(modifier = Modifier.height(lineHeightDp), contentAlignment = Alignment.Center) {
                        Text(
                            number.toString(),
                            style = style,
                            color = if (done) palette.clueDone else palette.textPrimary,
                            textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The cell grid. LAYERS, bottom to top: (1) a column of per-cell click targets that exist only for
 * screen readers, switch access and keyboards (description plus a click action that marks the cell
 * with the current tool); (2) the Canvas, which draws everything and owns the touch handling. The
 * Canvas is above the click layer on purpose: Compose delivers a touch to the topmost sibling that
 * has pointer input, so a finger always reaches the Canvas while TalkBack still finds the cells
 * below it (the Canvas carries no semantics of its own).
 *
 * TOUCH. Board fits ([selectOnLongPress] false): one gesture loop paints on touch-down and follows
 * the finger, so a tap and a drag are the same code and nothing waits on a touch-slop. Board pans
 * ([selectOnLongPress] true): a tap marks one cell, a plain drag is left to the scroll container,
 * and touch-and-hold then drag paints (the same trade Word Search makes).
 */
@Composable
private fun NonogramBoardGrid(
    s: NonogramState,
    cellPx: Int,
    tool: NonogramTool,
    selectOnLongPress: Boolean,
    palette: NonogramPalette,
    colorblind: Boolean,
    revealState: State<Float>,
    onStrokeStart: (Int) -> Unit,
    onStrokeMove: (Int, Int) -> Unit,
    onStrokeEnd: () -> Unit,
    onToggleCell: (Int) -> Unit
) {
    val density = LocalDensity.current
    val n = s.size
    val cellDp = with(density) { cellPx.toDp() }
    val boardDp = with(density) { (cellPx * n).toDp() }

    // The gesture blocks below are keyed on the layout, not on these, so they must read the latest
    // callbacks through State rather than capture the first composition's copy.
    val currentStart by rememberUpdatedState(onStrokeStart)
    val currentMove by rememberUpdatedState(onStrokeMove)
    val currentEnd by rememberUpdatedState(onStrokeEnd)
    val currentToggle by rememberUpdatedState(onToggleCell)
    val activate: (Int) -> Unit = remember { { index: Int -> currentToggle(index) } }

    val descriptions = remember(s.cells, s.solution) {
        List(n * n) { i -> nonogramCellDescription(s.cells[i], s.solution[i], i / n, i % n) }
    }

    val gestures = if (selectOnLongPress) {
        Modifier
            .pointerInput(n) {
                detectTapGestures(
                    onTap = { offset ->
                        val edge = ngCellEdgePx(size.width.toFloat(), size.height.toFloat(), n)
                        if (edge > 0f) {
                            currentToggle(ngCellAt(offset.y, edge, n) * n + ngCellAt(offset.x, edge, n))
                        }
                    }
                )
            }
            .pointerInput(n) {
                // The tap detector above is the outer modifier and this one the inner, so on a
                // long-press drag this one sees (and consumes) the release first and no tap fires.
                var lastRow = 0
                var lastCol = 0
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val edge = ngCellEdgePx(size.width.toFloat(), size.height.toFloat(), n)
                        if (edge > 0f) {
                            lastRow = ngCellAt(offset.y, edge, n)
                            lastCol = ngCellAt(offset.x, edge, n)
                            currentStart(lastRow * n + lastCol)
                        }
                    },
                    onDragEnd = { currentEnd() },
                    onDragCancel = { currentEnd() },
                    onDrag = { change, _ ->
                        change.consume()
                        val edge = ngCellEdgePx(size.width.toFloat(), size.height.toFloat(), n)
                        if (edge > 0f) {
                            val row = ngCellAt(change.position.y, edge, n)
                            val col = ngCellAt(change.position.x, edge, n)
                            if (row != lastRow || col != lastCol) {
                                lastRow = row
                                lastCol = col
                                currentMove(row, col)
                            }
                        }
                    }
                )
            }
    } else {
        Modifier.pointerInput(n) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val edge = ngCellEdgePx(size.width.toFloat(), size.height.toFloat(), n)
                if (edge <= 0f) return@awaitEachGesture
                down.consume()
                var lastRow = ngCellAt(down.position.y, edge, n)
                var lastCol = ngCellAt(down.position.x, edge, n)
                currentStart(lastRow * n + lastCol)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val row = ngCellAt(change.position.y, edge, n)
                        val col = ngCellAt(change.position.x, edge, n)
                        if (row != lastRow || col != lastCol) {
                            lastRow = row
                            lastCol = col
                            currentMove(row, col)
                        }
                        change.consume()
                    }
                } finally {
                    currentEnd()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .size(boardDp)
            .semantics { contentDescription = "Nonogram grid, $n by $n" }
    ) {
        Column {
            for (row in 0 until n) {
                Row {
                    for (col in 0 until n) {
                        val index = row * n + col
                        NonogramA11yCell(
                            index = index,
                            description = descriptions[index],
                            clickLabel = nonogramClickLabel(tool, s.cells[index]),
                            enabled = !s.won,
                            cellDp = cellDp,
                            onActivate = activate
                        )
                    }
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize().then(gestures)) {
            drawNonogramBoard(
                n = n,
                cells = s.cells,
                solution = s.solution,
                palette = palette,
                colorblind = colorblind,
                reveal = revealState.value
            )
        }
    }
}

/** One invisible per-cell target for accessibility services (see [NonogramBoardGrid]). */
@Composable
private fun NonogramA11yCell(
    index: Int,
    description: String,
    clickLabel: String,
    enabled: Boolean,
    cellDp: Dp,
    onActivate: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .size(cellDp)
            .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button) { onActivate(index) }
            .semantics { contentDescription = description }
    )
}

/**
 * Draws the whole board into a Canvas whose own size is the board: paper, fills (a mistake is
 * [NonogramPalette.danger]), thin cell lines, a heavier line every [NG_BLOCK] cells and around the
 * board, then the marks. A cross is an X on an empty square; a mistake carries a diagonal slash in
 * [NonogramPalette.slashInk] (heavier and outlined under [colorblind]), so neither is colour alone.
 * [reveal] (0 to 1, the solved-board fade) fades out the crosses and thin lines.
 */
private fun DrawScope.drawNonogramBoard(
    n: Int,
    cells: List<NonogramCellState>,
    solution: List<Boolean>,
    palette: NonogramPalette,
    colorblind: Boolean,
    reveal: Float
) {
    val cell = ngCellEdgePx(size.width, size.height, n)
    val board = cell * n

    drawRect(color = palette.undeterminedCell, topLeft = Offset.Zero, size = Size(board, board))

    for (i in cells.indices) {
        if (cells[i] != NonogramCellState.FILLED) continue
        drawRect(
            color = if (solution[i]) palette.filledCell else palette.danger,
            topLeft = Offset((i % n) * cell, (i / n) * cell),
            size = Size(cell, cell)
        )
    }

    val thin = maxOf(1f, 0.75.dp.toPx())
    val thick = 2.dp.toPx()
    val thinColor = palette.gridLine.copy(alpha = 1f - 0.8f * reveal)
    for (k in 1 until n) {
        if (k % NG_BLOCK == 0) continue
        val p = k * cell
        drawLine(color = thinColor, start = Offset(p, 0f), end = Offset(p, board), strokeWidth = thin)
        drawLine(color = thinColor, start = Offset(0f, p), end = Offset(board, p), strokeWidth = thin)
    }
    for (k in 1 until n) {
        if (k % NG_BLOCK != 0) continue
        val p = k * cell
        drawLine(color = palette.blockLine, start = Offset(p, 0f), end = Offset(p, board), strokeWidth = thick)
        drawLine(color = palette.blockLine, start = Offset(0f, p), end = Offset(board, p), strokeWidth = thick)
    }
    drawRect(
        color = palette.blockLine,
        topLeft = Offset(thick / 2f, thick / 2f),
        size = Size(board - thick, board - thick),
        style = Stroke(width = thick)
    )

    val crossColor = palette.crossMark.copy(alpha = 1f - reveal)
    val crossWidth = maxOf(1.5.dp.toPx(), cell * 0.07f)
    val crossPad = cell * 0.3f
    val slashPad = cell * 0.22f
    val slashWidth = cell * (if (colorblind) 0.16f else 0.1f)
    val outlineInset = 3.dp.toPx()
    val outlineWidth = 2.dp.toPx()
    for (i in cells.indices) {
        val x = (i % n) * cell
        val y = (i / n) * cell
        when (cells[i]) {
            NonogramCellState.MARKED_EMPTY -> if (reveal < 1f) {
                drawLine(
                    color = crossColor,
                    start = Offset(x + crossPad, y + crossPad),
                    end = Offset(x + cell - crossPad, y + cell - crossPad),
                    strokeWidth = crossWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = crossColor,
                    start = Offset(x + cell - crossPad, y + crossPad),
                    end = Offset(x + crossPad, y + cell - crossPad),
                    strokeWidth = crossWidth,
                    cap = StrokeCap.Round
                )
            }
            NonogramCellState.FILLED -> if (!solution[i]) {
                drawLine(
                    color = palette.slashInk,
                    start = Offset(x + slashPad, y + cell - slashPad),
                    end = Offset(x + cell - slashPad, y + slashPad),
                    strokeWidth = slashWidth,
                    cap = StrokeCap.Round
                )
                if (colorblind) {
                    drawRect(
                        color = palette.slashInk,
                        topLeft = Offset(x + outlineInset, y + outlineInset),
                        size = Size(cell - 2f * outlineInset, cell - 2f * outlineInset),
                        style = Stroke(width = outlineWidth)
                    )
                }
            }
            NonogramCellState.UNDETERMINED -> Unit
        }
    }
}

// ---------------------------------------------------------------------------
// Result panel
// ---------------------------------------------------------------------------

@Composable
private fun NonogramResultPanel(
    timeMillis: Long?,
    mistakes: Int,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    daily: Boolean,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: NonogramPalette,
    modifier: Modifier = Modifier
) {
    // Opaque (unlike a translucent tint) because on a phone this panel overlays the board's lower part.
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = palette.chipBackground,
            contentColor = palette.textPrimary
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (daily) "Daily puzzle solved!" else "Solved!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (timeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text("Time: ${nonogramFormatClock(timeMillis)}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (mistakes == 0) "No mistakes" else if (mistakes == 1) "1 mistake" else "$mistakes mistakes",
                style = MaterialTheme.typography.bodyMedium
            )
            if (isNewBest) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", fontWeight = FontWeight.Bold)
            } else if (bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best: ${nonogramFormatClock(bestTimeMillis)}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onNewPuzzle,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.textPrimary, contentColor = palette.background),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("New Puzzle", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Back to Menu", textAlign = TextAlign.Center) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this batch.
// ---------------------------------------------------------------------------
private data class NonogramPalette(
    val background: Color,
    val undeterminedCell: Color,
    val filledCell: Color,
    /** Thin cell lines: 3:1 or better against [undeterminedCell] (they were 1.5:1). */
    val gridLine: Color,
    /** The heavier every-5-cells line and the board's outer edge. */
    val blockLine: Color,
    /** The X drawn on a crossed-out square (6:1 or better; it was a 2.5:1 alpha fade). */
    val crossMark: Color,
    val accent: Color,
    val textPrimary: Color,
    /** Ink of a satisfied clue: dimmer than [textPrimary] but 4.5:1 or better on [background]. */
    val clueDone: Color,
    val textOnAccent: Color,
    /** The slash drawn through a mistake square, readable on [danger]. */
    val slashInk: Color,
    val chipBackground: Color,
    val danger: Color
)

private val NonogramLightPalette = NonogramPalette(
    background = Color(0xFFFBF1E6),
    undeterminedCell = Color(0xFFFFFBF5),
    filledCell = Color(0xFF3A2E22),
    gridLine = Color(0xFF9A8266),
    blockLine = Color(0xFF5E4B37),
    crossMark = Color(0xFF6B5A47),
    accent = Color(0xFFE08D4B),
    textPrimary = Color(0xFF3A2E22),
    clueDone = Color(0xFF7A6A58),
    // Dark ink: cream text on this orange was only ~2.5:1 on the selected chip.
    textOnAccent = Color(0xFF2B1F12),
    slashInk = Color(0xFFFFFBF5),
    chipBackground = Color(0xFFE3CBA9),
    danger = Color(0xFFD9573F)
)

private val NonogramDarkPalette = NonogramPalette(
    background = Color(0xFF1C1712),
    undeterminedCell = Color(0xFF15110D),
    filledCell = Color(0xFFF3E9DB),
    gridLine = Color(0xFF7A6A58),
    blockLine = Color(0xFFC2B093),
    crossMark = Color(0xFFC9B9A3),
    accent = Color(0xFFE8985B),
    textPrimary = Color(0xFFF3E9DB),
    clueDone = Color(0xFFA8977F),
    textOnAccent = Color(0xFF1C1712),
    slashInk = Color(0xFF1C1712),
    chipBackground = Color(0xFF453A2E),
    danger = Color(0xFFE0705A)
)

/** Shared instances, so a recomposition never allocates a fresh palette again. */
private fun nonogramPalette(isDark: Boolean): NonogramPalette = if (isDark) NonogramDarkPalette else NonogramLightPalette
