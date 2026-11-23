package com.gamesuite.games.towerdefence

import androidx.compose.ui.geometry.Offset
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceEnemy
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceEnemyDeathEvent
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceProjectile
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceRunResult
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceState
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceTower
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Id and event counters of one run, owned by the game (kept out of the immutable state). */
internal class TdIds {
    var enemy = 0
    var tower = 0
    var projectile = 0
    var patch = 0
    var event = 0L
}

/**
 * One fixed step of the open-field simulation: wave countdown and spawn, flow-field movement,
 * castle damage, tower aura/targeting/firing, projectile impact (splash, slow, stun, burn), kills
 * and gold, wave clear / win / loss. Pure function of its inputs, no randomness (so the Daily and
 * JVM tests are reproducible). Positions are tiles.
 */
internal object TdSiege {
    private class Aura(val at: Offset, val range: Float, val rate: Float, val rangeBonus: Float)

    fun step(
        s: TowerDefenceState,
        dtIn: Float,
        cfg: TdTierConfig,
        terrain: TdTerrain,
        flow: TdFlow,
        occupied: BooleanArray,
        ids: TdIds
    ): TowerDefenceState {
        val dt = dtIn.coerceIn(0f, 0.05f)
        var castle = s.lives
        var gold = s.gold
        var enemies = s.enemies
        var projectiles = s.projectiles
        var pending = s.pendingSpawns
        var spawnCooldown = s.spawnCooldown
        var inter = s.interWaveCooldown
        var wave = s.waveNumber
        var result: TowerDefenceRunResult? = null
        var death: TowerDefenceEnemyDeathEvent? = s.lastEnemyDeath
        var castleHit = s.lastCastleHit
        var patches = s.firePatches

        // Support auras: gold bonus is global, rate/range bonuses are local (best aura wins, no stacking).
        val auras = ArrayList<Aura>()
        var goldBonus = 0f
        for (t in s.towers) {
            if (t.role != TdRole.SUPPORT) continue
            val st = TdRules.stats(t.role, t.upgradeLevel, t.branch)
            auras.add(Aura(TdGrid.towerCentre(t.cx, t.cy), st.auraRange, st.auraRate, st.auraRangeBonus))
            goldBonus += st.goldBonus
        }

        fun reward(e: TowerDefenceEnemy): Int = (e.goldReward * (1f + goldBonus)).roundToInt()

        // Phase 0: countdown to the next wave.
        if (pending.isEmpty() && enemies.isEmpty() && inter > 0f) {
            inter = (inter - dt).coerceAtLeast(0f)
            if (inter <= 0f) {
                pending = TdWaves.spawnOrder(wave, cfg.countMultiplier)
                spawnCooldown = 0f
            }
        }

        // Phase 1: spawn, one per interval, alternating entrances.
        if (pending.isNotEmpty()) {
            spawnCooldown -= dt
            val list = enemies.toMutableList()
            var idx = 0
            while (spawnCooldown <= 0f && idx < pending.size) {
                val kind = pending[idx++]
                val spec = TdRules.enemy(kind)
                val hp = TdRules.waveBaseHp(wave) * cfg.hpMultiplier * spec.hpMultiplier
                val speed = TdRules.waveBaseSpeed(wave) * cfg.speedMultiplier * spec.speedMultiplier
                val lane = ids.enemy % terrain.spawns.size
                list.add(
                    TowerDefenceEnemy(
                        id = ids.enemy++, distanceTraveled = 0f, hp = hp, maxHp = hp, speed = speed,
                        goldReward = spec.gold, kind = kind, pos = TdGrid.centre(terrain.spawns[lane])
                    )
                )
                spawnCooldown += TdRules.SPAWN_INTERVAL
            }
            pending = pending.subList(idx, pending.size).toList()
            enemies = list
        }

        // Phase 2: fire patches, status timers, movement, leaks.
        val moved = ArrayList<TowerDefenceEnemy>(enemies.size)
        val burning = patches
        for (e in enemies) {
            val spec = TdRules.enemy(e.kind)
            var hp = e.hp
            // Ground fire: a walker standing in any patch takes the burn once per step (patches do not stack).
            if (!spec.flying && burning.isNotEmpty() && burning.any { dist(it.pos, e.pos) <= it.radius }) {
                hp -= TdRules.BURN_DPS * dt
                if (hp <= 0f) {
                    gold += reward(e)
                    ids.event++
                    death = TowerDefenceEnemyDeathEvent(ids.event, e.pos)
                    continue
                }
            }
            val stun = (e.stunRemaining - dt).coerceAtLeast(0f)
            val slowLeft = (e.slowRemaining - dt).coerceAtLeast(0f)
            val speedNow = when {
                e.stunRemaining > 0f -> 0f
                e.slowRemaining > 0f -> e.speed * (1f - e.slow)
                else -> e.speed
            }
            val travel = speedNow * dt
            var pos = e.pos
            var leaked = false
            if (spec.flying) {
                val to = terrain.gateCentre - pos
                val len = sqrt(to.x * to.x + to.y * to.y)
                if (len <= 0.35f || travel >= len) leaked = true else pos += to * (travel / len)
            } else {
                pos = settle(terrain, occupied, pos)
                if (travel > 0f) pos = advanceGround(terrain, flow, occupied, pos, travel)
                if (terrain.isGate(TdGrid.cellOf(pos.x, pos.y))) leaked = true
            }
            if (leaked) {
                castle -= spec.castleDamage
                ids.event++
                castleHit = TowerDefenceGame.CastleHitEvent(ids.event, spec.castleDamage)
                continue
            }
            moved.add(
                e.copy(
                    pos = pos, distanceTraveled = e.distanceTraveled + travel, hp = hp,
                    stunRemaining = stun, slowRemaining = slowLeft, slow = if (slowLeft > 0f) e.slow else 0f
                )
            )
        }
        enemies = moved
        if (patches.isNotEmpty()) {
            patches = patches.mapNotNull { if (it.remaining - dt > 0f) it.copy(remaining = it.remaining - dt) else null }
        }
        if (castle <= 0) {
            castle = 0
            result = TowerDefenceRunResult.LOST
        }

        // Phase 3: towers age, pick targets by mode, fire.
        var towers = s.towers
        if (result == null) {
            val updated = ArrayList<TowerDefenceTower>(towers.size)
            val fired = ArrayList<TowerDefenceProjectile>()
            for (t in towers) {
                val st = TdRules.stats(t.role, t.upgradeLevel, t.branch)
                val centre = TdGrid.towerCentre(t.cx, t.cy)
                val age = t.age + dt
                if (!st.attacks) { updated.add(t.copy(age = age)); continue }
                var rateB = 0f
                var rangeB = 0f
                for (a in auras) {
                    if (dist(a.at, centre) <= a.range) {
                        rateB = maxOf(rateB, a.rate)
                        rangeB = maxOf(rangeB, a.rangeBonus)
                    }
                }
                var cd = t.cooldownRemaining - dt * (1f + rateB)
                var shots = t.shots
                if (cd <= 0f) {
                    val range = st.range * (1f + rangeB)
                    val target = pickTarget(t, centre, range, enemies, terrain, flow)
                    if (target != null) {
                        shots++
                        val stun = if (st.stunEvery > 0 && shots % st.stunEvery == 0) st.stunSeconds else 0f
                        fired.add(
                            TowerDefenceProjectile(
                                id = ids.projectile++, position = centre, targetEnemyId = target.id, damage = st.damage,
                                role = t.role, splash = st.splashRadius, slow = st.slow, stunSeconds = stun,
                                burnSeconds = st.burnSeconds, bossBonus = st.bossBonus
                            )
                        )
                        cd = st.cooldownSeconds
                    } else {
                        cd = 0f
                    }
                }
                updated.add(t.copy(cooldownRemaining = cd.coerceAtLeast(0f), age = age, shots = shots))
            }
            towers = updated
            projectiles = projectiles + fired
        }

        // Phase 4: projectiles fly and land.
        if (result == null) {
            val byId = enemies.associateByTo(LinkedHashMap()) { it.id }
            val live = ArrayList<TowerDefenceProjectile>()
            fun hurt(id: Int, p: TowerDefenceProjectile, armoured: Boolean, bossBonus: Float, direct: Boolean) {
                val e = byId[id] ?: return
                val spec = TdRules.enemy(e.kind)
                var dmg = p.damage
                if (armoured) dmg *= (1f - spec.boltArmour)
                if (e.kind == TdEnemyKind.BOSS && bossBonus > 0f) dmg *= (1f + bossBonus)
                var next = e.copy(hp = e.hp - dmg)
                if (p.slow > 0f) {
                    val strength = p.slow * (1f - spec.slowResist)
                    if (next.slowRemaining <= 0f || strength >= next.slow) {
                        next = next.copy(slow = strength, slowRemaining = TdRules.SLOW_SECONDS)
                    }
                }
                if (p.stunSeconds > 0f && !spec.stunImmune && direct) {
                    next = next.copy(stunRemaining = maxOf(next.stunRemaining, p.stunSeconds))
                }
                if (next.hp <= 0f) {
                    gold += reward(next)
                    ids.event++
                    death = TowerDefenceEnemyDeathEvent(ids.event, e.pos)
                    byId.remove(id)
                } else {
                    byId[id] = next
                }
            }
            for (p in projectiles) {
                val target = byId[p.targetEnemyId] ?: continue
                val to = target.pos - p.position
                val d = sqrt(to.x * to.x + to.y * to.y)
                if (d <= TdRules.PROJECTILE_HIT) {
                    if (p.splash > 0f) {
                        val centre = target.pos
                        val victims = byId.values
                            .filter { !TdRules.enemy(it.kind).flying && dist(it.pos, centre) <= p.splash }
                            .map { it.id }
                        for (v in victims) hurt(v, p, false, 0f, v == target.id)
                        if (p.burnSeconds > 0f) patches = TdFire.ignite(patches, centre, p.splash, p.burnSeconds, ids)
                    } else {
                        hurt(target.id, p, p.role == TdRole.BOLT, p.bossBonus, true)
                    }
                } else {
                    val stepLen = minOf(TdRules.PROJECTILE_SPEED * dt, d)
                    live.add(p.copy(position = p.position + to * (stepLen / d)))
                }
            }
            enemies = byId.values.toList()
            projectiles = live
        }

        // Phase 5: wave cleared.
        if (result == null && pending.isEmpty() && enemies.isEmpty() && inter <= 0f) {
            if (s.mode != TdMode.ENDLESS && wave >= s.totalWaves) {
                result = TowerDefenceRunResult.WON
            } else {
                wave += 1
                inter = TowerDefenceGame.OPEN_INTER_WAVE_SECONDS
            }
        }

        return s.copy(
            lives = castle, gold = gold, enemies = enemies, towers = towers, projectiles = projectiles,
            pendingSpawns = pending, enemiesRemainingToSpawn = pending.size, spawnCooldown = spawnCooldown,
            interWaveCooldown = inter, waveNumber = wave, runResult = result,
            lastEnemyDeath = death, lastCastleHit = castleHit, firePatches = patches
        )
    }

    private fun dist(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Remaining distance to the castle, in cells: the flow value for walkers, straight line for flyers. */
    fun remaining(e: TowerDefenceEnemy, terrain: TdTerrain, flow: TdFlow): Float =
        if (TdRules.enemy(e.kind).flying) {
            dist(e.pos, terrain.gateCentre) * TdGrid.CELLS_PER_TILE
        } else {
            val d = flow.dist[TdGrid.cellOf(e.pos.x, e.pos.y)]
            if (d.isFinite()) d else 1.0e6f
        }

    fun pickTarget(
        t: TowerDefenceTower, centre: Offset, range: Float, enemies: List<TowerDefenceEnemy>,
        terrain: TdTerrain, flow: TdFlow
    ): TowerDefenceEnemy? {
        var best: TowerDefenceEnemy? = null
        var bestScore = 0f
        for (e in enemies) {
            if (t.role == TdRole.BLAST && TdRules.enemy(e.kind).flying) continue
            val d = dist(e.pos, centre)
            if (d > range) continue
            // Higher score wins; ties keep the earlier enemy.
            val score = when (t.target) {
                TdTarget.FIRST -> -remaining(e, terrain, flow)
                TdTarget.LAST -> remaining(e, terrain, flow)
                TdTarget.STRONG -> e.hp
                TdTarget.CLOSE -> -d
            }
            if (best == null || score > bestScore) { best = e; bestScore = score }
        }
        return best
    }

    private fun walkable(t: TdTerrain, occ: BooleanArray, cell: Int): Boolean = t.passable[cell] && !occ[cell]

    /** If a tower was built under the enemy, move it to the nearest free cell centre. */
    private fun settle(t: TdTerrain, occ: BooleanArray, pos: Offset): Offset {
        val c = TdGrid.cellOf(pos.x, pos.y)
        if (walkable(t, occ, c)) return pos
        val cx = TdGrid.cx(c)
        val cy = TdGrid.cy(c)
        for (r in 1..8) {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if (!TdGrid.inBounds(x, y)) continue
                val i = TdGrid.idx(x, y)
                if (!walkable(t, occ, i)) continue
                val d = dist(TdGrid.centre(i), pos)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0) return TdGrid.centre(best)
        }
        return pos
    }

    /** Steer along the bilinearly blended flow directions of the four nearest cell centres; slide along blocked cells. */
    private fun advanceGround(t: TdTerrain, flow: TdFlow, occ: BooleanArray, pos: Offset, travel: Float): Offset {
        val fx = pos.x * TdGrid.CELLS_PER_TILE - 0.5f
        val fy = pos.y * TdGrid.CELLS_PER_TILE - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        var dx = 0f
        var dy = 0f
        for (j in 0..1) for (i in 0..1) {
            val x = x0 + i
            val y = y0 + j
            if (!TdGrid.inBounds(x, y)) continue
            val c = TdGrid.idx(x, y)
            if (!walkable(t, occ, c)) continue
            val w = (if (i == 0) 1f - tx else tx) * (if (j == 0) 1f - ty else ty)
            val d = TdFlowField.direction(flow, c, t)
            dx += d.x * w
            dy += d.y * w
        }
        var len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) {
            val d = TdFlowField.direction(flow, TdGrid.cellOf(pos.x, pos.y), t)
            dx = d.x
            dy = d.y
            len = sqrt(dx * dx + dy * dy)
            if (len < 1e-4f) return pos
        }
        dx /= len
        dy /= len
        var nx = pos.x
        var ny = pos.y
        val tryX = pos.x + dx * travel
        if (walkable(t, occ, TdGrid.cellOf(tryX, ny))) nx = tryX
        val tryY = pos.y + dy * travel
        if (walkable(t, occ, TdGrid.cellOf(nx, tryY))) ny = tryY
        return Offset(nx, ny)
    }
}

/** Ground fire bookkeeping: a new patch merges into one already burning within half a tile (refreshed, never stacked), and the field holds at most [MAX_PATCHES]. */
internal object TdFire {
    const val MAX_PATCHES = 24
    private const val MERGE_DISTANCE = 0.5f

    fun ignite(
        patches: List<TowerDefenceGame.TdFirePatch>, at: Offset, splashRadius: Float, seconds: Float, ids: TdIds
    ): List<TowerDefenceGame.TdFirePatch> {
        val radius = splashRadius * 0.8f
        val dx = { p: TowerDefenceGame.TdFirePatch -> val x = p.pos.x - at.x; val y = p.pos.y - at.y; sqrt(x * x + y * y) }
        val near = patches.firstOrNull { dx(it) <= MERGE_DISTANCE }
        val out = if (near != null) {
            patches.map { if (it.id == near.id) it.copy(remaining = maxOf(it.remaining, seconds), radius = maxOf(it.radius, radius)) else it }
        } else {
            patches + TowerDefenceGame.TdFirePatch(id = ids.patch++, pos = at, radius = radius, remaining = seconds)
        }
        return if (out.size > MAX_PATCHES) out.sortedByDescending { it.remaining }.take(MAX_PATCHES) else out
    }
}
