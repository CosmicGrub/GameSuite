package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonBotTest {
    @Ignore("never stalls: not built yet")
    @Test
    fun everyBotLevelAlwaysReturnsALegalCommandInEveryPhase() {
        // TODO: never stalls
    }

    @Ignore("easy policy: not built yet")
    @Test
    fun easyBotBuysWithSmallReserveAndBidsUpToSeventyPercentOfPrice() {
        // TODO: easy policy
    }

    @Ignore("medium policy: not built yet")
    @Test
    fun mediumBotKeepsReserveAndBuildsHighestReturnEvenly() {
        // TODO: medium policy
    }

    @Ignore("hard valuation: not built yet")
    @Test
    fun hardBotValuesSetCompletionAndBlocking() {
        // TODO: hard valuation
    }

    @Ignore("timeout policy: not built yet")
    @Test
    fun botsPayFineEarlyAndStayInTheSandbarLate() {
        // TODO: timeout policy
    }

    @Ignore("debt policy: not built yet")
    @Test
    fun botLiquidationOrderLosesLeastValueFirst() {
        // TODO: debt policy
    }

    @Ignore("perf: not built yet")
    @Test
    fun botThinkBudgetStaysUnderTheLimitOnAMidRangeProfile() {
        // TODO: perf
    }

    @Ignore("determinism: not built yet")
    @Test
    fun botDecisionsAreDeterministicForAGivenStateAndSeed() {
        // TODO: determinism
    }
}
