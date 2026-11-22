package com.gamesuite.games.botbastion

import com.gamesuite.games.towerdefence.TowerDefenceGame
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T13: bests, boards, keys, unlocks. */
class StatsAndLeaderboardTest {
    private fun entry(score: Int, wave: Int = 10, time: Int = 100, initials: String = "ABC") = BbEntry(initials, score, wave, time, 50, 20000, 1)
    private fun outcome(score: Int, wave: Int = 10, stars: Int = 1) = BbOutcome(stars > 0, wave, score, 50, 100, stars, 1)

    @Test fun keysUseTheBbPrefixAndNeverCollideWithClassic() {
        assertEquals("bb/hearth:captain", BbProfile.key("hearth", Tier.CAPTAIN))
        assertTrue(BbProfile.key("x", Tier.ENDLESS).startsWith("bb/"))
        // Classic keys are "$levelId:${difficulty}" and never start with bb/
        assertFalse(TowerDefenceGame.LEVELS.any { "${it.id}:EASY".startsWith("bb/") })
    }

    @Test fun theBoardKeepsTheTopTenInOrder() {
        var p = BbProfile()
        for (i in 1..15) p = p.record("hearth", Tier.CAPTAIN, outcome(i * 100), entry(i * 100))
        val b = p.board("hearth", Tier.CAPTAIN)
        assertEquals(10, b.size)
        assertEquals((15 downTo 6).map { it * 100 }, b.map { it.score })
        assertFalse(p.qualifies("hearth", Tier.CAPTAIN, entry(500)))
        assertTrue(p.qualifies("hearth", Tier.CAPTAIN, entry(700)))
        assertTrue(p.qualifies("twin", Tier.CAPTAIN, entry(1)))
    }

    @Test fun equalScoresRankTheFasterRunFirst() {
        val p = BbProfile().record("hearth", Tier.HERO, outcome(100), entry(100, time = 300))
            .record("hearth", Tier.HERO, outcome(100), entry(100, time = 200, initials = "ZZZ"))
        assertEquals("ZZZ", p.board("hearth", Tier.HERO).first().initials)
    }

    @Test fun bestsOnlyGoUpAndBoardsAreSeparatePerMapAndTier() {
        var p = BbProfile().record("hearth", Tier.CAPTAIN, outcome(500, 12, 2), null)
        p = p.record("hearth", Tier.CAPTAIN, outcome(300, 20, 1), null)
        assertEquals(BbBest(20, 500, 2), p.best("hearth", Tier.CAPTAIN))
        assertEquals(BbBest(), p.best("hearth", Tier.CADET))
        assertEquals(emptyList<BbEntry>(), p.board("hearth", Tier.CAPTAIN))
        assertEquals(2, p.mapStars("hearth"))
    }

    @Test fun mapsUnlockByTotalStars() {
        val maps = shippedMaps()
        var p = BbProfile()
        assertTrue(p.isUnlocked(maps.first { it.id == "hearth" }, maps))
        assertTrue(p.isUnlocked(maps.first { it.id == "twin" }, maps))
        val pebble = maps.first { it.id == "pebble" }
        assertFalse(p.isUnlocked(pebble, maps))
        p = p.record("hearth", Tier.CAPTAIN, outcome(1, 30, 3), null)
        assertEquals(3, p.totalStars(maps))
        assertTrue(p.isUnlocked(pebble, maps))
        // stars on a map are the best over tiers, not the sum
        p = p.record("hearth", Tier.CADET, outcome(1, 20, 1), null)
        assertEquals(3, p.totalStars(maps))
    }

    @Test fun initialsAreThreeLetters() {
        assertEquals("ABC", BbProfile.sanitizeInitials("abcdef"))
        assertEquals("AB-", BbProfile.sanitizeInitials("a1b!"))
        assertEquals("---", BbProfile.sanitizeInitials("  12 "))
        val p = BbProfile().record("a", Tier.CADET, outcome(5), entry(5, initials = "x<script>"))
        assertEquals("XSC", p.board("a", Tier.CADET).single().initials)
    }

    @Test fun encodeDecodeRoundTripsAndCorruptionIsHarmless() {
        val p = BbProfile().record("hearth", Tier.CAPTAIN, outcome(500, 12, 2), entry(500))
        assertEquals(p, BbProfile.decode(p.encode()))
        assertEquals(BbProfile(), BbProfile.decode(null))
        assertEquals(BbProfile(), BbProfile.decode("{{{ nope"))
        assertEquals(BbProfile(), BbProfile.decode("[1,2,3]"))
        val hostile = Json.encodeToString(
            BbProfile(
                bests = mapOf("evil" to BbBest(1, 1, 1), "bb/a:cadet" to BbBest(5, 5, 9)),
                boards = mapOf("bb/a:cadet" to List(30) { BbEntry("a!c", -1 - it, 1, 1, 1, 1, 7) })
            )
        )
        val d = BbProfile.decode(hostile)
        assertTrue(d.bests.isEmpty())
        assertTrue(d.boards["bb/a:cadet"].orEmpty().isEmpty())
    }
}

/** T14: the Classic ruleset is untouched by the new mode. */
class ClassicParityTest {
    @Test fun classicConstantsAndLevelsAreUnchanged() {
        assertEquals(20, TowerDefenceGame.STARTING_LIVES)
        assertEquals(10, TowerDefenceGame.TOTAL_WAVES)
        assertEquals(3, TowerDefenceGame.LEVELS.size)
        assertEquals("tower-defence", TowerDefenceGame().gameId)
        assertEquals("bot-bastion", BotBastionGame().gameId)
    }

    @Test fun theNewModeIsItsOwnGameModuleWithItsOwnStorage() {
        val g = BotBastionGame()
        assertEquals(1, g.minPlayers); assertEquals(4, g.maxPlayers)
        assertNotEquals(TowerDefenceGame().gameId, g.gameId)
    }
}

/** T15 and the snapshot rules: same inputs give the same game; a damaged save is refused. */
class DeterminismAndSnapshotTest {
    private val map get() = shipped("twin")

    private fun script(seed: Long, players: Int): BbEngine {
        val e = BbEngine(map, Tier.CAPTAIN, players, seed).rich(3000)
        val types = listOf(BotType.PICKET, BotType.LOBBER, BotType.HAZE, BotType.TENDER)
        for ((i, n) in map.nodes.indices.toList().take(6).withIndex()) e.place(i % players, types[i % 4], n)
        e.buySkiff(0)
        e.callEarly(0)
        for (t in 0 until 600) {
            if (players > 1 && t % 50 == 0) e.setPilotTarget(1, (t % 13).toDouble() + 1, (t % 7).toDouble() + 1)
            if (t == 100) e.upgrade(0, e.bots[0].id)
            if (t == 200) e.buyAegis(0)
            e.advance(0.1)
        }
        return e
    }

    @Test fun sameSeedAndInputsGiveIdenticalStateIncludingCoop() {
        for (players in 1..3) {
            val a = BbSnapshot.encode(script(5L, players))
            val b = BbSnapshot.encode(script(5L, players))
            assertNotNull(a)
            assertEquals(a, b)
        }
    }

    @Test fun theSeedChangesTheWaves() {
        val a = script(5L, 1); val b = script(6L, 1)
        a.run(60.0); b.run(60.0)
        assertNotEquals(BbSnapshot.encode(a), BbSnapshot.encode(b))
    }

    @Test fun aSnapshotRoundTripContinuesIdentically() {
        val e = script(9L, 2)
        val text = BbSnapshot.encode(e)!!
        val copy = BbSnapshot.decode(text, shippedMaps())
        assertNotNull(copy)
        assertEquals(text, BbSnapshot.encode(copy!!))
        e.run(30.0); copy.run(30.0)
        assertEquals(BbSnapshot.encode(e), BbSnapshot.encode(copy))
    }

    @Test fun aSnapshotRestoredDuringASwarmKeepsTheSwarmCursor() {
        val e = BbEngine(map, Tier.HERO, 1, 2L).rich()
        e.wave = 5; e.phase = Phase.WAVE; e.spawns = emptyList()
        e.step()
        e.run(3.0)
        assertEquals(1, e.segment)
        assertTrue(e.spawnIdx in 1 until 30)
        val copy = BbSnapshot.decode(BbSnapshot.encode(e)!!, shippedMaps())!!
        assertEquals(e.spawnIdx, copy.spawnIdx)
        e.run(20.0); copy.run(20.0)
        assertEquals(BbSnapshot.encode(e), BbSnapshot.encode(copy))
    }

    // ---- corruption ----

    private fun data(): BbSnapshot.Data = Json { ignoreUnknownKeys = true }.decodeFromString(BbSnapshot.encode(script(4L, 2))!!)

    private fun refuses(label: String, change: (BbSnapshot.Data) -> BbSnapshot.Data) {
        assertNull(label, BbSnapshot.restore(change(data()), shippedMaps()))
    }

    @Test fun theUntouchedSnapshotIsAccepted() {
        assertNotNull(BbSnapshot.restore(data(), shippedMaps()))
    }

    @Test fun garbageAndWrongVersionsAreRefused() {
        assertNull(BbSnapshot.decode("", shippedMaps()))
        assertNull(BbSnapshot.decode("{", shippedMaps()))
        assertNull(BbSnapshot.decode("{\"version\":1}", shippedMaps()))
        assertNull(BbSnapshot.decode("null", shippedMaps()))
        val text = BbSnapshot.encode(script(4L, 1))!!
        assertNull(BbSnapshot.decode(text.substring(0, text.length / 2), shippedMaps()))
        refuses("version") { it.copy(version = 99) }
    }

    @Test fun outOfRangeValuesAreRefused() {
        refuses("unknown map") { it.copy(mapId = "nope") }
        refuses("unknown tier") { it.copy(tier = "godlike") }
        refuses("phase won") { it.copy(phase = "WON") }
        refuses("phase junk") { it.copy(phase = "??") }
        refuses("core 0") { it.copy(coreHp = 0) }
        refuses("core 101") { it.copy(coreHp = 101) }
        refuses("negative scrap") { it.copy(scrap = it.scrap.map { -5 }) }
        refuses("scrap count") { it.copy(scrap = listOf(1)) }
        refuses("shield without aegis") { it.copy(shield = 9999) }
        refuses("mult 3") { it.copy(scrapMult = 3) }
        refuses("mult without timer") { it.copy(scoreMult = 2, scoreMultLeft = 0.0) }
        refuses("special 9") { it.copy(specials = listOf(9, 0, 0, 0)) }
        refuses("special count") { it.copy(specials = listOf(1)) }
        refuses("negative wave") { it.copy(wave = -1) }
        refuses("wave past goal") { it.copy(wave = 31) }
        refuses("time nan") { it.copy(time = Double.NaN) }
        refuses("spawn cursor") { it.copy(spawnIdx = 100_000) }
        refuses("segment") { it.copy(segment = 1) }
        refuses("next id too small") { it.copy(nextId = 1) }
    }

    @Test fun inconsistentBotsAndEnemiesAreRefused() {
        refuses("bot node") { it.copy(bots = it.bots.map { b -> b.copy(node = 999) }) }
        refuses("bot level") { it.copy(bots = it.bots.map { b -> b.copy(level = 9) }) }
        refuses("bot type") { it.copy(bots = it.bots.map { b -> b.copy(type = "mega") }) }
        refuses("bot owner") { it.copy(bots = it.bots.map { b -> b.copy(owner = 7) }) }
        refuses("bot priority") { it.copy(bots = it.bots.map { b -> b.copy(priority = "RANDOM") }) }
        refuses("duplicate node") { it.copy(bots = it.bots.filter { b -> b.node >= 0 }.let { l -> listOf(l[0], l[1].copy(node = l[0].node)) }) }
        refuses("duplicate id") { it.copy(bots = listOf(it.bots[0], it.bots[1].copy(id = it.bots[0].id))) }
        refuses("bot moved off its node") { it.copy(bots = it.bots.map { b -> if (b.node >= 0) b.copy(x = 0.0) else b }) }
        refuses("too many bots") { it.copy(bots = List(100) { i -> it.bots[0].copy(id = i + 1, node = -1, type = "skiff") }) }
        val withEnemy = { d: BbSnapshot.Data ->
            d.copy(
                bots = d.bots.filter { b -> b.id != d.nextId - 1 },
                enemies = listOf(BbSnapshot.EnemyS(d.nextId - 1, "drifter", 0, 1.0, 10.0, 30.0, 1.0, false, 0.0))
            )
        }
        assertNotNull(BbSnapshot.restore(withEnemy(data()), shippedMaps()))
        refuses("enemy lane") { withEnemy(it).let { d -> d.copy(enemies = d.enemies.map { e -> e.copy(lane = 9) }) } }
        refuses("enemy dist") { withEnemy(it).let { d -> d.copy(enemies = d.enemies.map { e -> e.copy(dist = 1e6) }) } }
        refuses("enemy hp") { withEnemy(it).let { d -> d.copy(enemies = d.enemies.map { e -> e.copy(hp = 99.0) }) } }
        refuses("enemy hp zero") { withEnemy(it).let { d -> d.copy(enemies = d.enemies.map { e -> e.copy(hp = 0.0) }) } }
        refuses("enemy type") { withEnemy(it).let { d -> d.copy(enemies = d.enemies.map { e -> e.copy(type = "dragon") }) } }
        refuses("pilot") { it.copy(pilots = emptyList()) }
        refuses("pilot hp") { it.copy(pilots = it.pilots.map { p -> p.copy(hp = 500.0) }) }
    }

    @Test fun aGameThatIsOverHasNoSnapshot() {
        val e = engine(); e.coreHp = 1
        e.put(EnemyType.DRIFTER, 15.2); e.run(0.5)
        assertTrue(e.over)
        assertNull(BbSnapshot.encode(e))
    }

    @Test fun theGameModuleSavesAndRestores() {
        val g = BotBastionGame()
        g.configure(mapOf("map" to "twin", "tier" to "hero", "players" to "2"), shippedMaps())
        g.startMatch()
        g.engine!!.rich()
        g.place(0, BotType.PICKET, 0)
        val snap = g.snapshot()
        assertNotNull(snap)
        val g2 = BotBastionGame()
        g2.configure(emptyMap(), shippedMaps())
        assertTrue(g2.restore(snap!!))
        assertEquals("twin", g2.mapId); assertEquals(Tier.HERO, g2.tier); assertEquals(2, g2.playerCount)
        assertEquals(1, g2.engine!!.bots.size)
        assertFalse(BotBastionGame().apply { configure(emptyMap(), shippedMaps()) }.restore("{}"))
    }

    @Test fun launchExtrasFallBackToSafeDefaults() {
        val g = BotBastionGame()
        g.configure(mapOf("map" to "zzz", "tier" to "??", "players" to "99"), shippedMaps())
        assertEquals(shippedMaps().first().id, g.mapId); assertEquals(Tier.CAPTAIN, g.tier); assertEquals(4, g.playerCount)
    }
}
