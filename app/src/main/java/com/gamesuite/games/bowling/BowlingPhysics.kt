package com.gamesuite.games.bowling

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One roll, as the player describes it: [x] where the ball starts across the foul line (metres, 0 is
 * the middle, right is positive), [slope] the sideways travel per metre down the lane (aim), [speed]
 * in metres per second and [spin] from -1 (hooks left) to 1 (hooks right).
 */
@Serializable
data class BowlShot(val x: Double, val slope: Double, val speed: Double, val spin: Double) {
    fun isValid(): Boolean = x.isFinite() && slope.isFinite() && speed.isFinite() && spin.isFinite() &&
        x in -BowlingLane.MAX_START..BowlingLane.MAX_START && slope in -BowlingLane.MAX_SLOPE..BowlingLane.MAX_SLOPE &&
        speed in BowlingLane.MIN_SPEED..BowlingLane.MAX_SPEED && spin in -1.0..1.0

    fun clamped(): BowlShot = BowlShot(
        x.coerceIn(-BowlingLane.MAX_START, BowlingLane.MAX_START),
        slope.coerceIn(-BowlingLane.MAX_SLOPE, BowlingLane.MAX_SLOPE),
        speed.coerceIn(BowlingLane.MIN_SPEED, BowlingLane.MAX_SPEED),
        spin.coerceIn(-1.0, 1.0)
    )
}

/** Lane and pin dimensions in metres (regulation), with the tuning of the toy physics. */
object BowlingLane {
    const val HALF_W = 0.5271
    const val BALL_R = 0.1090
    const val PIN_R = 0.0620
    const val FALLEN_R = 0.10
    const val HEAD_Y = 18.288
    const val DECK_END = 19.4
    const val BREAK_Y = 6.5
    const val HOOK_END_Y = 17.2
    const val HOOK_K = 0.30
    const val MAX_START = 0.50
    const val MAX_SLOPE = 0.06
    const val MIN_SPEED = 5.0
    const val MAX_SPEED = 9.5
    const val DEFAULT_SPEED = 7.5
    const val BALL_MASS = 6.35
    const val PIN_MASS = 1.5
    private const val SP = 0.3048
    private const val ROW = 0.2639

    /** Pin positions for pins 1..10 (index 0..9): x right, y down the lane. */
    val PIN_X = doubleArrayOf(0.0, -SP / 2, SP / 2, -SP, 0.0, SP, -1.5 * SP, -SP / 2, SP / 2, 1.5 * SP)
    val PIN_Y = doubleArrayOf(
        HEAD_Y, HEAD_Y + ROW, HEAD_Y + ROW, HEAD_Y + 2 * ROW, HEAD_Y + 2 * ROW, HEAD_Y + 2 * ROW,
        HEAD_Y + 3 * ROW, HEAD_Y + 3 * ROW, HEAD_Y + 3 * ROW, HEAD_Y + 3 * ROW
    )
    const val ALL_PINS = 0x3FF

    fun pinCount(mask: Int): Int = Integer.bitCount(mask and ALL_PINS)

    /** Pin numbers (1..10) standing in [mask], in order. */
    fun pinNumbers(mask: Int): List<Int> = (0 until 10).filter { mask and (1 shl it) != 0 }.map { it + 1 }

    /** True when the head pin is down and the remaining pins are not all touching each other (a split). */
    fun isSplit(mask: Int): Boolean {
        val m = mask and ALL_PINS
        if (m and 1 != 0 || m == 0) return false
        val pins = (0 until 10).filter { m and (1 shl it) != 0 }
        if (pins.size < 2) return false
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Int>().also { it.add(pins[0]); seen.add(pins[0]) }
        while (stack.isNotEmpty()) {
            val a = stack.removeLast()
            for (b in pins) {
                if (b in seen) continue
                val dx = PIN_X[a] - PIN_X[b]; val dy = PIN_Y[a] - PIN_Y[b]
                if (dx * dx + dy * dy <= 0.31 * 0.31) { seen.add(b); stack.add(b) }
            }
        }
        return seen.size < pins.size
    }
}

/** One recorded instant of a roll, for the screen to play back. */
class SimFrame(
    val t: Float, val ballX: Float, val ballY: Float, val ballGone: Boolean,
    val px: FloatArray, val py: FloatArray,
    /** 0 standing, 1 fallen (lying), 2 gone (swept away or never there). */
    val pstate: ByteArray,
    /** Direction a fallen pin was sent, for drawing it lying down. */
    val fx: FloatArray, val fy: FloatArray
)

class SimResult(val standing: Int, val gutter: Boolean, val frames: List<SimFrame>)

/**
 * Deterministic 2D lane physics. The ball runs straight to the hook point, then curves toward its
 * spin side; at the pin deck the ball and the pins are discs that collide with restitution, a pin
 * topples when it is struck hard enough, and a toppled pin keeps sliding and can take other pins
 * with it. Only +, -, *, / and sqrt are used, so the same shot gives the same pins on any device.
 */
object BowlingPhysics {
    private const val DT = 1.0 / 240.0
    private const val MAX_STEPS = 240 * 7
    private const val RECORD_EVERY = 4
    private const val BALL_E = 0.5
    private const val PIN_E = 0.4
    private const val TOPPLE_BALL = 0.5
    private const val TOPPLE_PIN = 0.9
    private const val STAND_FRICTION = 6.0
    private const val FALLEN_FRICTION = 3.5

    fun simulate(shot: BowlingShotInput): SimResult = simulate(shot.shot, shot.standing, shot.bumpers, shot.record)

    fun simulate(shot: BowlShot, standing: Int, bumpers: Boolean, record: Boolean = false): SimResult {
        val s = shot.clamped()
        var bx = s.x
        var by = 0.0
        val vy0 = s.speed / sqrt(1.0 + s.slope * s.slope)
        var vy = vy0
        var vx = vy0 * s.slope
        var gutter = false
        var ballGone = false

        val px = BowlingLane.PIN_X.copyOf()
        val py = BowlingLane.PIN_Y.copyOf()
        val pvx = DoubleArray(10)
        val pvy = DoubleArray(10)
        val st = IntArray(10) { if (standing and (1 shl it) != 0) 0 else 2 }
        val fx = DoubleArray(10)
        val fy = DoubleArray(10)
        val frames = ArrayList<SimFrame>()

        fun rad(i: Int) = if (st[i] == 0) BowlingLane.PIN_R else BowlingLane.FALLEN_R
        fun snap(t: Double) {
            frames += SimFrame(
                t.toFloat(), bx.toFloat(), by.toFloat(), ballGone,
                FloatArray(10) { px[it].toFloat() }, FloatArray(10) { py[it].toFloat() },
                ByteArray(10) { st[it].toByte() },
                FloatArray(10) { fx[it].toFloat() }, FloatArray(10) { fy[it].toFloat() }
            )
        }

        var quiet = 0
        var pinsActive = false
        var step = 0
        if (record) snap(0.0)
        while (step < MAX_STEPS) {
            step++
            // --- ball ---
            if (!ballGone) {
                if (!gutter && by >= BowlingLane.BREAK_Y && by < BowlingLane.HOOK_END_Y) vx += s.spin * BowlingLane.HOOK_K * DT
                bx += vx * DT
                by += vy * DT
                val onLane = by < BowlingLane.HEAD_Y - 0.6
                if (bumpers && onLane) {
                    val lim = BowlingLane.HALF_W - BowlingLane.BALL_R + 0.02
                    if (bx > lim) { bx = lim; vx = -abs(vx) * 0.6 } else if (bx < -lim) { bx = -lim; vx = abs(vx) * 0.6 }
                } else if (onLane && !gutter && abs(bx) > BowlingLane.HALF_W) {
                    gutter = true
                    bx = if (bx > 0) BowlingLane.HALF_W + 0.12 else -BowlingLane.HALF_W - 0.12
                    vx = 0.0
                }
                if (by > 21.0) ballGone = true
            }
            if (!pinsActive && !ballGone && by > BowlingLane.HEAD_Y - 0.4) pinsActive = true
            // --- pins move and slow down ---
            for (i in 0 until 10) {
                if (!pinsActive) break
                if (st[i] == 2) continue
                px[i] += pvx[i] * DT
                py[i] += pvy[i] * DT
                val sp = sqrt(pvx[i] * pvx[i] + pvy[i] * pvy[i])
                if (sp > 0.0) {
                    val dec = (if (st[i] == 0) STAND_FRICTION else FALLEN_FRICTION) * DT
                    if (sp <= dec) { pvx[i] = 0.0; pvy[i] = 0.0 } else { val k = (sp - dec) / sp; pvx[i] *= k; pvy[i] *= k }
                }
            }
            // --- ball against pins ---
            if (pinsActive && !ballGone && !gutter) {
                for (i in 0 until 10) {
                    if (st[i] == 2) continue
                    val dx = px[i] - bx
                    val dy = py[i] - by
                    val rr = BowlingLane.BALL_R + rad(i)
                    val d2 = dx * dx + dy * dy
                    if (d2 >= rr * rr || d2 == 0.0) continue
                    val d = sqrt(d2)
                    val nx = dx / d
                    val ny = dy / d
                    val rv = (pvx[i] - vx) * nx + (pvy[i] - vy) * ny
                    if (rv < 0) {
                        val j = -(1.0 + BALL_E) * rv / (1.0 / BowlingLane.BALL_MASS + 1.0 / BowlingLane.PIN_MASS)
                        vx -= j / BowlingLane.BALL_MASS * nx
                        vy -= j / BowlingLane.BALL_MASS * ny
                        pvx[i] += j / BowlingLane.PIN_MASS * nx
                        pvy[i] += j / BowlingLane.PIN_MASS * ny
                        if (st[i] == 0 && -rv >= TOPPLE_BALL) { st[i] = 1; fx[i] = nx; fy[i] = ny }
                    }
                    px[i] = bx + nx * rr
                    py[i] = by + ny * rr
                }
            }
            // --- pins against pins ---
            for (i in 0 until 9) {
                if (!pinsActive) break
                if (st[i] == 2) continue
                for (j in i + 1 until 10) {
                    if (st[j] == 2) continue
                    val dx = px[j] - px[i]
                    val dy = py[j] - py[i]
                    val rr = rad(i) + rad(j)
                    val d2 = dx * dx + dy * dy
                    if (d2 >= rr * rr || d2 == 0.0) continue
                    val d = sqrt(d2)
                    val nx = dx / d
                    val ny = dy / d
                    val rv = (pvx[j] - pvx[i]) * nx + (pvy[j] - pvy[i]) * ny
                    if (rv < 0) {
                        val imp = -(1.0 + PIN_E) * rv / 2.0
                        pvx[i] -= imp * nx; pvy[i] -= imp * ny
                        pvx[j] += imp * nx; pvy[j] += imp * ny
                        if (-rv >= TOPPLE_PIN) {
                            if (st[i] == 0) { st[i] = 1; fx[i] = -nx; fy[i] = -ny }
                            if (st[j] == 0) { st[j] = 1; fx[j] = nx; fy[j] = ny }
                        }
                    }
                    val push = (rr - d) / 2.0
                    px[i] -= nx * push; py[i] -= ny * push
                    px[j] += nx * push; py[j] += ny * push
                }
            }
            // --- swept off the deck ---
            var moving = false
            for (i in 0 until 10) {
                if (!pinsActive) break
                if (st[i] == 2) continue
                if (py[i] > BowlingLane.DECK_END || abs(px[i]) > BowlingLane.HALF_W + 0.10 || py[i] < BowlingLane.HEAD_Y - 0.9) {
                    st[i] = 2
                    continue
                }
                if (pvx[i] != 0.0 || pvy[i] != 0.0) moving = true
            }
            if (record && step % RECORD_EVERY == 0) snap(step * DT)
            val ballBusy = !ballGone && by < BowlingLane.HEAD_Y + 1.2
            if (!moving && !ballBusy) quiet++ else quiet = 0
            if (quiet > 40 || (standing == 0)) break
        }
        if (record) snap(step * DT)
        var mask = 0
        for (i in 0 until 10) if (st[i] == 0) mask = mask or (1 shl i)
        return SimResult(mask, gutter, frames)
    }

    /** The path the ball takes ignoring the pins: (x, y) every 0.25 m down the lane, for the aim preview. */
    fun path(shot: BowlShot, bumpers: Boolean): List<Pair<Double, Double>> {
        val s = shot.clamped()
        val out = ArrayList<Pair<Double, Double>>()
        var bx = s.x
        var by = 0.0
        val vy = s.speed / sqrt(1.0 + s.slope * s.slope)
        var vx = vy * s.slope
        var gutter = false
        var next = 0.0
        var steps = 0
        while (by < BowlingLane.HEAD_Y + 0.5 && steps++ < MAX_STEPS) {
            if (!gutter && by >= BowlingLane.BREAK_Y && by < BowlingLane.HOOK_END_Y) vx += s.spin * BowlingLane.HOOK_K * DT
            bx += vx * DT
            by += vy * DT
            if (bumpers) {
                val lim = BowlingLane.HALF_W - BowlingLane.BALL_R + 0.02
                if (bx > lim) { bx = lim; vx = -abs(vx) * 0.6 } else if (bx < -lim) { bx = -lim; vx = abs(vx) * 0.6 }
            } else if (!gutter && abs(bx) > BowlingLane.HALF_W) {
                gutter = true
                bx = if (bx > 0) BowlingLane.HALF_W + 0.12 else -BowlingLane.HALF_W - 0.12
                vx = 0.0
            }
            if (by >= next) { out += bx to by; next += 0.25 }
        }
        return out
    }
}

/** Inputs of [BowlingPhysics.simulate] bundled for the screen. */
data class BowlingShotInput(val shot: BowlShot, val standing: Int, val bumpers: Boolean, val record: Boolean = true)

/** How steady a roll is: the scatter added to the player's intended shot. */
class BowlingScatter(val x: Double, val slope: Double, val speed: Double, val spin: Double) {
    fun apply(shot: BowlShot, rng: Random): BowlShot {
        fun g(): Double {
            val u1 = 1.0 - rng.nextDouble()
            val u2 = rng.nextDouble()
            return (sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)).coerceIn(-3.0, 3.0)
        }
        return BowlShot(shot.x + g() * x, shot.slope + g() * slope, shot.speed + g() * speed, shot.spin + g() * spin).clamped()
    }

    companion object {
        val STEADY = BowlingScatter(0.006, 0.0006, 0.12, 0.02)
        val NORMAL = BowlingScatter(0.012, 0.0012, 0.2, 0.05)
        val SHAKY = BowlingScatter(0.025, 0.0025, 0.35, 0.10)
        val CPU_EASY = BowlingScatter(0.10, 0.009, 0.6, 0.30)
        val CPU_MEDIUM = BowlingScatter(0.03, 0.0026, 0.3, 0.12)
        val CPU_HARD = BowlingScatter(0.01, 0.0009, 0.15, 0.035)
    }
}

/** The per-roll random stream: depends only on the match seed and the roll's serial number. */
fun bowlingRollRandom(matchSeed: Long, rollSerial: Int): Random =
    Random(matchSeed * 6364136223846793005L + (rollSerial.toLong() + 1L) * 1442695040888963407L)
