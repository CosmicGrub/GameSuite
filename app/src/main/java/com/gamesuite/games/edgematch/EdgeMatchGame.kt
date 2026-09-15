package com.gamesuite.games.edgematch

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * One tile's edge COLOR indices as originally generated ([canonicalEdges],
 * order [TOP]/[RIGHT]/[BOTTOM]/[LEFT] for a 4-edge SQUARE tile, [E]/[NE]/[NW]/
 * [W]/[SW]/[SE] for a 6-edge HEX tile — see [EdgeMatchGeometry] — never mutated
 * once created) plus how many clockwise turns have been applied since
 * ([rotation], `0 until canonicalEdges.size`: 0..3 in 90° steps for square,
 * 0..5 in 60° steps for hex). [currentEdge] is the only thing callers should
 * read to find out what's actually facing a given direction right now — see
 * its own KDoc for the rotation math. A tile's POSITION never changes for the
 * life of a puzzle — see the class KDoc's Core mechanic section for why.
 *
 * Generalized from a hardcoded `% 4` to `% canonicalEdges.size` for the
 * Custom Game Builder's hex phase (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md,
 * Phase 2) — fully backward-compatible, since a 4-edge square tile's own
 * modulus is still 4.
 */
data class EdgeMatchTile(
    val canonicalEdges: List<Int>,
    val rotation: Int = 0
) {
    private val sides get() = canonicalEdges.size

    /**
     * The color index currently facing [direction], accounting for
     * [rotation]. A clockwise turn moves whatever was facing direction `i` to
     * face `(i+1) % sides` instead, so the canonical index currently facing
     * `direction` is `(direction - rotation) mod sides`.
     */
    fun currentEdge(direction: Int): Int = canonicalEdges[((direction - rotation) % sides + sides) % sides]

    fun rotatedClockwise(): EdgeMatchTile = copy(rotation = (rotation + 1) % sides)
}

/**
 * The tiling geometry a board is built on — see
 * docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md's Phase 2 section. [SQUARE] is
 * every EASY/MEDIUM/HARD tier plus Custom mode's original shape (4-edge
 * tiles, row/col neighbors). [HEX] is Custom-mode-only (no fixed tiers use
 * it): 6-edge tiles arranged in a RHOMBUS grid via axial coordinates
 * `(q, r)`, both `0 until size` — deliberately not a "hexagon of hexagons"
 * overall shape, which would need a different tile count per row and break
 * the simple `size x size` slider semantics every other geometry shares.
 */
enum class EdgeMatchGeometry { SQUARE, HEX }

/**
 * [tiles] is [size] x [size], row-major, and stays in that same row-major
 * ORDER for the whole game — tiles never change position, only rotation
 * (see [EdgeMatchGame]'s own KDoc for why). For [EdgeMatchGeometry.HEX],
 * row-major means axial `r` then axial `q` (index `= r * size + q`), the
 * same flat-array convention as [EdgeMatchGeometry.SQUARE]'s row/col.
 * [solved] is per-puzzle ("every interior touching edge currently matches"),
 * separate from [EdgeMatchGame.matchOver] which only flips once the whole
 * session ends — same split every other solo puzzle in this app uses between
 * a round's outcome and the session's.
 */
data class EdgeMatchState(
    val size: Int,
    val colorCount: Int,
    val tiles: List<EdgeMatchTile>,
    val geometry: EdgeMatchGeometry = EdgeMatchGeometry.SQUARE,
    val moves: Int = 0,
    val solved: Boolean = false
)

/**
 * A player-chosen board size + color count (+ [geometry], from the Custom
 * Game Builder's Phase 2) from the Custom Game Builder
 * (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md), overriding [EdgeMatchGame]'s
 * fixed-tier [EdgeMatchGame.difficultyConfig] when set — see
 * [EdgeMatchGame.startCustomMatch]. Size/colorCount are always clamped into
 * [EdgeMatchGame.MIN_SIZE]/[EdgeMatchGame.MAX_SIZE]/[EdgeMatchGame.MIN_COLORS]/
 * [EdgeMatchGame.MAX_COLORS] at construction, so no other code needs to
 * re-validate them.
 */
data class EdgeMatchCustomConfig(val size: Int, val colorCount: Int, val geometry: EdgeMatchGeometry = EdgeMatchGeometry.SQUARE)

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
 *
 * CUSTOM GAME BUILDER (see docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md):
 * [startCustomMatch] lets a player pick size/colorCount (+ optionally
 * [EdgeMatchGeometry.HEX]) directly instead of one of the fixed tiers, via
 * the same generation approach above generalized to whichever geometry is
 * active — see [generateSolvedHexGrid] for hex's own seam-assignment pass.
 * [customConfig] non-null is what [statsKey] and every subsequent
 * [startMatch] read off; [selectDifficultyTier] is the only supported way
 * back to a fixed (always-SQUARE) tier, since it clears [customConfig] as
 * part of switching (a bare `difficulty = tier` assignment would leave a
 * stale [customConfig] in place and silently keep generating custom boards).
 * True Penrose/aperiodic tiling remains a separate, unstarted future phase
 * per that doc.
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
     * Non-null iff the CURRENT (or next) match should use a player-chosen size/
     * color count instead of [difficulty]'s fixed tier — see [startCustomMatch]/
     * [selectDifficultyTier] and the class KDoc's CUSTOM GAME BUILDER section.
     * Private setter: the only supported ways to change it are those two methods,
     * so it's never possible to leave it stale relative to what the UI thinks is
     * selected.
     */
    var customConfig: EdgeMatchCustomConfig? = null
        private set

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
        val cfg = customConfig
        val geometry = cfg?.geometry ?: EdgeMatchGeometry.SQUARE
        val (size, colorCount) = cfg?.let { it.size to it.colorCount }
            ?: difficultyConfig[difficulty]
            ?: difficultyConfig.getValue(CpuDifficulty.MEDIUM)
        timerStartElapsedRealtime.value = null
        solvedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A fresh puzzle is always playable, regardless of whether a PRIOR puzzle's
        // endMatch() left matchOver stuck true -- built in from the start here (not
        // found after the fact), the same fix already needed across every other
        // engine in this batch (see e.g. ColorFloodGame's own KDoc on this exact fix).
        matchOver.value = false
        val tiles = generatePuzzle(size, colorCount, geometry, dailySeed)
        state.value = EdgeMatchState(size = size, colorCount = colorCount, tiles = tiles, geometry = geometry)
        initialArrangement = tiles
    }

    /**
     * Starts a fresh puzzle using a player-chosen [size]/[colorCount]/[geometry]
     * instead of one of the fixed EASY/MEDIUM/HARD tiers (always
     * [EdgeMatchGeometry.SQUARE]) — the Custom Game Builder
     * (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md): same rotate-in-place
     * mechanic regardless of geometry, generation dispatched per-geometry by
     * [generatePuzzle]. Size/colorCount are CLAMPED into [MIN_SIZE]..
     * [MAX_SIZE] / [MIN_COLORS]..[MAX_COLORS] rather than rejected — a slider-
     * driven UI can't produce an out-of-range value anyway, so this is a
     * defensive floor, not user-facing validation. Setting [customConfig] here
     * is what [statsKey] and every later [startMatch] call read off, until
     * [selectDifficultyTier] switches back.
     */
    fun startCustomMatch(size: Int, colorCount: Int, geometry: EdgeMatchGeometry = EdgeMatchGeometry.SQUARE, dailySeed: Long? = null) {
        customConfig = EdgeMatchCustomConfig(
            size = size.coerceIn(MIN_SIZE, MAX_SIZE),
            colorCount = colorCount.coerceIn(MIN_COLORS, MAX_COLORS),
            geometry = geometry
        )
        startMatch(dailySeed)
    }

    /**
     * Switches back to a fixed EASY/MEDIUM/HARD tier, clearing [customConfig] —
     * the only correct way to leave Custom mode. A bare `difficulty = tier`
     * assignment without this would leave [customConfig] set, and [startMatch]
     * would keep silently generating custom boards instead of honoring the new
     * tier. Does NOT itself start a new puzzle, matching [startMatch]'s own
     * "caller decides when" convention.
     */
    fun selectDifficultyTier(tier: CpuDifficulty) {
        customConfig = null
        difficulty = tier
    }

    /**
     * The [EdgeMatchStatsStore] record key for whatever match is CURRENTLY
     * active: the tier name for a normal game, `"CUSTOM_{size}x{colorCount}"`
     * for a square custom game, or `"CUSTOM_HEX_{size}x{colorCount}"` for a
     * hex one — see docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md's Stats sections
     * for why records are kept per-exact-configuration (including geometry)
     * rather than not tracked at all, and why hex gets its own distinct
     * prefix instead of sharing square's (a 6x5 hex board and a 6x5 square
     * board are genuinely different puzzles, not the same difficulty).
     */
    fun statsKey(): String = customConfig?.let {
        val prefix = if (it.geometry == EdgeMatchGeometry.HEX) "CUSTOM_HEX" else "CUSTOM"
        "${prefix}_${it.size}x${it.colorCount}"
    } ?: difficulty.name

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

        val isSolved = isBoardSolved(newTiles, s.size, s.geometry)
        state.value = s.copy(tiles = newTiles, moves = s.moves + 1, solved = isSolved)

        if (isSolved) {
            puzzlesSolved.value += 1
            val start = timerStartElapsedRealtime.value ?: nowMillis()
            solvedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
        }
    }

    /**
     * Which of tile [index]'s own directions (TOP/RIGHT/BOTTOM/LEFT for a
     * SQUARE tile, E/NE/NW/W/SW/SE for a HEX one) currently match their
     * neighbor's touching edge, for the live match feedback the design doc
     * calls for — recomputed fresh from [state] on every call rather than
     * cached, since it's cheap (at most 6 neighbor comparisons) and always
     * needs to reflect the current rotation. A direction with no neighbor (a
     * border-facing edge) never appears here — border edges are unconstrained
     * and never "match," see the class KDoc.
     */
    fun matchingDirections(index: Int): Set<Int> {
        val s = state.value ?: return emptySet()
        if (index !in s.tiles.indices) return emptySet()
        val tile = s.tiles[index]
        val result = mutableSetOf<Int>()
        for (dir in allDirectionsFor(s.geometry)) {
            val neighbor = neighborIndex(index, s.size, s.geometry, dir) ?: continue
            val opposite = oppositeDirection(s.geometry, dir)
            if (tile.currentEdge(dir) == s.tiles[neighbor].currentEdge(opposite)) result += dir
        }
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

    /**
     * The neighbor of [index] in [direction] for [geometry]/[size], or `null`
     * if there is none (a border-facing edge) — the one place both SQUARE's
     * row/col topology and HEX's axial topology are computed, shared by
     * [matchingDirections]/[isBoardSolved]/hex generation so none of them can
     * drift out of sync with each other.
     */
    private fun neighborIndex(index: Int, size: Int, geometry: EdgeMatchGeometry, direction: Int): Int? = when (geometry) {
        EdgeMatchGeometry.SQUARE -> {
            val row = index / size
            val col = index % size
            when (direction) {
                TOP -> if (row > 0) index - size else null
                BOTTOM -> if (row < size - 1) index + size else null
                LEFT -> if (col > 0) index - 1 else null
                RIGHT -> if (col < size - 1) index + 1 else null
                else -> null
            }
        }
        EdgeMatchGeometry.HEX -> {
            val r = index / size
            val q = index % size
            val nq = q + HEX_DELTA_Q[direction]
            val nr = r + HEX_DELTA_R[direction]
            if (nq in 0 until size && nr in 0 until size) nr * size + nq else null
        }
    }

    /** `direction`'s opposite for [geometry] — half a turn away: 2 of 4 for SQUARE, 3 of 6 for HEX. */
    private fun oppositeDirection(geometry: EdgeMatchGeometry, direction: Int): Int = when (geometry) {
        EdgeMatchGeometry.SQUARE -> (direction + 2) % 4
        EdgeMatchGeometry.HEX -> (direction + 3) % 6
    }

    /** Every direction a tile of [geometry] has, for callers (like [matchingDirections]) that need to check all of them from one tile's own perspective. */
    private fun allDirectionsFor(geometry: EdgeMatchGeometry): IntArray = when (geometry) {
        EdgeMatchGeometry.SQUARE -> intArrayOf(TOP, RIGHT, BOTTOM, LEFT)
        EdgeMatchGeometry.HEX -> intArrayOf(E, NE, NW, W, SW, SE)
    }

    /** Exactly one direction per opposite-pair (RIGHT/BOTTOM for SQUARE; E/NE/NW for HEX) — enough to check every interior seam in the whole grid exactly once, per [isBoardSolved]. */
    private fun forwardDirectionsFor(geometry: EdgeMatchGeometry): IntArray = when (geometry) {
        EdgeMatchGeometry.SQUARE -> intArrayOf(RIGHT, BOTTOM)
        EdgeMatchGeometry.HEX -> intArrayOf(E, NE, NW)
    }

    /** True iff every pair of touching edges between adjacent tiles currently matches — the board's own win condition. Border-facing edges (no neighbor) are never checked. Only checks [forwardDirectionsFor] per tile (each interior seam is symmetric, so checking it from one side is enough) — not [allDirectionsFor], which [matchingDirections] needs instead. */
    private fun isBoardSolved(tiles: List<EdgeMatchTile>, size: Int, geometry: EdgeMatchGeometry): Boolean {
        for (i in tiles.indices) {
            for (dir in forwardDirectionsFor(geometry)) {
                val neighbor = neighborIndex(i, size, geometry, dir) ?: continue
                val opposite = oppositeDirection(geometry, dir)
                if (tiles[i].currentEdge(dir) != tiles[neighbor].currentEdge(opposite)) return false
            }
        }
        return true
    }

    /**
     * Builds a fully-matching [size] x [size] SQUARE grid directly from a
     * SEAM-based representation (see the class KDoc's GENERATION section):
     * one random color per INTERIOR seam — [horizontalSeam]\[r\]\[c\] is the
     * shared edge between tile (r,c) and tile (r+1,c), `(size-1) x size` of
     * them; [verticalSeam]\[r\]\[c\] is the shared edge between tile (r,c)
     * and tile (r,c+1), `size x (size-1)` of them — plus one independent
     * random color per border-facing edge (cosmetic only, no neighbor to
     * match). Every tile's own canonical 4-color record is read off directly
     * from its 4 relevant seam/border assignments, so the result is correct
     * by construction with no placement search needed. Every tile starts at
     * rotation 0 here; [generatePuzzle] is what scrambles rotation
     * afterward. See [generateSolvedHexGrid] for HEX's own approach — a hex
     * tile's 6 neighbors don't split into "above"/"left" this cleanly.
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

    /**
     * Builds a fully-matching [size] x [size] HEX grid using axial
     * coordinates `(q, r)`, both `0 until size` — see the class KDoc's Phase
     * 2 / docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md for why a rhombus grid,
     * not a "hexagon of hexagons." A hex tile's 6 neighbors don't split as
     * cleanly into "above"/"left" the way a square tile's 4 do, so instead
     * of two explicit seam arrays, this walks every tile once and, for
     * exactly 3 of its 6 directions ([E]/[NE]/[NW] — one from each of the 3
     * opposite-direction pairs), either reads an already-assigned neighbor
     * color or assigns a fresh one and mirrors it into that neighbor's own
     * opposite-direction slot. Because every direction's opposite lies in
     * the complementary 3-direction set ([W]/[SW]/[SE]), this single E/NE/NW
     * pass visits every interior seam in the whole grid exactly once, from
     * exactly one side — the second pass below only ever fills slots that
     * pass genuinely never reached (a tile with no real neighbor there),
     * with an independent random border color, exactly like square's own
     * border-edge fill.
     */
    private fun generateSolvedHexGrid(size: Int, colorCount: Int, random: Random): List<EdgeMatchTile> {
        val seams = Array(size * size) { arrayOfNulls<Int>(6) }
        fun indexOf(q: Int, r: Int) = r * size + q

        for (r in 0 until size) {
            for (q in 0 until size) {
                val i = indexOf(q, r)
                for (dir in intArrayOf(E, NE, NW)) {
                    val nq = q + HEX_DELTA_Q[dir]
                    val nr = r + HEX_DELTA_R[dir]
                    if (nq in 0 until size && nr in 0 until size) {
                        val color = random.nextInt(colorCount)
                        seams[i][dir] = color
                        seams[indexOf(nq, nr)][(dir + 3) % 6] = color
                    } else {
                        seams[i][dir] = random.nextInt(colorCount)
                    }
                }
            }
        }
        for (i in seams.indices) {
            for (dir in intArrayOf(W, SW, SE)) {
                if (seams[i][dir] == null) seams[i][dir] = random.nextInt(colorCount)
            }
        }
        return (0 until size * size).map { i -> EdgeMatchTile(canonicalEdges = seams[i].map { it!! }) }
    }

    /** Independent random rotation per tile, POSITION unchanged — see the class KDoc's GENERATION section for why rotation alone is enough to guarantee solvability. Uses each tile's OWN edge count (4 for square, 6 for hex), not a hardcoded value. */
    private fun scrambleRotations(solved: List<EdgeMatchTile>, random: Random): List<EdgeMatchTile> =
        solved.map { it.copy(rotation = random.nextInt(it.canonicalEdges.size)) }

    /**
     * [dailySeed], when non-null, makes the puzzle reproducible (`Random(dailySeed)`)
     * instead of using `kotlin.random.Random`'s unseeded default — see the class
     * KDoc for why this file takes a seed value rather than deriving one from the
     * clock itself.
     *
     * Two-level reject-and-reroll, not one: the inner loop re-rolls only ROTATIONS
     * against the same solved grid (belt-and-suspenders -- a tile with
     * rotationally-symmetric colors could land on a nonzero rotation and still happen
     * to match everywhere, the same guard SlidingPuzzleGame/LightsOutGame's own
     * generators use), but that alone isn't a real fix at the Custom Builder's new
     * minimum bounds: at size=[MIN_SIZE]/colorCount=[MIN_COLORS] there's a real
     * (~1/2048), non-negligible chance every seam/border color draw in
     * [generateSolvedGrid] happens to land on the SAME single color -- every tile ends
     * up individually monochrome, which makes EVERY possible rotation vector satisfy
     * [isBoardSolved]. Re-rolling rotations against that same `solved` grid can then
     * never find a non-solved one no matter how many times it's tried, since there
     * isn't one -- an unbounded version of this loop hangs forever (an ANR, since the
     * caller runs on the UI thread). [MAX_ROTATION_REROLLS] bounds that inner loop and,
     * once exhausted, the outer loop generates a genuinely FRESH `solved` grid (new
     * random seam colors, not just new rotations) and tries again -- [MAX_GENERATION_ATTEMPTS]
     * bounds that in turn, the same "never guess past an unbounded budget" discipline
     * every generator in this batch follows (see KakuroGame/KenKenGame), even though
     * reaching it here would require rolling a monochrome grid over and over, itself
     * astronomically unlikely.
     */
    private fun generatePuzzle(size: Int, colorCount: Int, geometry: EdgeMatchGeometry, dailySeed: Long?): List<EdgeMatchTile> {
        val random = if (dailySeed != null) Random(dailySeed) else Random
        repeat(MAX_GENERATION_ATTEMPTS) {
            val solved = when (geometry) {
                EdgeMatchGeometry.SQUARE -> generateSolvedGrid(size, colorCount, random)
                EdgeMatchGeometry.HEX -> generateSolvedHexGrid(size, colorCount, random)
            }
            var scrambled = scrambleRotations(solved, random)
            var rotationRerolls = 0
            while (isBoardSolved(scrambled, size, geometry) && rotationRerolls < MAX_ROTATION_REROLLS) {
                scrambled = scrambleRotations(solved, random)
                rotationRerolls++
            }
            if (!isBoardSolved(scrambled, size, geometry)) return scrambled
        }
        error("Edge Match generation failed to find a genuinely-scrambled puzzle for size=$size colorCount=$colorCount geometry=$geometry after $MAX_GENERATION_ATTEMPTS attempts")
    }

    companion object {
        const val TOP = 0
        const val RIGHT = 1
        const val BOTTOM = 2
        const val LEFT = 3

        // HEX direction indices (Custom Game Builder Phase 2 --
        // docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md) -- values 0..5 happen to
        // overlap numerically with TOP/RIGHT/BOTTOM/LEFT above, but the two
        // sets are never mixed: a tile's own `canonicalEdges.size` (4 or 6)
        // always determines which naming applies. HEX_DELTA_Q/HEX_DELTA_R are
        // this direction's axial-coordinate neighbor offset, indexed the same way.
        const val E = 0
        const val NE = 1
        const val NW = 2
        const val W = 3
        const val SW = 4
        const val SE = 5
        val HEX_DELTA_Q = intArrayOf(1, 1, 0, -1, -1, 0)
        val HEX_DELTA_R = intArrayOf(0, -1, -1, 0, 1, 1)

        // Custom Game Builder bounds (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md) --
        // MIN_SIZE=2 because a 1x1 board has zero interior seams (always trivially
        // "solved", not a puzzle); MIN_COLORS=2 because with a single color every
        // edge always matches. The upper bounds are a UI/legibility ceiling, not an
        // algorithmic one -- see that doc's UI section for why MAX_COLORS needed a
        // palette change to support.
        const val MIN_SIZE = 2
        const val MAX_SIZE = 10
        const val MIN_COLORS = 2
        const val MAX_COLORS = 8

        // generatePuzzle()'s two-level generation-attempt cap -- see that function's own
        // KDoc for why a single unbounded rotation-reroll loop can hang forever at the
        // Custom Builder's widened minimum bounds (2x2 / 2 colors). MAX_ROTATION_REROLLS
        // only needs to be large enough that a GENUINELY low-probability accidental match
        // (astronomically rarer than this, even at the smallest board) doesn't trip the
        // fallback -- not large enough to ever actually exhaust it against a board that's
        // deterministically unsolvable-by-rotation-alone, which fails on its very first
        // check. MAX_GENERATION_ATTEMPTS (re-rolling the seam colors themselves, the same
        // "regenerate the actual input, not just retry the same one" idiom
        // KakuroGame/KenKenGame's own generators use) exists only as the same
        // "never guess past an unverified/unbounded budget" backstop those generators
        // apply -- reaching it here would mean rolling a monochrome board repeatedly,
        // itself astronomically unlikely.
        const val MAX_ROTATION_REROLLS = 64
        const val MAX_GENERATION_ATTEMPTS = 1_000
    }
}
