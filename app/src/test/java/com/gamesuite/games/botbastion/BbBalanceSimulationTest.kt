package com.gamesuite.games.botbastion

import com.gamesuite.games.botbastion.BbSim.Strategy as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9: a headless balance simulator. Scripted players (no randomness of their own) buy and upgrade bots on a map,
 * and the tests assert the TARGETS of BALANCE.md section 9 (tuned 2026-11-22, see its "Tuned values" and tuning
 * log) with stated tolerances. A single exploit (one bot type spammed, no upgrades, all-in on upgrades) must never
 * win; only the rounded loadouts do. Results are printed as `SIM ...` lines.
 */
internal object BbSim {
    enum class Strategy {
        SPAM_PICKET, SPAM_LOBBER, SPAM_HAZE, SPAM_TENDER, SKIFF_ONLY, ALL_ROUND, ALL_ROUND_AEGIS, NO_TENDER_NO_SKIFF,
        MIXED, NO_UPGRADES, OVER_UPGRADE
    }

    class Result(val wave: Int, val won: Boolean, val coreLeft: Int, val score: Int, val shield: Int = 0)

    /** Nodes best first: how much lane (sampled every 0.25 u) lies within 3.2 u, with a small bonus for nearness to the Core. */
    fun rankNodes(m: MapDef, reach: Double = 3.2): List<Int> {
        val samples = ArrayList<Pt>()
        for (l in m.lanes) { var d = 0.0; while (d < l.length) { samples += l.pointAt(d); d += 0.25 } }
        return m.nodes.indices.sortedByDescending { i ->
            samples.count { it.dist(m.nodes[i]) <= reach } + (10.0 - m.nodes[i].dist(m.core)).coerceAtLeast(0.0)
        }
    }

    private fun upgradeCheapest(e: BbEngine, only: Set<BotType>? = null): Boolean {
        val b = e.bots.filter { (only == null || it.type in only) && e.upgradePrice(it) != null }
            .minByOrNull { it.level * 10_000 + (e.upgradePrice(it) ?: 0) } ?: return false
        return e.upgrade(0, b.id) == BbResult.OK
    }

    /** Over-upgrade: push the dearest-but-not-yet-maxed bot first, so a few bots reach level 5 before anything new is bought. */
    private fun upgradeTall(e: BbEngine): Boolean {
        val b = e.bots.filter { e.upgradePrice(it) != null }.maxByOrNull { it.level * 10_000 - (e.upgradePrice(it) ?: 0) } ?: return false
        return e.upgrade(0, b.id) == BbResult.OK
    }

    private fun placeBest(e: BbEngine, order: List<Int>, t: BotType): Boolean {
        if (t == BotType.SKIFF) return e.buySkiff(0) == BbResult.OK
        val n = order.firstOrNull { e.botAtNode(it) == null } ?: return false
        return e.place(0, t, n) == BbResult.OK
    }

    private val allRound = listOf(
        BotType.PICKET, BotType.PICKET, BotType.TENDER, BotType.LOBBER, BotType.PICKET, BotType.HAZE, BotType.LOBBER,
        BotType.SKIFF, BotType.TENDER, BotType.PICKET, BotType.LOBBER, BotType.SKIFF, BotType.HAZE, BotType.LOBBER
    )

    /** One of everything in rotation, upgrades always go to the lowest-level bot. */
    private val mixed = listOf(
        BotType.PICKET, BotType.LOBBER, BotType.HAZE, BotType.TENDER, BotType.SKIFF,
        BotType.PICKET, BotType.LOBBER, BotType.HAZE, BotType.TENDER, BotType.SKIFF,
        BotType.PICKET, BotType.LOBBER, BotType.HAZE, BotType.PICKET, BotType.LOBBER
    )

    /** Three bots, then everything into upgrades until they are maxed. */
    private val tall = listOf(BotType.PICKET, BotType.LOBBER, BotType.SKIFF, BotType.TENDER)

    private fun useSpecials(e: BbEngine) {
        if (e.coreHp <= 60 && e.specials[SpecialKind.REPAIR_KIT.ordinal] > 0) e.useSpecial(0, SpecialKind.REPAIR_KIT)
        val boss = e.enemies.any { it.type.isBoss }
        if ((e.enemies.count { it.x in 0.0..BbArena.W } >= 18 || boss) && e.specials[SpecialKind.NOVA_PULSE.ordinal] > 0) e.useSpecial(0, SpecialKind.NOVA_PULSE)
        if (e.phase == Phase.WAVE && e.enemies.size > 10) {
            e.useSpecial(0, SpecialKind.SCRAP_SURGE); e.useSpecial(0, SpecialKind.SCORE_BOOST)
        }
    }

    private fun follow(e: BbEngine, nodes: List<Int>, order: List<BotType>, next: Int, upgrade: (BbEngine) -> Boolean, aegis: Boolean): Int {
        var n = next
        while (n < order.size) {
            val t = order[n]
            val ok = if (t == BotType.SKIFF) e.buySkiff(0) == BbResult.OK else placeBest(e, nodes, t)
            if (ok) n++ else break
        }
        if (n >= order.size || nodes.none { e.botAtNode(it) == null }) {
            // spend on the shield first when it is cheap relative to the purse, then on upgrades
            if (aegis && e.shield < 120 && e.players[0].scrap > e.aegisPrice() * 2) e.buyAegis(0)
            while (upgrade(e)) { }
        }
        return n
    }

    fun play(m: MapDef, tier: Tier, strategy: Strategy, maxWave: Int = 1000, seed: Long = 7L, specials: Boolean = true): Result {
        val e = BbEngine(m, tier, 1, seed)
        val nodes = rankNodes(m)
        var next = 0
        var guard = 0
        while (!e.over && e.wave < maxWave + 1 && guard++ < 200_000) {
            // act twice a second of game time
            when (strategy) {
                Strategy.SPAM_PICKET, Strategy.SPAM_LOBBER, Strategy.SPAM_HAZE, Strategy.SPAM_TENDER -> {
                    val t = when (strategy) {
                        Strategy.SPAM_PICKET -> BotType.PICKET
                        Strategy.SPAM_LOBBER -> BotType.LOBBER
                        Strategy.SPAM_HAZE -> BotType.HAZE
                        else -> BotType.TENDER
                    }
                    while (e.players[0].scrap >= e.price(t) && nodes.any { e.botAtNode(it) == null }) placeBest(e, nodes, t)
                    while (upgradeCheapest(e)) { }
                }
                Strategy.SKIFF_ONLY -> {
                    while (e.buySkiff(0) == BbResult.OK) { }
                    while (upgradeCheapest(e)) { }
                }
                Strategy.ALL_ROUND -> next = follow(e, nodes, allRound, next, { upgradeCheapest(it) }, aegis = false)
                Strategy.ALL_ROUND_AEGIS -> next = follow(e, nodes, allRound, next, { upgradeCheapest(it) }, aegis = true)
                Strategy.NO_TENDER_NO_SKIFF ->
                    next = follow(e, nodes, allRound.filter { it != BotType.TENDER && it != BotType.SKIFF }, next, { upgradeCheapest(it) }, aegis = true)
                Strategy.MIXED -> next = follow(e, nodes, mixed, next, { upgradeCheapest(it) }, aegis = false)
                Strategy.NO_UPGRADES -> next = follow(e, nodes, allRound, next, { false }, aegis = true)
                Strategy.OVER_UPGRADE -> next = follow(e, nodes, tall, next, { upgradeTall(it) }, aegis = false)
            }
            if (specials) useSpecials(e)
            e.run(0.5)
        }
        return Result(e.wave, e.phase == Phase.WON, e.coreHp, e.score, e.shield)
    }
}

class BalanceSimulationTest {
    private val launch by lazy { listOf(shipped("hearth"), shipped("twin"), shipped("pebble")) }

    private fun report(label: String, r: BbSim.Result) = println("SIM $label wave=${r.wave} won=${r.won} core=${r.coreLeft} shield=${r.shield} score=${r.score}")

    private fun run(m: MapDef, tier: Tier, s: BbSim.Strategy, seed: Long = 7L, specials: Boolean = true): BbSim.Result =
        BbSim.play(m, tier, s, seed = seed, specials = specials)
            .also { report("${tier.id}-${s.name.lowercase()}-${m.id}#$seed${if (specials) "" else "-nospecials"}", it) }

    /** Every strategy except the rounded loadouts must lose. */
    private val exploits = listOf(S.SPAM_PICKET, S.SPAM_LOBBER, S.SPAM_HAZE, S.SPAM_TENDER, S.SKIFF_ONLY, S.NO_UPGRADES, S.OVER_UPGRADE)

    @Test fun theSimulatorIsDeterministic() {
        val a = BbSim.play(launch[1], Tier.CAPTAIN, S.ALL_ROUND, maxWave = 8)
        val b = BbSim.play(launch[1], Tier.CAPTAIN, S.ALL_ROUND, maxWave = 8)
        assertEquals(a.score, b.score); assertEquals(a.coreLeft, b.coreLeft)
    }

    @Test fun anEmptyDefenceLosesQuicklyOnEveryMap() {
        for (m in shippedMaps()) {
            val e = BbEngine(m, Tier.CAPTAIN, 1, 1L)
            e.run(400.0)
            assertTrue("${m.id}: an empty defence should fall", e.phase == Phase.LOST)
            assertTrue("${m.id}: an empty defence should fall within ~6 waves, wave ${e.wave}", e.wave <= 6)
        }
    }

    // ---- Cadet: forgiving, 20 waves -----------------------------------------------------------------------

    @Test fun cadetIsEasyForTheAllRoundLoadoutAndStillNeedsDamage() {
        for (m in launch) {
            val all = run(m, Tier.CADET, S.ALL_ROUND)
            assertTrue("${m.id} cadet all-round wins with the Core nearly untouched (>= 85), got ${all.coreLeft}", all.won && all.coreLeft >= 85)
            assertTrue("${m.id} cadet Tender-only cannot win", !run(m, Tier.CADET, S.SPAM_TENDER).won)
            assertTrue("${m.id} cadet Skiff-only cannot win", !run(m, Tier.CADET, S.SKIFF_ONLY).won)
        }
    }

    // ---- Captain: the BALANCE.md anchor tier (30 waves) ----------------------------------------------------

    /** Target: a competent loadout finishes with 60 to 75% Core left. Tolerance +-5 (the Behemoth leak is 25 Core). */
    @Test fun captainCompetentLoadoutWinsWithSixtyToSeventyFivePercentCore() {
        for (m in launch) {
            val r = run(m, Tier.CAPTAIN, S.ALL_ROUND)
            assertTrue("${m.id} captain all-round should win, reached wave ${r.wave}", r.won)
            assertTrue("${m.id} captain all-round Core left should be 55..80, was ${r.coreLeft}", r.coreLeft in 55..80)
            // Specials (Repair Kit, Nova) are seed luck: with them off the band must hold on any seed.
            for (seed in listOf(1L, 2L, 3L)) {
                val n = run(m, Tier.CAPTAIN, S.ALL_ROUND, seed, specials = false)
                assertTrue("${m.id} seed $seed no specials: won=${n.won} core=${n.coreLeft}", n.won && n.coreLeft in 55..80)
            }
        }
    }

    @Test fun captainMixedBuildAlsoWinsAndTheShieldOnlyMakesItSafer() {
        for (m in launch) {
            val mixed = run(m, Tier.CAPTAIN, S.MIXED)
            val shielded = run(m, Tier.CAPTAIN, S.ALL_ROUND_AEGIS)
            val bare = run(m, Tier.CAPTAIN, S.ALL_ROUND)
            assertTrue("${m.id} captain mixed should win with Core >= 40, got ${mixed.coreLeft}", mixed.won && mixed.coreLeft >= 40)
            assertTrue("${m.id} the Aegis variant wins with at least the bare Core", shielded.won && shielded.coreLeft >= bare.coreLeft)
        }
    }

    /** Target: spam-one-bot loses between waves 15 and 22 (tolerance +1 wave on the upper edge). */
    @Test fun captainSpammingOneBotLosesBetweenWaves15And22() {
        for (m in launch) for (s in listOf(S.SPAM_PICKET, S.SPAM_LOBBER, S.SPAM_HAZE)) {
            val r = run(m, Tier.CAPTAIN, s)
            assertTrue("${m.id} captain ${s.name} should lose in waves 15..23, got won=${r.won} wave=${r.wave}", !r.won && r.wave in 15..23)
        }
    }

    @Test fun captainSkiffOnlyAndTenderOnlyAndNoUpgradesAndOverUpgradeAllLose() {
        for (m in launch) {
            val skiff = run(m, Tier.CAPTAIN, S.SKIFF_ONLY)
            // Only 3 Skiffs per player can be bought, so this is a 3-bot build: it falls well before the spam builds, but not at once.
            assertTrue("${m.id} skiff-only should fall in waves 6..22, got ${skiff.wave}", !skiff.won && skiff.wave in 6..22)
            assertTrue("${m.id} tender-only deals no damage", run(m, Tier.CAPTAIN, S.SPAM_TENDER).wave <= 3)
            val noUp = run(m, Tier.CAPTAIN, S.NO_UPGRADES)
            assertTrue("${m.id} never upgrading should fall in waves 12..22, got ${noUp.wave}", !noUp.won && noUp.wave in 12..22)
            val tall = run(m, Tier.CAPTAIN, S.OVER_UPGRADE)
            assertTrue("${m.id} four maxed bots should fall in waves 15..29, got ${tall.wave}", !tall.won && tall.wave in 15..29)
        }
    }

    @Test fun captainNeedsAllFiveBotTypes() {
        for (m in launch) {
            val r = run(m, Tier.CAPTAIN, S.NO_TENDER_NO_SKIFF)
            assertTrue("${m.id} without Tender and Skiff the run should fall in waves 18..27, got won=${r.won} wave=${r.wave}", !r.won && r.wave in 18..27)
        }
    }

    @Test fun noSingleExploitWinsAnyTierOnAnyLaunchMap() {
        for (tier in listOf(Tier.CAPTAIN, Tier.HERO, Tier.INSANITY)) for (m in launch) for (s in exploits) {
            val r = BbSim.play(m, tier, s)
            assertTrue("${tier.id} ${m.id} ${s.name} must not win (wave ${r.wave})", !r.won)
        }
    }

    // ---- Hero: 40 waves, two Behemoth leaks ----------------------------------------------------------------

    /** Target (ours): the competent loadout wins with 35 to 60% Core left (two Behemoth leaks). Tolerance +-5 on seed luck. */
    @Test fun heroCompetentLoadoutWinsWithAroundHalfTheCore() {
        for (m in launch) {
            val r = run(m, Tier.HERO, S.ALL_ROUND, specials = false)
            assertTrue("${m.id} hero all-round should win, reached wave ${r.wave}", r.won)
            assertTrue("${m.id} hero all-round Core left should be 30..60 without specials, was ${r.coreLeft}", r.coreLeft in 30..60)
            val withSpecials = run(m, Tier.HERO, S.ALL_ROUND)
            assertTrue("${m.id} hero all-round with specials (seed 7) should win with Core 30..65, was won=${withSpecials.won} core=${withSpecials.coreLeft}", withSpecials.won && withSpecials.coreLeft in 30..65)
        }
    }

    @Test fun heroSpamLosesAndEveryOtherBuildNeedsTheFullKit() {
        for (m in launch) {
            for (s in listOf(S.SPAM_PICKET, S.SPAM_LOBBER, S.SPAM_HAZE)) {
                val r = run(m, Tier.HERO, s)
                assertTrue("${m.id} hero ${s.name} should lose in waves 15..23, got ${r.wave}", !r.won && r.wave in 15..23)
            }
            val r = run(m, Tier.HERO, S.NO_TENDER_NO_SKIFF)
            assertTrue("${m.id} hero without Tender and Skiff loses in waves 18..27, got ${r.wave}", !r.won && r.wave in 18..27)
        }
    }

    // ---- Insanity: winnable, very hard ---------------------------------------------------------------------

    /** Target: winnable by a strong all-five-types build, but only just: Core 5 to 35% left (specials on, seed 7). */
    @Test fun insanityIsWinnableByAStrongMixedBuildButOnlyJust() {
        for (m in launch) {
            val r = run(m, Tier.INSANITY, S.ALL_ROUND)
            assertTrue("${m.id} insanity all-round should win, reached wave ${r.wave}", r.won)
            assertTrue("${m.id} insanity all-round Core left should be 5..35, was ${r.coreLeft}", r.coreLeft in 5..35)
            // Other seeds change the specials found: it still wins (they only help).
            for (seed in listOf(1L, 2L, 3L)) {
                val o = run(m, Tier.INSANITY, S.ALL_ROUND, seed)
                assertTrue("${m.id} insanity seed $seed should win, reached wave ${o.wave}", o.won)
            }
            val shielded = run(m, Tier.INSANITY, S.ALL_ROUND_AEGIS)
            assertTrue("${m.id} insanity with the Aegis shield bought late should win with at least the bare Core", shielded.won && shielded.coreLeft >= r.coreLeft)
        }
    }

    @Test fun insanityIsUnwinnableWithoutAllFiveBotTypes() {
        for (m in launch) {
            val r = run(m, Tier.INSANITY, S.NO_TENDER_NO_SKIFF)
            assertTrue("${m.id} insanity without Tender and Skiff should lose in waves 14..27, got won=${r.won} wave=${r.wave}", !r.won && r.wave in 14..27)
            val noUp = run(m, Tier.INSANITY, S.NO_UPGRADES)
            assertTrue("${m.id} insanity never upgrading should lose before wave 25, got ${noUp.wave}", !noUp.won && noUp.wave < 25)
        }
    }

    // ---- Endless and the rest of the world -------------------------------------------------------------------

    @Test fun endlessAlwaysEndsAndNeverBeforeTheHeroGoal() {
        for (m in launch) {
            val r = run(m, Tier.ENDLESS, S.ALL_ROUND)
            assertTrue("${m.id} endless is never won", !r.won)
            assertTrue("${m.id} endless all-round should end in waves 41..60, got ${r.wave}", r.wave in 41..60)
        }
    }

    @Test fun everyShippedMapStaysWinnableOnCadetAndBeatsAnEmptyDefence() {
        for (m in shippedMaps()) {
            val r = BbSim.play(m, Tier.CADET, S.ALL_ROUND_AEGIS)
            assertTrue("${m.id} cadet with the full kit and the shield should win, reached wave ${r.wave}", r.won)
        }
    }
}
