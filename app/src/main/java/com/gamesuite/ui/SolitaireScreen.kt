package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card as Material3Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.CardVisual
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.PlayingCardView
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.games.cards.card3DFlip
import com.gamesuite.games.cards.card3DToss
import com.gamesuite.games.solitaire.CardMove
import com.gamesuite.games.solitaire.MoveDestination
import com.gamesuite.games.solitaire.SelectionSource
import com.gamesuite.games.solitaire.SolitaireGame
import com.gamesuite.games.solitaire.SolitaireMotionPrefsStore
import com.gamesuite.games.solitaire.SolitaireMotionTier
import com.gamesuite.games.solitaire.SolitairePrefsStore
import com.gamesuite.games.solitaire.TableauColumn
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.ui.effects.specularSweep
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Renders SolitaireGame's state reactively — same split as every other game
 * here (TicTacToeGame, MancalaGame, ...): all rules live in the GameModule,
 * this composable only draws piles/columns and forwards taps to them.
 *
 * No settingsViewModel/CPU-difficulty plumbing (unlike HangmanScreen or
 * TicTacToeScreen) — a solved-vs-not solitaire deal has no honest
 * difficulty lever (see SolitaireGame's KDoc), and card sizing already
 * comes for free from the app-wide [LocalCardScale] CompositionLocal
 * (UnoScreen reads the exact same value the same way).
 *
 * Also owns the full animation treatment from the animation/physics pitch's
 * Solitaire section, on top of the rendering this screen already had:
 * - The tableau auto-reveal flip ([RevealingTableauCard]) — the standard
 *   "next card turns face-up" moment removeFromSource() (SolitaireGame.kt)
 *   already computes, now actually shown instead of an instant, silent swap.
 * - A fly-to-destination overlay ([activeFlights]) for every manual or
 *   auto-complete move, driven by [SolitaireGame.setOnCardMoved] — the same
 *   "engine reports the event, screen owns the animation" split, and the
 *   same arc/fade/[card3DToss] visual language, UnoScreen's
 *   fly-to-discard-pile overlay uses. Generalized to support several
 *   CONCURRENT flights, not just one, for the Maximum-tier auto-complete
 *   finale (see the "premium 2026 vision" pass below).
 * - A staggered deal-in ([dealFlights]) reusing that exact same flight
 *   machinery so [CardSounds.playShuffle] finally has motion to land on.
 * - A persisted draw-1/draw-3 preference ([SolitairePrefsStore]), with its
 *   own toggle alongside Undo/Auto-complete below.
 *
 * Every one of those is gated behind [LocalEnhancedAnimations] (falling back
 * to today's exact instant/flat behavior when it — or Reduced Motion — is
 * off), with [LocalCard3DMode] additionally choosing perspective vs. plain
 * 2D style where the pitch calls for it. See CheckersScreen/ChessScreen for
 * this exact reducedMotion/card3D/enhanced gating pattern already in
 * production elsewhere in the suite.
 *
 * The "premium 2026 vision" pass on top of all that (see
 * [SolitaireMotionPrefsStore] for the new Standard/Maximum tier this
 * introduces): a real haptic vocabulary (LIGHT_TICK/NORMAL_ACTION/
 * STRONG_ACTION/CELEBRATION, deliberately limited to those four), a felt
 * table identity (with its own slow idle-sheen), face-card foil sheen, a
 * per-suit foundation completion shimmer plus a combined finale shimmer +
 * card-back fan flourish, a Maximum-tier concurrent-flight auto-complete
 * finale with a hit-stop + camera-shake on the final King landing, and a
 * Maximum-tier idle sheen on the waste card after ~15s of no input.
 */
@Composable
fun SolitaireScreen(
    sessionManager: GameSessionManager,
    game: SolitaireGame,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val cardScale = LocalCardScale.current
    val cardWidth = 64.dp * cardScale
    val cardHeight = 92.dp * cardScale
    val density = LocalDensity.current

    val reducedMotion = LocalReducedMotion.current
    val card3D = LocalCard3DMode.current && !reducedMotion
    // Gates every NEW motion upgrade in this file (reveal flip, fly-to-destination,
    // staggered deal) -- off (either the setting itself or reduced motion) means
    // every one of them collapses to today's exact instant/flat behavior.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion

    val prefsStore = remember { SolitairePrefsStore(androidContext) }
    val drawThree by prefsStore.drawThree.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    val haptics = rememberHaptics()

    // Ambient music (premium 2026 vision pass) -- gated on BOTH the dedicated ambient-music
    // setting AND the existing master sound toggle, same AND pattern every other feature
    // that respects CardSounds.soundEnabled already uses (see sounds.playTap()/playDraw()/
    // playShuffle() calls throughout this file).
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.SOLITAIRE, enabled = musicEnabled)

    // Standard/Maximum motion tier (see SolitaireMotionPrefsStore.kt's KDoc for exactly
    // what Maximum unlocks: the concurrent-flight auto-complete finale below, the
    // hit-stop/camera-shake on the final King landing, and the idle waste-card sheen).
    // Everything else new in this file (haptics, felt table identity, foundation
    // shimmer, foil sheen) applies regardless of this tier.
    val motionPrefsStore = remember { SolitaireMotionPrefsStore(androidContext) }
    val motionTier by motionPrefsStore.motionTier.collectAsState(initial = SolitaireMotionTier.STANDARD)
    val maximumTier = motionTier == SolitaireMotionTier.MAXIMUM

    // Bumped by every manual tap (stock/waste/foundation/tableau) below -- drives the
    // Maximum-tier idle waste-card sheen a few effects down. Starts at "now" rather than
    // 0L so a player who never touches anything still gets a correctly-timed first sheen
    // from when the deal actually appeared, not an instant one from the epoch.
    var lastInteractionAt by remember { mutableStateOf(System.currentTimeMillis()) }

    // ---- screen-space pile positions, kept fresh via onGloballyPositioned ----
    // (same technique FannedHand/UnoScreen/MancalaScreen already use). Two
    // different anchors are tracked per tableau column for two different
    // needs: [tableauTopPos] is the CURRENT top card's center -- an accurate
    // destination for a move landing on an already-dealt column (item 2) --
    // while [tableauOriginPos] is the column's own fixed top-left corner,
    // stable across the whole deal-in regardless of how many cards have
    // landed yet, which the staggered deal (item 4) needs to compute each
    // card's exact final slot analytically instead of chasing a target that
    // is itself still moving mid-cascade.
    var stockPos by remember { mutableStateOf(Offset.Zero) }
    var wastePos by remember { mutableStateOf(Offset.Zero) }
    val foundationPos = remember { mutableStateMapOf<Suit, Offset>() }
    val tableauTopPos = remember { mutableStateMapOf<Int, Offset>() }
    val tableauOriginPos = remember { mutableStateMapOf<Int, Offset>() }

    // ---- fly-to-destination overlay for every manual/auto-complete move (item 2) ----
    // Generalized to support several CONCURRENT flights at once (item 6's Maximum-tier
    // finale) rather than one nullable slot -- an ordinary manual move still only ever
    // puts exactly one Flight in this list, so its own visual behavior is unchanged.
    // flightsPending is a plain synchronous counter, NOT itself an animation -- it
    // increments the instant SolitaireGame.setOnCardMoved fires, before the flight's own
    // coroutine has even started, so the s.won-but-still-mid-flight window a few screens
    // down never has the gap the original single-var version's own comment already
    // called out (see moveInFlight below).
    var activeFlights by remember { mutableStateOf<List<Flight>>(emptyList()) }
    var flightsPending by remember { mutableStateOf(0) }
    val moveInFlight = flightsPending > 0

    // Item 6 (Maximum tier): how many finale moves autoCompleteStep() has played so far
    // this run -- drives both the finale driver's shrinking stagger and this flight
    // callback's own mild acceleration curve on flight duration, reset once the finale
    // ends (see the dedicated LaunchedEffect below).
    var finaleStepsPlayed by remember { mutableStateOf(0) }
    fun currentFlightDurationMs(): Int = if (finaleStepsPlayed > 0) {
        (MOVE_FLIGHT_MS - finaleStepsPlayed * FINALE_ACCEL_MS_PER_STEP).coerceAtLeast(FINALE_MIN_FLIGHT_MS)
    } else {
        MOVE_FLIGHT_MS
    }

    // game.setOnCardMoved below registers launchFlight exactly ONCE per game session
    // (LaunchedEffect(context) only re-runs when the session's context itself changes,
    // not on every recomposition) -- so launchFlight's own closure would otherwise keep
    // seeing whichever `enhanced` value happened to be current the moment that
    // registration ran, never a later one, if the player flips Enhanced Move Animations
    // mid-session. rememberUpdatedState is exactly Compose's documented fix for a
    // long-lived callback that needs to read a value fresh on every call regardless of
    // when its own closure was captured.
    val currentEnhanced by rememberUpdatedState(enhanced)

    fun launchFlight(move: CardMove) {
        flightsPending++
        if (!currentEnhanced) {
            flightsPending--
            return
        }
        // Live position lookups, not a one-time snapshot -- see Flight's own KDoc
        // for why a frozen Offset would go stale if the window/fold shape changes
        // while this flight is still airborne.
        val startOf: () -> Offset = when (val src = move.source) {
            SelectionSource.Waste -> ({ wastePos })
            is SelectionSource.Tableau -> ({ tableauTopPos[src.index] ?: wastePos })
        }
        val endOf: () -> Offset = when (val dest = move.destination) {
            is MoveDestination.Tableau -> ({ tableauTopPos[dest.index] ?: startOf() })
            is MoveDestination.Foundation -> ({ foundationPos[dest.suit] ?: startOf() })
        }
        scope.launch {
            val flight = Flight(move.card.toVisual(), startOf, endOf)
            activeFlights = activeFlights + flight
            flight.progress.animateTo(1f, animationSpec = tween(currentFlightDurationMs()))
            activeFlights = activeFlights - flight
            flightsPending--
        }
    }

    // ---- staggered deal-in (item 4), reusing the same Flight/FlyingCardOverlay ----
    // machinery as the move-flight above. dealRevealCounts null means "not
    // staggering, render s.tableau as dealt" (the default, and also where this
    // lands once every card has landed); non-null maps column index -> how many
    // of THAT column's own cards are currently revealed, which visibleColumn()
    // below uses to truncate the real state for display while its later cards
    // are still ghost-flying in from the stock.
    var dealFlights by remember { mutableStateOf<List<Flight>>(emptyList()) }
    var dealRevealCounts by remember { mutableStateOf<Map<Int, Int>?>(null) }
    LaunchedEffect(state) {
        val newState = state ?: return@LaunchedEffect
        if (newState.lastAction != "New deal") return@LaunchedEffect
        val sizes = newState.tableau.map { it.faceDown.size + it.faceUp.size }
        if (!enhanced || sizes.sum() == 0) {
            dealRevealCounts = null
            return@LaunchedEffect
        }
        dealRevealCounts = sizes.indices.associateWith { 0 }
        val cardWidthPx = with(density) { cardWidth.toPx() }
        val cardHeightPx = with(density) { cardHeight.toPx() }
        val overlapPx = cardHeightPx * TABLEAU_OVERLAP_FRACTION
        var globalIndex = 0
        coroutineScope {
            sizes.forEachIndexed { colIndex, size ->
                val col = newState.tableau[colIndex]
                val cardsInCol = col.faceDown + col.faceUp
                repeat(size) { depth ->
                    val myGlobalIndex = globalIndex++
                    launch {
                        delay(myGlobalIndex * DEAL_STAGGER_MS)
                        val card = cardsInCol.getOrNull(depth) ?: return@launch
                        val isTopOfColumn = depth == cardsInCol.lastIndex
                        // Live lookups, same reasoning as launchFlight's own KDoc reference
                        // above -- a fold/rotation mid-cascade shouldn't leave a still-airborne
                        // dealt card flying toward its pre-rotation column slot.
                        val startOf: () -> Offset = { stockPos }
                        val endOf: () -> Offset = {
                            val origin = tableauOriginPos[colIndex] ?: stockPos
                            origin + Offset(cardWidthPx / 2f, cardHeightPx / 2f + overlapPx * depth)
                        }
                        val flight = Flight(card.toVisual(faceDown = !isTopOfColumn), startOf, endOf)
                        dealFlights = dealFlights + flight
                        flight.progress.animateTo(1f, animationSpec = tween(DEAL_FLIGHT_MS))
                        dealFlights = dealFlights - flight
                        val current = dealRevealCounts ?: emptyMap()
                        dealRevealCounts = current + (colIndex to ((current[colIndex] ?: 0) + 1))
                    }
                }
            }
        }
        // Every staggered card above has now landed (coroutineScope suspends
        // until all its launched children finish) -- fall back to rendering
        // s.tableau directly, same as the !enhanced path.
        dealRevealCounts = null
    }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.setOnCardMoved { move -> launchFlight(move) }
        game.startMatch()
        sounds.playShuffle()
    }

    // Solitaire's own persisted draw-count preference (item 5) -- applied
    // whenever the store's Flow emits (its own initial load included), fully
    // decoupled from the context-init effect above since tapStock() isn't
    // reachable until well after both have had a chance to run.
    LaunchedEffect(drawThree) {
        game.drawThree = drawThree
    }

    val autoCompleting = game.isAutoCompleting.value

    // Drives SolitaireGame.autoCompleteStep() one card at a time instead of
    // resolving the whole deal in a single frame — same "keyed on state,
    // delay, then take the next automated step" shape as MancalaScreen's
    // CPU-turn LaunchedEffect. Also keyed on `autoCompleting` itself (not
    // just `state`) because tapping the Auto-complete button flips that flag
    // without changing `state`, and this effect has to (re)start right then,
    // not wait for a state change that already happened.
    //
    // The delay used to be a blind 400ms; now that every auto-played move
    // drives the same fly-to-destination overlay a manual move does (item 3),
    // it's matched to that flight's own tween duration instead so the next
    // step lands right as the previous card visually finishes -- collapsing
    // to a near-zero wait under Reduced Motion (nothing to watch happen), and
    // falling back to the original 400ms when Enhanced Move Animations is off
    // but motion isn't reduced (no flight to sync to, but still worth pacing
    // for readability, exactly as before that setting existed).
    LaunchedEffect(state, autoCompleting) {
        // Maximum tier drives its own concurrent-flight finale below instead —
        // see that LaunchedEffect's KDoc.
        if (maximumTier && enhanced) return@LaunchedEffect
        if (!autoCompleting) return@LaunchedEffect
        val s = state ?: return@LaunchedEffect
        if (s.won) return@LaunchedEffect
        val stepDelay = when {
            reducedMotion -> REDUCED_MOTION_AUTOCOMPLETE_DELAY_MS
            enhanced -> MOVE_FLIGHT_MS.toLong()
            else -> LEGACY_AUTOCOMPLETE_DELAY_MS
        }
        delay(stepDelay)
        game.autoCompleteStep()
    }

    // Item 6's finale flourish state, Maximum tier only: a brief hit-stop (a deliberate
    // pause, the final King frozen at rest) followed by a small decaying camera-shake on
    // the felt table -- triggered once by the finale driver right below, only on an
    // actual win. finaleFlourishActive additionally holds SolvedPanel back for that same
    // brief window so the panel doesn't pop up mid-shake (Standard tier never sets this,
    // so its SolvedPanel timing is completely unaffected). Declared here, before the
    // finale driver, since that effect increments hitStopTrigger.
    var hitStopTrigger by remember { mutableStateOf(0) }
    var finaleFlourishActive by remember { mutableStateOf(false) }
    val tableShakeX = remember { Animatable(0f) }

    // Item 6, Maximum tier only: decouples the engine's own pace from render pace for
    // the auto-complete finale instead of gating each move on the previous flight's FULL
    // completion. Drives autoCompleteStep() itself, capping how many flights are in the
    // air at once (FINALE_MAX_CONCURRENT_FLIGHTS) and shrinking the stagger between
    // steps as more cards land (a mild acceleration curve, floored so it never becomes
    // an unreadable blur) rather than waiting a whole flight's duration between every
    // single step. Once the heuristic runs out of moves (or wins), waits for every
    // still-airborne card to actually land, then -- only on a win -- triggers the
    // hit-stop + camera-shake finale flourish below.
    LaunchedEffect(autoCompleting, maximumTier, enhanced) {
        if (!autoCompleting || !maximumTier || !enhanced) return@LaunchedEffect
        finaleStepsPlayed = 0
        while (game.isAutoCompleting.value) {
            while (activeFlights.size >= FINALE_MAX_CONCURRENT_FLIGHTS) delay(FINALE_POLL_MS)
            game.autoCompleteStep()
            finaleStepsPlayed++
            val stagger = (FINALE_INITIAL_STAGGER_MS - finaleStepsPlayed * FINALE_ACCEL_STAGGER_MS)
                .coerceAtLeast(FINALE_MIN_STAGGER_MS)
            delay(stagger)
        }
        while (activeFlights.isNotEmpty()) delay(FINALE_POLL_MS)
        if (game.state.value?.won == true) {
            hitStopTrigger++
        }
        finaleStepsPlayed = 0
    }

    // A small, deliberately LIMITED haptic vocabulary (see this bundle's own notes on
    // why only 4 patterns, not one-per-interaction): LIGHT_TICK on selecting a card,
    // NORMAL_ACTION on an ordinary tableau move, STRONG_ACTION on a foundation landing,
    // CELEBRATION on solving the deal. Suppressed for individual moves while
    // isAutoCompleting is true -- a 24-card auto-complete finale buzzing once per card
    // would be exactly the fatigue this vocabulary is deliberately kept small to avoid;
    // the finale is a visual spectacle, not a haptic one. The win buzz itself still
    // fires regardless of how the deal was actually solved (manually or via
    // auto-complete), since CELEBRATION is reserved for the single biggest moment a
    // deal has, not tied to which mechanism produced it.
    var previousWon by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        val cur = state ?: return@LaunchedEffect
        val justWon = cur.won && !previousWon
        previousWon = cur.won
        when {
            justWon -> haptics(HapticSignal.CELEBRATION)
            game.isAutoCompleting.value -> {}
            cur.lastAction.startsWith("Selected") -> haptics(HapticSignal.LIGHT_TICK)
            cur.lastAction.startsWith("Moved") && "foundation" in cur.lastAction -> haptics(HapticSignal.STRONG_ACTION)
            cur.lastAction.startsWith("Moved") -> haptics(HapticSignal.NORMAL_ACTION)
        }
    }

    // Drives the hit-stop + camera-shake declared above the finale driver, whenever it
    // increments hitStopTrigger.
    LaunchedEffect(hitStopTrigger) {
        if (hitStopTrigger == 0) return@LaunchedEffect
        finaleFlourishActive = true
        tableShakeX.snapTo(0f)
        delay(HIT_STOP_MS)
        for (v in SHAKE_STEPS) {
            tableShakeX.animateTo(v, animationSpec = tween(SHAKE_STEP_MS))
        }
        finaleFlourishActive = false
    }

    // Solitaire-specific finale flourish (distinct from UNO's confetti), NOT tier-gated
    // -- fires on every solved deal regardless of Standard/Maximum, same as the
    // per-suit foundation shimmer it triggers alongside (see FoundationPileView) and
    // the card-back fan (see CardBackFanFlourish) rendered from this nonce below.
    var finaleShimmerNonce by remember { mutableStateOf(0) }
    var previousWonForShimmer by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        val cur = state ?: return@LaunchedEffect
        if (cur.won && !previousWonForShimmer) finaleShimmerNonce++
        previousWonForShimmer = cur.won
    }

    // Maximum tier only: a very subtle sheen crossing the waste card after ~15s of no
    // input -- Solitaire is the one game in the suite where a player plausibly sits
    // still thinking for this long. Restarts from zero on every real interaction
    // (lastInteractionAt in the key), and repeats every ~15s for as long as the player
    // stays genuinely idle rather than firing once and going quiet.
    var wasteIdleSheen by remember { mutableStateOf(false) }
    LaunchedEffect(lastInteractionAt, maximumTier, enhanced, state?.won, autoCompleting) {
        wasteIdleSheen = false
        val wonOrUnknown = state?.won ?: true
        if (!maximumTier || !enhanced || wonOrUnknown || autoCompleting) return@LaunchedEffect
        while (true) {
            delay(WASTE_IDLE_DELAY_MS)
            wasteIdleSheen = true
            delay(WASTE_IDLE_SHEEN_MS.toLong())
            wasteIdleSheen = false
        }
    }

    val s = state ?: return
    val gamesWon = game.gamesWon.value
    val dealing = dealRevealCounts != null

    // Primary-only (no secondary/hand content in this game) — same treatment
    // as TicTacToeScreen/HangmanScreen: caps + centers on a Tab S9 / unfolded
    // Fold instead of the table sitting stretched across the full width.
    //
    // Wrapped in an outer, unpadded Box so the flight overlays below (which
    // position themselves using root-absolute coordinates captured via
    // positionInRoot()) share the exact same coordinate frame as the piles
    // they're flying between -- AdaptiveTwoPane's own .padding(16.dp) would
    // otherwise shift everything inside it away from true root-relative (0,0).
    Box(modifier = Modifier.fillMaxSize()) {
        AdaptiveTwoPane(
            foldState = LocalFoldState.current,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            primary = {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Games won: $gamesWon", style = MaterialTheme.typography.labelLarge)
                                Text("Score: ${s.score}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                s.lastAction,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Table identity (item 7): a traditional green baize baseline behind
                        // the whole play surface -- Solitaire had none at all before this pass.
                        // tablePerspectiveTilt/specularSweep are applied to this ONE container
                        // (the surface everything sits "on"), not the outer screen chrome above
                        // (score/lastAction) or below (Undo/Auto-complete/etc.) -- exactly the
                        // same "whole board, not whole screen" scope Checkers/Chess/Mancala's own
                        // tablePerspectiveTilt call sites already use, so buttons stay flat and
                        // tap-accurate. FELT_BRUSH is a cached (file-level, allocated once) Brush,
                        // not rebuilt per frame -- the "cached baseline" the brief calls for. The
                        // slow specularSweep sheen is the felt's own ambient idle-sheen (always on
                        // whenever 3D Perspective Mode allows it); tableShakeX only ever moves
                        // during item 6's Maximum-tier finale flourish, staying at 0 otherwise.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(FELT_BRUSH)
                                .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
                                .specularSweep(enabled = card3D, tint = FELT_SHEEN_TINT, periodMs = FELT_SHEEN_PERIOD_MS)
                                .graphicsLayer { translationX = tableShakeX.value }
                                .padding(12.dp)
                        ) {
                            // Stock, waste, then the 4 foundations pushed to the far end.
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                StockPileView(
                                    hasCards = s.stock.isNotEmpty(),
                                    width = cardWidth,
                                    height = cardHeight,
                                    modifier = Modifier.onGloballyPositioned { stockPos = it.centerInRoot() },
                                    onClick = {
                                        if (!dealing) {
                                            lastInteractionAt = System.currentTimeMillis()
                                            game.tapStock()
                                            sounds.playDraw()
                                        }
                                    }
                                )
                                WastePileView(
                                    card = s.waste.lastOrNull(),
                                    isSelected = s.selected == SelectionSource.Waste,
                                    width = cardWidth,
                                    height = cardHeight,
                                    card3D = card3D,
                                    idleSheen = wasteIdleSheen,
                                    modifier = Modifier.onGloballyPositioned { wastePos = it.centerInRoot() },
                                    onClick = {
                                        if (!dealing) {
                                            lastInteractionAt = System.currentTimeMillis()
                                            game.tapWaste()
                                            sounds.playTap()
                                        }
                                    }
                                )
                                Spacer(Modifier.width(16.dp))
                                Suit.entries.forEach { suit ->
                                    FoundationPileView(
                                        suit = suit,
                                        cards = s.foundations[suit] ?: emptyList(),
                                        width = cardWidth,
                                        height = cardHeight,
                                        card3D = card3D,
                                        finaleTrigger = finaleShimmerNonce,
                                        modifier = Modifier.onGloballyPositioned { foundationPos[suit] = it.centerInRoot() },
                                        onClick = {
                                            if (!dealing) {
                                                lastInteractionAt = System.currentTimeMillis()
                                                game.tapFoundation(suit)
                                                sounds.playTap()
                                            }
                                        }
                                    )
                                }
                            }

                            Spacer(Modifier.height(24.dp))

                            // 7 tableau columns, cascaded.
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                s.tableau.forEachIndexed { index, column ->
                                    // While dealRevealCounts is staggering (item 4), show only
                                    // however many of this column's own cards have landed so far
                                    // -- see visibleColumn()'s KDoc.
                                    val displayColumn = dealRevealCounts?.let { visibleColumn(column, it[index] ?: 0) } ?: column
                                    TableauColumnView(
                                        column = displayColumn,
                                        index = index,
                                        isSelected = s.selected == SelectionSource.Tableau(index),
                                        width = cardWidth,
                                        height = cardHeight,
                                        card3D = card3D,
                                        enhanced = enhanced,
                                        onClick = {
                                            if (!dealing) {
                                                lastInteractionAt = System.currentTimeMillis()
                                                game.tapTableau(index)
                                                sounds.playTap()
                                            }
                                        },
                                        onTopPositioned = { tableauTopPos[index] = it },
                                        onOriginPositioned = { tableauOriginPos[index] = it }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = game::undo, enabled = game.canUndo.value && !autoCompleting) { Text("Undo") }
                            OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
                            // Only offered once SolitaireState.autoCompleteAvailable holds — see
                            // SolitaireGame's KDoc on why that check (no card face-down anywhere)
                            // guarantees the rest of the deal is winnable.
                            if (s.autoCompleteAvailable) {
                                Button(onClick = game::startAutoComplete, enabled = !autoCompleting) {
                                    Text(if (autoCompleting) "Auto-completing…" else "Auto-complete")
                                }
                            }
                            // Draw-1/draw-3 (item 5) -- a real, persisted, player-visible
                            // setting rather than a fixed rule, see SolitairePrefsStore.kt.
                            // Applies to future stock draws only; no restriction against
                            // changing it mid-deal since nothing about it is destructive.
                            OutlinedButton(onClick = { scope.launch { prefsStore.setDrawThree(!drawThree) } }) {
                                Text(if (drawThree) "Draw 3" else "Draw 1")
                            }
                            // Motion-intensity tier (item 6) -- see SolitaireMotionPrefsStore.kt's
                            // KDoc for exactly what Maximum unlocks over Standard.
                            OutlinedButton(onClick = { scope.launch { motionPrefsStore.setMotionTier(if (maximumTier) SolitaireMotionTier.STANDARD else SolitaireMotionTier.MAXIMUM) } }) {
                                Text(if (maximumTier) "Motion: Maximum" else "Motion: Standard")
                            }
                        }
                    }

                    // Sequencing note: gated on !moveInFlight, not just s.won, so this
                    // can't pop up while the winning card is still visibly mid-flight to
                    // its foundation -- moveInFlight flips true synchronously the instant
                    // SolitaireGame reports the move, well before this composable would
                    // otherwise see s.won with no flight yet in progress. Also held back by
                    // !finaleFlourishActive (Maximum tier only -- always false on Standard,
                    // so its panel timing is unaffected) so the panel doesn't pop up mid
                    // hit-stop/camera-shake.
                    if (s.won && !moveInFlight && !finaleFlourishActive) {
                        SolvedPanel(
                            // playShuffle() previously only ever played once, from the
                            // context-init effect above -- fine when a fresh deal was a
                            // silent instant pop, but item 4 gives every new deal (this
                            // button's included) a staggered visual cascade that deserves
                            // the same sound cue the very first deal already gets.
                            onNewGame = { game.playAgain(); sounds.playShuffle() },
                            onBackToMenu = game::leaveSession
                        )
                    }
                }
            }
        )

        activeFlights.forEach { flight ->
            FlyingCardOverlay(flight = flight, cardWidth = cardWidth, cardHeight = cardHeight, use3D = card3D)
        }
        dealFlights.forEach { flight ->
            FlyingCardOverlay(flight = flight, cardWidth = cardWidth, cardHeight = cardHeight, use3D = card3D)
        }

        // Combined finale flourish (item 7), NOT tier-gated -- a card-back fan flourish
        // distinct from UNO's confetti, played alongside the simultaneous per-suit
        // foundation shimmer (see FoundationPileView) whenever finaleShimmerNonce
        // advances (i.e. every solved deal, Standard or Maximum).
        if (enhanced) {
            CardBackFanFlourish(trigger = finaleShimmerNonce, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** Root-relative center of a laid-out node — positionInRoot() alone gives the top-left corner, and every flight target/origin in this file wants the center a card actually renders around (same technique MancalaScreen's pitCenter() uses for seed flights). */
private fun LayoutCoordinates.centerInRoot(): Offset = positionInRoot() + Offset(size.width / 2f, size.height / 2f)

/**
 * Truncates [column] to its first [revealedCount] dealt cards (bottom-to-top,
 * matching deal()'s own per-column order in SolitaireGame.kt) for the
 * staggered new-deal reveal (item 4) — the result has the exact same
 * "N-1 face-down, then 1 face-up on top" shape a real column at that card
 * count has, so TableauColumnView needs no special-casing to render a
 * partially-dealt column; it just looks like a smaller, in-progress one.
 */
private fun visibleColumn(column: TableauColumn, revealedCount: Int): TableauColumn {
    val total = column.faceDown.size + column.faceUp.size
    if (revealedCount >= total) return column
    val faceDown = column.faceDown.take(minOf(revealedCount, column.faceDown.size))
    val faceUp = if (revealedCount > column.faceDown.size) column.faceUp else emptyList()
    return TableauColumn(faceDown = faceDown, faceUp = faceUp)
}

/**
 * One card's in-flight visual, from [startProvider] to [endProvider] as
 * [progress] runs 0f..1f — the shared arc/fade/toss math every flight in this
 * screen uses, whether it's the single just-moved card (item 2/3) or one of
 * several concurrent ghosts in the staggered new-deal cascade (item 4).
 * Mirrors UnoScreen's FlyingCard/FlyingCardOverlay pair; the difference is
 * each Flight owns its own [progress] Animatable instead of sharing one
 * across the whole screen, since a staggered deal genuinely has multiple
 * cards in the air at once (UnoScreen only ever has one).
 *
 * Endpoints are LIVE lookups, not frozen Offsets captured once at launch:
 * stockPos/wastePos/tableauTopPos/foundationPos (SolitaireScreen) are all
 * re-derived every layout pass via onGloballyPositioned, including the one a
 * fold/rotation/window-shape change triggers mid-flight. A flight that
 * captured a fixed pixel destination at launch time would animate toward
 * wherever that pile USED to be if the window changed shape while it was
 * still in the air (a real risk during the Maximum-tier concurrent-flight
 * auto-complete finale, where several flights can be airborne for a few
 * hundred ms at once) — [FlyingCardOverlay] instead calls these providers
 * fresh on every recomposition (i.e. every animation frame), so an in-flight
 * card always heads toward the CURRENT position of its real source/destination.
 */
private class Flight(val visual: CardVisual, val startProvider: () -> Offset, val endProvider: () -> Offset) {
    val progress = Animatable(0f)
}

/**
 * Renders [flight] traveling from its start to its end position as its
 * progress runs 0f -> 1f, with a slight upward arc and a shrink+fade near
 * the finish so it reads as "landing" rather than sliding flatly across the
 * table — same easing shape as UnoScreen's own FlyingCardOverlay. [use3D]
 * adds a real perspective roll via [card3DToss] (Settings -> Display ->
 * "3D Perspective Mode", already resolved with reducedMotion by the caller).
 */
@Composable
private fun FlyingCardOverlay(flight: Flight, cardWidth: Dp, cardHeight: Dp, use3D: Boolean) {
    val progress = flight.progress.value
    // Re-read live every recomposition (i.e. every animation frame this flight is
    // in the air) rather than once at launch time -- see Flight's own KDoc for why
    // a frozen Offset would go stale if the window/fold shape changes mid-flight.
    val start = flight.startProvider()
    val end = flight.endProvider()
    // Eases faster than linear so the card feels "thrown" rather than
    // conveyor-belted: quick to leave its source, settling into the target.
    val eased = 1f - (1f - progress) * (1f - progress)
    val x = start.x + (end.x - start.x) * eased
    // A small upward arc (negative = up in screen space) peaking at the
    // midpoint — a real toss rises before it lands, a straight lerp doesn't.
    val arc = -160f * eased * (1f - eased)
    val y = start.y + (end.y - start.y) * eased + arc
    val scale = 1f - 0.1f * eased
    // Fades out only in the final stretch of the flight, so the ghost
    // dissolves right as it merges into the real card already sitting at
    // the destination, rather than visibly overlapping it at full opacity.
    val fadeOutStart = 0.85f
    val alpha = if (progress > fadeOutStart) 1f - (progress - fadeOutStart) / (1f - fadeOutStart) else 1f

    val density = LocalDensity.current
    val halfWidthPx = with(density) { (cardWidth / 2).toPx() }
    val halfHeightPx = with(density) { (cardHeight / 2).toPx() }

    Box(
        modifier = Modifier
            .offset { IntOffset((x - halfWidthPx).roundToInt(), (y - halfHeightPx).roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .then(if (use3D) Modifier.card3DToss(progress) else Modifier)
    ) {
        PlayingCardView(card = flight.visual, width = cardWidth, height = cardHeight)
    }
}

@Composable
private fun StockPileView(hasCards: Boolean, width: Dp, height: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.clickable(onClick = onClick)) {
        if (hasCards) {
            PlayingCardView(
                card = CardVisual(id = -1, label = "", backgroundColor = Color.White, faceDown = true),
                width = width,
                height = height
            )
        } else {
            EmptyPileSlot(width = width, height = height, symbol = "↺")
        }
    }
}

/** "Seven of Spades" style label for TalkBack — Card.label ("7♠"/rank.label+suit.symbol) is a visual-only shorthand, not something a screen reader should read character-by-character. Rank/Suit enum names (SEVEN, SPADES, ...) already spell the words out, so no separate name table is needed. */
private fun Card.accessibleLabel(): String =
    "${rank.name.lowercase().replaceFirstChar { it.uppercase() }} of ${suit.name.lowercase().replaceFirstChar { it.uppercase() }}"

/** Kings/Queens/Jacks get the shared foil sheen (item 8) — the printed face most likely to have real foil/gilt on an actual deck. */
private fun Card.isFoilRank(): Boolean = rank == Rank.JACK || rank == Rank.QUEEN || rank == Rank.KING

/** Shared foil-sheen modifier for a face-up face card, via the shared PremiumShaders specular sweep (item 8) — a no-op Modifier off 3D Perspective Mode, on a non-face card, or on a face-down one. */
@Composable
private fun faceCardFoilModifier(card: Card, faceDown: Boolean, card3D: Boolean): Modifier =
    Modifier.specularSweep(enabled = card3D && !faceDown && card.isFoilRank(), tint = FOIL_TINT, periodMs = FOIL_SHEEN_PERIOD_MS)

@Composable
private fun WastePileView(
    card: Card?,
    isSelected: Boolean,
    width: Dp,
    height: Dp,
    card3D: Boolean,
    idleSheen: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val description = if (card != null) "${card.accessibleLabel()}, waste pile" else "Empty waste pile"
    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .clickable(onClick = onClick)
            .then(if (isSelected) Modifier.border(3.dp, Color(0xFFFFC107), RoundedCornerShape(10.dp)) else Modifier)
    ) {
        if (card != null) {
            // Item 8's foil sheen (face cards) and the Maximum-tier idle sheen (item 6, ~15s
            // of no input) can both land on the waste's top card -- they're independent
            // specularSweep passes, harmlessly layered via .then() when both happen to apply.
            val idleModifier = Modifier.specularSweep(enabled = idleSheen, tint = WASTE_IDLE_TINT, periodMs = WASTE_IDLE_SHEEN_MS)
            PlayingCardView(
                card = card.toVisual(),
                width = width,
                height = height,
                modifier = faceCardFoilModifier(card, faceDown = false, card3D = card3D).then(idleModifier)
            )
        } else {
            EmptyPileSlot(width = width, height = height, symbol = "")
        }
    }
}

@Composable
private fun FoundationPileView(
    suit: Suit,
    cards: List<Card>,
    width: Dp,
    height: Dp,
    card3D: Boolean,
    /** Bumped once per solved deal (see finaleShimmerNonce at the call site) -- re-triggers this pile's own completion shimmer even if IT completed earlier than the deal as a whole did, so all 4 foundations shimmer together at the finale (item 7's combined flourish) on top of each one's own one-shot completion shimmer. */
    finaleTrigger: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val top = cards.lastOrNull()
    val description = if (top != null) "${top.accessibleLabel()}, foundation pile" else "Empty foundation, ${suit.name.lowercase()}"
    val complete = cards.size == Rank.entries.size

    // Item 7's per-suit one-shot completion shimmer: fires the instant this stack reaches
    // 13 cards, and again whenever finaleTrigger advances while still complete (the
    // combined-finale re-trigger described on that parameter's own KDoc above).
    var previousComplete by remember { mutableStateOf(complete) }
    var shimmerActive by remember { mutableStateOf(false) }
    LaunchedEffect(complete, finaleTrigger) {
        val justCompleted = complete && !previousComplete
        previousComplete = complete
        if (card3D && (justCompleted || (complete && finaleTrigger > 0))) {
            shimmerActive = true
            delay(FOUNDATION_SHIMMER_MS.toLong())
            shimmerActive = false
        }
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .clickable(onClick = onClick)
            .specularSweep(enabled = shimmerActive, tint = FOUNDATION_SHIMMER_TINT, periodMs = FOUNDATION_SHIMMER_MS)
    ) {
        if (top != null) {
            // A complete stack's top card is always the King -- already covered by the
            // shared face-card foil helper, so no separate "completed foundation" case is
            // needed here beyond the per-suit shimmer above.
            PlayingCardView(
                card = top.toVisual(),
                width = width,
                height = height,
                modifier = faceCardFoilModifier(top, faceDown = false, card3D = card3D)
            )
        } else {
            EmptyPileSlot(
                width = width,
                height = height,
                symbol = suit.symbol,
                symbolColor = if (suit.isRed) Color(0xFFD32F2F) else Color(0xFF212121)
            )
        }
    }
}

/** A column's own single tap target, cascading every card (hidden or shown) with a vertical offset — a Compose "solitaire fan" down a column instead of FannedHand's horizontal one. */
@Composable
private fun TableauColumnView(
    column: TableauColumn,
    index: Int,
    isSelected: Boolean,
    width: Dp,
    height: Dp,
    card3D: Boolean,
    enhanced: Boolean,
    onClick: () -> Unit,
    onTopPositioned: (Offset) -> Unit,
    onOriginPositioned: (Offset) -> Unit
) {
    val overlap = height * TABLEAU_OVERLAP_FRACTION
    val cards: List<Pair<Card, Boolean>> = column.faceDown.map { it to true } + column.faceUp.map { it to false }
    val totalHeight = if (cards.isEmpty()) height else height + overlap * (cards.size - 1)
    val columnLabel = "tableau column ${index + 1}"

    Box(
        modifier = Modifier
            .width(width)
            .height(totalHeight)
            // The column's own top-left corner, stable regardless of card count
            // since the enclosing Row top-aligns every column and this Box has a
            // fixed width — see [onOriginPositioned]'s param doc at the call site.
            .onGloballyPositioned { onOriginPositioned(it.positionInRoot()) }
            .clickable(onClick = onClick)
            .then(if (cards.isEmpty()) Modifier.semantics { contentDescription = "Empty, $columnLabel" } else Modifier)
    ) {
        if (cards.isEmpty()) {
            // A move destination (e.g. a King onto an empty column) needs this
            // anchor exactly like a non-empty column's top card does -- without
            // it, tableauTopPos[index] would only ever have been set the last
            // time this column had a card (single-card moves mean it always had
            // exactly one right before emptying, at this same offset-0 slot, so
            // it happens to coincide today, but reporting it explicitly here
            // doesn't leave that an unstated coincidence).
            Box(modifier = Modifier.onGloballyPositioned { onTopPositioned(it.centerInRoot()) }) {
                EmptyPileSlot(width = width, height = height, symbol = "")
            }
        } else {
            cards.forEachIndexed { i, (card, faceDown) ->
                val isTopCard = i == cards.lastIndex
                val cardDescription = if (faceDown) "Face-down card, $columnLabel" else "${card.accessibleLabel()}, $columnLabel"
                // Keyed on the card's own stable id (games/cards/Card.kt) rather than
                // its index in this list, so the SAME composable instance -- and the
                // reveal-flip Animatable it remembers -- survives this exact card
                // sliding from "last of faceDown" to "the sole faceUp" when it's
                // exposed, instead of being torn down and recreated with no memory
                // of having been face-down a moment ago. See RevealingTableauCard.
                key(card.id) {
                    Box(
                        modifier = Modifier
                            .offset(y = overlap * i)
                            .semantics { contentDescription = cardDescription }
                            .then(if (isTopCard) Modifier.onGloballyPositioned { onTopPositioned(it.centerInRoot()) } else Modifier)
                    ) {
                        RevealingTableauCard(
                            card = card,
                            faceDown = faceDown,
                            width = width,
                            height = height,
                            card3D = card3D,
                            enhanced = enhanced,
                            modifier = if (isTopCard && isSelected) {
                                Modifier.border(3.dp, Color(0xFFFFC107), RoundedCornerShape(10.dp))
                            } else Modifier
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders one tableau card, animating the standard Klondike auto-reveal the
 * instant it transitions from face-down to face-up — removeFromSource()
 * (SolitaireGame.kt) flips a column's next card face-up in the very same
 * state update that removes the card sitting above it, so today this was a
 * silent, instant swap despite being the most common "something happened"
 * moment in a deal. The transition is detected locally (mirrors
 * CheckersScreen.PieceView's justPromoted — this composable instance
 * persists across it because the caller wraps it in `key(card.id)`, so a
 * plain remembered "was it face-down last frame" check is enough, no
 * whole-board diff needed).
 *
 * Gated on [enhanced] (a new stylistic motion upgrade, not a correctness
 * fix — see this bundle's shared-infra note on that flag): off, this stays
 * exactly today's instant swap. On, [card3D] additionally picks the STYLE:
 * a real [card3DFlip] through 3D space when perspective mode is on, else a
 * plain 2D cross-fade between the two faces.
 */
@Composable
private fun RevealingTableauCard(
    card: Card,
    faceDown: Boolean,
    width: Dp,
    height: Dp,
    card3D: Boolean,
    enhanced: Boolean,
    modifier: Modifier = Modifier
) {
    var previousFaceDown by remember { mutableStateOf(faceDown) }
    val justRevealed = previousFaceDown && !faceDown
    val revealProgress = remember { Animatable(if (justRevealed && enhanced) 0f else 1f) }
    LaunchedEffect(faceDown) {
        if (justRevealed) {
            if (enhanced) {
                revealProgress.snapTo(0f)
                revealProgress.animateTo(1f, animationSpec = tween(REVEAL_FLIP_MS))
            } else {
                revealProgress.snapTo(1f)
            }
        }
        previousFaceDown = faceDown
    }

    when {
        revealProgress.value < 1f && card3D -> {
            // card3DFlip's documented contract: swap the drawn face at the
            // halfway point where the card is edge-on.
            val visual = if (revealProgress.value < 0.5f) card.toVisual(faceDown = true) else card.toVisual(faceDown = false)
            PlayingCardView(card = visual, width = width, height = height, modifier = modifier.card3DFlip(revealProgress.value))
        }
        revealProgress.value < 1f -> {
            // card3D is off but enhanced still wants better than an instant swap.
            Box(modifier = modifier) {
                PlayingCardView(
                    card = card.toVisual(faceDown = true),
                    width = width,
                    height = height,
                    modifier = Modifier.graphicsLayer { alpha = 1f - revealProgress.value }
                )
                PlayingCardView(
                    card = card.toVisual(faceDown = false),
                    width = width,
                    height = height,
                    modifier = Modifier.graphicsLayer { alpha = revealProgress.value }
                )
            }
        }
        else -> {
            // Item 8: Kings/Queens/Jacks get the shared foil-sheen shader once settled
            // face-up (never on a face-down card, which faceCardFoilModifier already
            // excludes on its own).
            PlayingCardView(
                card = card.toVisual(faceDown = faceDown),
                width = width,
                height = height,
                modifier = modifier.then(faceCardFoilModifier(card, faceDown = faceDown, card3D = card3D))
            )
        }
    }
}

@Composable
private fun EmptyPileSlot(width: Dp, height: Dp, symbol: String, symbolColor: Color = Color.White.copy(alpha = 0.7f)) {
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (symbol.isNotEmpty()) {
            Text(symbol, color = symbolColor, fontSize = (width.value * 0.35f).sp)
        }
    }
}

@Composable
private fun SolvedPanel(onNewGame: () -> Unit, onBackToMenu: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Material3Card(modifier = Modifier.padding(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Solved!", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onNewGame) { Text("New Game") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
            }
        }
    }
}

/**
 * Item 7's combined-finale flourish: a fan of face-down card backs that pop
 * out from center and settle/fade, distinct from UNO's falling confetti
 * (a different shape and motion entirely, per this bundle's own brief) —
 * played once per [trigger] advance (see finaleShimmerNonce at the call
 * site), alongside every foundation's own shimmer. Self-retiring: once its
 * own progress animation finishes, it draws nothing, so the caller doesn't
 * need to separately track "is this still playing."
 */
@Composable
private fun CardBackFanFlourish(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger <= 0) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(FAN_FLOURISH_MS))
    }
    if (progress.value >= 1f) return

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val eased = 1f - (1f - progress.value) * (1f - progress.value)
        val fadeOutStart = 0.7f
        val alpha = if (progress.value > fadeOutStart) 1f - (progress.value - fadeOutStart) / (1f - fadeOutStart) else 1f
        for (i in 0 until FAN_CARD_COUNT) {
            val angle = -FAN_SPREAD_DEG / 2f + (FAN_SPREAD_DEG / (FAN_CARD_COUNT - 1)) * i
            Box(
                modifier = Modifier.graphicsLayer {
                    rotationZ = angle
                    translationY = -FAN_RADIUS_PX * eased
                    this.alpha = alpha
                    scaleX = 0.55f + 0.45f * eased
                    scaleY = 0.55f + 0.45f * eased
                }
            ) {
                PlayingCardView(
                    card = CardVisual(id = -100 - i, label = "", backgroundColor = Color.White, faceDown = true),
                    width = 40.dp,
                    height = 58.dp
                )
            }
        }
    }
}

/** Fraction of a card's own height each tableau cascade step overlaps by — shared between TableauColumnView's real layout and the staggered deal-in's analytical per-slot targeting (item 4), which must agree exactly or dealt ghosts land off from where their real card actually settles. */
private const val TABLEAU_OVERLAP_FRACTION = 0.28f

/** Tween duration for a single fly-to-destination flight (item 2) — also what auto-complete's pacing (item 3) waits for so each step lands right as the previous card visually finishes. */
private const val MOVE_FLIGHT_MS = 300

/** Tween duration for one card's flight during the staggered deal-in (item 4). */
private const val DEAL_FLIGHT_MS = 280

/** How far apart successive cards launch during the staggered deal-in (item 4) — within the ~40-60ms window the brief calls for. */
private const val DEAL_STAGGER_MS = 50L

/** Tween duration for the tableau auto-reveal flip/cross-fade (item 1) — matches the house "meaningful board event" duration CheckersDisplay.cpp/ESP32 established. */
private const val REVEAL_FLIP_MS = 350

/** Auto-complete's original per-step pacing, kept as the fallback when Enhanced Move Animations is off but motion isn't reduced (item 3) — nothing to sync a delay to without a flight, but still worth pacing for readability, exactly as before that setting existed. */
private const val LEGACY_AUTOCOMPLETE_DELAY_MS = 400L

/** Auto-complete's per-step pacing under Reduced Motion (item 3) — deliberately not 0: still yields a frame between steps rather than resolving 52 cards in one, while collapsing the wait to something a player wouldn't call "an animation." */
private const val REDUCED_MOTION_AUTOCOMPLETE_DELAY_MS = 30L

// ---- item 6: Maximum-tier concurrent-flight auto-complete finale ----

/** How many auto-complete flights are allowed in the air at once during the Maximum-tier finale — the "3-4 CONCURRENT flight objects" the brief calls for. */
private const val FINALE_MAX_CONCURRENT_FLIGHTS = 4

/** Poll interval while the finale driver waits for a free flight slot or for the last flights to land — short enough to feel immediate, cheap enough to not matter. */
private const val FINALE_POLL_MS = 16L

/** Stagger between successive autoCompleteStep() calls at the very start of the finale, before any acceleration has applied. */
private const val FINALE_INITIAL_STAGGER_MS = 140L

/** How much the finale's own stagger shrinks per step played — the "mild acceleration curve as the finale progresses" the brief calls for. */
private const val FINALE_ACCEL_STAGGER_MS = 4L

/** Floor for the finale's shrinking stagger — keeps the tail of a long finale readable instead of degenerating into an instant blur. */
private const val FINALE_MIN_STAGGER_MS = 40L

/** How much a single flight's own duration shrinks per finale step played, mirroring the stagger's acceleration so flights and pacing speed up together. */
private const val FINALE_ACCEL_MS_PER_STEP = 3

/** Floor for a finale flight's own shrinking duration. */
private const val FINALE_MIN_FLIGHT_MS = 140

/** How long the final King's landing freezes before the camera-shake plays — the "hit-stop" the brief calls for. */
private const val HIT_STOP_MS = 90L

/** Duration of each individual step of the decaying camera-shake below. */
private const val SHAKE_STEP_MS = 45

/** A small decaying horizontal camera-shake — translationX steps applied to the felt table container, settling back to 0. */
private val SHAKE_STEPS = listOf(16f, -12f, 8f, -5f, 2f, 0f)

// ---- item 7: table identity + foundation/finale flourishes ----

/** Traditional green baize, cached once at file scope rather than rebuilt per frame/recomposition — the "cached baseline" the brief calls for. */
private val FELT_BRUSH = Brush.radialGradient(colors = listOf(Color(0xFF1B5E3A), Color(0xFF0B3D22)))

/** The felt's own slow, always-on (when 3D Perspective Mode allows it) idle-sheen — deliberately faint since it sweeps the WHOLE table, not one card. */
private val FELT_SHEEN_TINT = Color.White.copy(alpha = 0.05f)
private const val FELT_SHEEN_PERIOD_MS = 7000

/** Gold foil tint for face-card (item 8) sheen. */
private val FOIL_TINT = Color(0xFFFFD54F).copy(alpha = 0.20f)
private const val FOIL_SHEEN_PERIOD_MS = 3400

/** Tint/duration for a foundation stack's one-shot completion shimmer (item 7). */
private val FOUNDATION_SHIMMER_TINT = Color(0xFFFFF8C6).copy(alpha = 0.4f)
private const val FOUNDATION_SHIMMER_MS = 900

/** Duration of the combined-finale card-back fan flourish (item 7). */
private const val FAN_FLOURISH_MS = 900
private const val FAN_CARD_COUNT = 9
private const val FAN_SPREAD_DEG = 80f
private const val FAN_RADIUS_PX = 110f

// ---- item 6: Maximum-tier idle waste-card sheen ----

/** How long the player has to sit idle before the waste card gets its subtle sheen. */
private const val WASTE_IDLE_DELAY_MS = 15_000L

/** How long each idle-sheen pass lasts (and its specularSweep period) before it repeats, as long as the player stays idle. */
private const val WASTE_IDLE_SHEEN_MS = 2600
private val WASTE_IDLE_TINT = Color.White.copy(alpha = 0.14f)
