package com.gamesuite.games.fallback

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.kenken.KenKenCage
import com.gamesuite.games.kenken.KenKenFallbackPuzzles
import com.gamesuite.games.kenken.KenKenGame
import com.gamesuite.games.kenken.KenKenOperator
import com.gamesuite.games.nonogram.NonogramFallbackPuzzles
import com.gamesuite.games.nonogram.NonogramGame
import com.gamesuite.games.sudoku.SudokuFallbackPuzzles
import com.gamesuite.games.sudoku.SudokuGame
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Generation must never crash the app. A forced failure (the games' generationHook throws) is retried with new seeds
 * and then answered with a bundled puzzle; these tests prove the bundled data really is valid and unique, and that
 * the forced-failure paths still deal a playable board for every tier.
 */
class FallbackPuzzlesTest {
    private val ctx = GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "P1")), 0, LocalPassAndPlayTransport())
    private val boom: () -> Unit = { error("forced generation failure") }

    // ================= Sudoku =================

    private fun sudokuValid(g: IntArray): Boolean {
        for (i in 0 until 9) {
            val row = (0 until 9).map { g[i * 9 + it] }.toSet()
            val col = (0 until 9).map { g[it * 9 + i] }.toSet()
            val box = (0 until 9).map { g[((i / 3) * 3 + it / 3) * 9 + (i % 3) * 3 + it % 3] }.toSet()
            if (row != (1..9).toSet() || col != (1..9).toSet() || box != (1..9).toSet()) return false
        }
        return true
    }

    private fun sudokuCount(p: IntArray, cap: Int): Int {
        val g = p.copyOf()
        fun ok(pos: Int, d: Int): Boolean {
            val r = pos / 9; val c = pos % 9
            for (k in 0 until 9) {
                if (g[r * 9 + k] == d || g[k * 9 + c] == d) return false
                if (g[((r / 3) * 3 + k / 3) * 9 + (c / 3) * 3 + k % 3] == d) return false
            }
            return true
        }
        var count = 0
        fun go(): Unit {
            if (count >= cap) return
            var best = -1; var bestOpts = 10
            for (i in 0 until 81) if (g[i] == 0) {
                val n = (1..9).count { ok(i, it) }
                if (n < bestOpts) { bestOpts = n; best = i }
            }
            if (best < 0) { count++; return }
            for (d in 1..9) if (ok(best, d)) { g[best] = d; go(); g[best] = 0 }
        }
        go()
        return count
    }

    @Test fun everyBundledSudokuIsValidAndUnique() {
        for (tier in CpuDifficulty.values()) {
            val all = SudokuFallbackPuzzles.all(tier)
            assertTrue("$tier needs 2 or 3 puzzles, has ${all.size}", all.size in 2..3)
            for (f in all) {
                assertTrue(sudokuValid(f.solution))
                for (i in 0 until 81) assertTrue(f.puzzle[i] == 0 || f.puzzle[i] == f.solution[i])
                assertEquals("unique", 1, sudokuCount(f.puzzle, 2))
            }
        }
    }

    @Test fun sudokuForcedFailureStillDealsAValidBoardForEveryTier() {
        for (tier in CpuDifficulty.values()) for (seed in listOf<Long?>(null, 11L)) {
            val g = SudokuGame(nowMillis = { 0L }); g.init(ctx); g.difficulty = tier
            g.generationHook = boom
            g.startMatch(seed)
            val s = g.state.value!!
            val givens = IntArray(81) { s.cells[it].value ?: 0 }
            assertTrue(sudokuValid(s.solution.toIntArray()))
            assertEquals(1, sudokuCount(givens, 2))
            assertTrue(SudokuFallbackPuzzles.all(tier).any { it.solution.toList() == s.solution })
            assertTrue(s.cells.all { it.isGiven == (it.value != null) })
        }
    }

    @Test fun sudokuRecoversWhenOnlyTheFirstAttemptsFail() {
        var failures = 2
        val g = SudokuGame(nowMillis = { 0L }); g.init(ctx); g.difficulty = CpuDifficulty.EASY
        g.generationHook = { if (failures-- > 0) error("flaky") }
        g.startMatch(5L)
        assertTrue("a generated (not bundled) puzzle after two failures", SudokuFallbackPuzzles.all(CpuDifficulty.EASY).none { it.solution.toList() == g.state.value!!.solution })
    }

    @Test fun aDailySudokuIsStillTheSameForEveryoneWhenGenerationFails() {
        fun deal(): List<Int?> {
            val g = SudokuGame(nowMillis = { 0L }); g.init(ctx); g.difficulty = CpuDifficulty.MEDIUM
            g.generationHook = boom
            g.startMatch(77L)
            return g.state.value!!.cells.map { it.value }
        }
        assertEquals(deal(), deal())
    }

    // ================= KenKen =================

    private fun cageOk(c: KenKenCage, v: List<Int>): Boolean = when (c.operator) {
        KenKenOperator.ADD -> v.sum() == c.target
        KenKenOperator.MUL -> v.fold(1) { a, b -> a * b } == c.target
        KenKenOperator.SUB -> v.size == 2 && abs(v[0] - v[1]) == c.target
        KenKenOperator.DIV -> v.size == 2 && (v.max() % v.min() == 0) && v.max() / v.min() == c.target
        null -> v.size == 1 && v[0] == c.target
    }

    private fun latin(sol: List<Int>, n: Int): Boolean =
        (0 until n).all { i -> (0 until n).map { sol[i * n + it] }.toSet() == (1..n).toSet() && (0 until n).map { sol[it * n + i] }.toSet() == (1..n).toSet() }

    private fun tiles(cages: List<KenKenCage>, n: Int): Boolean =
        cages.flatMap { it.cellIndices }.sorted() == (0 until n * n).toList()

    private fun kenkenCount(n: Int, cages: List<KenKenCage>, cap: Int, budget: Long = 50_000_000L): Int {
        val cageOf = IntArray(n * n)
        cages.forEachIndexed { ci, c -> c.cellIndices.forEach { cageOf[it] = ci } }
        val g = IntArray(n * n)
        var count = 0
        var calls = 0L
        fun partialOk(ci: Int): Boolean {
            val c = cages[ci]
            val vals = c.cellIndices.map { g[it] }
            val filled = vals.filter { it != 0 }
            if (filled.size == vals.size) return cageOk(c, vals)
            return when (c.operator) {
                KenKenOperator.ADD -> filled.sum() < c.target
                KenKenOperator.MUL -> c.target % filled.fold(1) { a, b -> a * b } == 0
                else -> true
            }
        }
        fun go(pos: Int) {
            if (count >= cap) return
            check(++calls < budget) { "inconclusive" }
            if (pos == n * n) { count++; return }
            val r = pos / n; val c = pos % n
            for (d in 1..n) {
                if ((0 until c).any { g[r * n + it] == d } || (0 until r).any { g[it * n + c] == d }) continue
                g[pos] = d
                if (partialOk(cageOf[pos])) go(pos + 1)
                g[pos] = 0
            }
        }
        go(0)
        return count
    }

    private fun kenkenSpecChecks(size: Int, sol: List<Int>, cages: List<KenKenCage>) {
        assertTrue(latin(sol, size))
        assertTrue(tiles(cages, size))
        for (c in cages) assertTrue("cage ${c.id} clue matches", cageOk(c, c.cellIndices.map { sol[it] }))
        assertEquals("unique", 1, kenkenCount(size, cages, 2))
    }

    @Test fun everyBundledKenKenIsValidAndUnique() {
        val sizes = mapOf(CpuDifficulty.EASY to 4, CpuDifficulty.MEDIUM to 6, CpuDifficulty.HARD to 9)
        for (tier in CpuDifficulty.values()) {
            val all = KenKenFallbackPuzzles.all(tier)
            assertTrue("$tier needs 2 or 3 puzzles, has ${all.size}", all.size in 2..3)
            for (f in all) {
                assertEquals(sizes[tier], f.size)
                kenkenSpecChecks(f.size, f.solution.toList(), f.cages)
            }
        }
    }

    @Test fun kenKenForcedFailureStillDealsAValidPuzzleForEveryTier() {
        for (tier in CpuDifficulty.values()) for (seed in listOf<Long?>(null, 9L)) {
            val g = KenKenGame(nowMillis = { 0L }); g.init(ctx); g.difficulty = tier
            g.generationHook = boom
            g.startMatch(seed)
            val s = g.state.value!!
            kenkenSpecChecks(s.size, s.solution, s.cages)
            assertTrue(KenKenFallbackPuzzles.all(tier).any { it.solution.toList() == s.solution })
        }
    }

    @Test fun aDailyKenKenIsStillTheSameForEveryoneWhenGenerationFails() {
        fun deal(): List<Int> {
            val g = KenKenGame(nowMillis = { 0L }); g.init(ctx); g.difficulty = CpuDifficulty.MEDIUM
            g.generationHook = boom
            g.startMatch(77L)
            return g.state.value!!.solution
        }
        assertEquals(deal(), deal())
    }

    // ================= Nonogram =================

    private fun clues(line: List<Boolean>): List<Int> {
        val out = mutableListOf<Int>(); var run = 0
        for (b in line) if (b) run++ else { if (run > 0) out += run; run = 0 }
        if (run > 0) out += run
        return out
    }

    /** All fillings of a line of [n] cells that match [clue] and the known cells (1 filled, 0 empty, -1 unknown). */
    private fun lineOptions(clue: List<Int>, known: IntArray): List<IntArray> {
        val n = known.size
        val out = mutableListOf<IntArray>()
        val cur = IntArray(n)
        fun place(block: Int, from: Int) {
            if (block == clue.size) {
                for (i in from until n) cur[i] = 0
                if ((0 until n).all { known[it] == -1 || known[it] == cur[it] }) out += cur.copyOf()
                return
            }
            val rest = clue.drop(block + 1).sum() + (clue.size - block - 1)
            for (start in from..(n - clue[block] - rest)) {
                for (i in from until start) cur[i] = 0
                for (i in start until start + clue[block]) cur[i] = 1
                var next = start + clue[block]
                if (block < clue.size - 1) { cur[next] = 0; next++ }
                place(block + 1, next)
            }
        }
        place(0, 0)
        return out
    }

    /** Propagates line logic; returns false on contradiction. */
    private fun propagate(grid: IntArray, n: Int, rows: List<List<Int>>, cols: List<List<Int>>): Boolean {
        var changed = true
        while (changed) {
            changed = false
            for (isRow in listOf(true, false)) for (i in 0 until n) {
                val known = IntArray(n) { if (isRow) grid[i * n + it] else grid[it * n + i] }
                val opts = lineOptions(if (isRow) rows[i] else cols[i], known)
                if (opts.isEmpty()) return false
                for (k in 0 until n) {
                    if (known[k] != -1) continue
                    val v = opts[0][k]
                    if (opts.all { it[k] == v }) {
                        if (isRow) grid[i * n + k] = v else grid[k * n + i] = v
                        changed = true
                    }
                }
            }
        }
        return true
    }

    /** Returns (solutions found up to cap, solvedByLinesAlone). */
    private fun nonogramCount(n: Int, rows: List<List<Int>>, cols: List<List<Int>>, cap: Int): Pair<Int, Boolean> {
        var count = 0
        var lineOnly = false
        fun go(start: IntArray, top: Boolean) {
            if (count >= cap) return
            val g = start.copyOf()
            if (!propagate(g, n, rows, cols)) return
            val pos = g.indexOfFirst { it == -1 }
            if (pos < 0) { count++; if (top) lineOnly = true; return }
            for (v in 0..1) { val h = g.copyOf(); h[pos] = v; go(h, false) }
        }
        go(IntArray(n * n) { -1 }, true)
        return count to lineOnly
    }

    private fun nonogramChecks(tier: CpuDifficulty, size: Int, solution: List<Boolean>) {
        assertEquals(size * size, solution.size)
        val rows = (0 until size).map { r -> clues((0 until size).map { solution[r * size + it] }) }
        val cols = (0 until size).map { c -> clues((0 until size).map { solution[it * size + c] }) }
        val (count, lineOnly) = nonogramCount(size, rows, cols, 2)
        assertEquals("unique", 1, count)
        if (tier != CpuDifficulty.HARD) assertTrue("$tier must be line-solvable", lineOnly)
    }

    @Test fun everyBundledNonogramIsUniqueAndLineSolvableWhereRequired() {
        val sizes = mapOf(CpuDifficulty.EASY to 5, CpuDifficulty.MEDIUM to 10, CpuDifficulty.HARD to 15)
        for (tier in CpuDifficulty.values()) {
            val all = NonogramFallbackPuzzles.all(tier)
            assertTrue("$tier needs 2 or 3 puzzles, has ${all.size}", all.size in 2..3)
            for (f in all) {
                assertEquals(sizes[tier], f.size)
                nonogramChecks(tier, f.size, f.solution)
            }
        }
    }

    @Test fun nonogramForcedFailureStillDealsAValidPictureForEveryTier() {
        for (tier in CpuDifficulty.values()) for (seed in listOf<Long?>(null, 9L)) {
            val g = NonogramGame(nowMillis = { 0L }); g.init(ctx); g.difficulty = tier
            g.generationHook = boom
            g.startMatch(seed)
            val s = g.state.value!!
            nonogramChecks(tier, s.size, s.solution)
            assertTrue(NonogramFallbackPuzzles.all(tier).any { it.solution == s.solution })
        }
    }

    @Test fun aDailyNonogramIsStillTheSameForEveryoneWhenGenerationFails() {
        fun deal(): List<Boolean> {
            val g = NonogramGame(nowMillis = { 0L }); g.init(ctx); g.difficulty = CpuDifficulty.MEDIUM
            g.generationHook = boom
            g.startMatch(77L)
            return g.state.value!!.solution
        }
        assertEquals(deal(), deal())
    }
}
