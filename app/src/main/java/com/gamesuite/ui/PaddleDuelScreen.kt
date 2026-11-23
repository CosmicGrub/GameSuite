package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.gamesuite.games.partypack.PaddleDuelEngine
import com.gamesuite.games.partypack.PaddleDuelGame
import com.gamesuite.games.partypack.PdEventKind
import com.gamesuite.games.partypack.PdFormat
import com.gamesuite.games.partypack.PdPhase
import com.gamesuite.games.partypack.PdPointerRouter
import com.gamesuite.games.partypack.PdPowerKind
import com.gamesuite.games.partypack.PdRules
import com.gamesuite.games.partypack.PdViewTransform
import com.gamesuite.games.partypack.PpEdge
import com.gamesuite.games.partypack.PpSeatLayout
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private class PdPal(
    val bg: Color, val text: Color, val card: Color, val courtTop: Color, val courtBottom: Color,
    val line: Color, val wall: Color, val gold: Color, val ball: Color
)

private fun pdPal(dark: Boolean) = if (dark) PdPal(
    bg = Color(0xFF14161C), text = Color(0xFFEDEFF5), card = Color(0xFF23262F), courtTop = Color(0xFF14233A), courtBottom = Color(0xFF0C1626),
    line = Color(0x44FFFFFF), wall = Color(0xFF5C6B86), gold = Color(0xFFFFC83D), ball = Color(0xFFFFF6DA)
) else PdPal(
    bg = Color(0xFFF2F1F6), text = Color(0xFF1E2028), card = Color(0xFFE2E0EA), courtTop = Color(0xFF1B3556), courtBottom = Color(0xFF112440),
    line = Color(0x44FFFFFF), wall = Color(0xFF6C7C98), gold = Color(0xFFFFC83D), ball = Color(0xFFFFF6DA)
)

private class PdRing(val x: Double, val y: Double, val born: Long, val color: Color, val big: Boolean)

private fun nowMs(): Long = System.nanoTime() / 1_000_000L

private fun Context.pdDirWord(dx: Int, dy: Int): String = getString(
    when {
        dx < 0 -> R.string.pdl_dir_left
        dx > 0 -> R.string.pdl_dir_right
        dy < 0 -> R.string.pdl_dir_up
        else -> R.string.pdl_dir_down
    }
)

/**
 * Paddle Duel: Pong with spin for two to four people on ONE screen. Each player slides their own paddle along
 * their own edge with a thumb; every finger is tracked separately (see PdPointerRouter), so all of them can play
 * at once. Every seat also has arrow buttons (hold to move; a click nudges, for TalkBack and switch access) and
 * an optional one-switch mode. Seats are told apart by colour, shape and name. Same screen only: no Nearby or
 * Online in this version.
 */
@Composable
fun PaddleDuelScreen(
    sessionManager: GameSessionManager,
    game: PaddleDuelGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    rememberAmbientMusic(profile = MusicProfiles.UNO, enabled = com.gamesuite.settings.LocalMusicEnabled.current && CardSounds.soundEnabled)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pal = pdPal(dark)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val burst = rememberParticleBurst()
    val measurer = rememberTextMeasurer()

    var facing by remember { mutableStateOf(true) }
    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.formatId = extras["format"] ?: PdFormat.DEFAULT.id
        game.powerups = extras["powerups"] == "true"
        game.relaxed = extras["relaxed"] == "true"
        game.oneSwitch = extras["controls"] == "switch"
        facing = extras["facing"] != "false"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    val serial by game.matchSerial
    val engine = game.engine
    if (context == null || engine == null) return
    val n = engine.n
    val names = remember(context, n) { List(n) { androidContext.seatLabel(context!!.players[it].displayName) } }

    // ---- state the frame loop writes -------------------------------------------------------
    var status by remember(serial) { mutableStateOf("") }
    var scoreTick by remember(serial) { mutableIntStateOf(0) }
    var celebrated by remember(serial) { mutableStateOf(false) }
    val rings = remember(serial) { ArrayList<PdRing>() }
    val trail = remember(serial) { ArrayDeque<Offset>() }
    val shake = remember { Animatable(0f) }
    val hitAt = remember(serial) { LongArray(4) }
    val over by remember(serial) { derivedStateOf { game.frame.intValue; engine.over } }
    val transformRef = remember { arrayOfNulls<PdViewTransform>(1) }

    fun scoreSummary(): String = (0 until n).joinToString(", ") { i ->
        names[i] + " " + (if (engine.config.format.points) engine.score[i] else engine.lives[i])
    }

    LaunchedEffect(serial) {
        while (true) {
            androidx.compose.runtime.withFrameMillis { }
            game.timeScale = min(1.0, 1.0 / gameSpeed.delayScale.toDouble())
            game.tick()
            val t = transformRef[0]
            for (ev in game.drainEvents()) {
                fun norm(): Offset? = t?.let { tt ->
                    Offset((tt.toScreenX(ev.x, ev.y) / tt.viewW).toFloat(), (tt.toScreenY(ev.x, ev.y) / tt.viewH).toFloat())
                }
                val seatColor = PpSeatColors[(if (ev.kind == PdEventKind.GOAL) ev.a else ev.seat).coerceIn(0, 3)]
                when (ev.kind) {
                    PdEventKind.SERVE_TICK -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
                    PdEventKind.LAUNCH -> { playSfx(SfxKind.WHOOSH); status = androidContext.getString(R.string.pdl_ann_launch) }
                    PdEventKind.PADDLE_HIT -> {
                        playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.LIGHT_TICK)
                        if (ev.seat in 0..3) hitAt[ev.seat] = nowMs()
                        if (!reduced) rings += PdRing(ev.x, ev.y, nowMs(), PpSeatColors[ev.seat.coerceIn(0, 3)], false)
                    }
                    PdEventKind.WALL -> playSfx(SfxKind.LIGHT_TICK)
                    PdEventKind.GOAL -> {
                        playSfx(SfxKind.WHOOSH); haptics(HapticSignal.STRONG_ACTION)
                        scoreTick++
                        val pts = engine.config.format.points
                        status = if (pts && ev.a >= 0) androidContext.getString(R.string.pdl_ann_point, names[ev.a], scoreSummary())
                        else androidContext.getString(R.string.pdl_ann_life, names[ev.seat], scoreSummary())
                        if (!reduced) {
                            rings += PdRing(ev.x, ev.y, nowMs(), seatColor, true)
                            norm()?.let { burst.spawn(it, 22, listOf(seatColor, Color.White, pal.gold)) }
                            shake.snapTo(1f); shake.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
                        }
                    }
                    PdEventKind.ELIMINATED -> {
                        playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.ESCALATING); scoreTick++
                        status = androidContext.getString(R.string.pdl_ann_out, names[ev.seat])
                    }
                    PdEventKind.PICKUP_SPAWN -> playSfx(SfxKind.LIGHT_TICK)
                    PdEventKind.PICKUP_TAKEN -> {
                        playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.NORMAL_ACTION)
                        status = androidContext.getString(
                            when (PdPowerKind.entries[ev.a]) {
                                PdPowerKind.WIDE -> R.string.pdl_ann_wide
                                PdPowerKind.SLOW -> R.string.pdl_ann_slow
                                PdPowerKind.MULTI -> R.string.pdl_ann_multi
                            }, names[ev.seat]
                        )
                    }
                    PdEventKind.STALE -> status = androidContext.getString(R.string.pdl_ann_stale)
                    PdEventKind.WIN -> {
                        scoreTick++
                        game.noteFinished()
                        status = androidContext.getString(R.string.pdl_ann_win, names[ev.seat])
                        playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                        if (!reduced && !celebrated) {
                            celebrated = true
                            burst.spawn(Offset(0.5f, 0.4f), 110, listOf(PpSeatColors[ev.seat.coerceIn(0, 3)], Color.White, pal.gold), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
                        }
                    }
                }
            }
            // ring and trail upkeep (cheap, bounded)
            val now = nowMs()
            rings.removeAll { now - it.born > 700 }
            if (!reduced) {
                val b = engine.balls.firstOrNull()
                if (b != null && engine.phase == PdPhase.PLAY) {
                    trail.addLast(Offset(b.x.toFloat(), b.y.toFloat()))
                    while (trail.size > 14) trail.removeFirst()
                } else trail.clear()
            }
        }
    }

    val finishedMatches = game.sessionWins.value.values.sum()
    val helpText = stringResource(R.string.pdl_help_text)
    GameChrome(
        helpTitle = stringResource(R.string.pdl_help_title), helpText = helpText,
        matchInProgress = !over,
        onLeave = game::leaveSession,
        onAbort = { if (finishedMatches > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedMatches == 0,
        buttonFill = pal.bg, buttonContent = pal.text
    ) {
        val (_, openCoach) = rememberClCoach("paddle-duel", ready = true)
        val density = LocalDensity.current
        val fontScale = density.fontScale.coerceIn(1f, 1.6f)
        val band = 64.dp * fontScale
        Column(Modifier.fillMaxSize().background(pal.bg)) {
            Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                ClCoachButton("paddle-duel", openCoach)
                val format = engine.config.format
                ClStatusLine(
                    status.ifEmpty {
                        if (format.points) stringResource(R.string.pdl_goal_points, format.target) else stringResource(R.string.pdl_goal_lives, format.target)
                    },
                    pal.text, Modifier.weight(1f).padding(horizontal = 4.dp), emphasized = true
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val rotated = n == 2 && maxWidth > maxHeight * 1.1f
                val seatEdges = PpSeatLayout.edges(n)
                // each seat's band sits on the SCREEN edge where its edge is drawn
                val probe = PdViewTransform(n, rotated, 100.0, 100.0)
                val screenEdges = seatEdges.map { probe.screenEdge(it) }
                val padL = if (PpEdge.LEFT in screenEdges) band else 0.dp
                val padR = if (PpEdge.RIGHT in screenEdges) band else 0.dp
                val padT = if (PpEdge.TOP in screenEdges) band else 0.dp
                val padB = if (PpEdge.BOTTOM in screenEdges) band else 0.dp
                val arenaWpx = with(density) { (maxWidth - padL - padR).toPx().toDouble() }.coerceAtLeast(1.0)
                val arenaHpx = with(density) { (maxHeight - padT - padB).toPx().toDouble() }.coerceAtLeast(1.0)
                val marginPx = with(density) { 6.dp.toPx().toDouble() }
                val transform = PdViewTransform(n, rotated, arenaWpx, arenaHpx, marginPx) { engine.alive[it] }
                transformRef[0] = transform
                val router = remember(serial, rotated, n) { PdPointerRouter { x, y -> transformRef[0]?.zoneAt(x, y) ?: -1 } }
                val arenaDesc = run { scoreTick; stringResource(R.string.pdl_arena_desc, scoreSummary()) }

                Box(Modifier.fillMaxSize().padding(start = padL, end = padR, top = padT, bottom = padB)) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .semantics { contentDescription = arenaDesc }
                            .pointerInput(serial, rotated, n) {
                                try {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Main)
                                            val t = transformRef[0] ?: continue
                                            for (ch in ev.changes) {
                                                val id = ch.id.value
                                                val x = ch.position.x.toDouble(); val y = ch.position.y.toDouble()
                                                if (ch.pressed) {
                                                    val seat = if (ch.previousPressed) router.move(id, x, y) else router.down(id, x, y)
                                                    if (seat != null) { game.setTarget(seat, t.alongFor(seat, x, y)); ch.consume() }
                                                } else if (ch.previousPressed) {
                                                    router.up(id)?.let { game.setTarget(it, null) }
                                                    ch.consume()
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    router.cancelAll().forEach { game.setTarget(it, null) }
                                }
                            }
                    ) {
                        game.frame.intValue // redraw every frame
                        drawPaddleCourt(engine, transform, pal, reduced, measurer, trail, rings, hitAt, shake.value, facing)
                        drawBurst(burst)
                    }
                }

                // seat bands: tag, arrow buttons or the one switch
                for (s in 0 until n) {
                    val se = screenEdges[s]
                    val dead = !run { scoreTick; engine.alive[s] }
                    val quarter = if (facing) PpSeatLayout.quarterTurns(se) else 0
                    val bot = game.isBotSeat(s)
                    val oneSwitch = engine.config.seats[s].oneSwitch
                    val value = run {
                        scoreTick
                        if (engine.config.format.points) engine.score[s] else engine.lives[s]
                    }
                    val points = engine.config.format.points
                    val bandMod = when (se) {
                        PpEdge.BOTTOM, PpEdge.TOP -> Modifier.fillMaxWidth().height(band)
                        else -> Modifier.fillMaxHeight().widthIn(min = band, max = band)
                    }
                    Box(Modifier.align(se.alignment()).then(bandMod).ppRotateLayout(if (se.isHorizontal && facing) quarter else if (facing) quarter else 0)) {
                        val inRow = facing || se.isHorizontal
                        PdBand(
                            seat = s, name = names[s], value = value, points = points, dead = dead, bot = bot, oneSwitch = oneSwitch,
                            inRow = inRow, quarter = if (facing) quarter else 0, alongPlus = transform.alongPlus(s), aim = run { game.frame.intValue; engine.aimOf(s) },
                            pal = pal, context = androidContext,
                            onHold = { dir -> game.setHold(s, dir) }, onNudge = { dir -> game.nudge(s, dir) }, onSwitch = { game.pressSwitch(s) }
                        )
                    }
                }

                if (over) {
                    val winner = engine.winner
                    val title = when {
                        n >= 2 && context!!.players.count { !it.isBot } == 1 && !context!!.players[0].isBot ->
                            if (winner == 0) stringResource(R.string.ccl_you_won) else stringResource(R.string.ccl_you_lost)
                        else -> stringResource(R.string.pdl_result_winner, names.getOrElse(winner) { "" })
                    }
                    val session = context!!.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)} ${game.sessionWins.value[it.playerId] ?: 0}" }
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        ClFinishedPanel(
                            title = title, subtitle = null,
                            lines = listOf(stringResource(R.string.pdl_result_scores, scoreSummary()), stringResource(R.string.ccl_session, session)),
                            canPlayAgain = true,
                            onPlayAgain = { game.playAgain() },
                            onLeave = { game.leaveSession() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdBand(
    seat: Int, name: String, value: Int, points: Boolean, dead: Boolean, bot: Boolean, oneSwitch: Boolean,
    inRow: Boolean, quarter: Int, alongPlus: Pair<Int, Int>, aim: Int, pal: PdPal, context: Context,
    onHold: (Int) -> Unit, onNudge: (Int) -> Unit, onSwitch: () -> Unit
) {
    val color = PpSeatColors[seat.coerceIn(0, 3)]
    val first = if (inRow) ppRotateVec(-1, 0, quarter) else (0 to -1)
    val second = if (inRow) ppRotateVec(1, 0, quarter) else (0 to 1)
    fun sign(v: Pair<Int, Int>) = if (v.first * alongPlus.first + v.second * alongPlus.second >= 0) 1 else -1
    val tagDesc = if (points) stringResource(R.string.pdl_tag_points, name, value) else stringResource(R.string.pdl_tag_lives, name, value)
    val outDesc = stringResource(R.string.pdl_tag_out, name)
    val items: @Composable () -> Unit = {
        if (!bot && !oneSwitch && !dead) PdMoveButton(first, sign(first), name, color, context, onHold, onNudge, quarter)
        Row(
            Modifier.padding(horizontal = 6.dp).semantics { contentDescription = if (dead) outDesc else tagDesc },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ClSeatBadge(color, ppSeatShape(seat), 22)
            Text(name, color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 96.dp))
            Text(if (dead) "×" else if (points) "$value" else "♥ $value", color = pal.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        if (oneSwitch && !dead) {
            val dir = if (aim == 0) null else if (aim > 0) alongPlus else (-alongPlus.first to -alongPlus.second)
            val word = if (dir == null) stringResource(R.string.pdl_aim_straight) else context.pdDirWord(dir.first, dir.second)
            PpHoldButton(
                label = stringResource(R.string.pdl_btn_switch, name, word), onHold = {}, onActivate = onSwitch,
                background = color, size = 56.dp, modifier = Modifier.widthIn(min = 56.dp), activateOnTap = true
            ) {
                Canvas(Modifier.size(40.dp)) {
                    if (dir == null) drawCircle(Color.White, size.minDimension * 0.18f) else ppRotateVec(dir.first, dir.second, (4 - quarter % 4) % 4).let { g -> drawPpArrow(g.first, g.second, Color.White) }
                }
            }
        } else if (!bot && !dead) PdMoveButton(second, sign(second), name, color, context, onHold, onNudge, quarter)
    }
    if (inRow) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { items() }
    else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { items() }
}

@Composable
private fun PdMoveButton(dir: Pair<Int, Int>, sign: Int, name: String, color: Color, context: Context, onHold: (Int) -> Unit, onNudge: (Int) -> Unit, quarter: Int = 0) {
    // The whole band is turned by [quarter] quarter turns to face its player, so the arrow is drawn counter-turned: it
    // then points along the SCREEN direction [dir] the paddle really moves in.
    val glyphDir = ppRotateVec(dir.first, dir.second, (4 - quarter % 4) % 4)
    val label = stringResource(R.string.pdl_btn_move, name, context.pdDirWord(dir.first, dir.second))
    PpHoldButton(
        label = label, onHold = { down -> onHold(if (down) sign else 0) }, onActivate = { onNudge(sign) },
        background = color.copy(alpha = 0.9f), size = 56.dp
    ) { Canvas(Modifier.size(40.dp)) { drawPpArrow(glyphDir.first, glyphDir.second, Color.White) } }
}

// ---- drawing ------------------------------------------------------------------------------

private fun DrawScope.drawPaddleCourt(
    e: PaddleDuelEngine, t: PdViewTransform, pal: PdPal, reduced: Boolean, measurer: TextMeasurer,
    trail: ArrayDeque<Offset>, rings: List<PdRing>, hitAt: LongArray, shake: Float, facing: Boolean
) {
    fun sx(lx: Double, ly: Double) = t.toScreenX(lx, ly).toFloat()
    fun sy(lx: Double, ly: Double) = t.toScreenY(lx, ly).toFloat()
    fun rect(r: DoubleArray): Rect {
        val x1 = sx(r[0], r[1]); val y1 = sy(r[0], r[1]); val x2 = sx(r[2], r[3]); val y2 = sy(r[2], r[3])
        return Rect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }
    val k = t.scale.toFloat()
    val court = rect(doubleArrayOf(0.0, 0.0, e.width, e.height))
    val off = Offset((shake * 6f * kotlin.math.sin(shake * 40f)), (shake * 4f * kotlin.math.cos(shake * 33f)))
    translate(off.x, off.y) {
        // court
        drawRoundRect(Brush.verticalGradient(listOf(pal.courtTop, pal.courtBottom), court.top, court.bottom), court.topLeft, court.size, CornerRadius(18f))
        drawRoundRect(pal.line, court.topLeft, court.size, CornerRadius(18f), style = Stroke(3f))
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))
        if (e.n == 2) {
            drawLine(pal.line, Offset(sx(0.0, e.height / 2), sy(0.0, e.height / 2)), Offset(sx(e.width, e.height / 2), sy(e.width, e.height / 2)), 3f, pathEffect = dash)
        } else {
            drawCircle(pal.line, k * 0.16f, court.center, style = Stroke(3f, pathEffect = dash))
        }
        drawCircle(pal.line, k * 0.03f, court.center, style = Stroke(2f))
        // walls where no live seat stands
        val wallW = max(6f, k * 0.014f)
        for (edge in PpEdge.entries) {
            val idx = e.edges.indexOf(edge)
            if (idx >= 0 && e.alive[idx]) continue
            val r = when (edge) {
                PpEdge.BOTTOM -> doubleArrayOf(0.0, e.height - 0.014, e.width, e.height)
                PpEdge.TOP -> doubleArrayOf(0.0, 0.0, e.width, 0.014)
                PpEdge.LEFT -> doubleArrayOf(0.0, 0.0, 0.014, e.height)
                PpEdge.RIGHT -> doubleArrayOf(e.width - 0.014, 0.0, e.width, e.height)
            }
            val rr = rect(r)
            drawRect(pal.wall, rr.topLeft, Size(max(rr.width, wallW), max(rr.height, wallW)))
        }
        // corner blocks
        if (e.n > 2) {
            val c = PdRules.corner(e.n)
            for ((cx, cy) in listOf(0.0 to 0.0, e.width - c to 0.0, 0.0 to e.height - c, e.width - c to e.height - c)) {
                val rr = rect(doubleArrayOf(cx, cy, cx + c, cy + c))
                drawRoundRect(pal.wall, rr.topLeft, rr.size, CornerRadius(8f))
                drawRoundRect(Color(0x33FFFFFF), rr.topLeft, rr.size, CornerRadius(8f), style = Stroke(2f))
            }
        }
        // big faint scores toward each player
        for (s in 0 until e.n) {
            val edge = t.screenEdge(e.edges[s])
            val text = if (!e.alive[s]) "×" else if (e.config.format.points) "${e.score[s]}" else "${e.lives[s]}"
            val style = TextStyle(color = Color.White.copy(alpha = 0.16f), fontSize = (k * 0.2f / density * 1f).coerceIn(40f, 140f).sp, fontWeight = FontWeight.Black)
            val layout = measurer.measure(text, style)
            val inset = 0.2
            val lx: Double; val ly: Double
            when (e.edges[s]) {
                PpEdge.BOTTOM -> { lx = e.width * 0.5; ly = e.height - inset }
                PpEdge.TOP -> { lx = e.width * 0.5; ly = inset }
                PpEdge.LEFT -> { lx = inset; ly = e.height * 0.5 }
                PpEdge.RIGHT -> { lx = e.width - inset; ly = e.height * 0.5 }
            }
            val center = Offset(sx(lx, ly), sy(lx, ly))
            val deg = if (facing) PpSeatLayout.facingDegrees(edge) else 0f
            rotate(deg, center) { drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)) }
        }
        // paddles
        val now = nowMs()
        for (s in 0 until e.n) {
            if (!e.alive[s]) continue
            val r = rect(e.paddleRect(s))
            val color = PpSeatColors[s]
            val glow = ((now - hitAt[s]) < 180 && !reduced)
            if (e.paddleLength(s) > PdRules.PAD_LEN * 1.45) drawRoundRect(color.copy(alpha = 0.3f), Offset(r.left - 6, r.top - 6), Size(r.width + 12, r.height + 12), CornerRadius(14f))
            if (glow) drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(r.left - 4, r.top - 4), Size(r.width + 8, r.height + 8), CornerRadius(14f))
            val horiz = e.edges[s].isHorizontal
            drawRoundRect(
                if (horiz) Brush.verticalGradient(listOf(clShade(color, 0.35f), color, clShade(color, -0.3f)), r.top, r.bottom)
                else Brush.horizontalGradient(listOf(clShade(color, 0.35f), color, clShade(color, -0.3f)), r.left, r.right),
                r.topLeft, r.size, CornerRadius(min(r.width, r.height) / 2f)
            )
            drawRoundRect(clShade(color, -0.55f), r.topLeft, r.size, CornerRadius(min(r.width, r.height) / 2f), style = Stroke(2f))
            drawSeatGlyph(ppSeatShape(s), r.center, min(r.width, r.height) * 0.36f)
        }
        // pickup
        e.pickup?.let { p ->
            val c = Offset(sx(p.x, p.y), sy(p.x, p.y))
            val pulse = if (reduced) 1f else 1f + 0.08f * kotlin.math.sin(now / 160f)
            val rr = PdRules.PICKUP_R.toFloat() * k * pulse
            drawCircle(pal.gold.copy(alpha = 0.25f), rr * 1.6f, c)
            drawCircle(pal.gold, rr, c)
            drawCircle(Color(0xFF5A4300), rr, c, style = Stroke(3f))
            val col = Color(0xFF3A2C00)
            when (p.kind) {
                PdPowerKind.WIDE -> { drawLine(col, Offset(c.x - rr * 0.6f, c.y), Offset(c.x + rr * 0.6f, c.y), 5f); drawLine(col, Offset(c.x - rr * 0.6f, c.y - rr * 0.3f), Offset(c.x - rr * 0.6f, c.y + rr * 0.3f), 5f); drawLine(col, Offset(c.x + rr * 0.6f, c.y - rr * 0.3f), Offset(c.x + rr * 0.6f, c.y + rr * 0.3f), 5f) }
                PdPowerKind.SLOW -> { drawCircle(col, rr * 0.55f, c, style = Stroke(4f)); drawLine(col, c, Offset(c.x, c.y - rr * 0.4f), 4f); drawLine(col, c, Offset(c.x + rr * 0.3f, c.y), 4f) }
                PdPowerKind.MULTI -> { for (i in -1..1) drawCircle(col, rr * 0.2f, Offset(c.x + i * rr * 0.5f, c.y + abs(i) * rr * 0.25f)) }
            }
        }
        // rings
        for (r in rings) {
            val age = ((now - r.born) / 700f).coerceIn(0f, 1f)
            drawCircle(r.color.copy(alpha = 1f - age), k * (if (r.big) 0.18f else 0.08f) * age + 8f, Offset(sx(r.x, r.y), sy(r.x, r.y)), style = Stroke(4f))
        }
        // trail and balls
        if (!reduced) {
            trail.forEachIndexed { i, p ->
                drawCircle(pal.ball.copy(alpha = 0.04f + 0.14f * i / max(1, trail.size)), PdRules.BALL_R.toFloat() * k * (0.4f + 0.5f * i / max(1, trail.size)), Offset(sx(p.x.toDouble(), p.y.toDouble()), sy(p.x.toDouble(), p.y.toDouble())))
            }
        }
        for (b in e.balls) {
            val c = Offset(sx(b.x, b.y), sy(b.x, b.y))
            val br = PdRules.BALL_R.toFloat() * k
            drawCircle(Color(0x55000000), br, Offset(c.x + br * 0.2f, c.y + br * 0.3f))
            drawCircle(Brush.radialGradient(listOf(Color.White, pal.ball, Color(0xFFE0B857)), Offset(c.x - br * 0.3f, c.y - br * 0.35f), br * 1.5f), br, c)
            if (b.extra) drawCircle(pal.gold, br * 1.35f, c, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        }
        // serve countdown
        if (e.phase == PdPhase.SERVE) {
            val total = if (e.config.relaxed) PdRules.RELAXED_SERVE_S else PdRules.SERVE_S
            val num = ceil(e.serveLeft / total * 3.0).toInt().coerceIn(1, 3)
            val style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = (k * 0.22f / density).coerceIn(48f, 160f).sp, fontWeight = FontWeight.Black)
            val l = measurer.measure("$num", style)
            drawText(l, topLeft = Offset(court.center.x - l.size.width / 2f, court.center.y - k * 0.2f - l.size.height / 2f))
        }
    }
}
