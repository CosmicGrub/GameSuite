package com.gamesuite.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.gamesuite.games.sudoku.SudokuCell
import com.gamesuite.games.sudoku.SudokuGame
import com.gamesuite.games.sudoku.SudokuState
import com.gamesuite.games.sudoku.SudokuStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.CameraShake
import com.gamesuite.ui.effects.ParticleBurst
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Below this cell size (dp) the board stops shrinking and scrolls/pans instead. */
private const val MIN_CELL_DP = 28f

/** Upper bound so the grid does not sprawl across a tablet (9 x 56 = a 504dp board). */
private const val MAX_CELL_DP = 56f

/** The rim around the grid (dp). Real `padding` of the frame AND the `framePx` handed to [fitBoard], so the two can never drift apart. */
private const val BOARD_RIM_DP = 3f

/** Cell lines inside a 3x3 box, and the heavier lines between boxes (dp). */
private const val THIN_LINE_DP = 1f
private const val THICK_LINE_DP = 2.5f

/** A wrong digit's nudge: short and small, since a mistake is a frequent minor event. Off under [LocalReducedMotion]. */
private const val MISTAKE_SHAKE_MS = 140
private const val MISTAKE_SHAKE_MAGNITUDE_DP = 3f

/** The solve's shake and particle burst. Off under [LocalReducedMotion]; the chime and haptic are not. */
private const val WIN_SHAKE_MS = 350
private const val WIN_SHAKE_MAGNITUDE_DP = 10f
private const val WIN_BURST_PARTICLE_COUNT = 26
private const val WIN_BURST_MIN_SPEED_DP = 140f
private const val WIN_BURST_MAX_SPEED_DP = 320f
private const val WIN_BURST_GRAVITY_DP = 420f
private const val WIN_BURST_RADIUS_DP = 6f

/** How long a "that did nothing" hint stays on screen after the last rejected input. */
private const val HINT_VISIBLE_MS = 2500L

/** Portrait content never stretches wider than this, so a tablet in portrait is not a sea of cream. */
private val CONTENT_MAX_WIDTH = 560.dp

/** Wide-window layout: the right-hand column's width, and the cap on the whole board-plus-column row. */
private val SIDE_PANE_WIDTH = 340.dp
private val SIDE_BY_SIDE_MAX_WIDTH = 980.dp

/** The number pad: 48dp-tall keys, never wider than this on a wide window. */
private val PAD_KEY_HEIGHT = 48.dp
private val PAD_GAP = 6.dp
private val PAD_MAX_WIDTH = 340.dp

/** The reserved line above the pad for a "that did nothing" hint, so showing it never shifts the pad. */
private val HINT_SLOT_HEIGHT = 24.dp

/** The whole bottom zone while playing (hint line + gap + three pad rows); the result panel is at least this tall, so solving never resizes the board. */
private val BOTTOM_ZONE_HEIGHT = HINT_SLOT_HEIGHT + 4.dp + PAD_KEY_HEIGHT * 3 + PAD_GAP * 2

private const val HELP_TEXT =
    "Fill the grid so every row, every column and every 3x3 box contains each digit from 1 to 9 " +
        "exactly once; the bold digits are clues and cannot be changed. Tap a cell, then a digit " +
        "on the pad, and use Erase to clear the selected cell.\n\n" +
        "Turn Notes on to pencil small candidates into an empty cell instead, and placing a digit " +
        "clears it from the notes of its row, column and box. A wrong digit is marked at once with " +
        "a corner mark and adds to your mistake count, which never ends the game and does not go " +
        "down when you fix it.\n\n" +
        "Easy, Medium and Hard leave about 42, 32 and 26 clues. Your clock starts on your first " +
        "digit and your best time is saved for each difficulty; the daily puzzle is the same for " +
        "everyone on the same difficulty."

/**
 * Renders SudokuGame's state reactively — a difficulty selector, a live status row, the 9x9 grid, a
 * number pad and a solved-puzzle panel. Used for both the free-play route and the daily route
 * ([dailySeed]). Input is select-then-enter (tap a cell, then a digit on the pad: the same
 * "tap source, then tap destination" two-step this app uses for Solitaire, since it has no drag
 * model) plus a Notes toggle (the same "explicit mode toggle" idiom as Minesweeper's flag mode).
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, Back to Menu) plus its BackHandler.
 * The unit of this session is a PUZZLE, so leaving mid-puzzle discards only the unfinished one:
 * with nothing solved yet it is a pure `abortMatch()` (never recorded), but once the session has
 * solved puzzles it goes through `leaveSession()`, the same call the solved panel's "Back to Menu"
 * makes, so those solves still count (the confirm dialog says so). The tab row reserves
 * [GameChromeEndInset] so the corner button never covers it, and the wide layout does the same for
 * the top of its right-hand column.
 *
 * DAILY PUZZLE: [dailySeed] pins the first puzzle and the status row says "Daily puzzle" while it
 * is on screen. A difficulty switch made mid-puzzle replays today's puzzle at the new difficulty
 * (same solution grid, different clue count); "New puzzle" and any switch made after the puzzle is
 * solved deal a random puzzle and drop the tag. Both ask first when the puzzle has entries, since
 * they discard them.
 *
 * LAYOUT: the grid is sized by [fitBoard] against the measured space of its own slot (the rim is
 * handed to it as `framePx`), clamped to [MIN_CELL_DP]..[MAX_CELL_DP]; a slot that cannot give a
 * [MIN_CELL_DP] cell pans instead of shrinking the cells further. Portrait stacks tabs / status /
 * grid / pad (or result panel) in a column capped at [CONTENT_MAX_WIDTH], with the grid centred in
 * whatever the header and the bottom zone leave over; a wide landscape window puts the grid on the
 * left and tabs, status and pad in a [SIDE_PANE_WIDTH] column on the right. The result panel
 * replaces the pad in place and is at least as tall as it, so solving never resizes the grid. The
 * pad is two rows of five 48dp keys (1-5, then 6-9 and Erase) plus a full-width Notes toggle: nine
 * 48dp keys in one row would need 480dp, more than any phone has.
 *
 * ACCESSIBILITY: every cell is one button described like ReversiScreen.CellView (its digit or
 * "Empty" and any notes, given/entered/wrong, then 1-indexed row and column, plus "selected").
 * Nothing rides on colour alone: a wrong digit gets a corner mark and a tinted fill (and its
 * digit ink is chosen for contrast on that fill, which also fixes the old bug where a wrong digit
 * typed into the still-selected cell rendered cream on orange and could not be seen); the selected
 * cell has a heavy outline; given digits are bold and entered digits regular, and under
 * [LocalColorblindMode] entered digits are also italic and cells holding the selected digit also
 * get an inset outline. Peer shading (the selected cell's row, column and box) is a pure aid the
 * grid lines already convey, so it stays a tint. The 3x3 boxes are drawn with heavy lines and a
 * rim, not a faint gap. A rejected input (no cell selected, a given cell, a note on a filled cell,
 * nothing to erase) buzzes the failure haptic and says so in a polite live region; the mistake
 * counter is a polite live region too. Difficulty tabs are radio buttons, Notes is a switch, and
 * every non-grid control is at least 48dp.
 *
 * MOTION (off under [LocalReducedMotion]): a wrong digit nudges the grid for 140ms; a solve shakes
 * it for 350ms and bursts particles from its centre (speeds and gravity are in dp, converted to
 * pixels, so the burst actually travels). A solve plays the success chime and the celebration
 * haptic (a solve is always the human's) before the stats write; the haptic used to wait on it.
 *
 * CLOCK: shown by its own child composable reading [SudokuGame.activeElapsedMillis], so the 5Hz tick
 * recomposes only that Text (not the grid), freezes while the engine is paused, and ends on exactly
 * the recorded solve time.
 *
 * VISUAL IDENTITY: shares its warm background/accent tokens with MinesweeperScreen (see
 * [SudokuPalette]) for one consistent "new games" identity across this batch, extended with
 * Sudoku-specific tokens (given vs. entered vs. wrong, peer/same-value highlight). Never reads
 * `MaterialTheme.colorScheme` for gameplay colors, same standing rule as every other game's board.
 */
@Composable
fun SudokuScreen(
    sessionManager: GameSessionManager,
    game: SudokuGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's puzzle for every player — see SudokuGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.SUDOKU, enabled = musicEnabled)
    val statsStore = remember { SudokuStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = sudokuPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val scope = rememberCoroutineScope()

    // Two independent shakes: a wrong digit and a full solve want different magnitudes, and
    // Modifier.cameraShake's magnitude is fixed per call site, not per trigger.
    val mistakeShake = rememberCameraShake()
    val winShake = rememberCameraShake()

    // True while the puzzle on screen is today's seeded puzzle. Any later fresh puzzle (New puzzle,
    // or a tier switch once the puzzle is solved) is random again, and the status row says so.
    var dailyActive by remember { mutableStateOf(dailySeed != null) }
    // Bumped by every puzzle this screen starts, so per-puzzle UI state resets even if the engine
    // happened to deal the same solution twice in a row (a daily replayed at another difficulty).
    var puzzleId by remember { mutableStateOf(0) }
    var notesMode by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var hintTick by remember { mutableStateOf(0) }
    var pendingTier by remember { mutableStateOf<CpuDifficulty?>(null) }
    var confirmNewPuzzle by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        dailyActive = dailySeed != null
        game.startMatch(dailySeed)
    }

    LaunchedEffect(hintTick) {
        if (hintTick > 0) {
            delay(HINT_VISIBLE_MS)
            hint = ""
        }
    }

    val s = state ?: return

    val allBestTimes by statsStore.bestTimesMillis.collectAsState(initial = emptyMap())
    val bestTimeMillis = allBestTimes[game.difficulty.name]
    // null until this puzzle's solve has been written to the stats store; then whether it was a new best.
    var reportedNewBest by remember(puzzleId, s.solution) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(s.won) {
        if (!s.won) return@LaunchedEffect
        // A solve is always the human's (this game has no opponent), so the celebration is
        // unconditional. Feedback first: the chime and the haptic must not wait on the DataStore
        // write below.
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        if (reportedNewBest == null) {
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            reportedNewBest = statsStore.recordWin(game.difficulty, finalTime)
        }
    }

    // A digit is "used up" once it's correctly placed in all 9 of its occurrences (every valid,
    // uniquely-solvable grid has each digit exactly 9 times in the true solution) -- disabling it
    // on the number pad at that point is a standard, purely-cosmetic Sudoku-app convenience; it
    // never blocks re-entering that digit elsewhere.
    val remainingCounts = remember(s.cells, s.solution) {
        (1..9).associateWith { d -> 9 - s.cells.indices.count { s.cells[it].value == d && d == s.solution[it] } }
    }

    // "Finished units" for the abort policy: puzzles already solved this session. If any exist,
    // leaving mid-puzzle must still score them -- see onAbort below.
    val finishedPuzzles = game.puzzlesSolved.value

    fun showHint(text: String) {
        hint = text
        hintTick += 1
    }

    val startRound: (Boolean) -> Unit = { daily ->
        if (!game.matchOver.value) {
            dailyActive = daily && dailySeed != null
            puzzleId += 1
            notesMode = false
            hint = ""
            game.startMatch(if (dailyActive) dailySeed else null)
        }
    }
    val switchTier: (CpuDifficulty) -> Unit = { tier ->
        if (!game.matchOver.value && tier != game.difficulty) {
            val live = game.state.value
            // Mid-puzzle on the daily route the puzzle stays today's; once solved, a tier tap
            // means "give me a new puzzle at this difficulty".
            val keepDaily = dailyActive && live != null && !live.isOver
            game.difficulty = tier
            startRound(keepDaily)
        }
    }
    val onSelectTier: (CpuDifficulty) -> Unit = { tier ->
        if (tier != game.difficulty) {
            val live = game.state.value
            if (live != null && sudokuHasProgress(live)) {
                pendingTier = tier
            } else {
                switchTier(tier)
            }
        }
    }
    val onNewPuzzle: () -> Unit = {
        val live = game.state.value
        if (live != null && sudokuHasProgress(live)) {
            confirmNewPuzzle = true
        } else {
            startRound(false)
        }
    }

    val onTapCell: (Int) -> Unit = { index ->
        // Re-read the live state: the flag a cell was composed with can be a frame stale, and a
        // tap on a solved board (or after the session ended) must not select, tick or count.
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value) {
            game.selectCell(index)
            hint = ""
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val onDigit: (Int) -> Unit = { d ->
        // A FRESH read of game.state.value, not the composable's own `s` snapshot -- `s` is only
        // as current as the last recomposition, so two taps landing in one recomposition window
        // would otherwise both close over the same stale selection.
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value) {
            val index = live.selectedIndex
            val accepted = if (notesMode) game.toggleNote(d) else game.setValue(d)
            if (accepted && index != null) {
                hint = ""
                if (notesMode) {
                    haptics(HapticSignal.LIGHT_TICK)
                } else {
                    sounds.playTap()
                    haptics(HapticSignal.NORMAL_ACTION)
                    if (d != live.solution[index]) {
                        // A wrong digit is a much smaller deal than a genuine game-ending failure
                        // elsewhere in this app: the failure haptic and a buzz, but only a small,
                        // quick nudge of the grid (and none at all under reduced motion).
                        haptics(HapticSignal.FAILURE)
                        playSfx(SfxKind.INVALID_BUZZ)
                        if (!reducedMotion) scope.launch { mistakeShake.trigger(durationMs = MISTAKE_SHAKE_MS) }
                    }
                }
            } else {
                // The engine ignored it. Say so instead of playing the placement feedback.
                haptics(HapticSignal.FAILURE)
                showHint(
                    when {
                        index == null -> "Select an empty cell first"
                        live.cells[index].isGiven -> "That cell is a clue"
                        else -> "Erase the digit to add notes"
                    }
                )
            }
        }
    }
    val onErase: () -> Unit = {
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value) {
            if (game.clearValue()) {
                hint = ""
                haptics(HapticSignal.LIGHT_TICK)
            } else {
                val index = live.selectedIndex
                haptics(HapticSignal.FAILURE)
                showHint(
                    when {
                        index == null -> "Select a cell first"
                        live.cells[index].isGiven -> "That cell is a clue"
                        live.cells[index].notes.isNotEmpty() -> "Tap a note's digit again to remove it"
                        else -> "Nothing to erase here"
                    }
                )
            }
        }
    }

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-puzzle discards
    // ONLY the unfinished puzzle: if puzzles were already solved this session, leaving goes through
    // leaveSession() (the same call the solved panel's "Back to Menu" makes) so they still count;
    // with nothing solved it is a pure abort (never a win or loss). A solved puzzle leaves through
    // leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Sudoku",
        helpText = HELP_TEXT,
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
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            val availableHeight = maxHeight
            // Grid left + controls right only when the window is genuinely wide; every phone
            // portrait, the Fold cover screen included, takes the stacked layout.
            val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight * 1.15f

            val tabs: @Composable (Modifier) -> Unit = { tabsModifier ->
                SudokuTabs(current = game.difficulty, palette = palette, onSelect = onSelectTier, modifier = tabsModifier)
            }
            val status: @Composable (Modifier) -> Unit = { statusModifier ->
                SudokuStatus(
                    mistakes = s.mistakes,
                    daily = dailyActive,
                    game = game,
                    onNewPuzzle = onNewPuzzle,
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val board: @Composable (Modifier) -> Unit = { boardModifier ->
                SudokuBoardSlot(
                    s = s,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    mistakeShake = mistakeShake,
                    winShake = winShake,
                    onTapCell = onTapCell,
                    modifier = boardModifier
                )
            }
            val bottom: @Composable (Modifier) -> Unit = { zoneModifier ->
                SudokuBottomZone(
                    s = s,
                    notesMode = notesMode,
                    remainingCounts = remainingCounts,
                    hint = hint,
                    timeMillis = game.finishedElapsedMillis.value,
                    bestTimeMillis = bestTimeMillis,
                    isNewBest = reportedNewBest == true,
                    palette = palette,
                    onDigit = onDigit,
                    onErase = onErase,
                    onToggleNotes = { notesMode = it },
                    onNewPuzzle = onNewPuzzle,
                    onBackToMenu = { game.leaveSession() },
                    modifier = zoneModifier
                )
            }

            if (sideBySide) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = SIDE_BY_SIDE_MAX_WIDTH)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        board(Modifier.weight(1f).fillMaxHeight())
                        Column(
                            modifier = Modifier
                                .width(SIDE_PANE_WIDTH)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            // The top of this column is the top-right occupant of the layout, so the
                            // tab row keeps the corner button's width free at its end.
                            tabs(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                            status(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            bottom(Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = CONTENT_MAX_WIDTH)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // The tab row is the top-right occupant of this layout, so it keeps the
                        // corner button's width free at its end.
                        tabs(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        status(Modifier.fillMaxWidth())
                        // The grid's slot is MEASURED, not estimated: the header and the bottom zone
                        // are laid out at their natural height and the slot takes what is left.
                        board(Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        // Capped so a large font scale cannot push the grid out of the slot above;
                        // the result panel scrolls inside its own cap instead. The pad is a fixed
                        // height and is never capped.
                        bottom(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (s.isOver) {
                                        Modifier
                                            .heightIn(max = availableHeight * 0.45f)
                                            .verticalScroll(rememberScrollState())
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        }
    }

    val tierToConfirm = pendingTier
    if (tierToConfirm != null) {
        val tierName = sudokuTierLabel(tierToConfirm)
        AlertDialog(
            onDismissRequest = { pendingTier = null },
            containerColor = palette.background,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = { Text("Switch to $tierName?") },
            text = {
                Text(
                    if (dailyActive) {
                        "You'll start today's puzzle again as a $tierName puzzle. Your entries so far are cleared."
                    } else {
                        "This deals a new $tierName puzzle. Your current puzzle and entries are discarded."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTier = null
                        switchTier(tierToConfirm)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text("Switch", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingTier = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text("Keep Playing") }
            }
        )
    }

    if (confirmNewPuzzle) {
        AlertDialog(
            onDismissRequest = { confirmNewPuzzle = false },
            containerColor = palette.background,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = { Text("Deal a new puzzle?") },
            text = {
                Text(
                    if (dailyActive) {
                        "This replaces today's puzzle with a random one. Your entries so far are discarded."
                    } else {
                        "Your current puzzle and entries are discarded."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNewPuzzle = false
                        startRound(false)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text("New Puzzle", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmNewPuzzle = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text("Keep Playing") }
            }
        )
    }
}

private fun sudokuBoxOf(row: Int, col: Int) = (row / 3) * 3 + (col / 3)

/** True while the puzzle has entries a "new puzzle" or tier switch would throw away (a digit or a note the player placed). */
private fun sudokuHasProgress(s: SudokuState): Boolean =
    !s.isOver && s.cells.any { !it.isGiven && (it.value != null || it.notes.isNotEmpty()) }

private fun sudokuTierLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

private fun formatSudokuClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * What a screen reader says for one cell: its digit (or "Empty" plus any notes), whether that digit
 * is a clue, entered, or entered and wrong, then 1-indexed row and column, then "selected".
 * Same shape as ReversiScreen.CellView ("Dark disc, row 3, column 4").
 */
private fun sudokuCellDescription(row: Int, col: Int, cell: SudokuCell, isSelected: Boolean, isWrong: Boolean): String {
    val what = when {
        cell.value != null && cell.isGiven -> "${cell.value}, given"
        cell.value != null && isWrong -> "${cell.value}, entered, wrong"
        cell.value != null -> "${cell.value}, entered"
        cell.notes.isNotEmpty() -> "Empty, notes ${cell.notes.sorted().joinToString(" ")}"
        else -> "Empty"
    }
    val where = "row ${row + 1}, column ${col + 1}"
    return if (isSelected) "$what, $where, selected" else "$what, $where"
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- shares its base tokens with
// MinesweeperPalette (see this file's class KDoc for why this doesn't read
// MaterialTheme.colorScheme), extended with Sudoku-specific tokens. Every ink
// is picked for >= 4.5:1 against the fills it can sit on (given/entered/note
// ink on the cell and peer fills, textOnAccent on the selected fill, wrongText
// on wrongFill); the old cream-on-orange selected digit was only ~2.5:1.
// ---------------------------------------------------------------------------
private data class SudokuPalette(
    val background: Color,
    val cellBackground: Color,
    val selectedCell: Color,
    val peerHighlight: Color,
    val sameValueHighlight: Color,
    val accent: Color,
    val givenText: Color,
    val enteredText: Color,
    val noteText: Color,
    /** The corner mark on a wrong digit. */
    val danger: Color,
    /** Fill of a wrong cell, selected or not (it replaces the selected fill so the digit stays readable). */
    val wrongFill: Color,
    val wrongText: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    /** The rim and the heavy 3x3 box lines (the cell lines are this at low alpha). */
    val boardLine: Color
)

private val SudokuLightPalette = SudokuPalette(
    background = Color(0xFFFBF1E6),
    cellBackground = Color(0xFFFFFBF5),
    selectedCell = Color(0xFFE08D4B),
    peerHighlight = Color(0xFFF3E4D2),
    sameValueHighlight = Color(0xFFF0CFA0),
    accent = Color(0xFFE08D4B),
    givenText = Color(0xFF3A2E22),
    enteredText = Color(0xFF6B4A2A),
    noteText = Color(0xFF6F5F4C),
    danger = Color(0xFFC23B22),
    wrongFill = Color(0xFFFBD3CC),
    wrongText = Color(0xFFA8201A),
    textPrimary = Color(0xFF3A2E22),
    // Dark ink: cream text on this orange was only ~2.5:1.
    textOnAccent = Color(0xFF2B1F12),
    chipBackground = Color(0xFFE3CBA9),
    boardLine = Color(0xFF3A2E22)
)

private val SudokuDarkPalette = SudokuPalette(
    background = Color(0xFF1C1712),
    cellBackground = Color(0xFF15110D),
    selectedCell = Color(0xFFE8985B),
    peerHighlight = Color(0xFF2B241D),
    sameValueHighlight = Color(0xFF4A3A26),
    accent = Color(0xFFE8985B),
    givenText = Color(0xFFF3E9DB),
    enteredText = Color(0xFFCBB79C),
    noteText = Color(0xFFB8A892),
    danger = Color(0xFFFF7A63),
    wrongFill = Color(0xFF5A2A22),
    wrongText = Color(0xFFFFB4A5),
    textPrimary = Color(0xFFF3E9DB),
    textOnAccent = Color(0xFF1C1712),
    chipBackground = Color(0xFF453A2E),
    boardLine = Color(0xFFB8A892)
)

/** Shared instances, so a recomposition never allocates a fresh palette again. */
private fun sudokuPalette(isDark: Boolean): SudokuPalette = if (isDark) SudokuDarkPalette else SudokuLightPalette

// ---------------------------------------------------------------------------
// Header: difficulty tabs, status row, clock
// ---------------------------------------------------------------------------

/**
 * The EASY/MEDIUM/HARD tabs. `horizontalScroll` is the fallback for a window too narrow for three
 * pills at a large font scale, so a pill is never compressed to a sliver. Callers give it
 * `padding(end = GameChromeEndInset)`.
 */
@Composable
private fun SudokuTabs(
    current: CpuDifficulty,
    palette: SudokuPalette,
    onSelect: (CpuDifficulty) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (tier in CpuDifficulty.entries) {
            SudokuTab(
                label = sudokuTierLabel(tier),
                selected = tier == current,
                palette = palette,
                onClick = { onSelect(tier) }
            )
        }
    }
}

/** A pill with a 48dp-tall touch target around it, announced as a radio button. */
@Composable
private fun SudokuTab(label: String, selected: Boolean, palette: SudokuPalette, onClick: () -> Unit) {
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
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) palette.textOnAccent else palette.textPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

/**
 * Mistakes, the clock and the New puzzle button on one line, with a "Daily puzzle" tag above them
 * while today's seeded puzzle is on screen. At least 48dp tall.
 */
@Composable
private fun SudokuStatus(
    mistakes: Int,
    daily: Boolean,
    game: SudokuGame,
    onNewPuzzle: () -> Unit,
    palette: SudokuPalette,
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Mistakes: $mistakes",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A polite live region so a screen reader announces each new mistake.
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = mistakesWords
                    }
            )
            SudokuClock(game = game, palette = palette)
            Spacer(Modifier.width(12.dp))
            SudokuNewPuzzleButton(onClick = onNewPuzzle, palette = palette)
        }
    }
}

/** A 48dp-tall outlined pill. A word, not a glyph, so it reads on its own and needs no semantics workaround. */
@Composable
private fun SudokuNewPuzzleButton(onClick: () -> Unit, palette: SudokuPalette) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, palette.textPrimary.copy(alpha = 0.4f), shape)
            .clickable(onClickLabel = "Deal a new puzzle", role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "New puzzle",
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the whole screen and every cell (the old version read the clock at the screen root). Reads
 * [SudokuGame.activeElapsedMillis], so it freezes while the engine is paused (app backgrounded) and
 * ends on exactly the recorded solve time instead of jumping back at the solve.
 */
@Composable
private fun SudokuClock(game: SudokuGame, palette: SudokuPalette) {
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
        formatSudokuClock(elapsed),
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * The grid's own slot. Sizes from THIS slot's measured space (not an outer scope) with [fitBoard],
 * which never returns a footprint larger than the space it is given, so the old
 * `coerceAtLeast(24.dp)` floor (which let the grid exceed its container) and its fudge divisor are
 * gone. Each cell is laid out in whole pixels, so the dp size is floored to a pixel and trimmed by
 * one more: nine rounded-up cells plus the rounded rim would otherwise overshoot the slot by a
 * pixel or two. When the slot cannot give a cell [MIN_CELL_DP] the grid keeps that size and pans in
 * both axes instead of shrinking further.
 *
 * Owns the wrong-digit nudge, the solve shake and the particle burst, because it is the only place
 * that knows the grid's real pixel size when the burst spawns.
 */
@Composable
private fun SudokuBoardSlot(
    s: SudokuState,
    palette: SudokuPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    mistakeShake: CameraShake,
    winShake: CameraShake,
    onTapCell: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    // Pixel constants, converted once rather than inline at every use below.
    val mistakeShakePx = with(density) { MISTAKE_SHAKE_MAGNITUDE_DP.dp.toPx() }
    val winShakePx = with(density) { WIN_SHAKE_MAGNITUDE_DP.dp.toPx() }
    val burstMinSpeedPx = with(density) { WIN_BURST_MIN_SPEED_DP.dp.toPx() }
    val burstMaxSpeedPx = with(density) { WIN_BURST_MAX_SPEED_DP.dp.toPx() }
    val burstGravityPx = with(density) { WIN_BURST_GRAVITY_DP.dp.toPx() }
    val burst = remember { ParticleBurst() }
    // The shaken board's own real pixel size, so the burst origin is its centre as it is at spawn time.
    var boardPx by remember { mutableStateOf(Size.Zero) }

    // The burst's frame clock runs only while particles are alive. The shared rememberParticleBurst
    // keeps a withFrameNanos loop spinning for the whole life of the screen even when idle.
    LaunchedEffect(burst) {
        snapshotFlow { burst.particles.value.isNotEmpty() }.collectLatest { alive ->
            if (!alive) return@collectLatest
            var lastNanos = 0L
            while (true) {
                withFrameNanos { nanos ->
                    if (lastNanos != 0L) burst.tick((nanos - lastNanos) / 1_000_000_000f)
                    lastNanos = nanos
                }
            }
        }
    }

    LaunchedEffect(s.won) {
        // Reduced motion: no shake and no particles. The chime and haptic live in the screen's own
        // win effect and are unaffected.
        if (!s.won || reducedMotion) return@LaunchedEffect

        // Launched rather than awaited so the shake's decay never delays the burst below.
        launch { winShake.trigger(durationMs = WIN_SHAKE_MS, easing = FastOutSlowInEasing) }

        if (boardPx.width > 0f && boardPx.height > 0f) {
            burst.spawn(
                origin = Offset(boardPx.width / 2f, boardPx.height / 2f),
                count = WIN_BURST_PARTICLE_COUNT,
                colors = listOf(palette.accent, palette.enteredText, palette.sameValueHighlight),
                speedRange = burstMinSpeedPx..burstMaxSpeedPx,
                lifeRangeSeconds = 0.6f..0.9f,
                gravity = burstGravityPx
            )
        }
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = 9,
            rows = 9,
            framePx = BOARD_RIM_DP,
            minCellPx = MIN_CELL_DP,
            maxCellPx = MAX_CELL_DP
        )
        val scrolls = !fit.meetsMinimum
        val cellSize: Dp = if (scrolls) {
            MIN_CELL_DP.dp
        } else {
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
        }

        val selected = s.selectedIndex
        val selectedRow = selected?.let { it / 9 }
        val selectedCol = selected?.let { it % 9 }
        val selectedBox = selected?.let { sudokuBoxOf(it / 9, it % 9) }
        val selectedValue = selected?.let { s.cells[it].value }
        val boardShape = RoundedCornerShape(6.dp)

        Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Box(
                modifier = Modifier
                    // Shakes the grid AND the particles drawn over it together, as one physical board.
                    .then(
                        if (reducedMotion) {
                            Modifier
                        } else {
                            Modifier
                                .cameraShake(mistakeShake, magnitudePx = mistakeShakePx)
                                .cameraShake(winShake, magnitudePx = winShakePx)
                        }
                    )
                    .onSizeChanged { boardPx = Size(it.width.toFloat(), it.height.toFloat()) }
            ) {
                Box(
                    modifier = Modifier
                        .shadow(elevation = 3.dp, shape = boardShape)
                        .background(palette.boardLine)
                        .padding(BOARD_RIM_DP.dp)
                ) {
                    Column {
                        for (row in 0 until 9) {
                            Row {
                                for (col in 0 until 9) {
                                    val index = row * 9 + col
                                    val cell = s.cells[index]
                                    SudokuCellView(
                                        row = row,
                                        col = col,
                                        cell = cell,
                                        cellSize = cellSize,
                                        isSelected = index == selected,
                                        isPeerHighlighted = selected != null && index != selected &&
                                            (row == selectedRow || col == selectedCol || sudokuBoxOf(row, col) == selectedBox),
                                        isSameValueHighlighted = selectedValue != null && index != selected && cell.value == selectedValue,
                                        isWrong = cell.value != null && !cell.isGiven && cell.value != s.solution[index],
                                        enabled = !s.isOver,
                                        palette = palette,
                                        colorblind = colorblind,
                                        onTap = { onTapCell(index) }
                                    )
                                }
                            }
                        }
                    }
                    // The lines between cells (thin) and between 3x3 boxes (heavy), drawn over the
                    // cells so a box edge is never just a faint gap. Not interactive: a Canvas has
                    // no pointer input, so taps fall through to the cells.
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val cellPx = size.width / 9f
                        val thin = THIN_LINE_DP.dp.toPx()
                        val thick = THICK_LINE_DP.dp.toPx()
                        for (i in 1..8) {
                            val isBoxEdge = i % 3 == 0
                            val at = cellPx * i
                            val lineWidth = if (isBoxEdge) thick else thin
                            val lineColor = if (isBoxEdge) palette.boardLine else palette.boardLine.copy(alpha = 0.35f)
                            drawLine(color = lineColor, start = Offset(at, 0f), end = Offset(at, size.height), strokeWidth = lineWidth)
                            drawLine(color = lineColor, start = Offset(0f, at), end = Offset(size.width, at), strokeWidth = lineWidth)
                        }
                    }
                }
                Canvas(modifier = Modifier.matchParentSize()) {
                    for (particle in burst.particles.value) {
                        drawCircle(
                            color = particle.color.copy(alpha = particle.lifeFraction),
                            radius = WIN_BURST_RADIUS_DP.dp.toPx() * particle.lifeFraction.coerceAtLeast(0.35f),
                            center = particle.pos
                        )
                    }
                }
            }
        }
    }
}

/**
 * One cell. The WHOLE [cellSize] square is the touch target and the accessibility node. Described
 * like ReversiScreen.CellView (see [sudokuCellDescription]).
 *
 * Fill priority: wrong, selected, same digit as the selection, peer of the selection, plain. A
 * wrong cell always takes the wrong fill, even while it is the selected cell, which is what keeps a
 * wrong digit typed into the still-selected cell visible. Beyond colour: a wrong cell gets a corner
 * mark, the selected cell a heavy outline, given digits are bold, entered digits are regular (italic
 * as well under [colorblind]) and, under [colorblind], cells holding the selected digit get an inset
 * outline.
 */
@Composable
private fun SudokuCellView(
    row: Int,
    col: Int,
    cell: SudokuCell,
    cellSize: Dp,
    isSelected: Boolean,
    isPeerHighlighted: Boolean,
    isSameValueHighlighted: Boolean,
    isWrong: Boolean,
    enabled: Boolean,
    palette: SudokuPalette,
    colorblind: Boolean,
    onTap: () -> Unit
) {
    val bg = when {
        isWrong -> palette.wrongFill
        isSelected -> palette.selectedCell
        isSameValueHighlighted -> palette.sameValueHighlight
        isPeerHighlighted -> palette.peerHighlight
        else -> palette.cellBackground
    }
    val ink = when {
        isWrong -> palette.wrongText
        isSelected -> palette.textOnAccent
        cell.isGiven -> palette.givenText
        else -> palette.enteredText
    }
    val description = remember(row, col, cell, isSelected, isWrong) {
        sudokuCellDescription(row, col, cell, isSelected, isWrong)
    }

    Box(
        modifier = Modifier
            .size(cellSize)
            .background(bg)
            .drawBehind {
                if (isSameValueHighlighted && colorblind && !isSelected && !isWrong) {
                    val inset = 3.dp.toPx()
                    drawRect(
                        color = palette.textPrimary.copy(alpha = 0.7f),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2f * inset, size.height - 2f * inset),
                        style = Stroke(width = 1.5f.dp.toPx())
                    )
                }
                if (isWrong) {
                    val mark = size.minDimension * 0.34f
                    val corner = Path().apply {
                        moveTo(size.width - mark, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, mark)
                        close()
                    }
                    drawPath(corner, color = palette.danger)
                }
                if (isSelected) {
                    val ring = 3.dp.toPx()
                    drawRect(
                        color = palette.textPrimary,
                        topLeft = Offset(ring / 2f, ring / 2f),
                        size = Size(size.width - ring, size.height - ring),
                        style = Stroke(width = ring)
                    )
                }
            }
            .clickable(enabled = enabled, onClickLabel = "Select cell", role = Role.Button, onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        val value = cell.value
        if (value != null) {
            Text(
                value.toString(),
                color = ink,
                fontWeight = if (cell.isGiven) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (colorblind && !cell.isGiven) FontStyle.Italic else FontStyle.Normal,
                fontSize = (cellSize.value * 0.5f).sp,
                modifier = Modifier.clearAndSetSemantics {}
            )
        } else if (cell.notes.isNotEmpty()) {
            SudokuNotes(notes = cell.notes, cellSize = cellSize, ink = if (isSelected) palette.textOnAccent else palette.noteText)
        }
    }
}

/** A cell's pencil marks as a 3x3 of tiny digits (1 top-left, 9 bottom-right). Hidden from the screen reader: the cell's own description already lists them. */
@Composable
private fun SudokuNotes(notes: Set<Int>, cellSize: Dp, ink: Color) {
    val noteSize = (cellSize.value * 0.26f).sp
    Column(modifier = Modifier.fillMaxSize().padding(2.dp).clearAndSetSemantics {}) {
        for (r in 0 until 3) {
            Row(modifier = Modifier.weight(1f)) {
                for (c in 0 until 3) {
                    val digit = r * 3 + c + 1
                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (digit in notes) {
                            Text(
                                digit.toString(),
                                color = ink,
                                fontWeight = FontWeight.Medium,
                                fontSize = noteSize,
                                lineHeight = noteSize
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom zone: hint line + number pad, or the solved panel
// ---------------------------------------------------------------------------

/**
 * While the puzzle is unsolved: the reserved hint line (a polite live region) over the number pad.
 * Once solved: the result panel, at least as tall as that whole zone, so the grid above never
 * changes size when the puzzle ends.
 */
@Composable
private fun SudokuBottomZone(
    s: SudokuState,
    notesMode: Boolean,
    remainingCounts: Map<Int, Int>,
    hint: String,
    timeMillis: Long?,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    palette: SudokuPalette,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onToggleNotes: (Boolean) -> Unit,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!s.isOver) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HINT_SLOT_HEIGHT)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (hint.isNotEmpty()) contentDescription = hint
                    },
                contentAlignment = Alignment.Center
            ) {
                if (hint.isNotEmpty()) {
                    Text(
                        hint,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SudokuNumberPad(
                notesMode = notesMode,
                remainingCounts = remainingCounts,
                onDigit = onDigit,
                onErase = onErase,
                onToggleNotes = onToggleNotes,
                palette = palette
            )
        }
    } else {
        SudokuResultPanel(
            mistakes = s.mistakes,
            timeMillis = timeMillis,
            bestTimeMillis = bestTimeMillis,
            isNewBest = isNewBest,
            onNewPuzzle = onNewPuzzle,
            onBackToMenu = onBackToMenu,
            palette = palette,
            modifier = modifier
        )
    }
}

/**
 * Digits 1-5 on one row, 6-9 and Erase on the next (five equal slots each), then a full-width Notes
 * switch. Every key is 48dp tall and, down to a 264dp-wide pad, at least 48dp wide too. A digit that
 * is already correctly placed nine times dims and stops accepting taps. While Notes is on the digit
 * keys are drawn outlined instead of filled, so the mode shows without colour.
 */
@Composable
private fun SudokuNumberPad(
    notesMode: Boolean,
    remainingCounts: Map<Int, Int>,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onToggleNotes: (Boolean) -> Unit,
    palette: SudokuPalette
) {
    Column(
        modifier = Modifier.widthIn(max = PAD_MAX_WIDTH).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PAD_GAP)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PAD_GAP)) {
            for (d in 1..5) {
                SudokuDigitKey(
                    digit = d,
                    usedUp = (remainingCounts[d] ?: 1) <= 0,
                    notesMode = notesMode,
                    palette = palette,
                    onClick = { onDigit(d) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PAD_GAP)) {
            for (d in 6..9) {
                SudokuDigitKey(
                    digit = d,
                    usedUp = (remainingCounts[d] ?: 1) <= 0,
                    notesMode = notesMode,
                    palette = palette,
                    onClick = { onDigit(d) },
                    modifier = Modifier.weight(1f)
                )
            }
            SudokuEraseKey(palette = palette, onClick = onErase, modifier = Modifier.weight(1f))
        }
        SudokuNotesSwitch(notesOn = notesMode, onChange = onToggleNotes, palette = palette, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SudokuDigitKey(
    digit: Int,
    usedUp: Boolean,
    notesMode: Boolean,
    palette: SudokuPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val description = if (usedUp) "Digit $digit, all placed" else "Digit $digit"
    Box(
        modifier = modifier
            .height(PAD_KEY_HEIGHT)
            .clip(shape)
            .then(
                if (notesMode) {
                    Modifier.border(2.dp, palette.textPrimary, shape)
                } else {
                    Modifier.background(palette.chipBackground)
                }
            )
            .clickable(
                enabled = !usedUp,
                onClickLabel = if (notesMode) "Toggle note $digit" else "Enter $digit",
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            digit.toString(),
            color = if (usedUp) palette.textPrimary.copy(alpha = 0.35f) else palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

@Composable
private fun SudokuEraseKey(palette: SudokuPalette, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(PAD_KEY_HEIGHT)
            .clip(shape)
            .background(palette.chipBackground)
            .clickable(onClickLabel = "Erase the selected cell", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Erase" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 26.dp, height = 20.dp)) {
            drawEraseIcon(palette.textPrimary)
        }
    }
}

/** The Notes mode switch: a full-width 48dp pill whose label also says the state, so it never rides on colour. */
@Composable
private fun SudokuNotesSwitch(notesOn: Boolean, onChange: (Boolean) -> Unit, palette: SudokuPalette, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .height(PAD_KEY_HEIGHT)
            .clip(shape)
            .background(if (notesOn) palette.accent else palette.chipBackground)
            .toggleable(value = notesOn, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "Notes mode" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (notesOn) "Notes: on" else "Notes: off",
            color = if (notesOn) palette.textOnAccent else palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** A left-pointing "erase" tag with a cross, drawn to fill its Canvas (replaces a font-dependent glyph). */
private fun DrawScope.drawEraseIcon(ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.09f).coerceAtLeast(1.5f)
    val outline = Path().apply {
        moveTo(w * 0.34f, h * 0.10f)
        lineTo(w * 0.94f, h * 0.10f)
        lineTo(w * 0.94f, h * 0.90f)
        lineTo(w * 0.34f, h * 0.90f)
        lineTo(w * 0.04f, h * 0.50f)
        close()
    }
    drawPath(outline, color = ink, style = Stroke(width = stroke, join = StrokeJoin.Round))
    drawLine(color = ink, start = Offset(w * 0.52f, h * 0.34f), end = Offset(w * 0.76f, h * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color = ink, start = Offset(w * 0.76f, h * 0.34f), end = Offset(w * 0.52f, h * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
}

// ---------------------------------------------------------------------------
// Solved panel
// ---------------------------------------------------------------------------

@Composable
private fun SudokuResultPanel(
    mistakes: Int,
    timeMillis: Long?,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: SudokuPalette,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = palette.cellBackground, contentColor = palette.textPrimary),
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BOTTOM_ZONE_HEIGHT)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Puzzle solved!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                // A polite live region so a screen reader announces the win when the panel appears.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (timeMillis != null) "Mistakes: $mistakes · Time: ${formatSudokuClock(timeMillis)}" else "Mistakes: $mistakes",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (isNewBest) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", fontWeight = FontWeight.Bold)
            } else if (bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best time: ${formatSudokuClock(bestTimeMillis)}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onNewPuzzle,
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
