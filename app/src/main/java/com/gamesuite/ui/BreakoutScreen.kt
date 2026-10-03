package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlin.math.roundToInt

/**
 * Renders BreakoutGame's state reactively — see [BreakoutGame]'s own class KDoc for the full
 * design reasoning (fixed 5x8 brick grid, difficulty scales paddle width/ball speed rather than
 * board size, no daily-challenge route). Real-time physics loop, same shape as
 * [AirHockeyScreen]'s own (a `withFrameNanos` loop stepping `game.tick(dt)` every frame), but
 * deliberately without Air Hockey's own "premium 2026 vision" juice layer (camera shake, particle
 * bursts, ball trail). Haptics/SFX per real event (brick broken, paddle bounce, wall bounce, life
 * lost, level cleared, run over) are wired.
 *
 * Controls: touch down anywhere launches a resting ball AND starts controlling the paddle from
 * that x; dragging continues to move the paddle. One combined `awaitPointerEventScope` loop (not
 * `detectDragGestures`, which only fires after a real drag distance and would miss a plain
 * tap-to-launch). Fresh-touch-down detection is tracked manually (a `wasPressed` flag) rather
 * than via `PointerInputChange.changedToDown()`, which read false on a genuine fresh tap through
 * this exact input path (confirmed by on-device logging). The paddle follows the finger across
 * the whole board width, so it stays usable on a 312dp-wide window (the Fold cover screen).
 * Screen readers cannot drive a raw drag, so the board also exposes "Launch ball" and "Move
 * paddle left/right" custom actions and a state description (score, lives, level).
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play / Back to Menu) plus its BackHandler.
 * Leaving mid-run asks first. The unit of "finished" here is a RUN (lives reached 0), never a
 * cleared level: levels just add to the same run's score. If an earlier run this session already
 * ended ([BreakoutGame.runsFinished] > 0), leaving goes through leaveSession() so that run's
 * score is still reported; otherwise it is a pure abort (never a win or loss). A run still in
 * progress never counts either way (leaveSession ignores it, and the personal best is only
 * recorded at game over). A finished run leaves through leaveSession() with no prompt. The top
 * row reserves [GameChromeEndInset] so the corner button never sits on it.
 *
 * PAUSE: [BreakoutGame.pause] freezes a ball in flight; the engine's resume() deliberately does
 * not unfreeze it, so a "Paused / Tap to resume" scrim is shown and the player resumes with
 * [BreakoutGame.resumePlay]. Pause is triggered by the host Activity (onPause), by the window
 * losing focus (any dialog, the corner menu's popup, the notification shade: `isWindowFocused`),
 * and explicitly when the corner menu opens (via GameChrome's extraItems hook, composed only
 * while the menu is open). The frame loop runs only while a ball is in flight, the engine is not
 * paused and the lifecycle is RESUMED, and stops with the composition.
 *
 * SIZING: [fitBoard] against the measured space of the board's own slot at the engine's
 * [BreakoutGame.BOARD_ASPECT] (20 x 27 units == 1.35), capped at [MAX_BOARD_WIDTH_DP] wide so it
 * does not sprawl on a tablet. The board never exceeds its slot. Because it is a real-time field
 * it cannot scroll or pan; on a short window (landscape phone) it simply gets narrower, and the
 * paddle still maps the touch x across whatever width it has.
 *
 * DIFFICULTY is picked before the first launch (or after a game over); during a run the row shows
 * a read-only label, so one stray tap can no longer silently wipe the score, lives and level.
 * The result panel is an overlay on the board (it used to stack below and resize the board
 * mid-finale) and fades in over [RESULT_FADE_MS] unless reduced motion is on. The re-racked ball
 * fades in after a lost life or a cleared level (the old "teleport"), also off under reduced motion.
 *
 * ACCESSIBILITY / COLOR: hearts are drawn (filled = a life left, outline = spent) with one
 * "Lives: n of 3" description; the paddle has its own pale colour (it used to share the leaf-green
 * brick row's literal); with [LocalColorblindMode] bricks gain stripes, one more per row going
 * down, so the row (and its point value) is never carried by hue alone. Text drawn on the
 * always-dark board uses fixed light colours, never theme tokens (the old "Tap to launch" was
 * 1.1:1 in the light theme).
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
    val matchOver by game.matchOver
    val paused by game.paused
    val finishedRuns by game.runsFinished
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = breakoutPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycleOwner = LocalLifecycleOwner.current

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

    // The engine state changes every frame while the ball is in flight. Reading it directly here
    // would recompose this whole screen 60 times a second, so composition only sees this small
    // snapshot (which changes on events, not on frames) and the Canvas below reads the live state
    // inside its own draw lambda.
    val hud by remember(game) { derivedStateOf { game.state.value.toHud() } }
    val simulating by remember(game) {
        derivedStateOf { game.state.value.ballLaunched && !game.state.value.gameOver }
    }

    val bestScores by statsStore.bestScores.collectAsState(initial = emptyMap())
    val bestScore = bestScores[game.difficulty.name]
    // Keyed on runSeq (bumped by every BreakoutGame.startMatch -- see that field's KDoc), the same
    // "incrementing counter, unique per run" shape TowerDefenceScreen uses for its own
    // reportedForRunSeq. game.difficulty alone doesn't change between two runs of the same
    // difficulty, which let a finished run's own "new best score?" result silently keep showing
    // on every later run.
    var reportedNewBest by remember(hud.runSeq) { mutableStateOf<Boolean?>(null) }

    // Anything that takes window focus away (the corner menu's popup, the leave/help dialogs, the
    // notification shade, split-screen focus moving) freezes a ball in flight. The engine ignores
    // the call unless a ball is actually in flight, and never un-freezes on its own: the player
    // taps the "Paused" scrim.
    LaunchedEffect(windowFocused) {
        if (!windowFocused) game.pause()
    }

    // The physics frame loop. Runs only while a ball is in flight, the engine is not paused and
    // the lifecycle is RESUMED (so it stops when the app is backgrounded), and is cancelled when
    // the screen leaves composition. `lastFrameNanos` restarts from zero every time the loop
    // (re)starts, so a pause can never feed one huge dt into the ball.
    LaunchedEffect(lifecycleOwner, simulating, paused) {
        if (!simulating || paused) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastFrameNanos = 0L
            while (!game.matchOver.value && !game.paused.value) {
                withFrameNanos { nanos ->
                    if (lastFrameNanos != 0L) {
                        game.tick((nanos - lastFrameNanos) / 1_000_000_000f)
                    }
                    lastFrameNanos = nanos
                }
            }
        }
    }

    LaunchedEffect(hud.brickSeq) {
        if (hud.brickSeq == null) return@LaunchedEffect
        haptics(HapticSignal.LIGHT_TICK)
        playSfx(SfxKind.SOLID_THUNK)
    }
    LaunchedEffect(hud.paddleSeq) {
        if (hud.paddleSeq == null) return@LaunchedEffect
        haptics(HapticSignal.LIGHT_TICK)
        playSfx(SfxKind.LIGHT_TICK)
    }
    LaunchedEffect(hud.wallSeq) {
        if (hud.wallSeq == null) return@LaunchedEffect
        playSfx(SfxKind.WHOOSH)
    }
    LaunchedEffect(hud.levelSeq) {
        if (hud.levelSeq == null) return@LaunchedEffect
        haptics(HapticSignal.SUCCESS)
        playSfx(SfxKind.SUCCESS_CHIME)
    }
    LaunchedEffect(hud.lifeSeq) {
        if (hud.lifeSeq == null) return@LaunchedEffect
        if (!hud.lastLifeWasFinal) {
            haptics(HapticSignal.FAILURE)
            playSfx(SfxKind.INVALID_BUZZ)
        }
    }
    LaunchedEffect(hud.gameOver, hud.runSeq) {
        if (hud.gameOver && reportedNewBest == null) {
            val isNewBest = statsStore.recordScore(game.difficulty, hud.score)
            reportedNewBest = isNewBest
            // Solo game: a record is always the human's, so the celebration is unconditional.
            if (isNewBest) {
                haptics(HapticSignal.CELEBRATION)
                playSfx(SfxKind.SUCCESS_CHIME)
            } else {
                haptics(HapticSignal.FAILURE)
            }
        }
    }

    // The re-racked ball fades in after a lost life / cleared level instead of teleporting onto
    // the paddle. Always snaps back to opaque when there is nothing to fade (new run, reduced motion).
    val ballFade = remember { Animatable(1f) }
    LaunchedEffect(hud.lifeSeq, hud.levelSeq, reducedMotion) {
        if (reducedMotion || (hud.lifeSeq == null && hud.levelSeq == null)) {
            ballFade.snapTo(1f)
            return@LaunchedEffect
        }
        ballFade.snapTo(0f)
        ballFade.animateTo(1f, animationSpec = tween(durationMillis = BALL_FADE_MS))
    }

    // Picking a tier restarts the run, so it is only offered when nothing can be lost: before the
    // first launch of a fresh run, or after a game over. Mid-run it is a read-only label.
    val canChangeDifficulty = hud.gameOver ||
        (!hud.ballLaunched && hud.score == 0 && hud.lives == BreakoutGame.STARTING_LIVES && hud.level == 1)

    // Back / abort-confirm / How to Play live in the shared GameChrome. A run still in progress
    // is the unfinished unit: if an earlier run this session already ended, leaving goes through
    // leaveSession() (the same call the game-over panel's "Back to Menu" makes) so that run's
    // score still counts; with none finished it is a pure abort (never a win or loss).
    GameChrome(
        helpTitle = "How to Play Breakout",
        helpText = HELP_TEXT,
        matchInProgress = !hud.gameOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRuns > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this run?",
        leaveBody = if (finishedRuns > 0) {
            "This run is still in progress and won't count, but the runs you've already finished stay on your record."
        } else {
            "This run is still in progress. Leaving now won't count it as a win or a loss."
        },
        // Composed only while the corner menu is open, so opening it freezes a live ball. The
        // dialogs the menu leads to are covered by the window-focus effect above.
        extraItems = { _ -> LaunchedEffect(Unit) { game.pause() } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top-right occupant of this layout: keeps the corner button's width free at its end.
            // A fixed 48dp slot, so the board below never jumps when the picker becomes a label.
            Box(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(end = GameChromeEndInset),
                contentAlignment = Alignment.Center
            ) {
                if (canChangeDifficulty) {
                    DifficultyPicker(
                        current = game.difficulty,
                        palette = palette,
                        onSelect = { tier ->
                            if (tier != game.difficulty && !game.matchOver.value) {
                                game.difficulty = tier
                                reportedNewBest = null
                                game.startMatch()
                            }
                        }
                    )
                } else {
                    Text(
                        "Difficulty: ${difficultyLabel(game.difficulty)}",
                        color = palette.textPrimary.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            BreakoutStatusRow(
                score = hud.score,
                lives = hud.lives,
                level = hud.level,
                palette = palette,
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
                // fitBoard never returns a footprint larger than this BoxWithConstraints's own
                // measured space -- see its KDoc. 20 x 27 units is exactly BOARD_ASPECT.
                val fit = remember(maxWidth, maxHeight) {
                    fitBoard(
                        availableWidthPx = maxWidth.value,
                        availableHeightPx = maxHeight.value,
                        columns = BOARD_UNITS_WIDE,
                        rows = BOARD_UNITS_TALL,
                        maxCellPx = MAX_BOARD_WIDTH_DP / BOARD_UNITS_WIDE
                    )
                }
                val boardWidth = fit.boardWidthPx.dp
                val boardHeight = fit.boardHeightPx.dp

                val fieldState = buildString {
                    append("Score ${hud.score}, ${hud.lives} lives left, level ${hud.level}")
                    when {
                        hud.gameOver -> append(", game over")
                        paused -> append(", paused")
                        !hud.ballLaunched -> append(", ball ready to launch")
                    }
                }

                Box(
                    modifier = Modifier
                        .size(width = boardWidth, height = boardHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.boardBackground)
                        .semantics {
                            contentDescription = "Breakout play field"
                            stateDescription = fieldState
                            // Dragging is the real control; these are the screen-reader route to it.
                            customActions = listOf(
                                CustomAccessibilityAction("Launch ball") { game.launchBall(); true },
                                CustomAccessibilityAction("Move paddle left") {
                                    game.movePaddle(game.state.value.paddleX - PADDLE_NUDGE); true
                                },
                                CustomAccessibilityAction("Move paddle right") {
                                    game.movePaddle(game.state.value.paddleX + PADDLE_NUDGE); true
                                }
                            )
                        }
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    // Tracked manually rather than via PointerInputChange.changedToDown()
                                    // -- that flag proved unreliable against this exact input path
                                    // (confirmed false on a genuine fresh tap during on-device testing,
                                    // logged and verified before switching to this approach), so a fresh
                                    // "was nothing pressed last iteration, is something pressed now"
                                    // comparison captured in this closure is what actually detects a new
                                    // touch-down reliably.
                                    var wasPressed = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.pressed }
                                        if (change == null) {
                                            wasPressed = false
                                            continue
                                        }
                                        change.consume()
                                        if (size.width > 0) {
                                            if (!wasPressed) game.launchBall()
                                            val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                            game.movePaddle(nx)
                                        }
                                        wasPressed = true
                                    }
                                }
                            }
                    ) {
                        // Read here, in the draw phase, so a frame redraws the Canvas only.
                        val s = game.state.value
                        val w = size.width
                        val h = size.height

                        // Bricks.
                        val brickWidth = (1f - BRICK_GAP * (s.cols + 1)) / s.cols
                        val brickHeight = (BRICK_AREA_HEIGHT - BRICK_GAP * (s.rows + 1)) / s.rows
                        val brickCorner = CornerRadius(brickHeight * h * 0.18f)
                        val stripeWidth = 1.5.dp.toPx()
                        for (i in s.bricks.indices) {
                            if (!s.bricks[i]) continue
                            val row = i / s.cols
                            val col = i % s.cols
                            val left = (BRICK_GAP + col * (brickWidth + BRICK_GAP)) * w
                            val top = (BRICK_TOP_Y + BRICK_GAP + row * (brickHeight + BRICK_GAP)) * h
                            val bw = brickWidth * w
                            val bh = brickHeight * h
                            drawRoundRect(
                                color = palette.brickColors[row % palette.brickColors.size],
                                topLeft = Offset(left, top),
                                size = Size(bw, bh),
                                cornerRadius = brickCorner
                            )
                            if (colorblind) {
                                // Row r (0 = top, worth the most) carries r stripes, so the row is
                                // readable without its hue.
                                for (k in 1..row) {
                                    val y = top + bh * k / (row + 1)
                                    drawLine(
                                        color = BRICK_STRIPE,
                                        start = Offset(left + bw * 0.12f, y),
                                        end = Offset(left + bw * 0.88f, y),
                                        strokeWidth = stripeWidth
                                    )
                                }
                            }
                        }

                        // Paddle.
                        val paddleHeight = BreakoutGame.PADDLE_HALF_HEIGHT * 2f * h
                        drawRoundRect(
                            color = palette.paddleColor,
                            topLeft = Offset(
                                (s.paddleX - s.paddleHalfWidth) * w,
                                (BreakoutGame.PADDLE_Y - BreakoutGame.PADDLE_HALF_HEIGHT) * h
                            ),
                            size = Size(s.paddleHalfWidth * 2f * w, paddleHeight),
                            cornerRadius = CornerRadius(paddleHeight / 2f)
                        )

                        // Ball: a circle of BALL_RADIUS * width. The board is exactly BOARD_ASPECT
                        // times taller than wide and the engine's y radius is BALL_RADIUS_Y, so the
                        // drawn circle and the collision shape are the same size.
                        drawCircle(
                            color = palette.ballColor.copy(alpha = ballFade.value),
                            radius = BreakoutGame.BALL_RADIUS * w,
                            center = Offset(s.ballPos.x * w, s.ballPos.y * h)
                        )
                    }

                    if (!hud.ballLaunched && !hud.gameOver && !paused) {
                        LaunchHint(
                            text = if (hud.level > 1) "Level ${hud.level} · Tap to launch" else "Tap to launch",
                            // In the empty band between the bricks and the paddle: clear of both
                            // and of the resting ball.
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = boardHeight * 0.26f)
                        )
                    }

                    if (paused && !hud.gameOver) {
                        BreakoutPausedScrim(
                            onResume = game::resumePlay,
                            modifier = Modifier.matchParentSize()
                        )
                    }

                    if (hud.gameOver) {
                        BreakoutResultOverlay(
                            score = hud.score,
                            bestScore = bestScore,
                            isNewBest = reportedNewBest ?: false,
                            reducedMotion = reducedMotion,
                            onPlayAgain = { game.playAgain() },
                            onBackToMenu = { game.leaveSession() },
                            palette = palette,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            }
        }
    }
}

private const val BRICK_TOP_Y = BreakoutGame.BRICK_TOP_Y
private const val BRICK_AREA_HEIGHT = BreakoutGame.BRICK_AREA_HEIGHT
private const val BRICK_GAP = BreakoutGame.BRICK_GAP

/** The board is [BOARD_UNITS_WIDE] x [BOARD_UNITS_TALL] units so [fitBoard]'s cell grid has the
 *  engine's exact aspect (20 x 27 == 1.35). */
private const val BOARD_UNITS_WIDE = 20
private val BOARD_UNITS_TALL = (BOARD_UNITS_WIDE * BreakoutGame.BOARD_ASPECT).roundToInt()

/** Widest the play field gets, so it does not sprawl on a tablet (height follows at 1.35x). */
private const val MAX_BOARD_WIDTH_DP = 520f

/** The HUD rows stop growing here and stay centered over the board on a wide window. */
private val CONTENT_MAX_WIDTH = 560.dp

/** How far one "Move paddle" screen-reader action slides the paddle, as a fraction of the width. */
private const val PADDLE_NUDGE = 0.12f

private const val RESULT_FADE_MS = 250
private const val BALL_FADE_MS = 300

// Text and overlays that sit on the board are drawn over an always-dark surface, so they use fixed
// light colours rather than theme tokens (a theme "textPrimary" is dark brown in the light theme).
private val OnBoardText = Color(0xFFF5F0E6)
private val OnBoardScrim = Color(0xCC120E1C)
private val BRICK_STRIPE = Color(0xB3120E1C)

private const val HELP_TEXT =
    "Drag anywhere on the board to slide the paddle. Touching the board also launches the ball " +
        "while it is resting on the paddle.\n\n" +
        "Bounce the ball into the bricks to clear them. Higher rows score more, from 10 points for " +
        "the bottom row up to 50 for the top. Where the ball lands on the paddle steers it left or " +
        "right.\n\n" +
        "Let the ball fall past the paddle and you lose a life; with all 3 gone the run is over. " +
        "Clear every brick to start the next level, a little faster each time.\n\n" +
        "Easy, Medium and Hard change the paddle width and ball speed, and can be picked before you " +
        "launch the first ball or after a game over."

/**
 * The few engine fields the composition needs. [BreakoutGame.state] changes on every frame while
 * the ball is in flight; this changes only on real events (a brick, a bounce, a life, a level,
 * the run ending), so reading it recomposes the screen on events instead of 60 times a second.
 */
private data class BreakoutHud(
    val score: Int,
    val lives: Int,
    val level: Int,
    val gameOver: Boolean,
    val ballLaunched: Boolean,
    val runSeq: Int,
    val brickSeq: Long?,
    val paddleSeq: Long?,
    val wallSeq: Long?,
    val levelSeq: Long?,
    val lifeSeq: Long?,
    val lastLifeWasFinal: Boolean
)

private fun BreakoutGame.BreakoutState.toHud() = BreakoutHud(
    score = score,
    lives = lives,
    level = level,
    gameOver = gameOver,
    ballLaunched = ballLaunched,
    runSeq = runSeq,
    brickSeq = lastBrickBroken?.seq,
    paddleSeq = lastPaddleBounce?.seq,
    wallSeq = lastWallBounce?.seq,
    levelSeq = lastLevelCleared?.seq,
    lifeSeq = lastLifeLost?.seq,
    lastLifeWasFinal = lastLifeLost?.gameOver == true
)

private fun difficultyLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

@Composable
private fun BreakoutStatusRow(score: Int, lives: Int, level: Int, palette: BreakoutPalette, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Score: $score", color = palette.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
        LivesIndicator(lives = lives, palette = palette)
        Text("Level $level", color = palette.textPrimary, maxLines = 1)
    }
}

/** One heart per starting life: filled = still have it, outline = spent. One spoken description. */
@Composable
private fun LivesIndicator(lives: Int, palette: BreakoutPalette) {
    Row(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Lives: $lives of ${BreakoutGame.STARTING_LIVES}"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(BreakoutGame.STARTING_LIVES) { slot ->
            val filled = slot < lives
            Canvas(modifier = Modifier.size(18.dp)) {
                drawHeart(color = palette.heart, filled = filled)
            }
        }
    }
}

private fun DrawScope.drawHeart(color: Color, filled: Boolean) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.95f)
        cubicTo(w * -0.35f, h * 0.60f, w * 0.05f, h * -0.10f, w * 0.5f, h * 0.25f)
        cubicTo(w * 0.95f, h * -0.10f, w * 1.35f, h * 0.60f, w * 0.5f, h * 0.95f)
        close()
    }
    if (filled) {
        drawPath(path = path, color = color)
    } else {
        drawPath(path = path, color = color.copy(alpha = 0.7f), style = Stroke(width = 1.25.dp.toPx()))
    }
}

/** Three radio chips with 48dp-tall touch targets (36dp-ish pills inside them). */
@Composable
private fun DifficultyPicker(current: CpuDifficulty, palette: BreakoutPalette, onSelect: (CpuDifficulty) -> Unit) {
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (tier in CpuDifficulty.entries) {
            val selected = tier == current
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(tier) }),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) palette.accent else palette.chipBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        difficultyLabel(tier),
                        color = if (selected) palette.textOnAccent else palette.textPrimary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** "Tap to launch" pill. Fixed light-on-dark colours (it sits on the always-dark board). */
@Composable
private fun LaunchHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(OnBoardScrim)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            // Merged so the live region announces the text it contains.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = OnBoardText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/** The whole scrim is one big "Resume" button, so the resume target is as large as the board. */
@Composable
private fun BreakoutPausedScrim(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(OnBoardScrim)
            .clickable(onClickLabel = "Resume game", role = Role.Button, onClick = onResume)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Paused",
                color = OnBoardText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text("Tap to resume", color = OnBoardText.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * The game-over panel, laid over the board (not stacked below it, which used to resize the board
 * mid-finale). Fades in unless [reducedMotion]. Scrolls inside the board if a large font scale
 * makes it taller than the board; the buttons are stacked so it never needs more than the
 * board's width.
 */
@Composable
private fun BreakoutResultOverlay(
    score: Int,
    bestScore: Int?,
    isNewBest: Boolean,
    reducedMotion: Boolean,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: BreakoutPalette,
    modifier: Modifier = Modifier
) {
    val fade = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (fade.value < 1f) fade.animateTo(1f, animationSpec = tween(durationMillis = RESULT_FADE_MS))
    }
    Box(
        modifier = modifier
            .graphicsLayer { alpha = fade.value }
            .background(OnBoardScrim)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            // widthIn first, then fillMaxWidth: the reverse order forces the panel to the full
            // board width because widthIn cannot narrow constraints fillMaxWidth already pinned.
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Game Over",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text("Score: $score", color = palette.textPrimary, style = MaterialTheme.typography.titleMedium)
                if (isNewBest) {
                    Text("New best!", color = palette.accentText, fontWeight = FontWeight.Bold)
                } else if (bestScore != null) {
                    Text("Best: $bestScore", color = palette.textPrimary.copy(alpha = 0.75f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onPlayAgain,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.textOnAccent
                )
            ) { Text("Play Again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.4f))
            ) { Text("Back to Menu") }
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
    /** The accent as TEXT on [background] (the plain accent is only ~2.3:1 on the light cream). */
    val accentText: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val heart: Color,
    val ballColor: Color,
    /** Deliberately not any brick row's colour (it used to be the leaf-green row's literal). */
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
        accentText = Color(0xFF9A4E12),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFF2A1E12),
        chipBackground = Color(0xFFE3CBA9),
        heart = Color(0xFFC8452E),
        ballColor = Color(0xFFF5F0E6),
        paddleColor = Color(0xFFB8EFC2),
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
        accentText = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        heart = Color(0xFFE8644C),
        ballColor = Color(0xFFF5F0E6),
        paddleColor = Color(0xFFC2F0CB),
        brickColors = listOf(
            Color(0xFFE06B52),
            Color(0xFFE8B95A),
            Color(0xFF6FB36F),
            Color(0xFF4E8FBA),
            Color(0xFF9C72B5)
        )
    )
}
