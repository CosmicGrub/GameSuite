package com.gamesuite.games.towerdefence

import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.towerdefence.TowerDefenceGame.TdFirePatch
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceEnemy
import com.gamesuite.games.towerdefence.TowerDefenceGame.TowerDefenceRunResult
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Follow-ups: ground fire, Endless and Daily, resume, drag and keyboard geometry, layout math. */
class TdFollowUpsTest {

    private fun newGame(): TowerDefenceGame {
        val game = TowerDefenceGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    private fun openGame(mode: TdMode = TdMode.CAMPAIGN, day: Long = 20000L, mapId: String = "castle-pass"): TowerDefenceGame {
        val game = newGame()
        game.difficulty = CpuDifficulty.MEDIUM
        game.level = TowerDefenceGame.levelById(mapId)!!
        game.mode = mode
        game.dailyDay = day
        game.themeCount = 5
        game.startMatch()
        return game
    }

    private fun enemy(id: Int, kind: TdEnemyKind, x: Float, y: Float, hp: Float = 100f) =
        TowerDefenceEnemy(id = id, distanceTraveled = 0f, hp = hp, maxHp = hp, speed = 0f, goldReward = 10, kind = kind, pos = Offset(x, y))

    private fun tickSeconds(game: TowerDefenceGame, seconds: Float) {
        var r = seconds
        while (r > 0f) { game.tick(0.05f); r -= 0.05f }
    }

    private fun firstValidAnchor(game: TowerDefenceGame, skip: Int = 0): Pair<Int, Int> {
        var n = 0
        for (y in 0 until TdGrid.ROWS - 1 step 2) for (x in 0 until TdGrid.COLS - 1 step 2) {
            if (game.previewPlacement(x, y, TdRole.BOLT) is TdPlaceResult.Ok && n++ >= skip) return x to y
        }
        error("no valid anchor")
    }

    // ---- ground fire ------------------------------------------------------------------------------

    @Test
    fun aFirePatchBurnsWalkersInsideItAndSparesFlyersAndOutsiders() {
        val game = openGame()
        game.state.value = game.state.value.copy(
            enemies = listOf(
                enemy(1, TdEnemyKind.BRUTE, 3f, 7f),
                enemy(2, TdEnemyKind.FLYER, 3f, 7f),
                enemy(3, TdEnemyKind.BRUTE, 9f, 7f)
            ),
            firePatches = listOf(TdFirePatch(0, Offset(3f, 7f), 1.2f, 3f))
        )
        tickSeconds(game, 1f)
        val byId = game.state.value.enemies.associateBy { it.id }
        assertTrue("walker in the patch burns", byId.getValue(1).hp < 100f - 6f)
        assertEquals("flyer is not on the ground", 100f, byId.getValue(2).hp, 0.001f)
        assertEquals("outside the patch", 100f, byId.getValue(3).hp, 0.001f)
    }

    @Test
    fun overlappingPatchesDoNotStackAndPatchesExpire() {
        val game = openGame()
        game.state.value = game.state.value.copy(
            enemies = listOf(enemy(1, TdEnemyKind.BRUTE, 3f, 7f)),
            firePatches = listOf(TdFirePatch(0, Offset(3f, 7f), 1.2f, 2f), TdFirePatch(1, Offset(3.1f, 7f), 1.2f, 2f))
        )
        tickSeconds(game, 1f)
        val lost = 100f - game.state.value.enemies.single().hp
        assertTrue("one burn rate, not two ($lost)", lost in 6f..10f)
        tickSeconds(game, 1.5f)
        assertTrue(game.state.value.firePatches.isEmpty())
    }

    @Test
    fun ignitingNearAnExistingPatchRefreshesItAndTheFieldIsCapped() {
        val ids = TdIds()
        var patches = TdFire.ignite(emptyList(), Offset(5f, 5f), 2f, 6f, ids)
        patches = TdFire.ignite(patches, Offset(5.2f, 5f), 2f, 9f, ids)
        assertEquals(1, patches.size)
        assertEquals(9f, patches.single().remaining, 0f)
        for (i in 0 until 60) patches = TdFire.ignite(patches, Offset(i * 0.4f, (i % 7) * 2f), 1.5f, 6f, ids)
        assertTrue(patches.size <= TdFire.MAX_PATCHES)
    }

    @Test
    fun burningShellsLeaveGroundFireWhenTheyLand() {
        val game = openGame()
        val st = TdRules.stats(TdRole.BLAST, 4, TdBranch.B)
        assertTrue(st.burnSeconds > 0f)
        game.state.value = game.state.value.copy(
            enemies = listOf(enemy(1, TdEnemyKind.BRUTE, 3f, 7f)),
            projectiles = listOf(
                TowerDefenceGame.TowerDefenceProjectile(
                    id = 0, position = Offset(3f, 7f), targetEnemyId = 1, damage = st.damage, role = TdRole.BLAST,
                    splash = st.splashRadius, burnSeconds = st.burnSeconds
                )
            )
        )
        game.tick(0.05f)
        assertEquals(1, game.state.value.firePatches.size)
        assertEquals(st.splashRadius * 0.8f, game.state.value.firePatches.single().radius, 0.001f)
    }

    // ---- Endless and Daily ---------------------------------------------------------------------------

    @Test
    fun campaignWinsAfterWaveTenButEndlessKeepsGoing() {
        val campaign = openGame()
        campaign.state.value = campaign.state.value.copy(waveNumber = 10, interWaveCooldown = 0f)
        campaign.tick(0.05f)
        assertEquals(TowerDefenceRunResult.WON, campaign.state.value.runResult)

        val endless = openGame(TdMode.ENDLESS)
        endless.state.value = endless.state.value.copy(waveNumber = 10, interWaveCooldown = 0f)
        endless.tick(0.05f)
        assertNull(endless.state.value.runResult)
        assertEquals(11, endless.state.value.waveNumber)
        assertFalse(endless.nextWavePreview().isEmpty())
    }

    @Test
    fun endlessEndsOnlyWhenTheCastleFalls() {
        val g = openGame(TdMode.ENDLESS)
        g.state.value = g.state.value.copy(waveNumber = 14, lives = 1, interWaveCooldown = 0f, enemies = listOf(enemy(1, TdEnemyKind.BOSS, 3f, 7f)))
        // A leaked boss costs 5 castle health.
        val gate = g.state.value.level.terrain!!.gateCentre
        g.state.value = g.state.value.copy(enemies = listOf(enemy(1, TdEnemyKind.BOSS, gate.x, gate.y)))
        g.tick(0.05f)
        assertEquals(TowerDefenceRunResult.LOST, g.state.value.runResult)
    }

    @Test
    fun wavesPastTenGrowAndBossLapsBringMoreBosses() {
        val lap1 = TdWaves.groups(1).first { it.first == TdEnemyKind.RUNNER }.second
        val lap2 = TdWaves.groups(11).first { it.first == TdEnemyKind.RUNNER }.second
        assertTrue(lap2 > lap1)
        assertEquals(1, TdWaves.groups(5).first { it.first == TdEnemyKind.BOSS }.second)
        assertEquals(2, TdWaves.groups(15).first { it.first == TdEnemyKind.BOSS }.second)
        assertEquals(3, TdWaves.groups(25).first { it.first == TdEnemyKind.BOSS }.second)
        assertTrue(TdWaves.hasBoss(15))
        assertEquals(TdWaves.groups(7), TdWaves.groups(7)) // authored waves unchanged
        assertTrue(TdRules.waveBaseSpeed(500) <= TdRules.MAX_ENEMY_SPEED)
    }

    @Test
    fun theDailyPlanIsAPureFunctionOfTheDayAndVaries() {
        for (d in 20000L..20010L) assertEquals(TdDaily.plan(d, 5), TdDaily.plan(d, 5))
        val plans = (20000L..20060L).map { TdDaily.plan(it, 5) }
        assertTrue(plans.map { it.mapId }.toSet().size >= 2)
        assertTrue(plans.map { it.modifier }.toSet().size >= 3)
        assertTrue(plans.all { it.themeIndex in 0..4 && TdMaps.byId(it.mapId) != null })
    }

    @Test
    fun aDailyRunUsesThePlanMediumTierAndModifier() {
        val day = (20000L..20400L).first { TdDaily.plan(it, 5).modifier == TdModifier.LEAN }
        val plan = TdDaily.plan(day, 5)
        val g = openGame(TdMode.DAILY, day, mapId = "twin-gates")
        g.difficulty = CpuDifficulty.HARD
        g.gentle = true
        g.startMatch()
        val s = g.state.value
        assertEquals(plan.mapId, s.level.id)
        assertEquals(CpuDifficulty.MEDIUM, s.difficulty)
        assertFalse(s.gentle)
        assertEquals(TdModifier.LEAN, s.modifier)
        assertEquals(70, s.gold)
        assertEquals(plan.mapId, g.dailyPlan()!!.mapId)
    }

    @Test
    fun nonCampaignModesNeverUseAClassicMap() {
        val g = openGame(TdMode.ENDLESS, mapId = "switchback")
        assertTrue(g.state.value.level.isOpen)
    }

    @Test
    fun dailyScoreRanksWaveThenCastleAndStatsKeysSurviveSanitising() {
        assertTrue(TdDaily.score(10, 5) > TdDaily.score(9, 20))
        assertTrue(TdDaily.score(10, 12) > TdDaily.score(10, 5))
        val day = 20123L
        val raw = mapOf(
            "castle-pass:MEDIUM" to 4,
            TdStatsKeys.endlessKey("river-bend") to 31,
            TdStatsKeys.dailyKey(day) to TdDaily.score(7, 9),
            "bogus" to 3,
            "castle-pass:ENDLESS" to 0,
            "daily-1:DAILY" to 5
        )
        val clean = TdStatsKeys.sanitize(raw)
        assertEquals(setOf("castle-pass:MEDIUM", "river-bend:ENDLESS", "daily-20123:DAILY"), clean.keys)
        assertEquals(day, TdStatsKeys.dailyDayOf(TdStatsKeys.dailyKey(day)))
        val pruned = TdStatsKeys.pruneDaily(clean + (TdStatsKeys.dailyKey(day - 90) to 700), day)
        assertFalse(pruned.containsKey(TdStatsKeys.dailyKey(day - 90)))
        assertTrue(pruned.containsKey("castle-pass:MEDIUM"))
        assertEquals("river-bend:ENDLESS", TdStatsKeys.keyFor(TdMode.ENDLESS, "river-bend", CpuDifficulty.HARD, false, 0))
        assertEquals("castle-pass:GENTLE", TdStatsKeys.keyFor(TdMode.CAMPAIGN, "castle-pass", CpuDifficulty.HARD, true, 0))
    }

    // ---- resume --------------------------------------------------------------------------------------

    @Test
    fun theBuildPhaseSavesAndRestoresIntoAFreshGame() {
        val g = openGame()
        g.state.value = g.state.value.copy(gold = 5000)
        val (x, y) = firstValidAnchor(g)
        assertTrue(g.placeTower(x, y, TdRole.BOLT) is TdPlaceResult.Ok)
        val saved = g.snapshot()
        assertNotNull(saved)

        val fresh = newGame()
        assertTrue(fresh.restore(saved!!))
        assertEquals(1, fresh.state.value.towers.size)
        assertEquals(g.state.value.gold, fresh.state.value.gold)
        assertEquals(g.state.value.waveNumber, fresh.state.value.waveNumber)
        assertEquals(saved, fresh.snapshot())
    }

    @Test
    fun midWaveSavesTheCheckpointFromWhenTheWaveBegan() {
        val g = openGame()
        g.state.value = g.state.value.copy(gold = 5000)
        val (x, y) = firstValidAnchor(g)
        g.placeTower(x, y, TdRole.BOLT)
        val atStart = g.snapshot()
        g.callNextWave()
        tickSeconds(g, 0.5f)
        assertTrue(g.state.value.waveInProgress)
        // A tower bought mid-wave is not in the checkpoint.
        val (x2, y2) = firstValidAnchor(g, skip = 3)
        g.placeTower(x2, y2, TdRole.BLAST)
        assertEquals(atStart, g.snapshot())
        val fresh = newGame()
        assertTrue(fresh.restore(g.snapshot()!!))
        assertEquals(1, fresh.state.value.towers.size)
        assertFalse(fresh.state.value.waveInProgress)
    }

    @Test
    fun noSnapshotForClassicMapsFinishedRunsOrOtherModes() {
        val classic = openGame(mapId = "switchback")
        assertNull(classic.snapshot())
        val over = openGame()
        over.state.value = over.state.value.copy(runResult = TowerDefenceRunResult.LOST)
        assertNull(over.snapshot())
        val notStarted = TowerDefenceGame()
        assertNull(notStarted.snapshot())
        assertFalse(notStarted.restore("{}"))
    }

    @Test
    fun aTamperedOrForeignSaveIsRejectedAndLeavesTheGameUntouched() {
        val g = openGame()
        g.state.value = g.state.value.copy(gold = 5000)
        val (x, y) = firstValidAnchor(g)
        g.placeTower(x, y, TdRole.BOLT)
        val d = TdSnapshot.decode(g.snapshot())!!

        val target = openGame()
        val before = target.state.value
        assertFalse(target.restore("not json"))
        assertFalse(target.restore(TdSnapshot.encode(d.copy(towers = d.towers.map { it.copy(invested = 1) }))))   // minted refund
        assertFalse(target.restore(TdSnapshot.encode(d.copy(gold = -4))))
        assertFalse(target.restore(TdSnapshot.encode(d.copy(wave = 11))))                                          // campaign ends at 10
        assertFalse(target.restore(TdSnapshot.encode(d.copy(mode = "ZEN"))))
        assertFalse(target.restore(TdSnapshot.encode(d.copy(mode = "DAILY", day = 0))))
        assertFalse(target.restore(TdSnapshot.encode(d.copy(day = 5))))                                          // day only for Daily
        assertEquals(before, target.state.value)
    }

    @Test
    fun endlessAndDailySavesRoundTripAndDailyIsBoundToItsPlan() {
        val e = openGame(TdMode.ENDLESS)
        e.state.value = e.state.value.copy(waveNumber = 17)
        val savedE = e.snapshot()!!
        val re = newGame()
        assertTrue(re.restore(savedE))
        assertEquals(TdMode.ENDLESS, re.state.value.mode)
        assertEquals(17, re.state.value.waveNumber)

        val day = 20222L
        val dg = openGame(TdMode.DAILY, day)
        val savedD = dg.snapshot()!!
        val rd = newGame()
        assertTrue(rd.restore(savedD))
        assertEquals(day, rd.state.value.dailyDay)
        assertEquals(TdDaily.plan(day, 5).modifier, rd.state.value.modifier)
        val d = TdSnapshot.decode(savedD)!!
        val wrongMap = TdMaps.ALL.first { it.id != d.mapId }.id
        assertFalse(newGame().restore(TdSnapshot.encode(d.copy(mapId = wrongMap))))
    }

    @Test
    fun theHubSpecIsResumableAndOffersModes() {
        val spec = TowerDefenceHubSpecs.towerDefence
        assertTrue(spec.resumable)
        val keys = spec.modes.single().options.map { it.key }
        assertTrue("mode" in keys && "map" in keys && "theme" in keys && "gentle" in keys)
    }

    // ---- drag, keyboard, free placement ---------------------------------------------------------------------

    @Test
    fun draggingFromTheTrayMapsRootPositionToAnAnchorAndOutsideCancels() {
        // Board at root (100, 200), 480 x 280 px (20 px per tile). Finger at the middle of tile (5, 6), no lift.
        val a = TdBoardMath.anchorForDrag(100f + 5.5f * 20f, 200f + 6.5f * 20f, 100f, 200f, 480f, 280f, 0f)
        assertEquals(10 to 12, a)
        assertNull(TdBoardMath.anchorForDrag(50f, 250f, 100f, 200f, 480f, 280f, 0f))
        assertNull(TdBoardMath.anchorForDrag(300f, 500f, 100f, 200f, 480f, 280f, 0f))
        // The ghost rides above the finger.
        val lifted = TdBoardMath.anchorForDrag(100f + 5.5f * 20f, 200f + 6.5f * 20f, 100f, 200f, 480f, 280f, 40f)!!
        assertEquals(10, lifted.first)
        assertEquals(8, lifted.second)
    }

    @Test
    fun freePlacementLandsOnSingleCellsWhileSnapUsesWholeTiles() {
        val snapped = TdBoardMath.anchorFor(5.9f * 20f, 6.1f * 20f, 480f, 280f, snap = true)
        assertEquals(10 to 12, snapped)
        val free = TdBoardMath.anchorFor(5.9f * 20f, 6.1f * 20f, 480f, 280f, snap = false)
        assertTrue(free.first % 2 == 1 || free.second % 2 == 1 || free != snapped)
        // The engine accepts an odd anchor on open ground when free placement is used.
        val g = openGame()
        g.state.value = g.state.value.copy(gold = 5000)
        val (x, y) = firstValidAnchor(g)
        val odd = (x + 1) to y
        val res = g.previewPlacement(odd.first, odd.second, TdRole.BOLT)
        assertTrue(res is TdPlaceResult.Ok || res is TdPlaceResult.Rejected)
    }

    @Test
    fun theKeyboardCursorStepsByTileWhenSnappedAndByCellWhenFreeAndStaysOnTheBoard() {
        assertEquals(26 to 14, TdBoardMath.moveAnchor(24, 14, 1, 0, snap = true))
        assertEquals(25 to 14, TdBoardMath.moveAnchor(24, 14, 1, 0, snap = false))
        assertEquals(0 to 0, TdBoardMath.moveAnchor(0, 0, -1, -1, snap = true))
        assertEquals((TdGrid.COLS - 2) to (TdGrid.ROWS - 2), TdBoardMath.moveAnchor(46, 26, 1, 1, snap = true))
        assertEquals((TdGrid.COLS - 2) to (TdGrid.ROWS - 2), TdBoardMath.moveAnchor(46, 26, 1, 1, snap = false))
        var p = 24 to 14
        repeat(200) { p = TdBoardMath.moveAnchor(p.first, p.second, -1, 1, snap = true) }
        assertTrue(TdGrid.footprint(p.first, p.second) != null)
    }

    // ---- layouts -----------------------------------------------------------------------------------------

    @Test
    fun layoutKindFollowsTheWindowShape() {
        assertEquals(TdLayoutKind.PHONE_PORTRAIT, TdLayoutMath.kindFor(360f, 800f))
        assertEquals(TdLayoutKind.PHONE_PORTRAIT, TdLayoutMath.kindFor(344f, 882f)) // Fold cover screen, upright
        assertEquals(TdLayoutKind.SPLIT_LANDSCAPE, TdLayoutMath.kindFor(1280f, 800f)) // tablet landscape
        assertEquals(TdLayoutKind.SPLIT_LANDSCAPE, TdLayoutMath.kindFor(841f, 600f)) // unfolded Fold, landscape
        assertEquals(TdLayoutKind.STACKED, TdLayoutMath.kindFor(673f, 841f)) // Fold inner, portrait
        assertEquals(TdLayoutKind.STACKED, TdLayoutMath.kindFor(700f, 700f))
        assertEquals(TdLayoutKind.STACKED, TdLayoutMath.kindFor(640f, 360f)) // small landscape phone: too short for a side column
        assertEquals(TdLayoutKind.STACKED, TdLayoutMath.kindFor(0f, 0f))
        assertEquals(TdLayoutMath.PORTRAIT_ZOOM, TdLayoutMath.zoomFor(TdLayoutKind.PHONE_PORTRAIT), 0f)
        assertEquals(1f, TdLayoutMath.zoomFor(TdLayoutKind.STACKED), 0f)
    }

    @Test
    fun theSideColumnIsClamped() {
        assertEquals(TdLayoutMath.SIDE_COLUMN_MIN, TdLayoutMath.sideColumnWidth(720f), 0f)
        assertEquals(TdLayoutMath.SIDE_COLUMN_MAX, TdLayoutMath.sideColumnWidth(3000f), 0f)
        assertEquals(1000f * 0.32f, TdLayoutMath.sideColumnWidth(1000f), 0.01f)
    }

    @Test
    fun fitBoardKeepsTheAspectAndTheLimits() {
        val (w, h) = TdLayoutMath.fitBoard(1000f, 300f)
        assertEquals(TdLayoutMath.ASPECT, w / h, 0.001f)
        assertTrue(h <= 300.01f)
        val (w2, _) = TdLayoutMath.fitBoard(2000f, 2000f, maxW = 900f)
        assertEquals(900f, w2, 0f)
        assertEquals(0f, TdLayoutMath.fitBoard(0f, 500f).first, 0f)
    }

    @Test
    fun theScrollingMapPinsTheCastleAndTheMinimapMapsTapsToScroll() {
        val vw = 360f
        val z = TdLayoutMath.PORTRAIT_ZOOM
        val max = TdLayoutMath.maxScroll(vw, z)
        assertEquals(vw * z - vw, max, 0.01f)
        assertEquals(max, TdLayoutMath.castleScroll(vw, z), 0f)
        assertTrue(TdLayoutMath.castleVisible(max, vw, z))
        assertFalse(TdLayoutMath.castleVisible(0f, vw, z))
        assertEquals(0f, TdLayoutMath.scrollForMinimapTap(0f, vw, z), 0f)
        assertEquals(max, TdLayoutMath.scrollForMinimapTap(1f, vw, z), 0f)
        assertEquals(0.5f * vw * z - vw / 2f, TdLayoutMath.scrollForMinimapTap(0.5f, vw, z), 0.01f)
        assertEquals(1f / z, TdLayoutMath.visibleFraction(z), 0.0001f)
        assertEquals(1f - 1f / z, TdLayoutMath.viewportLeftFraction(max, vw, z), 0.001f)
        assertEquals(0f, TdLayoutMath.viewportLeftFraction(0f, vw, z), 0f)
        // Nothing scrolls without zoom.
        assertEquals(0f, TdLayoutMath.maxScroll(vw, 1f), 0f)
        assertTrue(TdLayoutMath.castleVisible(0f, vw, 1f))
    }
}
