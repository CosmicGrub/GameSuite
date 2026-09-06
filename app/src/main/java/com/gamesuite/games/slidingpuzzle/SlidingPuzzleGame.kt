package com.gamesuite.games.slidingpuzzle

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty

/**
 * One puzzle's board: [size] x [size] cells in row-major order, [tiles]
 * holding the numbers 1..(size*size-1) plus a single 0 standing in for the
 * blank cell. [solved] is per-puzzle ("this board is currently arranged
 * correctly"), separate from [SlidingPuzzleGame.matchOver] which only flips
 * once the whole session ends — same split Hangman uses between a round's
 * outcome and the session's.
 */
data class SlidingPuzzleState(
    val size: Int,
    val tiles: List<Int>,
    val moveCount: Int = 0,
    val solved: Boolean = false
)

/**
 * Classic 15-puzzle (roadmap item 13s, "picture puzzles (jigsaw/sliding
 * tile)"). Scoped deliberately to the sliding-tile half of that bullet: a
 * jigsaw needs image-slicing and bitmap asset infra this project doesn't
 * have, while a sliding-tile puzzle needs nothing but numbers and a grid,
 * so it's the honest, tractable MVP for the entry. The "picture" framing is
 * earned without any image loading by giving each tile a distinct flat
 * color (HSV-spaced by tile number, see SlidingPuzzleScreen) drawn behind
 * its number — solving the puzzle also reassembles a simple color mosaic.
 *
 * SOLVABILITY: a uniformly-random permutation of a 15-puzzle is unsolvable
 * exactly half the time (a parity invariant on the permutation + blank row
 * distance). Rather than shuffle-then-check-parity, this generates a start
 * position by making a large number of random *legal* single-tile moves
 * from the solved state — every intermediate position stays solvable by
 * construction, since each move is invertible, so this can never produce an
 * unsolvable board. See [scramble].
 *
 * Same solo-puzzle session pattern as HangmanGame: a module-level
 * [matchOver] distinct from the per-puzzle [SlidingPuzzleState.solved];
 * solving a puzzle does not end the match, just bumps [puzzlesSolved];
 * [playAgain] generates a fresh puzzle keeping the tally; [leaveSession]
 * builds the [GameResult] from the tally and ends the match.
 *
 * Difficulty (from Settings' "Default CPU difficulty" — there's no bot
 * here either, so exactly as in Hangman the lever has to be the puzzle
 * itself) scales both grid size and scramble depth: EASY is a smaller
 * 3x3 (8-puzzle) with a lighter scramble, MEDIUM is the classic 4x4 with a
 * solid 200-move scramble, HARD is a larger 5x5 with a longer scramble.
 */
class SlidingPuzzleGame : GameModule {
    override val gameId = "sliding-puzzle"
    override val displayName = "Sliding Puzzle"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<SlidingPuzzleState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-puzzle. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** grid size to scramble-move-count, per difficulty. */
    private val difficultyConfig: Map<CpuDifficulty, Pair<Int, Int>> = mapOf(
        CpuDifficulty.EASY to (3 to 100),
        CpuDifficulty.MEDIUM to (4 to 200),
        CpuDifficulty.HARD to (5 to 300)
    )

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val (size, scrambleMoves) = difficultyConfig[difficulty] ?: difficultyConfig.getValue(CpuDifficulty.MEDIUM)
        state.value = generatePuzzle(size, scrambleMoves)
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Tap a tile: if it's orthogonally adjacent to the blank, it slides into it. Otherwise a no-op. */
    fun tapTile(index: Int) {
        val s = state.value ?: return
        if (s.solved) return

        val blankIndex = s.tiles.indexOf(0)
        if (index !in neighborsOf(blankIndex, s.size)) return

        val newTiles = s.tiles.toMutableList()
        newTiles[blankIndex] = newTiles[index].also { newTiles[index] = newTiles[blankIndex] }

        val isSolved = newTiles == solvedTiles(s.size)
        state.value = s.copy(tiles = newTiles, moveCount = s.moveCount + 1, solved = isSolved)

        if (isSolved) {
            puzzlesSolved.value += 1
        }
    }

    /** Called from the solved panel's "New Puzzle" button — keeps the running tally. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the solved panel's "Back to Menu" button — ends the whole session. */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = if (player != null) listOf(
                PlayerScore(playerId = player.playerId, score = puzzlesSolved.value, isWinner = puzzlesSolved.value > 0)
            ) else emptyList()
        )
        endMatch(result)
    }

    private fun solvedTiles(size: Int): List<Int> = (1 until size * size).toList() + 0

    private fun neighborsOf(index: Int, size: Int): List<Int> {
        val row = index / size
        val col = index % size
        val result = mutableListOf<Int>()
        if (row > 0) result += index - size
        if (row < size - 1) result += index + size
        if (col > 0) result += index - 1
        if (col < size - 1) result += index + 1
        return result
    }

    /**
     * Random walk of [moves] legal single-tile slides starting from the
     * solved board. Every step swaps the blank with one of its (2-4)
     * neighbors, excluding the cell the blank just came from so the walk
     * doesn't waste half its moves undoing the previous one. Because each
     * step is itself a legal (hence reversible) move, the result is always
     * solvable — there is no shuffle-then-check-parity step needed.
     */
    private fun scramble(size: Int, moves: Int): List<Int> {
        val tiles = solvedTiles(size).toMutableList()
        var blank = tiles.indexOf(0)
        var cameFrom = -1
        repeat(moves) {
            val candidates = neighborsOf(blank, size).filter { it != cameFrom }
            val next = candidates.random()
            tiles[blank] = tiles[next].also { tiles[next] = tiles[blank] }
            cameFrom = blank
            blank = next
        }
        return tiles
    }

    private fun generatePuzzle(size: Int, scrambleMoves: Int): SlidingPuzzleState {
        var tiles = scramble(size, scrambleMoves)
        // Belt-and-suspenders: with a scramble count this high, landing back
        // on the solved board is not a practical concern, but never ship a
        // "shuffled" puzzle that's actually already solved.
        while (tiles == solvedTiles(size)) {
            tiles = scramble(size, scrambleMoves)
        }
        return SlidingPuzzleState(size = size, tiles = tiles, moveCount = 0, solved = false)
    }
}
