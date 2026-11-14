package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.gamesuite.games.partypack.PpEdge
import com.gamesuite.games.partypack.PpSeatLayout
import com.gamesuite.games.partypack.RpsEventKind
import com.gamesuite.games.partypack.RpsFormat
import com.gamesuite.games.partypack.RpsGame
import com.gamesuite.games.partypack.RpsMove
import com.gamesuite.games.partypack.RpsPhase
import com.gamesuite.games.partypack.RpsRules
import com.gamesuite.games.partypack.RpsVariant
import com.gamesuite.games.partypack.RpsView
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

private class RpsPal(val bg: Color, val text: Color, val card: Color, val gold: Color, val good: Color, val bad: Color)

private fun rpsPal(dark: Boolean) = if (dark) RpsPal(
    bg = Color(0xFF15161B), text = Color(0xFFEFEFF6), card = Color(0xFF262831), gold = Color(0xFFFFC83D), good = Color(0xFF4CD08C), bad = Color(0xFFFF7A70)
) else RpsPal(
    bg = Color(0xFFF4F2F7), text = Color(0xFF1F2026), card = Color(0xFFE3E1EB), gold = Color(0xFFB07A00), good = Color(0xFF1B7F4F), bad = Color(0xFFB3261E)
)

private fun Context.rpsMoveName(m: RpsMove): String = getString(
    when (m) {
        RpsMove.ROCK -> R.string.rps_move_rock
        RpsMove.PAPER -> R.string.rps_move_paper
        RpsMove.SCISSORS -> R.string.rps_move_scissors
        RpsMove.LIZARD -> R.string.rps_move_lizard
        RpsMove.SPOCK -> R.string.rps_move_spock
    }
)

/** "Rock crushes Scissors." for a winning move and a move it beats. */
private fun Context.rpsBeatLine(win: RpsMove, lose: RpsMove): String = getString(
    when (win to lose) {
        RpsMove.ROCK to RpsMove.SCISSORS -> R.string.rps_beat_rock_scissors
        RpsMove.ROCK to RpsMove.LIZARD -> R.string.rps_beat_rock_lizard
        RpsMove.PAPER to RpsMove.ROCK -> R.string.rps_beat_paper_rock
        RpsMove.PAPER to RpsMove.SPOCK -> R.string.rps_beat_paper_spock
        RpsMove.SCISSORS to RpsMove.PAPER -> R.string.rps_beat_scissors_paper
        RpsMove.SCISSORS to RpsMove.LIZARD -> R.string.rps_beat_scissors_lizard
        RpsMove.LIZARD to RpsMove.PAPER -> R.string.rps_beat_lizard_paper
        RpsMove.LIZARD to RpsMove.SPOCK -> R.string.rps_beat_lizard_spock
        RpsMove.SPOCK to RpsMove.SCISSORS -> R.string.rps_beat_spock_scissors
        else -> R.string.rps_beat_spock_rock
    }
)

/**
 * Rock-Paper-Showdown on one screen. Each player has a zone on their own edge, turned to face them. Tapping a move
 * LOCKS it in at once; from then on the zone shows only a lock, so a neighbour sees "locked" and never the pick (Undo
 * takes the lock back while others are still choosing). When everybody is locked a countdown runs and all picks flip
 * face up together. Same screen only: no Nearby or Online in this version.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RpsShowdownScreen(
    sessionManager: GameSessionManager,
    game: RpsGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    rememberAmbientMusic(profile = MusicProfiles.UNO, enabled = LocalMusicEnabled.current && CardSounds.soundEnabled)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pal = rpsPal(dark)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val burst = rememberParticleBurst()
    var facing by remember { mutableStateOf(true) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.variantId = extras["variant"] ?: RpsVariant.CLASSIC.id
        game.formatId = extras["format"] ?: RpsFormat.BEST_OF_3.id
        game.pickTimerMs = (extras["timer"]?.toIntOrNull() ?: 0).coerceIn(0, 120) * 1000
        game.relaxed = extras["relaxed"] == "true"
        facing = extras["facing"] != "false"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    val v = game.view.value
    val ctx = context
    var status by remember { mutableStateOf("") }
    val flip = remember { Animatable(1f) }
    var revealSerial by remember { mutableStateOf(0) }
    val engine = game.engine
    val names = remember(ctx) { ctx?.players?.map { androidContext.seatLabel(it.displayName) } ?: emptyList() }

    LaunchedEffect(game, ctx) {
        if (ctx == null) return@LaunchedEffect
        while (true) {
            game.timeScale = min(1.0, 1.0 / gameSpeed.delayScale.toDouble())
            game.tick()
            val e = game.engine
            for (ev in game.drainEvents()) {
                val nm = names.getOrElse(ev.seat) { "" }
                when (ev.kind) {
                    RpsEventKind.ROUND_START -> status = androidContext.getString(R.string.rps_ann_round, ev.a)
                    RpsEventKind.LOCKED -> {
                        status = androidContext.getString(R.string.rps_ann_locked, nm)
                        playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK)
                    }
                    RpsEventKind.UNLOCKED -> status = androidContext.getString(R.string.rps_ann_unlocked, nm)
                    RpsEventKind.AUTO_PICK -> status = androidContext.getString(R.string.rps_ann_timeout, nm)
                    RpsEventKind.COUNT -> { status = androidContext.getString(R.string.rps_ann_count, ev.a); playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
                    RpsEventKind.REVEAL -> {
                        val view = e?.view()
                        val o = view?.outcome
                        if (o != null) {
                            revealSerial++
                            playSfx(SfxKind.WHOOSH); haptics(HapticSignal.NORMAL_ACTION)
                            val played = o.picks.mapIndexedNotNull { i, m -> m?.let { androidContext.getString(R.string.rps_ann_played, names.getOrElse(i) { "" }, androidContext.rpsMoveName(it)) } }.joinToString(" ")
                            val tail = if (o.tie) androidContext.getString(R.string.rps_ann_tie)
                            else androidContext.rpsBeatLine(o.dominant!!, o.beaten ?: o.dominant!!) + " " +
                                androidContext.getString(R.string.rps_ann_round_win, o.winners.joinToString(" / ") { names.getOrElse(it) { "" } })
                            status = "$played $tail"
                            if (!reduced) { flip.snapTo(0f); flip.animateTo(1f, tween(520)) } else flip.snapTo(1f)
                            if (!o.tie) playSfx(SfxKind.SOLID_THUNK)
                        }
                    }
                    RpsEventKind.ELIMINATED -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                    RpsEventKind.WIN -> {
                        game.noteFinished()
                        status = androidContext.getString(R.string.rps_ann_win, nm) + " " + status
                        playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                        if (!reduced) burst.spawn(Offset(0.5f, 0.4f), 110, listOf(PpSeatColors[ev.seat.coerceIn(0, 3)], Color.White, pal.gold), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
                    }
                }
            }
            delay(50)
        }
    }

    if (ctx == null || v == null || engine == null) return
    val n = ctx.players.size.coerceAtMost(4)
    val cfg = engine.config
    val finishedMatches = game.sessionWins.value.values.sum()
    val cheat = listOf(
        R.string.rps_beat_rock_scissors, R.string.rps_beat_paper_rock, R.string.rps_beat_scissors_paper
    ) + if (cfg.variant == RpsVariant.LIZARD_SPOCK) listOf(
        R.string.rps_beat_rock_lizard, R.string.rps_beat_lizard_spock, R.string.rps_beat_spock_scissors,
        R.string.rps_beat_scissors_lizard, R.string.rps_beat_lizard_paper, R.string.rps_beat_paper_spock, R.string.rps_beat_spock_rock
    ) else emptyList()
    val helpText = stringResource(R.string.rps_help_text) + "\n\n" + cheat.map { stringResource(it) }.joinToString("\n")

    GameChrome(
        helpTitle = stringResource(R.string.rps_help_title), helpText = helpText,
        matchInProgress = v.phase != RpsPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedMatches > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedMatches == 0,
        buttonFill = pal.bg, buttonContent = pal.text
    ) {
        val (_, openCoach) = rememberClCoach("rps-showdown", ready = true)
        val density = LocalDensity.current
        val fontScale = density.fontScale.coerceIn(1f, 1.5f)
        BoxWithConstraints(Modifier.fillMaxSize().background(pal.bg)) {
            val zoneT: Dp = minOf(200.dp * fontScale, maxHeight * 0.4f)
            val edges = PpSeatLayout.edges(n)
            val over = v.phase == RpsPhase.OVER

            val zone: @Composable (Int) -> Unit = { s ->
                RpsZone(
                    seat = s, name = names.getOrElse(s) { "" }, allNames = names, view = v, bot = ctx.players[s].isBot, variant = cfg.variant,
                    elimination = cfg.format.elimination, pal = pal, context = androidContext,
                    onPick = { m -> game.lock(s, m) }, onUndo = { game.unlock(s) }
                )
            }

            if (facing) {
                // zones turned toward their players, the table in the middle
                val hasTop = PpEdge.TOP in edges; val hasBottom = PpEdge.BOTTOM in edges
                val hasLeft = PpEdge.LEFT in edges; val hasRight = PpEdge.RIGHT in edges
                Box(
                    Modifier.fillMaxSize().padding(
                        top = if (hasTop) zoneT else GameChromeEndInset, bottom = if (hasBottom) zoneT else 0.dp,
                        start = if (hasLeft) zoneT else 0.dp, end = if (hasRight) zoneT else 0.dp
                    ).padding(4.dp)
                ) {
                    RpsTable(v, names, n, cfg.format, cfg.variant, pal, androidContext, status, flip.value, true, edges, reduced, game, openCoach, over, ctx, finishedMatches)
                }
                for ((s, e) in edges.withIndex()) {
                    val q = PpSeatLayout.quarterTurns(e)
                    val mod = when (e) {
                        PpEdge.BOTTOM -> Modifier.fillMaxWidth().height(zoneT)
                        PpEdge.TOP -> Modifier.fillMaxWidth().height(zoneT).padding(end = GameChromeEndInset)
                        PpEdge.LEFT -> Modifier.fillMaxHeight().width(zoneT).padding(top = if (hasTop) zoneT else GameChromeEndInset, bottom = if (hasBottom) zoneT else 0.dp)
                        PpEdge.RIGHT -> Modifier.fillMaxHeight().width(zoneT).padding(top = if (hasTop) zoneT else GameChromeEndInset, bottom = if (hasBottom) zoneT else 0.dp)
                    }
                    Box(Modifier.align(e.alignment()).then(mod).padding(4.dp)) {
                        Box(Modifier.fillMaxSize().ppRotateLayout(q)) { zone(s) }
                    }
                }
            } else {
                // everything upright: the table on top, the zones one under another
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(top = GameChromeEndInset / 2)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 260.dp)) {
                        RpsTable(v, names, n, cfg.format, cfg.variant, pal, androidContext, status, flip.value, false, edges, reduced, game, openCoach, over, ctx, finishedMatches)
                    }
                    for (s in 0 until n) {
                        Box(Modifier.fillMaxWidth().height(zoneT).padding(vertical = 4.dp)) { zone(s) }
                    }
                }
            }
            if (facing) {
                val sideW = (if (PpEdge.LEFT in edges) zoneT else 0.dp) + (if (PpEdge.RIGHT in edges) zoneT else 0.dp) + 8.dp
                if (maxWidth - sideW < RPS_NARROW_TABLE && (v.phase == RpsPhase.RESULT || over)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 380.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (over) RpsFinished(v, names, ctx, androidContext, game)
                            else Button(onClick = { game.next() }, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.rps_next_round)) }
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
        }
    }
}

private val RPS_NARROW_TABLE = 150.dp

private enum class CardState { EMPTY, LOCKED, FACE, OUT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RpsTable(
    v: RpsView, names: List<String>, n: Int, format: RpsFormat, variant: RpsVariant, pal: RpsPal, context: Context,
    status: String, flip: Float, facing: Boolean, edges: List<PpEdge>, reduced: Boolean, game: RpsGame,
    openCoach: () -> Unit, over: Boolean, ctx: com.gamesuite.core.GameContext, finishedMatches: Int
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A phone with four players leaves the middle only a sliver: the zones then show the reveal themselves and the
        // screen puts the "next round" button and the result in an overlay (see RpsShowdownScreen), so draw nothing here.
        if (facing && maxWidth < RPS_NARROW_TABLE) return@BoxWithConstraints
        val cardSize = maxOf(minOf(96.dp, maxHeight * 0.34f), 52.dp)
        val outcome = v.outcome
        // cards
        for (s in 0 until n) {
            val state = when {
                !v.active[s] -> CardState.OUT
                outcome != null -> CardState.FACE
                v.locked[s] -> CardState.LOCKED
                else -> CardState.EMPTY
            }
            val verdict = if (outcome == null) null else RpsRules.resultFor(outcome, s)
            val card: @Composable () -> Unit = {
                RpsCard(PpSeatColors[s], ppSeatShape(s), names.getOrElse(s) { "" }, state, v.picks.getOrNull(s), verdict, outcome?.tie == true, flip, cardSize, pal, context)
            }
            if (facing) {
                val e = edges[s]
                Box(Modifier.align(e.alignment()).ppRotateLayout(PpSeatLayout.quarterTurns(e))) { card() }
            }
        }
        // the middle of the table
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = if (facing) cardSize + 8.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClCoachButton("rps-showdown", openCoach)
                Text(
                    stringResource(R.string.rps_round_label, v.round) + " · " + stringResource(if (variant == RpsVariant.CLASSIC) R.string.rps_variant_classic else R.string.rps_variant_lizard) + " · " +
                        if (format.elimination) stringResource(R.string.rps_target_elim) else stringResource(R.string.rps_target_wins, format.winsNeeded),
                    color = pal.text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in 0 until n) {
                    val desc = if (format.elimination) {
                        stringResource(if (v.active[s]) R.string.rps_score_in else R.string.rps_score_out, names.getOrElse(s) { "" }, v.wins[s])
                    } else stringResource(R.string.rps_score_wins, names.getOrElse(s) { "" }, v.wins[s])
                    Row(Modifier.semantics { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ClSeatBadge(PpSeatColors[s], ppSeatShape(s), 18)
                        Text(names.getOrElse(s) { "" } + " " + (if (format.elimination && !v.active[s]) "×" else "${v.wins[s]}"), color = pal.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                    }
                }
            }
            val main: String = when (v.phase) {
                RpsPhase.PICK -> stringResource(R.string.rps_center_pick) + " " + stringResource(R.string.rps_center_locked, v.locked.indices.count { v.active[it] && v.locked[it] }, v.active.count { it })
                RpsPhase.COUNTDOWN -> ""
                else -> if (outcome == null) "" else if (outcome.tie) stringResource(R.string.rps_ann_tie) else
                    context.rpsBeatLine(outcome.dominant!!, outcome.beaten ?: outcome.dominant!!)
            }
            if (v.phase == RpsPhase.COUNTDOWN) {
                Text("${v.countdown}", color = pal.gold, fontSize = 64.sp, fontWeight = FontWeight.Black, modifier = Modifier.graphicsLayer {
                    val k = if (reduced) 1f else 1f + 0.25f * abs(cos(v.countdown * 1.7f))
                    scaleX = k; scaleY = k
                })
            } else {
                Text(main, color = pal.text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            // spoken for TalkBack, shown small
            if (status.isNotEmpty()) ClStatusLine(status, pal.text.copy(alpha = 0.75f), Modifier.fillMaxWidth())
            if (v.phase == RpsPhase.PICK && v.timerLeftMs > 0) {
                Text(stringResource(R.string.rps_timer_left, (v.timerLeftMs + 999) / 1000), color = if (v.timerLeftMs < 4000) pal.bad else pal.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (v.phase == RpsPhase.RESULT) {
                Button(onClick = { game.next() }, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.rps_next_round)) }
            }
            if (!facing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in 0 until n) {
                        val state = when { !v.active[s] -> CardState.OUT; outcome != null -> CardState.FACE; v.locked[s] -> CardState.LOCKED; else -> CardState.EMPTY }
                        RpsCard(PpSeatColors[s], ppSeatShape(s), names.getOrElse(s) { "" }, state, v.picks.getOrNull(s), if (outcome == null) null else RpsRules.resultFor(outcome, s), outcome?.tie == true, flip, 72.dp, pal, context)
                    }
                }
            }
            if (over) RpsFinished(v, names, ctx, context, game)
        }
    }
}

@Composable
private fun RpsFinished(v: RpsView, names: List<String>, ctx: com.gamesuite.core.GameContext, context: Context, game: RpsGame) {
    val winner = v.winners.firstOrNull() ?: -1
    val humans = ctx.players.count { !it.isBot }
    val title = when {
        humans == 1 && !ctx.players[0].isBot -> if (winner == 0) stringResource(R.string.ccl_you_won) else stringResource(R.string.ccl_you_lost)
        else -> stringResource(R.string.rps_result_winner, names.getOrElse(winner) { "" })
    }
    val session = ctx.players.joinToString(" · ") { "${context.seatLabel(it.displayName)} ${game.sessionWins.value[it.playerId] ?: 0}" }
    ClFinishedPanel(
        title = title, subtitle = null,
        lines = listOf(names.indices.joinToString(" · ") { "${names[it]} ${v.wins[it]}" }, stringResource(R.string.ccl_session, session)),
        canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
    )
}

@Composable
private fun RpsCard(
    color: Color, shape: Int, name: String, state: CardState, move: RpsMove?, verdict: Int?, tie: Boolean,
    flip: Float, size: Dp, pal: RpsPal, context: Context
) {
    val face = state == CardState.FACE && move != null
    val desc = when {
        face -> stringResource(R.string.rps_card_face, name, context.rpsMoveName(move!!))
        state == CardState.LOCKED -> stringResource(R.string.rps_card_locked, name)
        state == CardState.OUT -> stringResource(R.string.rps_card_out, name)
        else -> stringResource(R.string.rps_card_waiting, name)
    }
    val verdictText = when {
        !face || verdict == null -> null
        tie -> stringResource(R.string.rps_card_tie)
        verdict > 0 -> stringResource(R.string.rps_card_wins)
        verdict < 0 -> stringResource(R.string.rps_card_loses)
        else -> null
    }
    val won = face && verdict == 1 && !tie
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = desc }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).graphicsLayer { scaleX = if (face) (abs(flip * 2f - 1f)).coerceAtLeast(0.02f) else 1f; alpha = if (state == CardState.OUT) 0.4f else 1f }
                .clip(RoundedCornerShape(14.dp))
                .background(if (face && flip >= 0.5f) Color.White else color.copy(alpha = if (state == CardState.EMPTY) 0.12f else 0.55f))
                .border(if (won) 4.dp else 2.dp, if (won) pal.gold else color, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(size)) {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                when {
                    face && flip >= 0.5f -> drawRpsMove(move!!, c, this.size.minDimension * 0.78f)
                    face || state == CardState.LOCKED -> drawRpsLock(c, this.size.minDimension * 0.55f, Color.White)
                    state == CardState.OUT -> drawSeatGlyph(shape, c, this.size.minDimension * 0.2f)
                    else -> drawSeatGlyph(shape, c, this.size.minDimension * 0.14f)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ClSeatBadge(color, shape, 14)
            Text(
                if (face) context.rpsMoveName(move!!) else name, color = pal.text, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = size + 16.dp)
            )
        }
        if (verdictText != null) Text(verdictText, color = if (won) pal.gold else pal.text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RpsZone(
    seat: Int, name: String, allNames: List<String>, view: RpsView, bot: Boolean, variant: RpsVariant, elimination: Boolean, pal: RpsPal, context: Context,
    onPick: (RpsMove) -> Unit, onUndo: () -> Unit
) {
    val color = PpSeatColors[seat.coerceIn(0, 3)]
    val out = !view.active[seat]
    val outcome = view.outcome
    val locked = view.locked[seat]
    val result = if (outcome == null) null else RpsRules.resultFor(outcome, seat)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(color.copy(alpha = 0.16f)).border(2.dp, color.copy(alpha = 0.85f), shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClSeatBadge(color, ppSeatShape(seat), 24)
            Text(name, color = pal.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(if (elimination && out) "×" else "★ ${view.wins[seat]}", color = pal.text, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            val statusText = when {
                out -> stringResource(R.string.rps_zone_out)
                view.phase == RpsPhase.OVER || view.phase == RpsPhase.RESULT -> when {
                    outcome == null -> ""
                    outcome.tie -> stringResource(R.string.rps_zone_tie)
                    result == 1 -> stringResource(R.string.rps_zone_win)
                    else -> stringResource(R.string.rps_zone_lose)
                }
                view.phase == RpsPhase.COUNTDOWN -> stringResource(R.string.rps_zone_countdown, view.countdown)
                bot -> stringResource(if (locked) R.string.rps_zone_cpu_locked else R.string.rps_zone_cpu_thinking)
                locked -> stringResource(R.string.rps_zone_locked)
                else -> stringResource(R.string.rps_zone_pick)
            }.let { if (view.phase == RpsPhase.PICK && view.timerLeftMs > 0) it + " · " + stringResource(R.string.rps_timer_left, (view.timerLeftMs + 999) / 1000) else it }
            ClStatusLine(statusText, pal.text, Modifier.widthIn(max = 220.dp), emphasized = true)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                // Everybody's move, face up, turned to this player: on a phone with four players the table in the middle is
                // too narrow to show it, so every zone carries the reveal itself.
                (view.phase == RpsPhase.RESULT || view.phase == RpsPhase.OVER) && outcome != null -> {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (t in allNames.indices) {
                            val pick = view.picks.getOrNull(t)
                            if (!view.active.getOrElse(t) { false } || pick == null) continue
                            val tint = PpSeatColors[t.coerceIn(0, 3)]
                            val tileDesc = stringResource(R.string.rps_card_face, allNames[t], context.rpsMoveName(pick))
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = if (pal.bg.luminance() < 0.5f) 0.14f else 0.7f))
                                    .border(if (t == seat) 3.dp else 1.dp, tint, RoundedCornerShape(12.dp)).semantics(mergeDescendants = true) { contentDescription = tileDesc }.padding(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                            ) {
                                Canvas(Modifier.weight(1f).fillMaxWidth()) { drawRpsMove(pick, Offset(size.width / 2, size.height / 2), min(size.width, size.height) * 0.9f) }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    ClSeatBadge(tint, ppSeatShape(t), 12)
                                    Text(context.rpsMoveName(pick), color = pal.text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                out || bot || view.phase == RpsPhase.RESULT || view.phase == RpsPhase.OVER -> {
                    if (bot && !out && (view.phase == RpsPhase.PICK || view.phase == RpsPhase.COUNTDOWN)) {
                        Canvas(Modifier.size(44.dp)) { if (locked) drawRpsLock(Offset(size.width / 2, size.height / 2), size.minDimension, color) else drawSeatGlyph(ppSeatShape(seat), Offset(size.width / 2, size.height / 2), size.minDimension * 0.3f) }
                    }
                }
                locked -> {
                    // the shield: nothing about the pick is shown, only that it is locked
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(48.dp)) { drawRpsLock(Offset(size.width / 2, size.height / 2), size.minDimension, color) }
                        if (view.phase == RpsPhase.PICK) {
                            OutlinedButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.rps_zone_undo), color = pal.text) }
                        }
                    }
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in variant.moves) {
                        val label = context.rpsMoveName(m)
                        val desc = stringResource(R.string.rps_btn_play, name, label)
                        Column(
                            Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = if (pal.bg.luminance() < 0.5f) 0.14f else 0.7f))
                                .clickable(onClickLabel = desc, role = Role.Button) { onPick(m) }
                                .semantics { contentDescription = desc; role = Role.Button }.padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                        ) {
                            Canvas(Modifier.weight(1f).fillMaxWidth()) { drawRpsMove(m, Offset(size.width / 2, size.height / 2), min(size.width, size.height) * 0.95f) }
                            Text(label, color = pal.text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
