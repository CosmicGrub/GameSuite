package com.gamesuite.games.curling

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CurlingPhysicsTest {
    private fun lone(t: CurlingThrow) = CurlingPhysics.loneStop(t)

    @Test fun theSameThrowPlaysOutIdentically() {
        val stones = listOf(CurlingStone(8, 1, 10.0, 1420.0))
        val t = CurlingThrow(40, 500, 1, listOf(100, 400))
        val a = CurlingPhysics.simulate(stones, 0, 0, t)
        val b = CurlingPhysics.simulate(stones, 0, 0, t)
        assertEquals(a.stones, b.stones)
        assertEquals(a.steps, b.steps)
    }

    @Test fun moreWeightCarriesFurther() {
        var last = -1.0
        for (w in 0..1000 step 100) {
            val y = lone(CurlingThrow(0, w, 1))[1]
            assertTrue("weight $w stops at $y after $last", y > last)
            last = y
        }
    }

    @Test fun weightsReachTheHouseAndTheFullRangeCoversGuardsToTakeouts() {
        val short = lone(CurlingThrow(0, 100, 1))[1]
        val draw = lone(CurlingThrow(0, 360, 1))[1]
        val hard = lone(CurlingThrow(0, 900, 1))[1]
        assertTrue("lightest throw should be a hog-line failure, got $short", short < CurlingSheet.HOG_FAR)
        assertTrue("a draw weight should reach the house, got $draw", draw in CurlingSheet.HOG_FAR..CurlingSheet.BACK_Y + 100)
        assertTrue("a hard throw should run past the back line, got $hard", hard > CurlingSheet.BACK_Y)
    }

    @Test fun theHandleCurlsTheStoneTheWayItIsTurned() {
        val straight = lone(CurlingThrow(0, 370, 1))
        val left = lone(CurlingThrow(0, 370, -1))
        assertTrue("in-turn should drift right, x=${straight[0]}", straight[0] > 15)
        assertTrue("out-turn should drift left, x=${left[0]}", left[0] < -15)
        assertTrue("a draw should not curl absurdly far, x=${straight[0]}", straight[0] < 200)
        assertEquals(-straight[0], left[0], 1e-6)
    }

    @Test fun sweepingCarriesFurtherAndStraighter() {
        val plain = lone(CurlingThrow(0, 370, 1))
        val swept = lone(CurlingThrow(0, 370, 1, CurlingSheet.fullSweep))
        assertTrue("swept ${swept[1]} should go further than ${plain[1]}", swept[1] > plain[1] + 40)
        assertTrue("sweeping keeps it straighter", Math.abs(swept[0]) < Math.abs(plain[0]))
        val half = lone(CurlingThrow(0, 370, 1, listOf(0, 300)))
        assertTrue(half[1] > plain[1] && half[1] < swept[1])
    }

    @Test fun theSolverPutsADrawWhereItWasCalled() {
        for (curl in intArrayOf(-1, 1)) for (sweeps in listOf(emptyList<Int>(), CurlingSheet.fullSweep)) {
            for ((tx, ty) in listOf(0.0 to 1420.0, 60.0 to 1380.0, -90.0 to 1300.0)) {
                val t = CurlingPhysics.solveDraw(tx, ty, curl, sweeps)!!
                val stop = lone(t)
                assertEquals("y for ($tx,$ty) curl $curl", ty, stop[1], 8.0)
                assertEquals("x for ($tx,$ty) curl $curl", tx, stop[0], 6.0)
                assertTrue(CurlingSheet.inRange(t))
            }
        }
    }

    @Test fun aHeadOnTakeoutTransfersMostOfTheSpeed() {
        val target = CurlingStone(8, 1, 0.0, 1420.0)
        val line = CurlingPhysics.solveLine(0.0, 1420.0, 650, 1)!!
        val r = CurlingPhysics.simulate(listOf(target), 0, 0, line)
        assertTrue(r.hitSomething)
        val moved = r.stones.firstOrNull { it.id == 8 }
        assertTrue("target should be knocked on or out", moved == null || moved.y > 1450)
    }

    @Test fun energyNeverGrowsAndStonesNeverPassThroughEachOther() {
        val rnd = Random(4)
        repeat(30) {
            val stones = List(6) { CurlingStone(it, it % 2, rnd.nextDouble(-90.0, 90.0), 1250.0 + rnd.nextDouble(0.0, 330.0)) }
                .let { list -> list.filterIndexed { i, a -> list.take(i).none { Math.hypot(it.x - a.x, it.y - a.y) < 31 } } }
            val t = CurlingThrow(rnd.nextInt(-300, 300), rnd.nextInt(300, 1000), if (rnd.nextBoolean()) 1 else -1)
            val sim = CurlingSim(stones, 0, 9, t)
            var last = sim.kineticEnergy()
            var guard = 0
            while (sim.isMoving() && guard++ < CurlingPhysics.MAX_STEPS) {
                sim.step(false)
                val e = sim.kineticEnergy()
                assertTrue("energy grew $last -> $e", e <= last * (1 + 1e-9) + 1e-9)
                last = e
                for (i in 0 until sim.n) for (j in i + 1 until sim.n) if (sim.alive[i] && sim.alive[j]) {
                    val d = Math.hypot(sim.x[i] - sim.x[j], sim.y[i] - sim.y[j])
                    assertTrue("stones $i/$j overlap deeply ($d)", d > 2 * CurlingSheet.STONE_R - 6.0)
                }
            }
        }
    }

    @Test fun sideLinesAndTheBackLineTakeStonesOutAndShortStonesAreHogged() {
        val wide = CurlingPhysics.simulate(emptyList(), 0, 0, CurlingThrow(2200, 800, 1))
        assertFalse(wide.thrownInPlay)
        val fast = CurlingPhysics.simulate(emptyList(), 0, 0, CurlingThrow(0, 1000, 1))
        assertFalse("through the back line", fast.thrownInPlay)
        val short = CurlingPhysics.simulate(emptyList(), 0, 0, CurlingThrow(0, 100, 1))
        assertTrue(short.hogged)
        assertFalse(short.thrownInPlay)
        val good = CurlingPhysics.solveDraw(0.0, 1420.0, 1)!!
        val ok = CurlingPhysics.simulate(emptyList(), 0, 0, good)
        assertTrue(ok.thrownInPlay)
        assertFalse(ok.hogged)
    }

    @Test fun validationRejectsBadSweepsAndThrows() {
        assertTrue(CurlingSheet.validSweeps(emptyList()))
        assertTrue(CurlingSheet.validSweeps(listOf(0, 10, 20, 30)))
        assertFalse(CurlingSheet.validSweeps(listOf(10, 5)))
        assertFalse(CurlingSheet.validSweeps(listOf(0, 10, 5, 20)))
        assertFalse(CurlingSheet.validSweeps(listOf(0)))
        assertFalse(CurlingSheet.validSweeps(listOf(0, CurlingPhysics.MAX_STEPS + 1)))
        assertFalse(CurlingSheet.validSweeps(List(40) { it * 10 }))
        assertFalse(CurlingSheet.inRange(CurlingThrow(0, 1001, 1)))
        assertFalse(CurlingSheet.inRange(CurlingThrow(0, 500, 0)))
        assertFalse(CurlingSheet.inRange(CurlingThrow(3000, 500, 1)))
        assertTrue(CurlingSheet.inRange(CurlingThrow(-2200, 0, -1)))
    }

    @Test fun fuzzRandomThrowsTerminateAndKeepTheIceSane() {
        val rnd = Random(21)
        repeat(150) {
            var stones = emptyList<CurlingStone>()
            repeat(6) { k ->
                val sweeps = if (rnd.nextBoolean()) emptyList() else listOf(rnd.nextInt(0, 200), 300 + rnd.nextInt(0, 800))
                val t = CurlingThrow(rnd.nextInt(-600, 601), rnd.nextInt(0, 1001), if (rnd.nextBoolean()) 1 else -1, sweeps)
                val r = CurlingPhysics.simulate(stones, k % 2, (k % 2) * 8 + k / 2, t)
                assertTrue(r.steps <= CurlingPhysics.MAX_STEPS)
                assertEquals(r.stones.map { it.id }.toSet().size, r.stones.size)
                r.stones.forEach {
                    assertTrue(it.x.isFinite() && it.y.isFinite())
                    assertTrue(Math.abs(it.x) + CurlingSheet.STONE_R <= CurlingSheet.HALF_WIDTH + 1)
                    assertTrue(it.y - CurlingSheet.STONE_R >= CurlingSheet.HOG_FAR - 1)
                }
                stones = r.stones
            }
        }
    }

    @Test fun theLiveSteppedSimMatchesTheOneShotRunForTheSameSweeps() {
        val stones = listOf(CurlingStone(8, 1, 5.0, 1400.0), CurlingStone(9, 1, -30.0, 1500.0))
        val t = CurlingThrow(10, 520, -1, listOf(50, 200, 300, 450))
        val whole = CurlingPhysics.simulate(stones, 0, 0, t)
        val live = CurlingSim(stones, 0, 0, t)
        while (live.steps < CurlingPhysics.MAX_STEPS && live.isMoving()) live.step(CurlingSheet.sweptAt(t.sweeps, live.steps))
        val res = live.finish()
        assertEquals(whole.stones, res.stones)
    }

    @Test fun recordedFramesCoverTheSlide() {
        val r = CurlingPhysics.simulate(emptyList(), 0, 0, CurlingPhysics.solveDraw(0.0, 1420.0, 1)!!, record = true)
        assertTrue(r.frames.size > 30)
        assertEquals(1, r.ids.size)
        assertEquals(0f, r.frames.first()[1], 1e-3f)
    }
}
