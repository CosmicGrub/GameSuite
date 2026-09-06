package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.airhockey.AirHockeyGame
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.settings.SettingsViewModel

/**
 * Real-time physics loop: each frame (withFrameNanos) computes elapsed
 * seconds and steps AirHockeyGame.tick(dt) — actual velocity/collision
 * simulation, not a turn-based approximation. Player paddle is fully
 * drag-controlled (finger position maps directly to paddle position,
 * clamped to their half of the table) for real free-range motion, not
 * discrete taps.
 *
 * Research pass (README item 9g) added a real CPU difficulty ladder — see
 * AirHockeyGame's `cpuSpeedFor`/`chooseCpuTargetX` KDoc — read here from
 * Settings' "Default CPU difficulty" the same way Tic-Tac-Toe and Hangman
 * already do.
 *
 * Local pass-and-play pass added a second drag zone for the top half, live
 * at the same time as the bottom one — two fingers on the table
 * simultaneously, one per player. `detectDragGestures` only tracks a single
 * pointer's stream (and consumes it), so a second independent
 * `pointerInput { detectDragGestures { ... } }` on the same Canvas can't
 * reliably co-exist with it; both zones are instead driven from one
 * `awaitPointerEventScope` loop below that walks *every* pointer change in
 * each `PointerEvent` and routes it by which half of the canvas it's in —
 * Compose's documented way to handle multiple simultaneous pointers.
 */
@Composable
fun AirHockeyScreen(
    sessionManager: GameSessionManager,
    game: AirHockeyGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = LocalHapticFeedback.current
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    // Drives both the header/instruction copy below and which half-drags route to the top
    // paddle (see the Canvas's pointerInput) — see AirHockeyGame's topPaddleIsBot for the
    // matching game-logic side of this same mode check.
    val isPassAndPlay = context?.activeMode == PlayMode.SINGLE_DEVICE_PASS_AND_PLAY

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result -> sessionManager.endActiveGame(result) }
        game.startMatch()
    }

    var lastScoreTotal by remember { mutableStateOf(0) }
    LaunchedEffect(state.playerScore, state.cpuScore) {
        val total = state.playerScore + state.cpuScore
        if (total != lastScoreTotal) {
            sounds.playPlace()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            lastScoreTotal = total
        }
    }

    // The physics frame loop — runs continuously while this screen is composed and the match isn't over.
    LaunchedEffect(state.matchOver) {
        if (state.matchOver) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (!game.state.value.matchOver) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    val dt = (nanos - lastFrameNanos) / 1_000_000_000f
                    game.tick(dt)
                }
                lastFrameNanos = nanos
            }
        }
    }

    if (state.matchOver) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                if (isPassAndPlay) {
                    if (state.winnerIsPlayer) "Player 1 wins! ${state.playerScore}-${state.cpuScore}" else "Player 2 wins! ${state.cpuScore}-${state.playerScore}"
                } else if (state.winnerIsPlayer) "You win! ${state.playerScore}-${state.cpuScore}" else "CPU wins ${state.cpuScore}-${state.playerScore}",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onMatchEnded) { Text("Back to menu") }
        }
        return
    }

    // Primary-only (no secondary/hand content in this game) — on a Tab S9 or
    // unfolded Fold this caps + centers the table instead of stretching the
    // square Canvas's surrounding column across the full (very wide) screen.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize(),
        primary = {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    if (isPassAndPlay) {
                        "Player 1: ${state.playerScore} — ${state.cpuScore} : Player 2 (first to ${AirHockeyGame.WIN_SCORE})"
                    } else {
                        "You ${state.playerScore} — ${state.cpuScore} CPU (first to ${AirHockeyGame.WIN_SCORE})"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (!isPassAndPlay) {
                    Text(
                        "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Locked to a square aspect ratio: the physics in AirHockeyGame works in normalized 0f..1f
                // coordinates shared by both axes (a single PADDLE_RADIUS/BALL_RADIUS, Euclidean distance
                // for collisions), which only matches drawn circles when the canvas's width and height are
                // equal. Without this, an unequal w/h canvas turns the true (physics) hitbox into an
                // ellipse in pixel space while paddles/ball are still drawn as circles sized from height
                // alone — so the visible circle and the real collision boundary disagree.
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .aspectRatio(1f)
                        // See this file's top-level KDoc for why both drag zones share one
                        // awaitPointerEventScope loop rather than two separate pointerInput
                        // blocks: every active pointer is routed every event, so a bottom-half
                        // finger and a top-half finger both move their own paddle at once. Not
                        // in pass-and-play, every pointer still goes to the player paddle
                        // regardless of which half it's in, unchanged from the old
                        // detectDragGestures behavior this replaces.
                        .pointerInput(isPassAndPlay) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    for (change in event.changes) {
                                        if (!change.pressed) continue
                                        change.consume()
                                        val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                        val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                        if (isPassAndPlay && ny < 0.5f) {
                                            game.moveTopPaddle(nx, ny)
                                        } else {
                                            game.movePlayerPaddle(nx, ny)
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height

                    // Table
                    drawRect(color = Color(0xFF0D47A1), size = size)
                    drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 2f)
                    drawCircle(Color.White.copy(alpha = 0.4f), radius = h * 0.12f, center = Offset(w / 2, h / 2), style = Stroke(2f))

                    // Goal mouths
                    val goalHalfW = AirHockeyGame.GOAL_HALF_WIDTH * w
                    drawLine(Color.Yellow, Offset(w / 2 - goalHalfW, 4f), Offset(w / 2 + goalHalfW, 4f), strokeWidth = 6f)
                    drawLine(Color.Yellow, Offset(w / 2 - goalHalfW, h - 4f), Offset(w / 2 + goalHalfW, h - 4f), strokeWidth = 6f)

                    // Paddles
                    drawCircle(Color(0xFFEF5350), radius = AirHockeyGame.PADDLE_RADIUS * h, center = Offset(state.cpuPaddle.x * w, state.cpuPaddle.y * h))
                    drawCircle(Color(0xFF66BB6A), radius = AirHockeyGame.PADDLE_RADIUS * h, center = Offset(state.playerPaddle.x * w, state.playerPaddle.y * h))

                    // Ball
                    drawCircle(Color.White, radius = AirHockeyGame.BALL_RADIUS * h, center = Offset(state.ballPos.x * w, state.ballPos.y * h))
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (isPassAndPlay) {
                        "Player 1: drag the bottom half — Player 2: drag the top half"
                    } else {
                        "Drag in the bottom half to move your paddle"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    )
}
