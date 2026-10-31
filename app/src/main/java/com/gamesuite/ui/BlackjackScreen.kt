package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import com.gamesuite.core.GameCoach
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.blackjack.BJ_HIDDEN_CARD
import com.gamesuite.games.blackjack.BjAction
import com.gamesuite.games.blackjack.BjCard
import com.gamesuite.games.blackjack.BjConfig
import com.gamesuite.games.blackjack.BjEvent
import com.gamesuite.games.blackjack.BjHand
import com.gamesuite.games.blackjack.BjOutcome
import com.gamesuite.games.blackjack.BjPhase
import com.gamesuite.games.blackjack.BjReject
import com.gamesuite.games.blackjack.BjRules
import com.gamesuite.games.blackjack.BjSeat
import com.gamesuite.games.blackjack.BjState
import com.gamesuite.games.blackjack.BlackjackGame
import com.gamesuite.games.blackjack.CasinoCard
import com.gamesuite.games.blackjack.CasinoShoe
import com.gamesuite.games.blackjack.ChipPile
import com.gamesuite.games.blackjack.ChipView
import com.gamesuite.games.blackjack.DealIn
import com.gamesuite.games.blackjack.cardHeightFor
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.CardSounds
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

private val FELT_TOP_LIGHT = Color(0xFF237A55)
private val FELT_BOTTOM_LIGHT = Color(0xFF14513A)
private val FELT_TOP_DARK = Color(0xFF16503A)
private val FELT_BOTTOM_DARK = Color(0xFF0A2C20)
private val FELT_TEXT = Color(0xFFF7F3E8)
private val GOLD = Color(0xFFF2CD6B)

/** "[name] verb ..." in the right grammar: the second-person string when the seat is "You". */
internal fun Context.sayFor(name: String, other: Int, you: Int, vararg args: Any): String =
    if (isYouName(name)) getString(you, *args) else getString(other, name, *args)

/** Card names for TalkBack and the sighted status line, e.g. "Ace of spades". */
@Composable
internal fun rememberCardNamer(): (Card) -> String {
    val ranks = stringArrayResource(R.array.bjw_rank_names)
    val suits = stringArrayResource(R.array.bjw_suit_names)
    val format = stringResource(R.string.bjw_card_desc)
    return remember(ranks.toList(), suits.toList(), format) {
        { c: Card -> String.format(format, ranks[c.rank.ordinal], suits[c.suit.ordinal]) }
    }
}

private fun signed(n: Int) = if (n > 0) "+$n" else "$n"

/**
 * Blackjack on a green felt table: cards fly out of the shoe, the dealer's hole card turns over, chips
 * stack on the bet spot. Play chips only. Every card, hand, chip and shoe has a TalkBack label; the
 * status line is a polite live region, and a blocked button says why. Suits differ by shape (and the
 * red suits get a hatch in colour-blind mode); Reduced Motion turns every flight and flip into a cut.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlackjackScreen(
    sessionManager: GameSessionManager,
    game: BlackjackGame,
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
    val hostLeft by game.hostLeft
    val hintAction by game.hintAction
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val burst = rememberParticleBurst()
    val nameOf = rememberCardNamer()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.config = BjConfig.fromExtras(sessionManager.launchOptions.extras)
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0

    var shoeCenter by remember { mutableStateOf<Offset?>(null) }
    // Cards already on the table when this screen opened (a resume, a rotation) appear in place; the
    // set of keys that have flown in once is kept so a card that moves on a split does not fly again.
    val initialKeys = remember { state?.let { cardKeys(it) } ?: emptySet() }
    val flown = remember { HashSet<String>() }
    var pending by remember { mutableIntStateOf(0) }
    val lastBets = remember { mutableStateMapOf<Int, Int>() }
    var whyKey by remember { mutableStateOf<BjReject?>(null) }
    var showHintNote by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Pair<String, Int>?>(null) } // text, tone: 2 big win, 1 win, 0 push, -1 loss
    val bannerScale = remember { Animatable(0f) }

    // The computer seats and the dealer act on a gentle clock, after the cards have landed.
    LaunchedEffect(state?.serial, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (game.matchOver.value) return@LaunchedEffect
        if (game.botToMove(s)) {
            delay(gameSpeed.scale(if (s.phase == BjPhase.BETTING) 450L else 800L).coerceAtLeast(380L))
            game.playBotTurnAsync()
        } else if (game.dealerToMove(s)) {
            delay(gameSpeed.scale(if (s.holeHidden) 900L else 850L).coerceAtLeast(380L))
            game.dealerStep()
        }
    }

    // Bets reset when the turn moves on; remember what each seat bet for "Rebet".
    LaunchedEffect(state?.round, state?.activeSeat, state?.phase) { pending = 0; whyKey = null; showHintNote = false }
    LaunchedEffect(state?.serial) {
        val s = state ?: return@LaunchedEffect
        if (s.event == BjEvent.BET) s.seats.getOrNull(s.eventSeat)?.let { lastBets[s.eventSeat] = it.bet }
    }

    // Sound and touch for every event. Cards are staggered to match the deal animation.
    LaunchedEffect(state?.serial) {
        val s = state ?: return@LaunchedEffect
        val mine = isNetworked && s.eventSeat == localSeat
        when (s.event) {
            BjEvent.DEAL, BjEvent.PEEK_CLEAR, BjEvent.DEALER_BLACKJACK -> {
                if (s.event == BjEvent.DEAL || s.event == BjEvent.PEEK_CLEAR) {
                    val cards = s.seats.sumOf { seat -> seat.hands.sumOf { it.cards.size } } + s.dealer.size
                    if (!reduced && s.round > 0 && s.serial > 0) cardSounds.playShuffle()
                    repeat(cards.coerceAtMost(12)) {
                        cardSounds.playDraw()
                        delay(gameSpeed.scale(if (reduced) 0L else 130L))
                    }
                }
                if (s.event == BjEvent.DEALER_BLACKJACK) { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.STRONG_ACTION) }
            }
            BjEvent.BET, BjEvent.REBUY -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
            BjEvent.HIT, BjEvent.DOUBLE, BjEvent.SPLIT, BjEvent.DEALER_HIT -> {
                cardSounds.playDraw(); haptics(if (mine) HapticSignal.NORMAL_ACTION else HapticSignal.LIGHT_TICK)
            }
            BjEvent.STAND, BjEvent.SURRENDER, BjEvent.INSURANCE_TAKEN, BjEvent.INSURANCE_DECLINED -> {
                cardSounds.playTap(); haptics(HapticSignal.LIGHT_TICK)
            }
            BjEvent.BUST -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
            BjEvent.REVEAL -> { cardSounds.playPlace(); haptics(HapticSignal.ESCALATING) }
        }
    }

    // The round result: a banner, confetti for a win, and the matching tone.
    LaunchedEffect(state?.roundsDone) {
        val s = state ?: return@LaunchedEffect
        if (s.phase != BjPhase.ROUND_OVER) return@LaunchedEffect
        val seatsHere = s.seats.indices.filter { !s.seats[it].isBot && s.seats[it].hands.isNotEmpty() && (!isNetworked || it == localSeat) }
        if (seatsHere.isEmpty()) return@LaunchedEffect
        val net = seatsHere.sumOf { s.seats[it].roundNet }
        val anyBj = seatsHere.any { i -> s.seats[i].hands.any { it.outcome == BjOutcome.BLACKJACK } }
        val single = seatsHere.size == 1
        val text = when {
            anyBj && single -> androidContext.getString(R.string.bj_banner_blackjack, signed(net))
            net > 0 -> androidContext.getString(R.string.bj_banner_win, signed(net))
            net < 0 -> androidContext.getString(R.string.bj_banner_lose, signed(net))
            else -> androidContext.getString(R.string.bj_banner_push)
        }
        val tone = when { anyBj && net > 0 -> 2; net > 0 -> 1; net < 0 -> -1; else -> 0 }
        banner = text to tone
        when (tone) {
            2 -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION) }
            1 -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
            -1 -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
            else -> haptics(HapticSignal.LIGHT_TICK)
        }
        if (tone > 0 && !reduced) {
            burst.spawn(
                Offset(0.5f, 0.45f), if (tone == 2) 90 else 45, listOf(GOLD, Color.White, Color(0xFFE96A5A), Color(0xFF6BC4E8)),
                speedRange = 0.25f..0.95f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f
            )
        }
        if (reduced) bannerScale.snapTo(1f) else {
            bannerScale.snapTo(0.4f)
            bannerScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        }
        delay(2600)
        banner = null
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    val s = state
    val helpTitle = stringResource(R.string.bj_help_title)
    val helpText = stringResource(R.string.bj_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = FELT_BOTTOM_LIGHT, buttonContent = FELT_TEXT
            ) {
                Box(Modifier.fillMaxSize().background(FELT_BOTTOM_LIGHT), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bjw_waiting_host), color = FELT_TEXT, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val ctx = LocalContext.current
    val multiHuman = !isNetworked && s.seats.count { !it.isBot } > 1
    val isMyTurn = game.isMyTurn(s)
    val legal = remember(s) { game.legalActions(s) }
    val active = s.seats.getOrNull(s.activeSeat)
    val sessionNet = s.seats.indices.firstOrNull { !s.seats[it].isBot && (!isNetworked || it == localSeat) }
        ?.let { game.netOf(s, it) } ?: 0

    fun seatName(i: Int) = s.seats.getOrNull(i)?.displayName ?: ""
    fun handTotalText(h: BjHand): String =
        if (h.total.soft && h.total.value < 21 && h.cards.size >= 1)
            ctx.getString(R.string.bj_total_soft, h.total.value) else ctx.getString(R.string.bj_total, h.total.value)

    fun explainText(r: BjReject): String? = when (r) {
        BjReject.NONE -> null
        BjReject.OVER -> ctx.getString(R.string.bj_why_over)
        BjReject.NOT_YOUR_TURN -> ctx.getString(if (isNetworked) R.string.bj_why_wait else R.string.bj_why_cpu)
        BjReject.WRONG_PHASE -> ctx.getString(R.string.bj_why_phase)
        BjReject.BAD_BET -> ctx.getString(R.string.bj_why_bad_bet, BjRules.MIN_BET)
        BjReject.CANT_AFFORD -> ctx.getString(R.string.bj_why_afford)
        BjReject.NOT_BROKE -> ctx.getString(R.string.bj_why_not_broke)
        BjReject.NOT_FIRST_TWO -> ctx.getString(R.string.bj_why_first_two)
        BjReject.NOT_PAIR -> ctx.getString(R.string.bj_why_not_pair)
        BjReject.MAX_HANDS -> ctx.getString(R.string.bj_why_max_hands, BjRules.MAX_HANDS)
        BjReject.SPLIT_ACES -> ctx.getString(R.string.bj_why_split_aces)
        BjReject.SURRENDER_OFF -> ctx.getString(R.string.bj_why_surrender_off)
        BjReject.INSURANCE_OFF -> ctx.getString(R.string.bj_why_insurance_off)
    }
    var whyOverride by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(whyOverride) { if (whyOverride != null) { delay(3500); whyOverride = null } }
    val whyText = whyOverride ?: whyKey?.let(::explainText)

    fun blocked(reason: BjReject) {
        whyKey = reason
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }

    fun tapAction(a: BjAction) {
        val reason = game.explainAction(a)
        if (reason != BjReject.NONE) { blocked(reason); return }
        whyKey = null
        game.act(a)
    }

    // ---- the words ---------------------------------------------------------------------------
    val statusText = run {
        val es = s.eventSeat
        val who = seatName(es)
        val seat = s.seats.getOrNull(es)
        val hand = seat?.hands?.getOrNull(s.eventHand)
        val total = hand?.total?.value ?: 0
        val last = when (s.event) {
            BjEvent.BET -> seat?.let { ctx.sayFor(who, R.string.bj_ann_bet, R.string.bj_ann_bet_you, it.bet) }
            BjEvent.REBUY -> ctx.sayFor(who, R.string.bj_ann_rebuy, R.string.bj_ann_rebuy_you, s.config.startingChips)
            BjEvent.SIT_OUT -> ctx.sayFor(who, R.string.bj_ann_sit_out, R.string.bj_ann_sit_out_you)
            BjEvent.NO_BETS -> ctx.getString(R.string.bj_ann_no_bets)
            BjEvent.DEAL, BjEvent.PEEK_CLEAR -> s.upcard?.let { ctx.getString(R.string.bj_ann_deal, nameOf(it)) } +
                if (s.event == BjEvent.PEEK_CLEAR) " " + ctx.getString(R.string.bj_ann_peek_clear) else ""
            BjEvent.INSURANCE_TAKEN -> seat?.let { ctx.sayFor(who, R.string.bj_ann_ins_yes, R.string.bj_ann_ins_yes_you, it.insurance) }
            BjEvent.INSURANCE_DECLINED -> ctx.sayFor(who, R.string.bj_ann_ins_no, R.string.bj_ann_ins_no_you)
            BjEvent.DEALER_BLACKJACK -> ctx.getString(R.string.bj_ann_dealer_bj)
            BjEvent.HIT -> ctx.sayFor(who, R.string.bj_ann_hit, R.string.bj_ann_hit_you, total)
            BjEvent.STAND -> ctx.sayFor(who, R.string.bj_ann_stand, R.string.bj_ann_stand_you, total)
            BjEvent.DOUBLE -> ctx.sayFor(who, R.string.bj_ann_double, R.string.bj_ann_double_you, total)
            BjEvent.SPLIT -> ctx.sayFor(who, R.string.bj_ann_split, R.string.bj_ann_split_you)
            BjEvent.SURRENDER -> ctx.sayFor(who, R.string.bj_ann_surrender, R.string.bj_ann_surrender_you)
            BjEvent.BUST -> ctx.sayFor(who, R.string.bj_ann_bust, R.string.bj_ann_bust_you, total)
            BjEvent.REVEAL -> ctx.getString(R.string.bj_ann_reveal, s.dealerTotal?.value ?: 0)
            BjEvent.DEALER_HIT -> {
                val t = s.dealerTotal?.value ?: 0
                ctx.getString(if (t > 21) R.string.bj_ann_dealer_bust else R.string.bj_ann_dealer_hit, t)
            }
            BjEvent.ROUND_OVER -> ctx.getString(R.string.bj_ann_round_over)
            else -> if (s.phase == BjPhase.BETTING && active != null) null else ctx.getString(R.string.bj_ann_start)
        }.orEmpty()
        val shuffled = if (s.reshuffled && s.phase == BjPhase.BETTING) " " + ctx.getString(R.string.bj_ann_reshuffled) else ""
        val turn = when {
            hostLeft -> ctx.getString(R.string.bjw_host_left)
            s.phase == BjPhase.BETTING && active != null ->
                if (isMyTurn && !multiHuman) ctx.getString(R.string.bj_turn_bet_you)
                else if (isMyTurn) ctx.getString(R.string.bj_turn_bet_named, active.displayName)
                else ctx.getString(R.string.bj_turn_bet_other, active.displayName)
            s.phase == BjPhase.INSURANCE && active != null ->
                if (isMyTurn && !multiHuman) ctx.getString(R.string.bj_turn_ins_you)
                else ctx.getString(R.string.bj_turn_ins_other, active.displayName)
            s.phase == BjPhase.PLAYING && active != null ->
                if (isMyTurn && !multiHuman) ctx.getString(R.string.bj_turn_play_you)
                else if (isMyTurn) ctx.getString(R.string.bj_turn_play_named, active.displayName)
                else ctx.getString(R.string.bj_turn_play_other, active.displayName)
            s.phase == BjPhase.DEALER -> ctx.getString(R.string.bj_turn_dealer)
            s.phase == BjPhase.ROUND_OVER -> ctx.getString(R.string.bj_turn_next)
            else -> ""
        }
        listOf(last + shuffled, turn).filter { it.isNotBlank() }.joinToString(" ")
    }

    val leaveTitle = stringResource(R.string.bjw_leave_title)
    val leaveBody = stringResource(if (isNetworked) R.string.bjw_leave_body_net else R.string.bj_leave_body)
    val canCoach = GameCoach.forGame("blackjack") != null
    val (_, openCoach) = rememberClCoach("blackjack", ready = canCoach && s.round == 1 && s.phase == BjPhase.BETTING)

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = true,
        onLeave = game::leaveSession,
        onAbort = { game.abortMatch() },
        leaveKeepsProgress = !isNetworked,
        onHint = if (isMyTurn) ({ game.hint(); showHintNote = true }) else null,
        buttonFill = FELT_BOTTOM_LIGHT, buttonContent = FELT_TEXT,
        leaveTitle = leaveTitle, leaveBody = leaveBody,
        extraItems = { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bj_end_session)) },
                onClick = { dismiss(); game.leaveSession() }
            )
        }
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(if (dark) listOf(FELT_TOP_DARK, FELT_BOTTOM_DARK) else listOf(FELT_TOP_LIGHT, FELT_BOTTOM_LIGHT))
            )
        ) {
            val seatCount = s.seats.size
            // On a wide, short screen the betting/action controls move to a side panel: stacked under the table
            // they would be pushed off the bottom.
            val landscape = maxWidth > maxHeight * 1.15f
            val sidePanelW = 300.dp
            val tableW = if (landscape) maxWidth - sidePanelW else maxWidth
            val cardW = minOf(
                (tableW - 24.dp) / (seatCount * 1.55f + 0.4f),
                maxHeight * (if (seatCount <= 2) 0.17f else 0.14f),
                if (seatCount <= 2) 88.dp else 64.dp
            ).coerceAtLeast(34.dp)
            val shoeW = (cardW * 0.8f).coerceAtLeast(30.dp)

            val controls: @Composable () -> Unit = {
                Controls(
                    s = s, game = game, isMyTurn = isMyTurn && !hostLeft, legal = legal, pending = pending,
                    onPending = { pending = it },
                    lastBet = lastBets[s.activeSeat] ?: 0,
                    hintAction = hintAction,
                    onHint = { game.hint(); showHintNote = true },
                    onBlocked = ::blocked,
                    onTapAction = ::tapAction,
                    onTooMuch = { whyOverride = ctx.getString(R.string.bj_why_afford); playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) },
                    canNext = !hostLeft
                )
            }
            Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // ---- header ----
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    if (canCoach) ClCoachButton("blackjack", openCoach)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.bj_round_line, s.round),
                            color = FELT_TEXT, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            stringResource(R.string.bj_session_line, signed(sessionNet)),
                            color = if (sessionNet < 0) Color(0xFFFFB4A8) else if (sessionNet > 0) GOLD else FELT_TEXT.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    CasinoShoe(
                        left = s.shoeLeft, width = shoeW,
                        description = stringResource(R.string.bj_shoe_desc, s.shoeLeft),
                        onCenter = { shoeCenter = it }
                    )
                }

                // ---- dealer ----
                DealerRow(s, cardW, shoeCenter, initialKeys, flown, colorblind, nameOf)

                // ---- status ----
                Text(
                    statusText, color = FELT_TEXT, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )
                val note = if (showHintNote && hintAction != null && isMyTurn) {
                    stringResource(R.string.bj_hint_suggest, actionLabel(hintAction!!))
                } else null
                val line = whyText ?: note
                if (line != null) {
                    Text(
                        line, color = Color(0xFF1B1B1B), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .background(GOLD, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { liveRegion = if (whyText != null) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                    )
                }

                // ---- seats ----
                Row(
                    Modifier.weight(1f, fill = true).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    s.seats.forEachIndexed { i, seat ->
                        SeatPanel(
                            s = s, index = i, seat = seat, cardW = cardW,
                            isActive = i == s.activeSeat && s.phase != BjPhase.ROUND_OVER && s.phase != BjPhase.DEALER,
                            isLocal = isNetworked && i == localSeat,
                            shoeCenter = shoeCenter, initialKeys = initialKeys, flown = flown, colorblind = colorblind, nameOf = nameOf,
                            hintAction = if (isMyTurn && i == s.activeSeat) hintAction else null,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                // ---- controls ----
                if (!landscape) {
                    Spacer(Modifier.height(4.dp))
                    controls()
                }
            }
            if (landscape) {
                Box(Modifier.width(sidePanelW).fillMaxHeight().padding(end = 8.dp, top = 6.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    controls()
                }
            }
            }
            Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
            banner?.let { (text, tone) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text,
                        color = if (tone < 0) Color(0xFFFFD2CB) else GOLD,
                        fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                        modifier = Modifier
                            .scale(bannerScale.value)
                            .background(Color(0xCC0B1F17), RoundedCornerShape(18.dp))
                            .padding(horizontal = 22.dp, vertical = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }
}

private fun cardKeys(s: BjState): Set<String> {
    val out = HashSet<String>()
    s.dealer.forEach { out += "${s.round}:${it.uid}" }
    s.seats.forEach { seat -> seat.hands.forEach { h -> h.cards.forEach { out += "${s.round}:${it.uid}" } } }
    return out
}

@Composable
private fun actionLabel(a: BjAction): String = stringResource(
    when (a) {
        BjAction.HIT -> R.string.bj_hit
        BjAction.STAND -> R.string.bj_stand
        BjAction.DOUBLE -> R.string.bj_double
        BjAction.SPLIT -> R.string.bj_split
        BjAction.SURRENDER -> R.string.bj_surrender
        BjAction.TAKE_INSURANCE -> R.string.bj_hint_take_insurance
        BjAction.DECLINE_INSURANCE -> R.string.bj_no_insurance
    }
)

// ------------------------------------------------------------------------------------------------
// Cards on the felt
// ------------------------------------------------------------------------------------------------

/** One dealt card: flies in from the shoe the first time it is seen, then sits still. */
@Composable
private fun TableCard(
    s: BjState, c: BjCard, faceUp: Boolean, cardW: Dp, shoeCenter: Offset?, initialKeys: Set<String>, flown: HashSet<String>,
    colorblind: Boolean, nameOf: (Card) -> String, modifier: Modifier = Modifier
) {
    val key = "${s.round}:${c.uid}"
    val animate = remember { key !in initialKeys && key !in flown }
    val dealCount = s.seats.count { it.betPlaced } * 2 + 2
    val delayMs = if (c.uid < dealCount) c.uid * 130L else 0L
    SideEffect { flown += key }
    val hiddenDesc = stringResource(R.string.bjw_card_back_desc)
    DealIn(from = shoeCenter, delayMs = delayMs, animate = animate, modifier = modifier) {
        CasinoCard(
            card = c.card, faceUp = faceUp, width = cardW,
            description = if (faceUp && c.card.id >= 0) nameOf(c.card) else hiddenDesc,
            colorblind = colorblind
        )
    }
}

@Composable
private fun DealerRow(
    s: BjState, cardW: Dp, shoeCenter: Offset?, initialKeys: Set<String>, flown: HashSet<String>,
    colorblind: Boolean, nameOf: (Card) -> String
) {
    val total = s.dealerTotal
    val desc = if (s.dealer.isEmpty()) stringResource(R.string.bj_dealer_empty)
    else if (s.holeHidden) stringResource(R.string.bj_dealer_desc_hidden, nameOf(s.dealer[0].card))
    else stringResource(R.string.bj_dealer_desc, s.dealer.joinToString(", ") { nameOf(it.card) }, total?.value ?: 0)
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bj_dealer), color = FELT_TEXT, fontWeight = FontWeight.Bold)
            if (total != null) {
                Spacer(Modifier.width(8.dp))
                TotalBadge(
                    text = if (total.bust) stringResource(R.string.bj_out_bust) else stringResource(if (total.soft && total.value < 21) R.string.bj_total_soft else R.string.bj_total, total.value),
                    bad = total.bust
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.heightIn(min = cardHeightFor(cardW))) {
            val step = if (s.dealer.size <= 4) cardW * 0.55f else cardW * 0.4f
            Box(Modifier.size(cardW + step * (s.dealer.size - 1).coerceAtLeast(0), cardHeightFor(cardW))) {
                s.dealer.forEachIndexed { idx, c ->
                    key(c.uid) {
                        TableCard(
                            s, c, faceUp = !(idx == 1 && s.holeHidden), cardW = cardW, shoeCenter = shoeCenter, initialKeys = initialKeys,
                            flown = flown, colorblind = colorblind, nameOf = nameOf, modifier = Modifier.offset(x = step * idx)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalBadge(text: String, bad: Boolean = false, good: Boolean = false) {
    Text(
        text,
        color = if (bad) Color.White else if (good) Color(0xFF1B1B1B) else FELT_TEXT,
        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(if (bad) Color(0xFFB3261E) else if (good) GOLD else Color(0x66000000), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun SeatPanel(
    s: BjState, index: Int, seat: BjSeat, cardW: Dp, isActive: Boolean, isLocal: Boolean, shoeCenter: Offset?,
    initialKeys: Set<String>, flown: HashSet<String>, colorblind: Boolean, nameOf: (Card) -> String,
    hintAction: BjAction?, modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val youTag = if (isLocal) " " + stringResource(R.string.bjw_you_suffix) else ""
    val cpuTag = if (seat.isBot) " (" + stringResource(R.string.bjw_cpu_tag) + ")" else ""
    val name = ctx.seatLabel(seat.displayName) + youTag
    val handDescs = seat.hands.mapIndexed { hi, h ->
        val cards = h.cards.joinToString(", ") { nameOf(it.card) }
        val total = if (h.total.soft && h.total.value < 21) ctx.getString(R.string.bj_total_soft, h.total.value) else ctx.getString(R.string.bj_total, h.total.value)
        val outcome = h.outcome?.let { " " + outcomeLabel(ctx, it) } ?: if (h.total.bust) " " + ctx.getString(R.string.bj_out_bust) else ""
        ctx.getString(R.string.bj_hand_desc, hi + 1, h.bet, cards, total) + outcome
    }
    val seatDesc = ctx.getString(R.string.bj_seat_desc, name + cpuTag, seat.stack) +
        (if (isActive) " " + ctx.getString(R.string.bj_seat_turn) else "") +
        (if (seat.hands.isEmpty() && seat.betPlaced) " " + ctx.getString(R.string.bj_seat_bet, seat.bet) else "") +
        (if (handDescs.isNotEmpty()) " " + handDescs.joinToString(" ") else "")
    val ring = if (isActive) GOLD else Color.Transparent
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) Color(0x33F2CD6B) else Color(0x22000000))
            .border(if (isActive) 2.dp else 1.dp, if (isActive) ring else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .widthIn(min = 92.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = seatDesc },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            name, color = FELT_TEXT, fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(stringResource(R.string.bj_chips_line, seat.stack), color = GOLD, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        if (seat.hands.isEmpty()) {
            if (seat.betPlaced) ChipPile(seat.bet, chipSize = (cardW * 0.6f).coerceAtLeast(28.dp))
            else if (seat.sittingOut) Text(stringResource(R.string.bj_sitting_out), color = FELT_TEXT.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            else Spacer(Modifier.height(cardHeightFor(cardW) * 0.5f))
        }
        seat.hands.forEachIndexed { hi, h ->
            val isCurrent = isActive && hi == s.activeHand && s.phase == BjPhase.PLAYING
            Column(
                Modifier.padding(vertical = 3.dp)
                    .then(if (isCurrent) Modifier.border(1.dp, GOLD, RoundedCornerShape(8.dp)).padding(2.dp) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val n = h.cards.size
                val step = if (n <= 3) cardW * 0.5f else cardW * 0.34f
                Box(Modifier.size(cardW + step * (n - 1).coerceAtLeast(0), cardHeightFor(cardW))) {
                    h.cards.forEachIndexed { ci, c ->
                        key(c.uid) {
                            TableCard(s, c, true, cardW, shoeCenter, initialKeys, flown, colorblind, nameOf, Modifier.offset(x = step * ci))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val totalText = if (h.total.bust) stringResource(R.string.bj_out_bust)
                    else if (h.total.soft && h.total.value < 21) stringResource(R.string.bj_total_soft, h.total.value)
                    else stringResource(R.string.bj_total, h.total.value)
                    TotalBadge(totalText, bad = h.total.bust, good = h.total.value == 21 && !h.total.bust)
                    Text(stringResource(R.string.bj_bet_small, h.bet), color = GOLD, style = MaterialTheme.typography.labelSmall)
                }
                h.outcome?.let { o ->
                    val net = h.credit - h.bet
                    Text(
                        outcomeLabel(ctx, o) + " " + signed(net),
                        color = if (net > 0) GOLD else if (net < 0) Color(0xFFFFB4A8) else FELT_TEXT,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        if (seat.insurance > 0) {
            Text(stringResource(R.string.bj_insured, seat.insurance), color = FELT_TEXT.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
        }
        if (s.phase == BjPhase.ROUND_OVER && seat.hands.isNotEmpty()) {
            Text(
                stringResource(R.string.bj_round_net, signed(seat.roundNet)),
                color = if (seat.roundNet > 0) GOLD else if (seat.roundNet < 0) Color(0xFFFFB4A8) else FELT_TEXT,
                fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge
            )
        }
        if (hintAction != null) Spacer(Modifier.height(0.dp))
    }
}

private fun outcomeLabel(ctx: Context, outcome: String): String = ctx.getString(
    when (outcome) {
        BjOutcome.BLACKJACK -> R.string.bj_out_blackjack
        BjOutcome.WIN -> R.string.bj_out_win
        BjOutcome.PUSH -> R.string.bj_out_push
        BjOutcome.SURRENDER -> R.string.bj_out_surrender
        BjOutcome.BUST -> R.string.bj_out_bust
        else -> R.string.bj_out_lose
    }
)

// ------------------------------------------------------------------------------------------------
// Controls
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    s: BjState, game: BlackjackGame, isMyTurn: Boolean, legal: Set<BjAction>, pending: Int, onPending: (Int) -> Unit,
    lastBet: Int, hintAction: BjAction?, onHint: () -> Unit, onBlocked: (BjReject) -> Unit, onTapAction: (BjAction) -> Unit,
    onTooMuch: () -> Unit, canNext: Boolean
) {
    val active = s.seats.getOrNull(s.activeSeat)
    val btn = Modifier.heightIn(min = 52.dp).widthIn(min = 76.dp)
    val unavailable = stringResource(R.string.bjw_unavailable)
    Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        when (s.phase) {
            BjPhase.BETTING -> if (isMyTurn && active != null) {
                if (active.stack < BjRules.MIN_BET) {
                    Text(stringResource(R.string.bj_broke, LocalContext.current.seatLabel(active.displayName)), color = FELT_TEXT, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { game.rebuy() }, modifier = btn) { Text(stringResource(R.string.bj_rebuy, s.config.startingChips)) }
                        OutlinedButton(onClick = { game.sitOut() }, modifier = btn, colors = feltOutlined()) { Text(stringResource(R.string.bj_sit_out)) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.BottomCenter) { if (pending > 0) ChipPile(pending, 36.dp) }
                        Text(
                            stringResource(R.string.bj_bet_line, pending),
                            color = FELT_TEXT, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BjRules.CHIP_VALUES.forEach { v ->
                            Box(
                                Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .clickable(role = Role.Button) { if (pending + v <= active.stack) onPending(pending + v) else onTooMuch() },
                                contentAlignment = Alignment.Center
                            ) { ChipView(v, 48.dp, description = stringResource(R.string.bj_chip_desc, v)) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { onPending(0) }, enabled = pending > 0, modifier = btn, colors = feltOutlined()) { Text(stringResource(R.string.bj_clear_bet)) }
                        if (lastBet in BjRules.MIN_BET..active.stack && lastBet != pending) {
                            OutlinedButton(onClick = { onPending(lastBet) }, modifier = btn, colors = feltOutlined()) { Text(stringResource(R.string.bj_rebet, lastBet)) }
                        }
                        OutlinedButton(onClick = { onPending(active.stack / BjRules.MIN_BET * BjRules.MIN_BET) }, modifier = btn, colors = feltOutlined()) {
                            Text(stringResource(R.string.bj_all_in))
                        }
                        Button(
                            onClick = {
                                val r = game.explainBet(pending)
                                if (r != BjReject.NONE) onBlocked(r) else game.bet(pending)
                            },
                            enabled = pending >= BjRules.MIN_BET, modifier = btn, colors = feltFilled()
                        ) { Text(stringResource(R.string.bj_place_bet), fontWeight = FontWeight.Bold) }
                    }
                }
            }
            BjPhase.INSURANCE -> if (isMyTurn && active != null) {
                val cost = active.bet / 2
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { if (game.canInsure(s)) game.insurance(true) else onBlocked(BjReject.CANT_AFFORD) },
                        modifier = btn,
                        border = if (hintAction == BjAction.TAKE_INSURANCE) androidx.compose.foundation.BorderStroke(3.dp, GOLD) else null
                    ) { Text(stringResource(R.string.bj_insure, cost)) }
                    Button(
                        onClick = { game.insurance(false) }, modifier = btn,
                        border = if (hintAction == BjAction.DECLINE_INSURANCE) androidx.compose.foundation.BorderStroke(3.dp, GOLD) else null
                    ) { Text(stringResource(R.string.bj_no_insurance)) }
                    OutlinedButton(onClick = onHint, modifier = btn, colors = feltOutlined()) { Text(stringResource(R.string.bj_hint)) }
                }
            }
            BjPhase.PLAYING -> if (isMyTurn) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(BjAction.HIT, BjAction.STAND, BjAction.DOUBLE, BjAction.SPLIT, BjAction.SURRENDER).forEach { a ->
                        val ok = a in legal
                        val suggested = hintAction == a
                        Button(
                            onClick = { onTapAction(a) },
                            modifier = btn.graphicsLayer { alpha = if (ok) 1f else 0.5f }
                                .semantics { if (!ok) stateDescription = unavailable },
                            colors = if (ok) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = if (suggested) androidx.compose.foundation.BorderStroke(3.dp, GOLD) else null
                        ) {
                            Text(actionLabel(a), fontWeight = if (suggested) FontWeight.Black else FontWeight.Bold)
                        }
                    }
                    OutlinedButton(onClick = onHint, modifier = btn, colors = feltOutlined()) { Text(stringResource(R.string.bj_hint)) }
                }
            }
            BjPhase.ROUND_OVER -> {
                Button(onClick = { game.nextRound() }, enabled = canNext, modifier = btn.widthIn(min = 180.dp)) {
                    Text(stringResource(R.string.bj_next_round), fontWeight = FontWeight.Bold)
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun feltOutlined() = ButtonDefaults.outlinedButtonColors(contentColor = FELT_TEXT, disabledContentColor = FELT_TEXT.copy(alpha = 0.5f))

/** A filled button that stays legible on the felt while disabled (the default 12% tint vanishes on green). */
@Composable
private fun feltFilled() = ButtonDefaults.buttonColors(disabledContainerColor = Color.White.copy(alpha = 0.2f), disabledContentColor = FELT_TEXT.copy(alpha = 0.6f))
