package com.gamesuite.games.holdem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HandEvaluatorTest {
    private fun ev(s: String) = HandEvaluator.evaluate(T.cards(s))
    private fun cat(s: String) = HandEvaluator.category(ev(s))

    // ---- categories ----------------------------------------------------------------------------

    @Test fun everyCategoryIsRecognisedOnSevenCards() {
        assertEquals(HandCategory.HIGH_CARD, cat("Ah Kd 9c 7s 4h 3d 2c"))
        assertEquals(HandCategory.PAIR, cat("Ah Ad 9c 7s 4h 3d 2c"))
        assertEquals(HandCategory.TWO_PAIR, cat("Ah Ad 9c 9s 4h 3d 2c"))
        assertEquals(HandCategory.TRIPS, cat("Ah Ad Ac 9s 4h 3d 2c"))
        assertEquals(HandCategory.STRAIGHT, cat("5h 6d 7c 8s 9h Kd 2c"))
        assertEquals(HandCategory.FLUSH, cat("Ah 9h 7h 4h 2h Kd Qc"))
        assertEquals(HandCategory.FULL_HOUSE, cat("Ah Ad Ac 9s 9h 3d 2c"))
        assertEquals(HandCategory.QUADS, cat("Ah Ad Ac As 9h 3d 2c"))
        assertEquals(HandCategory.STRAIGHT_FLUSH, cat("5h 6h 7h 8h 9h Kd 2c"))
    }

    @Test fun categoriesOrderCorrectlyAgainstEachOther() {
        val ladder = listOf(
            "Ah Kd 9c 7s 4h 3d 2c", "Ah Ad 9c 7s 4h 3d 2c", "Ah Ad 9c 9s 4h 3d 2c", "Ah Ad Ac 9s 4h 3d 2c",
            "5h 6d 7c 8s 9h Kd 2c", "Ah 9h 7h 4h 2h Kd Qc", "Ah Ad Ac 9s 9h 3d 2c", "Ah Ad Ac As 9h 3d 2c", "5h 6h 7h 8h 9h Kd 2c"
        ).map { ev(it) }
        for (i in 0 until ladder.size - 1) assertTrue("rung $i", ladder[i] < ladder[i + 1])
    }

    // ---- straights -----------------------------------------------------------------------------

    @Test fun theWheelIsAFiveHighStraightAndLosesToASixHigh() {
        val wheel = ev("Ah 2d 3c 4s 5h Kd Qc")
        assertEquals(HandCategory.STRAIGHT, HandEvaluator.category(wheel))
        assertEquals(5, HandEvaluator.rankAt(wheel, 0))
        assertTrue(wheel < ev("2h 3d 4c 5s 6h Kd Qc"))
    }

    @Test fun broadwayIsTheHighestStraightAndAceIsNotLowAfterAKing() {
        val broadway = ev("Th Jd Qc Ks Ah 2d 3c")
        assertEquals(14, HandEvaluator.rankAt(broadway, 0))
        // Q-K-A-2-3 is not a straight (no wrap-around)
        assertEquals(HandCategory.HIGH_CARD, cat("Qh Kd Ac 2s 3h 7d 9c"))
    }

    @Test fun aStraightUsesTheHighestFiveOfALongerRun() {
        val s = ev("4h 5d 6c 7s 8h 9d 2c")
        assertEquals(9, HandEvaluator.rankAt(s, 0))
    }

    @Test fun straightFlushBeatsAHigherStraightOfAnotherSuitAndRoyalIsDetected() {
        // six-high straight flush in hearts with an off-suit seven making a longer straight
        val sf = ev("2h 3h 4h 5h 6h 7d 8c")
        assertEquals(HandCategory.STRAIGHT_FLUSH, HandEvaluator.category(sf))
        assertEquals(6, HandEvaluator.rankAt(sf, 0))
        val royal = ev("Ts Js Qs Ks As 2d 3c")
        assertTrue(HandEvaluator.isRoyal(royal))
        assertTrue(royal > sf)
        assertTrue(!HandEvaluator.isRoyal(sf))
    }

    @Test fun aFlushWithTheStraightInTwoSuitsIsNotAStraightFlush() {
        // 5-6-7-8-9 exists, but the hearts are 2-5-6-7-9 only
        val h = ev("5h 6h 7h 8c 9h 2h Kd")
        assertEquals(HandCategory.FLUSH, HandEvaluator.category(h))
    }

    // ---- kickers and ties ----------------------------------------------------------------------

    @Test fun pairKickersDecideThenTie() {
        val aceKicker = ev("9h 9d Ac 7s 4h 3d 2c")
        val kingKicker = ev("9s 9c Kc 7d 4h 3d 2h")
        assertTrue(aceKicker > kingKicker)
        // the fifth card never matters in a pair hand: three kickers play
        assertEquals(ev("9h 9d Ac Ks 7h 3d 2c"), ev("9s 9c Ad Kc 7d 4h 2h"))
        assertTrue(ev("9h 9d Ac Ks 7h 3d 2c") > ev("9s 9c Ad Kc 6d 5h 4h"))
    }

    @Test fun twoPairUsesTheBestTwoOfThreePairsAndTheBestKicker() {
        val v = ev("Ah Ad Kc Ks 9h 9d 2c")
        assertEquals(HandCategory.TWO_PAIR, HandEvaluator.category(v))
        assertEquals(14, HandEvaluator.rankAt(v, 0))
        assertEquals(13, HandEvaluator.rankAt(v, 1))
        // the kicker is the nine (from the unused third pair), not the two
        assertEquals(9, HandEvaluator.rankAt(v, 2))
        assertEquals(ev("Ah Ad Kc Ks 9h 3d 2c"), v)
    }

    @Test fun twoTripsMakeAFullHouseWithTheLowerTripsAsThePair() {
        val v = ev("Kh Kd Kc 7s 7h 7d 2c")
        assertEquals(HandCategory.FULL_HOUSE, HandEvaluator.category(v))
        assertEquals(13, HandEvaluator.rankAt(v, 0))
        assertEquals(7, HandEvaluator.rankAt(v, 1))
    }

    @Test fun fullHouseUsesTheHigherOfTwoAvailablePairs() {
        val v = ev("Qh Qd Qc 9s 9h 4d 4c")
        assertEquals(12, HandEvaluator.rankAt(v, 0))
        assertEquals(9, HandEvaluator.rankAt(v, 1))
    }

    @Test fun quadsKickerIsTheBestRemainingCard() {
        val a = ev("8h 8d 8c 8s Ah 2d 3c")
        val b = ev("8h 8d 8c 8s Kh Qd Jc")
        assertTrue(a > b)
        assertEquals(14, HandEvaluator.rankAt(a, 1))
    }

    @Test fun flushComparesCardByCardFromTheTop() {
        val a = ev("Ah Kh 9h 5h 3h 2d 2c")
        val b = ev("Ah Kh 9h 5h 2h 3d 3c")
        assertTrue(a > b)
        // a sixth heart never plays
        assertEquals(ev("Ah Kh 9h 5h 3h"), ev("Ah Kh 9h 5h 3h 2h 4d"))
    }

    @Test fun theBoardPlayingIsAnExactTie() {
        val board = "Ah Kh Qh Jh Th"
        assertEquals(ev("$board 2c 3d"), ev("$board 4c 5d"))
    }

    @Test fun fiveCardAndSixCardInputsWork() {
        assertEquals(HandCategory.PAIR, HandEvaluator.category(HandEvaluator.evaluate(T.cards("Ah Ad 9c 7s 4h"))))
        assertEquals(HandCategory.TRIPS, HandEvaluator.category(HandEvaluator.evaluate(T.cards("Ah Ad Ac 7s 4h 2d"))))
    }

    // ---- best five -----------------------------------------------------------------------------

    @Test fun bestFiveReproducesTheScoreAndPicksTheRightCards() {
        val cards = T.cards("Ah Ad Kc Ks 9h 9d 2c")
        val five = HandEvaluator.bestFive(cards)
        assertEquals(5, five.size)
        assertEquals(HandEvaluator.evaluate(cards), HandEvaluator.evaluate(five))
        assertTrue(T.c("2c") !in five)
        // the wheel reads 5-4-3-2-A
        val wheel = HandEvaluator.bestFive(T.cards("Ah 2d 3c 4s 5h Kd Qc"))
        assertEquals(listOf(5, 4, 3, 2, 14), wheel.map { HoldemCards.rank(it) })
    }

    // ---- independent reference (fuzz) -----------------------------------------------------------

    /** A deliberately plain evaluator for five cards, sharing no code or encoding trick with [HandEvaluator]. */
    private fun referenceFive(five: List<Int>): List<Int> {
        val ranks = five.map { HoldemCards.rank(it) }.sortedDescending()
        val flush = five.map { HoldemCards.suit(it) }.toSet().size == 1
        val distinct = ranks.toSet().size
        val groups = ranks.groupBy { it }.map { it.value.size to it.key }.sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
        var straightHigh = 0
        if (distinct == 5) {
            if (ranks[0] - ranks[4] == 4) straightHigh = ranks[0]
            else if (ranks == listOf(14, 5, 4, 3, 2)) straightHigh = 5
        }
        val tie = groups.flatMap { g -> List(1) { g.second } }
        return when {
            straightHigh > 0 && flush -> listOf(8, straightHigh)
            groups[0].first == 4 -> listOf(7) + tie
            groups[0].first == 3 && groups[1].first == 2 -> listOf(6) + tie
            flush -> listOf(5) + ranks
            straightHigh > 0 -> listOf(4, straightHigh)
            groups[0].first == 3 -> listOf(3) + tie
            groups[0].first == 2 && groups[1].first == 2 -> listOf(2) + tie
            groups[0].first == 2 -> listOf(1) + tie
            else -> listOf(0) + ranks
        }
    }

    private fun compareLists(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return a.size.compareTo(b.size)
    }

    private fun referenceBest(seven: List<Int>): List<Int> {
        var best: List<Int>? = null
        for (i in 0 until 7) for (j in i + 1 until 7) {
            val five = seven.filterIndexed { idx, _ -> idx != i && idx != j }
            val r = referenceFive(five)
            if (best == null || compareLists(r, best) > 0) best = r
        }
        return best!!
    }

    @Test fun fuzzAgainstTheIndependentReferenceOnRandomSevenCardHands() {
        val rnd = Random(20261031)
        val deck = (0 until 52).toMutableList()
        repeat(60_000) {
            deck.shuffle(rnd)
            val seven = deck.take(7)
            val mine = HandEvaluator.evaluate(seven.toIntArray())
            val ref = referenceBest(seven)
            assertEquals(ref[0], HandEvaluator.category(mine).strength)
            // the packed tie-break ranks match the reference's, rank for rank
            for (i in 1 until ref.size) assertEquals("hand ${seven.joinToString()} rank $i", ref[i], HandEvaluator.rankAt(mine, i - 1))
        }
    }

    @Test fun fuzzOrderingAgreesWithTheReferenceOnPairsOfHands() {
        val rnd = Random(77)
        val deck = (0 until 52).toMutableList()
        repeat(40_000) {
            deck.shuffle(rnd)
            val a = deck.subList(0, 2) + deck.subList(4, 9)
            val b = deck.subList(2, 4) + deck.subList(4, 9)
            val mine = HandEvaluator.evaluate(a.toIntArray()).compareTo(HandEvaluator.evaluate(b.toIntArray()))
            val ref = compareLists(referenceBest(a), referenceBest(b))
            assertEquals(Integer.signum(ref), Integer.signum(mine))
        }
    }

    @Test fun bestFiveAlwaysMatchesTheSevenCardScoreAndUsesOnlyTheGivenCards() {
        val rnd = Random(5)
        val deck = (0 until 52).toMutableList()
        repeat(3_000) {
            deck.shuffle(rnd)
            val seven = deck.take(7)
            val five = HandEvaluator.bestFive(seven)
            assertEquals(5, five.size)
            assertTrue(seven.containsAll(five))
            assertEquals(HandEvaluator.evaluate(seven.toIntArray()), HandEvaluator.evaluate(five.toIntArray()))
        }
    }

    @Test fun categoryFrequenciesOfSevenCardHandsMatchThePublishedOdds() {
        // 133,784,560 seven-card hands: the well-known counts, checked by sampling (within 8%).
        val rnd = Random(123)
        val deck = (0 until 52).toMutableList()
        val n = 300_000
        val counts = IntArray(9)
        repeat(n) {
            deck.shuffle(rnd)
            counts[HandEvaluator.category(HandEvaluator.evaluate(deck.take(7).toIntArray())).strength]++
        }
        val expected = doubleArrayOf(0.1741, 0.4380, 0.2350, 0.0483, 0.0462, 0.0303, 0.0260, 0.00168, 0.000311)
        for (c in 0..6) {
            val got = counts[c].toDouble() / n
            assertTrue("category $c: got $got expected ${expected[c]}", Math.abs(got - expected[c]) / expected[c] < 0.08)
        }
        assertTrue(counts[7] in 1..(n / 200))
    }
}
