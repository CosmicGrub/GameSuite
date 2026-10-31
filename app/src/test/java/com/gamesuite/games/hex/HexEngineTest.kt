package com.gamesuite.games.hex

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Geometry, connection detection, distances and CPU behaviour of [HexEngine]. */
class HexEngineTest {
    private fun engine(n: Int = 11) = HexEngine.of(n)

    private fun board(n: Int, vararg stones: Triple<Int, Int, Int>): IntArray {
        val b = IntArray(n * n) { -1 }
        for ((r, c, p) in stones) b[r * n + c] = p
        return b
    }

    @Test
    fun `each cell has the right neighbours and they are symmetric`() {
        for (n in HexBoardSizes.ALL) {
            val e = engine(n)
            assertEquals(2, e.neighborsOf(0).size) // (0,0): acute corner
            assertEquals(3, e.neighborsOf(n - 1).size) // (0,n-1): obtuse corner
            assertEquals(3, e.neighborsOf((n - 1) * n).size) // (n-1,0)
            assertEquals(2, e.neighborsOf(n * n - 1).size) // (n-1,n-1)
            assertEquals(6, e.neighborsOf((n / 2) * n + n / 2).size)
            assertEquals(4, e.neighborsOf(n / 2).size) // an edge cell
            for (i in 0 until n * n) for (j in e.neighborsOf(i)) assertTrue(i in e.neighborsOf(j))
        }
    }

    @Test
    fun `the diagonal neighbours lean the right way`() {
        val n = 11
        val centre = 5 * n + 5
        val set = engine(n).neighborsOf(centre).toSet()
        assertEquals(setOf(5 * n + 4, 5 * n + 6, 4 * n + 5, 6 * n + 5, 4 * n + 6, 6 * n + 4), set)
    }

    @Test
    fun `a straight column joins top to bottom for seat 0 only`() {
        val n = 11
        val b = IntArray(n * n) { -1 }
        for (r in 0 until n) b[r * n + 4] = 0
        assertTrue(engine().connected(b, 0))
        assertFalse(engine().connected(b, 1))
        assertEquals(n, engine().winPath(b, 0)!!.size)
        b[5 * n + 4] = -1
        assertFalse(engine().connected(b, 0))
    }

    @Test
    fun `a straight row joins left to right for seat 1 only`() {
        val n = 11
        val b = IntArray(n * n) { -1 }
        for (c in 0 until n) b[3 * n + c] = 1
        assertTrue(engine().connected(b, 1))
        assertFalse(engine().connected(b, 0))
    }

    @Test
    fun `a diagonal chain using the leaning neighbour connects`() {
        val n = 11
        // (r, n-1-r... ) uses (r+1, c-1) steps: from (0,10) down-left to (10,0): 11 stones, joins top and bottom.
        val b = IntArray(n * n) { -1 }
        for (r in 0 until n) b[r * n + (n - 1 - r)] = 0
        assertTrue(engine().connected(b, 0))
        // The other diagonal direction (r+1, c+1) is NOT adjacent.
        val c = IntArray(n * n) { -1 }
        for (r in 0 until n) c[r * n + r] = 0
        assertFalse(engine().connected(c, 0))
    }

    @Test
    fun `an opposing stone or a gap breaks the chain`() {
        val n = 9
        val b = board(n, *(0 until n).map { Triple(it, 2, 0) }.toTypedArray())
        assertTrue(engine(n).connected(b, 0))
        b[4 * n + 2] = 1
        assertFalse(engine(n).connected(b, 0))
    }

    @Test
    fun `distance counts the empty cells still needed and respects walls`() {
        val n = 9
        val e = engine(n)
        assertEquals(n, e.distance(IntArray(n * n) { -1 }, 0))
        val b = board(n, Triple(0, 3, 0), Triple(1, 3, 0), Triple(2, 3, 0))
        assertEquals(n - 3, e.distance(b, 0))
        for (r in 0 until n) b[r * n + 3] = 0
        assertEquals(0, e.distance(b, 0))
        val walled = IntArray(n * n) { -1 }
        for (c in 0 until n) walled[4 * n + c] = 1
        assertEquals(HexEngine.BLOCKED, e.distance(walled, 0))
        assertEquals(0, e.distance(walled, 1))
    }

    @Test
    fun `a completely filled board always has exactly one winner`() {
        for (n in HexBoardSizes.ALL) {
            val e = engine(n)
            repeat(300) { seed ->
                val rnd = Random(seed * 31 + n)
                val b = IntArray(n * n) { rnd.nextInt(2) }
                val a = e.connected(b, 0)
                val z = e.connected(b, 1)
                assertTrue("n=$n seed=$seed", a != z)
            }
        }
    }

    @Test
    fun `swap cell is the transpose and is its own inverse`() {
        val e = engine(11)
        for (i in 0 until 121) assertEquals(i, e.swapCell(e.swapCell(i)))
        assertEquals(2 * 11 + 5, e.swapCell(5 * 11 + 2))
        assertEquals(60, e.swapCell(60)) // the long diagonal maps to itself
        // Transposing swaps the roles: a top-bottom chain for seat 0 becomes a left-right chain for seat 1.
        val b = IntArray(121) { -1 }
        for (r in 0 until 11) b[r * 11 + 4] = 0
        val t = IntArray(121) { -1 }
        for (i in 0 until 121) if (b[i] == 0) t[e.swapCell(i)] = 1
        assertTrue(e.connected(t, 1))
    }

    @Test
    fun `board size option ids map to supported sizes`() {
        assertEquals(9, HexBoardSizes.fromOptionId("9"))
        assertEquals(11, HexBoardSizes.fromOptionId("11"))
        assertEquals(13, HexBoardSizes.fromOptionId("13"))
        assertEquals(11, HexBoardSizes.fromOptionId("12"))
        assertEquals(11, HexBoardSizes.fromOptionId(null))
        assertEquals(11, HexBoardSizes.fromOptionId("big"))
    }

    // ---- CPU ----

    @Test
    fun `every level completes a connection when one move wins`() {
        val n = 9
        // Seat 0 has a column of eight with one missing cell at row 4.
        val b = board(n, *(0 until n).filter { it != 4 }.map { Triple(it, 3, 0) }.toTypedArray(), Triple(4, 5, 1))
        for (level in CpuDifficulty.values()) {
            assertEquals("level $level", 4 * n + 3, engine(n).chooseMove(b, 0, level, random = Random(1)))
        }
    }

    @Test
    fun `medium and hard stop a one-move win`() {
        val n = 9
        // Seat 1 has a row of eight (col 4 missing); seat 0 must take that cell.
        val b = board(n, *(0 until n).filter { it != 4 }.map { Triple(4, it, 1) }.toTypedArray(), Triple(0, 0, 0))
        for (level in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            assertEquals("level $level", 4 * n + 4, engine(n).chooseMove(b, 0, level, System.nanoTime() + 3_000_000_000L, Random(2)))
        }
    }

    @Test
    fun `every level returns only empty cells on random positions of every size`() {
        for (n in HexBoardSizes.ALL) {
            val e = engine(n)
            for (level in CpuDifficulty.values()) {
                val rnd = Random(n + level.ordinal)
                val b = IntArray(n * n) { -1 }
                repeat(25) {
                    val empties = b.indices.filter { b[it] == -1 }
                    b[empties[rnd.nextInt(empties.size)]] = 1
                    val m = e.chooseMove(b, 0, level, System.nanoTime() + 150_000_000L, rnd)
                    assertNotNull(m)
                    assertEquals(-1, b[m!!])
                    b[m] = 0
                }
            }
        }
    }

    @Test
    fun `no move on a full board`() {
        val b = IntArray(121) { it % 2 }
        assertNull(engine().chooseMove(b, 0, CpuDifficulty.HARD))
    }

    @Test
    fun `empty board opens in the centre`() {
        assertEquals(60, engine().chooseMove(IntArray(121) { -1 }, 0, CpuDifficulty.HARD, random = Random(1)))
    }

    private fun playGame(n: Int, hardIsSeat0: Boolean, seed: Int): Int {
        val e = engine(n)
        val b = IntArray(n * n) { -1 }
        val rnd = Random(seed)
        var toMove = 0
        while (true) {
            val hardTurn = (toMove == 0) == hardIsSeat0
            val level = if (hardTurn) CpuDifficulty.HARD else CpuDifficulty.EASY
            val m = e.chooseMove(b, toMove, level, System.nanoTime() + 250_000_000L, rnd) ?: return 0
            b[m] = toMove
            if (e.connected(b, toMove)) return if (hardTurn) 1 else -1
            toMove = 1 - toMove
        }
    }

    @Test
    fun `hard beats easy playing either side`() {
        assertEquals(1, playGame(9, hardIsSeat0 = true, seed = 1))
        assertEquals(1, playGame(9, hardIsSeat0 = false, seed = 2))
    }

    @Test
    fun `the pie logic swaps strong openings and keeps weak ones`() {
        val e = engine(11)
        assertTrue(e.shouldSwap(5 * 11 + 5))
        assertTrue(e.shouldSwap(3 * 11 + 3))
        assertFalse(e.shouldSwap(0))
        assertFalse(e.shouldSwap(1 * 11 + 5))
        repeat(20) { assertFalse(e.shouldSwap(e.pieOpening(Random(it)))) }
    }
}
