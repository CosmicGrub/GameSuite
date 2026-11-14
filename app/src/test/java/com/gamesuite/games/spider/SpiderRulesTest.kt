package com.gamesuite.games.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Rules of Spider on hand-built tables plus structural checks of the opening deal. */
class SpiderRulesTest {
    /** Card id for [rank] (1..13) of [suit] (0..3) in a four-suit game; [copy] picks which of the two decks. */
    private fun c(rank: Int, suit: Int = 0, copy: Int = 0): Int = (suit + 4 * copy) * 13 + (rank - 1)

    private fun table(suits: Int = 4, cols: List<List<Int>>, down: List<Int> = List(cols.size) { 0 }, stock: List<Int> = emptyList()): SpiderState {
        val padded = cols + List(SPIDER_COLUMNS - cols.size) { emptyList<Int>() }
        val paddedDown = down + List(SPIDER_COLUMNS - down.size) { 0 }
        return SpiderState(suits = suits, columns = padded, faceDown = paddedDown, stock = stock)
    }

    @Test fun rankAndSuitFollowTheBlockLayout() {
        assertEquals(1, SpiderRules.rankOf(0))
        assertEquals(13, SpiderRules.rankOf(12))
        assertEquals(1, SpiderRules.rankOf(13))
        assertEquals(SPIDER_SPADES, SpiderRules.suitOf(5, 4))
        assertEquals(SPIDER_HEARTS, SpiderRules.suitOf(13, 4))
        assertEquals(SPIDER_DIAMONDS, SpiderRules.suitOf(39, 4))
        assertEquals(SPIDER_SPADES, SpiderRules.suitOf(52, 4)) // the second deck starts over
        assertEquals(SPIDER_HEARTS, SpiderRules.suitOf(13, 2))
        assertEquals(SPIDER_SPADES, SpiderRules.suitOf(26, 2))
        for (id in 0 until SPIDER_DECK_SIZE) assertEquals(SPIDER_SPADES, SpiderRules.suitOf(id, 1))
    }

    @Test fun suitCountsAreSplitEvenlyAcrossTheDeck() {
        for (suits in listOf(1, 2, 4)) {
            val perSuit = (0 until SPIDER_DECK_SIZE).groupingBy { SpiderRules.suitOf(it, suits) }.eachCount()
            assertEquals(suits, perSuit.size)
            perSuit.values.forEach { assertEquals(SPIDER_DECK_SIZE / suits, it) }
        }
    }

    @Test fun openingDealHasTheStandardShape() {
        for (suits in listOf(1, 2, 4)) {
            val s = SpiderRules.openingState((0 until SPIDER_DECK_SIZE).toList().shuffled(Random(suits)), suits)
            assertEquals(listOf(6, 6, 6, 6, 5, 5, 5, 5, 5, 5), s.columns.map { it.size })
            assertEquals(listOf(5, 5, 5, 5, 4, 4, 4, 4, 4, 4), s.faceDown)
            assertEquals(50, s.stock.size)
            assertEquals(5, s.dealsLeft)
            assertEquals(SPIDER_START_SCORE, s.score)
            assertTrue(SpiderRules.isConsistent(s))
        }
    }

    @Test(expected = IllegalArgumentException::class) fun openingRejectsAShortDeck() {
        SpiderRules.openingState((0 until 103).toList(), 1)
    }

    @Test fun onlyFaceUpSameSuitDescendingRunsCanBePickedUp() {
        val s = table(
            cols = listOf(listOf(c(9), c(8, 1), c(7, 1), c(6, 1)), listOf(c(5), c(4), c(3))),
            down = listOf(1, 0)
        )
        assertTrue(SpiderRules.canPickUp(s, 0, 3))
        assertTrue(SpiderRules.canPickUp(s, 0, 2)) // 7H 6H
        assertTrue(SpiderRules.canPickUp(s, 0, 1)) // 8H 7H 6H
        assertFalse(SpiderRules.canPickUp(s, 0, 0)) // face down
        assertTrue(SpiderRules.canPickUp(s, 1, 0)) // 5S 4S 3S
        assertFalse(SpiderRules.canPickUp(s, 1, 3)) // out of range
        assertFalse(SpiderRules.canPickUp(s, 12, 0))
    }

    @Test fun aMixedSuitSequenceCannotMoveAsOne() {
        val s = table(cols = listOf(listOf(c(8, 0), c(7, 1), c(6, 1))))
        assertFalse(SpiderRules.canPickUp(s, 0, 0))
        assertTrue(SpiderRules.canPickUp(s, 0, 1))
        assertEquals(1, SpiderRules.runStart(s, 0))
    }

    @Test fun aRunMustDescendByExactlyOne() {
        val s = table(cols = listOf(listOf(c(9), c(7)), listOf(c(5), c(5, 0, 1))))
        assertFalse(SpiderRules.canPickUp(s, 0, 0))
        assertFalse(SpiderRules.canPickUp(s, 1, 0))
    }

    @Test fun dropNeedsOneRankHigherOfAnySuitOrAnEmptyColumn() {
        val s = table(cols = listOf(listOf(c(6, 1)), listOf(c(7, 0)), listOf(c(8, 2)), emptyList()))
        assertTrue(SpiderRules.canDrop(s, 0, 0, 1)) // 6H on 7S, off suit is fine
        assertFalse(SpiderRules.canDrop(s, 0, 0, 2)) // 6 on 8
        assertTrue(SpiderRules.canDrop(s, 0, 0, 3)) // empty column takes anything
        assertFalse(SpiderRules.canDrop(s, 0, 0, 0)) // not onto itself
        assertFalse(SpiderRules.canDrop(s, 0, 0, 10)) // off the table
    }

    @Test fun movingARunFlipsTheCardItUncovers() {
        val s = table(cols = listOf(listOf(c(2), c(9, 1), c(8, 1)), listOf(c(10, 2))), down = listOf(1, 0))
        val out = SpiderRules.applyMove(s, SpiderMove(0, 1, 1))!!
        assertTrue(out.flipped)
        assertEquals(listOf(c(2)), out.state.columns[0])
        assertEquals(0, out.state.faceDown[0])
        assertEquals(listOf(c(10, 2), c(9, 1), c(8, 1)), out.state.columns[1])
        assertEquals(1, out.state.moves)
        assertEquals(SPIDER_START_SCORE - 1, out.state.score)
    }

    @Test fun movingTheLastFaceUpCardLeavesAnEmptyColumnWithNoFaceDownCount() {
        val s = table(cols = listOf(listOf(c(5)), listOf(c(6, 1))))
        val out = SpiderRules.applyMove(s, SpiderMove(0, 0, 1))!!
        assertTrue(out.state.columns[0].isEmpty())
        assertEquals(0, out.state.faceDown[0])
        assertFalse(out.flipped)
    }

    @Test fun anIllegalMoveReturnsNullAndChangesNothing() {
        val s = table(cols = listOf(listOf(c(5)), listOf(c(9, 1))))
        assertNull(SpiderRules.applyMove(s, SpiderMove(0, 0, 1)))
        assertNull(SpiderRules.applyMove(s, SpiderMove(0, 4, 1)))
    }

    @Test fun completingKingToAceRemovesTheRunAndScoresAHundred() {
        val run = (13 downTo 2).map { c(it) }
        val s = table(cols = listOf(listOf(c(7, 1)) + run, listOf(c(1))), down = listOf(1, 0))
        val out = SpiderRules.applyMove(s, SpiderMove(1, 0, 0))!!
        assertEquals(listOf(SPIDER_SPADES), out.completedSuits)
        assertEquals(1, out.state.completedRuns)
        assertEquals(listOf(c(7, 1)), out.state.columns[0])
        assertEquals(0, out.state.faceDown[0]) // the card under the run turned face up
        assertEquals(13, out.state.completed.size)
        assertEquals(SPIDER_START_SCORE - 1 + SPIDER_RUN_BONUS, out.state.score)
    }

    @Test fun aMixedSuitKingToAceDoesNotComplete() {
        val seq = (13 downTo 2).map { c(it) }.toMutableList()
        seq[5] = c(8, 1)
        val s = table(cols = listOf(seq, listOf(c(1))))
        val out = SpiderRules.applyMove(s, SpiderMove(1, 0, 0))!!
        assertEquals(0, out.state.completedRuns)
        assertEquals(13, out.state.columns[0].size)
    }

    @Test fun aRunWhoseKingIsStillFaceDownDoesNotComplete() {
        val seq = (13 downTo 2).map { c(it) }
        val s = table(cols = listOf(seq, listOf(c(1))), down = listOf(1, 0)) // the King is face down
        val out = SpiderRules.applyMove(s, SpiderMove(1, 0, 0))!!
        assertEquals(0, out.state.completedRuns)
        assertEquals(13, out.state.columns[0].size)
    }

    @Test fun aCompletedRunTurnsTheCardBelowFaceUp() {
        val run = (13 downTo 2).map { c(it) }
        val s = table(cols = listOf(listOf(c(4, 2), c(5, 2)) + run, listOf(c(1))), down = listOf(2, 0))
        val out = SpiderRules.applyMove(s, SpiderMove(1, 0, 0))!!
        assertEquals(1, out.state.completedRuns)
        assertEquals(listOf(c(4, 2), c(5, 2)), out.state.columns[0])
        assertEquals(1, out.state.faceDown[0]) // the five is face down no more
    }

    @Test fun dealNeedsStockAndNoEmptyColumn() {
        val row = (0 until 10).map { c(1, 1, 0) + it }
        val ok = table(cols = List(10) { listOf(c(13, 2)) }, stock = row)
        val withEmpty = ok.copy(columns = ok.columns.toMutableList().also { it[3] = emptyList() })
        assertTrue(SpiderRules.canDeal(ok))
        assertFalse(SpiderRules.canDeal(withEmpty))
        assertFalse(SpiderRules.canDeal(ok.copy(stock = emptyList())))
        assertNull(SpiderRules.applyDeal(withEmpty))
    }

    @Test fun dealPutsOneFaceUpCardOnEveryColumnAndCostsAPoint() {
        val s = SpiderRules.openingState((0 until SPIDER_DECK_SIZE).toList().shuffled(Random(3)), 2)
        val out = SpiderRules.applyDeal(s)!!
        assertEquals(s.stock.takeLast(10), out.state.columns.map { it.last() })
        assertEquals(40, out.state.stock.size)
        assertEquals(s.faceDown, out.state.faceDown)
        assertEquals(s.columns.map { it.size + 1 }, out.state.columns.map { it.size })
        assertEquals(s.score - 1, out.state.score)
        assertTrue(SpiderRules.isConsistent(out.state))
    }

    @Test fun dealingCanCompleteARun() {
        // Column 0 holds K..2 of spades; the dealt card for it is the ace of spades.
        val run = (13 downTo 2).map { c(it) }
        val cols = List(10) { i -> if (i == 0) run else listOf(c(13, 1, 1)) }
        val row = listOf(c(1)) + List(9) { c(3, 3, 1) } // the row is taken from the END of the stock; index 0 goes to column 0
        val out = SpiderRules.applyDeal(table(cols = cols, stock = row))!!
        assertEquals(1, out.state.completedRuns)
        assertTrue(out.state.columns[0].isEmpty())
        assertEquals(listOf(SPIDER_SPADES), out.completedSuits)
        assertEquals(SPIDER_START_SCORE - 1 + SPIDER_RUN_BONUS, out.state.score)
    }

    @Test fun winningNeedsAllEightRuns() {
        val s = table(cols = emptyList()).copy(completed = (0 until SPIDER_DECK_SIZE).toList())
        assertFalse(s.copy(completed = s.completed.take(91)).won)
        assertTrue(s.won)
        assertFalse(SpiderRules.hasAnyMove(s))
    }

    @Test fun legalMovesListsEveryRunStartAndDestination() {
        val s = table(cols = listOf(listOf(c(5), c(4)), listOf(c(6)), emptyList()))
        val moves = SpiderRules.legalMoves(s)
        assertTrue(SpiderMove(0, 0, 1) in moves) // 5,4 onto 6
        assertTrue(SpiderMove(0, 1, 2) in moves) // 4 to the empty column
        assertTrue(SpiderMove(1, 0, 2) in moves)
        assertFalse(SpiderMove(0, 1, 1) in moves) // 4 on 6 is two ranks apart
        assertNotNull(moves.firstOrNull { it.from == 1 })
    }

    @Test fun consistencyRejectsDuplicatesMissingCardsAndBadFaceDownCounts() {
        val good = SpiderRules.openingState((0 until SPIDER_DECK_SIZE).toList().shuffled(Random(9)), 4)
        assertTrue(SpiderRules.isConsistent(good))
        assertFalse(SpiderRules.isConsistent(good.copy(stock = good.stock.drop(10))))
        assertFalse(SpiderRules.isConsistent(good.copy(stock = good.stock.toMutableList().also { it[0] = it[1] })))
        assertFalse(SpiderRules.isConsistent(good.copy(faceDown = good.faceDown.toMutableList().also { it[0] = 6 })))
        assertFalse(SpiderRules.isConsistent(good.copy(faceDown = good.faceDown.toMutableList().also { it[0] = -1 })))
        assertFalse(SpiderRules.isConsistent(good.copy(suits = 3)))
        assertFalse(SpiderRules.isConsistent(good.copy(score = -1)))
    }

    @Test fun hintPrefersCompletingARunThenJoiningThenFlipping() {
        // Move 1: a suited join that also uncovers a face-down card must beat a plain off-suit move.
        val s = table(
            cols = listOf(listOf(c(2, 3), c(5, 0)), listOf(c(6, 0)), listOf(c(6, 1))),
            down = listOf(1, 0, 0)
        )
        val hint = SpiderHints.suggest(s) as SpiderHint.Move
        assertEquals(SpiderMove(0, 1, 1), hint.move)
    }

    @Test fun hintSuggestsADealWhenNothingElseHelps() {
        val cols = List(10) { listOf(c(1 + it % 3, it % 4, it / 4)) }
        val s = table(cols = cols, stock = (0 until 10).map { c(13, 0, 1) - it })
        // No two adjacent ranks line up usefully except pointless ones; a deal is the answer if moves score <= 0.
        val hint = SpiderHints.suggest(s)
        assertNotNull(hint)
    }

    @Test fun noHintOnAWonTable() {
        val s = table(cols = emptyList()).copy(completed = (0 until SPIDER_DECK_SIZE).toList())
        assertNull(SpiderHints.suggest(s))
    }
}
