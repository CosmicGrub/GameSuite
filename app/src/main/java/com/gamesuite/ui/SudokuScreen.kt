package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.sudoku.SudokuCell
import com.gamesuite.games.sudoku.SudokuGame
import com.gamesuite.games.sudoku.SudokuStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders SudokuGame's state reactively — same overall shape as
 * MinesweeperScreen (difficulty selector, live status row, finished-board
 * panel), plus the two things unique to Sudoku input: a select-then-enter
 * flow (tap a cell, then tap a digit on the number pad — the same "tap
 * source, then tap destination" two-step this app already uses for
 * Solitaire, since it has no drag input model) and a notes-mode toggle
 * (same "explicit mode toggle" idiom as Minesweeper's flag mode).
 *
 * VISUAL IDENTITY: shares its warm background/accent tokens with
 * MinesweeperScreen (see [sudokuPalette]) for one consistent "new games"
 * identity across this batch, per the project's "new games only" visual
 * refresh decision — extended here with Sudoku-specific tokens (given vs.
 * entered vs. wrong text, peer/same-value highlight) that Minesweeper had
 * no need for. Never reads `MaterialTheme.colorScheme` for gameplay colors,
 * same standing rule as every other game's board.
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
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.SUDOKU, enabled = musicEnabled)
    val statsStore = remember { SudokuStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = sudokuPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

    var notesMode by remember { mutableStateOf(false) }

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
    var reportedNewBest by remember(s.solution) { mutableStateOf(false) }

    // Live "Time: M:SS" display -- same idiom as MinesweeperScreen's own
    // live-timer LaunchedEffect (the engine only exposes raw
    // elapsedRealtime() readings; this loop drives the ticking).
    var liveElapsedMillis by remember(s.solution) { mutableStateOf(0L) }
    LaunchedEffect(game.timerStartElapsedRealtime.value, s.isOver) {
        val start = game.timerStartElapsedRealtime.value
        if (start == null) {
            liveElapsedMillis = 0L
            return@LaunchedEffect
        }
        while (!s.isOver) {
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

    // A digit is "used up" once it's correctly placed in all 9 of its
    // occurrences (every valid, uniquely-solvable grid has each digit
    // appear exactly 9 times in the true solution) -- disabling it on the
    // number pad at that point is a standard, purely-cosmetic Sudoku-app
    // convenience; it never blocks re-entering that digit elsewhere.
    val correctCounts = remember(s.cells, s.solution) {
        (1..9).associateWith { d -> s.cells.indices.count { s.cells[it].value == d && d == s.solution[it] } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DifficultyTabs(
            current = game.difficulty,
            palette = palette,
            onSelect = { tier ->
                // Switching difficulty always deals a fresh puzzle immediately
                // (same as tapping the ↻ button below) -- no reason to block
                // this mid-puzzle or once one is already solved.
                if (tier != game.difficulty) {
                    game.difficulty = tier
                    game.startMatch()
                    notesMode = false
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        StatusRow(
            mistakes = s.mistakes,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            onNewPuzzle = { game.startMatch(); notesMode = false },
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            // Divided by 9.3 rather than 9 to leave a little slack for the
            // small inter-box gaps drawn below -- a few dp of unused space
            // at the board's edge is preferable to overflowing it.
            val cellSize = remember(maxWidth, maxHeight) {
                minOf(maxWidth / 9.3f, maxHeight / 9.3f, 44.dp).coerceAtLeast(24.dp)
            }
            val selected = s.selectedIndex
            val selectedRow = selected?.let { it / 9 }
            val selectedCol = selected?.let { it % 9 }
            val selectedBox = selected?.let { boxOf(it / 9, it % 9) }
            val selectedValue = selected?.let { s.cells[it].value }

            Column {
                for (boxRow in 0 until 3) {
                    Row {
                        for (boxCol in 0 until 3) {
                            Column {
                                for (r in 0 until 3) {
                                    Row {
                                        for (c in 0 until 3) {
                                            val row = boxRow * 3 + r
                                            val col = boxCol * 3 + c
                                            val index = row * 9 + col
                                            val cell = s.cells[index]
                                            SudokuCellView(
                                                cell = cell,
                                                size = cellSize,
                                                isSelected = index == selected,
                                                isPeerHighlighted = selected != null && index != selected &&
                                                    (row == selectedRow || col == selectedCol || boxOf(row, col) == selectedBox),
                                                isSameValueHighlighted = selectedValue != null && index != selected && cell.value == selectedValue,
                                                isWrong = cell.value != null && !cell.isGiven && cell.value != s.solution[index],
                                                palette = palette,
                                                onTap = {
                                                    game.selectCell(index)
                                                    haptics(HapticSignal.LIGHT_TICK)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            if (boxCol < 2) Spacer(Modifier.width(2.dp))
                        }
                    }
                    if (boxRow < 2) Spacer(Modifier.height(2.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        NumberPad(
            notesMode = notesMode,
            remainingCounts = correctCounts.mapValues { (_, correct) -> 9 - correct },
            onDigit = { d ->
                if (!s.isOver && s.selectedIndex != null) {
                    if (notesMode) {
                        game.toggleNote(d)
                    } else {
                        game.setValue(d)
                        sounds.playTap()
                    }
                    haptics(HapticSignal.NORMAL_ACTION)
                }
            },
            onErase = {
                if (!s.isOver) {
                    game.clearValue()
                    haptics(HapticSignal.LIGHT_TICK)
                }
            },
            onToggleNotesMode = { notesMode = !notesMode },
            palette = palette
        )

        if (s.isOver) {
            Spacer(Modifier.height(16.dp))
            FinishedPanel(
                mistakes = s.mistakes,
                bestTimeMillis = bestTimeMillis,
                isNewBest = reportedNewBest,
                onNewPuzzle = { game.startMatch(); notesMode = false },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

private fun boxOf(row: Int, col: Int) = (row / 3) * 3 + (col / 3)

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- shares its base tokens with
// MinesweeperPalette (see this file's class KDoc for why this doesn't read
// MaterialTheme.colorScheme), extended with Sudoku-specific tokens.
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
    val danger: Color,
    val textPrimary: Color,
    val textOnAccent: Color
)

@Composable
private fun sudokuPalette(isDark: Boolean): SudokuPalette = if (!isDark) {
    SudokuPalette(
        background = Color(0xFFFBF1E6),
        cellBackground = Color(0xFFFFFBF5),
        selectedCell = Color(0xFFE08D4B),
        peerHighlight = Color(0xFFF3E4D2),
        sameValueHighlight = Color(0xFFF0CFA0),
        accent = Color(0xFFE08D4B),
        givenText = Color(0xFF3A2E22),
        enteredText = Color(0xFF7A5A3A),
        noteText = Color(0xFF9A8A76),
        danger = Color(0xFFD9573F),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5)
    )
} else {
    SudokuPalette(
        background = Color(0xFF1C1712),
        cellBackground = Color(0xFF15110D),
        selectedCell = Color(0xFFE8985B),
        peerHighlight = Color(0xFF2B241D),
        sameValueHighlight = Color(0xFF4A3A26),
        accent = Color(0xFFE8985B),
        givenText = Color(0xFFF3E9DB),
        enteredText = Color(0xFFCBB79C),
        noteText = Color(0xFF8A7C6C),
        danger = Color(0xFFE0705A),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712)
    )
}

@Composable
private fun DifficultyTabs(current: CpuDifficulty, palette: SudokuPalette, onSelect: (CpuDifficulty) -> Unit) {
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
                    .background(if (selected) palette.accent else palette.peerHighlight)
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

@Composable
private fun StatusRow(
    mistakes: Int,
    elapsedMillis: Long,
    onNewPuzzle: () -> Unit,
    palette: SudokuPalette
) {
    val minutes = (elapsedMillis / 1000) / 60
    val seconds = (elapsedMillis / 1000) % 60
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Mistakes: $mistakes", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            "%d:%02d".format(minutes, seconds),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold
        )
        OutlinedButton(onClick = onNewPuzzle) { Text("↻") }
    }
}

@Composable
private fun SudokuCellView(
    cell: SudokuCell,
    size: Dp,
    isSelected: Boolean,
    isPeerHighlighted: Boolean,
    isSameValueHighlighted: Boolean,
    isWrong: Boolean,
    palette: SudokuPalette,
    onTap: () -> Unit
) {
    val bg = when {
        isSelected -> palette.selectedCell
        isSameValueHighlighted -> palette.sameValueHighlight
        isPeerHighlighted -> palette.peerHighlight
        else -> palette.cellBackground
    }
    val textColor = when {
        isSelected -> palette.textOnAccent
        isWrong -> palette.danger
        cell.isGiven -> palette.givenText
        else -> palette.enteredText
    }
    Box(
        modifier = Modifier
            .size(size)
            .padding(0.5.dp)
            .background(bg)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        val value = cell.value
        if (value != null) {
            Text(
                value.toString(),
                color = textColor,
                fontWeight = if (cell.isGiven) FontWeight.Bold else FontWeight.Normal,
                fontSize = (size.value * 0.48f).sp
            )
        } else if (cell.notes.isNotEmpty()) {
            NotesGrid(notes = cell.notes, cellSize = size, textColor = if (isSelected) palette.textOnAccent else palette.noteText)
        }
    }
}

@Composable
private fun NotesGrid(notes: Set<Int>, cellSize: Dp, textColor: Color) {
    Column(modifier = Modifier.fillMaxSize()) {
        for (row in 0 until 3) {
            Row(modifier = Modifier.weight(1f)) {
                for (col in 0 until 3) {
                    val digit = row * 3 + col + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (digit in notes) {
                            Text(digit.toString(), color = textColor, fontSize = (cellSize.value * 0.22f).sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberPad(
    notesMode: Boolean,
    remainingCounts: Map<Int, Int>,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onToggleNotesMode: () -> Unit,
    palette: SudokuPalette
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 1..9) {
                val usedUp = (remainingCounts[d] ?: 1) <= 0
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.peerHighlight)
                        .then(if (!usedUp) Modifier.clickable { onDigit(d) } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        d.toString(),
                        color = if (usedUp) palette.textPrimary.copy(alpha = 0.3f) else palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onErase) { Text("Erase") }
            OutlinedButton(onClick = onToggleNotesMode) {
                Text(if (notesMode) "✏️ Notes" else "Notes")
            }
        }
    }
}

@Composable
private fun FinishedPanel(
    mistakes: Int,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: SudokuPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Puzzle solved!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Mistakes: $mistakes", style = MaterialTheme.typography.bodyMedium)
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
