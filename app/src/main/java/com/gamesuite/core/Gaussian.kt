package com.gamesuite.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Shared noise helpers for bots and simulations. Each variant preserves the exact arithmetic (and
 * the exact number/order of [Random] draws) of the per-game copy it replaced, so seeded games and
 * tests are unchanged.
 */
object Gaussian {
    /** Sum of four uniforms, scaled to unit variance. Cheap, no trig/log (billiards, golf bots). */
    fun sum4(random: Random): Double {
        var s = 0.0
        repeat(4) { s += random.nextDouble() - 0.5 }
        return s * sqrt(3.0)
    }

    /** Sum of three uniforms, (a+b+c-1.5)*2 (toy soccer CPU, toy tennis). */
    fun sum3(random: Random): Double =
        (random.nextDouble() + random.nextDouble() + random.nextDouble() - 1.5) * 2.0

    /** Box-Muller pair, each component clamped to +-[clamp] (darts). */
    fun boxMuller(rng: Random, clamp: Double = 3.2): Pair<Double, Double> {
        val u1 = 1.0 - rng.nextDouble()
        val u2 = rng.nextDouble()
        val m = sqrt(-2.0 * ln(u1))
        return (m * cos(2 * PI * u2)).coerceIn(-clamp, clamp) to (m * sin(2 * PI * u2)).coerceIn(-clamp, clamp)
    }
}
