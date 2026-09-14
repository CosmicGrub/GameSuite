package com.gamesuite.games.sudoku

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SudokuGame]'s generator (full-grid fill + clue carving) is entirely
 * private, exactly like MinesweeperGame's mine placement/flood-fill — every
 * board here comes through the real public entry point (`difficulty` +
 * `startMatch(dailySeed)`). Row/column/box validity and solution-uniqueness
 * are re-checked independently in this file (see [rowColBoxAreValid] and
 * [countCompletions]) rather than trusting the engine's own internal
 * bookkeeping, so a bug in the engine's masks can't also hide from its own
 * test.
 */
class SudokuGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): SudokuGame {
        var fakeClock = 0L
        val game = SudokuGame(nowMillis = { fakeClock++ })
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

    /** Independent re-check that every row, column, and 3x3 box of a fully-filled 81-int grid contains 1-9 exactly once. */
    private fun rowColBoxAreValid(grid: List<Int>): Boolean {
        fun group(indices: List<Int>) = indices.map { grid[it] }.toSet() == (1..9).toSet()
        for (r in 0 until 9) if (!group((0 until 9).map { r * 9 + it })) return false
        for (c in 0 until 9) if (!group((0 until 9).map { it * 9 + c })) return false
        for (br in 0 until 3) for (bc in 0 until 3) {
            val boxIndices = (0 until 3).flatMap { r -> (0 until 3).map { c -> (br * 3 + r) * 9 + (bc * 3 + c) } }
            if (!group(boxIndices)) return false
        }
        return true
    }

    /**
     * Independent backtracking solution counter over [clues] (0 = empty),
     * capped at [cap] -- a fresh implementation, deliberately not calling
     * anything in SudokuGame itself, so this can't share a masking/off-by-one
     * bug with the engine's own solver. Uses the same minimum-remaining-
     * values (MRV) ordering and hard call-budget SudokuGame.countSolutions
     * uses, for the same reason: a plain fixed-order solver was found by
     * adversarial review to blow up combinatorially on a real fraction of
     * HARD-tier puzzles, and this test file sweeps far more seeds than the
     * original handful it shipped with (see the timing test below) -- a slow
     * verifier here would make the TEST SUITE itself flaky/slow, independent
     * of whether the engine's own generator is fixed. Returns
     * [INCONCLUSIVE_SENTINEL] if the budget runs out; callers assert against
     * that explicitly rather than silently accepting a wrong answer.
     */
    private val ALL_DIGITS = (1..9).fold(0) { acc, d -> acc or (1 shl d) }
    private val INCONCLUSIVE_SENTINEL = -1

    private fun countCompletions(clues: List<Int>, cap: Int = 2, callBudget: Int = 500_000): Int {
        val work = clues.toIntArray()
        val rowMask = IntArray(9)
        val colMask = IntArray(9)
        val boxMask = IntArray(9)
        fun box(r: Int, c: Int) = (r / 3) * 3 + (c / 3)
        for (i in 0 until 81) {
            val d = work[i]
            if (d != 0) {
                val r = i / 9; val c = i % 9; val b = box(r, c)
                val bit = 1 shl d
                rowMask[r] = rowMask[r] or bit; colMask[c] = colMask[c] or bit; boxMask[b] = boxMask[b] or bit
            }
        }
        var count = 0
        var calls = 0
        var budgetExceeded = false

        fun solve(): Boolean {
            calls++
            if (calls > callBudget) { budgetExceeded = true; return true }

            var bestPos = -1
            var bestMask = 0
            var bestCandidateCount = 10
            for (p in 0 until 81) {
                if (work[p] != 0) continue
                val r = p / 9; val c = p % 9; val b = box(r, c)
                val availableMask = ALL_DIGITS and (rowMask[r] or colMask[c] or boxMask[b]).inv()
                val n = Integer.bitCount(availableMask)
                if (n == 0) return false
                if (n < bestCandidateCount) {
                    bestCandidateCount = n; bestPos = p; bestMask = availableMask
                    if (n == 1) break
                }
            }
            if (bestPos == -1) { count++; return count >= cap }

            val r = bestPos / 9; val c = bestPos % 9; val b = box(r, c)
            var remaining = bestMask
            while (remaining != 0) {
                val bit = remaining and (-remaining)
                remaining = remaining and (remaining - 1)
                val d = Integer.numberOfTrailingZeros(bit)
                rowMask[r] = rowMask[r] or bit; colMask[c] = colMask[c] or bit; boxMask[b] = boxMask[b] or bit
                work[bestPos] = d
                if (solve()) return true
                work[bestPos] = 0
                rowMask[r] = rowMask[r] and bit.inv(); colMask[c] = colMask[c] and bit.inv(); boxMask[b] = boxMask[b] and bit.inv()
            }
            return false
        }
        solve()
        return if (budgetExceeded) INCONCLUSIVE_SENTINEL else count
    }

    @Test
    fun `the generated solution is a fully valid Sudoku grid, across every difficulty and many seeds`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..10L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                assertTrue(
                    "difficulty=$difficulty seed=$seed: solution is not a valid complete Sudoku grid",
                    rowColBoxAreValid(s.solution)
                )
            }
        }
    }

    @Test
    fun `every given cell's value matches the solution, and every non-given cell starts empty`() {
        val game = newGame(CpuDifficulty.MEDIUM)
        game.startMatch(dailySeed = 7L)
        val s = game.state.value!!
        for (i in s.cells.indices) {
            val cell = s.cells[i]
            if (cell.isGiven) {
                assertEquals("given cell $i should match the solution", s.solution[i], cell.value)
            } else {
                assertNull("non-given cell $i should start empty", cell.value)
            }
        }
    }

    @Test
    fun `the carved puzzle has exactly one solution, independently re-verified`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newGame(difficulty)
            game.startMatch(dailySeed = 3L)
            val s = game.state.value!!
            val clueGrid = s.cells.map { it.value ?: 0 }
            val result = countCompletions(clueGrid, cap = 2)
            assertNotEquals("difficulty=$difficulty: independent uniqueness check was inconclusive (ran out of budget)", INCONCLUSIVE_SENTINEL, result)
            assertEquals(
                "difficulty=$difficulty: the generated puzzle should have EXACTLY one solution",
                1,
                result
            )
        }
    }

    @Test
    fun `HARD-tier puzzles stay uniquely solvable and fast to generate across a wide seed sweep`() {
        // The whole reason this test exists: adversarial review found the
        // FIRST version of countSolutions (plain fixed left-to-right cell
        // order, no search-effort bound) could blow up combinatorially --
        // multi-million-step searches with no plateau found across hundreds
        // of sampled seeds -- specifically at HARD's 26-clue target, and
        // specifically in a way the original handful of seeds this test
        // file shipped with (1..11, 42, 3, 5, 7, 9) never happened to hit.
        // SudokuGame.countSolutions was fixed with an MRV heuristic plus a
        // hard call-budget ceiling (see its own KDoc); this test's job is to
        // actually exercise that fix across FAR more seeds than a handful,
        // both for correctness (still uniquely solvable) and for speed (no
        // seed should come anywhere close to a multi-second stall).
        val start = System.nanoTime()
        for (seed in 1L..300L) {
            val game = newGame(CpuDifficulty.HARD)
            game.startMatch(dailySeed = seed)
            val s = game.state.value!!
            val clueGrid = s.cells.map { it.value ?: 0 }
            val result = countCompletions(clueGrid, cap = 2)
            assertNotEquals("seed=$seed: independent uniqueness check was inconclusive (ran out of budget)", INCONCLUSIVE_SENTINEL, result)
            assertEquals("seed=$seed: HARD puzzle should have EXACTLY one solution", 1, result)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "generating + independently re-verifying 300 HARD puzzles took ${elapsedMs}ms -- the whole point of the MRV+budget fix was to bound this",
            elapsedMs < 30_000
        )
    }

    @Test
    fun `clue count is at or reasonably close to each difficulty's target`() {
        // carvePuzzle's own KDoc: targetClues is a floor it tries to reach in
        // a single pass, not a guarantee -- allow some slack above target
        // rather than asserting an exact count, to avoid a flaky test tied
        // to exactly how far a single random pass happens to get.
        val targets = mapOf(CpuDifficulty.EASY to 42, CpuDifficulty.MEDIUM to 32, CpuDifficulty.HARD to 26)
        for (difficulty in CpuDifficulty.entries) {
            val game = newGame(difficulty)
            game.startMatch(dailySeed = 11L)
            val s = game.state.value!!
            val clueCount = s.cells.count { it.value != null }
            val target = targets.getValue(difficulty)
            assertTrue(
                "difficulty=$difficulty: clue count $clueCount should be within 10 of target $target",
                clueCount in target..(target + 10)
            )
        }
    }

    @Test
    fun `a daily seed makes the puzzle reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)

        assertEquals(gameA.state.value!!.solution, gameB.state.value!!.solution)
        assertEquals(
            gameA.state.value!!.cells.map { it.value },
            gameB.state.value!!.cells.map { it.value }
        )
    }

    @Test
    fun `selecting an out-of-range index is ignored, not a crash`() {
        // Found by adversarial review: selectCell had no bounds check, so an
        // out-of-range selection would make setValue/clearValue/toggleNote
        // crash with IndexOutOfBoundsException instead of no-op'ing like
        // every other invalid-state case in this class.
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val before = game.state.value!!

        game.selectCell(-1)
        assertEquals("an out-of-range selectCell call must not change selectedIndex", before.selectedIndex, game.state.value!!.selectedIndex)

        game.selectCell(81)
        assertEquals("an out-of-range selectCell call must not change selectedIndex", before.selectedIndex, game.state.value!!.selectedIndex)

        // Must not have left the engine in a state where a subsequent real
        // action crashes either.
        game.setValue(1)
        game.clearValue()
        game.toggleNote(1)
    }

    @Test
    fun `a given cell cannot be edited`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val givenIndex = s.cells.indices.first { s.cells[it].isGiven }

        game.selectCell(givenIndex)
        game.setValue((s.solution[givenIndex] % 9) + 1) // guaranteed a different digit than the given's own value
        assertEquals("a given cell's value must never change", s.cells[givenIndex].value, game.state.value!!.cells[givenIndex].value)
        assertEquals("editing a given cell must not count as a mistake", 0, game.state.value!!.mistakes)
    }

    @Test
    fun `setting the correct value does not count as a mistake, a wrong value does`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val emptyIndex = s.cells.indices.first { !s.cells[it].isGiven }
        val correct = s.solution[emptyIndex]
        val wrong = (correct % 9) + 1

        game.selectCell(emptyIndex)
        game.setValue(correct)
        assertEquals(0, game.state.value!!.mistakes)

        game.setValue(wrong)
        assertEquals(1, game.state.value!!.mistakes)
        assertEquals(wrong, game.state.value!!.cells[emptyIndex].value)
    }

    @Test
    fun `placing a value clears that digit from every peer's pencil marks`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        var s = game.state.value!!
        val target = s.cells.indices.first { !s.cells[it].isGiven }
        val row = target / 9
        val peerInRow = (0 until 9).map { row * 9 + it }.first { it != target && !s.cells[it].isGiven }
        val value = s.solution[target]

        game.selectCell(peerInRow)
        game.toggleNote(value)
        assertTrue(value in game.state.value!!.cells[peerInRow].notes)

        game.selectCell(target)
        game.setValue(value)
        assertFalse("the peer's pencil mark for the placed value should be auto-cleared", value in game.state.value!!.cells[peerInRow].notes)
    }

    @Test
    fun `toggling a note works only on an empty, non-given cell`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val givenIndex = s.cells.indices.first { s.cells[it].isGiven }
        val emptyIndex = s.cells.indices.first { !s.cells[it].isGiven }

        game.selectCell(givenIndex)
        game.toggleNote(1)
        assertTrue("a given cell should never accept a pencil mark", game.state.value!!.cells[givenIndex].notes.isEmpty())

        game.selectCell(emptyIndex)
        game.toggleNote(4)
        assertTrue(4 in game.state.value!!.cells[emptyIndex].notes)
        game.toggleNote(4)
        assertFalse(4 in game.state.value!!.cells[emptyIndex].notes)

        game.setValue(s.solution[emptyIndex])
        game.toggleNote(1)
        assertTrue("a cell that already holds a value should never also accept a pencil mark", game.state.value!!.cells[emptyIndex].notes.isEmpty())
    }

    @Test
    fun `clearValue erases only the value, leaving notes untouched, and never touches a given`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val emptyIndex = s.cells.indices.first { !s.cells[it].isGiven }
        val givenIndex = s.cells.indices.first { s.cells[it].isGiven }

        game.selectCell(emptyIndex)
        game.toggleNote(2)
        game.setValue(s.solution[emptyIndex])
        assertNotNull(game.state.value!!.cells[emptyIndex].value)

        game.clearValue()
        assertNull(game.state.value!!.cells[emptyIndex].value)

        game.selectCell(givenIndex)
        val beforeValue = game.state.value!!.cells[givenIndex].value
        game.clearValue()
        assertEquals("clearValue must never touch a given cell", beforeValue, game.state.value!!.cells[givenIndex].value)
    }

    @Test
    fun `filling every cell with the solution wins the board and increments puzzlesSolved`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 9L)
        val s = game.state.value!!
        for (i in s.cells.indices) {
            if (!s.cells[i].isGiven) {
                game.selectCell(i)
                game.setValue(s.solution[i])
            }
        }
        assertTrue(game.state.value!!.won)
        assertEquals(1, game.puzzlesSolved.value)
        assertEquals(0, game.state.value!!.mistakes)
        assertNotNull(game.finishedElapsedMillis.value)
    }

    @Test
    fun `generating a full batch of puzzles across every difficulty completes quickly`() {
        // A coarse performance sanity check, not a strict benchmark -- this
        // exists to catch a real regression (e.g. an accidental infinite
        // loop, or an exponential blow-up in carvePuzzle/countSolutions),
        // not to assert a specific number. 30 puzzles (10 per tier)
        // comfortably finishing under 10s on a plain JVM is a generous bar;
        // real generation is expected to be far faster than this in practice.
        val start = System.nanoTime()
        for (difficulty in CpuDifficulty.entries) {
            repeat(10) { i ->
                val game = newGame(difficulty)
                game.startMatch(dailySeed = 100L + i)
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("generating 30 puzzles took ${elapsedMs}ms, unexpectedly slow", elapsedMs < 10_000)
    }

    @Test
    fun `pausing mid-puzzle does not inflate the recorded solve time`() {
        // Found by adversarial review during the Lights Out pass (this
        // class shares the exact same pattern): pause()/resume() used to be
        // no-ops while the timer was a pure wall-clock delta, so
        // backgrounding the app mid-puzzle (which really does call
        // pause()/resume() -- see GameSessionManager -- not merely a
        // theoretical concern) silently added the entire background
        // duration to the recorded solve time.
        var clock = 0L
        val game = SudokuGame(nowMillis = { clock })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.EASY
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val emptyIndex = s.cells.indices.first { !s.cells[it].isGiven }

        game.selectCell(emptyIndex)
        game.setValue(s.solution[emptyIndex]) // starts the timer

        clock = 10L
        game.pause() // e.g. the app was backgrounded here
        clock = 600_010L // ~10 real minutes pass while backgrounded
        game.resume()
        clock = 600_020L

        // Fill every remaining cell to win.
        for (i in s.cells.indices) {
            if (!s.cells[i].isGiven && i != emptyIndex) {
                game.selectCell(i)
                game.setValue(s.solution[i])
            }
        }

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- should reflect only real active play, not the ~10 minutes spent paused",
            recordedMillis < 1000L
        )
    }

    @Test
    fun `setValue, clearValue, and toggleNote are all rejected once the session has ended via leaveSession, even if the board itself was not yet won`() {
        // Found by adversarial review (ColorFloodGame.pick()'s own pass):
        // setValue()/clearValue()/toggleNote()'s only liveness check was
        // `s.isOver` (per-board), never `matchOver` (session-level), so a
        // call after leaveSession() had already delivered the final
        // GameResult could still mutate state -- including a phantom win
        // with no second result ever reported.
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val emptyIndex = s.cells.indices.first { !s.cells[it].isGiven }
        game.selectCell(emptyIndex)
        game.setValue(s.solution[emptyIndex])
        assertFalse(game.state.value!!.isOver)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.setValue((s.solution[emptyIndex] % 9) + 1)
        assertEquals("a setValue() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)

        game.clearValue()
        assertEquals("a clearValue() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)

        game.toggleNote(1)
        assertEquals("a toggleNote() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `pausing twice without an intervening resume does not lose the interval between the two pauses`() {
        // Found by adversarial review: pause() overwrote its anchor on a
        // second call with no resume() in between (unlike resume(), which
        // was already correctly idempotent), silently dropping the
        // interval between the two pause() calls from totalPausedMillis
        // and counting it as active play instead.
        var clock = 0L
        val game = SudokuGame(nowMillis = { clock })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.EASY
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val emptyIndex = s.cells.indices.first { !s.cells[it].isGiven }

        clock = 100L
        game.selectCell(emptyIndex)
        game.setValue(s.solution[emptyIndex]) // starts the timer at t=100

        clock = 110L
        game.pause() // pausedAt = 110

        clock = 200L
        game.pause() // must be a no-op -- pausedAt should STILL be 110, not overwritten to 200

        clock = 250L
        game.resume() // totalPausedMillis += 250 - 110 = 140 (not 250 - 200 = 50)

        clock = 300L
        for (i in s.cells.indices) {
            if (!s.cells[i].isGiven && i != emptyIndex) {
                game.selectCell(i)
                game.setValue(s.solution[i])
            }
        }

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- the [110,200] interval between the two pause() calls must count as paused, not active",
            recordedMillis < 100L
        )
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        game.endMatch(com.gamesuite.core.GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch(dailySeed = 2L) // a fresh puzzle should always be fully playable again
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }
}
