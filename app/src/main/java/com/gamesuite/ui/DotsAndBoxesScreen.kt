package com.gamesuite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.dotsandboxes.DotsAndBoxesGame
import com.gamesuite.games.dotsandboxes.DotsAndBoxesState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders DotsAndBoxesGame's state reactively. The first TWO-PLAYER game in
 * this new-games batch (Minesweeper/Sudoku/Lights Out were all solo) — no
 * daily-seed route exists here (that's a solo-puzzle concept in this app),
 * and unlike those screens, this one has to represent whose turn it is and
 * trigger the bot's turn itself, same idiom DominoesScreen already uses
 * (an 800ms `LaunchedEffect(state?.currentPlayerIndex, ...)` delay before
 * `game.playBotTurn()`, so a bot's move doesn't feel instantaneous/jarring).
 * In SINGLE_DEVICE_PASS_AND_PLAY mode neither player is a bot, so that
 * effect simply never fires — the same screen serves both modes with no
 * branching required.
 *
 * VISUAL IDENTITY: shares its warm background/textPrimary tokens with
 * MinesweeperScreen/SudokuScreen/LightsOutScreen (see
 * [dotsAndBoxesPalette]) for one consistent "new games" identity, extended
 * with two distinct per-player accent colors (needed here for the first
 * time in this batch, since ownership — which player claimed which box —
 * is the entire point of the board). Never reads `MaterialTheme.colorScheme`
 * for gameplay colors, same standing rule as every other game's board.
 */
@Composable
fun DotsAndBoxesScreen(
    sessionManager: GameSessionManager,
    game: DotsAndBoxesGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.DOTS_AND_BOXES, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = dotsAndBoxesPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

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

    // Same idiom as DominoesScreen's own bot-turn trigger -- a short delay
    // so a bot's move doesn't feel instantaneous, then let the engine's own
    // playBotTurn() handle however many chained extra turns it needs to.
    LaunchedEffect(state?.currentPlayerIndex, state?.boardOver) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(800)
            game.playBotTurn()
        }
    }

    val s = state ?: return

    LaunchedEffect(s.boardOver) {
        if (s.boardOver) haptics(if (s.winnerPlayerId != null) HapticSignal.CELEBRATION else HapticSignal.NORMAL_ACTION)
    }

    val isMyTurn = !s.players[s.currentPlayerIndex].isBot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusRow(s, palette)

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val cellSize = remember(maxWidth, maxHeight, s.boxRows, s.boxCols) {
                minOf(maxWidth / (s.boxCols + 0.6f), maxHeight / (s.boxRows + 0.6f), 46.dp).coerceAtLeast(22.dp)
            }
            val dotSize = 7.dp
            val edgeThickness = 14.dp // generous tap target around the thinner drawn line itself

            Column {
                for (boxRow in 0..s.boxRows) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (boxCol in 0..s.boxCols) {
                            DotView(dotSize, palette)
                            if (boxCol < s.boxCols) {
                                val drawn = s.horizontalEdges[boxRow * s.boxCols + boxCol]
                                EdgeView(
                                    drawn = drawn,
                                    horizontal = true,
                                    length = cellSize,
                                    thickness = edgeThickness,
                                    palette = palette,
                                    enabled = isMyTurn && !s.boardOver,
                                    onTap = {
                                        game.drawHorizontalEdge(boxRow, boxCol)
                                        sounds.playTap()
                                        haptics(HapticSignal.NORMAL_ACTION)
                                    }
                                )
                            }
                        }
                    }
                    if (boxRow < s.boxRows) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            for (boxCol in 0..s.boxCols) {
                                val drawnV = s.verticalEdges[boxRow * (s.boxCols + 1) + boxCol]
                                EdgeView(
                                    drawn = drawnV,
                                    horizontal = false,
                                    length = cellSize,
                                    thickness = edgeThickness,
                                    palette = palette,
                                    enabled = isMyTurn && !s.boardOver,
                                    onTap = {
                                        game.drawVerticalEdge(boxRow, boxCol)
                                        sounds.playTap()
                                        haptics(HapticSignal.NORMAL_ACTION)
                                    }
                                )
                                if (boxCol < s.boxCols) {
                                    val boxIndex = boxRow * s.boxCols + boxCol
                                    BoxCellView(owner = s.boxOwner[boxIndex], size = cellSize, palette = palette)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (s.boardOver) {
            Spacer(Modifier.height(16.dp))
            FinishedPanel(s = s, game = game, palette = palette)
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- shares its base tokens with
// MinesweeperPalette/SudokuPalette/LightsOutPalette (see this file's class
// KDoc for why this doesn't read MaterialTheme.colorScheme), extended with
// two distinct per-player accent colors this game needs for box ownership.
// ---------------------------------------------------------------------------
private data class DotsAndBoxesPalette(
    val background: Color,
    val dot: Color,
    val edgeHint: Color,
    val edgeDrawn: Color,
    val player0: Color,
    val player1: Color,
    val textPrimary: Color
)

@Composable
private fun dotsAndBoxesPalette(isDark: Boolean): DotsAndBoxesPalette = if (!isDark) {
    DotsAndBoxesPalette(
        background = Color(0xFFFBF1E6),
        dot = Color(0xFF3A2E22),
        edgeHint = Color(0xFFE3CBA9),
        edgeDrawn = Color(0xFF3A2E22),
        player0 = Color(0xFFE08D4B), // warm terracotta -- the shared "new games" accent
        player1 = Color(0xFF3E7A8C), // a cool contrasting teal-blue, deliberately outside the warm family for at-a-glance ownership contrast
        textPrimary = Color(0xFF3A2E22)
    )
} else {
    DotsAndBoxesPalette(
        background = Color(0xFF1C1712),
        dot = Color(0xFFF3E9DB),
        edgeHint = Color(0xFF453A2E),
        edgeDrawn = Color(0xFFF3E9DB),
        player0 = Color(0xFFE8985B),
        player1 = Color(0xFF6FB4C9),
        textPrimary = Color(0xFFF3E9DB)
    )
}

@Composable
private fun StatusRow(s: DotsAndBoxesState, palette: DotsAndBoxesPalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerScoreChip(
                name = s.players[0].displayName,
                score = s.scores[0],
                isTurn = s.currentPlayerIndex == 0 && !s.boardOver,
                color = palette.player0,
                palette = palette
            )
            PlayerScoreChip(
                name = s.players[1].displayName,
                score = s.scores[1],
                isTurn = s.currentPlayerIndex == 1 && !s.boardOver,
                color = palette.player1,
                palette = palette
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(s.lastAction, color = palette.textPrimary.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlayerScoreChip(name: String, score: Int, isTurn: Boolean, color: Color, palette: DotsAndBoxesPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isTurn) color.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(name, color = palette.textPrimary, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.width(6.dp))
        Text(score.toString(), color = palette.textPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DotView(size: Dp, palette: DotsAndBoxesPalette) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(palette.dot))
}

@Composable
private fun EdgeView(
    drawn: Boolean,
    horizontal: Boolean,
    length: Dp,
    thickness: Dp,
    palette: DotsAndBoxesPalette,
    enabled: Boolean,
    onTap: () -> Unit
) {
    // A wider invisible tap target (`thickness`) around a visually thinner
    // drawn/undrawn line -- the line itself is too thin to comfortably tap
    // directly on a phone.
    val lineThickness = 5.dp
    val targetModifier = if (horizontal) Modifier.width(length).height(thickness) else Modifier.width(thickness).height(length)
    val lineModifier = if (horizontal) Modifier.width(length).height(lineThickness) else Modifier.width(lineThickness).height(length)
    Box(
        modifier = targetModifier
            .then(if (enabled && !drawn) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = lineModifier
                .clip(RoundedCornerShape(2.dp))
                .background(if (drawn) palette.edgeDrawn else palette.edgeHint)
        )
    }
}

@Composable
private fun BoxCellView(owner: Int?, size: Dp, palette: DotsAndBoxesPalette) {
    val bg = when (owner) {
        0 -> palette.player0.copy(alpha = 0.35f)
        1 -> palette.player1.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
    )
}

@Composable
private fun FinishedPanel(s: DotsAndBoxesState, game: DotsAndBoxesGame, palette: DotsAndBoxesPalette) {
    val sessionWins by game.sessionWins
    val sessionDraws by game.sessionDraws
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val title = if (s.winnerPlayerId != null) {
                "${s.players.first { it.playerId == s.winnerPlayerId }.displayName} wins!"
            } else {
                "It's a tie!"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${s.players[0].displayName} ${s.scores[0]} — ${s.scores[1]} ${s.players[1].displayName}",
                style = MaterialTheme.typography.bodyMedium
            )
            val totalRounds = sessionWins.values.sum() + sessionDraws
            if (totalRounds > 1) {
                Spacer(Modifier.height(4.dp))
                val winsText = s.players.joinToString(" · ") { "${it.displayName} ${sessionWins[it.playerId] ?: 0}" }
                Text(
                    if (sessionDraws > 0) "Session: $winsText · Draws $sessionDraws" else "Session: $winsText",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPrimary.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { game.playAgain() }) { Text("Play Again") }
                OutlinedButton(onClick = { game.leaveSession() }) { Text("Back to Menu") }
            }
        }
    }
}
