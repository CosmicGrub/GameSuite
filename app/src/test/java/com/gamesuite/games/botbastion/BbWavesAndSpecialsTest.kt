package com.gamesuite.games.botbastion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T7: the wave generator. */
class WaveGeneratorTest {
    private val map = shipped("twin")

    @Test fun countFollowsTwentyPlusThreePerWaveCappedAtNinety() {
        for (w in listOf(1, 2, 5, 10, 20, 30, 40)) {
            val n = minOf(90, 20 + 3 * (w - 1))
            assertEquals(n, BbRules.waveCount(w))
            assertEquals("common $w", n, BbWaves.commonCounts(w).sumOf { it.second })
        }
        assertEquals(20, BbRules.waveCount(1))
        assertEquals(90, BbRules.waveCount(30))
        assertEquals(90, BbRules.waveCount(40))
    }

    @Test fun hpGrowsByElevenPercentPerWaveAndMatchesTheSampleTable() {
        assertEquals(1.0, BbRules.hpGrowth(1), 1e-9)
        assertEquals(2.56, BbRules.hpGrowth(10), 0.01)
        assertEquals(7.26, BbRules.hpGrowth(20), 0.01)
        assertEquals(20.62, BbRules.hpGrowth(30), 0.02)
        val s = BbWaves.mainSpawns(map, Tier.CAPTAIN, 1, 1L, 10).first { it.type == EnemyType.DRIFTER }
        assertEquals(77.0, EnemyType.DRIFTER.hp * s.hpMult, 0.6)
        val h = BbWaves.mainSpawns(map, Tier.HERO, 1, 1L, 1).first()
        assertEquals(1.25, h.hpMult, 1e-9)
    }

    @Test fun compositionFollowsTheTable() {
        assertEquals(listOf(20, 0, 0, 0), BbWaves.commonCounts(1).map { it.second })
        val w4 = BbWaves.commonCounts(4).map { it.second }
        assertEquals(29, w4.sum()); assertEquals(6, w4[1]); assertEquals(0, w4[2])
        val w7 = BbWaves.commonCounts(7).associate { it.first to it.second }
        assertTrue(w7[EnemyType.PLATER]!! > 0 && w7[EnemyType.SPLITTER]!! > 0)
        val w25 = BbWaves.commonCounts(25).associate { it.first to it.second }
        assertEquals(0.25, w25[EnemyType.DARTER]!! / 90.0, 0.02)
        assertEquals(0.30, w25[EnemyType.DRIFTER]!! / 90.0, 0.02)
    }

    @Test fun aCourierEveryFifthWaveAndABossEveryTenth() {
        for (w in 1..45) {
            val s = BbWaves.mainSpawns(map, Tier.CAPTAIN, 1, 9L, w)
            assertEquals("courier $w", if (w % 5 == 0) 1 else 0, s.count { it.type == EnemyType.COURIER })
            assertEquals("boss $w", if (w % 10 == 0) 1 else 0, s.count { it.type == EnemyType.BEHEMOTH })
            assertEquals(BbRules.waveCount(w) + (if (w % 5 == 0) 1 else 0) + (if (w % 10 == 0) 1 else 0), s.size)
            assertEquals(s.sortedBy { it.t }, s)
            assertTrue(s.all { it.lane in map.lanes.indices && it.t in 0.0..BbRules.SPAWN_WINDOW })
        }
    }

    @Test fun swarmsFollowTheTierInterval() {
        assertFalse((1..40).any { BbWaves.swarmAfter(Tier.CADET, it) })
        assertFalse((1..40).any { BbWaves.swarmAfter(Tier.CAPTAIN, it) })
        assertEquals(listOf(5, 10, 15, 20, 25, 30, 35, 40), (1..40).filter { BbWaves.swarmAfter(Tier.HERO, it) })
        assertEquals((1..13).map { it * 3 }, (1..40).filter { BbWaves.swarmAfter(Tier.INSANITY, it) })
        assertTrue(BbWaves.swarmAfter(Tier.ENDLESS, 50))
        val s = BbWaves.swarmSpawns(map, Tier.HERO, 1, 5)
        assertEquals(30, s.size)
        assertTrue(s.all { it.type == EnemyType.DARTER && it.swarm && it.speedMult == 1.4 && it.t < 8.0 })
    }

    @Test fun theEngineRunsASwarmAfterTheWaveAndSwarmKillsPayNoScrap() {
        val e = engine(flatMap(), Tier.HERO).rich()
        e.wave = 5; e.phase = Phase.WAVE; e.spawns = emptyList()
        e.place(0, BotType.LOBBER, 3)
        e.step()
        assertEquals(1, e.segment)
        assertEquals(30, e.spawns.size)
        assertTrue(e.drainEvents().any { it is BbEvent.SwarmStart })
        val before = e.players[0].scrap
        e.run(12.0)
        val kills = e.drainEvents().filterIsInstance<BbEvent.Kill>().filter { it.type == EnemyType.DARTER }
        assertTrue(kills.isNotEmpty())
        assertTrue(kills.all { it.scrap == 0 && it.points > 0 })
        assertEquals(before, e.players[0].scrap)
    }

    @Test fun sameSeedSameWaveDifferentSeedDifferentOrder() {
        assertEquals(BbWaves.mainSpawns(map, Tier.CAPTAIN, 1, 5L, 8), BbWaves.mainSpawns(map, Tier.CAPTAIN, 1, 5L, 8))
        assertNotEquals(BbWaves.mainSpawns(map, Tier.CAPTAIN, 1, 5L, 8), BbWaves.mainSpawns(map, Tier.CAPTAIN, 1, 6L, 8))
    }

    @Test fun coopScalesHpAndTheClearBonusesFollowTheSampleTable() {
        for ((n, m) in listOf(1 to 1.0, 2 to 1.2, 3 to 1.4, 4 to 1.6)) assertEquals(m, BbWaves.hpMult(1, Tier.CAPTAIN, n), 1e-9)
        assertEquals(25, BbRules.clearScrap(1)); assertEquals(110, BbRules.clearScore(1))
        assertEquals(120, BbRules.clearScrap(20)); assertEquals(300, BbRules.clearScore(20))
        assertEquals(220, BbRules.clearScrap(40)); assertEquals(500, BbRules.clearScore(40))
    }

    @Test fun clearingAWaveGivesTheBonusAndOpensABuildPhase() {
        val e = engine().rich(0)
        e.callEarly(0)
        assertEquals(Phase.WAVE, e.phase)
        e.spawns = emptyList()
        e.step()
        assertEquals(Phase.BUILD, e.phase)
        assertEquals(25, e.players[0].scrap - 30)
        assertEquals(110, e.score)
        assertEquals(BbRules.BUILD_SECONDS, e.buildLeft, 1e-9)
    }

    @Test fun callEarlyPaysThirtyAndOnlyInABuildPhase() {
        val e = engine()
        val s = e.players[0].scrap
        assertEquals(BbResult.OK, e.callEarly(0))
        assertEquals(s + 30, e.players[0].scrap)
        assertEquals(BbResult.NOTHING_TO_DO, e.callEarly(0))
    }

    @Test fun theBuildTimerStartsTheWaveByItself() {
        val e = engine()
        e.run(BbRules.FIRST_BUILD_SECONDS + 0.5)
        assertEquals(Phase.WAVE, e.phase)
        assertEquals(1, e.wave)
    }
}

/** T8: specials. */
class SpecialsTest {
    private fun withSpecials(): BbEngine {
        val e = engine().rich()
        for (i in e.specials.indices) e.specials[i] = 3
        return e
    }

    @Test fun surgeStacksToFourThenRefusesAndExpiresAfterThirtySeconds() {
        val e = withSpecials()
        assertEquals(BbResult.OK, e.useSpecial(0, SpecialKind.SCRAP_SURGE)); assertEquals(2, e.scrapMult)
        assertEquals(BbResult.OK, e.useSpecial(0, SpecialKind.SCRAP_SURGE)); assertEquals(4, e.scrapMult)
        assertEquals(BbResult.CAP, e.useSpecial(0, SpecialKind.SCRAP_SURGE)); assertEquals(4, e.scrapMult)
        assertEquals(1, e.specials[SpecialKind.SCRAP_SURGE.ordinal])
        e.run(29.0); assertEquals(4, e.scrapMult)
        e.run(1.5); assertEquals(1, e.scrapMult)
    }

    @Test fun scoreAndScrapMultipliersApplySeparately() {
        val e = withSpecials()
        e.place(0, BotType.LOBBER, 3)
        e.useSpecial(0, SpecialKind.SCRAP_SURGE)
        e.put(EnemyType.DRIFTER, 7.0, hp = 10.0)
        var s0 = e.players[0].scrap
        e.step()
        assertEquals(12, e.players[0].scrap - s0); assertEquals(10, e.killScore)
        e.useSpecial(0, SpecialKind.SCORE_BOOST)
        e.put(EnemyType.DRIFTER, 7.0, hp = 10.0)
        s0 = e.players[0].scrap
        e.run(3.0)
        assertEquals(12, e.players[0].scrap - s0); assertEquals(30, e.killScore)
        assertEquals(2, e.scoreMult)
    }

    @Test fun novaKillsEverythingOnScreenAndTheBossLosesAQuarter() {
        val e = withSpecials()
        e.put(EnemyType.DRIFTER, 5.0); e.put(EnemyType.PLATER, 8.0); e.put(EnemyType.SPLITTER, 9.0)
        val boss = e.put(EnemyType.BEHEMOTH, 10.0)
        val offscreen = e.put(EnemyType.DRIFTER, 0.0)
        assertEquals(BbResult.OK, e.useSpecial(0, SpecialKind.NOVA_PULSE))
        assertEquals(setOf(boss, offscreen), e.enemies.toSet())
        assertEquals(1125.0, boss.hp, 1e-9)
        assertEquals(2, e.specials[SpecialKind.NOVA_PULSE.ordinal])
        assertTrue(e.killScore >= 10 + 35 + 25)
        assertEquals(0, e.enemies.count { it.type == EnemyType.BIT })
    }

    @Test fun usingAnEmptySlotDoesNothing() {
        val e = engine()
        assertEquals(BbResult.NOTHING_TO_DO, e.useSpecial(0, SpecialKind.NOVA_PULSE))
    }

    @Test fun inventoryCapsAtThreePerKind() {
        val e = engine()
        var dropped = 0
        for (key in 0L until 200L) {
            e.awardSpecial(key)
            dropped += e.drainEvents().count { it is BbEvent.SpecialGained && !it.kept }
        }
        assertTrue(e.specials.all { it == 3 })
        assertTrue(dropped > 0)
    }

    @Test fun aboutThirtyPercentOfAwardsAreRareAndTheRollIsDeterministic() {
        val rare = (0L until 2000L).count { k ->
            val e = engine(); e.awardSpecial(k)
            (e.drainEvents().single() as BbEvent.SpecialGained).kind.rare
        }
        assertEquals(0.30, rare / 2000.0, 0.04)
        val a = engine(); a.awardSpecial(7)
        val b = engine(); b.awardSpecial(7)
        assertEquals(a.specials.toList(), b.specials.toList())
    }

    @Test fun aWaveFiveClearAwardsOneSpecial() {
        val e = engine()
        e.wave = 4
        e.callEarly(0)
        assertEquals(5, e.wave)
        e.spawns = emptyList()
        e.step()
        assertEquals(1, e.specials.sum())
    }
}

/** T10: score, stars, win bonus. */
class ScoringAndStarsTest {
    @Test fun winBonusIsCoreAndTime() {
        // kill score 1000, Core 80% -> 0.5% x 80 = 40% = 400; goal 20 waves -> par 800 s; 500 s taken -> 300
        assertEquals(400 + 300, BbScoring.winBonus(1000, 80, 500.0, 20))
        assertEquals(0 + 0, BbScoring.winBonus(1000, 0, 900.0, 20))
        assertEquals(500, BbScoring.winBonus(1000, 100, 800.0, 20))
    }

    @Test fun starRules() {
        assertEquals(0, BbScoring.stars(false, Tier.HERO, 100))
        assertEquals(1, BbScoring.stars(true, Tier.CADET, 40))
        assertEquals(1, BbScoring.stars(true, Tier.CAPTAIN, 50))
        assertEquals(2, BbScoring.stars(true, Tier.CAPTAIN, 51))
        assertEquals(2, BbScoring.stars(true, Tier.CAPTAIN, 80))
        assertEquals(3, BbScoring.stars(true, Tier.CAPTAIN, 81))
        assertEquals(3, BbScoring.stars(true, Tier.HERO, 5))
        assertEquals(3, BbScoring.stars(true, Tier.INSANITY, 5))
        assertEquals(3, BbScoring.stars(true, Tier.CADET, 90))
        assertEquals(0, BbScoring.stars(true, Tier.ENDLESS, 100))
    }

    @Test fun anEngineWinGivesTheBonusesAndStars() {
        val e = engine(tier = Tier.CADET)
        e.wave = 20; e.phase = Phase.WAVE; e.spawns = emptyList(); e.killScore = 1000
        e.time = 500.0
        e.step()
        assertEquals(Phase.WON, e.phase)
        val o = e.outcome()
        assertTrue(o.won)
        assertEquals(20, o.wave)
        assertEquals(3, o.stars)
        assertEquals(1000 + BbRules.clearScore(20) + BbScoring.winBonus(1000, 100, e.time, 20), o.score)
        assertTrue(e.over)
        assertEquals(null, BbSnapshot.encode(e))
    }

    @Test fun endlessNeverWins() {
        val e = engine(tier = Tier.ENDLESS)
        e.wave = 61; e.phase = Phase.WAVE; e.spawns = emptyList()
        e.step()
        assertEquals(Phase.BUILD, e.phase)
        assertEquals(61, e.wave)
    }

    @Test fun endlessRanksByWavesThenScore() {
        val a = BbEntry("AAA", 500, 12, 100, 50, 1, 1)
        val b = BbEntry("BBB", 9000, 9, 100, 50, 1, 1)
        val c = BbEntry("CCC", 800, 12, 100, 50, 1, 1)
        assertEquals(listOf(c, a, b), listOf(a, b, c).sortedWith(BbProfile.comparator(Tier.ENDLESS)))
        assertEquals(listOf(b, c, a), listOf(a, b, c).sortedWith(BbProfile.comparator(Tier.CAPTAIN)))
    }
}

/** T12: co-op. */
class CoopRulesTest {
    @Test fun playersAndPilots() {
        val e = engine(players = 3)
        assertEquals(3, e.players.size)
        assertEquals(2, e.pilots.size)
        assertEquals(listOf(1, 2), e.pilots.map { it.pid })
        assertEquals(1, engine(players = 1).players.size)
        assertEquals(4, engine(players = 9).players.size)
        assertEquals(0, engine(players = 1).pilots.size)
    }

    @Test fun anyoneBuildsAnywhereAndSellingIsOwnerOnly() {
        val e = engine(players = 2).rich()
        assertEquals(BbResult.OK, e.place(1, BotType.PICKET, 0))
        assertEquals(1, e.bots[0].owner)
        assertEquals(BbResult.TAKEN, e.place(0, BotType.PICKET, 0))
        assertEquals(BbResult.NOT_OWNER, e.sell(0, e.bots[0].id))
        assertEquals(BbResult.OK, e.sell(1, e.bots[0].id))
    }

    @Test fun upgradingSomeoneElsesBotCostsYouAndRebatesTenPercent() {
        val e = engine(players = 2).rich(1000)
        e.place(1, BotType.PICKET, 0)
        val id = e.bots[0].id
        val p0 = e.players[0].scrap; val p1 = e.players[1].scrap
        assertEquals(BbResult.OK, e.upgrade(0, id))
        assertEquals(p0 - 40, e.players[0].scrap)
        assertEquals(p1 + 4, e.players[1].scrap)
        assertEquals(100, e.bots[0].invested)
    }

    @Test fun giftingNeedsTenAndTwoDifferentPlayers() {
        val e = engine(players = 2).rich(100)
        assertEquals(BbResult.BAD_ARGUMENT, e.gift(0, 1, 9))
        assertEquals(BbResult.BAD_ARGUMENT, e.gift(0, 0, 50))
        assertEquals(BbResult.NO_SCRAP, e.gift(0, 1, 500))
        assertEquals(BbResult.OK, e.gift(0, 1, 40))
        assertEquals(60, e.players[0].scrap); assertEquals(140, e.players[1].scrap)
    }

    @Test fun killsPayTheOwnerOfTheBotThatMadeThem() {
        val e = engine(players = 2).rich(500)
        e.place(1, BotType.LOBBER, 3)
        val s0 = e.players[0].scrap; val s1 = e.players[1].scrap
        e.put(EnemyType.DRIFTER, 7.0, hp = 1.0)
        e.step()
        assertEquals(s0, e.players[0].scrap)
        assertEquals(s1 + 6, e.players[1].scrap)
        assertEquals(1, e.players[1].kills)
    }

    @Test fun enemyHpScalesWithPlayersAtWaveStart() {
        val e = engine(players = 4)
        e.callEarly(0)
        assertEquals(30.0 * 1.6, e.enemies.firstOrNull()?.maxHp ?: (e.spawns.first().type.hp * e.spawns.first().hpMult), 1e-9)
    }

    @Test fun aPilotSteersAndFiresAtTheClosestEnemy() {
        val e = engine(players = 2)
        e.setPilotTarget(1, 5.0, 4.0)
        e.run(3.0)
        assertEquals(5.0, e.pilots[0].x, 1e-6); assertEquals(4.0, e.pilots[0].y, 1e-6)
        val en = e.put(EnemyType.DRIFTER, 5.5, hp = 1000.0)
        e.step()
        assertEquals(980.0, en.hp, 1e-9)
    }

    @Test fun onlyABossHurtsAPilotWhichIsDownedAndRespawns() {
        val e = engine(players = 2)
        val pilot = e.pilots[0]
        e.setPilotTarget(1, 5.0, 4.5)
        e.run(3.0)
        val drifter = e.put(EnemyType.DRIFTER, 5.3, hp = 1e9)
        e.run(2.0)
        assertEquals(BbRules.PILOT_HP, pilot.hp, 1e-9)
        drifter.dead = true; e.run(0.1)
        e.addEnemy(EnemyType.BEHEMOTH, 0, 5.3, 1e9, 0.001, false)
        e.run(6.0)
        assertTrue(pilot.down)
        assertTrue(e.drainEvents().any { it is BbEvent.PilotDown })
        e.enemies.clear()
        e.run(BbRules.PILOT_RESPAWN + 0.5)
        assertFalse(pilot.down)
        assertEquals(BbRules.PILOT_HP, pilot.hp, 1e-9)
    }
}
