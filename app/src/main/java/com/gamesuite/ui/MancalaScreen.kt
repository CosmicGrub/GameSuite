package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayerInfo
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.mancala.MancalaGame
import com.gamesuite.games.mancala.MancalaMotionPrefs
import com.gamesuite.games.mancala.MancalaMotionTier
import com.gamesuite.games.mancala.MancalaState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import com.gamesuite.ui.effects.shakeSteps
import com.gamesuite.ui.effects.specularSweep
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders MancalaGame's state: a 2x6 pit board with a store at each end (standard Kalah rules,
 * all in the engine), a CPU/pass-and-play turn prompt, and a result panel.
 *
 * LAYOUT AND SIZING: the board is sized with [fitBoard] from the measured slot it lives in (never
 * an outer scope). One layout unit `u` is a quarter of a pit's pitch: a pit slot is 4u x 5u, a
 * store 3u x 10u, so the whole playing area is 30 x 10 units inside an 8dp wooden slab. `u` is
 * floored to whole pixels and clamped to [MIN_UNIT_DP, MAX_UNIT_DP] (pit pitch 28..96dp), so all
 * six pits per side fit a 312dp-wide Fold 5 cover pane (about 39dp pitch) and the board does not
 * sprawl on a tablet. Below the 28dp pit-pitch floor the board is not shrunk further; it scrolls
 * instead. A pit's tap target is its whole slot (taller than wide), not just the drawn circle.
 * Windows wider than tall put the status/result column beside the board; otherwise above it.
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, the motion-tier toggle, Back to Menu)
 * plus its BackHandler. Mid-game exit asks first and is an abort (never a win or a loss) unless
 * earlier games this session were already finished, in which case it leaves through
 * leaveSession() so those results still count. The status block reserves [GameChromeEndInset] at
 * its end so the corner button never covers it.
 *
 * RESULT: the board stays on screen when a game ends. The result panel appears after a hold long
 * enough for the final sow/capture cascade to play (at least 600ms); a tie renders as a tie; the
 * success haptic and chime fire only when a human wins (never for the CPU's win or a tie).
 *
 * ACCESSIBILITY: every pit is a button described as "<owner> pit N, K stones" (N counts from the
 * pit farthest from that player's store; the description string is also what the instrumented
 * rotation test looks for) with a state description (playable / would capture / empty / not
 * playable now); stores and count digits are described once, not twice. Capture-ready pits carry
 * a thicker ring plus, with colorblind mode on, a star. Count digits are dark ink on the lit wood
 * (about 5:1 even on dimmed pits). The turn prompt is a polite live region.
 *
 * MOTION (all off with Reduced Motion / "Enhanced move animations" off, unchanged from the
 * premium 2026 pass): seed-hop cascade, capture sweep with hit-stop, optional specular sheen, and
 * in the Maximum [MancalaMotionTier] per-seed physics plus a camera shake on big captures. The
 * hop overlay reads pit centers in root coordinates and subtracts its own root origin, so it also
 * lines up in the width-capped tablet layout. Per-frame values (hop progress, physics tick) are
 * read in layer/draw lambdas, not in composition.
 *
 * Research pass (README item 9i) added the CPU difficulty ladder, read from Settings' "Default
 * CPU difficulty".
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
    // Ambient music -- ANDs the dedicated ambient-music setting with the master sound toggle
    // (see LocalMusicEnabled.kt's own KDoc for why both must hold).
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.MANCALA, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    // Settings -> Display -> "3D perspective mode"; reduced motion always wins.
    val card3D = LocalCard3DMode.current && !reducedMotion
    // Settings -> Display -> "Enhanced move animations" (the seed-hop cascade + capture flourish);
    // always combined with reduced motion, same rule as card3D above.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val pxPerDp = LocalDensity.current.density

    // Mancala's own motion-intensity tier (see MancalaMotionPrefs.kt): Standard is the arc cascade
    // + capture sweep; Maximum adds per-seed physics + camera shake. Reduced motion /
    // enhanced-off still win over Maximum -- see `jostleActive` below.
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
    // Deliberately NOT `context ?: return`: setOnMatchEnd (above) calls endActiveGame, which nulls
    // activeContext the same beat the session ends, and nothing below may be skipped (or its
    // remembered state thrown away) because of that. The last known players are kept so the
    // screen does not flash default names or a wrong result while its exit transition runs.
    val playersCache = remember { arrayOfNulls<List<PlayerInfo>>(1) }
    context?.players?.let { playersCache[0] = it }
    val players: List<PlayerInfo> = context?.players ?: playersCache[0] ?: emptyList()
    val hasBot = players.any { it.isBot }
    val mover = players.getOrNull(s.currentPlayerIndex)
    val ownerLabel0 = ownerLabelFor(0, players, hasBot)
    val ownerLabel1 = ownerLabelFor(1, players, hasBot)
    val p0Name = players.getOrNull(0)?.displayName ?: "Player 1"
    val p1Name = players.getOrNull(1)?.displayName ?: "Player 2"

    val scoreP1 by game.scoreP1
    val scoreP2 by game.scoreP2
    val draws by game.draws
    // Finished games this session (won or drawn). Leaving mid-game must still count these.
    val finishedRounds = scoreP1 + scoreP2 + draws

    val winner = if (s.roundOver) players.firstOrNull { it.playerId == s.winnerPlayerId } else null
    // Celebration (haptic, chime) is for a HUMAN's win only -- never the CPU's win or a tie.
    val humanWon = winner != null && !winner.isBot

    // Rows are gated by whose pits they are and whose turn it currently is — NOT by a
    // fixed "human index" (SINGLE_DEVICE_PASS_AND_PLAY has two non-bot players, so a
    // fixed index would permanently favor one side and dead-end the other's turn).
    // isHumanTurn additionally blocks the human from tapping while a bot is thinking,
    // preserving vs-bot behavior without needing to know which side "the human" is.
    val isHumanTurn = mover?.isBot != true
    val humanCanMove = isHumanTurn && !s.roundOver

    // Capture preview: outline whichever of the current human player's legal pits would
    // land the last stone in an empty pit of theirs. Reuses MancalaGame.captureCandidates
    // (read-only, the same simulateSow the HARD bot's search uses).
    val captureCandidates = remember(s, humanCanMove) {
        if (humanCanMove) game.captureCandidates(s.currentPlayerIndex) else emptySet<Int>()
    }

    // A sow whose last stone lands in the mover's own store keeps currentPlayerIndex on the
    // mover; the engine never records that as a flag, but the sow path always ends in that
    // store exactly then (a sow never ends in the opponent's store, which it skips).
    val extraTurnOwed = !s.roundOver && s.lastSowPath.lastOrNull() == storeIndexOf(s.currentPlayerIndex)

    // Real per-seed physics jostle (see MancalaSeedPhysics below) is Maximum-tier-only AND
    // still yields to Settings -> Display "Enhanced move animations" / Reduced Motion.
    val jostleActive = enhanced && motionTier == MancalaMotionTier.MAXIMUM
    val seedPhysics = remember { MancalaSeedPhysics() }
    // Read only inside draw lambdas (SeedPhysicsOverlay), so a tick redraws the seeds without
    // recomposing the board.
    val physicsFrame = remember { mutableStateOf(0L) }
    val shakeAnim = remember { Animatable(0f) }

    // The physics tick loop -- same withFrameNanos shape AirHockeyScreen's real-time loop uses.
    // Cancelled automatically the instant `jostleActive` goes false.
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
                physicsFrame.value = physicsFrame.value + 1L
            }
        }
    }

    // Seed-hop cascade + capture flourish -- gated by `enhanced` so with the setting (or reduced
    // motion) off, sowPathForAnim/captureForAnim are always empty/null and this whole block is a
    // no-op, leaving an instant final-board-only update.
    //
    // Real, live on-screen center of every pit/store (root coordinates), populated via
    // onGloballyPositioned on each pit/store, keyed by absolute board index (0-13, see
    // MancalaGame's board-layout KDoc) so the twelve pits and both stores share one lookup.
    val pitPositions = remember { mutableStateMapOf<Int, Offset>() }
    // Root position of the overlay that draws the hops; its children are placed relative to it.
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    val sowPathForAnim = if (enhanced) s.lastSowPath else emptyList()
    // One Animatable per hop (path[i] -> path[i+1]) -- a fresh list every time `s` (or the
    // setting) changes, so each new sow always starts every hop back at 0f.
    val hopAnimations = remember(s, enhanced) {
        List((sowPathForAnim.size - 1).coerceAtLeast(0)) { Animatable(0f) }
    }
    val captureForAnim = if (enhanced) s.lastCapture else null
    val captureLandingAnim = remember(s, enhanced) { Animatable(0f) }
    val captureOppositeAnim = remember(s, enhanced) { Animatable(0f) }

    // Runs for EVERY state including the one that ends the game: the board stays composed on
    // roundOver now, so the final sow/capture cascade plays instead of being cut off.
    LaunchedEffect(s, enhanced) {
        if (!enhanced) return@LaunchedEffect
        // Staggered, not sequential: each hop starts well before the previous one lands
        // (~65-75% overlap) so a big sow (15-20+ stones) reads as one fluid cascade.
        if (sowPathForAnim.size > 1) {
            coroutineScope {
                hopAnimations.forEachIndexed { i, anim ->
                    launch {
                        delay(i * HOP_STAGGER_MS)
                        anim.animateTo(1f, animationSpec = tween(HOP_DURATION_MS))
                        // Real seed-pile physics (Maximum tier only): the arc got this seed TO its
                        // pit; only once it visually lands do we hand it to the physics sim.
                        if (jostleActive) seedPhysics.spawn(sowPathForAnim[i + 1])
                        // Layered seed-clatter: one tap per hop, staggered to match this same
                        // cascade's own timing (CardSounds exposes no per-call pitch).
                        sounds.playTap()
                    }
                }
            }
        }
        // Capture flourish plays after the cascade above finishes (coroutineScope suspends
        // until every launched hop completes).
        captureForAnim?.let { capture ->
            // Hit-stop: a 1-2 frame freeze the instant the swept seeds land.
            delay(HIT_STOP_MS)
            if (jostleActive && capture.totalSwept >= BIG_CAPTURE_STONE_THRESHOLD) {
                // Fire-and-forget: the shake plays alongside the sweep below. Pattern is in dp
                // (magnitudePx = one dp in px) so the shake is the same size on every density.
                launch { shakeAnim.shakeSteps(magnitudePx = pxPerDp, stepDurationMs = 35, pattern = SHAKE_PATTERN_DP) }
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
        // of path -- covers the end-of-round sweep of every remaining pit into its owner's store.
        if (jostleActive) seedPhysics.syncAll(s.pits)
    }

    // Haptics + fallback/capture sound, reacting to the real board state itself rather than to
    // the click site -- identical whether the move came from a human tap or the bot.
    var prevMancalaState by remember { mutableStateOf<MancalaState?>(null) }
    LaunchedEffect(s) {
        val prev = prevMancalaState
        prevMancalaState = s
        // The initial deal and a Play Again deal (both have an empty sow path) are not moves.
        if (prev == null || s.lastSowPath.isEmpty()) return@LaunchedEffect

        val capture = s.lastCapture
        val roundJustEnded = s.roundOver && !prev.roundOver
        val moverWasBot = players.getOrNull(prev.currentPlayerIndex)?.isBot == true
        // A non-capturing sow that lands exactly in the mover's own store keeps
        // currentPlayerIndex unchanged -- see MancalaGame.sow's `extraTurn` branch.
        val isExtraTurn = !s.roundOver && capture == null && s.currentPlayerIndex == prev.currentPlayerIndex

        when {
            roundJustEnded && humanWon -> haptics(HapticSignal.SUCCESS)
            capture != null -> haptics(HapticSignal.STRONG_ACTION)
            isExtraTurn && !moverWasBot -> haptics(HapticSignal.SUCCESS)
            else -> haptics(HapticSignal.LIGHT_TICK)
        }

        if (capture != null) {
            // Layered "sweep" sound -- two overlapping existing clips.
            sounds.playTap()
            delay(70)
            sounds.playPlace()
        } else if (!enhanced) {
            // With the hop cascade off there's no per-hop tap above, so this is the only sow
            // feedback there is.
            sounds.playTap()
        }
        // The round ending is a distinct audible moment, but only a human win is celebrated.
        if (roundJustEnded && humanWon) playSfx(SfxKind.SUCCESS_CHIME)
    }

    // The result panel waits for the final sow/capture cascade to play out over the live board.
    val resultHoldMs = if (enhanced) {
        val cascadeMs = s.lastSowPath.size * HOP_STAGGER_MS + HOP_DURATION_MS +
            (if (s.lastCapture != null) HIT_STOP_MS + CAPTURE_HOP_DURATION_MS else 0L)
        (cascadeMs + 300L).coerceIn(MIN_RESULT_HOLD_MS, MAX_RESULT_HOLD_MS)
    } else {
        MIN_RESULT_HOLD_MS
    }
    var resultVisible by remember { mutableStateOf(false) }
    LaunchedEffect(s.roundOver) {
        if (s.roundOver) {
            delay(resultHoldMs)
            resultVisible = true
        } else {
            resultVisible = false
        }
    }
    // The && keeps the panel off a freshly dealt board during the one frame before the effect above resets it.
    val showResult = resultVisible && s.roundOver

    val prompt = turnPrompt(mover = mover, hasBot = hasBot, extraTurn = extraTurnOwed, roundOver = s.roundOver)
    val summary = moveSummary(s, players)
    val detailLines = buildList<String> {
        if (hasBot) {
            add("CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}")
        }
        if (finishedRounds > 0 && !s.roundOver) add(sessionLine(p0Name, p1Name, scoreP1, scoreP2, draws))
    }
    val resultTitle = if (winner != null) winTitle(winner.displayName) else "It's a tie!"
    val stonesLine = "Final stones: $p0Name ${s.pits[6]} · $p1Name ${s.pits[13]}"
    val resultSessionLine = sessionLine(p0Name, p1Name, scoreP1, scoreP2, draws)

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-game discards
    // ONLY the unfinished game: if earlier games this session were already won or drawn, leaving
    // goes through leaveSession() so those results still count; only a session with nothing
    // finished is a pure abort (never a win or loss). A finished game always leaves through
    // leaveSession(), which scores the session.
    GameChrome(
        helpTitle = "How to Play Mancala",
        helpText = "Tap one of your pits to pick up its stones and sow them one at a time around " +
            "the board, toward and past your own store. Your opponent's store is skipped.\n\n" +
            "If your last stone lands in your own store, you take another turn. If it lands in an " +
            "empty pit on your side and the pit opposite has stones, you capture both piles " +
            "into your store; pits that would capture are ringed in gold.\n\n" +
            "The game ends when one side has no stones left. The other side's remaining stones go " +
            "to its owner's store, and whoever has more stones in their store wins (equal is a tie).",
        matchInProgress = !s.roundOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRounds > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = MaterialTheme.colorScheme.background,
        buttonContent = MaterialTheme.colorScheme.onBackground,
        leaveTitle = "Leave this game?",
        leaveBody = if (finishedRounds > 0) {
            "This game is still in progress and won't count, but the games you've already finished stay on your record."
        } else {
            "This game is still in progress. Leaving now won't count it as a win or a loss."
        },
        extraItems = { dismiss ->
            // The motion-tier control used to be a permanent button competing with the board.
            DropdownMenuItem(
                text = { Text(if (motionTier == MancalaMotionTier.MAXIMUM) "Use standard motion" else "Use maximum motion") },
                onClick = {
                    dismiss()
                    scope.launch {
                        motionPrefs.setTier(
                            if (motionTier == MancalaMotionTier.MAXIMUM) MancalaMotionTier.STANDARD else MancalaMotionTier.MAXIMUM
                        )
                    }
                }
            )
        }
    ) {
        // The board itself: 2x6 pits plus 2 stores on a wooden slab, sized by fitBoard from the
        // measured space of the slot it lives in (see this file's KDoc for the unit grid).
        val boardBlock: @Composable (Modifier) -> Unit = { boardModifier ->
            BoxWithConstraints(modifier = boardModifier, contentAlignment = Alignment.Center) {
                val dpPx = LocalDensity.current.density
                val fit = remember(maxWidth, maxHeight, dpPx) {
                    fitBoard(
                        availableWidthPx = maxWidth.value * dpPx,
                        availableHeightPx = maxHeight.value * dpPx,
                        columns = BOARD_COLUMNS,
                        rows = BOARD_ROWS,
                        framePx = (SLAB_PADDING.value * dpPx).roundToInt().toFloat(),
                        minCellPx = MIN_UNIT_DP * dpPx,
                        maxCellPx = MAX_UNIT_DP * dpPx
                    )
                }
                // Whole pixels so every slot size is exact and the row can never round past the
                // measured width; below the floor we stop shrinking and scroll instead.
                val unitPx = (if (fit.meetsMinimum) fit.cellPx else MIN_UNIT_DP * dpPx).toInt().coerceAtLeast(1)
                val u = (unitPx / dpPx).dp

                val hScroll = rememberScrollState()
                val vScroll = rememberScrollState()
                val scrollModifier = if (fit.meetsMinimum) {
                    Modifier
                } else {
                    Modifier.verticalScroll(vScroll).horizontalScroll(hScroll)
                }

                Box(modifier = scrollModifier) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer { translationX = shakeAnim.value }
                            .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
                            .clip(SlabShape)
                            .background(SlabBrush)
                            .border(2.dp, WOOD_DEEP, SlabShape)
                            .padding(SLAB_PADDING)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.onGloballyPositioned { pitPositions[13] = it.pitCenter() }) {
                                StoreView(
                                    count = s.pits[13], ownerLabel = ownerLabel1, unit = u,
                                    card3D = card3D, jostleActive = jostleActive, physics = seedPhysics,
                                    slotIndex = 13, physicsFrame = physicsFrame
                                )
                            }
                            Column {
                                Row {
                                    // Player 1's row runs right-to-left visually (12 downTo 7), but
                                    // their pit numbering still counts up from 1 starting at the pit
                                    // farthest from their store -- pit 7 -- matching how player 0's
                                    // row (below) numbers from the pit farthest from their store (0).
                                    (12 downTo 7).forEach { pit ->
                                        Box(modifier = Modifier.onGloballyPositioned { pitPositions[pit] = it.pitCenter() }) {
                                            PitView(
                                                count = s.pits[pit],
                                                enabled = humanCanMove && s.currentPlayerIndex == 1 && s.pits[pit] > 0,
                                                highlightCapture = pit in captureCandidates,
                                                ownerLabel = ownerLabel1,
                                                pitNumber = pit - 6,
                                                unit = u,
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
                                Row {
                                    (0..5).forEach { pit ->
                                        Box(modifier = Modifier.onGloballyPositioned { pitPositions[pit] = it.pitCenter() }) {
                                            PitView(
                                                count = s.pits[pit],
                                                enabled = humanCanMove && s.currentPlayerIndex == 0 && s.pits[pit] > 0,
                                                highlightCapture = pit in captureCandidates,
                                                ownerLabel = ownerLabel0,
                                                pitNumber = pit + 1,
                                                unit = u,
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
                                    count = s.pits[6], ownerLabel = ownerLabel0, unit = u,
                                    card3D = card3D, jostleActive = jostleActive, physics = seedPhysics,
                                    slotIndex = 6, physicsFrame = physicsFrame
                                )
                            }
                        }
                    }
                }
            }
        }

        // Wired into AdaptiveTwoPane (secondary = null -- Mancala has no natural "hand" pane) so a
        // Tab S9 or a fully-unfolded Fold 5 in landscape (TABLET mode) caps the board's width at
        // 840dp instead of stretching it across the whole window. Inside primary, an aspect check
        // puts the status/result column BESIDE the board (Row) when the window is wider than tall,
        // above it (Column) otherwise; in both the board's slot is a REAL bounded space (what is
        // left after the status), which is what fitBoard measures.
        AdaptiveTwoPane(
            foldState = LocalFoldState.current,
            secondary = null,
            primary = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        if (maxWidth > maxHeight) {
                            Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                Column(
                                    modifier = Modifier
                                        .widthIn(max = 220.dp)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    if (showResult) {
                                        MancalaResultPanel(
                                            title = resultTitle,
                                            stonesLine = stonesLine,
                                            sessionLine = resultSessionLine,
                                            stackButtons = true,
                                            reducedMotion = reducedMotion,
                                            onPlayAgain = game::playAgain,
                                            onLeave = game::leaveSession,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        MancalaStatus(
                                            prompt = prompt,
                                            summary = summary,
                                            detailLines = detailLines,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                                // The corner menu button sits over the top 56dp of the screen's
                                // end; keep the board's slot below it (16dp is already padding).
                                boardBlock(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(top = GameChromeEndInset - 16.dp)
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // End inset: the corner menu button overlays the top-right.
                                MancalaStatus(
                                    prompt = prompt,
                                    summary = summary,
                                    detailLines = detailLines,
                                    modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset)
                                )
                                Spacer(Modifier.height(16.dp))
                                boardBlock(Modifier.weight(1f).fillMaxWidth())
                            }
                            if (showResult) {
                                MancalaResultPanel(
                                    title = resultTitle,
                                    stonesLine = stonesLine,
                                    sessionLine = resultSessionLine,
                                    stackButtons = false,
                                    reducedMotion = reducedMotion,
                                    onPlayAgain = game::playAgain,
                                    onLeave = game::leaveSession,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(16.dp)
                                        .widthIn(max = 420.dp)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    }

                    // The seed-hop cascade itself: one small seed per hop, each easing from its
                    // origin pit's captured center to its destination pit's, with a parabolic arc
                    // (same shape as UnoScreen's FlyingCardOverlay). Progress and positions are
                    // read inside each seed's graphicsLayer, not in composition.
                    if (sowPathForAnim.size > 1) {
                        sowPathForAnim.zipWithNext().forEachIndexed { i, (fromPit, toPit) ->
                            SeedHop(
                                progress = { hopAnimations.getOrNull(i)?.value ?: 0f },
                                from = { pitPositions[fromPit] ?: Offset.Zero },
                                to = { pitPositions[toPit] ?: Offset.Zero },
                                origin = { overlayOrigin }
                            )
                        }
                    }

                    // Capture flourish: the landing pit's and the opposite pit's seeds both sweep
                    // into the mover's store (pit <= 5 is player 0's side -> store 6, see
                    // MancalaGame's board-layout KDoc).
                    captureForAnim?.let { capture ->
                        val store = if (capture.landingPit <= 5) 6 else 13
                        SeedHop(
                            progress = { captureLandingAnim.value },
                            from = { pitPositions[capture.landingPit] ?: Offset.Zero },
                            to = { pitPositions[store] ?: Offset.Zero },
                            origin = { overlayOrigin },
                            arcHeight = CAPTURE_ARC_HEIGHT
                        )
                        SeedHop(
                            progress = { captureOppositeAnim.value },
                            from = { pitPositions[capture.oppositePit] ?: Offset.Zero },
                            to = { pitPositions[store] ?: Offset.Zero },
                            origin = { overlayOrigin },
                            arcHeight = CAPTURE_ARC_HEIGHT
                        )
                    }
                }
            }
        )
    }
}

/** This node's on-screen center in root coordinates -- see the seed-hop position tracking above. */
private fun androidx.compose.ui.layout.LayoutCoordinates.pitCenter(): Offset =
    positionInRoot() + Offset(size.width / 2f, size.height / 2f)

// ---- Pure text helpers (no Compose) -------------------------------------------------------

/** Board index of [playerIndex]'s store (see MancalaGame's board-layout KDoc). */
private fun storeIndexOf(playerIndex: Int): Int = if (playerIndex == 0) 6 else 13

private fun possessive(name: String): String = if (name.equals("You", ignoreCase = true)) "Your" else "$name's"

private fun winTitle(name: String): String = if (name.equals("You", ignoreCase = true)) "You win!" else "$name wins!"

/**
 * Who owns a row/store, for screen-reader labels. Against the CPU it is "Your" / "Opponent" (the
 * wording the instrumented rotation test looks for); in pass-and-play both sides are named, since
 * "Your" would be wrong for one of two people sharing the screen.
 */
private fun ownerLabelFor(index: Int, players: List<PlayerInfo>, hasBot: Boolean): String {
    val player = players.getOrNull(index)
    return if (hasBot) {
        if (player?.isBot == true) "Opponent" else "Your"
    } else {
        possessive(player?.displayName ?: "Player ${index + 1}")
    }
}

/**
 * The one prompt line: names the mover (pass-and-play shows two humans the same screen) and any
 * owed action. [extraTurn] is a sow that ended in the mover's own store.
 */
private fun turnPrompt(mover: PlayerInfo?, hasBot: Boolean, extraTurn: Boolean, roundOver: Boolean): String {
    if (roundOver) return "Game over"
    val name = mover?.displayName ?: "Opponent"
    return if (mover?.isBot == true) {
        if (extraTurn) "$name sows again…" else "$name is thinking…"
    } else {
        val who = if (hasBot) "Your" else possessive(name)
        if (extraTurn) "$who extra turn — sow again" else "$who turn — tap a pit to sow"
    }
}

/**
 * The last move in the UI's own numbering. The engine's `lastAction` counts player 1's pits
 * 8..13 (its absolute board index plus one), which would not match the 1..6 labels on screen, so
 * this rebuilds the sentence from the sow path and capture instead. Falls back to the engine's
 * text for the initial deal.
 */
private fun moveSummary(s: MancalaState, players: List<PlayerInfo>): String {
    val firstPit = s.lastSowPath.firstOrNull() ?: return s.lastAction
    val moverIndex = if (firstPit <= 5) 0 else 1
    val name = players.getOrNull(moverIndex)?.displayName ?: "Player ${moverIndex + 1}"
    val pitNumber = if (moverIndex == 0) firstPit + 1 else firstPit - 6
    val captured = s.lastCapture?.let { " and captured ${it.totalSwept}" } ?: ""
    return "$name sowed from pit $pitNumber$captured"
}

private fun sessionLine(p0Name: String, p1Name: String, wins0: Int, wins1: Int, draws: Int): String =
    buildString {
        append("Session: $p0Name $wins0 · $p1Name $wins1")
        if (draws > 0) append(" · Draws $draws")
    }

// ---- Status + result ---------------------------------------------------------------------

@Composable
private fun MancalaStatus(
    prompt: String,
    summary: String,
    detailLines: List<String>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        // liveRegion: the prompt changes every turn ("Your turn", "CPU is thinking…", extra turn),
        // which a screen-reader user otherwise has no way to notice.
        Text(
            prompt,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Text(summary, style = MaterialTheme.typography.bodySmall)
        detailLines.forEach { line ->
            Text(line, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The finished-game panel: result title, final store counts, session tally, Play Again / Back to
 * Menu. Fades in over 250ms (instant with Reduced Motion). [stackButtons] stacks the buttons for
 * the narrow side column of the landscape layout.
 */
@Composable
private fun MancalaResultPanel(
    title: String,
    stonesLine: String,
    sessionLine: String,
    stackButtons: Boolean,
    reducedMotion: Boolean,
    onPlayAgain: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fade = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) fade.animateTo(1f, animationSpec = tween(RESULT_FADE_MS))
    }
    Card(modifier = modifier.graphicsLayer { alpha = fade.value }) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            Text(stonesLine, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(sessionLine, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            if (stackButtons) {
                Button(
                    onClick = onPlayAgain,
                    modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Play Again") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onLeave,
                    modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Back to Menu") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPlayAgain,
                        modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                    ) { Text("Play Again") }
                    OutlinedButton(
                        onClick = onLeave,
                        modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                    ) { Text("Back to Menu") }
                }
            }
        }
    }
}

// ---- Layout + motion constants -----------------------------------------------------------

/**
 * fitBoard grid, in units `u` (a quarter of a pit's pitch): pit slot 4u x 5u, store 3u x 10u, so
 * the playing area is 6 pits + 2 stores = 30u wide and two pit rows = 10u tall.
 */
private const val BOARD_COLUMNS = 30
private const val BOARD_ROWS = 10

/** Wooden slab padding around the playing area; also fitBoard's frame. */
private val SLAB_PADDING = 8.dp

/** Pit pitch is 4u, so 7dp is the 28dp "do not shrink below this, scroll instead" floor. */
private const val MIN_UNIT_DP = 7f

/** Pit pitch 96dp at most, so the board stays a sensible size on a tablet. */
private const val MAX_UNIT_DP = 24f

private const val HOP_DURATION_MS = 220
// ~70% overlap between consecutive hops (65-75% target): the next hop starts only 30% of the
// way through the current one's flight instead of waiting for it to land.
private const val HOP_STAGGER_MS = 66L
private const val CAPTURE_HOP_DURATION_MS = 320
private val CAPTURE_ARC_HEIGHT = 34.dp
private val SOW_ARC_HEIGHT = 19.dp
private val SEED_SIZE = 12.dp
// 1-2 frames at 60fps (~16.6ms each) -- a genuine freeze-frame, not a perceptible pause.
private const val HIT_STOP_MS = 32L
private const val BIG_CAPTURE_STONE_THRESHOLD = 6

/** Camera-shake impulses in dp (the old px pattern, 14/10/7/4, at the ~2.6x density it was tuned on). */
private val SHAKE_PATTERN_DP = listOf(-5f, 4f, -3f, 1.5f, 0f)

private const val MIN_RESULT_HOLD_MS = 600L
private const val MAX_RESULT_HOLD_MS = 2500L
private const val RESULT_FADE_MS = 250

/**
 * One small seed traveling from [from] to [to] (root-coordinate pit/store centers, see
 * pitCenter()) as [progress] runs 0f -> 1f, on a parabolic arc -- the same eased-lerp-plus-arc
 * shape UnoScreen's FlyingCardOverlay uses, scaled down to a quick pit-to-pit hop. Invisible at
 * rest (progress <= 0f, not yet started) and once landed (progress >= 1f) -- the pit's own count
 * already reflects the real, final board the instant sow() returns, so the hopping seed is purely
 * decorative. [origin] is the root position of the overlay this is drawn in. Everything is read
 * inside graphicsLayer, so a frame of the hop never recomposes anything.
 */
@Composable
private fun SeedHop(
    progress: () -> Float,
    from: () -> Offset,
    to: () -> Offset,
    origin: () -> Offset,
    arcHeight: Dp = SOW_ARC_HEIGHT
) {
    Box(
        modifier = Modifier
            .size(SEED_SIZE)
            .graphicsLayer {
                val p = progress()
                if (p <= 0f || p >= 1f) {
                    alpha = 0f
                } else {
                    val a = from()
                    val b = to()
                    val o = origin()
                    val eased = 1f - (1f - p) * (1f - p)
                    // A small upward arc peaking at the midpoint.
                    val arc = -arcHeight.toPx() * 4f * eased * (1f - eased)
                    val half = SEED_SIZE.toPx() / 2f
                    translationX = a.x + (b.x - a.x) * eased - o.x - half
                    translationY = a.y + (b.y - a.y) * eased + arc - o.y - half
                }
            }
            .clip(CircleShape)
            .background(Color(0xFF4E342E))
    )
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
 * (see SeedPhysicsOverlay) maps this normalized space onto that slot's own actual pixel size,
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
 * normalized simulation space onto this composable's actual rendered size. [physicsFrame] is
 * read INSIDE the draw lambda: [MancalaSeedPhysics] is plain mutable state outside Compose's
 * snapshot system, so the tick is what makes the draw redo every frame -- and because it is read
 * at draw time, a tick redraws these dots without recomposing the board.
 */
@Composable
private fun SeedPhysicsOverlay(physics: MancalaSeedPhysics, slotIndex: Int, physicsFrame: State<Long>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Draw-phase read of the tick: this Canvas redraws every frame without recomposing.
        // The annotation sits on its own line so it unambiguously covers the whole statement.
        @Suppress("UNUSED_EXPRESSION")
        physicsFrame.value
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

// ---- Carved-wood board identity ----------------------------------------------------------

private val WOOD_HIGHLIGHT = Color(0xFFC9986A)
private val WOOD_MID = Color(0xFF8D6238)
private val WOOD_DEEP = Color(0xFF4E3220)

/** Count digits: dark ink on the lit wood centre (about 6.6:1; about 5:1 on a dimmed pit). */
private val DIGIT_INK = Color(0xFF2A190C)

/** How much an unplayable pit is darkened -- kept low so the ink above stays above 4.5:1. */
private const val DIM_ALPHA = 0.14f

private val SlabShape = RoundedCornerShape(16.dp)
private val SlabBrush = Brush.verticalGradient(listOf(Color(0xFF7A5230), Color(0xFF5E3F24)))

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
 * A cached procedural wood-grain + radial shading baseline: a radial gradient lit at the centre
 * (where the count digit sits) and darker toward the rim, plus a handful of thin curved "grain"
 * strokes. [seed] gives each pit/store its own fixed-but-distinct grain (see [generateGrain]) so
 * the whole board doesn't look like one repeated stamp. [dim] slightly darkens the surface for a
 * currently-unplayable pit. [tall] stretches the lit region along a store's long axis so a
 * two-digit count still sits on lit wood. The brush and stroke are built once per size
 * (drawWithCache), not per frame.
 */
@Composable
private fun Modifier.mancalaWoodSurface(seed: Int, dim: Boolean, tall: Boolean = false): Modifier {
    val grain = remember(seed) { generateGrain(seed) }
    return this.drawWithCache {
        val radius = ((if (tall) size.height else size.minDimension) / 2f).coerceAtLeast(1f)
        val brush = Brush.radialGradient(
            colors = listOf(WOOD_HIGHLIGHT, WOOD_MID, WOOD_DEEP),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = radius
        )
        val grainStroke = Stroke(width = 0.7.dp.toPx())
        onDrawBehind {
            drawRect(brush)
            if (dim) drawRect(Color.Black.copy(alpha = DIM_ALPHA))
            grain.forEach { g ->
                rotate(g.rotationDeg, pivot = Offset(size.width * 0.5f, size.height * 0.5f)) {
                    drawOval(
                        color = Color.Black.copy(alpha = 0.14f),
                        topLeft = Offset(size.width * (g.cx - g.rx / 2f), size.height * (g.cy - g.ry / 2f)),
                        size = Size(size.width * g.rx, size.height * g.ry),
                        style = grainStroke
                    )
                }
            }
        }
    }
}

/**
 * One pit. The whole 4u x 5u slot is the button (a bigger target than the drawn 3.5u circle);
 * the circle is centered in it. Described as "<owner> pit N, K stones" with a state description;
 * the digit itself is hidden from semantics so it is not read twice.
 */
@Composable
private fun PitView(
    count: Int,
    enabled: Boolean,
    ownerLabel: String,
    pitNumber: Int,
    highlightCapture: Boolean,
    unit: Dp,
    card3D: Boolean,
    jostleActive: Boolean,
    physics: MancalaSeedPhysics,
    slotIndex: Int,
    physicsFrame: State<Long>,
    onClick: () -> Unit
) {
    // Settings -> Accessibility -> Reduced Motion: swap the spring for snap() so the capture
    // shrink/settle still happens, just without the motion.
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val scale by animateFloatAsState(
        targetValue = if (count > 0) 1f else 0.85f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pitScale"
    )
    val density = LocalDensity.current
    // Fixed physical size (not scaled by the user's font size): a 2-digit count has to fit the pit.
    val digitSize = with(density) { (unit * 1.5f).toSp() }
    val description = "$ownerLabel pit $pitNumber, ${stoneCountLabel(count)}"
    val stateText = when {
        enabled && highlightCapture -> "Playable, would capture"
        enabled -> "Playable"
        count == 0 -> "Empty"
        else -> "Not playable now"
    }
    Box(
        modifier = Modifier
            .size(width = unit * 4, height = unit * 5)
            // Rounded so the press ripple is a soft pad over the slot, not a hard rectangle.
            .clip(RoundedCornerShape(unit * 1.5f))
            .clickable(enabled = enabled, onClickLabel = "Sow from pit $pitNumber", role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            // Screen-reader announcement mirrors the visible layout (owner side + this pit's
            // position within that side, farthest-from-store first) plus the live stone count.
            .semantics {
                contentDescription = description
                stateDescription = stateText
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(unit * 3.5f)
                .scale(scale)
                .clip(CircleShape)
                .mancalaWoodSurface(seed = slotIndex, dim = !enabled)
                .specularSweep(enabled = card3D, tint = Color(0xFFFFE9BE).copy(alpha = 0.45f))
                .border(
                    if (highlightCapture) (if (colorblind) 4.dp else 3.dp) else 1.dp,
                    if (highlightCapture) Color(0xFFFFC107) else Color(0xFF3E2A18),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (jostleActive) {
                SeedPhysicsOverlay(physics = physics, slotIndex = slotIndex, physicsFrame = physicsFrame)
            }
            Text(
                count.toString(),
                fontWeight = FontWeight.Bold,
                color = DIGIT_INK,
                fontSize = digitSize,
                modifier = Modifier.clearAndSetSemantics {}
            )
            if (highlightCapture && colorblind) {
                // Non-color cue for the gold capture ring (decorative: the state description
                // already says "would capture").
                Text(
                    "★",
                    color = DIGIT_INK,
                    fontSize = with(density) { unit.toSp() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = unit * 0.2f)
                        .clearAndSetSemantics {}
                )
            }
        }
    }
}

/** One store: 3u x 10u slot with a quarter-unit wooden margin; not interactive. */
@Composable
private fun StoreView(
    count: Int,
    ownerLabel: String,
    unit: Dp,
    card3D: Boolean,
    jostleActive: Boolean,
    physics: MancalaSeedPhysics,
    slotIndex: Int,
    physicsFrame: State<Long>
) {
    val density = LocalDensity.current
    val digitSize = with(density) { (unit * 1.5f).toSp() }
    Box(
        modifier = Modifier
            .size(width = unit * 3, height = unit * 10)
            .padding(unit * 0.25f)
            .clip(RoundedCornerShape(unit * 1.2f))
            .mancalaWoodSurface(seed = slotIndex, dim = false, tall = true)
            .specularSweep(enabled = card3D, tint = Color(0xFFFFE9BE).copy(alpha = 0.4f))
            .semantics { contentDescription = "$ownerLabel store, ${stoneCountLabel(count)}" },
        contentAlignment = Alignment.Center
    ) {
        if (jostleActive) {
            SeedPhysicsOverlay(physics = physics, slotIndex = slotIndex, physicsFrame = physicsFrame)
        }
        Text(
            count.toString(),
            color = DIGIT_INK,
            fontWeight = FontWeight.Bold,
            fontSize = digitSize,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** "1 stone" vs "4 stones" -- the pit/store semantics text reads naturally either way. */
private fun stoneCountLabel(count: Int): String = if (count == 1) "1 stone" else "$count stones"
