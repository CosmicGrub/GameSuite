package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.breakout.BreakoutGame
import com.gamesuite.games.breakout.BreakoutStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel

/**
 * Renders BreakoutGame's state reactively — see [BreakoutGame]'s own class KDoc for the full
 * design reasoning (fixed 5x8 brick grid, difficulty scales paddle width/ball speed rather than
 * board size, no daily-challenge route). Real-time physics loop, same shape as
 * [AirHockeyScreen]'s own (a `withFrameNanos` loop stepping `game.tick(dt)` every frame) — the
 * whole point of reusing that infrastructure per `docs/NEW_GAMES_BRAINSTORM.md`'s own Breakout
 * entry — but deliberately without Air Hockey's own "premium 2026 vision" juice layer (camera
 * shake, particle bursts, ball trail): a simpler first build, matching the brainstorm doc's own
 * "lower risk" framing for this entry. Haptics/SFX per real event (brick broken, paddle bounce,
 * wall bounce, life lost, level cleared, run over) are still wired, same baseline every other
 * game in this app meets.
 *
 * Controls: touch down anywhere launches a resting ball AND starts controlling the paddle from
 * that x; dragging continues to move the paddle. One combined `awaitPointerEventScope` loop (not
 * `detectDragGestures`, which only fires after a real drag distance and would miss a plain
 * tap-to-launch) — same raw-loop idiom [AirHockeyScreen] already uses for its own paddle input.
 * Fresh-touch-down detection is tracked manually (a `wasPressed` flag captured in the loop's own
 * closure) rather than via `PointerInputChange.changedToDown()` — that flag was tried first and
 * confirmed, via on-device logging, to read false on a genuine fresh tap through this exact input
 * path; the manual comparison sidesteps whatever specific quirk caused that and was re-verified
 * live afterward.
 *
 * VISUAL IDENTITY: chrome shares this batch's warm tokens (see [breakoutPalette]); the ball,
 * paddle, and per-row brick colors are the one deliberate exception, same "authentic exception"
 * reasoning as Color Flood's cell colors / Edge Match's edge patterns / Connect Four's board
 * frame — this game's whole visual identity as arcade action needs a real, non-warm palette for
 * its actual play elements.
 */
@Composable
fun BreakoutScreen(
    sessionManager: GameSessionManager,
    game: BreakoutGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current
    rememberAmbientMusic(profile = MusicProfiles.BREAKOUT, enabled = musicEnabled)
    val statsStore = remember { BreakoutStatsStore(androidContext) }
    val state by game.state
    val matchOver by game.matchOver
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = breakoutPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

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

    if (matchOver) return

    val bestScores by statsStore.bestScores.collectAsState(initial = emptyMap())
    val bestScore = bestScores[game.difficulty.name]
    var reportedNewBest by remember(game.difficulty) { mutableStateOf<Boolean?>(null) }

    // The physics frame loop -- same shape as AirHockeyScreen's own; runs continuously while
    // this screen is composed and the session isn't over.
    LaunchedEffect(matchOver) {
        if (matchOver) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (!game.matchOver.value) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    val dt = (nanos - lastFrameNanos) / 1_000_000_000f
                    game.tick(dt)
                }
                lastFrameNanos = nanos
            }
        }
    }

    LaunchedEffect(state.lastBrickBroken?.seq) {
        if (state.lastBrickBroken == null) return@LaunchedEffect
        haptics(HapticSignal.LIGHT_TICK)
        playSfx(SfxKind.SOLID_THUNK)
    }
    LaunchedEffect(state.lastPaddleBounce?.seq) {
        if (state.lastPaddleBounce == null) return@LaunchedEffect
        haptics(HapticSignal.LIGHT_TICK)
        playSfx(SfxKind.LIGHT_TICK)
    }
    LaunchedEffect(state.lastWallBounce?.seq) {
        if (state.lastWallBounce == null) return@LaunchedEffect
        playSfx(SfxKind.WHOOSH)
    }
    LaunchedEffect(state.lastLevelCleared?.seq) {
        if (state.lastLevelCleared == null) return@LaunchedEffect
        haptics(HapticSignal.SUCCESS)
        playSfx(SfxKind.SUCCESS_CHIME)
    }
    LaunchedEffect(state.lastLifeLost?.seq) {
        val event = state.lastLifeLost ?: return@LaunchedEffect
        if (!event.gameOver) {
            haptics(HapticSignal.FAILURE)
            playSfx(SfxKind.INVALID_BUZZ)
        }
    }
    LaunchedEffect(state.gameOver) {
        if (state.gameOver && reportedNewBest == null) {
            val isNewBest = statsStore.recordScore(game.difficulty, state.score)
            reportedNewBest = isNewBest
            if (isNewBest) {
                haptics(HapticSignal.CELEBRATION)
                playSfx(SfxKind.SUCCESS_CHIME)
            } else {
                haptics(HapticSignal.FAILURE)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DifficultyTabsBreakout(
            current = game.difficulty,
            palette = palette,
            onSelect = { tier ->
                if (tier != game.difficulty) {
                    game.difficulty = tier
                    reportedNewBest = null
                    game.startMatch()
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        BreakoutStatusRow(
            score = state.score,
            lives = state.lives,
            level = state.level,
            palette = palette
        )

        Spacer(Modifier.height(10.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val boardSizeDp = minOf(maxWidth, maxHeight * 0.7f)
            Canvas(
                modifier = Modifier
                    .width(boardSizeDp)
                    .height(boardSizeDp * 1.35f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.boardBackground)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            // Tracked manually rather than via PointerInputChange.changedToDown() --
                            // that flag proved unreliable against this exact input path (confirmed
                            // false on a genuine fresh tap during on-device testing, logged and
                            // verified before switching to this approach), so a fresh "was nothing
                            // pressed last iteration, is something pressed now" comparison captured
                            // in this closure is what actually detects a new touch-down reliably.
                            var wasPressed = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change == null) {
                                    wasPressed = false
                                    continue
                                }
                                change.consume()
                                if (!wasPressed) game.launchBall()
                                wasPressed = true
                                val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                game.movePaddle(nx)
                            }
                        }
                    }
            ) {
                val w = this.size.width
                val h = this.size.height

                // Bricks.
                for (i in state.bricks.indices) {
                    if (!state.bricks[i]) continue
                    val row = i / state.cols
                    val col = i % state.cols
                    val brickWidth = (1f - BRICK_GAP * (state.cols + 1)) / state.cols
                    val brickHeight = (BRICK_AREA_HEIGHT - BRICK_GAP * (state.rows + 1)) / state.rows
                    val left = BRICK_GAP + col * (brickWidth + BRICK_GAP)
                    val top = BRICK_TOP_Y + BRICK_GAP + row * (brickHeight + BRICK_GAP)
                    drawRoundRect(
                        color = palette.brickColors[row % palette.brickColors.size],
                        topLeft = Offset(left * w, top * h),
                        size = Size(brickWidth * w, brickHeight * h),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                }

                // Paddle.
                val paddleRect = Rect(
                    (state.paddleX - state.paddleHalfWidth) * w, (BreakoutGame.PADDLE_Y - BreakoutGame.PADDLE_HALF_HEIGHT) * h,
                    (state.paddleX + state.paddleHalfWidth) * w, (BreakoutGame.PADDLE_Y + BreakoutGame.PADDLE_HALF_HEIGHT) * h
                )
                drawRoundRect(
                    color = palette.paddleColor,
                    topLeft = paddleRect.topLeft,
                    size = paddleRect.size,
                    cornerRadius = CornerRadius(6f, 6f)
                )

                // Ball.
                drawCircle(
                    color = palette.ballColor,
                    radius = BreakoutGame.BALL_RADIUS * minOf(w, h),
                    center = Offset(state.ballPos.x * w, state.ballPos.y * h)
                )
            }

            if (!state.ballLaunched && !state.gameOver) {
                Text(
                    "Tap to launch",
                    color = palette.textPrimary.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                )
            }
        }

        if (state.gameOver) {
            Spacer(Modifier.height(16.dp))
            BreakoutFinishedPanel(
                score = state.score,
                bestScore = bestScore,
                isNewBest = reportedNewBest ?: false,
                onPlayAgain = { game.playAgain() },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

private const val BRICK_TOP_Y = BreakoutGame.BRICK_TOP_Y
private const val BRICK_AREA_HEIGHT = BreakoutGame.BRICK_AREA_HEIGHT
private const val BRICK_GAP = BreakoutGame.BRICK_GAP

@Composable
private fun BreakoutStatusRow(score: Int, lives: Int, level: Int, palette: BreakoutPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Text("Score: $score", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text("Lives: ${"❤".repeat(lives.coerceAtLeast(0))}", color = palette.textPrimary)
        Text("Level $level", color = palette.textPrimary)
    }
}

@Composable
private fun BreakoutFinishedPanel(
    score: Int,
    bestScore: Int?,
    isNewBest: Boolean,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: BreakoutPalette
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Game Over", style = MaterialTheme.typography.headlineSmall, color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Score: $score", color = palette.textPrimary)
        if (isNewBest) {
            Text("New best!", color = palette.accent, fontWeight = FontWeight.Bold)
        } else if (bestScore != null) {
            Text("Best: $bestScore", color = palette.textPrimary.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlayAgain) { Text("Play Again") }
            OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
        }
    }
}

@Composable
private fun DifficultyTabsBreakout(current: CpuDifficulty, palette: BreakoutPalette, onSelect: (CpuDifficulty) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tier in CpuDifficulty.entries) {
            val selected = tier == current
            val label = when (tier) {
                CpuDifficulty.EASY -> "Easy"
                CpuDifficulty.MEDIUM -> "Medium"
                CpuDifficulty.HARD -> "Hard"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) palette.accent else palette.chipBackground)
                    .clickable { onSelect(tier) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) palette.textOnAccent else palette.textPrimary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this batch -- see this
// file's own KDoc for why ball/paddle/brick colors are the one deliberate exception.
// ---------------------------------------------------------------------------
private data class BreakoutPalette(
    val background: Color,
    val boardBackground: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val ballColor: Color,
    val paddleColor: Color,
    /** index 0 = top row (worth the most) .. last = bottom row -- classic Arkanoid rainbow. */
    val brickColors: List<Color>
)

@Composable
private fun breakoutPalette(isDark: Boolean): BreakoutPalette = if (!isDark) {
    BreakoutPalette(
        background = Color(0xFFFBF1E6),
        boardBackground = Color(0xFF241D33),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9),
        ballColor = Color(0xFFF5F0E6),
        paddleColor = Color(0xFF5B9A5B),
        brickColors = listOf(
            Color(0xFFD9573F), // top row -- terracotta red
            Color(0xFFE0A83E), // golden yellow
            Color(0xFF5B9A5B), // leaf green
            Color(0xFF3E7A9E), // ocean blue
            Color(0xFF8A5FA0)  // bottom row -- plum purple
        )
    )
} else {
    BreakoutPalette(
        background = Color(0xFF1C1712),
        boardBackground = Color(0xFF120E1C),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        ballColor = Color(0xFFF5F0E6),
        paddleColor = Color(0xFF6FB36F),
        brickColors = listOf(
            Color(0xFFE06B52),
            Color(0xFFE8B95A),
            Color(0xFF6FB36F),
            Color(0xFF4E8FBA),
            Color(0xFF9C72B5)
        )
    )
}
