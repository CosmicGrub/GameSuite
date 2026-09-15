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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.lightsout.LightsOutGame
import com.gamesuite.games.lightsout.LightsOutStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders LightsOutGame's state reactively — the simplest screen in this new
 * batch, matching the simplest engine: no notes/flag-mode toggle at all,
 * every tap is the same single action (press). Same overall shape as
 * MinesweeperScreen/SudokuScreen otherwise (difficulty selector, live status
 * row, finished-board panel).
 *
 * VISUAL IDENTITY: shares its warm background/accent tokens with
 * MinesweeperScreen/SudokuScreen (see [lightsOutPalette]) for one consistent
 * "new games" identity across this batch. Never reads
 * `MaterialTheme.colorScheme` for gameplay colors, same standing rule as
 * every other game's board.
 */
@Composable
fun LightsOutScreen(
    sessionManager: GameSessionManager,
    game: LightsOutGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's board for every player — see LightsOutGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.LIGHTS_OUT, enabled = musicEnabled)
    val statsStore = remember { LightsOutStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = lightsOutPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

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
    // Keyed on the actual board (unique per round, stable across moves of the SAME round) --
    // `.size` alone doesn't change between two rounds of the same difficulty, which let round
    // 1's result silently keep showing on every later round -- the same fix WordGuess's own
    // reportedResult already applies (see that screen's own comment), mirrored here.
    var reportedResult by remember(s.cells, game.difficulty) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    // Live "Time: M:SS" display -- same idiom as every other solo puzzle's
    // own live-timer LaunchedEffect (the engine only exposes raw
    // elapsedRealtime() readings; this loop drives the ticking).
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
                // Switching difficulty always deals a fresh board immediately
                // (same as tapping the ↻ button below) -- no reason to block
                // this mid-puzzle or once one is already solved.
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
                minOf(maxWidth / s.size, maxHeight / s.size, 56.dp).coerceAtLeast(28.dp)
            }
            Column {
                for (row in 0 until s.size) {
                    Row {
                        for (col in 0 until s.size) {
                            val index = row * s.size + col
                            LightsOutCellView(
                                lit = s.cells[index],
                                size = cellSize,
                                palette = palette,
                                onTap = {
                                    if (!s.isOver) {
                                        game.press(index)
                                        sounds.playTap()
                                        haptics(HapticSignal.NORMAL_ACTION)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

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
// Warm, Chogan-inspired palette -- shares its base tokens with
// MinesweeperPalette/SudokuPalette (see this file's class KDoc for why this
// doesn't read MaterialTheme.colorScheme), extended with an "on" glow color.
// ---------------------------------------------------------------------------
private data class LightsOutPalette(
    val background: Color,
    val cellOff: Color,
    val cellOn: Color,
    val cellOnGlow: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color
)

@Composable
private fun lightsOutPalette(isDark: Boolean): LightsOutPalette = if (!isDark) {
    LightsOutPalette(
        background = Color(0xFFFBF1E6),
        cellOff = Color(0xFF3A2E22),
        cellOn = Color(0xFFF5C453),
        cellOnGlow = Color(0xFFFFE39A),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5)
    )
} else {
    LightsOutPalette(
        background = Color(0xFF1C1712),
        cellOff = Color(0xFF15110D),
        cellOn = Color(0xFFF0B840),
        cellOnGlow = Color(0xFFFFD873),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712)
    )
}

@Composable
private fun DifficultyTabs(current: CpuDifficulty, palette: LightsOutPalette, onSelect: (CpuDifficulty) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tier in CpuDifficulty.entries) {
            val selected = tier == current
            val label = when (tier) {
                CpuDifficulty.EASY -> "Easy (3x3)"
                CpuDifficulty.MEDIUM -> "Medium (5x5)"
                CpuDifficulty.HARD -> "Hard (7x7)"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) palette.accent else palette.cellOff.copy(alpha = 0.12f))
                    .clickable { onSelect(tier) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
    palette: LightsOutPalette
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
private fun LightsOutCellView(
    lit: Boolean,
    size: Dp,
    palette: LightsOutPalette,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (lit) palette.cellOn else palette.cellOff)
            .clickable(onClick = onTap)
    )
}

@Composable
private fun FinishedPanel(
    moves: Int,
    record: com.gamesuite.games.lightsout.LightsOutRecord?,
    isNewBestMoves: Boolean,
    isNewBestTime: Boolean,
    onNewBoard: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: LightsOutPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Lights out!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
