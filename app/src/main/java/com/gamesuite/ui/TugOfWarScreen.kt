package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.gamesuite.games.tugofwar.TugEvent
import com.gamesuite.games.tugofwar.TugLayout
import com.gamesuite.games.tugofwar.TugOfWarGame
import com.gamesuite.games.tugofwar.TugPhase
import com.gamesuite.games.tugofwar.TugPull
import com.gamesuite.games.tugofwar.TugReject
import com.gamesuite.games.tugofwar.TugRules
import com.gamesuite.games.tugofwar.TugSeatState
import com.gamesuite.games.tugofwar.TugSides
import com.gamesuite.games.tugofwar.TugState
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

/**
 * Tug of War on one screen: a marker on a rope (teams) or in a small arena (free-for-all), and a pad for every player
 * in their own zone, all pulling at once. A tap pulls and costs stamina; a finger held down rests and charges, and
 * letting go heaves. The far row is turned half a turn so players sitting opposite read their own pad the right way
 * up, and each of them sees "their" goal on their right. Colour-blind safe: every side has a colour, a glyph and a
 * surface pattern, and the stamina bar says "Tired" in words. TalkBack: each pad is a 48dp+ button that a double-tap
 * pulls, with a "Heave" action for players who cannot hold a finger down. Same screen only: no Nearby, no Online.
 */
@Composable
fun TugOfWarScreen(
    sessionManager: GameSessionManager,
    game: TugOfWarGame,
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
        game.layout = TugLayout.fromId(extras["layout"])
        game.rounds = extras["rounds"]?.toIntOrNull()?.takeIf { it in TugRules.ROUND_CHOICES } ?: TugRules.DEFAULT_ROUNDS
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

    LaunchedEffect(game) {
        while (true) {
            game.tick()
            delay(25)
        }
    }

    val helpTitle = stringResource(R.string.tow_help_title)
    val helpText = stringResource(R.string.tow_help_text)
    val s = state ?: return
    val seatCount = s.seats.size
    val ffa = s.sides > 2
    val band = if (ffa) 0.30f else 0.20f
    val tracker = remember { TapTugPointerTracker() }
    val pressCount = remember { mutableStateMapOf<Int, Int>() }
    val lastSfx = remember { LongArray(1) }
    val seenPull = remember { IntArray(4) }
    var goFlash by remember { mutableStateOf(false) }
    val humans = s.seats.count { !it.isBot }

    fun sideNames(side: Int): String = s.seats.filter { it.side == side }.joinToString(" & ") { androidContext.seatLabel(it.name) }
    fun sideSeatsCount(side: Int) = s.seats.count { it.side == side }

    LaunchedEffect(s.eventSerial) {
        if (s.eventSerial == 0) return@LaunchedEffect
        when (s.event) {
            TugEvent.GO -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.STRONG_ACTION); goFlash = true; delay(gameSpeed.scale(800L)); goFlash = false }
            TugEvent.ROUND_WIN, TugEvent.ROUND_DRAW -> {
                playSfx(SfxKind.WHOOSH); haptics(HapticSignal.SUCCESS)
                if (s.eventSide >= 0 && !reduced) {
                    s.seats.forEachIndexed { i, seat ->
                        if (seat.side == s.eventSide) burst.spawn(tugZoneCenter(i, s, band, rotate), 26, listOf(TapTugSeatColors[teamColorIndex(i, s)], Color.White), speedRange = 0.2f..0.7f, lifeRangeSeconds = 0.8f..1.5f, gravity = 0.8f)
                    }
                }
            }
            TugEvent.MATCH_WIN -> {
                playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                if (!reduced) burst.spawn(Offset(0.5f, 0.5f), 90, listOf(Color(0xFFF2B100), Color.White, TapTugSeatColors[s.eventSide.coerceAtLeast(0) % 4]), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
            }
            TugEvent.MATCH_DRAW -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
            else -> {}
        }
    }
    // A heave by a person: a thump you can feel.
    val pullSum = s.seats.sumOf { it.pullSerial }
    LaunchedEffect(pullSum) {
        s.seats.forEachIndexed { i, seat ->
            if (i > 3 || seat.pullSerial == seenPull[i]) return@forEachIndexed
            seenPull[i] = seat.pullSerial
            if (seat.pullKind == TugPull.HEAVE && !seat.isBot) { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.STRONG_ACTION) }
        }
    }

    val phase = s.phaseValue
    val status: String = run {
        val side = s.eventSide
        val one = side >= 0 && sideSeatsCount(side) == 1
        val lead = if (side >= 0) sideNames(side) else ""
        val soleName = s.seats.firstOrNull { it.side == side }?.name ?: ""
        val ev: String = when (s.event) {
            TugEvent.GO -> androidContext.getString(R.string.tow_status_go)
            TugEvent.ROUND_WIN ->
                if (one) androidContext.youAware(soleName, R.string.tow_status_round_win, R.string.tow_status_round_win_you)
                else androidContext.getString(R.string.tow_status_round_win_team, lead)
            TugEvent.ROUND_DRAW -> androidContext.getString(R.string.tow_status_round_draw)
            TugEvent.MATCH_WIN ->
                if (one) androidContext.youAware(soleName, R.string.tow_status_match_win, R.string.tow_status_match_win_you)
                else androidContext.getString(R.string.tow_status_match_win_team, lead)
            TugEvent.MATCH_DRAW -> androidContext.getString(R.string.tow_status_match_draw)
            else -> androidContext.getString(R.string.tow_status_ready, s.round, s.rounds)
        }
        val score = if (phase == TugPhase.ROUND_END || phase == TugPhase.OVER) {
            androidContext.getString(R.string.tow_score, (0 until s.sides).joinToString(", ") { "${sideNames(it)} ${s.sideWins.getOrElse(it) { 0 }}" })
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
        val (_, openCoach) = rememberClCoach("tug-of-war", ready = true)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                ClCoachButton("tug-of-war", openCoach)
                ClStatusLine(status, MaterialTheme.colorScheme.onBackground, Modifier.weight(1f), emphasized = true)
            }
            val bottomCount = tugBottomCount(s)
            val zones = remember(seatCount, bottomCount, rotate, band) { TapTugZoneLayout.rows(bottomCount, seatCount - bottomCount, rotate, band) }
            val bandZone = remember(band) { TapTugZoneLayout.bandRect(band) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TapTugBoard(
                    zones = zones, band = bandZone, interactive = s.seats.map { !it.isBot }, tracker = tracker,
                    a11y = { seat ->
                        val me = s.seats[seat]
                        val name = androidContext.seatLabel(me.name)
                        TapTugZoneA11y(
                            description = androidContext.getString(if (me.isBot) R.string.tow_zone_desc_cpu else R.string.tow_zone_desc, name, me.stamina),
                            onActivate = { if (!me.isBot) game.tap(seat) },
                            extraLabel = if (me.isBot) null else androidContext.getString(R.string.tow_action_heave),
                            onExtra = { if (!me.isBot) game.heave(seat) }
                        )
                    },
                    onPress = { seat, pid ->
                        val r = game.press(seat, pid)
                        pressCount[seat] = (pressCount[seat] ?: 0) + 1
                        val now = System.currentTimeMillis()
                        if (r == TugReject.NONE && now - lastSfx[0] > 60) {
                            lastSfx[0] = now
                            playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK)
                        }
                    },
                    onRelease = { seat, pid ->
                        game.release(seat, pid)
                        pressCount[seat] = ((pressCount[seat] ?: 1) - 1).coerceAtLeast(0)
                    },
                    modifier = Modifier.fillMaxSize(),
                    bandContent = { TugBand(s, ffa, rotate, dark) },
                    zoneContent = { seat -> TugZone(seat, s, pressed = (pressCount[seat] ?: 0) > 0, goFlash = goFlash, reduced = reduced, dark = dark, ffa = ffa, speedScale = { gameSpeed.scale(it) }) },
                    overlay = {
                        Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                        if (s.over) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                                TugFinished(s, humans, game, androidContext) { side -> sideNames(side) }
                            }
                        }
                    }
                )
            }
        }
    }
}

/** Seats of the bottom row: the whole first side in a team layout, half the table in a free-for-all. */
private fun tugBottomCount(s: TugState): Int =
    if (s.sides > 2) TapTugZoneLayout.defaultBottomCount(s.seats.size) else s.seats.count { it.side == 0 }

/** The colour index of a seat: its side in a team layout (teammates match), its seat in a free-for-all. */
private fun teamColorIndex(seat: Int, s: TugState): Int = if (s.sides > 2) seat else s.seats[seat].side

private fun tugZoneCenter(seat: Int, s: TugState, band: Float, rotate: Boolean): Offset {
    val b = tugBottomCount(s)
    val z = TapTugZoneLayout.rows(b, s.seats.size - b, rotate, band).getOrNull(seat) ?: return Offset(0.5f, 0.5f)
    return Offset((z.left + z.right) / 2f, (z.top + z.bottom) / 2f)
}

@Composable
private fun TugFinished(s: TugState, humans: Int, game: TugOfWarGame, ctx: Context, sideName: (Int) -> String) {
    val winner = s.winnerSide
    val mineWon = winner >= 0 && s.seats.any { !it.isBot && it.side == winner }
    val title = when {
        winner < 0 -> stringResource(R.string.ccl_a_draw)
        humans == 1 && mineWon -> stringResource(R.string.ccl_you_won)
        humans == 1 -> stringResource(R.string.ccl_you_lost)
        else -> ctx.getString(R.string.tow_status_match_win_team, sideName(winner))
    }
    val rounds = (0 until s.sides).joinToString(" · ") { "${sideName(it)} ${s.sideWins.getOrElse(it) { 0 }}" }
    val session = s.seats.joinToString(" · ") { "${ctx.seatLabel(it.name)} ${s.sessionWins[it.playerId] ?: 0}" }
    Box(Modifier.padding(16.dp)) {
        ClFinishedPanel(
            title = title, subtitle = null,
            lines = listOf(stringResource(R.string.tow_final_rounds, rounds), stringResource(R.string.ccl_session, session)),
            canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
        )
    }
}

/** The strip between the rows: the rope with its marker (teams) or the arena (free-for-all), and the round for both rows to read. */
@Composable
private fun TugBand(s: TugState, ffa: Boolean, rotate: Boolean, dark: Boolean) {
    val info = (if (s.tiebreak) stringResource(R.string.tow_band_tiebreak) else stringResource(R.string.tow_band_info, s.round, s.rounds)) +
        if (s.phaseValue == TugPhase.PULL) "\n" + stringResource(R.string.tow_time_left, ceil(s.roundLeftMs / 1000f).toInt()) else ""
    val density = LocalDensity.current.density
    val rope = if (dark) Color(0xFFD9B98C) else Color(0xFF8A6034)
    val ropeDark = if (dark) Color(0xFFAB8A5E) else Color(0xFF5E3F1F)
    val ink = MaterialTheme.colorScheme.onBackground
    // Large system fonts: give the round/time text more of the strip and a smaller style so it is never clipped.
    val bigFont = LocalDensity.current.fontScale > 1.25f
    val sideW = if (bigFont) 1.0f else 0.6f
    val infoStyle = if (bigFont) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            info, Modifier.weight(sideW).graphicsLayer { rotationZ = if (rotate) 180f else 0f }, color = ink, textAlign = TextAlign.Center,
            maxLines = 4, overflow = TextOverflow.Ellipsis, style = infoStyle
        )
        Canvas(Modifier.weight(if (bigFont) 2.2f else 2.8f).fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (!ffa) {
                val pad = h * 0.55f
                val left = pad
                val right = w - pad
                val cy = h / 2f
                val span = (right - left) / 2f
                val mid = (left + right) / 2f
                // goal posts: right = side 0, left = side 1, each with its colour and glyph
                for (side in 0..1) {
                    val x = if (side == 0) right else left
                    val col = TapTugSeatColors[side]
                    drawLine(col, Offset(x, cy - h * 0.42f), Offset(x, cy + h * 0.42f), 6f * density, StrokeCap.Round)
                    drawMarble(Offset(x, cy - h * 0.42f), h * 0.16f, col, tapTugGlyph(side), glyph = 0.8f)
                }
                // the rope: a slightly sagging braid
                val sag = h * 0.07f
                val path = Path().apply { moveTo(left, cy); quadraticBezierTo(mid, cy + sag * 2f, right, cy) }
                drawPath(path, ropeDark, style = Stroke(h * 0.14f, cap = StrokeCap.Round))
                drawPath(path, rope, style = Stroke(h * 0.09f, cap = StrokeCap.Round))
                var x = left
                while (x < right) { drawLine(ropeDark.copy(alpha = 0.7f), Offset(x, cy - h * 0.05f + sag * 0.4f), Offset(x + h * 0.05f, cy + h * 0.05f + sag * 0.4f), 2f * density); x += h * 0.12f }
                // the start line and the marker
                drawLine(ink.copy(alpha = 0.5f), Offset(mid, cy - h * 0.38f), Offset(mid, cy + h * 0.38f), 2f * density)
                val mxp = mid + (s.markerX / TugRules.GOAL.toFloat()).coerceIn(-1f, 1f) * span
                val my = cy + sag * 1.0f
                drawCircle(Color(0xFFD7263D), h * 0.17f, Offset(mxp, my))
                drawCircle(Color.White.copy(alpha = 0.7f), h * 0.05f, Offset(mxp - h * 0.05f, my - h * 0.06f))
                drawCircle(Color(0xFF7A1020), h * 0.17f, Offset(mxp, my), style = Stroke(2.5f * density))
            } else {
                val r = minOf(w, h) * 0.40f
                val c = Offset(w / 2f, h / 2f)
                drawCircle(ink.copy(alpha = 0.10f), r * 1.12f, c)
                drawCircle(ink.copy(alpha = 0.35f), r * 1.12f, c, style = Stroke(2f * density))
                s.dirX.indices.forEach { i ->
                    val dx = s.dirX[i]; val dy = s.dirY[i]
                    val gp = Offset(c.x + dx * r, c.y + dy * r)
                    drawLine(ink.copy(alpha = 0.25f), c, gp, 2f * density)
                    drawMarble(gp, minOf(r * 0.2f, h * 0.17f), TapTugSeatColors[i % 4], tapTugGlyph(i), glyph = 0.8f)
                }
                val mp = Offset(c.x + s.markerX / TugRules.GOAL.toFloat() * r, c.y + s.markerY / TugRules.GOAL.toFloat() * r)
                drawCircle(Color(0xFFD7263D), r * 0.11f, mp)
                drawCircle(Color(0xFF7A1020), r * 0.11f, mp, style = Stroke(2.5f * density))
            }
        }
        Text(
            info, Modifier.weight(sideW), color = ink, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
            style = infoStyle
        )
    }
}

@Composable
private fun TugZone(
    seat: Int, s: TugState, pressed: Boolean, goFlash: Boolean, reduced: Boolean, dark: Boolean, ffa: Boolean, speedScale: (Long) -> Long
) {
    val me: TugSeatState = s.seats[seat]
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val ci = teamColorIndex(seat, s)
    val color = TapTugSeatColors[ci % 4]
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val phase = s.phaseValue
    var heaveShown by remember(seat) { mutableStateOf(false) }
    LaunchedEffect(me.pullSerial) {
        if (me.pullKind == TugPull.HEAVE) { heaveShown = true; delay(speedScale(650L)); heaveShown = false }
    }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, if (reduced) snap<Float>() else spring<Float>(dampingRatio = 0.45f, stiffness = 600f), label = "pad")
    val roundWon = phase == TugPhase.ROUND_END && s.roundWinnerSide == me.side
    val wins = s.sideWins.getOrElse(me.side) { 0 }
    Column(
        Modifier
            .fillMaxSize()
            .padding(3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(surface)
            .drawBehind {
                drawRect(color.copy(alpha = if (dark) 0.16f else 0.12f))
                drawTapTugPattern(ci, Offset.Zero, size, color.copy(alpha = if (dark) 0.22f else 0.16f))
                if (roundWon) drawRoundRect(color, Offset.Zero, size, CornerRadius(18f * density), style = Stroke(5f * density))
            }
            .padding(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ClSeatBadge(color, tapTugGlyph(ci), 22)
            Text(
                ctx.seatLabel(me.name) + if (me.isBot) " " + stringResource(R.string.tow_cpu_tag) else "",
                Modifier.weight(1f).padding(start = 6.dp), color = onSurface, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall
            )
            Text("★".repeat(wins.coerceAtMost(5)), color = onSurface, maxLines = 1, style = MaterialTheme.typography.labelLarge)
            if (!ffa) Canvas(Modifier.padding(start = 4.dp).width(26.dp).height(18.dp)) {
                // "your goal is to your right": a chevron in the side colour
                val p = Path().apply { moveTo(size.width * 0.1f, 0f); lineTo(size.width, size.height / 2f); lineTo(size.width * 0.1f, size.height); lineTo(size.width * 0.35f, size.height / 2f); close() }
                drawPath(p, color)
            }
        }
        // stamina
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).height(18.dp)) {
                val h = size.height
                drawRoundRect(onSurface.copy(alpha = 0.18f), Offset.Zero, size, CornerRadius(h / 2f))
                val p = (me.stamina / 100f).coerceIn(0f, 1f)
                val fillCol = if (me.tired) Color(0xFFD7263D) else color
                drawRoundRect(fillCol, Offset.Zero, Size(maxOf(size.width * p, h), h), CornerRadius(h / 2f))
                if (me.tired) drawTapTugPattern(0, Offset.Zero, Size(maxOf(size.width * p, h), h), Color.White.copy(alpha = 0.45f))
            }
            Text(
                if (me.tired) stringResource(R.string.tow_tired) else stringResource(R.string.tow_stamina_pct, me.stamina),
                Modifier.padding(start = 6.dp), color = onSurface, maxLines = 1, style = MaterialTheme.typography.labelMedium
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }) {
                val r = (minOf(size.width, size.height) * 0.40f).coerceAtLeast(24f * density)
                val c = Offset(size.width / 2f, size.height / 2f)
                drawTapTugPad(c, r, color, ci, pressed, enabled = !me.isBot && (phase == TugPhase.PULL || phase == TugPhase.COUNTDOWN), ring = onSurface)
                if (me.held && me.charge > 0f) {
                    drawArc(
                        Color(0xFFF2B100), -90f, 360f * me.charge, false, Offset(c.x - r * 1.18f, c.y - r * 1.18f), Size(r * 2.36f, r * 2.36f),
                        style = Stroke(r * 0.12f, cap = StrokeCap.Round)
                    )
                }
            }
            val center: String? = when {
                phase == TugPhase.COUNTDOWN -> ceil(s.countdownMs / 1000f).toInt().coerceAtLeast(1).toString()
                goFlash -> stringResource(R.string.tow_go)
                heaveShown -> stringResource(R.string.tow_heave)
                me.braced && !me.isBot -> stringResource(R.string.tow_resting)
                me.tired && !me.isBot && phase == TugPhase.PULL -> stringResource(R.string.tow_rest_hint)
                roundWon -> stringResource(R.string.tow_zone_won)
                else -> null
            }
            if (center != null) {
                Text(
                    center, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, maxLines = 2, textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.38f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
    }
}
