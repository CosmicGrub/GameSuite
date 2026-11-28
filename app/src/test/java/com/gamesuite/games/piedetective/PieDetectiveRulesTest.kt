package com.gamesuite.games.piedetective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cast and rules tables of docs/design/kids-games/pie-detective.md sections 2.5, 2.6 and 2.9 (tests P01-P07). */
class PieDetectiveRulesTest {
    @Test
    fun castHasTwelveSuspectsWithUniqueIdsAndNames() {
        assertEquals(12, PieCast.all.size)
        assertEquals(12, PieCast.all.map { it.id }.toSet().size)
        assertEquals(12, PieCast.all.map { it.speciesId }.toSet().size)
        assertEquals(12, PieCast.all.map { it.furColour }.toSet().size)
        for (s in PieCast.all) {
            assertEquals(6, s.vector.length)
            assertTrue(s.vector.all { it == '0' || it == '1' })
        }
        assertEquals(null, PieCast.byId("fox"))
        assertEquals("duck", PieCast.byId("duck")!!.id)
    }

    @Test
    fun firstFourTraitsAreUniqueAcrossTheCast() {
        val firstFour = PieCast.all.map { it.vector.take(4) }
        assertEquals(12, firstFour.toSet().size)
        for (unused in listOf("0000", "0111", "1110", "1111")) assertTrue(unused !in firstFour)
    }

    @Test
    fun traitTotalsMatchTheDesignTable() {
        val totals = PieTrait.entries.map { t -> PieCast.all.count { it.has(t) } }
        assertEquals(listOf(6, 5, 5, 6, 6, 6), totals)
    }

    @Test
    fun bandParamsMatchTheDesignTable() {
        val l = PieRules.band(PieBand.LITTLE)
        assertEquals(listOf(8, 4, 4, 2), listOf(l.lineup, l.traits, l.dashCards, l.cases))
        assertTrue(l.hintsDefault && l.autoFlipDefault); assertEquals(55, l.secondsPerTurn)
        val m = PieRules.band(PieBand.MEDIUM)
        assertEquals(listOf(10, 5, 5, 3), listOf(m.lineup, m.traits, m.dashCards, m.cases))
        assertTrue(m.hintsDefault && !m.autoFlipDefault); assertEquals(45, m.secondsPerTurn)
        val b = PieRules.band(PieBand.BIG)
        assertEquals(listOf(12, 6, 6, 3), listOf(b.lineup, b.traits, b.dashCards, b.cases))
        assertTrue(!b.hintsDefault && !b.autoFlipDefault); assertEquals(50, b.secondsPerTurn)
        assertEquals(PieTrait.entries.take(4), PieRules.traitsOf(PieBand.LITTLE))
        assertEquals(PieTrait.entries, PieRules.traitsOf(PieBand.BIG))
        assertEquals(PieBand.MEDIUM, PieBand.fromId("nonsense"))
        // minutes estimate stays inside the 8 to 15 minute target of design doc 3.1
        for (band in PieBand.entries) assertTrue(PieRules.estimatedMinutes(band, 4.6, 2) in 8.0..15.0)
    }

    @Test
    fun laneHasThreeHopsAndWrongCatchCostsTwo() {
        assertEquals(3, PieRules.LANE_HOPS)
        assertEquals(2, PieRules.WRONG_CATCH_HOPS)
        assertEquals(listOf("stall", "fountain", "bridge", "gate"), PieRules.lanePlaces)
        assertEquals(PieRules.LANE_HOPS + 1, PieRules.lanePlaces.size)
        assertEquals(3, PieRules.starsForCatch(0)); assertEquals(3, PieRules.starsForCatch(1)); assertEquals(2, PieRules.starsForCatch(2))
        assertEquals(12, PieRules.SNEAK_DECK)
        for (band in PieBand.entries) {
            val deck = PieDealer.deck(5L, 0, band)
            assertEquals(12, deck.size)
            assertEquals(PieRules.band(band).dashCards, deck.count { it })
        }
    }

    @Test
    fun bookWinThresholdIsHalfRoundedUp() {
        assertEquals(1, PieRules.bookWinThreshold(2))
        assertEquals(2, PieRules.bookWinThreshold(3))
        assertEquals(1, PieRules.bookWinThreshold(1))
    }

    @Test
    fun handicapParseAcceptsNoneAndP1ToP4AndRejectsGarbage() {
        assertEquals(listOf(0, 0, 0), PieHandicap.parse("none", 3))
        assertEquals(listOf(0, 0), PieHandicap.parse(null, 2))
        assertEquals(listOf(1, 0), PieHandicap.parse("p1", 2))
        assertEquals(listOf(0, 0, 1, 0), PieHandicap.parse("P3", 4))
        assertEquals(listOf(0, 0), PieHandicap.parse("p4", 2)) // seat 4 does not exist at a 2-seat table
        assertEquals(listOf(0, 0), PieHandicap.parse("p5", 2))
        assertEquals(listOf(0, 0), PieHandicap.parse("lots", 2))
        assertEquals(listOf(0, 1, 2), PieHandicap.parse("0,1,9", 3))
        assertEquals(listOf(0, 0, 0), PieHandicap.parse("1,x,2", 3))
        assertEquals(listOf(0, 0), PieHandicap.parse("p".repeat(100), 2))
        assertEquals(2, PieHandicap.parse("p1", 1).size) // seat count is clamped to 2..4
        assertEquals(4, PieHandicap.parse("p1", 9).size)
    }
}
