package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.president.PS_CARD_ASPECT
import com.gamesuite.games.president.PS_HIDDEN_CARD
import com.gamesuite.games.president.PresidentGame
import com.gamesuite.games.president.PresidentReject
import com.gamesuite.games.president.PresidentRole
import com.gamesuite.games.president.PresidentRuleSet
import com.gamesuite.games.president.PresidentRules
import com.gamesuite.games.president.PresidentState
import com.gamesuite.games.president.PsCardFace
import com.gamesuite.games.president.PsDeck
import com.gamesuite.games.president.PsEvent
import com.gamesuite.games.president.PsFelt
import com.gamesuite.games.president.PsHand
import com.gamesuite.games.president.PsLabels
import com.gamesuite.games.president.PsPhase
import com.gamesuite.games.president.PsSeatPlate
import com.gamesuite.games.president.PsTrickPlay
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Colours of a card table: a dark-room look and a bright-room look, felt included. */
internal class PsTheme(val bg: Color, val text: Color, val panel: Color, val felt: Color, val onFelt: Color, val accent: Color)

@Composable
internal fun psTheme(dark: Boolean, felt: Color) = if (dark) PsTheme(
    bg = Color(0xFF11181B), text = Color(0xFFEAF1F2), panel = Color(0xFF1D282C), felt = felt, onFelt = Color.White, accent = Color(0xFFFFE082)
) else PsTheme(
    bg = Color(0xFFEDF3F2), text = Color(0xFF1B2A2E), panel = Color(0xFFDCE8E6), felt = felt, onFelt = Color.White, accent = Color(0xFF7A5400)
)

private val PresidentFelt = Color(0xFF17535C)

/**
 * President on a teal felt: tap cards to select a set of one rank, Play to lay it on the pile, Pass to sit
 * a trick out. Tapping a card when a count is required selects that many of its rank for you. Plays fly in
 * from the seat that made them and the trick sweeps to whoever wins it; every motion is skipped with
 * Reduced Motion and scaled by Game Speed. Suits differ by shape (colour-blind safe), every card, seat and
 * result is described for TalkBack, the status line is a live region and every refused tap says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresidentScreen(
    sessionManager: GameSessionManager,
    game: PresidentGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val cardSounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.UNO, enabled = musicEnabled)
    val state by game.state
    val hint by game.hint
    val hostLeft by game.hostLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = psTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f, PresidentFelt)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val cardScale = LocalCardScale.current
    val labels = remember(androidContext) { PsLabels(androidContext) }
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.rules = PresidentRuleSet(
            revolutions = extras["revolutions"] != "false",
            ties = extras["ties"] != "false",
            exchange = extras["exchange"] != "false",
            rounds = extras["rounds"]?.toIntOrNull()?.takeIf { it in PresidentRuleSet.ROUND_CHOICES } ?: 5
        )
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    val isNetworked = game.networked
    val hotSeat = !isNetworked && game.humanSeatCount() > 1

    // ---- animation state -------------------------------------------------------------------
    var shown by remember { mutableStateOf<List<PsTrickPlay>>(emptyList()) }
    val flyIn = remember { Animatable(1f) }
    val sweep = remember { Animatable(0f) }
    var flySeat by remember { mutableStateOf<Int?>(null) }
    var sweepSeat by remember { mutableStateOf<Int?>(null) }
    var lastSerial by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var bubbleSeat by remember { mutableStateOf<Int?>(null) }
    var revolutionFlash by remember { mutableStateOf(false) }
    var dealKey by remember { mutableIntStateOf(0) }
    var whyKey by remember { mutableStateOf<PresidentReject?>(null) }
    var whyCount by remember { mutableIntStateOf(0) }
    var hintNote by remember { mutableStateOf<Int?>(null) }
    var revealedSeat by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }

    LaunchedEffect(state?.eventSerial) {
        val s = state ?: return@LaunchedEffect
        val first = lastSerial < 0
        if (s.eventSerial == lastSerial) return@LaunchedEffect
        lastSerial = s.eventSerial
        selected = emptySet()
        whyKey = null
        hintNote = null
        val seat = s.eventSeat
        val localMover = !isNetworked || seat == game.localSeat
        try {
            when (s.event) {
                PsEvent.DEAL -> {
                    shown = emptyList()
                    dealKey += 1
                    if (!first) { cardSounds.playShuffle(); haptics(HapticSignal.LIGHT_TICK) }
                }
                PsEvent.EXCHANGE -> {
                    shown = emptyList()
                    if (!first) { cardSounds.playDraw(); haptics(HapticSignal.LIGHT_TICK) }
                }
                PsEvent.PLAY, PsEvent.ROUND_END, PsEvent.SESSION_END -> {
                    val play = if (seat != null && s.eventCards.isNotEmpty()) PsTrickPlay(seat, s.eventCards) else null
                    val before = shown
                    val target = when {
                        play == null -> s.trick
                        s.trick.isEmpty() && s.eventCleared -> before + play
                        else -> s.trick.ifEmpty { before + play }
                    }
                    shown = target
                    if (play != null && !first) {
                        flySeat = seat
                        if (!reduced) { flyIn.snapTo(0f); sweep.snapTo(0f) }
                        cardSounds.playPlace(rate = 0.9f + 0.05f * play.cards.size)
                        playSfx(SfxKind.WHOOSH)
                        haptics(if (localMover) HapticSignal.NORMAL_ACTION else HapticSignal.LIGHT_TICK)
                        if (!reduced) flyIn.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
                        playSfx(SfxKind.SOLID_THUNK)
                        if (s.eventRevolution) {
                            revolutionFlash = true
                            playSfx(SfxKind.SUCCESS_CHIME)
                            haptics(HapticSignal.STRONG_ACTION)
                        }
                        if (s.eventSkipped != null) haptics(HapticSignal.LIGHT_TICK)
                        if (s.eventOut) {
                            playSfx(SfxKind.SUCCESS_CHIME)
                            haptics(if (localMover) HapticSignal.SUCCESS else HapticSignal.LIGHT_TICK)
                        }
                    }
                    if (s.eventCleared && !first) {
                        busy = true
                        sweepSeat = s.currentSeat
                        delay(gameSpeed.scale(650L))
                        if (!reduced) { sweep.snapTo(0f); sweep.animateTo(1f, tween(gameSpeed.scale(380L).toInt().coerceAtLeast(80), easing = LinearEasing)) }
                    }
                    if (s.event == PsEvent.ROUND_END || s.event == PsEvent.SESSION_END) {
                        val me = game.localSeat
                        val place = if (me in s.seats.indices) s.seats[me].outPosition else -1
                        val good = place == 0 || (place in 1 until s.seats.size / 2)
                        if (s.event == PsEvent.SESSION_END) {
                            val top = s.seats.maxOf { it.score }
                            val iWon = !isNetworked && s.seats.any { !it.isBot && it.score == top } || (me in s.seats.indices && s.seats[me].score == top)
                            if (iWon) {
                                playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                                if (!reduced) burst.spawn(Offset(0.5f, 0.3f), 70, listOf(Color(0xFFFFD54A), Color.White, Color(0xFF7CE0C3)), 0.25f..0.9f, 1.0f..1.9f, 0.9f)
                            } else { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                        } else if (good) { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
                        else { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                    }
                }
                PsEvent.PASS -> {
                    if (seat != null && !first) {
                        scope.launch {
                            bubbleSeat = seat
                            delay(gameSpeed.scale(1200L))
                            if (bubbleSeat == seat) bubbleSeat = null
                        }
                        playSfx(SfxKind.LIGHT_TICK)
                        haptics(HapticSignal.LIGHT_TICK)
                    }
                    if (s.eventCleared && !first) {
                        busy = true
                        sweepSeat = s.currentSeat
                        delay(gameSpeed.scale(650L))
                        if (!reduced) { sweep.snapTo(0f); sweep.animateTo(1f, tween(gameSpeed.scale(380L).toInt().coerceAtLeast(80), easing = LinearEasing)) }
                    } else {
                        shown = s.trick
                    }
                }
            }
        } finally {
            shown = s.trick
            busy = false
            revolutionFlash = false
            sweepSeat = null
            if (!reduced) { sweep.snapTo(0f); flyIn.snapTo(1f) }
        }
    }

    var botThinking by remember { mutableStateOf(false) }
    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (!game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            val base = when (game.difficulty) {
                CpuDifficulty.EASY -> 850L
                CpuDifficulty.MEDIUM -> 1000L
                CpuDifficulty.HARD -> 1150L
            }
            delay(gameSpeed.scale(base + Random.nextLong(0L, 600L)).coerceAtLeast(400L))
            while (busy) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val helpTitle = stringResource(R.string.ps_help_title)
    val helpText = stringResource(R.string.ps_help_text)
    val s = state
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = theme.bg, buttonContent = theme.text
            ) {
                Box(Modifier.fillMaxSize().background(theme.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ps_waiting_host), color = theme.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val n = s.seats.size
    val giver = game.giverSeat(s)
    val actingSeat: Int? = when {
        s.phase == PsPhase.EXCHANGE -> giver
        s.phase == PsPhase.PLAY && game.isMyTurn(s) -> s.currentSeat
        else -> null
    }
    // On a shared device the cards are shown only after the person on turn has said it is them.
    val coverFor: Int? = if (hotSeat && actingSeat != null && revealedSeat != actingSeat) actingSeat else null
    val shownSeat: Int = when {
        game.isSpectator -> -1
        hotSeat -> revealedSeat ?: -1
        else -> game.viewSeat(s)
    }
    val myHand: List<Int> = if (shownSeat in s.seats.indices && coverFor == null) s.seats[shownSeat].hand else emptyList()
    val isMyTurn = game.isMyTurn(s) && coverFor == null
    val sessionsDone = s.sessionWins.values.sum()

    fun dir(seat: Int): Offset {
        if (seat == shownSeat) return Offset(0f, 1f)
        val others = (0 until n).filter { it != shownSeat }
        val k = others.indexOf(seat).coerceAtLeast(0)
        val x = if (others.size <= 1) 0f else ((k + 0.5f) / others.size - 0.5f) * 2f
        return Offset(x, -1f)
    }

    // ---- words ---------------------------------------------------------------------------------
    fun nm(i: Int) = labels.displayName(s.seats.getOrNull(i)?.displayName ?: "")
    fun raw(i: Int) = s.seats.getOrNull(i)?.displayName ?: ""
    val ctxRes = androidContext.resources
    val lastLine = run {
        val who = raw(s.eventSeat ?: 0)
        when (s.event) {
            PsEvent.DEAL -> labels.aware(raw(s.currentSeat), R.string.ps_ann_deal, R.string.ps_yg_ann_deal, s.roundNumber, s.rules.rounds)
            PsEvent.EXCHANGE -> labels.aware(who, R.string.ps_ann_exchange, R.string.ps_yg_ann_exchange)
            PsEvent.PLAY -> buildString {
                append(labels.aware(who, R.string.ps_ann_play, R.string.ps_yg_ann_play, labels.groupText(s.eventCards)))
                if (s.eventRevolution) append(" ").append(androidContext.getString(if (s.revolution) R.string.ps_ann_revolution_on else R.string.ps_ann_revolution_off))
                s.eventSkipped?.let { append(" ").append(labels.aware(raw(it), R.string.ps_ann_skipped, R.string.ps_yg_ann_skipped)) }
                if (s.eventOut) append(" ").append(labels.aware(who, R.string.ps_ann_out, R.string.ps_yg_ann_out, (s.seats.getOrNull(s.eventSeat ?: 0)?.outPosition ?: 0) + 1))
                if (s.eventCleared && !s.roundOver) append(" ").append(labels.aware(raw(s.currentSeat), R.string.ps_ann_trick, R.string.ps_yg_ann_trick))
            }
            PsEvent.PASS -> buildString {
                append(labels.aware(who, R.string.ps_ann_pass, R.string.ps_yg_ann_pass))
                if (s.eventCleared) append(" ").append(labels.aware(raw(s.currentSeat), R.string.ps_ann_trick, R.string.ps_yg_ann_trick))
            }
            PsEvent.ROUND_END -> labels.aware(raw(s.finishOrder.firstOrNull() ?: 0), R.string.ps_ann_round_end, R.string.ps_yg_ann_round_end)
            else -> {
                val top = s.seats.maxOf { it.score }
                val leaders = s.seats.indices.filter { s.seats[it].score == top }
                if (leaders.size == 1) labels.aware(raw(leaders[0]), R.string.ps_ann_session_end, R.string.ps_yg_ann_session_end)
                else androidContext.getString(R.string.ps_ann_session_tie)
            }
        }
    }
    val turnLine = when {
        s.sessionOver || s.roundOver -> ""
        hostLeft -> androidContext.getString(R.string.ps_host_left)
        s.phase == PsPhase.EXCHANGE -> when {
            giver != null && giver == shownSeat -> {
                val task = s.pendingExchanges.first { it.from == giver }
                ctxRes.getQuantityString(R.plurals.ps_exch_choose, task.count, task.count, nm(task.to))
            }
            else -> {
                val waiting = s.pendingExchanges.map { nm(it.from) }.distinct().joinToString(", ")
                androidContext.getString(R.string.ps_exch_wait, waiting)
            }
        }
        isMyTurn && s.mustInclude != null && s.top == null -> androidContext.getString(R.string.ps_turn_first)
        isMyTurn && s.top == null -> androidContext.getString(R.string.ps_turn_lead)
        isMyTurn -> androidContext.getString(R.string.ps_turn_beat, labels.groupText(s.top!!.cards))
        botThinking -> labels.aware(raw(s.currentSeat), R.string.ps_thinking, R.string.ps_yg_thinking)
        else -> labels.aware(raw(s.currentSeat), R.string.ps_turn_named, R.string.ps_yg_turn_named)
    }
    val statusText = listOf(lastLine, turnLine).filter { it.isNotBlank() }.joinToString(" ")
    val whyText: String? = whyKey?.let { k ->
        val top = s.top
        when (k) {
            PresidentReject.NONE -> null
            PresidentReject.NOT_STARTED, PresidentReject.WRONG_PHASE -> androidContext.getString(R.string.ps_why_phase)
            PresidentReject.NOT_YOUR_TURN -> androidContext.getString(R.string.ps_why_wait)
            PresidentReject.SPECTATOR -> androidContext.getString(R.string.ps_why_spectator)
            PresidentReject.NOTHING_SELECTED -> androidContext.getString(R.string.ps_why_none)
            PresidentReject.NOT_IN_HAND -> androidContext.getString(R.string.ps_why_not_in_hand)
            PresidentReject.MIXED_RANKS -> androidContext.getString(R.string.ps_why_mixed)
            PresidentReject.WRONG_COUNT -> ctxRes.getQuantityString(R.plurals.ps_why_count, top?.cards?.size ?: 1, top?.cards?.size ?: 1)
            PresidentReject.TOO_LOW -> androidContext.getString(if (s.revolution) R.string.ps_why_low_rev else R.string.ps_why_low)
            PresidentReject.TIE_NOT_ALLOWED -> androidContext.getString(R.string.ps_why_tie)
            PresidentReject.MUST_LEAD_THREE_OF_CLUBS -> androidContext.getString(R.string.ps_why_three)
            PresidentReject.CANNOT_PASS_LEAD -> androidContext.getString(R.string.ps_why_cant_pass)
            PresidentReject.WRONG_GIFT_COUNT -> {
                val c = s.pendingExchanges.firstOrNull { it.from == giver }?.count ?: 1
                ctxRes.getQuantityString(R.plurals.ps_why_gift, c, c)
            }
        }
    }
    LaunchedEffect(whyKey, whyCount) { if (whyKey != null) { delay(4000); whyKey = null } }

    fun refuse(why: PresidentReject) {
        whyKey = why
        whyCount += 1
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }

    // The set a tap on [id] selects: the tapped card plus, when a count is required, enough of its rank.
    fun selectionFor(id: Int): Set<Int> {
        val need = s.top?.cards?.size
        val rank = PresidentRules.rankOf(id)
        val sameRank = myHand.filter { PresidentRules.rankOf(it) == rank }
        if (need != null && need > 1 && sameRank.size >= need) return (listOf(id) + sameRank.filter { it != id }).take(need).toSet()
        return setOf(id)
    }

    fun tapCard(id: Int) {
        if (s.phase == PsPhase.EXCHANGE) {
            if (giver == null || giver != shownSeat) { refuse(PresidentReject.NOT_YOUR_TURN); return }
            val need = s.pendingExchanges.first { it.from == giver }.count
            selected = if (id in selected) selected - id else if (selected.size >= need) setOf(id) else selected + id
            playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK)
            return
        }
        if (!isMyTurn) { refuse(game.explainPlay(listOf(id)).let { if (it == PresidentReject.NONE) PresidentReject.NOT_YOUR_TURN else it }); return }
        val next = when {
            id in selected -> selected - id
            selected.isNotEmpty() && PresidentRules.rankOf(selected.first()) == PresidentRules.rankOf(id) && s.top == null && selected.size < 4 -> selected + id
            else -> selectionFor(id)
        }
        selected = next
        playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK)
        if (next.isNotEmpty()) {
            val why = game.explainPlay(next.toList())
            if (why != PresidentReject.NONE) { whyKey = why; whyCount += 1 } else whyKey = null
        }
    }

    fun doPlay() {
        val ids = selected.toList()
        val why = game.tryPlay(ids)
        if (why == PresidentReject.NONE) { selected = emptySet(); whyKey = null } else refuse(why)
    }

    fun doPass() {
        val why = game.tryPass()
        if (why != PresidentReject.NONE) refuse(why)
    }

    fun doGive() {
        val why = game.tryGive(selected.toList())
        if (why == PresidentReject.NONE) { selected = emptySet(); whyKey = null } else refuse(why)
    }

    fun doHint() {
        scope.launch {
            val h = game.requestHint() ?: return@launch
            if (h.pass) { hintNote = R.string.ps_hint_pass; selected = emptySet() }
            else {
                hintNote = if (s.phase == PsPhase.EXCHANGE) R.string.ps_hint_give else R.string.ps_hint_play
                selected = h.cards.toSet()
            }
            playSfx(SfxKind.LIGHT_TICK)
        }
    }

    val localOnly = !isNetworked
    val tableDescription = if (shown.isEmpty()) androidContext.getString(R.string.ps_table_empty_desc)
    else {
        val last = shown.last()
        androidContext.getString(R.string.ps_table_desc, nm(last.seat), labels.groupText(last.cards))
    }
    val boardDescription = s.seats.indices.joinToString("; ") { i ->
        listOfNotNull(nm(i), labels.cardsLeft(s.seats[i].hand.size), labels.roleOf(s.seats[i].role)).joinToString(", ")
    }

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.sessionOver,
        onLeave = game::leaveSession,
        onAbort = { if (sessionsDone > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = sessionsDone == 0,
        onUndo = if (localOnly) ({ game.undo() }) else null,
        canUndo = game.canUndo,
        onHint = if (localOnly) ({ doHint() }) else null,
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.ps_leave_title),
        leaveBody = stringResource(if (sessionsDone > 0) R.string.ps_leave_body_session else R.string.ps_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("president", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val shortScreen = !landscape && maxHeight < 600.dp
            val cardW: Dp = (58.dp * cardScale).coerceIn(46.dp, 84.dp)
            val tableCardW: Dp = (cardW * 1.3f).coerceIn(52.dp, 92.dp)

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("president", openCoach)
                    Text(
                        stringResource(R.string.ps_round_of, s.roundNumber, s.rules.rounds),
                        color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (s.revolution) {
                        Text(
                            stringResource(R.string.ps_revolution_chip), color = Color(0xFF1B1B1B), fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.background(Color(0xFFFFD54A), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, theme.text, theme.panel)
                if (whyText == null && hintNote != null) ClWhyLine(stringResource(hintNote!!), theme.text, theme.panel)
            }

            val plates: @Composable () -> Unit = {
                val seatOrder = (1..n).map { ((if (shownSeat >= 0) shownSeat else -1) + it + n) % n }.filter { it != shownSeat }
                FlowRow(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    seatOrder.forEach { i ->
                        val seat = s.seats[i]
                        val turn = i == s.currentSeat && s.phase == PsPhase.PLAY
                        val role = labels.roleOf(seat.role)
                        val place = if (seat.outPosition >= 0 && !s.roundOver) stringResource(R.string.ps_out_label, seat.outPosition + 1) else null
                        val desc = buildString {
                            append(nm(i))
                            if (seat.isBot) append(" (").append(stringResource(R.string.ps_cpu_tag)).append(")")
                            if (isNetworked && i == game.localSeat) append(" ").append(stringResource(R.string.ps_you_suffix))
                            append(", ").append(labels.cardsLeft(seat.hand.size))
                            role?.let { append(", ").append(it) }
                            if (place != null) append(", ").append(place)
                            if (turn) append(", ").append(stringResource(R.string.ps_on_turn))
                        }
                        PsSeatPlate(
                            name = nm(i), seatIndex = i, cardCount = seat.hand.size, isTurn = turn, reduced = reduced,
                            description = desc, roleLabel = role, placeLabel = place,
                            bubble = if (bubbleSeat == i) stringResource(R.string.ps_pass_bubble) else null,
                            textColor = theme.text, plateColor = theme.panel, accentText = theme.accent
                        )
                    }
                }
            }

            val table: @Composable (Modifier) -> Unit = { mod ->
                PsFelt(theme.felt, mod.semantics { contentDescription = tableDescription; liveRegion = LiveRegionMode.Polite }) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (shown.isEmpty()) {
                            Text(
                                stringResource(R.string.ps_table_empty), color = theme.onFelt.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp)
                            )
                        } else {
                            PsTrickView(
                                plays = shown, cardW = tableCardW, colorblind = colorblind, reduced = reduced,
                                flyProgress = flyIn.value, flyFrom = flySeat?.let(::dir) ?: Offset(0f, 1f),
                                sweepProgress = sweep.value, sweepTo = sweepSeat?.let(::dir) ?: Offset(0f, -1f)
                            )
                        }
                        val last = shown.lastOrNull()
                        if (last != null) {
                            Text(
                                stringResource(R.string.ps_table_caption, nm(last.seat), labels.groupText(last.cards)),
                                color = theme.onFelt, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 16.dp, end = 16.dp)
                            )
                        }
                        if (revolutionFlash) {
                            val t = rememberInfiniteTransition(label = "rev").animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(280), RepeatMode.Reverse), label = "rev-s").value
                            Text(
                                stringResource(R.string.ps_revolution_banner), color = Color(0xFF1B1B1B), fontWeight = FontWeight.Black,
                                modifier = Modifier.graphicsLayer { scaleX = if (reduced) 1f else t; scaleY = if (reduced) 1f else t }
                                    .background(Color(0xFFFFD54A), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                }
            }

            val bottom: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (shownSeat in s.seats.indices) {
                        val me = s.seats[shownSeat]
                        val role = labels.roleOf(me.role)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(nm(shownSeat), color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            if (role != null) Text(role, color = theme.accent, style = MaterialTheme.typography.labelLarge)
                            Text(labels.cardsLeft(me.hand.size), color = theme.text.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                        }
                        // what the exchange moved, for the two seats it concerned
                        val mine = s.exchanges.filter { it.from == shownSeat || it.to == shownSeat }
                        if (mine.isNotEmpty() && s.phase != PsPhase.ROUND_OVER && s.phase != PsPhase.SESSION_OVER && !s.roundOver) {
                            Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                                mine.forEach { e ->
                                    val text = if (e.to == shownSeat) stringResource(R.string.ps_exch_received, labels.shortList(e.cards), nm(e.from))
                                    else stringResource(R.string.ps_exch_gave, labels.shortList(e.cards), nm(e.to))
                                    Text(text, color = theme.text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val canPlayNow = isMyTurn && s.phase == PsPhase.PLAY
                        val inExchange = s.phase == PsPhase.EXCHANGE && giver == shownSeat && coverFor == null
                        PsHand(
                            cards = myHand, selected = selected, playable = null, hint = hint?.cards?.toSet() ?: emptySet(),
                            enabled = canPlayNow || inExchange, colorblind = colorblind, reduced = reduced, dealKey = dealKey,
                            cardWidth = cardW, descriptionOf = labels::cardDesc, selectedLabel = stringResource(R.string.ps_selected),
                            onTap = ::tapCard, gameSpeedScale = { gameSpeed.scale(it) }, modifier = Modifier.fillMaxWidth(),
                            minStep = if (landscape) 34.dp else 44.dp
                        )
                        Spacer(Modifier.height(6.dp))
                        if (!s.roundOver) {
                            if (inExchange) {
                                val need = s.pendingExchanges.first { it.from == giver }.count
                                Button(
                                    onClick = ::doGive, enabled = selected.size == need,
                                    modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 160.dp)
                                ) { Text(ctxRes.getQuantityString(R.plurals.ps_give_cards, need, need), fontWeight = FontWeight.Bold) }
                            } else if (canPlayNow) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = ::doPass, enabled = s.top != null && !busy,
                                        modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 96.dp)
                                    ) { Text(stringResource(R.string.ps_pass), fontWeight = FontWeight.Bold) }
                                    Button(
                                        onClick = ::doPlay,
                                        enabled = selected.isNotEmpty() && !busy && game.explainPlay(selected.toList()) == PresidentReject.NONE,
                                        modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 140.dp)
                                    ) {
                                        Text(
                                            if (selected.isEmpty()) stringResource(R.string.ps_play) else ctxRes.getQuantityString(R.plurals.ps_play_cards, selected.size, selected.size),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (selected.isNotEmpty()) OutlinedButton(onClick = { selected = emptySet() }, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.ps_clear)) }
                                }
                            }
                            if (localOnly && (canPlayNow || inExchange)) {
                                Spacer(Modifier.height(6.dp))
                                ClUndoHintRow(
                                    canUndo = game.canUndo, canHint = true, onUndo = { game.undo() }, onHint = ::doHint, contentColor = theme.text
                                )
                            }
                        }
                    }
                    if (s.roundOver) {
                        Spacer(Modifier.height(8.dp))
                        PsResultPanel(s, labels, game, hostLeft, sessionsDone)
                    }
                }
            }

            // The "pass the device" screen on a shared device.
            val cover: @Composable () -> Unit = {
                if (coverFor != null) {
                    Box(Modifier.fillMaxSize().background(theme.bg), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text(
                                stringResource(R.string.ps_cover_title, nm(coverFor)), color = theme.text, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { revealedSeat = coverFor }, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp)) {
                                Text(stringResource(R.string.ps_cover_button), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.5f).fillMaxSize()) {
                        table(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp))
                    }
                    Column(Modifier.weight(0.5f).fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        header(); plates()
                        bottom()
                    }
                }
            } else if (shortScreen) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header(); plates()
                    table(Modifier.fillMaxWidth().height(170.dp))
                    Spacer(Modifier.height(6.dp)); bottom()
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header(); plates()
                    table(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp))
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { bottom() }
                }
            }
            cover()
            Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
        }
    }
}

/**
 * The trick on the table: the latest play in front at full size, earlier plays of the same trick fanned
 * behind it. [flyProgress] (0..1) slides the latest play in from [flyFrom] (a unit direction towards the seat that
 * played it); [sweepProgress] slides the whole trick off towards [sweepTo], the seat that won it.
 */
@Composable
private fun PsTrickView(
    plays: List<PsTrickPlay>, cardW: Dp, colorblind: Boolean, reduced: Boolean,
    flyProgress: Float, flyFrom: Offset, sweepProgress: Float, sweepTo: Offset
) {
    val visible = plays.takeLast(4)
    // the caption sits along the bottom edge of the felt: keep the cards clear of it
    BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 34.dp), contentAlignment = Alignment.Center) {
        val cardW = minOf(cardW, ((maxHeight - 16.dp) / PS_CARD_ASPECT).coerceAtLeast(30.dp))
        visible.forEachIndexed { index, play ->
            val age = visible.lastIndex - index
            val isLatest = age == 0
            val jitter = ((play.cards.sum() * 37) % 11 - 5).toFloat()
            val step = cardW * 0.5f
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(
                Modifier.graphicsLayer {
                    val dist = with(density) { 150.dp.toPx() }
                    val fly = if (isLatest && !reduced) 1f - flyProgress else 0f
                    val sw = if (reduced) 0f else sweepProgress
                    translationX = fly * flyFrom.x * dist + sw * sweepTo.x * dist * 1.6f - age * with(density) { 7.dp.toPx() }
                    translationY = fly * flyFrom.y * dist + sw * sweepTo.y * dist * 1.6f - age * with(density) { 5.dp.toPx() }
                    rotationZ = jitter * (1f + age * 0.5f) + fly * 12f * flyFrom.x
                    val sc = (1f - 0.06f * age) * (0.85f + 0.15f * (1f - fly))
                    scaleX = sc; scaleY = sc
                    alpha = (if (isLatest) 1f else (0.82f - 0.22f * age)).coerceAtLeast(0.25f) * (1f - sw)
                }
            ) {
                Box {
                    play.cards.forEachIndexed { k, id ->
                        PsCardFace(id, cardW, Modifier.padding(start = step * k), colorblind = colorblind)
                    }
                }
            }
        }
    }
}

/** Round results (standings, points and the titles the next round starts with) or the final standings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PsResultPanel(
    s: PresidentState, labels: PsLabels, game: PresidentGame, hostLeft: Boolean, sessionsDone: Int
) {
    val order = s.finishOrder
    val title = if (s.sessionOver) stringResource(R.string.ps_final_title) else stringResource(R.string.ps_result_title, s.roundNumber)
    val top = s.seats.maxOf { it.score }
    val leaders = s.seats.indices.filter { s.seats[it].score == top }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (s.sessionOver) {
                Text(
                    if (leaders.size == 1) labels.aware(s.seats[leaders[0]].displayName, R.string.ps_session_winner, R.string.ps_yg_session_winner)
                    else stringResource(R.string.ps_session_tie),
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            val rows = if (s.sessionOver) s.seats.indices.sortedByDescending { s.seats[it].score } else order
            rows.forEachIndexed { rank, seatIndex ->
                val seat = s.seats[seatIndex]
                val role = PresidentRules.roleFor(seat.outPosition.coerceAtLeast(0), s.seats.size).let(labels::roleName)
                val line = if (s.sessionOver) {
                    stringResource(R.string.ps_final_row, rank + 1, labels.displayName(seat.displayName), seat.score)
                } else {
                    stringResource(R.string.ps_result_row, rank + 1, labels.displayName(seat.displayName), role, seat.roundPoints, seat.score)
                }
                Text(line, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, fontWeight = if (rank == 0) FontWeight.Bold else FontWeight.Normal)
            }
            if (!s.sessionOver && order.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    labels.aware(s.seats[order[0]].displayName, R.string.ps_next_leads, R.string.ps_yg_next_leads),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                )
            }
            if (sessionsDone > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ps_sessions_won, s.seats.joinToString(" · ") { "${labels.displayName(it.displayName)} ${s.sessionWins[it.playerId] ?: 0}" }),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!game.isSpectator) {
                    Button(onClick = { game.continueSession() }, enabled = !hostLeft, modifier = Modifier.heightIn(min = 52.dp)) {
                        Text(stringResource(if (s.sessionOver) R.string.ps_play_again else R.string.ps_next_round), fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(onClick = { game.leaveSession() }, modifier = Modifier.heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.ps_back_to_menu))
                }
            }
        }
    }
}
