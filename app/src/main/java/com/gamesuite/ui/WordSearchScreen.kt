package com.gamesuite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.wordgames.wordsearch.GridPos
import com.gamesuite.games.wordgames.wordsearch.PlacedWord
import com.gamesuite.games.wordgames.wordsearch.WordSearchGame
import com.gamesuite.settings.SettingsViewModel

/** Smallest a grid cell is allowed to shrink to — below this, tapping accuracy suffers. */
private val MIN_CELL_SIZE = 24.dp
private val CELL_SPACING = 1.dp

/**
 * Research pass (README item 9k) added: the grid/word-count/direction set
 * now comes from a difficulty-tiered generator sourced from Settings'
 * "Default CPU difficulty" (see WordSearchGame's KDoc), a running session
 * tally, and New Puzzle/Back to Menu — previously finding the last word
 * immediately ended the whole visit to this screen.
 */
@Composable
fun WordSearchScreen(
    sessionManager: GameSessionManager,
    game: WordSearchGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.loadDictionary(androidContext)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
    }

    val s = state ?: return
    val puzzlesSolved = game.puzzlesSolved.value

    // Primary-only (no secondary/hand content in this game) — on a Tab S9 or
    // unfolded Fold this caps + centers the word-search column instead of it
    // stretching edge-to-edge; on everything else it's unchanged. The grid's
    // own BoxWithConstraints below correctly re-measures off the (now capped)
    // available width, so cell sizing still adapts sensibly in TABLET mode.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize(),
        primary = {
            // verticalScroll on the outer column guarantees the "Back to menu" button below the
            // grid is always reachable, even on small screens or large font scales where the
            // title + word list + grid would otherwise overflow the viewport with no scroll path.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("Puzzles solved: $puzzlesSolved", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(8.dp))
                Text("Find all ${s.placedWords.size} words", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                FlowText(
                    placedWords = s.placedWords,
                    found = s.foundWords
                )

                Spacer(Modifier.height(12.dp))

                val cellFoundSet: Set<GridPos> = remember(s.foundWords) {
                    s.placedWords.filter { it.id in s.foundWords }.flatMap { it.cells }.toSet()
                }

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    // The grid must keep exactly s.grid.size columns (tapCell()'s row/col math
                    // depends on it), so column count can't adapt — instead derive cell size from
                    // available width, with a floor for touch usability. If the floor would overflow
                    // the available width, let the grid pan horizontally instead of squeezing cells
                    // down to unusable, unresponsive-looking sizes.
                    val naturalCellSize = (maxWidth - CELL_SPACING * (s.grid.size - 1)) / s.grid.size
                    val cellSize = maxOf(naturalCellSize, MIN_CELL_SIZE)
                    val gridWidth = cellSize * s.grid.size + CELL_SPACING * (s.grid.size - 1)

                    val grid: @Composable () -> Unit = {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(s.grid.size),
                            modifier = Modifier.size(width = gridWidth, height = gridWidth),
                            horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
                            verticalArrangement = Arrangement.spacedBy(CELL_SPACING)
                        ) {
                            items(s.grid.size * s.grid.size) { index ->
                                val row = index / s.grid.size
                                val col = index % s.grid.size
                                val pos = GridPos(row, col)
                                val letter = s.grid[row][col]
                                val isFound = pos in cellFoundSet
                                val isSelected = pos == s.selectionStart

                                Box(
                                    modifier = Modifier
                                        .size(cellSize)
                                        .background(
                                            when {
                                                isFound -> Color(0xFF81C784)
                                                isSelected -> Color(0xFFFFF176)
                                                else -> Color(0xFFEEEEEE)
                                            }
                                        )
                                        .clickable(enabled = !s.solved) { game.tapCell(pos) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(letter.toString())
                                }
                            }
                        }
                    }

                    if (cellSize > naturalCellSize) {
                        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) { grid() }
                    } else {
                        grid()
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (s.solved) {
                    Text("All words found!", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = game::playAgain) { Text("New Puzzle") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
                } else {
                    OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
                }
            }
        }
    )
}

@Composable
private fun FlowText(placedWords: List<PlacedWord>, found: Set<Int>) {
    Row {
        Text(
            placedWords.joinToString("   ") { pw -> if (pw.id in found) "✓${pw.word}" else pw.word },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
