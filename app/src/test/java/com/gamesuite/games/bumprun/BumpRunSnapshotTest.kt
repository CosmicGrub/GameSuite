package com.gamesuite.games.bumprun

import org.junit.Ignore
import org.junit.Test

/**
 * Bump and Run: scaffold stubs. Every test of the test plan in docs/design/boardgames/bump-run.md is listed here by name,
 * ignored until its phase lands (Phase 2). Un-ignore a test only together with the code it proves.
 */
class BumpRunSnapshotTest {
    @Ignore("scaffold: Phase 2")
    @Test fun snapshotRoundTripRestoresEveryField() {}

    @Ignore("scaffold: Phase 2")
    @Test fun snapshotIsNullAfterTheMatchIsOver() {}

    @Ignore("scaffold: Phase 2")
    @Test fun corruptJsonIsRejected() {}

    @Ignore("scaffold: Phase 2")
    @Test fun truncatedJsonIsRejected() {}

    @Ignore("scaffold: Phase 2")
    @Test fun wrongRosterIsRejected() {}

    @Ignore("scaffold: Phase 2")
    @Test fun pileDiscardAndCardMustCoverAllFiftyFourIds() {}

    @Ignore("scaffold: Phase 2")
    @Test fun twoPawnsOnOneRingSquareIsRejected() {}

    @Ignore("scaffold: Phase 2")
    @Test fun shieldOnANonRingPawnIsRejected() {}

    @Ignore("scaffold: Phase 2")
    @Test fun awaitChoiceNeedsACardInPlay() {}

    @Ignore("scaffold: Phase 2")
    @Test fun teamsWithoutFourSeatsAreRejected() {}

    @Ignore("scaffold: Phase 2")
    @Test fun winnerMustMatchPawnsHome() {}

    @Ignore("scaffold: Phase 2")
    @Test fun pawnProgressOutOfRangeIsRejected() {}

    @Ignore("scaffold: Phase 2")
    @Test fun snapshotStaysUnderThreeKilobytes() {}

    @Ignore("scaffold: Phase 2")
    @Test fun restoredBoardContinuesIdenticallyToTheOriginal() {}

    @Ignore("scaffold: Phase 2")
    @Test fun hugeOrNastyInputNeverThrows() {}
}
