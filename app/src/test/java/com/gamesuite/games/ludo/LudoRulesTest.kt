package com.gamesuite.games.ludo

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class LudoRulesTest {
    private val two = listOf(0, 2)
    private val four = listOf(0, 1, 2, 3)

    private fun yardAll(seats: Int) = IntArray(seats * 4) { LudoBoard.YARD }

    // ---- geometry -----------------------------------------------------------------------

    @Test fun theRingHas52DistinctSquaresAndEachStepMovesToANeighbouringCell() {
        assertEquals(52, LudoBoard.RING.size)
        assertEquals(52, LudoBoard.RING.toSet().size)
        for (i in 0 until 52) {
            val a = LudoBoard.RING[i]; val b = LudoBoard.RING[(i + 1) % 52]
            // squares touch side by side, or corner to corner at the four inner turns of the cross
            assertEquals("step $i", 1, maxOf(abs(a.first - b.first), abs(a.second - b.second)))
        }
    }

    @Test fun startSquaresSitOnTheirColoursArmAndSafeSquaresAreEight() {
        assertEquals(setOf(0, 8, 13, 21, 26, 34, 39, 47), LudoBoard.SAFE)
        assertEquals(6 to 1, LudoBoard.RING[LudoBoard.startSquare(0)])
        assertEquals(1 to 8, LudoBoard.RING[LudoBoard.startSquare(1)])
        assertEquals(8 to 13, LudoBoard.RING[LudoBoard.startSquare(2)])
        assertEquals(13 to 6, LudoBoard.RING[LudoBoard.startSquare(3)])
    }

    @Test fun homeColumnsLeaveTheRingAtTheRightSquareAndNeverOverlap() {
        val ring = LudoBoard.RING.toSet()
        val seen = HashSet<Pair<Int, Int>>()
        for (color in 0..3) {
            // the last ring square a token uses must touch the first home-column square
            val last = LudoBoard.RING[LudoBoard.ringSquare(color, 50)!!]
            val first = LudoBoard.homeColumnCell(color, 51)
            assertEquals(1, abs(last.first - first.first) + abs(last.second - first.second))
            for (p in 51..55) {
                val c = LudoBoard.homeColumnCell(color, p)
                assertFalse(c in ring)
                assertTrue(seen.add(c))
            }
        }
        // yard slots and finish cells too
        for (color in 0..3) for (t in 0..3) {
            val y = LudoBoard.yardCell(color, t)
            assertFalse(y in ring); assertTrue(seen.add(y))
        }
    }

    @Test fun tablesUseOppositeCornersForTwoPlayers() {
        assertEquals(listOf(0, 2), LudoBoard.colorsFor(2))
        assertEquals(listOf(0, 1, 2), LudoBoard.colorsFor(3))
        assertEquals(listOf(0, 1, 2, 3), LudoBoard.colorsFor(4))
        var threw = false
        try { LudoBoard.colorsFor(5) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test fun cellOfCoversEveryProgressValueOfEveryColour() {
        for (color in 0..3) for (p in -1..56) {
            val c = LudoBoard.cellOf(color, 0, p)
            assertTrue(c.first in 0..14 && c.second in 0..14)
        }
    }

    // ---- movement -----------------------------------------------------------------------

    @Test fun aTokenInTheYardNeedsASixWhenThatRuleIsOn() {
        for (r in 1..5) assertNull(LudoRules.destination(-1, r, needSix = true, exactFinish = true))
        assertEquals(0, LudoRules.destination(-1, 6, true, true))
        for (r in 1..6) assertEquals(0, LudoRules.destination(-1, r, needSix = false, exactFinish = true))
    }

    @Test fun exactFinishRefusesOvershootAndTheLooseRuleAcceptsIt() {
        assertEquals(56, LudoRules.destination(53, 3, true, true))
        assertNull(LudoRules.destination(54, 3, true, true))
        assertEquals(56, LudoRules.destination(54, 3, true, false))
        assertNull("finished tokens never move", LudoRules.destination(56, 1, true, false))
        assertNull(LudoRules.destination(10, 0, true, true))
        assertNull(LudoRules.destination(10, 7, true, true))
    }

    @Test fun legalTokensListsExactlyTheMovableOnes() {
        val p = yardAll(2)
        p[0] = 10; p[1] = 56; p[2] = 54
        assertEquals(listOf(0), LudoRules.legalTokens(p, 0, 3, true, true))
        assertEquals(listOf(0, 2), LudoRules.legalTokens(p, 0, 2, true, true))
        assertEquals(listOf(0, 2, 3), LudoRules.legalTokens(p, 0, 6, false, false).filter { it != 1 })
    }

    @Test fun landingOnARivalSendsItBackToTheYardAndKeepsTheTurnRecord() {
        val p = yardAll(2)
        p[0] = 5                // seat 0, ring square 5
        p[4] = 35               // seat 1 (colour 2, start 26): (26+35)%52 = 9
        val r = LudoRules.apply(p, two, 0, 0, 4, true, true)
        assertEquals(9, r.to)
        assertEquals(listOf(1 to 0), r.captured)
        assertEquals(LudoBoard.YARD, r.positions[4])
        assertEquals(9, r.positions[0])
        assertFalse(r.finished); assertFalse(r.leftYard)
    }

    @Test fun safeSquaresShareTokensWithoutCapture() {
        val p = yardAll(2)
        p[0] = 4                // ring 4
        p[4] = 34               // (26+34)%52 = 8, a safe star
        val r = LudoRules.apply(p, two, 0, 0, 4, true, true) // lands on ring 8
        assertTrue(r.captured.isEmpty())
        assertEquals(34, r.positions[4])
    }

    @Test fun startSquareOfARivalIsSafeToo() {
        val p = yardAll(2)
        p[0] = 20              // seat 0 ring 20
        p[4] = 0               // seat 1 on its own start (ring 26)
        val r = LudoRules.apply(p, two, 0, 0, 6, true, true) // ring 26
        assertTrue(r.captured.isEmpty())
    }

    @Test fun ownTokensAreNeverCaptured() {
        val p = yardAll(2)
        p[0] = 5; p[1] = 9
        val r = LudoRules.apply(p, two, 0, 0, 4, true, true)
        assertTrue(r.captured.isEmpty())
        assertEquals(9, r.positions[1])
    }

    @Test fun everyOpposingTokenOnTheSquareIsCaptured() {
        val p = yardAll(3)
        p[0] = 5
        // seat 1 colour 1: ring 9 needs progress 48; seat 2 colour 2: ring 9 needs progress 35
        p[4] = 48; p[5] = 48         // two seat-1 tokens on ring 9
        p[8] = 35                    // a seat-2 token too
        val r = LudoRules.apply(p, listOf(0, 1, 2), 0, 0, 4, true, true)
        assertEquals(3, r.captured.size)
    }

    @Test fun homeColumnSquaresAreNeverCaptureSquares() {
        val p = yardAll(2)
        p[0] = 52; p[4] = 51
        val r = LudoRules.apply(p, two, 0, 0, 1, true, true)
        assertTrue(r.captured.isEmpty())
    }

    @Test fun leavingTheYardPutsTheTokenOnItsStartSquare() {
        val p = yardAll(2)
        val r = LudoRules.apply(p, two, 1, 2, 6, true, true)
        assertTrue(r.leftYard)
        assertEquals(0, r.positions[1 * 4 + 2])
    }

    @Test fun finishingIsReportedAndFourFinishedTokensWin() {
        val p = yardAll(2)
        p[0] = 56; p[1] = 56; p[2] = 56; p[3] = 53
        val r = LudoRules.apply(p, two, 0, 3, 3, true, true)
        assertTrue(r.finished)
        assertTrue(LudoRules.hasWon(r.positions, 0))
        assertFalse(LudoRules.hasWon(r.positions, 1))
        assertEquals(4, LudoRules.finishedCount(r.positions, 0))
    }

    @Test fun consistencyRejectsImpossiblePositions() {
        val ok = yardAll(2); ok[0] = 5; ok[4] = 35
        assertTrue(LudoRules.isConsistent(ok, two))
        val clash = yardAll(2); clash[0] = 9; clash[4] = 35 // rivals on one unsafe square
        assertFalse(LudoRules.isConsistent(clash, two))
        val range = yardAll(2); range[0] = 57
        assertFalse(LudoRules.isConsistent(range, two))
        assertFalse(LudoRules.isConsistent(IntArray(7), two))
        val safeShare = yardAll(2); safeShare[0] = 8; safeShare[4] = 34
        assertTrue(LudoRules.isConsistent(safeShare, two))
    }

    // ---- computer ------------------------------------------------------------------------

    @Test fun botPrefersACaptureAtMediumAndHard() {
        val p = yardAll(2)
        p[0] = 5     // can capture with a 4 (rival on ring 9)
        p[1] = 30    // or just advance
        p[4] = 35
        for (d in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            assertEquals("$d", 0, LudoBot.choose(p, two, 0, 4, true, true, d, Random(1)))
        }
    }

    @Test fun botBringsATokenOutOnASixWhenItCan() {
        val p = yardAll(2)
        p[0] = 20
        for (d in CpuDifficulty.values()) {
            val picks = (1..20).map { LudoBot.choose(p, two, 0, 6, true, true, d, Random(it)) }.toSet()
            assertTrue("$d $picks", picks.isNotEmpty() && (d == CpuDifficulty.EASY || picks.all { it in 1..3 }))
        }
    }

    @Test fun hardStepsOutOfDangerAndAvoidsLandingInFrontOfARival() {
        val p = yardAll(2)
        // seat 0 has token A at ring 20 (a rival at ring 17 threatens it) and token B at ring 40.
        p[0] = 20; p[1] = 40; p[2] = 56; p[3] = 56
        p[4] = 17 - 26 + 52          // seat 1 progress so that ring = 17
        // moving A by 2 -> ring 22 is still within 6 of ring 17 (gap 5): not an escape; moving B is calm.
        // moving A by 6 -> ring 26 is the rival's start square, which is safe: hard should take it.
        assertEquals(0, LudoBot.choose(p, two, 0, 6, true, true, CpuDifficulty.HARD, Random(1)))
    }

    @Test fun botNeverPicksAnIllegalTokenAndReturnsNullWhenStuck() {
        val rnd = Random(5)
        repeat(300) {
            val p = IntArray(8) { LudoBoard.YARD }
            for (i in 0..3) p[i] = listOf(-1, -1, 10, 33, 50, 53, 56)[rnd.nextInt(7)]
            val roll = rnd.nextInt(1, 7)
            for (d in CpuDifficulty.values()) {
                val legal = LudoRules.legalTokens(p, 0, roll, true, true)
                val c = LudoBot.choose(p, two, 0, roll, true, true, d, rnd)
                if (legal.isEmpty()) assertNull(c) else assertTrue(c in legal)
            }
        }
    }
}
