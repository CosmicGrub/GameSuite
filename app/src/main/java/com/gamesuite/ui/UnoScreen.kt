package com.gamesuite.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardGlyph
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.CardStyle
import com.gamesuite.games.cards.CardVisual
import com.gamesuite.games.cards.FannedHand
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.PlayingCardView
import com.gamesuite.games.cards.card3DFlip
import com.gamesuite.games.cards.card3DToss
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.games.uno.UnoCard
import com.gamesuite.games.uno.UnoColor
import com.gamesuite.games.uno.UnoGame
import com.gamesuite.games.uno.UnoPlayerState
import com.gamesuite.games.uno.UnoRank
import com.gamesuite.games.uno.UnoRules
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberFeltGrainBrush
import com.gamesuite.ui.effects.shakeSteps
import com.gamesuite.ui.effects.specularSweep
import com.gamesuite.ui.effects.victoryGlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun UnoScreen(
    sessionManager: GameSessionManager,
    game: UnoGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    initialRules: UnoRules = UnoRules()
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    // Real haptic-signature vocabulary (see haptics/Haptics.kt) replacing this
    // screen's old generic LocalHapticFeedback.current.performHapticFeedback(...)
    // calls -- already reads Settings -> Haptics internally, so no separate
    // hapticsEnabled guard is needed at any call site below.
    val haptics = rememberHaptics()
    // Backing scope for the layered-sound helpers below (layeredPlace/
    // layeredChime) -- both fire a second, staggered play() from a plain
    // (non-suspend) click handler, so they need a scope to launch into.
    val scope = rememberCoroutineScope()
    // Settings -> Sound & feedback -> Ambient music, ANDed with the master
    // sound switch -- same two-gate pattern every other rememberAmbientMusic
    // call site uses (see LocalMusicEnabled's own KDoc). CardSounds.soundEnabled
    // is a plain static var kept in sync with settings.soundEnabled at the
    // MainActivity root, so reading it directly here (rather than deriving a
    // separate local from `settings`) matches how this screen's own layered
    // CardSounds calls already treat it.
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.UNO, enabled = musicEnabled)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    // Settings -> Display -> "3D card mode" (see settings/LocalCard3DMode.kt)
    // — reduced motion always wins, per that flag's own KDoc: a perspective
    // flip/toss is still motion, so this is the one gate every 3D-mode call
    // site in this screen reads instead of the raw setting.
    val card3D = LocalCard3DMode.current && !reducedMotion
    // Settings -> Display -> "Enhanced move animations" (see
    // settings/LocalEnhancedAnimations.kt) -- motion QUALITY (does a card get
    // there with weight, or does it just appear), independent of card3D's
    // perspective/depth concern. The two compose: e.g. the draw-pile flip
    // below only runs its card3DFlip when card3D is also on, but still tosses
    // with weight under `enhanced` alone.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.rules = initialRules
        // UNO's bot was the one CPU opponent in the suite that ignored this setting — see
        // UnoBot.kt's difficulty ladder and the audited "Default CPU difficulty" finding.
        game.difficulty = settings.defaultCpuDifficulty
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
        }
        game.startMatch()
        // Layered instead of a single play() call -- CardSounds' public API in
        // this bundle is zero-arg (no rate/volume passthrough reachable from
        // here), so this is the in-scope approximation of "not the same clip
        // every time": two overlapping plays of the same clip, staggered by a
        // randomized few tens of ms, comb together into a thicker shuffle that
        // never lines up identically twice.
        sounds.playShuffle()
        delay((25L..60L).random())
        sounds.playShuffle()
    }

    // Drive bot turns automatically -- see botThinkDelayMs's own KDoc for why
    // this is no longer a flat delay(700) (opponent "personality" tells,
    // premium 2026 vision pitch's UNO section).
    // awaitingDrawDecision is a key too: a bot that draws a playable card keeps the turn (an unchanged
    // currentPlayerIndex), and that flag flipping is what re-fires this to play the drawn card as its own
    // step (chainDrawnPlay = false), after a fresh think delay, instead of inside the draw's call.
    LaunchedEffect(state?.currentPlayerIndex, state?.awaitingColorChoice, state?.awaitingChallenge, state?.roundOver, state?.awaitingDrawDecision) {
        val s = state ?: return@LaunchedEffect
        if (s.matchOver || s.roundOver) return@LaunchedEffect
        val current = s.players.getOrNull(s.currentPlayerIndex) ?: return@LaunchedEffect
        if (current.isBot || (s.awaitingChallenge && s.challengeVictimIndex?.let { s.players[it].isBot } == true)) {
            delay(botThinkDelayMs(current.hand, s, game.rules, current.playerId))
            game.playBotTurn(chainDrawnPlay = false)
        }
    }

    val s = state ?: return
    val activeContext = context ?: return

    // CELEBRATION timed to the match actually ending -- the matchOver early
    // return just below exits before any of this file's other LaunchedEffects
    // (all declared further down) would ever get a chance to see it.
    LaunchedEffect(s.matchOver) {
        if (s.matchOver) haptics(HapticSignal.CELEBRATION)
    }

    if (s.matchOver) {
        UnoBackground { MatchOverContent(state = s, onDone = onMatchEnded, enhanced = enhanced) }
        return
    }

    if (s.roundOver) {
        UnoBackground { RoundOverContent(state = s, onNextRound = { game.startNextRound() }, enhanced = enhanced) }
        return
    }

    val myIndex = humanIndex(s, activeContext)
    val myTurn = s.currentPlayerIndex == myIndex && !s.awaitingColorChoice && !s.awaitingChallenge
    // Whether a draw would actually do anything: not once you've just drawn a playable card (the turn stays
    // with you, and the engine refuses a second draw until you play it or keep it).
    val canDraw = myTurn && !s.awaitingDrawDecision
    val myHand = s.players.getOrNull(myIndex)?.hand ?: emptyList()

    // The user's card-size preference (Settings → Card size), already mapped to
    // a device-aware multiplier — see CardScale.kt. FannedHand reads this same
    // CompositionLocal itself for the player's own hand; everywhere else this
    // screen draws a card directly (discard pile, opponent fans, the challenge
    // hand-reveal) applies it explicitly here, since PlayingCardView itself
    // deliberately does not scale on its own (see CardScale.kt's KDoc for why).
    val cardScale = LocalCardScale.current
    // Read once here, same "one source of truth, passed down explicitly" convention as
    // cardScale directly above -- see unoCardToVisual's own KDoc for the audited gap this
    // closes (colorblind mode previously only worked inside the Wild color-choice dialog).
    val colorblindMode = LocalColorblindMode.current

    // "Hot streak" tension identity (premium 2026 vision pitch, UNO section):
    // a purely cosmetic tier derived from hand sizes and the pending-draw
    // stack, both already fully visible in state -- no new information leak,
    // every seat's hand size is already rendered via OpponentHandFan below,
    // and s.pendingDraw already backs the Draw button's own label. One
    // shared pulse phase (not one InfiniteTransition per seat) so up to 10
    // players' danger borders and the Draw button's own tension border all
    // breathe in lockstep instead of drifting independently.
    val dangerPulse = rememberInfiniteTransition(label = "unoDangerPulse")
    // Deliberately NOT read with `by`: that would recompose this whole screen (the table, every seat, the
    // hand) 60 times a second. [dangerAlpha] is only ever called from inside a draw lambda, so the pulse
    // invalidates just the borders it paints.
    val dangerPulseT = dangerPulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "unoDangerPulseT"
    )
    // The pulsing itself is new motion -- gated behind `enhanced` like every
    // other new motion in this pass; the base border color/alpha (its
    // non-animated identity) stays on regardless, same as this file's
    // existing always-on direction arrows.
    fun dangerAlpha(base: Float, swing: Float): Float =
        if (enhanced) base + swing * kotlin.math.abs(kotlin.math.sin(dangerPulseT.value * Math.PI.toFloat())) else base

    val handDangerLevel = s.players.maxOfOrNull { p ->
        when {
            p.hand.size <= 1 -> 1f
            p.hand.size == 2 -> 0.45f
            else -> 0f
        }
    } ?: 0f
    // Generalizing the same tension treatment to a stacking pendingDraw
    // moment (a growing +2/+4 obligation is table tension too, not just a
    // low hand) -- maxed out around a big stack rather than scaling forever.
    val pendingDrawTension = (s.pendingDraw / 8f).coerceIn(0f, 1f)
    val animatedTension by animateFloatAsState(
        targetValue = maxOf(handDangerLevel, pendingDrawTension),
        animationSpec = if (reducedMotion) snap() else tween(600),
        label = "unoTableTension"
    )

    // Idle touch: a single gentle nudge on the Draw button after 6-8s of
    // inactivity on the player's own turn. Keyed on more than just myTurn so
    // any actual activity during the turn (a card played, a card drawn, a
    // pending-draw stack changing) restarts the idle clock instead of firing
    // mid-decision.
    var idleNudgeActive by remember { mutableStateOf(false) }
    LaunchedEffect(myTurn, myHand.map { it.instanceId }, s.pendingDraw) {
        idleNudgeActive = false
        if (myTurn && enhanced) {
            delay(7000)
            idleNudgeActive = true
        }
    }
    val idleNudgeY = remember { Animatable(0f) }
    LaunchedEffect(idleNudgeActive) {
        if (idleNudgeActive) {
            idleNudgeY.animateTo(-10f, animationSpec = tween(160))
            idleNudgeY.animateTo(0f, animationSpec = tween(320))
        }
    }

    // Transient, presentation-only state for the two big dramatic beats the
    // reference gives special treatment — neither is real game state, both
    // are purely "what's playing on screen right now" on this one device.
    var challengeReveal by remember { mutableStateOf<ChallengeRevealSnapshot?>(null) }
    var showChallengeHand by remember { mutableStateOf(false) }
    LaunchedEffect(challengeReveal) {
        val snapshot = challengeReveal ?: return@LaunchedEffect
        showChallengeHand = false
        delay(650) // "CHALLENGE!" alone first, same beat as the reference
        showChallengeHand = true
        sounds.playTap() // the reveal's own resolution beat -- distinct single tap vs. the declaration's layeredChime above
        delay(1300) // then the accused hand lays face-up before resolving for real
        game.resolveChallenge(accept = false)
        challengeReveal = null
        showChallengeHand = false
    }

    var showUnoCallout by remember { mutableStateOf(false) }
    LaunchedEffect(showUnoCallout) {
        if (showUnoCallout) {
            delay(1100)
            showUnoCallout = false
        }
    }

    // playBotTurn() already calls callUno(botIndex) the instant a bot's hand hits
    // one card (UnoGame.kt) -- but until now nothing on screen reacted to it, so
    // only the human's own button tap ever showed the callout. That's backwards:
    // a human specifically wants to see an opponent's call as their cue to try a
    // Catch. Track each player's calledUno by id (not just index -- seats don't
    // move, but this stays correct if that ever changes) and fire the same
    // overlay the instant any of THEM flips it on, independent of the human path
    // above (which still fires immediately from its own button tap).
    var previousCalledUno by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(s.players) {
        val current = s.players.associate { it.playerId to it.calledUno }
        val botJustCalled = s.players.any { p ->
            p.isBot && p.calledUno && previousCalledUno[p.playerId] != true
        }
        if (botJustCalled) {
            showUnoCallout = true
            layeredChime(sounds, scope)
        }
        previousCalledUno = current
    }

    // Fly-to-discard-pile animation: a transient "ghost" of the card that was
    // just played, flying from wherever it actually was in the hand (reported
    // by FannedHand's onCardAboutToPlay, see that file's KDoc) to the discard
    // pile's own on-screen position, tracked below via onGloballyPositioned.
    // Purely presentation — game.playCard() already ran by the time this
    // starts, so the real state update (new top card, AnimatedContent's own
    // transition) has already landed; this overlay just fills the visual gap
    // of "where did the card in my hand actually go."
    // Real on-screen rectangles (root coordinates) of the things cards fly between. Sizes are
    // measured rather than assumed because the table sizes its cards from its own bounds, so a
    // flight has to start at the size of the card it leaves and land at the size of the card it
    // joins.
    var discardCardRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var drawPileRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // The pile's tap target is drawn without a ripple, so keyboard / D-pad focus (Tab S9 DeX, keyboard
    // cover) needs its own visible ring or Enter draws a card from something that shows no focus.
    val pileSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pileFocused by pileSource.collectIsFocusedAsState()
    var handAreaRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val seatRects = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }
    var flyingCard by remember { mutableStateOf<FlyingCard?>(null) }
    // Face-down cards traveling from the draw pile to opponents' seats (the deal, and opponent
    // draws); many at once, all advanced by one frame clock that only ticks while any are in
    // flight. See MiniFlight / MiniFlightsOverlay.
    val miniFlights = remember { mutableStateListOf<MiniFlight>() }
    val miniClock = remember { mutableLongStateOf(0L) }
    // Bumped on every launch so the frame loop below always restarts: keyed on "is the list empty" alone,
    // a flight added in the same frame the last one finished never woke the loop, and sat frozen.
    val miniKick = remember { mutableIntStateOf(0) }
    fun launchMini(flight: MiniFlight) {
        miniFlights += flight
        miniKick.intValue++
    }
    LaunchedEffect(miniKick.intValue) {
        while (miniFlights.isNotEmpty()) {
            withFrameNanos { miniClock.longValue = it / 1_000_000 }
            val now = miniClock.longValue
            miniFlights.removeAll { now >= it.startMs + it.durationMs }
        }
    }
    val flyProgress = remember { Animatable(0f) }
    LaunchedEffect(flyingCard) {
        val card = flyingCard ?: return@LaunchedEffect
        flyProgress.snapTo(0f)
        if (reducedMotion) {
            flyProgress.snapTo(1f)
        } else {
            // A bigger tumble (see tumbleTurnsFor in FlyingCardOverlay) needs a
            // touch more real time to actually read as tumbling rather than a
            // blur -- flat 280ms whenever `enhanced` is off, since no extra
            // spin is added then and the original timing should hold exactly.
            val flightMs = if (enhanced) when (card.rank) {
                UnoRank.WILD_DRAW_FOUR -> 420
                UnoRank.WILD, UnoRank.SKIP, UnoRank.REVERSE, UnoRank.DRAW_TWO -> 330
                else -> 280
            } else 280
            flyProgress.animateTo(1f, animationSpec = tween(flightMs))
        }
        flyingCard = null
    }
    // A brief "impact" pulse on the discard pile itself right as a tossed card
    // lands — the Xbox 360 UNO / modern mobile UNO reference point for this
    // whole pass both sell a card landing with actual weight, not just motion.
    // Triggered below, keyed on the discard pile's own instanceId change.
    val discardImpactPulse = remember { Animatable(1f) }

    // Dramatic-moment beats modeled on the two references the project owner
    // named directly: Xbox 360 UNO (2006, Gameloft) dramatically punches the
    // camera in on Wild Draw Four/UNO! moments and tosses cards onto the felt
    // table with real weight; the modern official mobile UNO app adds glossy
    // depth and a confetti burst on a round win. Skip/Reverse/Draw Two/Wild
    // Draw Four each get the same flash-text treatment UNO!/Wild Draw Four
    // already had (UnoCalloutOverlay's visual language), and Wild Draw Four
    // additionally gets the camera-punch zoom -- the single biggest swing in
    // the game deserves the single biggest beat.
    var actionFlash by remember { mutableStateOf<UnoRank?>(null) }
    // Bumped (not toggled) so the direction ring's own reversal-flourish
    // LaunchedEffect (see TurnDirectionRing) always sees a fresh key even if
    // two reversals land back-to-back before it finishes reacting to the
    // first -- a plain Boolean flip could coalesce two rapid REVERSE plays
    // into a single flourish. Console-era UNO's own turn-direction ring is
    // the single biggest confirmed visual gap this pass exists to close (see
    // GAP 1 in the brief) -- this is the trigger for it.
    var reversalSignal by remember { mutableStateOf(0) }
    val cameraPunch = remember { Animatable(1f) }
    // A few-px jittered translation layered onto cameraPunch's own scale for
    // Wild Draw Four's hit-stop beat below -- additive, cameraPunch's own
    // scale-punch is unchanged.
    val cameraShakeX = remember { Animatable(0f) }
    val cameraShakeY = remember { Animatable(0f) }
    var previousTopCardId by remember { mutableStateOf(s.topCard.instanceId) }
    // Who held which cards as of the previous state, so the effect below can tell whose hand a newly
    // landed top card came out of. A one-element array rather than state: it is only ever read and
    // written from effects, in declaration order, and must not itself trigger recomposition.
    val handSnapshot = remember { arrayOf(s.players.associate { it.playerId to it.hand.map { c -> c.instanceId }.toSet() }) }
    // The same moment's hand sizes and draw-pile size: card ids can be unusable (an online opponent's
    // cards are redacted to one placeholder id), sizes never are.
    val sizeSnapshot = remember { arrayOf(s.players.associate { it.playerId to it.hand.size }) }
    val pileSnapshot = remember { intArrayOf(s.drawPileSize) }
    val flightDensity = LocalDensity.current
    LaunchedEffect(s.topCard.instanceId) {
        if (s.topCard.instanceId == previousTopCardId) return@LaunchedEffect
        val rank = s.topCard.rank
        // Every played card now visibly travels: the local player's own flight starts in FannedHand
        // (flyingCard is already set by then), and this covers everyone else -- an opponent's or a
        // remote human's card takes off from their seat and lands on the pile at full size.
        val playedBySeat = s.players.indexOfFirst { p -> handSnapshot[0][p.playerId]?.contains(s.topCard.instanceId) == true }
            .takeIf { it >= 0 }
            // Ids can't say (redacted hands, or two updates landing in one frame): whoever's hand shrank.
            ?: s.players.indexOfFirst { p -> p.hand.size < (sizeSnapshot[0][p.playerId] ?: p.hand.size) }
        val seatRect = seatRects[playedBySeat]
        if (playedBySeat >= 0 && playedBySeat != myIndex && flyingCard == null && !reducedMotion &&
            seatRect != null && discardCardRect.width > 0f
        ) {
            val wPx = seatRect.width * 0.36f
            flyingCard = FlyingCard(
                visual = unoCardToVisual(s.topCard, colorblindMode = colorblindMode),
                start = Offset(seatRect.center.x - wPx / 2f, seatRect.center.y - wPx * (92f / 64f) / 2f),
                startRotationDeg = 0f,
                isWildRank = s.topCard.isWild,
                rank = rank,
                width = with(flightDensity) { wPx.toDp() }
            )
        }
        // Impact pulse: every landed card gets a small one, not just action cards.
        if (enhanced) {
            discardImpactPulse.snapTo(1f)
            discardImpactPulse.animateTo(1.12f, animationSpec = tween(90))
            discardImpactPulse.animateTo(1f, animationSpec = tween(160))
        }
        if (rank == UnoRank.WILD_DRAW_FOUR && enhanced) {
            // Hit-stop: a genuine hard pause on the biggest swing in the game,
            // immediately before its own flash/camera beat fires below.
            delay(42)
        }
        if (rank == UnoRank.SKIP || rank == UnoRank.REVERSE || rank == UnoRank.DRAW_TWO || rank == UnoRank.WILD_DRAW_FOUR) {
            actionFlash = rank
        }
        if (rank == UnoRank.WILD_DRAW_FOUR) {
            // STRONG_ACTION, not CELEBRATION -- CELEBRATION's own KDoc reserves it for
            // "genuinely rare events... using this for anything routine defeats the whole
            // point of having it," and a Wild Draw Four can land many times in one match
            // (audited finding: every +4 play felt identical to the actual match win, which
            // fires CELEBRATION separately above at this composable's own top via
            // `LaunchedEffect(s.matchOver)`). STRONG_ACTION's own KDoc ("a significant
            // action — a capture, a big play, a scored point") is the exact fit for the
            // biggest beat short of the win itself.
            haptics(HapticSignal.STRONG_ACTION) // timed to the hit-stop above
        }
        if (rank == UnoRank.REVERSE) {
            // Always fires regardless of `enhanced` -- this is the same haptic
            // a Reverse always gave before (previously NORMAL_ACTION from
            // onPlay, now ESCALATING from here instead, see that call site's
            // own comment) -- a motion setting shouldn't silence haptic
            // feedback for an ordinary rules event. Only the ring's VISUAL
            // flourish below is gated on enhanced.
            haptics(HapticSignal.ESCALATING)
            if (enhanced) reversalSignal++
        }
        if (rank == UnoRank.WILD_DRAW_FOUR && enhanced) {
            coroutineScope {
                launch {
                    cameraPunch.snapTo(1f)
                    cameraPunch.animateTo(1.05f, animationSpec = tween(160))
                    cameraPunch.animateTo(1f, animationSpec = tween(240))
                }
                // The shared com.gamesuite.ui.effects.Animatable.shakeSteps extension (see its
                // own KDoc) -- its default pattern IS this screen's own pre-migration
                // jitterShake step list verbatim, so this is a lossless, drop-in replacement.
                launch { cameraShakeX.shakeSteps(magnitudePx = 6f) }
                launch { cameraShakeY.shakeSteps(magnitudePx = 4f) }
            }
        }
        previousTopCardId = s.topCard.instanceId
    }
    // Declared after the top-card effect above on purpose: both restart on the same state change, and
    // the flight logic there has to read the PREVIOUS hands before this overwrites them.
    var lastRoundSeen by remember { mutableStateOf(s.roundNumber) }
    LaunchedEffect(s.players) {
        // An opponent who just took cards (a draw, or a +2/+4 penalty) gets that many cards flown
        // from the draw pile to their seat. Skipped on a new round (the deal has its own flights),
        // for the local seat (its own draw flight lives in the hand), and under reduced motion.
        val isNewRound = s.roundNumber != lastRoundSeen
        lastRoundSeen = s.roundNumber
        // Cards only leave the draw pile for a hand when the pile itself moved; a hand swap (or anything
        // else that reshuffles who holds what) leaves it alone and must not fly cards from the deck.
        val pileMoved = s.drawPileSize != pileSnapshot[0]
        if (!isNewRound && pileMoved && enhanced && !reducedMotion && drawPileRect.width > 0f) {
            s.players.forEachIndexed { index, p ->
                val previous = handSnapshot[0][p.playerId] ?: return@forEachIndexed
                val idGained = p.hand.count { it.instanceId !in previous }
                val sizeGained = p.hand.size - (sizeSnapshot[0][p.playerId] ?: p.hand.size)
                val gained = maxOf(idGained, sizeGained)
                val seat = seatRects[index]
                if (index != myIndex && gained > 0 && seat != null) {
                    val nowMs = System.nanoTime() / 1_000_000
                    repeat(gained.coerceAtMost(6)) { i ->
                        launchMini(
                            MiniFlight(
                                from = drawPileRect.center,
                                to = seat.center,
                                fromWidthPx = drawPileRect.width,
                                toWidthPx = seat.width * 0.3f,
                                startMs = nowMs + i * 90L
                            )
                        )
                    }
                }
            }
        }
        handSnapshot[0] = s.players.associate { it.playerId to it.hand.map { c -> c.instanceId }.toSet() }
        sizeSnapshot[0] = s.players.associate { it.playerId to it.hand.size }
        pileSnapshot[0] = s.drawPileSize
    }
    LaunchedEffect(actionFlash) {
        if (actionFlash != null) {
            delay(850)
            actionFlash = null
        }
    }

    // Draw-pile visual: a face-down stack near the Draw button, tracked so a
    // drawn card can fly from there into the hand, flipping face-up as it
    // settles -- the one flourish UnoState.drawPileSize existed for
    // (maintained through every draw/reshuffle in UnoGame.kt) but had zero
    // read sites anywhere on this screen until now.
    var handCardWidth by remember { mutableStateOf(64.dp) }
    var drawnCardFlight by remember { mutableStateOf<DrawnCardFlight?>(null) }
    val drawFlightProgress = remember { Animatable(0f) }
    var previousHandIds by remember { mutableStateOf(myHand.map { it.instanceId }.toSet()) }
    LaunchedEffect(myHand.map { it.instanceId }) {
        val currentIds = myHand.map { it.instanceId }.toSet()
        val newCards = myHand.filter { it.instanceId !in previousHandIds }
        // Only animate a genuine single voluntary draw -- the initial 7-card
        // deal is handled by its own staggered-deal beat below, and a 2/4-card
        // penalty draw is cheaper to let land via FannedHand's own dealTrigger
        // stagger than to choreograph several simultaneous flights for.
        if (newCards.size == 1 && enhanced) {
            drawnCardFlight = DrawnCardFlight(newCards.first())
        }
        previousHandIds = currentIds
    }
    LaunchedEffect(drawnCardFlight) {
        if (drawnCardFlight != null) {
            drawFlightProgress.snapTo(0f)
            drawFlightProgress.animateTo(1f, animationSpec = tween(380))
            drawnCardFlight = null
        }
    }

    // Staggered initial deal: dealNewRound() commits every hand in one state
    // update, so hands used to just appear fully formed on the same frame.
    // Keyed on roundNumber so this fires for the opening deal and every
    // redeal alike; FannedHand's own dealTrigger stagger handles the actual
    // per-card entrance (see that file's KDoc) since it already lands each
    // card at its real fan-slot position with no separate flight to track.
    // isDealing gates whether the render site below trusts the animated
    // opponentDealCounts snapshot or just shows each player's real, live hand
    // size -- without this, opponentDealCounts would go stale the instant a
    // player draws or plays a card any time AFTER the deal stagger finishes
    // (it's a one-shot animation output, not a live mirror of hand size).
    var isDealing by remember { mutableStateOf(false) }
    var opponentDealCounts by remember { mutableStateOf(s.players.map { it.hand.size }) }
    LaunchedEffect(s.roundNumber) {
        if (!enhanced) return@LaunchedEffect
        isDealing = true
        opponentDealCounts = s.players.map { 0 }
        // The deal, made visible: one face-down card leaves the draw pile for each opponent, round
        // robin in seat order, 55ms per round -- the same cadence the count chips tick at below, which
        // now start one flight-time later so a chip only counts a card once it has actually landed.
        // (One frame first, so the seats and pile have been laid out and their rects are known.)
        androidx.compose.runtime.withFrameNanos { }
        if (!reducedMotion && drawPileRect.width > 0f) {
            val nowMs = System.nanoTime() / 1_000_000
            val n = s.players.size
            val order = if (myIndex in s.players.indices) (1 until n).map { (myIndex + it) % n } else s.players.indices.toList()
            val rounds = s.players.maxOfOrNull { it.hand.size } ?: 0
            for (r in 0 until rounds) {
                order.forEachIndexed { j, idx ->
                    val seat = seatRects[idx] ?: return@forEachIndexed
                    if (r < s.players[idx].hand.size) {
                        launchMini(
                            MiniFlight(
                                from = drawPileRect.center,
                                to = seat.center,
                                fromWidthPx = drawPileRect.width,
                                toWidthPx = seat.width * 0.3f,
                                startMs = nowMs + r * 55L + j * (55L / order.size.coerceAtLeast(1))
                            )
                        )
                    }
                }
            }
            delay(300)
        }
        for (round in 1..(s.players.maxOfOrNull { it.hand.size } ?: 0)) {
            delay(55)
            opponentDealCounts = s.players.map { p -> minOf(round, p.hand.size) }
        }
        isDealing = false
    }

    // On a book-posture foldable (Z Fold 5 unfolded), the table (opponents,
    // discard pile, draw/UNO buttons) renders on one side of the hinge and
    // your hand on the other — like sitting across a real card table with
    // your cards close to you. On any non-separating device (Tab S9, the
    // Fold's cover screen, a phone) this collapses to the original single
    // stacked layout automatically, no separate code path needed.
    UnoBackground(dangerLevel = animatedTension) {
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
        primary = {
            // The table: a felt oval that fills this pane, opponents seated around its rim (in
            // turn order, starting with whoever plays after you), the piles and actions in the
            // middle. Every size below is derived from this pane's own bounds -- the previous
            // layout pinned fixed-size content to the top of the pane, which on a tall window
            // left the whole middle of the screen empty.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tableW = maxWidth
                val availH = maxHeight
                // Below this height the seats, piles and buttons stop fitting without overlapping
                // (Fold cover screen rotated to landscape is the tightest real case), so the table
                // keeps a floor and scrolls instead.
                val tableH = maxOf(availH, 300.dp)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (tableH > availH) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                ) {
                    Box(
                        modifier = Modifier
                            .width(tableW)
                            .height(tableH)
                            // Same resting perspective tilt every other board screen applies,
                            // scoped to the table (not the hand's drag surface).
                            .let { if (card3D) it.tablePerspectiveTilt() else it }
                            // Camera-punch zoom + hit-stop shake on the table for a Wild Draw
                            // Four landing -- see the cameraPunch/cameraShake Animatables above.
                            .graphicsLayer {
                                scaleX = cameraPunch.value
                                scaleY = cameraPunch.value
                                translationX = cameraShakeX.value
                                translationY = cameraShakeY.value
                            }
                    ) {
                        // ---- Metrics, all in dp, all derived from the pane (see computeTableLayout, which is unit tested) ----
                        val userScale = cardPreferenceScale(cardScale, LocalConfiguration.current.screenWidthDp)
                        // Opponents in turn order starting after the local seat, so the next player
                        // sits on your left and play travels clockwise around the table; a
                        // spectator (no local seat) just sees everyone in seat order.
                        val opponents: List<Int> = if (myIndex in s.players.indices) {
                            (1 until s.players.size).map { (myIndex + it) % s.players.size }
                        } else {
                            s.players.indices.toList()
                        }
                        val seatCount = opponents.size
                        val layout = remember(tableW, tableH, seatCount, userScale) {
                            computeTableLayout(tableW.value, tableH.value, seatCount, userScale)
                        }
                        val discardW = layout.discardW.dp
                        val discardH = layout.discardH.dp
                        val drawW = layout.drawW.dp
                        val drawH = drawW * (104f / 72f)
                        val ringScale = discardW.value / 72f
                        val seatScale = layout.seatScale
                        val seatWUnit = SEAT_W_UNIT.dp
                        val feltLeft = layout.feltLeft.dp
                        val feltRight = layout.feltRight.dp
                        val feltTop = layout.feltTop.dp
                        val feltBottom = layout.feltBottom.dp
                        val cx = tableW / 2f
                        val clusterYFrac = layout.clusterYFrac
                        TableFelt(left = feltLeft, top = feltTop, right = feltRight, bottom = feltBottom)

                        // ---- Seats around the rim ----
                        opponents.forEachIndexed { k, index ->
                            val p = s.players[index]
                            val isTurn = index == s.currentPlayerIndex
                            // Team tint -- teamId already exists on UnoPlayerState (>=0 only when
                            // rules.teamPlay is on); keyed on it, not seat index, so teammates read
                            // as grouped wherever they sit.
                            val teamColor = p.teamId.takeIf { it >= 0 }?.let { teamColors[it % teamColors.size] }
                            // "Hot streak" border from hand size (no new information leak: every
                            // seat's hand size is already on screen). Danger beats team tint.
                            val seatBorder = when {
                                p.hand.size <= 1 -> Color(0xFFE5544D) to { dangerAlpha(0.5f, 0.4f) }
                                p.hand.size == 2 -> Color(0xFFFFA726) to { dangerAlpha(0.35f, 0.25f) }
                                teamColor != null -> teamColor to { 0.5f }
                                else -> null
                            }
                            // Who owes a pending +2/+4 stack: exactly the current player (the same
                            // condition UnoGame.drawCard()/playCard() resolve on).
                            val owesDraw = isTurn && s.pendingDraw > 0
                            val boxW = layout.seatW.dp
                            val boxH = layout.seatH.dp
                            val slot = layout.seats[k]
                            val px = slot.cx.dp
                            val py = slot.cy.dp
                            Box(
                                modifier = Modifier
                                    .size(boxW, boxH)
                                    .offset(x = px - boxW / 2f, y = py - boxH / 2f)
                                    // Where an opponent's played card takes off from.
                                    .onGloballyPositioned { seatRects[index] = it.boundsInRoot() },
                                contentAlignment = Alignment.Center
                            ) {
                                OpponentSeat(
                                    player = p,
                                    seatIndex = index,
                                    handCount = if (isDealing) opponentDealCounts.getOrElse(index) { p.hand.size } else p.hand.size,
                                    isTurn = isTurn,
                                    turnGlow = { dangerAlpha(0.75f, 0.25f) },
                                    teamColor = teamColor,
                                    border = seatBorder,
                                    owedDraw = if (owesDraw) s.pendingDraw else 0,
                                    seatScale = seatScale,
                                    // Capped: a big Card-size setting must not grow the fan past the plate
                                    // the layout reserved for it.
                                    cardScale = minOf(userScale * 1.06f, 1.092f),
                                    score = s.cumulativeScores[p.playerId] ?: 0,
                                    // Crowded narrow tables use the compact plate: the count chip already says
                                    // how many cards, and there is no room for the fan.
                                    showFan = !layout.compact
                                )
                            }
                        }

                        // ---- Your own seat, on the near rim ----
                        // Completes the ring: the hand itself lives in the pane below the table, but
                        // your identity, hand size and turn glow belong at the table with everyone
                        // else's. Skipped on windows too short to fit it without crowding the piles.
                        if (myIndex in s.players.indices && tableH >= 420.dp) {
                            val me = s.players[myIndex]
                            val selfW = seatWUnit * seatScale
                            val selfH = 96.dp * seatScale
                            Box(
                                modifier = Modifier
                                    .size(selfW, selfH)
                                    .offset(x = cx - selfW / 2f, y = feltBottom - 18.dp - selfH),
                                contentAlignment = Alignment.Center
                            ) {
                                OpponentSeat(
                                    player = me,
                                    seatIndex = myIndex,
                                    handCount = me.hand.size,
                                    isTurn = s.currentPlayerIndex == myIndex,
                                    turnGlow = { dangerAlpha(0.75f, 0.25f) },
                                    teamColor = me.teamId.takeIf { it >= 0 }?.let { teamColors[it % teamColors.size] },
                                    border = null,
                                    owedDraw = 0,
                                    seatScale = seatScale,
                                    cardScale = minOf(userScale * 1.06f, 1.092f),
                                    showFan = false,
                                    score = s.cumulativeScores[me.playerId] ?: 0
                                )
                            }
                        }

                        // ---- Center of the table: piles, status, actions ----
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = tableH * clusterYFrac),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(discardW * 0.14f)
                            ) {
                                // Draw pile: a face-down stack UnoState.drawPileSize already tracked
                                // through every draw/reshuffle. Its own position feeds the drawn-card
                                // flight overlay below.
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .onGloballyPositioned { drawPileRect = it.boundsInRoot() }
                                        // Tapping the pile itself draws, like every UNO video game --
                                        // the Draw button below stays for discoverability. Same action
                                        // and feedback as the button (the engine validates whose turn).
                                        // Only live on the player's own turn, so a stray tap in an opponent's
                                        // turn doesn't play the draw sound and buzz for a draw that never happens.
                                        .then(if (pileFocused) Modifier.border(2.dp, panelGold, RoundedCornerShape(10.dp)) else Modifier)
                                        .clickable(
                                            enabled = canDraw,
                                            onClickLabel = if (s.pendingDraw > 0) "Draw ${s.pendingDraw} cards" else "Draw a card",
                                            role = androidx.compose.ui.semantics.Role.Button,
                                            interactionSource = pileSource,
                                            indication = null
                                        ) {
                                            game.drawCard(myIndex)
                                            sounds.playDraw()
                                            haptics(HapticSignal.LIGHT_TICK)
                                        }
                                ) {
                                    if (enhanced) {
                                        PlayingCardView(
                                            card = CardVisual(id = -2, label = "", backgroundColor = Color.Transparent, faceDown = true, style = CardStyle.UNO),
                                            width = drawW,
                                            height = drawH,
                                            showThickness = card3D,
                                            modifier = Modifier.offset(x = 3.dp, y = 3.dp).graphicsLayer { alpha = 0.6f }
                                        )
                                    }
                                    PlayingCardView(
                                        card = CardVisual(id = -1, label = "", backgroundColor = Color.Transparent, faceDown = true, style = CardStyle.UNO),
                                        width = drawW,
                                        height = drawH,
                                        showThickness = card3D,
                                        modifier = Modifier.semantics { contentDescription = "Draw pile, ${s.drawPileSize} cards" }
                                    )
                                    Text(
                                        "${s.drawPileSize}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 6.dp, y = 6.dp)
                                            .background(Color(0xFF1D1440), RoundedCornerShape(50))
                                            .border(1.dp, Color(0xFF6C5CE7), RoundedCornerShape(50))
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                                // Discard pile -- the top card animates in on change. The turn-direction
                                // ring rides directly behind the pile itself (see TurnDirectionRing's own
                                // KDoc); its size follows the pile's.
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                ) {
                                    // The ring is decoration around the pile, bigger than the card; measured
                                    // unbounded inside a card-sized slot so it never adds to the pile's layout
                                    // footprint (which the seats are laid out around).
                                    Box(
                                        modifier = Modifier.size(discardW, discardH),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(modifier = Modifier.wrapContentSize(Alignment.Center, unbounded = true)) {
                                            TurnDirectionRing(
                                                direction = s.direction,
                                                enhanced = enhanced,
                                                reversalSignal = reversalSignal,
                                                cardScale = ringScale
                                            )
                                        }
                                    }
                                    // The pile's landing target: an invisible spacer exactly the size of
                                    // the top card, so the fly-to-discard animation lands on the card
                                    // itself (the wrapper is bigger -- it includes the direction ring)
                                    // and stays stable across the top-card swap animation.
                                    Spacer(
                                        Modifier
                                            .size(discardW, discardH)
                                            .onGloballyPositioned { discardCardRect = it.boundsInRoot() }
                                    )
                                    // The pile itself: the last few cards played stay visible under the
                                    // top card, each turned and nudged by a fixed amount derived from
                                    // its own id, so it reads as a casually thrown pile rather than a
                                    // ruler-straight stack. (Deterministic per card, so a card never
                                    // re-shuffles its pose as newer ones land on it.)
                                    val pileDepth = minOf(4, s.discardPile.size - 1)
                                    for (depth in pileDepth downTo 1) {
                                        val under = s.discardPile[s.discardPile.size - 1 - depth]
                                        val tilt = ((under.instanceId * 37) % 41 - 20) / 20f * 17f
                                        val dx = ((under.instanceId * 13) % 21 - 10) / 10f * 0.07f
                                        val dy = ((under.instanceId * 29) % 21 - 10) / 10f * 0.05f
                                        PlayingCardView(
                                            card = unoCardToVisual(under, colorblindMode = colorblindMode),
                                            width = discardW,
                                            height = discardH,
                                            showThickness = false,
                                            modifier = Modifier
                                                .offset(x = discardW * dx, y = discardH * dy)
                                                .rotate(tilt)
                                                .graphicsLayer { alpha = 0.92f }
                                        )
                                    }
                                    AnimatedContent(
                                        targetState = s.topCard.instanceId,
                                        transitionSpec = {
                                            // Settings -> Accessibility -> Reduced Motion: swap the scale/fade
                                            // transition for a near-instant one instead of skipping the setting.
                                            if (reducedMotion) {
                                                fadeIn(tween(1)).togetherWith(fadeOut(tween(1)))
                                            } else {
                                                (scaleIn(animationSpec = tween(220), initialScale = 0.6f) + fadeIn(tween(220)))
                                                    .togetherWith(scaleOut(animationSpec = tween(150), targetScale = 0.8f) + fadeOut(tween(150)))
                                            }
                                        },
                                        label = "discardPile",
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = discardImpactPulse.value
                                            scaleY = discardImpactPulse.value
                                        }
                                    ) { _ ->
                                        PlayingCardView(
                                            card = unoCardToVisual(s.topCard, overrideColor = s.currentColor, colorblindMode = colorblindMode),
                                            width = discardW,
                                            height = discardH,
                                            showThickness = card3D,
                                            modifier = Modifier
                                                .semantics {
                                                    contentDescription = "Discard pile: ${s.topCard.accessibleDescription(colorOverride = s.currentColor)}"
                                                }
                                                // Shader gloss/foil scoped to just the two Wild ranks.
                                                .specularSweep(enabled = card3D && s.topCard.isWild, tint = panelGold)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            // The running play-by-play ("Sam played Red 7", "Skip!"). liveRegion: a
                            // screen-reader user otherwise has to re-explore after every move.
                            Box(
                                modifier = Modifier
                                    .widthIn(max = tableW * 0.8f)
                                    .background(Color(0xFF120C24).copy(alpha = 0.72f), RoundedCornerShape(50))
                                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                                    .padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    s.lastAction,
                                    color = Color.White.copy(alpha = 0.92f),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                )
                            }
                            // Whose turn it is and what they owe (the seat itself only glows), for a
                            // spectator or when an opponent has to answer a stack/challenge.
                            val actor = s.players.getOrNull(s.currentPlayerIndex)
                            if (actor != null && s.currentPlayerIndex != myIndex && !s.roundOver) {
                                Spacer(Modifier.height(6.dp))
                                TurnTag(text = "${actor.displayName}: ${turnPrompt(s)}")
                            }

                            Spacer(Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        // Idle touch: a gentle nudge after 6-8s of inactivity on
                                        // the player's own turn -- see idleNudgeY above.
                                        .graphicsLayer { translationY = idleNudgeY.value }
                                        // Same shared tension-pulse the seats' danger borders use,
                                        // generalized to the stacking pendingDraw moment.
                                        .then(
                                            if (s.pendingDraw > 0) {
                                                Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .drawWithContent {
                                                        drawContent()
                                                        val stroke = 1.5.dp.toPx()
                                                        drawRoundRect(
                                                            color = Color(0xFFFFA726).copy(alpha = dangerAlpha(0.5f, 0.4f).coerceIn(0f, 1f)),
                                                            topLeft = Offset(stroke / 2f, stroke / 2f),
                                                            size = Size(size.width - stroke, size.height - stroke),
                                                            cornerRadius = CornerRadius(size.height / 2f - stroke / 2f),
                                                            style = Stroke(stroke)
                                                        )
                                                    }
                                            } else Modifier
                                        )
                                ) {
                                    Button(
                                        onClick = {
                                            game.drawCard(myIndex)
                                            // Same rule as tapping the pile: no draw sound or buzz unless a draw can happen.
                                            if (canDraw) {
                                                sounds.playDraw()
                                                haptics(HapticSignal.LIGHT_TICK)
                                            }
                                        },
                                        // The theme's default primary reads washed-out against the felt;
                                        // this is the same violet the UNO panels' borders already use.
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7), contentColor = Color.White),
                                        modifier = Modifier.heightIn(min = 44.dp).pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Text(if (s.pendingDraw > 0) "Draw ${s.pendingDraw}" else "Draw", fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (myHand.size == 1) {
                                    Button(
                                        onClick = {
                                            game.callUno(myIndex)
                                            showUnoCallout = true
                                            layeredChime(sounds, scope)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                                        modifier = Modifier.heightIn(min = 44.dp).pointerHoverIcon(PointerIcon.Hand)
                                    ) { Text("UNO!", fontWeight = FontWeight.Black) }
                                }
                                // Official UNO: playing a card you just drew is your OPTION, not mandatory --
                                // only offered when the house rule doesn't force it (see
                                // UnoRules.forcePlayDrawnCard and UnoGame.keepDrawnCard()).
                                if (myTurn && s.awaitingDrawDecision && !game.rules.forcePlayDrawnCard) {
                                    OutlinedButton(
                                        onClick = {
                                            game.keepDrawnCard(myIndex)
                                            haptics(HapticSignal.LIGHT_TICK)
                                        },
                                        modifier = Modifier.heightIn(min = 44.dp).pointerHoverIcon(PointerIcon.Hand)
                                    ) { Text("Keep card") }
                                }
                            }
                        }

                        // ---- Catch! buttons ----
                        // Table-level siblings, drawn last: inside a seat they sat within a fixed-size slot, and a
                        // child poking outside its parent's bounds is not hit-tested -- the button drew fine but
                        // part of it went dead. Here nothing clips it. index != myIndex / a real local seat: never
                        // let the human (or a spectator) catch on their own behalf. The window check mirrors the
                        // engine's own catch-window rule (see UnoPlayerState.catchWindowClosesAfterPlayerIndex) so
                        // the button disappears the instant it would stop doing anything.
                        if (myIndex in s.players.indices) {
                            opponents.forEachIndexed { k, index ->
                                val p = s.players[index]
                                if (index != myIndex && p.hand.size == 1 && !p.calledUno &&
                                    p.catchWindowClosesAfterPlayerIndex == s.currentPlayerIndex
                                ) {
                                    val slot = layout.seats[k]
                                    Box(
                                        modifier = Modifier
                                            .offset(x = slot.cx.dp - 45.dp, y = slot.cy.dp + layout.seatH.dp / 2f - 30.dp)
                                            .width(90.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Button(
                                            onClick = {
                                                // Check the live state at the moment of the tap, not the
                                                // composition's captured `p` -- a bot can call UNO (or the
                                                // window can close) in the gap between render and tap, and the
                                                // "successful Catch" feedback should only fire for a real catch.
                                                val live = game.state.value
                                                val caught = live?.players?.getOrNull(index)?.let {
                                                    it.hand.size == 1 && !it.calledUno &&
                                                        it.catchWindowClosesAfterPlayerIndex == live.currentPlayerIndex
                                                } == true
                                                game.catchUnoFailure(accuserIndex = myIndex, targetIndex = index)
                                                if (caught) {
                                                    haptics(HapticSignal.STRONG_ACTION)
                                                    sounds.playTap()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5544D), contentColor = Color.White),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            // Mouse/trackpad hover cursor (Tab S9 DeX / keyboard-cover, doc 4c) -- purely
                                            // additive, no effect on touch.
                                            modifier = Modifier.heightIn(min = 36.dp).pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Text("Catch!", fontWeight = FontWeight.Black, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        secondary = {
            // Your hand: card size follows the pane's width (and a share of the window height), so a
            // tablet gets real cards instead of phone-sized ones, and the fan is centered under the
            // table. The user's Card size setting still scales it proportionally.
            // On a foldable in book/tabletop posture this pane is a whole half of the screen rather than
            // a strip under the table, so the hand is centered in it; in the ordinary stacked layout the
            // pane must stay wrap-height (a fill-height root here would starve the table above it).
            val foldState = LocalFoldState.current
            val splitPane = foldState.isBookPosture || foldState.isTabletopPosture
            BoxWithConstraints(
                modifier = (if (splitPane) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    // The drawn-card flight's actual landing target -- tracked on this whole pane
                    // rather than any one card slot, since the exact fan position of a not-yet-added
                    // card can't be known in advance.
                    .onGloballyPositioned { handAreaRect = it.boundsInRoot() }
            ) {
                val paneW = maxWidth
                val screenH = LocalConfiguration.current.screenHeightDp.dp
                val handSize = myHand.size.coerceAtLeast(1)
                val userHandScale = cardPreferenceScale(cardScale, LocalConfiguration.current.screenWidthDp)
                val availFan = paneW - 32.dp
                var handCardW = (paneW * 0.15f).coerceIn(64.dp, 116.dp) * userHandScale
                val maxHandH = screenH * 0.23f
                if (handCardW * (92f / 64f) > maxHandH) handCardW = maxHandH * (64f / 92f)
                // Spread the cards as loosely as the width allows (more of each face showing),
                // tightening toward the classic fan as the hand grows, and only then shrinking the
                // cards themselves; past that the fan scrolls.
                var handOverlap = 0.72f
                if (handSize > 1 && handCardW + handCardW * handOverlap * (handSize - 1) > availFan) {
                    handOverlap = ((availFan - handCardW) / (handCardW * (handSize - 1))).coerceIn(0.5f, 0.72f)
                    if (handCardW + handCardW * handOverlap * (handSize - 1) > availFan) {
                        handCardW = availFan / (1f + handOverlap * (handSize - 1))
                    }
                }
                handCardW = handCardW.coerceAtLeast(44.dp)
                val handCardWFinal = handCardW
                // Flights (a drawn card landing in the hand) read this to arrive at the hand's own size.
                SideEffect { handCardWidth = handCardWFinal }
                Column(
                    modifier = (if (splitPane) Modifier.align(Alignment.Center) else Modifier).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (myTurn) {
                            // Every card is dimmed when none can be played -- say what to do about it.
                            val stuck = !s.awaitingDrawDecision && myHand.none { game.canPlay(it) }
                            TurnTag(
                                text = when {
                                    stuck && s.pendingDraw > 0 -> "Nothing to stack — draw ${s.pendingDraw}"
                                    stuck -> "Nothing to play — draw a card"
                                    else -> "${turnPrompt(s)} — drag a card up to play it"
                                }
                            )
                        } else {
                            Text("Your hand", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        // Same badge treatment as the seats above, so a pending +2/+4 obligation reads
                        // as one indicator across both sides of the table.
                        if (s.currentPlayerIndex == myIndex && s.pendingDraw > 0) {
                            Spacer(Modifier.width(8.dp))
                            PendingDrawBadge(count = s.pendingDraw)
                        }
                    }

                    // Edge cards in the fan rise above its top line, so leave room for them.
                    Spacer(Modifier.height(16.dp))

                    FannedHand(
                        items = myHand,
                        idOf = { it.instanceId },
                        visualOf = { unoCardToVisual(it, colorblindMode = colorblindMode) },
                        enabled = myTurn,
                        // FannedHand multiplies by the user's card scale itself, so hand the
                        // unscaled size that lands on the width computed above.
                        cardWidth = handCardW / cardScale,
                        cardHeight = handCardW / cardScale * (92f / 64f),
                        overlapFraction = handOverlap,
                        // Playable cards rise and the rest dim while it's your turn -- the engine's own
                        // legality check, so the highlight can never disagree with what would be accepted.
                        isPlayable = { card -> game.canPlay(card) },
                        // Jump-in (fully engine-complete in UnoGame.jumpIn(); this is its UI path):
                        // mirrors jumpIn()'s own guard so a card only lights up as jump-in-able when
                        // the engine would accept it -- an exact color+rank match of the top card,
                        // off-turn, and not mid color-choice/challenge.
                        alsoEnabledFor = { card ->
                            game.rules.jumpIn && !myTurn && !s.awaitingColorChoice && !s.awaitingChallenge &&
                                card.color == s.topCard.color && card.rank == s.topCard.rank
                        },
                        onPlay = { card ->
                            // myTurn is already known false for any card that only became tappable
                            // via alsoEnabledFor above -- jumpIn() itself re-validates everything
                            // (including re-checking rules.jumpIn) before touching state, so this
                            // routing is a convenience, not the actual authority.
                            if (myTurn) game.playCard(myIndex, card) else game.jumpIn(myIndex, card)
                            layeredPlace(sounds, scope, rate = placeRateFor(card.rank))
                            // Haptic vocabulary via the shared Haptics.kt -- LIGHT_TICK for a plain
                            // number card, NORMAL_ACTION/STRONG_ACTION for an action card. Wild Draw
                            // Four and Reverse get no haptic here: theirs fire from the topCard-change
                            // effect above, timed to the hit-stop / ring-reversal beat rather than
                            // the instant of the tap.
                            when {
                                card.rank == UnoRank.WILD_DRAW_FOUR -> {}
                                card.rank == UnoRank.REVERSE -> {}
                                card.rank == UnoRank.DRAW_TWO -> haptics(HapticSignal.STRONG_ACTION)
                                card.rank == UnoRank.SKIP || card.rank == UnoRank.WILD ->
                                    haptics(HapticSignal.NORMAL_ACTION)
                                else -> haptics(HapticSignal.LIGHT_TICK)
                            }
                        },
                        onCardAboutToPlay = { card, visual, startPos, startRotation ->
                            flyingCard = FlyingCard(visual, startPos, startRotation, isWildRank = card.isWild, rank = card.rank, width = handCardWFinal)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        // Each card in the player's own hand announces its identity
                        // ("Red Seven", "Wild Draw Four", "Skip") -- see accessibleDescription().
                        descriptionOf = { it.accessibleDescription() },
                        // Staggered deal-in -- see FannedHand's own dealTrigger KDoc. null (not just
                        // gated on `enhanced` alone) so a card added mid-round by an ordinary draw
                        // never replays the whole hand's entrance.
                        dealTrigger = if (enhanced) s.roundNumber else null
                    )
                }
            }
        }
    )

    if (s.awaitingColorChoice && s.currentPlayerIndex == myIndex) {
        ColorPickerDialog(onColorChosen = { game.chooseColor(it) })
    }

    // challengeReveal == null: this device isn't mid-animation, so the normal
    // Accept/Challenge decision dialog is still live. Once "Challenge!" is
    // tapped, onChallenge captures a snapshot instead of resolving immediately
    // — the LaunchedEffect above plays the CHALLENGE!/hand-reveal beat first,
    // then calls resolveChallenge() for real. Accept has no equivalent
    // fanfare in the reference, so it still resolves instantly.
    if (s.awaitingChallenge && s.challengeVictimIndex == myIndex && challengeReveal == null) {
        val playedByIndex = s.challengePlayedByIndex
        val playedBy = playedByIndex?.let { s.players.getOrNull(it) }
        ChallengeDialog(
            playedByName = playedBy?.displayName ?: "them",
            onAccept = { game.resolveChallenge(accept = true) },
            onChallenge = {
                if (playedBy != null) {
                    // Audited gap: the whole Challenge flow (declaration through
                    // reveal-resolves) previously had a dedicated haptic-free visual
                    // beat (ChallengeFlashOverlay) but no sound at all. layeredChime,
                    // not layeredPlace -- this is a declaration, not a card landing.
                    layeredChime(sounds, scope)
                    challengeReveal = ChallengeRevealSnapshot(playedBy.displayName, playedBy.hand)
                } else {
                    game.resolveChallenge(accept = false)
                }
            }
        )
    }

    challengeReveal?.let { snapshot ->
        ChallengeFlashOverlay(snapshot = snapshot, showHand = showChallengeHand, cardScale = cardScale, use3D = card3D, colorblindMode = colorblindMode)
    }

    if (showUnoCallout) {
        UnoCalloutOverlay()
    }

    flyingCard?.let { card ->
        FlyingCardOverlay(
            card = card,
            targetCenter = discardCardRect.center,
            targetWidthPx = discardCardRect.width,
            progressState = flyProgress.asState(),
            use3D = card3D,
            enhanced = enhanced
        )
    }

    // Unconditional: the overlay reads the list itself, so a flight starting or ending recomposes only it.
    MiniFlightsOverlay(flights = miniFlights, clock = miniClock)

    drawnCardFlight?.let { flight ->
        DrawPileFlightOverlay(
            card = flight.card,
            startRect = drawPileRect,
            targetCenter = handAreaRect.center,
            targetWidthPx = with(LocalDensity.current) { handCardWidth.toPx() },
            progressState = drawFlightProgress.asState(),
            use3D = card3D,
            colorblindMode = colorblindMode
        )
    }

    actionFlash?.let { rank ->
        ActionCardFlashOverlay(rank = rank)
    }
    }
}

/**
 * [colorblindMode] threads Settings -> Accessibility -> Colorblind-safe mode through explicitly
 * (read once via `LocalColorblindMode.current` by whichever @Composable actually calls this,
 * same "one source of truth, passed down" convention [LocalCardScale]'s own KDoc already
 * establishes for `cardScale` on this screen) rather than reading the CompositionLocal in here
 * directly — this stays a plain, non-@Composable function precisely so every existing call site
 * keeps working unchanged. Audited finding: colorblind mode previously only drew a glyph inside
 * [ColorPickerDialog]'s own four swatches -- every other card on screen (hand, discard pile,
 * opponent plays) relied on hue alone for the rest of a match, the worst case for exactly the
 * red/green confusion this setting exists to cover. [colorblindGlyph] carries no entry for
 * [UnoColor.WILD] (a neutral dark gray, never one of the four confusable suit colors), so an
 * unresolved Wild correctly never gets a glyph regardless of this flag.
 */
private fun unoCardToVisual(card: UnoCard, overrideColor: UnoColor? = null, colorblindMode: Boolean = false): CardVisual {
    // Skip/Reverse/Wild are drawn as vector marks (see CardGlyph) instead of words -- "Reverse"
    // set in a corner index was unreadable -- so only the number/+2/+4 ranks carry text. The
    // same short string serves the big center mark and the mirrored corner marks, like the
    // printed deck.
    val glyph = when (card.rank) {
        UnoRank.SKIP -> CardGlyph.SKIP
        UnoRank.REVERSE -> CardGlyph.REVERSE
        UnoRank.WILD -> CardGlyph.WILD
        else -> CardGlyph.TEXT
    }
    val mark = when (card.rank) {
        UnoRank.DRAW_TWO -> "+2"
        UnoRank.WILD_DRAW_FOUR -> "+4"
        else -> card.displayLabel()
    }
    return CardVisual(
        id = card.instanceId,
        label = mark,
        backgroundColor = colorFor(overrideColor ?: card.color),
        cornerIndex = mark,
        colorblindGlyph = if (colorblindMode) colorblindGlyph[overrideColor ?: card.color] else null,
        style = CardStyle.UNO,
        glyph = glyph
    )
}

/** Spoken-out-loud form of [UnoRank]'s number ranks — [UnoCard.displayLabel] only
 *  needs a single glyph ("7") for the compact on-card corner index, but a
 *  screen reader needs the actual word for it to read sensibly. Index == rank.ordinal
 *  for every number rank (see UnoCard.isNumber). */
private val unoNumberWords = listOf(
    "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine"
)

/**
 * Full screen-reader identity for a card — "Red Seven", "Blue Skip",
 * "Wild Draw Four" — as opposed to [UnoCard.displayLabel]'s terse on-card glyph
 * ("7", "Wild +4") meant to be read visually at a glance, not spoken aloud.
 * This app had no screen-reader semantics anywhere before this pass (an
 * audited finding), so there's no prior convention to match here beyond
 * Compose's own `Modifier.semantics { contentDescription = ... }` idiom —
 * see the FannedHand/discard-pile/opponent-fan call sites below.
 *
 * [colorOverride] lets the discard pile's top card describe the color the
 * table has actually agreed on after a Wild is played (s.currentColor)
 * rather than the card's own literal WILD color, matching what's shown
 * visually via [unoCardToVisual]'s own overrideColor. Wild ranks ignore it
 * and stay colorless ("Wild", "Wild Draw Four") since the two Wild ranks
 * have no meaningful color of their own until a color is chosen.
 */
private fun UnoCard.accessibleDescription(colorOverride: UnoColor? = null): String {
    if (rank == UnoRank.WILD) return "Wild"
    if (rank == UnoRank.WILD_DRAW_FOUR) return "Wild Draw Four"
    val colorName = (colorOverride ?: color).name.lowercase().replaceFirstChar { it.uppercase() }
    return when (rank) {
        UnoRank.SKIP -> "$colorName Skip"
        UnoRank.REVERSE -> "$colorName Reverse"
        UnoRank.DRAW_TWO -> "$colorName Draw Two"
        else -> "$colorName ${unoNumberWords[rank.ordinal]}"
    }
}

/** One non-color shape per UNO color, distinct enough at 20sp to read at a glance —
 *  used only when Settings -> Accessibility -> "Colorblind-safe mode" is on. */
private val colorblindGlyph: Map<UnoColor, String> = mapOf(
    UnoColor.RED to "●",
    UnoColor.YELLOW to "▲",
    UnoColor.GREEN to "■",
    UnoColor.BLUE to "◆"
)

@Composable
private fun ColorPickerDialog(onColorChosen: (UnoColor) -> Unit) {
    // Settings -> Accessibility -> Colorblind-safe mode (see settings/LocalColorblindMode.kt)
    // -- the audited finding that this toggle was stored but consumed nowhere. The text
    // label below is already always-on (a good universal a11y win, unchanged for everyone);
    // this is the thing that actually changes when the setting is flipped.
    val colorblindMode = LocalColorblindMode.current
    Dialog(onDismissRequest = {}) {
        UnoPanel {
            PanelTitle("Choose a color")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE).forEach { c ->
                    val label = c.name.lowercase().replaceFirstChar { it.uppercase() }
                    // A visible text label covers colorblind players even with this setting
                    // off, and the semantics contentDescription (on top of Text's own, for a
                    // single clear TalkBack announcement per swatch) covers screen-reader
                    // users — docs/SETTINGS_THEMING_ACCESSIBILITY.md named this its
                    // highest-priority accessibility gap.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colorFor(c))
                                .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .semantics { contentDescription = label }
                                .clickable { onColorChosen(c) }
                        ) {
                            if (colorblindMode) {
                                Text(
                                    colorblindGlyph.getValue(c),
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 20.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChallengeDialog(playedByName: String, onAccept: () -> Unit, onChallenge: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        UnoPanel {
            PanelTitle("Wild Draw Four")
            Spacer(Modifier.height(8.dp))
            Text("$playedByName played it on you.", color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                "Think they had a matching color card in hand? Challenge them.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onAccept) { Text("Accept (draw 4)") }
                Button(
                    onClick = onChallenge,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) { Text("Challenge!") }
            }
        }
    }
}

@Composable
private fun RoundOverContent(state: com.gamesuite.games.uno.UnoState, onNextRound: () -> Unit, enhanced: Boolean = false) {
    // Confirmed real gap: a round win got the confetti/ConfettiOverlay visual
    // beat and (for a match win specifically) a CELEBRATION haptic from the
    // caller, but literally no sound at all -- SUCCESS_CHIME is exactly the
    // "a solve, a win" case its own KDoc names. Fires once per composition,
    // same LaunchedEffect(Unit)-on-a-once-shown-composable idiom ConfettiOverlay
    // itself already uses just below.
    val playSfx = rememberProceduralSfx()
    LaunchedEffect(Unit) { playSfx(SfxKind.SUCCESS_CHIME) }
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (enhanced) ConfettiOverlay()
        UnoPanel(modifier = Modifier.widthIn(min = 300.dp, max = 460.dp)) {
            PanelTitle(winnerLabel(state, "wins round ${state.roundNumber}"))
            Spacer(Modifier.height(4.dp))
            Text("First to 500", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(14.dp))
            // Up to ten rows: on a short window (Fold cover, landscape) it scrolls instead of pushing the
            // button off the bottom of the screen.
            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { UnoScoreboard(state) }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onNextRound,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7), contentColor = Color.White),
                modifier = Modifier.heightIn(min = 46.dp)
            ) { Text("Next round", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun MatchOverContent(state: com.gamesuite.games.uno.UnoState, onDone: () -> Unit, enhanced: Boolean = false) {
    // Same currently-silent-win gap as RoundOverContent above -- the match
    // win already gets a CELEBRATION haptic (see UnoScreen's own
    // LaunchedEffect(s.matchOver)) but no sound of any kind accompanies it.
    val playSfx = rememberProceduralSfx()
    LaunchedEffect(Unit) { playSfx(SfxKind.SUCCESS_CHIME) }
    Box(
        // AGSL deepening: the same shared "big win" glow every other flagship screen now
        // shows, gated on the same `enhanced` flag (LocalEnhancedAnimations && !reducedMotion)
        // as the confetti right below -- this panel only exists while the match is actually
        // over, so the no-arg victoryGlow() (fires once on entering composition) fits better
        // here than the boolean-trigger overload the still-mounted screens above use.
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .let { if (enhanced) it.victoryGlow() else it },
        contentAlignment = Alignment.Center
    ) {
        // The match win gets the bigger celebration -- two waves instead of
        // round-over's one -- since it's the actual end of the game, not just
        // one hand of it (Xbox 360 UNO / modern mobile UNO both save their
        // biggest fanfare for the real win, not every round along the way).
        if (enhanced) {
            ConfettiOverlay()
            ConfettiOverlay(delayMs = 500)
        }
        UnoPanel(modifier = Modifier.widthIn(min = 300.dp, max = 460.dp)) {
            UnoCrown()
            Spacer(Modifier.height(6.dp))
            PanelTitle(winnerLabel(state, "wins the match!"))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { UnoScoreboard(state) }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7), contentColor = Color.White),
                modifier = Modifier.heightIn(min = 46.dp)
            ) { Text("Back to menu", fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * The user's Card size setting as a device-independent multiplier around 1.0. [cardScale] is
 * already device-mapped (see CardScale.kt: its ceiling shrinks on narrow windows), so using it
 * directly against a fixed tablet reference made every card on a narrow screen small twice over;
 * this inverts that mapping back to the 0..1 preference and re-centers it, so the default
 * preference means "normal size" on any device and the slider still spans small to large.
 */
private fun cardPreferenceScale(cardScale: Float, screenWidthDp: Int): Float =
    0.75f + com.gamesuite.games.cards.cardSizePreferenceFor(cardScale, screenWidthDp) * 0.7f

private data class ScoreRow(val name: String, val score: Int, val isWinner: Boolean)

/**
 * The end-of-round / end-of-match standings: players (teams, when team play is on) ranked by
 * cumulative score, each with a medal, their total, and a bar filling toward the 500-point target.
 * The round's winner is picked out with a gold plate. The bars fill in on entry, staggered by rank.
 */
@Composable
private fun UnoScoreboard(state: com.gamesuite.games.uno.UnoState, targetScore: Int = 500) {
    val rows = state.players
        .distinctBy { it.teamId.takeIf { t -> t >= 0 } ?: it.playerId }
        .map { p ->
            ScoreRow(
                name = p.displayName,
                score = state.cumulativeScores[p.playerId] ?: 0,
                isWinner = state.winnerPlayerId == p.playerId ||
                    (state.winningTeamId != null && state.winningTeamId == p.teamId)
            )
        }
        .sortedByDescending { it.score }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { rank, r ->
            val fill by animateFloatAsState(
                targetValue = if (shown) (r.score / targetScore.toFloat()).coerceIn(0f, 1f) else 0f,
                animationSpec = tween(900, delayMillis = 140 * rank),
                label = "scoreBar"
            )
            val medal = when (rank) {
                0 -> Color(0xFFFFD54F)
                1 -> Color(0xFFCFD8DC)
                2 -> Color(0xFFD08A57)
                else -> Color(0xFF5C4B99)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (r.isWinner) {
                            Modifier
                                .background(panelGold.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                                .border(1.dp, panelGold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(26.dp).background(medal, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${rank + 1}", color = Color(0xFF1D1440), fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        r.name,
                        color = Color.White,
                        fontWeight = if (rank == 0) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${r.score}", color = panelGold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fill)
                            .background(
                                Brush.horizontalGradient(listOf(Color(0xFFFFE08A), Color(0xFFC9962F))),
                                RoundedCornerShape(50)
                            )
                    )
                }
            }
        }
    }
}

/** A small gold crown for the match-win panel, drawn as a path so it never depends on a font glyph. */
@Composable
private fun UnoCrown() {
    Canvas(modifier = Modifier.size(width = 64.dp, height = 44.dp)) {
        val w = size.width
        val h = size.height
        val crown = Path().apply {
            moveTo(w * 0.10f, h * 0.92f)
            lineTo(w * 0.03f, h * 0.30f)
            lineTo(w * 0.29f, h * 0.56f)
            lineTo(w * 0.50f, h * 0.08f)
            lineTo(w * 0.71f, h * 0.56f)
            lineTo(w * 0.97f, h * 0.30f)
            lineTo(w * 0.90f, h * 0.92f)
            close()
        }
        drawPath(crown, Brush.verticalGradient(listOf(Color(0xFFFFE9A8), Color(0xFFC9962F))))
        drawPath(crown, Color(0xFF7C5417), style = Stroke(width = 2.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        for (x in listOf(0.03f, 0.50f, 0.97f)) {
            drawCircle(Color(0xFFFFF3C4), radius = h * 0.09f, center = Offset(w * x, if (x == 0.50f) h * 0.08f else h * 0.30f))
        }
    }
}

/**
 * A simple, self-contained confetti burst — the modern official mobile UNO
 * app's own signature round/match-win beat. Each of a fixed batch of
 * rectangular pieces gets a randomized column, fall speed, and start delay
 * (all seeded once via `remember`, never re-rolled across recompositions),
 * falls from just above the top of the screen to just below the bottom over
 * its own duration, and fades out in its final 20%. Fires once per
 * composition (LaunchedEffect(Unit)) — this composable's caller is only ever
 * shown once per round/match ending, so "once" here already means "once per
 * win," not just once ever.
 */
@Composable
private fun ConfettiOverlay(delayMs: Int = 0) {
    val colors = remember {
        listOf(Color(0xFFD32F2F), Color(0xFFFBC02D), Color(0xFF388E3C), Color(0xFF1976D2))
    }
    val pieces = remember {
        List(40) { i ->
            ConfettiPiece(
                columnFraction = kotlin.random.Random.nextFloat(),
                startDelay = kotlin.random.Random.nextFloat() * 0.35f,
                fallDuration = 1.7f + kotlin.random.Random.nextFloat() * 0.9f,
                driftFraction = (kotlin.random.Random.nextFloat() - 0.5f) * 0.35f,
                color = colors[i % colors.size],
                widthPx = 7f + kotlin.random.Random.nextFloat() * 5f,
                wobbleSpeed = 3f + kotlin.random.Random.nextFloat() * 4f
            )
        }
    }
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        time.animateTo(1f, animationSpec = tween(2800, easing = androidx.compose.animation.core.LinearEasing))
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        pieces.forEach { piece ->
            val t = ((time.value - piece.startDelay) / piece.fallDuration).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) return@forEach
            val py = t * (size.height + 60f) - 30f
            val wobble = kotlin.math.sin(t * piece.wobbleSpeed * Math.PI.toFloat()) * 14f
            val px = piece.columnFraction * size.width + piece.driftFraction * size.width * t + wobble
            val alpha = if (t > 0.8f) ((1f - t) / 0.2f).coerceIn(0f, 1f) else 1f
            val heightPx = piece.widthPx * 1.6f * (0.4f + 0.6f * kotlin.math.abs(kotlin.math.cos(t * piece.wobbleSpeed * Math.PI.toFloat())))
            drawRect(
                color = piece.color.copy(alpha = alpha),
                topLeft = Offset(px - piece.widthPx / 2f, py - heightPx / 2f),
                size = androidx.compose.ui.geometry.Size(piece.widthPx, heightPx)
            )
        }
    }
}

private data class ConfettiPiece(
    val columnFraction: Float,
    val startDelay: Float,
    val fallDuration: Float,
    val driftFraction: Float,
    val color: Color,
    val widthPx: Float,
    val wobbleSpeed: Float
)

/**
 * Every screen in the reference sits on a deep purple-to-black radial glow —
 * the single strongest tonal signature distinguishing it from GameSuite's flat
 * shared AppTheme surface. Scoped to this screen alone: must not touch the
 * four suit colors themselves, which stay theme-independent by GameSuite's own
 * established rule (game-critical colors never read MaterialTheme.colorScheme)
 * — this is set dressing behind the cards, not a recolor of them.
 */
@Composable
private fun UnoBackground(dangerLevel: Float = 0f, content: @Composable BoxScope.() -> Unit) {
    // "Hot streak" tension identity: a subtle warm shift as the table's
    // tension rises (a hand at 1 card, or a big +2/+4 stack pending) -- see
    // UnoScreen's own tableTension computation for what feeds this.
    // dangerLevel's default (0f) reproduces the original fixed gradient
    // exactly, so the two end-screen callers (RoundOverContent/
    // MatchOverContent), which have no live tension value to pass, are
    // unaffected.
    val topColor = lerp(Color(0xFF3A2E6E), Color(0xFF6E2E3A), dangerLevel.coerceIn(0f, 1f))
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(topColor, Color(0xFF120C24))))
            // A soft darkened vignette at the very corners -- cheap, static, no
            // animation cost -- reads closer to a real table lit from above
            // (the Xbox 360 UNO reference's own table lighting) than a flat
            // gradient fill alone did.
            .background(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)),
                    radius = 1400f
                )
            )
    ) {
        // Every screen drawn on this dark table background reads its default text color from
        // here. Left to the app theme it comes out dark-on-dark whenever the device is in light
        // mode (seat names and the play-by-play were nearly invisible), so it is pinned light.
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            content()
        }
    }
}

private val panelBackground = Color(0xFF1D1440)
private val panelBorder = Color(0xFF6C5CE7).copy(alpha = 0.55f)
private val panelGold = Color(0xFFFFC933)

/** The rounded, dark, violet-bordered panel every overlay in the reference
 *  uses (first seen on its in-game "Command Cards" rules screen) — applied
 *  here to every dialog/end screen so they read as one consistent game
 *  rather than a mix of this treatment and plain Material dialogs. */
@Composable
private fun UnoPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .background(panelBackground, RoundedCornerShape(16.dp))
            .border(1.5.dp, panelBorder, RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        color = panelGold,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        letterSpacing = 0.5.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

private data class ChallengeRevealSnapshot(val playedByName: String, val hand: List<UnoCard>)

/**
 * A snapshot of one card mid-flight from the player's hand to the discard
 * pile — see the fly-to-discard-pile LaunchedEffect in UnoScreen for how
 * this is populated (FannedHand's onCardAboutToPlay) and consumed (the
 * FlyingCardOverlay composable below, driven by a shared Animatable
 * progress rather than one per card since only one card is ever in flight
 * at a time on a single device).
 */
private data class FlyingCard(
    val visual: CardVisual,
    val start: Offset,
    val startRotationDeg: Float,
    /** Whether the card actually in flight is one of the two Wild ranks --
     *  CardVisual itself carries no rank, so this is captured at
     *  onCardAboutToPlay time (see UnoScreen's own call site) purely to scope
     *  the shared specular-sweep shader to Wild cards without threading a
     *  whole UnoCard through this transient overlay's data. */
    val isWildRank: Boolean = false,
    /** The actual rank of the card in flight -- captured alongside
     *  isWildRank at onCardAboutToPlay time, purely to weight the tumbling-
     *  toss rotation (see [tumbleTurnsFor]) and the flight duration by how
     *  big the play is (animation/physics pitch: a plain number card gets a
     *  gentle wobble, an action card a fuller single rotation, Wild Draw Four
     *  the most dramatic multi-rotation tumble). */
    val rank: UnoRank = UnoRank.ZERO,
    /** On-screen width of the card at the moment it leaves, so the flight starts at exactly the
     *  size of the card it replaces (the hand's cards, or a small opponent-fan card). */
    val width: androidx.compose.ui.unit.Dp = 64.dp
)

/**
 * One face-down card traveling between two points on the table (the draw pile out to an
 * opponent's seat: the deal at the start of a round, and an opponent's draw). Unlike
 * [FlyingCard]/[DrawnCardFlight] -- one-at-a-time flights driven by a shared Animatable -- many of
 * these run at once (a whole deal), so each carries its own absolute start time and duration and
 * they are all advanced by one frame clock (see [MiniFlightsOverlay]).
 */
private data class MiniFlight(
    val from: Offset,
    val to: Offset,
    val fromWidthPx: Float,
    val toWidthPx: Float,
    val startMs: Long,
    val durationMs: Long = 340L
)

@Composable
private fun MiniFlightsOverlay(flights: List<MiniFlight>, clock: State<Long>) {
    // Reading the clock here (not in the caller) keeps the per-frame recomposition scoped to this
    // overlay instead of the whole screen.
    val now = clock.value
    val density = LocalDensity.current
    val back = remember { CardVisual(id = -3, label = "", backgroundColor = Color.Transparent, faceDown = true, style = CardStyle.UNO) }
    for (f in flights) {
        val p = (now - f.startMs).toFloat() / f.durationMs
        if (p <= 0f || p >= 1f) continue
        val eased = 1f - (1f - p) * (1f - p)
        val wPx = f.fromWidthPx.coerceAtLeast(1f)
        val hPx = wPx * (92f / 64f)
        val cx = f.from.x + (f.to.x - f.from.x) * eased
        val cy = f.from.y + (f.to.y - f.from.y) * eased - with(density) { 18.dp.toPx() } * 4f * eased * (1f - eased)
        val ratio = f.toWidthPx / wPx
        val scale = 1f + (ratio - 1f) * eased
        val alpha = if (p > 0.85f) 1f - (p - 0.85f) / 0.15f else 1f
        Box(
            modifier = Modifier
                .offset { IntOffset((cx - wPx / 2f).roundToInt(), (cy - hPx / 2f).roundToInt()) }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = 14f * (1f - eased)
                    this.alpha = alpha
                }
        ) {
            PlayingCardView(card = back, width = with(density) { wPx.toDp() }, height = with(density) { hPx.toDp() })
        }
    }
}

/** A card mid-flight from the draw pile into the local player's hand —
 *  face-down for the first leg, flipping face-up as it settles. See the
 *  draw-pile LaunchedEffect above for how this gets populated and
 *  [DrawPileFlightOverlay] below for how it's rendered. */
private data class DrawnCardFlight(val card: UnoCard)

/**
 * Renders [card] traveling from its captured hand position to [target] (the
 * discard pile's own root position) as [progress] runs 0f -> 1f, easing its
 * fan rotation back to flat and giving it a slight arc + shrink so it reads
 * as "landing" on the pile rather than sliding flatly across the table.
 * Physically identical in spirit to FannedHand's own drag-lift spring — see
 * that file's onCardAboutToPlay KDoc for why this lives here instead of
 * inside the shared component.
 *
 * [enhanced] additionally layers a genuine tumbling-toss rotationZ spin on
 * top of the base fan-tilt decay below (animation/physics pitch: "not just
 * decay to flat") -- weighted by [tumbleTurnsFor] so a plain number card
 * gets a gentle wobble, an action card a fuller single rotation, and Wild
 * Draw Four the most dramatic multi-rotation tumble in the game, matching
 * this file's own established "biggest swing gets the biggest beat" rule for
 * its hit-stop/camera-punch elsewhere. This is purely 2D (rotationZ) and
 * gated on `enhanced` alone (motion QUALITY) -- independent of, and additive
 * to, whatever [use3D] separately contributes below via [card3DToss]'s real
 * perspective rotationY roll (motion DEPTH/PERSPECTIVE); with `enhanced` off
 * this overlay looks and behaves exactly as it did before this pass.
 *
 * [use3D] adds a real perspective roll via [card3DToss] (Settings -> Display
 * -> "3D card mode", already resolved with reducedMotion by the caller) —
 * gated so this overlay looks identical to before that setting existed when
 * it's off.
 */
@Composable
private fun FlyingCardOverlay(card: FlyingCard, targetCenter: Offset, targetWidthPx: Float, progressState: State<Float>, use3D: Boolean, enhanced: Boolean) {
    // Read here, not by the caller: the per-frame recomposition stays inside this overlay instead of
    // re-running the whole screen (table, seats, hand) for every frame of a flight.
    val progress = progressState.value
    val density = LocalDensity.current
    // The ghost is laid out at the size of the card it leaves and scaled (about its center) up or
    // down to the size of the card it lands on, so a hand card doesn't visibly shrink on lift-off
    // and the pile card it merges into matches exactly. `card.start` is the source card's
    // top-left, so its center follows from the source size.
    val srcW = with(density) { card.width.toPx() }
    val srcH = srcW * (92f / 64f)
    val srcCenter = Offset(card.start.x + srcW / 2f, card.start.y + srcH / 2f)
    val sizeRatio = if (srcW > 0f && targetWidthPx > 0f) targetWidthPx / srcW else 1f
    // Eased eases faster than linear so the card feels "thrown" rather than
    // conveyor-belted: quick to leave the hand, settling into the pile.
    val eased = 1f - (1f - progress) * (1f - progress)
    val cx = srcCenter.x + (targetCenter.x - srcCenter.x) * eased
    // A small upward arc (negative = up in screen space) peaking at the
    // midpoint — a real toss rises before it lands, a straight lerp doesn't.
    val arc = -with(density) { 28.dp.toPx() } * 4f * eased * (1f - eased)
    val cy = srcCenter.y + (targetCenter.y - srcCenter.y) * eased + arc
    val x = cx - srcW / 2f
    val y = cy - srcH / 2f
    // The fan-tilt the card had at pickup, decaying to flat by landing --
    // unchanged from before this pass.
    val baseDecay = card.startRotationDeg * (1f - eased)
    // The new tumble, additive on top of baseDecay. Every tier is built so it
    // resolves to a clean, flat, correctly-oriented landing at eased == 1,
    // never a mid-spin: the directional-spin tiers (action ranks, Wild, Wild
    // Draw Four) always use a WHOLE number of extra 360-degree turns, so "N
    // full turns" and "0 turns" look visually identical at rest; the plain
    // number-card tier instead uses a symmetric sine wobble that returns to
    // exactly 0 by construction (sin(pi) == 0) rather than a directional
    // spin that could land mid-turn.
    val tumble = if (enhanced) {
        val turns = tumbleTurnsFor(card.rank)
        if (turns > 0f) {
            360f * turns * eased
        } else {
            val wobbleSign = if (card.startRotationDeg < 0f) -1f else 1f
            26f * wobbleSign * kotlin.math.sin(eased * Math.PI.toFloat())
        }
    } else 0f
    val rotation = baseDecay + tumble
    val scale = 1f + (sizeRatio - 1f) * eased
    // Fades out only in the final stretch of the flight, so the ghost
    // dissolves right as it merges into the real top-of-pile card
    // (AnimatedContent's own scale/fade transition, already running) rather
    // than visibly overlapping it at full opacity.
    val fadeOutStart = 0.85f
    val alpha = if (progress > fadeOutStart) 1f - (progress - fadeOutStart) / (1f - fadeOutStart) else 1f

    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .graphicsLayer {
                rotationZ = rotation
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .then(if (use3D) Modifier.card3DToss(progress) else Modifier)
    ) {
        PlayingCardView(
            card = card.visual,
            width = card.width,
            height = card.width * (92f / 64f),
            modifier = Modifier.specularSweep(enabled = use3D && card.isWildRank, tint = panelGold)
        )
    }
}

/**
 * Weights [FlyingCardOverlay]'s tumbling-toss rotation by how big the play
 * is: 0 for a plain number card (no directional spin -- see the sine wobble
 * at that overlay's own tumble call site instead), 1 full turn for the three
 * lesser action ranks, 2 for a color-changing Wild, and 3 -- the most
 * dramatic tumble in the game -- for Wild Draw Four, matching this file's own
 * established "biggest swing gets the biggest beat" rule for its hit-stop/
 * camera-punch treatment elsewhere. Always a whole number of turns so the
 * rotation always lands visually flat, never mid-spin, by eased == 1.
 */
private fun tumbleTurnsFor(rank: UnoRank): Float = when (rank) {
    UnoRank.WILD_DRAW_FOUR -> 3f
    UnoRank.WILD -> 2f
    UnoRank.SKIP, UnoRank.REVERSE, UnoRank.DRAW_TWO -> 1f
    else -> 0f
}

/**
 * Renders a drawn card traveling from the draw pile ([start]) into the
 * player's hand area ([target]) as [progress] runs 0f -> 1f — face-down for
 * the flight, flipping face-up only in the final stretch as it settles,
 * mirroring a real draw where you don't see what you got until it's
 * basically already in your hand. [use3D] drives that reveal through
 * card3DFlip's real edge-on rotation; without it, this cross-fades between
 * the two faces at the same progress point instead of a hard cut.
 */
@Composable
private fun DrawPileFlightOverlay(
    card: UnoCard,
    startRect: androidx.compose.ui.geometry.Rect,
    targetCenter: Offset,
    targetWidthPx: Float,
    progressState: State<Float>,
    use3D: Boolean,
    colorblindMode: Boolean = false
) {
    val progress = progressState.value
    val density = LocalDensity.current
    // Laid out at the draw pile's own card size and scaled toward the hand's card size as it
    // travels, both measured (see the rect tracking in UnoScreen) rather than assumed.
    val srcW = startRect.width.coerceAtLeast(1f)
    val srcH = srcW * (92f / 64f)
    val cardWidthDp = with(density) { srcW.toDp() }
    val sizeRatio = if (targetWidthPx > 0f) targetWidthPx / srcW else 1f
    val eased = 1f - (1f - progress) * (1f - progress)
    val cx = startRect.center.x + (targetCenter.x - startRect.center.x) * eased
    val arc = -with(density) { 22.dp.toPx() } * 4f * eased * (1f - eased)
    val cy = startRect.center.y + (targetCenter.y - startRect.center.y) * eased + arc
    val x = cx - srcW / 2f
    val y = cy - srcH / 2f
    val scale = 1f + (sizeRatio - 1f) * eased
    val fadeOutStart = 0.9f
    val alpha = if (progress > fadeOutStart) 1f - (progress - fadeOutStart) / (1f - fadeOutStart) else 1f

    // Reveal begins well past the midpoint, not at it — a drawn card stays
    // face-down for most of the trip and only turns over right as it's about
    // to land, the same beat a real draw has.
    val revealStart = 0.65f
    val revealProgress = ((progress - revealStart) / (1f - revealStart)).coerceIn(0f, 1f)
    val faceVisual = unoCardToVisual(card, colorblindMode = colorblindMode)

    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
    ) {
        if (use3D) {
            Box(modifier = Modifier.card3DFlip(revealProgress)) {
                PlayingCardView(
                    card = if (revealProgress < 0.5f) faceVisual.copy(faceDown = true) else faceVisual,
                    width = cardWidthDp,
                    height = cardWidthDp * (92f / 64f),
                    modifier = Modifier.specularSweep(enabled = use3D && card.isWild && revealProgress >= 0.5f, tint = panelGold)
                )
            }
        } else {
            PlayingCardView(
                card = if (revealProgress < 1f) faceVisual.copy(faceDown = true) else faceVisual,
                width = cardWidthDp,
                height = cardWidthDp * (92f / 64f)
            )
        }
    }
}

/**
 * The flash-text beat for Skip/Reverse/Draw Two/Wild Draw Four — the same
 * visual language [UnoCalloutOverlay] already uses for UNO!, extended to
 * every action card per the animation pitch's own UNO section ("Skip,
 * Reverse, and Draw Two get no equivalent to the flash-text treatment Wild
 * Draw Four and UNO! already have"). Wild Draw Four additionally gets the
 * whole-table camera-punch zoom, driven separately by the caller.
 */
@Composable
private fun ActionCardFlashOverlay(rank: UnoRank) {
    val (text, color) = when (rank) {
        UnoRank.SKIP -> "SKIP!" to Color(0xFFE5544D)
        UnoRank.REVERSE -> "REVERSE!" to Color(0xFF4FC3F7)
        UnoRank.DRAW_TWO -> "+2!" to Color(0xFFFFA726)
        UnoRank.WILD_DRAW_FOUR -> "+4!" to Color(0xFFAB47BC)
        else -> return
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = color,
            fontWeight = FontWeight.Black,
            fontSize = 44.sp,
            letterSpacing = 1.sp,
            modifier = Modifier
                .rotate(-8f)
                .graphicsLayer { shadowElevation = 12f }
        )
    }
}

/**
 * The most dramatic beat in the reference: a huge diagonal "CHALLENGE!" flash
 * across the table, then the challenged player's hand laid face-up as proof,
 * before the real outcome resolves. UnoGame.resolveChallenge() already computes
 * the correct legal/illegal result (traced this session) — this only delays
 * calling it long enough for the animation to play; see the LaunchedEffect that
 * drives [showHand] and the eventual resolveChallenge() call in UnoScreen.
 */
@Composable
private fun ChallengeFlashOverlay(snapshot: ChallengeRevealSnapshot, showHand: Boolean, cardScale: Float, use3D: Boolean, colorblindMode: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "CHALLENGE!",
                color = Color(0xFF4CE05A),
                fontWeight = FontWeight.Black,
                fontSize = 40.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.rotate(-8f)
            )
            AnimatedVisibility(visible = showHand) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(28.dp))
                    Text("${snapshot.playedByName}'s hand", color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.widthIn(max = 320.dp).horizontalScroll(rememberScrollState())
                    ) {
                        snapshot.hand.forEach { card ->
                            PlayingCardView(
                                card = unoCardToVisual(card, colorblindMode = colorblindMode),
                                width = 36.dp * cardScale,
                                height = 52.dp * cardScale,
                                modifier = Modifier.specularSweep(enabled = use3D && card.isWild, tint = panelGold)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Fired off the "UNO!" button — the reference flashes its own call-out text
 *  the instant a player calls, distinct from its squared display type
 *  elsewhere; approximated here with italic+bold since a real script
 *  typeface isn't part of this project's font setup. */
@Composable
private fun UnoCalloutOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "UNO!",
            color = panelGold,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontSize = 48.sp,
            modifier = Modifier.rotate(-6f)
        )
    }
}

private fun winnerLabel(s: com.gamesuite.games.uno.UnoState, suffix: String): String {
    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
        ?: s.players.firstOrNull { it.teamId == s.winningTeamId }
    // "You wins round 1" read wrong -- the local player is "You", which takes "win".
    val phrase = if (winner?.displayName == "You" && suffix.startsWith("wins")) "win" + suffix.removePrefix("wins") else suffix
    return if (winner != null) "${winner.displayName} $phrase" else "Round over"
}

/**
 * "Which seat is this device/screen currently representing" — mode-dependent, since
 * that means something different in each:
 *  - LOCAL_AD_HOC: each device IS one specific, fixed player for the whole match —
 *    context.localPlayerIndex (assigned once by the lobby) is exactly that.
 *  - SINGLE_DEVICE_PASS_AND_PLAY: there's no single "local player" at all — the one
 *    shared device is handed around, so "my seat" is whoever's turn it currently is.
 *    Found via on-device Nearby testing: this function used to always return the
 *    first non-bot player's index regardless of mode (a comment here literally said
 *    "the human player is always index 0"), which happens to be harmless for vs-bot
 *    (there's only one human seat, so "first non-bot" and "current human's turn"
 *    coincide) but is a real bug for multi-human pass-and-play — players 2/3/4 could
 *    never act, since turn-gating requires currentPlayerIndex == myIndex and myIndex
 *    was frozen at 0. Nothing had exercised a full 4-player/2v2 pass-and-play match
 *    to the point of a later player's turn before this.
 *  - ONLINE: same fixed-seat-per-device shape as LOCAL_AD_HOC — context.localPlayerIndex,
 *    assigned once by OnlineHostLobbyScreen (always 0)/OnlineJoinLobbyScreen (resolved from
 *    the GameStart roster) at lobby time. This case was simply missing — ONLINE fell through
 *    to the same "first non-bot player" branch SINGLE_PLAYER_VS_BOT uses below, which is
 *    exactly the bug this KDoc already documents LOCAL_AD_HOC once had: every device's own
 *    human is non-bot, so "first non-bot" resolved to the SAME seat (whichever player joined
 *    first, i.e. the host) on every device, regardless of which one was actually running.
 *    Found via a real two-device Online match: the guest read the host's own hand off
 *    UnoState.players[0] — which UnoGame.redactHandsExcept correctly blanks to placeholder
 *    WILD cards for every recipient except the seat it's addressed to — so the guest saw
 *    seven identical "Wild" cards instead of its own real, varied hand.
 *  - SINGLE_PLAYER_VS_BOT (default): the one human seat — first non-bot player.
 */
private fun humanIndex(s: com.gamesuite.games.uno.UnoState, context: com.gamesuite.core.GameContext): Int =
    when (context.activeMode) {
        com.gamesuite.core.PlayMode.LOCAL_AD_HOC, com.gamesuite.core.PlayMode.ONLINE -> context.localPlayerIndex
        // Whoever is up is "me" -- unless whoever is up is a CPU. A mixed table (the house-rules
        // "vs 2 CPU bots" entry runs in this mode) has one device and one human: following the turn onto
        // a CPU seat showed that CPU's hand, lit up as playable, on the human's screen. Pin to the human.
        com.gamesuite.core.PlayMode.SINGLE_DEVICE_PASS_AND_PLAY ->
            if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == false) s.currentPlayerIndex
            else s.players.indexOfFirst { !it.isBot }.let { if (it >= 0) it else s.currentPlayerIndex }
        else -> s.players.indexOfFirst { !it.isBot }.let { if (it >= 0) it else 0 }
    }

private fun colorFor(color: UnoColor): Color = when (color) {
    UnoColor.RED -> Color(0xFFD32F2F)
    UnoColor.YELLOW -> Color(0xFFFBC02D)
    UnoColor.GREEN -> Color(0xFF388E3C)
    UnoColor.BLUE -> Color(0xFF1976D2)
    UnoColor.WILD -> Color(0xFF424242)
}

// ---- Opponent "personality" tells (premium 2026 vision pitch, UNO section) ----

/**
 * Replaces the old flat delay(700) before every bot action. Both inputs
 * below are purely cosmetic -- UnoBot.chooseMove() has already decided the
 * actual move by the time this delay even starts running, and [hand] is
 * read only to derive a legal-play COUNT, never rendered or otherwise
 * exposed, exactly like every other CPU opponent in this suite already
 * keeps its hand-reading honest.
 */
private fun botThinkDelayMs(hand: List<UnoCard>, state: com.gamesuite.games.uno.UnoState, rules: UnoRules, playerId: String): Long {
    val legalCount = approxLegalPlayCount(hand, state, rules)
    // More live options reads as a bot "weighing" choices a little longer; a
    // forced/obvious play reads faster -- capped so a big hand never makes a
    // bot feel sluggish.
    val base = 420L + legalCount.coerceIn(0, 6) * 65L
    // A small, stable per-seat offset derived from the playerId's own hash --
    // the same bot seat reads with a consistent rhythm turn after turn
    // instead of jittering randomly, which is what actually reads as
    // "personality" rather than noise.
    val seedOffset = (kotlin.math.abs(playerId.hashCode()) % 260).toLong()
    return base + seedOffset
}

/** A local, screen-only approximation of UnoGame's own private isLegalPlay
 *  (and UnoBot's identical private copy) -- duplicated deliberately rather
 *  than exposed from the engine, since this bundle only touches
 *  UnoScreen.kt and the result here only ever drives a cosmetic delay,
 *  never a real legality decision. */
private fun approxLegalPlayCount(hand: List<UnoCard>, state: com.gamesuite.games.uno.UnoState, rules: UnoRules): Int {
    if (state.pendingDraw > 0) {
        val top = state.topCard
        return hand.count { card ->
            when {
                top.rank == UnoRank.DRAW_TWO -> card.rank == UnoRank.DRAW_TWO ||
                    (rules.stackDrawFourOnDrawTwo && card.rank == UnoRank.WILD_DRAW_FOUR)
                top.rank == UnoRank.WILD_DRAW_FOUR -> card.rank == UnoRank.WILD_DRAW_FOUR
                else -> false
            }
        }
    }
    return hand.count { it.isWild || it.color == state.currentColor || it.rank == state.topCard.rank }
}

// ---- Layered sound (premium 2026 vision pitch's "procedural/layered sound" ----
// ---- recommendation, applied via CardSounds' existing public API) ----

/**
 * Layers a second, slightly staggered play of the same clip on top of the
 * first, instead of a single play() call. CardSounds' own play() has an
 * unused rate parameter for real pitch jitter, but it (and every public
 * wrapper around it) is zero-arg and lives in CardSounds.kt, which this
 * bundle does not touch. SoundPool's own overlapping streams (maxStreams=4
 * — see CardSounds.kt) make two staggered plays of the same clip comb
 * together into a thicker, less identical-sounding hit than a single
 * play() ever gives alone, and the randomized stagger keeps repeat plays
 * from lining up the same way twice -- the actual "not the same clip every
 * time" goal this pass is after, reachable from this bundle alone.
 */
/** [rate] pitch-shifts both layered plays together (see CardSounds.playPlace's own KDoc) --
 *  lets a rank matter to how a play SOUNDS, not just which haptic fires, still with no new
 *  audio asset. Defaults to 1f so every pre-existing call site is unaffected. */
private fun layeredPlace(sounds: CardSounds, scope: CoroutineScope, rate: Float = 1f) {
    sounds.playPlace(rate)
    scope.launch {
        delay((16L..42L).random())
        sounds.playPlace(rate)
    }
}

/** Per-rank pitch for [layeredPlace] -- a plain number card stays at the
 *  unpitched baseline, action cards (the same four ranks UnoScreen.kt's own
 *  haptic `when` below already singles out) pitch down slightly so a played
 *  action card is audibly, not just haptically, distinct from an ordinary
 *  number card. Wild Draw Four sits lowest/weightiest, matching it already
 *  being the single strongest haptic and the only rank with its own
 *  hit-stop/camera-punch treatment above (audited finding: previously every
 *  rank shared one identical, unpitched clip). */
private fun placeRateFor(rank: UnoRank): Float = when (rank) {
    UnoRank.WILD_DRAW_FOUR -> 0.78f
    UnoRank.DRAW_TWO -> 0.86f
    UnoRank.SKIP, UnoRank.REVERSE -> 0.92f
    UnoRank.WILD -> 1.08f
    else -> 1f
}

/** Same layering technique as [layeredPlace], reused for the UNO! callout's
 *  own chime -- confirmed silent before this pass on both the self-tap and
 *  the bot-detected callout path. No new audio asset: two staggered taps of
 *  the existing generic ui_tap clip read as a distinct "ding-ding" against
 *  every single-play sound elsewhere on this screen. */
private fun layeredChime(sounds: CardSounds, scope: CoroutineScope) {
    sounds.playTap()
    scope.launch {
        delay(80L)
        sounds.playTap()
    }
}


// ---- Reference-driven presentation (see docs — "Reshuffling UNO" design memo) ----
//
// Modeled after a 2006 Xbox 360 longplay that identifies every seat with a small
// colored icon badge instead of a portrait or Avatar — deliberately no per-player
// photo/character art here, matching that reference exactly.

/** Cycled by seat index, not tied to any player data — GameSuite doesn't (and
 *  shouldn't need to) model a "player color" just to give badges some variety. */
private val badgeGradients = listOf(
    listOf(Color(0xFFFFA726), Color(0xFFEF6C00)), // orange
    listOf(Color(0xFFAB47BC), Color(0xFF6A1B9A)), // purple
    listOf(Color(0xFF26A69A), Color(0xFF00695C)), // teal
    listOf(Color(0xFFEC407A), Color(0xFFAD1457)), // pink
    listOf(Color(0xFF42A5F5), Color(0xFF1565C0)), // blue
    listOf(Color(0xFFFFCA28), Color(0xFFF57F17)), // gold
)

/** Team tint for the live opponents row -- keyed on UnoPlayerState.teamId
 *  (>=0 only when rules.teamPlay is on; see UnoGame.dealNewRound()), not
 *  seat index, so teammates read as visually grouped regardless of where
 *  they're seated. Distinct from [badgeGradients] above, which cycles per
 *  SEAT purely for visual variety and carries no team meaning at all --
 *  this is the fix for the confirmed real gap that only the end screens
 *  (state.players.distinctBy { it.teamId... }) ever grouped players by
 *  team; the live opponents row never did. */
private val teamColors = listOf(Color(0xFF42A5F5), Color(0xFFEF5350), Color(0xFF66BB6A), Color(0xFFFFCA28))

/**
 * One opponent's place at the table: identity tile with a live hand-count chip and a turn glow,
 * a name plate, and the face-down fan. Sized entirely off [seatScale] (the table computes it
 * from its own bounds and how many seats share the rim) so the same seat reads on a phone and
 * on a tablet. [border] is the hand-danger / team outline; [owedDraw] > 0 marks the seat that
 * has to resolve a pending +2/+4 stack.
 */
@Composable
private fun OpponentSeat(
    player: UnoPlayerState,
    seatIndex: Int,
    handCount: Int,
    isTurn: Boolean,
    /** Turn-glow strength, read only while drawing so the pulse never recomposes the seat. Used only when [isTurn]. */
    turnGlow: () -> Float,
    teamColor: Color?,
    /** Outline color and its (draw-time) alpha. */
    border: Pair<Color, () -> Float>?,
    owedDraw: Int,
    seatScale: Float,
    cardScale: Float,
    /** False for the local player's own seat: their real hand is drawn face-up below the table. */
    showFan: Boolean = true,
    /** Cumulative match score; the chip only appears once someone has actually scored. */
    score: Int = 0
) {
    val ss = seatScale
    val plateShape = RoundedCornerShape(16.dp * ss)
    val turnGreen = Color(0xFF7CFFB2)
    // Measured unbounded and centered in whatever fixed slot the table gives this seat: its content
    // (a text row, chips and fonts that don't shrink with the seat scale) can be taller than the slot
    // at small scales, and a bounded Column just clipped the fan and the bottom seat's name plate.
    Box(modifier = Modifier.wrapContentSize(Alignment.Center, unbounded = true)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(plateShape)
                // A dark plate under every seat so names and card backs stay legible on the felt.
                .background(Color(0xFF0E0A1F).copy(alpha = 0.34f))
                .then(if (teamColor != null) Modifier.background(teamColor.copy(alpha = 0.16f)) else Modifier)
                .then(
                    if (border != null) {
                        Modifier.drawWithContent {
                            drawContent()
                            val stroke = 1.5.dp.toPx()
                            drawRoundRect(
                                color = border.first.copy(alpha = border.second().coerceIn(0f, 1f)),
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                cornerRadius = CornerRadius((16.dp * ss).toPx() - stroke / 2f),
                                style = Stroke(stroke)
                            )
                        }
                    } else Modifier
                )
                .padding(horizontal = 8.dp * ss, vertical = 6.dp * ss)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = if (isTurn) {
                    Modifier.drawBehind {
                        // Kept inside the plate's own padding -- the plate clips its children, so a
                        // larger halo showed up as a flat-topped blob.
                        drawCircle(turnGreen.copy(alpha = 0.34f * turnGlow()), radius = size.minDimension * 0.62f)
                    }
                } else Modifier
            ) {
                PlayerBadge(
                    seatIndex = seatIndex,
                    size = 44.dp * ss,
                    modifier = if (isTurn) {
                        Modifier.drawWithContent {
                            drawContent()
                            val stroke = 2.5.dp.toPx()
                            drawRoundRect(
                                color = turnGreen.copy(alpha = turnGlow().coerceIn(0f, 1f)),
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                cornerRadius = CornerRadius(10.dp.toPx() - stroke / 2f),
                                style = Stroke(stroke)
                            )
                        }
                    } else Modifier
                )
                // Hand size as a number, not just the fan's silhouette (which stops growing at 8
                // cards) -- the way every UNO video game shows it.
                val chipColor = when {
                    handCount <= 1 -> Color(0xFFE5544D)
                    handCount == 2 -> Color(0xFFFFA726)
                    else -> Color(0xFF1D1440)
                }
                Text(
                    "$handCount",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = (11f * ss).coerceAtLeast(9f).sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 7.dp * ss, y = 6.dp * ss)
                        // Without the fan (compact plates, your own seat) this chip is the only place the
                        // hand size exists, so it has to say what the number is; with the fan, the fan's own
                        // description already does.
                        .then(
                            if (!showFan) {
                                Modifier.semantics { contentDescription = "${player.displayName} has $handCount ${if (handCount == 1) "card" else "cards"}" }
                            } else Modifier
                        )
                        .background(chipColor, RoundedCornerShape(50))
                        .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp * ss, vertical = 1.dp * ss)
                )
            }
            Spacer(Modifier.height(4.dp * ss))
            Box(
                modifier = Modifier
                    .widthIn(max = 124.dp * ss)
                    .background(
                        if (isTurn) Color(0xFF43A047) else Color(0xFF120C24).copy(alpha = 0.72f),
                        RoundedCornerShape(50)
                    )
                    .border(1.dp, Color.White.copy(alpha = if (isTurn) 0.5f else 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp * ss, vertical = 2.dp * ss)
            ) {
                Text(
                    player.displayName,
                    color = Color.White,
                    fontSize = (12f * ss).coerceAtLeast(9f).sp,
                    fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (score > 0) {
                Spacer(Modifier.height(2.dp * ss))
                Text(
                    "$score pts",
                    color = panelGold,
                    fontWeight = FontWeight.Bold,
                    fontSize = (10.5f * ss).coerceAtLeast(9f).sp
                )
            }
            if (showFan) {
                Spacer(Modifier.height(4.dp * ss))
                // At one card the fan collapses to a single large card instead of a counter -- the
                // hand's own shape is the "they're close" tell.
                OpponentHandFan(count = handCount, playerName = player.displayName, scale = cardScale * ss)
            }
        }
        // A small corner badge, not a border around the whole seat -- the hand-danger border above
        // is about a LOW hand, this is about an OBLIGATION, a different concern that must never be
        // visually confused with it (see PendingDrawBadge's own KDoc).
        if (owedDraw > 0) {
            PendingDrawBadge(
                count = owedDraw,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
            )
        }
    }
}

/**
 * The table surface: a gold-rimmed oval of indigo felt with a stitched inner line and a soft top
 * light, drawn to the given rectangle (all in dp, relative to the canvas this fills). The palette
 * stays inside the screen's existing violet/gold identity (see [panelBackground]/[panelGold]) and
 * keeps the four UNO colors high-contrast against it -- a green felt would swallow green cards.
 */
@Composable
private fun TableFelt(left: Dp, top: Dp, right: Dp, bottom: Dp, modifier: Modifier = Modifier) {
    // Cloth grain (AGSL, API 33+); null elsewhere and the felt is simply smooth.
    val grain = rememberFeltGrainBrush()
    // Its own layer: the table tilts and punches on a play, and the felt (gradients + AGSL grain) is far
    // and away the most expensive thing on it -- as a layer it is composited, not re-rasterised.
    Canvas(modifier = modifier.fillMaxSize().graphicsLayer()) {
        val l = left.toPx()
        val t = top.toPx()
        val w = right.toPx() - l
        val h = bottom.toPx() - t
        val rim = (minOf(w, h) * 0.022f).coerceIn(5.dp.toPx(), 12.dp.toPx())

        // Soft drop shadow so the table sits above the room instead of printing on it.
        drawOval(Color.Black.copy(alpha = 0.38f), topLeft = Offset(l, t + rim * 0.8f), size = Size(w, h))
        // Gold rim.
        drawOval(
            brush = Brush.linearGradient(
                0f to Color(0xFFFFE9A8),
                0.35f to Color(0xFFC9962F),
                0.7f to Color(0xFF7C5417),
                1f to Color(0xFFE8BE5A),
                start = Offset(l, t),
                end = Offset(l + w, t + h)
            ),
            topLeft = Offset(l, t),
            size = Size(w, h)
        )
        // Felt.
        val fl = l + rim
        val ft = t + rim
        val fw = w - rim * 2f
        val fh = h - rim * 2f
        drawOval(
            brush = Brush.radialGradient(
                0f to Color(0xFF4A3BB0),
                0.6f to Color(0xFF2E2280),
                1f to Color(0xFF1B1355),
                center = Offset(l + w / 2f, t + h * 0.42f),
                radius = maxOf(fw, fh) * 0.62f
            ),
            topLeft = Offset(fl, ft),
            size = Size(fw, fh)
        )
        if (grain != null) {
            drawOval(brush = grain, topLeft = Offset(fl, ft), size = Size(fw, fh))
        }
        // Where the felt meets the rim.
        drawOval(Color.Black.copy(alpha = 0.35f), topLeft = Offset(fl, ft), size = Size(fw, fh), style = Stroke(width = 2.dp.toPx()))
        // Stitching.
        val stitch = rim * 1.6f
        drawOval(
            Color.White.copy(alpha = 0.16f),
            topLeft = Offset(fl + stitch, ft + stitch),
            size = Size(fw - stitch * 2f, fh - stitch * 2f),
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
        )
        // Overhead light.
        drawOval(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                startY = ft,
                endY = ft + fh * 0.5f
            ),
            topLeft = Offset(fl, ft),
            size = Size(fw, fh)
        )
    }
}

/** The reference's player identity: a gradient tile with a small wild-pinwheel
 *  icon, cycled per seat — no avatar, no photo, matching the source exactly. */
@Composable
private fun PlayerBadge(seatIndex: Int, size: androidx.compose.ui.unit.Dp = 44.dp, modifier: Modifier = Modifier) {
    val gradient = badgeGradients[seatIndex % badgeGradients.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(gradient))
            .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.6f)) {
            val wedgeColors = listOf(Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5))
            for (i in 0 until 4) {
                drawArc(color = wedgeColors[i], startAngle = -45f + i * 90f, sweepAngle = 90f, useCenter = true)
            }
        }
    }
}

/** Opponent hands as a fan of face-down cards rather than a "N cards" count —
 *  and, at exactly one card, the fan collapses to a single large card instead
 *  of a badge or counter. The hand's own silhouette is the tell, same as the
 *  reference (and it applies to opponents and the local player's own hand
 *  alike there — this covers the opponents side of that).
 *  [scale] is the same LocalCardScale multiplier every other card on this
 *  screen applies — passed explicitly (not read internally) so the caller's
 *  single `val cardScale = LocalCardScale.current` stays the one source of
 *  truth, matching the rule in CardScale.kt's KDoc. Applied to the fan's own
 *  spacing/offset math too, not just the cards themselves, so a scaled-up fan
 *  still overlaps proportionally instead of gapping or crowding.
 *  [playerName] backs the fan's contentDescription ("Sam has 4 cards") — the
 *  fan silhouette this composable renders is otherwise a purely visual tell
 *  with nothing else on screen a screen reader could substitute for it. */
@Composable
private fun OpponentHandFan(count: Int, playerName: String, scale: Float, modifier: Modifier = Modifier) {
    if (count <= 0) return
    // "Opponent has 4 cards" (by name, since up to 10 players can be at the
    // table) — the fan silhouette this whole composable is built around is a
    // purely visual tell with nothing else on screen to substitute for it.
    val description = "$playerName has $count ${if (count == 1) "card" else "cards"}"
    if (count == 1) {
        PlayingCardView(
            card = CardVisual(id = -1, label = "", backgroundColor = Color.Transparent, faceDown = true, style = CardStyle.UNO),
            modifier = modifier.semantics { contentDescription = description },
            width = 40.dp * scale,
            height = 58.dp * scale
        )
        return
    }
    val displayCount = count.coerceAtMost(8) // cap fan width past ~8 cards, same as the reference quietly does
    val cardStep = 9.dp * scale
    Box(
        modifier = modifier
            .height(50.dp * scale)
            .width(22.dp * scale + cardStep * displayCount)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.TopCenter
    ) {
        for (i in 0 until displayCount) {
            val offsetFromCenter = i - (displayCount - 1) / 2f
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = cardStep * offsetFromCenter)
                    .rotate(offsetFromCenter * 5f)
            ) {
                PlayingCardView(
                    card = CardVisual(id = i, label = "", backgroundColor = Color.Transparent, faceDown = true, style = CardStyle.UNO),
                    width = 24.dp * scale,
                    height = 36.dp * scale
                )
            }
        }
        // The fan's own cap silently hides everything past 8 cards -- this player's
        // real count is still fully correct in `description` above (screen readers
        // are unaffected), but a sighted player watching the table had no visual tell
        // that a wide fan is actually undercounting once someone hoards a big hand
        // (audited finding). A neutral dark badge, not the red/orange this seat's own
        // hand-danger border and PendingDrawBadge already use just above -- those mean
        // "close to winning" / "owes a draw"; this means something else entirely
        // ("more cards than fit"), and reusing their color would read as the same signal.
        if (count > displayCount) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF2B2B2B))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    "+${count - displayCount}",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp
                )
            }
        }
    }
}

/**
 * The literal circular turn-direction indicator this whole gap exists for.
 * RESEARCH GROUNDING: console-era UNO video games of this family (Xbox
 * 360/PlayStation, mid-2000s Carbonated Games/Gameloft titles -- the exact
 * reference the project owner named for this whole pass) used a literal
 * circular arrow indicator in the middle of the table showing the current
 * turn order/direction, which visibly reverses when a Reverse card is played
 * -- a documented, real feature of that generation of UNO console games, and
 * the single biggest confirmed visual gap in this file before this pass.
 * Replaces the old `DirectionArrows` (two static glyphs mirrored via a bare
 * scaleX flip -- no actual rotation, no ring) with a real rotating ring of
 * chevrons drawn around the discard-pile cluster.
 *
 * Two motion tiers, matching every other purely-decorative effect in this
 * file:
 *  - The ring's EXISTENCE and its STATIC chevron orientation (which way they
 *    point, reflecting [direction]) are always-on regardless of any motion
 *    setting -- direction is table-critical information, not decoration,
 *    exactly like the old DirectionArrows' own "always-on read of
 *    s.direction" comment this replaces.
 *  - The CONTINUOUS rotation and the reversal flourish are gated on
 *    [enhanced], freezing to a static, correctly-oriented ring under reduced
 *    motion / enhanced-off -- same convention as the idle nudge, the danger
 *    pulse, and everything else `enhanced` already gates in this file.
 *
 * [reversalSignal] is bumped by the caller's own topCard-change effect the
 * instant a Reverse lands (the same signal already driving actionFlash's own
 * "REVERSE!" text and its ESCALATING haptic) -- driving a deliberate
 * decelerate/snap-reverse/accelerate beat here instead of a silent, instant
 * flip. [cardScale] keeps the ring proportional to the discard pile at every
 * card-size setting, same as every other card-adjacent element on this
 * screen.
 */
@Composable
private fun TurnDirectionRing(direction: Int, enhanced: Boolean, reversalSignal: Int, cardScale: Float, modifier: Modifier = Modifier) {
    val ringAngle = remember { Animatable(0f) }
    val ringFlash = remember { Animatable(0f) }
    // rememberUpdatedState: the ambient spin loop below is a long-lived
    // coroutine that only relaunches when `enhanced` itself changes, so a
    // live direction value has to reach it without needing a restart --
    // reading `direction` through a plain closure would freeze on whatever
    // value was captured the moment the loop started.
    val liveDirection = rememberUpdatedState(direction)

    // Ambient continuous rotation: one fresh 360-degree leg at a time (rather
    // than one infinite animateTo) so a live direction flip always takes
    // effect on the very next leg, and so the reversal-flourish effect below
    // can pre-empt an in-flight leg early -- Animatable's own mutual
    // exclusion means a second animateTo call on this same instance, from a
    // different coroutine, cancels whichever one is already running (the
    // same interruptible-animation idiom shakeSteps/cameraPunch already
    // rely on elsewhere in this file, just split across two coroutines here
    // instead of sequential calls in one).
    LaunchedEffect(enhanced) {
        if (!enhanced) {
            ringAngle.snapTo(0f)
            return@LaunchedEffect
        }
        while (isActive) {
            val dir = if (liveDirection.value < 0) -1f else 1f
            try {
                ringAngle.animateTo(
                    ringAngle.value + dir * 360f,
                    animationSpec = tween(7000, easing = androidx.compose.animation.core.LinearEasing)
                )
            } catch (e: CancellationException) {
                // Pre-empted by the reversal flourish below -- loop back around
                // and start a fresh leg in whatever direction is current now.
            }
        }
    }

    // The reversal flourish itself -- decelerate-and-overshoot in the OLD
    // direction, a hard snap back past flat, then accelerate away in the NEW
    // direction, plus a bright flash pulse on the ring -- synced to the same
    // instant as the "REVERSE!" ActionCardFlashOverlay text and its haptic
    // (see the topCard-change effect in UnoScreen that bumps reversalSignal).
    LaunchedEffect(reversalSignal) {
        if (reversalSignal == 0 || !enhanced) return@LaunchedEffect
        val newSign = if (liveDirection.value < 0) -1f else 1f
        val oldSign = -newSign
        coroutineScope {
            launch {
                ringAngle.animateTo(ringAngle.value + oldSign * 22f, animationSpec = tween(140))
                ringAngle.animateTo(ringAngle.value - oldSign * 60f, animationSpec = tween(150))
                ringAngle.animateTo(ringAngle.value + newSign * 95f, animationSpec = tween(260))
            }
            launch {
                ringFlash.snapTo(1f)
                ringFlash.animateTo(0f, animationSpec = tween(500))
            }
        }
    }

    val ringSize = 118.dp * cardScale
    Canvas(modifier = modifier.size(ringSize)) {
        val chevronCount = 8
        val radius = size.minDimension / 2f * 0.92f
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseColor = Color(0xFF9B8FEA)
        val color = lerp(baseColor, panelGold, ringFlash.value)
        val alpha = 0.4f + ringFlash.value * 0.5f
        // The faint track the chevrons ride on -- kept subtle by design (an
        // ambient/peripheral read, not the focal point) so it never fights
        // the discard pile card it surrounds.
        drawCircle(
            color = color.copy(alpha = alpha * 0.45f),
            radius = radius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
        // Each chevron's own pointing direction encodes `direction`
        // independently of ringAngle's animated offset -- this is the
        // always-on, non-decorative part: even frozen at ringAngle == 0
        // (enhanced off / reduced motion), the chevrons still point the
        // correct way, exactly like the old DirectionArrows did.
        val chevronDirSign = if (direction < 0) -1f else 1f
        val chevronLen = 6.dp.toPx()
        val spread = 3.5.dp.toPx()
        for (i in 0 until chevronCount) {
            val baseAngleDeg = i * (360f / chevronCount) + ringAngle.value
            val angleRad = baseAngleDeg * (Math.PI.toFloat() / 180f)
            val px = center.x + radius * kotlin.math.cos(angleRad)
            val py = center.y + radius * kotlin.math.sin(angleRad)
            val tangentRad = angleRad + (Math.PI.toFloat() / 2f) * chevronDirSign
            val dx = kotlin.math.cos(tangentRad)
            val dy = kotlin.math.sin(tangentRad)
            val backX = px - dx * chevronLen
            val backY = py - dy * chevronLen
            val perpX = -dy
            val perpY = dx
            val chevron = Path().apply {
                moveTo(px, py)
                lineTo(backX + perpX * spread, backY + perpY * spread)
                moveTo(px, py)
                lineTo(backX - perpX * spread, backY - perpY * spread)
            }
            drawPath(chevron, color = color.copy(alpha = alpha), style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * "Who has to draw 4 (or 2, or a bigger stack) and things of this nature" --
 * a small filled circular badge at a seat corner, deliberately distinct from
 * the hand-danger border already used on the same opponent seat Column: that
 * border is about a LOW hand, this badge is about an OBLIGATION -- different
 * concerns that must never be visually confused with each other. Escalates
 * in color once the obligation itself grows past a plain +2 (a stacked
 * +4/+6/+8... reads as more urgent), same spirit as the pulsing amber
 * Draw-button border this mirrors on the human's own side.
 */
@Composable
private fun PendingDrawBadge(count: Int, modifier: Modifier = Modifier) {
    val color = if (count >= 4) Color(0xFFE5544D) else Color(0xFFFFA726)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("+$count", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
    }
}

@Composable
private fun TurnTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            // liveRegion: this tag is re-anchored to a new seat (or re-worded, e.g.
            // "Draw or stack") every time the turn advances — without this a
            // screen-reader user only learns whose turn it is by hunting for it.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(Color(0xFF43A047), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold
    )
}

private fun turnPrompt(s: com.gamesuite.games.uno.UnoState): String = when {
    s.awaitingChallenge -> "Accept or challenge"
    s.awaitingColorChoice -> "Choose a color"
    s.pendingDraw > 0 -> "Draw or stack"
    else -> "Play a card"
}
