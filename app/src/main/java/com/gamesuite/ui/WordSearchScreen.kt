package com.gamesuite.ui

import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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
import com.gamesuite.games.wordgames.wordsearch.GridPos
import com.gamesuite.games.wordgames.wordsearch.PlacedWord
import com.gamesuite.games.wordgames.wordsearch.SelectionResult
import com.gamesuite.games.wordgames.wordsearch.WordSearchGame
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

/** Smallest a grid cell is allowed to shrink to — below this, tapping accuracy suffers. */
private val MIN_CELL_SIZE = 24.dp
private val CELL_SPACING = 1.dp

/**
 * Premium 2026 vision pass: the old two-discrete-taps flow (tap a start
 * cell, tap an end cell, with zero visual feedback in between) is replaced
 * by real drag-to-select — a live highlighter trace that follows the finger,
 * solidifying to found-green with a staggered along-the-line letter pulse on
 * a hit, or a fade+shake on a miss instead of a silent reset. The whole grid
 * moved from a per-cell `LazyVerticalGrid` to a single [Canvas]: the same
 * surface draws the live trace, the lock-in stroke, the per-cell pulse, and
 * the full-board finale sweep, and one `pointerInput` on it avoids fighting
 * the grid's own default scroll-gesture recognizer for the drag. This does
 * trade away the old per-cell screen-reader semantics (a raw Canvas can't
 * carry them the way individual clickable cells could) — the word list
 * below stays fully text-based/accessible as the primary progress readout,
 * and the grid itself gets one summary contentDescription.
 *
 * Ambient identity: a cached graph-paper background (see
 * [graphPaperBackground]), matching this game's "puzzle-book" family
 * without borrowing Hangman's chalkboard or Crossword's newsprint look.
 */
@Composable
fun WordSearchScreen(
    sessionManager: GameSessionManager,
    game: WordSearchGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's puzzle for every player — see WordSearchGame.startMatch(seed)'s
     *  KDoc. Computed by the caller (e.g. a "Daily Challenge" menu entry) from today's date;
     *  this screen has no calendar knowledge of its own. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val sheenEnabled = LocalCard3DMode.current && !reducedMotion
    val haptics = rememberHaptics()
    val sounds = remember { CardSounds.get(androidContext) }
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.WORD_SEARCH, enabled = musicEnabled)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.loadDictionary(androidContext)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch(dailySeed)
    }

    val s = state ?: return
    val puzzlesSolved = game.puzzlesSolved.value

    // Full-board finale: a soft diagonal light sweep once every word is found, synced
    // with a richer completion haptic — see the LaunchedEffect(s.solved) below.
    val finaleSweep = remember { Animatable(0f) }
    LaunchedEffect(s.solved) {
        if (!s.solved) {
            finaleSweep.snapTo(0f)
            return@LaunchedEffect
        }
        haptics(HapticSignal.CELEBRATION)
        if (enhanced) {
            delay(250) // let the last word's own lock-in pulse land first
            sounds.playShuffle()
            delay(70)
            sounds.playTap()
            delay(70)
            sounds.playDraw()
            finaleSweep.snapTo(-0.25f)
            finaleSweep.animateTo(1.25f, tween(900))
        }
    }

    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize(),
        primary = {
            // Capture the REAL bounded height of this pane BEFORE the Column below
            // applies verticalScroll — a scrollable Column measures its children
            // with an unbounded (infinite) max height in the scroll axis, so a
            // BoxWithConstraints placed INSIDE it (like the one around the grid
            // further down) can never see a real height to size a square grid
            // against. This outer BoxWithConstraints, measured against this pane's
            // own incoming constraints, is the one genuine height signal available
            // on this screen, and it's real regardless of which AdaptiveLayoutMode
            // branch placed this primary slot.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val availableHeight = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphPaperBackground()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text("Puzzles solved: $puzzlesSolved", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Find all ${s.placedWords.size} words", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    SimpleFlowRow(modifier = Modifier.fillMaxWidth()) {
                        s.placedWords.forEach { pw ->
                            WordChip(word = pw, isFound = pw.id in s.foundWords, reducedMotion = reducedMotion)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val cellFoundSet: Set<GridPos> = remember(s.foundWords) {
                        s.placedWords.filter { it.id in s.foundWords }.flatMap { it.cells }.toSet()
                    }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .specularSweep(enabled = sheenEnabled, tint = Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        val gridSize = s.grid.size
                        // Fixed-aspect (square) board — size from BOTH the available
                        // width (this BoxWithConstraints' own, correctly bounded by
                        // the Column's padding) AND the available height (from the
                        // OUTER, pre-scroll BoxWithConstraints above). A wide-but-short
                        // window (the Fold cover screen rotated to landscape has tons
                        // of width and very little height) sized from width alone
                        // used to make the grid far taller than the viewport, forcing
                        // a lot of vertical scrolling just to reach the bottom rows
                        // and the buttons below — this budgets a flat estimate for
                        // the header/word-chip/button chrome above and below the grid
                        // and fits the grid to whatever's left, in every orientation.
                        val estimatedChromeHeight = 200.dp
                        val heightBudget = (availableHeight - estimatedChromeHeight)
                            .coerceAtLeast(MIN_CELL_SIZE * gridSize)
                        val naturalCellSizeFromWidth = (maxWidth - CELL_SPACING * (gridSize - 1)) / gridSize
                        val naturalCellSizeFromHeight = (heightBudget - CELL_SPACING * (gridSize - 1)) / gridSize
                        val naturalCellSize = minOf(naturalCellSizeFromWidth, naturalCellSizeFromHeight)
                        val cellSize = maxOf(naturalCellSize, MIN_CELL_SIZE)
                        val gridWidth = cellSize * gridSize + CELL_SPACING * (gridSize - 1)
                        val cellSizePx = with(density) { cellSize.toPx() }
                        val spacingPx = with(density) { CELL_SPACING.toPx() }

                        val gridContent: @Composable () -> Unit = {
                            WordSearchGrid(
                                game = game,
                                grid = s.grid,
                                cellFoundSet = cellFoundSet,
                                gridSize = gridSize,
                                cellSizePx = cellSizePx,
                                spacingPx = spacingPx,
                                gridWidth = gridWidth,
                                solved = s.solved,
                                finaleSweep = finaleSweep,
                                reducedMotion = reducedMotion,
                                haptics = haptics,
                                sounds = sounds,
                                playSfx = playSfx,
                                scope = scope
                            )
                        }

                        // Horizontal scroll is only needed when the FINAL cell size
                        // (after the MIN_CELL_SIZE floor) makes the grid wider than
                        // what's actually available — comparing against the
                        // width-derived natural size specifically (not the
                        // min-of-both value) avoids wrapping in a scroll container
                        // that's a no-op when the grid was really height-constrained
                        // instead.
                        if (cellSize > naturalCellSizeFromWidth) {
                            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) { gridContent() }
                        } else {
                            gridContent()
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (s.solved) {
                        Text(
                            "All words found!",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF2E7D32)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = game::playAgain) { Text("New Puzzle") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
                    } else {
                        OutlinedButton(onClick = game::leaveSession) { Text("Back to Menu") }
                    }
                }
            }
        }
    )
}

/** One drawn/interactive state of the trace line currently on top of the grid. */
private sealed class TraceLine {
    class Dragging(val start: GridPos) : TraceLine()
    class Locking(val start: GridPos, val end: GridPos, val colorProgress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, val endProgress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) : TraceLine()
    class Missing(val start: GridPos, val rawEnd: Offset, val fade: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) : TraceLine()
}

@Composable
private fun WordSearchGrid(
    game: WordSearchGame,
    grid: List<List<Char>>,
    cellFoundSet: Set<GridPos>,
    gridSize: Int,
    cellSizePx: Float,
    spacingPx: Float,
    gridWidth: Dp,
    solved: Boolean,
    finaleSweep: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    reducedMotion: Boolean,
    haptics: (HapticSignal) -> Unit,
    sounds: CardSounds,
    playSfx: (SfxKind) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var dragCurrentPx by remember { mutableStateOf<Offset?>(null) }
    var traceLine by remember { mutableStateOf<TraceLine?>(null) }
    val pulseAnims = remember { mutableStateMapOf<GridPos, Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>() }
    val shakeOffset = remember { Animatable(0f) }

    val letterPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    val tentativeColor = Color(0xFFFFEE58)
    val foundColor = Color(0xFF81C784)

    suspend fun runMiss(start: GridPos, rawEnd: Offset) {
        val fade = Animatable(1f)
        traceLine = TraceLine.Missing(start, rawEnd, fade)
        haptics(HapticSignal.FAILURE)
        sounds.playDraw()
        if (!reducedMotion) {
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(-cellSizePx * 0.18f, tween(40))
            shakeOffset.animateTo(cellSizePx * 0.18f, tween(60))
            shakeOffset.animateTo(-cellSizePx * 0.1f, tween(60))
            shakeOffset.animateTo(0f, tween(50))
        }
        fade.animateTo(0f, tween(if (reducedMotion) 60 else 260))
        traceLine = null
    }

    suspend fun runLockIn(result: SelectionResult) {
        val start = result.orderedCells.first()
        val end = result.orderedCells.last()
        val colorProgress = Animatable(0f)
        val endProgress = Animatable(0f)
        traceLine = TraceLine.Locking(start, end, colorProgress, endProgress)
        val justSolved = game.state.value?.solved == true
        if (!justSolved) {
            haptics(HapticSignal.SUCCESS)
            sounds.playPlace()
        }
        if (reducedMotion) {
            colorProgress.snapTo(1f)
            endProgress.snapTo(1f)
        } else {
            scope.launch { colorProgress.animateTo(1f, tween(150)) }
            endProgress.animateTo(1f, tween(150))
        }
        traceLine = null

        if (!reducedMotion) {
            result.orderedCells.forEachIndexed { i, pos ->
                scope.launch {
                    delay(i * 60L)
                    val anim = Animatable(0f)
                    pulseAnims[pos] = anim
                    anim.animateTo(1f, tween(140))
                    anim.animateTo(0f, tween(160))
                    pulseAnims.remove(pos)
                }
            }
        }
    }

    fun offsetToCell(offset: Offset): GridPos {
        val step = cellSizePx + spacingPx
        val col = (offset.x / step).toInt().coerceIn(0, gridSize - 1)
        val row = (offset.y / step).toInt().coerceIn(0, gridSize - 1)
        return GridPos(row, col)
    }

    fun cellCenter(pos: GridPos): Offset {
        val step = cellSizePx + spacingPx
        return Offset(pos.col * step + cellSizePx / 2f, pos.row * step + cellSizePx / 2f)
    }

    Box(modifier = Modifier.graphicsLayer { translationX = shakeOffset.value }) {
        Canvas(
            modifier = Modifier
                .size(width = gridWidth, height = gridWidth)
                .semantics { contentDescription = "Word search grid, drag from one letter to another to select a word" }
                .pointerInput(gridSize, cellSizePx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (game.state.value?.solved == true) return@detectDragGestures
                            val cell = offsetToCell(offset)
                            traceLine = TraceLine.Dragging(cell)
                            dragCurrentPx = offset
                            game.setSelectionStart(cell)
                            haptics(HapticSignal.LIGHT_TICK)
                            // Previously silent — only the haptic fired when a selection
                            // starts on a letter, with no accompanying sound at all.
                            playSfx(SfxKind.LIGHT_TICK)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragCurrentPx = change.position
                        },
                        onDragEnd = {
                            val dragging = traceLine as? TraceLine.Dragging
                            val endPx = dragCurrentPx
                            if (dragging != null && endPx != null) {
                                val endCell = offsetToCell(endPx)
                                val result = game.attemptSelection(dragging.start, endCell)
                                if (result != null) {
                                    scope.launch { runLockIn(result) }
                                } else {
                                    traceLine = null
                                    scope.launch { runMiss(dragging.start, endPx) }
                                }
                            } else {
                                traceLine = null
                            }
                            dragCurrentPx = null
                        },
                        onDragCancel = {
                            traceLine = null
                            dragCurrentPx = null
                            game.setSelectionStart(null)
                        }
                    )
                }
        ) {
            // Cell fills + letters.
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    val pos = GridPos(row, col)
                    val topLeft = cellTopLeft(pos, cellSizePx, spacingPx)
                    val isFound = pos in cellFoundSet
                    if (isFound) {
                        drawRect(color = foundColor, topLeft = topLeft, size = Size(cellSizePx, cellSizePx))
                    }
                    val pulse = pulseAnims[pos]?.value ?: 0f
                    if (pulse > 0f) {
                        drawRect(color = Color.White.copy(alpha = pulse * 0.6f), topLeft = topLeft, size = Size(cellSizePx, cellSizePx))
                    }
                    val sweep = finaleSweep.value
                    if (sweep > 0f) {
                        val diag = (row + col).toFloat() / (2f * (gridSize - 1).coerceAtLeast(1))
                        val band = (1f - kotlin.math.abs(diag - sweep) / 0.18f).coerceIn(0f, 1f)
                        if (band > 0f) {
                            drawRect(color = Color.White.copy(alpha = band * 0.4f), topLeft = topLeft, size = Size(cellSizePx, cellSizePx))
                        }
                    }
                    letterPaint.textSize = cellSizePx * 0.5f
                    letterPaint.color = if (isFound) Color(0xFF1B5E20).toArgb() else Color(0xFF263238).toArgb()
                    val center = cellCenter(pos)
                    // Baseline offset so the glyph optically centers rather than sitting on the midline.
                    drawContext.canvas.nativeCanvas.drawText(
                        grid[row][col].toString(),
                        center.x,
                        center.y + cellSizePx * 0.18f,
                        letterPaint
                    )
                }
            }

            // Live drag trace — a raw line to wherever the finger currently is.
            val dragging = traceLine as? TraceLine.Dragging
            val currentPx = dragCurrentPx
            if (dragging != null && currentPx != null) {
                drawLine(
                    color = tentativeColor.copy(alpha = 0.85f),
                    start = cellCenter(dragging.start),
                    end = currentPx,
                    strokeWidth = cellSizePx * 0.35f,
                    cap = StrokeCap.Round
                )
            }

            // Lock-in stroke: solidifying from tentative-yellow to found-green while
            // finishing the trace to the exact end-cell center.
            (traceLine as? TraceLine.Locking)?.let { locking ->
                val color = lerp(tentativeColor, foundColor, locking.colorProgress.value)
                val end = androidx.compose.ui.geometry.lerp(
                    cellCenter(locking.start), cellCenter(locking.end), locking.endProgress.value
                )
                drawLine(
                    color = color,
                    start = cellCenter(locking.start),
                    end = end,
                    strokeWidth = cellSizePx * 0.35f,
                    cap = StrokeCap.Round
                )
            }

            // Miss feedback: the tentative line fades out instead of vanishing instantly.
            (traceLine as? TraceLine.Missing)?.let { missing ->
                drawLine(
                    color = Color(0xFFE57373).copy(alpha = 0.85f * missing.fade.value),
                    start = cellCenter(missing.start),
                    end = missing.rawEnd,
                    strokeWidth = cellSizePx * 0.35f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun cellTopLeft(pos: GridPos, cellSizePx: Float, spacingPx: Float): Offset {
    val step = cellSizePx + spacingPx
    return Offset(pos.col * step, pos.row * step)
}

@Composable
private fun WordChip(word: PlacedWord, isFound: Boolean, reducedMotion: Boolean) {
    val strikeProgress by animateFloatAsState(
        targetValue = if (isFound) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(320),
        label = "wordSearchStrike"
    )
    val textColor by animateColorAsState(
        targetValue = if (isFound) Color(0xFF7CB342) else Color(0xFF37474F),
        animationSpec = if (reducedMotion) snap() else tween(320),
        label = "wordSearchWordColor"
    )
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .drawWithContent {
                drawContent()
                if (strikeProgress > 0f) {
                    val y = size.height / 2f
                    drawLine(
                        color = Color(0xFF558B2F),
                        start = Offset(0f, y),
                        end = Offset(size.width * strikeProgress, y),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round
                    )
                }
            }
    ) {
        Text(word.word, color = textColor, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Minimal left-to-right, top-to-bottom wrapping row — kept hand-rolled
 * (rather than reaching for `androidx.compose.foundation.layout.FlowRow`)
 * so this file doesn't depend on exactly which Foundation version stabilized
 * that API; a plain [Layout] measure/wrap pass is a handful of lines and
 * works on any Compose version this project could plausibly be on.
 */
@Composable
private fun SimpleFlowRow(
    modifier: Modifier = Modifier,
    horizontalGapDp: Dp = 8.dp,
    verticalGapDp: Dp = 6.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hGap = horizontalGapDp.roundToPx()
        val vGap = verticalGapDp.roundToPx()
        val maxWidth = constraints.maxWidth
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }

        data class Line(val items: MutableList<androidx.compose.ui.layout.Placeable> = mutableListOf(), var width: Int = 0, var height: Int = 0)
        val lines = mutableListOf(Line())
        for (p in placeables) {
            var line = lines.last()
            val extra = if (line.items.isEmpty()) 0 else hGap
            if (line.width + extra + p.width > maxWidth && line.items.isNotEmpty()) {
                line = Line()
                lines.add(line)
            }
            val gapForThis = if (line.items.isEmpty()) 0 else hGap
            line.items.add(p)
            line.width += gapForThis + p.width
            line.height = maxOf(line.height, p.height)
        }

        val totalHeight = lines.sumOf { it.height } + vGap * (lines.size - 1).coerceAtLeast(0)
        layout(maxWidth, totalHeight) {
            var y = 0
            for (line in lines) {
                var x = 0
                for (p in line.items) {
                    p.placeRelative(x, y)
                    x += p.width + hGap
                }
                y += line.height + vGap
            }
        }
    }
}

/**
 * Graph-paper ambient identity — a lightweight cached grid of faint blue
 * ruling lines on an off-white sheet, distinct from Hangman's chalkboard and
 * Crossword's newsprint. [drawWithCache] rebuilds the line list only when
 * the drawing size actually changes, not on every recomposition.
 */
@Composable
private fun Modifier.graphPaperBackground(): Modifier = this.drawWithCache {
    val ruleColor = Color(0xFFBBDEFB).copy(alpha = 0.55f)
    val spacing = 26f
    onDrawBehind {
        drawRect(Color(0xFFFFFDF6))
        var x = 0f
        while (x <= size.width) {
            drawLine(ruleColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += spacing
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(ruleColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += spacing
        }
    }
}
