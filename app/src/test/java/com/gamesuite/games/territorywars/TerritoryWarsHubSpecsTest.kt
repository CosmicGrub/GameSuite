package com.gamesuite.games.territorywars

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD test stubs: Hub spec and strings (doc 2.9 and 4.2).
 * Every test is @Ignore until the engine exists, so the gate stays green. Each name is a test-plan item
 * (docs/design/boardgames/territory-wars.md section 3.10); un-ignore as the phase that owns it lands.
 */
class TerritoryWarsHubSpecsTest {
    @Ignore("scaffold: audience ADULTS, minAge 10")
    @Test fun specIsAdultsWithMinAgeTen() {}

    @Ignore("scaffold: no NEARBY / ONLINE mode")
    @Test fun specIsNotNetworkedInTheMvp() {}

    @Ignore("scaffold: minSeats 2, maxSeats 6 in both modes")
    @Test fun seatRangeIsTwoToSix() {}

    @Ignore("scaffold: hubKey(text) exists in values and values-es, or in strings.xml")
    @Test fun everyHubTextHasAResourceInBothLocales() {}

    @Ignore("scaffold: unique across res/values")
    @Test fun noHubOrTwStringNameClashesWithAnotherFile() {}

    @Ignore("scaffold: %1 and %2 placeholder parity")
    @Test fun spanishPlaceholdersMatchEnglishOnes() {}

    @Ignore("scaffold: key parity for territory_wars_strings.xml")
    @Test fun englishAndSpanishFilesHaveTheSameKeys() {}

    @Ignore("scaffold: EN and ES")
    @Test fun tutorialLinesAreUnderOneHundredTwentyCharacters() {}

    @Ignore("scaffold: 3.0 non-text, 4.5 label text, both themes")
    @Test fun accentColoursMeetContrastMinimums() {}

    @Ignore("scaffold: six distinct colours and six distinct patterns")
    @Test fun seatColoursAndPatternsAreDistinctForSixSeats() {}

    @Ignore("scaffold: whyKey resources exist")
    @Test fun everyRejectReasonKeyExistsAsAString() {}
}
