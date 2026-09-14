package com.gamesuite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.gamesuite.games.connectfour.ConnectFourGame
import com.gamesuite.games.connectfour.ConnectFourState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders ConnectFourGame's state reactively — same overall shape as
 * DotsAndBoxesScreen (the other two-player game in this batch): no
 * daily-seed route (a solo-puzzle concept in this app), and the same
 * 800ms-delayed `LaunchedEffect` + `game.playBotTurn()` idiom so a bot's
 * move doesn't feel instantaneous, which simply never fires in
 * SINGLE_DEVICE_PASS_AND_PLAY mode since neither player is a bot there.
 *
 * A tap anywhere in a column (not just its lowest empty cell) drops into
 * that column — matching a real Connect Four cabinet, where you aim at a
 * column, not a specific slot.
 *
 * VISUAL IDENTITY: chrome (background/text/buttons) shares its warm tokens
 * with the rest of this batch (see [connectFourPalette]), but the BOARD
 * ITSELF — frame plus discs — is the one deliberate exception, same
 * reasoning ColorFloodScreen's own KDoc gives for ITS cell colors: Connect
 * Four's blue frame with red/yellow discs is one of the most instantly
 * recognizable visual signatures in board games, and reinventing it in the
 * warm palette would trade real recognizability for consistency this
 * particular game doesn't need.
 */
@Composable
fun ConnectFourScreen(
    sessionManager: GameSessionManager,
    game: ConnectFourGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CONNECT_FOUR, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = connectFourPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

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

    // Same idiom as DotsAndBoxesScreen/DominoesScreen's own bot-turn trigger -- a short
    // delay so a bot's move doesn't feel instantaneous. No recursion needed here (unlike
    // Dots and Boxes' "go again" rule) since a single playBotTurn() call always either
    // ends the board or passes the turn to the human.
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
        StatusRow(s, game, palette)

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val cellSize = remember(maxWidth, maxHeight, s.rows, s.cols) {
                minOf(maxWidth / s.cols, maxHeight / s.rows, 48.dp).coerceAtLeast(24.dp)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.boardFrame)
                    .padding(4.dp)
            ) {
                for (col in 0 until s.cols) {
                    val columnFull = s.cells[col] != null // row 0 = the top row -- filled means no room left
                    val enabled = isMyTurn && !s.boardOver && !columnFull
                    Column(
                        modifier = Modifier.clickable(enabled = enabled) {
                            game.dropDisc(col)
                            sounds.playTap()
                            haptics(HapticSignal.NORMAL_ACTION)
                        }
                    ) {
                        for (row in 0 until s.rows) {
                            val index = row * s.cols + col
                            DiscSlotView(
                                owner = s.cells[index],
                                highlighted = s.winningLine?.contains(index) == true,
                                size = cellSize,
                                palette = palette
                            )
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
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- see this file's own KDoc for why the board frame/discs are the
// one deliberate exception, staying the classic blue/red/yellow instead.
// ---------------------------------------------------------------------------
private data class ConnectFourPalette(
    val background: Color,
    val textPrimary: Color,
    val boardFrame: Color,
    val emptySlot: Color,
    val player0Disc: Color,
    val player1Disc: Color,
    val winningGlow: Color
)

@Composable
private fun connectFourPalette(isDark: Boolean): ConnectFourPalette = if (!isDark) {
    ConnectFourPalette(
        background = Color(0xFFFBF1E6),
        textPrimary = Color(0xFF3A2E22),
        boardFrame = Color(0xFF2E5FA3),
        emptySlot = Color(0xFFEFEAE1),
        player0Disc = Color(0xFFE23B3B), // classic red
        player1Disc = Color(0xFFF2C230), // classic yellow
        winningGlow = Color(0xFF3ED18C)
    )
} else {
    ConnectFourPalette(
        background = Color(0xFF1C1712),
        textPrimary = Color(0xFFF3E9DB),
        boardFrame = Color(0xFF3A6BB0),
        emptySlot = Color(0xFF241E17),
        player0Disc = Color(0xFFEB5757),
        player1Disc = Color(0xFFF5CE4E),
        winningGlow = Color(0xFF4CE0A0)
    )
}

@Composable
private fun StatusRow(s: ConnectFourState, game: ConnectFourGame, palette: ConnectFourPalette) {
    val sessionWins by game.sessionWins
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerChip(
                name = s.players[0].displayName,
                wins = sessionWins[s.players[0].playerId] ?: 0,
                isTurn = s.currentPlayerIndex == 0 && !s.boardOver,
                discColor = palette.player0Disc,
                palette = palette
            )
            PlayerChip(
                name = s.players[1].displayName,
                wins = sessionWins[s.players[1].playerId] ?: 0,
                isTurn = s.currentPlayerIndex == 1 && !s.boardOver,
                discColor = palette.player1Disc,
                palette = palette
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(s.lastAction, color = palette.textPrimary.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlayerChip(name: String, wins: Int, isTurn: Boolean, discColor: Color, palette: ConnectFourPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isTurn) discColor.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(discColor))
        Spacer(Modifier.width(6.dp))
        Text(name, color = palette.textPrimary, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.width(6.dp))
        Text(wins.toString(), color = palette.textPrimary.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DiscSlotView(owner: Int?, highlighted: Boolean, size: Dp, palette: ConnectFourPalette) {
    val discColor = when (owner) {
        0 -> palette.player0Disc
        1 -> palette.player1Disc
        else -> palette.emptySlot
    }
    Box(
        modifier = Modifier
            .size(size)
            .padding(3.dp)
            .clip(CircleShape)
            .background(discColor)
            .then(if (highlighted) Modifier.border(3.dp, palette.winningGlow, CircleShape) else Modifier)
    )
}

@Composable
private fun FinishedPanel(s: ConnectFourState, game: ConnectFourGame, palette: ConnectFourPalette) {
    val sessionWins by game.sessionWins
    val sessionDraws by game.sessionDraws
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val title = if (s.winnerPlayerId != null) {
                "${s.players.first { it.playerId == s.winnerPlayerId }.displayName} connects four!"
            } else {
                "It's a tie!"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
