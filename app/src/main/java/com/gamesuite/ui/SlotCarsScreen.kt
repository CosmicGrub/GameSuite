package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberPannedProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.slotcars.SlotCarsGame
import com.gamesuite.games.slotcars.SlotCarsReject
import com.gamesuite.games.slotcars.SlotCarsRules
import com.gamesuite.games.slotcars.SlotCarsState
import com.gamesuite.games.slotcars.SlotPhase
import com.gamesuite.games.slotcars.SlotTrack
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
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
import kotlin.math.ceil
import kotlin.math.min

private fun placeWord(ctx: Context, place: Int): String = ctx.getString(
    when (place) { 0 -> R.string.sct_place_1; 1 -> R.string.sct_place_2; 2 -> R.string.sct_place_3; else -> R.string.sct_place_4 }
)

/** m:ss.t from seconds. */
internal fun slotTimeText(sec: Double): String {
    if (sec < 0) return "-"
    val t = (sec * 10).toInt()
    return "%d:%02d.%d".format(t / 600, (t / 10) % 60, t % 10)
}

/**
 * Slot Cars. Every driver has a pad: hold it to speed up, let go to brake. Brake before the amber
 * signs or the car leaves the slot. Each human has their own pad, so pass and play is simultaneous; the
 * pads are pinned under the track in every layout. With a screen reader on (or the step assist) a pad
 * becomes an on / off toggle, and the step assist adds a Go button. Cars are told apart by colour, roof
 * pattern and number.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlotCarsScreen(
    sessionManager: GameSessionManager,
    game: SlotCarsGame,
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
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pal = remember(dark) { slotCarsPalette(dark) }
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reduced
    val screenReader = rememberClTouchExploration()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.laps = extras["laps"]?.toIntOrNull() ?: 3
        game.trackId = extras["track"] ?: "meadow"
        game.assist = extras["assist"] ?: "off"
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
    LaunchedEffect(game) {
        while (true) { androidx.compose.runtime.withFrameNanos { game.advance() } }
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val helpTitle = stringResource(R.string.sct_help_title)
    val helpText = stringResource(R.string.sct_help_text)
    val s = state
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = pal.bg, buttonContent = pal.text
            ) {
                Box(Modifier.fillMaxSize().background(pal.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ccl_waiting_host), color = pal.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val humanSeats: List<Int> = if (isNetworked) listOf(localSeat) else s.players.indices.filter { !s.players[it].isBot }
    val viewSeat = humanSeats.firstOrNull() ?: 0
    val stepMode = s.assist == "step"
    val useToggle = screenReader || stepMode
    fun name(i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    fun carName(i: Int) = androidContext.getString(R.string.sct_car, i + 1, name(i))
    fun isMe(i: Int) = !s.players[i].isBot && (isNetworked && i == localSeat || !isNetworked && humanSeats.size == 1 && i == viewSeat)

    // ---- announcements, sounds, effects (all keyed to the engine's serials) ----
    var announce by remember { mutableStateOf("") }
    var whyText by remember { mutableStateOf<String?>(null) }
    var whySerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(lastReject?.second) {
        val r = lastReject?.first ?: return@LaunchedEffect
        whyText = androidContext.getString(if (r == SlotCarsReject.OPPONENT_LEFT) R.string.sct_why_left else R.string.sct_why_over)
        whySerial++
        playSfx(SfxKind.INVALID_BUZZ, 0f); haptics(HapticSignal.FAILURE)
    }
    LaunchedEffect(whySerial) {
        if (whySerial == 0) return@LaunchedEffect
        val mine = whySerial
        delay(3200)
        if (whySerial == mine) whyText = null
    }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(s.deslotSerial) {
        if (s.deslotSerial == 0 || s.lastDeslotCar < 0) return@LaunchedEffect
        val c = s.lastDeslotCar
        announce = androidContext.youAware(name(c), R.string.sct_ann_deslot, R.string.sct_ann_deslot_you)
        playSfx(SfxKind.INVALID_BUZZ, 0f)
        if (isMe(c) || humanSeats.size > 1) haptics(HapticSignal.FAILURE)
        if (enhanced && (isMe(c) || humanSeats.size > 1)) { shake.snapTo(1f); shake.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium)) }
    }
    LaunchedEffect(s.lapSerial) {
        if (s.lapSerial == 0 || s.lastLapCar < 0) return@LaunchedEffect
        val c = s.lastLapCar
        val car = s.cars[c]
        if (car.lapsDone in 1 until s.laps) {
            announce = androidContext.youAware(name(c), R.string.sct_ann_lap, R.string.sct_ann_lap_you, car.lapsDone + 1, s.laps)
            if (isMe(c)) { playSfx(SfxKind.LIGHT_TICK, 0f); haptics(HapticSignal.LIGHT_TICK) }
        }
    }
    LaunchedEffect(s.finishSerial) {
        if (s.finishSerial == 0 || s.lastFinishCar < 0) return@LaunchedEffect
        val c = s.lastFinishCar
        val place = s.cars[c].place
        announce = androidContext.youAware(name(c), R.string.sct_ann_finish, R.string.sct_ann_finish_you, placeWord(androidContext, place)) +
            (if (androidContext.isYouName(name(c))) " " + androidContext.getString(R.string.sct_ann_place_you, placeWord(androidContext, place)) else "")
        if (isMe(c) || humanSeats.size > 1) { playSfx(SfxKind.SUCCESS_CHIME, 0f); haptics(HapticSignal.STRONG_ACTION) }
    }
    val digit = if (s.countdownTicks in 1..180) ((s.countdownTicks - 1) / 60 + 1).coerceIn(1, 3) else 0
    LaunchedEffect(digit) {
        if (digit > 0) { playSfx(SfxKind.LIGHT_TICK, 0f); haptics(HapticSignal.LIGHT_TICK) }
    }
    var wasCountdown by remember { mutableStateOf(false) }
    LaunchedEffect(s.countdownTicks > 0) {
        if (wasCountdown && s.countdownTicks == 0 && s.phase == SlotPhase.RACING) {
            announce = androidContext.getString(R.string.sct_status_go)
            playSfx(SfxKind.SUCCESS_CHIME, 0f); haptics(HapticSignal.STRONG_ACTION)
        }
        wasCountdown = s.countdownTicks > 0
    }
    LaunchedEffect(s.phase) {
        if (s.phase != SlotPhase.OVER) return@LaunchedEffect
        val w = s.winner ?: s.order.first()
        val mine = isMe(w) || (humanSeats.size > 1 && !s.players[w].isBot)
        announce = androidContext.youAware(name(w), R.string.sct_ann_win, R.string.sct_ann_win_you)
        playSfx(if (mine || humanSeats.size > 1) SfxKind.SUCCESS_CHIME else SfxKind.INVALID_BUZZ, 0f)
        haptics(if (mine) HapticSignal.CELEBRATION else HapticSignal.NORMAL_ACTION)
        if (!reduced && mine) burst.spawn(Offset(0.5f, 0.35f), 90, listOf(pal.gold, Color.White, pal.accent, SlotCarColors[w % 4]), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
    }
    LaunchedEffect(hint?.serial) { if (hint != null) { delay(2600); if (game.hint.value?.serial == hint?.serial) game.hint.value = null } }

    val status: String = when {
        s.raceOver -> announce
        opponentLeft -> stringResource(R.string.sct_opp_left)
        s.countdownTicks > 0 -> stringResource(R.string.sct_status_ready)
        stepMode -> stringResource(R.string.sct_step_status)
        else -> announce.ifBlank { stringResource(R.string.sct_status_go) }
    }
    val trackWord = stringResource(when (s.track) { "hill" -> R.string.sct_track_hill; "twist" -> R.string.sct_track_twist; else -> R.string.sct_track_meadow })
    val raceDesc = stringResource(R.string.sct_track_desc, trackWord, s.laps) + " " + s.order.mapIndexed { place, c ->
        androidContext.getString(R.string.sct_chip_desc, placeWord(androidContext, place), carName(c), min(s.cars[c].lapsDone + 1, s.laps), s.laps)
    }.joinToString(" ")

    val finishedRaces = sessionWins.sum()
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.raceOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRaces > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedRaces == 0,
        onHint = if (isNetworked) null else ({ game.requestHint(viewSeat) }),
        buttonFill = pal.bg, buttonContent = pal.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedRaces > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body),
        extraItems = { _ ->
            androidx.compose.runtime.DisposableEffect(Unit) {
                game.pause()
                onDispose { game.resume() }
            }
        }
    ) {
        val (_, openCoach) = rememberClCoach("slot-cars", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(pal.bg)) {
            val boxW = maxWidth
            val boxH = maxHeight
            val landscape = boxW > boxH * 1.15f

            @Composable
            fun Header(modifier: Modifier) {
                Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                        ClCoachButton("slot-cars", openCoach)
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            s.order.forEachIndexed { place, c ->
                                val car = s.cars[c]
                                val desc = stringResource(R.string.sct_chip_desc, placeWord(androidContext, place), carName(c), min(car.lapsDone + 1, s.laps), s.laps)
                                Row(
                                    Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(14.dp)).background(pal.card)
                                        .border(2.dp, SlotCarColors[c % 4], RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                                        .semantics { contentDescription = desc },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Canvas(Modifier.size(34.dp, 20.dp)) { drawSlotCar(Offset(size.width / 2, size.height / 2), 0.0, size.height / 14f, c, SlotCarColors[c % 4], false, 0f, false) }
                                    Text(
                                        " ${place + 1}. ${name(c)}  ${if (car.finishTime >= 0) "✓" else "${min(car.lapsDone + 1, s.laps)}/${s.laps}"}",
                                        color = pal.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOfNotNull(
                            trackWord,
                            stringResource(R.string.sct_laps_badge, s.laps),
                            when (s.assist) { "slow" -> stringResource(R.string.sct_slow_badge); "step" -> stringResource(R.string.sct_step_badge); else -> null }
                        ).forEach {
                            Text(it, color = pal.text, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, pal.accent, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                        if (!isNetworked && !s.raceOver) {
                            OutlinedButton(
                                onClick = { game.requestHint(viewSeat) }, enabled = s.phase == SlotPhase.RACING,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = pal.text), modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.ccl_hint)) }
                        }
                    }
                    ClStatusLine(status, pal.text, Modifier.fillMaxWidth().padding(top = 4.dp), emphasized = true)
                    val h = hint
                    if (h != null) {
                        val t = stringResource(if (h.brake) R.string.sct_hint_brake else R.string.sct_hint_go)
                        ClWhyLine(t, pal.text, pal.card)
                    }
                    ClWhyLine(whyText, pal.text, pal.card)
                }
            }

            @Composable
            fun TrackView(modifier: Modifier) {
                var cs by remember { mutableStateOf(IntSize.Zero) }
                val track = remember(s.track) { SlotTrack.byId(s.track) }
                val xf = remember(cs, track) { if (cs.width > 0 && cs.height > 0) SlotXf(cs.width.toFloat(), cs.height.toFloat(), track) else null }
                val roadPath = remember(xf) { xf?.let { slotRoadPath(it, track) } }
                val lanePaths = remember(xf, s.cars.size) { xf?.let { x -> List(s.cars.size) { slotRoadPath(x, track, it, s.cars.size) } } ?: emptyList() }
                Box(modifier.onSizeChanged { cs = it }) {
                    Canvas(Modifier.fillMaxSize().semantics { contentDescription = raceDesc }.graphicsLayer {
                        translationX = shake.value * 6f * kotlin.math.sin(shake.value * 40f)
                    }) {
                        drawSlotBaize(pal)
                        val x = xf ?: return@Canvas
                        val rp = roadPath ?: return@Canvas
                        drawSlotTrack(x, track, s.cars.size, pal, rp, lanePaths)
                        val unit = x.scale
                        // cars: the ones on the track first, lying ones last
                        for (car in s.cars.sortedBy { it.off > 25 }) {
                            val c = car.seat
                            val color = SlotCarColors[c % 4]
                            if (car.off > 25) {
                                val t = (SlotCarsRules.RESLOT_TICKS - car.off) / 60.0
                                val run = car.offV * 0.5 * min(t, 0.9)
                                val h = track.headingAt(car.offS)
                                val px = track.laneX(car.offS, c, s.cars.size) + kotlin.math.cos(h) * run
                                val py = track.laneY(car.offS, c, s.cars.size) + kotlin.math.sin(h) * run
                                val spin = if (reduced) 40f else (min(t, 0.9) * 600.0).toFloat()
                                drawSlotCar(x.pt(px, py), x.angle(h), unit, c, color, false, 0f, false, spin = spin + c * 37f)
                                // a little dust cloud where it left
                                val d = x.pt(track.laneX(car.offS, c, s.cars.size), track.laneY(car.offS, c, s.cars.size))
                                drawCircle(Color(0x55FFFFFF), unit * (10f + 22f * min(t.toFloat(), 1f)), d)
                            } else {
                                val h = track.headingAt(car.s)
                                val blink = car.off in 1..25 && (car.off / 4) % 2 == 0
                                val slip = if (reduced) 0f else ((car.grip - 0.8) / 0.2).coerceIn(0.0, 1.0).toFloat()
                                drawSlotCar(
                                    x.pt(track.laneX(car.s, c, s.cars.size), track.laneY(car.s, c, s.cars.size)), x.angle(h), unit, c, color,
                                    car.throttle && car.v > 1.0, if (car.v > 150) slip else 0f, blink
                                )
                            }
                        }
                        if (s.countdownTicks > 0) {
                            val lit = ((SlotCarsRules.COUNTDOWN_TICKS - s.countdownTicks) / 60).coerceIn(0, 3)
                            drawStartLights(Offset(size.width / 2, size.height / 2), min(size.width, size.height) / 260f, if (s.phase == SlotPhase.COUNTDOWN) lit else 3, false)
                        } else if (s.phase == SlotPhase.RACING && s.tick < 50) {
                            drawStartLights(Offset(size.width / 2, size.height / 2), min(size.width, size.height) / 260f, 3, true)
                        }
                        drawBurst(burst)
                    }
                    if (digit > 0 && s.phase == SlotPhase.COUNTDOWN) {
                        Text(digit.toString(), color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = 0.85f; translationY = 90f })
                    }
                }
            }

            @Composable
            fun Footer(modifier: Modifier) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.raceOver) {
                        val w = s.winner ?: s.order.first()
                        val best = s.cars.filter { it.bestLap >= 0 }.minByOrNull { it.bestLap }
                        ClFinishedPanel(
                            title = androidContext.youAware(name(w), R.string.sct_over_title, R.string.sct_over_title_you),
                            subtitle = best?.let { stringResource(R.string.sct_over_best, carName(it.seat), slotTimeText(it.bestLap)) },
                            lines = s.order.mapIndexed { place, c ->
                                val car = s.cars[c]
                                androidContext.getString(R.string.sct_over_line, placeWord(androidContext, place), carName(c),
                                    if (car.finishTime >= 0) slotTimeText(car.finishTime / SlotCarsRules.STEPS_PER_SECOND) else androidContext.getString(R.string.sct_over_dnf))
                            } + stringResource(R.string.sct_over_session, sessionWins.joinToString(" – ")),
                            canPlayAgain = !opponentLeft, onPlayAgain = { game.playAgain() }, onLeave = game::leaveSession
                        )
                    } else {
                        // The pads: pinned under the track in every layout.
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                            for (seat in humanSeats) {
                                val car = s.cars.getOrNull(seat) ?: continue
                                ThrottlePad(
                                    modifier = Modifier.weight(1f, fill = true),
                                    seat = seat, label = carName(seat), car = car, toggle = useToggle, step = stepMode, pal = pal,
                                    onThrottle = { on -> game.setThrottle(seat, on) },
                                    onGo = { game.setReady(seat, !car.ready) },
                                    onPress = { haptics(HapticSignal.LIGHT_TICK) }
                                )
                            }
                        }
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    TrackView(Modifier.weight(1f).fillMaxSize().padding(4.dp))
                    Column(Modifier.width(boxW * 0.40f).fillMaxSize().padding(8.dp)) {
                        Header(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                        Footer(Modifier.fillMaxWidth().heightIn(max = boxH * 0.7f).verticalScroll(rememberScrollState()).padding(top = 6.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Header(Modifier.fillMaxWidth().heightIn(max = boxH * 0.40f).verticalScroll(rememberScrollState()))
                    TrackView(Modifier.weight(1f).fillMaxWidth().heightIn(min = 160.dp))
                    Footer(Modifier.fillMaxWidth().heightIn(max = boxH * 0.55f).verticalScroll(rememberScrollState()).padding(top = 6.dp))
                }
            }
        }
    }
}

/** One driver's throttle pad (a hold pad, or a toggle with a screen reader / the step assist), with the grip bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThrottlePad(
    modifier: Modifier, seat: Int, label: String, car: com.gamesuite.games.slotcars.SlotCarView, toggle: Boolean, step: Boolean,
    pal: SlotCarsPalette, onThrottle: (Boolean) -> Unit, onGo: () -> Unit, onPress: () -> Unit
) {
    val color = SlotCarColors[seat % 4]
    val on = car.throttle
    val gripPct = (car.grip * 100).toInt().coerceIn(0, 120)
    val gripDesc = stringResource(R.string.sct_grip_desc, gripPct)
    val padDesc = stringResource(if (toggle) R.string.sct_pad_toggle_desc else R.string.sct_pad_desc, label)
    val stateDesc = stringResource(if (on) R.string.sct_throttle_on else R.string.sct_throttle_off)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).semantics { contentDescription = gripDesc }
        ) { Canvas(Modifier.fillMaxSize()) { drawGripBar(car.grip.toFloat(), pal) } }
        val base = Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) color else pal.card).border(3.dp, color, RoundedCornerShape(18.dp))
            .semantics { contentDescription = padDesc; stateDescription = stateDesc; role = Role.Button }
        val input = if (toggle) base.clickable { onPress(); onThrottle(!on) }
        else base.pointerInput(seat) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onPress(); onThrottle(true)
                waitForUpOrCancellation()
                onThrottle(false)
            }
        }
        Box(input, contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
                Canvas(Modifier.size(44.dp, 26.dp)) {
                    // a pale plate under the car so it stays visible on its own colour when the pad is lit
                    if (on) drawRoundRect(Color.White.copy(alpha = 0.85f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
                    drawSlotCar(Offset(size.width / 2, size.height / 2), 0.0, size.height / 14f, seat, color, on, 0f, false)
                }
                val textColor = if (on) (if (color.luminance() > 0.5f) Color.Black else Color.White) else pal.text
                Column {
                    Text(label, color = textColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(
                        stringResource(
                            when {
                                toggle && on -> R.string.sct_pad_toggle_on
                                toggle -> R.string.sct_pad_toggle_off
                                on -> R.string.sct_pad_hold_on
                                else -> R.string.sct_pad_hold
                            }
                        ), color = textColor, style = MaterialTheme.typography.bodyMedium, maxLines = 2
                    )
                }
            }
        }
        if (step) {
            val goLabel = stringResource(if (car.ready) R.string.sct_step_waiting else R.string.sct_step_go)
            Button(onClick = onGo, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(goLabel, maxLines = 2) }
        }
    }
}
