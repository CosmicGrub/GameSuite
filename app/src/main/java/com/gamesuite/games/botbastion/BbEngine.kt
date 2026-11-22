package com.gamesuite.games.botbastion

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Phase { BUILD, WAVE, WON, LOST }

enum class BbResult { OK, NO_SCRAP, TAKEN, BAD_ARGUMENT, MAX_LEVEL, NOT_OWNER, OVER, CAP, NOTHING_TO_DO }

class BbEnemy(
    val id: Int, val type: EnemyType, val lane: Int, var dist: Double, var hp: Double, val maxHp: Double,
    val speedMult: Double, val swarm: Boolean
) {
    var x = 0.0
    var y = 0.0
    var freeze = 0.0
    var slow = 0.0
    var dead = false
    val pt: Pt get() = Pt(x, y)
}

class BbBot(val id: Int, val type: BotType, val node: Int, val owner: Int, val placedAt: Double) {
    var level = 1
    var invested = 0
    var priority = if (type == BotType.SKIFF) TargetPriority.CLOSEST else TargetPriority.FIRST
    var cooldown = 0.0
    var x = 0.0
    var y = 0.0
    var rimS = 0.0
    var chasing = false
}

class BbPilot(val pid: Int) {
    var x = 0.0
    var y = 0.0
    var hp = BbRules.PILOT_HP
    var downLeft = 0.0
    var tx = 0.0
    var ty = 0.0
    var cooldown = 0.0
    val down: Boolean get() = downLeft > 0.0
}

class BbPlayer(val index: Int) {
    var scrap = 0
    var kills = 0
}

sealed class BbEvent {
    abstract val t: Double
    data class Placed(override val t: Double, val botId: Int, val type: BotType, val x: Double, val y: Double, val owner: Int) : BbEvent()
    data class Upgraded(override val t: Double, val botId: Int, val level: Int) : BbEvent()
    data class Sold(override val t: Double, val botId: Int, val refund: Int) : BbEvent()
    data class Shot(override val t: Double, val type: BotType, val fx: Double, val fy: Double, val tx: Double, val ty: Double) : BbEvent()
    data class Blast(override val t: Double, val x: Double, val y: Double, val r: Double) : BbEvent()
    data class Freeze(override val t: Double, val x: Double, val y: Double) : BbEvent()
    data class Kill(override val t: Double, val type: EnemyType, val x: Double, val y: Double, val scrap: Int, val points: Int, val pid: Int) : BbEvent()
    data class Leak(override val t: Double, val type: EnemyType, val damage: Int, val absorbed: Int) : BbEvent()
    data class CourierEscaped(override val t: Double) : BbEvent()
    data class WaveStart(override val t: Double, val wave: Int, val count: Int, val boss: Boolean) : BbEvent()
    data class SwarmStart(override val t: Double, val wave: Int) : BbEvent()
    data class WaveClear(override val t: Double, val wave: Int, val scrapBonus: Int, val scoreBonus: Int) : BbEvent()
    data class SpecialGained(override val t: Double, val kind: SpecialKind, val kept: Boolean) : BbEvent()
    data class SpecialUsed(override val t: Double, val kind: SpecialKind) : BbEvent()
    data class AegisBought(override val t: Double, val shield: Int) : BbEvent()
    data class PilotDown(override val t: Double, val pid: Int) : BbEvent()
    data class PilotBack(override val t: Double, val pid: Int) : BbEvent()
    data class Ended(override val t: Double, val won: Boolean) : BbEvent()
}

/** What the result card and the leaderboard need. */
data class BbOutcome(val won: Boolean, val wave: Int, val score: Int, val coreLeft: Int, val timeSeconds: Int, val stars: Int, val players: Int)

/**
 * The Bot Bastion simulation. Pure Kotlin, fixed step, no clock and no random generator state (every random
 * choice derives from [seed] and the wave number), so the same inputs always give the same game.
 *
 * Players are indexes 0 until [playerCount]; player 0 is the touch player, players 1 and up also fly a pilot
 * skiff ([setPilotTarget]). The Core, the waves, the specials and the score are shared; Scrap is per player.
 */
class BbEngine(val map: MapDef, val tier: Tier, val playerCount: Int, val seed: Long) {
    companion object {
        const val STEP = 1.0 / 30.0
        const val MAX_EVENTS = 4000
        private const val MAX_STEPS_PER_ADVANCE = 600
    }

    val players: List<BbPlayer> = List(playerCount.coerceIn(1, BbRules.MAX_PLAYERS)) { BbPlayer(it) }
    val bots = ArrayList<BbBot>()
    val enemies = ArrayList<BbEnemy>()
    val pilots = ArrayList<BbPilot>()
    private val events = ArrayList<BbEvent>()

    var phase = Phase.BUILD; internal set
    var wave = 0; internal set
    var time = 0.0; internal set
    var buildLeft = BbRules.FIRST_BUILD_SECONDS; internal set
    var coreHp = BbRules.CORE_MAX; internal set
    var shield = 0; internal set
    var aegisBought = 0; internal set
    var killScore = 0; internal set
    var clearScore = 0; internal set
    var winBonus = 0; internal set
    var scrapMult = 1; internal set
    var scrapMultLeft = 0.0; internal set
    var scoreMult = 1; internal set
    var scoreMultLeft = 0.0; internal set
    val specials = IntArray(SpecialKind.values().size)
    var leaked = 0; internal set
    var nextId = 1; internal set

    internal var spawns: List<Spawn> = emptyList()
    internal var spawnIdx = 0
    internal var segment = 0
    internal var segTime = 0.0
    private var accum = 0.0

    val score: Int get() = killScore + clearScore + winBonus
    val goalWave: Int get() = tier.goal
    val lastWaveDone: Boolean get() = phase == Phase.WON || phase == Phase.LOST
    val over: Boolean get() = lastWaveDone
    val nextWave: Int get() = wave + 1
    val maxCore: Int get() = BbRules.CORE_MAX

    init {
        for (p in players) p.scrap = tier.startScrap
        for (i in 1 until players.size) {
            val s = BbRim.at(BbRim.perimeter * i / BbRules.MAX_PLAYERS)
            pilots += BbPilot(i).also { it.x = s.x; it.y = s.y; it.tx = s.x; it.ty = s.y }
        }
    }

    // ---- events ------------------------------------------------------------------------------------

    private fun emit(e: BbEvent) {
        if (events.size >= MAX_EVENTS) events.removeAt(0)
        events += e
    }

    /** The events since the last call, oldest first. */
    fun drainEvents(): List<BbEvent> {
        if (events.isEmpty()) return emptyList()
        val out = ArrayList(events)
        events.clear()
        return out
    }

    // ---- queries -----------------------------------------------------------------------------------

    fun botAtNode(node: Int): BbBot? = bots.firstOrNull { it.node == node }
    fun bot(id: Int): BbBot? = bots.firstOrNull { it.id == id }
    fun skiffCount(owner: Int? = null): Int = bots.count { it.type == BotType.SKIFF && (owner == null || it.owner == owner) }

    fun price(type: BotType): Int = BbRules.priced(type.cost, tier)
    fun upgradePrice(b: BbBot): Int? = BotStats.upgradeCost(b.type, b.level)?.let { BbRules.priced(it, tier) }
    fun aegisPrice(): Int = BbRules.aegisPrice(aegisBought, tier)
    fun sellValue(b: BbBot): Int = BotStats.refund(b.invested, time - b.placedAt)
    fun preview(): WavePreview = BbWaves.preview(tier, nextWave)

    fun outcome(): BbOutcome {
        val won = phase == Phase.WON
        val coreLeft = if (won) coreHp else coreHp.coerceAtLeast(0)
        return BbOutcome(won, wave, score, coreLeft, time.toInt(), BbScoring.stars(won, tier, coreLeft), players.size)
    }

    private fun valid(pid: Int) = pid in players.indices
    private fun playing() = phase == Phase.BUILD || phase == Phase.WAVE

    // ---- player actions ----------------------------------------------------------------------------

    fun place(pid: Int, type: BotType, node: Int): BbResult {
        if (!valid(pid)) return BbResult.BAD_ARGUMENT
        if (!playing()) return BbResult.OVER
        if (!type.needsNode || node !in map.nodes.indices) return BbResult.BAD_ARGUMENT
        if (botAtNode(node) != null) return BbResult.TAKEN
        val cost = price(type)
        val p = players[pid]
        if (p.scrap < cost) return BbResult.NO_SCRAP
        p.scrap -= cost
        val b = BbBot(nextId++, type, node, pid, time)
        b.invested = cost
        b.x = map.nodes[node].x; b.y = map.nodes[node].y
        bots += b
        emit(BbEvent.Placed(time, b.id, type, b.x, b.y, pid))
        return BbResult.OK
    }

    fun buySkiff(pid: Int): BbResult {
        if (!valid(pid)) return BbResult.BAD_ARGUMENT
        if (!playing()) return BbResult.OVER
        if (skiffCount() >= BbRules.MAX_SKIFFS || skiffCount(pid) >= BbRules.SKIFF_CAP_PER_PLAYER) return BbResult.CAP
        val cost = price(BotType.SKIFF)
        val p = players[pid]
        if (p.scrap < cost) return BbResult.NO_SCRAP
        p.scrap -= cost
        val b = BbBot(nextId++, BotType.SKIFF, -1, pid, time)
        b.invested = cost
        b.rimS = (skiffCount() * BbRim.perimeter / BbRules.MAX_SKIFFS)
        val at = BbRim.at(b.rimS)
        b.x = at.x; b.y = at.y
        bots += b
        emit(BbEvent.Placed(time, b.id, BotType.SKIFF, b.x, b.y, pid))
        return BbResult.OK
    }

    /** Anyone may upgrade any bot; a non-owner pays the price and the owner gets a 10% rebate. */
    fun upgrade(pid: Int, botId: Int): BbResult {
        if (!valid(pid)) return BbResult.BAD_ARGUMENT
        if (!playing()) return BbResult.OVER
        val b = bot(botId) ?: return BbResult.BAD_ARGUMENT
        val cost = upgradePrice(b) ?: return BbResult.MAX_LEVEL
        val p = players[pid]
        if (p.scrap < cost) return BbResult.NO_SCRAP
        p.scrap -= cost
        if (b.owner != pid) players[b.owner].scrap += cost * BbRules.UPGRADE_REBATE_PERCENT / 100
        b.level++
        b.invested += cost
        emit(BbEvent.Upgraded(time, b.id, b.level))
        return BbResult.OK
    }

    fun sell(pid: Int, botId: Int): BbResult {
        if (!valid(pid)) return BbResult.BAD_ARGUMENT
        if (!playing()) return BbResult.OVER
        val b = bot(botId) ?: return BbResult.BAD_ARGUMENT
        if (b.owner != pid) return BbResult.NOT_OWNER
        val refund = sellValue(b)
        players[pid].scrap += refund
        bots.remove(b)
        emit(BbEvent.Sold(time, b.id, refund))
        return BbResult.OK
    }

    fun setPriority(botId: Int, p: TargetPriority): BbResult {
        val b = bot(botId) ?: return BbResult.BAD_ARGUMENT
        b.priority = p
        return BbResult.OK
    }

    fun buyAegis(pid: Int): BbResult {
        if (!valid(pid)) return BbResult.BAD_ARGUMENT
        if (!playing()) return BbResult.OVER
        if (aegisBought >= BbRules.AEGIS_MAX_BUYS) return BbResult.CAP
        val cost = aegisPrice()
        val p = players[pid]
        if (p.scrap < cost) return BbResult.NO_SCRAP
        p.scrap -= cost
        aegisBought++
        shield += BbRules.SHIELD_PER_AEGIS
        emit(BbEvent.AegisBought(time, shield))
        return BbResult.OK
    }

    fun gift(from: Int, to: Int, amount: Int): BbResult {
        if (!valid(from) || !valid(to) || from == to || amount < BbRules.MIN_GIFT) return BbResult.BAD_ARGUMENT
        if (players[from].scrap < amount) return BbResult.NO_SCRAP
        players[from].scrap -= amount
        players[to].scrap += amount
        return BbResult.OK
    }

    /** Starts the next wave now; adds the call-early bonus to [pid] when there was build time left to skip. */
    fun callEarly(pid: Int): BbResult {
        if (!valid(pid)) return BbResult.BAD_ARGUMENT
        if (phase != Phase.BUILD) return BbResult.NOTHING_TO_DO
        if (buildLeft > 1.0) players[pid].scrap += (BbRules.CALL_EARLY_BONUS * tier.cash).roundToInt()
        startWave()
        return BbResult.OK
    }

    fun useSpecial(pid: Int, kind: SpecialKind): BbResult {
        if (!valid(pid)) return BbResult.BAD_ARGUMENT
        if (!playing()) return BbResult.OVER
        val i = kind.ordinal
        if (specials[i] <= 0) return BbResult.NOTHING_TO_DO
        when (kind) {
            SpecialKind.SCRAP_SURGE -> {
                if (scrapMult >= BbRules.MULT_CAP) return BbResult.CAP
                scrapMult = min(BbRules.MULT_CAP, scrapMult * 2); scrapMultLeft = BbRules.MULT_SECONDS
            }
            SpecialKind.SCORE_BOOST -> {
                if (scoreMult >= BbRules.MULT_CAP) return BbResult.CAP
                scoreMult = min(BbRules.MULT_CAP, scoreMult * 2); scoreMultLeft = BbRules.MULT_SECONDS
            }
            SpecialKind.REPAIR_KIT -> {
                if (coreHp >= BbRules.CORE_MAX) return BbResult.NOTHING_TO_DO
                coreHp = min(BbRules.CORE_MAX, coreHp + BbRules.CORE_MAX / 2)
            }
            SpecialKind.NOVA_PULSE -> nova(pid)
        }
        specials[i]--
        emit(BbEvent.SpecialUsed(time, kind))
        return BbResult.OK
    }

    fun setPilotTarget(pid: Int, x: Double, y: Double) {
        val p = pilots.firstOrNull { it.pid == pid } ?: return
        p.tx = x.coerceIn(0.0, BbArena.W)
        p.ty = y.coerceIn(0.0, BbArena.H)
    }

    // ---- stepping ----------------------------------------------------------------------------------

    /** Advances by [seconds] of game time in fixed steps (a long call is capped, never a spiral). */
    fun advance(seconds: Double) {
        if (!playing() || seconds <= 0.0) return
        accum += seconds
        var n = 0
        while (accum >= STEP && playing() && n < MAX_STEPS_PER_ADVANCE) {
            step()
            accum -= STEP
            n++
        }
        if (n >= MAX_STEPS_PER_ADVANCE) accum = 0.0
    }

    internal fun step() {
        time += STEP
        if (scrapMultLeft > 0) { scrapMultLeft -= STEP; if (scrapMultLeft <= 0) { scrapMult = 1; scrapMultLeft = 0.0 } }
        if (scoreMultLeft > 0) { scoreMultLeft -= STEP; if (scoreMultLeft <= 0) { scoreMult = 1; scoreMultLeft = 0.0 } }
        if (phase == Phase.BUILD) {
            buildLeft -= STEP
            if (buildLeft <= 0) startWave()
        }
        if (phase == Phase.WAVE) spawnDue()
        updateSupport()
        moveEnemies()
        if (phase == Phase.LOST) return
        updateBots()
        updatePilots()
        reap()
        if (phase == Phase.WAVE && spawnIdx >= spawns.size && enemies.isEmpty()) segmentDone()
    }

    private fun startWave() {
        wave++
        phase = Phase.WAVE
        segment = 0
        segTime = 0.0
        spawnIdx = 0
        spawns = BbWaves.mainSpawns(map, tier, players.size, seed, wave)
        emit(BbEvent.WaveStart(time, wave, spawns.size, BbRules.isBossWave(wave)))
    }

    private fun spawnDue() {
        segTime += STEP
        while (spawnIdx < spawns.size && spawns[spawnIdx].t <= segTime) {
            val s = spawns[spawnIdx++]
            addEnemy(s.type, s.lane, 0.0, s.type.hp * s.hpMult, s.speedMult, s.swarm)
        }
    }

    internal fun addEnemy(type: EnemyType, lane: Int, dist: Double, hp: Double, speedMult: Double, swarm: Boolean): BbEnemy {
        val e = BbEnemy(nextId++, type, lane, dist, hp, hp, speedMult, swarm)
        val p = map.lanes[lane].pointAt(dist)
        e.x = p.x; e.y = p.y
        enemies += e
        return e
    }

    private fun segmentDone() {
        if (segment == 0 && BbWaves.swarmAfter(tier, wave)) {
            segment = 1
            segTime = 0.0
            spawnIdx = 0
            spawns = BbWaves.swarmSpawns(map, tier, players.size, wave)
            emit(BbEvent.SwarmStart(time, wave))
            return
        }
        val scrapBonus = (BbRules.clearScrap(wave) * tier.cash).roundToInt()
        val scoreBonus = BbRules.clearScore(wave)
        clearScore += scoreBonus
        for (p in players) p.scrap += scrapBonus
        emit(BbEvent.WaveClear(time, wave, scrapBonus, scoreBonus))
        if (BbRules.hasCourier(wave)) awardSpecial(wave * 100L)
        spawns = emptyList(); spawnIdx = 0
        if (!tier.endless && wave >= tier.goal) {
            win()
        } else {
            phase = Phase.BUILD
            buildLeft = BbRules.BUILD_SECONDS
        }
    }

    private fun win() {
        phase = Phase.WON
        winBonus = BbScoring.winBonus(killScore, coreHp, time, tier.goal)
        emit(BbEvent.Ended(time, true))
    }

    private fun lose() {
        phase = Phase.LOST
        emit(BbEvent.Ended(time, false))
    }

    // ---- enemies -----------------------------------------------------------------------------------

    private fun updateSupport() {
        for (e in enemies) { e.slow = 0.0; if (e.freeze > 0) e.freeze -= STEP }
        for (b in bots) {
            if (b.type != BotType.TENDER) continue
            val range = BotStats.rangeU(b.type, b.level)
            val slow = BotStats.tenderSlow(b.level)
            var cand: BbEnemy? = null
            for (e in enemies) {
                if (hypot(e.x - b.x, e.y - b.y) > range) continue
                e.slow = maxOf(e.slow, if (e.type.isBoss) slow / 2 else slow)
                if (!e.type.isBoss && e.freeze <= 0 && better(e, cand, b.priority, b.x, b.y)) cand = e
            }
            b.cooldown -= STEP
            if (b.cooldown <= 0 && cand != null) {
                cand.freeze = BotStats.FREEZE_SECONDS
                b.cooldown = BotStats.tenderFreezeEvery(b.level)
                emit(BbEvent.Freeze(time, cand.x, cand.y))
            } else if (b.cooldown < 0) b.cooldown = 0.0
        }
    }

    private fun moveEnemies() {
        var i = 0
        while (i < enemies.size) {
            val e = enemies[i]
            val lane = map.lanes[e.lane]
            if (e.freeze <= 0) {
                val v = e.type.speedU * map.pace * tier.speed * e.speedMult * (1.0 - e.slow)
                e.dist += v * STEP
            }
            if (e.dist >= lane.length) {
                enemies.removeAt(i)
                leak(e)
                if (phase == Phase.LOST) return
                continue
            }
            val p = lane.pointAt(e.dist)
            e.x = p.x; e.y = p.y
            i++
        }
    }

    private fun leak(e: BbEnemy) {
        leaked++
        if (e.type == EnemyType.COURIER) emit(BbEvent.CourierEscaped(time))
        val dmg = tier.leak * e.type.leakMult
        if (dmg <= 0) return
        val absorbed = min(shield, dmg)
        shield -= absorbed
        coreHp -= dmg - absorbed
        emit(BbEvent.Leak(time, e.type, dmg, absorbed))
        if (coreHp <= 0) { coreHp = 0; lose() }
    }

    private fun reap() {
        enemies.removeAll { it.dead }
    }

    // ---- targeting and damage ----------------------------------------------------------------------

    /** True when [a] beats [b] for [prio] seen from (x, y). Ties keep the earlier (lower id) enemy. */
    internal fun better(a: BbEnemy, b: BbEnemy?, prio: TargetPriority, x: Double, y: Double): Boolean {
        if (b == null) return true
        return when (prio) {
            TargetPriority.FIRST -> (map.lanes[a.lane].length - a.dist) < (map.lanes[b.lane].length - b.dist)
            TargetPriority.LAST -> (map.lanes[a.lane].length - a.dist) > (map.lanes[b.lane].length - b.dist)
            TargetPriority.WEAKEST -> a.hp < b.hp
            TargetPriority.STRONGEST -> a.hp > b.hp
            TargetPriority.CLOSEST -> hypot(a.x - x, a.y - y) < hypot(b.x - x, b.y - y)
        }
    }

    internal fun pickTarget(x: Double, y: Double, range: Double, minRange: Double, prio: TargetPriority): BbEnemy? {
        var best: BbEnemy? = null
        for (e in enemies) {
            if (e.dead) continue
            val d = hypot(e.x - x, e.y - y)
            if (d > range || d < minRange) continue
            if (better(e, best, prio, x, y)) best = e
        }
        return best
    }

    private fun updateBots() {
        for (b in bots) {
            when (b.type) {
                BotType.TENDER -> {}
                BotType.SKIFF -> { skiffMove(b); shoot(b) }
                else -> shoot(b)
            }
        }
    }

    private fun shoot(b: BbBot) {
        b.cooldown -= STEP
        if (b.cooldown > 0) return
        val range = BotStats.rangeU(b.type, b.level)
        val minR = if (b.type == BotType.LOBBER) BotStats.BLIND_U else 0.0
        val target = pickTarget(b.x, b.y, range, minR, b.priority) ?: run { if (b.cooldown < 0) b.cooldown = 0.0; return }
        if (b.cooldown < -STEP) b.cooldown = -STEP
        b.cooldown += 1.0 / BotStats.rate(b.type, b.level)
        val power = BotStats.power(b.type, b.level)
        emit(BbEvent.Shot(time, b.type, b.x, b.y, target.x, target.y))
        if (b.type == BotType.LOBBER) {
            val ix = target.x
            val iy = target.y
            emit(BbEvent.Blast(time, ix, iy, BotStats.SPLASH_U))
            for (e in ArrayList(enemies)) {
                if (!e.dead && hypot(e.x - ix, e.y - iy) <= BotStats.SPLASH_U) hit(e, power, b.owner)
            }
        } else {
            val armoured = target.type == EnemyType.PLATER && (b.type == BotType.PICKET || b.type == BotType.HAZE)
            hit(target, if (armoured) power * BotStats.ARMOUR_FACTOR else power, b.owner)
        }
    }

    internal fun hit(e: BbEnemy, amount: Double, pid: Int) {
        if (e.dead) return
        e.hp -= amount
        if (e.hp <= 0) kill(e, pid, split = true)
    }

    private fun kill(e: BbEnemy, pid: Int, split: Boolean) {
        e.dead = true
        val scrap = if (e.swarm) 0 else (e.type.scrap * tier.cash * scrapMult).roundToInt()
        val points = e.type.points * scoreMult
        killScore += points
        val p = players[pid.coerceIn(0, players.size - 1)]
        p.scrap += scrap
        p.kills++
        emit(BbEvent.Kill(time, e.type, e.x, e.y, scrap, points, p.index))
        if (e.type == EnemyType.COURIER) awardSpecial(wave * 100L + 1)
        if (split && e.type == EnemyType.SPLITTER) {
            val hp = EnemyType.BIT.hp * (e.maxHp / EnemyType.SPLITTER.hp)
            val len = map.lanes[e.lane].length
            addEnemy(EnemyType.BIT, e.lane, e.dist, hp, e.speedMult, e.swarm)
            addEnemy(EnemyType.BIT, e.lane, min(len, e.dist + 0.2), hp, e.speedMult, e.swarm)
        }
    }

    private fun nova(pid: Int) {
        emit(BbEvent.Blast(time, BbArena.W / 2, BbArena.H / 2, BbArena.W))
        for (e in ArrayList(enemies)) {
            if (e.dead || e.x < 0 || e.x > BbArena.W || e.y < 0 || e.y > BbArena.H) continue
            if (e.type.isBoss) {
                e.hp -= e.maxHp * 0.25
                if (e.hp <= 0) kill(e, pid, split = false)
            } else kill(e, pid, split = false)
        }
        reap()
    }

    internal fun awardSpecial(key: Long) {
        val r = Random(seed * 7919L + key)
        val rare = r.nextInt(100) >= 70
        val pick = r.nextBoolean()
        val kind = if (rare) (if (pick) SpecialKind.NOVA_PULSE else SpecialKind.REPAIR_KIT)
        else (if (pick) SpecialKind.SCRAP_SURGE else SpecialKind.SCORE_BOOST)
        val keep = specials[kind.ordinal] < BbRules.SPECIAL_CAP
        if (keep) specials[kind.ordinal]++
        emit(BbEvent.SpecialGained(time, kind, keep))
    }

    // ---- skiffs and pilots -------------------------------------------------------------------------

    private fun moveToward(ox: Double, oy: Double, tx: Double, ty: Double, maxStep: Double): Pt {
        val d = hypot(tx - ox, ty - oy)
        if (d <= maxStep || d == 0.0) return Pt(tx, ty)
        return Pt(ox + (tx - ox) / d * maxStep, oy + (ty - oy) / d * maxStep)
    }

    private fun skiffMove(b: BbBot) {
        val step = BotStats.SKIFF_SPEED * STEP
        val range = BotStats.rangeU(BotType.SKIFF, b.level)
        val target = pickTarget(b.x, b.y, range, 0.0, b.priority)
        if (target != null) {
            val d = hypot(target.x - b.x, target.y - b.y)
            val hold = range * 0.5
            if (d > hold) {
                val p = moveToward(b.x, b.y, target.x, target.y, min(step, d - hold))
                b.x = p.x; b.y = p.y
            }
            b.chasing = true
        } else if (b.chasing) {
            val rp = BbRim.at(b.rimS)
            val p = moveToward(b.x, b.y, rp.x, rp.y, step)
            b.x = p.x; b.y = p.y
            if (p.x == rp.x && p.y == rp.y) b.chasing = false
        } else {
            b.rimS = (b.rimS + step) % BbRim.perimeter
            val p = BbRim.at(b.rimS)
            b.x = p.x; b.y = p.y
        }
    }

    private fun updatePilots() {
        if (pilots.isEmpty()) return
        val step = BotStats.SKIFF_SPEED * STEP
        for (p in pilots) {
            if (p.down) {
                p.downLeft -= STEP
                if (p.downLeft <= 0) {
                    p.downLeft = 0.0; p.hp = BbRules.PILOT_HP
                    val s = BbRim.at(BbRim.perimeter * p.pid / BbRules.MAX_PLAYERS)
                    p.x = s.x; p.y = s.y; p.tx = s.x; p.ty = s.y
                    emit(BbEvent.PilotBack(time, p.pid))
                }
                continue
            }
            val m = moveToward(p.x, p.y, p.tx, p.ty, step)
            p.x = m.x; p.y = m.y
            for (e in enemies) {
                if (e.type.isBoss && hypot(e.x - p.x, e.y - p.y) < 2.0) p.hp -= 12.0 * STEP
            }
            if (p.hp <= 0) {
                p.hp = 0.0; p.downLeft = BbRules.PILOT_RESPAWN
                emit(BbEvent.PilotDown(time, p.pid))
                continue
            }
            p.cooldown -= STEP
            if (p.cooldown > 0) continue
            val range = BotStats.rangeU(BotType.SKIFF, 1)
            val t = pickTarget(p.x, p.y, range, 0.0, TargetPriority.CLOSEST)
            if (t == null) { if (p.cooldown < 0) p.cooldown = 0.0; continue }
            p.cooldown += 1.0 / BotStats.rate(BotType.SKIFF, 1)
            emit(BbEvent.Shot(time, BotType.SKIFF, p.x, p.y, t.x, t.y))
            hit(t, BotStats.power(BotType.SKIFF, 1), p.pid)
        }
    }
}

/** Score, win bonus and stars (`BALANCE.md` section 7). */
object BbScoring {
    const val PAR_SECONDS_PER_WAVE = 40

    /** Core-left bonus is 0.5% of the kill score per 1% Core left; the time bonus is 1 per second under par. */
    fun winBonus(killScore: Int, coreHp: Int, timeSeconds: Double, goalWaves: Int): Int {
        val corePercent = coreHp * 100 / BbRules.CORE_MAX
        val coreBonus = floor(killScore * 0.005 * corePercent).toInt()
        val par = PAR_SECONDS_PER_WAVE * goalWaves
        val timeBonus = (par - timeSeconds).toInt().coerceAtLeast(0)
        return coreBonus + timeBonus
    }

    /**
     * 0 for a loss and for Endless; 1 for a win; 2 for a win on Captain or above with the Core above 50%;
     * 3 for a win on Hero or Insanity, or with the Core above 80% (SPEC section 8).
     */
    fun stars(won: Boolean, tier: Tier, coreLeft: Int): Int {
        if (!won || tier.endless) return 0
        val pct = coreLeft * 100 / BbRules.CORE_MAX
        return when {
            tier >= Tier.HERO || pct > 80 -> 3
            tier >= Tier.CAPTAIN && pct > 50 -> 2
            else -> 1
        }
    }
}
