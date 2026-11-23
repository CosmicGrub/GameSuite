package com.gamesuite.games.hareandhounds

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HoundsEngineTest {

    private fun pos(hounds: List<Int>, hare: Int, turn: Int = HoundsRole.HOUNDS, moves: Int = 0) =
        HoundsPosition(hounds.fold(0) { m, p -> m or (1 shl p) }, hare, turn, moves)

    // ---- geometry ----

    @Test
    fun `the board has eleven points with the classic lines`() {
        val adj = HoundsBoard.ADJACENT
        assertEquals(11, adj.size)
        for (a in 0 until 11) for (b in adj[a]) assertTrue("$a-$b not symmetric", a in adj[b])
        assertEquals(listOf(1, 2, 3), adj[0].toList())
        assertEquals(listOf(7, 8, 9), adj[10].toList())
        assertEquals(listOf(0, 2, 4, 5), adj[1].toList()) // top of the first column: apex, below, right, and a diagonal
        assertEquals(listOf(0, 1, 3, 5), adj[2].toList()) // no diagonals on the plain points
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8, 9), adj[5].toList()) // the centre sees eight
    }

    @Test
    fun `the start is hounds on the apex and beside it with the hare on the far apex`() {
        val p = HoundsEngine.initial()
        assertEquals(listOf(0, 1, 3), p.houndPoints())
        assertEquals(10, p.hare)
        assertEquals(HoundsRole.HOUNDS, p.turn)
        assertEquals(HoundsRole.NONE, HoundsEngine.winner(p))
    }

    // ---- movement ----

    @Test
    fun `hounds never step backwards but may move straight along a column`() {
        val moves = HoundsEngine.legalMoves(pos(listOf(5, 2, 9), 10))
        assertFalse(moves.any { it.from == 5 && HoundsBoard.COLS[it.to] < 2 })
        assertTrue(moves.contains(HoundsMove(5, 4))) // up in the same column
        assertTrue(moves.contains(HoundsMove(5, 6)))
        assertTrue(moves.contains(HoundsMove(5, 8)))
        assertFalse(moves.contains(HoundsMove(5, 1))) // diagonal back
        assertTrue(moves.contains(HoundsMove(2, 1))) // vertical in column 1
        // The apex hound can step right but a hound on column 1 cannot go back to the apex.
        assertFalse(moves.contains(HoundsMove(2, 0)))
    }

    @Test
    fun `the hare moves to any empty neighbour including backwards`() {
        val p = pos(listOf(5, 2, 9), 8, HoundsRole.HARE)
        val moves = HoundsEngine.legalMoves(p).map { it.to }.toSet()
        assertEquals(setOf(7, 10), moves) // point 8 sees 5, 7, 9 and 10; 5 and 9 hold hounds
        assertTrue(HoundsEngine.legalMoves(p).all { it.from == 8 })
    }

    @Test
    fun `apply switches the turn and counts only hound moves`() {
        val p = HoundsEngine.initial()
        val afterHound = HoundsEngine.apply(p, HoundsMove(1, 4))
        assertEquals(HoundsRole.HARE, afterHound.turn)
        assertEquals(1, afterHound.houndMoves)
        assertEquals(listOf(0, 3, 4), afterHound.houndPoints())
        val afterHare = HoundsEngine.apply(afterHound, HoundsMove(10, 8))
        assertEquals(HoundsRole.HOUNDS, afterHare.turn)
        assertEquals(1, afterHare.houndMoves)
        assertEquals(8, afterHare.hare)
    }

    // ---- results ----

    @Test
    fun `the hounds win by leaving the hare no move`() {
        val trapped = pos(listOf(7, 8, 9), 10, HoundsRole.HARE)
        assertEquals(HoundsRole.HOUNDS, HoundsEngine.winner(trapped))
        assertEquals("trapped", HoundsEngine.reasonOf(trapped))
    }

    @Test
    fun `the hare wins by getting past every hound`() {
        val loose = pos(listOf(5, 6, 9), 2, HoundsRole.HOUNDS)
        assertTrue(HoundsEngine.hasEscaped(loose))
        assertEquals(HoundsRole.HARE, HoundsEngine.winner(loose))
        assertEquals("escaped", HoundsEngine.reasonOf(loose))
        // Level with the rearmost hound is also past it: the hounds can no longer turn round.
        assertTrue(HoundsEngine.hasEscaped(pos(listOf(4, 5, 8), 6)))
        assertFalse(HoundsEngine.hasEscaped(pos(listOf(4, 5, 8), 7)))
    }

    @Test
    fun `the hare wins when the hounds run out of moves`() {
        val timeout = pos(listOf(1, 3, 5), 10, HoundsRole.HOUNDS, HoundsBoard.MAX_HOUND_MOVES)
        assertEquals(HoundsRole.HARE, HoundsEngine.winner(timeout))
        assertEquals("timeout", HoundsEngine.reasonOf(timeout))
        assertEquals(HoundsRole.NONE, HoundsEngine.winner(pos(listOf(1, 3, 5), 10, HoundsRole.HOUNDS, HoundsBoard.MAX_HOUND_MOVES - 1)))
    }

    @Test
    fun `stuck hounds lose`() {
        val stuck = pos(listOf(8, 9, 10), 7, HoundsRole.HOUNDS)
        assertEquals(HoundsRole.HARE, HoundsEngine.winner(stuck))
        // Stuck without having escaped: hare behind a wall it cannot be in. Build via hounds boxed by edge and hare.
        val boxed = pos(listOf(9, 10, 8), 5, HoundsRole.HOUNDS)
        assertEquals(HoundsRole.HARE, HoundsEngine.winner(boxed))
    }

    // ---- the exact solution ----

    @Test
    fun `with correct play the hounds win from the start well inside the move limit`() {
        val v = HoundsEngine.solve(HoundsEngine.initial())
        assertTrue("hounds should win, value $v", v > 0)
        val pliesToWin = 1000 - v
        assertTrue("took $pliesToWin plies", pliesToWin <= 2 * HoundsBoard.MAX_HOUND_MOVES - 4)
    }

    @Test
    fun `perfect play on both sides ends with the hounds trapping the hare`() {
        var p = HoundsEngine.initial()
        var plies = 0
        while (HoundsEngine.winner(p) == HoundsRole.NONE) {
            p = HoundsEngine.apply(p, HoundsEngine.bestMove(p)!!)
            plies++
            assertTrue(plies < 80)
        }
        assertEquals(HoundsRole.HOUNDS, HoundsEngine.winner(p))
        assertEquals("trapped", HoundsEngine.reasonOf(p))
    }

    // ---- cpu ----

    @Test
    fun `every level answers with a legal move for either side`() {
        val rng = Random(5)
        for (level in CpuDifficulty.values()) {
            var p = HoundsEngine.initial()
            var guard = 0
            while (HoundsEngine.winner(p) == HoundsRole.NONE && guard++ < 80) {
                val m = HoundsEngine.chooseMove(p, level, rng)
                assertNotNull(m)
                assertTrue("$level illegal $m", m in HoundsEngine.legalMoves(p))
                p = HoundsEngine.apply(p, m!!)
            }
        }
        assertNull(HoundsEngine.chooseMove(pos(listOf(7, 8, 9), 10, HoundsRole.HARE), CpuDifficulty.HARD))
    }

    private fun playout(houndsLevel: CpuDifficulty, hareLevel: CpuDifficulty, seed: Int): Int {
        val rng = Random(seed)
        var p = HoundsEngine.initial()
        while (HoundsEngine.winner(p) == HoundsRole.NONE) {
            val level = if (p.turn == HoundsRole.HOUNDS) houndsLevel else hareLevel
            p = HoundsEngine.apply(p, HoundsEngine.chooseMove(p, level, rng)!!)
        }
        return HoundsEngine.winner(p)
    }

    @Test
    fun `the levels are distinguishable - hard hounds always win, easy hounds often fail`() {
        val n = 40
        val hardVsEasy = (0 until n).count { playout(CpuDifficulty.HARD, CpuDifficulty.EASY, it) == HoundsRole.HOUNDS }
        val hardVsHard = (0 until n).count { playout(CpuDifficulty.HARD, CpuDifficulty.HARD, it) == HoundsRole.HOUNDS }
        val mediumVsEasy = (0 until n).count { playout(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, it) == HoundsRole.HOUNDS }
        val easyVsMedium = (0 until n).count { playout(CpuDifficulty.EASY, CpuDifficulty.MEDIUM, it) == HoundsRole.HOUNDS }
        val easyVsHard = (0 until n).count { playout(CpuDifficulty.EASY, CpuDifficulty.HARD, it) == HoundsRole.HOUNDS }
        assertEquals("hard hounds vs hard hare", n, hardVsHard)
        assertEquals("hard hounds vs easy hare", n, hardVsEasy)
        assertTrue("medium hounds vs easy hare: $mediumVsEasy/$n", mediumVsEasy >= n * 0.8)
        // The hare has a real chance against weak hounds, and the stronger the hare the better it does.
        assertTrue("easy hounds vs medium hare: $easyVsMedium/$n", easyVsMedium < n)
        assertTrue("easy hounds should not beat a perfect hare: $easyVsHard/$n", easyVsHard <= easyVsMedium + 5)
    }

    // ---- fuzz ----

    @Test
    fun `random play terminates and keeps three distinct hounds and one hare`() {
        val rng = Random(31)
        repeat(300) {
            var p = HoundsEngine.initial()
            var plies = 0
            while (HoundsEngine.winner(p) == HoundsRole.NONE) {
                val moves = HoundsEngine.legalMoves(p)
                assertTrue(moves.isNotEmpty())
                val m = moves[rng.nextInt(moves.size)]
                val before = p
                p = HoundsEngine.apply(p, m)
                assertEquals(3, Integer.bitCount(p.hounds))
                assertEquals(0, p.hounds and (1 shl p.hare))
                assertEquals(1 - before.turn, p.turn)
                if (before.turn == HoundsRole.HOUNDS) {
                    assertTrue(HoundsBoard.COLS[m.to] >= HoundsBoard.COLS[m.from])
                    assertEquals(before.houndMoves + 1, p.houndMoves)
                } else {
                    assertEquals(before.houndMoves, p.houndMoves)
                }
                assertTrue(++plies <= 2 * HoundsBoard.MAX_HOUND_MOVES + 2)
            }
        }
    }

    @Test
    fun `evaluate agrees with the result at the end and favours the side with the better position`() {
        assertTrue(HoundsEngine.evaluate(pos(listOf(7, 8, 9), 10, HoundsRole.HARE)) > 500)
        assertTrue(HoundsEngine.evaluate(pos(listOf(5, 6, 9), 2)) < -500)
        val start = HoundsEngine.evaluate(HoundsEngine.initial())
        val advanced = HoundsEngine.evaluate(pos(listOf(5, 4, 6), 10))
        assertTrue(advanced > start)
    }
}
