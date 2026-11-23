package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadGameTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadGameTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun initRequiresTwoToSixPlayers() {
        // Rejects one and seven
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun startMatchCreatesAStartForkState() {
        // Phase START_FORK, every seat at S0
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun abortMatchReportsNoScores() {
        // wasAborted true and empty scores
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun endMatchReportsScoresAndWinners() {
        // GameResult matches the score card
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun botStepAlwaysChangesTheState() {
        // A CPU seat can never stall the table
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun playAgainKeepsSessionWins() {
        // Session wins survive a new board
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun moduleMetadataMatchesTheDoc() {
        // id life-road, 2..6 players, BOARD, modes
        // TODO
    }
}
