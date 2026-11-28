package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.bumprun.BumpCardKind
import com.gamesuite.games.bumprun.BumpMove
import com.gamesuite.games.bumprun.BumpMoveKind
import com.gamesuite.games.bumprun.BumpPhase
import com.gamesuite.games.bumprun.BumpRunBoard
import com.gamesuite.games.bumprun.BumpRunDeck
import com.gamesuite.games.bumprun.BumpRunEvent
import com.gamesuite.games.bumprun.BumpRunGame
import com.gamesuite.games.bumprun.BumpRunReject
import com.gamesuite.games.bumprun.BumpRunState
import com.gamesuite.games.bumprun.parseBumpRunHelper
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.games.cards.CardSounds
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
import kotlin.math.hypot

private const val BR_COACH_ID = "bump-run"

private val brShapeNames = listOf(
    R.string.bump_run_shape_circle, R.string.bump_run_shape_square, R.string.bump_run_shape_triangle, R.string.bump_run_shape_diamond
)

private fun Context.brText(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

private fun Context.brCardName(id: Int): String {
    val c = BumpRunDeck.byId(id) ?: return ""
    return when (c.kind) {
        BumpCardKind.HOP -> getString(R.string.bump_run_card_hop, c.value)
        BumpCardKind.SWAP -> getString(R.string.bump_run_card_swap)
        BumpCardKind.CHASE -> getString(R.string.bump_run_card_chase)
        BumpCardKind.FREE_EXIT -> getString(R.string.bump_run_card_free_exit)
        BumpCardKind.AGAIN -> getString(R.string.bump_run_card_again)
        BumpCardKind.SHIELD -> getString(R.string.bump_run_card_shield)
    }
}

private fun Context.brCardDesc(id: Int): String {
    val c = BumpRunDeck.byId(id) ?: return ""
    return when (c.kind) {
        BumpCardKind.HOP -> getString(if (c.exits) R.string.bump_run_desc_hop_star else R.string.bump_run_desc_hop, c.value)
        BumpCardKind.SWAP -> getString(R.string.bump_run_desc_swap)
        BumpCardKind.CHASE -> getString(R.string.bump_run_desc_chase)
        BumpCardKind.FREE_EXIT -> getString(R.string.bump_run_desc_free_exit)
        BumpCardKind.AGAIN -> getString(R.string.bump_run_desc_again)
        BumpCardKind.SHIELD -> getString(R.string.bump_run_desc_shield)
    }
}

private fun brSeatLabel(ctx: Context, s: BumpRunState, seat: Int) = ctx.seatLabel(s.players.getOrNull(seat)?.displayName ?: "")

/** Where the pawn [pawn] of [s] rests when it has [progress]. */
private fun brPos(s: BumpRunState, pawn: Int, progress: Int): Offset {
    val per = s.rules.pawnsPerSeat
    return BrGeo.pawnPos(s.sides[pawn / per], pawn % per, progress)
}

/** The cell a move ends on (or, for a swap, the rival pawn's cell), for drawing and tap matching. */
private fun brTargetPos(s: BumpRunState, pawns: List<Int>, m: BumpMove): Offset? = when (m.kind) {
    BumpMoveKind.STEP, BumpMoveKind.CHASE -> brPos(s, m.pawn, m.to)
    BumpMoveKind.EXIT -> brPos(s, m.pawn, 0)
    BumpMoveKind.SWAP -> brPos(s, m.target, pawns[m.target])
    else -> null
}

/** What the moving pawns do for the last event, as stages (legs inside a stage run together) with a duration each. */
private class BrStage(val legs: List<BrLeg>, val ms: Long, val tick: Boolean, val settle: Int = -1)

private fun brStages(cur: BumpRunState, shownPos: List<Int>): List<BrStage> {
    val p = cur.eventPawn
    if (p < 0 || cur.event == BumpRunEvent.SHIELD || cur.event == BumpRunEvent.NO_MOVE) return emptyList()
    val out = ArrayList<BrStage>()
    val from = cur.eventFrom
    val to = cur.eventTo
    val pad = cur.eventPadFrom
    val kind = cur.eventCard.takeIf { it >= 0 }?.let { BumpRunDeck.byId(it)?.kind }
    if (cur.event == BumpRunEvent.SWAP) {
        val v = cur.eventVictim
        val legs = ArrayList<BrLeg>()
        legs += BrLeg(p, brPos(cur, p, from), brPos(cur, p, to), 0.5f)
        if (v >= 0) legs += BrLeg(v, brPos(cur, v, shownPos.getOrElse(v) { cur.pawns[v] }), brPos(cur, v, cur.pawns[v]), 0.5f)
        out += BrStage(legs, 520, false)
        return out
    }
    val end = if (pad >= 0) pad else to
    if (from < 0) {
        out += BrStage(listOf(BrLeg(p, brPos(cur, p, from), brPos(cur, p, end), 0.6f)), 380, false)
    } else if (kind == BumpCardKind.CHASE && end - from > 1) {
        out += BrStage(listOf(BrLeg(p, brPos(cur, p, from), brPos(cur, p, end), 0.9f)), 520, false)
    } else {
        for (g in from + 1..end) out += BrStage(listOf(BrLeg(p, brPos(cur, p, g - 1), brPos(cur, p, g), 0.32f)), 170, true)
    }
    if (pad >= 0) out += BrStage(listOf(BrLeg(p, brPos(cur, p, pad), brPos(cur, p, to), 0.9f)), 420, false)
    val v = cur.eventVictim
    if (v >= 0) out += BrStage(listOf(BrLeg(v, brPos(cur, v, shownPos.getOrElse(v) { 0 }), brPos(cur, v, BumpRunBoard.NEST), 1.1f)), 480, false, settle = p)
    return out
}

/**
 * Bump and Run on one device (2 to 4 seats, people and computers in any mix, optional 2 v 2 teams). Tap the card pile,
 * then tap a lit pawn (and, when it has a choice, the lit square) or press one of the move buttons under the board.
 * Pawns hop square by square; a bump boings the rival back to its Nest. Reduced Motion shows every result at once,
 * and the game speed setting scales every pause and hop.
 *
 * Accessibility: pawns carry a colour, a shape and a number; legal squares are ringed AND carry an arrow; the
 * status line is a polite live region (every draw, move and bump is announced), a blocked tap says why through an
 * assertive line, and every legal move is also a real 48dp+ button with a full spoken label, so the small board
 * cells never have to be hit exactly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BumpRunScreen(
    sessionManager: GameSessionManager,
    game: BumpRunGame,
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
    val theme = remember(dark) { bumpRunTheme(dark) }
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val burst = rememberParticleBurst()
    val density = LocalDensity.current

    // the table this screen already dealt: an equal context emitted again must not restart the board in progress
    val dealtFor = remember { arrayOfNulls<GameContext>(1) }
    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val prev = dealtFor[0]
        if (prev != null && prev.activeMode == ctx.activeMode && prev.players == ctx.players && prev.localPlayerIndex == ctx.localPlayerIndex) return@LaunchedEffect
        dealtFor[0] = ctx
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.length = com.gamesuite.games.bumprun.BumpLength.fromId(extras["length"])
        game.pads = extras["pads"] != "false"
        game.autoPlay = extras["autoPlay"] != "false"
        game.teams = ctx.players.size == 4 && ctx.players.all { it.teamId >= 0 }
        game.helperSeats = parseBumpRunHelper(extras["helper"], ctx.players.size)
        game.holdInputUntilSettled = true
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // ---- animation state ---------------------------------------------------------------------
    val legT = remember { Animatable(0f) }
    var legs by remember { mutableStateOf<List<BrLeg>>(emptyList()) }
    var shownPos by remember { mutableStateOf<List<Int>>(emptyList()) }
    var shownShields by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var animReady by remember { mutableStateOf(false) }
    var lastSerial by remember { mutableIntStateOf(-1) }
    var lastSeed by remember { mutableStateOf(0L) }
    var whyKey by remember { mutableStateOf<BumpRunReject?>(null) }
    var selected by remember { mutableIntStateOf(-1) }
    var shownState by remember { mutableStateOf<BumpRunState?>(null) }
    val liveState = state
    val animating = busy || (liveState != null && liveState.seed == lastSeed && liveState.turnSerial > lastSerial && animReady)

    suspend fun awaitUnpaused() {
        if (game.paused.value) snapshotFlow { game.paused.value }.first { !it }
    }

    LaunchedEffect(state?.turnSerial, state?.seed) {
        val cur = state ?: return@LaunchedEffect
        if (!animReady || cur.seed != lastSeed || cur.turnSerial < lastSerial) {
            animReady = true
            lastSeed = cur.seed
            shownPos = cur.pawns
            shownShields = cur.shields
            shownState = cur
            lastSerial = cur.turnSerial
            selected = -1
            game.settled()
            return@LaunchedEffect
        }
        if (cur.turnSerial == lastSerial) {
            game.settled()
            return@LaunchedEffect
        }
        busy = true
        try {
            awaitUnpaused()
            lastSerial = cur.turnSerial
            selected = -1
            when (cur.event) {
                BumpRunEvent.DRAW, BumpRunEvent.AGAIN, BumpRunEvent.NO_MOVE, BumpRunEvent.DO_OVER -> {
                    playSfx(SfxKind.WHOOSH)
                    if (!reduced) delay(gameSpeed.scale(380L))
                    if (cur.event == BumpRunEvent.NO_MOVE) {
                        haptics(HapticSignal.FAILURE)
                        delay(gameSpeed.scale(if (reduced) 500L else 900L))
                    } else haptics(HapticSignal.LIGHT_TICK)
                }
                else -> {
                    if (!reduced) {
                        for (stage in brStages(cur, shownPos)) {
                            awaitUnpaused()
                            // the mover has finished its own hops: it rests where it ended while the victim boings home
                            if (stage.settle >= 0) shownPos = shownPos.toMutableList().also { it[stage.settle] = cur.pawns[stage.settle] }
                            legs = stage.legs
                            legT.snapTo(0f)
                            legT.animateTo(1f, tween(gameSpeed.scale(stage.ms).toInt().coerceAtLeast(60), easing = LinearEasing))
                            playSfx(if (stage.tick) SfxKind.LIGHT_TICK else SfxKind.SOLID_THUNK)
                        }
                    }
                    legs = emptyList()
                    when (cur.event) {
                        BumpRunEvent.BUMP -> haptics(HapticSignal.STRONG_ACTION)
                        BumpRunEvent.HOME, BumpRunEvent.PAD -> haptics(HapticSignal.SUCCESS)
                        BumpRunEvent.WIN -> {}
                        else -> haptics(HapticSignal.NORMAL_ACTION)
                    }
                    if (cur.event == BumpRunEvent.BUMP) playSfx(SfxKind.INVALID_BUZZ)
                }
            }
        } finally {
            shownPos = cur.pawns
            shownShields = cur.shields
            shownState = cur
            legs = emptyList()
            busy = false
            game.settled()
        }
    }

    val gamePaused = game.paused.value
    // the computer draws, then (holding a card) plays, with a pause that follows the speed setting
    LaunchedEffect(state, animating, gamePaused) {
        val cur = state ?: return@LaunchedEffect
        if (animating || gamePaused || !game.botToMove(cur)) return@LaunchedEffect
        delay(gameSpeed.scale(if (cur.phase == BumpPhase.AWAIT_DRAW) 900L else 1100L).coerceAtLeast(350L))
        game.playBotStep()
    }
    // a single legal move is played for a person after a visible preview (Skip obvious moves)
    LaunchedEffect(state, animating, gamePaused) {
        val cur = state ?: return@LaunchedEffect
        if (animating || gamePaused || !cur.rules.autoPlay || !game.isMyTurn(cur) || cur.phase != BumpPhase.AWAIT_CHOICE) return@LaunchedEffect
        val legal = game.legalMoves()
        if (legal.size != 1 || legal[0].kind == BumpMoveKind.PASS) return@LaunchedEffect
        delay(gameSpeed.scale(600L).coerceAtLeast(300L))
        if (game.state.value === cur) game.playMove(legal[0])
    }

    val helpTitle = stringResource(R.string.bump_run_help_title)
    val helpText = stringResource(R.string.bump_run_help_text)
    val s = state
    if (s == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
            onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
            buttonFill = theme.bg, buttonContent = theme.text
        ) { Box(Modifier.fillMaxSize().background(theme.bg)) }
        return
    }

    val pulse: State<Float> = if (reduced) remember { mutableStateOf(0.7f) } else rememberInfiniteTransition(label = "bump-run-pulse").animateFloat(
        initialValue = 0.3f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "bump-run-pulse-a"
    )
    val v = if (animating) (shownState ?: s) else s
    val per = s.rules.pawnsPerSeat
    val seatColors = ChineseSeatColors
    fun seatName(i: Int) = brSeatLabel(androidContext, s, i)
    val settled = !animating
    val isMyTurn = game.isMyTurn(s)
    val oneHuman = s.players.count { !it.isBot } == 1
    val finishedGames = game.sessionWins.value.values.sum()
    val legal: List<BumpMove> = if (isMyTurn && settled) game.legalMoves() else emptyList()
    val movable = legal.filter { it.kind != BumpMoveKind.PASS }.map { it.pawn }.toSet()
    val targets = legal.filter { selected >= 0 && it.pawn == selected }.mapNotNull { brTargetPos(s, shownPos.ifEmpty { s.pawns }, it) }.map { BrTarget(it) }
    val canDraw = isMyTurn && settled && s.phase == BumpPhase.AWAIT_DRAW
    val myDoOvers = if (isMyTurn) s.doOvers[s.currentPlayerIndex] else 0
    val showDoOver = isMyTurn && settled && s.phase == BumpPhase.AWAIT_CHOICE && myDoOvers > 0
    val showPass = isMyTurn && settled && s.phase == BumpPhase.AWAIT_CHOICE && legal.isEmpty()

    LaunchedEffect(v.over) {
        if (!v.over) return@LaunchedEffect
        val winnerSeat = v.players.indexOfFirst { it.playerId in v.winnerPlayerIds }
        val humanWon = v.players.any { it.playerId in v.winnerPlayerIds && !it.isBot }
        delay(gameSpeed.scale(300L))
        playSfx(SfxKind.SUCCESS_CHIME)
        haptics(if (humanWon) HapticSignal.CELEBRATION else HapticSignal.SUCCESS)
        if (!reduced && winnerSeat >= 0) burst.spawn(Offset(0.5f, 0.4f), if (humanWon) 90 else 50, listOf(Color(0xFFFFD866), Color.White, seatColors[winnerSeat % 4]), speedRange = 0.25f..0.95f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
    }

    // ---- words ---------------------------------------------------------------------------
    val ann = run {
        val ctx = androidContext
        val who = brSeatLabel(ctx, v, v.eventSeat ?: v.currentPlayerIndex)
        val victimSeat = if (v.eventVictim >= 0) v.eventVictim / per else -1
        val victim = if (victimSeat >= 0) brSeatLabel(ctx, v, victimSeat) else ""
        val squares = v.eventTo - v.eventFrom
        val homeCount = (0 until per).count { v.pawns[(v.eventSeat ?: 0) * per + it] == BumpRunBoard.HOME }
        val base = when (v.event) {
            BumpRunEvent.DRAW -> ctx.brText(who, R.string.bump_run_ann_draw, R.string.bump_run_ann_draw_you, ctx.brCardName(v.eventCard))
            BumpRunEvent.DO_OVER -> ctx.brText(who, R.string.bump_run_ann_do_over, R.string.bump_run_ann_do_over_you)
            BumpRunEvent.MOVE -> ctx.brText(who, R.string.bump_run_ann_hop, R.string.bump_run_ann_hop_you, squares)
            BumpRunEvent.PAD -> ctx.brText(who, R.string.bump_run_ann_hop, R.string.bump_run_ann_hop_you, (v.eventPadFrom - v.eventFrom).coerceAtLeast(0)) + " " + ctx.getString(R.string.bump_run_ann_pad)
            BumpRunEvent.EXIT -> ctx.brText(who, R.string.bump_run_ann_exit, R.string.bump_run_ann_exit_you)
            BumpRunEvent.BUMP ->
                if (ctx.isYouName(victim)) ctx.getString(R.string.bump_run_ann_bumped_you, who)
                else ctx.brText(who, R.string.bump_run_ann_bump, R.string.bump_run_ann_bump_you, victim)
            BumpRunEvent.SWAP -> ctx.brText(who, R.string.bump_run_ann_swap, R.string.bump_run_ann_swap_you, victim)
            BumpRunEvent.SHIELD -> ctx.brText(who, R.string.bump_run_ann_shield, R.string.bump_run_ann_shield_you)
            BumpRunEvent.HOME -> ctx.brText(who, R.string.bump_run_ann_home, R.string.bump_run_ann_home_you, homeCount, per)
            BumpRunEvent.AGAIN -> ctx.brText(who, R.string.bump_run_ann_again, R.string.bump_run_ann_again_you)
            BumpRunEvent.NO_MOVE -> ctx.brText(who, R.string.bump_run_ann_no_move, R.string.bump_run_ann_no_move_you)
            BumpRunEvent.WIN ->
                if (v.rules.teams) ctx.getString(R.string.bump_run_ann_win_team, ctx.getString(if (v.players.indexOfFirst { it.playerId in v.winnerPlayerIds } % 2 == 0) R.string.bump_run_team_1 else R.string.bump_run_team_2))
                else ctx.brText(brSeatLabel(ctx, v, v.players.indexOfFirst { it.playerId in v.winnerPlayerIds }), R.string.bump_run_ann_win, R.string.bump_run_ann_win_you)
            else -> ctx.brText(brSeatLabel(ctx, v, v.currentPlayerIndex), R.string.bump_run_ann_start, R.string.bump_run_ann_start_you)
        }
        if (v.eventReshuffled) base + " " + ctx.getString(R.string.bump_run_ann_reshuffle) else base
    }
    val vMine = game.isMyTurn(v)
    val current = brSeatLabel(androidContext, v, v.currentPlayerIndex)
    val turn = when {
        v.over -> ""
        vMine && v.phase == BumpPhase.AWAIT_DRAW -> androidContext.brText(current, R.string.bump_run_ann_turn, R.string.bump_run_ann_turn_you)
        vMine && v.phase == BumpPhase.AWAIT_CHOICE -> stringResource(if (selected >= 0) R.string.bump_run_pick_target else R.string.bump_run_pick_pawn)
        else -> ""
    }
    // Only a result and a person's prompt are in the polite live region; the computer's progress is not re-announced.
    val liveText = listOf(ann, turn).filter { it.isNotBlank() }.joinToString(" ")
    val whyText = whyKey?.let { k ->
        when (k) {
            BumpRunReject.NOT_YOUR_TURN -> stringResource(R.string.bump_run_why_cpu)
            BumpRunReject.DRAW_FIRST -> stringResource(R.string.bump_run_why_draw_first)
            BumpRunReject.DECIDE_FIRST -> stringResource(R.string.bump_run_why_decide)
            BumpRunReject.CANNOT_MOVE -> stringResource(R.string.bump_run_why_cannot_move)
            BumpRunReject.EXACT_ROLL -> stringResource(R.string.bump_run_why_exact)
            BumpRunReject.OWN_PAWN -> stringResource(R.string.bump_run_why_own)
            BumpRunReject.SHIELDED -> stringResource(R.string.bump_run_why_shield)
            BumpRunReject.OVER -> stringResource(R.string.bump_run_why_over)
            BumpRunReject.MOVES_AVAILABLE -> stringResource(R.string.bump_run_why_moves)
            BumpRunReject.NO_DO_OVER -> stringResource(R.string.bump_run_why_no_do_over)
            BumpRunReject.NONE, BumpRunReject.BUSY -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    fun refuse(reason: BumpRunReject) {
        whyKey = reason
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }

    fun tapPile() {
        if (!settled) return
        val reason = game.explainDraw()
        if (reason == BumpRunReject.BUSY) return
        if (reason == BumpRunReject.NONE) { whyKey = null; game.draw() } else refuse(reason)
    }

    fun play(m: BumpMove) {
        if (!settled) return
        val reason = game.explainMove(m)
        if (reason == BumpRunReject.BUSY) return
        if (reason == BumpRunReject.NONE) { whyKey = null; selected = -1; game.playMove(m) } else refuse(reason)
    }

    fun tapDoOver() {
        if (!settled) return
        val reason = game.explainDoOver()
        if (reason == BumpRunReject.BUSY) return
        if (reason == BumpRunReject.NONE) { whyKey = null; game.doOver() } else refuse(reason)
    }

    fun tapPass() {
        if (!settled) return
        val reason = game.explainMove(BumpMove(-1, BumpMoveKind.PASS))
        if (reason == BumpRunReject.BUSY) return
        if (reason == BumpRunReject.NONE) { whyKey = null; game.pass() } else refuse(reason)
    }

    /** A tap on the board, in cell units. */
    fun tapBoard(at: Offset) {
        if (!settled) return
        if (s.phase == BumpPhase.AWAIT_DRAW) { if (isMyTurn) refuse(BumpRunReject.DRAW_FIRST) else refuse(BumpRunReject.NOT_YOUR_TURN); return }
        if (!isMyTurn) { refuse(BumpRunReject.NOT_YOUR_TURN); return }
        val pawnsNow = shownPos.ifEmpty { s.pawns }
        val slop = 0.78f
        fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)
        // 1) a lit destination of the selected pawn
        if (selected >= 0) {
            val hit = legal.filter { it.pawn == selected }.mapNotNull { m -> brTargetPos(s, pawnsNow, m)?.let { m to dist(it, at) } }
                .filter { it.second <= slop }.minByOrNull { it.second }
            if (hit != null) { play(hit.first); return }
        }
        // 2) a pawn that can move (a nest pawn answers to its whole Nest block)
        val candidates = movable.map { p ->
            val pos = brPos(s, p, pawnsNow[p])
            val inNest = pawnsNow[p] < 0
            val d = if (inNest) minOf(dist(pos, at), if (dist(BrGeo.nestCenter(s.sides[p / per]), at) <= 1.5f) 0f else Float.MAX_VALUE) else dist(pos, at)
            p to d
        }.filter { it.second <= slop }.minByOrNull { it.second }
        if (candidates != null) {
            val mine = legal.filter { it.pawn == candidates.first }
            if (mine.size == 1) play(mine[0]) else { selected = candidates.first; playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
            return
        }
        // 3) one of the table's own pawns that has no move with this card
        val own = s.pawns.indices.filter { val seat = it / per; seat == s.currentPlayerIndex || (s.rules.teams && seat % 2 == s.currentPlayerIndex % 2) }
        val touched = own.firstOrNull { dist(brPos(s, it, pawnsNow[it]), at) <= slop }
        if (touched != null) {
            val hop = BumpRunDeck.byId(s.card ?: -1)?.takeIf { it.kind == BumpCardKind.HOP }?.value ?: 0
            val reason = if (hop > 0) game.explainMove(BumpMove(touched, BumpMoveKind.STEP, s.pawns[touched] + hop)) else BumpRunReject.CANNOT_MOVE
            refuse(if (reason == BumpRunReject.NONE) BumpRunReject.CANNOT_MOVE else reason)
        } else selected = -1
    }

    val boardDescription = remember(v.pawns, v.shields, v.players) {
        val ctx = androidContext
        v.pawns.indices.joinToString(". ") { p ->
            val seat = p / per
            val g = v.pawns[p]
            var st = when {
                g < 0 -> ctx.getString(R.string.bump_run_state_nest)
                g < BumpRunBoard.LOOP -> ctx.getString(R.string.bump_run_state_loop, BumpRunBoard.squareOf(v.sides[seat], g)!! + 1)
                g < BumpRunBoard.HOME -> ctx.getString(R.string.bump_run_state_lane, g - BumpRunBoard.LANE_START + 1, BumpRunBoard.LANE_SQUARES)
                else -> ctx.getString(R.string.bump_run_state_home)
            }
            if (v.shields.getOrElse(p) { false }) st += ", " + ctx.getString(R.string.bump_run_state_shielded)
            ctx.getString(R.string.bump_run_pawn_label, brSeatLabel(ctx, v, seat), p % per + 1, st)
        }
    }

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !v.over,
        onLeave = game::leaveSession,
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedGames == 0,
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.bump_run_leave_title),
        leaveBody = stringResource(R.string.bump_run_leave_body)
    ) {
        val (coachShown, openCoach) = rememberClCoach(BR_COACH_ID, ready = true)
        // dialogs, the corner menu and the notification shade take window focus; hops and the computer wait for resume()
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, coachShown) {
            if (!windowFocused || coachShown) game.pause() else game.resume()
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton(BR_COACH_ID, openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        v.players.indices.forEach { i ->
                            val name = seatName(i)
                            val home = (0 until per).count { v.pawns[i * per + it] == BumpRunBoard.HOME }
                            val nest = (0 until per).count { v.pawns[i * per + it] == BumpRunBoard.NEST }
                            BumpRunSeatChip(
                                seat = i, name = name, home = home, per = per, doOvers = v.doOvers[i], isBot = v.players[i].isBot,
                                isTurn = i == v.currentPlayerIndex && !v.over, color = seatColors[i % seatColors.size], theme = theme,
                                description = androidContext.getString(R.string.bump_run_home_label, name, home, per) + ". " +
                                    androidContext.getString(R.string.bump_run_nest_label, name, nest) + ". " +
                                    androidContext.getString(brShapeNames[i % 4]) +
                                    (if (v.doOvers[i] > 0) ". " + androidContext.getString(R.string.bump_run_do_overs_left, v.doOvers[i]) else "") +
                                    (if (v.rules.teams) ". " + androidContext.getString(if (i % 2 == 0) R.string.bump_run_team_1 else R.string.bump_run_team_2) else "") +
                                    (if (v.players[i].isBot) " (" + androidContext.getString(R.string.ccl_cpu_tag) + ")" else "")
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClStatusLine(liveText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val board: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    val fit = fitBoard(
                        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), columns = BrGeo.SIDE, rows = BrGeo.SIDE,
                        maxCellPx = with(density) { 84.dp.toPx() }
                    )
                    val cellDp = with(density) { fit.cellPx.toDp() }
                    BumpRunBoardView(
                        s = s, pawns = shownPos.ifEmpty { s.pawns }, shields = shownShields.ifEmpty { s.shields },
                        legs = { legs }, legT = legT, pulse = pulse, cellDp = cellDp, theme = theme, seatColors = seatColors,
                        movable = movable, targets = targets, selected = selected, description = boardDescription,
                        burst = burst, onTap = ::tapBoard
                    )
                }
            }

            val controls: @Composable () -> Unit = {
                if (v.over) {
                    val winnerSeat = v.players.indexOfFirst { it.playerId in v.winnerPlayerIds }
                    val title = when {
                        winnerSeat < 0 -> ""
                        v.rules.teams -> stringResource(R.string.bump_run_result_win_team, stringResource(if (winnerSeat % 2 == 0) R.string.bump_run_team_1 else R.string.bump_run_team_2))
                        androidContext.isYouName(v.players[winnerSeat].displayName) -> stringResource(R.string.bump_run_result_win_you)
                        else -> stringResource(R.string.bump_run_result_win, seatName(winnerSeat))
                    }
                    ClFinishedPanel(
                        title = title, subtitle = stringResource(R.string.bump_run_score_title),
                        lines = v.players.indices.flatMap { i ->
                            listOf(
                                stringResource(R.string.bump_run_score_line, seatName(i), (0 until per).count { v.pawns[i * per + it] == BumpRunBoard.HOME }, per),
                                stringResource(R.string.bump_run_score_bumps, seatName(i), v.bumpsMade[i], v.bumpsTaken[i])
                            )
                        } + stringResource(R.string.bump_run_score_turns, v.turnsTaken) + (if (finishedGames > 1) listOf(
                            stringResource(R.string.bump_run_score_session, v.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)}: ${game.sessionWins.value[it.playerId] ?: 0}" })
                        ) else emptyList()),
                        canPlayAgain = true,
                        playAgainLabel = stringResource(R.string.bump_run_play_again),
                        backLabel = stringResource(R.string.bump_run_back_to_menu),
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                } else {
                    val shownCardId = v.card ?: v.eventCard.takeIf { it >= 0 }
                    val card = shownCardId?.let { BumpRunDeck.byId(it) }
                    val pileLabel = stringResource(R.string.bump_run_pile_label, s.pile.size) +
                        (if (canDraw) ". " + stringResource(R.string.bump_run_tap_pile) else "")
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
                    ) {
                        BumpRunPile(count = s.pile.size, active = canDraw, pulse = pulse, theme = theme, description = pileLabel, onClick = ::tapPile)
                        if (card != null) {
                            val cardName = androidContext.brCardName(card.id)
                            val label = if (v.card != null) stringResource(R.string.bump_run_card_label, cardName, androidContext.brCardDesc(card.id))
                            else stringResource(R.string.bump_run_last_card, cardName)
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(104.dp)) {
                                BumpRunCardFace(card.kind, card.value, card.exits, theme, 112.dp, label)
                                Text(cardName, color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    if (card != null && v.card != null) {
                        Text(androidContext.brCardDesc(card.id), color = theme.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (showDoOver || showPass) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                            if (showDoOver) Button(onClick = ::tapDoOver, modifier = Modifier.heightIn(min = 56.dp)) {
                                Text(stringResource(R.string.bump_run_do_over_button) + " · " + myDoOvers)
                            }
                            if (showPass) OutlinedButton(onClick = ::tapPass, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bump_run_pass_button)) }
                        }
                    }
                    if (legal.isNotEmpty() && legal.size <= 12) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            legal.forEach { m ->
                                if (m.kind != BumpMoveKind.PASS) BumpRunMoveChip(s, m, androidContext, seatColors, theme) { play(m) }
                            }
                        }
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.42f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { header() }
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.weight(1f, fill = true).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { controls() }
                    }
                    board(Modifier.weight(0.58f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    board(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { controls() }
                }
            }
        }
    }
}

/** One seat: its marble (colour and shape), pawns Home, a little do-over count when it holds some. */
@Composable
private fun BumpRunSeatChip(
    seat: Int, name: String, home: Int, per: Int, doOvers: Int, isBot: Boolean, isTurn: Boolean,
    color: Color, theme: BumpRunTheme, description: String
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
        ClSeatBadge(color, seat % 4, 34)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(name, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text("$home/$per", color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        if (doOvers > 0) {
            Spacer(Modifier.width(6.dp))
            Text("↻$doOvers", color = theme.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (isBot) {
            Spacer(Modifier.width(4.dp))
            Text("⚙", color = theme.text.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The card pile: tap it to draw. A pulsing ring (a steady one under Reduced Motion) shows it is waiting for a tap. */
@Composable
private fun BumpRunPile(count: Int, active: Boolean, pulse: State<Float>, theme: BumpRunTheme, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .size(104.dp, 128.dp)
            .clip(shape)
            .background(theme.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        BumpRunCardFace(null, 0, false, theme, 112.dp, "")
        Box(
            Modifier.matchParentSize()
                .border(if (active) 4.dp else 1.dp, if (active) theme.rim.copy(alpha = 0.35f + 0.65f * pulse.value) else theme.text.copy(alpha = 0.15f), shape)
        )
        Text(
            "$count", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp).background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** A legal move as a real button: the mover's marble, its number and a symbol; the full sentence is its spoken label. */
@Composable
private fun BumpRunMoveChip(s: BumpRunState, m: BumpMove, ctx: Context, seatColors: List<Color>, theme: BumpRunTheme, onClick: () -> Unit) {
    val per = s.rules.pawnsPerSeat
    val seat = m.pawn / per
    val n = m.pawn % per + 1
    val owner = if (seat != s.currentPlayerIndex) " " + ctx.getString(R.string.bump_run_chip_of, brSeatLabel(ctx, s, seat)) else ""
    val (symbol, sentence) = when (m.kind) {
        BumpMoveKind.STEP ->
            if (m.to == BumpRunBoard.HOME) "→ ⌂" to ctx.getString(R.string.bump_run_chip_home, n)
            else "→ ${m.to - s.pawns[m.pawn]}" to ctx.getString(R.string.bump_run_chip_step, n, m.to - s.pawns[m.pawn])
        BumpMoveKind.EXIT -> "★" to ctx.getString(R.string.bump_run_chip_exit, n)
        BumpMoveKind.CHASE -> "➤" to ctx.getString(R.string.bump_run_chip_chase, n)
        BumpMoveKind.SWAP -> "⇄ #${m.target % per + 1}" to ctx.getString(R.string.bump_run_chip_swap, n, brSeatLabel(ctx, s, m.target / per), m.target % per + 1)
        BumpMoveKind.SHIELD -> "◈" to ctx.getString(R.string.bump_run_chip_shield, n)
        BumpMoveKind.PASS -> "" to ""
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(theme.card)
            .border(2.dp, seatColors[seat % seatColors.size], shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = sentence + owner },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ClSeatBadge(seatColors[seat % seatColors.size], seat % 4, 30)
        Text("#$n", color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(symbol, color = theme.text, style = MaterialTheme.typography.titleMedium)
    }
}
