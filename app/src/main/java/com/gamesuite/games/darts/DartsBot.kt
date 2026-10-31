package com.gamesuite.games.darts

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** Where a player (or the computer) aims, with the throw [power] and, when known, the intended [target]. */
data class DartsAimPoint(val x: Double, val y: Double, val power: Double = DartsAim.IDEAL_POWER, val target: DartHit? = null)

/** A suggestion for the hint button: the aim point and the route (for a finish) as scoreboard codes. */
data class DartsHint(val aim: DartsAimPoint, val route: List<String>)

/**
 * The computer thrower. All levels throw through the same wobble model as a person; they differ in
 * how steady the hand is ([sigmaFor]) and how well they choose the target:
 *  - Easy: a wild hand, aims for the big numbers and only tries a one-dart finish.
 *  - Medium: a decent hand, plans over the trebles, bull and every double.
 *  - Hard: a tight hand that picks, from every treble, double and single on the board, the target
 *    whose scatter is worth the most (a finish route, a set-up for one, or marks and points).
 * Nothing here touches the main thread's state: it reads an immutable [DartsState].
 */
object DartsBot {
    fun sigmaFor(level: CpuDifficulty): Double = when (level) {
        CpuDifficulty.EASY -> DartsAim.SIGMA_CPU_EASY
        CpuDifficulty.MEDIUM -> DartsAim.SIGMA_CPU_MEDIUM
        CpuDifficulty.HARD -> DartsAim.SIGMA_CPU_HARD
    }

    fun aim(s: DartsState, seat: Int, level: CpuDifficulty, rng: Random): DartsAimPoint = when (level) {
        CpuDifficulty.EASY -> easy(s, seat, rng)
        CpuDifficulty.MEDIUM -> plan(s, seat, sigmaFor(level), full = false)
        CpuDifficulty.HARD -> plan(s, seat, sigmaFor(level), full = true)
    }

    fun hint(s: DartsState, seat: Int, sigma: Double): DartsHint {
        val a = plan(s, seat, sigma, full = true)
        val route = if (s.game == DartsGame.CRICKET) emptyList() else {
            val p = s.players[seat]
            DartsCheckout.route(p.remaining, 3 - s.visit.size, s.doubleOut)?.map { it.code } ?: emptyList()
        }
        return DartsHint(a, route)
    }

    private fun aimAt(h: DartHit): DartsAimPoint {
        val (x, y) = DartsBoard.center(h.ring, h.number)
        return DartsAimPoint(x, y, target = h)
    }

    private fun easy(s: DartsState, seat: Int, rng: Random): DartsAimPoint {
        val p = s.players[seat]
        val left = 3 - s.visit.size
        if (s.game == DartsGame.CRICKET) {
            val i = (0 until Cricket.NUMBERS.size).firstOrNull { p.marks[it] < 3 }
            val n = if (i == null) 20 else Cricket.NUMBERS[i]
            return if (n == 25) aimAt(DartHit(Ring.BULL, 25)) else aimAt(DartHit(Ring.SINGLE, n))
        }
        val one = DartsCheckout.route(p.remaining, 1, s.doubleOut)
        if (one != null && rng.nextInt(10) < 6) return aimAt(one.first())
        if (!p.started) return aimAt(DartHit(Ring.DOUBLE, 20))
        val r = rng.nextInt(100)
        return when {
            p.remaining <= 40 && left >= 1 && s.doubleOut -> aimAt(DartHit(Ring.SINGLE, 20).takeIf { p.remaining > 20 } ?: DartHit(Ring.SINGLE, 5))
            r < 40 -> aimAt(DartHit(Ring.TREBLE, 20))
            r < 70 -> aimAt(DartHit(Ring.SINGLE, 20))
            r < 85 -> aimAt(DartHit(Ring.TREBLE, 19))
            else -> aimAt(DartHit(Ring.BULL, 25))
        }
    }

    private fun candidates(s: DartsState, seat: Int, full: Boolean): List<DartsAimPoint> {
        val out = ArrayList<DartsAimPoint>()
        fun add(h: DartHit) = out.add(aimAt(h))
        if (full) {
            for (n in 1..20) {
                add(DartHit(Ring.TREBLE, n)); add(DartHit(Ring.DOUBLE, n)); add(DartHit(Ring.SINGLE, n))
                val (ox, oy) = DartsBoard.outerSingle(n)
                out.add(DartsAimPoint(ox, oy, target = DartHit(Ring.SINGLE, n)))
            }
        } else {
            for (n in intArrayOf(20, 19, 18, 17, 16, 15)) add(DartHit(Ring.TREBLE, n))
            for (n in 1..20) add(DartHit(Ring.DOUBLE, n))
            add(DartHit(Ring.SINGLE, 20)); add(DartHit(Ring.SINGLE, 19)); add(DartHit(Ring.SINGLE, 18))
        }
        add(DartHit(Ring.BULL, 25))
        if (s.game != DartsGame.CRICKET) {
            val p = s.players[seat]
            DartsCheckout.route(p.remaining, 3 - s.visit.size, s.doubleOut)?.firstOrNull()?.let { add(it) }
        }
        return out
    }

    private fun setup(nr: Int, leftAfter: Int, doubleOut: Boolean): Double {
        if (!doubleOut) return 0.0
        if (nr == 1) return -40.0
        val r = DartsCheckout.route(nr, 3, true) ?: return 0.0
        return (if (leftAfter >= r.size) 45.0 else 20.0) - 6.0 * r.size
    }

    private fun x01Utility(s: DartsState, p: DartsPlayerState, hit: DartHit, left: Int): Double {
        val o = X01.apply(p.remaining, p.started, hit, s.doubleOut, s.doubleIn)
        if (o.finished) return 1000.0
        if (o.bust) return -s.visitScore - 15.0
        return o.counted + setup(o.remaining, left - 1, s.doubleOut) + if (!p.started && o.started) 30.0 else 0.0
    }

    private fun cricketUtility(s: DartsState, seat: Int, hit: DartHit): Double {
        val i = Cricket.indexOf(hit)
        if (i < 0) return 0.0
        val p = s.players[seat]
        val others = s.players.filterIndexed { k, _ -> k != seat }.map { it.marks }
        val (marks, pts) = Cricket.apply(p.marks, others, hit)
        return (marks[i] - p.marks[i]) * (8.0 + Cricket.NUMBERS[i] * 0.5) + pts
    }

    /** Expected-value planner: the candidate aim point whose scatter is worth the most. */
    fun plan(s: DartsState, seat: Int, sigma: Double, full: Boolean): DartsAimPoint {
        val p = s.players[seat]
        val left = (3 - s.visit.size).coerceAtLeast(1)
        var best: DartsAimPoint? = null
        var bestV = Double.NEGATIVE_INFINITY
        for (c in candidates(s, seat, full)) {
            var v = 0.0
            for ((hit, w) in DartsOdds.distribution(c.x, c.y, sigma)) {
                v += w * (if (s.game == DartsGame.CRICKET) cricketUtility(s, seat, hit) else x01Utility(s, p, hit, left))
            }
            if (v > bestV + 1e-9) { bestV = v; best = c }
        }
        return best ?: aimAt(DartHit(Ring.TREBLE, 20))
    }
}
