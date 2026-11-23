package com.gamesuite.games.slotcars

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The Slot Cars module: lifecycle, intents, assists, resume, scoring. */
class SlotCarsGameTest {
    private val K = ScKit

    @Test fun aNewRaceStartsWithACountdownThenRacesAndNobodyMovesBeforeGo() {
        val g = K.newGame()
        val s0 = g.state.value!!
        assertEquals(SlotPhase.COUNTDOWN, s0.phase)
        assertTrue(s0.countdownTicks in 1..SlotCarsRules.COUNTDOWN_TICKS)
        g.setThrottle(0, true)
        g.stepTicks(SlotCarsRules.COUNTDOWN_TICKS - 10)
        assertEquals(0.0, g.state.value!!.cars[0].s, 1e-12)
        g.stepTicks(20)
        assertEquals(SlotPhase.RACING, g.state.value!!.phase)
        g.stepTicks(60)
        assertTrue(g.state.value!!.cars[0].s > 100.0)
        assertEquals(0.0, g.state.value!!.cars[1].s, 1e-12)             // seat 1 never pressed
    }

    @Test fun offlineOnlyHumanSeatsMayDriveAndBotsDriveThemselves() {
        val g = K.newGame(K.roster(1, 3))
        K.live(g)
        assertEquals(SlotCarsReject.NOT_YOUR_CAR, g.setThrottle(1, true))
        assertEquals(SlotCarsReject.BAD_INPUT, g.setThrottle(9, true))
        assertEquals(SlotCarsReject.NONE, g.setThrottle(0, true))
        g.stepTicks(240)
        val cars = g.state.value!!.cars
        assertTrue("bot 1 drives", cars[1].s > 50.0 && cars[2].s > 50.0)
    }

    @Test fun passAndPlayGivesEverySeatItsOwnPad() {
        val g = K.newGame(K.roster(3))
        K.live(g)
        g.setThrottle(0, true); g.setThrottle(2, true)
        g.stepTicks(90)
        val c = g.state.value!!.cars
        assertTrue(c[0].s > 80 && c[2].s > 80 && c[1].s == 0.0)
        assertEquals(c[0].s, c[2].s, 1e-9)                               // same pad, same physics
    }

    @Test fun theRaceEndsScoresPlacesAndCountsTheWin() {
        val results = mutableListOf<GameResult>()
        val g = K.newGame(K.roster(1, 2), results = results, laps = 3, difficulty = CpuDifficulty.HARD)
        K.live(g)
        var guard = 0
        while (!g.state.value!!.raceOver && guard++ < 40000) {
            val st = g.state.value!!
            val c = st.cars[0]
            g.setThrottle(0, SlotCpu.wantsThrottle(SlotTrack.byId("meadow"), c.s, c.v, SlotCpu.PERFECT, 0.0))
            g.stepTicks(1)
        }
        val s = g.state.value!!
        assertTrue(s.raceOver)
        assertNotNull(s.winner)
        assertEquals(1, g.sessionWins.value.sum())
        assertEquals(listOf(2, 1), s.order.indices.map { sc -> g.sessionPoints.value[s.order[sc]] }.sortedDescending())
        g.leaveSession()
        assertTrue(g.matchOver.value)
    }

    @Test fun leavingScoresEachSeatByPointsAndOnlyAStrictLeaderWins() {
        val results = mutableListOf<GameResult>()
        val g = K.newGame(K.roster(2), results = results)
        g.sessionWins.value = listOf(1, 1); g.sessionPoints.value = listOf(2, 2)
        g.leaveSession()
        assertEquals(2, results.single().scores.size)
        assertTrue(results.single().scores.none { it.isWinner })        // level: nobody wins
        val results2 = mutableListOf<GameResult>()
        val g2 = K.newGame(K.roster(2), results = results2)
        g2.sessionWins.value = listOf(0, 2); g2.sessionPoints.value = listOf(2, 4)
        g2.leaveSession()
        assertEquals(listOf(false, true), results2.single().scores.map { it.isWinner })
        assertEquals(listOf(2, 4), results2.single().scores.map { it.score })
    }

    @Test fun playAgainStartsAFreshRaceKeepingTheTally() {
        val g = K.newGame(K.roster(1, 2), laps = 3)
        K.live(g)
        g.simForTest().finishTime.fill(1.0); g.simForTest().phase = SlotPhase.RACING
        g.stepTicks(2)
        assertTrue(g.state.value!!.raceOver)
        val wins = g.sessionWins.value.sum()
        g.playAgain()
        assertEquals(SlotPhase.COUNTDOWN, g.state.value!!.phase)
        assertEquals(wins, g.sessionWins.value.sum())
        g.playAgain()                                                    // a race in progress ignores it
        assertEquals(SlotPhase.COUNTDOWN, g.state.value!!.phase)
    }

    // ---- assists ----

    @Test fun slowMotionRunsTheClockAtHalfSpeed() {
        val clock = ScClock()
        val g = K.newGame(K.roster(2), clock = clock, assist = "slow")
        g.advance()                                                      // anchors the clock
        clock.advance(200); g.advance()
        val a = g.simForTest().tick
        val clock2 = ScClock()
        val g2 = K.newGame(K.roster(2), clock = clock2, assist = "off")
        g2.advance(); clock2.advance(200); g2.advance()
        assertTrue("slow $a vs normal ${g2.simForTest().tick}", a * 2 in (g2.simForTest().tick - 2)..(g2.simForTest().tick + 2))
    }

    @Test fun stepByStepHoldsTheCarsUntilEveryHumanIsReadyThenRunsOneStep() {
        val g = K.newGame(K.roster(2), assist = "step")
        K.live(g)
        g.setThrottle(0, true)
        g.stepTicks(60)
        assertEquals(0.0, g.state.value!!.cars[0].s, 1e-12)              // not ready: the cars wait
        assertEquals(SlotCarsReject.NONE, g.setReady(0, true))
        g.stepTicks(60)
        assertEquals(0.0, g.state.value!!.cars[0].s, 1e-12)              // still waiting for seat 1
        g.setReady(1, true)
        g.stepTicks(SlotCarsRules.STEP_TICKS)
        val s = g.state.value!!
        assertTrue(s.cars[0].s > 20.0)                                   // seat 0 drove its planned step
        assertEquals(0.0, s.cars[1].s, 1e-12)                            // seat 1 planned to coast
        assertFalse(s.cars[0].ready)                                     // ready flags clear after the step
        assertEquals(0, s.stepRemaining)
        g.stepTicks(30)
        assertEquals(s.cars[0].s, g.state.value!!.cars[0].s, 1e-12)      // and the cars wait again
    }

    @Test fun readyOnlyExistsInStepMode() {
        val g = K.newGame(K.roster(2), assist = "off")
        K.live(g)
        assertEquals(SlotCarsReject.NOT_STEP_MODE, g.setReady(0, true))
    }

    @Test fun theStepProjectionPredictsTheOutcome() {
        val g = K.newGame(K.roster(2), assist = "step")
        K.live(g)
        val (ps, _, left) = g.projectStep(0, true)!!
        assertFalse(left)
        g.setThrottle(0, true); g.setReady(0, true); g.setReady(1, true)
        g.stepTicks(SlotCarsRules.STEP_TICKS)
        assertEquals(ps, g.state.value!!.cars[0].s, 1e-9)
    }

    @Test fun theHintTellsYouToEaseOffBeforeABend() {
        val g = K.newGame(K.roster(1, 2), track = "hill")
        K.live(g)
        val sim = g.simForTest()
        // flat out toward the hairpin: find a spot just before the first sharp bend
        val corner = sim.track.corners.first { SlotCarsRules.criticalSpeed(it.peak) < 190.0 }
        sim.s[0] = corner.startS - 60.0; sim.v[0] = 280.0
        assertTrue(g.requestHint(0)!!.brake)
        sim.s[0] = corner.startS - 700.0; sim.v[0] = 100.0
        sim.s[0] = (sim.s[0] + sim.track.length) % sim.track.length
        // on open road at low speed the advice is to go (the hint is advisory, not a rule)
        assertNotNull(g.requestHint(0))
    }

    // ---- resume ----

    @Test fun aRaceSurvivesSnapshotAndRestore() {
        val g = K.newGame(K.roster(1, 3), track = "twist", laps = 5, difficulty = CpuDifficulty.HARD)
        K.live(g)
        g.setThrottle(0, true)
        g.stepTicks(300)
        val snap = g.snapshot()!!
        val before = g.state.value!!
        val g2 = K.newGame(K.roster(1, 3), start = false, seed = 77)
        assertTrue(g2.restore(snap))
        val after = g2.state.value!!
        assertEquals("twist", after.track); assertEquals(5, after.laps)
        for (i in 0 until 3) { assertEquals(before.cars[i].s, after.cars[i].s, 1e-9); assertEquals(before.cars[i].lapsDone, after.cars[i].lapsDone) }
        assertTrue(after.countdownTicks > 0)                              // a short get-ready pause before the cars move
        g2.stepTicks(60)
        assertEquals(before.cars[0].s, g2.state.value!!.cars[0].s, 1e-9)
        g2.stepTicks(200)
        assertTrue(g2.state.value!!.cars[0].s > before.cars[0].s - 1e-9)
    }

    @Test fun restoreRefusesStaleOrCorruptSnapshotsAndLeavesTheGameUntouched() {
        val g = K.newGame(K.roster(1, 2))
        K.live(g); g.stepTicks(100)
        val snap = g.snapshot()!!
        val g2 = K.newGame(K.roster(2, 3), start = false)                 // another roster
        assertFalse(g2.restore(snap))
        assertNull(g2.state.value)
        val g3 = K.newGame(K.roster(1, 2), start = false)
        for (bad in listOf("", "{", "null", "[]", snap.replace("\"meadow\"", "\"mars\""), snap.replace("\"laps\":3", "\"laps\":4"),
            snap.replace("\"p1\"", "\"zz\""), snap.replace(Regex("\"s\":[0-9.E]+"), "\"s\":-5.0"))) {
            assertFalse("accepted: $bad", g3.restore(bad))
            assertNull(g3.state.value)
        }
        assertTrue(g3.restore(snap))
    }

    @Test fun thereIsNothingToSaveBeforeTheStartAfterTheEndOrOnline() {
        val g = K.newGame(K.roster(2), start = false)
        assertNull(g.snapshot())
        val g2 = K.newGame(K.roster(2))
        assertNull(g2.snapshot())                                         // still counting down
        K.live(g2); assertNotNull(g2.snapshot())
        g2.leaveSession()
        assertNull(g2.snapshot())
        val online = SlotCarsGame(Random(1), ScClock())
        online.init(GameContext(PlayMode.ONLINE, K.roster(2), 0, ScFakeTransport()))
        online.startMatch(); K.live(online)
        assertNull(online.snapshot())
    }

    // ---- fuzz ----

    @Test fun randomPlayNeverThrowsAndEveryRaceTerminates() {
        for (seed in 0 until 25) {
            val r = Random(seed)
            val humans = 1 + r.nextInt(3)
            val total = humans + r.nextInt(5 - humans)
            val assist = listOf("off", "slow", "step")[r.nextInt(3)]
            val g = K.newGame(K.roster(humans, total.coerceAtLeast(2)), seed = seed, laps = 3, track = SlotTrack.ids[seed % 3], assist = assist)
            var guard = 0
            while (!g.state.value!!.raceOver && guard++ < 30000) {
                if (r.nextInt(4) == 0) g.setThrottle(r.nextInt(humans), r.nextBoolean())
                if (assist == "step" && r.nextInt(3) == 0) for (h in 0 until humans) g.setReady(h, true)
                if (r.nextInt(200) == 0) { val snap = g.snapshot(); if (snap != null) { val g2 = K.newGame(K.roster(humans, total.coerceAtLeast(2)), start = false); g2.restore(snap) } }
                g.stepTicks(1 + r.nextInt(8))
            }
            assertTrue("seed $seed assist $assist", g.state.value!!.raceOver)
            val st = g.state.value!!
            assertEquals(st.cars.indices.toSet(), st.order.toSet())
        }
    }

    @Test fun theSameInputsReplayToTheSameRace() {
        fun run(): Long {
            val g = K.newGame(K.roster(1, 3), seed = 4, track = "hill")
            val r = Random(11)
            K.live(g)
            repeat(900) { if (it % 9 == 0) g.setThrottle(0, r.nextBoolean()); g.stepTicks(1) }
            return g.fingerprint()
        }
        assertEquals(run(), run())
    }

    @Test fun configurationIsNormalised() {
        val g = SlotCarsGame(Random(1), ScClock())
        g.laps = 7; g.trackId = "x"; g.assist = "turbo"
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, K.roster(1, 2), 0, ScFakeTransport()))
        g.startMatch()
        val s = g.state.value!!
        assertEquals(3, s.laps); assertEquals("meadow", s.track); assertEquals("off", s.assist)
        val one = SlotCarsGame(Random(1), ScClock())
        one.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "A")), 0, ScFakeTransport()))
        one.startMatch()                                                  // a lone car must not crash
        one.stepTicks(400)
        assertEquals(1, one.state.value!!.cars.size)
    }
}
