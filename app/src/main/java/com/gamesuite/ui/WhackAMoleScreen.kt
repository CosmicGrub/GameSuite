package com.gamesuite.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.partypack.ReactionWhackLayout
import com.gamesuite.games.partypack.ReactionWhackZones
import com.gamesuite.games.whackamole.Critter
import com.gamesuite.games.whackamole.WhackAMoleGame
import com.gamesuite.games.whackamole.WhackEngine
import com.gamesuite.games.whackamole.WhackKind
import com.gamesuite.games.whackamole.WhackPhase
import com.gamesuite.games.whackamole.WhackRules
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

private class WmColors(val bg: Color, val text: Color, val card: Color, val accent: Color, val gold: Color, val bad: Color)

private fun wmColors(dark: Boolean) = if (!dark) WmColors(
    bg = Color(0xFFF3ECE4), text = Color(0xFF2B2018), card = Color(0xFFD9E6C5), accent = Color(0xFF9C5638), gold = Color(0xFFD99A00), bad = Color(0xFFB3261E)
) else WmColors(
    bg = Color(0xFF16170F), text = Color(0xFFF1EBDD), card = Color(0xFF2A3320), accent = Color(0xFFF0B096), gold = Color(0xFFFFC83D), bad = Color(0xFFFF7A70)
)

private val ZoneHeaderH = 44.dp
private val ZoneInner = 8.dp
private val ZoneGapH = 6.dp
private val MinCell = 48.dp

private class WmPopup(val id: Int, val seat: Int, val hole: Int, val text: String, val good: Boolean)

private fun Context.wmName(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

private fun critterName(c: Context, critter: Critter?): String = c.getString(
    when (critter) {
        Critter.MOLE -> R.string.wm_critter_mole
        Critter.GOLD -> R.string.wm_critter_gold
        Critter.DECOY -> R.string.wm_critter_decoy
        null -> R.string.wm_critter_none
    }
)

/**
 * Whack-a-Mole Race on one shared screen: each player has a zone (a grid of holes) turned to face them, and all the
 * zones follow the same seeded critter list, so the race is fair. Fingers are tracked one by one (see
 * [RwTouchSurface]), so everybody whacks at the same time. Moxie the mole scores, Goldie the gopher (star on her cap)
 * is a bonus, spiky Prickle costs points; a streak multiplies. Relaxed removes penalties and slows everything; One-switch
 * replaces aiming with a sweeping cursor.
 *
 * Modes: Same screen and vs Computer only (no Nearby/Online in this version).
 */
@Composable
fun WhackAMoleScreen(
    sessionManager: GameSessionManager,
    game: WhackAMoleGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = wmColors(dark)
    val reduced = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val burst = rememberParticleBurst()
    val density = LocalDensity.current
    val version by game.version
    val frame = remember { mutableStateOf(0L) }

    var orient by remember { mutableStateOf(true) }
    var gridNote by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var popups by remember { mutableStateOf(listOf<WmPopup>()) }
    var popupId by remember { mutableIntStateOf(0) }
    var lastSerial by remember { mutableIntStateOf(0) }
    val centres = remember { mutableStateMapOf<Int, Offset>() }

    LaunchedEffect(game) {
        while (true) {
            androidx.compose.runtime.withFrameMillis { frame.value = it }
            game.tick()
        }
    }
    LaunchedEffect(popups) {
        if (popups.isNotEmpty()) {
            delay(gameSpeed.scale(700L))
            popups = popups.drop(1)
        }
    }

    val helpTitle = stringResource(R.string.wm_help_title)
    val helpText = stringResource(R.string.wm_help_text)
    val engine = game.engine
    val (_, openCoach) = rememberClCoach("whack-a-mole", ready = true)
    val finishedRaces = game.sessionWins.value.values.sum() + game.sessionDraws.value

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = engine?.phase != WhackPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRaces > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedRaces == 0,
        buttonFill = colors.bg, buttonContent = colors.text
    ) {
        val ctx0 = context
        val seats = (ctx0?.players?.size ?: 2).coerceIn(2, 4)
        Column(Modifier.fillMaxSize().background(colors.bg)) {
            // Top bar: coach, a timer bar (shape, not colour) and a polite status line.
            val players = game.takeIf { engine != null }?.players ?: ctx0?.players ?: emptyList()
            fun rawName(i: Int) = players.getOrNull(i)?.displayName ?: ""
            fun seatName(i: Int) = androidContext.seatLabel(rawName(i))
            // Derived states: they change once a second (or on a phase change), so the screen is not recomposed every frame.
            val nowState = remember(engine) { androidx.compose.runtime.derivedStateOf { frame.value; game.nowMs() } }
            val secsLeftState = remember(engine) { androidx.compose.runtime.derivedStateOf { frame.value; engine?.let { ceil(it.timeLeftMs(game.nowMs()) / 1000.0).toInt() } ?: 0 } }
            val countdownSecsState = remember(engine) {
                androidx.compose.runtime.derivedStateOf {
                    frame.value
                    if (engine != null && engine.phase == WhackPhase.COUNTDOWN) ceil(((engine.playStartAt - game.nowMs()) / 1000.0).coerceAtLeast(0.0)).toInt().coerceAtLeast(1) else 0
                }
            }
            val secsLeft = secsLeftState.value
            val status: String = run {
                val c = androidContext
                when {
                    engine == null -> ""
                    engine.phase == WhackPhase.COUNTDOWN -> c.getString(R.string.wm_status_ready) + if (gridNote) " " + c.getString(R.string.wm_grid_reduced) else ""
                    engine.phase == WhackPhase.OVER -> {
                        val w = engine.winner()
                        if (w >= 0) c.wmName(rawName(w), R.string.wm_over_title, R.string.wm_over_title_you) else c.getString(R.string.ccl_a_draw)
                    }
                    else -> {
                        // Announced every 15 seconds, so a screen reader is not chattering through the round.
                        val q = (secsLeft + 14) / 15 * 15
                        val top = engine.score.max()
                        val leaders = engine.score.indices.filter { engine.score[it] == top }
                        val lead = if (leaders.size == 1) c.getString(R.string.wm_status_leader, seatName(leaders[0]), top) else c.getString(R.string.wm_status_tied, top)
                        c.getString(R.string.wm_status_time, q, lead)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                ClCoachButton("whack-a-mole", openCoach)
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.wm_time_left, secsLeft / 60, secsLeft % 60), color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Canvas(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.card)) {
                            val frac = engine?.let { it.timeLeftMs(nowState.value).toFloat() / it.config.durationMs } ?: 1f
                            drawRect(colors.accent, size = androidx.compose.ui.geometry.Size(size.width * frac.coerceIn(0f, 1f), size.height))
                        }
                    }
                    ClStatusLine(status, colors.text, Modifier.fillMaxWidth(), emphasized = true)
                }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                val edge = minOf(maxWidth, maxHeight) >= 560.dp
                val layout: ReactionWhackLayout = remember(wPx, hPx, seats, orient, edge) { ReactionWhackZones.layout(seats, wPx, hPx, orient, edge) }
                val reservedPx = with(density) { (ZoneInner * 2 + ZoneHeaderH + ZoneGapH + 8.dp).toPx() }
                val minCellPx = with(density) { MinCell.toPx() }

                // Start once the layout is known, so the grid can be reduced to keep every hole at least 48dp.
                LaunchedEffect(context, layout) {
                    val ctx = context ?: return@LaunchedEffect
                    if (started) return@LaunchedEffect
                    started = true
                    val extras = sessionManager.launchOptions.extras
                    val relaxed = extras["relaxed"] == "true"
                    val requested = if (relaxed) 3 else (extras["grid"]?.toIntOrNull() ?: 3)
                    val fit = layout.zones.minOf { ReactionWhackZones.maxGridThatFits(it.contentWidth, it.contentHeight, requested, minCellPx, reservedPx) }
                    gridNote = fit < requested
                    game.grid = fit
                    game.timeSeconds = extras["time"]?.toIntOrNull() ?: WhackRules.DEFAULT_TIME_S
                    game.relaxed = relaxed
                    game.oneSwitch = extras["oneSwitch"] == "true"
                    orient = extras["orient"] != "false"
                    game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
                    game.init(ctx)
                    game.setOnMatchEnd { result ->
                        sessionManager.endActiveGame(result)
                        onMatchEnded()
                    }
                    if (!sessionManager.tryResume(game)) game.startMatch()
                }
                LaunchedEffect(layout) {
                    for (z in layout.zones) centres[z.seat] = Offset(((z.left + z.right) / 2f) / wPx, ((z.top + z.bottom) / 2f) / hPx)
                }

                val e = engine
                if (e != null) {
                    val grid = e.config.grid
                    val grids = remember(layout, grid) { mutableStateMapOf<Int, Rect>() }

                    // Sounds, haptics, popups and confetti from the engine's event log (human zones only make noise).
                    LaunchedEffect(version) {
                        var sounded = false
                        for (ev in e.eventsSince(lastSerial)) {
                            lastSerial = maxOf(lastSerial, ev.serial)
                            if (ev.seat < 0) {
                                if (e.phase == WhackPhase.OVER) {
                                    val w = e.winner()
                                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                                    if (!reduced && w >= 0) centres[w]?.let { burst.spawn(it, 90, listOf(colors.gold, Color.White, colors.accent, Color(0xFF56B4E9)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f) }
                                }
                                continue
                            }
                            val human = !players[ev.seat].isBot
                            if (ev.kind != WhackKind.MISS && ev.kind != WhackKind.IGNORED) {
                                popupId++
                                val text = if (ev.delta >= 0) androidContext.getString(R.string.wm_popup_plus, ev.delta) else androidContext.getString(R.string.wm_popup_minus, ev.delta)
                                popups = (popups + WmPopup(popupId, ev.seat, ev.hole, text, ev.delta >= 0)).takeLast(12)
                            }
                            if (!human || sounded) continue
                            sounded = true
                            when (ev.kind) {
                                WhackKind.HIT -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
                                WhackKind.BONUS -> {
                                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.STRONG_ACTION)
                                    if (!reduced) centres[ev.seat]?.let { burst.spawn(it, 24, listOf(colors.gold, Color.White), speedRange = 0.15f..0.5f, lifeRangeSeconds = 0.5f..0.9f, gravity = 0.8f) }
                                }
                                WhackKind.DECOY -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                                WhackKind.MISS -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
                                WhackKind.IGNORED -> {}
                            }
                        }
                    }

                    RwTouchSurface(
                        layout = layout,
                        onPress = { seat, cx, cy, age ->
                            if (seat !in 0 until seats || players[seat].isBot) return@RwTouchSurface
                            val g = grids[seat] ?: return@RwTouchSurface
                            if (e.config.oneSwitch) {
                                game.whack(seat, -1, age)
                                return@RwTouchSurface
                            }
                            val cell = g.width / grid
                            val col = ((cx - g.left) / cell).toInt()
                            val row = ((cy - g.top) / cell).toInt()
                            if (cx < g.left || cy < g.top || col !in 0 until grid || row !in 0 until grid) return@RwTouchSurface
                            game.whack(seat, row * grid + col, age)
                        }
                    ) {
                        for (zone in layout.zones) {
                            RwZoneBox(zone) {
                                val seat = zone.seat
                                WmZoneContent(
                                    seat = seat, name = seatName(seat), bot = players[seat].isBot, game = game, engine = e,
                                    version = version, frame = frame, colors = colors, reduced = reduced, popups = popups.filter { it.seat == seat },
                                    onGrid = { r -> grids[seat] = r }, countdownSecs = countdownSecsState,
                                    onSemanticWhack = { hole -> game.whack(seat, hole, 0L) }, interactive = !players[seat].isBot
                                )
                            }
                        }
                        layout.hub?.let { h ->
                            val hubW = with(density) { (h[2] - h[0]).toDp() }
                            val hubH = with(density) { (h[3] - h[1]).toDp() }
                            Box(Modifier.offset { IntOffset(h[0].roundToInt(), h[1].roundToInt()) }.size(hubW, hubH), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                                    for (i in 0 until seats) {
                                        Text(
                                            stringResource(R.string.wm_score_line, seatName(i), e.score[i]), color = colors.text,
                                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                        if (e.phase == WhackPhase.OVER) {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
                                val w = e.winner()
                                val title = if (w >= 0) androidContext.wmName(rawName(w), R.string.wm_over_title, R.string.wm_over_title_you) else stringResource(R.string.ccl_a_draw)
                                val session = players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)} ${game.sessionWins.value[it.playerId] ?: 0}" }
                                ClFinishedPanel(
                                    title = title, subtitle = null,
                                    lines = (0 until seats).map { i ->
                                        stringResource(R.string.wm_over_line, seatName(i), e.score[i], e.bestCombo[i], e.bonuses[i], e.decoyHits[i])
                                    } + stringResource(R.string.ccl_session, session),
                                    canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One zone in its own upright frame: header (who, score, streak) over the grid of holes. */
@Composable
private fun WmZoneContent(
    seat: Int,
    name: String,
    bot: Boolean,
    game: WhackAMoleGame,
    engine: WhackEngine,
    version: Int,
    frame: State<Long>,
    colors: WmColors,
    reduced: Boolean,
    popups: List<WmPopup>,
    onGrid: (Rect) -> Unit,
    countdownSecs: State<Int>,
    onSemanticWhack: (Int) -> Unit,
    interactive: Boolean
) {
    @Suppress("UNUSED_VARIABLE") val subscribe = version
    val ctx = LocalContext.current
    val seatColor = RwSeatColors[seat.coerceIn(0, 3)]
    val grid = engine.config.grid
    val holes = engine.config.holes
    val oneSwitch = engine.config.oneSwitch
    val score = engine.score[seat]
    val combo = engine.combo[seat]
    val cpuTag = if (bot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
    val scanTag = if (oneSwitch) " " + stringResource(R.string.wm_scan_desc) else ""
    val zoneDesc = stringResource(R.string.wm_zone_desc, name, score, combo) + cpuTag + scanTag
    val pressLabel = stringResource(R.string.wm_zone_whack)
    Column(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .border(BorderStroke(3.dp, seatColor), RoundedCornerShape(22.dp))
            .padding(ZoneInner)
            .semantics(mergeDescendants = false) {
                contentDescription = zoneDesc
                if (interactive && oneSwitch) { role = Role.Button; onClick(label = pressLabel) { onSemanticWhack(-1); true } }
            }
    ) {
        Row(Modifier.fillMaxWidth().height(ZoneHeaderH), verticalAlignment = Alignment.CenterVertically) {
            ClSeatBadge(seatColor, seat, sizeDp = 26)
            Spacer(Modifier.width(6.dp))
            Text(name + if (bot) " · " + stringResource(R.string.ccl_cpu_tag) else "", color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (combo >= 2) {
                Text(stringResource(R.string.wm_combo, combo, WhackRules.comboMultiplier(combo)), color = colors.text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            Text(score.toString(), color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        Spacer(Modifier.height(ZoneGapH))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val d = LocalDensity.current
            val cellPx = minOf(with(d) { maxWidth.toPx() }, with(d) { maxHeight.toPx() }) / grid
            val cellDp = with(d) { cellPx.toDp() }
            val side = cellDp * grid
            Box(
                Modifier.size(side).onGloballyPositioned { c ->
                    val p = c.positionInParent()
                    onGrid(Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height))
                }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val rel = engine.relative(game.nowMs()).also { frame.value }
                    val cursor = if (oneSwitch && engine.phase == WhackPhase.PLAY) engine.scanHoleAt(rel) else -1
                    for (h in 0 until holes) {
                        val col = h % grid
                        val row = h / grid
                        val c = Offset((col + 0.5f) * cellPx, (row + 0.5f) * cellPx)
                        val v = if (engine.phase == WhackPhase.PLAY) engine.holeView(seat, h, rel) else null
                        wmDrawHole(c, cellPx, v, cursor == h)
                    }
                }
                // Accessible holes: each is its own labelled 48dp+ button for switch access and TalkBack.
                for (h in 0 until holes) {
                    val col = h % grid
                    val row = h / grid
                    Box(
                        Modifier
                            .offset(cellDp * col, cellDp * row)
                            .size(cellDp)
                            .semantics {
                                val up = engine.holeView(seat, h, engine.relative(game.nowMs()))?.takeIf { it.hitAgeMs < 0 }?.critter
                                contentDescription = ctx.getString(R.string.wm_hole_desc, h + 1, holes, critterName(ctx, up))
                                if (interactive && !oneSwitch) { role = Role.Button; onClick(label = pressLabel) { onSemanticWhack(h); true } }
                            }
                    )
                }
                // Score popups float over their hole.
                for (p in popups) {
                    val col = p.hole % grid
                    val row = p.hole / grid
                    Text(
                        p.text, color = if (p.good) Color(0xFFFFE066) else Color(0xFFFF8A80), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
                        modifier = Modifier.offset(cellDp * col + cellDp * 0.18f, cellDp * row + cellDp * 0.02f - (if (reduced) 0.dp else 6.dp))
                    )
                }
            }
            if (engine.phase == WhackPhase.COUNTDOWN) {
                val secs = countdownSecs.value.coerceAtLeast(1)
                Box(Modifier.size(side).background(colors.bg.copy(alpha = 0.55f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.wm_countdown, secs), color = colors.text, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}
