package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.playProceduralSfx
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameCoach
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.minigolf.GolfCourse
import com.gamesuite.games.minigolf.GolfEnd
import com.gamesuite.games.minigolf.GolfEvent
import com.gamesuite.games.minigolf.GolfGame
import com.gamesuite.games.minigolf.GolfHole
import com.gamesuite.games.minigolf.GolfPhase
import com.gamesuite.games.minigolf.GolfPhysics
import com.gamesuite.games.minigolf.GolfReject
import com.gamesuite.games.minigolf.GolfRules
import com.gamesuite.games.minigolf.GolfScoreName
import com.gamesuite.games.minigolf.GolfShot
import com.gamesuite.games.minigolf.GolfState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val MG_GRASS_LIGHT = Color(0xFF3F9A4A)
private val MG_GRASS_DARK = Color(0xFF2F8140)
private val MG_ROUGH_TOP = Color(0xFF1B4D2B)
private val MG_ROUGH_BOTTOM = Color(0xFF123820)
private val MG_WOOD = Color(0xFF8A5A2B)
private val MG_WOOD_HI = Color(0xFFC08A52)
private val MG_SAND = Color(0xFFE6D08F)
private val MG_WATER = Color(0xFF2F86C8)
private val MG_TEXT = Color(0xFFF3F8F0)
private val MG_GOLD = Color(0xFFFFD866)
private const val MG_WALL = 2.6

/** Maps hole units to screen pixels. In portrait the field is turned so its long side runs down the screen. */
private class GolfView(val rot: Boolean, val scale: Float, val ox: Float, val oy: Float) {
    fun at(x: Double, y: Double): Offset =
        if (rot) Offset(ox + ((GolfCourse.FIELD_H - y) * scale).toFloat(), oy + (x * scale).toFloat())
        else Offset(ox + (x * scale).toFloat(), oy + (y * scale).toFloat())
    fun toField(p: Offset): Pair<Double, Double> =
        if (rot) Pair(((p.y - oy) / scale).toDouble(), GolfCourse.FIELD_H - ((p.x - ox) / scale).toDouble())
        else Pair(((p.x - ox) / scale).toDouble(), ((p.y - oy) / scale).toDouble())
    /** A field-space direction as a screen-space direction. */
    fun dir(dx: Double, dy: Double): Offset = if (rot) Offset((-dy).toFloat(), dx.toFloat()) else Offset(dx.toFloat(), dy.toFloat())
}

private fun makeGolfView(w: Float, h: Float): GolfView {
    val rot = h > w * 1.05f
    val fw = if (rot) GolfCourse.FIELD_H else GolfCourse.FIELD_W
    val fh = if (rot) GolfCourse.FIELD_W else GolfCourse.FIELD_H
    val scale = min(w / fw.toFloat(), h / fh.toFloat()).coerceAtLeast(0.01f)
    return GolfView(rot, scale, (w - fw.toFloat() * scale) / 2f, (h - fh.toFloat() * scale) / 2f)
}

/** A rough preview of the putt: the straight line to the first wall or bumper, then up to two bounces. */
private fun previewPath(hole: GolfHole, x0: Double, y0: Double, angle: Double, power: Double): List<Pair<Double, Double>> {
    val v0 = GolfPhysics.speedFor(power)
    var remaining = v0 * v0 / (2 * GolfPhysics.FRICTION)
    var x = x0; var y = y0
    var dx = cos(angle); var dy = sin(angle)
    val pts = arrayListOf(x to y)
    val r = GolfPhysics.BALL_R
    for (leg in 0..2) {
        var bestT = remaining
        var nx = 0.0; var ny = 0.0; var hit = false
        for (w in hole.walls) {
            val sx = w[2] - w[0]; val sy = w[3] - w[1]
            val den = dx * sy - dy * sx
            if (abs(den) < 1e-9) continue
            val t = ((w[0] - x) * sy - (w[1] - y) * sx) / den
            val u = ((w[0] - x) * dy - (w[1] - y) * dx) / den
            if (u < -0.02 || u > 1.02 || t <= 1e-6) continue
            var wnx = -sy; var wny = sx
            val wl = hypot(wnx, wny); wnx /= wl; wny /= wl
            if (wnx * dx + wny * dy > 0) { wnx = -wnx; wny = -wny }
            val tt = t - r / max(0.05, abs(wnx * dx + wny * dy))
            if (tt in 0.0..bestT) { bestT = tt; nx = wnx; ny = wny; hit = true }
        }
        for (b in hole.bumpers) {
            val ox = b.x - x; val oy = b.y - y
            val proj = ox * dx + oy * dy
            if (proj <= 0) continue
            val rr = b.r + r
            val d2 = ox * ox + oy * oy - proj * proj
            if (d2 > rr * rr) continue
            val t = proj - sqrt(rr * rr - d2)
            if (t in 0.0..bestT) { bestT = t; val hx = x + dx * t; val hy = y + dy * t; val l = hypot(hx - b.x, hy - b.y); nx = (hx - b.x) / l; ny = (hy - b.y) / l; hit = true }
        }
        x += dx * bestT; y += dy * bestT
        pts.add(x to y)
        remaining -= bestT
        if (!hit || remaining <= 1.0) break
        val dot = dx * nx + dy * ny
        dx -= 2 * dot * nx; dy -= 2 * dot * ny
        remaining *= 0.6
    }
    return pts
}

/**
 * Mini Golf: a turf course drawn in vector art (mown stripes, wooden rails, sand, rippling water, belts with
 * moving chevrons, springy bumpers, a flag on the cup), a slingshot putt (drag back anywhere and let go) with a
 * dotted preview, the same putt through nudge buttons and a power slider for TalkBack, a scorecard, and a replay of
 * every stroke from its recorded shot. Reduced Motion cuts the replay and the water and belt motion.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MiniGolfScreen(
    sessionManager: GameSessionManager,
    game: GolfGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.TOWER_DEFENCE, enabled = musicEnabled)
    val state by game.state
    val hostLeft by game.hostLeft
    val hintShot by game.hintShot
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val burst = rememberParticleBurst()
    val scope = rememberCoroutineScope()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.holeCount = if (extras["holes"] == "3") 3 else 9
        game.limit = extras["limit"]?.toIntOrNull()?.coerceIn(3, 12) ?: 6
        game.firstSeat = sessionManager.launchOptions.firstMoverIndex
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
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

    var aim by remember { mutableStateOf(0.0) }
    var power by remember { mutableFloatStateOf(0.5f) }
    var pullFrom by remember { mutableStateOf<Offset?>(null) }
    var pullTo by remember { mutableStateOf<Offset?>(null) }
    var whyKey by remember { mutableStateOf<GolfReject?>(null) }
    var showHint by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }

    var frames by remember { mutableStateOf<FloatArray?>(null) }
    var frameIdx by remember { mutableIntStateOf(0) }
    var animating by remember { mutableStateOf(false) }
    var seenStroke by remember { mutableIntStateOf(-1) }
    var seenHole by remember { mutableIntStateOf(-1) }
    val drop = remember { Animatable(0f) }
    var splash by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var timeSec by remember { mutableStateOf(0f) }

    // the water and belt motion clock
    LaunchedEffect(reduced) {
        if (reduced) { timeSec = 0f; return@LaunchedEffect }
        val t0 = androidx.compose.runtime.withFrameNanos { it }
        while (true) { androidx.compose.runtime.withFrameNanos { timeSec = ((it - t0) / 1_000_000_000.0).toFloat() % 1000f } }
    }

    LaunchedEffect(state?.strokeNo, state?.serial) {
        val s = state ?: return@LaunchedEffect
        val rec = s.last
        if (seenStroke < 0 || rec == null || s.strokeNo <= seenStroke || s.hole != seenHole) {
            seenStroke = s.strokeNo; seenHole = s.hole; frames = null
            return@LaunchedEffect
        }
        seenStroke = s.strokeNo
        animating = true
        try {
            val hole = GolfCourse.holes[s.hole]
            val sim = withContext(Dispatchers.Default) { GolfPhysics.simulate(hole, rec.startX, rec.startY, rec.shot, frameEvery = 4) }
            val fr = sim.frames ?: FloatArray(0)
            if (!reduced) {
                frames = fr
                var next = 0
                bgPlayFrames(fr.size / 2, 60f) { idx ->
                    frameIdx = idx
                    while (next < sim.events.size && sim.events[next].step <= idx * 4) {
                        val e = sim.events[next++]
                        when (e.kind) {
                            GolfEvent.WALL -> playProceduralSfx(androidContext, if (e.strength > 40) SfxKind.SOLID_THUNK else SfxKind.LIGHT_TICK, 0f)
                            GolfEvent.BUMPER -> { playProceduralSfx(androidContext, SfxKind.SOLID_THUNK, 0f); haptics(HapticSignal.LIGHT_TICK) }
                            GolfEvent.SAND -> playProceduralSfx(androidContext, SfxKind.WHOOSH, 0f)
                            GolfEvent.WATER -> { playProceduralSfx(androidContext, SfxKind.WHOOSH, 0f); splash = rec.endX to rec.endY }
                            GolfEvent.CUP -> {}
                        }
                    }
                }
                if (rec.end == GolfEnd.CUP) { drop.snapTo(0f); drop.animateTo(1f, tween(320)) }
                if (rec.end == GolfEnd.WATER) { delay(350) }
            }
            val mine = if (isNetworked) rec.seat == localSeat else !s.seats[rec.seat].isBot
            when {
                rec.end == GolfEnd.CUP -> {
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(if (mine) HapticSignal.SUCCESS else HapticSignal.LIGHT_TICK)
                    if (!reduced && mine && s.strokes[rec.seat][s.hole] <= 1) burst.spawn(Offset(0.5f, 0.4f), 70, listOf(MG_GOLD, Color.White, Color(0xFFE96A5A), Color(0xFF6BC4E8)), speedRange = 0.25f..0.8f, lifeRangeSeconds = 0.9f..1.7f, gravity = 0.9f)
                }
                rec.end == GolfEnd.WATER -> { playSfx(SfxKind.INVALID_BUZZ); if (mine) haptics(HapticSignal.FAILURE) }
                rec.hitLimit -> { playSfx(SfxKind.INVALID_BUZZ); if (mine) haptics(HapticSignal.FAILURE) }
            }
        } finally {
            animating = false; frames = null; splash = null
            drop.snapTo(0f)
        }
    }

    LaunchedEffect(state?.serial, hostLeft, animating) {
        val s = state ?: return@LaunchedEffect
        if (animating || !game.botToMove(s)) return@LaunchedEffect
        delay(gameSpeed.scale(900L).coerceAtLeast(450L))
        game.playBotTurnAsync()
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    // new turn: aim at the cup and reset the power
    LaunchedEffect(state?.serial, animating) {
        val s = state ?: return@LaunchedEffect
        if (animating) return@LaunchedEffect
        showHint = false; pullFrom = null; pullTo = null
        val hole = GolfCourse.holes[s.hole]
        aim = atan2(hole.cup.y - s.ballY[s.current], hole.cup.x - s.ballX[s.current])
        power = 0.55f
    }

    // round over: a cheer for a human winner
    LaunchedEffect(state?.phase) {
        val s = state ?: return@LaunchedEffect
        if (s.phase != GolfPhase.OVER) return@LaunchedEffect
        while (animating) delay(60)
        val totals = s.seats.indices.map { s.total(it) }
        val winners = GolfRules.winners(totals, s.parTotal, s.holeCount)
        val humanWon = winners.any { !s.seats[it].isBot && (!isNetworked || it == localSeat) }
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.35f), 90, listOf(MG_GOLD, Color.White, Color(0xFFE96A5A), Color(0xFF6BC4E8)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        } else haptics(HapticSignal.LIGHT_TICK)
    }

    val s = state
    val helpTitle = stringResource(R.string.mg_help_title)
    val helpText = stringResource(R.string.mg_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = MG_ROUGH_BOTTOM, buttonContent = MG_TEXT
            ) {
                Box(Modifier.fillMaxSize().background(MG_ROUGH_BOTTOM), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bjw_waiting_host), color = MG_TEXT, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val ctx = LocalContext.current
    val hole = GolfCourse.holes[s.hole]
    fun seatName(i: Int) = ctx.seatLabel(s.seats[i].displayName)
    fun scoreText(strokes: Int, par: Int) = ctx.getString(
        when (GolfRules.scoreName(strokes, par)) {
            GolfScoreName.HOLE_IN_ONE -> R.string.mg_score_hio
            GolfScoreName.ALBATROSS -> R.string.mg_score_albatross
            GolfScoreName.EAGLE -> R.string.mg_score_eagle
            GolfScoreName.BIRDIE -> R.string.mg_score_birdie
            GolfScoreName.PAR -> R.string.mg_score_par
            GolfScoreName.BOGEY -> R.string.mg_score_bogey
            GolfScoreName.DOUBLE_BOGEY -> R.string.mg_score_double
            GolfScoreName.TRIPLE_BOGEY -> R.string.mg_score_triple
            GolfScoreName.OVER -> R.string.mg_score_over
        }
    )
    val myTurn = game.isMyTurn() && !animating
    val canAdjust = myTurn && s.phase == GolfPhase.AIM
    val ball = s.ballX[s.current] to s.ballY[s.current]

    fun currentShot() = GolfShot(aim, power.toDouble())
    fun fire(shot: GolfShot = currentShot()) {
        val r = game.explainPutt(shot)
        if (r == GolfReject.NONE) { whyKey = null; showHint = false; game.putt(shot) }
        else { whyKey = r; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
    }

    val whyText = whyKey?.let { k ->
        ctx.getString(
            when (k) {
                GolfReject.OVER -> R.string.mg_why_over
                GolfReject.NOT_YOUR_TURN -> R.string.mg_why_wait
                GolfReject.BAD_POWER -> R.string.mg_why_power
                else -> R.string.mg_why_shot
            }
        )
    }

    val statusText = run {
        val rec = s.last
        val last = when {
            rec == null -> ""
            rec.end == GolfEnd.CUP -> ctx.sayFor(s.seats[rec.seat].displayName, R.string.mg_last_cup, R.string.mg_last_cup_you,
                s.strokes[rec.seat][s.hole], scoreText(s.strokes[rec.seat][s.hole], hole.par))
            rec.end == GolfEnd.WATER -> ctx.sayFor(s.seats[rec.seat].displayName, R.string.mg_last_water, R.string.mg_last_water_you)
            rec.hitLimit -> ctx.sayFor(s.seats[rec.seat].displayName, R.string.mg_last_limit, R.string.mg_last_limit_you)
            else -> ""
        }
        val turn = when {
            s.phase != GolfPhase.AIM -> ""
            hostLeft -> ctx.getString(R.string.bjw_host_left)
            animating -> ""
            s.seats[s.current].isBot -> ctx.getString(R.string.mg_cpu_thinking, seatName(s.current))
            else -> ctx.sayFor(s.seats[s.current].displayName, R.string.mg_turn_named, R.string.mg_turn_you, s.strokes[s.current][s.hole] + 1)
        }
        listOf(last, turn).filter { it.isNotBlank() }.joinToString(" ")
    }

    val hazards = buildList {
        if (hole.sand.isNotEmpty()) add(ctx.getString(R.string.mg_haz_sand))
        if (hole.water.isNotEmpty()) add(ctx.getString(R.string.mg_haz_water))
        if (hole.bumpers.isNotEmpty()) add(ctx.getString(R.string.mg_haz_bumpers))
        if (hole.ramps.isNotEmpty()) add(ctx.getString(R.string.mg_haz_belts))
        if (hole.obstacles.isNotEmpty()) add(ctx.getString(R.string.mg_haz_walls))
    }
    val holeName = ctx.getString(
        intArrayOf(
            R.string.mg_hole_name_1, R.string.mg_hole_name_2, R.string.mg_hole_name_3, R.string.mg_hole_name_4, R.string.mg_hole_name_5,
            R.string.mg_hole_name_6, R.string.mg_hole_name_7, R.string.mg_hole_name_8, R.string.mg_hole_name_9
        )[s.hole.coerceIn(0, 8)]
    )
    val fieldDesc = ctx.getString(R.string.mg_field_desc, s.hole + 1, holeName, hole.par, if (hazards.isEmpty()) ctx.getString(R.string.mg_haz_none) else hazards.joinToString(", "))

    val canCoach = GameCoach.forGame("mini-golf") != null
    val (_, openCoach) = rememberClCoach("mini-golf", ready = canCoach && s.strokeNo == 0 && s.hole == 0)
    val finishedRounds = s.seats.sumOf { it.wins }
    val leaveTitle = stringResource(R.string.bjw_leave_title)
    val leaveBody = stringResource(if (isNetworked) R.string.bjw_leave_body_net else R.string.mg_leave_body)
    val seatColors = LudoSeatColors

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = s.phase != GolfPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRounds > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = !isNetworked && finishedRounds == 0,
        onUndo = if (game.canUndo) ({ game.undo() }) else null,
        canUndo = game.canUndo && !animating,
        onHint = if (!isNetworked && canAdjust) ({ scope.launch { game.requestHint()?.let { aim = it.angle; power = it.power.toFloat(); showHint = true } } }) else null,
        buttonFill = MG_ROUGH_BOTTOM, buttonContent = MG_TEXT,
        leaveTitle = leaveTitle, leaveBody = leaveBody
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MG_ROUGH_TOP, MG_ROUGH_BOTTOM)))) {
            val landscape = maxWidth > maxHeight * 1.15f
            val density = LocalDensity.current

            val controls: @Composable () -> Unit = {
              Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ClStatusLine(statusText, MG_TEXT, Modifier.fillMaxWidth().padding(vertical = 2.dp), emphasized = true)
                    ClWhyLine(whyText ?: (if (showHint && canAdjust) stringResource(R.string.mg_hint_line) else null), Color(0xFF1B1B1B), MG_GOLD)
                    when (s.phase) {
                        GolfPhase.HOLE_DONE -> {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.mg_hole_done, s.hole + 1), color = MG_GOLD, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                                s.seats.indices.forEach { i ->
                                    Text(
                                        stringResource(R.string.mg_hole_result, seatName(i), s.strokes[i][s.hole], scoreText(s.strokes[i][s.hole], hole.par)),
                                        color = MG_TEXT, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        GolfPhase.OVER -> {
                            val totals = s.seats.indices.map { s.total(it) }
                            val winners = GolfRules.winners(totals, s.parTotal, s.holeCount)
                            val solo = s.seats.size == 1
                            ClFinishedPanel(
                                title = when {
                                    solo -> stringResource(R.string.mg_over_solo, totals[0], GolfRules.relativeText(totals[0], s.parTotal))
                                    winners.isEmpty() -> stringResource(R.string.mg_over_tie)
                                    else -> youAware(s.seats[winners[0]].displayName, R.string.mg_over_win, R.string.mg_over_win_you)
                                },
                                subtitle = if (isNetworked && !solo && winners.isNotEmpty()) stringResource(if (winners[0] == localSeat) R.string.ccl_you_won else R.string.ccl_you_lost) else null,
                                lines = s.seats.indices.map { stringResource(R.string.mg_final_line, LocalContext.current.seatLabel(s.seats[it].displayName), totals[it], GolfRules.relativeText(totals[it], s.parTotal)) },
                                canPlayAgain = !hostLeft && !animating,
                                onPlayAgain = { game.playAgain() },
                                onLeave = { game.leaveSession() }
                            )
                            OutlinedButton(onClick = { showCard = true }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MG_TEXT)) {
                                Text(stringResource(R.string.mg_scorecard))
                            }
                        }
                        else -> if (canAdjust) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(onClick = { aim -= 0.5 * PI / 180 }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MG_TEXT)) { Text(stringResource(R.string.mg_aim_left)) }
                                OutlinedButton(onClick = { aim += 0.5 * PI / 180 }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MG_TEXT)) { Text(stringResource(R.string.mg_aim_right)) }
                                OutlinedButton(onClick = { aim = atan2(hole.cup.y - ball.second, hole.cup.x - ball.first) }, modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MG_TEXT)) { Text(stringResource(R.string.mg_aim_cup)) }
                            }
                            val powerDesc = stringResource(R.string.mg_power_desc, (power * 100).roundToInt())
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.mg_power), color = MG_TEXT, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = power, onValueChange = { power = it }, valueRange = 0.02f..1f,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 8.dp)
                                        .semantics { contentDescription = powerDesc; stateDescription = powerDesc }
                                )
                                Text("${(power * 100).roundToInt()}%", color = MG_GOLD, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // Putt / Next hole stay on screen under the scrolling controls (they were pushed below the fold in landscape and at large fonts).
                if (s.phase == GolfPhase.HOLE_DONE) {
                    Button(
                                onClick = { game.nextHole() }, enabled = !animating && !hostLeft,
                                modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 160.dp)
                            ) { Text(stringResource(if (s.hole == s.holeCount - 1) R.string.mg_finish else R.string.mg_next_hole), fontWeight = FontWeight.Bold) }
                } else if (s.phase != GolfPhase.OVER && canAdjust) {
                    Button(onClick = { fire() }, enabled = !animating, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 160.dp)) {
                        Text(stringResource(R.string.mg_putt), fontWeight = FontWeight.Bold)
                    }
                    Text(stringResource(R.string.mg_drag_hint), color = MG_TEXT.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp))
                }
              }
            }

            val fieldArea: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.clipToBounds()) {
                    val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
                    val view = remember(wPx, hPx) { makeGolfView(wPx, hPx) }
                    val latestView = rememberUpdatedState(view)
                    val latestCan = rememberUpdatedState(canAdjust)
                    val latestFire = rememberUpdatedState({ shot: GolfShot -> fire(shot) })
                    val maxPull = 120f * density.density
                    Canvas(
                        Modifier.fillMaxSize()
                            .semantics { contentDescription = fieldDesc }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!latestCan.value) return@awaitEachGesture
                                    pullFrom = down.position; pullTo = down.position
                                    drag(down.id) { change ->
                                        pullTo = change.position
                                        val dxs = down.position.x - change.position.x; val dys = down.position.y - change.position.y
                                        val len = hypot(dxs, dys)
                                        if (len > 6f) {
                                            val v = latestView.value
                                            // the putt goes opposite to the drag; convert the screen direction back to field space
                                            val fd = if (v.rot) Pair(dys.toDouble(), -dxs.toDouble()) else Pair(dxs.toDouble(), dys.toDouble())
                                            aim = atan2(fd.second, fd.first)
                                            power = (len / maxPull).coerceIn(0.02f, 1f)
                                        }
                                        change.consume()
                                    }
                                    val end = pullTo; val start = pullFrom
                                    pullFrom = null; pullTo = null
                                    if (start != null && end != null && hypot(start.x - end.x, start.y - end.y) > 0.12f * maxPull) {
                                        latestFire.value(GolfShot(aim, power.toDouble()))
                                    }
                                }
                            }
                    ) {
                        drawGolfHole(view, hole, timeSec, dark, reduced)
                        val r = (GolfPhysics.BALL_R * view.scale).toFloat()
                        val fr = frames
                        // other players' balls (static), then the active ball
                        for (i in s.seats.indices) {
                            if (i == s.current && (animating || canAdjust || s.phase == GolfPhase.AIM)) continue
                            if (s.done[i][s.hole] && s.last?.seat != i) continue
                            drawMarble(view.at(s.ballX[i], s.ballY[i]), r * 1.05f, seatColors[i % 4], i % 4, glyph = if (colorblind) 0.7f else 0.5f)
                        }
                        val rec = s.last
                        val mover = if (animating && rec != null) rec.seat else s.current
                        var bx = s.ballX[mover]; var by = s.ballY[mover]
                        var visible = true
                        var scale = 1f
                        if (animating && rec != null) {
                            if (fr != null && frameIdx * 2 + 1 < fr.size) { bx = fr[frameIdx * 2].toDouble(); by = fr[frameIdx * 2 + 1].toDouble() }
                            else if (fr == null) { bx = rec.startX; by = rec.startY }
                            if (rec.end == GolfEnd.CUP && drop.value > 0f) { scale = 1f - drop.value; bx = hole.cup.x; by = hole.cup.y }
                            if (rec.end == GolfEnd.WATER && fr != null && frameIdx >= fr.size / 2 - 1) visible = false
                        } else if (s.done[mover][s.hole] && rec?.end == GolfEnd.CUP) visible = false
                        val center = view.at(bx, by)
                        // slingshot band and dotted preview
                        if (canAdjust && !animating) {
                            val pts = previewPath(hole, ball.first, ball.second, aim, power.toDouble())
                            val dash = PathEffect.dashPathEffect(floatArrayOf(r * 0.9f, r * 0.9f))
                            val col = if (showHint && hintShot != null) MG_GOLD else Color.White.copy(alpha = 0.9f)
                            for (i in 0 until pts.size - 1) drawLine(col, view.at(pts[i].first, pts[i].second), view.at(pts[i + 1].first, pts[i + 1].second), strokeWidth = max(2f, r * 0.3f), pathEffect = dash, cap = StrokeCap.Round)
                            val d = view.dir(cos(aim), sin(aim))
                            val back = Offset(center.x - d.x * (r * 2f + power * 7f * view.scale), center.y - d.y * (r * 2f + power * 7f * view.scale))
                            drawLine(Brush.linearGradient(listOf(Color(0xFF7BE06C), Color(0xFFFFD866), Color(0xFFE5533C)), center, back), center, back, strokeWidth = r * 0.9f, cap = StrokeCap.Round)
                        }
                        if (visible && scale > 0.02f) {
                            drawGolfBall(center, r * scale)
                            if (s.seats.size == 1) drawMarble(center, r * scale * 1.05f, Color(0xFFF7F7F2), 0, glyph = 0f)
                            else drawMarble(center, r * scale * 1.05f, seatColors[mover % 4], mover % 4, glyph = if (colorblind) 0.7f else 0.5f)
                        }
                        splash?.let { (sx, sy) ->
                            val c = view.at(sx, sy)
                            drawCircle(Color.White.copy(alpha = 0.7f), r * 2.2f, c, style = Stroke(r * 0.3f))
                            drawCircle(Color.White.copy(alpha = 0.4f), r * 3.4f, c, style = Stroke(r * 0.2f))
                        }
                        burst.particles.value.forEach { p ->
                            drawCircle(p.color.copy(alpha = p.lifeFraction), size.minDimension * 0.012f + 3f, Offset(p.pos.x * size.width, p.pos.y * size.height))
                        }
                    }
                }
            }

            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = GameChromeEndInset, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canCoach) ClCoachButton("mini-golf", openCoach)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.mg_hole_line, s.hole + 1, s.holeCount, hole.par) + "  ·  " + holeName,
                            color = MG_TEXT, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() }
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.seats.indices.forEach { i ->
                                val total = s.total(i)
                                val line = seatName(i) + (if (isNetworked && i == localSeat) " " + stringResource(R.string.bjw_you_suffix) else "") +
                                    " " + (s.strokes[i][s.hole]) + " · " + GolfRules.relativeText(total, s.parSoFar(i))
                                val active = s.current == i && s.phase == GolfPhase.AIM
                                Row(
                                    Modifier.clip(RoundedCornerShape(10.dp)).background(if (active) Color(0x33FFD866) else Color(0x22000000))
                                        .border(if (active) 2.dp else 1.dp, if (active) MG_GOLD else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = ctx.getString(R.string.mg_seat_desc, seatName(i), s.strokes[i][s.hole], GolfRules.relativeText(total, s.parSoFar(i)))
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ClSeatBadge(seatColors[i % 4], i % 4, 20)
                                    Spacer(Modifier.width(4.dp))
                                    Text(line, color = MG_TEXT, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { showCard = true }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MG_TEXT)) {
                        Text(stringResource(R.string.mg_scorecard), maxLines = 1)
                    }
                }
                if (landscape) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        fieldArea(Modifier.weight(1f).fillMaxHeight().padding(4.dp))
                        Box(Modifier.width(300.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { controls() }
                    }
                } else {
                    fieldArea(Modifier.weight(1f).fillMaxWidth().padding(4.dp))
                    Box(Modifier.fillMaxWidth().heightIn(max = 280.dp)) { controls() }
                }
            }
        }
    }

    if (showCard) {
        AlertDialog(
            onDismissRequest = { showCard = false },
            confirmButton = { TextButton(onClick = { showCard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mg_close)) } },
            title = { Text(stringResource(R.string.mg_scorecard)) },
            text = { Scorecard(s) }
        )
    }
}

@Composable
private fun Scorecard(s: GolfState) {
    val ctx = LocalContext.current
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        fun cell(text: String, bold: Boolean = false, w: Int = 34) = @Composable {
            Text(text, modifier = Modifier.width(w.dp).padding(2.dp), textAlign = TextAlign.Center, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mg_card_hole), Modifier.width(92.dp).padding(2.dp), fontWeight = FontWeight.Bold, maxLines = 1)
            (1..s.holeCount).forEach { cell("$it", true)() }
            cell(stringResource(R.string.mg_card_total), true, 52)()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mg_card_par), Modifier.width(92.dp).padding(2.dp), maxLines = 1)
            (0 until s.holeCount).forEach { cell("${GolfCourse.holes[it].par}")() }
            cell("${s.parTotal}", true, 52)()
        }
        s.seats.indices.forEach { i ->
            val rowDesc = ctx.getString(R.string.mg_card_row_desc, ctx.seatLabel(s.seats[i].displayName), s.total(i), GolfRules.relativeText(s.total(i), s.parSoFar(i)))
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = rowDesc }, verticalAlignment = Alignment.CenterVertically) {
                Text(ctx.seatLabel(s.seats[i].displayName), Modifier.width(92.dp).padding(2.dp), maxLines = 1, fontWeight = FontWeight.Bold)
                (0 until s.holeCount).forEach { h ->
                    cell(if (s.done[i][h]) "${s.strokes[i][h]}" else if (s.strokes[i][h] > 0) "${s.strokes[i][h]}*" else "-")()
                }
                cell("${s.total(i)}", true, 52)()
            }
        }
    }
}

// ---- drawing --------------------------------------------------------------------------------------

private fun polyPath(view: GolfView, pts: List<com.gamesuite.games.minigolf.GolfPt>): Path = Path().apply {
    pts.forEachIndexed { i, p -> val o = view.at(p.x, p.y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
    close()
}

private fun DrawScope.drawGolfHole(view: GolfView, hole: GolfHole, time: Float, dark: Boolean, reduced: Boolean) {
    val s = view.scale
    val wall = (MG_WALL * s).toFloat()
    val outline = polyPath(view, hole.outline)
    // wooden outer wall: a wide stroke, then the turf is painted over its inner half
    drawPath(outline, bgShade(MG_WOOD, -0.25f), style = Stroke(wall * 2.2f, join = StrokeJoin.Round))
    drawPath(outline, MG_WOOD, style = Stroke(wall * 2f, join = StrokeJoin.Round))
    drawPath(outline, Brush.linearGradient(listOf(MG_GRASS_LIGHT, MG_GRASS_DARK), Offset.Zero, Offset(size.width, size.height)))
    // mown stripes
    clipPath(outline) {
        var k = 0
        var sx = -wall
        val stripe = 6f * s
        while (sx < size.width + stripe) {
            if (k % 2 == 0) drawRect(Color(0x14FFFFFF), Offset(sx, 0f), Size(stripe, size.height))
            sx += stripe; k++
        }
        // sand
        for (r in hole.sand) {
            val a = view.at(r.x0, r.y0); val b = view.at(r.x1, r.y1)
            val tl = Offset(min(a.x, b.x), min(a.y, b.y)); val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
            drawRoundRect(MG_SAND, tl, sz, CornerRadius(wall * 0.6f))
            var gx = 0
            while (gx < 40) {
                val px = tl.x + ((gx * 37) % 100) / 100f * sz.width; val py = tl.y + ((gx * 61) % 100) / 100f * sz.height
                drawCircle(Color(0x33806A30), max(1.2f, s * 0.22f), Offset(px, py))
                gx++
            }
        }
        // water
        for (r in hole.water) {
            val a = view.at(r.x0, r.y0); val b = view.at(r.x1, r.y1)
            val tl = Offset(min(a.x, b.x), min(a.y, b.y)); val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
            drawRoundRect(Brush.verticalGradient(listOf(bgShade(MG_WATER, 0.15f), MG_WATER, bgShade(MG_WATER, -0.2f)), tl.y, tl.y + sz.height), tl, sz, CornerRadius(wall * 0.5f))
            var yy = tl.y + 6f
            var row = 0
            while (yy < tl.y + sz.height) {
                val phase = if (reduced) 0f else time * 2f + row
                val path = Path().apply {
                    var xx = tl.x
                    moveTo(xx, yy)
                    while (xx < tl.x + sz.width) { xx += 8f; lineTo(xx, yy + kotlin.math.sin(phase + xx * 0.05f) * 2f) }
                }
                drawPath(path, Color(0x55FFFFFF), style = Stroke(1.6f))
                yy += max(9f, s * 2.4f); row++
            }
        }
        // belts: moving chevrons
        for (rp in hole.ramps) {
            val a = view.at(rp.area.x0, rp.area.y0); val b = view.at(rp.area.x1, rp.area.y1)
            val tl = Offset(min(a.x, b.x), min(a.y, b.y)); val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
            drawRect(Color(0x33202020), tl, sz)
            val d = view.dir(rp.ax, rp.ay)
            val dl = hypot(d.x, d.y).coerceAtLeast(1e-6f)
            val ux = d.x / dl; val uy = d.y / dl
            val gap = 7f * s
            val shift = if (reduced) 0f else (time * 14f * s) % gap
            clipPath(Path().apply { addRect(androidx.compose.ui.geometry.Rect(tl, sz)) }) {
                val cx0 = tl.x + sz.width / 2; val cy0 = tl.y + sz.height / 2
                val span = (sz.width + sz.height)
                var t = -span
                while (t < span) {
                    val c = Offset(cx0 + ux * (t + shift), cy0 + uy * (t + shift))
                    val px = -uy; val py = ux
                    val arm = 2.6f * s
                    val chev = Path().apply {
                        moveTo(c.x - ux * arm * 0.6f + px * arm, c.y - uy * arm * 0.6f + py * arm)
                        lineTo(c.x + ux * arm * 0.6f, c.y + uy * arm * 0.6f)
                        lineTo(c.x - ux * arm * 0.6f - px * arm, c.y - uy * arm * 0.6f - py * arm)
                    }
                    drawPath(chev, Color(0xCCFFE08A), style = Stroke(max(2f, s * 0.6f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    t += gap
                }
            }
        }
    }
    // obstacles: raised wooden blocks
    for (ob in hole.obstacles) {
        val p = polyPath(view, ob)
        drawPath(p, Color(0x66000000), style = Stroke(wall * 2.4f, join = StrokeJoin.Round))
        drawPath(p, bgShade(MG_WOOD, -0.2f), style = Stroke(wall * 2f, join = StrokeJoin.Round))
        drawPath(p, MG_WOOD)
        drawPath(p, MG_WOOD_HI, style = Stroke(max(1.5f, s * 0.35f), join = StrokeJoin.Round))
    }
    // wall highlight along the outer rail
    drawPath(outline, MG_WOOD_HI, style = Stroke(max(1.5f, s * 0.35f), join = StrokeJoin.Round))
    // bumpers
    for (b in hole.bumpers) {
        val c = view.at(b.x, b.y)
        val br = (b.r * s).toFloat()
        drawCircle(Color(0x55000000), br, Offset(c.x + br * 0.12f, c.y + br * 0.18f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFF8A7A), Color(0xFFD1342B), Color(0xFF8E1B16)), center = Offset(c.x - br * 0.3f, c.y - br * 0.3f), radius = br * 1.4f), br, c)
        drawCircle(Color.White, br * 0.62f, c, style = Stroke(br * 0.18f))
        drawCircle(Color.White.copy(alpha = 0.55f), br * 0.14f, Offset(c.x - br * 0.38f, c.y - br * 0.42f))
    }
    // tee mat and the cup with its flag
    val tee = view.at(hole.tee.x, hole.tee.y)
    drawCircle(Color(0x33000000), (3.2 * s).toFloat(), tee)
    drawCircle(Color(0x66FFFFFF), (3.2 * s).toFloat(), tee, style = Stroke(max(1.5f, s * 0.3f)))
    val cup = view.at(hole.cup.x, hole.cup.y)
    val cr = (GolfPhysics.CUP_R * s).toFloat()
    drawCircle(Color(0xFF1A1A1A), cr * 1.12f, cup)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF000000), Color(0xFF2A2A2A)), center = cup, radius = cr), cr, cup)
    val top = Offset(cup.x, cup.y - cr * 5.2f)
    drawLine(Color(0xFFF5F5F0), cup, top, strokeWidth = max(2f, s * 0.5f), cap = StrokeCap.Round)
    val flag = Path().apply { moveTo(top.x, top.y); lineTo(top.x + cr * 2.6f, top.y + cr * 0.9f); lineTo(top.x, top.y + cr * 1.8f); close() }
    drawPath(flag, Color(0xFFE5533C))
}

private fun DrawScope.drawGolfBall(c: Offset, r: Float) {
    if (r < 0.5f) return
    drawCircle(Color(0x55000000), r, Offset(c.x + r * 0.15f, c.y + r * 0.25f))
}
