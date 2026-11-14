package com.gamesuite.games.bumprun

import org.junit.Ignore
import org.junit.Test

/**
 * Bump and Run: scaffold stubs. Every test of the test plan in docs/design/boardgames/bump-run.md is listed here by name,
 * ignored until its phase lands (Phase 1). Un-ignore a test only together with the code it proves.
 */
class BumpRunBotTest {
    @Ignore("scaffold: Phase 1")
    @Test fun easyBotNeverStallsTheTable() {}

    @Ignore("scaffold: Phase 1")
    @Test fun easyBotIgnoresDangerButNeverPlaysIllegally() {}

    @Ignore("scaffold: Phase 1")
    @Test fun mediumBotPrefersHomeThenBumpThenExit() {}

    @Ignore("scaffold: Phase 1")
    @Test fun mediumBotAvoidsLandingJustAheadOfARival() {}

    @Ignore("scaffold: Phase 1")
    @Test fun mediumBotUsesADoOverOnlyBelowTheThreshold() {}

    @Ignore("scaffold: Phase 1")
    @Test fun hardBotCountsTheCardsLeftInTheDeck() {}

    @Ignore("scaffold: Phase 1")
    @Test fun hardBotChoiceIsStableForAState() {}

    @Ignore("scaffold: Phase 1")
    @Test fun hardBotStaysUnderThirtyMillisecondsPerDecision() {}

    @Ignore("scaffold: Phase 1")
    @Test fun botNeverTouchesTheDeckRandom() {}

    @Ignore("scaffold: Phase 1")
    @Test fun botsPlayEveryLevelToTheEndOnAThousandSeeds() {}

    @Ignore("scaffold: Phase 1")
    @Test fun hardBeatsMediumBeatsEasyOverManyBoards() {}
}
