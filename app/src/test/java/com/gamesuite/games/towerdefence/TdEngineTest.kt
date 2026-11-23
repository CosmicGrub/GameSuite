package com.gamesuite.games.towerdefence

import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceEnemy
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceRunResult
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Open-field engine: nav grid, flow field, placement rules, roles, archetypes, castle, determinism. */
class TdEngineTest {

    private fun openGame(
        mapId: String = "castle-pass",
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
        gentle: Boolean = false
    ): TowerDefenceGame {
        val game = TowerDefenceGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        game.gentle = gentle
        game.level = TowerDefenceGame.levelById(mapId)!!
        game.startMatch()
        return game
    }

    private fun tickSeconds(game: TowerDefenceGame, seconds: Float, step: Float = 0.05f) {
        var remaining = seconds
        while (remaining > 0f) { game.tick(step); remaining -= step }
    }

    private fun rich(game: TowerDefenceGame, gold: Int = 100_000) {
        game.state.value = game.state.value.copy(gold = gold)
    }

    private fun enemy(id: Int, kind: TdEnemyKind, x: Float, y: Float, hp: Float = 100f, speed: Float = 0f) =
        TowerDefenceEnemy(id = id, distanceTraveled = 0f, hp = hp, maxHp = hp, speed = speed, goldReward = 10, kind = kind, pos = Offset(x, y))

    // ---- nav grid and flow field -------------------------------------------------------------

    @Test
    fun everyMapReachesTheGateFromEverySpawnAndHasNoUnreachablePockets() {
        for (t in TdMaps.ALL) {
            val flow = t.openFlow
            for (s in t.spawns) assertTrue("${t.id} spawn $s", flow.dist[s].isFinite())
            val stranded = (0 until TdGrid.CELLS).count { t.passable[it] && !flow.dist[it].isFinite() }
            assertEquals("${t.id} has unreachable open cells", 0, stranded)
            // Castle cells are neither passable nor buildable; gate cells are passable but not buildable.
            for (g in t.gate) { assertTrue(t.passable[g]); assertFalse(t.buildable[g]); assertEquals(0f, flow.dist[g], 0f) }
            assertTrue("${t.id} must leave real building room", t.buildable.count { it } > 500)
        }
    }

    @Test
    fun openMapsAreAttackFromTheLeftTowardTheCastleOnTheRight() {
        for (t in TdMaps.ALL) {
            for (s in t.spawns) assertTrue(TdGrid.cx(s) <= 2)
            assertTrue(t.gateCentre.x > 20f)
            assertTrue(t.castle[0] >= 40)
        }
    }

    @Test
    fun flowFieldNeverCutsCornersThroughBlockedCells() {
        val b = TdTerrainBuilder("corner")
        b.castle(10, 17)
        b.spawn(14)
        b.block(10, 10, 10, 10)
        b.block(11, 11, 11, 11)
        val t = b.build()
        val flow = TdFlowField.compute(t, null)
        val a = TdGrid.idx(10, 11)
        val diag = TdGrid.idx(11, 10)
        assertTrue(flow.next[a] != diag)
        assertTrue(flow.next[diag] != a)
    }

    @Test
    fun flowFieldIsDeterministic() {
        val a = TdMaps.RIVER_BEND.openFlow
        val b = TdFlowField.compute(TdMaps.RIVER_BEND, null)
        assertTrue(a.dist.contentEquals(b.dist))
        assertTrue(a.next.contentEquals(b.next))
    }

    @Test
    fun routeEndsAtTheGate() {
        for (t in TdMaps.ALL) for (s in t.spawns) {
            val route = TdFlowField.route(t.openFlow, s)
            assertTrue(route.size > 10)
            assertTrue(t.isGate(TdGrid.cellOf(route.last().x, route.last().y)))
        }
    }

    // ---- placement validation ------------------------------------------------------------------

    private fun corridor(alternative: Boolean, cap: Float): TdTerrain {
        val b = TdTerrainBuilder("corridor")
        b.routeCap = cap
        b.block(0, 0, 47, 27)
        fun carve(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0..y1) for (x in x0..x1) { b.cost[TdGrid.idx(x, y)] = TdTerrainBuilder.OPEN; b.buildable[TdGrid.idx(x, y)] = true }
        }
        carve(0, 12, 43, 15)
        if (alternative) { carve(0, 16, 3, 23); carve(0, 20, 43, 23); carve(40, 16, 43, 23) }
        b.castle(12, 15)
        b.spawn(13)
        return b.build()
    }

    @Test
    fun rejectsUnbuildableOverlapAndNoGold() {
        val t = TdMaps.CASTLE_PASS
        val occ = TdPlacement.occupancy(listOf(26 to 12))
        // On the pond.
        assertEquals(TdPlaceResult.Rejected(TdReject.UNBUILDABLE), TdPlacement.validate(t, occ, 17, 19, 1000, 50))
        // Off the grid and on the castle.
        assertEquals(TdPlaceResult.Rejected(TdReject.UNBUILDABLE), TdPlacement.validate(t, occ, 47, 27, 1000, 50))
        assertEquals(TdPlaceResult.Rejected(TdReject.UNBUILDABLE), TdPlacement.validate(t, occ, 44, 12, 1000, 50))
        // Overlap with the tower at (26,12): footprint 26..27 x 12..13.
        assertEquals(TdPlaceResult.Rejected(TdReject.OVERLAP), TdPlacement.validate(t, occ, 27, 13, 1000, 50))
        // Gold.
        assertEquals(TdPlaceResult.Rejected(TdReject.NO_GOLD), TdPlacement.validate(t, occ, 10, 10, 49, 50))
        assertTrue(TdPlacement.validate(t, occ, 10, 10, 50, 50) is TdPlaceResult.Ok)
    }

    @Test
    fun sealingTheOnlyRouteIsRejectedAsBlocksRoute() {
        val t = corridor(alternative = false, cap = 2.5f)
        val first = TdPlacement.validate(t, TdPlacement.occupancy(emptyList()), 20, 12, 1000, 50)
        assertTrue(first is TdPlaceResult.Ok)
        val occ = TdPlacement.occupancy(listOf(20 to 12))
        assertEquals(TdPlaceResult.Rejected(TdReject.BLOCKS_ROUTE), TdPlacement.validate(t, occ, 20, 14, 1000, 50))
    }

    @Test
    fun aDetourBeyondTheCapIsRejectedAsTooLongAndAcceptedWhenTheCapIsLooser() {
        val occ = TdPlacement.occupancy(listOf(20 to 12))
        val tight = corridor(alternative = true, cap = 1.2f)
        assertEquals(TdPlaceResult.Rejected(TdReject.TOO_LONG), TdPlacement.validate(tight, occ, 20, 14, 1000, 50))
        val loose = corridor(alternative = true, cap = 2.5f)
        val ok = TdPlacement.validate(loose, occ, 20, 14, 1000, 50)
        assertTrue(ok is TdPlaceResult.Ok)
        assertTrue((ok as TdPlaceResult.Ok).detourTiles > 1f)
        val off = corridor(alternative = true, cap = Float.POSITIVE_INFINITY)
        assertTrue(TdPlacement.validate(off, occ, 20, 14, 1000, 50) is TdPlaceResult.Ok)
    }

    @Test
    fun randomLegalPlacementsNeverStrandASpawnOrBreakTheCap() {
        for (t in TdMaps.ALL) {
            val rnd = Random(42L)
            val anchors = ArrayList<Pair<Int, Int>>()
            var placed = 0
            repeat(500) {
                val ax = rnd.nextInt(TdGrid.COLS - 1)
                val ay = rnd.nextInt(TdGrid.ROWS - 1)
                val occ = TdPlacement.occupancy(anchors)
                val r = TdPlacement.validate(t, occ, ax, ay, 1_000_000, 50)
                if (r is TdPlaceResult.Ok) {
                    anchors.add(ax to ay)
                    placed++
                    val flow = TdFlowField.compute(t, TdPlacement.occupancy(anchors))
                    for ((i, s) in t.spawns.withIndex()) {
                        assertTrue("${t.id} spawn stranded after $placed towers", flow.dist[s].isFinite())
                        assertTrue("${t.id} cap broken", flow.dist[s] <= t.baseline[i] * t.routeCap + 0.001f)
                    }
                }
            }
            assertTrue("${t.id} should allow plenty of towers", placed > 25)
        }
    }

    @Test
    fun towersCanStandOnTheRoadAndTheRoadStillRoutesAroundThem() {
        val game = openGame()
        rich(game)
        val before = game.routePreview().first()
        val mid = before[before.size / 2]
        val ax = (mid.x * 2).toInt() - 1
        val ay = (mid.y * 2).toInt() - 1
        val r = game.placeTower(ax, ay, TdRole.BOLT)
        assertTrue("placing on the road should be allowed: $r", r is TdPlaceResult.Ok)
        val after = game.routePreview().first()
        assertTrue(after.isNotEmpty())
        assertTrue(game.currentFlow()!!.dist[TdMaps.CASTLE_PASS.spawns.first()].isFinite())
    }

    // ---- economy: roles, branches, sell, move, targeting ------------------------------------------

    @Test
    fun roleCostsAndUpgradeCurvesMatchTheDesignBoard() {
        assertEquals(listOf(50, 90, 80, 110), TdRole.entries.map { TdRules.baseCost(it) })
        assertEquals(listOf(40, 64, 102, 164), (1..4).map { TdRules.upgradeCost(TdRole.BOLT, it) })
        assertEquals(listOf(72, 115, 184, 295), (1..4).map { TdRules.upgradeCost(TdRole.BLAST, it) })
        assertEquals(listOf(64, 102, 164, 262), (1..4).map { TdRules.upgradeCost(TdRole.CONTROL, it) })
        assertEquals(listOf(88, 141, 225, 360), (1..4).map { TdRules.upgradeCost(TdRole.SUPPORT, it) })
    }

    @Test
    fun levelThreeNeedsABranchAndTheBranchChangesTheStats() {
        val game = openGame()
        rich(game)
        assertTrue(game.placeTower(10, 10, TdRole.BOLT) is TdPlaceResult.Ok)
        val id = game.state.value.towers.single().id
        game.upgradeTower(id); game.upgradeTower(id)
        assertEquals(3, game.state.value.towers.single().upgradeLevel)
        val gold = game.state.value.gold
        game.upgradeTower(id) // no branch chosen: nothing happens
        assertEquals(3, game.state.value.towers.single().upgradeLevel)
        assertEquals(gold, game.state.value.gold)
        game.upgradeTower(id, TdBranch.B)
        game.upgradeTower(id)
        val t = game.state.value.towers.single()
        assertEquals(5, t.upgradeLevel)
        assertEquals(TdBranch.B, t.branch)
        assertEquals(90f, TdRules.stats(t.role, t.upgradeLevel, t.branch).damage, 0f)
        assertEquals(2.9f, TdRules.stats(TdRole.BOLT, 5, TdBranch.A).shotsPerSecond, 0f)
        game.upgradeTower(id)
        assertEquals(5, game.state.value.towers.single().upgradeLevel)
    }

    @Test
    fun sellRefundsEverythingInTheUndoWindowThenSeventyFivePercent() {
        val game = openGame()
        game.state.value = game.state.value.copy(gold = 500)
        game.placeTower(10, 10, TdRole.BLAST)
        assertEquals(410, game.state.value.gold)
        val id = game.state.value.towers.single().id
        assertEquals(90, game.sellTower(id))
        assertEquals(500, game.state.value.gold)
        assertTrue(game.state.value.towers.isEmpty())

        game.placeTower(10, 10, TdRole.BLAST)
        val id2 = game.state.value.towers.single().id
        game.upgradeTower(id2)
        tickSeconds(game, 4f)
        val invested = 90 + 72
        assertEquals(invested * 3 / 4, game.sellTower(id2))
        assertEquals(-1, game.sellTower(id2))
    }

    @Test
    fun moveIsFreeBetweenWavesAndCostsTwentyPercentDuringOne() {
        val game = openGame()
        rich(game, 1000)
        game.placeTower(10, 10, TdRole.BOLT)
        val id = game.state.value.towers.single().id
        val gold = game.state.value.gold
        assertTrue(game.moveTower(id, 12, 12) is TdPlaceResult.Ok)
        assertEquals(gold, game.state.value.gold)
        assertEquals(12, game.state.value.towers.single().cx)
        // During a wave the fee is 20 percent of 50.
        game.state.value = game.state.value.copy(enemies = listOf(enemy(1, TdEnemyKind.RUNNER, 3f, 7f)))
        assertEquals(10, game.moveFee(game.state.value.towers.single()))
        assertTrue(game.moveTower(id, 14, 12) is TdPlaceResult.Ok)
        assertEquals(gold - 10, game.state.value.gold)
        // Moving onto water is refused and charges nothing.
        assertEquals(TdPlaceResult.Rejected(TdReject.UNBUILDABLE), game.moveTower(id, 17, 19))
        assertEquals(gold - 10, game.state.value.gold)
    }

    @Test
    fun targetingModesPickFirstLastStrongAndClose() {
        val t = TdMaps.CASTLE_PASS
        val flow = t.openFlow
        val tower = TowerDefenceGame.TowerDefenceTower(1, -1, 1, 0f, role = TdRole.BOLT, cx = 20, cy = 18)
        val centre = TdGrid.towerCentre(20, 18) // (10.5, 9.5)
        val near = enemy(1, TdEnemyKind.RUNNER, 9f, 9f, hp = 30f)          // far from the castle, close to the tower
        val ahead = enemy(2, TdEnemyKind.RUNNER, 14f, 9f, hp = 20f)        // nearer the castle
        val tough = enemy(3, TdEnemyKind.BRUTE, 10.8f, 10.5f, hp = 500f)
        val list = listOf(near, ahead, tough)
        fun pick(mode: TdTarget) = TdSiege.pickTarget(tower.copy(target = mode), centre, 6f, list, t, flow)!!.id
        assertEquals(2, pick(TdTarget.FIRST))
        assertEquals(1, pick(TdTarget.LAST))
        assertEquals(3, pick(TdTarget.STRONG))
        assertEquals(3, pick(TdTarget.CLOSE))
    }

    @Test
    fun targetingSettingSticksToTheTower() {
        val game = openGame()
        rich(game)
        game.placeTower(10, 10, TdRole.BOLT)
        val id = game.state.value.towers.single().id
        assertEquals(TdTarget.FIRST, game.state.value.towers.single().target)
        game.setTargeting(id, TdTarget.STRONG)
        assertEquals(TdTarget.STRONG, game.state.value.towers.single().target)
    }

    // ---- enemies ---------------------------------------------------------------------------------

    @Test
    fun waveTableHasBossesOnWavesFiveAndTenOnly() {
        for (w in 1..10) assertEquals("wave $w", w == 5 || w == 10, TdWaves.hasBoss(w))
        assertEquals(1, TdWaves.spawnOrder(5, 1f).count { it == TdEnemyKind.BOSS })
        assertEquals(1, TdWaves.spawnOrder(10, 0.7f).count { it == TdEnemyKind.BOSS })
        assertEquals(6, TdWaves.spawnOrder(1, 1f).size)
        assertTrue(TdWaves.spawnOrder(4, 1f).contains(TdEnemyKind.FLYER))
        assertTrue(TdWaves.spawnOrder(3, 0.7f).count { it == TdEnemyKind.BRUTE } >= 1)
    }

    /** One tower and one stationary enemy next to it; ticks until the enemy has been hit, returns the enemy then. */
    private fun duel(role: TdRole, level: Int, branch: TdBranch, kind: TdEnemyKind, ticks: Int = 400): TowerDefenceEnemy {
        val game = openGame()
        val tower = TowerDefenceGame.TowerDefenceTower(
            id = 1, zoneIndex = -1, upgradeLevel = level, cooldownRemaining = 0f, role = role, branch = branch, cx = 20, cy = 12
        )
        val c = TdGrid.towerCentre(20, 12)
        game.state.value = game.state.value.copy(
            towers = listOf(tower), interWaveCooldown = 60f,
            enemies = listOf(enemy(5, kind, c.x + 1.5f, c.y, hp = 100000f))
        )
        var i = 0
        while (i++ < ticks) {
            game.tick(0.05f)
            val e = game.state.value.enemies.single()
            if (e.hp < 100000f) return e
        }
        return game.state.value.enemies.single()
    }

    @Test
    fun bruteArmourReducesBoltButNotBlast() {
        assertEquals(100000f - 18f * 0.75f, duel(TdRole.BOLT, 1, TdBranch.NONE, TdEnemyKind.BRUTE).hp, 0.01f)
        assertEquals(100000f - 18f, duel(TdRole.BOLT, 1, TdBranch.NONE, TdEnemyKind.RUNNER).hp, 0.01f)
        assertEquals(100000f - 30f, duel(TdRole.BLAST, 1, TdBranch.NONE, TdEnemyKind.BRUTE).hp, 0.01f)
    }

    @Test
    fun sniperBranchHitsBossesHarder() {
        assertEquals(100000f - 90f * 1.25f, duel(TdRole.BOLT, 5, TdBranch.B, TdEnemyKind.BOSS).hp, 0.01f)
        assertEquals(100000f - 90f, duel(TdRole.BOLT, 5, TdBranch.B, TdEnemyKind.RUNNER).hp, 0.01f)
    }

    @Test
    fun controlSlowsRunnersFullyAndBossesByHalfAndNeverStunsBosses() {
        val runner = duel(TdRole.CONTROL, 5, TdBranch.A, TdEnemyKind.RUNNER)
        assertEquals(0.65f, runner.slow, 0.001f)
        val boss = duel(TdRole.CONTROL, 5, TdBranch.A, TdEnemyKind.BOSS)
        assertEquals(0.325f, boss.slow, 0.001f)
        // Chain stun: every 4th shot stuns a runner for 0.6 s; a boss is never stunned.
        fun everStunned(kind: TdEnemyKind): Boolean {
            val game = openGame()
            val tower = TowerDefenceGame.TowerDefenceTower(1, -1, 5, 0f, role = TdRole.CONTROL, branch = TdBranch.B, cx = 20, cy = 12)
            val c = TdGrid.towerCentre(20, 12)
            game.state.value = game.state.value.copy(
                towers = listOf(tower), interWaveCooldown = 60f, enemies = listOf(enemy(1, kind, c.x + 1.5f, c.y, hp = 1e6f))
            )
            repeat(400) {
                game.tick(0.05f)
                if (game.state.value.enemies.single().stunRemaining > 0f) return true
            }
            return false
        }
        assertTrue(everStunned(TdEnemyKind.RUNNER))
        assertFalse(everStunned(TdEnemyKind.BOSS))
    }

    @Test
    fun flyersFlyStraightIgnoreTheMazeAndCannotBeHitByBlast() {
        val game = openGame()
        rich(game)
        // Blast towers right under the flyer: they must not fire at it.
        game.placeTower(10, 10, TdRole.BLAST)
        game.placeTower(10, 14, TdRole.BLAST)
        val s0 = game.state.value
        val flyer = enemy(7, TdEnemyKind.FLYER, 5f, 6f, hp = 100000f, speed = 1.0f)
        game.state.value = s0.copy(enemies = listOf(flyer), interWaveCooldown = 30f)
        val y0 = flyer.pos.y
        tickSeconds(game, 2f)
        val moved = game.state.value.enemies.single()
        assertTrue(game.state.value.projectiles.isEmpty())
        assertEquals(100000f, moved.hp, 0f)
        assertTrue(moved.pos.x > flyer.pos.x + 1.5f)
        // Heads straight at the gate: y drifts monotonically toward the gate centre's y.
        val gy = TdMaps.CASTLE_PASS.gateCentre.y
        assertTrue(kotlin.math.abs(moved.pos.y - gy) < kotlin.math.abs(y0 - gy))
        // A Bolt does hit it.
        game.placeTower(12, 8, TdRole.BOLT)
        tickSeconds(game, 2f)
        assertTrue(game.state.value.enemies.single().hp < 100000f)
    }

    @Test
    fun groundEnemiesWalkTheOpenFieldAndLeakIntoTheCastle() {
        val game = openGame()
        tickSeconds(game, TowerDefenceGame.OPEN_FIRST_WAVE_SECONDS + 1f)
        assertTrue(game.state.value.waveInProgress)
        var leaked = false
        var steps = 0
        while (steps++ < 20_000 && !leaked) {
            game.tick(0.05f)
            if (game.state.value.lives < game.state.value.castleMax) leaked = true
        }
        assertTrue("an undefended wave must reach the castle", leaked)
    }

    @Test
    fun everyEntranceDeliversEnemiesToTheGateOnEveryMapWithTowersInTheWay() {
        for (id in listOf("castle-pass", "river-bend", "twin-gates")) {
            val game = openGame(id)
            rich(game, 5000)
            // Fill a lot of the field with towers (those the rules allow), then run a wave undefended.
            val rnd = Random(7L)
            repeat(300) { game.placeTower(rnd.nextInt(TdGrid.COLS - 1), rnd.nextInt(TdGrid.ROWS - 1), TdRole.SUPPORT) }
            assertTrue(game.state.value.towers.size > 10)
            tickSeconds(game, TowerDefenceGame.OPEN_FIRST_WAVE_SECONDS + 1f)
            var steps = 0
            while (steps++ < 40_000 && game.state.value.lives == game.state.value.castleMax) game.tick(0.05f)
            assertTrue("$id: enemies got stuck among the towers", game.state.value.lives < game.state.value.castleMax)
        }
    }

    // ---- castle health ---------------------------------------------------------------------------

    @Test
    fun castleReplacesLivesBossCostsFiveAndZeroEndsTheRun() {
        val game = openGame()
        assertEquals(20, game.state.value.castleMax)
        assertEquals(20, game.state.value.lives)
        val gate = TdMaps.CASTLE_PASS.gateCentre
        game.state.value = game.state.value.copy(
            enemies = listOf(enemy(1, TdEnemyKind.BOSS, gate.x - 0.1f, gate.y, speed = 1f)), interWaveCooldown = 30f
        )
        tickSeconds(game, 0.2f)
        assertEquals(15, game.state.value.lives)
        assertNotNull(game.state.value.lastCastleHit)
        assertEquals(5, game.state.value.lastCastleHit!!.damage)
        game.state.value = game.state.value.copy(
            enemies = listOf(enemy(2, TdEnemyKind.RUNNER, gate.x - 0.1f, gate.y, speed = 1f)), interWaveCooldown = 30f
        )
        tickSeconds(game, 0.2f)
        assertEquals(14, game.state.value.lives)
        game.state.value = game.state.value.copy(lives = 1, enemies = listOf(enemy(3, TdEnemyKind.RUNNER, gate.x - 0.1f, gate.y, speed = 1f)))
        tickSeconds(game, 0.2f)
        assertEquals(TowerDefenceRunResult.LOST, game.state.value.runResult)
        assertEquals(0, game.state.value.lives)
    }

    @Test
    fun gentleTierHasMoreCastleAndGoldAndSofterWaves() {
        val game = openGame(gentle = true)
        assertEquals(30, game.state.value.castleMax)
        assertEquals(200, game.state.value.gold)
        assertTrue(game.state.value.gentle)
        assertEquals(4, TdWaves.spawnOrder(1, TowerDefenceGame.GENTLE.countMultiplier).size)
    }

    // ---- determinism ---------------------------------------------------------------------------------

    private fun playScripted(): List<Float> {
        val game = openGame()
        rich(game, 600)
        game.placeTower(22, 10, TdRole.BOLT)
        game.placeTower(26, 14, TdRole.BLAST)
        game.placeTower(30, 8, TdRole.CONTROL)
        tickSeconds(game, 60f)
        val s = game.state.value
        return listOf(s.gold.toFloat(), s.lives.toFloat(), s.waveNumber.toFloat()) + s.enemies.flatMap { listOf(it.pos.x, it.pos.y, it.hp) }
    }

    @Test
    fun sameInputsGiveIdenticalOutcomes() {
        assertEquals(playScripted(), playScripted())
    }

    // ---- balance smoke -----------------------------------------------------------------------------------

    /** A greedy but sensible player: cover the route with towers, mix roles, upgrade with spare gold. */
    private fun greedyBuild(game: TowerDefenceGame, buildOrder: List<TdRole>, buys: Int) {
        val s = game.state.value
        val route = game.routePreview().flatten()
        val order = buildOrder[s.towers.size % buildOrder.size]
        if (game.state.value.gold >= TdRules.baseCost(order) && s.towers.size < 14 && buys > 0) {
            var best: Triple<Int, Int, Int>? = null
            for (ay in 0 until TdGrid.ROWS - 1 step 2) for (ax in 0 until TdGrid.COLS - 1 step 2) {
                val c = TdGrid.towerCentre(ax, ay)
                val cover = route.count { (it - c).getDistance() <= 5f }
                if (cover == 0 || (best != null && cover <= best.first)) continue
                if (game.previewPlacement(ax, ay, order) is TdPlaceResult.Ok) best = Triple(cover, ax, ay)
            }
            if (best != null) game.placeTower(best.second, best.third, order)
        }
        // Upgrade the cheapest-to-upgrade attacker with spare gold.
        val tw = game.state.value.towers.filter { it.upgradeLevel < TdRules.MAX_LEVEL }
            .sortedBy { TdRules.upgradeCost(it.role, it.upgradeLevel) }
        for (t in tw) {
            val branch = if (TdRules.needsBranch(t.upgradeLevel, t.branch)) TdBranch.A else TdBranch.NONE
            val before = game.state.value.gold
            game.upgradeTower(t.id, branch)
            if (game.state.value.gold < before) break
        }
    }

    @Test
    fun aSensiblePlayerSurvivesTheCampaignOnMedium() {
        for (mapId in listOf("castle-pass", "river-bend", "twin-gates")) {
            val game = openGame(mapId)
            val order = listOf(TdRole.BOLT, TdRole.BLAST, TdRole.BOLT, TdRole.CONTROL, TdRole.BOLT, TdRole.SUPPORT, TdRole.BLAST)
            var steps = 0
            var lastWave = 0
            while (game.state.value.runResult == null && steps++ < 300_000) {
                if (steps % 40 == 0 && !game.state.value.waveInProgress) greedyBuild(game, order, 1)
                if (steps % 400 == 0 && game.state.value.waveInProgress) greedyBuild(game, order, 1)
                game.tick(0.05f)
                lastWave = game.state.value.waveNumber
            }
            val s = game.state.value
            println("BALANCE $mapId result=${s.runResult} wave=$lastWave castle=${s.lives}/${s.castleMax} towers=${s.towers.size} gold=${s.gold}")
            assertNotNull("$mapId run must end", s.runResult)
            assertEquals("$mapId should be winnable by a sensible build", TowerDefenceRunResult.WON, s.runResult)
        }
    }

    @Test
    fun doingNothingLosesOnMedium() {
        val game = openGame()
        var steps = 0
        while (game.state.value.runResult == null && steps++ < 200_000) game.tick(0.05f)
        assertEquals(TowerDefenceRunResult.LOST, game.state.value.runResult)
    }

    // ---- snapshot ---------------------------------------------------------------------------------------------

    private fun savedGame(): Pair<TowerDefenceGame, String> {
        val game = openGame("twin-gates")
        rich(game, 3000)
        game.placeTower(10, 10, TdRole.BOLT)
        game.placeTower(14, 14, TdRole.CONTROL)
        val id = game.state.value.towers.first().id
        game.upgradeTower(id); game.upgradeTower(id); game.upgradeTower(id, TdBranch.A)
        game.setTargeting(id, TdTarget.LAST)
        return game to game.exportSnapshot()!!
    }

    @Test
    fun snapshotRoundTripsTheBuildPhase() {
        val (game, raw) = savedGame()
        val other = openGame("castle-pass")
        assertTrue(other.restoreSnapshot(raw))
        val a = game.state.value
        val b = other.state.value
        assertEquals(a.level.id, b.level.id)
        assertEquals(a.gold, b.gold)
        assertEquals(a.towers.map { listOf(it.cx, it.cy, it.upgradeLevel, it.invested) }, b.towers.map { listOf(it.cx, it.cy, it.upgradeLevel, it.invested) })
        assertEquals(TdTarget.LAST, b.towers.first().target)
        assertEquals(TdBranch.A, b.towers.first().branch)
        assertTrue(other.currentFlow()!!.dist[TdMaps.TWIN_GATES.spawns.first()].isFinite())
    }

    @Test
    fun snapshotIsRefusedMidWave() {
        val (game, _) = savedGame()
        game.state.value = game.state.value.copy(enemies = listOf(enemy(1, TdEnemyKind.RUNNER, 3f, 3f)))
        assertNull(game.exportSnapshot())
    }

    @Test
    fun corruptSnapshotsAreRejectedAndLeaveTheGameUntouched() {
        val (_, raw) = savedGame()
        val d = TdSnapshot.decode(raw)!!
        val t0 = d.towers[0]
        val bad = listOf(
            null, "", "   ", "not json", raw.substring(0, raw.length / 2), "{}", "[]", "null",
            raw.replace("\"mapId\":\"twin-gates\"", "\"mapId\":\"nowhere\""),
            raw.replace("\"version\":1", "\"version\":2"),
            raw.replace("}", ",\"extra\":1}"),
            TdSnapshot.encode(d.copy(wave = 0)),
            TdSnapshot.encode(d.copy(wave = 11)),
            TdSnapshot.encode(d.copy(difficulty = "NIGHTMARE")),
            TdSnapshot.encode(d.copy(castle = 0)),
            TdSnapshot.encode(d.copy(castle = d.castleMax + 1)),
            TdSnapshot.encode(d.copy(castleMax = 99)),
            TdSnapshot.encode(d.copy(gold = -1)),
            TdSnapshot.encode(d.copy(gold = 2_000_000)),
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(level = 6)))),
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(level = 0)))),
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(role = "LASER")))),
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(target = "RANDOM")))),
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(branch = "NONE")))),                 // level 4 needs a branch
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(level = 2, branch = "A")))),         // branch below level 4
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(invested = 1)))),                    // minted refund
            TdSnapshot.encode(d.copy(towers = listOf(t0, t0))),                                   // overlap
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(cx = -1)))),
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(cx = 47, cy = 27)))),                // off the grid
            TdSnapshot.encode(d.copy(towers = listOf(t0.copy(cx = 20, cy = 10)))),                // on the wall
            TdSnapshot.encode(d.copy(towers = List(81) { t0.copy(cx = (it % 20) * 2, cy = 0) }))  // too many
        )
        for ((i, b) in bad.withIndex()) {
            val g = openGame("castle-pass")
            val before = g.state.value
            assertFalse("case $i should be rejected", g.restoreSnapshot(b))
            assertEquals("case $i must not touch the game", before, g.state.value)
        }
    }

    @Test
    fun aSnapshotThatSealsEveryRouteIsRejected() {
        val (_, raw) = savedGame()
        val d = TdSnapshot.decode(raw)!!
        // Two gaps in the twin-gates wall (rows 4..7 and 20..23 at x 20..23); fill both completely.
        fun wall(ay: Int) = listOf(20, 22).flatMap { ax -> listOf(ay, ay + 2).map { TdSnapshot.TowerS("BOLT", "NONE", "FIRST", 1, ax, it, 50) } }
        val sealed = d.copy(towers = wall(4) + wall(20))
        assertNull(TdSnapshot.decode(TdSnapshot.encode(sealed)))
    }

    // ---- stats keys ----------------------------------------------------------------------------------------------

    @Test
    fun classicStatsKeysAreUnchangedAndOpenAndGentleFollowTheSameFormat() {
        assertEquals("switchback:MEDIUM", TdStatsKeys.key("switchback", CpuDifficulty.MEDIUM))
        assertEquals("spiral:HARD", TdStatsKeys.key("spiral", CpuDifficulty.HARD))
        assertEquals("gauntlet:EASY", TdStatsKeys.key("gauntlet", CpuDifficulty.EASY))
        assertEquals("castle-pass:MEDIUM", TdStatsKeys.key("castle-pass", CpuDifficulty.MEDIUM))
        assertEquals("river-bend:GENTLE", TdStatsKeys.key("river-bend", CpuDifficulty.MEDIUM, gentle = true))
        // The three Classic ids are still the shipped ones.
        assertEquals(listOf("switchback", "spiral", "gauntlet"), TowerDefenceGame.LEVELS.map { it.id })
    }

    @Test
    fun statsMigrationKeepsOldRecordsAndDropsGarbage() {
        val stored = mapOf(
            "switchback:MEDIUM" to 7, "spiral:HARD" to 10, "gauntlet:EASY" to 3,
            "castle-pass:GENTLE" to 4,
            "bad key" to 5, "switchback:NIGHTMARE" to 5, "spiral:EASY" to 0, "spiral:MEDIUM" to 99, "gauntlet:HARD" to -2
        )
        val kept = TdStatsKeys.sanitize(stored)
        assertEquals(setOf("switchback:MEDIUM", "spiral:HARD", "gauntlet:EASY", "castle-pass:GENTLE"), kept.keys)
        assertEquals(7, kept["switchback:MEDIUM"])
    }

    // ---- classic coexistence --------------------------------------------------------------------------------------

    @Test
    fun classicMapsStillWorkThroughTheSameEngine() {
        val game = TowerDefenceGame()
        game.init(
            GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "P")), 0, LocalPassAndPlayTransport())
        )
        game.level = TowerDefenceGame.LEVEL_SPIRAL
        game.startMatch()
        assertFalse(game.state.value.level.isOpen)
        assertEquals(20, game.state.value.lives)
        game.placeTower(0)
        assertEquals(1, game.state.value.towers.size)
        assertNull(game.exportSnapshot())
        assertTrue(game.routePreview().isEmpty())
        assertEquals(-1, game.sellTower(0))
        // Open-field placement is refused on a Classic map.
        assertEquals(TdPlaceResult.Rejected(TdReject.UNBUILDABLE), game.placeTower(10, 10, TdRole.BOLT))
    }
}
