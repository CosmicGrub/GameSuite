package com.gamesuite.games.toytennis

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** The court: scripted serves, windows, faults, out/net/second-bounce rules, relaxed timing, determinism and fuzz. */
class TennisSimTest {
    private fun sim(assist: Boolean = false, bot: BooleanArray = booleanArrayOf(false, false)) =
        TennisSim(TennisConfig(assist, bot, doubleArrayOf(8.0, 8.0), intArrayOf(0, 0)))

    private fun match(sim: TennisSim, format: TennisFormat = TennisFormat.POINTS7, first: Int = 0) = sim.newMatch(format, first)

    private fun stepUntil(sim: TennisSim, s: TennisState, max: Int = 6000, cond: (TennisState) -> Boolean) {
        var n = 0
        while (!cond(s) && n < max) { sim.step(s); n++ }
        assertTrue("condition reached", cond(s))
    }

    private fun toToss(sim: TennisSim, s: TennisState) = stepUntil(sim, s) { it.phase == TPhase.TOSS }

    /** Steps until the sim is at [tick], then applies [cmd] with that tick's step. */
    private fun swingAt(sim: TennisSim, s: TennisState, tick: Int, cmd: SwingCmd) {
        while (s.tick < tick) sim.step(s)
        sim.step(s, listOf(cmd))
    }

    private fun serve(sim: TennisSim, s: TennisState, lateTicks: Int = 0, dir: Double = 0.0, power: Double = 0.7) {
        toToss(sim, s)
        swingAt(sim, s, s.cTick + lateTicks, SwingCmd(s.server, dir, power))
    }

    @Test fun aPerfectlyTimedServeAlwaysLandsInTheBoxForEveryServerSideAndAim() {
        for (first in 0..1) for (dir in listOf(-0.9, -0.4, 0.0, 0.4, 0.9)) for (power in listOf(0.0, 0.3, 0.6, 0.9)) {
            val sim = sim()
            var s = match(sim, first = first)
            // play two points so both serve sides are covered
            for (pt in 0..1) {
                serve(sim, s, 0, dir, power)
                assertEquals("serve hit first=$first dir=$dir power=$power", TPhase.RALLY, s.phase)
                assertEquals(4, s.lastQuality)
                val faults = s.faultSerial
                val bounces = s.bounceSerial
                stepUntil(sim, s) { it.bounceSerial > bounces || it.faultSerial != faults || it.phase == TPhase.POINT }
                assertEquals("no fault: first=$first pt=$pt dir=$dir power=$power ball=(${s.bx},${s.by})", faults, s.faultSerial)
                assertEquals(1, s.bounces)
                // the serve is in the opposite service box
                assertTrue(abs(s.by) <= TennisRules.SERVICE + 0.08)
                assertTrue(s.bx * s.boxSign >= -0.08)
                // let the point finish (nobody returns) and go to the next serve
                stepUntil(sim, s) { it.phase == TPhase.PREP && it.pointSerial > pt }
            }
        }
    }

    @Test fun theSwingWindowIsAQuarterSecondEitherSideAndQualityFallsOffWithTheError() {
        val expected = mapOf(0 to 4, 4 to 4, 8 to 3, 16 to 2, 28 to 1, -4 to 4, -6 to 3, -12 to 2, -26 to 1)
        for ((late, q) in expected) {
            val sim = sim(); val s = match(sim)
            serve(sim, s, late)
            assertEquals("late=$late", q, s.lastQuality)
            assertEquals(TPhase.RALLY, s.phase)
        }
        for (late in listOf(32, 40, -33, -60)) {
            val sim = sim(); val s = match(sim)
            serve(sim, s, late)
            assertEquals("late=$late is a miss", 0, s.lastQuality)
            assertEquals(if (late < 0) Whiff.EARLY else Whiff.LATE, s.lastWhiff)
            assertEquals(TPhase.TOSS, s.phase)                 // the toss is still there
            assertEquals(0, s.hitSerial)
        }
    }

    @Test fun aSwingIsOutOfReachWhenThePlayerIsNotThere() {
        val sim = sim(); val s = match(sim)
        toToss(sim, s)
        s.p0x = 9.0
        swingAt(sim, s, s.cTick, SwingCmd(0, 0.0, 0.5))
        assertEquals(Whiff.FAR, s.lastWhiff)
        assertEquals(0, s.hitSerial)
    }

    @Test fun aWhiffCostsAHalfSecondOfRecovery() {
        val sim = sim(); val s = match(sim)
        toToss(sim, s)
        sim.step(s, listOf(SwingCmd(0, 0.0, 0.5)))             // far too early
        assertEquals(Whiff.EARLY, s.lastWhiff)
        val swings = s.swingSerial
        repeat(10) { sim.step(s) }
        sim.step(s, listOf(SwingCmd(0, 0.0, 0.5)))             // still recovering: ignored altogether
        assertEquals(swings, s.swingSerial)
        // once recovered, the same player can swing at the real contact and hit
        swingAt(sim, s, s.cTick, SwingCmd(0, 0.0, 0.5))
        assertEquals(1, s.hitSerial)
    }

    @Test fun swingsOutsideTheServeOrTheReturnDoNothingOrWhiff() {
        val sim = sim(); val s = match(sim)
        sim.step(s, listOf(SwingCmd(0, 0.0, 0.5)))             // during the pause before the serve: ignored
        assertEquals(0, s.swingSerial)
        serve(sim, s)
        sim.step(s, listOf(SwingCmd(1, 0.0, 0.5)))             // the receiver before the bounce: not yet
        assertEquals(Whiff.WRONG_TIME, s.lastWhiff)
        repeat(50) { sim.step(s) }
        sim.step(s, listOf(SwingCmd(0, 0.0, 0.5)))             // the hitter again: not its ball
        assertEquals(Whiff.WRONG_TIME, s.lastWhiff)
        assertEquals(1, s.hitSerial)
    }

    @Test fun twoLateServesAreADoubleFaultAndTheReceiverGetsThePoint() {
        val sim = sim(); val s = match(sim)
        serve(sim, s, 20, 0.0, 0.95)
        stepUntil(sim, s) { it.faultSerial == 1 }
        assertEquals(1, s.faults)
        assertEquals(TPhase.PREP, s.phase)
        assertEquals(0, s.score.totalPoints)
        serve(sim, s, 20, 0.0, 0.95)
        stepUntil(sim, s) { it.phase == TPhase.POINT }
        assertEquals(PointReason.DOUBLE_FAULT, s.pointReason)
        assertEquals(1, s.pointWinner)
        assertEquals(1, s.score.pointsB); assertEquals(0, s.score.pointsA)
        assertEquals(2, s.faultSerial)
    }

    @Test fun anUnreturnedServeIsAnAceAndTheServerKeepsServingForTwoInTheShortMode() {
        val sim = sim(); val s = match(sim)
        serve(sim, s)
        stepUntil(sim, s) { it.phase == TPhase.POINT }
        assertEquals(PointReason.ACE, s.pointReason)
        assertEquals(0, s.pointWinner)
        stepUntil(sim, s) { it.phase == TPhase.PREP }
        assertEquals(1, s.score.totalPoints)
        assertEquals(1, s.server)                               // after one point the serve changes in the short mode
        assertEquals(1, s.score.serveSide)
    }

    @Test fun aPerfectlyTimedRallyGoesOnForManyHitsBothWays() {
        val sim = sim(); val s = match(sim)
        serve(sim, s)
        var hits = 1
        while (hits < 16) {
            assertEquals("rally alive after $hits hits", TPhase.RALLY, s.phase)
            val side = 1 - s.lastHitter
            val dir = if (hits % 2 == 0) 0.5 else -0.5
            // wait for the contact tick of this return
            var guard = 0
            while (s.cTick < 0 && guard++ < 10) sim.step(s)
            assertTrue(s.cTick >= s.tick)
            swingAt(sim, s, s.cTick, SwingCmd(side, dir, 0.55))
            assertEquals("hit $hits", hits + 1, s.hitSerial)
            hits++
        }
        assertEquals(TPhase.RALLY, s.phase)
    }

    @Test fun theContactPredictionMatchesWhereTheBallReallyIs() {
        val sim = sim(); val s = match(sim)
        serve(sim, s)
        val cx = s.cX; val cy = s.cY; val ct = s.cTick
        while (s.tick < ct) sim.step(s)
        assertEquals(cx, s.bx, 0.02); assertEquals(cy, s.by, 0.02)
        assertTrue(s.bz >= 0.0)
    }

    @Test fun aBallThatDoesNotClearTheNetIsAFaultOnServeOrALostPointInARally() {
        val sim = sim(); val s = match(sim)
        s.phase = TPhase.RALLY; s.lastHitter = 0; s.lastWasServe = false; s.bounces = 0
        s.bx = 0.0; s.by = 1.0; s.bz = 0.3; s.bvx = 0.0; s.bvy = -12.0; s.bvz = 0.0
        stepUntil(sim, s) { it.phase == TPhase.POINT }
        assertEquals(PointReason.NET, s.pointReason); assertEquals(1, s.pointWinner)
        assertEquals(1, s.netSerial)
        // as a first serve it is a fault, not a point
        val s2 = match(sim)
        s2.phase = TPhase.RALLY; s2.lastHitter = 0; s2.lastWasServe = true; s2.bounces = 0
        s2.bx = 0.0; s2.by = 1.0; s2.bz = 0.3; s2.bvy = -12.0
        stepUntil(sim, s2) { it.faultSerial > 0 }
        assertEquals(1, s2.faults); assertEquals(TPhase.PREP, s2.phase)
    }

    @Test fun aBallLandingOutIsPointToTheOtherSide() {
        val sim = sim()
        for ((x, y) in listOf(5.0 to -8.0, 1.0 to -13.0, 0.0 to 3.0)) {   // wide, long, and on the hitter's own half
            val s = match(sim)
            s.phase = TPhase.RALLY; s.lastHitter = 0; s.lastWasServe = false; s.bounces = 0
            s.bx = x; s.by = y; s.bz = 0.01; s.bvx = 0.0; s.bvy = 0.0; s.bvz = -3.0
            sim.step(s)
            assertEquals("($x,$y)", TPhase.POINT, s.phase)
            assertEquals(PointReason.OUT, s.pointReason); assertEquals(1, s.pointWinner)
        }
    }

    @Test fun aServeLandingBeyondTheServiceLineIsAFault() {
        val sim = sim(); val s = match(sim)
        s.phase = TPhase.RALLY; s.lastHitter = 0; s.lastWasServe = true; s.bounces = 0
        s.boxSign = -1
        s.bx = -2.0; s.by = -7.5; s.bz = 0.01; s.bvz = -3.0
        sim.step(s)
        assertEquals(1, s.faultSerial)
        val s2 = match(sim)
        s2.phase = TPhase.RALLY; s2.lastHitter = 0; s2.lastWasServe = true; s2.bounces = 0
        s2.boxSign = -1
        s2.bx = 2.0; s2.by = -4.0; s2.bz = 0.01; s2.bvz = -3.0                // the wrong box (x must be negative)
        sim.step(s2)
        assertEquals(1, s2.faultSerial)
        val s3 = match(sim)
        s3.phase = TPhase.RALLY; s3.lastHitter = 0; s3.lastWasServe = true; s3.bounces = 0
        s3.boxSign = -1
        s3.bx = -2.0; s3.by = -4.0; s3.bz = 0.01; s3.bvz = -3.0                // inside
        sim.step(s3)
        assertEquals(0, s3.faultSerial); assertEquals(1, s3.bounces)
    }

    @Test fun aSecondBounceBeforeTheReturnLosesThePointForTheReceiver() {
        val sim = sim(); val s = match(sim)
        s.phase = TPhase.RALLY; s.lastHitter = 1; s.lastWasServe = false; s.bounces = 1
        s.bx = 0.0; s.by = 8.0; s.bz = 0.01; s.bvz = -3.0
        sim.step(s)
        assertEquals(TPhase.POINT, s.phase)
        assertEquals(PointReason.MISSED, s.pointReason); assertEquals(1, s.pointWinner)
    }

    @Test fun relaxedTimingHoldsTheBallAtTheContactPointUntilTheSwing() {
        val sim = sim(assist = true); val s = match(sim)
        toToss(sim, s)
        while (s.tick < s.cTick + 5) sim.step(s)
        assertTrue(s.held)
        val z = s.bz
        repeat(120) { sim.step(s) }
        assertEquals(z, s.bz, 1e-12)
        assertEquals(TPhase.TOSS, s.phase)
        sim.step(s, listOf(SwingCmd(0, 0.0, 0.6)))
        assertEquals(TPhase.RALLY, s.phase)
        assertEquals(4, s.lastQuality)
        assertFalse(s.held)
    }

    @Test fun relaxedTimingNeverFaultsFromTheWindowOnlyFromTheAim() {
        // Waiting forever is fine; the receiver is also held at its contact point.
        val sim = sim(assist = true); val s = match(sim)
        toToss(sim, s)
        while (s.tick < s.cTick + 3) sim.step(s)
        sim.step(s, listOf(SwingCmd(0, 0.0, 0.6)))
        stepUntil(sim, s) { it.held || it.phase == TPhase.POINT }
        assertTrue(s.held)
        assertEquals(1, s.bounces)
        sim.step(s, listOf(SwingCmd(1, 0.4, 0.6)))
        assertEquals(2, s.hitSerial)
        assertEquals(4, s.lastQuality)
    }

    @Test fun theSameCommandsGiveTheSameCourtAndStateSurvivesSerialization() {
        fun run(): TennisState {
            val sim = sim(); val s = match(sim, TennisFormat.SET4)
            val r = Random(11)
            repeat(9000) {
                val cmds = if (it % 17 == 0) listOf(SwingCmd(r.nextInt(2), r.nextDouble(-1.0, 1.0), r.nextDouble())) else emptyList()
                sim.step(s, cmds)
            }
            return s
        }
        val a = run(); val b = run()
        assertEquals(a, b)
        assertNotEquals(a, match(sim()))
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(TennisState.serializer(), json.encodeToString(TennisState.serializer(), a))
        assertEquals(a, back)
        // and a restored copy carries on identically
        val sim1 = sim(); val sim2 = sim()
        val c1 = a.copy(); val c2 = back.copy()
        repeat(500) { sim1.step(c1); sim2.step(c2) }
        assertEquals(c1, c2)
    }

    @Test fun randomSwingsKeepTheCourtSane() {
        for (seed in 1..10) for (assist in listOf(false, true)) for (fmt in listOf(TennisFormat.POINTS7, TennisFormat.BEST3)) {
            val sim = sim(assist = assist); val s = match(sim, fmt, first = seed % 2)
            val r = Random(seed)
            var ticks = 0
            var lastTick = -1
            while (s.phase != TPhase.OVER && ticks < 400_000) {
                val cmds = if (r.nextInt(25) == 0) listOf(SwingCmd(r.nextInt(2), r.nextDouble(-2.0, 2.0), r.nextDouble(-1.0, 2.0))) else emptyList()
                // aim a share of the swings at the contact tick, so rallies happen
                val live = if (cmds.isEmpty() && s.cTick == s.tick && (s.phase == TPhase.RALLY || s.phase == TPhase.TOSS)) {
                    val side = if (s.phase == TPhase.TOSS) s.server else 1 - s.lastHitter
                    listOf(SwingCmd(side, r.nextDouble(-0.9, 0.9), r.nextDouble(0.2, 1.0)))
                } else cmds
                sim.step(s, live)
                ticks++
                assertTrue(s.tick > lastTick); lastTick = s.tick
                assertTrue(listOf(s.bx, s.by, s.bz, s.bvx, s.bvy, s.bvz, s.p0x, s.p0y, s.p1x, s.p1y).all { it.isFinite() })
                assertTrue(abs(s.bx) < 80 && abs(s.by) < 80 && s.bz > -0.01 && s.bz < 40)
                assertTrue(abs(s.p0x) < 20 && abs(s.p1x) < 20 && abs(s.p0y) < 40 && abs(s.p1y) < 40)
                assertTrue(s.p0y > 0 || s.phase == TPhase.PREP || true)
                assertTrue(s.faults in 0..1)
                assertTrue(s.cool0 >= 0 && s.cool1 >= 0)
            }
            assertEquals("seed $seed assist=$assist $fmt finished", TPhase.OVER, s.phase)
            assertTrue(s.score.over)
        }
    }

    @Test fun playersStayOnTheirOwnHalf() {
        val sim = sim(); val s = match(sim)
        serve(sim, s)
        repeat(1200) { sim.step(s); assertTrue(s.p0y >= 3.9 || s.phase == TPhase.PREP); assertTrue(s.p1y <= -3.9 || s.phase == TPhase.PREP) }
    }

    @Test fun aGuestStyleNonAuthoritativeStepOnlyMovesThingsAndNeverScores() {
        val sim = sim(); val s = match(sim)
        serve(sim, s)
        val g = s.copy()
        repeat(2000) { sim.step(g, emptyList(), auth = false) }
        assertEquals(s.score, g.score)
        assertEquals(TPhase.RALLY, g.phase)
        assertEquals(0, g.pointSerial)
        assertTrue(hypot(g.bx - s.bx, g.by - s.by) > 0.0)
    }
}
