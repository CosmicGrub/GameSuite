package com.gamesuite.games.morris

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MorrisEngineTest {

    private fun pos(
        p0: List<Int> = emptyList(), p1: List<Int> = emptyList(),
        hand0: Int = 0, hand1: Int = 0, side: Int = 0, quiet: Int = 0
    ): MorrisPosition {
        val pts = IntArray(24) { -1 }
        p0.forEach { pts[it] = 0 }
        p1.forEach { pts[it] = 1 }
        return MorrisPosition(pts, intArrayOf(hand0, hand1), side, quiet)
    }

    // ---- geometry ----

    @Test
    fun `adjacency matches the drawn lattice`() {
        val coords = MorrisBoard.COORDS
        fun connected(a: Int, b: Int): Boolean {
            val (ax, ay) = coords[a]
            val (bx, by) = coords[b]
            if (ax != bx && ay != by) return false
            // The centre of the board is not a point, so lines never run through it.
            val crossesCentre = (ax == 3 && bx == 3 && minOf(ay, by) < 3 && maxOf(ay, by) > 3) ||
                (ay == 3 && by == 3 && minOf(ax, bx) < 3 && maxOf(ax, bx) > 3)
            if (crossesCentre) return false
            return coords.indices.none { c ->
                c != a && c != b && coords[c].let { (cx, cy) ->
                    if (ax == bx) cx == ax && cy > minOf(ay, by) && cy < maxOf(ay, by)
                    else cy == ay && cx > minOf(ax, bx) && cx < maxOf(ax, bx)
                }
            }
        }
        for (a in 0 until 24) {
            val expected = (0 until 24).filter { it != a && connected(a, it) }
            assertEquals("neighbours of $a", expected, MorrisBoard.ADJACENT[a].toList())
        }
        assertEquals(32, MorrisBoard.ADJACENT.sumOf { it.size } / 2)
    }

    @Test
    fun `there are sixteen mills and every point is in two`() {
        assertEquals(16, MorrisBoard.MILLS.size)
        assertTrue(MorrisBoard.MILLS_OF.all { it.size == 2 })
        // Every mill is a straight line on the lattice.
        for (m in MorrisBoard.MILLS) {
            val c = m.map { MorrisBoard.COORDS[it] }
            assertTrue(c.map { it.first }.distinct().size == 1 || c.map { it.second }.distinct().size == 1)
        }
    }

    // ---- placing ----

    @Test
    fun `a closing placement must remove an opponent man that is not in a mill`() {
        val e = MorrisEngine()
        val p = pos(p0 = listOf(0, 1), p1 = listOf(5, 6), hand0 = 7, hand1 = 7)
        val closing = e.legalMoves(p).filter { it.to == 2 }
        assertEquals(setOf(5, 6), closing.map { it.remove }.toSet())
        // A placement that closes nothing carries no removal.
        assertTrue(e.legalMoves(p).filter { it.to == 10 }.all { it.remove == -1 })
    }

    @Test
    fun `men in a mill are protected unless every opponent man is in one`() {
        val e = MorrisEngine()
        val mixed = pos(p0 = listOf(0, 1), p1 = listOf(16, 17, 18, 12), hand0 = 7, hand1 = 5)
        assertEquals(setOf(12), e.legalMoves(mixed).filter { it.to == 2 }.map { it.remove }.toSet())
        val allInMill = pos(p0 = listOf(0, 1), p1 = listOf(16, 17, 18), hand0 = 7, hand1 = 6)
        assertEquals(setOf(16, 17, 18), e.legalMoves(allInMill).filter { it.to == 2 }.map { it.remove }.toSet())
    }

    @Test
    fun `a mill against an empty board removes nothing`() {
        val e = MorrisEngine()
        val p = pos(p0 = listOf(0, 1), hand0 = 7, hand1 = 9)
        assertEquals(listOf(MorrisMove(-1, 2, -1)), e.legalMoves(p).filter { it.to == 2 })
    }

    @Test
    fun `placing stops once the hand is empty and men then slide`() {
        val e = MorrisEngine()
        val p = pos(p0 = listOf(0, 9, 20, 5), p1 = listOf(3, 12, 22), hand0 = 0, hand1 = 0)
        val moves = e.legalMoves(p)
        assertTrue(moves.all { it.from >= 0 })
        for (m in moves) assertTrue(m.to in MorrisBoard.ADJACENT[m.from].toList())
        assertEquals(setOf(1, 7), moves.filter { it.from == 0 }.map { it.to }.toSet())
        assertEquals(setOf(1, 8, 10, 17), moves.filter { it.from == 9 }.map { it.to }.toSet())
        assertEquals(setOf(19, 21), moves.filter { it.from == 20 }.map { it.to }.toSet())
        assertEquals(setOf(4, 6, 13), moves.filter { it.from == 5 }.map { it.to }.toSet())
    }

    @Test
    fun `apply moves the man and updates hands, side and quiet counter`() {
        val e = MorrisEngine()
        val placed = e.apply(e.initial(0), MorrisMove(-1, 4))
        assertEquals(0, placed.points[4])
        assertEquals(8, placed.hand[0])
        assertEquals(1, placed.side)
        assertEquals(0, placed.quiet)
        val sliding = pos(p0 = listOf(0, 9, 20), p1 = listOf(3, 12, 22))
        val moved = e.apply(sliding, MorrisMove(0, 1))
        assertEquals(-1, moved.points[0])
        assertEquals(0, moved.points[1])
        assertEquals(1, moved.quiet)
    }

    // ---- flying ----

    @Test
    fun `a player down to three men flies when flying is on`() {
        val flyer = pos(p0 = listOf(0, 9, 20), p1 = listOf(3, 12, 22, 14))
        val on = MorrisEngine(9, true).legalMoves(flyer)
        assertEquals(3 * (24 - 7), on.size)
        val off = MorrisEngine(9, false).legalMoves(flyer)
        assertTrue(off.all { it.to in MorrisBoard.ADJACENT[it.from].toList() })
        assertTrue(off.size < on.size)
    }

    @Test
    fun `four men never fly`() {
        val p = pos(p0 = listOf(0, 9, 20, 5), p1 = listOf(3, 12, 22, 14))
        assertTrue(MorrisEngine().legalMoves(p).all { it.to in MorrisBoard.ADJACENT[it.from].toList() })
    }

    // ---- outcomes ----

    @Test
    fun `reducing the opponent to two men wins`() {
        val e = MorrisEngine()
        val p = pos(p0 = listOf(0, 1, 3), p1 = listOf(13, 22, 5), hand0 = 0, hand1 = 0)
        // Slide 3 -> 2 closes the outer top mill (0,1,2) and takes a man: 3 -> 2.
        val move = e.legalMoves(p).first { it.from == 3 && it.to == 2 && it.remove == 5 }
        val after = e.apply(p, move)
        assertEquals(0, e.outcome(after))
    }

    @Test
    fun `a player with no legal move loses unless flying frees them`() {
        val blockedP = pos(p0 = listOf(0, 2, 4), p1 = listOf(1, 3, 5, 7))
        assertEquals(1, MorrisEngine(9, false).outcome(blockedP))
        assertEquals(MorrisEngine.NONE, MorrisEngine(9, true).outcome(blockedP))
    }

    @Test
    fun `a long stretch without a mill is a draw`() {
        val p = pos(p0 = listOf(0, 9, 20, 5), p1 = listOf(3, 12, 22, 14), quiet = MorrisEngine.QUIET_LIMIT)
        assertEquals(MorrisEngine.DRAW, MorrisEngine().outcome(p))
        assertEquals(MorrisEngine.NONE, MorrisEngine().outcome(pos(p0 = listOf(0, 9, 20, 5), p1 = listOf(3, 12, 22, 14), quiet = 5)))
    }

    @Test
    fun `placement never counts men in hand as lost`() {
        val e = MorrisEngine()
        assertEquals(MorrisEngine.NONE, e.outcome(e.initial(0)))
        // Two men on the board but four in hand is still a live position.
        assertEquals(MorrisEngine.NONE, e.outcome(pos(p0 = listOf(0, 1), p1 = listOf(5, 6, 7), hand0 = 4, hand1 = 3)))
    }

    @Test
    fun `six men start with six in hand`() {
        val e = MorrisEngine(men = 6)
        assertEquals(listOf(6, 6), e.initial(0).hand.toList())
        assertEquals(24, e.legalMoves(e.initial(0)).size)
        assertEquals(6, MorrisMen.fromOptionId("6"))
        assertEquals(9, MorrisMen.fromOptionId("nonsense"))
        assertEquals(9, MorrisMen.fromOptionId(null))
    }

    // ---- scripted full game ----

    @Test
    fun `a scripted game places, slides, mills and wins`() {
        val e = MorrisEngine(men = 6)
        var p = e.initial(0)
        fun play(m: MorrisMove): MorrisPosition {
            assertTrue("illegal $m", m in e.legalMoves(p))
            return e.apply(p, m)
        }
        // Dark builds the outer top row while light plays in the inner ring.
        p = play(MorrisMove(-1, 0)); p = play(MorrisMove(-1, 16))
        p = play(MorrisMove(-1, 1)); p = play(MorrisMove(-1, 18))
        p = play(MorrisMove(-1, 2, 16)) // mill 0-1-2, takes light's man on 16
        assertEquals(1, p.side)
        assertEquals(-1, p.points[16])
        assertEquals(1, e.count(p, 1))
        assertEquals(5, e.total(p, 1)) // 1 on board + 4 in hand
    }

    // ---- search ----

    @Test
    fun `every level returns a legal move`() {
        val e = MorrisEngine()
        val rng = Random(7)
        var p = e.initial(0)
        repeat(60) { ply ->
            if (e.outcome(p) != MorrisEngine.NONE) return
            val level = CpuDifficulty.values()[ply % 3]
            val m = e.chooseMove(p, level, rng, timeBudgetMs = 40)
            assertNotNull(m)
            assertTrue("$level played an illegal move $m", m in e.legalMoves(p))
            p = e.apply(p, m!!)
        }
    }

    @Test
    fun `the cpu takes a mill that is on offer and blocks one that is threatened`() {
        val e = MorrisEngine()
        val win = pos(p0 = listOf(0, 1), p1 = listOf(5, 6), hand0 = 7, hand1 = 7)
        val m = e.chooseMove(win, CpuDifficulty.HARD, Random(1), 300)!!
        assertEquals(2, m.to)
        assertTrue(m.remove >= 0)

        val threat = pos(p0 = listOf(12), p1 = listOf(16, 17), hand0 = 8, hand1 = 7)
        val block = e.chooseMove(threat, CpuDifficulty.HARD, Random(1), 300)!!
        assertEquals(18, block.to)
    }

    @Test
    fun `hint is deterministic and legal`() {
        val e = MorrisEngine()
        val p = pos(p0 = listOf(0, 1), p1 = listOf(5, 6), hand0 = 7, hand1 = 7)
        val a = e.hint(p, 200)
        assertNotNull(a)
        assertTrue(a in e.legalMoves(p))
        assertEquals(2, a!!.to)
    }

    @Test
    fun `hard beats easy over a short series`() {
        val e = MorrisEngine(men = 6, flying = true)
        var hardWins = 0
        var easyWins = 0
        for (game in 0 until 6) {
            val hardSeat = game % 2
            val rng = Random(100 + game)
            var p = e.initial(0)
            var plies = 0
            while (e.outcome(p) == MorrisEngine.NONE && plies < 400) {
                val level = if (p.side == hardSeat) CpuDifficulty.HARD else CpuDifficulty.EASY
                p = e.apply(p, e.chooseMove(p, level, rng, timeBudgetMs = 35)!!)
                plies++
            }
            val out = e.outcome(p)
            if (out == hardSeat) hardWins++ else if (out == 1 - hardSeat) easyWins++
        }
        assertTrue("hard $hardWins vs easy $easyWins", hardWins > easyWins)
        assertTrue("hard should win most games, won $hardWins", hardWins >= 4)
    }

    // ---- fuzz / property ----

    @Test
    fun `random legal play always terminates with consistent counts`() {
        val rng = Random(2024)
        for (men in MorrisMen.ALL) for (flying in listOf(true, false)) {
            val e = MorrisEngine(men, flying)
            repeat(40) {
                var p = e.initial(rng.nextInt(2))
                var plies = 0
                val removed = intArrayOf(0, 0)
                while (e.outcome(p) == MorrisEngine.NONE) {
                    val moves = e.legalMoves(p)
                    assertTrue(moves.isNotEmpty())
                    val m = moves[rng.nextInt(moves.size)]
                    val before = p
                    p = e.apply(p, m)
                    assertEquals(1 - before.side, p.side)
                    for (q in 0..1) {
                        assertTrue(e.count(p, q) + p.hand[q] <= men)
                        assertTrue(p.hand[q] >= 0)
                    }
                    if (m.remove >= 0) removed[1 - before.side]++
                    // A removal never targets the mover's own man or an empty point.
                    if (m.remove >= 0) assertEquals(1 - before.side, before.points[m.remove])
                    plies++
                    assertTrue("game did not end", plies < 3000)
                }
                // Material accounting: men = on board + in hand + removed.
                for (q in 0..1) assertEquals(men, e.count(p, q) + p.hand[q] + removed[q])
            }
        }
    }

    @Test
    fun `legal moves never include occupied destinations or a second removal`() {
        val rng = Random(5)
        val e = MorrisEngine()
        repeat(30) {
            var p = e.initial(0)
            repeat(80) {
                if (e.outcome(p) != MorrisEngine.NONE) return@repeat
                val moves = e.legalMoves(p)
                for (m in moves) {
                    assertEquals(-1, p.points[m.to])
                    if (m.from >= 0) assertEquals(p.side, p.points[m.from])
                }
                assertEquals(moves.size, moves.toSet().size)
                p = e.apply(p, moves[rng.nextInt(moves.size)])
            }
        }
    }

    @Test
    fun `fewer than three men always ends the game for that player`() {
        val e = MorrisEngine()
        assertFalse(e.outcome(pos(p0 = listOf(0, 1), p1 = listOf(5, 6, 7), hand0 = 0, hand1 = 0)) == MorrisEngine.NONE)
    }
}
