package com.gamesuite.games.bumprun

import org.junit.Ignore
import org.junit.Test

/**
 * Bump and Run: scaffold stubs. Every test of the test plan in docs/design/boardgames/bump-run.md is listed here by name,
 * ignored until its phase lands (Phase 2). Un-ignore a test only together with the code it proves.
 */
class BumpRunGameTest {
    @Ignore("scaffold: Phase 2")
    @Test fun drawThenPlayMovesTheChosenPawn() {}

    @Ignore("scaffold: Phase 2")
    @Test fun playingAnIllegalMoveIsRejectedWithAReason() {}

    @Ignore("scaffold: Phase 2")
    @Test fun drawingTwiceIsRejected() {}

    @Ignore("scaffold: Phase 2")
    @Test fun chanceComesOnlyFromTheInjectedRandom() {}

    @Ignore("scaffold: Phase 2")
    @Test fun firstOpenerIsHonoured() {}

    @Ignore("scaffold: Phase 2")
    @Test fun playAgainRotatesTheStarter() {}

    @Ignore("scaffold: Phase 2")
    @Test fun leaveSessionRecordsTheBoardsWon() {}

    @Ignore("scaffold: Phase 2")
    @Test fun computerSeatNeverGetsAHumanTurn() {}

    @Ignore("scaffold: Phase 2")
    @Test fun sessionWinsAccumulateAcrossBoards() {}

    @Ignore("scaffold: Phase 2")
    @Test fun passIsOnlyAllowedWithNoLegalMove() {}
}
