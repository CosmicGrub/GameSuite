package com.gamesuite.games.reactionduel

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

/** The shell-facing Reaction Duel module on a fake clock: roster, scoring, save/resume. */
class ReactionDuelGameTest {
    private fun game(
        humans: Int = 2, bots: Int = 0, variant: DuelVariant = DuelVariant.CLASSIC, best: Int = 3, seed: Int = 1,
        clock: RdWhClock = RdWhClock(), level: CpuDifficulty = CpuDifficulty.MEDIUM, relaxed: Boolean = false, oneSwitch: Boolean = false
    ): Pair<ReactionDuelGame, RdWhClock> {
        val g = ReactionDuelGame(Random(seed), clock)
        g.variant = variant; g.bestOf = best; g.difficulty = level; g.relaxed = relaxed; g.oneSwitch = oneSwitch
        g.init(rdWhContext(humans, bots))
        g.startMatch()
        return g to clock
    }

    private fun goLive(g: ReactionDuelGame, c: RdWhClock) {
        val e = g.engine!!
        c.now = e.signalAt
        g.tick()
    }

    @Test fun computerSeatsFillTheEmptyZones() {
        val (g, _) = game(humans = 1, bots = 2)
        assertEquals(3, g.engine!!.config.seats)
        assertEquals(setOf(1, 2), g.engine!!.config.cpuSeats)
        assertEquals(DuelVariant.CLASSIC, g.engine!!.config.variant)
        val (h, _) = game(humans = 4, bots = 0)
        assertTrue(h.engine!!.config.cpuSeats.isEmpty())
    }

    @Test fun aPressCarriesItsAgeSoADelayedFrameNeverCostsAPlayerTheirTime() {
        val (g, c) = game()
        goLive(g, c)
        val s = g.engine!!.signalAt
        c.now = s + 200
        g.press(0, ageMs = 0)                 // touched at +200
        c.now = s + 230
        g.press(1, ageMs = 120)               // touched at +110, delivered 30 ms later
        assertEquals(1, g.engine!!.lastOutcome!!.winner)
        assertEquals(110, g.engine!!.reactionMs[1])
    }

    @Test fun anEchoTouchThatMissedEveryPadIsNothingNotAMistake() {
        val (g, c) = game(variant = DuelVariant.ECHO)
        goLive(g, c)
        assertEquals(PressResult.IGNORED, g.press(0, 0, -1))
        assertEquals(SeatStatus.ARMED, g.engine!!.seatStatus[0])
        assertEquals(PressResult.WRONG, g.press(0, 0, (g.engine!!.sequence[0] + 1) % 4))
    }

    @Test fun theSessionTalliesTheDuelWinnerAndLeavingReportsIt() {
        val (g, c) = game(best = 3)
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        repeat(2) {
            val e = g.engine!!
            c.now = e.liveStartAt; g.tick()
            goLive(g, c)
            c.now = e.signalAt + 150; g.press(0)
            c.now += DuelRules.GATHER_MS + 5; g.tick()
            c.now += DuelRules.RESULT_MS + 5; g.tick()
        }
        assertEquals(DuelPhase.OVER, g.engine!!.phase)
        assertEquals(1, g.sessionWins.value["p1"]); assertEquals(0, g.sessionWins.value["p2"])
        g.leaveSession()
        val r = result!!
        assertFalse(r.wasAborted)
        assertEquals(listOf(true, false), r.scores.map { it.isWinner })
        assertEquals(listOf(1, 0), r.scores.map { it.score })
    }

    @Test fun aDrawnSessionHasNoWinner() {
        val (g, _) = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertTrue(result!!.scores.none { it.isWinner })
    }

    @Test fun playAgainStartsANewMatchOnlyOnceTheOldOneIsOver() {
        val (g, c) = game(best = 3)
        val first = g.engine
        g.playAgain()
        assertTrue(first === g.engine)             // still playing: nothing happens
        var guard = 0
        while (g.engine!!.phase != DuelPhase.OVER && guard++ < 100) { c.now = g.engine!!.deadlineAt + DuelRules.RESULT_MS + 1000; g.tick() }
        g.playAgain()
        assertFalse(first === g.engine)
        assertEquals(1, g.engine!!.attempts)
        assertEquals(1, g.sessionDraws.value)
    }

    // ---- save and resume ----

    private fun played(variant: DuelVariant = DuelVariant.CLASSIC): Pair<ReactionDuelGame, RdWhClock> {
        val (g, c) = game(humans = 2, bots = 1, variant = variant, best = 5, relaxed = true)
        for (round in 0 until 2) {
            val e = g.engine!!
            c.now = e.liveStartAt; g.tick(); goLive(g, c)
            c.now = e.signalAt + 100
            if (variant == DuelVariant.ECHO) for ((i, p) in e.sequence.withIndex()) { c.now = e.signalAt + 100 + 10L * i; g.press(0, 0, p) } else g.press(0)
            c.now += 300; g.tick()
            c.now += DuelRules.RESULT_MS + 10; g.tick()
        }
        return g to c
    }

    @Test fun snapshotRoundTripsTheProgressAndRestartsTheRoundInFlight() {
        for (v in DuelVariant.entries) {
            val (g, c) = played(v)
            val snap = g.snapshot()
            assertNotNull(snap)
            val before = g.engine!!
            val r = ReactionDuelGame(Random(9), RdWhClock(777))
            r.init(rdWhContext(2, 1))
            assertTrue(r.restore(snap!!))
            val after = r.engine!!
            assertEquals(before.wins.toList(), after.wins.toList())
            assertEquals(before.config.variant, after.config.variant)
            assertEquals(before.config.relaxed, after.config.relaxed)
            assertEquals(before.config.bestOf, after.config.bestOf)
            assertEquals(before.config.seed, after.config.seed)
            assertEquals(DuelPhase.COUNTDOWN, after.phase)
            assertEquals(before.progress().completedAttempts + 1, after.attempts)
            assertEquals(g.sessionWins.value, r.sessionWins.value)
            // The same round replays: the signal is the same distance after its countdown.
            assertEquals(before.signalAt - before.liveStartAt, after.signalAt - after.liveStartAt)
            assertNotNull(r.snapshot()) // a restored match can be saved again
            assertTrue(c.now >= 0)
        }
    }

    @Test fun noSnapshotBeforeStartOrOnceTheMatchIsOver() {
        val g = ReactionDuelGame(Random(1), RdWhClock())
        g.init(rdWhContext(2, 0))
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        val (h, clock) = game()
        var guard = 0
        while (h.engine!!.phase != DuelPhase.OVER && guard++ < 100) { clock.now = h.engine!!.deadlineAt + DuelRules.RESULT_MS + 1000; h.tick() }
        assertNull(h.snapshot())
    }

    @Test fun aSnapshotForAnotherRosterOrCorruptTextIsRefusedAndLeavesTheGameAlone() {
        val (g, _) = played()
        val snap = g.snapshot()!!
        val other = ReactionDuelGame(Random(2), RdWhClock())
        other.init(rdWhContext(3, 0)) // different players and bots
        assertFalse(other.restore(snap))
        assertNull(other.engine)
        val same = ReactionDuelGame(Random(2), RdWhClock())
        same.init(rdWhContext(2, 1))
        for (bad in listOf("", "{", "not json", "{}", snap.replace("\"bestOf\":5", "\"bestOf\":4"), snap.replace("\"variant\":\"classic\"", "\"variant\":\"nope\""),
            snap.replace("\"wins\":[", "\"wins\":[9,"), snap.replace("\"difficulty\":\"MEDIUM\"", "\"difficulty\":\"EXPERT\""))) {
            assertFalse("accepted: $bad", same.restore(bad))
            assertNull(same.engine)
        }
        assertTrue(same.restore(snap))
    }

    @Test fun aSnapshotWithImpossibleCountsIsRefused() {
        val (g, _) = played()
        val snap = g.snapshot()!!
        val r = ReactionDuelGame(Random(2), RdWhClock())
        r.init(rdWhContext(2, 1))
        // Wins at or above the target would mean the match had already ended.
        assertFalse(r.restore(Regex("\"wins\":\\[[^\\]]*\\]").replace(snap, "\"wins\":[3,0,0]")))
        assertFalse(r.restore(Regex("\"completed\":\\d+").replace(snap, "\"completed\":99")))
        assertFalse(r.restore(Regex("\"bestMs\":\\[[^\\]]*\\]").replace(snap, "\"bestMs\":[-5,0,0]")))
        assertFalse(r.restore(Regex("\"sessionDraws\":\\d+").replace(snap, "\"sessionDraws\":-1")))
        assertFalse(r.restore(Regex("\"roundsPlayed\":\\d+").replace(snap, "\"roundsPlayed\":50")))
    }

    @Test fun randomSessionsWithSavesAndResumesAlwaysTerminate() {
        for (seed in 0 until 60) {
            val rnd = java.util.Random(seed.toLong())
            val clock = RdWhClock()
            val humans = 1 + rnd.nextInt(3); val bots = if (humans == 1) 1 + rnd.nextInt(3) else rnd.nextInt(5 - humans)
            var (g, _) = game(humans = humans, bots = bots, variant = DuelVariant.entries[rnd.nextInt(3)], best = listOf(3, 5, 7)[rnd.nextInt(3)], seed = seed, clock = clock, relaxed = rnd.nextBoolean())
            var steps = 0
            while (g.engine!!.phase != DuelPhase.OVER && steps++ < 3_000) {
                clock.now += rnd.nextInt(500).toLong()
                when (rnd.nextInt(6)) {
                    0 -> g.press(rnd.nextInt(humans + bots), rnd.nextInt(100).toLong(), rnd.nextInt(6) - 1)
                    1 -> {
                        val snap = g.snapshot()
                        if (snap != null) {
                            val n = ReactionDuelGame(Random(seed), clock)
                            n.init(rdWhContext(humans, bots))
                            assertTrue("seed $seed: $snap", n.restore(snap))
                            g = n
                        }
                    }
                    else -> g.tick()
                }
            }
            var guard = 0
            while (g.engine!!.phase != DuelPhase.OVER && guard++ < 500) { clock.now += 6_000; g.tick() }
            assertEquals("seed $seed", DuelPhase.OVER, g.engine!!.phase)
            assertEquals(1, g.sessionWins.value.values.sum() + g.sessionDraws.value)
        }
    }
}
