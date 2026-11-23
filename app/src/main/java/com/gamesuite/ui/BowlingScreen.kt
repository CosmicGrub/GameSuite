package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.bowling.BowlShot
import com.gamesuite.games.bowling.BowlingEvent
import com.gamesuite.games.bowling.BowlingGame
import com.gamesuite.games.bowling.BowlingLane
import com.gamesuite.games.bowling.BowlingPhysics
import com.gamesuite.games.bowling.BowlingReject
import com.gamesuite.games.bowling.BowlingRules
import com.gamesuite.games.bowling.BowlingState
import com.gamesuite.games.bowling.SimFrame
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val EYE_SETBACK = 2.2f
private const val EYE_HEIGHT = 1.7f
private const val MAX_CAM_Y = 13.0f
private const val SLOPE_PER_RATIO = 0.05
private const val SWIPE_MIN_DP_PER_S = 500f

private class BowlingTheme(val bg: Color, val text: Color, val card: Color)

@Composable
private fun bowlingTheme(dark: Boolean) = if (!dark) BowlingTheme(
    bg = Color(0xFFEEF1F6), text = Color(0xFF1D2433), card = Color(0xFFDDE3EE)
) else BowlingTheme(bg = Color(0xFF12161F), text = Color(0xFFE8ECF4), card = Color(0xFF1E2531))

private fun ballColor(seat: Int): Color = LudoSeatColors[seat % 4]

private fun speedOf(frac: Float): Double = BowlingLane.MIN_SPEED + frac * (9.0 - BowlingLane.MIN_SPEED)
private fun fracOfSpeed(speed: Double): Float = ((speed - BowlingLane.MIN_SPEED) / (9.0 - BowlingLane.MIN_SPEED)).toFloat().coerceIn(0f, 1f)

/**
 * Ten-pin bowling in a perspective lane drawn on a Canvas. Set where the ball starts, aim and spin
 * with the sliders (or by touch: drag across the foot of the lane to place the ball, then swipe up;
 * the swipe's angle is the aim, its speed the pace and its curve the hook). A camera follows the ball
 * down the lane to the pins, which are discs with real collisions (see BowlingPhysics). The scorecard
 * is official, the rack on screen shows which pins are left, and everything is announced.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BowlingScreen(
    sessionManager: GameSessionManager,
    game: BowlingGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CONNECT_FOUR, enabled = musicEnabled)
    val state by game.state
    val hostLeft by game.hostLeft
    val hintShot by game.hintShot
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = bowlingTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()
    var guide by remember { mutableStateOf(true) }
    var showCard by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.bumpers = extras["bumpers"] == "true"
        guide = extras["guide"] != "false"
        game.assist = extras["assist"]?.takeIf { it in BowlingGame.ASSISTS } ?: BowlingGame.ASSIST_NORMAL
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0

    // ---- shot controls and animation state --------------------------------------------------
    var startX by remember { mutableStateOf(0f) }
    var aimSlope by remember { mutableStateOf(0f) }
    var spin by remember { mutableStateOf(0f) }
    var speedFrac by remember { mutableStateOf(fracOfSpeed(BowlingLane.DEFAULT_SPEED)) }
    var frames by remember { mutableStateOf<List<SimFrame>?>(null) }
    val playhead = remember { Animatable(0f) }
    var playing by remember { mutableStateOf(false) }
    var lastRoll by remember { mutableIntStateOf(-1) }
    var whyKey by remember { mutableStateOf<BowlingReject?>(null) }
    var callout by remember { mutableStateOf<String?>(null) }
    var botThinking by remember { mutableStateOf(false) }
    var rollerSeat by remember { mutableIntStateOf(0) }

    val callStrike = stringResource(R.string.bw_big_strike)
    val callSpare = stringResource(R.string.bw_big_spare)
    val callGutter = stringResource(R.string.bw_big_gutter)
    val callSplit = stringResource(R.string.bw_big_split)
    val callPerfect = stringResource(R.string.bw_big_perfect)

    LaunchedEffect(state?.rollSerial) {
        val cur = state ?: return@LaunchedEffect
        val isNew = lastRoll >= 0 && cur.rollSerial == lastRoll + 1 && cur.lastShot != null
        lastRoll = cur.rollSerial
        if (!isNew) { frames = null; playing = false; return@LaunchedEffect }
        val shot = cur.lastShot ?: return@LaunchedEffect
        val mover = cur.roller
        rollerSeat = mover
        val localMover = !isNetworked || mover == localSeat
        try {
            playSfx(SfxKind.WHOOSH)
            haptics(HapticSignal.LIGHT_TICK)
            if (!reduced) {
                val res = withContext(Dispatchers.Default) { BowlingPhysics.simulate(shot, cur.before, cur.bumpers, true) }
                val fr = res.frames
                frames = fr
                playing = true
                val hitIdx = fr.indexOfFirst { f -> f.pstate.indices.any { i -> cur.before and (1 shl i) != 0 && f.pstate[i].toInt() != 0 } }
                    .let { if (it < 0) fr.size - 1 else it }
                val msPer = (1000f / 60f) * min(gameSpeed.delayScale, 1.6f)
                playhead.snapTo(0f)
                playhead.animateTo(hitIdx.toFloat(), tween((hitIdx * msPer).toInt().coerceAtLeast(60), easing = LinearEasing))
                if (cur.knocked != 0) {
                    playSfx(SfxKind.SOLID_THUNK)
                    haptics(HapticSignal.STRONG_ACTION)
                    launch { delay(gameSpeed.scale(140L)); playSfx(SfxKind.SOLID_THUNK) }
                }
                val rest = (fr.size - 1 - hitIdx).coerceAtLeast(1)
                playhead.animateTo((fr.size - 1).toFloat(), tween((rest * msPer).toInt().coerceAtLeast(60), easing = LinearEasing))
                delay(gameSpeed.scale(250L))
            } else if (cur.knocked != 0) {
                playSfx(SfxKind.SOLID_THUNK)
                haptics(HapticSignal.STRONG_ACTION)
            }
        } finally {
            frames = null
            playing = false
        }
        val split = cur.event == BowlingEvent.ROLL && BowlingLane.isSplit(cur.standing) && cur.phase != BowlingGame.PHASE_OVER
        when {
            cur.event == BowlingEvent.STRIKE -> {
                callout = callStrike; playSfx(SfxKind.SUCCESS_CHIME); haptics(if (localMover) HapticSignal.CELEBRATION else HapticSignal.SUCCESS)
                if (!reduced) burst.spawn(Offset(0.5f, 0.45f), 40, listOf(ballColor(mover), Color.White, Color(0xFFFFD866)),
                    speedRange = 0.2f..0.7f, lifeRangeSeconds = 0.8f..1.4f, gravity = 0.9f)
            }
            cur.event == BowlingEvent.SPARE -> { callout = callSpare; playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
            cur.event == BowlingEvent.GUTTER -> { callout = callGutter; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
            split -> { callout = callSplit; playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.ESCALATING) }
        }
        if (cur.phase == BowlingGame.PHASE_OVER && cur.players.any { BowlingRules.total(it.rolls) == BowlingRules.MAX_SCORE }) callout = callPerfect
        if (callout != null) { delay(gameSpeed.scale(1000L)); callout = null }
    }

    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (s.phase == BowlingGame.PHASE_OVER || !game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            delay(gameSpeed.scale(900L).coerceAtLeast(500L))
            while (playing) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    LaunchedEffect(hintShot) {
        val h = hintShot ?: return@LaunchedEffect
        startX = h.x.toFloat(); aimSlope = h.slope.toFloat(); spin = h.spin.toFloat(); speedFrac = fracOfSpeed(h.speed)
    }

    val s = state
    val helpTitle = stringResource(R.string.bw_help_title)
    val helpText = stringResource(R.string.bw_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = theme.bg, buttonContent = theme.text
            ) {
                Box(Modifier.fillMaxSize().background(theme.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bw_waiting_host), color = theme.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    fun nameOf(i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    val isMyTurn = game.isMyTurn(s)
    val canRoll = isMyTurn && !playing && s.phase == BowlingGame.PHASE_THROW
    val finishedBoards = game.sessionWins.value.values.sum()
    val pos = s.position
    val frameNo = (pos?.first ?: (BowlingRules.FRAMES - 1)) + 1
    val ballNo = (pos?.second ?: 0) + 1
    val standingNow = s.standing
    val pinsText = if (BowlingLane.pinCount(standingNow) == 0) stringResource(R.string.bw_pins_none)
    else BowlingLane.pinNumbers(standingNow).joinToString(", ")

    val currentShot = BowlShot(startX.toDouble(), aimSlope.toDouble(), speedOf(speedFrac), spin.toDouble())

    // ---- words ------------------------------------------------------------------------------
    val current = nameOf(s.current)
    val statusText = run {
        val who = current
        val roller = nameOf(s.roller)
        val pins = BowlingLane.pinCount(s.before) - BowlingLane.pinCount(s.before and s.knocked.inv())
        val last = when (s.event) {
            BowlingEvent.STRIKE -> sayYou(roller, R.string.bw_ann_strike, R.string.bw_ann_strike_you)
            BowlingEvent.SPARE -> sayYou(roller, R.string.bw_ann_spare, R.string.bw_ann_spare_you)
            BowlingEvent.GUTTER -> sayYou(roller, R.string.bw_ann_gutter, R.string.bw_ann_gutter_you)
            BowlingEvent.MISS -> sayYou(roller, R.string.bw_ann_miss, R.string.bw_ann_miss_you)
            BowlingEvent.ROLL -> sayYou(roller, R.string.bw_ann_roll, R.string.bw_ann_roll_you, pins, BowlingLane.pinCount(s.before) - pins)
            BowlingEvent.FINISH -> if (s.winnerPlayerId != null) sayYou(nameOf(s.players.indexOfFirst { it.playerId == s.winnerPlayerId }), R.string.bw_ann_win, R.string.bw_ann_win_you, s.total(s.players.indexOfFirst { it.playerId == s.winnerPlayerId }))
            else stringResource(R.string.bw_ann_tie)
            else -> sayYou(who, R.string.bw_ann_start, R.string.bw_ann_start_you)
        }
        val turn = when {
            s.phase == BowlingGame.PHASE_OVER -> ""
            hostLeft -> stringResource(R.string.bw_host_left)
            botThinking -> stringResource(R.string.bw_thinking, who)
            isMyTurn -> stringResource(R.string.bw_turn_you, who, frameNo, ballNo)
            else -> stringResource(R.string.bw_turn_named, who, frameNo, ballNo)
        }
        listOf(last, turn).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText = whyKey?.let { k ->
        when (k) {
            BowlingReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.bw_why_wait else R.string.bw_why_cpu)
            BowlingReject.OVER -> stringResource(R.string.bw_why_over)
            BowlingReject.BAD_SHOT -> stringResource(R.string.bw_why_bad_shot)
            BowlingReject.NONE -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    fun tryRoll(shot: BowlShot) {
        val r = game.explainRoll(shot)
        if (r != BowlingReject.NONE) { whyKey = r; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE); return }
        if (playing) return
        whyKey = null
        game.roll(shot)
    }

    val laneDescription = stringResource(R.string.bw_lane_desc, pinsText)

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = s.phase != BowlingGame.PHASE_OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.bw_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.bw_leave_body_session else R.string.bw_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("bowling", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("bowling", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.players.indices.forEach { i ->
                            val p = s.players[i]
                            val c = ballColor(i)
                            val isTurn = i == s.current && s.phase != BowlingGame.PHASE_OVER
                            val chipName = nameOf(i) + if (isNetworked && i == localSeat) " " + stringResource(R.string.bw_you_suffix) else ""
                            val total = s.total(i)
                            val desc = stringResource(R.string.bw_chip_desc, chipName, total) +
                                if (p.isBot) " (" + stringResource(R.string.bw_cpu_tag) + ")" else ""
                            Row(
                                Modifier
                                    .heightIn(min = 40.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isTurn) c.copy(alpha = 0.22f) else Color.Transparent)
                                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) c else theme.text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics(mergeDescendants = true) { contentDescription = desc },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ClSeatBadge(c, i, 20)
                                Spacer(Modifier.width(5.dp))
                                Text(chipName, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(6.dp))
                                Text("$total", color = theme.text, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val lane: @Composable (Modifier) -> Unit = { mod ->
                Box(mod.clipToBounds(), contentAlignment = Alignment.Center) {
                    val startXNow by rememberUpdatedState(startX)
                    val canRollNow by rememberUpdatedState(canRoll)
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = laneDescription }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val f = min(1.2f * w, 1.05f * h)
                                    fun laneX(px: Float) = ((px - w / 2f) * EYE_SETBACK / f).coerceIn(-BowlingLane.MAX_START.toFloat(), BowlingLane.MAX_START.toFloat())
                                    val tracker = VelocityTracker()
                                    tracker.addPosition(down.uptimeMillis, down.position)
                                    val points = ArrayList<Offset>()
                                    points += down.position
                                    val placing = down.position.y > h * 0.55f
                                    if (placing && canRollNow) startX = laneX(down.position.x)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                        // historical samples too: a quick flick delivered as one batched event would otherwise give the tracker too few points
                                            ch.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
                                            tracker.addPosition(ch.uptimeMillis, ch.position)
                                        points += ch.position
                                        if (ch.changedToUp()) {
                                            val v = tracker.calculateVelocity()
                                            val vUp = -v.y / density
                                            val a = points.first(); val b = points.last()
                                            val dy = a.y - b.y
                                            if (vUp > SWIPE_MIN_DP_PER_S && dy > 50f * density) {
                                                val dx = b.x - a.x
                                                val ratio = (dx / dy).toDouble()
                                                val slope = (ratio * SLOPE_PER_RATIO).coerceIn(-BowlingLane.MAX_SLOPE, BowlingLane.MAX_SLOPE)
                                                // curve: how far the middle of the swipe bulges sideways of the straight line
                                                val mid = points[points.size / 2]
                                                val len = kotlin.math.hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1f)
                                                val cross = ((b.x - a.x) * (mid.y - a.y) - (b.y - a.y) * (mid.x - a.x)) / len
                                                val sp = (-(cross / len) * 4.5).coerceIn(-1.0, 1.0)
                                                val spd = (BowlingLane.MIN_SPEED + ((vUp - SWIPE_MIN_DP_PER_S) / 2400f).coerceIn(0f, 1f) * (9.0 - BowlingLane.MIN_SPEED))
                                                aimSlope = slope.toFloat(); spin = sp.toFloat(); speedFrac = fracOfSpeed(spd)
                                                tryRoll(BowlShot(startXNow.toDouble(), slope, spd, sp))
                                            } else if (!canRollNow) {
                                                val r = game.explainRoll()
                                                if (r != BowlingReject.NONE) { whyKey = r; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                                            }
                                            break
                                        }
                                        if (placing && canRollNow) startX = laneX(ch.position.x)
                                        ch.consume()
                                    }
                                }
                            }
                    ) {
                        val fr = frames
                        val idx = if (fr != null) playhead.value.toInt().coerceIn(0, fr.size - 1) else 0
                        val frame = fr?.getOrNull(idx)
                        val camY = if (frame != null) min(max(frame.ballY - 1.5f, 0f), MAX_CAM_Y) else 0f
                        val cam = LaneCamera(size.width, size.height, camY)
                        drawLane(cam, s.bumpers)
                        if (guide && canRoll && frame == null) {
                            drawGuide(cam, BowlingPhysics.path(currentShot, s.bumpers), Color.White)
                            val hs = hintShot
                            if (hs != null) drawGuide(cam, BowlingPhysics.path(hs, s.bumpers), Color(0xFFFFD21F))
                        }
                        // pins
                        if (frame != null) {
                            val order = (0 until 10).sortedByDescending { frame.py[it] }
                            for (i in order) {
                                when (frame.pstate[i].toInt()) {
                                    0 -> drawPin(cam, frame.px[i], frame.py[i])
                                    1 -> drawFallenPin(cam, frame.px[i], frame.py[i], frame.fx[i], frame.fy[i])
                                }
                            }
                        } else {
                            for (i in (0 until 10).sortedByDescending { BowlingLane.PIN_Y[it] }) {
                                if (standingNow and (1 shl i) != 0) drawPin(cam, BowlingLane.PIN_X[i].toFloat(), BowlingLane.PIN_Y[i].toFloat())
                            }
                        }
                        // ball
                        val bcol = ballColor(if (frame != null) rollerSeat else s.current)
                        if (frame != null) {
                            if (!frame.ballGone) drawBall(cam, frame.ballX, frame.ballY, bcol)
                        } else if (canRoll || s.phase == BowlingGame.PHASE_THROW) {
                            drawBall(cam, startX, 0f, bcol)
                        }
                        drawBurst(burst)
                    }
                    // the rack: pins still standing
                    Canvas(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(width = 74.dp, height = 62.dp)
                            .semantics { contentDescription = androidContext.getString(R.string.bw_rack_desc, pinsText) }
                    ) {
                        drawRack(standingNow, theme.text)
                    }
                    val co = callout
                    if (co != null) {
                        Text(
                            co, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                                .background(Color(0xAA000000), RoundedCornerShape(16.dp))
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            val footer: @Composable () -> Unit = {
                Spacer(Modifier.height(4.dp))
                ScoreRow(s, s.current, theme, ::nameOf)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showCard = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bw_scorecard)) }
                    Text(stringResource(R.string.bw_pins_standing, pinsText), color = theme.text, style = MaterialTheme.typography.bodySmall)
                }
                if (s.phase != BowlingGame.PHASE_OVER) {
                    BowlSlider(stringResource(R.string.bw_start), startX, -0.5f..0.5f, canRoll,
                        valueText(startX * 100f, androidContext)) { startX = it }
                    BowlSlider(stringResource(R.string.bw_aim), aimSlope, -0.05f..0.05f, canRoll,
                        valueText(aimSlope * 2000f, androidContext)) { aimSlope = it }
                    BowlSlider(stringResource(R.string.bw_spin), spin, -1f..1f, canRoll,
                        valueText(spin * 100f, androidContext)) { spin = it }
                    BowlSlider(stringResource(R.string.bw_speed), speedFrac, 0f..1f, canRoll,
                        androidContext.getString(R.string.bw_percent, (speedFrac * 100).toInt())) { speedFrac = it }
                } else {
                    Spacer(Modifier.height(10.dp))
                    val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                    val secs = (game.durationMs.value / 1000L).toInt()
                    ClFinishedPanel(
                        title = if (winnerSeat >= 0 && s.players.size > 1) sayYou(nameOf(winnerSeat), R.string.bw_wins, R.string.bw_wins_you, s.total(winnerSeat))
                        else if (winnerSeat >= 0) stringResource(R.string.bw_solo_score, s.total(winnerSeat))
                        else stringResource(R.string.bw_tie),
                        subtitle = if (isNetworked && winnerSeat >= 0) stringResource(if (winnerSeat == localSeat) R.string.bw_you_won else R.string.bw_you_lost) else null,
                        lines = s.players.indices.map { stringResource(R.string.bw_final_line, nameOf(it), s.total(it)) } +
                            listOf(stringResource(R.string.bw_match_time, "%d:%02d".format(secs / 60, secs % 60))) +
                            (if (finishedBoards > 1) listOf(
                                stringResource(R.string.bw_session, s.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)}: ${game.sessionWins.value[it.playerId] ?: 0}" })
                            ) else emptyList()),
                        canPlayAgain = !hostLeft,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() },
                        playAgainLabel = stringResource(R.string.bw_play_again),
                        backLabel = stringResource(R.string.bw_back_to_menu)
                    )
                }
            }

            // The Roll and Hint buttons stay on screen (they are not part of the scrolling controls): in a short window or at a large font they were pushed below the fold.
            val actions: @Composable () -> Unit = {
                if (s.phase != BowlingGame.PHASE_OVER) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { tryRoll(currentShot) },
                            enabled = canRoll,
                            modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 140.dp)
                        ) { Text(stringResource(R.string.bw_roll), fontWeight = FontWeight.Bold) }
                        if (!isNetworked) {
                            OutlinedButton(
                                onClick = { scope.launch { game.requestHint() } }, enabled = canRoll,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.bw_hint)) }
                        }
                    }
                    Text(
                        stringResource(R.string.bw_gesture_hint), color = theme.text.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.5f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            header(); footer()
                        }
                        actions()
                    }
                    lane(Modifier.weight(0.5f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    lane(Modifier.weight(1f, fill = true).fillMaxWidth().heightIn(min = 180.dp))
                    Column(Modifier.heightIn(max = if (s.phase == BowlingGame.PHASE_OVER) 420.dp else 240.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { footer() }
                    actions()
                }
            }
        }
        if (showCard) {
            AlertDialog(
                onDismissRequest = { showCard = false },
                confirmButton = { TextButton(onClick = { showCard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bw_close)) } },
                title = { Text(stringResource(R.string.bw_scorecard)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        s.players.indices.forEach { ScoreRow(s, it, theme, ::nameOf) }
                    }
                }
            )
        }
    }
}

private fun valueText(v: Float, ctx: android.content.Context): String {
    val n = kotlin.math.round(abs(v)).toInt()
    return when {
        n == 0 -> ctx.getString(R.string.bw_center)
        v < 0 -> ctx.getString(R.string.bw_left_n, n)
        else -> ctx.getString(R.string.bw_right_n, n)
    }
}

@Composable
private fun BowlSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, valueDesc: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(74.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, enabled = enabled,
            modifier = Modifier.weight(1f).semantics { contentDescription = label; stateDescription = valueDesc }
        )
        Text(valueDesc, modifier = Modifier.width(78.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
    }
}

// ------------------------------------------------------------------------------------------
// Scorecard
// ------------------------------------------------------------------------------------------

/** The marks of frame [f] (0-based) as shown on a paper scoresheet. */
private fun frameMarks(rolls: List<Int>, f: Int): List<String> {
    val fr = BowlingRules.frames(rolls).getOrNull(f) ?: return emptyList()
    if (f < BowlingRules.FRAMES - 1) {
        if (fr[0] == 10) return listOf("X")
        val out = ArrayList<String>()
        out += if (fr[0] == 0) "-" else "${fr[0]}"
        if (fr.size > 1) out += if (fr[0] + fr[1] == 10) "/" else if (fr[1] == 0) "-" else "${fr[1]}"
        return out
    }
    val out = ArrayList<String>()
    var standing = 10
    for (r in fr) {
        out += when {
            r == 10 && standing == 10 -> "X"
            r == standing && standing != 10 -> "/"
            r == 0 -> "-"
            else -> "$r"
        }
        standing -= r
        if (standing == 0) standing = 10
    }
    return out
}

@Composable
private fun ScoreRow(s: BowlingState, seat: Int, theme: BowlingTheme, nameOf: (Int) -> String) {
    val rolls = s.players[seat].rolls
    val cum = BowlingRules.cumulative(rolls)
    val strikeWord = stringResource(R.string.bw_word_strike)
    val spareWord = stringResource(R.string.bw_word_spare)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(nameOf(seat), color = theme.text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (f in 0 until BowlingRules.FRAMES) {
                val marks = frameMarks(rolls, f)
                val total = cum.getOrNull(f)
                val w = if (f == BowlingRules.FRAMES - 1) 58.dp else 40.dp
                val desc = if (marks.isEmpty()) stringResource(R.string.bw_frame_empty, f + 1)
                else stringResource(R.string.bw_frame_desc, f + 1,
                    marks.joinToString(" ") { if (it == "X") strikeWord else if (it == "/") spareWord else if (it == "-") "0" else it },
                    total?.toString() ?: "-")
                val isNow = seat == s.current && s.phase != BowlingGame.PHASE_OVER && (s.position?.first == f)
                Column(
                    Modifier.width(w).padding(1.dp)
                        .border(if (isNow) 2.dp else 1.dp, if (isNow) ballColor(seat) else theme.text.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .semantics(mergeDescendants = true) { contentDescription = desc },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), horizontalArrangement = Arrangement.End) {
                        marks.forEach { m ->
                            Text(m, color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(18.dp).background(theme.card), textAlign = TextAlign.Center)
                        }
                    }
                    Text(total?.toString() ?: "", color = theme.text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.heightIn(min = 22.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Drawing: a pinhole camera looking down the lane
// ------------------------------------------------------------------------------------------

private class LaneCamera(val w: Float, val h: Float, val camY: Float) {
    val f = min(1.2f * w, 1.05f * h)
    val horizon = 0.10f * h
    fun depth(y: Float) = (y - camY + EYE_SETBACK).coerceAtLeast(0.35f)
    fun p(x: Float, y: Float, z: Float = 0f): Offset {
        val d = depth(y)
        return Offset(w / 2f + f * x / d, horizon + f * (EYE_HEIGHT - z) / d)
    }
    fun scale(y: Float) = f / depth(y)
}

private fun DrawScope.drawLane(cam: LaneCamera, bumpers: Boolean) {
    val hw = BowlingLane.HALF_W.toFloat()
    val gut = 0.235f
    val yNear = cam.camY - EYE_SETBACK + 0.5f
    val yFar = BowlingLane.DECK_END.toFloat() + 0.2f
    // backdrop: the pinsetter
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0D12), Color(0xFF232A36)), 0f, cam.p(0f, yFar).y), size = androidx.compose.ui.geometry.Size(size.width, cam.p(0f, yFar).y))
    fun quad(x0: Float, x1: Float, y0: Float, y1: Float, brush: Brush) {
        val path = Path().apply {
            val a = cam.p(x0, y0); val b = cam.p(x1, y0); val c = cam.p(x1, y1); val d = cam.p(x0, y1)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        drawPath(path, brush)
    }
    val top = cam.p(0f, yFar).y
    val bottom = cam.p(0f, yNear).y
    // gutters
    quad(-hw - gut, -hw, yNear, yFar, Brush.verticalGradient(listOf(Color(0xFF14171D), Color(0xFF2B313C)), top, bottom))
    quad(hw, hw + gut, yNear, yFar, Brush.verticalGradient(listOf(Color(0xFF14171D), Color(0xFF2B313C)), top, bottom))
    // approach and lane
    val foul = cam.p(0f, 0f).y
    if (yNear < 0f) quad(-hw, hw, yNear, 0f, Brush.verticalGradient(listOf(Color(0xFFB98A55), Color(0xFFD9AE76)), foul, bottom))
    quad(-hw, hw, max(yNear, 0f), yFar, Brush.verticalGradient(listOf(Color(0xFFD8A967), Color(0xFFEFCB8E)), top, if (yNear < 0f) foul else bottom))
    // the pit
    quad(-hw - gut, hw + gut, BowlingLane.DECK_END.toFloat(), yFar, SolidBrush(Color(0xFF07080B)))
    // boards
    for (k in 1 until 39 step 2) {
        val x = -hw + k * (2 * hw / 39f)
        drawLine(Color(0x22000000), cam.p(x, max(yNear, 0f)), cam.p(x, BowlingLane.DECK_END.toFloat()), 1f)
    }
    // foul line, arrows
    drawLine(Color(0xFFCC3B2E), cam.p(-hw, 0f), cam.p(hw, 0f), max(2f, 0.03f * cam.scale(0f)))
    for (b in intArrayOf(5, 10, 15, 20, 25, 30, 35)) {
        val x = -hw + (b - 0.5f) * (2 * hw / 39f)
        val ay = 4.57f + abs(b - 20) * 0.045f
        val p = Path().apply {
            val tip = cam.p(x, ay + 0.35f); val l = cam.p(x - 0.035f, ay); val r = cam.p(x + 0.035f, ay)
            moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(r.x, r.y); close()
        }
        drawPath(p, Color(0xAA5B3A1A))
    }
    if (bumpers) {
        val c = Color(0xFF2E7D9A)
        drawLine(c, cam.p(-hw, max(yNear, 0f), 0.05f), cam.p(-hw, BowlingLane.HEAD_Y.toFloat() - 0.6f, 0.05f), max(3f, 0.1f * cam.scale(2f)), StrokeCap.Round)
        drawLine(c, cam.p(hw, max(yNear, 0f), 0.05f), cam.p(hw, BowlingLane.HEAD_Y.toFloat() - 0.6f, 0.05f), max(3f, 0.1f * cam.scale(2f)), StrokeCap.Round)
    }
}

private fun SolidBrush(c: Color): Brush = Brush.verticalGradient(listOf(c, c))

private fun DrawScope.drawGuide(cam: LaneCamera, pts: List<Pair<Double, Double>>, color: Color) {
    pts.forEachIndexed { i, (x, y) ->
        if (i % 2 == 0 && y > 0.4) {
            val p = cam.p(x.toFloat(), y.toFloat())
            drawCircle(color.copy(alpha = 0.85f - 0.5f * (y / 19.0).toFloat()), max(2f, 0.03f * cam.scale(y.toFloat())), p)
        }
    }
}

private val PIN_PROFILE = listOf(
    0.00f to 0.028f, 0.02f to 0.040f, 0.07f to 0.056f, 0.11f to 0.060f, 0.16f to 0.052f, 0.21f to 0.036f,
    0.25f to 0.028f, 0.29f to 0.031f, 0.33f to 0.040f, 0.36f to 0.036f, 0.38f to 0.0f
)

private fun DrawScope.drawPin(cam: LaneCamera, x: Float, y: Float) {
    val base = cam.p(x, y)
    val sc = cam.scale(y)
    if (sc < 1f) return
    drawOval(Color(0x44000000), Offset(base.x - 0.065f * sc, base.y - 0.02f * sc), androidx.compose.ui.geometry.Size(0.13f * sc, 0.045f * sc))
    val path = Path().apply {
        PIN_PROFILE.forEachIndexed { i, (z, w) ->
            val px = base.x + w * sc; val py = base.y - z * sc
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        for ((z, w) in PIN_PROFILE.reversed()) lineTo(base.x - w * sc, base.y - z * sc)
        close()
    }
    drawPath(path, Brush.horizontalGradient(listOf(Color(0xFFDCD8CE), Color(0xFFFFFFFF), Color(0xFFC9C4B8)), base.x - 0.06f * sc, base.x + 0.06f * sc))
    drawPath(path, Color(0x55000000), style = Stroke(max(1f, 0.004f * sc)))
    // two red neck stripes
    for (z in floatArrayOf(0.235f, 0.26f)) {
        drawLine(Color(0xFFC0392B), Offset(base.x - 0.03f * sc, base.y - z * sc), Offset(base.x + 0.03f * sc, base.y - z * sc), max(1.5f, 0.012f * sc))
    }
}

private fun DrawScope.drawFallenPin(cam: LaneCamera, x: Float, y: Float, fx: Float, fy: Float) {
    val sc = cam.scale(y)
    if (sc < 1f) return
    val len = 0.19f
    val a = cam.p(x - fx * len, y - fy * len, 0.055f)
    val b = cam.p(x + fx * len, y + fy * len, 0.055f)
    drawLine(Color(0x44000000), Offset(a.x, a.y + 0.04f * sc), Offset(b.x, b.y + 0.04f * sc), 0.11f * sc, StrokeCap.Round)
    drawLine(Color(0xFFF1EEE6), a, b, 0.105f * sc, StrokeCap.Round)
    val mid = Offset(a.x + (b.x - a.x) * 0.72f, a.y + (b.y - a.y) * 0.72f)
    drawCircle(Color(0xFFC0392B), 0.032f * sc, mid)
}

private fun DrawScope.drawBall(cam: LaneCamera, x: Float, y: Float, color: Color) {
    val r = BowlingLane.BALL_R.toFloat()
    val center = cam.p(x, y, r)
    val rad = r * cam.scale(y)
    if (rad < 1f) return
    val ground = cam.p(x, y)
    drawOval(Color(0x55000000), Offset(ground.x - rad * 0.95f, ground.y - rad * 0.22f), androidx.compose.ui.geometry.Size(rad * 1.9f, rad * 0.5f))
    drawCircle(
        Brush.radialGradient(listOf(clShade(color, 0.45f), color, clShade(color, -0.55f)), Offset(center.x - rad * 0.35f, center.y - rad * 0.4f), rad * 1.7f),
        rad, center
    )
    // finger holes roll with the ball
    val spin = y / r
    val holes = listOf(-0.5f to 0.55f, 0.5f to 0.55f, 0.0f to 0.2f)
    for ((hx, phi) in holes) {
        val ang = spin + phi * 3f
        val depth = cos(ang)
        if (depth > 0.1f) {
            val hp = Offset(center.x + hx * 0.45f * rad, center.y - sin(ang) * 0.6f * rad)
            drawCircle(Color(0xFF0B0B0F), rad * 0.12f * depth + 0.5f, hp)
        }
    }
    drawCircle(Color.White.copy(alpha = 0.6f), rad * 0.16f, Offset(center.x - rad * 0.4f, center.y - rad * 0.45f))
}

private fun DrawScope.drawRack(mask: Int, color: Color) {
    val sx = size.width / 4.4f
    val sy = size.height / 4.6f
    for (i in 0 until 10) {
        val row = (BowlingLane.PIN_Y[i].toFloat() - BowlingLane.HEAD_Y.toFloat()) / 0.2639f
        val c = Offset(size.width / 2f + (BowlingLane.PIN_X[i].toFloat() / 0.3048f) * sx * 0.95f, size.height - sy * 0.8f - row * sy)
        if (mask and (1 shl i) != 0) drawCircle(color, sx * 0.34f, c)
        else drawCircle(color.copy(alpha = 0.35f), sx * 0.34f, c, style = Stroke(2f))
    }
}
