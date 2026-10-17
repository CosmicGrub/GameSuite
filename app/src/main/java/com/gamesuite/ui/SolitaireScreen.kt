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
import androidx.compose.material3.Card as Material3Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
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
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.ui.effects.shakeSteps
import com.gamesuite.ui.effects.specularSweep
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
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
 * difficulty lever (see SolitaireGame's KDoc).
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play / Undo / Hint / Draw 1-3 / Motion tier /
 * Back to Menu) plus its BackHandler. Leaving mid-deal is an abort (never recorded) unless earlier
 * deals this session were already solved: then it goes through `leaveSession()` so those wins still
 * count. The status row reserves [GameChromeEndInset] so the corner button never covers it.
 *
 * LAYOUT: [solitaireFitLayout] against the measured space of the board's own slot. Card width is
 * (space - gaps) / 7, capped by the card-size setting ([LocalCardScale]) and by a nominal tall
 * column, so the seven columns always fit across; only a window narrower than ~270dp scrolls
 * sideways, and a column taller than the slot scrolls the board vertically. Face-up cards step
 * down far enough to show their own rank and suit (the overlay in [SolitaireCardView] draws a
 * corner index sized to the card, since the shared PlayingCardView's own index is too small below
 * ~64dp), face-down cards step down only a sliver.
 *
 * RESUME / UNDO / HINT: the engine implements the shell's resume and undo contracts. The start-up
 * effect resumes a saved deal when the player picked Resume, otherwise deals. Undo takes back one
 * move (including a stock draw). Hint highlights a source and a destination with a ring AND a text
 * badge (FROM / TO / DRAW) plus a spoken "suggested" description, so it is not colour alone.
 *
 * ACCESSIBILITY: every pile and column is a Role.Button with a spoken description; selection adds
 * a "Selected" state, a lift and a two-tone ring; the status line is a polite live region; card
 * art itself is decorative.
 *
 * Also owns the full animation treatment from the animation/physics pitch's
 * Solitaire section:
 * - The tableau auto-reveal flip ([SolitaireRevealingCard]) — the "next card turns
 *   face-up" moment removeFromSource() (SolitaireGame.kt) computes.
 * - A fly-to-destination overlay ([activeFlights]) for every manual or
 *   auto-complete move, driven by [SolitaireGame.setOnCardMoved] — the same
 *   "engine reports the event, screen owns the animation" split, and the
 *   same arc/fade/[card3DToss] visual language, UnoScreen's
 *   fly-to-discard-pile overlay uses. Supports several CONCURRENT flights for
 *   the Maximum-tier auto-complete finale.
 * - A staggered deal-in ([dealFlights]) reusing that machinery, keyed on
 *   [SolitaireGame.dealSeq] so only a FRESH deal plays it (never Undo or Resume).
 * - A persisted draw-1/draw-3 preference ([SolitairePrefsStore]).
 *
 * Every one of those is gated behind [LocalEnhancedAnimations] (falling back
 * to today's exact instant/flat behavior when it — or Reduced Motion — is
 * off), with [LocalCard3DMode] additionally choosing perspective vs. plain
 * 2D style where the pitch calls for it.
 *
 * The "premium 2026 vision" pass on top of all that (see
 * [SolitaireMotionPrefsStore] for the Standard/Maximum tier): a real haptic
 * vocabulary (LIGHT_TICK/NORMAL_ACTION/STRONG_ACTION/CELEBRATION, plus FAILURE with an
 * invalid buzz for a rejected placement), a felt table identity, face-card foil sheen, a per-suit
 * foundation completion shimmer plus a combined finale shimmer + card-back fan flourish, a
 * Maximum-tier concurrent-flight auto-complete finale with a hit-stop + camera-shake on the final
 * King landing, and a Maximum-tier idle sheen on the waste card after ~15s of no input.
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
    val density = LocalDensity.current

    val reducedMotion = LocalReducedMotion.current
    val card3D = LocalCard3DMode.current && !reducedMotion
    val colorblind = LocalColorblindMode.current
    // Gates every NEW motion upgrade in this file (reveal flip, fly-to-destination,
    // staggered deal) -- off (either the setting itself or reduced motion) means
    // every one of them collapses to today's exact instant/flat behavior.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion

    val prefsStore = remember { SolitairePrefsStore(androidContext) }
    val drawThree by prefsStore.drawThree.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()

    // Ambient music -- gated on BOTH the dedicated ambient-music setting AND the existing
    // master sound toggle, same AND pattern every other feature that respects
    // CardSounds.soundEnabled already uses.
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.SOLITAIRE, enabled = musicEnabled)

    // Standard/Maximum motion tier (see SolitaireMotionPrefsStore.kt's KDoc for exactly
    // what Maximum unlocks).
    val motionPrefsStore = remember { SolitaireMotionPrefsStore(androidContext) }
    val motionTier by motionPrefsStore.motionTier.collectAsState(initial = SolitaireMotionTier.STANDARD)
    val maximumTier = motionTier == SolitaireMotionTier.MAXIMUM

    val hintMove by game.hintMove
    val dealSeq by game.dealSeq
    val rejectedCount by game.rejectedCount

    // Bumped by every manual tap (stock/waste/foundation/tableau) below -- drives the
    // Maximum-tier idle waste-card sheen a few effects down.
    var lastInteractionAt by remember { mutableStateOf(System.currentTimeMillis()) }

    // ---- screen-space pile positions, kept fresh via onGloballyPositioned ----
    // Two different anchors are tracked per tableau column for two different needs:
    // [tableauTopPos] is the CURRENT top card's center -- an accurate destination for a move
    // landing on an already-dealt column -- while [tableauOriginPos] is the column's own fixed
    // top-left corner, stable across the whole deal-in regardless of how many cards have
    // landed yet, which the staggered deal needs to compute each card's exact final slot.
    var stockPos by remember { mutableStateOf(Offset.Zero) }
    var wastePos by remember { mutableStateOf(Offset.Zero) }
    val foundationPos = remember { mutableStateMapOf<Suit, Offset>() }
    val tableauTopPos = remember { mutableStateMapOf<Int, Offset>() }
    val tableauOriginPos = remember { mutableStateMapOf<Int, Offset>() }
    // Root-space top-left of the board slot (where the flight overlay is drawn), so flight
    // coordinates measured with positionInRoot() line up wherever the slot sits on screen.
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    // Card metrics of the current layout, in px, for the deal-in's analytical slot targeting.
    // Plain fields (not state): they are only read inside per-frame lambdas.
    val metrics = remember { SolitaireMetrics() }

    // ---- fly-to-destination overlay for every manual/auto-complete move ----
    // Supports several CONCURRENT flights (the Maximum-tier finale). flightsPending is a plain
    // synchronous counter that increments the instant SolitaireGame.setOnCardMoved fires,
    // before the flight's own coroutine has even started, so the s.won-but-still-mid-flight
    // window never has a gap (see moveInFlight below).
    var activeFlights by remember { mutableStateOf<List<SolitaireFlight>>(emptyList()) }
    var flightsPending by remember { mutableStateOf(0) }
    val moveInFlight = flightsPending > 0

    // How many finale moves autoCompleteStep() has played so far this run -- drives both the
    // finale driver's shrinking stagger and this flight callback's own mild acceleration curve.
    var finaleStepsPlayed by remember { mutableStateOf(0) }
    fun currentFlightDurationMs(): Int = if (finaleStepsPlayed > 0) {
        (SOLITAIRE_MOVE_FLIGHT_MS - finaleStepsPlayed * SOLITAIRE_FINALE_ACCEL_MS_PER_STEP).coerceAtLeast(SOLITAIRE_FINALE_MIN_FLIGHT_MS)
    } else {
        SOLITAIRE_MOVE_FLIGHT_MS
    }

    // game.setOnCardMoved below registers launchFlight exactly ONCE per game session, so its
    // closure would otherwise keep seeing whichever `enhanced` value was current at that
    // moment. rememberUpdatedState is Compose's documented fix for a long-lived callback.
    val currentEnhanced by rememberUpdatedState(enhanced)

    fun launchFlight(move: CardMove) {
        flightsPending++
        if (!currentEnhanced) {
            flightsPending--
            return
        }
        // The engine reports the move BEFORE the new state is committed, so the source pile
        // is still where the card was lifted from: freeze the start point now. The end point
        // stays a live lookup so a fold/rotation mid-flight still lands on the real pile.
        val startPoint: Offset = when (val src = move.source) {
            SelectionSource.Waste -> wastePos
            is SelectionSource.Tableau -> tableauTopPos[src.index] ?: wastePos
        }
        val endOf: () -> Offset = when (val dest = move.destination) {
            is MoveDestination.Tableau -> ({ tableauTopPos[dest.index] ?: startPoint })
            is MoveDestination.Foundation -> ({ foundationPos[dest.suit] ?: startPoint })
        }
        scope.launch {
            val flight = SolitaireFlight(move.card, faceDown = false, startProvider = { startPoint }, endProvider = endOf)
            activeFlights = activeFlights + flight
            flight.progress.animateTo(1f, animationSpec = tween(currentFlightDurationMs()))
            activeFlights = activeFlights - flight
            flightsPending--
        }
    }

    // ---- staggered deal-in, reusing the same flight machinery ----
    // dealRevealCounts null means "not staggering, render s.tableau as dealt" (the default,
    // and where this lands once every card has landed); non-null maps column index -> how
    // many of THAT column's own cards are currently revealed.
    var dealFlights by remember { mutableStateOf<List<SolitaireFlight>>(emptyList()) }
    var dealRevealCounts by remember { mutableStateOf<Map<Int, Int>?>(null) }
    // Keyed on the engine's deal counter, NOT on `state`: Undo back to the first move of a deal
    // and Resume both change `state` but must never replay the deal-in.
    LaunchedEffect(dealSeq) {
        if (dealSeq == 0) return@LaunchedEffect
        val newState = state ?: return@LaunchedEffect
        val sizes = newState.tableau.map { it.faceDown.size + it.faceUp.size }
        if (!enhanced || sizes.sum() == 0) {
            dealRevealCounts = null
            return@LaunchedEffect
        }
        dealRevealCounts = sizes.indices.associateWith { 0 }
        var globalIndex = 0
        coroutineScope {
            sizes.forEachIndexed { colIndex, size ->
                val col = newState.tableau[colIndex]
                val cardsInCol = col.faceDown + col.faceUp
                repeat(size) { depth ->
                    val myGlobalIndex = globalIndex++
                    launch {
                        delay(myGlobalIndex * SOLITAIRE_DEAL_STAGGER_MS)
                        val card = cardsInCol.getOrNull(depth) ?: return@launch
                        val isTopOfColumn = depth == cardsInCol.lastIndex
                        // Live lookups: a fold/rotation mid-cascade shouldn't leave a
                        // still-airborne dealt card flying toward its pre-rotation slot. Every
                        // card above a dealt card is face-down, so its slot is depth steps of
                        // the face-down step below the column's origin.
                        val startOf: () -> Offset = { stockPos }
                        val endOf: () -> Offset = {
                            val origin = tableauOriginPos[colIndex] ?: stockPos
                            origin + Offset(metrics.cardWidthPx / 2f, metrics.cardHeightPx / 2f + metrics.downStepPx * depth)
                        }
                        val flight = SolitaireFlight(card, faceDown = !isTopOfColumn, startProvider = startOf, endProvider = endOf)
                        dealFlights = dealFlights + flight
                        flight.progress.animateTo(1f, animationSpec = tween(SOLITAIRE_DEAL_FLIGHT_MS))
                        dealFlights = dealFlights - flight
                        val current = dealRevealCounts ?: emptyMap()
                        dealRevealCounts = current + (colIndex to ((current[colIndex] ?: 0) + 1))
                    }
                }
            }
        }
        // Every staggered card has now landed -- fall back to rendering s.tableau directly.
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
        // Picking this game from the menu after leaving it mid-deal offers Resume; when the
        // player took it, the engine is put back exactly as it was (same stock order, same
        // tally) and no deal-in plays. Otherwise deal a fresh game.
        if (!sessionManager.tryResume(game)) {
            game.startMatch()
            sounds.playShuffle()
        }
    }

    // Solitaire's own persisted draw-count preference -- applied whenever the store's Flow
    // emits (its own initial load included).
    LaunchedEffect(drawThree) {
        game.drawThree = drawThree
    }

    val autoCompleting = game.isAutoCompleting.value

    // Drives SolitaireGame.autoCompleteStep() one card at a time instead of resolving the whole
    // deal in a single frame. Also keyed on `autoCompleting` itself because tapping the
    // Auto-complete button flips that flag without changing `state`.
    LaunchedEffect(state, autoCompleting) {
        // Maximum tier drives its own concurrent-flight finale below instead.
        if (maximumTier && enhanced) return@LaunchedEffect
        if (!autoCompleting) return@LaunchedEffect
        val s = state ?: return@LaunchedEffect
        if (s.won) return@LaunchedEffect
        val stepDelay = when {
            reducedMotion -> SOLITAIRE_REDUCED_MOTION_AUTOCOMPLETE_DELAY_MS
            enhanced -> SOLITAIRE_MOVE_FLIGHT_MS.toLong()
            else -> SOLITAIRE_LEGACY_AUTOCOMPLETE_DELAY_MS
        }
        delay(stepDelay)
        game.autoCompleteStep()
    }

    // Maximum-tier finale flourish state: a brief hit-stop followed by a small decaying
    // camera-shake on the felt table, triggered once by the finale driver right below, only on
    // an actual win. finaleFlourishActive additionally holds the Solved panel back for that
    // same window so it doesn't pop up mid-shake.
    var hitStopTrigger by remember { mutableStateOf(0) }
    var finaleFlourishActive by remember { mutableStateOf(false) }
    val tableShakeX = remember { Animatable(0f) }

    // Maximum tier only: decouples the engine's own pace from render pace for the auto-complete
    // finale, capping how many flights are in the air at once and shrinking the stagger as more
    // cards land. Once the heuristic runs out of moves (or wins), waits for every airborne card
    // to land, then -- only on a win -- triggers the hit-stop + camera-shake finale flourish.
    LaunchedEffect(autoCompleting, maximumTier, enhanced) {
        if (!autoCompleting || !maximumTier || !enhanced) return@LaunchedEffect
        finaleStepsPlayed = 0
        while (game.isAutoCompleting.value) {
            while (activeFlights.size >= SOLITAIRE_FINALE_MAX_CONCURRENT_FLIGHTS) delay(SOLITAIRE_FINALE_POLL_MS)
            game.autoCompleteStep()
            finaleStepsPlayed++
            val stagger = (SOLITAIRE_FINALE_INITIAL_STAGGER_MS - finaleStepsPlayed * SOLITAIRE_FINALE_ACCEL_STAGGER_MS)
                .coerceAtLeast(SOLITAIRE_FINALE_MIN_STAGGER_MS)
            delay(stagger)
        }
        while (activeFlights.isNotEmpty()) delay(SOLITAIRE_FINALE_POLL_MS)
        if (game.state.value?.won == true) {
            hitStopTrigger++
        }
        finaleStepsPlayed = 0
    }

    // A small, deliberately LIMITED haptic vocabulary: LIGHT_TICK on selecting a card,
    // NORMAL_ACTION on an ordinary tableau move, STRONG_ACTION on a foundation landing,
    // CELEBRATION (plus the success chime) on solving the deal -- solitaire is solo, so every win
    // is the human's. Suppressed for individual moves while isAutoCompleting is true. Undo and
    // Resume set their own lastAction ("Undid last move"/"Game resumed"), so they never replay a
    // move buzz, and the first state this screen sees is only a baseline (previousWon == null),
    // so resuming an already-solved deal does not celebrate again.
    var previousWon by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state) {
        val cur = state ?: return@LaunchedEffect
        val before = previousWon
        previousWon = cur.won
        val justWon = cur.won && before == false
        when {
            justWon -> {
                haptics(HapticSignal.CELEBRATION)
                playSfx(SfxKind.SUCCESS_CHIME)
            }
            game.isAutoCompleting.value -> {}
            cur.lastAction.startsWith("Selected") -> haptics(HapticSignal.LIGHT_TICK)
            cur.lastAction.startsWith("Moved") && "foundation" in cur.lastAction -> haptics(HapticSignal.STRONG_ACTION)
            cur.lastAction.startsWith("Moved") -> haptics(HapticSignal.NORMAL_ACTION)
        }
    }

    // A rejected placement gets a failure haptic and the shared invalid buzz. The baseline is
    // the count at entry, so re-entering the screen with a leftover count stays silent.
    val rejectedBaseline = remember { rejectedCount }
    LaunchedEffect(rejectedCount) {
        if (rejectedCount > rejectedBaseline) {
            haptics(HapticSignal.FAILURE)
            playSfx(SfxKind.INVALID_BUZZ)
        }
    }

    // Drives the hit-stop + camera-shake declared above the finale driver.
    LaunchedEffect(hitStopTrigger) {
        if (hitStopTrigger == 0) return@LaunchedEffect
        finaleFlourishActive = true
        tableShakeX.snapTo(0f)
        delay(SOLITAIRE_HIT_STOP_MS)
        // SOLITAIRE_SHAKE_STEPS are in dp; magnitudePx = the screen density turns them into px.
        tableShakeX.shakeSteps(magnitudePx = density.density, stepDurationMs = SOLITAIRE_SHAKE_STEP_MS, pattern = SOLITAIRE_SHAKE_STEPS)
        finaleFlourishActive = false
    }

    // Solitaire-specific finale flourish (distinct from UNO's confetti), NOT tier-gated. Same
    // baseline rule as the haptics above.
    var finaleShimmerNonce by remember { mutableStateOf(0) }
    var previousWonForShimmer by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state) {
        val cur = state ?: return@LaunchedEffect
        if (cur.won && previousWonForShimmer == false) finaleShimmerNonce++
        previousWonForShimmer = cur.won
    }

    // Maximum tier only: a very subtle sheen crossing the waste card after ~15s of no input.
    var wasteIdleSheen by remember { mutableStateOf(false) }
    LaunchedEffect(lastInteractionAt, maximumTier, enhanced, state?.won, autoCompleting) {
        wasteIdleSheen = false
        val wonOrUnknown = state?.won ?: true
        if (!maximumTier || !enhanced || wonOrUnknown || autoCompleting) return@LaunchedEffect
        while (true) {
            delay(SOLITAIRE_WASTE_IDLE_DELAY_MS)
            wasteIdleSheen = true
            delay(SOLITAIRE_WASTE_IDLE_SHEEN_MS.toLong())
            wasteIdleSheen = false
        }
    }

    val s = state ?: return
    val gamesWon = game.gamesWon.value
    val dealing = dealRevealCounts != null
    val canAct = !dealing && !s.won && !autoCompleting

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-deal discards
    // ONLY the unfinished deal: if earlier deals this session were already solved, leaving goes
    // through leaveSession() so those wins still count; with nothing solved it is a pure abort
    // (never a win or loss). A solved deal's panel leaves through leaveSession(), which scores
    // the session.
    GameChrome(
        helpTitle = "How to Play Solitaire",
        helpText = "Build the four foundation piles, one per suit, from Ace up to King. " +
            "In the seven tableau columns, stack cards downward in alternating colours, " +
            "for example a red 6 on a black 7. A face-down card turns over by itself when the card above it leaves.\n\n" +
            "Tap a card to pick it up (the top waste card or the top card of a column), then tap a column or a foundation to place it. " +
            "Only the top card moves, and only a King can start an empty column.\n\n" +
            "Tap the stock to draw a card (three at a time with Draw 3 in the menu, but only the top waste card is playable). " +
            "When the stock runs out, tap it to recycle the waste. Auto-complete appears once no card is face-down. " +
            "Undo takes back one move and Hint shows a move you can make.",
        matchInProgress = !s.won,
        onLeave = game::leaveSession,
        onAbort = { if (gamesWon > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = gamesWon == 0,
        onUndo = { game.undo() },
        canUndo = game.canUndo,
        onHint = { if (canAct) game.hint() },
        leaveTitle = "Leave this deal?",
        leaveBody = if (gamesWon > 0) {
            "This deal is still in progress and won't count, but the deals you've already solved stay on your record."
        } else {
            "This deal is still in progress. Leaving now won't count it as a win or a loss."
        },
        extraItems = { dismiss ->
            // Draw-1/draw-3 and the motion tier are permanent settings, not play actions, so
            // they live in the corner menu instead of crowding the play row.
            DropdownMenuItem(
                text = { Text(if (drawThree) "Draw 3 (switch to Draw 1)" else "Draw 1 (switch to Draw 3)") },
                onClick = {
                    dismiss()
                    scope.launch { prefsStore.setDrawThree(!drawThree) }
                }
            )
            DropdownMenuItem(
                text = { Text(if (maximumTier) "Motion: Maximum (switch to Standard)" else "Motion: Standard (switch to Maximum)") },
                onClick = {
                    dismiss()
                    scope.launch { motionPrefsStore.setMotionTier(if (maximumTier) SolitaireMotionTier.STANDARD else SolitaireMotionTier.MAXIMUM) }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            SolitaireStatusRow(gamesWon = gamesWon, score = s.score, lastAction = s.lastAction)
            Spacer(Modifier.height(8.dp))

            // The board's own slot: everything below is sized from THIS slot's measured space.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val feltPad = 8.dp
                val fit = solitaireFitLayout(
                    availableWidthDp = maxWidth.value - feltPad.value * 2f,
                    availableHeightDp = maxHeight.value - feltPad.value * 2f,
                    columns = s.tableau.map { SolitaireColumnShape(it.faceDown.size, it.faceUp.size) },
                    cardScale = cardScale
                )
                val cardWidth = fit.cardWidthDp.dp
                val cardHeight = fit.cardHeightDp.dp
                val gap = fit.gapDp.dp
                val downStep = fit.downStepDp.dp
                val upStep = fit.upStepDp.dp
                SideEffect {
                    metrics.cardWidthPx = with(density) { cardWidth.toPx() }
                    metrics.cardHeightPx = with(density) { cardHeight.toPx() }
                    metrics.downStepPx = with(density) { downStep.toPx() }
                }
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
                ) {
                    // Table identity: a traditional green baize behind the whole play surface.
                    // tablePerspectiveTilt/specularSweep are applied to this ONE container (the
                    // surface everything sits "on"), not the status row or buttons, so those stay
                    // flat and tap-accurate. SOLITAIRE_FELT_BRUSH is allocated once at file scope.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SOLITAIRE_FELT_BRUSH)
                            .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
                            .specularSweep(enabled = card3D, tint = SOLITAIRE_FELT_SHEEN_TINT, periodMs = SOLITAIRE_FELT_SHEEN_PERIOD_MS)
                            .graphicsLayer { translationX = tableShakeX.value }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(vScroll)
                                .then(if (fit.scrollsHorizontally) Modifier.horizontalScroll(hScroll) else Modifier)
                                .padding(feltPad),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Stock, waste, a gap, then the 4 foundations: the same seven columns
                            // as the tableau below, so every pile sits above its own column.
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(gap),
                                verticalAlignment = Alignment.Top
                            ) {
                                SolitaireStockPile(
                                    stockCount = s.stock.size,
                                    hintRole = if (hintMove?.isStockDraw == true) SolitaireHintRole.DRAW else SolitaireHintRole.NONE,
                                    width = cardWidth,
                                    height = cardHeight,
                                    modifier = Modifier.onGloballyPositioned { stockPos = it.solitaireCenterInRoot() },
                                    onClick = {
                                        if (!dealing) {
                                            lastInteractionAt = System.currentTimeMillis()
                                            game.tapStock()
                                            sounds.playDraw()
                                        }
                                    }
                                )
                                SolitaireWastePile(
                                    card = s.waste.lastOrNull(),
                                    isSelected = s.selected == SelectionSource.Waste,
                                    hintRole = if (hintMove?.source == SelectionSource.Waste) SolitaireHintRole.FROM else SolitaireHintRole.NONE,
                                    width = cardWidth,
                                    height = cardHeight,
                                    card3D = card3D,
                                    idleSheen = wasteIdleSheen,
                                    modifier = Modifier.onGloballyPositioned { wastePos = it.solitaireCenterInRoot() },
                                    onClick = {
                                        if (!dealing) {
                                            lastInteractionAt = System.currentTimeMillis()
                                            game.tapWaste()
                                            sounds.playTap()
                                        }
                                    }
                                )
                                Spacer(Modifier.width(cardWidth))
                                Suit.entries.forEach { suit ->
                                    SolitaireFoundationPile(
                                        suit = suit,
                                        cards = s.foundations[suit] ?: emptyList(),
                                        hintRole = if ((hintMove?.destination as? MoveDestination.Foundation)?.suit == suit) SolitaireHintRole.TO else SolitaireHintRole.NONE,
                                        selectionActive = s.selected != null,
                                        width = cardWidth,
                                        height = cardHeight,
                                        card3D = card3D,
                                        colorblind = colorblind,
                                        finaleTrigger = finaleShimmerNonce,
                                        modifier = Modifier.onGloballyPositioned { foundationPos[suit] = it.solitaireCenterInRoot() },
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

                            Spacer(Modifier.height(SOLITAIRE_SECTION_GAP_DP.dp))

                            // 7 tableau columns, cascaded.
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(gap),
                                verticalAlignment = Alignment.Top
                            ) {
                                s.tableau.forEachIndexed { index, column ->
                                    // While dealRevealCounts is staggering, show only however many
                                    // of this column's own cards have landed so far -- see
                                    // solitaireVisibleColumn()'s KDoc.
                                    val displayColumn = dealRevealCounts?.let { solitaireVisibleColumn(column, it[index] ?: 0) } ?: column
                                    val hintRole = when {
                                        (hintMove?.source as? SelectionSource.Tableau)?.index == index -> SolitaireHintRole.FROM
                                        (hintMove?.destination as? MoveDestination.Tableau)?.index == index -> SolitaireHintRole.TO
                                        else -> SolitaireHintRole.NONE
                                    }
                                    SolitaireTableauColumn(
                                        column = displayColumn,
                                        index = index,
                                        isSelected = s.selected == SelectionSource.Tableau(index),
                                        selectionActive = s.selected != null,
                                        hintRole = hintRole,
                                        width = cardWidth,
                                        height = cardHeight,
                                        downStep = downStep,
                                        upStep = upStep,
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
                    }

                    // The overlay shares the slot's coordinate frame: flight positions come from
                    // positionInRoot(), so the slot's own root origin is subtracted when drawing.
                    activeFlights.forEach { flight ->
                        SolitaireFlyingCard(flight = flight, cardWidth = cardWidth, cardHeight = cardHeight, use3D = card3D, origin = overlayOrigin)
                    }
                    dealFlights.forEach { flight ->
                        SolitaireFlyingCard(flight = flight, cardWidth = cardWidth, cardHeight = cardHeight, use3D = card3D, origin = overlayOrigin)
                    }

                    // Combined finale flourish, NOT tier-gated -- a card-back fan flourish distinct
                    // from UNO's confetti, alongside every foundation's own shimmer.
                    if (enhanced) {
                        SolitaireCardBackFan(trigger = finaleShimmerNonce, modifier = Modifier.align(Alignment.Center))
                    }

                    // Sequencing note: gated on !moveInFlight, not just s.won, so this can't pop up
                    // while the winning card is still visibly mid-flight to its foundation. Also
                    // held back by !finaleFlourishActive (Maximum tier only) so the panel doesn't
                    // pop up mid hit-stop/camera-shake.
                    if (s.won && !moveInFlight && !finaleFlourishActive) {
                        SolitaireSolvedPanel(
                            gamesWon = gamesWon,
                            score = s.score,
                            reducedMotion = reducedMotion,
                            // playShuffle() cue for the new deal's staggered cascade.
                            onNewGame = { game.playAgain(); sounds.playShuffle() },
                            onBackToMenu = game::leaveSession
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SolitaireControlsRow(
                canUndo = game.canUndo && !dealing,
                canHint = canAct,
                showAutoComplete = s.autoCompleteAvailable || autoCompleting,
                autoCompleting = autoCompleting,
                canAutoComplete = s.autoCompleteAvailable && !autoCompleting && !dealing,
                onUndo = { game.undo() },
                onHint = { game.hint() },
                onAutoComplete = game::startAutoComplete
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Layout (pure, unit-tested in SolitaireLayoutTest)
// ---------------------------------------------------------------------------

/** One tableau column's card counts, the only thing [solitaireFitLayout] needs to know about it. */
internal data class SolitaireColumnShape(val faceDown: Int, val faceUp: Int)

/**
 * The result of [solitaireFitLayout]. All values are dp. [downStepDp]/[upStepDp] are how far each
 * successive face-down/face-up card sits below the one before it in a column.
 */
internal data class SolitaireLayoutFit(
    val cardWidthDp: Float,
    val cardHeightDp: Float,
    val gapDp: Float,
    val downStepDp: Float,
    val upStepDp: Float,
    /** Width of the seven columns plus gaps. Never exceeds the available width unless [scrollsHorizontally]. */
    val boardWidthDp: Float,
    /** Height of the tallest column at the chosen steps. */
    val tallestColumnDp: Float,
    /** True only when even the minimum card width cannot fit seven columns: the board then pans sideways. */
    val scrollsHorizontally: Boolean,
    /** True when the top row, the gap and the tallest column together exceed the available height: the board then scrolls. */
    val needsVerticalScroll: Boolean
)

internal const val SOLITAIRE_COLUMNS = 7
internal const val SOLITAIRE_CARD_ASPECT = 1.44f
internal const val SOLITAIRE_GAP_DP = 6f
internal const val SOLITAIRE_SECTION_GAP_DP = 12f
internal const val SOLITAIRE_MIN_CARD_WIDTH_DP = 30f

/** Widest a card gets at card-size 1.0; the card-size setting scales this cap. */
internal const val SOLITAIRE_MAX_CARD_WIDTH_DP = 88f

/** Fraction of card height a face-down card steps down by (just a sliver of the one below shows). */
internal const val SOLITAIRE_DOWN_STEP_FRACTION = 0.14f

/** Fraction of card height a face-up card steps down by: enough to show its rank and suit. */
internal const val SOLITAIRE_UP_STEP_FRACTION = 0.30f

/** Steps never shrink below this share of the above, so a corner index always stays readable. */
internal const val SOLITAIRE_MIN_STEP_FACTOR = 0.9f

/** The nominal tall column the width cap is computed against, so card size stays stable during play. */
private const val SOLITAIRE_NOMINAL_DOWN = 6
private const val SOLITAIRE_NOMINAL_UP = 7

/** Height of a column of [down] face-down plus [up] face-up cards. */
internal fun solitaireColumnHeightDp(cardHeight: Float, downStep: Float, upStep: Float, down: Int, up: Int): Float {
    val count = down + up
    if (count <= 0) return cardHeight
    val lastIndex = count - 1
    val lastOffset = if (lastIndex < down) downStep * lastIndex else downStep * down + upStep * (lastIndex - down)
    return cardHeight + lastOffset
}

/**
 * Sizes the Klondike board from the space it really has. Width is the binding constraint on a
 * phone: seven columns and six gaps must fit across, so the card is (width - gaps) / 7, capped by
 * [SOLITAIRE_MAX_CARD_WIDTH_DP] times [cardScale] (the player's card-size setting, so a huge
 * window does not stretch cards) and by what a nominal tall column needs vertically (so a short
 * landscape window gets smaller cards, not an unreachable board). The floor is
 * [SOLITAIRE_MIN_CARD_WIDTH_DP]: below it the board pans sideways rather than shrink further.
 * The step between cards in a column then compresses (never below [SOLITAIRE_MIN_STEP_FACTOR])
 * to fit the tallest actual column; beyond that the board scrolls vertically.
 */
internal fun solitaireFitLayout(
    availableWidthDp: Float,
    availableHeightDp: Float,
    columns: List<SolitaireColumnShape>,
    cardScale: Float
): SolitaireLayoutFit {
    val gap = SOLITAIRE_GAP_DP
    val availW = availableWidthDp.coerceAtLeast(0f)
    val availH = availableHeightDp.coerceAtLeast(0f)
    val rawWidth = ((availW - gap * (SOLITAIRE_COLUMNS - 1)) / SOLITAIRE_COLUMNS).coerceAtLeast(0f)
    val maxWidth = SOLITAIRE_MAX_CARD_WIDTH_DP * cardScale.coerceIn(0.5f, 2f)

    // Width a nominal tall column would need the height to allow, at the minimum step factor.
    val nominalSteps = SOLITAIRE_DOWN_STEP_FRACTION * SOLITAIRE_MIN_STEP_FACTOR * SOLITAIRE_NOMINAL_DOWN +
        SOLITAIRE_UP_STEP_FRACTION * SOLITAIRE_MIN_STEP_FACTOR * (SOLITAIRE_NOMINAL_UP - 1)
    val heightInCards = 2f + nominalSteps // the top row, plus the nominal column
    val widthByHeight = ((availH - SOLITAIRE_SECTION_GAP_DP) / (SOLITAIRE_CARD_ASPECT * heightInCards)).coerceAtLeast(0f)

    val scrollsHorizontally = rawWidth < SOLITAIRE_MIN_CARD_WIDTH_DP
    val width = minOf(rawWidth, maxWidth, widthByHeight).coerceAtLeast(SOLITAIRE_MIN_CARD_WIDTH_DP)
    val height = width * SOLITAIRE_CARD_ASPECT

    val baseDown = height * SOLITAIRE_DOWN_STEP_FRACTION
    val baseUp = height * SOLITAIRE_UP_STEP_FRACTION
    val budget = availH - height - SOLITAIRE_SECTION_GAP_DP
    val naturalTallest = columns.maxOfOrNull { solitaireColumnHeightDp(height, baseDown, baseUp, it.faceDown, it.faceUp) } ?: height
    val factor = if (naturalTallest <= budget || naturalTallest <= height) {
        1f
    } else {
        ((budget - height) / (naturalTallest - height)).coerceIn(SOLITAIRE_MIN_STEP_FACTOR, 1f)
    }
    val downStep = baseDown * factor
    val upStep = baseUp * factor
    val tallest = columns.maxOfOrNull { solitaireColumnHeightDp(height, downStep, upStep, it.faceDown, it.faceUp) } ?: height

    return SolitaireLayoutFit(
        cardWidthDp = width,
        cardHeightDp = height,
        gapDp = gap,
        downStepDp = downStep,
        upStepDp = upStep,
        boardWidthDp = width * SOLITAIRE_COLUMNS + gap * (SOLITAIRE_COLUMNS - 1),
        tallestColumnDp = tallest,
        scrollsHorizontally = scrollsHorizontally,
        needsVerticalScroll = height + SOLITAIRE_SECTION_GAP_DP + tallest > availH + 0.5f
    )
}

/** Card metrics of the live layout in px, for the deal-in's analytical slot targeting. Plain fields on purpose. */
private class SolitaireMetrics {
    var cardWidthPx: Float = 0f
    var cardHeightPx: Float = 0f
    var downStepPx: Float = 0f
}

/** Offset of card [index] below its column's top: face-down cards step by [downStep], face-up by [upStep]. */
private fun solitaireCardOffset(index: Int, downCount: Int, downStep: Dp, upStep: Dp): Dp =
    if (index < downCount) downStep * index else downStep * downCount + upStep * (index - downCount)

// ---------------------------------------------------------------------------
// Top-level helpers
// ---------------------------------------------------------------------------

/** Root-relative center of a laid-out node — positionInRoot() alone gives the top-left corner, and every flight target/origin in this file wants the center a card actually renders around. */
private fun LayoutCoordinates.solitaireCenterInRoot(): Offset = positionInRoot() + Offset(size.width / 2f, size.height / 2f)

/**
 * Truncates [column] to its first [revealedCount] dealt cards (bottom-to-top,
 * matching deal()'s own per-column order in SolitaireGame.kt) for the
 * staggered new-deal reveal — the result has the exact same
 * "N-1 face-down, then 1 face-up on top" shape a real column at that card
 * count has, so the column view needs no special-casing to render a
 * partially-dealt column.
 */
private fun solitaireVisibleColumn(column: TableauColumn, revealedCount: Int): TableauColumn {
    val total = column.faceDown.size + column.faceUp.size
    if (revealedCount >= total) return column
    val faceDown = column.faceDown.take(minOf(revealedCount, column.faceDown.size))
    val faceUp = if (revealedCount > column.faceDown.size) column.faceUp else emptyList()
    return TableauColumn(faceDown = faceDown, faceUp = faceUp)
}

/** "Seven of Spades" style label for TalkBack — Card.label ("7♠") is a visual-only shorthand, not something a screen reader should read character-by-character. */
private fun solitaireCardName(card: Card): String =
    "${card.rank.name.lowercase().replaceFirstChar { it.uppercase() }} of ${card.suit.name.lowercase().replaceFirstChar { it.uppercase() }}"

private fun solitaireSuitName(suit: Suit): String = suit.name.lowercase()

/** Kings/Queens/Jacks get the shared foil sheen — the printed face most likely to have real foil/gilt on an actual deck. */
private fun solitaireIsFoilRank(card: Card): Boolean = card.rank == Rank.JACK || card.rank == Rank.QUEEN || card.rank == Rank.KING

/** Shared foil-sheen modifier for a face-up face card — a no-op Modifier off 3D Perspective Mode, on a non-face card, or on a face-down one. */
@Composable
private fun solitaireFoilModifier(card: Card, faceDown: Boolean, card3D: Boolean): Modifier =
    Modifier.specularSweep(enabled = card3D && !faceDown && solitaireIsFoilRank(card), tint = SOLITAIRE_FOIL_TINT, periodMs = SOLITAIRE_FOIL_SHEEN_PERIOD_MS)

/** Which part of the current hint a pile or column plays. Drawn as a ring PLUS a text badge, never colour alone. */
private enum class SolitaireHintRole(val badge: String) {
    NONE(""),
    FROM("FROM"),
    TO("TO"),
    DRAW("DRAW")
}

private val SOLITAIRE_RING_SHAPE = RoundedCornerShape(10.dp)

/** Two-tone selection ring (dark inner, amber outer) so it reads against both the white card and the green felt. */
private fun Modifier.solitaireSelectionRing(): Modifier = this
    .border(4.dp, Color(0xFF212121), SOLITAIRE_RING_SHAPE)
    .border(2.dp, Color(0xFFFFC107), SOLITAIRE_RING_SHAPE)

/** Two-tone hint ring (dark inner, cyan outer); always paired with a [SolitaireHintBadge]. */
private fun Modifier.solitaireHintRing(): Modifier = this
    .border(4.dp, Color(0xFF00363A), SOLITAIRE_RING_SHAPE)
    .border(2.dp, Color(0xFF00E5FF), SOLITAIRE_RING_SHAPE)

@Composable
private fun BoxScope.SolitaireHintBadge(role: SolitaireHintRole) {
    if (role == SolitaireHintRole.NONE) return
    Text(
        role.badge,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 2.dp)
            .background(Color(0xFF00363A), RoundedCornerShape(50))
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clearAndSetSemantics {}
    )
}

private fun solitaireHintSuffix(role: SolitaireHintRole): String = when (role) {
    SolitaireHintRole.NONE -> ""
    SolitaireHintRole.FROM -> ", hint: move this card"
    SolitaireHintRole.TO -> ", hint: place the card here"
    SolitaireHintRole.DRAW -> ", hint: draw a card"
}

// ---------------------------------------------------------------------------
// Flights
// ---------------------------------------------------------------------------

/**
 * One card's in-flight visual, from [startProvider] to [endProvider] as
 * [progress] runs 0f..1f — the shared arc/fade/toss math every flight in this
 * screen uses, whether it's the single just-moved card or one of several
 * concurrent ghosts in the staggered new-deal cascade. Mirrors UnoScreen's
 * flying-card pair; each flight owns its own [progress] Animatable since a
 * staggered deal genuinely has multiple cards in the air at once.
 *
 * The end point is a LIVE lookup, re-evaluated every animation frame, so a card in the air
 * heads toward the CURRENT position of its real destination even if the window or fold
 * posture changes mid-flight. A move's start point is frozen at the instant it is reported
 * (the source pile is still where the card was lifted from at that moment).
 */
private class SolitaireFlight(
    val card: Card,
    val faceDown: Boolean,
    val startProvider: () -> Offset,
    val endProvider: () -> Offset
) {
    val progress = Animatable(0f)
}

/**
 * Renders [flight] traveling from its start to its end position as its progress runs 0f -> 1f,
 * with a slight upward arc and a shrink+fade near the finish so it reads as "landing" rather
 * than sliding flatly across the table. [use3D] adds a real perspective roll via [card3DToss]
 * (already resolved with reducedMotion by the caller). [origin] is the root-space top-left of
 * the box this overlay is drawn in.
 */
@Composable
private fun SolitaireFlyingCard(flight: SolitaireFlight, cardWidth: Dp, cardHeight: Dp, use3D: Boolean, origin: Offset) {
    val progress = flight.progress.value
    val start = flight.startProvider()
    val end = flight.endProvider()
    // Eases faster than linear so the card feels "thrown" rather than conveyor-belted.
    val eased = 1f - (1f - progress) * (1f - progress)
    val density = LocalDensity.current
    val x = start.x + (end.x - start.x) * eased
    // A small upward arc (negative = up in screen space) peaking at the midpoint, 14dp high.
    val peakPx = with(density) { SOLITAIRE_FLIGHT_ARC_DP.dp.toPx() }
    val arc = -4f * peakPx * eased * (1f - eased)
    val y = start.y + (end.y - start.y) * eased + arc
    val scale = 1f - 0.1f * eased
    // Fades out only in the final stretch, so the ghost dissolves right as it merges into the
    // real card already sitting at the destination.
    val fadeOutStart = 0.85f
    val alpha = if (progress > fadeOutStart) 1f - (progress - fadeOutStart) / (1f - fadeOutStart) else 1f

    val halfWidthPx = with(density) { (cardWidth / 2).toPx() }
    val halfHeightPx = with(density) { (cardHeight / 2).toPx() }

    Box(
        modifier = Modifier
            .offset { IntOffset((x - origin.x - halfWidthPx).roundToInt(), (y - origin.y - halfHeightPx).roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .then(if (use3D) Modifier.card3DToss(progress) else Modifier)
    ) {
        SolitaireCardView(card = flight.card, faceDown = flight.faceDown, width = cardWidth, height = cardHeight)
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** A card back, drawn by the shared PlayingCardView. Decorative: the pile that holds it describes itself. */
@Composable
private fun SolitaireCardBack(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clearAndSetSemantics {}) {
        PlayingCardView(
            card = CardVisual(id = -1, label = "", backgroundColor = Color.White, faceDown = true),
            width = width,
            height = height
        )
    }
}

/**
 * One standard-deck card: the shared PlayingCardView for the card itself, plus (face-up) a corner
 * index drawn here and sized to the card. PlayingCardView's own corner index scales as ~0.16 of the
 * width with a 9sp floor, which is only ~5sp once the card is under ~64dp wide, and a covered
 * face-up card's centre label is hidden by the card above it, so without this overlay a column of
 * covered cards is a stack of blank strips. Decorative: the pile or column describes itself.
 */
@Composable
private fun SolitaireCardView(card: Card, faceDown: Boolean, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(modifier = modifier.clearAndSetSemantics {}) {
        val visual = card.toVisual(faceDown = faceDown)
        PlayingCardView(card = visual, width = width, height = height)
        if (!faceDown) {
            // Sized against the card, not the system font scale (the card itself does not grow with it).
            val fontSize = with(density) { (width.value * 0.27f).coerceAtLeast(10f).dp.toSp() }
            Text(
                card.label,
                color = visual.textColor,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = width * 0.07f, top = 2.dp)
            )
        }
    }
}

@Composable
private fun SolitaireEmptySlot(
    width: Dp,
    height: Dp,
    symbol: String,
    symbolColor: Color = Color.White.copy(alpha = 0.7f),
    bold: Boolean = false
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(SOLITAIRE_RING_SHAPE)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), SOLITAIRE_RING_SHAPE),
        contentAlignment = Alignment.Center
    ) {
        if (symbol.isNotEmpty()) {
            Text(
                symbol,
                color = symbolColor,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontSize = with(density) { (width.value * 0.35f).dp.toSp() },
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

@Composable
private fun SolitaireStockPile(
    stockCount: Int,
    hintRole: SolitaireHintRole,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val description = (if (stockCount > 0) "Stock pile, $stockCount cards" else "Empty stock, tap to recycle the waste") +
        solitaireHintSuffix(hintRole)
    Box(
        modifier = modifier
            .size(width, height)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = if (stockCount > 0) "Draw from stock" else "Recycle waste into stock", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        if (stockCount > 0) {
            SolitaireCardBack(width = width, height = height)
        } else {
            SolitaireEmptySlot(width = width, height = height, symbol = "↺")
        }
        if (hintRole != SolitaireHintRole.NONE) {
            Box(Modifier.fillMaxSize().solitaireHintRing())
            SolitaireHintBadge(hintRole)
        }
    }
}

@Composable
private fun SolitaireWastePile(
    card: Card?,
    isSelected: Boolean,
    hintRole: SolitaireHintRole,
    width: Dp,
    height: Dp,
    card3D: Boolean,
    idleSheen: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val description = (if (card != null) "Waste pile, top card ${solitaireCardName(card)}" else "Empty waste pile") +
        solitaireHintSuffix(hintRole)
    Box(
        modifier = modifier
            .size(width, height)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                onClickLabel = if (isSelected) "Deselect waste card" else "Select waste card",
                role = Role.Button,
                onClick = onClick
            )
            .semantics {
                contentDescription = description
                if (isSelected) stateDescription = "Selected"
            }
    ) {
        if (card != null) {
            // The foil sheen (face cards) and the Maximum-tier idle sheen (~15s of no input)
            // can both land on the waste's top card -- independent specularSweep passes,
            // harmlessly layered via .then() when both happen to apply.
            val idleModifier = Modifier.specularSweep(enabled = idleSheen, tint = SOLITAIRE_WASTE_IDLE_TINT, periodMs = SOLITAIRE_WASTE_IDLE_SHEEN_MS)
            SolitaireCardView(
                card = card,
                faceDown = false,
                width = width,
                height = height,
                modifier = Modifier
                    // A selected card lifts a little, so selection never relies on the ring colour alone.
                    .offset(y = if (isSelected) (-6).dp else 0.dp)
                    .then(if (isSelected) Modifier.solitaireSelectionRing() else Modifier)
                    .then(solitaireFoilModifier(card, faceDown = false, card3D = card3D))
                    .then(idleModifier)
            )
        } else {
            SolitaireEmptySlot(width = width, height = height, symbol = "")
        }
        if (hintRole != SolitaireHintRole.NONE) {
            Box(Modifier.fillMaxSize().solitaireHintRing())
            SolitaireHintBadge(hintRole)
        }
    }
}

@Composable
private fun SolitaireFoundationPile(
    suit: Suit,
    cards: List<Card>,
    hintRole: SolitaireHintRole,
    selectionActive: Boolean,
    width: Dp,
    height: Dp,
    card3D: Boolean,
    colorblind: Boolean,
    /** Bumped once per solved deal (see finaleShimmerNonce at the call site) -- re-triggers this pile's own completion shimmer even if IT completed earlier than the deal as a whole did, so all 4 foundations shimmer together at the finale on top of each one's own one-shot completion shimmer. */
    finaleTrigger: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val top = cards.lastOrNull()
    val suitName = solitaireSuitName(suit)
    val description = (if (top != null) "Foundation $suitName, top card ${solitaireCardName(top)}, ${cards.size} of 13" else "Empty foundation, $suitName") +
        solitaireHintSuffix(hintRole)
    val complete = cards.size == Rank.entries.size

    // One-shot completion shimmer: fires the instant this stack reaches 13 cards, and again
    // whenever finaleTrigger advances while still complete.
    var previousComplete by remember { mutableStateOf(complete) }
    var shimmerActive by remember { mutableStateOf(false) }
    LaunchedEffect(complete, finaleTrigger) {
        val justCompleted = complete && !previousComplete
        previousComplete = complete
        if (card3D && (justCompleted || (complete && finaleTrigger > 0))) {
            shimmerActive = true
            delay(SOLITAIRE_FOUNDATION_SHIMMER_MS.toLong())
            shimmerActive = false
        }
    }

    Box(
        modifier = modifier
            .size(width, height)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                onClickLabel = if (selectionActive) "Place selected card on $suitName foundation" else "Select a card first",
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = description }
            .specularSweep(enabled = shimmerActive, tint = SOLITAIRE_FOUNDATION_SHIMMER_TINT, periodMs = SOLITAIRE_FOUNDATION_SHIMMER_MS)
    ) {
        if (top != null) {
            // A complete stack's top card is always the King -- already covered by the shared
            // face-card foil helper, so no separate "completed foundation" case is needed.
            SolitaireCardView(
                card = top,
                faceDown = false,
                width = width,
                height = height,
                modifier = solitaireFoilModifier(top, faceDown = false, card3D = card3D)
            )
        } else {
            // A light outline glyph on the felt (the old red/near-black glyph on the dark slot was
            // 1.3-2.4:1). The suit's shape is the cue, so it never depends on red vs black.
            SolitaireEmptySlot(
                width = width,
                height = height,
                symbol = suit.symbol,
                symbolColor = Color.White.copy(alpha = if (colorblind) 0.95f else 0.7f),
                bold = colorblind
            )
        }
        if (hintRole != SolitaireHintRole.NONE) {
            Box(Modifier.fillMaxSize().solitaireHintRing())
            SolitaireHintBadge(hintRole)
        }
    }
}

/** A column's own single tap target, cascading every card (hidden or shown) with a vertical offset — a Compose "solitaire fan" down a column instead of FannedHand's horizontal one. */
@Composable
private fun SolitaireTableauColumn(
    column: TableauColumn,
    index: Int,
    isSelected: Boolean,
    selectionActive: Boolean,
    hintRole: SolitaireHintRole,
    width: Dp,
    height: Dp,
    downStep: Dp,
    upStep: Dp,
    card3D: Boolean,
    enhanced: Boolean,
    onClick: () -> Unit,
    onTopPositioned: (Offset) -> Unit,
    onOriginPositioned: (Offset) -> Unit
) {
    val downCount = column.faceDown.size
    val cards: List<Pair<Card, Boolean>> = column.faceDown.map { it to true } + column.faceUp.map { it to false }
    val lastOffset = if (cards.isEmpty()) 0.dp else solitaireCardOffset(cards.lastIndex, downCount, downStep, upStep)
    val totalHeight = height + lastOffset
    val columnNumber = index + 1
    val topCard = column.faceUp.lastOrNull()
    val description = (
        if (topCard == null) {
            "Column $columnNumber, empty"
        } else {
            "Column $columnNumber, $downCount face-down, ${column.faceUp.size} face-up, top card ${solitaireCardName(topCard)}"
        }
        ) + solitaireHintSuffix(hintRole)
    val clickLabel = when {
        isSelected -> "Deselect card in column $columnNumber"
        selectionActive -> "Place selected card on column $columnNumber"
        else -> "Select top card of column $columnNumber"
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(totalHeight)
            // The column's own top-left corner, stable regardless of card count since the
            // enclosing Row top-aligns every column and this Box has a fixed width.
            .onGloballyPositioned { onOriginPositioned(it.positionInRoot()) }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (isSelected) stateDescription = "Selected"
            }
    ) {
        if (cards.isEmpty()) {
            // A move destination (e.g. a King onto an empty column) needs this anchor exactly
            // like a non-empty column's top card does.
            Box(modifier = Modifier.onGloballyPositioned { onTopPositioned(it.solitaireCenterInRoot()) }) {
                SolitaireEmptySlot(width = width, height = height, symbol = "")
                if (hintRole != SolitaireHintRole.NONE) {
                    Box(Modifier.size(width, height).solitaireHintRing())
                    SolitaireHintBadge(hintRole)
                }
            }
        } else {
            cards.forEachIndexed { i, (card, faceDown) ->
                val isTopCard = i == cards.lastIndex
                // Keyed on the card's own stable id (games/cards/Card.kt) rather than its index in
                // this list, so the SAME composable instance -- and the reveal-flip Animatable it
                // remembers -- survives this exact card sliding from "last of faceDown" to "the sole
                // faceUp" when it's exposed. See SolitaireRevealingCard.
                key(card.id) {
                    val lift = if (isTopCard && isSelected) 6.dp else 0.dp
                    Box(
                        modifier = Modifier
                            .offset(y = solitaireCardOffset(i, downCount, downStep, upStep) - lift)
                            .then(if (isTopCard) Modifier.onGloballyPositioned { onTopPositioned(it.solitaireCenterInRoot()) } else Modifier)
                    ) {
                        SolitaireRevealingCard(
                            card = card,
                            faceDown = faceDown,
                            width = width,
                            height = height,
                            card3D = card3D,
                            enhanced = enhanced,
                            modifier = if (isTopCard && isSelected) Modifier.solitaireSelectionRing() else Modifier
                        )
                        if (isTopCard && hintRole != SolitaireHintRole.NONE) {
                            Box(Modifier.size(width, height).solitaireHintRing())
                            SolitaireHintBadge(hintRole)
                        }
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
 * state update that removes the card sitting above it. The transition is
 * detected locally (this composable instance persists across it because the
 * caller wraps it in `key(card.id)`, so a plain remembered "was it face-down
 * last frame" check is enough, no whole-board diff needed).
 *
 * Gated on [enhanced]: off, this stays an instant swap. On, [card3D] picks
 * the STYLE: a real [card3DFlip] through 3D space when perspective mode is
 * on, else a plain 2D cross-fade between the two faces.
 */
@Composable
private fun SolitaireRevealingCard(
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
                revealProgress.animateTo(1f, animationSpec = tween(SOLITAIRE_REVEAL_FLIP_MS))
            } else {
                revealProgress.snapTo(1f)
            }
        }
        previousFaceDown = faceDown
    }

    when {
        revealProgress.value < 1f && card3D -> {
            // card3DFlip's documented contract: swap the drawn face at the halfway point where
            // the card is edge-on.
            val showBack = revealProgress.value < 0.5f
            SolitaireCardView(card = card, faceDown = showBack, width = width, height = height, modifier = modifier.card3DFlip(revealProgress.value))
        }
        revealProgress.value < 1f -> {
            // card3D is off but enhanced still wants better than an instant swap.
            Box(modifier = modifier) {
                SolitaireCardView(
                    card = card,
                    faceDown = true,
                    width = width,
                    height = height,
                    modifier = Modifier.graphicsLayer { alpha = 1f - revealProgress.value }
                )
                SolitaireCardView(
                    card = card,
                    faceDown = false,
                    width = width,
                    height = height,
                    modifier = Modifier.graphicsLayer { alpha = revealProgress.value }
                )
            }
        }
        else -> {
            // Kings/Queens/Jacks get the foil-sheen shader once settled face-up (never on a
            // face-down card, which solitaireFoilModifier already excludes on its own).
            SolitaireCardView(
                card = card,
                faceDown = faceDown,
                width = width,
                height = height,
                modifier = modifier.then(solitaireFoilModifier(card, faceDown = faceDown, card3D = card3D))
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Chrome pieces
// ---------------------------------------------------------------------------

/**
 * Wins, score and the last action's one-line status. Reserves [GameChromeEndInset] at its end so
 * the shared corner menu button never overlaps it. The status text is a polite live region, so a
 * rejected move, a hint or an undo is announced.
 */
@Composable
private fun SolitaireStatusRow(gamesWon: Int, score: Int, lastAction: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = GameChromeEndInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Games won: $gamesWon", style = MaterialTheme.typography.labelLarge)
            Text("Score: $score", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            lastAction,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

/**
 * Visible Undo and Hint (and Auto-complete once it applies) under the board, so none of them is
 * hidden in the corner menu. All 48dp tall; Undo and Hint are disabled, never hidden.
 */
@Composable
private fun SolitaireControlsRow(
    canUndo: Boolean,
    canHint: Boolean,
    showAutoComplete: Boolean,
    autoCompleting: Boolean,
    canAutoComplete: Boolean,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onAutoComplete: () -> Unit
) {
    val padding = PaddingValues(horizontal = 8.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mouse/trackpad hover cursor (Tab S9 DeX / keyboard-cover use case) on every real
        // game-action control button -- purely additive, zero effect on touch input.
        OutlinedButton(
            onClick = onUndo,
            enabled = canUndo,
            contentPadding = padding,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { if (!canUndo) stateDescription = "Nothing to undo" }
        ) { Text("Undo", maxLines = 1) }
        OutlinedButton(
            onClick = onHint,
            enabled = canHint,
            contentPadding = padding,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { if (!canHint) stateDescription = "No hint available right now" }
        ) { Text("Hint", maxLines = 1) }
        // Only offered once SolitaireState.autoCompleteAvailable holds -- see SolitaireGame's KDoc
        // on why that check (no card face-down anywhere) guarantees the rest of the deal is winnable.
        if (showAutoComplete) {
            Button(
                onClick = onAutoComplete,
                enabled = canAutoComplete,
                contentPadding = padding,
                modifier = Modifier
                    .weight(1.7f)
                    .heightIn(min = 48.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text(if (autoCompleting) "Completing…" else "Auto-complete", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The solved-deal panel: a modal over the board (it swallows pointers, so Undo or a stray tap
 * cannot reach the board behind it), with the session tally, a short enter animation (off under
 * Reduced Motion) and a scroll for very short windows.
 */
@Composable
private fun SolitaireSolvedPanel(
    gamesWon: Int,
    score: Int,
    reducedMotion: Boolean,
    onNewGame: () -> Unit,
    onBackToMenu: () -> Unit
) {
    val appear = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) appear.animateTo(1f, animationSpec = tween(SOLITAIRE_PANEL_ENTER_MS))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            // A modal barrier: with a pointerInput node on top, the board behind never sees taps.
            .pointerInput(Unit) { },
        contentAlignment = Alignment.Center
    ) {
        Material3Card(
            modifier = Modifier
                .padding(16.dp)
                .graphicsLayer {
                    alpha = appear.value
                    scaleX = 0.92f + 0.08f * appear.value
                    scaleY = 0.92f + 0.08f * appear.value
                }
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Solved!", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Deals solved this session: $gamesWon",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("Score this deal: $score", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                // Same additive hover-cursor treatment as the in-game control row.
                Button(
                    onClick = onNewGame,
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Play again") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Back to menu") }
                Spacer(modifier = Modifier.height(8.dp))
                com.gamesuite.uikit.ChangeSetupButton(
                    onLeave = onBackToMenu,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                )
            }
        }
    }
}

/**
 * The combined-finale flourish: a fan of face-down card backs that pop out from center and
 * settle/fade, distinct from UNO's falling confetti — played once per [trigger] advance (see
 * finaleShimmerNonce at the call site), alongside every foundation's own shimmer. Self-retiring:
 * once its own progress animation finishes, it draws nothing. The radius is in dp.
 */
@Composable
private fun SolitaireCardBackFan(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger <= 0) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(SOLITAIRE_FAN_FLOURISH_MS))
    }
    if (progress.value >= 1f) return

    Box(modifier = modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val eased = 1f - (1f - progress.value) * (1f - progress.value)
        val fadeOutStart = 0.7f
        val alpha = if (progress.value > fadeOutStart) 1f - (progress.value - fadeOutStart) / (1f - fadeOutStart) else 1f
        for (i in 0 until SOLITAIRE_FAN_CARD_COUNT) {
            val angle = -SOLITAIRE_FAN_SPREAD_DEG / 2f + (SOLITAIRE_FAN_SPREAD_DEG / (SOLITAIRE_FAN_CARD_COUNT - 1)) * i
            Box(
                modifier = Modifier.graphicsLayer {
                    rotationZ = angle
                    translationY = -SOLITAIRE_FAN_RADIUS_DP * density * eased
                    this.alpha = alpha
                    scaleX = 0.55f + 0.45f * eased
                    scaleY = 0.55f + 0.45f * eased
                }
            ) {
                SolitaireCardBack(width = 40.dp, height = 58.dp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Tween duration for a single fly-to-destination flight — also what auto-complete's pacing waits for so each step lands right as the previous card visually finishes. */
private const val SOLITAIRE_MOVE_FLIGHT_MS = 300

/** Tween duration for one card's flight during the staggered deal-in. */
private const val SOLITAIRE_DEAL_FLIGHT_MS = 280

/** How far apart successive cards launch during the staggered deal-in. */
private const val SOLITAIRE_DEAL_STAGGER_MS = 50L

/** Tween duration for the tableau auto-reveal flip/cross-fade — matches the house "meaningful board event" duration. */
private const val SOLITAIRE_REVEAL_FLIP_MS = 350

/** Enter tween for the solved panel (off under Reduced Motion). */
private const val SOLITAIRE_PANEL_ENTER_MS = 250

/** Peak height of a flight's arc. */
private const val SOLITAIRE_FLIGHT_ARC_DP = 14f

/** Auto-complete's original per-step pacing, kept as the fallback when Enhanced Move Animations is off but motion isn't reduced. */
private const val SOLITAIRE_LEGACY_AUTOCOMPLETE_DELAY_MS = 400L

/** Auto-complete's per-step pacing under Reduced Motion — deliberately not 0: still yields a frame between steps rather than resolving 52 cards in one. */
private const val SOLITAIRE_REDUCED_MOTION_AUTOCOMPLETE_DELAY_MS = 30L

// ---- Maximum-tier concurrent-flight auto-complete finale ----

/** How many auto-complete flights are allowed in the air at once during the Maximum-tier finale. */
private const val SOLITAIRE_FINALE_MAX_CONCURRENT_FLIGHTS = 4

/** Poll interval while the finale driver waits for a free flight slot or for the last flights to land. */
private const val SOLITAIRE_FINALE_POLL_MS = 16L

/** Stagger between successive autoCompleteStep() calls at the very start of the finale. */
private const val SOLITAIRE_FINALE_INITIAL_STAGGER_MS = 140L

/** How much the finale's own stagger shrinks per step played. */
private const val SOLITAIRE_FINALE_ACCEL_STAGGER_MS = 4L

/** Floor for the finale's shrinking stagger. */
private const val SOLITAIRE_FINALE_MIN_STAGGER_MS = 40L

/** How much a single flight's own duration shrinks per finale step played. */
private const val SOLITAIRE_FINALE_ACCEL_MS_PER_STEP = 3

/** Floor for a finale flight's own shrinking duration. */
private const val SOLITAIRE_FINALE_MIN_FLIGHT_MS = 140

/** How long the final King's landing freezes before the camera-shake plays. */
private const val SOLITAIRE_HIT_STOP_MS = 90L

/** Duration of each individual step of the decaying camera-shake. */
private const val SOLITAIRE_SHAKE_STEP_MS = 45

/** A small decaying horizontal camera-shake, in dp (scaled by the screen density at the call site), settling back to 0. */
private val SOLITAIRE_SHAKE_STEPS = listOf(6f, -4.5f, 3f, -2f, 1f, 0f)

// ---- table identity + foundation/finale flourishes ----

/** Traditional green baize, cached once at file scope rather than rebuilt per frame/recomposition. */
private val SOLITAIRE_FELT_BRUSH = Brush.radialGradient(colors = listOf(Color(0xFF1B5E3A), Color(0xFF0B3D22)))

/** The felt's own slow, always-on (when 3D Perspective Mode allows it) idle-sheen. */
private val SOLITAIRE_FELT_SHEEN_TINT = Color.White.copy(alpha = 0.05f)
private const val SOLITAIRE_FELT_SHEEN_PERIOD_MS = 7000

/** Gold foil tint for face-card sheen. */
private val SOLITAIRE_FOIL_TINT = Color(0xFFFFD54F).copy(alpha = 0.20f)
private const val SOLITAIRE_FOIL_SHEEN_PERIOD_MS = 3400

/** Tint/duration for a foundation stack's one-shot completion shimmer. */
private val SOLITAIRE_FOUNDATION_SHIMMER_TINT = Color(0xFFFFF8C6).copy(alpha = 0.4f)
private const val SOLITAIRE_FOUNDATION_SHIMMER_MS = 900

/** Duration of the combined-finale card-back fan flourish. */
private const val SOLITAIRE_FAN_FLOURISH_MS = 900
private const val SOLITAIRE_FAN_CARD_COUNT = 9
private const val SOLITAIRE_FAN_SPREAD_DEG = 80f
private const val SOLITAIRE_FAN_RADIUS_DP = 40f

// ---- Maximum-tier idle waste-card sheen ----

/** How long the player has to sit idle before the waste card gets its subtle sheen. */
private const val SOLITAIRE_WASTE_IDLE_DELAY_MS = 15_000L

/** How long each idle-sheen pass lasts (and its specularSweep period) before it repeats, as long as the player stays idle. */
private const val SOLITAIRE_WASTE_IDLE_SHEEN_MS = 2600
private val SOLITAIRE_WASTE_IDLE_TINT = Color.White.copy(alpha = 0.14f)
