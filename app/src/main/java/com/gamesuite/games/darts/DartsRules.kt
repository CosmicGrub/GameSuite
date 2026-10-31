package com.gamesuite.games.darts

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Which part of the board a dart finished in. */
enum class Ring { MISS, SINGLE, DOUBLE, TREBLE, OUTER_BULL, BULL }

/** What a dart scored: a [ring] and the sector [number] (25 for either bull, 0 for a miss). */
data class DartHit(val ring: Ring, val number: Int) {
    val multiplier: Int
        get() = when (ring) {
            Ring.SINGLE, Ring.OUTER_BULL -> 1
            Ring.DOUBLE, Ring.BULL -> 2
            Ring.TREBLE -> 3
            Ring.MISS -> 0
        }
    val base: Int get() = if (ring == Ring.MISS) 0 else number
    val score: Int get() = base * multiplier

    /** A double (including the centre bull) is what a double-out finish needs. */
    val isDouble: Boolean get() = ring == Ring.DOUBLE || ring == Ring.BULL
    val isBull: Boolean get() = ring == Ring.BULL || ring == Ring.OUTER_BULL

    /** Language-neutral scoreboard code: T20, D16, S5, 25, 50 and "-" for a miss. */
    val code: String
        get() = when (ring) {
            Ring.MISS -> "-"
            Ring.SINGLE -> "S$number"
            Ring.DOUBLE -> "D$number"
            Ring.TREBLE -> "T$number"
            Ring.OUTER_BULL -> "25"
            Ring.BULL -> "50"
        }
}

/**
 * A regulation dartboard in millimetres, origin at the bull, x to the right, y DOWN (screen
 * orientation, so the 20 sits at negative y). Radii are the official WDF dimensions.
 */
object DartsBoard {
    /** Sector numbers clockwise from the top. */
    val SECTORS = intArrayOf(20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5)
    const val BULL_R = 6.35
    const val OUTER_BULL_R = 15.9
    const val TREBLE_IN = 99.0
    const val TREBLE_OUT = 107.0
    const val DOUBLE_IN = 162.0
    const val DOUBLE_OUT = 170.0

    /** Anything this far out is on the wall or the floor: still a legal (missing) throw. */
    const val MAX_AIM = 400.0

    fun sectorNumber(x: Double, y: Double): Int {
        var deg = Math.toDegrees(atan2(x, -y))
        if (deg < 0) deg += 360.0
        val idx = (((deg + 9.0) % 360.0) / 18.0).toInt() % 20
        return SECTORS[idx]
    }

    fun resolve(x: Double, y: Double): DartHit {
        val r = sqrt(x * x + y * y)
        return when {
            r > DOUBLE_OUT -> DartHit(Ring.MISS, 0)
            r <= BULL_R -> DartHit(Ring.BULL, 25)
            r <= OUTER_BULL_R -> DartHit(Ring.OUTER_BULL, 25)
            else -> {
                val n = sectorNumber(x, y)
                val ring = when {
                    r > TREBLE_IN && r <= TREBLE_OUT -> Ring.TREBLE
                    r >= DOUBLE_IN -> Ring.DOUBLE
                    else -> Ring.SINGLE
                }
                DartHit(ring, n)
            }
        }
    }

    /** The middle of a ring in a sector, where a player aims for it. */
    fun center(ring: Ring, number: Int): Pair<Double, Double> {
        if (ring == Ring.BULL || ring == Ring.OUTER_BULL || ring == Ring.MISS) return 0.0 to 0.0
        val idx = SECTORS.indexOf(number).coerceAtLeast(0)
        val a = Math.toRadians(18.0 * idx)
        val r = when (ring) {
            Ring.TREBLE -> (TREBLE_IN + TREBLE_OUT) / 2
            Ring.DOUBLE -> (DOUBLE_IN + DOUBLE_OUT) / 2
            else -> 58.0
        }
        return r * sin(a) to -r * cos(a)
    }

    /** The big outer single of a sector, a safer target than the inner one for a wild thrower. */
    fun outerSingle(number: Int): Pair<Double, Double> {
        val idx = SECTORS.indexOf(number).coerceAtLeast(0)
        val a = Math.toRadians(18.0 * idx)
        val r = 134.0
        return r * sin(a) to -r * cos(a)
    }
}

/** The result of one dart in an X01 game. */
data class X01Outcome(
    val remaining: Int, val started: Boolean, val counted: Int, val bust: Boolean, val finished: Boolean
)

object X01 {
    const val MAX_VISIT = 180

    /**
     * Applies [hit] for a player on [remaining] (before the dart) who has [started] (always true without
     * double-in). A bust leaves [X01Outcome.remaining] as given: the caller restores the visit's start.
     */
    fun apply(remaining: Int, started: Boolean, hit: DartHit, doubleOut: Boolean, doubleIn: Boolean): X01Outcome {
        var st = started || !doubleIn
        if (!st) {
            if (hit.isDouble) st = true else return X01Outcome(remaining, false, 0, false, false)
        }
        val s = hit.score
        val nr = remaining - s
        val bust = nr < 0 || (doubleOut && (nr == 1 || (nr == 0 && !hit.isDouble)))
        if (bust) return X01Outcome(remaining, st, 0, true, false)
        return X01Outcome(nr, st, s, false, nr == 0)
    }
}

/** Cricket: close 20, 19, 18, 17, 16, 15 and the bull with three marks each, then out-score your rivals. */
object Cricket {
    val NUMBERS = intArrayOf(20, 19, 18, 17, 16, 15, 25)

    fun indexOf(hit: DartHit): Int = if (hit.ring == Ring.MISS) -1 else NUMBERS.indexOf(hit.number)

    /** Marks a dart is worth: single 1, double 2, treble 3 (the outer bull counts one, the bull two). */
    fun marksOf(hit: DartHit): Int = if (indexOf(hit) < 0) 0 else hit.multiplier

    /** How many of [marks]'s numbers are closed (three marks each). */
    fun closedCount(marks: List<Int>): Int = marks.count { it >= 3 }
    fun allClosed(marks: List<Int>): Boolean = marks.size == NUMBERS.size && marks.all { it >= 3 }

    /**
     * New marks for the thrower and the points the dart scored. Marks beyond the third score the number's
     * value for each extra mark, but only while at least one opponent has that number open.
     */
    fun apply(mine: List<Int>, others: List<List<Int>>, hit: DartHit): Pair<List<Int>, Int> {
        val i = indexOf(hit)
        if (i < 0) return mine to 0
        val m = marksOf(hit)
        val cur = mine[i]
        val closing = minOf(m, 3 - cur).coerceAtLeast(0)
        val extra = m - closing
        val next = mine.toMutableList().also { it[i] = minOf(3, cur + m) }
        val anyOpen = others.any { it[i] < 3 }
        val points = if (anyOpen) extra * NUMBERS[i] else 0
        return next to points
    }

    /** A player wins once all their numbers are closed and their points are at least everyone else's. */
    fun hasWon(marks: List<Int>, points: Int, otherPoints: List<Int>): Boolean =
        allClosed(marks) && otherPoints.all { points >= it }
}

/**
 * Checkout routes: how to finish from a score in the fewest darts. Easy-to-hit targets are preferred
 * (treble 20, 19, 18, 17, singles) and the last dart goes on the favourite doubles first (D20, D16, D8 ...).
 */
object DartsCheckout {
    private val DOUBLE_PREF = intArrayOf(20, 16, 8, 10, 12, 18, 4, 6, 14, 2, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19)

    private val allHits: List<DartHit> = buildList {
        for (n in 20 downTo 1) {
            add(DartHit(Ring.TREBLE, n)); add(DartHit(Ring.SINGLE, n)); add(DartHit(Ring.DOUBLE, n))
        }
        add(DartHit(Ring.OUTER_BULL, 25)); add(DartHit(Ring.BULL, 25))
    }

    private fun ease(h: DartHit): Double = when (h.ring) {
        Ring.SINGLE -> 1.0 + (20 - h.number) * 0.01
        Ring.TREBLE -> (if (h.number >= 17) 1.6 else 2.2) + (20 - h.number) * 0.01
        Ring.OUTER_BULL -> 2.0
        Ring.BULL -> 2.4
        else -> 2.0
    }

    private fun doubleRank(h: DartHit): Int =
        if (h.ring == Ring.BULL) DOUBLE_PREF.size else DOUBLE_PREF.indexOf(h.number).let { if (it < 0) 99 else it }

    private val cache = HashMap<Triple<Int, Int, Boolean>, List<DartHit>?>()

    /**
     * The best way to reach exactly [remaining] in at most [maxDarts] darts (1..3), or null. With
     * [doubleOut] the last dart must be a double or the bull; without it any dart may finish.
     */
    @Synchronized
    fun route(remaining: Int, maxDarts: Int = 3, doubleOut: Boolean = true): List<DartHit>? {
        if (remaining < 1 || maxDarts < 1) return null
        val md = maxDarts.coerceAtMost(3)
        return cache.getOrPut(Triple(remaining, md, doubleOut)) { search(remaining, md, doubleOut) }
    }

    private fun search(remaining: Int, maxDarts: Int, doubleOut: Boolean): List<DartHit>? {
        if (remaining > 180) return null
        val finishers = if (doubleOut) allHits.filter { it.isDouble } else allHits
        var best: List<DartHit>? = null
        var bestKey = DoubleArray(0)
        fun consider(seq: List<DartHit>) {
            val last = seq.last()
            val key = doubleArrayOf(
                seq.size.toDouble(), round(seq.sumOf { ease(it) } * 10_000.0), (if (doubleOut) doubleRank(last) else 0).toDouble(),
                -seq.first().score.toDouble()
            )
            val b = best
            if (b == null || compare(key, bestKey) < 0) { best = seq; bestKey = key }
        }
        for (a in finishers) if (a.score == remaining) consider(listOf(a))
        if (maxDarts >= 2) {
            for (f in finishers) {
                val rest = remaining - f.score
                if (rest <= 0) continue
                for (a in allHits) if (a.score == rest) consider(listOf(a, f))
            }
        }
        if (maxDarts >= 3) {
            for (f in finishers) {
                val rest = remaining - f.score
                if (rest <= 0) continue
                for (a in allHits) {
                    val rest2 = rest - a.score
                    if (rest2 <= 0) continue
                    for (b in allHits) if (b.score == rest2) consider(listOf(b, a, f))
                }
            }
        }
        return best
    }

    private fun compare(a: DoubleArray, b: DoubleArray): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }
}

/**
 * The aim-wobble model. A throw lands where the player aimed plus a bell-curve scatter ([sigma]
 * millimetres, a little taller than wide because a hand wobbles more vertically than sideways). The
 * throw [power] (0..1, the flick) matters too: inside the comfortable band 0.55..0.80 the scatter is
 * as given, outside it grows, and a soft throw also sags below the aim. All the randomness comes from
 * the [Random] handed in, so a seeded match replays exactly.
 */
object DartsAim {
    const val SIGMA_STEADY = 7.0
    const val SIGMA_NORMAL = 12.0
    const val SIGMA_SHAKY = 19.0
    const val SIGMA_CPU_EASY = 30.0
    const val SIGMA_CPU_MEDIUM = 17.0
    const val SIGMA_CPU_HARD = 9.0
    const val IDEAL_POWER = 0.675
    const val VERTICAL_WOBBLE = 1.2

    fun scatterFactor(power: Double): Double {
        val dev = (abs(power - IDEAL_POWER) - 0.125).coerceAtLeast(0.0)
        return 1.0 + 3.0 * dev
    }

    fun sag(power: Double): Double = (0.5 - power).coerceAtLeast(0.0) * 80.0

    fun gauss2(rng: Random): Pair<Double, Double> {
        val u1 = 1.0 - rng.nextDouble()
        val u2 = rng.nextDouble()
        val m = sqrt(-2.0 * ln(u1))
        return (m * cos(2 * PI * u2)).coerceIn(-3.2, 3.2) to (m * sin(2 * PI * u2)).coerceIn(-3.2, 3.2)
    }

    private fun tenth(v: Double) = round(v * 10.0) / 10.0

    /** Where a dart aimed at ([aimX], [aimY]) with [power] lands, to 0.1 mm. */
    fun land(aimX: Double, aimY: Double, power: Double, sigma: Double, rng: Random): Pair<Double, Double> {
        val s = sigma * scatterFactor(power)
        val (gx, gy) = gauss2(rng)
        val x = (aimX + gx * s).coerceIn(-DartsBoard.MAX_AIM, DartsBoard.MAX_AIM)
        val y = (aimY + gy * s * VERTICAL_WOBBLE + sag(power)).coerceIn(-DartsBoard.MAX_AIM, DartsBoard.MAX_AIM)
        return tenth(x) to tenth(y)
    }

    /** The per-throw random stream: depends only on the match seed and the dart's serial number. */
    fun throwRandom(matchSeed: Long, throwSerial: Int): Random =
        Random(matchSeed * 6364136223846793005L + (throwSerial.toLong() + 1L) * 1442695040888963407L)
}

/**
 * The chance of landing in each part of the board for an aim point, by a fixed quadrature of the
 * scatter (no randomness): what the computer players plan with and what a hint is based on.
 */
object DartsOdds {
    private class Sample(val dx: Double, val dy: Double, val w: Double)

    private val samples: List<Sample> by lazy {
        val out = ArrayList<Sample>()
        out += Sample(0.0, 0.0, 0.06125)
        val ks = doubleArrayOf(0.7, 1.4, 2.1, 2.8)
        val angles = 12
        for ((ki, k) in ks.withIndex()) {
            val ringW = k * 0.7 * exp(-k * k / 2.0)
            for (j in 0 until angles) {
                val a = 2 * PI * (j + 0.5 * (ki % 2)) / angles
                out += Sample(k * cos(a), k * sin(a), ringW / angles)
            }
        }
        val total = out.sumOf { it.w }
        out.map { Sample(it.dx, it.dy, it.w / total) }
    }

    fun distribution(aimX: Double, aimY: Double, sigma: Double): Map<DartHit, Double> {
        val out = LinkedHashMap<DartHit, Double>()
        for (s in samples) {
            val hit = DartsBoard.resolve(aimX + s.dx * sigma, aimY + s.dy * sigma * DartsAim.VERTICAL_WOBBLE)
            out[hit] = (out[hit] ?: 0.0) + s.w
        }
        return out
    }
}
