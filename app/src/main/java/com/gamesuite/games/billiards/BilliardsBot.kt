package com.gamesuite.games.billiards

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The computer player. Every level starts from the same geometry: for each ball it may legally touch and each
 * pocket, the "ghost ball" spot where the cue ball must be at contact to send that ball into that pocket,
 * checked for a clear path. The levels differ in how they use it:
 *  - Easy aims with a wide error (about 3 degrees), sometimes picks a worse shot, and hits with uneven power.
 *  - Medium takes the best-scoring geometric shot with a small error (about 1 degree).
 *  - Hard turns the best candidate shots (with a few powers and spins each) into real simulations, judges
 *    each result with the rules (pots, fouls, keeping the turn) and takes the best. It is meant to run off
 *    the main thread; [chooseShot] is pure and thread-safe.
 */
object BilliardsBot {
    class View(
        val variant: String,
        val x: DoubleArray, val y: DoubleArray, val onTable: BooleanArray,
        val shooter: Int, val groups: List<Int>, val ballInHand: Boolean, val behindHead: Boolean,
        val isBreak: Boolean, val foulStreak: Int
    ) {
        val balls: Set<Int> get() = (1 until BilliardsTable.BALLS).filter { onTable[it] }.toSet()
    }

    private class Candidate(val shot: Shot, val score: Double)

    fun viewOf(s: BilliardsState): View = View(
        s.variant, s.xs(), s.ys(), s.ons(), s.shooter, s.groups, s.ballInHand, s.behindHead, s.isBreak, s.foulStreak[s.shooter]
    )

    /** [exact] skips Hard's tiny human-like error (the hint wants the best line there is). */
    fun chooseShot(view: View, level: CpuDifficulty, random: Random, exact: Boolean = false): Shot {
        val cue = chooseCuePosition(view, level, random)
        val v = View(view.variant, view.x.copyOf().also { it[0] = cue.first }, view.y.copyOf().also { it[0] = cue.second },
            view.onTable, view.shooter, view.groups, view.ballInHand, view.behindHead, view.isBreak, view.foulStreak)
        if (view.isBreak) return breakShot(v, level, random)
        val cands = candidates(v)
        return when (level) {
            CpuDifficulty.HARD -> hardShot(v, cands, random).let { b ->
                if (exact) b else b.copy(angle = b.angle + gauss(random) * 0.12 * PI / 180.0, power = (b.power * (1.0 + gauss(random) * 0.02)).coerceIn(0.05, 1.0))
            }
            CpuDifficulty.MEDIUM -> softShot(v, cands, random, aimSigmaDeg = 1.0, powerNoise = 0.08, sloppy = 0.0)
            CpuDifficulty.EASY -> softShot(v, cands, random, aimSigmaDeg = 2.0, powerNoise = 0.22, sloppy = 0.2)
        }
    }

    // ---- break and fallbacks -----------------------------------------------------------------------

    private fun breakShot(v: View, level: CpuDifficulty, random: Random): Shot {
        val cx = v.x[0]; val cy = v.y[0]
        val apex = v.balls.minByOrNull { (v.x[it] - cx) * (v.x[it] - cx) + (v.y[it] - cy) * (v.y[it] - cy) } ?: 1
        val base = atan2(v.y[apex] - cy, v.x[apex] - cx)
        val sigma = when (level) { CpuDifficulty.HARD -> 0.002; CpuDifficulty.MEDIUM -> 0.012; CpuDifficulty.EASY -> 0.035 }
        val power = when (level) { CpuDifficulty.HARD -> 1.0; CpuDifficulty.MEDIUM -> 0.88; CpuDifficulty.EASY -> 0.62 }
        return Shot(base + gauss(random) * sigma, power, 0.0, 0.0, cx, cy)
    }

    private fun directAt(v: View, target: Int, power: Double, aimSigma: Double, random: Random): Shot {
        val a = atan2(v.y[target] - v.y[0], v.x[target] - v.x[0]) + gauss(random) * aimSigma
        return Shot(a, power.coerceIn(0.15, 1.0), 0.0, 0.0, v.x[0], v.y[0])
    }

    // ---- ball-in-hand ----------------------------------------------------------------------------

    private fun chooseCuePosition(v: View, level: CpuDifficulty, random: Random): Pair<Double, Double> {
        if (v.isBreak && !v.ballInHand) return v.x[0] to v.y[0]
        if (!v.ballInHand) return v.x[0] to v.y[0]
        val default = BilliardsRules.freeSpot(BilliardsTable.HEAD_X, BilliardsTable.MID_Y, v.x, v.y, v.onTable, v.behindHead)
        if (v.isBreak || level == CpuDifficulty.EASY) {
            if (v.isBreak) return BilliardsRules.freeSpot(BilliardsTable.HEAD_X, BilliardsTable.MID_Y, v.x, v.y, v.onTable, true)
            return default
        }
        val targets = BilliardsRules.legalTargets(v.variant, v.groups, v.shooter, v.balls, false)
        var best = default
        var bestScore = -1e18
        val spots = ArrayList<Pair<Double, Double>>()
        spots.add(default)
        for (t in targets) for (k in 0 until 6) {
            val gx = BilliardsTable.POCKET_X[k]; val gy = BilliardsTable.POCKET_Y[k]
            val dx = gx - v.x[t]; val dy = gy - v.y[t]
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1e-6) continue
            for (back in doubleArrayOf(9.0, 16.0)) {
                val sx = v.x[t] - dx / d * back; val sy = v.y[t] - dy / d * back
                if (BilliardsRules.canPlaceCue(sx, sy, v.x, v.y, v.onTable, v.behindHead)) spots.add(sx to sy)
            }
        }
        for (spot in spots) {
            val vv = View(v.variant, v.x.copyOf().also { it[0] = spot.first }, v.y.copyOf().also { it[0] = spot.second },
                v.onTable, v.shooter, v.groups, false, false, false, v.foulStreak)
            val c = candidates(vv).maxByOrNull { it.score }?.score ?: -1000.0
            if (c > bestScore) { bestScore = c; best = spot }
        }
        return best
    }

    // ---- geometry --------------------------------------------------------------------------------

    private fun clearPath(v: View, ax: Double, ay: Double, bx: Double, by: Double, ignoreA: Int, ignoreB: Int, margin: Double): Boolean {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        for (o in 0 until BilliardsTable.BALLS) {
            if (!v.onTable[o] || o == ignoreA || o == ignoreB) continue
            val t = if (len2 < 1e-9) 0.0 else (((v.x[o] - ax) * dx + (v.y[o] - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val qx = ax + dx * t - v.x[o]; val qy = ay + dy * t - v.y[o]
            if (qx * qx + qy * qy < margin * margin) return false
        }
        return true
    }

    private fun candidates(v: View): List<Candidate> {
        val targets = BilliardsRules.legalTargets(v.variant, v.groups, v.shooter, v.balls, false)
        val out = ArrayList<Candidate>()
        val cx = v.x[0]; val cy = v.y[0]
        val d2r = 2 * BilliardsTable.R
        for (t in targets) {
            for (k in 0 until 6) {
                val px = BilliardsTable.POCKET_X[k]; val py = BilliardsTable.POCKET_Y[k]
                val tx = v.x[t]; val ty = v.y[t]
                val ddx = px - tx; val ddy = py - ty
                val dTP = sqrt(ddx * ddx + ddy * ddy)
                if (dTP < 1e-6) continue
                val ux = ddx / dTP; val uy = ddy / dTP
                val gx = tx - ux * d2r; val gy = ty - uy * d2r
                if (gx < BilliardsTable.R || gx > BilliardsTable.W - BilliardsTable.R || gy < BilliardsTable.R || gy > BilliardsTable.H - BilliardsTable.R) continue
                val cgx = gx - cx; val cgy = gy - cy
                val dCG = sqrt(cgx * cgx + cgy * cgy)
                if (dCG < 1e-6) continue
                val cosCut = (cgx * ux + cgy * uy) / dCG
                if (cosCut < 0.25) continue // more than about 75 degrees of cut
                if (!clearPath(v, cx, cy, gx, gy, 0, t, d2r - 0.1)) continue
                if (!clearPath(v, tx, ty, px, py, t, 0, d2r - 0.1)) continue
                val angle = atan2(cgy, cgx)
                val need = dCG + dTP * 1.5 + 8.0
                val speed = sqrt(2 * BilliardsPhysics.FRICTION * need) / (0.55 + 0.45 * cosCut)
                val power = ((speed - BilliardsPhysics.MIN_SPEED) / (BilliardsPhysics.MAX_SPEED - BilliardsPhysics.MIN_SPEED)).coerceIn(0.14, 1.0)
                val score = cosCut * 60.0 - dCG * 0.25 - dTP * 0.5 - (if (k >= 4) 6.0 else 0.0)
                out.add(Candidate(Shot(angle, power, 0.0, 0.0, cx, cy), score))
            }
        }
        out.sortByDescending { it.score }
        return out
    }

    // ---- Easy and Medium -------------------------------------------------------------------------

    private fun softShot(v: View, cands: List<Candidate>, random: Random, aimSigmaDeg: Double, powerNoise: Double, sloppy: Double): Shot {
        val sigma = aimSigmaDeg * PI / 180.0
        val pick: Candidate? = when {
            cands.isEmpty() -> null
            random.nextDouble() < sloppy -> cands[random.nextInt(minOf(cands.size, 4))]
            else -> cands[0]
        }
        if (pick == null) {
            val targets = BilliardsRules.legalTargets(v.variant, v.groups, v.shooter, v.balls, false)
            val t = targets.minByOrNull { (v.x[it] - v.x[0]) * (v.x[it] - v.x[0]) + (v.y[it] - v.y[0]) * (v.y[it] - v.y[0]) } ?: 1
            return directAt(v, t, 0.45 + gauss(random) * powerNoise, sigma, random)
        }
        val power = (pick.shot.power * (1.0 + gauss(random) * powerNoise)).coerceIn(0.12, 1.0)
        return pick.shot.copy(angle = pick.shot.angle + gauss(random) * sigma, power = power)
    }

    // ---- Hard: simulate and judge ------------------------------------------------------------------

    private fun hardShot(v: View, cands: List<Candidate>, random: Random): Shot {
        val trials = ArrayList<Shot>()
        for (c in cands.take(10)) {
            for (pm in doubleArrayOf(1.0, 0.8, 1.25)) for (spin in doubleArrayOf(0.0, -0.5, 0.5)) {
                trials.add(c.shot.copy(power = (c.shot.power * pm).coerceIn(0.1, 1.0), spinY = spin))
            }
        }
        // plain contact shots at each legal ball, so there is always an answer that avoids a foul
        val targets = BilliardsRules.legalTargets(v.variant, v.groups, v.shooter, v.balls, false)
        for (t in targets) for (p in doubleArrayOf(0.3, 0.55, 0.8)) trials.add(directAt(v, t, p, 0.0, random))
        var best: Shot = trials.firstOrNull() ?: Shot(0.0, 0.5, 0.0, 0.0, v.x[0], v.y[0])
        var bestScore = -1e18
        val ctx = RuleContext(v.variant, v.shooter, v.groups, v.balls, false, v.foulStreak)
        for (s in trials) {
            val r = BilliardsPhysics.simulate(v.x, v.y, v.onTable, s)
            val score = evaluate(v, ctx, r)
            if (score > bestScore) { bestScore = score; best = s }
        }
        return best
    }

    /** How good a simulated result is for the shooter (higher is better). */
    fun evaluate(v: View, ctx: RuleContext, r: SimResult): Double {
        val verdict = BilliardsRules.judge(ctx, ShotOutcome.of(r))
        var score = 0.0
        if (verdict.winner == ctx.shooter) return 100_000.0
        if (verdict.winner == 1 - ctx.shooter) return -100_000.0
        score += verdict.pocketed.size * 100.0
        if (verdict.foul) score -= 250.0
        if (0 in r.pocketed) score -= 80.0
        if (verdict.nextShooter == ctx.shooter) score += 30.0
        // leave the cue ball near a remaining ball of ours when the turn continues
        if (verdict.nextShooter == ctx.shooter && r.onTable[0]) {
            val remaining = BilliardsRules.legalTargets(v.variant, v.groups, ctx.shooter, (1 until BilliardsTable.BALLS).filter { r.onTable[it] }.toSet(), false)
            val d = remaining.minOfOrNull { abs(r.x[it] - r.x[0]) + abs(r.y[it] - r.y[0]) } ?: 0.0
            score -= d * 0.1
        }
        return score
    }

    private fun gauss(random: Random): Double {
        // sum of 4 uniforms: close enough to normal, no trig/log so it is cheap and deterministic
        var s = 0.0
        repeat(4) { s += random.nextDouble() - 0.5 }
        return s * sqrt(3.0)
    }

    /** A direction vector, for tests and the screens. */
    fun dir(angle: Double): Pair<Double, Double> = cos(angle) to sin(angle)
}
