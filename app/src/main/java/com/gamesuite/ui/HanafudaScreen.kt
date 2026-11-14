package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.hanafuda.HanaCard
import com.gamesuite.games.hanafuda.HanaKind
import com.gamesuite.games.hanafuda.HanaSpecial
import com.gamesuite.games.hanafuda.HanafudaCards
import com.gamesuite.games.hanafuda.HanafudaEvent
import com.gamesuite.games.hanafuda.HanafudaGame
import com.gamesuite.games.hanafuda.HanafudaLayout
import com.gamesuite.games.hanafuda.HanafudaOptions
import com.gamesuite.games.hanafuda.HanafudaReject
import com.gamesuite.games.hanafuda.HanafudaState
import com.gamesuite.games.hanafuda.HfMetrics
import com.gamesuite.games.hanafuda.HfPlacement
import com.gamesuite.games.hanafuda.HfZone
import com.gamesuite.games.hanafuda.KoiEnd
import com.gamesuite.games.hanafuda.KoiHint
import com.gamesuite.games.hanafuda.KoiPhase
import com.gamesuite.games.hanafuda.KoiRound
import com.gamesuite.games.hanafuda.KoiRules
import com.gamesuite.games.hanafuda.Yaku
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private val HfTableTop = Color(0xFF4A0F1A)
private val HfTableBottom = Color(0xFF2B0A12)
private val HfGold = Color(0xFFF0D9A8)
private val HfGlow = Color(0xFFFFE066)
private val HfHintRing = Color(0xFF3DDCFF)
private val HfInk = Color(0xFF2A1A1E)

/** Everything the screen needs to read card names aloud and in text. */
private class HfNames(
    val months: Array<String>,
    val specials: Array<String>,
    val ribbons: Array<String>,
    val kinds: Array<String>,
    val yaku: Array<String>,
    private val fSpecial: String,
    private val fRibbon: String,
    private val fPlain: String
) {
    fun kind(c: HanaCard): String = kinds[c.kind.ordinal]
    fun card(id: Int): String {
        if (!HanafudaCards.isReal(id)) return ""
        val c = HanafudaCards.card(id)
        val month = months[c.month]
        return when {
            c.special != HanaSpecial.NONE -> String.format(fSpecial, specials[c.special.ordinal], kind(c).lowercase(), month)
            c.kind == HanaKind.RIBBON -> String.format(fRibbon, ribbons[c.ribbon.ordinal], month)
            else -> String.format(fPlain, month)
        }
    }
    fun cards(ids: List<Int>): String = ids.joinToString(" + ") { card(it) }
    fun yakuName(y: Yaku): String = yaku[y.ordinal]
}

private class HfDrag(val card: Int, val offset: Offset)

/**
 * Hanafuda (Koi-Koi). The 48 cards are original vector art ([HanafudaCardFace]); every card on the
 * table is one composable keyed by its id and placed by [HanafudaLayout.placements], so a play, a
 * capture or a deal animates as each card glides to its slot (a guest sees the same, since the table
 * it receives differs only in where the cards are). Tap a hand card to select it (matching field
 * cards glow), tap a glowing card or the card again to play; dragging a hand card onto the field
 * works too. TalkBack gets a spoken name and an action on every card, a summary of each zone, and a
 * Captured list; nothing needs a drag.
 */
@Composable
fun HanafudaScreen(
    sessionManager: GameSessionManager,
    game: HanafudaGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val reducedMotion = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    val state by game.state
    val event by game.event
    val eventSeq by game.eventSeq
    val hint by game.hint
    val opponentLeft by game.opponentLeft

    var selectedHand by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableStateOf<HfDrag?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var capturedSeat by remember { mutableStateOf<Int?>(null) }
    var revealedKey by remember { mutableStateOf("") }
    var botThinking by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.totalRounds = HanafudaOptions.roundsFrom(extras)
        game.yakuSet = HanafudaOptions.yakuSetFrom(extras)
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
    val isPassAndPlay = !isNetworked && activeCtx != null && activeCtx.players.none { it.isBot }

    // The computer's turn: think, then one action; the state change restarts this for its next action.
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        if (!game.botToMove()) {
            botThinking = false
            return@LaunchedEffect
        }
        botThinking = true
        val base = when (s.round.phase) {
            KoiPhase.PLAY_HAND -> 950L
            KoiPhase.DRAW_READY -> 520L
            KoiPhase.CHOOSE_DRAW_MATCH -> 650L
            else -> 1100L
        }
        val settle = if (game.event.value is HanafudaEvent.RoundStarted) 1100L else 0L
        delay(gameSpeed.scale(base) + settle)
        game.playBotStepAsync()
    }

    val names = HfNames(
        months = stringArrayResource(R.array.hf_month_names),
        specials = stringArrayResource(R.array.hf_special_names),
        ribbons = stringArrayResource(R.array.hf_ribbon_names),
        kinds = stringArrayResource(R.array.hf_kind_names),
        yaku = stringArrayResource(R.array.hf_yaku_names),
        fSpecial = stringResource(R.string.hf_card_special),
        fRibbon = stringResource(R.string.hf_card_ribbon),
        fPlain = stringResource(R.string.hf_card_plain)
    )

    // Cues per event.
    LaunchedEffect(eventSeq) {
        note = null
        when (val e = game.event.value) {
            is HanafudaEvent.Played -> {
                sounds.playPlace()
                haptics(if (e.taken.isEmpty()) HapticSignal.LIGHT_TICK else HapticSignal.NORMAL_ACTION)
                if (e.taken.any { HanafudaCards.card(it).kind == HanaKind.BRIGHT }) playSfx(SfxKind.SUCCESS_CHIME)
            }
            is HanafudaEvent.Drew -> {
                sounds.playDraw()
                haptics(HapticSignal.NORMAL_ACTION)
            }
            is HanafudaEvent.NeedsChoice -> sounds.playDraw()
            is HanafudaEvent.Decided -> {
                playSfx(if (e.koiKoi) SfxKind.WHOOSH else SfxKind.SUCCESS_CHIME)
                haptics(if (e.koiKoi) HapticSignal.ESCALATING else HapticSignal.SUCCESS)
            }
            is HanafudaEvent.Rejected -> {
                playSfx(SfxKind.INVALID_BUZZ)
                haptics(HapticSignal.FAILURE)
            }
            is HanafudaEvent.RoundStarted -> sounds.playShuffle()
            is HanafudaEvent.Undone -> playSfx(SfxKind.LIGHT_TICK)
            is HanafudaEvent.Hinted -> haptics(HapticSignal.LIGHT_TICK)
            else -> Unit
        }
    }
    LaunchedEffect(state?.round?.phase == KoiPhase.ROUND_OVER, state?.roundNumber) {
        val s = state ?: return@LaunchedEffect
        if (s.round.over) {
            val mine = s.round.winner == game.viewerSeat() || s.round.winner == localSeat
            playSfx(if (s.round.winner < 0) SfxKind.LIGHT_TICK else if (mine) SfxKind.SUCCESS_CHIME else SfxKind.SOLID_THUNK)
            haptics(if (s.finished && mine) HapticSignal.CELEBRATION else if (s.round.winner < 0) HapticSignal.NORMAL_ACTION else HapticSignal.SUCCESS)
        }
    }

    val s = state
    GameChrome(
        helpTitle = stringResource(R.string.hf_help_title),
        helpText = stringResource(R.string.hf_help_text),
        matchInProgress = s != null && !s.finished,
        onLeave = game::leaveSession,
        onAbort = { if ((s?.roundScores?.size ?: 0) > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = (s?.roundScores?.size ?: 0) == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = { game.requestHint() },
        buttonFill = HfTableBottom,
        buttonContent = Color.White,
        leaveTitle = stringResource(R.string.hf_leave_title),
        leaveBody = stringResource(R.string.hf_leave_body)
    ) {
        if (s == null) {
            Box(Modifier.fillMaxSize().background(HfTableBottom), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.hf_waiting_host), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            return@GameChrome
        }
        val r = s.round
        val viewer = if (isNetworked) localSeat else game.viewerSeat()
        val myTurn = if (isNetworked) r.turn == localSeat && !opponentLeft else !s.players[r.turn].isBot
        val canAct = myTurn && !r.over
        val rKey = "${s.roundNumber}-${r.turn}"
        val needCover = isPassAndPlay && !r.over && revealedKey != rKey

        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(HfTableTop, HfTableBottom)))) {
        // Wide and short: the table gets the full height and the header + buttons sit in a side column
        // (stacked on top of it they squeeze the cards to thumbnails).
        val wide = maxWidth > maxHeight * 1.15f
        val header: @Composable () -> Unit = {
            HfHeader(s, viewer, hfEventText(event, eventSeq, names, s), hfPrompt(s, r, viewer, canAct, selectedHand, note))
        }
        val controls: @Composable () -> Unit = {
            HfControls(
                canUndo = game.canUndo && !isNetworked,
                showUndo = !isNetworked,
                onUndo = { selectedHand = null; game.undo() },
                onHint = { game.requestHint() },
                onMine = { capturedSeat = viewer },
                onTheirs = { capturedSeat = 1 - viewer },
                stacked = wide
            )
        }
        val tableBox: @Composable (Modifier) -> Unit = { mod ->
            BoxWithConstraints(mod, contentAlignment = Alignment.TopCenter) {
                val m = remember(maxWidth, maxHeight) { HanafudaLayout.metrics(maxWidth.value, maxHeight.value) }
                HfTable(
                    game = game, s = s, m = m, viewer = viewer, canAct = canAct, names = names,
                    selectedHand = selectedHand, onSelect = { selectedHand = it },
                    drag = drag, onDrag = { drag = it }, hint = hint,
                    event = event, eventSeq = eventSeq, roundKey = s.roundNumber,
                    reducedMotion = reducedMotion, speedScale = gameSpeed.delayScale,
                    onNote = { note = it }, onOpenCaptured = { capturedSeat = it }
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Under the pass-and-play cover the hand must not exist at all (a screen reader would read it out).
                if (needCover) Spacer(Modifier.weight(1f).fillMaxHeight()) else tableBox(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    if (!needCover) header()
                    Spacer(Modifier.height(8.dp))
                    if (!needCover) controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                if (needCover) Spacer(Modifier.weight(1f)) else {
                    header()
                    tableBox(Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp))
                }
                if (!needCover) controls()
            }
        }
        }

        if (canAct && (r.phase == KoiPhase.DECIDE_AFTER_HAND || r.phase == KoiPhase.DECIDE_AFTER_DRAW) && !needCover) {
            HfDecisionPanel(r, names, hint as? KoiHint.Decide, onKoi = { game.decide(true) }, onShobu = { game.decide(false) })
        }
        if (r.over && !needCover) {
            HfRoundOver(
                s = s, names = names, viewer = viewer, isNetworked = isNetworked, opponentLeft = opponentLeft,
                reducedMotion = reducedMotion,
                onNext = { selectedHand = null; game.nextRound() },
                onAgain = { selectedHand = null; game.playAgain() },
                onLeave = game::leaveSession
            )
        }
        if (needCover) {
            HfPassCover(s.players[r.turn].displayName) { revealedKey = rKey }
        }
        capturedSeat?.let { seat ->
            HfCapturedDialog(
                title = stringResource(if (seat == viewer) R.string.hf_cap_title_mine else R.string.hf_cap_title_theirs),
                cards = r.captured[seat], names = names, onClose = { capturedSeat = null }
            )
        }
    }
}

// ---- header and prompts -------------------------------------------------------------------------------

@Composable
private fun HfHeader(s: HanafudaState, viewer: Int, line: String, prompt: String) {
    val other = 1 - viewer
    Column(Modifier.fillMaxWidth().padding(end = GameChromeEndInset)) {
        Text(
            stringResource(R.string.hf_round, s.roundNumber, s.totalRounds),
            color = HfGold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
        )
        Text(
            stringResource(R.string.hf_score_line, LocalContext.current.seatLabel(s.players[viewer].displayName), s.totals[viewer], LocalContext.current.seatLabel(s.players[other].displayName), s.totals[other]),
            color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1
        )
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(line, color = Color(0xFFFFF3C4), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(prompt, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, maxLines = 2, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun hfPrompt(s: HanafudaState, r: KoiRound, viewer: Int, canAct: Boolean, selected: Int?, note: String?): String {
    if (note != null) return note
    if (r.over) return ""
    if (!canAct) return stringResource(R.string.hf_prompt_other, s.players[r.turn].displayName)
    return when (r.phase) {
        KoiPhase.PLAY_HAND -> {
            if (selected == null || selected !in r.hands[viewer]) stringResource(R.string.hf_prompt_play)
            else when (r.matchesOnField(selected).size) {
                0 -> stringResource(R.string.hf_prompt_selected_discard)
                2 -> stringResource(R.string.hf_prompt_selected_choose)
                else -> stringResource(R.string.hf_prompt_selected_match)
            }
        }
        KoiPhase.DRAW_READY -> stringResource(R.string.hf_prompt_draw)
        KoiPhase.CHOOSE_DRAW_MATCH -> stringResource(R.string.hf_prompt_choose)
        KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> stringResource(R.string.hf_prompt_decide)
        KoiPhase.ROUND_OVER -> ""
    }
}

@Composable
private fun hfEventText(e: HanafudaEvent, seq: Int, n: HfNames, s: HanafudaState): String {
    @Suppress("UNUSED_EXPRESSION") seq
    fun who(seat: Int) = s.players.getOrNull(seat)?.displayName ?: ""
    return when (e) {
        HanafudaEvent.None -> ""
        is HanafudaEvent.RoundStarted ->
            if (e.special != KoiEnd.NONE) stringResource(R.string.hf_ev_round_special, s.roundNumber)
            else stringResource(R.string.hf_ev_round, s.roundNumber, who(e.parent))
        is HanafudaEvent.Played ->
            if (e.taken.isEmpty()) stringResource(R.string.hf_ev_played_field, who(e.seat), n.card(e.card))
            else stringResource(R.string.hf_ev_played_took, who(e.seat), n.card(e.card), n.cards(e.taken.filter { it != e.card }))
        is HanafudaEvent.Drew ->
            if (e.taken.isEmpty()) stringResource(R.string.hf_ev_drew_field, who(e.seat), n.card(e.card))
            else stringResource(R.string.hf_ev_drew_took, who(e.seat), n.card(e.card), n.cards(e.taken.filter { it != e.card }))
        is HanafudaEvent.NeedsChoice -> stringResource(R.string.hf_ev_needs_choice, who(e.seat), n.card(e.card))
        is HanafudaEvent.Decided -> stringResource(if (e.koiKoi) R.string.hf_ev_koi else R.string.hf_ev_shobu, who(e.seat))
        is HanafudaEvent.Rejected -> stringResource(
            when (e.reason) {
                HanafudaReject.NOT_YOUR_TURN -> R.string.hf_ev_rej_turn
                HanafudaReject.NOT_IN_HAND -> R.string.hf_ev_rej_not_in_hand
                HanafudaReject.PICK_A_FIELD_CARD -> R.string.hf_ev_rej_pick_field
                HanafudaReject.WRONG_FIELD_CARD -> R.string.hf_ev_rej_wrong_field
                HanafudaReject.WRONG_PHASE -> R.string.hf_ev_rej_phase
            }
        )
        HanafudaEvent.Undone -> stringResource(R.string.hf_ev_undone)
        is HanafudaEvent.Hinted -> when (val h = e.hint) {
            is KoiHint.Play -> {
                val matches = s.round.matchesOnField(h.play.handCard)
                if (h.play.fieldCard >= 0) stringResource(R.string.hf_ev_hint_play_on, n.card(h.play.handCard), n.card(h.play.fieldCard))
                else if (matches.isNotEmpty()) stringResource(R.string.hf_ev_hint_play_on, n.card(h.play.handCard), n.cards(matches))
                else stringResource(R.string.hf_ev_hint_discard, n.card(h.play.handCard))
            }
            KoiHint.Draw -> stringResource(R.string.hf_ev_hint_draw)
            is KoiHint.Match -> stringResource(R.string.hf_ev_hint_take, n.card(h.fieldCard))
            is KoiHint.Decide -> stringResource(if (h.koiKoi) R.string.hf_ev_hint_koi else R.string.hf_ev_hint_shobu)
        }
        HanafudaEvent.Resumed -> stringResource(R.string.hf_ev_resumed)
        HanafudaEvent.OpponentLeft -> stringResource(R.string.hf_ev_opp_left)
        HanafudaEvent.OpponentBack -> stringResource(R.string.hf_ev_opp_back)
    }
}

// ---- the table ----------------------------------------------------------------------------------------

@Composable
private fun HfTable(
    game: HanafudaGame,
    s: HanafudaState,
    m: HfMetrics,
    viewer: Int,
    canAct: Boolean,
    names: HfNames,
    selectedHand: Int?,
    onSelect: (Int?) -> Unit,
    drag: HfDrag?,
    onDrag: (HfDrag?) -> Unit,
    hint: KoiHint?,
    event: HanafudaEvent,
    eventSeq: Int,
    roundKey: Int,
    reducedMotion: Boolean,
    speedScale: Float,
    onNote: (String?) -> Unit,
    onOpenCaptured: (Int) -> Unit
) {
    val r = s.round
    val placements = remember(r, viewer, m) { HanafudaLayout.placements(r, viewer, m) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val glide = if (reducedMotion) 0 else (300 * speedScale).roundToInt()

    // Where freshly appearing cards come from: the deck for a deal or a turn-up, the opponent's hand for their play.
    val deckOrigin = Offset(m.deckX, m.deckY)
    val oppOrigin = Offset(m.oppHand.x + m.oppHand.w / 2f, m.oppHand.y)
    val spawn: Offset? = if (reducedMotion) null else when {
        event is HanafudaEvent.RoundStarted || event is HanafudaEvent.Drew || event is HanafudaEvent.NeedsChoice -> deckOrigin
        event is HanafudaEvent.Played && event.seat != viewer -> oppOrigin
        else -> null
    }

    val matches: Set<Int> = when {
        !canAct -> emptySet()
        r.phase == KoiPhase.CHOOSE_DRAW_MATCH -> KoiRules.drawChoices(r).toSet()
        r.phase == KoiPhase.PLAY_HAND && selectedHand != null -> r.matchesOnField(selectedHand).toSet()
        else -> emptySet()
    }
    val hintHand = (hint as? KoiHint.Play)?.play?.handCard
    val hintField = when (hint) {
        is KoiHint.Play -> if (hint.play.fieldCard >= 0) setOf(hint.play.fieldCard) else r.matchesOnField(hint.play.handCard).toSet()
        is KoiHint.Match -> setOf(hint.fieldCard)
        else -> emptySet()
    }
    val deckTappable = canAct && r.phase == KoiPhase.DRAW_READY

    fun playFrom(card: Int, fieldCard: Int) {
        onSelect(null)
        game.playHand(card, fieldCard)
    }

    fun tapHand(card: Int) {
        if (!canAct || r.phase != KoiPhase.PLAY_HAND) {
            game.playHand(card)
            return
        }
        if (selectedHand != card) {
            onSelect(card)
            onNote(null)
            return
        }
        when (r.matchesOnField(card).size) {
            2 -> onNote(null)
            else -> playFrom(card, -1)
        }
    }

    fun tapField(card: Int) {
        if (!canAct) return
        when (r.phase) {
            KoiPhase.CHOOSE_DRAW_MATCH -> game.chooseMatch(card)
            KoiPhase.PLAY_HAND -> {
                val sel = selectedHand
                if (sel == null) return
                if (HanafudaCards.monthOf(sel) == HanafudaCards.monthOf(card)) playFrom(sel, card)
                else {
                    game.playHand(sel, card)
                }
            }
            else -> Unit
        }
    }

    Box(Modifier.width(m.width.dp).height(m.height.dp)) {
        // Zone plates: soft felt rectangles so the table reads as zones even when they are empty.
        ZonePlate(m.oppHand, alpha = 0.06f)
        ZonePlate(m.oppCaptured, alpha = 0.10f)
        ZonePlate(m.field, alpha = 0.07f)
        ZonePlate(m.myCaptured, alpha = 0.10f)
        ZonePlate(m.myHand, alpha = 0.06f)

        for ((id, p) in placements) {
            key(roundKey, id) {
                val dragging = drag != null && drag.card == id
                HfCard(
                    id = id, p = p, density = density, glide = glide, spawn = spawn,
                    spawnDelay = if (event is HanafudaEvent.RoundStarted && !reducedMotion) (p.z.coerceAtMost(40) * 22L) else 0L,
                    dragOffset = if (dragging) drag!!.offset else null,
                    names = names, r = r,
                    selected = id == selectedHand,
                    glow = id in matches,
                    ringHint = id == hintHand || id in hintField,
                    deckGlow = p.zone == HfZone.DECK && deckTappable && id == r.deck.lastOrNull(),
                    canAct = canAct,
                    reducedMotion = reducedMotion,
                    onTap = {
                        when (p.zone) {
                            HfZone.MY_HAND -> tapHand(id)
                            HfZone.FIELD -> tapField(id)
                            HfZone.DECK -> game.drawFromDeck()
                            else -> Unit
                        }
                    },
                    canDrag = p.zone == HfZone.MY_HAND && canAct && r.phase == KoiPhase.PLAY_HAND,
                    onDragMove = { off -> onDrag(HfDrag(id, off)) },
                    onDragEnd = { off ->
                        onDrag(null)
                        val cx = p.x + p.w / 2f + off.x
                        val cy = p.y + p.h / 2f + off.y
                        val idx = HanafudaLayout.fieldIndexAt(cx, cy, r, m)
                        if (idx >= 0 && HanafudaCards.monthOf(r.field[idx]) == HanafudaCards.monthOf(id)) playFrom(id, r.field[idx])
                        else if (m.field.contains(cx, cy)) {
                            if (r.matchesOnField(id).size == 2) {
                                onSelect(id)
                                onNote(null)
                            } else playFrom(id, -1)
                        }
                    }
                )
            }
        }

        // Zone summaries for TalkBack (and a tap target to open the captured lists).
        val oppName = s.players[1 - viewer].displayName
        val mineName = s.players[viewer].displayName
        val res = LocalContext.current.resources
        ZoneNode(m.oppHand, res.getQuantityString(R.plurals.hf_cd_opp_hand, r.hands[1 - viewer].size, oppName, r.hands[1 - viewer].size), null)
        ZoneNode(m.oppCaptured, capturedSummary(r.captured[1 - viewer], oppName), { onOpenCaptured(1 - viewer) })
        ZoneNode(m.myCaptured, capturedSummary(r.captured[viewer], mineName), { onOpenCaptured(viewer) })
        val deckDesc = res.getQuantityString(R.plurals.hf_cd_deck, r.deck.size, r.deck.size)
        Box(
            Modifier
                .offset(m.deckX.dp, m.deckY.dp)
                .size(m.fieldCellW.dp, m.fieldCellH.dp)
                .zIndex(5f)
                .clearAndSetSemantics {
                    contentDescription = deckDesc
                    role = Role.Button
                }
                .clickable(enabled = deckTappable, onClickLabel = stringResource(R.string.hf_act_draw)) { game.drawFromDeck() }
        )
    }
}

@Composable
private fun capturedSummary(cards: List<Int>, who: String): String {
    val k = HanaKind.entries.map { kind -> cards.count { HanafudaCards.card(it).kind == kind } }
    return stringResource(R.string.hf_cd_captured, who, cards.size, k[0], k[1], k[2], k[3])
}

@Composable
private fun ZonePlate(r: com.gamesuite.games.hanafuda.HfRect, alpha: Float) {
    Box(
        Modifier
            .offset(r.x.dp, r.y.dp)
            .size(r.w.dp, r.h.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = alpha))
    )
}

@Composable
private fun ZoneNode(r: com.gamesuite.games.hanafuda.HfRect, description: String, onClick: (() -> Unit)?) {
    var mod: Modifier = Modifier
        .offset(r.x.dp, r.y.dp)
        .size(r.w.dp, r.h.dp)
        .zIndex(1f)
    if (onClick != null) mod = mod.clickable(onClickLabel = stringResource(R.string.hf_act_open_captured)) { onClick() }
    Box(mod.semantics { contentDescription = description; if (onClick != null) role = Role.Button })
}

// ---- one card -----------------------------------------------------------------------------------------

@Composable
private fun HfCard(
    id: Int,
    p: HfPlacement,
    density: Float,
    glide: Int,
    spawn: Offset?,
    spawnDelay: Long,
    dragOffset: Offset?,
    names: HfNames,
    r: KoiRound,
    selected: Boolean,
    glow: Boolean,
    ringHint: Boolean,
    deckGlow: Boolean,
    canAct: Boolean,
    reducedMotion: Boolean,
    onTap: () -> Unit,
    canDrag: Boolean,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit
) {
    val target = Offset(p.x, p.y + if (selected) -6f else 0f)
    val anim = remember { Animatable(spawn ?: target, Offset.VectorConverter) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(target.x, target.y, dragOffset == null) {
        if (dragOffset != null) return@LaunchedEffect
        if (glide == 0) {
            anim.snapTo(target)
        } else {
            if (!settled && spawnDelay > 0) delay(spawnDelay)
            anim.animateTo(target, tween(glide, easing = FastOutSlowInEasing))
        }
        settled = true
    }
    LaunchedEffect(dragOffset) {
        if (dragOffset != null) anim.snapTo(Offset(p.x + dragOffset.x, p.y + dragOffset.y))
    }
    val w by animateFloatAsState(p.w, if (glide == 0) snap() else tween(glide), label = "hfW")
    val h by animateFloatAsState(p.h, if (glide == 0) snap() else tween(glide), label = "hfH")
    val flip by animateFloatAsState(
        if (p.faceUp) 1f else 0f,
        if (glide == 0) snap() else tween((glide * 0.8f).roundToInt(), easing = LinearEasing),
        label = "hfFlip"
    )
    val shown = if (dragOffset != null) Offset(p.x + dragOffset.x, p.y + dragOffset.y) else anim.value
    val moving = dragOffset == null && (anim.value - target).getDistance() > 0.5f
    val zone = when (p.zone) {
        HfZone.DECK -> 0
        HfZone.OPP_CAPTURED, HfZone.MY_CAPTURED -> 100
        HfZone.OPP_HAND -> 200
        HfZone.FIELD -> 300
        HfZone.MY_HAND -> 400
        HfZone.DRAWN -> 500
    }
    val z = (if (dragOffset != null) 3000 else if (moving) 2000 else zone) + p.z

    var mod: Modifier = Modifier
        .zIndex(z.toFloat())
        .offset { IntOffset((shown.x * density).roundToInt(), (shown.y * density).roundToInt()) }
        .size(w.dp, h.dp)
        .graphicsLayer { scaleX = max(0.04f, kotlin.math.abs(1f - 2f * flip) * 0.98f + 0.02f) }

    val real = id >= 0
    if (real && p.zone == HfZone.MY_HAND) {
        val desc = buildString {
            append(stringResource(R.string.hf_cd_in_hand, names.card(id)))
            if (selected) append(", ").append(stringResource(R.string.hf_cd_selected))
        }
        mod = mod
            .clickable(onClickLabel = stringResource(if (selected) R.string.hf_act_play else R.string.hf_act_select)) { onTap() }
            .semantics { contentDescription = desc; role = Role.Button }
    } else if (real && p.zone == HfZone.FIELD) {
        val desc = buildString {
            append(stringResource(R.string.hf_cd_on_field, names.card(id)))
            if (glow) append(", ").append(stringResource(R.string.hf_cd_can_take))
        }
        mod = mod
            .clickable(enabled = canAct, onClickLabel = stringResource(R.string.hf_act_take)) { onTap() }
            .semantics { contentDescription = desc; role = Role.Button }
    } else if (real && p.zone == HfZone.DRAWN) {
        val desc = stringResource(R.string.hf_cd_drawn, names.card(id))
        mod = mod.semantics { contentDescription = desc }
    } else {
        mod = mod.clearAndSetSemantics { }
    }
    if (canDrag) {
        var total = Offset.Zero
        mod = mod.pointerInput(id, p.x, p.y) {
            detectDragGestures(
                onDragStart = { total = Offset.Zero; onDragMove(Offset.Zero) },
                onDrag = { change, amount ->
                    change.consume()
                    total += Offset(amount.x / density, amount.y / density)
                    onDragMove(total)
                },
                onDragEnd = { onDragEnd(total) },
                onDragCancel = { onDragEnd(Offset(-9999f, -9999f)) }
            )
        }
    }

    Box(mod) {
        if (flip > 0.5f && real) {
            val c = HanafudaCards.card(id)
            HanafudaCardFace(id, w.dp, h.dp, names.kind(c))
        } else {
            HanafudaCardBack(w.dp, h.dp)
        }
        if (selected || glow || ringHint || deckGlow) {
            val pulse = if (reducedMotion || !(glow || deckGlow)) 1f else {
                val t = rememberInfiniteTransition(label = "hfPulse")
                val v by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "v")
                v
            }
            Box(
                Modifier
                    .size(w.dp, h.dp)
                    .border(
                        if (selected) 3.dp else 2.5.dp,
                        when {
                            selected -> HfGlow
                            ringHint -> HfHintRing
                            else -> HfGlow.copy(alpha = pulse)
                        },
                        RoundedCornerShape((w * 0.07f).dp)
                    )
            )
        }
    }
}

// ---- controls and panels ------------------------------------------------------------------------------

@Composable
private fun HfControls(
    canUndo: Boolean,
    showUndo: Boolean,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onMine: () -> Unit,
    onTheirs: () -> Unit,
    stacked: Boolean = false
) {
    val colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.4f))
    val pad = PaddingValues(horizontal = 4.dp)
    // Four labels do not fit one line beside each other with a large system font (or in a narrow side column).
    val twoRows = stacked || LocalDensity.current.fontScale > 1.3f
    val undo: @Composable RowScope.() -> Unit = {
        if (showUndo) OutlinedButton(onClick = onUndo, enabled = canUndo, colors = colors, contentPadding = pad, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.hf_undo), maxLines = 1)
        }
    }
    val hint: @Composable RowScope.() -> Unit = {
        OutlinedButton(onClick = onHint, colors = colors, contentPadding = pad, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.hf_hint), maxLines = 1)
        }
    }
    val mine: @Composable RowScope.() -> Unit = {
        OutlinedButton(onClick = onMine, colors = colors, contentPadding = pad, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.hf_captured_mine), maxLines = 1)
        }
    }
    val theirs: @Composable RowScope.() -> Unit = {
        OutlinedButton(onClick = onTheirs, colors = colors, contentPadding = pad, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.hf_captured_theirs), maxLines = 1)
        }
    }
    val rowMod = Modifier.fillMaxWidth().padding(top = 4.dp)
    if (stacked && LocalDensity.current.fontScale > 1.3f) {
        Column {
            for (b in listOf(undo, hint, mine, theirs)) {
                Row(rowMod, verticalAlignment = Alignment.CenterVertically) { b() }
            }
        }
    } else if (twoRows) {
        Column {
            Row(rowMod, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { undo(); hint() }
            Row(rowMod, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { mine(); theirs() }
        }
    } else {
        Row(rowMod, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { undo(); hint(); mine(); theirs() }
    }
}

@Composable
private fun HfDecisionPanel(r: KoiRound, names: HfNames, hint: KoiHint.Decide?, onKoi: () -> Unit, onShobu: () -> Unit) {
    val yaku = r.yakuOf(r.turn)
    val total = r.yakuTotal(r.turn)
    val score = KoiRules.scoreFor(r, r.turn)
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFFFF6DC))
                .border(2.dp, HfGold, RoundedCornerShape(16.dp))
                .padding(14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.hf_dec_title), color = HfInk, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            for (y in yaku) {
                Text(stringResource(R.string.hf_yaku_line, names.yakuName(y.yaku), y.points), color = HfInk, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.hf_dec_worth, score), color = HfInk, fontWeight = FontWeight.Bold)
            if (score != total) Text(stringResource(R.string.hf_dec_multiplied, total), color = HfInk.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onKoi,
                    modifier = Modifier.heightIn(min = 52.dp).then(if (hint?.koiKoi == true) Modifier.border(2.dp, HfHintRing, RoundedCornerShape(24.dp)) else Modifier)
                ) { Text(stringResource(R.string.hf_koi), fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onShobu,
                    modifier = Modifier.heightIn(min = 52.dp).then(if (hint?.koiKoi == false) Modifier.border(2.dp, HfHintRing, RoundedCornerShape(24.dp)) else Modifier)
                ) { Text(stringResource(R.string.hf_shobu, score), fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.hf_dec_help), color = HfInk.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HfRoundOver(
    s: HanafudaState,
    names: HfNames,
    viewer: Int,
    isNetworked: Boolean,
    opponentLeft: Boolean,
    reducedMotion: Boolean,
    onNext: () -> Unit,
    onAgain: () -> Unit,
    onLeave: () -> Unit
) {
    val r = s.round
    val winnerName = if (r.winner >= 0) s.players[r.winner].displayName else ""
    val mineWon = r.winner == viewer
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) { },
        contentAlignment = Alignment.Center
    ) {
        if (s.finished && mineWon && !reducedMotion) HfPetals()
        Column(
            Modifier
                .padding(18.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFFFF6DC))
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(if (s.finished) R.string.hf_final_title else R.string.hf_sum_title, s.roundNumber),
                color = HfInk, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(6.dp))
            if (r.winner < 0) {
                Text(
                    stringResource(if (r.end == KoiEnd.DEALT_BOTH) R.string.hf_sum_dealt_both else R.string.hf_sum_draw),
                    color = HfInk, textAlign = TextAlign.Center
                )
            } else {
                Text(stringResource(R.string.hf_sum_winner, winnerName, r.points), color = HfInk, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                val lines = r.yakuOf(r.winner)
                if (r.end == KoiEnd.DEALT_FOUR_OF_A_MONTH || r.end == KoiEnd.DEALT_FOUR_PAIRS) {
                    Text(
                        stringResource(if (r.end == KoiEnd.DEALT_FOUR_OF_A_MONTH) R.string.hf_sum_dealt_four else R.string.hf_sum_dealt_pairs),
                        color = HfInk, textAlign = TextAlign.Center
                    )
                } else {
                    for (y in lines) Text(stringResource(R.string.hf_yaku_line, names.yakuName(y.yaku), y.points), color = HfInk)
                    val base = r.yakuTotal(r.winner)
                    if (base >= KoiRules.DOUBLE_AT) Text(stringResource(R.string.hf_sum_double7), color = HfInk.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    if (r.koiCalls[1 - r.winner] > 0) Text(stringResource(R.string.hf_sum_koi_double), color = HfInk.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    if (r.end == KoiEnd.EXHAUSTED_KOI) Text(stringResource(R.string.hf_sum_exhausted), color = HfInk.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.hf_score_line, LocalContext.current.seatLabel(s.players[0].displayName), s.totals[0], LocalContext.current.seatLabel(s.players[1].displayName), s.totals[1]),
                color = HfInk, fontWeight = FontWeight.Bold
            )
            if (s.finished) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (s.winnerSeat >= 0) stringResource(R.string.hf_final_winner, s.players[s.winnerSeat].displayName) else stringResource(R.string.hf_final_draw),
                    color = HfInk, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val canContinue = !opponentLeft
                if (s.finished) {
                    Button(onClick = onAgain, enabled = canContinue, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hf_play_again)) }
                } else {
                    Button(onClick = onNext, enabled = canContinue, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hf_next_round)) }
                }
                OutlinedButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hf_leave)) }
            }
            if (isNetworked && opponentLeft) Text(stringResource(R.string.hf_ev_opp_left), color = HfInk, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A drift of falling petals behind the final panel. */
@Composable
private fun HfPetals() {
    val t = rememberInfiniteTransition(label = "hfPetals")
    val progress by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(Modifier.fillMaxSize()) {
        for (i in 0 until 36) {
            val seed = (i * 7919) % 1000 / 1000f
            val x = ((i * 389) % 1000) / 1000f * size.width + kotlin.math.sin((progress + seed) * 6.28f) * 18f
            val fall = (progress + seed) % 1f
            val y = fall * (size.height + 30f) - 15f
            rotate(fall * 300f, Offset(x, y)) {
                drawOval(if (i % 3 == 0) Color(0xFFF7B5C6) else Color(0xFFFFE3E8), Offset(x, y), Size(9f + seed * 6f, 14f + seed * 6f))
            }
        }
    }
}

@Composable
private fun HfPassCover(nextName: String, onReady: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF1B0A10)).pointerInput(Unit) { },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(stringResource(R.string.hf_pass_title, nextName), color = HfGold, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.hf_pass_body), color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onReady, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.hf_ready)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HfCapturedDialog(title: String, cards: List<Int>, names: HfNames, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (cards.isEmpty()) Text(stringResource(R.string.hf_cap_empty))
                for (kind in HanaKind.entries) {
                    val group = cards.filter { HanafudaCards.card(it).kind == kind }.sorted()
                    if (group.isEmpty()) continue
                    Text(names.kinds[kind.ordinal], fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (id in group) {
                            val c = HanafudaCards.card(id)
                            Box(Modifier.semantics { contentDescription = names.card(id) }) {
                                HanafudaCardFace(id, 46.dp, 73.dp, names.kind(c))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.hf_close)) } }
    )
}
