package com.gamesuite.games.egggarden

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (HubSpecs). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/egg-garden.md, section SCALE > Test plan. Nothing here has been run.
 */
class EggGardenHubSpecsTest {
    @Ignore("TODO EG-H4: audience KIDS, minAge 5, no NEARBY or ONLINE mode")
    @Test fun hubSpecIsKidsAudienceMinAgeFiveAndNotNetworked() {}

    @Ignore("TODO EG-H5: hubKey(text) exists in both locales")
    @Test fun everyHubTextHasAnEnglishAndASpanishString() {}

    @Ignore("TODO EG-H6: the same numbered placeholders in EN and ES")
    @Test fun ourStringsMatchPlaceholdersInBothLocales() {}

    @Ignore("TODO EG-H7: our keys are defined only in egg_garden_strings.xml")
    @Test fun noStringKeyClashesWithAnotherFile() {}

    @Ignore("TODO EG-H8: every egg_garden_tut_* in EN and ES")
    @Test fun tutorialLinesAreUnderOneHundredTwentyCharacters() {}

    @Ignore("TODO EG-H9: style, length, peek, free and band arrive in LaunchOptions.extras")
    @Test fun hubLaunchOptionsReachTheGameAsExtras() {}
}
