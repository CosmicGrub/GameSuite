package com.gamesuite.ui

import android.content.Context
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import com.gamesuite.games.toysoccer.SoccerBody
import com.gamesuite.games.toysoccer.SoccerEvent
import com.gamesuite.games.toysoccer.SoccerPhase
import com.gamesuite.games.toysoccer.SoccerReject
import com.gamesuite.games.toysoccer.SoccerRules
import com.gamesuite.games.toysoccer.SoccerState
import com.gamesuite.games.toysoccer.ToySoccerGame
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

private class SoccerColors(
    val bg: Color, val text: Color, val card: Color, val accent: Color, val gold: Color,
    val grassA: Color, val grassB: Color, val line: Color, val dark: Boolean
)

private fun soccerColors(dark: Boolean) = if (!dark) SoccerColors(
    bg = Color(0xFFF1F4EC), text = Color(0xFF1F2A1C), card = Color(0xFFDDE5D3), accent = Color(0xFF1E7B34),
    gold = Color(0xFFE0A100), grassA = Color(0xFF3E9B4F), grassB = Color(0xFF369046), line = Color(0xE6FFFFFF), dark = false
) else SoccerColors(
    bg = Color(0xFF121712), text = Color(0xFFE8F0E3), card = Color(0xFF222B21), accent = Color(0xFF5BD477),
    gold = Color(0xFFFFC83D), grassA = Color(0xFF2B6D38), grassB = Color(0xFF25622F), line = Color(0xCCEAF3E6), dark = true
)

/** The pitch drawn in the canvas: maps world units to pixels, turned when the screen is wide and flipped for team 1's view. */
private class PitchXf(val w: Float, val h: Float, val flip: Boolean) {
    val landscape = w > h * 1.05f
    private val pad = 0.03f * min(w, h)
    // The goal nets stand outside the touchlines (about 7.5 percent of the pitch width): leave room for them so they never cover the text above the pitch.
    private val lengthWithGoals = SoccerRules.L.toFloat() + 2f * 0.075f * SoccerRules.W.toFloat()
    val scale: Float = if (!landscape) min((w - 2 * pad) / SoccerRules.W.toFloat(), (h - 2 * pad) / lengthWithGoals)
    else min((w - 2 * pad) / lengthWithGoals, (h - 2 * pad) / SoccerRules.W.toFloat())
    private val sw = (if (!landscape) SoccerRules.W else SoccerRules.L).toFloat() * scale
    private val sh = (if (!landscape) SoccerRules.L else SoccerRules.W).toFloat() * scale
    private val ox = (w - sw) / 2
    private val oy = (h - sh) / 2

    fun toScreen(wx: Double, wy: Double): Offset {
        var x = wx.toFloat(); var y = wy.toFloat()
        if (flip) { x = SoccerRules.W.toFloat() - x; y = SoccerRules.L.toFloat() - y }
        return if (!landscape) Offset(ox + x * scale, oy + y * scale) else Offset(ox + y * scale, oy + x * scale)
    }

    fun toWorld(p: Offset): Offset {
        var x: Float; var y: Float
        if (!landscape) { x = (p.x - ox) / scale; y = (p.y - oy) / scale } else { y = (p.x - ox) / scale; x = (p.y - oy) / scale }
        if (flip) { x = SoccerRules.W.toFloat() - x; y = SoccerRules.L.toFloat() - y }
        return Offset(x, y)
    }

    fun len(u: Double): Float = (u * scale).toFloat()
}

/** A pull in progress: which figure, for which seat, and where the finger is (world units). */
private data class SoccerAim(val team: Int, val figure: Int, val seat: Int, val finger: Offset)

private fun Context.soccerTeamName(team: Int): String = getString(if (team == 0) R.string.tsc_team_a else R.string.tsc_team_b)

/**
 * Toy Soccer. Pull back from one of your figures and let go to flick it (slingshot: one finger, the
 * finger never covers the target, aim and power come straight off the pull). The pitch turns to the
 * long way round in landscape and is flipped for the team on the far side so your own goal is always
 * nearest you. Two people can flick at the same time in Pass and Play (and 2v2). With a screen reader
 * on, a button panel replaces the drag: pick a figure, an aim, a power and flick. Reduced Motion, game
 * speed, colour-blind teams (stripes and triangles against dots and diamonds), dark mode and large
 * fonts are all handled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToySoccerScreen(
    sessionManager: GameSessionManager,
    game: ToySoccerGame,
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
    val state by game.state
    val hint by game.hint
    val lastReject by game.lastReject
    val opponentLeft by game.opponentLeft
    val sessionWins by game.sessionWins
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val colors = soccerColors(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reduced
    val colorblind = LocalColorblindMode.current
    val screenReader = rememberClTouchExploration()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.assist = extras["assist"] == "true"
        game.matchSpec = extras["match"] ?: "first3"
        game.firstTeam = sessionManager.launchOptions.firstMoverIndex.coerceAtLeast(0) % 2
        game.cpuReactionScale = gameSpeed.delayScale.toDouble()
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }
    LaunchedEffect(gameSpeed) { game.cpuReactionScale = gameSpeed.delayScale.toDouble() }

    // The frame loop: the table advances by the real time since the last frame.
    LaunchedEffect(game) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { game.advance() }
        }
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val seatCount = activeCtx?.players?.size ?: 2
    val helpTitle = stringResource(R.string.tsc_help_title)
    val helpText = stringResource(R.string.tsc_help_text)
    val s = state
    if (s == null) {
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

    // Seats this device may flick for, and the team whose goal is nearest the player.
    val humanSeats: Set<Int> = if (isNetworked) setOf(localSeat) else s.players.indices.filter { !s.players[it].isBot }.toSet()
    val viewTeam = if (isNetworked) SoccerRules.teamOfSeat(localSeat) else 0
    val hotSeat = !isNetworked && humanSeats.map { SoccerRules.teamOfSeat(it) }.toSet().size > 1
    fun seatName(i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    fun teamNames(t: Int): String = SoccerRules.membersOf(t, s.players.size).joinToString(" + ") { seatName(it) }
    val teamWord = listOf(androidContext.soccerTeamName(0), androidContext.soccerTeamName(1))
    fun teamLabel(t: Int): String = teamWord[t]
    fun localTeamWon(t: Int?): Boolean = t != null && (if (hotSeat) false else t == viewTeam)

    var whyText by remember { mutableStateOf<String?>(null) }
    var whySerial by remember { mutableIntStateOf(0) }
    val whyStrings = mapOf(
        SoccerReject.NOT_STARTED to stringResource(R.string.tsc_why_not_started),
        SoccerReject.NOT_YOUR_FIGURE to stringResource(R.string.tsc_why_not_yours),
        SoccerReject.NOT_YOUR_TURN to stringResource(R.string.tsc_why_not_turn),
        SoccerReject.STILL_MOVING to stringResource(R.string.tsc_why_moving),
        SoccerReject.TOO_WEAK to stringResource(R.string.tsc_why_weak),
        SoccerReject.OPPONENT_LEFT to stringResource(R.string.tsc_why_left),
        SoccerReject.NOT_PLAYING to stringResource(R.string.tsc_why_over),
        SoccerReject.BAD_INPUT to stringResource(R.string.tsc_why_over)
    )
    fun showWhy(r: SoccerReject) {
        val t = whyStrings[r] ?: return
        whyText = t; whySerial++
    }
    LaunchedEffect(lastReject?.serial) {
        val r = lastReject ?: return@LaunchedEffect
        showWhy(r.reason)
        playSfx(SfxKind.INVALID_BUZZ, 0f)
        haptics(HapticSignal.FAILURE)
    }
    LaunchedEffect(whySerial) {
        if (whySerial == 0) return@LaunchedEffect
        val mine = whySerial
        delay(3200)
        if (whySerial == mine) whyText = null
    }

    // Sound, haptics and effects follow the engine's serials.
    fun pan(x: Double): Float {
        val f = ((x / SoccerRules.W) * 2 - 1).toFloat().coerceIn(-1f, 1f)
        return if (viewTeam == 1) -f else f
    }
    val impact = remember { Animatable(1f) }
    var impactAt by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(s.flickSerial) {
        if (s.flickSerial == 0) return@LaunchedEffect
        playSfx(SfxKind.WHOOSH, 0f)
        haptics(HapticSignal.LIGHT_TICK)
    }
    LaunchedEffect(s.kickSerial) {
        if (s.kickSerial == 0) return@LaunchedEffect
        playSfx(SfxKind.SOLID_THUNK, pan(s.hitX))
        haptics(if (s.hitStrength > 0.6) HapticSignal.STRONG_ACTION else if (s.hitStrength > 0.3) HapticSignal.NORMAL_ACTION else HapticSignal.LIGHT_TICK)
        if (enhanced) {
            val ball = s.bodies.first()
            impactAt = Offset(ball.x.toFloat(), ball.y.toFloat())
            impact.snapTo(0f)
            impact.animateTo(1f, tween(gameSpeed.scale(420L).toInt().coerceAtLeast(160), easing = LinearEasing))
        }
    }
    LaunchedEffect(s.wallSerial) { if (s.wallSerial > 0) playSfx(SfxKind.LIGHT_TICK, pan(s.hitX)) }
    LaunchedEffect(s.bumpSerial) { if (s.bumpSerial > 0) playSfx(SfxKind.LIGHT_TICK, 0f) }
    val goalScale = remember { Animatable(0f) }
    LaunchedEffect(s.goalSerial) {
        if (s.goalSerial == 0) return@LaunchedEffect
        val scorer = s.eventTeam
        val mine = scorer != null && (hotSeat || scorer == viewTeam)
        playSfx(if (mine) SfxKind.SUCCESS_CHIME else SfxKind.INVALID_BUZZ, 0f)
        haptics(if (mine) HapticSignal.CELEBRATION else HapticSignal.FAILURE)
        if (!reduced && mine) {
            burst.spawn(
                Offset(0.5f, if (scorer == viewTeam || hotSeat) 0.12f else 0.88f), 60,
                listOf(colors.gold, Color.White, colors.accent, stTeamColor(scorer ?: 0, colors.dark)),
                speedRange = 0.25f..0.9f, lifeRangeSeconds = 0.9f..1.8f, gravity = 0.9f
            )
        }
        if (!reduced) {
            goalScale.snapTo(0f)
            goalScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        } else goalScale.snapTo(1f)
    }
    LaunchedEffect(s.phase) {
        if (s.phase == SoccerPhase.PLAYING && !s.assist && s.goalSerial + s.flickSerial >= 0 && s.event == SoccerEvent.GO) {
            playSfx(SfxKind.SUCCESS_CHIME, 0f)
            haptics(HapticSignal.STRONG_ACTION)
        }
        if (s.phase == SoccerPhase.OVER) {
            val win = localTeamWon(s.winnerTeam) || hotSeat
            playSfx(if (win) SfxKind.SUCCESS_CHIME else SfxKind.INVALID_BUZZ, 0f)
            haptics(if (win) HapticSignal.CELEBRATION else HapticSignal.FAILURE)
            if (!reduced && win) burst.spawn(Offset(0.5f, 0.4f), 90, listOf(colors.gold, Color.White, colors.accent), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
        }
    }
    val countdownDigit = if (s.phase == SoccerPhase.COUNTDOWN && !s.assist) (s.phaseTicks / 90 + 1).coerceIn(1, 3) else 0
    LaunchedEffect(countdownDigit) { if (countdownDigit > 0 && s.phaseTicks > 0 && s.goalSerial >= 0) playSfx(SfxKind.LIGHT_TICK, 0f) }

    // ---- words ----
    val status: String = run {
        val last = when (s.event) {
            SoccerEvent.GOAL -> {
                val t = s.eventTeam ?: 0
                if (!hotSeat && !isNetworked && t == viewTeam) androidContext.getString(R.string.tsc_ann_goal_you, s.score[t], s.score[1 - t])
                else androidContext.getString(R.string.tsc_ann_goal, teamLabel(t), s.score[t], s.score[1 - t])
            }
            SoccerEvent.OVERTIME -> stringResource(R.string.tsc_ann_overtime)
            SoccerEvent.WIN -> {
                val t = s.winnerTeam ?: 0
                if (!hotSeat && t == viewTeam) stringResource(R.string.tsc_ann_win_you) else androidContext.getString(R.string.tsc_ann_win, teamLabel(t))
            }
            else -> ""
        }
        val now = when {
            s.matchOver -> ""
            opponentLeft -> stringResource(R.string.tsc_opp_left)
            s.phase == SoccerPhase.COUNTDOWN || s.phase == SoccerPhase.GOAL -> if (s.phase == SoccerPhase.COUNTDOWN) stringResource(R.string.tsc_ann_wait) else ""
            s.assist && s.turnTeam == viewTeam && !hotSeat -> stringResource(R.string.tsc_ann_turn_you)
            s.assist -> androidContext.getString(R.string.tsc_ann_turn, teamLabel(s.turnTeam))
            else -> stringResource(R.string.tsc_status_play)
        }
        listOf(last, now).filter { it.isNotBlank() }.joinToString(" ")
    }

    // ---- accessible (screen reader) controls ----
    var selFig by remember { mutableIntStateOf(0) }
    var aimMode by remember { mutableIntStateOf(0) }
    var powerMode by remember { mutableIntStateOf(1) }
    val myFigures: List<Pair<Int, Int>> = remember(s.figuresPerTeam, humanSeats, viewTeam) { // (team, figure)
        val out = ArrayList<Pair<Int, Int>>()
        for (t in 0..1) for (f in 0 until s.figuresPerTeam) {
            if (SoccerRules.controllerSeat(t, f, s.players.size) in humanSeats && (isNetworked || !hotSeat || t == viewTeam)) out += t to f
        }
        out
    }
    fun doPanelFlick() {
        val (t, f) = myFigures.getOrNull(selFig.coerceIn(0, (myFigures.size - 1).coerceAtLeast(0))) ?: return
        val body = s.bodies.first { it.team == t && it.figure == f }
        val ball = s.bodies.first()
        val attackY = if (t == 0) 0.0 else SoccerRules.L
        val target = when (aimMode) {
            0 -> Offset(ball.x.toFloat(), ball.y.toFloat())
            1 -> Offset(SoccerRules.CX.toFloat(), attackY.toFloat())
            else -> Offset(SoccerRules.CX.toFloat(), (SoccerRules.L / 2).toFloat())
        }
        var dx = target.x - body.x.toFloat(); var dy = target.y - body.y.toFloat()
        val d = hypot(dx, dy).coerceAtLeast(1f)
        val frac = when (powerMode) { 0 -> 0.35f; 1 -> 0.65f; else -> 1f }
        val pull = (SoccerRules.MAX_PULL * frac).toFloat()
        dx = dx / d * pull; dy = dy / d * pull
        game.flick(SoccerRules.controllerSeat(t, f, s.players.size), f, dx.toDouble(), dy.toDouble())
    }

    val finishedMatches = sessionWins.sum()
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.matchOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedMatches > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedMatches == 0,
        onHint = if (isNetworked) null else ({ game.requestHint(humanSeats.minOrNull() ?: 0) }),
        buttonFill = colors.bg, buttonContent = colors.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedMatches > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body),
        extraItems = { _ ->
            androidx.compose.runtime.DisposableEffect(Unit) {
                game.pause()
                onDispose { game.resume() }
            }
        }
    ) {
        val (_, openCoach) = rememberClCoach("toy-soccer", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
            val boxW = maxWidth
            val boxH = maxHeight
            val landscape = boxW > boxH * 1.15f

            @Composable
            fun Header(modifier: Modifier) {
                Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(end = if (landscape) 0.dp else GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                        ClCoachButton("toy-soccer", openCoach)
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(viewTeam, 1 - viewTeam).forEach { t ->
                                val glyph = if (t == 0) "▲" else "◆"
                                val names = teamNames(t)
                                val desc = stringResource(R.string.tsc_chip_desc, teamLabel(t), names, s.score[t])
                                Text(
                                    "$glyph $names  ${s.score[t]}", color = colors.text, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(14.dp))
                                        .background(colors.card)
                                        .border(2.dp, stTeamColor(t, colors.dark), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .semantics { contentDescription = desc }
                                )
                            }
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val limit = when {
                            s.goldenGoal -> stringResource(R.string.tsc_sudden)
                            s.ticksLeft >= 0 -> stringResource(R.string.tsc_time_left, stClock(s.ticksLeft, SoccerRules.STEPS_PER_SECOND))
                            s.flicksLeft >= 0 -> stringResource(R.string.tsc_flicks_left, s.flicksLeft)
                            s.target > 0 -> stringResource(R.string.tsc_target_first, s.target)
                            else -> ""
                        }
                        if (limit.isNotEmpty()) Text(limit, color = colors.text, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, colors.accent, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                        if (s.assist) Text(stringResource(R.string.tsc_assist_badge), color = colors.text, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, colors.accent, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                        if (!isNetworked && !s.matchOver) {
                            OutlinedButton(
                                onClick = { game.requestHint(humanSeats.minOrNull() ?: 0) },
                                enabled = s.phase == SoccerPhase.PLAYING,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.ccl_hint)) }
                        }
                    }
                    ClStatusLine(status, colors.text, Modifier.fillMaxWidth().padding(top = 4.dp), emphasized = true)
                    ClWhyLine(whyText, colors.text, colors.card)
                }
            }

            @Composable
            fun Pitch(modifier: Modifier) {
                var size by remember { mutableStateOf(IntSize.Zero) }
                val xf = if (size.width > 0 && size.height > 0) PitchXf(size.width.toFloat(), size.height.toFloat(), viewTeam == 1) else null
                val latestState = rememberUpdatedState(s)
                val latestXf = rememberUpdatedState(xf)
                val aims = remember { mutableStateMapOf<Long, SoccerAim>() }
                val pulse by rememberInfiniteTransition(label = "ready").animateFloat(
                    0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse"
                )
                val pitchDesc = stringResource(R.string.tsc_pitch_desc, teamLabel(0), s.score[0], teamLabel(1), s.score[1])
                Box(modifier.onSizeChanged { size = it }) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .semantics { contentDescription = pitchDesc }
                            .pointerInput(isNetworked, humanSeats) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val cur = latestState.value
                                        val x = latestXf.value
                                        for (ch in event.changes) {
                                            val id = ch.id.value
                                            if (x == null) continue
                                            val wp = x.toWorld(ch.position)
                                            if (ch.pressed && !ch.previousPressed) {
                                                // Which of my figures is under the finger?
                                                var best: SoccerBody? = null; var bd = Float.MAX_VALUE
                                                for (b in cur.bodies) {
                                                    if (b.kind == 0) continue
                                                    val d = hypot(wp.x - b.x.toFloat(), wp.y - b.y.toFloat())
                                                    if (d < b.r * 1.6 && d < bd) { bd = d; best = b }
                                                }
                                                val b = best
                                                if (b != null) {
                                                    val seat = SoccerRules.controllerSeat(b.team, b.figure, cur.players.size)
                                                    val mine = seat in humanSeats
                                                    when {
                                                        !mine -> { showWhy(SoccerReject.NOT_YOUR_FIGURE) }
                                                        cur.phase != SoccerPhase.PLAYING -> showWhy(SoccerReject.NOT_STARTED)
                                                        cur.assist && cur.turnTeam != b.team -> showWhy(SoccerReject.NOT_YOUR_TURN)
                                                        cur.assist && cur.turnFlicked -> showWhy(SoccerReject.NOT_YOUR_TURN)
                                                        !b.ready -> showWhy(SoccerReject.STILL_MOVING)
                                                        else -> aims[id] = SoccerAim(b.team, b.figure, seat, wp)
                                                    }
                                                }
                                                ch.consume()
                                            } else if (ch.pressed) {
                                                val a = aims[id]
                                                if (a != null) { aims[id] = a.copy(finger = wp); ch.consume() }
                                            } else if (!ch.pressed && ch.previousPressed) {
                                                val a = aims.remove(id)
                                                if (a != null) {
                                                    val body = cur.bodies.firstOrNull { it.team == a.team && it.figure == a.figure }
                                                    if (body != null) {
                                                        val px = body.x - a.finger.x; val py = body.y - a.finger.y
                                                        if (hypot(px, py) < SoccerRules.MIN_PULL) showWhy(SoccerReject.TOO_WEAK)
                                                        else game.flick(a.seat, a.figure, px, py)
                                                    }
                                                    ch.consume()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        val xfNow = xf ?: return@Canvas
                        drawPitch(xfNow, colors)
                        // Impact ring where the ball was last kicked.
                        if (enhanced && impact.value < 1f) {
                            val c = xfNow.toScreen(impactAt.x.toDouble(), impactAt.y.toDouble())
                            drawCircle(Color.White.copy(alpha = 0.7f * (1f - impact.value)), xfNow.len(30.0 + 90.0 * impact.value), c, style = Stroke(xfNow.len(6.0)))
                        }
                        // Hint arrow.
                        val h = hint
                        if (h != null) {
                            val b = s.bodies.firstOrNull { it.team == h.team && it.figure == h.plan.figure }
                            if (b != null) {
                                val from = xfNow.toScreen(b.x, b.y)
                                val to = xfNow.toScreen(b.x + h.plan.pullX * 2.2, b.y + h.plan.pullY * 2.2)
                                drawLine(colors.gold, from, to, strokeWidth = xfNow.len(14.0), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)))
                                drawCircle(colors.gold, xfNow.len(20.0), to)
                                drawCircle(colors.gold, xfNow.len(b.r * 1.35), from, style = Stroke(xfNow.len(8.0)))
                            }
                        }
                        // Figures first, the ball above them.
                        for (b in s.bodies) {
                            if (b.kind == 0) continue
                            val c = xfNow.toScreen(b.x, b.y)
                            val owned = SoccerRules.controllerSeat(b.team, b.figure, s.players.size) in humanSeats
                            val canPlay = owned && s.phase == SoccerPhase.PLAYING && (!s.assist || (s.turnTeam == b.team && !s.turnFlicked))
                            drawStFigure(c, xfNow.len(b.r), b.team, colors.dark, b.kind == 1, b.ready && canPlay, if (reduced) 0.5f else pulse, outline = colorblind)
                        }
                        // Aim lines for fingers that are pulling.
                        for (a in aims.values) {
                            val b = s.bodies.firstOrNull { it.team == a.team && it.figure == a.figure } ?: continue
                            val pullLen = hypot(b.x - a.finger.x, b.y - a.finger.y).coerceAtMost(SoccerRules.MAX_PULL)
                            val dirX = (b.x - a.finger.x) / hypot(b.x - a.finger.x, b.y - a.finger.y).coerceAtLeast(1e-6)
                            val dirY = (b.y - a.finger.y) / hypot(b.x - a.finger.x, b.y - a.finger.y).coerceAtLeast(1e-6)
                            val from = xfNow.toScreen(b.x, b.y)
                            val power = (pullLen / SoccerRules.MAX_PULL).toFloat()
                            val to = xfNow.toScreen(b.x + dirX * (80 + 520 * power), b.y + dirY * (80 + 520 * power))
                            val rubber = xfNow.toScreen(a.finger.x.toDouble(), a.finger.y.toDouble())
                            drawLine(Color.White.copy(alpha = 0.5f), from, rubber, strokeWidth = xfNow.len(7.0))
                            drawLine(Color.White, from, to, strokeWidth = xfNow.len(12.0), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
                            drawCircle(Color.White, xfNow.len(14.0 + 14 * power), to)
                            drawCircle(Color.White.copy(alpha = 0.85f), xfNow.len(b.r * (1.2 + 0.3 * power)), from, style = Stroke(xfNow.len(8.0)))
                        }
                        val ball = s.bodies.first()
                        val bc = xfNow.toScreen(ball.x, ball.y)
                        val sp = hypot(ball.vx, ball.vy)
                        if (enhanced && sp > 500) {
                            for (k in 1..4) {
                                val t = k * 0.012
                                val tc = xfNow.toScreen(ball.x - ball.vx * t, ball.y - ball.vy * t)
                                drawCircle(Color.White.copy(alpha = 0.22f - 0.04f * k), xfNow.len(ball.r * (1.0 - 0.12 * k)), tc)
                            }
                        }
                        drawStBall(bc, xfNow.len(ball.r), ((ball.x + ball.y) / ball.r).toFloat())
                        drawBurst(burst)
                    }
                    // Big overlay words: countdown digit, GO, and the goal banner.
                    if (countdownDigit > 0) {
                        Text(countdownDigit.toString(), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = 0.85f })
                    }
                    if (s.phase == SoccerPhase.GOAL) {
                        Text(stringResource(R.string.tsc_goal_banner), color = colors.gold, fontSize = 72.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.align(Alignment.Center).graphicsLayer {
                                val sc = 0.6f + 0.6f * goalScale.value
                                scaleX = sc; scaleY = sc
                            })
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(boxW * 0.34f).fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                        Header(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        SoccerFooter(s, colors, screenReader, myFigures, selFig, aimMode, powerMode, seatName = ::seatName,
                            teamLabel = ::teamLabel, sessionWins = sessionWins, onSel = { selFig = it }, onAim = { aimMode = it },
                            onPower = { powerMode = it }, onFlick = ::doPanelFlick, game = game, onLeave = game::leaveSession)
                    }
                    Pitch(Modifier.weight(1f).fillMaxSize().padding(top = 8.dp, end = 4.dp, bottom = 4.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Header(Modifier.fillMaxWidth())
                    val pitchH = (boxH * 0.78f).coerceAtLeast(300.dp)
                    Pitch(Modifier.fillMaxWidth().height(pitchH))
                    SoccerFooter(s, colors, screenReader, myFigures, selFig, aimMode, powerMode, seatName = ::seatName,
                        teamLabel = ::teamLabel, sessionWins = sessionWins, onSel = { selFig = it }, onAim = { aimMode = it },
                        onPower = { powerMode = it }, onFlick = ::doPanelFlick, game = game, onLeave = game::leaveSession)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoccerFooter(
    s: SoccerState, colors: SoccerColors, screenReader: Boolean,
    myFigures: List<Pair<Int, Int>>, selFig: Int, aimMode: Int, powerMode: Int,
    seatName: (Int) -> String, teamLabel: (Int) -> String, sessionWins: List<Int>,
    onSel: (Int) -> Unit, onAim: (Int) -> Unit, onPower: (Int) -> Unit, onFlick: () -> Unit,
    game: ToySoccerGame, onLeave: () -> Unit
) {
    if (s.matchOver) {
        val w = s.winnerTeam ?: 0
        ClFinishedPanel(
            title = stringResource(R.string.tsc_over_title, teamLabel(w)),
            subtitle = stringResource(R.string.tsc_over_sub, s.score[0], s.score[1]),
            lines = listOf(stringResource(R.string.tsc_over_session, sessionWins[0], sessionWins[1])),
            canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = onLeave
        )
        return
    }
    if (!screenReader || myFigures.isEmpty()) return
    val sel = selFig.coerceIn(0, myFigures.size - 1)
    val (t, f) = myFigures[sel]
    val body = s.bodies.firstOrNull { it.team == t && it.figure == f }
    val seat = SoccerRules.controllerSeat(t, f, s.players.size)
    val role = stringResource(if (f == 0) R.string.tsc_keeper else R.string.tsc_field)
    val readyWord = stringResource(if (body?.ready == true) R.string.tsc_ready else R.string.tsc_moving)
    val figDesc = stringResource(R.string.tsc_fig_desc, role, sel + 1, myFigures.size, readyWord)
    val btn = ButtonDefaults.outlinedButtonColors(contentColor = colors.text)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.tsc_a11y_title), color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(figDesc + " " + seatName(seat), color = colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onSel((sel + myFigures.size - 1) % myFigures.size) }, colors = btn, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.tsc_fig_prev)) }
            OutlinedButton(onClick = { onSel((sel + 1) % myFigures.size) }, colors = btn, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.tsc_fig_next)) }
        }
        val aimNames = listOf(R.string.tsc_aim_ball, R.string.tsc_aim_goal, R.string.tsc_aim_clear)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            aimNames.forEachIndexed { i, id ->
                val label = stringResource(id)
                val chosen = aimMode == i
                (if (chosen) Button(onClick = { onAim(i) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) { Text(label) }
                else OutlinedButton(onClick = { onAim(i) }, colors = btn, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) })
            }
        }
        val powNames = listOf(R.string.tsc_pow_soft, R.string.tsc_pow_med, R.string.tsc_pow_hard)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            powNames.forEachIndexed { i, id ->
                val label = stringResource(id)
                if (powerMode == i) Button(onClick = { onPower(i) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                else OutlinedButton(onClick = { onPower(i) }, colors = btn, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
        Button(onClick = onFlick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.tsc_flick_btn)) }
    }
}

/** Grass in mowed stripes, white markings, penalty boxes and goals with nets. */
private fun DrawScope.drawPitch(x: PitchXf, c: SoccerColors) {
    val W = SoccerRules.W; val L = SoccerRules.L; val cx = SoccerRules.CX
    val tl = x.toScreen(0.0, 0.0); val br = x.toScreen(W, L)
    val left = minOf(tl.x, br.x); val top = minOf(tl.y, br.y)
    val sizeW = abs(br.x - tl.x); val sizeH = abs(br.y - tl.y)
    // wooden table frame
    val frame = x.len(26.0)
    drawRect(Color(0xFF6B4A2B), Offset(left - frame, top - frame), Size(sizeW + 2 * frame, sizeH + 2 * frame))
    drawRect(Color(0xFF8A6238), Offset(left - frame * 0.55f, top - frame * 0.55f), Size(sizeW + 1.1f * frame, sizeH + 1.1f * frame))
    drawRect(c.grassA, Offset(left, top), Size(sizeW, sizeH))
    val bands = 10
    for (i in 0 until bands step 2) {
        val a = x.toScreen(0.0, L / bands * (i + 1)); val b = x.toScreen(W, L / bands * i)
        drawRect(c.grassB, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y)))
    }
    val lw = x.len(7.0)
    val line = c.line
    drawRect(line, Offset(left, top), Size(sizeW, sizeH), style = Stroke(lw))
    val m1 = x.toScreen(0.0, L / 2); val m2 = x.toScreen(W, L / 2)
    drawLine(line, m1, m2, strokeWidth = lw)
    val mid = x.toScreen(cx, L / 2)
    drawCircle(line, x.len(150.0), mid, style = Stroke(lw))
    drawCircle(line, x.len(10.0), mid)
    for (end in 0..1) {
        val gy = if (end == 0) 0.0 else L
        val dir = if (end == 0) 1.0 else -1.0
        val a = x.toScreen(cx - 260, gy); val b = x.toScreen(cx + 260, gy + dir * 190)
        drawRect(line, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y)), style = Stroke(lw * 0.8f))
        // goal: net pocket behind the line with posts
        val g1 = x.toScreen(cx - SoccerRules.GOAL_HALF, gy); val g2 = x.toScreen(cx + SoccerRules.GOAL_HALF, gy - dir * SoccerRules.GOAL_DEPTH)
        val gl = minOf(g1.x, g2.x); val gt = minOf(g1.y, g2.y); val gw = abs(g2.x - g1.x); val gh = abs(g2.y - g1.y)
        drawRect(Color(0xFF1B2A1E), Offset(gl, gt), Size(gw, gh))
        val step = x.len(22.0).coerceAtLeast(6f)
        var k = 0f
        while (k < gw) { drawLine(Color(0x66FFFFFF), Offset(gl + k, gt), Offset(gl + k, gt + gh), strokeWidth = 1.5f); k += step }
        k = 0f
        while (k < gh) { drawLine(Color(0x66FFFFFF), Offset(gl, gt + k), Offset(gl + gw, gt + k), strokeWidth = 1.5f); k += step }
        drawRect(Color.White, Offset(gl, gt), Size(gw, gh), style = Stroke(lw * 0.9f))
        for (sgn in listOf(-1.0, 1.0)) {
            val p = x.toScreen(cx + sgn * SoccerRules.GOAL_HALF, gy)
            drawCircle(Color.White, x.len(SoccerRules.POST_R * 1.5), p)
            drawCircle(Color(0xFFB0B0B0), x.len(SoccerRules.POST_R * 1.5), p, style = Stroke(lw * 0.3f))
        }
    }
}
