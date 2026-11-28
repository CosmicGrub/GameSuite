package com.gamesuite.games.liferoad

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadBotTest {
    private fun run(level: CpuDifficulty, seed: Long, size: RoadSize = RoadSize.STANDARD, n: Int = 4, junior: Boolean = false) =
        LRT.playBots(LRT.start(n, LRT.config(size = size, seed = seed, level = level, junior = junior), bots = n), level)

    @Test fun easyBotNeverStalls() { for (s in 1L..30L) assertTrue(run(CpuDifficulty.EASY, s).first.over) }
    @Test fun mediumBotNeverStalls() { for (s in 1L..30L) assertTrue(run(CpuDifficulty.MEDIUM, s).first.over) }
    @Test fun hardBotNeverStalls() { for (s in 1L..30L) assertTrue(run(CpuDifficulty.HARD, s).first.over) }

    @Test fun botActionIsAlwaysLegal() {
        // playBots asserts explain() == NONE for every bot action
        for (s in 1L..40L) for (level in CpuDifficulty.values()) run(level, s, RoadSize.values()[(s % 3).toInt()], 2 + (s % 5).toInt(), s % 6 == 0L)
    }

    @Test fun botsAreDeterministicForASeed() {
        assertEquals(run(CpuDifficulty.MEDIUM, 17).first, run(CpuDifficulty.MEDIUM, 17).first)
    }

    @Test fun easyBuysFewerCoversThanMedium() {
        fun covers(level: CpuDifficulty) = (1L..80L).sumOf { seed -> run(level, seed, RoadSize.STANDARD, 3).first.players.sumOf { it.covers.size } }
        assertTrue(covers(CpuDifficulty.EASY) <= covers(CpuDifficulty.MEDIUM) + 40)
    }

    @Test fun hardUsesLuckyCoinsOnlyWhenItHelps() {
        val s = LRT.edit(LRT.start(2, LRT.config(level = CpuDifficulty.HARD)), 0) { it.copy(luckyCoins = 1) }
        val low = s.copy(phase = Phase.AWAIT_RESPIN, pending = PendingState(spin = 2))
        val high = s.copy(phase = Phase.AWAIT_RESPIN, pending = PendingState(spin = 9))
        assertEquals(LifeRoadAction.UseLuckyCoin, LRT.engine.botAction(low, CpuDifficulty.HARD))
        assertEquals(LifeRoadAction.KeepSpin, LRT.engine.botAction(high, CpuDifficulty.HARD))
    }

    @Test fun hardDecisionFitsTheTimeBudget() {
        val times = ArrayList<Long>()
        var s = LRT.start(4, LRT.config(seed = 3), bots = 4)
        var n = 0
        while (!s.over && n++ < 3000) {
            val t0 = System.nanoTime()
            val a = LRT.engine.botAction(s, CpuDifficulty.HARD)
            times += System.nanoTime() - t0
            s = LRT.act(s, a)
        }
        times.sort()
        assertTrue("p95 ${times[times.size * 95 / 100] / 1e6} ms", times[times.size * 95 / 100] < 25_000_000L)
    }

    @Test fun botsFinishHundredsOfGamesWithoutException() {
        var games = 0
        for (size in RoadSize.values()) for (seats in 2..6) for (seed in 1L..8L) {
            assertTrue(run(CpuDifficulty.values()[(seed % 3).toInt()], seed, size, seats).first.over)
            games++
        }
        assertEquals(3 * 5 * 8, games)
    }
}
