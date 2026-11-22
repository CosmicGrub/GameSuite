package com.gamesuite.games.botbastion

import kotlin.math.roundToInt
import kotlin.random.Random

/** One enemy to release [t] seconds after its segment starts. */
data class Spawn(
    val t: Double,
    val type: EnemyType,
    val lane: Int,
    val hpMult: Double,
    val speedMult: Double,
    val swarm: Boolean
)

/** What the next-wave card shows. */
data class WavePreview(val wave: Int, val total: Int, val counts: Map<EnemyType, Int>, val boss: Boolean, val swarmAfter: Boolean)

/**
 * The wave generator (`BALANCE.md` sections 2 and 3). Pure and deterministic: the same (map, tier, players,
 * seed, wave) always yields the same list, so the engine never has to save a random generator.
 */
object BbWaves {
    /** Shares of Drifter, Darter, Plater, Splitter for wave [w]. */
    fun composition(w: Int): DoubleArray = when {
        w <= 2 -> doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        w <= 5 -> doubleArrayOf(0.80, 0.20, 0.0, 0.0)
        w <= 9 -> doubleArrayOf(0.55, 0.20, 0.15, 0.10)
        w <= 19 -> doubleArrayOf(0.40, 0.22, 0.20, 0.18)
        else -> doubleArrayOf(0.30, 0.25, 0.25, 0.20)
    }

    fun hpMult(w: Int, tier: Tier, players: Int): Double =
        BbRules.hpGrowth(w) * tier.hp * BbRules.COOP_HP[(players - 1).coerceIn(0, 3)]

    fun swarmAfter(tier: Tier, w: Int): Boolean = tier.swarmEvery > 0 && w > 0 && w % tier.swarmEvery == 0

    /** How many of each of the four common types the wave has (before the Courier and the boss). */
    fun commonCounts(w: Int): List<Pair<EnemyType, Int>> {
        val n = BbRules.waveCount(w)
        val c = composition(w)
        val darter = (n * c[1]).roundToInt()
        val plater = (n * c[2]).roundToInt()
        val splitter = (n * c[3]).roundToInt()
        return listOf(
            EnemyType.DRIFTER to (n - darter - plater - splitter),
            EnemyType.DARTER to darter, EnemyType.PLATER to plater, EnemyType.SPLITTER to splitter
        )
    }

    fun mainSpawns(map: MapDef, tier: Tier, players: Int, seed: Long, w: Int): List<Spawn> {
        val lanes = map.lanes.size
        val mult = hpMult(w, tier, players)
        val types = ArrayList<EnemyType>()
        for ((t, k) in commonCounts(w)) repeat(k) { types += t }
        val shuffled = types.shuffled(Random(seed * 1_000_003L + w))
        val n = shuffled.size
        val out = ArrayList<Spawn>(n + 2)
        for (i in 0 until n) out += Spawn(i * BbRules.SPAWN_WINDOW / n, shuffled[i], i % lanes, mult, 1.0, false)
        if (BbRules.hasCourier(w)) out += Spawn(BbRules.SPAWN_WINDOW / 2, EnemyType.COURIER, (w / 5) % lanes, mult, 1.0, false)
        if (BbRules.isBossWave(w)) out += Spawn(2.0, EnemyType.BEHEMOTH, (w / 10) % lanes, mult, 1.0, false)
        return out.sortedBy { it.t }
    }

    fun swarmSpawns(map: MapDef, tier: Tier, players: Int, w: Int): List<Spawn> {
        val lanes = map.lanes.size
        val mult = hpMult(w, tier, players)
        return List(BbRules.SWARM_COUNT) {
            Spawn(it * BbRules.SWARM_WINDOW / BbRules.SWARM_COUNT, EnemyType.DARTER, it % lanes, mult, BbRules.SWARM_SPEED, true)
        }
    }

    fun preview(tier: Tier, w: Int): WavePreview {
        val counts = LinkedHashMap<EnemyType, Int>()
        for ((t, k) in commonCounts(w)) if (k > 0) counts[t] = k
        if (BbRules.hasCourier(w)) counts[EnemyType.COURIER] = 1
        if (BbRules.isBossWave(w)) counts[EnemyType.BEHEMOTH] = 1
        return WavePreview(w, counts.values.sum(), counts, BbRules.isBossWave(w), swarmAfter(tier, w))
    }
}
