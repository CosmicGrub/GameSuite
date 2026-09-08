package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.card3DFlip
import com.gamesuite.games.checkers.CheckersGame
import com.gamesuite.games.checkers.CheckersMotionTier
import com.gamesuite.games.checkers.CheckersPiece
import com.gamesuite.games.checkers.CheckersPrefsStore
import com.gamesuite.games.checkers.CheckersState
import com.gamesuite.games.checkers.PieceKind
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Shape mirrors MancalaScreen exactly (see that file's KDoc for the settings
 * wiring rationale): two LaunchedEffects (session init; bot-turn timing),
 * early-return guards, a plain-Column round-over panel, self-contained board
 * composables below with no shared board/widget library.
 *
 * Piece movement (including a whole multi-jump chain, which the engine
 * commits as a single overall move -- see CheckersMove's KDoc) is animated
 * rather than an instant jump: each occupied square's piece is rendered in a
 * `key(id)`-scoped [PieceView] slot (ids from [deriveIds]) so the same
 * composable instance persists across the move, sliding from its old offset
 * to its new one.
 *
 * ANIMATION/PHYSICS PITCH ADDITIONS (Checkers section):
 *  - [PieceView]'s move is a single [Animatable]<Float> `progress` (not two
 *    independent position tweens) so a real lift/scale/shadow "hop" can be
 *    layered on top of the same x/y glide, gated behind `enhanced`
 *    ([LocalEnhancedAnimations] && ![LocalReducedMotion]).
 *  - A captured piece no longer vanishes the instant its square goes null:
 *    [capturedGhostsFor] reconstructs which square(s) were captured this
 *    turn -- and, for a bot's compound multi-jump chain, in which order --
 *    from [CheckersState.lastTurnHops] (see that field's own KDoc), and
 *    [CapturedGhostView] keeps rendering each one until the shared move
 *    `progress` timeline actually reaches that hop's segment, so a 3-jump
 *    chain visibly loses its captured pieces one at a time as the mover
 *    passes each one, not all at once at the start. The per-hop TIMING is a
 *    correctness fix and always applies (the ghost still holds until its
 *    segment, then simply cuts instead of fading); the fade itself is the
 *    `enhanced`-gated flourish. Both fall back to today's plain instant-swap
 *    (single glide, no lift, no lingering captures) behavior whenever
 *    reduced motion is on.
 *
 * PREMIUM-2026-VISION PASS ADDITIONS (Checkers section) -- see
 * [CheckersMotionTier] for the new per-game Standard/Maximum/Off setting
 * that gates everything below beyond `enhanced`/`card3D`'s existing reach:
 *  - Squares are no longer a flat literal [Color] fill: [rememberWoodGrainTile]
 *    procedurally draws a warm-brown/honey-tan wood-grain baseline ONCE per
 *    composition into a cached [ImageBitmap] (never per-frame), stretched
 *    onto every dark/light square respectively, with a felt-green surround
 *    framing the 8x8 grid -- this baseline renders identically (just without
 *    the animated highlight) on every device, per
 *    [com.gamesuite.ui.effects.PremiumShaders]' own documented contract.
 *    [com.gamesuite.ui.effects.specularSweep] layers a moving specular
 *    highlight on top of that baseline for squares AND pieces (a "lacquered"
 *    sheen), plus the king glyph gets its own distinct metallic-gold
 *    treatment -- all three reserved for [CheckersMotionTier.MAXIMUM] (see
 *    `maximum` below), NOT the shared [LocalCard3DMode] gate every other
 *    specularSweep consumer in this project uses, since this game's own
 *    tier setting is the more specific, explicitly-requested gate here.
 *  - A genuine hit-stop (a brief freeze of the shared move-progress clock)
 *    plus a small decaying jittered camera-shake on the board container,
 *    both reserved for a capturing hop only -- see the `moveProgress`
 *    [LaunchedEffect] below -- never fires on a plain move.
 *  - A full haptic vocabulary via [com.gamesuite.haptics.rememberHaptics]:
 *    LIGHT_TICK on selecting a piece, NORMAL_ACTION on a quiet move,
 *    STRONG_ACTION on a capture, ESCALATING on a promotion, SUCCESS/FAILURE
 *    on the round ending -- all under `maximum` too. Scoped to the human's
 *    own taps in [onSquareTapped] only, mirroring how `sounds.playTap()`
 *    already only ever fires there and never for [CheckersGame.playBotTurn].
 *  - A round-over transition instead of a hard cut: the board plays a brief
 *    bounce-pulse on the winner's surviving pieces plus an overall fade/
 *    scale-down (`roundEndProgress` below) before the plain results Column
 *    swaps in -- gated on the tier being anything but [CheckersMotionTier.OFF]
 *    (same reach as `enhanced`/`card3D`, not `maximum`-only, since this is
 *    baseline round-transition polish rather than a top-tier flourish).
 */
@Composable
fun CheckersScreen(
    sessionManager: GameSessionManager,
    game: CheckersGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current

    // Checkers' own 3-tier motion setting (see CheckersMotionTier's KDoc) --
    // composes with, rather than replaces, the existing global gates: a
    // system-wide Reduced Motion always wins (forces OFF regardless of what
    // the player picked here, mirroring every other screen's own collapse
    // rule), and STANDARD/MAXIMUM still both defer to LocalCard3DMode /
    // LocalEnhancedAnimations for whether the ALREADY-shipped lift/hop/
    // promotion-flip motion plays at all -- this tier only ever narrows that
    // reach further (OFF) or extends it with new premium flourishes
    // (MAXIMUM), never widens past what those global settings already allow.
    val prefsStore = remember { CheckersPrefsStore(androidContext) }
    val motionTierPref by prefsStore.motionTier.collectAsState(initial = CheckersMotionTier.STANDARD)
    val effectiveTier = if (reducedMotion) CheckersMotionTier.OFF else motionTierPref
    val card3D = LocalCard3DMode.current && effectiveTier != CheckersMotionTier.OFF
    val enhanced = LocalEnhancedAnimations.current && effectiveTier != CheckersMotionTier.OFF
    val maximum = effectiveTier == CheckersMotionTier.MAXIMUM
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    // Audio infra wiring (audio pass): ambient music respects BOTH the
    // ambient-music setting AND the master sound toggle, same AND pattern
    // every other feature-specific gate in this codebase already follows.
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CHECKERS, enabled = musicEnabled)
    val playSfx = rememberProceduralSfx()

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

    // Keyed on the whole state object so a forced-continuation hop (which keeps
    // currentPlayerIndex on the same side) still relaunches -- every hop always
    // changes board/lastMove, so a new CheckersState is never equal to the last.
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        val ctx = context ?: return@LaunchedEffect
        if (s.gameOver) return@LaunchedEffect
        if (ctx.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(700)
            game.playBotTurn()
            // Real SFX gap: the bot's own move was completely silent before —
            // sounds.playTap() only ever fires from the human's own tap in
            // onSquareTapped (see this file's own KDoc). Mirror the same plain-
            // move/capture distinction added to the human path below so a bot's
            // capture reads as a distinct, weightier landing too.
            val wasCapture = game.state.value?.lastMove?.isCapture == true
            playSfx(if (wasCapture) SfxKind.SOLID_THUNK else SfxKind.LIGHT_TICK)
        }
    }

    val s = state ?: return
    val ctx = context ?: return

    // Round-over transition (premium-2026-vision pass): rather than an
    // instant hard cut to the results Column the moment s.gameOver flips
    // true, the board itself plays a brief bounce-pulse on the winner's
    // pieces plus a fade/scale-down first -- see this file's own KDoc.
    // `showResultsScreen` only flips once that transition (or, at OFF tier,
    // nothing at all) has finished, so the early-return below stays hidden
    // until then and the normal interactive-board code path underneath
    // renders in the meantime (with taps disabled -- see onSquareTapped).
    var showResultsScreen by remember { mutableStateOf(false) }
    val roundEndProgress = remember { Animatable(0f) }
    val winnerSide = remember(s.winnerPlayerId) { ctx.players.indexOfFirst { it.playerId == s.winnerPlayerId }.takeIf { it >= 0 } }
    LaunchedEffect(s.gameOver) {
        if (s.gameOver) {
            val winnerIsBot = ctx.players.firstOrNull { it.playerId == s.winnerPlayerId }?.isBot == true
            // Real SFX gap: round end had no sound of its own at any motion tier —
            // only `maximum`'s haptic just below. This fires regardless of tier,
            // mirroring the same human-perspective win/lose framing that haptic
            // already uses.
            playSfx(if (winnerIsBot) SfxKind.INVALID_BUZZ else SfxKind.SUCCESS_CHIME)
            if (maximum) {
                haptics(if (winnerIsBot) HapticSignal.FAILURE else HapticSignal.SUCCESS)
            }
            if (effectiveTier == CheckersMotionTier.OFF) {
                roundEndProgress.snapTo(1f)
            } else {
                roundEndProgress.snapTo(0f)
                roundEndProgress.animateTo(1f, animationSpec = tween(ROUND_END_TRANSITION_MS))
            }
            showResultsScreen = true
        } else {
            roundEndProgress.snapTo(0f)
            showResultsScreen = false
        }
    }

    if (s.gameOver && showResultsScreen) {
        val winner = ctx.players.firstOrNull { it.playerId == s.winnerPlayerId }
        val p1Name = ctx.players.getOrNull(0)?.displayName ?: "Player 1"
        val p2Name = ctx.players.getOrNull(1)?.displayName ?: "Player 2"
        val scoreP1 by game.scoreP1
        val scoreP2 by game.scoreP2
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(if (winner != null) "${winner.displayName} wins!" else "Game over", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("$p1Name: $scoreP1 · $p2Name: $scoreP2", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = game::playAgain) { Text("Play Again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
        }
        return
    }

    val isHumanTurn = !s.gameOver && ctx.players.getOrNull(s.currentPlayerIndex)?.isBot != true

    // User-picked source square. Overridden by the forced-continuation square
    // whenever one is active (that piece MUST jump again -- the player can't
    // deselect it or pick another), so this only ever matters when there's no
    // forced continuation. Cleared on every turn change / new round.
    var userSelected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(s.currentPlayerIndex, s.gameOver, s.lastMove == null) { userSelected = null }
    val selected = if (s.inForcedContinuation) s.forcedRow to s.forcedCol else userSelected

    val legalDestinations = selected?.let { (r, c) -> game.legalDestinationsFrom(r, c) } ?: emptySet()

    fun onSquareTapped(row: Int, col: Int) {
        if (!isHumanTurn || s.gameOver) return
        if (selected != null && (row to col) in legalDestinations) {
            val fromKind = s.pieceAt(selected.first, selected.second)?.kind
            game.playMove(s.currentPlayerIndex, selected.first, selected.second, row, col)
            sounds.playTap()
            // Hoisted out of the `maximum`-only block below so the new procedural
            // SFX gap fill (a capture landing genuinely sounds different from a
            // plain move) can read it too — sound identity isn't a `maximum`-tier
            // flourish the way the haptic escalation below is.
            val justCommitted = game.state.value
            val landedKind = justCommitted?.pieceAt(row, col)?.kind
            val wasCapture = justCommitted?.lastMove?.isCapture == true
            // Real SFX gap: sounds.playTap() above is the same generic tap for
            // every valid move today, with nothing distinguishing a satisfying
            // capture landing from a quiet plain slide.
            playSfx(if (wasCapture) SfxKind.SOLID_THUNK else SfxKind.LIGHT_TICK)
            if (maximum) {
                when {
                    fromKind == PieceKind.MAN && landedKind == PieceKind.KING -> haptics(HapticSignal.ESCALATING)
                    wasCapture -> haptics(HapticSignal.STRONG_ACTION)
                    else -> haptics(HapticSignal.NORMAL_ACTION)
                }
            }
            userSelected = null
            return
        }
        if (s.inForcedContinuation) return // must play the forced jump, can't pick a different square
        val canSelect = s.pieceAt(row, col)?.owner == s.currentPlayerIndex && game.hasLegalMoveFrom(row, col)
        val newSelection = if (canSelect) {
            if (userSelected == (row to col)) null else row to col
        } else {
            null
        }
        // Real SFX gap: selecting a piece was silent at every motion tier — only
        // `maximum`'s LIGHT_TICK haptic just below acknowledged it.
        if (newSelection != null) playSfx(SfxKind.LIGHT_TICK)
        if (maximum && newSelection != null) haptics(HapticSignal.LIGHT_TICK)
        userSelected = newSelection
    }

    // --- Derived board/animation state, hoisted above the portrait/landscape branch below so
    // these Animatable/remember instances survive an aspect-ratio flip (rotating the device, or
    // folding/unfolding the Fold 5 mid-move) instead of being torn down and restarted just
    // because the chrome+board arrangement switched from a Column to a Row. ---
    val darkWoodTile = rememberWoodGrainTile(
        base = Color(0xFF6D4C37), grain = Color(0xFF3E2723), highlight = Color(0xFF8D6C52), seed = 1
    )
    val lightWoodTile = rememberWoodGrainTile(
        base = Color(0xFFE3C79E), grain = Color(0xFFC19A6B), highlight = Color(0xFFFBEEDA), seed = 2
    )
    val moveProgress = remember(s) { Animatable(if (reducedMotion || effectiveTier == CheckersMotionTier.OFF) 1f else 0f) }
    val cameraShake = remember { Animatable(0f) }
    LaunchedEffect(s) {
        if (reducedMotion || effectiveTier == CheckersMotionTier.OFF) return@LaunchedEffect
        val hops = s.lastTurnHops
        val captureMidpoints = if (maximum && hops.isNotEmpty()) {
            hops.indices.filter { hops[it].isCapture }.map { (it + 0.5f) / hops.size }.sorted()
        } else {
            emptyList()
        }
        var from = 0f
        for (mid in captureMidpoints) {
            if (mid > from) {
                moveProgress.animateTo(mid, animationSpec = tween(((mid - from) * MOVE_DURATION_MS).roundToInt().coerceAtLeast(1)))
            }
            launch {
                cameraShake.snapTo(1f)
                cameraShake.animateTo(0f, animationSpec = tween(CAMERA_SHAKE_DECAY_MS))
            }
            delay(HIT_STOP_MS)
            from = mid
        }
        if (from < 1f) {
            moveProgress.animateTo(1f, animationSpec = tween(((1f - from) * MOVE_DURATION_MS).roundToInt().coerceAtLeast(1)))
        }
    }
    var previousIds by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    val boardMemory = remember { CheckersBoardMemory() }
    val derived = remember(s) {
        val ghosts = capturedGhostsFor(boardMemory.previous, s)
        previousIds = deriveIds(previousIds, s)
        boardMemory.previous = s.board
        CheckersMoveDerived(previousIds, ghosts)
    }
    val pieceIds = derived.ids
    val capturedGhosts = derived.ghosts

    // Turn status / difficulty / motion-tier picker / captured-piece tray -- reused as-is by
    // both the portrait (stacked above the board) and landscape (beside the board) arrangements
    // below, so this chrome is never the thing silently eating the vertical budget a short
    // window (e.g. the Fold 5 cover screen rotated to landscape, ~344dp tall) needs for the
    // board itself.
    val chromeBlock: @Composable (Modifier) -> Unit = { chromeModifier ->
        Column(modifier = chromeModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    !isHumanTurn -> "Opponent's turn"
                    s.inForcedContinuation -> "Capture again with the same piece"
                    else -> "Your turn — tap a piece, then a highlighted square"
                },
                style = MaterialTheme.typography.titleMedium
            )
            if (ctx.players.any { it.isBot }) {
                Text(
                    "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(4.dp))

            // Visible control for CheckersMotionTier (premium-2026-vision pass) --
            // same FilterChip-row shape as SettingsScreen's own DifficultySelector,
            // kept on this screen (not Settings) since it's this one game's own
            // rule/presentation choice, mirroring SolitaireScreen's draw-1/3 toggle.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CheckersMotionTier.entries.forEach { tier ->
                    FilterChip(
                        selected = motionTierPref == tier,
                        onClick = { scope.launch { prefsStore.setMotionTier(tier) } },
                        label = { Text(tier.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Derived purely from s.board (12 starting men per side, per startMatch()) --
            // no engine changes needed, matching how legalDestinations/isHumanTurn above
            // are also just read off the existing CheckersState.
            CapturedPieceTray(
                darkCaptured = STARTING_PIECES_PER_SIDE - s.board.count { it?.owner == 0 },
                lightCaptured = STARTING_PIECES_PER_SIDE - s.board.count { it?.owner == 1 },
                modifier = Modifier.widthIn(max = 480.dp)
            )
        }
    }

    // The board itself. Sized from BOTH the available width AND height (never width alone --
    // see this file's own bundle notes on the CRITICAL SIZING PRINCIPLE), with cellSize floored
    // to MIN_TOUCH_TARGET so an 8x8 grid never produces an untappable square regardless of how
    // little space is actually available.
    val boardBlock: @Composable (Modifier) -> Unit = { boardModifier ->
        BoxWithConstraints(modifier = boardModifier, contentAlignment = Alignment.Center) {
            val feltPadding = 14.dp
            val rawCellSize = (minOf(maxWidth, maxHeight).coerceAtMost(480.dp) - feltPadding * 2) / 8
            val cellSize = rawCellSize.coerceAtLeast(MIN_TOUCH_TARGET)
            val gridSize = cellSize * 8
            val boardSize = gridSize + feltPadding * 2

            // Felt-green surround (premium-2026-vision pass): a static, matte
            // frame around the actual 8x8 grid -- deliberately NOT given a
            // specularSweep of its own (felt is matte cloth; a moving shine
            // would read as the wrong material entirely, unlike the lacquered
            // wood squares/pieces it frames).
            Box(
                modifier = Modifier
                    .size(boardSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FELT_SURROUND_BRUSH)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(gridSize)
                        .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
                        .then(
                            if (maximum) Modifier.graphicsLayer {
                                val shake = cameraShake.value
                                val jitterPx = 4.dp.toPx()
                                translationX = jitterPx * shake * kotlin.math.sin(shake * 47f)
                                translationY = jitterPx * 0.6f * shake * kotlin.math.cos(shake * 39f)
                            } else Modifier
                        )
                        .then(
                            if (effectiveTier != CheckersMotionTier.OFF) Modifier.graphicsLayer {
                                val p = roundEndProgress.value
                                scaleX = 1f - 0.12f * p
                                scaleY = 1f - 0.12f * p
                                alpha = 1f - 0.55f * p
                            } else Modifier
                        )
                ) {
                    // Squares (background grid): dark squares only ever hold pieces;
                    // light squares are always empty, matching CheckersLogic.h's KDoc.
                    for (row in 0..7) for (col in 0..7) {
                        val isDark = (row + col) % 2 == 1
                        val isDestination = (row to col) in legalDestinations
                        val isSelected = selected == (row to col)
                        Box(
                            modifier = Modifier
                                .offset(x = cellSize * col, y = cellSize * row)
                                .size(cellSize)
                                .drawBehind {
                                    val tile = if (isDark) darkWoodTile else lightWoodTile
                                    drawImage(image = tile, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                                }
                                .then(
                                    if (maximum) {
                                        Modifier.specularSweep(
                                            enabled = true,
                                            tint = if (isDark) DARK_SQUARE_SHEEN else LIGHT_SQUARE_SHEEN,
                                            periodMs = 4200
                                        )
                                    } else Modifier
                                )
                                .then(
                                    if (isSelected || isDestination) {
                                        Modifier.border(3.dp, if (isSelected) Color(0xFFFFC107) else Color(0xFF8BC34A))
                                    } else Modifier
                                )
                                .clickable(enabled = isDark) { onSquareTapped(row, col) }
                                .semantics { contentDescription = squareDescription(s, row, col, isDestination) }
                        )
                    }

                    // Pieces (animated overlay layer) -- one composable per stable id,
                    // so its progress Animatable interpolates across recompositions.
                    for ((id, square) in pieceIds) {
                        val row = square / 8
                        val col = square % 8
                        // The moving piece this turn is exactly the one now sitting on
                        // s.lastMove's destination square (true for both a single real
                        // hop and a bot's collapsed whole-chain summary -- see
                        // CheckersMove's KDoc); every other piece's "from" square is
                        // just its own current square, so its progress-driven glide
                        // below is a no-op and its lift/scale/shadow flourish never
                        // triggers (see PieceView's own isMoving check).
                        val mv = s.lastMove
                        val isMover = mv != null && row == mv.toRow && col == mv.toCol
                        val fromRow = if (isMover) mv!!.fromRow else row
                        val fromCol = if (isMover) mv!!.fromCol else col
                        val piece = s.pieceAt(row, col)!!
                        val isWinnerPiece = s.gameOver && winnerSide != null && piece.owner == winnerSide
                        key(id) {
                            // deriveIds only ever returns squares s.board still has a piece on
                            // (see its own KDoc), so this is never actually null.
                            PieceView(
                                piece = piece,
                                row = row,
                                col = col,
                                fromRow = fromRow,
                                fromCol = fromCol,
                                cellSize = cellSize,
                                selected = selected == (row to col),
                                card3D = card3D,
                                enhanced = enhanced,
                                maximum = maximum,
                                bouncePulse = isWinnerPiece && effectiveTier != CheckersMotionTier.OFF,
                                roundEndProgress = roundEndProgress,
                                moveProgress = moveProgress,
                                onClick = { onSquareTapped(row, col) }
                            )
                        }
                    }

                    // Captured piece(s) this turn -- kept rendered (fading out under
                    // `enhanced`, cutting instantly otherwise) until the shared
                    // moveProgress clock reaches each one's own hop segment, instead of
                    // vanishing the instant pieceIds above drops their id. See this
                    // file's own KDoc and capturedGhostsFor's.
                    for (ghost in capturedGhosts) {
                        key(ghost) {
                            CapturedGhostView(ghost = ghost, cellSize = cellSize, enhanced = enhanced, moveProgress = moveProgress)
                        }
                    }
                }
            }
        }
    }

    // Wired into AdaptiveTwoPane (secondary = null -- Checkers has no natural "hand" pane) so a
    // Tab S9 or a fully-unfolded Fold 5 in landscape (TABLET mode) caps the board's width at
    // 840dp instead of stretching it across the whole window, matching every other game in the
    // suite. Inside primary, an aspect check reflows the chrome BESIDE the board (Row) rather
    // than above it (Column) whenever the window is wider than it is tall -- the tightest real
    // case being the Fold 5 cover screen rotated to landscape (~344dp tall) -- and in both
    // arrangements the board's own BoxWithConstraints sits in a weight(1f) slot so it receives a
    // REAL bounded/reduced maxHeight (the space actually left after the chrome), not the whole
    // pane's height as if the chrome took none of it.
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        secondary = null,
        primary = {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth > maxHeight) {
                    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        chromeBlock(
                            Modifier
                                .widthIn(max = 220.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        )
                        Spacer(Modifier.width(16.dp))
                        boardBlock(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        chromeBlock(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        boardBlock(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    )
}

/** Assigns/tracks stable piece ids (id -> current square index) from a fresh
 *  board forward through [CheckersState.lastMove]'s (from -> to) each turn --
 *  see this file's own KDoc for why this needs to exist at all. At round
 *  start every occupied square is given its own index as its id; from then
 *  on an id keeps following the same physical piece for the rest of the
 *  round, and a captured piece's id simply drops out of the map once its
 *  square is no longer occupied. */
private fun deriveIds(previous: Map<Int, Int>, s: CheckersState): Map<Int, Int> {
    val move = s.lastMove
    val stepped: Map<Int, Int> = if (move == null) {
        previous
    } else {
        val fromSquare = move.fromRow * 8 + move.fromCol
        val toSquare = move.toRow * 8 + move.toCol
        val movingId = previous.entries.firstOrNull { it.value == fromSquare }?.key
        if (movingId != null) previous + (movingId to toSquare) else previous
    }
    val result = stepped.filterValues { s.board[it] != null }.toMutableMap()
    val coveredSquares = result.values.toHashSet()
    for (square in s.board.indices) {
        if (s.board[square] != null && square !in coveredSquares) {
            result[square] = square // fallback id = own square index; only needed on the very first round
        }
    }
    return result
}

/** Plain (non-Compose-state) holder for the board as it stood immediately before the
 *  CURRENT [CheckersState] -- mirrors ChessScreen.kt's own `BoardMemory`: read then
 *  overwritten inside a single `remember(s)` block, so it never itself triggers a
 *  recomposition the way a `mutableStateOf` would. */
private class CheckersBoardMemory { var previous: List<CheckersPiece?>? = null }

private class CheckersMoveDerived(val ids: Map<Int, Int>, val ghosts: List<CapturedGhost>)

/** One captured piece still being animated off the board for the current move --
 *  [square]/[piece] are exactly what [CheckersState.board] held there just before this
 *  turn (see [capturedGhostsFor]); [hopIndex]/[hopCount] locate which equal timeline
 *  segment (of [CheckersState.lastTurnHops]'s [hopCount] hops) this particular capture
 *  belongs to, so [CapturedGhostView] knows when -- relative to the shared move
 *  progress clock -- to make it disappear. */
private data class CapturedGhost(val square: Int, val piece: CheckersPiece, val hopIndex: Int, val hopCount: Int)

/**
 * Reconstructs which square(s) [s]'s just-committed turn captured a piece on, in hop
 * order, straight from [CheckersState.lastTurnHops] -- the same field a single real
 * hop ([CheckersGame.playMove], including one link of a human's forced-continuation
 * chain) and a bot's whole compound multi-jump chain ([CheckersGame.playBotTurn]) both
 * populate (see that field's own KDoc), so this needs no special-casing between the
 * two: a single-hop, non-chained move is just the N=1 case, and a non-capturing move
 * (or the very first render, where [prevBoard] is still null) naturally yields no
 * ghosts at all. [prevBoard] -- the board as it stood immediately BEFORE this turn --
 * is where each captured piece's owner/kind is read from, since by the time [s] exists
 * that square has already gone null.
 */
private fun capturedGhostsFor(prevBoard: List<CheckersPiece?>?, s: CheckersState): List<CapturedGhost> {
    if (prevBoard == null) return emptyList()
    val hops = s.lastTurnHops
    val captureHops = hops.withIndex().filter { it.value.isCapture }
    if (captureHops.isEmpty()) return emptyList()
    return captureHops.mapNotNull { (hopIndex, hop) ->
        val square = hop.capRow * 8 + hop.capCol
        val piece = prevBoard.getOrNull(square) ?: return@mapNotNull null
        CapturedGhost(square = square, piece = piece, hopIndex = hopIndex, hopCount = hops.size)
    }
}

@Composable
private fun PieceView(
    piece: CheckersPiece,
    row: Int,
    col: Int,
    fromRow: Int,
    fromCol: Int,
    cellSize: Dp,
    selected: Boolean,
    card3D: Boolean,
    enhanced: Boolean,
    maximum: Boolean,
    bouncePulse: Boolean,
    roundEndProgress: Animatable<Float, AnimationVector1D>,
    moveProgress: Animatable<Float, AnimationVector1D>,
    onClick: () -> Unit
) {
    // Real lift-and-place move animation (animation/physics pitch, Checkers section):
    // [fromRow]/[fromCol] -- this piece's square just before the CURRENT move, equal
    // to [row]/[col] themselves for every piece except the one that actually moved
    // (see the call site's own comment) -- and the shared [moveProgress] clock (0f at
    // the start of the move, 1f once it settles; pinned to 1f throughout under reduced
    // motion, see this file's own KDoc) together replace the old two independent
    // animateDpAsState calls: x/y are still a straight linear interpolation between
    // the two squares (same path shape as before), but sharing one progress value
    // also lets the `enhanced`-gated lift/scale/shadow flourish below play in lockstep
    // with that same glide, and lets CapturedGhostView (a separate composable
    // entirely) key its own per-hop fade timing off the exact same clock.
    val progress = moveProgress.value
    val isMoving = fromRow != row || fromCol != col
    val x = cellSize * (fromCol + (col - fromCol) * progress)
    val y = cellSize * (fromRow + (row - fromRow) * progress)

    // Promotion reveal via card3DFlip (animation/physics pitch, Checkers section) --
    // PieceView already persists across a promotion as the SAME composable instance
    // (the caller keys it on the piece's stable id, see deriveIds' KDoc), so unlike
    // ChessScreen.kt's board-diffing approach, detecting "this exact piece just
    // promoted" only needs locally-remembered previous-kind state, not a whole-board
    // diff. flipProgress starts (and stays) at 1f when not promoting/3D-off, so the
    // face-swap check below never trips and today's plain instant crown-appears
    // behavior keeps happening unmodified.
    var previousKind by remember { mutableStateOf(piece.kind) }
    val justPromoted = previousKind == PieceKind.MAN && piece.kind == PieceKind.KING
    val flipProgress = remember { Animatable(if (justPromoted && card3D) 0f else 1f) }
    LaunchedEffect(piece.kind) {
        if (justPromoted && card3D) {
            flipProgress.snapTo(0f)
            flipProgress.animateTo(1f, animationSpec = tween(250))
        }
        // Only updates once the flip above has fully played (animateTo suspends until
        // done) -- so `justPromoted` correctly stays true, and flipProgress keeps
        // driving the render below, for the whole animation, not just its first frame.
        previousKind = piece.kind
    }
    val showPromotionFlip = justPromoted && card3D

    // Gameplay colors are fixed literals per AppTheme.kt's documented rule --
    // never MaterialTheme.colorScheme here. Shared with CapturedPieceTray below
    // so its swatches visually match these real pieces exactly.
    val pieceColor = if (piece.owner == 0) DARK_PIECE_COLOR else LIGHT_PIECE_COLOR
    val ringColor = if (piece.owner == 0) DARK_PIECE_RING else LIGHT_PIECE_RING

    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(cellSize)
            .padding(4.dp)
            // Lift/scale/shadow "hop" flourish -- gated behind `enhanced` AND only
            // for the piece actually moving this turn (isMoving; see this function's
            // own KDoc): a stationary piece's progress-driven glide above is already
            // a no-op, and without the isMoving guard here it would still visibly
            // bob in place every time ANY other piece moves, since moveProgress is
            // shared across the whole board. All three effects share one sin() hop
            // shape (0 at both ends, peaking at the midpoint) so they read as one
            // coherent motion rather than three independently-timed ones.
            .then(
                if (enhanced && isMoving) {
                    Modifier.graphicsLayer {
                        val hop = kotlin.math.sin(progress.coerceIn(0f, 1f) * kotlin.math.PI.toFloat())
                        translationY = -(12.dp.toPx()) * hop
                        val scale = 1f + 0.08f * hop
                        scaleX = scale
                        scaleY = scale
                        shadowElevation = 6.dp.toPx() * hop
                        shape = CircleShape
                    }
                } else Modifier
            )
            // Round-over bounce-pulse (premium-2026-vision pass): a brief, decaying
            // scale oscillation on the winner's surviving pieces only, synchronized
            // to the same roundEndProgress clock the board container's own fade/
            // scale-down reads -- see the call site's `bouncePulse`. The sin(...)*
            // (1-p) envelope is exactly 0 at both p=0 and p=1, so an instant OFF-tier
            // snap straight to p=1 (see the call site) never shows a stray pop.
            .then(
                if (bouncePulse) {
                    Modifier.graphicsLayer {
                        val p = roundEndProgress.value
                        val bounce = kotlin.math.sin(p * kotlin.math.PI.toFloat() * 2.5f) * (1f - p)
                        val scale = 1f + 0.22f * bounce
                        scaleX = scale
                        scaleY = scale
                    }
                } else Modifier
            )
            .clip(CircleShape)
            .background(pieceColor)
            // Lacquered-piece sheen (premium-2026-vision pass): the same shared
            // specularSweep every other game's "gloss"/"foil" recommendation uses,
            // tinted per side and reserved for `maximum` -- see this file's own
            // KDoc for why this game gates it on its own tier rather than the
            // default LocalCard3DMode rule.
            .then(
                if (maximum) {
                    Modifier.specularSweep(
                        enabled = true,
                        tint = if (piece.owner == 0) DARK_PIECE_SHEEN else LIGHT_PIECE_SHEEN,
                        periodMs = 3600
                    )
                } else Modifier
            )
            .border(if (selected) 3.dp else 2.dp, if (selected) Color(0xFFFFC107) else ringColor, CircleShape)
            .then(if (showPromotionFlip) Modifier.card3DFlip(flipProgress.value) else Modifier)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${if (piece.owner == 0) "Dark" else "Light"} " +
                    "${if (piece.kind == PieceKind.KING) "king" else "man"} at row ${row + 1}, column ${col + 1}"
            },
        contentAlignment = Alignment.Center
    ) {
        // card3DFlip's documented contract: swap the rendered face at the halfway
        // point where the piece is edge-on -- below that, still show a plain man
        // (about to promote); at/after it, the crown.
        val showCrown = if (showPromotionFlip) flipProgress.value >= 0.5f else piece.kind == PieceKind.KING
        if (showCrown) {
            // Metallic king crown (premium-2026-vision pass): a single warm-gold
            // treatment shared by BOTH sides now (was owner-tinted before), with a
            // small drop shadow for a lacquered/metallic read, plus its own subtle
            // specularSweep glint at `maximum` -- distinct from, and layered on top
            // of, the piece body's own broader sheen above.
            Box(
                modifier = Modifier.then(
                    if (maximum) Modifier.specularSweep(enabled = true, tint = KING_CROWN_SHEEN, periodMs = 2600) else Modifier
                )
            ) {
                Text(
                    "♚",
                    style = TextStyle(
                        color = KING_CROWN_GOLD,
                        textAlign = TextAlign.Center,
                        shadow = Shadow(color = Color(0x99000000), offset = Offset(1f, 1.5f), blurRadius = 2f)
                    )
                )
            }
        }
    }
}

/**
 * A single captured piece, still fading (or -- with `enhanced` off, or under reduced
 * motion since that always forces `enhanced` false, see this file's own KDoc -- simply
 * still present) at the square it occupied just before this turn, until the shared
 * [moveProgress] clock reaches this particular hop's segment of the timeline: with N
 * hops splitting progress 0f..1f into N equal segments, hop i's capture starts
 * disappearing the instant progress enters segment i, i.e. at progress >= i/N. That
 * threshold check -- not the fade itself -- is the correctness fix (each capture
 * disappears at the actual moment its hop happens instead of all at once at the
 * start), so it runs the same way whether `enhanced` is on or off; only which of
 * `animateTo`/`snapTo` finishes the job once that instant is reached differs.
 */
@Composable
private fun CapturedGhostView(ghost: CapturedGhost, cellSize: Dp, enhanced: Boolean, moveProgress: Animatable<Float, AnimationVector1D>) {
    val threshold = ghost.hopIndex.toFloat() / ghost.hopCount
    val fade = remember(ghost) { Animatable(1f) }
    LaunchedEffect(ghost) {
        snapshotFlow { moveProgress.value }.first { it >= threshold }
        if (enhanced) fade.animateTo(0f, animationSpec = tween(220)) else fade.snapTo(0f)
    }
    if (fade.value <= 0f) return

    val pieceColor = if (ghost.piece.owner == 0) DARK_PIECE_COLOR else LIGHT_PIECE_COLOR
    val ringColor = if (ghost.piece.owner == 0) DARK_PIECE_RING else LIGHT_PIECE_RING
    Box(
        modifier = Modifier
            .offset(x = cellSize * (ghost.square % 8), y = cellSize * (ghost.square / 8))
            .size(cellSize)
            .padding(4.dp)
            .graphicsLayer {
                alpha = fade.value
                scaleX = fade.value
                scaleY = fade.value
            }
            .clip(CircleShape)
            .background(pieceColor)
            .border(2.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (ghost.piece.kind == PieceKind.KING) {
            Text("♚", color = KING_CROWN_GOLD)
        }
    }
}

/**
 * Procedurally draws a warm wood-grain baseline tile ONCE per composition into a
 * cached [ImageBitmap] -- never per-frame -- then every square below just stretches
 * (via `drawImage`'s `dstSize`) whichever tile (dark/light) matches it. A fixed
 * [seed] keeps the grain deterministic/reproducible across recompositions of the
 * SAME tile (the point is a stable-looking baseline, not visual noise that shifts
 * every frame) while still differing between the two tiles. This baseline alone --
 * no shader, no animation -- is the "must look complete and correct on any device"
 * requirement; [com.gamesuite.ui.effects.specularSweep] (gated on `maximum`) is
 * purely the moving highlight layered on top of it at the call site.
 */
@Composable
private fun rememberWoodGrainTile(base: Color, grain: Color, highlight: Color, seed: Int): ImageBitmap =
    remember(base, grain, highlight, seed) { drawWoodGrainTile(base, grain, highlight, seed) }

private fun drawWoodGrainTile(base: Color, grain: Color, highlight: Color, seed: Int, size: Int = 96): ImageBitmap {
    val bitmap = ImageBitmap(size, size)
    val canvas = Canvas(bitmap)
    val drawScope = CanvasDrawScope()
    val rng = Random(seed)
    drawScope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(size.toFloat(), size.toFloat())) {
        drawRect(base)
        // A handful of gently wobbling grain streaks -- alternating a darker
        // grain tone and a lighter highlight tone -- give the flat base fill a
        // real (if simple) organic wood-grain read at a glance.
        repeat(10) { i ->
            val y = rng.nextFloat() * size
            val wobble = rng.nextFloat() * 8f - 4f
            val streakColor = if (i % 3 == 0) highlight.copy(alpha = 0.4f) else grain.copy(alpha = 0.32f)
            val path = Path().apply {
                moveTo(0f, y)
                cubicTo(size * 0.28f, y + wobble, size * 0.62f, y - wobble, size.toFloat(), y + wobble * 0.4f)
            }
            drawPath(path, color = streakColor, style = Stroke(width = 1f + rng.nextFloat() * 1.8f))
        }
        // A couple of soft radial "burl" highlights so the grain doesn't read
        // as perfectly uniform stripes.
        repeat(2) {
            val center = Offset(rng.nextFloat() * size, rng.nextFloat() * size)
            val radius = size * 0.32f
            drawCircle(
                brush = Brush.radialGradient(colors = listOf(highlight.copy(alpha = 0.18f), Color.Transparent), center = center, radius = radius),
                radius = radius,
                center = center
            )
        }
    }
    return bitmap
}

// Same literals PieceView renders real pieces with (see its own comment) --
// pulled up here so the captured-piece tray's swatches are guaranteed to
// match, not just visually approximate them.
private val DARK_PIECE_COLOR = Color(0xFF212121)
private val DARK_PIECE_RING = Color(0xFF616161)
private val LIGHT_PIECE_COLOR = Color(0xFFFAFAFA)
private val LIGHT_PIECE_RING = Color(0xFFBDBDBD)

// Premium-2026-vision pass: subtle per-side specularSweep tints (pieces/squares) and
// the shared metallic-gold king treatment -- see PieceView's own comments for where
// each is used. Kept subtle (low alpha) per the pitch's own "subtle" instruction for
// piece sheen; squares get a touch more since they're the larger, flatter surface.
private val DARK_PIECE_SHEEN = Color(0x59FFFFFF)
private val LIGHT_PIECE_SHEEN = Color(0x40FFF8E1)
private val DARK_SQUARE_SHEEN = Color(0x4DFFE0B2)
private val LIGHT_SQUARE_SHEEN = Color(0x40FFFFFF)
private val KING_CROWN_SHEEN = Color(0x80FFF3B0)
private val KING_CROWN_GOLD = Color(0xFFD4AF37)

// Felt surround (premium-2026-vision pass) -- a static, matte radial gradient (no
// specularSweep, see this file's own KDoc for why felt stays matte) framing the grid.
private val FELT_SURROUND_BRUSH = Brush.radialGradient(colors = listOf(Color(0xFF1B5E20), Color(0xFF0B3D14)))

/** 12 men per side at [CheckersGame.startMatch] -- captured count is just this minus
 *  however many of that owner's pieces [CheckersState.board] still has on it. */
private const val STARTING_PIECES_PER_SIDE = 12

/** Real touch-target floor for a board square -- coerced onto cellSize regardless of how little
 *  space is actually available (same "floor, never let it shrink below a usable tap size"
 *  pattern as [com.gamesuite.games.cards.CardScale]'s own multiplier), so an 8x8 grid never
 *  produces an untappable square even in the tightest window (e.g. the Fold 5 cover screen). */
private val MIN_TOUCH_TARGET = 48.dp

/** The existing per-move glide duration (unchanged) -- pulled into a named constant
 *  since the premium-2026-vision pass' hit-stop needs to carve proportional
 *  sub-durations out of it (see the `moveProgress` LaunchedEffect above). */
private const val MOVE_DURATION_MS = 300

/** A genuine hit-stop freeze, reserved for a capturing hop only (see the
 *  `moveProgress` LaunchedEffect above) -- within the pitch's requested ~60-80ms
 *  range. */
private const val HIT_STOP_MS = 70L

/** How long the board container's decaying jittered camera-shake takes to settle
 *  back to zero once triggered by a capture's hit-stop. */
private const val CAMERA_SHAKE_DECAY_MS = 180

/** Duration of the round-over board fade/scale-down before the results screen swaps
 *  in (see the `roundEndProgress` LaunchedEffect above). */
private const val ROUND_END_TRANSITION_MS = 520

/**
 * Pure derived-state readout, no engine changes: a small swatch row per side
 * showing how many of that side's starting 12 pieces are gone. Positioned
 * directly above the board in [CheckersScreen] (see call site) rather than
 * docked to the screen edge, since with only two players there's no need for
 * a persistent scoreboard chrome -- this is closer to a glance-able detail
 * than a HUD.
 */
@Composable
private fun CapturedPieceTray(darkCaptured: Int, lightCaptured: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CapturedSideRow(label = "Dark", count = darkCaptured, pieceColor = DARK_PIECE_COLOR, ringColor = DARK_PIECE_RING)
        CapturedSideRow(label = "Light", count = lightCaptured, pieceColor = LIGHT_PIECE_COLOR, ringColor = LIGHT_PIECE_RING)
    }
}

@Composable
private fun CapturedSideRow(label: String, count: Int, pieceColor: Color, ringColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "$label captured: $count" }
    ) {
        Text("$label captured: $count", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(6.dp))
        repeat(count) {
            Box(
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(pieceColor)
                    .border(1.dp, ringColor, CircleShape)
            )
        }
    }
}

private fun squareDescription(s: CheckersState, row: Int, col: Int, isDestination: Boolean): String {
    val piece = s.pieceAt(row, col)
    val base = when {
        piece != null -> "${if (piece.owner == 0) "Dark" else "Light"} piece"
        isDestination -> "Legal destination"
        else -> "Empty square"
    }
    return "$base, row ${row + 1}, column ${col + 1}"
}
