package com.gamesuite.games.fishing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The fishing engine with known values: swing meter, lakes and fish, bite, hook, reel-in, scoring, determinism. */
class FishingSimTest {
    private fun engine(lake: String = "lake", seats: Int = 2, rounds: Int = 3, relaxed: Boolean = false, seed: Int = 1) =
        FishingEngine(lake, seats, rounds, relaxed, Random(seed))

    /** Press, hold for [ticks], release: the cast. */
    private fun cast(e: FishingEngine, ticks: Int) {
        e.step(true)
        repeat(ticks) { e.step(true) }
        e.step(false)
    }

    private fun ticksForBand(band: Int) = ((band + 0.5) / 4.0 * FishingRules.METER_HALF).toInt()

    private fun runUntil(e: FishingEngine, limit: Int = 4000, hold: () -> Boolean = { false }, until: () -> Boolean) {
        var g = 0
        while (!until() && g++ < limit) e.step(hold())
        assertTrue("condition not reached (phase ${e.phase})", until())
    }

    /** Puts the engine into the reel-in against [species] with the given rushes. */
    private fun hooked(e: FishingEngine, species: String, rushes: List<FishRush> = emptyList(), grams: Int = 1000, band: Int = 1) {
        val idx = FishingLakes.species.indexOfFirst { it.id == species }
        e.roll = FishRoll(idx, grams, 100, emptyList(), rushes)
        e.band = band
        e.phase = FishPhase.REELING; e.phaseTicks = 0; e.reelTicks = 0
        e.tension = 0.4; e.distance = FishingRules.distanceOf(band); e.slackTicks = 0
    }

    // ---- constants and tables ----

    @Test fun theSwingMeterRisesThenFallsAndMapsToFourBands() {
        assertEquals(0.0, FishingRules.meterAt(0), 1e-12)
        assertEquals(0.5, FishingRules.meterAt(35), 1e-12)
        assertEquals(1.0, FishingRules.meterAt(70), 1e-12)
        assertEquals(0.5, FishingRules.meterAt(105), 1e-12)
        assertEquals(0.0, FishingRules.meterAt(140), 1e-12)
        assertEquals(0.5, FishingRules.meterAt(140 + 35), 1e-12)
        assertEquals(listOf(0, 0, 1, 2, 3, 3), listOf(0.0, 0.24, 0.25, 0.5, 0.99, 1.0).map { FishingRules.bandOfPower(it) }.let { listOf(it[0], it[1], it[2], it[3], it[4], it[5]) })
        assertEquals(listOf(6.0, 14.0, 22.0, 30.0), (0..3).map { FishingRules.distanceOf(it) })
    }

    @Test fun thereAreFifteenOriginalSpeciesWithSaneSizesAndAllFourTiers() {
        val sp = FishingLakes.species
        assertTrue(sp.size >= 10)
        assertEquals(sp.size, sp.map { it.id }.toSet().size)
        for (s in sp) {
            assertTrue(s.id, s.minG in 1 until s.maxG)
            assertEquals(3, s.depthWeights.size)
            assertTrue(s.strength in 0.0..1.0 && s.rarity > 0)
            assertTrue(s.coverFavourite in 0..3)
        }
        assertEquals(setOf(0, 1, 2, 3), sp.map { it.tier }.toSet())
        assertTrue(sp.first { it.id == "moonpike" }.tier == 3 && sp.first { it.id == "minnow" }.tier == 0)
    }

    @Test fun lakesDecideWhichFishCanBeCaught() {
        for (lake in FishingLakes.lakeIds) for (b in 0 until 4) for (c in 0 until 5) {
            val w = FishingLakes.weightsAt(lake, b, c)
            assertEquals(FishingLakes.species.size, w.size); assertTrue(w.all { it >= 0.0 }); assertTrue(w.sum() > 0)
            assertTrue(FishingLakes.depthAt(lake, b, c) in 0..2); assertTrue(FishingLakes.coverAt(lake, b, c) in 0..3)
        }
        val pond = FishingLakes.weightsAt("pond", 3, 2)
        assertEquals(0.0, pond[FishingLakes.species.indexOfFirst { it.id == "sturgeon" }], 0.0)
        val best = { lake: String -> (0 until 4).maxOf { b -> (0 until 5).maxOf { c -> FishingLakes.expectedGrams(lake, b, c) } } }
        assertTrue(best("reservoir") > 2 * best("pond"))
        assertTrue(FishingCpu.bestSpot("reservoir").second >= 2)                      // deep water pays
        assertEquals(FishingLakes.lakeIds[0], FishingLakes.lakeId("mars"))
    }

    @Test fun nearWaterGivesSmallFishAndFarWaterBigOnes() {
        for (lake in FishingLakes.lakeIds) {
            val near = (0 until 5).map { FishingLakes.expectedGrams(lake, 0, it) }.average()
            val far = (0 until 5).map { FishingLakes.expectedGrams(lake, 3, it) }.average()
            assertTrue("$lake near $near far $far", far > near)
        }
    }

    // ---- the turn ----

    @Test fun aWholeTurnFlowsAimCastWaitBiteHookReelResult() {
        val e = engine(seed = 3)
        assertEquals(FishPhase.AIM, e.phase); assertEquals(0, e.seat)
        e.step(true)
        assertEquals(FishPhase.CHARGING, e.phase)
        repeat(ticksForBand(2)) { e.step(true) }
        assertEquals(FishingRules.meterAt(ticksForBand(2)), e.meter, 1e-9)
        e.step(false)
        assertEquals(FishPhase.CASTING, e.phase)
        assertEquals(2, e.band); assertEquals(1, e.castSerial); assertEquals(1, e.casts[0])
        assertNotNull(e.roll)
        repeat(FishingRules.CAST_TICKS) { e.step(false) }
        assertEquals(FishPhase.WAITING, e.phase)
        runUntil(e, until = { e.phase == FishPhase.BITE })
        assertEquals(1, e.biteSerial)
        e.step(false); e.step(true)
        assertEquals(FishPhase.REELING, e.phase); assertEquals(1, e.hookSerial)
        assertEquals(FishingRules.distanceOf(2), e.distance, 0.2)
        // reel with a sensible policy
        runUntil(e, 8000, hold = { e.rush == null && !e.warning && e.tension < 0.62 }, until = { e.phase == FishPhase.RESULT })
        assertEquals(FishOutcome.CAUGHT, e.outcome)
        assertEquals(e.catchOfTurn!!.grams, e.grams[0]); assertEquals(1, e.catches[0].size)
        assertEquals(1, e.resultSerial)
        runUntil(e, until = { e.phase == FishPhase.AIM })
        assertEquals(1, e.seat)                                                    // the next angler
        assertEquals(0, e.round)
    }

    @Test fun everyoneFishesOncePerRoundAndTheFirstCasterRotates() {
        val e = engine(seats = 3, rounds = 3)
        val order = ArrayList<Int>()
        var g = 0
        while (e.phase != FishPhase.OVER && g++ < 200000) {
            if (e.phase == FishPhase.AIM && e.phaseTicks == 0) order += e.seat
            e.step(false)                                                            // nobody does anything: every turn times out
        }
        assertEquals(FishPhase.OVER, e.phase)
        assertEquals(listOf(0, 1, 2, 1, 2, 0, 2, 0, 1), order)
        assertEquals(0, e.grams.sum())
    }

    @Test fun idleAnglersTimeOutAndTheTimeoutDoublesWhenRelaxed() {
        val e = engine()
        repeat(FishingRules.AIM_TIMEOUT - 1) { e.step(false); assertEquals(FishPhase.AIM, e.phase) }
        e.step(false)
        assertEquals(FishPhase.RESULT, e.phase); assertEquals(FishOutcome.TIMED_OUT, e.outcome)
        val r = engine(relaxed = true)
        repeat(FishingRules.AIM_TIMEOUT + 5) { r.step(false) }
        assertEquals(FishPhase.AIM, r.phase)
        assertEquals(FishingRules.AIM_TIMEOUT * 2, r.aimTimeout)
    }

    @Test fun anIgnoredBiteLosesTheFishAfterTheWindowAndRelaxedDoublesIt() {
        for (relaxed in listOf(false, true)) {
            val e = engine(relaxed = relaxed, seed = 5)
            cast(e, 30)
            runUntil(e, until = { e.phase == FishPhase.BITE })
            val window = e.biteWindow
            assertEquals(if (relaxed) 96 else 48, window)
            repeat(window - 1) { e.step(false); assertEquals(FishPhase.BITE, e.phase) }
            e.step(false)
            assertEquals(FishOutcome.MISSED, e.outcome)
        }
    }

    @Test fun aLateButInTimePressStillHooks() {
        val e = engine(seed = 8)
        cast(e, 30)
        runUntil(e, until = { e.phase == FishPhase.BITE })
        repeat(e.biteWindow - 2) { e.step(false) }
        e.step(true)
        assertEquals(FishPhase.REELING, e.phase)
    }

    @Test fun pullingOnANibbleScaresTheFishButPlainWaitingTapsDoNothing() {
        var found = 0
        for (seed in 0 until 40) {
            val e = engine(seed = seed)
            cast(e, 30)
            repeat(FishingRules.CAST_TICKS) { e.step(false) }
            val nib = e.roll!!.nibbles.firstOrNull() ?: continue
            found++
            // a press well before the nibble is ignored
            if (nib > 6) { e.step(true); e.step(false); assertEquals(FishPhase.WAITING, e.phase) }
            runUntil(e, until = { e.isNibbling })
            assertEquals(1, e.nibbleSerial.coerceAtMost(1))
            e.step(true)
            assertEquals(FishPhase.RESULT, e.phase); assertEquals(FishOutcome.TOO_EARLY, e.outcome)
            assertNull(e.catchOfTurn)
        }
        assertTrue("seeds with a nibble: $found", found >= 10)
    }

    @Test fun rollsAreHiddenUntilTheCastAndNibblesComeBeforeTheBite() {
        for (seed in 0 until 60) {
            val e = engine(seed = seed)
            assertNull(e.roll)
            cast(e, 20 + seed % 50)
            val r = e.roll!!
            assertTrue(r.biteDelay in 90..520)
            assertTrue(r.nibbles.all { it >= 30 && it + FishingRules.NIBBLE_TICKS < r.biteDelay })
            assertEquals(r.nibbles, r.nibbles.sorted())
            assertTrue(r.grams in r.species.minG..r.species.maxG)
            assertTrue(r.rushes.isNotEmpty())
            for ((a, b) in r.rushes.zipWithNext()) assertTrue(b.start >= a.start + a.len + FishingRules.RUSH_WARNING)
        }
    }

    // ---- the reel-in ----

    @Test fun holdingRaisesTensionAndLettingGoLowersItAtTheKnownRates() {
        val e = engine()
        hooked(e, "minnow")
        repeat(30) { e.step(true) }
        assertEquals(0.4 + FishingRules.T_UP * 0.5, e.tension, 1e-6)
        val before = e.tension
        repeat(30) { e.step(false) }
        assertEquals(before - FishingRules.T_DOWN * 0.5, e.tension, 1e-6)
        assertEquals(FishPhase.REELING, e.phase)
    }

    @Test fun reelingBringsTheFishInAndTheSweetSpotIsFastest() {
        val a = engine(); hooked(a, "bluegill")
        val b = engine(); hooked(b, "bluegill")
        // a: tension held in the sweet zone; b: too tight from the start
        a.tension = 0.5; b.tension = 0.9
        val d0 = a.distance
        a.step(true); b.step(true)
        val gainA = d0 - a.distance; val gainB = d0 - b.distance
        assertTrue("sweet $gainA tight $gainB", gainA > gainB && gainB > 0)
        val c = engine(); hooked(c, "bluegill"); c.tension = 0.1
        c.step(true)
        assertTrue(d0 - c.distance < gainA)                                          // slack reeling is slow too
        val drift = engine(); hooked(drift, "bluegill"); drift.step(false)
        assertTrue(drift.distance > d0)                                              // an idle line lets the fish drift away
    }

    @Test fun tooMuchTensionSnapsTheLine() {
        val e = engine(); hooked(e, "carp")
        runUntil(e, hold = { true }, until = { e.phase == FishPhase.RESULT })
        assertEquals(FishOutcome.SNAPPED, e.outcome)
        assertNull(e.catchOfTurn); assertEquals(0, e.grams[0])
    }

    @Test fun aSlackLineLetsTheFishEscape() {
        val e = engine(); hooked(e, "minnow")
        runUntil(e, hold = { false }, until = { e.phase == FishPhase.RESULT })
        assertEquals(FishOutcome.SLACK, e.outcome)
        // the time it takes: tension falls from 0.4 to the slack level, then the slack timer runs
        assertTrue(e.reelTicks in 90..140)
    }

    @Test fun aRushPullsTheLineAndPunishesHoldingButForgivesLettingGo() {
        val rush = listOf(FishRush(10, 60, 100))
        val hold = engine(); hooked(hold, "pike", rush)
        val ease = engine(); hooked(ease, "pike", rush)
        repeat(9) { hold.step(true); ease.step(false) }
        hold.tension = 0.5; ease.tension = 0.5
        val d0 = ease.distance
        repeat(20) { hold.step(true); ease.step(false) }
        assertTrue(hold.tension > ease.tension)
        assertTrue("fish takes line in a rush", ease.distance > d0)
        assertTrue(ease.rush != null || ease.reelTicks > 10)
        assertEquals(1, ease.rushSerial)
        // holding through the whole rush snaps; easing off through it survives
        val h2 = engine(); hooked(h2, "pike", rush)
        runUntil(h2, hold = { true }, until = { h2.phase == FishPhase.RESULT })
        assertEquals(FishOutcome.SNAPPED, h2.outcome)
        val e2 = engine(); hooked(e2, "pike", rush)
        repeat(100) { e2.step(!(e2.rush != null || e2.warning || e2.tension > 0.62)) }
        assertEquals(FishPhase.REELING, e2.phase)
    }

    @Test fun aWarningShowsBeforeEachRush() {
        val e = engine(); hooked(e, "pike", listOf(FishRush(100, 50, 80)))
        e.tension = 0.5
        repeat(100 - FishingRules.RUSH_WARNING - 1) { e.step(e.tension < 0.6) }
        assertFalse(e.warning)
        e.step(true); e.step(true)
        assertTrue(e.warning && e.rush == null)
        repeat(FishingRules.RUSH_WARNING) { e.step(false) }
        assertTrue(e.rush != null)
        assertFalse(e.warning)
        val r = engine(relaxed = true); hooked(r, "pike", listOf(FishRush(100, 50, 80)))
        repeat(100 - FishingRules.RUSH_WARNING * 2 + 1) { r.step(r.tension < 0.6) }
        assertTrue("relaxed warns twice as early", r.warning)
    }

    @Test fun relaxedFishingWidensTheSweetZoneAndSoftensThePull() {
        val n = engine(); val r = engine(relaxed = true)
        assertTrue(r.sweetLo < n.sweetLo && r.sweetHi > n.sweetHi)
        hooked(n, "pike", listOf(FishRush(1, 60, 100))); hooked(r, "pike", listOf(FishRush(1, 60, 100)))
        n.tension = 0.5; r.tension = 0.5
        repeat(30) { n.step(true); r.step(true) }
        assertTrue(n.tension > r.tension)
    }

    // ---- scoring ----

    @Test fun rankingIsWeightThenBiggestFishThenCount() {
        val e = engine(seats = 3)
        e.catches[0] += listOf(FishCatch("carp", 3000)); e.grams[0] = 3000
        e.catches[1] += listOf(FishCatch("bass", 1000), FishCatch("bass", 2000)); e.grams[1] = 3000
        e.catches[2] += listOf(FishCatch("pike", 5000)); e.grams[2] = 5000
        assertEquals(listOf(2, 0, 1), e.ranking())                                    // 2 heaviest; 0 and 1 tie on weight, 0 has the bigger fish
        assertFalse(e.tied(0, 1))
        e.catches[1].clear(); e.catches[1] += FishCatch("carp", 3000)
        assertTrue(e.tied(0, 1))
    }

    @Test fun fishWeightsAreTopHeavyAndStayInTheirSpeciesRange() {
        var big = 0; var total = 0
        for (seed in 0 until 400) {
            val e = engine(lake = "lake", seed = seed)
            cast(e, ticksForBand(seed % 4))
            val r = e.roll!!
            val frac = (r.grams - r.species.minG).toDouble() / (r.species.maxG - r.species.minG)
            total++; if (frac > 0.7) big++
            assertTrue(frac in 0.0..1.0)
        }
        assertTrue("a big fish is rare: $big of $total", big < total * 0.2)
        val pondSpecies = HashSet<String>()
        for (seed in 0 until 300) { val e = engine(lake = "pond", seed = seed); cast(e, ticksForBand(seed % 4)); pondSpecies += e.roll!!.species.id }
        assertTrue("pond never gives sturgeon, catfish, eel or moonpike", pondSpecies.none { it in setOf("sturgeon", "catfish", "eel", "moonpike") })
        assertTrue(pondSpecies.size >= 6)
    }

    // ---- determinism and fuzz ----

    @Test fun sameSeedSameInputsSameContest() {
        fun run(seed: Int): Long {
            val e = engine(seed = seed, seats = 3, rounds = 3)
            val r = Random(77)
            var g = 0
            while (e.phase != FishPhase.OVER && g++ < 400000) e.step(if (e.phase == FishPhase.REELING) e.tension < 0.6 && e.rush == null else r.nextInt(30) < 7)
            return e.fingerprint()
        }
        assertEquals(run(4), run(4))
        assertNotEquals(run(4), run(5))
    }

    @Test fun fuzzedButtonsNeverBreakAnInvariantAndEveryContestEnds() {
        for (seed in 0 until 60) {
            val r = Random(seed)
            val seats = 1 + r.nextInt(4)
            val e = engine(lake = FishingLakes.lakeIds[seed % 3], seats = seats, rounds = listOf(3, 5)[seed % 2], relaxed = seed % 3 == 0, seed = seed)
            var g = 0
            var down = false
            while (e.phase != FishPhase.OVER && g++ < 600000) {
                if (r.nextInt(12) == 0) down = r.nextBoolean()
                if (r.nextInt(300) == 0) e.aimCol = r.nextInt(5)
                e.step(down)
                assertTrue(e.tension in 0.0..1.05 && e.distance in -1.0..60.0)
                assertTrue(e.round in 0..e.rounds && e.turnIndex in 0 until seats)
            }
            assertEquals("seed $seed", FishPhase.OVER, e.phase)
            for (s in 0 until seats) {
                assertEquals(e.grams[s], e.catches[s].sumOf { it.grams })
                assertTrue(e.catches[s].size <= e.rounds && e.casts[s] <= e.rounds)
            }
            assertEquals((0 until seats).toSet(), e.ranking().toSet())
        }
    }
}
