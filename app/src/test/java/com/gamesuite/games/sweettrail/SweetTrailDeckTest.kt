package com.gamesuite.games.sweettrail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 66-card deck and its shuffles (docs/design/boardgames/sweet-trail.md, 2.3 and 3.3). */
class SweetTrailDeckTest {
    private fun cards(length: TrailLength) = SweetTrailDeck.cards(length)

    @Test fun standardDeckHasSixtySixCards() {
        for (length in TrailLength.values()) assertEquals(66, cards(length).size)
        assertEquals(SweetTrailDeck.SIZE, 66)
    }

    @Test fun singlesAreEightPerColourInStandard() {
        for (length in listOf(TrailLength.STANDARD, TrailLength.SHORT)) {
            for (c in TrailColour.values()) {
                assertEquals(c.name, 8, cards(length).count { it.kind == TrailCardKind.SINGLE && it.colour == c && it.id < 48 })
            }
            assertEquals(48, cards(length).count { it.kind == TrailCardKind.SINGLE })
        }
    }

    @Test fun doublesAreTwoPerColourInStandard() {
        for (c in TrailColour.values()) {
            assertEquals(c.name, 2, cards(TrailLength.STANDARD).count { it.kind == TrailCardKind.DOUBLE && it.colour == c })
        }
        assertEquals(12, cards(TrailLength.STANDARD).count { it.kind == TrailCardKind.DOUBLE })
        assertEquals(12, cards(TrailLength.SHORT).count { it.kind == TrailCardKind.DOUBLE })
    }

    @Test fun longDeckHasTenSinglesPerColourAndNoDoubles() {
        val long = cards(TrailLength.LONG)
        assertEquals(0, long.count { it.kind == TrailCardKind.DOUBLE })
        for (c in TrailColour.values()) assertEquals(c.name, 10, long.count { it.kind == TrailCardKind.SINGLE && it.colour == c })
        assertEquals(60, long.count { it.kind == TrailCardKind.SINGLE })
    }

    @Test fun landmarkCardsAreSixAndMatchTheLandmarks() {
        for (length in TrailLength.values()) {
            val lm = cards(length).filter { it.kind == TrailCardKind.LANDMARK }
            assertEquals(6, lm.size)
            assertEquals(TrailLandmark.values().toList(), lm.map { it.landmark })
            assertEquals((60..65).toList(), lm.map { it.id })
            assertTrue(lm.all { it.colour == null })
        }
    }

    @Test fun cardIdsAreDenseAndUnique() {
        for (length in TrailLength.values()) {
            assertEquals((0 until 66).toList(), cards(length).map { it.id })
            assertEquals(cards(length)[17], SweetTrailDeck.byId(17, length))
        }
        assertNull(SweetTrailDeck.byId(66, TrailLength.STANDARD))
        assertNull(SweetTrailDeck.byId(-1, TrailLength.STANDARD))
    }

    @Test fun shuffleIsDeterministicForASeed() {
        assertEquals(stBoard(seed = 99).pile, stBoard(seed = 99).pile)
        assertEquals(stBoard(seed = 0).pile, stBoard(seed = 0).pile)
    }

    @Test fun shuffleIsAPermutationOfAllCards() {
        for (seed in 0L until 50L) {
            val pile = stBoard(seed = seed).pile
            assertEquals((0 until 66).toList(), pile.sorted())
        }
    }

    @Test fun differentSeedsGiveDifferentOrders() {
        val orders = (0L until 40L).map { stBoard(seed = it).pile }.toSet()
        assertTrue("only ${orders.size} distinct orders in 40 seeds", orders.size >= 39)
        assertNotEquals(stBoard(seed = 1).pile, stBoard(seed = 2).pile)
    }

    @Test fun reshuffleKeepsEveryCardExactlyOnce() {
        var s = stBoard(seed = 5)
        var reshuffles = 0
        var turns = 0
        while (!s.over && reshuffles < 2 && turns < 5_000) {
            // a board that never ends: park the pieces back so the deck runs dry again and again
            val parked = s.copy(positions = s.positions.map { minOf(it, 5) })
            s = if (parked.pending) stEngine.decide(parked, false) else stEngine.draw(parked)
            if (s.eventReshuffled) reshuffles++
            assertEquals((0 until 66).toList(), (s.pile + s.discard + listOfNotNull(s.card)).sorted())
            turns++
        }
        assertTrue("never reshuffled", reshuffles >= 1)
    }
}
