package com.gamesuite.games.nonogram

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NonogramGame]'s generator (random-grid-then-verify-unique) and its
 * solver ([lineCanMatch]/`countSolutions`) are both private — every puzzle
 * here comes through the real public entry point (`difficulty` +
 * `startMatch(dailySeed)`). Both the clue-derivation and the uniqueness
 * verification are re-checked in this file via completely independent,
 * freshly-written implementations (see [independentCluesOf] and
 * [independentCountSolutions]) rather than trusting the engine's own
 * bookkeeping — a bug shared between the engine's solver and this file's
 * own would have to be an identical mistake made twice, independently.
 */
class NonogramGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): NonogramGame {
        var fakeClock = 0L
        val game = NonogramGame(nowMillis = { fakeClock++ })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    // -- Independent clue derivation + solver, deliberately not calling anything in NonogramGame. --

    private fun independentCluesOf(line: List<Boolean>): List<Int> {
        val result = mutableListOf<Int>()
        var run = 0
        for (cell in line) {
            if (cell) run++ else {
                if (run > 0) result += run
                run = 0
            }
        }
        if (run > 0) result += run
        return result
    }

    /** Independent memoized line-feasibility matcher -- same conceptual algorithm as the engine's own [NonogramGame] private method, written completely fresh here. */
    private fun independentLineCanMatch(line: List<Boolean?>, clue: List<Int>): Boolean {
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
                        ok = if (end == n) solve(end, runIndex + 1) else canBeEmpty(end) && solve(end + 1, runIndex + 1)
                    }
                }
                ok
            }
            memo[key] = result
            return result
        }
        return solve(0, 0)
    }

    /** Independent uniqueness-counting backtracker, capped at [cap] solutions and [callBudget] steps -- returns -1 (inconclusive) if the budget runs out. */
    private fun independentCountSolutions(rowClues: List<List<Int>>, colClues: List<List<Int>>, size: Int, cap: Int = 2, callBudget: Int = 2_000_000): Int {
        val grid = arrayOfNulls<Boolean>(size * size)
        var calls = 0
        var count = 0
        var budgetExceeded = false
        fun rowLine(row: Int): List<Boolean?> = (0 until size).map { c -> grid[row * size + c] }
        fun colLine(col: Int): List<Boolean?> = (0 until size).map { r -> grid[r * size + col] }
        fun solve(index: Int): Boolean {
            calls++
            if (calls > callBudget) { budgetExceeded = true; return true }
            if (index == size * size) { count++; return count >= cap }
            val row = index / size
            val col = index % size
            for (value in booleanArrayOf(false, true)) {
                grid[index] = value
                if (independentLineCanMatch(rowLine(row), rowClues[row]) && independentLineCanMatch(colLine(col), colClues[col])) {
                    if (solve(index + 1)) return true
                }
                grid[index] = null
            }
            return false
        }
        solve(0)
        return if (budgetExceeded) -1 else count
    }

    @Test
    fun `difficulty controls board size -- EASY 5x5, MEDIUM 10x10, HARD 15x15`() {
        val expected = mapOf(CpuDifficulty.EASY to 5, CpuDifficulty.MEDIUM to 10, CpuDifficulty.HARD to 15)
        for ((difficulty, size) in expected) {
            val game = newGame(difficulty)
            game.startMatch(dailySeed = 1L)
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty", size, s.size)
            assertEquals(size * size, s.solution.size)
            assertEquals(size, s.rowClues.size)
            assertEquals(size, s.colClues.size)
        }
    }

    @Test
    fun `every generated puzzle's clues are exactly the independently-derived clues of its own solution`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..5L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                for (r in 0 until s.size) {
                    val row = (0 until s.size).map { c -> s.solution[r * s.size + c] }
                    assertEquals("difficulty=$difficulty seed=$seed row=$r", independentCluesOf(row), s.rowClues[r])
                }
                for (c in 0 until s.size) {
                    val col = (0 until s.size).map { r -> s.solution[r * s.size + c] }
                    assertEquals("difficulty=$difficulty seed=$seed col=$c", independentCluesOf(col), s.colClues[c])
                }
            }
        }
    }

    @Test
    fun `every generated puzzle is genuinely uniquely solvable, independently re-verified`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..5L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                val result = independentCountSolutions(s.rowClues, s.colClues, s.size)
                assertNotEquals("difficulty=$difficulty seed=$seed: independent uniqueness check was inconclusive (ran out of budget)", -1, result)
                assertEquals("difficulty=$difficulty seed=$seed: puzzle should have EXACTLY one solution", 1, result)
            }
        }
    }

    /**
     * Independent constraint-propagation line-solver, deliberately not
     * calling anything in [NonogramGame] — mirrors the engine's own
     * `propagateLineConstraints`/`forcedAssignmentsForLine` shape (force
     * each undetermined cell to each of true/false in turn via
     * [independentLineCanMatch], keep whichever direction(s) stay
     * feasible), iterated across every row/column to a fixed point.
     * Returns true iff propagation alone (no guessing) resolves every cell.
     */
    private fun independentIsFullyLineSolvable(rowClues: List<List<Int>>, colClues: List<List<Int>>, size: Int): Boolean {
        val grid = arrayOfNulls<Boolean>(size * size)
        fun forcedFor(line: List<Boolean?>, clue: List<Int>): List<Boolean?> = line.mapIndexed { i, cur ->
            if (cur != null) cur else {
                val trial = line.toMutableList()
                trial[i] = true
                val canFill = independentLineCanMatch(trial, clue)
                trial[i] = false
                val canEmpty = independentLineCanMatch(trial, clue)
                if (canFill && !canEmpty) true else if (!canFill && canEmpty) false else null
            }
        }
        var changed = true
        while (changed) {
            changed = false
            for (r in 0 until size) {
                val line = (0 until size).map { c -> grid[r * size + c] }
                val forced = forcedFor(line, rowClues[r])
                for (c in 0 until size) if (grid[r * size + c] == null && forced[c] != null) { grid[r * size + c] = forced[c]; changed = true }
            }
            for (c in 0 until size) {
                val line = (0 until size).map { r -> grid[r * size + c] }
                val forced = forcedFor(line, colClues[c])
                for (r in 0 until size) if (grid[r * size + c] == null && forced[r] != null) { grid[r * size + c] = forced[r]; changed = true }
            }
        }
        return grid.none { it == null }
    }

    @Test
    fun `EASY and MEDIUM puzzles are always solvable through pure line-deduction alone, independently re-verified -- never require guessing`() {
        // The actual fairness guarantee docs/NONOGRAM_DESIGN.md calls for at
        // these two tiers -- a merely-unique puzzle (what HARD settles for)
        // is a strictly weaker property than one propagation alone can
        // fully resolve. Independently re-derived rather than trusting the
        // engine's own isFullyDeterminedByLineSolving, same "don't let the
        // solver check itself" precedent this file's other tests follow.
        for (difficulty in listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM)) {
            for (seed in 1L..10L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                assertTrue(
                    "difficulty=$difficulty seed=$seed: puzzle should be fully solvable through pure line-deduction, no guessing required",
                    independentIsFullyLineSolvable(s.rowClues, s.colClues, s.size)
                )
            }
        }
    }

    @Test
    fun `generating puzzles across every difficulty completes quickly, across many seeds`() {
        // A coarse performance sanity check, not a strict benchmark -- this is
        // the single most important test in this file given NEW_GAMES_BRAINSTORM.md's
        // own explicit warning that Nonogram's generator is "the hardest ... in
        // this whole list." Exists to catch a real regression (e.g. the
        // callBudget/MAX_GENERATION_ATTEMPTS safety nets failing to bound
        // worst-case work), not to assert a specific number.
        val start = System.nanoTime()
        for (difficulty in CpuDifficulty.entries) {
            repeat(15) { i ->
                val game = newGame(difficulty)
                game.startMatch(dailySeed = 1000L + i)
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("generating 45 puzzles (15 per tier, including HARD's 15x15) took ${elapsedMs}ms, unexpectedly slow", elapsedMs < 30_000)
    }

    @Test
    fun `tapping a cell cycles UNDETERMINED to FILLED to MARKED_EMPTY back to UNDETERMINED`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        assertEquals(NonogramCellState.UNDETERMINED, game.state.value!!.cells[0])
        game.tapCell(0)
        assertEquals(NonogramCellState.FILLED, game.state.value!!.cells[0])
        game.tapCell(0)
        assertEquals(NonogramCellState.MARKED_EMPTY, game.state.value!!.cells[0])
        game.tapCell(0)
        assertEquals(NonogramCellState.UNDETERMINED, game.state.value!!.cells[0])
    }

    @Test
    fun `filling a cell the solution says should be empty counts as a mistake, filling a truly-filled cell does not`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val s = game.state.value!!
        val filledIndex = s.solution.indexOfFirst { it }
        val emptyIndex = s.solution.indexOfFirst { !it }

        game.tapCell(filledIndex) // FILLED, matches solution -- no mistake
        assertEquals(0, game.state.value!!.mistakes)

        game.tapCell(emptyIndex) // FILLED, does NOT match solution -- a mistake
        assertEquals(1, game.state.value!!.mistakes)
    }

    @Test
    fun `filling every solution cell (and only those) wins the board regardless of X-marks elsewhere`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val s = game.state.value!!
        for (i in s.solution.indices) {
            if (s.solution[i]) {
                game.tapCell(i) // -> FILLED
            } else {
                game.tapCell(i) // -> FILLED
                game.tapCell(i) // -> MARKED_EMPTY (an X mark on a correctly-empty cell; must not block winning)
            }
        }
        assertTrue(game.state.value!!.won)
        assertEquals(1, game.puzzlesSolved.value)
        assertNotNull(game.finishedElapsedMillis.value)
    }

    @Test
    fun `tapping an out-of-range index is ignored, not a crash`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val before = game.state.value!!.cells
        game.tapCell(-1)
        assertEquals(before, game.state.value!!.cells)
        game.tapCell(before.size)
        assertEquals(before, game.state.value!!.cells)
    }

    @Test
    fun `tapCell is rejected once the session has ended via leaveSession, even if the board itself was not yet won`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        game.tapCell(0)
        assertFalse(game.state.value!!.won)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.tapCell(1)
        assertEquals("a tapCell() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `pausing mid-puzzle does not inflate the recorded solve time`() {
        var clock = 0L
        val game = NonogramGame(nowMillis = { clock })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.EASY
        game.startMatch(dailySeed = 1L)

        clock = 10L
        game.tapCell(0) // starts the timer
        clock = 20L
        game.pause()
        clock = 600_020L // ~10 real minutes pass while backgrounded
        game.pause() // must be a no-op -- pausedAt should stay at the first pause's timestamp
        clock = 600_030L
        game.resume()
        clock = 600_040L

        val s = game.state.value!!
        for (i in s.solution.indices) {
            if (s.solution[i] && i != 0) game.tapCell(i)
        }

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- should reflect only real active play, not the ~10 minutes spent paused",
            recordedMillis < 1000L
        )
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        game.endMatch(GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch(dailySeed = 2L)
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    @Test
    fun `a daily seed makes the puzzle reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        assertEquals(gameA.state.value!!.solution, gameB.state.value!!.solution)
        assertEquals(gameA.state.value!!.rowClues, gameB.state.value!!.rowClues)
        assertEquals(gameA.state.value!!.colClues, gameB.state.value!!.colClues)
    }
}
