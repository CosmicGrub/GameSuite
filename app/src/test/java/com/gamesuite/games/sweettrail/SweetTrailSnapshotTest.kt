package com.gamesuite.games.sweettrail

import org.junit.Ignore
import org.junit.Test

/**
 * Sweet Trail: scaffold stubs. Every test of the test plan in docs/design/boardgames/sweet-trail.md is listed here by name,
 * ignored until its phase lands (Phase 1). Un-ignore a test only together with the code it proves.
 */
class SweetTrailSnapshotTest {
    @Ignore("scaffold: Phase 1")
    @Test fun snapshotRoundTripRestoresEveryField() {}

    @Ignore("scaffold: Phase 1")
    @Test fun snapshotIsNullAfterTheMatchIsOver() {}

    @Ignore("scaffold: Phase 1")
    @Test fun corruptJsonIsRejected() {}

    @Ignore("scaffold: Phase 1")
    @Test fun truncatedJsonIsRejected() {}

    @Ignore("scaffold: Phase 1")
    @Test fun wrongRosterIsRejected() {}

    @Ignore("scaffold: Phase 1")
    @Test fun pileDiscardAndCardMustCoverAllSixtySixIds() {}

    @Ignore("scaffold: Phase 1")
    @Test fun positionOutOfRangeIsRejected() {}

    @Ignore("scaffold: Phase 1")
    @Test fun stuckSeatMustStandOnAPuddle() {}

    @Ignore("scaffold: Phase 1")
    @Test fun pendingMustMatchThePendingEvent() {}

    @Ignore("scaffold: Phase 1")
    @Test fun moveEventMustReplayThroughTheEngine() {}

    @Ignore("scaffold: Phase 1")
    @Test fun winnerMustBeTheOnlyPieceOnTheFinish() {}

    @Ignore("scaffold: Phase 1")
    @Test fun snapshotStaysUnderFourKilobytes() {}

    @Ignore("scaffold: Phase 1")
    @Test fun restoredBoardContinuesIdenticallyToTheOriginal() {}

    @Ignore("scaffold: Phase 1")
    @Test fun hugeOrNastyInputNeverThrows() {}
}
