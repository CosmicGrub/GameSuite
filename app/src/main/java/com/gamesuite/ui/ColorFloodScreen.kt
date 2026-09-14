package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.colorflood.ColorFloodGame
import com.gamesuite.games.colorflood.ColorFloodRecord
import com.gamesuite.games.colorflood.ColorFloodStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders ColorFloodGame's state reactively — same overall shape as
 * LightsOutScreen (difficulty selector, live status row, finished-board
 * panel), but the board itself is pure DISPLAY here: every tap happens on
 * the color swatches below it, not the grid, since a cell's color is what
 * you're choosing FROM, not a thing you act ON directly.
 *
 * VISUAL IDENTITY: chrome (background/text/accent) shares its warm tokens
 * with MinesweeperScreen/SudokuScreen/LightsOutScreen/DotsAndBoxesScreen
 * (see [colorFloodPalette]). The CELL colors themselves are a deliberate
 * exception — this puzzle's entire mechanic depends on genuinely
 * distinguishable hues, so [colorFloodPalette.colors] spans real variety
 * (not shades of one warm tone) the same way Minesweeper's own
 * per-adjacent-count number colors already do, rather than forcing content
 * that needs contrast into a monochrome identity it can't play well in.
 */
@Composable
fun ColorFloodScreen(
    sessionManager: GameSessionManager,
    game: ColorFloodGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's board for every player — see ColorFloodGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.COLOR_FLOOD, enabled = musicEnabled)
    val statsStore = remember { ColorFloodStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = colorFloodPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

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

    val allRecords by statsStore.records.collectAsState(initial = emptyMap())
    val record = allRecords[game.difficulty.name]
    var reportedResult by remember(s.cellColors.size, game.difficulty) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    // Live "Time: M:SS" display -- same idiom as every other solo puzzle's
    // own live-timer LaunchedEffect.
    var liveElapsedMillis by remember(s.size, s.moves == 0) { mutableStateOf(0L) }
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
        if (s.won && reportedResult == null) {
            val finalTime = game.finishedElapsedMillis.value ?: liveElapsedMillis
            val result = statsStore.recordSolve(game.difficulty, s.moves, finalTime)
            reportedResult = result.isNewBestMoves to result.isNewBestTimeMillis
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
        DifficultyTabs(
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

        StatusRow(
            moves = s.moves,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            onNewBoard = { game.startMatch() },
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val cellSize = remember(maxWidth, maxHeight, s.size) {
                minOf(maxWidth / s.size, maxHeight / s.size, 34.dp).coerceAtLeast(14.dp)
            }
            Column {
                for (row in 0 until s.size) {
                    Row {
                        for (col in 0 until s.size) {
                            val index = row * s.size + col
                            ColorFloodCellView(colorIndex = s.cellColors[index], size = cellSize, palette = palette)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        ColorSwatches(
            colorCount = s.colorCount,
            currentColor = s.currentColor,
            palette = palette,
            enabled = !s.isOver,
            onPick = { colorIndex ->
                game.pick(colorIndex)
                sounds.playTap()
                haptics(HapticSignal.NORMAL_ACTION)
            }
        )

        if (s.isOver) {
            Spacer(Modifier.height(16.dp))
            FinishedPanel(
                moves = s.moves,
                record = record,
                isNewBestMoves = reportedResult?.first ?: false,
                isNewBestTime = reportedResult?.second ?: false,
                onNewBoard = { game.startMatch() },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- see this file's own KDoc for why [colors] itself is the one
// deliberate exception, spanning real hue variety rather than warm shades.
// ---------------------------------------------------------------------------
private data class ColorFloodPalette(
    val background: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val colors: List<Color> // index 0..5, matches ColorFloodGame's own color indices
)

@Composable
private fun colorFloodPalette(isDark: Boolean): ColorFloodPalette = if (!isDark) {
    ColorFloodPalette(
        background = Color(0xFFFBF1E6),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9),
        colors = listOf(
            Color(0xFFD9573F), // terracotta red
            Color(0xFFE0A83E), // golden yellow
            Color(0xFF5B9A5B), // leaf green
            Color(0xFF3E7A9E), // ocean blue
            Color(0xFF8A5FA0), // plum purple
            Color(0xFFC9628F)  // warm rose
        )
    )
} else {
    ColorFloodPalette(
        background = Color(0xFF1C1712),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        colors = listOf(
            Color(0xFFE0705A),
            Color(0xFFE8BE6C),
            Color(0xFF7ECB98),
            Color(0xFF7FA6D9),
            Color(0xFFB399D9),
            Color(0xFFE099B8)
        )
    )
}

@Composable
private fun DifficultyTabs(current: CpuDifficulty, palette: ColorFloodPalette, onSelect: (CpuDifficulty) -> Unit) {
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
private fun StatusRow(
    moves: Int,
    elapsedMillis: Long,
    onNewBoard: () -> Unit,
    palette: ColorFloodPalette
) {
    val minutes = (elapsedMillis / 1000) / 60
    val seconds = (elapsedMillis / 1000) % 60
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Moves: $moves", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            "%d:%02d".format(minutes, seconds),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold
        )
        OutlinedButton(onClick = onNewBoard) { Text("↻") }
    }
}

@Composable
private fun ColorFloodCellView(colorIndex: Int, size: Dp, palette: ColorFloodPalette) {
    // Deliberately no padding/rounding/gap here -- a solid, edge-to-edge
    // mosaic is this puzzle's own visual identity, unlike every other
    // board in this batch which separates cells with a small gap.
    Box(modifier = Modifier.size(size).background(palette.colors[colorIndex]))
}

@Composable
private fun ColorSwatches(
    colorCount: Int,
    currentColor: Int,
    palette: ColorFloodPalette,
    enabled: Boolean,
    onPick: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in 0 until colorCount) {
            val isCurrent = c == currentColor
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(palette.colors[c])
                    .then(if (isCurrent) Modifier.border(3.dp, palette.textPrimary, CircleShape) else Modifier)
                    .then(if (enabled && !isCurrent) Modifier.clickable { onPick(c) } else Modifier)
            )
        }
    }
}

@Composable
private fun FinishedPanel(
    moves: Int,
    record: ColorFloodRecord?,
    isNewBestMoves: Boolean,
    isNewBestTime: Boolean,
    onNewBoard: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: ColorFloodPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Board flooded!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Moves: $moves", style = MaterialTheme.typography.bodyMedium)
            if (isNewBestMoves) {
                Spacer(Modifier.height(4.dp))
                Text("New best move count!", color = palette.accent, fontWeight = FontWeight.Bold)
            } else if (record?.bestMoves != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best moves: ${record.bestMoves}", style = MaterialTheme.typography.bodyMedium)
            }
            if (isNewBestTime) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", color = palette.accent, fontWeight = FontWeight.Bold)
            } else if (record?.bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                val m = (record.bestTimeMillis / 1000) / 60
                val sec = (record.bestTimeMillis / 1000) % 60
                Text("Best time: %d:%02d".format(m, sec), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNewBoard) { Text("New Board") }
                OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
            }
        }
    }
}
