package com.gamesuite.games.whackamole

import com.gamesuite.core.GameResult
import com.gamesuite.games.partypack.RdWhClock
import com.gamesuite.games.partypack.rdWhContext
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The shell-facing Whack-a-Mole Race module on a fake clock: roster, tally, save/resume. */
class WhackAMoleGameTest {
    private fun game(
        humans: Int = 2, bots: Int = 0, seed: Int = 1, clock: RdWhClock = RdWhClock(), time: Int = 60, grid: Int = 3,
        relaxed: Boolean = false, oneSwitch: Boolean = false, level: CpuDifficulty = CpuDifficulty.MEDIUM
    ): Pair<WhackAMoleGame, RdWhClock> {
        val g = WhackAMoleGame(Random(seed), clock)
        g.timeSeconds = time; g.grid = grid; g.relaxed = relaxed; g.oneSwitch = oneSwitch; g.difficulty = level
        g.init(rdWhContext(humans, bots))
        g.startMatch()
        return g to clock
    }

    @Test fun computerSeatsFillEmptyZonesAndTheHubSettingsReachTheEngine() {
        val (g, _) = game(humans = 1, bots = 3, time = 120, grid = 4, relaxed = true, level = CpuDifficulty.HARD)
        val c = g.engine!!.config
        assertEquals(4, c.seats); assertEquals(setOf(1, 2, 3), c.cpuSeats)
        assertEquals(120_000, c.durationMs); assertEquals(4, c.grid); assertTrue(c.relaxed); assertEquals(CpuDifficulty.HARD, c.level)
    }

    @Test fun nonsenseSettingsFallBackToTheDefaults() {
        val (g, _) = game(time = 77, grid = 9)
        assertEquals(WhackRules.DEFAULT_TIME_S * 1000, g.engine!!.config.durationMs)
        assertEquals(WhackRules.DEFAULT_GRID, g.engine!!.config.grid)
    }

    @Test fun aWhackCarriesItsAgeAndScoresOnTheFakeClock() {
        val (g, c) = game()
        val e = g.engine!!
        val mole = e.spawns.first { it.critter == Critter.MOLE }
        c.now = e.playStartAt + mole.startMs + 200L
        g.tick()
        val r = g.whack(0, mole.hole, ageMs = 150)
        assertEquals(WhackKind.HIT, r.kind)
        assertEquals(10, e.score[0])
        assertEquals(WhackKind.IGNORED, g.whack(0, mole.hole + 0, ageMs = 4_000_000).kind) // touched before play began
    }

    @Test fun theSessionTalliesTheRaceAndLeavingReportsTheWinner() {
        val (g, c) = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        val e = g.engine!!
        c.now = e.playStartAt; g.tick()
        val mole = e.spawns.first { it.critter == Critter.MOLE }
        c.now = e.playStartAt + mole.startMs + 30L
        g.whack(1, mole.hole)
        c.now = e.playStartAt + 61_000L
        g.tick()
        assertEquals(WhackPhase.OVER, e.phase)
        assertEquals(1, g.sessionWins.value["p2"])
        g.leaveSession()
        assertEquals(listOf(false, true), result!!.scores.map { it.isWinner })
        assertFalse(result!!.wasAborted)
    }

    @Test fun aTiedRaceCountsAsADrawAndPlayAgainWaitsForTheEnd() {
        val (g, c) = game()
        val first = g.engine
        g.playAgain()
        assertTrue(first === g.engine)
        c.now = g.engine!!.playStartAt + 61_000L
        g.tick()
        assertEquals(1, g.sessionDraws.value)
        g.playAgain()
        assertFalse(first === g.engine)
        assertEquals(WhackPhase.COUNTDOWN, g.engine!!.phase)
    }

    @Test fun snapshotRestoresTheSameCrittersAndRestartsTheRound() {
        val (g, c) = game(humans = 2, bots = 1, time = 90, grid = 4, relaxed = true, oneSwitch = true)
        c.now = g.engine!!.playStartAt + 20_000L
        g.tick()
        val snap = g.snapshot()
        assertNotNull(snap)
        val r = WhackAMoleGame(Random(9), RdWhClock(500))
        r.init(rdWhContext(2, 1))
        assertTrue(r.restore(snap!!))
        val a = g.engine!!; val b = r.engine!!
        assertEquals(a.spawns, b.spawns)
        assertEquals(a.config.copy(), b.config)
        assertEquals(WhackPhase.COUNTDOWN, b.phase)
        assertEquals(0, b.score.sum())
        assertNotNull(r.snapshot())
    }

    @Test fun noSnapshotBeforeStartOrAfterTheRoundEnds() {
        val g = WhackAMoleGame(Random(1), RdWhClock())
        g.init(rdWhContext(2, 0))
        assertNull(g.snapshot())
        val (h, c) = game()
        assertNotNull(h.snapshot())
        c.now = h.engine!!.playStartAt + 61_000L
        h.tick()
        assertNull(h.snapshot())
    }

    @Test fun staleOrCorruptSnapshotsAreRefusedWithoutTouchingTheGame() {
        val (g, _) = game(humans = 2, bots = 1)
        val snap = g.snapshot()!!
        val wrongRoster = WhackAMoleGame(Random(1), RdWhClock()); wrongRoster.init(rdWhContext(3, 0))
        assertFalse(wrongRoster.restore(snap)); assertNull(wrongRoster.engine)
        val same = WhackAMoleGame(Random(1), RdWhClock()); same.init(rdWhContext(2, 1))
        for (bad in listOf("", "{", "[]", "{}", snap.replace("\"timeS\":60", "\"timeS\":61"), snap.replace("\"grid\":3", "\"grid\":5"), snap.replace("\"difficulty\":\"MEDIUM\"", "\"difficulty\":\"X\""),
            Regex("\"sessionDraws\":\\d+").replace(snap, "\"sessionDraws\":-3"))) {
            assertFalse("accepted: $bad", same.restore(bad)); assertNull(same.engine)
        }
        assertTrue(same.restore(snap))
    }

    @Test fun randomSessionsWithSavesAndResumesAlwaysEnd() {
        for (seed in 0 until 40) {
            val rnd = java.util.Random(seed.toLong())
            val clock = RdWhClock()
            val humans = 1 + rnd.nextInt(3); val bots = if (humans == 1) 1 + rnd.nextInt(3) else rnd.nextInt(5 - humans)
            var (g, _) = game(humans = humans, bots = bots, seed = seed, clock = clock, time = listOf(60, 90, 120)[rnd.nextInt(3)], grid = 3 + rnd.nextInt(2), relaxed = rnd.nextBoolean(), oneSwitch = rnd.nextInt(4) == 0)
            var steps = 0
            while (g.engine!!.phase != WhackPhase.OVER && steps++ < 6_000) {
                clock.now += rnd.nextInt(400).toLong()
                when (rnd.nextInt(8)) {
                    0, 1, 2 -> g.whack(rnd.nextInt(humans + bots), rnd.nextInt(18) - 1, rnd.nextInt(80).toLong())
                    3 -> {
                        val snap = g.snapshot()
                        if (snap != null) {
                            val n = WhackAMoleGame(Random(seed), clock)
                            n.init(rdWhContext(humans, bots))
                            assertTrue("seed $seed: $snap", n.restore(snap))
                            g = n
                        }
                    }
                    else -> g.tick()
                }
            }
            var guard = 0
            while (g.engine!!.phase != WhackPhase.OVER && guard++ < 50) { clock.now += 30_000; g.tick() }
            assertEquals("seed $seed", WhackPhase.OVER, g.engine!!.phase)
            assertEquals(1, g.sessionWins.value.values.sum() + g.sessionDraws.value)
        }
    }
}
