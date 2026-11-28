package com.gamesuite.core

import com.gamesuite.games.sudoku.SudokuGame
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-tier daily seeds: every difficulty tier of one day gets its own, stable, shared seed. */
class DailyTierSeedTest {
    @Test fun nullStaysNull() {
        assertNull(DailySeed.forTier(null, 0))
        assertNull(DailySeed.forTier(null, 2))
    }

    @Test fun sameDaySameTierSameSeed() {
        val day = DailySeed.forDay("sudoku", 0, 20_000L)
        assertEquals(DailySeed.forTier(day, 1), DailySeed.forTier(day, 1))
    }

    @Test fun everyTierGetsItsOwnSeed() {
        val day = DailySeed.forDay("sudoku", 0, 20_000L)
        val seeds = (0..2).map { DailySeed.forTier(day, it) }.toSet()
        assertEquals(3, seeds.size)
        assertEquals(false, seeds.contains(day)) // tier 0 is not the raw day seed either
    }

    @Test fun differentDaysGiveDifferentTierSeeds() {
        val a = DailySeed.forTier(DailySeed.forDay("sudoku", 0, 20_000L), 1)
        val b = DailySeed.forTier(DailySeed.forDay("sudoku", 0, 20_001L), 1)
        assertNotEquals(a, b)
    }

    private fun sudoku(tier: CpuDifficulty): SudokuGame {
        var clock = 0L
        val g = SudokuGame(nowMillis = { clock++ })
        g.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "P")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        g.difficulty = tier
        return g
    }

    @Test fun sudokuTiersOfOneDailyDoNotShareASolutionButEachIsReproducible() {
        val seed = DailySeed.forDay("sudoku", 0, 20_500L)
        val solutions = CpuDifficulty.values().map { tier ->
            val a = sudoku(tier).also { it.startMatch(dailySeed = seed) }.state.value!!.solution
            val b = sudoku(tier).also { it.startMatch(dailySeed = seed) }.state.value!!.solution
            assertEquals("tier $tier must reproduce", a, b)
            a
        }
        assertEquals("each tier has its own board", 3, solutions.toSet().size)
    }
}
