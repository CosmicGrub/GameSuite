package com.gamesuite.games.flickrink

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (HubSpecs). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/flick-rink.md, section SCALE > Test plan. Nothing here has been run.
 */
class FlickRinkHubSpecsTest {
    @Ignore("TODO FR-H1: audience KIDS, minAge 5, no NEARBY or ONLINE mode")
    @Test fun hubSpecIsKidsAudienceMinAgeFiveAndNotNetworked() {}

    @Ignore("TODO FR-H2: hubKey(text) exists in both locales")
    @Test fun everyHubTextHasAnEnglishAndASpanishString() {}

    @Ignore("TODO FR-H3: the same numbered placeholders in EN and ES")
    @Test fun ourStringsMatchPlaceholdersInBothLocales() {}

    @Ignore("TODO FR-H4: our keys are defined only in flick_rink_strings.xml")
    @Test fun noStringKeyClashesWithAnotherFile() {}

    @Ignore("TODO FR-H5: every flick_rink_tut_* in EN and ES")
    @Test fun tutorialLinesAreUnderOneHundredTwentyCharacters() {}

    @Ignore("TODO FR-H6: style, game, band, length, assist and relaxed arrive in LaunchOptions.extras")
    @Test fun hubLaunchOptionsReachTheGameAsExtras() {}
}
