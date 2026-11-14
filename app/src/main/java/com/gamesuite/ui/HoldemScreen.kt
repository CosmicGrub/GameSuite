package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.CpuLevel
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.holdem.HIDDEN_CARD
import com.gamesuite.games.holdem.HandCategory
import com.gamesuite.games.holdem.HandEvaluator
import com.gamesuite.games.holdem.HintAdvice
import com.gamesuite.games.holdem.HoldemAction
import com.gamesuite.games.holdem.HoldemBlinds
import com.gamesuite.games.holdem.HoldemCardSlot
import com.gamesuite.games.holdem.HoldemCardView
import com.gamesuite.games.holdem.HoldemCards
import com.gamesuite.games.holdem.HoldemChipStack
import com.gamesuite.games.holdem.HoldemConfig
import com.gamesuite.games.holdem.HoldemDealerButton
import com.gamesuite.games.holdem.HoldemEvent
import com.gamesuite.games.holdem.HoldemFelt
import com.gamesuite.games.holdem.HoldemGame
import com.gamesuite.games.holdem.HoldemHint
import com.gamesuite.games.holdem.HoldemOptions
import com.gamesuite.games.holdem.HoldemReject
import com.gamesuite.games.holdem.HoldemSeat
import com.gamesuite.games.holdem.HoldemState
import com.gamesuite.games.holdem.chipBreakdown
import com.gamesuite.games.holdem.drawChipStack
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Texas Hold'em (play chips only). A felt table with the seats around it, the board and pot in the middle,
 * your two cards and the betting controls underneath. Everything is original vector art (see
 * games/holdem/HoldemCardArt.kt). Deal, flip and chip-push animations, sound and haptic cues, a win
 * celebration; Reduced Motion and the game-speed setting are honoured; the colour-blind-safe setting gives
 * every suit its own colour as well as its shape.
 *
 * Accessibility: every card, chip stack and pot has a TalkBack label, each seat reads as one stop, the
 * status line is a polite live region (and says "name: action" in a neutral form so it reads right for "You"),
 * all controls are at least 48dp, and the bet slider has -/+ buttons and presets so it never needs a drag.
 */

private val RoomTop = Color(0xFF0B1317)
private val RoomBottom = Color(0xFF14211F)
private val Ink = Color(0xFFF3EBD9)
private val InkDim = Color(0xFFB9B2A2)
private val Gold = Color(0xFFE8C766)
private val Panel = Color(0xFF17262A)
private val PlateBg = Color(0xE6101C20)
private val Danger = Color(0xFFFF8A80)

/** Looks up card, hand and rank names in the current locale. */
private class HoldemWords(val ctx: Context) {
    private val ranks: Array<String> = ctx.resources.getStringArray(R.array.holdem_rank_names)
    private val plurals: Array<String> = ctx.resources.getStringArray(R.array.holdem_rank_plurals)
    private val suits: Array<String> = ctx.resources.getStringArray(R.array.holdem_suit_names)

    fun rank(r: Int) = ranks[(r - 2).coerceIn(0, 12)]
    fun rankPlural(r: Int) = plurals[(r - 2).coerceIn(0, 12)]

    fun card(c: Int): String =
        if (HoldemCards.isValid(c)) ctx.getString(R.string.holdem_card_cd, rank(HoldemCards.rank(c)), suits[HoldemCards.suit(c)])
        else ctx.getString(R.string.holdem_card_hidden_cd)

    fun hand(score: Int): String {
        fun r(i: Int) = HandEvaluator.rankAt(score, i)
        return when (HandEvaluator.category(score)) {
            HandCategory.HIGH_CARD -> ctx.getString(R.string.holdem_hand_high, rank(r(0)))
            HandCategory.PAIR -> ctx.getString(R.string.holdem_hand_pair, rankPlural(r(0)))
            HandCategory.TWO_PAIR -> ctx.getString(R.string.holdem_hand_two_pair, rankPlural(r(0)), rankPlural(r(1)))
            HandCategory.TRIPS -> ctx.getString(R.string.holdem_hand_trips, rankPlural(r(0)))
            HandCategory.STRAIGHT -> ctx.getString(R.string.holdem_hand_straight, rank(r(0)))
            HandCategory.FLUSH -> ctx.getString(R.string.holdem_hand_flush, rank(r(0)))
            HandCategory.FULL_HOUSE -> ctx.getString(R.string.holdem_hand_full_house, rankPlural(r(0)), rankPlural(r(1)))
            HandCategory.QUADS -> ctx.getString(R.string.holdem_hand_quads, rankPlural(r(0)))
            HandCategory.STRAIGHT_FLUSH ->
                if (HandEvaluator.isRoyal(score)) ctx.getString(R.string.holdem_hand_royal)
                else ctx.getString(R.string.holdem_hand_straight_flush, rank(r(0)))
        }
    }

    fun cardList(cards: List<Int>): String = cards.joinToString(", ") { card(it) }
}

/** A run of chips travelling between two places on the table. */
private class ChipFlight(val from: Endpoint, val to: Endpoint, val amount: Int, val start: Float, val end: Float)

private class Endpoint(val kind: Int, val seat: Int) {
    companion object {
        const val BET = 0
        const val POT = 1
        const val SEAT = 2
    }
}

private fun Modifier.centerAt(x: Float, y: Float): Modifier = this
    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
    .graphicsLayer {
        translationX = -size.width / 2f
        translationY = -size.height / 2f
    }

/** Seat centres around the felt oval, clockwise on screen, with [anchor] at the bottom. */
private fun ringPositions(n: Int, anchor: Int, w: Float, h: Float, plateW: Float, plateH: Float, clearBand: Float = 0f): List<Offset> {
    val rx = (w / 2f - plateW / 2f - 2f).coerceAtLeast(w * 0.2f)
    val ry = (h / 2f - plateH / 2f - 2f).coerceAtLeast(h * 0.2f)
    return List(n) { seat ->
        val rel = ((seat - anchor) % n + n) % n
        val phi = PI / 2.0 + rel * 2.0 * PI / n
        var dy = (ry * sin(phi)).toFloat()
        // Seats on the left/right rim must not sit in the band the five board cards occupy, or they
        // hide the first and last community card: slide them below (or above) that band.
        val clear = minOf(clearBand + plateH / 2f, h / 2f - plateH / 2f - 2f)
        if (clearBand > 0f && abs(cos(phi)) > 0.55 && abs(dy) < clear) {
            dy = if (dy < 0f) -clear else clear
        }
        Offset((w / 2f + rx * cos(phi)).toFloat(), (h / 2f + dy).toFloat())
    }
}

/** Seat-plate type is part of the table drawing: it keeps its size whatever the system font scale, capped at 1.2x. */
@Composable
private fun plateSp(dpValue: Float): androidx.compose.ui.unit.TextUnit {
    val d = LocalDensity.current
    return with(d) { (dpValue * minOf(d.fontScale, 1.2f)).dp.toSp() }
}

private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HoldemScreen(
    sessionManager: GameSessionManager,
    game: HoldemGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val sessionContext by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val configuration = LocalConfiguration.current
    val words = remember(androidContext, configuration) { HoldemWords(androidContext) }
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CHESS, enabled = musicEnabled)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()
    val density = LocalDensity.current

    LaunchedEffect(sessionContext) {
        val ctx = sessionContext ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.config = HoldemConfig(
            startingChips = extras["startingChips"]?.toIntOrNull()
                ?.takeIf { it in HoldemConfig.MIN_START..HoldemConfig.MAX_START } ?: 1000,
            blindSpeed = extras["blindSpeed"] ?: "normal"
        )
        game.cpuStyle = extras["cpuStyle"] ?: "mixed"
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    val activeCtx = sessionContext
    val mode = activeCtx?.activeMode
    val isNetworked = mode == PlayMode.LOCAL_AD_HOC || mode == PlayMode.ONLINE
    val passMode = mode == PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val spectator = isNetworked && localSeat < 0
    val driving = !isNetworked || localSeat == 0
    val hostLeft by game.hostLeft
    val hint by game.hint
    val s = game.state.value

    val helpTitle = stringResource(R.string.holdem_help_title)
    val helpText = stringResource(R.string.holdem_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = RoomTop, buttonContent = Ink
            ) {
                Box(Modifier.fillMaxSize().background(RoomTop), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.holdem_waiting_host), color = Ink, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val n = s.seats.size
    val anchor = if (isNetworked && localSeat >= 0) localSeat else 0

    // ---- who may see which cards -----------------------------------------------------------------
    var ack by remember { mutableStateOf(-1 to -1) }
    val actorIsHuman = !s.handOver && !s.runout && s.toAct in 0 until n && !s.seats[s.toAct].isBot
    val needsCover = passMode && actorIsHuman && ack != (s.handNumber to s.toAct)
    val traySeat: Int = when {
        spectator -> -1
        isNetworked -> localSeat
        passMode -> if (actorIsHuman && !needsCover) s.toAct else -1
        else -> 0
    }
    val myTurn = traySeat >= 0 && !s.handOver && !s.runout && s.toAct == traySeat && !s.matchOver
    val options: HoldemOptions? = if (myTurn) game.options(traySeat) else null

    // ---- animation state -------------------------------------------------------------------------
    val dealAnim = remember { Animatable(1f) }
    val boardFlip = remember { List(5) { Animatable(0f) } }
    val seatFlip = remember { List(6) { Animatable(0f) } }
    val flightProgress = remember { Animatable(0f) }
    var flights by remember { mutableStateOf<List<ChipFlight>>(emptyList()) }
    var busy by remember { mutableIntStateOf(0) }
    var thinkingSeat by remember { mutableIntStateOf(-1) }
    var prevState by remember { mutableStateOf<HoldemState?>(null) }
    var prevBoardSize by remember { mutableIntStateOf(-1) }
    var whyKey by remember { mutableStateOf<HoldemReject?>(null) }
    var sentSerial by remember { mutableIntStateOf(-1) }
    var raiseOpen by remember { mutableStateOf(false) }
    var raiseTo by remember { mutableIntStateOf(0) }
    val ms = { base: Long -> gameSpeed.scale(base).toInt().coerceAtLeast(60) }

    LaunchedEffect(s.handNumber) {
        for (a in boardFlip) a.snapTo(0f)
        for (a in seatFlip) a.snapTo(0f)
        prevBoardSize = 0
        if (s.handNumber > 0 && !reduced) {
            dealAnim.snapTo(0f)
            sounds.playShuffle()
            dealAnim.animateTo(1f, tween(ms(950L), easing = FastOutSlowInEasing))
        } else {
            dealAnim.snapTo(1f)
        }
    }

    LaunchedEffect(s.board.size, s.handNumber) {
        val size = s.board.size
        val before = prevBoardSize
        prevBoardSize = size
        if (before < 0) {
            for (i in 0 until 5) boardFlip[i].snapTo(if (i < size) 1f else 0f)
            return@LaunchedEffect
        }
        if (size < before) {
            for (i in size until 5) boardFlip[i].snapTo(0f)
            return@LaunchedEffect
        }
        for (i in before until size) {
            val slot = i
            val order = i - before
            if (reduced) {
                boardFlip[slot].snapTo(1f)
            } else {
                scope.launch {
                    busy++
                    try {
                        delay(ms(140L) * order.toLong())
                        sounds.playPlace(rate = 0.95f + 0.04f * order)
                        boardFlip[slot].snapTo(0f)
                        boardFlip[slot].animateTo(1f, tween(ms(480L), easing = FastOutSlowInEasing))
                    } finally {
                        busy--
                    }
                }
            }
        }
    }

    val revealKey = s.seats.map { it.revealed }
    LaunchedEffect(revealKey, s.handNumber) {
        s.seats.forEachIndexed { i, p ->
            if (i >= seatFlip.size) return@forEachIndexed
            if (!p.revealed) {
                seatFlip[i].snapTo(0f)
            } else if (seatFlip[i].value < 1f) {
                if (reduced) seatFlip[i].snapTo(1f) else {
                    scope.launch {
                        busy++
                        try {
                            sounds.playDraw()
                            seatFlip[i].animateTo(1f, tween(ms(420L), easing = FastOutSlowInEasing))
                        } finally {
                            busy--
                        }
                    }
                }
            }
        }
    }

    // Sounds, haptics, the chip flights and the win celebration, once per committed state.
    LaunchedEffect(s.eventSerial) {
        val prev = prevState
        prevState = s
        if (prev == null || prev.eventSerial == s.eventSerial) return@LaunchedEffect
        val mine = traySeat
        when (s.event) {
            HoldemEvent.CHECK -> playSfx(SfxKind.LIGHT_TICK)
            HoldemEvent.FOLD -> playSfx(SfxKind.WHOOSH)
            HoldemEvent.CALL, HoldemEvent.BET, HoldemEvent.RAISE -> {
                playSfx(SfxKind.SOLID_THUNK)
                if (s.eventSeat == mine) haptics(HapticSignal.NORMAL_ACTION)
            }
            HoldemEvent.ALL_IN -> {
                playSfx(SfxKind.SOLID_THUNK)
                haptics(HapticSignal.STRONG_ACTION)
            }
        }
        // chips sliding into the pot, and from the pot to the winners
        val handEnded = s.handOver && !prev.handOver
        if (!reduced) {
            val out = ArrayList<ChipFlight>()
            prev.seats.forEachIndexed { i, ps ->
                val extra = if (i == s.eventSeat && (s.event == HoldemEvent.CALL || s.event == HoldemEvent.ALL_IN)) s.eventAmount else 0
                val moved = if (s.seats[i].bet == 0) ps.bet + extra else 0
                if (moved > 0) out += ChipFlight(Endpoint(Endpoint.BET, i), Endpoint(Endpoint.POT, -1), moved, 0f, if (handEnded) 0.4f else 1f)
            }
            if (handEnded) {
                s.seats.forEachIndexed { i, p ->
                    if (p.won > 0) out += ChipFlight(Endpoint(Endpoint.POT, -1), Endpoint(Endpoint.SEAT, i), p.won, 0.45f, 1f)
                }
            }
            if (out.isNotEmpty()) {
                scope.launch {
                    busy++
                    try {
                        flights = out
                        flightProgress.snapTo(0f)
                        playSfx(SfxKind.SOLID_THUNK)
                        flightProgress.animateTo(1f, tween(ms(if (handEnded) 1500L else 650L), easing = LinearEasing))
                    } finally {
                        flights = emptyList()
                        busy--
                    }
                }
            }
        }
        if (handEnded) {
            val winners = s.seats.indices.filter { s.seats[it].won > 0 && s.results.any { r -> !r.refund && it in r.winners } }
            val humanWon = winners.any { w ->
                val human = !s.seats[w].isBot
                human && (!isNetworked || w == localSeat) && (!isNetworked || localSeat >= 0)
            }
            if (s.matchOver) {
                val iWon = s.winnerPlayerId != null && s.seats.indexOfFirst { it.playerId == s.winnerPlayerId }.let { w ->
                    w >= 0 && !s.seats[w].isBot && (!isNetworked || w == localSeat)
                }
                if (iWon) {
                    playSfx(SfxKind.SUCCESS_CHIME)
                    haptics(HapticSignal.CELEBRATION)
                    if (!reduced) burst.spawn(Offset(0.5f, 0.35f), 90, listOf(Gold, Color.White, Color(0xFF63D3A0), Color(0xFFE57373)), speedRange = 0.25f..0.95f, lifeRangeSeconds = 1.1f..2.1f, gravity = 0.9f)
                } else {
                    playSfx(SfxKind.INVALID_BUZZ)
                    haptics(HapticSignal.FAILURE)
                }
            } else if (humanWon) {
                playSfx(SfxKind.SUCCESS_CHIME)
                haptics(HapticSignal.SUCCESS)
                if (!reduced) burst.spawn(Offset(0.5f, 0.45f), 40, listOf(Gold, Color.White), speedRange = 0.2f..0.7f, lifeRangeSeconds = 0.9f..1.5f, gravity = 0.8f)
            }
            val bustedMe = mine >= 0 && s.seats[mine].busted && !prev.seats[mine].busted
            if (bustedMe) haptics(HapticSignal.FAILURE)
        }
        if (myTurnJustStarted(prev, s, mine)) haptics(HapticSignal.LIGHT_TICK)
    }

    // The raise panel belongs to one turn.
    LaunchedEffect(s.eventSerial, traySeat) {
        raiseOpen = false
        whyKey = null
        sentSerial = -1
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(4000); whyKey = null } }

    // The computer players, and the run-out of the board, are driven by the host (or the local device).
    LaunchedEffect(s.eventSerial, driving) {
        val cur = game.state.value ?: return@LaunchedEffect
        if (!driving || cur.matchOver) return@LaunchedEffect
        if (cur.runout && !cur.handOver) {
            delay(ms(1000L).toLong())
            while (busy > 0 || dealAnim.isRunning) delay(40)
            game.stepRunout()
            return@LaunchedEffect
        }
        if (!game.botToAct(cur)) return@LaunchedEffect
        thinkingSeat = cur.toAct
        try {
            coroutineScope {
                val level = CpuLevel.fromDifficulty(game.difficulty)
                val think = ms(level.minThinkMillis + Random.nextLong(0L, 700L)).toLong().coerceAtLeast(400L)
                val deferred = async { game.computeBotDecision() }
                delay(think)
                while (busy > 0 || dealAnim.isRunning) delay(40)
                val decision = deferred.await()
                if (decision != null) game.applyBotDecision(decision)
            }
        } finally {
            thinkingSeat = -1
        }
    }

    // Idle-turn limit: the host moves on a human seat that has not acted for a minute (networked tables only).
    LaunchedEffect(s.eventSerial, s.toAct, driving) {
        val cur = game.state.value ?: return@LaunchedEffect
        if (!driving || game.idleTimeoutAction(cur) == null) return@LaunchedEffect
        delay(com.gamesuite.core.IdleTurnPolicy.TURN_LIMIT_MS)
        game.applyIdleTimeout(cur)
    }

    // ---- words ------------------------------------------------------------------------------------
    fun seatName(i: Int) = s.seats.getOrNull(i)?.displayName ?: ""
    val statusText = holdemStatusLine(
        words = words, s = s, myTurn = myTurn, hostLeft = hostLeft, passMode = passMode,
        actorIsHuman = actorIsHuman, thinking = thinkingSeat == s.toAct && thinkingSeat >= 0
    )

    val whyText: String? = whyKey?.let { k ->
        when (k) {
            HoldemReject.NOT_YOUR_TURN -> stringResource(R.string.holdem_why_turn)
            HoldemReject.MUST_CALL -> stringResource(R.string.holdem_why_must_call)
            HoldemReject.NOTHING_TO_CALL -> stringResource(R.string.holdem_why_nothing_to_call)
            HoldemReject.BELOW_MIN_RAISE -> stringResource(R.string.holdem_why_min_raise, options?.minRaiseTo ?: s.bigBlind)
            HoldemReject.ABOVE_STACK, HoldemReject.NO_CHIPS -> stringResource(R.string.holdem_why_stack, (options?.maxRaiseTo ?: 0))
            HoldemReject.CANNOT_RAISE -> stringResource(R.string.holdem_why_no_reopen)
            HoldemReject.HAND_OVER, HoldemReject.NO_HAND -> stringResource(R.string.holdem_why_hand_over)
            HoldemReject.MATCH_OVER -> stringResource(R.string.holdem_why_match_over)
            else -> null
        }
    }

    fun act(action: HoldemAction) {
        if (traySeat < 0) return
        val r = game.act(traySeat, action)
        if (r != HoldemReject.NONE) {
            whyKey = r
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        } else {
            whyKey = null
            raiseOpen = false
            if (isNetworked && !isHostDevice(localSeat)) sentSerial = s.eventSerial
        }
    }

    val blindsText = stringResource(R.string.holdem_blinds, s.smallBlind, s.bigBlind)
    val untilRise = HoldemBlinds.handsUntilRise(s.config.speed, s.handNumber.coerceAtLeast(1))
    val riseText = if (untilRise != null && !s.matchOver) stringResource(R.string.holdem_blinds_rise, untilRise) else ""
    val handText = stringResource(R.string.holdem_hand_no, s.handNumber)
    val busyAnim = busy > 0 || dealAnim.isRunning

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.matchOver,
        onLeave = game::leaveSession,
        onAbort = { game.abortMatch() },
        leaveKeepsProgress = !isNetworked,
        onUndo = if (!isNetworked && !passMode) ({ game.undo() }) else null,
        canUndo = game.canUndo,
        onHint = if (!isNetworked && !passMode) ({ scope.launch { game.requestHint() } }) else null,
        buttonFill = RoomTop, buttonContent = Ink,
        leaveTitle = stringResource(R.string.holdem_leave_title),
        leaveBody = stringResource(R.string.holdem_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("texas-holdem", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(RoomTop, RoomBottom)))) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("texas-holdem", openCoach)
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            "$handText · $blindsText", color = Ink, style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (riseText.isNotEmpty()) {
                            Text(riseText, color = InkDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                ClStatusLine(statusText, Ink, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, Ink, Panel)
            }

            val table: @Composable (Modifier) -> Unit = { mod ->
                HoldemTable(
                    s = s, anchor = anchor, traySeat = traySeat, words = words,
                    dealAnim = dealAnim.value, boardFlip = boardFlip.map { it.value }, seatFlip = seatFlip.map { it.value },
                    flights = flights, flightProgress = flightProgress.value, burst = burst,
                    thinkingSeat = thinkingSeat, reduced = reduced, colorblind = colorblind,
                    localSeat = if (isNetworked) localSeat else -1, modifier = mod.padding(top = 34.dp)
                )
            }

            val controls: @Composable () -> Unit = {
                Spacer(Modifier.height(6.dp))
                if (needsCover) {
                    PassCover(name = seatName(s.toAct), onShow = { ack = s.handNumber to s.toAct })
                } else {
                    if (traySeat >= 0 && s.seats[traySeat].inHand && !s.seats[traySeat].busted) {
                        HandTray(
                            seat = s.seats[traySeat], s = s, words = words,
                            dealAnim = dealAnim.value, colorblind = colorblind,
                            showName = passMode
                        )
                    }
                    val hintNow = hint
                    if (hintNow != null && !s.handOver && myTurn) HintLine(hintNow, words)
                    if (myTurn && options != null && !s.matchOver) {
                        ActionPanel(
                            s = s, options = options, enabled = !busyAnim && sentSerial != s.eventSerial && !hostLeft,
                            raiseOpen = raiseOpen, raiseTo = raiseTo,
                            onRaiseOpen = {
                                raiseTo = options.minRaiseTo
                                raiseOpen = true
                            },
                            onRaiseClose = { raiseOpen = false },
                            onRaiseTo = { raiseTo = it },
                            onAct = ::act
                        )
                    } else if (s.handOver && !s.matchOver) {
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { game.nextHand() },
                            enabled = !busyAnim && !hostLeft && !spectator,
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1B1405)),
                            modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp)
                        ) { Text(stringResource(R.string.holdem_deal_next), fontWeight = FontWeight.Bold) }
                    }
                }
                if (s.matchOver) {
                    Spacer(Modifier.height(8.dp))
                    val winnerSeat = s.seats.indexOfFirst { it.playerId == s.winnerPlayerId }
                    val iWon = winnerSeat >= 0 && !s.seats[winnerSeat].isBot && (!isNetworked || winnerSeat == localSeat)
                    ClFinishedPanel(
                        title = stringResource(R.string.holdem_winner_title, seatName(winnerSeat)),
                        subtitle = when {
                            isNetworked && !spectator -> stringResource(if (winnerSeat == localSeat) R.string.holdem_you_won else R.string.holdem_you_lost)
                            !isNetworked && !passMode -> stringResource(if (iWon) R.string.holdem_you_won else R.string.holdem_you_lost)
                            else -> null
                        },
                        lines = s.seats.sortedByDescending { it.chips }.map { stringResource(R.string.holdem_final_line, LocalContext.current.seatLabel(it.displayName), it.chips) },
                        canPlayAgain = !hostLeft && !spectator,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() },
                        playAgainLabel = stringResource(R.string.holdem_play_again),
                        backLabel = stringResource(R.string.holdem_back_menu)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    table(Modifier.weight(0.6f).fillMaxHeight())
                    Column(
                        Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { header(); controls() }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    table(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(
                        Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { controls() }
                }
            }
        }
    }
}

/** The one-line, neutral-form status ("name: action") that is also the polite live region. */
private fun holdemStatusLine(
    words: HoldemWords, s: HoldemState, myTurn: Boolean, hostLeft: Boolean, passMode: Boolean,
    actorIsHuman: Boolean, thinking: Boolean
): String {
    val ctx = words.ctx
    fun name(i: Int) = ctx.seatLabel(s.seats.getOrNull(i)?.displayName ?: "")
    val actor = name(s.toAct)
    val last: String = when (s.event) {
        HoldemEvent.FOLD -> ctx.getString(R.string.holdem_ev_fold, name(s.eventSeat))
        HoldemEvent.CHECK -> ctx.getString(R.string.holdem_ev_check, name(s.eventSeat))
        HoldemEvent.CALL -> ctx.getString(R.string.holdem_ev_call, name(s.eventSeat), s.eventAmount)
        HoldemEvent.BET -> ctx.getString(R.string.holdem_ev_bet, name(s.eventSeat), s.eventAmount)
        HoldemEvent.RAISE -> ctx.getString(R.string.holdem_ev_raise, name(s.eventSeat), s.eventAmount)
        HoldemEvent.ALL_IN -> ctx.getString(R.string.holdem_ev_allin, name(s.eventSeat), s.eventAmount)
        HoldemEvent.LEFT -> ctx.getString(R.string.holdem_ev_left, name(s.eventSeat))
        HoldemEvent.RETURNED -> ctx.getString(R.string.holdem_ev_returned, name(s.eventSeat))
        HoldemEvent.HAND -> ctx.getString(R.string.holdem_ev_new_hand, s.handNumber, s.smallBlind, s.bigBlind, name(s.button))
        else -> ""
    }
    val street = when (s.board.size) {
        3 -> ctx.getString(R.string.holdem_street_flop)
        4 -> ctx.getString(R.string.holdem_street_turn)
        5 -> ctx.getString(R.string.holdem_street_river)
        else -> ""
    }
    val result: String = if (s.handOver) {
        val lines = s.seats.indices.filter { s.seats[it].won > 0 }.map { i ->
            val sr = s.seatResults.firstOrNull { it.seat == i }
            if (sr != null) ctx.getString(R.string.holdem_ev_win_with, name(i), s.seats[i].won, words.hand(sr.score))
            else ctx.getString(R.string.holdem_ev_win_plain, name(i), s.seats[i].won)
        }.joinToString("; ")
        when {
            s.matchOver -> ctx.getString(R.string.holdem_ev_match_over, name(s.seats.indexOfFirst { it.playerId == s.winnerPlayerId }))
            !s.showdown -> ctx.getString(R.string.holdem_ev_uncontested, lines)
            else -> ctx.getString(R.string.holdem_ev_hand_over, lines)
        }
    } else ""
    val turn = when {
        s.handOver || s.matchOver || s.runout -> ""
        hostLeft -> ctx.getString(R.string.holdem_host_left)
        myTurn -> ctx.getString(R.string.holdem_turn_you)
        passMode && actorIsHuman -> ctx.getString(R.string.holdem_turn_named, actor)
        thinking -> ctx.getString(R.string.holdem_thinking, actor)
        else -> ctx.getString(R.string.holdem_turn_named, actor)
    }
    return listOf(if (s.handOver) "" else last, if (s.handOver) "" else street, result, turn).filter { it.isNotBlank() }
        .joinToString(" ") { part -> if (part.last() in ".!?") part else "$part." }
}

private fun isHostDevice(localSeat: Int) = localSeat == 0

private fun myTurnJustStarted(prev: HoldemState, s: HoldemState, mine: Int): Boolean =
    mine >= 0 && !s.handOver && !s.runout && s.toAct == mine && (prev.toAct != mine || prev.handNumber != s.handNumber)

// ------------------------------------------------------------------------------------------------
// The table
// ------------------------------------------------------------------------------------------------

@Composable
private fun HoldemTable(
    s: HoldemState,
    anchor: Int,
    traySeat: Int,
    words: HoldemWords,
    dealAnim: Float,
    boardFlip: List<Float>,
    seatFlip: List<Float>,
    flights: List<ChipFlight>,
    flightProgress: Float,
    burst: com.gamesuite.ui.effects.ParticleBurst,
    thinkingSeat: Int,
    reduced: Boolean,
    colorblind: Boolean,
    localSeat: Int,
    modifier: Modifier
) {
    val n = s.seats.size
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopStart) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }
        val dens = LocalDensity.current.density
        val plateDp = (maxWidth * (if (n >= 5) 0.27f else 0.31f)).coerceIn(78.dp, 124.dp)
        val plateWpx = with(LocalDensity.current) { plateDp.toPx() }
        val miniCard = plateDp * 0.26f
        val plateHpx = with(LocalDensity.current) { (miniCard * 1.4f + 50.dp).toPx() }
        val boardCardW = ((maxWidth * 0.84f - 4.dp * 4) / 5f).coerceIn(30.dp, 58.dp).coerceAtMost(maxHeight * 0.17f)
        // half the height of the board band (cards, pot pill, spacing): side seats must clear it
        val bandHalfPx = with(LocalDensity.current) { ((boardCardW * 1.4f + 40.dp) / 2f).toPx() }
        val positions = remember(n, anchor, wPx, hPx, plateWpx, plateHpx, bandHalfPx) {
            ringPositions(n, anchor, wPx, hPx, plateWpx, plateHpx, clearBand = bandHalfPx)
        }
        val center = Offset(wPx / 2f, hPx / 2f)
        fun betPos(seat: Int) = lerpOffset(positions[seat], center, 0.46f)
        val potPos = Offset(center.x, center.y + hPx * 0.07f)
        fun endpoint(e: Endpoint): Offset = when (e.kind) {
            Endpoint.BET -> betPos(e.seat)
            Endpoint.POT -> potPos
            else -> positions[e.seat]
        }

        // Highlight: at a showdown the winners' best five stay bright, everything else dims.
        val highlight: Set<Int> = if (s.handOver && s.showdown) {
            s.seatResults.filter { sr -> s.seats[sr.seat].won > 0 }.flatMap { it.best }.toSet()
        } else emptySet()
        val dimOthers = highlight.isNotEmpty()

        HoldemFelt(Modifier.fillMaxSize().padding(horizontal = plateDp * 0.12f, vertical = 10.dp))

        // bets in front of the seats
        for (i in 0 until n) {
            val b = s.seats[i].bet
            if (b > 0) {
                val p = betPos(i)
                Column(
                    Modifier.centerAt(p.x, p.y),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HoldemChipStack(b, chipWidth = 22.dp, description = stringResource(R.string.holdem_bet_cd, LocalContext.current.seatLabel(s.seats[i].displayName), b))
                    Text(
                        b.toString(), color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
            }
        }

        // board and pot
        Column(
            Modifier.centerAt(center.x, center.y + hPx * 0.02f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val boardCd = if (s.board.isEmpty()) stringResource(R.string.holdem_board_empty_cd)
            else stringResource(R.string.holdem_board_cd, words.cardList(s.board))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = boardCd }
            ) {
                for (i in 0 until 5) {
                    val card = s.board.getOrNull(i)
                    if (card == null) {
                        HoldemCardSlot(boardCardW)
                    } else {
                        val inHand = card in highlight
                        HoldemCardView(
                            card = card, width = boardCardW, flip = boardFlip.getOrElse(i) { 1f },
                            fourColor = colorblind, dim = dimOthers && !inHand, glow = dimOthers && inHand,
                            description = words.card(card)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val total = s.totalPot
            val potCd = potDescription(s, words)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(PlateBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clearAndSetSemantics { contentDescription = potCd }
            ) {
                if (s.pot > 0) HoldemChipStack(s.pot, chipWidth = 18.dp)
                Text(
                    stringResource(R.string.holdem_pot, total), color = Gold, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.clearAndSetSemantics { }
                )
            }
            if (s.handOver && s.results.size > 1) {
                Spacer(Modifier.height(2.dp))
                val parts = s.results.filter { !it.refund }.mapIndexed { idx, r ->
                    val label = stringResource(if (idx == 0) R.string.holdem_main_pot else R.string.holdem_side_pot)
                    "$label ${r.amount}"
                }
                Text(parts.joinToString(" · "), color = InkDim, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.clearAndSetSemantics { })
            }
        }

        // seats
        for (i in 0 until n) {
            val seat = s.seats[i]
            val p = positions[i]
            val isTurn = !s.handOver && !s.runout && s.toAct == i
            val faceCards = i != traySeat
            val winner = s.handOver && seat.won > 0
            val dealDelay = 0.04f * ((i - s.button - 1 + n) % n)
            val dealP = if (reduced) 1f else ((dealAnim - dealDelay) / (1f - 0.04f * n)).coerceIn(0f, 1f)
            val sr = s.seatResults.firstOrNull { it.seat == i }
            val tagText = seatTag(s, i, sr?.score?.let { words.hand(it) })
            val cd = seatDescription(s, i, words, tagText, isTurn, localSeat)
            Box(Modifier.centerAt(p.x, p.y)) {
                SeatPlate(
                    seat = seat, plateW = plateDp, miniCard = miniCard, tag = tagText, description = cd,
                    isTurn = isTurn, thinking = thinkingSeat == i, isWinner = winner,
                    showCards = faceCards, flip = seatFlip.getOrElse(i) { 0f }, dealP = dealP,
                    dealFrom = Offset(center.x - p.x, center.y - p.y), colorblind = colorblind, words = words,
                    dim = dimOthers && !winner, highlight = highlight, reduced = reduced, dealer = i == s.button
                )
            }
        }

        // chips in flight, then the confetti
        if (flights.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                for (f in flights) {
                    val t = ((flightProgress - f.start) / (f.end - f.start)).coerceIn(0f, 1f)
                    if (t <= 0f || t >= 1f) continue
                    val e = FastOutSlowInEasing.transform(t)
                    val a = endpoint(f.from)
                    val b = endpoint(f.to)
                    val pos = lerpOffset(a, b, e)
                    val lift = sin(e * PI.toFloat()) * 14f * dens
                    val parts = chipBreakdown(f.amount, maxChips = 6)
                    val chipW = 20f * dens
                    translate(pos.x - chipW, pos.y - lift - chipW * 1.6f) {
                        drawChipStack(parts, chipW * 2f, chipW * 2f, chipW)
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) { drawBurst(burst) }
    }
}

private fun potDescription(s: HoldemState, words: HoldemWords): String {
    val ctx = words.ctx
    val base = ctx.getString(R.string.holdem_pot_cd, s.totalPot)
    if (!s.handOver || s.results.isEmpty()) return base
    val parts = s.results.filter { !it.refund }.mapIndexed { idx, r ->
        val label = ctx.getString(if (idx == 0) R.string.holdem_main_pot else R.string.holdem_side_pot)
        "$label ${r.amount}: " + r.winners.joinToString(", ") { s.seats[it].displayName }
    }
    return if (parts.isEmpty()) base else base + ". " + parts.joinToString(". ")
}

private fun seatTag(s: HoldemState, i: Int, handName: String?): String {
    val p = s.seats[i]
    return when {
        p.busted -> "out"
        s.handOver && p.won > 0 && handName != null -> "+${p.won}|$handName"
        s.handOver && p.won > 0 -> "+${p.won}"
        p.folded -> "fold"
        s.handOver && handName != null && p.revealed -> "|$handName"
        else -> when (p.lastAction) {
            "allin" -> "allin"
            "call" -> "call ${p.lastAmount}"
            "bet" -> "bet ${p.lastAmount}"
            "raise" -> "raise ${p.lastAmount}"
            "check" -> "check"
            "sb" -> "sb"
            "bb" -> "bb"
            else -> ""
        }
    }
}

@Composable
private fun seatTagText(tag: String): String {
    if (tag.isEmpty()) return ""
    val (code, hand) = if ('|' in tag) tag.substringBefore('|') to tag.substringAfter('|') else tag to ""
    val base = when {
        code == "out" -> stringResource(R.string.holdem_act_out)
        code == "fold" -> stringResource(R.string.holdem_act_fold)
        code == "allin" -> stringResource(R.string.holdem_act_allin)
        code == "check" -> stringResource(R.string.holdem_act_check)
        code == "sb" -> stringResource(R.string.holdem_act_sb)
        code == "bb" -> stringResource(R.string.holdem_act_bb)
        code.startsWith("call ") -> stringResource(R.string.holdem_act_call, code.removePrefix("call ").toIntOrNull() ?: 0)
        code.startsWith("bet ") -> stringResource(R.string.holdem_act_bet, code.removePrefix("bet ").toIntOrNull() ?: 0)
        code.startsWith("raise ") -> stringResource(R.string.holdem_act_raise, code.removePrefix("raise ").toIntOrNull() ?: 0)
        code.startsWith("+") -> code
        else -> ""
    }
    return if (hand.isNotEmpty()) (if (base.isNotEmpty()) "$base · $hand" else hand) else base
}

private fun seatDescription(s: HoldemState, i: Int, words: HoldemWords, tag: String, isTurn: Boolean, localSeat: Int): String {
    val ctx = words.ctx
    val p = s.seats[i]
    val parts = ArrayList<String>()
    parts += ctx.seatLabel(p.displayName)
    parts += ctx.getString(R.string.holdem_chips_cd, p.chips)
    if (i == localSeat) parts += ctx.getString(R.string.holdem_tag_you)
    if (i == s.button && !s.matchOver) parts += ctx.getString(R.string.holdem_tag_dealer)
    if (!s.handOver || s.handNumber > 0) {
        if (i == s.smallBlindSeat && p.inHand) parts += ctx.getString(R.string.holdem_tag_sb)
        if (i == s.bigBlindSeat && p.inHand) parts += ctx.getString(R.string.holdem_tag_bb)
    }
    if (p.busted) parts += ctx.getString(R.string.holdem_tag_out)
    else if (p.folded) parts += ctx.getString(R.string.holdem_tag_folded)
    else if (p.allIn && !s.handOver) parts += ctx.getString(R.string.holdem_tag_allin)
    if (p.bet > 0) parts += ctx.getString(R.string.holdem_bet_cd, ctx.seatLabel(p.displayName), p.bet)
    if (p.revealed && p.hole.size == 2 && p.hole.none { it == HIDDEN_CARD }) parts += ctx.getString(R.string.holdem_hole_cd, words.cardList(p.hole))
    if (s.handOver && p.won > 0) parts += ctx.getString(R.string.holdem_won_cd, p.won)
    if (isTurn) parts += ctx.getString(R.string.holdem_tag_turn)
    return parts.joinToString(". ")
}

@Composable
private fun SeatPlate(
    seat: HoldemSeat,
    plateW: Dp,
    miniCard: Dp,
    tag: String,
    description: String,
    isTurn: Boolean,
    thinking: Boolean,
    isWinner: Boolean,
    showCards: Boolean,
    flip: Float,
    dealP: Float,
    dealFrom: Offset,
    colorblind: Boolean,
    words: HoldemWords,
    dim: Boolean,
    highlight: Set<Int>,
    reduced: Boolean,
    dealer: Boolean = false
) {
    val pulse = if (reduced || !(isTurn || isWinner)) 1f else rememberInfiniteTransition(label = "holdem-pulse").animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "holdem-pulse-a"
    ).value
    val out = seat.busted
    val folded = seat.folded
    val borderColor = when {
        isWinner -> Gold.copy(alpha = pulse)
        isTurn -> Gold.copy(alpha = pulse)
        else -> Color.White.copy(alpha = 0.18f)
    }
    Column(
        Modifier
            .width(plateW)
            .alpha(if (out) 0.4f else if (folded || dim) 0.62f else 1f)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val cardH = miniCard * 1.4f
        Box(Modifier.height(cardH * 0.82f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            if (showCards && seat.inHand && !folded && !out && seat.hole.size == 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(-miniCard * 0.28f)) {
                    seat.hole.forEachIndexed { idx, c ->
                        val face = if (c == HIDDEN_CARD) 0f else if (seat.revealed) flip else 0f
                        val lit = c in highlight
                        Box(
                            Modifier.graphicsLayer {
                                val t = (1f - dealP)
                                translationX = dealFrom.x * t
                                translationY = dealFrom.y * t
                                alpha = if (dealP <= 0.02f) 0f else 1f
                                rotationZ = (if (idx == 0) -5f else 5f) * dealP + 180f * t * (if (idx == 0) 1f else -1f)
                                scaleX = 0.6f + 0.4f * dealP
                                scaleY = 0.6f + 0.4f * dealP
                            }
                        ) {
                            HoldemCardView(
                                card = if (c == HIDDEN_CARD) 0 else c, width = miniCard, flip = if (c == HIDDEN_CARD) 0f else face,
                                fourColor = colorblind, glow = lit && face > 0.5f && highlight.isNotEmpty(),
                                dim = highlight.isNotEmpty() && face > 0.5f && !lit,
                                description = if (c != HIDDEN_CARD && seat.revealed && face > 0.5f) words.card(c) else words.card(HIDDEN_CARD)
                            )
                        }
                    }
                }
            }
        }
        Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(PlateBg)
                .border(if (isTurn || isWinner) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                LocalContext.current.seatLabel(seat.displayName), color = Ink, fontWeight = FontWeight.Bold, fontSize = plateSp(12f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { }
            )
            Text(
                seat.chips.toString(), color = Gold, fontWeight = FontWeight.Bold, fontSize = plateSp(13f),
                modifier = Modifier.clearAndSetSemantics { }
            )
            val shown = if (thinking && tag.isEmpty()) "…" else seatTagText(tag)
            if (shown.isNotEmpty()) {
                Text(
                    shown, color = if (isWinner) Gold else InkDim, fontSize = plateSp(11f), maxLines = 2, textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { }
                )
            }
        }
        if (dealer) HoldemDealerButton(20.dp, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Your cards, the hint, the pass-and-play cover
// ------------------------------------------------------------------------------------------------

@Composable
private fun HandTray(
    seat: HoldemSeat,
    s: HoldemState,
    words: HoldemWords,
    dealAnim: Float,
    colorblind: Boolean,
    showName: Boolean
) {
    val cardW = 62.dp
    val flip = ((dealAnim - 0.55f) / 0.45f).coerceIn(0f, 1f)
    val cards = seat.hole
    val known = cards.size == 2 && cards.none { it == HIDDEN_CARD }
    val madeHand = if (known && s.board.size >= 3 && !seat.folded) {
        words.hand(HandEvaluator.evaluate((cards + s.board).toIntArray()))
    } else ""
    val cd = if (known) stringResource(R.string.holdem_hole_cd, words.cardList(cards)) else ""
    Column(
        Modifier.clearAndSetSemantics { contentDescription = cd + if (madeHand.isNotEmpty()) ". $madeHand" else "" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showName) Text(LocalContext.current.seatLabel(seat.displayName), color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (known) {
                cards.forEach { c ->
                    HoldemCardView(
                        card = c, width = cardW, flip = flip, fourColor = colorblind,
                        dim = seat.folded, description = words.card(c)
                    )
                }
            } else {
                repeat(2) { HoldemCardView(card = 0, width = cardW, flip = 0f, description = words.card(HIDDEN_CARD)) }
            }
        }
        if (madeHand.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(madeHand, color = Gold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
private fun HintLine(hint: HoldemHint, words: HoldemWords) {
    val eq = (hint.equity * 100).roundToInt()
    val need = (hint.potOdds * 100).roundToInt()
    val text = when (hint.advice) {
        HintAdvice.FOLD -> stringResource(R.string.holdem_hint_fold, eq, hint.toCall, hint.pot + hint.toCall, need)
        HintAdvice.CHECK -> stringResource(R.string.holdem_hint_check, eq)
        HintAdvice.CALL -> stringResource(R.string.holdem_hint_call, eq, hint.toCall, hint.pot + hint.toCall, need)
        HintAdvice.RAISE -> stringResource(R.string.holdem_hint_raise, eq, hint.raiseTo)
    }
    Box(
        Modifier.padding(top = 6.dp).fillMaxWidth().widthIn(max = 520.dp).clip(RoundedCornerShape(10.dp)).background(Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) { Text(text, color = Ink, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun PassCover(name: String, onShow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 520.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.holdem_pass_title, name), color = Ink, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.holdem_pass_body), color = InkDim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onShow,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1B1405)),
            modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp)
        ) { Text(stringResource(R.string.holdem_pass_show), fontWeight = FontWeight.Bold) }
    }
}

// ------------------------------------------------------------------------------------------------
// Betting controls
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionPanel(
    s: HoldemState,
    options: HoldemOptions,
    enabled: Boolean,
    raiseOpen: Boolean,
    raiseTo: Int,
    onRaiseOpen: () -> Unit,
    onRaiseClose: () -> Unit,
    onRaiseTo: (Int) -> Unit,
    onAct: (HoldemAction) -> Unit
) {
    val o = options
    val opening = s.currentBet == 0
    Column(
        Modifier.padding(top = 8.dp).fillMaxWidth().widthIn(max = 560.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onAct(HoldemAction.FOLD) }, enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 92.dp)
            ) { Text(stringResource(R.string.holdem_fold), fontWeight = FontWeight.Bold) }

            if (o.canCheck) {
                Button(
                    onClick = { onAct(HoldemAction.CHECK) }, enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1B1405)),
                    modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 108.dp)
                ) { Text(stringResource(R.string.holdem_check), fontWeight = FontWeight.Bold) }
            } else {
                val allInCall = o.callAmount >= s.seats[s.toAct].chips
                Button(
                    onClick = { onAct(HoldemAction.CALL) }, enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1B1405)),
                    modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 108.dp)
                ) {
                    Text(
                        stringResource(if (allInCall) R.string.holdem_call_allin else R.string.holdem_call, o.callAmount),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (o.canRaise) {
                Button(
                    onClick = { if (raiseOpen) onRaiseClose() else onRaiseOpen() }, enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D6A), contentColor = Color.White),
                    modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 108.dp)
                ) { Text(stringResource(if (opening) R.string.holdem_bet else R.string.holdem_raise), fontWeight = FontWeight.Bold) }
            } else if (o.canAllIn && !o.canCall) {
                Button(
                    onClick = { onAct(HoldemAction.ALL_IN) }, enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D6A), contentColor = Color.White),
                    modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 108.dp)
                ) { Text(stringResource(R.string.holdem_all_in), fontWeight = FontWeight.Bold) }
            }
        }
        if (!o.canRaise && o.toCall >= 0 && s.seats[s.toAct].chips > o.toCall) {
            Text(
                stringResource(R.string.holdem_why_no_reopen), color = InkDim, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (raiseOpen && o.canRaise) BetPanel(s, o, enabled, raiseTo, onRaiseTo, onAct)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BetPanel(
    s: HoldemState,
    o: HoldemOptions,
    enabled: Boolean,
    value: Int,
    onValue: (Int) -> Unit,
    onAct: (HoldemAction) -> Unit
) {
    val min = o.minRaiseTo
    val max = o.maxRaiseTo
    val step = (s.bigBlind / 2).coerceAtLeast(1)
    val amount = value.coerceIn(min, max)
    fun snap(v: Float): Int {
        if (v >= max - step / 2f) return max
        if (v <= min + step / 2f) return min
        return (min + (((v - min) / step).roundToInt() * step)).coerceIn(min, max)
    }
    val potRaise = s.currentBet + s.totalPot + o.toCall
    val halfRaise = s.currentBet + (s.totalPot + o.toCall) / 2
    val presets = listOf(
        stringResource(R.string.holdem_preset_min) to min,
        stringResource(R.string.holdem_preset_half) to halfRaise.coerceIn(min, max),
        stringResource(R.string.holdem_preset_pot) to potRaise.coerceIn(min, max),
        stringResource(R.string.holdem_preset_allin) to max
    )
    val sliderCd = stringResource(R.string.holdem_slider_cd)
    val sliderState = stringResource(R.string.holdem_slider_state, amount)
    val lessCd = stringResource(R.string.holdem_minus_cd)
    val moreCd = stringResource(R.string.holdem_plus_cd)
    Column(
        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            amount.toString(), color = Gold, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = sliderState }
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onValue((amount - step).coerceAtLeast(min)) }, enabled = enabled && amount > min,
                modifier = Modifier.size(48.dp).semantics { contentDescription = lessCd },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) { Text("−", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics { }) }
            if (min < max) {
                Slider(
                    value = amount.toFloat(), onValueChange = { onValue(snap(it)) },
                    valueRange = min.toFloat()..max.toFloat(), enabled = enabled,
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold),
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                        .semantics { contentDescription = sliderCd; stateDescription = sliderState }
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = { onValue((amount + step).coerceAtMost(max)) }, enabled = enabled && amount < max,
                modifier = Modifier.size(48.dp).semantics { contentDescription = moreCd },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) { Text("+", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics { }) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { (label, target) ->
                val cd = stringResource(R.string.holdem_preset_cd, label, target)
                OutlinedButton(
                    onClick = { onValue(target) }, enabled = enabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (target == amount) Gold else Ink),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cd }
                ) { Text(label, fontWeight = if (target == amount) FontWeight.Bold else FontWeight.Normal) }
            }
        }
        Spacer(Modifier.height(8.dp))
        val label = when {
            amount >= max -> stringResource(R.string.holdem_allin_amount, amount)
            s.currentBet == 0 -> stringResource(R.string.holdem_bet_amount, amount)
            else -> stringResource(R.string.holdem_raise_to, amount)
        }
        Button(
            onClick = { onAct(if (amount >= max) HoldemAction.ALL_IN else HoldemAction.raiseTo(amount)) },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D6A), contentColor = Color.White),
            modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth()
        ) { Text(label, fontWeight = FontWeight.Bold) }
    }
}
