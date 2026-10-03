package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameContext
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
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import com.gamesuite.ui.effects.cameraShakeOffsetFor
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.ui.effects.specularSweep
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
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
 * to its new one. The glide's position is read inside an `offset { }` lambda
 * (layout phase), so the 24 pieces do not recompose on every animation frame.
 *
 * UI-QUALITY PASS (shared GameChrome / fitBoard kit, see ReversiScreen):
 *  - CHROME: the whole screen sits in [GameChrome] -- corner menu (How to Play,
 *    the motion-tier choice, Back to Menu), intercepted system back, and a
 *    confirm-before-leaving dialog. Leaving mid-round goes through
 *    `GameModule.abortMatch` (never recorded) when no round has been finished
 *    yet; once a round is already won or lost it goes through `leaveSession()`
 *    instead so those finished rounds still count (see `onAbort` below). The
 *    status block reserves [GameChromeEndInset] so the corner button never
 *    sits on it. The motion-tier picker moved from an always-visible chip row
 *    into that menu: it was the widest control on a 312dp cover screen.
 *  - SIZING: [fitBoard] against the measured slot the board lives in (never an
 *    outer scope), 8 columns, felt frame subtracted, [MAX_CELL_DP] cap. The old
 *    48dp cell floor made a 312dp pane clip its right-hand column. Below
 *    [MIN_CELL_DP] the cell size is held there and the board scrolls instead.
 *  - SEAT: the engine seats "You" as side 0 (Dark), which starts on rows 0-2 --
 *    the top of the grid. Against the CPU the board is drawn rotated 180
 *    degrees ([rotated]) so your pieces start at the bottom with a dark corner
 *    square at your left, like a real board; the engine is untouched. Light
 *    (side 1) moves first in this engine, and the status text says which
 *    colour you play.
 *  - ACCESSIBILITY: every piece and every empty dark square carries
 *    "<what>, row r, column c" (1-indexed, as drawn, so it follows the
 *    rotation); the pieces you may act on and the legal destinations are
 *    `Role.Button` with a click label; occupied squares and light squares carry
 *    no semantics of their own so nothing is announced twice. Legal
 *    destinations also get a centre dot (shape, not just green), and with
 *    [LocalColorblindMode] the dot gains a dark outline and the selected piece
 *    a second inner ring. The status line and any notice are live regions.
 *  - FEEDBACK: tapping one of your pieces that cannot move says why ("You must
 *    jump" when a capture is mandatory elsewhere) with the invalid buzz/haptic.
 *  - RESULT: renders inside the chrome, "You win!" grammar, tie wording, 48dp
 *    buttons. Celebration (chime, SUCCESS haptic, winner-piece bounce) fires
 *    only when a human wins; a CPU win gets the buzz/FAILURE cue instead.
 *    Haptics are no longer tied to the Maximum motion tier -- they follow the
 *    master haptics setting alone.
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
 * [CheckersMotionTier] for the per-game Standard/Maximum/Off setting that gates
 * everything below beyond `enhanced`/`card3D`'s existing reach:
 *  - Squares are no longer a flat literal [Color] fill: [rememberWoodGrainTile]
 *    procedurally draws a warm-brown/honey-tan wood-grain baseline ONCE per
 *    composition into a cached [ImageBitmap] (never per-frame), stretched
 *    onto every dark/light square respectively, with a felt-green surround
 *    framing the 8x8 grid -- this baseline renders identically (just without
 *    the animated highlight) on every device, per
 *    [com.gamesuite.ui.effects.PremiumShaders]' own documented contract.
 *    At [CheckersMotionTier.MAXIMUM] ONE [com.gamesuite.ui.effects.specularSweep]
 *    layer drifts across the whole grid (squares, pieces and crowns together,
 *    a "lacquered" sheen) -- it used to be one shader per square, piece and
 *    crown (up to ~100 RuntimeShaders). It is gated on this game's own tier,
 *    NOT the shared [LocalCard3DMode] gate other specularSweep consumers use.
 *    Kings wear a drawn gold crown ([CheckersCrownGlyph], with a dark outline so
 *    it reads on the light pieces too) instead of a font glyph.
 *  - A genuine hit-stop (a brief freeze of the shared move-progress clock)
 *    plus a small decaying jittered camera-shake on the board container,
 *    both reserved for a capturing hop only -- see the `moveProgress`
 *    [LaunchedEffect] below -- never fires on a plain move.
 *  - A haptic vocabulary via [com.gamesuite.haptics.rememberHaptics]:
 *    LIGHT_TICK on selecting a piece, NORMAL_ACTION on a quiet move,
 *    STRONG_ACTION on a capture, ESCALATING on a promotion, SUCCESS/FAILURE
 *    on the round ending, FAILURE on a rejected tap. The selection/move/
 *    rejection haptics are scoped to the human's own taps in [onSquareTapped]
 *    only, mirroring how `sounds.playTap()` already only ever fires there and
 *    never for [CheckersGame.playBotTurn]; the round-end SUCCESS/FAILURE pair
 *    fires from the `s.gameOver` effect (SUCCESS only when a human wins).
 *  - A round-over transition instead of a hard cut: the board plays a brief
 *    bounce-pulse on the human winner's surviving pieces plus an overall fade/
 *    scale-down (`roundEndProgress` below) before the results panel swaps in --
 *    gated on the tier being anything but [CheckersMotionTier.OFF] (same
 *    reach as `enhanced`/`card3D`, not `maximum`-only, since this is baseline
 *    round-transition polish rather than a top-tier flourish).
 *  - Selecting a piece lifts it 8% over 120ms (snaps when `enhanced` is off,
 *    so never under reduced motion).
 *
 * TAB S9 INPUT PASS (DEVICE_SPECIFIC_PLAN.md §4c) additions: every tappable
 * board square, every piece ([PieceView]) and the results-screen Play Again/
 * Back to Menu buttons carry `Modifier.pointerHoverIcon(PointerIcon.Hand)`, so
 * a mouse or the Tab S9 trackpad shows a hand cursor over them (DeX windowed
 * mode, keyboard-cover scenario) -- zero effect on touch, purely additive. This
 * is an in-game board per §4c's own scoping, so no keyboard-focus/Tab-traversal
 * work was added here.
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
    val colorblind = LocalColorblindMode.current

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

    // The last non-null session context. leaveSession()/abortMatch() null the shell's
    // activeContext in the same beat they end the match, and this screen reads player names and
    // seats from it -- without a memory the whole screen (result panel included) would go blank
    // for the frames before navigation pops.
    val contextMemory = remember { CheckersContextMemory() }
    val liveContext = context
    if (liveContext != null) contextMemory.last = liveContext

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
        if (s.gameOver || game.matchOver.value) return@LaunchedEffect
        if (ctx.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(700)
            // The player may have left (abort) during the delay.
            if (game.matchOver.value) return@LaunchedEffect
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
    val ctx = context ?: contextMemory.last ?: return

    val hasBot = ctx.players.any { it.isBot }
    val humanSide = ctx.players.indexOfFirst { !it.isBot }.coerceAtLeast(0)
    // The engine seats side 0 (Dark) on rows 0-2, the TOP of the grid. Against the CPU, "You" is
    // side 0, so rotate the drawing 180 degrees to put the human at the bottom. A 180 turn (not
    // a row mirror) keeps dark squares on the same logical squares and puts a dark corner square
    // at the human's left, as on a real board. Pass-and-play keeps the engine's own orientation.
    val rotated = hasBot && humanSide == 0
    val matchEnded = game.matchOver.value
    val winnerPlayer = ctx.players.firstOrNull { it.playerId == s.winnerPlayerId }
    val humanWon = s.gameOver && winnerPlayer != null && !winnerPlayer.isBot

    // Round-over transition (premium-2026-vision pass): rather than an
    // instant hard cut to the results panel the moment s.gameOver flips
    // true, the board itself plays a brief bounce-pulse on the winner's
    // pieces plus a fade/scale-down first -- see this file's own KDoc.
    // `showResultsScreen` only flips once that transition (or, at OFF tier,
    // nothing at all) has finished, so the normal interactive-board code path
    // keeps rendering in the meantime (with taps disabled -- see onSquareTapped).
    var showResultsScreen by remember { mutableStateOf(false) }
    val roundEndProgress = remember { Animatable(0f) }
    val winnerSide = remember(s.winnerPlayerId) { ctx.players.indexOfFirst { it.playerId == s.winnerPlayerId }.takeIf { it >= 0 } }
    LaunchedEffect(s.gameOver) {
        if (s.gameOver) {
            // Celebration (chime + SUCCESS haptic, and the winner-piece bounce below) is the
            // human's win only. A CPU win gets the quiet buzz + FAILURE cue, never a fanfare.
            if (humanWon) {
                playSfx(SfxKind.SUCCESS_CHIME)
                haptics(HapticSignal.SUCCESS)
            } else {
                playSfx(SfxKind.INVALID_BUZZ)
                haptics(HapticSignal.FAILURE)
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

    val isHumanTurn = !s.gameOver && !matchEnded && ctx.players.getOrNull(s.currentPlayerIndex)?.isBot != true

    // User-picked source square. Overridden by the forced-continuation square
    // whenever one is active (that piece MUST jump again -- the player can't
    // deselect it or pick another), so this only ever matters when there's no
    // forced continuation. Cleared on every turn change / new round.
    var userSelected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // One-line explanation after a rejected tap ("You must jump"); cleared with the selection.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.currentPlayerIndex, s.gameOver, s.lastMove == null) {
        userSelected = null
        notice = null
    }
    val selected = if (s.inForcedContinuation) s.forcedRow to s.forcedCol else userSelected

    val legalDestinations = selected?.let { (r, c) -> game.legalDestinationsFrom(r, c) } ?: emptySet()

    // Squares (row * 8 + col) of the current player's pieces that have at least one legal move
    // right now -- computed once per state instead of per piece per recomposition.
    val movableSquares = remember(s, isHumanTurn) {
        if (!isHumanTurn) {
            emptySet<Int>()
        } else {
            (0 until 64).filter { sq ->
                s.board[sq]?.owner == s.currentPlayerIndex && game.hasLegalMoveFrom(sq / 8, sq % 8)
            }.toSet()
        }
    }

    fun rejectTap(message: String) {
        notice = message
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }

    // A capture is mandatory iff any movable piece's legal destinations are two rows away.
    fun captureIsMandatory(): Boolean = movableSquares.any { sq ->
        game.legalDestinationsFrom(sq / 8, sq % 8).any { (toRow, _) -> abs(toRow - sq / 8) == 2 }
    }

    fun onSquareTapped(row: Int, col: Int) {
        if (!isHumanTurn) return
        if (selected != null && (row to col) in legalDestinations) {
            val fromKind = s.pieceAt(selected.first, selected.second)?.kind
            game.playMove(s.currentPlayerIndex, selected.first, selected.second, row, col)
            sounds.playTap()
            // Hoisted so the procedural SFX (a capture landing genuinely sounds different
            // from a plain move) and the haptic escalation below can both read it.
            val justCommitted = game.state.value
            val landedKind = justCommitted?.pieceAt(row, col)?.kind
            val wasCapture = justCommitted?.lastMove?.isCapture == true
            // Real SFX gap: sounds.playTap() above is the same generic tap for
            // every valid move today, with nothing distinguishing a satisfying
            // capture landing from a quiet plain slide.
            playSfx(if (wasCapture) SfxKind.SOLID_THUNK else SfxKind.LIGHT_TICK)
            when {
                fromKind == PieceKind.MAN && landedKind == PieceKind.KING -> haptics(HapticSignal.ESCALATING)
                wasCapture -> haptics(HapticSignal.STRONG_ACTION)
                else -> haptics(HapticSignal.NORMAL_ACTION)
            }
            notice = null
            userSelected = null
            return
        }
        val tappedOwn = s.pieceAt(row, col)?.owner == s.currentPlayerIndex
        if (s.inForcedContinuation) {
            // must play the forced jump, can't pick a different square
            if (tappedOwn && (row to col) != selected) rejectTap("Keep jumping with the highlighted piece.")
            return
        }
        val canSelect = tappedOwn && (row * 8 + col) in movableSquares
        val newSelection = if (canSelect) {
            if (userSelected == (row to col)) null else row to col
        } else {
            null
        }
        notice = null
        if (newSelection != null) {
            // Real SFX gap: selecting a piece was silent at every motion tier.
            playSfx(SfxKind.LIGHT_TICK)
            haptics(HapticSignal.LIGHT_TICK)
        } else if (tappedOwn && !canSelect) {
            rejectTap(if (captureIsMandatory()) "You must jump. Pick a piece that can capture." else "That piece has no moves.")
        }
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
    // The shared com.gamesuite.ui.effects.CameraShake utility (see its own KDoc) -- this exact
    // Animatable-driven `shake * sin(shake*freq)` formula was extracted directly from this
    // screen's own pre-migration code, so this is a lossless, drop-in replacement, not an
    // approximation. `easing = FastOutSlowInEasing` reproduces the plain `tween(...)` (no
    // explicit easing) the pre-migration `cameraShake.animateTo` call used, whose DEFAULT
    // easing is FastOutSlowInEasing, not linear.
    val cameraShake = rememberCameraShake()
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
            launch { cameraShake.trigger(durationMs = CAMERA_SHAKE_DECAY_MS, easing = FastOutSlowInEasing) }
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

    val statusText = when {
        s.gameOver -> "Game over"
        !isHumanTurn -> "Opponent's turn"
        s.inForcedContinuation -> "Capture again with the same piece"
        hasBot -> "Your turn"
        else -> "${sideName(s.currentPlayerIndex)}'s turn"
    }
    val hintText = if (hasBot) {
        "You play ${sideName(humanSide)}. Tap a piece, then a highlighted square."
    } else {
        "Tap a piece, then a highlighted square."
    }

    // Turn status / difficulty / captured-piece tray -- reused as-is by both the portrait
    // (stacked above the board) and landscape (beside the board) arrangements below, so this
    // chrome is never the thing silently eating the vertical budget a short window (e.g. the
    // Fold 5 cover screen rotated to landscape, ~344dp tall) needs for the board itself.
    // [reserveCorner]: portrait puts this block under the corner menu button, so its status
    // lines keep GameChromeEndInset free at the end; in landscape it sits on the left.
    val chromeBlock: @Composable (Modifier, Boolean) -> Unit = { chromeModifier, reserveCorner ->
        Column(modifier = chromeModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (reserveCorner) Modifier.padding(end = GameChromeEndInset) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                Text(
                    hintText,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
                val noticeText = notice
                if (noticeText != null) {
                    Text(
                        noticeText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                if (hasBot) {
                    Text(
                        "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelSmall
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
                stacked = !reserveCorner,
                modifier = Modifier.widthIn(max = 480.dp)
            )
        }
    }

    // The board itself. Sized by fitBoard from the measured space of THIS slot (the room left
    // after the status block / beside it), never from an outer scope: the old formula floored the
    // cell at 48dp, which on a 312dp pane made the felt clip while the squares kept their pitch
    // and the right-hand column fell off the edge. fitBoard never returns a footprint larger
    // than its input; below MIN_CELL_DP we keep the cell at that size and let the board scroll.
    val boardBlock: @Composable (Modifier) -> Unit = { boardModifier ->
        BoxWithConstraints(modifier = boardModifier, contentAlignment = Alignment.Center) {
            val fit = remember(maxWidth, maxHeight) {
                fitBoard(
                    availableWidthPx = maxWidth.value,
                    availableHeightPx = maxHeight.value,
                    columns = 8,
                    rows = 8,
                    framePx = FELT_PADDING_DP,
                    minCellPx = MIN_CELL_DP,
                    maxCellPx = MAX_CELL_DP
                )
            }
            val scrolls = !fit.meetsMinimum
            val cellSize = if (scrolls) MIN_CELL_DP.dp else fit.cellPx.dp
            val feltPadding = FELT_PADDING_DP.dp
            val gridSize = cellSize * 8
            val boardSize = gridSize + feltPadding * 2
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()

            Box(
                modifier = if (scrolls) {
                    Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)
                } else {
                    Modifier
                }
            ) {
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
                                    // Y axis intentionally flattened to 0.6x the X magnitude (a
                                    // real, deliberate asymmetry from before the migration) -- two
                                    // calls with different magnitudes, one per axis, since
                                    // cameraShakeOffsetFor's own single-magnitude convenience
                                    // shape doesn't cover an asymmetric shake.
                                    val jitterPx = 4.dp.toPx()
                                    translationX = cameraShakeOffsetFor(cameraShake.value, jitterPx, frequencyX = 47f).x
                                    translationY = cameraShakeOffsetFor(cameraShake.value, jitterPx * 0.6f, frequencyY = 39f).y
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
                            // Innermost layer on purpose: the one shared sheen sees the plain
                            // squares + pieces, and the shake/fade layers above move it with them.
                            .then(
                                if (maximum) Modifier.specularSweep(enabled = true, tint = BOARD_SHEEN, periodMs = 4200) else Modifier
                            )
                    ) {
                        // Squares (background grid): dark squares only ever hold pieces;
                        // light squares are always empty, matching CheckersLogic.h's KDoc.
                        // (row, col) are the engine's coordinates; displayRow/displayCol are
                        // where that square is DRAWN (and announced) -- see `rotated`.
                        for (row in 0..7) for (col in 0..7) {
                            val isDark = (row + col) % 2 == 1
                            val displayRow = if (rotated) 7 - row else row
                            val displayCol = if (rotated) 7 - col else col
                            val isDestination = (row to col) in legalDestinations
                            val isSelected = selected == (row to col)
                            val squarePiece = s.pieceAt(row, col)
                            Box(
                                modifier = Modifier
                                    .offset(x = cellSize * displayCol, y = cellSize * displayRow)
                                    .size(cellSize)
                                    .drawBehind {
                                        val tile = if (isDark) darkWoodTile else lightWoodTile
                                        drawImage(image = tile, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                                    }
                                    .then(
                                        if (isSelected || isDestination) {
                                            Modifier.border(3.dp, if (isSelected) Color(0xFFFFC107) else Color(0xFF8BC34A))
                                        } else Modifier
                                    )
                                    // Light squares never hold a piece and an occupied dark square is
                                    // announced by its piece (which carries the description), so only an
                                    // EMPTY dark square is announced here: a legal destination is a real
                                    // button; any other empty square just clears the selection (no click
                                    // semantics, nothing to announce as actionable). An occupied square
                                    // keeps a silent tap so a touch in the 4dp rim around its piece
                                    // (outside the piece's circle) still reaches onSquareTapped, as it
                                    // did before the piece became the semantic target.
                                    .then(
                                        when {
                                            !isDark -> Modifier
                                            squarePiece != null -> Modifier.silentTap { onSquareTapped(row, col) }
                                            isDestination -> Modifier
                                                // Mouse/trackpad hover cursor (§4c) -- no effect on touch input.
                                                .pointerHoverIcon(PointerIcon.Hand)
                                                .clickable(onClickLabel = "Move piece here", role = Role.Button) { onSquareTapped(row, col) }
                                                .semantics { contentDescription = squareDescription(null, true, displayRow, displayCol) }
                                            else -> Modifier
                                                .silentTap { onSquareTapped(row, col) }
                                                .semantics { contentDescription = squareDescription(null, false, displayRow, displayCol) }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isDestination) {
                                    // A shape cue (centre dot) so "legal destination" never rides on
                                    // the green border alone; colorblind mode adds a dark outline.
                                    Box(
                                        modifier = Modifier
                                            .size(cellSize * 0.3f)
                                            .clip(CircleShape)
                                            .background(DESTINATION_DOT)
                                            .then(
                                                if (colorblind) Modifier.border(2.dp, DESTINATION_DOT_OUTLINE, CircleShape) else Modifier
                                            )
                                    )
                                }
                            }
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
                            // deriveIds only ever returns squares s.board still has a piece on
                            // (see its own KDoc), so this is never actually null.
                            val piece = s.pieceAt(row, col)!!
                            val displayRow = if (rotated) 7 - row else row
                            val displayCol = if (rotated) 7 - col else col
                            val isSelectedPiece = selected == (row to col)
                            val isWinnerPiece = humanWon && winnerSide != null && piece.owner == winnerSide
                            // Only the current human player's own pieces are buttons (and not
                            // while a forced jump pins the choice); everything else is described
                            // but not offered as an action.
                            val pieceActionable = isHumanTurn && !s.inForcedContinuation && piece.owner == s.currentPlayerIndex
                            val pieceState = when {
                                isSelectedPiece && s.inForcedContinuation -> "Selected, must jump again"
                                isSelectedPiece -> "Selected"
                                pieceActionable && square in movableSquares -> "Can move"
                                pieceActionable -> "No moves"
                                else -> null
                            }
                            key(id) {
                                PieceView(
                                    piece = piece,
                                    row = row,
                                    col = col,
                                    fromRow = fromRow,
                                    fromCol = fromCol,
                                    rotated = rotated,
                                    cellSize = cellSize,
                                    selected = isSelectedPiece,
                                    actionable = pieceActionable,
                                    colorblind = colorblind,
                                    description = squareDescription(piece, false, displayRow, displayCol),
                                    stateText = pieceState,
                                    card3D = card3D,
                                    enhanced = enhanced,
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
                                CapturedGhostView(
                                    ghost = ghost,
                                    cellSize = cellSize,
                                    rotated = rotated,
                                    enhanced = enhanced,
                                    moveProgress = moveProgress
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // "Finished units" for the abort policy: rounds already won/lost this session (Checkers has
    // no draws). If any exist, leaving mid-round must still score them -- see onAbort below.
    val finishedRounds = game.scoreP1.value + game.scoreP2.value
    val helpText = (
        "Tap one of your pieces, then a highlighted square. " +
            (if (hasBot) "You play Dark, at the bottom; the CPU plays Light and moves first." else "Light moves first.") +
            " Pieces step one square diagonally forward, and a piece that reaches the far row becomes " +
            "a king, which can step and jump diagonally backward too.\n\n" +
            "Jumping is mandatory: if any of your pieces can jump an opponent's piece, you must make a jump, " +
            "and if the same piece can jump again it must keep going (a piece that has just been crowned stops there).\n\n" +
            "You win by capturing every opposing piece or leaving your opponent with no legal move. There are no draws."
        )

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-round
    // discards ONLY the unfinished round: if earlier rounds in this session were already won or
    // lost, leaving goes through leaveSession() so those results still count (the same call the
    // result panel's "Back to Menu" makes); with nothing finished it is a pure abort (never a win
    // or loss). A finished round always leaves through leaveSession(), which scores the session.
    GameChrome(
        helpTitle = "How to Play Checkers",
        helpText = helpText,
        matchInProgress = !s.gameOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRounds > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = MaterialTheme.colorScheme.background,
        buttonContent = MaterialTheme.colorScheme.onBackground,
        leaveTitle = "Leave this game?",
        leaveBody = if (finishedRounds > 0) {
            "This round is still in progress and won't count, but the rounds you've already finished stay on your record."
        } else {
            "This game is still in progress. Leaving now won't count it as a win or a loss."
        },
        extraItems = { dismiss ->
            // The Standard/Maximum/Off motion picker (see CheckersMotionTier) lives in the
            // corner menu rather than as a row of chips over the board.
            CheckersMotionTier.entries.forEach { tier ->
                val tierName = tier.name.lowercase().replaceFirstChar { it.uppercase() }
                DropdownMenuItem(
                    text = { Text(if (motionTierPref == tier) "Motion: $tierName (current)" else "Motion: $tierName") },
                    onClick = {
                        dismiss()
                        scope.launch { prefsStore.setMotionTier(tier) }
                    }
                )
            }
        }
    ) {
        if (s.gameOver && showResultsScreen) {
            val p1Name = ctx.players.getOrNull(0)?.displayName ?: "Player 1"
            val p2Name = ctx.players.getOrNull(1)?.displayName ?: "Player 2"
            val scoreP1 by game.scoreP1
            val scoreP2 by game.scoreP2
            val resultTitle = when {
                winnerPlayer == null -> "It's a tie!"
                winnerPlayer.displayName == "You" -> "You win!"
                else -> "${winnerPlayer.displayName} wins!"
            }
            CheckersResultPanel(
                title = resultTitle,
                scoreLine = "$p1Name: $scoreP1 · $p2Name: $scoreP2",
                onPlayAgain = game::playAgain,
                onBackToMenu = game::leaveSession
            )
        } else {
            // Wired into AdaptiveTwoPane (secondary = null -- Checkers has no natural "hand"
            // pane) so a Tab S9 or a fully-unfolded Fold 5 in landscape (TABLET mode) caps the
            // board's width at 840dp instead of stretching it across the whole window, matching
            // every other game in the suite. Inside primary, an aspect check reflows the chrome
            // BESIDE the board (Row) rather than above it (Column) whenever the window is wider
            // than it is tall -- the tightest real case being the Fold 5 cover screen rotated to
            // landscape (~344dp tall) -- and in both arrangements the board's own
            // BoxWithConstraints sits in a weight(1f) slot so it receives a REAL bounded/reduced
            // maxHeight (the space actually left after the chrome), not the whole pane's height
            // as if the chrome took none of it.
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
                                        .verticalScroll(rememberScrollState()),
                                    false
                                )
                                Spacer(Modifier.width(16.dp))
                                // End inset: the corner menu button sits at this slot's top-right.
                                boardBlock(Modifier.weight(1f).fillMaxHeight().padding(end = GameChromeEndInset))
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                chromeBlock(Modifier.fillMaxWidth(), true)
                                Spacer(Modifier.height(8.dp))
                                boardBlock(Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                }
            )
        }
    }
}

/** The round-over panel: title, running session score, Play Again / Back to Menu. Rendered
 *  inside [GameChrome], so the corner menu and back handling stay available. */
@Composable
private fun CheckersResultPanel(title: String, scoreLine: String, onPlayAgain: () -> Unit, onBackToMenu: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(8.dp))
        Text(scoreLine, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        // In-screen game control buttons (§4c) — hover cursor only, additive.
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

/** Plain holder for the last non-null session context -- see the `contextMemory` comment in
 *  [CheckersScreen]. */
private class CheckersContextMemory { var last: GameContext? = null }

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

/**
 * A tap handler that adds NO click semantics (no role, no "double tap to activate"): used for
 * things that must still react to a tap -- tapping an empty square or an opponent's piece clears
 * the selection -- but are not actions a screen-reader user should be offered. Reads the latest
 * [onTap] so the handler never goes stale between recompositions.
 */
@Composable
private fun Modifier.silentTap(onTap: () -> Unit): Modifier {
    val latestOnTap by rememberUpdatedState(onTap)
    return this.pointerInput(Unit) { detectTapGestures(onTap = { latestOnTap() }) }
}

@Composable
private fun PieceView(
    piece: CheckersPiece,
    row: Int,
    col: Int,
    fromRow: Int,
    fromCol: Int,
    rotated: Boolean,
    cellSize: Dp,
    selected: Boolean,
    actionable: Boolean,
    colorblind: Boolean,
    description: String,
    stateText: String?,
    card3D: Boolean,
    enhanced: Boolean,
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
    //
    // The progress value is read ONLY inside the offset/graphicsLayer lambdas below (layout /
    // draw phase), never here in composition -- reading it here recomposed every piece on every
    // frame of the glide. The columns/rows are DRAWN positions (rotated when the board is).
    val isMoving = fromRow != row || fromCol != col
    val fromX = (if (rotated) 7 - fromCol else fromCol).toFloat()
    val fromY = (if (rotated) 7 - fromRow else fromRow).toFloat()
    val toX = (if (rotated) 7 - col else col).toFloat()
    val toY = (if (rotated) 7 - row else row).toFloat()

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

    // Selection lift: 8% over 120ms, an instant snap whenever `enhanced` is off (which includes
    // reduced motion). Read inside the graphicsLayer lambda, so it never recomposes the piece.
    val selectScale by animateFloatAsState(
        targetValue = if (selected) SELECTED_PIECE_SCALE else 1f,
        animationSpec = if (enhanced) tween(SELECT_SCALE_MS) else snap(),
        label = "checkers-select-scale"
    )

    // Gameplay colors are fixed literals per AppTheme.kt's documented rule --
    // never MaterialTheme.colorScheme here. Shared with CapturedPieceTray below
    // so its swatches visually match these real pieces exactly.
    val pieceColor = if (piece.owner == 0) DARK_PIECE_COLOR else LIGHT_PIECE_COLOR
    val ringColor = if (piece.owner == 0) DARK_PIECE_RING else LIGHT_PIECE_RING

    Box(
        modifier = Modifier
            .offset {
                val p = moveProgress.value
                val cellPx = cellSize.toPx()
                IntOffset(
                    (cellPx * (fromX + (toX - fromX) * p)).roundToInt(),
                    (cellPx * (fromY + (toY - fromY) * p)).roundToInt()
                )
            }
            .size(cellSize)
            .padding(4.dp)
            // One layer for every scale effect (they multiply):
            //  - lift/scale/shadow "hop" flourish, gated behind `enhanced` AND only for the
            //    piece actually moving this turn (isMoving): a stationary piece's glide is
            //    already a no-op, and without the isMoving guard it would still visibly bob in
            //    place every time ANY other piece moves, since moveProgress is shared across the
            //    whole board. All three effects share one sin() hop shape (0 at both ends,
            //    peaking at the midpoint) so they read as one coherent motion;
            //  - round-over bounce-pulse (premium-2026-vision pass): a brief, decaying scale
            //    oscillation on the human winner's surviving pieces only, synchronized to the
            //    same roundEndProgress clock the board container's own fade/scale-down reads.
            //    The sin(...)*(1-p) envelope is exactly 0 at both p=0 and p=1, so an instant
            //    OFF-tier snap straight to p=1 never shows a stray pop;
            //  - the selection lift above.
            .graphicsLayer {
                var scale = selectScale
                if (enhanced && isMoving) {
                    val hop = sin(moveProgress.value.coerceIn(0f, 1f) * PI.toFloat())
                    translationY = -(12.dp.toPx()) * hop
                    scale *= 1f + 0.08f * hop
                    shadowElevation = 6.dp.toPx() * hop
                    shape = CircleShape
                }
                if (bouncePulse) {
                    val p = roundEndProgress.value
                    val bounce = sin(p * PI.toFloat() * 2.5f) * (1f - p)
                    scale *= 1f + 0.22f * bounce
                }
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(pieceColor)
            .border(if (selected) 3.dp else 2.dp, if (selected) Color(0xFFFFC107) else ringColor, CircleShape)
            .then(if (showPromotionFlip) Modifier.card3DFlip(flipProgress.value) else Modifier)
            // Pieces the player may act on are real buttons (hover cursor, click label, role);
            // every other piece is described but not offered as an action, and a tap on it
            // still reaches onClick (clears the selection) without click semantics.
            .then(
                if (actionable) {
                    Modifier
                        // Mouse/trackpad hover cursor (§4c) — tappable/selectable piece; no
                        // effect on touch input.
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            onClickLabel = if (selected) "Deselect piece" else "Select piece",
                            role = Role.Button,
                            onClick = onClick
                        )
                } else {
                    Modifier.silentTap(onClick)
                }
            )
            .semantics {
                contentDescription = description
                if (stateText != null) stateDescription = stateText
            },
        contentAlignment = Alignment.Center
    ) {
        // card3DFlip's documented contract: swap the rendered face at the halfway
        // point where the piece is edge-on -- below that, still show a plain man
        // (about to promote); at/after it, the crown.
        val showCrown = if (showPromotionFlip) flipProgress.value >= 0.5f else piece.kind == PieceKind.KING
        if (showCrown) {
            // A drawn gold crown shared by BOTH sides, with a dark outline so it also reads
            // on the light pieces (gold on near-white was ~2:1 as a font glyph). Decorative:
            // the piece's own description already says "king".
            CheckersCrownGlyph(glyphSize = cellSize * 0.5f)
        }
        if (selected && colorblind) {
            // Colorblind mode: selection is also a second, inner ring (a shape cue), not only
            // the amber outline.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .border(2.dp, if (piece.owner == 0) Color.White else Color.Black, CircleShape)
            )
        }
    }
}

/** A drawn king's crown (gold fill, dark outline), [glyphSize] square. Pure decoration. */
@Composable
private fun CheckersCrownGlyph(glyphSize: Dp) {
    Box(
        modifier = Modifier
            .size(glyphSize)
            .clearAndSetSemantics {}
            .drawBehind {
                val w = size.width
                val h = size.height
                val crown = Path().apply {
                    moveTo(w * 0.08f, h * 0.86f)
                    lineTo(w * 0.04f, h * 0.30f)
                    lineTo(w * 0.28f, h * 0.55f)
                    lineTo(w * 0.50f, h * 0.12f)
                    lineTo(w * 0.72f, h * 0.55f)
                    lineTo(w * 0.96f, h * 0.30f)
                    lineTo(w * 0.92f, h * 0.86f)
                    close()
                }
                drawPath(crown, color = KING_CROWN_GOLD)
                drawPath(crown, color = KING_CROWN_OUTLINE, style = Stroke(width = (glyphSize.toPx() * 0.07f).coerceAtLeast(1f)))
            }
    )
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
private fun CapturedGhostView(
    ghost: CapturedGhost,
    cellSize: Dp,
    rotated: Boolean,
    enhanced: Boolean,
    moveProgress: Animatable<Float, AnimationVector1D>
) {
    val threshold = ghost.hopIndex.toFloat() / ghost.hopCount
    val fade = remember(ghost) { Animatable(1f) }
    LaunchedEffect(ghost) {
        snapshotFlow { moveProgress.value }.first { it >= threshold }
        if (enhanced) fade.animateTo(0f, animationSpec = tween(220)) else fade.snapTo(0f)
    }
    if (fade.value <= 0f) return

    val ghostRow = ghost.square / 8
    val ghostCol = ghost.square % 8
    val displayRow = if (rotated) 7 - ghostRow else ghostRow
    val displayCol = if (rotated) 7 - ghostCol else ghostCol
    val pieceColor = if (ghost.piece.owner == 0) DARK_PIECE_COLOR else LIGHT_PIECE_COLOR
    val ringColor = if (ghost.piece.owner == 0) DARK_PIECE_RING else LIGHT_PIECE_RING
    Box(
        modifier = Modifier
            .offset(x = cellSize * displayCol, y = cellSize * displayRow)
            .size(cellSize)
            .padding(4.dp)
            .graphicsLayer {
                alpha = fade.value
                scaleX = fade.value
                scaleY = fade.value
            }
            .clip(CircleShape)
            .background(pieceColor)
            .border(2.dp, ringColor, CircleShape)
            // A vanishing visual only; the board's real pieces carry the semantics.
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        if (ghost.piece.kind == PieceKind.KING) {
            CheckersCrownGlyph(glyphSize = cellSize * 0.5f)
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
// match, not just visually approximate them. The dark piece's rim is a light grey
// (it was 0xFF616161) so a dark piece still separates from the dark wood square it sits on.
private val DARK_PIECE_COLOR = Color(0xFF212121)
private val DARK_PIECE_RING = Color(0xFFAAAAAA)
private val LIGHT_PIECE_COLOR = Color(0xFFFAFAFA)
private val LIGHT_PIECE_RING = Color(0xFFBDBDBD)

// King crown: one warm gold for both sides, plus a dark outline so it reads on a light piece.
private val KING_CROWN_GOLD = Color(0xFFD4AF37)
private val KING_CROWN_OUTLINE = Color(0xFF5D4037)

// The ONE shared sheen drifting across the whole grid at the Maximum motion tier.
private val BOARD_SHEEN = Color(0x4DFFF0D8)

// Legal-destination centre dot (a shape cue alongside the green square border) and the dark
// outline it gains in colorblind mode.
private val DESTINATION_DOT = Color(0xE68BC34A)
private val DESTINATION_DOT_OUTLINE = Color(0xFF1B1B1B)

// Felt surround (premium-2026-vision pass) -- a static, matte radial gradient (no
// specularSweep, see this file's own KDoc for why felt stays matte) framing the grid.
private val FELT_SURROUND_BRUSH = Brush.radialGradient(colors = listOf(Color(0xFF1B5E20), Color(0xFF0B3D14)))

/** 12 men per side at [CheckersGame.startMatch] -- captured count is just this minus
 *  however many of that owner's pieces [CheckersState.board] still has on it. */
private const val STARTING_PIECES_PER_SIDE = 12

/** Felt frame around the 8x8 grid, per side, in dp. Handed to fitBoard as its `framePx`. */
private const val FELT_PADDING_DP = 14f

/** Board-fit hint, in dp. NOT a floor: [fitBoard] only reports `meetsMinimum`; below this the
 *  board holds the cell at this size and scrolls rather than overflowing its container (the old
 *  48dp floor made a 312dp pane clip its last column). A grid cell is exempt from the 48dp
 *  control rule; a tap target of about 28dp+ is what an 8x8 board can honestly offer. */
private const val MIN_CELL_DP = 28f

/** Largest cell, in dp, so the board doesn't sprawl on a tablet. (8 * 72 + felt = 604dp.) */
private const val MAX_CELL_DP = 72f

/** Selected piece scale, and how long the lift takes (snaps when motion is off). */
private const val SELECTED_PIECE_SCALE = 1.08f
private const val SELECT_SCALE_MS = 120

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
 * Pure derived-state readout, no engine changes: how many of each side's starting 12
 * pieces are gone, one swatch + count per side (a full swatch row per captured piece
 * overflowed a 312dp pane once about five were gone). Sits directly above the board in
 * [CheckersScreen] (see call site) rather than docked to the screen edge, since with only
 * two players there's no need for a persistent scoreboard chrome -- this is closer to a
 * glance-able detail than a HUD.
 */
@Composable
private fun CapturedPieceTray(darkCaptured: Int, lightCaptured: Int, stacked: Boolean, modifier: Modifier = Modifier) {
    if (stacked) {
        // The narrow landscape side column (220dp, maybe larger text): one line per item.
        Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Captured", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clearAndSetSemantics {})
            CapturedSideRow(label = "Dark", count = darkCaptured, pieceColor = DARK_PIECE_COLOR, ringColor = DARK_PIECE_RING)
            CapturedSideRow(label = "Light", count = lightCaptured, pieceColor = LIGHT_PIECE_COLOR, ringColor = LIGHT_PIECE_RING)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Captured", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clearAndSetSemantics {})
            CapturedSideRow(label = "Dark", count = darkCaptured, pieceColor = DARK_PIECE_COLOR, ringColor = DARK_PIECE_RING)
            CapturedSideRow(label = "Light", count = lightCaptured, pieceColor = LIGHT_PIECE_COLOR, ringColor = LIGHT_PIECE_RING)
        }
    }
}

@Composable
private fun CapturedSideRow(label: String, count: Int, pieceColor: Color, ringColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$label captured: $count" }
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(pieceColor)
                .border(1.dp, ringColor, CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text("$label ×$count", style = MaterialTheme.typography.labelSmall)
    }
}

private fun sideName(owner: Int): String = if (owner == 0) "Dark" else "Light"

/**
 * "<what is there>, row r, column c" with 1-indexed row/column numbers as DRAWN (so they follow
 * the board's rotation). [piece] non-null describes that piece ("Dark man", "Light king");
 * otherwise an empty square, or a legal destination. Reversi's cells use the same phrasing.
 */
private fun squareDescription(piece: CheckersPiece?, isDestination: Boolean, displayRow: Int, displayCol: Int): String {
    val base = when {
        piece != null -> "${sideName(piece.owner)} ${if (piece.kind == PieceKind.KING) "king" else "man"}"
        isDestination -> "Legal destination"
        else -> "Empty square"
    }
    return "$base, row ${displayRow + 1}, column ${displayCol + 1}"
}
