package com.gamesuite.games.gomoku

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Rules, win detection and CPU behaviour of [GomokuEngine] on hand-built boards. */
class GomokuEngineTest {
    private val n = GOMOKU_SIZE
    private fun at(r: Int, c: Int) = r * n + c

    private fun board(vararg stones: Triple<Int, Int, Int>): IntArray {
        val b = IntArray(n * n) { GomokuEngine.EMPTY }
        for ((r, c, p) in stones) b[at(r, c)] = p
        return b
    }

    private fun line(r: Int, c: Int, dr: Int, dc: Int, len: Int, p: Int) = (0 until len).map { Triple(r + it * dr, c + it * dc, p) }

    // ---- rules ----

    @Test
    fun `five in a row wins in every direction`() {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for ((dr, dc) in dirs) {
            val startC = if (dc < 0) 9 else 3
            val b = board(*line(3, startC, dr, dc, 5, 0).toTypedArray())
            val engine = GomokuEngine(n, false)
            for (k in 0 until 5) {
                val idx = at(3 + k * dr, startC + k * dc)
                val win = engine.winLine(b, idx, 0)
                assertNotNull("dir $dr,$dc cell $k", win)
                assertEquals(5, win!!.size)
            }
        }
    }

    @Test
    fun `four in a row is not a win and a broken line is not a win`() {
        val engine = GomokuEngine(n, false)
        val four = board(*line(7, 3, 0, 1, 4, 0).toTypedArray())
        assertNull(engine.winLine(four, at(7, 3), 0))
        val broken = board(*line(7, 3, 0, 1, 2, 0).toTypedArray(), Triple(7, 5, 1), *line(7, 6, 0, 1, 3, 0).toTypedArray())
        assertNull(engine.winLine(broken, at(7, 6), 0))
    }

    @Test
    fun `a line does not wrap from the end of one row to the start of the next`() {
        val engine = GomokuEngine(n, false)
        val b = board(Triple(4, 13, 0), Triple(4, 14, 0), Triple(5, 0, 0), Triple(5, 1, 0), Triple(5, 2, 0))
        for (idx in listOf(at(4, 13), at(4, 14), at(5, 0), at(5, 1), at(5, 2))) assertNull(engine.winLine(b, idx, 0))
    }

    @Test
    fun `an opponent stone in the middle breaks a line`() {
        val engine = GomokuEngine(n, false)
        val b = board(*line(2, 2, 1, 1, 5, 0).toTypedArray(), Triple(4, 4, 1))
        assertNull(engine.winLine(b, at(2, 2), 0))
    }

    @Test
    fun `free-style counts an overline of six but exact-five does not`() {
        val six = board(*line(7, 2, 0, 1, 6, 0).toTypedArray())
        assertEquals(6, GomokuEngine(n, false).winLine(six, at(7, 4), 0)!!.size)
        assertNull(GomokuEngine(n, true).winLine(six, at(7, 4), 0))
        val five = board(*line(7, 2, 0, 1, 5, 0).toTypedArray())
        assertNotNull(GomokuEngine(n, true).winLine(five, at(7, 4), 0))
    }

    @Test
    fun `exact-five still wins when another direction makes exactly five`() {
        // Six across row 7, but the stone at (7,4) also completes exactly five down column 4.
        val b = board(*line(7, 2, 0, 1, 6, 0).toTypedArray(), *line(3, 4, 1, 0, 4, 0).toTypedArray())
        assertNotNull(GomokuEngine(n, true).winLine(b, at(7, 4), 0))
    }

    @Test
    fun `winLine ignores an empty cell and the wrong player`() {
        val engine = GomokuEngine(n, false)
        val b = board(*line(7, 2, 0, 1, 5, 0).toTypedArray())
        assertNull(engine.winLine(b, at(0, 0), 0))
        assertNull(engine.winLine(b, at(7, 2), 1))
        assertNull(engine.winLine(b, -1, 0))
        assertNull(engine.winLine(b, n * n, 0))
    }

    // ---- CPU ----

    @Test
    fun `empty board opens in the centre at every level`() {
        for (level in CpuDifficulty.values()) {
            assertEquals(at(7, 7), GomokuEngine(n, false).chooseMove(IntArray(n * n) { -1 }, 0, level, random = Random(1)))
        }
    }

    @Test
    fun `a full board has no move`() {
        val full = IntArray(n * n) { it % 2 }
        for (level in CpuDifficulty.values()) assertNull(GomokuEngine(n, false).chooseMove(full, 0, level))
    }

    @Test
    fun `every level takes a win when it has one`() {
        // CPU is seat 1 with an open four; the opponent also has a four, but winning comes first.
        val b = board(*line(3, 3, 0, 1, 4, 1).toTypedArray(), *line(9, 3, 0, 1, 4, 0).toTypedArray(), Triple(9, 2, 1), Triple(9, 7, 1))
        for (level in CpuDifficulty.values()) {
            val move = GomokuEngine(n, false).chooseMove(b, 1, level, random = Random(5))!!
            assertTrue("level $level played $move", move == at(3, 2) || move == at(3, 7))
        }
    }

    @Test
    fun `medium and hard always block a closed four`() {
        val b = board(*line(7, 5, 0, 1, 4, 0).toTypedArray(), Triple(7, 4, 1))
        for (level in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            assertEquals("level $level", at(7, 9), GomokuEngine(n, false).chooseMove(b, 1, level, random = Random(2)))
        }
    }

    @Test
    fun `easy sometimes misses a threat so a person can beat it, but usually blocks`() {
        val b = board(*line(7, 5, 0, 1, 4, 0).toTypedArray(), Triple(7, 4, 1))
        var blocked = 0
        for (seed in 0 until 200) {
            if (GomokuEngine(n, false).chooseMove(b, 1, CpuDifficulty.EASY, random = Random(seed)) == at(7, 9)) blocked++
        }
        assertTrue("blocked $blocked of 200", blocked in 100..190)
    }

    @Test
    fun `hard blocks an open three before it becomes an unstoppable four`() {
        // Opponent open three on row 7 cols 6-8; the only sound replies are the two ends (or one step out).
        val b = board(*line(7, 6, 0, 1, 3, 0).toTypedArray(), Triple(6, 6, 1))
        val move = GomokuEngine(n, false).chooseMove(b, 1, CpuDifficulty.HARD, System.nanoTime() + 3_000_000_000L, Random(3))!!
        assertTrue("played $move", move in setOf(at(7, 5), at(7, 9), at(7, 4), at(7, 10)))
    }

    @Test
    fun `every level returns only legal empty cells over whole random games`() {
        val engine = GomokuEngine(n, false)
        for (level in CpuDifficulty.values()) {
            val rnd = Random(level.ordinal + 10)
            val b = IntArray(n * n) { -1 }
            repeat(40) {
                // A random stone for the other side, then the CPU's reply: positions stay varied.
                val empties = b.indices.filter { b[it] == -1 }
                b[empties[rnd.nextInt(empties.size)]] = 1
                val m = engine.chooseMove(b, 0, level, System.nanoTime() + 150_000_000L, rnd)
                assertNotNull(m)
                assertEquals(-1, b[m!!])
                b[m] = 0
            }
        }
    }

    private fun playGame(hardIsFirst: Boolean, seed: Int): Int {
        val engine = GomokuEngine(n, false)
        val b = IntArray(n * n) { -1 }
        val rnd = Random(seed)
        var toMove = 0
        while (b.any { it == -1 }) {
            val hardTurn = (toMove == 0) == hardIsFirst
            val level = if (hardTurn) CpuDifficulty.HARD else CpuDifficulty.EASY
            val m = engine.chooseMove(b, toMove, level, System.nanoTime() + 250_000_000L, rnd)!!
            b[m] = toMove
            if (engine.winLine(b, m, toMove) != null) return if (hardTurn) 1 else -1
            toMove = 1 - toMove
        }
        return 0
    }

    @Test
    fun `hard beats easy playing either colour`() {
        assertEquals(1, playGame(hardIsFirst = true, seed = 1))
        assertEquals(1, playGame(hardIsFirst = false, seed = 2))
    }
}
