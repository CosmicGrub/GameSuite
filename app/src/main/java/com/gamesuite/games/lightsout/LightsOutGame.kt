package com.gamesuite.games.lightsout

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * [cells] is row-major (size*size), true = lit. Pressing a cell (see
 * [LightsOutGame.press]) toggles it AND its up/down/left/right neighbors —
 * the entire rule set of this puzzle. Won once every cell is off.
 */
data class LightsOutState(
    val size: Int,
    val cells: List<Boolean>,
    val moves: Int = 0,
    val won: Boolean = false
) {
    val isOver: Boolean get() = won
}

/**
 * Classic Lights Out: an NxN grid of lit/unlit cells; pressing one toggles
 * it and its orthogonal (not diagonal) neighbors; clear every light to win.
 * The smallest engine in this new-games batch — the whole rule set really is
 * one XOR-neighbors operation (see [affectedIndices]/[press]) — which is
 * exactly why it was picked as the pacing game between the two larger builds
 * on either side of it (Sudoku before, Dots and Boxes after).
 *
 * SOLVABILITY BY CONSTRUCTION (see [scramble]'s KDoc for the actual math):
 * every board here is reached by pressing real cells starting from the
 * all-off state, so it is GUARANTEED solvable — pressing the exact same set
 * of cells again (any order; a press is its own inverse and the operation
 * is commutative over GF(2)) returns the board to all-off. This sidesteps
 * the well-known real fact that not every arbitrary NxN light pattern is
 * solvable for every N — this engine never presents an arbitrary pattern,
 * only ones reachable from all-off, which are solvable by definition. Same
 * "solvable by construction, not by validation" idiom as SlidingPuzzleGame's
 * own scramble.
 *
 * Difficulty (no bot here either, so the lever is the puzzle itself, same
 * idiom as every other solo puzzle in this app) scales board size: EASY 3x3,
 * MEDIUM 5x5 (the classic size), HARD 7x7 — a bigger board is a strictly
 * larger, harder state space to reason through, the same "difficulty via
 * grid size" idiom SlidingPuzzleGame uses.
 *
 * DELIBERATE SCOPE CUTS (honest MVP, same spirit as every other game's own
 * documented cuts):
 *   - No hint/solver — a real Lights Out solver is a genuine linear-algebra
 *     exercise (Gaussian elimination over GF(2) to find a minimum-press
 *     solution), a meaningfully separate feature from the board engine
 *     itself. Left for a later pass, same reasoning MinesweeperGame gives
 *     for skipping its own hint/solver.
 *   - No "fewest possible presses" reference shown to the player — only
 *     their own best-ever move count for that tier (see
 *     [LightsOutStatsStore]), not a computed optimal solution length, for
 *     the same reason: computing that requires the same linear-algebra
 *     solver this MVP doesn't build.
 *   - Daily-seed support mirrors MinesweeperGame/SudokuGame/
 *     SlidingPuzzleGame's `startMatch(dailySeed)` exact shape/reasoning —
 *     this file never reads today's date itself.
 */
class LightsOutGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule {
    override val gameId = "lights-out"
    override val displayName = "Lights Out"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<LightsOutState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Same "stopwatch starts on the first real move" idiom as every other solo puzzle's own field. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [press] detects a win; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var currentDailySeed: Long? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT board, subtracted out in [freezeTimer] — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** Board size (N of NxN) per tier. */
    private val difficultyBoardSize: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 3,
        CpuDifficulty.MEDIUM to 5,
        CpuDifficulty.HARD to 7
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

    /** See this class's KDoc / MinesweeperGame.startMatch(dailySeed)'s KDoc for what this is for. */
    fun startMatch(dailySeed: Long?) {
        val random = dailySeed?.let { Random(it) } ?: Random.Default
        currentDailySeed = dailySeed
        val size = difficultyBoardSize[difficulty] ?: difficultyBoardSize.getValue(CpuDifficulty.MEDIUM)

        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true (see the class KDoc's
        // note on this fix) -- found by adversarial review, see that note.
        matchOver.value = false
        state.value = LightsOutState(size = size, cells = scramble(size, random))
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app
     * was actually paused. FOUND BY ADVERSARIAL REVIEW, NOT PART OF THE
     * ORIGINAL DESIGN: [pause]/[resume] used to be empty no-ops while the
     * timer was a pure wall-clock delta (`nowMillis() - start` in
     * [freezeTimer]) — since `SystemClock.elapsedRealtime()` keeps advancing
     * while the app is backgrounded (unlike `uptimeMillis()`), backgrounding
     * mid-puzzle for even a few minutes silently inflated the recorded solve
     * time (and therefore any "best time" record) by the ENTIRE background
     * duration. `GameSessionManager.pause()`/`resume()` really do forward
     * from `MainActivity.onPause()`/`onResume()` — this is not a
     * theoretical/unwired path, it fires on every real backgrounding.
     *
     * Guarded by `pausedAtElapsedRealtime == null` so a second [pause] call
     * with no [resume] in between is a no-op rather than silently moving the
     * anchor forward and losing the intervening interval — the same
     * asymmetry-with-[resume] gap found and fixed in ColorFloodGame.pause()
     * (see that class's KDoc), closed here defensively for the same reason.
     */
    override fun pause() {
        if (pausedAtElapsedRealtime == null && timerStartElapsedRealtime.value != null && state.value?.isOver != true) {
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
     * Presses cell [index]: toggles it and its orthogonal neighbors. No-op
     * on an out-of-range index, an already-won board, or once the whole
     * session has already ended via [leaveSession]/[endMatch] — found
     * missing by ColorFloodGame.pick()'s own adversarial review (only the
     * per-board `isOver` was checked, never `matchOver`, so a call after the
     * final `GameResult` had already been delivered could still mutate
     * state, including a phantom win with no second result ever reported —
     * see that class's KDoc). Not currently reachable through this app's
     * real screens, but a real gap worth closing defensively regardless.
     */
    fun press(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        if (index !in s.cells.indices) return

        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()

        val cells = s.cells.toMutableList()
        for (i in affectedIndices(index, s.size)) cells[i] = !cells[i]
        val won = cells.none { it }
        state.value = s.copy(cells = cells, moves = s.moves + 1, won = won)
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
    }

    /** Called from the finished-board panel's "New Board" button — keeps the running tally. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the finished-board panel's (or in-progress screen's) "Back to Menu" button — ends the whole session. */
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

    /** [index] itself plus whichever of its up/down/left/right neighbors exist on an NxN board — bounds-checked, no wraparound. */
    private fun affectedIndices(index: Int, size: Int): List<Int> {
        val row = index / size
        val col = index % size
        val result = mutableListOf(index)
        if (row > 0) result += index - size
        if (row < size - 1) result += index + size
        if (col > 0) result += index - 1
        if (col < size - 1) result += index + 1
        return result
    }

    /**
     * Builds a scrambled board by pressing real, randomly-chosen cells
     * (with replacement) [size]*[size]*3 times starting from all-off — three
     * full board's worth of presses is generously more than enough to mix a
     * board this small, not a tuned constant.
     *
     * WHY THIS GUARANTEES SOLVABILITY: this puzzle's press operation is
     * addition over GF(2) — pressing cell i adds a fixed "toggle vector" Pi
     * (i and its neighbors) to the board's state vector, and GF(2) addition
     * is commutative and self-inverse (Pi + Pi = 0). If the scramble
     * sequence presses cells i1, i2, ..., ik (possibly with repeats) from
     * all-off, the resulting board is exactly Pi1 + Pi2 + ... + Pik.
     * Pressing that SAME multiset of cells again (any order) adds each Pij a
     * second time, and every doubled term cancels: the board returns to
     * all-off. So every scrambled board is solvable by definition — this
     * sidesteps the well-known real fact that not every arbitrary NxN light
     * pattern is solvable for every N, since this never presents an
     * arbitrary pattern, only ones reached this way.
     *
     * A board that happens to land back on all-off (possible in principle —
     * enough presses could fully cancel out — but with probability roughly
     * 1/2^(size*size), astronomically small for anything but the smallest
     * tier, and still small there) is rejected and re-rolled: presenting an
     * already-solved board isn't a puzzle.
     */
    private fun scramble(size: Int, random: Random): List<Boolean> {
        val pressCount = size * size * 3
        while (true) {
            val cells = MutableList(size * size) { false }
            repeat(pressCount) {
                val index = random.nextInt(size * size)
                for (i in affectedIndices(index, size)) cells[i] = !cells[i]
            }
            if (cells.any { it }) return cells
        }
    }
}
