package com.gamesuite.games.wordgames.wordsearch

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.wordgames.WordDictionary
import kotlin.random.Random

data class GridPos(val row: Int, val col: Int)

data class PlacedWord(
    val id: Int,
    val word: String,
    val cells: List<GridPos>
)

data class WordSearchState(
    val grid: List<List<Char>>,
    val placedWords: List<PlacedWord>,
    /** Ids of claimed PlacedWord entries — keyed by id rather than word text, since the
     *  same dictionary word can legitimately be placed at two distinct locations. */
    val foundWords: Set<Int>,
    /** First tap of the current selection, or null if none pending. */
    val selectionStart: GridPos? = null,
    val matchOver: Boolean = false
) {
    val allFound: Boolean get() = foundWords.size == placedWords.size
}

/**
 * Word search: pick N random dictionary words that fit an NxN grid, place
 * each along a random direction (8-way, including diagonals and reversed),
 * fill remaining cells with noise letters. Tap a start cell then an end
 * cell in a straight line to claim a word.
 */
class WordSearchGame(
    private val gridSize: Int = 12,
    private val wordCount: Int = 8
) : GameModule {
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

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    private val directions = listOf(
        0 to 1, 0 to -1, 1 to 0, -1 to 0,
        1 to 1, 1 to -1, -1 to 1, -1 to -1
    )

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /** Call once, from the UI, before startMatch() — loads the shared dictionary asset. */
    fun loadDictionary(androidContext: Context) {
        WordDictionary.ensureLoaded(androidContext)
    }

    override fun startMatch() {
        val grid = Array(gridSize) { CharArray(gridSize) { ' ' } }
        val placed = mutableListOf<PlacedWord>()

        val candidateLengths = (4..minOf(gridSize, 9)).shuffled()
        var attempts = 0
        while (placed.size < wordCount && attempts < 500) {
            attempts++
            val length = candidateLengths[attempts % candidateLengths.size]
            // WordDictionary's pool is lowercase (see WordDictionary.kt); placed words are
            // stored uppercase below, so the exclusion set must be lowercased too, or this
            // filter is a silent no-op and the same word can be placed twice.
            val word = WordDictionary.randomWordsOfLength(
                length, 1,
                excluding = placed.map { it.word.lowercase() }.toSet()
            ).firstOrNull() ?: continue

            val (dr, dc) = directions.random()
            val startRow = Random.nextInt(gridSize)
            val startCol = Random.nextInt(gridSize)
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
            if (grid[r][c] == ' ') grid[r][c] = ('A'..'Z').random()
        }

        state.value = WordSearchState(
            grid = grid.map { it.toList() },
            placedWords = placed,
            foundWords = emptySet()
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value = state.value?.copy(matchOver = true)
        onMatchEnd?.invoke(result)
    }

    /** Call on every cell tap. First tap sets selectionStart; second tap attempts to claim a word. */
    fun tapCell(pos: GridPos) {
        val s = state.value ?: return
        if (s.matchOver) return

        val start = s.selectionStart
        if (start == null) {
            state.value = s.copy(selectionStart = pos)
            return
        }

        if (start == pos) {
            state.value = s.copy(selectionStart = null)
            return
        }

        val lineCells = cellsBetween(start, pos)
        if (lineCells == null) {
            state.value = s.copy(selectionStart = pos) // restart selection from the new tap
            return
        }

        val match = s.placedWords.firstOrNull { placed ->
            placed.id !in s.foundWords &&
                (placed.cells == lineCells || placed.cells == lineCells.reversed())
        }

        if (match != null) {
            val newFound = s.foundWords + match.id
            val allDone = newFound.size == s.placedWords.size
            state.value = s.copy(foundWords = newFound, selectionStart = null, matchOver = allDone)
            if (allDone) {
                // Word Search has no turn/attribution tracking (any player can tap any word
                // in pass-and-play), so it's a shared board: every player in the match gets
                // the same completion score instead of only context.localPlayerIndex.
                val scores = context.players.map { p ->
                    PlayerScore(playerId = p.playerId, score = newFound.size, isWinner = true)
                }
                endMatch(GameResult(scores = scores))
            }
        } else {
            state.value = s.copy(selectionStart = null)
        }
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
