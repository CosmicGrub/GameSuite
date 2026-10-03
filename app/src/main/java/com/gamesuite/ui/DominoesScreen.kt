package com.gamesuite.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.card3DToss
import com.gamesuite.games.dominoes.Domino
import com.gamesuite.games.dominoes.DominoGame
import com.gamesuite.games.dominoes.DominoPlayerState
import com.gamesuite.games.dominoes.DominoState
import com.gamesuite.games.dominoes.PlacedDomino
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Research pass (README item 9h) added a real CPU difficulty ladder — see
 * DominoGame's `chooseBotPlay`/`chooseOpeningPlay` KDoc — read here from
 * Settings' "Default CPU difficulty" the same way the other per-game
 * upgrade passes (9a, 9f, 9g) already do.
 *
 * Animation/physics pitch pass added: pip-dot tile faces (see [PipFace]),
 * a full drag-to-place gesture with a legality-accurate ghost preview (see
 * the drag state block and [DragGhostOverlay] below), and a card3DToss
 * fly-to-chain animation for a tile that actually lands (see [FlyingDomino]
 * / [FlyingDominoOverlay]). The drag gesture and its ghost's position/
 * orientation always read straight from [DominoGame.canPlace] /
 * [DominoGame.wouldFlip] — the same rule playDomino() itself uses — so the
 * preview can never show a placement as legal/illegal or facing the wrong
 * way. Only the FLOURISH on top of that (spring snap-back, shake, the red
 * tint pulse, and the fly-to-chain toss itself) is gated behind `enhanced`;
 * with it off, an illegal drop resets instantly and a legal one lands
 * without a flight, matching today's flat behavior.
 *
 * Premium 2026 vision pass (Dominoes section) added: a real haptic vocabulary via
 * [rememberHaptics] (replacing every generic `LocalHapticFeedback` buzz, and filling
 * in the gaps where drawFromBoneyard()/pass()/an illegal drop fired nothing at all),
 * a matte slate-table identity behind the chain (see [dominoSlateTable]), layered
 * tile-clatter audio built from [CardSounds]' existing clips (a clack on a placement,
 * a heavier one for a double, a rattle for the initial deal, a scrape for a boneyard
 * draw), a real flight for [DominoGame.drawFromBoneyard] reusing the same
 * [FlyingDomino] machinery a placed tile already used, a staggered deal-in reveal,
 * hit-stop on a legal drop landing, and a one-time win-only toppling cascade of the
 * final chain (see the `handOverPhase` state machine below) -- a domino-flavored
 * analog to UNO's confetti, deliberately NOT a per-move flourish (there's no
 * mechanical trigger for a mid-game topple in real Draw Dominoes).
 *
 * UI-QUALITY PASS (shared GameChrome / fitBoard kit, see ReversiScreen):
 *  - CHROME: the whole screen, result panel included, sits in [GameChrome] (corner menu, back
 *    handling, How to Play). The first row of each pane reserves [GameChromeEndInset] so the
 *    corner button never covers a seat chip or the hand title. Leaving mid-hand is an abort
 *    (`abortMatch`, never recorded) unless an earlier hand of this session already finished; then
 *    it goes through `leaveSession()` (the same call the result panel's "Back to Menu" makes) so
 *    those hands' points still count, and the confirm dialog says so. The count of finished hands
 *    is this screen's own tally of hand-over transitions (the engine only keeps points).
 *  - CHAIN: a [LazyRow] with a real list state. After every placement it scrolls to the end that
 *    was just played (instantly under reduced motion) so the new tile is never off-screen. The
 *    drag/ghost/flight targets are derived from that list state's layout info plus the strip's
 *    own live coordinates, not from marker items inside the list: those left composition once
 *    scrolled away and kept stale positions. A drop left of the strip's centre attaches left.
 *  - SIZING: the chain tile size comes from [fitBoard] against the chain slot's measured width
 *    (about 8 tiles in view, 44-72dp long side; below 44dp it keeps 44 and scrolls). The hand is a
 *    wrapping [FlowRow] sized the same way, with every tile in a 48dp-wide touch box, so a seven
 *    tile hand never needs a hidden scroll to find a tile and a dragged tile is not clipped by a
 *    scroll container.
 *  - ACTIONS: Play on Left / Play on Right show only while a tile is selected, Draw only when the
 *    local player is stuck with tiles left in the boneyard, Pass only when stuck with it empty,
 *    all in a wrapping row of 48dp buttons. A polite live region carries the turn prompt.
 *  - HOTSEAT: with two or more human seats the hand is hidden behind a "pass the device" curtain
 *    until the next player taps it. (The menu only launches vs-CPU today; the engine supports it.)
 *  - RESULT: [DominoHandResultPanel] renders when a hand ends (a win or a blocked hand), with
 *    correct grammar for "You", the points scored, pips left per seat and the session tally.
 *    The engine always names a winner (a blocked hand with level lowest totals goes to the first
 *    seat, and the panel and help text say so); the panel's "tie" wording is defensive, for a
 *    null winner. Haptic CELEBRATION, the success chime and the toppling chain fire for a HUMAN
 *    win only.
 *  - ACCESSIBILITY: each hand tile is a button ("Domino 3 and 5, tile 2 of 7, playable") and
 *    each chain tile has a description with its position; the ghost carries a check or cross
 *    glyph besides its colour, and with [LocalColorblindMode] on its legal/illegal colours become
 *    a blue/orange pair. A selected tile also lifts, so it is not marked by colour alone.
 *  - MOTION: the selected-tile lift (120ms) and the result panel fade (250ms), both off under
 *    reduced motion. [FlyingDominoOverlay] reads the flight progress itself, so a flight
 *    recomposes only the overlay, not the whole screen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DominoesScreen(
    sessionManager: GameSessionManager,
    game: DominoGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val density = LocalDensity.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    // Ambient music (premium 2026 vision pitch) -- ANDs the dedicated ambient-music
    // setting with the master sound toggle, same composition rule every other
    // LocalHapticsEnabled-style gate in this file already follows (see
    // LocalMusicEnabled.kt's own KDoc for why both must hold).
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.DOMINOES, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    // Settings -> Accessibility -> Reduced Motion always wins over both
    // LocalCard3DMode and LocalEnhancedAnimations below (see either
    // CompositionLocal's own KDoc).
    val reducedMotion = LocalReducedMotion.current
    // Settings -> Display -> "3D perspective mode" -- see ui/TablePerspective.kt's KDoc.
    val card3D = LocalCard3DMode.current && !reducedMotion
    // Settings -> Display -> "Enhanced move animations" -- motion QUALITY (lift/
    // toss/slide flourish), distinct from card3D's perspective/depth concern; the
    // two compose independently. See settings/LocalEnhancedAnimations.kt's KDoc.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val colorblind = LocalColorblindMode.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
    }

    LaunchedEffect(state?.currentPlayerIndex, state?.handOver) {
        val s = state ?: return@LaunchedEffect
        if (s.handOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(800)
            game.playBotTurn()
        }
    }

    val s = state ?: return
    val sessionScores by game.sessionScores

    val winnerPlayer = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
    // Celebration (haptic, chime, toppling chain) is for a HUMAN winning the hand only: a CPU win
    // or a tie gets a plain acknowledgement. In pass-and-play either human winning counts.
    val humanWon = s.handOver && winnerPlayer != null && !winnerPlayer.isBot
    // Hands this session that reached their own end -- see the KDoc's CHROME note for why the
    // screen tallies this itself. Decides whether leaving mid-hand aborts or scores the session.
    var finishedHands by remember { mutableStateOf(0) }

    // Win-only toppling celebration (premium 2026 vision pitch): a ONE-TIME cascade of
    // the final chain, computed synchronously the instant a hand-over with a human winner
    // is first seen (remember's key is s.handOver itself, so this recomputes fresh every
    // time a NEW hand-over begins) -- avoids a one-frame flash of the summary panel
    // before the celebration would otherwise kick in via an effect. Skipped for a CPU win, a
    // genuine tie (nothing to celebrate), an empty chain, or with enhanced motion off/reduced
    // motion on.
    var handOverPhase by remember(s.handOver) {
        mutableStateOf(humanWon && enhanced && s.chain.isNotEmpty())
    }
    val toppleAnims = remember(s.handOver) { List(s.chain.size) { Animatable(0f) } }
    LaunchedEffect(s.handOver) {
        if (!s.handOver) return@LaunchedEffect
        finishedHands += 1
        if (humanWon) {
            haptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)
        } else {
            haptics(HapticSignal.NORMAL_ACTION)
        }
        // The final tile's own clack -- a placement sound, not a celebration, so every hand-over
        // gets it (the per-move effect below skips the hand-over transition).
        sounds.playPlace()
        if (handOverPhase) {
            coroutineScope {
                s.chain.indices.forEach { i ->
                    launch {
                        delay(i * TOPPLE_STAGGER_MS)
                        toppleAnims.getOrNull(i)?.animateTo(TOPPLE_ANGLE_DEG, animationSpec = tween(TOPPLE_DURATION_MS))
                        // One soft clack per toppled tile -- same layering technique as
                        // every other sound in this pass, see CardSounds.kt's own KDoc.
                        sounds.playTap()
                    }
                }
            }
            delay(HOLD_AFTER_TOPPLE_MS)
            handOverPhase = false
        }
    }

    val showResult = s.handOver && !handOverPhase

    // Address controls at whichever player's turn it currently is, not a fixed
    // "first human" guess — this is what lets a second local human act in
    // SINGLE_DEVICE_PASS_AND_PLAY instead of only ever seeing disabled buttons.
    val activePlayerIndex = s.currentPlayerIndex
    val activePlayer = s.players.getOrNull(activePlayerIndex)
    val isMyTurn = activePlayer?.isBot == false
    val vsBot = s.players.any { it.isBot }
    val isHotseat = s.players.count { !it.isBot } > 1
    // Hotseat hand-off: the next human's hand stays behind a curtain until they tap it. Keyed on
    // s.handOver so every new hand starts with the curtain up again.
    var revealedSeat by remember(s.handOver) { mutableStateOf<Int?>(null) }
    val handHidden = isHotseat && isMyTurn && !s.handOver && revealedSeat != activePlayerIndex
    // Whether this device's player may act right now (play, draw, pass, drag).
    val canAct = isMyTurn && !handHidden && !s.handOver
    val canPlayNow = isMyTurn && !s.handOver && game.canPlay(activePlayerIndex)
    var selectedDomino by remember(s.handOver) { mutableStateOf<Domino?>(null) }

    // The hand row follows whoever's turn it is (see activePlayerIndex above) so a second local
    // human sees their own hand during hotseat play. That's wrong when the active seat is a bot:
    // there is no "pass the device" moment for a CPU turn, so it was showing the CPU's hand
    // face-up, labeled "Your hand". Fall back to the local viewer's own hand whenever the active
    // seat isn't human.
    val shownHand: List<Domino> = activePlayer?.takeIf { !it.isBot }?.hand
        ?: context?.let { ctx -> s.players.getOrNull(ctx.localPlayerIndex)?.hand }
        ?: emptyList()

    val dragScope = rememberCoroutineScope()

    // The chain strip: a real list state so the played end can be scrolled into view, and the
    // strip's own LayoutCoordinates (held, not copied, so positionInRoot() is always read live,
    // even after the pane has scrolled). The drag side decision, the ghost and the fly-to-chain
    // target are all derived from these -- see chainEndAnchor() below. chainScale is the chain
    // tile's size relative to its 44x32dp base, published by the chain slot once measured (the
    // ghost and the flight, which live outside that slot, draw their tile at the same size).
    val chainListState = rememberLazyListState()
    val chainHolder = remember { DominoCoordsHolder() }
    val chainMemo = remember { DominoChainMemo() }
    var chainScale by remember { mutableStateOf(1f) }

    // Root-space anchors for the drawFromBoneyard() flight (premium 2026 vision pitch) --
    // boneyardAnchorPos is the "Boneyard: N" label's own center, handAnchorPos is a point just
    // inside the start of the current hand's row.
    var boneyardAnchorPos by remember { mutableStateOf(Offset.Zero) }
    var handAnchorPos by remember { mutableStateOf(Offset.Zero) }
    // Which of the two placement paths produced the chain-size increase the per-move
    // reactive effect below is about to react to -- true only for a drag-drop (so its
    // sound/haptic/hit-stop can wait for that drop's own flight to land); every other
    // placement path (button, tap, bot) resets this to false right before playing.
    var lastPlacementWasDrag by remember { mutableStateOf(false) }
    // Staggered deal-in reveal (premium 2026 vision pitch): hand tiles at index >=
    // dealtCount stay hidden until the deal-in sequence below reveals them one at a
    // time. Int.MAX_VALUE (the resting value once a deal finishes, or before the very
    // first one starts) means "show everything normally" -- a tile drawn later mid-hand
    // is never gated by this.
    var dealtCount by remember { mutableStateOf(Int.MAX_VALUE) }

    // Drag-to-place state -- shared/top-level rather than per-hand-tile since
    // only one domino can ever be mid-gesture at a time (mirrors how UnoScreen
    // shares a single flyProgress for its fly-to-discard-pile animation).
    var draggedDomino by remember { mutableStateOf<Domino?>(null) }
    var isActivelyDragging by remember { mutableStateOf(false) }
    // Synchronous 1:1 finger-follow offset while actively dragging -- an
    // Animatable would lag a frame behind the raw pointer, which is fine for
    // the spring-back below but wrong while the finger is still moving.
    var dragOffsetState by remember { mutableStateOf(Offset.Zero) }
    val returnOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val shakeX = remember { Animatable(0f) }
    val shakePx = with(density) { 14.dp.toPx() }
    var showIllegalTint by remember { mutableStateOf(false) }
    // Held as State (not `by`) and read inside drawBehind, so the 80/260ms tint animation
    // invalidates only that tile's draw pass instead of recomposing it every frame.
    val illegalTint = animateColorAsState(
        targetValue = if (showIllegalTint) Color(0xFFD32F2F).copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(if (showIllegalTint) 80 else 260),
        label = "dominoIllegalTint"
    )
    var liveAttachToLeft by remember { mutableStateOf(true) }
    // Tracks the illegal-drop return animation's job so a fresh pick-up right
    // on the heels of a snap-back cancels the old one instead of racing it on
    // the shared returnOffset/shakeX/showIllegalTint state below.
    var returnJob by remember { mutableStateOf<Job?>(null) }

    // Fly-to-chain animation for a tile that was actually (legally) placed, OR a tile
    // that just arrived via drawFromBoneyard() -- see FlyingDomino's KDoc. Only one is
    // ever in flight at a time (a single device only ever has one domino mid-drop/draw).
    var flyingDomino by remember { mutableStateOf<FlyingDomino?>(null) }
    val flyProgress = remember { Animatable(0f) }
    LaunchedEffect(flyingDomino) {
        val fly = flyingDomino ?: return@LaunchedEffect
        flyProgress.snapTo(0f)
        flyProgress.animateTo(1f, animationSpec = tween(FLIGHT_DURATION_MS.toInt()))
        flyingDomino = null
    }

    /** Root-space top-left of the chain strip right now (zero if it is not laid out yet). */
    fun chainRootOffset(): Offset = chainHolder.coords?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero

    /** Measured width of the chain strip in px (zero if it is not laid out yet). */
    fun chainViewportWidthPx(): Float = chainHolder.coords?.takeIf { it.isAttached }?.size?.width?.toFloat() ?: 0f

    /** The strip's horizontal centre: a drop left of it attaches left, right of it attaches right. */
    fun chainMidX(): Float = chainRootOffset().x + chainViewportWidthPx() / 2f

    /**
     * Where a tile placed on the [attachToLeft] end lands, in root space (the new tile's top-left).
     * When that end's tile is in the visible list items it is the real edge of the chain; when it
     * has scrolled out of view it is the strip's own edge on that side, which is where the
     * auto-scroll below brings the new tile. An empty chain lands in the middle of the strip.
     * The result is always clamped inside the strip: a chain scrolled flush to its end has its
     * edge tile touching the viewport edge, and the unclamped edge would put the ghost and the
     * flight's landing spot outside the table (even off-screen).
     * A list item's `offset` is relative to the start of the list (the list has no content
     * padding, so that is the strip's own left edge, the same origin [chainRootOffset] returns).
     */
    fun chainEndAnchor(attachToLeft: Boolean): Offset {
        val root = chainRootOffset()
        val info = chainListState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return Offset(root.x + chainViewportWidthPx() / 2f, root.y)
        val edgeIndex = if (attachToLeft) 0 else total - 1
        val edge = info.visibleItemsInfo.firstOrNull { it.index == edgeIndex }
        val tilePx = with(density) { (DOM_CHAIN_LONG_DP * chainScale + 4f).dp.toPx() }
        val stripEndX = root.x + chainViewportWidthPx() - tilePx
        val x = when {
            edge != null && attachToLeft -> (root.x + edge.offset.toFloat()).coerceAtLeast(root.x)
            edge != null -> (root.x + (edge.offset + edge.size).toFloat()).coerceAtMost(stripEndX)
            attachToLeft -> root.x
            else -> stripEndX
        }
        return Offset(x, root.y)
    }

    // Per-move reaction: haptics + layered sound + (for a draw) the flight above --
    // reacts to the real board state itself, not to the click site, so a bot's own
    // draw/play/pass feels and sounds identical to a human one instead of being silent.
    var prevDominoState by remember { mutableStateOf<DominoState?>(null) }
    LaunchedEffect(s) {
        val prev = prevDominoState
        prevDominoState = s

        val isDealIn = s.lastAction == "Game started" && (prev == null || prev.lastAction != "Game started")
        if (isDealIn) {
            // The currently-silent, zero-animation initial deal (DominoGame.startMatch())
            // -- a staggered reveal of the shown hand plus a soft rattle-feeling sequence,
            // reusing the same scaleIn appear-transition the chain's own tiles already use.
            dealtCount = 0
            haptics(HapticSignal.LIGHT_TICK)
            val myHandSize = shownHand.size
            repeat(myHandSize) { i ->
                delay(DEAL_STAGGER_MS)
                dealtCount = i + 1
                sounds.playDraw()
            }
            return@LaunchedEffect
        }
        dealtCount = Int.MAX_VALUE // any hand already mid-play always shows fully revealed

        if (prev == null) return@LaunchedEffect // shouldn't normally happen once isDealIn is handled above, but stay defensive
        if (s.handOver && !prev.handOver) {
            // The hand-over effect above owns that transition. Drop a drag flag the winning
            // move may have left set, or the next hand's first placement would wait on a
            // flight that never happened (this state now outlives a hand).
            lastPlacementWasDrag = false
            return@LaunchedEffect
        }

        when {
            s.chain.size > prev.chain.size -> {
                val wasDrag = lastPlacementWasDrag
                lastPlacementWasDrag = false
                // Sync the clatter/hit-stop with the drop's own flight landing rather than
                // firing the instant state changes -- a button/tap/bot placement has no
                // flight to wait for, so only a drag-sourced one delays.
                if (enhanced && wasDrag) delay(FLIGHT_DURATION_MS)
                if (enhanced) delay(HIT_STOP_MS) // a 1-2 frame freeze-beat right as it lands
                haptics(HapticSignal.STRONG_ACTION)
                val added = when {
                    prev.chain.isEmpty() -> s.chain.firstOrNull()
                    s.chain.firstOrNull() != prev.chain.firstOrNull() -> s.chain.firstOrNull()
                    else -> s.chain.lastOrNull()
                }
                if (added?.domino?.isDouble == true) {
                    // A heavier double-clack for a landed double.
                    sounds.playTap(); delay(50); sounds.playPlace(); delay(60); sounds.playTap()
                } else {
                    sounds.playTap(); delay(60); sounds.playPlace()
                }
            }
            s.boneyardSize < prev.boneyardSize -> {
                haptics(HapticSignal.LIGHT_TICK)
                // A distinct scrape-feeling call, longer/overlapping rather than a single tap.
                sounds.playDraw(); delay(90); sounds.playDraw()
                if (enhanced) {
                    val playerIdx = s.currentPlayerIndex // unchanged by a draw
                    val oldHand = prev.players.getOrNull(playerIdx)?.hand ?: emptyList()
                    val newHand = s.players.getOrNull(playerIdx)?.hand ?: emptyList()
                    val drawn = newHand.firstOrNull { nt -> oldHand.none { it.instanceId == nt.instanceId } }
                    if (drawn != null) {
                        // Reuses the exact same flight machinery a placed tile already used
                        // (see FlyingDomino/FlyingDominoOverlay below) -- the currently-instant
                        // drawFromBoneyard() now gets a real short flight instead.
                        flyingDomino = FlyingDomino(drawn.a, drawn.b, boneyardAnchorPos, handAnchorPos, chainScale)
                    }
                }
            }
            s.consecutivePasses > prev.consecutivePasses -> {
                // A pass previously fired haptics only -- no sound at all, human or bot.
                haptics(HapticSignal.NORMAL_ACTION)
                playSfx(SfxKind.LIGHT_TICK)
            }
        }
    }

    // Keep the end that was just played in view. Keyed on the chain's two end tiles: a changed
    // first tile means the move went on the left, anything else on the right (a first placement
    // has no previous first tile and lands on index 0 either way). Instant under reduced motion.
    val chainFirstId = s.chain.firstOrNull()?.domino?.instanceId
    val chainLastId = s.chain.lastOrNull()?.domino?.instanceId
    LaunchedEffect(chainFirstId, chainLastId) {
        val previousFirst = chainMemo.firstId
        chainMemo.firstId = chainFirstId
        if (chainFirstId == null || chainLastId == null) return@LaunchedEffect
        val target = if (previousFirst != null && chainFirstId != previousFirst) 0 else s.chain.lastIndex
        if (reducedMotion) chainListState.scrollToItem(target) else chainListState.animateScrollToItem(target)
    }

    // The turn prompt, read out by a polite live region so a screen reader announces the CPU's
    // reply and what to do next without being asked.
    val activeName = activePlayer?.displayName ?: "Player"
    val yourTurnLabel = if (vsBot) "Your turn" else "$activeName's turn"
    val promptText = when {
        s.handOver -> dominoHandResultTitle(s)
        activePlayer == null -> ""
        activePlayer.isBot -> "${activePlayer.displayName} is thinking…"
        handHidden -> "Pass the device to ${activePlayer.displayName}"
        !canPlayNow && s.boneyardSize > 0 -> "Nothing to play — draw a tile"
        !canPlayNow -> "Nothing to play — pass"
        s.chain.isEmpty() -> "$yourTurnLabel — play any tile to start"
        else -> "$yourTurnLabel — drag a tile to an end, or tap one to select it"
    }
    val handTitle = if (isHotseat) "$activeName's hand (${shownHand.size})" else "Your hand (${shownHand.size})"

    GameChrome(
        helpTitle = "How to Play Dominoes",
        helpText = "Each player is dealt 7 tiles (5 each with 3 or 4 players) and the rest form the " +
            "boneyard. The holder of the highest double starts, or of the heaviest tile if nobody " +
            "has a double, and may open with any tile.\n\n" +
            "On your turn, add a tile to either end of the chain so that one of its halves matches " +
            "the pips showing at that end: touch and hold a tile and drag it to an end, or tap it " +
            "and choose Play on Left or Play on Right. If nothing fits, draw from the boneyard one " +
            "tile at a time until something does; once the boneyard is empty you pass.\n\n" +
            "A hand ends when someone plays their last tile, or when everyone passes in a row. " +
            "Then the lowest pip total wins, and level totals go to the first seat at the table. " +
            "The winner scores every pip left in the other hands, and Play Again keeps the " +
            "running score.",
        matchInProgress = !s.handOver,
        onLeave = game::leaveSession,
        // Leaving mid-hand discards ONLY the unfinished hand: if earlier hands in this session
        // already finished, leaving goes through leaveSession() (the same call the result
        // panel's "Back to Menu" makes) so their points still count; with nothing finished it is
        // a pure abort, never a win or a loss. A finished hand always leaves via leaveSession().
        onAbort = { if (finishedHands > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = MaterialTheme.colorScheme.background,
        buttonContent = MaterialTheme.colorScheme.onBackground,
        leaveTitle = "Leave this hand?",
        leaveBody = if (finishedHands > 0) {
            "This hand is still in progress and won't count, but the hands you've already finished stay on your record."
        } else {
            "This game is still in progress. Leaving now won't count it as a win or a loss."
        }
    ) {
        if (showResult) {
            DominoHandResultPanel(
                s = s,
                sessionScores = sessionScores,
                reducedMotion = reducedMotion,
                onPlayAgain = game::playAgain,
                onBackToMenu = game::leaveSession
            )
        } else {
            AdaptiveTwoPane(
                foldState = LocalFoldState.current,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                primary = {
                    // CenterHorizontally at the Column level (matching UnoScreen's per-section
                    // treatment) so every row here — not just the opponents summary — centers
                    // within the capped TABLET-mode column instead of hugging its start edge.
                    // Rows that already declare their own fillMaxWidth() (the seat row, the
                    // chain strip) are unaffected, since a full-width child ignores the
                    // parent's alignment.
                    //
                    // Fold/rotation fix: verticalScroll, not a bare fillMaxWidth() Column -- this
                    // pane's own bounded height (from AdaptiveTwoPane/FoldAwareTwoPane's weight(1f)
                    // box, see FoldAwareLayout.kt's KDoc) is real, but in the tightest real vertical
                    // budget in the app -- the Fold 5 cover screen rotated to landscape, ~344dp tall
                    // total, split further by the hand row below -- the stacked score/status rows +
                    // chain table + action buttons can come within a hair of that budget (or exceed
                    // it once system font scaling is in play). A plain Column would silently clip the
                    // bottom (likely the action buttons); verticalScroll costs nothing when
                    // everything already fits (the overwhelmingly common case) and guarantees every
                    // control stays reachable when it doesn't.
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            // End padding reserves room for the corner menu button, which sits at
                            // the screen's top-end and would otherwise cover the last seat chip.
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = GameChromeEndInset)
                                .horizontalScroll(rememberScrollState()),
                            // Centers the group when it fits (typical case), still spaces-then-scrolls
                            // once it overflows — see the identical fix/comment in UnoScreen.kt.
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            s.players.forEachIndexed { i, p ->
                                DominoSeatChip(player = p, isTurn = i == s.currentPlayerIndex && !s.handOver)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                promptText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            if (!s.handOver) {
                                Text(
                                    s.lastAction,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Text(
                            "Boneyard: ${s.boneyardSize}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.onGloballyPositioned { boneyardAnchorPos = it.tileCenter() }
                        )
                        if (vsBot) {
                            Text(
                                "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("Chain", style = MaterialTheme.typography.titleSmall)

                        // The chain slot. Tile size comes from THIS slot's measured width, never an
                        // outer scope: fitBoard keeps about eight tiles in view and never returns a
                        // size the slot cannot hold; when the slot is too narrow for the 44dp
                        // minimum the tiles keep 44dp and the strip scrolls instead of shrinking.
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val chainTileLong = dominoChainTileLongDp(maxWidth.value)
                            val chainTileScale = chainTileLong / DOM_CHAIN_LONG_DP
                            SideEffect { chainScale = chainTileScale }
                            // Matte slate-table identity (premium 2026 vision pitch) -- distinct from
                            // Mancala's wood and UNO's felt, see dominoSlateTable()'s own KDoc.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .dominoSlateTable()
                                    .specularSweep(enabled = card3D, tint = Color.White.copy(alpha = 0.08f), periodMs = 5200)
                            ) {
                                LazyRow(
                                    state = chainListState,
                                    // Centers a short chain in the strip (so a drop's left/right
                                    // halves are symmetric about the chain), scrolls a long one.
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        // An empty LazyRow measures 0dp tall; a fixed strip height keeps
                                        // the table the same size before and after the first tile.
                                        .height((DOM_CHAIN_SHORT_DP * chainTileScale + 4f).dp)
                                        .onGloballyPositioned { chainHolder.coords = it }
                                        .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
                                ) {
                                    itemsIndexed(s.chain, key = { _, item -> item.domino.instanceId }) { index, placed ->
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = true,
                                            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .graphicsLayer {
                                                        // Win-only toppling celebration -- each tile in the final chain
                                                        // rotates onto its edge in sequence (see toppleAnims above); 0f
                                                        // at every other time, a pure no-op transform.
                                                        rotationZ = toppleAnims.getOrNull(index)?.value ?: 0f
                                                        transformOrigin = TransformOrigin(0f, 1f)
                                                    }
                                                    .semantics { contentDescription = dominoChainTileDescription(index, s.chain.size, placed) }
                                            ) {
                                                DominoTileView(
                                                    top = placed.leftValue,
                                                    bottom = placed.rightValue,
                                                    horizontal = true,
                                                    scale = chainTileScale
                                                )
                                            }
                                        }
                                    }
                                }
                                if (s.chain.isEmpty()) {
                                    Text(
                                        if (isMyTurn) "Play any tile to start the chain" else "Waiting for the first tile",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp)
                                    )
                                }
                            }
                        }

                        if (s.chain.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Sighted players read the exposed pip value off the end tile's own pips;
                                // a screen-reader user has no such visual, so each label spells out which
                                // end it is and the value a domino must match to play there.
                                Text(
                                    "Left end: ${s.leftEnd}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.semantics { contentDescription = "Left end, exposed pip value ${s.leftEnd}" }
                                )
                                Text(
                                    "Right end: ${s.rightEnd}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.semantics { contentDescription = "Right end, exposed pip value ${s.rightEnd}" }
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Actions wrap instead of overflowing a narrow window, and only the ones that
                        // apply right now exist: the two Play buttons while a tile is selected, Draw
                        // when stuck with tiles left in the boneyard, Pass when stuck with it empty.
                        // heightIn keeps the row from collapsing (and the layout from jumping) when
                        // there is nothing to offer.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val selected = selectedDomino
                            if (selected != null && s.chain.isNotEmpty()) {
                                // Each end has its own pip value, so a domino that legally matches the
                                // left end may not match the right end (or vice versa) -- same check
                                // playDomino() itself uses internally, just narrowed to one specific
                                // end per button instead of "matches either end" (see `playable` below,
                                // which is the "either end" version used for the hand tiles). Without
                                // this, an illegal button stayed enabled, silently no-opped inside
                                // DominoGame, and still fired the success sound/haptic/deselect.
                                val matchesLeft = selected.a == s.leftEnd || selected.b == s.leftEnd
                                val matchesRight = selected.a == s.rightEnd || selected.b == s.rightEnd
                                Button(
                                    enabled = canAct && matchesLeft,
                                    onClick = {
                                        if (matchesLeft) {
                                            lastPlacementWasDrag = false
                                            game.playDomino(activePlayerIndex, selected, attachToLeft = true)
                                            selectedDomino = null
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                                ) { Text("Play on Left") }
                                Button(
                                    enabled = canAct && matchesRight,
                                    onClick = {
                                        if (matchesRight) {
                                            lastPlacementWasDrag = false
                                            game.playDomino(activePlayerIndex, selected, attachToLeft = false)
                                            selectedDomino = null
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                                ) { Text("Play on Right") }
                            }
                            if (canAct && !canPlayNow && s.boneyardSize > 0) {
                                Button(
                                    onClick = { game.drawFromBoneyard(activePlayerIndex) },
                                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                                ) { Text("Draw a tile") }
                            }
                            if (canAct && !canPlayNow && s.boneyardSize == 0) {
                                Button(
                                    onClick = { game.pass(activePlayerIndex) },
                                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                                ) { Text("Pass") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                },
                secondary = {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        // The pane's top-right can sit under the corner menu button (a tablet's side
                        // pane, the right half of a book posture), so the title block reserves its inset.
                        Column(modifier = Modifier.padding(end = GameChromeEndInset)) {
                            Text(handTitle, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Dimmed tiles don't match either end of the chain.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                            )
                        }

                        if (handHidden) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "Pass the device to $activeName",
                                    style = MaterialTheme.typography.titleSmall,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { revealedSeat = activePlayerIndex },
                                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                                ) { Text("Show my hand") }
                            }
                        } else {
                            // The hand slot. Each tile sits in a touch box at least 48dp wide, sized
                            // by fitBoard from THIS slot's measured width (seven to a row where they
                            // fit); a narrower slot keeps 48dp and the row wraps rather than shrinks.
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val handCell = dominoHandCellDp(maxWidth.value)
                                val handTileScale = (handCell - DOM_HAND_GUTTER_DP) / DOM_HAND_W_DP
                                // Tile (60dp base, plus its 2dp margins) with room for the selection lift.
                                val handHitHeight = (DOM_HAND_H_DP * handTileScale + 4f + 12f).dp
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .onGloballyPositioned {
                                            handAnchorPos = it.positionInRoot() + Offset(with(density) { 24.dp.toPx() }, it.size.height / 2f)
                                        },
                                    horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    shownHand.forEachIndexed { handIndex, domino ->
                                        key(domino.instanceId) {
                                            androidx.compose.animation.AnimatedVisibility(
                                                visible = handIndex < dealtCount,
                                                // A tile being dragged draws above its neighbours.
                                                modifier = Modifier.zIndex(if (draggedDomino?.instanceId == domino.instanceId) 1f else 0f),
                                                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f)
                                            ) {
                                                val selected = selectedDomino?.instanceId == domino.instanceId
                                                val playable = s.chain.isEmpty() || domino.a == s.leftEnd || domino.b == s.leftEnd ||
                                                    domino.a == s.rightEnd || domino.b == s.rightEnd
                                                val isThisDragged = draggedDomino?.instanceId == domino.instanceId
                                                var tileRootPos by remember(domino.instanceId) { mutableStateOf(Offset.Zero) }
                                                // A selected tile lifts so selection is not carried by colour alone.
                                                val lift by animateDpAsState(
                                                    targetValue = if (selected) (-6).dp else 0.dp,
                                                    animationSpec = if (reducedMotion) snap<Dp>() else tween<Dp>(DOM_LIFT_MS),
                                                    label = "dominoSelectLift"
                                                )
                                                // Hand tiles are otherwise just two bare numbers separated by a divider
                                                // bar -- nothing a screen reader can read as "domino" or "playable" on
                                                // its own, so spell out both pip values, the position and legality.
                                                val tileDescription = "Domino ${domino.a} and ${domino.b}, " +
                                                    "tile ${handIndex + 1} of ${shownHand.size}, " +
                                                    when {
                                                        !playable -> "not playable"
                                                        selected -> "playable, selected"
                                                        else -> "playable"
                                                    }

                                                Box(
                                                    modifier = Modifier
                                                        .size(width = handCell.dp, height = handHitHeight)
                                                        .graphicsLayer {
                                                            if (isThisDragged) {
                                                                translationX = if (isActivelyDragging) dragOffsetState.x else returnOffset.value.x + shakeX.value
                                                                translationY = if (isActivelyDragging) dragOffsetState.y else returnOffset.value.y
                                                            }
                                                        }
                                                        .onGloballyPositioned { tileRootPos = it.positionInRoot() }
                                                        .pointerInput(canAct, playable, domino.instanceId) {
                                                            if (!canAct || !playable) return@pointerInput
                                                            detectDragGesturesAfterLongPress(
                                                                onDragStart = {
                                                                    returnJob?.cancel()
                                                                    showIllegalTint = false
                                                                    draggedDomino = domino
                                                                    isActivelyDragging = true
                                                                    dragOffsetState = Offset.Zero
                                                                    liveAttachToLeft = tileRootPos.x < chainMidX()
                                                                    haptics(HapticSignal.LIGHT_TICK)
                                                                },
                                                                onDrag = { change, amount ->
                                                                    change.consume()
                                                                    dragOffsetState += amount
                                                                    val fingerRefX = tileRootPos.x + dragOffsetState.x
                                                                    liveAttachToLeft = fingerRefX < chainMidX()
                                                                },
                                                                onDragEnd = {
                                                                    isActivelyDragging = false
                                                                    val attachToLeft = liveAttachToLeft
                                                                    if (game.canPlace(domino, attachToLeft)) {
                                                                        val flipped = game.wouldFlip(domino, attachToLeft)
                                                                        val placed = PlacedDomino(domino, flipped)
                                                                        val startPos = Offset(tileRootPos.x + dragOffsetState.x, tileRootPos.y + dragOffsetState.y)
                                                                        val endPos = chainEndAnchor(attachToLeft)
                                                                        draggedDomino = null
                                                                        dragOffsetState = Offset.Zero
                                                                        lastPlacementWasDrag = true
                                                                        game.playDomino(activePlayerIndex, domino, attachToLeft)
                                                                        selectedDomino = null
                                                                        // The toss itself is the new stylistic flourish -- gated
                                                                        // behind `enhanced` per the pitch; with it off the tile
                                                                        // just lands instantly, matching today's flat behavior.
                                                                        if (enhanced) {
                                                                            flyingDomino = FlyingDomino(placed.leftValue, placed.rightValue, startPos, endPos, chainScale)
                                                                        }
                                                                    } else {
                                                                        // "An illegal drop" is called out by name in HapticSignal.FAILURE's
                                                                        // own KDoc -- fired regardless of `enhanced` since it's tactile
                                                                        // rejection feedback, not a motion preference. Previously this was
                                                                        // haptics-only -- the shake/red-tint/spring-back below had no
                                                                        // matching sound at all, a real and genuinely silent gap.
                                                                        haptics(HapticSignal.FAILURE)
                                                                        playSfx(SfxKind.INVALID_BUZZ)
                                                                        val droppedOffset = dragOffsetState
                                                                        dragOffsetState = Offset.Zero
                                                                        if (enhanced) {
                                                                            returnJob = dragScope.launch {
                                                                                returnOffset.snapTo(droppedOffset)
                                                                                coroutineScope {
                                                                                    launch {
                                                                                        returnOffset.animateTo(
                                                                                            Offset.Zero,
                                                                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                                                                        )
                                                                                    }
                                                                                    launch {
                                                                                        shakeX.snapTo(0f)
                                                                                        shakeX.animateTo(-shakePx, tween(40))
                                                                                        shakeX.animateTo(shakePx, tween(80))
                                                                                        shakeX.animateTo(-shakePx * 0.64f, tween(80))
                                                                                        shakeX.animateTo(0f, tween(60))
                                                                                    }
                                                                                    launch {
                                                                                        showIllegalTint = true
                                                                                        delay(220)
                                                                                        showIllegalTint = false
                                                                                    }
                                                                                }
                                                                                draggedDomino = null
                                                                            }
                                                                        } else {
                                                                            draggedDomino = null
                                                                        }
                                                                    }
                                                                },
                                                                onDragCancel = {
                                                                    isActivelyDragging = false
                                                                    draggedDomino = null
                                                                    dragOffsetState = Offset.Zero
                                                                }
                                                            )
                                                        }
                                                        .clickable(
                                                            enabled = canAct && playable,
                                                            onClickLabel = when {
                                                                s.chain.isEmpty() -> "Play as the opening tile"
                                                                selected -> "Deselect tile"
                                                                else -> "Select tile"
                                                            },
                                                            role = Role.Button
                                                        ) {
                                                            selectedDomino = if (selected) null else domino
                                                            if (s.chain.isEmpty()) {
                                                                lastPlacementWasDrag = false
                                                                game.playDomino(activePlayerIndex, domino, attachToLeft = true)
                                                                selectedDomino = null
                                                            }
                                                        }
                                                        .pointerHoverIcon(PointerIcon.Hand)
                                                        .semantics { contentDescription = tileDescription },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .offset(y = lift)
                                                            .then(
                                                                // Only a selected tile gets the ring: border(0.dp) is a
                                                                // one-pixel hairline in Compose, not "no border".
                                                                if (selected) {
                                                                    Modifier.border(
                                                                        2.dp,
                                                                        if (colorblind) DOM_OK_COLORBLIND else DOM_OK_COLOR,
                                                                        RoundedCornerShape(6.dp)
                                                                    )
                                                                } else {
                                                                    Modifier
                                                                }
                                                            )
                                                    ) {
                                                        DominoTileView(
                                                            top = domino.a,
                                                            bottom = domino.b,
                                                            horizontal = false,
                                                            dimmed = !playable,
                                                            scale = handTileScale
                                                        )
                                                        if (isThisDragged && showIllegalTint) {
                                                            Box(
                                                                Modifier
                                                                    .matchParentSize()
                                                                    .padding(2.dp)
                                                                    .clip(RoundedCornerShape(6.dp))
                                                                    .drawBehind { drawRect(illegalTint.value) }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }

        // Live drag ghost -- shows exactly where/how the held tile would land,
        // reading straight from DominoGame.canPlace()/wouldFlip() so it can never
        // drift from the real rule. Position/orientation are always accurate
        // (that's the "core legality logic" the pitch calls out); only the
        // translucency/tint styling is cosmetic, which is fine to always show
        // since it's just communicating that same legality result.
        // Only while the finger is actually down/moving -- once released, the
        // hand tile's own shake/tint/spring (illegal) or the fly overlay (legal)
        // takes over telling the story, so the ghost disappears immediately
        // rather than lingering at the target through that follow-up animation.
        if (isActivelyDragging) {
            draggedDomino?.let { domino ->
                val flipped = if (s.chain.isEmpty()) false else game.wouldFlip(domino, liveAttachToLeft)
                val legal = game.canPlace(domino, liveAttachToLeft)
                DragGhostOverlay(
                    top = if (flipped) domino.b else domino.a,
                    bottom = if (flipped) domino.a else domino.b,
                    position = chainEndAnchor(liveAttachToLeft),
                    legal = legal,
                    scale = chainScale,
                    colorblind = colorblind
                )
            }
        }

        flyingDomino?.let { fly ->
            FlyingDominoOverlay(fly = fly, progress = flyProgress.asState(), use3D = card3D)
        }
    }
}

/** This node's on-screen center in root coordinates -- see boneyardAnchorPos's KDoc above. */
private fun LayoutCoordinates.tileCenter(): Offset =
    positionInRoot() + Offset(size.width / 2f, size.height / 2f)

/**
 * Holds the chain strip's [LayoutCoordinates] without making them observable state: the object
 * itself never changes once attached, and every reader asks it for `positionInRoot()` at the
 * moment it needs the position, so a scrolled pane can never leave a stale copy behind.
 */
private class DominoCoordsHolder {
    var coords: LayoutCoordinates? = null
}

/** The chain's first tile (by instance id) as of the last auto-scroll, to tell a left play from a right one. */
private class DominoChainMemo {
    var firstId: Int? = null
}

// Must match the flight's own tween duration below (LaunchedEffect(flyingDomino)) so the
// per-move reactive effect's hit-stop/clatter can wait for a drag-sourced placement's
// flight to actually land before firing.
private const val FLIGHT_DURATION_MS = 260L
// 1-2 frames at 60fps (~16.6ms each) -- a genuine freeze-beat, not a perceptible pause.
private const val HIT_STOP_MS = 32L
private const val DEAL_STAGGER_MS = 70L
private const val TOPPLE_STAGGER_MS = 90L
private const val TOPPLE_DURATION_MS = 260
private const val TOPPLE_ANGLE_DEG = 78f
private const val HOLD_AFTER_TOPPLE_MS = 900L

// ---- Tile sizing (all dp, Float) ----

/** Chain tile base size (long side x short side) and hand tile base size; the measured slot scales them. */
private const val DOM_CHAIN_LONG_DP = 44f
private const val DOM_CHAIN_SHORT_DP = 32f
private const val DOM_HAND_W_DP = 32f
private const val DOM_HAND_H_DP = 60f

/** About this many chain tiles are meant to be in view; the long side stays within [MIN, MAX]. */
private const val DOM_CHAIN_IN_VIEW = 8
private const val DOM_CHAIN_MIN_DP = 44f
private const val DOM_CHAIN_MAX_DP = 72f

/** A hand row aims for seven touch boxes across, each between 48 (the touch floor) and 60dp wide. */
private const val DOM_HAND_PER_ROW = 7
private const val DOM_HAND_CELL_MIN_DP = 48f
private const val DOM_HAND_CELL_MAX_DP = 60f

/** A hand tile is its touch box minus this much, so neighbouring tiles keep a visible gap. */
private const val DOM_HAND_GUTTER_DP = 12f

private const val DOM_LIFT_MS = 120
private const val DOM_RESULT_FADE_MS = 250

/** The "this is the legal / selected" accent, and a colour-blind-safe blue standing in for it. */
private val DOM_OK_COLOR = Color(0xFF388E3C)
private val DOM_OK_COLORBLIND = Color(0xFF0072B2)

/** The "illegal" accent for the drag ghost, and a colour-blind-safe vermilion standing in for it. */
private val DOM_BAD_COLOR = Color(0xFFD32F2F)
private val DOM_BAD_COLORBLIND = Color(0xFFD55E00)

/**
 * Long side (dp) of a chain tile for a chain slot [slotWidthDp] wide. [fitBoard] never returns a
 * footprint larger than the slot; when it cannot give a tile [DOM_CHAIN_MIN_DP] the tile keeps
 * that minimum and the strip scrolls (it always can) instead of shrinking further.
 */
private fun dominoChainTileLongDp(slotWidthDp: Float): Float {
    val fit = fitBoard(
        availableWidthPx = slotWidthDp,
        availableHeightPx = DOM_CHAIN_MAX_DP,
        columns = DOM_CHAIN_IN_VIEW,
        rows = 1,
        minCellPx = DOM_CHAIN_MIN_DP,
        maxCellPx = DOM_CHAIN_MAX_DP
    )
    return if (fit.meetsMinimum) fit.cellPx else DOM_CHAIN_MIN_DP
}

/**
 * Width (dp) of one hand tile's touch box for a hand slot [slotWidthDp] wide: a seventh of the
 * slot, kept within the 48 to 60dp band. A slot too narrow for 48dp keeps 48dp and the row wraps.
 */
private fun dominoHandCellDp(slotWidthDp: Float): Float {
    val fit = fitBoard(
        availableWidthPx = slotWidthDp,
        availableHeightPx = DOM_HAND_CELL_MAX_DP,
        columns = DOM_HAND_PER_ROW,
        rows = 1,
        minCellPx = DOM_HAND_CELL_MIN_DP,
        maxCellPx = DOM_HAND_CELL_MAX_DP
    )
    return if (fit.meetsMinimum) fit.cellPx else DOM_HAND_CELL_MIN_DP
}

// ---- Text helpers ----

private fun dominoIsYou(name: String): Boolean = name.equals("You", ignoreCase = true)

private fun dominoPipTotal(hand: List<Domino>): Int = hand.sumOf { it.a + it.b }

private fun dominoPipsText(pips: Int): String = if (pips == 1) "1 pip" else "$pips pips"

private fun dominoPointsText(points: Int): String = if (points == 1) "1 point" else "$points points"

/** "You win the hand!" / "CPU wins the hand!" / "It's a tie!". The engine's own lastAction
 *  ("You wins!") is not grammatical for the local player, so the screen words the result itself. */
private fun dominoHandResultTitle(s: DominoState): String {
    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId } ?: return "It's a tie!"
    return if (dominoIsYou(winner.displayName)) "You win the hand!" else "${winner.displayName} wins the hand!"
}

/** Screen-reader description of one chain tile: its position, both values, and which end it is. */
private fun dominoChainTileDescription(index: Int, total: Int, placed: PlacedDomino): String {
    val ends = buildString {
        if (index == 0) append(", left end")
        if (index == total - 1) append(", right end")
    }
    return "Chain tile ${index + 1} of $total, ${placed.leftValue} and ${placed.rightValue}$ends"
}

// ---- Seat chip ----

@Composable
private fun DominoSeatChip(player: DominoPlayerState, isTurn: Boolean) {
    val tiles = player.hand.size
    val description = buildString {
        append(player.displayName)
        append(", ")
        append(if (tiles == 1) "1 tile" else "$tiles tiles")
        append(" in hand")
        if (isTurn) append(if (player.isBot) ", thinking" else ", to move")
    }
    Text(
        "${player.displayName}: $tiles",
        fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isTurn) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            // One labelled node per chip: name, tiles left, whose move. The visible text is a
            // shorthand ("You: 7") a screen reader would read without its meaning.
            .clearAndSetSemantics { contentDescription = description }
    )
}

// ---- Result panel ----

/**
 * The hand-over panel. Renders for a win, a blocked hand and (defensively) a tie: [s]'s hands
 * still hold the tiles left at the end, which is what the pips-left lines and the points scored
 * are computed from (the engine awards the winner the pips left in every OTHER hand).
 */
@Composable
private fun DominoHandResultPanel(
    s: DominoState,
    sessionScores: Map<String, Int>,
    reducedMotion: Boolean,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    // A short fade-in so the panel does not snap in over the table; none under reduced motion.
    val appear = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) appear.animateTo(1f, tween(DOM_RESULT_FADE_MS))
    }
    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
    // The engine's own blocked condition: everyone passed in a row.
    val blocked = s.consecutivePasses >= s.players.size
    val handPoints = if (winner == null) 0 else s.players.filter { it.playerId != winner.playerId }.sumOf { dominoPipTotal(it.hand) }
    // The engine gives a blocked hand with level lowest totals to the first seat (see
    // DominoGame.finishBlocked), so say that instead of claiming the lowest total won outright.
    val levelTotals = winner != null && s.players.any {
        it.playerId != winner.playerId && dominoPipTotal(it.hand) == dominoPipTotal(winner.hand)
    }
    val reason = when {
        winner == null -> "Nobody could move and the pip totals are level."
        blocked && levelTotals -> "Blocked with level pip totals, so the first seat takes the hand."
        blocked -> "Blocked: nobody can move, so the lowest pip total wins."
        dominoIsYou(winner.displayName) -> "You played your last tile."
        else -> "${winner.displayName} played the last tile."
    }
    val scoredText = when {
        winner == null -> "No points this hand."
        dominoIsYou(winner.displayName) -> "You score ${dominoPointsText(handPoints)}."
        else -> "${winner.displayName} scores ${dominoPointsText(handPoints)}."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .graphicsLayer { alpha = appear.value },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            dominoHandResultTitle(s),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(8.dp))
        Text(reason, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(scoredText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            s.players.joinToString(" · ") { "${it.displayName}: ${dominoPipsText(dominoPipTotal(it.hand))} left" },
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
        Text(
            "Session: " + s.players.joinToString(" · ") { "${it.displayName} ${sessionScores[it.playerId] ?: 0}" },
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPlayAgain,
            modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
        ) { Text("Play Again") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBackToMenu,
            modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
        ) { Text("Back to Menu") }
    }
}

// ---- Matte slate-table identity (replaces the chain's old bare background) ----

private val SLATE_BASE = Color(0xFF4B4F52)

private data class Speckle(val x: Float, val y: Float, val radiusFraction: Float, val alpha: Float)

/** Cached (fixed-seed) speckle layout for the table -- generated once, not per frame. */
private fun generateSpeckles(seed: Int, count: Int = 26): List<Speckle> {
    val r = Random(seed)
    return List(count) {
        Speckle(
            x = r.nextFloat(),
            y = r.nextFloat(),
            radiusFraction = 0.006f + r.nextFloat() * 0.01f,
            alpha = 0.05f + r.nextFloat() * 0.09f
        )
    }
}

/**
 * A matte, deliberately non-glossy mottled-gray + speckle-noise cached baseline (premium 2026
 * vision pitch, Dominoes section) -- distinct from Mancala's carved wood and UNO's
 * felt. A flat base color plus a handful of cached low-alpha speckle dots (see
 * [generateSpeckles]); no gradient sheen baked in here since "matte" is the whole point --
 * any shine comes only from the optional, restrained [specularSweep] layered on top by the
 * caller.
 */
@Composable
private fun Modifier.dominoSlateTable(): Modifier {
    val speckles = remember { generateSpeckles(seed = 47) }
    return this
        .background(SLATE_BASE)
        .drawBehind {
            speckles.forEach { sp ->
                drawCircle(
                    color = Color.Black.copy(alpha = sp.alpha),
                    radius = sp.radiusFraction * size.minDimension,
                    center = Offset(sp.x * size.width, sp.y * size.height)
                )
            }
        }
}

/**
 * One domino tile. [scale] multiplies every dimension of the 44x32dp (horizontal) / 32x60dp
 * (vertical) base tile, pips, padding and corner radius included, so a tile sized from its
 * measured slot keeps its proportions. The tile itself carries no semantics: its owner (a hand
 * button or a chain item) describes it.
 */
@Composable
private fun DominoTileView(top: Int, bottom: Int, horizontal: Boolean, dimmed: Boolean = false, scale: Float = 1f) {
    val bg = if (dimmed) Color(0xFFE0E0E0) else Color(0xFFFFF8E1)
    val pipColor = Color.Black.copy(alpha = if (dimmed) 0.45f else 0.85f)
    val dividerColor = Color.Black.copy(alpha = if (dimmed) 0.3f else 1f)
    val pipSize = (if (horizontal) 16.dp else 20.dp) * scale
    val shape = RoundedCornerShape(6.dp * scale)
    val content: @Composable () -> Unit = {
        PipFace(top, dotColor = pipColor, modifier = Modifier.size(pipSize))
        if (horizontal) {
            Box(Modifier.background(dividerColor).width(1.5.dp).fillMaxHeight(0.7f))
        } else {
            Box(Modifier.background(dividerColor).height(1.5.dp).fillMaxWidth(0.7f))
        }
        PipFace(bottom, dotColor = pipColor, modifier = Modifier.size(pipSize))
    }
    Box(
        modifier = Modifier
            .padding(2.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, Color.Black.copy(alpha = 0.3f), shape)
            .size(
                width = (if (horizontal) 44.dp else 32.dp) * scale,
                height = (if (horizontal) 32.dp else 60.dp) * scale
            )
            .padding(4.dp * scale),
        contentAlignment = Alignment.Center
    ) {
        if (horizontal) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) { content() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) { content() }
        }
    }
}

/**
 * One half of a domino tile rendered as real pip dots (a standard six-face
 * die layout) instead of a printed digit -- 0 is blank, 1 is a lone center
 * dot, 2/3 run the diagonal (3 adding the center), 4 is the four corners,
 * 5 is the four corners plus center, and 6 is two columns of three. Drawn
 * on a plain [Canvas] sized by the caller via [modifier] (a fixed square is
 * expected, but the layout is expressed in fractions of the actual measured
 * size so it stays centered regardless).
 */
@Composable
private fun PipFace(value: Int, dotColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val dotRadius = minOf(w, h) * 0.13f
        fun pt(fx: Float, fy: Float) = Offset(w * fx, h * fy)
        val positions = when (value.coerceIn(0, 6)) {
            0 -> emptyList()
            1 -> listOf(pt(0.5f, 0.5f))
            2 -> listOf(pt(0.28f, 0.28f), pt(0.72f, 0.72f))
            3 -> listOf(pt(0.25f, 0.25f), pt(0.5f, 0.5f), pt(0.75f, 0.75f))
            4 -> listOf(pt(0.25f, 0.25f), pt(0.75f, 0.25f), pt(0.25f, 0.75f), pt(0.75f, 0.75f))
            5 -> listOf(pt(0.25f, 0.25f), pt(0.75f, 0.25f), pt(0.5f, 0.5f), pt(0.25f, 0.75f), pt(0.75f, 0.75f))
            else -> listOf(
                pt(0.28f, 0.18f), pt(0.28f, 0.5f), pt(0.28f, 0.82f),
                pt(0.72f, 0.18f), pt(0.72f, 0.5f), pt(0.72f, 0.82f)
            )
        }
        positions.forEach { center -> drawCircle(color = dotColor, radius = dotRadius, center = center) }
    }
}

/**
 * A snapshot of one tile mid-flight -- from the hand to its landing spot in the chain, or
 * from the boneyard to the hand -- populated the instant a drag ends in a legal drop, or a
 * drawFromBoneyard() reveals a newly drawn tile (see the per-move reactive effect above),
 * and consumed by [FlyingDominoOverlay], driven by DominoesScreen's single shared
 * `flyProgress`. Only one is ever in flight at a time (a single device only ever has one
 * domino mid-drop/draw), same reasoning as UnoScreen's shared fly-to-discard-pile animation.
 * [scale] is the chain tile scale at launch, so the flying tile matches the tile it lands as.
 */
private data class FlyingDomino(
    val topValue: Int,
    val bottomValue: Int,
    val start: Offset,
    val end: Offset,
    val scale: Float
)

/**
 * Renders [fly] traveling from its captured drop position to its real chain
 * anchor as [progress] runs 0f -> 1f, with a small arc so it reads as a toss
 * rather than a straight slide. [use3D] adds a real perspective roll via
 * [card3DToss] (Settings -> Display -> "3D perspective mode", already
 * resolved with reducedMotion by the caller) -- independent of whether this
 * overlay exists at all (that's gated by `enhanced` at the call site).
 *
 * [progress] is a State read HERE, not by the caller: a flight advances every frame for 260ms,
 * and reading it at the screen's root recomposed the whole screen each of those frames.
 */
@Composable
private fun FlyingDominoOverlay(fly: FlyingDomino, progress: State<Float>, use3D: Boolean) {
    val density = LocalDensity.current
    val p = progress.value
    val eased = 1f - (1f - p) * (1f - p)
    val x = fly.start.x + (fly.end.x - fly.start.x) * eased
    // A 32dp-high arc at the midpoint of the flight (the arc was a raw 32px before, which
    // flattened on a dense screen).
    val arc = -with(density) { 32.dp.toPx() } * 4f * eased * (1f - eased)
    val y = fly.start.y + (fly.end.y - fly.start.y) * eased + arc
    val scale = 1f - 0.08f * eased
    val fadeOutStart = 0.85f
    val alpha = if (p > fadeOutStart) 1f - (p - fadeOutStart) / (1f - fadeOutStart) else 1f

    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .then(if (use3D) Modifier.card3DToss(p) else Modifier)
    ) {
        DominoTileView(top = fly.topValue, bottom = fly.bottomValue, horizontal = true, scale = fly.scale)
    }
}

/**
 * The translucent ghost shown while a hand tile is being dragged -- always
 * visible and always accurate the instant a drag starts (position snapped to
 * the real chain end, orientation from [DominoGame.wouldFlip], legal/
 * illegal border from [DominoGame.canPlace]), since that's the whole point
 * of the preview: it must never claim a placement is legal when playDomino()
 * would actually reject it, or vice versa. The verdict is also a check or cross
 * badge, so it does not rest on red versus green alone; [colorblind] swaps the
 * pair for a blue/vermilion one.
 */
@Composable
private fun DragGhostOverlay(top: Int, bottom: Int, position: Offset, legal: Boolean, scale: Float, colorblind: Boolean) {
    val okColor = if (colorblind) DOM_OK_COLORBLIND else DOM_OK_COLOR
    val badColor = if (colorblind) DOM_BAD_COLORBLIND else DOM_BAD_COLOR
    val borderColor = if (legal) okColor else badColor
    Box(
        modifier = Modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .graphicsLayer { alpha = 0.72f }
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        DominoTileView(top = top, bottom = bottom, horizontal = true, scale = scale)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(borderColor),
            contentAlignment = Alignment.Center
        ) {
            // Decorative: the ghost is a sighted-only drag preview (a screen-reader user plays a
            // tile by selecting it and using the Play on Left / Play on Right buttons).
            Text(
                if (legal) "✓" else "✕",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}
