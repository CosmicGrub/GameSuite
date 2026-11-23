package com.gamesuite.games.territorywars

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD test stubs: GameModule wrapper, snapshot, resume, threading (doc 3.6 and 3.7).
 * Every test is @Ignore until the engine exists, so the gate stays green. Each name is a test-plan item
 * (docs/design/boardgames/territory-wars.md section 3.10); un-ignore as the phase that owns it lands.
 */
class TerritoryWarsGameTest {
    @Ignore("scaffold: IllegalArgumentException")
    @Test fun initRejectsFewerThanTwoOrMoreThanSixSeats() {}

    @Ignore("scaffold: length, fortify, handicap, autoPlace parse")
    @Test fun startMatchBuildsAConfigFromHubOptions() {}

    @Ignore("scaffold: mapIdFor rule")
    @Test fun blitzWithFiveSeatsPlaysTheFullMap() {}

    @Ignore("scaffold: even seed west, odd seed east")
    @Test fun blitzWithFewerSeatsPicksAHalfFromTheSeed() {}

    @Ignore("scaffold: snapshot -> restore -> structurally equal state for each TurnPhase")
    @Test fun snapshotRoundTripsEveryPhase() {}

    @Ignore("scaffold: contract")
    @Test fun snapshotIsNullBeforeStartAndAfterTheWarEnds() {}

    @Ignore("scaffold: each validation item in the DTO KDoc has a case")
    @Test fun restoreRejectsCorruptStaleAndForeignSnapshots() {}

    @Ignore("scaffold: returns false, state unchanged")
    @Test fun restoreLeavesTheGameUntouchedOnFailure() {}

    @Ignore("scaffold: next die after restore equals next die of uninterrupted war")
    @Test fun restoreFastForwardsTheDiceStream() {}

    @Ignore("scaffold: TerritoryWarsState.redactedFor")
    @Test fun redactedStateNeverContainsAnotherSeatsCards() {}

    @Ignore("scaffold: slide-and-climb parity")
    @Test fun leaveSessionRecordsWinsAndALoneLeaderWins() {}

    @Ignore("scaffold: GameModule.abortMatch default")
    @Test fun abortMatchEndsWithoutScores() {}

    @Ignore("scaffold: identity guard")
    @Test fun botStepIsAppliedOnlyIfTheTableHasNotMoved() {}

    @Ignore("scaffold: bot turn is stateless")
    @Test fun savingDuringABotTurnResumesWithTheBotReplanning() {}

    @Ignore("scaffold: UI state derived, not saved")
    @Test fun handOverCurtainShowsAgainAfterResumeInPassAndPlay() {}
}
