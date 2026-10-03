package com.gamesuite.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
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
import com.gamesuite.games.wordgames.wordsearch.WordSearchState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

/** Frame between the board's rounded border and its first cell, in dp. It is both the real
 *  `padding` of the board frame and the `framePx` handed to [fitBoard], so the two cannot drift. */
private const val WS_FRAME_DP = 4f

/** Below this cell size (dp) the board stops shrinking and scrolls instead (see
 *  [WordSearchBoardSlot]). Lower than the 28dp the tap-target games use on purpose: a letter here
 *  is never tapped on its own, it is picked by dragging a line across cell centers, so what limits
 *  the cell is legibility, not finger size. 18dp keeps a 15x15 HARD grid on a 312dp Fold 5 cover
 *  screen (a 288dp-wide slot) without scrolling. */
private const val WS_MIN_CELL_DP = 18f

/** Upper bound on a cell (dp) so a small EASY grid does not sprawl across a tablet. */
private const val WS_MAX_CELL_DP = 56f

// The graph-paper identity is an always-light sheet, in light AND dark theme, exactly as before.
// Every piece of text on it therefore uses an explicit ink colour: the old header lines used the
// theme's onSurface, which is near-white in dark theme (1.27:1 on this paper).
private val WS_PAPER = Color(0xFFFFFDF6)
private val WS_RULE = Color(0xFFBBDEFB).copy(alpha = 0.55f)
private val WS_INK = Color(0xFF263238)
private val WS_INK_SOFT = Color(0xFF455A64)
private val WS_CHIP_INK = Color(0xFF37474F)
private val WS_BACKING = Color.White.copy(alpha = 0.55f)
private val WS_TENTATIVE = Color(0xFFFFEE58)
private val WS_FOUND_FILL = Color(0xFF81C784)
private val WS_FOUND_INK = Color(0xFF0B3A10)
private val WS_FOUND_TEXT = Color(0xFF33691E)
private val WS_STRIKE = Color(0xFF558B2F)
private val WS_MISS = Color(0xFFE57373)
private val WS_SOLVED_GREEN = Color(0xFF2E7D32)

private const val WORD_SEARCH_HELP =
    "Find every word on the list hidden in the grid. Words run in straight lines. Easy uses only " +
        "left-to-right and top-to-bottom; Medium and Hard add diagonals and backwards words.\n\n" +
        "Drag from one end of a word to the other (either direction works): a correct word turns " +
        "green and is crossed off the list, a wrong guess just fades away. With a screen reader or " +
        "switch, double-tap the first letter, then the last. If the grid is bigger than the window it " +
        "scrolls, so touch and hold a letter before dragging.\n\n" +
        "Finding every word solves the puzzle, New Puzzle keeps your tally, and the Daily puzzle is the " +
        "same for everyone who plays it that day on the same difficulty."

/**
 * Word search: find every listed word hidden in a letter grid by dragging from its first letter to
 * its last. Real drag-to-select: a live highlighter trace follows the finger, solidifies to
 * found-green with a staggered along-the-line letter pulse on a hit, or fades with a shake on a
 * miss. The whole grid is a single [Canvas] (one `pointerInput`, no per-cell recognizers fighting
 * each other); the same surface draws the live trace, the lock-in stroke, the per-cell pulse and
 * the full-board finale sweep.
 *
 * Ambient identity: a cached graph-paper background (see [graphPaperBackground]), the
 * "puzzle-book" look, distinct from Hangman's chalkboard and Crossword's newsprint. It is an
 * always-light sheet in both themes, with explicit ink colours on top of it.
 *
 * UI-QUALITY PASS (shared GameChrome / fitBoard kit, see ReversiScreen):
 *  - CHROME: the whole screen sits in [GameChrome] (corner menu, back handling, How to Play). The
 *    HUD reserves [GameChromeEndInset] at its end so the corner button never covers it. Leaving
 *    mid-puzzle discards ONLY the unfinished puzzle: with nothing solved yet it is a pure abort
 *    (never recorded); if earlier puzzles in this session were solved, it leaves through
 *    `leaveSession()` (the same call the solved panel's Back to Menu makes) so those still count,
 *    and the dialog says so. The in-puzzle "Back to Menu" button moved into the corner menu.
 *  - SIZING: [fitBoard] against the measured space of the board's own slot, a 18-56dp cell clamp.
 *    Hit-testing and drawing both derive the cell edge from the Canvas's own measured size
 *    ([wordSearchGridCellPx]), so a drag lands on the cell that is drawn under the finger. Cells
 *    are whole pixels, so there is no rounding drift across 15 columns. If the slot cannot give a
 *    cell [WS_MIN_CELL_DP] the board scrolls both ways instead of shrinking or overflowing; since
 *    a drag-consuming surface inside a scroll container could never be panned, selection then
 *    needs a touch-and-hold first (`detectDragGesturesAfterLongPress`), so a plain drag pans.
 *    A wide window (a landscape phone, the cover screen sideways, a tablet) puts the board on the
 *    left and HUD, word list and result in a column on the right.
 *  - OFF THE UI THREAD: the 359k-word dictionary parse and puzzle generation run on
 *    `Dispatchers.Default`; a "Preparing puzzle" placeholder (still inside the chrome, so Back
 *    works) shows meanwhile.
 *  - DRAG RACE: the live drag (start cell plus finger position) and the animating result trace
 *    (lock-in / miss) are separate state, and a finishing animation only clears the trace if it is
 *    still the one on screen. Before, a drag begun while a miss was still fading lost its trace
 *    and its release was dropped silently.
 *  - ACCESSIBILITY: every cell has a screen-reader description (letter, state, row and column,
 *    1-indexed) and a click action, in a layer UNDER the drag Canvas (the Canvas is on top, so
 *    touch still reaches it): double-tap the first letter, then the last, same as a drag. Hits,
 *    misses and the running count are announced through a polite live region in the HUD; word
 *    chips carry a found / not-found state. With `LocalColorblindMode` on, each found word also
 *    gets a dark outline (found letters are bold regardless), so "found" is not green alone.
 *  - DAILY: the HUD labels the first board of the Daily route "Daily puzzle". Word choice is
 *    seeded too (see WordSearchGame.startMatch), so it really is the same puzzle for everyone.
 *  - MOTION: the result panel fades in over 250ms (instant under reduced motion). The existing
 *    juice (pulse, finale sweep, shake) is unchanged and already off under reduced motion.
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
    val colorblind = LocalColorblindMode.current
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    val sheenEnabled = LocalCard3DMode.current && !reducedMotion
    val haptics = rememberHaptics()
    val sounds = remember { CardSounds.get(androidContext) }
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.WORD_SEARCH, enabled = musicEnabled)
    val scope = rememberCoroutineScope()

    // Only the FIRST board of the Daily route is today's puzzle: New Puzzle always deals a fresh,
    // unseeded board (see WordSearchGame.playAgain), so the label goes away after it.
    var onDailyBoard by remember { mutableStateOf(dailySeed != null) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        // The first dictionary load parses ~359k lines and generation draws from it: keep both
        // off the main thread (the old version froze the first frame for the duration).
        withContext(Dispatchers.Default) {
            game.loadDictionary(androidContext)
            game.startMatch(dailySeed)
        }
    }

    val s = state
    if (s == null) {
        WordSearchLoading(game = game)
        return
    }

    val puzzlesSolved = game.puzzlesSolved.value
    // What a screen reader hears after the last selection; reset whenever a new puzzle arrives.
    var announcement by remember(s.placedWords) { mutableStateOf("") }
    val foundWordList = remember(s.placedWords, s.foundWords) { s.placedWords.filter { it.id in s.foundWords } }
    val cellFoundSet: Set<GridPos> = remember(foundWordList) { foundWordList.flatMap { it.cells }.toSet() }

    // Full-board finale: a soft diagonal light sweep once every word is found, synced
    // with a richer completion haptic. Every solve here is a human's (there is no CPU
    // opponent in this game), so it always celebrates.
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

    val difficultyName = game.difficulty.name.lowercase().replaceFirstChar { it.uppercase() }
    val infoLine = hudInfoLine(onDailyBoard, difficultyName, puzzlesSolved)

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-puzzle
    // discards ONLY the unfinished puzzle: if earlier puzzles in this session were already
    // solved, leaving goes through leaveSession() (the same call the solved panel's Back to Menu
    // makes) so those results still count; only a session with nothing solved is a pure abort
    // (never a win or loss). A solved puzzle always leaves through leaveSession().
    GameChrome(
        helpTitle = "How to Play Word Search",
        helpText = WORD_SEARCH_HELP,
        matchInProgress = !s.solved,
        onLeave = game::leaveSession,
        onAbort = { if (puzzlesSolved > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = WS_PAPER,
        buttonContent = WS_INK,
        leaveTitle = "Leave this puzzle?",
        leaveBody = if (puzzlesSolved > 0) {
            "This puzzle is still in progress and won't count, but the puzzles you've already solved stay on your record."
        } else {
            "This puzzle is still in progress. Leaving now won't count it as a win or a loss."
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().graphPaperBackground()) {
            AdaptiveTwoPane(
                foldState = LocalFoldState.current,
                modifier = Modifier.fillMaxSize(),
                primary = {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        // Read here: inside the Column / Row below this scope's maxHeight is not
                        // implicitly reachable (layout scopes are DSL-marked).
                        val screenHeight = maxHeight
                        // Board on the left, everything else in a column on the right, only when
                        // the window is genuinely landscape; phones in portrait, the Fold cover
                        // screen included, take the stacked layout.
                        val wide = maxWidth >= 480.dp && maxWidth > maxHeight * 1.1f
                        val sideWidth = (maxWidth * 0.34f).coerceIn(220.dp, 320.dp)

                        val hud: @Composable () -> Unit = {
                            WordSearchHud(
                                found = s.foundWords.size,
                                total = s.placedWords.size,
                                solved = s.solved,
                                announcement = announcement,
                                infoLine = infoLine
                            )
                        }
                        val wordList: @Composable (Modifier) -> Unit = { listModifier ->
                            WordSearchWordList(
                                words = s.placedWords,
                                found = s.foundWords,
                                reducedMotion = reducedMotion,
                                modifier = listModifier
                            )
                        }
                        val board: @Composable (Modifier) -> Unit = { slotModifier ->
                            WordSearchBoardSlot(
                                game = game,
                                s = s,
                                foundWords = foundWordList,
                                cellFoundSet = cellFoundSet,
                                finaleSweep = finaleSweep,
                                sheenEnabled = sheenEnabled,
                                colorblind = colorblind,
                                reducedMotion = reducedMotion,
                                haptics = haptics,
                                sounds = sounds,
                                playSfx = playSfx,
                                scope = scope,
                                onAnnounce = { announcement = it },
                                modifier = slotModifier
                            )
                        }
                        val resultEnter = if (reducedMotion) EnterTransition.None else fadeIn(animationSpec = tween(250))
                        val result: @Composable () -> Unit = {
                            AnimatedVisibility(visible = s.solved, enter = resultEnter, exit = ExitTransition.None) {
                                Column {
                                    Spacer(Modifier.height(12.dp))
                                    WordSearchResultPanel(
                                        total = s.placedWords.size,
                                        puzzlesSolved = puzzlesSolved,
                                        onNewPuzzle = {
                                            onDailyBoard = false
                                            game.playAgain()
                                        },
                                        onBackToMenu = { game.leaveSession() },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        if (wide) {
                            Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                                board(Modifier.weight(1f).fillMaxHeight())
                                Spacer(Modifier.width(16.dp))
                                Column(
                                    modifier = Modifier
                                        .width(sideWidth)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    hud()
                                    Spacer(Modifier.height(10.dp))
                                    wordList(Modifier.fillMaxWidth())
                                    result()
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                hud()
                                Spacer(Modifier.height(8.dp))
                                // Capped, and scrolling inside its own cap, so a long word list or a
                                // large font scale can never push the board out of its slot.
                                wordList(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = screenHeight * 0.3f)
                                        .verticalScroll(rememberScrollState())
                                )
                                Spacer(Modifier.height(8.dp))
                                board(Modifier.weight(1f).fillMaxWidth())
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = screenHeight * 0.45f)
                                        .verticalScroll(rememberScrollState())
                                ) { result() }
                            }
                        }
                    }
                }
            )
        }
    }
}

/** "Daily puzzle · Medium · 2 puzzles solved": what the second HUD line says about this board. */
private fun hudInfoLine(onDailyBoard: Boolean, difficultyName: String, puzzlesSolved: Int): String = buildString {
    if (onDailyBoard) append("Daily puzzle · ")
    append(difficultyName)
    if (puzzlesSolved > 0) {
        append(" · ")
        append(if (puzzlesSolved == 1) "1 puzzle solved" else "$puzzlesSolved puzzles solved")
    }
}

/**
 * Shown while the dictionary loads and the first puzzle is generated off the main thread. It is
 * still inside [GameChrome] so Back works: with nothing on the board there is nothing to confirm,
 * and leaving is a plain abort.
 */
@Composable
private fun WordSearchLoading(game: WordSearchGame) {
    GameChrome(
        helpTitle = "How to Play Word Search",
        helpText = WORD_SEARCH_HELP,
        matchInProgress = false,
        onLeave = { game.abortMatch() },
        onAbort = { game.abortMatch() },
        buttonFill = WS_PAPER,
        buttonContent = WS_INK
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphPaperBackground(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Preparing puzzle…",
                color = WS_INK,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/**
 * The one-glance status block: "3 of 8 found" plus a line saying which board this is. Reserves
 * [GameChromeEndInset] at its end so the corner menu button never covers it. The first line is a
 * polite live region: it speaks the last selection's outcome ([announcement]) and the running
 * count whenever either changes, which is how a screen-reader user learns what a drag or a pair
 * of taps did.
 */
@Composable
private fun WordSearchHud(found: Int, total: Int, solved: Boolean, announcement: String, infoLine: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset)) {
        val visible = if (solved) "All $total found" else "$found of $total found"
        val progressSpoken = if (solved) "All $total words found. Puzzle solved." else "$found of $total words found."
        val spoken = if (announcement.isEmpty()) progressSpoken else "$announcement $progressSpoken"
        Text(
            visible,
            color = WS_INK,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            }
        )
        Text(infoLine, color = WS_INK_SOFT, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun WordSearchWordList(words: List<PlacedWord>, found: Set<Int>, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    SimpleFlowRow(modifier = modifier) {
        words.forEach { pw ->
            WordChip(word = pw, isFound = pw.id in found, reducedMotion = reducedMotion)
        }
    }
}

/**
 * The slot the board lives in. Sized by [fitBoard] from THIS slot's own measured space (not an
 * outer scope), so it can never exceed its container. The cell is rounded DOWN to whole pixels
 * so 15 cells never add up to more than the slot, and the Canvas, its hit-testing and the
 * per-cell semantics all share that one integer cell. Below [WS_MIN_CELL_DP] the board keeps that
 * minimum and scrolls both ways; selection then needs a long press first (see [WordSearchGrid]).
 */
@Composable
private fun WordSearchBoardSlot(
    game: WordSearchGame,
    s: WordSearchState,
    foundWords: List<PlacedWord>,
    cellFoundSet: Set<GridPos>,
    finaleSweep: Animatable<Float, AnimationVector1D>,
    sheenEnabled: Boolean,
    colorblind: Boolean,
    reducedMotion: Boolean,
    haptics: (HapticSignal) -> Unit,
    sounds: CardSounds,
    playSfx: (SfxKind) -> Unit,
    scope: CoroutineScope,
    onAnnounce: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val gridSize = s.grid.size
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = gridSize,
            rows = gridSize,
            framePx = WS_FRAME_DP,
            minCellPx = WS_MIN_CELL_DP,
            maxCellPx = WS_MAX_CELL_DP
        )
        val scrolls = !fit.meetsMinimum
        val cellPx: Int = if (scrolls) {
            with(density) { WS_MIN_CELL_DP.dp.roundToPx() }
        } else {
            floor(fit.cellPx * density.density).toInt().coerceAtLeast(1)
        }
        Box(
            modifier = if (scrolls) Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll) else Modifier
        ) {
            WordSearchGrid(
                game = game,
                grid = s.grid,
                foundWords = foundWords,
                cellFoundSet = cellFoundSet,
                selectionStart = s.selectionStart,
                cellPx = cellPx,
                selectOnLongPress = scrolls,
                solved = s.solved,
                finaleSweep = finaleSweep,
                sheenEnabled = sheenEnabled,
                colorblind = colorblind,
                reducedMotion = reducedMotion,
                haptics = haptics,
                sounds = sounds,
                playSfx = playSfx,
                scope = scope,
                onAnnounce = onAnnounce
            )
        }
    }
}

/** What is currently animating on top of the grid after a selection was released. (The live drag
 *  itself is separate state, so a finishing animation can never clobber a drag in progress.) */
private sealed class WordSearchTrace {
    class Locking(
        val start: GridPos,
        val end: GridPos,
        val colorProgress: Animatable<Float, AnimationVector1D>,
        val endProgress: Animatable<Float, AnimationVector1D>
    ) : WordSearchTrace()

    class Missing(
        val start: GridPos,
        val rawEnd: Offset,
        val fade: Animatable<Float, AnimationVector1D>
    ) : WordSearchTrace()
}

/** Edge length of one cell, in px, for a square grid drawn into a [widthPx] x [heightPx] Canvas.
 *  Drawing AND hit-testing both call this with the Canvas's own measured size, so they cannot
 *  disagree about where a cell is. */
private fun wordSearchGridCellPx(widthPx: Float, heightPx: Float, gridSize: Int): Float =
    minOf(widthPx, heightPx) / gridSize

private fun wordSearchCellAt(offset: Offset, cellPx: Float, gridSize: Int): GridPos {
    val col = (offset.x / cellPx).toInt().coerceIn(0, gridSize - 1)
    val row = (offset.y / cellPx).toInt().coerceIn(0, gridSize - 1)
    return GridPos(row, col)
}

private fun wordSearchCellCenter(pos: GridPos, cellPx: Float): Offset =
    Offset(pos.col * cellPx + cellPx / 2f, pos.row * cellPx + cellPx / 2f)

/** "row 3, column 5": the same 1-indexed phrasing ReversiScreen / CheckersScreen use. */
private fun wordSearchSpoken(pos: GridPos): String = "row ${pos.row + 1}, column ${pos.col + 1}"

/**
 * The board: a framed, single-Canvas letter grid with drag-to-select.
 *
 * LAYERS inside the frame, bottom to top: (1) a transparent column of per-cell click targets that
 * exist only for screen readers, switch access and keyboards (description + click action; the
 * click is the tap-first-letter-then-last-letter alternative to dragging); (2) the Canvas, which
 * draws everything and owns the drag recognizer. The Canvas is above the click layer on purpose:
 * Compose delivers a touch to the topmost sibling that has pointer input, so a finger always
 * reaches the Canvas, and TalkBack still finds the cells below it (the Canvas carries no
 * semantics of its own).
 *
 * Draw order in the Canvas: cell fills / pulse / finale sweep, then the traces, then the letters,
 * so a selected letter is never painted over (the old order left it ~1.3:1 against the trace).
 */
@Composable
private fun WordSearchGrid(
    game: WordSearchGame,
    grid: List<List<Char>>,
    foundWords: List<PlacedWord>,
    cellFoundSet: Set<GridPos>,
    selectionStart: GridPos?,
    cellPx: Int,
    selectOnLongPress: Boolean,
    solved: Boolean,
    finaleSweep: Animatable<Float, AnimationVector1D>,
    sheenEnabled: Boolean,
    colorblind: Boolean,
    reducedMotion: Boolean,
    haptics: (HapticSignal) -> Unit,
    sounds: CardSounds,
    playSfx: (SfxKind) -> Unit,
    scope: CoroutineScope,
    onAnnounce: (String) -> Unit
) {
    val density = LocalDensity.current
    val gridSize = grid.size
    val cellPxF = cellPx.toFloat()
    val cellDp = with(density) { cellPx.toDp() }
    val boardDp = with(density) { (cellPx * gridSize).toDp() }

    // The drag recognizer below is keyed on the layout, not on these, so it must read them
    // through State rather than capture the first composition's copy (a stale `onAnnounce` would
    // keep writing to the PREVIOUS puzzle's announcement state).
    val currentHaptics by rememberUpdatedState(haptics)
    val currentPlaySfx by rememberUpdatedState(playSfx)
    val currentAnnounce by rememberUpdatedState(onAnnounce)

    // The live drag: where it started and where the finger is now. Kept apart from [resultTrace].
    var dragStart by remember { mutableStateOf<GridPos?>(null) }
    var dragCurrentPx by remember { mutableStateOf<Offset?>(null) }
    // The lock-in / miss animation left on screen after a release.
    var resultTrace by remember { mutableStateOf<WordSearchTrace?>(null) }
    val pulseAnims = remember { mutableStateMapOf<GridPos, Animatable<Float, AnimationVector1D>>() }
    val shakeOffset = remember { Animatable(0f) }

    val letterStrings = remember(grid) { grid.map { row -> row.map { it.toString() } } }
    val cellDescriptions = remember(grid, cellFoundSet, selectionStart) {
        grid.mapIndexed { r, rowChars ->
            rowChars.mapIndexed { c, ch ->
                val pos = GridPos(r, c)
                val base = when {
                    pos == selectionStart -> "Letter $ch, selection start"
                    pos in cellFoundSet -> "Letter $ch, part of a found word"
                    else -> "Letter $ch"
                }
                "$base, ${wordSearchSpoken(pos)}"
            }
        }
    }

    val letterPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            color = WS_INK.toArgb()
        }
    }
    // Found letters are bold as well as dark green: a second, non-colour cue and more contrast
    // on the green fill than the old regular-weight 3.9:1.
    val foundLetterPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            color = WS_FOUND_INK.toArgb()
        }
    }

    suspend fun runMiss(start: GridPos, rawEnd: Offset) {
        val fade = Animatable(1f)
        val miss = WordSearchTrace.Missing(start, rawEnd, fade)
        resultTrace = miss
        currentHaptics(HapticSignal.FAILURE)
        currentPlaySfx(SfxKind.INVALID_BUZZ)
        if (!reducedMotion) {
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(-cellPxF * 0.18f, tween(40))
            shakeOffset.animateTo(cellPxF * 0.18f, tween(60))
            shakeOffset.animateTo(-cellPxF * 0.1f, tween(60))
            shakeOffset.animateTo(0f, tween(50))
        }
        fade.animateTo(0f, tween(if (reducedMotion) 60 else 260))
        // Only clear what is still ours: a drag that ended in the meantime may have put its own
        // trace here, and clearing that one is exactly the bug this guard replaces.
        if (resultTrace === miss) resultTrace = null
    }

    suspend fun runLockIn(result: SelectionResult) {
        val start = result.orderedCells.first()
        val end = result.orderedCells.last()
        val colorProgress = Animatable(0f)
        val endProgress = Animatable(0f)
        val lock = WordSearchTrace.Locking(start, end, colorProgress, endProgress)
        resultTrace = lock
        val justSolved = game.state.value?.solved == true
        if (!justSolved) {
            currentHaptics(HapticSignal.SUCCESS)
            sounds.playPlace()
        }
        if (reducedMotion) {
            colorProgress.snapTo(1f)
            endProgress.snapTo(1f)
        } else {
            scope.launch { colorProgress.animateTo(1f, tween(150)) }
            endProgress.animateTo(1f, tween(150))
        }
        if (resultTrace === lock) resultTrace = null

        if (!reducedMotion) {
            result.orderedCells.forEachIndexed { i, pos ->
                scope.launch {
                    delay(i * 60L)
                    val anim = Animatable(0f)
                    pulseAnims[pos] = anim
                    anim.animateTo(1f, tween(140))
                    anim.animateTo(0f, tween(160))
                    if (pulseAnims[pos] === anim) pulseAnims.remove(pos)
                }
            }
        }
    }

    /** Both input paths (drag release and the screen-reader tap pair) end here. */
    fun commitSelection(start: GridPos, end: GridPos, rawEnd: Offset) {
        val before = game.state.value
        if (before == null || before.solved || game.matchOver.value) return
        val result = game.attemptSelection(start, end)
        if (result != null) {
            scope.launch { runLockIn(result) }
            currentAnnounce("Found ${result.word.word}.")
        } else if (start != end) {
            // start == end is a drag that came back to where it began: a cancel, not a miss.
            scope.launch { runMiss(start, rawEnd) }
            currentAnnounce("No word from ${wordSearchSpoken(start)} to ${wordSearchSpoken(end)}.")
        }
    }

    /** Click action of a cell for screen readers, switch access and keyboards: first letter, then last. */
    fun onCellActivated(pos: GridPos) {
        val live = game.state.value ?: return
        if (live.solved || game.matchOver.value) return
        val pending = live.selectionStart
        when {
            pending == null -> {
                game.setSelectionStart(pos)
                currentHaptics(HapticSignal.LIGHT_TICK)
                currentAnnounce("First letter set at ${wordSearchSpoken(pos)}. Double-tap the last letter of the word.")
            }
            pending == pos -> {
                game.setSelectionStart(null)
                currentAnnounce("Selection cleared.")
            }
            else -> commitSelection(pending, pos, wordSearchCellCenter(pos, cellPxF))
        }
    }

    val clickLabel = if (selectionStart == null) "Mark as first letter of a word" else "Mark as last letter of a word"
    val gridDescription =
        "Word search grid, $gridSize by $gridSize. Drag from a word's first letter to its last, " +
            "or double-tap the first letter and then the last."

    Box(
        modifier = Modifier
            .specularSweep(enabled = sheenEnabled, tint = Color.White.copy(alpha = 0.08f))
            .graphicsLayer { translationX = shakeOffset.value }
            .background(WS_BACKING, RoundedCornerShape(6.dp))
            .border(1.dp, WS_INK.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(WS_FRAME_DP.dp)
            .semantics { contentDescription = gridDescription }
    ) {
        Box(modifier = Modifier.size(boardDp)) {
            // Layer 1: screen-reader / switch / keyboard targets. They sit UNDER the Canvas, so a
            // finger never reaches them (the Canvas below is hit first); their click actions are
            // invoked directly by the accessibility service or a keyboard.
            Column {
                for (row in 0 until gridSize) {
                    Row {
                        for (col in 0 until gridSize) {
                            val pos = GridPos(row, col)
                            val description = cellDescriptions[row][col]
                            Box(
                                modifier = Modifier
                                    .size(cellDp)
                                    .clickable(
                                        enabled = !solved,
                                        onClickLabel = if (pos == selectionStart) "Clear selection" else clickLabel,
                                        role = Role.Button
                                    ) { onCellActivated(pos) }
                                    .semantics { contentDescription = description }
                            )
                        }
                    }
                }
            }

            // Layer 2: the drawing and the drag. No pointerHoverIcon: this is one continuous
            // drag-select surface with no per-cell composable to hang a hand cursor on (same
            // reasoning as AirHockeyScreen's table Canvas).
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(gridSize, cellPx, selectOnLongPress, reducedMotion) {
                        fun boardCellPx(): Float =
                            wordSearchGridCellPx(size.width.toFloat(), size.height.toFloat(), gridSize)

                        fun startDrag(offset: Offset) {
                            if (game.matchOver.value || game.state.value?.solved == true) return
                            val cell = wordSearchCellAt(offset, boardCellPx(), gridSize)
                            dragStart = cell
                            dragCurrentPx = offset
                            game.setSelectionStart(cell)
                            currentHaptics(HapticSignal.LIGHT_TICK)
                            currentPlaySfx(SfxKind.LIGHT_TICK)
                        }

                        fun moveDrag(position: Offset) {
                            if (dragStart != null) dragCurrentPx = position
                        }

                        fun endDrag() {
                            val start = dragStart
                            val endPx = dragCurrentPx
                            dragStart = null
                            dragCurrentPx = null
                            if (start == null || endPx == null) return
                            commitSelection(start, wordSearchCellAt(endPx, boardCellPx(), gridSize), endPx)
                        }

                        fun cancelDrag() {
                            if (dragStart == null) return
                            dragStart = null
                            dragCurrentPx = null
                            game.setSelectionStart(null)
                        }

                        if (selectOnLongPress) {
                            // The board scrolls: a plain drag must pan it, so selecting needs a
                            // touch-and-hold first. Once the long press fires the drag is consumed.
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset -> startDrag(offset) },
                                onDragEnd = { endDrag() },
                                onDragCancel = { cancelDrag() },
                                onDrag = { change, _ ->
                                    change.consume()
                                    moveDrag(change.position)
                                }
                            )
                        } else {
                            detectDragGestures(
                                onDragStart = { offset -> startDrag(offset) },
                                onDragEnd = { endDrag() },
                                onDragCancel = { cancelDrag() },
                                onDrag = { change, _ ->
                                    change.consume()
                                    moveDrag(change.position)
                                }
                            )
                        }
                    }
            ) {
                val cell = wordSearchGridCellPx(size.width, size.height, gridSize)
                val gap = 0.5.dp.toPx()
                val cellExtent = Size(cell - 2f * gap, cell - 2f * gap)
                val sweep = finaleSweep.value

                // Pass 1: fills. Found cells, the pending start cell, per-cell pulse, finale sweep.
                for (row in 0 until gridSize) {
                    for (col in 0 until gridSize) {
                        val pos = GridPos(row, col)
                        val topLeft = Offset(col * cell + gap, row * cell + gap)
                        if (pos in cellFoundSet) {
                            drawRect(color = WS_FOUND_FILL, topLeft = topLeft, size = cellExtent)
                        }
                        if (pos == selectionStart) {
                            drawRect(color = WS_TENTATIVE.copy(alpha = 0.7f), topLeft = topLeft, size = cellExtent)
                        }
                        val pulse = pulseAnims[pos]?.value ?: 0f
                        if (pulse > 0f) {
                            drawRect(color = Color.White.copy(alpha = pulse * 0.6f), topLeft = topLeft, size = cellExtent)
                        }
                        if (sweep > 0f) {
                            val diag = (row + col).toFloat() / (2f * (gridSize - 1).coerceAtLeast(1))
                            val band = (1f - abs(diag - sweep) / 0.18f).coerceIn(0f, 1f)
                            if (band > 0f) {
                                drawRect(color = Color.White.copy(alpha = band * 0.4f), topLeft = topLeft, size = cellExtent)
                            }
                        }
                    }
                }

                // Colorblind mode: a dark outlined capsule around every found word, so "found" is
                // carried by a shape and not by green alone.
                if (colorblind) {
                    val ringWidth = cell * 0.78f
                    val ringEdge = 2.dp.toPx()
                    for (word in foundWords) {
                        val from = wordSearchCellCenter(word.cells.first(), cell)
                        val to = wordSearchCellCenter(word.cells.last(), cell)
                        drawLine(color = WS_INK, start = from, end = to, strokeWidth = ringWidth, cap = StrokeCap.Round)
                        drawLine(
                            color = WS_FOUND_FILL,
                            start = from,
                            end = to,
                            strokeWidth = (ringWidth - 2f * ringEdge).coerceAtLeast(1f),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Pass 2: traces, under the letters. Live drag: a raw line to the finger.
                val dragFrom = dragStart
                val dragTo = dragCurrentPx
                if (dragFrom != null && dragTo != null) {
                    drawLine(
                        color = WS_TENTATIVE.copy(alpha = 0.85f),
                        start = wordSearchCellCenter(dragFrom, cell),
                        end = dragTo,
                        strokeWidth = cell * 0.35f,
                        cap = StrokeCap.Round
                    )
                }
                when (val trace = resultTrace) {
                    // Lock-in: solidifying from tentative-yellow to found-green while finishing the
                    // trace to the exact end-cell center.
                    is WordSearchTrace.Locking -> {
                        val from = wordSearchCellCenter(trace.start, cell)
                        val to = androidx.compose.ui.geometry.lerp(
                            from, wordSearchCellCenter(trace.end, cell), trace.endProgress.value
                        )
                        drawLine(
                            color = lerp(WS_TENTATIVE, WS_FOUND_FILL, trace.colorProgress.value),
                            start = from,
                            end = to,
                            strokeWidth = cell * 0.35f,
                            cap = StrokeCap.Round
                        )
                    }
                    // Miss: the tentative line fades out instead of vanishing instantly.
                    is WordSearchTrace.Missing -> {
                        drawLine(
                            color = WS_MISS.copy(alpha = 0.85f * trace.fade.value),
                            start = wordSearchCellCenter(trace.start, cell),
                            end = trace.rawEnd,
                            strokeWidth = cell * 0.35f,
                            cap = StrokeCap.Round
                        )
                    }
                    null -> Unit
                }

                // Pass 3: the letters, on top of everything above.
                letterPaint.textSize = cell * 0.56f
                foundLetterPaint.textSize = cell * 0.56f
                // Optical centering from the font's own metrics instead of a magic fraction.
                val metrics = letterPaint.fontMetrics
                val baselineShift = -(metrics.ascent + metrics.descent) / 2f
                val androidCanvas = drawContext.canvas.nativeCanvas
                for (row in 0 until gridSize) {
                    for (col in 0 until gridSize) {
                        val pos = GridPos(row, col)
                        val center = wordSearchCellCenter(pos, cell)
                        androidCanvas.drawText(
                            letterStrings[row][col],
                            center.x,
                            center.y + baselineShift,
                            if (pos in cellFoundSet) foundLetterPaint else letterPaint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WordChip(word: PlacedWord, isFound: Boolean, reducedMotion: Boolean) {
    val strikeProgress by animateFloatAsState(
        targetValue = if (isFound) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(320),
        label = "wordSearchStrike"
    )
    // Dark green, not the old 7CB342 (2.5:1 on the chip): the strike-through stays the primary
    // "found" cue, the text colour just has to remain readable.
    val textColor by animateColorAsState(
        targetValue = if (isFound) WS_FOUND_TEXT else WS_CHIP_INK,
        animationSpec = if (reducedMotion) snap() else tween(320),
        label = "wordSearchWordColor"
    )
    Box(
        modifier = Modifier
            // One node per chip: the word, then its found / not-found state.
            .semantics(mergeDescendants = true) {
                stateDescription = if (isFound) "Found" else "Not found yet"
            }
            .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .drawWithContent {
                drawContent()
                if (strikeProgress > 0f) {
                    val y = size.height / 2f
                    drawLine(
                        color = WS_STRIKE,
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
 * The solved-puzzle panel: the same two exits as before (New Puzzle keeps the tally, Back to Menu
 * ends the session through `leaveSession`), now a real panel with 48dp buttons.
 */
@Composable
private fun WordSearchResultPanel(
    total: Int,
    puzzlesSolved: Int,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.8f), contentColor = WS_INK)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Puzzle solved!",
                color = WS_SOLVED_GREEN,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "All $total words found",
                color = WS_INK,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Text(
                if (puzzlesSolved == 1) "1 puzzle solved this session" else "$puzzlesSolved puzzles solved this session",
                color = WS_INK_SOFT,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onNewPuzzle,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                    colors = ButtonDefaults.buttonColors(containerColor = WS_SOLVED_GREEN, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("New Puzzle", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WS_INK),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Back to Menu", textAlign = TextAlign.Center) }
            }
        }
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
    val spacing = 26f
    onDrawBehind {
        drawRect(WS_PAPER)
        var x = 0f
        while (x <= size.width) {
            drawLine(WS_RULE, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += spacing
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(WS_RULE, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += spacing
        }
    }
}
