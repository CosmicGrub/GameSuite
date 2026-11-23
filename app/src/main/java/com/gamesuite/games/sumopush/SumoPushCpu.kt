package com.gamesuite.games.sumopush

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The computer sumos. One instance per computer seat; [update] is called every simulation step and only
 * re-thinks every [thinkMs], which is the reaction delay.
 *  - EASY thinks slowly (0.42 s), steers with a big wobble, often forgets the rope, and dashes at random.
 *  - MEDIUM (0.3 s) charges the nearest rival, retreats from the rope, and dashes when it is lined up.
 *  - HARD (0.22 s) goes for the rival closest to the rope, lines itself up on the centre side so the push drives
 *    them outward, holds a bigger charge, sidesteps incoming dashes and keeps well clear of the rope itself.
 * Pure: the engine, the level and an injected Random decide everything (the engine time is its only clock).
 */
class SumoCpu(private val level: CpuDifficulty, private val random: Random) {
    private var nextThink = 0L
    private var ix = 0.0
    private var iy = 0.0
    private var releaseAt = 0.0

    val thinkMs: Int get() = when (level) {
        CpuDifficulty.EASY -> 420
        CpuDifficulty.MEDIUM -> 300
        CpuDifficulty.HARD -> 220
    }

    /** A new round starts: forget the last round's think timer and charge plan (the engine clock restarts at zero). */
    fun reset() { nextThink = 0L; ix = 0.0; iy = 0.0; releaseAt = 0.0 }

    private fun wobble(rad: Double): Double = (random.nextDouble() * 2 - 1) * rad

    fun update(engine: SumoEngine, seat: Int) {
        val me = engine.sumos[seat]
        if (!me.alive || engine.over) { engine.setInput(seat, 0.0, 0.0); engine.setCharging(seat, false); return }
        // Releasing a charge is checked every step (it needs fine timing); steering is re-thought on the think interval.
        if (me.charging) {
            val t = engine.nearestRival(me)
            val dist = if (t == null) 9.0 else dist(me.x, me.y, t.x, t.y)
            val frac = me.chargeMs.toDouble() / SumoRules.CHARGE_MAX_MS
            val closeEnough = dist < when (level) { CpuDifficulty.HARD -> 0.34; CpuDifficulty.MEDIUM -> 0.36; CpuDifficulty.EASY -> 0.5 }
            if (frac >= releaseAt && (closeEnough || frac >= releaseAt + 0.3)) engine.setCharging(seat, false)
        }
        if (engine.timeMs < nextThink) { engine.setInput(seat, ix, iy); return }
        nextThink = engine.timeMs + thinkMs
        val ringR = engine.ringRadius()
        val rivals = engine.sumos.filter { it.alive && it.id != seat }
        if (rivals.isEmpty()) return
        val d0 = sqrt(me.x * me.x + me.y * me.y)

        val margin = when (level) { CpuDifficulty.HARD -> 0.34; CpuDifficulty.MEDIUM -> 0.22; CpuDifficulty.EASY -> 0.10 }
        val careful = level != CpuDifficulty.EASY || random.nextInt(100) < 45
        if (careful && d0 > ringR - margin) {
            // Back to the middle (cancel any charge: it would only carry us further out).
            aim(-me.x, -me.y, 0.0)
            if (me.charging && level != CpuDifficulty.EASY) engine.setCharging(seat, false)
            engine.setInput(seat, ix, iy)
            return
        }
        // Hard: sidestep a rival's dash that is aimed at us.
        if (level == CpuDifficulty.HARD) {
            for (o in rivals) {
                val incoming = (o.dashMs > 0 || (o.charging && o.chargeMs > 280))
                if (!incoming) continue
                val dx = me.x - o.x; val dy = me.y - o.y
                val dd = sqrt(dx * dx + dy * dy)
                if (dd < 0.9 && dd > 1e-6 && (o.facingX * dx + o.facingY * dy) / dd > 0.8) {
                    // Perpendicular, on the side that points toward the middle of the ring.
                    var px = -o.facingY; var py = o.facingX
                    if (px * -me.x + py * -me.y < 0) { px = -px; py = -py }
                    ix = px; iy = py
                    engine.setInput(seat, ix, iy)
                    return
                }
            }
        }
        val target = when (level) {
            CpuDifficulty.HARD -> rivals.maxByOrNull { sqrt(it.x * it.x + it.y * it.y) / ringR - 0.3 * dist(me.x, me.y, it.x, it.y) }!!
            else -> rivals.minByOrNull { dist(me.x, me.y, it.x, it.y) }!!
        }
        var px = target.x; var py = target.y
        if (level == CpuDifficulty.HARD) {
            val tl = sqrt(target.x * target.x + target.y * target.y)
            if (tl > 0.15) {
                // Stand on the centre side of the rival so a push sends them out.
                px = target.x - target.x / tl * 0.2
                py = target.y - target.y / tl * 0.2
            }
        }
        val toTarget = dist(me.x, me.y, target.x, target.y)
        val wob = when (level) { CpuDifficulty.EASY -> 0.6; CpuDifficulty.MEDIUM -> 0.3; CpuDifficulty.HARD -> 0.2 }
        // Close in on the stand-off point; once next to the rival just lean into them.
        if (level == CpuDifficulty.HARD && toTarget > 0.4) aim(px - me.x, py - me.y, wob) else aim(target.x - me.x, target.y - me.y, wob)
        engine.setInput(seat, ix, iy)

        // Dash?
        if (!me.charging && me.cooldownMs == 0) {
            val dx = target.x - me.x; val dy = target.y - me.y
            val dd = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
            val aligned = (me.facingX * dx + me.facingY * dy) / dd
            val range = when (level) { CpuDifficulty.HARD -> 0.6; CpuDifficulty.MEDIUM -> 0.5; CpuDifficulty.EASY -> 0.9 }
            val start = when (level) {
                CpuDifficulty.EASY -> random.nextInt(100) < 10
                CpuDifficulty.MEDIUM -> dd < range && aligned > 0.8
                CpuDifficulty.HARD -> dd < range && aligned > 0.88
            }
            if (start) {
                releaseAt = when (level) { CpuDifficulty.HARD -> 0.55; CpuDifficulty.MEDIUM -> 0.35; CpuDifficulty.EASY -> 0.1 + random.nextDouble() * 0.3 }
                engine.setCharging(seat, true)
            }
        }
    }

    private fun aim(dx: Double, dy: Double, wobbleRad: Double) {
        val l = sqrt(dx * dx + dy * dy)
        if (l < 1e-6) { ix = 0.0; iy = 0.0; return }
        val a = kotlin.math.atan2(dy, dx) + wobble(wobbleRad)
        ix = cos(a); iy = sin(a)
    }

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
}
