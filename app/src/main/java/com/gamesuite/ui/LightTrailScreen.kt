package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shadow
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
import com.gamesuite.games.lighttrail.LightTrailConfig
import com.gamesuite.games.lighttrail.LightTrailGame
import com.gamesuite.games.lighttrail.LtBoard
import com.gamesuite.games.lighttrail.LtCause
import com.gamesuite.games.lighttrail.LtDir
import com.gamesuite.games.lighttrail.LtLog
import com.gamesuite.games.lighttrail.LtPhase
import com.gamesuite.games.lighttrail.LtPower
import com.gamesuite.games.lighttrail.LtTurn
import com.gamesuite.games.partypack.TsPadSet
import com.gamesuite.games.partypack.TsPointerRouter
import com.gamesuite.games.partypack.TsRegion
import com.gamesuite.games.partypack.TsRouterListener
import com.gamesuite.games.partypack.TsZoneLayout
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

private class LtColors(val bg: Color, val text: Color, val card: Color)

private fun ltColors(dark: Boolean) = if (dark) LtColors(Color(0xFF12141C), Color(0xFFEDEFF7), Color(0xFF252938))
else LtColors(Color(0xFFF1F4FA), Color(0xFF1B2030), Color(0xFFDDE3F0))

/** An expanding ring where a rider crashed. [atMs] is the frame clock when it started (0 under Reduced Motion). */
private class CrashFx(val x: Int, val y: Int, val seat: Int, val atMs: Long)

/**
 * Light-Trail Duel: 2 to 4 riders, each leaving a glowing wall; touch a wall or the edge and you are out. Every
 * player has Left and Right pads (or one button) in their own zone, turned to face them; every finger is bound to
 * one pad by [TsPointerRouter], so all players steer at once. Same screen only in this version (no Nearby or
 * Online). Colour-blind safe (each seat has a colour, a head shape, a trail pattern and a name), Reduced-Motion
 * aware, TalkBack-friendly (the pads are labelled buttons and every crash and round result is announced).
 */
@Composable
fun LightTrailScreen(
    sessionManager: GameSessionManager,
    game: LightTrailGame,
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
    val colors = ltColors(dark)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val burst = rememberParticleBurst()
    val frame = rememberTsFrameMs(active = !reduced)
    val currentHaptics by rememberUpdatedState(haptics)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.config = LightTrailConfig.fromExtras(ctx.players.size, sessionManager.launchOptions.extras)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }
    SideEffect { game.speedScale = gameSpeed.delayScale.toDouble() }

    // The clock: one tick a frame; the game turns it into fixed simulation steps.
    LaunchedEffect(game) {
        while (true) withFrameMillis { game.tick() }
    }

    val ctx = context
    val info by game.info
    val helpTitle = stringResource(R.string.ltd_help_title)
    val helpText = stringResource(R.string.ltd_help_text)
    if (ctx == null || info.wins.isEmpty()) return

    val seats = ctx.players.size.coerceIn(2, 4)
    val faceEach = sessionManager.launchOptions.extras["zones"] != "false"
    val config = game.effectiveConfig
    val padSet = if (config.oneSwitch) TsPadSet.TRAIL_ONE else TsPadSet.TRAIL_PADS
    fun rawName(seat: Int) = ctx.players.getOrNull(seat)?.displayName ?: ""
    fun seatName(seat: Int) = androidContext.seatLabel(rawName(seat))

    // ---- touch: one router, every finger bound to one pad ----
    var pressed by remember { mutableStateOf(setOf<String>()) }
    val router = remember(game) {
        val holder = arrayOfNulls<TsPointerRouter>(1)
        val r = TsPointerRouter(object : TsRouterListener {
            override fun onButton(region: TsRegion, down: Boolean) {
                if (down) {
                    val cmd = when (region.id.substringAfter(':')) { "L" -> LtTurn.LEFT; "R" -> LtTurn.RIGHT; else -> LtTurn.SWITCH }
                    if (game.turn(region.seat, cmd)) currentHaptics(HapticSignal.LIGHT_TICK)
                }
                val self = holder[0] ?: return
                pressed = self.regionsNow().filter { self.isPressed(it.id) }.map { it.id }.toSet()
            }

            override fun onVector(region: TsRegion, vx: Float, vy: Float) {}
        })
        holder[0] = r
        r
    }
    LaunchedEffect(info.paused, info.phase) {
        if (info.paused || info.phase == LtPhase.MATCH_OVER) { router.cancelAll(); pressed = emptySet() }
    }

    // ---- effects from the engine's event log ----
    var status by remember { mutableStateOf("") }
    var fx by remember { mutableStateOf(listOf<CrashFx>()) }
    var seen by remember { mutableIntStateOf(0) }
    var fxId by remember { mutableIntStateOf(0) }
    LaunchedEffect(info.eventSerial) {
        val events = game.eventsSince(seen)
        if (events.isEmpty()) return@LaunchedEffect
        seen = events.last().id
        for (ev in events) {
            val who = rawName(ev.seat.coerceAtLeast(0))
            when (ev.kind) {
                LtLog.COUNT -> { status = ev.value.toString(); playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
                LtLog.GO -> { status = androidContext.getString(R.string.tsp_go); playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.NORMAL_ACTION) }
                LtLog.CRASH -> {
                    val cause = LtCause.entries.getOrElse(ev.value) { LtCause.WALL }
                    status = when (cause) {
                        LtCause.EDGE -> androidContext.youAware(who, R.string.ltd_ev_edge, R.string.ltd_ev_edge_you)
                        LtCause.SELF -> androidContext.youAware(who, R.string.ltd_ev_self, R.string.ltd_ev_self_you)
                        LtCause.HEAD_ON -> androidContext.youAware(who, R.string.ltd_ev_head_on, R.string.ltd_ev_head_on_you)
                        LtCause.GAP_END -> androidContext.youAware(who, R.string.ltd_ev_gap_end, R.string.ltd_ev_gap_end_you)
                        LtCause.TIMEOUT -> androidContext.getString(R.string.ltd_ev_timeout)
                        LtCause.WALL -> androidContext.youAware(who, R.string.ltd_ev_wall, R.string.ltd_ev_wall_you)
                    }
                    fxId++
                    fx = (fx + CrashFx(ev.x, ev.y, ev.seat, if (reduced) 0L else frame.value)).takeLast(6)
                    playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.STRONG_ACTION)
                }
                LtLog.PICKUP -> {
                    status = if (ev.value == LtPower.GAP.ordinal) androidContext.youAware(who, R.string.ltd_ev_pick_gap, R.string.ltd_ev_pick_gap_you)
                    else androidContext.youAware(who, R.string.ltd_ev_pick_boost, R.string.ltd_ev_pick_boost_you)
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.NORMAL_ACTION)
                }
                LtLog.AUTO_AVOID -> status = androidContext.youAware(who, R.string.ltd_ev_saved, R.string.ltd_ev_saved_you)
                LtLog.ROUND_WIN -> {
                    status = androidContext.youAware(who, R.string.tsp_round_win, R.string.tsp_round_win_you)
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS)
                }
                LtLog.ROUND_DRAW -> { status = androidContext.getString(R.string.tsp_round_draw); playSfx(SfxKind.WHOOSH) }
                LtLog.MATCH_WIN -> {
                    status = androidContext.youAware(who, R.string.tsp_match_win, R.string.tsp_match_win_you)
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                    if (!reduced) burst.spawn(Offset(0.5f, 0.4f), 90, TsSeatColors + Color.White, speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
                }
                LtLog.MATCH_DRAW -> status = androidContext.getString(R.string.tsp_match_draw)
            }
        }
    }
    LaunchedEffect(fxId) {
        if (fx.isNotEmpty()) {
            kotlinx.coroutines.delay(if (reduced) 1200L else 900L)
            fx = fx.drop(1)
        }
    }

    val finishedMatches = game.sessionWins.value.values.sum() + game.sessionDraws.value
    val (_, openCoach) = rememberClCoach("light-trail", ready = true)
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = info.phase != LtPhase.MATCH_OVER,
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
            val thickPx = with(d) { (min(wPx, hPx) * 0.20f).coerceIn(80.dp.toPx(), 150.dp.toPx()) }
            val humans = List(seats) { !game.isBotSeat(it) }
            val layout = remember(wPx, hPx, headerPx, thickPx, humans, faceEach, padSet) {
                TsZoneLayout.compute(wPx, hPx, headerPx, humans, faceEach, padSet, thickPx, with(d) { 48.dp.toPx() }, with(d) { 6.dp.toPx() })
            }
            LaunchedEffect(layout) { router.setRegions(layout.regions()) }
            val boardDp = with(d) { layout.board.width.toDp() }
            val boardDesc = stringResource(R.string.ltd_arena_desc, seats, info.alive.count { it })

            // 1. The arena.
            Box(
                Modifier
                    .offset { IntOffset(layout.board.left.roundToInt(), layout.board.top.roundToInt()) }
                    .size(boardDp)
                    .semantics { contentDescription = boardDesc }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val b = game.board.value ?: return@Canvas
                    val t = if (reduced) 0f else frame.value / 1000f
                    drawArena(b, t, if (reduced) 0L else frame.value, reduced, fx)
                }
                Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                if (info.phase == LtPhase.COUNTDOWN) {
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

            // 2. Every player's zone, turned to face them.
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
                        val kind = p.id.substringAfter(':')
                        val name = seatName(seat)
                        when (kind) {
                            "L", "R" -> {
                                val left = kind == "L"
                                TsPad(
                                    p.local, color, p.id in pressed,
                                    label = stringResource(if (left) R.string.ltd_pad_left else R.string.ltd_pad_right),
                                    desc = stringResource(if (left) R.string.ltd_pad_desc_left else R.string.ltd_pad_desc_right, name),
                                    onActivate = { game.turn(seat, if (left) LtTurn.LEFT else LtTurn.RIGHT) },
                                    drawIcon = { c, s, col -> tsArrow(if (left) -1f else 1f, 0f, c, s, col) }
                                )
                            }
                            else -> {
                                val armedLeft = info.armed == LtTurn.LEFT
                                TsPad(
                                    p.local, color, p.id in pressed,
                                    label = stringResource(R.string.ltd_pad_one),
                                    desc = stringResource(if (armedLeft) R.string.ltd_pad_desc_one_left else R.string.ltd_pad_desc_one_right, name),
                                    onActivate = { game.turn(seat, LtTurn.SWITCH) },
                                    drawIcon = { c, s, col -> tsArrow(if (armedLeft) -1f else 1f, 0f, c, s, col) }
                                )
                            }
                        }
                    }
                }
            }

            // 3. The multi-touch layer (below the header, so its buttons still work).
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

            // 4. Header: help, round, pause, and every seat's chip.
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("light-trail", openCoach)
                    Text(
                        stringResource(R.string.tsp_hud_round, info.round, info.winsNeeded), color = colors.text,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                    if (compact) Box(Modifier.weight(3f)) { TsChipGrid(seats, seats, chipContent) }
                    OutlinedButton(
                        onClick = game::pause, enabled = info.phase != LtPhase.MATCH_OVER && !info.paused,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text), modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.tsp_pause)) }
                }
                if (!compact) TsChipGrid(seats, perRow, chipContent)
            }

            // 5. Overlays: the result and the pause screen.
            if (info.phase == LtPhase.MATCH_OVER) {
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

private fun DrawScope.drawArena(b: LtBoard, t: Float, nowMs: Long, reduced: Boolean, fx: List<CrashFx>) {
    val n = b.size
    val cell = size.width / n
    val pulse = if (reduced) 0.5f else 0.5f + 0.5f * sin(t * 3f)
    val corner = CornerRadius(cell * 0.9f)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF070B18), Color(0xFF111A33))), cornerRadius = corner)
    val grid = Color(0xFF5BD2FF).copy(alpha = 0.07f)
    for (i in 0..n) {
        val p = i * cell
        drawLine(grid, Offset(p, 0f), Offset(p, size.height), 1f)
        drawLine(grid, Offset(0f, p), Offset(size.width, p), 1f)
    }
    drawRoundRect(Color(0xFF5BD2FF).copy(alpha = 0.30f + 0.2f * pulse), style = Stroke(cell * 0.3f), cornerRadius = corner)

    // Walls: colour, and a pattern per seat (solid, dashed, dotted, hollow) so they read without colour.
    val cells = b.cells
    for (i in cells.indices) {
        val owner = cells[i]
        if (owner == 0) continue
        val seat = (owner - 1) % 4
        val c = TsSeatColors[seat]
        val cx = i % n; val cy = i / n
        val x0 = cx * cell; val y0 = cy * cell
        when (seat) {
            0 -> {
                drawRect(c.copy(alpha = 0.22f), Offset(x0 - cell * 0.2f, y0 - cell * 0.2f), Size(cell * 1.4f, cell * 1.4f))
                drawRect(c, Offset(x0 + cell * 0.16f, y0 + cell * 0.16f), Size(cell * 0.68f, cell * 0.68f))
            }
            1 -> {
                val on = (cx + cy) % 2 == 0
                drawRect(c.copy(alpha = if (on) 0.22f else 0.07f), Offset(x0 - cell * 0.2f, y0 - cell * 0.2f), Size(cell * 1.4f, cell * 1.4f))
                if (on) drawRect(c, Offset(x0 + cell * 0.1f, y0 + cell * 0.1f), Size(cell * 0.8f, cell * 0.8f))
            }
            2 -> {
                drawCircle(c.copy(alpha = 0.2f), cell * 0.6f, Offset(x0 + cell / 2, y0 + cell / 2))
                drawCircle(c, cell * 0.3f, Offset(x0 + cell / 2, y0 + cell / 2))
            }
            else -> {
                drawRect(c.copy(alpha = 0.16f), Offset(x0 - cell * 0.15f, y0 - cell * 0.15f), Size(cell * 1.3f, cell * 1.3f))
                drawRect(c, Offset(x0 + cell * 0.15f, y0 + cell * 0.15f), Size(cell * 0.7f, cell * 0.7f), style = Stroke(cell * 0.14f))
            }
        }
    }

    // Pickups: Gap is a dashed ring, Boost a star (a shape each, not just a colour).
    for (it in b.items) {
        val c = Offset((it.x + 0.5f) * cell, (it.y + 0.5f) * cell)
        val s = 0.85f + 0.15f * pulse
        if (it.kind == LtPower.GAP) {
            tsDashedCircle(c, cell * 0.5f * s, Color.White, cell * 0.12f)
            drawCircle(Color(0xFF9BE7FF).copy(alpha = 0.55f), cell * 0.2f, c)
        } else {
            tsStar(c, cell * 0.62f * s, cell * 0.28f * s, Color(0xFFFFD54F))
        }
    }

    // Heads: coloured disc with the seat's own shape, a chevron for the heading, rings for Gap and Boost.
    for (r in b.riders) {
        if (!r.alive) continue
        val seat = r.id % 4
        val c = TsSeatColors[seat]
        val center = Offset((r.x + 0.5f) * cell, (r.y + 0.5f) * cell)
        drawCircle(c.copy(alpha = 0.22f + 0.12f * pulse), cell * 1.1f, center)
        drawCircle(c, cell * 0.62f, center)
        drawSeatGlyph(seat, center, cell * 0.36f)
        val dir: LtDir = r.dir
        tsArrow(dir.dx.toFloat(), dir.dy.toFloat(), Offset(center.x + dir.dx * cell * 1.0f, center.y + dir.dy * cell * 1.0f), cell * 0.75f, c)
        if (r.gapLeft > 0) tsDashedCircle(center, cell * 0.95f, Color.White, cell * 0.12f)
        if (r.boostLeft > 0) drawCircle(Color.White.copy(alpha = 0.75f), cell * 0.85f, center, style = Stroke(cell * 0.09f))
    }

    // Crashes: an expanding ring and sparks (a plain cross under Reduced Motion).
    for (f in fx) {
        val c = TsSeatColors[f.seat.coerceAtLeast(0) % 4]
        val center = Offset((f.x + 0.5f) * cell, (f.y + 0.5f) * cell)
        if (reduced) {
            val s = cell * 0.9f
            drawLine(Color.White, Offset(center.x - s, center.y - s), Offset(center.x + s, center.y + s), cell * 0.22f)
            drawLine(Color.White, Offset(center.x - s, center.y + s), Offset(center.x + s, center.y - s), cell * 0.22f)
        } else {
            val p = ((nowMs - f.atMs).coerceAtLeast(0L) / 700f).coerceIn(0f, 1f)
            drawCircle(c.copy(alpha = 1f - p), cell * (0.8f + 4f * p), center, style = Stroke(cell * 0.3f * (1f - p) + 1f))
            for (k in 0 until 8) {
                val a = k * Math.PI / 4
                val dx = kotlin.math.cos(a).toFloat(); val dy = sin(a).toFloat()
                drawLine(
                    Color.White.copy(alpha = 1f - p),
                    Offset(center.x + dx * cell * (1f + 3f * p), center.y + dy * cell * (1f + 3f * p)),
                    Offset(center.x + dx * cell * (1.6f + 4f * p), center.y + dy * cell * (1.6f + 4f * p)), cell * 0.12f
                )
            }
        }
    }
}
