package com.gamesuite.games.partypack

import com.gamesuite.core.GameResult
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PaddleDuelGameTest {
    private fun game(humans: Int, bots: Int, clock: PpClock = PpClock(), seed: Long = 5L, configure: PaddleDuelGame.() -> Unit = {}): PaddleDuelGame {
        val g = PaddleDuelGame(clock, { seed })
        g.configure()
        g.init(ppContext(humans, bots))
        return g
    }

    @Test fun theClockDrivesTheEngineAndTheFirstTickOnlySetsTheBaseline() {
        val clock = PpClock(); val g = game(2, 0, clock)
        g.startMatch()
        g.tick()
        assertEquals(0.0, g.engine!!.simTime, 0.0)
        repeat(10) { clock.advance(50); g.tick() }
        assertEquals(0.5, g.engine!!.simTime, 0.02)
        clock.advance(60_000); g.tick() // a long stall is clamped to a quarter second
        assertEquals(0.75, g.engine!!.simTime, 0.02)
    }

    @Test fun pausingStopsTimeAndResumingRebaselines() {
        val clock = PpClock(); val g = game(2, 0, clock)
        g.startMatch(); g.tick(); g.pause()
        clock.advance(1000); g.tick()
        assertEquals(0.0, g.engine!!.simTime, 0.0)
        g.resume(); g.tick(); clock.advance(100); g.tick()
        assertEquals(0.1, g.engine!!.simTime, 0.02)
    }

    @Test fun gameSpeedTimeScaleSlowsPlay() {
        val clock = PpClock(); val g = game(2, 0, clock); g.timeScale = 0.5
        g.startMatch(); g.tick(); repeat(20) { clock.advance(50); g.tick() }
        assertEquals(0.5, g.engine!!.simTime, 0.02)
    }

    @Test fun botSeatsRefuseInputAndTheRosterBecomesSeats() {
        val g = game(1, 3); g.startMatch()
        assertEquals(4, g.engine!!.n)
        assertTrue(g.isBotSeat(1) && !g.isBotSeat(0))
        g.setTarget(1, 0.9); g.setHold(2, 1)
        // nothing to assert on the bots' paddles beyond: it does not throw and the human seat works
        g.setTarget(0, 0.1)
    }

    @Test fun settingsFromTheHubReachTheEngine() {
        val g = game(2, 1) { formatId = "l5"; powerups = true; relaxed = true; oneSwitch = true; difficulty = CpuDifficulty.HARD }
        g.startMatch()
        val c = g.engine!!.config
        assertEquals(PdFormat(false, 5), c.format); assertTrue(c.powerups && c.relaxed)
        assertTrue(c.seats[0].oneSwitch && c.seats[1].oneSwitch); assertFalse(c.seats[2].oneSwitch)
        assertEquals(CpuDifficulty.HARD, c.seats[2].level); assertTrue(c.seats[2].bot)
    }

    @Test fun snapshotRoundTripsScoresAndRestartsTheRally() {
        val a = game(2, 0, seed = 9) { formatId = "p5" }
        a.startMatch()
        val e = a.engine!!
        // play two points by hand
        repeat(2) {
            while (e.phase == PdPhase.SERVE) e.step()
            val b = e.balls.first(); b.x = 0.1; b.y = 1.2; b.vx = 0.0; b.vy = 0.8; e.paddlePos[0] = 0.9
            repeat(200) { e.step() }
        }
        assertEquals(listOf(0, 2), e.score.toList())
        val snap = a.snapshot()
        assertNotNull(snap)
        val b = game(2, 0, seed = 77); b.formatId = "p7" // the snapshot's format wins
        assertTrue(b.restore(snap!!))
        assertEquals(listOf(0, 2), b.engine!!.score.toList())
        assertEquals(PdFormat(true, 5), b.engine!!.config.format)
        assertEquals(PdPhase.SERVE, b.engine!!.phase) // a mid-rally restore starts the rally over
        assertEquals(1, b.engine!!.balls.size)
    }

    @Test fun livesSnapshotKeepsEliminations() {
        val a = game(3, 0) { formatId = "l3" }
        a.startMatch(); val e = a.engine!!
        // knock seat 1 (the LEFT edge with three players) out
        repeat(3) {
            while (e.phase == PdPhase.SERVE) e.step()
            e.paddlePos[1] = 0.85
            val b = e.balls.first(); b.x = 0.3; b.y = 0.5; b.vx = -0.8; b.vy = 0.0
            repeat(300) { e.step() }
        }
        assertEquals(listOf(true, false, true), e.alive.toList())
        val b = game(3, 0); assertTrue(b.restore(a.snapshot()!!))
        assertEquals(e.alive.toList(), b.engine!!.alive.toList()); assertEquals(e.lives.toList(), b.engine!!.lives.toList())
    }

    @Test fun badSnapshotsAreRefusedWithoutTouchingTheGame() {
        val a = game(2, 0); a.startMatch()
        val good = a.snapshot()!!
        val bads = listOf(
            "", "garbage", "{}", good.replace("\"seats\":2", "\"seats\":4"), good.replace("\"format\":\"p7\"", "\"format\":\"p8\""),
            good.replace("\"score\":[0,0]", "\"score\":[99,0]"), good.replace("\"score\":[0,0]", "\"score\":[-1,0]"), good.replace("\"score\":[0,0]", "\"score\":[0]"),
            good.replace("\"matchNo\":1", "\"matchNo\":0"), good.replace("\"rallies\":0", "\"rallies\":-4")
        )
        for (bad in bads) {
            val g = game(2, 0)
            assertFalse("accepted: $bad", g.restore(bad))
            assertNull(g.engine)
        }
        // a lives snapshot whose alive flags contradict the lives
        val l = game(3, 0) { formatId = "l3" }; l.startMatch()
        val ls = l.snapshot()!!
        assertFalse(game(3, 0).restore(ls.replace("\"alive\":[true,true,true]", "\"alive\":[true,false,true]")))
        assertFalse(game(3, 0).restore(ls.replace("\"alive\":[true,true,true]", "\"alive\":[false,false,true]")))
        // a snapshot of a different roster size
        assertFalse(game(3, 0).restore(good))
    }

    @Test fun snapshotsAreNullWhenThereIsNothingToSave() {
        val g = game(2, 0)
        assertNull(g.snapshot())
        g.startMatch(); assertNotNull(g.snapshot())
        val e = g.engine!!
        repeat(7) {
            while (e.phase == PdPhase.SERVE) e.step()
            val b = e.balls.first(); b.x = 0.1; b.y = 1.2; b.vx = 0.0; b.vy = 0.8; e.paddlePos[0] = 0.9
            repeat(200) { e.step() }
        }
        assertTrue(e.over); assertNull(g.snapshot())
    }

    @Test fun finishedMatchesAreTalliedAndLeaveReportsAStrictLeaderOnly() {
        val g = game(2, 0, seed = 3) { formatId = "p5" }
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.startMatch()
        fun finishMatch() {
            val e = g.engine!!
            repeat(5) {
                while (e.phase == PdPhase.SERVE) e.step()
                val b = e.balls.first(); b.x = 0.1; b.y = 1.2; b.vx = 0.0; b.vy = 0.8; e.paddlePos[0] = 0.9
                repeat(200) { e.step() }
            }
            assertTrue(e.over)
            g.noteFinished(); g.noteFinished() // idempotent
        }
        finishMatch()
        assertEquals(mapOf("p1" to 0, "p2" to 1), g.sessionWins.value)
        g.playAgain()
        assertFalse(g.engine!!.over)
        finishMatch()
        assertEquals(2, g.sessionWins.value["p2"])
        g.leaveSession()
        assertTrue(g.matchOver.value)
        val scores = result!!.scores.associateBy { it.playerId }
        assertTrue(scores["p2"]!!.isWinner); assertFalse(scores["p1"]!!.isWinner)
        assertFalse(result!!.wasAborted)
    }

    @Test fun leavingWithNoFinishedMatchNamesNoWinnerAndAbortWorks() {
        val g = game(2, 0); var r: GameResult? = null
        g.setOnMatchEnd { r = it }
        g.startMatch(); g.abortMatch()
        assertTrue(r!!.wasAborted && r!!.scores.isEmpty())
        val h = game(2, 0); var r2: GameResult? = null
        h.setOnMatchEnd { r2 = it }
        h.startMatch(); h.leaveSession()
        assertTrue(r2!!.scores.none { it.isWinner })
    }

    @Test fun fuzzSnapshotRoundTripsAtRandomMomentsOfRandomMatches() {
        val rnd = Random(21)
        repeat(80) { run ->
            val humans = 1 + rnd.nextInt(3); val bots = if (humans == 1) 1 + rnd.nextInt(3) else rnd.nextInt(5 - humans)
            val fmt = PdFormat.ALL[rnd.nextInt(PdFormat.ALL.size)]
            val clock = PpClock()
            val g = game(humans, bots, clock, seed = run.toLong()) { formatId = fmt.id; powerups = rnd.nextBoolean(); relaxed = rnd.nextBoolean() }
            g.startMatch(); g.tick()
            repeat(rnd.nextInt(2000)) { clock.advance(rnd.nextLong(5, 40)); g.tick(); if (it % 50 == 0) g.drainEvents() }
            val snap = g.snapshot() ?: return@repeat
            val h = game(humans, bots)
            assertTrue("run $run: $snap", h.restore(snap))
            assertEquals(g.engine!!.scores().score, h.engine!!.scores().score)
            assertEquals(g.engine!!.scores().lives, h.engine!!.scores().lives)
            assertEquals(g.engine!!.scores().alive, h.engine!!.scores().alive)
            assertEquals(snap, h.snapshot()) // restoring and saving again changes nothing
        }
    }
}
