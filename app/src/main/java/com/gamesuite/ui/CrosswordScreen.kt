package com.gamesuite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.wordgames.crossword.CrosswordCell
import com.gamesuite.games.wordgames.crossword.CrosswordEntry
import com.gamesuite.games.wordgames.crossword.CrosswordGame
import com.gamesuite.games.wordgames.crossword.CrosswordPos
import com.gamesuite.games.wordgames.crossword.CrosswordState
import com.gamesuite.games.wordgames.crossword.Direction
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Smallest cell the grid will shrink to. Below this the board stops shrinking and the player pans
 * it (two-axis scroll) instead, so letters and clue numbers stay legible on a Fold 5 cover screen.
 */
private const val CROSSWORD_MIN_CELL_DP = 28f

/** Largest cell, so a small cropped puzzle does not sprawl across a tablet. */
private const val CROSSWORD_MAX_CELL_DP = 48f

/**
 * Below this cell size, a revealed cell's TopStart clue-number badge and its Center-aligned letter
 * have essentially no clearance from each other. Once a cell is revealed the number is no longer
 * needed (the player can already see the letter), so it is dropped instead of crowding the letter.
 */
private const val CROSSWORD_COMFORTABLE_CELL_DP = 32f

/**
 * Crossword screen: puzzle-book "newsprint" paper identity (a warm sheet with faint rules in
 * light mode, the same sheet inked dark in dark mode; see [crosswordPalette]).
 *
 * GRID: cropped to the bounding box of the lettered cells (the engine's 15x15 grid is mostly
 * empty) and sized with [fitBoard] against the slot it actually lives in, 28dp to 48dp cells. When
 * the slot cannot hold 28dp cells the board keeps 28dp and pans in both axes instead of shrinking
 * or overflowing. Every lettered cell is a button: tapping it selects an unsolved word through
 * that square (across first); tapping the same square again switches to the crossing word. The
 * selected word is outlined, the cursor square is tinted and outlined thicker (shape cues, not
 * colour alone), and the active-clue banner under the header shows the clue with Answer and Hint.
 * Letters a hint gave stay marked (italic letter plus a corner triangle) after the flash fades.
 *
 * ANSWERING: Answer (banner) or a tap on a clue row opens a dialog to type the whole word; the
 * system keyboard appears focused and Done submits. A correct word fills its squares with the
 * staggered letter reveal, a wrong one shakes.
 *
 * CHROME: [GameChrome] provides the corner menu (Back to Menu, How to Play, Hint) and back
 * handling. Leaving mid-puzzle with no puzzle solved yet is an abort that parks the game for
 * Resume; once at least one puzzle is solved it goes through leaveSession() so those solves still
 * count (and the dialog says so). The result panel (solved, hints used, New Puzzle / Back to
 * Menu) replaces the banner when the puzzle is complete.
 *
 * RESUME / HINT: the engine implements ResumableGame, so the screen starts with
 * `tryResume(game)` and reads difficulty from the engine afterwards. Hint reveals one letter
 * (selected square, else the next blank of the selected word) and spends the shared per-puzzle
 * hint budget; Crossword keeps no best-time record, so "assisted" shows only as the hint count on
 * the result panel.
 *
 * Premium 2026 pass (unchanged): per-entry staggered letter reveal, answer-dialog checkmark beat,
 * wrong-answer shake with FAILURE haptic, amber hint pulse distinct from the green solve pulse,
 * clue strike-through draw-on, a full-grid "wave of ink" finale, a cascading "Puzzle solved!"
 * headline and a three-tier haptic hierarchy. All of it is off under reduced motion.
 */
@Composable
fun CrosswordScreen(
    sessionManager: GameSessionManager,
    game: CrosswordGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val sheenEnabled = LocalCard3DMode.current && !reducedMotion
    val colorblind = LocalColorblindMode.current
    val haptics = rememberHaptics()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CROSSWORD, enabled = musicEnabled)
    val palette = crosswordPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

    // Solved-cell flash: submitAnswer() stamps the newly-revealed entry's cells into
    // justSolvedCells as a one-shot signal (see its KDoc); this clears it back to empty once
    // the flash has had time to play, so the next correct submit gets a fresh pulse.
    val justSolvedCells = game.justSolvedCells.value
    val justHintedCells = game.justHintedCells.value
    val hintedCells = game.hintedCells.value
    val selectedCell = game.selectedCell.value

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        // Picking this game from the menu after leaving it mid-puzzle offers Resume; when the
        // player took it the engine is put back exactly as it was (same puzzle, same difficulty,
        // same hint budget) instead of generating a new puzzle.
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    val s = state ?: return
    val puzzlesSolved = game.puzzlesSolved.value
    val hintsRemaining = game.hintsRemaining.value

    LaunchedEffect(justSolvedCells) {
        if (justSolvedCells.isNotEmpty()) {
            // A full-grid solve gets its own CELEBRATION tier below — this per-word
            // STRONG_ACTION tier is for every OTHER completed entry along the way.
            if (!s.matchOver) {
                haptics(HapticSignal.STRONG_ACTION)
                sounds.playPlace()
            }
            delay(if (reducedMotion) 0L else 450L)
            game.clearJustSolved()
        }
    }
    LaunchedEffect(justHintedCells) {
        if (justHintedCells.isNotEmpty()) {
            haptics(HapticSignal.LIGHT_TICK)
            sounds.playTap()
            delay(if (reducedMotion) 0L else 420L)
            game.clearJustHinted()
        }
    }

    // Full-grid finale: a soft "wave of ink" sweep across every filled cell, staggered by
    // distance from the top-left corner (see CrosswordCellView's draw block), plus a layered
    // completion flourish and the top haptic tier. Without the enhanced animations the wave is
    // simply parked past the far corner so the finished grid reads as uniformly "inked".
    val inkWave = remember { Animatable(0f) }
    LaunchedEffect(s.matchOver) {
        if (!s.matchOver) {
            inkWave.snapTo(0f)
            return@LaunchedEffect
        }
        haptics(HapticSignal.CELEBRATION)
        if (enhanced) {
            sounds.playShuffle()
            delay(70)
            sounds.playTap()
            delay(70)
            sounds.playDraw()
            inkWave.snapTo(-0.2f)
            inkWave.animateTo(1.2f, tween(1000))
        } else {
            inkWave.snapTo(1.2f)
        }
    }

    // The entry a just-completed correct submit belongs to, if any — used only to look up
    // each of its cells' index along the entry so the letter-reveal animation can stagger in
    // that same direction; a single hint reveal (justSolvedCells empty) finds no match here
    // and animates in immediately with no stagger.
    val justSolvedEntry = remember(justSolvedCells, s.entries) {
        if (justSolvedCells.isEmpty()) null else s.entries.firstOrNull { it.cells.toSet() == justSolvedCells }
    }
    val clueList = remember(s.entries) {
        s.entries.sortedWith(compareBy({ it.number }, { it.direction.ordinal }))
    }
    val selectedEntry = s.entries.firstOrNull { it.id == s.selectedEntryId }

    // Dialog visibility is tracked locally rather than driven straight off s.selectedEntryId:
    // submitAnswer() clears selectedEntryId the instant a correct guess lands, but the dialog
    // itself needs to stay open a beat longer to show the "Correct!" checkmark pulse (see
    // AnswerDialog's justCorrect state) before actually closing.
    var openEntryId by remember { mutableStateOf<String?>(null) }

    val canHint = game.canHint
    val hintUnavailableReason = when {
        s.matchOver -> "the puzzle is complete"
        hintsRemaining <= 0 -> "no hints left"
        else -> "nothing left to reveal"
    }

    // Back / abort-confirm / How to Play / Hint live in the shared GameChrome. Leaving mid-puzzle
    // discards ONLY the unfinished puzzle: if earlier puzzles this session were solved, leaving
    // goes through leaveSession() so they still count; with none solved it is a pure abort that
    // parks the game for Resume. A solved puzzle leaves through leaveSession(), which scores
    // the session.
    GameChrome(
        helpTitle = "How to Play Crossword",
        helpText = "Tap a square to pick a word running through it (tap the same square again to " +
            "switch between across and down), or tap a clue in the list. Press Answer, type the " +
            "whole word and submit. A correct word fills in its squares, including letters it " +
            "shares with crossing words.\n\n" +
            "Hint reveals one letter of the selected word (the selected square if it is still " +
            "blank) and is marked with a corner triangle. Each puzzle has a limited number of " +
            "hints: 5 on Easy, 3 on Medium, 2 on Hard.\n\n" +
            "Solve every clue to finish the puzzle, then start a new one or return to the menu.",
        matchInProgress = !s.matchOver,
        onLeave = game::leaveSession,
        onAbort = { if (puzzlesSolved > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = puzzlesSolved == 0,
        onHint = { game.hint() },
        buttonFill = palette.paper,
        buttonContent = palette.ink,
        leaveTitle = "Leave this puzzle?",
        leaveBody = if (puzzlesSolved > 0) {
            "This puzzle is still in progress and won't count, but the puzzles you've already solved stay on your record."
        } else {
            "This puzzle is still in progress. Leaving now won't count it as a win or a loss."
        }
    ) {
        AdaptiveTwoPane(
            foldState = LocalFoldState.current,
            modifier = Modifier.fillMaxSize(),
            primary = {
                CompositionLocalProvider(LocalContentColor provides palette.ink) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .crosswordPaper(paper = palette.paper, rule = palette.ink)
                            .padding(12.dp)
                    ) {
                        // End padding reserves room for the corner menu button, which sits at the
                        // screen's top-end and would otherwise cover this header's right edge.
                        Column(modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset)) {
                            Text(
                                "Puzzles solved: $puzzlesSolved · ${
                                    game.difficulty.name.lowercase().replaceFirstChar { it.uppercase() }
                                }",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.ink
                            )
                            Text(
                                "${s.solvedEntryIds.size}/${s.entries.size} solved",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.ink
                            )
                            Text(
                                "Hints left: $hintsRemaining",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkMuted
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        if (s.matchOver) {
                            CrosswordResultPanel(
                                hintsUsed = hintedCells.size,
                                reducedMotion = reducedMotion,
                                palette = palette,
                                onNewPuzzle = game::playAgain,
                                onLeave = game::leaveSession
                            )
                        } else {
                            CrosswordClueBanner(
                                entry = selectedEntry,
                                canHint = canHint,
                                hintsRemaining = hintsRemaining,
                                hintUnavailableReason = hintUnavailableReason,
                                palette = palette,
                                onAnswer = { selectedEntry?.let { openEntryId = it.id } },
                                onHint = { game.hint() }
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        CrosswordBoard(
                            s = s,
                            selectedCell = selectedCell,
                            hintedCells = hintedCells,
                            justSolvedCells = justSolvedCells,
                            justHintedCells = justHintedCells,
                            justSolvedEntry = justSolvedEntry,
                            palette = palette,
                            reducedMotion = reducedMotion,
                            sheenEnabled = sheenEnabled,
                            colorblind = colorblind,
                            waveProvider = { inkWave.value },
                            onCellTap = { pos ->
                                game.selectCell(pos)
                                haptics(HapticSignal.LIGHT_TICK)
                            },
                            modifier = Modifier.fillMaxWidth().weight(1.5f, fill = false)
                        )

                        Spacer(Modifier.height(8.dp))
                        Text("Clues", style = MaterialTheme.typography.titleSmall, color = palette.ink)

                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(clueList, key = { it.id }) { entry ->
                                CrosswordClueRow(
                                    entry = entry,
                                    solved = entry.id in s.solvedEntryIds,
                                    selected = entry.id == s.selectedEntryId,
                                    palette = palette,
                                    reducedMotion = reducedMotion,
                                    colorblind = colorblind,
                                    onClick = {
                                        game.selectEntry(entry.id)
                                        openEntryId = entry.id
                                    }
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    val dialogEntry = s.entries.firstOrNull { it.id == openEntryId }
    if (dialogEntry != null) {
        // Pattern of what's revealed so far for this entry, so the hint button
        // inside the dialog has something to act on and the player can see
        // its effect without leaving the dialog.
        val pattern = dialogEntry.cells.joinToString(" ") { pos ->
            val gridCell = s.grid[pos.row][pos.col]
            if (gridCell.revealed) gridCell.letter.toString() else "_"
        }
        AnswerDialog(
            title = "${dialogEntry.number} ${crosswordDirectionWord(dialogEntry.direction).replaceFirstChar { it.uppercase() }}",
            clue = dialogEntry.clue,
            length = dialogEntry.word.length,
            pattern = pattern,
            hintsRemaining = hintsRemaining,
            reducedMotion = reducedMotion,
            palette = palette,
            onHint = { game.revealNextLetter() },
            onSubmit = { answer -> game.submitAnswer(dialogEntry.id, answer) },
            onDismiss = { openEntryId = null },
            onCorrectSettled = { openEntryId = null }
        )
    }
}

// ---------------------------------------------------------------------------
// Palette: the paper/ink identity in light and dark. Every text and cell colour on the sheet comes
// from here so nothing depends on the ambient theme's content colour (the old screen drew
// theme-coloured text on fixed light surfaces, unreadable in dark themes).
// ---------------------------------------------------------------------------
private data class CrosswordPalette(
    val paper: Color,
    val ink: Color,
    val inkMuted: Color,
    val cell: Color,
    val cellRim: Color,
    val cursor: Color,
    val solvedFlash: Color,
    val hintFlash: Color,
    val wave: Color,
    val good: Color,
    val hintMark: Color
)

private fun crosswordPalette(isDark: Boolean): CrosswordPalette = if (!isDark) {
    CrosswordPalette(
        paper = Color(0xFFF7F3EA),
        ink = Color(0xFF2B2620),
        inkMuted = Color(0xFF5A5247),
        cell = Color(0xFFFFFDF8),
        cellRim = Color(0xFF8A8070),
        cursor = Color(0xFFFFE9A8),
        solvedFlash = Color(0xFFA5D6A7),
        hintFlash = Color(0xFFFFCC80),
        wave = Color(0xFFD7CCC8),
        good = Color(0xFF1B5E20),
        hintMark = Color(0xFFB26A00)
    )
} else {
    CrosswordPalette(
        paper = Color(0xFF1E1B16),
        ink = Color(0xFFEDE6D8),
        inkMuted = Color(0xFFB9B0A0),
        cell = Color(0xFF2B2720),
        cellRim = Color(0xFF7C7466),
        cursor = Color(0xFF5A4A1C),
        solvedFlash = Color(0xFF2E5E32),
        hintFlash = Color(0xFF7A4F10),
        wave = Color(0xFF4A423A),
        good = Color(0xFF81C784),
        hintMark = Color(0xFFFFB74D)
    )
}

private fun crosswordDirectionWord(direction: Direction): String =
    if (direction == Direction.ACROSS) "across" else "down"

/** Inclusive row/column range of the lettered cells; the board draws only this box. */
private data class CrosswordBounds(val minRow: Int, val maxRow: Int, val minCol: Int, val maxCol: Int) {
    val rows: Int get() = maxRow - minRow + 1
    val cols: Int get() = maxCol - minCol + 1
}

private fun crosswordBounds(grid: List<List<CrosswordCell>>): CrosswordBounds {
    var minRow = Int.MAX_VALUE
    var maxRow = -1
    var minCol = Int.MAX_VALUE
    var maxCol = -1
    grid.forEachIndexed { r, line ->
        line.forEachIndexed { c, cell ->
            if (cell.letter != null) {
                if (r < minRow) minRow = r
                if (r > maxRow) maxRow = r
                if (c < minCol) minCol = c
                if (c > maxCol) maxCol = c
            }
        }
    }
    // No lettered cell at all (generation produced nothing): show the whole grid rather than a 0x0 box.
    if (maxRow < 0) return CrosswordBounds(0, (grid.size - 1).coerceAtLeast(0), 0, ((grid.firstOrNull()?.size ?: 1) - 1).coerceAtLeast(0))
    return CrosswordBounds(minRow, maxRow, minCol, maxCol)
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------
@Composable
private fun CrosswordBoard(
    s: CrosswordState,
    selectedCell: CrosswordPos?,
    hintedCells: Set<CrosswordPos>,
    justSolvedCells: Set<CrosswordPos>,
    justHintedCells: Set<CrosswordPos>,
    justSolvedEntry: CrosswordEntry?,
    palette: CrosswordPalette,
    reducedMotion: Boolean,
    sheenEnabled: Boolean,
    colorblind: Boolean,
    waveProvider: () -> Float,
    onCellTap: (CrosswordPos) -> Unit,
    modifier: Modifier = Modifier
) {
    val bounds = remember(s.grid) { crosswordBounds(s.grid) }
    val entriesAt = remember(s.entries) {
        val map = HashMap<CrosswordPos, MutableList<CrosswordEntry>>()
        s.entries.forEach { e -> e.cells.forEach { map.getOrPut(it) { mutableListOf() }.add(e) } }
        map
    }
    val selectedEntryCells = remember(s.selectedEntryId, s.entries) {
        s.entries.firstOrNull { it.id == s.selectedEntryId }?.cells?.toSet() ?: emptySet()
    }
    val maxCornerDist = if (s.grid.size > 1) kotlin.math.sqrt(2f) * (s.grid.size - 1) else 1f

    BoxWithConstraints(
        modifier = modifier.specularSweep(enabled = sheenEnabled, tint = Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        // fitBoard never returns a footprint larger than this slot's measured space. When the
        // natural cell falls under the 28dp floor the board keeps 28dp and pans instead.
        val fit = remember(maxWidth, maxHeight, bounds) {
            fitBoard(
                availableWidthPx = maxWidth.value,
                availableHeightPx = maxHeight.value,
                columns = bounds.cols,
                rows = bounds.rows,
                minCellPx = CROSSWORD_MIN_CELL_DP,
                maxCellPx = CROSSWORD_MAX_CELL_DP
            )
        }
        val cellDp = if (fit.meetsMinimum) fit.cellPx else CROSSWORD_MIN_CELL_DP

        Box(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
        ) {
            Column {
                for (row in bounds.minRow..bounds.maxRow) {
                    Row {
                        for (col in bounds.minCol..bounds.maxCol) {
                            val pos = CrosswordPos(row, col)
                            val cell = s.grid[row][col]
                            val here = entriesAt[pos]
                            val memberships = here?.joinToString(", ") { "${it.number} ${crosswordDirectionWord(it.direction)}" } ?: ""
                            CrosswordCellView(
                                cell = cell,
                                displayRow = row - bounds.minRow + 1,
                                displayCol = col - bounds.minCol + 1,
                                cellDp = cellDp,
                                memberships = memberships,
                                isHinted = pos in hintedCells,
                                isCursor = pos == selectedCell,
                                inSelectedEntry = pos in selectedEntryCells,
                                justSolved = pos in justSolvedCells,
                                justHinted = pos in justHintedCells,
                                staggerIndex = justSolvedEntry?.cells?.indexOf(pos) ?: -1,
                                tappable = !s.matchOver && here?.any { it.id !in s.solvedEntryIds } == true,
                                matchOver = s.matchOver,
                                cellDist = kotlin.math.sqrt((row * row + col * col).toFloat()) / maxCornerDist,
                                waveProvider = waveProvider,
                                palette = palette,
                                reducedMotion = reducedMotion,
                                colorblind = colorblind,
                                onTap = { onCellTap(pos) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrosswordCellView(
    cell: CrosswordCell,
    displayRow: Int,
    displayCol: Int,
    cellDp: Float,
    memberships: String,
    isHinted: Boolean,
    isCursor: Boolean,
    inSelectedEntry: Boolean,
    justSolved: Boolean,
    justHinted: Boolean,
    staggerIndex: Int,
    tappable: Boolean,
    matchOver: Boolean,
    cellDist: Float,
    waveProvider: () -> Float,
    palette: CrosswordPalette,
    reducedMotion: Boolean,
    colorblind: Boolean,
    onTap: () -> Unit
) {
    val letter = cell.letter
    if (letter == null) {
        // An unused square inside the cropped box: pure spacing, nothing for a screen reader.
        Box(modifier = Modifier.size(cellDp.dp).clearAndSetSemantics {})
        return
    }

    val description = remember(displayRow, displayCol, cell.revealed, cell.number, memberships, isHinted, isCursor, inSelectedEntry) {
        buildString {
            append(if (cell.revealed) "Letter $letter" else "Empty square")
            append(", row $displayRow, column $displayCol")
            if (cell.number != null) append(", clue number ${cell.number}")
            if (memberships.isNotEmpty()) append(", part of $memberships")
            if (isHinted) append(", given by a hint")
            if (isCursor) append(", selected") else if (inSelectedEntry) append(", in the selected word")
        }
    }

    val restingColor = when {
        justSolved -> palette.solvedFlash
        justHinted -> palette.hintFlash
        isCursor -> palette.cursor
        else -> palette.cell
    }
    val cellBackground by animateColorAsState(
        targetValue = restingColor,
        animationSpec = if (reducedMotion) snap() else tween(350),
        label = "crosswordCellFlash"
    )

    // Per-entry solve: each cell's letter fades+scales in, staggered along the entry direction.
    // Only a revealed-false -> true TRANSITION animates; a cell that is already revealed when it
    // first composes (resume, scrolling, a new layout) just shows.
    val letterScale = remember { Animatable(1f) }
    val letterAlpha = remember { Animatable(1f) }
    var shownRevealed by remember { mutableStateOf(cell.revealed) }
    LaunchedEffect(cell.revealed) {
        if (cell.revealed && !shownRevealed) {
            if (reducedMotion) {
                letterScale.snapTo(1f)
                letterAlpha.snapTo(1f)
            } else {
                letterScale.snapTo(0.3f)
                letterAlpha.snapTo(0f)
                if (staggerIndex > 0) delay(staggerIndex * 45L)
                launch { letterAlpha.animateTo(1f, tween(220)) }
                letterScale.animateTo(1f, tween(220))
            }
        } else if (!cell.revealed) {
            letterScale.snapTo(1f)
            letterAlpha.snapTo(1f)
        }
        shownRevealed = cell.revealed
    }

    val rimWidth = when {
        isCursor -> 3.dp
        inSelectedEntry -> 2.dp
        else -> 1.dp
    }
    val rimColor = if (isCursor || inSelectedEntry) palette.ink else palette.cellRim
    val markFraction = if (colorblind) 0.42f else 0.3f

    Box(
        modifier = Modifier
            .size(cellDp.dp)
            .background(cellBackground)
            .drawBehind {
                // Finale wave: read in the DRAW phase so the 1s sweep repaints cells without
                // recomposing the grid every frame.
                if (matchOver) {
                    val w = waveProvider()
                    if (w >= cellDist) drawRect(palette.wave)
                    val band = (1f - kotlin.math.abs(cellDist - w) / 0.16f).coerceIn(0f, 1f)
                    if (band > 0f) drawRect(Color.White.copy(alpha = band * 0.45f))
                }
                if (isHinted) {
                    val t = size.minDimension * markFraction
                    val mark = Path().apply {
                        moveTo(size.width, size.height - t)
                        lineTo(size.width, size.height)
                        lineTo(size.width - t, size.height)
                        close()
                    }
                    drawPath(mark, palette.hintMark)
                }
            }
            .border(rimWidth, rimColor)
            .clickable(enabled = tappable, onClickLabel = "Select this word", role = Role.Button, onClick = onTap)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        val cramped = cellDp < CROSSWORD_COMFORTABLE_CELL_DP
        if (cell.number != null && !(cell.revealed && cramped)) {
            Text(
                cell.number.toString(),
                fontSize = (cellDp * 0.28f).coerceAtLeast(8f).sp,
                lineHeight = (cellDp * 0.3f).coerceAtLeast(9f).sp,
                color = palette.ink,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp).clearAndSetSemantics {}
            )
        }
        if (cell.revealed) {
            Text(
                letter.toString(),
                fontSize = (cellDp * 0.55f).sp,
                fontWeight = if (isHinted) FontWeight.Normal else FontWeight.SemiBold,
                fontStyle = if (isHinted) FontStyle.Italic else FontStyle.Normal,
                color = palette.ink,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .graphicsLayer {
                        scaleX = letterScale.value
                        scaleY = letterScale.value
                        alpha = letterAlpha.value
                    }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Banner, clue list, result panel
// ---------------------------------------------------------------------------
@Composable
private fun CrosswordClueBanner(
    entry: CrosswordEntry?,
    canHint: Boolean,
    hintsRemaining: Int,
    hintUnavailableReason: String,
    palette: CrosswordPalette,
    onAnswer: () -> Unit,
    onHint: () -> Unit
) {
    val colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, palette.cellRim, RoundedCornerShape(12.dp))
            .background(palette.cell)
            .padding(10.dp)
    ) {
        Text(
            if (entry == null) {
                "Tap a square or a clue to choose a word."
            } else {
                "${entry.number} ${crosswordDirectionWord(entry.direction).replaceFirstChar { it.uppercase() }} · ${entry.word.length} letters\n${entry.clue}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = palette.ink,
            maxLines = 3,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAnswer,
                enabled = entry != null,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            ) { Text("Answer") }
            OutlinedButton(
                onClick = onHint,
                enabled = canHint,
                colors = colors,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics {
                        contentDescription = if (canHint) {
                            "Hint, reveals one letter, $hintsRemaining left"
                        } else {
                            "Hint unavailable, $hintUnavailableReason"
                        }
                    }
            ) { Text("Hint ($hintsRemaining)") }
        }
    }
}

@Composable
private fun CrosswordClueRow(
    entry: CrosswordEntry,
    solved: Boolean,
    selected: Boolean,
    palette: CrosswordPalette,
    reducedMotion: Boolean,
    colorblind: Boolean,
    onClick: () -> Unit
) {
    val strikeProgress by animateFloatAsState(
        targetValue = if (solved) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(340),
        label = "crosswordClueStrike"
    )
    val textColor by animateColorAsState(
        targetValue = if (solved) palette.good else palette.ink,
        animationSpec = if (reducedMotion) snap() else tween(340),
        label = "crosswordClueColor"
    )
    val spoken = "${entry.number} ${crosswordDirectionWord(entry.direction)}: ${entry.clue}, ${entry.word.length} letters" +
        if (solved) ", solved" else ""
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Explicit 48dp touch-target floor: a raw clickable Row wrapping a single line of
            // text otherwise lands well under it.
            .heightIn(min = 48.dp)
            .background(if (selected) palette.cursor else Color.Transparent)
            .clickable(enabled = !solved, onClickLabel = "Answer this clue", role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = spoken }
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .drawWithContent {
                drawContent()
                if (strikeProgress > 0f) {
                    val y = size.height / 2f
                    drawLine(
                        color = palette.good,
                        start = Offset(0f, y),
                        end = Offset(size.width * strikeProgress, y),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round
                    )
                }
            }
    ) {
        Text(
            (if (solved && colorblind) "✓ " else "") +
                "${entry.number}${if (entry.direction == Direction.ACROSS) "A" else "D"}. ${entry.clue}",
            color = textColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun CrosswordResultPanel(
    hintsUsed: Int,
    reducedMotion: Boolean,
    palette: CrosswordPalette,
    onNewPuzzle: () -> Unit,
    onLeave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.cell, contentColor = palette.ink),
        border = BorderStroke(1.dp, palette.cellRim)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CascadingSolvedText(reducedMotion = reducedMotion, color = palette.good)
            if (hintsUsed > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Solved with $hintsUsed hint${if (hintsUsed == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkMuted
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onNewPuzzle,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text("New Puzzle") }
                OutlinedButton(
                    onClick = onLeave,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Back to Menu") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Answer dialog
// ---------------------------------------------------------------------------
@Composable
private fun AnswerDialog(
    title: String,
    clue: String,
    length: Int,
    pattern: String,
    hintsRemaining: Int,
    reducedMotion: Boolean,
    palette: CrosswordPalette,
    onHint: () -> Unit,
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit,
    onCorrectSettled: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    // Set when a submitted guess didn't match, so the field can show an
    // inline error instead of leaving the dialog looking unresponsive.
    // Cleared as soon as the user edits the field again.
    var isError by remember { mutableStateOf(false) }
    // Increments on every wrong submit (even a repeat of the same wrong text) so the
    // shake/haptic below reliably retriggers, rather than being keyed on isError's
    // boolean value which wouldn't change on a second identical wrong guess.
    var wrongAttempt by remember { mutableStateOf(0) }
    var justCorrect by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val scope = rememberCoroutineScope()
    val shakeOffset = remember { Animatable(0f) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // Open with the keyboard up. The field may not be attached yet on a very fast dismiss.
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(wrongAttempt) {
        if (wrongAttempt == 0) return@LaunchedEffect
        haptics(HapticSignal.FAILURE)
        playSfx(SfxKind.INVALID_BUZZ)
        if (!reducedMotion) {
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(-10f, tween(45))
            shakeOffset.animateTo(10f, tween(65))
            shakeOffset.animateTo(-7f, tween(65))
            shakeOffset.animateTo(7f, tween(55))
            shakeOffset.animateTo(0f, tween(45))
        }
    }

    val submit: () -> Unit = {
        if (!justCorrect) {
            val correct = onSubmit(text)
            if (correct) {
                justCorrect = true
                scope.launch {
                    delay(if (reducedMotion) 80L else 380L)
                    onCorrectSettled()
                }
            } else {
                wrongAttempt++
                isError = true
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = palette.cell,
            contentColor = palette.ink,
            border = BorderStroke(1.dp, palette.cellRim)
        ) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = palette.ink)
                Spacer(Modifier.height(4.dp))
                Text(clue, color = palette.ink)
                Spacer(Modifier.height(4.dp))
                Text("$length letters", style = MaterialTheme.typography.labelSmall, color = palette.inkMuted)
                Spacer(Modifier.height(4.dp))
                Text(
                    pattern,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                    modifier = Modifier.semantics {
                        contentDescription = "Letters so far: " + pattern.replace("_", "blank")
                    }
                )
                Spacer(Modifier.height(12.dp))

                if (justCorrect) {
                    CorrectBeat(reducedMotion = reducedMotion, color = palette.good)
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            if (it.length <= length) text = it.uppercase()
                            isError = false
                        },
                        singleLine = true,
                        isError = isError,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.ink),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .offset(x = shakeOffset.value.dp)
                            .focusRequester(focusRequester),
                        supportingText = if (isError) {
                            { Text("Not quite — try again") }
                        } else null
                    )
                    Spacer(Modifier.height(8.dp))
                    // Reveals one more letter of THIS entry — capped by the shared
                    // per-puzzle hint budget (see CrosswordGame.revealNextLetter) and
                    // a no-op once every letter here is already shown.
                    TextButton(
                        onClick = onHint,
                        enabled = hintsRemaining > 0 && '_' in pattern,
                        modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) { Text("Hint ($hintsRemaining left)") }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                        ) { Text("Cancel") }
                        Button(
                            onClick = submit,
                            modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                        ) { Text("Submit") }
                    }
                }
            }
        }
    }
}

/** The dialog's "hold on the win" beat — a scale-pulse checkmark plus a settled "Correct!" label, shown for ~350-400ms before the dialog actually dismisses (see the submit action above). */
@Composable
private fun CorrectBeat(reducedMotion: Boolean, color: Color) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.4f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "crosswordCorrectScale"
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
        ) {
            drawCircle(color = Color(0xFF43A047), radius = size.minDimension / 2f)
            val w = size.width
            val h = size.height
            val checkPath = Path().apply {
                moveTo(w * 0.28f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.68f)
                lineTo(w * 0.74f, h * 0.32f)
            }
            drawPath(
                checkPath,
                color = Color.White,
                style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Correct!", style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** Full-grid-solve headline: each character cascades in on its own stagger instead of the shared suite-wide banner popping in as one block. */
@Composable
private fun CascadingSolvedText(reducedMotion: Boolean, color: Color) {
    val text = "Puzzle solved!"
    Row(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(if (reducedMotion) 0L else i * 40L)
                visible = true
            }
            AnimatedVisibility(
                visible = visible,
                enter = if (reducedMotion) {
                    fadeIn(snap())
                } else {
                    fadeIn(tween(180)) + scaleIn(initialScale = 0.4f, animationSpec = tween(180))
                }
            ) {
                Text(
                    ch.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
    }
}

/**
 * Newsprint paper ambient identity — a sheet with faint speckling and column-rule lines, distinct
 * from Hangman's chalkboard and Word Search's graph paper. [rule] is the ink colour the faint rules
 * and flecks are drawn in (a light sheet gets dark rules, a dark sheet light ones).
 * [drawWithCache] rebuilds the speckle positions only when the drawing size actually changes.
 */
private fun Modifier.crosswordPaper(paper: Color, rule: Color): Modifier = this.drawWithCache {
    val rng = Random(2024)
    val flecks = List(220) { Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height) }
    onDrawBehind {
        drawRect(paper)
        var y = 36f
        while (y < size.height) {
            drawLine(rule.copy(alpha = 0.08f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += 44f
        }
        flecks.forEach { pos ->
            drawCircle(color = rule.copy(alpha = 0.06f), radius = 0.9f, center = pos)
        }
    }
}
