package com.gamesuite.games.minigolf

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GolfPhysicsTest {
    private val h1 get() = GolfCourse.holes[0]

    @Test fun theSameStrokeReplaysBitIdentically() {
        for (hole in GolfCourse.holes) {
            val shot = GolfShot(-0.37, 0.83)
            val a = GolfPhysics.simulate(hole, hole.tee.x, hole.tee.y, shot)
            val b = GolfPhysics.simulate(hole, hole.tee.x, hole.tee.y, shot)
            assertEquals(a.x, b.x, 0.0); assertEquals(a.y, b.y, 0.0); assertEquals(a.steps, b.steps); assertEquals(a.end, b.end)
        }
    }

    @Test fun everyHoleIsWellFormed() {
        assertEquals(9, GolfCourse.holes.size)
        for ((i, hole) in GolfCourse.holes.withIndex()) {
            assertEquals(i + 1, hole.number)
            assertTrue("par ${hole.par}", hole.par in 2..5)
            assertTrue("tee playable on ${hole.number}", hole.isPlayable(hole.tee.x, hole.tee.y))
            assertTrue("cup playable on ${hole.number}", hole.isPlayable(hole.cup.x, hole.cup.y))
            assertTrue("tee not wet or sandy", !hole.inWater(hole.tee.x, hole.tee.y) && !hole.inSand(hole.tee.x, hole.tee.y))
            assertTrue("cup not wet", !hole.inWater(hole.cup.x, hole.cup.y))
            assertTrue("cup reachable from the tee", GolfNav.forHole(hole).distanceTo(hole.tee.x, hole.tee.y) < 500.0)
            for (p in hole.outline) assertTrue(p.x in 0.0..GolfCourse.FIELD_W && p.y in 0.0..GolfCourse.FIELD_H)
            for (b in hole.bumpers) assertTrue("bumper inside", hole.isPlayable(b.x, b.y))
        }
    }

    @Test fun randomStrokesTerminateAndNeverLeaveTheCourse() {
        val rnd = Random(99)
        for (hole in GolfCourse.holes) {
            var x = hole.tee.x; var y = hole.tee.y
            repeat(150) {
                val shot = GolfShot(rnd.nextDouble(-Math.PI, Math.PI), rnd.nextDouble(0.02, 1.0))
                val sim = GolfPhysics.simulate(hole, x, y, shot, frameEvery = 6)
                assertTrue("terminates", sim.steps < GolfPhysics.MAX_STEPS + 1)
                assertTrue(sim.x.isFinite() && sim.y.isFinite())
                if (sim.end == GolfEnd.REST) {
                    assertTrue("rests on the course hole ${hole.number}: ${sim.x},${sim.y}", hole.isPlayable(sim.x, sim.y))
                    x = sim.x; y = sim.y
                } else { x = hole.tee.x; y = hole.tee.y }
                val f = sim.frames!!
                assertEquals(sim.x.toFloat(), f[f.size - 2], 1e-4f)
                var i = 0
                while (i < f.size) {
                    assertTrue("frame inside the walls", GolfHole.insidePoly(hole.outline, f[i].toDouble(), f[i + 1].toDouble()))
                    i += 2
                }
            }
        }
    }

    @Test fun aFullPowerStrokeNeverTunnelsThroughAWall() {
        for (hole in GolfCourse.holes) for (deg in 0 until 360 step 7) {
            val sim = GolfPhysics.simulate(hole, hole.tee.x, hole.tee.y, GolfShot(deg * Math.PI / 180, 1.0), frameEvery = 1)
            val f = sim.frames!!
            var i = 0
            while (i < f.size) { assertTrue(GolfHole.insidePoly(hole.outline, f[i].toDouble(), f[i + 1].toDouble())); i += 2 }
        }
    }

    @Test fun aGentleStraightPuttOnTheFirstHoleDropsAndAFastOneSkipsOver() {
        val hole = h1
        var holed = 0
        for (p in 40..80) {
            val sim = GolfPhysics.simulate(hole, hole.tee.x, hole.tee.y, GolfShot(0.0, p / 100.0))
            if (sim.end == GolfEnd.CUP) holed++
        }
        assertTrue("some powers drop ($holed)", holed in 1..40)
        val fast = GolfPhysics.simulate(hole, hole.tee.x, hole.tee.y, GolfShot(0.0, 1.0))
        assertTrue("a full-power putt does not drop", fast.end == GolfEnd.REST)
    }

    @Test fun sandStopsTheBallShort() {
        val sandy = GolfCourse.holes[2]
        val plain = GolfHole(1, 2, sandy.outline, tee = sandy.tee, cup = sandy.cup)
        val shot = GolfShot(0.0, 0.6)
        val onSand = GolfPhysics.simulate(sandy, sandy.tee.x, sandy.tee.y, shot)
        val onGrass = GolfPhysics.simulate(plain, plain.tee.x, plain.tee.y, shot)
        assertTrue(onSand.touchedSand)
        assertTrue("sand ${onSand.x} < grass ${onGrass.x}", onSand.x < onGrass.x - 10.0)
        assertTrue(onSand.events.any { it.kind == GolfEvent.SAND })
    }

    @Test fun waterSinksTheBall() {
        val pond = GolfCourse.holes[3]
        val sim = GolfPhysics.simulate(pond, pond.tee.x, pond.tee.y, GolfShot(Math.atan2(17.0 - 30.0, 48.0 - 16.0), 0.7))
        assertEquals(GolfEnd.WATER, sim.end)
        assertTrue(sim.events.any { it.kind == GolfEvent.WATER })
    }

    @Test fun aBeltCarriesTheBallAlong() {
        val belts = GolfCourse.holes[6]
        val sim = GolfPhysics.simulate(belts, belts.tee.x, belts.tee.y, GolfShot(0.0, 0.45))
        assertTrue("pushed up the field: y=${sim.y}", sim.y < belts.tee.y - 6.0)
    }

    @Test fun aBumperKicksTheBallBack() {
        val pin = GolfCourse.holes[4]
        val sim = GolfPhysics.simulate(pin, 20.0, 20.0, GolfShot(0.0, 0.5))
        assertTrue(sim.events.any { it.kind == GolfEvent.BUMPER })
    }

    @Test fun wallBouncesAreReportedAndKeepTheBallInside() {
        val hole = h1
        val sim = GolfPhysics.simulate(hole, 50.0, 30.0, GolfShot(Math.PI / 2, 0.5))
        assertTrue(sim.events.any { it.kind == GolfEvent.WALL })
        assertTrue(hole.isPlayable(sim.x, sim.y))
    }

    /** Every hole can be holed by a sequence of simulated strokes within a reasonable number of tries. */
    @Test fun everyHoleIsSolvableByTheHardComputer() {
        for (hole in GolfCourse.holes) {
            var x = hole.tee.x; var y = hole.tee.y
            var strokes = 0
            var holed = false
            var attempts = 0
            while (!holed && attempts < hole.par + 6) {
                attempts++
                val shot = GolfBot.chooseShot(hole, x, y, CpuDifficulty.HARD, Random(1), exact = true)
                val sim = GolfPhysics.simulate(hole, x, y, shot)
                strokes++
                when (sim.end) {
                    GolfEnd.CUP -> holed = true
                    GolfEnd.WATER -> strokes++
                    else -> { x = sim.x; y = sim.y }
                }
            }
            println("hole ${hole.number} par ${hole.par}: hard computer needs $strokes strokes")
            assertTrue("hole ${hole.number} holed", holed)
            assertTrue("hole ${hole.number}: $strokes strokes is within par+1 (${hole.par})", strokes <= hole.par + 1)
        }
    }

    @Test fun theLevelsAreOrderedOnTheWholeCourse() {
        fun total(level: CpuDifficulty): Int {
            var sum = 0
            for (hole in GolfCourse.holes) {
                val rnd = Random(7 + hole.number)
                var x = hole.tee.x; var y = hole.tee.y
                var strokes = 0
                while (strokes < 8) {
                    val sim = GolfPhysics.simulate(hole, x, y, GolfBot.chooseShot(hole, x, y, level, rnd))
                    strokes++
                    if (sim.end == GolfEnd.CUP) break
                    if (sim.end == GolfEnd.WATER) strokes++ else { x = sim.x; y = sim.y }
                }
                sum += strokes
            }
            return sum
        }
        val easy = total(CpuDifficulty.EASY); val medium = total(CpuDifficulty.MEDIUM); val hard = total(CpuDifficulty.HARD)
        println("golf course totals: easy=$easy medium=$medium hard=$hard (par ${GolfCourse.holes.sumOf { it.par }})")
        assertTrue("hard ($hard) beats medium ($medium)", hard <= medium)
        assertTrue("medium ($medium) beats easy ($easy)", medium < easy)
        assertTrue("hard is near par ($hard)", hard <= GolfCourse.holes.sumOf { it.par } + 5)
    }
}
