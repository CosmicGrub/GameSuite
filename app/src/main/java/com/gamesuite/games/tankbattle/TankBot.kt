package com.gamesuite.games.tankbattle

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** A shot a bot (or the hint) proposes. */
data class TankPlan(val angle: Int, val power: Int, val weapon: String)

/**
 * The computer's gunner. It never cheats: it simulates candidate shots with the very same
 * [TankSim] the real shot uses (wind included), scores each by damage dealt minus damage taken,
 * and fires the best one. The levels differ in how many candidates they look at and how steady the
 * hand is:
 *  - Easy looks at a handful of random shots and then misses by up to 12 degrees / 14 power.
 *  - Medium scans a coarse grid and misses by up to 6 degrees / 8 power.
 *  - Hard scans a coarse grid, refines the three best around themselves, and is nearly exact.
 * Planning is pure and can run on any thread.
 */
object TankBot {
    fun weaponsFor(s: TankState, seat: Int): List<String> {
        val t = s.tanks[seat]
        return buildList {
            add(TankWeapons.SHELL)
            if (t.bigLeft > 0) add(TankWeapons.BIG)
            if (t.bouncersLeft > 0) add(TankWeapons.BOUNCER)
        }
    }

    /** How good a shot is: damage dealt to others (plus a bonus per kill), minus a penalty for hurting oneself, and closeness for a miss. */
    fun evaluate(s: TankState, seat: Int, weapon: String, angle: Int, power: Int): Double {
        val terrain = s.terrain.toIntArray()
        val bodies = s.tanks.map { TankBody(it.x, it.hp > 0) }
        val hp = IntArray(s.tanks.size) { s.tanks[it].hp }
        val r = TankSim.simulate(terrain, bodies, hp, seat, weapon, angle, power, s.wind, record = false)
        var value = 0.0
        s.tanks.indices.forEach { k ->
            if (s.tanks[k].hp <= 0) return@forEach
            val d = minOf(r.damage[k], hp[k])
            if (k == seat) value -= 1.6 * d else value += d
        }
        r.killed.forEach { k -> if (k == seat) value -= 60.0 else value += 30.0 }
        if (weapon != TankWeapons.SHELL) value -= 4.0
        val last = r.impacts.lastOrNull()
        var minDist = 1000.0
        if (last != null && last.kind != TankImpact.LOST) {
            s.tanks.indices.forEach { k ->
                if (k == seat || s.tanks[k].hp <= 0) return@forEach
                val d = StrictMath.hypot(s.tanks[k].x - last.x10 / 10.0, s.terrain[s.tanks[k].x] + TankWorld.TANK_RISE - last.y10 / 10.0)
                if (d < minDist) minDist = d
            }
        }
        return 10.0 * value - 0.1 * minDist
    }

    private fun best(s: TankState, seat: Int, weapons: List<String>, angles: IntProgression, powers: IntProgression): Pair<TankPlan, Double> {
        var bestPlan = TankPlan(s.tanks[seat].angle, s.tanks[seat].power, TankWeapons.SHELL)
        var bestScore = Double.NEGATIVE_INFINITY
        for (w in weapons) for (a in angles) for (p in powers) {
            val sc = evaluate(s, seat, w, a, p)
            if (sc > bestScore) { bestScore = sc; bestPlan = TankPlan(a, p, w) }
        }
        return bestPlan to bestScore
    }

    private fun clamp(plan: TankPlan) = plan.copy(
        angle = plan.angle.coerceIn(0, TankWorld.ANGLE_MAX),
        power = plan.power.coerceIn(TankWorld.POWER_MIN, TankWorld.POWER_MAX)
    )

    /** Medium-quality plan without any slip: the suggestion the Hint button shows. */
    fun hint(s: TankState, seat: Int): TankPlan =
        best(s, seat, weaponsFor(s, seat), 6..174 step 8, 20..100 step 8).first.let { coarse ->
            best(s, seat, listOf(coarse.weapon), (coarse.angle - 6)..(coarse.angle + 6) step 2, (coarse.power - 8)..(coarse.power + 8) step 2).first
        }.let(::clamp)

    fun plan(s: TankState, seat: Int, level: CpuDifficulty, rng: Random): TankPlan {
        val weapons = weaponsFor(s, seat)
        return when (level) {
            CpuDifficulty.EASY -> {
                var bestPlan = TankPlan(90, 50, TankWeapons.SHELL)
                var bestScore = Double.NEGATIVE_INFINITY
                repeat(20) {
                    val w = if (rng.nextInt(5) == 0) weapons[rng.nextInt(weapons.size)] else TankWeapons.SHELL
                    val p = TankPlan(rng.nextInt(15, 166), rng.nextInt(25, 101), w)
                    val sc = evaluate(s, seat, p.weapon, p.angle, p.power)
                    if (sc > bestScore) { bestScore = sc; bestPlan = p }
                }
                clamp(bestPlan.copy(angle = bestPlan.angle + rng.nextInt(-14, 15), power = bestPlan.power + rng.nextInt(-16, 17)))
            }
            CpuDifficulty.MEDIUM -> {
                val b = best(s, seat, weapons, 8..172 step 8, 20..100 step 8).first
                clamp(b.copy(angle = b.angle + rng.nextInt(-6, 7), power = b.power + rng.nextInt(-8, 9)))
            }
            CpuDifficulty.HARD -> {
                val coarse = ArrayList<Pair<TankPlan, Double>>()
                for (w in weapons) for (a in 4..176 step 6) for (p in 20..100 step 6) coarse += TankPlan(a, p, w) to evaluate(s, seat, w, a, p)
                coarse.sortByDescending { it.second }
                var bestPlan = coarse.first().first
                var bestScore = coarse.first().second
                for ((c, _) in coarse.take(3)) {
                    val (refined, sc) = best(s, seat, listOf(c.weapon), (c.angle - 5)..(c.angle + 5), (c.power - 6)..(c.power + 6))
                    if (sc > bestScore) { bestScore = sc; bestPlan = refined }
                }
                clamp(bestPlan.copy(angle = bestPlan.angle + rng.nextInt(-1, 2)))
            }
        }
    }
}
