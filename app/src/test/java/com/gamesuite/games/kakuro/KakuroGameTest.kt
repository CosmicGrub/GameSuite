package com.gamesuite.games.kakuro

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KakuroGame]'s generator (template pick + digit-fill backtracking +
 * sum-pruned uniqueness solver) is entirely private -- every puzzle here
 * comes through the real public entry point (`difficulty` +
 * `startMatch(dailySeed)`). Both the hand-authored [KakuroTemplates] AND the
 * generated puzzles' clue derivation / uniqueness are re-checked in this
 * file via completely independent, freshly-written implementations (see
 * [independentRuns] and [independentCountSolutions]) rather than trusting
 * the engine's own bookkeeping or its own private solver -- a bug shared
 * between the engine's solver and this file's own would have to be an
 * identical mistake made twice, independently. Mirrors SudokuGameTest's/
 * NonogramGameTest's own "don't let the engine check itself" precedent.
 */
class KakuroGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): KakuroGame {
        var fakeClock = 0L
        val game = KakuroGame(nowMillis = { fakeClock++ })
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

    // -- Independent run computation, deliberately not calling anything in KakuroGame. --

    private data class IndependentRun(val cells: List<Int>, val horizontal: Boolean)

    private fun independentRuns(rows: Int, cols: Int, layout: List<KakuroCellType>): List<IndependentRun> {
        val runs = mutableListOf<IndependentRun>()
        for (r in 0 until rows) {
            var c = 0
            while (c < cols) {
                if (layout[r * cols + c] == KakuroCellType.WHITE) {
                    val start = c
                    while (c < cols && layout[r * cols + c] == KakuroCellType.WHITE) c++
                    if (c - start >= 2) runs += IndependentRun((start until c).map { r * cols + it }, horizontal = true)
                } else {
                    c++
                }
            }
        }
        for (c in 0 until cols) {
            var r = 0
            while (r < rows) {
                if (layout[r * cols + c] == KakuroCellType.WHITE) {
                    val start = r
                    while (r < rows && layout[r * cols + c] == KakuroCellType.WHITE) r++
                    if (r - start >= 2) runs += IndependentRun((start until r).map { it * cols + c }, horizontal = false)
                } else {
                    r++
                }
            }
        }
        return runs
    }

    // -- Template authoring validation: BEFORE these templates are trusted, verify docs/KAKURO_DESIGN.md's two hard authoring constraints hold. --

    @Test
    fun `every hand-authored template's row 0 and column 0 are entirely black`() {
        for (tier in CpuDifficulty.entries) {
            for ((i, template) in KakuroTemplates.forTier(tier).withIndex()) {
                for (c in 0 until template.cols) {
                    assertEquals("tier=$tier template=$i row0 col=$c should be BLACK", KakuroCellType.BLACK, template.layout[c])
                }
                for (r in 0 until template.rows) {
                    assertEquals("tier=$tier template=$i col0 row=$r should be BLACK", KakuroCellType.BLACK, template.layout[r * template.cols])
                }
            }
        }
    }

    @Test
    fun `every hand-authored template -- every white cell belongs to a run of length at least 2 in some direction, and no run exceeds length 9`() {
        for (tier in CpuDifficulty.entries) {
            for ((i, template) in KakuroTemplates.forTier(tier).withIndex()) {
                val runs = independentRuns(template.rows, template.cols, template.layout)
                val covered = BooleanArray(template.rows * template.cols)
                for (run in runs) {
                    assertTrue("tier=$tier template=$i: a run of length ${run.cells.size} exceeds 9", run.cells.size <= 9)
                    for (cell in run.cells) covered[cell] = true
                }
                for (idx in template.layout.indices) {
                    if (template.layout[idx] == KakuroCellType.WHITE) {
                        assertTrue(
                            "tier=$tier template=$i: white cell $idx is not part of any run of length >= 2 in either direction",
                            covered[idx]
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `each tier has 3 hand-authored templates, all sharing that tier's own bounding box`() {
        val expectedSize = mapOf(CpuDifficulty.EASY to 6, CpuDifficulty.MEDIUM to 9, CpuDifficulty.HARD to 12)
        for (tier in CpuDifficulty.entries) {
            val templates = KakuroTemplates.forTier(tier)
            assertEquals("tier=$tier should have 3-4 templates", 3, templates.size)
            for ((i, template) in templates.withIndex()) {
                assertEquals("tier=$tier template=$i rows", expectedSize.getValue(tier), template.rows)
                assertEquals("tier=$tier template=$i cols", expectedSize.getValue(tier), template.cols)
            }
        }
    }

    // -- Independent uniqueness verifier: a fresh backtracking sum+distinct-digit solver, NOT calling into KakuroGame's own private solver. --

    private fun independentCountSolutions(
        rows: Int,
        cols: Int,
        layout: List<KakuroCellType>,
        acrossClues: Map<Int, Int>,
        downClues: Map<Int, Int>,
        cap: Int = 2,
        callBudget: Int = 2_000_000
    ): Int {
        val runs = independentRuns(rows, cols, layout)
        val runTargets = runs.map { run ->
            val first = run.cells.first()
            if (run.horizontal) acrossClues.getValue(first - 1) else downClues.getValue(first - cols)
        }
        val cellRuns = Array(rows * cols) { mutableListOf<Int>() }
        runs.forEachIndexed { i, run -> for (cell in run.cells) cellRuns[cell] += i }
        val whiteCells = (0 until rows * cols).filter { layout[it] == KakuroCellType.WHITE }

        val grid = IntArray(rows * cols)
        val runSum = IntArray(runs.size)
        val runCount = IntArray(runs.size)
        val runMask = IntArray(runs.size)
        var calls = 0
        var count = 0
        var budgetExceeded = false

        fun isLegal(cell: Int, d: Int): Boolean {
            val bit = 1 shl d
            for (rid in cellRuns[cell]) {
                if (runMask[rid] and bit != 0) return false
                val newCount = runCount[rid] + 1
                val newSum = runSum[rid] + d
                val remaining = runs[rid].cells.size - newCount
                val target = runTargets[rid]
                if (remaining == 0) {
                    if (newSum != target) return false
                } else {
                    val minRem = remaining * (remaining + 1) / 2
                    val maxRem = remaining * (19 - remaining) / 2
                    if (newSum + minRem > target || newSum + maxRem < target) return false
                }
            }
            return true
        }

        fun solve(pos: Int): Boolean {
            calls++
            if (calls > callBudget) {
                budgetExceeded = true
                return true
            }
            if (pos == whiteCells.size) {
                count++
                return count >= cap
            }
            val cell = whiteCells[pos]
            for (d in 1..9) {
                if (!isLegal(cell, d)) continue
                val bit = 1 shl d
                for (rid in cellRuns[cell]) {
                    runMask[rid] = runMask[rid] or bit
                    runSum[rid] += d
                    runCount[rid] += 1
                }
                grid[cell] = d
                if (solve(pos + 1)) return true
                grid[cell] = 0
                for (rid in cellRuns[cell]) {
                    runMask[rid] = runMask[rid] and bit.inv()
                    runSum[rid] -= d
                    runCount[rid] -= 1
                }
            }
            return false
        }

        solve(0)
        return if (budgetExceeded) -1 else count
    }

    @Test
    fun `every generated puzzle is genuinely uniquely solvable, independently re-verified`() {
        for (tier in CpuDifficulty.entries) {
            for (seed in 1L..8L) {
                val game = newGame(tier)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                val acrossClues = HashMap<Int, Int>()
                val downClues = HashMap<Int, Int>()
                for (i in s.cells.indices) {
                    s.cells[i].acrossClue?.let { acrossClues[i] = it }
                    s.cells[i].downClue?.let { downClues[i] = it }
                }
                val layout = s.cells.map { it.type }
                val result = independentCountSolutions(s.rows, s.cols, layout, acrossClues, downClues)
                assertNotEquals("tier=$tier seed=$seed: independent uniqueness check was inconclusive (ran out of budget)", -1, result)
                assertEquals("tier=$tier seed=$seed: puzzle should have EXACTLY one solution", 1, result)
            }
        }
    }

    @Test
    fun `every run's clue equals the independently-summed, independently distinct digits of its own solution`() {
        for (tier in CpuDifficulty.entries) {
            for (seed in 1L..5L) {
                val game = newGame(tier)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                val layout = s.cells.map { it.type }
                val runs = independentRuns(s.rows, s.cols, layout)
                assertTrue("tier=$tier seed=$seed: puzzle has no runs at all", runs.isNotEmpty())
                for (run in runs) {
                    val digits = run.cells.map { s.solution[it]!! }
                    val independentSum = digits.sum()
                    val first = run.cells.first()
                    val clueCell = if (run.horizontal) first - 1 else first - s.cols
                    val actualClue = if (run.horizontal) s.cells[clueCell].acrossClue else s.cells[clueCell].downClue
                    assertEquals("tier=$tier seed=$seed run=$run", independentSum, actualClue)
                    assertEquals("tier=$tier seed=$seed run=$run should have no repeated digit", digits.size, digits.toSet().size)
                }
            }
        }
    }

    @Test
    fun `every white cell's solution digit is 1-9 and starts empty, every black cell's solution is null`() {
        for (tier in CpuDifficulty.entries) {
            val game = newGame(tier)
            game.startMatch(dailySeed = 3L)
            val s = game.state.value!!
            for (i in s.cells.indices) {
                if (s.cells[i].type == KakuroCellType.BLACK) {
                    assertNull("tier=$tier index=$i", s.solution[i])
                } else {
                    assertTrue("tier=$tier index=$i solution should be 1-9", s.solution[i] in 1..9)
                    assertNull("tier=$tier index=$i should start with no value", s.cells[i].value)
                    assertTrue("tier=$tier index=$i should start with no notes", s.cells[i].notes.isEmpty())
                }
            }
        }
    }

    @Test
    fun `generating puzzles across every difficulty completes quickly, across many seeds`() {
        // A coarse performance sanity check, not a strict benchmark -- the single
        // most important test in this file given docs/KAKURO_DESIGN.md's own
        // explicit warning that this generator carries "real algorithmic risk...
        // comparable to Sudoku's/Nonogram's own." Exists to catch a real
        // regression (e.g. the callBudget/MAX_GENERATION_ATTEMPTS safety nets
        // failing to bound worst-case work), not to assert a specific number.
        val start = System.nanoTime()
        for (tier in CpuDifficulty.entries) {
            repeat(15) { i ->
                val game = newGame(tier)
                game.startMatch(dailySeed = 1000L + i)
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("generating 45 puzzles (15 per tier, including HARD's 12x12) took ${elapsedMs}ms, unexpectedly slow", elapsedMs < 30_000)
    }

    // -- Gameplay tests --

    @Test
    fun `difficulty controls the board's bounding box per tier`() {
        val expected = mapOf(CpuDifficulty.EASY to 6, CpuDifficulty.MEDIUM to 9, CpuDifficulty.HARD to 12)
        for ((tier, size) in expected) {
            val game = newGame(tier)
            game.startMatch(dailySeed = 1L)
            val s = game.state.value!!
            assertEquals("tier=$tier", size, s.rows)
            assertEquals("tier=$tier", size, s.cols)
            assertEquals(size * size, s.cells.size)
            assertEquals(size * size, s.solution.size)
        }
    }

    @Test
    fun `row 0 and column 0 of every generated puzzle are entirely black`() {
        for (tier in CpuDifficulty.entries) {
            val game = newGame(tier)
            game.startMatch(dailySeed = 1L)
            val s = game.state.value!!
            for (c in 0 until s.cols) assertEquals(KakuroCellType.BLACK, s.cells[c].type)
            for (r in 0 until s.rows) assertEquals(KakuroCellType.BLACK, s.cells[r * s.cols].type)
        }
    }

    @Test
    fun `selecting a black cell is a no-op`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val s = game.state.value!!
        val blackIndex = s.cells.indices.first { s.cells[it].type == KakuroCellType.BLACK }
        game.selectCell(blackIndex)
        assertNull(game.state.value!!.selectedIndex)
    }

    @Test
    fun `selecting an out-of-range index is ignored, not a crash`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        game.selectCell(-1)
        assertNull(game.state.value!!.selectedIndex)
        game.selectCell(game.state.value!!.cells.size)
        assertNull(game.state.value!!.selectedIndex)
        // Must not have left the engine in a state where a subsequent real action crashes.
        game.setValue(1)
        game.clearValue()
        game.toggleNote(1)
    }

    @Test
    fun `setting the correct value does not count as a mistake, a wrong value does`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val whiteIndex = s.cells.indices.first { s.cells[it].type == KakuroCellType.WHITE }
        val correct = s.solution[whiteIndex]!!
        val wrong = (correct % 9) + 1

        game.selectCell(whiteIndex)
        game.setValue(correct)
        assertEquals(0, game.state.value!!.mistakes)

        game.setValue(wrong)
        assertEquals(1, game.state.value!!.mistakes)
        assertEquals(wrong, game.state.value!!.cells[whiteIndex].value)
    }

    @Test
    fun `toggling a note works only on an empty white cell`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val blackIndex = s.cells.indices.first { s.cells[it].type == KakuroCellType.BLACK }
        val whiteIndex = s.cells.indices.first { s.cells[it].type == KakuroCellType.WHITE }

        game.selectCell(blackIndex) // no-op selection (black), so nothing should be selected
        game.toggleNote(1)
        assertNull(game.state.value!!.selectedIndex)

        game.selectCell(whiteIndex)
        game.toggleNote(4)
        assertTrue(4 in game.state.value!!.cells[whiteIndex].notes)
        game.toggleNote(4)
        assertFalse(4 in game.state.value!!.cells[whiteIndex].notes)

        game.setValue(s.solution[whiteIndex]!!)
        game.toggleNote(1)
        assertTrue("a cell that already holds a value should never also accept a pencil mark", game.state.value!!.cells[whiteIndex].notes.isEmpty())
    }

    @Test
    fun `clearValue erases only the value, leaving notes untouched, and never touches a black cell`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val whiteIndex = s.cells.indices.first { s.cells[it].type == KakuroCellType.WHITE }
        val blackIndex = s.cells.indices.first { s.cells[it].type == KakuroCellType.BLACK }

        game.selectCell(whiteIndex)
        game.toggleNote(2)
        game.setValue(s.solution[whiteIndex]!!)
        assertNotNull(game.state.value!!.cells[whiteIndex].value)

        game.clearValue()
        assertNull(game.state.value!!.cells[whiteIndex].value)

        // Black cell was never selectable, so clearValue has nothing to touch there; its clue fields must be untouched throughout.
        val blackBefore = game.state.value!!.cells[blackIndex]
        game.clearValue()
        assertEquals(blackBefore, game.state.value!!.cells[blackIndex])
    }

    @Test
    fun `filling every white cell with the solution wins the board and increments puzzlesSolved`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 9L)
        val s = game.state.value!!
        for (i in s.cells.indices) {
            if (s.cells[i].type == KakuroCellType.WHITE) {
                game.selectCell(i)
                game.setValue(s.solution[i]!!)
            }
        }
        assertTrue(game.state.value!!.won)
        assertEquals(1, game.puzzlesSolved.value)
        assertEquals(0, game.state.value!!.mistakes)
        assertNotNull(game.finishedElapsedMillis.value)
    }

    @Test
    fun `a daily seed makes the puzzle reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        assertEquals(gameA.state.value!!.solution, gameB.state.value!!.solution)
        assertEquals(gameA.state.value!!.cells, gameB.state.value!!.cells)
    }

    @Test
    fun `pausing mid-puzzle does not inflate the recorded solve time`() {
        var clock = 0L
        val game = KakuroGame(nowMillis = { clock })
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
        val whiteIndices = s.cells.indices.filter { s.cells[it].type == KakuroCellType.WHITE }

        clock = 10L
        game.selectCell(whiteIndices[0])
        game.setValue(s.solution[whiteIndices[0]]!!) // starts the timer

        clock = 20L
        game.pause()
        clock = 600_020L // ~10 real minutes pass while backgrounded
        game.pause() // must be a no-op -- pausedAt should stay at the first pause's timestamp
        clock = 600_030L
        game.resume()
        clock = 600_040L

        for (i in whiteIndices.drop(1)) {
            game.selectCell(i)
            game.setValue(s.solution[i]!!)
        }

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- should reflect only real active play, not the ~10 minutes spent paused",
            recordedMillis < 1000L
        )
    }

    @Test
    fun `setValue, clearValue, and toggleNote are all rejected once the session has ended via leaveSession, even if the board itself was not yet won`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val whiteIndex = s.cells.indices.first { s.cells[it].type == KakuroCellType.WHITE }
        game.selectCell(whiteIndex)
        game.setValue(s.solution[whiteIndex]!!)
        assertFalse(game.state.value!!.isOver)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.setValue((s.solution[whiteIndex]!! % 9) + 1)
        assertEquals("a setValue() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)

        game.clearValue()
        assertEquals("a clearValue() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)

        game.toggleNote(1)
        assertEquals("a toggleNote() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
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
}
