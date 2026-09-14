package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.minesweeper.CellState
import com.gamesuite.games.minesweeper.MinesweeperCell
import com.gamesuite.games.minesweeper.MinesweeperGame
import com.gamesuite.games.minesweeper.MinesweeperStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders MinesweeperGame's state reactively — same overall shape as
 * SlidingPuzzleScreen/HangmanScreen: a difficulty selector, a live status
 * row (mines remaining, elapsed time), a flag-mode toggle (this app has no
 * long-press-is-always-flag convention elsewhere, so a tap in flag mode
 * flags instead of revealing — long-press ALSO flags regardless of mode,
 * as a bonus affordance, via [combinedClickable]), and a finished-board
 * panel with New Board / Back to Menu once the board is won or lost.
 *
 * VISUAL IDENTITY: this screen's own gameplay colors are a warm,
 * cream-and-terracotta palette distinct from GameSuite's own Material
 * theme — deliberately, per the project's own "new games only" visual
 * refresh decision: existing games keep their established Material3
 * identity untouched, new games (starting here) get this warm treatment
 * instead. This is consistent with, not an exception to, this project's
 * standing rule that gameplay-meaningful colors (which number means what,
 * which cell is flagged) must never come from `MaterialTheme.colorScheme`
 * in the first place — every game's board colors are already bespoke
 * literals; this file's literals just draw from a different, intentionally
 * chosen palette than Checkers/Chess/etc.'s do. Chrome (buttons, the
 * finished-board panel's Card) still uses MaterialTheme, same as everywhere
 * else, so it still respects the player's own light/dark and accessibility
 * settings.
 */
@Composable
fun MinesweeperScreen(
    sessionManager: GameSessionManager,
    game: MinesweeperGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's board for every player — see MinesweeperGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.MINESWEEPER, enabled = musicEnabled)
    val statsStore = remember { MinesweeperStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = minesweeperPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

    var flagMode by remember { mutableStateOf(false) }

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
    var reportedNewBest by remember(s) { mutableStateOf(false) }

    // Live "Time: M:SS" display -- the engine only exposes raw
    // elapsedRealtime() readings, same idiom as SlidingPuzzleScreen's own
    // live-timer LaunchedEffect (see that screen's KDoc for why this loop,
    // not the engine, drives the ticking).
    var liveElapsedMillis by remember(s.rows, s.cols) { mutableStateOf(0L) }
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
    LaunchedEffect(s.exploded) {
        if (s.exploded) haptics(HapticSignal.FAILURE)
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
                // (same as tapping the ↻ button below) -- there's no reason to
                // block this while a board is in progress or already decided.
                if (tier != game.difficulty) {
                    game.difficulty = tier
                    game.startMatch()
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        StatusRow(
            minesRemaining = s.mineCount - s.flagCount,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            flagMode = flagMode,
            onToggleFlagMode = { flagMode = !flagMode },
            onNewBoard = { game.startMatch(); flagMode = false },
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val cellSize = remember(maxWidth, maxHeight, s.rows, s.cols) {
                val fromWidth = maxWidth / s.cols
                val fromHeight = maxHeight / s.rows
                minOf(fromWidth, fromHeight, 44.dp).coerceAtLeast(20.dp)
            }
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
                    for (row in 0 until s.rows) {
                        Row {
                            for (col in 0 until s.cols) {
                                val index = row * s.cols + col
                                MinesweeperCellView(
                                    cell = s.cells[index],
                                    size = cellSize,
                                    palette = palette,
                                    boardOver = s.isOver,
                                    onReveal = {
                                        if (!s.isOver) {
                                            if (flagMode) {
                                                game.toggleFlag(index)
                                                haptics(HapticSignal.LIGHT_TICK)
                                            } else {
                                                val wasHidden = s.cells[index].state == CellState.HIDDEN
                                                game.revealCell(index)
                                                if (wasHidden) {
                                                    sounds.playTap()
                                                    haptics(HapticSignal.NORMAL_ACTION)
                                                }
                                            }
                                        }
                                    },
                                    onFlagToggle = {
                                        if (!s.isOver) {
                                            game.toggleFlag(index)
                                            haptics(HapticSignal.LIGHT_TICK)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (s.isOver) {
            Spacer(Modifier.height(16.dp))
            FinishedPanel(
                won = s.won,
                bestTimeMillis = bestTimeMillis,
                isNewBest = reportedNewBest,
                onNewBoard = { game.startMatch(); flagMode = false },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan/Tessel-inspired palette -- see this file's class KDoc for why
// this doesn't read MaterialTheme.colorScheme.
// ---------------------------------------------------------------------------
private data class MinesweeperPalette(
    val background: Color,
    val hiddenCell: Color,
    val hiddenCellBorder: Color,
    val revealedCell: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val danger: Color,
    val numberColors: List<Color> // index 0 unused (a 0 renders blank), 1..8
)

@Composable
private fun minesweeperPalette(isDark: Boolean): MinesweeperPalette = if (!isDark) {
    MinesweeperPalette(
        background = Color(0xFFFBF1E6),
        hiddenCell = Color(0xFFF3E4D2),
        hiddenCellBorder = Color(0xFFE3CBA9),
        revealedCell = Color(0xFFFFFBF5),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        danger = Color(0xFFD9573F),
        numberColors = listOf(
            Color.Unspecified,
            Color(0xFF3B6FD4), Color(0xFF3E8E5B), Color(0xFFD9573F), Color(0xFF6B4E9E),
            Color(0xFF9A3B3B), Color(0xFF2E8B8B), Color(0xFF3A2E22), Color(0xFF7A7167)
        )
    )
} else {
    MinesweeperPalette(
        background = Color(0xFF1C1712),
        hiddenCell = Color(0xFF2B241D),
        hiddenCellBorder = Color(0xFF453A2E),
        revealedCell = Color(0xFF15110D),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        danger = Color(0xFFE0705A),
        numberColors = listOf(
            Color.Unspecified,
            Color(0xFF7FA6F0), Color(0xFF7ECB98), Color(0xFFE68A73), Color(0xFFB199D9),
            Color(0xFFCE8A8A), Color(0xFF7FC6C6), Color(0xFFF3E9DB), Color(0xFFA89E92)
        )
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DifficultyTabs(current: CpuDifficulty, palette: MinesweeperPalette, onSelect: (CpuDifficulty) -> Unit) {
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
                    .background(if (selected) palette.accent else palette.hiddenCell)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .combinedClickable(onClick = { onSelect(tier) }),
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
    minesRemaining: Int,
    elapsedMillis: Long,
    flagMode: Boolean,
    onToggleFlagMode: () -> Unit,
    onNewBoard: () -> Unit,
    palette: MinesweeperPalette
) {
    val minutes = (elapsedMillis / 1000) / 60
    val seconds = (elapsedMillis / 1000) % 60
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("💣 $minesRemaining", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            "%d:%02d".format(minutes, seconds),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onToggleFlagMode) {
                Text(if (flagMode) "🚩 Flag mode" else "Flag mode")
            }
            OutlinedButton(onClick = onNewBoard) {
                Text("↻")
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MinesweeperCellView(
    cell: MinesweeperCell,
    size: Dp,
    palette: MinesweeperPalette,
    boardOver: Boolean,
    onReveal: () -> Unit,
    onFlagToggle: () -> Unit
) {
    val bg = when {
        cell.state == CellState.REVEALED && cell.isMine -> palette.danger
        cell.state == CellState.REVEALED -> palette.revealedCell
        else -> palette.hiddenCell
    }
    // Hidden vs. revealed is already unambiguous from the fill-color
    // contrast alone (see the warm palette's hiddenCell/revealedCell) at
    // this cell size -- a separate drawn border would add real complexity
    // (a second layered Box, since Modifier has no plain "border" that
    // respects the clip shape without also needing stroke-width math at
    // small sizes) for little visual gain, so it's deliberately skipped.
    Box(
        modifier = Modifier
            .size(size)
            .padding(1.5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .combinedClickable(
                onClick = onReveal,
                onLongClick = onFlagToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            cell.state == CellState.FLAGGED -> Text("🚩", fontSize = (size.value * 0.5f).sp)
            cell.state == CellState.REVEALED && cell.isMine -> Text("💥")
            cell.state == CellState.REVEALED && cell.adjacentMines > 0 -> Text(
                cell.adjacentMines.toString(),
                color = palette.numberColors[cell.adjacentMines],
                fontWeight = FontWeight.Bold
            )
            else -> {}
        }
    }
}

@Composable
private fun FinishedPanel(
    won: Boolean,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    onNewBoard: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: MinesweeperPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (won) "Board cleared!" else "Boom — you hit a mine",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (won && isNewBest) {
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
                Button(onClick = onNewBoard) { Text("New Board") }
                OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
            }
        }
    }
}
