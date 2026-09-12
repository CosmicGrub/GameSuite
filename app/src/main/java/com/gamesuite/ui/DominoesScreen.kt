package com.gamesuite.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
import com.gamesuite.games.dominoes.DominoState
import com.gamesuite.games.dominoes.PlacedDomino
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
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
 */
@Composable
fun DominoesScreen(
    sessionManager: GameSessionManager,
    game: DominoGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
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

    // Win-only toppling celebration (premium 2026 vision pitch): a ONE-TIME cascade of
    // the final chain, computed synchronously the instant a hand-over with a real winner
    // is first seen (remember's key is s.handOver itself, so this recomputes fresh every
    // time a NEW hand-over begins) -- avoids a one-frame flash of the summary panel
    // before the celebration would otherwise kick in via an effect. Skipped for a genuine
    // tie (winnerPlayerId == null, nothing to celebrate), an empty chain, or with enhanced
    // motion off/reduced motion on.
    var handOverPhase by remember(s.handOver) {
        mutableStateOf(s.handOver && s.winnerPlayerId != null && enhanced && s.chain.isNotEmpty())
    }
    val toppleAnims = remember(s.handOver) { List(s.chain.size) { Animatable(0f) } }
    LaunchedEffect(s.handOver) {
        if (!s.handOver) return@LaunchedEffect
        haptics(HapticSignal.SUCCESS)
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

    if (s.handOver && !handOverPhase) {
        val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("${winner?.displayName ?: "Nobody"} wins the hand!", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                s.players.joinToString(" · ") { "${it.displayName}: ${sessionScores[it.playerId] ?: 0}" },
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = game::playAgain, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Play Again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = game::leaveSession, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Back to Menu") }
        }
        return
    }

    // Address controls at whichever player's turn it currently is, not a fixed
    // "first human" guess — this is what lets a second local human act in
    // SINGLE_DEVICE_PASS_AND_PLAY instead of only ever seeing disabled buttons.
    val activePlayerIndex = s.currentPlayerIndex
    val isMyTurn = s.players.getOrNull(activePlayerIndex)?.isBot == false
    val canPlayNow = isMyTurn && game.canPlay(activePlayerIndex)
    var selectedDomino by remember { mutableStateOf<Domino?>(null) }

    val dragScope = rememberCoroutineScope()

    // Root-space anchors for "just past the leftmost chain tile" / "just past
    // the rightmost chain tile" — tiny zero-content marker items placed at the
    // very start/end of the chain LazyRow itself (see the `item(key = ...)`
    // calls below) so they track the chain's REAL rendered edges rather than
    // guessing from the LazyRow container's own (much wider) bounds. When the
    // chain is empty both anchors coincide, which is correct: either side is
    // an equally valid landing spot for an opening tile.
    var leftAnchorPos by remember { mutableStateOf(Offset.Zero) }
    var rightAnchorPos by remember { mutableStateOf(Offset.Zero) }
    // Root-space anchors for the new drawFromBoneyard() flight (premium 2026 vision
    // pitch) -- boneyardAnchorPos is the "Boneyard: N" label's own center, handAnchorPos
    // is a point just inside the start of the current hand's LazyRow.
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
    var showIllegalTint by remember { mutableStateOf(false) }
    val illegalTint by animateColorAsState(
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
            // -- a staggered reveal of the active hand plus a soft rattle-feeling sequence,
            // reusing the same scaleIn appear-transition the chain's own tiles already use.
            dealtCount = 0
            haptics(HapticSignal.LIGHT_TICK)
            val myHandSize = s.players.getOrNull(s.currentPlayerIndex)?.hand?.size ?: 0
            repeat(myHandSize) { i ->
                delay(DEAL_STAGGER_MS)
                dealtCount = i + 1
                sounds.playDraw()
            }
            return@LaunchedEffect
        }
        dealtCount = Int.MAX_VALUE // any hand already mid-play always shows fully revealed

        if (prev == null) return@LaunchedEffect // shouldn't normally happen once isDealIn is handled above, but stay defensive
        if (s.handOver && !prev.handOver) return@LaunchedEffect // the hand-over effect above owns that transition

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
                        flyingDomino = FlyingDomino(drawn.a, drawn.b, boneyardAnchorPos, handAnchorPos)
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

    Box(modifier = Modifier.fillMaxSize()) {
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        primary = {
        // CenterHorizontally at the Column level (matching UnoScreen's per-section
        // treatment) so every row here — not just the opponents summary — centers
        // within the capped TABLET-mode column instead of hugging its start edge.
        // Rows that already declare their own fillMaxWidth() (the opponents Row,
        // the chain LazyRow) are unaffected, since a full-width child ignores the
        // parent's alignment.
        //
        // Fold/rotation fix: verticalScroll, not a bare fillMaxWidth() Column -- this
        // pane's own bounded height (from AdaptiveTwoPane/FoldAwareTwoPane's weight(1f)
        // box, see FoldAwareLayout.kt's KDoc) is real, but in the tightest real vertical
        // budget in the app -- the Fold 5 cover screen rotated to landscape, ~344dp tall
        // total, split further by the hand row below -- the stacked score/status rows +
        // chain table + action buttons can come within a hair of that budget (or exceed
        // it once system font scaling is in play). A plain Column would silently clip the
        // bottom (likely the Play Left/Right/Draw/Pass buttons); verticalScroll costs
        // nothing when everything already fits (the overwhelmingly common case) and
        // guarantees every control stays reachable when it doesn't.
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            // Centers the group when it fits (typical case), still spaces-then-scrolls
            // once it overflows — see the identical fix/comment in UnoScreen.kt.
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
        ) {
            s.players.forEachIndexed { i, p ->
                Text(
                    "${p.displayName}: ${p.hand.size}",
                    fontWeight = if (i == s.currentPlayerIndex) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
        Text(
            "Boneyard: ${s.boneyardSize}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.onGloballyPositioned { boneyardAnchorPos = it.tileCenter() }
        )
        Text(
            "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(Modifier.height(12.dp))
        Text("Chain", style = MaterialTheme.typography.titleSmall)

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
        ) {
            // Zero-width markers bracketing the real chain content -- see the
            // leftAnchorPos/rightAnchorPos KDoc above for why these exist
            // instead of reading the LazyRow's own (much wider) container.
            item(key = "chain-left-anchor") {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .onGloballyPositioned { leftAnchorPos = it.positionInRoot() }
                )
            }
            itemsIndexed(s.chain, key = { _, item -> item.domino.instanceId }) { index, placed ->
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f)
                ) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            // Win-only toppling celebration -- each tile in the final chain
                            // rotates onto its edge in sequence (see toppleAnims above); 0f
                            // at every other time, a pure no-op transform.
                            rotationZ = toppleAnims.getOrNull(index)?.value ?: 0f
                            transformOrigin = TransformOrigin(0f, 1f)
                        }
                    ) {
                        DominoTileView(top = placed.leftValue, bottom = placed.rightValue, horizontal = true)
                    }
                }
            }
            item(key = "chain-right-anchor") {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .onGloballyPositioned { rightAnchorPos = it.positionInRoot() }
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

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedDomino != null && s.chain.isNotEmpty()) {
                val domino = selectedDomino!!
                // Each end has its own pip value, so a domino that legally matches the
                // left end may not match the right end (or vice versa) -- same check
                // playDomino() itself uses internally, just narrowed to one specific
                // end per button instead of "matches either end" (see `playable` below,
                // which is the "either end" version used for the hand list). Without
                // this, an illegal button stayed enabled, silently no-opped inside
                // DominoGame, and still fired the success sound/haptic/deselect.
                val matchesLeft = domino.a == s.leftEnd || domino.b == s.leftEnd
                val matchesRight = domino.a == s.rightEnd || domino.b == s.rightEnd
                Button(
                    enabled = isMyTurn && matchesLeft,
                    onClick = {
                        if (matchesLeft) {
                            lastPlacementWasDrag = false
                            game.playDomino(activePlayerIndex, domino, attachToLeft = true)
                            selectedDomino = null
                        }
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Play on Left") }
                Button(
                    enabled = isMyTurn && matchesRight,
                    onClick = {
                        if (matchesRight) {
                            lastPlacementWasDrag = false
                            game.playDomino(activePlayerIndex, domino, attachToLeft = false)
                            selectedDomino = null
                        }
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Play on Right") }
            }
            OutlinedButton(
                enabled = isMyTurn && !canPlayNow && s.boneyardSize > 0,
                onClick = { game.drawFromBoneyard(activePlayerIndex) },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) { Text("Draw") }
            OutlinedButton(
                enabled = isMyTurn && !canPlayNow && s.boneyardSize == 0,
                onClick = { game.pass(activePlayerIndex) },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) { Text("Pass") }
        }
        }
        },
        secondary = {
        Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (isMyTurn) "Your hand — drag a tile to an end, or tap to select then Play Left/Right"
            else "Your hand — tap to select, then Play Left/Right",
            style = MaterialTheme.typography.titleSmall
        )

        LazyRow(
            modifier = Modifier
                .padding(top = 8.dp)
                .onGloballyPositioned { handAnchorPos = it.positionInRoot() + Offset(24f, it.size.height / 2f) }
        ) {
            val myHand = s.players.getOrNull(activePlayerIndex)?.hand ?: emptyList()
            itemsIndexed(myHand, key = { _, domino -> domino.instanceId }) { handIndex, domino ->
                androidx.compose.animation.AnimatedVisibility(
                    visible = handIndex < dealtCount,
                    enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f)
                ) {
                val selected = selectedDomino?.instanceId == domino.instanceId
                val playable = s.chain.isEmpty() || domino.a == s.leftEnd || domino.b == s.leftEnd ||
                    domino.a == s.rightEnd || domino.b == s.rightEnd
                val isThisDragged = draggedDomino?.instanceId == domino.instanceId
                var tileRootPos by remember(domino.instanceId) { mutableStateOf(Offset.Zero) }

                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .border(if (selected) 2.dp else 0.dp, Color(0xFF388E3C), RoundedCornerShape(6.dp))
                        .graphicsLayer {
                            if (isThisDragged) {
                                translationX = if (isActivelyDragging) dragOffsetState.x else returnOffset.value.x + shakeX.value
                                translationY = if (isActivelyDragging) dragOffsetState.y else returnOffset.value.y
                            }
                        }
                        .onGloballyPositioned { tileRootPos = it.positionInRoot() }
                        .pointerInput(isMyTurn, playable, domino.instanceId) {
                            if (!isMyTurn || !playable) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    returnJob?.cancel()
                                    showIllegalTint = false
                                    draggedDomino = domino
                                    isActivelyDragging = true
                                    dragOffsetState = Offset.Zero
                                    val midX = (leftAnchorPos.x + rightAnchorPos.x) / 2f
                                    liveAttachToLeft = tileRootPos.x < midX
                                    haptics(HapticSignal.LIGHT_TICK)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffsetState += amount
                                    val fingerRefX = tileRootPos.x + dragOffsetState.x
                                    val midX = (leftAnchorPos.x + rightAnchorPos.x) / 2f
                                    liveAttachToLeft = fingerRefX < midX
                                },
                                onDragEnd = {
                                    isActivelyDragging = false
                                    val attachToLeft = liveAttachToLeft
                                    if (game.canPlace(domino, attachToLeft)) {
                                        val flipped = game.wouldFlip(domino, attachToLeft)
                                        val placed = PlacedDomino(domino, flipped)
                                        val startPos = Offset(tileRootPos.x + dragOffsetState.x, tileRootPos.y + dragOffsetState.y)
                                        val endPos = if (attachToLeft) leftAnchorPos else rightAnchorPos
                                        draggedDomino = null
                                        dragOffsetState = Offset.Zero
                                        lastPlacementWasDrag = true
                                        game.playDomino(activePlayerIndex, domino, attachToLeft)
                                        selectedDomino = null
                                        // The toss itself is the new stylistic flourish -- gated
                                        // behind `enhanced` per the pitch; with it off the tile
                                        // just lands instantly, matching today's flat behavior.
                                        if (enhanced) {
                                            flyingDomino = FlyingDomino(placed.leftValue, placed.rightValue, startPos, endPos)
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
                                                        shakeX.animateTo(-14f, tween(40))
                                                        shakeX.animateTo(14f, tween(80))
                                                        shakeX.animateTo(-9f, tween(80))
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
                        .clickable(enabled = isMyTurn && playable) {
                            selectedDomino = if (selected) null else domino
                            if (s.chain.isEmpty()) {
                                lastPlacementWasDrag = false
                                game.playDomino(activePlayerIndex, domino, attachToLeft = true)
                                selectedDomino = null
                            }
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        // Hand tiles are otherwise just two bare numbers separated by a divider
                        // bar -- nothing a screen reader can read as "domino" or "playable" on
                        // its own, so spell out both pip values and legality explicitly.
                        .semantics {
                            contentDescription = "Domino ${domino.a} dash ${domino.b}, " +
                                if (playable) "playable" else "not playable"
                        }
                ) {
                    DominoTileView(top = domino.a, bottom = domino.b, horizontal = false, dimmed = !playable)
                    if (isThisDragged && showIllegalTint) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .padding(2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(illegalTint)
                        )
                    }
                }
                }
            }
        }
        }
        }
    )

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
            val anchor = if (liveAttachToLeft) leftAnchorPos else rightAnchorPos
            DragGhostOverlay(
                top = if (flipped) domino.b else domino.a,
                bottom = if (flipped) domino.a else domino.b,
                position = anchor,
                legal = legal
            )
        }
    }

    flyingDomino?.let { fly ->
        FlyingDominoOverlay(fly = fly, progress = flyProgress.value, use3D = card3D)
    }
    }
}

/** This node's on-screen center in root coordinates -- see boneyardAnchorPos's KDoc above. */
private fun androidx.compose.ui.layout.LayoutCoordinates.tileCenter(): Offset =
    positionInRoot() + Offset(size.width / 2f, size.height / 2f)

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
 * A matte, deliberately non-glossy mottled-gray + speckle-noise cached baseline (premium
 * 2026 vision pitch, Dominoes section) -- distinct from Mancala's carved wood and UNO's
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

@Composable
private fun DominoTileView(top: Int, bottom: Int, horizontal: Boolean, dimmed: Boolean = false) {
    val bg = if (dimmed) Color(0xFFE0E0E0) else Color(0xFFFFF8E1)
    val pipColor = Color.Black.copy(alpha = if (dimmed) 0.45f else 0.85f)
    val dividerColor = Color.Black.copy(alpha = if (dimmed) 0.3f else 1f)
    val pipSize = if (horizontal) 16.dp else 20.dp
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
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .size(width = if (horizontal) 44.dp else 32.dp, height = if (horizontal) 32.dp else 60.dp)
            .padding(4.dp),
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
 */
private data class FlyingDomino(val topValue: Int, val bottomValue: Int, val start: Offset, val end: Offset)

/**
 * Renders [fly] traveling from its captured drop position to its real chain
 * anchor as [progress] runs 0f -> 1f, with a small arc so it reads as a toss
 * rather than a straight slide. [use3D] adds a real perspective roll via
 * [card3DToss] (Settings -> Display -> "3D perspective mode", already
 * resolved with reducedMotion by the caller) -- independent of whether this
 * overlay exists at all (that's gated by `enhanced` at the call site).
 */
@Composable
private fun FlyingDominoOverlay(fly: FlyingDomino, progress: Float, use3D: Boolean) {
    val eased = 1f - (1f - progress) * (1f - progress)
    val x = fly.start.x + (fly.end.x - fly.start.x) * eased
    val arc = -32f * 4f * eased * (1f - eased)
    val y = fly.start.y + (fly.end.y - fly.start.y) * eased + arc
    val scale = 1f - 0.08f * eased
    val fadeOutStart = 0.85f
    val alpha = if (progress > fadeOutStart) 1f - (progress - fadeOutStart) / (1f - fadeOutStart) else 1f

    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .then(if (use3D) Modifier.card3DToss(progress) else Modifier)
    ) {
        DominoTileView(top = fly.topValue, bottom = fly.bottomValue, horizontal = true)
    }
}

/**
 * The translucent ghost shown while a hand tile is being dragged -- always
 * visible and always accurate the instant a drag starts (position snapped to
 * the real chain-end anchor, orientation from [DominoGame.wouldFlip], legal/
 * illegal border from [DominoGame.canPlace]), since that's the whole point
 * of the preview: it must never claim a placement is legal when playDomino()
 * would actually reject it, or vice versa.
 */
@Composable
private fun DragGhostOverlay(top: Int, bottom: Int, position: Offset, legal: Boolean) {
    val borderColor = if (legal) Color(0xFF388E3C) else Color(0xFFD32F2F)
    Box(
        modifier = Modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .graphicsLayer { alpha = 0.72f }
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        DominoTileView(top = top, bottom = bottom, horizontal = true)
    }
}
