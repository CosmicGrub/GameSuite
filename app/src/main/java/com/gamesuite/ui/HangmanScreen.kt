package com.gamesuite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.hangman.HangmanGame

@Composable
fun HangmanScreen(
    sessionManager: GameSessionManager,
    game: HangmanGame,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
        }
        game.startMatch()
    }

    val s = state ?: return

    // Primary-only (no secondary/hand content in this game) — same treatment
    // as TicTacToeScreen: caps + centers on a Tab S9 / unfolded Fold instead
    // of sitting stretched across the full width.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(24.dp),
        primary = {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Guesses left: ${s.remainingGuesses}")
                Spacer(Modifier.height(16.dp))
                Text(s.revealedWord, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))

                if (s.matchOver) {
                    Text(if (s.won) "You got it!" else "Out of guesses — the word was ${s.word}")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onMatchEnded) { Text("Back to menu") }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 56.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(('A'..'Z').toList()) { letter ->
                            val used = letter in s.guessedLetters
                            // Material3's default Button content padding is 24dp
                            // horizontal — sized for real text labels, not a
                            // single letter squeezed into a ~50dp grid cell. Left
                            // at the default, that padding alone exceeds the
                            // button's own available width, leaving zero room for
                            // the Text and rendering every letter invisible (found
                            // via on-device inspection — confirmed empty text in
                            // the accessibility tree, not just a visual clip).
                            Button(
                                onClick = { game.guessLetter(letter) },
                                enabled = !used,
                                modifier = Modifier.padding(2.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text(letter.toString())
                            }
                        }
                    }
                }
            }
        }
    )
}
