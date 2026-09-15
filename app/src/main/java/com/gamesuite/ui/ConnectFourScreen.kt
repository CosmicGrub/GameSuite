package com.gamesuite.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.connectfour.ConnectFourGame
import com.gamesuite.games.connectfour.ConnectFourState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.ui.effects.rememberParticleBurst
import kotlinx.coroutines.delay

/** The board Row's own inset from its frame background -- pulled out as a named constant
 *  (rather than a bare `4.dp` used in two places) so the Row's real padding and the win-burst
 *  particle math inside [ConnectFourScreen] that converts cell row/col into a pixel origin can
 *  never silently drift apart from each other. */
private val BOARD_FRAME_PADDING = 4.dp

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
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CONNECT_FOUR, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = connectFourPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    // JUICE: the shared com.gamesuite.ui.effects.CameraShake/ParticleBurst utilities (see those
    // files' own KDoc) -- reserved for this game's single biggest moment, a genuine four-in-a-row
    // win (never a draw; see the LaunchedEffect inside the board's BoxWithConstraints below),
    // same "biggest moment gets the biggest combo" rule this batch's other juice passes follow.
    // density/shakeMagnitudePx are resolved once up here (a @Composable CompositionLocal read)
    // so that LaunchedEffect -- a plain suspend lambda, which can't call LocalDensity.current
    // itself -- can simply close over the already-resolved pixel value, the same pattern
    // TowerDefenceScreen's own shakeMagnitudePx already uses.
    val cameraShake = rememberCameraShake()
    val particleBurst = rememberParticleBurst()
    val density = LocalDensity.current
    val shakeMagnitudePx = with(density) { 14.dp.toPx() }

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
            // Pixel-space equivalents of the exact geometry DiscSlotView/the Row below already
            // use (a BOARD_FRAME_PADDING inset, then cellSize per cell) -- reused as-is for the
            // win-burst particle origins below, rather than a second, potentially-diverging
            // layout computation.
            val cellSizePx = with(density) { cellSize.toPx() }
            val framePaddingPx = with(density) { BOARD_FRAME_PADDING.toPx() }

            // Win celebration -- fires exactly once per genuine win (never a draw, where
            // winningLine stays null the whole time). Keyed on s.boardOver, the SAME one-shot
            // idiom the haptics LaunchedEffect above already relies on: boardOver only ever
            // flips false -> true once per board, reset back to false by startMatch()/
            // playAgain() before it can fire again. Kept as its own effect (rather than folded
            // into that one) purely because it needs this BoxWithConstraints' own cellSize/
            // density math, which isn't in scope up where that first effect lives.
            LaunchedEffect(s.boardOver) {
                val winningLine = s.winningLine
                if (!s.boardOver || s.winnerPlayerId == null || winningLine == null) return@LaunchedEffect
                val winnerIndex = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                val discColor = if (winnerIndex == 1) palette.player1Disc else palette.player0Disc
                // A small burst from EVERY cell in the winning line (not just its midpoint) --
                // the board grid's own row/col math makes each cell's exact pixel center cheap
                // to compute, so there's no need to fall back to a single-point approximation.
                for (index in winningLine) {
                    val row = index / s.cols
                    val col = index % s.cols
                    val center = Offset(
                        framePaddingPx + col * cellSizePx + cellSizePx / 2f,
                        framePaddingPx + row * cellSizePx + cellSizePx / 2f
                    )
                    particleBurst.spawn(
                        origin = center,
                        count = 10,
                        colors = listOf(discColor),
                        speedRange = 0.5f..1.1f,
                        lifeRangeSeconds = 0.5f..0.8f,
                        gravity = 1.2f
                    )
                }
                playSfx(SfxKind.SUCCESS_CHIME)
                cameraShake.trigger(durationMs = 400, easing = FastOutSlowInEasing)
            }

            // Both the disc grid AND the win-burst overlay below live inside this shaking Box --
            // adversarial review caught an earlier version that applied .cameraShake only to the
            // Row, leaving the particle Canvas as an unshaken sibling (a graphicsLayer translation
            // never propagates to a sibling composable), so the board would visibly jolt while the
            // burst hung in place relative to it. Matches ColorFloodScreen/DotsAndBoxesScreen's own
            // "shake the whole board, particles included, as one physical unit" pattern.
            Box(modifier = Modifier.cameraShake(cameraShake, magnitudePx = shakeMagnitudePx)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.boardFrame)
                        .padding(BOARD_FRAME_PADDING)
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

                // Win-burst motes, drawn last so they sit on top of the board/discs -- the same
                // overlay pattern TowerDefenceScreen's own particleBurst draw loop uses, applied
                // here via a plain matchParentSize() Canvas since this board has no single Canvas
                // of its own to draw into directly (see this file's own module-level guidance).
                Canvas(modifier = Modifier.matchParentSize()) {
                    for (particle in particleBurst.particles.value) {
                        drawCircle(
                            color = particle.color.copy(alpha = particle.lifeFraction),
                            radius = cellSizePx * 0.12f * particle.lifeFraction.coerceAtLeast(0.35f),
                            center = particle.pos
                        )
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
