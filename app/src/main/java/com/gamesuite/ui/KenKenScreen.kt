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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
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
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * Renders KenKenGame's state reactively — same overall shape as
 * SudokuScreen (difficulty selector, live status row, finished-board
 * panel, select-then-enter number pad with a notes-mode toggle), since
 * KenKenGame's own KDoc (SCOPING DECISION 5) deliberately mirrors Sudoku's
 * input model rather than inventing a new one. The one genuinely
 * KenKen-specific rendering job this screen has that SudokuScreen doesn't:
 * drawing CAGE BOUNDARIES (irregular polyomino outlines, not row/box grid
 * lines) and each cage's target+operator clue in its top-left-most cell —
 * see [KenKenCellView] and [neighborDiffersOrEdge].
 *
 * VISUAL IDENTITY: shares its warm background/accent/chip tokens with every
 * other new-game screen in this batch (see [kenkenPalette]) for one
 * consistent identity, extended with KenKen-specific tokens (cage border,
 * same-cage highlight) SudokuScreen had no need for. Never reads
 * `MaterialTheme.colorScheme` for gameplay colors, same standing rule as
 * every other game's board.
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
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { KenKenStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = kenkenPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

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

    // Live "Time: M:SS" display -- same idiom as every other solo puzzle
    // screen's own live-timer LaunchedEffect (the engine only exposes raw
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

    // A digit is "used up" once it's correctly placed in all `size` of its
    // occurrences (every valid Latin square has each digit appear exactly
    // `size` times -- once per row) -- disabling it on the number pad at
    // that point is a purely-cosmetic convenience, same idiom as
    // SudokuScreen's own correctCounts.
    val correctCounts = remember(s.cells, s.solution) {
        (1..s.size).associateWith { d -> s.cells.indices.count { s.cells[it].value == d && d == s.solution[it] } }
    }

    val cageById = remember(s.cages) { s.cages.associateBy { it.id } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KenKenDifficultyTabs(
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

        KenKenStatusRow(
            mistakes = s.mistakes,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            onNewPuzzle = { game.startMatch(); notesMode = false },
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            // A little slack (+0.3) for the thicker cage-border strokes drawn
            // inside each cell's own bounds -- same "leave a few dp unused
            // rather than overflow" idiom as SudokuScreen's own cellSize.
            val cellSize = remember(maxWidth, maxHeight, s.size) {
                minOf(maxWidth / (s.size + 0.3f), maxHeight / (s.size + 0.3f), 64.dp).coerceAtLeast(28.dp)
            }
            val selected = s.selectedIndex
            val selectedRow = selected?.let { it / s.size }
            val selectedCol = selected?.let { it % s.size }
            val selectedValue = selected?.let { s.cells[it].value }
            val selectedCageId = selected?.let { s.cells[it].cageId }

            Column {
                for (row in 0 until s.size) {
                    Row {
                        for (col in 0 until s.size) {
                            val index = row * s.size + col
                            val cell = s.cells[index]
                            val cage = cageById.getValue(cell.cageId)
                            KenKenCellView(
                                cell = cell,
                                size = cellSize,
                                boardSize = s.size,
                                isSelected = index == selected,
                                isPeerHighlighted = selected != null && index != selected &&
                                    (row == selectedRow || col == selectedCol),
                                isSameValueHighlighted = selectedValue != null && index != selected && cell.value == selectedValue,
                                isSameCageHighlighted = selectedCageId != null && index != selected && cell.cageId == selectedCageId,
                                isWrong = cell.value != null && cell.value != s.solution[index],
                                clueLabel = if (cage.cellIndices.min() == index) cageLabel(cage) else null,
                                topBorder = neighborDiffersOrEdge(s, row, col, -1, 0),
                                bottomBorder = neighborDiffersOrEdge(s, row, col, 1, 0),
                                startBorder = neighborDiffersOrEdge(s, row, col, 0, -1),
                                endBorder = neighborDiffersOrEdge(s, row, col, 0, 1),
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
        }

        Spacer(Modifier.height(14.dp))

        KenKenNumberPad(
            size = s.size,
            notesMode = notesMode,
            remainingCounts = correctCounts.mapValues { (_, correct) -> s.size - correct },
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
            KenKenFinishedPanel(
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

/** True iff the neighbor in direction ([dr],[dc]) from ([row],[col]) is off-board OR belongs to a different cage than this cell — either way, [KenKenCellView] draws a heavier cage-boundary stroke on that side rather than an ordinary grid line. */
private fun neighborDiffersOrEdge(s: KenKenState, row: Int, col: Int, dr: Int, dc: Int): Boolean {
    val nr = row + dr
    val nc = col + dc
    if (nr !in 0 until s.size || nc !in 0 until s.size) return true
    val thisCage = s.cells[row * s.size + col].cageId
    val neighborCage = s.cells[nr * s.size + nc].cageId
    return thisCage != neighborCage
}

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

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- shares its base tokens (background, accent,
// textPrimary, textOnAccent, chipBackground, danger) with every other new-game
// screen in this batch (see SudokuScreen's own kenkenPalette-sibling KDoc for
// why this doesn't read MaterialTheme.colorScheme), extended with
// KenKen-specific tokens (cage border, same-cage highlight) Sudoku had no
// need for.
// ---------------------------------------------------------------------------
private data class KenKenPalette(
    val background: Color,
    val cellBackground: Color,
    val selectedCell: Color,
    val peerHighlight: Color,
    val sameValueHighlight: Color,
    val cageBorder: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val noteText: Color,
    val danger: Color
)

@Composable
private fun kenkenPalette(isDark: Boolean): KenKenPalette = if (!isDark) {
    KenKenPalette(
        background = Color(0xFFFBF1E6),
        cellBackground = Color(0xFFFFFBF5),
        selectedCell = Color(0xFFE08D4B),
        peerHighlight = Color(0xFFF3E4D2),
        sameValueHighlight = Color(0xFFF0CFA0),
        cageBorder = Color(0xFF3A2E22),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9),
        noteText = Color(0xFF9A8A76),
        danger = Color(0xFFD9573F)
    )
} else {
    KenKenPalette(
        background = Color(0xFF1C1712),
        cellBackground = Color(0xFF15110D),
        selectedCell = Color(0xFFE8985B),
        peerHighlight = Color(0xFF2B241D),
        sameValueHighlight = Color(0xFF4A3A26),
        cageBorder = Color(0xFFF3E9DB),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        noteText = Color(0xFF8A7C6C),
        danger = Color(0xFFE0705A)
    )
}

@Composable
private fun KenKenDifficultyTabs(current: CpuDifficulty, palette: KenKenPalette, onSelect: (CpuDifficulty) -> Unit) {
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

@Composable
private fun KenKenStatusRow(
    mistakes: Int,
    elapsedMillis: Long,
    onNewPuzzle: () -> Unit,
    palette: KenKenPalette
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
private fun KenKenCellView(
    cell: KenKenCell,
    size: Dp,
    boardSize: Int,
    isSelected: Boolean,
    isPeerHighlighted: Boolean,
    isSameValueHighlighted: Boolean,
    isSameCageHighlighted: Boolean,
    isWrong: Boolean,
    clueLabel: String?,
    topBorder: Boolean,
    bottomBorder: Boolean,
    startBorder: Boolean,
    endBorder: Boolean,
    palette: KenKenPalette,
    onTap: () -> Unit
) {
    val bg = when {
        isSelected -> palette.selectedCell
        isSameValueHighlighted -> palette.sameValueHighlight
        isSameCageHighlighted || isPeerHighlighted -> palette.peerHighlight
        else -> palette.cellBackground
    }
    val textColor = when {
        isSelected -> palette.textOnAccent
        isWrong -> palette.danger
        else -> palette.textPrimary
    }
    val borderStrokePx = with(LocalDensity.current) { 2.5.dp.toPx() }
    Box(
        modifier = Modifier
            .size(size)
            .padding(0.5.dp)
            .background(bg)
            .drawWithContent {
                drawContent()
                // Cage boundaries are drawn as a heavier, distinct-colored
                // stroke INSIDE this cell's own bounds (rather than relying
                // on inter-cell Spacer gaps the way Sudoku's fixed 3x3 box
                // grouping does) since a cage's shape is irregular and can't
                // be expressed as a fixed row/column grouping — see this
                // file's class KDoc.
                val half = borderStrokePx / 2f
                if (topBorder) drawLine(palette.cageBorder, Offset(0f, half), Offset(this.size.width, half), borderStrokePx)
                if (bottomBorder) drawLine(palette.cageBorder, Offset(0f, this.size.height - half), Offset(this.size.width, this.size.height - half), borderStrokePx)
                if (startBorder) drawLine(palette.cageBorder, Offset(half, 0f), Offset(half, this.size.height), borderStrokePx)
                if (endBorder) drawLine(palette.cageBorder, Offset(this.size.width - half, 0f), Offset(this.size.width - half, this.size.height), borderStrokePx)
            }
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (clueLabel != null) {
            Text(
                clueLabel,
                color = if (isSelected) palette.textOnAccent else palette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.2f).sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 1.dp)
            )
        }
        val value = cell.value
        if (value != null) {
            Text(
                value.toString(),
                color = textColor,
                fontWeight = FontWeight.Normal,
                fontSize = (size.value * 0.46f).sp
            )
        } else if (cell.notes.isNotEmpty()) {
            KenKenNotesGrid(
                notes = cell.notes,
                boardSize = boardSize,
                cellSize = size,
                textColor = if (isSelected) palette.textOnAccent else palette.noteText
            )
        }
    }
}

@Composable
private fun KenKenNotesGrid(notes: Set<Int>, boardSize: Int, cellSize: Dp, textColor: Color) {
    val cols = ceil(sqrt(boardSize.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(boardSize / cols.toFloat()).toInt().coerceAtLeast(1)
    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        for (r in 0 until rows) {
            Row(modifier = Modifier.weight(1f)) {
                for (c in 0 until cols) {
                    val digit = r * cols + c + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (digit <= boardSize && digit in notes) {
                            Text(digit.toString(), color = textColor, fontSize = (cellSize.value * (0.62f / cols)).sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KenKenNumberPad(
    size: Int,
    notesMode: Boolean,
    remainingCounts: Map<Int, Int>,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onToggleNotesMode: () -> Unit,
    palette: KenKenPalette
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 1..size) {
                val usedUp = (remainingCounts[d] ?: 1) <= 0
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.chipBackground)
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
private fun KenKenFinishedPanel(
    mistakes: Int,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: KenKenPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Solved!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
