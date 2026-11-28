package com.gamesuite.games.bumprun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BumpRunDeckTest {
    private val cards = BumpRunDeck.cards()

    @Test fun deckHasFiftyFourCards() = assertEquals(54, cards.size)

    @Test fun compositionMatchesTheDocTable() {
        assertEquals(37, cards.count { it.kind == BumpCardKind.HOP })
        assertEquals(156, cards.filter { it.kind == BumpCardKind.HOP }.sumOf { it.value })
        assertEquals(4, cards.count { it.kind == BumpCardKind.SWAP })
        assertEquals(3, cards.count { it.kind == BumpCardKind.CHASE })
        assertEquals(4, cards.count { it.kind == BumpCardKind.FREE_EXIT })
        assertEquals(3, cards.count { it.kind == BumpCardKind.AGAIN })
        assertEquals(3, cards.count { it.kind == BumpCardKind.SHIELD })
    }

    @Test fun starredCardsAreHopOneHopSixAndFreeExit() {
        assertTrue(cards.filter { it.exits }.all { it.kind == BumpCardKind.FREE_EXIT || (it.kind == BumpCardKind.HOP && it.value in listOf(1, 6)) })
    }

    @Test fun fourteenCardsCanBringAPawnOut() = assertEquals(14, cards.count { it.exits })

    @Test fun cardIdsAreDenseAndUnique() {
        assertEquals((0 until 54).toList(), cards.map { it.id })
        assertNull(BumpRunDeck.byId(54))
        assertNull(BumpRunDeck.byId(-1))
    }

    @Test fun shuffleIsDeterministicForASeed() {
        assertEquals(brBoard(seed = 42).pile, brBoard(seed = 42).pile)
        assertTrue(brBoard(seed = 42).pile != brBoard(seed = 43).pile)
    }

    @Test fun shuffleIsAPermutationOfAllCards() = assertEquals((0 until 54).toSet(), brBoard(seed = 9).pile.toSet())

    @Test fun reshuffleKeepsEveryCardExactlyOnce() {
        val s = brBoard().let { it.copy(pile = emptyList(), discard = it.pile) }
        val d = bumpEngine.draw(s)
        assertTrue(d.eventReshuffled)
        assertEquals(1, d.reshuffles)
        assertEquals(emptyList<String>(), bumpEngine.validate(d))
        assertEquals((0 until 54).toSet(), (d.pile + d.discard + listOfNotNull(d.card)).toSet())
    }

    @Test fun cardInPlayIsNeverInPileOrDiscard() {
        var s = brBoard(seed = 5)
        val r = Random(3)
        repeat(300) {
            if (s.over) return
            s = if (s.phase == BumpPhase.AWAIT_DRAW) bumpEngine.draw(s) else bumpEngine.legalMoves(s).let { m ->
                bumpEngine.play(s, if (m.isEmpty()) BumpMove(-1, BumpMoveKind.PASS) else m[r.nextInt(m.size)])
            }
            s.card?.let { assertTrue(it !in s.pile && it !in s.discard) }
        }
    }
}
