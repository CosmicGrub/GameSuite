package com.gamesuite.games.bridgebuilders

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BridgeRulesTest {
    @Test fun theCartIsATriangleWaveBetweenZeroAndOne() {
        assertEquals(0.0, BridgeRules.triangle(0.0), 1e-9)
        assertEquals(1.0, BridgeRules.triangle(0.5), 1e-9)
        assertEquals(0.0, BridgeRules.triangle(1.0), 1e-9)
        assertEquals(0.5, BridgeRules.triangle(0.25), 1e-9)
        assertEquals(0.5, BridgeRules.triangle(0.75), 1e-9)
        assertEquals(BridgeRules.triangle(0.3), BridgeRules.triangle(3.3), 1e-9)
        for (i in 0..400) assertTrue(BridgeRules.triangle(i * 0.037) in 0.0..1.0)
    }

    @Test fun aFullPassTakesPassMsAtNormalPaceAndGameSpeedScalesIt() {
        val b = BridgeBand.MEDIUM
        val perMs = BridgeRules.phasePerMs(b, relaxed = false, pace = 1.0)
        // half a period (phase 0.5) is one pass
        assertEquals(BridgeRules.band(b).passMs.toDouble(), 0.5 / perMs, 1e-6)
        assertEquals(perMs / 2, BridgeRules.phasePerMs(b, false, 0.5), 1e-12)
        assertEquals(perMs * BridgeRules.RELAXED_SPEED, BridgeRules.phasePerMs(b, true, 1.0), 1e-12)
        // absurd paces are clamped
        assertEquals(BridgeRules.phasePerMs(b, false, BridgeRules.MIN_PACE), BridgeRules.phasePerMs(b, false, 0.0), 1e-12)
        assertEquals(BridgeRules.phasePerMs(b, false, BridgeRules.MAX_PACE), BridgeRules.phasePerMs(b, false, 99.0), 1e-12)
    }

    @Test fun bandsGetHarderLittleToBig() {
        val l = BridgeRules.band(BridgeBand.LITTLE); val m = BridgeRules.band(BridgeBand.MEDIUM); val g = BridgeRules.band(BridgeBand.BIG)
        assertTrue(l.window > m.window && m.window > g.window)
        assertTrue(l.passMs > m.passMs && m.passMs > g.passMs)
        assertTrue(l.waterSeconds > m.waterSeconds && m.waterSeconds > g.waterSeconds)
        assertTrue(l.spans < m.spans && m.spans < g.spans)
        assertTrue(l.stumbleMs < m.stumbleMs && m.stumbleMs < g.stumbleMs)
        assertEquals(BridgeBand.MEDIUM, BridgeBand.fromId("nope")); assertEquals(BridgeBand.BIG, BridgeBand.fromId("big")); assertEquals(BridgeBand.MEDIUM, BridgeBand.fromId(null))
    }

    @Test fun waterRisesFasterInBiggerBandsAndSlowerWhenRelaxed() {
        assertTrue(BridgeRules.waterPerMs(BridgeBand.LITTLE, false) < BridgeRules.waterPerMs(BridgeBand.BIG, false))
        assertTrue(BridgeRules.waterPerMs(BridgeBand.MEDIUM, true) < BridgeRules.waterPerMs(BridgeBand.MEDIUM, false))
        assertEquals(1.0, BridgeRules.waterPerMs(BridgeBand.MEDIUM, false) * 85_000.0, 1e-9)
    }

    @Test fun hitQualityIsOneInTheMiddleFallsToTheEdgeAndIsZeroOutside() {
        val w = 0.24
        assertEquals(1.0, BridgeRules.hitQuality(0.0, w), 1e-9)
        assertEquals(1.0, BridgeRules.hitQuality(0.12 * BridgeRules.PERFECT_FRACTION, w), 1e-9)
        assertEquals(BridgeRules.EDGE_QUALITY, BridgeRules.hitQuality(0.12, w), 1e-9)
        assertEquals(0.0, BridgeRules.hitQuality(0.13, w), 1e-9)
        var prev = 1.0
        for (i in 0..12) { val q = BridgeRules.hitQuality(i * 0.01, w); assertTrue(q <= prev + 1e-12); prev = q }
    }

    @Test fun tiersFollowQuality() {
        assertEquals(BridgeTier.PERFECT, BridgeRules.tierOf(1.0))
        assertEquals(BridgeTier.GOOD, BridgeRules.tierOf(0.8))
        assertEquals(BridgeTier.WOBBLY, BridgeRules.tierOf(0.45))
    }

    @Test fun relaxedQualityIsNeverAMissAndStillRewardsTheMiddle() {
        val w = 0.2
        for (i in 0..100) {
            val q = BridgeRules.relaxedQuality(i / 100.0, w)
            assertTrue(q >= BridgeRules.RELAXED_MIN_QUALITY - 1e-9 && q <= 1.0)
        }
        assertEquals(1.0, BridgeRules.relaxedQuality(0.0, w), 1e-9)
        assertTrue(BridgeRules.relaxedQuality(0.05, w) > BridgeRules.relaxedQuality(0.5, w))
    }

    @Test fun windowsStayInsideTheBarAndHandicapWidensThem() {
        val random = Random(7)
        for (b in BridgeBand.entries) for (bonus in listOf(0.0, 0.5, 1.0)) {
            val w = BridgeRules.windowWidth(b, bonus)
            assertTrue(w <= BridgeRules.MAX_WINDOW)
            repeat(200) {
                val c = BridgeRules.pickCentre(random, w)
                assertTrue(c - w / 2 >= 0.0 && c + w / 2 <= 1.0)
            }
        }
        assertTrue(BridgeRules.windowWidth(BridgeBand.MEDIUM, 0.5) > BridgeRules.windowWidth(BridgeBand.MEDIUM, 0.0))
        assertEquals(0.24 * 1.5, BridgeRules.windowWidth(BridgeBand.MEDIUM, 0.5), 1e-9)
        assertEquals(BridgeRules.windowWidth(BridgeBand.MEDIUM, 1.0), BridgeRules.windowWidth(BridgeBand.MEDIUM, 7.0), 1e-9) // clamped
    }

    @Test fun theCooperationRuleStopsALaneTwoPlanksAhead() {
        assertFalse(BridgeRules.mustWait(1, 0, 5))
        assertTrue(BridgeRules.mustWait(2, 0, 5))
        assertFalse(BridgeRules.mustWait(2, 1, 5))
        assertFalse(BridgeRules.mustWait(5, 3, 5)) // a finished lane never "waits"
    }

    @Test fun sturdierBridgesCrossFaster() {
        val weak = BridgeRules.crossMs(BridgeBand.MEDIUM, 0.0)
        val strong = BridgeRules.crossMs(BridgeBand.MEDIUM, 1.0)
        assertTrue(strong < weak)
        assertEquals(3200.0, strong.toDouble(), 1.0); assertEquals(6000.0, weak.toDouble(), 1.0)
    }

    @Test fun handicapParsing() {
        assertEquals(listOf(0.0, 0.0, 0.0), BridgeHandicap.parse("none", 3))
        assertEquals(listOf(0.0, 0.0), BridgeHandicap.parse(null, 2))
        assertEquals(listOf(0.0, BridgeRules.HANDICAP_BONUS, 0.0), BridgeHandicap.parse("p2", 3))
        assertEquals(listOf(0.0, 0.0), BridgeHandicap.parse("p4", 2)) // no such lane
        assertEquals(listOf(0.0, 0.0), BridgeHandicap.parse("p9", 2))
        assertEquals(listOf(0.0, 0.25, 1.0, 0.0), BridgeHandicap.parse("0, 0.25, 7, -2", 4))
        assertEquals(listOf(0.0, 0.0, 0.0), BridgeHandicap.parse("0.5,0.5", 3)) // wrong count
        assertEquals(listOf(0.0, 0.0), BridgeHandicap.parse("abc,def", 2))
        assertEquals(listOf(0.0, 0.0), BridgeHandicap.parse("x".repeat(100), 2))
    }

    @Test fun helperRobotsGetSteadierWithLevel() {
        val e = BridgeRules.bot(CpuDifficulty.EASY); val m = BridgeRules.bot(CpuDifficulty.MEDIUM); val h = BridgeRules.bot(CpuDifficulty.HARD)
        assertTrue(e.missChance > m.missChance && m.missChance > h.missChance)
        assertTrue(e.tolMax > m.tolMax && m.tolMax > h.tolMax)
        assertTrue(e.reactMs > m.reactMs && m.reactMs > h.reactMs)
        for (p in listOf(e, m, h)) assertTrue(p.tolMin in 0.0..p.tolMax && p.tolMax <= 1.0)
    }
}
