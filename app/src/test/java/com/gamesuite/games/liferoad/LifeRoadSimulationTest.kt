package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadSimulationTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadSimulationTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun freshStartRateStaysInTarget() {
        // Standard Medium at most 8% of player-games
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun winnerBySeatStaysWithinTolerance() {
        // Each seat within 1.5 points of 1/n at 20k games
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun averageLengthMatchesEachMode() {
        // Turns per player near 7, 13 and 19
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun learnVersusWorkWinRateIsBalanced() {
        // Each between 45% and 55%
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun coldBoldVersusMeadowIsBalanced() {
        // Cliff and Meadow each between 45% and 55%
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun grandVersusCozyIsBalanced() {
        // Grand between 40% and 55% of the lane choices that are legal
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun coverIsNotADominantStrategy() {
        // Buying all three covers wins at most 55% against not buying
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun hardBeatsEasyInTheSkillWindow() {
        // Hard versus Easy head to head between 58% and 70%
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun handicapMovesWinRateByTheTargetAmount() {
        // Small about +5 points, big about +12
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun noGameExceedsTheActionCap() {
        // Every game ends below 4000 engine actions
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun twentyThousandGamesRunUnderNinetySeconds() {
        // Throughput gate for CI
        // TODO
    }
}
