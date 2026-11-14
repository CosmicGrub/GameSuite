package com.gamesuite.games.sixball

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** An independent, deliberately naive implementation of the tilt: nudge balls one cell at a time until nothing moves. */
internal fun sbNaiveTilt(w: Int, h: Int, walls: Set<Int>, pos: List<Int>, dir: Tilt): List<Int> {
    val p = pos.toMutableList()
    var moved = true
    while (moved) {
        moved = false
        for (i in p.indices) {
            val x = p[i] % w + dir.dx
            val y = p[i] / w + dir.dy
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            val c = y * w + x
            if (c in walls || c in p) continue
            p[i] = c; moved = true
        }
    }
    return p
}

/** Shortest number of tilts by plain BFS over [List]s with the naive tilt, or -1 when unreachable within [limit] states. */
internal fun sbNaiveDistance(w: Int, h: Int, walls: Set<Int>, start: List<Int>, target: List<Int>, limit: Int = 400_000): Int {
    if (start == target) return 0
    val seen = HashSet<List<Int>>(); seen += start
    var frontier = listOf(start)
    var d = 0
    while (frontier.isNotEmpty() && seen.size < limit) {
        d++
        val next = ArrayList<List<Int>>()
        for (s in frontier) for (dir in Tilt.values()) {
            val t = sbNaiveTilt(w, h, walls, s, dir)
            if (t == s || !seen.add(t)) continue
            if (t == target) return d
            next += t
        }
        frontier = next
    }
    return -1
}

class SixBallRulesTest {
    private fun level(w: Int, h: Int, walls: List<Int>, start: List<Int>, target: List<Int>, par: Int = 0) =
        SixBallLevel("t", w, h, walls, start, target, par)

    private val open4 = level(4, 4, emptyList(), listOf(0, 2, 4, 5, 10, 13), listOf(0, 1, 2, 3, 4, 5))

    @Test fun tiltingRollsEveryBallUntilSomethingStopsItAndRowsPileUpInOrder() {
        // ball0 at 0 and ball1 at 2 in the top row; balls 2,3 in the second row; ball4 alone; ball5 in the bottom row
        val r = SixBallRules.tilt(open4, intArrayOf(0, 2, 4, 5, 10, 13), Tilt.RIGHT)
        assertArrayEquals(intArrayOf(2, 3, 6, 7, 11, 15), r)
        val d = SixBallRules.tilt(open4, intArrayOf(0, 2, 4, 5, 10, 13), Tilt.DOWN)
        // column 0: balls 0 and 2 pile on the floor (8, 12); column 1: ball5 already on the floor, ball3 above it (9);
        // column 2: ball4 falls to 14 and ball1 stacks on it at 10.
        assertArrayEquals(intArrayOf(8, 10, 12, 9, 14, 13), d)
    }

    @Test fun postsStopBallsAndAFullyJammedTiltIsNotAMove() {
        val posted = level(4, 4, listOf(3), listOf(0, 2, 4, 5, 10, 13), listOf(0, 1, 2, 4, 5, 6))
        val r = SixBallRules.tilt(posted, intArrayOf(0, 2, 4, 5, 10, 13), Tilt.RIGHT)
        assertEquals(listOf(1, 2, 6, 7, 11, 15), r.toList()) // top row: post at 3 holds ball1 at 2, ball0 stops at 1
        val settled = SixBallRules.tilt(open4, intArrayOf(0, 2, 4, 5, 10, 13), Tilt.RIGHT)
        assertArrayEquals(settled, SixBallRules.tilt(open4, settled, Tilt.RIGHT))
        assertEquals(SixBallRules.pack(settled), SixBallRules.pack(SixBallRules.tilt(open4, settled, Tilt.RIGHT)))
    }

    @Test fun theEngineAgreesWithANaiveSimulationOnRandomTrays() {
        val r = Random(3)
        repeat(1500) {
            val w = r.nextInt(3, 8); val h = r.nextInt(3, 8)
            val cells = (0 until w * h).shuffled(r)
            val nw = r.nextInt(0, (w * h - 7).coerceAtMost(10) + 1)
            val walls = cells.take(nw).toSet()
            val pos = cells.drop(nw).take(6)
            val lv = level(w, h, walls.toList(), pos, pos)
            for (dir in Tilt.values()) {
                assertEquals("$w x $h $walls $pos $dir", sbNaiveTilt(w, h, walls, pos, dir), SixBallRules.tilt(lv, pos.toIntArray(), dir).toList())
            }
        }
    }

    @Test fun packingRoundTrips() {
        val r = Random(4)
        repeat(500) {
            val p = IntArray(6) { r.nextInt(0, 64) }
            assertArrayEquals(p, SixBallRules.unpack(SixBallRules.pack(p)))
        }
    }

    @Test fun theSolverFindsTheShortestSolutionAndAgreesWithABruteForceSearch() {
        val r = Random(11)
        var checked = 0
        repeat(120) {
            val w = r.nextInt(3, 6); val h = r.nextInt(3, 6)
            val cells = (0 until w * h).shuffled(r)
            val nw = r.nextInt(0, (w * h - 7).coerceAtMost(8) + 1)
            val walls = cells.take(nw)
            val free = cells.drop(nw)
            val start = free.take(6)
            // the target is the result of a short random walk, so it is reachable
            var t = start
            repeat(r.nextInt(1, 7)) { t = SixBallRules.tilt(level(w, h, walls, t, t), t.toIntArray(), Tilt.values()[r.nextInt(4)]).toList() }
            val lv = level(w, h, walls, start, t)
            val path = SixBallRules.solve(lv)
            assertNotNull(path)
            assertEquals("$w x $h walls $walls start $start target $t", sbNaiveDistance(w, h, walls.toSet(), start, t), path!!.size)
            // replaying the path really lands on the target
            var p = start.toIntArray()
            for (m in path) p = SixBallRules.tilt(lv, p, m)
            assertTrue(SixBallRules.isSolved(lv, p))
            checked++
        }
        assertEquals(120, checked)
    }

    @Test fun anArrangementThatCannotBeReachedIsReportedAsADeadEnd() {
        val r = Random(5)
        var deadEnds = 0
        repeat(40) {
            val cells = (0 until 25).shuffled(r)
            val l = level(5, 5, cells.take(9), cells.drop(9).take(6), cells.drop(9).drop(6).take(6))
            val s = SixBallRules.solve(l)
            val dead = SixBallRules.isDeadEnd(l, l.start.toIntArray())
            if (dead) { deadEnds++; assertNull(s) } else if (s != null) {
                var p = l.start.toIntArray(); for (m in s) p = SixBallRules.tilt(l, p, m)
                assertTrue(SixBallRules.isSolved(l, p))
            }
        }
        assertTrue("random targets are usually unreachable", deadEnds > 10)
    }

    @Test fun anAlreadySolvedTrayNeedsNoTilts() {
        val l = level(4, 4, emptyList(), listOf(0, 1, 2, 3, 4, 5), listOf(0, 1, 2, 3, 4, 5))
        assertEquals(emptyList<Tilt>(), SixBallRules.solve(l))
        assertTrue(SixBallRules.isSolved(l, l.start.toIntArray()))
    }

    @Test fun replayRefusesInvalidNoOpAndPostSolveMoves() {
        val l = SixBallLevels.campaign(1)
        val path = SixBallRules.solve(l)!!.map { it.ordinal }
        val end = SixBallRules.replay(l, path)
        assertNotNull(end); assertTrue(SixBallRules.isSolved(l, end!!))
        assertNull(SixBallRules.replay(l, path + path.first()))           // a move after the tray is solved
        assertNull(SixBallRules.replay(l, listOf(7)))
        assertNull(SixBallRules.replay(l, listOf(-1)))
        val first = path.first()
        assertNull("a second identical tilt moves nothing", SixBallRules.replay(l, listOf(first, first)))
        assertArrayEquals(l.start.toIntArray(), SixBallRules.replay(l, emptyList()))
    }

    @Test fun starsAreThreeAtParTwoWithinHalfAgainAndOneOtherwise() {
        assertEquals(3, SixBallRules.stars(10, 10)); assertEquals(3, SixBallRules.stars(7, 10))
        assertEquals(2, SixBallRules.stars(11, 10)); assertEquals(2, SixBallRules.stars(15, 10))
        assertEquals(1, SixBallRules.stars(16, 10)); assertEquals(1, SixBallRules.stars(400, 10))
        assertEquals(3, SixBallRules.stars(3, 3)); assertEquals(2, SixBallRules.stars(4, 3)); assertEquals(1, SixBallRules.stars(5, 3))
    }

    @Test fun wellFormednessRejectsBrokenLevels() {
        val ok = level(4, 4, listOf(15), listOf(0, 1, 2, 3, 4, 5), listOf(6, 7, 8, 9, 10, 11), 3)
        assertTrue(ok.isWellFormed())
        assertFalse(ok.copy(width = 9).isWellFormed())
        assertFalse(ok.copy(start = listOf(0, 1, 2, 3, 4, 15)).isWellFormed())      // a ball on a post
        assertFalse(ok.copy(start = listOf(0, 0, 2, 3, 4, 5)).isWellFormed())       // two balls in a cell
        assertFalse(ok.copy(target = listOf(6, 7, 8, 9, 10, 40)).isWellFormed())    // off the tray
        assertFalse(ok.copy(start = listOf(0, 1, 2)).isWellFormed())
        assertFalse(ok.copy(walls = listOf(15, 15)).isWellFormed())
    }
}
