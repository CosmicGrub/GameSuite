package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonHubSpecsTest {
    @Ignore("kid filter: not built yet")
    @Test
    fun specIsAdultsAudienceWithMinAgeEightSoKidProfilesNeverSeeIt() {
        // TODO: kid filter
    }

    @Ignore("kid filter: not built yet")
    @Test
    fun kidMaySeeIsFalseForEveryAgeBand() {
        // TODO: kid filter
    }

    @Ignore("registry guard: not built yet")
    @Test
    fun specIsNotRegisteredInGameHubSpecsUntilTheCoordinatorAddsIt() {
        // TODO: registry guard
    }

    @Ignore("HubLocalizationTest rules: not built yet")
    @Test
    fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        // TODO: HubLocalizationTest rules
    }

    @Ignore("buildHubLaunch: not built yet")
    @Test
    fun launchesProduceTwoToSixSeatsForCpuAndPassAndPlayModes() {
        // TODO: buildHubLaunch
    }

    @Ignore("visibleWhen: not built yet")
    @Test
    fun handicapOptionsOnlyShowWhenAPlayerIsChosen() {
        // TODO: visibleWhen
    }
}
