package com.gamesuite.games.wordgames.crossword

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*

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
    val matchOver: Boolean = false
)

/**
 * Crossword built from a small hand-authored clue bank (see CrosswordClueBank
 * — real clues can't be auto-generated from the bare dictionary). Words are
 * placed greedily: longest first at center, each subsequent word placed at
 * the first valid intersection found with an already-placed word. Input is
 * per-clue (tap a clue, type the whole answer) rather than per-cell — a
 * deliberate simplification for touch input; see games/wordgames/crossword.
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

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val pool = CrosswordClueBank.gamesAndTechTheme.shuffled()
        val (grid, entries) = generate(pool)
        state.value = CrosswordState(
            grid = grid,
            entries = entries,
            solvedEntryIds = emptySet()
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value = state.value?.copy(matchOver = true)
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

        if (allSolved) {
            val player = context.players.getOrNull(context.localPlayerIndex)
            endMatch(GameResult(scores = listOfNotNull(player?.let {
                PlayerScore(playerId = it.playerId, score = solved.size, isWinner = true)
            })))
        }

        return true
    }

    // ---- Generation ----

    private fun generate(pool: List<CrosswordClueBank.Entry>): Pair<List<List<CrosswordCell>>, List<CrosswordEntry>> {
        val letterGrid = Array(gridSize) { arrayOfNulls<Char>(gridSize) }
        val placedEntries = mutableListOf<CrosswordEntry>()
        val sorted = pool.sortedByDescending { it.word.length }
        var nextId = 0

        // Seed with the longest word, centered horizontally through the middle row.
        val seed = sorted.firstOrNull() ?: return emptyGrid() to emptyList()
        val seedRow = gridSize / 2
        val seedCol = (gridSize - seed.word.length) / 2
        if (seedCol < 0) return emptyGrid() to emptyList()

        placeWord(letterGrid, seed.word, seedRow, seedCol, Direction.ACROSS)
        placedEntries.add(
            CrosswordEntry("e${nextId++}", seed.word, seed.clue, Direction.ACROSS, CrosswordPos(seedRow, seedCol), 0)
        )

        for (candidate in sorted.drop(1)) {
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
}
