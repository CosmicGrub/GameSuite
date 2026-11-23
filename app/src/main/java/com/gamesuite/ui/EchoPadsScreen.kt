package com.gamesuite.ui

import android.content.Context
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.echopads.EchoCues
import com.gamesuite.games.echopads.EchoEventKind
import com.gamesuite.games.echopads.EchoMode
import com.gamesuite.games.echopads.EchoOutcome
import com.gamesuite.games.echopads.EchoPadGrid
import com.gamesuite.games.echopads.EchoPadTones
import com.gamesuite.games.echopads.EchoPads
import com.gamesuite.games.echopads.EchoPadsBest
import com.gamesuite.games.echopads.EchoPadsEngine
import com.gamesuite.games.echopads.EchoPadsGame
import com.gamesuite.games.echopads.EchoPadsStatsStore
import com.gamesuite.games.echopads.EchoPhase
import com.gamesuite.games.echopads.EchoSpeed
import com.gamesuite.games.echopads.TapResult
import com.gamesuite.games.partypack.ReactionWhackZones
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class EpColors(val bg: Color, val text: Color, val card: Color, val accent: Color, val neutral: Color)

private fun epColors(dark: Boolean) = if (!dark) EpColors(
    bg = Color(0xFFF6F3FB), text = Color(0xFF211F2B), card = Color(0xFFE8E4F3), accent = Color(0xFF5D4DB8), neutral = Color(0xFF7A7690)
) else EpColors(
    bg = Color(0xFF15151C), text = Color(0xFFEDEBF7), card = Color(0xFF24232F), accent = Color(0xFFB3A8F5), neutral = Color(0xFF8C88A0)
)

/** What an unlit pad fades toward. */
private val EpDim = Color(0xFF1B1A26)

private fun padNameRes(pad: Int): Int = when (pad) {
    EchoPads.CIRCLE -> R.string.echo_pads_pad_circle
    EchoPads.TRIANGLE -> R.string.echo_pads_pad_triangle
    EchoPads.SQUARE -> R.string.echo_pads_pad_square
    else -> R.string.echo_pads_pad_star
}

/** Hub choice of "Turn halves" etc. are read from [com.gamesuite.core.LaunchOptions.extras]; see EchoPadsHubSpecs. */
private fun statusText(c: Context, e: EchoPadsEngine, name: (Int) -> String, multi: Boolean, duel: Boolean): String {
    val who = name(e.currentPlayer)
    return when (e.phase) {
        EchoPhase.READY -> when {
            e.awaitingReady -> c.getString(R.string.echo_pads_status_handoff, who)
            multi -> c.getString(R.string.echo_pads_status_ready_name, who)
            else -> c.getString(R.string.echo_pads_status_ready)
        }
        EchoPhase.SHOWING -> if (multi) c.getString(R.string.echo_pads_status_watch_name, who) else c.getString(R.string.echo_pads_status_watch)
        EchoPhase.WAITING -> if (multi) c.getString(R.string.echo_pads_status_copy_name, who) else c.getString(R.string.echo_pads_status_copy)
        EchoPhase.CHECKING -> when (e.outcome) {
            EchoOutcome.OK -> if (multi) c.getString(R.string.echo_pads_status_ok_name, who) else c.getString(R.string.echo_pads_status_ok)
            EchoOutcome.RETRY -> c.getString(R.string.echo_pads_status_retry)
            EchoOutcome.MISS -> when {
                !multi -> c.getString(R.string.echo_pads_status_miss)
                duel -> c.getString(R.string.echo_pads_status_miss_plain, who)
                else -> c.getString(R.string.echo_pads_status_miss_out, who)
            }
            null -> ""
        }
        EchoPhase.OVER -> when {
            !multi -> c.getString(R.string.echo_pads_over_solo_title)
            e.isTie -> c.getString(R.string.echo_pads_over_tie, e.winners.joinToString(", ") { name(it) })
            else -> c.getString(R.string.echo_pads_over_win, name(e.winners.first()))
        }
    }
}

/**
 * Echo Pads: watch four shaped, coloured, pitched pads light up, then tap them back in order. Solo and Pass and play
 * share one board; the Head to head duel ([duel]) gives each player a half of the screen turned to face them.
 * Shapes and notes, never colour alone, tell the pads apart; with Reduced Motion the pads only change brightness;
 * with Sound off (a Hub option) the game plays by light and shape. Taps are accepted only while copying.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EchoPadsScreen(
    sessionManager: GameSessionManager,
    game: EchoPadsGame,
    @Suppress("UNUSED_PARAMETER") settingsViewModel: SettingsViewModel,
    duel: Boolean,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = epColors(dark)
    val reduced = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val density = LocalDensity.current
    val version by game.version
    val store = remember(androidContext) { EchoPadsStatsStore(androidContext.applicationContext) }
    val bests by remember(store) { store.bests }.collectAsState(initial = emptyMap())

    var soundOn by remember { mutableStateOf(true) }
    var orient by remember { mutableStateOf(true) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.mode = if (duel) EchoMode.DUEL else EchoMode.PASS_AND_PLAY
        game.speed = EchoSpeed.fromId(extras["speed"])
        game.forgiving = extras["forgiving"] == "true"
        game.delayScale = gameSpeed.delayScale
        soundOn = extras["sound"] != "false"
        orient = extras["orient"] != "false"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // The clock: one engine tick per frame (the engine is told the time; it never sleeps).
    // It only runs while something moves: the pattern playing, a lead-in or feedback pause, or a tap's flash (the
    // short settle window after each version change). Copying, hand-off and the finished panel need no frames.
    val frame = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { EchoPadTones.warmUp() }
    LaunchedEffect(game, version) {
        val settleUntil = game.nowMs() + 300L
        while (true) {
            androidx.compose.runtime.withFrameMillis { frame.value = it }
            game.tick()
            val e = game.engine ?: break
            val busy = when (e.phase) {
                EchoPhase.SHOWING, EchoPhase.CHECKING -> true
                EchoPhase.READY -> !e.awaitingReady
                else -> false
            }
            if (!busy && game.nowMs() > settleUntil) break
        }
    }

    val helpTitle = stringResource(R.string.echo_pads_help_title)
    val helpText = stringResource(R.string.echo_pads_help_text)
    val engine = game.engine
    val activeCtx = context
    if (engine == null || activeCtx == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
            onLeave = game::abortMatch, onAbort = { game.abortMatch() },
            buttonFill = colors.bg, buttonContent = colors.text
        ) { Box(Modifier.fillMaxSize().background(colors.bg)) }
        return
    }

    val players = game.players
    val mode = engine.config.mode
    val solo = mode == EchoMode.SOLO
    val multi = !solo
    fun seatName(i: Int) = androidContext.seatLabel(players.getOrNull(i)?.displayName ?: "")
    fun tone(pad: Int) { if (soundOn) EchoPadTones.play(pad) }

    // Taps flash the pad the finger landed on, per seat (key = seat * 4 + pad), for a moment.
    val flash = remember(engine) { mutableStateMapOf<Int, Long>() }
    val lit by remember(engine) { derivedStateOf { frame.value; engine.litPad(game.nowMs()) } }
    var lastSerial by remember(engine) { mutableIntStateOf(0) }

    val recorded = remember(engine) { mutableStateOf(false) }
    val newBest = remember(engine) { mutableStateOf(false) }

    LaunchedEffect(engine, version) {
        for (ev in engine.eventsSince(lastSerial)) {
            lastSerial = max(lastSerial, ev.serial)
            when (EchoCues.soundFor(ev.kind, multi)) {
                EchoCues.Sound.TONE_PAD -> { tone(ev.pad); haptics(HapticSignal.LIGHT_TICK) }
                EchoCues.Sound.SUCCESS -> { if (soundOn) playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
                // A free try is a gentle low thunk, never the miss buzz: listening alone must tell "again" from "out".
                EchoCues.Sound.SOFT_RETRY -> { if (soundOn) playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
                EchoCues.Sound.MISS_BUZZ -> { if (soundOn) playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                EchoCues.Sound.WIN -> { if (soundOn) playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION) }
                EchoCues.Sound.NONE -> {}
            }
        }
        if (solo && engine.phase == EchoPhase.OVER && !recorded.value) {
            recorded.value = true
            val c = engine.completedBy(0)
            if (c > 0) {
                // NonCancellable: the next version bump, Play Again or leaving the screen cancels this effect, and
                // the score (already marked recorded) must still be written. On a real failure allow a retry.
                try {
                    newBest.value = withContext(NonCancellable) {
                        store.recordScore(engine.config.speed, engine.config.forgiving, c)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    recorded.value = false
                }
            }
        }
    }

    val onPadTap: (Int, Int) -> Unit = { seat, pad ->
        val r = game.tap(pad, if (multi) seat else -1)
        if (r != TapResult.IGNORED) {
            flash[seat * EchoPads.COUNT + pad] = game.nowMs() + 170L
            tone(pad)
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val litProvider: (Int, Int) -> Boolean = { seat, pad ->
        frame.value
        lit == pad || (flash[seat * EchoPads.COUNT + pad] ?: 0L) > game.nowMs()
    }

    val status = statusText(androidContext, engine, ::seatName, multi, duel)
    val storedBest = bests[EchoPadsBest.key(engine.config.speed, engine.config.forgiving)] ?: 0
    val header = when {
        solo -> stringResource(R.string.echo_pads_score_best, engine.completedBy(0), max(storedBest, engine.completedBy(0)))
        mode == EchoMode.PASS_AND_PLAY -> stringResource(R.string.echo_pads_round_left, engine.round, engine.alive.size)
        else -> stringResource(R.string.echo_pads_round, engine.round)
    }
    val roundLine = if (solo) stringResource(R.string.echo_pads_round, engine.round) else null

    // The finished panel. In an oriented duel each player gets their own copy, turned to face them, inside their half.
    val finishedPanel: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
            val sessionTally = players.joinToString(" · ") { "${seatName(players.indexOf(it))} ${game.sessionWins.value[it.playerId] ?: 0}" }
            if (solo) {
                val c = engine.completedBy(0)
                ClFinishedPanel(
                    title = stringResource(R.string.echo_pads_over_solo_title),
                    subtitle = if (newBest.value) stringResource(R.string.echo_pads_over_new_best) else null,
                    lines = listOf(
                        if (c > 0) stringResource(R.string.echo_pads_over_solo_line, c) else stringResource(R.string.echo_pads_over_solo_none),
                        stringResource(R.string.echo_pads_over_best, max(storedBest, c))
                    ),
                    canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
                )
            } else {
                val title = if (engine.isTie) stringResource(R.string.echo_pads_over_tie, engine.winners.joinToString(", ") { seatName(it) })
                else stringResource(R.string.echo_pads_over_win, seatName(engine.winners.first()))
                ClFinishedPanel(
                    title = title, subtitle = null,
                    lines = players.indices.map { stringResource(R.string.echo_pads_over_line, seatName(it), engine.completedBy(it)) } +
                        stringResource(R.string.ccl_session, sessionTally),
                    canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
                )
            }
        }
    }
    val (_, openCoach) = rememberClCoach("echo-pads", ready = true)
    val finished = game.sessionWins.value.values.sum() + game.sessionBest.intValue
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = engine.phase != EchoPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finished > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finished == 0,
        buttonFill = colors.bg, buttonContent = colors.text
    ) {
        Column(Modifier.fillMaxSize().background(colors.bg)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                ClCoachButton("echo-pads", openCoach)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (roundLine != null) "$roundLine · $header" else header, color = colors.text,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    ClStatusLine(status, colors.text, Modifier.fillMaxWidth(), emphasized = true)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (duel && mode == EchoMode.DUEL) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wPx = with(density) { maxWidth.toPx() }
                        val hPx = with(density) { maxHeight.toPx() }
                        val layout = remember(wPx, hPx, orient) { ReactionWhackZones.layout(2, wPx, hPx, orient, false) }
                        for (zone in layout.zones) {
                            RwZoneBox(zone) {
                                EpDuelZone(
                                    seat = zone.seat, name = seatName(zone.seat), engine = engine, colors = colors, reduced = reduced,
                                    litProvider = litProvider, onTap = onPadTap, winnerNow = engine.phase == EchoPhase.OVER && zone.seat in engine.winners && !engine.isTie,
                                    compact = zone.contentHeight / density.density < EchoPadGrid.COMPACT_ZONE_BELOW_DP
                                )
                                if (engine.phase == EchoPhase.OVER && orient) finishedPanel()
                            }
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
                        // A wide, short window (a phone or the Fold cover screen in landscape) puts the controls
                        // beside the board instead of under it, so the pads keep the whole height.
                        val wide = maxWidth > maxHeight * 1.25f
                        val onReady: () -> Unit = { game.ready() }
                        if (wide) {
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                EpBoardSlot(
                                    Modifier.weight(1f).fillMaxHeight(), engine, litProvider, reduced, colors, onPadTap
                                )
                                Spacer(Modifier.width(12.dp))
                                EpTurnControls(
                                    Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    engine, players.size, mode, colors, ::seatName, onReady, centered = true
                                )
                            }
                        } else {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                EpBoardSlot(
                                    Modifier.weight(1f).fillMaxWidth(), engine, litProvider, reduced, colors, onPadTap
                                )
                                Spacer(Modifier.height(8.dp))
                                EpTurnControls(
                                    Modifier.fillMaxWidth(), engine, players.size, mode, colors, ::seatName, onReady, centered = false
                                )
                            }
                        }
                    }
                }
                if (engine.phase == EchoPhase.OVER && !(duel && mode == EchoMode.DUEL && orient)) finishedPanel()
            }
        }
    }
}

/** The shared square board of Solo and Pass and play, as large as fits its slot. */
@Composable
private fun EpBoardSlot(
    modifier: Modifier,
    engine: EchoPadsEngine,
    litProvider: (Int, Int) -> Boolean,
    reduced: Boolean,
    colors: EpColors,
    onTap: (Int, Int) -> Unit
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        EpPadBoard(
            modifier = Modifier.size(side), seat = engine.currentPlayer, engine = engine,
            litProvider = litProvider, reduced = reduced, colors = colors,
            interactive = engine.phase == EchoPhase.WAITING, onTap = onTap
        )
    }
}

/**
 * The Ready button / "x of y" line (in a slot of fixed height, so the board never changes size when a phase
 * changes) and, in Pass and play, the player chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpTurnControls(
    modifier: Modifier,
    engine: EchoPadsEngine,
    playerCount: Int,
    mode: EchoMode,
    colors: EpColors,
    seatName: (Int) -> String,
    onReady: () -> Unit,
    centered: Boolean
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
            if (engine.awaitingReady) {
                Button(onClick = onReady, modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth(0.7f)) {
                    Text(stringResource(R.string.echo_pads_ready_button), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            } else if (engine.phase == EchoPhase.WAITING) {
                Text(
                    stringResource(R.string.echo_pads_progress, engine.progress, engine.sequence.size),
                    color = colors.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
            }
        }
        if (mode == EchoMode.PASS_AND_PLAY) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()
            ) {
                for (i in 0 until playerCount) {
                    val out = engine.isOut(i)
                    val current = i == engine.currentPlayer && engine.phase != EchoPhase.OVER
                    val seatColor = RwSeatColors[i % RwSeatColors.size]
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.card)
                            .border(if (current) 3.dp else 1.dp, if (current) seatColor else colors.neutral, RoundedCornerShape(18.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ClSeatBadge(seatColor, i % 5, sizeDp = 20)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            seatName(i) + if (out) " · " + stringResource(R.string.echo_pads_out_tag) else "",
                            color = if (out) colors.neutral else colors.text,
                            textDecoration = if (out) TextDecoration.LineThrough else null,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** One player's half of the duel: a header (who, and what they are doing) over their own four pads. */
@Composable
private fun EpDuelZone(
    seat: Int,
    name: String,
    engine: EchoPadsEngine,
    colors: EpColors,
    reduced: Boolean,
    litProvider: (Int, Int) -> Boolean,
    onTap: (Int, Int) -> Unit,
    winnerNow: Boolean,
    compact: Boolean
) {
    val mine = engine.currentPlayer == seat
    val state = when (engine.phase) {
        EchoPhase.READY -> stringResource(R.string.echo_pads_zone_get_ready)
        EchoPhase.SHOWING -> stringResource(R.string.echo_pads_zone_watch)
        EchoPhase.WAITING -> stringResource(if (mine) R.string.echo_pads_zone_your_turn else R.string.echo_pads_zone_wait)
        EchoPhase.CHECKING -> if (!mine) stringResource(R.string.echo_pads_zone_wait) else when (engine.outcome) {
            EchoOutcome.OK -> stringResource(R.string.echo_pads_zone_ok)
            EchoOutcome.RETRY -> stringResource(R.string.echo_pads_zone_retry)
            else -> stringResource(R.string.echo_pads_zone_miss)
        }
        EchoPhase.OVER -> if (winnerNow) stringResource(R.string.echo_pads_zone_winner) else ""
    }
    val seatColor = RwSeatColors[seat.coerceIn(0, RwSeatColors.lastIndex)]
    Column(
        Modifier
            .fillMaxSize()
            .padding(if (compact) 2.dp else 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .border(if (mine && engine.phase == EchoPhase.WAITING) 5.dp else 3.dp, seatColor, RoundedCornerShape(22.dp))
            .padding(if (compact) 4.dp else 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(if (compact) 28.dp else 36.dp), verticalAlignment = Alignment.CenterVertically) {
            ClSeatBadge(seatColor, seat, sizeDp = 24)
            Spacer(Modifier.width(6.dp))
            Text(name, color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(state, color = colors.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
        EpPadBoard(
            modifier = Modifier.weight(1f).fillMaxWidth(), seat = seat, engine = engine,
            litProvider = litProvider, reduced = reduced, colors = colors,
            interactive = mine && engine.phase == EchoPhase.WAITING, onTap = onTap
        )
    }
}

/**
 * The four pads in a 2 x 2 grid. The pads draw from [litProvider] inside the draw pass (so the lights follow the
 * clock without recomposing); each pad is its own touch target (a press registers on touch-down, not on release)
 * with its shape as the accessibility label. Brightness shows the state: lit is bright, copying is full colour,
 * anything else is faded. With Reduced Motion that is all that changes; otherwise a lit pad also swells slightly
 * and gets a soft halo.
 */
@Composable
private fun EpPadBoard(
    modifier: Modifier,
    seat: Int,
    engine: EchoPadsEngine,
    litProvider: (Int, Int) -> Boolean,
    reduced: Boolean,
    colors: EpColors,
    interactive: Boolean,
    onTap: (Int, Int) -> Unit
) {
    val d = LocalDensity.current
    val tap by rememberUpdatedState(onTap)
    val canTap by rememberUpdatedState(interactive)
    BoxWithConstraints(modifier) {
        val w = with(d) { maxWidth.toPx() }
        val h = with(d) { maxHeight.toPx() }
        val outline = Color(0xFF111118)
        // A wide, short board (a duel half in landscape) lays its pads in one row so each stays large.
        val cols = EchoPadGrid.columnsFor(w, h)
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = 2.dp.toPx()
            for (i in 0 until EchoPads.COUNT) {
                val r = EchoPadGrid.rect(i, size.width, size.height, cols)
                val isLit = litProvider(seat, i)
                val base = echoPadColor(i)
                val fill = when {
                    isLit -> echoBlend(base, Color.White, 0.32f)
                    canTap -> base
                    else -> echoBlend(base, EpDim, 0.55f)
                }
                val side = min(r[2] - r[0], r[3] - r[1])
                val swell = if (isLit && !reduced) side * 0.03f else 0f
                val tl = Offset(r[0] - swell, r[1] - swell)
                val sz = Size(r[2] - r[0] + swell * 2, r[3] - r[1] + swell * 2)
                val corner = CornerRadius(side * 0.22f)
                if (isLit && !reduced) {
                    val halo = side * 0.07f
                    drawRoundRect(base.copy(alpha = 0.35f), Offset(tl.x - halo, tl.y - halo), Size(sz.width + halo * 2, sz.height + halo * 2), CornerRadius(side * 0.28f))
                }
                drawRoundRect(fill, tl, sz, corner)
                // A dark rim on every pad keeps its edge visible on the light theme (yellow on lavender is about 1.2:1).
                drawRoundRect(outline.copy(alpha = 0.85f), tl, sz, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(if (isLit) 8.dp.toPx() else 3.dp.toPx()))
                if (isLit) drawRoundRect(Color.White, tl, sz, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx()))
                else if (canTap) drawRoundRect(Color.White.copy(alpha = 0.6f), tl, sz, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(strokePx))
                val glyphAlpha = if (isLit || canTap) 1f else 0.6f
                drawEchoShape(
                    i, echoGlyphColor(i).copy(alpha = glyphAlpha), (if (i == EchoPads.SQUARE) Color.White else outline).copy(alpha = 0.55f * glyphAlpha),
                    Offset(tl.x + sz.width / 2f, tl.y + sz.height / 2f), side * 0.26f, strokePx
                )
            }
        }
        // Underneath the pads: only a touch that lands in the gap between them reaches this layer (the pads sit
        // above and take their own touches), and it snaps to the nearest pad so fast play next to the gap is not lost.
        Box(
            Modifier.fillMaxSize().pointerInput(seat, w, h, cols) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pad = EchoPadGrid.nearestPad(down.position.x, down.position.y, w, h, cols)
                    if (pad >= 0) tap(seat, pad)
                    waitForUpOrCancellation()
                }
            }
        )
        for (i in 0 until EchoPads.COUNT) {
            val r = EchoPadGrid.rect(i, w, h, cols)
            val pw = with(d) { (r[2] - r[0]).toDp() }
            val ph = with(d) { (r[3] - r[1]).toDp() }
            val name = stringResource(padNameRes(i))
            Box(
                Modifier
                    .offset { IntOffset(r[0].roundToInt(), r[1].roundToInt()) }
                    .size(pw, ph)
                    .pointerInput(i, seat) {
                        awaitEachGesture {
                            awaitFirstDown()
                            tap(seat, i)
                            waitForUpOrCancellation()
                        }
                    }
                    .semantics {
                        // Static: reading the frame-driven lit state here would invalidate the node every frame under TalkBack.
                        contentDescription = name
                        role = Role.Button
                        if (interactive) onClick(label = name) { tap(seat, i); true }
                    }
            )
        }
    }
}
