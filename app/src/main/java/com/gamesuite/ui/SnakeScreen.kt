package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.snake.SnakeConfig
import com.gamesuite.games.snake.SnakeDeathCause
import com.gamesuite.games.snake.SnakeDirection
import com.gamesuite.games.snake.SnakeGame
import com.gamesuite.games.snake.SnakePhase
import com.gamesuite.games.snake.SnakeSpeed
import com.gamesuite.games.snake.SnakeState
import com.gamesuite.games.snake.SnakeStatsStore
import com.gamesuite.games.snake.SnakeStatus
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Snake. The pure rules live in [com.gamesuite.games.snake.SnakeEngine]; [SnakeGame] owns the run
 * lifecycle; this screen owns the clock and the input.
 *
 * LOOP: one LaunchedEffect with a fixed tick (a `delay` of [SnakeGame.tickMillis], which already
 * folds in the chosen speed and the level speed-up; it is NOT scaled by LocalGameSpeed, which is
 * for CPU pauses). It runs only while a countdown or a run is live and not paused, only while the
 * lifecycle is RESUMED, and ends with the composition. It is keyed on (lifecycle, live, paused), so
 * a recomposition or a rotation (the Activity handles its own configuration changes, so the
 * composition and the [SnakeGame] survive) never starts a second loop.
 *
 * PAUSE: the engine freezes on the corner menu opening, any lost window focus (leave dialog, help
 * dialog, notification shade), ON_PAUSE, and an orientation change. Resuming is the player's call:
 * the board shows a "Paused / Tap to resume" scrim.
 *
 * INPUT: swipes on the board (several turns per continuous swipe), an on-screen pad of four 56dp+
 * buttons, arrow keys and WASD. All go through [SnakeGame.steer], whose direction queue refuses
 * reversals. The board also exposes the four turns as screen-reader custom actions.
 *
 * A11Y / COLOUR: the head is a larger rounded square with eyes (crossed out when dead), the body
 * segments are smaller rounded squares, the food is a diamond; solid walls are a solid frame and
 * wrap-around a dashed one. [LocalColorblindMode] adds a dot to every body segment and a cross to
 * the food. [LocalReducedMotion] removes the countdown pop and the result fade.
 */
@Composable
fun SnakeScreen(
    sessionManager: GameSessionManager,
    game: SnakeGame,
    @Suppress("UNUSED_PARAMETER") settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val statsStore = remember { SnakeStatsStore(androidContext) }
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycleOwner = LocalLifecycleOwner.current
    val orientation = LocalConfiguration.current.orientation

    val matchOver by game.matchOver
    val paused by game.paused
    val phase by game.phase
    val countdown by game.countdown
    val finishedRuns by game.runsFinished
    val s by game.state

    // Start-up. A recomposition or a rotation re-enters this effect with the SAME game object, so
    // only a game that has never started gets configured and started.
    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!game.started.value) {
            game.configure(sessionManager.launchOptions.extras)
            game.startMatch()
        }
    }

    if (matchOver) return

    val config = game.config
    val live = phase == SnakePhase.COUNTDOWN || phase == SnakePhase.PLAYING

    // Pause on anything that takes the player's eyes or the window focus away.
    LaunchedEffect(windowFocused) { if (!windowFocused) game.pause() }
    var seenOrientation by rememberSaveable { mutableStateOf(orientation) }
    LaunchedEffect(orientation) {
        if (orientation != seenOrientation) {
            seenOrientation = orientation
            game.pause()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) game.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The one game loop: countdown beats, then fixed-interval steps.
    LaunchedEffect(lifecycleOwner, live, paused) {
        if (!live || paused) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                when (game.phase.value) {
                    SnakePhase.COUNTDOWN -> {
                        delay(COUNTDOWN_BEAT_MS)
                        game.tickCountdown()
                    }
                    SnakePhase.PLAYING -> {
                        delay(game.tickMillis())
                        game.step()
                    }
                    else -> return@repeatOnLifecycle
                }
                if (game.paused.value || game.matchOver.value) return@repeatOnLifecycle
            }
        }
    }

    val bestScores by statsStore.bestScores.collectAsState(initial = emptyMap())
    val bestScore = bestScores[config.bestKey]
    var reportedNewBest by remember(s.runSeq) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(s.foods, s.runSeq) {
        if (s.foods > 0) {
            haptics(HapticSignal.LIGHT_TICK)
            playSfx(SfxKind.LIGHT_TICK)
        }
    }
    LaunchedEffect(s.level, s.runSeq) {
        if (s.level > 1) {
            haptics(HapticSignal.SUCCESS)
            playSfx(SfxKind.SUCCESS_CHIME)
        }
    }
    LaunchedEffect(phase, s.runSeq) {
        if (phase == SnakePhase.OVER && reportedNewBest == null) {
            val isNewBest = statsStore.recordScore(config, s.score)
            reportedNewBest = isNewBest
            if (isNewBest || s.status == SnakeStatus.CLEARED) {
                haptics(HapticSignal.CELEBRATION)
                playSfx(SfxKind.SUCCESS_CHIME)
            } else {
                haptics(HapticSignal.FAILURE)
                playSfx(SfxKind.INVALID_BUZZ)
            }
        }
    }

    val steer: (SnakeDirection) -> Unit = { dir ->
        if (game.steer(dir)) haptics(HapticSignal.LIGHT_TICK)
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val helpTitle = stringResource(R.string.snake_help_title)
    val helpText = stringResource(R.string.snake_help_text)
    val colors = MaterialTheme.colorScheme
    val palette = SnakePalette(
        board = colors.surfaceVariant,
        boardAlt = lerp(colors.surfaceVariant, colors.surface, 0.45f),
        frame = colors.outline,
        body = colors.primary,
        bodyAlt = lerp(colors.primary, colors.surface, 0.22f),
        onBody = colors.onPrimary,
        food = colors.error,
        onFood = colors.onError
    )

    GameChrome(
        helpTitle = helpTitle,
        helpText = helpText,
        matchInProgress = phase != SnakePhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRuns > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedRuns == 0,
        leaveTitle = stringResource(R.string.snake_leave_title),
        leaveBody = stringResource(
            if (finishedRuns > 0) R.string.snake_leave_body_finished else R.string.snake_leave_body
        ),
        // Composed only while the corner menu is open, so opening it freezes the run.
        extraItems = { _ -> LaunchedEffect(Unit) { game.pause() } }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val dir = when (event.key) {
                        Key.DirectionUp, Key.W -> SnakeDirection.UP
                        Key.DirectionDown, Key.S -> SnakeDirection.DOWN
                        Key.DirectionLeft, Key.A -> SnakeDirection.LEFT
                        Key.DirectionRight, Key.D -> SnakeDirection.RIGHT
                        else -> null
                    }
                    if (dir == null) false else { steer(dir); true }
                }
        ) {
            val landscape = maxWidth > maxHeight
            val padSize = if (maxHeight < 560.dp && !landscape) 52.dp else 60.dp

            val board: @Composable (Modifier) -> Unit = { mod ->
                SnakeBoardSlot(
                    state = s,
                    config = config,
                    phase = phase,
                    countdown = countdown,
                    paused = paused,
                    bestScore = bestScore,
                    isNewBest = reportedNewBest ?: false,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    onSteer = steer,
                    onResume = game::resumePlay,
                    onPlayAgain = game::playAgain,
                    onLeave = game::leaveSession,
                    modifier = mod
                )
            }

            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    board(Modifier.weight(1f).fillMaxSize())
                    Column(
                        modifier = Modifier.widthIn(max = 220.dp).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Spacer(Modifier.height(GameChromeEndInset))
                        SnakeHud(state = s, config = config, bestScore = bestScore, stacked = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        SnakeDPad(size = padSize, onSteer = steer)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SnakeHud(
                        state = s,
                        config = config,
                        bestScore = bestScore,
                        stacked = false,
                        modifier = Modifier
                            .widthIn(max = CONTENT_MAX_WIDTH)
                            .fillMaxWidth()
                            .padding(end = GameChromeEndInset)
                    )
                    Spacer(Modifier.height(8.dp))
                    board(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SnakeDPad(size = padSize, onSteer = steer)
                }
            }
        }
    }
}

private val CONTENT_MAX_WIDTH = 560.dp
private const val MAX_BOARD_DP = 560f
private const val COUNTDOWN_BEAT_MS = 800L
private const val RESULT_FADE_MS = 250

/** The always-dark scrim drawn over the board for the countdown, pause and result layers. */
private val SnakeScrim = Color(0xCC101015)
private val SnakeOnScrim = Color(0xFFF5F0E6)

private data class SnakePalette(
    val board: Color,
    val boardAlt: Color,
    val frame: Color,
    val body: Color,
    val bodyAlt: Color,
    val onBody: Color,
    val food: Color,
    val onFood: Color
)

// ---------------------------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------------------------

@Composable
private fun SnakeHud(
    state: SnakeState,
    config: SnakeConfig,
    bestScore: Int?,
    stacked: Boolean,
    modifier: Modifier = Modifier
) {
    val setupLine = stringResource(
        R.string.snake_setup_line,
        config.grid.size,
        stringResource(if (config.wrap) R.string.snake_walls_wrap else R.string.snake_walls_solid),
        stringResource(speedLabel(config.speed))
    )
    val scoreText = stringResource(R.string.snake_score, state.score)
    val bestText = bestScore?.let { stringResource(R.string.snake_best, it) }
    val levelText = stringResource(R.string.snake_level, state.level)
    val lengthText = stringResource(R.string.snake_length, state.snake.size)
    val onBg = MaterialTheme.colorScheme.onBackground
    if (stacked) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(scoreText, color = onBg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (bestText != null) Text(bestText, color = onBg.copy(alpha = 0.75f))
            Text("$levelText · $lengthText", color = onBg)
            Text(
                setupLine,
                color = onBg.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Column(modifier = modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scoreText,
                    color = onBg,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (bestText != null) Text(bestText, color = onBg.copy(alpha = 0.75f), maxLines = 1)
            }
            Text("$levelText · $lengthText", color = onBg, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            Text(
                setupLine,
                color = onBg.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}

private fun speedLabel(speed: SnakeSpeed): Int = when (speed) {
    SnakeSpeed.RELAXED -> R.string.snake_speed_relaxed
    SnakeSpeed.STEADY -> R.string.snake_speed_steady
    SnakeSpeed.QUICK -> R.string.snake_speed_quick
}

// ---------------------------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------------------------

/** Measures its own slot, fits the square board into it, and layers the overlays on top. */
@Composable
private fun SnakeBoardSlot(
    state: SnakeState,
    config: SnakeConfig,
    phase: SnakePhase,
    countdown: Int,
    paused: Boolean,
    bestScore: Int?,
    isNewBest: Boolean,
    palette: SnakePalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    onSteer: (SnakeDirection) -> Unit,
    onResume: () -> Unit,
    onPlayAgain: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val n = config.grid.size
        val fit = remember(maxWidth, maxHeight, n) {
            fitBoard(
                availableWidthPx = maxWidth.value,
                availableHeightPx = maxHeight.value,
                columns = n,
                rows = n,
                maxCellPx = MAX_BOARD_DP / n
            )
        }
        val boardWidth = fit.boardWidthPx.dp
        val boardHeight = fit.boardHeightPx.dp

        val stateText = buildString {
            append(stringResource(R.string.snake_state_desc, state.score, state.level, state.snake.size))
            when {
                phase == SnakePhase.OVER -> append(stringResource(R.string.snake_state_over))
                paused -> append(stringResource(R.string.snake_state_paused))
                phase == SnakePhase.COUNTDOWN -> append(stringResource(R.string.snake_state_countdown, countdown))
            }
        }
        val up = stringResource(R.string.snake_steer_up)
        val down = stringResource(R.string.snake_steer_down)
        val left = stringResource(R.string.snake_steer_left)
        val right = stringResource(R.string.snake_steer_right)
        val boardDesc = stringResource(R.string.snake_board_desc)

        Box(
            modifier = Modifier
                .size(width = boardWidth, height = boardHeight)
                .clip(RoundedCornerShape(10.dp))
                .semantics {
                    contentDescription = boardDesc
                    stateDescription = stateText
                    role = Role.Image
                    customActions = listOf(
                        CustomAccessibilityAction(up) { onSteer(SnakeDirection.UP); true },
                        CustomAccessibilityAction(down) { onSteer(SnakeDirection.DOWN); true },
                        CustomAccessibilityAction(left) { onSteer(SnakeDirection.LEFT); true },
                        CustomAccessibilityAction(right) { onSteer(SnakeDirection.RIGHT); true }
                    )
                }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Several turns per continuous swipe: once the finger has travelled far
                        // enough in one direction the turn is sent and the measuring restarts.
                        val threshold = 22.dp.toPx()
                        var acc = Offset.Zero
                        detectDragGestures(
                            onDragStart = { acc = Offset.Zero },
                            onDrag = { change, drag ->
                                change.consume()
                                acc += drag
                                if (abs(acc.x) >= threshold || abs(acc.y) >= threshold) {
                                    val dir = if (abs(acc.x) > abs(acc.y)) {
                                        if (acc.x > 0f) SnakeDirection.RIGHT else SnakeDirection.LEFT
                                    } else {
                                        if (acc.y > 0f) SnakeDirection.DOWN else SnakeDirection.UP
                                    }
                                    onSteer(dir)
                                    acc = Offset.Zero
                                }
                            }
                        )
                    }
            ) {
                drawSnakeBoard(state, palette, colorblind)
            }

            when {
                phase == SnakePhase.OVER -> SnakeResultOverlay(
                    state = state,
                    bestScore = bestScore,
                    isNewBest = isNewBest,
                    reducedMotion = reducedMotion,
                    onPlayAgain = onPlayAgain,
                    onLeave = onLeave,
                    modifier = Modifier.matchParentSize()
                )
                paused -> SnakePausedScrim(onResume = onResume, modifier = Modifier.matchParentSize())
                phase == SnakePhase.COUNTDOWN -> SnakeCountdownOverlay(
                    number = countdown,
                    reducedMotion = reducedMotion,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

private fun DrawScope.drawSnakeBoard(state: SnakeState, palette: SnakePalette, colorblind: Boolean) {
    val cols = state.cols
    val rows = state.rows
    val cell = size.width / cols
    val dp = 1.dp.toPx()

    // Checkerboard.
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            drawRect(
                color = if ((r + c) % 2 == 0) palette.board else palette.boardAlt,
                topLeft = Offset(c * cell, r * cell),
                size = Size(cell, cell)
            )
        }
    }

    // Food: a diamond (the snake is made of squares, so it differs in shape, not just colour).
    if (state.food >= 0) {
        val fx = (state.food % cols + 0.5f) * cell
        val fy = (state.food / cols + 0.5f) * cell
        val r = cell * 0.4f
        val diamond = Path().apply {
            moveTo(fx, fy - r)
            lineTo(fx + r, fy)
            lineTo(fx, fy + r)
            lineTo(fx - r, fy)
            close()
        }
        drawPath(diamond, palette.food)
        if (colorblind) {
            val k = r * 0.5f
            drawLine(palette.onFood, Offset(fx - k, fy), Offset(fx + k, fy), strokeWidth = 2 * dp)
            drawLine(palette.onFood, Offset(fx, fy - k), Offset(fx, fy + k), strokeWidth = 2 * dp)
        }
    }

    // Body, tail to head, so the head is drawn last.
    val snake = state.snake
    for (i in snake.indices.reversed()) {
        val c = snake[i]
        val x = (c % cols) * cell
        val y = (c / cols) * cell
        if (i == 0) {
            val inset = cell * 0.03f
            drawRoundRect(
                color = palette.body,
                topLeft = Offset(x + inset, y + inset),
                size = Size(cell - 2 * inset, cell - 2 * inset),
                cornerRadius = CornerRadius(cell * 0.32f)
            )
            drawRoundRect(
                color = palette.onBody.copy(alpha = 0.85f),
                topLeft = Offset(x + inset, y + inset),
                size = Size(cell - 2 * inset, cell - 2 * inset),
                cornerRadius = CornerRadius(cell * 0.32f),
                style = Stroke(width = 1.5f * dp)
            )
            drawSnakeEyes(x + cell / 2f, y + cell / 2f, cell, state.direction, dead = state.status == SnakeStatus.DEAD, palette = palette)
        } else {
            val inset = cell * 0.12f
            drawRoundRect(
                color = if (i % 2 == 0) palette.body else palette.bodyAlt,
                topLeft = Offset(x + inset, y + inset),
                size = Size(cell - 2 * inset, cell - 2 * inset),
                cornerRadius = CornerRadius(cell * 0.2f)
            )
            if (colorblind) {
                drawCircle(palette.onBody, radius = cell * 0.08f, center = Offset(x + cell / 2f, y + cell / 2f))
            }
        }
    }

    // Frame: solid walls are a solid line, wrap-around a dashed one.
    val stroke = 3f * dp
    drawRect(
        color = palette.frame,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(
            width = stroke,
            pathEffect = if (state.wrap) PathEffect.dashPathEffect(floatArrayOf(8f * dp, 6f * dp)) else null
        )
    )
}

private fun DrawScope.drawSnakeEyes(
    cx: Float,
    cy: Float,
    cell: Float,
    direction: SnakeDirection,
    dead: Boolean,
    palette: SnakePalette
) {
    val dx = direction.dx.toFloat()
    val dy = direction.dy.toFloat()
    val px = -dy
    val py = dx
    for (side in intArrayOf(-1, 1)) {
        val ex = cx + dx * cell * 0.13f + px * side * cell * 0.2f
        val ey = cy + dy * cell * 0.13f + py * side * cell * 0.2f
        if (dead) {
            val k = cell * 0.09f
            val w = cell * 0.05f
            drawLine(palette.onBody, Offset(ex - k, ey - k), Offset(ex + k, ey + k), strokeWidth = w)
            drawLine(palette.onBody, Offset(ex - k, ey + k), Offset(ex + k, ey - k), strokeWidth = w)
        } else {
            drawCircle(Color.White, radius = cell * 0.15f, center = Offset(ex, ey))
            drawCircle(Color(0xFF15151A), radius = cell * 0.075f, center = Offset(ex + dx * cell * 0.05f, ey + dy * cell * 0.05f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Overlays
// ---------------------------------------------------------------------------------------------

@Composable
private fun SnakeCountdownOverlay(number: Int, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(number, reducedMotion) {
        if (reducedMotion) {
            pop.snapTo(1f)
        } else {
            pop.snapTo(1.5f)
            pop.animateTo(1f, animationSpec = tween(durationMillis = 450))
        }
    }
    Box(
        modifier = modifier
            .background(SnakeScrim.copy(alpha = 0.55f))
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                number.toString(),
                color = SnakeOnScrim,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.snake_steer_hint),
                color = SnakeOnScrim.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

/** The whole scrim is one big resume button. */
@Composable
private fun SnakePausedScrim(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(SnakeScrim)
            .clickable(onClickLabel = stringResource(R.string.snake_resume_action), role = Role.Button, onClick = onResume)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.snake_paused),
                color = SnakeOnScrim,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.snake_tap_to_resume),
                color = SnakeOnScrim.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun SnakeResultOverlay(
    state: SnakeState,
    bestScore: Int?,
    isNewBest: Boolean,
    reducedMotion: Boolean,
    onPlayAgain: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fade = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (fade.value < 1f) fade.animateTo(1f, animationSpec = tween(durationMillis = RESULT_FADE_MS))
    }
    val cleared = state.status == SnakeStatus.CLEARED
    Box(
        modifier = modifier
            .graphicsLayer { alpha = fade.value }
            .background(SnakeScrim)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(if (cleared) R.string.snake_board_cleared else R.string.snake_game_over),
                    style = MaterialTheme.typography.headlineSmall,
                    color = onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                if (!cleared && state.deathCause != null) {
                    Text(
                        stringResource(
                            if (state.deathCause == SnakeDeathCause.WALL) R.string.snake_death_wall else R.string.snake_death_self
                        ),
                        color = onSurface.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.snake_score, state.score),
                    color = onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                if (isNewBest) {
                    Text(
                        stringResource(R.string.snake_new_best),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                } else if (bestScore != null) {
                    Text(stringResource(R.string.snake_best, bestScore), color = onSurface.copy(alpha = 0.75f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPlayAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.snake_play_again), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            ChangeSetupButton(onLeave = onLeave)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.snake_back_to_menu), textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// D-pad
// ---------------------------------------------------------------------------------------------

@Composable
private fun SnakeDPad(size: Dp, onSteer: (SnakeDirection) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        SnakePadButton(SnakeDirection.UP, size, onSteer)
        Row(horizontalArrangement = Arrangement.spacedBy(size)) {
            SnakePadButton(SnakeDirection.LEFT, size, onSteer)
            SnakePadButton(SnakeDirection.RIGHT, size, onSteer)
        }
        SnakePadButton(SnakeDirection.DOWN, size, onSteer)
    }
}

@Composable
private fun SnakePadButton(dir: SnakeDirection, size: Dp, onSteer: (SnakeDirection) -> Unit) {
    val label = stringResource(
        when (dir) {
            SnakeDirection.UP -> R.string.snake_steer_up
            SnakeDirection.DOWN -> R.string.snake_steer_down
            SnakeDirection.LEFT -> R.string.snake_steer_left
            SnakeDirection.RIGHT -> R.string.snake_steer_right
        }
    )
    val container = MaterialTheme.colorScheme.secondaryContainer
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier = Modifier
            .padding(2.dp)
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable(role = Role.Button, onClick = { onSteer(dir) }),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val w = this.size.width
            val h = this.size.height
            val degrees = when (dir) {
                SnakeDirection.UP -> 0f
                SnakeDirection.RIGHT -> 90f
                SnakeDirection.DOWN -> 180f
                SnakeDirection.LEFT -> 270f
            }
            rotate(degrees, pivot = Offset(w / 2f, h / 2f)) {
                val arrow = Path().apply {
                    moveTo(w * 0.5f, h * 0.12f)
                    lineTo(w * 0.88f, h * 0.82f)
                    lineTo(w * 0.12f, h * 0.82f)
                    close()
                }
                drawPath(arrow, content)
            }
        }
    }
}
