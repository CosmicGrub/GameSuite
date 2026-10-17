package com.gamesuite.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.slidingpuzzle.SlidingPuzzleGame
import com.gamesuite.games.slidingpuzzle.SlidingPuzzleRecord
import com.gamesuite.games.slidingpuzzle.SlidingPuzzleStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Below this cell size (dp) the board stops shrinking and scrolls instead. */
private const val SLIDING_PUZZLE_MIN_CELL_DP = 28f

/** Upper bound on a cell (dp) so a 3x3 board does not sprawl on a tablet. */
private const val SLIDING_PUZZLE_MAX_CELL_DP = 112f

/**
 * Renders SlidingPuzzleGame's state reactively — same shape as HangmanScreen:
 * a difficulty label (the engine's own difficulty, so a resumed puzzle shows the tier it was
 * dealt at), a live move counter, and a solved-panel with New Puzzle / Reset / Back to Menu once
 * the board is arranged correctly. Tiles are colored by number (HSV hues spaced evenly across the
 * palette) rather than plain gray, so a solved board reads as a simple color mosaic — the
 * "picture" half of the roadmap's "picture puzzles" framing, plus a real picture-slice mode
 * (built-in Sunset/Koi or a picked photo).
 *
 * Also owns everything the stopwatch/best-record feature needs on the UI
 * side: [SlidingPuzzleStatsStore] is created and collected here (the game
 * engine only exposes raw timer readings, see SlidingPuzzleGame's KDoc), the
 * live elapsed-time text ticks off a small `LaunchedEffect` loop reading
 * `SystemClock.elapsedRealtime()` rather than the engine running its own
 * coroutine, and the "New best!" banner is computed the moment the puzzle's
 * `solved` flag flips true by comparing this solve against the stored
 * record before overwriting it. A solve that used a hint is "assisted": it counts as solved for
 * the session but never touches the record.
 *
 * Sound/haptics reuse the exact CardSounds/LocalHapticFeedback idiom every
 * other game screen already uses (see e.g. MancalaScreen): `playTap()` +
 * `LongPress` on a tile slide, matching the "generic board-game move" feel
 * TicTacToe/Mancala/Dominoes already use `playTap()` for. Puzzle-solved gets
 * `playShuffle()` instead — the one CardSounds clip not already claimed by a
 * per-move sound elsewhere, repurposed here as this screen's one-off "big
 * moment" cue since nothing in the suite has a dedicated win/fanfare sound.
 *
 * CHROME: the shared [GameChrome] corner menu (Back to Menu / How to Play / Undo / Hint) plus its
 * BackHandler. Leaving mid-puzzle is an abort (confirmation first, never recorded) when no puzzle
 * was solved yet and the puzzle is saved for Resume; if puzzles were already solved this session,
 * leaving goes through `leaveSession()` so those results still count (and nothing is saved). A
 * solved board always leaves through `leaveSession()`. The header reserves [GameChromeEndInset]
 * at its end so the corner button never covers it.
 *
 * RESUME / UNDO / HINT: start-up asks the session manager to resume before dealing a new puzzle.
 * A saved picture mode comes back with the game (a picked photo cannot be saved, so it returns to
 * Numbers). Undo and Hint are visible 48dp buttons under the board as well as menu entries; the
 * hinted tile gets a double ring and a direction arrow (not colour alone) and its description says
 * "suggested move".
 *
 * LAYOUT: [fitBoard] against the measured space of the board's own slot, a 28-112dp cell clamp,
 * and a scroll fallback (never a bigger-than-container board) if the slot cannot give a cell 28dp.
 * Portrait stacks header / board / controls / result; a landscape window puts the header, controls
 * and result in a column beside the board, so the result panel never covers the finished picture.
 * Tile numerals scale with the tile.
 */
@Composable
fun SlidingPuzzleScreen(
    sessionManager: GameSessionManager,
    game: SlidingPuzzleGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's scramble for every player — see
     *  SlidingPuzzleGame.startMatch(dailySeed)'s KDoc. Computed by the caller (e.g. a
     *  "Daily Challenge" menu entry) from today's date; this screen has no calendar
     *  knowledge of its own. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.SLIDING_PUZZLE, enabled = musicEnabled)
    val reducedMotion = LocalReducedMotion.current
    val card3D = LocalCard3DMode.current && !reducedMotion
    // Gates the per-tile slide/settle animation below (item 2/3 of this bundle) --
    // off (either the setting itself or reduced motion) means every tile snaps
    // straight to its new cell with no animation at all, same final layout either way.
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val statsStore = remember { SlidingPuzzleStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // Real picture-slice mode (the content feature this game's own KDoc flags as a scope-down
    // from a real picture puzzle -- see SlidingPuzzleGame's class KDoc): a couple of built-in
    // images, decoded once, or a player-picked photo, each sliced into one bitmap-cropped tile
    // per grid cell (see sliceIntoTiles) -- NUMBERS keeps today's exact flat-color-mosaic look.
    // The engine carries the chosen source's name (game.imageSourceTag) so Resume can restore it.
    var imageSource by remember { mutableStateOf(PuzzleImageSource.NUMBERS) }
    var customBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val sunsetBitmap = remember { runCatching { BitmapFactory.decodeResource(androidContext.resources, R.drawable.puzzle_pic_sunset) }.getOrNull() }
    val koiBitmap = remember { runCatching { BitmapFactory.decodeResource(androidContext.resources, R.drawable.puzzle_pic_koi) }.getOrNull() }
    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bitmap = decodeSampledBitmapFromUri(androidContext, uri)
            if (bitmap != null) {
                customBitmap = bitmap
                imageSource = PuzzleImageSource.CUSTOM
                game.imageSourceTag = PuzzleImageSource.CUSTOM.name
            }
        }
    }
    val sourceBitmap: Bitmap? = when (imageSource) {
        PuzzleImageSource.NUMBERS -> null
        PuzzleImageSource.BUILT_IN_SUNSET -> sunsetBitmap
        PuzzleImageSource.BUILT_IN_KOI -> koiBitmap
        PuzzleImageSource.CUSTOM -> customBitmap
    }
    // Re-sliced only when the source image or the grid size changes (a new difficulty tier
    // reshapes the grid) -- not on every tile move, since the slice itself never changes mid-puzzle.
    val tileImages: Map<Int, ImageBitmap>? = remember(sourceBitmap, state?.size) {
        val bmp = sourceBitmap
        val size = state?.size
        if (bmp != null && size != null) sliceIntoTiles(bmp, size) else null
    }

    // One restrained completion beat: a single whole-board scale pulse (1.0 -> 1.03 -> 1.0) the
    // instant the puzzle solves -- gated by enhanced/reducedMotion like every other animation
    // here. Deliberately just this one pulse: no hit-stop/camera-shake (wrong genre signal for
    // a precise, deliberate slide) and no per-tile ladder.
    val boardPulseScale = remember { Animatable(1f) }

    // puzzlesSolved of a board that was ALREADY solved when it was saved, or -1. The solve effect
    // below skips that board: its sound, celebration and record were handled before the save.
    var resumedSolvedPuzzles by remember { mutableStateOf(-1) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        // Picking this game from the menu after leaving it mid-puzzle offers Resume; when the
        // player took it, the engine is put back exactly as it was instead of dealing a new puzzle.
        if (sessionManager.tryResume(game)) {
            // The screen's own picture-mode state follows the engine. A picked photo is not saved,
            // so a saved CUSTOM source falls back to Numbers.
            val saved = PuzzleImageSource.values().firstOrNull { it.name == game.imageSourceTag } ?: PuzzleImageSource.NUMBERS
            val restored = if (saved == PuzzleImageSource.CUSTOM && customBitmap == null) PuzzleImageSource.NUMBERS else saved
            imageSource = restored
            game.imageSourceTag = restored.name
            resumedSolvedPuzzles = if (game.state.value?.solved == true) game.puzzlesSolved.value else -1
        } else {
            game.startMatch(dailySeed)
            // init() reset the engine's picture tag; keep it in step with what the screen still shows.
            game.imageSourceTag = imageSource.name
        }
    }

    val s = state ?: return
    val puzzlesSolved = game.puzzlesSolved.value
    val hintsUsed = game.hintsUsed.value
    val hintTile = game.hintTile.value
    val totalNumberedTiles = s.size * s.size - 1

    val allRecords by statsStore.records.collectAsState(initial = emptyMap())
    val record = allRecords[game.difficulty.name] ?: SlidingPuzzleRecord()

    // Live "Time: M:SS" display. The engine only exposes raw elapsedRealtime()
    // readings (timerStartElapsedRealtime/solvedElapsedMillis) rather than
    // ticking a value itself, so this loop is what actually drives
    // recomposition once a second while a puzzle is in progress; it goes
    // idle (no delay loop running) both before the first move and once
    // solved, when the frozen solvedElapsedMillis reading is shown instead.
    var liveElapsedMillis by remember { mutableStateOf(0L) }
    LaunchedEffect(game.timerStartElapsedRealtime.value, s.solved) {
        val start = game.timerStartElapsedRealtime.value
        if (start == null) {
            liveElapsedMillis = 0L
            return@LaunchedEffect
        }
        while (!s.solved) {
            liveElapsedMillis = SystemClock.elapsedRealtime() - start
            delay(200)
        }
    }
    val displayedElapsedMillis = if (s.solved) (game.solvedElapsedMillis.value ?: liveElapsedMillis) else liveElapsedMillis

    // Fires exactly once per solved puzzle (keyed on puzzlesSolved as well as
    // s.solved so a same-boolean edge case can't suppress a re-trigger): the
    // "solved" sound/haptic, persisting this run's (moves, time) into
    // SlidingPuzzleStatsStore, and computing whether either half of the
    // record actually improved for the "New best!" banner below. An ASSISTED solve (a hint was
    // used) is celebrated and counted as solved but never written to the record.
    var newBestMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.solved, puzzlesSolved) {
        if (!s.solved) {
            newBestMessage = null
            return@LaunchedEffect
        }
        // Resumed straight onto an already-solved board: nothing new happened.
        if (resumedSolvedPuzzles == puzzlesSolved) return@LaunchedEffect
        sounds.playShuffle()
        // One new haptic, not a ladder: SUCCESS's own distinct, warmer-feeling pattern (see
        // Haptics.kt's KDoc) -- there's no variable "impact force" analog in this game the way
        // Air Hockey has real collision speed to map to, so this stays a single fixed signal.
        haptics(HapticSignal.SUCCESS)
        if (enhanced) {
            boardPulseScale.snapTo(1f)
            boardPulseScale.animateTo(1.03f, tween(120))
            boardPulseScale.animateTo(1f, tween(160))
        }
        if (game.hintsUsed.value > 0) {
            newBestMessage = null
            return@LaunchedEffect
        }
        val elapsed = game.solvedElapsedMillis.value ?: 0L
        val result = statsStore.recordSolve(game.difficulty, s.moveCount, elapsed)
        newBestMessage = when {
            result.isNewBestMoves && result.isNewBestTimeMillis -> "New best! Fewest moves and fastest time."
            result.isNewBestMoves -> "New best move count!"
            result.isNewBestTimeMillis -> "New best time!"
            else -> null
        }
    }

    // Back / abort-confirm / How to Play / Undo / Hint live in the shared GameChrome. Leaving
    // mid-puzzle discards ONLY the unfinished puzzle: if earlier puzzles in this session were
    // already solved, leaving goes through leaveSession() so those results still count; only a
    // session with nothing solved is a pure abort (and is saved for Resume). A solved board
    // always leaves through leaveSession(), which scores the session.
    GameChrome(
        helpTitle = "How to Play Sliding Puzzle",
        helpText = "Tap a tile next to the empty space to slide it in. Rearrange the tiles so the " +
            "numbers run in order, left to right and top to bottom, with the empty space last.\n\n" +
            "The grid size follows your default difficulty: 3x3 on Easy, 4x4 on Medium, 5x5 on Hard. " +
            "The clock starts on your first slide, and your fewest moves and fastest time are kept " +
            "for each difficulty. Numbers, Sunset, Koi or Photo choose what the tiles show.\n\n" +
            "Undo takes back your last slide. Hint highlights a tile worth sliding next; a puzzle " +
            "solved with a hint still counts as solved but does not set a best record.",
        matchInProgress = !s.solved,
        onLeave = game::leaveSession,
        onAbort = { if (puzzlesSolved > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = puzzlesSolved == 0,
        onUndo = { game.undo() },
        canUndo = game.canUndo,
        onHint = { game.hint() },
        buttonFill = MaterialTheme.colorScheme.background,
        buttonContent = MaterialTheme.colorScheme.onBackground,
        leaveTitle = "Leave this puzzle?",
        leaveBody = if (puzzlesSolved > 0) {
            "This puzzle is still in progress and won't count, but the puzzles you've already solved stay on your record."
        } else {
            "This puzzle is still in progress. Leaving now won't count it."
        }
    ) {
        // Primary-only (no secondary/hand content in this game) — same treatment
        // as TicTacToeScreen/HangmanScreen.
        //
        // The board is a fixed 1:1 aspect shape (every cell is square), so its size
        // MUST come from both the available width AND height -- never a hardcoded
        // dp constant and never width alone. The outer BoxWithConstraints below also picks
        // between a stacked (chrome above board) and side-by-side (chrome beside
        // board) arrangement based on the window's own aspect ratio, so a
        // landscape-class window -- most importantly the Fold 5 cover screen
        // rotated to landscape, ~882x344dp, the tightest real vertical budget
        // anywhere in this app -- doesn't starve the board down by stacking rows of
        // stat text above it.
        AdaptiveTwoPane(
            foldState = LocalFoldState.current,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            primary = {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isLandscape = maxWidth > maxHeight
                    // Read here: inside the Column below, this scope's maxHeight is not implicitly reachable.
                    val screenMaxHeight = maxHeight

                    val chrome: @Composable (Dp) -> Unit = { endInset ->
                        // The end inset keeps the corner menu button off the header text.
                        Column(modifier = Modifier.padding(end = endInset)) {
                            Text("Puzzles solved: $puzzlesSolved", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "Difficulty: ${game.difficulty.name.lowercase().replaceFirstChar { it.uppercase() }}" +
                                    " (${s.size}×${s.size})",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        // Defensively horizontal-scrollable: four chip labels normally fit even
                        // in a narrow cover-portrait width, but a narrow landscape side column
                        // (see chromeWidth below) has much less room to work with.
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ImageSourceChip("Numbers", imageSource == PuzzleImageSource.NUMBERS) {
                                imageSource = PuzzleImageSource.NUMBERS
                                game.imageSourceTag = PuzzleImageSource.NUMBERS.name
                            }
                            ImageSourceChip("Sunset", imageSource == PuzzleImageSource.BUILT_IN_SUNSET) {
                                imageSource = PuzzleImageSource.BUILT_IN_SUNSET
                                game.imageSourceTag = PuzzleImageSource.BUILT_IN_SUNSET.name
                            }
                            ImageSourceChip("Koi", imageSource == PuzzleImageSource.BUILT_IN_KOI) {
                                imageSource = PuzzleImageSource.BUILT_IN_KOI
                                game.imageSourceTag = PuzzleImageSource.BUILT_IN_KOI.name
                            }
                            ImageSourceChip("Photo…", imageSource == PuzzleImageSource.CUSTOM) {
                                pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Moves: ${s.moveCount}   Time: ${formatElapsed(displayedElapsedMillis)}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Best: " + (record.bestMoves?.let { "$it moves" } ?: "—") +
                                " · " + (record.bestTimeMillis?.let { formatElapsed(it) } ?: "—"),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    val controls: @Composable () -> Unit = {
                        SlidingPuzzleControlsRow(
                            canUndo = game.canUndo,
                            canHint = !s.solved,
                            hintLabel = hintTile?.let { tile ->
                                val blank = s.tiles.indexOf(0)
                                val at = s.tiles.indexOf(tile)
                                "Hint: slide tile $tile ${slidingPuzzleDirection(at, blank, s.size)}"
                            },
                            onUndo = { game.undo() },
                            onHint = { game.hint() }
                        )
                    }

                    val solvedPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                        SolvedPanel(
                            newBestMessage = newBestMessage,
                            moves = s.moveCount,
                            timeText = formatElapsed(displayedElapsedMillis),
                            hintsUsed = hintsUsed,
                            onPlayAgain = game::playAgain,
                            onReset = game::resetToInitial,
                            onBackToMenu = game::leaveSession,
                            modifier = panelModifier
                        )
                    }

                    val board: @Composable () -> Unit = {
                        // Sits in a slot that's genuinely bounded on BOTH axes in either branch
                        // below (weight(1f) inside a fillMaxSize Row/Column, never wrap-content),
                        // so maxWidth/maxHeight here are real window-derived bounds, not
                        // effectively-infinite ones -- exactly the distinction the fold/rotation
                        // layout-correctness pass exists to get right.
                        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            // fitBoard never returns a footprint larger than this slot (the old
                            // 48dp floor could). If the slot cannot give a cell the minimum, keep
                            // the minimum and let the board scroll instead of shrinking further.
                            val fit = fitBoard(
                                availableWidthPx = maxWidth.value,
                                availableHeightPx = maxHeight.value,
                                columns = s.size,
                                minCellPx = SLIDING_PUZZLE_MIN_CELL_DP,
                                maxCellPx = SLIDING_PUZZLE_MAX_CELL_DP
                            )
                            val scrolls = !fit.meetsMinimum
                            val cellSize: Dp = if (scrolls) SLIDING_PUZZLE_MIN_CELL_DP.dp else fit.cellPx.dp
                            val boardSize = cellSize * s.size
                            val hScroll = rememberScrollState()
                            val vScroll = rememberScrollState()

                            // Plain manually-laid-out Box, not LazyVerticalGrid: the board never
                            // scrolls (bar the tiny-window fallback) and tops out at 5x5, so Lazy
                            // virtualization buys nothing here while getting in the way of per-tile
                            // animation -- a Lazy grid keys its items by grid INDEX, which is
                            // exactly wrong for animating a tile that just changed index. Children
                            // below are absolute-positioned via Modifier.offset from the board's own
                            // known size/cell-count instead.
                            Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
                                Box(
                                    modifier = Modifier
                                        .size(boardSize)
                                        .graphicsLayer { scaleX = boardPulseScale.value; scaleY = boardPulseScale.value }
                                        .then(if (card3D) Modifier.tablePerspectiveTilt() else Modifier)
                                ) {
                                    // Blank cell: rendered at its current position but never animated --
                                    // it has no identity of its own to preserve across a slide (it's an
                                    // absence, not a tile), so it simply redraws at the vacated cell each
                                    // move, same as it always instantly has.
                                    val blankIndex = s.tiles.indexOf(0)
                                    val blankRow = blankIndex / s.size
                                    val blankCol = blankIndex % s.size
                                    Box(
                                        modifier = Modifier
                                            .offset(x = cellSize * blankCol, y = cellSize * blankRow)
                                            .size(cellSize)
                                            .padding(2.dp)
                                            .background(Color.LightGray.copy(alpha = 0.3f))
                                            .semantics { contentDescription = "Blank space, row ${blankRow + 1} column ${blankCol + 1}" }
                                    )

                                    // Numbered tiles, one composable per TILE VALUE (via key()) rather
                                    // than per grid index -- this is what lets SlidingTileView's own
                                    // Animatable survive a swap/move and animate from the tile's old cell
                                    // to its new one instead of an instant value-swap. O(size^2) indexOf
                                    // scans here are trivial at this board's scale (<=25 tiles).
                                    for (value in 1..totalNumberedTiles) {
                                        val index = s.tiles.indexOf(value)
                                        val row = index / s.size
                                        val col = index % s.size
                                        key(value) {
                                            SlidingTileView(
                                                value = value,
                                                row = row,
                                                col = col,
                                                homeRow = (value - 1) / s.size,
                                                homeCol = (value - 1) % s.size,
                                                cellSize = cellSize,
                                                color = tileColor(value, totalNumberedTiles),
                                                image = tileImages?.get(value),
                                                enhanced = enhanced,
                                                isFreshPuzzle = s.moveCount == 0,
                                                enabled = !s.solved,
                                                hintDirection = if (hintTile == value) slidingPuzzleDirection(index, blankIndex, s.size) else null,
                                                onClick = {
                                                    val movesBefore = s.moveCount
                                                    game.tapTile(index)
                                                    // tapTile no-ops for a tap on a tile that isn't adjacent
                                                    // to the blank, so only fire feedback for an actual slide.
                                                    if (game.state.value?.moveCount != movesBefore) {
                                                        sounds.playTap()
                                                        // The ordinary "a tile placed" bucket -- there's no
                                                        // variable impact-force analog here to scale off of,
                                                        // so every slide uses the same single signal.
                                                        haptics(HapticSignal.NORMAL_ACTION)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isLandscape) {
                        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                chrome(0.dp)
                                Spacer(Modifier.height(8.dp))
                                controls()
                                if (s.solved) {
                                    Spacer(Modifier.height(8.dp))
                                    solvedPanel(Modifier.fillMaxWidth())
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            // The corner menu button sits at the screen's top-end, so the board slot
                            // leaves its width free rather than risk the board running under it.
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(end = GameChromeEndInset)) { board() }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            chrome(GameChromeEndInset)
                            Spacer(Modifier.height(12.dp))
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { board() }
                            Spacer(Modifier.height(8.dp))
                            controls()
                            if (s.solved) {
                                Spacer(Modifier.height(8.dp))
                                // Capped so even a large font scale cannot push the board out of the
                                // slot above; the panel scrolls inside its own cap instead.
                                solvedPanel(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = screenMaxHeight * 0.45f)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

/** Formats a millisecond duration as "M:SS" for the live/best-time displays. */
private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Distinct flat color per tile number, hues spaced evenly around the wheel so a solved board reads as a mosaic. */
private fun tileColor(number: Int, totalNumberedTiles: Int): Color {
    val hue = 360f * (number - 1) / totalNumberedTiles
    return Color.hsv(hue = hue, saturation = 0.55f, value = 0.9f)
}

/** Which way the tile at [tileIndex] moves to enter the blank at [blankIndex] (they are adjacent): "up", "down", "left" or "right". */
private fun slidingPuzzleDirection(tileIndex: Int, blankIndex: Int, size: Int): String {
    val tileRow = tileIndex / size
    val blankRow = blankIndex / size
    return when {
        blankRow < tileRow -> "up"
        blankRow > tileRow -> "down"
        blankIndex < tileIndex -> "left"
        else -> "right"
    }
}

/** The arrow glyph for a direction word from [slidingPuzzleDirection]. */
private fun slidingPuzzleArrow(direction: String): String = when (direction) {
    "up" -> "↑"
    "down" -> "↓"
    "left" -> "←"
    else -> "→"
}

/**
 * One numbered tile, keyed by the caller on its stable [value] (see the loop in
 * [SlidingPuzzleScreen]) so this composable instance -- and the Animatable position it
 * owns -- survives across a slide/reshuffle instead of being torn down and rebuilt the
 * way a grid-index-keyed item would be.
 *
 * Two distinct motion specs, both deliberately shorter/punchier than Checkers'
 * 300ms piece slide since a puzzle tile only ever crosses a single cell width:
 *  - ordinary move ([isFreshPuzzle] false): a quick ~150ms [tween] to the new cell.
 *  - fresh puzzle ([isFreshPuzzle] true, i.e. [moveCount][com.gamesuite.games.slidingpuzzle.SlidingPuzzleState.moveCount] == 0 --
 *    the very first puzzle of the session, or right after New Puzzle/Reset): a bouncier
 *    [spring] instead, so the whole board visibly "settles into place" once rather than
 *    every tile just appearing pre-arranged. The internal 100-300+ move scramble walk
 *    itself is never animated -- only the single before/after transition is, exactly
 *    like an ordinary move, just with a showier spec.
 *
 * On this composable's very first entry into composition (a brand-new instance, i.e. the
 * very first puzzle of the whole session) there is no "old cell" to animate from, so
 * without help the tile would simply appear at [row]/[col] with nothing to animate --
 * defeating the "settles into place" goal above. To give the spring something to actually
 * cover, a fresh puzzle's initial position is seeded at the tile's solved/"home" cell
 * ([homeRow]/[homeCol]) instead of its real (scrambled) one, so it visibly flies to its
 * scrambled cell and bounces to rest. Every later fresh puzzle (New Puzzle/Reset) already
 * has a real previous position to animate from -- wherever this same instance last sat --
 * so no such seeding is needed there; the branch below only ever fires once per instance.
 *
 * [enhanced] off (setting disabled, or reduced motion always wins per LocalReducedMotion's
 * contract) collapses everything to [snap] -- an instant position set with the exact same
 * final layout, no animation played at all, including on that very first frame.
 *
 * The animated position is applied through the lambda form of `Modifier.offset`, so the
 * Animatable is read in the layout phase and an animating tile does not recompose each frame.
 *
 * [image], when non-null (picture-slice mode -- see [sliceIntoTiles]), is drawn cropped to
 * this tile's own home cell instead of the flat [color] fill, plus a small numbered corner
 * badge for solvability; null keeps today's exact flat-color-mosaic look. Numerals and the
 * badge scale with [cellSize]. Either way, a subtle shadow + border gives every tile a
 * physical, beveled/inset read -- no rotation, no perspective, so it never competes with the
 * solving itself.
 *
 * [hintDirection], when non-null, marks this as the suggested tile: a double ring (dark outer,
 * white inner, so it reads by brightness on any hue) and a direction arrow, plus a
 * "suggested move" state description for screen readers. The content description is left
 * untouched ("Tile N, row R column C") so existing tests and readers keep matching it.
 */
@Composable
private fun SlidingTileView(
    value: Int,
    row: Int,
    col: Int,
    homeRow: Int,
    homeCol: Int,
    cellSize: Dp,
    color: Color,
    image: ImageBitmap?,
    enhanced: Boolean,
    isFreshPuzzle: Boolean,
    enabled: Boolean,
    hintDirection: String?,
    onClick: () -> Unit
) {
    val targetX = cellSize * col
    val targetY = cellSize * row
    val seedAtHome = enhanced && isFreshPuzzle
    val x = remember { Animatable(if (seedAtHome) cellSize * homeCol else targetX, Dp.VectorConverter) }
    val y = remember { Animatable(if (seedAtHome) cellSize * homeRow else targetY, Dp.VectorConverter) }

    LaunchedEffect(targetX, targetY, isFreshPuzzle, enhanced) {
        val spec: AnimationSpec<Dp> = when {
            !enhanced -> snap()
            isFreshPuzzle -> spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            else -> tween(durationMillis = 150, easing = FastOutSlowInEasing)
        }
        // Both axes must animate concurrently (a coroutineScope waits for both child
        // launches), not sequentially -- sequential x-then-y would visibly animate as an
        // L-shaped path instead of a straight one.
        coroutineScope {
            launch { x.animateTo(targetX, spec) }
            launch { y.animateTo(targetY, spec) }
        }
    }

    val density = LocalDensity.current
    val numeralSize = with(density) { (cellSize * 0.4f).toSp() }
    val numeralLine = with(density) { (cellSize * 0.48f).toSp() }
    val badgeSize = with(density) { (cellSize * 0.22f).toSp() }
    val badgeLine = with(density) { (cellSize * 0.26f).toSp() }

    // Subtle bevel/inset: a soft elevation shadow plus a thin dark border makes each tile read
    // as a small physical, raised object -- deliberately just this, no rotation/perspective,
    // so it never competes with the actual solving cognition the way a flashier effect would.
    val tileShape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .offset { IntOffset(x.value.roundToPx(), y.value.roundToPx()) }
            .size(cellSize)
            .padding(2.dp)
            .shadow(elevation = 2.dp, shape = tileShape, clip = false)
            .clip(tileShape)
            .background(color)
            .border(width = 1.dp, color = Color.Black.copy(alpha = 0.28f), shape = tileShape)
            .clickable(enabled = enabled, onClickLabel = "Slide tile $value", role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .then(
                if (hintDirection != null) {
                    Modifier
                        .border(3.dp, Color(0xFF111111), tileShape)
                        .padding(3.dp)
                        .border(2.dp, Color.White, tileShape)
                } else {
                    Modifier
                }
            )
            .semantics {
                contentDescription = "Tile $value, row ${row + 1} column ${col + 1}"
                if (hintDirection != null) stateDescription = "suggested move, slides $hintDirection"
            },
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Small numbered corner badge -- picture mode still needs SOME solvability aid,
            // just not the dominant flat-color-plus-big-number look NUMBERS mode uses.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .clearAndSetSemantics {}
            ) {
                Text(
                    value.toString(),
                    fontSize = badgeSize,
                    lineHeight = badgeLine,
                    maxLines = 1,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        } else {
            Text(
                value.toString(),
                fontSize = numeralSize,
                lineHeight = numeralLine,
                maxLines = 1,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
        if (hintDirection != null) {
            // Non-colour cue: where the tile will slide. Decorative for screen readers (the
            // state description above already says it).
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size((cellSize * 0.3f).coerceAtLeast(16.dp))
                    .background(Color(0xFF111111), CircleShape)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center
            ) {
                Text(
                    slidingPuzzleArrow(hintDirection),
                    fontSize = with(density) { (cellSize * 0.2f).toSp() },
                    lineHeight = with(density) { (cellSize * 0.24f).toSp() },
                    maxLines = 1,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Visible Undo and Hint under the board, so neither is hidden in the corner menu (both are the
 * same actions the menu offers). Undo is enabled once there is a slide to take back and the
 * puzzle is unsolved; Hint while the puzzle is unsolved. A disabled button says why in its
 * description. [hintLabel] spells out the showing hint ("Hint: slide tile 7 left") in a polite
 * live region; its line height is reserved so a hint appearing never resizes the board.
 */
@Composable
private fun SlidingPuzzleControlsRow(
    canUndo: Boolean,
    canHint: Boolean,
    hintLabel: String?,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(
                        if (canUndo) Modifier else Modifier.semantics { contentDescription = "Undo, unavailable: no slide to take back" }
                    )
            ) { Text("Undo") }
            OutlinedButton(
                onClick = onHint,
                enabled = canHint,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(
                        if (canHint) Modifier else Modifier.semantics { contentDescription = "Hint, unavailable: the puzzle is solved" }
                    )
            ) { Text("Hint") }
        }
        Box(modifier = Modifier.heightIn(min = 20.dp).padding(top = 2.dp)) {
            if (hintLabel != null) {
                Text(
                    hintLabel,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

@Composable
private fun SolvedPanel(
    newBestMessage: String?,
    moves: Int,
    timeText: String,
    hintsUsed: Int,
    onPlayAgain: () -> Unit,
    onReset: () -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Solved!",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("$moves moves · $timeText", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (hintsUsed > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Solved with $hintsUsed ${if (hintsUsed == 1) "hint" else "hints"}: no best record set.",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center
                )
            } else if (newBestMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    newBestMessage,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onPlayAgain,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("New Puzzle")
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Replays this same puzzle's original scramble, as opposed to "New Puzzle"
            // above dealing a brand-new one — see SlidingPuzzleGame.resetToInitial's KDoc.
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Reset puzzle")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Back to Menu")
            }
        }
    }
}

/** Which image the board's tiles are cut from — see [sliceIntoTiles]. [NUMBERS] is the original,
 *  unchanged flat-color-mosaic look; the rest are real picture-slice mode. The names are saved
 *  in the engine's snapshot, so do not rename them. */
private enum class PuzzleImageSource { NUMBERS, BUILT_IN_SUNSET, BUILT_IN_KOI, CUSTOM }

/** One Numbers/Sunset/Koi/Photo… selector label: a 48dp-tall radio-style target (the selected one
 *  is also bold, so it is not colour alone). */
@Composable
private fun ImageSourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The real picture-slice mode's core: center-crops [source] to a square, then cuts it into
 * [size] x [size] bitmap-cropped cells, one per grid cell, keyed by the tile VALUE that cell is
 * home to (`value` 1..size*size-1, home cell `(value-1)/size, (value-1)%size` — the same mapping
 * [SlidingPuzzleScreen] already uses for [SlidingTileView]'s `homeRow`/`homeCol`). The blank
 * cell's own square (home cell for the missing size*size value) is simply never sliced out.
 *
 * Center-cropping to a square (rather than stretching) keeps every built-in/custom photo's own
 * aspect ratio intact within the cropped region, matching how a real jigsaw box's picture reads.
 */
private fun sliceIntoTiles(source: Bitmap, size: Int): Map<Int, ImageBitmap> {
    val side = minOf(source.width, source.height)
    val offsetX = (source.width - side) / 2
    val offsetY = (source.height - side) / 2
    val square = Bitmap.createBitmap(source, offsetX, offsetY, side, side)
    val cell = side / size
    val result = mutableMapOf<Int, ImageBitmap>()
    for (value in 1 until size * size) {
        val homeRow = (value - 1) / size
        val homeCol = (value - 1) % size
        result[value] = Bitmap.createBitmap(square, homeCol * cell, homeRow * cell, cell, cell).asImageBitmap()
    }
    return result
}

/**
 * Decodes a player-picked photo [uri] downsampled to at most [maxDimension] px on its longer
 * side, via [BitmapFactory]'s standard bounds-then-sample two-pass approach — a full-resolution
 * modern phone photo (tens of megapixels) decoded directly would be a needless multi-hundred-MB
 * spike for a puzzle that only ever displays a handful of small cropped cells. Returns null on
 * any failure (an unreadable/corrupt stream, a revoked content permission, ...) rather than
 * crashing the screen; the caller simply keeps whatever image source was active before.
 */
private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 900): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()
