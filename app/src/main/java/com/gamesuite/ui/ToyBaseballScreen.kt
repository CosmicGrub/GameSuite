package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.toybaseball.BaseballOutcome
import com.gamesuite.games.toybaseball.BaseballPhase
import com.gamesuite.games.toybaseball.BaseballReject
import com.gamesuite.games.toybaseball.BaseballRules
import com.gamesuite.games.toybaseball.BaseballState
import com.gamesuite.games.toybaseball.BaseballSwing
import com.gamesuite.games.toybaseball.PitchRecord
import com.gamesuite.games.toybaseball.PitchType
import com.gamesuite.games.toybaseball.ToyBaseballGame
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

private class BbColors(val bg: Color, val text: Color, val card: Color, val accent: Color, val gold: Color, val grass: Color, val dirt: Color, val zone: Color)

private fun bbColors(dark: Boolean) = if (!dark) BbColors(
    bg = Color(0xFFEFF1F6), text = Color(0xFF1B2030), card = Color(0xFFDCE1EE), accent = Color(0xFF1F3A8A),
    gold = Color(0xFFD99A00), grass = Color(0xFF3E8E41), dirt = Color(0xFFC49A6C), zone = Color(0xFFFFFFFF)
) else BbColors(
    bg = Color(0xFF14171F), text = Color(0xFFE9ECF5), card = Color(0xFF242938), accent = Color(0xFF8FA8F5),
    gold = Color(0xFFFFC83D), grass = Color(0xFF2B6B31), dirt = Color(0xFF8C6B47), zone = Color(0xFFE9ECF5)
)

private val TeamColors = listOf(Color(0xFFD55E00), Color(0xFF0072B2))

private fun Context.bbAnn(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

private fun outcomeRes(o: String): Int = when (o) {
    BaseballOutcome.CALLED_STRIKE -> R.string.tbb_out_called_strike
    BaseballOutcome.BALL -> R.string.tbb_out_ball
    BaseballOutcome.SWING_MISS -> R.string.tbb_out_swing_miss
    BaseballOutcome.FOUL -> R.string.tbb_out_foul
    BaseballOutcome.SINGLE -> R.string.tbb_out_single
    BaseballOutcome.DOUBLE -> R.string.tbb_out_double
    BaseballOutcome.HOME_RUN -> R.string.tbb_out_home_run
    BaseballOutcome.GROUND_OUT -> R.string.tbb_out_ground_out
    BaseballOutcome.FLY_OUT -> R.string.tbb_out_fly_out
    BaseballOutcome.LINE_OUT -> R.string.tbb_out_line_out
    BaseballOutcome.DOUBLE_PLAY -> R.string.tbb_out_double_play
    BaseballOutcome.SAC_FLY -> R.string.tbb_out_sac_fly
    BaseballOutcome.WALK -> R.string.tbb_out_walk
    BaseballOutcome.STRIKEOUT_SWINGING -> R.string.tbb_out_k_swinging
    else -> R.string.tbb_out_k_looking
}

private fun typeNameRes(t: PitchType) = when (t) {
    PitchType.FASTBALL -> R.string.tbb_type_fastball
    PitchType.SLIDER -> R.string.tbb_type_slider
    PitchType.CURVEBALL -> R.string.tbb_type_curve
    PitchType.CHANGEUP -> R.string.tbb_type_change
}

private fun typeDescRes(t: PitchType) = when (t) {
    PitchType.FASTBALL -> R.string.tbb_typed_fastball
    PitchType.SLIDER -> R.string.tbb_typed_slider
    PitchType.CURVEBALL -> R.string.tbb_typed_curve
    PitchType.CHANGEUP -> R.string.tbb_typed_change
}

private val typeByName = PitchType.entries.associateBy { it.name }

/**
 * Toy Baseball: a pitcher against a batter, one pitch at a time. The pitcher secretly picks a pitch type and a
 * spot on the 5x5 grid (the middle 3x3 is the strike zone); the batter, not knowing it, picks which pitch they are
 * ready for (their timing), a spot to aim at and contact or power, or takes. The pitch is revealed and the
 * deterministic contact model decides the result. Pass and Play hides each side's choice behind a hand-over
 * card. Colour-blind safe (roles are words and icons, teams carry badge shapes), Reduced-Motion aware,
 * TalkBack-friendly (every grid cell is a labelled button; every result is announced).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToyBaseballScreen(
    sessionManager: GameSessionManager,
    game: ToyBaseballGame,
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
    val hint by game.hint
    val lastReject by game.lastReject
    val opponentLeft by game.opponentLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = bbColors(dark)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val opts = sessionManager.launchOptions
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.innings = opts.extras["innings"]?.toIntOrNull() ?: BaseballRules.DEFAULT_INNINGS
        game.awaySeat = opts.firstMoverIndex.coerceIn(0, 1)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }
    LaunchedEffect(game) {
        while (true) {
            game.tick()
            delay(100)
        }
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val colorblind = LocalColorblindMode.current
    val helpTitle = stringResource(R.string.tbb_help_title)
    val helpText = stringResource(R.string.tbb_help_text)
    val s = state
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = colors.bg, buttonContent = colors.text
            ) {
                Box(Modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ccl_waiting_host), color = colors.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val hotSeat = !isNetworked && s.players.none { it.isBot }
    val viewSeat = if (isNetworked) localSeat.coerceIn(0, 1) else if (hotSeat) -1 else s.players.indexOfFirst { !it.isBot }.coerceAtLeast(0)
    fun seatName(i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    fun rawName(i: Int) = s.players.getOrNull(i)?.displayName ?: ""
    val phase = s.phaseValue
    val actor = when (phase) { BaseballPhase.PITCH -> s.pitcherSeat; BaseballPhase.SWING -> s.batterSeat; else -> -1 }
    val actorIsHuman = actor >= 0 && !s.players[actor].isBot

    // local picks
    var pType by remember { mutableStateOf(PitchType.FASTBALL) }
    var pRow by remember { mutableIntStateOf(-1) }
    var pCol by remember { mutableIntStateOf(-1) }
    var sTiming by remember { mutableStateOf(PitchType.FASTBALL) }
    var sRow by remember { mutableIntStateOf(1) }
    var sCol by remember { mutableIntStateOf(1) }
    var sPower by remember { mutableStateOf(false) }
    var revealed by remember { mutableIntStateOf(-1) } // pass and play: whose options are showing
    var animDone by remember { mutableStateOf(true) }
    var whyKey by remember { mutableStateOf<Pair<BaseballReject, Int>?>(null) }

    val anim = remember { Animatable(1f) }
    val last = s.last
    LaunchedEffect(s.eventSerial) {
        val rec = last ?: return@LaunchedEffect
        if (s.eventSerial == 0) return@LaunchedEffect
        animDone = false
        val fast = typeByName[rec.type]?.speed ?: 0
        playSfx(SfxKind.WHOOSH)
        if (!reduced) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(gameSpeed.scale(1100L + fast * 90L).toInt().coerceAtLeast(300), easing = LinearEasing))
        } else {
            anim.snapTo(1f)
            delay(gameSpeed.scale(500L))
        }
        when (rec.outcome) {
            BaseballOutcome.HOME_RUN -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION); if (!reduced) burst.spawn(Offset(0.5f, 0.25f), 70, listOf(colors.gold, Color.White, colors.accent), speedRange = 0.25f..0.8f, lifeRangeSeconds = 0.9f..1.7f, gravity = 0.9f) }
            BaseballOutcome.SINGLE, BaseballOutcome.DOUBLE, BaseballOutcome.SAC_FLY -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.STRONG_ACTION) }
            BaseballOutcome.WALK -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
            BaseballOutcome.BALL, BaseballOutcome.CALLED_STRIKE, BaseballOutcome.FOUL -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
            BaseballOutcome.SWING_MISS, BaseballOutcome.STRIKEOUT_LOOKING, BaseballOutcome.STRIKEOUT_SWINGING -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.NORMAL_ACTION) }
            else -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
        }
        if (s.lastRuns > 0) playSfx(SfxKind.SUCCESS_CHIME)
        animDone = true
    }
    LaunchedEffect(s.over, s.eventSerial) {
        if (!s.over) return@LaunchedEffect
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        val localWon = winnerSeat >= 0 && (hotSeat || winnerSeat == viewSeat)
        delay(gameSpeed.scale(1200L))
        if (localWon) {
            playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.4f), 90, listOf(colors.gold, Color.White, colors.accent, Color(0xFFD55E00)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
        } else if (winnerSeat >= 0) {
            playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
        }
    }
    LaunchedEffect(lastReject?.second) {
        val r = lastReject ?: return@LaunchedEffect
        whyKey = r
        playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
        delay(2800)
        if (whyKey?.second == r.second) whyKey = null
    }
    // A new pitch (or a new turn) starts with a clean location pick.
    LaunchedEffect(s.eventSerial, s.pitcherSeat) { pRow = -1; pCol = -1 }

    // ---- words ----
    val ctx = androidContext
    val batterName = rawName(s.batterSeat)
    val status: String = run {
        val rec = last
        val resultLine = if (rec != null) {
            val base = ctx.getString(outcomeRes(rec.outcome))
            val runs = when {
                s.lastRuns == 1 -> " " + ctx.getString(R.string.tbb_runs_one)
                s.lastRuns > 1 -> " " + ctx.getString(R.string.tbb_runs_many, s.lastRuns)
                else -> ""
            }
            val type = typeByName[rec.type]
            val where = ctx.getString(if (BaseballRules.inZone(rec.row, rec.col)) R.string.tbb_loc_zone else R.string.tbb_loc_ball)
            ctx.getString(R.string.tbb_pitch_was, ctx.getString(typeNameRes(type ?: PitchType.FASTBALL)), where) + " " + base + runs
        } else ctx.getString(R.string.tbb_play_ball)
        val tail = when {
            s.over -> {
                val ws = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                if (ws < 0) ctx.getString(R.string.tbb_draw, s.score[0], s.score[1])
                else ctx.bbAnn(rawName(ws), R.string.tbb_win, R.string.tbb_win_you, s.score[ws], s.score[1 - ws])
            }
            opponentLeft -> ctx.getString(R.string.tbb_opp_left)
            s.halfEnded -> ctx.getString(R.string.tbb_half_end)
            phase == BaseballPhase.PITCH && s.players[s.pitcherSeat].isBot -> ctx.getString(R.string.tbb_wait_pitcher, seatName(s.pitcherSeat))
            phase == BaseballPhase.PITCH && s.pitcherSeat == viewSeat -> ctx.getString(R.string.tbb_turn_pitch_you)
            phase == BaseballPhase.PITCH && !hotSeat -> ctx.getString(R.string.tbb_wait_pitcher, seatName(s.pitcherSeat))
            phase == BaseballPhase.SWING && s.players[s.batterSeat].isBot -> ctx.getString(R.string.tbb_wait_batter, seatName(s.batterSeat))
            phase == BaseballPhase.SWING && s.batterSeat == viewSeat -> ctx.getString(R.string.tbb_turn_bat_you)
            phase == BaseballPhase.SWING && !hotSeat -> ctx.getString(R.string.tbb_wait_batter, seatName(s.batterSeat))
            else -> ""
        }
        listOf(resultLine, tail).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText: String? = whyKey?.first?.let { r ->
        when (r) {
            BaseballReject.NOT_PLAYING -> stringResource(R.string.tbb_why_over)
            BaseballReject.BAD_INPUT -> stringResource(R.string.tbb_why_input)
            BaseballReject.NONE -> null
            else -> stringResource(if (opponentLeft) R.string.tbb_opp_left else R.string.tbb_why_wait)
        }
    }
    val hintText: String? = hint?.let { h ->
        if (h.row < 0) stringResource(R.string.tbb_hint_take)
        else if (phase == BaseballPhase.PITCH) stringResource(R.string.tbb_hint_pitch, stringResource(typeNameRes(h.type)), h.row + 1, h.col + 1)
        else stringResource(R.string.tbb_hint_swing, stringResource(typeNameRes(h.type)), h.row + 1, h.col + 1, stringResource(if (h.power) R.string.tbb_power else R.string.tbb_contact))
    }

    val finishedGames = game.sessionWins.value.values.sum() + game.sessionDraws.value
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.over,
        onLeave = game::leaveSession,
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedGames == 0,
        onHint = if (isNetworked) null else ({ game.hint(if (hotSeat) actor.coerceAtLeast(0) else viewSeat) }),
        buttonFill = colors.bg, buttonContent = colors.text,
        leaveTitle = stringResource(R.string.tbb_leave_title),
        leaveBody = stringResource(if (finishedGames > 0) R.string.tbb_leave_body_session else R.string.tbb_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("toy-baseball", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val fieldContent: @Composable () -> Unit = {
                Box(Modifier.fillMaxWidth()) {
                    Diamond(s, colors, anim.value, last, reduced, TeamColors, bigHeight = if (landscape) (this@BoxWithConstraints.maxHeight - 120.dp).coerceIn(160.dp, 300.dp) else 210.dp)
                    Canvas(Modifier.matchParentSize()) { drawBurst(burst) }
                }
            }
            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("toy-baseball", openCoach)
                    Scoreboard(s, colors, ::seatName, Modifier.weight(1f))
                    if (!isNetworked && !s.over) {
                        OutlinedButton(
                            onClick = { game.hint(if (hotSeat) actor.coerceAtLeast(0) else viewSeat) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.ccl_hint)) }
                    }
                }
                CountLamps(s, colors)
                ClStatusLine(status, colors.text, Modifier.fillMaxWidth().padding(top = 2.dp), emphasized = true)
                ClWhyLine(whyText ?: hintText, colors.text, colors.card)
            }
            val panel: @Composable () -> Unit = {
                if (s.over) {
                    val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                    val title = when {
                        winnerSeat < 0 -> stringResource(R.string.ccl_a_draw)
                        hotSeat -> ctx.bbAnn(rawName(winnerSeat), R.string.tbb_win_short, R.string.tbb_win_short_you)
                        winnerSeat == viewSeat -> stringResource(R.string.ccl_you_won)
                        else -> stringResource(R.string.ccl_you_lost)
                    }
                    val session = s.players.joinToString(" · ") { "${ctx.seatLabel(it.displayName)} ${game.sessionWins.value[it.playerId] ?: 0}" }
                    ClFinishedPanel(
                        title = title, subtitle = stringResource(R.string.tbb_final_score, s.score[0], s.score[1]),
                        lines = listOf(stringResource(R.string.tbb_final_hits, s.hits[0], s.hits[1]), stringResource(R.string.ccl_session, session)),
                        canPlayAgain = !opponentLeft, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
                    )
                } else if (actor >= 0 && actorIsHuman && (hotSeat || actor == viewSeat) && animDone && !opponentLeft) {
                    val mine = actor
                    if (hotSeat && revealed != mine) {
                        HandoffCover(seatName(mine), colors) { revealed = mine }
                    } else if (phase == BaseballPhase.PITCH) {
                        PitchPicker(
                            colors = colors, type = pType, row = pRow, col = pCol, hint = hint,
                            onType = { pType = it }, onCell = { r, c -> pRow = r; pCol = c },
                            onThrow = {
                                val seatNow = mine
                                if (pRow < 0) { game.pitch(seatNow, pType, -1, -1) } else {
                                    val res = game.pitch(seatNow, pType, pRow, pCol)
                                    if (res == BaseballReject.NONE) revealed = -1
                                }
                            },
                            history = s.history, pitchedBy = s.pitcherSeat
                        )
                    } else {
                        SwingPicker(
                            colors = colors, timing = sTiming, row = sRow, col = sCol, power = sPower, hint = hint,
                            onTiming = { sTiming = it }, onCell = { r, c -> sRow = r; sCol = c }, onPower = { sPower = it },
                            onSwing = { if (game.swing(mine, BaseballSwing(false, sRow, sCol, sTiming, sPower)) == BaseballReject.NONE) revealed = -1 },
                            onTake = { if (game.swing(mine, BaseballSwing.TAKE) == BaseballReject.NONE) revealed = -1 },
                            history = s.history, pitchedBy = s.pitcherSeat
                        )
                    }
                } else if (!s.over && phase != BaseballPhase.OVER && animDone) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(colors.card).padding(12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (opponentLeft) stringResource(R.string.tbb_opp_left) else stringResource(R.string.tbb_wait_generic),
                            color = colors.text, textAlign = TextAlign.Center
                        )
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) { header(); fieldContent() }
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) { panel() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    header()
                    fieldContent()
                    Spacer(Modifier.height(8.dp))
                    panel()
                }
            }
        }
    }
}

// ---- scoreboard and count --------------------------------------------------------------------------

@Composable
private fun Scoreboard(s: BaseballState, colors: BbColors, seatName: (Int) -> String, modifier: Modifier) {
    val ctx = LocalContext.current
    val inningText = ctx.getString(if (s.top) R.string.tbb_inning_top else R.string.tbb_inning_bottom, s.inning.coerceAtMost(99))
    val away = if (s.top) s.batterSeat else s.pitcherSeat
    val rows = listOf(away, 1 - away)
    val shown = maxOf(s.innings, s.inning)
    val desc = ctx.getString(R.string.tbb_score_desc, seatName(rows[0]), s.score[rows[0]], seatName(rows[1]), s.score[rows[1]], inningText)
    Column(modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { contentDescription = desc }) {
        Text(inningText, color = colors.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        rows.forEach { seat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    if (seat % 2 == 0) drawCircle(TeamColors[seat], size.minDimension / 2, c)
                    else drawRect(TeamColors[seat], Offset.Zero, size)
                }
                Spacer(Modifier.width(4.dp))
                Text(seatName(seat), color = colors.text, fontSize = 11.sp, maxLines = 1, modifier = Modifier.widthIn(min = 40.dp, max = 90.dp))
                for (i in 0 until shown) {
                    val v = s.lineScore[seat].getOrNull(i)
                    val future = v == null || (i + 1 > s.inning)
                    Text(if (future) "·" else v.toString(), color = colors.text, fontSize = 11.sp, modifier = Modifier.width(14.dp), textAlign = TextAlign.Center)
                }
                Text(s.score[seat].toString(), color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
                Text(s.hits[seat].toString(), color = colors.text, fontSize = 11.sp, modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun CountLamps(s: BaseballState, colors: BbColors) {
    val desc = stringResource(R.string.tbb_count_desc, s.balls, s.strikes, s.outs)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        @Composable
        fun group(label: String, n: Int, max: Int, shape: Int) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                for (i in 0 until max) Canvas(Modifier.padding(horizontal = 2.dp).size(14.dp)) {
                    val on = i < n
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.minDimension / 2 - 1.5f
                    when (shape) {
                        0 -> { if (on) drawCircle(colors.text, r, c) else drawCircle(colors.text, r, c, style = Stroke(2.5f)) }
                        1 -> { if (on) drawRect(colors.text, c - Offset(r, r), Size(r * 2, r * 2)) else drawRect(colors.text, c - Offset(r, r), Size(r * 2, r * 2), style = Stroke(2.5f)) }
                        else -> {
                            val p = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y + r); lineTo(c.x - r, c.y + r); close() }
                            if (on) drawPath(p, colors.text) else drawPath(p, colors.text, style = Stroke(2.5f))
                        }
                    }
                }
            }
        }
        group(stringResource(R.string.tbb_lamp_b), s.balls, BaseballRules.BALLS_FOR_WALK - 1, 0)
        group(stringResource(R.string.tbb_lamp_s), s.strikes, BaseballRules.STRIKES_FOR_OUT - 1, 1)
        group(stringResource(R.string.tbb_lamp_o), s.outs, BaseballRules.OUTS_PER_HALF - 1, 2)
    }
}

// ---- the field ---------------------------------------------------------------------------------------

@Composable
private fun Diamond(
    s: BaseballState, colors: BbColors, t: Float, last: PitchRecord?, reduced: Boolean, teams: List<Color>, bigHeight: androidx.compose.ui.unit.Dp
) {
    val ctx = LocalContext.current
    val desc = ctx.getString(
        R.string.tbb_field_desc,
        ctx.getString(if (s.bases[0]) R.string.tbb_base_on else R.string.tbb_base_off),
        ctx.getString(if (s.bases[1]) R.string.tbb_base_on else R.string.tbb_base_off),
        ctx.getString(if (s.bases[2]) R.string.tbb_base_on else R.string.tbb_base_off)
    )
    Canvas(Modifier.fillMaxWidth().height(bigHeight).clip(RoundedCornerShape(18.dp)).semantics { contentDescription = desc }) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(colors.grass, clShade(colors.grass, -0.18f))))
        // Mowing stripes.
        for (i in 0 until 8) if (i % 2 == 0) drawRect(Color.White.copy(alpha = 0.05f), Offset(0f, h * i / 8f), Size(w, h / 8f))
        // Outfield wall.
        drawArc(Color(0xFF1E3A8A).copy(alpha = 0.85f), 200f, 140f, false, Offset(w * 0.04f, -h * 0.55f), Size(w * 0.92f, h * 1.3f), style = Stroke(h * 0.035f))
        val home = Offset(w * 0.5f, h * 0.86f)
        val first = Offset(w * 0.5f + w * 0.24f, h * 0.55f)
        val second = Offset(w * 0.5f, h * 0.25f)
        val third = Offset(w * 0.5f - w * 0.24f, h * 0.55f)
        val dirt = Path().apply { moveTo(home.x, home.y + h * 0.04f); lineTo(first.x + w * 0.04f, first.y); lineTo(second.x, second.y - h * 0.04f); lineTo(third.x - w * 0.04f, third.y); close() }
        drawPath(dirt, colors.dirt)
        val grassInfield = Path().apply { moveTo(home.x, home.y - h * 0.08f); lineTo(first.x - w * 0.05f, first.y); lineTo(second.x, second.y + h * 0.08f); lineTo(third.x + w * 0.05f, third.y); close() }
        drawPath(grassInfield, colors.grass)
        drawLine(Color.White.copy(alpha = 0.8f), home, Offset(w * 1.05f, home.y - (w * 0.55f)), 3f)
        drawLine(Color.White.copy(alpha = 0.8f), home, Offset(-w * 0.05f, home.y - (w * 0.55f)), 3f)
        // Mound and plate.
        val mound = Offset(w * 0.5f, h * 0.56f)
        drawOval(colors.dirt, mound - Offset(w * 0.05f, h * 0.03f), Size(w * 0.1f, h * 0.06f))
        drawRect(Color.White, mound - Offset(w * 0.015f, 2f), Size(w * 0.03f, 4f))
        // Bases: white squares; occupied ones carry a runner (the batting team's colour AND badge).
        val batSeat = s.batterSeat
        listOf(first to s.bases[0], second to s.bases[1], third to s.bases[2]).forEach { (p, on) ->
            rotate(45f, p) { drawRect(Color.White, p - Offset(w * 0.025f, w * 0.025f), Size(w * 0.05f, w * 0.05f)) }
            if (on) {
                val c = p + Offset(0f, -h * 0.05f)
                drawCircle(Color.Black.copy(alpha = 0.25f), w * 0.034f, c + Offset(2f, 4f))
                drawCircle(teams[batSeat], w * 0.032f, c)
                if (batSeat % 2 == 0) drawCircle(Color.White, w * 0.012f, c) else drawRect(Color.White, c - Offset(w * 0.011f, w * 0.011f), Size(w * 0.022f, w * 0.022f))
                drawCircle(Color.Black, w * 0.032f, c, style = Stroke(2f))
            }
        }
        val plate = Path().apply {
            moveTo(home.x - w * 0.02f, home.y - h * 0.01f); lineTo(home.x + w * 0.02f, home.y - h * 0.01f)
            lineTo(home.x + w * 0.02f, home.y + h * 0.015f); lineTo(home.x, home.y + h * 0.035f); lineTo(home.x - w * 0.02f, home.y + h * 0.015f); close()
        }
        drawPath(plate, Color.White)
        // Pitcher and batter: little toy figures.
        fun figure(c: Offset, color: Color, badge: Int, scale: Float) {
            drawOval(Color.Black.copy(alpha = 0.22f), c + Offset(-w * 0.03f * scale, h * 0.035f * scale), Size(w * 0.06f * scale, h * 0.03f * scale))
            drawRoundRect(color, c + Offset(-w * 0.022f * scale, -h * 0.02f * scale), Size(w * 0.044f * scale, h * 0.06f * scale), androidx.compose.ui.geometry.CornerRadius(w * 0.015f * scale))
            if (badge % 2 == 0) drawCircle(Color.White, w * 0.01f * scale, c + Offset(0f, h * 0.012f * scale)) else drawRect(Color.White, c + Offset(-w * 0.009f * scale, h * 0.004f * scale), Size(w * 0.018f * scale, w * 0.018f * scale))
            drawCircle(Color(0xFFF0C9A0), w * 0.02f * scale, c + Offset(0f, -h * 0.045f * scale))
            drawArc(color, 180f, 180f, true, c + Offset(-w * 0.021f * scale, -h * 0.066f * scale), Size(w * 0.042f * scale, h * 0.03f * scale))
        }
        figure(mound + Offset(0f, -h * 0.02f), teams[s.pitcherSeat], s.pitcherSeat, 1f)
        val batterPos = home + Offset(-w * 0.07f, -h * 0.01f)
        figure(batterPos, teams[batSeat], batSeat, 1.15f)
        // The bat: resting on the shoulder; swings through the pitch (early or late depending on the timing guess).
        val swung = last?.swung == true && t < 1f
        val timingErr = last?.let { ((typeFor(it.timing)?.speed ?: 0) - (typeFor(it.type)?.speed ?: 0)) } ?: 0
        val swingStart = 0.38f + 0.07f * timingErr
        val swingT = if (swung || (last?.swung == true && t >= 1f)) ((t - swingStart) / 0.12f).coerceIn(0f, 1f) else 0f
        val ang = if (reduced) (if (last?.swung == true && t >= 0.5f) 40f else -60f) else -60f + 170f * swingT
        rotate(ang, batterPos + Offset(0f, -h * 0.02f)) {
            drawLine(Color(0xFFB07A3C), batterPos + Offset(0f, -h * 0.02f), batterPos + Offset(w * 0.0f, -h * 0.02f - h * 0.13f), w * 0.012f, StrokeCap.Round)
        }
        // The ball.
        if (last != null) {
            val type = typeFor(last.type)
            val pitchShare = 0.34f + 0.05f * (type?.speed ?: 0)
            val ballPos: Offset?
            if (t < pitchShare) {
                val p = t / pitchShare
                val zoneX = home.x + (last.col - 2) * w * 0.012f
                ballPos = Offset(mound.x + (zoneX - mound.x) * p, mound.y + (home.y - mound.y) * p + sin(p * PI.toFloat()) * (type?.movement ?: 0) * 0.6f)
            } else if (t >= pitchShare) {
                val p = ((t - pitchShare) / (1f - pitchShare)).coerceIn(0f, 1f)
                val target: Offset; val arc: Float
                when (last.outcome) {
                    BaseballOutcome.HOME_RUN -> { target = Offset(w * 0.5f + w * 0.12f, -h * 0.08f); arc = h * 0.5f }
                    BaseballOutcome.DOUBLE -> { target = Offset(w * 0.5f + w * 0.3f, h * 0.12f); arc = h * 0.25f }
                    BaseballOutcome.SINGLE -> { target = Offset(w * 0.5f - w * 0.1f, h * 0.38f); arc = h * 0.05f }
                    BaseballOutcome.SAC_FLY, BaseballOutcome.FLY_OUT -> { target = Offset(w * 0.5f + w * 0.08f, h * 0.18f); arc = h * 0.4f }
                    BaseballOutcome.LINE_OUT -> { target = Offset(w * 0.5f - w * 0.2f, h * 0.42f); arc = h * 0.04f }
                    BaseballOutcome.GROUND_OUT, BaseballOutcome.DOUBLE_PLAY -> { target = Offset(w * 0.5f - w * 0.17f, h * 0.6f); arc = h * 0.0f }
                    BaseballOutcome.FOUL -> { target = Offset(w * 0.06f, h * 0.6f); arc = h * 0.2f }
                    else -> { target = home + Offset(0f, h * 0.1f); arc = 0f }
                }
                ballPos = Offset(home.x + (target.x - home.x) * p, home.y + (target.y - home.y) * p - sin(p * PI.toFloat()) * arc)
            } else ballPos = null
            if (ballPos != null && t < 1f) {
                drawCircle(Color.Black.copy(alpha = 0.2f), w * 0.016f, ballPos + Offset(3f, 5f))
                drawCircle(Color.White, w * 0.016f, ballPos)
                drawCircle(Color(0xFFC62828), w * 0.016f, ballPos, style = Stroke(2f))
            }
        }
    }
}

private fun typeFor(name: String): PitchType? = PitchType.entries.firstOrNull { it.name == name }

// ---- the choosing panels --------------------------------------------------------------------------------

@Composable
private fun HandoffCover(name: String, colors: BbColors, onReady: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.card).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.tbb_pass_to, name), color = colors.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(stringResource(R.string.tbb_pass_body), color = colors.text, textAlign = TextAlign.Center)
        Button(onClick = onReady, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.tbb_pass_ready, name)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(colors: BbColors, current: PitchType, onPick: (PitchType) -> Unit, hintType: PitchType?) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PitchType.entries.forEach { t ->
            val sel = t == current
            val name = stringResource(typeNameRes(t))
            val desc = stringResource(typeDescRes(t)) + " " + stringResource(R.string.tbb_speed, t.speed + 1)
            Column(
                Modifier.widthIn(min = 72.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (sel) colors.accent else colors.card)
                    .border(if (hintType == t) 4.dp else 2.dp, if (hintType == t) colors.gold else if (sel) colors.text else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { onPick(t) }
                    .semantics(mergeDescendants = true) { role = Role.RadioButton; selected = sel; contentDescription = desc }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val ink = if (sel) (if (colors.bg.luminance() < 0.5f) Color(0xFF101014) else Color.White) else colors.text
                Canvas(Modifier.size(width = 40.dp, height = 20.dp)) { drawTypeGlyph(t, ink) }
                Text(name, color = ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                // Speed as 4 pips: faster = more filled.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (i in 0 until 4) Box(Modifier.size(width = 8.dp, height = 4.dp).background(if (i < 4 - t.speed) ink else ink.copy(alpha = 0.25f)))
                }
            }
        }
    }
}

private fun DrawScope.drawTypeGlyph(t: PitchType, ink: Color) {
    val w = size.width; val h = size.height
    val st = h * 0.14f
    when (t) {
        PitchType.FASTBALL -> { drawLine(ink, Offset(0f, h / 2), Offset(w, h / 2), st, StrokeCap.Round); drawLine(ink, Offset(w, h / 2), Offset(w * 0.8f, h * 0.2f), st, StrokeCap.Round); drawLine(ink, Offset(w, h / 2), Offset(w * 0.8f, h * 0.8f), st, StrokeCap.Round) }
        PitchType.SLIDER -> { drawLine(ink, Offset(0f, h * 0.15f), Offset(w, h * 0.85f), st, StrokeCap.Round); drawCircle(ink, h * 0.16f, Offset(w, h * 0.85f)) }
        PitchType.CURVEBALL -> { val p = Path().apply { moveTo(0f, h * 0.15f); cubicTo(w * 0.5f, h * 0.0f, w * 0.9f, h * 0.2f, w, h * 0.9f) }; drawPath(p, ink, style = Stroke(st, cap = StrokeCap.Round)) }
        PitchType.CHANGEUP -> { var x = 0f; while (x < w) { drawCircle(ink, h * 0.1f, Offset(x + h * 0.1f, h / 2)); x += w / 6f } ; drawCircle(ink, h * 0.2f, Offset(w, h / 2)) }
    }
}

@Composable
private fun CellGrid(
    colors: BbColors, n: Int, zoneFrom: Int, selRow: Int, selCol: Int, hinted: Pair<Int, Int>?, history: List<PitchRecord>, showHistory: Boolean,
    descFor: @Composable (Int, Int) -> String, onCell: (Int, Int) -> Unit
) {
    val cell = 52.dp
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.card).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (r in 0 until n) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (c in 0 until n) {
                val inZone = n == 3 || BaseballRules.inZone(r, c)
                val sel = r == selRow && c == selCol
                val isHint = hinted == (r to c)
                val dots = if (showHistory) history.withIndex().filter { (_, h) -> h.row == r && h.col == c }.map { it.index } else emptyList()
                val d = descFor(r, c)
                Box(
                    Modifier.size(cell).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) colors.accent else if (inZone) colors.zone.copy(alpha = 0.9f) else colors.text.copy(alpha = 0.08f))
                        .border(if (isHint) 4.dp else if (inZone) 2.dp else 1.dp, if (isHint) colors.gold else colors.text.copy(alpha = if (inZone) 0.55f else 0.2f), RoundedCornerShape(8.dp))
                        .clickable { onCell(r, c) }
                        .semantics { role = Role.Button; contentDescription = d; selected = sel },
                    contentAlignment = Alignment.Center
                ) {
                    if (sel) Canvas(Modifier.size(22.dp)) { drawCircle(Color.White, size.minDimension / 2, center); drawCircle(Color(0xFFC62828), size.minDimension / 2, center, style = Stroke(3f)) }
                    if (dots.isNotEmpty()) Text(dots.last().plus(1).toString(), color = if (sel) Color.White else Color(0xFF6A1B1B), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 30.dp, start = 28.dp))
                }
            }
        }
    }
}

@Composable
private fun PitchPicker(
    colors: BbColors, type: PitchType, row: Int, col: Int, hint: com.gamesuite.games.toybaseball.BaseballHint?,
    onType: (PitchType) -> Unit, onCell: (Int, Int) -> Unit, onThrow: () -> Unit, history: List<PitchRecord>, pitchedBy: Int
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tbb_pick_pitch_title), color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        TypeChips(colors, type, onType, hint?.takeIf { it.row >= 0 }?.type)
        Text(stringResource(R.string.tbb_pick_loc), color = colors.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        CellGrid(
            colors, BaseballRules.GRID, 1, row, col, hint?.takeIf { it.row >= 0 }?.let { it.row to it.col },
            history.filter { it.batterSeat != pitchedBy }, true,
            descFor = { r, c ->
                var text = ctx.getString(if (BaseballRules.inZone(r, c)) R.string.tbb_cell_zone else R.string.tbb_cell_ball, r + 1, c + 1)
                history.forEachIndexed { i, h ->
                    if (h.row == r && h.col == c) text += " " + ctx.getString(R.string.tbb_cell_before, i + 1, ctx.getString(typeNameRes(typeFor(h.type) ?: PitchType.FASTBALL)))
                }
                text
            },
            onCell = onCell
        )
        Button(onClick = onThrow, enabled = row >= 0, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (row >= 0) stringResource(R.string.tbb_throw) else stringResource(R.string.tbb_pick_spot_first))
        }
    }
}

@Composable
private fun SwingPicker(
    colors: BbColors, timing: PitchType, row: Int, col: Int, power: Boolean, hint: com.gamesuite.games.toybaseball.BaseballHint?,
    onTiming: (PitchType) -> Unit, onCell: (Int, Int) -> Unit, onPower: (Boolean) -> Unit, onSwing: () -> Unit, onTake: () -> Unit,
    history: List<PitchRecord>, pitchedBy: Int
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tbb_pick_swing_title), color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tbb_ready_for), color = colors.text, style = MaterialTheme.typography.bodySmall)
        TypeChips(colors, timing, onTiming, hint?.takeIf { it.row >= 0 }?.type)
        Text(stringResource(R.string.tbb_aim), color = colors.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        CellGrid(
            colors, 3, 0, row, col, hint?.takeIf { it.row >= 0 }?.let { it.row to it.col }, emptyList(), false,
            descFor = { r, c -> stringResource(R.string.tbb_aim_cell, r + 1, c + 1) }, onCell = onCell
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false, true).forEach { p ->
                val sel = power == p
                OutlinedButton(
                    onClick = { onPower(p) },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (sel) colors.accent else Color.Transparent, contentColor = if (sel) (if (colors.bg.luminance() < 0.5f) Color(0xFF101014) else Color.White) else colors.text),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton; selected = sel }
                ) { Text(stringResource(if (p) R.string.tbb_power else R.string.tbb_contact)) }
            }
        }
        Text(stringResource(if (power) R.string.tbb_power_help else R.string.tbb_contact_help), color = colors.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSwing, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.tbb_swing)) }
            OutlinedButton(
                onClick = onTake, colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp)
            ) { Text(stringResource(R.string.tbb_take)) }
        }
        // What the pitcher has thrown so far: the batter's reading material.
        val recent = history.filter { it.batterSeat != pitchedBy || true }.takeLast(6)
        if (recent.isNotEmpty()) {
            val ctx = LocalContext.current
            val listText = recent.joinToString("; ") {
                ctx.getString(R.string.tbb_chart_item, ctx.getString(typeNameRes(typeFor(it.type) ?: PitchType.FASTBALL)), it.row + 1, it.col + 1, ctx.getString(outcomeRes(it.outcome)))
            }
            Text(
                stringResource(R.string.tbb_recent, listText), color = colors.text, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = listText }
            )
        }
    }
}
