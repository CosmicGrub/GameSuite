package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberPannedProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.toytennis.PointReason
import com.gamesuite.games.toytennis.TPhase
import com.gamesuite.games.toytennis.TennisReject
import com.gamesuite.games.toytennis.TennisRules
import com.gamesuite.games.toytennis.TennisState
import com.gamesuite.games.toytennis.TennisView
import com.gamesuite.games.toytennis.ToyTennisGame
import com.gamesuite.games.toytennis.Whiff
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

private class TennisColors(
    val bg: Color, val text: Color, val card: Color, val accent: Color, val gold: Color,
    val courtIn: Color, val courtOut: Color, val line: Color, val dark: Boolean
)

private fun tennisColors(dark: Boolean) = if (!dark) TennisColors(
    bg = Color(0xFFEFF3F8), text = Color(0xFF18222E), card = Color(0xFFD8E1EC), accent = Color(0xFF1D6FB8),
    gold = Color(0xFFE0A100), courtIn = Color(0xFF2F7FB8), courtOut = Color(0xFF3F9C6B), line = Color(0xF2FFFFFF), dark = false
) else TennisColors(
    bg = Color(0xFF10151B), text = Color(0xFFE6EDF5), card = Color(0xFF1F2933), accent = Color(0xFF6CB6F0),
    gold = Color(0xFFFFC83D), courtIn = Color(0xFF24618F), courtOut = Color(0xFF2B6E4C), line = Color(0xDDEAF1F8), dark = true
)

/** Court metres to pixels; the court is turned the long way in landscape and flipped for side 1's view. */
private class CourtXf(val w: Float, val h: Float, val flip: Boolean) {
    val landscape = w > h * 1.05f
    private val halfW = 6.2f
    private val halfL = 14.8f
    private val pad = 0.02f * min(w, h)
    val scale: Float = if (!landscape) min((w - 2 * pad) / (2 * halfW), (h - 2 * pad) / (2 * halfL))
    else min((w - 2 * pad) / (2 * halfL), (h - 2 * pad) / (2 * halfW))
    private val cx = w / 2
    private val cy = h / 2

    fun toScreen(x: Double, y: Double, z: Double = 0.0): Offset {
        var px = x.toFloat(); var py = y.toFloat()
        if (flip) { px = -px; py = -py }
        val base = if (!landscape) Offset(cx + px * scale, cy + py * scale) else Offset(cx + py * scale, cy + px * scale)
        return Offset(base.x, base.y - (z * scale * 0.9f).toFloat())
    }

    fun toWorld(p: Offset): Offset {
        var x: Float; var y: Float
        if (!landscape) { x = (p.x - cx) / scale; y = (p.y - cy) / scale } else { y = (p.x - cx) / scale; x = (p.y - cy) / scale }
        if (flip) { x = -x; y = -y }
        return Offset(x, y)
    }

    fun len(m: Double): Float = (m * scale).toFloat()
}

private class SwipeStart(val seat: Int, val pos: Offset, val time: Long)

/**
 * Toy Tennis. Your player runs to the ball by itself; you choose the shot and its moment with ONE
 * swipe: toward the net, left or right of straight for the angle, and quicker for more power, released
 * as the shrinking timing ring closes on the ball. A late or early swipe lands long/short or misses.
 * Relaxed timing (Hub option) makes the ball wait, and a screen-reader panel replaces the swipe with
 * buttons. The court turns the long way in landscape and is flipped for the far-side player.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToyTennisScreen(
    sessionManager: GameSessionManager,
    game: ToyTennisGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberPannedProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.AIR_HOCKEY, enabled = musicEnabled)
    val view by game.state
    val hint by game.hint
    val lastReject by game.lastReject
    val opponentLeft by game.opponentLeft
    val sessionWins by game.sessionWins
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val colors = tennisColors(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reduced
    val colorblind = LocalColorblindMode.current
    val screenReader = rememberClTouchExploration()
    val burst = rememberParticleBurst()
    val density = LocalDensity.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.assist = extras["assist"] == "true"
        game.formatId = extras["format"] ?: "points7"
        game.firstServer = sessionManager.launchOptions.firstMoverIndex.coerceIn(0, 1)
        game.cpuReactionScale = gameSpeed.delayScale.toDouble()
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }
    LaunchedEffect(game) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { game.advance() }
        }
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val helpTitle = stringResource(R.string.tte_help_title)
    val helpText = stringResource(R.string.tte_help_text)
    val v = view
    if (v == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = colors.bg, buttonContent = colors.text
            ) {
                Box(Modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ccl_waiting_host), color = colors.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }
    val c = v.court
    val humanSeats: Set<Int> = if (isNetworked) setOf(localSeat) else v.players.indices.filter { !v.players[it].isBot }.toSet()
    val hotSeat = !isNetworked && humanSeats.size > 1
    val viewSide = if (isNetworked) localSeat.coerceIn(0, 1) else 0
    fun name(i: Int) = androidContext.seatLabel(v.players.getOrNull(i)?.displayName ?: "")

    // ---- transient words ----
    var banner by remember { mutableStateOf("") }
    var bannerSerial by remember { mutableIntStateOf(0) }
    var whyText by remember { mutableStateOf<String?>(null) }
    var whySerial by remember { mutableIntStateOf(0) }
    fun say(text: String) { banner = text; bannerSerial++ }
    fun why(text: String) { whyText = text; whySerial++ }
    LaunchedEffect(bannerSerial) {
        if (bannerSerial == 0) return@LaunchedEffect
        val mine = bannerSerial
        delay(gameSpeed.scale(1800L))
        if (bannerSerial == mine) banner = ""
    }
    LaunchedEffect(whySerial) {
        if (whySerial == 0) return@LaunchedEffect
        val mine = whySerial
        delay(3000)
        if (whySerial == mine) whyText = null
    }
    val whyRejectLeft = stringResource(R.string.tte_why_left)
    val whyOver = stringResource(R.string.tte_why_over)
    LaunchedEffect(lastReject?.serial) {
        val r = lastReject ?: return@LaunchedEffect
        why(if (r.reason == TennisReject.OPPONENT_LEFT) whyRejectLeft else whyOver)
        haptics(HapticSignal.FAILURE)
    }

    fun pan(x: Double): Float { val f = (x / 5.0).toFloat().coerceIn(-1f, 1f); return if (viewSide == 1) -f else f }
    val qualityWords = listOf(
        stringResource(R.string.tte_q_miss), stringResource(R.string.tte_q_weak), stringResource(R.string.tte_q_ok),
        stringResource(R.string.tte_q_good), stringResource(R.string.tte_q_perfect)
    )
    val swing = remember { Animatable(1f) }
    var swingSide by remember { mutableIntStateOf(0) }
    LaunchedEffect(c.swingSerial) {
        if (c.swingSerial == 0) return@LaunchedEffect
        swingSide = c.lastSwingSide.coerceAtLeast(0)
        val mine = c.lastSwingSide == viewSide || hotSeat
        if (c.lastQuality == 0) {
            playSfx(SfxKind.WHOOSH, 0f)
            haptics(HapticSignal.LIGHT_TICK)
            if (mine) {
                val msg = when (c.lastWhiff) {
                    Whiff.EARLY -> androidContext.getString(R.string.tte_why_early)
                    Whiff.LATE -> androidContext.getString(R.string.tte_why_late)
                    Whiff.FAR -> androidContext.getString(R.string.tte_why_far)
                    else -> androidContext.getString(R.string.tte_why_wrong_time)
                }
                why(msg); say(androidContext.getString(R.string.tte_q_miss))
            }
        } else {
            playSfx(SfxKind.SOLID_THUNK, pan(c.bx))
            haptics(when (c.lastQuality) { 4 -> HapticSignal.STRONG_ACTION; 3 -> HapticSignal.NORMAL_ACTION; else -> HapticSignal.LIGHT_TICK })
            if (mine || c.lastQuality == 4) say(qualityWords[c.lastQuality.coerceIn(0, 4)])
        }
        if (!reduced) {
            swing.snapTo(0f)
            swing.animateTo(1f, tween(gameSpeed.scale(240L).toInt().coerceAtLeast(100), easing = LinearEasing))
        }
    }
    LaunchedEffect(c.bounceSerial) { if (c.bounceSerial > 0) playSfx(SfxKind.LIGHT_TICK, pan(c.bx)) }
    LaunchedEffect(c.netSerial) { if (c.netSerial > 0) { playSfx(SfxKind.INVALID_BUZZ, 0f); say(androidContext.getString(R.string.tte_reason_net)) } }
    LaunchedEffect(c.faultSerial) {
        if (c.faultSerial > 0) {
            playSfx(SfxKind.INVALID_BUZZ, 0f); haptics(HapticSignal.FAILURE)
            say(androidContext.getString(R.string.tte_fault))
        }
    }
    val pointFlash = remember { Animatable(1f) }
    LaunchedEffect(c.pointSerial) {
        if (c.pointSerial == 0) return@LaunchedEffect
        val w = c.pointWinner
        val mine = w >= 0 && (hotSeat || w == viewSide)
        playSfx(if (mine) SfxKind.SUCCESS_CHIME else SfxKind.INVALID_BUZZ, 0f)
        haptics(if (mine) HapticSignal.STRONG_ACTION else HapticSignal.FAILURE)
        val reason = when (c.pointReason) {
            PointReason.OUT -> androidContext.getString(R.string.tte_reason_out)
            PointReason.NET -> androidContext.getString(R.string.tte_reason_net)
            PointReason.DOUBLE_FAULT -> androidContext.getString(R.string.tte_reason_double)
            PointReason.ACE -> androidContext.getString(R.string.tte_reason_ace)
            else -> androidContext.getString(R.string.tte_reason_missed)
        }
        say(if (!hotSeat && w == viewSide) androidContext.getString(R.string.tte_point_you, reason) else androidContext.getString(R.string.tte_point_to, name(w), reason))
        if (!reduced) { pointFlash.snapTo(0f); pointFlash.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
    }
    LaunchedEffect(c.phase) {
        if (c.phase == TPhase.OVER) {
            val w = c.score.winner
            val win = hotSeat || w == viewSide
            playSfx(if (win) SfxKind.SUCCESS_CHIME else SfxKind.INVALID_BUZZ, 0f)
            haptics(if (win) HapticSignal.CELEBRATION else HapticSignal.FAILURE)
            if (!reduced && win) burst.spawn(Offset(0.5f, 0.4f), 90, listOf(colors.gold, Color.White, colors.accent), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
        }
    }

    // ---- status line ----
    val serverName = name(c.server)
    val status: String = when {
        c.phase == TPhase.OVER -> androidContext.getString(
            if (!hotSeat && c.score.winner == viewSide) R.string.tte_ann_win_you else R.string.tte_ann_win, name(c.score.winner.coerceAtLeast(0))
        )
        opponentLeft -> stringResource(R.string.tte_opp_left)
        c.phase == TPhase.PREP -> if (!hotSeat && c.server == viewSide) stringResource(R.string.tte_status_serve_you)
        else androidContext.getString(R.string.tte_status_serve, serverName)
        c.phase == TPhase.TOSS -> if (c.server in humanSeats) stringResource(R.string.tte_status_swing_now) else ""
        c.phase == TPhase.RALLY && c.lastHitter >= 0 && (1 - c.lastHitter) in humanSeats -> stringResource(R.string.tte_status_return)
        else -> ""
    }
    val gp = c.score.gamePointFor()
    val gamePointLine = if (gp >= 0 && c.phase != TPhase.OVER && c.phase != TPhase.POINT) androidContext.getString(
        if (c.score.format.sets) R.string.tte_game_point else R.string.tte_point_point, name(gp)
    ) else ""

    // ---- accessible controls ----
    var dirChoice by remember { mutableIntStateOf(1) }
    var powerChoice by remember { mutableIntStateOf(1) }
    fun panelSwing() {
        val seat = if (isNetworked) localSeat else humanSeats.minOrNull() ?: 0
        game.swing(seat, listOf(-0.7, 0.0, 0.7)[dirChoice], listOf(0.35, 0.65, 0.95)[powerChoice])
    }

    val finished = sessionWins.sum()
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = c.phase != TPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finished > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finished == 0,
        onHint = if (isNetworked) null else ({ game.requestHint(humanSeats.minOrNull() ?: 0) }),
        buttonFill = colors.bg, buttonContent = colors.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finished > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body),
        extraItems = { _ ->
            androidx.compose.runtime.DisposableEffect(Unit) {
                game.pause()
                onDispose { game.resume() }
            }
        }
    ) {
        val (_, openCoach) = rememberClCoach("toy-tennis", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
            val boxW = maxWidth
            val boxH = maxHeight
            val landscape = boxW > boxH * 1.15f

            @Composable
            fun Header(modifier: Modifier) {
                Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(end = if (landscape) 0.dp else GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                        ClCoachButton("toy-tennis", openCoach)
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(viewSide, 1 - viewSide).forEach { side ->
                                val sc = c.score
                                val glyph = if (side == 0) "▲" else "◆"
                                val serving = if (c.server == side) "●" else ""
                                val line = StringBuilder("$glyph ${name(side)} $serving")
                                if (sc.format.sets) {
                                    if (sc.format.setsToWin > 1) line.append("  S${sc.sets(side)}")
                                    line.append("  G${sc.games(side)}")
                                }
                                line.append("  ${sc.pointText(side)}")
                                val desc = when {
                                    sc.format.sets && sc.format.setsToWin > 1 -> stringResource(R.string.tte_desc_sets, name(side), sc.sets(side), sc.games(side), sc.pointText(side))
                                    sc.format.sets -> stringResource(R.string.tte_desc_games, name(side), sc.games(side), sc.pointText(side))
                                    else -> stringResource(R.string.tte_desc_points, name(side), sc.pointText(side))
                                } + if (c.server == side) " " + stringResource(R.string.tte_serving) else ""
                                Text(
                                    line.toString(), color = colors.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(14.dp)).background(colors.card)
                                        .border(2.dp, stTeamColor(side, colors.dark), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp).semantics { contentDescription = desc }
                                )
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (v.assist) Text(stringResource(R.string.tte_assist_badge), color = colors.text, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, colors.accent, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                        if (!isNetworked && c.phase != TPhase.OVER) {
                            OutlinedButton(
                                onClick = { game.requestHint(humanSeats.minOrNull() ?: 0) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.ccl_hint)) }
                        }
                    }
                    ClStatusLine(listOf(banner, status, gamePointLine).filter { it.isNotBlank() }.joinToString(" "), colors.text, Modifier.fillMaxWidth().padding(top = 4.dp), emphasized = true)
                    ClWhyLine(whyText, colors.text, colors.card)
                }
            }

            @Composable
            fun Court(modifier: Modifier) {
                var size by remember { mutableStateOf(IntSize.Zero) }
                val xf = if (size.width > 0 && size.height > 0) CourtXf(size.width.toFloat(), size.height.toFloat(), viewSide == 1) else null
                val latestView = rememberUpdatedState(v)
                val latestXf = rememberUpdatedState(xf)
                val pulse by rememberInfiniteTransition(label = "ring").animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
                val courtDesc = stringResource(R.string.tte_court_desc)
                Box(modifier.onSizeChanged { size = it }) {
                    Canvas(
                        Modifier.fillMaxSize().semantics { contentDescription = courtDesc }
                            .pointerInput(isNetworked, humanSeats) {
                                val starts = HashMap<Long, SwipeStart>()
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val cur = latestView.value
                                        val x = latestXf.value ?: continue
                                        for (ch in event.changes) {
                                            val id = ch.id.value
                                            if (ch.pressed && !ch.previousPressed) {
                                                val wp = x.toWorld(ch.position)
                                                val seat = when {
                                                    isNetworked -> humanSeats.first()
                                                    humanSeats.size == 1 -> humanSeats.first()
                                                    else -> if (wp.y > 0) 0 else 1
                                                }
                                                if (seat in humanSeats) starts[id] = SwipeStart(seat, ch.position, ch.uptimeMillis)
                                                ch.consume()
                                            } else if (!ch.pressed && ch.previousPressed) {
                                                val st = starts.remove(id)
                                                if (st != null) {
                                                    val dtMs = (ch.uptimeMillis - st.time).coerceAtLeast(16L)
                                                    val dxPx = ch.position.x - st.pos.x; val dyPx = ch.position.y - st.pos.y
                                                    val lenPx = hypot(dxPx, dyPx)
                                                    val lenDp = lenPx / density.density
                                                    if (lenDp < 24f) {
                                                        if (cur.assist) game.swing(st.seat, 0.0, 0.6) else why(androidContext.getString(R.string.tte_why_swipe))
                                                    } else {
                                                        val w0 = x.toWorld(st.pos); val w1 = x.toWorld(ch.position)
                                                        val wvx = (w1.x - w0.x).toDouble(); val wvy = (w1.y - w0.y).toDouble()
                                                        val ss = if (st.seat == 0) 1.0 else -1.0
                                                        val fwd = wvy * -ss
                                                        val lat = wvx * ss
                                                        val ang = atan2(lat, maxOf(fwd, 0.2 * hypot(wvx, wvy)))
                                                        val dirX = (ang / (PI / 3)).coerceIn(-1.0, 1.0)
                                                        val speed = lenDp / dtMs.toFloat()
                                                        val power = ((speed - 0.3) / 1.7).coerceIn(0.0, 1.0)
                                                        game.swing(st.seat, dirX, power)
                                                    }
                                                    ch.consume()
                                                }
                                            } else if (ch.pressed) {
                                                if (starts.containsKey(id)) ch.consume()
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        val xfNow = xf ?: return@Canvas
                        drawCourt(xfNow, colors)
                        val st = c
                        // Hint target.
                        val h = hint
                        if (h != null) {
                            val t = xfNow.toScreen(h.targetX, h.targetY)
                            drawCircle(colors.gold.copy(alpha = 0.35f + 0.3f * pulse), xfNow.len(0.9), t)
                            drawCircle(colors.gold, xfNow.len(0.9), t, style = Stroke(xfNow.len(0.12)))
                        }
                        // Timing ring where the ball will be hit.
                        val cueSide = if (st.phase == TPhase.TOSS) st.server else if (st.phase == TPhase.RALLY && st.lastHitter >= 0) 1 - st.lastHitter else -1
                        if (cueSide >= 0 && cueSide in humanSeats && st.cTick >= 0 && st.bounces <= 1) {
                            val remaining = (st.cTick - st.tick) * TennisRules.DT
                            if (remaining > -0.3) {
                                val z = if (st.phase == TPhase.TOSS) st.cZ else 0.9
                                val center = xfNow.toScreen(st.cX, st.cY, z)
                                val ringR = xfNow.len(0.25 + (remaining.coerceIn(0.0, 1.0)) * 1.6)
                                val good = abs(remaining) <= TennisRules.GOOD
                                val ringColor = if (v.assist) colors.gold else if (abs(remaining) <= TennisRules.PERFECT) Color(0xFF3DDC84) else if (good) colors.gold else Color.White
                                drawCircle(ringColor.copy(alpha = if (st.held) 0.6f + 0.4f * pulse else 0.9f), if (st.held) xfNow.len(0.45 + 0.1 * pulse) else ringR, center, style = Stroke(xfNow.len(0.1)))
                                drawCircle(ringColor.copy(alpha = 0.35f), xfNow.len(0.25), center)
                            }
                        }
                        drawNet(xfNow, colors)
                        // Players.
                        for (side in 0..1) {
                            val p = xfNow.toScreen(st.px(side), st.py(side))
                            val r = xfNow.len(0.85)
                            drawStFigure(p, r, side, colors.dark, keeper = false, ready = false, pulse = 0f, outline = colorblind)
                            // Racket: swings across when this side just swung.
                            val phase = if (swingSide == side) swing.value else 1f
                            val fwdSign = if ((side == 0) != (viewSide == 1)) -1f else 1f
                            val a = -1.1f + 2.2f * phase
                            val dirV = if (!xfNow.landscape) Offset(kotlin.math.sin(a), fwdSign * kotlin.math.cos(a)) else Offset(fwdSign * kotlin.math.cos(a), kotlin.math.sin(a))
                            val handle = p + dirV * (r * 1.0f)
                            val head = p + dirV * (r * 2.0f)
                            drawLine(Color(0xFF3A2A1A), handle, head, strokeWidth = r * 0.14f)
                            drawCircle(Color(0xFFEFE7D0), r * 0.55f, head)
                            drawCircle(Color(0xFF3A2A1A), r * 0.55f, head, style = Stroke(r * 0.12f))
                        }
                        // Ball with its shadow on the court and a short trail.
                        val b = xfNow.toScreen(st.bx, st.by, st.bz)
                        val shadow = xfNow.toScreen(st.bx, st.by)
                        val speed = hypot(st.bvx, st.bvy)
                        drawCircle(Color(0x55000000), xfNow.len(0.16), shadow)
                        if (enhanced && speed > 8.0) {
                            for (k in 1..4) {
                                val tcol = xfNow.toScreen(st.bx - st.bvx * 0.012 * k, st.by - st.bvy * 0.012 * k, st.bz - st.bvz * 0.012 * k)
                                drawCircle(Color(0xFFE8F36B).copy(alpha = 0.3f - 0.06f * k), xfNow.len(0.15 * (1 - 0.1 * k)), tcol)
                            }
                        }
                        drawCircle(Color(0xFFE8F36B), xfNow.len(0.17), b)
                        drawCircle(Color(0xFF9DA83A), xfNow.len(0.17), b, style = Stroke(xfNow.len(0.025)))
                        drawBurst(burst)
                    }
                    if (banner.isNotEmpty() && c.phase != TPhase.OVER) {
                        Text(banner, color = Color.White, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).graphicsLayer {
                                val sc = 0.7f + 0.3f * pointFlash.value
                                scaleX = sc; scaleY = sc
                            }.widthIn(max = 280.dp).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(boxW * 0.34f).fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                        Header(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        TennisFooter(v, colors, screenReader, dirChoice, powerChoice, sessionWins, { dirChoice = it }, { powerChoice = it }, ::panelSwing, game, ::name, game::leaveSession)
                    }
                    Court(Modifier.weight(1f).fillMaxSize().padding(top = 8.dp, end = 4.dp, bottom = 4.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Header(Modifier.fillMaxWidth())
                    Court(Modifier.fillMaxWidth().height((boxH * 0.78f).coerceAtLeast(300.dp)))
                    TennisFooter(v, colors, screenReader, dirChoice, powerChoice, sessionWins, { dirChoice = it }, { powerChoice = it }, ::panelSwing, game, ::name, game::leaveSession)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TennisFooter(
    v: TennisView, colors: TennisColors, screenReader: Boolean, dirChoice: Int, powerChoice: Int, sessionWins: List<Int>,
    onDir: (Int) -> Unit, onPower: (Int) -> Unit, onSwing: () -> Unit, game: ToyTennisGame,
    name: (Int) -> String, onLeave: () -> Unit
) {
    val c = v.court
    if (c.phase == TPhase.OVER) {
        val w = c.score.winner.coerceAtLeast(0)
        val sc = c.score
        val detail = if (sc.format.sets) (0 until sc.finishedSetsA.size).joinToString(", ") { "${sc.finishedSetsA[it]}-${sc.finishedSetsB[it]}" }
        else "${sc.pointsA}-${sc.pointsB}"
        ClFinishedPanel(
            title = stringResource(R.string.tte_over_title, name(w)),
            subtitle = detail,
            lines = listOf(stringResource(R.string.tte_over_session, name(0), sessionWins[0], name(1), sessionWins[1])),
            canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = onLeave
        )
        return
    }
    if (!screenReader) return
    val btn = ButtonDefaults.outlinedButtonColors(contentColor = colors.text)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.tte_a11y_title), color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tte_a11y_hint), color = colors.text, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.tte_dir_left, R.string.tte_dir_straight, R.string.tte_dir_right).forEachIndexed { i, id ->
                val label = stringResource(id)
                if (dirChoice == i) Button(onClick = { onDir(i) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                else OutlinedButton(onClick = { onDir(i) }, colors = btn, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.tsc_pow_soft, R.string.tsc_pow_med, R.string.tsc_pow_hard).forEachIndexed { i, id ->
                val label = stringResource(id)
                if (powerChoice == i) Button(onClick = { onPower(i) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                else OutlinedButton(onClick = { onPower(i) }, colors = btn, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
        Button(onClick = onSwing, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.tte_swing_btn)) }
    }
}

private fun DrawScope.drawCourt(x: CourtXf, c: TennisColors) {
    val hw = TennisRules.HW; val hl = TennisRules.HL
    val tl = x.toScreen(-6.2, -14.8); val br = x.toScreen(6.2, 14.8)
    drawRect(c.courtOut, Offset(minOf(tl.x, br.x), minOf(tl.y, br.y)), Size(abs(br.x - tl.x), abs(br.y - tl.y)))
    val a = x.toScreen(-hw - 1.2, -hl - 2.2); val b = x.toScreen(hw + 1.2, hl + 2.2)
    drawRect(c.courtIn, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y)))
    val lw = x.len(0.07)
    fun seg(x1: Double, y1: Double, x2: Double, y2: Double) = drawLine(c.line, x.toScreen(x1, y1), x.toScreen(x2, y2), strokeWidth = lw)
    seg(-hw, -hl, hw, -hl); seg(-hw, hl, hw, hl)
    seg(-hw, -hl, -hw, hl); seg(hw, -hl, hw, hl)
    seg(-hw, -TennisRules.SERVICE, hw, -TennisRules.SERVICE); seg(-hw, TennisRules.SERVICE, hw, TennisRules.SERVICE)
    seg(0.0, -TennisRules.SERVICE, 0.0, TennisRules.SERVICE)
    seg(0.0, -hl, 0.0, -hl + 0.3); seg(0.0, hl, 0.0, hl - 0.3)
}

private fun DrawScope.drawNet(x: CourtXf, c: TennisColors) {
    val hw = TennisRules.HW + 0.6
    val l = x.toScreen(-hw, 0.0, 0.0); val r = x.toScreen(hw, 0.0, 0.0)
    val lt = x.toScreen(-hw, 0.0, TennisRules.NET_H); val rt = x.toScreen(hw, 0.0, TennisRules.NET_H)
    drawLine(Color(0x55000000), l, r, strokeWidth = x.len(0.14))
    val steps = 24
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val a = Offset(l.x + (r.x - l.x) * t, l.y + (r.y - l.y) * t)
        val b = Offset(lt.x + (rt.x - lt.x) * t, lt.y + (rt.y - lt.y) * t)
        drawLine(Color.White.copy(alpha = 0.45f), a, b, strokeWidth = 1.5f)
    }
    drawLine(Color.White, lt, rt, strokeWidth = x.len(0.1))
    drawCircle(Color(0xFF444444), x.len(0.14), lt)
    drawCircle(Color(0xFF444444), x.len(0.14), rt)
}
