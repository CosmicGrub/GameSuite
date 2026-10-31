package com.gamesuite.games.g2048

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.random.Random

/** The four swipe directions. */
enum class G2048Direction { LEFT, RIGHT, UP, DOWN }

/**
 * The three grid sizes (the Hub's "size" choice ids "small" / "classic" / "big").
 *
 * [target] is the tile that wins. A 3 x 3 board physically cannot make a 2048 tile (the biggest
 * tile n cells can hold is 2^(n+1), so 1024 at the very most, and in practice nobody gets near
 * it), so the small board wins at 512. Classic and big both win at 2048.
 */
enum class G2048Size(val id: String, val n: Int, val target: Int) {
    SMALL("small", 3, 512),
    CLASSIC("classic", 4, 2048),
    BIG("big", 5, 2048);

    val cellCount: Int get() = n * n

    /** The largest tile this grid can ever hold: used to reject a corrupt snapshot. */
    val maxTile: Int get() = 1 shl (cellCount + 1)

    companion object {
        /** The size for a Hub "size" choice id; anything unknown or missing is the classic board. */
        fun fromId(id: String?): G2048Size = values().firstOrNull { it.id == id } ?: CLASSIC

        /** The size with exactly this side length, or null. */
        fun forSide(n: Int): G2048Size? = values().firstOrNull { it.n == n }
    }
}

/** One tile's journey in a move: [from] and [to] are cell indices, [merged] true when it joined an equal tile. */
data class G2048Motion(val from: Int, val to: Int, val value: Int, val merged: Boolean)

/** The outcome of sliding a whole grid one way. [cells] is the grid AFTER the move (before any new tile). */
data class G2048MoveResult(
    val cells: List<Int>,
    /** Sum of the values of every tile created by merging in this move. */
    val gained: Int,
    val moved: Boolean,
    val motions: List<G2048Motion>
)

/**
 * The pure rules of 2048: no state, no randomness except where a Random is passed in. Grids are
 * row-major `List<Int>` of side n, 0 for an empty cell, otherwise the tile's value (a power of two).
 */
object G2048Rules {
    /** One new tile in this many is a 4; the rest are 2s (90% / 10%). */
    const val SPAWN_FOUR_ONE_IN = 10

    /** The grid index of position [pos] along line [line] when sliding toward [direction]'s edge (pos 0 is the edge itself). */
    fun lineIndex(direction: G2048Direction, n: Int, line: Int, pos: Int): Int = when (direction) {
        G2048Direction.LEFT -> line * n + pos
        G2048Direction.RIGHT -> line * n + (n - 1 - pos)
        G2048Direction.UP -> pos * n + line
        G2048Direction.DOWN -> (n - 1 - pos) * n + line
    }

    /**
     * Slides every tile as far as it goes toward [direction]. Two equal tiles that meet merge into
     * one of double value, and a tile made by a merge cannot merge again in the same move
     * ([2,2,2,2] slides to [4,4,0,0], not [8,0,0,0]); with three in a row the pair nearest the
     * edge merges first ([2,2,2,0] slides to [4,2,0,0]).
     */
    fun move(cells: List<Int>, n: Int, direction: G2048Direction): G2048MoveResult {
        val out = IntArray(cells.size)
        val motions = ArrayList<G2048Motion>()
        var gained = 0
        for (line in 0 until n) {
            var written = 0
            var lastMerged = false
            for (pos in 0 until n) {
                val from = lineIndex(direction, n, line, pos)
                val v = cells[from]
                if (v == 0) continue
                if (written > 0 && !lastMerged && out[lineIndex(direction, n, line, written - 1)] == v) {
                    val to = lineIndex(direction, n, line, written - 1)
                    out[to] = v * 2
                    gained += v * 2
                    lastMerged = true
                    motions += G2048Motion(from, to, v, merged = true)
                } else {
                    val to = lineIndex(direction, n, line, written)
                    out[to] = v
                    written++
                    lastMerged = false
                    motions += G2048Motion(from, to, v, merged = false)
                }
            }
        }
        val result = out.toList()
        return G2048MoveResult(result, gained, result != cells, motions)
    }

    /** True while any move would change the grid: an empty cell, or two equal neighbours. */
    fun canMove(cells: List<Int>, n: Int): Boolean {
        for (r in 0 until n) for (c in 0 until n) {
            val v = cells[r * n + c]
            if (v == 0) return true
            if (c + 1 < n && cells[r * n + c + 1] == v) return true
            if (r + 1 < n && cells[(r + 1) * n + c] == v) return true
        }
        return false
    }

    /** The directions that would change the grid. */
    fun legalDirections(cells: List<Int>, n: Int): List<G2048Direction> =
        G2048Direction.values().filter { move(cells, n, it).moved }

    /**
     * Picks where a new tile goes and what it is, or null when the grid is full. The cell is chosen
     * uniformly among the empty ones; the value is a 4 one time in [SPAWN_FOUR_ONE_IN], else a 2.
     */
    fun spawn(cells: List<Int>, random: Random): Pair<Int, Int>? {
        val empties = cells.indices.filter { cells[it] == 0 }
        if (empties.isEmpty()) return null
        val index = empties[random.nextInt(empties.size)]
        val value = if (random.nextInt(SPAWN_FOUR_ONE_IN) == 0) 4 else 2
        return index to value
    }

    private fun rank(v: Int): Int = if (v <= 0) 0 else 31 - Integer.numberOfLeadingZeros(v)

    /**
     * A simple position score for the hint (higher is better): many empty cells, merges made, rows
     * and columns that run in one direction (monotonic), and the biggest tile parked in a corner.
     */
    fun evaluate(cells: List<Int>, n: Int, gained: Int): Double {
        val empties = cells.count { it == 0 }
        var penalty = 0.0
        for (line in 0 until n) {
            for (horizontal in listOf(true, false)) {
                var up = 0.0
                var down = 0.0
                for (i in 0 until n - 1) {
                    val a = rank(if (horizontal) cells[line * n + i] else cells[i * n + line])
                    val b = rank(if (horizontal) cells[line * n + i + 1] else cells[(i + 1) * n + line])
                    if (a > b) down += (a * a - b * b).toDouble() else up += (b * b - a * a).toDouble()
                }
                penalty += minOf(up, down)
            }
        }
        val maxValue = cells.maxOrNull() ?: 0
        val corners = listOf(0, n - 1, (n - 1) * n, n * n - 1)
        val cornerBonus = if (maxValue > 0 && corners.any { cells[it] == maxValue }) 2.0 * rank(maxValue) else 0.0
        return empties * 3.0 + ln(1.0 + gained) + cornerBonus - penalty * 0.5
    }

    /** The legal direction with the best [evaluate] score (the first on a tie), or null when no move is possible. */
    fun bestDirection(cells: List<Int>, n: Int): G2048Direction? {
        var best: G2048Direction? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (d in G2048Direction.values()) {
            val r = move(cells, n, d)
            if (!r.moved) continue
            val score = evaluate(r.cells, n, r.gained)
            if (score > bestScore) {
                bestScore = score
                best = d
            }
        }
        return best
    }
}

/**
 * Everything on screen. [previousCells], [lastDirection] and [lastSpawn] only exist so the screen
 * can animate the last move; they are not saved, and are null after an undo or a resume.
 */
data class G2048State(
    val size: G2048Size,
    val cells: List<Int>,
    val score: Int = 0,
    val moves: Int = 0,
    /** True once the target tile has been made in this game. */
    val won: Boolean = false,
    /** True once the player chose to carry on after winning. */
    val keepGoing: Boolean = false,
    /** True once Undo or Hint was used in this game: its score can no longer set a best. */
    val assisted: Boolean = false,
    /** Points earned by the last move (0 when nothing merged). */
    val lastGain: Int = 0,
    val lastDirection: G2048Direction? = null,
    val previousCells: List<Int>? = null,
    val lastSpawn: Int? = null
) {
    val n: Int get() = size.n
    val target: Int get() = size.target

    /** No move is left. */
    val isOver: Boolean get() = !G2048Rules.canMove(cells, n)

    /** The target was reached and the player has not yet chosen to keep going: input waits on that choice. */
    val awaitingContinue: Boolean get() = won && !keepGoing

    /** Moves are refused: the game is over, or waiting on the keep-going choice. */
    val isFinished: Boolean get() = isOver || awaitingContinue

    /** The game has reached a result (a win or a loss) that the session tally counts. */
    val hasResult: Boolean get() = won || isOver

    val highestTile: Int get() = cells.maxOrNull() ?: 0
}

@Serializable
private data class G2048Snapshot(
    val playerIds: List<String>,
    val side: Int,
    val cells: List<Int>,
    val score: Int,
    val moves: Int,
    val won: Boolean,
    val keepGoing: Boolean,
    val assisted: Boolean = false,
    val sessionBest: Int = 0,
    val highScore: Int = 0,
    val finishedWins: Int = 0,
    val finishedLosses: Int = 0
)

private val g2048SnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * 2048: slide the whole grid; equal tiles that meet merge into their sum; a new 2 (90%) or 4 (10%)
 * appears after every move that changed the board; reach the target tile to win (then optionally
 * keep going); the game ends when nothing can move.
 *
 * [random] is injected so tests are deterministic. A resumed game keeps its grid but not the
 * generator's position (a java/kotlin Random cannot be saved), so the tiles dealt after a resume
 * differ from the ones the original run would have dealt; the position itself is exact.
 *
 * ASSISTED GAMES: Undo and Hint both mark the current game assisted ([G2048State.assisted]). An
 * assisted game's score never raises [sessionBest] and the screen never writes it to the stored best.
 */
class G2048Game(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "game-2048"
    override val displayName = "2048"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<G2048State?>(null)

    /** True only once the whole session ends (leaving), not per game. */
    val matchOver = mutableStateOf(false)

    /** The best score of an UNASSISTED game this session (0 before any). */
    val sessionBest = mutableStateOf(0)

    /** The direction the last [hint] suggested, shown until the next move or undo. */
    val hintDirection = mutableStateOf<G2048Direction?>(null)

    /** Bumped whenever a different game is put on screen (new game, resume), so per-game UI state can reset. */
    val gameNumber = mutableStateOf(0)

    /** The grid size the NEXT [startMatch] deals (the Hub's "size" choice; the screen sets it first). A restored game sets it to the saved size. */
    var gridSize: G2048Size = G2048Size.CLASSIC

    private val finishedWins = mutableStateOf(0)
    private val finishedLosses = mutableStateOf(0)
    private var highScore = 0

    /** Snapshots taken just BEFORE each effective move, newest last; popping one is Undo. Per game. */
    private val history = SnapshotHistory()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Games this session that reached a result (won, or ran out of moves), the current one included. */
    val finishedGames: Int
        get() = finishedWins.value + finishedLosses.value + if (state.value?.hasResult == true) 1 else 0

    /** Games this session in which the target was reached, the current one included. */
    val gamesWon: Int
        get() = finishedWins.value + if (state.value?.won == true) 1 else 0

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        state.value = null
        sessionBest.value = 0
        hintDirection.value = null
        finishedWins.value = 0
        finishedLosses.value = 0
        highScore = 0
        history.clear()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Dealing ----------------------------------------------------------------------------

    override fun startMatch() = deal()

    private fun deal() {
        val spec = gridSize
        var cells = List(spec.cellCount) { 0 }
        repeat(2) {
            G2048Rules.spawn(cells, random)?.let { (index, value) ->
                cells = cells.toMutableList().also { list -> list[index] = value }
            }
        }
        matchOver.value = false
        history.clear()
        hintDirection.value = null
        gameNumber.value += 1
        state.value = G2048State(size = spec, cells = cells)
    }

    /**
     * Deals a fresh game within the same session. A game that reached a result is added to the
     * session tally first; a game still in progress is simply discarded (the screen confirms
     * that with the player before calling).
     */
    fun newGame() {
        if (matchOver.value) return
        val s = state.value
        if (s != null && s.hasResult) {
            if (s.won) finishedWins.value += 1 else finishedLosses.value += 1
            highScore = maxOf(highScore, s.score)
        }
        deal()
    }

    /** The finished-game panel's "Play again". */
    fun playAgain() = newGame()

    // --- Playing ----------------------------------------------------------------------------

    /**
     * Slides the grid. Returns true only when the move changed the board; a move that changes
     * nothing (or arrives after the game ended, or while waiting on the keep-going choice) does
     * nothing at all: no new tile, no move counted, no undo step.
     */
    fun move(direction: G2048Direction): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.isFinished) return false
        val result = G2048Rules.move(s.cells, s.n, direction)
        if (!result.moved) return false

        captureSnapshot()?.let { history.push(it) }
        var cells = result.cells
        val spawn = G2048Rules.spawn(cells, random)
        if (spawn != null) cells = cells.toMutableList().also { it[spawn.first] = spawn.second }
        val score = s.score + result.gained
        hintDirection.value = null
        state.value = s.copy(
            cells = cells,
            score = score,
            moves = s.moves + 1,
            won = s.won || result.cells.any { it >= s.target },
            lastGain = result.gained,
            lastDirection = direction,
            previousCells = s.cells,
            lastSpawn = spawn?.first
        )
        if (!s.assisted && score > sessionBest.value) sessionBest.value = score
        return true
    }

    /** After reaching the target: carry on playing for a higher score. */
    fun keepGoing() {
        val s = state.value ?: return
        if (matchOver.value || !s.awaitingContinue) return
        hintDirection.value = null
        state.value = s.copy(keepGoing = true, lastDirection = null, previousCells = null, lastSpawn = null)
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Suggests the direction that scores best on a simple heuristic ([G2048Rules.evaluate]), shows it
     * ([hintDirection]) and returns it. Always a legal move. Null when no move is possible or the
     * game is not accepting moves. Taking a hint makes the game assisted.
     */
    fun hint(): G2048Direction? {
        val s = state.value ?: return null
        if (matchOver.value || s.isFinished) return null
        val direction = G2048Rules.bestDirection(s.cells, s.n) ?: return null
        hintDirection.value = direction
        if (!s.assisted) state.value = s.copy(assisted = true)
        return direction
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value

    /**
     * Takes back the last move (even one that ended the game). The game becomes assisted. The
     * score tally, the grid and the move count return exactly to before that move; the session
     * best keeps whatever an unassisted run really reached.
     */
    override fun undo() {
        if (matchOver.value) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap, assisted = true, restoreTallies = false)
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        val roster = if (::context.isInitialized) context.players.map { it.playerId } else emptyList()
        return g2048SnapshotJson.encodeToString(
            G2048Snapshot.serializer(),
            G2048Snapshot(
                playerIds = roster,
                side = s.n,
                cells = s.cells,
                score = s.score,
                moves = s.moves,
                won = s.won,
                keepGoing = s.keepGoing,
                assisted = s.assisted,
                sessionBest = sessionBest.value,
                highScore = highScore,
                finishedWins = finishedWins.value,
                finishedLosses = finishedLosses.value
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against THIS game's roster and grid shapes; null if unusable. */
    private fun decodeSnapshot(text: String): G2048Snapshot? {
        val snap = try {
            g2048SnapshotJson.decodeFromString(G2048Snapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        if (!::context.isInitialized) return null
        if (snap.playerIds != context.players.map { it.playerId }) return null
        val size = G2048Size.forSide(snap.side) ?: return null
        if (snap.cells.size != size.cellCount) return null
        if (snap.cells.any { !isValidTile(it, size) }) return null
        if (snap.cells.none { it != 0 }) return null
        if (snap.score < 0 || snap.moves < 0 || snap.sessionBest < 0 || snap.highScore < 0) return null
        if (snap.finishedWins < 0 || snap.finishedLosses < 0) return null
        if (snap.keepGoing && !snap.won) return null
        // A tile at or past the target means the game was won; a snapshot that disagrees is corrupt.
        if (!snap.won && snap.cells.any { it >= size.target }) return null
        if (snap.won && snap.cells.none { it >= size.target } && !snap.keepGoing) return null
        return snap
    }

    private fun isValidTile(v: Int, size: G2048Size): Boolean =
        v == 0 || (v >= 2 && v <= size.maxTile && (v and (v - 1)) == 0)

    private fun applySnapshot(snap: G2048Snapshot, assisted: Boolean, restoreTallies: Boolean) {
        val size = G2048Size.forSide(snap.side) ?: return
        if (restoreTallies) {
            sessionBest.value = snap.sessionBest
            highScore = snap.highScore
            finishedWins.value = snap.finishedWins
            finishedLosses.value = snap.finishedLosses
        } else {
            sessionBest.value = maxOf(sessionBest.value, snap.sessionBest)
        }
        gridSize = size
        hintDirection.value = null
        state.value = G2048State(
            size = size,
            cells = snap.cells,
            score = snap.score,
            moves = snap.moves,
            won = snap.won,
            keepGoing = snap.keepGoing,
            assisted = assisted || snap.assisted
        )
    }

    /** Null when there is nothing to save: no game yet, the session ended, the game is over, or a mode that cannot resume. */
    override fun snapshot(): String? {
        return try {
            if (matchOver.value || !::context.isInitialized) return null
            val s = state.value ?: return null
            if (s.isOver) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val snap = decodeSnapshot(snapshot) ?: return false
            matchOver.value = false
            applySnapshot(snap, assisted = false, restoreTallies = true)
            history.clear()
            gameNumber.value += 1
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Lifecycle --------------------------------------------------------------------------

    /** No timer in this game, so nothing to pause. */
    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Ends the session and reports it: the score is the highest any game this session reached
     * (assisted or not), and the player counts as the winner if the target was reached in any of
     * them. Leaving a game that never reached a result goes through [abortMatch] instead (the screen
     * decides), so a walk-away is never recorded as a loss.
     */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val top = maxOf(highScore, state.value?.score ?: 0)
        val result = GameResult(
            scores = if (player != null) {
                listOf(PlayerScore(playerId = player.playerId, score = top, isWinner = gamesWon > 0))
            } else {
                emptyList()
            }
        )
        endMatch(result)
    }
}
