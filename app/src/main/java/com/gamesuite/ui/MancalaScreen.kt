package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import com.gamesuite.games.mancala.MancalaCapture
import com.gamesuite.games.mancala.MancalaGame
import com.gamesuite.games.mancala.MancalaMotionPrefs
import com.gamesuite.games.mancala.MancalaMotionTier
import com.gamesuite.games.mancala.MancalaState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Research pass (README item 9i) added a real CPU difficulty ladder — see
 * MancalaGame's `playBotTurn`/`minimaxBestMove` KDoc — read here from
 * Settings' "Default CPU difficulty" the same way the other per-game
 * upgrade passes already do.
 *
 * Premium 2026 vision pass (Mancala section) added: a real haptic vocabulary via
 * [rememberHaptics] (replacing the old generic `LocalHapticFeedback` buzz on every
 * pit tap), a carved-wood board identity (see [mancalaWoodSurface]) with an optional
 * idle specular sheen, capture hit-stop + camera-shake, layered seed-clatter audio
 * built from [CardSounds]' existing clips, and — gated behind the new
 * [MancalaMotionTier] preference — real per-seed physics (see [MancalaSeedPhysics])
 * once a seed's hop actually lands, on top of the arc-hop cascade this screen already
 * had. See each block below for exactly which setting gates it and why.
 */
@Composable
fun MancalaScreen(
    sessionManager: GameSessionManager,
    game: MancalaGame,
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
    rememberAmbientMusic(profile = MusicProfiles.MANCALA, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    // Settings -> Display -> "3D perspective mode", reduced motion always wins --
    // see ui/TablePerspective.kt's KDoc for why this is a board-agnostic reuse of
    // the same infrastructure UNO's card animations were built on.
    val card3D = LocalCard3DMode.current && !reducedMotion
    // Settings -> Display -> "Enhanced move animations" (see settings/LocalEnhancedAnimations.kt)
    // -- motion QUALITY (the seed-hop cascade + capture flourish below), distinct from card3D's
    // perspective/depth concern, so it's its own gate rather than folded into card3D; the two
    // compose independently per that setting's own KDoc. Always combined with reduced motion,
    // same rule as card3D above.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion

    // Mancala's own motion-intensity tier (see MancalaMotionPrefs.kt's KDoc for why this is a
    // per-game DataStore preference, not a global AppSettings field) -- Standard mirrors today's
    // shipped arc-cascade + capture-sweep exactly; Maximum additionally turns on the real
    // per-seed physics jostle + camera-shake below. Reduced motion / enhanced-off still win over
    // Maximum -- see `jostleActive` below.
    val motionPrefs = remember { MancalaMotionPrefs(androidContext) }
    val motionTier by motionPrefs.tier.collectAsState(initial = MancalaMotionTier.STANDARD)
    val scope = rememberCoroutineScope()

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

    // Keyed on the whole state object (not just currentPlayerIndex/roundOver) so this
    // relaunches on every move, including a bonus/extra turn where the mover keeps
    // possession of currentPlayerIndex — sow() always changes pits/lastAction, so a new
    // MancalaState is never equal to the previous one even when currentPlayerIndex repeats.
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        val ctx = context ?: return@LaunchedEffect
        if (s.roundOver) return@LaunchedEffect
        if (ctx.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(700)
            game.playBotTurn()
        }
    }

    val s = state ?: return
    val ctx = context ?: return

    if (s.roundOver) {
        val winner = ctx.players.firstOrNull { it.playerId == s.winnerPlayerId }
        val p1Name = ctx.players.getOrNull(0)?.displayName ?: "Player 1"
        val p2Name = ctx.players.getOrNull(1)?.displayName ?: "Player 2"
        val scoreP1 by game.scoreP1
        val scoreP2 by game.scoreP2
        val draws by game.draws
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(if (winner != null) "${winner.displayName} wins!" else "It's a tie!", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "$p1Name: $scoreP1 · $p2Name: $scoreP2" + if (draws > 0) " · Draws: $draws" else "",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = game::playAgain, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Play Again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = game::leaveSession, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Back to Menu") }
        }
        return
    }

    // Rows are gated by whose pits they are and whose turn it currently is — NOT by a
    // fixed "human index" (SINGLE_DEVICE_PASS_AND_PLAY has two non-bot players, so a
    // fixed index would permanently favor one side and dead-end the other's turn).
    // isHumanTurn additionally blocks the human from tapping while a bot is thinking,
    // preserving vs-bot behavior without needing to know which side "the human" is.
    val isHumanTurn = ctx.players.getOrNull(s.currentPlayerIndex)?.isBot != true

    // Capture preview: outline whichever of the current human player's legal pits would
    // land the last stone in an empty pit of theirs. Reuses MancalaGame.captureCandidates,
    // which is itself just a read-only call into the exact legalMoves()/simulateSow() pair
    // the HARD bot's minimax search already relies on -- purely visual, recomputed fresh
    // each recomposition, never touches sow()'s real state.
    val captureCandidates = if (isHumanTurn) game.captureCandidates(s.currentPlayerIndex) else emptySet()

    // Real per-seed physics jostle (see MancalaSeedPhysics below) is Maximum-tier-only AND
    // still yields to Settings -> Display "Enhanced move animations" / Reduced Motion, same
    // rule every other new motion in this pass follows -- Maximum never overrides either.
    val jostleActive = enhanced && motionTier == MancalaMotionTier.MAXIMUM
    val seedPhysics = remember { MancalaSeedPhysics() }
    var physicsFrame by remember { mutableStateOf(0L) }
    val shakeAnim = remember { Animatable(0f) }

    // The physics tick loop -- same withFrameNanos shape AirHockeyScreen's real-time loop
    // already uses in this project. Cancelled automatically the instant `jostleActive` goes
    // false (tier switched back to Standard, animations disabled, or reduced motion turned
    // on), so there is never a stray simulation running behind a flat board.
    LaunchedEffect(jostleActive) {
        if (!jostleActive) return@LaunchedEffect
        seedPhysics.syncAll(game.state.value?.pits ?: List(14) { 0 })
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    val dt = ((nanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    seedPhysics.step(dt)
                }
                lastFrameNanos = nanos
                physicsFrame++
            }
        }
    }

    // Seed-hop cascade + capture flourish (animation/physics pitch, Mancala section) -- gated
    // by `enhanced` so with the setting (or reduced motion) off, sowPathForAnim/captureForAnim
    // are always empty/null and this whole block is a no-op, leaving today's instant
    // final-board-only update exactly as it was.
    //
    // Real, live on-screen center of every pit/store (root coordinates) -- populated below via
    // onGloballyPositioned on each PitView/StoreView, same technique FannedHand/UnoScreen
    // already use for their own drag-lift and fly-to-discard-pile animations (see those files'
    // KDoc). Keyed by absolute board index (0-13, see MancalaGame's board-layout KDoc) so the
    // twelve pits and both stores share one lookup.
    val pitPositions = remember { mutableStateMapOf<Int, Offset>() }

    val sowPathForAnim = if (enhanced) s.lastSowPath else emptyList()
    // One Animatable per hop (path[i] -> path[i+1]) -- a fresh list every time `s` (or the
    // setting) changes, so each new sow always starts every hop back at 0f with no stale
    // progress left over from the previous move.
    val hopAnimations = remember(s, enhanced) {
        List((sowPathForAnim.size - 1).coerceAtLeast(0)) { Animatable(0f) }
    }
    val captureForAnim = if (enhanced) s.lastCapture else null
    val captureLandingAnim = remember(s, enhanced) { Animatable(0f) }
    val captureOppositeAnim = remember(s, enhanced) { Animatable(0f) }

    LaunchedEffect(s, enhanced) {
        if (!enhanced) return@LaunchedEffect
        // Staggered, not sequential: each hop starts well before the previous one lands
        // (~65-75% overlap) so a big sow (15-20+ stones) reads as one fluid cascade rather
        // than a slow single-file queue of seeds.
        if (sowPathForAnim.size > 1) {
            coroutineScope {
                hopAnimations.forEachIndexed { i, anim ->
                    launch {
                        delay(i * HOP_STAGGER_MS)
                        anim.animateTo(1f, animationSpec = tween(HOP_DURATION_MS))
                        // Real seed-pile physics (Maximum tier only, see MancalaSeedPhysics'
                        // own KDoc): the arc above got this seed TO its pit; only once it
                        // visually lands do we hand it to the physics sim, so it actually
                        // falls/jostles among whatever is already resting there.
                        if (jostleActive) seedPhysics.spawn(sowPathForAnim[i + 1])
                        // Layered seed-clatter: one tap per hop, staggered to match this same
                        // cascade's own timing -- see CardSounds.kt's own KDoc for why this
                        // can't be pitch-varied from here (its public API doesn't expose a
                        // per-call rate), so the "not the same sound every time" goal is
                        // delivered by how many/how staggered these calls are instead.
                        sounds.playTap()
                    }
                }
            }
        }
        // Capture flourish plays after the cascade above finishes (coroutineScope suspends
        // until every launched hop completes) -- the swept seeds visibly sweep into the
        // mover's store once the sow itself has finished landing.
        captureForAnim?.let { capture ->
            // Hit-stop: a 1-2 frame freeze the instant the swept seeds land, before the
            // sweep-into-store flourish plays -- pure "juice", gated only by `enhanced`
            // (not by motion tier) per this pass's own rule for new hit-stop/shake.
            delay(HIT_STOP_MS)
            if (jostleActive && capture.totalSwept >= BIG_CAPTURE_STONE_THRESHOLD) {
                // Fire-and-forget: the shake plays alongside the sweep below rather than
                // delaying it.
                launch { runCameraShake(shakeAnim) }
            }
            coroutineScope {
                launch { captureLandingAnim.animateTo(1f, animationSpec = tween(CAPTURE_HOP_DURATION_MS)) }
                launch { captureOppositeAnim.animateTo(1f, animationSpec = tween(CAPTURE_HOP_DURATION_MS)) }
            }
            if (jostleActive) {
                // The captured pits' seeds leave their old physics sims entirely and
                // reappear, freshly landed, in the mover's store once the sweep arrives.
                seedPhysics.clear(capture.landingPit)
                seedPhysics.clear(capture.oppositePit)
                val store = if (capture.landingPit <= 5) 6 else 13
                repeat(capture.totalSwept) { seedPhysics.spawn(store) }
            }
        }
        // Safety net: reconcile the physics sim to the board's real final counts regardless
        // of path -- covers edge cases the hop/capture instrumentation above doesn't walk
        // through move-by-move, e.g. the end-of-round sweep of every remaining pit into its
        // owner's store all at once (see MancalaGame.sow's roundOver branch).
        if (jostleActive) seedPhysics.syncAll(s.pits)
    }

    // Haptics + fallback/capture sound, reacting to the real board state itself rather than
    // to the click site -- this fires identically whether the move that produced `s` came
    // from a human tap or the bot's own turn, so a bot capture feels/sounds the same as a
    // human one instead of being silent (see rememberHaptics' own KDoc: it already reads
    // Settings -> Accessibility -> Haptics internally, no extra guard needed here).
    var prevMancalaState by remember { mutableStateOf<MancalaState?>(null) }
    LaunchedEffect(s) {
        val prev = prevMancalaState
        prevMancalaState = s
        if (prev == null) return@LaunchedEffect // the initial deal -- nothing happened yet

        val capture = s.lastCapture
        val justWonRound = s.roundOver && !prev.roundOver
        // A non-capturing sow that lands exactly in the mover's own store keeps
        // currentPlayerIndex unchanged instead of handing the turn over -- see
        // MancalaGame.sow's `extraTurn` branch.
        val isExtraTurn = !justWonRound && capture == null && s.currentPlayerIndex == prev.currentPlayerIndex

        when {
            justWonRound -> haptics(HapticSignal.SUCCESS)
            capture != null -> haptics(HapticSignal.STRONG_ACTION)
            isExtraTurn -> haptics(HapticSignal.SUCCESS)
            else -> haptics(HapticSignal.LIGHT_TICK)
        }

        if (capture != null) {
            // Layered "sweep" sound -- two overlapping existing clips standing in for the
            // rate-jitter CardSounds.kt's own KDoc describes (see the hop-cascade comment
            // above for why pitch itself isn't available from this file).
            sounds.playTap()
            delay(70)
            sounds.playPlace()
        } else if (!enhanced) {
            // With the hop cascade off there's no per-hop tap playing above, so this is the
            // only sow feedback there is.
            sounds.playTap()
        }
        if (justWonRound) {
            // The round actually ending was previously silent -- haptics(SUCCESS) above
            // fired but nothing audible did, even when the winning move was a plain sow
            // with no capture. Additive alongside whichever sow/capture sound just played,
            // never replacing it -- a real, distinct "the round is over" moment.
            playSfx(SfxKind.SUCCESS_CHIME)
        }
    }

    // Turn status / difficulty / motion-tier toggle -- reused as-is by both the portrait
    // (stacked above the board) and landscape (beside the board) arrangements below, so this
    // chrome is never the thing silently eating the vertical budget a short window (e.g. the
    // Fold 5 cover screen rotated to landscape, ~344dp tall) needs for the board itself.
    val chromeBlock: @Composable (Modifier) -> Unit = { chromeModifier ->
        Column(modifier = chromeModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (isHumanTurn) "Your turn — tap a pit to sow" else "Opponent's turn", style = MaterialTheme.typography.titleMedium)
            Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
            if (ctx.players.any { it.isBot }) {
                Text(
                    "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            // Motion-intensity tier control (premium 2026 vision pitch, Mancala section) --
            // same single-toggle-button shape as Solitaire's own draw-1/3 control
            // (SolitaireScreen.kt), see MancalaMotionPrefs.kt's KDoc for why the physics
            // jostle specifically warrants this where most other games in this pass don't.
            OutlinedButton(
                onClick = {
                    scope.launch {
                        motionPrefs.setTier(if (motionTier == MancalaMotionTier.MAXIMUM) MancalaMotionTier.STANDARD else MancalaMotionTier.MAXIMUM)
                    }
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text(if (motionTier == MancalaMotionTier.MAXIMUM) "Motion: Maximum" else "Motion: Standard")
            }
        }
    }

    // The board itself: 2x6 pits plus 2 stores. Scaled from BOTH the available width AND height
    // (never width alone -- the original scaleFactor here was width-only, exactly the shape most
    // likely to already handle width fine but forget to check it still fits vertically alongside
    // the chrome above/below it in a very short window, e.g. the Fold 5 cover screen rotated to
    // landscape at ~344dp tall). pitSize (the only actually-tappable element -- StoreView has no
    // onClick) is additionally floored to MIN_TOUCH_TARGET so a pit never becomes untappable
    // regardless of how little space is actually available; storeWidth/storeHeight are purely
    // visual and keep scaling down freely to fit a tight window.
    val boardBlock: @Composable (Modifier) -> Unit = { boardModifier ->
        BoxWithConstraints(
            modifier = boardModifier
                .graphicsLayer { translationX = shakeAnim.value }
                .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
        ) {
            val baseBoardWidth = 488.dp // 6 * 60dp pits/row + 2 * 64dp stores, see PitView/StoreView
            val baseBoardHeight = 140.dp // tallest single element at scale 1.0 -- the store column
            val widthFactor = maxWidth / baseBoardWidth
            val heightFactor = maxHeight / baseBoardHeight
            val scaleFactor = minOf(widthFactor, heightFactor).coerceIn(0.4f, 1.5f)
            val pitSize = (52.dp * scaleFactor).coerceAtLeast(MIN_TOUCH_TARGET)
            val storeWidth = 48.dp * scaleFactor
            val storeHeight = 140.dp * scaleFactor

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.onGloballyPositioned { pitPositions[13] = it.pitCenter() }) {
                    StoreView(
                        count = s.pits[13], ownerLabel = "Opponent", width = storeWidth, height = storeHeight,
                        card3D = card3D, jostleActive = jostleActive, physics = seedPhysics, slotIndex = 13, physicsFrame = physicsFrame
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        // Opponent's row runs right-to-left visually (12 downTo 7), but their
                        // pit numbering should still count up from 1 starting at the pit
                        // farthest from their store -- pit 7 -- matching how the player's own
                        // row (below) numbers from the pit farthest from their store (0).
                        (12 downTo 7).forEach { pit ->
                            Box(modifier = Modifier.onGloballyPositioned { pitPositions[pit] = it.pitCenter() }) {
                                PitView(
                                    count = s.pits[pit],
                                    enabled = isHumanTurn && s.currentPlayerIndex == 1 && s.pits[pit] > 0,
                                    highlightCapture = pit in captureCandidates,
                                    size = pitSize,
                                    ownerLabel = "Opponent",
                                    pitNumber = pit - 6,
                                    card3D = card3D,
                                    jostleActive = jostleActive,
                                    physics = seedPhysics,
                                    slotIndex = pit,
                                    physicsFrame = physicsFrame,
                                    onClick = { game.sow(1, pit) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        (0..5).forEach { pit ->
                            Box(modifier = Modifier.onGloballyPositioned { pitPositions[pit] = it.pitCenter() }) {
                                PitView(
                                    count = s.pits[pit],
                                    enabled = isHumanTurn && s.currentPlayerIndex == 0 && s.pits[pit] > 0,
                                    highlightCapture = pit in captureCandidates,
                                    size = pitSize,
                                    ownerLabel = "Your",
                                    pitNumber = pit + 1,
                                    card3D = card3D,
                                    jostleActive = jostleActive,
                                    physics = seedPhysics,
                                    slotIndex = pit,
                                    physicsFrame = physicsFrame,
                                    onClick = { game.sow(0, pit) }
                                )
                            }
                        }
                    }
                }
                Box(modifier = Modifier.onGloballyPositioned { pitPositions[6] = it.pitCenter() }) {
                    StoreView(
                        count = s.pits[6], ownerLabel = "Your", width = storeWidth, height = storeHeight,
                        card3D = card3D, jostleActive = jostleActive, physics = seedPhysics, slotIndex = 6, physicsFrame = physicsFrame
                    )
                }
            }
        }
    }

    // Wired into AdaptiveTwoPane (secondary = null -- Mancala has no natural "hand" pane) so a
    // Tab S9 or a fully-unfolded Fold 5 in landscape (TABLET mode) caps the board's width at
    // 840dp instead of stretching it across the whole window, matching every other game in the
    // suite. Inside primary, an aspect check reflows the chrome BESIDE the board (Row) rather
    // than above it (Column) whenever the window is wider than it is tall, and in both
    // arrangements the board's own BoxWithConstraints sits in a weight(1f) slot so it receives a
    // REAL bounded/reduced maxHeight (the space actually left after the chrome), not the whole
    // pane's height as if the chrome took none of it.
    //
    // The seed-hop/capture overlay below reads pit/store centers in ROOT coordinates
    // (pitPositions, populated via onGloballyPositioned -- see this file's own KDoc), so it
    // renders correctly regardless of which of the two arrangements below is active; it stays a
    // sibling of the aspect-branch content inside the same outer fillMaxSize Box exactly as
    // before.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        secondary = null,
        primary = {
            Box(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    if (maxWidth > maxHeight) {
                        Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            chromeBlock(
                                Modifier
                                    .widthIn(max = 200.dp)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                            )
                            Spacer(Modifier.width(16.dp))
                            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                boardBlock(Modifier.fillMaxSize())
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            chromeBlock(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(16.dp))
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                boardBlock(Modifier.fillMaxSize())
                            }
                        }
                    }
                }

                // The seed-hop cascade itself: one small seed per hop, each easing from its origin
                // pit's captured center to its destination pit's, with a parabolic arc -- same
                // eased-lerp-plus-arc shape as UnoScreen's FlyingCardOverlay (see that file's KDoc),
                // just pit-to-pit instead of hand-to-discard-pile.
                if (sowPathForAnim.size > 1) {
                    sowPathForAnim.zipWithNext().forEachIndexed { i, (fromPit, toPit) ->
                        SeedHop(
                            progress = hopAnimations.getOrNull(i)?.value ?: 0f,
                            from = pitPositions[fromPit] ?: Offset.Zero,
                            to = pitPositions[toPit] ?: Offset.Zero
                        )
                    }
                }

                // Capture flourish: the landing pit's and the opposite pit's seeds both sweep into
                // the mover's store (pit <= 5 is player 0's side -> store 6, see MancalaGame's
                // board-layout KDoc).
                captureForAnim?.let { capture ->
                    val store = if (capture.landingPit <= 5) 6 else 13
                    val storePos = pitPositions[store] ?: Offset.Zero
                    SeedHop(
                        progress = captureLandingAnim.value,
                        from = pitPositions[capture.landingPit] ?: Offset.Zero,
                        to = storePos,
                        arcHeightPx = CAPTURE_ARC_HEIGHT_PX
                    )
                    SeedHop(
                        progress = captureOppositeAnim.value,
                        from = pitPositions[capture.oppositePit] ?: Offset.Zero,
                        to = storePos,
                        arcHeightPx = CAPTURE_ARC_HEIGHT_PX
                    )
                }
            }
        }
    )
}

/** This node's on-screen center in root coordinates -- see the seed-hop position tracking above. */
private fun androidx.compose.ui.layout.LayoutCoordinates.pitCenter(): Offset =
    positionInRoot() + Offset(size.width / 2f, size.height / 2f)

private const val HOP_DURATION_MS = 220
// ~70% overlap between consecutive hops (65-75% target): the next hop starts only 30% of the
// way through the current one's flight instead of waiting for it to land.
private const val HOP_STAGGER_MS = 66L
private const val CAPTURE_HOP_DURATION_MS = 320
private const val CAPTURE_ARC_HEIGHT_PX = 90f
private const val SOW_ARC_HEIGHT_PX = 50f
private val SEED_SIZE = 12.dp
// 1-2 frames at 60fps (~16.6ms each) -- a genuine freeze-frame, not a perceptible pause.
private const val HIT_STOP_MS = 32L
private const val BIG_CAPTURE_STONE_THRESHOLD = 6

/** Real touch-target floor for a pit (the only actually-tappable board element -- StoreView has
 *  no onClick), coerced onto pitSize regardless of how little space is actually available (same
 *  "floor, never let it shrink below a usable tap size" pattern as
 *  [com.gamesuite.games.cards.CardScale]'s own multiplier). */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * One small seed traveling from [from] to [to] (root-coordinate pit/store centers, see
 * pitCenter()) as [progress] runs 0f -> 1f, on a parabolic arc -- the same eased-lerp-plus-arc
 * shape UnoScreen's FlyingCardOverlay uses for its fly-to-discard-pile toss, scaled down to a
 * quick pit-to-pit hop. Renders nothing at rest (progress <= 0f, not yet started) or once
 * landed (progress >= 1f) -- the pit's own count text already reflects the real, final board
 * the instant sow() returns, so the hopping seed is purely a decorative overlay on top of it.
 */
@Composable
private fun SeedHop(progress: Float, from: Offset, to: Offset, arcHeightPx: Float = SOW_ARC_HEIGHT_PX) {
    if (progress <= 0f || progress >= 1f) return
    val density = LocalDensity.current
    val seedPx = with(density) { SEED_SIZE.toPx() }
    val eased = 1f - (1f - progress) * (1f - progress)
    val x = from.x + (to.x - from.x) * eased
    // A small upward arc peaking at the midpoint, same shape as UnoScreen's own toss.
    val arc = -arcHeightPx * 4f * eased * (1f - eased)
    val y = from.y + (to.y - from.y) * eased + arc
    Box(
        modifier = Modifier
            .offset { IntOffset((x - seedPx / 2f).roundToInt(), (y - seedPx / 2f).roundToInt()) }
            .size(SEED_SIZE)
            .clip(CircleShape)
            .background(Color(0xFF4E342E))
    )
}

/** A short, decaying back-and-forth horizontal jolt applied to the whole board's translationX. */
private suspend fun runCameraShake(shakeAnim: Animatable<Float, AnimationVector1D>) {
    shakeAnim.snapTo(0f)
    val impulses = listOf(-14f, 10f, -7f, 4f, 0f)
    for (target in impulses) shakeAnim.animateTo(target, animationSpec = tween(35))
}

/**
 * Real per-seed physics for [MancalaMotionTier.MAXIMUM] -- every seed that has landed in a
 * pit/store gets actual position+velocity state, gravity, and simple circle-circle collision
 * resolution against every other seed resting in that same slot (semi-implicit Euler
 * integration, no physics-engine dependency needed -- at most ~48 seeds board-wide, which is
 * computationally trivial at 60fps). This layers ON TOP of the arc-hop cascade above: the arc
 * gets a seed TO its pit; this is what happens once it actually lands there.
 *
 * All coordinates are normalized to each slot's own local space -- x/y roughly span
 * [-halfExtent, halfExtent] on each axis (a plain axis-aligned box boundary rather than a true
 * circle: cheap, stable, and visually indistinguishable here since gravity already pulls
 * every seed toward the bottom-center long before it would reach a corner). The renderer
 * (see PitView/StoreView) maps this normalized space onto that slot's own actual pixel size,
 * so the same simulation works for both the round pits and the tall, narrow stores.
 */
private class MancalaSeedPhysics {
    class Seed(var x: Float, var y: Float, var vx: Float, var vy: Float)

    // Slot 6 and 13 are the two stores (see MancalaGame's board-layout KDoc) -- taller/
    // narrower than a pit since they can hold far more seeds by the end of a round.
    private val halfExtents: List<Pair<Float, Float>> = List(14) { i -> if (i == 6 || i == 13) 1f to 2.4f else 1f to 1f }
    private val slots: Array<MutableList<Seed>> = Array(14) { mutableListOf() }

    fun seedsFor(slot: Int): List<Seed> = slots.getOrElse(slot) { emptyList() }

    fun halfExtentFor(slot: Int): Pair<Float, Float> = halfExtents.getOrElse(slot) { 1f to 1f }

    /** A freshly-landed seed drops in near the top of its slot with a small random scatter. */
    fun spawn(slot: Int) {
        val list = slots.getOrNull(slot) ?: return
        val (hx, hy) = halfExtentFor(slot)
        list += Seed(
            x = (Random.nextFloat() - 0.5f) * 2f * (hx - SEED_RADIUS) * 0.5f,
            y = -(hy - SEED_RADIUS),
            vx = (Random.nextFloat() - 0.5f) * 1.2f,
            vy = 0.2f + Random.nextFloat() * 0.3f
        )
    }

    fun clear(slot: Int) {
        slots.getOrNull(slot)?.clear()
    }

    /** Reconciles every slot's physics-body count to [pits]' real counts -- see call sites' KDoc. */
    fun syncAll(pits: List<Int>) {
        pits.forEachIndexed { i, count ->
            val list = slots.getOrNull(i) ?: return@forEachIndexed
            while (list.size < count) spawn(i)
            while (list.size > count) list.removeAt(list.size - 1)
        }
    }

    fun step(dt: Float) {
        for (slot in slots.indices) {
            val list = slots[slot]
            if (list.isEmpty()) continue
            val (hx, hy) = halfExtents[slot]
            val maxX = hx - SEED_RADIUS
            val maxY = hy - SEED_RADIUS
            for (seed in list) {
                // Semi-implicit (symplectic) Euler: integrate velocity first, then use the
                // NEW velocity to move position -- unconditionally stable for this kind of
                // constant-gravity system, unlike explicit Euler.
                seed.vy += GRAVITY * dt
                seed.x += seed.vx * dt
                seed.y += seed.vy * dt
                val damp = (1f - FRICTION_PER_SEC * dt).coerceAtLeast(0f)
                seed.vx *= damp
                seed.vy *= damp
                if (seed.x > maxX) { seed.x = maxX; if (seed.vx > 0f) seed.vx = -seed.vx * WALL_DAMPING }
                if (seed.x < -maxX) { seed.x = -maxX; if (seed.vx < 0f) seed.vx = -seed.vx * WALL_DAMPING }
                if (seed.y > maxY) { seed.y = maxY; if (seed.vy > 0f) seed.vy = -seed.vy * WALL_DAMPING }
                if (seed.y < -maxY) { seed.y = -maxY; if (seed.vy < 0f) seed.vy = -seed.vy * WALL_DAMPING }
            }
            // Circle-circle collision resolution: a position-based push-apart (half the
            // overlap each way) plus a soft damp of the closing velocity along the collision
            // normal -- stable for many overlapping bodies and settles instead of jittering,
            // which a full impulse-based solver would need several iterations to guarantee.
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val dist = sqrt(dx * dx + dy * dy)
                    val minDist = SEED_RADIUS * 2f
                    if (dist > 0.0001f && dist < minDist) {
                        val overlap = (minDist - dist) / 2f
                        val nx = dx / dist
                        val ny = dy / dist
                        a.x -= nx * overlap; a.y -= ny * overlap
                        b.x += nx * overlap; b.y += ny * overlap
                        val relVx = b.vx - a.vx
                        val relVy = b.vy - a.vy
                        val relN = relVx * nx + relVy * ny
                        if (relN < 0f) {
                            val impulse = relN * 0.5f
                            a.vx += impulse * nx; a.vy += impulse * ny
                            b.vx -= impulse * nx; b.vy -= impulse * ny
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val SEED_RADIUS = 0.16f
        private const val GRAVITY = 2.6f
        private const val FRICTION_PER_SEC = 2.2f
        private const val WALL_DAMPING = 0.35f
    }
}

/**
 * Draws [physics]' current bodies for [slotIndex] as small circles, scaled from their
 * normalized simulation space onto this composable's actual rendered size. Reading
 * [physicsFrame] (otherwise unused) is what makes this recompose every physics tick --
 * [MancalaSeedPhysics] itself is plain mutable state outside Compose's snapshot system, so
 * without this the dots would render once at spawn and never visibly move.
 */
@Composable
private fun SeedPhysicsOverlay(physics: MancalaSeedPhysics, slotIndex: Int, physicsFrame: Long) {
    @Suppress("UNUSED_EXPRESSION") physicsFrame
    // fillMaxSize() rather than matchParentSize() -- this composable isn't itself declared
    // as a BoxScope extension, and PitView/StoreView's own outer Box already has an explicit
    // fixed size, so filling that is equivalent here without needing a BoxScope receiver.
    Canvas(modifier = Modifier.fillMaxSize()) {
        val (hx, hy) = physics.halfExtentFor(slotIndex)
        val scaleX = size.width / 2f / hx
        val scaleY = size.height / 2f / hy
        val seedRadiusPx = MancalaSeedPhysics.SEED_RADIUS * minOf(scaleX, scaleY)
        val cx = size.width / 2f
        val cy = size.height / 2f
        physics.seedsFor(slotIndex).forEachIndexed { idx, seed ->
            drawCircle(
                color = if (idx % 3 == 0) SEED_DOT_LIGHT else SEED_DOT_DARK,
                radius = seedRadiusPx,
                center = Offset(cx + seed.x * scaleX, cy + seed.y * scaleY)
            )
        }
    }
}

private val SEED_DOT_LIGHT = Color(0xFF7A5230)
private val SEED_DOT_DARK = Color(0xFF4E342E)

// ---- Carved-wood board identity (replaces the old flat pit/store fills) ----

private val WOOD_HIGHLIGHT = Color(0xFFC9986A)
private val WOOD_MID = Color(0xFF8D6238)
private val WOOD_DEEP = Color(0xFF4E3220)

private data class GrainStroke(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val rotationDeg: Float)

/** Cached (fixed-seed) grain-stroke layout for one pit/store -- generated once, not per frame. */
private fun generateGrain(seed: Int): List<GrainStroke> {
    val r = Random(seed * 92821 + 17)
    return List(4) {
        GrainStroke(
            cx = 0.5f + (r.nextFloat() - 0.5f) * 0.35f,
            cy = 0.5f + (r.nextFloat() - 0.5f) * 0.6f,
            rx = 0.4f + r.nextFloat() * 0.3f,
            ry = 0.08f + r.nextFloat() * 0.06f,
            rotationDeg = (r.nextFloat() - 0.5f) * 50f
        )
    }
}

/**
 * A cached procedural wood-grain + radial carved-bowl-shading baseline (premium 2026 vision
 * pitch, Mancala section) -- a radial gradient standing in for a bowl carved deeper toward its
 * center (lighter rim, darker toward the middle), plus a handful of thin curved "grain"
 * strokes. [seed] gives each pit/store its own fixed-but-distinct grain (see [generateGrain])
 * so the whole board doesn't look like one repeated stamp. [dim] slightly darkens the surface
 * for a currently-unplayable pit -- the same lighter/darker affordance the old flat-color
 * version used to signal "can I tap this."
 */
@Composable
private fun Modifier.mancalaWoodSurface(seed: Int, dim: Boolean): Modifier {
    val grain = remember(seed) { generateGrain(seed) }
    return this
        .background(Brush.radialGradient(colors = listOf(WOOD_HIGHLIGHT, WOOD_MID, WOOD_DEEP)))
        .drawBehind {
            if (dim) drawRect(Color.Black.copy(alpha = 0.22f))
            grain.forEach { g ->
                rotate(g.rotationDeg, pivot = Offset(size.width * 0.5f, size.height * 0.5f)) {
                    drawOval(
                        color = Color.Black.copy(alpha = 0.14f),
                        topLeft = Offset(size.width * (g.cx - g.rx / 2f), size.height * (g.cy - g.ry / 2f)),
                        size = Size(size.width * g.rx, size.height * g.ry),
                        style = Stroke(width = 1.6f)
                    )
                }
            }
        }
}

@Composable
private fun PitView(
    count: Int,
    enabled: Boolean,
    ownerLabel: String,
    pitNumber: Int,
    highlightCapture: Boolean = false,
    size: Dp = 52.dp,
    card3D: Boolean = false,
    jostleActive: Boolean = false,
    physics: MancalaSeedPhysics? = null,
    slotIndex: Int = -1,
    physicsFrame: Long = 0L,
    onClick: () -> Unit
) {
    // Settings -> Accessibility -> Reduced Motion (see settings/LocalReducedMotion.kt) --
    // same technique FannedHand.kt already uses: swap the spring for snap() so the capture
    // shrink/settle still happens, just without the motion.
    val reducedMotion = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (count > 0) 1f else 0.85f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pitScale"
    )
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .mancalaWoodSurface(seed = slotIndex, dim = !enabled)
            .specularSweep(enabled = card3D, tint = Color(0xFFFFE9BE).copy(alpha = 0.45f))
            .border(
                if (highlightCapture) 3.dp else 1.dp,
                if (highlightCapture) Color(0xFFFFC107) else Color(0xFF3E2A18),
                CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            // Screen-reader announcement mirrors the visible layout (owner side + this pit's
            // position within that side, farthest-from-store first) plus the live stone count,
            // since a TalkBack user can't see which pit their finger landed on otherwise.
            .semantics { contentDescription = "$ownerLabel pit $pitNumber, ${stoneCountLabel(count)}" },
        contentAlignment = Alignment.Center
    ) {
        if (jostleActive && physics != null) {
            SeedPhysicsOverlay(physics = physics, slotIndex = slotIndex, physicsFrame = physicsFrame)
        }
        Text(count.toString(), fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun StoreView(
    count: Int,
    ownerLabel: String,
    width: Dp = 48.dp,
    height: Dp = 140.dp,
    card3D: Boolean = false,
    jostleActive: Boolean = false,
    physics: MancalaSeedPhysics? = null,
    slotIndex: Int = -1,
    physicsFrame: Long = 0L
) {
    Box(
        modifier = Modifier
            .padding(8.dp)
            .size(width = width, height = height)
            .clip(RoundedCornerShape(12.dp))
            .mancalaWoodSurface(seed = slotIndex, dim = false)
            .specularSweep(enabled = card3D, tint = Color(0xFFFFE9BE).copy(alpha = 0.4f))
            .semantics { contentDescription = "$ownerLabel store, ${stoneCountLabel(count)}" },
        contentAlignment = Alignment.Center
    ) {
        if (jostleActive && physics != null) {
            SeedPhysicsOverlay(physics = physics, slotIndex = slotIndex, physicsFrame = physicsFrame)
        }
        Text(count.toString(), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** "1 stone" vs "4 stones" -- the pit/store semantics text reads naturally either way. */
private fun stoneCountLabel(count: Int): String = if (count == 1) "1 stone" else "$count stones"
