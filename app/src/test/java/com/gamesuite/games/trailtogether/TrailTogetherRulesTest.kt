package com.gamesuite.games.trailtogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The data tables, the path rules and the handicap parser (design doc sections 2.2, 2.5, 2.9; tests T01 to T06). */
class TrailTogetherRulesTest {
    /** The deck table of section 2.5 (the same numbers as the design doc's trail-spec): band, pals, hops per colour, lanterns, fog, total. */
    private data class Row(val band: TrailBand, val pals: Int, val hops: Int, val lanterns: Int, val fog: Int, val total: Int)

    private val table = listOf(
        Row(TrailBand.LITTLE, 2, 5, 2, 10, 48), Row(TrailBand.LITTLE, 3, 7, 3, 12, 59), Row(TrailBand.LITTLE, 4, 10, 4, 12, 72),
        Row(TrailBand.MEDIUM, 2, 4, 2, 10, 52), Row(TrailBand.MEDIUM, 3, 6, 3, 10, 63), Row(TrailBand.MEDIUM, 4, 8, 4, 10, 74),
        Row(TrailBand.BIG, 2, 4, 2, 10, 60), Row(TrailBand.BIG, 3, 6, 3, 10, 73), Row(TrailBand.BIG, 4, 8, 4, 10, 86)
    )

    @Test
    fun deckCompositionMatchesTableForEveryBandAndPalCount() {
        for (r in table) {
            val spec = TrailRules.deck(r.band, r.pals)
            val colours = TrailRules.band(r.band).colours
            assertEquals("stones ${r.band} ${r.pals}", 4, spec.stonesPerColour)
            assertEquals("hops ${r.band} ${r.pals}", r.hops, spec.hopsPerColour)
            assertEquals("lanterns ${r.band} ${r.pals}", r.lanterns, spec.lanterns)
            assertEquals("fog ${r.band} ${r.pals}", r.fog, spec.fogCards)
            assertEquals("total ${r.band} ${r.pals}", r.total, spec.totalCards(colours))
            // and the codes the snapshot validator expects add up the same way
            assertEquals(r.total, TrailSaveCodec.deckCounts(r.band, r.pals).values.sum())
        }
        // a real deal uses exactly that deck: hands + pile = everything
        for (r in table) {
            val e = trailStarted(5L, humans = r.pals, band = r.band)
            val save = e.toSave()!!
            assertEquals(r.total, save.hands.sumOf { it.size } + save.pile.size + save.discard.size)
        }
    }

    @Test
    fun fogCardsEqualFogTrackLengthForEveryBandAndPalCount() {
        for (b in TrailBand.entries) for (pals in 2..4) {
            val e = trailStarted(11L, humans = pals, band = b)
            val s = e.state()!!
            assertEquals(TrailRules.deck(b, pals).fogCards, s.fogLength)
            assertEquals(s.fogLength, e.toSave()!!.pile.count { it == "F" })
            assertTrue("the Burrow is the last spot", s.spots.last().index == s.length)
        }
    }

    @Test
    fun palsAreAtLeastTwoAndEqualSeatsUpToFour() {
        assertEquals(listOf(2, 2, 3, 4), (1..4).map { TrailRules.palsFor(it) })
        assertEquals(2, TrailRules.palsFor(0))
        assertEquals(4, TrailRules.palsFor(9))
        for (seats in 1..4) assertEquals(TrailRules.palsFor(seats), trailStarted(3L, humans = seats).state()!!.pals.size)
    }

    @Test
    fun handicapParseAcceptsNoneAndP1ToP4AndRejectsGarbage() {
        assertEquals(listOf(3, 3, 3), TrailHandicap.parse("none", 3))
        assertEquals(listOf(4, 3, 3), TrailHandicap.parse("p1", 3))
        assertEquals(listOf(3, 3, 4), TrailHandicap.parse("p3", 3))
        assertEquals(listOf(3, 3, 3, 4), TrailHandicap.parse("p4", 4))
        // a seat that is not at the table, or garbage, means no handicap
        assertEquals(listOf(3, 3), TrailHandicap.parse("p3", 2))
        assertEquals(listOf(3, 3), TrailHandicap.parse("p5", 2))
        assertEquals(listOf(3, 3), TrailHandicap.parse("P1", 2))
        assertEquals(listOf(3, 3), TrailHandicap.parse("everyone", 2))
        assertEquals(listOf(3, 3), TrailHandicap.parse(null, 2))
        assertEquals(listOf(3, 3), TrailHandicap.parse("", 2))
        assertEquals(listOf(3, 3), TrailHandicap.parse("p1".padEnd(80, 'x'), 2))
        // the per-profile comma list: one size per seat, clamped to 3..4
        assertEquals(listOf(3, 4, 3), TrailHandicap.parse("3,4,3", 3))
        assertEquals(listOf(4, 3), TrailHandicap.parse("9,1", 2))
        assertEquals(listOf(3, 3, 3), TrailHandicap.parse("3,4", 3))
        assertEquals(listOf(3, 3), TrailHandicap.parse("3,x", 2))
        // seat counts outside 1..4 are clamped
        assertEquals(4, TrailHandicap.parse("none", 9).size)
        assertEquals(1, TrailHandicap.parse("none", 0).size)
    }

    @Test
    fun handicapFromExtrasOnlyAcceptsTheSeatsAtTheTable() {
        assertEquals("handicap", TrailHandicap.keyFor(2))
        assertEquals("handicap3", TrailHandicap.keyFor(3))
        assertEquals("handicap4", TrailHandicap.keyFor(4))
        assertEquals("p2", TrailHandicap.fromExtras(mapOf("handicap" to "p2"), 2))
        assertEquals("p3", TrailHandicap.fromExtras(mapOf("handicap3" to "p3"), 3))
        assertEquals("none", TrailHandicap.fromExtras(mapOf("handicap" to "p3"), 2))
        assertEquals("none", TrailHandicap.fromExtras(mapOf("handicap3" to "p4"), 3))
        assertEquals("none", TrailHandicap.fromExtras(mapOf("handicap" to "p1"), 1))
        assertEquals("none", TrailHandicap.fromExtras(emptyMap(), 4))
        assertEquals("none", TrailHandicap.fromExtras(mapOf("handicap4" to "3,4,3,3"), 4))
    }

    @Test
    fun bandParamsAreMonotonicLittleToBig() {
        val l = TrailRules.band(TrailBand.LITTLE)
        val m = TrailRules.band(TrailBand.MEDIUM)
        val b = TrailRules.band(TrailBand.BIG)
        assertTrue(l.colours < m.colours && m.colours < b.colours)
        assertTrue(l.length < m.length && m.length < b.length)
        assertTrue(l.stonesToLight < m.stonesToLight && m.stonesToLight < b.stonesToLight)
        assertTrue(l.builtAtStart >= m.builtAtStart && m.builtAtStart >= b.builtAtStart)
        assertEquals(listOf(17, 21, 25), listOf(l, m, b).map { it.spotCount })
        assertEquals(listOf(12, 16, 21), listOf(l, m, b).map { it.stonesToLight })
        for (band in TrailBand.entries) {
            val p = TrailRules.band(band)
            assertEquals(5, p.columns)
            // every colour of the band has its own stones and hops in the deck
            assertEquals(p.colours, TrailRules.coloursOf(band).size)
        }
    }

    @Test
    fun colourShapePairsAreUniquePerColour() {
        assertEquals(6, TrailColour.entries.map { it.shape }.toSet().size)
        assertEquals(6, TrailColour.entries.map { it.id }.toSet().size)
        for (c in TrailColour.entries) assertEquals(c, TrailColour.fromId(c.id))
        assertNull(TrailColour.fromId("teal"))
        assertEquals(TrailBand.MEDIUM, TrailBand.fromId("nonsense"))
        assertNull(TrailBand.parse("nonsense"))
    }

    @Test
    fun hopTargetFindsTheNearestLitSpotOfTheColourAndTheBurrowCountsAsEveryColour() {
        val trail = LITTLE_OPENING + listOf(TrailColour.SKY, TrailColour.SUN)
        assertEquals(3, TrailRules.hopTarget(trail, 16, 0, TrailColour.BERRY))
        assertEquals(1, TrailRules.hopTarget(trail, 16, 0, TrailColour.SUN))
        assertEquals(5, TrailRules.hopTarget(trail, 16, 2, TrailColour.SUN))
        assertEquals(-1, TrailRules.hopTarget(trail, 16, 3, TrailColour.BERRY))
        assertEquals(-1, TrailRules.hopTarget(trail, 16, 5, TrailColour.SUN))
        assertEquals("a spot behind never counts", -1, TrailRules.hopTarget(trail, 16, 4, TrailColour.LEAF))
        // lit through: the Burrow takes any colour, but a nearer lit spot of that colour still wins
        assertEquals(4, TrailRules.hopTarget(LITTLE_FULL_TRAIL, 16, 1, TrailColour.BERRY))
        assertEquals(16, TrailRules.hopTarget(LITTLE_FULL_TRAIL, 16, 13, TrailColour.BERRY))
        assertEquals("a pal at home never moves", -1, TrailRules.hopTarget(LITTLE_FULL_TRAIL, 16, 16, TrailColour.SUN))
        assertEquals(-1, TrailRules.hopTarget(trail, 16, -1, TrailColour.SUN))
    }

    @Test
    fun stepTargetNeedsALitSpotOrTheOpenBurrow() {
        val trail = LITTLE_OPENING
        assertEquals(1, TrailRules.stepTarget(trail, 16, 0))
        assertEquals(3, TrailRules.stepTarget(trail, 16, 2))
        assertEquals("the next spot is not lit", -1, TrailRules.stepTarget(trail, 16, 3))
        assertEquals(16, TrailRules.stepTarget(LITTLE_FULL_TRAIL, 16, 15))
        assertEquals(-1, TrailRules.stepTarget(LITTLE_FULL_TRAIL, 16, 16))
    }

    @Test
    fun cardCodesRoundTripAndOnlyCanonicalCodesParse() {
        for (kind in listOf(TrailCardKind.STEP, TrailCardKind.STONE)) for (c in TrailColour.entries) {
            val card = TrailCard(kind, c)
            assertEquals(card, TrailCard.fromCode(card.code))
        }
        assertEquals(TrailCard(TrailCardKind.LANTERN), TrailCard.fromCode("L"))
        assertEquals(TrailCard(TrailCardKind.FOG), TrailCard.fromCode("F"))
        for (bad in listOf("", "S", "S:", "S:teal", "S:berry:", "T", "L:berry", "F:sun", "X:berry", "s:berry", "S:BERRY", " L")) {
            assertNull("'$bad' must not parse", TrailCard.fromCode(bad))
        }
        assertNotEquals(TrailCard(TrailCardKind.STEP, TrailColour.SUN).code, TrailCard(TrailCardKind.STONE, TrailColour.SUN).code)
    }
}
