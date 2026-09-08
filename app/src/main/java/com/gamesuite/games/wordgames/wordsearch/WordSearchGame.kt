package com.gamesuite.games.wordgames.wordsearch

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.wordgames.WordDictionary
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

data class GridPos(val row: Int, val col: Int)

data class PlacedWord(
    val id: Int,
    val word: String,
    val cells: List<GridPos>
)

/** Result of a successful [WordSearchGame.attemptSelection] — see that function's KDoc for why [orderedCells] can differ from [word]'s own [PlacedWord.cells] order. */
data class SelectionResult(val word: PlacedWord, val orderedCells: List<GridPos>)

data class WordSearchState(
    val grid: List<List<Char>>,
    val placedWords: List<PlacedWord>,
    /** Ids of claimed PlacedWord entries — keyed by id rather than word text, since the
     *  same dictionary word can legitimately be placed at two distinct locations. */
    val foundWords: Set<Int>,
    /** First tap of the current selection, or null if none pending. */
    val selectionStart: GridPos? = null,
    /** This puzzle is solved (every word found) — distinct from [WordSearchGame.matchOver], which only becomes true once the whole session ends via leaveSession(). */
    val solved: Boolean = false
) {
    val allFound: Boolean get() = foundWords.size == placedWords.size
}

/**
 * Word search: pick N random dictionary words that fit an NxN grid, place
 * each along a random direction (8-way, including diagonals and reversed),
 * fill remaining cells with noise letters. Tap a start cell then an end
 * cell in a straight line to claim a word.
 *
 * Research pass (README item 9k) added: a real difficulty ladder — like
 * Hangman/Crossword/Sliding Puzzle, this is a solo puzzle with no opponent,
 * so the honest lever is puzzle generation, not a bot. EASY is a smaller
 * grid, fewer/shorter words, and *no reversed or diagonal placements* —
 * every word reads left-to-right or top-to-bottom, which is the single
 * biggest felt difference in a word search (scanning is far easier when you
 * never have to read backwards or on a diagonal). MEDIUM reproduces the
 * original single-tier generator byte-for-byte (12x12, 8 words, lengths
 * 4..9, all 8 directions). HARD is a larger grid with more, longer words,
 * still using every direction. Selected from Settings' "Default CPU
 * difficulty" the same way the other passes in this series are.
 *
 * Also added a session tally ([puzzlesSolved]) and New Puzzle/Back to Menu
 * flow, matching the Hangman pattern: solving a puzzle no longer calls
 * [endMatch] directly (it used to, ending the whole visit to this screen
 * the instant the last word was found) — only [leaveSession] does that now.
 */
class WordSearchGame : GameModule {
    override val gameId = "word-search"
    override val displayName = "Word Search"
    override val category = GameCategory.WORD
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<WordSearchState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-puzzle. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * Quick-win structural hook: grid size was previously locked 1:1 to [difficulty] via
     * [tierParams] (EASY=8, MEDIUM=12, HARD=15), with no way to ask for a different board size
     * independent of the difficulty tier's word-count/length/direction knobs. Set this before
     * startMatch() to pin the grid dimension directly; leave null (the default) for the original
     * behavior where grid size is fully determined by difficulty. No UI control reads/writes this
     * yet — this is the parameter a future explicit "grid size" picker would set.
     */
    var gridSizeOverride: Int? = null

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** All 8 directions — unchanged from the original single-tier generator; MEDIUM and HARD both use this. */
    private val allDirections = listOf(
        0 to 1, 0 to -1, 1 to 0, -1 to 0,
        1 to 1, 1 to -1, -1 to 1, -1 to -1
    )

    /** Forward-only (left-to-right, top-to-bottom): no reversed or diagonal placements — EASY's actual difficulty lever. */
    private val forwardOnlyDirections = listOf(0 to 1, 1 to 0)

    private data class TierParams(val gridSize: Int, val wordCount: Int, val maxLength: Int, val directions: List<Pair<Int, Int>>)

    /**
     * MEDIUM reproduces the original generator's numbers exactly (12x12, 8 words, lengths 4..9,
     * all 8 directions). [gridSizeOverride], when set, replaces just the [TierParams.gridSize]
     * this returns — word count/length/direction stay difficulty-driven either way; only the
     * board dimension becomes independently selectable.
     */
    private fun tierParams(): TierParams {
        val base = when (difficulty) {
            CpuDifficulty.EASY -> TierParams(gridSize = 8, wordCount = 6, maxLength = 6, directions = forwardOnlyDirections)
            CpuDifficulty.MEDIUM -> TierParams(gridSize = 12, wordCount = 8, maxLength = 9, directions = allDirections)
            CpuDifficulty.HARD -> TierParams(gridSize = 15, wordCount = 10, maxLength = 12, directions = allDirections)
        }
        return gridSizeOverride?.let { base.copy(gridSize = it) } ?: base
    }

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /** Call once, from the UI, before startMatch() — loads the shared dictionary asset. */
    fun loadDictionary(androidContext: Context) {
        WordDictionary.ensureLoaded(androidContext)
    }

    override fun startMatch() {
        startMatch(seed = null)
    }

    /**
     * Actual puzzle-generation entry point — [startMatch] (the no-arg [GameModule] override) is
     * just this with `seed = null`. Exposed separately so a future "Daily Puzzle" mode can read
     * today's date at its own call site (this class has no business knowing about calendars) and
     * derive a stable seed from it: the same [seed] plus the same [difficulty] tier always
     * reproduces the same grid, since [generatePuzzle] threads that seed through every random
     * decision it makes about *where* words go. [playAgain] deliberately keeps calling the no-arg
     * [startMatch], so "New Puzzle" during a daily challenge still hands back a fresh, unseeded
     * board rather than looping the same one.
     *
     * Note for whoever wires up daily puzzles: word *choice* isn't seeded yet.
     * [WordDictionary.randomWordsOfLength] shuffles its candidate pool with the global unseeded
     * Random internally, so two runs of the same seed currently produce identical placement
     * geometry and filler letters but can still surface different actual words. Making that
     * deterministic too means threading a Random through WordDictionary, which is out of this
     * change's scope.
     */
    fun startMatch(seed: Long?) {
        state.value = generatePuzzle(tierParams(), seed)
    }

    private fun generatePuzzle(params: TierParams, seed: Long? = null): WordSearchState {
        val rng: Random = if (seed != null) Random(seed) else Random.Default
        val gridSize = params.gridSize
        val grid = Array(gridSize) { CharArray(gridSize) { ' ' } }
        val placed = mutableListOf<PlacedWord>()

        val candidateLengths = (4..minOf(params.maxLength, gridSize)).shuffled(rng)
        var attempts = 0
        while (placed.size < params.wordCount && attempts < 500) {
            attempts++
            val length = candidateLengths[attempts % candidateLengths.size]
            // WordDictionary's pool is lowercase (see WordDictionary.kt); placed words are
            // stored uppercase below, so the exclusion set must be lowercased too, or this
            // filter is a silent no-op and the same word can be placed twice.
            val word = WordDictionary.randomWordsOfLength(
                length, 1,
                excluding = placed.map { it.word.lowercase() }.toSet()
            ).firstOrNull() ?: continue

            val (dr, dc) = params.directions.random(rng)
            val startRow = rng.nextInt(gridSize)
            val startCol = rng.nextInt(gridSize)
            val cells = (0 until word.length).map { i -> GridPos(startRow + dr * i, startCol + dc * i) }

            if (cells.any { it.row !in 0 until gridSize || it.col !in 0 until gridSize }) continue
            if (cells.withIndex().any { (i, pos) ->
                    val existing = grid[pos.row][pos.col]
                    existing != ' ' && existing != word[i].uppercaseChar()
                }) continue

            cells.forEachIndexed { i, pos -> grid[pos.row][pos.col] = word[i].uppercaseChar() }
            placed.add(PlacedWord(id = placed.size, word = word.uppercase(), cells = cells))
        }

        for (r in 0 until gridSize) for (c in 0 until gridSize) {
            if (grid[r][c] == ' ') grid[r][c] = ('A'..'Z').random(rng)
        }

        return WordSearchState(
            grid = grid.map { it.toList() },
            placedWords = placed,
            foundWords = emptySet()
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Marks [pos] as the pending drag-selection anchor (or clears it when null) — the UI calls
     * this on drag-start so [WordSearchState.selectionStart] still drives the start-cell
     * highlight while the live trace itself is tracked as raw pixels on the UI side (this class
     * has no notion of pixels/canvas geometry). Superseded the old two-discrete-taps flow (see
     * [attemptSelection]'s KDoc) as part of the drag-to-select pass.
     */
    fun setSelectionStart(pos: GridPos?) {
        val s = state.value ?: return
        if (s.solved) return
        state.value = s.copy(selectionStart = pos)
    }

    /**
     * The drag-based replacement for the old two-tap [GridPos]-then-[GridPos] flow: called once,
     * on drag-release, with the drag's start and (nearest-cell-snapped) end position. Returns the
     * matched [PlacedWord] plus the straight line of cells from [start] to [end] IN THAT ORDER
     * (i.e. the actual direction the player dragged, which may be the reverse of how the word is
     * stored in [PlacedWord.cells]) — the UI uses that ordering to stagger the found-cell pulse
     * along the direction the player actually traced, not the word's canonical storage order.
     * Returns null on a miss (no straight line, or no unclaimed word matches it), leaving
     * [WordSearchState.foundWords] untouched so the UI can play a distinct miss animation instead
     * of a silent reset.
     */
    fun attemptSelection(start: GridPos, end: GridPos): SelectionResult? {
        val s = state.value ?: return null
        if (s.solved) {
            return null
        }
        if (start == end) {
            state.value = s.copy(selectionStart = null)
            return null
        }

        val lineCells = cellsBetween(start, end)
        if (lineCells == null) {
            state.value = s.copy(selectionStart = null)
            return null
        }

        val match = s.placedWords.firstOrNull { placed ->
            placed.id !in s.foundWords &&
                (placed.cells == lineCells || placed.cells == lineCells.reversed())
        }

        if (match == null) {
            state.value = s.copy(selectionStart = null)
            return null
        }

        val newFound = s.foundWords + match.id
        val allDone = newFound.size == s.placedWords.size
        state.value = s.copy(foundWords = newFound, selectionStart = null, solved = allDone)
        if (allDone) puzzlesSolved.value += 1
        return SelectionResult(match, lineCells)
    }

    /** Called from the solved panel's "New Puzzle" button — keeps the running tally, generates a fresh board. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /**
     * Called from the solved panel's (or in-progress screen's) "Back to Menu" button — ends
     * the whole session. Word Search has no turn/attribution tracking (any player can tap
     * any word in pass-and-play), so every player in the match gets the same tally-based
     * score, same shared-board convention the original single-puzzle endMatch() call used.
     */
    fun leaveSession() {
        if (matchOver.value) return
        val scores = context.players.map { p ->
            PlayerScore(playerId = p.playerId, score = puzzlesSolved.value, isWinner = puzzlesSolved.value > 0)
        }
        endMatch(GameResult(scores = scores))
    }

    private fun cellsBetween(a: GridPos, b: GridPos): List<GridPos>? {
        val dr = (b.row - a.row).let { if (it == 0) 0 else it / kotlin.math.abs(it) }
        val dc = (b.col - a.col).let { if (it == 0) 0 else it / kotlin.math.abs(it) }
        if (dr == 0 && dc == 0) return null
        val rowSteps = kotlin.math.abs(b.row - a.row)
        val colSteps = kotlin.math.abs(b.col - a.col)
        if (dr != 0 && dc != 0 && rowSteps != colSteps) return null // not a straight diagonal
        val steps = maxOf(rowSteps, colSteps)
        return (0..steps).map { i -> GridPos(a.row + dr * i, a.col + dc * i) }
    }
}
