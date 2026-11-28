package com.gamesuite.games.territorywars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerritoryWarsCardsTest {
    private fun c(id: String, s: CardSymbol) = WarCard(id, s, null)
    private val t1 = c("a", CardSymbol.TORCH); private val t2 = c("b", CardSymbol.TORCH); private val t3 = c("c", CardSymbol.TORCH)
    private val k1 = c("d", CardSymbol.COMPASS); private val a1 = c("e", CardSymbol.ANCHOR); private val w = c("w", CardSymbol.WILD)

    @Test fun fullDeckHasThirtySixTerritoryCardsAndTwoWilds() {
        val d = TerritoryWarsCards.buildDeck(TerritoryWarsMaps.STANDARD)
        assertEquals(38, d.size)
        assertEquals(2, d.count { it.symbol == CardSymbol.WILD })
        assertEquals(36, d.count { it.territoryId != null })
        assertEquals(12, d.count { it.symbol == CardSymbol.TORCH })
        assertEquals(d.size, d.map { it.id }.toSet().size)
    }

    @Test fun blitzDeckHasEighteenTerritoryCardsAndOneWild() {
        for (m in listOf(TerritoryWarsMaps.BLITZ_WEST, TerritoryWarsMaps.BLITZ_EAST)) {
            val d = TerritoryWarsCards.buildDeck(m)
            assertEquals(19, d.size)
            assertEquals(1, d.count { it.symbol == CardSymbol.WILD })
        }
    }

    @Test fun everyRegionHoldsTwoOfEachSymbol() {
        val map = TerritoryWarsMaps.STANDARD
        val deck = TerritoryWarsCards.buildDeck(map).associateBy { it.territoryId }
        for (r in map.regions) for (sym in listOf(CardSymbol.TORCH, CardSymbol.COMPASS, CardSymbol.ANCHOR)) {
            assertEquals("${r.id} $sym", 2, r.territoryIds.count { deck.getValue(it).symbol == sym })
        }
    }

    @Test fun threeOfOneSymbolIsASet() = assertTrue(TerritoryWarsCards.isSet(listOf(t1, t2, t3)))
    @Test fun oneOfEachSymbolIsASet() = assertTrue(TerritoryWarsCards.isSet(listOf(t1, k1, a1)))
    @Test fun aWildWithAnyTwoCardsIsASet() { assertTrue(TerritoryWarsCards.isSet(listOf(t1, k1, w))); assertTrue(TerritoryWarsCards.isSet(listOf(t1, t2, w))) }
    @Test fun twoOfOneSymbolAndOneOtherIsNotASet() = assertFalse(TerritoryWarsCards.isSet(listOf(t1, t2, k1)))

    @Test fun duplicateOrWrongSizedSelectionsAreNotASet() {
        assertFalse(TerritoryWarsCards.isSet(listOf(t1, t1, t1)))
        assertFalse(TerritoryWarsCards.isSet(listOf(t1, t2)))
        assertFalse(TerritoryWarsCards.isSet(listOf(t1, t2, t3, k1)))
    }

    @Test fun anyFiveCardsContainASet() {
        val deck = TerritoryWarsCards.buildDeck(TerritoryWarsMaps.STANDARD)
        val rnd = kotlin.random.Random(1)
        repeat(300) { assertTrue(TerritoryWarsCards.hasSet(deck.shuffled(rnd).take(5))) }
    }

    @Test fun setValuesEscalateThreeFiveSevenNineTwelveFifteenEighteenThenPlusFour() {
        assertEquals(listOf(3, 5, 7, 9, 12, 15, 18, 22, 26), (0..8).map { TerritoryWarsRules.setValue(it) })
    }

    @Test fun setCounterIsSharedByAllPlayers() {
        val e = TWT.seeded(2)
        var s = TWT.started(2, 2, e = e)
        val deck = s.deck
        s = s.copy(hands = listOf(deck.take(5), deck.drop(5).take(5)), deck = deck.drop(10), phase = TurnPhase.CARDS_FORCED, currentSeat = 0)
        val set0 = e.legalActions(s).filterIsInstance<TerritoryWarsAction.TurnInCards>().first()
        val r0 = TWT.accepted(e.apply(s, set0))
        assertEquals(3, r0.events.first().amount)
        assertEquals(1, r0.state.setsTurnedIn)
        val s2 = r0.state.copy(currentSeat = 1, phase = TurnPhase.CARDS_FORCED)
        val set1 = e.legalActions(s2).filterIsInstance<TerritoryWarsAction.TurnInCards>().first()
        assertEquals(5, TWT.accepted(e.apply(s2, set1)).events.first().amount)
    }

    @Test fun emptyDeckReshufflesTheDiscardPile() {
        val e = TWT.engine(6, 6, 6, 1)
        val (a, b) = TWT.adjacentPair(TerritoryWarsMaps.STANDARD)
        var s = TWT.duel(e, a, 6, b, 1)
        val all = s.deck
        s = s.copy(deck = emptyList(), discard = all.take(4), hands = s.hands.map { emptyList() })
        val moved = TWT.act(e, TWT.act(e, s, TerritoryWarsAction.Attack(a, b, 3)), TerritoryWarsAction.MoveIn(3))
        val end = TWT.act(e, TWT.act(e, moved, TerritoryWarsAction.EndAttack), TerritoryWarsAction.SkipFortify)
        assertEquals(1, end.hands[0].size)
        assertEquals(3, end.deck.size)
        assertEquals(0, end.discard.size)
    }
}
