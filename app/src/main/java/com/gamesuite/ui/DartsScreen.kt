package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.darts.Cricket
import com.gamesuite.games.darts.DartHit
import com.gamesuite.games.darts.DartsBoard
import com.gamesuite.games.darts.DartsEvent
import com.gamesuite.games.darts.DartsGame
import com.gamesuite.games.darts.DartsReject
import com.gamesuite.games.darts.DartsState
import com.gamesuite.games.darts.DartsThrow
import com.gamesuite.games.darts.Ring
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val VIEW_MM = 208f
private const val FLICK_MIN_DP_PER_S = 600f
private const val FLICK_RANGE_DP_PER_S = 2200f
private const val LIFT_DP = 52f

private class DartsTheme(val bg: Color, val text: Color, val card: Color, val accent: Color)

@Composable
private fun dartsTheme(dark: Boolean) = if (!dark) DartsTheme(
    bg = Color(0xFFF3EBDD), text = Color(0xFF2B2418), card = Color(0xFFE6DAC3), accent = Color(0xFF8B1E3F)
) else DartsTheme(
    bg = Color(0xFF16201C), text = Color(0xFFEFE8D8), card = Color(0xFF22302A), accent = Color(0xFFF07C9E)
)

private fun flightColor(seat: Int): Color = LudoSeatColors[seat % 4]

/**
 * Darts on a drawn regulation board. Drag anywhere on the board to move the reticle (it floats above
 * your finger and sways like a real hand), then flick upward to throw: the flick speed is your power
 * (too soft sags low, too hard wobbles). Buttons do the same for anyone not using touch: Throw, four
 * nudges and one-tap targets. Every dart is announced, every refused tap says why, and the board is
 * colour-blind safe (a blue/orange scheme with outlined scoring rings).
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DartsScreen(
    sessionManager: GameSessionManager,
    game: DartsGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.AIR_HOCKEY, enabled = musicEnabled)
    val state by game.state
    val hostLeft by game.hostLeft
    val hintAim by game.hintAim
    val hintRoute by game.hintRoute
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = dartsTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.gameType = extras["game"]?.takeIf { it in DartsGame.GAMES } ?: DartsGame.G501
        game.doubleOut = extras["doubleOut"] != "false"
        game.doubleIn = extras["doubleIn"] == "true"
        game.assist = extras["assist"]?.takeIf { it in DartsGame.ASSISTS } ?: DartsGame.ASSIST_NORMAL
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

    // ---- aim and animation state ------------------------------------------------------------
    var aim by remember { mutableStateOf(Offset(0f, 0f)) }
    var shown by remember { mutableStateOf<List<DartsThrow>>(emptyList()) }
    var flight by remember { mutableStateOf<DartsThrow?>(null) }
    var flightSeat by remember { mutableIntStateOf(0) }
    val flightProgress = remember { Animatable(0f) }
    val impact = remember { Animatable(1f) }
    var lastThrow by remember { mutableIntStateOf(-1) }
    var whyKey by remember { mutableStateOf<DartsReject?>(null) }
    var callout by remember { mutableStateOf<String?>(null) }
    var botThinking by remember { mutableStateOf(false) }

    // A State read only in the draw pass and at throw time: `.value` in composition recomposed the whole screen every frame.
    val swayState: State<Float> = if (reduced) remember { mutableStateOf(0f) } else rememberInfiniteTransition(label = "darts-sway").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "darts-sway-a"
    )

    val txtBull = stringResource(R.string.dt_bull_short)
    val callBust = stringResource(R.string.dt_big_bust)
    val call180 = stringResource(R.string.dt_big_180)
    val callWin = stringResource(R.string.dt_big_game_shot)
    val callTreble = stringResource(R.string.dt_big_treble)
    val callBullseye = stringResource(R.string.dt_big_bullseye)
    val callDouble = stringResource(R.string.dt_big_double)

    LaunchedEffect(state?.throwSerial, state?.visitSerial) {
        val cur = state ?: return@LaunchedEffect
        val isNew = lastThrow >= 0 && cur.throwSerial == lastThrow + 1 && cur.visit.isNotEmpty()
        lastThrow = cur.throwSerial
        if (!isNew) {
            flight = null
            shown = cur.visit
            if (cur.visit.isEmpty()) aim = Offset(0f, 0f)
            return@LaunchedEffect
        }
        val dart = cur.visit.last()
        val localMover = !isNetworked || cur.current == localSeat
        try {
            if (reduced) {
                shown = cur.visit
            } else {
                shown = cur.visit.dropLast(1)
                flightSeat = cur.current
                flight = dart
                flightProgress.snapTo(0f)
                playSfx(SfxKind.WHOOSH)
                flightProgress.animateTo(1f, tween(gameSpeed.scale(280L).toInt().coerceAtLeast(90), easing = LinearEasing))
                flight = null
                shown = cur.visit
                impact.snapTo(0f)
            }
            playSfx(if (dart.ring == Ring.MISS) SfxKind.LIGHT_TICK else SfxKind.SOLID_THUNK)
            haptics(if (localMover) HapticSignal.NORMAL_ACTION else HapticSignal.LIGHT_TICK)
            when {
                cur.event == DartsEvent.BUST -> {
                    callout = callBust; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
                }
                cur.event == DartsEvent.WIN -> { callout = callWin }
                cur.event == DartsEvent.ONE_EIGHTY -> {
                    callout = call180; playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                }
                dart.ring == Ring.BULL -> {
                    callout = callBullseye; playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.STRONG_ACTION)
                }
                dart.ring == Ring.TREBLE -> {
                    callout = callTreble; playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.STRONG_ACTION)
                }
                dart.ring == Ring.DOUBLE && cur.game != DartsGame.CRICKET -> {
                    callout = callDouble; haptics(HapticSignal.STRONG_ACTION)
                }
                dart.ring == Ring.MISS -> { playSfx(SfxKind.INVALID_BUZZ) }
            }
            if (!reduced) impact.animateTo(1f, tween(gameSpeed.scale(380L).toInt().coerceAtLeast(80), easing = LinearEasing))
            if (callout != null) { delay(gameSpeed.scale(900L)); callout = null }
        } finally {
            flight = null
            callout = null
            shown = state?.visit ?: cur.visit
        }
    }

    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (s.phase == DartsGame.PHASE_OVER || !game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            val base = if (s.phase == DartsGame.PHASE_COLLECT) 1300L else 800L
            delay(gameSpeed.scale(base).coerceAtLeast(450L))
            while (flight != null) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.dt_help_title)
    val helpText = stringResource(R.string.dt_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = theme.bg, buttonContent = theme.text
            ) {
                Box(Modifier.fillMaxSize().background(theme.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.dt_waiting_host), color = theme.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val cricket = s.game == DartsGame.CRICKET
    fun nameOf(i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    val isMyTurn = game.isMyTurn(s)
    val settled = flight == null
    val canAim = isMyTurn && s.phase == DartsGame.PHASE_THROW && settled
    val sigma = game.sigmaFor(s.current).toFloat()
    val finishedBoards = game.sessionWins.value.values.sum()

    fun hitWords(h: DartHit): String = when (h.ring) {
        Ring.MISS -> androidContext.getString(R.string.dt_hit_miss)
        Ring.SINGLE -> androidContext.getString(R.string.dt_hit_single, h.number)
        Ring.DOUBLE -> androidContext.getString(R.string.dt_hit_double, h.number)
        Ring.TREBLE -> androidContext.getString(R.string.dt_hit_treble, h.number)
        Ring.OUTER_BULL -> androidContext.getString(R.string.dt_hit_outer_bull)
        Ring.BULL -> androidContext.getString(R.string.dt_hit_bullseye)
    }

    LaunchedEffect(s.phase, s.winnerPlayerId, s.throwSerial) {
        if (s.phase != DartsGame.PHASE_OVER) return@LaunchedEffect
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        val humanWon = winnerSeat >= 0 && !s.players[winnerSeat].isBot && (!isNetworked || winnerSeat == localSeat)
        delay(gameSpeed.scale(500L))
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) {
                burst.spawn(Offset(0.5f, 0.32f), 80, listOf(flightColor(winnerSeat), Color.White, Color(0xFFFFD866)),
                    speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
            }
        } else {
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        }
    }

    // ---- words ------------------------------------------------------------------------------
    val current = nameOf(s.current)
    val lastDart = s.visit.lastOrNull()
    val statusText = run {
        val who = current
        val last = when (s.event) {
            DartsEvent.THROW -> lastDart?.let {
                if (it.ring == Ring.MISS) sayYou(who, R.string.dt_ann_miss, R.string.dt_ann_miss_you)
                else sayYou(who, R.string.dt_ann_throw, R.string.dt_ann_throw_you, hitWords(it.hit), it.score)
            } ?: ""
            DartsEvent.BUST -> sayYou(who, R.string.dt_ann_bust, R.string.dt_ann_bust_you, s.visitStart)
            DartsEvent.ONE_EIGHTY -> sayYou(who, R.string.dt_ann_180, R.string.dt_ann_180_you)
            DartsEvent.WIN -> sayYou(nameOf(s.players.indexOfFirst { it.playerId == s.winnerPlayerId }), R.string.dt_ann_win, R.string.dt_ann_win_you)
            DartsEvent.COLLECT -> sayYou(who, R.string.dt_ann_up, R.string.dt_ann_up_you)
            else -> sayYou(who, R.string.dt_ann_start, R.string.dt_ann_start_you)
        }
        val turn = when {
            s.phase == DartsGame.PHASE_OVER -> ""
            hostLeft -> stringResource(R.string.dt_host_left)
            s.phase == DartsGame.PHASE_COLLECT && isMyTurn -> stringResource(R.string.dt_turn_collect)
            s.phase == DartsGame.PHASE_COLLECT -> ""
            botThinking -> stringResource(R.string.dt_thinking, who)
            isMyTurn -> stringResource(R.string.dt_turn_throw, who, s.visit.size + 1)
            else -> sayYou(who, R.string.dt_turn_named, R.string.dt_turn_named_you, s.visit.size + 1)
        }
        listOf(last, turn).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText = whyKey?.let { k ->
        when (k) {
            DartsReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.dt_why_wait else R.string.dt_why_cpu)
            DartsReject.COLLECT_FIRST -> stringResource(R.string.dt_why_collect)
            DartsReject.THROW_FIRST -> stringResource(R.string.dt_why_throw_first)
            DartsReject.OVER -> stringResource(R.string.dt_why_over)
            DartsReject.BAD_AIM -> stringResource(R.string.dt_why_over)
            DartsReject.NONE -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    fun refuse(r: DartsReject) {
        whyKey = r
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }

    fun doThrow(at: Offset, power: Float) {
        val r = game.explainThrow()
        if (r != DartsReject.NONE) { refuse(r); return }
        if (!settled) return
        whyKey = null
        game.throwDart(at.x.toDouble(), at.y.toDouble(), power.toDouble())
    }

    fun doCollect() {
        val r = game.explainCollect()
        if (r != DartsReject.NONE) { refuse(r); return }
        whyKey = null
        game.collect()
    }

    val swayAmp = sigma * 0.45f
    fun swayOffsetNow(): Offset {
        if (!canAim) return Offset.Zero
        val sway = swayState.value
        return Offset(sin(sway * 1.0f) * swayAmp, cos(sway * 1.3f) * swayAmp * 0.8f)
    }
    val aimHit = DartsBoard.resolve(aim.x.toDouble(), aim.y.toDouble())

    val darts = shown.size + (if (flight != null) 1 else 0)
    val boardDescription = stringResource(
        R.string.dt_board_desc, darts,
        if (canAim) stringResource(R.string.dt_reticle_desc, hitWords(aimHit)) else ""
    )

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = s.phase != DartsGame.PHASE_OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.dt_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.dt_leave_body_session else R.string.dt_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("darts", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("darts", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.players.indices.forEach { i ->
                            val p = s.players[i]
                            val c = flightColor(i)
                            val isTurn = i == s.current && s.phase != DartsGame.PHASE_OVER
                            val chipName = nameOf(i) + if (isNetworked && i == localSeat) " " + stringResource(R.string.dt_you_suffix) else ""
                            val scoreText = if (cricket) "${p.points}" else "${p.remaining}"
                            val desc = (if (cricket) stringResource(R.string.dt_chip_cricket, chipName, p.points, Cricket.closedCount(p.marks))
                            else stringResource(R.string.dt_chip_x01, chipName, p.remaining)) +
                                if (p.isBot) " (" + stringResource(R.string.dt_cpu_tag) + ")" else ""
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
                                Text(scoreText, color = theme.text, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val board: @Composable (Modifier) -> Unit = { mod ->
                Box(mod, contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize()) {
                        val measurer = rememberTextMeasurer()
                        val currentAim by rememberUpdatedState(aim)
                        val canAimNow by rememberUpdatedState(canAim)
                        val swayNow by rememberUpdatedState(::swayOffsetNow)
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .semantics { contentDescription = boardDescription }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val scale = (minOf(size.width, size.height) / 2f) / VIEW_MM
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val lift = LIFT_DP * density
                                        fun toMm(p: Offset): Offset {
                                            val v = Offset((p.x - cx) / scale, (p.y - lift - cy) / scale)
                                            val d = hypot(v.x, v.y)
                                            return if (d > 230f) Offset(v.x * 230f / d, v.y * 230f / d) else v
                                        }
                                        if (!canAimNow) {
                                            // a tap while it is not your throw explains why (handled on release)
                                        }
                                        val tracker = VelocityTracker()
                                        tracker.addPosition(down.uptimeMillis, down.position)
                                        var moved = 0f
                                        var last = down.position
                                        if (canAimNow) aim = toMm(down.position)
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                            // historical samples too: a quick flick delivered as one batched event would otherwise give the tracker too few points
                                            ch.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
                                            tracker.addPosition(ch.uptimeMillis, ch.position)
                                            if (ch.changedToUp()) {
                                                if (!canAimNow) {
                                                    val r = game.explainThrow()
                                                    if (r != DartsReject.NONE) refuse(r)
                                                } else {
                                                    val vy = tracker.calculateVelocity().y / density
                                                    if (vy < -FLICK_MIN_DP_PER_S && moved > 8f * density) {
                                                        val p = (0.35f + 0.65f * ((-vy - FLICK_MIN_DP_PER_S) / FLICK_RANGE_DP_PER_S).coerceIn(0f, 1f))
                                                        swayNow().let { sw -> doThrow(Offset(currentAim.x + sw.x, currentAim.y + sw.y), p) }
                                                    }
                                                }
                                                break
                                            }
                                            moved += (ch.position - last).getDistance()
                                            last = ch.position
                                            if (canAimNow) aim = toMm(ch.position)
                                            ch.consume()
                                        }
                                    }
                                }
                        ) {
                            drawDartboard(measurer, colorblind, theme.text)
                            shown.forEachIndexed { k, d -> drawDartAt(d.x.toFloat(), d.y.toFloat(), flightColor(s.current), k, 1f, 1f) }
                            val f = flight
                            if (f != null) {
                                val p = flightProgress.value
                                val rise = (1f - p) * 190f
                                drawDartAt(f.x.toFloat(), f.y.toFloat() + rise, flightColor(flightSeat), 9, 1f + (1f - p) * 1.1f, 0.35f + 0.65f * p)
                            }
                            if (impact.value < 1f && shown.isNotEmpty()) {
                                val d = shown.last()
                                val sc = (size.minDimension / 2f) / VIEW_MM
                                val c = Offset(size.width / 2f + d.x.toFloat() * sc, size.height / 2f + d.y.toFloat() * sc)
                                drawCircle(Color.White.copy(alpha = 0.6f * (1f - impact.value)), (6f + 26f * impact.value) * sc, c, style = Stroke(2.5f))
                            }
                            val hintPt = hintAim
                            if (hintPt != null && canAim) {
                                val sc = (size.minDimension / 2f) / VIEW_MM
                                val c = Offset(size.width / 2f + hintPt.x.toFloat() * sc, size.height / 2f + hintPt.y.toFloat() * sc)
                                drawCircle(Color(0xFFFFD21F), 11f * sc + 6f, c, style = Stroke(5f))
                                drawCircle(Color(0xFFFFD21F).copy(alpha = 0.35f), 11f * sc + 14f, c, style = Stroke(3f))
                            }
                            if (canAim) swayOffsetNow().let { sw -> drawReticle(Offset(aim.x + sw.x, aim.y + sw.y), sigma, flightColor(s.current)) }
                            drawBurst(burst)
                        }
                        val co = callout
                        if (co != null) {
                            Text(
                                co, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center)
                                    .background(Color(0xAA000000), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            val footer: @Composable () -> Unit = {
                Spacer(Modifier.height(6.dp))
                // the visit: three dart slots and the total
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (k in 0 until 3) {
                        val d = s.visit.getOrNull(k)
                        val desc = if (d == null) stringResource(R.string.dt_dart_empty, k + 1) else stringResource(R.string.dt_dart_desc, k + 1, hitWords(d.hit), d.score)
                        Box(
                            Modifier.size(width = 58.dp, height = 36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(theme.card)
                                .border(1.dp, theme.text.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                .semantics { contentDescription = desc },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(d?.hit?.code ?: "·", color = if (d?.bust == true) Color(0xFFD55E00) else theme.text, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(stringResource(R.string.dt_visit_total, s.visitScore), color = theme.text, style = MaterialTheme.typography.titleSmall)
                }
                val route = if (isMyTurn) game.checkoutFor(s) else emptyList()
                if (route.isNotEmpty() && hintRoute.isEmpty()) {
                    Text(stringResource(R.string.dt_checkout, route.joinToString(" · ")), color = theme.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                if (hintRoute.isNotEmpty()) {
                    Text(stringResource(R.string.dt_hint_route, hintRoute.joinToString(" · ")), color = theme.text, style = MaterialTheme.typography.bodyMedium)
                }
                if (cricket) CricketBoard(s, theme, ::nameOf)
                Spacer(Modifier.height(6.dp))
                if (s.phase != DartsGame.PHASE_OVER) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (s.phase == DartsGame.PHASE_COLLECT && isMyTurn) {
                            Button(onClick = { doCollect() }, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 150.dp)) {
                                Text(stringResource(R.string.dt_collect), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { doThrow(aim, 0.68f) },
                                enabled = canAim,
                                modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 120.dp)
                            ) { Text(stringResource(R.string.dt_throw), fontWeight = FontWeight.Bold) }
                        }
                        if (!isNetworked) {
                            OutlinedButton(
                                onClick = { scope.launch { game.requestHint() } }, enabled = canAim,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.dt_hint)) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val step = 6f
                        NudgeButton("◀", stringResource(R.string.dt_aim_left), canAim) { aim = clampAim(aim.x - step, aim.y) }
                        NudgeButton("▲", stringResource(R.string.dt_aim_up), canAim) { aim = clampAim(aim.x, aim.y - step) }
                        NudgeButton("▼", stringResource(R.string.dt_aim_down), canAim) { aim = clampAim(aim.x, aim.y + step) }
                        NudgeButton("▶", stringResource(R.string.dt_aim_right), canAim) { aim = clampAim(aim.x + step, aim.y) }
                        val targets = if (cricket) listOf(
                            DartHit(Ring.TREBLE, 20), DartHit(Ring.TREBLE, 19), DartHit(Ring.TREBLE, 18), DartHit(Ring.TREBLE, 17),
                            DartHit(Ring.TREBLE, 16), DartHit(Ring.TREBLE, 15), DartHit(Ring.BULL, 25)
                        ) else listOf(
                            DartHit(Ring.BULL, 25), DartHit(Ring.TREBLE, 20), DartHit(Ring.TREBLE, 19), DartHit(Ring.TREBLE, 18),
                            DartHit(Ring.DOUBLE, 20), DartHit(Ring.DOUBLE, 16)
                        )
                        targets.forEach { t ->
                            val label = if (t.ring == Ring.BULL) txtBull else t.code
                            NudgeButton(label, stringResource(R.string.dt_aim_at, hitWords(t)), canAim) {
                                val (x, y) = DartsBoard.center(t.ring, t.number)
                                aim = Offset(x.toFloat(), y.toFloat())
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.dt_flick_hint), color = theme.text.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (s.phase == DartsGame.PHASE_OVER) {
                    Spacer(Modifier.height(10.dp))
                    val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                    val secs = (game.durationMs.value / 1000L).toInt()
                    ClFinishedPanel(
                        title = sayYou(nameOf(winnerSeat), R.string.dt_wins, R.string.dt_wins_you),
                        subtitle = if (isNetworked) stringResource(if (winnerSeat == localSeat) R.string.dt_you_won else R.string.dt_you_lost) else null,
                        lines = s.players.indices.map { i ->
                            val p = s.players[i]
                            if (cricket) stringResource(R.string.dt_final_cricket, nameOf(i), p.points, Cricket.closedCount(p.marks))
                            else {
                                val avg = if (p.darts > 0) p.totalScored * 3.0 / p.darts else 0.0
                                stringResource(R.string.dt_final_x01, nameOf(i), String.format(java.util.Locale.getDefault(), "%.1f", avg), p.bestVisit)
                            }
                        } + listOf(stringResource(R.string.dt_match_time, "%d:%02d".format(secs / 60, secs % 60))) +
                            (if (finishedBoards > 1) listOf(
                                stringResource(R.string.dt_session, s.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)}: ${game.sessionWins.value[it.playerId] ?: 0}" })
                            ) else emptyList()),
                        canPlayAgain = !hostLeft,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() },
                        playAgainLabel = stringResource(R.string.dt_play_again),
                        backLabel = stringResource(R.string.dt_back_to_menu)
                    )
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.46f).fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        header(); footer()
                    }
                    board(Modifier.weight(0.54f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    board(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { footer() }
                }
            }
        }
    }
}

private fun clampAim(x: Float, y: Float): Offset {
    val d = hypot(x, y)
    return if (d > 230f) Offset(x * 230f / d, y * 230f / d) else Offset(x, y)
}

@Composable
private fun NudgeButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

/** The Cricket scoreboard: a row per number, a column per player, marks drawn as / X and a circled X. */
@Composable
private fun CricketBoard(s: DartsState, theme: DartsTheme, nameOf: (Int) -> String) {
    val bull = stringResource(R.string.dt_bull_short)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(52.dp))
            s.players.indices.forEach { i ->
                Text(nameOf(i), color = theme.text, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
            }
        }
        Cricket.NUMBERS.forEachIndexed { row, n ->
            val label = if (n == 25) bull else "$n"
            val open = s.players.any { it.marks[row] < 3 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = theme.text.copy(alpha = if (open) 1f else 0.45f), fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
                s.players.forEachIndexed { i, p ->
                    val m = p.marks[row]
                    val desc = stringResource(R.string.dt_marks_desc, nameOf(i), label, m)
                    Canvas(Modifier.width(64.dp).height(30.dp).semantics { contentDescription = desc }) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val r = size.height * 0.32f
                        val col = flightColor(i)
                        val sw = r * 0.3f
                        if (m >= 1) drawLine(col, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), sw)
                        if (m >= 2) drawLine(col, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), sw)
                        if (m >= 3) drawCircle(col, r * 1.35f, c, style = Stroke(sw))
                        if (m == 0) drawCircle(theme.text.copy(alpha = 0.18f), r * 0.2f, c)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Drawing
// ------------------------------------------------------------------------------------------

private fun DrawScope.drawDartboard(measurer: androidx.compose.ui.text.TextMeasurer, colorblind: Boolean, labelColor: Color) {
    val scale = (minOf(size.width, size.height) / 2f) / VIEW_MM
    val c = Offset(size.width / 2f, size.height / 2f)
    fun r(mm: Float) = mm * scale
    // wooden surround with a soft shadow
    drawCircle(Color(0x44000000), r(206f), Offset(c.x + r(2f), c.y + r(4f)))
    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF6B4A2B), Color(0xFF3F2A16)), c, r(206f)), r(206f), c)
    drawCircle(Color(0xFF1A1A1A), r(184f), c)
    val BULL_R = DartsBoard.BULL_R.toFloat(); val OUTER_BULL_R = DartsBoard.OUTER_BULL_R.toFloat()
    val TREBLE_IN = DartsBoard.TREBLE_IN.toFloat(); val TREBLE_OUT = DartsBoard.TREBLE_OUT.toFloat()
    val DOUBLE_IN = DartsBoard.DOUBLE_IN.toFloat(); val DOUBLE_OUT = DartsBoard.DOUBLE_OUT.toFloat()
    val dark = Color(0xFF1B1B1B)
    val cream = Color(0xFFF1E4C0)
    val red = if (colorblind) Color(0xFFD55E00) else Color(0xFFC62828)
    val green = if (colorblind) Color(0xFF0072B2) else Color(0xFF1E7B3A)
    fun ring(inner: Float, outer: Float, color: Color, sector: Int) {
        val mid = (inner + outer) / 2f
        drawArc(
            color, startAngle = -90f + 18f * sector - 9f, sweepAngle = 18.2f, useCenter = false,
            topLeft = Offset(c.x - r(mid), c.y - r(mid)), size = Size(r(mid) * 2, r(mid) * 2),
            style = Stroke(r(outer - inner))
        )
    }
    for (i in 0 until 20) {
        val even = i % 2 == 0
        ring(OUTER_BULL_R, TREBLE_IN, if (even) dark else cream, i)
        ring(TREBLE_IN, TREBLE_OUT, if (even) red else green, i)
        ring(TREBLE_OUT, DOUBLE_IN, if (even) dark else cream, i)
        ring(DOUBLE_IN, DOUBLE_OUT, if (even) red else green, i)
    }
    drawCircle(green, r(OUTER_BULL_R), c)
    drawCircle(red, r(BULL_R), c)
    // wire
    val wire = Color(0xFFD9D9D9).copy(alpha = 0.85f)
    val wireW = (r(0.9f)).coerceAtLeast(1f)
    for (rad in floatArrayOf(DartsBoard.BULL_R.toFloat(), DartsBoard.OUTER_BULL_R.toFloat(), DartsBoard.TREBLE_IN.toFloat(), DartsBoard.TREBLE_OUT.toFloat(), DartsBoard.DOUBLE_IN.toFloat(), DartsBoard.DOUBLE_OUT.toFloat())) {
        drawCircle(wire, r(rad), c, style = Stroke(wireW))
    }
    for (i in 0 until 20) {
        val a = Math.toRadians((18.0 * i - 9.0))
        val x0 = sin(a).toFloat(); val y0 = -cos(a).toFloat()
        drawLine(wire, Offset(c.x + x0 * r(DartsBoard.OUTER_BULL_R.toFloat()), c.y + y0 * r(DartsBoard.OUTER_BULL_R.toFloat())),
            Offset(c.x + x0 * r(DartsBoard.DOUBLE_OUT.toFloat()), c.y + y0 * r(DartsBoard.DOUBLE_OUT.toFloat())), wireW)
    }
    // numbers
    val style = TextStyle(fontSize = (r(15f) / density).sp, fontWeight = FontWeight.Bold, color = Color(0xFFF4EFE2))
    for (i in 0 until 20) {
        val a = Math.toRadians(18.0 * i)
        val layout = measurer.measure("${DartsBoard.SECTORS[i]}", style)
        val p = Offset(c.x + sin(a).toFloat() * r(177f), c.y - cos(a).toFloat() * r(177f))
        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
    }
}

/** A dart stuck in the board at ([x], [y]) mm: steel tip, brass barrel, shaft and a flight in the thrower's colour. */
private fun DrawScope.drawDartAt(x: Float, y: Float, flightCol: Color, variant: Int, scaleUp: Float, alpha: Float) {
    val sc = (minOf(size.width, size.height) / 2f) / VIEW_MM
    val tip = Offset(size.width / 2f + x * sc, size.height / 2f + y * sc)
    // the dart leans toward the thrower (down and a little right), shortened by perspective
    val dx = 0.28f; val dy = 0.96f
    fun at(t: Float) = Offset(tip.x + dx * t * sc * scaleUp, tip.y + dy * t * sc * scaleUp)
    drawCircle(Color(0x55000000).copy(alpha = 0.33f * alpha), 3.2f * sc * scaleUp, Offset(tip.x + 1.5f * sc, tip.y + 1.5f * sc))
    drawLine(Color(0xFFC9CED6).copy(alpha = alpha), tip, at(9f), 1.6f * sc * scaleUp + 1f)
    drawLine(Color(0xFFD9A441).copy(alpha = alpha), at(9f), at(33f), 5.2f * sc * scaleUp + 1f)
    drawLine(Color(0xFF8A6A22).copy(alpha = alpha), at(14f), at(17f), 5.4f * sc * scaleUp + 1f)
    drawLine(Color(0xFF2B2B2B).copy(alpha = alpha), at(33f), at(46f), 2.4f * sc * scaleUp + 1f)
    val f = Path().apply {
        val a = at(44f); val b = at(76f)
        val nx = dy; val ny = -dx
        val w = 15f * sc * scaleUp
        moveTo(a.x, a.y)
        lineTo(b.x + nx * w, b.y + ny * w)
        lineTo(b.x - nx * w, b.y - ny * w)
        close()
    }
    drawPath(f, flightCol.copy(alpha = 0.92f * alpha))
    drawPath(f, Color.White.copy(alpha = 0.5f * alpha), style = Stroke(1.2f))
    if (variant % 2 == 0) drawLine(Color.White.copy(alpha = 0.35f * alpha), at(48f), at(72f), 1.6f)
}

private fun DrawScope.drawReticle(aim: Offset, sigma: Float, color: Color) {
    val sc = (minOf(size.width, size.height) / 2f) / VIEW_MM
    val c = Offset(size.width / 2f + aim.x * sc, size.height / 2f + aim.y * sc)
    val spread = (sigma * sc).coerceAtLeast(10f)
    drawCircle(Color.White.copy(alpha = 0.16f), spread * 1.6f, c)
    drawCircle(Color.White.copy(alpha = 0.7f), spread * 1.6f, c, style = Stroke(2f))
    drawCircle(Color(0xFF111111), 9f, c, style = Stroke(5f))
    drawCircle(color, 9f, c, style = Stroke(2.5f))
    val len = 17f
    drawLine(Color(0xFF111111), Offset(c.x - len, c.y), Offset(c.x + len, c.y), 4.5f)
    drawLine(Color(0xFF111111), Offset(c.x, c.y - len), Offset(c.x, c.y + len), 4.5f)
    drawLine(Color.White, Offset(c.x - len, c.y), Offset(c.x + len, c.y), 2f)
    drawLine(Color.White, Offset(c.x, c.y - len), Offset(c.x, c.y + len), 2f)
}
