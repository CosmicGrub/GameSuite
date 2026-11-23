package com.gamesuite.games.curling

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.abs
import kotlin.random.Random

/**
 * The computer's deliveries. It builds candidate calls the way a skip would: draws to spots in and around the house
 * (swept or not, both handles), guards in front of it, and takeouts of each stone it would like to remove, using the
 * physics itself to find the line and weight for each.
 *  - EASY calls one draw somewhere near the house and misses its line and weight by a visible margin; no simulation.
 *  - MEDIUM simulates twelve random candidates, plays the best, and is slightly off.
 *  - HARD simulates every candidate and plays the best result, exactly.
 */
object CurlingBot {
    class Candidate(val throwing: CurlingThrow)

    fun choose(state: CurlingState, level: CpuDifficulty, random: Random): CurlingThrow = when (level) {
        CpuDifficulty.EASY -> {
            val tx = random.nextInt(-70, 71).toDouble()
            val ty = CurlingSheet.TEE_Y + random.nextInt(-90, 41)
            val curl = if (random.nextBoolean()) 1 else -1
            val base = CurlingPhysics.solveDraw(tx, ty, curl) ?: fallback(random)
            wobble(base, random, aimTenths = 380, weightFrac = 0.10)
        }
        CpuDifficulty.MEDIUM -> {
            val pool = candidates(state, takeouts = true).shuffled(random).take(12)
            wobble(best(state, pool.ifEmpty { listOf(fallback(random)) }, random), random, aimTenths = 70, weightFrac = 0.025)
        }
        CpuDifficulty.HARD -> best(state, candidates(state, takeouts = true).ifEmpty { listOf(fallback(random)) }, random)
    }

    /** The best delivery found and its value (for hints). */
    fun search(state: CurlingState, random: Random): Pair<CurlingThrow, Double>? {
        val c = candidates(state, takeouts = true)
        if (c.isEmpty()) return null
        val team = state.teamToThrow
        var bt = c[0]
        var bv = -1e18
        for (t in c) {
            val v = value(state, t, team) + random.nextDouble() * 0.001
            if (v > bv) { bv = v; bt = t }
        }
        return bt to bv
    }

    fun value(state: CurlingState, t: CurlingThrow, team: Int): Double {
        val id = team * 8 + state.thrown[team]
        val sim = CurlingPhysics.simulate(state.stones, team, id, t)
        // each remaining delivery matters a little less than the stones already on the ice, so rate the position as it stands
        return CurlingRules.evaluate(sim.stones, team)
    }

    private fun best(state: CurlingState, pool: List<CurlingThrow>, random: Random): CurlingThrow {
        val team = state.teamToThrow
        var bt = pool[0]
        var bv = -1e18
        for (t in pool) {
            val v = value(state, t, team) + random.nextDouble() * 0.001
            if (v > bv) { bv = v; bt = t }
        }
        return bt
    }

    private fun fallback(random: Random) = CurlingThrow(random.nextInt(-300, 301), 380 + random.nextInt(-20, 21), if (random.nextBoolean()) 1 else -1)

    private fun wobble(t: CurlingThrow, random: Random, aimTenths: Int, weightFrac: Double): CurlingThrow = CurlingThrow(
        (t.aim + random.nextInt(-aimTenths, aimTenths + 1)).coerceIn(-2200, 2200),
        (t.weight * (1 + (random.nextDouble() * 2 - 1) * weightFrac)).toInt().coerceIn(0, 1000),
        t.curl, t.sweeps
    )

    fun candidates(state: CurlingState, takeouts: Boolean): List<CurlingThrow> {
        val out = ArrayList<CurlingThrow>()
        val team = state.teamToThrow
        val sweepOptions = listOf(emptyList<Int>(), CurlingSheet.fullSweep)
        val xs = intArrayOf(-120, -80, -40, -15, 0, 15, 40, 80, 120)
        val ys = doubleArrayOf(CurlingSheet.TEE_Y + 5, CurlingSheet.TEE_Y - 90, CurlingSheet.TEE_Y - 190)
        for (curl in intArrayOf(-1, 1)) for (sw in sweepOptions) {
            for (y in ys) for (x in xs) CurlingPhysics.solveDraw(x.toDouble(), y, curl, sw)?.let { out += it }
            for (x in intArrayOf(-50, 0, 50)) for (y in doubleArrayOf(1160.0, 1240.0)) CurlingPhysics.solveDraw(x.toDouble(), y, curl, sw)?.let { out += it }
        }
        if (takeouts) {
            val targets = state.stones.filter { it.team != team && it.y > CurlingSheet.HOG_FAR + 40 }
            for (st in targets) for (curl in intArrayOf(-1, 1)) for (w in intArrayOf(620, 780)) {
                CurlingPhysics.solveLine(st.x, st.y, w, curl)?.let { out += it }
            }
        }
        return out.filter { CurlingSheet.inRange(it) }.distinct()
    }

    /** Lets tests and hints see how far apart two throws are. */
    fun distance(a: CurlingThrow, b: CurlingThrow) = abs(a.aim - b.aim) + abs(a.weight - b.weight)
}
