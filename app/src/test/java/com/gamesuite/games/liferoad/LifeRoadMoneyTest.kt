package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadMoneyTest {
    private fun base(junior: Boolean = false) = LRT.start(2, LRT.config(junior = junior))
    private fun bank(s: LifeRoadState) = s.copy(phase = Phase.AWAIT_BANK, pending = PendingState())

    @Test fun loanBorrowsFiftyAndOwesSixty() {
        val s = LRT.act(bank(base()), LifeRoadAction.TakeLoan)
        assertEquals(150, s.players[0].cash)
        assertEquals(1, s.players[0].loans)
        assertEquals(Phase.AWAIT_BANK, s.phase)
    }

    @Test fun loansAreCappedAtThree() {
        var s = bank(base())
        repeat(3) { s = LRT.act(s, LifeRoadAction.TakeLoan) }
        assertEquals(LifeRoadReject.LOAN_LIMIT, LRT.engine.explain(s, 0, LifeRoadAction.TakeLoan))
    }

    @Test fun forcedPaymentAutoBorrows() {
        val s = LRT.edit(base(), 0) { it.copy(cash = 10) }
        val r = LRT.act(LRT.card(s, "broken_pipe"), LifeRoadAction.Acknowledge)
        assertEquals(10 + 50 - 35, r.players[0].cash)
        assertEquals(1, r.players[0].loans)
    }

    @Test fun quickSaleHappensBeforeFreshStart() {
        val s = LRT.edit(base(), 0) { it.copy(cash = 0, loans = 3, homes = listOf(HomeHolding("tiny_flat", 80))) }
        val r = LRT.act(LRT.card(s, "broken_pipe"), LifeRoadAction.Acknowledge)
        assertEquals(80 * 80 / 100 - 35, r.players[0].cash)
        assertTrue(r.players[0].homes.isEmpty())
        assertEquals(0, r.players[0].freshStarts)
    }

    @Test fun freshStartWaivesDebtAndCostsFortyPoints() {
        val s = LRT.edit(base(), 0) { it.copy(cash = 0, loans = 3) }
        val r = LRT.act(LRT.card(s, "broken_pipe"), LifeRoadAction.Acknowledge)
        assertEquals(LifeRoadRules.FRESH_HELP, r.players[0].cash)
        assertEquals(0, r.players[0].loans)
        assertEquals(1, r.players[0].freshStarts)
        val card = LifeRoadScoring.card(r, 0)
        assertEquals(-40, card.lines.first { it.labelKey == "lr_sc_fresh" }.amount)
    }

    @Test fun cashNeverEndsBelowZero() {
        val (end, _) = LRT.playRandom(LRT.start(4, LRT.config(seed = 5)), kotlin.random.Random(5))
        LRT.checkInvariants(end)
    }

    @Test fun repayOnlyWhenCashCoversIt() {
        val s = LRT.edit(bank(base()), 0) { it.copy(cash = 30, loans = 1) }
        assertEquals(LifeRoadReject.NOT_ENOUGH_COINS, LRT.engine.explain(s, 0, LifeRoadAction.RepayLoan))
        val r = LRT.act(LRT.edit(s, 0) { it.copy(cash = 90) }, LifeRoadAction.RepayLoan)
        assertEquals(30, r.players[0].cash)
        assertEquals(0, r.players[0].loans)
        assertEquals(LifeRoadReject.NOTHING_TO_REPAY, LRT.engine.explain(r, 0, LifeRoadAction.RepayLoan))
    }

    @Test fun boldSquaresScaleCoinsByOnePointFive() {
        assertEquals(30, LifeRoadRules.boldCoins(20))
        assertEquals(-15, LifeRoadRules.boldCoins(-10))
        assertEquals(25, LifeRoadRules.boldCoins(15)) // 22.5 rounds to the nearest 5, halves up
        val s = LRT.at(base(), 0, "RISK.2")
        val r = LRT.act(LRT.card(s, "coin_jar"), LifeRoadAction.Acknowledge)
        assertEquals(100 + 30, r.players[0].cash)
    }

    @Test fun tuitionUsesTheSamePaymentRules() {
        val seed = LRT.seedFor(1)
        val s = LRT.edit(LRT.at(LRT.start(2, LRT.config(seed = seed)), 0, "LRN.6"), 0) { it.copy(cash = 30) }
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(1, r.players[0].loans)
        assertEquals(30 + 50 - 80, r.players[0].cash)
        assertTrue(r.players[0].degree)
    }

    @Test fun juniorHasNoLoansAndCashFloorsAtZero() {
        val s = LRT.edit(base(junior = true), 0) { it.copy(cash = 10) }
        val r = LRT.act(LRT.card(s, "broken_pipe"), LifeRoadAction.Acknowledge)
        assertEquals(0, r.players[0].cash)
        assertEquals(0, r.players[0].loans)
        assertEquals(0, r.players[0].freshStarts)
        assertEquals(LifeRoadReject.JUNIOR_NO_LOANS, LRT.engine.explain(bank(base(true)), 0, LifeRoadAction.TakeLoan))
    }
}
