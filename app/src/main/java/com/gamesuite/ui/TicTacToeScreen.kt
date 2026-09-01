package com.gamesuite.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
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
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.tictactoe.TicTacToeGame
import kotlinx.coroutines.delay

/**
 * Renders TicTacToeGame's state reactively. All game rules live in
 * TicTacToeGame itself (a GameModule) — this composable only draws the
 * board and forwards taps to it. Fidelity pass: each mark scales in when
 * placed, with a tap sound + haptic — the same "reusable feedback" pattern
 * as the card engine's playPlace(), applied to a board game instead.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun TicTacToeScreen(
    sessionManager: GameSessionManager,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val game = remember { TicTacToeGame() }
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
    }

    val board = game.board.value
    val currentPlayer = game.currentPlayer.value
    val matchOver = game.matchOver.value

    // Drive the bot's turn automatically, mirroring MancalaScreen's pattern:
    // once it becomes a bot player's turn, wait a beat and let it move itself.
    LaunchedEffect(currentPlayer, matchOver) {
        val ctx = context ?: return@LaunchedEffect
        if (matchOver) return@LaunchedEffect
        if (ctx.players.getOrNull(currentPlayer - 1)?.isBot == true) {
            delay(500)
            game.playBotTurn()
        }
    }

    val isBotTurn = context?.players?.getOrNull(currentPlayer - 1)?.isBot == true

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
                    if (isBotTurn) "Bot is thinking..." else "Player $currentPlayer's turn",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.size(300.dp)
                ) {
                    items(9) { index ->
                        val cellValue = board[index]
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(92.dp)
                                .background(Color.LightGray)
                                .clickable(enabled = cellValue == 0 && !isBotTurn) {
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
            }
        }
    )
}
