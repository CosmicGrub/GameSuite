package com.gamesuite.games.toysoccer

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A flick the computer has decided on: figure [figure] (local index in its team) pulled by ([pullX], [pullY]). */
data class SoccerFlickPlan(val figure: Int, val pullX: Double, val pullY: Double)

/**
 * How a computer level plays. [reactionTicks] is the wait between noticing a chance and flicking
 * (and the least gap between two flicks of one seat); [aimErrDeg] and [powerErr] blur the aim;
 * [smartness] is the chance of planning a shot instead of poking at the ball; [defendSkill] the
 * chance of reacting to a ball heading for its goal.
 */
data class SoccerCpuProfile(
    val reactionTicks: Int,
    val jitter: Double,
    val aimErrDeg: Double,
    val powerErr: Double,
    val smartness: Double,
    val defendSkill: Double,
    val hitSpeed: Double,
    val leadBall: Boolean
)

object SoccerCpu {
    fun profile(level: CpuDifficulty): SoccerCpuProfile = when (level) {
        CpuDifficulty.EASY -> SoccerCpuProfile(130, 0.30, 11.0, 0.32, 0.30, 0.10, 650.0, false)
        CpuDifficulty.MEDIUM -> SoccerCpuProfile(70, 0.20, 4.5, 0.15, 0.75, 0.55, 900.0, false)
        CpuDifficulty.HARD -> SoccerCpuProfile(30, 0.15, 1.2, 0.05, 1.0, 0.95, 1150.0, true)
    }

    /** The profile of a hint: perfect play, no blur. */
    val PERFECT = SoccerCpuProfile(0, 0.0, 0.0, 0.0, 1.0, 1.0, 1100.0, true)

    private fun gauss(r: Random): Double = (r.nextDouble() + r.nextDouble() + r.nextDouble() - 1.5) * 2.0

    /** Folds an x position into the pitch the way a ball bouncing off the side walls would. */
    private fun fold(x: Double): Double {
        val lo = SoccerRules.BALL_R; val hi = SoccerRules.W - SoccerRules.BALL_R
        val span = hi - lo
        var t = (x - lo) % (2 * span)
        if (t < 0) t += 2 * span
        return lo + if (t <= span) t else 2 * span - t
    }

    private fun plan(
        w: SoccerWorld, idx: Int, ux: Double, uy: Double, dist: Double, hitSpeed: Double,
        p: SoccerCpuProfile, rnd: Random
    ): SoccerFlickPlan {
        val k = SoccerRules.FIG_DAMP
        val avg = maxOf(250.0, hitSpeed + k * dist * 0.5)
        var v0 = hitSpeed + k * dist + SoccerRules.FIG_COULOMB * (dist / avg)
        var ang = atan2(uy, ux)
        if (p.aimErrDeg > 0) ang += gauss(rnd) * p.aimErrDeg * PI / 180.0
        if (p.powerErr > 0) v0 *= 1.0 + gauss(rnd) * p.powerErr
        v0 = v0.coerceIn(300.0, SoccerRules.MAX_FLICK)
        val pull = v0 / SoccerRules.FLICK_GAIN
        return SoccerFlickPlan(w.figure(idx), cos(ang) * pull, sin(ang) * pull)
    }

    private fun shotScore(bx: Double, by: Double, nx: Double, ny: Double, team: Int): Double {
        val sign = if (team == 0) -1.0 else 1.0
        val attackY = if (team == 0) 0.0 else SoccerRules.L
        val ownY = SoccerRules.L - attackY
        val forward = ny * sign
        var score = forward * 40.0
        val c = SoccerRules.CX
        val gh = SoccerRules.GOAL_HALF - 45.0
        if (forward > 0.05) {
            val t = (attackY - by) / ny
            val xg = bx + nx * t
            score += if (abs(xg - c) < gh) 100.0 - abs(xg - c) * 0.3
            else if (abs(fold(xg) - c) < gh) 55.0 else 0.0
        } else if (forward < -0.05) {
            val t = (ownY - by) / ny
            val xg = bx + nx * t
            score -= if (abs(xg - c) < SoccerRules.GOAL_HALF + 25.0) 300.0 else 20.0
        }
        return score
    }

    /**
     * What seat-figures [figs] of [team] should do now, or null when none is ready. Cheap (a few
     * hundred distance checks), so it runs inline on the game clock rather than on another thread.
     */
    fun decide(w: SoccerWorld, team: Int, figs: List<Int>, p: SoccerCpuProfile, rnd: Random): SoccerFlickPlan? {
        val ready = figs.filter { w.isReady(w.index(team, it)) }
        if (ready.isEmpty()) return null
        val ownY = if (team == 0) SoccerRules.L else 0.0
        val c = SoccerRules.CX

        var bx = w.x[0]; var by = w.y[0]
        if (p.leadBall) {
            val lead = 0.18
            bx = fold(bx + w.vx[0] * lead); by += w.vy[0] * lead
        }

        // Defence: the ball is coming at our goal.
        val bvy = w.vy[0]
        if ((ownY - w.y[0]) * bvy > 0 && abs(bvy) > 160.0 && rnd.nextDouble() < p.defendSkill) {
            val t = (ownY - w.y[0]) / bvy
            val xc = fold(w.x[0] + w.vx[0] * t)
            if (t < 2.4 && abs(xc - c) < SoccerRules.GOAL_HALF + 45.0) {
                val ty = if (team == 0) SoccerRules.L - 78.0 else 78.0
                val tx = xc.coerceIn(c - SoccerRules.GOAL_HALF + 25, c + SoccerRules.GOAL_HALF - 25)
                var best = -1; var bd = Double.MAX_VALUE
                for (f in ready) {
                    val i = w.index(team, f)
                    val d = hypot(tx - w.x[i], ty - w.y[i])
                    val adj = if (w.isKeeper(i)) d * 0.4 else d
                    if (adj < bd) { bd = adj; best = i }
                }
                val i = best
                val dx = tx - w.x[i]; val dy = ty - w.y[i]
                val d = hypot(dx, dy)
                if (d > 8.0) return plan(w, i, dx / d, dy / d, d, 160.0, p, rnd)
            }
        }

        // A poke: the nearest ready figure heads for the ball.
        if (rnd.nextDouble() >= p.smartness) {
            val f = ready[rnd.nextInt(ready.size)]
            val i = w.index(team, f)
            val dx = bx - w.x[i]; val dy = by - w.y[i]
            val d = hypot(dx, dy).coerceAtLeast(1.0)
            return plan(w, i, dx / d, dy / d, d, p.hitSpeed * (0.5 + 0.5 * rnd.nextDouble()), p, rnd)
        }

        var bestScore = -1e9
        var bestIdx = -1; var bestUx = 0.0; var bestUy = 0.0; var bestS = 0.0
        val ownGoalDist = { i: Int -> hypot(w.x[i] - c, w.y[i] - ownY) }
        for (f in ready) {
            val i = w.index(team, f)
            if (w.isKeeper(i) && hypot(bx - c, by - ownY) > 420.0) continue
            val fx = w.x[i]; val fy = w.y[i]
            val wx = bx - fx; val wy = by - fy
            val dist = hypot(wx, wy)
            if (dist < 1.0) continue
            val rr = w.radius(i) + SoccerRules.BALL_R
            val base = atan2(wy, wx)
            for (k in -22..22) {
                val ang = base + k * (2.0 * PI / 180.0)
                val ux = cos(ang); val uy = sin(ang)
                val proj = wx * ux + wy * uy
                if (proj <= 0) continue
                val h2 = dist * dist - proj * proj
                if (h2 >= rr * rr * 0.98) continue
                val s = maxOf(0.0, proj - sqrt(rr * rr - h2))
                val cx = fx + ux * s; val cy = fy + uy * s
                var nx = bx - cx; var ny = by - cy
                val nd = hypot(nx, ny)
                if (nd < 1e-6) continue
                nx /= nd; ny /= nd
                var score = shotScore(bx, by, nx, ny, team) - s * 0.02
                if (w.isKeeper(i)) score -= 25.0
                score -= ownGoalDist(i) * 0.0
                if (score > bestScore) { bestScore = score; bestIdx = i; bestUx = ux; bestUy = uy; bestS = s }
            }
        }
        if (bestIdx >= 0 && bestScore > -150.0) {
            return plan(w, bestIdx, bestUx, bestUy, bestS, p.hitSpeed, p, rnd)
        }
        // Nothing useful: get a figure between the ball and our goal.
        var f0 = ready[0]; var fd = Double.MAX_VALUE
        for (f in ready) {
            val i = w.index(team, f)
            val d = hypot(bx - w.x[i], by - w.y[i])
            if (d < fd) { fd = d; f0 = f }
        }
        val i = w.index(team, f0)
        val dirY = if (team == 0) 1.0 else -1.0
        val tx = bx; val ty = by + dirY * 120.0
        val dx = tx - w.x[i]; val dy = ty - w.y[i]
        val d = hypot(dx, dy).coerceAtLeast(1.0)
        return plan(w, i, dx / d, dy / d, d, 250.0, p, rnd)
    }
}
