package com.gamesuite.games.toysoccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** The physics: determinism, invariants (no tunnelling, bounds) and scripted goal sequences. */
class SoccerWorldTest {
    private val C = SoccerRules.CX
    private val L = SoccerRules.L
    private val W = SoccerRules.W

    private fun runUntilGoal(w: SoccerWorld, maxSteps: Int = 600): Int {
        repeat(maxSteps) { val g = w.step(); if (g >= 0) return g }
        return -1
    }

    @Test fun kickoffPutsEveryBodyAtHomeAtRestAndMirrored() {
        for (fpt in listOf(3, 4)) {
            val w = SoccerWorld(fpt)
            assertEquals(1 + 2 * fpt, w.count)
            assertEquals(C, w.x[0], 1e-9); assertEquals(L / 2, w.y[0], 1e-9)
            for (f in 0 until fpt) {
                val a = w.index(0, f); val b = w.index(1, f)
                assertEquals(W - w.x[a], w.x[b], 1e-9)
                assertEquals(L - w.y[a], w.y[b], 1e-9)
                assertTrue(w.isReady(a) && w.isReady(b))
                assertTrue(w.y[a] > L / 2 && w.y[b] < L / 2) // each team starts in its own half
            }
            assertTrue(w.isKeeper(w.index(0, 0)) && !w.isKeeper(w.index(0, 1)))
            // nobody overlaps at kick-off
            for (i in 0 until w.count) for (j in i + 1 until w.count)
                assertTrue(hypot(w.x[i] - w.x[j], w.y[i] - w.y[j]) >= w.radius(i) + w.radius(j))
        }
    }

    @Test fun flickValidation() {
        val w = SoccerWorld(3)
        val f = w.index(0, 1)
        assertFalse(w.flick(0, 100.0, 0.0))                 // the ball is not a figure
        assertFalse(w.flick(f, 3.0, 4.0))                    // too small a pull
        assertFalse(w.flick(f, Double.NaN, 50.0))
        assertFalse(w.flick(f, Double.POSITIVE_INFINITY, 0.0))
        assertFalse(w.flick(99, 50.0, 0.0))
        assertTrue(w.flick(f, 0.0, -100.0))
        assertEquals(-100.0 * SoccerRules.FLICK_GAIN, w.vy[f], 1e-9)
        assertFalse(w.isReady(f))                              // moving, and recovering
        assertFalse(w.flick(f, 0.0, -100.0))
        // a huge pull is capped
        val g = w.index(0, 2)
        assertTrue(w.flick(g, 0.0, -10_000.0))
        assertEquals(SoccerRules.MAX_FLICK, hypot(w.vx[g], w.vy[g]), 1e-6)
    }

    @Test fun aFigureIsReadyAgainOnlyAfterItStopsAndRecovers() {
        val w = SoccerWorld(3)
        val f = w.index(0, 1)
        w.flick(f, 0.0, -150.0)
        var steps = 0
        while (!w.isReady(f) && steps < 3000) { w.step(); steps++ }
        assertTrue(w.isReady(f))
        assertTrue(steps >= SoccerRules.COOLDOWN_TICKS)
        assertTrue(w.speed(f) < SoccerRules.READY_SPEED)
    }

    @Test fun ballShotUpTheMiddleScoresForTeamZero() {
        val w = SoccerWorld(3)
        w.x[0] = C + 100; w.y[0] = 300.0; w.vy[0] = -1500.0
        assertEquals(0, runUntilGoal(w))
        assertTrue(w.y[0] < 0)
    }

    @Test fun ballShotDownTheMiddleScoresForTeamOne() {
        val w = SoccerWorld(3)
        w.x[0] = C + 100; w.y[0] = L - 300.0; w.vy[0] = 1500.0
        assertEquals(1, runUntilGoal(w))
    }

    @Test fun aShotAtThePostBouncesBackAndDoesNotScore() {
        val w = SoccerWorld(3)
        w.x[0] = C + SoccerRules.GOAL_HALF; w.y[0] = 250.0; w.vy[0] = -1400.0
        repeat(200) { assertEquals(-1, w.step()) }
        assertTrue(w.y[0] > 0)
    }

    @Test fun aShotWideOfTheMouthBouncesOffTheEndLine() {
        val w = SoccerWorld(3)
        w.x[0] = 300.0; w.y[0] = 250.0; w.vy[0] = -1400.0
        var minY = Double.MAX_VALUE
        repeat(300) { assertEquals(-1, w.step()); minY = minOf(minY, w.y[0]) }
        assertTrue(minY >= SoccerRules.BALL_R - 1e-6)
    }

    @Test fun anOwnGoalCountsForTheOtherTeam() {
        // team 0 defends the bottom goal; a ball driven in there is team 1's goal
        val w = SoccerWorld(3)
        w.x[0] = C + 100; w.y[0] = L - 250; w.vy[0] = 1400.0
        assertEquals(1, runUntilGoal(w))
    }

    @Test fun theBallStaysInTheNetAfterAGoal() {
        val w = SoccerWorld(3)
        w.x[0] = C + 100; w.y[0] = 200.0; w.vy[0] = -2000.0
        runUntilGoal(w)
        repeat(600) {
            w.step(detectGoal = false)
            assertTrue(w.y[0] >= -SoccerRules.GOAL_DEPTH)
            assertTrue(abs(w.x[0] - C) <= SoccerRules.GOAL_HALF)
        }
    }

    @Test fun theKeeperSpringsBackToItsSpot() {
        val w = SoccerWorld(3)
        val k = w.index(0, 0)
        assertTrue(w.flick(k, 130.0, -50.0))
        repeat(1200) { w.step() }
        assertEquals(w.homeX(k), w.x[k], 3.0)
        assertEquals(w.homeY(k), w.y[k], 3.0)
    }

    @Test fun glideBringsEveryoneHomeAndStops() {
        val w = SoccerWorld(4)
        for (i in 1 until w.count) w.flick(i, Random(i).nextDouble(-90.0, 90.0), Random(i + 9).nextDouble(-90.0, 90.0))
        repeat(200) { w.step() }
        var n = 0
        while (!w.glideToKickoff() && n < 500) n++
        assertTrue(n < 500)
        for (i in 0 until w.count) { assertEquals(w.homeX(i), w.x[i], 1e-9); assertEquals(w.homeY(i), w.y[i], 1e-9) }
    }

    private fun randomRun(seed: Int, fpt: Int, steps: Int): SoccerWorld {
        val r = Random(seed)
        val w = SoccerWorld(fpt)
        for (s in 0 until steps) {
            if (s % 20 == 0) {
                val i = 1 + r.nextInt(w.count - 1)
                w.flick(i, r.nextDouble(-300.0, 300.0), r.nextDouble(-300.0, 300.0))
            }
            if (s % 97 == 0 && r.nextInt(3) == 0) { // and sometimes blast the ball itself
                w.vx[0] = r.nextDouble(-2400.0, 2400.0); w.vy[0] = r.nextDouble(-2400.0, 2400.0)
            }
            val g = w.step()
            if (g >= 0) { w.kickoff() }
            invariants(w)
        }
        return w
    }

    private fun invariants(w: SoccerWorld) {
        for (i in 0 until w.count) {
            assertTrue(w.x[i].isFinite() && w.y[i].isFinite() && w.vx[i].isFinite() && w.vy[i].isFinite())
            assertTrue("speed ${w.speed(i)}", w.speed(i) <= SoccerRules.MAX_SPEED * 1.8)
            val r = w.radius(i)
            if (i > 0) {
                // figures never leave the pitch
                assertTrue(w.x[i] in (r - 1e-6)..(W - r + 1e-6))
                assertTrue(w.y[i] in (r - 1e-6)..(L - r + 1e-6))
            } else {
                assertTrue(w.x[0] in (-1.0)..(W + 1.0))
                assertTrue(w.y[0] in (-SoccerRules.GOAL_DEPTH - 1)..(L + SoccerRules.GOAL_DEPTH + 1))
                // no tunnelling: outside the mouth the ball stays on the pitch
                if (abs(w.x[0] - C) >= SoccerRules.GOAL_HALF + 1) assertTrue(w.y[0] in (r - 1e-6)..(L - r + 1e-6))
            }
        }
        // no two bodies deeply overlap (a tunnelling or resolution failure)
        for (i in 0 until w.count) for (j in i + 1 until w.count) {
            val d = hypot(w.x[i] - w.x[j], w.y[i] - w.y[j])
            assertTrue("bodies $i,$j overlap d=$d", d >= (w.radius(i) + w.radius(j)) * 0.5)
        }
    }

    @Test fun randomFlicksKeepEveryInvariantForBothTeamSizes() {
        for (seed in 1..12) for (fpt in listOf(3, 4)) randomRun(seed, fpt, 6000)
    }

    @Test fun theSameInputsGiveTheSameTableAndDifferentInputsDoNot() {
        val a = randomRun(5, 3, 4000).fingerprint()
        val b = randomRun(5, 3, 4000).fingerprint()
        val c = randomRun(6, 3, 4000).fingerprint()
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test fun copyFromReproducesTheTable() {
        val a = randomRun(3, 4, 1500)
        val b = SoccerWorld(4)
        b.copyFrom(a)
        assertEquals(a.fingerprint(), b.fingerprint())
        repeat(300) { a.step(); b.step() }
        assertEquals(a.fingerprint(), b.fingerprint())
    }

    @Test fun seatsAndFiguresMapCleanly() {
        assertEquals(3, SoccerRules.figuresFor(2)); assertEquals(4, SoccerRules.figuresFor(4)); assertEquals(4, SoccerRules.figuresFor(3))
        assertEquals(listOf(0, 2), SoccerRules.membersOf(0, 4)); assertEquals(listOf(1, 3), SoccerRules.membersOf(1, 4))
        // one player a side controls every figure
        for (f in 0 until 3) { assertEquals(0, SoccerRules.controllerSeat(0, f, 2)); assertEquals(1, SoccerRules.controllerSeat(1, f, 2)) }
        // two players a side split the figures between them
        assertEquals(listOf(0, 1), SoccerRules.figuresOfSeat(0, 4)); assertEquals(listOf(2, 3), SoccerRules.figuresOfSeat(2, 4))
        assertEquals(listOf(0, 1), SoccerRules.figuresOfSeat(1, 4)); assertEquals(listOf(2, 3), SoccerRules.figuresOfSeat(3, 4))
        // every figure has exactly one controller
        for (seats in 2..4) for (t in 0..1) for (f in 0 until SoccerRules.figuresFor(seats))
            assertEquals(1, (0 until seats).count { f in SoccerRules.figuresOfSeat(it, seats) && SoccerRules.teamOfSeat(it) == t })
        // a team mate that left hands over its figures
        assertEquals(0, SoccerRules.controllerSeat(0, 3, 4, gone = setOf(2)))
        // a team with 3 seats: team 1 has one player who flicks everything
        assertEquals(1, SoccerRules.controllerSeat(1, 3, 3))
    }
}
