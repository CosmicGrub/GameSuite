package com.gamesuite.games.edgematch

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * One tile's 4 edge COLOR indices as originally generated ([canonicalEdges],
 * order [TOP], [RIGHT], [BOTTOM], [LEFT] — never mutated once created) plus
 * how many 90°-clockwise turns have been applied since ([rotation], 0..3).
 * [currentEdge] is the only thing callers should read to find out what's
 * actually facing a given direction right now — see its own KDoc for the
 * rotation math. A tile's POSITION never changes for the life of a puzzle —
 * see the class KDoc's Core mechanic section for why.
 */
data class EdgeMatchTile(
    val canonicalEdges: List<Int>,
    val rotation: Int = 0
) {
    /**
     * The color index currently facing [direction] (one of [TOP]/[RIGHT]/
     * [BOTTOM]/[LEFT]), accounting for [rotation]. A clockwise turn moves
     * whatever was facing direction `i` to face `(i+1)%4` instead, so the
     * canonical index currently facing `direction` is
     * `(direction - rotation) mod 4`.
     */
    fun currentEdge(direction: Int): Int = canonicalEdges[((direction - rotation) % 4 + 4) % 4]

    fun rotatedClockwise(): EdgeMatchTile = copy(rotation = (rotation + 1) % 4)
}

/**
 * [tiles] is [size] x [size], row-major, and stays in that same row-major
 * ORDER for the whole game — tiles never change position, only rotation
 * (see [EdgeMatchGame]'s own KDoc for why). [solved] is per-puzzle ("every
 * interior touching edge currently matches"), separate from
 * [EdgeMatchGame.matchOver] which only flips once the whole session ends —
 * same split every other solo puzzle in this app uses between a round's
 * outcome and the session's.
 */
data class EdgeMatchState(
    val size: Int,
    val colorCount: Int,
    val tiles: List<EdgeMatchTile>,
    val moves: Int = 0,
    val solved: Boolean = false
)

/**
 * Edge Match (roadmap item 7, the fixed-board half of the Tessel-inspired
 * tile edge-matching entry — see docs/EDGE_MATCH_DESIGN.md, approved by the
 * project owner via brainstorming, for the full scoping writeup this class
 * implements, and docs/NEW_GAMES_BRAINSTORM.md for why the generative
 * "Custom game" builder, including Penrose tiling, is a deliberately
 * deferred separate phase, not part of this build).
 *
 * CORE MECHANIC: every tile has 4 edges, each carrying a color index; the
 * board is solved once every pair of touching edges between adjacent tiles
 * matches. Unlike real Tessel's own pool-based "place tiles, choosing both
 * position AND rotation" mechanic, **every tile starts already in its
 * correct grid cell and never moves — the only player action is rotating a
 * tile in place** (see [tapTile]). This was the design doc's own
 * single-highest-leverage scoping decision: it turns an open-ended
 * placement/tiling problem (a much harder generation and
 * uniqueness-verification problem) into one that's solvable by
 * construction, the same idiom LightsOutGame/ColorFloodGame already use —
 * scramble a known-solved board via a reversible operation (there,
 * toggling/re-picking; here, rotating), so undoing that exact operation
 * always works. Border-facing edges (no neighbor there) are unconstrained
 * and purely cosmetic; the win condition only asks "is everything
 * currently consistent," not "did you reproduce the exact original
 * arrangement" — any fully-matched rotation state is a legitimate win,
 * which is also what makes a uniqueness proof unnecessary (unlike
 * SudokuGame's own generator).
 *
 * GENERATION (see [generateSolvedGrid]): built directly from a SEAM-based
 * representation rather than placing tiles first and inferring seams — one
 * random color per interior seam (the shared edge between two adjacent
 * tiles), one independent random color per border-facing edge, and each
 * tile's own canonical 4-color record is read off its 4 relevant
 * seam/border assignments. [generatePuzzle] then scrambles by giving every
 * tile an independent random ROTATION only (never touching position or the
 * seam colors themselves), re-rolling if the scramble coincidentally
 * already satisfies every constraint (the same "never hand the player an
 * already-solved board" guard SlidingPuzzleGame/LightsOutGame's own
 * generators use).
 *
 * DIFFICULTY: EASY/MEDIUM/HARD scale BOTH board size AND color count
 * together (4x4/4, 6x6/5, 8x8/6) — the same lever ColorFloodGame already
 * uses, since more cells AND more colors to coordinate both make optimal
 * play genuinely harder, not just longer.
 *
 * MOVES: every valid tap counts as a move unconditionally — including one
 * that rotates an already-correctly-oriented tile, or nets to zero after
 * several taps — the same "every state-changing input counts, no exemption
 * for an unproductive one" idiom LightsOutGame.press() already follows.
 * There's a real minimum move count to reach solved (0-3 rotations per
 * tile), which is the actual skill metric [EdgeMatchStatsStore] tracks.
 */
class EdgeMatchGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule {
    override val gameId = "edge-match"
    override val displayName = "Edge Match"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<EdgeMatchState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-puzzle. */
    val matchOver = mutableStateOf(false)

    /** Same "stopwatch starts on the first real move" idiom as every other solo puzzle's own field — see [tapTile]. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total solve time for the current puzzle, frozen once [tapTile] detects the solve; null until then. */
    val solvedElapsedMillis = mutableStateOf<Long?>(null)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * Snapshot of the puzzle's arrangement as first generated, captured once in
     * [startMatch] — stays untouched by [tapTile] so [resetToInitial] can restore to
     * it, same idiom/purpose as SlidingPuzzleGame's own `initialArrangement`.
     */
    private var initialArrangement: List<EdgeMatchTile>? = null

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT puzzle, subtracted out wherever [solvedElapsedMillis] is computed — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** (board size, color count) per difficulty — see the class KDoc's DIFFICULTY section. */
    private val difficultyConfig: Map<CpuDifficulty, Pair<Int, Int>> = mapOf(
        CpuDifficulty.EASY to (4 to 4),
        CpuDifficulty.MEDIUM to (6 to 5),
        CpuDifficulty.HARD to (8 to 6)
    )

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** See the class KDoc / SlidingPuzzleGame.startMatch(dailySeed)'s KDoc for what [dailySeed] is for. */
    fun startMatch(dailySeed: Long?) {
        val (size, colorCount) = difficultyConfig[difficulty] ?: difficultyConfig.getValue(CpuDifficulty.MEDIUM)
        timerStartElapsedRealtime.value = null
        solvedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A fresh puzzle is always playable, regardless of whether a PRIOR puzzle's
        // endMatch() left matchOver stuck true -- built in from the start here (not
        // found after the fact), the same fix already needed across every other
        // engine in this batch (see e.g. ColorFloodGame's own KDoc on this exact fix).
        matchOver.value = false
        val tiles = generatePuzzle(size, colorCount, dailySeed)
        state.value = EdgeMatchState(size = size, colorCount = colorCount, tiles = tiles)
        initialArrangement = tiles
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app was
     * actually paused, so backgrounding mid-puzzle never inflates the recorded
     * solve time — the same fix Minesweeper/Sudoku/Lights Out/Sliding Puzzle/
     * Color Flood all needed (some found only after the fact by adversarial
     * review); built in here from the start, per docs/EDGE_MATCH_DESIGN.md.
     * Guarded by `pausedAtElapsedRealtime == null` so a second [pause] call with
     * no [resume] in between is a no-op rather than silently losing the
     * intervening interval — the exact idempotency gap Color Flood's own
     * adversarial review found in ITS `pause()`, closed here proactively too.
     */
    override fun pause() {
        if (pausedAtElapsedRealtime == null && timerStartElapsedRealtime.value != null && state.value?.solved != true) {
            pausedAtElapsedRealtime = nowMillis()
        }
    }

    /** Accumulates the just-finished pause's duration into [totalPausedMillis] — see [pause]'s KDoc. */
    override fun resume() {
        pausedAtElapsedRealtime?.let {
            totalPausedMillis += nowMillis() - it
            pausedAtElapsedRealtime = null
        }
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Rotates the tile at [index] 90° clockwise in place — the ONE player
     * action this game has (see the class KDoc's CORE MECHANIC section for
     * why there's no placement/swapping). Every valid tap counts as a move
     * unconditionally. No-op if the board is already solved, [index] is out
     * of range, or the whole session has already ended via
     * [leaveSession]/[endMatch].
     */
    fun tapTile(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.solved) return
        if (index !in s.tiles.indices) return

        val newTiles = s.tiles.toMutableList()
        newTiles[index] = newTiles[index].rotatedClockwise()

        // Stopwatch starts on the first *actual* rotate, not on puzzle generation --
        // same idiom every other solo puzzle's own timer start uses.
        if (timerStartElapsedRealtime.value == null) {
            timerStartElapsedRealtime.value = nowMillis()
        }

        val isSolved = isBoardSolved(newTiles, s.size)
        state.value = s.copy(tiles = newTiles, moves = s.moves + 1, solved = isSolved)

        if (isSolved) {
            puzzlesSolved.value += 1
            val start = timerStartElapsedRealtime.value ?: nowMillis()
            solvedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
        }
    }

    /**
     * Which of tile [index]'s own 4 directions ([TOP]/[RIGHT]/[BOTTOM]/[LEFT])
     * currently match their neighbor's touching edge, for the live match
     * feedback the design doc calls for — recomputed fresh from [state] on
     * every call rather than cached, since it's cheap (at most 4 neighbor
     * comparisons) and always needs to reflect the current rotation. A
     * direction with no neighbor (a border-facing edge) never appears here —
     * border edges are unconstrained and never "match," see the class KDoc.
     */
    fun matchingDirections(index: Int): Set<Int> {
        val s = state.value ?: return emptySet()
        if (index !in s.tiles.indices) return emptySet()
        val row = index / s.size
        val col = index % s.size
        val tile = s.tiles[index]
        val result = mutableSetOf<Int>()
        if (row > 0 && tile.currentEdge(TOP) == s.tiles[index - s.size].currentEdge(BOTTOM)) result += TOP
        if (row < s.size - 1 && tile.currentEdge(BOTTOM) == s.tiles[index + s.size].currentEdge(TOP)) result += BOTTOM
        if (col > 0 && tile.currentEdge(LEFT) == s.tiles[index - 1].currentEdge(RIGHT)) result += LEFT
        if (col < s.size - 1 && tile.currentEdge(RIGHT) == s.tiles[index + 1].currentEdge(LEFT)) result += RIGHT
        return result
    }

    /**
     * Restores the current puzzle to its original scramble ([initialArrangement]),
     * undoing every rotation made so far without generating a new board — distinct
     * from [playAgain], which deals a fresh puzzle entirely. Also resets the move
     * counter and stopwatch the same way a brand-new puzzle would. Mirrors
     * SlidingPuzzleGame.resetToInitial() exactly, including its guards: a no-op
     * once the whole session has ended, or if no puzzle/snapshot exists yet.
     */
    fun resetToInitial() {
        if (matchOver.value) return
        val s = state.value ?: return
        val initial = initialArrangement ?: return
        timerStartElapsedRealtime.value = null
        solvedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        state.value = s.copy(tiles = initial, moves = 0, solved = false)
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

    /** True iff every pair of touching edges between adjacent tiles currently matches — the board's own win condition. Border-facing edges (no neighbor) are never checked. */
    private fun isBoardSolved(tiles: List<EdgeMatchTile>, size: Int): Boolean {
        for (r in 0 until size) {
            for (c in 0 until size) {
                val index = r * size + c
                if (c < size - 1) {
                    val rightIndex = index + 1
                    if (tiles[index].currentEdge(RIGHT) != tiles[rightIndex].currentEdge(LEFT)) return false
                }
                if (r < size - 1) {
                    val belowIndex = index + size
                    if (tiles[index].currentEdge(BOTTOM) != tiles[belowIndex].currentEdge(TOP)) return false
                }
            }
        }
        return true
    }

    /**
     * Builds a fully-matching [size] x [size] grid directly from a SEAM-based
     * representation (see the class KDoc's GENERATION section): one random
     * color per INTERIOR seam — [horizontalSeam]\[r\]\[c\] is the shared edge
     * between tile (r,c) and tile (r+1,c), `(size-1) x size` of them;
     * [verticalSeam]\[r\]\[c\] is the shared edge between tile (r,c) and tile
     * (r,c+1), `size x (size-1)` of them — plus one independent random color
     * per border-facing edge (cosmetic only, no neighbor to match). Every
     * tile's own canonical 4-color record is read off directly from its 4
     * relevant seam/border assignments, so the result is correct by
     * construction with no placement search needed. Every tile starts at
     * rotation 0 here; [generatePuzzle] is what scrambles rotation afterward.
     */
    private fun generateSolvedGrid(size: Int, colorCount: Int, random: Random): List<EdgeMatchTile> {
        val horizontalSeam = Array(size - 1) { IntArray(size) { random.nextInt(colorCount) } }
        val verticalSeam = Array(size) { IntArray(size - 1) { random.nextInt(colorCount) } }

        val tiles = ArrayList<EdgeMatchTile>(size * size)
        for (r in 0 until size) {
            for (c in 0 until size) {
                val top = if (r == 0) random.nextInt(colorCount) else horizontalSeam[r - 1][c]
                val bottom = if (r == size - 1) random.nextInt(colorCount) else horizontalSeam[r][c]
                val left = if (c == 0) random.nextInt(colorCount) else verticalSeam[r][c - 1]
                val right = if (c == size - 1) random.nextInt(colorCount) else verticalSeam[r][c]
                tiles += EdgeMatchTile(canonicalEdges = listOf(top, right, bottom, left))
            }
        }
        return tiles
    }

    /** Independent random rotation per tile, POSITION unchanged — see the class KDoc's GENERATION section for why rotation alone is enough to guarantee solvability. */
    private fun scrambleRotations(solved: List<EdgeMatchTile>, random: Random): List<EdgeMatchTile> =
        solved.map { it.copy(rotation = random.nextInt(4)) }

    /**
     * [dailySeed], when non-null, makes the puzzle reproducible (`Random(dailySeed)`)
     * instead of using `kotlin.random.Random`'s unseeded default — see the class
     * KDoc for why this file takes a seed value rather than deriving one from the
     * clock itself.
     */
    private fun generatePuzzle(size: Int, colorCount: Int, dailySeed: Long?): List<EdgeMatchTile> {
        val random = if (dailySeed != null) Random(dailySeed) else Random
        val solved = generateSolvedGrid(size, colorCount, random)
        var scrambled = scrambleRotations(solved, random)
        // Belt-and-suspenders: a tile with rotationally-symmetric colors could land
        // on a nonzero rotation and still happen to match everywhere -- never ship a
        // "shuffled" puzzle that's actually already solved, same guard
        // SlidingPuzzleGame/LightsOutGame's own generators use.
        while (isBoardSolved(scrambled, size)) {
            scrambled = scrambleRotations(solved, random)
        }
        return scrambled
    }

    companion object {
        const val TOP = 0
        const val RIGHT = 1
        const val BOTTOM = 2
        const val LEFT = 3
    }
}
