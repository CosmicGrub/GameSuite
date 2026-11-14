package com.gamesuite.games.trailtogether

import org.junit.Ignore
import org.junit.Test

/**
 * Scaffold stubs: Game wrapper, resume snapshot and result rules of sections 2.3 and 3.5.
 * Every test is @Ignore until its feature lands, so the gate stays green. ID = row in the design doc section 3.9.
 */
class TrailTogetherGameTest {
    @Ignore("scaffold: T25 not implemented yet")
    @Test
    fun snapshotIsNullBeforeStartAndAfterMatchOver() {
        // TODO(T25): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T26 not implemented yet")
    @Test
    fun snapshotRoundTripRestoresIdenticalState() {
        // TODO(T26): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T27 not implemented yet")
    @Test
    fun restoreRejectsWrongVersion() {
        // TODO(T27): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T28 not implemented yet")
    @Test
    fun restoreRejectsRosterMismatch() {
        // TODO(T28): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T29 not implemented yet")
    @Test
    fun restoreRejectsOutOfRangePalPositionAndFogOverflow() {
        // TODO(T29): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T30 not implemented yet")
    @Test
    fun restoreRejectsDeckThatDoesNotMatchTable() {
        // TODO(T30): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T31 not implemented yet")
    @Test
    fun restoreFailureLeavesGameUntouched() {
        // TODO(T31): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T32 not implemented yet")
    @Test
    fun leavingBeforeAnyTryEndsUsesAbortPath() {
        // TODO(T32): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T33 not implemented yet")
    @Test
    fun resultMarksEveryoneWinnerOnlyWhenAtLeastOneTryWon() {
        // TODO(T33): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T34 not implemented yet")
    @Test
    fun resultAndStatusTextUseCooperativeWordingOnly() {
        // TODO(T34): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T35 not implemented yet")
    @Test
    fun pauseStopsHelperAutoplayAndResumeContinues() {
        // TODO(T35): implement per docs/design/kids-games/trail-together.md section 3.9
    }
}
