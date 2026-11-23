package com.gamesuite.games.whackamole

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** The original critters of the meadow. Each is told apart by SHAPE and a label, never by colour alone. */
enum class Critter(val points: Int) {
    /** Moxie the mole: the everyday target (a round brown head with a pink nose). */
    MOLE(10),

    /** Goldie the gopher: a rare golden bonus with a star on her cap; she leaves quickly. */
    GOLD(30),

    /** Prickle the urchin: a spiky decoy (a pointed crown). Whack it and you lose points. */
    DECOY(-20)
}

/** One critter popping up: the same list is given to every zone. [startMs] and [lifeMs] are relative to the start of play. */
data class WhackSpawn(val id: Int, val hole: Int, val critter: Critter, val startMs: Int, val lifeMs: Int) {
    val endMs: Int get() = startMs + lifeMs
}

object WhackRules {
    const val MIN_SEATS = 2
    const val MAX_SEATS = 4
    val TIME_CHOICES = listOf(60, 90, 120)
    const val DEFAULT_TIME_S = 90
    val GRID_CHOICES = listOf(3, 4)
    const val DEFAULT_GRID = 3

    const val COUNTDOWN_MS = 3_000L
    const val MOLE_BONUS_SHARE = 0.12
    const val DECOY_SHARE = 0.20

    /** A critter takes this long to pop up and to drop back down (drawing only; a whack counts from the first moment it shows). */
    const val POP_MS = 140

    /** Whacks on one hole closer together than this are one touch (a bounce or a second finger on the same hole). */
    const val DOUBLE_TAP_MS = 150

    /** One-switch scanning: the cursor rests this long on each hole. */
    const val SCAN_STEP_MS = 800

    /** Pace multiplier (intervals and lifetimes) in Relaxed and in one-switch mode. */
    const val RELAXED_PACE = 1.6
    const val SWITCH_PACE = 2.4

    fun comboMultiplier(combo: Int): Int = (1 + combo / 5).coerceAtMost(3)

    fun scanHole(sinceStartMs: Long, holes: Int): Int = ((sinceStartMs.coerceAtLeast(0L) / SCAN_STEP_MS) % holes).toInt()

    /**
     * Builds the whole schedule of a round from [seed]: identical for every zone, so the game is fair. Critters
     * arrive faster and stay shorter as the round goes on. No hole is ever asked to host two critters at once.
     */
    fun generate(seed: Long, grid: Int, durationMs: Int, pace: Double): List<WhackSpawn> {
        val rnd = Random(seed xor 0x5DEECE66DL)
        val holes = grid * grid
        val busyUntil = IntArray(holes)
        val out = ArrayList<WhackSpawn>()
        var t = 700.0
        var id = 0
        var sinceBurst = 0
        while (t < durationMs - 600) {
            val progress = (t / durationMs).coerceIn(0.0, 1.0)
            val baseInterval = if (grid >= 4) 640.0 - 270.0 * progress else 800.0 - 320.0 * progress
            val baseLife = 1_300.0 - 450.0 * progress
            val count = if (++sinceBurst >= 9 && grid >= 4) { sinceBurst = 0; 2 } else 1
            repeat(count) {
                val free = (0 until holes).filter { busyUntil[it] <= t.toInt() }
                if (free.isNotEmpty()) {
                    val hole = free[rnd.nextInt(free.size)]
                    val u = rnd.nextDouble()
                    val critter = when {
                        u < MOLE_BONUS_SHARE -> Critter.GOLD
                        u < MOLE_BONUS_SHARE + DECOY_SHARE -> Critter.DECOY
                        else -> Critter.MOLE
                    }
                    val lifeScale = when (critter) { Critter.GOLD -> 0.75; Critter.DECOY -> 1.1; Critter.MOLE -> 1.0 }
                    val life = (baseLife * lifeScale * pace * (0.9 + rnd.nextDouble() * 0.2)).toInt().coerceAtLeast(500)
                    val start = t.toInt()
                    out += WhackSpawn(id++, hole, critter, start, life)
                    busyUntil[hole] = start + life + 250
                }
            }
            t += baseInterval * pace * (0.8 + rnd.nextDouble() * 0.4)
        }
        return out
    }

    /**
     * How the computer plays. [reactMs]/[reactSd]: time from a critter appearing to the whack (never below [floorMs]);
     * [gapMs]: how soon its hand can strike again; [notice]: chance it spots a given real critter; [mistake]: chance it
     * whacks a given decoy.
     */
    class CpuProfile(val reactMs: Int, val reactSd: Int, val floorMs: Int, val gapMs: Int, val notice: Double, val mistake: Double)

    fun profile(level: CpuDifficulty): CpuProfile = when (level) {
        CpuDifficulty.EASY -> CpuProfile(reactMs = 720, reactSd = 150, floorMs = 300, gapMs = 640, notice = 0.55, mistake = 0.30)
        CpuDifficulty.MEDIUM -> CpuProfile(reactMs = 520, reactSd = 90, floorMs = 260, gapMs = 430, notice = 0.80, mistake = 0.12)
        CpuDifficulty.HARD -> CpuProfile(reactMs = 380, reactSd = 60, floorMs = 230, gapMs = 300, notice = 0.90, mistake = 0.05)
    }

    fun gauss(random: Random, mean: Double, sd: Double): Double {
        val u1 = (1.0 - random.nextDouble()).coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return mean + sd * sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
