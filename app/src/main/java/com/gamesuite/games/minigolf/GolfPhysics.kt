package com.gamesuite.games.minigolf

import kotlinx.serialization.Serializable

/** One putt: [angle] in radians (0 = +x, y grows downward), [power] 0..1. */
@Serializable
data class GolfShot(val angle: Double, val power: Double) {
    val isFinite: Boolean get() = angle.isFinite() && power.isFinite()
}

object GolfEnd {
    const val REST = 0
    const val CUP = 1
    const val WATER = 2
}

class GolfEvent(val step: Int, val kind: Int, val strength: Double) {
    companion object {
        const val WALL = 0
        const val BUMPER = 1
        const val SAND = 2
        const val CUP = 3
        const val WATER = 4
    }
}

class GolfSim(
    /** Where the ball ended: at rest, in the cup (the cup centre) or, for water, where it sank. */
    val x: Double,
    val y: Double,
    val end: Int,
    val steps: Int,
    val events: List<GolfEvent>,
    /** x, y pairs, one per [frameEvery] steps, or null. */
    val frames: FloatArray?,
    val frameEvery: Int,
    /** Whether the ball touched sand at any point (for the screen's feedback). */
    val touchedSand: Boolean
)

/**
 * Deterministic one-ball physics for a hole: a fixed 1/240 s step and plain Double arithmetic (+ - * / and
 * sqrt, plus StrictMath for the aim direction), so a stroke replays bit-identically on every device.
 * Walls reflect with 75% of the speed, bumpers kick the ball away, sand and rolling friction slow it, ramps
 * accelerate it, water sinks it, and a slow ball near the cup is pulled in.
 */
object GolfPhysics {
    const val DT = 1.0 / 240.0
    const val BALL_R = 1.2
    const val CUP_R = 2.2
    const val FRICTION = 55.0
    const val SAND_FRICTION = 300.0
    const val WALL_RESTITUTION = 0.75
    const val MIN_SPEED = 14.0
    const val MAX_SPEED = 200.0
    const val SPEED_CAP = 230.0
    const val CUP_CAPTURE_SPEED = 110.0
    const val CUP_PULL_SPEED = 70.0
    const val CUP_PULL = 90.0
    const val MAX_STEPS = 240 * 22

    fun speedFor(power: Double): Double {
        val p = power.coerceIn(0.0, 1.0)
        return MIN_SPEED + (MAX_SPEED - MIN_SPEED) * p * p
    }

    fun simulate(hole: GolfHole, startX: Double, startY: Double, shot: GolfShot, frameEvery: Int = 0): GolfSim {
        var x = startX; var y = startY
        val v0 = speedFor(shot.power)
        var vx = StrictMath.cos(shot.angle) * v0
        var vy = StrictMath.sin(shot.angle) * v0
        val events = ArrayList<GolfEvent>()
        val frames: ArrayList<Float>? = if (frameEvery > 0) ArrayList() else null
        var touchedSand = false
        var wasInSand = false
        val walls = hole.walls
        val r = BALL_R
        var end = GolfEnd.REST
        var step = 0
        frames?.let { it.add(x.toFloat()); it.add(y.toFloat()) }
        while (step < MAX_STEPS) {
            step++
            // acceleration: ramps, cup pull
            for (rp in hole.ramps) if (rp.area.contains(x, y)) { vx += rp.ax * DT; vy += rp.ay * DT }
            val cdx = hole.cup.x - x; val cdy = hole.cup.y - y
            val cd2 = cdx * cdx + cdy * cdy
            val speed2 = vx * vx + vy * vy
            if (cd2 < CUP_R * CUP_R * 3.2 && speed2 < CUP_PULL_SPEED * CUP_PULL_SPEED) {
                val cd = Math.sqrt(cd2)
                if (cd > 1e-9) { vx += cdx / cd * CUP_PULL * DT; vy += cdy / cd * CUP_PULL * DT }
            }
            // friction
            val inSand = hole.inSand(x, y)
            if (inSand && !wasInSand) { events.add(GolfEvent(step, GolfEvent.SAND, Math.sqrt(speed2))) }
            wasInSand = inSand
            if (inSand) touchedSand = true
            val sp = Math.sqrt(vx * vx + vy * vy)
            if (sp > 0.0) {
                val decel = (if (inSand) SAND_FRICTION else FRICTION) * DT
                val ns = sp - decel
                if (ns <= 0.0) { vx = 0.0; vy = 0.0 } else { val k = ns / sp; vx *= k; vy *= k }
            }
            // cap
            val sp2 = vx * vx + vy * vy
            if (sp2 > SPEED_CAP * SPEED_CAP) { val k = SPEED_CAP / Math.sqrt(sp2); vx *= k; vy *= k }
            x += vx * DT; y += vy * DT

            // water
            if (hole.inWater(x, y)) {
                events.add(GolfEvent(step, GolfEvent.WATER, Math.sqrt(vx * vx + vy * vy)))
                end = GolfEnd.WATER
                break
            }
            // cup
            val ex = hole.cup.x - x; val ey = hole.cup.y - y
            if (ex * ex + ey * ey < CUP_R * CUP_R && vx * vx + vy * vy < CUP_CAPTURE_SPEED * CUP_CAPTURE_SPEED) {
                events.add(GolfEvent(step, GolfEvent.CUP, Math.sqrt(vx * vx + vy * vy)))
                x = hole.cup.x; y = hole.cup.y; vx = 0.0; vy = 0.0
                end = GolfEnd.CUP
                break
            }
            // walls (two passes so a corner settles)
            for (pass in 0 until 2) {
                for (w in walls) {
                    val ax = w[0]; val ay = w[1]; val bx = w[2]; val by = w[3]
                    val sx = bx - ax; val sy = by - ay
                    val l2 = sx * sx + sy * sy
                    val t = if (l2 < 1e-12) 0.0 else (((x - ax) * sx + (y - ay) * sy) / l2).coerceIn(0.0, 1.0)
                    val qx = ax + sx * t; val qy = ay + sy * t
                    var dx = x - qx; var dy = y - qy
                    val d2 = dx * dx + dy * dy
                    if (d2 >= r * r) continue
                    var d = Math.sqrt(d2)
                    if (d < 1e-9) {
                        // centre exactly on the wall: push to the side the ball came from
                        val nl = Math.sqrt(l2).coerceAtLeast(1e-9)
                        dx = -sy / nl; dy = sx / nl; d = 1e-9
                    } else { dx /= d; dy /= d }
                    x = qx + dx * r; y = qy + dy * r
                    val vn = vx * dx + vy * dy
                    if (vn < 0.0) {
                        vx -= (1.0 + WALL_RESTITUTION) * vn * dx
                        vy -= (1.0 + WALL_RESTITUTION) * vn * dy
                        if (-vn > 3.0) events.add(GolfEvent(step, GolfEvent.WALL, -vn))
                    }
                }
            }
            // bumpers
            for (b in hole.bumpers) {
                var dx = x - b.x; var dy = y - b.y
                val rr = b.r + r
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr) continue
                var d = Math.sqrt(d2)
                if (d < 1e-9) { dx = 1.0; dy = 0.0; d = 1e-9 } else { dx /= d; dy /= d }
                x = b.x + dx * rr; y = b.y + dy * rr
                val vn = vx * dx + vy * dy
                if (vn < 0.0) {
                    vx -= 2.0 * vn * dx; vy -= 2.0 * vn * dy
                    vx += dx * 28.0; vy += dy * 28.0 // the springy kick
                    events.add(GolfEvent(step, GolfEvent.BUMPER, -vn))
                }
            }
            if (frames != null && step % frameEvery == 0) { frames.add(x.toFloat()); frames.add(y.toFloat()) }
            if (step > 1500 && vx * vx + vy * vy < 100.0) break // pinned against a wall by a belt: call it at rest
            if (vx == 0.0 && vy == 0.0) {
                // at rest, unless a ramp or the pull would move it again next step
                val onRamp = hole.ramps.any { it.area.contains(x, y) && (it.ax * it.ax + it.ay * it.ay) > FRICTION * FRICTION }
                if (!onRamp) break
            }
        }
        if (frames != null) {
            val n = frames.size
            if (n < 2 || frames[n - 2] != x.toFloat() || frames[n - 1] != y.toFloat()) { frames.add(x.toFloat()); frames.add(y.toFloat()) }
        }
        return GolfSim(x, y, end, step, events, frames?.toFloatArray(), frameEvery, touchedSand)
    }
}
