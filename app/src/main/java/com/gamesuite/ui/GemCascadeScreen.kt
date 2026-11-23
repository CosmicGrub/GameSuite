package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.gemcascade.BoosterKind
import com.gamesuite.games.gemcascade.Cell
import com.gamesuite.games.gemcascade.GemAction
import com.gamesuite.games.gemcascade.GemBatch
import com.gamesuite.games.gemcascade.GemBatchSummary
import com.gamesuite.games.gemcascade.GemBoard
import com.gamesuite.games.gemcascade.GemCascadeGame
import com.gamesuite.games.gemcascade.GemEvent
import com.gamesuite.games.gemcascade.GemEventApplier
import com.gamesuite.games.gemcascade.GemInput
import com.gamesuite.games.gemcascade.GemLevelStore
import com.gamesuite.games.gemcascade.GemMode
import com.gamesuite.games.gemcascade.GemScan
import com.gamesuite.games.gemcascade.GemStatus
import com.gamesuite.games.gemcascade.GemView
import com.gamesuite.games.gemcascade.GoalState
import com.gamesuite.games.gemcascade.GoalType
import com.gamesuite.games.gemcascade.OverReason
import com.gamesuite.games.gemcascade.PieceType
import com.gamesuite.games.gemcascade.Pos
import com.gamesuite.games.gemcascade.RejectReason
import com.gamesuite.games.gemcascade.SpecialKind
import com.gamesuite.games.gemcascade.UtcDate
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private val gcKindNames = listOf(
    R.string.gem_cascade_kind_0, R.string.gem_cascade_kind_1, R.string.gem_cascade_kind_2, R.string.gem_cascade_kind_3,
    R.string.gem_cascade_kind_4, R.string.gem_cascade_kind_5, R.string.gem_cascade_kind_6
)

private fun specialNameRes(s: SpecialKind): Int? = when (s) {
    SpecialKind.NONE -> null
    SpecialKind.FLARE_ROW -> R.string.gem_cascade_special_flare_row
    SpecialKind.FLARE_COL -> R.string.gem_cascade_special_flare_col
    SpecialKind.PRISM -> R.string.gem_cascade_special_prism
    SpecialKind.BURST -> R.string.gem_cascade_special_burst
    SpecialKind.NOVA -> R.string.gem_cascade_special_nova
}

private fun modeNameRes(m: GemMode): Int = when (m) {
    GemMode.CLASSIC -> R.string.gem_cascade_mode_classic
    GemMode.ZEN -> R.string.gem_cascade_mode_zen
    GemMode.LIGHTNING -> R.string.gem_cascade_mode_lightning
    GemMode.QUEST -> R.string.gem_cascade_mode_quest
    GemMode.TWIST -> R.string.gem_cascade_mode_twist
    GemMode.MINE -> R.string.gem_cascade_mode_mine
    GemMode.BUTTERFLIES -> R.string.gem_cascade_mode_butterflies
    GemMode.COUNTDOWN -> R.string.gem_cascade_mode_countdown
}

private fun boosterNameRes(b: BoosterKind): Int = when (b) {
    BoosterKind.HAMMER -> R.string.gem_cascade_booster_hammer
    BoosterKind.SWAP_ANY -> R.string.gem_cascade_booster_swap_any
    BoosterKind.SHUFFLE -> R.string.gem_cascade_booster_shuffle
    BoosterKind.EXTRA_MOVES -> R.string.gem_cascade_booster_extra
    BoosterKind.COLOUR_BOMB -> R.string.gem_cascade_booster_colour_bomb
}

/** Describes one cell in words for TalkBack and the caption (kind, special, obstacles). */
internal fun describePiece(ctx: Context, board: GemBoard, p: Pos): String {
    val cell = board[p]
    val parts = ArrayList<String>()
    when (cell.type) {
        PieceType.EMPTY -> parts += ctx.getString(R.string.gem_cascade_desc_empty)
        PieceType.GEM -> {
            parts += ctx.getString(gcKindNames[cell.kind.coerceIn(0, 6)])
            specialNameRes(cell.special)?.let { parts += ctx.getString(it) }
            if (cell.locked) parts += ctx.getString(R.string.gem_cascade_desc_caged)
            if (cell.timer > 0) parts += ctx.getString(R.string.gem_cascade_desc_bomb, cell.timer)
        }
        PieceType.STONE -> parts += ctx.getString(R.string.gem_cascade_desc_stone, cell.hp)
        PieceType.SPIDER -> parts += ctx.getString(R.string.gem_cascade_desc_spider)
        PieceType.BUTTERFLY -> parts += ctx.getString(R.string.gem_cascade_desc_butterfly)
        PieceType.TREASURE -> parts += ctx.getString(R.string.gem_cascade_desc_treasure)
    }
    val ice = board.iceAt(p)
    if (ice > 0) parts += ctx.getString(R.string.gem_cascade_desc_ice, ice)
    return parts.joinToString(", ")
}

internal fun describeCell(ctx: Context, board: GemBoard, p: Pos, selected: Boolean): String {
    val base = ctx.getString(R.string.gem_cascade_cell_pos, p.r + 1, p.c + 1, describePiece(ctx, board, p))
    return if (selected) base + ", " + ctx.getString(R.string.gem_cascade_selected_word) else base
}

private fun goalText(ctx: Context, g: GoalState): String {
    val res = when (g.type) {
        GoalType.SCORE -> R.string.gem_cascade_goal_score
        GoalType.CLEAR_GEMS -> if (g.kind >= 0) R.string.gem_cascade_goal_clear_colour else R.string.gem_cascade_goal_clear_gems
        GoalType.CLEAR_ICE -> R.string.gem_cascade_goal_ice
        GoalType.BREAK_STONES -> R.string.gem_cascade_goal_stones
        GoalType.BREAK_LOCKS -> R.string.gem_cascade_goal_locks
        GoalType.COLLECT_TREASURE -> R.string.gem_cascade_goal_treasure
        GoalType.ESCAPE_BUTTERFLIES -> R.string.gem_cascade_goal_butterflies
        GoalType.DEFUSE_BOMBS -> R.string.gem_cascade_goal_bombs
        GoalType.MAKE_SPECIALS -> R.string.gem_cascade_goal_specials
        GoalType.SQUASH_SPIDERS -> R.string.gem_cascade_goal_spiders
    }
    return if (g.type == GoalType.CLEAR_GEMS && g.kind >= 0) ctx.getString(res, g.current, g.target, ctx.getString(gcKindNames[g.kind.coerceIn(0, 6)]))
    else ctx.getString(res, g.current, g.target)
}

private fun overText(ctx: Context, r: OverReason?): String = ctx.getString(
    when (r) {
        OverReason.NO_MOVES -> R.string.gem_cascade_over_no_moves
        OverReason.OUT_OF_MOVES -> R.string.gem_cascade_over_out_of_moves
        OverReason.TIME_UP -> R.string.gem_cascade_over_time
        OverReason.BOMB -> R.string.gem_cascade_over_bomb
        OverReason.SPIDER -> R.string.gem_cascade_over_spider
        null -> R.string.gem_cascade_over_no_moves
    }
)

/** One polite line for the live region, built once per finished batch. */
private fun announcement(ctx: Context, s: GemBatchSummary): String {
    val out = ArrayList<String>()
    if (s.swappedBack) out += ctx.getString(R.string.gem_cascade_ann_swapback)
    if (s.rotatedBack) out += ctx.getString(R.string.gem_cascade_ann_rotateback)
    if (s.shuffled) out += ctx.getString(R.string.gem_cascade_ann_shuffle)
    if (s.gemsMatched > 0) {
        out += ctx.getString(R.string.gem_cascade_ann_cleared, s.gemsMatched)
        if (s.bestChain >= 2) out += ctx.getString(R.string.gem_cascade_ann_chain, s.bestChain)
        s.specialsMade.firstOrNull()?.let { sp -> specialNameRes(sp)?.let { out += ctx.getString(R.string.gem_cascade_ann_special, ctx.getString(it)) } }
        if (s.combos > 0) out += ctx.getString(R.string.gem_cascade_ann_combo)
        out += ctx.getString(R.string.gem_cascade_ann_score, s.scoreGained)
    }
    if (s.butterfliesEscaped > 0) out += ctx.getString(R.string.gem_cascade_ann_butterfly)
    if (s.treasures > 0) out += ctx.getString(R.string.gem_cascade_ann_treasure)
    if (s.bombsDefused > 0) out += ctx.getString(R.string.gem_cascade_ann_defused)
    s.levelUp?.let { out += ctx.getString(R.string.gem_cascade_ann_levelup, it) }
    return out.joinToString(" ")
}

private class GemAnim {
    var board: GemBoard = GemBoard(8, 8)
    val offsets = HashMap<Pos, Offset>()
    val fading = HashSet<Pos>()
    val glow = HashSet<Pos>()
    val pop = HashSet<Pos>()
    val rings = HashSet<Pos>()
    var hint: Set<Pos> = emptySet()
    var selected: Pos? = null
    var scan: Pos? = null
    var chainText: String? = null
    var scoreText: String? = null
    var textAt: Offset = Offset(3f, 3f)
}

private fun off(from: Pos, to: Pos) = Offset((from.c - to.c).toFloat(), (from.r - to.r).toFloat())

/**
 * Gem Cascade, the functional 2D baseline: it only DRAWS the engine's step log (swap, clear, fall, spawn tweens, special
 * glows, chain and score pop text). Nothing animated is read in composition (the board is drawn in a Canvas that reads
 * its tween state in the draw scope), an accepted action locks input until its animation has finished, and the
 * game is paused whenever a dialog or the shade takes window focus. Controls: drag or tap-tap to swap (Twist: tap a
 * block), a Hint button, free capped boosters, Pause, and a one-switch scanning mode with a single Select button.
 * TalkBack: every cell is a labelled button, the caption names the selected cell, and one polite line speaks each
 * finished move once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GemCascadeScreen(
    sessionManager: GameSessionManager,
    game: GemCascadeGame,
    settingsViewModel: SettingsViewModel,
    daily: Boolean = false,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val reduced = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val paints = remember { GemPaints() }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // ---- start / resume ---------------------------------------------------------------------
    val dealtFor = remember { arrayOfNulls<GameContext>(1) }
    var switchMode by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        if (dealtFor[0] == ctx) return@LaunchedEffect
        dealtFor[0] = ctx
        val extras = sessionManager.launchOptions.extras
        switchMode = extras["switchMode"] == "true"
        game.configure(if (daily) extras + ("daily" to "true") else extras, GemLevelStore.load(androidContext))
        game.holdInputUntilSettled = true
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    val live = game.view.value
    val batch = game.batch.value
    val paused = game.paused.value

    // ---- animation state (mutated by the playback coroutine, read only in draw scopes) --------
    val anim = remember { GemAnim() }
    val frame = remember { mutableIntStateOf(0) }
    val t = remember { Animatable(1f) }
    val floatT = remember { Animatable(1f) }
    var shown by remember { mutableStateOf<GemView?>(null) }
    var settledBoard by remember { mutableStateOf<GemBoard?>(null) }
    var busy by remember { mutableStateOf(false) }
    var announce by remember { mutableStateOf("") }
    var whyText by remember { mutableStateOf<String?>(null) }
    var selCell by remember { mutableStateOf<Pos?>(null) }
    var armed by remember { mutableStateOf<BoosterKind?>(null) }
    var armedFirst by remember { mutableStateOf<Pos?>(null) }
    var clockwise by remember { mutableStateOf(true) }
    var lastBatch by remember { mutableIntStateOf(-1) }
    var hintShown by remember { mutableStateOf(false) }

    suspend fun awaitUnpaused() {
        if (game.paused.value) snapshotFlow { game.paused.value }.first { !it }
    }

    suspend fun animate(ms: Long) {
        val d = if (reduced) 0L else gameSpeed.scale(ms)
        if (d <= 0L) {
            t.snapTo(1f)
            return
        }
        t.snapTo(0f)
        t.animateTo(1f, tween(d.toInt(), easing = FastOutSlowInEasing))
    }

    fun poke() {
        frame.intValue = frame.intValue + 1
    }

    suspend fun play(b: GemBatch, v: GemView) {
        val ev = b.events
        anim.board = b.before.copy()
        anim.offsets.clear(); anim.fading.clear(); anim.glow.clear(); anim.pop.clear(); anim.rings.clear()
        var lastClear: Pos? = null
        var i = 0
        while (i < ev.size) {
            awaitUnpaused()
            when (val e = ev[i]) {
                is GemEvent.Deal -> { anim.board = v.board.copy(); poke(); i++ }
                is GemEvent.Swap -> {
                    GemEventApplier.apply(anim.board, e)
                    anim.offsets[e.a] = off(e.b, e.a); anim.offsets[e.b] = off(e.a, e.b)
                    haptics(HapticSignal.LIGHT_TICK)
                    animate(150); anim.offsets.clear(); poke(); i++
                }
                is GemEvent.SwapBack -> {
                    GemEventApplier.apply(anim.board, e)
                    anim.offsets[e.a] = off(e.b, e.a); anim.offsets[e.b] = off(e.a, e.b)
                    playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
                    animate(150); anim.offsets.clear(); poke(); i++
                }
                is GemEvent.Rotate -> {
                    GemEventApplier.apply(anim.board, e)
                    for (q in listOf(e.topLeft, Pos(e.topLeft.r, e.topLeft.c + 1), Pos(e.topLeft.r + 1, e.topLeft.c), Pos(e.topLeft.r + 1, e.topLeft.c + 1))) anim.pop += q
                    haptics(HapticSignal.LIGHT_TICK)
                    animate(200); anim.pop.clear(); poke(); i++
                }
                is GemEvent.RotateBack -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE); i++ }
                is GemEvent.Match -> {
                    anim.glow.clear(); anim.glow.addAll(e.cells)
                    animate(130); i++
                }
                is GemEvent.SpecialCreated -> {
                    GemEventApplier.apply(anim.board, e)
                    anim.pop += e.at
                    animate(160); anim.pop.clear(); poke(); i++
                }
                is GemEvent.SpecialActivated -> {
                    anim.rings.clear(); anim.rings.addAll(e.affected)
                    playSfx(SfxKind.WHOOSH); haptics(HapticSignal.STRONG_ACTION)
                    animate(170); anim.rings.clear(); poke(); i++
                }
                is GemEvent.ComboActivated -> {
                    anim.rings.clear(); anim.rings.addAll(e.affected)
                    playSfx(SfxKind.WHOOSH); haptics(HapticSignal.STRONG_ACTION)
                    animate(240); anim.rings.clear(); poke(); i++
                }
                is GemEvent.ObstacleDamaged -> { GemEventApplier.apply(anim.board, e); poke(); i++ }
                is GemEvent.Clear -> {
                    anim.glow.clear()
                    anim.fading.addAll(e.cells)
                    lastClear = e.cells.getOrNull(e.cells.size / 2)
                    playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION)
                    animate(170)
                    GemEventApplier.apply(anim.board, e)
                    anim.fading.clear(); poke(); i++
                }
                is GemEvent.ChainStep -> {
                    if (e.n >= 2) {
                        anim.chainText = androidContext.getString(R.string.gem_cascade_chain_pop, e.n)
                        if (e.n >= 3) haptics(HapticSignal.ESCALATING)
                    }
                    i++
                }
                is GemEvent.ScoreDelta -> {
                    anim.scoreText = "+" + e.points
                    lastClear?.let { anim.textAt = Offset(it.c + 0.5f, it.r + 0.5f) }
                    scope.launch { floatT.snapTo(0f); floatT.animateTo(1f, tween(if (reduced) 1 else gameSpeed.scale(900L).toInt())) }
                    i++
                }
                is GemEvent.Fall, is GemEvent.Spawn, is GemEvent.Escaped -> {
                    var j = i
                    while (j < ev.size && (ev[j] is GemEvent.Fall || ev[j] is GemEvent.Spawn || ev[j] is GemEvent.Escaped)) {
                        val x = ev[j]
                        GemEventApplier.apply(anim.board, x)
                        when (x) {
                            is GemEvent.Fall -> anim.offsets[x.to] = off(x.from, x.to)
                            is GemEvent.Spawn -> anim.offsets[x.at] = Offset(0f, (x.fromRow - x.at.r).toFloat())
                            else -> Unit
                        }
                        j++
                    }
                    animate(280); anim.offsets.clear(); poke(); i = j
                }
                is GemEvent.PieceMoved -> {
                    GemEventApplier.apply(anim.board, e)
                    anim.offsets[e.to] = off(e.from, e.to); anim.offsets[e.from] = off(e.to, e.from)
                    animate(200); anim.offsets.clear(); poke(); i++
                }
                is GemEvent.NoMovesShuffle -> {
                    GemEventApplier.apply(anim.board, e)
                    for (m in e.moves) anim.offsets[m.to] = off(m.from, m.to)
                    animate(320); anim.offsets.clear(); poke(); i++
                }
                is GemEvent.BombTick -> { GemEventApplier.apply(anim.board, e); poke(); i++ }
                is GemEvent.LevelComplete -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION); i++ }
                is GemEvent.GameOver -> { haptics(HapticSignal.LIGHT_TICK); i++ }
                else -> i++
            }
        }
        anim.board = v.board.copy()
        anim.chainText = null; anim.scoreText = null
        poke()
    }

    LaunchedEffect(batch?.serial) {
        val b = game.batch.value ?: return@LaunchedEffect
        if (b.serial == lastBatch) return@LaunchedEffect
        val v = game.view.value ?: return@LaunchedEffect
        busy = true
        if (b.events.firstOrNull() is GemEvent.Deal) { selCell = null; armed = null; armedFirst = null; anim.selected = null }
        try {
            play(b, v)
        } finally {
            val end = game.view.value ?: v
            anim.board = end.board.copy()
            lastBatch = b.serial
            shown = end
            settledBoard = end.board.copy()
            val line = announcement(androidContext, GemBatchSummary.of(b.events))
            if (line.isNotEmpty()) announce = line
            busy = false
            game.settled()
            poke()
        }
    }

    // pause while a dialog, the shade or the corner menu holds window focus
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) game.pause() else game.resume() }

    // Lightning's clock
    val timed = live?.mode == GemMode.LIGHTNING
    val playing = live?.status == GemStatus.PLAYING
    LaunchedEffect(timed, paused, playing) {
        if (!timed || paused || !playing) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameMillis { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameMillis { it }
            game.tick(now - last)
            last = now
        }
    }

    LaunchedEffect(whyText) { if (whyText != null) { delay(3500); whyText = null } }
    LaunchedEffect(hintShown) { if (hintShown) { delay(4000); anim.hint = emptySet(); hintShown = false; poke() } }

    val helpTitle = stringResource(R.string.gem_cascade_help_title)
    val helpText = stringResource(R.string.gem_cascade_help_text)
    val sv = shown
    if (live == null || sv == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = false,
            onLeave = game::abortMatch, onAbort = { game.abortMatch() }
        ) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) }
        return
    }

    val twist = sv.mode == GemMode.TWIST
    val settled = !busy && !paused
    val over = sv.status != GemStatus.PLAYING && !busy
    val scan = remember(sv.rows, sv.cols, twist) { GemScan(sv.rows, sv.cols, blocks = twist) }

    fun refuse(r: RejectReason) {
        whyText = androidContext.getString(
            when (r) {
                RejectReason.NOT_ADJACENT -> R.string.gem_cascade_why_adjacent
                RejectReason.BLOCKED -> R.string.gem_cascade_why_blocked
                RejectReason.BOOSTER_CAP -> R.string.gem_cascade_why_cap
                RejectReason.BAD_TARGET, RejectReason.NOT_ALLOWED -> R.string.gem_cascade_why_target
                RejectReason.NO_MOVES_LEFT, RejectReason.TIME_UP, RejectReason.GAME_OVER -> R.string.gem_cascade_why_over
                else -> R.string.gem_cascade_why_target
            }
        )
        playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
    }

    fun doSwap(a: Pos, b: Pos) {
        selCell = null; anim.selected = null; poke()
        val r = game.swap(a, b) ?: return
        if (!r.accepted && r.events.isEmpty()) refuse(r.reason ?: RejectReason.BLOCKED)
    }

    fun doRotate(tl: Pos) {
        val r = game.rotate(tl, clockwise) ?: return
        if (!r.accepted && r.events.isEmpty()) refuse(r.reason ?: RejectReason.BLOCKED)
    }

    fun runBooster(kind: BoosterKind, at: Pos?, other: Pos? = null) {
        val r = game.useBooster(kind, at, other) ?: return
        if (!r.accepted) refuse(r.reason ?: RejectReason.BAD_TARGET)
        armed = null; armedFirst = null; anim.selected = null; poke()
    }

    fun handleTap(p: Pos) {
        if (!settled || over) return
        val kind = armed
        if (kind != null) {
            when (kind) {
                BoosterKind.HAMMER, BoosterKind.COLOUR_BOMB -> runBooster(kind, p)
                BoosterKind.SWAP_ANY -> {
                    val first = armedFirst
                    if (first == null) { armedFirst = p; anim.selected = p; poke() }
                    else if (GemInput.adjacent(first, p)) runBooster(kind, first, p)
                    else { armedFirst = p; anim.selected = p; poke() }
                }
                else -> Unit
            }
            return
        }
        if (twist) { doRotate(GemInput.blockFor(p, sv.rows, sv.cols)); return }
        when (val o = GemInput.onTap(selCell, p)) {
            is GemInput.TapOutcome.Select -> { selCell = o.pos; anim.selected = o.pos; haptics(HapticSignal.LIGHT_TICK); poke() }
            GemInput.TapOutcome.Deselect -> { selCell = null; anim.selected = null; poke() }
            is GemInput.TapOutcome.Swap -> doSwap(o.a, o.b)
        }
    }

    fun handleDrag(from: Pos, to: Pos) {
        if (!settled || over || twist || armed != null) return
        doSwap(from, to)
    }

    val tapState = rememberUpdatedState<(Pos) -> Unit> { handleTap(it) }
    val dragState = rememberUpdatedState<(Pos, Pos) -> Unit> { a, b -> handleDrag(a, b) }

    fun showHint() {
        if (!settled || over) return
        val a = game.hint()
        val cells: Set<Pos> = when (a) {
            is GemAction.SwapAction -> setOf(a.a, a.b)
            is GemAction.RotateAction -> setOf(a.topLeft, Pos(a.topLeft.r, a.topLeft.c + 1), Pos(a.topLeft.r + 1, a.topLeft.c), Pos(a.topLeft.r + 1, a.topLeft.c + 1))
            else -> emptySet()
        }
        anim.hint = cells; hintShown = cells.isNotEmpty(); poke()
        if (a is GemAction.SwapAction) {
            announce = androidContext.getString(R.string.gem_cascade_hint_swap, a.a.r + 1, a.a.c + 1, a.b.r + 1, a.b.c + 1)
        } else if (a is GemAction.RotateAction) {
            announce = androidContext.getString(R.string.gem_cascade_hint_turn, a.topLeft.r + 1, a.topLeft.c + 1)
        }
    }

    // one-switch scanning
    LaunchedEffect(switchMode, settled, over, sv.rows, twist) {
        if (!switchMode || !settled || over) { anim.scan = null; poke(); return@LaunchedEffect }
        anim.scan = scan.highlight(); poke()
        while (true) {
            delay(gameSpeed.scale(1100L))
            scan.step()
            anim.scan = scan.highlight(); poke()
        }
    }

    fun scanSelect() {
        if (!settled || over) return
        when (val r = scan.select()) {
            is GemScan.Result.Chose -> { anim.selected = r.pos; poke() }
            is GemScan.Result.Swap -> { anim.selected = null; doSwap(r.a, r.b) }
            is GemScan.Result.Block -> doRotate(r.topLeft)
            else -> { anim.selected = null }
        }
        anim.scan = scan.highlight(); poke()
    }

    val fg = MaterialTheme.colorScheme.onBackground
    val bgBoard = if (dark) Color(0xFF2A2D3A) else Color(0xFFE9E6F2)
    val tileA = if (dark) Color(0xFF343848) else Color(0xFFF4F1FA)
    val tileB = if (dark) Color(0xFF2C3040) else Color(0xFFE2DEEF)
    val accent = if (dark) Color(GemCascadeAccent.DARK) else Color(GemCascadeAccent.LIGHT)

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = sv.status == GemStatus.PLAYING,
        onLeave = game::finish,
        onAbort = { game.abortMatch() },
        leaveKeepsProgress = true
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 8.dp)) {
            val landscape = maxWidth > maxHeight * 1.2f
            val hud: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val title = if (daily) stringResource(R.string.gem_cascade_daily_title, UtcDate.iso(UtcDate.epochDay(System.currentTimeMillis())))
                        else stringResource(modeNameRes(sv.mode)) + if (sv.mode == GemMode.QUEST) " " + stringResource(R.string.gem_cascade_level_n, sv.levelId) else ""
                        Text(title, color = fg, fontWeight = FontWeight.Bold)
                        if (sv.mode == GemMode.CLASSIC) Text(stringResource(R.string.gem_cascade_level_n, sv.classicLevel), color = fg)
                        Text(stringResource(R.string.gem_cascade_score, sv.score), color = fg)
                        if (sv.movesLeft >= 0) Text(stringResource(R.string.gem_cascade_moves, sv.movesLeft), color = fg)
                        if (timed) {
                            val secs = ((live.timeLeftMs + 999) / 1000).toInt()
                            Text(stringResource(R.string.gem_cascade_time, secs), color = fg, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (sv.goals.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (g in sv.goals) {
                                val txt = goalText(androidContext, g)
                                Text(txt, color = if (g.done) accent else fg, fontWeight = if (g.done) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.semantics { contentDescription = txt })
                            }
                        }
                    }
                }
            }
            val caption: @Composable () -> Unit = {
                val sel = selCell ?: armedFirst
                val text = if (sel != null && settledBoard != null) stringResource(R.string.gem_cascade_selected, describePiece(androidContext, settledBoard!!, sel))
                else stringResource(R.string.gem_cascade_nothing_selected)
                Text(text, color = fg, style = MaterialTheme.typography.bodySmall)
                Text(
                    announce, color = fg, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )
                val why = whyText
                if (why != null) Text(why, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            val controls: @Composable () -> Unit = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { showHint() }, enabled = settled && !over, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gem_cascade_hint)) }
                    OutlinedButton(onClick = { if (paused) game.resume() else game.pause() }, enabled = !over, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (paused) R.string.gem_cascade_resume else R.string.gem_cascade_pause))
                    }
                    for (b in BoosterKind.values()) {
                        val left = sv.boosters[b] ?: 0
                        val on = armed == b
                        FilterChip(
                            selected = on, enabled = settled && !over && left > 0,
                            onClick = {
                                when (b) {
                                    BoosterKind.HAMMER, BoosterKind.COLOUR_BOMB, BoosterKind.SWAP_ANY -> { armed = if (on) null else b; armedFirst = null; anim.selected = null; poke() }
                                    else -> runBooster(b, null)
                                }
                            },
                            label = { Text(stringResource(boosterNameRes(b)) + " " + left) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    if (twist) {
                        OutlinedButton(onClick = { clockwise = !clockwise }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (clockwise) R.string.gem_cascade_turn_cw else R.string.gem_cascade_turn_ccw))
                        }
                    }
                    OutlinedButton(onClick = { switchMode = !switchMode }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (switchMode) R.string.gem_cascade_switch_off else R.string.gem_cascade_switch_on))
                    }
                }
                if (switchMode) {
                    Button(onClick = { scanSelect() }, enabled = settled && !over, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        Text(stringResource(R.string.gem_cascade_scan_select))
                    }
                }
                val armedName = armed?.let { stringResource(boosterNameRes(it)) }
                if (armedName != null) Text(stringResource(R.string.gem_cascade_booster_pick, armedName), color = fg)
            }

            val boardArea: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod) {
                    val minCell = with(density) { 48.dp.toPx() }
                    val fit = fitBoard(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), sv.cols, sv.rows, minCellPx = minCell, maxCellPx = with(density) { 80.dp.toPx() })
                    val cellPx = fit.cellPx
                    val cellDp = with(density) { cellPx.toDp() }
                    Box(
                        Modifier.align(Alignment.Center)
                            .size(with(density) { fit.boardWidthPx.toDp() }, with(density) { fit.boardHeightPx.toDp() })
                    ) {
                        val boardDesc = stringResource(R.string.gem_cascade_board_desc, sv.rows, sv.cols)
                        Canvas(
                            Modifier.fillMaxSize()
                                .semantics { contentDescription = boardDesc }
                                .pointerInput(sv.rows, sv.cols, cellPx) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val start = GemInput.cellAt(down.position.x, down.position.y, cellPx, sv.rows, sv.cols)
                                        var dragged = false
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (ch.changedToUp()) {
                                                if (!dragged && start != null) tapState.value(start)
                                                break
                                            }
                                            if (!dragged && start != null) {
                                                val d = ch.position - down.position
                                                val target = GemInput.dragTarget(start, d.x, d.y, cellPx, sv.rows, sv.cols)
                                                if (target != null) { dragged = true; dragState.value(start, target) }
                                            }
                                            if (dragged) ch.consume()
                                        }
                                    }
                                }
                        ) {
                            frame.intValue // draw-scope read: invalidates the canvas only
                            val tv = t.value
                            val fv = floatT.value
                            val s = cellPx
                            drawRoundRect(bgBoard, Offset.Zero, Size(size.width, size.height), CornerRadius(s * 0.15f))
                            val b = anim.board
                            if (b.rows != sv.rows || b.cols != sv.cols) return@Canvas
                            for (r in 0 until b.rows) for (c in 0 until b.cols) {
                                drawRect(if ((r + c) % 2 == 0) tileA else tileB, Offset(c * s, r * s), Size(s, s))
                            }
                            for (r in 0 until b.rows) for (c in 0 until b.cols) drawIce(Offset(c * s, r * s), s, b.iceAt(Pos(r, c)), paints)
                            for (r in 0 until b.rows) for (c in 0 until b.cols) {
                                val p = Pos(r, c)
                                val cell = b[p]
                                if (cell.type == PieceType.EMPTY) continue
                                val o = anim.offsets[p]
                                val cx = (c + 0.5f + (o?.x ?: 0f) * (1f - tv)) * s
                                val cy = (r + 0.5f + (o?.y ?: 0f) * (1f - tv)) * s
                                var scale = 1f
                                var alpha = 1f
                                if (p in anim.fading) { alpha = 1f - tv; scale = if (reduced) 1f else 1f - 0.4f * tv }
                                if (p in anim.pop) scale = 1f + 0.35f * sin(PI.toFloat() * tv)
                                drawPiece(cell, Offset(cx, cy), s, scale, alpha, paints, textMeasurer)
                            }
                            for (p in anim.glow) drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(p.c * s + s * 0.06f, p.r * s + s * 0.06f), Size(s * 0.88f, s * 0.88f), CornerRadius(s * 0.2f))
                            for (p in anim.rings) drawCircle(accent.copy(alpha = 0.55f * (1f - tv)), s * (0.3f + 0.4f * tv), Offset((p.c + 0.5f) * s, (p.r + 0.5f) * s), style = Stroke(s * 0.08f))
                            for (p in anim.hint) drawRoundRect(accent, Offset(p.c * s + s * 0.05f, p.r * s + s * 0.05f), Size(s * 0.9f, s * 0.9f), CornerRadius(s * 0.2f), style = Stroke(s * 0.08f))
                            anim.selected?.let { p -> drawRoundRect(fg, Offset(p.c * s + s * 0.04f, p.r * s + s * 0.04f), Size(s * 0.92f, s * 0.92f), CornerRadius(s * 0.2f), style = Stroke(s * 0.07f)) }
                            anim.scan?.let { p ->
                                val w = if (twist) 2f else 1f
                                drawRoundRect(accent, Offset(p.c * s + s * 0.03f, p.r * s + s * 0.03f), Size(s * w * 0.94f, s * w * 0.94f), CornerRadius(s * 0.2f), style = Stroke(s * 0.1f))
                            }
                            val fa = if (fv >= 1f) 0f else (1f - fv)
                            if (fa > 0f) {
                                anim.scoreText?.let {
                                    val lay = textMeasurer.measure(it, TextStyle(color = fg.copy(alpha = fa), fontSize = (s * 0.34f).toSp(), fontWeight = FontWeight.Bold))
                                    drawText(lay, topLeft = Offset(anim.textAt.x * s - lay.size.width / 2f, (anim.textAt.y - 0.5f * fv) * s))
                                }
                                anim.chainText?.let {
                                    val lay = textMeasurer.measure(it, TextStyle(color = accent.copy(alpha = fa), fontSize = (s * 0.5f).toSp(), fontWeight = FontWeight.ExtraBold))
                                    drawText(lay, topLeft = Offset(size.width / 2f - lay.size.width / 2f, size.height * 0.4f - 0.3f * fv * s))
                                }
                            }
                        }
                        // one labelled button per cell for TalkBack; no pointer handling here (the canvas does the touching)
                        val sb = settledBoard
                        if (sb != null && sb.rows == sv.rows && sb.cols == sv.cols) {
                            Column(Modifier.fillMaxSize()) {
                                for (r in 0 until sv.rows) Row(Modifier.fillMaxWidth()) {
                                    for (c in 0 until sv.cols) {
                                        val p = Pos(r, c)
                                        val isSel = (selCell ?: armedFirst) == p
                                        val desc = describeCell(androidContext, sb, p, isSel)
                                        val label = stringResource(R.string.gem_cascade_select_action)
                                        Box(
                                            Modifier.size(cellDp).semantics {
                                                contentDescription = desc
                                                role = Role.Button
                                                selected = isSel
                                                onClick(label) { handleTap(p); true }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (paused && !over) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.gem_cascade_paused), color = fg, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { game.resume() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gem_cascade_resume)) }
                                }
                            }
                        }
                        if (over) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                                val won = sv.status == GemStatus.WON
                                val title = if (won) stringResource(R.string.gem_cascade_won) else overText(androidContext, sv.overReason)
                                Column(
                                    Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(title, color = fg, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    if (won && sv.mode == GemMode.QUEST) Text(stringResource(R.string.gem_cascade_stars, sv.stars), color = fg)
                                    Text(stringResource(R.string.gem_cascade_final_score, sv.score), color = fg)
                                    Button(onClick = { game.playAgain() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(stringResource(if (won) R.string.gem_cascade_play_again else R.string.gem_cascade_try_again))
                                    }
                                    if (won && sv.mode == GemMode.QUEST && !daily) {
                                        Button(onClick = { game.nextLevel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gem_cascade_next_level)) }
                                    }
                                    OutlinedButton(onClick = { game.finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gem_cascade_leave)) }
                                }
                            }
                        }
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    boardArea(Modifier.weight(1f).fillMaxSize())
                    Column(Modifier.widthIn(min = 200.dp, max = 320.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        hud(); caption(); controls()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hud()
                    boardArea(Modifier.weight(1f).fillMaxWidth())
                    caption()
                    controls()
                }
            }
        }
    }
}

/** Identity colours (kept in step with GemCascadeHubSpecs). */
private object GemCascadeAccent {
    const val LIGHT: Long = com.gamesuite.games.gemcascade.GemCascadeHubSpecs.ACCENT_LIGHT
    const val DARK: Long = com.gamesuite.games.gemcascade.GemCascadeHubSpecs.ACCENT_DARK
}
