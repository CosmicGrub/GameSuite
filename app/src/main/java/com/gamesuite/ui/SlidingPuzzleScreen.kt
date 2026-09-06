package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.slidingpuzzle.SlidingPuzzleGame
import com.gamesuite.games.slidingpuzzle.SlidingPuzzleRecord
import com.gamesuite.games.slidingpuzzle.SlidingPuzzleStatsStore
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders SlidingPuzzleGame's state reactively — same shape as HangmanScreen:
 * a difficulty label sourced from Settings, a live move counter, and a
 * solved-panel with New Puzzle / Back to Menu once the board is arranged
 * correctly. Tiles are colored by number (HSV hues spaced evenly across the
 * palette) rather than plain gray, so a solved board reads as a simple color
 * mosaic — the "picture" half of the roadmap's "picture puzzles" framing,
 * without needing any bitmap/image-slicing asset pipeline.
 *
 * Also owns everything the stopwatch/best-record feature needs on the UI
 * side: [SlidingPuzzleStatsStore] is created and collected here (the game
 * engine only exposes raw timer readings, see SlidingPuzzleGame's KDoc), the
 * live elapsed-time text ticks off a small `LaunchedEffect` loop reading
 * `SystemClock.elapsedRealtime()` rather than the engine running its own
 * coroutine, and the "New best!" banner is computed the moment the puzzle's
 * `solved` flag flips true by comparing this solve against the stored
 * record before overwriting it.
 *
 * Sound/haptics reuse the exact CardSounds/LocalHapticFeedback idiom every
 * other game screen already uses (see e.g. MancalaScreen): `playTap()` +
 * `LongPress` on a tile slide, matching the "generic board-game move" feel
 * TicTacToe/Mancala/Dominoes already use `playTap()` for. Puzzle-solved gets
 * `playShuffle()` instead — the one CardSounds clip not already claimed by a
 * per-move sound elsewhere, repurposed here as this screen's one-off "big
 * moment" cue since nothing in the suite has a dedicated win/fanfare sound.
 */
@Composable
fun SlidingPuzzleScreen(
    sessionManager: GameSessionManager,
    game: SlidingPuzzleGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = LocalHapticFeedback.current
    val statsStore = remember { SlidingPuzzleStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
    }

    val s = state ?: return
    val puzzlesSolved = game.puzzlesSolved.value
    val totalNumberedTiles = s.size * s.size - 1

    val allRecords by statsStore.records.collectAsState(initial = emptyMap())
    val record = allRecords[game.difficulty.name] ?: SlidingPuzzleRecord()

    // Live "Time: M:SS" display. The engine only exposes raw elapsedRealtime()
    // readings (timerStartElapsedRealtime/solvedElapsedMillis) rather than
    // ticking a value itself, so this loop is what actually drives
    // recomposition once a second while a puzzle is in progress; it goes
    // idle (no delay loop running) both before the first move and once
    // solved, when the frozen solvedElapsedMillis reading is shown instead.
    var liveElapsedMillis by remember { mutableStateOf(0L) }
    LaunchedEffect(game.timerStartElapsedRealtime.value, s.solved) {
        val start = game.timerStartElapsedRealtime.value
        if (start == null) {
            liveElapsedMillis = 0L
            return@LaunchedEffect
        }
        while (!s.solved) {
            liveElapsedMillis = SystemClock.elapsedRealtime() - start
            delay(200)
        }
    }
    val displayedElapsedMillis = if (s.solved) (game.solvedElapsedMillis.value ?: liveElapsedMillis) else liveElapsedMillis

    // Fires exactly once per solved puzzle (keyed on puzzlesSolved as well as
    // s.solved so a same-boolean edge case can't suppress a re-trigger): the
    // "solved" sound/haptic, persisting this run's (moves, time) into
    // SlidingPuzzleStatsStore, and computing whether either half of the
    // record actually improved for the "New best!" banner below.
    var newBestMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.solved, puzzlesSolved) {
        if (!s.solved) {
            newBestMessage = null
            return@LaunchedEffect
        }
        sounds.playShuffle()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val elapsed = game.solvedElapsedMillis.value ?: 0L
        val result = statsStore.recordSolve(game.difficulty, s.moveCount, elapsed)
        newBestMessage = when {
            result.isNewBestMoves && result.isNewBestTimeMillis -> "New best! Fewest moves and fastest time."
            result.isNewBestMoves -> "New best move count!"
            result.isNewBestTimeMillis -> "New best time!"
            else -> null
        }
    }

    // Primary-only (no secondary/hand content in this game) — same treatment
    // as TicTacToeScreen/HangmanScreen.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(24.dp),
        primary = {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Puzzles solved: $puzzlesSolved", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}" +
                        " (${s.size}×${s.size})",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(8.dp))
                Text("Moves: ${s.moveCount}   Time: ${formatElapsed(displayedElapsedMillis)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Best: " + (record.bestMoves?.let { "$it moves" } ?: "—") +
                        " · " + (record.bestTimeMillis?.let { formatElapsed(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(16.dp))

                Box(contentAlignment = Alignment.Center) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(s.size),
                        modifier = Modifier.size(300.dp),
                        userScrollEnabled = false
                    ) {
                        items(s.tiles.size) { index ->
                            val value = s.tiles[index]
                            val isBlank = value == 0
                            val row = index / s.size + 1
                            val col = index % s.size + 1
                            val description = if (isBlank) {
                                "Blank space, row $row column $col"
                            } else {
                                "Tile $value, row $row column $col"
                            }
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(if (isBlank) Color.LightGray.copy(alpha = 0.3f) else tileColor(value, totalNumberedTiles))
                                    .clickable(enabled = !isBlank && !s.solved) {
                                        val movesBefore = s.moveCount
                                        game.tapTile(index)
                                        // tapTile no-ops for a tap on a tile that isn't adjacent
                                        // to the blank, so only fire feedback for an actual slide.
                                        if (game.state.value?.moveCount != movesBefore) {
                                            sounds.playTap()
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                    .semantics { contentDescription = description },
                                contentAlignment = Alignment.Center
                            ) {
                                if (!isBlank) {
                                    Text(
                                        value.toString(),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }

                    if (s.solved) {
                        SolvedPanel(newBestMessage = newBestMessage, onPlayAgain = game::playAgain, onBackToMenu = game::leaveSession)
                    }
                }
            }
        }
    )
}

/** Formats a millisecond duration as "M:SS" for the live/best-time displays. */
private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Distinct flat color per tile number, hues spaced evenly around the wheel so a solved board reads as a mosaic. */
private fun tileColor(number: Int, totalNumberedTiles: Int): Color {
    val hue = 360f * (number - 1) / totalNumberedTiles
    return Color.hsv(hue = hue, saturation = 0.55f, value = 0.9f)
}

@Composable
private fun SolvedPanel(
    newBestMessage: String?,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit
) {
    Card(modifier = Modifier.padding(16.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Solved!", style = MaterialTheme.typography.headlineSmall)
            if (newBestMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    newBestMessage,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onPlayAgain) {
                Text("New Puzzle")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onBackToMenu) {
                Text("Back to Menu")
            }
        }
    }
}
