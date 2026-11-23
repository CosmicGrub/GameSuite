package com.gamesuite.games.towerdefence

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A saved Tower Defence run, taken in the build phase of an open map (between waves, so no enemy,
 * projectile or cooldown has to be stored). Decoding is strict: anything out of range, inconsistent
 * with the rules, or that would leave a spawn without a route makes [decode] return null; the caller
 * then starts fresh and never gets a half-valid game. `invested` must equal the price of the build
 * and upgrades, so a hand-edited file cannot mint refunds.
 */
object TdSnapshot {
    const val VERSION = 1
    private const val MAX_TOWERS = 80
    private const val MAX_GOLD = 1_000_000
    const val MAX_ENDLESS_WAVE = 999

    @Serializable
    data class TowerS(
        val role: String, val branch: String, val target: String, val level: Int,
        val cx: Int, val cy: Int, val invested: Int
    )

    @Serializable
    data class Data(
        val version: Int = VERSION,
        val mapId: String,
        val difficulty: String,
        val gentle: Boolean,
        val wave: Int,
        val castle: Int,
        val castleMax: Int,
        val gold: Int,
        val towers: List<TowerS>,
        /** [TdMode] name; saves from before modes existed decode as CAMPAIGN. */
        val mode: String = "CAMPAIGN",
        /** Daily only: the UTC day number (0 otherwise). */
        val day: Long = 0L
    )

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(d: Data): String = json.encodeToString(d)

    /** The price a tower of this role, level and branch has cost in total (build plus every upgrade). */
    fun expectedInvested(role: TdRole, level: Int): Int {
        var sum = TdRules.baseCost(role)
        for (l in 1 until level) sum += TdRules.upgradeCost(role, l)
        return sum
    }

    fun decode(raw: String?): Data? {
        if (raw.isNullOrBlank() || raw.length > 200_000) return null
        val d = runCatching { json.decodeFromString<Data>(raw) }.getOrNull() ?: return null
        return if (validate(d)) d else null
    }

    fun validate(d: Data): Boolean {
        if (d.version != VERSION) return false
        val terrain = TdMaps.byId(d.mapId) ?: return false
        if (CpuDifficulty.entries.none { it.name == d.difficulty }) return false
        val mode = TdMode.entries.firstOrNull { it.name == d.mode } ?: return false
        val maxWave = if (mode == TdMode.ENDLESS) MAX_ENDLESS_WAVE else TowerDefenceGame.TOTAL_WAVES
        if (d.wave !in 1..maxWave) return false
        if (mode == TdMode.DAILY) {
            // A Daily save is only valid for the map, tier and castle that day's plan dictates.
            if (d.day <= 0L) return false
            if (TdDaily.plan(d.day, 1).mapId != d.mapId || d.gentle || d.difficulty != "MEDIUM") return false
        } else if (d.day != 0L) return false
        val expectedMax = (TowerDefenceGame.STARTING_LIVES * (if (d.gentle) TowerDefenceGame.GENTLE.castleMultiplier else 1f)).toInt()
        if (d.castleMax != expectedMax || d.castle !in 1..d.castleMax) return false
        if (d.gold !in 0..MAX_GOLD) return false
        if (d.towers.size > MAX_TOWERS) return false
        val occ = BooleanArray(TdGrid.CELLS)
        for (t in d.towers) {
            val role = TdRole.entries.firstOrNull { it.name == t.role } ?: return false
            val branch = TdBranch.entries.firstOrNull { it.name == t.branch } ?: return false
            if (TdTarget.entries.none { it.name == t.target }) return false
            if (t.level !in 1..TdRules.MAX_LEVEL) return false
            if ((t.level <= TdRules.BRANCH_LEVEL) != (branch == TdBranch.NONE)) return false
            if (t.invested != expectedInvested(role, t.level)) return false
            val fp = TdGrid.footprint(t.cx, t.cy) ?: return false
            for (c in fp) {
                if (!terrain.buildable[c] || occ[c]) return false
                occ[c] = true
            }
        }
        val flow = TdFlowField.compute(terrain, occ)
        for ((i, s) in terrain.spawns.withIndex()) {
            val dist = flow.dist[s]
            if (!dist.isFinite()) return false
            if (terrain.routeCap.isFinite() && dist > terrain.baseline[i] * terrain.routeCap) return false
        }
        return true
    }
}
