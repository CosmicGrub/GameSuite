package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.gamesuite.games.partypack.TsKind
import com.gamesuite.games.partypack.TsPadSet
import com.gamesuite.games.partypack.TsPadSpec
import com.gamesuite.games.partypack.TsPointerRouter
import com.gamesuite.games.partypack.TsRegion
import com.gamesuite.games.partypack.TsRouterListener
import com.gamesuite.games.partypack.TsZone
import com.gamesuite.games.partypack.TsZoneLayout
import com.gamesuite.games.sumopush.SuBoard
import com.gamesuite.games.sumopush.SuLog
import com.gamesuite.games.sumopush.SuPhase
import com.gamesuite.games.sumopush.SuView
import com.gamesuite.games.sumopush.SumoConfig
import com.gamesuite.games.sumopush.SumoPower
import com.gamesuite.games.sumopush.SumoPushGame
import com.gamesuite.games.sumopush.SumoRules
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private class SuColors(val bg: Color, val text: Color, val card: Color)

private fun suColors(dark: Boolean) = if (dark) SuColors(Color(0xFF17151B), Color(0xFFF1ECE6), Color(0xFF2A2630))
else SuColors(Color(0xFFF6F0E8), Color(0xFF2A2018), Color(0xFFE8DDD0))

/**
 * Sumo Push: round toy sumos in a ring that shrinks; push the others out. Each player steers with a thumb-stick or
 * a direction pad in their own zone (or just taps Dash in One-button mode, when the sumo steers itself) and holds
 * Dash to charge a shove. Every finger is bound to one control by [TsPointerRouter], so all players push at once.
 * Same screen only in this version (no Nearby or Online). Colour-blind safe (a belt pattern, a belly shape and a
 * name per sumo), Reduced-Motion aware. A thumb-stick cannot be driven by a screen reader, so the One-button
 * control is the TalkBack route and every control says so.
 */
@Composable
fun SumoPushScreen(
    sessionManager: GameSessionManager,
    game: SumoPushGame,
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
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = suColors(dark)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val burst = rememberParticleBurst()
    val frame = rememberTsFrameMs(active = !reduced)
    val currentHaptics by rememberUpdatedState(haptics)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.config = SumoConfig.fromExtras(ctx.players.size, sessionManager.launchOptions.extras)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }
    SideEffect { game.speedScale = gameSpeed.delayScale.toDouble() }

    LaunchedEffect(game) {
        while (true) withFrameMillis { game.tick() }
    }

    val ctx = context
    val info by game.info
    val helpTitle = stringResource(R.string.sup_help_title)
    val helpText = stringResource(R.string.sup_help_text)
    if (ctx == null || info.wins.isEmpty()) return

    val seats = ctx.players.size.coerceIn(2, 4)
    val faceEach = sessionManager.launchOptions.extras["zones"] != "false"
    val config = game.effectiveConfig
    val controls = sessionManager.launchOptions.extras["controls"]
    val padSet = when {
        config.oneSwitch -> TsPadSet.SUMO_ONE
        controls == "dpad" -> TsPadSet.SUMO_DPAD
        else -> TsPadSet.SUMO_STICK
    }
    fun rawName(seat: Int) = ctx.players.getOrNull(seat)?.displayName ?: ""
    fun seatName(seat: Int) = androidContext.seatLabel(rawName(seat))

    // ---- touch ----
    var pressed by remember { mutableStateOf(setOf<String>()) }
    var vectors by remember { mutableStateOf(mapOf<String, Pair<Float, Float>>()) }
    val router = remember(game) {
        val holder = arrayOfNulls<TsPointerRouter>(1)
        val r = TsPointerRouter(object : TsRouterListener {
            override fun onButton(region: TsRegion, down: Boolean) {
                val self = holder[0] ?: return
                if (down) { game.setDash(region.seat, true); currentHaptics(HapticSignal.LIGHT_TICK) }
                else if (!self.isPressed(region.id)) game.setDash(region.seat, false)
                pressed = self.regionsNow().filter { self.isPressed(it.id) }.map { it.id }.toSet()
            }

            override fun onVector(region: TsRegion, vx: Float, vy: Float) {
                game.setInput(region.seat, vx.toDouble(), vy.toDouble())
                vectors = vectors + (region.id to (vx to vy))
            }
        })
        holder[0] = r
        r
    }
    LaunchedEffect(info.paused, info.phase) {
        if (info.paused || info.phase == SuPhase.MATCH_OVER) {
            router.cancelAll(); pressed = emptySet()
            for (s in 0 until seats) { game.setInput(s, 0.0, 0.0); game.setDash(s, false) }
            vectors = emptyMap()
        }
    }

    // ---- effects from the engine's event log ----
    var status by remember { mutableStateOf("") }
    var seen by remember { mutableIntStateOf(0) }
    LaunchedEffect(info.eventSerial) {
        val events = game.eventsSince(seen)
        if (events.isEmpty()) return@LaunchedEffect
        seen = events.last().id
        for (ev in events) {
            val who = rawName(ev.seat.coerceAtLeast(0))
            when (ev.kind) {
                SuLog.COUNT -> { status = ev.value.toString(); playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
                SuLog.GO -> { status = androidContext.getString(R.string.tsp_go); playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.NORMAL_ACTION) }
                SuLog.HIT -> {
                    playSfx(SfxKind.SOLID_THUNK)
                    haptics(if (ev.value > 80) HapticSignal.STRONG_ACTION else HapticSignal.LIGHT_TICK)
                    if (!reduced) {
                        val nx = (0.5f + ev.x / 1000f / (2f * SumoRules.VIEW_HALF.toFloat())).coerceIn(0f, 1f)
                        val ny = (0.5f + ev.y / 1000f / (2f * SumoRules.VIEW_HALF.toFloat())).coerceIn(0f, 1f)
                        burst.spawn(Offset(nx, ny), 8, listOf(Color.White, TsSeatColors[ev.seat.coerceAtLeast(0) % 4]), speedRange = 0.15f..0.4f, lifeRangeSeconds = 0.25f..0.45f, gravity = 0f)
                    }
                }
                SuLog.DASH -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.NORMAL_ACTION) }
                SuLog.FELL -> {
                    status = androidContext.youAware(who, R.string.sup_ev_fell, R.string.sup_ev_fell_you)
                    playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.STRONG_ACTION)
                }
                SuLog.PICKUP -> {
                    status = if (ev.value == SumoPower.HEAVY.ordinal) androidContext.youAware(who, R.string.sup_ev_pick_heavy, R.string.sup_ev_pick_heavy_you)
                    else androidContext.youAware(who, R.string.sup_ev_pick_sprint, R.string.sup_ev_pick_sprint_you)
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.NORMAL_ACTION)
                }
                SuLog.ROUND_WIN -> {
                    status = androidContext.youAware(who, R.string.tsp_round_win, R.string.tsp_round_win_you)
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS)
                }
                SuLog.ROUND_DRAW -> { status = androidContext.getString(R.string.tsp_round_draw); playSfx(SfxKind.WHOOSH) }
                SuLog.MATCH_WIN -> {
                    status = androidContext.youAware(who, R.string.tsp_match_win, R.string.tsp_match_win_you)
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                    if (!reduced) burst.spawn(Offset(0.5f, 0.4f), 90, TsSeatColors + Color.White, speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
                }
                SuLog.MATCH_DRAW -> status = androidContext.getString(R.string.tsp_match_draw)
            }
        }
    }
    LaunchedEffect(info.shrinking) {
        if (info.shrinking) { status = androidContext.getString(R.string.sup_ring_shrinking); haptics(HapticSignal.ESCALATING) }
    }

    val finishedMatches = game.sessionWins.value.values.sum() + game.sessionDraws.value
    val (_, openCoach) = rememberClCoach("sumo-push", ready = true)
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = info.phase != SuPhase.MATCH_OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedMatches > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedMatches == 0,
        buttonFill = colors.bg, buttonContent = colors.text,
        leaveTitle = stringResource(R.string.tsp_leave_title),
        leaveBody = stringResource(if (finishedMatches > 0) R.string.tsp_leave_body_session else R.string.tsp_leave_body)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
            val d = LocalDensity.current
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            val perRow = if (seats > 2 && maxWidth < 600.dp) 2 else seats
            val chipRows = (seats + perRow - 1) / perRow
            // A wide, short screen (a phone on its side): the seat chips share the title row, so the arena keeps its height.
            val compact = maxHeight < 480.dp && maxWidth >= 600.dp
            val headerPx = with(d) { ((56 + if (compact) 0 else 44 * chipRows).dp * d.fontScale.coerceIn(1f, 1.6f)).toPx() }
            val thickPx = with(d) { (min(wPx, hPx) * 0.20f).coerceIn(84.dp.toPx(), 160.dp.toPx()) }
            val humans = List(seats) { !game.isBotSeat(it) }
            val layout = remember(wPx, hPx, headerPx, thickPx, humans, faceEach, padSet) {
                TsZoneLayout.compute(wPx, hPx, headerPx, humans, faceEach, padSet, thickPx, with(d) { 48.dp.toPx() }, with(d) { 6.dp.toPx() })
            }
            LaunchedEffect(layout) { router.setRegions(layout.regions()) }
            val boardDp = with(d) { layout.board.width.toDp() }
            val boardDesc = stringResource(R.string.sup_ring_desc, seats, info.alive.count { it })

            // 1. The ring.
            Box(
                Modifier
                    .offset { IntOffset(layout.board.left.roundToInt(), layout.board.top.roundToInt()) }
                    .size(boardDp)
                    .clip(RoundedCornerShape(18.dp))
                    .semantics { contentDescription = boardDesc }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val b = game.board.value ?: return@Canvas
                    drawRing(b, if (reduced) 0f else frame.value / 1000f, reduced)
                }
                Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                if (info.phase == SuPhase.COUNTDOWN) {
                    Text(
                        info.countdown.toString(), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.ExtraBold,
                        style = TextStyle(shadow = Shadow(Color(0xCC000000), Offset(2f, 2f), 8f)), modifier = Modifier.align(Alignment.Center)
                    )
                }
                if (status.isNotEmpty()) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { ClStatusLine(status, Color.White, emphasized = true) }
                }
            }

            // 2. Every player's zone.
            for (zone in layout.zones) {
                val seat = zone.seat
                val color = TsSeatColors[seat % 4]
                val alive = info.alive.getOrElse(seat) { true }
                if (!zone.human) {
                    TsCpuZone(zone, seatName(seat), stringResource(levelWord(game.difficulty)), colors.text)
                    continue
                }
                TsZoneBox(zone, Modifier.alpha(if (alive) 1f else 0.45f)) {
                    TsZoneBackdrop(color, dim = !alive)
                    for (p in zone.pads) {
                        if (p.kind == TsKind.BUTTON) DashPad(game, zone, p, color, p.id in pressed, seatName(seat))
                        else MovePad(zone, p, color, p.kind == TsKind.DPAD, vectors[p.id] ?: (0f to 0f), seatName(seat))
                    }
                }
            }

            // 3. The multi-touch layer (below the header).
            Box(
                Modifier
                    .offset { IntOffset(0, headerPx.roundToInt()) }
                    .size(with(d) { wPx.toDp() }, with(d) { (hPx - headerPx).coerceAtLeast(0f).toDp() })
                    .tsPointers(router, 0f, headerPx)
            )

            val chipContent: @Composable (Int, Modifier) -> Unit = { seat, mod ->
                TsSeatChip(
                    seat, seatName(seat), game.isBotSeat(seat),
                    info.wins.getOrElse(seat) { 0 }, info.winsNeeded, info.alive.getOrElse(seat) { true },
                    colors.text, colors.card, mod
                )
            }

            // 4. Header.
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("sumo-push", openCoach)
                    Text(
                        stringResource(R.string.tsp_hud_round, info.round, info.winsNeeded), color = colors.text,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                    if (compact) Box(Modifier.weight(3f)) { TsChipGrid(seats, seats, chipContent) }
                    OutlinedButton(
                        onClick = game::pause, enabled = info.phase != SuPhase.MATCH_OVER && !info.paused,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text), modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.tsp_pause)) }
                }
                if (!compact) TsChipGrid(seats, perRow, chipContent)
            }

            // 5. Overlays.
            if (info.phase == SuPhase.MATCH_OVER) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val winner = info.matchWinner
                    val title = if (winner < 0) stringResource(R.string.tsp_match_draw)
                    else androidContext.youAware(rawName(winner), R.string.tsp_match_win, R.string.tsp_match_win_you)
                    val tally = (0 until seats).joinToString(" · ") { "${seatName(it)} ${info.wins.getOrElse(it) { 0 }}" }
                    val session = ctx.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)} ${game.sessionWins.value[it.playerId] ?: 0}" }
                    Box(Modifier.widthIn(max = 460.dp).padding(16.dp)) {
                        ClFinishedPanel(
                            title = title, subtitle = stringResource(R.string.tsp_summary, tally),
                            lines = listOf(stringResource(R.string.ccl_session, session)),
                            canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
                        )
                    }
                }
            }
            if (info.paused) TsPauseOverlay(colors.text, colors.card) { game.resumePlay() }
        }
    }
}

private fun levelWord(d: CpuDifficulty): Int = when (d) {
    CpuDifficulty.EASY -> R.string.hub_easy
    CpuDifficulty.MEDIUM -> R.string.hub_medium
    CpuDifficulty.HARD -> R.string.hub_hard
}

/** The dash button: hold to charge (the outer ring fills), release to shove, then a cooldown (the face dims and refills). */
@Composable
private fun DashPad(game: SumoPushGame, zone: TsZone, p: TsPadSpec, color: Color, pressed: Boolean, name: String) {
    val seat = zone.seat
    val state by remember(game, seat) {
        derivedStateOf {
            val v = game.board.value?.sumos?.getOrNull(seat)
            when {
                v == null -> 0
                v.charge > 0f -> 1
                v.cooldown > 0f -> 2
                else -> 0
            }
        }
    }
    val on = tsOnColor(color)
    val word = stringResource(when (state) { 1 -> R.string.sup_dash_charging; 2 -> R.string.sup_dash_wait; else -> R.string.sup_dash_ready })
    val desc = stringResource(R.string.sup_dash_desc, name, word)
    TsLocalBox(
        p.local,
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (pressed) 1f else 0.74f))
            .border(if (pressed) 5.dp else 2.dp, on.copy(alpha = 0.8f), CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = desc
                onClick(label = desc) { game.setDash(seat, true); game.setDash(seat, false); true }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val v = game.board.value?.sumos?.getOrNull(seat)
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            if (v != null && v.cooldown > 0f) {
                // The part of the face still cooling down is darkened; it shrinks to nothing as the dash comes back.
                drawArc(Color.Black.copy(alpha = 0.38f), -90f, 360f * v.cooldown, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
            }
            if (v != null && v.charge > 0f) {
                drawArc(on, -90f, 360f * v.charge, false, Offset(c.x - r * 0.92f, c.y - r * 0.92f), Size(r * 1.84f, r * 1.84f), style = Stroke(r * 0.14f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.sup_dash), color = on, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(word, color = on, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** The thumb-stick or direction pad, drawn in the zone's own (rotated) frame; the vector comes from the router in screen axes. */
@Composable
private fun MovePad(zone: TsZone, p: TsPadSpec, color: Color, dpad: Boolean, vec: Pair<Float, Float>, name: String) {
    val desc = stringResource(if (dpad) R.string.sup_dpad_desc else R.string.sup_stick_desc, name)
    TsLocalBox(
        p.local,
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f))
            .border(3.dp, color, CircleShape)
            .semantics { contentDescription = desc }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val (lx, ly) = zone.toLocalVector(vec.first, vec.second)
            if (!dpad) {
                drawCircle(color.copy(alpha = 0.35f), r * 0.88f, c, style = Stroke(2.dp.toPx()))
                val knob = Offset(c.x + lx * r * 0.55f, c.y + ly * r * 0.55f)
                drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.4f, Offset(knob.x + r * 0.04f, knob.y + r * 0.06f))
                drawCircle(color, r * 0.4f, knob)
                drawCircle(tsOnColor(color).copy(alpha = 0.7f), r * 0.4f, knob, style = Stroke(2.dp.toPx()))
            } else {
                // Four arrows laid out by SCREEN direction (the pad moves the sumo on the screen), lit when held.
                for ((sx, sy) in listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)) {
                    val (dx, dy) = zone.toLocalVector(sx, sy)
                    val active = vec.first * sx + vec.second * sy > 0.5f
                    tsArrow(dx, dy, Offset(c.x + dx * r * 0.52f, c.y + dy * r * 0.52f), r * 0.5f, if (active) tsOnColor(color).copy(alpha = 1f) else color.copy(alpha = 0.6f))
                }
            }
        }
    }
}

private fun DrawScope.drawRing(b: SuBoard, t: Float, reduced: Boolean) {
    val s = size.width / 2f / SumoRules.VIEW_HALF.toFloat()
    val c = Offset(size.width / 2f, size.height / 2f)
    fun p(x: Double, y: Double) = Offset(c.x + (x * s).toFloat(), c.y + (y * s).toFloat())
    drawRect(Brush.radialGradient(listOf(Color(0xFF3A2F3F), Color(0xFF1F1A24)), c, size.width * 0.75f))
    val start = (b.ringStart * s).toFloat()
    val ring = (b.ringRadius * s).toFloat()
    // The ground the ring has shrunk off is darker, so the closing ring reads as a shrinking circle.
    drawCircle(Color(0xFF7A6340), start, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFEBD6A0), Color(0xFFD1AE6E)), c, ring), ring, c)
    for (k in 1..4) drawCircle(Color(0xFF8A6A32).copy(alpha = 0.10f), ring * k / 5f, c, style = Stroke(1.5f))
    drawLine(Color(0xFF5A3E1E), Offset(c.x - s * 0.16f, c.y - s * 0.07f), Offset(c.x - s * 0.16f, c.y + s * 0.07f), s * 0.025f)
    drawLine(Color(0xFF5A3E1E), Offset(c.x + s * 0.16f, c.y - s * 0.07f), Offset(c.x + s * 0.16f, c.y + s * 0.07f), s * 0.025f)
    // The rope: red with white dashes (a pattern, not only a colour).
    drawCircle(Color(0xFFB8341F), ring, c, style = Stroke(s * 0.045f))
    tsDashedCircle(c, ring, Color.White.copy(alpha = 0.85f), s * 0.03f, 28)

    val pulse = if (reduced) 0.5f else 0.5f + 0.5f * sin(t * 4f)
    for (it in b.items) {
        val o = p(it.x, it.y)
        val rr = s * 0.075f * (0.9f + 0.15f * pulse)
        drawCircle(Color.White.copy(alpha = 0.9f), rr * 1.35f, o)
        if (it.kind == SumoPower.HEAVY) {
            drawRect(Color(0xFF5A3E1E), Offset(o.x - rr * 0.7f, o.y - rr * 0.55f), Size(rr * 1.4f, rr * 1.1f))
        } else {
            tsArrow(1f, 0f, o, rr * 1.7f, Color(0xFF1B7F4F))
        }
    }
    // Fallen sumos first so the ones still in play are drawn on top.
    for (v in b.sumos.sortedBy { if (it.alive) 1 else 0 }) drawSumo(v, p(v.x, v.y), s, reduced)
}

private fun shade(c: Color, f: Float): Color = if (f >= 0f) lerp(c, Color.White, f) else lerp(c, Color.Black, -f)

private fun DrawScope.drawSumo(v: SuView, center: Offset, s: Float, reduced: Boolean) {
    val fall = if (v.alive) 0f else (v.fallMs.toFloat() / SumoRules.FALL_MS).coerceIn(0f, 1f)
    if (fall >= 1f) return
    val scale = if (reduced) 1f else 1f - 0.6f * fall
    val alpha = 1f - fall
    val r = (SumoRules.RADIUS * s).toFloat() * scale
    if (r < 1f) return
    val base = TsSeatColors[v.id % 4]
    val fx = v.facingX.toFloat(); val fy = v.facingY.toFloat()
    val px = -fy; val py = fx
    // Shadow, then a dash streak behind a dashing sumo.
    drawCircle(Color.Black.copy(alpha = 0.28f * alpha), r, Offset(center.x + r * 0.1f, center.y + r * 0.2f))
    if (v.dashing && !reduced) {
        for (k in -1..1) drawLine(
            Color.White.copy(alpha = 0.55f * alpha),
            Offset(center.x - fx * r * 1.1f + px * r * 0.5f * k, center.y - fy * r * 1.1f + py * r * 0.5f * k),
            Offset(center.x - fx * r * 2.3f + px * r * 0.5f * k, center.y - fy * r * 2.3f + py * r * 0.5f * k), r * 0.1f
        )
    }
    // Body.
    drawCircle(
        Brush.radialGradient(listOf(shade(base, 0.45f), base, shade(base, -0.35f)), Offset(center.x - r * 0.3f, center.y - r * 0.35f), r * 1.5f),
        r, center, alpha = alpha
    )
    // Belt (mawashi): a pattern per seat so sumos differ without colour: solid, dashed, dotted, double outline.
    val belt = Color.White.copy(alpha = 0.9f * alpha)
    val br = r * 0.66f
    when (v.id % 4) {
        0 -> drawCircle(belt, br, center, style = Stroke(r * 0.16f))
        1 -> tsDashedCircle(center, br, belt, r * 0.16f, 9)
        2 -> for (k in 0 until 12) {
            val a = k * Math.PI / 6
            drawCircle(belt, r * 0.07f, Offset(center.x + (kotlin.math.cos(a) * br).toFloat(), center.y + (sin(a) * br).toFloat()))
        }
        else -> {
            drawCircle(belt, br, center, style = Stroke(r * 0.05f))
            drawCircle(belt, br * 0.82f, center, style = Stroke(r * 0.05f))
        }
    }
    drawSeatGlyph(v.id, center, r * 0.2f)
    // Arms and head: the head leads the way the sumo faces.
    val skin = Color(0xFFF2C9A0).copy(alpha = alpha)
    for (sgn in listOf(-1f, 1f)) drawCircle(skin, r * 0.2f, Offset(center.x + fx * r * 0.62f + px * r * 0.86f * sgn, center.y + fy * r * 0.62f + py * r * 0.86f * sgn))
    val head = Offset(center.x + fx * r * 0.7f, center.y + fy * r * 0.7f)
    drawCircle(skin, r * 0.36f, head)
    drawCircle(Color(0xFF2B2018).copy(alpha = alpha), r * 0.13f, Offset(head.x - fx * r * 0.28f, head.y - fy * r * 0.28f)) // the topknot
    for (sgn in listOf(-1f, 1f)) drawCircle(Color(0xFF2B2018).copy(alpha = alpha), r * 0.045f, Offset(head.x + fx * r * 0.1f + px * r * 0.13f * sgn, head.y + fy * r * 0.1f + py * r * 0.13f * sgn))
    // Status rings: gold double ring for Heavy, chevrons for Sprint, a filling ring while charging.
    if (v.heavy) drawCircle(Color(0xFFFFC83D).copy(alpha = alpha), r * 1.1f, center, style = Stroke(r * 0.1f))
    if (v.sprint) tsArrow(-fx, -fy, Offset(center.x - fx * r * 1.45f, center.y - fy * r * 1.45f), r * 0.7f, Color(0xFF1B7F4F).copy(alpha = alpha))
    if (v.charge > 0f) {
        drawArc(Color.White.copy(alpha = alpha), -90f, 360f * v.charge, false, Offset(center.x - r * 1.22f, center.y - r * 1.22f), Size(r * 2.44f, r * 2.44f), style = Stroke(r * 0.12f))
    }
}
