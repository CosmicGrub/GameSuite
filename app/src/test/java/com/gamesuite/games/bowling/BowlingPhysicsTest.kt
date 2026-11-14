package com.gamesuite.games.bowling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BowlingPhysicsTest {
    private val all = BowlingLane.ALL_PINS
    private fun down(shot: BowlShot, mask: Int = all, bumpers: Boolean = false) =
        BowlingLane.pinCount(mask) - BowlingLane.pinCount(BowlingPhysics.simulate(shot, mask, bumpers).standing)

    @Test fun thePinDeckIsARegulationTriangle() {
        assertEquals(10, BowlingLane.PIN_X.size)
        assertEquals(0.0, BowlingLane.PIN_X[0], 1e-9)
        assertEquals(-BowlingLane.PIN_X[9], BowlingLane.PIN_X[6], 1e-9)
        assertEquals(0.3048, BowlingLane.PIN_X[2] - BowlingLane.PIN_X[1], 1e-9)
        assertEquals(BowlingLane.PIN_Y[1], BowlingLane.PIN_Y[2], 1e-9)
        // every pin touching distance: neighbours are one foot apart
        val dx = BowlingLane.PIN_X[1] - BowlingLane.PIN_X[0]
        val dy = BowlingLane.PIN_Y[1] - BowlingLane.PIN_Y[0]
        assertEquals(0.3048, Math.sqrt(dx * dx + dy * dy), 0.001)
    }

    @Test fun theSameShotGivesTheSamePinsEveryTime() {
        val shot = BowlShot(0.2, -0.012, 7.5, 0.5)
        val a = BowlingPhysics.simulate(shot, all, false, record = true)
        val b = BowlingPhysics.simulate(shot, all, false, record = true)
        assertEquals(a.standing, b.standing)
        assertEquals(a.frames.size, b.frames.size)
        assertEquals(a.frames.last().px.toList(), b.frames.last().px.toList())
        assertTrue(a.frames.size > 100)
    }

    @Test fun theBallIsInTheGutterWhenItLeavesTheLane() {
        val r = BowlingPhysics.simulate(BowlShot(0.5, 0.03, 7.5, 0.0), all, false)
        assertTrue(r.gutter)
        assertEquals(all, r.standing)
        val left = BowlingPhysics.simulate(BowlShot(-0.5, -0.03, 7.5, 0.0), all, false)
        assertTrue(left.gutter); assertEquals(all, left.standing)
    }

    @Test fun bumpersKeepTheBallOnTheLane() {
        val shot = BowlShot(0.5, 0.03, 7.5, 0.0)
        val r = BowlingPhysics.simulate(shot, all, true)
        assertFalse(r.gutter)
        val path = BowlingPhysics.path(shot, true)
        assertTrue(path.all { Math.abs(it.first) <= BowlingLane.HALF_W })
    }

    @Test fun aBallStraightDownTheMiddleHitsTheHeadPinAndTopplesSeveral() {
        val n = down(BowlShot(0.0, 0.0, 7.5, 0.0))
        assertTrue("head-on knocked $n", n in 5..10)
    }

    @Test fun pinsAlreadyDownCannotBeKnockedAndNothingStandingStaysEmpty() {
        val mask = 0b0000000111 // pins 1, 2, 3
        val r = BowlingPhysics.simulate(BowlShot(0.0, 0.0, 7.5, 0.0), mask, false)
        assertEquals(0, r.standing and mask.inv())
        assertEquals(0, BowlingPhysics.simulate(BowlShot(0.0, 0.0, 7.5, 0.0), 0, false).standing)
    }

    @Test fun pathHooksTowardTheSpin() {
        val straight = BowlingPhysics.path(BowlShot(0.0, 0.0, 7.5, 0.0), false)
        val right = BowlingPhysics.path(BowlShot(0.0, 0.0, 7.5, 1.0), false)
        val left = BowlingPhysics.path(BowlShot(0.0, 0.0, 7.5, -1.0), false)
        assertEquals(0.0, straight.last().first, 1e-9)
        assertTrue("right hook ends right: ${right.last().first}", right.last().first > 0.2)
        assertTrue(left.last().first < -0.2)
        assertEquals(right.last().first, -left.last().first, 1e-6)
        // the first third of the lane is skid: no sideways drift
        assertTrue(right.filter { it.second < BowlingLane.BREAK_Y }.all { Math.abs(it.first) < 1e-9 })
        assertTrue(right.zipWithNext().all { (a, b) -> b.second >= a.second })
    }

    @Test fun aSlowerBallHooksMore() {
        val fast = BowlingPhysics.path(BowlShot(0.0, 0.0, 9.0, 1.0), false).last().first
        val slow = BowlingPhysics.path(BowlShot(0.0, 0.0, 6.0, 1.0), false).last().first
        assertTrue(slow > fast)
    }

    @Test fun shotValidityAndClamping() {
        assertTrue(BowlShot(0.0, 0.0, 7.0, 0.0).isValid())
        assertFalse(BowlShot(Double.NaN, 0.0, 7.0, 0.0).isValid())
        assertFalse(BowlShot(0.9, 0.0, 7.0, 0.0).isValid())
        assertFalse(BowlShot(0.0, 0.5, 7.0, 0.0).isValid())
        assertFalse(BowlShot(0.0, 0.0, 30.0, 0.0).isValid())
        assertFalse(BowlShot(0.0, 0.0, 7.0, 2.0).isValid())
        assertTrue(BowlShot(9.0, 9.0, 99.0, 9.0).clamped().isValid())
    }

    @Test fun splitDetection() {
        fun m(vararg pins: Int) = pins.fold(0) { acc, p -> acc or (1 shl (p - 1)) }
        assertTrue(BowlingLane.isSplit(m(7, 10)))
        assertTrue(BowlingLane.isSplit(m(4, 6)))
        assertTrue(BowlingLane.isSplit(m(5, 7)))
        assertFalse(BowlingLane.isSplit(m(2, 3)))
        assertFalse(BowlingLane.isSplit(m(4, 7)))
        assertFalse(BowlingLane.isSplit(m(1, 7, 10)))
        assertFalse(BowlingLane.isSplit(m(10)))
        assertFalse(BowlingLane.isSplit(0))
        assertEquals(listOf(7, 10), BowlingLane.pinNumbers(m(10, 7)))
    }

    @Test fun aGoodHookIntoThePocketStrikesOftenButNotAlways() {
        // right-handed: start right, angle left, hook right back into the 1-3 pocket
        val plan = BowlingBot.plan(all, false)
        var strikes = 0
        var pins = 0
        val rng = Random(10)
        val n = 80
        repeat(n) {
            val s = BowlingScatter.CPU_HARD.apply(plan, rng)
            val r = BowlingPhysics.simulate(s, all, false)
            val d = 10 - BowlingLane.pinCount(r.standing)
            pins += d
            if (r.standing == 0) strikes++
        }
        val rate = strikes.toDouble() / n
        assertTrue("planned pocket shot strike rate $rate (avg pins ${pins.toDouble() / n}, plan $plan)", rate in 0.15..0.95)
        assertTrue("avg pins ${pins.toDouble() / n}", pins.toDouble() / n > 8.0)
    }

    @Test fun aWildlyScatteredHandStrikesRarely() {
        var strikes = 0
        val rng = Random(2)
        val plan = BowlingBot.plan(all, false)
        repeat(60) {
            val s = BowlingScatter(0.2, 0.02, 1.0, 0.5).apply(plan, rng)
            if (BowlingPhysics.simulate(s, all, false).standing == 0) strikes++
        }
        assertTrue("strikes $strikes of 60", strikes <= 12)
    }

    @Test fun theRackIsNeverIncreasedAndSpareShotsExist() {
        // the planner finds a shot that clears single pins when one exists
        for (pin in listOf(1, 5, 7, 10)) {
            val mask = 1 shl (pin - 1)
            val shot = BowlingBot.plan(mask, false)
            val r = BowlingPhysics.simulate(shot, mask, false)
            assertEquals("pin $pin should be makeable with a steady hand", 0, r.standing)
        }
        for (mask in listOf(0b1000001, 0b1001000000, 0b0001110000)) {
            val shot = BowlingBot.plan(mask, false)
            val r = BowlingPhysics.simulate(shot, mask, false)
            assertEquals(0, r.standing and mask.inv())
        }
    }
}
