package com.gamesuite.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.mancala.MancalaGame
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Research pass (README item 9i) added a real CPU difficulty ladder — see
 * MancalaGame's `playBotTurn`/`minimaxBestMove` KDoc — read here from
 * Settings' "Default CPU difficulty" the same way the other per-game
 * upgrade passes already do.
 */
@Composable
fun MancalaScreen(
    sessionManager: GameSessionManager,
    game: MancalaGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = LocalHapticFeedback.current
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

    // Keyed on the whole state object (not just currentPlayerIndex/roundOver) so this
    // relaunches on every move, including a bonus/extra turn where the mover keeps
    // possession of currentPlayerIndex — sow() always changes pits/lastAction, so a new
    // MancalaState is never equal to the previous one even when currentPlayerIndex repeats.
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        val ctx = context ?: return@LaunchedEffect
        if (s.roundOver) return@LaunchedEffect
        if (ctx.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(700)
            game.playBotTurn()
        }
    }

    val s = state ?: return
    val ctx = context ?: return

    if (s.roundOver) {
        val winner = ctx.players.firstOrNull { it.playerId == s.winnerPlayerId }
        val p1Name = ctx.players.getOrNull(0)?.displayName ?: "Player 1"
        val p2Name = ctx.players.getOrNull(1)?.displayName ?: "Player 2"
        val scoreP1 by game.scoreP1
        val scoreP2 by game.scoreP2
        val draws by game.draws
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(if (winner != null) "${winner.displayName} wins!" else "It's a tie!", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "$p1Name: $scoreP1 · $p2Name: $scoreP2" + if (draws > 0) " · Draws: $draws" else "",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = game::playAgain) { Text("Play Again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
        }
        return
    }

    // Rows are gated by whose pits they are and whose turn it currently is — NOT by a
    // fixed "human index" (SINGLE_DEVICE_PASS_AND_PLAY has two non-bot players, so a
    // fixed index would permanently favor one side and dead-end the other's turn).
    // isHumanTurn additionally blocks the human from tapping while a bot is thinking,
    // preserving vs-bot behavior without needing to know which side "the human" is.
    val isHumanTurn = ctx.players.getOrNull(s.currentPlayerIndex)?.isBot != true

    // Capture preview: outline whichever of the current human player's legal pits would
    // land the last stone in an empty pit of theirs. Reuses MancalaGame.captureCandidates,
    // which is itself just a read-only call into the exact legalMoves()/simulateSow() pair
    // the HARD bot's minimax search already relies on -- purely visual, recomputed fresh
    // each recomposition, never touches sow()'s real state.
    val captureCandidates = if (isHumanTurn) game.captureCandidates(s.currentPlayerIndex) else emptySet()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (isHumanTurn) "Your turn — tap a pit to sow" else "Opponent's turn", style = MaterialTheme.typography.titleMedium)
        Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
        if (ctx.players.any { it.isBot }) {
            Text(
                "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.labelSmall
            )
        }
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
                StoreView(count = s.pits[13], ownerLabel = "Opponent", width = storeWidth, height = storeHeight)
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        // Opponent's row runs right-to-left visually (12 downTo 7), but their
                        // pit numbering should still count up from 1 starting at the pit
                        // farthest from their store -- pit 7 -- matching how the player's own
                        // row (below) numbers from the pit farthest from their store (0).
                        (12 downTo 7).forEach { pit ->
                            PitView(
                                count = s.pits[pit],
                                enabled = isHumanTurn && s.currentPlayerIndex == 1 && s.pits[pit] > 0,
                                highlightCapture = pit in captureCandidates,
                                size = pitSize,
                                ownerLabel = "Opponent",
                                pitNumber = pit - 6,
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
                                highlightCapture = pit in captureCandidates,
                                size = pitSize,
                                ownerLabel = "Your",
                                pitNumber = pit + 1,
                                onClick = {
                                    game.sow(0, pit)
                                    sounds.playTap()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            )
                        }
                    }
                }
                StoreView(count = s.pits[6], ownerLabel = "Your", width = storeWidth, height = storeHeight)
            }
        }
    }
}

@Composable
private fun PitView(
    count: Int,
    enabled: Boolean,
    ownerLabel: String,
    pitNumber: Int,
    highlightCapture: Boolean = false,
    size: Dp = 52.dp,
    onClick: () -> Unit
) {
    // Settings -> Accessibility -> Reduced Motion (see settings/LocalReducedMotion.kt) --
    // same technique FannedHand.kt already uses: swap the spring for snap() so the capture
    // shrink/settle still happens, just without the motion.
    val reducedMotion = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (count > 0) 1f else 0.85f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pitScale"
    )
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(if (enabled) Color(0xFFD7CCC8) else Color(0xFFEFEBE9))
            .border(
                if (highlightCapture) 3.dp else 1.dp,
                if (highlightCapture) Color(0xFFFFC107) else Color(0xFF8D6E63),
                CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            // Screen-reader announcement mirrors the visible layout (owner side + this pit's
            // position within that side, farthest-from-store first) plus the live stone count,
            // since a TalkBack user can't see which pit their finger landed on otherwise.
            .semantics { contentDescription = "$ownerLabel pit $pitNumber, ${stoneCountLabel(count)}" },
        contentAlignment = Alignment.Center
    ) {
        Text(count.toString(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StoreView(count: Int, ownerLabel: String, width: Dp = 48.dp, height: Dp = 140.dp) {
    Box(
        modifier = Modifier
            .padding(8.dp)
            .size(width = width, height = height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF6D4C41))
            .semantics { contentDescription = "$ownerLabel store, ${stoneCountLabel(count)}" },
        contentAlignment = Alignment.Center
    ) {
        Text(count.toString(), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** "1 stone" vs "4 stones" -- the pit/store semantics text reads naturally either way. */
private fun stoneCountLabel(count: Int): String = if (count == 1) "1 stone" else "$count stones"
