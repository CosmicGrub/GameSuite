package com.gamesuite.games.lighttrail

import com.gamesuite.core.GameResult
import com.gamesuite.games.partypack.TsClock
import com.gamesuite.games.partypack.tsContext
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Match flow, scoring, resume, CPU strength ordering and fuzz. Everything runs on a hand-wound clock: no sleeping. */
class LightTrailGameTest {
    private fun game(humans: Int, bots: Int, seed: Int = 1, rounds: Int = 3, arena: LtArena = LtArena.SMALL, clock: TsClock = TsClock()): LightTrailGame {
        val g = LightTrailGame(Random(seed), clock.read)
        g.config = LightTrailConfig(humans + bots, arena, rounds)
        g.init(tsContext(humans, bots))
        g.startMatch()
        return g
    }

    private fun LightTrailGame.toRunning() { advance(LightTrailRules.COUNTDOWN_BEATS * LightTrailRules.COUNTDOWN_BEAT_MS) }
    private fun LightTrailGame.steps(n: Int) = repeat(n) { advance(stepMillis()) }
    private fun LightTrailGame.toNextRound() { advance(LightTrailRules.ROUND_BREAK_MS); toRunning() }

    /** Seat 1 spins into its own trail (dies on step 4); seat 0 rides on, so seat 0 takes the round. */
    private fun LightTrailGame.seatOneSuicides() { repeat(4) { turn(1, LtTurn.RIGHT); advance(stepMillis()) } }

    @Test fun theCountdownRunsThreeBeatsThenGo() {
        val g = game(2, 0)
        assertEquals(LtPhase.COUNTDOWN, g.info.value.phase)
        assertEquals(3, g.info.value.countdown)
        g.advance(800)
        assertEquals(2, g.info.value.countdown)
        g.advance(700)
        assertEquals(1, g.info.value.countdown)
        assertEquals(LtPhase.COUNTDOWN, g.info.value.phase)
        g.advance(700)
        assertEquals(LtPhase.RUNNING, g.info.value.phase)
        assertTrue(g.eventsSince(0).any { it.kind == LtLog.GO })
    }

    @Test fun nothingMovesWhileCountingDownOrPaused() {
        val g = game(2, 0)
        val before = g.board.value!!.riders.map { it.y }
        g.advance(1_000)
        assertEquals(before, g.board.value!!.riders.map { it.y })
        g.toRunning()
        g.pause()
        assertTrue(g.info.value.paused)
        g.advance(5_000); g.tick()
        assertEquals(before, g.board.value!!.riders.map { it.y })
        assertFalse(g.turn(0, LtTurn.LEFT))
        g.resumePlay()
        g.steps(2)
        assertEquals(before[0] - 2, g.board.value!!.riders[0].y)
    }

    @Test fun tickConvertsTheClockIntoStepsAndClampsAHugeGap() {
        val clock = TsClock()
        val g = game(2, 0, clock = clock)
        clock.advance(10_000); g.tick() // a long gap (the app was backgrounded) counts as at most 250 ms
        assertEquals(LtPhase.COUNTDOWN, g.info.value.phase)
        repeat(9) { clock.advance(250); g.tick() }
        assertEquals(LtPhase.RUNNING, g.info.value.phase)
    }

    @Test fun onlyLocalSeatsCanSteer() {
        val g = game(1, 1)
        g.toRunning()
        assertTrue(g.turn(0, LtTurn.RIGHT))
        assertFalse(g.turn(1, LtTurn.RIGHT)) // a computer seat
        assertFalse(g.turn(7, LtTurn.RIGHT))
    }

    @Test fun lastRiderStandingWinsTheRoundAndTheMatchIsBestOfThree() {
        val g = game(2, 0)
        g.toRunning(); g.seatOneSuicides()
        assertEquals(LtPhase.ROUND_OVER, g.info.value.phase)
        assertEquals(0, g.info.value.roundWinner)
        assertEquals(listOf(1, 0), g.info.value.wins)
        assertEquals(listOf(true, false), g.info.value.alive)
        g.toNextRound()
        assertEquals(2, g.info.value.round)
        g.seatOneSuicides()
        assertEquals(LtPhase.MATCH_OVER, g.info.value.phase)
        assertEquals(0, g.info.value.matchWinner)
        assertEquals(1, g.sessionWins.value["p1"])
        assertTrue(g.eventsSince(0).any { it.kind == LtLog.MATCH_WIN && it.seat == 0 })
    }

    @Test fun leavingReportsMatchesWonAndOnlyAStrictLeaderIsTheWinner() {
        val g = game(2, 0)
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.toRunning(); g.seatOneSuicides(); g.toNextRound(); g.seatOneSuicides()
        g.leaveSession()
        val r = result!!
        assertEquals(listOf(true, false), r.scores.map { it.isWinner })
        assertEquals(listOf(1, 0), r.scores.map { it.score })
        assertTrue(g.matchOver.value)
        g.leaveSession() // a second leave does nothing
    }

    @Test fun playAgainStartsAFreshMatchButKeepsTheSessionTally() {
        val g = game(2, 0)
        g.toRunning(); g.seatOneSuicides(); g.toNextRound(); g.seatOneSuicides()
        g.playAgain()
        assertEquals(LtPhase.COUNTDOWN, g.info.value.phase)
        assertEquals(listOf(0, 0), g.info.value.wins)
        assertEquals(1, g.sessionWins.value["p1"])
        assertEquals(1, g.info.value.round)
    }

    @Test fun drawnRoundsReplayAndFiveDrawsEndTheMatchAsADraw() {
        val g = game(2, 0)
        repeat(5) {
            g.toRunning()
            g.steps(18) // both ride straight into their edges on step 18
            if (g.info.value.phase == LtPhase.ROUND_OVER) assertEquals(-1, g.info.value.roundWinner)
            if (g.info.value.phase == LtPhase.ROUND_OVER) g.advance(LightTrailRules.ROUND_BREAK_MS)
        }
        assertEquals(LtPhase.MATCH_OVER, g.info.value.phase)
        assertEquals(-1, g.info.value.matchWinner)
        assertEquals(1, g.sessionDraws.value)
        assertTrue(g.eventsSince(0).any { it.kind == LtLog.MATCH_DRAW })
    }

    @Test fun aRoundEachThenTheThirdRoundDecidesTheMatch() {
        val g = game(2, 0)
        // Round 1: seat 1 dies early (seat 0 wins). Round 2: seat 0 dies early (seat 1 wins). Round 3 decides.
        g.toRunning(); g.seatOneSuicides(); g.toNextRound()
        repeat(4) { g.turn(0, LtTurn.RIGHT); g.advance(g.stepMillis()) }
        assertEquals(listOf(1, 1), g.info.value.wins)
        assertEquals(LtPhase.ROUND_OVER, g.info.value.phase)
        g.toNextRound(); g.seatOneSuicides()
        assertEquals(0, g.info.value.matchWinner)
        assertEquals(0, g.decideMatch())
    }

    @Test fun snapshotRestoresAMatchBetweenRoundsAndRestartsTheCurrentRound() {
        val g = game(1, 1)
        assertNotNull(g.snapshot())
        g.toRunning()
        g.steps(3)
        val snap = g.snapshot()!!
        val g2 = LightTrailGame(Random(5), TsClock().read)
        g2.init(tsContext(1, 1))
        assertTrue(g2.restore(snap))
        assertEquals(LtPhase.COUNTDOWN, g2.info.value.phase)
        assertEquals(1, g2.info.value.round)
        assertEquals(g.board.value!!.riders.map { it.alive }, g2.board.value!!.riders.map { it.alive })
        assertEquals(snap, g2.snapshot())
    }

    @Test fun snapshotKeepsScoresSettingsAndLevel() {
        val g = game(2, 0, rounds = 5)
        g.config = g.config.copy(powerUps = true, relaxed = true, oneSwitch = true, arena = LtArena.LARGE)
        g.startMatch()
        g.toRunning(); g.seatOneSuicides()
        val snap = g.snapshot()!!
        val g2 = LightTrailGame(Random(2), TsClock().read)
        g2.init(tsContext(2, 0))
        assertTrue(g2.restore(snap))
        assertEquals(listOf(1, 0), g2.info.value.wins)
        assertEquals(5, g2.info.value.rounds)
        assertEquals(LtArena.LARGE, g2.config.arena)
        assertTrue(g2.config.powerUps && g2.config.relaxed && g2.config.oneSwitch)
        assertEquals(2, g2.info.value.round)
    }

    @Test fun restoreRejectsStaleCorruptAndTamperedSnapshots() {
        val g = game(2, 0)
        g.toRunning(); g.seatOneSuicides()
        val good = g.snapshot()!!
        fun fresh(humans: Int = 2, bots: Int = 0) = LightTrailGame(Random(1), TsClock().read).also { it.init(tsContext(humans, bots)) }
        assertFalse(fresh().restore("not json"))
        assertFalse(fresh().restore("{}"))
        assertFalse(fresh(1, 1).restore(good)) // a different roster
        assertFalse(fresh(3, 0).restore(good))
        assertFalse(fresh().restore(good.replace("\"wins\":[1,0]", "\"wins\":[2,0]"))) // already decided
        assertFalse(fresh().restore(good.replace("\"wins\":[1,0]", "\"wins\":[9,-1]")))
        assertFalse(fresh().restore(good.replace("\"rounds\":3", "\"rounds\":4")))
        assertFalse(fresh().restore(good.replace("\"MEDIUM\"", "\"HUGE\"")))
        assertTrue(fresh().restore(good))
    }

    @Test fun noSnapshotBeforeStartOrAfterTheMatchIsOver() {
        val g = LightTrailGame(Random(1), TsClock().read)
        g.init(tsContext(2, 0))
        assertNull(g.snapshot())
        g.startMatch()
        g.toRunning(); g.seatOneSuicides(); g.toNextRound(); g.seatOneSuicides()
        assertNull(g.snapshot())
    }

    @Test fun theSameSeedAndInputsReplayTheSameMatch() {
        fun play(): List<Any> {
            val g = game(1, 2, seed = 11)
            g.difficulty = CpuDifficulty.MEDIUM
            val rnd = Random(3)
            var n = 0
            while (g.info.value.phase != LtPhase.MATCH_OVER && n++ < 4000) {
                if (rnd.nextInt(6) == 0) g.turn(0, LtTurn.entries[rnd.nextInt(2)])
                g.advance(70)
            }
            return listOf(g.info.value.wins, g.info.value.matchWinner, g.board.value!!.cells.toList())
        }
        assertEquals(play(), play())
    }

    // ---- the computer ----

    private fun duel(a: CpuDifficulty, b: CpuDifficulty, matches: Int, arena: LtArena = LtArena.SMALL): Triple<Int, Int, Int> {
        var aw = 0; var bw = 0; var draws = 0
        for (m in 0 until matches) {
            val swap = m % 2 == 1
            val g = game(0, 2, seed = 1000 + m, arena = arena)
            g.seatLevelOverride[0] = if (swap) b else a
            g.seatLevelOverride[1] = if (swap) a else b
            var n = 0
            while (g.info.value.phase != LtPhase.MATCH_OVER && n++ < 60_000) g.advance(g.stepMillis())
            assertEquals(LtPhase.MATCH_OVER, g.info.value.phase)
            val w = g.info.value.matchWinner
            when {
                w < 0 -> draws++
                (w == 0) != swap -> aw++
                else -> bw++
            }
            if (g.info.value.phase == LtPhase.ROUND_OVER) g.advance(LightTrailRules.ROUND_BREAK_MS)
        }
        return Triple(aw, bw, draws)
    }

    @Test fun cpuLevelsAreClearlyOrderedButNoneIsInvincible() {
        val n = 60
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

    @Test fun cpuSeatsFillEmptyZonesAndAFullCpuTableTerminates() {
        for (level in CpuDifficulty.entries) {
            val g = game(1, 3, seed = 4, arena = LtArena.MEDIUM)
            g.difficulty = level
            var n = 0
            while (g.info.value.phase != LtPhase.MATCH_OVER && n++ < 30_000) {
                g.advance(g.stepMillis())
                if (g.info.value.phase == LtPhase.RUNNING && n % 40 == 0) g.turn(0, LtTurn.RIGHT)
            }
            assertEquals("level $level", LtPhase.MATCH_OVER, g.info.value.phase)
        }
    }

    @Test fun fuzzRandomInputAndClockStepsAlwaysTerminateAndSnapshotsRoundTrip() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val humans = 1 + rnd.nextInt(3); val bots = if (humans == 1) 1 + rnd.nextInt(3) else rnd.nextInt(5 - humans)
            val g = game(humans, bots, seed = seed, rounds = if (rnd.nextBoolean()) 3 else 5, arena = LtArena.entries[rnd.nextInt(3)])
            g.config = g.config.copy(powerUps = rnd.nextBoolean(), relaxed = rnd.nextBoolean(), oneSwitch = rnd.nextBoolean())
            g.startMatch()
            g.difficulty = CpuDifficulty.entries[rnd.nextInt(3)]
            var n = 0
            while (g.info.value.phase != LtPhase.MATCH_OVER) {
                assertTrue("runaway seed $seed", n++ < 100_000)
                val r = rnd.nextInt(10)
                if (r < 5) g.turn(rnd.nextInt(humans + bots), LtTurn.entries[rnd.nextInt(3)])
                if (r == 9) g.pause()
                if (r == 8) g.resumePlay()
                g.advance(1L + rnd.nextInt(300))
                if (rnd.nextInt(50) == 0) {
                    val snap = g.snapshot()
                    if (snap != null) {
                        val g2 = LightTrailGame(Random(seed), TsClock().read)
                        g2.init(tsContext(humans, bots))
                        assertTrue("restore failed seed $seed", g2.restore(snap))
                        assertEquals(g.info.value.wins, g2.info.value.wins)
                        assertEquals(snap, g2.snapshot())
                    }
                }
                g.resumePlay()
            }
            assertTrue(g.info.value.wins.max() <= LightTrailRules.winsNeeded(g.info.value.rounds))
        }
    }
}
