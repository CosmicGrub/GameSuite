package com.gamesuite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.wordgames.crossword.CrosswordGame

/**
 * Floor on grid cell size. Below this, stretching every cell to fit
 * maxWidth / gridSize (the old behavior) shrinks cells to ~20-21dp on very
 * narrow displays (e.g. a Fold's cover screen) — instead the grid holds this
 * minimum and becomes horizontally scrollable.
 */
private val MIN_CELL_SIZE = 24.dp

/**
 * Below this cell size, a revealed cell's TopStart clue-number badge and its
 * Center-aligned letter have essentially no clearance from each other. Once
 * a cell is revealed the number is no longer needed (the player can already
 * see the letter), so it's dropped instead of crowding the letter, which
 * itself shrinks to a smaller text style.
 */
private val COMFORTABLE_CELL_SIZE = 32.dp

@Composable
fun CrosswordScreen(
    sessionManager: GameSessionManager,
    game: CrosswordGame,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd { result -> sessionManager.endActiveGame(result) }
        game.startMatch()
    }

    val s = state ?: return

    // Deliberately NOT using FoldAwareTwoPane's secondary slot (unlike
    // Uno/Dominoes/Word Tiles) — those games' `secondary` pane is a small,
    // naturally-bounded "hand" (a row of cards/tiles). This screen's clue
    // list is an open-ended scrollable LazyColumn meant to fill remaining
    // space, and FoldAwareTwoPane's non-book/tabletop fallback renders
    // `secondary` unweighted/measured-first — an unbounded LazyColumn there
    // would claim the *entire* available height before `primary` (the grid)
    // gets a weighted share, collapsing the grid to zero height on every
    // non-separating device (i.e. almost all of them). The grid below is
    // still made responsive directly (min cell-size floor + horizontal
    // scroll fallback) so narrow/foldable-cover-screen widths stay legible.
    //
    // AdaptiveTwoPane is still used here in primary-only mode (secondary =
    // null) purely for the TABLET-mode cap+center treatment — that path
    // never touches the Row/weight machinery above, so the reasoning above
    // still holds.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize(),
        primary = {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    "${s.solvedEntryIds.size}/${s.entries.size} solved",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    val gridDimension = s.grid.size
                    val naturalCellSize = maxWidth / gridDimension
                    // Never let cells get smaller than MIN_CELL_SIZE — below that,
                    // hold the floor and let the grid scroll horizontally instead.
                    val cellSize = maxOf(naturalCellSize, MIN_CELL_SIZE)
                    val needsHorizontalScroll = naturalCellSize < MIN_CELL_SIZE

                    val grid: @Composable () -> Unit = {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridDimension),
                            modifier = if (needsHorizontalScroll) {
                                Modifier.width(cellSize * gridDimension)
                            } else {
                                Modifier.fillMaxWidth()
                            }
                        ) {
                            items(gridDimension * gridDimension) { index ->
                                val row = index / gridDimension
                                val col = index % gridDimension
                                val cell = s.grid[row][col]

                                Box(
                                    modifier = Modifier
                                        .padding(0.5.dp)
                                        .aspectRatio(1f)
                                        .background(if (cell.letter == null) Color.Transparent else Color(0xFFFAFAFA)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (cell.letter != null) {
                                        // A revealed cell shows both the number (TopStart) and the
                                        // letter (Center) in the same box — at small cell sizes they
                                        // have no clearance from each other, so once the cell is
                                        // revealed (the number is no longer needed) drop the badge and
                                        // shrink the letter instead of letting them crowd/overlap.
                                        val cramped = cellSize < COMFORTABLE_CELL_SIZE
                                        if (cell.number != null && !(cell.revealed && cramped)) {
                                            Text(
                                                cell.number.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.align(Alignment.TopStart)
                                            )
                                        }
                                        if (cell.revealed) {
                                            if (cramped) {
                                                Text(cell.letter.toString(), style = MaterialTheme.typography.bodySmall)
                                            } else {
                                                Text(cell.letter.toString())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (needsHorizontalScroll) {
                        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            grid()
                        }
                    } else {
                        grid()
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Clues", style = MaterialTheme.typography.titleSmall)

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(s.entries.sortedBy { it.number }) { entry ->
                        val solved = entry.id in s.solvedEntryIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !solved) { game.selectEntry(entry.id) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                "${entry.number}${if (entry.direction.name == "ACROSS") "A" else "D"}. ${entry.clue}",
                                color = if (solved) Color(0xFF388E3C) else Color.Unspecified
                            )
                        }
                    }
                }

                if (s.matchOver) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onMatchEnded) { Text("Back to menu") }
                }
            }
        }
    )

    val selectedEntry = s.entries.firstOrNull { it.id == s.selectedEntryId }
    if (selectedEntry != null) {
        AnswerDialog(
            clue = selectedEntry.clue,
            length = selectedEntry.word.length,
            onSubmit = { answer -> game.submitAnswer(selectedEntry.id, answer) },
            onDismiss = { game.clearSelection() }
        )
    }
}

@Composable
private fun AnswerDialog(clue: String, length: Int, onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    // Set when a submitted guess didn't match, so the field can show an
    // inline error instead of leaving the dialog looking unresponsive.
    // Cleared as soon as the user edits the field again.
    var isError by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.background(Color.White).padding(24.dp)
        ) {
            Text(clue)
            Spacer(Modifier.height(4.dp))
            Text("$length letters", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    if (it.length <= length) text = it.uppercase()
                    isError = false
                },
                singleLine = true,
                isError = isError,
                supportingText = if (isError) {
                    { Text("Not quite — try again") }
                } else null
            )
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { isError = !onSubmit(text) }) { Text("Submit") }
            }
        }
    }
}
