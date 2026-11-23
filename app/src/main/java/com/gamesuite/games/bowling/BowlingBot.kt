package com.gamesuite.games.bowling

import com.gamesuite.settings.CpuDifficulty
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The computer bowler. It plans with the real lane physics: a coarse sweep of start position, aim
 * and spin, a finer search around the best, then a robustness check (the shot must still work when
 * it is a hair off). The plan for a given set of standing pins is cached. Levels differ in how
 * steady the roll is ([scatterFor]) and how often Easy just rolls it straight down the middle.
 */
object BowlingBot {
    private val cache = ConcurrentHashMap<Pair<Int, Boolean>, BowlShot>()

    fun scatterFor(level: CpuDifficulty): BowlingScatter = when (level) {
        CpuDifficulty.EASY -> BowlingScatter.CPU_EASY
        CpuDifficulty.MEDIUM -> BowlingScatter.CPU_MEDIUM
        CpuDifficulty.HARD -> BowlingScatter.CPU_HARD
    }

    /** The shot the bot intends (before its hand scatters it). */
    fun intended(standing: Int, bumpers: Boolean, level: CpuDifficulty, rng: Random): BowlShot {
        if (level == CpuDifficulty.EASY && rng.nextInt(100) < 60) {
            return BowlShot(rng.nextDouble(-0.2, 0.2), rng.nextDouble(-0.004, 0.004), 6.5 + rng.nextDouble() , 0.0)
        }
        return plan(standing, bumpers)
    }

    /** The shot the bot throws: the intended one, scattered by its hand. */
    fun choose(standing: Int, bumpers: Boolean, level: CpuDifficulty, rng: Random): BowlShot =
        scatterFor(level).apply(intended(standing, bumpers, level, rng), rng)

    private fun value(shot: BowlShot, standing: Int, bumpers: Boolean): Double {
        val r = BowlingPhysics.simulate(shot, standing, bumpers)
        val down = BowlingLane.pinCount(standing) - BowlingLane.pinCount(r.standing)
        return down + if (r.standing == 0) 4.0 else 0.0
    }

    private fun robust(shot: BowlShot, standing: Int, bumpers: Boolean): Double {
        val ds = listOf(
            shot, shot.copy(x = shot.x - 0.02), shot.copy(x = shot.x + 0.02),
            shot.copy(slope = shot.slope - 0.0015), shot.copy(slope = shot.slope + 0.0015)
        )
        return ds.sumOf { value(it.clamped(), standing, bumpers) } / ds.size
    }

    /** The best shot for [standing] with a perfectly steady hand, found by search. Cached. */
    fun plan(standing: Int, bumpers: Boolean): BowlShot =
        cache.getOrPut(standing to bumpers) { search(standing, bumpers) }

    private fun search(standing: Int, bumpers: Boolean): BowlShot {
        if (standing == 0) return BowlShot(0.0, 0.0, BowlingLane.DEFAULT_SPEED, 0.0)
        val scored = ArrayList<Pair<BowlShot, Double>>()
        val xs = doubleArrayOf(-0.45, -0.30, -0.15, 0.0, 0.15, 0.30, 0.45)
        val spins = doubleArrayOf(-0.8, 0.0, 0.8)
        var k = -7
        while (k <= 7) {
            val slope = k * 0.008
            for (x in xs) for (sp in spins) {
                val s = BowlShot(x, slope, BowlingLane.DEFAULT_SPEED, sp).clamped()
                scored += s to value(s, standing, bumpers)
            }
            k++
        }
        val top = scored.sortedByDescending { it.second }.take(6).map { it.first }
        val fine = ArrayList<Pair<BowlShot, Double>>()
        for (c in top) for (dx in doubleArrayOf(-0.05, 0.0, 0.05)) for (ds in doubleArrayOf(-0.003, 0.0, 0.003)) {
            val s = c.copy(x = c.x + dx, slope = c.slope + ds).clamped()
            fine += s to value(s, standing, bumpers)
        }
        val finalists = (fine + scored).sortedByDescending { it.second }.map { it.first }.distinct().take(6)
        return finalists.maxByOrNull { robust(it, standing, bumpers) } ?: top.first()
    }
}
