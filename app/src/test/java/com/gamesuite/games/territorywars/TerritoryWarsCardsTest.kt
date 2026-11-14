package com.gamesuite.games.territorywars

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD test stubs: Deck composition and sets (doc 2.1 rules 13-17, 2.4).
 * Every test is @Ignore until the engine exists, so the gate stays green. Each name is a test-plan item
 * (docs/design/boardgames/territory-wars.md section 3.10); un-ignore as the phase that owns it lands.
 */
class TerritoryWarsCardsTest {
    @Ignore("scaffold: 38 cards, 12 per symbol, ids unique")
    @Test fun fullDeckHasThirtySixTerritoryCardsAndTwoWilds() {}

    @Ignore("scaffold: 19 cards")
    @Test fun blitzDeckHasEighteenTerritoryCardsAndOneWild() {}

    @Ignore("scaffold: symbol cycling check")
    @Test fun everyRegionHoldsTwoOfEachSymbol() {}

    @Ignore("scaffold: isSet true")
    @Test fun threeOfOneSymbolIsASet() {}

    @Ignore("scaffold: isSet true")
    @Test fun oneOfEachSymbolIsASet() {}

    @Ignore("scaffold: isSet true for all pairs")
    @Test fun aWildWithAnyTwoCardsIsASet() {}

    @Ignore("scaffold: isSet false")
    @Test fun twoOfOneSymbolAndOneOtherIsNotASet() {}

    @Ignore("scaffold: same card twice, 2 or 4 cards")
    @Test fun duplicateOrWrongSizedSelectionsAreNotASet() {}

    @Ignore("scaffold: hasSet exhaustive over all 5-card hands of the 38 deck (pigeonhole)")
    @Test fun anyFiveCardsContainASet() {}

    @Ignore("scaffold: setValue(0..10)")
    @Test fun setValuesEscalateThreeFiveSevenNineTwelveFifteenEighteenThenPlusFour() {}

    @Ignore("scaffold: second player's first turn-in pays the 2nd value")
    @Test fun setCounterIsSharedByAllPlayers() {}

    @Ignore("scaffold: draw with empty deck")
    @Test fun emptyDeckReshufflesTheDiscardPile() {}
}
