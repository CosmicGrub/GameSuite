package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.nonogram.NonogramCellState
import com.gamesuite.games.nonogram.NonogramGame
import com.gamesuite.games.nonogram.NonogramState
import com.gamesuite.games.nonogram.NonogramStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders NonogramGame's state reactively — same overall shape as
 * MinesweeperScreen/SudokuScreen (difficulty selector, live status row, a
 * finished-board panel), adapted for this genre's own signature UI: row clue
 * numbers to the LEFT of the grid, column clue numbers ABOVE it — the
 * classic Nonogram/Picross layout, since the clues themselves (not a
 * separately-displayed hint) are the entire puzzle.
 *
 * Tapping a cell cycles UNDETERMINED -> FILLED -> MARKED_EMPTY ->
 * UNDETERMINED (see [NonogramGame.tapCell]'s KDoc); a FILLED cell that
 * doesn't match the solution renders in [NonogramPalette.danger] immediately
 * — this app's established "full information, no hidden state" puzzle
 * culture (Sudoku flags a wrong entry immediately, Edge Match live-
 * highlights matches), not a genre convention this screen invents on its
 * own. [NonogramState.solution] is available directly on state precisely so
 * this immediate feedback doesn't need any separate solver/hint logic.
 *
 * Music: [MusicProfiles.PUZZLE_FOCUS] directly, same as EdgeMatchScreen's
 * own choice — no dedicated `MusicProfiles.NONOGRAM` alias needed for a
 * genuinely quiet solo logic puzzle (unlike Breakout's real-time-arcade
 * exception to that pattern).
 *
 * VISUAL IDENTITY: chrome shares this batch's warm tokens (see
 * [nonogramPalette]); no bespoke play-element colors beyond the standard
 * filled/marked/mistake states — this puzzle's whole identity is numbers and
 * cells, not a genre with its own visual signature to defend the way Color
 * Flood's cell colors or Connect Four's board frame do.
 *
 * SCOPE CUT: no thicker every-5-cells block-separator lines (a common
 * Nonogram readability aid on larger grids, akin to Sudoku's 3x3 box
 * borders) — left for a later pass rather than risking a layout bug under
 * this session's own time budget; the row/column clue numbers alone are
 * enough to play correctly today, just a little more effort to eyeball on
 * HARD's 15x15 grid.
 *
 * LIVE CLUE STRIKETHROUGH (docs/NONOGRAM_DESIGN.md's own **Feedback** section): a row's or
 * column's clue numbers strike through once that line's CURRENT fill pattern (FILLED cells only —
 * MARKED_EMPTY doesn't count either way) already matches its clue — [nonogramCluesOf] is the
 * exact same run-length derivation [NonogramGame] itself uses to build the clues in the first
 * place, just re-run against the player's live fill state instead of the solution, matching the
 * design doc's own "recomputed live via the same [logic], not a separate 'is this line correct'
 * check" call. A duplicated small pure function rather than a new public method on [NonogramGame]
 * — [NonogramState] already exposes everything this needs ([NonogramState.cells]/`rowClues`/
 * `colClues`), and this mirrors the same "a tiny derivation function gets its own independent
 * copy where it's used" idiom [NonogramGameTest]'s own `independentCluesOf` already established
 * for verification rather than reuse. This does NOT gate winning or mistakes — a satisfied-looking
 * line can still include MARKED_EMPTY noise elsewhere, or (rarely) a line whose WRONG fill pattern
 * happens to match its clue's shape by coincidence; the real win check ([NonogramState.won]) still
 * compares every cell against [NonogramState.solution] directly, unaffected by this display-only
 * signal.
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
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { NonogramStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = nonogramPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch(dailySeed)
    }

    val s = state ?: return

    val allBestTimes by statsStore.bestTimesMillis.collectAsState(initial = emptyMap())
    val bestTimeMillis = allBestTimes[game.difficulty.name]
    var reportedNewBest by remember(s.size) { mutableStateOf(false) }

    // Live "Time: M:SS" display -- same idiom as every other solo puzzle's own live-timer LaunchedEffect.
    var liveElapsedMillis by remember(s.size) { mutableStateOf(0L) }
    LaunchedEffect(game.timerStartElapsedRealtime.value, s.won) {
        val start = game.timerStartElapsedRealtime.value
        if (start == null) {
            liveElapsedMillis = 0L
            return@LaunchedEffect
        }
        while (!s.won) {
            liveElapsedMillis = SystemClock.elapsedRealtime() - start
            delay(200)
        }
    }

    LaunchedEffect(s.won) {
        if (s.won && !reportedNewBest) {
            val finalTime = game.finishedElapsedMillis.value ?: liveElapsedMillis
            reportedNewBest = statsStore.recordWin(game.difficulty, finalTime)
            haptics(HapticSignal.CELEBRATION)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DifficultyTabsNonogram(
            current = game.difficulty,
            palette = palette,
            onSelect = { tier ->
                if (tier != game.difficulty) {
                    game.difficulty = tier
                    game.startMatch()
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        NonogramStatusRow(
            mistakes = s.mistakes,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val maxColClueLines = s.colClues.maxOf { it.size.coerceAtLeast(1) }
            val rowHeaderCellUnits = 2.6f
            val colHeaderCellUnits = (maxColClueLines * 0.62f).coerceAtLeast(1.1f)
            val cellSize = remember(maxWidth, maxHeight, s.size, maxColClueLines) {
                val fromWidth = maxWidth / (s.size + rowHeaderCellUnits)
                val fromHeight = maxHeight / (s.size + colHeaderCellUnits)
                minOf(fromWidth, fromHeight, 38.dp).coerceAtLeast(14.dp)
            }
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
            ) {
                NonogramGrid(
                    state = s,
                    cellSize = cellSize,
                    rowHeaderWidth = cellSize * rowHeaderCellUnits,
                    colHeaderHeight = cellSize * colHeaderCellUnits,
                    palette = palette,
                    onTap = { index ->
                        if (!s.won) {
                            game.tapCell(index)
                            sounds.playTap()
                            haptics(HapticSignal.LIGHT_TICK)
                        }
                    }
                )
            }
        }

        if (s.won) {
            Spacer(Modifier.height(16.dp))
            FinishedPanelNonogram(
                bestTimeMillis = bestTimeMillis,
                isNewBest = reportedNewBest,
                onNewPuzzle = { game.playAgain() },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

@Composable
private fun NonogramGrid(
    state: NonogramState,
    cellSize: Dp,
    rowHeaderWidth: Dp,
    colHeaderHeight: Dp,
    palette: NonogramPalette,
    onTap: (Int) -> Unit
) {
    Column {
        Row {
            Box(Modifier.width(rowHeaderWidth).height(colHeaderHeight))
            for (c in 0 until state.size) {
                val colFilled = (0 until state.size).map { r -> state.cells[r * state.size + c] == NonogramCellState.FILLED }
                val colSatisfied = nonogramCluesOf(colFilled) == state.colClues[c]
                Box(
                    modifier = Modifier.width(cellSize).height(colHeaderHeight),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val clue = state.colClues[c]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (clue.isEmpty()) {
                            ClueDigit("0", palette, dim = true)
                        } else {
                            for (n in clue) ClueDigit(n.toString(), palette, dim = false, satisfied = colSatisfied)
                        }
                    }
                }
            }
        }
        for (r in 0 until state.size) {
            val rowFilled = (0 until state.size).map { c -> state.cells[r * state.size + c] == NonogramCellState.FILLED }
            val rowClue = state.rowClues[r]
            val rowSatisfied = nonogramCluesOf(rowFilled) == rowClue
            Row {
                Box(
                    modifier = Modifier.width(rowHeaderWidth).height(cellSize),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    ClueDigit(
                        if (rowClue.isEmpty()) "0" else rowClue.joinToString(" "),
                        palette,
                        dim = rowClue.isEmpty(),
                        satisfied = rowSatisfied,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                for (c in 0 until state.size) {
                    val index = r * state.size + c
                    NonogramCellView(
                        cellState = state.cells[index],
                        isMistake = state.cells[index] == NonogramCellState.FILLED && !state.solution[index],
                        size = cellSize,
                        palette = palette,
                        onTap = { onTap(index) }
                    )
                }
            }
        }
    }
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

@Composable
private fun ClueDigit(text: String, palette: NonogramPalette, dim: Boolean, satisfied: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        color = if (dim) palette.textPrimary.copy(alpha = 0.35f) else if (satisfied) palette.textPrimary.copy(alpha = 0.4f) else palette.textPrimary,
        textDecoration = if (satisfied) TextDecoration.LineThrough else TextDecoration.None,
        fontSize = 11.sp,
        lineHeight = 12.sp,
        modifier = modifier
    )
}

@Composable
private fun NonogramCellView(
    cellState: NonogramCellState,
    isMistake: Boolean,
    size: Dp,
    palette: NonogramPalette,
    onTap: () -> Unit
) {
    val background = when {
        cellState == NonogramCellState.FILLED && isMistake -> palette.danger
        cellState == NonogramCellState.FILLED -> palette.filledCell
        else -> palette.undeterminedCell
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(background)
            .border(width = 0.5.dp, color = palette.gridLine)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (cellState == NonogramCellState.MARKED_EMPTY) {
            Text("✕", color = palette.textPrimary.copy(alpha = 0.45f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun NonogramStatusRow(mistakes: Int, elapsedMillis: Long, palette: NonogramPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val m = (elapsedMillis / 1000) / 60
        val sec = (elapsedMillis / 1000) % 60
        Text("Time: %d:%02d".format(m, sec), color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text("Mistakes: $mistakes", color = palette.textPrimary)
    }
}

@Composable
private fun FinishedPanelNonogram(
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: NonogramPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Solved!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (isNewBest) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", color = palette.accent, fontWeight = FontWeight.Bold)
            } else if (bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                val m = (bestTimeMillis / 1000) / 60
                val sec = (bestTimeMillis / 1000) % 60
                Text("Best: %d:%02d".format(m, sec), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNewPuzzle) { Text("New Puzzle") }
                OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
            }
        }
    }
}

@Composable
private fun DifficultyTabsNonogram(current: CpuDifficulty, palette: NonogramPalette, onSelect: (CpuDifficulty) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tier in CpuDifficulty.entries) {
            val selected = tier == current
            val label = when (tier) {
                CpuDifficulty.EASY -> "Easy"
                CpuDifficulty.MEDIUM -> "Medium"
                CpuDifficulty.HARD -> "Hard"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) palette.accent else palette.chipBackground)
                    .clickable { onSelect(tier) }
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
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this batch.
// ---------------------------------------------------------------------------
private data class NonogramPalette(
    val background: Color,
    val undeterminedCell: Color,
    val filledCell: Color,
    val gridLine: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val danger: Color
)

@Composable
private fun nonogramPalette(isDark: Boolean): NonogramPalette = if (!isDark) {
    NonogramPalette(
        background = Color(0xFFFBF1E6),
        undeterminedCell = Color(0xFFFFFBF5),
        filledCell = Color(0xFF3A2E22),
        gridLine = Color(0xFFE3CBA9),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9),
        danger = Color(0xFFD9573F)
    )
} else {
    NonogramPalette(
        background = Color(0xFF1C1712),
        undeterminedCell = Color(0xFF15110D),
        filledCell = Color(0xFFF3E9DB),
        gridLine = Color(0xFF453A2E),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        danger = Color(0xFFE0705A)
    )
}
