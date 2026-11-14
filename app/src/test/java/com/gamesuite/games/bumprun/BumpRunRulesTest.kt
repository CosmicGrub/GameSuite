package com.gamesuite.games.bumprun

import org.junit.Ignore
import org.junit.Test

/**
 * Bump and Run: scaffold stubs. Every test of the test plan in docs/design/boardgames/bump-run.md is listed here by name,
 * ignored until its phase lands (Phase 0). Un-ignore a test only together with the code it proves.
 */
class BumpRunRulesTest {
    @Ignore("scaffold: Phase 0")
    @Test fun hopMovesOnePawnTheCardValue() {}

    @Ignore("scaffold: Phase 0")
    @Test fun hopOverPawnsIsAllowed() {}

    @Ignore("scaffold: Phase 0")
    @Test fun hopLandingOnAnOwnPawnIsIllegal() {}

    @Ignore("scaffold: Phase 0")
    @Test fun hopLandingOnARivalBumpsItToTheNest() {}

    @Ignore("scaffold: Phase 0")
    @Test fun hopLandingOnAShieldedRivalIsIllegal() {}

    @Ignore("scaffold: Phase 0")
    @Test fun hopPastHomeIsIllegalExactCountRequired() {}

    @Ignore("scaffold: Phase 0")
    @Test fun hopOntoHomeExactlyIsLegalForManyPawns() {}

    @Ignore("scaffold: Phase 0")
    @Test fun lanePawnsAreNeverBumped() {}

    @Ignore("scaffold: Phase 0")
    @Test fun twoOwnPawnsCannotShareALaneSquare() {}

    @Ignore("scaffold: Phase 0")
    @Test fun starCardsMayBringAPawnOutOfTheNest() {}

    @Ignore("scaffold: Phase 0")
    @Test fun exitBumpsARivalStandingOnTheExitSquare() {}

    @Ignore("scaffold: Phase 0")
    @Test fun exitIsBlockedByAnOwnPawnOnTheExitSquare() {}

    @Ignore("scaffold: Phase 0")
    @Test fun exitIsBlockedByAShieldedRivalOnTheExitSquare() {}

    @Ignore("scaffold: Phase 0")
    @Test fun freeExitWithNoPawnInTheNestPlaysAsHopThree() {}

    @Ignore("scaffold: Phase 0")
    @Test fun freeExitWithABlockedExitPlaysAsHopThree() {}

    @Ignore("scaffold: Phase 0")
    @Test fun swapExchangesSquaresWithARivalOnTheRing() {}

    @Ignore("scaffold: Phase 0")
    @Test fun swapRecomputesProgressForBothPawns() {}

    @Ignore("scaffold: Phase 0")
    @Test fun swapNeverTargetsNestLaneHomeOrShieldedPawns() {}

    @Ignore("scaffold: Phase 0")
    @Test fun swapWithNoLegalTargetIsNoMove() {}

    @Ignore("scaffold: Phase 0")
    @Test fun chaseBumpsTheNearestRivalAheadWithinTwelve() {}

    @Ignore("scaffold: Phase 0")
    @Test fun chasePassesOverOwnPawns() {}

    @Ignore("scaffold: Phase 0")
    @Test fun chaseBlockedByShieldedNearestRivalIsNotLegalForThatPawn() {}

    @Ignore("scaffold: Phase 0")
    @Test fun chaseWithNoRivalAheadPlaysAsHopFour() {}

    @Ignore("scaffold: Phase 0")
    @Test fun chaseNeverLooksIntoTheLane() {}

    @Ignore("scaffold: Phase 0")
    @Test fun shieldProtectsAPawnUntilItsOwnersNextTurn() {}

    @Ignore("scaffold: Phase 0")
    @Test fun shieldWithNoRingPawnPlaysAsHopTwo() {}

    @Ignore("scaffold: Phase 0")
    @Test fun againDrawsAnotherCardForTheSameSeat() {}

    @Ignore("scaffold: Phase 0")
    @Test fun againChainsAreFiniteBecauseTheDeckIs() {}

    @Ignore("scaffold: Phase 0")
    @Test fun padHopsThreeAndBumpsARivalOnArrival() {}

    @Ignore("scaffold: Phase 0")
    @Test fun padHopIsCancelledByAnOwnPawnOrShieldedRivalOnArrival() {}

    @Ignore("scaffold: Phase 0")
    @Test fun padDoesNotTriggerOnSwapBumpOrExit() {}

    @Ignore("scaffold: Phase 0")
    @Test fun padsOffMakesPadsPlainSquares() {}

    @Ignore("scaffold: Phase 0")
    @Test fun noLegalMoveMeansNoMoveAndTheTurnPasses() {}

    @Ignore("scaffold: Phase 0")
    @Test fun noLegalMoveWithADoOverOffersDoOverOrPass() {}

    @Ignore("scaffold: Phase 0")
    @Test fun doOverDiscardsTheCardAndDrawsAgain() {}

    @Ignore("scaffold: Phase 0")
    @Test fun doOverCountNeverGoesNegative() {}

    @Ignore("scaffold: Phase 0")
    @Test fun singleLegalMoveIsAutoPlayedWhenTheOptionIsOn() {}

    @Ignore("scaffold: Phase 0")
    @Test fun emptyPileReshufflesTheDiscards() {}

    @Ignore("scaffold: Phase 0")
    @Test fun allPawnsHomeEndsTheBoard() {}

    @Ignore("scaffold: Phase 0")
    @Test fun turnPassesClockwiseSkippingNothing() {}

    @Ignore("scaffold: Phase 0")
    @Test fun helperPackPutsOnePawnOnItsExitAndGivesThreeDoOvers() {}
}
