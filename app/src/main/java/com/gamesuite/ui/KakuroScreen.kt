package com.gamesuite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.kakuro.KakuroCell
import com.gamesuite.games.kakuro.KakuroCellType
import com.gamesuite.games.kakuro.KakuroGame
import com.gamesuite.games.kakuro.KakuroRunInfo
import com.gamesuite.games.kakuro.KakuroRunProgress
import com.gamesuite.games.kakuro.KakuroRuns
import com.gamesuite.games.kakuro.KakuroState
import com.gamesuite.games.kakuro.KakuroStatsStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** Below this cell size (dp) the board stops shrinking and scrolls/pans instead. */
private const val KAKURO_MIN_CELL_DP = 28f

/** Upper bound so an EASY board does not sprawl across a tablet (6 x 72 = a 432dp board). */
private const val KAKURO_MAX_CELL_DP = 72f

/** Padding between the board's outer rim and its cells (dp). Real `padding` of the frame AND the `framePx` handed to [fitBoard], so the two can never drift apart. */
private const val KAKURO_BOARD_FRAME_DP = 3f

/** The number pad and the result panel never grow wider than this, so they do not stretch across a tablet. */
private const val KAKURO_CONTROLS_MAX_WIDTH_DP = 420

/** The result panel fades in over this long. Instant under [LocalReducedMotion]. */
private const val KAKURO_RESULT_FADE_MS = 250

private const val KAKURO_HELP_TEXT =
    "Fill every white cell with a digit from 1 to 9 so that each run of white cells adds up to its " +
        "clue. A clue cell's lower-left number is the sum for the run going across to its right, its " +
        "upper-right number is the sum for the run going down below it, and no digit may repeat " +
        "within a run.\n\n" +
        "Tap a white cell, then a digit, or switch on Notes to pencil in candidates instead; the " +
        "selected cell's runs and their clues are highlighted, and the line under the board shows " +
        "how close each sum is.\n\n" +
        "A digit that doesn't match the solution is struck through and counts as a mistake. Your clock " +
        "starts on your first digit and your best time is saved per difficulty (Easy 6x6, Medium 9x9, " +
        "Hard 12x12, counting the clue border). The daily puzzle is the same for every player on the " +
        "same difficulty."

/**
 * Renders KakuroGame's state reactively: difficulty chips, a live mistakes / clock status block, the
 * board, a run-sum helper line, a number pad with a notes-mode switch, and a solved-puzzle panel.
 * The board keeps Kakuro's own signature look: BLACK clue cells split by a diagonal, the DOWN sum
 * in the upper-right half and the ACROSS sum in the lower-left half, drawn on a dark frame.
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, Back to Menu) plus its BackHandler.
 * A "finished unit" here is a solved puzzle ([KakuroGame.puzzlesSolved]). Leaving mid-puzzle
 * discards ONLY the unfinished puzzle: with nothing solved yet it is a pure `abortMatch()` (never
 * recorded), but once the session has solved puzzles it goes through `leaveSession()`, the same
 * call the solved panel's "Back to Menu" makes, so those solves still count (the confirm dialog
 * says so). The status block reserves [GameChromeEndInset] so the corner button never covers it.
 *
 * DAILY PUZZLE: [dailySeed] pins the first puzzle only, tagged "Daily puzzle". A difficulty switch
 * or "New puzzle" always deals a random puzzle and drops the tag; either asks first while the
 * puzzle has entries on it or is the daily, so a stray tap can no longer silently throw away
 * progress or today's puzzle.
 *
 * OFF THE UI THREAD: generation (a reject-and-retry search, slow on HARD) runs on
 * `Dispatchers.Default` via [KakuroGame.generate] and is applied on the main thread with
 * [KakuroGame.beginPuzzle]. The first board shows a "Dealing a puzzle" placeholder (still inside
 * the chrome, so Back works); a later deal keeps the old board on screen, inert, under a
 * "Dealing a new puzzle" chip, and the chips, the board and the engine's difficulty switch together.
 *
 * LAYOUT: [fitBoard] against the measured space of the board's own slot, the frame padding handed
 * to it as `framePx`, a [KAKURO_MIN_CELL_DP] to [KAKURO_MAX_CELL_DP] clamp, and a scroll/pan
 * fallback (never a board bigger than its container) when the slot cannot give a cell
 * [KAKURO_MIN_CELL_DP]: HARD (12 x 12) pans on the Fold cover screen. Portrait stacks status /
 * chips / board / controls; a wide landscape window puts the board on the left and status, chips
 * and controls in a scrolling column on the right. The controls swap for the result panel on a
 * solve, so the finished board stays in view (the board only grows if the panel is shorter than
 * the pad it replaces, and only when its slot is height-limited).
 *
 * RUN CONTEXT: selecting a cell tints its across and down runs, rings their clue cells, and the
 * helper line says how each sum stands ("Across 17: 12 so far, 5 to go"). Digits already placed in
 * either run are dimmed on the pad (still tappable: they may need correcting). A clue whose run is
 * full, correct and repeat-free fades out. The run maths lives in [KakuroRuns] (unit-tested).
 *
 * CLUE LEGIBILITY: clue numbers are sized in dp (never below 10), not sp, so the system font scale
 * cannot push a two-digit sum out of its triangle; the helper line is ordinary scaling text, so a
 * large font setting still has a readable copy of the selected sums.
 *
 * ACCESSIBILITY: every clue cell is one node ("Across 17, down 23, row 2, column 1"; filler black
 * cells are skipped); every white cell is a button described by its state plus 1-indexed row and
 * column (matching ReversiScreen.CellView); the mistakes counter and the helper line are polite
 * live regions; difficulty chips are radio buttons and Notes is a switch; the pad keys and every
 * other non-grid control are at least 48dp. A wrong digit is struck through (a shape cue, drawn in
 * the digit's own colour so it reads on the orange selected cell where the danger colour alone
 * measured about 1.5:1); the selected cell has a heavy inner border; clue and "done" cues are
 * luminance and shape. With [LocalColorblindMode] the tinted run cells also get a thin outline.
 *
 * FEEDBACK/MOTION: a wrong digit buzzes and gives the failure haptic; a solve gives the success
 * chime and celebration haptic (a solve is always the human's). The result panel fades in over
 * 250ms, instant under [LocalReducedMotion].
 *
 * Music: [MusicProfiles.PUZZLE_FOCUS] directly, matching EdgeMatchScreen's/NonogramScreen's own
 * choice for a quiet solo puzzle.
 *
 * VISUAL IDENTITY: shares this batch's warm Chogan-inspired tokens (see [kakuroPalette]) and never
 * reads `MaterialTheme.colorScheme` for gameplay colors. In the dark theme the clue cells stay the
 * light, inverted ones they always were.
 */
@Composable
fun KakuroScreen(
    sessionManager: GameSessionManager,
    game: KakuroGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's puzzle for every player -- see KakuroGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { KakuroStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = kakuroPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val scope = rememberCoroutineScope()

    var notesMode by remember { mutableStateOf(false) }
    // True while the puzzle on screen is the seeded daily one. Any later fresh puzzle (a tier
    // switch, "New puzzle") is random again, and the screen says so.
    var dailyBoardActive by remember { mutableStateOf(dailySeed != null) }
    // True while a new puzzle is being generated off the main thread (the old board stays up).
    var generating by remember { mutableStateOf(false) }
    // A tier chip or "New puzzle" tapped while a puzzle is in progress waits here for confirmation.
    var pendingDeal by remember { mutableStateOf<KakuroDealRequest?>(null) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        dailyBoardActive = dailySeed != null
        val tier = game.difficulty
        // HARD can take a while to generate: never on the main thread.
        val puzzle = withContext(Dispatchers.Default) { game.generate(tier, dailySeed) }
        // The player may have backed out while it was generating; never revive a finished session.
        if (!game.matchOver.value) game.beginPuzzle(puzzle)
    }

    val s = state
    if (s == null) {
        KakuroLoading(game = game, palette = palette)
        return
    }

    val allBestTimes by statsStore.bestTimesMillis.collectAsState(initial = emptyMap())
    val bestTimeMillis = allBestTimes[game.difficulty.name]
    var reportedNewBest by remember { mutableStateOf(false) }

    LaunchedEffect(s.won) {
        if (!s.won) {
            reportedNewBest = false
            return@LaunchedEffect
        }
        // A solve is always the human's (this is a solo game), so the celebration is unconditional.
        // Feedback first: the chime and the haptic must not wait on the DataStore write below.
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
        reportedNewBest = statsStore.recordWin(game.difficulty, finalTime)
    }

    // Derived once per change of the cells / selection, not once per cell per frame.
    val selection = remember(s.cells, s.selectedIndex) { kakuroSelection(s) }
    val completedClues = remember(s.cells) { KakuroRuns.completedClueKeys(s) }
    val hasProgress = remember(s.cells) { s.cells.any { it.value != null || it.notes.isNotEmpty() } }

    val dealPuzzle: (CpuDifficulty) -> Unit = { tier ->
        if (!generating && !game.matchOver.value) {
            generating = true
            scope.launch {
                try {
                    val puzzle = withContext(Dispatchers.Default) { game.generate(tier, null) }
                    if (!game.matchOver.value) {
                        game.beginPuzzle(puzzle)
                        dailyBoardActive = false
                        notesMode = false
                    }
                } finally {
                    generating = false
                }
            }
        }
    }
    // Only a puzzle with entries on it (or an untouched daily one) is worth a confirmation; a
    // solved puzzle, or a blank random one, can be left behind freely.
    val roundInProgress = !s.isOver && (hasProgress || dailyBoardActive)
    val requestDeal: (CpuDifficulty?) -> Unit = { tier ->
        if (!generating && !game.matchOver.value && (tier == null || tier != game.difficulty)) {
            if (roundInProgress) {
                pendingDeal = KakuroDealRequest(tier)
            } else {
                dealPuzzle(tier ?: game.difficulty)
            }
        }
    }

    val onTapCell: (Int) -> Unit = { index ->
        // Re-read the live state: the flag a cell was composed with can be a frame stale, and a tap
        // on a solved board (or after the session ended) must not buzz, click or select.
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value && !generating) {
            game.selectCell(index)
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val onDigit: (Int) -> Unit = { digit ->
        val live = game.state.value
        val selected = live?.selectedIndex
        if (live != null && selected != null && !live.isOver && !game.matchOver.value && !generating) {
            if (notesMode) {
                // A cell that holds a digit takes no pencil marks (the engine ignores it too).
                if (live.cells[selected].value == null) {
                    game.toggleNote(digit)
                    haptics(HapticSignal.LIGHT_TICK)
                }
            } else {
                game.setValue(digit)
                val after = game.state.value
                when {
                    after == null -> Unit
                    after.mistakes > live.mistakes -> {
                        haptics(HapticSignal.FAILURE)
                        playSfx(SfxKind.INVALID_BUZZ)
                    }
                    // The solving digit gets the celebration from the effect above instead.
                    !after.won -> {
                        sounds.playTap()
                        haptics(HapticSignal.NORMAL_ACTION)
                    }
                    else -> Unit
                }
            }
        }
    }
    val onErase: () -> Unit = {
        val live = game.state.value
        val selected = live?.selectedIndex
        if (live != null && selected != null && !live.isOver && !game.matchOver.value && !generating &&
            live.cells[selected].value != null
        ) {
            game.clearValue()
            haptics(HapticSignal.LIGHT_TICK)
        }
    }

    // "Finished units" for the abort policy: puzzles already solved this session. If any exist,
    // leaving mid-puzzle must still score them -- see onAbort below.
    val finishedPuzzles = game.puzzlesSolved.value

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-puzzle discards
    // ONLY the unfinished puzzle: if puzzles were already solved this session, leaving goes through
    // leaveSession() (the same call the solved panel's "Back to Menu" makes) so they still count;
    // with nothing solved it is a pure abort (never a win or loss). A solved puzzle leaves through
    // leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Kakuro",
        helpText = KAKURO_HELP_TEXT,
        matchInProgress = !s.isOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedPuzzles > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this puzzle?",
        leaveBody = if (finishedPuzzles > 0) {
            "This puzzle is still unsolved and won't count, but the puzzles you've already solved stay on your record."
        } else {
            "This puzzle is still unsolved. Leaving now won't count it as a win or a loss."
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
            val sideBySide = maxWidth >= 560.dp && maxWidth > maxHeight * 1.2f

            val statusBlock: @Composable (Modifier) -> Unit = { statusModifier ->
                KakuroStatus(
                    mistakes = s.mistakes,
                    daily = dailyBoardActive,
                    generating = generating,
                    game = game,
                    onNewPuzzle = { requestDeal(null) },
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val tiersRow: @Composable () -> Unit = {
                KakuroDifficultyTabs(
                    current = game.difficulty,
                    palette = palette,
                    onSelect = { tier -> requestDeal(tier) }
                )
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                KakuroBoardSlot(
                    s = s,
                    selection = selection,
                    completedClues = completedClues,
                    enabled = !s.isOver && !generating,
                    generating = generating,
                    palette = palette,
                    colorblind = colorblind,
                    onTapCell = onTapCell,
                    modifier = slotModifier
                )
            }
            val controlsBlock: @Composable (Modifier) -> Unit = { controlsModifier ->
                val resultEnter = if (reducedMotion) EnterTransition.None else fadeIn(animationSpec = tween(KAKURO_RESULT_FADE_MS))
                Column(modifier = controlsModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!s.isOver) {
                        KakuroRunStrip(lines = selection.summary, palette = palette)
                        Spacer(Modifier.height(6.dp))
                        KakuroNumberPad(
                            notesMode = notesMode,
                            enabled = s.selectedIndex != null && !generating,
                            usedDigits = selection.usedDigits,
                            onDigit = onDigit,
                            onErase = onErase,
                            onNotesChange = { notesMode = it },
                            palette = palette
                        )
                    }
                    AnimatedVisibility(visible = s.isOver, enter = resultEnter, exit = ExitTransition.None) {
                        KakuroResultPanel(
                            daily = dailyBoardActive,
                            mistakes = s.mistakes,
                            timeMillis = game.finishedElapsedMillis.value,
                            bestTimeMillis = bestTimeMillis,
                            isNewBest = reportedNewBest,
                            puzzlesSolved = finishedPuzzles,
                            generating = generating,
                            onNewPuzzle = { dealPuzzle(game.difficulty) },
                            onBackToMenu = { game.leaveSession() },
                            palette = palette,
                            // Capped so even a large font scale cannot push the board out of its
                            // slot above; the panel scrolls inside its own cap instead.
                            modifier = Modifier
                                .widthIn(max = KAKURO_CONTROLS_MAX_WIDTH_DP.dp)
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        tiersRow()
                        controlsBlock(Modifier.fillMaxWidth())
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
                    controlsBlock(Modifier.fillMaxWidth())
                }
            }
        }
    }

    pendingDeal?.let { request ->
        val tier = request.tier
        AlertDialog(
            onDismissRequest = { pendingDeal = null },
            containerColor = palette.background,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = { Text(if (tier != null) "Switch to ${kakuroTierLabel(tier)}?" else "Start a new puzzle?") },
            text = {
                Text(
                    if (dailyBoardActive) {
                        "This starts a new random puzzle and leaves today's daily puzzle. Your entries on this puzzle are discarded."
                    } else {
                        "This starts a new puzzle. Your entries on this puzzle are discarded."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeal = null
                        dealPuzzle(tier ?: game.difficulty)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text(if (tier != null) "Switch" else "New Puzzle", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeal = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text("Keep Playing") }
            }
        )
    }
}

/** What a tapped difficulty chip (or the New puzzle button, [tier] null) wants once the player confirms. */
private class KakuroDealRequest(val tier: CpuDifficulty?)

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- own private copy, not shared with sibling
// screens (every screen in this app duplicates this small set of tokens
// rather than sharing one), matching this batch's standard hex values.
// ---------------------------------------------------------------------------
private data class KakuroPalette(
    val background: Color,
    val cellBackground: Color,
    val selectedCell: Color,
    /** Fill of the white cells in the selected cell's two runs. */
    val runTint: Color,
    /** Clue cells and the frame around the board. */
    val blackCell: Color,
    val clueText: Color,
    val accent: Color,
    val textPrimary: Color,
    /** Dark ink for anything drawn on [accent] / [selectedCell] (cream on that orange was only ~2.5:1). */
    val textOnAccent: Color,
    val noteText: Color,
    val chipBackground: Color,
    val danger: Color
)

private val KakuroLightPalette = KakuroPalette(
    background = Color(0xFFFBF1E6),
    cellBackground = Color(0xFFFFFBF5),
    selectedCell = Color(0xFFE08D4B),
    runTint = Color(0xFFF7DFCB),
    blackCell = Color(0xFF3A2E22),
    clueText = Color(0xFFFFFBF5),
    accent = Color(0xFFE08D4B),
    textPrimary = Color(0xFF3A2E22),
    textOnAccent = Color(0xFF2B1F12),
    noteText = Color(0xFF3A2E22).copy(alpha = 0.7f),
    chipBackground = Color(0xFFE3CBA9),
    danger = Color(0xFFD9573F)
)

private val KakuroDarkPalette = KakuroPalette(
    background = Color(0xFF1C1712),
    cellBackground = Color(0xFF15110D),
    selectedCell = Color(0xFFE8985B),
    runTint = Color(0xFF543A24),
    blackCell = Color(0xFFF3E9DB),
    clueText = Color(0xFF1C1712),
    accent = Color(0xFFE8985B),
    textPrimary = Color(0xFFF3E9DB),
    textOnAccent = Color(0xFF1C1712),
    noteText = Color(0xFFF3E9DB).copy(alpha = 0.7f),
    chipBackground = Color(0xFF453A2E),
    danger = Color(0xFFE0705A)
)

/** Shared instances, so a recomposition never allocates a fresh palette again. */
private fun kakuroPalette(isDark: Boolean): KakuroPalette = if (isDark) KakuroDarkPalette else KakuroLightPalette

// ---------------------------------------------------------------------------
// Selection, text and clock helpers
// ---------------------------------------------------------------------------

/**
 * Everything the screen derives from the selected cell: the tinted run cells (without the selected
 * cell itself), the [KakuroRuns.clueKey]s of the two clue halves to ring, the digits already placed
 * in either run, and the helper-line text, one entry per run.
 */
private class KakuroSelection(
    val runCells: Set<Int>,
    val activeClues: Set<Int>,
    val usedDigits: Set<Int>,
    val summary: List<String>
)

private val NoKakuroSelection = KakuroSelection(emptySet(), emptySet(), emptySet(), emptyList())

private fun kakuroSelection(s: KakuroState): KakuroSelection {
    val index = s.selectedIndex ?: return NoKakuroSelection
    val runs = listOfNotNull(
        KakuroRuns.runThrough(s, index, horizontal = true),
        KakuroRuns.runThrough(s, index, horizontal = false)
    )
    if (runs.isEmpty()) return NoKakuroSelection
    val runCells = HashSet<Int>()
    val activeClues = HashSet<Int>()
    val summary = ArrayList<String>()
    for (run in runs) {
        runCells.addAll(run.cells)
        activeClues.add(KakuroRuns.clueKey(run.clueCellIndex, run.horizontal))
        summary.add(kakuroRunSummary(run, KakuroRuns.progress(s, run)))
    }
    runCells.remove(index)
    return KakuroSelection(
        runCells = runCells,
        activeClues = activeClues,
        usedDigits = KakuroRuns.usedDigits(s, runs, index),
        summary = summary
    )
}

/** "Across 17: 12 so far, 5 to go" / "Down 23: done" -- the helper line for one run. */
private fun kakuroRunSummary(run: KakuroRunInfo, p: KakuroRunProgress): String {
    val label = if (run.horizontal) "Across" else "Down"
    val remaining = run.target - p.entered
    val detail = when {
        p.complete -> "done"
        p.hasRepeat -> "a digit repeats"
        remaining < 0 -> "over by ${-remaining}"
        p.filled == p.size -> "short by $remaining"
        else -> "${p.entered} so far, $remaining to go"
    }
    return "$label ${run.target}: $detail"
}

private fun kakuroTierLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

private fun kakuroFormatClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

// ---------------------------------------------------------------------------
// Loading placeholder, difficulty chips, status block, clock
// ---------------------------------------------------------------------------

/**
 * Shown until the first puzzle has been generated off the main thread. It is still inside
 * [GameChrome] so Back works: with nothing on the board there is nothing to confirm, and leaving is
 * a plain abort.
 */
@Composable
private fun KakuroLoading(game: KakuroGame, palette: KakuroPalette) {
    GameChrome(
        helpTitle = "How to Play Kakuro",
        helpText = KAKURO_HELP_TEXT,
        matchInProgress = false,
        onLeave = { game.abortMatch() },
        onAbort = { game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(palette.background),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Dealing a puzzle…",
                color = palette.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
private fun KakuroDifficultyTabs(current: CpuDifficulty, palette: KakuroPalette, onSelect: (CpuDifficulty) -> Unit) {
    // horizontalScroll is the fallback for a window too narrow for three chips plus large text, so
    // a chip is never compressed to a sliver; the row is simply scrolled into view.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        for (tier in CpuDifficulty.entries) {
            KakuroTab(
                label = kakuroTierLabel(tier),
                selected = tier == current,
                palette = palette,
                onClick = { onSelect(tier) }
            )
        }
    }
}

/** A pill chip with a 48dp-tall touch target around a 36dp-tall pill, announced as a radio button. */
@Composable
private fun KakuroTab(label: String, selected: Boolean, palette: KakuroPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected) palette.accent else palette.chipBackground)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) palette.textOnAccent else palette.textPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/**
 * Mistakes, the clock and the New puzzle button on one line (plus a "Daily puzzle" tag when today's
 * seeded puzzle is the one on screen). At least 48dp tall so the layout below it clears the corner
 * button. Callers give it `padding(end = GameChromeEndInset)`. The mistakes counter is a polite live
 * region, so a screen-reader player hears every wrong digit.
 */
@Composable
private fun KakuroStatus(
    mistakes: Int,
    daily: Boolean,
    generating: Boolean,
    game: KakuroGame,
    onNewPuzzle: () -> Unit,
    palette: KakuroPalette,
    modifier: Modifier = Modifier
) {
    val mistakesWords = if (mistakes == 1) "1 mistake" else "$mistakes mistakes"
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
            Text(
                "Mistakes: $mistakes",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = mistakesWords
                    }
            )
            KakuroClock(game = game, palette = palette)
            // Matches the shape/size of the corner menu button right next to it.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, palette.textPrimary.copy(alpha = 0.35f), CircleShape)
                    .semantics { contentDescription = "New puzzle" }
                    .clickable(
                        enabled = !generating,
                        onClickLabel = "Deal a new puzzle",
                        role = Role.Button,
                        onClick = onNewPuzzle
                    ),
                contentAlignment = Alignment.Center
            ) {
                // The glyph is decoration; without this a screen reader reads "clockwise open circle arrow".
                Text(
                    "↻",
                    color = palette.textPrimary.copy(alpha = if (generating) 0.4f else 1f),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the whole screen and every cell. Reads [KakuroGame.activeElapsedMillis], so it freezes while the
 * engine is paused (app backgrounded) and ends on exactly the recorded solve time.
 */
@Composable
private fun KakuroClock(game: KakuroGame, palette: KakuroPalette) {
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
        kakuroFormatClock(elapsed),
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * The board's own slot. Sizes from THIS slot's measured space (not an outer scope) with
 * [fitBoard], which never returns a footprint larger than the space it is given, so the old
 * `coerceAtLeast(20.dp)` floor (which let a board exceed its container) is gone. When the slot
 * cannot give a cell [KAKURO_MIN_CELL_DP] the board keeps that size and scrolls/pans in both axes
 * instead of shrinking further. While a new puzzle is generating the old board stays, inert, with
 * a chip over it.
 */
@Composable
private fun KakuroBoardSlot(
    s: KakuroState,
    selection: KakuroSelection,
    completedClues: Set<Int>,
    enabled: Boolean,
    generating: Boolean,
    palette: KakuroPalette,
    colorblind: Boolean,
    onTapCell: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = s.cols,
            rows = s.rows,
            framePx = KAKURO_BOARD_FRAME_DP,
            minCellPx = KAKURO_MIN_CELL_DP,
            maxCellPx = KAKURO_MAX_CELL_DP
        )
        val scrolls = !fit.meetsMinimum
        // Each cell is laid out in whole pixels, so the dp size is floored to a pixel and trimmed by
        // one more: n rounded-up cells plus the rounded frame would otherwise overshoot the slot by
        // a pixel or two and squeeze the last column.
        val cellSize: Dp = if (scrolls) {
            KAKURO_MIN_CELL_DP.dp
        } else {
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
        }
        Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.blackCell)
                    .padding(KAKURO_BOARD_FRAME_DP.dp)
            ) {
                Column {
                    for (row in 0 until s.rows) {
                        Row {
                            for (col in 0 until s.cols) {
                                val index = row * s.cols + col
                                val cell = s.cells[index]
                                if (cell.type == KakuroCellType.BLACK) {
                                    val acrossKey = KakuroRuns.clueKey(index, horizontal = true)
                                    val downKey = KakuroRuns.clueKey(index, horizontal = false)
                                    KakuroClueCell(
                                        row = row,
                                        col = col,
                                        across = cell.acrossClue,
                                        down = cell.downClue,
                                        acrossDone = acrossKey in completedClues,
                                        downDone = downKey in completedClues,
                                        acrossActive = acrossKey in selection.activeClues,
                                        downActive = downKey in selection.activeClues,
                                        cellSize = cellSize,
                                        palette = palette
                                    )
                                } else {
                                    KakuroEntryCell(
                                        row = row,
                                        col = col,
                                        cell = cell,
                                        isSelected = index == s.selectedIndex,
                                        inRun = index in selection.runCells,
                                        isWrong = cell.value != null && cell.value != s.solution[index],
                                        enabled = enabled,
                                        cellSize = cellSize,
                                        palette = palette,
                                        colorblind = colorblind,
                                        onTap = { onTapCell(index) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (generating) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.background.copy(alpha = 0.94f))
                    .border(1.dp, palette.textPrimary.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    "Dealing a new puzzle…",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

/**
 * A BLACK cell. With a clue it shows it split by a diagonal -- DOWN sum in the upper-right half,
 * ACROSS sum in the lower-left half, the standard Kakuro convention -- and is one screen-reader node
 * ("Across 17, down 23, row 2, column 1"). A cell with no clue is plain filler and is skipped by a
 * screen reader. Numbers are sized from the cell in dp, floored at 10, so they stay legible at the
 * smallest cell and never grow with the system font scale. A ringed cell holds a clue of the selected
 * cell's runs; a number that has faded belongs to a run that is already full and correct.
 */
@Composable
private fun KakuroClueCell(
    row: Int,
    col: Int,
    across: Int?,
    down: Int?,
    acrossDone: Boolean,
    downDone: Boolean,
    acrossActive: Boolean,
    downActive: Boolean,
    cellSize: Dp,
    palette: KakuroPalette
) {
    val hasClue = across != null || down != null
    val density = LocalDensity.current
    val clueSp: TextUnit = with(density) { (cellSize.value * 0.34f).coerceAtLeast(10f).dp.toSp() }
    val spoken = remember(across, down, acrossDone, downDone, row, col) {
        val acrossText = across?.let { "Across $it" + if (acrossDone) " complete" else "" }
        val downLabel = if (across != null) "down" else "Down"
        val downText = down?.let { "$downLabel $it" + if (downDone) " complete" else "" }
        val clues = listOfNotNull(acrossText, downText).joinToString(", ")
        "$clues, row ${row + 1}, column ${col + 1}"
    }
    val semanticsModifier = if (hasClue) {
        Modifier.clearAndSetSemantics { contentDescription = spoken }
    } else {
        Modifier.clearAndSetSemantics {}
    }

    Box(
        modifier = Modifier
            .size(cellSize)
            .drawWithContent {
                val gap = 0.5.dp.toPx()
                val innerWidth = size.width - 2f * gap
                val innerHeight = size.height - 2f * gap
                drawRect(color = palette.blackCell, topLeft = Offset(gap, gap), size = Size(innerWidth, innerHeight))
                if (hasClue) {
                    drawLine(
                        color = palette.clueText.copy(alpha = 0.55f),
                        start = Offset(gap, gap),
                        end = Offset(size.width - gap, size.height - gap),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                if (acrossActive || downActive) {
                    val ring = 2.dp.toPx()
                    drawRect(
                        color = palette.clueText,
                        topLeft = Offset(gap + ring / 2f, gap + ring / 2f),
                        size = Size(innerWidth - ring, innerHeight - ring),
                        style = Stroke(width = ring)
                    )
                }
                drawContent()
            }
            .then(semanticsModifier)
    ) {
        if (down != null) {
            Text(
                down.toString(),
                color = if (downDone) palette.clueText.copy(alpha = 0.5f) else palette.clueText,
                fontSize = clueSp,
                fontWeight = if (downActive) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 2.dp)
            )
        }
        if (across != null) {
            Text(
                across.toString(),
                color = if (acrossDone) palette.clueText.copy(alpha = 0.5f) else palette.clueText,
                fontSize = clueSp,
                fontWeight = if (acrossActive) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 2.dp, bottom = 1.dp)
            )
        }
    }
}

/**
 * A WHITE cell: the player's digit, or its pencil marks as a 3x3 grid. The WHOLE [cellSize] square is
 * the touch target and the accessibility node, described like ReversiScreen.CellView (state first,
 * then 1-indexed row and column). The selected cell is filled with the accent and has a heavy inner
 * border; the cells of its two runs are tinted (plus a thin outline under [colorblind]). A digit that
 * does not match the solution is struck through, in the digit's own colour so the mark still reads
 * on the orange selected cell, where the danger colour alone is nearly invisible.
 */
@Composable
private fun KakuroEntryCell(
    row: Int,
    col: Int,
    cell: KakuroCell,
    isSelected: Boolean,
    inRun: Boolean,
    isWrong: Boolean,
    enabled: Boolean,
    cellSize: Dp,
    palette: KakuroPalette,
    colorblind: Boolean,
    onTap: () -> Unit
) {
    val value = cell.value
    val notes = cell.notes
    val description = remember(value, notes, isSelected, isWrong, row, col) {
        val base = when {
            value != null && isWrong -> "Wrong digit $value"
            value != null -> "Digit $value"
            notes.isNotEmpty() -> "Notes " + notes.sorted().joinToString(", ")
            else -> "Empty"
        }
        val prefix = if (isSelected) "Selected, " else ""
        "$prefix$base, row ${row + 1}, column ${col + 1}"
    }
    val density = LocalDensity.current
    val digitSp: TextUnit = with(density) { (cellSize.value * 0.5f).dp.toSp() }
    val noteSp: TextUnit = with(density) { (cellSize.value * 0.28f).coerceAtLeast(8f).dp.toSp() }
    val ink = if (isSelected) palette.textOnAccent else palette.textPrimary
    val digitColor = if (isWrong && !isSelected) palette.danger else ink

    Box(
        modifier = Modifier
            .size(cellSize)
            .drawWithContent {
                val gap = 0.5.dp.toPx()
                val innerWidth = size.width - 2f * gap
                val innerHeight = size.height - 2f * gap
                val fill = when {
                    isSelected -> palette.selectedCell
                    inRun -> palette.runTint
                    else -> palette.cellBackground
                }
                drawRect(color = fill, topLeft = Offset(gap, gap), size = Size(innerWidth, innerHeight))
                if (isSelected) {
                    val border = 2.5.dp.toPx()
                    drawRect(
                        color = palette.textOnAccent,
                        topLeft = Offset(gap + border / 2f, gap + border / 2f),
                        size = Size(innerWidth - border, innerHeight - border),
                        style = Stroke(width = border)
                    )
                } else if (inRun && colorblind) {
                    val outline = 1.5.dp.toPx()
                    drawRect(
                        color = palette.textPrimary.copy(alpha = 0.45f),
                        topLeft = Offset(gap + outline / 2f, gap + outline / 2f),
                        size = Size(innerWidth - outline, innerHeight - outline),
                        style = Stroke(width = outline)
                    )
                }
                drawContent()
                if (isWrong) {
                    drawLine(
                        color = digitColor,
                        start = Offset(size.width * 0.24f, size.height * 0.76f),
                        end = Offset(size.width * 0.76f, size.height * 0.24f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            .clickable(enabled = enabled, onClickLabel = "Select cell", role = Role.Button, onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (value != null) {
            Text(
                value.toString(),
                color = digitColor,
                fontWeight = FontWeight.Bold,
                fontSize = digitSp,
                modifier = Modifier.clearAndSetSemantics {}
            )
        } else if (notes.isNotEmpty()) {
            KakuroNotesGrid(
                notes = notes,
                noteSp = noteSp,
                textColor = if (isSelected) palette.textOnAccent else palette.noteText
            )
        }
    }
}

@Composable
private fun KakuroNotesGrid(notes: Set<Int>, noteSp: TextUnit, textColor: Color) {
    Column(modifier = Modifier.fillMaxSize().padding(2.dp).clearAndSetSemantics {}) {
        for (noteRow in 0 until 3) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (noteCol in 0 until 3) {
                    val digit = noteRow * 3 + noteCol + 1
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (digit in notes) {
                            Text(digit.toString(), color = textColor, fontSize = noteSp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Controls: run helper line, number pad, notes switch
// ---------------------------------------------------------------------------

/**
 * One line per run of the selected cell ("Across 17: 12 so far, 5 to go"), or a prompt when nothing is
 * selected. Reserves two lines so the pad below never jumps. A polite live region, so a screen-reader
 * player hears where each sum stands after a digit goes in.
 */
@Composable
private fun KakuroRunStrip(lines: List<String>, palette: KakuroPalette, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (lines.isEmpty()) {
            Text(
                "Tap a white cell, then a digit.",
                color = palette.textPrimary.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        } else {
            for (line in lines) {
                Text(
                    line,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Digits 1-5 over 6-9 plus Erase, then the Notes switch: every key at least 48dp tall and, at the
 * 288dp content width of a Fold cover screen, about 53dp wide. Keys are disabled until a cell is
 * selected. A digit already placed in one of the selected cell's runs is dimmed (and announced as
 * "already used in this run") but stays tappable. In notes mode the digit keys carry an accent
 * outline, a shape cue that does not depend on the colour of the Notes switch.
 */
@Composable
private fun KakuroNumberPad(
    notesMode: Boolean,
    enabled: Boolean,
    usedDigits: Set<Int>,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onNotesChange: (Boolean) -> Unit,
    palette: KakuroPalette,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(max = KAKURO_CONTROLS_MAX_WIDTH_DP.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (digit in 1..5) {
                KakuroPadKey(
                    label = digit.toString(),
                    clickLabel = if (notesMode) "Toggle note $digit" else "Enter $digit",
                    enabled = enabled,
                    dimmed = digit in usedDigits,
                    stateText = if (digit in usedDigits) "Already used in this run" else null,
                    outlined = notesMode,
                    palette = palette,
                    onClick = { onDigit(digit) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (digit in 6..9) {
                KakuroPadKey(
                    label = digit.toString(),
                    clickLabel = if (notesMode) "Toggle note $digit" else "Enter $digit",
                    enabled = enabled,
                    dimmed = digit in usedDigits,
                    stateText = if (digit in usedDigits) "Already used in this run" else null,
                    outlined = notesMode,
                    palette = palette,
                    onClick = { onDigit(digit) },
                    modifier = Modifier.weight(1f)
                )
            }
            KakuroPadKey(
                label = "Erase",
                clickLabel = "Erase the digit in the selected cell",
                enabled = enabled,
                dimmed = false,
                stateText = null,
                outlined = false,
                palette = palette,
                onClick = onErase,
                modifier = Modifier.weight(1f)
            )
        }
        KakuroNotesSwitch(
            notesMode = notesMode,
            palette = palette,
            onChange = onNotesChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun KakuroPadKey(
    label: String,
    clickLabel: String,
    enabled: Boolean,
    dimmed: Boolean,
    stateText: String?,
    outlined: Boolean,
    palette: KakuroPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textAlpha = when {
        !enabled -> 0.4f
        dimmed -> 0.5f
        else -> 1f
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.chipBackground)
            .then(if (outlined) Modifier.border(2.dp, palette.accent, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .semantics { if (stateText != null) stateDescription = stateText },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = palette.textPrimary.copy(alpha = textAlpha),
            fontWeight = FontWeight.Bold,
            // A word ("Erase") gets the smaller style so it fits a ~53dp key; a lone digit stays large.
            style = if (label.length > 1) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            maxLines = 1
        )
    }
}

/** The pencil-marks switch: at least 48dp tall, announced as a switch named "Notes" (its on/off state is read by the toggle role). */
@Composable
private fun KakuroNotesSwitch(
    notesMode: Boolean,
    palette: KakuroPalette,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (notesMode) palette.textOnAccent else palette.textPrimary
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (notesMode) palette.accent else palette.chipBackground)
            .toggleable(value = notesMode, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Notes", color = contentColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        // Decoration only: the switch role already announces on/off.
        Text(
            if (notesMode) "ON" else "OFF",
            color = contentColor.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

// ---------------------------------------------------------------------------
// Solved panel
// ---------------------------------------------------------------------------

/**
 * Takes the place of the number pad when the puzzle is solved, so the finished board stays in view
 * and does not resize. A live region announces the win when the panel appears. Both buttons are at
 * least 48dp tall; "New Puzzle" is inert while the next puzzle is being generated.
 */
@Composable
private fun KakuroResultPanel(
    daily: Boolean,
    mistakes: Int,
    timeMillis: Long?,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    puzzlesSolved: Int,
    generating: Boolean,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: KakuroPalette,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = palette.background, contentColor = palette.textPrimary),
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (daily) "Daily puzzle solved!" else "Solved!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                // A polite live region so a screen reader announces the win when the panel appears.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (timeMillis != null) "Mistakes: $mistakes  ·  Time: ${kakuroFormatClock(timeMillis)}" else "Mistakes: $mistakes",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (isNewBest) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", fontWeight = FontWeight.Bold)
            } else if (bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best: ${kakuroFormatClock(bestTimeMillis)}", style = MaterialTheme.typography.bodyMedium)
            }
            if (puzzlesSolved > 1) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "$puzzlesSolved puzzles solved this session",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPrimary.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onNewPuzzle,
                    enabled = !generating,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.textOnAccent),
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
