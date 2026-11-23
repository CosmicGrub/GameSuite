package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadMovementTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadMovementTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun spinMovesExactlyThatManySquares() {
        // A spin of n walks n nodes along the chosen branches
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun passingAPayStopCollectsOnce() {
        // Passing collects salary once, never twice in one move
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun landingOnAPayStopAddsTheBonus() {
        // Landing pays salary plus 10
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun passingAForkMidMoveAsksForAChoice() {
        // Movement pauses with stepsLeft kept
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun landingOnAForkDefersTheChoiceToNextTurn() {
        // A piece that stops on a fork chooses before the next spin
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun enteringARetireNodeStopsMovement() {
        // Spare steps are wasted at the retire node
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun luckyCoinRespinsOnlyOncePerTurn() {
        // Second use in the same turn is rejected
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun luckyCoinOnlyBeforeTheMove() {
        // No respin once the piece has moved
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun catchUpSpinOnlyWhenFarBehind() {
        // Double spin triggers at the size gap and not before
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun cardMovementNeverTriggersASecondCard() {
        // Move effects collect pay stops but skip landing effects
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun moveBackStopsAtTheStartOfTheCurrentSegment() {
        // Never crosses a fork or a merge
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun extraTurnCardGrantsAnotherSpin() {
        // EXTRA_TURN keeps the same seat on turn
        // TODO
    }
}
