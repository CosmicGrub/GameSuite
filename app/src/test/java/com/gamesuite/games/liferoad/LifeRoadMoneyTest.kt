package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadMoneyTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadMoneyTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun loanBorrowsFiftyAndOwesSixty() {
        // One loan block adds 50 cash and 60 owed
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun loansAreCappedAtThree() {
        // A fourth loan is rejected with LOAN_LIMIT
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun forcedPaymentAutoBorrows() {
        // A bill above cash borrows the fewest blocks
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun quickSaleHappensBeforeFreshStart() {
        // Homes sell at 80% of price before a fresh start
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun freshStartWaivesDebtAndCostsFortyPoints() {
        // Loans cleared, 20 coins helping hand, 40 point penalty
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun cashNeverEndsBelowZero() {
        // After every action cash is at least 0
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun repayOnlyWhenCashCoversIt() {
        // Repay needs 60 cash
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun boldSquaresScaleCoinsByOnePointFive() {
        // Gains and losses on bold nodes round to the nearest 5, halves up
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun tuitionUsesTheSamePaymentRules() {
        // Graduation with 30 coins borrows instead of failing
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun juniorHasNoLoansAndCashFloorsAtZero() {
        // Junior pays what it can and never borrows
        // TODO
    }
}
