package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.trailtogether.LanternMode
import com.gamesuite.games.trailtogether.TrailBand
import com.gamesuite.games.trailtogether.TrailCard
import com.gamesuite.games.trailtogether.TrailCardKind
import com.gamesuite.games.trailtogether.TrailColour
import com.gamesuite.games.trailtogether.TrailEventCode
import com.gamesuite.games.trailtogether.TrailHandicap
import com.gamesuite.games.trailtogether.TrailHint
import com.gamesuite.games.trailtogether.TrailMoveResult
import com.gamesuite.games.trailtogether.TrailPhase
import com.gamesuite.games.trailtogether.TrailRules
import com.gamesuite.games.trailtogether.TrailState
import com.gamesuite.games.trailtogether.TrailTarget
import com.gamesuite.games.trailtogether.TrailTogetherGame
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TT_COACH_ID = "trail-together"
private const val TT_PREFS = "trail_together_prefs"
private const val TT_TUTORIAL_SEEN = "tutorial_seen"

private val ttColourNames = mapOf(
    TrailColour.BERRY to R.string.trail_together_colour_berry, TrailColour.SUN to R.string.trail_together_colour_sun,
    TrailColour.LEAF to R.string.trail_together_colour_leaf, TrailColour.SKY to R.string.trail_together_colour_sky,
    TrailColour.PLUM to R.string.trail_together_colour_plum, TrailColour.PEACH to R.string.trail_together_colour_peach
)
private val ttShapeNames = mapOf(
    "circle" to R.string.trail_together_shape_circle, "triangle" to R.string.trail_together_shape_triangle,
    "square" to R.string.trail_together_shape_square, "diamond" to R.string.trail_together_shape_diamond,
    "star" to R.string.trail_together_shape_star, "hexagon" to R.string.trail_together_shape_hexagon
)
private val ttPalNames = listOf(
    R.string.trail_together_pal_name_1, R.string.trail_together_pal_name_2, R.string.trail_together_pal_name_3, R.string.trail_together_pal_name_4
)
private val ttTutorial = listOf(
    R.string.trail_together_tutorial_1, R.string.trail_together_tutorial_2, R.string.trail_together_tutorial_3, R.string.trail_together_tutorial_4,
    R.string.trail_together_tutorial_5, R.string.trail_together_tutorial_6, R.string.trail_together_tutorial_7, R.string.trail_together_tutorial_8
)

private fun Context.ttColour(c: TrailColour): String = getString(ttColourNames.getValue(c))
private fun Context.ttColourShape(c: TrailColour): String = ttColour(c) + " " + getString(ttShapeNames.getValue(c.shape))

private fun Context.ttCardDesc(card: TrailCard): String = when (card.kind) {
    TrailCardKind.STEP -> getString(R.string.trail_together_card_hop_desc, ttColourShape(card.colour ?: TrailColour.BERRY))
    TrailCardKind.STONE -> getString(R.string.trail_together_card_stone_desc, ttColourShape(card.colour ?: TrailColour.BERRY))
    else -> getString(R.string.trail_together_card_lantern_desc)
}

private fun Context.ttTutorialSeen(): Boolean = try {
    getSharedPreferences(TT_PREFS, Context.MODE_PRIVATE).getBoolean(TT_TUTORIAL_SEEN, false)
} catch (_: Exception) {
    true
}

private fun Context.ttMarkTutorialSeen() {
    try { getSharedPreferences(TT_PREFS, Context.MODE_PRIVATE).edit().putBoolean(TT_TUTORIAL_SEEN, true).apply() } catch (_: Exception) { }
}

/** The pal on the move: which pal, from and to which spot. The drawing reads its progress from an [Animatable]. */
private class TrailLeg(val pal: Int, val from: Int, val to: Int)

/** What the person on turn has picked so far this turn (it resets whenever the turn changes). */
private data class TrailPick(
    val card: Int = -1,
    val lantern: LanternMode? = null,
    val swap: Boolean = false,
    val hint: TrailHint? = null,
    val guidance: Int = 0
)

/**
 * Trail Together on one screen: the cooperative hedgehog trail game for 1 to 4 players, helper pals in empty seats.
 * Warm cream background, a winding trail drawn in code, open hands at the bottom (the far seats' hands at the top, turned
 * around, when "Turn the far hands around" is on), hedgehog pals marked by a badge of colour AND shape, and a Fog bank that
 * creeps in over the trail as Fog cards are drawn.
 *
 * Lessons from the earlier kids games' audits are built in: animated values are only read inside draw and graphicsLayer
 * lambdas; a second tap in the same input batch is refused by the game itself ([TrailTogetherGame.holdInputUntilSettled]);
 * every result is worded, announced and the result card shown only after its animation has finished (the screen shows a
 * `shown` state that trails the engine's state by one animation); the help, leave and coach dialogs, the tutorial and
 * losing window focus pause the helper pals and the animation; the status text lives in two small live regions that
 * change only when their own text does.
 *
 * Accessibility: touch targets are 56 dp or more (cards 72 x 96 at the default font size), every card, pal, stone and the fog
 * track has a description, and nothing needs a drag (tap a card, tap a pal). Reduced Motion: pals jump at once with a
 * short highlight, no confetti, the fog moves at once. Game speed scales every pause.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrailTogetherScreen(
    sessionManager: GameSessionManager,
    game: TrailTogetherGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.MANCALA, enabled = musicEnabled)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val gameSpeedNow by rememberUpdatedState(gameSpeed)
    val reducedNow by rememberUpdatedState(reduced)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val theme = remember(dark) { trailTheme(dark) }
    val burst = rememberParticleBurst()
    var hintsMode by remember { mutableStateOf("auto") }
    var rotate by remember { mutableStateOf(true) }
    var showTutorial by remember { mutableStateOf(false) }
    val initDone = remember { arrayOfNulls<Any>(1) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        if (initDone[0] === ctx) return@LaunchedEffect
        initDone[0] = ctx
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.band = TrailBand.fromId(extras["band"])
        game.handicap = TrailHandicap.fromExtras(extras, ctx.players.size)
        hintsMode = extras["hints"] ?: "auto"
        rotate = extras["rotate"] != "false"
        // a tap that lands before this screen has recomposed is refused by the game itself (see settled() below)
        game.holdInputUntilSettled = true
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // ---- animation: the screen shows `shown`, which trails the engine's state by one animation -----------------------
    var shown by remember { mutableStateOf<TrailState?>(null) }
    val legT = remember { Animatable(0f) }
    var leg by remember { mutableStateOf<TrailLeg?>(null) }
    val fogShown = remember { Animatable(0f) }
    val popT = remember { Animatable(1f) }
    var popIndex by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }

    suspend fun awaitUnpaused() {
        if (game.paused.value) snapshotFlow { game.paused.value }.first { !it }
    }

    LaunchedEffect(game) {
        snapshotFlow { game.state.value }.collectLatest { cur ->
            if (cur == null) {
                shown = null
                return@collectLatest
            }
            val prev = shown
            val act = cur.action
            if (prev == null || act == null || prev.seed != cur.seed || act.serial == prev.action?.serial) {
                shown = cur
                leg = null
                popIndex = -1
                fogShown.snapTo(cur.fog.toFloat())
                game.settled()
                return@collectLatest
            }
            busy = true
            var completed = false
            try {
                awaitUnpaused()
                val speed = gameSpeedNow
                val calm = reducedNow
                when (act.kind) {
                    TrailEventCode.HOP -> {
                        leg = TrailLeg(act.palIndex, act.from, act.to)
                        if (calm) {
                            legT.snapTo(1f)
                            shown = prev.copy(pals = cur.pals)
                            delay(150)
                        } else {
                            legT.snapTo(0f)
                            playSfx(SfxKind.LIGHT_TICK)
                            val steps = abs(act.to - act.from)
                            legT.animateTo(1f, tween(speed.scale((260L + 70L * steps).coerceAtMost(900L)).toInt().coerceAtLeast(120), easing = FastOutSlowInEasing))
                            shown = prev.copy(pals = cur.pals)
                        }
                        playSfx(SfxKind.SOLID_THUNK)
                        haptics(HapticSignal.NORMAL_ACTION)
                        leg = null
                    }
                    TrailEventCode.STONE -> {
                        popIndex = act.spotIndex
                        shown = prev.copy(spots = cur.spots, trail = cur.trail, burrowOpen = cur.burrowOpen)
                        if (calm) {
                            popT.snapTo(1f)
                            delay(150)
                        } else {
                            popT.snapTo(0f)
                            playSfx(SfxKind.SUCCESS_CHIME)
                            popT.animateTo(1f, tween(speed.scale(380L).toInt().coerceAtLeast(120), easing = FastOutSlowInEasing))
                        }
                        haptics(HapticSignal.LIGHT_TICK)
                        if (act.burrowOpened) haptics(HapticSignal.STRONG_ACTION)
                        popIndex = -1
                    }
                    else -> {
                        delay(speed.scale(260L))
                        playSfx(SfxKind.LIGHT_TICK)
                    }
                }
                if (act.fogSteps > 0) {
                    awaitUnpaused()
                    if (calm) {
                        fogShown.snapTo(cur.fog.toFloat())
                    } else {
                        haptics(HapticSignal.LIGHT_TICK)
                        fogShown.animateTo(cur.fog.toFloat(), tween(speed.scale(600L).toInt().coerceAtLeast(150), easing = LinearEasing))
                    }
                }
                completed = true
            } finally {
                withContext(NonCancellable) {
                    shown = cur
                    leg = null
                    popIndex = -1
                    fogShown.snapTo(cur.fog.toFloat())
                    busy = false
                    game.settled()
                }
            }
            if (completed) {
                if (cur.phase == TrailPhase.WON) {
                    playSfx(SfxKind.SUCCESS_CHIME)
                    haptics(HapticSignal.CELEBRATION)
                    if (!reducedNow) {
                        burst.spawn(Offset(0.5f, 0.4f), 90, listOf(Color(0xFFFFD866), Color.White, TrailPalColours[0], TrailPalColours[1]), speedRange = 0.25f..0.95f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
                    }
                } else if (cur.phase == TrailPhase.LOST) {
                    playSfx(SfxKind.LIGHT_TICK)
                    haptics(HapticSignal.LIGHT_TICK)
                }
            }
        }
    }

    val helpTitle = stringResource(R.string.trail_together_help_title)
    val helpText = stringResource(R.string.trail_together_help_text)
    val players = context?.players ?: emptyList()
    val live = game.state.value
    val v = shown
    if (v == null || live == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
            onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
            buttonFill = theme.bg, buttonContent = theme.ink
        ) {
            Box(Modifier.fillMaxSize().background(theme.bg))
        }
        return
    }

    // an animation is owed (the engine moved on) or running; read in composition on purpose: it changes only when an action starts or ends
    val animating = busy || live.seed != v.seed || (live.action?.serial ?: -1) != (v.action?.serial ?: -1)
    val seatCount = v.hands.size
    fun seatName(i: Int): String {
        val p = players.getOrNull(i)
        return if (p == null) "" else if (p.isBot) androidContext.getString(ttPalNames[i % 4]) + " " + androidContext.getString(R.string.trail_together_seat_helper_tag) else androidContext.seatLabel(p.displayName)
    }
    fun isBotSeat(i: Int) = players.getOrNull(i)?.isBot == true
    val humans = players.count { !it.isBot }
    val farHands = rotate && humans >= 2 && seatCount >= 2
    val personTurn = !v.over && !animating && !isBotSeat(v.currentSeat)
    val helperTurn = !v.over && isBotSeat(v.currentSeat)
    val glow = when (hintsMode) { "on" -> true; "off" -> false; else -> v.band == TrailBand.LITTLE }

    var pick by remember(v.seed, v.plays) { mutableStateOf(TrailPick()) }
    var hintShown by remember(v.seed, v.plays) { mutableStateOf<TrailHint?>(null) }

    // first run: the picture guide
    LaunchedEffect(v.seed) {
        if (v.plays == 0 && !androidContext.ttTutorialSeen()) showTutorial = true
    }

    // the helper pal plays after a short pause that follows the speed setting; it waits while paused or animating
    val gamePaused = game.paused.value
    LaunchedEffect(v.seed, v.plays, v.currentSeat, animating, gamePaused, v.phase) {
        if (animating || gamePaused || v.over || !isBotSeat(v.currentSeat)) return@LaunchedEffect
        delay(gameSpeed.scale(if (reduced) 600L else 1200L))
        awaitUnpaused()
        game.helperStep()
    }

    // the pulse is a State read only inside draw / graphicsLayer lambdas, so it never recomposes the screen
    val pulse: State<Float> = if (reduced) remember { mutableStateOf(0.7f) } else rememberInfiniteTransition(label = "trail-pulse").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "trail-pulse-a"
    )
    val drift: State<Float> = if (reduced) remember { mutableStateOf(0f) } else rememberInfiniteTransition(label = "trail-drift").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "trail-drift-a"
    )

    // ---- what a person can do -------------------------------------------------------------------------------------
    val seat = v.currentSeat
    val hand = v.hands.getOrElse(seat) { emptyList() }
    fun feedbackRefused() {
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }
    fun handle(r: TrailMoveResult) {
        when (r) {
            TrailMoveResult.OK, TrailMoveResult.BUSY -> {}
            else -> feedbackRefused()
        }
    }
    fun palLegal(p: Int): Boolean {
        val pal = v.pals.getOrNull(p) ?: return false
        if (pal.position >= v.length) return false
        val card = hand.getOrNull(pick.card) ?: return false
        return when {
            card.kind == TrailCardKind.STEP && card.colour != null -> v.hopDestination(pal.position, card.colour) >= 0
            card.kind == TrailCardKind.LANTERN && pick.lantern == LanternMode.HOP -> v.stepDestination(pal.position) >= 0
            else -> false
        }
    }
    val pickingPal = personTurn && pick.card >= 0 && (
        hand.getOrNull(pick.card)?.kind == TrailCardKind.STEP || pick.lantern == LanternMode.HOP
        )
    val legalPals: Set<Int> = if (pickingPal) v.pals.indices.filter { palLegal(it) }.toSet() else emptySet()
    val canLanternHop = v.pals.any { it.position < v.length && v.stepDestination(it.position) >= 0 }

    fun tapCard(i: Int) {
        if (!personTurn) return
        val card = hand.getOrNull(i) ?: return
        if (pick.swap) {
            handle(game.swap(seat, i))
            return
        }
        when (card.kind) {
            TrailCardKind.STONE -> {
                if (v.burrowOpen) {
                    pick = pick.copy(guidance = R.string.trail_together_status_burrow_open)
                    feedbackRefused()
                } else {
                    handle(game.playCard(seat, i, TrailTarget()))
                }
            }
            TrailCardKind.STEP -> {
                val c = card.colour
                val any = c != null && v.pals.any { it.position < v.length && v.hopDestination(it.position, c) >= 0 }
                if (!any) {
                    pick = TrailPick(guidance = R.string.trail_together_status_no_hop)
                    feedbackRefused()
                } else {
                    pick = if (pick.card == i) TrailPick() else TrailPick(card = i, guidance = R.string.trail_together_status_pick_pal)
                    playSfx(SfxKind.LIGHT_TICK)
                }
            }
            TrailCardKind.LANTERN -> {
                pick = if (pick.card == i) TrailPick() else TrailPick(card = i, guidance = R.string.trail_together_status_lantern)
                playSfx(SfxKind.LIGHT_TICK)
            }
            TrailCardKind.FOG -> {}
        }
    }

    fun tapPal(p: Int) {
        if (!personTurn || p !in legalPals) return
        val card = hand.getOrNull(pick.card) ?: return
        val r = if (card.kind == TrailCardKind.LANTERN) {
            game.playCard(seat, pick.card, TrailTarget(palIndex = p, lanternMode = LanternMode.HOP))
        } else {
            game.playCard(seat, pick.card, TrailTarget(palIndex = p))
        }
        handle(r)
    }

    fun tapColour(c: TrailColour) {
        if (!personTurn || pick.card < 0) return
        handle(game.playCard(seat, pick.card, TrailTarget(colour = c, lanternMode = LanternMode.STONE)))
    }

    fun tapMode(m: LanternMode) {
        if (!personTurn || pick.card < 0) return
        pick = pick.copy(
            lantern = m,
            guidance = if (m == LanternMode.STONE) R.string.trail_together_status_pick_colour else R.string.trail_together_status_pick_pal
        )
        playSfx(SfxKind.LIGHT_TICK)
    }

    fun tapSwap() {
        if (!personTurn) return
        pick = if (pick.swap) TrailPick() else TrailPick(swap = true, guidance = R.string.trail_together_status_swap)
        playSfx(SfxKind.LIGHT_TICK)
    }

    fun tapHint() {
        if (!personTurn) return
        val h = game.hint() ?: return
        hintShown = h
        pick = TrailPick(guidance = if (h.isSwap) R.string.trail_together_status_hint_swap else R.string.trail_together_status_hint)
        playSfx(SfxKind.LIGHT_TICK)
    }

    fun tapBack() {
        pick = TrailPick()
    }

    // the glow follows the hint rule; it is recomputed only when the turn changes (cheap: at most 4 cards and 4 pals)
    val glowHint: TrailHint? = remember(v.seed, v.plays, glow, personTurn) {
        if (glow && personTurn) game.hint() else null
    }
    val suggested: TrailHint? = hintShown ?: glowHint

    // ---- words --------------------------------------------------------------------------------------------------
    // Everything below is worded from [v], the state the board has finished animating, so a result is spoken (and the result
    // card shown) only once the pieces have landed.
    val act = v.action
    val annText = run {
        if (act == null) return@run ""
        val who = seatName(act.seat)
        val yourName = players.getOrNull(act.seat)?.displayName ?: ""
        val you = androidContext.isYouName(yourName)
        val base = when (act.kind) {
            TrailEventCode.HOP -> {
                val home = act.to >= v.length
                val col = v.trail.getOrNull(act.to)
                when {
                    home && you -> androidContext.getString(R.string.trail_together_ann_hop_home_you)
                    home -> androidContext.getString(R.string.trail_together_ann_hop_home, who)
                    col == null -> ""
                    you -> androidContext.getString(R.string.trail_together_ann_hop_you, androidContext.ttColourShape(col))
                    else -> androidContext.getString(R.string.trail_together_ann_hop, who, androidContext.ttColourShape(col))
                }
            }
            TrailEventCode.STONE -> {
                val c = act.colour
                when {
                    c == null -> ""
                    you -> androidContext.getString(R.string.trail_together_ann_stone_you, androidContext.ttColourShape(c))
                    else -> androidContext.getString(R.string.trail_together_ann_stone, who, androidContext.ttColourShape(c))
                }
            }
            else -> if (you) androidContext.getString(R.string.trail_together_ann_swap_you) else androidContext.getString(R.string.trail_together_ann_swap, who)
        }
        val extra = buildList {
            if (act.burrowOpened) add(androidContext.getString(R.string.trail_together_status_burrow_open))
            if (act.fogSteps > 0 && !act.lost) add(androidContext.getString(if (v.fog >= v.fogLength - 2) R.string.trail_together_status_fog_near else R.string.trail_together_status_fog_rolls))
            if (act.won) add(androidContext.getString(R.string.trail_together_status_win))
            if (act.lost) add(androidContext.getString(R.string.trail_together_status_lose))
        }
        (listOf(base) + extra).filter { it.isNotBlank() }.joinToString(" ")
    }
    val currentName = seatName(seat)
    val promptText: String = when {
        v.over || animating -> ""
        helperTurn -> ""
        pick.guidance != 0 -> stringResource(pick.guidance)
        else -> stringResource(R.string.trail_together_status_your_turn, currentName)
    }
    val quietText = if (helperTurn && !animating) stringResource(R.string.trail_together_status_helper_turn, currentName) else ""
    val readyText = if (act == null && v.plays == 0 && !v.over) stringResource(R.string.trail_together_status_ready) else ""

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !v.over,
        onLeave = game::leaveSession,
        onAbort = { if (live.tries > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = live.tries == 0,
        buttonFill = theme.bg, buttonContent = theme.ink
    ) {
        val (coachShown, openCoach) = rememberClCoach(TT_COACH_ID, ready = true)
        // The help / leave dialogs and the corner menu take window focus (a dialog does not pause the Activity), as does the
        // notification shade; the coach card and the picture guide have no window of their own. Helpers and hops wait.
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, coachShown, showTutorial) {
            if (!windowFocused || coachShown || showTutorial) game.pause() else game.resume()
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton(TT_COACH_ID, openCoach)
                    TrailGuideButton(theme) { showTutorial = true }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (annText.isNotBlank()) annText else readyText, color = theme.ink, textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Text(
                            promptText, color = theme.ink, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        if (quietText.isNotBlank()) {
                            Text(quietText, color = theme.soft, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                TrailChips(v, ::seatName, seatCount, theme, androidContext)
                TrailFogTrack(v.fog, v.fogLength, fogShown, theme, androidContext)
            }

            val board: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    val params = TrailRules.band(v.band)
                    val rows = TrailGeometry.rows(v.length + 1, params.columns)
                    val dens = LocalDensity.current
                    val fit = fitBoard(
                        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), params.columns, rows,
                        maxCellPx = with(dens) { 84.dp.toPx() }
                    )
                    TrailBoardView(
                        v = v, columns = params.columns, rows = rows, cellPx = fit.cellPx,
                        legProvider = { leg }, legT = legT, fogShown = fogShown, drift = drift, popT = popT, popIndex = { popIndex },
                        pulse = pulse, theme = theme, glowFrontier = personTurn && hand.getOrNull(pick.card)?.kind == TrailCardKind.STONE,
                        legalPals = legalPals, suggestedPal = if (personTurn) suggested?.target?.palIndex ?: -1 else -1,
                        ownerName = { p -> seatName(v.pals[p].ownerSeat) },
                        ownerIsYou = { p -> androidContext.isYouName(players.getOrNull(v.pals[p].ownerSeat)?.displayName ?: "") },
                        onPalTap = ::tapPal, ctx = androidContext
                    )
                }
            }

            val strip: @Composable (Int, Boolean) -> Unit = { s, rotated ->
                TrailHandStrip(
                    v = v, seat = s, name = seatName(s), current = s == seat && !v.over, personSeat = !isBotSeat(s),
                    interactive = s == seat && personTurn, rotated = rotated,
                    pick = pick, suggested = suggested, pulse = pulse, theme = theme, ctx = androidContext,
                    canLanternHop = canLanternHop, hasHopTargets = { c -> v.pals.any { it.position < v.length && v.hopDestination(it.position, c) >= 0 } },
                    onCard = ::tapCard, onSwap = ::tapSwap, onHint = ::tapHint, onBack = ::tapBack,
                    onMode = ::tapMode, onColour = ::tapColour
                )
            }

            // which seat's strip sits where: with the far hands turned around, odd seats read from the top edge
            val nearSeat = if (!farHands || seat % 2 == 0) seat else 0
            val farSeatShown = if (seat % 2 == 1) seat else 1

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    board(Modifier.weight(0.56f).fillMaxSize())
                    Column(Modifier.weight(0.44f).fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        header()
                        Spacer(Modifier.height(6.dp))
                        strip(seat, false)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    if (farHands) strip(farSeatShown, true)
                    board(Modifier.weight(1f, fill = true).fillMaxWidth())
                    strip(nearSeat, false)
                }
            }

            // the result card, only once everything has landed
            if (v.over && !animating) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                    TrailResultCard(v, theme, androidContext, onAgain = { game.playAgain() }, onFinish = { game.leaveSession() })
                }
            }
            Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }

            if (showTutorial) {
                TrailTutorial(theme) {
                    androidContext.ttMarkTutorialSeen()
                    showTutorial = false
                }
            }
        }
    }
}

// ---- header pieces ------------------------------------------------------------------------------------------------

@Composable
private fun TrailGuideButton(theme: TrailTheme, onClick: () -> Unit) {
    val desc = stringResource(R.string.trail_together_tutorial_open)
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(32.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(theme.accent.copy(alpha = 0.18f), size.minDimension / 2, c)
            drawCircle(theme.accent, size.minDimension / 2, c, style = Stroke(2f * density))
            drawLantern(c, size.minDimension * 0.62f, theme, density)
        }
    }
}

/** Every seat with its pal badge, its name and its open hand as tiny cards: the whole table at a glance. */
@Composable
private fun TrailChips(v: TrailState, name: (Int) -> String, seats: Int, theme: TrailTheme, ctx: Context) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until seats) {
            val isTurn = i == v.currentSeat && !v.over
            val desc = ctx.getString(R.string.trail_together_hand_desc, name(i), v.hands[i].size)
            val onTurn = stringResource(R.string.trail_together_seat_on_turn)
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (isTurn) TrailPalColours[i % 4].copy(alpha = 0.2f) else Color.Transparent)
                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) TrailPalColours[i % 4] else theme.soft.copy(alpha = 0.35f), shape)
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = desc
                        if (isTurn) stateDescription = onTurn
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrailSeatBadge(i, 18.dp, theme)
                    Text(
                        name(i), Modifier.padding(start = 3.dp).clearAndSetSemantics { }, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelSmall
                    )
                }
                Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                    val cards = v.hands[i]
                    val w = 12.dp.toPx()
                    val gap = 3.dp.toPx()
                    val total = cards.size * w + (cards.size - 1).coerceAtLeast(0) * gap
                    var x = (size.width - total) / 2f
                    val scratch = Path()
                    for (card in cards) {
                        drawRoundRect(theme.surface, Offset(x, 1.dp.toPx()), Size(w, size.height - 2.dp.toPx()), CornerRadius(2.dp.toPx()))
                        drawRoundRect(theme.ink.copy(alpha = 0.45f), Offset(x, 1.dp.toPx()), Size(w, size.height - 2.dp.toPx()), CornerRadius(2.dp.toPx()), style = Stroke(1f))
                        val c = Offset(x + w / 2, size.height / 2)
                        val col = card.colour
                        if (col != null) drawTrailShape(col.shape, c, w * 0.34f, col.tint(), theme.ink, 0.8f, scratch)
                        else drawCircle(Color(0xFFFFC93C), w * 0.3f, c)
                        x += w + gap
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailSeatBadge(seat: Int, size: Dp, theme: TrailTheme) {
    Canvas(Modifier.size(size)) {
        drawTrailShape(
            TrailPalShapes[seat % 4], Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension * 0.4f,
            TrailPalColours[seat % 4], theme.ink, 1.2f * density, Path()
        )
    }
}

/** The Fog track: a row of steps that fill as Fog cards come up, with a cloud riding at the front. Read-only; spoken as one line. */
@Composable
private fun TrailFogTrack(fog: Int, length: Int, fogShown: Animatable<Float, *>, theme: TrailTheme, ctx: Context) {
    val desc = ctx.getString(R.string.trail_together_fog_desc, fog, length)
    Canvas(
        Modifier.fillMaxWidth().height(34.dp).padding(vertical = 2.dp).semantics { contentDescription = desc }
    ) {
        val n = length.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val cellW = (size.width - gap * (n - 1)) / n
        val h = size.height
        val progress = fogShown.value
        for (i in 0 until n) {
            val x = i * (cellW + gap)
            val filled = (progress - i).coerceIn(0f, 1f)
            drawRoundRect(theme.soft.copy(alpha = 0.22f), Offset(x, h * 0.3f), Size(cellW, h * 0.5f), CornerRadius(6.dp.toPx()))
            if (filled > 0f) drawRoundRect(theme.fogBack, Offset(x, h * 0.3f), Size(cellW * filled, h * 0.5f), CornerRadius(6.dp.toPx()))
            drawRoundRect(theme.soft.copy(alpha = 0.5f), Offset(x, h * 0.3f), Size(cellW, h * 0.5f), CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
        val cx = (progress.coerceIn(0f, n.toFloat()) / n) * size.width
        drawCloud(Offset(cx.coerceIn(h * 0.5f, size.width - h * 0.5f), h * 0.45f), h * 0.42f, theme.fogVeil, theme.ink, density)
    }
}

// ---- the board ---------------------------------------------------------------------------------------------------

@Composable
private fun TrailBoardView(
    v: TrailState, columns: Int, rows: Int, cellPx: Float,
    legProvider: () -> TrailLeg?, legT: Animatable<Float, *>, fogShown: Animatable<Float, *>, drift: State<Float>,
    popT: Animatable<Float, *>, popIndex: () -> Int, pulse: State<Float>, theme: TrailTheme, glowFrontier: Boolean,
    legalPals: Set<Int>, suggestedPal: Int, ownerName: (Int) -> String, ownerIsYou: (Int) -> Boolean,
    onPalTap: (Int) -> Unit, ctx: Context
) {
    val dn = LocalDensity.current
    val wDp = with(dn) { (cellPx * columns).toDp() }
    val hDp = with(dn) { (cellPx * rows).toDp() }
    val scratch = remember { TrailScratch() }
    val palSize = cellPx * 0.74f
    // pals sharing a spot are fanned out a little; the offsets only change when a pal lands
    val offsets = remember(v.pals) {
        val seen = HashMap<Int, Int>()
        val counts = v.pals.groupingBy { it.position }.eachCount()
        v.pals.map { pal ->
            val k = seen.getOrDefault(pal.position, 0)
            seen[pal.position] = k + 1
            val n = counts.getValue(pal.position)
            if (n == 1) Offset.Zero else Offset((k - (n - 1) / 2f) * 0.2f, (k % 2) * 0.1f - 0.05f)
        }
    }
    val trailDesc = ctx.getString(R.string.trail_together_trail_desc, v.frontier, v.length)
    Box(Modifier.size(wDp, hDp).semantics { contentDescription = trailDesc }) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / columns
            val dens = density
            val fogLevel = if (v.fogLength > 0) fogShown.value / v.fogLength else 0f
            drawRoundRect(theme.path.copy(alpha = 0.3f), Offset.Zero, size, CornerRadius(cell * 0.3f))
            drawFogBank(fogLevel, drift.value, theme, front = false)
            // the winding trail
            val p = scratch.a
            p.rewind()
            for (i in 0..v.length) {
                val c = TrailGeometry.centre(i, columns, rows, cell)
                if (i == 0) p.moveTo(c.x, c.y) else p.lineTo(c.x, c.y)
            }
            drawPath(p, theme.path, style = Stroke(cell * 0.34f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(p, theme.ink.copy(alpha = 0.12f), style = Stroke(cell * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val stoneR = cell * 0.31f
            val pop = popIndex()
            for (i in 0..v.length) {
                val c = TrailGeometry.centre(i, columns, rows, cell)
                when {
                    i == v.length -> drawBurrow(c, stoneR * 1.15f, v.burrowOpen, theme, dens, scratch.b)
                    i < v.trail.size -> drawStone(v.trail[i], c, stoneR, theme, dens, scratch.b, scale = if (i == pop) 0.4f + 0.6f * popT.value else 1f)
                    else -> {
                        drawDimStone(c, stoneR, theme, dens)
                        if (i == v.frontier && glowFrontier) drawFrontierGlow(c, stoneR, theme, dens, pulse.value)
                    }
                }
            }
            drawFogBank(fogLevel, drift.value, theme, front = true)
            // the pals, with the one that is hopping lifted along an arc
            val leg = legProvider()
            for (pi in v.pals.indices) {
                if (leg != null && leg.pal == pi) continue
                val pal = v.pals[pi]
                val c = TrailGeometry.centre(pal.position, columns, rows, cell)
                val o = offsets[pi]
                drawHedgehog(Offset(c.x + o.x * cell, c.y + o.y * cell - cell * 0.08f), palSize, TrailPalColours[pal.ownerSeat % 4], TrailPalShapes[pal.ownerSeat % 4], theme, dens, scratch.a, scratch.b)
                if (pi in legalPals) drawCircle(theme.glow.copy(alpha = 0.3f + 0.5f * pulse.value), palSize * 0.72f, Offset(c.x + o.x * cell, c.y + o.y * cell), style = Stroke(3f * dens))
                if (pi == suggestedPal) drawCircle(theme.glow, palSize * 0.8f, Offset(c.x + o.x * cell, c.y + o.y * cell), style = Stroke(4f * dens))
            }
            if (leg != null) {
                val a = TrailGeometry.centre(leg.from, columns, rows, cell)
                val b = TrailGeometry.centre(leg.to, columns, rows, cell)
                val t = legT.value
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t - sin(PI * t).toFloat() * cell * 0.45f
                val pal = v.pals[leg.pal]
                drawHedgehog(Offset(x, y - cell * 0.08f), palSize, TrailPalColours[pal.ownerSeat % 4], TrailPalShapes[pal.ownerSeat % 4], theme, dens, scratch.a, scratch.b)
                drawCircle(theme.glow.copy(alpha = 0.7f), palSize * 0.7f, Offset(x, y), style = Stroke(3f * dens))
            }
        }
        // spoken stones: one quiet node per spot, in trail order (nothing here is tappable)
        for (i in 0..v.length) {
            val c = TrailGeometry.centre(i, columns, rows, cellPx)
            val desc = when {
                i == v.length -> ctx.getString(if (v.burrowOpen) R.string.trail_together_burrow_open_desc else R.string.trail_together_burrow_closed_desc)
                i >= v.trail.size -> ctx.getString(R.string.trail_together_spot_dim, i + 1)
                i == 0 -> ctx.getString(R.string.trail_together_start_desc, ctx.ttColour(v.trail[i]), ctx.getString(ttShapeNames.getValue(v.trail[i].shape)))
                else -> ctx.getString(R.string.trail_together_spot_desc, i + 1, ctx.ttColour(v.trail[i]), ctx.getString(ttShapeNames.getValue(v.trail[i].shape)))
            }
            Box(
                Modifier
                    .offset { IntOffset((c.x - cellPx / 2).roundToInt(), (c.y - cellPx / 2).roundToInt()) }
                    .size(with(dn) { cellPx.toDp() })
                    .semantics { contentDescription = desc }
            )
        }
        // the pals: 56 dp tap targets, described for TalkBack, tappable only while one can hop
        val half = with(dn) { 28.dp.toPx() }
        val canHopText = stringResource(R.string.trail_together_pal_state_can_hop)
        for (pi in v.pals.indices) {
            val pal = v.pals[pi]
            val c = TrailGeometry.centre(pal.position, columns, rows, cellPx)
            val o = offsets[pi]
            val legal = pi in legalPals
            val you = ownerIsYou(pi)
            val desc = if (pal.position >= v.length) {
                if (you) ctx.getString(R.string.trail_together_pal_home_you) else ctx.getString(R.string.trail_together_pal_home, ownerName(pi))
            } else {
                if (you) ctx.getString(R.string.trail_together_pal_desc_you, pal.position + 1, v.length) else ctx.getString(R.string.trail_together_pal_desc, ownerName(pi), pal.position + 1, v.length)
            }
            Box(
                Modifier
                    .offset { IntOffset((c.x + o.x * cellPx - half).roundToInt(), (c.y + o.y * cellPx - half).roundToInt()) }
                    .size(56.dp)
                    .then(if (legal) Modifier.clickable(role = Role.Button) { onPalTap(pi) } else Modifier)
                    .semantics { contentDescription = desc; if (legal) stateDescription = canHopText }
            )
        }
    }
}

// ---- hands ---------------------------------------------------------------------------------------------------------

private val TT_STRIP_MIN_HEIGHT = 132.dp

/**
 * One seat's strip: the seat on turn shows its hand big (72 x 96 dp cards) with Swap and Help me choose beside it; a lantern
 * swaps the cards for its own chooser; a seat that is not on turn shows its open hand smaller. [rotated] turns the whole
 * strip half a turn, for the player across the table.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrailHandStrip(
    v: TrailState, seat: Int, name: String, current: Boolean, personSeat: Boolean, interactive: Boolean, rotated: Boolean,
    pick: TrailPick, suggested: TrailHint?,
    pulse: State<Float>, theme: TrailTheme, ctx: Context, canLanternHop: Boolean, hasHopTargets: (TrailColour) -> Boolean,
    onCard: (Int) -> Unit, onSwap: () -> Unit, onHint: () -> Unit, onBack: () -> Unit, onMode: (LanternMode) -> Unit, onColour: (TrailColour) -> Unit
) {
    val cards = v.hands.getOrElse(seat) { emptyList() }
    val scratch = remember { Path() }
    val isTurn = current
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TT_STRIP_MIN_HEIGHT)
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .clip(shape)
            .background(if (isTurn) TrailPalColours[seat % 4].copy(alpha = 0.14f) else theme.surface.copy(alpha = 0.6f))
            .border(if (isTurn) 3.dp else 1.dp, if (isTurn) TrailPalColours[seat % 4] else theme.soft.copy(alpha = 0.3f), shape)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        val lanternOpen = interactive && pick.card >= 0 && cards.getOrNull(pick.card)?.kind == TrailCardKind.LANTERN
        if (lanternOpen) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                TrailPill(stringResource(R.string.trail_together_action_cancel), theme, false, true, onBack)
                TrailPill(stringResource(R.string.trail_together_action_light), theme, pick.lantern == LanternMode.STONE, !v.burrowOpen) { onMode(LanternMode.STONE) }
                TrailPill(stringResource(R.string.trail_together_action_hop_one), theme, pick.lantern == LanternMode.HOP, canLanternHop) { onMode(LanternMode.HOP) }
                if (pick.lantern == LanternMode.STONE) {
                    for (c in TrailRules.coloursOf(v.band)) {
                        val d = ctx.getString(R.string.trail_together_colour_pick_desc, ctx.ttColour(c), ctx.getString(ttShapeNames.getValue(c.shape)))
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(theme.surface)
                                .border(2.dp, theme.ink.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button) { onColour(c) }.semantics { contentDescription = d },
                            contentAlignment = Alignment.Center
                        ) {
                            val sc = remember { Path() }
                            Canvas(Modifier.size(34.dp)) { drawTrailShape(c.shape, Offset(size.width / 2, size.height / 2), size.minDimension * 0.42f, c.tint(), theme.ink, 1.6f * density, sc) }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (current && personSeat) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TrailToolButton(stringResource(R.string.trail_together_action_swap), pick.swap, interactive, theme, onSwap) { c, r, col -> drawSwapIcon(c, r, col, density, scratch) }
                        TrailToolButton(stringResource(R.string.trail_together_action_hint), false, interactive, theme, onHint) { c, r, col -> drawSparkIcon(c, r, col, scratch) }
                    }
                } else {
                    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        TrailSeatBadge(seat, 28.dp, theme)
                        Text(name, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    cards.forEachIndexed { i, card ->
                        val c = card.colour
                        val noHop = card.kind == TrailCardKind.STEP && c != null && !hasHopTargets(c)
                        val stoneBlocked = card.kind == TrailCardKind.STONE && v.burrowOpen
                        val glowThis = interactive && suggested != null && suggested.handIndex == i
                        TrailCardView(
                            card = card, big = current, theme = theme, pulse = pulse,
                            selected = interactive && pick.card == i, glow = glowThis, dim = interactive && (noHop || stoneBlocked),
                            stateText = when {
                                interactive && pick.swap -> ctx.getString(R.string.trail_together_card_state_swap)
                                interactive && pick.card == i -> ctx.getString(R.string.trail_together_card_state_selected)
                                glowThis -> ctx.getString(R.string.trail_together_card_state_suggested)
                                interactive && noHop -> ctx.getString(R.string.trail_together_card_state_no_hop)
                                else -> null
                            },
                            desc = ctx.ttCardDesc(card), clickable = interactive, onClick = { onCard(i) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailPill(label: String, theme: TrailTheme, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) theme.accent else theme.surface)
            .border(2.dp, if (selected) theme.accent else theme.ink.copy(alpha = if (enabled) 0.5f else 0.2f), shape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (selected) theme.onAccent else theme.ink.copy(alpha = if (enabled) 1f else 0.5f), fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center
        )
    }
}

/** A 56 dp icon button (Swap, Help me choose) with a drawn icon; [desc] is what TalkBack says. */
@Composable
private fun TrailToolButton(
    desc: String, selected: Boolean, enabled: Boolean, theme: TrailTheme, onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.drawscope.DrawScope.(Offset, Float, Color) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(56.dp)
            .clip(shape)
            .background(if (selected) theme.accent else theme.surface)
            .border(2.dp, if (selected) theme.accent else theme.ink.copy(alpha = 0.5f), shape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = desc; if (!enabled) disabled() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(30.dp)) {
            icon(Offset(size.width / 2, size.height / 2), size.minDimension * 0.4f, if (selected) theme.onAccent else theme.ink)
        }
    }
}

/**
 * One card. The big card is 72 x 96 dp (tall enough for a larger font); a small card is 40 x 54 dp and never taps. A card is a
 * shape in its colour (so colour never stands alone), a hop arrow, a "+" or a lantern, and one short word.
 */
@Composable
private fun TrailCardView(
    card: TrailCard, big: Boolean, theme: TrailTheme, pulse: State<Float>, selected: Boolean, glow: Boolean, dim: Boolean,
    stateText: String?, desc: String, clickable: Boolean, onClick: () -> Unit
) {
    val w = if (big) 72.dp else 44.dp
    val minH = if (big) 96.dp else 58.dp
    val shape = RoundedCornerShape(if (big) 14.dp else 9.dp)
    val label = stringResource(
        when (card.kind) {
            TrailCardKind.STEP -> R.string.trail_together_card_hop_label
            TrailCardKind.STONE -> R.string.trail_together_card_stone_label
            else -> R.string.trail_together_card_lantern_label
        }
    )
    val scratch = remember { TrailScratch() }
    Box(
        Modifier
            .width(w)
            .heightIn(min = minH)
            .graphicsLayer { translationY = if (selected) -10.dp.toPx() else 0f; alpha = if (dim) 0.55f else 1f }
            .clip(shape)
            .background(theme.surface)
            .border(if (selected) 4.dp else 2.dp, if (selected) theme.accent else theme.ink.copy(alpha = 0.45f), shape)
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                if (stateText != null) stateDescription = stateText
            }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.weight(1f).fillMaxWidth().heightIn(min = if (big) 48.dp else 30.dp)) {
                val cx = size.width / 2
                val cy = size.height / 2
                val r = size.minDimension * 0.34f
                val col = card.colour
                when (card.kind) {
                    TrailCardKind.STEP -> if (col != null) {
                        drawTrailShape(col.shape, Offset(cx, cy + r * 0.35f), r, col.tint(), theme.ink, 1.6f * density, scratch.a)
                        drawHopArrow(Rect(cx - r * 0.9f, cy - r * 1.25f, cx + r * 0.9f, cy - r * 0.85f), theme.ink, density, scratch.b)
                    }
                    TrailCardKind.STONE -> if (col != null) {
                        drawTrailShape(col.shape, Offset(cx - r * 0.1f, cy + r * 0.1f), r, col.tint(), theme.ink, 1.6f * density, scratch.a)
                        drawPlus(Offset(cx + r * 0.85f, cy - r * 0.8f), r * 0.42f, theme.accent, theme.onAccent, density)
                    }
                    else -> drawLantern(Offset(cx, cy), size.minDimension * 0.9f, theme, density)
                }
            }
            Text(
                label, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = if (big) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
        if (glow) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 0.35f + 0.65f * pulse.value }
                    .border(4.dp, theme.glow, shape)
            )
        }
    }
}

// ---- result card and picture guide -----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrailResultCard(v: TrailState, theme: TrailTheme, ctx: Context, onAgain: () -> Unit, onFinish: () -> Unit) {
    val won = v.won
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .padding(16.dp)
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(theme.surface)
            .border(3.dp, if (won) theme.accent else theme.soft, shape)
            .padding(18.dp)
            .verticalScroll(rememberScrollState())
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(if (won) R.string.trail_together_status_win else R.string.trail_together_status_lose), color = theme.ink,
            fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(if (won) R.string.trail_together_status_win_sub else R.string.trail_together_status_lose_sub), color = theme.ink,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(10.dp))
        val bandName = stringResource(
            when (v.band) {
                TrailBand.LITTLE -> R.string.trail_together_band_little
                TrailBand.MEDIUM -> R.string.trail_together_band_medium
                TrailBand.BIG -> R.string.trail_together_band_big
            }
        )
        Text(stringResource(R.string.trail_together_score_title), color = theme.accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Text(bandName, color = theme.soft, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        val lines = listOf(
            ctx.getString(R.string.trail_together_score_pals_home, v.palsHome, v.pals.size),
            ctx.getString(R.string.trail_together_score_turns, v.plays),
            ctx.getString(R.string.trail_together_score_tries, v.tries),
            ctx.getString(R.string.trail_together_score_homes, v.wins)
        )
        lines.forEach { Text(it, color = theme.ink, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.trail_together_teaches), color = theme.soft, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TrailPill(stringResource(R.string.trail_together_action_play_again), theme, true, true, onAgain)
            TrailPill(stringResource(R.string.trail_together_action_finish), theme, false, true, onFinish)
        }
    }
}

/** The first-run picture guide: eight big picture cards, each with its one line; Next walks through them and Skip leaves. */
@Composable
private fun TrailTutorial(theme: TrailTheme, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val last = step == ttTutorial.size - 1
    val shape = RoundedCornerShape(24.dp)
    val stepDesc = stringResource(R.string.trail_together_tutorial_step, step + 1, ttTutorial.size)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(theme.surface)
                .border(3.dp, theme.accent, shape)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = stepDesc }) { drawTutorialArt(step, theme, density) }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(ttTutorial[step]), color = theme.ink, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            Text(stepDesc, color = theme.soft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clearAndSetSemantics { })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrailPill(stringResource(R.string.trail_together_action_skip), theme, false, true, onDone)
                TrailPill(stringResource(if (last) R.string.trail_together_action_go else R.string.trail_together_action_next), theme, true, true, { if (last) onDone() else step++ })
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTutorialArt(step: Int, theme: TrailTheme, dens: Float) {
    val w = size.width
    val h = size.height
    val sa = Path()
    val sb = Path()
    val cy = h * 0.55f
    val r = (h * 0.22f)
    fun hedge(x: Float, y: Float, seat: Int, s: Float = h * 0.46f) =
        drawHedgehog(Offset(x, y), s, TrailPalColours[seat % 4], TrailPalShapes[seat % 4], theme, dens, sa, sb)
    drawRoundRect(theme.path.copy(alpha = 0.35f), Offset.Zero, size, CornerRadius(h * 0.12f))
    when (step) {
        0 -> {
            drawLine(theme.path, Offset(w * 0.1f, cy + r * 0.4f), Offset(w * 0.72f, cy + r * 0.4f), r * 0.9f, StrokeCap.Round)
            hedge(w * 0.25f, cy, 0)
            hedge(w * 0.45f, cy, 1)
            drawBurrow(Offset(w * 0.8f, cy), r * 1.2f, true, theme, dens, sa)
        }
        1 -> {
            drawLine(theme.path, Offset(w * 0.12f, cy), Offset(w * 0.88f, cy), r * 0.9f, StrokeCap.Round)
            TrailColour.entries.take(4).forEachIndexed { i, c -> drawStone(c, Offset(w * (0.16f + 0.23f * i), cy), r, theme, dens, sb) }
        }
        2 -> {
            drawLine(theme.path, Offset(w * 0.1f, cy + r), Offset(w * 0.9f, cy + r), r * 0.9f, StrokeCap.Round)
            drawStone(TrailColour.BERRY, Offset(w * 0.18f, cy + r), r, theme, dens, sb)
            drawStone(TrailColour.SUN, Offset(w * 0.4f, cy + r), r, theme, dens, sb)
            drawStone(TrailColour.BERRY, Offset(w * 0.84f, cy + r), r, theme, dens, sb)
            hedge(w * 0.18f, cy + r * 0.3f, 0, h * 0.4f)
            drawHopArrow(Rect(w * 0.2f, cy - r * 0.8f, w * 0.82f, cy - r * 0.2f), theme.ink, dens, sa)
        }
        3 -> {
            drawLine(theme.path, Offset(w * 0.1f, cy), Offset(w * 0.9f, cy), r * 0.9f, StrokeCap.Round)
            drawStone(TrailColour.LEAF, Offset(w * 0.15f, cy), r, theme, dens, sb)
            drawStone(TrailColour.SKY, Offset(w * 0.35f, cy), r, theme, dens, sb)
            drawStone(TrailColour.SUN, Offset(w * 0.55f, cy), r, theme, dens, sb)
            drawFrontierGlow(Offset(w * 0.75f, cy), r, theme, dens, 0.8f)
            drawTrailShape("star", Offset(w * 0.75f, cy), r * 0.7f, TrailColour.PLUM.tint(), theme.ink, 1.6f * dens, sb, 0.85f)
            drawPlus(Offset(w * 0.86f, cy - r * 1.1f), r * 0.4f, theme.accent, theme.onAccent, dens)
        }
        4 -> {
            drawLantern(Offset(w * 0.5f, cy), h * 0.62f, theme, dens)
            drawStone(TrailColour.PEACH, Offset(w * 0.2f, cy), r * 0.9f, theme, dens, sb)
            hedge(w * 0.8f, cy, 2, h * 0.36f)
        }
        5 -> {
            val n = 6
            val gap = 6f * dens
            val cw = (w * 0.84f - gap * (n - 1)) / n
            for (i in 0 until n) {
                val x = w * 0.08f + i * (cw + gap)
                drawRoundRect(if (i < 3) theme.fogBack else theme.soft.copy(alpha = 0.25f), Offset(x, cy), Size(cw, h * 0.2f), CornerRadius(8f * dens))
            }
            drawCloud(Offset(w * 0.08f + 3 * (cw + gap), cy - h * 0.12f), h * 0.2f, theme.fogVeil, theme.ink, dens)
        }
        6 -> {
            hedge(w * 0.25f, cy + r * 0.3f, 0, h * 0.4f)
            hedge(w * 0.75f, cy + r * 0.3f, 1, h * 0.4f)
            drawCircle(theme.surface, r * 0.8f, Offset(w * 0.3f, cy - r * 1.3f))
            drawCircle(theme.ink, r * 0.8f, Offset(w * 0.3f, cy - r * 1.3f), style = Stroke(2f * dens))
            drawTrailShape("diamond", Offset(w * 0.3f, cy - r * 1.3f), r * 0.45f, TrailColour.SKY.tint(), theme.ink, 1.4f * dens, sb)
            drawCircle(theme.surface, r * 0.8f, Offset(w * 0.7f, cy - r * 1.3f))
            drawCircle(theme.ink, r * 0.8f, Offset(w * 0.7f, cy - r * 1.3f), style = Stroke(2f * dens))
            drawTrailShape("circle", Offset(w * 0.7f, cy - r * 1.3f), r * 0.45f, TrailColour.BERRY.tint(), theme.ink, 1.4f * dens, sb)
        }
        else -> {
            drawBurrow(Offset(w * 0.5f, cy - r * 0.4f), r * 1.5f, true, theme, dens, sa)
            hedge(w * 0.2f, cy + r * 0.9f, 0, h * 0.36f)
            hedge(w * 0.5f, cy + r * 1.1f, 1, h * 0.36f)
            hedge(w * 0.8f, cy + r * 0.9f, 2, h * 0.36f)
            drawTrailShape("star", Offset(w * 0.5f, h * 0.14f), r * 0.55f, Color(0xFFFFD866), theme.ink, 1.6f * dens, sb)
        }
    }
}
