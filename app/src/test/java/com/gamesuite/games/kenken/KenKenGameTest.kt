package com.gamesuite.games.kenken

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
 * [KenKenGame]'s generator (Latin-square fill + cage-template pick +
 * operator derivation) and its uniqueness solver are both private — every
 * puzzle here comes through the real public entry point (`difficulty` +
 * `startMatch(dailySeed)`). Both the cage-arithmetic checking and the
 * uniqueness verification are re-checked in this file via completely
 * independent, freshly-written implementations ([independentCageSatisfied],
 * [independentCountSolutions]) rather than trusting the engine's own
 * bookkeeping — same "don't let the solver check itself" precedent
 * SudokuGameTest/NonogramGameTest already established. [KENKEN_CAGE_TEMPLATES]
 * itself (the hand-authored cage shapes — see KenKenGame's own KDoc, SCOPING
 * DECISION 1) is also validated directly here: full partition, per-cage
 * connectivity, and sane cage sizes, independent of any particular generated
 * puzzle.
 */
class KenKenGameTest {

    private val sizeByDifficulty = mapOf(CpuDifficulty.EASY to 4, CpuDifficulty.MEDIUM to 6, CpuDifficulty.HARD to 9)

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): KenKenGame {
        var fakeClock = 0L
        val game = KenKenGame(nowMillis = { fakeClock++ })
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

    // -- Independent Latin-square validity + cage-arithmetic checker + backtracking solution counter. Deliberately fresh code, not calling anything in KenKenGame. --

    private fun independentCageSatisfied(operator: KenKenOperator?, target: Int, values: List<Int>): Boolean = when (operator) {
        null -> values.size == 1 && values[0] == target
        KenKenOperator.ADD -> values.sum() == target
        KenKenOperator.MUL -> values.fold(1) { acc, v -> acc * v } == target
        KenKenOperator.SUB -> values.size == 2 && kotlin.math.abs(values[0] - values[1]) == target
        KenKenOperator.DIV -> if (values.size != 2) {
            false
        } else {
            val a = maxOf(values[0], values[1])
            val b = minOf(values[0], values[1])
            b != 0 && a % b == 0 && a / b == target
        }
    }

    private fun isValidLatinSquare(size: Int, grid: List<Int>): Boolean {
        for (r in 0 until size) {
            val row = (0 until size).map { grid[r * size + it] }
            if (row.toSet() != (1..size).toSet()) return false
        }
        for (c in 0 until size) {
            val col = (0 until size).map { grid[it * size + c] }
            if (col.toSet() != (1..size).toSet()) return false
        }
        return true
    }

    private val INCONCLUSIVE_SENTINEL = -1

    /**
     * Independent backtracking solution counter over Latin-square masks plus
     * per-cage arithmetic pruning (checked the instant a cage's last cell is
     * filled) — same conceptual algorithm as the engine's own private
     * countKenKenSolutions, written completely fresh here so a masking/
     * off-by-one bug can't be shared between the engine and this check.
     */
    private fun independentCountSolutions(size: Int, cages: List<KenKenCage>, cap: Int = 2, callBudget: Int = 500_000): Int {
        val cellCage = IntArray(size * size)
        for (cage in cages) for (idx in cage.cellIndices) cellCage[idx] = cage.id
        val cageById = cages.associateBy { it.id }
        val work = IntArray(size * size)
        val rowMask = IntArray(size)
        val colMask = IntArray(size)
        val allMask = (1..size).fold(0) { acc, d -> acc or (1 shl d) }
        var count = 0
        var calls = 0
        var budgetExceeded = false

        fun solve(): Boolean {
            calls++
            if (calls > callBudget) {
                budgetExceeded = true
                return true
            }
            var bestPos = -1
            var bestMask = 0
            var bestCount = size + 1
            for (p in 0 until size * size) {
                if (work[p] != 0) continue
                val row = p / size
                val col = p % size
                val avail = allMask and (rowMask[row] or colMask[col]).inv()
                val n = Integer.bitCount(avail)
                if (n == 0) return false
                if (n < bestCount) {
                    bestCount = n; bestPos = p; bestMask = avail
                    if (n == 1) break
                }
            }
            if (bestPos == -1) {
                count++
                return count >= cap
            }
            val row = bestPos / size
            val col = bestPos % size
            var remaining = bestMask
            while (remaining != 0) {
                val bit = remaining and (-remaining)
                remaining = remaining and (remaining - 1)
                val d = Integer.numberOfTrailingZeros(bit)
                work[bestPos] = d
                rowMask[row] = rowMask[row] or bit
                colMask[col] = colMask[col] or bit

                val cage = cageById.getValue(cellCage[bestPos])
                val values = cage.cellIndices.map { work[it] }
                val complete = values.none { it == 0 }
                val ok = !complete || independentCageSatisfied(cage.operator, cage.target, values)

                if (ok && solve()) return true

                work[bestPos] = 0
                rowMask[row] = rowMask[row] and bit.inv()
                colMask[col] = colMask[col] and bit.inv()
            }
            return false
        }
        solve()
        return if (budgetExceeded) INCONCLUSIVE_SENTINEL else count
    }

    // -- Generated-puzzle correctness tests. --

    @Test
    fun `board size matches each tier's own scoping decision -- EASY 4x4, MEDIUM 6x6, HARD 9x9`() {
        for ((difficulty, size) in sizeByDifficulty) {
            val game = newGame(difficulty)
            game.startMatch(dailySeed = 1L)
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty", size, s.size)
            assertEquals(size * size, s.cells.size)
            assertEquals(size * size, s.solution.size)
        }
    }

    @Test
    fun `the generated solution is a fully valid Latin square, across every difficulty and many seeds`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..10L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                assertTrue("difficulty=$difficulty seed=$seed: solution is not a valid Latin square", isValidLatinSquare(s.size, s.solution))
            }
        }
    }

    @Test
    fun `every cage's clue is genuinely satisfied by the puzzle's own solution`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..10L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                for (cage in s.cages) {
                    val values = cage.cellIndices.map { s.solution[it] }
                    assertTrue(
                        "difficulty=$difficulty seed=$seed cage=${cage.id}: solution values $values do not satisfy ${cage.operator}=${cage.target}",
                        independentCageSatisfied(cage.operator, cage.target, values)
                    )
                }
            }
        }
    }

    @Test
    fun `1-cell cages carry no operator, 2-cell cages always carry one, and 3+-cell cages only ever use ADD or MUL`() {
        // The real KenKen convention KenKenGame's own KDoc (SCOPING DECISION 3)
        // documents -- SUB/DIV are only well-defined for an ordered pair of
        // exactly two values, never 3+. Independently re-checked here rather
        // than trusted from the engine's own bookkeeping.
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..10L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                for (cage in s.cages) {
                    when (cage.cellIndices.size) {
                        1 -> assertEquals("a 1-cell cage must carry no operator", null, cage.operator)
                        2 -> assertNotEquals("a 2-cell cage must carry an operator", null, cage.operator)
                        else -> assertTrue(
                            "a ${cage.cellIndices.size}-cell cage must use only ADD or MUL, was ${cage.operator}",
                            cage.operator == KenKenOperator.ADD || cage.operator == KenKenOperator.MUL
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `every generated puzzle is genuinely uniquely solvable, independently re-verified, across every difficulty and many seeds`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..8L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                val result = independentCountSolutions(s.size, s.cages)
                assertNotEquals("difficulty=$difficulty seed=$seed: independent uniqueness check was inconclusive (ran out of budget)", INCONCLUSIVE_SENTINEL, result)
                assertEquals("difficulty=$difficulty seed=$seed: puzzle should have EXACTLY one solution", 1, result)
            }
        }
    }

    @Test
    fun `a daily seed makes the puzzle reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        assertEquals(gameA.state.value!!.solution, gameB.state.value!!.solution)
        assertEquals(gameA.state.value!!.cages, gameB.state.value!!.cages)
    }

    // -- Hand-authored cage template validation, independent of any generated puzzle. --

    private fun isFullyConnected(size: Int, cells: List<Int>): Boolean {
        if (cells.isEmpty()) return false
        val cellSet = cells.toSet()
        val visited = mutableSetOf(cells.first())
        val queue = ArrayDeque(listOf(cells.first()))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val row = cur / size
            val col = cur % size
            val neighbors = listOfNotNull(
                if (row > 0) cur - size else null,
                if (row < size - 1) cur + size else null,
                if (col > 0) cur - 1 else null,
                if (col < size - 1) cur + 1 else null
            )
            for (n in neighbors) {
                if (n in cellSet && n !in visited) {
                    visited += n
                    queue.addLast(n)
                }
            }
        }
        return visited.size == cells.size
    }

    @Test
    fun `every hand-authored cage template fully partitions its board with no gaps or overlaps`() {
        for ((difficulty, templates) in KENKEN_CAGE_TEMPLATES) {
            val size = sizeByDifficulty.getValue(difficulty)
            for ((templateIndex, template) in templates.withIndex()) {
                assertEquals("difficulty=$difficulty template=$templateIndex: wrong array length for a ${size}x$size board", size * size, template.size)
                val allCells = template.indices.toSet()
                val coveredCells = template.toSet().flatMap { id -> template.indices.filter { template[it] == id } }.toSet()
                assertEquals("difficulty=$difficulty template=$templateIndex: every cell must belong to exactly one cage", allCells, coveredCells)
            }
        }
    }

    @Test
    fun `every hand-authored cage template's cages are each a single orthogonally-connected region`() {
        for ((difficulty, templates) in KENKEN_CAGE_TEMPLATES) {
            val size = sizeByDifficulty.getValue(difficulty)
            for ((templateIndex, template) in templates.withIndex()) {
                val byCage = template.indices.groupBy { template[it] }
                for ((cageId, cells) in byCage) {
                    assertTrue(
                        "difficulty=$difficulty template=$templateIndex cage=$cageId: cells $cells are not a single connected region",
                        isFullyConnected(size, cells)
                    )
                }
            }
        }
    }

    @Test
    fun `every hand-authored cage template keeps cage sizes sane -- at least 1, never more than 4`() {
        for ((difficulty, templates) in KENKEN_CAGE_TEMPLATES) {
            for ((templateIndex, template) in templates.withIndex()) {
                val sizesById = template.toList().groupingBy { it }.eachCount()
                for ((cageId, cageSize) in sizesById) {
                    assertTrue(
                        "difficulty=$difficulty template=$templateIndex cage=$cageId: size $cageSize is out of the sane 1..4 range",
                        cageSize in 1..4
                    )
                }
            }
        }
    }

    @Test
    fun `each tier ships at least 3 distinct hand-authored cage templates`() {
        for ((difficulty, templates) in KENKEN_CAGE_TEMPLATES) {
            assertTrue("difficulty=$difficulty should ship at least 3 templates", templates.size >= 3)
            assertEquals(
                "difficulty=$difficulty: templates should all be distinct shapes, not accidental duplicates",
                templates.size,
                templates.map { it.toList() }.distinct().size
            )
        }
    }

    // -- Gameplay tests, mirroring SudokuGameTest's own precedent. --

    @Test
    fun `every cell starts blank -- KenKen has no given cells, unlike Sudoku`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        assertTrue(
            "every KenKen cell should start empty -- a single-cell cage's clue is not a pre-filled given (see KenKenGame's own KDoc, NO GIVEN CELLS)",
            s.cells.all { it.value == null }
        )
    }

    @Test
    fun `selecting an out-of-range index is ignored, not a crash`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val before = game.state.value!!

        game.selectCell(-1)
        assertEquals("an out-of-range selectCell call must not change selectedIndex", before.selectedIndex, game.state.value!!.selectedIndex)

        game.selectCell(before.cells.size)
        assertEquals("an out-of-range selectCell call must not change selectedIndex", before.selectedIndex, game.state.value!!.selectedIndex)

        // Must not have left the engine in a state where a subsequent real action crashes either.
        game.setValue(1)
        game.clearValue()
        game.toggleNote(1)
    }

    @Test
    fun `setting the correct value does not count as a mistake, a wrong value does`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val index = 0
        val correct = s.solution[index]
        val wrong = (correct % s.size) + 1

        game.selectCell(index)
        game.setValue(correct)
        assertEquals(0, game.state.value!!.mistakes)

        game.setValue(wrong)
        assertEquals(1, game.state.value!!.mistakes)
        assertEquals(wrong, game.state.value!!.cells[index].value)
    }

    @Test
    fun `a value outside the current board's own digit range is rejected -- unlike Sudoku's fixed 1-9, KenKen's range varies by tier`() {
        val game = newGame(CpuDifficulty.EASY) // 4x4 -- valid digits are 1..4
        game.startMatch(dailySeed = 5L)
        game.selectCell(0)

        game.setValue(9) // out of range for a 4x4 board
        assertEquals("an out-of-range digit must be a no-op", null, game.state.value!!.cells[0].value)

        game.toggleNote(9)
        assertTrue("an out-of-range note must be a no-op", game.state.value!!.cells[0].notes.isEmpty())

        game.setValue(0)
        assertEquals("digit 0 must be a no-op", null, game.state.value!!.cells[0].value)
    }

    @Test
    fun `placing a value clears that digit from every peer's pencil marks`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val target = 0
        val row = target / s.size
        val peerInRow = row * s.size + 1
        val value = s.solution[target]

        game.selectCell(peerInRow)
        game.toggleNote(value)
        assertTrue(value in game.state.value!!.cells[peerInRow].notes)

        game.selectCell(target)
        game.setValue(value)
        assertFalse("the peer's pencil mark for the placed value should be auto-cleared", value in game.state.value!!.cells[peerInRow].notes)
    }

    @Test
    fun `toggling a note works only on an empty cell`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val index = 0

        game.selectCell(index)
        game.toggleNote(2)
        assertTrue(2 in game.state.value!!.cells[index].notes)
        game.toggleNote(2)
        assertFalse(2 in game.state.value!!.cells[index].notes)

        game.setValue(s.solution[index])
        game.toggleNote(1)
        assertTrue("a cell that already holds a value should never also accept a pencil mark", game.state.value!!.cells[index].notes.isEmpty())
    }

    @Test
    fun `clearValue erases only the value`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        val index = 0

        game.selectCell(index)
        game.setValue(s.solution[index])
        assertNotNull(game.state.value!!.cells[index].value)

        game.clearValue()
        assertEquals(null, game.state.value!!.cells[index].value)
    }

    @Test
    fun `filling every cell with the solution wins the board and increments puzzlesSolved`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 9L)
        val s = game.state.value!!
        for (i in s.cells.indices) {
            game.selectCell(i)
            game.setValue(s.solution[i])
        }
        assertTrue(game.state.value!!.won)
        assertEquals(1, game.puzzlesSolved.value)
        assertEquals(0, game.state.value!!.mistakes)
        assertNotNull(game.finishedElapsedMillis.value)
    }

    @Test
    fun `pausing mid-puzzle does not inflate the recorded solve time`() {
        var clock = 0L
        val game = KenKenGame(nowMillis = { clock })
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

        game.selectCell(0)
        game.setValue(s.solution[0]) // starts the timer

        clock = 10L
        game.pause()
        clock = 600_010L // ~10 real minutes pass while backgrounded
        game.resume()
        clock = 600_020L

        for (i in s.cells.indices) {
            if (i != 0) {
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
    fun `pausing twice without an intervening resume does not lose the interval between the two pauses`() {
        var clock = 0L
        val game = KenKenGame(nowMillis = { clock })
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

        clock = 100L
        game.selectCell(0)
        game.setValue(s.solution[0]) // starts the timer at t=100

        clock = 110L
        game.pause() // pausedAt = 110
        clock = 200L
        game.pause() // must be a no-op -- pausedAt should STILL be 110, not overwritten to 200
        clock = 250L
        game.resume() // totalPausedMillis += 250 - 110 = 140 (not 250 - 200 = 50)

        clock = 300L
        for (i in s.cells.indices) {
            if (i != 0) {
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
    fun `setValue, clearValue, and toggleNote are all rejected once the session has ended via leaveSession, even if the board itself was not yet won`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 5L)
        val s = game.state.value!!
        game.selectCell(0)
        game.setValue(s.solution[0])
        assertFalse(game.state.value!!.isOver)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.setValue((s.solution[0] % s.size) + 1)
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

        game.startMatch(dailySeed = 2L) // a fresh puzzle should always be fully playable again
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    @Test
    fun `generating a full batch of puzzles across every difficulty completes quickly`() {
        // A coarse performance sanity check, not a strict benchmark -- exists to
        // catch a real regression (e.g. an accidental infinite loop, or the
        // MAX_GENERATION_ATTEMPTS/call-budget safety nets failing to bound
        // worst-case work), not to assert a specific number. Mirrors
        // SudokuGameTest's/NonogramGameTest's own precedent for this exact
        // kind of test.
        val start = System.nanoTime()
        for (difficulty in CpuDifficulty.entries) {
            repeat(10) { i ->
                val game = newGame(difficulty)
                game.startMatch(dailySeed = 1000L + i)
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("generating 30 puzzles (10 per tier, including HARD's 9x9) took ${elapsedMs}ms, unexpectedly slow", elapsedMs < 20_000)
    }
}
