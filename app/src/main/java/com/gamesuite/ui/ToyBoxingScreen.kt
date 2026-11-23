package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.toyboxing.BoxFighter
import com.gamesuite.games.toyboxing.BoxingAction
import com.gamesuite.games.toyboxing.BoxingEvent
import com.gamesuite.games.toyboxing.BoxingMove
import com.gamesuite.games.toyboxing.BoxingPhase
import com.gamesuite.games.toyboxing.BoxingReject
import com.gamesuite.games.toyboxing.BoxingRules
import com.gamesuite.games.toyboxing.BoxingState
import com.gamesuite.games.toyboxing.ToyBoxingGame
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
import kotlin.math.ceil
import kotlin.math.sin

private class BoxColors(val bg: Color, val text: Color, val card: Color, val accent: Color, val gold: Color, val good: Color, val bad: Color)

private fun boxColors(dark: Boolean) = if (!dark) BoxColors(
    bg = Color(0xFFF3EEE8), text = Color(0xFF2A2420), card = Color(0xFFE4DAD0), accent = Color(0xFF9A3412),
    gold = Color(0xFFD99A00), good = Color(0xFF1B7F4F), bad = Color(0xFFB3261E)
) else BoxColors(
    bg = Color(0xFF16171C), text = Color(0xFFEFE9DF), card = Color(0xFF262831), accent = Color(0xFFFB8A5C),
    gold = Color(0xFFFFC83D), good = Color(0xFF4CD08C), bad = Color(0xFFFF7A70)
)

private fun Context.boxAnn(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

@Composable
private fun rememberFrameMs(): State<Long> {
    val t = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameMillis { t.value = it }
    }
    return t
}

private class Popup(val id: Int, val text: String, val seat: Int, val good: Boolean)

/**
 * Toy Boxing: two wind-up toy boxers in a ring. In real time each player has pads on their own side: JAB (quick),
 * HOOK (slow, big, with a visible "!" tell), BLOCK (press and hold) and DODGE (time it against a hook). A jab
 * cancels a winding-up punch; mashing runs out of stamina. When a boxer is down, tap fast to get up before the
 * ten count. Turn-based assist swaps the pads for choosing a punch and then a defence, with no clock.
 * Colour-blind safe (a badge shape and a name tag on each boxer), Reduced-Motion aware, TalkBack-friendly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToyBoxingScreen(
    sessionManager: GameSessionManager,
    game: ToyBoxingGame,
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
    val hintAction by game.hintAction
    val lastReject by game.lastReject
    val opponentLeft by game.opponentLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val colors = boxColors(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val burst = rememberParticleBurst()
    val frame = rememberFrameMs()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.assist = extras["assist"] == "true"
        game.rounds = extras["rounds"]?.toIntOrNull() ?: BoxingRules.DEFAULT_ROUNDS
        game.knockouts = extras["knockouts"] != "false"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    // The clock: the fight simulation and the computer's reactions run from here.
    LaunchedEffect(game) {
        while (true) {
            game.tick()
            delay(35)
        }
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val helpTitle = stringResource(R.string.tbx_help_title)
    val helpText = stringResource(R.string.tbx_help_text)
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

    val bottomSeat = if (isNetworked) localSeat.coerceIn(0, 1) else 0
    val hotSeat = !isNetworked && s.players.none { it.isBot }
    fun seatName(i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    fun rawName(i: Int) = s.players.getOrNull(i)?.displayName ?: ""
    val phase = s.phaseValue

    // ---- effects keyed by the engine's event serial ----
    val shake = remember { Animatable(0f) }
    val flash = remember { listOf(Animatable(0f), Animatable(0f)) }
    var popups by remember { mutableStateOf(listOf<Popup>()) }
    var popupId by remember { mutableIntStateOf(0) }
    var goFlash by remember { mutableStateOf(false) }
    var goAt by remember { mutableIntStateOf(0) }
    var whyKey by remember { mutableStateOf<Pair<BoxingReject, Int>?>(null) }
    var pressed by remember { mutableStateOf(setOf<String>()) } // "seat:ACTION" while a pad is held

    LaunchedEffect(s.eventSerial) {
        if (s.eventSerial == 0) return@LaunchedEffect
        val seat = s.eventSeat
        val victim = if (seat in 0..1) 1 - seat else -1
        fun pop(text: String, at: Int, good: Boolean) {
            popupId++
            popups = (popups + Popup(popupId, text, at, good)).takeLast(4)
        }
        when (s.event) {
            BoxingEvent.JAB_HIT -> {
                playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION)
                if (victim >= 0) { pop("-${s.eventAmount}", victim, false); if (!reduced) { flash[victim].snapTo(1f); flash[victim].animateTo(0f, tween(320)) } }
                if (!reduced) { shake.snapTo(1f); shake.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium)) }
            }
            BoxingEvent.HOOK_HIT, BoxingEvent.GUARD_BREAK -> {
                playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.STRONG_ACTION)
                if (victim >= 0) { pop("-${s.eventAmount}", victim, false); if (!reduced) { flash[victim].snapTo(1f); flash[victim].animateTo(0f, tween(480)) } }
                if (!reduced) { shake.snapTo(2.2f); shake.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium)) }
            }
            BoxingEvent.BLOCKED -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK); if (victim >= 0) pop("-${s.eventAmount}", victim, true) }
            BoxingEvent.DODGED -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
            BoxingEvent.KNOCKDOWN -> {
                playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.ESCALATING)
                if (!reduced) { shake.snapTo(3f); shake.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessLow)) }
            }
            BoxingEvent.COUNT -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
            BoxingEvent.GET_UP -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.NORMAL_ACTION) }
            BoxingEvent.ROUND_START -> {
                playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.STRONG_ACTION)
                goAt = s.eventSerial
            }
            BoxingEvent.ROUND_END -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
            BoxingEvent.ANNOUNCE -> { playSfx(SfxKind.LIGHT_TICK) }
            BoxingEvent.KO, BoxingEvent.TKO, BoxingEvent.DECISION, BoxingEvent.DRAW -> {
                val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                val localWon = winnerSeat >= 0 && (hotSeat || winnerSeat == bottomSeat)
                if (localWon) {
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                    if (!reduced) burst.spawn(Offset(0.5f, 0.35f), 90, listOf(colors.gold, Color.White, colors.accent, Color(0xFF56B4E9)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
                } else if (winnerSeat >= 0) {
                    playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
                }
            }
            else -> {}
        }
    }
    LaunchedEffect(goAt) {
        if (goAt != 0) { goFlash = true; delay(gameSpeed.scale(800L)); goFlash = false }
    }
    LaunchedEffect(popups) {
        if (popups.isNotEmpty()) {
            delay(gameSpeed.scale(900L))
            popups = popups.drop(1)
        }
    }
    LaunchedEffect(lastReject?.second) {
        val r = lastReject ?: return@LaunchedEffect
        whyKey = r
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
        delay(2800)
        if (whyKey?.second == r.second) whyKey = null
    }

    // ---- words ----
    fun moveWord(name: String) = if (name == BoxingMove.HOOK.name) androidContext.getString(R.string.tbx_move_hook) else androidContext.getString(R.string.tbx_move_jab)
    val status: String = run {
        val who = rawName(s.eventSeat.coerceAtLeast(0))
        val ev: String = when (s.event) {
            BoxingEvent.JAB_HIT -> androidContext.boxAnn(who, R.string.tbx_ann_jab, R.string.tbx_ann_jab_you, s.eventAmount)
            BoxingEvent.HOOK_HIT -> androidContext.boxAnn(who, R.string.tbx_ann_hook, R.string.tbx_ann_hook_you, s.eventAmount)
            BoxingEvent.BLOCKED -> androidContext.boxAnn(who, R.string.tbx_ann_blocked, R.string.tbx_ann_blocked_you)
            BoxingEvent.DODGED -> androidContext.boxAnn(who, R.string.tbx_ann_dodged, R.string.tbx_ann_dodged_you)
            BoxingEvent.GUARD_BREAK -> androidContext.boxAnn(who, R.string.tbx_ann_guard_break, R.string.tbx_ann_guard_break_you)
            BoxingEvent.KNOCKDOWN -> androidContext.boxAnn(who, R.string.tbx_ann_knockdown, R.string.tbx_ann_knockdown_you)
            BoxingEvent.COUNT -> androidContext.getString(R.string.tbx_ann_count, s.eventAmount)
            BoxingEvent.GET_UP -> androidContext.boxAnn(who, R.string.tbx_ann_get_up, R.string.tbx_ann_get_up_you)
            BoxingEvent.ROUND_START -> androidContext.getString(R.string.tbx_ann_fight)
            BoxingEvent.ROUND_END -> androidContext.getString(R.string.tbx_ann_round_end, s.eventAmount)
            BoxingEvent.KO -> androidContext.boxAnn(who, R.string.tbx_ann_ko, R.string.tbx_ann_ko_you)
            BoxingEvent.TKO -> androidContext.boxAnn(who, R.string.tbx_ann_tko, R.string.tbx_ann_tko_you)
            BoxingEvent.DECISION -> androidContext.boxAnn(who, R.string.tbx_ann_decision, R.string.tbx_ann_decision_you)
            BoxingEvent.DRAW -> androidContext.getString(R.string.tbx_ann_draw)
            BoxingEvent.ANNOUNCE -> androidContext.boxAnn(who, R.string.tbx_ann_announce, R.string.tbx_ann_announce_you, moveWord(s.assistPending))
            BoxingEvent.REST -> androidContext.boxAnn(who, R.string.tbx_ann_rest, R.string.tbx_ann_rest_you)
            else -> androidContext.getString(R.string.tbx_ann_get_ready, s.round)
        }
        val turn: String = when {
            s.over -> ""
            opponentLeft -> androidContext.getString(R.string.tbx_opp_left)
            s.assist && phase == BoxingPhase.FIGHT -> {
                val actor = s.assistActor
                val defender = 1 - actor
                val mine = if (hotSeat) -1 else bottomSeat
                when {
                    s.assistStage == "ATTACK" && actor == mine -> androidContext.getString(R.string.tbx_turn_attack_you)
                    s.assistStage == "ATTACK" -> androidContext.getString(R.string.tbx_turn_attack_named, seatName(actor))
                    defender == mine -> androidContext.getString(R.string.tbx_turn_defend_you)
                    else -> androidContext.getString(R.string.tbx_turn_defend_named, seatName(defender))
                }
            }
            else -> ""
        }
        listOf(ev, turn).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText: String? = whyKey?.first?.let { r ->
        when (r) {
            BoxingReject.BUSY -> stringResource(R.string.tbx_why_busy)
            BoxingReject.STUNNED -> stringResource(R.string.tbx_why_stunned)
            BoxingReject.EXHAUSTED -> stringResource(R.string.tbx_why_exhausted)
            BoxingReject.NOT_YOUR_TURN -> stringResource(R.string.tbx_why_not_turn)
            BoxingReject.NEUTRAL_CORNER -> stringResource(R.string.tbx_why_corner)
            BoxingReject.NOT_STARTED -> stringResource(R.string.tbx_why_not_started)
            BoxingReject.NOT_PLAYING -> stringResource(R.string.tbx_why_over)
            BoxingReject.NOT_YOUR_SEAT -> stringResource(if (opponentLeft) R.string.tbx_opp_left else R.string.tbx_why_not_turn)
            BoxingReject.BAD_STAGE -> stringResource(R.string.tbx_why_stage)
            BoxingReject.NONE -> null
        }
    }
    val hintText: String? = hintAction?.let { stringResource(R.string.tbx_hint_say, actionLabel(it)) }

    val finishedBouts = game.sessionWins.value.values.sum() + game.sessionDraws.value
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.over,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBouts > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBouts == 0,
        onHint = if (isNetworked) null else ({ game.hint(bottomSeat) }),
        buttonFill = colors.bg, buttonContent = colors.text,
        leaveTitle = stringResource(R.string.tbx_leave_title),
        leaveBody = stringResource(if (finishedBouts > 0) R.string.tbx_leave_body_session else R.string.tbx_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("toy-boxing", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val sideBySide = landscape && hotSeat && !s.assist
            val padsBlock = if (hotSeat) 2 else 1
            val reserved = if (landscape && !sideBySide) 250.dp else if (landscape) 150.dp else 170.dp + 104.dp * padsBlock
            // When the bout is over the result panel (title, summary, three buttons) takes the pads' place: a shorter ring keeps its buttons on screen.
            val ringH: Dp = (maxHeight - reserved - (if (s.over && !landscape) 170.dp else 0.dp)).coerceIn(150.dp, 440.dp)

            val ringContent: @Composable () -> Unit = {
                BoxingRing(
                    s = s, ringH = ringH, frame = frame, shake = { shake.value }, flash = { i -> flash[i].value },
                    reduced = reduced, dark = dark, colors = colors, accent = colors.accent, popups = popups,
                    seatName = ::seatName, bottomSeat = bottomSeat, hot = hotSeat, goFlash = goFlash, burstContent = { Canvas(Modifier.fillMaxSize()) { drawBurst(burst) } }
                )
            }
            val hudContent: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (seat in listOf(0, 1)) {
                        FighterHud(
                            seat = seat, name = seatName(seat) + if (isNetworked && seat == localSeat) " " + stringResource(R.string.ccl_you_suffix) else "",
                            bot = s.players[seat].isBot, f = s.fighters[seat], colors = colors, modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            val headerContent: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("toy-boxing", openCoach)
                    RoundBanner(s, colors, Modifier.weight(1f))
                    if (!isNetworked && !s.over) {
                        OutlinedButton(
                            onClick = { game.hint(bottomSeat) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.ccl_hint)) }
                    }
                }
                ClStatusLine(status, colors.text, Modifier.fillMaxWidth().padding(top = 2.dp), emphasized = true)
                ClWhyLine(whyText ?: hintText, colors.text, colors.card)
            }
            val finishedContent: @Composable () -> Unit = {
                if (s.over) {
                    val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                    val title = when {
                        winnerSeat < 0 -> stringResource(R.string.ccl_a_draw)
                        hotSeat -> androidContext.boxAnn(rawName(winnerSeat), R.string.tbx_ann_decision, R.string.tbx_ann_decision_you, "").trim()
                        winnerSeat == bottomSeat -> stringResource(R.string.ccl_you_won)
                        else -> stringResource(R.string.ccl_you_lost)
                    }
                    val how = when (s.event) {
                        BoxingEvent.KO -> stringResource(R.string.tbx_how_ko)
                        BoxingEvent.TKO -> stringResource(R.string.tbx_how_tko)
                        BoxingEvent.DECISION -> stringResource(R.string.tbx_how_points)
                        else -> null
                    }
                    val session = s.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)} ${game.sessionWins.value[it.playerId] ?: 0}" }
                    Spacer(Modifier.height(8.dp))
                    ClFinishedPanel(
                        title = title, subtitle = how,
                        lines = listOf(
                            stringResource(R.string.tbx_summary, s.fighters[0].roundsWon, s.fighters[1].roundsWon, s.fighters[0].totalPoints, s.fighters[1].totalPoints),
                            stringResource(R.string.ccl_session, session)
                        ),
                        canPlayAgain = !opponentLeft,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                }
            }

            fun press(seat: Int, a: BoxingAction) {
                pressed = pressed + "$seat:${a.name}"
                game.play(seat, a)
            }
            fun release(seat: Int, a: BoxingAction) {
                pressed = pressed - "$seat:${a.name}"
                if (a == BoxingAction.BLOCK) game.play(seat, BoxingAction.GUARD_DOWN)
            }
            val controls: @Composable (Int, Boolean, Boolean) -> Unit = { seat, rotated, vertical ->
                BoxingControls(
                    s = s, seat = seat, rotated = rotated, vertical = vertical, colors = colors, hint = hintAction,
                    pressed = pressed, onPress = ::press, onRelease = ::release,
                    onToggleGuard = { game.play(seat, if (s.fighters[seat].guard) BoxingAction.GUARD_DOWN else BoxingAction.BLOCK) },
                    onAct = { a -> game.play(seat, a) },
                    seatLabel = seatName(seat)
                )
            }

            if (sideBySide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(190.dp).fillMaxSize(), contentAlignment = Alignment.Center) { controls(0, false, true) }
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                        headerContent(); hudContent(); Spacer(Modifier.height(6.dp)); ringContent(); finishedContent()
                    }
                    Box(Modifier.width(190.dp).fillMaxSize(), contentAlignment = Alignment.Center) { controls(1, false, true) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    headerContent()
                    if (hotSeat) controls(1, true, false)
                    hudContent()
                    Spacer(Modifier.height(6.dp))
                    ringContent()
                    Spacer(Modifier.height(6.dp))
                    controls(bottomSeat, false, false)
                    finishedContent()
                }
            }
        }
    }
}

@Composable
private fun actionLabel(a: BoxingAction): String = stringResource(
    when (a) {
        BoxingAction.JAB -> R.string.tbx_pad_jab
        BoxingAction.HOOK -> R.string.tbx_pad_hook
        BoxingAction.BLOCK, BoxingAction.GUARD_DOWN -> R.string.tbx_pad_block
        BoxingAction.DODGE -> R.string.tbx_pad_dodge
        BoxingAction.REST -> R.string.tbx_pad_rest
        BoxingAction.TAP -> R.string.tbx_pad_getup
    }
)

@Composable
private fun RoundBanner(s: BoxingState, colors: BoxColors, modifier: Modifier) {
    val secs = ceil(s.roundLeftMs / 1000.0).toInt()
    val text = if (s.assist) stringResource(R.string.tbx_hud_round_turns, s.round, s.rounds, s.exchangesLeft)
    else stringResource(R.string.tbx_hud_round_time, s.round, s.rounds, secs / 60, secs % 60)
    Text(
        text, color = colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun FighterHud(seat: Int, name: String, bot: Boolean, f: BoxFighter, colors: BoxColors, modifier: Modifier) {
    val ctx = LocalContext.current
    val stateWord = ctx.getString(
        when {
            f.action == "DOWN" -> R.string.tbx_state_down
            f.action == "STAGGER" -> R.string.tbx_state_stagger
            f.action == "HOOK" -> R.string.tbx_state_hook
            f.action == "JAB" -> R.string.tbx_state_jab
            f.action == "DODGE" -> R.string.tbx_state_dodge
            f.guard -> R.string.tbx_state_guard
            else -> R.string.tbx_state_ready
        }
    )
    val desc = stringResource(R.string.tbx_fighter_desc, name, f.hp, f.stamina, f.roundsWon, f.points, stateWord) + if (bot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(colors.card).padding(8.dp).semantics(mergeDescendants = true) { contentDescription = desc }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(20.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(BoxerColors[seat], size.minDimension * 0.5f, c)
                if (seat % 2 == 0) drawCircle(Color.White, size.minDimension * 0.22f, c)
                else drawRect(Color.White, c - Offset(size.minDimension * 0.2f, size.minDimension * 0.2f), androidx.compose.ui.geometry.Size(size.minDimension * 0.4f, size.minDimension * 0.4f))
            }
            Spacer(Modifier.width(6.dp))
            Text(name, color = colors.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            for (i in 0 until f.roundsWon) Box(Modifier.padding(start = 3.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.gold))
        }
        Spacer(Modifier.height(4.dp))
        Meter(f.hp / BoxingRules.MAX_HP.toFloat(), if (f.hp > 35) colors.good else colors.bad, stringResource(R.string.tbx_hp, f.hp), colors, 18.dp, striped = f.hp <= 35)
        Spacer(Modifier.height(3.dp))
        Meter(f.stamina / BoxingRules.MAX_STAMINA.toFloat(), colors.gold, stringResource(R.string.tbx_stamina, f.stamina), colors, 9.dp, striped = f.stamina < BoxingRules.TIRED_BELOW)
    }
}

/** A bar with its value written on it; low values are striped as well as recoloured (never colour alone). */
@Composable
private fun Meter(fraction: Float, fill: Color, label: String, colors: BoxColors, height: Dp, striped: Boolean) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(colors.text.copy(alpha = 0.14f))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width * fraction.coerceIn(0f, 1f)
            drawRect(fill, Offset.Zero, androidx.compose.ui.geometry.Size(w, size.height))
            if (striped) {
                var x = 0f
                while (x < w) {
                    drawLine(Color.Black.copy(alpha = 0.28f), Offset(x, size.height), Offset(x + size.height, 0f), size.height * 0.22f)
                    x += size.height * 0.9f
                }
            }
        }
        // 11sp on a line of 13sp fits the 18dp bar (10sp on the default 16sp line was cut in half) and the shadow keeps it readable over the empty part of the track.
        if (height >= 14.dp) Text(label, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), Offset(1f, 1f), 3f)),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
    }
    if (height < 14.dp) Text(label, color = colors.text, fontSize = 10.sp, modifier = Modifier.padding(start = 2.dp))
}

@Composable
private fun BoxingRing(
    s: BoxingState, ringH: Dp, frame: State<Long>, shake: () -> Float, flash: (Int) -> Float, reduced: Boolean, dark: Boolean,
    colors: BoxColors, accent: Color, popups: List<Popup>, seatName: (Int) -> String, bottomSeat: Int, hot: Boolean,
    goFlash: Boolean, burstContent: @Composable () -> Unit
) {
    val commitFrame = remember(s) { Snapshot.withoutReadObservation { frame.value } }
    val ctx = LocalContext.current
    val ringDesc = ctx.getString(R.string.tbx_ring_desc, seatName(0), s.fighters[0].hp, seatName(1), s.fighters[1].hp)
    Box(Modifier.fillMaxWidth().height(ringH).clip(RoundedCornerShape(18.dp)).semantics { contentDescription = ringDesc }) {
        Canvas(Modifier.fillMaxSize()) {
            val frameMs = frame.value
            drawRing(accent, dark, if (reduced) 0f else frameMs / 1000f, reduced)
            val h = size.height
            val bodyH = h * 0.56f
            val floorY = h * 0.9f
            val xs = listOf(size.width * 0.30f, size.width * 0.70f)
            val sinceCommit = if (reduced) 0f else (frameMs - commitFrame).coerceIn(0L, 400L).toFloat()
            for (seat in 0..1) {
                val f = s.fighters[seat]
                val timed = f.action == "JAB" || f.action == "HOOK" || f.action == "DODGE"
                val age = f.actionAgeMs + if (timed) sinceCommit else 0f
                val bob = if (reduced) 0f else sin(frameMs / 380f + seat * 1.6f)
                val winner = s.over && s.winnerPlayerId == s.players[seat].playerId
                val pose = boxerPose(f.action, age, f.guard, bob, winner)
                drawBoxer(
                    cx = xs[seat], floorY = floorY, bodyH = bodyH, facing = if (seat == 0) 1 else -1, color = BoxerColors[seat], badge = seat,
                    pose = pose, hurtFlash = flash(seat), shake = shake() * 5f * (if (seat == 0) 1f else -1f),
                    winner = winner
                )
            }
        }
        // Name tags sit above each boxer: a text label in addition to colour and the trunk badge.
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (seat in 0..1) {
                Text(
                    seatName(seat), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(BoxerColors[seat].copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        // Damage numbers.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            popups.forEach { p ->
                key(p.id) {
                    Text(
                        p.text, color = if (p.good) colors.gold else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
                        modifier = Modifier.offset(x = maxWidth * (if (p.seat == 0) 0.22f else 0.62f), y = maxHeight * 0.18f)
                            .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        // Centre call: countdown digits, "Fight!", the ten count, the knockout.
        val big: String? = when {
            s.over && (s.event == BoxingEvent.KO || s.event == BoxingEvent.TKO) -> stringResource(R.string.tbx_big_ko)
            s.phaseValue == BoxingPhase.KNOCKDOWN -> if (s.count > 0) s.count.toString() else stringResource(R.string.tbx_big_down)
            s.phaseValue == BoxingPhase.COUNTDOWN -> if (s.phaseLeftMs > 1500) ceil(s.phaseLeftMs / 1000.0).toInt().toString() else stringResource(R.string.tbx_big_ready)
            s.phaseValue == BoxingPhase.BREAK -> stringResource(R.string.tbx_big_break)
            goFlash -> stringResource(R.string.tbx_ann_fight)
            else -> null
        }
        if (big != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    big, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 60.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0x99000000)).padding(horizontal = 28.dp, vertical = 4.dp)
                )
            }
        }
        burstContent()
    }
}

@Composable
private fun BoxingControls(
    s: BoxingState, seat: Int, rotated: Boolean, vertical: Boolean, colors: BoxColors, hint: BoxingAction?,
    pressed: Set<String>, onPress: (Int, BoxingAction) -> Unit, onRelease: (Int, BoxingAction) -> Unit,
    onToggleGuard: () -> Unit, onAct: (BoxingAction) -> Unit, seatLabel: String
) {
    val f = s.fighters[seat]
    val enabledGame = !s.over
    val mod = Modifier.fillMaxWidth().then(if (rotated) Modifier.rotate(180f) else Modifier)
    val pads: List<PadSpec> = when {
        !enabledGame -> emptyList()
        s.assist && s.phaseValue == BoxingPhase.FIGHT -> {
            val myAttack = s.assistStage == "ATTACK" && s.assistActor == seat
            val myDefend = s.assistStage == "DEFEND" && s.assistActor != seat
            when {
                myAttack -> listOf(
                    PadSpec(BoxingAction.JAB, R.string.tbx_pad_jab, R.string.tbx_padd_jab, 0, BoxingMove.JAB.cost, f.stamina >= BoxingMove.JAB.cost, false),
                    PadSpec(BoxingAction.HOOK, R.string.tbx_pad_hook, R.string.tbx_padd_hook, 1, BoxingMove.HOOK.cost, f.stamina >= BoxingMove.HOOK.cost, false),
                    PadSpec(BoxingAction.REST, R.string.tbx_pad_rest, R.string.tbx_padd_rest, 4, null, true, false)
                )
                myDefend -> listOf(
                    PadSpec(BoxingAction.BLOCK, R.string.tbx_pad_block, R.string.tbx_padd_block_turn, 2, null, true, false),
                    PadSpec(BoxingAction.DODGE, R.string.tbx_pad_dodge, R.string.tbx_padd_dodge, 3, BoxingRules.DODGE_COST, f.stamina >= BoxingRules.DODGE_COST, false),
                    PadSpec(BoxingAction.REST, R.string.tbx_pad_take, R.string.tbx_padd_take, 5, null, true, false)
                )
                else -> emptyList()
            }
        }
        f.action == "DOWN" -> listOf(PadSpec(BoxingAction.TAP, R.string.tbx_pad_getup, R.string.tbx_padd_getup, 0, null, true, false))
        s.phaseValue == BoxingPhase.FIGHT || s.phaseValue == BoxingPhase.COUNTDOWN || s.phaseValue == BoxingPhase.BREAK || s.phaseValue == BoxingPhase.KNOCKDOWN -> listOf(
            PadSpec(BoxingAction.JAB, R.string.tbx_pad_jab, R.string.tbx_padd_jab, 0, BoxingMove.JAB.cost, f.stamina >= BoxingMove.JAB.cost, false),
            PadSpec(BoxingAction.HOOK, R.string.tbx_pad_hook, R.string.tbx_padd_hook, 1, BoxingMove.HOOK.cost, f.stamina >= BoxingMove.HOOK.cost, false),
            PadSpec(BoxingAction.BLOCK, R.string.tbx_pad_block, R.string.tbx_padd_block, 2, null, true, true),
            PadSpec(BoxingAction.DODGE, R.string.tbx_pad_dodge, R.string.tbx_padd_dodge, 3, BoxingRules.DODGE_COST, f.stamina >= BoxingRules.DODGE_COST, false)
        )
        else -> emptyList()
    }
    if (pads.isEmpty()) {
        if (enabledGame && s.assist) {
            Box(mod.heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(colors.card).padding(12.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.tbx_wait_turn), color = colors.text, textAlign = TextAlign.Center)
            }
        } else Spacer(Modifier.height(4.dp))
        return
    }
    val getUp = f.action == "DOWN"
    val content: @Composable (PadSpec, Modifier) -> Unit = { p, m ->
        val label = stringResource(p.label)
        val desc = if (p.cost != null) stringResource(p.desc, p.cost) else stringResource(p.desc)
        val extra = if (getUp) " " + stringResource(R.string.tbx_getup_progress, f.riseTaps, f.riseNeeded) else ""
        val isGuard = p.action == BoxingAction.BLOCK && f.guard && !s.assist
        BoxPad(
            label = label + if (getUp) " ${f.riseTaps}/${f.riseNeeded}" else "",
            desc = desc + extra + if (!p.enabled) " " + stringResource(R.string.tbx_pad_tired) else "",
            icon = p.icon, cost = p.cost, enabled = p.enabled, hinted = hint == p.action || (hint == BoxingAction.BLOCK && p.action == BoxingAction.BLOCK),
            held = isGuard || "$seat:${p.action.name}" in pressed, hold = p.hold && !s.assist, colors = colors,
            accent = BoxerColors[seat],
            onDown = { onPress(seat, p.action) }, onUp = { onRelease(seat, p.action) },
            onActivate = if (p.hold && !s.assist) onToggleGuard else ({ onAct(p.action) }),
            modifier = m
        )
    }
    if (vertical) {
        Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pads.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p -> content(p, Modifier.weight(1f).heightIn(min = 88.dp)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        Row(mod, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pads.forEach { p -> content(p, Modifier.weight(1f).heightIn(min = 88.dp)) }
        }
    }
}

private class PadSpec(
    val action: BoxingAction, val label: Int, val desc: Int, val icon: Int, val cost: Int?, val enabled: Boolean, val hold: Boolean
)

@Composable
private fun BoxPad(
    label: String, desc: String, icon: Int, cost: Int?, enabled: Boolean, hinted: Boolean, held: Boolean, hold: Boolean,
    colors: BoxColors, accent: Color, onDown: () -> Unit, onUp: () -> Unit, onActivate: () -> Unit, modifier: Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    val bg = if (held) clShade(accent, -0.15f) else accent
    val content = Color.White
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .border(if (hinted) 5.dp else 2.dp, if (hinted) colors.gold else Color.Black.copy(alpha = 0.25f), shape)
            .pointerInput(hold) {
                detectTapGestures(onPress = {
                    onDown()
                    tryAwaitRelease()
                    onUp()
                })
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = desc
                onClick(label = label) { onActivate(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
            Canvas(Modifier.size(34.dp)) { drawPadIcon(icon, content) }
            Text(label, color = content, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center)
            if (cost != null) Text("-$cost", color = content.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1)
            else if (hold) Text("▮▮", color = content.copy(alpha = 0.0f), fontSize = 1.sp)
        }
    }
}
