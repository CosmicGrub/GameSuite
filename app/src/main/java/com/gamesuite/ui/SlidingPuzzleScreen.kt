package com.gamesuite.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.slidingpuzzle.SlidingPuzzleGame
import com.gamesuite.settings.SettingsViewModel

/**
 * Renders SlidingPuzzleGame's state reactively — same shape as HangmanScreen:
 * a difficulty label sourced from Settings, a live move counter, and a
 * solved-panel with New Puzzle / Back to Menu once the board is arranged
 * correctly. Tiles are colored by number (HSV hues spaced evenly across the
 * palette) rather than plain gray, so a solved board reads as a simple color
 * mosaic — the "picture" half of the roadmap's "picture puzzles" framing,
 * without needing any bitmap/image-slicing asset pipeline.
 */
@Composable
fun SlidingPuzzleScreen(
    sessionManager: GameSessionManager,
    game: SlidingPuzzleGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
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
                Text("Moves: ${s.moveCount}", style = MaterialTheme.typography.titleMedium)
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
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(if (isBlank) Color.LightGray.copy(alpha = 0.3f) else tileColor(value, totalNumberedTiles))
                                    .clickable(enabled = !isBlank && !s.solved) { game.tapTile(index) },
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
                        SolvedPanel(onPlayAgain = game::playAgain, onBackToMenu = game::leaveSession)
                    }
                }
            }
        }
    )
}

/** Distinct flat color per tile number, hues spaced evenly around the wheel so a solved board reads as a mosaic. */
private fun tileColor(number: Int, totalNumberedTiles: Int): Color {
    val hue = 360f * (number - 1) / totalNumberedTiles
    return Color.hsv(hue = hue, saturation = 0.55f, value = 0.9f)
}

@Composable
private fun SolvedPanel(
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit
) {
    Card(modifier = Modifier.padding(16.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Solved!", style = MaterialTheme.typography.headlineSmall)
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
