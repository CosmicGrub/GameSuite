package com.gamesuite.games.territorywars

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD test stubs: Battle resolution known values (doc 2.1 rules 8-10 and the 3.4 reference table).
 * Every test is @Ignore until the engine exists, so the gate stays green. Each name is a test-plan item
 * (docs/design/boardgames/territory-wars.md section 3.10); un-ignore as the phase that owns it lands.
 */
class TerritoryWarsBattleTest {
    @Ignore("scaffold: enumerate all 7776 outcomes: 1445/3888, 2611/7776, 2275/7776")
    @Test fun threeDiceVsTwoDiceMatchesExactProbabilities() {}

    @Ignore("scaffold: enumerate 1296 outcomes: 295/1296, 35/108, 581/1296")
    @Test fun twoDiceVsTwoDiceMatchesExactProbabilities() {}

    @Ignore("scaffold: 161/216 attacker loses nothing, 55/216 loses one")
    @Test fun oneDieVsTwoDiceMatchesExactProbabilities() {}

    @Ignore("scaffold: 95/144 and 49/144")
    @Test fun threeDiceVsOneDieMatchesExactProbabilities() {}

    @Ignore("scaffold: scripted 4 vs 4 removes one attacker army")
    @Test fun tieGoesToTheDefender() {}

    @Ignore("scaffold: scripted unsorted rolls are sorted high to low before pairing")
    @Test fun highestDiceAreComparedFirstThenSecondHighest() {}

    @Ignore("scaffold: 3 v 1 has one pair, 1 v 2 has one pair")
    @Test fun dicePairsEqualMinOfBothSides() {}

    @Ignore("scaffold: ScriptedDice order contract")
    @Test fun diceAreDrawnAttackerFirstThenDefender() {}

    @Ignore("scaffold: attackerLosses + defenderLosses == pairs, always")
    @Test fun totalLossesPerRoundEqualPairsCompared() {}

    @Ignore("scaffold: DP table: 2 vs 1 = 0.754, 5 vs 5 = 0.506, 10 vs 5 = 0.916 (doc 3.4)")
    @Test fun conquestWinProbabilityTableMatchesReferenceValues() {}

    @Ignore("scaffold: same seed, same dice; rolled counter matches draws")
    @Test fun seededDiceReplayTheSameSequence() {}

    @Ignore("scaffold: chi-square on faces within bounds")
    @Test fun diceAreFairOverOneMillionRolls() {}
}
