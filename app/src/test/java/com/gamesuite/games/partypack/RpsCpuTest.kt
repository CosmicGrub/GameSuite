package com.gamesuite.games.partypack

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RpsCpuTest {
    private val R = RpsMove.ROCK; private val P = RpsMove.PAPER; private val S = RpsMove.SCISSORS

    /** A scripted human: sees only PAST rounds, like anybody, and plays a habit. */
    private fun interface Habit { fun next(round: Int, past: List<RpsRoundRecord>, rnd: Random, moves: List<RpsMove>): RpsMove }

    private val biased = Habit { _, _, rnd, m -> if (rnd.nextDouble() < 0.6) RpsMove.ROCK else m[rnd.nextInt(m.size)] }
    private val cycle = Habit { r, _, _, m -> m[r % m.size] }
    private val pairs = Habit { r, _, _, m -> m[(r / 2) % m.size] }
    private val uniform = Habit { _, _, rnd, m -> m[rnd.nextInt(m.size)] }
    private fun wsls(v: RpsVariant) = Habit { _, past, rnd, m ->
        val last = past.lastOrNull()
        if (last == null) m[rnd.nextInt(m.size)] else {
            val mine = last.picks[0]!!; val theirs = last.picks[1]!!
            when (last.results[0]) {
                1 -> mine // win-stay
                -1 -> RpsRules.beatenBy(v, theirs).first() // lose-shift to what would have beaten them
                else -> m[rnd.nextInt(m.size)]
            }
        }
    }

    /** The computer's fraction of rounds won against [habit] over [rounds] rounds (ties count as not won). */
    private fun cpuWinRate(level: CpuDifficulty, habit: Habit, variant: RpsVariant, rounds: Int, seed: Int): Double {
        val brain = RpsCpuBrain(level, Random(seed), variant)
        val humanRnd = Random(seed + 1000)
        val past = mutableListOf<RpsRoundRecord>()
        var won = 0
        for (r in 0 until rounds) {
            val human = habit.next(r, past, humanRnd, variant.moves)
            val cpu = brain.choose(RpsBrainContext(variant, 1, listOf(0)))
            val o = RpsRules.resolve(variant, listOf(human, cpu))
            if (o.winners == listOf(1)) won++
            val rec = RpsRoundRecord(listOf(human, cpu), List(2) { RpsRules.resultFor(o, it) })
            past += rec
            brain.observe(rec)
        }
        return won.toDouble() / rounds
    }

    private fun average(level: CpuDifficulty, variant: RpsVariant): Double {
        val habits = listOf(biased, cycle, pairs, wsls(variant))
        var total = 0.0
        var n = 0
        for (h in habits) for (seed in 1..6) { total += cpuWinRate(level, h, variant, 400, seed); n++ }
        return total / n
    }

    @Test fun classicLevelsAreClearlyOrderedAndHardIsNotPerfect() {
        val easy = average(CpuDifficulty.EASY, RpsVariant.CLASSIC)
        val med = average(CpuDifficulty.MEDIUM, RpsVariant.CLASSIC)
        val hard = average(CpuDifficulty.HARD, RpsVariant.CLASSIC)
        println("RPS classic win rates: easy=$easy medium=$med hard=$hard")
        assertTrue("easy near chance: $easy", easy in 0.26..0.42)
        assertTrue("medium beats easy: $med vs $easy", med > easy + 0.05)
        assertTrue("hard beats medium: $hard vs $med", hard > med + 0.05)
        assertTrue("hard is not perfect: $hard", hard < 0.9)
    }

    @Test fun lizardSpockLevelsAreOrderedToo() {
        val easy = average(CpuDifficulty.EASY, RpsVariant.LIZARD_SPOCK)
        val med = average(CpuDifficulty.MEDIUM, RpsVariant.LIZARD_SPOCK)
        val hard = average(CpuDifficulty.HARD, RpsVariant.LIZARD_SPOCK)
        println("RPS lizard win rates: easy=$easy medium=$med hard=$hard")
        assertTrue("$easy < $med < $hard", easy + 0.03 < med && med + 0.03 < hard)
        assertTrue(hard < 0.9)
    }

    @Test fun nobodyCanReadAUniformRandomPlayerAtAnyLevel() {
        for (level in CpuDifficulty.entries) {
            val rate = (1..6).map { cpuWinRate(level, uniform, RpsVariant.CLASSIC, 600, it) }.average()
            assertTrue("$level against random play: $rate", rate in 0.28..0.39)
        }
    }

    @Test fun hardLearnsAPlainBiasQuicklyAndEasyDoesNot() {
        val rock = Habit { _, _, _, _ -> RpsMove.ROCK }
        val hard = cpuWinRate(CpuDifficulty.HARD, rock, RpsVariant.CLASSIC, 200, 3)
        val easy = cpuWinRate(CpuDifficulty.EASY, rock, RpsVariant.CLASSIC, 200, 3)
        assertTrue("hard $hard", hard > 0.8)
        assertTrue("easy $easy", easy < 0.6)
    }

    @Test fun easyPicksAreNearUniform() {
        val brain = RpsCpuBrain(CpuDifficulty.EASY, Random(5))
        val counts = HashMap<RpsMove, Int>()
        repeat(3000) {
            val m = brain.choose(RpsBrainContext(RpsVariant.CLASSIC, 1, listOf(0)))
            counts[m] = (counts[m] ?: 0) + 1
            brain.observe(RpsRoundRecord(listOf(R, m), listOf(0, 0)))
        }
        for (m in RpsVariant.CLASSIC.moves) assertTrue("$m ${counts[m]}", (counts[m] ?: 0) in 700..1400)
    }

    @Test fun predictorMemoryIsBoundedAndReadsAPatternAndSameSeedReplaysTheSame() {
        val p = RpsPredictor(RpsVariant.CLASSIC.moves, memory = 60)
        repeat(10_000) { p.observe(RpsVariant.CLASSIC.moves[it % 3], 0) }
        assertEquals(60, p.size)
        // after ...R P S R P S the next is R: the model must lean to it
        val d = p.distribution()
        val last = RpsVariant.CLASSIC.moves[(10_000 - 1) % 3]
        val next = RpsVariant.CLASSIC.moves[(RpsVariant.CLASSIC.moves.indexOf(last) + 1) % 3]
        assertTrue("distribution ${d.toList()}", d[RpsVariant.CLASSIC.moves.indexOf(next)] > 0.7)
        assertEquals(1.0, d.sum(), 1e-9)
        assertEquals(cpuWinRate(CpuDifficulty.HARD, cycle, RpsVariant.CLASSIC, 100, 4), cpuWinRate(CpuDifficulty.HARD, cycle, RpsVariant.CLASSIC, 100, 4), 0.0)
    }

    @Test fun multiplayerBrainCountersTheLikelyMoveOfEveryOpponent() {
        val brain = RpsCpuBrain(CpuDifficulty.HARD, Random(1))
        repeat(60) { brain.observe(RpsRoundRecord(listOf(R, R, null), listOf(0, 0, 0))) } // both opponents always rock
        val picks = (1..200).map { brain.choose(RpsBrainContext(RpsVariant.CLASSIC, 2, listOf(0, 1))) }
        assertTrue(picks.count { it == P } > 150)
    }
}
