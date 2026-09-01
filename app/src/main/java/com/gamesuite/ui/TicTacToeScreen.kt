package com.gamesuite.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.ExperimentalAnimationApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders TicTacToeGame's state reactively. All game rules live in
 * TicTacToeGame itself (a GameModule) — this composable only draws the
 * board and forwards taps to it. Fidelity pass: each mark scales in when
 * placed, with a tap sound + haptic — the same "reusable feedback" pattern
 * as the card engine's playPlace(), applied to a board game instead.
 *
 * Research pass (README item 9a) added: the winning line highlights instead
 * of the match silently ending, and a round-over panel with a running
 * session score + Play Again — previously a win/draw kicked the player
 * straight back to the main menu with no chance to see the result or play
 * another round. The CPU's difficulty is pre-set from Settings' own
 * "Default CPU difficulty" (`settingsViewModel`) — the first game in this
 * suite to actually honor that setting rather than just storing it.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun TicTacToeScreen(
    sessionManager: GameSessionManager,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val game = remember { TicTacToeGame() }
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = LocalHapticFeedback.current
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

    val board = game.board.value
    val currentPlayer = game.currentPlayer.value
    val roundOver = game.roundOver.value
    val winningLine = game.winningLine.value
    val scoreP1 = game.scoreP1.value
    val scoreP2 = game.scoreP2.value
    val draws = game.draws.value

    // Drive the bot's turn automatically, mirroring MancalaScreen's pattern:
    // once it becomes a bot player's turn, wait a beat and let it move itself.
    LaunchedEffect(currentPlayer, roundOver) {
        val ctx = context ?: return@LaunchedEffect
        if (roundOver) return@LaunchedEffect
        if (ctx.players.getOrNull(currentPlayer - 1)?.isBot == true) {
            delay(500)
            game.playBotTurn()
        }
    }

    val isBotTurn = context?.players?.getOrNull(currentPlayer - 1)?.isBot == true
    val vsCpu = context?.players?.any { it.isBot } == true
    val p1Name = context?.players?.getOrNull(0)?.displayName ?: "Player 1"
    val p2Name = context?.players?.getOrNull(1)?.displayName ?: "Player 2"

    // Primary-only (no secondary/hand content in this game) — on a Tab S9 or
    // unfolded Fold this just caps + centers the board instead of it sitting
    // tiny-and-off-center in a huge stretched Column; on everything else it's
    // the exact same fillMaxSize().padding(24.dp) layout as before.
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
                    "$p1Name: $scoreP1 · $p2Name: $scoreP2" + if (draws > 0) " · Draws: $draws" else "",
                    style = MaterialTheme.typography.labelLarge
                )
                if (vsCpu) {
                    Text(
                        "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    when {
                        isBotTurn -> "Bot is thinking..."
                        currentPlayer == 1 -> "${possessive(p1Name)} turn"
                        else -> "${possessive(p2Name)} turn"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                Box(contentAlignment = Alignment.Center) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.size(300.dp),
                        userScrollEnabled = false
                    ) {
                        items(9) { index ->
                            val cellValue = board[index]
                            val isWinningCell = winningLine?.contains(index) == true
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(92.dp)
                                    .background(if (isWinningCell) Color(0xFFFFD54F) else Color.LightGray)
                                    .clickable(enabled = cellValue == 0 && !isBotTurn && !roundOver) {
                                        game.cellClicked(index)
                                        sounds.playTap()
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = cellValue != 0,
                                    enter = scaleIn(
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                        initialScale = 0.3f
                                    )
                                ) {
                                    val mark = if (cellValue == 1) "X" else "O"
                                    val color = if (cellValue == 1) Color(0xFF1976D2) else Color(0xFFD32F2F)
                                    Text(mark, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
                                }
                            }
                        }
                    }

                    if (roundOver) {
                        RoundOverPanel(
                            resultText = when {
                                winningLine == null -> "It's a draw!"
                                vsCpu && currentPlayer == 1 -> "You win!"
                                vsCpu -> "CPU wins!"
                                else -> "${if (currentPlayer == 1) p1Name else p2Name} wins!"
                            },
                            onPlayAgain = game::playAgain,
                            onBackToMenu = game::leaveSession
                        )
                    }
                }
            }
        }
    )
}

/** "You" -> "Your" (not "You's"); any other display name -> "Name's". */
private fun possessive(name: String): String = if (name == "You") "Your" else "$name's"

@Composable
private fun RoundOverPanel(
    resultText: String,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit
) {
    Card(modifier = Modifier.padding(16.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(resultText, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onPlayAgain) {
                Text("Play Again")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onBackToMenu) {
                Text("Back to Menu")
            }
        }
    }
}
