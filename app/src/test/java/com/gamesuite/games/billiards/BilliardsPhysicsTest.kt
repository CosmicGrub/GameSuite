package com.gamesuite.games.billiards

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BilliardsPhysicsTest {
    private fun empty() = Triple(DoubleArray(16), DoubleArray(16), BooleanArray(16))

    private fun place(vararg balls: Triple<Int, Double, Double>): Triple<DoubleArray, DoubleArray, BooleanArray> {
        val (x, y, on) = empty()
        for ((id, bx, by) in balls) { x[id] = bx; y[id] = by; on[id] = true }
        return Triple(x, y, on)
    }

    @Test fun theSameShotFromTheSamePositionsIsBitIdentical() {
        val (x, y, on) = BilliardsRules.rack(BilliardsVariant.EIGHT, Random(5))
        val shot = Shot(0.013, 1.0, 0.3, -0.4, 25.0, 25.0)
        val a = BilliardsPhysics.simulate(x, y, on, shot)
        val b = BilliardsPhysics.simulate(x, y, on, shot)
        assertArrayEquals(a.x, b.x, 0.0)
        assertArrayEquals(a.y, b.y, 0.0)
        assertEquals(a.steps, b.steps)
        assertEquals(a.events.size, b.events.size)
        assertEquals(a.pocketed, b.pocketed)
    }

    @Test fun inputsAreNeverModified() {
        val (x, y, on) = BilliardsRules.rack(BilliardsVariant.EIGHT, Random(1))
        val cx = x.copyOf(); val cy = y.copyOf(); val co = on.copyOf()
        BilliardsPhysics.simulate(x, y, on, Shot(0.0, 1.0, 0.0, 0.0, 25.0, 25.0))
        assertArrayEquals(cx, x, 0.0); assertArrayEquals(cy, y, 0.0); assertTrue(co.contentEquals(on))
    }

    @Test fun aStraightHitSendsTheObjectBallOnAndStopsTheCueBall() {
        val (x, y, on) = place(Triple(0, 30.0, 25.0), Triple(1, 50.0, 25.0))
        val r = BilliardsPhysics.simulate(x, y, on, Shot(0.0, 0.2, 0.0, 0.0, 30.0, 25.0))
        assertEquals(1, r.firstContact)
        assertTrue("object ball moved on ${r.x[1]}", r.x[1] > 55.0)
        assertTrue("cue ball nearly stopped at the contact point", r.x[0] > 45.0 && r.x[0] < 49.0)
        assertEquals(25.0, r.y[1], 1e-6)
    }

    @Test fun followAndDrawMoveTheCueBallForwardAndBack() {
        fun finalCueX(spin: Double): Double {
            val (x, y, on) = place(Triple(0, 30.0, 25.0), Triple(1, 50.0, 25.0))
            return BilliardsPhysics.simulate(x, y, on, Shot(0.0, 0.35, 0.0, spin, 30.0, 25.0)).x[0]
        }
        val draw = finalCueX(-1.0); val none = finalCueX(0.0); val follow = finalCueX(1.0)
        assertTrue("draw $draw < none $none", draw < none - 3.0)
        assertTrue("follow $follow > none $none", follow > none + 3.0)
    }

    @Test fun englishChangesTheAngleOffACushionSymmetrically() {
        fun finalY(side: Double): Double {
            val (x, y, on) = place(Triple(0, 30.0, 25.0))
            return BilliardsPhysics.simulate(x, y, on, Shot(0.0, 0.45, side, 0.0, 30.0, 25.0)).y[0]
        }
        val left = finalY(-1.0); val none = finalY(0.0); val right = finalY(1.0)
        assertEquals(25.0, none, 1e-6)
        assertTrue("english moves the ball sideways", Math.abs(left - none) > 0.5 && Math.abs(right - none) > 0.5)
        assertTrue("opposite english goes the opposite way", (left - none) * (right - none) < 0)
        assertEquals(left - none, -(right - none), 1e-6)
    }

    @Test fun aBallDrivenIntoACornerFallsInThePocket() {
        val (x, y, on) = place(Triple(0, 16.0, 16.0), Triple(1, 8.0, 8.0))
        val r = BilliardsPhysics.simulate(x, y, on, Shot(Math.atan2(-8.0, -8.0), 0.45, 0.0, 0.0, 16.0, 16.0))
        assertTrue("ball 1 pocketed: ${r.pocketed}", 1 in r.pocketed)
        assertFalse(r.onTable[1])
        assertTrue(r.railOrPocketAfterContact)
    }

    @Test fun aBallAlongTheMiddleOfTheLongRailDropsInTheSidePocket() {
        val (x, y, on) = place(Triple(0, 30.0, 25.0), Triple(1, 40.0, 25.0))
        // knock ball 1 straight at the top side pocket: cue below it pushes it up
        val (x2, y2, on2) = place(Triple(0, 50.0, 40.0), Triple(1, 50.0, 25.0))
        val r = BilliardsPhysics.simulate(x2, y2, on2, Shot(-Math.PI / 2, 0.5, 0.0, 0.0, 50.0, 40.0))
        assertTrue("side pocket: ${r.pocketed}", 1 in r.pocketed)
        assertTrue(x.size == 16 && y.size == 16 && on.size == 16)
    }

    @Test fun aFullPowerShotNeverTunnelsThroughALoneBallOrACushion() {
        val (x, y, on) = place(Triple(0, 5.0, 25.0), Triple(1, 80.0, 25.0))
        val r = BilliardsPhysics.simulate(x, y, on, Shot(0.0, 1.0, 0.0, 0.0, 5.0, 25.0))
        assertEquals(1, r.firstContact)
        for (i in 0..1) {
            if (r.onTable[i]) assertTrue(r.x[i] in 1.5..98.5 && r.y[i] in 1.5..48.5)
        }
    }

    @Test fun randomShotsAlwaysTerminateKeepBallsOnTheClothAndConserveThem() {
        val rnd = Random(2024)
        var shots = 0
        repeat(120) { game ->
            val variant = if (game % 2 == 0) BilliardsVariant.EIGHT else BilliardsVariant.NINE
            var (x, y, on) = BilliardsRules.rack(variant, rnd)
            repeat(4) {
                val before = on.count { it }
                val shot = Shot(rnd.nextDouble(-Math.PI, Math.PI), rnd.nextDouble(0.02, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), x[0], y[0])
                val r = BilliardsPhysics.simulate(x, y, on, shot)
                shots++
                assertTrue("terminates", r.steps < BilliardsPhysics.MAX_STEPS)
                val pocketedNow = r.pocketed.size
                assertEquals("balls are conserved", before, r.onTable.count { it } + pocketedNow)
                for (i in 0 until 16) {
                    assertTrue(r.x[i].isFinite() && r.y[i].isFinite())
                    if (r.onTable[i]) {
                        assertTrue("ball $i on the cloth: ${r.x[i]},${r.y[i]}", r.x[i] in 1.5 - 1e-9..98.5 + 1e-9 && r.y[i] in 1.5 - 1e-9..48.5 + 1e-9)
                    }
                }
                // at rest no two balls overlap by more than a hair
                for (i in 0 until 16) for (j in i + 1 until 16) if (r.onTable[i] && r.onTable[j]) {
                    val d = Math.hypot(r.x[i] - r.x[j], r.y[i] - r.y[j])
                    assertTrue("balls $i and $j overlap ($d)", d > 2 * 1.5 - 0.35)
                }
                x = r.x; y = r.y; on = r.onTable
                if (!on[0]) { val s = BilliardsRules.freeSpot(25.0, 25.0, x, y, on); x[0] = s.first; y[0] = s.second; on[0] = true }
            }
        }
        assertTrue(shots == 480)
    }

    @Test fun framesAreRecordedAndTheLastOneIsTheFinalPosition() {
        val (x, y, on) = BilliardsRules.rack(BilliardsVariant.EIGHT, Random(3))
        val r = BilliardsPhysics.simulate(x, y, on, Shot(0.0, 0.9, 0.0, 0.0, 25.0, 25.0), frameEvery = 4)
        val frames = r.frames!!
        assertTrue(frames.size > 10)
        val last = frames.last()
        for (i in 0 until 16) assertEquals(r.x[i].toFloat(), last[i * 2], 1e-6f)
    }

    @Test fun theBreakFromTheHeadSpotMovesTheRackAndUsuallyDropsABall() {
        var broke = 0
        for (seed in 1..20) {
            val (x, y, on) = BilliardsRules.rack(BilliardsVariant.EIGHT, Random(seed))
            val r = BilliardsPhysics.simulate(x, y, on, Shot(0.0, 1.0, 0.0, 0.0, 25.0, 25.0))
            if (r.objectBallsOnRails.size >= 4 || r.pocketed.any { it != 0 }) broke++
        }
        assertTrue("a full-power break is legal most of the time ($broke/20)", broke >= 12)
    }
}
