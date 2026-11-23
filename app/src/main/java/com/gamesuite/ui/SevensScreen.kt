package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.Suit
import com.gamesuite.games.president.PsCardFace
import com.gamesuite.games.president.PsFelt
import com.gamesuite.games.president.PsHand
import com.gamesuite.games.president.PsLabels
import com.gamesuite.games.president.PsSeatPlate
import com.gamesuite.games.president.drawSuit
import com.gamesuite.games.sevens.SevensGame
import com.gamesuite.games.sevens.SevensReject
import com.gamesuite.games.sevens.SevensRuleSet
import com.gamesuite.games.sevens.SevensRules
import com.gamesuite.games.sevens.SevensState
import com.gamesuite.games.sevens.SvEvent
import com.gamesuite.games.sevens.SvPhase
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

private val SevensFelt = Color(0xFF1E6B3A)

/**
 * Sevens (Fan Tan) on a green felt: four rows, one per suit, grow outwards from the sevens. A glowing slot
 * (and the matching glowing card in your hand) shows every card you can play; tap the card, or the slot, to
 * play it. When nothing fits you must pass. New cards pop onto their slot, passes speak from the seat's
 * plate, and the first hand to empty wins the round. Suits differ by shape (colour-blind safe), every row
 * and card is described for TalkBack and the status line is a live region.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SevensScreen(
    sessionManager: GameSessionManager,
    game: SevensGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val cardSounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.DOMINOES, enabled = musicEnabled)
    val state by game.state
    val hintCard by game.hintCard
    val hintShown by game.hintShown
    val hostLeft by game.hostLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = psTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f, SevensFelt)
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
        game.rules = SevensRuleSet(passPenalty = extras["passPenalty"] == "true")
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
    val pop = remember { Animatable(1f) }
    var popCard by remember { mutableStateOf<Int?>(null) }
    var popSeat by remember { mutableStateOf<Int?>(null) }
    var lastSerial by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var bubbleSeat by remember { mutableStateOf<Int?>(null) }
    var dealKey by remember { mutableIntStateOf(0) }
    var whyKey by remember { mutableStateOf<SevensReject?>(null) }
    var whyCount by remember { mutableIntStateOf(0) }
    var revealedSeat by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state?.eventSerial) {
        val s = state ?: return@LaunchedEffect
        val first = lastSerial < 0
        if (s.eventSerial == lastSerial) return@LaunchedEffect
        lastSerial = s.eventSerial
        whyKey = null
        val seat = s.eventSeat
        val localMover = !isNetworked || seat == game.localSeat
        try {
            when (s.event) {
                SvEvent.DEAL -> {
                    popCard = null
                    dealKey += 1
                    if (!first) { cardSounds.playShuffle(); haptics(HapticSignal.LIGHT_TICK) }
                }
                SvEvent.PLAY, SvEvent.ROUND_END -> {
                    if (!first && s.eventCard != null) {
                        busy = true
                        popCard = s.eventCard
                        popSeat = seat
                        if (!reduced) { pop.snapTo(0f) }
                        cardSounds.playPlace(rate = 0.92f + 0.015f * SevensRules.rankIndex(s.eventCard))
                        playSfx(SfxKind.WHOOSH)
                        haptics(if (localMover) HapticSignal.NORMAL_ACTION else HapticSignal.LIGHT_TICK)
                        if (!reduced) pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
                        playSfx(SfxKind.SOLID_THUNK)
                    } else if (s.eventCard != null) {
                        popCard = null
                    }
                    if (s.event == SvEvent.ROUND_END && !first) {
                        val me = game.localSeat
                        val iWon = if (isNetworked) s.winnerSeat == me else s.winnerSeat?.let { !s.seats[it].isBot } == true
                        if (iWon) {
                            playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                            if (!reduced) burst.spawn(Offset(0.5f, 0.3f), 70, listOf(Color(0xFFFFD54A), Color.White, Color(0xFF7CE0C3)), 0.25f..0.9f, 1.0f..1.9f, 0.9f)
                        } else { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                    }
                }
                SvEvent.PASS -> if (!first && seat != null) {
                    scope.launch {
                        bubbleSeat = seat
                        delay(gameSpeed.scale(1200L))
                        if (bubbleSeat == seat) bubbleSeat = null
                    }
                    playSfx(if (s.rules.passPenalty) SfxKind.INVALID_BUZZ else SfxKind.LIGHT_TICK)
                    haptics(if (localMover && s.rules.passPenalty) HapticSignal.FAILURE else HapticSignal.LIGHT_TICK)
                }
            }
        } finally {
            busy = false
            if (!reduced) pop.snapTo(1f)
        }
    }

    var botThinking by remember { mutableStateOf(false) }
    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (!game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            val base = when (game.difficulty) {
                CpuDifficulty.EASY -> 700L
                CpuDifficulty.MEDIUM -> 850L
                CpuDifficulty.HARD -> 1000L
            }
            delay(gameSpeed.scale(base + Random.nextLong(0L, 500L)).coerceAtLeast(400L))
            while (busy) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val helpTitle = stringResource(R.string.sv_help_title)
    val helpText = stringResource(R.string.sv_help_text)
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
    val actingSeat: Int? = if (s.phase == SvPhase.PLAY && game.isMyTurn(s)) s.currentSeat else null
    val coverFor: Int? = if (hotSeat && actingSeat != null && revealedSeat != actingSeat) actingSeat else null
    val shownSeat: Int = when {
        game.isSpectator -> -1
        hotSeat -> revealedSeat ?: -1
        else -> game.viewSeat(s)
    }
    val myHand: List<Int> = if (shownSeat in s.seats.indices && coverFor == null) s.seats[shownSeat].hand else emptyList()
    val isMyTurn = game.isMyTurn(s) && coverFor == null
    val playable: Set<Int> = if (isMyTurn) SevensRules.playableCards(myHand, s.low, s.high).toSet() else emptySet()
    val roundsDone = s.wins.values.sum()

    fun nm(i: Int) = labels.displayName(s.seats.getOrNull(i)?.displayName ?: "")
    fun raw(i: Int) = s.seats.getOrNull(i)?.displayName ?: ""

    // ---- words -----------------------------------------------------------------------------
    val lastLine = when (s.event) {
        SvEvent.DEAL -> labels.aware(raw(s.currentSeat), R.string.sv_ann_deal, R.string.sv_yg_ann_deal)
        SvEvent.PLAY -> labels.aware(raw(s.eventSeat ?: 0), R.string.ps_ann_play, R.string.ps_yg_ann_play, labels.cardDesc(s.eventCard ?: -1))
        SvEvent.PASS -> labels.aware(raw(s.eventSeat ?: 0), R.string.ps_ann_pass, R.string.ps_yg_ann_pass) +
            if (s.rules.passPenalty) " " + androidContext.getString(R.string.sv_ann_penalty, SevensRules.PASS_PENALTY) else ""
        else -> labels.aware(raw(s.winnerSeat ?: 0), R.string.sv_ann_round_end, R.string.sv_yg_ann_round_end)
    }
    val turnLine = when {
        s.roundOver -> ""
        hostLeft -> androidContext.getString(R.string.ps_host_left)
        isMyTurn && !s.started -> androidContext.getString(R.string.sv_turn_open)
        isMyTurn && playable.isEmpty() -> androidContext.getString(R.string.sv_turn_pass)
        isMyTurn -> androidContext.getString(R.string.sv_turn_play)
        botThinking -> labels.aware(raw(s.currentSeat), R.string.ps_thinking, R.string.ps_yg_thinking)
        else -> labels.aware(raw(s.currentSeat), R.string.ps_turn_named, R.string.ps_yg_turn_named)
    }
    val statusText = listOf(lastLine, turnLine).filter { it.isNotBlank() }.joinToString(" ")
    val whyText: String? = whyKey?.let { k ->
        when (k) {
            SevensReject.NONE -> null
            SevensReject.NOT_STARTED, SevensReject.WRONG_PHASE -> androidContext.getString(R.string.ps_why_phase)
            SevensReject.NOT_YOUR_TURN -> androidContext.getString(R.string.ps_why_wait)
            SevensReject.SPECTATOR -> androidContext.getString(R.string.ps_why_spectator)
            SevensReject.NOT_IN_HAND -> androidContext.getString(R.string.ps_why_not_in_hand)
            SevensReject.NOT_PLAYABLE -> androidContext.getString(R.string.sv_why_not_playable)
            SevensReject.MUST_START_WITH_SEVEN_OF_DIAMONDS -> androidContext.getString(R.string.sv_why_open)
            SevensReject.MUST_PLAY_IF_ABLE -> androidContext.getString(R.string.sv_why_must_play)
        }
    }
    LaunchedEffect(whyKey, whyCount) { if (whyKey != null) { delay(4000); whyKey = null } }
    val hintLine: String? = if (hintShown && isMyTurn) {
        if (hintCard == null) androidContext.getString(R.string.sv_hint_pass) else androidContext.getString(R.string.sv_hint_play, labels.cardDesc(hintCard!!))
    } else null

    fun refuse(why: SevensReject) {
        whyKey = why; whyCount += 1
        playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
    }

    fun playCard(id: Int) {
        val why = game.tryPlay(id)
        if (why != SevensReject.NONE) refuse(why)
    }

    fun doPass() {
        val why = game.tryPass()
        if (why != SevensReject.NONE) refuse(why)
    }

    fun doHint() {
        scope.launch {
            game.requestHint()
            playSfx(SfxKind.LIGHT_TICK)
        }
    }

    fun rowDescription(suit: Int): String {
        val name = labels.suitName(Suit.values()[suit])
        return if (s.low[suit] == 0) androidContext.getString(R.string.sv_row_none, name)
        else androidContext.getString(
            R.string.sv_row_range, name,
            labels.rankName(PsRank.of(s.low[suit])), labels.rankName(PsRank.of(s.high[suit]))
        )
    }
    val boardDescription = androidContext.getString(R.string.sv_board_desc, (0 until 4).joinToString(". ") { rowDescription(it) })

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = true,
        onLeave = game::leaveSession,
        onAbort = { if (roundsDone > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = roundsDone == 0,
        onUndo = if (!isNetworked) ({ game.undo() }) else null,
        canUndo = game.canUndo,
        onHint = if (!isNetworked) ({ doHint() }) else null,
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.ps_leave_title),
        leaveBody = stringResource(if (roundsDone > 0) R.string.sv_leave_body_session else R.string.ps_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("sevens", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val shortScreen = !landscape && maxHeight < 620.dp
            val cardW: Dp = (56.dp * cardScale).coerceIn(44.dp, 80.dp)

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("sevens", openCoach)
                    Text(
                        stringResource(R.string.sv_round_n, s.roundNumber), color = theme.text, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
                    )
                    if (s.rules.passPenalty) {
                        Text(
                            stringResource(R.string.sv_penalty_chip, SevensRules.PASS_PENALTY), color = theme.text,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.background(theme.panel, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText ?: hintLine, theme.text, theme.panel)
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
                        val turn = i == s.currentSeat && s.phase == SvPhase.PLAY
                        val desc = buildString {
                            append(nm(i))
                            if (seat.isBot) append(" (").append(stringResource(R.string.ps_cpu_tag)).append(")")
                            if (isNetworked && i == game.localSeat) append(" ").append(stringResource(R.string.ps_you_suffix))
                            append(", ").append(labels.cardsLeft(seat.hand.size))
                            if (turn) append(", ").append(stringResource(R.string.ps_on_turn))
                        }
                        PsSeatPlate(
                            name = nm(i), seatIndex = i, cardCount = seat.hand.size, isTurn = turn, reduced = reduced,
                            description = desc, bubble = if (bubbleSeat == i) stringResource(R.string.ps_pass_bubble) else null,
                            textColor = theme.text, plateColor = theme.panel, accentText = theme.accent
                        )
                    }
                }
            }

            val board: @Composable (Modifier) -> Unit = { mod ->
                PsFelt(theme.felt, mod.semantics { contentDescription = boardDescription }) {
                    SevensBoard(
                        s = s, hand = myHand, playable = playable, hintCard = if (isMyTurn) hintCard else null,
                        popCard = popCard, popProgress = pop.value, colorblind = colorblind, reduced = reduced,
                        onTapSlot = { id -> if (id in myHand) playCard(id) },
                        rowDescription = ::rowDescription
                    )
                    Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                }
            }

            val bottom: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (shownSeat in s.seats.indices) {
                        val me = s.seats[shownSeat]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(nm(shownSeat), color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text(labels.cardsLeft(me.hand.size), color = theme.text.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                            if (s.rules.passPenalty && me.passes > 0) Text(stringResource(R.string.sv_passes_count, me.passes), color = theme.text.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        PsHand(
                            cards = myHand, selected = emptySet(), playable = if (isMyTurn) playable else null,
                            hint = if (isMyTurn && hintCard != null) setOf(hintCard!!) else emptySet(),
                            enabled = isMyTurn, colorblind = colorblind, reduced = reduced, dealKey = dealKey,
                            cardWidth = cardW, descriptionOf = { id ->
                                labels.cardDesc(id) + if (isMyTurn && id in playable) " " + androidContext.getString(R.string.sv_playable) else ""
                            },
                            selectedLabel = stringResource(R.string.ps_selected),
                            onTap = { id -> if (!isMyTurn) refuse(game.explainPlay(id).let { if (it == SevensReject.NONE) SevensReject.NOT_YOUR_TURN else it }) else playCard(id) },
                            gameSpeedScale = { gameSpeed.scale(it) }, modifier = Modifier.fillMaxWidth(),
                            minStep = if (landscape) 34.dp else 44.dp
                        )
                        Spacer(Modifier.height(6.dp))
                        if (isMyTurn) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                val forced = playable.isEmpty()
                                if (forced) {
                                    Button(onClick = ::doPass, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 140.dp)) {
                                        Text(stringResource(R.string.ps_pass), fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    OutlinedButton(onClick = ::doPass, modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 120.dp)) {
                                        Text(stringResource(R.string.ps_pass))
                                    }
                                }
                            }
                            if (!isNetworked) {
                                Spacer(Modifier.height(6.dp))
                                ClUndoHintRow(canUndo = game.canUndo, canHint = true, onUndo = { game.undo() }, onHint = ::doHint, contentColor = theme.text)
                            }
                        }
                    }
                    if (s.roundOver) {
                        Spacer(Modifier.height(8.dp))
                        SvResultPanel(s, labels, game, hostLeft)
                    }
                }
            }

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
                    Column(Modifier.weight(0.56f).fillMaxSize()) {
                        board(Modifier.weight(1f).fillMaxWidth().heightIn(min = 140.dp))
                    }
                    Column(Modifier.weight(0.44f).fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        header(); plates()
                        bottom()
                    }
                }
            } else if (shortScreen) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header(); plates()
                    board(Modifier.fillMaxWidth().height(190.dp))
                    Spacer(Modifier.height(6.dp)); bottom()
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header(); plates()
                    board(Modifier.weight(1f).fillMaxWidth().heightIn(min = 150.dp))
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { bottom() }
                }
            }
            cover()
            Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
        }
    }
}

/** Rank by Sevens index (1 = ace ... 13 = king) for row descriptions. */
private object PsRank {
    fun of(index: Int) = com.gamesuite.games.cards.Rank.values().first { SevensRules.rankIndex(it) == index }
}

/**
 * The four rows. A laid card is drawn small; an empty slot is a ghost with its rank and suit shape. Slots
 * for cards the viewer can play right now glow; tapping one plays that card. The newest card pops in.
 * Each row is one TalkBack node (its range), not 13.
 */
@Composable
private fun SevensBoard(
    s: SevensState,
    hand: List<Int>,
    playable: Set<Int>,
    hintCard: Int?,
    popCard: Int?,
    popProgress: Float,
    colorblind: Boolean,
    reduced: Boolean,
    onTapSlot: (Int) -> Unit,
    rowDescription: (Int) -> String
) {
    val pulse = if (reduced) 0.7f else rememberInfiniteTransition(label = "sv-pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "sv-pulse-a"
    ).value
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp)) {
        val slotW = (maxWidth / 13)
        val cardW = (slotW - 2.dp).coerceAtLeast(14.dp)
        val cardH = cardW * 1.4f
        val rowH = cardH + 4.dp
        val density = LocalDensity.current
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            for (suit in 0 until 4) {
                Row(
                    Modifier.height(rowH).semantics(mergeDescendants = false) { contentDescription = rowDescription(suit) }.clearAndSetSemantics { contentDescription = rowDescription(suit) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    for (rank in 1..13) {
                        val id = SevensRules.cardId(suit, rank)
                        val laid = s.low[suit] != 0 && rank in s.low[suit]..s.high[suit]
                        val canPlay = id in playable
                        val isHint = id == hintCard
                        Box(
                            Modifier
                                .width(slotW).height(rowH)
                                .then(if (canPlay) Modifier.clickable(role = Role.Button, onClickLabel = null) { onTapSlot(id) } else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            if (laid) {
                                val popping = id == popCard && !reduced
                                PsCardFace(
                                    id, cardW, colorblind = colorblind,
                                    modifier = Modifier.graphicsLayer {
                                        if (popping) {
                                            val p = popProgress
                                            val sc = 1f + 0.9f * (1f - p)
                                            scaleX = sc; scaleY = sc
                                            translationY = -(1f - p) * with(density) { 40.dp.toPx() }
                                            alpha = (0.4f + 0.6f * p).coerceIn(0f, 1f)
                                        }
                                    }
                                )
                            } else {
                                GhostSlot(suit, rank, cardW, cardH, canPlay, isHint, pulse, colorblind)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GhostSlot(suit: Int, rank: Int, w: Dp, h: Dp, glow: Boolean, hint: Boolean, pulse: Float, colorblind: Boolean) {
    val suitValue = Suit.values()[suit]
    val ringColor = if (hint) Color(0xFF7CE0C3) else Color(0xFFFFE082)
    Canvas(Modifier.size(w, h)) {
        val r = CornerRadius(size.width * 0.12f)
        drawRoundRect(Color.White.copy(alpha = if (glow) 0.18f else 0.06f), Offset.Zero, size, r)
        drawRoundRect(
            if (glow) ringColor.copy(alpha = 0.35f + 0.65f * pulse) else Color.White.copy(alpha = 0.16f),
            Offset.Zero, Size(size.width, size.height), r, style = Stroke(if (glow) size.width * 0.12f else size.width * 0.05f)
        )
        val c = Offset(size.width / 2f, size.height / 2f)
        if (glow) drawSuit(suitValue, c, size.width * 0.26f, Color.White.copy(alpha = 0.85f))
        else drawSuit(suitValue, c, size.width * 0.22f, Color.White.copy(alpha = if (rank == 7) 0.3f else 0.14f))
    }
}

/** The end-of-round card: scores and totals, the winner first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SvResultPanel(s: SevensState, labels: PsLabels, game: SevensGame, hostLeft: Boolean) {
    val res = LocalContext.current.resources
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.sv_result_title, s.roundNumber), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            val winner = s.winnerSeat
            if (winner != null) {
                Text(
                    labels.aware(s.seats[winner].displayName, R.string.sv_winner_line, R.string.sv_yg_winner_line),
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            val order = s.seats.indices.sortedWith(compareBy({ s.seats[it].roundScore }, { if (it == winner) 0 else 1 }))
            order.forEachIndexed { rank, i ->
                val seat = s.seats[i]
                val roundText = res.getQuantityString(R.plurals.sv_points, seat.roundScore, seat.roundScore)
                val totalText = res.getQuantityString(R.plurals.sv_points, seat.total, seat.total)
                Text(
                    stringResource(R.string.sv_result_row, rank + 1, labels.displayName(seat.displayName), roundText, totalText),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    fontWeight = if (i == winner) FontWeight.Bold else FontWeight.Normal
                )
                if (s.rules.passPenalty && seat.passes > 0) {
                    Text(stringResource(R.string.sv_result_passes, seat.passes), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.sv_rounds_won, s.seats.joinToString(" · ") { "${labels.displayName(it.displayName)} ${s.wins[it.playerId] ?: 0}" }),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!game.isSpectator) {
                    Button(onClick = { game.continueSession() }, enabled = !hostLeft, modifier = Modifier.heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.sv_next_round), fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(onClick = { game.leaveSession() }, modifier = Modifier.heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.ps_back_to_menu))
                }
            }
        }
    }
}
