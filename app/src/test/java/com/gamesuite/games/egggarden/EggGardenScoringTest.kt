package com.gamesuite.games.egggarden

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Scoring). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/egg-garden.md, section SCALE > Test plan. Nothing here has been run.
 */
class EggGardenScoringTest {
    @Ignore("TODO EG-S1: hand-built gardens with known totals")
    @Test fun eggScoreIsTheSumOfEggsOnPlacedSquares() {}

    @Ignore("TODO EG-S2: diagonals never join a patch")
    @Test fun patchSizeIsAnOrthogonalFloodFillExcludingTheNest() {}

    @Ignore("TODO EG-S3: 4 points; none when the largest is under 3")
    @Test fun theBonusGoesToEveryTiedLargestPatchOfAtLeastThree() {}

    @Ignore("TODO EG-S4: each rung decided by a crafted pair of gardens")
    @Test fun tieBreakOrderIsEggsThenPatchThenSquaresThenShared() {}

    @Ignore("TODO EG-S5: property: shuffled placement order, same score")
    @Test fun scoreDoesNotDependOnPlacementOrder() {}

    @Ignore("TODO EG-S6: fuzz 10 000 games")
    @Test fun randomLegalPlayNeverThrowsAndScoresAreNonNegative() {}

    @Ignore("TODO EG-S7: every band and star tier boundary")
    @Test fun coopStarsFollowTheGoalTable() {}
}
