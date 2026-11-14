package com.gamesuite.games.territorywars

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD test stubs: Monte Carlo balance harness, headless (doc 3.4).
 * Every test is @Ignore until the engine exists, so the gate stays green. Each name is a test-plan item
 * (docs/design/boardgames/territory-wars.md section 3.10); un-ignore as the phase that owns it lands.
 */
class TerritoryWarsSimulationTest {
    @Ignore("scaffold: 10,000 wars per seat count, rotated seats")
    @Test fun winnerBySeatIsWithinTwoPointsOfFairForTwoToSixBotSeats() {}

    @Ignore("scaffold: P50/P90 per mode vs doc table")
    @Test fun warLengthInRoundsMatchesTheModeTargets() {}

    @Ignore("scaffold: cap/stalemate endings")
    @Test fun stalemateRateIsUnderThreePercentForStandard() {}

    @Ignore("scaffold: skill ladder gates")
    @Test fun hardBeatsMediumAndMediumBeatsEasyInMixedTables() {}

    @Ignore("scaffold: ablation: with and without cards")
    @Test fun cardsDoNotDecideMoreThanHalfOfAllWars() {}

    @Ignore("scaffold: Blitz target")
    @Test fun blitzFinishesInsideTheRoundCapInNinetyPercentOfWars() {}

    @Ignore("scaffold: same seeds, same results")
    @Test fun simulationIsDeterministicForAFixedSeedList() {}

    @Ignore("scaffold: throughput guard (manual gate, not CI)")
    @Test fun tenThousandWarsFinishInUnderTenMinutesOnADevMachine() {}
}
