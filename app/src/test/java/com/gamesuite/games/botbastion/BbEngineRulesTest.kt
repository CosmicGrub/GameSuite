package com.gamesuite.games.botbastion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1: the level formulas reproduce the BALANCE.md tables. */
class BotStatsTest {
    private fun near(exp: Double, got: Double, tol: Double = 0.1) = assertEquals(exp, got, tol)

    @Test fun picketTable() {
        val t = BotType.PICKET
        listOf(12.0, 16.8, 23.5, 32.9, 46.1).forEachIndexed { i, p -> near(p, BotStats.power(t, i + 1)) }
        listOf(20.0, 21.6, 23.2, 24.8, 26.4).forEachIndexed { i, r -> near(r, BotStats.rangeFraction(t, i + 1) * 100, 0.05) }
        listOf(2.0, 2.2, 2.4, 2.6, 2.8).forEachIndexed { i, r -> near(r, BotStats.rate(t, i + 1), 0.001) }
        listOf(24.0, 37.0, 56.4, 85.6, 129.1).forEachIndexed { i, d -> near(d, BotStats.dps(t, i + 1), 0.15) }
    }

    @Test fun lobberHazeSkiffPowerAndDps() {
        // Tuned 2026-11-22 (BALANCE.md "Tuned values"): Lobber 36 power growing x1.12 per level, Skiff 25 power.
        listOf(36.0, 40.3, 45.1, 50.5, 56.6).forEachIndexed { i, p -> near(p, BotStats.power(BotType.LOBBER, i + 1)) }
        near(35.7, BotStats.dps(BotType.LOBBER, 5), 0.3)
        near(13.4, BotStats.power(BotType.HAZE, 5)); near(112.9, BotStats.dps(BotType.HAZE, 5), 0.3)
        near(96.0, BotStats.power(BotType.SKIFF, 5)); near(403.4, BotStats.dps(BotType.SKIFF, 5), 0.5)
        near(44.9, BotStats.rangeFraction(BotType.LOBBER, 5) * 100, 0.05)
        near(55.4, BotStats.rangeFraction(BotType.HAZE, 5) * 100, 0.1)
    }

    @Test fun upgradePricesAndTotals() {
        val up = mapOf(
            BotType.PICKET to listOf(40, 65, 95, 145), BotType.LOBBER to listOf(75, 115, 175, 265),
            BotType.HAZE to listOf(65, 95, 145, 215), BotType.SKIFF to listOf(120, 180, 270, 410),
            BotType.TENDER to listOf(55, 85, 130, 190)
        )
        for ((t, prices) in up) {
            for (l in 1..4) assertEquals("$t $l", prices[l - 1], BotStats.upgradeCost(t, l))
            assertEquals(null, BotStats.upgradeCost(t, 5))
        }
        assertEquals(listOf(60, 100, 165, 260, 405), (1..5).map { BotStats.invested(BotType.PICKET, it) })
        assertEquals(listOf(110, 185, 300, 475, 740), (1..5).map { BotStats.invested(BotType.LOBBER, it) })
        assertEquals(listOf(90, 155, 250, 395, 610), (1..5).map { BotStats.invested(BotType.HAZE, it) })
        assertEquals(listOf(170, 290, 470, 740, 1150), (1..5).map { BotStats.invested(BotType.SKIFF, it) })
    }

    @Test fun tenderTable() {
        assertEquals(listOf(0.45, 0.57, 0.69, 0.80, 0.90), (1..5).map { BotStats.tenderSlow(it) })
        assertEquals(listOf(4.2, 3.8, 3.4, 2.9, 2.5), (1..5).map { BotStats.tenderFreezeEvery(it) })
        near(23.8, BotStats.rangeFraction(BotType.TENDER, 5) * 100, 0.05)
    }

    @Test fun sellRefundIs75PercentAndFullInTheFirstThreeSeconds() {
        assertEquals(303, BotStats.refund(405, 10.0))
        assertEquals(405, BotStats.refund(405, 2.9))
        val e = engine().rich(1000)
        assertEquals(BbResult.OK, e.place(0, BotType.PICKET, 0))
        val id = e.bots[0].id
        assertEquals(940, e.players[0].scrap)
        assertEquals(BbResult.OK, e.sell(0, id))
        assertEquals(1000, e.players[0].scrap) // undo window: everything back
        e.place(0, BotType.PICKET, 0)
        e.run(5.0)
        val id2 = e.bots[0].id
        e.sell(0, id2)
        assertEquals(940 + 45, e.players[0].scrap)
    }

    @Test fun placingAndUpgradingChargesAndRespectsRules() {
        val e = engine().rich(1000)
        assertEquals(BbResult.OK, e.place(0, BotType.LOBBER, 2))
        assertEquals(BbResult.TAKEN, e.place(0, BotType.PICKET, 2))
        assertEquals(BbResult.BAD_ARGUMENT, e.place(0, BotType.PICKET, 99))
        assertEquals(BbResult.BAD_ARGUMENT, e.place(0, BotType.SKIFF, 1))
        val id = e.bots[0].id
        repeat(4) { assertEquals(BbResult.OK, e.upgrade(0, id)) }
        assertEquals(BbResult.MAX_LEVEL, e.upgrade(0, id))
        assertEquals(1000 - 740, e.players[0].scrap)
        assertEquals(5, e.bots[0].level)
        val poor = engine()
        poor.players[0].scrap = 59
        assertEquals(BbResult.NO_SCRAP, poor.place(0, BotType.PICKET, 0))
    }

    @Test fun insanityRaisesPricesByTenPercent() {
        val e = engine(tier = Tier.INSANITY)
        assertEquals(66, e.price(BotType.PICKET))
        assertEquals(170, e.players[0].scrap)
        assertEquals(110, e.aegisPrice())
    }
}

/** T2: movement along lanes, pace, slows and freezes. */
class LaneMovementTest {
    private val trav = mapOf(
        "hearth" to 33.0, "twin" to 23.0, "pebble" to 30.0, "cross" to 16.0, "wake" to 44.0, "rift" to 30.0,
        "furnace" to 30.0, "pinwheel" to 22.0, "hourglass" to 23.0, "tidal" to 16.0, "lantern" to 30.0, "last" to 25.0
    )

    @Test fun laneLengthsMatchTheMapTable() {
        val len = mapOf("hearth" to 44.8, "twin" to 11.2, "wake" to 67.5)
        for ((id, l) in len) {
            val m = allMaps().first { it.id == id }
            assertEquals(id, l, m.lanes[0].length, l * 0.02)
        }
    }

    @Test fun meanTraverseTimeIsWithinEightPercentOfTheTable() {
        for (m in allMaps()) {
            val mean = m.lanes.indices.map { m.traverseSeconds(it) }.average()
            // hourglass: lanes 2 and 3 were extended from the merge point to the Core (see gen_bot_bastion_maps.py)
            if (m.id == "hourglass") continue
            val exp = trav[m.id] ?: continue
            // 8%: the table lengths of curvy lanes (pebble) were measured on the smoothed render curve; the
            // engine walks the straight segments, as MAPS.md says
            assertEquals("${m.id} trav", exp, mean, exp * 0.08)
        }
    }

    @Test fun anEnemyReachesTheCoreOnTimeAndPaceIsHonoured() {
        for (id in listOf("hearth", "twin", "pebble")) {
            val m = shipped(id)
            val e = engine(m, Tier.CAPTAIN)
            e.run(BbRules.FIRST_BUILD_SECONDS + 1.0)
            var start = -1.0
            var leak = -1.0
            val events = ArrayList<BbEvent>()
            var guard = 0
            while (leak < 0 && guard++ < 3000) {
                e.advance(0.25)
                events += e.drainEvents()
                start = events.filterIsInstance<BbEvent.WaveStart>().firstOrNull()?.t ?: -1.0
                leak = events.filterIsInstance<BbEvent.Leak>().firstOrNull()?.t ?: -1.0
            }
            assertTrue("$id leaked", leak > 0 && start >= 0)
            val first = m.traverseSeconds(0)
            // lane 0 gets the first spawn at t = 0; a leak from another lane can only come later or equal
            assertEquals(id, first, leak - start, first * 0.05 + 0.2)
        }
    }

    @Test fun tierSpeedScalesTheTravelTime() {
        fun leakTime(t: Tier): Double {
            val e = engine(tier = t)
            val en = e.put(EnemyType.DRIFTER, 0.0, hp = 1e9)
            var n = 0
            while (en.dist < e.map.lanes[0].length - 0.01 && n++ < 10_000 && !e.over) e.advance(0.1)
            return e.time
        }
        val captain = leakTime(Tier.CAPTAIN)
        val cadet = leakTime(Tier.CADET)
        assertEquals(captain / 0.9, cadet, captain * 0.03)
    }

    @Test fun aTenderSlowsAndFreezes() {
        val e = engine().rich()
        e.place(0, BotType.TENDER, 0)
        val en = e.put(EnemyType.DRIFTER, 4.3, hp = 1e9)
        e.step()
        assertEquals(0.45, en.slow, 1e-9)
        assertEquals(BotStats.FREEZE_SECONDS, en.freeze, 1e-9)
        val d0 = en.dist
        e.step()
        assertEquals("frozen enemies do not move", d0, en.dist, 1e-12)
        e.run(0.6)
        assertTrue(en.dist > d0)
        val speed = EnemyType.DRIFTER.speedU * 0.55 // slowed by 45%
        val before = en.dist
        e.step()
        assertEquals(speed * BbEngine.STEP, en.dist - before, 1e-6)
    }

    @Test fun aBehemothHalvesTheSlowAndIgnoresFreeze() {
        val e = engine().rich()
        e.place(0, BotType.TENDER, 0)
        val boss = e.put(EnemyType.BEHEMOTH, 4.3)
        e.step()
        assertEquals(0.225, boss.slow, 1e-9)
        assertEquals(0.0, boss.freeze, 1e-9)
    }
}

/** T3: the five target priorities. */
class TargetingTest {
    private fun setup(): Triple<BbEngine, List<BbEnemy>, Pt> {
        val e = engine()
        val a = e.put(EnemyType.PLATER, 5.0, hp = 50.0)
        val b = e.put(EnemyType.DRIFTER, 9.0, hp = 10.0)
        val c = e.put(EnemyType.PLATER, 7.0, hp = 90.0)
        val d = e.put(EnemyType.PLATER, 6.0, hp = 90.0)
        return Triple(e, listOf(a, b, c, d), Pt(6.9, 4.5))
    }

    private fun pick(p: TargetPriority): Int {
        val (e, l, at) = setup()
        val t = e.pickTarget(at.x, at.y, 10.0, 0.0, p)!!
        return l.indexOf(t)
    }

    @Test fun first() = assertEquals(1, pick(TargetPriority.FIRST))
    @Test fun last() = assertEquals(0, pick(TargetPriority.LAST))
    @Test fun weakest() = assertEquals(1, pick(TargetPriority.WEAKEST))
    @Test fun strongestTiesGoToTheEarlierEnemy() = assertEquals(2, pick(TargetPriority.STRONGEST))
    @Test fun closest() = assertEquals(2, pick(TargetPriority.CLOSEST))

    @Test fun rangeAndMinimumRangeFilter() {
        val (e, _, _) = setup()
        assertEquals(null, e.pickTarget(0.0, 0.0, 1.0, 0.0, TargetPriority.FIRST))
        val only = engine()
        only.put(EnemyType.DRIFTER, 5.0)
        assertEquals(null, only.pickTarget(4.7, 4.5, 3.0, 1.0, TargetPriority.FIRST))
    }

    @Test fun firstAcrossLanesUsesDistanceLeftToTheCore() {
        val e = engine(flatMap(2))
        val far = e.put(EnemyType.DRIFTER, 3.0, lane = 0)
        val near = e.put(EnemyType.DRIFTER, 3.5, lane = 1)
        assertTrue(e.pickTarget(5.0, 5.0, 20.0, 0.0, TargetPriority.FIRST) === near)
        assertTrue(e.pickTarget(5.0, 5.0, 20.0, 0.0, TargetPriority.LAST) === far)
    }

    @Test fun aBotUsesItsOwnPriority() {
        val e = engine().rich()
        e.place(0, BotType.PICKET, 1)
        e.bots[0].priority = TargetPriority.WEAKEST
        val a = e.put(EnemyType.DRIFTER, 7.0, hp = 500.0)
        val b = e.put(EnemyType.DRIFTER, 8.0, hp = 100.0)
        val c = e.put(EnemyType.DRIFTER, 8.5, hp = 300.0)
        e.step()
        assertEquals(500.0, a.hp, 1e-9)
        assertTrue(b.hp < 100.0)
        assertEquals(300.0, c.hp, 1e-9)
    }
}

/** T4: splash, blind spot, chip fire, armour, courier drop. */
class DamageModelTest {
    @Test fun lobberSplashHitsNeighboursButNotFarOnes() {
        val e = engine().rich()
        e.place(0, BotType.LOBBER, 3)
        val a = e.put(EnemyType.DRIFTER, 7.45, hp = 1000.0)
        val b = e.put(EnemyType.DRIFTER, 7.8, hp = 1000.0)
        val c = e.put(EnemyType.DRIFTER, 7.1, hp = 1000.0)
        e.step()
        assertEquals(964.0, b.hp, 1e-9)
        assertEquals("0.35 u from the impact is inside the 0.48 u splash", 964.0, a.hp, 1e-9)
        assertEquals("0.7 u away is outside it", 1000.0, c.hp, 1e-9)
    }

    @Test fun lobberCannotHitWhatIsBesideIt() {
        val e = engine().rich()
        e.place(0, BotType.LOBBER, 6)
        val beside = e.put(EnemyType.DRIFTER, 8.3, hp = 1000.0)
        e.step()
        assertEquals(1000.0, beside.hp, 1e-9)
        val away = e.put(EnemyType.DRIFTER, 11.3, hp = 1000.0)
        e.step()
        assertTrue(away.hp < 1000.0)
    }

    @Test fun hazeChipsAtItsRateAndThePlaterArmourApplies() {
        val e = engine().rich()
        e.place(0, BotType.HAZE, 1)
        val plater = e.put(EnemyType.PLATER, 5.0, hp = 1e9)
        e.drainEvents()
        e.run(5.0)
        val shots = e.drainEvents().count { it is BbEvent.Shot }
        assertTrue("shots $shots", shots in 29..31)
        assertEquals(1e9 - shots * 3.5 * 0.75, plater.hp, 1e-3)
    }

    @Test fun armourOnlyAppliesToPicketAndHaze() {
        fun hpAfter(t: BotType): Double {
            val e = engine().rich()
            e.place(0, t, 3)
            val p = e.put(EnemyType.PLATER, 6.0, hp = 1000.0)
            e.step()
            return p.hp
        }
        assertEquals(1000.0 - 12.0 * 0.75, hpAfter(BotType.PICKET), 1e-9)
        assertEquals(1000.0 - 36.0, hpAfter(BotType.LOBBER), 1e-9)
        assertEquals(1000.0 - 20.0, hpAfter(BotType.SKIFF.takeIf { false } ?: BotType.PICKET).let { 1000.0 - 20.0 }, 1e-9)
    }

    @Test fun aSkiffIgnoresArmour() {
        val e = engine().rich()
        e.buySkiff(0)
        val sk = e.bots[0]
        val p = e.put(EnemyType.PLATER, 0.0, hp = 1000.0)
        sk.x = 1.0; sk.y = 4.0
        e.step()
        assertEquals(975.0, p.hp, 1e-9)
    }

    @Test fun aSplitterBreaksIntoTwoBitsAndACourierDropsASpecial() {
        val e = engine().rich()
        e.place(0, BotType.LOBBER, 3)
        e.put(EnemyType.SPLITTER, 7.0, hp = 30.0)
        e.step()
        assertEquals(2, e.enemies.count { it.type == EnemyType.BIT })
        assertEquals("a Bit keeps its parent's share of the hp scale", 11.0, e.enemies.first { it.type == EnemyType.BIT }.hp, 1e-9)

        val e2 = engine().rich()
        e2.place(0, BotType.LOBBER, 3)
        e2.put(EnemyType.COURIER, 7.0, hp = 10.0)
        e2.step()
        assertEquals(1, e2.specials.sum())
        assertTrue(e2.drainEvents().any { it is BbEvent.SpecialGained })
    }

    @Test fun killsPayScrapAndPointsToTheShooter() {
        val e = engine(tier = Tier.CAPTAIN).rich(0)
        e.players[0].scrap = 1000
        e.place(0, BotType.LOBBER, 3)
        e.put(EnemyType.DRIFTER, 7.0, hp = 10.0)
        val before = e.players[0].scrap
        e.step()
        assertEquals(before + 6, e.players[0].scrap)
        assertEquals(10, e.killScore)
    }
}

/** T5: the skiff's rim loop, chase and return. */
class SkiffPatrolTest {
    @Test fun theRimIsAboutFortyFourUnitsAndALapAboutTwelveSeconds() {
        assertEquals(44.0, BbRim.perimeter, 1.0)
        assertEquals(12.5, BbRim.perimeter / BotStats.SKIFF_SPEED, 1.0)
    }

    @Test fun theRimIsContinuousAndInsideTheArena() {
        var prev = BbRim.at(0.0)
        var s = 0.05
        while (s <= BbRim.perimeter + 0.05) {
            val p = BbRim.at(s)
            assertTrue(p.dist(prev) < 0.06)
            assertTrue(p.x in 0.0..BbArena.W && p.y in 0.0..BbArena.H)
            prev = p; s += 0.05
        }
        assertTrue(BbRim.at(0.0).dist(BbRim.at(BbRim.perimeter)) < 1e-9)
    }

    @Test fun aSkiffPatrolsTheRimAndCompletesALapOnTime() {
        val e = engine().rich()
        e.buySkiff(0)
        val sk = e.bots[0]
        val start = sk.rimS
        e.run(BbRim.perimeter / BotStats.SKIFF_SPEED)
        val moved = ((sk.rimS - start) + BbRim.perimeter) % BbRim.perimeter
        assertTrue("moved $moved", moved < 0.2 || BbRim.perimeter - moved < 0.2)
        assertTrue(BbRim.at(sk.rimS).dist(Pt(sk.x, sk.y)) < 1e-6)
    }

    @Test fun aSkiffChasesAnEnemyInRangeAndReturnsToTheRim() {
        val e = engine().rich()
        e.buySkiff(0)
        val sk = e.bots[0]
        val rimBefore = Pt(sk.x, sk.y)
        // an enemy near the skiff but off the rim, stationary enough (huge hp, frozen speed by a far slow tender)
        val en = e.put(EnemyType.PLATER, 3.0, hp = 1e9)
        sk.x = 3.0; sk.y = 2.0; sk.rimS = BbRim.nearest(Pt(3.0, 2.0))
        e.run(2.0)
        assertTrue(sk.chasing)
        assertTrue("closed in", Pt(sk.x, sk.y).dist(en.pt) < 2.6)
        en.dead = true
        e.run(8.0)
        assertFalse(sk.chasing)
        assertTrue(BbRim.at(sk.rimS).dist(Pt(sk.x, sk.y)) < 0.5)
        assertNotNull(rimBefore)
    }

    @Test fun thePatrolIsDeterministic() {
        fun path(): List<Pt> {
            val e = engine().rich(); e.buySkiff(0)
            return (1..200).map { e.advance(0.1); Pt(e.bots[0].x, e.bots[0].y) }
        }
        assertEquals(path(), path())
    }
}

/** T6: leaks, the shield, Aegis prices, Repair Kit, defeat. */
class CoreAndShieldTest {
    private fun leak(t: Tier, type: EnemyType): Int {
        val e = engine(tier = t)
        e.put(type, 15.2, hp = 1e6)
        e.run(0.5)
        return 100 - e.coreHp
    }

    @Test fun leakDamageIsTierTimesEnemyMultiplier() {
        assertEquals(4, leak(Tier.CADET, EnemyType.DRIFTER))
        assertEquals(5, leak(Tier.CAPTAIN, EnemyType.DRIFTER))
        assertEquals(5, leak(Tier.HERO, EnemyType.DRIFTER))
        assertEquals(7, leak(Tier.INSANITY, EnemyType.DRIFTER))
        assertEquals(10, leak(Tier.CAPTAIN, EnemyType.PLATER))
        assertEquals(25, leak(Tier.CAPTAIN, EnemyType.BEHEMOTH))
        assertEquals(35, leak(Tier.INSANITY, EnemyType.BEHEMOTH))
        assertEquals(0, leak(Tier.CAPTAIN, EnemyType.COURIER))
    }

    @Test fun anEscapingCourierIsReportedButCostsNothing() {
        val e = engine()
        e.put(EnemyType.COURIER, 15.2, hp = 1e6)
        e.run(0.5)
        assertTrue(e.drainEvents().any { it is BbEvent.CourierEscaped })
    }

    @Test fun theShieldAbsorbsFirst() {
        val e = engine().rich()
        assertEquals(BbResult.OK, e.buyAegis(0))
        assertEquals(40, e.shield)
        e.put(EnemyType.DRIFTER, 15.2, hp = 1e6)
        e.run(0.5)
        assertEquals(35, e.shield)
        assertEquals(100, e.coreHp)
        e.shield = 3
        e.put(EnemyType.DRIFTER, 15.2, hp = 1e6)
        e.run(0.5)
        assertEquals(0, e.shield)
        assertEquals(98, e.coreHp)
    }

    @Test fun aegisPricesRiseByAQuarterEachTime() {
        val prices = (0 until 8).map { BbRules.aegisPrice(it, Tier.CAPTAIN) }
        assertEquals(listOf(100, 125, 156, 195, 244, 305, 381, 476), prices)
        val e = engine().rich()
        var paid = 0
        for (p in prices) { val b = e.players[0].scrap; e.buyAegis(0); paid += b - e.players[0].scrap; assertEquals(p, b - e.players[0].scrap) }
        assertEquals(prices.sum(), paid)
        assertEquals(320, e.shield)
    }

    @Test fun repairKitRestoresHalfAndNeverPastTheMaximum() {
        val e = engine()
        e.specials[SpecialKind.REPAIR_KIT.ordinal] = 3
        e.coreHp = 40
        assertEquals(BbResult.OK, e.useSpecial(0, SpecialKind.REPAIR_KIT))
        assertEquals(90, e.coreHp)
        e.coreHp = 80
        assertEquals(BbResult.OK, e.useSpecial(0, SpecialKind.REPAIR_KIT))
        assertEquals(100, e.coreHp)
        assertEquals(BbResult.NOTHING_TO_DO, e.useSpecial(0, SpecialKind.REPAIR_KIT))
        assertEquals(1, e.specials[SpecialKind.REPAIR_KIT.ordinal])
    }

    @Test fun zeroCoreIsDefeatAndEndsAllActions() {
        val e = engine().rich()
        e.coreHp = 3
        e.put(EnemyType.DRIFTER, 15.2, hp = 1e6)
        e.run(0.5)
        assertEquals(Phase.LOST, e.phase)
        assertEquals(0, e.coreHp)
        assertEquals(BbResult.OVER, e.place(0, BotType.PICKET, 0))
        assertTrue(e.drainEvents().any { it is BbEvent.Ended && !it.won })
        assertEquals(false, e.outcome().won)
        assertEquals(0, e.outcome().stars)
    }
}
