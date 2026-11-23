package com.gamesuite.games.blackjack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackjackRulesTest {
    private fun t(vararg c: String) = BjRules.total(cds(*c))

    @Test fun aceCountsElevenUntilItWouldBust() {
        assertEquals(BjRules.Total(21, true), t("AS", "KD"))
        assertEquals(BjRules.Total(12, true), t("AS", "AH"))
        assertEquals(BjRules.Total(17, true), t("AS", "6D"))
        assertEquals(BjRules.Total(17, false), t("AS", "6D", "KH"))
        assertEquals(BjRules.Total(12, false), t("AS", "AH", "JD"))
        assertEquals(BjRules.Total(21, true), t("AS", "5D", "5H"))
        assertEquals(BjRules.Total(16, false), t("9S", "7D"))
        assertTrue(t("KS", "QD", "2H").bust)
        assertFalse(t("KS", "QD").bust)
        assertEquals(10, BjRules.cardValue(cd("KS")))
        assertEquals(10, BjRules.cardValue(cd("10S")))
        assertEquals(11, BjRules.cardValue(cd("AS")))
    }

    @Test fun naturalNeedsExactlyTwoCards() {
        assertTrue(BjRules.isBlackjack(cds("AS", "KD")))
        assertTrue(BjRules.isBlackjack(cds("10H", "AC")))
        assertFalse(BjRules.isBlackjack(cds("7S", "7D", "7H")))
        assertFalse(BjRules.isBlackjack(cds("9S", "AS")))
    }

    @Test fun pairsAreSameRankOnly() {
        assertTrue(BjRules.isPair(cds("8S", "8D")))
        assertFalse(BjRules.isPair(cds("KS", "QD")))
        assertFalse(BjRules.isPair(cds("8S", "8D", "8H")))
    }

    @Test fun dealerRule() {
        assertTrue(BjRules.dealerMustHit(cds("10S", "6D"), false))
        assertFalse(BjRules.dealerMustHit(cds("10S", "7D"), false))
        assertFalse(BjRules.dealerMustHit(cds("AS", "6D"), false))
        assertTrue(BjRules.dealerMustHit(cds("AS", "6D"), true))
        assertFalse(BjRules.dealerMustHit(cds("10S", "7D"), true))
        assertTrue(BjRules.dealerMustHit(cds("AS", "5D"), false))
        assertFalse(BjRules.dealerMustHit(cds("AS", "7D"), true))
    }

    @Test fun payoutMath() {
        assertEquals(150, BjRules.blackjackProfit(100, BjPayout.THREE_TO_TWO))
        assertEquals(120, BjRules.blackjackProfit(100, BjPayout.SIX_TO_FIVE))
        assertEquals(15, BjRules.blackjackProfit(10, BjPayout.THREE_TO_TWO))
        assertEquals(12, BjRules.blackjackProfit(10, BjPayout.SIX_TO_FIVE))
        assertEquals(250, BjRules.credit(BjOutcome.BLACKJACK, 100, BjPayout.THREE_TO_TWO))
        assertEquals(220, BjRules.credit(BjOutcome.BLACKJACK, 100, BjPayout.SIX_TO_FIVE))
        assertEquals(200, BjRules.credit(BjOutcome.WIN, 100, BjPayout.THREE_TO_TWO))
        assertEquals(100, BjRules.credit(BjOutcome.PUSH, 100, BjPayout.THREE_TO_TWO))
        assertEquals(50, BjRules.credit(BjOutcome.SURRENDER, 100, BjPayout.THREE_TO_TWO))
        assertEquals(0, BjRules.credit(BjOutcome.LOSE, 100, BjPayout.THREE_TO_TWO))
        assertEquals(0, BjRules.credit(BjOutcome.BUST, 100, BjPayout.THREE_TO_TWO))
        // every bet the table allows (multiples of 10) pays a whole number of chips under both ratios
        for (bet in 10..5000 step 10) for (p in BjPayout.entries) assertEquals("bet $bet ${p.id}", 0, (bet * p.num) % p.den)
    }

    @Test fun outcomeTable() {
        fun o(p: List<String>, d: List<String>, natural: Boolean = false, sur: Boolean = false) =
            BjRules.outcome(cds(*p.toTypedArray()), natural, sur, cds(*d.toTypedArray()))
        assertEquals(BjOutcome.BLACKJACK, o(listOf("AS", "KD"), listOf("10S", "9D"), natural = true))
        assertEquals(BjOutcome.PUSH, o(listOf("AS", "KD"), listOf("AH", "QD"), natural = true))
        assertEquals(BjOutcome.LOSE, o(listOf("10S", "6D", "5H"), listOf("AH", "QD")))
        assertEquals(BjOutcome.WIN, o(listOf("10S", "8D"), listOf("10H", "6D", "KC")))
        assertEquals(BjOutcome.BUST, o(listOf("10S", "8D", "5H"), listOf("10H", "6D", "KC")))
        assertEquals(BjOutcome.WIN, o(listOf("10S", "9D"), listOf("10H", "8D")))
        assertEquals(BjOutcome.LOSE, o(listOf("10S", "7D"), listOf("10H", "8D")))
        assertEquals(BjOutcome.PUSH, o(listOf("10S", "8D"), listOf("9H", "9D")))
        assertEquals(BjOutcome.SURRENDER, o(listOf("10S", "6D"), listOf("10H", "10D"), sur = true))
        // 21 made after a split is an ordinary 21, not a natural
        assertEquals(BjOutcome.WIN, o(listOf("AS", "KD"), listOf("10H", "9D"), natural = false))
    }

    @Test fun configFromExtrasFallsBackOnJunk() {
        assertEquals(BjConfig(), BjConfig.fromExtras(emptyMap()))
        val c = BjConfig.fromExtras(
            mapOf("decks" to "2", "chips" to "2500", "payout" to "6:5", "hitSoft17" to "true", "insurance" to "false", "surrender" to "false")
        )
        assertEquals(2, c.decks)
        assertEquals(2500, c.startingChips)
        assertEquals(BjPayout.SIX_TO_FIVE, c.payoutRule)
        assertTrue(c.hitSoft17)
        assertFalse(c.insurance)
        assertFalse(c.surrender)
        val bad = BjConfig.fromExtras(mapOf("decks" to "9", "chips" to "-5", "payout" to "9:1"))
        assertEquals(6, bad.decks)
        assertEquals(1000, bad.startingChips)
        assertEquals(BjPayout.THREE_TO_TWO, bad.payoutRule)
        assertTrue(bad.isValid)
        assertFalse(BjConfig(decks = 3).isValid)
    }

    // ---- basic strategy against the published chart ------------------------------------------------

    private fun best(hand: List<String>, up: String, dbl: Boolean = true, spl: Boolean = true, sur: Boolean = false, h17: Boolean = false) =
        BjStrategy.best(cds(*hand.toTypedArray()), cd(up), dbl, spl, sur, h17)

    @Test fun hardTotals() {
        assertEquals(BjAction.HIT, best(listOf("5S", "2D"), "10H"))
        assertEquals(BjAction.DOUBLE, best(listOf("5S", "4D"), "4H"))
        assertEquals(BjAction.HIT, best(listOf("5S", "4D"), "7H"))
        assertEquals(BjAction.DOUBLE, best(listOf("6S", "4D"), "9H"))
        assertEquals(BjAction.HIT, best(listOf("6S", "4D"), "10H"))
        assertEquals(BjAction.DOUBLE, best(listOf("6S", "5D"), "6H"))
        assertEquals(BjAction.HIT, best(listOf("6S", "5D"), "AH"))
        assertEquals(BjAction.DOUBLE, best(listOf("6S", "5D"), "AH", h17 = true))
        assertEquals(BjAction.HIT, best(listOf("10S", "2D"), "3H"))
        assertEquals(BjAction.STAND, best(listOf("10S", "2D"), "4H"))
        assertEquals(BjAction.STAND, best(listOf("10S", "6D"), "6H"))
        assertEquals(BjAction.HIT, best(listOf("10S", "6D"), "10H"))
        assertEquals(BjAction.STAND, best(listOf("10S", "7D"), "AH"))
        assertEquals(BjAction.HIT, best(listOf("6S", "4D"), "6H", dbl = false))
    }

    @Test fun softTotals() {
        assertEquals(BjAction.DOUBLE, best(listOf("AS", "2D"), "5H"))
        assertEquals(BjAction.HIT, best(listOf("AS", "2D"), "4H"))
        assertEquals(BjAction.DOUBLE, best(listOf("AS", "4D"), "4H"))
        assertEquals(BjAction.DOUBLE, best(listOf("AS", "6D"), "3H"))
        assertEquals(BjAction.HIT, best(listOf("AS", "6D"), "7H"))
        assertEquals(BjAction.DOUBLE, best(listOf("AS", "7D"), "6H"))
        assertEquals(BjAction.STAND, best(listOf("AS", "7D"), "6H", dbl = false))
        assertEquals(BjAction.STAND, best(listOf("AS", "7D"), "8H"))
        assertEquals(BjAction.HIT, best(listOf("AS", "7D"), "9H"))
        assertEquals(BjAction.HIT, best(listOf("AS", "7D"), "AH"))
        assertEquals(BjAction.STAND, best(listOf("AS", "8D"), "6H"))
        assertEquals(BjAction.DOUBLE, best(listOf("AS", "8D"), "6H", h17 = true))
        assertEquals(BjAction.STAND, best(listOf("AS", "9D"), "6H"))
    }

    @Test fun pairs() {
        assertEquals(BjAction.SPLIT, best(listOf("AS", "AD"), "10H"))
        assertEquals(BjAction.SPLIT, best(listOf("8S", "8D"), "10H"))
        assertEquals(BjAction.STAND, best(listOf("10S", "10D"), "6H"))
        assertEquals(BjAction.SPLIT, best(listOf("9S", "9D"), "6H"))
        assertEquals(BjAction.STAND, best(listOf("9S", "9D"), "7H"))
        assertEquals(BjAction.SPLIT, best(listOf("9S", "9D"), "9H"))
        assertEquals(BjAction.STAND, best(listOf("9S", "9D"), "10H"))
        assertEquals(BjAction.SPLIT, best(listOf("7S", "7D"), "7H"))
        assertEquals(BjAction.HIT, best(listOf("7S", "7D"), "8H"))
        assertEquals(BjAction.SPLIT, best(listOf("6S", "6D"), "2H"))
        assertEquals(BjAction.DOUBLE, best(listOf("5S", "5D"), "6H"))
        assertEquals(BjAction.SPLIT, best(listOf("4S", "4D"), "5H"))
        assertEquals(BjAction.HIT, best(listOf("4S", "4D"), "7H"))
        assertEquals(BjAction.SPLIT, best(listOf("2S", "2D"), "7H"))
        assertEquals(BjAction.HIT, best(listOf("2S", "2D"), "8H"))
        assertEquals(BjAction.HIT, best(listOf("8S", "8D"), "10H", spl = false))
        assertEquals(BjAction.STAND, best(listOf("8S", "8D"), "6H", spl = false))
    }

    @Test fun surrenderSpots() {
        assertEquals(BjAction.SURRENDER, best(listOf("10S", "6D"), "10H", sur = true))
        assertEquals(BjAction.SURRENDER, best(listOf("9S", "7D"), "AH", sur = true))
        assertEquals(BjAction.SURRENDER, best(listOf("10S", "5D"), "10H", sur = true))
        assertEquals(BjAction.HIT, best(listOf("10S", "5D"), "9H", sur = true))
        assertEquals(BjAction.SPLIT, best(listOf("8S", "8D"), "10H", sur = true))
        assertEquals(BjAction.SURRENDER, best(listOf("8S", "8D"), "AH", sur = true, h17 = true))
        assertEquals(BjAction.SURRENDER, best(listOf("10S", "5D"), "AH", sur = true, h17 = true))
        assertEquals(BjAction.HIT, best(listOf("10S", "6D"), "10H", sur = false))
    }

    @Test fun strategyOnlyReturnsActionsTheCallerAllowed() {
        val ranks = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "A")
        for (r1 in ranks) for (r2 in ranks) for (u in ranks) for (flags in 0 until 16) {
            val a = best(listOf(r1 + "S", r2 + "D"), u + "H", flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0)
            if (flags and 1 == 0) assertTrue(a != BjAction.DOUBLE)
            if (flags and 2 == 0) assertTrue(a != BjAction.SPLIT)
            if (flags and 4 == 0) assertTrue(a != BjAction.SURRENDER)
        }
    }
}
