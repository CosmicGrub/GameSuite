package com.gamesuite.games.bridgebuilders

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BridgeEngineTest {
    private fun BridgeRig.dist(seat: Int): Double = abs(s.lanes[seat].marker - s.lanes[seat].windowCentre).toDouble()
    private fun BridgeRig.half(seat: Int): Double = s.lanes[seat].windowWidth / 2.0

    // ---- marker motion ----

    @Test fun thereIsACountdownThenBuilding() {
        val r = bridgeRig()
        assertEquals("COUNTDOWN", r.s.phase)
        assertTrue(r.s.countdownMs in 1..BridgeRules.COUNTDOWN_MS.toInt())
        assertEquals(BridgeTapResult.NOT_BUILDING, r.e.tap(0))
        r.live()
        assertEquals("BUILD", r.s.phase)
        assertEquals(BridgeEvent.GO, r.s.event)
    }

    @Test fun theCartSlidesBackAndForthBetweenTheEnds() {
        val r = bridgeRig()
        r.live()
        var lo = 1f; var hi = 0f
        var turns = 0; var lastDir = 0
        var prev = r.s.lanes[0].marker
        repeat(600) {
            r.adv(10)
            val m = r.s.lanes[0].marker
            assertTrue(m in 0f..1f)
            lo = minOf(lo, m); hi = maxOf(hi, m)
            val dir = if (m > prev) 1 else if (m < prev) -1 else 0
            if (dir != 0 && lastDir != 0 && dir != lastDir) turns++
            if (dir != 0) lastDir = dir
            prev = m
        }
        assertTrue(lo < 0.02f && hi > 0.98f)
        assertTrue("turned $turns times", turns >= 3)
    }

    @Test fun aPassTakesAboutPassMs() {
        val r = bridgeRig(band = BridgeBand.BIG)
        r.live()
        val turns = mutableListOf<Long>()
        var prev = r.s.lanes[0].marker
        var dir = 0
        repeat(600) {
            r.adv(10)
            val m = r.s.lanes[0].marker
            val d = if (m > prev) 1 else if (m < prev) -1 else dir
            if (dir != 0 && d != dir) turns += r.clock.now
            dir = d; prev = m
        }
        assertTrue(turns.size >= 3)
        for (i in 1 until turns.size) assertEquals(BridgeRules.band(BridgeBand.BIG).passMs.toDouble(), (turns[i] - turns[i - 1]).toDouble(), 40.0)
    }

    @Test fun gameSpeedPaceSlowsTheCart() {
        fun pathLength(pace: Double): Float {
            val r = bridgeRig(); r.e.pace = pace; r.live()
            var total = 0f
            var prev = r.s.lanes[0].marker
            repeat(200) { r.adv(10); val m = r.s.lanes[0].marker; total += abs(m - prev); prev = m }
            return total
        }
        val slow = pathLength(0.5); val normal = pathLength(1.0)
        assertEquals(normal / 2f, slow, 0.05f)
    }

    // ---- window hits ----

    @Test fun aTapInTheMiddleOfTheGreenSpotPlacesAPerfectPlank() {
        val r = bridgeRig()
        r.live()
        val res = r.place(0)
        assertEquals(BridgeTapResult.PERFECT, res)
        assertEquals(1, r.s.lanes[0].planks)
        assertEquals(BridgeTier.PERFECT, r.s.lanes[0].lastTier)
        assertEquals(1f, r.s.lanes[0].lastQuality, 1e-6f)
    }

    @Test fun nearerTheEdgeMakesAWeakerPlank() {
        val good = bridgeRig(); good.live()
        val gRes = good.tapWhen(0) { l -> val d = abs(l.marker - l.windowCentre); val h = l.windowWidth / 2; d >= 0.4 * h && d <= 0.6 * h }
        assertEquals(BridgeTapResult.GOOD, gRes)
        val wob = bridgeRig(); wob.live()
        val wRes = wob.tapWhen(0) { l -> val d = abs(l.marker - l.windowCentre); val h = l.windowWidth / 2; d >= 0.8 * h && d <= 0.98 * h }
        assertEquals(BridgeTapResult.WOBBLY, wRes)
        assertTrue(wob.s.lanes[0].lastQuality < good.s.lanes[0].lastQuality)
        assertTrue(wob.s.lanes[0].lastQuality >= BridgeRules.EDGE_QUALITY)
    }

    @Test fun aTapOutsideTheSpotMissesAndTheCartRestsThenGoesOn() {
        val r = bridgeRig()
        r.live()
        val res = r.tapWhen(0) { l -> abs(l.marker - l.windowCentre) > l.windowWidth / 2 + 0.03 }
        assertEquals(BridgeTapResult.MISS, res)
        assertEquals(0, r.s.lanes[0].planks)
        assertEquals(BridgeTier.MISS, r.s.lanes[0].lastTier)
        assertEquals(1, r.s.misses)
        assertEquals(BridgeLaneStatus.STUMBLED, r.s.lanes[0].status)
        val frozen = r.s.lanes[0].marker
        assertEquals(BridgeTapResult.STUMBLED, r.e.tap(0))
        r.adv(100)
        assertEquals(frozen, r.s.lanes[0].marker, 1e-6f)
        r.adv(BridgeRules.band(BridgeBand.MEDIUM).stumbleMs + 40)
        assertEquals(BridgeLaneStatus.MOVING, r.s.lanes[0].status)
        assertEquals(0, r.s.lanes[0].planks)
    }

    @Test fun aMissDoesNotMoveTheGreenSpot() {
        val r = bridgeRig()
        r.live()
        val c = r.s.lanes[0].windowCentre
        r.tapWhen(0) { l -> abs(l.marker - l.windowCentre) > l.windowWidth / 2 + 0.03 }
        assertEquals(c, r.s.lanes[0].windowCentre, 1e-6f)
    }

    @Test fun aPlacedPlankMovesTheGreenSpotAndKeepsItOnTheBar() {
        val r = bridgeRig(seed = 3)
        r.live()
        repeat(2) {
            r.place(0); r.place(1)
            val l = r.s.lanes[0]
            assertTrue(l.windowCentre - l.windowWidth / 2 >= 0f && l.windowCentre + l.windowWidth / 2 <= 1f)
        }
    }

    // ---- cooperation ----

    @Test fun aLaneCannotGoMoreThanTwoPlanksAheadOfTheSlowest() {
        val r = bridgeRig(humans = 3)
        r.live()
        assertTrue(r.place(0).placed); assertTrue(r.place(0).placed)
        assertEquals(2, r.s.lanes[0].planks)
        assertEquals(BridgeLaneStatus.WAITING, r.s.lanes[0].status)
        val frozen = r.s.lanes[0].marker
        assertEquals(BridgeTapResult.WAITING, r.e.tap(0))
        r.adv(500)
        assertEquals(frozen, r.s.lanes[0].marker, 1e-6f) // the cart waits
        assertEquals(2, r.s.lanes[0].lead)
        // one friend moving is not enough: lane 2 is still at zero
        r.place(1)
        assertEquals(BridgeLaneStatus.WAITING, r.s.lanes[0].status)
        r.place(2)
        assertEquals(1, r.s.minPlanks)
        assertEquals(BridgeLaneStatus.MOVING, r.s.lanes[0].status)
        assertTrue(r.place(0).placed)
        assertEquals(3, r.s.lanes[0].planks)
    }

    @Test fun noLaneIsEverMoreThanTwoAheadWhateverIsTapped() {
        val r = bridgeRig(humans = 2, bots = 1, level = CpuDifficulty.HARD, seed = 9, band = BridgeBand.LITTLE)
        r.live()
        repeat(3000) {
            r.adv(10)
            // hammer lane 0 and 1 with taps at every step
            r.e.tap(0); r.e.tap(1)
            val p = r.s.lanes.map { it.planks }
            assertTrue("planks $p", p.max() - p.min() <= BridgeRules.MAX_LEAD)
        }
    }

    @Test fun aWaitingLaneIsNotPunished() {
        val r = bridgeRig()
        r.live()
        r.place(0); r.place(0)
        val misses = r.s.misses
        repeat(5) { r.e.tap(0) }
        assertEquals(misses, r.s.misses)
        assertEquals(2, r.s.lanes[0].planks)
    }

    // ---- water, win, lose ----

    @Test fun theWaterRisesAtTheBandRate() {
        val r = bridgeRig(band = BridgeBand.MEDIUM)
        r.live()
        val w0 = r.s.water
        r.adv(10_000)
        assertEquals(w0 + 10.0f / 85f, r.s.water, 0.005f)
        val big = bridgeRig(band = BridgeBand.BIG); big.live(); big.adv(10_000)
        val little = bridgeRig(band = BridgeBand.LITTLE); little.live(); little.adv(10_000)
        assertTrue(big.s.water > r.s.water && r.s.water > little.s.water)
    }

    @Test fun aSturdyPlankPushesTheWaterBack() {
        val r = bridgeRig()
        r.live()
        r.adv(20_000)
        val before = r.s.water
        assertEquals(BridgeTapResult.PERFECT, r.place(0))
        // the water also rose while the cart reached the middle (at most about 0.016), but a perfect plank pushes back 0.02
        assertTrue(r.s.water < before)
    }

    @Test fun theWaterNeverGoesBelowEmpty() {
        val r = bridgeRig(); r.live()
        r.place(0)
        assertTrue(r.s.water >= 0f)
    }

    @Test fun whenWaterCoversTheBridgeItIsATryAgainTogether() {
        val r = bridgeRig(band = BridgeBand.BIG)
        r.live()
        r.adv(80_000)
        assertTrue(r.s.over)
        assertFalse(r.s.won)
        assertEquals(BridgeEvent.LOSE, r.s.event)
        assertEquals(0, r.s.bridgesBuilt)
        assertEquals(1f, r.s.water, 1e-6f)
        assertFalse(r.e.tick())
        assertEquals(BridgeTapResult.NOT_BUILDING, r.e.tap(0))
    }

    @Test fun everyoneBuildingAllPlanksThenCrossingIsAWin() {
        val r = bridgeRig()
        r.live()
        val spans = r.s.spans
        repeat(spans) { r.place(0); r.place(1) }
        assertEquals("CROSS", r.s.phase)
        assertEquals(BridgeEvent.CROSS, r.s.event)
        assertEquals(1f, r.s.strength, 1e-3f) // every plank perfect
        r.adv(1000)
        assertTrue(r.s.lanes.all { it.walk in 0.01f..0.99f })
        r.adv(BridgeRules.crossMs(BridgeBand.MEDIUM, 1.0) + 200)
        assertTrue(r.s.over && r.s.won)
        assertEquals(BridgeEvent.WIN, r.s.event)
        assertEquals(1, r.s.bridgesBuilt)
        assertTrue(r.s.lanes.all { it.walk == 1f })
    }

    @Test fun weakPlanksMakeAWeakerBridgeAndALongerCrossing() {
        fun crossDuration(edge: Boolean): Long {
            val r = bridgeRig(humans = 2, band = BridgeBand.LITTLE)
            r.live()
            repeat(r.s.spans) { for (seat in 0..1) {
                if (edge) r.tapWhen(seat) { l -> val d = abs(l.marker - l.windowCentre); val h = l.windowWidth / 2; l.status == BridgeLaneStatus.MOVING && d >= 0.85 * h && d <= 0.98 * h }
                else r.place(seat)
            } }
            assertEquals("CROSS", r.s.phase)
            val start = r.clock.now
            while (r.s.phase == "CROSS") r.adv(10)
            return r.clock.now - start
        }
        val sturdy = crossDuration(false)
        val weak = crossDuration(true)
        assertTrue("weak $weak vs sturdy $sturdy", weak > sturdy)
    }

    @Test fun weakPlanksLowerTheSharedStrengthMeter() {
        val r = bridgeRig(humans = 2)
        r.live()
        r.tapWhen(0) { l -> val d = abs(l.marker - l.windowCentre); val h = l.windowWidth / 2; d >= 0.85 * h && d <= 0.98 * h }
        val s = r.s
        assertTrue(s.strength > 0f)
        assertTrue(s.strength < 1f / (2 * s.spans)) // less than one perfect plank's worth
        assertTrue(s.sturdiness < 0.6f)
    }

    @Test fun aRowMilestoneIsAnnouncedOnlyWhenEveryLaneHasMore() {
        val r = bridgeRig()
        r.live()
        val serial0 = r.s.eventSerial
        r.place(0)
        assertEquals(serial0, r.s.eventSerial) // one lane alone is not a milestone
        r.place(1)
        assertEquals(BridgeEvent.ROW, r.s.event); assertEquals(1, r.s.eventArg)
    }

    @Test fun waterMilestonesAreAnnouncedOnceEachThenTheTryEnds() {
        val r = bridgeRig(band = BridgeBand.BIG)
        r.live()
        val events = mutableListOf<String>()
        var last = r.s.eventSerial
        repeat(8000) { r.adv(10); if (r.s.eventSerial != last) { last = r.s.eventSerial; events += r.s.event } }
        assertEquals(listOf(BridgeEvent.WATER_HALF, BridgeEvent.WATER_HIGH, BridgeEvent.LOSE), events)
    }

    // ---- crossing: the water waits ----

    private fun fullSave(lanes: Int, water: Double) = BridgeSave(
        players = bridgeSeats(lanes, 0).map { BridgePlayerSave(it.playerId, it.isBot) },
        band = "medium", relaxed = false, handicap = "none", difficulty = "MEDIUM",
        lanes = List(lanes) { BridgeLaneSave(5, 5.0) }, water = water, bridges = 0, tries = 1
    )

    @Test fun theWaterStopsRisingOnceTheLastPlankIsIn() {
        val r = bridgeRig(band = BridgeBand.LITTLE)
        r.live()
        r.adv(30_000)
        repeat(r.s.spans) { r.place(0); r.place(1) }
        assertEquals("CROSS", r.s.phase)
        val atCross = r.s.water
        r.adv(1500)
        assertEquals("CROSS", r.s.phase)
        assertEquals(atCross, r.s.water, 0f)
        r.adv(BridgeRules.crossMs(BridgeBand.LITTLE, 1.0) + 500)
        assertTrue(r.s.over && r.s.won)
        assertEquals(atCross, r.s.water, 0f)
    }

    @Test fun aBridgeFinishedWithAlmostFullWaterStillGetsEverybodyAcross() {
        // water at 0.98 would have covered the bridge within about 1.7 s of the 3.2 to 6 s crossing
        val r = bridgeRig(humans = 2)
        assertTrue(r.e.restore(fullSave(2, 0.98)))
        r.live()
        assertEquals("CROSS", r.s.phase)
        r.adv(BridgeRules.crossMs(BridgeBand.MEDIUM, 0.0) + 500)
        assertTrue("the friends must get across", r.s.over && r.s.won)
        assertEquals(BridgeEvent.WIN, r.s.event)
        assertEquals(1, r.s.bridgesBuilt)
        assertTrue(r.s.water < 1f)
    }

    @Test fun theWaterStillDrownsAnUnfinishedBridge() {
        val r = bridgeRig(humans = 2)
        val save = fullSave(2, 0.999).copy(lanes = listOf(BridgeLaneSave(5, 5.0), BridgeLaneSave(4, 4.0)))
        assertTrue(r.e.restore(save))
        r.live()
        r.adv(500)
        assertTrue(r.s.over && !r.s.won)
        assertEquals(BridgeEvent.LOSE, r.s.event)
    }

    // ---- pause and the fixed-step clock ----

    @Test fun aLongPauseWithNoTicksInBetweenDoesNotBurstOnResume() {
        val r = bridgeRig()
        r.live()
        r.adv(2000)
        r.e.pause()
        val w = r.s.water; val m = r.s.lanes[0].marker
        r.clock.advance(120_000)            // a dialog stayed open for two minutes, nobody ticked
        r.e.resume()
        r.e.tick()
        assertEquals(w, r.s.water, 0.0005f)
        assertEquals(m, r.s.lanes[0].marker, 0.02f)
        assertEquals("BUILD", r.s.phase)
    }

    @Test fun aHugeClockJumpIsCappedAtOneSecondOfSimulation() {
        val r = bridgeRig(band = BridgeBand.MEDIUM)
        r.live()
        val w = r.s.water
        r.clock.advance(60_000)
        r.e.tick()
        val oneSecond = (BridgeRules.waterPerMs(BridgeBand.MEDIUM, false) * 1000.0).toFloat()
        assertTrue("water moved ${r.s.water - w}", r.s.water - w <= oneSecond * 1.2f)
        assertFalse(r.s.over)
    }

    @Test fun pausingBeforeAnyTickAndResumingKeepsTheCountdownWhereItWas() {
        val r = bridgeRig()
        r.adv(1000)
        val left = r.s.countdownMs
        r.e.pause()
        r.clock.advance(30_000)
        r.e.resume()
        r.e.tick()
        assertEquals(left.toDouble(), r.s.countdownMs.toDouble(), 30.0)
        assertEquals("COUNTDOWN", r.s.phase)
    }

    // ---- roster guards ----

    @Test fun anEngineTableOfOneOrFiveLanesIsRefusedLoudly() {
        for (n in listOf(0, 1, 5, 6)) {
            val e = BridgeEngine(kotlin.random.Random(1), BridgeClock())
            val failed = try { e.setup(bridgeSeats(n, 0)); false } catch (_: IllegalArgumentException) { true }
            assertTrue("a table of $n lanes must be refused", failed)
        }
        for (n in 2..4) BridgeEngine(kotlin.random.Random(1), BridgeClock()).setup(bridgeSeats(n, 0))
    }

    @Test fun savesNeverFitATableOfTheWrongSize() {
        val save = fullSave(2, 0.3)
        assertTrue(BridgeSaveCodec.isValid(save, bridgeSeats(2, 0)))
        assertFalse(BridgeSaveCodec.isValid(save, bridgeSeats(1, 0)))
        assertFalse(BridgeSaveCodec.isValid(save, bridgeSeats(5, 0)))
        assertFalse(BridgeSaveCodec.isValid(fullSave(5, 0.3), bridgeSeats(5, 0)))
    }

    // ---- difficulty and handicap ----

    @Test fun biggerBandsBringSmallerGreenSpotsAndMorePlanks() {
        val l = bridgeRig(band = BridgeBand.LITTLE); val m = bridgeRig(band = BridgeBand.MEDIUM); val b = bridgeRig(band = BridgeBand.BIG)
        assertTrue(l.s.lanes[0].windowWidth > m.s.lanes[0].windowWidth && m.s.lanes[0].windowWidth > b.s.lanes[0].windowWidth)
        assertEquals(listOf(4, 5, 6), listOf(l, m, b).map { it.s.spans })
    }

    @Test fun aHandicapWidensOnlyThatLanesGreenSpot() {
        val r = bridgeRig(humans = 3, handicap = "p2")
        val w = r.s.lanes.map { it.windowWidth }
        assertEquals(w[0] * 1.5f, w[1], 1e-5f)
        assertEquals(w[0], w[2], 1e-6f)
        assertEquals(listOf(false, true, false), r.s.lanes.map { it.widened })
        r.live(); r.place(1)
        assertEquals(w[1], r.s.lanes[1].windowWidth, 1e-6f) // still wide after the next spot is picked
    }

    @Test fun aHandicapMakesThatLaneEasierToHit() {
        // Over a whole sweep, the share of positions inside the green spot is 1.5x larger.
        val plain = bridgeRig(humans = 2, handicap = "none"); val wide = bridgeRig(humans = 2, handicap = "p1")
        assertTrue(wide.s.lanes[0].windowWidth > plain.s.lanes[0].windowWidth)
        assertEquals(plain.s.lanes[1].windowWidth, wide.s.lanes[1].windowWidth, 1e-6f)
    }

    // ---- relaxed ----

    @Test fun relaxedModeNeverMissesButStillScoresTheTiming() {
        val r = bridgeRig(relaxed = true)
        r.live()
        val far = r.tapWhen(0) { l -> abs(l.marker - l.windowCentre) > l.windowWidth / 2 + 0.05 }
        assertTrue(far.placed)
        assertEquals(1, r.s.lanes[0].planks)
        assertEquals(0, r.s.misses)
        assertTrue(r.s.lanes[0].lastQuality >= BridgeRules.RELAXED_MIN_QUALITY - 1e-6f)
        val mid = r.place(1)
        assertEquals(BridgeTapResult.PERFECT, mid)
        assertTrue(r.s.lanes[1].lastQuality > r.s.lanes[0].lastQuality)
    }

    @Test fun relaxedModeIsSlowerCartAndSlowerWater() {
        val n = bridgeRig(); n.live(); n.adv(10_000)
        val x = bridgeRig(relaxed = true); x.live(); x.adv(10_000)
        assertTrue(x.s.water < n.s.water)
        assertTrue(x.s.relaxed && !n.s.relaxed)
    }

    // ---- helper robots ----

    private fun botRun(seed: Int, level: CpuDifficulty, band: BridgeBand = BridgeBand.LITTLE, ms: Long = 45_000): BridgeState {
        val r = bridgeRig(humans = 0, bots = 2, band = band, level = level, seed = seed)
        r.adv(BridgeRules.COUNTDOWN_MS + ms)
        return r.s
    }

    @Test fun helperRobotsReplayExactlyWithTheSameSeed() {
        for (level in CpuDifficulty.entries) {
            val a = botRun(5, level); val b = botRun(5, level)
            assertEquals(a, b)
        }
        assertTrue(botRun(5, CpuDifficulty.MEDIUM) != botRun(6, CpuDifficulty.MEDIUM))
    }

    @Test fun helperRobotsBuildABridgeAloneOnTheLittleBand() {
        val s = botRun(2, CpuDifficulty.HARD)
        assertTrue(s.over && s.won)
        assertEquals(1, s.bridgesBuilt)
    }

    @Test fun harderRobotsPlaceSturdierPlanks() {
        var easy = 0.0; var hard = 0.0
        for (seed in 1..4) {
            easy += botRun(seed, CpuDifficulty.EASY, BridgeBand.MEDIUM, 25_000).sturdiness
            hard += botRun(seed, CpuDifficulty.HARD, BridgeBand.MEDIUM, 25_000).sturdiness
        }
        assertTrue("easy $easy hard $hard", hard > easy)
    }

    @Test fun aHelperRobotRespectsTheTwoPlankRuleAndAHumanCannotTapIt() {
        val r = bridgeRig(humans = 1, bots = 1, level = CpuDifficulty.HARD, band = BridgeBand.LITTLE, seed = 4)
        r.live()
        assertEquals(BridgeTapResult.BOT_SEAT, r.e.tap(1))
        assertEquals(BridgeTapResult.BAD_SEAT, r.e.tap(7))
        r.adv(30_000) // the human never taps: the robot must stop two ahead
        assertTrue(r.s.lanes[1].planks <= 2)
        assertEquals(BridgeLaneStatus.WAITING, r.s.lanes[1].status)
    }

    @Test fun robotLevelsCanBeSetPerSeat() {
        val clock = BridgeClock()
        val e = BridgeEngine(kotlin.random.Random(1), clock)
        e.setup(bridgeSeats(0, 2)); e.seatLevelOverride[0] = CpuDifficulty.HARD; e.seatLevelOverride[1] = CpuDifficulty.EASY
        e.start()
        val r = BridgeRig(e, clock)
        r.adv(BridgeRules.COUNTDOWN_MS + 20_000)
        assertTrue(r.s.lanes.all { it.planks > 0 })
    }

    // ---- pause ----

    @Test fun pausingStopsTheWaterAndTheCart() {
        val r = bridgeRig()
        r.live()
        r.adv(1000)
        r.e.pause()
        val w = r.s.water; val m = r.s.lanes[0].marker
        r.adv(5000)
        assertEquals(w, r.s.water, 0f); assertEquals(m, r.s.lanes[0].marker, 0f)
        assertEquals(BridgeTapResult.NOT_BUILDING, r.e.tap(0))
        r.e.resume()
        r.adv(1000)
        assertTrue(r.s.water > w)
    }

    // ---- sessions ----

    @Test fun aNewTryKeepsTheSessionTallyAndStartsFresh() {
        val r = bridgeRig(band = BridgeBand.LITTLE)
        r.live()
        repeat(r.s.spans) { r.place(0); r.place(1) }
        r.adv(8000)
        assertTrue(r.s.won)
        r.e.start()
        assertEquals(1, r.s.bridgesBuilt)
        assertEquals(2, r.s.tries)
        assertEquals(0, r.s.lanes[0].planks)
        assertEquals(0f, r.s.water, 0f)
        assertEquals("COUNTDOWN", r.s.phase)
    }
}
