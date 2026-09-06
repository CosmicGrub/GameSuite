package com.gamesuite.games.wordgames.crossword

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty

enum class Direction { ACROSS, DOWN }

data class CrosswordPos(val row: Int, val col: Int)

data class CrosswordEntry(
    val id: String,
    val word: String,
    val clue: String,
    val direction: Direction,
    val start: CrosswordPos,
    val number: Int
) {
    val cells: List<CrosswordPos>
        get() = (0 until word.length).map { i ->
            if (direction == Direction.ACROSS) CrosswordPos(start.row, start.col + i)
            else CrosswordPos(start.row + i, start.col)
        }
}

data class CrosswordCell(
    val letter: Char?,          // correct answer letter, null = black/unused cell
    val number: Int? = null,    // clue number if this cell starts an entry
    val revealed: Boolean = false
)

data class CrosswordState(
    val grid: List<List<CrosswordCell>>,
    val entries: List<CrosswordEntry>,
    val solvedEntryIds: Set<String>,
    val selectedEntryId: String? = null,
    /** True once every entry in THIS puzzle is solved — not the whole session, see CrosswordGame.matchOver. */
    val matchOver: Boolean = false,
    /**
     * Per-entry hints revealed so far this puzzle, keyed by entry id — the
     * hint economy (see [CrosswordGame.revealNextLetter]) caps how many
     * letters of a given entry can be revealed for free, so the UI needs to
     * know how many of THIS entry's letters were hint-revealed rather than
     * guessed, independent of [solvedEntryIds].
     */
    val hintsUsedByEntry: Map<String, Int> = emptyMap()
)

/**
 * Crossword built from a small hand-authored clue bank (see CrosswordClueBank
 * — real clues can't be auto-generated from the bare dictionary). Words are
 * placed greedily: longest first at center, each subsequent word placed at
 * the first valid intersection found with an already-placed word. Input is
 * per-clue (tap a clue, type the whole answer) rather than per-cell — a
 * deliberate simplification for touch input; see games/wordgames/crossword.
 *
 * Upgrade pass (README item 9l): mirrors Hangman's session shape (see
 * HangmanGame's KDoc) since both are solo word puzzles with no opponent to
 * make smarter/dumber — [difficulty] instead picks which CrosswordClueBank
 * theme the puzzle is drawn from, and solving one puzzle no longer ends the
 * whole match: it surfaces a "New Puzzle"/"Back to Menu" choice ([playAgain]
 * / [leaveSession]) so a session can cover several puzzles, tallied in
 * [puzzlesSolved]. [generate] is a single bounded pass over a small pool
 * (<= ~30 candidates) with no retry loop, so it stays cheap enough to run
 * directly on the UI thread from startMatch(), same as before this pass.
 *
 * Variety pass: [generate] used to always seed the puzzle with the single
 * longest word in the theme's pool, so any theme with one clear longest word
 * (true of MEDIUM and HARD) produced the identical anchor word every time —
 * see [pickSeed], which now randomizes among the top few longest candidates.
 * That same seed choice also feeds [recentSeedWords], a small in-memory
 * rolling history (no persistence — it's session-only, same spirit as
 * [puzzlesSolved]) so back-to-back "New Puzzle" taps don't keep re-anchoring
 * on the same word.
 */
class CrosswordGame(private val gridSize: Int = 15) : GameModule {
    override val gameId = "crossword"
    override val displayName = "Crossword"
    override val category = GameCategory.WORD
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<CrosswordState?>(null)

    /** Running tally of fully-solved puzzles across the session (survives "New Puzzle"). */
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-puzzle. */
    val matchOver = mutableStateOf(false)

    /**
     * Hint uses left for the CURRENT puzzle — resets to [MAX_HINTS_PER_PUZZLE]
     * on every [startMatch] (including "New Puzzle"). See [revealNextLetter].
     */
    val hintsRemaining = mutableStateOf(MAX_HINTS_PER_PUZZLE)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /**
     * Last few seed words used (most recent last), across "New Puzzle" taps
     * this session — excluded from [pickSeed]'s candidate pool so the anchor
     * word doesn't keep repeating. In-memory only, cleared on [init] like
     * [puzzlesSolved]; see [SEED_HISTORY_SIZE].
     */
    private val recentSeedWords = ArrayDeque<String>()

    private val clueBankByDifficulty: Map<CpuDifficulty, List<CrosswordClueBank.Entry>> = mapOf(
        CpuDifficulty.EASY to CrosswordClueBank.easyEverydayTheme,
        CpuDifficulty.MEDIUM to CrosswordClueBank.gamesAndTechTheme,
        CpuDifficulty.HARD to CrosswordClueBank.generalKnowledgeTheme
    )

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
        recentSeedWords.clear()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val pool = (clueBankByDifficulty[difficulty] ?: CrosswordClueBank.gamesAndTechTheme).shuffled()
        val (grid, entries) = generate(pool)
        hintsRemaining.value = MAX_HINTS_PER_PUZZLE
        state.value = CrosswordState(
            grid = grid,
            entries = entries,
            solvedEntryIds = emptySet()
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun selectEntry(entryId: String) {
        val s = state.value ?: return
        state.value = s.copy(selectedEntryId = entryId)
    }

    fun clearSelection() {
        val s = state.value ?: return
        state.value = s.copy(selectedEntryId = null)
    }

    /**
     * Attempts to fill in [entryId] with [answer]. Returns true when the guess
     * matched and the entry is now revealed/solved, false when the guess was
     * wrong (state is left untouched, dialog stays open) or the entry/state
     * couldn't be found at all — callers use the return value to surface an
     * incorrect-guess indication in the UI instead of failing silently.
     */
    fun submitAnswer(entryId: String, answer: String): Boolean {
        val s = state.value ?: return false
        val entry = s.entries.firstOrNull { it.id == entryId } ?: return false
        if (!answer.equals(entry.word, ignoreCase = true)) return false

        val newGrid = s.grid.map { it.toMutableList() }
        entry.cells.forEachIndexed { i, pos ->
            val cell = newGrid[pos.row][pos.col]
            newGrid[pos.row][pos.col] = cell.copy(revealed = true)
        }

        val solved = s.solvedEntryIds + entryId
        val allSolved = solved.size == s.entries.size

        state.value = s.copy(
            grid = newGrid,
            solvedEntryIds = solved,
            selectedEntryId = null,
            matchOver = allSolved
        )

        // Solving a puzzle only tallies it and surfaces the New Puzzle/Back to
        // Menu choice — it does NOT end the match. Only leaveSession() does
        // that, same split as Hangman's per-round win/loss vs. session end.
        if (allSolved) {
            puzzlesSolved.value += 1
        }

        return true
    }

    /**
     * Hint economy: revealing every unsolved entry's first letter for free
     * made the hint trivially spammable (one tap disclosed the whole board's
     * starting letters at no cost). Replaced with a per-entry hint that
     * reveals one more letter of the currently-*selected* entry — reusing
     * [CrosswordState.selectedEntryId], the same selection AnswerDialog is
     * built from, rather than adding new selection UI — and costs a hint use
     * against [MAX_HINTS_PER_PUZZLE], visible in the UI as a shrinking
     * "Hint (n left)" affordance. A no-op with no selected entry, an already
     * fully-revealed entry, an already-solved entry, a completed puzzle, or
     * an exhausted hint budget.
     */
    fun revealNextLetter() {
        val s = state.value ?: return
        if (s.matchOver) return
        if (hintsRemaining.value <= 0) return
        val entryId = s.selectedEntryId ?: return
        if (entryId in s.solvedEntryIds) return
        val entry = s.entries.firstOrNull { it.id == entryId } ?: return

        val newGrid = s.grid.map { it.toMutableList() }
        val nextIndex = entry.cells.indexOfFirst { pos -> !newGrid[pos.row][pos.col].revealed }
        if (nextIndex < 0) return // entry already fully revealed
        val pos = entry.cells[nextIndex]
        newGrid[pos.row][pos.col] = newGrid[pos.row][pos.col].copy(revealed = true)

        hintsRemaining.value -= 1
        state.value = s.copy(
            grid = newGrid,
            hintsUsedByEntry = s.hintsUsedByEntry + (entryId to ((s.hintsUsedByEntry[entryId] ?: 0) + 1))
        )
    }

    /** Called from the puzzle-complete panel's "New Puzzle" button — keeps the running tally. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the puzzle-complete panel's "Back to Menu" button — ends the whole session. */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = listOfNotNull(player?.let {
                PlayerScore(playerId = it.playerId, score = puzzlesSolved.value, isWinner = puzzlesSolved.value > 0)
            })
        )
        endMatch(result)
    }

    // ---- Generation ----

    private fun generate(pool: List<CrosswordClueBank.Entry>): Pair<List<List<CrosswordCell>>, List<CrosswordEntry>> {
        val letterGrid = Array(gridSize) { arrayOfNulls<Char>(gridSize) }
        val placedEntries = mutableListOf<CrosswordEntry>()
        val sorted = pool.sortedByDescending { it.word.length }
        var nextId = 0

        // Seed word, centered horizontally through the middle row. See
        // pickSeed() for why this is no longer always sorted.first().
        val seed = pickSeed(sorted) ?: return emptyGrid() to emptyList()
        val seedRow = gridSize / 2
        val seedCol = (gridSize - seed.word.length) / 2
        if (seedCol < 0) return emptyGrid() to emptyList()

        placeWord(letterGrid, seed.word, seedRow, seedCol, Direction.ACROSS)
        placedEntries.add(
            CrosswordEntry("e${nextId++}", seed.word, seed.clue, Direction.ACROSS, CrosswordPos(seedRow, seedCol), 0)
        )

        for (candidate in sorted) {
            if (candidate === seed) continue
            val placement = findIntersection(letterGrid, candidate.word) ?: continue
            placeWord(letterGrid, candidate.word, placement.start.row, placement.start.col, placement.direction)
            placedEntries.add(
                CrosswordEntry("e${nextId++}", candidate.word, candidate.clue, placement.direction, placement.start, 0)
            )
        }

        val numbered = assignNumbers(placedEntries)
        val cellGrid = buildCellGrid(letterGrid, numbered)
        return cellGrid to numbered
    }

    /**
     * Picks the anchor word [generate] seeds the grid with. Always taking the
     * single longest word in the pool made the anchor identical on every
     * "New Puzzle" tap for any theme with one clear longest word (true of the
     * MEDIUM and HARD clue banks) — instead this picks randomly among the
     * longest word and any others within [SEED_LENGTH_TOLERANCE] letters of
     * it, so the puzzle still opens on a long, well-connecting word but
     * varies which one. That candidate pool is further filtered against
     * [recentSeedWords] (the last [SEED_HISTORY_SIZE] anchors used this
     * session) to avoid immediately repeating — falling back to allowing a
     * repeat only when every top candidate is already in that history (e.g.
     * a theme whose top tier is smaller than the history window).
     */
    private fun pickSeed(sorted: List<CrosswordClueBank.Entry>): CrosswordClueBank.Entry? {
        val longest = sorted.firstOrNull() ?: return null
        val topCandidates = sorted.takeWhile { longest.word.length - it.word.length <= SEED_LENGTH_TOLERANCE }
        val unseen = topCandidates.filterNot { it.word in recentSeedWords }
        val chosen = unseen.ifEmpty { topCandidates }.random()

        recentSeedWords.addLast(chosen.word)
        while (recentSeedWords.size > SEED_HISTORY_SIZE) recentSeedWords.removeFirst()
        return chosen
    }

    private data class Placement(val start: CrosswordPos, val direction: Direction)

    private fun findIntersection(grid: Array<Array<Char?>>, word: String): Placement? {
        for (row in 0 until gridSize) for (col in 0 until gridSize) {
            val existing = grid[row][col] ?: continue
            for (i in word.indices) {
                if (word[i] != existing) continue

                // Try placing DOWN through this intersection.
                val downStart = CrosswordPos(row - i, col)
                if (canPlace(grid, word, downStart, Direction.DOWN)) return Placement(downStart, Direction.DOWN)

                // Try placing ACROSS through this intersection.
                val acrossStart = CrosswordPos(row, col - i)
                if (canPlace(grid, word, acrossStart, Direction.ACROSS)) return Placement(acrossStart, Direction.ACROSS)
            }
        }
        return null
    }

    private fun canPlace(grid: Array<Array<Char?>>, word: String, start: CrosswordPos, direction: Direction): Boolean {
        var hasIntersection = false
        for (i in word.indices) {
            val r = if (direction == Direction.DOWN) start.row + i else start.row
            val c = if (direction == Direction.ACROSS) start.col + i else start.col
            if (r !in 0 until gridSize || c !in 0 until gridSize) return false

            val existing = grid[r][c]
            if (existing != null) {
                if (existing != word[i]) return false
                hasIntersection = true
            }
        }
        return hasIntersection
    }

    private fun placeWord(grid: Array<Array<Char?>>, word: String, row: Int, col: Int, direction: Direction) {
        for (i in word.indices) {
            val r = if (direction == Direction.DOWN) row + i else row
            val c = if (direction == Direction.ACROSS) col + i else col
            grid[r][c] = word[i]
        }
    }

    private fun assignNumbers(entries: List<CrosswordEntry>): List<CrosswordEntry> {
        val startPositions = entries.map { it.start }.distinct().sortedWith(compareBy({ it.row }, { it.col }))
        val numberByPos = startPositions.withIndex().associate { (idx, pos) -> pos to (idx + 1) }
        return entries.map { it.copy(number = numberByPos[it.start] ?: 0) }
    }

    private fun buildCellGrid(letterGrid: Array<Array<Char?>>, entries: List<CrosswordEntry>): List<List<CrosswordCell>> {
        val numberAt = entries.associate { it.start to it.number }
        return (0 until gridSize).map { r ->
            (0 until gridSize).map { c ->
                CrosswordCell(letter = letterGrid[r][c], number = numberAt[CrosswordPos(r, c)])
            }
        }
    }

    private fun emptyGrid(): List<List<CrosswordCell>> =
        (0 until gridSize).map { (0 until gridSize).map { CrosswordCell(letter = null) } }

    companion object {
        /** How many letters shorter than the longest word still counts as a valid seed candidate. */
        private const val SEED_LENGTH_TOLERANCE = 2

        /** How many past seed words [pickSeed] avoids repeating. */
        private const val SEED_HISTORY_SIZE = 5

        /** Total per-entry hint reveals allowed per puzzle — see [revealNextLetter]. */
        const val MAX_HINTS_PER_PUZZLE = 3
    }
}
