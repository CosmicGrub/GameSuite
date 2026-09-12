package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.cards.card3DFlip
import com.gamesuite.games.chess.ChessGame
import com.gamesuite.games.chess.ChessMotionTier
import com.gamesuite.games.chess.ChessPieceStyle
import com.gamesuite.games.chess.ChessPrefsStore
import com.gamesuite.games.chess.ChessResult
import com.gamesuite.games.chess.Piece
import com.gamesuite.games.chess.PieceColor
import com.gamesuite.games.chess.PieceType
import com.gamesuite.games.chess.capturedPiecesFor
import com.gamesuite.games.chess.materialAdvantageForWhite
import com.gamesuite.games.chess.playerIndexForColor
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
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mirrors MancalaScreen's shape exactly: two LaunchedEffects (one on `context`
 * to init/startMatch, one on `state` to trigger the bot's turn after a UI-layer
 * delay -- bot timing is always a screen concern, never the engine's), early-
 * return guards right after them, a plain-Column round-over panel (no
 * Scaffold/TopAppBar/BackHandler anywhere in this app), and touch input that
 * calls [ChessGame.playMove] directly (which silently rejects illegal calls) --
 * cheap UI-side checks here only gate which squares LOOK enabled/highlighted.
 *
 * Board orientation is fixed with White's home rank at the bottom (matching
 * the ESP32 firmware's own ChessDisplay.cpp convention of "a human player
 * expects to see their own side" -- the same rank flip, `7 - row`, is used
 * here) rather than rotating per player, matching how every other GameSuite
 * board game already renders one fixed board for pass-and-play instead of
 * flipping it between turns.
 *
 * ANIMATION: unlike the ESP32 firmware (real hardware constraints forced
 * manual sprite/redraw tricks there), this uses a single [Animatable] offset
 * per moved piece (plus a second one for the rook on a castling move),
 * re-created fresh every time `state.lastFrom`/`lastTo` changes and animated
 * from the old square's screen position to the new one -- an overshoot-then-
 * settle spring rather than a flat linear tween whenever `enhanced` motion is
 * on (see the "PREMIUM 2026" section below), snapping instantly under reduced
 * motion. Any square not involved in the move renders its piece statically; a
 * captured piece (including an en-passant capture) fades/scales out via a
 * transient overlay instead of disappearing the instant the board updates --
 * see the "QUICK-WIN ADDITIONS" paragraph below. Legal destination squares
 * are highlighted whenever a piece is selected, the same "wanted feature"
 * the ESP32 version's own two-tap flow added (see ChessLogic.h's
 * legalDestinations()).
 *
 * QUICK-WIN ADDITIONS (gated by `card3D` = [LocalCard3DMode] && ![LocalReducedMotion]):
 * a captured piece (including an en-passant capture, whose square is derived by diffing
 * boards rather than disappearing with the rest of the static render -- see
 * [findCapturedSquare]) now stays rendered for a short beat and fades/scales out instead of
 * vanishing the instant the board recomposes; a pawn promoting plays a real [card3DFlip]
 * reveal (swapping pawn-face for promoted-piece-face at the documented progress >= 0.5f
 * halfway point) instead of an instant swap; and the board's own container gets a resting
 * [tablePerspectiveTilt]. All three fall back to today's plain instant-swap/no-tilt behavior
 * whenever 3D mode or reduced motion is off.
 *
 * PREMIUM 2026 (this pass): real vector piece silhouettes in place of the old monogram-letter
 * token, in either of two persisted [ChessPieceStyle]s ([buildPieceSilhouette]); a
 * captured-piece tray + material-differential readout ([CapturedPieceTray], computed via
 * [capturedPiecesFor]/[materialAdvantageForWhite] -- zero new persisted state, purely a board
 * diff against the standard starting position); a "Position Balance" bar wired to
 * [ChessGame.positionBalance] ([PositionBalanceBar] -- explicitly NOT labeled an engine
 * evaluation, see that function's KDoc); the full [HapticSignal] vocabulary plus layered
 * [CardSounds] playback at every move/check/capture/checkmate/stalemate; a selected piece's
 * small scale-up + growing soft drop-shadow ([selectionPulse]); a cached, procedurally-drawn
 * wood-grain board surface ([generateWoodGrainBitmap]) with an optional specular sheen; a real
 * checkmate "moment" (a brief hit-stop + camera-push + an attack-line from the mating piece to
 * the king, see [ChessGame.checkingPieceSquares]); an idle highlight pulse after ~25s of no
 * move; and a genuine Standard/Maximum motion-intensity tier ([ChessMotionTier], persisted via
 * [ChessPrefsStore]) layered on top of the app-wide enhanced-animations/reduced-motion gates --
 * see each piece of state below for exactly which bucket it falls into and why.
 */
@Composable
fun ChessScreen(
    sessionManager: GameSessionManager,
    game: ChessGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    val card3D = LocalCard3DMode.current && !reducedMotion
    // The app-wide "new juice/new motion" gate every screen already uses, per the shared infra
    // convention -- Chess's own Standard/Maximum tier (below) narrows this further, it never
    // widens it: nothing new plays with enhanced animations off, tier setting notwithstanding.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion

    val androidContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val sounds = remember { CardSounds.get(androidContext) }
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CHESS, enabled = musicEnabled)
    val prefsStore = remember { ChessPrefsStore(androidContext) }
    val pieceStyle by prefsStore.pieceStyle.collectAsState(initial = ChessPieceStyle.CLASSIC)
    val motionTier by prefsStore.motionTier.collectAsState(initial = ChessMotionTier.STANDARD)
    // Maximum specifically arms the checkmate hit-stop/camera-push and the board sheen (see
    // ChessMotionTier's own KDoc) -- everything else new (haptics, layered sound, the piece
    // lift/shadow, the overshoot spring) plays whenever `enhanced` is on regardless of tier,
    // since those already have their own dedicated global toggles (Settings -> Haptics,
    // Settings -> Sound) and double-gating the same "do you want feedback" choice behind a
    // second, game-local switch would just be confusing.
    val maximum = enhanced && motionTier == ChessMotionTier.MAXIMUM

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

    var selectedSquare by remember { mutableStateOf<Int?>(null) }

    // Selected-piece "material response": a small scale-up plus a growing soft drop-shadow
    // (see ChessPieceToken's liftScale/shadowAlpha params) instead of the flat highlight tint
    // alone. A single Animatable shared by whichever square is currently selected.
    val selectionPulse = remember { Animatable(0f) }
    LaunchedEffect(selectedSquare, enhanced) {
        if (selectedSquare != null && enhanced) {
            selectionPulse.animateTo(1f, animationSpec = tween(160))
        } else {
            selectionPulse.snapTo(0f)
        }
    }

    // Keyed on the whole state object (not just sideToMove/roundOver) so this relaunches on
    // every move -- mirrors MancalaScreen's identical note: sow()/applyPlayedMove() always
    // produce a genuinely new state object, so re-running this on every change is safe and
    // correct. Also clears the current selection on every move (the mover's own move, the
    // opponent's, or the bot's) since a stale selectedSquare from before the move may no
    // longer even hold the same piece.
    LaunchedEffect(state) {
        selectedSquare = null
        val s = state ?: return@LaunchedEffect
        val ctx = context ?: return@LaunchedEffect
        if (s.roundOver) return@LaunchedEffect
        if (ctx.players.getOrNull(playerIndexForColor(s.sideToMove))?.isBot == true) {
            delay(700)
            game.playBotTurn()
        }
    }

    val s = state ?: return
    val ctx = context ?: return

    val isCheckmate = s.result == ChessResult.WHITE_WINS || s.result == ChessResult.BLACK_WINS

    // Checkmate as a real moment, not the same shared dialog stalemate gets: hold the board on
    // screen for a short beat (Maximum tier layers a hit-stop + camera-push into that beat, see
    // the checkmatePush Animatable below) before handing off to the round-over panel. Stalemate
    // and reduced motion both skip straight to the panel, matching today's instant behavior.
    var revealRoundOverPanel by remember { mutableStateOf(false) }
    val checkmatePush = remember { Animatable(1f) }
    LaunchedEffect(s.roundOver, isCheckmate) {
        if (!s.roundOver) {
            revealRoundOverPanel = false
            checkmatePush.snapTo(1f)
            return@LaunchedEffect
        }
        if (!isCheckmate || reducedMotion) {
            revealRoundOverPanel = true
            return@LaunchedEffect
        }
        if (maximum) {
            checkmatePush.snapTo(1f)
            delay(70) // hit-stop: a brief freeze before the camera reacts
            checkmatePush.animateTo(1.06f, animationSpec = tween(160))
            delay(180)
            checkmatePush.animateTo(1f, animationSpec = tween(180))
            delay(120)
        } else {
            delay(420) // Standard tier: no hit-stop/push, but still a beat to see the mate
        }
        revealRoundOverPanel = true
    }

    if (s.roundOver && revealRoundOverPanel) {
        val winner = ctx.players.firstOrNull { it.playerId == s.winnerPlayerId }
        val whiteName = ctx.players.getOrNull(0)?.displayName ?: "White"
        val blackName = ctx.players.getOrNull(1)?.displayName ?: "Black"
        val scoreP1 by game.scoreP1
        val scoreP2 by game.scoreP2
        val draws by game.draws
        val headline = when (s.result) {
            ChessResult.WHITE_WINS, ChessResult.BLACK_WINS -> "${winner?.displayName ?: "?"} wins by checkmate!"
            ChessResult.DRAW_STALEMATE -> "Draw by stalemate"
            ChessResult.DRAW_REPETITION -> "Draw by repetition"
            ChessResult.IN_PROGRESS -> ""
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(headline, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "$whiteName: $scoreP1 · $blackName: $scoreP2" + if (draws > 0) " · Draws: $draws" else "",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = game::playAgain,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) { Text("Play Again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = game::leaveSession,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) { Text("Back to Menu") }
        }
        return
    }

    val currentPlayerIndex = playerIndexForColor(s.sideToMove)
    val isHumanTurn = ctx.players.getOrNull(currentPlayerIndex)?.isBot != true
    val legalDestinations = selectedSquare?.let { if (isHumanTurn) game.legalDestinationsFor(it) else emptySet() } ?: emptySet()
    val checkedKingSquare = if (s.inCheck) s.board.indexOfFirst { it?.type == PieceType.KING && it.color == s.sideToMove } else -1

    // Turn status / piece-style + motion-tier pickers / captured tray / balance bar -- reused
    // as-is by both the portrait (stacked above the board) and landscape (beside the board)
    // arrangements below, so these existing controls stay reachable and are never the thing
    // silently eating the vertical budget a short window needs for the board itself.
    val chromeBlock: @Composable (Modifier) -> Unit = { chromeModifier ->
        Column(modifier = chromeModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            val sideName = if (s.sideToMove == PieceColor.WHITE) "White" else "Black"
            // The round-over PANEL already distinguishes "wins by checkmate!" from "Draw by
            // stalemate" above (confirmed before touching this -- it already did). This is this
            // screen's own transient title during the brief "moment" window where roundOver is
            // true but the panel hasn't taken over yet.
            val titleText = when {
                s.roundOver && isCheckmate -> "Checkmate!"
                s.roundOver -> "Stalemate"
                isHumanTurn -> "Your turn ($sideName) — tap a piece"
                else -> "Opponent's turn ($sideName)"
            }
            Text(titleText, style = MaterialTheme.typography.titleMedium)
            Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
            if (ctx.players.any { it.isBot }) {
                Text(
                    "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pieces:", style = MaterialTheme.typography.labelSmall)
                TextButton(
                    onClick = {
                        val next = if (pieceStyle == ChessPieceStyle.CLASSIC) ChessPieceStyle.MINIMALIST else ChessPieceStyle.CLASSIC
                        scope.launch { prefsStore.setPieceStyle(next) }
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(if (pieceStyle == ChessPieceStyle.CLASSIC) "Classic" else "Minimalist")
                }
                Spacer(Modifier.width(8.dp))
                Text("Motion:", style = MaterialTheme.typography.labelSmall)
                TextButton(
                    onClick = {
                        val next = if (motionTier == ChessMotionTier.STANDARD) ChessMotionTier.MAXIMUM else ChessMotionTier.STANDARD
                        scope.launch { prefsStore.setMotionTier(next) }
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(if (motionTier == ChessMotionTier.STANDARD) "Standard" else "Maximum")
                }
            }

            Spacer(Modifier.height(4.dp))
            CapturedPieceTray(board = s.board, pieceStyle = pieceStyle)
            Spacer(Modifier.height(6.dp))
            PositionBalanceBar(balance = game.positionBalance())
        }
    }

    // The board itself. Sized from BOTH the available width AND height (never width alone --
    // this was the one clear "classic bug" instance in this bundle: squareSize used to be
    // derived from maxWidth alone, which stretched/overflowed in any orientation where height
    // was the tighter dimension), with squareSize floored to MIN_TOUCH_TARGET so an 8x8 grid
    // never produces an untappable square regardless of how little space is actually available.
    val boardBlock: @Composable (Modifier) -> Unit = { boardModifier ->
        BoxWithConstraints(modifier = boardModifier, contentAlignment = Alignment.Center) {
            val squareSize = (minOf(maxWidth, maxHeight) / 8).coerceIn(MIN_TOUCH_TARGET, 64.dp)
            val boardSize = squareSize * 8
            val squarePx = with(LocalDensity.current) { squareSize.toPx() }

            val moveKey = s.lastFrom to s.lastTo
            val rookSquares = castleRookSquares(s.lastFrom, s.lastTo, s.board.getOrNull(s.lastTo ?: -1))
            val animatedSquares = setOfNotNull(s.lastTo, rookSquares?.second)

            // Move landing: an overshoot-then-settle spring once `enhanced` is on, instead of a
            // flat linear tween -- makes a completed move feel like it actually lands rather
            // than just arriving. Snaps instantly under reduced motion, matching every other
            // animation in this file.
            val landingSpec: AnimationSpec<Offset> = when {
                reducedMotion -> snap()
                enhanced -> spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                else -> tween(300)
            }

            val moverAnim = remember(moveKey) { Animatable(offsetPxFor(s.lastFrom ?: s.lastTo ?: 0, squarePx), Offset.VectorConverter) }
            LaunchedEffect(moveKey) {
                if (s.lastFrom != null && s.lastTo != null) {
                    moverAnim.snapTo(offsetPxFor(s.lastFrom, squarePx))
                    moverAnim.animateTo(offsetPxFor(s.lastTo, squarePx), animationSpec = landingSpec)
                }
            }
            val rookAnim = remember(moveKey) {
                Animatable(offsetPxFor(rookSquares?.first ?: 0, squarePx), Offset.VectorConverter)
            }
            LaunchedEffect(moveKey) {
                if (rookSquares != null) {
                    rookAnim.snapTo(offsetPxFor(rookSquares.first, squarePx))
                    rookAnim.animateTo(offsetPxFor(rookSquares.second, squarePx), animationSpec = landingSpec)
                }
            }

            // `boardMemory` holds the board as it stood immediately before the CURRENT move --
            // plain non-Compose-state storage, read then overwritten inside a single
            // `remember(moveKey)` calculation (the same one-shot-per-key idiom the Animatables
            // above already use), so it never itself triggers recomposition.
            val boardMemory = remember { BoardMemory() }
            val moveDiff = remember(moveKey) {
                val prevBoard = boardMemory.previous
                val diff = if (prevBoard != null && s.lastFrom != null && s.lastTo != null) {
                    val captured = findCapturedSquare(prevBoard, s.board, s.lastFrom, s.lastTo)?.let { sq ->
                        prevBoard.getOrNull(sq)?.let { CapturedPieceInfo(it, sq) }
                    }
                    val before = prevBoard.getOrNull(s.lastFrom)
                    val after = s.board.getOrNull(s.lastTo)
                    val promotion = if (before?.type == PieceType.PAWN && after != null &&
                        after.type != PieceType.PAWN && after.color == before.color
                    ) {
                        PromotionInfo(before)
                    } else null
                    MoveDiff(captured, promotion)
                } else {
                    MoveDiff(captured = null, promotion = null)
                }
                boardMemory.previous = s.board
                diff
            }
            val capturedInfo = moveDiff.captured
            val promotionInfo = moveDiff.promotion

            // Captured piece: stays fully visible for a short beat, then fades and shrinks away
            // instead of vanishing the instant the board recomposes.
            val captureFade = remember(moveKey) { Animatable(1f) }
            LaunchedEffect(moveKey) {
                if (capturedInfo != null) {
                    captureFade.snapTo(1f)
                    if (reducedMotion) {
                        captureFade.snapTo(0f)
                    } else {
                        delay(120)
                        captureFade.animateTo(0f, animationSpec = tween(180))
                    }
                }
            }

            // Promotion reveal: a real card3DFlip through progress 0..1, gated on `card3D` --
            // starts (and stays) at 1f otherwise so the render below's face-swap check below
            // never trips and today's plain instant swap keeps happening unmodified.
            val flipProgress = remember(moveKey) { Animatable(if (promotionInfo != null && card3D) 0f else 1f) }
            LaunchedEffect(moveKey) {
                if (promotionInfo != null && card3D) {
                    flipProgress.snapTo(0f)
                    flipProgress.animateTo(1f, animationSpec = tween(250))
                }
            }

            // Full haptic vocabulary + layered sound for every move: check/mate/capture/quiet
            // all feel and sound distinct (see Haptics.kt's HapticSignal KDoc for what each
            // bucket means). Sound is layered by staggering 2-3 calls to CardSounds' EXISTING
            // clips rather than a single fixed one, so a quiet move alternates between two
            // clips, a capture layers two in quick succession for a heavier thud, and
            // checkmate gets a short 3-note staggered fanfare -- CardSounds.kt itself is out of
            // this bundle's file list, so true per-play pitch/rate jitter isn't reachable here;
            // distinct-clip layering is this pass's stand-in for that same "not the same sound
            // every time" goal.
            LaunchedEffect(moveKey) {
                if (s.lastFrom == null || s.lastTo == null) return@LaunchedEffect
                when {
                    isCheckmate -> {
                        haptics(HapticSignal.CELEBRATION)
                        sounds.playPlace()
                        delay(90)
                        sounds.playDraw()
                        delay(110)
                        sounds.playShuffle()
                    }
                    s.result == ChessResult.DRAW_STALEMATE || s.result == ChessResult.DRAW_REPETITION -> {
                        haptics(HapticSignal.FAILURE)
                        sounds.playTap()
                    }
                    s.inCheck -> {
                        haptics(HapticSignal.ESCALATING)
                        sounds.playTap()
                        delay(70)
                        sounds.playTap()
                    }
                    capturedInfo != null -> {
                        haptics(HapticSignal.STRONG_ACTION)
                        sounds.playPlace()
                        delay(45)
                        sounds.playDraw()
                    }
                    else -> {
                        haptics(HapticSignal.NORMAL_ACTION)
                        if (Random.nextBoolean()) sounds.playTap() else sounds.playPlace()
                    }
                }
            }

            // Idle touch: after ~25s with no move, a slow low-amplitude pulse on the in-check
            // king (or, absent a check, the last-moved piece) -- resets on every move.
            var idlePulseActive by remember { mutableStateOf(false) }
            LaunchedEffect(moveKey, enhanced) {
                idlePulseActive = false
                if (!enhanced) return@LaunchedEffect
                delay(25_000)
                idlePulseActive = true
            }
            val idlePulseAlpha = remember { Animatable(0f) }
            LaunchedEffect(idlePulseActive) {
                if (idlePulseActive) {
                    while (true) {
                        idlePulseAlpha.animateTo(0.35f, animationSpec = tween(1400))
                        idlePulseAlpha.animateTo(0.06f, animationSpec = tween(1400))
                    }
                } else {
                    idlePulseAlpha.snapTo(0f)
                }
            }
            val idleTargetSquare = if (checkedKingSquare >= 0) checkedKingSquare else s.lastTo

            // Board-as-object ambient identity: a cached wood-grain texture per square color,
            // procedurally drawn ONCE (not per-frame) into a small ImageBitmap and reused for
            // every square of that color -- see generateWoodGrainBitmap.
            val darkWoodBitmap = remember { generateWoodGrainBitmap(DARK_SQUARE, DARK_SQUARE_GRAIN, seed = 7) }
            val lightWoodBitmap = remember { generateWoodGrainBitmap(LIGHT_SQUARE, LIGHT_SQUARE_GRAIN, seed = 19) }

            // Checkmate attack line: the mating piece's square(s) -> the checkmated king's
            // square, only once the game has actually ended in checkmate.
            val attackerSquares = remember(moveKey) {
                if (s.roundOver && isCheckmate) game.checkingPieceSquares() else emptySet()
            }

            Box(
                modifier = Modifier
                    .size(boardSize)
                    .graphicsLayer { scaleX = checkmatePush.value; scaleY = checkmatePush.value }
                    .let { if (card3D) it.tablePerspectiveTilt() else it }
                    // Shared PremiumShaders sheen: shared infra's default gate (card3D && !reducedMotion)
                    // AND this game's own Maximum-tier restriction, per this file's Chess-specific notes.
                    .specularSweep(enabled = card3D && maximum, tint = BOARD_SHEEN_TINT)
            ) {
                for (square in 0 until 64) {
                    val col = square % 8
                    val displayRow = 7 - (square / 8)
                    val isDark = (square / 8 + col) % 2 == 0
                    val isSelected = selectedSquare == square
                    val isLegalDest = square in legalDestinations
                    val isLastMoveSquare = square == s.lastFrom || square == s.lastTo
                    val isCheckSquare = square == checkedKingSquare
                    val piece = s.board.getOrNull(square)

                    Box(
                        modifier = Modifier
                            .offset(x = squareSize * col, y = squareSize * displayRow)
                            .size(squareSize)
                            .drawBehind {
                                val bmp = if (isDark) darkWoodBitmap else lightWoodBitmap
                                drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                            }
                            .clickable(enabled = isHumanTurn && !s.roundOver) {
                                val sel = selectedSquare
                                when {
                                    sel != null && square in legalDestinations -> {
                                        game.playMove(currentPlayerIndex, sel, square)
                                    }
                                    piece != null && piece.color == s.sideToMove -> {
                                        selectedSquare = square
                                        haptics(HapticSignal.LIGHT_TICK)
                                    }
                                    else -> {
                                        // A piece was selected and the player tapped a square it
                                        // can't legally move to (previously silent -- deselection
                                        // alone gave no feedback that the attempted move failed).
                                        if (sel != null) playSfx(SfxKind.INVALID_BUZZ)
                                        selectedSquare = null
                                    }
                                }
                            }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .semantics { contentDescription = squareDescription(square, piece) }
                    ) {
                        if (isLastMoveSquare) Box(Modifier.matchParentSize().background(LAST_MOVE_TINT))
                        if (isCheckSquare) Box(Modifier.matchParentSize().background(CHECK_TINT))
                        if (isSelected) Box(Modifier.matchParentSize().background(SELECTED_TINT))
                        if (idlePulseActive && square == idleTargetSquare) {
                            Box(Modifier.matchParentSize().background(IDLE_PULSE_COLOR.copy(alpha = idlePulseAlpha.value)))
                        }
                        if (isLegalDest) {
                            Box(
                                Modifier.align(Alignment.Center).size(squareSize * 0.32f).clip(CircleShape).background(LEGAL_DOT_COLOR)
                            )
                        }
                        if (square !in animatedSquares && piece != null) {
                            val lift = if (isSelected) 1f + selectionPulse.value * 0.14f else 1f
                            val shadow = if (isSelected) selectionPulse.value * 0.6f else 0f
                            ChessPieceToken(piece, squareSize, pieceStyle, Modifier.fillMaxSize(), liftScale = lift, shadowAlpha = shadow)
                        }
                    }
                }

                // Transient overlay(s) for the piece(s) that just moved -- see this file's KDoc.
                if (s.lastFrom != null && s.lastTo != null) {
                    s.board.getOrNull(s.lastTo)?.let { movedPiece ->
                        val showPromotionFlip = promotionInfo != null && card3D
                        Box(
                            modifier = Modifier
                                .size(squareSize)
                                .graphicsLayer { translationX = moverAnim.value.x; translationY = moverAnim.value.y }
                                .let { if (showPromotionFlip) it.card3DFlip(flipProgress.value) else it }
                        ) {
                            // card3DFlip's documented contract: swap the rendered face at the
                            // halfway point where the piece is edge-on. Below that, show the
                            // pawn about to promote; at/after it, the promoted piece.
                            val flippingFrom = promotionInfo?.takeIf { showPromotionFlip }
                            if (flippingFrom != null && flipProgress.value < 0.5f) {
                                ChessPieceToken(flippingFrom.fromPiece, squareSize, pieceStyle, Modifier.fillMaxSize())
                            } else {
                                ChessPieceToken(movedPiece, squareSize, pieceStyle, Modifier.fillMaxSize())
                            }
                        }
                    }
                    if (rookSquares != null) {
                        s.board.getOrNull(rookSquares.second)?.let { rookPiece ->
                            Box(
                                modifier = Modifier
                                    .size(squareSize)
                                    .graphicsLayer { translationX = rookAnim.value.x; translationY = rookAnim.value.y }
                            ) {
                                ChessPieceToken(rookPiece, squareSize, pieceStyle, Modifier.fillMaxSize())
                            }
                        }
                    }
                    // Captured-piece fade: rendered fixed at the square it was actually captured
                    // on (== s.lastTo for an ordinary capture, but a different square for en
                    // passant -- see findCapturedSquare) instead of vanishing the instant the
                    // board recomposes.
                    capturedInfo?.let { info ->
                        val col = info.square % 8
                        val displayRow = 7 - (info.square / 8)
                        Box(
                            modifier = Modifier
                                .offset(x = squareSize * col, y = squareSize * displayRow)
                                .size(squareSize)
                                .graphicsLayer {
                                    alpha = captureFade.value
                                    scaleX = captureFade.value
                                    scaleY = captureFade.value
                                }
                        ) {
                            ChessPieceToken(info.piece, squareSize, pieceStyle, Modifier.fillMaxSize())
                        }
                    }
                }

                // Checkmate attack line: mating piece(s) -> king, drawn on top of everything else.
                if (attackerSquares.isNotEmpty() && checkedKingSquare >= 0) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val kingCenter = Offset(
                            (checkedKingSquare % 8) * squarePx + squarePx / 2f,
                            (7 - checkedKingSquare / 8) * squarePx + squarePx / 2f
                        )
                        attackerSquares.forEach { atk ->
                            val atkCenter = Offset(
                                (atk % 8) * squarePx + squarePx / 2f,
                                (7 - atk / 8) * squarePx + squarePx / 2f
                            )
                            drawLine(color = ATTACK_LINE_COLOR, start = atkCenter, end = kingCenter, strokeWidth = squarePx * 0.05f, cap = StrokeCap.Round)
                            drawCircle(color = ATTACK_LINE_COLOR, radius = squarePx * 0.09f, center = atkCenter)
                        }
                        drawCircle(color = ATTACK_LINE_COLOR, radius = squarePx * 0.13f, center = kingCenter)
                    }
                }
            }
        }
    }

    // Wired into AdaptiveTwoPane (secondary = null -- Chess has no natural "hand" pane) so a
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
                        Spacer(Modifier.height(12.dp))
                        boardBlock(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    )
}

/**
 * A single piece token: the circular color-coded badge from before, now filled with a real
 * vector silhouette ([buildPieceSilhouette]) instead of a monogram letter. [liftScale] and
 * [shadowAlpha] drive the "piece as object" material response for a selected piece -- a small
 * scale-up plus a growing soft drop-shadow "puddle" beneath it, both default to their rest
 * values (1f / 0f) so every other call site is unaffected.
 */
@Composable
private fun ChessPieceToken(
    piece: Piece,
    squareSize: Dp,
    pieceStyle: ChessPieceStyle,
    modifier: Modifier = Modifier,
    liftScale: Float = 1f,
    shadowAlpha: Float = 0f
) {
    val isWhite = piece.color == PieceColor.WHITE
    val tokenColor = if (isWhite) WHITE_PIECE else BLACK_PIECE
    val glyphColor = if (isWhite) BLACK_PIECE else WHITE_PIECE
    val borderColor = if (isWhite) Color(0xFF8A8375) else Color(0xFF000000)
    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        if (shadowAlpha > 0.01f) {
            Box(
                Modifier
                    .fillMaxWidth(0.58f)
                    .height(squareSize * 0.16f)
                    .graphicsLayer { alpha = shadowAlpha }
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .blur(squareSize * 0.10f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = liftScale; scaleY = liftScale }
                .padding(squareSize * 0.08f)
                .clip(CircleShape)
                .background(tokenColor)
                .border(1.5.dp, borderColor, CircleShape)
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(squareSize * 0.12f)) {
                drawPath(buildPieceSilhouette(piece.type, pieceStyle, size), color = glyphColor)
                if (piece.type == PieceType.BISHOP) {
                    // The bishop's characteristic diagonal mitre notch -- "cut" by drawing a
                    // short stroke back in the token's own background color on top of the fill.
                    drawLine(
                        color = tokenColor,
                        start = Offset(size.width * 0.40f, size.height * 0.22f),
                        end = Offset(size.width * 0.58f, size.height * 0.30f),
                        strokeWidth = size.minDimension * 0.06f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

/**
 * The real, per-type vector silhouette powering [ChessPieceToken] -- the pitch's highest-
 * leverage single change, replacing the old monogram-letter token with an actually recognizable
 * king/queen/rook/bishop/knight/pawn shape. Coordinates are all in a normalized 0f..1f unit
 * square (origin top-left), scaled to [size] fresh on every call -- cheap enough to rebuild
 * every draw (a few dozen path ops per piece), unlike the board's own wood-grain texture below
 * which genuinely does need caching (see [generateWoodGrainBitmap]'s KDoc for why those two
 * cases are different). [ChessPieceStyle.CLASSIC] leans on curves for a traditional Staunton-ish
 * read; [ChessPieceStyle.MINIMALIST] reduces the same six silhouettes to flat polygons while
 * keeping every type visually distinct from every other (never just a re-skinned circle). A
 * single [Path] can hold several disjoint sub-figures (e.g. the queen's crown circles plus her
 * body) filled together in one [androidx.compose.ui.graphics.drawscope.DrawScope.drawPath] call,
 * the same multi-contour technique most icon glyphs use.
 */
private fun buildPieceSilhouette(type: PieceType, style: ChessPieceStyle, size: Size): Path {
    val w = size.width
    val h = size.height
    val path = Path()
    fun r(x0: Float, y0: Float, x1: Float, y1: Float) = Rect(x0 * w, y0 * h, x1 * w, y1 * h)

    fun gobletBody(neckHalfWidth: Float, neckY: Float, baseHalfWidth: Float, waistY: Float, baseTopY: Float) {
        path.moveTo((0.5f - neckHalfWidth) * w, neckY * h)
        path.cubicTo(
            (0.5f - neckHalfWidth - 0.10f) * w, (neckY + 0.10f) * h,
            (0.5f - baseHalfWidth - 0.02f) * w, (waistY - 0.04f) * h,
            (0.5f - baseHalfWidth) * w, waistY * h
        )
        path.lineTo((0.5f - baseHalfWidth - 0.06f) * w, baseTopY * h)
        path.lineTo((0.5f + baseHalfWidth + 0.06f) * w, baseTopY * h)
        path.lineTo((0.5f + baseHalfWidth) * w, waistY * h)
        path.cubicTo(
            (0.5f + baseHalfWidth + 0.02f) * w, (waistY - 0.04f) * h,
            (0.5f + neckHalfWidth + 0.10f) * w, (neckY + 0.10f) * h,
            (0.5f + neckHalfWidth) * w, neckY * h
        )
        path.close()
    }

    fun trapezoidBody(topHalfWidth: Float, topY: Float, baseHalfWidth: Float, waistY: Float, baseTopY: Float) {
        path.moveTo((0.5f - topHalfWidth) * w, topY * h)
        path.lineTo((0.5f - baseHalfWidth) * w, waistY * h)
        path.lineTo((0.5f - baseHalfWidth - 0.06f) * w, baseTopY * h)
        path.lineTo((0.5f + baseHalfWidth + 0.06f) * w, baseTopY * h)
        path.lineTo((0.5f + baseHalfWidth) * w, waistY * h)
        path.lineTo((0.5f + topHalfWidth) * w, topY * h)
        path.close()
    }

    fun base(halfWidth: Float, topY: Float, bottomY: Float) {
        path.addRect(r(0.5f - halfWidth, topY, 0.5f + halfWidth, bottomY))
    }

    when (type) {
        PieceType.PAWN -> {
            path.addOval(r(0.38f, 0.16f, 0.62f, 0.40f))
            if (style == ChessPieceStyle.CLASSIC) gobletBody(0.10f, 0.40f, 0.20f, 0.74f, 0.80f)
            else trapezoidBody(0.12f, 0.42f, 0.22f, 0.74f, 0.80f)
            base(0.26f, 0.80f, 0.90f)
        }
        PieceType.ROOK -> {
            // A crenellated tower is already about as minimal as a rook silhouette gets -- both
            // styles intentionally share this one shape.
            path.addRect(r(0.30f, 0.42f, 0.70f, 0.78f))
            path.addRect(r(0.30f, 0.28f, 0.40f, 0.42f))
            path.addRect(r(0.45f, 0.28f, 0.55f, 0.42f))
            path.addRect(r(0.60f, 0.28f, 0.70f, 0.42f))
            base(0.28f, 0.78f, 0.88f)
        }
        PieceType.KNIGHT -> {
            if (style == ChessPieceStyle.CLASSIC) {
                path.moveTo(0.30f * w, 0.86f * h)
                path.lineTo(0.70f * w, 0.86f * h)
                path.lineTo(0.66f * w, 0.60f * h)
                path.cubicTo(0.74f * w, 0.55f * h, 0.76f * w, 0.44f * h, 0.68f * w, 0.37f * h)
                path.cubicTo(0.63f * w, 0.29f * h, 0.55f * w, 0.27f * h, 0.50f * w, 0.20f * h)
                path.cubicTo(0.46f * w, 0.16f * h, 0.39f * w, 0.19f * h, 0.40f * w, 0.26f * h)
                path.cubicTo(0.37f * w, 0.29f * h, 0.41f * w, 0.32f * h, 0.44f * w, 0.30f * h)
                path.cubicTo(0.33f * w, 0.35f * h, 0.25f * w, 0.43f * h, 0.25f * w, 0.53f * h)
                path.cubicTo(0.23f * w, 0.59f * h, 0.28f * w, 0.63f * h, 0.34f * w, 0.60f * h)
                path.cubicTo(0.30f * w, 0.68f * h, 0.30f * w, 0.77f * h, 0.34f * w, 0.86f * h)
                path.close()
            } else {
                path.moveTo(0.32f * w, 0.86f * h)
                path.lineTo(0.68f * w, 0.86f * h)
                path.lineTo(0.64f * w, 0.55f * h)
                path.lineTo(0.70f * w, 0.40f * h)
                path.lineTo(0.55f * w, 0.20f * h)
                path.lineTo(0.46f * w, 0.18f * h)
                path.lineTo(0.50f * w, 0.28f * h)
                path.lineTo(0.34f * w, 0.30f * h)
                path.lineTo(0.26f * w, 0.48f * h)
                path.lineTo(0.32f * w, 0.58f * h)
                path.lineTo(0.30f * w, 0.70f * h)
                path.close()
            }
            base(0.28f, 0.86f, 0.92f)
        }
        PieceType.BISHOP -> {
            path.addOval(r(0.455f, 0.06f, 0.545f, 0.15f))
            if (style == ChessPieceStyle.CLASSIC) {
                path.moveTo(0.40f * w, 0.40f * h)
                path.cubicTo(0.34f * w, 0.30f * h, 0.36f * w, 0.18f * h, 0.50f * w, 0.10f * h)
                path.cubicTo(0.64f * w, 0.18f * h, 0.66f * w, 0.30f * h, 0.60f * w, 0.40f * h)
                path.close()
                gobletBody(0.13f, 0.42f, 0.20f, 0.74f, 0.80f)
            } else {
                path.moveTo(0.50f * w, 0.10f * h)
                path.lineTo(0.66f * w, 0.42f * h)
                path.lineTo(0.34f * w, 0.42f * h)
                path.close()
                trapezoidBody(0.13f, 0.44f, 0.22f, 0.74f, 0.80f)
            }
            base(0.26f, 0.80f, 0.90f)
        }
        PieceType.QUEEN -> {
            val xs = floatArrayOf(0.30f, 0.40f, 0.50f, 0.60f, 0.70f)
            val rs = floatArrayOf(0.040f, 0.048f, 0.055f, 0.048f, 0.040f)
            if (style == ChessPieceStyle.CLASSIC) {
                for (i in xs.indices) path.addOval(r(xs[i] - rs[i], 0.26f - rs[i], xs[i] + rs[i], 0.26f + rs[i]))
                path.addRect(r(0.32f, 0.32f, 0.68f, 0.40f))
                gobletBody(0.16f, 0.42f, 0.22f, 0.74f, 0.80f)
            } else {
                for (i in xs.indices) {
                    path.moveTo(xs[i] * w, (0.30f - rs[i] * 2.2f) * h)
                    path.lineTo((xs[i] - rs[i]) * w, 0.30f * h)
                    path.lineTo((xs[i] + rs[i]) * w, 0.30f * h)
                    path.close()
                }
                path.addRect(r(0.32f, 0.30f, 0.68f, 0.40f))
                trapezoidBody(0.16f, 0.42f, 0.24f, 0.74f, 0.80f)
            }
            base(0.28f, 0.80f, 0.90f)
        }
        PieceType.KING -> {
            path.addRect(r(0.47f, 0.08f, 0.53f, 0.28f))
            path.addRect(r(0.40f, 0.14f, 0.60f, 0.20f))
            path.addRect(r(0.32f, 0.32f, 0.68f, 0.40f))
            if (style == ChessPieceStyle.CLASSIC) gobletBody(0.17f, 0.42f, 0.22f, 0.74f, 0.80f)
            else trapezoidBody(0.17f, 0.42f, 0.24f, 0.74f, 0.80f)
            base(0.28f, 0.80f, 0.90f)
        }
    }
    return path
}

/**
 * The captured-piece tray + material-differential readout -- Chess had no persistent capture
 * record at all before this (unlike Checkers). Both rows and the differential text are derived
 * purely from [board] via [capturedPiecesFor]/[materialAdvantageForWhite] -- no new game-state.
 */
@Composable
private fun CapturedPieceTray(board: List<Piece?>, pieceStyle: ChessPieceStyle, modifier: Modifier = Modifier) {
    val whiteCaptured = remember(board) { capturedPiecesFor(board, PieceColor.BLACK) }
    val blackCaptured = remember(board) { capturedPiecesFor(board, PieceColor.WHITE) }
    val materialDiff = remember(board) { materialAdvantageForWhite(board) }
    Column(modifier = modifier.fillMaxWidth(0.9f)) {
        CapturedRow("White captured", whiteCaptured, PieceColor.BLACK, pieceStyle)
        CapturedRow("Black captured", blackCaptured, PieceColor.WHITE, pieceStyle)
        val diffText = when {
            materialDiff > 0 -> "Material: White +%.1f".format(materialDiff / 100f)
            materialDiff < 0 -> "Material: Black +%.1f".format(-materialDiff / 100f)
            else -> "Material even"
        }
        Text(diffText, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CapturedRow(label: String, pieces: List<PieceType>, pieceColorForIcon: PieceColor, pieceStyle: ChessPieceStyle) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label: ${pieces.size}" }
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(100.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (type in pieces) {
                ChessPieceToken(
                    piece = Piece(type, pieceColorForIcon),
                    squareSize = 22.dp,
                    pieceStyle = pieceStyle,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * A lightweight bar for [ChessGame.positionBalance] -- deliberately just a bar, never a raw
 * number, and labeled "Position Balance" rather than "Engine Evaluation" (see that function's
 * KDoc for why: the heuristic behind it is intentionally simple, not engine-accurate).
 */
@Composable
private fun PositionBalanceBar(balance: Int, modifier: Modifier = Modifier) {
    // Clamped to +-800 (a rook-plus-minor-piece-ish swing) -- generous enough for this
    // heuristic's modest positional terms without letting one huge material swing pin the bar.
    val clamped = balance.coerceIn(-800, 800)
    val whiteFraction = ((clamped + 800) / 1600f).coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth(0.75f)) {
        Text("Position Balance", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF3A3733))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(whiteFraction)
                    .background(WHITE_PIECE)
            )
        }
    }
}

/**
 * Board-as-object ambient identity: a small procedurally-drawn wood-grain tile, generated ONCE
 * per square color (cached via `remember` at the call site, never regenerated per frame or per
 * square) and scaled up to fill each square. This is exactly the case the per-piece silhouette
 * path-building above does NOT need caching for: a fixed small bitmap painted with a handful of
 * sine-wave "grain" strokes is a one-time, size-independent cost, whereas re-walking that same
 * noise pattern at full board resolution on every recomposition would not be. [baseColor] is
 * baked in as the tile's own background fill, so callers no longer need a separate flat
 * `.background(color)` on the square underneath it.
 */
private fun generateWoodGrainBitmap(baseColor: Color, grainColor: Color, seed: Int, sizePx: Int = 128): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val canvas = GraphicsCanvas(bitmap)
    canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), Paint().apply { color = baseColor })
    val rnd = Random(seed)
    val grainPaint = Paint().apply {
        color = grainColor
        style = PaintingStyle.Stroke
        strokeWidth = 1.3f
        alpha = 0.5f
    }
    repeat(9) {
        val baseY = rnd.nextFloat() * sizePx
        val amplitude = 3f + rnd.nextFloat() * 6f
        val freq = 1.5f + rnd.nextFloat() * 2.5f
        val grainPath = Path()
        var x = 0f
        var first = true
        while (x <= sizePx) {
            val y = baseY + sin((x / sizePx) * freq * Math.PI.toFloat()) * amplitude
            if (first) {
                grainPath.moveTo(x, y)
                first = false
            } else {
                grainPath.lineTo(x, y)
            }
            x += 6f
        }
        canvas.drawPath(grainPath, grainPaint)
    }
    return bitmap
}

private fun squareDescription(square: Int, piece: Piece?): String {
    val name = "${'a' + square % 8}${square / 8 + 1}"
    if (piece == null) return name
    val color = if (piece.color == PieceColor.WHITE) "White" else "Black"
    return "$name, $color ${pieceTypeName(piece.type)}"
}

private fun pieceTypeName(type: PieceType): String = when (type) {
    PieceType.PAWN -> "pawn"
    PieceType.KNIGHT -> "knight"
    PieceType.BISHOP -> "bishop"
    PieceType.ROOK -> "rook"
    PieceType.QUEEN -> "queen"
    PieceType.KING -> "king"
}

/** Screen-pixel offset of a square's top-left corner within the board Box, with White's home
 *  rank drawn at the bottom (`7 - row`) -- see this file's class KDoc. */
private fun offsetPxFor(square: Int, squarePx: Float): Offset {
    val col = square % 8
    val displayRow = 7 - (square / 8)
    return Offset(col * squarePx, displayRow * squarePx)
}

/** Detects a castling king-move from the plain (from, to) the engine already reports, purely
 *  so the screen can also animate the rook: a king moving two files is only ever a castle in
 *  a legal position (see ChessGame's generatePseudoMoves). Returns the rook's (from, to) pair,
 *  or null for any non-castling move. */
private fun castleRookSquares(from: Int?, to: Int?, pieceAtTo: Piece?): Pair<Int, Int>? {
    if (from == null || to == null || pieceAtTo?.type != PieceType.KING) return null
    val fromCol = from % 8
    val toCol = to % 8
    if (kotlin.math.abs(toCol - fromCol) != 2) return null
    val rank = from / 8
    val kingside = toCol == 6
    val rookFrom = rank * 8 + (if (kingside) 7 else 0)
    val rookTo = rank * 8 + (if (kingside) 5 else 3)
    return rookFrom to rookTo
}

/** Mutable holder for "the board as it stood immediately before the move currently being
 *  animated" -- plain, non-Compose-observed storage: read then overwritten entirely inside a
 *  single `remember(moveKey)` calculation in ChessScreen, so writing to it never itself
 *  triggers recomposition. See the captured-piece/promotion quick-win additions in this
 *  file's KDoc. */
private class BoardMemory {
    var previous: List<Piece?>? = null
}

/** The piece + square captured by the just-played move (including en passant), so the screen
 *  can keep rendering it fading out instead of it vanishing the instant the board recomposes
 *  -- see [findCapturedSquare]. */
private data class CapturedPieceInfo(val piece: Piece, val square: Int)

/** The pawn's own state immediately before it promoted, used to render [card3DFlip]'s
 *  "before" face. */
private data class PromotionInfo(val fromPiece: Piece)

private data class MoveDiff(val captured: CapturedPieceInfo?, val promotion: PromotionInfo?)

/** The square whose piece was actually captured by the just-played (from, to) move, or null
 *  if it wasn't a capture at all. For an ordinary capture this is simply [to] itself (the
 *  captured piece is overwritten there by the mover's own piece); for en passant, the pawn
 *  that disappears sits beside [to] on [from]'s own rank, not on [to] -- mirroring
 *  ChessGame.applyPlayedMove's identical "if enPassant look beside `to`, else look at `to`"
 *  branch, but derived purely by diffing boards since the screen has no access to the
 *  engine's private ChessMove.enPassant flag. */
private fun findCapturedSquare(prevBoard: List<Piece?>, newBoard: List<Piece?>, from: Int, to: Int): Int? {
    if (prevBoard.getOrNull(to) != null) return to
    for (sq in 0 until 64) {
        if (sq == from || sq == to) continue
        if (prevBoard.getOrNull(sq) != null && newBoard.getOrNull(sq) == null) return sq
    }
    return null
}

// Gameplay colors are fixed literals, never MaterialTheme.colorScheme.* -- per AppTheme.kt's
// documented rule, only chrome (buttons/panels/text above) reads the app theme.
private val LIGHT_SQUARE = Color(0xFFEEEED2)
private val DARK_SQUARE = Color(0xFF769656)
private val LIGHT_SQUARE_GRAIN = Color(0xFFD9D2AC)
private val DARK_SQUARE_GRAIN = Color(0xFF4E6B3A)
private val SELECTED_TINT = Color(0xFFF7EC5B).copy(alpha = 0.55f)
private val LAST_MOVE_TINT = Color(0xFFF7EC5B).copy(alpha = 0.30f)
private val CHECK_TINT = Color(0xFFE5544D).copy(alpha = 0.70f)
private val LEGAL_DOT_COLOR = Color(0xFF2B2724).copy(alpha = 0.30f)
private val IDLE_PULSE_COLOR = Color(0xFF6FA8DC)
private val ATTACK_LINE_COLOR = Color(0xFFE5544D)
private val BOARD_SHEEN_TINT = Color(0xFFFFF3C4).copy(alpha = 0.30f)
private val WHITE_PIECE = Color(0xFFF5F1E8)
private val BLACK_PIECE = Color(0xFF2B2724)

/** Real touch-target floor for a board square -- coerced onto squareSize regardless of how
 *  little space is actually available (same "floor, never let it shrink below a usable tap
 *  size" pattern as [com.gamesuite.games.cards.CardScale]'s own multiplier), so an 8x8 grid
 *  never produces an untappable square even in the tightest window (e.g. the Fold 5 cover
 *  screen). */
private val MIN_TOUCH_TARGET = 48.dp
