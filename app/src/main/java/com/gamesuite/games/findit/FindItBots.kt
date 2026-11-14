package com.gamesuite.games.findit

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The helper (computer seat) model, pure and seeded. A helper never reads the clock and never touches engine state:
 * the engine asks for a [RoundPlan] when a round starts and then taps through the same path as a person.
 * Numbers are the table in docs/design/kids-games/find-it.md (SCRIPT, "Helper behaviour per level").
 */
object FindItBots {
    private const val TAG_BOT = 0x424FL
    private const val TAG_HUNT = 0x4855L
    const val MIN_REACTION_MS = 1_200L

    /** Mean and standard deviation of the round reaction, the wrong-tap chance and the hunt find interval of one level. */
    data class Level(val meanMs: Double, val sdMs: Double, val wrongChance: Double, val huntIntervalMs: Long)

    val EASY = Level(7_000.0, 2_000.0, 0.15, 12_000L)
    val MEDIUM = Level(4_000.0, 1_200.0, 0.07, 8_000L)
    val HARD = Level(2_200.0, 700.0, 0.02, 5_000L)

    fun level(d: CpuDifficulty): Level = when (d) {
        CpuDifficulty.EASY -> EASY
        CpuDifficulty.MEDIUM -> MEDIUM
        CpuDifficulty.HARD -> HARD
    }

    /** Relaxed play: helpers play one level slower (Easy stays Easy but is 30 percent slower). */
    fun effective(d: CpuDifficulty, relaxed: Boolean): Pair<Level, Double> {
        if (!relaxed) return level(d) to 1.0
        return when (d) {
            CpuDifficulty.HARD -> MEDIUM to 1.0
            CpuDifficulty.MEDIUM -> EASY to 1.0
            CpuDifficulty.EASY -> EASY to 1.3
        }
    }

    fun sizeMultiplier(size: FindItCardSize): Double = when (size) {
        FindItCardSize.TINY -> 0.7
        FindItCardSize.LITTLE -> 1.0
        FindItCardSize.STANDARD -> 1.3
    }

    /** What one helper will do in one round. [wrongIndex] is the index into the seat's card of the mistaken picture. */
    data class RoundPlan(val tapAtMs: Long, val wrong: Boolean, val wrongIndex: Int, val retryAtMs: Long)

    private fun normal(rng: FindItRng): Double {
        val u1 = 1.0 - rng.nextDouble()
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** The reaction time of a helper for one round, drawn from [rng] (always exactly two draws). */
    fun reactionMs(level: Level, extra: Double, size: FindItCardSize, timeScale: Double, rng: FindItRng): Long {
        val m = sizeMultiplier(size) * extra
        val mean = level.meanMs * m
        val sd = level.sdMs * m
        val raw = (mean + sd * normal(rng)).coerceIn(0.5 * mean, 2.5 * mean)
        return (raw * timeScale).toLong().coerceAtLeast(MIN_REACTION_MS)
    }

    /** The plan for ([round], [seat]); always the same for the same inputs. [cardSymbols] is the seat's picture count. */
    fun plan(
        seed: Long, round: Int, seat: Int, difficulty: CpuDifficulty, size: FindItCardSize, relaxed: Boolean,
        timeScale: Double, cardSymbols: Int
    ): RoundPlan {
        val rng = FindItRng(findItDerive(seed, TAG_BOT, round.toLong(), seat.toLong()))
        val (level, extra) = effective(difficulty, relaxed)
        val at = reactionMs(level, extra, size, timeScale, rng)
        val wrong = rng.nextDouble() < level.wrongChance
        val wrongPick = rng.nextInt(cardSymbols.coerceAtLeast(1))
        val retry = FindItRules.LOCKOUT_MS + rng.nextInt(1_000)
        return RoundPlan(at, wrong, wrongPick, at + retry)
    }

    /** The first find of a hunt helper is due after one interval; later ones likewise. [count] keys the stream. */
    fun huntDelayMs(seed: Long, sceneIndex: Int, tries: Int, count: Int, difficulty: CpuDifficulty, relaxed: Boolean, timeScale: Double): Long {
        val rng = FindItRng(findItDerive(seed, TAG_HUNT, sceneIndex.toLong(), tries.toLong(), count.toLong()))
        val (level, extra) = effective(difficulty, relaxed)
        val jitter = 0.75 + 0.5 * rng.nextDouble()
        val pick = rng.nextInt(1_000_000)
        return ((level.huntIntervalMs * extra * jitter * timeScale).toLong().coerceAtLeast(500L)) * 1_000_000L + pick
    }

    /** Splits [huntDelayMs] into the delay in ms and the pick number (a draw used to choose the target). */
    fun delayOf(packed: Long): Long = packed / 1_000_000L
    fun pickOf(packed: Long): Int = (packed % 1_000_000L).toInt()
}
