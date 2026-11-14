package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.partypack.TapTugPointerTracker
import com.gamesuite.games.partypack.TapTugZoneLayout
import com.gamesuite.games.taprace.RaceEvent
import com.gamesuite.games.taprace.RaceJudge
import com.gamesuite.games.taprace.RaceLight
import com.gamesuite.games.taprace.RacePhase
import com.gamesuite.games.taprace.RaceSeatState
import com.gamesuite.games.taprace.RaceVariant
import com.gamesuite.games.taprace.TapRaceGame
import com.gamesuite.games.taprace.TapRaceRules
import com.gamesuite.games.taprace.TapRaceState
import com.gamesuite.games.taprace.TapReject
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
import kotlin.math.ceil

private const val BAND = 0.12f

/**
 * Tap Race on one screen: every player has a lane (a progress track toward a finish flag) and a big pad in their own
 * zone, and all of them play at once with as many fingers as they like (the pointer model is TapTugPointerTracker).
 * With two players the far zone is turned half a turn so both read their own lane the right way up. Colour-blind
 * safe: each racer has a colour, a glyph, a surface pattern and a name; the traffic light is a triangle, a bar and a
 * square. Reduced Motion drops the squash, the pulse and the confetti. TalkBack: every pad is a 48dp+ button that a
 * double-tap taps, and the status line is a live region. Same screen only: there is no Nearby or Online mode.
 */
@Composable
fun TapRaceScreen(
    sessionManager: GameSessionManager,
    game: TapRaceGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.UNO, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reduced = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val burst = rememberParticleBurst()
    var rotate by remember { mutableStateOf(true) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.variant = RaceVariant.fromId(extras["variant"])
        game.rounds = extras["rounds"]?.toIntOrNull()?.takeIf { it in TapRaceRules.ROUND_CHOICES } ?: TapRaceRules.DEFAULT_ROUNDS
        game.relaxed = extras["relaxed"] == "true"
        game.holdToPlay = extras["hold"] == "true"
        rotate = extras["rotate"] != "false"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // The clock: the race and the computer racers run from here.
    LaunchedEffect(game) {
        while (true) {
            game.tick()
            delay(25)
        }
    }

    val helpTitle = stringResource(R.string.trc_help_title)
    val helpText = stringResource(R.string.trc_help_text)
    val s = state ?: return
    val seatCount = s.seats.size
    val colors = TapTugSeatColors
    val tracker = remember { TapTugPointerTracker(maxContactPx = Float.MAX_VALUE) }
    val pressCount = remember { mutableStateMapOf<Int, Int>() }
    val lastSfx = remember { LongArray(1) }
    val seenJudge = remember { IntArray(4) }
    var whyText by remember { mutableStateOf<String?>(null) }
    var whySerial by remember { mutableStateOf(0) }
    var goFlash by remember { mutableStateOf(false) }
    val humans = s.seats.count { !it.isBot }

    fun nameOf(i: Int) = androidContext.seatLabel(s.seats.getOrNull(i)?.name ?: "")

    // ---- effects keyed by the engine's event serial ----
    LaunchedEffect(s.eventSerial) {
        if (s.eventSerial == 0) return@LaunchedEffect
        when (s.event) {
            RaceEvent.GO -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.STRONG_ACTION); goFlash = true; delay(gameSpeed.scale(800L)); goFlash = false }
            RaceEvent.AMBER -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
            RaceEvent.RED -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.ESCALATING) }
            RaceEvent.GREEN -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.NORMAL_ACTION) }
            RaceEvent.ROUND_WIN, RaceEvent.ROUND_DRAW -> {
                playSfx(SfxKind.WHOOSH); haptics(HapticSignal.SUCCESS)
                val w = s.eventSeat
                if (w in 0 until seatCount && !reduced) burst.spawn(zoneCenter(w, seatCount), 40, listOf(colors[w % 4], Color.White), speedRange = 0.2f..0.7f, lifeRangeSeconds = 0.8f..1.5f, gravity = 0.8f)
            }
            RaceEvent.MATCH_WIN -> {
                playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                val w = s.eventSeat
                if (w in 0 until seatCount && !reduced) burst.spawn(Offset(0.5f, 0.5f), 90, listOf(colors[w % 4], Color.White, Color(0xFFF2B100)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
            }
            RaceEvent.MATCH_DRAW -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
            else -> {}
        }
    }
    // Per-seat judgements: a quiet tick for a good tap, a buzz and a line of text for a penalty.
    val judgeSum = s.seats.sumOf { it.judgeSerial }
    LaunchedEffect(judgeSum) {
        s.seats.forEachIndexed { i, seat ->
            if (i > 3 || seat.judgeSerial == seenJudge[i]) return@forEachIndexed
            seenJudge[i] = seat.judgeSerial
            val penalty = seat.judge == RaceJudge.RED || seat.judge == RaceJudge.FALSE_START || seat.judge == RaceJudge.EARLY ||
                seat.judge == RaceJudge.LATE || seat.judge == RaceJudge.DOUBLE
            if (penalty && !seat.isBot) {
                playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
                whyText = when (seat.judge) {
                    RaceJudge.RED -> androidContext.getString(R.string.trc_why_red, androidContext.seatLabel(seat.name), TapRaceRules.redPenalty(s.relaxed))
                    RaceJudge.FALSE_START -> androidContext.getString(R.string.trc_why_false_start, androidContext.seatLabel(seat.name))
                    RaceJudge.DOUBLE -> androidContext.getString(R.string.trc_why_double, androidContext.seatLabel(seat.name))
                    else -> androidContext.getString(R.string.trc_why_off_beat, androidContext.seatLabel(seat.name))
                }
                whySerial++
            } else if (!penalty && !seat.isBot && seat.judge != RaceJudge.NONE) {
                playSfx(SfxKind.LIGHT_TICK)
            }
        }
    }
    LaunchedEffect(whySerial) {
        if (whySerial == 0) return@LaunchedEffect
        delay(2600)
        whyText = null
    }

    // ---- words ----
    val phase = s.phaseValue
    val status: String = run {
        val who = s.seats.getOrNull(s.eventSeat)?.name ?: ""
        val ev: String = when (s.event) {
            RaceEvent.GO -> androidContext.getString(
                when (RaceVariant.fromId(s.variant)) {
                    RaceVariant.RHYTHM -> R.string.trc_status_go_rhythm
                    RaceVariant.BRAKE -> R.string.trc_status_go_brake
                    RaceVariant.SPRINT -> R.string.trc_status_go
                }
            )
            RaceEvent.AMBER -> androidContext.getString(R.string.trc_status_amber)
            RaceEvent.RED -> androidContext.getString(R.string.trc_status_red)
            RaceEvent.GREEN -> androidContext.getString(R.string.trc_status_green)
            RaceEvent.ROUND_WIN -> androidContext.youAware(who, R.string.trc_status_round_win, R.string.trc_status_round_win_you)
            RaceEvent.ROUND_DRAW -> androidContext.getString(R.string.trc_status_round_draw)
            RaceEvent.MATCH_WIN -> androidContext.youAware(who, R.string.trc_status_match_win, R.string.trc_status_match_win_you)
            RaceEvent.MATCH_DRAW -> androidContext.getString(R.string.trc_status_match_draw)
            else -> androidContext.getString(R.string.trc_status_ready, s.round, s.rounds)
        }
        val score = if (phase == RacePhase.ROUND_END || phase == RacePhase.OVER) {
            androidContext.getString(R.string.trc_score, s.seats.joinToString(", ") { "${androidContext.seatLabel(it.name)} ${it.roundWins}" })
        } else ""
        listOf(ev, score).filter { it.isNotBlank() }.joinToString(" ")
    }

    val sessionBouts = s.sessionWins.values.sum() + s.sessionDraws
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.over,
        onLeave = game::leaveSession,
        onAbort = { if (sessionBouts > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = sessionBouts == 0,
        buttonFill = MaterialTheme.colorScheme.background, buttonContent = MaterialTheme.colorScheme.onBackground
    ) {
        val (_, openCoach) = rememberClCoach("tap-race", ready = true)
        val onBg = MaterialTheme.colorScheme.onBackground
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                ClCoachButton("tap-race", openCoach)
                Column(Modifier.weight(1f)) {
                    ClStatusLine(status, onBg, Modifier.fillMaxWidth(), emphasized = true)
                }
            }
            ClWhyLine(whyText, MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
            val zones = remember(seatCount, rotate) { TapTugZoneLayout.forSeats(seatCount, rotate, BAND) }
            val bandZone = remember { TapTugZoneLayout.bandRect(BAND) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TapTugBoard(
                    zones = zones, band = bandZone, interactive = s.seats.map { !it.isBot }, tracker = tracker,
                    a11y = { seat ->
                        val me = s.seats[seat]
                        val name = androidContext.seatLabel(me.name)
                        TapTugZoneA11y(
                            description = androidContext.getString(
                                if (me.isBot) R.string.trc_zone_desc_cpu else R.string.trc_zone_desc, name, me.progress, s.target
                            ),
                            onActivate = { if (!me.isBot) game.tap(seat) }
                        )
                    },
                    onPress = { seat, pid ->
                        val r = game.press(seat, pid)
                        pressCount[seat] = (pressCount[seat] ?: 0) + 1
                        val now = System.currentTimeMillis()
                        if (r == TapReject.NONE && now - lastSfx[0] > 60) {
                            lastSfx[0] = now
                            if (s.variant != RaceVariant.RHYTHM.id) { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
                        }
                    },
                    onRelease = { seat, pid ->
                        game.release(seat, pid)
                        pressCount[seat] = ((pressCount[seat] ?: 1) - 1).coerceAtLeast(0)
                    },
                    modifier = Modifier.fillMaxSize(),
                    bandContent = { RaceBand(s, rotate, dark) },
                    zoneContent = { seat ->
                        RaceZone(seat, s, pressed = (pressCount[seat] ?: 0) > 0, goFlash = goFlash, reduced = reduced, dark = dark, speedScale = { gameSpeed.scale(it) })
                    },
                    overlay = {
                        Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                        if (s.over) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                                RaceFinished(s, humans, game, androidContext)
                            }
                        }
                    }
                )
            }
        }
    }
}

/** The centre of a seat's zone as a fraction of the board, for confetti. */
private fun zoneCenter(seat: Int, seats: Int): Offset {
    val z = TapTugZoneLayout.forSeats(seats, rotateTop = false, band = BAND).getOrNull(seat) ?: return Offset(0.5f, 0.5f)
    return Offset((z.left + z.right) / 2f, (z.top + z.bottom) / 2f)
}

@Composable
private fun RaceFinished(s: TapRaceState, humans: Int, game: TapRaceGame, ctx: Context) {
    val winner = s.winnerSeats.firstOrNull()
    val name = winner?.let { s.seats[it].name } ?: ""
    val title = when {
        winner == null -> stringResource(R.string.ccl_a_draw)
        humans == 1 && !s.seats[winner].isBot -> stringResource(R.string.ccl_you_won)
        humans == 1 -> stringResource(R.string.ccl_you_lost)
        else -> ctx.getString(R.string.trc_status_match_win, ctx.seatLabel(name))
    }
    val rounds = s.seats.joinToString(" · ") { "${ctx.seatLabel(it.name)} ${it.roundWins}" }
    val session = s.seats.joinToString(" · ") { "${ctx.seatLabel(it.name)} ${s.sessionWins[it.playerId] ?: 0}" }
    Box(Modifier.padding(16.dp)) {
        ClFinishedPanel(
            title = title, subtitle = null,
            lines = listOf(stringResource(R.string.trc_final_rounds, rounds), stringResource(R.string.ccl_session, session)),
            canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
        )
    }
}

/** The strip between the rows: the round, the race type, and (Mash and Brake) the traffic light. Both rows can read it. */
@Composable
private fun RaceBand(s: TapRaceState, rotate: Boolean, dark: Boolean) {
    val variantName = stringResource(
        when (RaceVariant.fromId(s.variant)) {
            RaceVariant.SPRINT -> R.string.trc_variant_sprint
            RaceVariant.RHYTHM -> R.string.trc_variant_rhythm
            RaceVariant.BRAKE -> R.string.trc_variant_brake
        }
    )
    val info = if (s.tiebreak) stringResource(R.string.trc_band_tiebreak, variantName) else stringResource(R.string.trc_band_info, s.round, s.rounds, variantName)
    val light = s.lightValue
    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        val textMod = Modifier.weight(1f)
        Text(
            info, textMod.graphicsLayer { rotationZ = if (rotate) 180f else 0f }, color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge
        )
        if (light != RaceLight.NONE) {
            Canvas(Modifier.padding(horizontal = 6.dp).height(34.dp).width(110.dp)) { drawTapTugLight(Offset.Zero, size, light, horizontal = true) }
        } else {
            Canvas(Modifier.padding(horizontal = 6.dp).height(24.dp).width(24.dp)) { drawTapTugFlag(Offset(size.width * 0.2f, size.height), size.height, dark) }
        }
        Text(
            info, textMod, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun RaceZone(
    seat: Int, s: TapRaceState, pressed: Boolean, goFlash: Boolean, reduced: Boolean, dark: Boolean, speedScale: (Long) -> Long
) {
    val me: RaceSeatState = s.seats[seat]
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val color = TapTugSeatColors[seat % 4]
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val phase = s.phaseValue
    val variant = RaceVariant.fromId(s.variant)
    val light = s.lightValue
    var shown by remember(seat) { mutableStateOf<String?>(null) }
    LaunchedEffect(me.judgeSerial) {
        if (me.judgeSerial > 0 && me.judge != RaceJudge.NONE) {
            shown = me.judge
            delay(speedScale(900L))
            shown = null
        }
    }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, if (reduced) snap<Float>() else spring<Float>(dampingRatio = 0.45f, stiffness = 600f), label = "pad")
    val roundWon = phase == RacePhase.ROUND_END && s.roundWinnerSeat == seat
    val dim = (phase == RacePhase.ROUND_END && s.roundWinnerSeat >= 0 && !roundWon)
    val frozen = me.lockedMs > 0
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxSize()
            .padding(3.dp)
            .clip(shape)
            .background(surface)
            .drawBehind {
                drawRect(color.copy(alpha = if (dark) 0.16f else 0.12f))
                drawTapTugPattern(seat, Offset.Zero, size, color.copy(alpha = if (dark) 0.22f else 0.16f))
                if (roundWon) drawRoundRect(color, Offset.Zero, size, CornerRadius(18f * density), style = androidx.compose.ui.graphics.drawscope.Stroke(5f * density))
            }
            .padding(6.dp)
    ) {
        // name row: badge, name, round wins, taps
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ClSeatBadge(color, tapTugGlyph(seat), 22)
            Text(
                ctx.seatLabel(me.name) + if (me.isBot) " " + stringResource(R.string.trc_cpu_tag) else "",
                Modifier.weight(1f).padding(start = 6.dp), color = onSurface, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall
            )
            Text(
                "★".repeat(me.roundWins.coerceAtMost(5)) + "  " + me.progress + "/" + s.target,
                color = onSurface, maxLines = 1, style = MaterialTheme.typography.labelLarge
            )
        }
        // the lane
        Canvas(Modifier.fillMaxWidth().height(34.dp).padding(vertical = 2.dp)) {
            val h = size.height
            val pad = h * 0.5f
            val trackW = size.width - pad * 2
            drawRoundRect(onSurface.copy(alpha = 0.16f), Offset(pad / 2, h * 0.3f), Size(size.width - pad, h * 0.4f), CornerRadius(h * 0.2f))
            val p = (me.progress.toFloat() / s.target.coerceAtLeast(1)).coerceIn(0f, 1f)
            drawRoundRect(color, Offset(pad / 2, h * 0.3f), Size((size.width - pad) * p, h * 0.4f).let { Size(maxOf(it.width, h * 0.4f), it.height) }, CornerRadius(h * 0.2f))
            for (i in 1..3) drawLine(onSurface.copy(alpha = 0.25f), Offset(pad + trackW * i / 4f, h * 0.3f), Offset(pad + trackW * i / 4f, h * 0.7f), 2f)
            drawTapTugFlag(Offset(size.width - pad * 0.35f, h), h * 0.95f, dark)
            drawMarble(Offset(pad + trackW * p * 0.96f, h / 2f), h * 0.44f, color, tapTugGlyph(seat), glyph = 0.7f, scale = if (roundWon && !reduced) 1.15f else 1f)
        }
        // the pad
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ringColor = when (light) {
                RaceLight.GREEN -> Color(0xFF1FA85A)
                RaceLight.AMBER -> Color(0xFFF2B100)
                RaceLight.RED -> Color(0xFFD7263D)
                RaceLight.NONE -> onSurface
            }
            Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }) {
                val r = (minOf(size.width, size.height) * 0.40f).coerceAtLeast(24f * density)
                val c = Offset(size.width / 2f, size.height / 2f)
                if (variant == RaceVariant.RHYTHM && phase == RacePhase.RACE) {
                    val ringR = r * (1f + 0.9f * (1f - s.beatPhase))
                    val strength = if (reduced) 0.9f else (0.35f + 0.65f * s.beatPhase)
                    drawCircle(onSurface.copy(alpha = strength), ringR, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.07f))
                    val good = r * (TapRaceRules.goodWindowMs(s.relaxed).toFloat() / TapRaceRules.beatMs(s.relaxed)) * 1.8f
                    drawCircle(color.copy(alpha = 0.35f), r + good, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f * density))
                }
                drawTapTugPad(c, r, color, seat, pressed, enabled = !me.isBot && !frozen && !dim, ring = if (variant == RaceVariant.BRAKE) ringColor else onSurface)
                if (variant == RaceVariant.BRAKE && phase == RacePhase.RACE && light != RaceLight.NONE) {
                    drawTapTugLight(Offset(size.width - r * 0.9f, c.y - r * 1.05f).let { Offset(it.x.coerceAtLeast(0f), it.y.coerceAtLeast(0f)) }, Size(r * 0.55f, r * 1.3f), light, horizontal = false)
                }
            }
            // words over the pad
            val center: String? = when {
                phase == RacePhase.COUNTDOWN -> ceil(s.countdownMs / 1000f).toInt().coerceAtLeast(1).toString()
                goFlash -> stringResource(R.string.trc_go)
                frozen -> stringResource(R.string.trc_frozen)
                variant == RaceVariant.BRAKE && phase == RacePhase.RACE -> when (light) {
                    RaceLight.GREEN -> stringResource(R.string.trc_light_go)
                    RaceLight.AMBER -> stringResource(R.string.trc_light_slow)
                    RaceLight.RED -> stringResource(R.string.trc_light_stop)
                    RaceLight.NONE -> null
                }
                roundWon -> stringResource(R.string.trc_zone_won)
                else -> null
            }
            if (center != null) {
                Text(
                    center, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, maxLines = 1, textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            val j = shown
            if (j != null) {
                Text(
                    stringResource(
                        when (j) {
                            RaceJudge.PERFECT -> R.string.trc_judge_perfect
                            RaceJudge.GOOD -> R.string.trc_judge_good
                            RaceJudge.EARLY -> R.string.trc_judge_early
                            RaceJudge.LATE -> R.string.trc_judge_late
                            RaceJudge.DOUBLE -> R.string.trc_judge_double
                            RaceJudge.FALSE_START -> R.string.trc_judge_false_start
                            else -> R.string.trc_judge_red
                        }
                    ),
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.TopCenter).background(surface.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }
        }
    }
}
