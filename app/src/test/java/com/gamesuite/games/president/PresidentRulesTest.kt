package com.gamesuite.games.president

import com.gamesuite.games.cards.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresidentRulesTest {
    @Test fun ranksRunThreeLowToTwoHigh() {
        val order = Rank.values().sortedBy { PresidentRules.basePower(it) }
        assertEquals(listOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2"), order.map { it.label })
        assertEquals(12, PresidentRules.basePower(Rank.TWO))
        assertEquals(0, PresidentRules.basePower(Rank.THREE))
    }

    @Test fun revolutionTurnsTheOrderUpsideDown() {
        assertEquals(12, PresidentRules.power(Rank.THREE, true))
        assertEquals(0, PresidentRules.power(Rank.TWO, true))
        assertTrue(PresidentRules.power(Rank.FIVE, true) > PresidentRules.power(Rank.KING, true))
        assertTrue(PresidentRules.power(Rank.KING, false) > PresidentRules.power(Rank.FIVE, false))
    }

    @Test fun deckIdsAreStable() {
        assertEquals(52, PsDeck.cards.size)
        assertEquals(1, PsDeck.THREE_OF_CLUBS)
        assertEquals("3♣", PsDeck.card(PsDeck.THREE_OF_CLUBS).label)
        assertEquals(PsDeck.cards.indices.toList(), PsDeck.cards.map { it.id })
    }

    @Test fun aSingleMustBeatTheLastSingle() {
        val hand = cids("4C", "9H", "KS")
        val top = cids("8D")
        assertEquals(PresidentReject.NONE, PresidentRules.explainPlay(cids("9H"), hand, top, false, true, null))
        assertEquals(PresidentReject.TOO_LOW, PresidentRules.explainPlay(cids("4C"), hand, top, false, true, null))
    }

    @Test fun aTieIsAllowedOnlyWhenTiesAreOn() {
        val hand = cids("8H")
        val top = cids("8D")
        assertEquals(PresidentReject.NONE, PresidentRules.explainPlay(hand, hand, top, false, true, null))
        assertEquals(PresidentReject.TIE_NOT_ALLOWED, PresidentRules.explainPlay(hand, hand, top, false, false, null))
    }

    @Test fun pairsBeatPairsAndTheCountMustMatch() {
        val hand = cids("9C", "9H", "5D", "5S", "JC")
        val pairOfEights = cids("8C", "8D")
        assertEquals(PresidentReject.NONE, PresidentRules.explainPlay(cids("9C", "9H"), hand, pairOfEights, false, true, null))
        assertEquals(PresidentReject.TOO_LOW, PresidentRules.explainPlay(cids("5D", "5S"), hand, pairOfEights, false, true, null))
        assertEquals(PresidentReject.WRONG_COUNT, PresidentRules.explainPlay(cids("JC"), hand, pairOfEights, false, true, null))
        assertEquals(PresidentReject.WRONG_COUNT, PresidentRules.explainPlay(cids("9C", "9H"), hand, cids("8C"), false, true, null))
    }

    @Test fun mixedRanksAndCardsNotHeldAreRefused() {
        val hand = cids("9C", "5D")
        assertEquals(PresidentReject.MIXED_RANKS, PresidentRules.explainPlay(cids("9C", "5D"), hand, null, false, true, null))
        assertEquals(PresidentReject.NOT_IN_HAND, PresidentRules.explainPlay(cids("KS"), hand, null, false, true, null))
        assertEquals(PresidentReject.NOT_IN_HAND, PresidentRules.explainPlay(cids("9C", "9C"), hand, null, false, true, null))
        assertEquals(PresidentReject.NOTHING_SELECTED, PresidentRules.explainPlay(emptyList(), hand, null, false, true, null))
    }

    @Test fun theTwoIsHighAndRevolutionFlipsIt() {
        val hand = cids("2H", "AS")
        val top = cids("AD")
        assertEquals(PresidentReject.NONE, PresidentRules.explainPlay(cids("2H"), hand, top, false, true, null))
        // under a revolution the 2 is the weakest
        assertEquals(PresidentReject.TOO_LOW, PresidentRules.explainPlay(cids("2H"), hand, top, true, true, null))
    }

    @Test fun theFirstLeadMustContainTheThreeOfClubs() {
        val hand = cids("3C", "3D", "9H")
        assertEquals(PresidentReject.MUST_LEAD_THREE_OF_CLUBS, PresidentRules.explainPlay(cids("9H"), hand, null, false, true, PsDeck.THREE_OF_CLUBS))
        assertEquals(PresidentReject.MUST_LEAD_THREE_OF_CLUBS, PresidentRules.explainPlay(cids("3D"), hand, null, false, true, PsDeck.THREE_OF_CLUBS))
        assertEquals(PresidentReject.NONE, PresidentRules.explainPlay(cids("3C"), hand, null, false, true, PsDeck.THREE_OF_CLUBS))
        assertEquals(PresidentReject.NONE, PresidentRules.explainPlay(cids("3C", "3D"), hand, null, false, true, PsDeck.THREE_OF_CLUBS))
    }

    @Test fun candidatePlaysAreOnePerRankAndCount() {
        val hand = cids("4C", "4D", "4H", "9S", "KC")
        val lead = PresidentRules.candidatePlays(hand, null, false, true, null)
        // 4s: 1, 2, 3 cards; 9: 1; K: 1
        assertEquals(5, lead.size)
        val onPair = PresidentRules.candidatePlays(hand, cids("3C", "3D"), false, true, null)
        assertEquals(listOf(2), onPair.map { it.size }.distinct())
        assertEquals(1, onPair.size)
        assertTrue(PresidentRules.candidatePlays(hand, cids("2C"), false, true, null).isEmpty())
    }

    @Test fun candidatePlaysKeepTheThreeOfClubsOnTheFirstLead() {
        val hand = cids("3C", "3D", "3H", "9S")
        val plays = PresidentRules.candidatePlays(hand, null, false, true, PsDeck.THREE_OF_CLUBS)
        assertEquals(3, plays.size)
        assertTrue(plays.all { PsDeck.THREE_OF_CLUBS in it })
    }

    @Test fun trickKillerIsTheTopRankUnderTheOrderInForce() {
        assertTrue(PresidentRules.isTrickKiller(cids("2C"), false))
        assertFalse(PresidentRules.isTrickKiller(cids("AC"), false))
        assertTrue(PresidentRules.isTrickKiller(cids("3C"), true))
        assertFalse(PresidentRules.isTrickKiller(cids("2C"), true))
    }

    @Test fun rolesFollowTheFinishingPlace() {
        assertEquals(
            listOf(PresidentRole.PRESIDENT, PresidentRole.NEUTRAL, PresidentRole.SCUM),
            (0..2).map { PresidentRules.roleFor(it, 3) }
        )
        assertEquals(
            listOf(PresidentRole.PRESIDENT, PresidentRole.VICE_PRESIDENT, PresidentRole.VICE_SCUM, PresidentRole.SCUM),
            (0..3).map { PresidentRules.roleFor(it, 4) }
        )
        assertEquals(
            listOf(PresidentRole.PRESIDENT, PresidentRole.VICE_PRESIDENT, PresidentRole.NEUTRAL, PresidentRole.VICE_SCUM, PresidentRole.SCUM),
            (0..4).map { PresidentRules.roleFor(it, 5) }
        )
        assertEquals(
            listOf(PresidentRole.PRESIDENT, PresidentRole.VICE_PRESIDENT, PresidentRole.NEUTRAL, PresidentRole.NEUTRAL, PresidentRole.VICE_SCUM, PresidentRole.SCUM),
            (0..5).map { PresidentRules.roleFor(it, 6) }
        )
    }

    @Test fun pointsRunFromSeatsMinusOneDownToZero() {
        assertEquals(listOf(5, 4, 3, 2, 1, 0), (0..5).map { PresidentRules.pointsFor(it, 6) })
        assertEquals(listOf(2, 1, 0), (0..2).map { PresidentRules.pointsFor(it, 3) })
    }

    @Test fun exchangePairsPresidentScumAndViceViceScum() {
        assertEquals(listOf(PresidentRules.ExchangePair(2, 0, 1)), PresidentRules.exchangePairs(listOf(2, 1, 0)))
        assertEquals(
            listOf(PresidentRules.ExchangePair(3, 1, 2), PresidentRules.ExchangePair(0, 2, 1)),
            PresidentRules.exchangePairs(listOf(3, 0, 2, 1))
        )
        assertEquals(2, PresidentRules.exchangePairs(listOf(0, 1, 2, 3, 4, 5)).size)
    }

    @Test fun theScumGivesItsBestCards() {
        val hand = cids("3C", "9D", "AH", "2S", "KC")
        assertEquals(cids("2S", "AH"), PresidentRules.bestCards(hand, 2))
    }

    @Test fun sortHandIsWeakestFirst() {
        val sorted = PresidentRules.sortHand(cids("2S", "AH", "3C", "KD", "9S"))
        assertEquals(cids("3C", "9S", "KD", "AH", "2S"), sorted)
    }
}
