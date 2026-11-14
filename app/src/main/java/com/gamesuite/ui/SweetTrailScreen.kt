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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
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
import com.gamesuite.games.sweettrail.SweetTrailBoard
import com.gamesuite.games.sweettrail.SweetTrailDeck
import com.gamesuite.games.sweettrail.SweetTrailEvent
import com.gamesuite.games.sweettrail.SweetTrailGame
import com.gamesuite.games.sweettrail.SweetTrailLayout
import com.gamesuite.games.sweettrail.SweetTrailMoves
import com.gamesuite.games.sweettrail.SweetTrailReject
import com.gamesuite.games.sweettrail.SweetTrailRules
import com.gamesuite.games.sweettrail.SweetTrailState
import com.gamesuite.games.sweettrail.TrailCardKind
import com.gamesuite.games.sweettrail.TrailColour
import com.gamesuite.games.sweettrail.TrailLandmark
import com.gamesuite.games.sweettrail.TrailLength
import com.gamesuite.games.sweettrail.sweetTrailWishFromExtras
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val ST_COACH_ID = "sweet-trail"

/** The four seat shapes (circle, square, triangle, diamond are glyphs 0..3 of the shared seat pieces). */
private val stShapeNames = listOf(
    R.string.sweet_trail_shape_circle, R.string.sweet_trail_shape_square,
    R.string.sweet_trail_shape_triangle, R.string.sweet_trail_shape_diamond
)

private fun colourNameRes(c: TrailColour): Int = when (c) {
    TrailColour.CHERRY -> R.string.sweet_trail_colour_cherry
    TrailColour.TANGERINE -> R.string.sweet_trail_colour_tangerine
    TrailColour.LEMON -> R.string.sweet_trail_colour_lemon
    TrailColour.LIME -> R.string.sweet_trail_colour_lime
    TrailColour.BLUEBERRY -> R.string.sweet_trail_colour_blueberry
    TrailColour.GRAPE -> R.string.sweet_trail_colour_grape
}

private fun patternNameRes(c: TrailColour): Int = when (c) {
    TrailColour.CHERRY -> R.string.sweet_trail_pattern_cherry
    TrailColour.TANGERINE -> R.string.sweet_trail_pattern_tangerine
    TrailColour.LEMON -> R.string.sweet_trail_pattern_lemon
    TrailColour.LIME -> R.string.sweet_trail_pattern_lime
    TrailColour.BLUEBERRY -> R.string.sweet_trail_pattern_blueberry
    TrailColour.GRAPE -> R.string.sweet_trail_pattern_grape
}

private fun landmarkNameRes(l: TrailLandmark): Int = when (l) {
    TrailLandmark.COOKIE_HILL -> R.string.sweet_trail_landmark_cookie_hill
    TrailLandmark.LEMONADE_LAKE -> R.string.sweet_trail_landmark_lemonade_lake
    TrailLandmark.CUPCAKE_TOWER -> R.string.sweet_trail_landmark_cupcake_tower
    TrailLandmark.HONEYCOMB_CAVE -> R.string.sweet_trail_landmark_honeycomb_cave
    TrailLandmark.MARSHMALLOW_MEADOW -> R.string.sweet_trail_landmark_marshmallow_meadow
    TrailLandmark.CAKE_GATE -> R.string.sweet_trail_landmark_cake_gate
}

private fun shortcutNameRes(startSquare: Int): Int? = when (SweetTrailBoard.SHORTCUT_NAMES[startSquare]) {
    "jelly_bridge" -> R.string.sweet_trail_jelly_bridge
    "pretzel_ladder" -> R.string.sweet_trail_pretzel_ladder
    "cloud_zipline" -> R.string.sweet_trail_cloud_zipline
    else -> null
}

private fun Context.stColourWithPattern(c: TrailColour): String =
    getString(R.string.sweet_trail_colour_pattern, getString(colourNameRes(c)), getString(patternNameRes(c)))

/** The short spoken name of a card: the colour, "Double <colour>" or the landmark. */
private fun Context.stCardName(cardId: Int, length: TrailLength): String {
    val card = SweetTrailDeck.byId(cardId, length) ?: return ""
    val colour = card.colour
    val landmark = card.landmark
    return when {
        card.kind == TrailCardKind.LANDMARK && landmark != null -> getString(landmarkNameRes(landmark))
        card.kind == TrailCardKind.DOUBLE && colour != null -> getString(R.string.sweet_trail_card_double, getString(colourNameRes(colour)))
        colour != null -> getString(colourNameRes(colour))
        else -> ""
    }
}

/** The face-up card as a screen reader says it: its name plus the colour's pattern. */
private fun Context.stCardLabel(cardId: Int, length: TrailLength, last: Boolean): String {
    val card = SweetTrailDeck.byId(cardId, length) ?: return ""
    val colour = card.colour
    val base = if (colour != null && card.kind != TrailCardKind.LANDMARK) {
        val pattern = getString(patternNameRes(colour))
        stCardName(cardId, length) + ", " + pattern
    } else {
        stCardName(cardId, length)
    }
    return getString(if (last) R.string.sweet_trail_last_card else R.string.sweet_trail_card_label, base)
}

private fun Context.stSquareLabel(square: Int, rules: SweetTrailRules): String {
    if (square <= SweetTrailBoard.START) return getString(R.string.sweet_trail_place_start)
    if (square >= SweetTrailBoard.FINISH) return getString(R.string.sweet_trail_place_finish)
    val colour = SweetTrailBoard.colourAt(square)
    val parts = ArrayList<String>()
    if (colour != null) parts += stColourWithPattern(colour)
    SweetTrailBoard.landmarkAt(square)?.let { parts += getString(landmarkNameRes(it)) }
    if (rules.shortcutsOn) {
        val to = SweetTrailBoard.SHORTCUTS[square]
        val name = shortcutNameRes(square)
        if (to != null && name != null) parts += getString(R.string.sweet_trail_shortcut_desc, getString(name), square, to)
    }
    if (square in rules.puddleSquares) parts += getString(R.string.sweet_trail_puddle)
    return getString(R.string.sweet_trail_square_label, square, parts.joinToString(", "))
}

private fun Context.stText(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

/**
 * Sweet Trail on one device (2 to 4 seats, people and computers in any mix). One big card pile to tap, a card that
 * flips over, pieces that hop along a winding trail drawn in code. A seat holding a wish (the handicap) gets two
 * round buttons after drawing: a check to keep the card, a star to swap it once. The screen has almost no words:
 * the one status line is for adults and screen readers.
 *
 * Motion. Reduced Motion turns the card flip and the hops into cross-fades and puts every piece straight on its
 * square; the game speed setting scales every pause and every hop. Nothing blinks faster than 3 Hz.
 *
 * Accessibility. Every control is at least 56 dp (the pile 120 dp), each seat is a colour AND a shape, every square
 * has a colour AND a pattern, the status line is a polite live region that only ever carries the newest event, a
 * blocked tap says why through an assertive line, and with a screen reader on every square can be visited.
 *
 * Correctness notes learned from the audits of the earlier kids games: animated state is read only inside draw and
 * graphicsLayer lambdas (the screen never recomposes per frame); a draw is marked in flight synchronously by the
 * game, so a double tap cannot draw for the next seat; results are worded, announced and the winner panel shown
 * only after the animation has finished; dialogs, the coach card and loss of window focus pause the computer and
 * the hops.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SweetTrailScreen(
    sessionManager: GameSessionManager,
    game: SweetTrailGame,
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
    val theme = remember(dark) { sweetTrailTheme(dark) }
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val burst = rememberParticleBurst()
    val density = LocalDensity.current
    val touchExploration = rememberClTouchExploration()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.length = TrailLength.fromId(extras["length"])
        game.sticky = extras["sticky"] != "false"
        game.wishSeats = sweetTrailWishFromExtras(extras, ctx.players.size)
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
    val flip = remember { Animatable(1f) }        // 0 = face down, 1 = face up
    val cardFade = remember { Animatable(1f) }    // Reduced Motion: a cross-fade instead of the flip
    val legT = remember { Animatable(0f) }
    var leg by remember { mutableStateOf<SweetTrailLeg?>(null) }
    var shownPos by remember { mutableStateOf<List<Int>>(emptyList()) }
    var shownCardId by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var animReady by remember { mutableStateOf(false) }
    var lastDrawSerial by remember { mutableIntStateOf(-1) }
    var lastMoveSerial by remember { mutableIntStateOf(-1) }
    var whyRes by remember { mutableStateOf<Int?>(null) }
    // the state the board last finished animating: results are worded, announced and the winner panel is shown from it
    var shownState by remember { mutableStateOf<SweetTrailState?>(null) }
    val liveState = state
    // an animation is owed (new serials, not a restart / new board) or running; read in composition on purpose,
    // it changes only when an action starts or ends
    val animating = busy || (liveState != null &&
        liveState.drawSerial >= lastDrawSerial && liveState.moveSerial >= lastMoveSerial &&
        (liveState.drawSerial != lastDrawSerial || liveState.moveSerial != lastMoveSerial))

    // Pause (dialog, background): hops and bot steps wait for resume().
    suspend fun awaitUnpaused() {
        if (game.paused.value) snapshotFlow { game.paused.value }.first { !it }
    }

    suspend fun runLeg(l: SweetTrailLeg, ms: Int) {
        awaitUnpaused()
        leg = l
        legT.snapTo(0f)
        legT.animateTo(1f, tween(ms.coerceAtLeast(40), easing = LinearEasing))
    }

    LaunchedEffect(state?.drawSerial, state?.moveSerial, state?.winnerPlayerId) {
        val cur = state ?: return@LaunchedEffect
        if (!animReady || cur.drawSerial < lastDrawSerial || cur.moveSerial < lastMoveSerial) {
            animReady = true
            shownPos = cur.positions
            shownState = cur
            shownCardId = cur.eventCard
            lastDrawSerial = cur.drawSerial
            lastMoveSerial = cur.moveSerial
            flip.snapTo(if (cur.eventCard != null) 1f else 0f)
            cardFade.snapTo(1f)
            game.settled()
            return@LaunchedEffect
        }
        val newDraw = cur.drawSerial != lastDrawSerial
        val newMove = cur.moveSerial != lastMoveSerial
        if (!newDraw && !newMove) {
            game.settled()
            return@LaunchedEffect
        }
        busy = true
        try {
            awaitUnpaused()
            if (newDraw) {
                lastDrawSerial = cur.drawSerial
                if (reduced) {
                    shownCardId = cur.eventCard
                    flip.snapTo(1f)
                    cardFade.snapTo(0f)
                    cardFade.animateTo(1f, tween(gameSpeed.scale(250L).toInt().coerceAtLeast(60)))
                } else {
                    flip.snapTo(0f)
                    shownCardId = cur.eventCard
                    playSfx(SfxKind.WHOOSH)
                    flip.animateTo(1f, tween(gameSpeed.scale(520L).toInt().coerceAtLeast(160), easing = LinearEasing))
                    playSfx(SfxKind.LIGHT_TICK)
                }
                if (cur.event == SweetTrailEvent.PENDING) haptics(HapticSignal.NORMAL_ACTION)
                // let the child see the colour before the piece sets off
                if (cur.event != SweetTrailEvent.PENDING) delay(gameSpeed.scale(280L))
            }
            if (newMove) {
                lastMoveSerial = cur.moveSerial
                val seat = cur.eventSeat
                if (seat != null && !reduced) {
                    val card = cur.eventCard?.let { SweetTrailDeck.byId(it, cur.rules.length) }
                    val from = cur.eventFrom
                    val crowd = shownPos.count { it == from }.coerceAtLeast(1)
                    var at = stPiecePos(seat, from, crowd)
                    when {
                        cur.event == SweetTrailEvent.STUCK -> {
                            // the piece wiggles free of the goo: a gentle shimmy, never a sad sound
                            playSfx(SfxKind.LIGHT_TICK)
                            runLeg(SweetTrailLeg(seat, at, at, 0.14f, bounces = 4), gameSpeed.scale(1200L).toInt())
                        }
                        cur.event == SweetTrailEvent.PASSED -> {
                            playSfx(SfxKind.SUCCESS_CHIME)
                            runLeg(SweetTrailLeg(seat, at, at, 0.4f, bounces = 3), gameSpeed.scale(900L).toInt())
                        }
                        card != null -> {
                            val step = SweetTrailMoves.step(cur.rules, from, card)
                            if (card.kind == TrailCardKind.LANDMARK) {
                                val dest = stPiecePos(seat, step.landed, 1)
                                playSfx(SfxKind.WHOOSH)
                                runLeg(SweetTrailLeg(seat, at, dest, 1.6f), gameSpeed.scale(700L).toInt())
                                at = dest
                            } else {
                                val route = ArrayList<Int>()
                                for (sq in (from + 1)..minOf(step.landed, SweetTrailBoard.PATH_SQUARES)) route += sq
                                if (step.landed >= SweetTrailBoard.FINISH) route += SweetTrailBoard.FINISH
                                // a long hop is sampled so it never takes more than about a second and a half
                                val stride = ((route.size + 13) / 14).coerceAtLeast(1)
                                val legs = route.filterIndexed { i, _ -> (i + 1) % stride == 0 || i == route.lastIndex }
                                val hopMs = gameSpeed.scale((1400L / legs.size.coerceAtLeast(1)).coerceIn(70L, 170L)).toInt()
                                for (sq in legs) {
                                    val next = stPiecePos(seat, sq, 1)
                                    runLeg(SweetTrailLeg(seat, at, next, 0.32f), hopMs)
                                    playSfx(SfxKind.LIGHT_TICK)
                                    at = next
                                }
                            }
                            if (cur.event == SweetTrailEvent.SHORTCUT) {
                                delay(gameSpeed.scale(120L))
                                val dest = stPiecePos(seat, cur.eventTo, 1)
                                playSfx(SfxKind.SUCCESS_CHIME)
                                runLeg(
                                    SweetTrailLeg(seat, at, dest, 0.25f, control = stShortcutControl(step.landed, cur.eventTo)),
                                    gameSpeed.scale(700L).toInt()
                                )
                            } else if (cur.event == SweetTrailEvent.PUDDLE) {
                                runLeg(SweetTrailLeg(seat, at, at, 0.12f, bounces = 2), gameSpeed.scale(500L).toInt())
                            }
                            leg = null
                            playSfx(SfxKind.SOLID_THUNK)
                        }
                    }
                }
                when (cur.event) {
                    SweetTrailEvent.SHORTCUT -> haptics(HapticSignal.SUCCESS)
                    SweetTrailEvent.PUDDLE, SweetTrailEvent.STUCK, SweetTrailEvent.PASSED -> haptics(HapticSignal.LIGHT_TICK)
                    SweetTrailEvent.WIN -> {}
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

    // the computer draws, then (with a wish in hand) decides, after a short pause that follows the speed setting
    val gamePaused = game.paused.value
    LaunchedEffect(state, animating, gamePaused) {
        val cur = state ?: return@LaunchedEffect
        if (animating || gamePaused || !game.botToMove(cur)) return@LaunchedEffect
        delay(gameSpeed.scale(600L + (cur.drawSerial % 4) * 100L).coerceAtLeast(250L))
        game.playBotStep()
    }

    val helpTitle = stringResource(R.string.sweet_trail_help_title)
    val helpText = stringResource(R.string.sweet_trail_help_text)
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
    val pulse: State<Float> = if (reduced) remember { mutableStateOf(0.7f) } else rememberInfiniteTransition(label = "sweet-trail-pulse").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "sweet-trail-pulse-a"
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
    val canDraw = isMyTurn && settled && !s.pending
    val showDecision = isMyTurn && settled && s.pending

    LaunchedEffect(v.over) {
        if (!v.over) return@LaunchedEffect
        val winnerSeat = v.players.indexOfFirst { it.playerId == v.winnerPlayerId }
        val humanWon = winnerSeat >= 0 && !v.players[winnerSeat].isBot
        delay(gameSpeed.scale(300L))
        playSfx(SfxKind.SUCCESS_CHIME)
        haptics(if (humanWon) HapticSignal.CELEBRATION else HapticSignal.SUCCESS)
        if (!reduced && winnerSeat >= 0) {
            burst.spawn(
                Offset(0.5f, 0.4f), if (humanWon) 90 else 50,
                listOf(Color(0xFFFFD866), Color.White, seatColors[winnerSeat % seatColors.size]),
                speedRange = 0.25f..0.95f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f
            )
        }
    }

    // ---- words ---------------------------------------------------------------------------
    // Everything below is worded from [v], the state the board has finished animating, so a result is spoken
    // (and the winner panel shown) only once the piece has landed.
    val ann = run {
        val who = seatName(v.eventSeat ?: v.currentPlayerIndex)
        val card = v.eventCard?.let { androidContext.stCardName(it, v.rules.length) } ?: ""
        val base = when (v.event) {
            SweetTrailEvent.PENDING -> androidContext.stText(who, R.string.sweet_trail_ann_pending, R.string.sweet_trail_ann_pending_you, card)
            SweetTrailEvent.MOVE -> androidContext.stText(who, R.string.sweet_trail_ann_move, R.string.sweet_trail_ann_move_you, card, v.eventTo)
            SweetTrailEvent.SHORTCUT -> {
                val landed = SweetTrailMoves.shortcutStartFor(v.eventTo)
                val place = landed?.let { shortcutNameRes(it) }?.let { androidContext.getString(it) } ?: ""
                androidContext.stText(who, R.string.sweet_trail_ann_shortcut, R.string.sweet_trail_ann_shortcut_you, card, place, v.eventTo)
            }
            SweetTrailEvent.PUDDLE -> androidContext.stText(who, R.string.sweet_trail_ann_puddle, R.string.sweet_trail_ann_puddle_you, card)
            SweetTrailEvent.STUCK -> androidContext.stText(who, R.string.sweet_trail_ann_stuck, R.string.sweet_trail_ann_stuck_you)
            SweetTrailEvent.PASSED -> androidContext.stText(who, R.string.sweet_trail_ann_passed, R.string.sweet_trail_ann_passed_you, card)
            SweetTrailEvent.WIN -> androidContext.stText(who, R.string.sweet_trail_ann_win, R.string.sweet_trail_ann_win_you)
            else -> androidContext.stText(seatName(v.currentPlayerIndex), R.string.sweet_trail_ann_start, R.string.sweet_trail_ann_start_you)
        }
        val moved = v.event != SweetTrailEvent.START && v.event != SweetTrailEvent.PENDING && v.event != SweetTrailEvent.STUCK
        val extra = StringBuilder(base)
        if (v.eventReshuffled && (moved || v.event == SweetTrailEvent.PENDING)) extra.append(' ').append(androidContext.getString(R.string.sweet_trail_ann_reshuffle))
        if (v.eventWished && moved) extra.append(' ').append(androidContext.getString(R.string.sweet_trail_ann_wish_used))
        extra.toString()
    }
    val vMine = game.isMyTurn(v)
    val vBot = game.botToMove(v)
    val turn = when {
        v.over -> ""
        v.pending && vBot -> stringResource(R.string.sweet_trail_deciding, current)
        v.pending -> ""
        vMine && oneHuman -> stringResource(R.string.sweet_trail_turn_you)
        vBot -> stringResource(R.string.sweet_trail_thinking, current)
        else -> stringResource(R.string.sweet_trail_turn_named, current)
    }
    // Only the result and a person's "tap the pile" are in the polite live region; the computer's "is drawing..."
    // changes on every bot step and sits in a quiet line, so TalkBack does not re-read the old result each time.
    val liveText = listOf(ann, if (vBot) "" else turn).filter { it.isNotBlank() }.joinToString(" ")
    val quietTurn = if (vBot) turn else ""
    val whyText = whyRes?.let { stringResource(it) }
    LaunchedEffect(whyRes) { if (whyRes != null) { delay(3500); whyRes = null } }

    fun refuse(reason: Int, buzz: Boolean) {
        whyRes = reason
        if (buzz) {
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        }
    }
    // The game answers BUSY from the moment it accepts an action until settled() (so a second tap in the same
    // input batch cannot draw for the next seat before this screen has recomposed); that is dropped silently.
    fun tapPile() {
        if (!settled) { if (isMyTurn) refuse(R.string.sweet_trail_why_wait, buzz = false); return }
        when (game.explainDraw()) {
            SweetTrailReject.NONE -> { whyRes = null; game.draw() }
            SweetTrailReject.NOT_YOUR_TURN -> refuse(R.string.sweet_trail_why_cpu, buzz = true)
            SweetTrailReject.DECIDE_FIRST -> refuse(R.string.sweet_trail_why_decide, buzz = true)
            SweetTrailReject.OVER -> refuse(R.string.sweet_trail_why_over, buzz = true)
            else -> {}
        }
    }
    fun tapKeep() {
        if (!settled) return
        if (game.explainDecision(wish = false) == SweetTrailReject.NONE) { whyRes = null; game.keepCard() }
    }
    fun tapWish() {
        if (!settled) return
        if (game.explainDecision(wish = true) == SweetTrailReject.NONE) { whyRes = null; game.useWish() }
    }

    // rebuilt only when a piece actually settles somewhere new
    val boardDescription = remember(v.positions, v.rules, v.players) {
        val pieces = v.players.indices.joinToString(", ") { i ->
            val nm = androidContext.seatLabel(seatName(i))
            val shape = androidContext.getString(stShapeNames[i % stShapeNames.size])
            if (v.positions[i] == 0) androidContext.getString(R.string.sweet_trail_pawn_label_start, nm, shape)
            else androidContext.getString(R.string.sweet_trail_pawn_label, nm, shape, v.positions[i])
        }
        val shortcuts = if (v.rules.shortcutsOn) SweetTrailBoard.SHORTCUTS.entries.sortedBy { it.key }.joinToString(", ") {
            val name = shortcutNameRes(it.key)?.let { r -> androidContext.getString(r) } ?: ""
            androidContext.getString(R.string.sweet_trail_shortcut_desc, name, it.key, it.value)
        } else ""
        androidContext.getString(R.string.sweet_trail_board_desc, pieces, shortcuts)
    }
    val squareLabels = if (touchExploration) remember(v.rules) { (0..SweetTrailBoard.FINISH).map { androidContext.stSquareLabel(it, v.rules) } } else null
    val faceCard = shownCardId?.let { SweetTrailDeck.byId(it, s.rules.length) }
    // the card in play while a wish is still open, "last card" once it has been played
    val cardDescription = shownCardId?.let { androidContext.stCardLabel(it, s.rules.length, last = !v.pending) } ?: ""
    val pileDescription = (if (canDraw) stringResource(R.string.sweet_trail_tap_pile) + ". " else "") +
        stringResource(R.string.sweet_trail_pile_label, s.pile.size)

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !v.over,
        onLeave = game::leaveSession,
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedGames == 0,
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.sweet_trail_leave_title),
        leaveBody = stringResource(if (finishedGames > 0) R.string.ccl_leave_body_session else R.string.sweet_trail_leave_body)
    ) {
        val (coachShown, openCoach) = rememberClCoach(ST_COACH_ID, ready = true)
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
                    ClCoachButton(ST_COACH_ID, openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        v.players.indices.forEach { i ->
                            val isTurn = i == v.currentPlayerIndex && !v.over
                            val label = androidContext.seatLabel(seatName(i))
                            val shapeName = androidContext.getString(stShapeNames[i % stShapeNames.size])
                            val pos = v.positions[i]
                            val wish = v.wishes[i] > 0
                            SweetTrailChip(
                                seat = i,
                                name = label,
                                square = shownPos.getOrElse(i) { pos },
                                wish = wish,
                                isBot = v.players[i].isBot,
                                isTurn = isTurn,
                                color = seatColors[i % seatColors.size],
                                theme = theme,
                                description = (if (pos == 0) androidContext.getString(R.string.sweet_trail_pawn_label_start, label, shapeName)
                                else androidContext.getString(R.string.sweet_trail_pawn_label, label, shapeName, pos)) +
                                    (if (wish) ". " + androidContext.getString(R.string.sweet_trail_wish_left, label)
                                    else if (i in game.wishSeats) ". " + androidContext.getString(R.string.sweet_trail_wish_none) else "") +
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
                        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                        columns = SweetTrailLayout.COLUMNS,
                        rows = SweetTrailLayout.ROWS,
                        maxCellPx = with(density) { 84.dp.toPx() }
                    )
                    val cellDp = with(density) { fit.cellPx.toDp() }
                    SweetTrailBoardView(
                        s = s, shownPos = shownPos, leg = { leg }, legT = legT, pulse = pulse, cellDp = cellDp,
                        theme = theme, seatColors = seatColors, description = boardDescription,
                        squareLabels = squareLabels, burst = burst
                    )
                }
            }

            val controls: @Composable () -> Unit = {
                if (v.over) {
                    val winner = v.players.firstOrNull { it.playerId == v.winnerPlayerId }
                    val rows = v.players.indices.map { i ->
                        SweetTrailScoreRow(
                            seat = i, square = v.positions[i], color = seatColors[i % seatColors.size],
                            line = androidContext.getString(R.string.sweet_trail_score_line, androidContext.seatLabel(seatName(i)), v.positions[i], SweetTrailBoard.FINISH),
                            extras = androidContext.getString(R.string.sweet_trail_score_shortcuts, v.shortcutsFound[i]) + " · " +
                                androidContext.getString(R.string.sweet_trail_score_puddles, v.puddleStops[i])
                        )
                    }
                    SweetTrailScoreCard(
                        title = winner?.let { youAware(it.displayName, R.string.sweet_trail_result_win, R.string.sweet_trail_result_win_you) } ?: "",
                        reportTitle = stringResource(R.string.sweet_trail_score_title),
                        rows = rows,
                        turnsLine = stringResource(R.string.sweet_trail_score_turns, v.turnsTaken),
                        sessionLine = if (finishedGames > 1) stringResource(
                            R.string.sweet_trail_score_session,
                            v.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)}: ${game.sessionWins.value[it.playerId] ?: 0}" }
                        ) else null,
                        theme = theme, dark = dark,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                } else {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SweetTrailPile(
                            thickness = s.pile.size / SweetTrailDeck.SIZE.toFloat(), active = canDraw, pulse = pulse, theme = theme,
                            description = pileDescription, onClick = ::tapPile
                        )
                        Box(
                            Modifier
                                .size(width = 96.dp, height = 132.dp)
                                .graphicsLayer { alpha = cardFade.value }
                                .semantics { contentDescription = cardDescription }
                        ) {
                            SweetTrailCardArt(card = { faceCard }, flip = flip, theme = theme, modifier = Modifier.fillMaxSize())
                        }
                        if (showDecision) {
                            SweetTrailRoundButton(Color(0xFF1B6B36), stringResource(R.string.sweet_trail_keep), ::tapKeep) {
                                SweetTrailKeepIcon(Color.White, 44.dp)
                            }
                            SweetTrailRoundButton(if (dark) Color(0xFF8A3F6B) else Color(0xFF9C1B52), stringResource(R.string.sweet_trail_wish_button), ::tapWish) {
                                SweetTrailWishIcon(Color.White, 48.dp)
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

/** One seat: its piece (colour and shape), the square it is on, a star if it still holds a wish. */
@Composable
private fun SweetTrailChip(
    seat: Int, name: String, square: Int, wish: Boolean, isBot: Boolean, isTurn: Boolean,
    color: Color, theme: SweetTrailTheme, description: String
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
        ClSeatBadge(color, seat % stShapeNames.size, 34)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(name, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text("$square", color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        if (wish) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(24.dp)) { SweetTrailWishBadge(24.dp) }
        }
        if (isBot) {
            Spacer(Modifier.width(4.dp))
            Text("⚙", color = theme.text.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The big card pile: tap it to draw. A pulsing ring (a steady one under Reduced Motion) shows it is waiting for a
 * tap; [pulse] is read only inside a graphicsLayer lambda, so it never recomposes the screen. For TalkBack it is one
 * button with one description and it reads as disabled while it cannot be tapped (the tap still says why).
 */
@Composable
private fun SweetTrailPile(
    thickness: Float, active: Boolean, pulse: State<Float>, theme: SweetTrailTheme, description: String, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .size(width = 120.dp, height = 132.dp)
            .clip(shape)
            .background(theme.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!active) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        SweetTrailPileArt(theme = theme, thickness = thickness, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (active) 0.35f + 0.65f * pulse.value else 1f }
                .border(if (active) 4.dp else 1.dp, if (active) theme.rim else theme.text.copy(alpha = 0.15f), shape)
        )
    }
}

/** A 72 dp round button with an icon and no words; [description] is what TalkBack says. */
@Composable
private fun SweetTrailRoundButton(fill: Color, description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
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

private class SweetTrailScoreRow(val seat: Int, val square: Int, val color: Color, val line: String, val extras: String)

/** The post-game card: who won, each seat's progress along the trail, turns taken and the session tally. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SweetTrailScoreCard(
    title: String, reportTitle: String, rows: List<SweetTrailScoreRow>, turnsLine: String, sessionLine: String?,
    theme: SweetTrailTheme, dark: Boolean, onPlayAgain: () -> Unit, onLeave: () -> Unit
) {
    val buttonColors = ButtonDefaults.buttonColors(containerColor = theme.rim, contentColor = if (dark) Color(0xFF1F1830) else Color.White)
    val outlineColors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = theme.card, contentColor = theme.text)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = theme.text)
            Spacer(Modifier.height(4.dp))
            Text(reportTitle, style = MaterialTheme.typography.titleSmall, color = theme.text)
            Spacer(Modifier.height(6.dp))
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClSeatBadge(r.color, r.seat % stShapeNames.size, 30)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.line, style = MaterialTheme.typography.bodyMedium, color = theme.text, fontWeight = FontWeight.Bold)
                        Box(
                            Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(theme.text.copy(alpha = 0.12f))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((r.square / SweetTrailBoard.FINISH.toFloat()).coerceIn(0.03f, 1f))
                                    .fillMaxHeight()
                                    .background(r.color)
                            )
                        }
                        Text(r.extras, style = MaterialTheme.typography.bodySmall, color = theme.text)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(turnsLine, style = MaterialTheme.typography.bodyMedium, color = theme.text)
            if (sessionLine != null) Text(sessionLine, style = MaterialTheme.typography.bodyMedium, color = theme.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlayAgain, colors = buttonColors, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.sweet_trail_play_again))
                }
                ChangeSetupButton(onLeave = onLeave, colors = outlineColors)
                OutlinedButton(onClick = onLeave, colors = outlineColors, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.sweet_trail_back_to_menu))
                }
            }
        }
    }
}
