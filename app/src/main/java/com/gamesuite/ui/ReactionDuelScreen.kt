package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.Dp
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
import com.gamesuite.games.reactionduel.DisplayKind
import com.gamesuite.games.reactionduel.DuelEventKind
import com.gamesuite.games.reactionduel.DuelPadGrid
import com.gamesuite.games.reactionduel.DuelPhase
import com.gamesuite.games.reactionduel.DuelSymbol
import com.gamesuite.games.reactionduel.DuelVariant
import com.gamesuite.games.reactionduel.PressResult
import com.gamesuite.games.reactionduel.ReactionDuelEngine
import com.gamesuite.games.reactionduel.ReactionDuelGame
import com.gamesuite.games.reactionduel.RoundEnd
import com.gamesuite.games.reactionduel.SeatStatus
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private class RdColors(val bg: Color, val text: Color, val card: Color, val accent: Color, val gold: Color, val good: Color, val bad: Color, val wait: Color, val neutral: Color)

private fun rdColors(dark: Boolean) = if (!dark) RdColors(
    bg = Color(0xFFF6F1E7), text = Color(0xFF2A2318), card = Color(0xFFE9DFCB), accent = Color(0xFF12507A),
    gold = Color(0xFFD99A00), good = Color(0xFF1B7F4F), bad = Color(0xFFB3261E), wait = Color(0xFFB87700), neutral = Color(0xFF7A7365)
) else RdColors(
    bg = Color(0xFF15161B), text = Color(0xFFF1EBDD), card = Color(0xFF24262E), accent = Color(0xFF86C8F5),
    gold = Color(0xFFFFC83D), good = Color(0xFF4CD08C), bad = Color(0xFFFF7A70), wait = Color(0xFFE0A030), neutral = Color(0xFF8C8778)
)

private enum class Tone { NEUTRAL, WAIT, GO, OUT, DONE, WIN }

private val ZoneHeader = 40.dp
private val ZoneFooter = 44.dp
private val ZonePad = 8.dp
private val ZoneGap = 6.dp

private fun Context.rdName(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

private fun shapeNameRes(shape: Int): Int = when (shape) {
    0 -> R.string.rd_shape_circle
    1 -> R.string.rd_shape_square
    2 -> R.string.rd_shape_triangle
    3 -> R.string.rd_shape_diamond
    else -> R.string.rd_shape_star
}

private fun colourNameRes(colour: Int): Int = when (colour) {
    0 -> R.string.rd_colour_orange
    1 -> R.string.rd_colour_blue
    2 -> R.string.rd_colour_yellow
    else -> R.string.rd_colour_green
}

private fun Context.symbolDesc(s: DuelSymbol): String = getString(R.string.rd_symbol_desc, getString(colourNameRes(s.colour)), getString(shapeNameRes(s.shape)))

/**
 * Reaction Duel on one shared screen. Every player has a zone turned to face them; all the zones follow the same signal.
 * Touches are tracked per finger (see [RwTouchSurface]) so all of them can press at once. The go-signal is a single
 * steady change (a green pad with a play glyph and the word GO plus a sound and a haptic tick), never a flash, and
 * with Reduced Motion on even the one soft halo is dropped. Shapes and words, never colour alone, carry every state.
 *
 * Modes: Same screen and vs Computer only (no Nearby/Online in this version).
 */
@Composable
fun ReactionDuelScreen(
    sessionManager: GameSessionManager,
    game: ReactionDuelGame,
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
    val colors = rdColors(dark)
    val reduced = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val burst = rememberParticleBurst()
    val density = LocalDensity.current
    val version by game.version

    var orient by remember { mutableStateOf(true) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.variant = DuelVariant.fromId(extras["variant"])
        game.bestOf = extras["best"]?.toIntOrNull() ?: 3
        game.relaxed = extras["relaxed"] == "true"
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

    // The clock: one engine tick per frame (the engine is told the time; it never sleeps).
    val frame = remember { mutableStateOf(0L) }
    LaunchedEffect(game) {
        while (true) {
            androidx.compose.runtime.withFrameMillis { frame.value = it }
            game.tick()
        }
    }

    val helpTitle = stringResource(R.string.rd_help_title)
    val helpText = stringResource(R.string.rd_help_text)
    val engine = game.engine
    val activeCtx = context
    if (engine == null || activeCtx == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
            onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
            buttonFill = colors.bg, buttonContent = colors.text
        ) { Box(Modifier.fillMaxSize().background(colors.bg)) }
        return
    }

    val players = game.players
    val seats = engine.config.seats
    val hotSeat = players.none { it.isBot }
    fun rawName(i: Int) = players.getOrNull(i)?.displayName ?: ""
    fun seatName(i: Int) = androidContext.seatLabel(rawName(i))

    // ---- derived per-frame display (recomposes only when it changes) ----
    val display by remember(engine) { derivedStateOf { frame.value; engine.displayAt(game.nowMs()) } }
    val scanPad by remember(engine) { derivedStateOf { frame.value; if (display.kind == DisplayKind.YOUR_TURN) engine.scanPadAt(game.nowMs()) else -1 } }
    val goPulse = remember { Animatable(1f) }
    val wobble = remember { List(4) { Animatable(0f) } }
    var foulNote by remember { mutableStateOf<String?>(null) }
    var lastSerial by remember { mutableIntStateOf(0) }
    var lastLitSlot by remember { mutableIntStateOf(-1) }

    // One tone per lit echo pad (a different tone for each pad), once per slot.
    LaunchedEffect(engine) {
        while (true) {
            androidx.compose.runtime.withFrameMillis { }
            val slot = engine.echoSlotAt(game.nowMs())
            if (slot != lastLitSlot) {
                lastLitSlot = slot
                if (slot >= 0) {
                    playSfx(
                        when (engine.sequence[slot]) { 0 -> SfxKind.LIGHT_TICK; 1 -> SfxKind.SOLID_THUNK; 2 -> SfxKind.WHOOSH; else -> SfxKind.SUCCESS_CHIME }
                    )
                }
            }
        }
    }

    // Stage geometry (set by the stage below): where each seat's centre is, as fractions, for the confetti.
    val centres = remember { mutableStateMapOf<Int, Offset>() }

    LaunchedEffect(version) {
        for (ev in engine.eventsSince(lastSerial)) {
            lastSerial = maxOf(lastSerial, ev.serial)
            when (ev.kind) {
                DuelEventKind.COUNTDOWN -> { foulNote = null; playSfx(SfxKind.LIGHT_TICK) }
                DuelEventKind.GO -> {
                    playSfx(if (engine.config.variant == DuelVariant.ECHO) SfxKind.SOLID_THUNK else SfxKind.SUCCESS_CHIME)
                    haptics(HapticSignal.STRONG_ACTION)
                    if (!reduced) { goPulse.snapTo(0f); goPulse.animateTo(1f, tween(gameSpeed.scale(500L).toInt())) }
                }
                DuelEventKind.FALSE_START, DuelEventKind.WRONG -> {
                    val who = rawName(ev.seat)
                    foulNote = if (ev.kind == DuelEventKind.FALSE_START) androidContext.rdName(who, R.string.rd_ann_false, R.string.rd_ann_false_you)
                    else androidContext.rdName(who, R.string.rd_ann_wrong, R.string.rd_ann_wrong_you)
                    playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
                    if (!reduced && ev.seat in 0..3) {
                        wobble[ev.seat].snapTo(1f)
                        wobble[ev.seat].animateTo(0f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium))
                    }
                }
                DuelEventKind.ROUND_WIN, DuelEventKind.DEFAULT_WIN -> {
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS)
                    if (!reduced) centres[ev.seat]?.let { burst.spawn(it, 36, listOf(colors.gold, Color.White, RwSeatColors[ev.seat.coerceIn(0, 3)]), speedRange = 0.2f..0.7f, lifeRangeSeconds = 0.7f..1.3f, gravity = 0.8f) }
                }
                DuelEventKind.DEAD_HEAT, DuelEventKind.TOO_SLOW -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
                DuelEventKind.MATCH_OVER -> {
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                    if (!reduced) centres[ev.seat]?.let { burst.spawn(it, 90, listOf(colors.gold, Color.White, colors.accent, Color(0xFF56B4E9)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f) }
                }
            }
        }
    }

    // ---- words ----
    val outcome = engine.lastOutcome
    val status: String = run {
        val c = androidContext
        when (engine.phase) {
            DuelPhase.COUNTDOWN -> c.getString(R.string.rd_status_ready)
            DuelPhase.LIVE -> foulNote?.let { it + " " + liveHint(c, engine) } ?: liveHint(c, engine)
            DuelPhase.RESULT, DuelPhase.OVER -> {
                val base = if (outcome == null) "" else when (outcome.end) {
                    RoundEnd.WIN -> c.rdName(rawName(outcome.winner), R.string.rd_ann_win, R.string.rd_ann_win_you, outcome.reactionMs[outcome.winner])
                    RoundEnd.DEFAULT_WIN -> c.rdName(rawName(outcome.winner), R.string.rd_ann_default, R.string.rd_ann_default_you)
                    RoundEnd.DEAD_HEAT -> c.getString(R.string.rd_ann_dead_heat)
                    RoundEnd.TOO_SLOW -> c.getString(R.string.rd_ann_too_slow)
                }
                if (engine.phase == DuelPhase.OVER) base + " " + (if (engine.matchWinner >= 0) c.rdName(rawName(engine.matchWinner), R.string.rd_ann_match, R.string.rd_ann_match_you) else c.getString(R.string.rd_ann_match_draw)) else base
            }
        }
    }

    val (_, openCoach) = rememberClCoach("reaction-duel", ready = true)
    val finishedDuels = game.sessionWins.value.values.sum() + game.sessionDraws.value
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = engine.phase != DuelPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedDuels > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedDuels == 0,
        buttonFill = colors.bg, buttonContent = colors.text
    ) {
        Column(Modifier.fillMaxSize().background(colors.bg)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                ClCoachButton("reaction-duel", openCoach)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.rd_round_banner, engine.attempts, engine.targetWins), color = colors.text,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    ClStatusLine(status, colors.text, Modifier.fillMaxWidth(), emphasized = true)
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                val edge = minOf(maxWidth, maxHeight) >= 560.dp
                val layout: ReactionWhackLayout = remember(wPx, hPx, seats, orient, edge) { ReactionWhackZones.layout(seats, wPx, hPx, orient, edge) }
                val areas = remember(layout) { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }
                LaunchedEffect(layout) {
                    for (z in layout.zones) centres[z.seat] = Offset(((z.left + z.right) / 2f) / wPx, ((z.top + z.bottom) / 2f) / hPx)
                }

                RwTouchSurface(
                    layout = layout,
                    onPress = { seat, cx, cy, age ->
                        if (seat !in 0 until seats || players[seat].isBot) return@RwTouchSurface
                        val a = areas[seat]
                        val pad = if (a != null && engine.config.variant == DuelVariant.ECHO) DuelPadGrid.padAt(cx - a.left, cy - a.top, a.width, a.height) else -1
                        val r = game.press(seat, age, pad)
                        if (r != PressResult.IGNORED && r != PressResult.EARLY_IGNORED) haptics(HapticSignal.NORMAL_ACTION)
                        if (r == PressResult.PROGRESS) playSfx(SfxKind.LIGHT_TICK)
                    }
                ) {
                    for (zone in layout.zones) {
                        RwZoneBox(zone) {
                            val seat = zone.seat
                            RdZoneContent(
                                seat = seat, name = seatName(seat), bot = players[seat].isBot, game = game, engine = engine,
                                version = version, display = display, scanPad = scanPad, goPulse = goPulse.value,
                                wobble = wobble.getOrNull(seat)?.value ?: 0f, colors = colors, reduced = reduced,
                                frame = frame,
                                onArea = { r -> areas[seat] = r },
                                onSemanticPress = { pad -> game.press(seat, 0L, pad) },
                                interactive = !players[seat].isBot
                            )
                        }
                    }
                    layout.hub?.let { h ->
                        val hubW = with(density) { (h[2] - h[0]).toDp() }
                        val hubH = with(density) { (h[3] - h[1]).toDp() }
                        Box(Modifier.offset { IntOffset(h[0].roundToInt(), h[1].roundToInt()) }.size(hubW, hubH), contentAlignment = Alignment.Center) {
                            RdScoreboard(engine, ::seatName, colors)
                        }
                    }
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                    if (engine.phase == DuelPhase.OVER) {
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
                            val w = engine.matchWinner
                            val title = when {
                                w < 0 -> stringResource(R.string.ccl_a_draw)
                                else -> androidContext.rdName(rawName(w), R.string.rd_over_title, R.string.rd_over_title_you)
                            }
                            val session = players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)} ${game.sessionWins.value[it.playerId] ?: 0}" }
                            ClFinishedPanel(
                                title = title, subtitle = null,
                                lines = listOf(
                                    stringResource(R.string.rd_over_summary, players.indices.joinToString(" · ") { "${seatName(it)} ${engine.wins[it]}" }),
                                    stringResource(R.string.ccl_session, session)
                                ),
                                canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun liveHint(c: Context, e: ReactionDuelEngine): String = when (e.config.variant) {
    DuelVariant.CLASSIC -> c.getString(if (e.signalLive) R.string.rd_status_go else R.string.rd_status_wait_classic)
    DuelVariant.COLOUR -> if (e.signalLive) c.getString(R.string.rd_status_go) else c.getString(R.string.rd_status_wait_colour, e.target?.let { c.getString(shapeNameRes(it.shape)) } ?: "")
    DuelVariant.ECHO -> c.getString(if (e.signalLive) R.string.rd_status_your_turn else R.string.rd_status_watch)
}

@Composable
private fun RdScoreboard(engine: ReactionDuelEngine, seatName: (Int) -> String, colors: RdColors) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
        for (i in 0 until engine.config.seats) {
            Text(
                stringResource(R.string.rd_score_line, seatName(i), engine.wins[i], engine.targetWins), color = colors.text,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One zone's content in its own upright frame: header (who, round wins, best time), the pad area, and a one-line hint. */
@Composable
private fun RdZoneContent(
    seat: Int,
    name: String,
    bot: Boolean,
    game: ReactionDuelGame,
    engine: ReactionDuelEngine,
    version: Int,
    display: com.gamesuite.games.reactionduel.DuelDisplay,
    scanPad: Int,
    goPulse: Float,
    wobble: Float,
    colors: RdColors,
    reduced: Boolean,
    frame: State<Long>,
    onArea: (androidx.compose.ui.geometry.Rect) -> Unit,
    onSemanticPress: (Int) -> Unit,
    interactive: Boolean
) {
    @Suppress("UNUSED_VARIABLE") val subscribe = version
    val ctx = LocalContext.current
    val variant = engine.config.variant
    val oneSwitch = engine.config.oneSwitch
    val status = engine.seatStatus[seat]
    val phase = engine.phase
    val outcome = engine.lastOutcome
    val isWinner = (phase == DuelPhase.RESULT || phase == DuelPhase.OVER) && outcome != null && outcome.winner == seat &&
        (outcome.end == RoundEnd.WIN || outcome.end == RoundEnd.DEFAULT_WIN)
    val echoGrid = variant == DuelVariant.ECHO && phase == DuelPhase.LIVE && status == SeatStatus.ARMED
    val seatColor = RwSeatColors[seat.coerceIn(0, 3)]

    // What the pad shows.
    val tone: Tone
    val label: String
    var sub: String? = null
    val ms = engine.reactionMs[seat]
    when {
        phase == DuelPhase.COUNTDOWN -> { tone = Tone.NEUTRAL; label = stringResource(R.string.rd_pad_get_ready) }
        isWinner -> { tone = Tone.WIN; label = if (ms >= 0) stringResource(R.string.rd_pad_done_ms, ms) else stringResource(R.string.rd_pad_fastest); sub = stringResource(R.string.rd_pad_fastest) }
        status == SeatStatus.LOCKED -> { tone = Tone.OUT; label = stringResource(if (variant == DuelVariant.ECHO) R.string.rd_pad_out_wrong else R.string.rd_pad_out_early) }
        status == SeatStatus.DONE -> { tone = Tone.DONE; label = if (ms >= 0) stringResource(R.string.rd_pad_done_ms, ms) else stringResource(R.string.rd_pad_done) }
        phase != DuelPhase.LIVE -> { tone = Tone.NEUTRAL; label = stringResource(R.string.rd_pad_dash) }
        else -> when (display.kind) {
            DisplayKind.GO -> { tone = Tone.GO; label = stringResource(R.string.rd_pad_go) }
            DisplayKind.TARGET -> { tone = Tone.GO; label = stringResource(R.string.rd_pad_go); sub = display.symbol?.let { ctx.symbolDesc(it) } }
            DisplayKind.DECOY -> { tone = Tone.NEUTRAL; label = display.symbol?.let { stringResource(shapeNameRes(it.shape)) } ?: ""; sub = stringResource(R.string.rd_pad_not_it) }
            DisplayKind.YOUR_TURN, DisplayKind.ECHO_LIT, DisplayKind.ECHO_REST -> { tone = Tone.NEUTRAL; label = "" }
            else -> { tone = Tone.WAIT; label = stringResource(R.string.rd_pad_wait) }
        }
    }
    val padBase = when (tone) {
        Tone.NEUTRAL -> colors.neutral
        Tone.WAIT -> colors.wait
        Tone.GO -> colors.good
        Tone.OUT -> colors.bad
        Tone.DONE -> colors.accent
        Tone.WIN -> colors.gold
    }
    val pressedLook = tone == Tone.DONE || tone == Tone.WIN
    val glowStrength = if (tone == Tone.GO && !reduced) 1f - goPulse else if (tone == Tone.WIN) 0.7f else 0f

    val zoneDesc = run {
        val state = when {
            phase == DuelPhase.COUNTDOWN -> stringResource(R.string.rd_pad_get_ready)
            tone == Tone.GO -> stringResource(R.string.rd_status_go)
            else -> label.ifBlank { stringResource(R.string.rd_status_watch) }
        }
        stringResource(R.string.rd_zone_desc, name, engine.wins[seat], engine.targetWins, state) + if (bot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
    }
    val pressLabel = stringResource(R.string.rd_zone_press)

    val shakePx = with(LocalDensity.current) { (wobble * 14.dp.toPx()).roundToInt() }
    Column(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .offset { IntOffset(shakePx, 0) }
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .border(BorderStroke(if (isWinner) 5.dp else 3.dp, if (isWinner) colors.gold else seatColor), RoundedCornerShape(22.dp))
            .padding(ZonePad)
            .semantics(mergeDescendants = false) {
                contentDescription = zoneDesc
                role = Role.Button
                if (interactive && !(variant == DuelVariant.ECHO && !oneSwitch)) onClick(label = pressLabel) { onSemanticPress(-1); true }
            }
    ) {
        // Header
        Row(Modifier.fillMaxWidth().height(ZoneHeader), verticalAlignment = Alignment.CenterVertically) {
            ClSeatBadge(seatColor, seat, sizeDp = 26)
            Spacer(Modifier.width(6.dp))
            Text(name + if (bot) " · " + stringResource(R.string.ccl_cpu_tag) else "", color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Canvas(Modifier.height(16.dp).width((18 * engine.targetWins).dp)) {
                val d = size.height
                for (i in 0 until engine.targetWins) {
                    val c = Offset(d / 2 + i * (d + 2.dp.toPx()), size.height / 2)
                    if (i < engine.wins[seat]) rdDrawSymbol(4, colors.gold, c, d * 0.5f) else rdDrawSymbolOutline(4, colors.neutral, c, d * 0.4f)
                }
            }
            val best = engine.bestMs[seat]
            if (best >= 0) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.rd_best_ms, best), color = colors.text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Spacer(Modifier.height(ZoneGap))
        // The pad area
        Box(
            Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { c ->
                val p = c.positionInParent()
                onArea(androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height))
            }
        ) {
            val d = LocalDensity.current
            if (echoGrid) {
                RdEchoPads(
                    seat = seat, engine = engine, display = display, scanPad = scanPad, goPulse = goPulse, colors = colors, reduced = reduced,
                    interactive = interactive, onSemanticPress = onSemanticPress, oneSwitch = oneSwitch
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val inset = 2.dp.toPx()
                    val tl = Offset(inset, inset)
                    val sz = Size(size.width - 2 * inset, size.height - 2 * inset - size.minDimension * 0.05f)
                    rdDrawPad(tl, sz, padBase, pressed = pressedLook, glow = glowStrength)
                    val c = Offset(tl.x + sz.width / 2f, tl.y + sz.height * 0.42f)
                    val r = sz.minDimension * 0.2f
                    val onPad = Color.White
                    when {
                        tone == Tone.GO && display.symbol != null && variant == DuelVariant.COLOUR ->
                            rdDrawSymbol(display.symbol.shape, RdSymbolColours[display.symbol.colour], c, r * 1.15f)
                        tone == Tone.GO -> rdDrawPlay(c, r, onPad)
                        tone == Tone.WAIT -> rdDrawHourglass(c, r, Color(0xFF1B1405))
                        tone == Tone.OUT -> rdDrawCross(c, r, onPad)
                        tone == Tone.DONE -> rdDrawCheck(c, r, Color(0xFF1B1405).takeIf { dark(colors) } ?: onPad)
                        tone == Tone.WIN -> rdDrawSymbol(4, Color.White, c, r * 1.15f)
                        display.kind == DisplayKind.DECOY && display.symbol != null && phase == DuelPhase.LIVE ->
                            rdDrawSymbol(display.symbol.shape, RdSymbolColours[display.symbol.colour], c, r * 1.15f)
                        tone == Tone.NEUTRAL && phase == DuelPhase.COUNTDOWN -> rdDrawHourglass(c, r * 0.9f, onPad)
                    }
                }
                val onText = padTextColor(tone)
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 8.dp, end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (label.isNotBlank()) Text(label, color = onText, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, textAlign = TextAlign.Center, maxLines = 2)
                    sub?.let { Text(it, color = onText, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2) }
                }
            }
        }
        Spacer(Modifier.height(ZoneGap))
        // Footer: the target reminder in colour match, otherwise a one-line hint.
        Row(Modifier.fillMaxWidth().height(ZoneFooter), verticalAlignment = Alignment.CenterVertically) {
            val tgt = engine.target
            if (variant == DuelVariant.COLOUR && tgt != null) {
                Canvas(Modifier.size(34.dp)) { rdDrawSymbol(tgt.shape, RdSymbolColours[tgt.colour], Offset(size.width / 2, size.height / 2), size.minDimension * 0.4f) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rd_target_label, ctx.symbolDesc(tgt)), color = colors.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.weight(1f))
            } else {
                val hint = when {
                    variant == DuelVariant.ECHO && engine.sequence.isNotEmpty() && phase == DuelPhase.LIVE && status == SeatStatus.ARMED && engine.signalLive ->
                        stringResource(R.string.rd_pad_progress, engine.echoProgress[seat], engine.sequence.size)
                    oneSwitch && variant == DuelVariant.ECHO -> stringResource(R.string.rd_hint_one_switch)
                    else -> ""
                }
                Text(hint, color = colors.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun dark(c: RdColors): Boolean = c.bg.luminance() < 0.5f

private fun padTextColor(t: Tone): Color = when (t) {
    Tone.WAIT, Tone.WIN -> Color(0xFF1B1405)
    else -> Color.White
}

/** The four echo pads: dim until lit (during the pattern) and bright, pressable and each with its own shape and name once it is your turn. */
@Composable
private fun RdEchoPads(
    seat: Int,
    engine: ReactionDuelEngine,
    display: com.gamesuite.games.reactionduel.DuelDisplay,
    scanPad: Int,
    goPulse: Float,
    colors: RdColors,
    reduced: Boolean,
    interactive: Boolean,
    onSemanticPress: (Int) -> Unit,
    oneSwitch: Boolean
) {
    val ctx = LocalContext.current
    val yourTurn = display.kind == DisplayKind.YOUR_TURN
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val w = with(d) { maxWidth.toPx() }
        val h = with(d) { maxHeight.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until DuelSymbol.ECHO_PADS) {
                val r = DuelPadGrid.rect(i, w, h)
                val lit = display.kind == DisplayKind.ECHO_LIT && display.pad == i
                val base = RdSymbolColours[i]
                val shown = if (yourTurn || lit) base else rdShade(base, -0.55f)
                val tl = Offset(r[0], r[1])
                val sz = Size(r[2] - r[0], r[3] - r[1])
                rdDrawPad(tl, sz, shown, pressed = false, glow = if (lit) 1f else if (yourTurn && !reduced) 1f - goPulse else 0f, corner = 0.22f)
                val c = Offset(tl.x + sz.width / 2f, tl.y + sz.height * 0.45f)
                rdDrawSymbol(i, if (yourTurn || lit) Color.White else Color(0x99FFFFFF), c, sz.minDimension * 0.24f)
                if (oneSwitch && yourTurn && scanPad == i) rdDrawCursor(tl, sz, Color.White)
            }
        }
        for (i in 0 until DuelSymbol.ECHO_PADS) {
            val r = DuelPadGrid.rect(i, w, h)
            val pw = with(d) { (r[2] - r[0]).toDp() }
            val ph = with(d) { (r[3] - r[1]).toDp() }
            val name = stringResource(shapeNameRes(i))
            Box(
                Modifier
                    .offset { IntOffset(r[0].roundToInt(), r[1].roundToInt()) }
                    .size(pw, ph)
                    .semantics {
                        contentDescription = name
                        role = Role.Button
                        if (interactive && !oneSwitch) onClick(label = name) { onSemanticPress(i); true }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(name, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp), maxLines = 1)
            }
        }
    }
}
