package com.gamesuite.games.billiards

import kotlinx.serialization.Serializable

/**
 * The cloth: a 100 x 50 playing surface (units, not centimetres; a ball is 3 units across, which is a
 * little bigger than a real ball so it can be seen and tapped on a phone). x runs along the long side,
 * y down the short side, ball 0 is the cue ball and 1..15 are the object balls.
 */
object BilliardsTable {
    const val W = 100.0
    const val H = 50.0
    const val R = 1.5
    const val BALLS = 16
    const val HEAD_X = 25.0
    const val FOOT_X = 75.0
    const val MID_Y = 25.0

    const val CORNER_POCKET_R = 3.7
    const val SIDE_POCKET_R = 3.3

    /** Pocket centres: 4 corners then the 2 side pockets. */
    val POCKET_X = doubleArrayOf(0.0, W, 0.0, W, W / 2, W / 2)
    val POCKET_Y = doubleArrayOf(0.0, 0.0, H, H, 0.0, H)
    val POCKET_R = doubleArrayOf(CORNER_POCKET_R, CORNER_POCKET_R, CORNER_POCKET_R, CORNER_POCKET_R, SIDE_POCKET_R, SIDE_POCKET_R)

    fun isSolid(ball: Int) = ball in 1..7
    fun isStripe(ball: Int) = ball in 9..15

    /** 0 = no group (cue, 8), 1 = solids, 2 = stripes. */
    fun groupOf(ball: Int): Int = if (isSolid(ball)) 1 else if (isStripe(ball)) 2 else 0
}

/**
 * One stroke of the cue. [angle] is the direction of travel in radians (0 = +x, y grows downward),
 * [power] runs 0..1, [spinX] is side english (-1 left .. 1 right as seen along the shot) and [spinY] is
 * top (+, follow) or back (-, draw) spin. [cueX]/[cueY] is where the cue ball sat when it was struck
 * (the player's chosen spot when they had ball in hand).
 */
@Serializable
data class Shot(
    val angle: Double,
    val power: Double,
    val spinX: Double = 0.0,
    val spinY: Double = 0.0,
    val cueX: Double,
    val cueY: Double
) {
    val isFinite: Boolean
        get() = angle.isFinite() && power.isFinite() && spinX.isFinite() && spinY.isFinite() && cueX.isFinite() && cueY.isFinite()
}

/** What the physics saw, in order: ball-on-ball contact, a ball touching a cushion, a ball dropping into a pocket. */
class SimEvent(val step: Int, val kind: Int, val a: Int, val b: Int, val strength: Double) {
    companion object {
        const val HIT = 0
        const val RAIL = 1
        const val POCKET = 2
    }
}

class SimResult(
    val x: DoubleArray,
    val y: DoubleArray,
    val onTable: BooleanArray,
    val events: List<SimEvent>,
    val steps: Int,
    /** Position snapshots (x0,y0 .. x15,y15 then 16 on-table flags 0/1), one per [frameEvery] steps, or null. */
    val frames: List<FloatArray>?,
    val frameEvery: Int
) {
    /** The first object ball the cue ball touched, or -1. */
    val firstContact: Int
        get() = events.firstOrNull { it.kind == SimEvent.HIT && (it.a == 0 || it.b == 0) }
            ?.let { if (it.a == 0) it.b else it.a } ?: -1

    /** Balls that dropped, in the order they fell. */
    val pocketed: List<Int> get() = events.filter { it.kind == SimEvent.POCKET }.map { it.a }

    private val contactStep: Int
        get() = events.firstOrNull { it.kind == SimEvent.HIT && (it.a == 0 || it.b == 0) }?.step ?: -1

    /** After the cue ball's first contact, did any ball reach a cushion or fall in a pocket? */
    val railOrPocketAfterContact: Boolean
        get() {
            val c = contactStep
            return c >= 0 && events.any { (it.kind == SimEvent.RAIL || it.kind == SimEvent.POCKET) && it.step >= c }
        }

    /** The object balls (not the cue ball) that touched a cushion at any time. */
    val objectBallsOnRails: Set<Int>
        get() = events.filter { it.kind == SimEvent.RAIL && it.a != 0 }.map { it.a }.toSet()
}

/**
 * Deterministic ball physics. A fixed 1/240 s step, plain Double arithmetic (+ - * / and sqrt only, which
 * Java defines exactly) and StrictMath for the aim direction: the same shot from the same positions
 * gives bit-identical results on every device. Nothing here reads a clock or a random number.
 *
 * It is a game model, not a full billiards simulation: rolling friction is a constant deceleration,
 * balls collide elastically with a little loss, cushions return 75% of the speed, follow and draw act
 * at the cue ball's first contact, and side english kicks the ball along the cushion when it bounces.
 * Pockets swallow a ball whose centre reaches the mouth.
 */
object BilliardsPhysics {
    const val DT = 1.0 / 240.0
    const val FRICTION = 40.0
    const val STOP_SPEED = 0.35
    const val BALL_RESTITUTION = 0.95
    const val CUSHION_RESTITUTION = 0.75
    const val MAX_STEPS = 240 * 45
    const val MIN_SPEED = 15.0
    const val MAX_SPEED = 250.0

    fun speedFor(power: Double): Double = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * power.coerceIn(0.0, 1.0)

    /**
     * Plays [shot] out from the given positions. [x]/[y]/[onTable] are not modified. The cue ball is first
     * moved to the shot's own cue position. [frameEvery] > 0 records a position frame every that many steps.
     */
    fun simulate(
        x: DoubleArray, y: DoubleArray, onTable: BooleanArray, shot: Shot, frameEvery: Int = 0
    ): SimResult {
        val n = BilliardsTable.BALLS
        val px = x.copyOf(); val py = y.copyOf(); val on = onTable.copyOf()
        val vx = DoubleArray(n); val vy = DoubleArray(n)
        val events = ArrayList<SimEvent>()
        val frames: ArrayList<FloatArray>? = if (frameEvery > 0) ArrayList() else null
        fun frame() {
            frames ?: return
            val f = FloatArray(n * 3)
            for (i in 0 until n) { f[i * 2] = px[i].toFloat(); f[i * 2 + 1] = py[i].toFloat(); f[2 * n + i] = if (on[i]) 1f else 0f }
            frames.add(f)
        }

        px[0] = shot.cueX; py[0] = shot.cueY; on[0] = true
        val dirX = StrictMath.cos(shot.angle); val dirY = StrictMath.sin(shot.angle)
        val v0 = speedFor(shot.power)
        vx[0] = dirX * v0; vy[0] = dirY * v0
        var spinY = shot.spinY.coerceIn(-1.0, 1.0)
        var spinX = shot.spinX.coerceIn(-1.0, 1.0)
        var spinUsed = false
        val minDist = 2 * BilliardsTable.R
        val minDist2 = minDist * minDist
        val r = BilliardsTable.R

        var step = 0
        frame()
        while (step < MAX_STEPS) {
            step++
            var moving = false
            for (i in 0 until n) {
                if (!on[i]) continue
                var sx = vx[i]; var sy = vy[i]
                val sp2 = sx * sx + sy * sy
                if (sp2 > 0.0) {
                    val sp = Math.sqrt(sp2)
                    val ns = sp - FRICTION * DT
                    if (ns <= STOP_SPEED) { sx = 0.0; sy = 0.0 } else { val k = ns / sp; sx *= k; sy *= k }
                    vx[i] = sx; vy[i] = sy
                    px[i] += sx * DT; py[i] += sy * DT
                    if (sx != 0.0 || sy != 0.0) moving = true
                }
            }
            // pockets
            for (i in 0 until n) {
                if (!on[i]) continue
                for (k in 0 until 6) {
                    val dx = px[i] - BilliardsTable.POCKET_X[k]; val dy = py[i] - BilliardsTable.POCKET_Y[k]
                    val pr = BilliardsTable.POCKET_R[k]
                    if (dx * dx + dy * dy < pr * pr) {
                        events.add(SimEvent(step, SimEvent.POCKET, i, k, Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i])))
                        on[i] = false; vx[i] = 0.0; vy[i] = 0.0
                        break
                    }
                }
            }
            // cushions
            for (i in 0 until n) {
                if (!on[i]) continue
                var nx = 0.0; var ny = 0.0
                if (px[i] < r) { px[i] = r; nx = 1.0 } else if (px[i] > BilliardsTable.W - r) { px[i] = BilliardsTable.W - r; nx = -1.0 }
                if (py[i] < r) { py[i] = r; ny = 1.0 } else if (py[i] > BilliardsTable.H - r) { py[i] = BilliardsTable.H - r; ny = -1.0 }
                if (nx != 0.0 && vx[i] * nx < 0.0) {
                    bounce(i, nx, 0.0, vx, vy, events, step, if (i == 0) spinX else 0.0)
                    if (i == 0) spinX *= 0.5
                }
                if (ny != 0.0 && vy[i] * ny < 0.0) {
                    bounce(i, 0.0, ny, vx, vy, events, step, if (i == 0) spinX else 0.0)
                    if (i == 0) spinX *= 0.5
                }
            }
            // ball against ball (two passes so a tight cluster settles)
            for (pass in 0 until 2) {
                for (i in 0 until n - 1) {
                    if (!on[i]) continue
                    for (j in i + 1 until n) {
                        if (!on[j]) continue
                        var dx = px[j] - px[i]; var dy = py[j] - py[i]
                        val d2 = dx * dx + dy * dy
                        if (d2 >= minDist2) continue
                        var d = Math.sqrt(d2)
                        if (d < 1e-9) { dx = 1.0; dy = 0.0; d = 1e-9 } else { dx /= d; dy /= d }
                        val overlap = (minDist - d) / 2.0
                        px[i] -= dx * overlap; py[i] -= dy * overlap
                        px[j] += dx * overlap; py[j] += dy * overlap
                        val vn = (vx[i] - vx[j]) * dx + (vy[i] - vy[j]) * dy
                        if (vn > 0.0) {
                            if (i == 0 && !spinUsed) {
                                // remember the cue ball's heading before it deflects, for follow / draw
                                val hs = Math.sqrt(vx[0] * vx[0] + vy[0] * vy[0])
                                val hx = vx[0] / hs; val hy = vy[0] / hs
                                applyImpulse(i, j, dx, dy, vn, vx, vy)
                                vx[0] += hx * spinY * 0.30 * vn; vy[0] += hy * spinY * 0.30 * vn
                                spinUsed = true; spinY = 0.0
                            } else {
                                applyImpulse(i, j, dx, dy, vn, vx, vy)
                            }
                            events.add(SimEvent(step, SimEvent.HIT, i, j, vn))
                        }
                    }
                }
            }
            for (i in 0 until n) if (on[i] && (vx[i] != 0.0 || vy[i] != 0.0)) moving = true
            if (frames != null && step % frameEvery == 0) frame()
            if (!moving) break
        }
        if (frames != null && step % frameEvery != 0) frame()
        return SimResult(px, py, on, events, step, frames, frameEvery)
    }

    private fun applyImpulse(i: Int, j: Int, nx: Double, ny: Double, vn: Double, vx: DoubleArray, vy: DoubleArray) {
        val imp = (1.0 + BALL_RESTITUTION) / 2.0 * vn
        vx[i] -= imp * nx; vy[i] -= imp * ny
        vx[j] += imp * nx; vy[j] += imp * ny
    }

    /** Reflect ball [i] off a cushion whose inward normal is (nx, ny); [side] is the cue ball's english. */
    private fun bounce(
        i: Int, nx: Double, ny: Double, vx: DoubleArray, vy: DoubleArray, events: MutableList<SimEvent>, step: Int, side: Double
    ) {
        val inX = vx[i]; val inY = vy[i]
        val vn = inX * nx + inY * ny // negative: heading into the cushion
        val tx = inX - vn * nx; val ty = inY - vn * ny
        vx[i] = tx * 0.98 - vn * nx * CUSHION_RESTITUTION
        vy[i] = ty * 0.98 - vn * ny * CUSHION_RESTITUTION
        if (side != 0.0) {
            // english kicks the ball toward its right-hand side (looking along its heading), along the cushion only
            val hs = Math.sqrt(inX * inX + inY * inY)
            if (hs > 1e-9) {
                val rx = -inY / hs; val ry = inX / hs
                val tx2 = -ny; val ty2 = nx // cushion tangent
                val along = rx * tx2 + ry * ty2
                val sgn = if (along >= 0.0) 1.0 else -1.0
                val kick = sgn * side * 0.25 * (-vn)
                vx[i] += tx2 * kick
                vy[i] += ty2 * kick
            }
        }
        events.add(SimEvent(step, SimEvent.RAIL, i, -1, -vn))
    }
}
