package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
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
import com.gamesuite.games.kakuro.KakuroCell
import com.gamesuite.games.kakuro.KakuroCellType
import com.gamesuite.games.kakuro.KakuroGame
import com.gamesuite.games.kakuro.KakuroState
import com.gamesuite.games.kakuro.KakuroStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders KakuroGame's state reactively -- same overall shape as
 * SudokuScreen (difficulty selector, live status row, select-then-enter
 * input via a number pad plus a notes-mode toggle, a finished-board panel),
 * adapted for Kakuro's own signature visual: BLACK clue cells split by a
 * diagonal line, the DOWN sum in the upper-right half and the ACROSS sum in
 * the lower-left half -- the standard Kakuro grid convention.
 *
 * Music: [MusicProfiles.PUZZLE_FOCUS] directly, matching EdgeMatchScreen's/
 * NonogramScreen's own choice for a quiet solo puzzle -- no bespoke
 * `MusicProfiles.KAKURO` alias needed.
 *
 * VISUAL IDENTITY: shares this batch's warm Chogan-inspired tokens (see
 * [kakuroPalette]) -- never reads `MaterialTheme.colorScheme` for gameplay
 * colors, same standing rule as every other game's board in this app.
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
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { KakuroStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = kakuroPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

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

    // Live "Time: M:SS" display -- same idiom as SudokuScreen's own live-timer LaunchedEffect.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KakuroDifficultyTabs(
            current = game.difficulty,
            palette = palette,
            onSelect = { tier ->
                if (tier != game.difficulty) {
                    game.difficulty = tier
                    game.startMatch()
                    notesMode = false
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        KakuroStatusRow(
            mistakes = s.mistakes,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            onNewPuzzle = { game.startMatch(); notesMode = false },
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val cellSize = remember(maxWidth, maxHeight, s.rows, s.cols) {
                minOf(maxWidth / (s.cols + 0.3f), maxHeight / (s.rows + 0.3f), 42.dp).coerceAtLeast(20.dp)
            }
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
                    for (r in 0 until s.rows) {
                        Row {
                            for (c in 0 until s.cols) {
                                val index = r * s.cols + c
                                val cell = s.cells[index]
                                KakuroCellView(
                                    cell = cell,
                                    size = cellSize,
                                    isSelected = index == s.selectedIndex,
                                    isWrong = cell.value != null && cell.value != s.solution[index],
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
        }

        Spacer(Modifier.height(14.dp))

        KakuroNumberPad(
            notesMode = notesMode,
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
            KakuroFinishedPanel(
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

@Composable
private fun KakuroDifficultyTabs(current: CpuDifficulty, palette: KakuroPalette, onSelect: (CpuDifficulty) -> Unit) {
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
private fun KakuroStatusRow(
    mistakes: Int,
    elapsedMillis: Long,
    onNewPuzzle: () -> Unit,
    palette: KakuroPalette
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

/**
 * A BLACK cell shows its clue(s) split by a diagonal line -- DOWN sum in the
 * top-right, ACROSS sum in the bottom-left, the standard Kakuro convention.
 * A cell with only one clue direction shows just that one number (no
 * diagonal line drawn) -- the split line only appears when both are
 * present, since drawing one for a lone clue would be misleading chrome. A
 * WHITE cell shows the player's entered digit (in [KakuroPalette.danger] if
 * it doesn't match the known solution -- SudokuScreen's own immediate-
 * feedback convention, see docs/KAKURO_DESIGN.md's "Mistakes & feedback")
 * or its pencil-mark notes, mirroring SudokuCellView exactly.
 */
@Composable
private fun KakuroCellView(
    cell: KakuroCell,
    size: Dp,
    isSelected: Boolean,
    isWrong: Boolean,
    palette: KakuroPalette,
    onTap: () -> Unit
) {
    when (cell.type) {
        KakuroCellType.BLACK -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .padding(0.5.dp)
                    .background(palette.blackCell)
            ) {
                if (cell.acrossClue != null && cell.downClue != null) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawLine(
                            color = palette.clueText.copy(alpha = 0.5f),
                            start = Offset(0f, 0f),
                            end = Offset(this.size.width, this.size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
                cell.downClue?.let { down ->
                    Text(
                        down.toString(),
                        color = palette.clueText,
                        fontSize = (size.value * 0.24f).sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 2.dp)
                    )
                }
                cell.acrossClue?.let { across ->
                    Text(
                        across.toString(),
                        color = palette.clueText,
                        fontSize = (size.value * 0.24f).sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 2.dp, bottom = 1.dp)
                    )
                }
            }
        }
        KakuroCellType.WHITE -> {
            val bg = if (isSelected) palette.selectedCell else palette.cellBackground
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
                        color = if (isWrong) palette.danger else if (isSelected) palette.textOnAccent else palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value * 0.48f).sp
                    )
                } else if (cell.notes.isNotEmpty()) {
                    KakuroNotesGrid(notes = cell.notes, cellSize = size, textColor = if (isSelected) palette.textOnAccent else palette.noteText)
                }
            }
        }
    }
}

@Composable
private fun KakuroNotesGrid(notes: Set<Int>, cellSize: Dp, textColor: Color) {
    Column(modifier = Modifier.fillMaxSize()) {
        for (row in 0 until 3) {
            Row(modifier = Modifier.weight(1f)) {
                for (col in 0 until 3) {
                    val digit = row * 3 + col + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (digit in notes) {
                            Text(digit.toString(), color = textColor, fontSize = (cellSize.value * 0.2f).sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KakuroNumberPad(
    notesMode: Boolean,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onToggleNotesMode: () -> Unit,
    palette: KakuroPalette
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 1..9) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.chipBackground)
                        .clickable { onDigit(d) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(d.toString(), color = palette.textPrimary, fontWeight = FontWeight.Bold)
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
private fun KakuroFinishedPanel(
    mistakes: Int,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: KakuroPalette
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

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- own private copy, not shared with sibling
// screens (every screen in this app duplicates this small set of tokens
// rather than sharing one), matching this batch's standard hex values.
// ---------------------------------------------------------------------------
private data class KakuroPalette(
    val background: Color,
    val cellBackground: Color,
    val selectedCell: Color,
    val blackCell: Color,
    val clueText: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val noteText: Color,
    val chipBackground: Color,
    val danger: Color
)

@Composable
private fun kakuroPalette(isDark: Boolean): KakuroPalette = if (!isDark) {
    val textPrimary = Color(0xFF3A2E22)
    val textOnAccent = Color(0xFFFFFBF5)
    KakuroPalette(
        background = Color(0xFFFBF1E6),
        cellBackground = Color(0xFFFFFBF5),
        selectedCell = Color(0xFFE08D4B),
        blackCell = textPrimary,
        clueText = textOnAccent,
        accent = Color(0xFFE08D4B),
        textPrimary = textPrimary,
        textOnAccent = textOnAccent,
        noteText = textPrimary.copy(alpha = 0.5f),
        chipBackground = Color(0xFFE3CBA9),
        danger = Color(0xFFD9573F)
    )
} else {
    val textPrimary = Color(0xFFF3E9DB)
    val textOnAccent = Color(0xFF1C1712)
    KakuroPalette(
        background = Color(0xFF1C1712),
        cellBackground = Color(0xFF15110D),
        selectedCell = Color(0xFFE8985B),
        blackCell = textPrimary,
        clueText = textOnAccent,
        accent = Color(0xFFE8985B),
        textPrimary = textPrimary,
        textOnAccent = textOnAccent,
        noteText = textPrimary.copy(alpha = 0.5f),
        chipBackground = Color(0xFF453A2E),
        danger = Color(0xFFE0705A)
    )
}
