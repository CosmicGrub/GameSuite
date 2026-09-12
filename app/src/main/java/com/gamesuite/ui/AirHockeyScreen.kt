package com.gamesuite.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.airhockey.AirHockeyGame
import com.gamesuite.games.airhockey.AirHockeyGame.AirHockeyMotionTier
import com.gamesuite.games.airhockey.AirHockeyPrefsStore
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.PremiumShaders
import com.gamesuite.ui.effects.specularSweep
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

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
 *
 * "Premium 2026 vision" pass (real-physics juice, since this is the one game
 * in the suite already built around a genuine simulation): a velocity-scaled
 * puck trail, paddle-impact shockwave + hit-stop, a baseline+AGSL specular
 * puck highlight, an ice-rink table identity, layered/pitch-varied sound
 * (see [AirHockeySounds] below for why this is a small local SoundPool
 * rather than routing through the shared `CardSounds`), a real goal
 * celebration sequence, the [HapticSignal] vocabulary, and a LOCAL
 * Standard/Maximum motion-intensity tier ([AirHockeyPrefsStore]) — Air
 * Hockey is the one game in this pass that genuinely stacks enough
 * independent compounding effects to earn one. See each read site below for
 * exactly what layers in at which tier.
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
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.AIR_HOCKEY, enabled = musicEnabled)
    val reducedMotion = LocalReducedMotion.current
    val card3D = LocalCard3DMode.current && !reducedMotion
    // Gates every new "juice" effect below (trail rendering, shockwave rings, camera-shake,
    // goal particles, scoreboard punch) — same pattern every screen in this pass uses.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    // Drives both the header/instruction copy below and which half-drags route to the top
    // paddle (see the Canvas's pointerInput) — see AirHockeyGame's topPaddleIsBot for the
    // matching game-logic side of this same mode check.
    val isPassAndPlay = context?.activeMode == PlayMode.SINGLE_DEVICE_PASS_AND_PLAY

    val prefsStore = remember { AirHockeyPrefsStore(androidContext) }
    val motionTier by prefsStore.motionTier.collectAsState(initial = AirHockeyMotionTier.STANDARD)
    val prefsScope = rememberCoroutineScope()
    // Kept in sync every recomposition so AirHockeyGame.tick()'s trail-length scaling reads
    // the same persisted tier this screen's own effects gate on.
    LaunchedEffect(motionTier) { game.motionTier = motionTier }

    val airHockeySounds = remember { AirHockeySounds(androidContext) }
    DisposableEffect(Unit) { onDispose { airHockeySounds.release() } }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result -> sessionManager.endActiveGame(result) }
        game.startMatch()
    }

    // Goal sound/haptic/scoring reaction — keyed on the event's own seq (not just score totals)
    // so every goal, including a same-score-shape rematch, fires its own reaction exactly once.
    LaunchedEffect(state.goalEvent?.seq) {
        val event = state.goalEvent ?: return@LaunchedEffect
        airHockeySounds.playGoal()
        when {
            event.matchOver -> haptics(HapticSignal.CELEBRATION)
            event.scoredByPlayer -> haptics(HapticSignal.STRONG_ACTION)
            else -> haptics(HapticSignal.FAILURE)
        }
    }

    // Paddle-contact sound/haptic — amplitude-scaled off the real impact speed tick() already
    // computes (AirHockeyGame.PaddleCollisionResult.impactSpeed), replacing what used to be
    // total silence + no haptic on every paddle hit in this game.
    LaunchedEffect(state.lastPaddleImpact?.seq) {
        val impact = state.lastPaddleImpact ?: return@LaunchedEffect
        val speedFrac = (impact.speed / AirHockeyGame.MAX_SPEED).coerceIn(0f, 1f)
        airHockeySounds.playPaddleHit(speedFrac)
        haptics(
            when {
                speedFrac > 0.66f -> HapticSignal.STRONG_ACTION
                speedFrac > 0.33f -> HapticSignal.NORMAL_ACTION
                else -> HapticSignal.LIGHT_TICK
            }
        )
    }

    // Wall/board bounce sound — the other previously-silent event; a thinner, higher, single
    // (not layered) cue since a board tap is a much smaller event than a mallet strike.
    LaunchedEffect(state.lastWallBounce?.seq) {
        if (state.lastWallBounce != null) airHockeySounds.playWallBounce()
    }

    // Paddle-impact shockwave rings: a genuine radial pulse spawned on real contact (below,
    // keyed on the same event as the sound/haptic above), then aged/culled every frame by a
    // second, steady-state loop — see ShockwaveRing's KDoc.
    var shockwaveRings by remember { mutableStateOf(listOf<ShockwaveRing>()) }
    LaunchedEffect(state.lastPaddleImpact?.seq) {
        val impact = state.lastPaddleImpact ?: return@LaunchedEffect
        if (!enhanced) return@LaunchedEffect
        val speedFrac = (impact.speed / AirHockeyGame.MAX_SPEED).coerceIn(0f, 1f)
        shockwaveRings = shockwaveRings + ShockwaveRing(impact.seq, impact.position, speedFrac)
    }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L && shockwaveRings.isNotEmpty()) {
                    val dtMs = (nanos - lastNanos) / 1_000_000f
                    shockwaveRings = shockwaveRings
                        .map { it.copy(ageMs = it.ageMs + dtMs) }
                        .filter { it.ageMs < SHOCKWAVE_LIFESPAN_MS }
                }
                lastNanos = nanos
            }
        }
    }

    // Real goal-scored celebration: the ball itself is already held still by tick()'s own
    // hit-stop (AirHockeyGame.GOAL_FREEZE_FRAMES) — this drives the rest of the sequence:
    // camera-shake (Maximum tier only) + a goal-mouth flash + a real velocity/gravity/fade
    // particle burst, all gated behind `enhanced`.
    var shakeOffset by remember { mutableStateOf(Offset.Zero) }
    val flashAlpha = remember { Animatable(0f) }
    var goalParticles by remember { mutableStateOf(listOf<GoalParticle>()) }
    LaunchedEffect(state.goalEvent?.seq) {
        val event = state.goalEvent ?: return@LaunchedEffect
        if (!enhanced) return@LaunchedEffect

        flashAlpha.snapTo(1f)
        launch { flashAlpha.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }

        val spawnY = if (event.scoredByPlayer) 0f else 1f
        val particleCount = if (motionTier == AirHockeyMotionTier.MAXIMUM) 14 else 8
        goalParticles = List(particleCount) {
            val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
            val spd = 0.35f + Random.nextFloat() * 0.5f
            GoalParticle(
                pos = Offset(0.5f, spawnY),
                vel = Offset(cos(angle) * spd, sin(angle) * spd * 0.6f - 0.15f),
                life = 0.5f + Random.nextFloat() * 0.25f,
                maxLife = 0.75f,
                color = if (event.scoredByPlayer) Color(0xFF66BB6A) else Color(0xFFEF5350)
            )
        }

        // Camera-shake reserved for Maximum — the clearest "extra compounding layer" case
        // between the two tiers.
        if (motionTier == AirHockeyMotionTier.MAXIMUM) {
            val shakeDurationMs = 300f
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val elapsedMs = (now - start) / 1_000_000f
                if (elapsedMs >= shakeDurationMs) break
                val decay = 1f - elapsedMs / shakeDurationMs
                val mag = 10f * decay
                shakeOffset = Offset(sin(elapsedMs * 0.09f) * mag, cos(elapsedMs * 0.11f) * mag)
            }
            shakeOffset = Offset.Zero
        }
    }
    // Particle physics loop — independent of the trigger above so an in-flight burst keeps
    // animating smoothly regardless of what else recomposes. Real per-particle velocity +
    // gravity + fade, cheap at this particle count.
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L && goalParticles.isNotEmpty()) {
                    val dt = (nanos - lastNanos) / 1_000_000_000f
                    goalParticles = goalParticles.mapNotNull { p ->
                        val newLife = p.life - dt
                        if (newLife <= 0f) return@mapNotNull null
                        val newVel = Offset(p.vel.x, p.vel.y + GOAL_PARTICLE_GRAVITY * dt)
                        p.copy(
                            pos = Offset(p.pos.x + newVel.x * dt, p.pos.y + newVel.y * dt),
                            vel = newVel,
                            life = newLife
                        )
                    }
                }
                lastNanos = nanos
            }
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
            Button(
                onClick = onMatchEnded,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) { Text("Back to menu") }
        }
        return
    }

    // Cached brushes/textures built once (not re-allocated every frame): Brush.radialGradient's
    // unspecified center/radius resolves against whatever shape it's drawn into, so these need
    // no size key at all despite the canvas size never being known at this point.
    val iceBrush = remember {
        Brush.radialGradient(colors = listOf(Color(0xFF1E88E5), Color(0xFF0D47A1), Color(0xFF082B63)))
    }
    val topPaddleBrush = remember {
        Brush.radialGradient(colors = listOf(Color(0xFFFF8A80), Color(0xFFEF5350), Color(0xFFB71C1C)))
    }
    val playerPaddleBrush = remember {
        Brush.radialGradient(colors = listOf(Color(0xFFB9F6CA), Color(0xFF66BB6A), Color(0xFF1B5E20)))
    }
    // A fixed set of low-alpha scuff lines in normalized 0f..1f table space, seeded once so the
    // texture reads as a static frosted surface rather than flickering noise every frame.
    val frostLines = remember {
        val rnd = Random(20260906L)
        List(18) {
            val x0 = rnd.nextFloat(); val y0 = rnd.nextFloat()
            val len = 0.05f + rnd.nextFloat() * 0.18f
            val angle = rnd.nextFloat() * (Math.PI.toFloat() * 2f)
            Offset(x0, y0) to Offset(
                (x0 + cos(angle) * len).coerceIn(0f, 1f),
                (y0 + sin(angle) * len).coerceIn(0f, 1f)
            )
        }
    }

    // Primary-only (no secondary/hand content in this game) — on a Tab S9 or
    // unfolded Fold this caps + centers the table instead of stretching the
    // square Canvas's surrounding column across the full (very wide) screen.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize(),
        primary = {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPassAndPlay) {
                        Text("Player 1: ", style = MaterialTheme.typography.titleMedium)
                        ScorePunch(state.playerScore, MaterialTheme.typography.titleMedium, enhanced)
                        Text(" — ", style = MaterialTheme.typography.titleMedium)
                        ScorePunch(state.cpuScore, MaterialTheme.typography.titleMedium, enhanced)
                        Text(" : Player 2 (first to ${game.matchTarget})", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("You ", style = MaterialTheme.typography.titleMedium)
                        ScorePunch(state.playerScore, MaterialTheme.typography.titleMedium, enhanced)
                        Text(" — ", style = MaterialTheme.typography.titleMedium)
                        ScorePunch(state.cpuScore, MaterialTheme.typography.titleMedium, enhanced)
                        Text(" CPU (first to ${game.matchTarget})", style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (!isPassAndPlay) {
                    Text(
                        "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Motion: ", style = MaterialTheme.typography.labelSmall)
                    MotionTierLabel("Standard", motionTier == AirHockeyMotionTier.STANDARD) {
                        prefsScope.launch { prefsStore.setMotionTier(AirHockeyMotionTier.STANDARD) }
                    }
                    Spacer(Modifier.width(10.dp))
                    MotionTierLabel("Maximum", motionTier == AirHockeyMotionTier.MAXIMUM) {
                        prefsScope.launch { prefsStore.setMotionTier(AirHockeyMotionTier.MAXIMUM) }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Locked to a square aspect ratio: the physics in AirHockeyGame works in normalized 0f..1f
                // coordinates shared by both axes (a single PADDLE_RADIUS/BALL_RADIUS, Euclidean distance
                // for collisions), which only matches drawn circles when the canvas's width and height are
                // equal. Without this, an unequal w/h canvas turns the true (physics) hitbox into an
                // ellipse in pixel space while paddles/ball are still drawn as circles sized from height
                // alone — so the visible circle and the real collision boundary disagree.
                // The tilt below is a graphicsLayer applied to this wrapping BoxWithConstraints, AFTER the
                // Canvas draws — it only rotates how the table is composited/hit-tested, never
                // the Canvas's own draw-scope math or the pointerInput's coordinate reading
                // (both stay in the Canvas's un-transformed local size/space; Compose maps
                // pointer positions through ancestor graphicsLayers for us). BoxWithConstraints
                // (rather than a plain Box) so the enhanced puck-highlight overlay below can
                // convert the ball's normalized position into a real Dp offset.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .aspectRatio(1f)
                        .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
                        // Goal celebration camera-shake (Maximum tier only, see above) — a pure
                        // compositing translation, never touches layout or the Canvas's own
                        // draw-scope math, same non-interference guarantee as the tilt above.
                        .graphicsLayer { translationX = shakeOffset.x; translationY = shakeOffset.y }
                ) {
                    val boardSizeDp = maxWidth

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
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

                        // Ice-rink table identity: a cached gradient base (brighter center,
                        // darker toward the rail) instead of a flat rect.
                        drawRect(brush = iceBrush, size = size)

                        // Frost/scratch texture: fixed, low-alpha scuff lines (see frostLines'
                        // remember above) converted from normalized to this frame's pixel size.
                        frostLines.forEach { (a, b) ->
                            drawLine(
                                Color.White.copy(alpha = 0.05f),
                                Offset(a.x * w, a.y * h),
                                Offset(b.x * w, b.y * h),
                                strokeWidth = 1.5f
                            )
                        }

                        drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 2f)
                        drawCircle(Color.White.copy(alpha = 0.4f), radius = h * 0.12f, center = Offset(w / 2, h / 2), style = Stroke(2f))

                        // Goal-mouth flash: part of the goal celebration sequence (see
                        // flashAlpha's LaunchedEffect above) — a brief colored wash at whichever
                        // edge was just scored on.
                        if (flashAlpha.value > 0f) {
                            val atTop = state.goalEvent?.scoredByPlayer == true
                            val flashHeight = h * 0.2f
                            val flashTop = if (atTop) 0f else h - flashHeight
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = if (atTop)
                                        listOf(Color(0xFFFFF59D).copy(alpha = flashAlpha.value * 0.55f), Color.Transparent)
                                    else
                                        listOf(Color.Transparent, Color(0xFFFFF59D).copy(alpha = flashAlpha.value * 0.55f)),
                                    startY = flashTop,
                                    endY = flashTop + flashHeight
                                ),
                                topLeft = Offset(0f, flashTop),
                                size = Size(w, flashHeight)
                            )
                        }

                        // Beveled rail/board edge — visually explains the existing wall-bounce
                        // physics (the ball's real collision boundary is exactly this rect's
                        // edge): a dark outer shadow stroke plus a slightly-inset lighter
                        // highlight stroke fakes a rounded bevel without real 3D geometry.
                        val bevelInset = h * 0.01f
                        drawRoundRect(
                            color = Color(0xFF01275A),
                            topLeft = Offset(bevelInset, bevelInset),
                            size = Size(w - bevelInset * 2, h - bevelInset * 2),
                            cornerRadius = CornerRadius(h * 0.03f),
                            style = Stroke(width = h * 0.028f)
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.18f),
                            topLeft = Offset(bevelInset * 2.4f, bevelInset * 2.4f),
                            size = Size(w - bevelInset * 4.8f, h - bevelInset * 4.8f),
                            cornerRadius = CornerRadius(h * 0.025f),
                            style = Stroke(width = h * 0.006f)
                        )

                        // Goal mouths
                        val goalHalfW = AirHockeyGame.GOAL_HALF_WIDTH * w
                        drawLine(Color.Yellow, Offset(w / 2 - goalHalfW, 4f), Offset(w / 2 + goalHalfW, 4f), strokeWidth = 6f)
                        drawLine(Color.Yellow, Offset(w / 2 - goalHalfW, h - 4f), Offset(w / 2 + goalHalfW, h - 4f), strokeWidth = 6f)

                        // Velocity-driven puck trail: the REAL recent ball positions/speeds
                        // AirHockeyGame.tick() already tracks (AirHockeyState.ballTrail), drawn
                        // oldest-to-newest with alpha/width scaled by each point's own real
                        // speed — visualizing existing physics state, not a separate system.
                        if (enhanced) {
                            val trail = state.ballTrail
                            trail.forEachIndexed { i, point ->
                                val ageFrac = (i + 1f) / trail.size
                                val speedFrac = (point.speed / AirHockeyGame.MAX_SPEED).coerceIn(0f, 1f)
                                val alpha = ageFrac * speedFrac * 0.5f
                                if (alpha > 0.01f) {
                                    drawCircle(
                                        Color.White.copy(alpha = alpha),
                                        radius = AirHockeyGame.BALL_RADIUS * h * (0.35f + 0.5f * ageFrac),
                                        center = Offset(point.pos.x * w, point.pos.y * h)
                                    )
                                }
                            }
                        }

                        // Paddles: layered radial gradients for a rounded-plastic-mallet read,
                        // plus a small offset gloss highlight — replaces the old flat drawCircle.
                        val topCenter = Offset(state.cpuPaddle.x * w, state.cpuPaddle.y * h)
                        drawCircle(topPaddleBrush, radius = AirHockeyGame.PADDLE_RADIUS * h, center = topCenter)
                        drawCircle(
                            Color.White.copy(alpha = 0.35f),
                            radius = AirHockeyGame.PADDLE_RADIUS * h * 0.28f,
                            center = topCenter - Offset(AirHockeyGame.PADDLE_RADIUS * h * 0.25f, AirHockeyGame.PADDLE_RADIUS * h * 0.25f)
                        )
                        val playerCenter = Offset(state.playerPaddle.x * w, state.playerPaddle.y * h)
                        drawCircle(playerPaddleBrush, radius = AirHockeyGame.PADDLE_RADIUS * h, center = playerCenter)
                        drawCircle(
                            Color.White.copy(alpha = 0.35f),
                            radius = AirHockeyGame.PADDLE_RADIUS * h * 0.28f,
                            center = playerCenter - Offset(AirHockeyGame.PADDLE_RADIUS * h * 0.25f, AirHockeyGame.PADDLE_RADIUS * h * 0.25f)
                        )

                        // Ball (puck): flat white fill plus a baseline, always-on manual
                        // specular highlight offset opposite the ball's real velocity direction
                        // — works on any device/API. The shared AGSL sweep on top of this (when
                        // supported + enabled) is a separate composable overlay below.
                        val ballCenter = Offset(state.ballPos.x * w, state.ballPos.y * h)
                        drawCircle(Color.White, radius = AirHockeyGame.BALL_RADIUS * h, center = ballCenter)
                        val speed = state.ballVel.length()
                        val highlightDir = if (speed > 0.001f) {
                            Offset(-state.ballVel.x / speed, -state.ballVel.y / speed)
                        } else {
                            Offset(-0.6f, -0.6f)
                        }
                        drawCircle(
                            Color.White.copy(alpha = 0.9f),
                            radius = AirHockeyGame.BALL_RADIUS * h * 0.4f,
                            center = ballCenter + highlightDir * (AirHockeyGame.BALL_RADIUS * h * 0.35f)
                        )

                        // Paddle-impact shockwave rings — a genuine radial pulse on real
                        // contact only (see shockwaveRings' LaunchedEffect above).
                        shockwaveRings.forEach { ring ->
                            val lifeFrac = (ring.ageMs / SHOCKWAVE_LIFESPAN_MS).coerceIn(0f, 1f)
                            drawCircle(
                                Color.White.copy(alpha = (1f - lifeFrac) * (0.2f + 0.35f * ring.speedFrac)),
                                radius = AirHockeyGame.PADDLE_RADIUS * h * (0.9f + lifeFrac * 1.8f),
                                center = Offset(ring.pos.x * w, ring.pos.y * h),
                                style = Stroke(width = h * 0.012f * (1f - lifeFrac * 0.5f))
                            )
                        }

                        // Goal celebration particle burst — real per-particle velocity +
                        // gravity + fade physics (see the particle loop above), cheap at this
                        // count.
                        goalParticles.forEach { p ->
                            val lifeFrac = (p.life / p.maxLife).coerceIn(0f, 1f)
                            drawCircle(
                                p.color.copy(alpha = lifeFrac * 0.9f),
                                radius = h * 0.012f * (0.5f + lifeFrac),
                                center = Offset(p.pos.x * w, p.pos.y * h)
                            )
                        }
                    }

                    // Enhanced real-time specular highlight on the puck: the shared AGSL sweep
                    // layered on top of the baseline highlight drawn inside the Canvas above,
                    // clipped to the puck's own circular bounds so it reads as shine ON the
                    // puck rather than a whole-table effect. A pure no-op below API 33 (or with
                    // 3D perspective mode off) — the baseline highlight is all that shows then.
                    if (card3D && PremiumShaders.isSupported) {
                        val puckDiameterDp = boardSizeDp * (AirHockeyGame.BALL_RADIUS * 2f)
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = boardSizeDp * state.ballPos.x - puckDiameterDp / 2f,
                                    y = boardSizeDp * state.ballPos.y - puckDiameterDp / 2f
                                )
                                .size(puckDiameterDp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f))
                                .specularSweep(enabled = true, tint = Color.White.copy(alpha = 0.85f), periodMs = 1400)
                        ) {}
                    }
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

/** One label of the Standard/Maximum motion-tier control — a plain clickable Text rather than
 *  a full segmented-button component, matching this screen's otherwise text-only header row. */
@Composable
private fun MotionTierLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    )
}

/**
 * One score number with a punch-scale beat (1.0 -> 1.3 -> 1.0) the instant it changes — part of
 * the goal celebration sequence. Gated on [enhanced]: with it false, the number simply updates
 * with no animation played at all, same final value either way.
 */
@Composable
private fun ScorePunch(score: Int, style: TextStyle, enhanced: Boolean) {
    var previous by remember { mutableStateOf(score) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(score) {
        if (score != previous) {
            previous = score
            if (enhanced) {
                scale.snapTo(1.3f)
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            } else {
                scale.snapTo(1f)
            }
        }
    }
    Text(
        score.toString(),
        style = style,
        modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
    )
}

/** An active paddle-impact shockwave ring — see its spawn/age LaunchedEffects above. */
private data class ShockwaveRing(val id: Long, val pos: Offset, val speedFrac: Float, val ageMs: Float = 0f)
private const val SHOCKWAVE_LIFESPAN_MS = 380f

/** One goal-celebration particle — real position/velocity/life, integrated with gravity each
 *  frame by the particle loop above; drawn as a small fading circle. */
private data class GoalParticle(val pos: Offset, val vel: Offset, val life: Float, val maxLife: Float, val color: Color)
/** Downward pull on burst particles, in the same normalized 0f..1f-per-second units as the
 *  ball's own velocity — tuned so a ~0.6s burst arcs and falls within the table's bounds. */
private const val GOAL_PARTICLE_GRAVITY = 1.4f

/**
 * Air Hockey's own tiny SoundPool wrapper, local to this screen only. `games/cards/CardSounds.kt`
 * is shared infrastructure owned by a different, concurrently-edited work item in this pass —
 * its private `play()` already accepts a pitch-jitter `rate`, but none of its public
 * `playPlace()`/`playDraw()`/`playShuffle()`/`playTap()` wrappers forward it, so there is no way
 * to get rate-jittered playback through its existing public API without editing that file, which
 * is outside this bundle's four files. Reusing the exact same existing clips (no new audio
 * assets authored) through a second, tiny SoundPool instance delivers the real goal here —
 * genuine pitch/volume variation from real impact data — without touching a file this bundle
 * doesn't own. `CardSounds.soundEnabled`'s existing public flag is still read (not duplicated)
 * so the app's one master sound toggle keeps controlling this too.
 */
private class AirHockeySounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loadedIds = mutableSetOf<Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loadedIds += sampleId }
    }

    private val thwockId = pool.load(context, R.raw.card_place, 1)
    private val thinId = pool.load(context, R.raw.ui_tap, 1)
    private val goalAId = pool.load(context, R.raw.card_draw, 1)
    private val goalBId = pool.load(context, R.raw.card_shuffle, 1)

    private fun play(id: Int, volume: Float, rate: Float) {
        if (!CardSounds.soundEnabled) return
        // Deliberately no pending-play queue (unlike CardSounds): by the time a real
        // paddle/wall/goal event can fire, the ~tens-of-ms async decode from init() above has
        // always long since finished in practice, and a dropped sound on a freak first-frame
        // hit is an acceptable trade for keeping this small helper simple.
        if (id in loadedIds) pool.play(id, volume, volume, 0, 0, rate)
    }

    /** Pitch/volume scaled by [speedFrac] (0f..1f, impact speed as a fraction of
     *  AirHockeyGame.MAX_SPEED) — two slightly staggered, independently pitch-jittered layers
     *  of the same clip so a hit never sounds identical twice, per the "layered sound" goal. */
    fun playPaddleHit(speedFrac: Float) {
        val f = speedFrac.coerceIn(0f, 1f)
        val baseRate = 0.85f + f * 0.5f
        play(thwockId, volume = (0.55f + f * 0.45f).coerceIn(0f, 1f), rate = (baseRate + Random.nextFloat() * 0.08f).coerceIn(0.5f, 2f))
        play(thwockId, volume = (0.3f + f * 0.3f).coerceIn(0f, 1f), rate = (baseRate * 0.9f + Random.nextFloat() * 0.1f).coerceIn(0.5f, 2f))
    }

    /** A single, thinner/higher-pitched layer — boards getting tapped, not mallets colliding. */
    fun playWallBounce() {
        play(thinId, volume = 0.35f, rate = 1.3f + Random.nextFloat() * 0.15f)
    }

    /** A real goal cue distinct from every other clip in the suite — two different existing
     *  clips layered together rather than a repurposed card-place blip. */
    fun playGoal() {
        play(goalAId, volume = 0.9f, rate = 0.95f + Random.nextFloat() * 0.1f)
        play(goalBId, volume = 0.7f, rate = 1.05f + Random.nextFloat() * 0.1f)
    }

    fun release() = pool.release()
}
