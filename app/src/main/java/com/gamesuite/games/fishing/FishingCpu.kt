package com.gamesuite.games.fishing

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** How a computer angler thinks. Ticks are 60 per second. */
data class FishingProfile(
    /** Pause before it casts. */
    val thinkTicks: Int,
    /** Error of its cast power (0..1 of the meter). */
    val powerError: Double,
    /** 0 = random spot, 1 = always the best spot. */
    val spotSense: Double,
    /** Reaction time to a bite (ticks). */
    val biteReaction: Int,
    val biteJitter: Int,
    /** Chance to jerk on a nibble (and lose the fish). */
    val spookChance: Double,
    /** Reaction time to a rush or its warning (ticks). */
    val rushReaction: Int,
    /** Where it stops reeling and where it starts again (tension), plus noise. */
    val releaseAt: Double,
    val resumeAt: Double,
    val tensionNoise: Double,
    /** How many ticks old the tension it sees is (slow eyes overshoot). */
    val perceptionLag: Int = 0,
    /** Chance per cast of a lapse of attention that makes it slow to react to the fish. */
    val lapse: Double = 0.0
)

object FishingCpu {
    fun profile(level: CpuDifficulty): FishingProfile = when (level) {
        CpuDifficulty.EASY -> FishingProfile(100, 0.22, 0.0, 34, 14, 0.30, 44, 0.74, 0.30, 0.12, 7, 0.12)
        CpuDifficulty.MEDIUM -> FishingProfile(70, 0.10, 0.5, 24, 8, 0.10, 28, 0.70, 0.40, 0.06, 7, 0.08)
        CpuDifficulty.HARD -> FishingProfile(45, 0.04, 1.0, 12, 4, 0.0, 8, 0.66, 0.42, 0.04, 4, 0.06)
    }

    /** The best spot for a cast on [lake]: (column, band). */
    fun bestSpot(lake: String): Pair<Int, Int> {
        var best = 2 to 1; var bv = -1.0
        for (b in 0 until FishingRules.BANDS) for (c in 0 until FishingRules.COLUMNS) {
            val v = FishingLakes.expectedGrams(lake, b, c)
            if (v > bv) { bv = v; best = c to b }
        }
        return best
    }
}

/** One computer angler's brain. Call [hold] every tick the angler is on turn; it answers with the button state. */
class FishingBot(private val level: () -> CpuDifficulty, private val rng: Random, private val reactionScale: () -> Double = { 1.0 }) {
    private var turnKey = -1
    private var decided = false
    private var targetPower = 0.5
    private var thinkLeft = 0
    private var biteDelay = 0
    private var spookLeft = -1
    private var rushSeenAt = -1
    private var releasing = false
    private var releaseTicks = 0
    private var lastNib = 0
    private val seen = ArrayDeque<Double>()
    private var distracted = 0

    private fun scaled(t: Int) = (t * reactionScale()).toInt().coerceAtLeast(1)

    fun hold(e: FishingEngine, turnId: Int): Boolean {
        val p = FishingCpu.profile(level())
        if (turnId != turnKey) { turnKey = turnId; decided = false; spookLeft = -1; rushSeenAt = -1; releasing = false; lastNib = e.nibbleSerial; seen.clear(); distracted = if (rng.nextDouble() < p.lapse) 45 else 0 }
        when (e.phase) {
            FishPhase.AIM -> {
                if (!decided) {
                    decided = true
                    val best = FishingCpu.bestSpot(e.lake)
                    val smart = rng.nextDouble() < p.spotSense
                    val col = if (smart) best.first else rng.nextInt(FishingRules.COLUMNS)
                    val band = if (smart) best.second else rng.nextInt(FishingRules.BANDS)
                    e.aimCol = col
                    targetPower = ((band + 0.5) / FishingRules.BANDS + (rng.nextDouble() * 2 - 1) * p.powerError).coerceIn(0.02, 0.98)
                    thinkLeft = scaled(p.thinkTicks) + rng.nextInt(20)
                }
                if (thinkLeft > 0) { thinkLeft--; return false }
                return true
            }
            FishPhase.CHARGING -> {
                // Release on the way up as the meter reaches the target.
                return !(e.meter >= targetPower && e.chargeTicks <= FishingRules.METER_HALF)
            }
            FishPhase.WAITING -> {
                if (e.nibbleSerial != lastNib) {
                    lastNib = e.nibbleSerial
                    if (rng.nextDouble() < p.spookChance) spookLeft = 3
                }
                if (spookLeft > 0) { spookLeft--; return true }
                return false
            }
            FishPhase.BITE -> {
                if (biteDelay == 0 || e.phaseTicks == 1) biteDelay = scaled(p.biteReaction) + rng.nextInt(p.biteJitter + 1)
                return e.phaseTicks >= biteDelay
            }
            FishPhase.REELING -> return reel(e, p)
            else -> return false
        }
    }

    private fun reel(e: FishingEngine, p: FishingProfile): Boolean {
        biteDelay = 0
        val danger = e.rush != null || e.warning
        if (danger) {
            if (rushSeenAt < 0) rushSeenAt = e.reelTicks
            if (e.reelTicks - rushSeenAt >= scaled(p.rushReaction) + distracted) return false
        } else rushSeenAt = -1
        seen.addLast(e.tension)
        while (seen.size > p.perceptionLag + 1) seen.removeFirst()
        val t = seen.first() + (rng.nextDouble() * 2 - 1) * p.tensionNoise
        if (releasing) { if (t <= p.resumeAt) releasing = false } else if (t >= p.releaseAt) releasing = true
        return !releasing
    }

}
