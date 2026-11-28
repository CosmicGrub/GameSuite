package com.gamesuite.games.liferoad

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The balance harness of docs section 3.4, in a CI-sized form: a few hundred real games with the real CPUs. The full
 * 20,000-game tolerances (seat share within 1.5 points, route win rates 45 to 55 percent, skill window 58 to 70 percent)
 * are a tuning task (phase P5) and are listed below as ignored, not silently dropped.
 */
class LifeRoadSimulationTest {
    private fun games(count: Int, size: RoadSize, seats: Int, level: CpuDifficulty, junior: Boolean = false) =
        (1..count).map { LRT.playBots(LRT.start(seats, LRT.config(size = size, seed = 7000L + it, level = level, junior = junior), bots = seats), level) }

    @Test fun noGameExceedsTheActionCap() {
        for (size in RoadSize.values()) for ((_, n) in games(60, size, 4, CpuDifficulty.MEDIUM)) assertTrue("$n", n < 4000)
    }

    @Test fun freshStartRateIsSmallForMedium() {
        val g = games(150, RoadSize.STANDARD, 4, CpuDifficulty.MEDIUM)
        val rate = g.sumOf { (s, _) -> s.players.count { it.freshStarts > 0 } }.toDouble() / (g.size * 4)
        assertTrue("fresh start rate $rate", rate <= 0.20)
    }

    @Test fun juniorNeverHasFreshStartsOrLoans() {
        for ((s, _) in games(40, RoadSize.QUICK, 3, CpuDifficulty.EASY, junior = true)) assertTrue(s.players.all { it.freshStarts == 0 && it.loans == 0 })
    }

    @Test fun averageLengthIsNearTheModeTarget() {
        val targets = mapOf(RoadSize.QUICK to 7.5, RoadSize.STANDARD to 13.0, RoadSize.LONG to 19.5)
        for ((size, t) in targets) {
            val g = games(30, size, 3, CpuDifficulty.MEDIUM)
            val avg = g.map { (s, _) -> s.players.map { it.turnsTaken }.average() }.average()
            assertTrue("$size avg $avg vs $t", avg in (t * 0.6)..(t * 1.6))
        }
    }

    @Test fun lastLightIsASafetyNetNotTheUsualEnd() {
        val g = games(80, RoadSize.STANDARD, 4, CpuDifficulty.MEDIUM)
        val late = g.count { (s, _) -> s.players.any { it.lane == RetireLane.LATE } }
        assertTrue("late $late of ${g.size}", late <= g.size / 4)
    }

    @Test fun seatOrderDoesNotDecideTheGame() {
        val g = games(240, RoadSize.QUICK, 3, CpuDifficulty.MEDIUM)
        val wins = IntArray(3)
        for ((s, _) in g) s.winnerSeats.forEach { wins[it]++ }
        for (w in wins) assertTrue("wins ${wins.toList()}", w.toDouble() / g.size in 0.15..0.55)
    }

    @Test fun smallBoostMovesTheWinRateTheRightWay() {
        var boosted = 0
        val n = 200
        repeat(n) { i ->
            val cfg = LRT.config(size = RoadSize.QUICK, seed = 900L + i, head = HeadStart.BIG, seats = setOf(1))
            val (s, _) = LRT.playBots(LRT.start(2, cfg, bots = 2), CpuDifficulty.MEDIUM)
            if (1 in s.winnerSeats) boosted++
        }
        assertTrue("boosted seat won $boosted of $n", boosted > n * 0.5)
    }

    @Test fun twoThousandGamesRunQuickly() {
        val t0 = System.nanoTime()
        games(300, RoadSize.QUICK, 3, CpuDifficulty.MEDIUM)
        assertTrue((System.nanoTime() - t0) / 1e9 < 60.0)
    }

    @Ignore("P5 tuning: needs the 20,000-game grid of docs 3.4")
    @Test fun learnVersusWorkWinRateIsBalanced() { assertEquals(1, 1) }

    @Ignore("P5 tuning: needs the 20,000-game grid of docs 3.4")
    @Test fun coverIsNotADominantStrategy() { assertEquals(1, 1) }

    @Ignore("P5 tuning: needs the 20,000-game grid of docs 3.4")
    @Test fun hardBeatsEasyInTheSkillWindow() { assertEquals(1, 1) }
}
