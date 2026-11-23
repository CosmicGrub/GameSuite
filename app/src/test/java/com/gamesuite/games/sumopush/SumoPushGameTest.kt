package com.gamesuite.games.sumopush

import com.gamesuite.core.GameResult
import com.gamesuite.games.partypack.TsClock
import com.gamesuite.games.partypack.tsContext
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Match flow, input mapping, scoring, resume, CPU strength ordering and fuzz, all on a hand-wound clock. */
class SumoPushGameTest {
    private fun game(humans: Int, bots: Int, seed: Int = 1, rounds: Int = 3, arena: SumoArena = SumoArena.MEDIUM, clock: TsClock = TsClock()): SumoPushGame {
        val g = SumoPushGame(Random(seed), clock.read)
        g.config = SumoConfig(humans + bots, arena, rounds)
        g.init(tsContext(humans, bots))
        g.startMatch()
        return g
    }

    private fun SumoPushGame.toRunning() { advance(SumoRules.COUNTDOWN_BEATS * SumoRules.COUNTDOWN_BEAT_MS) }
    private fun SumoPushGame.toNextRound() { advance(SumoRules.ROUND_BREAK_MS); toRunning() }
    private fun SumoPushGame.ms(n: Long) { var left = n; while (left > 0) { advance(minOf(16L, left)); left -= 16 } }

    /** Seat 1 (top) walks straight out over the top rope; seat 0 stands still and takes the round. */
    private fun SumoPushGame.seatOneWalksOut() { setInput(1, 0.0, -1.0); ms(2_500); setInput(1, 0.0, 0.0) }

    @Test fun theCountdownRunsThreeBeatsThenGo() {
        val g = game(2, 0)
        assertEquals(SuPhase.COUNTDOWN, g.info.value.phase)
        assertEquals(3, g.info.value.countdown)
        g.advance(800); assertEquals(2, g.info.value.countdown)
        g.advance(700); assertEquals(1, g.info.value.countdown)
        assertEquals(SuPhase.COUNTDOWN, g.info.value.phase)
        g.advance(700)
        assertEquals(SuPhase.RUNNING, g.info.value.phase)
        assertTrue(g.eventsSince(0).any { it.kind == SuLog.GO })
    }

    @Test fun sumosCannotMoveBeforeGoAndAHeldStickCountsFromGo() {
        val g = game(2, 0)
        g.setInput(0, 0.0, -1.0)
        g.advance(1_000)
        assertEquals(0.55, g.board.value!!.sumos[0].y, 1e-9)
        g.toRunning()
        g.ms(300)
        assertTrue(g.board.value!!.sumos[0].y < 0.55) // the stick was already held at the bell
    }

    @Test fun onlyLocalSeatsCanBeDriven() {
        val g = game(1, 1)
        assertTrue(g.setInput(0, 1.0, 0.0)); assertTrue(g.setDash(0, true))
        assertFalse(g.setInput(1, 1.0, 0.0)); assertFalse(g.setDash(1, true))
        assertFalse(g.setInput(9, 1.0, 0.0))
    }

    @Test fun holdingDashChargesAndReleasingFiresIt() {
        val g = game(2, 0)
        g.toRunning()
        g.setDash(0, true); g.ms(350)
        assertTrue(g.board.value!!.sumos[0].charge in 0.3f..0.7f)
        g.setDash(0, false); g.ms(32)
        assertTrue(g.eventsSince(0).any { it.kind == SuLog.DASH && it.seat == 0 })
        assertTrue(g.board.value!!.sumos[0].dashing || g.board.value!!.sumos[0].cooldown > 0f)
    }

    @Test fun aQuickTapStillDashes() {
        val g = game(2, 0)
        g.toRunning()
        g.setDash(0, true); g.setDash(0, false) // down and up between two steps
        g.ms(32)
        assertTrue(g.eventsSince(0).any { it.kind == SuLog.DASH && it.seat == 0 })
    }

    @Test fun lastSumoInTheRingWinsTheRoundAndTheMatchIsBestOfThree() {
        val g = game(2, 0)
        g.toRunning(); g.seatOneWalksOut()
        assertEquals(SuPhase.ROUND_OVER, g.info.value.phase)
        assertEquals(0, g.info.value.roundWinner)
        assertEquals(listOf(1, 0), g.info.value.wins)
        assertEquals(listOf(true, false), g.info.value.alive)
        assertTrue(g.eventsSince(0).any { it.kind == SuLog.FELL && it.seat == 1 })
        g.toNextRound()
        assertEquals(2, g.info.value.round)
        g.seatOneWalksOut()
        assertEquals(SuPhase.MATCH_OVER, g.info.value.phase)
        assertEquals(0, g.info.value.matchWinner)
        assertEquals(1, g.sessionWins.value["p1"])
    }

    @Test fun theFallenSumoKeepsFallingOnScreenDuringTheBreak() {
        val g = game(2, 0)
        g.toRunning(); g.seatOneWalksOut()
        val a = g.board.value!!.sumos[1].fallMs
        g.advance(300)
        val b = g.board.value!!.sumos[1].fallMs
        assertTrue(b > a || a == SumoRules.FALL_MS)
        assertEquals(0, g.board.value!!.sumos[0].fallMs)
    }

    @Test fun leavingReportsMatchesWonAndOnlyAStrictLeaderIsTheWinner() {
        val g = game(2, 0)
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.toRunning(); g.seatOneWalksOut(); g.toNextRound(); g.seatOneWalksOut()
        g.leaveSession()
        assertEquals(listOf(true, false), result!!.scores.map { it.isWinner })
        assertTrue(g.matchOver.value)
    }

    @Test fun playAgainKeepsTheSessionTallyAndRestartsTheMatch() {
        val g = game(2, 0)
        g.toRunning(); g.seatOneWalksOut(); g.toNextRound(); g.seatOneWalksOut()
        g.playAgain()
        assertEquals(SuPhase.COUNTDOWN, g.info.value.phase)
        assertEquals(listOf(0, 0), g.info.value.wins)
        assertEquals(1, g.sessionWins.value["p1"])
    }

    @Test fun pausingFreezesTheRingAndGameSpeedScalesTheSimulation() {
        val g = game(2, 0)
        g.toRunning()
        g.setInput(0, 0.0, -1.0)
        g.pause(); g.ms(1_000)
        assertEquals(0.55, g.board.value!!.sumos[0].y, 1e-9)
        g.resumePlay(); g.ms(400)
        val normal = g.board.value!!.sumos[0].y
        val slow = game(2, 0); slow.speedScale = 2.0
        slow.toRunning(); slow.setInput(0, 0.0, -1.0); slow.ms(400)
        assertTrue(slow.board.value!!.sumos[0].y > normal)
    }

    @Test fun snapshotRestoresAMatchBetweenRoundsAndRestartsTheCurrentRound() {
        val g = game(1, 1)
        g.toRunning(); g.ms(200)
        val snap = g.snapshot()!!
        val g2 = SumoPushGame(Random(5), TsClock().read)
        g2.init(tsContext(1, 1))
        assertTrue(g2.restore(snap))
        assertEquals(SuPhase.COUNTDOWN, g2.info.value.phase)
        assertEquals(1, g2.info.value.round)
        assertEquals(0.55, g2.board.value!!.sumos[0].y, 1e-9)
        assertEquals(snap, g2.snapshot())
    }

    @Test fun snapshotKeepsScoresAndSettings() {
        val g = game(2, 0, rounds = 5)
        g.config = g.config.copy(powerUps = true, arena = SumoArena.LARGE)
        g.startMatch(); g.toRunning(); g.seatOneWalksOut()
        g.config = g.config.copy(relaxed = true, oneSwitch = true) // the saved settings are whatever is set when the snapshot is taken
        val g2 = SumoPushGame(Random(2), TsClock().read)
        g2.init(tsContext(2, 0))
        assertTrue(g2.restore(g.snapshot()!!))
        assertEquals(listOf(1, 0), g2.info.value.wins)
        assertEquals(5, g2.info.value.rounds)
        assertTrue(g2.config.powerUps && g2.config.relaxed && g2.config.oneSwitch && g2.config.arena == SumoArena.LARGE)
        assertEquals(2, g2.info.value.round)
    }

    @Test fun restoreRejectsStaleCorruptAndTamperedSnapshots() {
        val g = game(2, 0)
        g.toRunning(); g.seatOneWalksOut()
        val good = g.snapshot()!!
        fun fresh(h: Int = 2, b: Int = 0) = SumoPushGame(Random(1), TsClock().read).also { it.init(tsContext(h, b)) }
        assertFalse(fresh().restore("nope"))
        assertFalse(fresh().restore("{}"))
        assertFalse(fresh(1, 1).restore(good))
        assertFalse(fresh().restore(good.replace("\"wins\":[1,0]", "\"wins\":[2,0]")))
        assertFalse(fresh().restore(good.replace("\"wins\":[1,0]", "\"wins\":[-3,9]")))
        assertFalse(fresh().restore(good.replace("\"rounds\":3", "\"rounds\":8")))
        assertFalse(fresh().restore(good.replace("\"MEDIUM\"", "\"BIGGEST\"")))
        assertTrue(fresh().restore(good))
    }

    @Test fun noSnapshotBeforeStartOrAfterTheMatchIsOver() {
        val g = SumoPushGame(Random(1), TsClock().read)
        g.init(tsContext(2, 0))
        assertNull(g.snapshot())
        g.startMatch(); g.toRunning(); g.seatOneWalksOut(); g.toNextRound(); g.seatOneWalksOut()
        assertNull(g.snapshot())
    }

    @Test fun theSameSeedAndInputsReplayTheSameMatch() {
        fun play(): List<Any> {
            val g = game(1, 2, seed = 5)
            val rnd = Random(9)
            var n = 0
            while (g.info.value.phase != SuPhase.MATCH_OVER && n++ < 20_000) {
                if (n % 11 == 0) g.setInput(0, rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1)
                if (n % 37 == 0) g.setDash(0, rnd.nextBoolean())
                g.advance(16)
            }
            return listOf(g.info.value.wins, g.info.value.matchWinner, g.board.value!!.sumos.map { it.x })
        }
        assertEquals(play(), play())
    }

    // ---- the computer ----

    private fun duel(a: CpuDifficulty, b: CpuDifficulty, matches: Int): Triple<Int, Int, Int> {
        var aw = 0; var bw = 0; var draws = 0
        for (m in 0 until matches) {
            val swap = m % 2 == 1
            val g = game(0, 2, seed = 500 + m)
            g.seatLevelOverride[0] = if (swap) b else a
            g.seatLevelOverride[1] = if (swap) a else b
            g.startMatch() // the computers are built per match: rebuild them with these levels
            var n = 0
            while (g.info.value.phase != SuPhase.MATCH_OVER && n++ < 200_000) g.advance(16)
            assertEquals(SuPhase.MATCH_OVER, g.info.value.phase)
            val w = g.info.value.matchWinner
            when {
                w < 0 -> draws++
                (w == 0) != swap -> aw++
                else -> bw++
            }
        }
        return Triple(aw, bw, draws)
    }

    @Test fun cpuLevelsAreClearlyOrderedButNoneIsInvincible() {
        val n = 80
        val hardEasy = duel(CpuDifficulty.HARD, CpuDifficulty.EASY, n)
        val medEasy = duel(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, n)
        val hardMed = duel(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, n)
        println("hard-easy $hardEasy medium-easy $medEasy hard-medium $hardMed")
        assertTrue("Hard beats Easy $hardEasy", hardEasy.first >= n * 8 / 10 && hardEasy.first > hardEasy.second * 4)
        assertTrue("Medium beats Easy $medEasy", medEasy.first > medEasy.second + n / 10)
        assertTrue("Hard beats Medium $hardMed", hardMed.first > hardMed.second + n / 10)
        // Nobody is perfect: Easy takes matches off Medium, and Medium takes matches or draws off Hard.
        assertTrue("Easy is beatable but not helpless $medEasy", medEasy.second >= 1)
        assertTrue("Medium is not helpless against Hard $hardMed", hardMed.second + hardMed.third >= 1)
    }

    @Test fun aFullCpuTableAtEveryLevelTerminates() {
        for (level in CpuDifficulty.entries) for (players in 2..4) {
            val g = game(1, players - 1, seed = 3 + players)
            g.difficulty = level
            var n = 0
            while (g.info.value.phase != SuPhase.MATCH_OVER && n++ < 300_000) g.advance(16)
            assertEquals("$level x $players", SuPhase.MATCH_OVER, g.info.value.phase)
        }
    }

    @Test fun fuzzRandomInputAndClockStepsAlwaysTerminateAndSnapshotsRoundTrip() {
        for (seed in 1..30) {
            val rnd = Random(seed)
            val humans = 1 + rnd.nextInt(3); val bots = if (humans == 1) 1 + rnd.nextInt(3) else rnd.nextInt(5 - humans)
            val g = game(humans, bots, seed = seed, rounds = if (rnd.nextBoolean()) 3 else 5, arena = SumoArena.entries[rnd.nextInt(3)])
            g.config = g.config.copy(powerUps = rnd.nextBoolean(), relaxed = rnd.nextBoolean(), oneSwitch = rnd.nextBoolean())
            g.startMatch()
            g.difficulty = CpuDifficulty.entries[rnd.nextInt(3)]
            var n = 0
            while (g.info.value.phase != SuPhase.MATCH_OVER) {
                assertTrue("runaway seed $seed", n++ < 400_000)
                val r = rnd.nextInt(12)
                if (r < 4) g.setInput(rnd.nextInt(humans + bots), rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1)
                if (r == 5) g.setDash(rnd.nextInt(humans + bots), rnd.nextBoolean())
                if (r == 11) g.pause()
                g.advance(1L + rnd.nextInt(120))
                g.resumePlay()
                if (rnd.nextInt(200) == 0) {
                    val snap = g.snapshot()
                    if (snap != null) {
                        val g2 = SumoPushGame(Random(seed), TsClock().read)
                        g2.init(tsContext(humans, bots))
                        assertTrue("restore failed seed $seed", g2.restore(snap))
                        assertEquals(g.info.value.wins, g2.info.value.wins)
                        assertEquals(snap, g2.snapshot())
                    }
                }
            }
            assertTrue(g.info.value.wins.max() <= SumoRules.winsNeeded(g.info.value.rounds))
        }
    }
}
