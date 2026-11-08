package com.gamesuite.games.slotcars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

/** Slot Cars physics and tracks with known values, determinism and invariants. */
class SlotCarsSimTest {
    private fun racing(track: String = "meadow", laps: Int = 3, cars: Int = 2): SlotCarsSim {
        val sim = SlotCarsSim(SlotTrack.byId(track), laps, cars)
        while (sim.phase == SlotPhase.COUNTDOWN) sim.step()
        return sim
    }

    // ---- tracks ----

    @Test fun everyTrackIsAClosedSmoothLoopOfSensibleSize() {
        for (id in SlotTrack.ids) {
            val t = SlotTrack.byId(id)
            assertTrue("$id length ${t.length}", t.length in 1500.0..3500.0)
            // the loop closes: the end of the lap is the start of the next
            assertTrue(hypot(t.x(t.length - 0.01) - t.x(0.0), t.y(t.length - 0.01) - t.y(0.0)) < 1.0)
            assertEquals(t.x(5.0), t.x(5.0 + t.length), 1e-6)
            assertEquals(t.y(12.0), t.y(12.0 - t.length), 1e-6)
            // no kinks: the heading never jumps between nearby points
            var s = 0.0
            while (s < t.length) {
                var d = t.headingAt(s + 4.0) - t.headingAt(s)
                while (d > Math.PI) d -= 2 * Math.PI
                while (d < -Math.PI) d += 2 * Math.PI
                assertTrue("$id kink at $s", abs(d) < 0.35)
                s += 4.0
            }
        }
    }

    @Test fun everyTrackNeedsBrakingButCanBeDrivenFlatOutOnTheStraights() {
        for (id in SlotTrack.ids) {
            val t = SlotTrack.byId(id)
            assertTrue("$id has a bend that throws a flat-out car off (${t.slowestCritical})", t.slowestCritical < 0.8 * SlotCarsRules.VMAX)
            assertTrue("$id slowest bend is takeable at all", t.slowestCritical > 100.0)
            assertTrue("$id has signed corners", t.corners.isNotEmpty())
            val straight = (0..(t.length / 4).toInt()).count { SlotCarsRules.criticalSpeed(t.curvatureAt(it * 4.0)) > SlotCarsRules.VMAX }
            assertTrue("$id has real straights", straight > 40)
        }
    }

    @Test fun theThreeTracksAreDifferent() {
        val lengths = SlotTrack.ids.map { SlotTrack.byId(it).length.toInt() }
        assertEquals(3, lengths.toSet().size)
        assertEquals(SlotTrack.byId("meadow"), SlotTrack.byId("nonsense"))   // unknown ids fall back to the first track
    }

    @Test fun criticalSpeedIsTheCircleLimit() {
        assertEquals(Math.sqrt(SlotCarsRules.A_MAX * 100.0), SlotCarsRules.criticalSpeed(0.01), 1e-9)
        assertTrue(SlotCarsRules.criticalSpeed(0.0) > 1e6)
    }

    // ---- physics ----

    @Test fun holdingTheThrottleAcceleratesTowardTopSpeedAndReleasingBrakes() {
        val sim = racing("meadow", 3, 1)
        sim.s[0] = 0.0
        sim.throttle[0] = true
        repeat(60) { sim.step() }
        val expect = SlotCarsRules.VMAX * (1 - exp(-SlotCarsRules.ACC / SlotCarsRules.VMAX))
        assertEquals(expect, sim.v[0], 6.0)                           // about 1 s of the exponential approach
        assertTrue(sim.v[0] <= SlotCarsRules.VMAX + 1e-9)
        sim.off[0] = 0; sim.v[0] = 200.0; sim.throttle[0] = false; sim.s[0] = 400.0
        val before = sim.v[0]
        repeat(30) { sim.step() }
        assertTrue(sim.v[0] < before - 100.0)                         // half a second of braking sheds >100 units/s
        repeat(60) { sim.step() }
        assertEquals(0.0, sim.v[0], 1e-9)
    }

    @Test fun aCarWhoseSpeedSquaredTimesCurvatureIsOverTheLimitLeavesTheSlot() {
        val t = SlotTrack.byId("hill")
        val sim = racing("hill", 3, 2)
        // find the sharpest point
        var worst = 0.0; var at = 0.0
        var s = 0.0
        while (s < t.length) { if (t.curvatureAt(s) > worst) { worst = t.curvatureAt(s); at = s }; s += 2.0 }
        val vc = SlotCarsRules.criticalSpeed(worst)
        // at 90 percent of the limit it holds on
        sim.s[0] = at; sim.v[0] = vc * 0.9; sim.throttle[0] = false
        sim.step()
        assertEquals(0, sim.off[0]); assertEquals(0, sim.deslots[0])
        // at 120 percent it flies off, loses all speed and lies off for RESLOT_TICKS
        sim.s[1] = at; sim.v[1] = vc * 1.2; sim.throttle[1] = true
        sim.step()
        assertEquals(SlotCarsRules.RESLOT_TICKS, sim.off[1])
        assertEquals(1, sim.deslots[1]); assertEquals(1, sim.deslotSerial); assertEquals(1, sim.lastDeslotCar)
        assertEquals(0.0, sim.v[1], 1e-12)
        assertTrue(sim.offV[1] > vc)
        val frozenAt = sim.s[1]
        repeat(SlotCarsRules.RESLOT_TICKS - 1) { sim.step(); assertEquals(frozenAt, sim.s[1], 1e-12) }
        assertTrue(sim.off[1] >= 0)
        sim.step()
        assertEquals(0, sim.off[1]); assertEquals(0.0, sim.v[1], 1e-12)
    }

    @Test fun lapsAreCountedAndTheFinishTimeIsInterpolated() {
        val sim = racing("meadow", 3, 1)
        val len = sim.track.length
        sim.s[0] = len - 1.0; sim.v[0] = 120.0; sim.throttle[0] = true
        val t0 = sim.tick
        sim.step()
        assertEquals(1, sim.lapsDone[0]); assertEquals(1, sim.lapSerial)
        assertEquals(0, sim.finishSerial)
        assertTrue(sim.lapStart[0] in (t0 + 0.0)..(t0 + 1.0))
        sim.s[0] = 3 * len - 1.0; sim.v[0] = 120.0; sim.lapsDone[0] = 2
        sim.step()
        assertEquals(3, sim.lapsDone[0]); assertEquals(1, sim.finishSerial)
        assertTrue(sim.finishTime[0] > 0)
        assertEquals(SlotPhase.OVER, sim.phase)                        // the only car is done
        assertTrue(sim.bestLap[0] > 0)
    }

    @Test fun theRaceOrderIsFinishTimeThenDistance() {
        val sim = racing("meadow", 3, 3)
        sim.finishTime[2] = 900.0; sim.finishTime[0] = 1000.0
        sim.s[1] = 100.0
        assertEquals(listOf(2, 0, 1), sim.order())
        val sim2 = racing("meadow", 3, 3)
        sim2.s[0] = 10.0; sim2.s[1] = 30.0; sim2.s[2] = 20.0
        assertEquals(listOf(1, 2, 0), sim2.order())
    }

    @Test fun countdownThenRaceAndGraceEndsTheRaceAfterTheFirstFinish() {
        val sim = SlotCarsSim(SlotTrack.byId("meadow"), 3, 2)
        repeat(SlotCarsRules.COUNTDOWN_TICKS - 1) { sim.throttle[0] = true; sim.step(); assertEquals(SlotPhase.COUNTDOWN, sim.phase) }
        sim.step()
        assertEquals(SlotPhase.RACING, sim.phase); assertEquals(0, sim.tick)
        assertEquals(0.0, sim.s[0], 1e-12)                              // nobody moved during the countdown
        sim.s[0] = sim.totalDistance - 1.0; sim.v[0] = 120.0; sim.lapsDone[0] = 2; sim.throttle[0] = true
        sim.step()
        assertTrue(sim.finishTime[0] >= 0 && sim.phase == SlotPhase.RACING)
        repeat(SlotCarsRules.FINISH_GRACE_TICKS) { sim.step() }
        assertEquals(SlotPhase.OVER, sim.phase)
        assertEquals(0, sim.order().first())
    }

    @Test fun gripUseIsOneAtTheLimit() {
        val sim = racing("hill", 3, 1)
        val t = sim.track
        var at = 0.0; var worst = 0.0; var s = 0.0
        while (s < t.length) { if (t.curvatureAt(s) > worst) { worst = t.curvatureAt(s); at = s }; s += 2.0 }
        sim.s[0] = at; sim.v[0] = SlotCarsRules.criticalSpeed(worst)
        assertEquals(1.0, sim.gripUse(0), 1e-9)
        sim.v[0] = 0.0
        assertEquals(0.0, sim.gripUse(0), 1e-12)
    }

    @Test fun projectionMatchesWhatActuallyHappens() {
        val sim = racing("twist", 3, 1)
        sim.v[0] = 180.0; sim.s[0] = 300.0
        for (on in listOf(true, false)) {
            val (ps, pv, left) = sim.project(0, on, 30)
            val copy = racing("twist", 3, 1)
            copy.v[0] = 180.0; copy.s[0] = 300.0; copy.throttle[0] = on
            repeat(30) { copy.step() }
            assertEquals(copy.s[0], ps, 1e-9); assertEquals(copy.v[0], pv, 1e-9)
            assertEquals(copy.deslots[0] > 0, left)
        }
    }

    // ---- determinism and invariants ----

    @Test fun identicalInputsGiveIdenticalRacesOnEveryTrack() {
        for (id in SlotTrack.ids) {
            val fps = (0 until 2).map {
                val sim = racing(id, 3, 3)
                val r = Random(99)
                repeat(2500) { if (it % 7 == 0) for (c in 0 until 3) sim.throttle[c] = r.nextBoolean(); sim.step() }
                sim.fingerprint()
            }
            assertEquals(fps[0], fps[1])
        }
        val a = racing("meadow", 3, 2); val b = racing("meadow", 3, 2)
        a.throttle[0] = true
        repeat(100) { a.step(); b.step() }
        assertNotEquals(a.fingerprint(), b.fingerprint())
    }

    @Test fun fuzzedThrottlesNeverBreakAnInvariantAndEveryRaceEnds() {
        for (seed in 0 until 40) {
            val r = Random(seed)
            val id = SlotTrack.ids[seed % 3]
            val n = 1 + r.nextInt(4)
            val sim = racing(id, listOf(3, 5)[r.nextInt(2)], n)
            val last = DoubleArray(n)
            var guard = 0
            while (sim.phase != SlotPhase.OVER && guard++ < SlotCarsRules.MAX_RACE_TICKS + 10) {
                if (r.nextInt(5) == 0) for (c in 0 until n) sim.throttle[c] = r.nextDouble() < 0.7
                sim.step()
                for (c in 0 until n) {
                    assertTrue(sim.s[c] + 1e-9 >= last[c]); last[c] = sim.s[c]
                    assertTrue(sim.v[c] in 0.0..SlotCarsRules.VMAX + 1e-9)
                    assertTrue(sim.off[c] in 0..SlotCarsRules.RESLOT_TICKS)
                    assertTrue(sim.lapsDone[c] in 0..sim.laps)
                    assertTrue(sim.s[c].isFinite() && sim.v[c].isFinite())
                }
            }
            assertEquals(SlotPhase.OVER, sim.phase)
            assertEquals((0 until n).toSet(), sim.order().toSet())
        }
    }

    @Test fun aFlatOutDriverIsPunishedByTheBends() {
        // Holding the throttle for ever on the hairpin track must cost time against a driver who brakes.
        val flat = racing("hill", 3, 1)
        flat.throttle[0] = true
        var g = 0
        while (flat.phase != SlotPhase.OVER && g++ < 60000) { flat.throttle[0] = true; flat.step() }
        assertTrue("flat out left the slot ${flat.deslots[0]} times", flat.deslots[0] >= 3)
        val smart = racing("hill", 3, 1)
        val perfect = SlotCpu.PERFECT
        g = 0
        while (smart.phase != SlotPhase.OVER && g++ < 60000) {
            smart.throttle[0] = smart.off[0] == 0 && SlotCpu.wantsThrottle(smart.track, smart.s[0], smart.v[0], perfect, 0.0)
            smart.step()
        }
        assertEquals(0, smart.deslots[0])
        assertTrue(smart.finishTime[0] > 0 && (flat.finishTime[0] < 0 || smart.finishTime[0] < flat.finishTime[0]))
    }
}
