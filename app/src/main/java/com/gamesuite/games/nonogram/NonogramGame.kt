package com.gamesuite.games.nonogram

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** UNDETERMINED -> FILLED -> MARKED_EMPTY -> UNDETERMINED on tap — the standard three-state Nonogram cell cycle. [MARKED_EMPTY] is purely a player memory aid (an "X" mark); it's never scored differently from [UNDETERMINED] — only whether a cell is [FILLED] matters for mistakes/winning. */
enum class NonogramCellState { UNDETERMINED, FILLED, MARKED_EMPTY }

/**
 * [solution] is the one true size*size filled/empty grid (row-major) this
 * board's [rowClues]/[colClues] were derived from and verified unique
 * against — kept alongside [cells] (not hidden) for the same non-concern
 * every other solo puzzle's own state shares (see SudokuState's own KDoc).
 * [rowClues]/[colClues] are each `size` lists of run-lengths, left-to-right
 * / top-to-bottom; an empty list means "this whole line is empty."
 */
data class NonogramState(
    val size: Int,
    val solution: List<Boolean>,
    val cells: List<NonogramCellState>,
    val rowClues: List<List<Int>>,
    val colClues: List<List<Int>>,
    /** Every FILLED mark placed on a cell the solution says should be empty, counted at the moment it's placed — never decrements, same "running total, not a live count" idiom as SudokuState.mistakes. */
    val mistakes: Int = 0,
    val won: Boolean = false
) {
    val isOver: Boolean get() = won
}

/**
 * Nonogram (Picross): row/column numeric clues describe the run-lengths of
 * filled cells in that line; fill the grid to satisfy every clue
 * simultaneously. Flagged in docs/NEW_GAMES_BRAINSTORM.md as "the hardest
 * generator problem in this whole list" — unlike Sudoku's "carve a solved
 * grid down" approach (which only needs to preserve an already-guaranteed-
 * valid grid), a RANDOM candidate grid here has no internal validity
 * constraint to violate, but its DERIVED clues can easily correspond to
 * MULTIPLE valid grids, not just the one that produced them. See
 * [generateUniqueSolvableGrid] and [countSolutions] for the real
 * constraint-satisfaction solver this actually needs, not an approximation.
 *
 * Difficulty (no bot here either, so the lever is the puzzle itself, same
 * idiom as every other solo puzzle in this app) scales board size (EASY
 * 5x5, MEDIUM 10x10, HARD 15x15 — a bigger grid is a strictly larger,
 * harder deduction problem) AND the acceptance bar generation holds a
 * candidate to, per docs/NONOGRAM_DESIGN.md: EASY/MEDIUM only accept a
 * grid [isFullyDeterminedByLineSolving] resolves completely through pure
 * propagation alone (provably unique AND solvable without ever guessing);
 * HARD accepts a backtracking-confirmed-unique grid too, even one
 * line-solving alone can't fully pin down — a real technique-level
 * difficulty jump, not just a bigger board with the same solving method.
 *
 * STATS SHAPE, DELIBERATELY DIFFERENT FROM LIGHTS OUT/COLOR FLOOD/EDGE
 * MATCH: this game tracks best TIME only (see [NonogramStatsStore]), not a
 * two-metric best-moves-and-time pair. Marking a cell with an "X" is normal,
 * encouraged good play here (tracking your own deductions), not a sign of
 * inefficiency the way an extra move is in a sliding-tile or lights-toggle
 * puzzle — so "fewest moves" isn't an honest skill metric for this genre,
 * unlike those. Same reasoning Minesweeper/Sudoku already applied when they
 * chose time-only over Lights Out's own two-metric shape.
 *
 * DELIBERATE SCOPE CUTS (honest MVP, same spirit as every other game's own
 * documented cuts):
 *   - No hint/solver — a real Nonogram hint needs the same line-deduction
 *     logic [lineCanMatch] already implements internally, surfaced as a
 *     "which cells are FORCED given what's known" feature — a genuinely
 *     separate, presentation-facing feature from the generator/checker
 *     this file builds, left for a later pass.
 *   - No auto-detect-and-cross-out-completed-lines (many real Nonogram apps
 *     auto-place "X"s across a fully-satisfied row/column) — a real
 *     convenience, not required for a correct, playable first version.
 *   - Daily-seed support mirrors every other solo puzzle's
 *     `startMatch(dailySeed)` exact shape/reasoning — this file never reads
 *     today's date itself.
 */
class NonogramGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule {
    override val gameId = "nonogram"
    override val displayName = "Nonogram"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<NonogramState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Same "stopwatch starts on the first real move" idiom as every other solo puzzle's own field. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [tapCell] detects a win; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT board, subtracted out in [freezeTimer] — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** Board size (N of NxN) per tier. */
    private val difficultySize: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 5,
        CpuDifficulty.MEDIUM to 10,
        CpuDifficulty.HARD to 15
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
        val size = difficultySize[difficulty] ?: difficultySize.getValue(CpuDifficulty.MEDIUM)

        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true -- see the
        // MinesweeperGame/SudokuGame/LightsOutGame/DotsAndBoxesGame/
        // ColorFloodGame KDocs on this exact fix, built in from the start
        // here rather than rediscovered later.
        matchOver.value = false

        // EASY/MEDIUM require a puzzle the line-solver alone can fully
        // resolve (provably unique AND guess-free); HARD accepts a
        // backtracking-confirmed-unique puzzle too, even if line-solving
        // alone stalls -- see generateUniqueSolvableGrid's own KDoc.
        val solution = generateUniqueSolvableGrid(size, random, requireLineSolvable = difficulty != CpuDifficulty.HARD)
        val rowClues = (0 until size).map { r -> cluesOf((0 until size).map { c -> solution[r * size + c] }) }
        val colClues = (0 until size).map { c -> cluesOf((0 until size).map { r -> solution[r * size + c] }) }

        state.value = NonogramState(
            size = size,
            solution = solution,
            cells = List(size * size) { NonogramCellState.UNDETERMINED },
            rowClues = rowClues,
            colClues = colClues
        )
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app
     * was actually paused, so backgrounding mid-puzzle never inflates the
     * recorded solve time — the same fix (with the same idempotence guard)
     * every other solo puzzle in this batch needed after adversarial review
     * found it missing; built in here from the start rather than
     * rediscovered again.
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
     * Cycles the cell at [index] UNDETERMINED -> FILLED -> MARKED_EMPTY ->
     * UNDETERMINED. No-op if [index] is out of range, the board is already
     * won, or the whole session has already ended via [leaveSession]/
     * [endMatch] (found missing from a sibling game's own first pass by
     * adversarial review — see ColorFloodGame.pick()'s KDoc for the full
     * story — built in here from the start).
     */
    fun tapCell(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        if (index !in s.cells.indices) return

        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()

        val next = when (s.cells[index]) {
            NonogramCellState.UNDETERMINED -> NonogramCellState.FILLED
            NonogramCellState.FILLED -> NonogramCellState.MARKED_EMPTY
            NonogramCellState.MARKED_EMPTY -> NonogramCellState.UNDETERMINED
        }
        val cells = s.cells.toMutableList()
        cells[index] = next

        val mistakes = if (next == NonogramCellState.FILLED && !s.solution[index]) s.mistakes + 1 else s.mistakes
        val won = cells.indices.all { (cells[it] == NonogramCellState.FILLED) == s.solution[it] }

        state.value = s.copy(cells = cells, mistakes = mistakes, won = won)
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
    }

    /** Called from the finished-board panel's "New Puzzle" button — keeps the running tally. */
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

    /** The run-length clue for one true/false [line] — e.g. [true,true,false,true] -> [2,1]; an all-false line -> emptyList(). */
    private fun cluesOf(line: List<Boolean>): List<Int> {
        val result = mutableListOf<Int>()
        var run = 0
        for (cell in line) {
            if (cell) {
                run++
            } else if (run > 0) {
                result += run
                run = 0
            }
        }
        if (run > 0) result += run
        return result
    }

    /**
     * Generates a random size x size grid, derives its row/column clues,
     * and verifies those clues describe EXACTLY ONE grid — if not (or, for
     * [requireLineSolvable], if a real solution exists but pure logic alone
     * can't find it), the whole grid is discarded and a fresh one tried, up
     * to [MAX_GENERATION_ATTEMPTS]. Unlike SudokuGame's "carve a solved
     * grid down" approach, there's no partial-credit "floor, not a
     * guarantee" here — either a fully verified grid meeting the tier's own
     * bar is found, or generation fails outright (see the `error()` below)
     * rather than ever silently shipping an unverified or unfair puzzle.
     *
     * [requireLineSolvable] (true for EASY/MEDIUM, false for HARD — see
     * [startMatch]) is the actual fairness gate docs/NONOGRAM_DESIGN.md
     * calls for: EASY/MEDIUM only accept a candidate [isFullyDeterminedByLineSolving]
     * resolves completely on its own — this PROVES both uniqueness (a fully
     * propagation-determined grid can't have a second, different valid
     * completion) and pure-logic solvability (no guessing was ever needed
     * to find it) in one check, strictly stronger than [countSolutions]'
     * own "some solution is unique, possibly only findable by
     * guess-and-check" guarantee. HARD keeps using [countSolutions]
     * directly (backtracking-confirmed uniqueness, the only check this
     * engine had before the line-solver existed) — a line-solvable grid is
     * *also* backtracking-unique, so this naturally accepts both "solvable
     * by pure logic" and "genuinely needs a guess" HARD candidates without
     * needing to special-case either.
     */
    private fun generateUniqueSolvableGrid(size: Int, random: Random, requireLineSolvable: Boolean): List<Boolean> {
        repeat(MAX_GENERATION_ATTEMPTS) {
            val grid = List(size * size) { random.nextFloat() < FILL_PROBABILITY }
            val rowClues = (0 until size).map { r -> cluesOf((0 until size).map { c -> grid[r * size + c] }) }
            val colClues = (0 until size).map { c -> cluesOf((0 until size).map { r -> grid[r * size + c] }) }
            val accepted = if (requireLineSolvable) {
                isFullyDeterminedByLineSolving(rowClues, colClues, size)
            } else {
                countSolutions(rowClues, colClues, size, cap = 2, callBudget = SOLVE_CALL_BUDGET) == 1
            }
            if (accepted) return grid
        }
        // Should be unreachable in practice -- random grids at every board
        // size this game actually uses meet their own tier's bar with high
        // enough probability that MAX_GENERATION_ATTEMPTS retries
        // essentially never runs out (see NonogramGameTest's own empirical
        // success-rate/timing check) -- but never silently return an
        // unverified or unfair grid if every attempt somehow failed.
        error("Nonogram generation failed to find a valid ${size}x$size grid after $MAX_GENERATION_ATTEMPTS attempts (requireLineSolvable=$requireLineSolvable)")
    }

    /**
     * True iff constraint PROPAGATION alone — never backtracking/guessing —
     * fully determines every cell of a grid satisfying [rowClues]/[colClues].
     * Just [propagateLineConstraints] with no cell left undetermined — a
     * `true` result is simultaneously a proof of uniqueness (no cell was
     * ever left with room for a different value) AND of pure-logic
     * solvability (no guess was ever needed), the actual EASY/MEDIUM
     * fairness gate this class's own KDoc (DIFFICULTY section) and
     * docs/NONOGRAM_DESIGN.md call for.
     */
    private fun isFullyDeterminedByLineSolving(rowClues: List<List<Int>>, colClues: List<List<Int>>, size: Int): Boolean =
        propagateLineConstraints(rowClues, colClues, size).none { it == null }

    /**
     * Runs constraint propagation to a fixed point: repeatedly applies
     * [forcedAssignmentsForLine] across every row and column, applying any
     * newly forced cell, until a full pass makes no further progress — the
     * standard nonogram line-solving technique, and the one piece of logic
     * both [isFullyDeterminedByLineSolving] (does propagation ALONE finish
     * the grid?) and [countSolutions] (propagate first, then backtrack only
     * over whatever's left — see that method's own KDoc) need. Extracted
     * once here rather than duplicated between them.
     */
    private fun propagateLineConstraints(rowClues: List<List<Int>>, colClues: List<List<Int>>, size: Int): Array<Boolean?> {
        val grid = arrayOfNulls<Boolean>(size * size)
        var changed = true
        while (changed) {
            changed = false
            for (r in 0 until size) {
                val line = (0 until size).map { c -> grid[r * size + c] }
                val forced = forcedAssignmentsForLine(line, rowClues[r])
                for (c in 0 until size) {
                    if (grid[r * size + c] == null && forced[c] != null) {
                        grid[r * size + c] = forced[c]
                        changed = true
                    }
                }
            }
            for (c in 0 until size) {
                val line = (0 until size).map { r -> grid[r * size + c] }
                val forced = forcedAssignmentsForLine(line, colClues[c])
                for (r in 0 until size) {
                    if (grid[r * size + c] == null && forced[r] != null) {
                        grid[r * size + c] = forced[r]
                        changed = true
                    }
                }
            }
        }
        return grid
    }

    /**
     * Counts how many size x size grids satisfy BOTH [rowClues] and
     * [colClues], stopping as soon as [cap] is reached — [carvePuzzle]-style
     * callers only ever need to distinguish "exactly one" from "more than
     * one." Backtracks cell-by-cell (row-major), pruning immediately
     * whenever a cell's own row-so-far or column-so-far can no longer
     * possibly match its clue (via [lineCanMatch]'s prefix-feasibility
     * check, called after EVERY single cell placement, not just at row/
     * column ends) — this incremental pruning is what keeps this
     * tractable rather than a brute-force 2^(size*size) search, the same
     * "check consistency as early as possible" idiom SudokuGame's own
     * solver uses.
     *
     * [callBudget] is a hard, unconditional ceiling on total backtracking
     * steps, mirroring SudokuGame.countSolutions' own fix (found necessary
     * there by adversarial review after a real ANR risk) — if exhausted
     * before a verdict is reached, this returns [INCONCLUSIVE] rather than
     * guessing, and [generateUniqueSolvableGrid] discards that grid and
     * tries a fresh one rather than ever accepting a puzzle whose
     * uniqueness was never actually verified.
     *
     * BEFORE any backtracking, runs [propagateLineConstraints] (real
     * constraint propagation to a fixed point — see that method's own
     * KDoc). This is the standard, well-established Nonogram-solving
     * technique — an initial version of this method skipped it and
     * backtracked from a blank grid in naive row-major order, which
     * measured up to ~2.5 SECONDS for some HARD-tier (15x15) seeds despite
     * the call-budget safety net bounding it (a real, if bounded, on-device
     * stall this fixes properly rather than papering over with a bigger
     * budget or a smaller board). Propagation alone fully solves most
     * reasonably-constrained grids outright with zero backtracking (and IS
     * itself already a proof of uniqueness when it fully determines the
     * grid — no cell is left with room for a different value, exactly what
     * [isFullyDeterminedByLineSolving] checks for); backtracking below only
     * ever explores whatever residual cells propagation couldn't pin down.
     */
    private fun countSolutions(rowClues: List<List<Int>>, colClues: List<List<Int>>, size: Int, cap: Int, callBudget: Int): Int {
        val grid = propagateLineConstraints(rowClues, colClues, size)

        var calls = 0
        var count = 0
        var budgetExceeded = false

        fun rowLine(row: Int): List<Boolean?> = (0 until size).map { c -> grid[row * size + c] }
        fun colLine(col: Int): List<Boolean?> = (0 until size).map { r -> grid[r * size + col] }

        val undetermined = (0 until size * size).filter { grid[it] == null }

        fun solve(pos: Int): Boolean {
            calls++
            if (calls > callBudget) {
                budgetExceeded = true
                return true
            }
            if (pos == undetermined.size) {
                count++
                return count >= cap
            }
            val index = undetermined[pos]
            val row = index / size
            val col = index % size
            for (value in booleanArrayOf(false, true)) {
                grid[index] = value
                if (lineCanMatch(rowLine(row), rowClues[row]) && lineCanMatch(colLine(col), colClues[col])) {
                    if (solve(pos + 1)) return true
                }
                grid[index] = null
            }
            return false
        }

        solve(0)
        return if (budgetExceeded) INCONCLUSIVE else count
    }

    /**
     * True iff SOME assignment of [line]'s null ("not yet decided by the
     * caller's backtracking") entries makes the WHOLE line satisfy [clue]'s
     * run-length pattern exactly (an empty [clue] means "every cell must be
     * empty"). A general memoized recursive matcher over (position in line,
     * how many runs placed so far) — not special-cased to "no cells decided
     * yet," so the exact same function works both as [countSolutions]'
     * real-time incremental-consistency check (some prefix decided, a
     * genuine free suffix) and would work checking a fully-decided line.
     * `line.size` is at most 15 (HARD's board size) and `clue.size` at most
     * a handful of runs, so this memoized search is cheap per call despite
     * running on every single cell placement above.
     */
    private fun lineCanMatch(line: List<Boolean?>, clue: List<Int>): Boolean {
        val n = line.size
        val memo = HashMap<Int, Boolean>()

        fun canBeEmpty(pos: Int) = line[pos] != true
        fun canBeFilled(pos: Int) = line[pos] != false

        fun solve(pos: Int, runIndex: Int): Boolean {
            val key = pos * (clue.size + 1) + runIndex
            memo[key]?.let { return it }

            val result = if (runIndex == clue.size) {
                (pos until n).all { canBeEmpty(it) }
            } else {
                var ok = pos < n && canBeEmpty(pos) && solve(pos + 1, runIndex)
                if (!ok) {
                    val runLen = clue[runIndex]
                    val end = pos + runLen
                    if (end <= n && (pos until end).all { canBeFilled(it) }) {
                        ok = if (end == n) {
                            solve(end, runIndex + 1)
                        } else {
                            canBeEmpty(end) && solve(end + 1, runIndex + 1)
                        }
                    }
                }
                ok
            }

            memo[key] = result
            return result
        }

        return solve(0, 0)
    }

    /**
     * For each still-undetermined ([line]'s `null`) position, checks
     * whether EVERY valid completion of [line] (respecting [clue] and
     * [line]'s own already-decided cells) agrees on that position's value —
     * via [lineCanMatch] queried twice (once forcing it filled, once
     * forcing it empty): if only one of the two is feasible, that position
     * is newly forced; if both remain feasible, it's genuinely still
     * ambiguous from this line alone. Already-decided positions pass
     * through unchanged. This is the real per-line deduction
     * [countSolutions]' propagation loop repeatedly applies across every
     * row and column to solve most of a well-constrained grid before ever
     * falling back to backtracking.
     */
    private fun forcedAssignmentsForLine(line: List<Boolean?>, clue: List<Int>): List<Boolean?> {
        return line.mapIndexed { i, current ->
            if (current != null) {
                current
            } else {
                val trial = line.toMutableList()
                trial[i] = true
                val canFill = lineCanMatch(trial, clue)
                trial[i] = false
                val canEmpty = lineCanMatch(trial, clue)
                when {
                    canFill && !canEmpty -> true
                    !canFill && canEmpty -> false
                    else -> null
                }
            }
        }
    }

    private companion object {
        const val MAX_GENERATION_ATTEMPTS = 500
        const val SOLVE_CALL_BUDGET = 200_000
        const val INCONCLUSIVE = -1
        const val FILL_PROBABILITY = 0.45f
    }
}
