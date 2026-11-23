package com.gamesuite.games.flowlines

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Everything needed to put a Flow Lines session back exactly as it was: the dots, the known
 * solution (for hints), every path, the tier and daily seed, moves, hints and the ACTIVE
 * (pause-adjusted) clock. One shape serves both Resume (on disk) and Undo (in memory).
 */
@Serializable
private data class FlowLinesSnapshot(
    val tier: String,
    val size: Int,
    /** One `[a, b]` pair of dot cells per colour. */
    val pairs: List<List<Int>>,
    val solution: List<List<Int>>,
    val paths: List<List<Int>>,
    val moves: Int,
    val won: Boolean,
    val puzzlesSolved: Int,
    val hintsUsed: Int,
    val dailySeed: Long?,
    /** Active elapsed ms (pause-adjusted); null while the stopwatch has not started. */
    val elapsedMillis: Long?,
    /** The frozen solve time once [won]; otherwise null. */
    val finishedMillis: Long?,
    val playerIds: List<String>
)

private val flowLinesSnapshotJson = Json { ignoreUnknownKeys = true }

/** [paths] is one list of cells per colour (see [FlowRules]); [won] once every pair is connected and every cell covered. */
data class FlowLinesState(
    val puzzle: FlowPuzzle,
    val paths: List<List<Int>>,
    val moves: Int = 0,
    val won: Boolean = false
) {
    val size: Int get() = puzzle.size
    val isOver: Boolean get() = won
    val connected: Int get() = FlowRules.connectedCount(puzzle, paths)
    val filled: Int get() = FlowRules.filledCount(paths)
}

/**
 * Flow Lines: join each pair of same-coloured dots with a path so that no two paths cross and every
 * cell is covered. Boards are SOLVABLE BY CONSTRUCTION (see [FlowGenerator]); the generated tiling
 * is kept as the solution so hints never need a search.
 *
 * Input model: a stroke ([beginStroke], [dragTo], [endStroke]) is one move and one Undo step. A
 * hint reveals part of a correct path and marks the puzzle assisted: it can still be solved but sets
 * no record. [tapCell] is the non-drag route (select a dot, then tap neighbouring cells) used by
 * accessibility services. Daily support mirrors the other puzzles: the screen passes
 * `startMatch(dailySeed)`; this file never reads the date.
 */
class FlowLinesGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "flow-lines"
    override val displayName = "Flow Lines"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<FlowLinesState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves), not per-puzzle. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI before startMatch(); chosen with the tier chips. */
    var tier: FlowTier = FlowTier.S7

    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    /** True while the puzzle on screen is the seeded daily one. */
    val isDailyBoard = mutableStateOf(false)

    /** Hints taken on the CURRENT puzzle. Any hint makes the solve assisted: no record. Never lowered by undo. */
    val hintsUsed = mutableStateOf(0)

    /** The last cell a hint revealed, ringed until the next stroke, undo or new puzzle. */
    val hintCell = mutableStateOf<Int?>(null)

    /** The colour being drawn right now (a stroke is in progress), else null. */
    val activeColor = mutableStateOf<Int?>(null)

    /** The colour selected through [tapCell] (accessibility route), else null. */
    val selectedColor = mutableStateOf<Int?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var currentDailySeed: Long? = null
    private var pausedAtElapsedRealtime: Long? = null
    private var totalPausedMillis: Long = 0L
    private val history = SnapshotHistory()

    private var strokeBeforeSnapshot: String? = null
    private var strokeBeforePaths: List<List<Int>>? = null

    /** The seed of the daily puzzle on screen (null for a random one). */
    val dailySeedInUse: Long? get() = currentDailySeed

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
        isDailyBoard.value = false
        hintsUsed.value = 0
        hintCell.value = null
        activeColor.value = null
        selectedColor.value = null
        strokeBeforePaths = null
        strokeBeforeSnapshot = null
        history.clear()
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(s: FlowLinesState, withClock: Boolean = true): String {
        return flowLinesSnapshotJson.encodeToString(
            FlowLinesSnapshot.serializer(),
            FlowLinesSnapshot(
                tier = tier.name,
                size = s.size,
                pairs = s.puzzle.pairs.map { listOf(it.a, it.b) },
                solution = s.puzzle.solution,
                paths = s.paths,
                moves = s.moves,
                won = s.won,
                puzzlesSolved = puzzlesSolved.value,
                hintsUsed = hintsUsed.value,
                dailySeed = currentDailySeed,
                elapsedMillis = if (withClock) activeElapsedMillis() else null,
                finishedMillis = finishedElapsedMillis.value,
                playerIds = context.players.map { it.playerId }
            )
        )
    }

    /** Decodes and validates a snapshot as a whole; null if any part is unusable. */
    private fun decodeSnapshot(text: String): Pair<FlowLinesSnapshot, FlowPuzzle>? {
        return try {
            val snap = flowLinesSnapshotJson.decodeFromString(FlowLinesSnapshot.serializer(), text)
            val snapTier = FlowTier.fromName(snap.tier) ?: return null
            if (snap.size != snapTier.size) return null
            if (snap.pairs.any { it.size != 2 }) return null
            val puzzle = FlowPuzzle(snap.size, snap.pairs.map { FlowPair(it[0], it[1]) }, snap.solution)
            if (!puzzle.isValid()) return null
            if (!FlowRules.validatePaths(puzzle, snap.paths)) return null
            val valid = snap.moves >= 0 && snap.puzzlesSolved >= 0 && snap.hintsUsed >= 0 &&
                (snap.elapsedMillis ?: 0L) >= 0L && (snap.finishedMillis ?: 0L) >= 0L &&
                snap.won == FlowRules.isSolved(puzzle, snap.paths) &&
                snap.playerIds == context.players.map { it.playerId }
            if (valid) snap to puzzle else null
        } catch (_: Exception) {
            null
        }
    }

    override fun snapshot(): String? {
        return try {
            if (!::context.isInitialized) return null
            val s = state.value ?: return null
            if (matchOver.value) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            captureSnapshot(s)
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        try {
            if (!::context.isInitialized) return false
            val (snap, puzzle) = decodeSnapshot(snapshot) ?: return false
            val snapTier = FlowTier.fromName(snap.tier) ?: return false
            val newState = FlowLinesState(puzzle = puzzle, paths = snap.paths, moves = snap.moves, won = snap.won)
            val finished: Long? = if (snap.won) (snap.finishedMillis ?: snap.elapsedMillis ?: 0L) else null
            val active: Long? = finished ?: snap.elapsedMillis
            matchOver.value = false
            tier = snapTier
            currentDailySeed = snap.dailySeed
            isDailyBoard.value = snap.dailySeed != null
            puzzlesSolved.value = snap.puzzlesSolved
            hintsUsed.value = snap.hintsUsed
            hintCell.value = null
            activeColor.value = null
            selectedColor.value = null
            strokeBeforePaths = null
            strokeBeforeSnapshot = null
            history.clear()
            pausedAtElapsedRealtime = null
            totalPausedMillis = 0L
            finishedElapsedMillis.value = finished
            timerStartElapsedRealtime.value = active?.let { nowMillis() - it }
            state.value = newState
            return true
        } catch (_: Exception) {
            return false
        }
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && state.value?.isOver != true

    /** Takes back the last stroke (or hint): paths and move count go back; the clock and hints used do not. */
    override fun undo() {
        if (matchOver.value) return
        endStroke()
        val s = state.value ?: return
        if (s.isOver) return
        val previous = history.pop() ?: return
        val (snap, puzzle) = decodeSnapshot(previous) ?: return
        if (puzzle != s.puzzle) return
        state.value = s.copy(paths = snap.paths, moves = snap.moves, won = false)
        hintCell.value = null
        selectedColor.value = null
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Reveals part of a correct path (see [FlowRules.hintPaths]), ringing the last revealed cell via
     * [hintCell], and marks the puzzle assisted. Null when solved, over, or nothing is left to reveal.
     */
    fun hint(): Int? {
        endStroke()
        val s = state.value ?: return null
        if (matchOver.value || s.isOver) return null
        val h = FlowRules.hintPaths(s.puzzle, s.paths) ?: return null
        history.push(captureSnapshot(s, withClock = false))
        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()
        hintsUsed.value += 1
        hintCell.value = h.cell
        selectedColor.value = null
        val won = FlowRules.isSolved(s.puzzle, h.paths)
        state.value = s.copy(paths = h.paths, won = won)
        if (won) onSolved()
        return h.cell
    }

    // --- Drawing ----------------------------------------------------------------------------

    /**
     * A finger touched [cell]. True when a stroke started (a dot or a path cell); false on an
     * empty cell, a solved board or an ended session, in which case nothing changes.
     */
    fun beginStroke(cell: Int): Boolean {
        if (strokeBeforePaths != null) endStroke()
        val s = state.value ?: return false
        if (matchOver.value || s.isOver) return false
        val b = FlowRules.begin(s.puzzle, s.paths, cell) ?: return false
        strokeBeforeSnapshot = captureSnapshot(s, withClock = false)
        strokeBeforePaths = s.paths
        activeColor.value = b.color
        selectedColor.value = null
        hintCell.value = null
        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()
        if (b.paths != s.paths) state.value = s.copy(paths = b.paths)
        return true
    }

    /** The finger moved to [cell]; the active path follows it one cell at a time (see [FlowRules.stepToward]). */
    fun dragTo(cell: Int) {
        val color = activeColor.value ?: return
        val s = state.value ?: return
        val next = FlowRules.stepToward(s.puzzle, s.paths, color, cell)
        if (next !== s.paths) state.value = s.copy(paths = next)
    }

    /** The finger lifted. A stroke that changed the paths is one move and one Undo step; the board is checked for a solve. */
    fun endStroke() {
        val before = strokeBeforePaths ?: return
        val beforeSnapshot = strokeBeforeSnapshot
        strokeBeforePaths = null
        strokeBeforeSnapshot = null
        activeColor.value = null
        val s = state.value ?: return
        if (FlowRules.sameDrawing(s.paths, before)) {
            // Touching a dot without drawing anything is not a move: put the board back as it was.
            if (s.paths != before) state.value = s.copy(paths = before)
            return
        }
        if (beforeSnapshot != null) history.push(beforeSnapshot)
        finishMove(s)
    }

    /**
     * Non-drag input for accessibility services: tap a dot (or any path cell) to select that colour,
     * then tap neighbouring cells to extend, retrace or cut it. Every change is one move.
     */
    fun tapCell(cell: Int) {
        endStroke()
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        val sel = selectedColor.value
        if (sel != null && sel in s.paths.indices) {
            val path = s.paths[sel]
            if (path.isNotEmpty() && FlowRules.adjacent(s.size, path.last(), cell)) {
                val next = FlowRules.step(s.puzzle, s.paths, sel, cell)
                if (next !== s.paths) {
                    commit(s, next)
                    if (FlowRules.isConnected(s.puzzle, next, sel)) selectedColor.value = null
                    return
                }
            }
        }
        val b = FlowRules.begin(s.puzzle, s.paths, cell)
        if (b == null) {
            selectedColor.value = null
            return
        }
        if (b.paths != s.paths) {
            if (FlowRules.sameDrawing(b.paths, s.paths)) state.value = s.copy(paths = b.paths) else commit(s, b.paths)
        }
        selectedColor.value = b.color
    }

    private fun commit(s: FlowLinesState, newPaths: List<List<Int>>) {
        history.push(captureSnapshot(s, withClock = false))
        hintCell.value = null
        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()
        finishMove(s.copy(paths = newPaths))
    }

    private fun finishMove(s: FlowLinesState) {
        val won = FlowRules.isSolved(s.puzzle, s.paths)
        state.value = s.copy(moves = s.moves + 1, won = won)
        if (won) onSolved()
    }

    private fun onSolved() {
        puzzlesSolved.value += 1
        freezeTimer()
        activeColor.value = null
        selectedColor.value = null
    }

    // --- Lifecycle / clock ------------------------------------------------------------------

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** Deals a puzzle for [tier]; a non-null [dailySeed] (the epoch day) makes it the same for everyone that day. */
    fun startMatch(dailySeed: Long?) {
        currentDailySeed = dailySeed
        isDailyBoard.value = dailySeed != null
        history.clear()
        hintsUsed.value = 0
        hintCell.value = null
        activeColor.value = null
        selectedColor.value = null
        strokeBeforePaths = null
        strokeBeforeSnapshot = null
        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        matchOver.value = false
        val seed = if (dailySeed != null) FlowGenerator.dailySeed(dailySeed, tier) else Random.Default.nextLong()
        val puzzle = FlowGenerator.generate(tier, seed)
        state.value = FlowLinesState(puzzle = puzzle, paths = List(puzzle.colorCount) { emptyList() })
    }

    override fun pause() {
        if (pausedAtElapsedRealtime == null && timerStartElapsedRealtime.value != null && state.value?.isOver != true) {
            pausedAtElapsedRealtime = nowMillis()
        }
    }

    override fun resume() {
        pausedAtElapsedRealtime?.let {
            totalPausedMillis += nowMillis() - it
            pausedAtElapsedRealtime = null
        }
    }

    /**
     * The stopwatch reading a live clock should show right now: wall time since the first stroke
     * minus every paused interval, frozen at the solve time once won. Null before the first stroke.
     */
    fun activeElapsedMillis(): Long? {
        val start = timerStartElapsedRealtime.value ?: return null
        finishedElapsedMillis.value?.let { return it }
        val upTo = pausedAtElapsedRealtime ?: nowMillis()
        return (upTo - start - totalPausedMillis).coerceAtLeast(0L)
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = ((nowMillis() - start) - totalPausedMillis).coerceAtLeast(0L)
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** "New puzzle": deals a fresh random puzzle of the current tier, keeping the running tally. */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        startMatch()
    }

    /** Ends the whole session, scoring the puzzles solved in it. */
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
}
