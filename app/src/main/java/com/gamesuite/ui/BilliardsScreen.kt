package com.gamesuite.ui

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import com.gamesuite.games.billiards.BilliardsGame
import com.gamesuite.games.billiards.BilliardsPhase
import com.gamesuite.games.billiards.BilliardsPhysics
import com.gamesuite.games.billiards.BilliardsRules
import com.gamesuite.games.billiards.BilliardsState
import com.gamesuite.games.billiards.BilliardsTable
import com.gamesuite.games.billiards.BilliardsVariant
import com.gamesuite.games.billiards.FoulKind
import com.gamesuite.games.billiards.Shot
import com.gamesuite.games.billiards.ShotReject
import com.gamesuite.games.billiards.SimEvent
import com.gamesuite.games.billiards.WinReason
import com.gamesuite.games.cards.CardSounds
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

private val BL_FELT_LIGHT = Color(0xFF218F68)
private val BL_FELT_DARK = Color(0xFF0D5A42)
private val BL_RAIL = Color(0xFF6A3E1C)
private val BL_RAIL_HI = Color(0xFF9A6434)
private val BL_BG_TOP = Color(0xFF1B2A25)
private val BL_BG_BOTTOM = Color(0xFF0E1714)
private val BL_TEXT = Color(0xFFF2F7F4)
private val BL_GOLD = Color(0xFFF4D27A)
private const val BL_RAIL_W = 5.0

/** Standard ball colours by number 1..8 (9..15 reuse 1..7 as stripes). */
private val BL_BALL = intArrayOf(
    0xFFF8F8F2.toInt(), 0xFFF2BE00.toInt(), 0xFF1F4FBF.toInt(), 0xFFD92B2B.toInt(), 0xFF5E2D8C.toInt(),
    0xFFF58A1F.toInt(), 0xFF1B8C47.toInt(), 0xFF7E2222.toInt(), 0xFF141414.toInt()
)

private fun ballColor(id: Int): Color = Color(BL_BALL[if (id == 0) 0 else if (id <= 8) id else id - 8])

/** Maps table units to screen pixels. In portrait the long side runs down the screen. */
private class TableView(val rot: Boolean, val scale: Float, val ox: Float, val oy: Float) {
    fun sx(x: Double, y: Double): Float = if (rot) ox + ((BilliardsTable.H - y) * scale).toFloat() else ox + (x * scale).toFloat()
    fun sy(x: Double, y: Double): Float = if (rot) oy + (x * scale).toFloat() else oy + (y * scale).toFloat()
    fun at(x: Double, y: Double) = Offset(sx(x, y), sy(x, y))
    fun toTable(p: Offset): Pair<Double, Double> =
        if (rot) Pair(((p.y - oy) / scale).toDouble(), BilliardsTable.H - ((p.x - ox) / scale).toDouble())
        else Pair(((p.x - ox) / scale).toDouble(), ((p.y - oy) / scale).toDouble())
    /** Table-unit direction to screen-unit direction. */
    fun dir(dx: Double, dy: Double): Offset = if (rot) Offset((-dy).toFloat(), dx.toFloat()) else Offset(dx.toFloat(), dy.toFloat())
}

private fun makeView(w: Float, h: Float): TableView {
    val bw = (BilliardsTable.W + 2 * BL_RAIL_W).toFloat(); val bh = (BilliardsTable.H + 2 * BL_RAIL_W).toFloat()
    val rot = h > w * 1.05f
    val ew = if (rot) bh else bw; val eh = if (rot) bw else bh
    val scale = min(w / ew, h / eh).coerceAtLeast(0.01f)
    val cw = (if (rot) BilliardsTable.H else BilliardsTable.W).toFloat() * scale
    val ch = (if (rot) BilliardsTable.W else BilliardsTable.H).toFloat() * scale
    return TableView(rot, scale, (w - cw) / 2f, (h - ch) / 2f)
}

/** First thing the cue ball meets along [angle]: a ball (id >= 1) or a cushion (-1), with the cue centre there. */
private class AimHit(val ball: Int, val t: Double, val cx: Double, val cy: Double)

private fun traceAim(s: BilliardsState, px: Double, py: Double, angle: Double): AimHit {
    val dx = cos(angle); val dy = sin(angle)
    var bestT = Double.MAX_VALUE; var bestBall = -1
    val r2 = 2 * BilliardsTable.R
    for (b in 1 until BilliardsTable.BALLS) {
        if (!s.onTable[b]) continue
        val ox = s.x[b] - px; val oy = s.y[b] - py
        val proj = ox * dx + oy * dy
        if (proj <= 0) continue
        val d2 = ox * ox + oy * oy - proj * proj
        if (d2 > r2 * r2) continue
        val t = proj - sqrt(r2 * r2 - d2)
        if (t < bestT) { bestT = t; bestBall = b }
    }
    val lo = BilliardsTable.R; val hiX = BilliardsTable.W - BilliardsTable.R; val hiY = BilliardsTable.H - BilliardsTable.R
    var tw = Double.MAX_VALUE
    if (dx > 1e-9) tw = min(tw, (hiX - px) / dx) else if (dx < -1e-9) tw = min(tw, (lo - px) / dx)
    if (dy > 1e-9) tw = min(tw, (hiY - py) / dy) else if (dy < -1e-9) tw = min(tw, (lo - py) / dy)
    return if (bestT < tw) AimHit(bestBall, bestT, px + dx * bestT, py + dy * bestT) else AimHit(-1, tw, px + dx * tw, py + dy * tw)
}

/**
 * Billiards: a felt table with wooden rails and brass-ringed pockets, glossy numbered balls (solids have a
 * number, stripes a band and a number, so no colour has to be told apart), a cue stick that draws back with the
 * power, an aim line with a ghost ball, a spin pad for english and follow / draw, ball in hand placement, and a
 * replay of every stroke from the recorded shot. Reduced Motion cuts the replay; everything is labelled for
 * TalkBack (including quick "aim at ball N" buttons, nudge buttons and spin actions) and announced politely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BilliardsScreen(
    sessionManager: GameSessionManager,
    game: BilliardsGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.AIR_HOCKEY, enabled = musicEnabled)
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
        game.variant = BilliardsVariant.fromOption(sessionManager.launchOptions.extras["variant"])
        game.firstBreaker = sessionManager.launchOptions.firstMoverIndex
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

    // ---- local controls ----
    var aim by remember { mutableStateOf(0.0) }
    var power by remember { mutableFloatStateOf(0.6f) }
    var spinX by remember { mutableFloatStateOf(0f) }
    var spinY by remember { mutableFloatStateOf(0f) }
    var place by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var whyKey by remember { mutableStateOf<ShotReject?>(null) }
    var showHint by remember { mutableStateOf(false) }

    // ---- replay of the last stroke ----
    var frames by remember { mutableStateOf<List<FloatArray>?>(null) }
    var frameIdx by remember { mutableIntStateOf(0) }
    var animating by remember { mutableStateOf(false) }
    var seenShot by remember { mutableIntStateOf(-1) }
    val strike = remember { Animatable(0f) }
    val flashes = remember { mutableStateListOf<Triple<Double, Double, Long>>() }
    var clockMs by remember { mutableStateOf(0L) }

    LaunchedEffect(state?.shotNo, state?.serial) {
        val s = state ?: return@LaunchedEffect
        val rec = s.lastShot
        if (seenShot < 0 || rec == null || s.shotNo <= seenShot) {
            seenShot = s.shotNo; frames = null
            return@LaunchedEffect
        }
        seenShot = s.shotNo
        val mine = if (isNetworked) rec.shooter == localSeat else !s.seats[rec.shooter].isBot
        animating = true
        try {
            if (!reduced) {
                if (mine) {
                    strike.snapTo(0f)
                    strike.animateTo(1f, tween(gameSpeed.scale(170L).toInt().coerceAtLeast(90)))
                }
                val sim = withContext(Dispatchers.Default) {
                    BilliardsPhysics.simulate(rec.startX.toDoubleArray(), rec.startY.toDoubleArray(), rec.startOn.toBooleanArray(), rec.shot, frameEvery = 4)
                }
                val fr = sim.frames ?: emptyList()
                frames = fr
                var next = 0
                var lastSound = -1000
                bgPlayFrames(fr.size, 60f) { idx ->
                    frameIdx = idx
                    clockMs = idx * 1000L / 60
                    var loudest: SimEvent? = null
                    while (next < sim.events.size && sim.events[next].step <= idx * 4) {
                        val e = sim.events[next]
                        if (e.kind == SimEvent.POCKET) {
                            flashes.add(Triple(BilliardsTable.POCKET_X[e.b], BilliardsTable.POCKET_Y[e.b], clockMs))
                            playProceduralSfx(androidContext, SfxKind.SOLID_THUNK, 0f)
                            haptics(HapticSignal.NORMAL_ACTION)
                        } else if (loudest == null || e.strength > loudest.strength) loudest = e
                        next++
                    }
                    val l = loudest
                    if (l != null && idx - lastSound >= 5) {
                        lastSound = idx
                        val pan = ((fr[idx][max(l.a, 0) * 2] / BilliardsTable.W) * 2 - 1).toFloat().coerceIn(-0.8f, 0.8f)
                        if (l.strength > 55.0) playProceduralSfx(androidContext, SfxKind.SOLID_THUNK, pan)
                        else if (l.strength > 6.0) playProceduralSfx(androidContext, SfxKind.LIGHT_TICK, pan)
                    }
                }
                delay(120)
            }
            if (rec.fouls.isNotEmpty()) {
                if (rec.shooter == (if (isNetworked) localSeat else rec.shooter)) { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
            } else if (rec.pocketed.isNotEmpty() && mine) haptics(HapticSignal.STRONG_ACTION)
        } finally {
            animating = false
            frames = null
            strike.snapTo(0f)
        }
    }

    // flashes fade
    LaunchedEffect(clockMs) { flashes.removeAll { clockMs - it.third > 500 || clockMs < it.third } }

    // computer shots, on a gentle clock
    LaunchedEffect(state?.serial, hostLeft, animating) {
        val s = state ?: return@LaunchedEffect
        if (animating || !game.botToMove(s)) return@LaunchedEffect
        delay(gameSpeed.scale(900L).coerceAtLeast(450L))
        game.playBotTurnAsync()
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    // new turn: aim at the nearest legal ball, centre the spin, put the cue ball down
    LaunchedEffect(state?.serial, animating) {
        val s = state ?: return@LaunchedEffect
        if (animating) return@LaunchedEffect
        place = null; spinX = 0f; spinY = 0f; showHint = false
        val targets = BilliardsRules.legalTargets(s.variant, s.groups, s.shooter, s.ballsOnTable(), s.isBreak)
        val t = targets.minByOrNull { hypot(s.x[it] - s.x[0], s.y[it] - s.y[0]) }
        if (t != null) aim = atan2(s.y[t] - s.y[0], s.x[t] - s.x[0])
        power = if (s.isBreak) 0.9f else 0.55f
    }

    // game over: celebration for a human winner
    LaunchedEffect(state?.phase, state?.winner) {
        val s = state ?: return@LaunchedEffect
        if (s.phase != BilliardsPhase.OVER) return@LaunchedEffect
        while (animating) delay(60)
        val humanWon = s.winner >= 0 && !s.seats[s.winner].isBot && (!isNetworked || s.winner == localSeat)
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.35f), 90, listOf(BL_GOLD, Color.White, Color(0xFFE96A5A), Color(0xFF6BC4E8)),
                speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        } else if (s.winner >= 0 && isNetworked) { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
        else haptics(HapticSignal.LIGHT_TICK)
    }

    val s = state
    val helpTitle = stringResource(R.string.bl_help_title)
    val helpText = stringResource(R.string.bl_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = BL_BG_BOTTOM, buttonContent = BL_TEXT
            ) {
                Box(Modifier.fillMaxSize().background(BL_BG_BOTTOM), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bjw_waiting_host), color = BL_TEXT, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val ctx = LocalContext.current
    fun seatName(i: Int) = ctx.seatLabel(s.seats[i].displayName)
    val myTurn = game.isMyTurn() && !animating
    val canAdjust = myTurn && s.phase == BilliardsPhase.AIM
    val cuePos: Pair<Double, Double> = if (s.ballInHand && canAdjust) (place ?: (s.x[0] to s.y[0])) else (s.x[0] to s.y[0])
    val targets = BilliardsRules.legalTargets(s.variant, s.groups, s.shooter, s.ballsOnTable(), s.isBreak)

    fun currentShot() = Shot(aim, power.toDouble(), spinX.toDouble(), spinY.toDouble(), cuePos.first, cuePos.second)
    fun fire() {
        val shot = currentShot()
        val r = game.explainShot(shot)
        if (r == ShotReject.NONE) { whyKey = null; showHint = false; game.shoot(shot) }
        else { whyKey = r; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
    }
    fun applyHint(h: Shot) {
        aim = h.angle; power = h.power.toFloat(); spinX = h.spinX.toFloat(); spinY = h.spinY.toFloat()
        if (s.ballInHand) place = h.cueX to h.cueY
        showHint = true
    }

    val whyText = whyKey?.let { k ->
        ctx.getString(
            when (k) {
                ShotReject.OVER -> R.string.bl_why_over
                ShotReject.NOT_YOUR_TURN -> R.string.bl_why_wait
                ShotReject.BAD_POWER -> R.string.bl_why_power
                ShotReject.BAD_PLACEMENT -> R.string.bl_why_place
                else -> R.string.bl_why_shot
            }
        )
    }

    fun foulText(kind: String) = ctx.getString(
        when (kind) {
            FoulKind.SCRATCH -> R.string.bl_foul_scratch
            FoulKind.NO_CONTACT -> R.string.bl_foul_no_contact
            FoulKind.WRONG_BALL -> R.string.bl_foul_wrong_ball
            FoulKind.NO_RAIL -> R.string.bl_foul_no_rail
            FoulKind.BAD_BREAK -> R.string.bl_foul_bad_break
            else -> R.string.bl_foul_three
        }
    )

    fun groupName(g: Int) = ctx.getString(if (g == 1) R.string.bl_group_solids else if (g == 2) R.string.bl_group_stripes else R.string.bl_group_open)

    val statusText = run {
        val rec = s.lastShot
        val last = when {
            s.phase == BilliardsPhase.OVER -> {
                val winner = seatName(s.winner)
                val head = ctx.sayFor(s.seats[s.winner].displayName, R.string.bl_over_win, R.string.bl_over_win_you)
                val reason = when (s.winReason) {
                    WinReason.EIGHT_EARLY -> ctx.getString(R.string.bl_over_eight_early)
                    WinReason.EIGHT_FOUL -> ctx.getString(R.string.bl_over_eight_foul)
                    WinReason.THREE_FOULS -> ctx.getString(R.string.bl_over_three_fouls)
                    else -> ctx.getString(R.string.bl_over_cleared)
                }
                if (winner.isNotEmpty()) "$head $reason" else head
            }
            rec == null -> ""
            s.lastFouls.isNotEmpty() -> ctx.getString(R.string.bl_foul_prefix, s.lastFouls.joinToString(" ") { foulText(it) })
            rec.pocketed.isNotEmpty() -> ctx.sayFor(s.seats[rec.shooter].displayName, R.string.bl_pocketed_line, R.string.bl_pocketed_you, rec.pocketed.joinToString(", "))
            else -> ctx.getString(R.string.bl_nothing_pocketed)
        }
        val turn = when {
            s.phase == BilliardsPhase.OVER -> ""
            hostLeft -> ctx.getString(R.string.bjw_host_left)
            animating -> ""
            s.seats[s.shooter].isBot -> ctx.getString(R.string.bl_cpu_thinking, seatName(s.shooter))
            s.ballInHand -> ctx.getString(if (s.behindHead) R.string.bl_in_hand_head else R.string.bl_in_hand)
            s.isBreak -> ctx.sayFor(s.seats[s.shooter].displayName, R.string.bl_break_named, R.string.bl_break_you)
            else -> ctx.sayFor(s.seats[s.shooter].displayName, R.string.bl_turn_named, R.string.bl_turn_you)
        }
        listOf(last, turn).filter { it.isNotBlank() }.joinToString(" ")
    }

    val tableDesc = run {
        val left = s.ballsOnTable().size
        val base = ctx.getString(R.string.bl_table_desc, left)
        val tg = if (canAdjust) " " + ctx.getString(R.string.bl_table_targets, targets.sorted().joinToString(", ")) else ""
        base + tg
    }

    val canCoach = GameCoach.forGame("billiards") != null
    val (_, openCoach) = rememberClCoach("billiards", ready = canCoach && s.shotNo == 0)
    val finishedRacks = s.seats.sumOf { it.wins }
    val leaveTitle = stringResource(R.string.bjw_leave_title)
    val leaveBody = stringResource(if (isNetworked) R.string.bjw_leave_body_net else R.string.bl_leave_body)

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = s.phase != BilliardsPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRacks > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = !isNetworked && finishedRacks == 0,
        onUndo = if (game.canUndo) ({ game.undo() }) else null,
        canUndo = game.canUndo && !animating,
        onHint = if (!isNetworked && canAdjust) ({ scope.launch { game.requestHint()?.let { applyHint(it) } } }) else null,
        buttonFill = BL_BG_BOTTOM, buttonContent = BL_TEXT,
        leaveTitle = leaveTitle, leaveBody = leaveBody
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BL_BG_TOP, BL_BG_BOTTOM)))) {
            val landscape = maxWidth > maxHeight * 1.15f
            val density = LocalDensity.current

            val controls: @Composable () -> Unit = {
              Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ClStatusLine(statusText, BL_TEXT, Modifier.fillMaxWidth().padding(vertical = 2.dp), emphasized = true)
                    val hintLine = if (showHint && canAdjust) stringResource(R.string.bl_hint_line) else null
                    ClWhyLine(whyText ?: hintLine, Color(0xFF1B1B1B), BL_GOLD)
                    if (s.phase == BilliardsPhase.OVER) {
                        val winner = s.seats.getOrNull(s.winner)
                        ClFinishedPanel(
                            title = if (winner == null) "" else youAware(winner.displayName, R.string.bl_over_win, R.string.bl_over_win_you),
                            subtitle = if (isNetworked && winner != null) stringResource(if (s.winner == localSeat) R.string.ccl_you_won else R.string.ccl_you_lost) else null,
                            lines = s.seats.map { stringResource(R.string.bl_final_line, LocalContext.current.seatLabel(it.displayName), it.wins) },
                            canPlayAgain = !hostLeft && !animating,
                            onPlayAgain = { game.playAgain() },
                            onLeave = { game.leaveSession() }
                        )
                    } else if (canAdjust) {
                        if (s.ballInHand) {
                            OutlinedButton(
                                onClick = {
                                    val sp = BilliardsRules.freeSpot(BilliardsTable.HEAD_X, BilliardsTable.MID_Y, s.xs(), s.ys(), s.ons(), s.behindHead)
                                    place = sp
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BL_TEXT)
                            ) { Text(stringResource(R.string.bl_place_head)) }
                        }
                        // quick aim at each ball you may hit, for TalkBack and for speed
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            targets.sorted().take(9).forEach { b ->
                                OutlinedButton(
                                    onClick = { aim = atan2(s.y[b] - cuePos.second, s.x[b] - cuePos.first) },
                                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BL_TEXT)
                                ) { Text(stringResource(R.string.bl_aim_at, b)) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { aim -= 0.4 * PI / 180 },
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BL_TEXT)
                            ) { Text(stringResource(R.string.bl_aim_left)) }
                            OutlinedButton(
                                onClick = { aim += 0.4 * PI / 180 },
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BL_TEXT)
                            ) { Text(stringResource(R.string.bl_aim_right)) }
                        }
                        val powerDesc = stringResource(R.string.bl_power_desc, (power * 100).roundToInt())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.bl_power), color = BL_TEXT, fontWeight = FontWeight.Bold)
                            Slider(
                                value = power, onValueChange = { power = it }, valueRange = 0.02f..1f,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 8.dp)
                                    .semantics { contentDescription = powerDesc; stateDescription = powerDesc }
                            )
                            Text("${(power * 100).roundToInt()}%", color = BL_GOLD, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // The spin pad and Shoot stay on screen under the scrolling controls: Shoot used to sit below the fold.
                if (s.phase != BilliardsPhase.OVER && canAdjust) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SpinPad(spinX, spinY, onChange = { x, y -> spinX = x; spinY = y }, colorblind = colorblind)
                    Button(
                        onClick = { fire() }, enabled = !animating,
                        modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 150.dp)
                    ) { Text(stringResource(R.string.bl_shoot), fontWeight = FontWeight.Bold) }
                }
                    Spacer(Modifier.height(4.dp))
                }
              }
            }

            val tableArea: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.clipToBounds()) {
                    val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
                    val view = remember(wPx, hPx) { makeView(wPx, hPx) }
                    val numberPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = android.graphics.Color.BLACK } }
                    val latestState = rememberUpdatedState(s)
                    val latestCan = rememberUpdatedState(canAdjust)
                    val latestCue = rememberUpdatedState(cuePos)
                    val latestView = rememberUpdatedState(view)
                    var dragMode by remember { mutableIntStateOf(0) } // 0 aim, 1 move the cue ball
                    Canvas(
                        Modifier.fillMaxSize()
                            .semantics { contentDescription = tableDesc }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val st = latestState.value
                                    if (!latestCan.value) return@awaitEachGesture
                                    val v = latestView.value
                                    val cue = latestCue.value
                                    val cueScreen = v.at(cue.first, cue.second)
                                    dragMode = if (st.ballInHand && bgDist(down.position, cueScreen) < BilliardsTable.R.toFloat() * v.scale * 3.2f) 1 else 0
                                    fun handle(p: Offset) {
                                        if (dragMode == 1) {
                                            val (tx, ty) = v.toTable(p)
                                            if (BilliardsRules.canPlaceCue(tx, ty, st.xs(), st.ys(), st.ons(), st.behindHead)) place = tx to ty
                                        } else {
                                            val c = latestCue.value
                                            val (tx, ty) = v.toTable(p)
                                            if (hypot(tx - c.first, ty - c.second) > 0.5) aim = atan2(ty - c.second, tx - c.first)
                                        }
                                    }
                                    handle(down.position)
                                    drag(down.id) { change -> handle(change.position); change.consume() }
                                }
                            }
                    ) {
                        drawBilliardsTable(view, dark)
                        // positions: the replay frame while animating, else the state (with the cue ball being placed)
                        val fr = frames
                        val px = DoubleArray(16); val py = DoubleArray(16); val on = BooleanArray(16)
                        if (animating && fr != null && frameIdx in fr.indices) {
                            val f = fr[frameIdx]
                            for (i in 0 until 16) { px[i] = f[i * 2].toDouble(); py[i] = f[i * 2 + 1].toDouble(); on[i] = f[32 + i] > 0.5f }
                        } else if (animating && s.lastShot != null && fr == null) {
                            val rec = s.lastShot!!
                            for (i in 0 until 16) { px[i] = rec.startX[i]; py[i] = rec.startY[i]; on[i] = rec.startOn[i] }
                            px[0] = rec.shot.cueX; py[0] = rec.shot.cueY; on[0] = true
                        } else {
                            for (i in 0 until 16) { px[i] = s.x[i]; py[i] = s.y[i]; on[i] = s.onTable[i] }
                            px[0] = cuePos.first; py[0] = cuePos.second
                        }
                        val r = (BilliardsTable.R * view.scale).toFloat()
                        // aim guide and cue stick
                        if (canAdjust && !animating) {
                            val hit = traceAim(s, px[0], py[0], aim)
                            drawAimGuide(view, s, px[0], py[0], aim, hit, r, hintShot != null && showHint)
                        }
                        for (i in 1 until 16) if (on[i]) drawPoolBall(i, view.at(px[i], py[i]), r, numberPaint, colorblind)
                        if (on[0]) drawPoolBall(0, view.at(px[0], py[0]), r, numberPaint, colorblind)
                        if (on[0] && (canAdjust || (animating && strike.value > 0f && frames == null))) {
                            val pull = if (animating) (1f - strike.value) * 6.0 else (power * 9.0)
                            drawCueStick(view, px[0], py[0], if (animating) s.lastShot?.shot?.angle ?: aim else aim, pull, r)
                        }
                        if (on[0] && s.ballInHand && canAdjust) {
                            drawCircle(BL_GOLD, r * 1.9f, view.at(px[0], py[0]), style = Stroke(r * 0.18f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.6f, r * 0.5f))))
                        }
                        // pocket flashes
                        for ((fx, fy, t0) in flashes) {
                            val age = ((clockMs - t0) / 500f).coerceIn(0f, 1f)
                            drawCircle(BL_GOLD.copy(alpha = 1f - age), r * (1.5f + 2.5f * age), view.at(fx, fy), style = Stroke(r * 0.2f))
                        }
                        // confetti
                        burst.particles.value.forEach { p ->
                            drawCircle(p.color.copy(alpha = p.lifeFraction), size.minDimension * 0.012f + 3f, Offset(p.pos.x * size.width, p.pos.y * size.height))
                        }
                    }
                }
            }

            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = GameChromeEndInset, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canCoach) ClCoachButton("billiards", openCoach)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 0..1) SeatPanel(s, i, isNetworked && i == localSeat, Modifier.weight(1f))
                    }
                }
                if (landscape) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        tableArea(Modifier.weight(1f).fillMaxHeight().padding(4.dp))
                        Box(Modifier.width(300.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { controls() }
                    }
                } else {
                    tableArea(Modifier.weight(1f).fillMaxWidth().padding(4.dp))
                    Box(Modifier.fillMaxWidth().heightIn(max = 300.dp)) { controls() }
                }
            }
        }
    }
}

// ---- seat panels ----------------------------------------------------------------------------------

@Composable
private fun SeatPanel(s: BilliardsState, seat: Int, isLocal: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val x = s.seats[seat]
    val youTag = if (isLocal) " " + stringResource(R.string.bjw_you_suffix) else ""
    val cpuWord = stringResource(R.string.bjw_cpu_tag)
    val cpuTag = if (x.isBot && !x.displayName.contains(cpuWord, ignoreCase = true)) " ($cpuWord)" else ""
    val group = s.groups[seat]
    val groupText = if (s.variant == BilliardsVariant.NINE) stringResource(R.string.bl_nine_goal, s.ballsOnTable().minOrNull() ?: 9)
    else stringResource(if (group == 1) R.string.bl_group_solids else if (group == 2) R.string.bl_group_stripes else R.string.bl_group_open)
    val mine = if (group != 0) s.ballsOnTable().filter { BilliardsTable.groupOf(it) == group } else emptyList()
    val active = s.shooter == seat && s.phase == BilliardsPhase.AIM
    val desc = stringResource(R.string.bl_seat_desc, ctx.seatLabel(x.displayName) + youTag + cpuTag, groupText, x.wins) +
        (if (group != 0) " " + stringResource(R.string.bl_seat_left, mine.size) else "") + (if (active) " " + stringResource(R.string.bl_seat_shooting) else "")
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0x33F4D27A) else Color(0x22000000))
            .border(if (active) 2.dp else 1.dp, if (active) BL_GOLD else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
    ) {
        Text(ctx.seatLabel(x.displayName) + youTag, color = BL_TEXT, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        Text(groupText + "  ·  " + x.wins, color = BL_GOLD, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        if (mine.isNotEmpty()) {
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val r = size.height / 2f - 1f
                mine.forEachIndexed { i, b -> drawTrayBall(b, Offset(r + 1f + i * (r * 2.1f), size.height / 2f), r) }
            }
        }
    }
}

private fun DrawScope.drawTrayBall(id: Int, c: Offset, r: Float) {
    drawCircle(ballColor(id), r, c)
    if (id >= 9) drawCircle(Color.White, r * 0.5f, c)
    drawCircle(Color.Black.copy(alpha = 0.5f), r, c, style = Stroke(1f))
}

// ---- spin pad -------------------------------------------------------------------------------------

@Composable
private fun SpinPad(spinX: Float, spinY: Float, onChange: (Float, Float) -> Unit, colorblind: Boolean) {
    val desc = stringResource(R.string.bl_spin_desc, (spinX * 100).roundToInt(), (spinY * 100).roundToInt())
    val reset = stringResource(R.string.bl_spin_reset)
    val topLabel = stringResource(R.string.bl_spin_top)
    val backLabel = stringResource(R.string.bl_spin_back)
    val leftLabel = stringResource(R.string.bl_spin_left)
    val rightLabel = stringResource(R.string.bl_spin_right)
    val latest = rememberUpdatedState(onChange)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(84.dp).clip(CircleShape)
                .semantics {
                    contentDescription = desc
                    customActions = listOf(
                        CustomAccessibilityAction(topLabel) { latest.value(spinX, (spinY + 0.25f).coerceAtMost(1f)); true },
                        CustomAccessibilityAction(backLabel) { latest.value(spinX, (spinY - 0.25f).coerceAtLeast(-1f)); true },
                        CustomAccessibilityAction(leftLabel) { latest.value((spinX - 0.25f).coerceAtLeast(-1f), spinY); true },
                        CustomAccessibilityAction(rightLabel) { latest.value((spinX + 0.25f).coerceAtMost(1f), spinY); true },
                        CustomAccessibilityAction(reset) { latest.value(0f, 0f); true }
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun set(p: Offset) {
                            val rr = size.width / 2f
                            var dx = (p.x - rr) / (rr * 0.8f); var dy = (p.y - rr) / (rr * 0.8f)
                            val m = hypot(dx, dy)
                            if (m > 1f) { dx /= m; dy /= m }
                            latest.value(dx, -dy)
                        }
                        set(down.position)
                        drag(down.id) { ch -> set(ch.position); ch.consume() }
                    }
                }
        ) {
            val rr = size.width / 2f
            drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFD7D7CF)), center = Offset(rr * 0.8f, rr * 0.7f), radius = rr * 1.4f), rr)
            drawCircle(Color(0xFF222222), rr, style = Stroke(2f))
            drawLine(Color(0x66000000), Offset(rr, 4f), Offset(rr, size.height - 4f), 1.5f)
            drawLine(Color(0x66000000), Offset(4f, rr), Offset(size.width - 4f, rr), 1.5f)
            drawCircle(Color(0x66000000), rr * 0.5f, style = Stroke(1.5f))
            val cx = rr + spinX * rr * 0.8f; val cy = rr - spinY * rr * 0.8f
            drawCircle(Color(0xFFC62828), rr * (if (colorblind) 0.2f else 0.16f), Offset(cx, cy))
            drawCircle(Color.White, rr * 0.2f, Offset(cx, cy), style = Stroke(2.5f))
        }
        Text(stringResource(R.string.bl_spin), color = BL_TEXT, style = MaterialTheme.typography.labelSmall)
    }
}

// ---- drawing --------------------------------------------------------------------------------------

private fun DrawScope.drawBilliardsTable(v: TableView, dark: Boolean) {
    val s = v.scale
    val m = (BL_RAIL_W * s).toFloat()
    val tl = v.at(0.0, 0.0); val br = v.at(BilliardsTable.W, BilliardsTable.H)
    val left = min(tl.x, br.x); val top = min(tl.y, br.y); val right = max(tl.x, br.x); val bottom = max(tl.y, br.y)
    // wooden frame
    val frameTL = Offset(left - m, top - m)
    val frameSize = Size(right - left + 2 * m, bottom - top + 2 * m)
    drawRoundRect(Brush.linearGradient(listOf(BL_RAIL_HI, BL_RAIL, bgShade(BL_RAIL, -0.35f)), frameTL, Offset(frameTL.x + frameSize.width, frameTL.y + frameSize.height)),
        frameTL, frameSize, androidx.compose.ui.geometry.CornerRadius(m * 0.9f))
    // wood grain
    var gy = top - m + 4f
    while (gy < bottom + m - 2f) {
        drawLine(Color(0x22000000), Offset(frameTL.x + 3f, gy), Offset(frameTL.x + frameSize.width - 3f, gy + 2f), 1f)
        gy += max(5f, m * 0.55f)
    }
    // cloth
    val clothSize = Size(right - left, bottom - top)
    drawRect(Brush.radialGradient(listOf(BL_FELT_LIGHT, BL_FELT_DARK), center = Offset((left + right) / 2, (top + bottom) / 2), radius = max(clothSize.width, clothSize.height) * 0.75f),
        Offset(left, top), clothSize)
    // soft cushion shadow just inside the rails
    drawRect(Color(0x33000000), Offset(left, top), clothSize, style = Stroke(m * 0.5f))
    // cloth weave
    var wx = left + 6f
    while (wx < right) { drawLine(Color(0x0DFFFFFF), Offset(wx, top), Offset(wx, bottom), 1f); wx += 7f }
    // head string and spots
    val hs0 = v.at(BilliardsTable.HEAD_X, 0.0); val hs1 = v.at(BilliardsTable.HEAD_X, BilliardsTable.H)
    drawLine(Color(0x33FFFFFF), hs0, hs1, 1.5f)
    drawCircle(Color(0x55FFFFFF), 3f * (s / 4f).coerceIn(0.6f, 2f), v.at(BilliardsTable.HEAD_X, BilliardsTable.MID_Y))
    drawCircle(Color(0x55FFFFFF), 3f * (s / 4f).coerceIn(0.6f, 2f), v.at(BilliardsTable.FOOT_X, BilliardsTable.MID_Y))
    // diamonds on the rails
    for (i in 1..7) {
        val dx = BilliardsTable.W * i / 8.0
        for (yy in doubleArrayOf(-BL_RAIL_W / 2, BilliardsTable.H + BL_RAIL_W / 2)) drawCircle(Color(0xFFF1E3C4), m * 0.1f, v.at(dx, yy))
    }
    for (i in 1..3) {
        val dy = BilliardsTable.H * i / 4.0
        for (xx in doubleArrayOf(-BL_RAIL_W / 2, BilliardsTable.W + BL_RAIL_W / 2)) drawCircle(Color(0xFFF1E3C4), m * 0.1f, v.at(xx, dy))
    }
    // pockets
    for (k in 0 until 6) {
        val c = v.at(BilliardsTable.POCKET_X[k], BilliardsTable.POCKET_Y[k])
        val pr = (BilliardsTable.POCKET_R[k] * s).toFloat() * 1.05f
        drawCircle(Color(0xFFB8913A), pr * 1.2f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF000000), Color(0xFF1A1A1A)), center = c, radius = pr), pr, c)
    }
}

private fun DrawScope.drawPoolBall(id: Int, c: Offset, r: Float, paint: Paint, highContrast: Boolean) {
    if (r < 1f) return
    drawCircle(Color(0x55000000), r, Offset(c.x + r * 0.18f, c.y + r * 0.26f))
    val base = ballColor(id)
    val stripe = id >= 9
    if (stripe) {
        drawCircle(Color(0xFFF6F6EE), r, c)
        clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }) {
            drawRect(base, Offset(c.x - r, c.y - r * 0.62f), Size(2 * r, r * 1.24f))
        }
    } else {
        drawCircle(Brush.radialGradient(listOf(bgShade(base, 0.35f), base, bgShade(base, -0.4f)), center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), radius = r * 1.5f), r, c)
    }
    if (id != 0) {
        drawCircle(Color.White, r * 0.48f, c)
        drawCircle(Color(0xFF222222), r * 0.48f, c, style = Stroke(if (highContrast) r * 0.12f else r * 0.05f))
        paint.textSize = r * (if (highContrast) 0.8f else 0.66f)
        drawIntoCanvas { cv ->
            val fm = paint.fontMetrics
            cv.nativeCanvas.drawText(id.toString(), c.x, c.y - (fm.ascent + fm.descent) / 2f, paint)
        }
    }
    // shading and gloss
    drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color(0x44000000)), center = c, radius = r), r, c)
    drawCircle(Color.White.copy(alpha = 0.6f), r * 0.17f, Offset(c.x - r * 0.38f, c.y - r * 0.42f))
    drawCircle(Color(0xAA101010), r, c, style = Stroke(r * 0.06f))
}

private fun DrawScope.drawAimGuide(v: TableView, s: BilliardsState, cx: Double, cy: Double, angle: Double, hit: AimHit, r: Float, hinted: Boolean) {
    val from = v.at(cx, cy)
    val gx = hit.cx; val gy = hit.cy
    val to = v.at(gx, gy)
    val dash = PathEffect.dashPathEffect(floatArrayOf(r * 0.9f, r * 0.7f))
    val color = if (hinted) BL_GOLD else Color.White.copy(alpha = 0.85f)
    drawLine(color, from, to, strokeWidth = max(2f, r * 0.16f), pathEffect = dash)
    drawCircle(color, r, to, style = Stroke(max(2f, r * 0.14f)))
    if (hit.ball >= 1) {
        val bx = s.x[hit.ball]; val by = s.y[hit.ball]
        val nx = bx - gx; val ny = by - gy
        val nl = hypot(nx, ny).coerceAtLeast(1e-6)
        val len = 14.0
        drawLine(Color(0xFFF4D27A), v.at(bx, by), v.at(bx + nx / nl * len, by + ny / nl * len), strokeWidth = max(2f, r * 0.2f), pathEffect = dash)
        // cue ball tangent after contact
        val dx = cos(angle); val dy = sin(angle)
        val dot = dx * (nx / nl) + dy * (ny / nl)
        var tx = dx - dot * (nx / nl); var ty = dy - dot * (ny / nl)
        val tl = hypot(tx, ty)
        if (tl > 0.05) { tx /= tl; ty /= tl; drawLine(Color.White.copy(alpha = 0.5f), v.at(gx, gy), v.at(gx + tx * 8 * tl, gy + ty * 8 * tl), strokeWidth = max(2f, r * 0.12f), pathEffect = dash) }
    }
}

private fun DrawScope.drawCueStick(v: TableView, cx: Double, cy: Double, angle: Double, pull: Double, r: Float) {
    val dx = cos(angle); val dy = sin(angle)
    val start = BilliardsTable.R * 1.6 + pull
    val len = 58.0
    val tip = v.at(cx - dx * start, cy - dy * start)
    val butt = v.at(cx - dx * (start + len), cy - dy * (start + len))
    val sd = v.dir(dx, dy)
    val nrm = Offset(-sd.y, sd.x)
    val tw = r * 0.22f; val bw = r * 0.46f
    val path = Path().apply {
        moveTo(tip.x + nrm.x * tw, tip.y + nrm.y * tw)
        lineTo(butt.x + nrm.x * bw, butt.y + nrm.y * bw)
        lineTo(butt.x - nrm.x * bw, butt.y - nrm.y * bw)
        lineTo(tip.x - nrm.x * tw, tip.y - nrm.y * tw)
        close()
    }
    drawPath(path, Brush.linearGradient(listOf(Color(0xFFF1D9A6), Color(0xFFC08B4A), Color(0xFF3A2414)), tip, butt))
    drawPath(path, Color(0x99000000), style = Stroke(1.2f))
    // ferrule and chalked tip
    val f1 = Offset(tip.x - sd.x * 0f, tip.y - sd.y * 0f)
    drawCircle(Color(0xFF3A78C9), r * 0.24f, f1)
}
