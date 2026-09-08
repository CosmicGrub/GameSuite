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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders SlidingPuzzleGame's state reactively — same shape as HangmanScreen:
 * a difficulty label sourced from Settings, a live move counter, and a
 * solved-panel with New Puzzle / Back to Menu once the board is arranged
 * correctly. Tiles are colored by number (HSV hues spaced evenly across the
 * palette) rather than plain gray, so a solved board reads as a simple color
 * mosaic — the "picture" half of the roadmap's "picture puzzles" framing,
 * without needing any bitmap/image-slicing asset pipeline.
 *
 * Also owns everything the stopwatch/best-record feature needs on the UI
 * side: [SlidingPuzzleStatsStore] is created and collected here (the game
 * engine only exposes raw timer readings, see SlidingPuzzleGame's KDoc), the
 * live elapsed-time text ticks off a small `LaunchedEffect` loop reading
 * `SystemClock.elapsedRealtime()` rather than the engine running its own
 * coroutine, and the "New best!" banner is computed the moment the puzzle's
 * `solved` flag flips true by comparing this solve against the stored
 * record before overwriting it.
 *
 * Sound/haptics reuse the exact CardSounds/LocalHapticFeedback idiom every
 * other game screen already uses (see e.g. MancalaScreen): `playTap()` +
 * `LongPress` on a tile slide, matching the "generic board-game move" feel
 * TicTacToe/Mancala/Dominoes already use `playTap()` for. Puzzle-solved gets
 * `playShuffle()` instead — the one CardSounds clip not already claimed by a
 * per-move sound elsewhere, repurposed here as this screen's one-off "big
 * moment" cue since nothing in the suite has a dedicated win/fanfare sound.
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

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch(dailySeed)
    }

    val s = state ?: return
    val puzzlesSolved = game.puzzlesSolved.value
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
    // record actually improved for the "New best!" banner below.
    var newBestMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.solved, puzzlesSolved) {
        if (!s.solved) {
            newBestMessage = null
            return@LaunchedEffect
        }
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
        val elapsed = game.solvedElapsedMillis.value ?: 0L
        val result = statsStore.recordSolve(game.difficulty, s.moveCount, elapsed)
        newBestMessage = when {
            result.isNewBestMoves && result.isNewBestTimeMillis -> "New best! Fewest moves and fastest time."
            result.isNewBestMoves -> "New best move count!"
            result.isNewBestTimeMillis -> "New best time!"
            else -> null
        }
    }

    // Primary-only (no secondary/hand content in this game) — same treatment
    // as TicTacToeScreen/HangmanScreen.
    //
    // The board is a fixed 1:1 aspect shape (every cell is square), so its size
    // MUST come from both the available width AND height -- never a hardcoded
    // dp constant (the bug this exact spot used to have: a flat 300.dp board
    // that simply overflowed the Fold 5 cover screen's ~296dp usable width, and
    // badly overflowed its ~296dp usable height once rotated to cover-landscape)
    // and never width alone. The outer BoxWithConstraints below also picks
    // between a stacked (chrome above board) and side-by-side (chrome beside
    // board) arrangement based on the window's own aspect ratio, so a
    // landscape-class window -- most importantly the Fold 5 cover screen
    // rotated to landscape, ~882x344dp, the tightest real vertical budget
    // anywhere in this app -- doesn't starve the board down by stacking five
    // rows of stat text above it. Per-screen local if/else, matching how
    // AirHockeyScreen/other screens in this pass solve the same problem rather
    // than a shared abstraction (see AdaptiveTwoPane's own KDoc precedent).
    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize().padding(24.dp),
        primary = {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isLandscape = maxWidth > maxHeight

                val chrome: @Composable () -> Unit = {
                    Text("Puzzles solved: $puzzlesSolved", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}" +
                            " (${s.size}×${s.size})",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    // Defensively horizontal-scrollable: four chip labels normally fit even
                    // in a narrow cover-portrait width, but a narrow landscape side column
                    // (see chromeWidth below) has much less room to work with.
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ImageSourceChip("Numbers", imageSource == PuzzleImageSource.NUMBERS) { imageSource = PuzzleImageSource.NUMBERS }
                        ImageSourceChip("Sunset", imageSource == PuzzleImageSource.BUILT_IN_SUNSET) { imageSource = PuzzleImageSource.BUILT_IN_SUNSET }
                        ImageSourceChip("Koi", imageSource == PuzzleImageSource.BUILT_IN_KOI) { imageSource = PuzzleImageSource.BUILT_IN_KOI }
                        ImageSourceChip("Photo…", imageSource == PuzzleImageSource.CUSTOM) {
                            pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Moves: ${s.moveCount}   Time: ${formatElapsed(displayedElapsedMillis)}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Best: " + (record.bestMoves?.let { "$it moves" } ?: "—") +
                            " · " + (record.bestTimeMillis?.let { formatElapsed(it) } ?: "—"),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                val board: @Composable () -> Unit = {
                    // Sits in a slot that's genuinely bounded on BOTH axes in either branch
                    // below (weight(1f) inside a fillMaxSize Row/Column, never wrap-content),
                    // so maxWidth/maxHeight here are real window-derived bounds, not
                    // effectively-infinite ones -- exactly the distinction the fold/rotation
                    // layout-correctness pass exists to get right.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val available = minOf(maxWidth, maxHeight)
                        // Every touch target floor: never let a cell shrink below a real tap
                        // size, matching CardScale.kt's rememberCardScaleMultiplier floor. If
                        // that floor pushes the board bigger than this slot's own bounds (only
                        // realistic on a pathologically tiny window with a 5x5 grid),
                        // requiredSize deliberately lets it overflow rather than silently
                        // shrinking every cell under a usable tap size.
                        val cellSize = (available / s.size).coerceAtLeast(48.dp)
                        val boardSize = cellSize * s.size

                        // Plain manually-laid-out Box, not LazyVerticalGrid: the board never
                        // scrolls and tops out at 5x5, so Lazy virtualization buys nothing here
                        // while getting in the way of per-tile animation -- a Lazy grid keys its
                        // items by grid INDEX, which is exactly wrong for animating a tile that
                        // just changed index. Children below are absolute-positioned via
                        // Modifier.offset from the board's own known size/cell-count instead.
                        Box(
                            modifier = Modifier
                                .requiredSize(boardSize)
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

                        if (s.solved) {
                            SolvedPanel(
                                newBestMessage = newBestMessage,
                                onPlayAgain = game::playAgain,
                                onReset = game::resetToInitial,
                                onBackToMenu = game::leaveSession
                            )
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
                        ) { chrome() }
                        Spacer(Modifier.width(16.dp))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { board() }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        chrome()
                        Spacer(Modifier.height(16.dp))
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { board() }
                    }
                }
            }
        }
    )
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
 * [image], when non-null (picture-slice mode -- see [sliceIntoTiles]), is drawn cropped to
 * this tile's own home cell instead of the flat [color] fill, plus a small numbered corner
 * badge for solvability; null keeps today's exact flat-color-mosaic look. Either way, a
 * subtle shadow + border gives every tile a physical, beveled/inset read -- no rotation, no
 * perspective, so it never competes with the solving itself.
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

    // Subtle bevel/inset: a soft elevation shadow plus a thin dark border makes each tile read
    // as a small physical, raised object -- deliberately just this, no rotation/perspective,
    // so it never competes with the actual solving cognition the way a flashier effect would.
    val tileShape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .offset(x = x.value, y = y.value)
            .size(cellSize)
            .padding(2.dp)
            .shadow(elevation = 2.dp, shape = tileShape, clip = false)
            .clip(tileShape)
            .background(color)
            .border(width = 1.dp, color = Color.Black.copy(alpha = 0.28f), shape = tileShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Tile $value, row ${row + 1} column ${col + 1}" },
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
            ) {
                Text(value.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        } else {
            Text(
                value.toString(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun SolvedPanel(
    newBestMessage: String?,
    onPlayAgain: () -> Unit,
    onReset: () -> Unit,
    onBackToMenu: () -> Unit
) {
    Card(modifier = Modifier.padding(16.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Solved!", style = MaterialTheme.typography.headlineSmall)
            if (newBestMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    newBestMessage,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onPlayAgain) {
                Text("New Puzzle")
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Replays this same puzzle's original scramble, as opposed to "New Puzzle"
            // above dealing a brand-new one — see SlidingPuzzleGame.resetToInitial's KDoc.
            OutlinedButton(onClick = onReset) {
                Text("Reset puzzle")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onBackToMenu) {
                Text("Back to Menu")
            }
        }
    }
}

/** Which image the board's tiles are cut from — see [sliceIntoTiles]. [NUMBERS] is the original,
 *  unchanged flat-color-mosaic look; the rest are real picture-slice mode. */
private enum class PuzzleImageSource { NUMBERS, BUILT_IN_SUNSET, BUILT_IN_KOI, CUSTOM }

/** One Numbers/Sunset/Koi/Photo… selector label — a plain clickable Text, matching this
 *  screen's otherwise text-only header rather than pulling in a whole chip/segmented-button
 *  component for four options. */
@Composable
private fun ImageSourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    )
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
