package com.gamesuite.games.slotcars

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Fixed numbers of the Slot Cars world. Distances are track units, speeds units per second. */
object SlotCarsRules {
    const val STEPS_PER_SECOND = 60
    const val STEP_MS = 1000.0 / STEPS_PER_SECOND
    const val DT = 1.0 / STEPS_PER_SECOND

    /** Top speed with the throttle held for ever. */
    const val VMAX = 300.0
    /** Acceleration at standstill; it fades to nothing at [VMAX]. */
    const val ACC = 260.0
    /** Braking with the throttle released (the motor drags the car like a magnet). */
    const val BRAKE = 330.0
    const val DRAG = 0.6
    /** A car leaves the slot when speed squared times the bend's curvature passes this (lateral acceleration). */
    const val A_MAX = 361.0
    /** Ticks a car lies off the track before it is put back in the slot. */
    const val RESLOT_TICKS = 100
    const val COUNTDOWN_TICKS = 240
    /** Once the first car finishes the others have this long to follow. */
    const val FINISH_GRACE_TICKS = 900
    const val MAX_RACE_TICKS = 60 * 60 * 6
    /** Ticks one step of the stepwise assist covers (half a second). */
    const val STEP_TICKS = 30
    const val MAX_CARS = 4
    val LAP_CHOICES = listOf(3, 5, 10)
    const val LANE_WIDTH = 15.0

    /** The speed above which a bend of the given curvature throws a car off. */
    fun criticalSpeed(curvature: Double): Double = if (curvature < 1e-9) 1e9 else sqrt(A_MAX / curvature)

    /** Points for a finishing place (0 = winner) among [cars]: the winner gets [cars], the last 1. */
    fun points(place: Int, cars: Int): Int = (cars - place).coerceAtLeast(1)
}

/**
 * One original closed track: a smooth loop through the control [points] (closed Catmull-Rom),
 * re-sampled evenly by arc length. Everything the physics needs is a table lookup, so it is exact
 * and identical on every device.
 */
class SlotTrack(val id: String, private val points: List<Pair<Double, Double>>) {
    val length: Double
    private val n: Int
    private val ds: Double
    private val px: DoubleArray
    private val py: DoubleArray
    private val heading: DoubleArray
    private val curve: DoubleArray
    val minX: Double; val maxX: Double; val minY: Double; val maxY: Double

    init {
        val dense = ArrayList<DoubleArray>()
        val m = points.size
        for (i in 0 until m) {
            val p0 = points[(i + m - 1) % m]; val p1 = points[i]; val p2 = points[(i + 1) % m]; val p3 = points[(i + 2) % m]
            for (k in 0 until 48) {
                val t = k / 48.0; val t2 = t * t; val t3 = t2 * t
                fun cr(a: Double, b: Double, c: Double, d: Double) =
                    0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                dense += doubleArrayOf(cr(p0.first, p1.first, p2.first, p3.first), cr(p0.second, p1.second, p2.second, p3.second))
            }
        }
        val cum = DoubleArray(dense.size + 1)
        for (i in dense.indices) {
            val a = dense[i]; val b = dense[(i + 1) % dense.size]
            cum[i + 1] = cum[i] + StrictMath.hypot(b[0] - a[0], b[1] - a[1])
        }
        val total = cum[dense.size]
        n = Math.round(total / 4.0).toInt().coerceAtLeast(64)
        ds = total / n
        length = total
        px = DoubleArray(n); py = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val target = k * ds
            while (j < dense.size - 1 && cum[j + 1] < target) j++
            val a = dense[j]; val b = dense[(j + 1) % dense.size]
            val seg = (cum[j + 1] - cum[j]).coerceAtLeast(1e-9)
            val f = ((target - cum[j]) / seg).coerceIn(0.0, 1.0)
            px[k] = a[0] + (b[0] - a[0]) * f; py[k] = a[1] + (b[1] - a[1]) * f
        }
        heading = DoubleArray(n) { k ->
            val a = (k + n - 1) % n; val b = (k + 1) % n
            StrictMath.atan2(py[b] - py[a], px[b] - px[a])
        }
        val raw = DoubleArray(n) { k ->
            var d = heading[(k + 1) % n] - heading[k]
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            d / ds
        }
        curve = DoubleArray(n) { k ->
            var sum = 0.0
            for (o in -6..6) sum += raw[(k + o + n) % n]
            sum / 13.0
        }
        minX = px.min(); maxX = px.max(); minY = py.min(); maxY = py.max()
    }

    private fun index(s: Double): Double {
        var u = (s % length) / ds
        if (u < 0) u += n
        return u
    }

    /** Position on the centre line at distance [s] (any real number: the track repeats). */
    fun x(s: Double): Double { val u = index(s); val i = floor(u).toInt() % n; val f = u - floor(u); return px[i] + (px[(i + 1) % n] - px[i]) * f }
    fun y(s: Double): Double { val u = index(s); val i = floor(u).toInt() % n; val f = u - floor(u); return py[i] + (py[(i + 1) % n] - py[i]) * f }

    /** Direction of travel in radians. */
    fun headingAt(s: Double): Double {
        val u = index(s); val i = floor(u).toInt() % n; val j = (i + 1) % n; val f = u - floor(u)
        var d = heading[j] - heading[i]
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return heading[i] + d * f
    }

    /** Absolute curvature (1 / radius) at [s]. */
    fun curvatureAt(s: Double): Double { val u = index(s); val i = floor(u).toInt() % n; val j = (i + 1) % n; val f = u - floor(u); return abs(curve[i] + (curve[j] - curve[i]) * f) }

    /** Signed turn direction at [s]: +1 clockwise on screen (y down), -1 the other way, 0 straight. */
    fun turnSign(s: Double): Int {
        val u = index(s); val i = floor(u).toInt() % n
        return if (abs(curve[i]) < 1e-4) 0 else if (curve[i] > 0) 1 else -1
    }

    /** Position of a car in lane [lane] of [lanes] (lanes are spread symmetrically about the centre line). */
    fun laneX(s: Double, lane: Int, lanes: Int): Double = x(s) - StrictMath.sin(headingAt(s)) * laneOffset(lane, lanes)
    fun laneY(s: Double, lane: Int, lanes: Int): Double = y(s) + StrictMath.cos(headingAt(s)) * laneOffset(lane, lanes)
    fun laneOffset(lane: Int, lanes: Int): Double = (lane - (lanes - 1) / 2.0) * SlotCarsRules.LANE_WIDTH

    /** The slowest safe speed anywhere on the lap. */
    val slowestCritical: Double by lazy { (0 until n).minOf { SlotCarsRules.criticalSpeed(abs(curve[it])) } }

    /** Length of the lap with the throttle held and never leaving the track, ignoring bends (a lower bound). */
    val minLapSeconds: Double get() = length / SlotCarsRules.VMAX

    /** A stretch of bend that needs braking for: where it starts and ends along the lap, and its sharpest curvature. */
    data class Corner(val startS: Double, val endS: Double, val peak: Double)

    /** Every bend whose safe speed is below 80 percent of top speed, in lap order (for the warning signs). */
    val corners: List<Corner> by lazy {
        val out = ArrayList<Corner>()
        val limit = SlotCarsRules.A_MAX / (0.8 * SlotCarsRules.VMAX).let { it * it }
        var start = -1; var peak = 0.0
        for (k in 0..n) {
            val c = if (k < n) abs(curve[k]) else 0.0
            if (c > limit) { if (start < 0) start = k; if (c > peak) peak = c }
            else if (start >= 0) { out += Corner(start * ds, (k - 1) * ds, peak); start = -1; peak = 0.0 }
        }
        out
    }

    companion object {
        /** The three tracks. Ids are the values of the `track` hub option. */
        val ids = listOf("meadow", "hill", "twist")

        private val cache = HashMap<String, SlotTrack>()

        fun byId(id: String?): SlotTrack = synchronized(cache) {
            val key = if (id in ids) id!! else ids[0]
            cache.getOrPut(key) { SlotTrack(key, pointsFor(key)) }
        }

        private fun pointsFor(id: String): List<Pair<Double, Double>> = when (id) {
            // A friendly oval with one tightening bend at the far end.
            "meadow" -> listOf(
                170.0 to 320.0, 250.0 to 160.0, 430.0 to 110.0, 640.0 to 120.0, 790.0 to 190.0,
                840.0 to 330.0, 770.0 to 470.0, 610.0 to 520.0, 420.0 to 505.0, 260.0 to 470.0
            )
            // Straights joined by a hairpin and a chicane: brake hard or leave the slot.
            "hill" -> listOf(
                140.0 to 120.0, 400.0 to 100.0, 700.0 to 110.0, 860.0 to 150.0, 910.0 to 240.0, 860.0 to 330.0,
                700.0 to 370.0, 560.0 to 410.0, 420.0 to 360.0, 260.0 to 380.0, 140.0 to 340.0, 100.0 to 230.0
            )
            // A twisty loop of S bends.
            else -> listOf(
                100.0 to 230.0, 230.0 to 120.0, 380.0 to 190.0, 530.0 to 110.0, 680.0 to 190.0, 830.0 to 120.0,
                920.0 to 250.0, 830.0 to 420.0, 690.0 to 500.0, 540.0 to 430.0, 390.0 to 510.0, 240.0 to 440.0, 120.0 to 360.0
            )
        }
    }
}
