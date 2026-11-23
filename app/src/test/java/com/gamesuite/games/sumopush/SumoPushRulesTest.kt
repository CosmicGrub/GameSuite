package com.gamesuite.games.sumopush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** The physics with known values: drag, collisions, mass, dash, ring shrink, falling out, termination. */
class SumoPushRulesTest {
    private fun engine(players: Int = 2, powerUps: Boolean = false, relaxed: Boolean = false, arena: SumoArena = SumoArena.MEDIUM, seed: Int = 1) =
        SumoEngine(SumoConfig(players, arena, 3, powerUps, relaxed), Random(seed))

    private fun SumoEngine.run(n: Int): List<SumoEvent> { val all = ArrayList<SumoEvent>(); repeat(n) { all += step() }; return all }

    private fun place(s: Sumo, x: Double, y: Double, vx: Double = 0.0, vy: Double = 0.0) { s.x = x; s.y = y; s.vx = vx; s.vy = vy }

    @Test fun sumosStartOnTheirOwnSidesFacingTheMiddleAndIdleSumosStayPut() {
        val e = engine(4)
        val d = 0.55
        assertEquals(0.0 to d, e.sumos[0].x to e.sumos[0].y)
        assertEquals(0.0 to -d, e.sumos[1].x to e.sumos[1].y)
        assertEquals(-d to 0.0, e.sumos[2].x to e.sumos[2].y)
        assertEquals(d to 0.0, e.sumos[3].x to e.sumos[3].y)
        assertEquals(-1.0, e.sumos[0].facingY, 1e-9)
        assertEquals(1.0, e.sumos[2].facingX, 1e-9)
        e.run(200)
        assertEquals(d, e.sumos[0].y, 1e-12)
        assertTrue(e.alive() == 4 && !e.over)
    }

    @Test fun steeringFollowsTheAccelerationAndDragRecurrenceExactly() {
        val e = engine(arena = SumoArena.LARGE)
        e.setInput(0, 1.0, 0.0)
        var v = 0.0
        val k = 1.0 - SumoRules.DRAG * SumoRules.DT
        repeat(40) { v = (v + SumoRules.ACCEL * SumoRules.DT) * k }
        e.run(40)
        assertEquals(v, e.sumos[0].vx, 1e-12)
        assertEquals(0.0, e.sumos[0].vy, 1e-12)
        // And the terminal speed is the documented ACCEL / DRAG, less one step of drag.
        val terminal = SumoRules.ACCEL / SumoRules.DRAG * k
        assertTrue(v < terminal && v > terminal * 0.6)
    }

    @Test fun steeringInputIsClampedToUnitLength() {
        val e = engine()
        e.setInput(0, 3.0, 4.0)
        assertEquals(1.0, sqrt(e.sumos[0].inputX * e.sumos[0].inputX + e.sumos[0].inputY * e.sumos[0].inputY), 1e-12)
    }

    @Test fun aSumoPastTheRopeIsOutAndTheLastOneInTakesTheRound() {
        val e = engine()
        place(e.sumos[1], 0.0, -1.01)
        val ev = e.step()
        assertTrue(ev.any { it is SumoEvent.Fell && it.seat == 1 })
        assertFalse(e.sumos[1].alive)
        assertTrue(e.over)
        assertEquals(0, e.winner)
        assertTrue(e.step().isEmpty()) // a finished round ignores steps
    }

    @Test fun twoSumosFallingOnTheSameStepIsADraw() {
        val e = engine()
        place(e.sumos[0], 0.0, 1.01); place(e.sumos[1], 0.0, -1.01)
        e.step()
        assertTrue(e.over)
        assertEquals(-1, e.winner)
    }

    @Test fun anEqualMassCollisionConservesMomentumAndBouncesByTheRestitution() {
        val e = engine()
        val a = e.sumos[0]; val b = e.sumos[1]
        place(a, -0.1, 0.0, vx = 1.0); place(b, 0.1, 0.0)
        val k = 1.0 - SumoRules.DRAG * SumoRules.DT
        val preA = 1.0 * k // drag is applied before the collision is judged
        val moved = 1.0 * k * SumoRules.DT
        assertTrue(0.2 - moved < SumoRules.RADIUS * 2) // they do overlap after moving
        val ev = e.step()
        assertTrue(ev.any { it is SumoEvent.Hit })
        assertEquals(preA, a.vx + b.vx, 1e-12)
        assertEquals(SumoRules.RESTITUTION * preA, b.vx - a.vx, 1e-12)
        assertTrue(a.vx < b.vx)
        assertTrue(sqrt((b.x - a.x).let { it * it } + (b.y - a.y).let { it * it }) >= SumoRules.RADIUS * 2 - 1e-9) // pushed apart
    }

    @Test fun aHeavierSumoPushesALighterOneFurtherAndKeepsItsOwnWay() {
        fun shove(heavy: Boolean): Pair<Double, Double> {
            val e = engine()
            val a = e.sumos[0]; val b = e.sumos[1]
            if (heavy) a.heavyMs = 10_000
            place(a, -0.1, 0.0, vx = 1.0); place(b, 0.1, 0.0)
            e.step()
            return a.vx to b.vx
        }
        val equal = shove(false); val heavy = shove(true)
        assertTrue("the shoved sumo leaves faster when the pusher is heavy", heavy.second > equal.second)
        assertTrue("the heavy pusher keeps going forward", heavy.first > 0.0)
        assertTrue("the equal-mass pusher is stopped or bounced", equal.first < heavy.first)
    }

    @Test fun aDashingSumoCountsAsMuchHeavierWhileTheDashLasts() {
        val e = engine()
        val s = e.sumos[0]
        assertEquals(1.0, s.mass, 1e-12)
        s.dashMs = 100
        assertEquals(SumoRules.DASH_MASS_FACTOR, s.mass, 1e-12)
        s.heavyMs = 100
        assertEquals(SumoRules.DASH_MASS_FACTOR * SumoRules.HEAVY_MASS, s.mass, 1e-12)
    }

    @Test fun aTapDashesAtMinimumPowerAndAFullChargeAtMaximumThenTheCooldownBlocksTheNextOne() {
        val e = engine()
        val s = e.sumos[0] // faces up
        e.setCharging(0, true); e.setCharging(0, false)
        assertEquals(-SumoRules.DASH_MIN_SPEED, s.vy, 1e-12)
        assertEquals(SumoRules.DASH_ACTIVE_MS, s.dashMs)
        assertEquals(SumoRules.COOLDOWN_MS, s.cooldownMs)
        e.setCharging(0, true)
        assertFalse("cooldown blocks charging", s.charging)
        // Ride out the cooldown, then charge to the limit: it fires by itself with full power.
        val e2 = engine(); val t = e2.sumos[1]
        e2.setCharging(1, true)
        assertTrue(t.charging)
        val ev = e2.run(SumoRules.CHARGE_MAX_MS / SumoRules.STEP_MS + 2)
        val dash = ev.filterIsInstance<SumoEvent.Dash>().single()
        assertEquals(1.0, dash.charge, 1e-9)
        assertFalse(t.charging)
        assertTrue(t.vy > SumoRules.DASH_MIN_SPEED + SumoRules.DASH_EXTRA_SPEED * 0.5) // seat 1 faces down: the full-power dash is still carrying it
    }

    @Test fun halfAChargeIsHalfTheExtraSpeed() {
        val e = engine()
        e.setCharging(0, true)
        e.run(SumoRules.CHARGE_MAX_MS / SumoRules.STEP_MS / 2)
        val charge = e.sumos[0].chargeMs.toDouble() / SumoRules.CHARGE_MAX_MS
        val vBefore = e.sumos[0].vy
        e.setCharging(0, false)
        assertEquals(vBefore - (SumoRules.DASH_MIN_SPEED + SumoRules.DASH_EXTRA_SPEED * charge), e.sumos[0].vy, 1e-9)
        assertTrue(charge in 0.4..0.6)
    }

    @Test fun cooldownCountsDownToZeroAndTheDashIsReadyAgain() {
        val e = engine(arena = SumoArena.LARGE)
        e.setCharging(0, true); e.setCharging(0, false)
        e.run(SumoRules.COOLDOWN_MS / SumoRules.STEP_MS + 1)
        assertEquals(0, e.sumos[0].cooldownMs)
        if (e.sumos[0].alive) { e.setCharging(0, true); assertTrue(e.sumos[0].charging) }
    }

    @Test fun theRingStaysFullForTheGraceThenShrinksToItsMinimumAndRelaxedIsSlower() {
        val c = SumoConfig(arena = SumoArena.MEDIUM)
        assertEquals(1.0, SumoEngine.ringRadiusAt(c, 0), 1e-12)
        assertEquals(1.0, SumoEngine.ringRadiusAt(c, c.graceMs.toLong()), 1e-12)
        var last = 1.0
        for (t in c.graceMs..(c.graceMs + c.shrinkMs + 5000) step 500) {
            val r = SumoEngine.ringRadiusAt(c, t.toLong())
            assertTrue(r <= last + 1e-12); last = r
        }
        assertEquals(SumoRules.RING_MIN, last, 1e-12)
        val relaxed = c.copy(relaxed = true)
        assertTrue(SumoEngine.ringRadiusAt(relaxed, 40_000) > SumoEngine.ringRadiusAt(c, 40_000))
        assertTrue(relaxed.ringStart > c.ringStart)
    }

    @Test fun twoIdleSumosAreSqueezedOutTogetherWhenTheRingClosesOnThemAndItIsADraw() {
        val e = engine()
        val ev = e.run(2 * SumoRules.ROUND_CAP_MS / SumoRules.STEP_MS)
        assertTrue(e.over)
        assertEquals(-1, e.winner)
        assertEquals(2, ev.count { it is SumoEvent.Fell })
        // They stand 0.55 from the middle; the ring passes that after (1 - 0.55) / (1 - 0.40) of the shrink time.
        val expectedMs = SumoRules.GRACE_MS + (0.45 / 0.60 * SumoRules.SHRINK_MS)
        assertEquals(expectedMs, e.timeMs.toDouble(), 100.0)
    }

    @Test fun theRoundCapGivesTheRoundToTheSumoNearestTheMiddle() {
        val e = engine()
        place(e.sumos[0], 0.0, 0.0); place(e.sumos[1], 0.2, 0.0)
        e.run(SumoRules.ROUND_CAP_MS / SumoRules.STEP_MS + 2)
        assertTrue(e.over)
        assertEquals(SumoRules.ROUND_CAP_MS.toLong(), e.timeMs)
        assertEquals(0, e.winner)
    }

    @Test fun oneButtonModeSteersTowardTheNearestRivalByItself() {
        val e = engine(arena = SumoArena.LARGE)
        e.sumos[0].autoSteer = true
        val gap0 = e.sumos[0].y - e.sumos[1].y
        e.run(30)
        assertTrue(e.sumos[0].y - e.sumos[1].y < gap0 - 0.05)
        assertTrue(e.sumos[0].vy < 0)
    }

    @Test fun relaxedRopeGripKeepsASumoFromSteeringItselfOverTheEdge() {
        fun speedAfter(atRope: Boolean): Double {
            val e = engine(relaxed = true)
            val r = e.ringRadius()
            place(e.sumos[0], 0.0, if (atRope) r - 0.05 else 0.0) // at the rope (pushing outward, down) or in the middle
            e.setInput(0, 0.0, 1.0)
            e.run(3)
            return e.sumos[0].vy
        }
        assertTrue(speedAfter(true) < speedAfter(false) * 0.5)
    }

    @Test fun powerUpsAppearOnTimeAndAPickupGrantsHeavyOrSprint() {
        val off = engine(powerUps = false); off.run(SumoRules.ITEM_EVERY_MS / SumoRules.STEP_MS + 2)
        assertTrue(off.items.isEmpty())
        val on = engine(powerUps = true)
        on.run(SumoRules.ITEM_EVERY_MS / SumoRules.STEP_MS + 2)
        assertEquals(1, on.items.size)
        val pick = engine(powerUps = true)
        pick.items += SumoItem(pick.sumos[0].x, pick.sumos[0].y, SumoPower.HEAVY)
        val ev = pick.step()
        assertTrue(ev.any { it is SumoEvent.Pickup && it.seat == 0 && it.kind == SumoPower.HEAVY })
        assertTrue(pick.sumos[0].heavyMs > 0)
        assertTrue(pick.items.isEmpty())
        pick.items += SumoItem(pick.sumos[0].x, pick.sumos[0].y, SumoPower.SPRINT)
        pick.step()
        assertTrue(pick.sumos[0].sprintMs > 0)
    }

    @Test fun theSameSeedAndInputsReplayTheSameRound() {
        fun play(): List<Double> {
            val e = engine(3, powerUps = true, seed = 8)
            val rnd = Random(21)
            var n = 0
            while (!e.over && n++ < 9000) {
                if (n % 7 == 0) for (s in 0 until 3) e.setInput(s, rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1)
                if (n % 53 == 0) e.setCharging(rnd.nextInt(3), rnd.nextBoolean())
                e.step()
            }
            return e.sumos.flatMap { listOf(it.x, it.y, it.vx, it.vy) } + e.timeMs.toDouble()
        }
        assertEquals(play(), play())
    }

    @Test fun randomPlayAlwaysTerminatesWithFiniteStateAndNobodyComesBackFromTheDead() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val n = 2 + rnd.nextInt(3)
            val e = SumoEngine(SumoConfig(n, SumoArena.entries[rnd.nextInt(3)], 3, rnd.nextBoolean(), rnd.nextBoolean()), Random(seed))
            var steps = 0
            var aliveBefore = n
            while (!e.over) {
                assertTrue("runaway seed $seed", steps++ < 20_000)
                if (steps % 5 == 0) for (s in 0 until n) e.setInput(s, rnd.nextDouble() * 2.4 - 1.2, rnd.nextDouble() * 2.4 - 1.2)
                if (steps % 9 == 0) e.setCharging(rnd.nextInt(n), rnd.nextBoolean())
                e.step()
                assertTrue(e.alive() <= aliveBefore); aliveBefore = e.alive()
                e.sumos.forEach { assertTrue(it.x.isFinite() && it.y.isFinite() && it.vx.isFinite() && abs(it.vx) < 20 && abs(it.vy) < 20) }
            }
            assertEquals(if (e.alive() == 1) e.sumos.first { it.alive }.id else if (e.alive() == 0) -1 else e.winner, e.winner)
        }
    }
}
