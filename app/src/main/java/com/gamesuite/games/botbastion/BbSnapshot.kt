package com.gamesuite.games.botbastion

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A saved game in progress. Decoding is strict: anything out of range, inconsistent or unknown makes
 * [decode] return null (the screen then starts fresh), never a half-valid engine.
 */
object BbSnapshot {
    const val VERSION = 1
    private const val MAX_WAVE = 100_000
    private const val MAX_SCRAP = 100_000_000
    private const val MAX_SCORE = 2_000_000_000
    private const val MAX_BOTS = 64
    private const val MAX_ENEMIES = 600
    private const val MAX_TIME = 100_000_000.0

    @Serializable
    data class BotS(
        val id: Int, val type: String, val node: Int, val owner: Int, val placedAt: Double, val level: Int,
        val invested: Int, val priority: String, val cooldown: Double, val x: Double, val y: Double,
        val rimS: Double, val chasing: Boolean
    )

    @Serializable
    data class EnemyS(
        val id: Int, val type: String, val lane: Int, val dist: Double, val hp: Double, val maxHp: Double,
        val speedMult: Double, val swarm: Boolean, val freeze: Double
    )

    @Serializable
    data class PilotS(val pid: Int, val x: Double, val y: Double, val hp: Double, val downLeft: Double, val tx: Double, val ty: Double)

    @Serializable
    data class Data(
        val version: Int = VERSION,
        val mapId: String,
        val tier: String,
        val seed: Long,
        val phase: String,
        val wave: Int,
        val time: Double,
        val buildLeft: Double,
        val coreHp: Int,
        val shield: Int,
        val aegisBought: Int,
        val killScore: Int,
        val clearScore: Int,
        val scrapMult: Int,
        val scrapMultLeft: Double,
        val scoreMult: Int,
        val scoreMultLeft: Double,
        val specials: List<Int>,
        val leaked: Int,
        val nextId: Int,
        val spawnIdx: Int,
        val segment: Int,
        val segTime: Double,
        val scrap: List<Int>,
        val kills: List<Int>,
        val bots: List<BotS>,
        val enemies: List<EnemyS>,
        val pilots: List<PilotS>
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The snapshot of a game in progress, or null once it is over. */
    fun encode(e: BbEngine): String? {
        if (e.over) return null
        val d = Data(
            mapId = e.map.id, tier = e.tier.id, seed = e.seed, phase = e.phase.name, wave = e.wave, time = e.time,
            buildLeft = e.buildLeft, coreHp = e.coreHp, shield = e.shield, aegisBought = e.aegisBought,
            killScore = e.killScore, clearScore = e.clearScore, scrapMult = e.scrapMult, scrapMultLeft = e.scrapMultLeft,
            scoreMult = e.scoreMult, scoreMultLeft = e.scoreMultLeft, specials = e.specials.toList(), leaked = e.leaked,
            nextId = e.nextId, spawnIdx = e.spawnIdx, segment = e.segment, segTime = e.segTime,
            scrap = e.players.map { it.scrap }, kills = e.players.map { it.kills },
            bots = e.bots.map {
                BotS(it.id, it.type.id, it.node, it.owner, it.placedAt, it.level, it.invested, it.priority.name,
                    it.cooldown, it.x, it.y, it.rimS, it.chasing)
            },
            enemies = e.enemies.filter { !it.dead }.map {
                EnemyS(it.id, it.type.id, it.lane, it.dist, it.hp, it.maxHp, it.speedMult, it.swarm, it.freeze)
            },
            pilots = e.pilots.map { PilotS(it.pid, it.x, it.y, it.hp, it.downLeft, it.tx, it.ty) }
        )
        return json.encodeToString(d)
    }

    private fun fin(v: Double, lo: Double, hi: Double) = v.isFinite() && v >= lo && v <= hi

    /** The engine the text describes, or null when it is not a valid snapshot of a game on one of [maps]. */
    fun decode(text: String, maps: List<MapDef>): BbEngine? = try {
        restore(json.decodeFromString<Data>(text), maps)
    } catch (_: Exception) {
        null
    }

    internal fun restore(d: Data, maps: List<MapDef>): BbEngine? {
        if (d.version != VERSION) return null
        val map = maps.firstOrNull { it.id == d.mapId } ?: return null
        val tier = Tier.fromId(d.tier) ?: return null
        val phase = Phase.values().firstOrNull { it.name == d.phase } ?: return null
        if (phase != Phase.BUILD && phase != Phase.WAVE) return null
        val n = d.scrap.size
        if (n !in 1..BbRules.MAX_PLAYERS || d.kills.size != n) return null
        if (d.wave !in 0..MAX_WAVE || (phase == Phase.WAVE && d.wave < 1)) return null
        if (!tier.endless && d.wave > tier.goal) return null
        if (!fin(d.time, 0.0, MAX_TIME) || !fin(d.buildLeft, -1.0, 1000.0) || !fin(d.segTime, 0.0, 1000.0)) return null
        if (d.coreHp !in 1..BbRules.CORE_MAX) return null
        if (d.aegisBought !in 0..BbRules.AEGIS_MAX_BUYS || d.shield !in 0..d.aegisBought * BbRules.SHIELD_PER_AEGIS) return null
        if (d.killScore !in 0..MAX_SCORE || d.clearScore !in 0..MAX_SCORE) return null
        if (d.scrap.any { it !in 0..MAX_SCRAP } || d.kills.any { it < 0 }) return null
        if (d.scrapMult !in listOf(1, 2, 4) || d.scoreMult !in listOf(1, 2, 4)) return null
        if (!fin(d.scrapMultLeft, 0.0, BbRules.MULT_SECONDS) || !fin(d.scoreMultLeft, 0.0, BbRules.MULT_SECONDS)) return null
        if ((d.scrapMult > 1) != (d.scrapMultLeft > 0) || (d.scoreMult > 1) != (d.scoreMultLeft > 0)) return null
        if (d.specials.size != SpecialKind.values().size || d.specials.any { it !in 0..BbRules.SPECIAL_CAP }) return null
        if (d.leaked < 0 || d.nextId !in 1..Int.MAX_VALUE - 1000 || d.segment !in 0..1) return null
        if (d.bots.size > MAX_BOTS || d.enemies.size > MAX_ENEMIES) return null

        val e = BbEngine(map, tier, n, d.seed)
        // the wave being fought (or the swarm after it) regenerates the spawn list; the cursor must fit it
        val spawns = when {
            phase == Phase.BUILD -> emptyList()
            d.segment == 1 -> BbWaves.swarmSpawns(map, tier, n, d.wave)
            else -> BbWaves.mainSpawns(map, tier, n, d.seed, d.wave)
        }
        if (d.segment == 1 && !BbWaves.swarmAfter(tier, d.wave)) return null
        if (d.spawnIdx !in 0..spawns.size) return null
        if (phase == Phase.BUILD && (d.spawnIdx != 0 || d.segment != 0 || d.enemies.isNotEmpty())) return null

        val ids = HashSet<Int>()
        val nodes = HashSet<Int>()
        for (b in d.bots) {
            val type = BotType.fromId(b.type) ?: return null
            val prio = TargetPriority.values().firstOrNull { it.name == b.priority } ?: return null
            if (b.id !in 1 until d.nextId || !ids.add(b.id)) return null
            if (type.needsNode) { if (b.node !in map.nodes.indices || !nodes.add(b.node)) return null } else if (b.node != -1) return null
            if (b.owner !in 0 until n || b.level !in 1..BotStats.MAX_LEVEL) return null
            if (b.invested !in 0..MAX_SCRAP || !fin(b.placedAt, 0.0, MAX_TIME) || !fin(b.cooldown, -1.0, 100.0)) return null
            if (!fin(b.x, -1.0, BbArena.W + 1) || !fin(b.y, -1.0, BbArena.H + 1) || !fin(b.rimS, 0.0, BbRim.perimeter)) return null
            if (type.needsNode && (b.x != map.nodes[b.node].x || b.y != map.nodes[b.node].y)) return null
            val bot = BbBot(b.id, type, b.node, b.owner, b.placedAt)
            bot.level = b.level; bot.invested = b.invested; bot.priority = prio; bot.cooldown = b.cooldown
            bot.x = b.x; bot.y = b.y; bot.rimS = b.rimS; bot.chasing = b.chasing
            e.bots += bot
        }
        if (e.skiffCount() > BbRules.MAX_SKIFFS) return null
        for (en in d.enemies) {
            val type = EnemyType.values().firstOrNull { it.id == en.type } ?: return null
            if (en.id !in 1 until d.nextId || !ids.add(en.id)) return null
            if (en.lane !in map.lanes.indices) return null
            val len = map.lanes[en.lane].length
            if (!fin(en.dist, 0.0, len) || !fin(en.maxHp, 1e-9, 1e12) || !fin(en.hp, 1e-9, en.maxHp)) return null
            if (!fin(en.speedMult, 0.1, 5.0) || !fin(en.freeze, 0.0, 10.0)) return null
            val enemy = BbEnemy(en.id, type, en.lane, en.dist, en.hp, en.maxHp, en.speedMult, en.swarm)
            enemy.freeze = en.freeze
            val p = map.lanes[en.lane].pointAt(en.dist)
            enemy.x = p.x; enemy.y = p.y
            e.enemies += enemy
        }
        if (d.pilots.size != e.pilots.size) return null
        for ((i, ps) in d.pilots.withIndex()) {
            val p = e.pilots[i]
            if (ps.pid != p.pid || !fin(ps.x, 0.0, BbArena.W) || !fin(ps.y, 0.0, BbArena.H)) return null
            if (!fin(ps.tx, 0.0, BbArena.W) || !fin(ps.ty, 0.0, BbArena.H)) return null
            if (!fin(ps.hp, 0.0, BbRules.PILOT_HP) || !fin(ps.downLeft, 0.0, BbRules.PILOT_RESPAWN)) return null
            p.x = ps.x; p.y = ps.y; p.hp = ps.hp; p.downLeft = ps.downLeft; p.tx = ps.tx; p.ty = ps.ty
        }
        for ((i, s) in d.scrap.withIndex()) { e.players[i].scrap = s; e.players[i].kills = d.kills[i] }
        e.phase = phase; e.wave = d.wave; e.time = d.time; e.buildLeft = d.buildLeft; e.coreHp = d.coreHp
        e.shield = d.shield; e.aegisBought = d.aegisBought; e.killScore = d.killScore; e.clearScore = d.clearScore
        e.scrapMult = d.scrapMult; e.scrapMultLeft = d.scrapMultLeft; e.scoreMult = d.scoreMult; e.scoreMultLeft = d.scoreMultLeft
        for (i in d.specials.indices) e.specials[i] = d.specials[i]
        e.leaked = d.leaked; e.nextId = d.nextId; e.spawns = spawns; e.spawnIdx = d.spawnIdx; e.segment = d.segment
        e.segTime = d.segTime
        return e
    }
}
