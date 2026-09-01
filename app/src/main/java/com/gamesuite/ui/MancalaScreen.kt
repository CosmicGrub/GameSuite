package com.gamesuite.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.mancala.MancalaGame
import kotlinx.coroutines.delay

@Composable
fun MancalaScreen(
    sessionManager: GameSessionManager,
    game: MancalaGame,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = LocalHapticFeedback.current
    val state by game.state

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd { result -> sessionManager.endActiveGame(result) }
        game.startMatch()
    }

    // Keyed on the whole state object (not just currentPlayerIndex/matchOver) so this
    // relaunches on every move, including a bonus/extra turn where the mover keeps
    // possession of currentPlayerIndex — sow() always changes pits/lastAction, so a new
    // MancalaState is never equal to the previous one even when currentPlayerIndex repeats.
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        val ctx = context ?: return@LaunchedEffect
        if (s.matchOver) return@LaunchedEffect
        if (ctx.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(700)
            game.playBotTurn()
        }
    }

    val s = state ?: return
    val ctx = context ?: return

    if (s.matchOver) {
        val winner = ctx.players.firstOrNull { it.playerId == s.winnerPlayerId }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(if (winner != null) "${winner.displayName} wins!" else "It's a tie!", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onMatchEnded) { Text("Back to menu") }
        }
        return
    }

    // Rows are gated by whose pits they are and whose turn it currently is — NOT by a
    // fixed "human index" (SINGLE_DEVICE_PASS_AND_PLAY has two non-bot players, so a
    // fixed index would permanently favor one side and dead-end the other's turn).
    // isHumanTurn additionally blocks the human from tapping while a bot is thinking,
    // preserving vs-bot behavior without needing to know which side "the human" is.
    val isHumanTurn = ctx.players.getOrNull(s.currentPlayerIndex)?.isBot != true

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (isHumanTurn) "Your turn — tap a pit to sow" else "Opponent's turn", style = MaterialTheme.typography.titleMedium)
        Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        // Player 1 (top row, pits 12 down to 7, right-to-left visually) and stores on the sides.
        // The board is scaled to fit the available width (BoxWithConstraints) so all 6 pits
        // and both stores stay reachable on narrow screens (e.g. a Fold cover display) and the
        // board can grow on wide/unfolded screens instead of sitting tiny with large margins.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val baseBoardWidth = 488.dp // 6 * 60dp pits/row + 2 * 64dp stores, see PitView/StoreView
            val scaleFactor = (maxWidth / baseBoardWidth).coerceIn(0.5f, 1.5f)
            val pitSize = 52.dp * scaleFactor
            val storeWidth = 48.dp * scaleFactor
            val storeHeight = 140.dp * scaleFactor

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                StoreView(count = s.pits[13], width = storeWidth, height = storeHeight)
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        (12 downTo 7).forEach { pit ->
                            PitView(
                                count = s.pits[pit],
                                enabled = isHumanTurn && s.currentPlayerIndex == 1 && s.pits[pit] > 0,
                                size = pitSize,
                                onClick = {
                                    game.sow(1, pit)
                                    sounds.playTap()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        (0..5).forEach { pit ->
                            PitView(
                                count = s.pits[pit],
                                enabled = isHumanTurn && s.currentPlayerIndex == 0 && s.pits[pit] > 0,
                                size = pitSize,
                                onClick = {
                                    game.sow(0, pit)
                                    sounds.playTap()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            )
                        }
                    }
                }
                StoreView(count = s.pits[6], width = storeWidth, height = storeHeight)
            }
        }
    }
}

@Composable
private fun PitView(count: Int, enabled: Boolean, size: Dp = 52.dp, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (count > 0) 1f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pitScale"
    )
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(if (enabled) Color(0xFFD7CCC8) else Color(0xFFEFEBE9))
            .border(1.dp, Color(0xFF8D6E63), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(count.toString(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StoreView(count: Int, width: Dp = 48.dp, height: Dp = 140.dp) {
    Box(
        modifier = Modifier
            .padding(8.dp)
            .size(width = width, height = height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF6D4C41)),
        contentAlignment = Alignment.Center
    ) {
        Text(count.toString(), color = Color.White, fontWeight = FontWeight.Bold)
    }
}
