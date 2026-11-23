package com.gamesuite.games.stackzoo

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/stack-zoo.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class StackZooHubSpecsTest {
    @Ignore("SZ-H1: audience, minAge, no NEARBY or ONLINE mode")
    @Test
    fun sz_h1_hub_spec_is_kids_min_age_4_and_not_networked() {
        // TODO(stackzoo): audience, minAge, no NEARBY or ONLINE mode
    }

    @Ignore("SZ-H2: same rule as HubLocalizationTest")
    @Test
    fun sz_h2_every_hub_text_has_en_and_es_resource_under_hub_key() {
        // TODO(stackzoo): same rule as HubLocalizationTest
    }

    @Ignore("SZ-H3: none, all, p1..p4")
    @Test
    fun sz_h3_option_defaults_resolve_and_assist_choices_cover_four_seats() {
        // TODO(stackzoo): none, all, p1..p4
    }

    @Ignore("SZ-H4: values and values-es key sets are equal")
    @Test
    fun sz_h4_strings_have_es_parity_and_no_todo_translate_in_values() {
        // TODO(stackzoo): values and values-es key sets are equal
    }

    @Ignore("SZ-H5: buildHubLaunch round trip")
    @Test
    fun sz_h5_launch_options_reach_extras_for_goal_length_assist_relaxed_and_switch() {
        // TODO(stackzoo): buildHubLaunch round trip
    }
}
