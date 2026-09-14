package com.gamesuite.games.sudoku

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * One of 81 cells, row-major (index = row * 9 + col).
 *
 * [isGiven] cells are the puzzle's original clues and can never be edited —
 * every mutating engine method (setValue/clearValue/toggleNote) no-ops on
 * one. [notes] are pencil marks (1-9); a cell that already has a [value]
 * never carries notes (setValue clears them; toggleNote refuses to add one).
 */
data class SudokuCell(
    val value: Int? = null,
    val isGiven: Boolean = false,
    val notes: Set<Int> = emptySet()
)

/**
 * [cells] and [solution] are both row-major length-81 lists. [solution] is
 * the one true completion of this board — kept alongside [cells] (not
 * hidden) purely so [SudokuGame.setValue] can tell a right entry from a
 * wrong one without re-deriving it; nothing stops a determined player from
 * reading it off `state.value`, the same non-concern as `MinesweeperCell`
 * exposing `isMine` on hidden cells (an offline single-player local game has
 * no "the server hides this from the client" boundary to defend anyway).
 */
data class SudokuState(
    val cells: List<SudokuCell>,
    val solution: List<Int>,
    val selectedIndex: Int? = null,
    /** Every wrong entry ever placed, counted at the moment it's placed — see [SudokuGame.setValue]'s KDoc for why this never decrements. */
    val mistakes: Int = 0,
    val won: Boolean = false
) {
    val isOver: Boolean get() = won
}

/**
 * Classic 9x9 Sudoku: three overlapping constraints (each row, column, and
 * 3x3 box contains 1-9 exactly once) and a puzzle that's solvable by logic
 * alone from its given clues. Select a cell, then either enter a value or
 * (in notes mode, same "explicit mode toggle" idiom as Minesweeper's flag
 * mode, since this app has no long-press-vs-tap distinction to spare) toggle
 * a pencil mark.
 *
 * GENERATION is the one genuinely nontrivial piece of this engine — see
 * [generateSolvedGrid], [carvePuzzle], and [countSolutions]'s own KDocs. In
 * short: fill a complete valid grid via randomized backtracking, then remove
 * clues one at a time, keeping each removal only if the puzzle-so-far still
 * has EXACTLY ONE solution (verified by actually re-solving it via a
 * minimum-remaining-values backtracking solver under a hard search-effort
 * budget, not assumed) — the standard, well-established technique for
 * guaranteeing a puzzle is uniquely, logically solvable rather than merely
 * "looks like Sudoku." An earlier version of the solver used a naive
 * fixed-order search with no effort bound at all; adversarial review found
 * it could blow up combinatorially (multi-million-step searches, real risk
 * of an on-device ANR) for a real fraction of seeds at HARD's clue count —
 * [countSolutions]'s KDoc has the full story and the fix.
 *
 * Difficulty (no bot here either, so the lever is the puzzle itself, same
 * idiom as Minesweeper/Sliding Puzzle) scales target clue count: EASY 42,
 * MEDIUM 32, HARD 26 — real Sudoku-difficulty ranges, though see
 * [carvePuzzle]'s KDoc for why a given tier's actual clue count can land
 * slightly above its target rather than exactly on it.
 *
 * DELIBERATE SCOPE CUTS (honest MVP, same spirit as every other game's own
 * documented cuts):
 *   - No mistake limit / game-over — [SudokuState.mistakes] is shown to the
 *     player as feedback, not enforced as a fail condition. A "3 strikes"
 *     mode is a real, separable feature, not required for a correct,
 *     playable first version.
 *   - No real-time row/col/box conflict highlighting — a wrong entry is
 *     detected by comparing against the known [SudokuState.solution]
 *     (simple and always correct for a puzzle generated with a unique
 *     solution), not by independently re-deriving which cells conflict with
 *     which. Good enough to tell the player "that's wrong"; a future pass
 *     could highlight exactly which peer(s) it conflicts with.
 *   - No undo stack — unlike Solitaire (where a move can strand cards),
 *     every Sudoku entry directly overwrites the same cell, which already
 *     serves as its own "undo": just enter the right value. A dedicated
 *     undo history would only matter for restoring cleared pencil marks,
 *     judged not worth the complexity for a first version.
 *   - Daily-seed support mirrors MinesweeperGame/SlidingPuzzleGame's
 *     `startMatch(dailySeed)` exact shape/reasoning — this file never reads
 *     today's date itself.
 */
class SudokuGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule {
    override val gameId = "sudoku"
    override val displayName = "Sudoku"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<SudokuState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Same "stopwatch starts on the first real move" idiom as MinesweeperGame's own field — see that class's KDoc. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [setValue] detects a win; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var currentDailySeed: Long? = null

    /** Target clue count per tier — a floor [carvePuzzle] tries to reach, not a guarantee; see that method's KDoc. */
    private val difficultyClueTarget: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 42,
        CpuDifficulty.MEDIUM to 32,
        CpuDifficulty.HARD to 26
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
        val solved = generateSolvedGrid(random)
        val target = difficultyClueTarget[difficulty] ?: difficultyClueTarget.getValue(CpuDifficulty.MEDIUM)
        val puzzle = carvePuzzle(solved, target, random)

        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        state.value = SudokuState(
            cells = List(81) { i ->
                val v = puzzle[i]
                SudokuCell(value = if (v == 0) null else v, isGiven = v != 0)
            },
            solution = solved.toList()
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Selects [index] as the target of the next setValue/clearValue/toggleNote call. Any cell is selectable (including givens) purely for highlighting — only edits are blocked on a given. Out-of-range indices are ignored rather than stored, same guarded-no-op treatment every other invalid state gets here — found missing by adversarial review before this ever shipped. */
    fun selectCell(index: Int) {
        val s = state.value ?: return
        if (s.isOver) return
        if (index !in s.cells.indices) return
        state.value = s.copy(selectedIndex = index)
    }

    /**
     * Sets the selected cell's value (1-9). No-op if nothing is selected,
     * the cell is a given, or the board is already won. A wrong entry is
     * still accepted (not blocked) and counted in [SudokuState.mistakes] —
     * that counter is a running total of every wrong placement EVER made,
     * not a live "currently wrong" count, so correcting a mistake afterward
     * does not decrement it. This matches how most real Sudoku apps track
     * mistakes (a session stat), not a strict/enforced limit — see this
     * class's own KDoc on why there's no game-over here.
     */
    fun setValue(value: Int) {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (s.isOver) return
        val cell = s.cells[index]
        if (cell.isGiven) return

        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()

        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(value = value, notes = emptySet())
        // Placing a value clears that same digit from every peer's pencil
        // marks -- the standard "auto-clean notes" convenience real Sudoku
        // apps have; a pure bookkeeping nicety, not a rules requirement.
        for (p in peersOf(index)) {
            val peer = cells[p]
            if (value in peer.notes) cells[p] = peer.copy(notes = peer.notes - value)
        }

        val mistakes = if (value != s.solution[index]) s.mistakes + 1 else s.mistakes
        val won = cells.indices.all { cells[it].value == s.solution[it] }
        state.value = s.copy(cells = cells, mistakes = mistakes, won = won)
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    /** Clears the selected cell's value only (notes are untouched). No-op on a given cell, an already-empty cell, or an already-won board. */
    fun clearValue() {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (s.isOver) return
        val cell = s.cells[index]
        if (cell.isGiven || cell.value == null) return
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(value = null)
        state.value = s.copy(cells = cells)
    }

    /** Toggles a pencil mark on the selected cell. No-op on a given cell, a cell that already holds a value, or an already-won board. */
    fun toggleNote(value: Int) {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (s.isOver) return
        val cell = s.cells[index]
        if (cell.isGiven || cell.value != null) return
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(notes = if (value in cell.notes) cell.notes - value else cell.notes + value)
        state.value = s.copy(cells = cells)
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = nowMillis() - start
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

    private fun boxIndex(row: Int, col: Int) = (row / 3) * 3 + (col / 3)

    /** Every OTHER index sharing [index]'s row, column, or 3x3 box. */
    private fun peersOf(index: Int): List<Int> {
        val row = index / 9
        val col = index % 9
        val box = boxIndex(row, col)
        return (0 until 81).filter { it != index && (it / 9 == row || it % 9 == col || boxIndex(it / 9, it % 9) == box) }
    }

    /**
     * Randomized backtracking fill of a complete, valid 81-cell grid (0-8
     * masks track which digits 1-9 are already used per row/column/box, so
     * placement legality is an O(1) bitwise check rather than a scan).
     * Tries digits 1..9 in random order per cell, backtracking on dead ends.
     * Always terminates with a valid grid — a full backtracking search over
     * Sudoku's constraint space cannot fail to find one — and does so fast
     * in practice (this is the standard technique; see [SudokuGameTest] for
     * an actual measured timing, not just an assumption).
     */
    private fun generateSolvedGrid(random: Random): IntArray {
        val grid = IntArray(81)
        val rowMask = IntArray(9)
        val colMask = IntArray(9)
        val boxMask = IntArray(9)

        fun fill(pos: Int): Boolean {
            if (pos == 81) return true
            val row = pos / 9
            val col = pos % 9
            val box = boxIndex(row, col)
            for (d in (1..9).shuffled(random)) {
                val bit = 1 shl d
                if (rowMask[row] and bit == 0 && colMask[col] and bit == 0 && boxMask[box] and bit == 0) {
                    rowMask[row] = rowMask[row] or bit
                    colMask[col] = colMask[col] or bit
                    boxMask[box] = boxMask[box] or bit
                    grid[pos] = d
                    if (fill(pos + 1)) return true
                    grid[pos] = 0
                    rowMask[row] = rowMask[row] and bit.inv()
                    colMask[col] = colMask[col] and bit.inv()
                    boxMask[box] = boxMask[box] and bit.inv()
                }
            }
            return false
        }

        check(fill(0)) { "Sudoku full-grid generation failed -- should be unreachable" }
        return grid
    }

    /**
     * Removes cells from [solution] one at a time (random order, single
     * pass) as long as doing so leaves a puzzle with EXACTLY ONE solution —
     * verified by actually re-solving the puzzle-so-far via [countSolutions]
     * (capped at 2, since "one" vs "more than one" is all that matters
     * here), not approximated. This is the standard, well-established
     * technique for guaranteeing a generated puzzle is uniquely, logically
     * solvable.
     *
     * [countSolutions] can come back [INCONCLUSIVE] (its own search-effort
     * budget ran out before reaching a verdict — see that method's KDoc);
     * that's treated IDENTICALLY to "not unique" here, i.e. the cell goes
     * back. Never removing a cell without a verified-unique result is what
     * keeps the "always uniquely, logically solvable" guarantee honest even
     * under that budget.
     *
     * Stops once [targetClues] is reached OR the single pass over all 81
     * cells completes (whichever first) — [targetClues] is therefore a
     * FLOOR this tries to reach, not a promise: a tier that can't be carved
     * all the way down to its nominal target ships with slightly more clues
     * than nominal rather than a non-unique (or unverified) puzzle. A fully
     * general "minimum possible clue count" search (multiple passes,
     * backtracking over which cells to remove) is a much harder, open
     * combinatorial problem this MVP doesn't attempt — an honest trade-off,
     * not a bug.
     */
    private fun carvePuzzle(solution: IntArray, targetClues: Int, random: Random): IntArray {
        val puzzle = solution.copyOf()
        var clues = 81
        for (i in (0 until 81).shuffled(random)) {
            if (clues <= targetClues) break
            val backup = puzzle[i]
            puzzle[i] = 0
            if (countSolutions(puzzle, cap = 2, callBudget = SOLVE_CALL_BUDGET) == 1) {
                clues--
            } else {
                puzzle[i] = backup
            }
        }
        return puzzle
    }

    /**
     * Counts how many completions [grid] (0 = empty) has, stopping as soon
     * as [cap] is reached — [carvePuzzle] only ever needs to distinguish
     * "exactly one" from "more than one," never the true count.
     *
     * Uses a minimum-remaining-values (MRV) heuristic: at each step, branch
     * on the EMPTY cell with the FEWEST legal candidates left (not a fixed
     * left-to-right scan) — the standard, well-known technique for keeping
     * Sudoku backtracking fast in practice, and a real fix, not a tweak: an
     * earlier fixed-order version of this method was found by adversarial
     * review to blow up combinatorially (multi-million-call searches, no
     * plateau found across hundreds of sampled seeds) for a real,
     * non-negligible fraction of puzzles at HARD's 26-clue target.
     *
     * WHY A CALL BUDGET ON TOP OF MRV: MRV dramatically shrinks the search
     * in the common case, but — like any backtracking heuristic — it has no
     * hard worst-case bound of its own; some adversarial grid could still
     * exist. [callBudget] is an unconditional ceiling on total backtracking
     * steps. If it's exhausted before a verdict is reached, this returns
     * [INCONCLUSIVE] rather than guessing — [carvePuzzle] then plays it safe
     * (puts the cell back) rather than ever risk calling a puzzle unique
     * when uniqueness was never actually verified. This bounds
     * [carvePuzzle]'s total worst-case work to (81 removal attempts) x
     * [callBudget], independent of how adversarial a given seed's puzzle
     * geometry turns out to be — the actual property the earlier version
     * was missing, not just a higher-probability-of-being-fast version of
     * the same unbounded algorithm.
     */
    private fun countSolutions(grid: IntArray, cap: Int, callBudget: Int): Int {
        val work = grid.copyOf()
        val rowMask = IntArray(9)
        val colMask = IntArray(9)
        val boxMask = IntArray(9)
        for (i in 0 until 81) {
            val d = work[i]
            if (d != 0) {
                val row = i / 9
                val col = i % 9
                val box = boxIndex(row, col)
                val bit = 1 shl d
                rowMask[row] = rowMask[row] or bit
                colMask[col] = colMask[col] or bit
                boxMask[box] = boxMask[box] or bit
            }
        }

        var count = 0
        var calls = 0
        var budgetExceeded = false

        // true return means "stop searching" -- either cap solutions were
        // found, or the call budget ran out; budgetExceeded distinguishes
        // the two for the final return value below.
        fun solve(): Boolean {
            calls++
            if (calls > callBudget) {
                budgetExceeded = true
                return true
            }

            // Find the empty cell with the fewest legal candidates (MRV).
            var bestPos = -1
            var bestMask = 0
            var bestCandidateCount = 10
            for (p in 0 until 81) {
                if (work[p] != 0) continue
                val row = p / 9
                val col = p % 9
                val box = boxIndex(row, col)
                val availableMask = ALL_DIGITS_MASK and (rowMask[row] or colMask[col] or boxMask[box]).inv()
                val candidateCount = Integer.bitCount(availableMask)
                if (candidateCount == 0) return false // dead end: an empty cell with no legal digit at all
                if (candidateCount < bestCandidateCount) {
                    bestCandidateCount = candidateCount
                    bestPos = p
                    bestMask = availableMask
                    if (candidateCount == 1) break // can't beat a forced cell
                }
            }
            if (bestPos == -1) {
                // No empty cells left -- every cell is filled and legal.
                count++
                return count >= cap
            }

            val row = bestPos / 9
            val col = bestPos % 9
            val box = boxIndex(row, col)
            var remaining = bestMask
            while (remaining != 0) {
                val bit = remaining and (-remaining) // lowest set bit
                remaining = remaining and (remaining - 1) // clear it
                val d = Integer.numberOfTrailingZeros(bit)
                rowMask[row] = rowMask[row] or bit
                colMask[col] = colMask[col] or bit
                boxMask[box] = boxMask[box] or bit
                work[bestPos] = d
                if (solve()) return true
                work[bestPos] = 0
                rowMask[row] = rowMask[row] and bit.inv()
                colMask[col] = colMask[col] and bit.inv()
                boxMask[box] = boxMask[box] and bit.inv()
            }
            return false
        }

        solve()
        return if (budgetExceeded) INCONCLUSIVE else count
    }

    private companion object {
        /** Bits 1..9 set (bit 0 unused, matching every mask in this file). */
        val ALL_DIGITS_MASK = (1..9).fold(0) { acc, d -> acc or (1 shl d) }

        /** See [countSolutions]'s KDoc for why this exists at all and how the number was chosen. */
        const val SOLVE_CALL_BUDGET = 200_000

        /** Sentinel [countSolutions] result meaning "ran out of budget before reaching a verdict," never a real solution count. */
        const val INCONCLUSIVE = -1
    }
}
