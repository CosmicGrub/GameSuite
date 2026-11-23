package com.gamesuite.games.sweettrail

import org.junit.Ignore
import org.junit.Test

/**
 * Sweet Trail: scaffold stubs. Every test of the test plan in docs/design/boardgames/sweet-trail.md is listed here by name,
 * ignored until its phase lands (Phase 0). Un-ignore a test only together with the code it proves.
 */
class SweetTrailRulesTest {
    @Ignore("scaffold: Phase 0")
    @Test fun singleCardMovesToTheNextSquareOfItsColour() {}

    @Ignore("scaffold: Phase 0")
    @Test fun doubleCardMovesToTheSecondNextSquareOfItsColour() {}

    @Ignore("scaffold: Phase 0")
    @Test fun doubleCardWithOnlyOneSquareLeftReachesTheFinish() {}

    @Ignore("scaffold: Phase 0")
    @Test fun singleCardWithNoSquareAheadReachesTheFinish() {}

    @Ignore("scaffold: Phase 0")
    @Test fun landmarkAheadJumpsThere() {}

    @Ignore("scaffold: Phase 0")
    @Test fun landmarkBehindLeavesThePieceAndDances() {}

    @Ignore("scaffold: Phase 0")
    @Test fun landmarkOnTheCurrentSquareLeavesThePiece() {}

    @Ignore("scaffold: Phase 0")
    @Test fun landingOnAShortcutStartTakesTheShortcutOnce() {}

    @Ignore("scaffold: Phase 0")
    @Test fun shortcutEndNeverTriggersAnotherJump() {}

    @Ignore("scaffold: Phase 0")
    @Test fun shortcutsAreOffInLongLength() {}

    @Ignore("scaffold: Phase 0")
    @Test fun landingOnThePuddleSticksForOneTurn() {}

    @Ignore("scaffold: Phase 0")
    @Test fun aStuckSeatSkipsItsDrawThenIsFree() {}

    @Ignore("scaffold: Phase 0")
    @Test fun stickyOffMakesThePuddleAPlainSquare() {}

    @Ignore("scaffold: Phase 0")
    @Test fun noCardEverMovesAPieceBackwards() {}

    @Ignore("scaffold: Phase 0")
    @Test fun firstPieceOnTheFinishWinsAndTheBoardEnds() {}

    @Ignore("scaffold: Phase 0")
    @Test fun turnPassesToTheNextSeatAfterEveryMove() {}

    @Ignore("scaffold: Phase 0")
    @Test fun starterRotatesBetweenBoards() {}

    @Ignore("scaffold: Phase 0")
    @Test fun reshuffleHappensWhenThePileIsEmpty() {}
}
