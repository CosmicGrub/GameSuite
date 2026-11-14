package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.slideclimb.SlideClimbEvent
import com.gamesuite.games.slideclimb.SlideClimbGame
import com.gamesuite.games.slideclimb.SlideClimbReject
import com.gamesuite.games.slideclimb.SlideClimbRules
import com.gamesuite.games.slideclimb.SlideClimbState
import com.gamesuite.games.slideclimb.slideClimbHandicapFromExtras
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val SC_COACH_ID = "chutes-race"

private val scShapeNames = listOf(
    R.string.slide_climb_shape_circle, R.string.slide_climb_shape_square, R.string.slide_climb_shape_triangle,
    R.string.slide_climb_shape_diamond, R.string.slide_climb_shape_star, R.string.slide_climb_shape_plus
)

private fun Context.scText(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

/**
 * Slide and Climb on one device (2 to 4 seats, people and computers in any mix). The die is a big tappable
 * button (the whole screen is icons and numbers; the one status line is for adults and screen readers),
 * pieces hop square by square, ropes carry them up and slides carry them down. A seat holding a spare roll
 * (the handicap) gets two round buttons after rolling: a check to keep the roll, a die in a circular arrow to
 * roll again. Reduced Motion drops the tumble, the hops and the confetti and shows each result at once; the
 * game speed setting scales every pause and every hop.
 *
 * Accessibility: every control is at least 56dp, each seat is a colour AND a shape (circle, square, triangle,
 * diamond ...) and the status line is a polite live region, so TalkBack hears every roll, move and the winner;
 * a blocked tap says why through an assertive line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlideClimbScreen(
    sessionManager: GameSessionManager,
    game: SlideClimbGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.REVERSI, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val theme = remember(dark) { slideClimbTheme(dark) }
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val burst = rememberParticleBurst()
    val density = LocalDensity.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.exactFinish = extras["exactFinish"] == "true"
        game.handicapSeats = slideClimbHandicapFromExtras(extras, ctx.players.size)
        // a tap that lands before this screen has recomposed is refused by the game itself (see settled() below)
        game.holdInputUntilSettled = true
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // ---- animation state ---------------------------------------------------------------------
    val tumble = remember { Animatable(1f) }   // 0 = just thrown, 1 = settled
    val legT = remember { Animatable(0f) }
    var leg by remember { mutableStateOf<SlideClimbLeg?>(null) }
    var shownPos by remember { mutableStateOf<List<Int>>(emptyList()) }
    var shownDice by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var animReady by remember { mutableStateOf(false) }
    var lastRollSerial by remember { mutableIntStateOf(-1) }
    var lastMoveSerial by remember { mutableIntStateOf(-1) }
    var whyKey by remember { mutableStateOf<SlideClimbReject?>(null) }
    // the state the board last finished animating: results are worded, announced and the winner panel is shown from it
    var shownState by remember { mutableStateOf<SlideClimbState?>(null) }
    val liveState = state
    // an animation is owed (new serials, not a restart / new board) or running; read in composition on purpose,
    // it changes only when an action starts or ends
    val animating = busy || (liveState != null &&
        liveState.rollSerial >= lastRollSerial && liveState.moveSerial >= lastMoveSerial &&
        (liveState.rollSerial != lastRollSerial || liveState.moveSerial != lastMoveSerial))

    // Pause (dialog, background): hops and bot steps wait for resume().
    suspend fun awaitUnpaused() {
        if (game.paused.value) snapshotFlow { game.paused.value }.first { !it }
    }

    LaunchedEffect(state?.rollSerial, state?.moveSerial, state?.winnerPlayerId) {
        val cur = state ?: return@LaunchedEffect
        if (!animReady || cur.rollSerial < lastRollSerial || cur.moveSerial < lastMoveSerial) {
            animReady = true
            shownPos = cur.positions
            shownDice = cur.dice
            shownState = cur
            lastRollSerial = cur.rollSerial
            lastMoveSerial = cur.moveSerial
            tumble.snapTo(1f)
            game.settled()
            return@LaunchedEffect
        }
        val newRoll = cur.rollSerial != lastRollSerial
        val newMove = cur.moveSerial != lastMoveSerial
        if (!newRoll && !newMove) {
            game.settled()
            return@LaunchedEffect
        }
        busy = true
        try {
            awaitUnpaused()
            if (newRoll) {
                lastRollSerial = cur.rollSerial
                shownDice = cur.dice
                if (reduced) {
                    tumble.snapTo(1f)
                } else {
                    tumble.snapTo(0f)
                    playSfx(SfxKind.WHOOSH)
                    val ticker = launch {
                        repeat(3) { delay(gameSpeed.scale(170L)); playSfx(SfxKind.LIGHT_TICK) }
                    }
                    tumble.animateTo(1f, tween(gameSpeed.scale(720L).toInt().coerceAtLeast(160), easing = LinearEasing))
                    ticker.cancel()
                }
                tumble.snapTo(1f)
                if (cur.event == SlideClimbEvent.ROLL) {
                    playSfx(SfxKind.SOLID_THUNK)
                    haptics(HapticSignal.NORMAL_ACTION)
                }
            }
            if (newMove) {
                lastMoveSerial = cur.moveSerial
                val seat = cur.eventSeat
                if (seat != null && !reduced) {
                    var at = slideClimbPiecePos(seat, cur.eventFrom, 1)
                    val hopMs = gameSpeed.scale(170L).coerceAtLeast(60L)
                    val path = SlideClimbRules.hopPath(cur.eventFrom, cur.dice, cur.exactFinish)
                    if (path.isEmpty()) {
                        // an exact finish that overshoots: a little hop on the spot
                        leg = SlideClimbLeg(seat, at, at, 0.35f)
                        legT.snapTo(0f)
                        playSfx(SfxKind.INVALID_BUZZ)
                        legT.animateTo(1f, tween((hopMs * 2).toInt(), easing = LinearEasing))
                    }
                    for (square in path) {
                        awaitUnpaused()
                        val next = slideClimbCellCenter(square)
                        leg = SlideClimbLeg(seat, at, next, 0.32f)
                        legT.snapTo(0f)
                        legT.animateTo(1f, tween(hopMs.toInt(), easing = LinearEasing))
                        playSfx(SfxKind.LIGHT_TICK)
                        at = next
                    }
                    if (cur.eventTo != cur.eventLanded) {
                        val dest = slideClimbCellCenter(cur.eventTo)
                        delay(gameSpeed.scale(120L))
                        awaitUnpaused()
                        leg = SlideClimbLeg(seat, at, dest, if (cur.event == SlideClimbEvent.CLIMB) 0.5f else 0.1f)
                        legT.snapTo(0f)
                        playSfx(if (cur.event == SlideClimbEvent.CLIMB) SfxKind.SUCCESS_CHIME else SfxKind.WHOOSH)
                        legT.animateTo(1f, tween(gameSpeed.scale(650L).toInt().coerceAtLeast(160), easing = LinearEasing))
                    }
                    leg = null
                    playSfx(SfxKind.SOLID_THUNK)
                }
                when (cur.event) {
                    SlideClimbEvent.CLIMB -> haptics(HapticSignal.SUCCESS)
                    SlideClimbEvent.SLIDE -> haptics(HapticSignal.STRONG_ACTION)
                    SlideClimbEvent.STUCK -> haptics(HapticSignal.FAILURE)
                    SlideClimbEvent.WIN -> {}
                    else -> haptics(HapticSignal.NORMAL_ACTION)
                }
            }
        } finally {
            shownPos = cur.positions
            shownState = cur
            leg = null
            busy = false
            game.settled()
        }
    }

    // the computer rolls, then (with a spare roll in hand) decides, after a short pause that follows the speed setting
    val gamePaused = game.paused.value
    LaunchedEffect(state, animating, gamePaused) {
        val cur = state ?: return@LaunchedEffect
        if (animating || gamePaused || !game.botToMove(cur)) return@LaunchedEffect
        delay(gameSpeed.scale(if (cur.pending) 1100L else 800L).coerceAtLeast(350L))
        game.playBotStep()
    }

    val helpTitle = stringResource(R.string.slide_climb_help_title)
    val helpText = stringResource(R.string.slide_climb_help_text)
    val s = state
    if (s == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
            onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
            buttonFill = theme.bg, buttonContent = theme.text
        ) {
            Box(Modifier.fillMaxSize().background(theme.bg))
        }
        return
    }

    // a State, never .value here: it is read only inside draw / graphicsLayer lambdas, so the pulse never recomposes the screen
    val pulse: State<Float> = if (reduced) remember { mutableStateOf(0.7f) } else rememberInfiniteTransition(label = "slide-climb-pulse").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "slide-climb-pulse-a"
    )
    // what the words, chips and winner panel show: the state the board has finished animating
    val v = if (animating) (shownState ?: s) else s

    fun seatName(i: Int) = s.players.getOrNull(i)?.displayName ?: ""
    val seatColors = ChineseSeatColors
    val isMyTurn = game.isMyTurn(s)
    val settled = !animating
    val finishedGames = game.sessionWins.value.values.sum()
    val current = androidContext.seatLabel(seatName(v.currentPlayerIndex))
    val oneHuman = s.players.count { !it.isBot } == 1
    val canRoll = isMyTurn && settled && !s.pending
    val showDecision = isMyTurn && settled && s.pending

    LaunchedEffect(v.over) {
        if (!v.over) return@LaunchedEffect
        val winnerSeat = v.players.indexOfFirst { it.playerId == v.winnerPlayerId }
        val humanWon = winnerSeat >= 0 && !v.players[winnerSeat].isBot
        delay(gameSpeed.scale(300L))
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.4f), 90, listOf(Color(0xFFFFD866), Color.White, seatColors[winnerSeat % 6]), speedRange = 0.25f..0.95f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
        } else {
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.SUCCESS)
            if (!reduced && winnerSeat >= 0) burst.spawn(Offset(0.5f, 0.4f), 50, listOf(Color(0xFFFFD866), Color.White, seatColors[winnerSeat % 6]), speedRange = 0.2f..0.8f, lifeRangeSeconds = 0.9f..1.6f, gravity = 0.9f)
        }
    }

    // ---- words ---------------------------------------------------------------------------
    // Everything below is worded from [v], the state the board has finished animating, so a result is spoken
    // (and the winner panel shown) only once the piece has landed.
    val ann = run {
        val who = seatName(v.eventSeat ?: v.currentPlayerIndex)
        val base = when (v.event) {
            SlideClimbEvent.ROLL -> androidContext.scText(who, R.string.slide_climb_ann_pending, R.string.slide_climb_ann_pending_you, v.dice)
            SlideClimbEvent.MOVE -> androidContext.scText(who, R.string.slide_climb_ann_move, R.string.slide_climb_ann_move_you, v.dice, v.eventTo)
            SlideClimbEvent.CLIMB -> androidContext.scText(who, R.string.slide_climb_ann_climb, R.string.slide_climb_ann_climb_you, v.dice, v.eventTo)
            SlideClimbEvent.SLIDE -> androidContext.scText(who, R.string.slide_climb_ann_slide, R.string.slide_climb_ann_slide_you, v.dice, v.eventTo)
            SlideClimbEvent.BOUNCE -> androidContext.scText(who, R.string.slide_climb_ann_bounce, R.string.slide_climb_ann_bounce_you, v.dice, v.eventTo)
            SlideClimbEvent.STUCK -> androidContext.scText(who, R.string.slide_climb_ann_stuck, R.string.slide_climb_ann_stuck_you, v.dice, v.eventTo)
            SlideClimbEvent.WIN -> androidContext.scText(who, R.string.slide_climb_ann_win, R.string.slide_climb_ann_win_you)
            else -> androidContext.scText(seatName(v.currentPlayerIndex), R.string.slide_climb_ann_start, R.string.slide_climb_ann_start_you)
        }
        if (v.eventRerolled && v.event in SlideClimbEvent.MOVES) base + " " + stringResource(R.string.slide_climb_spare_used) else base
    }
    val vMine = game.isMyTurn(v)
    val vBot = game.botToMove(v)
    val turn = when {
        v.over -> ""
        v.pending && vBot -> stringResource(R.string.slide_climb_deciding, current)
        v.pending -> ""
        vMine && oneHuman -> stringResource(R.string.slide_climb_turn_you)
        vBot -> stringResource(R.string.slide_climb_thinking, current)
        else -> stringResource(R.string.slide_climb_turn_named, current)
    }
    // Only the result and a person's "tap the dice" are in the polite live region; the computer's "is rolling..."
    // changes on every bot step and sits in a quiet line, so TalkBack does not re-read the old result each time.
    val liveText = listOf(ann, if (vBot) "" else turn).filter { it.isNotBlank() }.joinToString(" ")
    val quietTurn = if (vBot) turn else ""
    val whyText = whyKey?.let { k ->
        when (k) {
            SlideClimbReject.NOT_YOUR_TURN -> stringResource(R.string.cc_why_cpu)
            SlideClimbReject.DECIDE_FIRST -> stringResource(R.string.slide_climb_why_decide)
            SlideClimbReject.OVER -> stringResource(R.string.cc_why_over)
            SlideClimbReject.NOTHING_TO_DECIDE, SlideClimbReject.NO_SPARE_ROLL, SlideClimbReject.NONE, SlideClimbReject.BUSY -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    fun refuse(reason: SlideClimbReject) {
        whyKey = reason
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }
    // The game answers BUSY from the moment it accepts an action until settled() (so a second tap in the same
    // input batch cannot roll for the next seat before this screen has recomposed); that is dropped silently.
    fun tapRoll() {
        if (!settled) return
        val reason = game.explainRoll()
        if (reason == SlideClimbReject.BUSY) return
        if (reason == SlideClimbReject.NONE) { whyKey = null; game.roll() } else refuse(reason)
    }
    fun tapKeep() {
        if (!settled) return
        val reason = game.explainDecision(spare = false)
        if (reason == SlideClimbReject.BUSY) return
        if (reason == SlideClimbReject.NONE) { whyKey = null; game.keepRoll() } else refuse(reason)
    }
    fun tapSpare() {
        if (!settled) return
        val reason = game.explainDecision(spare = true)
        if (reason == SlideClimbReject.BUSY) return
        if (reason == SlideClimbReject.NONE) { whyKey = null; game.spareRoll() } else refuse(reason)
    }

    // rebuilt only when a piece actually settles somewhere new (it sorts and formats every rope and slide)
    val boardDescription = remember(v.positions, v.layout, v.players) {
        val pieces = v.players.indices.joinToString(", ") { i ->
            val nm = androidContext.seatLabel(seatName(i))
            val shape = androidContext.getString(scShapeNames[i % scShapeNames.size])
            if (v.positions[i] == 0) androidContext.getString(R.string.slide_climb_board_at_start, nm, shape)
            else androidContext.getString(R.string.slide_climb_board_piece, nm, shape, v.positions[i])
        }
        val ropes = v.layout.ropes.entries.sortedBy { it.key }.joinToString(", ") {
            androidContext.getString(R.string.slide_climb_jump_rope, it.key, it.value)
        }
        val slides = v.layout.slides.entries.sortedBy { it.key }.joinToString(", ") {
            androidContext.getString(R.string.slide_climb_jump_slide, it.key, it.value)
        }
        androidContext.getString(R.string.slide_climb_board_desc, pieces, ropes, slides)
    }
    val diceDescription = when {
        canRoll -> stringResource(R.string.slide_climb_dice_tap)
        shownDice in 1..6 -> stringResource(R.string.slide_climb_dice_value, shownDice)
        else -> stringResource(R.string.slide_climb_dice_none)
    }

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !v.over,
        onLeave = game::leaveSession,
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedGames == 0,
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedGames > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val (coachShown, openCoach) = rememberClCoach(SC_COACH_ID, ready = true)
        // The help / leave dialogs and the corner menu take window focus (a dialog does not pause the Activity), as
        // does the notification shade; the coach card has no window of its own. Bots and hops wait for resume().
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, coachShown) {
            if (!windowFocused || coachShown) game.pause() else game.resume()
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton(SC_COACH_ID, openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        v.players.indices.forEach { i ->
                            val isTurn = i == v.currentPlayerIndex && !v.over
                            SlideClimbChip(
                                seat = i,
                                name = androidContext.seatLabel(seatName(i)),
                                square = shownPos.getOrElse(i) { v.positions[i] },
                                spare = v.rerolls[i] > 0,
                                isBot = v.players[i].isBot,
                                isTurn = isTurn,
                                color = seatColors[i % seatColors.size],
                                theme = theme,
                                description = androidContext.getString(
                                    R.string.slide_climb_chip_desc, androidContext.seatLabel(seatName(i)),
                                    androidContext.getString(scShapeNames[i % scShapeNames.size]), v.positions[i]
                                ) + (if (v.rerolls[i] > 0) " " + androidContext.getString(R.string.slide_climb_chip_spare) else "") +
                                    (if (v.players[i].isBot) " (" + androidContext.getString(R.string.ccl_cpu_tag) + ")" else "")
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClStatusLine(liveText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                if (quietTurn.isNotBlank()) {
                    Text(
                        quietTurn, color = theme.text, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium
                    )
                }
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val board: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    val fit = fitBoard(
                        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), columns = 10, rows = 11,
                        maxCellPx = with(density) { 84.dp.toPx() }
                    )
                    val cellDp = with(density) { fit.cellPx.toDp() }
                    SlideClimbBoardView(
                        s = s, shownPos = shownPos, leg = { leg }, legT = legT, pulse = pulse, cellDp = cellDp,
                        theme = theme, seatColors = seatColors, description = boardDescription, burst = burst
                    )
                }
            }

            val controls: @Composable () -> Unit = {
                if (v.over) {
                    val winner = v.players.firstOrNull { it.playerId == v.winnerPlayerId }
                    ClFinishedPanel(
                        title = winner?.let { youAware(it.displayName, R.string.slide_climb_wins, R.string.yg_win) } ?: "",
                        subtitle = null,
                        lines = v.players.indices.map {
                            stringResource(R.string.slide_climb_final_line, androidContext.seatLabel(seatName(it)), v.positions[it])
                        } + (if (finishedGames > 1) listOf(
                            stringResource(R.string.ccl_session, v.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)}: ${game.sessionWins.value[it.playerId] ?: 0}" })
                        ) else emptyList()),
                        canPlayAgain = true,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlideClimbDice(
                            value = shownDice, tumble = tumble, seed = s.rollSerial,
                            active = canRoll, pulse = pulse, theme = theme, description = diceDescription,
                            onClick = ::tapRoll
                        )
                        if (showDecision) {
                            val keepDesc = stringResource(R.string.slide_climb_keep)
                            val spareDesc = stringResource(R.string.slide_climb_spare)
                            SlideClimbRoundButton(Color(0xFF1B6B36), keepDesc, ::tapKeep) { SlideClimbKeepIcon(Color.White, 44.dp) }
                            SlideClimbRoundButton(if (dark) Color(0xFF6A3F8F) else Color(0xFF541380), spareDesc, ::tapSpare) {
                                SlideClimbSpareIcon(Color.White, 48.dp)
                            }
                        }
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.42f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.weight(1f, fill = true).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            header()
                        }
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) { controls() }
                    }
                    board(Modifier.weight(0.58f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    board(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) { controls() }
                }
            }
        }
    }
}

/** One seat: its piece (colour and shape), the square it is on, a little die if it still holds a spare roll. */
@Composable
private fun SlideClimbChip(
    seat: Int, name: String, square: Int, spare: Boolean, isBot: Boolean, isTurn: Boolean,
    color: Color, theme: SlideClimbTheme, description: String
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (isTurn) color.copy(alpha = 0.24f) else Color.Transparent)
            .border(if (isTurn) 3.dp else 1.dp, if (isTurn) color else theme.text.copy(alpha = 0.2f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClSeatBadge(color, seat % 6, 34)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(name, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text("$square", color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        if (spare) {
            Spacer(Modifier.width(6.dp))
            SlideClimbSpareBadge(theme.text, 22.dp)
        }
        if (isBot) {
            Spacer(Modifier.width(4.dp))
            Text("⚙", color = theme.text.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The big die: tap it to roll. A pulsing ring (a steady one under Reduced Motion) shows it is waiting for a tap.
 * [pulse] and [tumble] are read here (the pulse in a graphicsLayer lambda), so neither recomposes the screen.
 * For TalkBack it is one button with one description: "Roll the dice" while it can be rolled (no second
 * "double tap to roll" label), the face it shows otherwise, and it reads as disabled then (the tap still plays the
 * refusal sound and says why).
 */
@Composable
private fun SlideClimbDice(
    value: Int, tumble: Animatable<Float, *>, seed: Int, active: Boolean, pulse: State<Float>, theme: SlideClimbTheme,
    description: String, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val spin = (1f - tumble.value) * (1f - tumble.value)
    Box(
        Modifier
            .size(128.dp)
            .clip(shape)
            .background(theme.card)
            .alpha(if (active || value > 0) 1f else 0.8f)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (!active) disabled() },
        contentAlignment = Alignment.Center
    ) {
        // a blank face before the first roll (value 0); Die3DView would draw a 5
        if (value in 1..6) {
            Die3DView(
                value = value, spin = spin, axisSeed = seed * 7,
                size = 116.dp, body = Color(0xFFFBF6EA), pip = Color(0xFF2A2118), description = description
            )
        } else {
            Box(Modifier.size(116.dp).semantics { contentDescription = description })
        }
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { this.alpha = if (active) 0.35f + 0.65f * pulse.value else 1f }
                .border(if (active) 4.dp else 1.dp, if (active) theme.rim else theme.text.copy(alpha = 0.15f), shape)
        )
    }
}

/** A 72dp round button with an icon and no words; [description] is what TalkBack says. */
@Composable
private fun SlideClimbRoundButton(fill: Color, description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { icon() }
}
