package com.gamesuite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.hangman.HangmanGame
import com.gamesuite.settings.SettingsViewModel

/**
 * Research pass (README item 9f) added: the word is now drawn from a
 * difficulty-tiered pool sourced from Settings' "Default CPU difficulty" —
 * a solo word-guessing puzzle has no opponent to make smarter/dumber, so the
 * word itself is the difficulty lever here (see HangmanGame's KDoc). Also
 * added a running session score and "New Word" so one loss doesn't end the
 * whole visit to this screen — previously "Back to menu" was the only way
 * forward after any single round.
 */
@Composable
fun HangmanScreen(
    sessionManager: GameSessionManager,
    game: HangmanGame,
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
    val wins = game.wins.value
    val losses = game.losses.value

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
                Text(
                    "Wins: $wins · Losses: $losses",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "Word difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(16.dp))
                Text("Guesses left: ${s.remainingGuesses}")
                Spacer(Modifier.height(16.dp))
                Text(s.revealedWord, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))

                if (s.matchOver) {
                    Text(if (s.won) "You got it!" else "Out of guesses — the word was ${s.word}")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = game::playAgain) { Text("New Word") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 56.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(('A'..'Z').toList()) { letter ->
                            val used = letter in s.guessedLetters
                            val correct = used && letter in s.word
                            // A single letter is not enough for a screen reader to
                            // announce meaningfully on its own — this states the
                            // letter plus its guessed/correct/incorrect status,
                            // since a sighted player gets that same information
                            // from the button being disabled and (once revealed
                            // in the word) which letters turned out right.
                            val status = when {
                                !used -> "not guessed"
                                correct -> "correct"
                                else -> "incorrect"
                            }
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
                                modifier = Modifier
                                    .padding(2.dp)
                                    .semantics { contentDescription = "Letter $letter, $status" },
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
