package com.gamesuite.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
import com.gamesuite.games.kenken.KenKenCage
import com.gamesuite.games.kenken.KenKenCell
import com.gamesuite.games.kenken.KenKenGame
import com.gamesuite.games.kenken.KenKenOperator
import com.gamesuite.games.kenken.KenKenState
import com.gamesuite.games.kenken.KenKenStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.victoryGlow
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlin.math.ceil
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Below this cell size (dp) the board stops shrinking and scrolls/pans instead. */
private const val MIN_CELL_DP = 28f

/** Upper bound so a small board does not sprawl across a tablet (a 9x9 tops out at 648dp). */
private const val MAX_CELL_DP = 72f

/** The rim around the grid (dp). Real padding of the board AND the `framePx` handed to [fitBoard], so the two cannot drift apart. It is also the room the heavy cage lines need to straddle the outer edge. */
private const val BOARD_FRAME_DP = 3f

/** The selected cell's ring: stroke width and the gap (dp) between the cell edge and the ring's outer edge, so it never merges with a cage line. */
private const val RING_STROKE_DP = 2f
private const val RING_INSET_DP = 2f

/** A cage clue sits in the top-left corner of its cell, inside the ring. Preferred size is cell-relative but never under [CLUE_PREF_MIN_DP]; a long label may shrink to [CLUE_MIN_DP] to stay inside the cell. */
private const val CLUE_INSET_X_DP = 4.5f
private const val CLUE_INSET_Y_DP = 2.5f
private const val CLUE_PREF_MIN_DP = 10f
private const val CLUE_MIN_DP = 9f
private const val CLUE_MAX_DP = 15f

/** Average advance of one bold clue glyph as a fraction of its font size, used to decide whether a label fits its cell. */
private const val CLUE_GLYPH_EM = 0.6f

/** Pencil marks: padding inside the cell, the smallest legible size (below it the marks are hidden rather than drawn unreadable) and the largest. */
private const val NOTE_PAD_DP = 3f
private const val NOTE_MIN_DP = 6f
private const val NOTE_MAX_DP = 16f

/** How long a "that did nothing" hint stays on screen after the last rejected input. */
private const val HINT_VISIBLE_MS = 2500L

/** The solve's glow. Off under [LocalReducedMotion]; the chime and haptic are not. */
private const val WIN_GLOW_MS = 350

/** Portrait content never stretches wider than this, so a tablet in portrait is not a sea of cream. */
private val CONTENT_MAX_WIDTH = 560.dp

/** Wide-window layout: the cap on the whole board-plus-column row, and the right-hand column's width range. */
private val SIDE_BY_SIDE_MAX_WIDTH = 1040.dp
private val SIDE_PANE_MIN_WIDTH = 260.dp
private val SIDE_PANE_MAX_WIDTH = 340.dp

/** The number pad: 48dp-tall keys, at least 48dp wide, in as many balanced rows as the width needs. */
private val PAD_KEY_HEIGHT = 48.dp
private const val PAD_GAP_DP = 6f
private const val PAD_KEY_MIN_WIDTH_DP = 48f
private const val PAD_KEY_MAX_WIDTH_DP = 60f

/** The reserved line above the pad for a "that did nothing" hint, so showing it never shifts the pad. */
private val HINT_SLOT_HEIGHT = 24.dp

/** The result panel is at least this tall, and at least as tall as the whole playing zone, so solving never resizes the board. */
private val RESULT_ZONE_MIN_HEIGHT = 164.dp

private const val HELP_TEXT =
    "Fill the grid so every row and every column holds each number from 1 up to the grid size " +
        "exactly once (4 on Easy, 6 on Medium, 9 on Hard); there are no boxes. Heavy outlines " +
        "split the grid into cages, and each cage's top-left cell shows a target and an " +
        "operator: the numbers in the cage must combine to that target with + (add), × " +
        "(multiply), − (larger minus smaller) or ÷ (larger divided by smaller), where − and ÷ " +
        "only appear on two-cell cages and a lone target is a one-cell cage holding exactly that " +
        "number.\n\n" +
        "A number may repeat inside a cage as long as no two copies share a row or column. Tap a " +
        "cell, then a number to fill it; turn Notes on to pencil in candidates instead, and " +
        "Erase removes the number in the selected cell.\n\n" +
        "A wrong number is accepted but marked at once and counted as a mistake. The daily " +
        "puzzle is the same for everyone on the same difficulty."

/** A tier switch (a tier) or a new puzzle at the same tier (null) that is waiting on the player's confirmation. */
private data class KenKenPendingChange(val tier: CpuDifficulty?)

/**
 * Renders KenKenGame's state reactively — same overall shape as SudokuScreen (difficulty selector,
 * live status row, solved-puzzle panel, select-then-enter number pad with a notes-mode toggle),
 * since KenKenGame's own KDoc (SCOPING DECISION 5) deliberately mirrors Sudoku's input model
 * rather than inventing a new one. The one genuinely KenKen-specific rendering job is drawing the
 * CAGES: irregular polyomino outlines plus each cage's target and operator in its top-left-most
 * cell — see [KenKenBoardSlot]. Used for both the free-play route and the daily route
 * ([dailySeed]).
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
 * is on screen. A difficulty switch made mid-puzzle replays today's puzzle at the new difficulty;
 * "New" and any switch made after the puzzle is solved deal a random puzzle and drop the tag. Both
 * ask first when the puzzle has entries, since they discard them.
 *
 * LAYOUT: the grid is sized by [fitBoard] against the measured space of its own slot, in whole
 * pixels (the rim is handed to it as `framePx`), clamped to [MIN_CELL_DP]..[MAX_CELL_DP]; a slot
 * that cannot give a [MIN_CELL_DP] cell pans instead of shrinking the cells further. A 9x9 fits a
 * 312dp-wide window at about 31dp cells without panning. Portrait stacks tabs / status / grid / pad
 * (or result panel) in a column capped at [CONTENT_MAX_WIDTH]; a wide landscape window puts the
 * grid on the left and tabs, status and pad in a column on the right. The result panel replaces
 * the pad in place and is at least as tall as it, so solving never resizes the grid. The pad's
 * keys are 48dp tall and at least 48dp wide, wrapped into balanced rows by the available width (a
 * 9x9's nine keys would need 480dp in one row), with Erase and a Notes switch below.
 *
 * CAGES: the grid is drawn in two layers inside one board so a cage boundary is one stroke, not
 * two cells' worth of border: the cells (fill, clue, digit, pencil marks), then a single Canvas
 * with the thin cell lines, the heavy cage outlines (one stroke width everywhere, including the
 * board edge), the heavier outline of the SELECTED cage, the selected cell's ring and the
 * wrong-cell marks. A clue is at least [CLUE_PREF_MIN_DP] and shrinks only as far as a long label
 * needs to stay inside its cell, never under [CLUE_MIN_DP] (a four-digit target such as "1080×"
 * fits a 31dp cell at that floor; only a pan-mode cell under about 31dp can clip its last glyph).
 * Pencil marks are two rows (digits 1-5 over 6-9 on a 9x9) in the area under the clue, at the same
 * size in every cell, and are hidden only when they could not be drawn at [NOTE_MIN_DP] (a cell
 * under about 31dp).
 *
 * ACCESSIBILITY: every cell is one button described like ReversiScreen.CellView (its digit, or
 * "Empty" and any notes, then 1-indexed row and column, then its cage as "cage 12 plus, 3 cells",
 * then "selected"). Cage membership is never colour alone: cages are outlined, the selected cage's
 * outline is heavier, and the clue is text. A wrong digit gets a tinted fill, a corner mark and
 * digit ink chosen for contrast on that fill (it replaces the selected fill, which fixes the old
 * bug where a wrong digit typed into the still-selected cell rendered cream on orange and could not
 * be seen); the selected cell has an inset ring. Under [LocalColorblindMode] cells holding the
 * selected digit also get an inset outline; the row/column wash is a pure aid the grid lines
 * already convey, so it stays a tint. Tabs are radio buttons, Notes is a switch, and every
 * non-grid control is at least 48dp. The mistake counter is a polite live region, and a rejected
 * input (no cell selected, nothing to erase, a note on a filled cell) buzzes the failure haptic and
 * says so in a polite hint line.
 *
 * FEEDBACK AND MOTION: a wrong digit plays the failure haptic and the buzz; a solve plays the
 * success chime and the celebration haptic (a solve is always the human's, there is no opponent)
 * BEFORE the stats write, and the stats write is not cancelled by dealing the next puzzle. The solve
 * also glows the screen for [WIN_GLOW_MS]ms, off under [LocalReducedMotion].
 *
 * CLOCK: shown by its own child composable reading [KenKenGame.activeElapsedMillis], so the 5Hz tick
 * recomposes only that Text (not the grid), freezes while the engine is paused, and ends on exactly
 * the recorded solve time.
 *
 * VISUAL IDENTITY: shares its warm background/accent/chip tokens with every other new-game screen in
 * this batch (see [kenkenPalette]), extended with KenKen-specific tokens (cage line, same-cage
 * tint). Never reads `MaterialTheme.colorScheme` for gameplay colors, same standing rule as every
 * other game's board.
 */
@Composable
fun KenKenScreen(
    sessionManager: GameSessionManager,
    game: KenKenGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's puzzle for every player — see KenKenGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { KenKenStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = kenkenPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current

    // True while the puzzle on screen is today's seeded puzzle. Any later fresh puzzle (New, or a
    // tier switch once the puzzle is solved) is random again, and the status row says so.
    var dailyActive by remember { mutableStateOf(dailySeed != null) }
    // Bumped by every puzzle this screen starts, so per-puzzle UI state resets even if the engine
    // happened to deal the same solution twice in a row (a daily replayed at another difficulty).
    var puzzleId by remember { mutableStateOf(0) }
    var notesMode by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var hintTick by remember { mutableStateOf(0) }
    var pendingChange by remember { mutableStateOf<KenKenPendingChange?>(null) }

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
            val tier = game.difficulty
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            // NonCancellable: dealing the next puzzle straight away flips s.won back, which would
            // otherwise cancel this effect mid-write and silently drop the solve's record.
            reportedNewBest = withContext(NonCancellable) { statsStore.recordWin(tier, finalTime) }
        }
    }

    // A digit is "used up" once it's correctly placed in all `size` of its occurrences (every
    // valid Latin square has each digit exactly `size` times -- once per row) -- disabling it on
    // the number pad at that point is a purely-cosmetic convenience, same idiom as SudokuScreen's
    // own remainingCounts; it never blocks re-entering that digit elsewhere.
    val remainingCounts = remember(s.cells, s.solution) {
        (1..s.size).associateWith { d ->
            s.size - s.cells.indices.count { s.cells[it].value == d && d == s.solution[it] }
        }
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
            if (live != null && kenKenHasProgress(live)) pendingChange = KenKenPendingChange(tier) else switchTier(tier)
        }
    }
    val onNewPuzzle: () -> Unit = {
        val live = game.state.value
        if (live != null && kenKenHasProgress(live)) pendingChange = KenKenPendingChange(null) else startRound(false)
    }

    val onTapCell: (Int) -> Unit = { index ->
        // Re-read the live state: the flag a cell was composed with can be a frame stale, and a
        // tap on a solved board (or after the session ended) must not select or tick.
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
            if (index == null) {
                haptics(HapticSignal.FAILURE)
                showHint("Select a cell first")
            } else if (notesMode) {
                if (live.cells[index].value != null) {
                    haptics(HapticSignal.FAILURE)
                    showHint("Erase the number to add notes")
                } else {
                    game.toggleNote(d)
                    hint = ""
                    haptics(HapticSignal.LIGHT_TICK)
                }
            } else {
                game.setValue(d)
                hint = ""
                val after = game.state.value
                if (after != null && after.mistakes > live.mistakes) {
                    // The engine accepts a wrong entry and counts it; the cell itself now shows the
                    // wrong mark, and this is the matching buzz and haptic.
                    haptics(HapticSignal.FAILURE)
                    playSfx(SfxKind.INVALID_BUZZ)
                } else if (after?.won != true) {
                    // (A winning entry plays the solve's own chime and haptic from the effect above.)
                    sounds.playTap()
                    haptics(HapticSignal.NORMAL_ACTION)
                }
            }
        }
    }
    val onErase: () -> Unit = {
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value) {
            val index = live.selectedIndex
            if (index == null) {
                haptics(HapticSignal.FAILURE)
                showHint("Select a cell first")
            } else if (live.cells[index].value == null) {
                haptics(HapticSignal.FAILURE)
                showHint(
                    if (live.cells[index].notes.isNotEmpty()) "Tap a note's number again to remove it" else "Nothing to erase here"
                )
            } else {
                game.clearValue()
                hint = ""
                haptics(HapticSignal.LIGHT_TICK)
            }
        }
    }

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-puzzle discards
    // ONLY the unfinished puzzle: if puzzles were already solved this session, leaving goes through
    // leaveSession() (the same call the solved panel's "Back to Menu" makes) so they still count;
    // with nothing solved it is a pure abort (never a win or loss). A solved puzzle leaves through
    // leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play KenKen",
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
                // The shared "big win" glow, timed to the same s.won moment the CELEBRATION haptic
                // above fires from, shortened to WIN_GLOW_MS and gated on reduced motion.
                .victoryGlow(trigger = s.won && !reducedMotion, tint = palette.accent, durationMs = WIN_GLOW_MS)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            val availableHeight = maxHeight
            // Grid left + controls right only when the window is genuinely wide; every phone
            // portrait, the Fold cover screen included, takes the stacked layout.
            val sideBySide = maxWidth >= 480.dp && maxWidth > maxHeight * 1.15f

            val tabs: @Composable (Modifier) -> Unit = { tabsModifier ->
                KenKenTabs(current = game.difficulty, palette = palette, onSelect = onSelectTier, modifier = tabsModifier)
            }
            val status: @Composable (Modifier) -> Unit = { statusModifier ->
                KenKenStatus(
                    mistakes = s.mistakes,
                    daily = dailyActive,
                    game = game,
                    onNewPuzzle = onNewPuzzle,
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val board: @Composable (Modifier) -> Unit = { boardModifier ->
                KenKenBoardSlot(
                    s = s,
                    palette = palette,
                    colorblind = colorblind,
                    onTapCell = onTapCell,
                    modifier = boardModifier
                )
            }
            val bottom: @Composable (Modifier) -> Unit = { zoneModifier ->
                KenKenBottomZone(
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
                    onNewPuzzle = { startRound(false) },
                    onBackToMenu = { game.leaveSession() },
                    modifier = zoneModifier
                )
            }

            if (sideBySide) {
                val paneWidth = (maxWidth * 0.4f).coerceIn(SIDE_PANE_MIN_WIDTH, SIDE_PANE_MAX_WIDTH)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = SIDE_BY_SIDE_MAX_WIDTH)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        board(Modifier.weight(1f).fillMaxHeight())
                        Box(
                            modifier = Modifier.width(paneWidth).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                // The top of this column is the top-right occupant of the layout, so
                                // the tab row keeps the corner button's width free at its end.
                                tabs(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                                status(Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                bottom(Modifier.fillMaxWidth())
                            }
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

    val change = pendingChange
    if (change != null) {
        val tier = change.tier
        AlertDialog(
            onDismissRequest = { pendingChange = null },
            containerColor = palette.background,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = { Text(if (tier != null) "Switch to ${kenKenTierLabel(tier)}?" else "Deal a new puzzle?") },
            text = { Text(kenKenChangeBody(tier, dailyActive)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingChange = null
                        if (tier != null) switchTier(tier) else startRound(false)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text(if (tier != null) "Switch" else "New Puzzle", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingChange = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text("Keep Playing") }
            }
        )
    }
}

/** True while the puzzle has entries a "new puzzle" or tier switch would throw away (a number or a note the player placed). */
private fun kenKenHasProgress(s: KenKenState): Boolean =
    !s.isOver && s.cells.any { it.value != null || it.notes.isNotEmpty() }

private fun kenKenTierLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

/** The confirm dialog's body: what the pending tier switch or new puzzle will do, and that it discards the entries so far. */
private fun kenKenChangeBody(tier: CpuDifficulty?, dailyActive: Boolean): String = when {
    tier != null && dailyActive ->
        "This starts today's ${kenKenTierLabel(tier)} puzzle from scratch. Your entries so far are cleared."
    tier != null ->
        "This deals a new ${kenKenTierLabel(tier)} puzzle. Your current puzzle and entries are discarded."
    dailyActive ->
        "This replaces the daily puzzle with a random one. Your entries so far are discarded."
    else ->
        "Your current puzzle and entries are discarded."
}

private fun formatKenKenClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

/** A text size that tracks the board's geometry, not the user's font scale: [dpValue] dp, converted so `sp x fontScale` comes back to exactly that. */
private fun Density.kenKenSp(dpValue: Float): TextUnit = dpValue.dp.toSp()

/** "12+" / "3−" / "6×" / "2÷" for a normal cage, or just the bare target (e.g. "5") for a single-cell cage — see [KenKenCage.operator]'s KDoc for why that one has no operator symbol at all. */
private fun cageLabel(cage: KenKenCage): String {
    val opSymbol = when (cage.operator) {
        null -> ""
        KenKenOperator.ADD -> "+"
        KenKenOperator.SUB -> "−"
        KenKenOperator.MUL -> "×"
        KenKenOperator.DIV -> "÷"
    }
    return "${cage.target}$opSymbol"
}

/** The cage clue as a screen reader says it: "cage 12 plus, 3 cells" / "single cell cage, target 5". */
private fun kenKenCageSpeech(cage: KenKenCage): String {
    val op = cage.operator
    return if (op == null) {
        "single cell cage, target ${cage.target}"
    } else {
        val word = when (op) {
            KenKenOperator.ADD -> "plus"
            KenKenOperator.SUB -> "minus"
            KenKenOperator.MUL -> "times"
            KenKenOperator.DIV -> "divided by"
        }
        "cage ${cage.target} $word, ${cage.cellIndices.size} cells"
    }
}

/**
 * What a screen reader says for one cell: its number (or "Empty" plus any notes), whether that
 * number is wrong, then 1-indexed row and column, then its cage, then "selected". Same shape as
 * ReversiScreen.CellView ("Dark disc, row 3, column 4").
 */
private fun kenKenCellDescription(
    row: Int,
    col: Int,
    cell: KenKenCell,
    isSelected: Boolean,
    isWrong: Boolean,
    cageSpeech: String
): String {
    val what = when {
        cell.value != null && isWrong -> "${cell.value}, wrong"
        cell.value != null -> "${cell.value}"
        cell.notes.isNotEmpty() -> "Empty, notes ${cell.notes.sorted().joinToString(" ")}"
        else -> "Empty"
    }
    val base = "$what, row ${row + 1}, column ${col + 1}, $cageSpeech"
    return if (isSelected) "$base, selected" else base
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- shares its base tokens (background, accent,
// textPrimary, textOnAccent, chipBackground) with every other new-game screen
// in this batch (see SudokuScreen's own palette KDoc for why this doesn't read
// MaterialTheme.colorScheme), extended with KenKen-specific tokens (cage line,
// same-cage tint). Every ink is picked for >= 4.5:1 against the fills it can
// sit on (textOnAccent on the selected fill, wrongText on wrongFill, noteText
// on the cell fill); the old cream-on-orange selected digit was only ~2.5:1.
// ---------------------------------------------------------------------------
private data class KenKenPalette(
    val background: Color,
    val cellBackground: Color,
    val selectedCell: Color,
    /** The selected cell's row and column: a light wash the grid lines already convey. */
    val peerHighlight: Color,
    val sameValueHighlight: Color,
    /** The other cells of the SELECTED cage; a different hue from the row/column wash, backed by a heavier outline. */
    val cageHighlight: Color,
    /** Fill of a wrong cell, selected or not (it replaces the selected fill so the digit stays readable). */
    val wrongFill: Color,
    val wrongText: Color,
    /** The corner mark on a wrong cell. */
    val danger: Color,
    /** The cage outlines, the board edge and (at low alpha) the thin cell lines. */
    val cageBorder: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val noteText: Color
)

private val KenKenLightPalette = KenKenPalette(
    background = Color(0xFFFBF1E6),
    cellBackground = Color(0xFFFFFBF5),
    selectedCell = Color(0xFFE08D4B),
    peerHighlight = Color(0xFFF3E4D2),
    sameValueHighlight = Color(0xFFF0CFA0),
    cageHighlight = Color(0xFFE6EBCB),
    wrongFill = Color(0xFFFBD3CC),
    wrongText = Color(0xFFA8201A),
    danger = Color(0xFFC23B22),
    cageBorder = Color(0xFF3A2E22),
    accent = Color(0xFFE08D4B),
    textPrimary = Color(0xFF3A2E22),
    // Dark ink: cream text on this orange was only ~2.5:1.
    textOnAccent = Color(0xFF2B1F12),
    chipBackground = Color(0xFFE3CBA9),
    noteText = Color(0xFF6F5F4C)
)

private val KenKenDarkPalette = KenKenPalette(
    background = Color(0xFF1C1712),
    cellBackground = Color(0xFF15110D),
    selectedCell = Color(0xFFE8985B),
    peerHighlight = Color(0xFF2B241D),
    sameValueHighlight = Color(0xFF4A3A26),
    cageHighlight = Color(0xFF2F3523),
    wrongFill = Color(0xFF5A2A22),
    wrongText = Color(0xFFFFB4A5),
    danger = Color(0xFFFF7A63),
    cageBorder = Color(0xFFF3E9DB),
    accent = Color(0xFFE8985B),
    textPrimary = Color(0xFFF3E9DB),
    textOnAccent = Color(0xFF1C1712),
    chipBackground = Color(0xFF453A2E),
    noteText = Color(0xFFB8A892)
)

/** Shared instances, so a recomposition never allocates a fresh palette again. */
private fun kenkenPalette(isDark: Boolean): KenKenPalette = if (isDark) KenKenDarkPalette else KenKenLightPalette

// ---------------------------------------------------------------------------
// Header: difficulty tabs, status row, clock
// ---------------------------------------------------------------------------

/**
 * The EASY/MEDIUM/HARD tabs. `horizontalScroll` is the fallback for a window too narrow for three
 * pills at a large font scale, so a pill is never compressed to a sliver. Callers give it
 * `padding(end = GameChromeEndInset)`.
 */
@Composable
private fun KenKenTabs(
    current: CpuDifficulty,
    palette: KenKenPalette,
    onSelect: (CpuDifficulty) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (tier in CpuDifficulty.entries) {
            KenKenTab(
                label = kenKenTierLabel(tier),
                selected = tier == current,
                palette = palette,
                onClick = { onSelect(tier) }
            )
        }
    }
}

/** A pill with a 48dp-tall touch target around it, announced as a radio button. */
@Composable
private fun KenKenTab(label: String, selected: Boolean, palette: KenKenPalette, onClick: () -> Unit) {
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
 * Mistakes, the clock and the New button on one line, with a "Daily puzzle" tag above them while
 * today's seeded puzzle is on screen. At least 48dp tall.
 */
@Composable
private fun KenKenStatus(
    mistakes: Int,
    daily: Boolean,
    game: KenKenGame,
    onNewPuzzle: () -> Unit,
    palette: KenKenPalette,
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
            KenKenClock(game = game, palette = palette)
            Spacer(Modifier.width(12.dp))
            KenKenNewButton(onClick = onNewPuzzle, palette = palette)
        }
    }
}

/** A 48dp-tall outlined pill. A word, not a glyph, so it reads on its own and needs no semantics workaround. */
@Composable
private fun KenKenNewButton(onClick: () -> Unit, palette: KenKenPalette) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, palette.textPrimary.copy(alpha = 0.4f), shape)
            .clickable(onClickLabel = "Deal a new puzzle", role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "New",
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the whole screen and every cell. Reads [KenKenGame.activeElapsedMillis], so it freezes while the
 * engine is paused (app backgrounded) and ends on exactly the recorded solve time.
 */
@Composable
private fun KenKenClock(game: KenKenGame, palette: KenKenPalette) {
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
    val text = formatKenKenClock(elapsed)
    Text(
        text,
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { contentDescription = "Time $text" }
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * The grid's own slot. Sizes from THIS slot's measured space (not an outer scope) with [fitBoard],
 * which never returns a footprint larger than the space it is given, so the old
 * `coerceAtLeast(28.dp)` floor (which let the board exceed its container) and its fudge divisor
 * are gone. Everything is worked out in whole pixels: the cell is floored to a pixel, the rim is a
 * whole number of pixels, and the dp values the layout uses are converted back from those, so the
 * Canvas that draws the lines and the cells it draws them over can never drift apart. When the slot
 * cannot give a cell [MIN_CELL_DP] the board keeps that size and pans in both axes instead of
 * shrinking further.
 *
 * Two layers share one [Box] the size of the whole board (see the class KDoc, CAGES): the cells and
 * the line Canvas over them. Only the cells take input and carry semantics; the Canvas has no
 * pointer handling, so taps (and screen-reader exploration) fall through to the cells below.
 */
@Composable
private fun KenKenBoardSlot(
    s: KenKenState,
    palette: KenKenPalette,
    colorblind: Boolean,
    onTapCell: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val n = s.size
    val cageSpeechById = remember(s.cages) { s.cages.associate { it.id to kenKenCageSpeech(it) } }
    // Each cage's clue lives in its top-left-most cell (cellIndices is sorted ascending).
    val clueLabelByCell = remember(s.cages) { s.cages.associate { it.cellIndices.min() to cageLabel(it) } }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val framePx = with(density) { BOARD_FRAME_DP.dp.roundToPx() }
        val minCellPx = with(density) { MIN_CELL_DP.dp.toPx() }
        val maxCellPx = with(density) { MAX_CELL_DP.dp.toPx() }
        val availableWidthPx = constraints.maxWidth.toFloat()
        val availableHeightPx = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else availableWidthPx
        val fit = fitBoard(
            availableWidthPx = availableWidthPx,
            availableHeightPx = availableHeightPx,
            columns = n,
            rows = n,
            framePx = framePx.toFloat(),
            minCellPx = minCellPx,
            maxCellPx = maxCellPx
        )
        val scrolls = !fit.meetsMinimum
        val cellPx = (if (scrolls) minCellPx else fit.cellPx).toInt().coerceAtLeast(1)
        val cellDp = with(density) { cellPx.toDp() }
        val frameDp = with(density) { framePx.toDp() }
        val boardDp = with(density) { (cellPx * n + 2 * framePx).toDp() }
        val cellDpValue = cellDp.value

        // Sizes that follow the cell, all in dp. The clue band is what the pencil marks start under.
        val clueDp = (cellDpValue * 0.30f).coerceIn(CLUE_PREF_MIN_DP, CLUE_MAX_DP)
        val digitDp = (cellDpValue * 0.5f).coerceIn(14f, 34f)
        val noteCols = (n + 1) / 2
        val noteTopDp = CLUE_INSET_Y_DP + clueDp * 1.1f + 0.5f
        val noteAreaHeightDp = cellDpValue - noteTopDp - NOTE_PAD_DP
        val noteAreaWidthDp = cellDpValue - 2f * NOTE_PAD_DP
        val noteFitDp = minOf(noteAreaHeightDp / 2f * 0.88f, noteAreaWidthDp / noteCols / 0.62f, NOTE_MAX_DP)
        val noteFontDp: Float? = if (noteFitDp >= NOTE_MIN_DP) noteFitDp else null

        // One stroke width for every cage line (and the board edge), the selected cage's heavier.
        val cageStrokeDp = (cellDpValue * 0.08f).coerceIn(2f, 3.5f)
        val cageStrokePx = with(density) { cageStrokeDp.dp.toPx() }
        val emphasisStrokePx = cageStrokePx * 1.7f
        val thinStrokePx = with(density) { 1f.dp.toPx() }
        val ringStrokePx = with(density) { RING_STROKE_DP.dp.toPx() }
        val ringInsetPx = with(density) { RING_INSET_DP.dp.toPx() }

        // A solved board shows no selection (no ring, no tints, no heavier cage), just the solution.
        val selected = if (s.isOver) null else s.selectedIndex
        val selectedRow = selected?.let { it / n }
        val selectedCol = selected?.let { it % n }
        val selectedValue = selected?.let { s.cells[it].value }
        val selectedCageId = selected?.let { s.cells[it].cageId }
        val selectedCage = selectedCageId?.let { id -> s.cages.firstOrNull { it.id == id } }
        val wrongIndices = s.cells.indices.filter { i ->
            val v = s.cells[i].value
            v != null && v != s.solution[i]
        }
        val wrongSet = wrongIndices.toSet()
        val sameValueIndices = if (colorblind && selectedValue != null) {
            s.cells.indices.filter { it != selected && s.cells[it].value == selectedValue }
        } else {
            emptyList()
        }
        // Fill priority: wrong, selected, same number as the selection, selected cage, row/column of
        // the selection, plain. A wrong cell always takes the wrong fill, even while it is the
        // selected cell, which is what keeps a wrong number typed into the still-selected cell visible.
        val cellBackgrounds = List(n * n) { index ->
            val cell = s.cells[index]
            when {
                index in wrongSet -> palette.wrongFill
                index == selected -> palette.selectedCell
                selectedValue != null && cell.value == selectedValue -> palette.sameValueHighlight
                selectedCageId != null && cell.cageId == selectedCageId -> palette.cageHighlight
                selected != null && (index / n == selectedRow || index % n == selectedCol) -> palette.peerHighlight
                else -> palette.cellBackground
            }
        }

        Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Box(modifier = Modifier.size(boardDp)) {
                // Layer 1: the cells.
                Column(modifier = Modifier.padding(frameDp)) {
                    for (row in 0 until n) {
                        Row {
                            for (col in 0 until n) {
                                val index = row * n + col
                                val cell = s.cells[index]
                                KenKenCellView(
                                    row = row,
                                    col = col,
                                    cell = cell,
                                    cellDp = cellDp,
                                    background = cellBackgrounds[index],
                                    isSelected = index == selected,
                                    isWrong = index in wrongSet,
                                    digitDp = digitDp,
                                    clueLabel = clueLabelByCell[index],
                                    clueDp = clueDp,
                                    noteFontDp = noteFontDp,
                                    noteTopDp = noteTopDp,
                                    noteCols = noteCols,
                                    boardSize = n,
                                    cageSpeech = cageSpeechById.getValue(cell.cageId),
                                    enabled = !s.isOver,
                                    palette = palette,
                                    onTap = { onTapCell(index) }
                                )
                            }
                        }
                    }
                }

                // Layer 2: every line and mark drawn over the cells, in one Canvas. Not interactive
                // and not in the accessibility tree: a Canvas has no pointer input or semantics, so
                // taps and screen-reader exploration fall through to the cells.
                Canvas(modifier = Modifier.matchParentSize()) {
                    val cell = cellPx.toFloat()
                    val frame = framePx.toFloat()
                    val extent = cell * n

                    // Thin lines between every pair of neighbouring cells.
                    val thinColor = palette.cageBorder.copy(alpha = 0.28f)
                    for (i in 1 until n) {
                        val at = frame + i * cell
                        drawLine(color = thinColor, start = Offset(at, frame), end = Offset(at, frame + extent), strokeWidth = thinStrokePx)
                        drawLine(color = thinColor, start = Offset(frame, at), end = Offset(frame + extent, at), strokeWidth = thinStrokePx)
                    }

                    // Heavy cage outlines: a segment wherever a cell's left or top neighbour is in a
                    // different cage. Square caps close every corner, and the board edge below is
                    // the same stroke width, so a boundary reads the same inside and at the edge.
                    for (r in 0 until n) {
                        for (c in 0 until n) {
                            val id = s.cells[r * n + c].cageId
                            if (c > 0 && s.cells[r * n + c - 1].cageId != id) {
                                val x = frame + c * cell
                                drawLine(
                                    color = palette.cageBorder,
                                    start = Offset(x, frame + r * cell),
                                    end = Offset(x, frame + (r + 1) * cell),
                                    strokeWidth = cageStrokePx,
                                    cap = StrokeCap.Square
                                )
                            }
                            if (r > 0 && s.cells[(r - 1) * n + c].cageId != id) {
                                val y = frame + r * cell
                                drawLine(
                                    color = palette.cageBorder,
                                    start = Offset(frame + c * cell, y),
                                    end = Offset(frame + (c + 1) * cell, y),
                                    strokeWidth = cageStrokePx,
                                    cap = StrokeCap.Square
                                )
                            }
                        }
                    }
                    drawRect(
                        color = palette.cageBorder,
                        topLeft = Offset(frame, frame),
                        size = Size(extent, extent),
                        style = Stroke(width = cageStrokePx)
                    )

                    // The selected cage's whole outline, heavier: the one constraint the selected
                    // cell belongs to, readable without relying on its tint.
                    if (selectedCage != null) {
                        for (index in selectedCage.cellIndices) {
                            val r = index / n
                            val c = index % n
                            val left = frame + c * cell
                            val right = left + cell
                            val top = frame + r * cell
                            val bottom = top + cell
                            if (r == 0 || s.cells[index - n].cageId != selectedCage.id) {
                                drawLine(palette.cageBorder, Offset(left, top), Offset(right, top), emphasisStrokePx, StrokeCap.Square)
                            }
                            if (r == n - 1 || s.cells[index + n].cageId != selectedCage.id) {
                                drawLine(palette.cageBorder, Offset(left, bottom), Offset(right, bottom), emphasisStrokePx, StrokeCap.Square)
                            }
                            if (c == 0 || s.cells[index - 1].cageId != selectedCage.id) {
                                drawLine(palette.cageBorder, Offset(left, top), Offset(left, bottom), emphasisStrokePx, StrokeCap.Square)
                            }
                            if (c == n - 1 || s.cells[index + 1].cageId != selectedCage.id) {
                                drawLine(palette.cageBorder, Offset(right, top), Offset(right, bottom), emphasisStrokePx, StrokeCap.Square)
                            }
                        }
                    }

                    // Colorblind-only cue: an inset outline on every cell holding the selected number.
                    for (index in sameValueIndices) {
                        if (index in wrongSet) continue
                        val inset = 3.dp.toPx() + ringInsetPx
                        drawRect(
                            color = palette.textPrimary.copy(alpha = 0.7f),
                            topLeft = Offset(frame + (index % n) * cell + inset, frame + (index / n) * cell + inset),
                            size = Size(cell - 2f * inset, cell - 2f * inset),
                            style = Stroke(width = 1.5f.dp.toPx())
                        )
                    }

                    // A wrong number's corner mark (top-right; the clue owns the top-left).
                    val mark = cell * 0.30f
                    for (index in wrongIndices) {
                        val right = frame + (index % n + 1) * cell
                        val top = frame + (index / n) * cell
                        val corner = Path().apply {
                            moveTo(right - mark, top)
                            lineTo(right, top)
                            lineTo(right, top + mark)
                            close()
                        }
                        drawPath(corner, color = palette.danger)
                    }

                    // The selected cell's ring, inset so it never merges with a cage line.
                    if (selected != null) {
                        val half = ringStrokePx / 2f
                        val side = cell - 2f * (ringInsetPx + half)
                        drawRect(
                            color = palette.textPrimary,
                            topLeft = Offset(
                                frame + (selected % n) * cell + ringInsetPx + half,
                                frame + (selected / n) * cell + ringInsetPx + half
                            ),
                            size = Size(side, side),
                            style = Stroke(width = ringStrokePx)
                        )
                    }
                }
            }
        }
    }
}

/**
 * One cell: its fill, its cage clue (only the cage's top-left-most cell has one, [clueLabel]), its
 * number or its pencil marks. The WHOLE [cellDp] square is the touch target and the accessibility
 * node, described by [kenKenCellDescription]; the clue, number and marks are hidden from the
 * screen reader because that description already says them. Cage lines, the selection ring and the
 * wrong mark are drawn by the board's Canvas over the cells.
 *
 * The clue sits in the top-left corner at [clueDp] (never under [CLUE_PREF_MIN_DP]); a long label
 * shrinks only as far as it needs to stay inside the cell, and never under [CLUE_MIN_DP].
 */
@Composable
private fun KenKenCellView(
    row: Int,
    col: Int,
    cell: KenKenCell,
    cellDp: Dp,
    background: Color,
    isSelected: Boolean,
    isWrong: Boolean,
    digitDp: Float,
    clueLabel: String?,
    clueDp: Float,
    noteFontDp: Float?,
    noteTopDp: Float,
    noteCols: Int,
    boardSize: Int,
    cageSpeech: String,
    enabled: Boolean,
    palette: KenKenPalette,
    onTap: () -> Unit
) {
    val density = LocalDensity.current
    val ink = when {
        isWrong -> palette.wrongText
        isSelected -> palette.textOnAccent
        else -> palette.textPrimary
    }
    val description = remember(row, col, cell, isSelected, isWrong, cageSpeech) {
        kenKenCellDescription(row, col, cell, isSelected, isWrong, cageSpeech)
    }

    Box(
        modifier = Modifier
            .size(cellDp)
            .background(background)
            .clickable(enabled = enabled, onClickLabel = "Select cell", role = Role.Button, onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        val value = cell.value
        if (value != null) {
            Text(
                value.toString(),
                color = ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = density.kenKenSp(digitDp),
                lineHeight = density.kenKenSp(digitDp * 1.1f),
                // Nudged down a little in a clue cell, so a tall label above it never touches the digit.
                modifier = Modifier
                    .padding(top = if (clueLabel != null) (clueDp * 0.45f).dp else 0.dp)
                    .clearAndSetSemantics {}
            )
        } else if (cell.notes.isNotEmpty() && noteFontDp != null) {
            KenKenNotes(
                notes = cell.notes,
                boardSize = boardSize,
                cols = noteCols,
                topDp = noteTopDp,
                fontDp = noteFontDp,
                ink = if (isSelected) palette.textOnAccent else palette.noteText
            )
        }
        if (clueLabel != null) {
            val roomDp = cellDp.value - CLUE_INSET_X_DP - 2f
            val clueFontDp = minOf(clueDp, roomDp / (clueLabel.length * CLUE_GLYPH_EM)).coerceAtLeast(CLUE_MIN_DP)
            Text(
                clueLabel,
                color = if (isSelected && !isWrong) palette.textOnAccent else palette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = density.kenKenSp(clueFontDp),
                lineHeight = density.kenKenSp(clueFontDp * 1.1f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = CLUE_INSET_X_DP.dp, top = CLUE_INSET_Y_DP.dp)
                    .clearAndSetSemantics {}
            )
        }
    }
}

/**
 * A cell's pencil marks as two rows of [cols] tiny numbers (on a 9x9: 1-5 over 6-9), in the area
 * under the clue band, so a number is in the same place in every cell. Hidden from the screen
 * reader: the cell's own description already lists them.
 */
@Composable
private fun KenKenNotes(notes: Set<Int>, boardSize: Int, cols: Int, topDp: Float, fontDp: Float, ink: Color) {
    val density = LocalDensity.current
    val fontSize = density.kenKenSp(fontDp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = NOTE_PAD_DP.dp, end = NOTE_PAD_DP.dp, top = topDp.dp, bottom = NOTE_PAD_DP.dp)
            .clearAndSetSemantics {}
    ) {
        for (r in 0 until 2) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until cols) {
                    val digit = r * cols + c + 1
                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (digit <= boardSize && digit in notes) {
                            Text(
                                digit.toString(),
                                color = ink,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = fontSize,
                                lineHeight = fontSize,
                                maxLines = 1,
                                softWrap = false
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

/** How the digit keys wrap at a given width: [perRow] keys per row over [rows] rows, each [keyWidth] wide; [padHeight] is the whole pad (digit rows plus the Erase/Notes row). */
private data class KenKenPadMetrics(val perRow: Int, val rows: Int, val keyWidth: Dp, val padHeight: Dp)

/**
 * Fits `size` digit keys into [available] width at no less than 48dp wide each, in balanced rows
 * (9 keys at 288dp: 5 + 4, not 6 + 3), and no wider than [PAD_KEY_MAX_WIDTH_DP] so a wide window
 * does not grow a slab of keys.
 */
private fun kenKenPadMetrics(size: Int, available: Dp): KenKenPadMetrics {
    val maxPerRow = ((available.value + PAD_GAP_DP) / (PAD_KEY_MIN_WIDTH_DP + PAD_GAP_DP)).toInt().coerceAtLeast(1)
    val rows = ceil(size / maxPerRow.toFloat()).toInt().coerceAtLeast(1)
    val perRow = ceil(size / rows.toFloat()).toInt().coerceAtLeast(1)
    val keyWidth = ((available.value - PAD_GAP_DP * (perRow - 1)) / perRow).coerceIn(PAD_KEY_MIN_WIDTH_DP, PAD_KEY_MAX_WIDTH_DP)
    val padHeight = PAD_KEY_HEIGHT * (rows + 1) + PAD_GAP_DP.dp * rows
    return KenKenPadMetrics(perRow = perRow, rows = rows, keyWidth = keyWidth.dp, padHeight = padHeight)
}

/**
 * While the puzzle is unsolved: the reserved hint line (a polite live region) over the number pad.
 * Once solved: the result panel, at least as tall as that whole zone, so the board above never
 * changes size when the puzzle ends.
 */
@Composable
private fun KenKenBottomZone(
    s: KenKenState,
    notesMode: Boolean,
    remainingCounts: Map<Int, Int>,
    hint: String,
    timeMillis: Long?,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    palette: KenKenPalette,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onToggleNotes: (Boolean) -> Unit,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The outer Box carries the caller's modifier (which may scroll); the inner one is measured
    // only for its width, because the pad's row count (and so its height) depends on it.
    Box(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val metrics = kenKenPadMetrics(s.size, maxWidth)
            val zoneHeight = maxOf(HINT_SLOT_HEIGHT + 4.dp + metrics.padHeight, RESULT_ZONE_MIN_HEIGHT)
            if (!s.isOver) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = zoneHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
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
                    KenKenNumberPad(
                        size = s.size,
                        metrics = metrics,
                        notesMode = notesMode,
                        remainingCounts = remainingCounts,
                        onDigit = onDigit,
                        onErase = onErase,
                        onToggleNotes = onToggleNotes,
                        palette = palette
                    )
                }
            } else {
                KenKenResultPanel(
                    mistakes = s.mistakes,
                    timeMillis = timeMillis,
                    bestTimeMillis = bestTimeMillis,
                    isNewBest = isNewBest,
                    minHeight = zoneHeight,
                    onNewPuzzle = onNewPuzzle,
                    onBackToMenu = onBackToMenu,
                    palette = palette
                )
            }
        }
    }
}

/**
 * Digit keys 1..size in balanced rows ([KenKenPadMetrics]), then Erase and a Notes switch side by
 * side. Every key is 48dp tall and at least 48dp wide. A digit that is already correctly placed in
 * every row dims and stops accepting taps. While Notes is on the digit keys are drawn outlined
 * instead of filled, so the mode shows without colour.
 */
@Composable
private fun KenKenNumberPad(
    size: Int,
    metrics: KenKenPadMetrics,
    notesMode: Boolean,
    remainingCounts: Map<Int, Int>,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onToggleNotes: (Boolean) -> Unit,
    palette: KenKenPalette
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PAD_GAP_DP.dp)
    ) {
        for (digits in (1..size).chunked(metrics.perRow)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PAD_GAP_DP.dp)) {
                for (d in digits) {
                    KenKenDigitKey(
                        digit = d,
                        usedUp = (remainingCounts[d] ?: 1) <= 0,
                        notesMode = notesMode,
                        palette = palette,
                        onClick = { onDigit(d) },
                        modifier = Modifier.width(metrics.keyWidth)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            KenKenEraseKey(palette = palette, onClick = onErase, modifier = Modifier.weight(1f))
            KenKenNotesSwitch(notesOn = notesMode, onChange = onToggleNotes, palette = palette, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun KenKenDigitKey(
    digit: Int,
    usedUp: Boolean,
    notesMode: Boolean,
    palette: KenKenPalette,
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
private fun KenKenEraseKey(palette: KenKenPalette, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .height(PAD_KEY_HEIGHT)
            .clip(shape)
            .background(palette.chipBackground)
            .clickable(onClickLabel = "Erase the selected cell", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Erase" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Erase",
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** The Notes mode switch: a 48dp pill whose label also says the state, so it never rides on colour. */
@Composable
private fun KenKenNotesSwitch(notesOn: Boolean, onChange: (Boolean) -> Unit, palette: KenKenPalette, modifier: Modifier = Modifier) {
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

// ---------------------------------------------------------------------------
// Solved panel
// ---------------------------------------------------------------------------

@Composable
private fun KenKenResultPanel(
    mistakes: Int,
    timeMillis: Long?,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    minHeight: Dp,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: KenKenPalette,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.cellBackground, contentColor = palette.textPrimary),
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
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
                if (timeMillis != null) "Mistakes: $mistakes · Time: ${formatKenKenClock(timeMillis)}" else "Mistakes: $mistakes",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (isNewBest) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", fontWeight = FontWeight.Bold)
            } else if (bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best time: ${formatKenKenClock(bestTimeMillis)}", style = MaterialTheme.typography.bodyMedium)
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
