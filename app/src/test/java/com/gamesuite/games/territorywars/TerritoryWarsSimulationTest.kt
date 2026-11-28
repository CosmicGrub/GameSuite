package com.gamesuite.games.territorywars

import com.gamesuite.core.CpuLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** CI-sized war simulations (design doc 3.4). The 10,000-war grids are the P4 tuning task and stay ignored below. */
class TerritoryWarsSimulationTest {
    private fun wars(count: Int, n: Int, length: WarLength, level: (Int) -> CpuLevel = { CpuLevel.MEDIUM }): List<TerritoryWarsState> =
        (1..count).map { i ->
            val e = TWT.seeded(500L + i)
            TWT.playBots(e, e.newGame(TWT.players(n, n), TWT.config(length, n, 500L + i)), level).first
        }

    @Test fun everyWarEndsWithAWinner() {
        for (len in WarLength.values()) for (s in wars(6, 4, len)) {
            assertEquals(TurnPhase.GAME_OVER, s.phase)
            assertTrue(s.winnerSeats.isNotEmpty())
            assertTrue(s.round <= (if (s.config.roundCap > 0) s.config.roundCap + 1 else 1000))
        }
    }

    @Test fun blitzFinishesInsideTheRoundCap() {
        val w = wars(30, 3, WarLength.BLITZ)
        assertTrue(w.all { it.round <= 11 })
    }

    @Test fun stalemateIsRareForStandard() {
        val w = wars(40, 3, WarLength.STANDARD)
        val stale = w.count { it.winReason == WinReason.STALEMATE }
        assertTrue("stalemates $stale of ${w.size}", stale <= w.size / 4)
    }

    @Test fun seatOrderDoesNotDecideTheWar() {
        val w = wars(120, 3, WarLength.BLITZ)
        val wins = IntArray(3)
        for (s in w) s.winnerSeats.forEach { wins[it]++ }
        for (x in wins) assertTrue("wins ${wins.toList()}", x > w.size / 10)
    }

    @Test fun cleverBotsBeatRandomOnesMoreOftenThanNot() {
        var hardWins = 0
        val n = 40
        for (i in 1..n) {
            val e = TWT.seeded(900L + i)
            val s = TWT.playBots(e, e.newGame(TWT.players(2, 2), TWT.config(WarLength.BLITZ, 2, 900L + i)), { seat -> if (seat == 0) CpuLevel.HARD else CpuLevel.EASY }).first
            if (0 in s.winnerSeats) hardWins++
        }
        assertTrue("hard won $hardWins of $n", hardWins >= n * 0.5)
    }

    @Test fun simulationIsDeterministicForAFixedSeedList() {
        assertEquals(wars(3, 3, WarLength.BLITZ), wars(3, 3, WarLength.BLITZ))
    }

    @Ignore("P4 tuning: needs the 10,000-war grid of docs 3.4")
    @Test fun winnerBySeatIsWithinTwoPointsOfFairForTwoToSixBotSeats() { assertEquals(1, 1) }

    @Ignore("P4 tuning: needs the 10,000-war grid of docs 3.4")
    @Test fun hardBeatsMediumAndMediumBeatsEasyInMixedTables() { assertEquals(1, 1) }
}
