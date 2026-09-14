package com.gamesuite.games.minesweeper

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

enum class CellState { HIDDEN, REVEALED, FLAGGED }

data class MinesweeperCell(
    val isMine: Boolean = false,
    /** 0-8; meaningless (and never read) while [isMine] is true. */
    val adjacentMines: Int = 0,
    val state: CellState = CellState.HIDDEN
)

/**
 * [cells] is row-major ([rows] x [cols]). [exploded] (lost — a mine was
 * revealed) and [won] (every non-mine cell is revealed) are mutually
 * exclusive terminal outcomes for the CURRENT board only — distinct from
 * [MinesweeperGame.matchOver], which only flips once the whole session
 * ends, same split every other solo-puzzle game here (Hangman, Sliding
 * Puzzle) uses between a round's outcome and the session's.
 */
data class MinesweeperState(
    val rows: Int,
    val cols: Int,
    val cells: List<MinesweeperCell>,
    val mineCount: Int,
    val flagCount: Int = 0,
    val exploded: Boolean = false,
    val won: Boolean = false
) {
    val isOver: Boolean get() = exploded || won
}

/**
 * Classic Minesweeper. Reveal a cell; a 0 auto-floods its neighbors, a
 * number tells you how many of its 8 neighbors are mines, a mine ends the
 * board. Flag a suspected mine instead of revealing it (this app has no
 * long-press/right-click input model, so the screen offers an explicit
 * "flag mode" toggle instead — see MinesweeperScreen).
 *
 * FIRST-CLICK SAFETY: mines are NOT placed at [startMatch] — [minesPlaced]
 * stays false until the player's first [revealCell] call, which places
 * every mine avoiding that cell AND its neighbors (a small "safe zone", not
 * just the one cell) before proceeding. This is the standard modern
 * Minesweeper rule ("the first click is never a mine, and usually opens up
 * a decent-sized area") and also sidesteps ever needing to re-roll a board
 * that happened to be a mine under the first tap.
 *
 * Same solo-puzzle session pattern as SlidingPuzzleGame/HangmanGame: a
 * module-level [matchOver] distinct from the per-board [MinesweeperState.isOver];
 * finishing a board (won OR lost) does not end the match, just bumps
 * [puzzlesSolved] on a win; [playAgain] deals a fresh board keeping the
 * tally; [leaveSession] builds the [GameResult] from the tally and ends
 * the match.
 *
 * Difficulty (there's no bot here either, so exactly as in Sliding Puzzle
 * the lever has to be the puzzle itself) scales board size and mine density:
 * EASY is the classic 9x9/10-mine beginner board, MEDIUM is 16x16/40 mines,
 * HARD is the classic 16x30/99-mine "expert" board.
 *
 * DELIBERATE SCOPE CUTS (honest MVP, same spirit as every other game's own
 * documented cuts — see SolitaireGame.kt/SpiderLogic.h's equivalents):
 *   - No "chord" (tapping an already-revealed number whose flag count
 *     matches it to auto-reveal its remaining neighbors) — a real
 *     convenience, but it lets a misplaced flag silently detonate a mine
 *     on behalf of the player, which needs its own careful confirmation UX
 *     to do safely. A clean follow-up, not required for a correct, playable
 *     first version.
 *   - No hint/solver — a genuine hint needs real constraint-satisfaction
 *     reasoning over the revealed numbers (which cell is *logically*
 *     guaranteed safe or mined), a meaningfully separate feature from the
 *     board engine itself. Left for a later pass.
 *   - Daily-seed support mirrors SlidingPuzzleGame.startMatch(dailySeed)'s
 *     exact shape/reasoning (see that class's KDoc) — this file never reads
 *     today's date itself.
 */
/**
 * [nowMillis] defaults to the real `SystemClock.elapsedRealtime()` for
 * every real call site (`MinesweeperGame()`, matching every other game's
 * own no-arg construction) — overridable only so a plain JUnit test can
 * supply a deterministic fake instead. `SystemClock.elapsedRealtime()`
 * throws "not mocked" under plain JUnit (no Robolectric in this project),
 * which is exactly why SlidingPuzzleGameTest never calls `tapTile()` at
 * all — this seam avoids that same coverage gap here rather than
 * repeating it, without adding a new test dependency project-wide.
 */
class MinesweeperGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule {
    override val gameId = "minesweeper"
    override val displayName = "Minesweeper"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<MinesweeperState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * `SystemClock.elapsedRealtime()` reading taken on the CURRENT board's first reveal, or
     * null before that first reveal (or between boards) — same "stopwatch starts on the first
     * real move, not on board generation" idiom as SlidingPuzzleGame's own
     * `timerStartElapsedRealtime`, and for the same reason (sitting and staring at a fresh
     * board shouldn't count against the clock). The screen derives its live "elapsed time"
     * display from this rather than this class ticking a timer itself.
     */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [revealCell] detects a win OR a loss; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** True once mines have actually been placed for the CURRENT board — see the class KDoc's FIRST-CLICK SAFETY section. */
    private var minesPlaced = false
    private var currentDailySeed: Long? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT board, subtracted out in [freezeTimer] — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** rows x cols x mineCount per difficulty — classic beginner/intermediate/expert board sizes. */
    private val difficultyConfig: Map<CpuDifficulty, Triple<Int, Int, Int>> = mapOf(
        CpuDifficulty.EASY to Triple(9, 9, 10),
        CpuDifficulty.MEDIUM to Triple(16, 16, 40),
        CpuDifficulty.HARD to Triple(16, 30, 99)
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

    /** See MinesweeperGame's class KDoc / SlidingPuzzleGame.startMatch(dailySeed)'s KDoc for what this is for. */
    fun startMatch(dailySeed: Long?) {
        val (rows, cols, mines) = difficultyConfig[difficulty] ?: difficultyConfig.getValue(CpuDifficulty.MEDIUM)
        minesPlaced = false
        currentDailySeed = dailySeed
        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true (see the class KDoc's
        // note on this fix) -- found by adversarial review, see that note.
        matchOver.value = false
        state.value = MinesweeperState(
            rows = rows,
            cols = cols,
            cells = List(rows * cols) { MinesweeperCell() },
            mineCount = mines
        )
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app
     * was actually paused. FOUND BY ADVERSARIAL REVIEW (during the Lights
     * Out pass, which shares this exact pattern) — [pause]/[resume] used to
     * be empty no-ops while the timer was a pure wall-clock delta
     * (`nowMillis() - start` in [freezeTimer]) — since
     * `SystemClock.elapsedRealtime()` keeps advancing while the app is
     * backgrounded (unlike `uptimeMillis()`), backgrounding mid-puzzle for
     * even a few minutes silently inflated the recorded solve time (and
     * therefore any "best time" record) by the ENTIRE background duration.
     * `GameSessionManager.pause()`/`resume()` really do forward from
     * `MainActivity.onPause()`/`onResume()` — this is not a
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
     * Reveal the cell at [index] (row-major). No-op if the board is already
     * decided, the cell isn't hidden (already revealed, or flagged — a
     * flagged cell must be unflagged first, the standard convention), or the
     * whole session has already ended via [leaveSession]/[endMatch] — found
     * missing by ColorFloodGame.pick()'s own adversarial review (only the
     * per-board `isOver` was checked, never `matchOver`, so a call after the
     * final `GameResult` had already been delivered could still mutate
     * state, including a phantom win with no second result ever reported —
     * see that class's KDoc). Not currently reachable through this app's
     * real screens, but a real gap worth closing defensively regardless.
     */
    fun revealCell(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        if (s.cells[index].state != CellState.HIDDEN) return

        // Stopwatch starts on the first *actual* reveal, not on board generation --
        // a no-op tap above never reaches this line, so idle time before the
        // player's first real move is never counted. Same idiom as
        // SlidingPuzzleGame.tapTile()'s own timer start.
        if (timerStartElapsedRealtime.value == null) {
            timerStartElapsedRealtime.value = nowMillis()
        }

        var working = s
        if (!minesPlaced) {
            val random = currentDailySeed?.let { Random(it) } ?: Random
            working = placeMines(working, avoidIndex = index, random = random)
            minesPlaced = true
        }

        if (working.cells[index].isMine) {
            // Loss: reveal every mine so the board explains itself. Everything
            // else (flags included) is left exactly as the player last saw it.
            val revealedMines = working.cells.map { if (it.isMine) it.copy(state = CellState.REVEALED) else it }
            state.value = working.copy(cells = revealedMines, exploded = true)
            freezeTimer()
            return
        }

        working = floodReveal(working, index)
        val revealedCount = working.cells.count { it.state == CellState.REVEALED }
        val safeCellCount = working.rows * working.cols - working.mineCount
        val won = revealedCount == safeCellCount
        state.value = working.copy(won = won)
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
    }

    /** Toggles a flag on a still-hidden cell — a no-op on an already-revealed cell, once the board is decided, or once the whole session has already ended via [leaveSession]/[endMatch] (see [revealCell]'s KDoc for why). */
    fun toggleFlag(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        val cell = s.cells[index]
        if (cell.state == CellState.REVEALED) return

        val flagging = cell.state != CellState.FLAGGED
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(state = if (flagging) CellState.FLAGGED else CellState.HIDDEN)
        state.value = s.copy(cells = cells, flagCount = s.flagCount + if (flagging) 1 else -1)
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

    private fun neighborsOf(index: Int, rows: Int, cols: Int): List<Int> {
        val row = index / cols
        val col = index % cols
        val result = mutableListOf<Int>()
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r in 0 until rows && c in 0 until cols) result += r * cols + c
            }
        }
        return result
    }

    /**
     * Places [MinesweeperState.mineCount] mines uniformly at random among
     * every cell EXCEPT [avoidIndex] and its own neighbors (the "safe zone"
     * — see the class KDoc's FIRST-CLICK SAFETY section), then computes
     * every non-mine cell's [MinesweeperCell.adjacentMines] count.
     * [random] is threaded in (rather than reaching for the global default)
     * so a daily-seeded board is reproducible, same idiom
     * SlidingPuzzleGame.scramble() uses.
     */
    private fun placeMines(s: MinesweeperState, avoidIndex: Int, random: Random): MinesweeperState {
        val safeZone = (neighborsOf(avoidIndex, s.rows, s.cols) + avoidIndex).toSet()
        val candidates = s.cells.indices.filter { it !in safeZone }
        val mineIndices = candidates.shuffled(random).take(s.mineCount.coerceAtMost(candidates.size)).toSet()

        val withMines = s.cells.mapIndexed { i, cell -> cell.copy(isMine = i in mineIndices) }
        val withCounts = withMines.mapIndexed { i, cell ->
            if (cell.isMine) cell else cell.copy(adjacentMines = neighborsOf(i, s.rows, s.cols).count { withMines[it].isMine })
        }
        return s.copy(cells = withCounts)
    }

    /**
     * Reveals [startIndex] and, if it has zero adjacent mines, floods
     * outward through every zero-adjacent-mines neighbor chain, revealing
     * (but not further expanding past) the numbered cells bordering that
     * chain — the classic "click an empty area, a whole region opens up"
     * behavior. Iterative (a plain queue, not recursion) so this can't blow
     * the call stack on the largest (16x30) board. Never reveals a flagged
     * cell, even if the flood would otherwise reach it — flags are the
     * player's own mark and this function must not silently clear them.
     */
    private fun floodReveal(s: MinesweeperState, startIndex: Int): MinesweeperState {
        val cells = s.cells.toMutableList()
        val queue = ArrayDeque<Int>()
        queue.add(startIndex)
        val visited = mutableSetOf<Int>()

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (!visited.add(i)) continue
            val cell = cells[i]
            if (cell.state != CellState.HIDDEN) continue // already revealed, or flagged -- leave flags alone
            cells[i] = cell.copy(state = CellState.REVEALED)
            if (!cell.isMine && cell.adjacentMines == 0) {
                for (n in neighborsOf(i, s.rows, s.cols)) if (n !in visited) queue.add(n)
            }
        }
        return s.copy(cells = cells)
    }
}
