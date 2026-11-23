package com.gamesuite.games.fishing

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The computer anglers: three clearly ordered levels, beatable, deterministic for a seed. */
class FishingCpuTest {
    private class Outcome(val grams: IntArray, val casts: IntArray, val caught: IntArray, val winner: Int)

    private fun contest(levels: List<CpuDifficulty>, seed: Int, lake: String = "lake", rounds: Int = 5): Outcome {
        val rng = Random(seed)
        val e = FishingEngine(lake, levels.size, rounds, false, rng)
        val bots = levels.mapIndexed { i, l -> FishingBot({ l }, Random(seed * 31 + i)) }
        var g = 0
        while (e.phase != FishPhase.OVER && g++ < 800000) {
            val active = e.phase != FishPhase.RESULT
            e.step(if (active) bots[e.seat].hold(e, e.round * 10 + e.turnIndex) else false)
        }
        assertEquals(FishPhase.OVER, e.phase)
        return Outcome(e.grams.copyOf(), e.casts.copyOf(), IntArray(levels.size) { e.catches[it].size }, e.ranking().first())
    }

    @Test fun hardOutfishesMediumOutfishesEasy() {
        val lv = listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM, CpuDifficulty.HARD)
        for (lake in FishingLakes.lakeIds) {
            val total = DoubleArray(3); val caught = DoubleArray(3); val wins = IntArray(3)
            val n = 60
            for (seed in 0 until n) {
                val o = contest(lv, seed, lake)
                for (i in 0..2) { total[i] += o.grams[i].toDouble() / n; caught[i] += o.caught[i].toDouble() / n }
                wins[o.winner]++
            }
            assertTrue("$lake avg grams ${total.toList()} caught ${caught.toList()} wins ${wins.toList()}", total[2] > total[1] && total[1] > total[0])
            assertTrue("$lake Hard wins ${wins.toList()}", wins[2] >= n * 0.4 && wins[2] > wins[0])
        }
    }

    @Test fun easyIsBeatableAndNotHelpless() {
        var easyWins = 0; var mediumWins = 0; var easyFish = 0; var easyCasts = 0
        for (seed in 0 until 80) {
            val o = contest(listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM), seed)
            if (o.winner == 0) easyWins++ else mediumWins++
            easyFish += o.caught[0]; easyCasts += o.casts[0]
        }
        assertTrue("easy $easyWins medium $mediumWins", easyWins >= 8 && mediumWins > easyWins)
        assertTrue("easy lands a fair share of its casts: $easyFish of $easyCasts", easyFish >= easyCasts * 0.35 && easyFish < easyCasts)
    }

    @Test fun hardIsStrongButEasyAndMediumDoLoseFish() {
        var fish = 0; var casts = 0
        for (seed in 0 until 40) { val o = contest(listOf(CpuDifficulty.HARD, CpuDifficulty.HARD), seed); fish += o.caught.sum(); casts += o.casts.sum() }
        assertTrue("hard landed $fish of $casts", fish >= casts * 0.9)
        var ef = 0; var ec = 0; var mf = 0; var mc = 0
        for (seed in 0 until 40) {
            val o = contest(listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM), seed)
            ef += o.caught[0]; ec += o.casts[0]; mf += o.caught[1]; mc += o.casts[1]
        }
        assertTrue("easy $ef/$ec medium $mf/$mc hard $fish/$casts", ef.toDouble() / ec < mf.toDouble() / mc && mf < mc && mf.toDouble() / mc < fish.toDouble() / casts + 0.001)
    }

    @Test fun anAnglerOnTurnNeverStallsTheContest() {
        // every level finishes a contest well inside the engine's own time limits
        for (l in CpuDifficulty.entries) {
            val o = contest(listOf(l, l, l, l), 3, rounds = 8)
            assertEquals(8, o.casts.max().coerceAtMost(8))
        }
    }

    @Test fun theSameSeedGivesTheSameContest() {
        val lv = listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)
        assertEquals(contest(lv, 9).grams.toList(), contest(lv, 9).grams.toList())
        assertTrue(contest(lv, 9).grams.toList() != contest(lv, 10).grams.toList())
    }

    @Test fun profilesGetBetterWithTheLevel() {
        val e = FishingCpu.profile(CpuDifficulty.EASY); val m = FishingCpu.profile(CpuDifficulty.MEDIUM); val h = FishingCpu.profile(CpuDifficulty.HARD)
        assertTrue(e.biteReaction > m.biteReaction && m.biteReaction > h.biteReaction)
        assertTrue(e.rushReaction > m.rushReaction && m.rushReaction > h.rushReaction)
        assertTrue(e.powerError > m.powerError && m.powerError > h.powerError)
        assertTrue(e.spotSense < m.spotSense && m.spotSense < h.spotSense)
        assertTrue("every bite reaction is humanly slow but inside the window", e.biteReaction + e.biteJitter < 100 && h.biteReaction >= 8)
    }
}
