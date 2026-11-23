package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadStateMachineTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadStateMachineTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun everyPhaseTransitionFollowsTheTable() {
        // Random play only visits edges listed in section 2.15
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun illegalActionsAreRejectedWithAReason() {
        // explain() returns a specific LifeRoadReject
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun onlyTheSeatOnTurnMayAct() {
        // Other seats get NOT_YOUR_TURN
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun retiredSeatsAreSkipped() {
        // Turn order skips retired players
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun turnCapEndsTheGame() {
        // Last light fires at the size cap
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun gameOverIsTerminal() {
        // No action is legal after GAME_OVER
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun everyPhaseHasAtLeastOneLegalAction() {
        // No state can stall the table
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun legalActionsMatchWhatApplyAccepts() {
        // legalActions() and apply() agree on 5000 random states
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun startForkIsAskedInSeatOrder() {
        // Every seat chooses Learn or Work before the first spin
        // TODO
    }
}
