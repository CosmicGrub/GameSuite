package com.gamesuite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.wordgames.crossword.CrosswordGame
import com.gamesuite.games.wordgames.crossword.CrosswordPos
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
import kotlin.random.Random

/**
 * Floor on grid cell size. Below this, stretching every cell to fit
 * maxWidth / gridSize (the old behavior) shrinks cells to ~20-21dp on very
 * narrow displays (e.g. a Fold's cover screen) — instead the grid holds this
 * minimum and becomes horizontally scrollable.
 */
private val MIN_CELL_SIZE = 24.dp

/**
 * Below this cell size, a revealed cell's TopStart clue-number badge and its
 * Center-aligned letter have essentially no clearance from each other. Once
 * a cell is revealed the number is no longer needed (the player can already
 * see the letter), so it's dropped instead of crowding the letter, which
 * itself shrinks to a smaller text style.
 */
private val COMFORTABLE_CELL_SIZE = 32.dp

/**
 * Premium 2026 vision pass — this was the least-animated game in the suite
 * (zero animation work before this pass) and now gets: a per-entry solve
 * where each cell's letter fades+scales in staggered along the entry
 * direction (see [justSolvedEntry] below); an answer dialog that actually
 * HOLDS on a scale-pulse checkmark beat before dismissing on a correct
 * submit instead of just vanishing, and shakes with a red flash + FAILURE
 * haptic on a wrong one; hints get their own honest amber/gold pulse
 * (distinct from a solve's green) via [CrosswordGame.justHintedCells] — the
 * game previously had no way to show "earned vs given" at all; the clue
 * list strikes through with a draw-on line instead of an instant color
 * flip; and the full-grid solve gets a real premium ceiling: a "wave of
 * ink" pass over every filled cell staggered by distance from the top-left
 * corner, a per-letter cascading "Puzzle solved!" (see [CascadingSolvedText])
 * instead of the shared generic banner, and a three-tier haptic hierarchy
 * (LIGHT_TICK on hint < STRONG_ACTION per completed word < CELEBRATION on
 * the full grid).
 *
 * Ambient identity: a cached newsprint/off-white paper background (see
 * [newsprintBackground]) — this game's own distinct "puzzle-book" material,
 * separate from Hangman's chalkboard and Word Search's graph paper.
 */
@Composable
fun CrosswordScreen(
    sessionManager: GameSessionManager,
    game: CrosswordGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val sheenEnabled = LocalCard3DMode.current && !reducedMotion
    val haptics = rememberHaptics()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CROSSWORD, enabled = musicEnabled)

    // Solved-cell flash: submitAnswer() stamps the newly-revealed entry's cells into
    // justSolvedCells as a one-shot signal (see its KDoc); this clears it back to empty once
    // the flash has had time to play, so the next correct submit gets a fresh pulse.
    val justSolvedCells = game.justSolvedCells.value
    val justHintedCells = game.justHintedCells.value

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

    val s = state ?: return
    val puzzlesSolved = game.puzzlesSolved.value

    LaunchedEffect(justSolvedCells) {
        if (justSolvedCells.isNotEmpty()) {
            // A full-grid solve gets its own CELEBRATION tier below — this per-word
            // STRONG_ACTION tier is for every OTHER completed entry along the way.
            if (!s.matchOver) {
                haptics(HapticSignal.STRONG_ACTION)
                sounds.playPlace()
            }
            delay(if (reducedMotion) 0L else 450L)
            game.clearJustSolved()
        }
    }
    LaunchedEffect(justHintedCells) {
        if (justHintedCells.isNotEmpty()) {
            haptics(HapticSignal.LIGHT_TICK)
            sounds.playTap()
            delay(if (reducedMotion) 0L else 420L)
            game.clearJustHinted()
        }
    }

    // Full-grid finale: a soft "wave of ink" sweep across every filled cell, staggered by
    // distance from the top-left corner (see the per-cell wave math below), plus a layered
    // completion flourish and the top haptic tier.
    val inkWave = remember { Animatable(0f) }
    LaunchedEffect(s.matchOver) {
        if (!s.matchOver) {
            inkWave.snapTo(0f)
            return@LaunchedEffect
        }
        haptics(HapticSignal.CELEBRATION)
        if (enhanced) {
            sounds.playShuffle()
            delay(70)
            sounds.playTap()
            delay(70)
            sounds.playDraw()
            inkWave.snapTo(-0.2f)
            inkWave.animateTo(1.2f, tween(1000))
        }
    }

    // The entry a just-completed correct submit belongs to, if any — used only to look up
    // each of its cells' index along the entry so the letter-reveal animation below can stagger
    // in that same direction; a single hint reveal (justSolvedCells empty) simply finds no match
    // here and animates in immediately with no stagger, which is the desired behavior either way.
    val justSolvedEntry = remember(justSolvedCells, s.entries) {
        if (justSolvedCells.isEmpty()) null else s.entries.firstOrNull { it.cells.toSet() == justSolvedCells }
    }

    // Dialog visibility is tracked locally rather than driven straight off s.selectedEntryId:
    // submitAnswer() clears selectedEntryId the instant a correct guess lands, but the dialog
    // itself needs to stay open a beat longer to show the "Correct!" checkmark pulse (see
    // AnswerDialog's justCorrect state) before actually closing.
    var openEntryId by remember { mutableStateOf<String?>(null) }

    AdaptiveTwoPane(
        foldState = LocalFoldState.current,
        modifier = Modifier.fillMaxSize(),
        primary = {
            Column(modifier = Modifier.fillMaxSize().newsprintBackground().padding(12.dp)) {
                Text(
                    "Puzzles solved: $puzzlesSolved",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "Difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${s.solvedEntryIds.size}/${s.entries.size} solved",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .specularSweep(enabled = sheenEnabled, tint = Color.White.copy(alpha = 0.08f))
                ) {
                    val gridDimension = s.grid.size
                    // Fixed-aspect (square, gridDimension x gridDimension) board —
                    // size from BOTH the available width AND the available height of
                    // this BoxWithConstraints. Both are REAL here (this Box already
                    // carries weight(1f, fill = false) inside the bounded outer
                    // Column, so maxHeight is a genuine finite share of it, not
                    // unbounded) — width alone (the old formula) would size the grid
                    // to the FULL width of a wide-but-short window (a cover screen
                    // rotated to landscape, or the TABLET branch's 840dp-capped pane)
                    // regardless of how little vertical room is actually left after
                    // the header and the clue list below, forcing constant internal
                    // grid scrolling just to see the rest of the puzzle instead of
                    // showing it all at once.
                    val naturalCellSizeFromWidth = maxWidth / gridDimension
                    val naturalCellSizeFromHeight = maxHeight / gridDimension
                    val naturalCellSize = minOf(naturalCellSizeFromWidth, naturalCellSizeFromHeight)
                    // Never let cells get smaller than MIN_CELL_SIZE — below that,
                    // hold the floor and let the grid scroll horizontally instead.
                    val cellSize = maxOf(naturalCellSize, MIN_CELL_SIZE)
                    // Compare the FINAL cell size against the width-derived natural
                    // size specifically (not the min-of-both value) — a grid that's
                    // shrunk because of the HEIGHT budget can still fit the width
                    // fine, and forcing an unnecessary horizontal-scroll wrapper in
                    // that case would be a no-op at best and a stray extra scroll
                    // gesture at worst.
                    val needsHorizontalScroll = cellSize * gridDimension > maxWidth
                    val maxCornerDist = if (gridDimension > 1) {
                        kotlin.math.sqrt(2f) * (gridDimension - 1)
                    } else 1f

                    val grid: @Composable () -> Unit = {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridDimension),
                            modifier = if (needsHorizontalScroll) {
                                Modifier.width(cellSize * gridDimension)
                            } else {
                                Modifier.fillMaxWidth()
                            }
                        ) {
                            items(gridDimension * gridDimension) { index ->
                                val row = index / gridDimension
                                val col = index % gridDimension
                                val cell = s.grid[row][col]
                                val posHere = CrosswordPos(row, col)

                                val cellModifier = if (cell.letter != null) {
                                    val membership = s.entries
                                        .filter { entry -> entry.cells.any { it.row == row && it.col == col } }
                                        .joinToString(", ") { entry ->
                                            "${entry.number} ${if (entry.direction.name == "ACROSS") "across" else "down"}"
                                        }
                                    val letterStatus = if (cell.revealed) "letter ${cell.letter}" else "blank"
                                    Modifier.semantics { contentDescription = "$letterStatus, $membership" }
                                } else {
                                    Modifier
                                }

                                val justSolved = cell.letter != null && posHere in justSolvedCells
                                val justHinted = cell.letter != null && posHere in justHintedCells
                                val cellDist = if (cell.letter != null) {
                                    kotlin.math.sqrt((row * row + col * col).toFloat()) / maxCornerDist
                                } else 0f
                                val wavePassed = s.matchOver && inkWave.value >= cellDist
                                val waveBand = if (s.matchOver && cell.letter != null) {
                                    (1f - kotlin.math.abs(cellDist - inkWave.value) / 0.16f).coerceIn(0f, 1f)
                                } else 0f

                                val restingColor = if (cell.letter == null) Color.Transparent else Color(0xFFFAFAFA)
                                val targetColor = when {
                                    justSolved -> Color(0xFFA5D6A7)
                                    justHinted -> Color(0xFFFFCC80)
                                    wavePassed -> Color(0xFFD7CCC8)
                                    else -> restingColor
                                }
                                val cellBackground by animateColorAsState(
                                    targetValue = targetColor,
                                    animationSpec = if (reducedMotion) snap() else tween(350),
                                    label = "crosswordCellFlash"
                                )

                                // Per-entry solve: each cell's letter fades+scales in, staggered
                                // along the entry direction via justSolvedEntry's cell order.
                                val letterScale = remember(row, col) { Animatable(1f) }
                                val letterAlpha = remember(row, col) { Animatable(1f) }
                                LaunchedEffect(cell.revealed) {
                                    if (cell.revealed) {
                                        val staggerIndex = justSolvedEntry?.cells?.indexOf(posHere) ?: -1
                                        if (staggerIndex > 0 && !reducedMotion) delay(staggerIndex * 45L)
                                        if (reducedMotion) {
                                            letterScale.snapTo(1f)
                                            letterAlpha.snapTo(1f)
                                        } else {
                                            letterScale.snapTo(0.3f)
                                            letterAlpha.snapTo(0f)
                                            launch { letterAlpha.animateTo(1f, tween(220)) }
                                            letterScale.animateTo(1f, tween(220))
                                        }
                                    } else {
                                        letterScale.snapTo(1f)
                                        letterAlpha.snapTo(1f)
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .padding(0.5.dp)
                                        .aspectRatio(1f)
                                        .background(cellBackground)
                                        .then(
                                            if (waveBand > 0f) {
                                                Modifier.background(Color.White.copy(alpha = waveBand * 0.45f))
                                            } else Modifier
                                        )
                                        .then(cellModifier),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (cell.letter != null) {
                                        val cramped = cellSize < COMFORTABLE_CELL_SIZE
                                        if (cell.number != null && !(cell.revealed && cramped)) {
                                            Text(
                                                cell.number.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.align(Alignment.TopStart)
                                            )
                                        }
                                        if (cell.revealed) {
                                            val letterModifier = Modifier.graphicsLayer {
                                                scaleX = letterScale.value
                                                scaleY = letterScale.value
                                                alpha = letterAlpha.value
                                            }
                                            if (cramped) {
                                                Text(cell.letter.toString(), style = MaterialTheme.typography.bodySmall, modifier = letterModifier)
                                            } else {
                                                Text(cell.letter.toString(), modifier = letterModifier)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (needsHorizontalScroll) {
                        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            grid()
                        }
                    } else {
                        grid()
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Clues", style = MaterialTheme.typography.titleSmall)

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(s.entries.sortedBy { it.number }) { entry ->
                        val solved = entry.id in s.solvedEntryIds
                        val strikeProgress by animateFloatAsState(
                            targetValue = if (solved) 1f else 0f,
                            animationSpec = if (reducedMotion) snap() else tween(340),
                            label = "crosswordClueStrike"
                        )
                        val defaultTextColor = LocalContentColor.current
                        val textColor by animateColorAsState(
                            targetValue = if (solved) Color(0xFF388E3C) else defaultTextColor,
                            animationSpec = if (reducedMotion) snap() else tween(340),
                            label = "crosswordClueColor"
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Explicit 48dp touch-target floor — a raw clickable Row
                                // wrapping a single line of text otherwise lands well
                                // under it (unlike Button/OutlinedButton elsewhere in
                                // this game, which already carry Material3's own
                                // minimum), matching the floor pattern in CardScale.kt.
                                .heightIn(min = 48.dp)
                                .clickable(enabled = !solved) {
                                    game.selectEntry(entry.id)
                                    openEntryId = entry.id
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(vertical = 6.dp)
                                .drawWithContent {
                                    drawContent()
                                    if (strikeProgress > 0f) {
                                        val y = size.height / 2f
                                        drawLine(
                                            color = Color(0xFF2E7D32),
                                            start = Offset(0f, y),
                                            end = Offset(size.width * strikeProgress, y),
                                            strokeWidth = 2.5f,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                        ) {
                            Text(
                                "${entry.number}${if (entry.direction.name == "ACROSS") "A" else "D"}. ${entry.clue}",
                                color = textColor
                            )
                        }
                    }
                }

                if (s.matchOver) {
                    Spacer(Modifier.height(8.dp))
                    CascadingSolvedText(reducedMotion = reducedMotion)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = game::playAgain, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("New Puzzle") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = game::leaveSession, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Back to Menu") }
                } else {
                    Spacer(Modifier.height(8.dp))
                    // The hint action itself lives in AnswerDialog below (it reveals
                    // a letter of whichever entry is selected, reusing that same
                    // selection state) — this just surfaces the shared per-puzzle
                    // budget so it's visible even before a clue is tapped.
                    Text(
                        "Hints left: ${game.hintsRemaining.value}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    )

    val dialogEntry = s.entries.firstOrNull { it.id == openEntryId }
    if (dialogEntry != null) {
        // Pattern of what's revealed so far for this entry, so the hint button
        // inside the dialog has something to act on and the player can see
        // its effect without leaving the dialog.
        val pattern = dialogEntry.cells.joinToString(" ") { pos ->
            val gridCell = s.grid[pos.row][pos.col]
            if (gridCell.revealed) gridCell.letter.toString() else "_"
        }
        AnswerDialog(
            clue = dialogEntry.clue,
            length = dialogEntry.word.length,
            pattern = pattern,
            hintsRemaining = game.hintsRemaining.value,
            reducedMotion = reducedMotion,
            onHint = { game.revealNextLetter() },
            onSubmit = { answer -> game.submitAnswer(dialogEntry.id, answer) },
            onDismiss = {
                game.clearSelection()
                openEntryId = null
            },
            onCorrectSettled = { openEntryId = null }
        )
    }
}

@Composable
private fun AnswerDialog(
    clue: String,
    length: Int,
    pattern: String,
    hintsRemaining: Int,
    reducedMotion: Boolean,
    onHint: () -> Unit,
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit,
    onCorrectSettled: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    // Set when a submitted guess didn't match, so the field can show an
    // inline error instead of leaving the dialog looking unresponsive.
    // Cleared as soon as the user edits the field again.
    var isError by remember { mutableStateOf(false) }
    // Increments on every wrong submit (even a repeat of the same wrong text) so the
    // shake/haptic below reliably retriggers, rather than being keyed on isError's
    // boolean value which wouldn't change on a second identical wrong guess.
    var wrongAttempt by remember { mutableStateOf(0) }
    var justCorrect by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val scope = rememberCoroutineScope()
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(wrongAttempt) {
        if (wrongAttempt == 0) return@LaunchedEffect
        haptics(HapticSignal.FAILURE)
        // Previously silent — a wrong answer submit only shook the field and
        // fired a haptic, with no distinct sound of its own (unlike every
        // other outcome in this game, which already has one).
        playSfx(SfxKind.INVALID_BUZZ)
        if (!reducedMotion) {
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(-10f, tween(45))
            shakeOffset.animateTo(10f, tween(65))
            shakeOffset.animateTo(-7f, tween(65))
            shakeOffset.animateTo(7f, tween(55))
            shakeOffset.animateTo(0f, tween(45))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.background(Color.White).padding(24.dp)
        ) {
            Text(clue)
            Spacer(Modifier.height(4.dp))
            Text("$length letters", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(pattern, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (justCorrect) {
                CorrectBeat(reducedMotion = reducedMotion)
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.length <= length) text = it.uppercase()
                        isError = false
                    },
                    singleLine = true,
                    isError = isError,
                    modifier = Modifier.offset(x = shakeOffset.value.dp),
                    supportingText = if (isError) {
                        { Text("Not quite — try again") }
                    } else null
                )
                Spacer(Modifier.height(8.dp))
                // Reveals one more letter of THIS entry — capped by the shared
                // per-puzzle hint budget (see CrosswordGame.revealNextLetter) and
                // a no-op once every letter here is already shown.
                TextButton(
                    onClick = onHint,
                    enabled = hintsRemaining > 0 && '_' in pattern,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Hint ($hintsRemaining left)") }
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val correct = onSubmit(text)
                            if (correct) {
                                justCorrect = true
                                scope.launch {
                                    delay(if (reducedMotion) 80L else 380L)
                                    onCorrectSettled()
                                }
                            } else {
                                wrongAttempt++
                                isError = true
                            }
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) { Text("Submit") }
                }
            }
        }
    }
}

/** The dialog's "hold on the win" beat — a scale-pulse checkmark plus a settled "Correct!" label, shown for ~350-400ms before the dialog actually dismisses (see the Submit button's onClick above). */
@Composable
private fun CorrectBeat(reducedMotion: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.4f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "crosswordCorrectScale"
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
        ) {
            drawCircle(color = Color(0xFF43A047), radius = size.minDimension / 2f)
            val w = size.width
            val h = size.height
            val checkPath = Path().apply {
                moveTo(w * 0.28f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.68f)
                lineTo(w * 0.74f, h * 0.32f)
            }
            drawPath(
                checkPath,
                color = Color.White,
                style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Correct!", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
    }
}

/** Full-grid-solve headline: each character cascades in on its own stagger instead of the shared suite-wide banner popping in as one block. */
@Composable
private fun CascadingSolvedText(reducedMotion: Boolean) {
    val text = "Puzzle solved!"
    Row {
        text.forEachIndexed { i, ch ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(if (reducedMotion) 0L else i * 40L)
                visible = true
            }
            AnimatedVisibility(
                visible = visible,
                enter = if (reducedMotion) {
                    fadeIn(snap())
                } else {
                    fadeIn(tween(180)) + scaleIn(initialScale = 0.4f, animationSpec = tween(180))
                }
            ) {
                Text(
                    ch.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Newsprint/off-white paper ambient identity — a warm off-white sheet with
 * faint speckling and column-rule lines, distinct from Hangman's chalkboard
 * and Word Search's graph paper. [drawWithCache] rebuilds the speckle
 * positions only when the drawing size actually changes.
 */
@Composable
private fun Modifier.newsprintBackground(): Modifier = this.drawWithCache {
    val rng = Random(2024)
    val flecks = List(220) { Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height) }
    onDrawBehind {
        drawRect(Color(0xFFF7F3EA))
        var y = 36f
        while (y < size.height) {
            drawLine(Color(0xFFBDBDBD).copy(alpha = 0.14f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += 44f
        }
        flecks.forEach { pos ->
            drawCircle(color = Color(0xFF9E9E9E).copy(alpha = 0.06f), radius = 0.9f, center = pos)
        }
    }
}
