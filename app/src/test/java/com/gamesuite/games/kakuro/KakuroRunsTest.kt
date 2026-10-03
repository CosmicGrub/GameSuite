package com.gamesuite.games.kakuro

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
 * [KakuroRuns] is derived from the visible cells and clues alone, so every check here compares it
 * against the one thing the engine guarantees independently: each run's solution digits sum to the
 * clue printed on its clue cell with no repeat. Boards come through the real public entry point
 * (`difficulty` + `startMatch(dailySeed)`), and entries go in through the real `setValue`.
 */
class KakuroRunsTest {

    private fun newGame(tier: CpuDifficulty): KakuroGame {
        val game = KakuroGame(nowMillis = { 0L })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = tier
        return game
    }

    private fun enter(game: KakuroGame, index: Int, digit: Int) {
        game.selectCell(index)
        game.setValue(digit)
    }

    @Test
    fun `allRuns finds every run exactly once, each with its clue cell, matching clue, and a distinct digit sum in the solution`() {
        for (tier in CpuDifficulty.entries) {
            for (seed in 1L..4L) {
                val game = newGame(tier)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                val runs = KakuroRuns.allRuns(s)

                val clueCount = s.cells.count { it.acrossClue != null } + s.cells.count { it.downClue != null }
                assertEquals("tier=$tier seed=$seed: one run per printed clue", clueCount, runs.size)
                assertEquals("tier=$tier seed=$seed: no run is reported twice", runs.size, runs.map { it.clueCellIndex to it.horizontal }.toSet().size)

                for (run in runs) {
                    val step = if (run.horizontal) 1 else s.cols
                    val first = run.cells.first()
                    val last = run.cells.last()
                    assertTrue("run has at least 2 cells", run.cells.size >= 2)
                    assertTrue("run cells are consecutive", run.cells.zipWithNext().all { (a, b) -> b - a == step })
                    assertTrue("every run cell is white", run.cells.all { s.cells[it].type == KakuroCellType.WHITE })
                    assertEquals("clue cell sits just before the run", first - step, run.clueCellIndex)
                    assertEquals(KakuroCellType.BLACK, s.cells[run.clueCellIndex].type)
                    val atEnd = if (run.horizontal) last % s.cols == s.cols - 1 else last + s.cols >= s.cells.size
                    assertTrue("run is maximal (ends at the edge or a black cell)", atEnd || s.cells[last + step].type == KakuroCellType.BLACK)

                    val printed = if (run.horizontal) s.cells[run.clueCellIndex].acrossClue else s.cells[run.clueCellIndex].downClue
                    assertEquals("target is the printed clue", printed, run.target)
                    val digits = run.cells.map { s.solution[it]!! }
                    assertEquals("tier=$tier seed=$seed: solution digits sum to the clue", run.target, digits.sum())
                    assertEquals("solution digits are distinct", digits.size, digits.toSet().size)
                }
            }
        }
    }

    @Test
    fun `runThrough agrees with allRuns for every white cell in both directions, and every white cell is on at least one run`() {
        for (tier in CpuDifficulty.entries) {
            val game = newGame(tier)
            game.startMatch(dailySeed = 3L)
            val s = game.state.value!!
            val runs = KakuroRuns.allRuns(s)
            for (index in s.cells.indices) {
                if (s.cells[index].type != KakuroCellType.WHITE) continue
                val across = KakuroRuns.runThrough(s, index, horizontal = true)
                val down = KakuroRuns.runThrough(s, index, horizontal = false)
                assertEquals("tier=$tier index=$index across", runs.firstOrNull { it.horizontal && index in it.cells }, across)
                assertEquals("tier=$tier index=$index down", runs.firstOrNull { !it.horizontal && index in it.cells }, down)
                assertTrue("tier=$tier index=$index is on no run at all", across != null || down != null)
            }
        }
    }

    @Test
    fun `a black cell or an out-of-range index has no run`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val s = game.state.value!!
        val black = s.cells.indices.first { s.cells[it].type == KakuroCellType.BLACK }
        assertNull(KakuroRuns.runThrough(s, black, horizontal = true))
        assertNull(KakuroRuns.runThrough(s, black, horizontal = false))
        assertNull(KakuroRuns.runThrough(s, -1, horizontal = true))
        assertNull(KakuroRuns.runThrough(s, s.cells.size, horizontal = false))
    }

    @Test
    fun `clueKey is unique per clue cell and direction`() {
        val keys = HashSet<Int>()
        for (index in 0 until 200) {
            assertTrue(keys.add(KakuroRuns.clueKey(index, horizontal = true)))
            assertTrue(keys.add(KakuroRuns.clueKey(index, horizontal = false)))
        }
        assertNotEquals(KakuroRuns.clueKey(5, true), KakuroRuns.clueKey(5, false))
    }

    @Test
    fun `progress tracks entered sum and filled count, flags a repeat, and is complete only for a full correct repeat-free run`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val start = game.state.value!!
        val run = KakuroRuns.allRuns(start).first()
        val cells = run.cells
        val digits = cells.map { start.solution[it]!! }

        val empty = KakuroRuns.progress(start, run)
        assertEquals(KakuroRunProgress(entered = 0, filled = 0, size = cells.size, hasRepeat = false, complete = false), empty)

        // Everything but the last cell: partially filled, not complete.
        for (k in 0 until cells.size - 1) enter(game, cells[k], digits[k])
        val partial = KakuroRuns.progress(game.state.value!!, run)
        assertEquals(digits.dropLast(1).sum(), partial.entered)
        assertEquals(cells.size - 1, partial.filled)
        assertFalse(partial.complete)

        enter(game, cells.last(), digits.last())
        val full = KakuroRuns.progress(game.state.value!!, run)
        assertEquals(run.target, full.entered)
        assertEquals(cells.size, full.filled)
        assertFalse(full.hasRepeat)
        assertTrue("every solution digit in: the run is complete", full.complete)

        // The first cell's digit again in the last cell repeats, even though every cell is "filled".
        enter(game, cells.last(), digits.first())
        val repeated = KakuroRuns.progress(game.state.value!!, run)
        assertTrue(repeated.hasRepeat)
        assertFalse(repeated.complete)

        // A different last digit changes the sum, so it is not complete either.
        val wrong = (digits.last() % 9) + 1
        enter(game, cells.last(), wrong)
        val off = KakuroRuns.progress(game.state.value!!, run)
        assertFalse("digit $wrong instead of ${digits.last()} cannot complete the run", off.complete)
    }

    @Test
    fun `completedClueKeys lists exactly the runs that are full and correct`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val start = game.state.value!!
        assertTrue("nothing is complete on an empty board", KakuroRuns.completedClueKeys(start).isEmpty())

        val run = KakuroRuns.allRuns(start).first()
        for (cell in run.cells) enter(game, cell, start.solution[cell]!!)
        val keys = KakuroRuns.completedClueKeys(game.state.value!!)
        assertTrue(KakuroRuns.clueKey(run.clueCellIndex, run.horizontal) in keys)

        // Breaking one digit un-completes it again.
        val victim = run.cells.first()
        enter(game, victim, (start.solution[victim]!! % 9) + 1)
        assertFalse(KakuroRuns.clueKey(run.clueCellIndex, run.horizontal) in KakuroRuns.completedClueKeys(game.state.value!!))
    }

    @Test
    fun `completing the whole board completes every clue`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 9L)
        val s = game.state.value!!
        for (i in s.cells.indices) {
            if (s.cells[i].type == KakuroCellType.WHITE) enter(game, i, s.solution[i]!!)
        }
        val solved = game.state.value!!
        assertTrue(solved.won)
        val expected = KakuroRuns.allRuns(solved).map { KakuroRuns.clueKey(it.clueCellIndex, it.horizontal) }.toSet()
        assertEquals(expected, KakuroRuns.completedClueKeys(solved))
        assertNotNull(expected.firstOrNull())
    }

    @Test
    fun `usedDigits collects the digits in the given runs but never the excluded cell's own`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val start = game.state.value!!
        val run = KakuroRuns.allRuns(start).first()
        val a = run.cells[0]
        val b = run.cells[1]
        val da = start.solution[a]!!
        val db = start.solution[b]!!

        assertTrue(KakuroRuns.usedDigits(start, listOf(run), exceptIndex = a).isEmpty())

        enter(game, a, da)
        enter(game, b, db)
        val s = game.state.value!!
        assertEquals("seen from cell a, only b's digit counts", setOf(db), KakuroRuns.usedDigits(s, listOf(run), exceptIndex = a))
        assertEquals("seen from cell b, only a's digit counts", setOf(da), KakuroRuns.usedDigits(s, listOf(run), exceptIndex = b))
        assertEquals("with nothing excluded both count", setOf(da, db), KakuroRuns.usedDigits(s, listOf(run), exceptIndex = -1))
    }

    // -- Hand-built boards: every expected number below is worked out by hand, not read back from the engine. --

    /**
     * 17 x 10 "ladder": row 0 and every even row are black, and each odd row r holds one across run of
     * length (r + 3) / 2 (2, 3, ... 9 cells) in columns 1..length, with its clue (the 1..length sum,
     * i.e. 3, 6, 10, ... 45) on the black cell in column 0. A black row sits above and below every
     * white cell, so no cell has a down run.
     */
    private fun ladderState(values: Map<Int, Int> = emptyMap()): KakuroState {
        val cols = 10
        val rows = 17
        val cells = ArrayList<KakuroCell>()
        for (r in 0 until rows) {
            val runLength = if (r % 2 == 1) (r + 3) / 2 else 0
            for (c in 0 until cols) {
                val index = r * cols + c
                cells.add(
                    when {
                        runLength > 0 && c in 1..runLength -> KakuroCell(KakuroCellType.WHITE, value = values[index])
                        runLength > 0 && c == 0 -> KakuroCell(KakuroCellType.BLACK, acrossClue = runLength * (runLength + 1) / 2)
                        else -> KakuroCell(KakuroCellType.BLACK)
                    }
                )
            }
        }
        return KakuroState(rows = rows, cols = cols, cells = cells, solution = cells.map { null })
    }

    /**
     * 4 x 4 with one white 2 x 2 block (cells 5, 6, 9, 10): across clues 3 (clue cell 4) and 7 (cell 8),
     * down clues 4 (cell 1) and 6 (cell 2). Its solution would be 1 2 / 3 4.
     */
    private fun crossState(values: Map<Int, Int> = emptyMap()): KakuroState {
        val white = setOf(5, 6, 9, 10)
        val cells = (0 until 16).map { i ->
            when (i) {
                1 -> KakuroCell(KakuroCellType.BLACK, downClue = 4)
                2 -> KakuroCell(KakuroCellType.BLACK, downClue = 6)
                4 -> KakuroCell(KakuroCellType.BLACK, acrossClue = 3)
                8 -> KakuroCell(KakuroCellType.BLACK, acrossClue = 7)
                in white -> KakuroCell(KakuroCellType.WHITE, value = values[i])
                else -> KakuroCell(KakuroCellType.BLACK)
            }
        }
        return KakuroState(rows = 4, cols = 4, cells = cells, solution = cells.map { null })
    }

    @Test
    fun `hand-built ladder - runs of every length 2 to 9 have the right cells, clue cell, target and an empty progress`() {
        val empty = ladderState()
        val runs = KakuroRuns.allRuns(empty)
        assertEquals((2..9).toList(), runs.map { it.cells.size })
        for (run in runs) {
            val length = run.cells.size
            val row = run.cells.first() / 10
            assertTrue(run.horizontal)
            assertEquals("the clue cell is column 0 of the run's own row", row * 10, run.clueCellIndex)
            assertEquals("target is the clue printed on that cell", length * (length + 1) / 2, run.target)
            assertEquals((1..length).map { row * 10 + it }, run.cells)
            assertEquals(
                "length $length, nothing entered yet: 0 so far, the whole target to go",
                KakuroRunProgress(entered = 0, filled = 0, size = length, hasRepeat = false, complete = false),
                KakuroRuns.progress(empty, run)
            )
            for (cell in run.cells) {
                assertEquals(run, KakuroRuns.runThrough(empty, cell, horizontal = true))
                assertNull("a black row sits above and below, so there is no down run", KakuroRuns.runThrough(empty, cell, horizontal = false))
            }
        }
    }

    @Test
    fun `hand-built ladder - progress for runs of length 2 to 9 with part, all and a repeated digit entered`() {
        for (run in KakuroRuns.allRuns(ladderState())) {
            val length = run.cells.size
            val target = length * (length + 1) / 2

            // 1, 2, ... length in order: sums to the target with no repeat.
            val inOrder = ladderState(run.cells.mapIndexed { k, cell -> cell to (k + 1) }.toMap())
            assertEquals(
                "length $length, 1..$length entered",
                KakuroRunProgress(entered = target, filled = length, size = length, hasRepeat = false, complete = true),
                KakuroRuns.progress(inOrder, run)
            )
            assertEquals(setOf(KakuroRuns.clueKey(run.clueCellIndex, true)), KakuroRuns.completedClueKeys(inOrder))

            // Everything but the last cell: the digit still to go is exactly `length`.
            val allButLast = ladderState(run.cells.dropLast(1).mapIndexed { k, cell -> cell to (k + 1) }.toMap())
            val partial = KakuroRuns.progress(allButLast, run)
            assertEquals(length * (length - 1) / 2, partial.entered)
            assertEquals(length - 1, partial.filled)
            assertEquals("the one digit still to go is $length", length, run.target - partial.entered)
            assertFalse(partial.complete)
            assertTrue(KakuroRuns.completedClueKeys(allButLast).isEmpty())

            // Every cell a 1: `length` in total and a repeat, so never complete.
            val repeated = KakuroRuns.progress(ladderState(run.cells.associateWith { 1 }), run)
            assertEquals(length, repeated.entered)
            assertEquals(length, repeated.filled)
            assertTrue(repeated.hasRepeat)
            assertFalse(repeated.complete)
        }
    }

    @Test
    fun `hand-built cross - both runs of a cell, their clue cells and targets, and the digits used in them`() {
        val empty = crossState()
        assertEquals(
            KakuroRunInfo(cells = listOf(5, 6), clueCellIndex = 4, horizontal = true, target = 3),
            KakuroRuns.runThrough(empty, 5, horizontal = true)
        )
        assertEquals(
            KakuroRunInfo(cells = listOf(5, 9), clueCellIndex = 1, horizontal = false, target = 4),
            KakuroRuns.runThrough(empty, 5, horizontal = false)
        )
        assertEquals(
            KakuroRunInfo(cells = listOf(9, 10), clueCellIndex = 8, horizontal = true, target = 7),
            KakuroRuns.runThrough(empty, 10, horizontal = true)
        )
        assertEquals(
            KakuroRunInfo(cells = listOf(6, 10), clueCellIndex = 2, horizontal = false, target = 6),
            KakuroRuns.runThrough(empty, 10, horizontal = false)
        )
        assertEquals(4, KakuroRuns.allRuns(empty).size)

        // Cell 5 holds 1, cell 6 holds 2, cell 9 holds 3, cell 10 is still empty.
        val s = crossState(mapOf(5 to 1, 6 to 2, 9 to 3))
        fun runsThrough(index: Int) = listOfNotNull(
            KakuroRuns.runThrough(s, index, horizontal = true),
            KakuroRuns.runThrough(s, index, horizontal = false)
        )
        assertEquals("from cell 5: its own 1 is left out, the across partner 2 and the down partner 3 count", setOf(2, 3), KakuroRuns.usedDigits(s, runsThrough(5), exceptIndex = 5))
        assertEquals("from cell 6: across partner is 1, down partner (cell 10) is empty", setOf(1), KakuroRuns.usedDigits(s, runsThrough(6), exceptIndex = 6))
        assertEquals("from the empty cell 10: down partner 2, across partner 3", setOf(2, 3), KakuroRuns.usedDigits(s, runsThrough(10), exceptIndex = 10))

        // "so far / to go" for the across run 5-6 (target 3): the 1 and the 2 make 3 so far, 0 to go.
        val across = KakuroRuns.runThrough(s, 5, horizontal = true)!!
        val progress = KakuroRuns.progress(s, across)
        assertEquals(1 + 2, progress.entered)
        assertEquals(2, progress.filled)
        assertEquals("1 + 2 already reaches the target 3: nothing more to go", 0, across.target - progress.entered)
        assertTrue("1 and 2 fill the run and sum to its clue 3", progress.complete)
        // The down run 5-9 (target 4): 1 + 3 = 4, complete as well.
        val down = KakuroRuns.runThrough(s, 5, horizontal = false)!!
        assertTrue(KakuroRuns.progress(s, down).complete)
        // The down run 6-10 (target 6): only the 2 so far, 4 to go.
        val rightDown = KakuroRuns.runThrough(s, 6, horizontal = false)!!
        val rightProgress = KakuroRuns.progress(s, rightDown)
        assertEquals(2, rightProgress.entered)
        assertEquals(1, rightProgress.filled)
        assertEquals(4, rightDown.target - rightProgress.entered)
    }
}
