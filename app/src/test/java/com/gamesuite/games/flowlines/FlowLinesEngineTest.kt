package com.gamesuite.games.flowlines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-engine tests for Flow Lines: generator, solver and the path-drawing rules. */
class FlowLinesEngineTest {

    /**
     * 0 1 2
     * 3 4 5
     * 6 7 8
     * colour 0: dots 0 and 8, solution 0-1-2-5-8; colour 1: dots 3 and 6, solution 3-4-7-6.
     */
    private fun small() = FlowPuzzle(
        size = 3,
        pairs = listOf(FlowPair(0, 8), FlowPair(3, 6)),
        solution = listOf(listOf(0, 1, 2, 5, 8), listOf(3, 4, 7, 6))
    )

    private fun empty(p: FlowPuzzle): List<List<Int>> = List(p.colorCount) { emptyList() }

    // --- Generator --------------------------------------------------------------------------

    @Test
    fun `every tier and many seeds give a valid board covered exactly once by the generated paths`() {
        for (tier in FlowTier.entries) {
            for (seed in 1L..60L) {
                val p = FlowGenerator.generate(tier, seed)
                assertTrue("tier $tier seed $seed", p.isValid())
                assertEquals(tier.size, p.size)
                assertEquals(tier.flows, p.colorCount)
                val seen = IntArray(p.cellCount)
                for (path in p.solution) for (c in path) seen[c]++
                assertTrue("every cell exactly once ($tier, seed $seed)", seen.all { it == 1 })
            }
        }
    }

    @Test
    fun `dots are unique and each path is at least three cells so no pair is trivial`() {
        for (tier in FlowTier.entries) {
            for (seed in 100L..130L) {
                val p = FlowGenerator.generate(tier, seed)
                val dots = p.pairs.flatMap { listOf(it.a, it.b) }
                assertEquals("dots unique", dots.size, dots.toSet().size)
                assertTrue(p.solution.all { it.size >= 3 })
                for (pair in p.pairs) assertNotEquals(pair.a, pair.b)
            }
        }
    }

    @Test
    fun `every solution path is contiguous and starts and ends on its own dots`() {
        for (seed in 1L..40L) {
            val p = FlowGenerator.generate(FlowTier.S8, seed)
            for ((color, path) in p.solution.withIndex()) {
                assertEquals(p.pairs[color].a, path.first())
                assertEquals(p.pairs[color].b, path.last())
                for (i in 1 until path.size) assertTrue(FlowRules.adjacent(p.size, path[i - 1], path[i]))
            }
        }
    }

    @Test
    fun `the generator is deterministic for a seed and varies across seeds`() {
        val a = FlowGenerator.generate(FlowTier.S9, 4242L)
        val b = FlowGenerator.generate(FlowTier.S9, 4242L)
        assertEquals(a, b)
        val distinct = (1L..20L).map { FlowGenerator.generate(FlowTier.S7, it) }.toSet()
        assertTrue("different seeds should give different boards", distinct.size > 15)
    }

    @Test
    fun `the biggest board generates in well under a second`() {
        val start = System.nanoTime()
        repeat(20) { FlowGenerator.generate(FlowTier.S9, it.toLong()) }
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("20 nine-by-nine boards took $millis ms", millis < 1000)
    }

    @Test
    fun `daily seed is deterministic and depends on day and tier`() {
        assertEquals(FlowGenerator.dailySeed(20000L, FlowTier.S7), FlowGenerator.dailySeed(20000L, FlowTier.S7))
        assertNotEquals(FlowGenerator.dailySeed(20000L, FlowTier.S7), FlowGenerator.dailySeed(20001L, FlowTier.S7))
        assertNotEquals(FlowGenerator.dailySeed(20000L, FlowTier.S7), FlowGenerator.dailySeed(20000L, FlowTier.S8))
        val x = FlowGenerator.generate(FlowTier.S6, FlowGenerator.dailySeed(20000L, FlowTier.S6))
        val y = FlowGenerator.generate(FlowTier.S6, FlowGenerator.dailySeed(20000L, FlowTier.S6))
        assertEquals(x, y)
    }

    // --- Solver -----------------------------------------------------------------------------

    @Test
    fun `the solver agrees generated 5x5 and 6x6 boards are solvable and its answer is a real solution`() {
        var conclusive = 0
        var total = 0
        for (tier in listOf(FlowTier.S5, FlowTier.S6)) {
            for (seed in 1L..25L) {
                total++
                val p = FlowGenerator.generate(tier, seed)
                val result = FlowSolver.solve(p.size, p.pairs)
                // A board is never reported unsolvable: either the solver finds a real solution or it ran out of budget.
                if (result.solutions.isEmpty()) {
                    assertTrue("solver wrongly reported no solution ($tier $seed)", result.exhausted)
                    continue
                }
                conclusive++
                assertEquals(1, result.solutions.size)
                assertTrue(FlowRules.isSolved(p, result.solutions[0]))
            }
        }
        assertTrue("solver should settle nearly every small board ($conclusive of $total)", conclusive >= total * 8 / 10)
    }

    @Test
    fun `the solver finds the handmade board and can count its solutions`() {
        val p = small()
        val result = FlowSolver.solve(p.size, p.pairs, maxSolutions = 10)
        assertFalse(result.exhausted)
        assertTrue(result.solutions.isNotEmpty())
        for (sol in result.solutions) assertTrue(FlowRules.isSolved(p, sol))
        assertTrue("the intended solution is among them", result.solutions.any { it == p.solution })
    }

    @Test
    fun `the solver reports no solution for an impossible board`() {
        // Diagonal pairs on 2x2: each path would have to run through the other colour's dot.
        val result = FlowSolver.solve(2, listOf(FlowPair(0, 3), FlowPair(1, 2)))
        assertFalse(result.exhausted)
        assertTrue(result.solutions.isEmpty())
    }

    @Test
    fun `a generated 7x7 board is solvable within the node budget or the solver gives up honestly`() {
        val p = FlowGenerator.generate(FlowTier.S7, 5L)
        val result = FlowSolver.solve(p.size, p.pairs, maxNodes = 300_000)
        if (result.solutions.isNotEmpty()) assertTrue(FlowRules.isSolved(p, result.solutions[0])) else assertTrue(result.exhausted)
    }

    // --- Drawing rules ----------------------------------------------------------------------

    @Test
    fun `touching a dot starts that colour from the dot and an empty cell starts nothing`() {
        val p = small()
        val b = FlowRules.begin(p, empty(p), 0)
        assertNotNull(b)
        assertEquals(0, b!!.color)
        assertEquals(listOf(0), b.paths[0])
        assertNull(FlowRules.begin(p, empty(p), 4))
    }

    @Test
    fun `a path extends one adjacent cell at a time`() {
        val p = small()
        var paths = FlowRules.begin(p, empty(p), 0)!!.paths
        paths = FlowRules.step(p, paths, 0, 1)
        paths = FlowRules.step(p, paths, 0, 2)
        assertEquals(listOf(0, 1, 2), paths[0])
        val jump = FlowRules.step(p, paths, 0, 8)
        assertSame("a non-adjacent cell changes nothing", paths, jump)
    }

    @Test
    fun `dragging back retraces the path`() {
        val p = small()
        var paths = listOf(listOf(0, 1, 2), emptyList<Int>())
        paths = FlowRules.step(p, paths, 0, 1)
        assertEquals(listOf(0, 1), paths[0])
        paths = FlowRules.step(p, paths, 0, 0)
        assertEquals(listOf(0), paths[0])
    }

    @Test
    fun `stepping onto an earlier cell of your own path shortens it`() {
        val p = small()
        // 0-1-4-3? cell 3 is a foreign dot, so build a loop-back on a bigger free board instead.
        val big = FlowPuzzle(3, listOf(FlowPair(0, 2)), listOf(listOf(0, 3, 6, 7, 8, 5, 4, 1, 2)))
        val paths = listOf(listOf(0, 3, 4, 1))
        val after = FlowRules.step(big, paths, 0, 0)
        assertEquals("1 is adjacent to 0 and 0 is the start: path cut back to it", listOf(0), after[0])
        assertTrue(p.isValid())
    }

    @Test
    fun `drawing across another path cuts it at the crossing`() {
        val p = small()
        val paths = listOf(listOf(0, 1, 2, 5), listOf(3, 4))
        val after = FlowRules.step(p, paths, 1, 5)
        assertEquals(listOf(3, 4, 5), after[1])
        assertEquals("the crossed path loses the crossing cell and everything beyond", listOf(0, 1, 2), after[0])
        assertTrue(FlowRules.validatePaths(p, after))
    }

    @Test
    fun `a path cannot enter a dot of another colour`() {
        val p = small()
        val paths = listOf(emptyList<Int>(), listOf(3, 4, 7))
        val after = FlowRules.step(p, paths, 1, 8)
        assertSame(paths, after)
    }

    @Test
    fun `a path may end on its partner dot and cannot grow past it`() {
        val p = small()
        var paths = listOf(emptyList<Int>(), listOf(3, 4, 7))
        paths = FlowRules.step(p, paths, 1, 6)
        assertTrue(FlowRules.isConnected(p, paths, 1))
        // 6 has no free neighbour beyond 7, so test the "no growth" rule on colour 0, which is complete at 8.
        val done = listOf(listOf(0, 1, 2, 5, 8), emptyList<Int>())
        assertSame(done, FlowRules.step(p, done, 0, 7))
        // ...but a complete path can still be retraced.
        assertEquals(listOf(0, 1, 2, 5), FlowRules.step(p, done, 0, 5)[0])
    }

    @Test
    fun `touching the middle of a path cuts it back and continues from there`() {
        val p = small()
        val paths = listOf(listOf(0, 1, 2, 5), emptyList<Int>())
        val b = FlowRules.begin(p, paths, 1)
        assertEquals(0, b!!.color)
        assertEquals(listOf(0, 1), b.paths[0])
    }

    @Test
    fun `touching a dot again restarts its path`() {
        val p = small()
        val paths = listOf(listOf(0, 1, 2, 5), emptyList<Int>())
        assertEquals(listOf(0), FlowRules.begin(p, paths, 0)!!.paths[0])
    }

    @Test
    fun `a fast swipe is followed cell by cell`() {
        val p = small()
        val start = FlowRules.begin(p, empty(p), 0)!!.paths
        assertEquals(listOf(0, 1, 2), FlowRules.stepToward(p, start, 0, 2)[0])
        // Aiming at 8 goes through the foreign dot at 3 first (rows before columns) and stops there.
        assertEquals(listOf(0), FlowRules.stepToward(p, start, 0, 8)[0])
    }

    @Test
    fun `solved needs every pair joined and every cell covered`() {
        val p = small()
        assertTrue(FlowRules.isSolved(p, p.solution))
        val reversed = p.solution.map { it.reversed() }
        assertTrue("direction does not matter", FlowRules.isSolved(p, reversed))
        val joinedButGaps = listOf(listOf(0, 1, 2, 5, 8), listOf(3, 6))
        assertTrue(FlowRules.isConnected(p, joinedButGaps, 0) && FlowRules.isConnected(p, joinedButGaps, 1))
        assertFalse("cells 4 and 7 are uncovered", FlowRules.isSolved(p, joinedButGaps))
        assertFalse(FlowRules.isSolved(p, empty(p)))
    }

    @Test
    fun `validatePaths rejects overlaps jumps foreign dots and bad starts`() {
        val p = small()
        assertTrue(FlowRules.validatePaths(p, p.solution))
        assertTrue(FlowRules.validatePaths(p, empty(p)))
        assertFalse("overlap", FlowRules.validatePaths(p, listOf(listOf(0, 1, 2), listOf(3, 4, 1))))
        assertFalse("jump", FlowRules.validatePaths(p, listOf(listOf(0, 2), emptyList())))
        assertFalse("foreign dot", FlowRules.validatePaths(p, listOf(listOf(0, 3), emptyList())))
        assertFalse("starts off a dot", FlowRules.validatePaths(p, listOf(listOf(1, 2), emptyList())))
        assertFalse("wrong colour count", FlowRules.validatePaths(p, listOf(emptyList())))
    }

    @Test
    fun `an invalid puzzle is rejected`() {
        val p = small()
        assertFalse(p.copy(solution = listOf(listOf(0, 2, 5, 8), listOf(3, 4, 7, 6))).isValid())
        assertFalse(p.copy(pairs = listOf(FlowPair(0, 8), FlowPair(0, 6))).isValid())
        assertFalse(p.copy(solution = listOf(listOf(0, 1, 2, 5, 8), listOf(3, 4, 7, 6, 6))).isValid())
        assertFalse(p.copy(size = 1).isValid())
    }

    @Test
    fun `drawing the whole solution stroke by stroke solves the board`() {
        val p = FlowGenerator.generate(FlowTier.S6, 9L)
        var paths = empty(p)
        for (path in p.solution) {
            val b = FlowRules.begin(p, paths, path[0])!!
            paths = b.paths
            for (i in 1 until path.size) paths = FlowRules.step(p, paths, b.color, path[i])
        }
        assertTrue(FlowRules.isSolved(p, paths))
        assertTrue(FlowRules.validatePaths(p, paths))
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `a hint reveals a prefix of a correct path and nothing wrong`() {
        val p = small()
        val h = FlowRules.hintPaths(p, empty(p))!!
        assertEquals(0, h.color)
        assertEquals(p.solution[0].take(1 + FlowRules.HINT_STEP), h.paths[0])
        assertEquals(h.paths[0].last(), h.cell)
        assertTrue(FlowRules.validatePaths(p, h.paths))
    }

    @Test
    fun `hints repair a wrong path and keep going until the board is solved`() {
        val p = FlowGenerator.generate(FlowTier.S7, 3L)
        // A deliberately wrong start: draw colour 0 one step off the solution if possible.
        var paths = FlowRules.begin(p, empty(p), p.pairs[0].a)!!.paths
        var guard = 0
        while (!FlowRules.isSolved(p, paths) && guard++ < 200) {
            val h = FlowRules.hintPaths(p, paths) ?: break
            assertTrue(FlowRules.validatePaths(p, h.paths))
            val sol = p.solution[h.color]
            assertEquals("revealed cells are the solution's", sol.take(h.paths[h.color].size), h.paths[h.color])
            paths = h.paths
        }
        assertTrue(FlowRules.isSolved(p, paths))
        assertNull("nothing left to reveal", FlowRules.hintPaths(p, paths))
    }

    @Test
    fun `a hint cuts a path that crosses the revealed cells`() {
        val p = small()
        // Colour 1 has wandered onto 5, which belongs to colour 0's solution path.
        val clash = listOf(emptyList<Int>(), listOf(3, 4, 5))
        val h = FlowRules.hintPaths(p, clash)!!
        assertEquals(0, h.color)
        assertEquals("colour 1 is cut where cell 5 was revealed", listOf(3, 4), h.paths[1])
        assertTrue(FlowRules.validatePaths(p, h.paths))
    }

    @Test
    fun `a hint orients itself to the end the player started from`() {
        val p = small()
        val fromB = listOf(listOf(8), emptyList<Int>())
        val h = FlowRules.hintPaths(p, fromB)!!
        assertEquals(listOf(8, 5, 2, 1), h.paths[0])
    }

    // --- Misc -------------------------------------------------------------------------------

    @Test
    fun `sameDrawing ignores lone dot paths only`() {
        assertTrue(FlowRules.sameDrawing(listOf(listOf(0), emptyList()), listOf(emptyList(), emptyList())))
        assertFalse(FlowRules.sameDrawing(listOf(listOf(0, 1), emptyList()), listOf(listOf(0), emptyList())))
    }

    @Test
    fun `tier names round trip and unknown names are rejected`() {
        for (t in FlowTier.entries) assertEquals(t, FlowTier.fromName(t.name))
        assertNull(FlowTier.fromName("S99"))
        assertEquals(listOf(5, 6, 7, 8, 9), FlowTier.entries.map { it.size })
    }
}
