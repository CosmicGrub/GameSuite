package com.gamesuite.games.lighttrail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The grid rules with known sequences. A SMALL arena is 20 cells: seat 0 starts at (10,17) going up, seat 1 at (9,2)
 * going down, seat 2 at (2,10) going right, seat 3 at (17,9) going left.
 */
class LightTrailRulesTest {
    private fun engine(players: Int = 2, powerUps: Boolean = false, relaxed: Boolean = false, one: Boolean = false, seed: Int = 1, arena: LtArena = LtArena.SMALL) =
        LightTrailEngine(LightTrailConfig(players, arena, 3, powerUps, relaxed, one), Random(seed))

    private fun LightTrailEngine.run(n: Int) = repeat(n) { step() }

    @Test fun ridersStartOnTheirOwnEdgesFacingIn() {
        val e = engine(4)
        assertEquals(listOf(LtDir.UP, LtDir.DOWN, LtDir.RIGHT, LtDir.LEFT), e.riders.map { it.dir })
        assertEquals(10 to 17, e.riders[0].x to e.riders[0].y)
        assertEquals(9 to 2, e.riders[1].x to e.riders[1].y)
        assertEquals(2 to 10, e.riders[2].x to e.riders[2].y)
        assertEquals(17 to 9, e.riders[3].x to e.riders[3].y)
        e.riders.forEach { assertEquals(it.id + 1, e.cell(it.x, it.y)) }
    }

    @Test fun ridersThatKeepGoingStraightBothHitTheirEdgeOnStepEighteen() {
        val e = engine()
        e.run(17)
        assertFalse(e.over)
        val ev = e.step()
        assertTrue(e.over)
        assertEquals(-1, e.winner)
        val crashes = ev.filterIsInstance<LtEvent.Crash>()
        assertEquals(listOf(LtCause.EDGE, LtCause.EDGE), crashes.map { it.cause })
        assertEquals(listOf(10 to 0, 9 to 19), crashes.map { it.x to it.y })
    }

    @Test fun headOnIntoTheSameCellIsADrawAndEveryWallVanishes() {
        val e = engine()
        e.turn(1, LtTurn.LEFT); e.step() // seat 1 (heading down) turns east into column 10
        e.turn(1, LtTurn.RIGHT) // and back down: now seat 0 and seat 1 ride toward each other in column 10
        var guard = 0
        while (!e.over && guard++ < 40) e.step()
        assertEquals(8, e.step)
        assertEquals(-1, e.winner)
        assertTrue(e.riders.all { !it.alive && it.cause == LtCause.HEAD_ON })
        assertEquals(0, e.cells.count { it != 0 })
    }

    @Test fun aWallOfAnotherRiderKillsTheRiderThatHitsItAndTheLastRiderStandingWins() {
        val e = engine(3)
        e.run(7)
        assertTrue(e.riders.all { it.alive })
        val ev = e.step()
        // Seat 2 rides into seat 0's wall at (10,10) and seat 1 into seat 2's wall at (9,10); seat 0 rides on.
        assertEquals(setOf(1, 2), ev.filterIsInstance<LtEvent.Crash>().map { it.rider }.toSet())
        assertTrue(ev.filterIsInstance<LtEvent.Crash>().all { it.cause == LtCause.WALL })
        assertTrue(e.over)
        assertEquals(0, e.winner)
        assertEquals(0, e.cells.count { it == 2 || it == 3 })
        assertTrue(e.cells.any { it == 1 })
    }

    @Test fun turningIntoYourOwnTrailIsASelfCrashAndTheOtherRiderWins() {
        val e = engine()
        repeat(3) { e.turn(0, LtTurn.RIGHT); e.step() } // east, south, west: a square corner at a time
        assertTrue(e.riders[0].alive)
        assertEquals(10 to 18, e.riders[0].x to e.riders[0].y)
        e.turn(0, LtTurn.RIGHT)
        val ev = e.step() // north again: straight into (10,17), its own first cell
        assertEquals(LtCause.SELF, (ev.single { it is LtEvent.Crash } as LtEvent.Crash).cause)
        assertTrue(e.over)
        assertEquals(1, e.winner)
    }

    @Test fun theTurnQueueHoldsTwoAndAppliesOnePerStep() {
        val e = engine()
        assertTrue(e.turn(0, LtTurn.RIGHT)); assertTrue(e.turn(0, LtTurn.RIGHT)); assertFalse(e.turn(0, LtTurn.RIGHT))
        e.step()
        assertEquals(LtDir.RIGHT, e.riders[0].dir)
        e.step()
        assertEquals(LtDir.DOWN, e.riders[0].dir)
        assertEquals(11 to 18, e.riders[0].x to e.riders[0].y)
    }

    @Test fun leftAndRightAreRelativeToTheRidersHeading() {
        val e = engine()
        e.turn(0, LtTurn.LEFT); e.turn(1, LtTurn.LEFT); e.step()
        assertEquals(LtDir.LEFT, e.riders[0].dir) // heading up: left is west
        assertEquals(LtDir.RIGHT, e.riders[1].dir) // heading down: left is east
    }

    @Test fun oneButtonTurnsTowardTheArmedSideWhichFlipsEveryThreeSteps() {
        val e = engine(one = true)
        assertEquals(LtTurn.RIGHT, e.armedSide())
        e.run(2); assertEquals(LtTurn.RIGHT, e.armedSide())
        e.run(1); assertEquals(LtTurn.LEFT, e.armedSide())
        e.run(3); assertEquals(LtTurn.RIGHT, e.armedSide())
        val e2 = engine(one = true)
        e2.turn(0, LtTurn.SWITCH); e2.step()
        assertEquals(LtDir.RIGHT, e2.riders[0].dir)
        e2.run(2) // three steps in: the armed side is now LEFT
        e2.turn(0, LtTurn.SWITCH); e2.step()
        assertEquals(LtDir.UP, e2.riders[0].dir) // east turned left is north
    }

    @Test fun boostMovesTwoCellsAStepForSixStepsAndLaysBothWalls() {
        val e = engine()
        e.riders[0].boostLeft = 3
        e.step()
        assertEquals(15, e.riders[0].y)
        assertEquals(1, e.cell(10, 16)); assertEquals(1, e.cell(10, 15))
        e.run(2)
        assertEquals(11, e.riders[0].y)
        e.step()
        assertEquals(10, e.riders[0].y) // the boost is over: one cell again
    }

    @Test fun gapLetsARiderSlipThroughAWallLeavingNoTrailForItsLength() {
        val e = engine()
        e.cells[14 * e.size + 10] = 2 // a wall of seat 1 across seat 0's column
        e.riders[0].gapLeft = LightTrailRules.GAP_MOVES
        e.run(3)
        assertTrue(e.riders[0].alive)
        assertEquals(14, e.riders[0].y)
        assertEquals(0, e.cell(10, 16)); assertEquals(0, e.cell(10, 15))
        assertEquals(2, e.cell(10, 14)) // the wall is untouched
        e.step() // the last gap move lands on open floor
        assertTrue(e.riders[0].alive)
        assertEquals(0, e.riders[0].gapLeft)
        assertEquals(0, e.cell(10, 13))
        e.step()
        assertEquals(1, e.cell(10, 12)) // the trail is back
    }

    @Test fun aGapThatRunsOutInsideAWallIsACrash() {
        val e = engine()
        e.cells[15 * e.size + 10] = 2
        e.riders[0].gapLeft = 2
        e.run(2)
        assertFalse(e.riders[0].alive)
        assertEquals(LtCause.GAP_END, e.riders[0].cause)
    }

    @Test fun itemsOnlyAppearWhenPowerUpsAreOnAndAPickupGrantsThePower() {
        val off = engine(powerUps = false, arena = LtArena.LARGE); off.run(LightTrailRules.ITEM_EVERY_STEPS)
        assertTrue(off.items.isEmpty())
        val on = engine(powerUps = true, arena = LtArena.LARGE); on.run(LightTrailRules.ITEM_EVERY_STEPS)
        assertEquals(1, on.items.size)
        val pick = engine(powerUps = true)
        pick.items += LtItem(10, 16, LtPower.GAP)
        val ev = pick.step()
        assertTrue(ev.any { it is LtEvent.Pickup && it.rider == 0 && it.kind == LtPower.GAP })
        assertEquals(LightTrailRules.GAP_MOVES, pick.riders[0].gapLeft)
        assertTrue(pick.items.isEmpty())
    }

    @Test fun relaxedSavesARiderFromTheEdgeAndWithoutItTheSameRiderCrashes() {
        for (relaxed in listOf(true, false)) {
            val e = engine(relaxed = relaxed)
            val r = e.riders[0]
            e.cells[r.y * e.size + r.x] = 0
            r.x = 10; r.y = 0; r.dir = LtDir.UP
            e.cells[10] = 1
            val ev = e.step()
            if (relaxed) {
                assertTrue(r.alive)
                assertEquals(LtDir.RIGHT, r.dir) // the Relaxed arena is 26 wide: more open floor to the right of column 10
                assertTrue(ev.any { it is LtEvent.AutoAvoid && it.rider == 0 })
            } else {
                assertFalse(r.alive)
            }
        }
    }

    @Test fun relaxedWidensTheArenaAndSlowsTheRiders() {
        val normal = LightTrailConfig(arena = LtArena.SMALL)
        val relaxed = normal.copy(relaxed = true)
        assertEquals(20, normal.size); assertEquals(26, relaxed.size)
        assertTrue(relaxed.stepMs > normal.stepMs)
    }

    @Test fun theSameSeedAndCommandsReplayTheSameRound() {
        fun play(seed: Int): List<Int> {
            val e = engine(3, powerUps = true, seed = seed, arena = LtArena.LARGE)
            val rnd = Random(99)
            var n = 0
            while (!e.over && n++ < 2000) {
                for (r in 0 until 3) if (rnd.nextInt(5) == 0) e.turn(r, if (rnd.nextBoolean()) LtTurn.LEFT else LtTurn.RIGHT)
                e.step()
            }
            return e.cells.toList() + e.step + e.winner
        }
        assertEquals(play(4), play(4))
    }

    @Test fun aFinishedRoundIgnoresMoreInputAndSteps() {
        val e = engine()
        e.run(40)
        assertTrue(e.over)
        val before = e.cells.toList()
        assertFalse(e.turn(0, LtTurn.LEFT))
        assertTrue(e.step().isEmpty())
        assertEquals(before, e.cells.toList())
    }

    @Test fun turnsForUnknownOrOutRidersAreRejected() {
        val e = engine()
        assertFalse(e.turn(5, LtTurn.LEFT))
        e.riders[1].alive = false
        assertFalse(e.turn(1, LtTurn.LEFT))
    }

    @Test fun randomPlayAlwaysTerminatesWithAConsistentBoard() {
        for (seed in 1..60) {
            val rnd = Random(seed)
            val players = 2 + rnd.nextInt(3)
            val e = LightTrailEngine(LightTrailConfig(players, LtArena.entries[rnd.nextInt(3)], 3, rnd.nextBoolean(), rnd.nextBoolean(), false), Random(seed))
            var n = 0
            while (!e.over) {
                assertTrue("runaway round seed $seed", n++ < 20_000)
                for (r in 0 until players) if (rnd.nextInt(3) == 0) e.turn(r, LtTurn.entries[rnd.nextInt(3)])
                e.step()
                if (e.over) break
                // Every wall belongs to a rider that is still alive, and every living rider is on the board.
                for (c in e.cells) if (c != 0) assertTrue(e.riders[c - 1].alive)
                for (r in e.riders) if (r.alive) assertTrue(e.inBounds(r.x, r.y))
            }
            assertTrue(e.aliveCount() <= 1)
            assertEquals(if (e.aliveCount() == 1) e.riders.first { it.alive }.id else -1, e.winner)
        }
    }
}
