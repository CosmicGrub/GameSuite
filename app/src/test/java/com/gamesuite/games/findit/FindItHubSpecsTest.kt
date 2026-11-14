package com.gamesuite.games.findit

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/find-it.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class FindItHubSpecsTest {
    @Ignore("FI-H1: audience, minAge, no NEARBY or ONLINE mode")
    @Test
    fun fi_h1_hub_spec_is_kids_min_age_4_and_not_networked() {
        // TODO(findit): audience, minAge, no NEARBY or ONLINE mode
    }

    @Ignore("FI-H2: same rule as HubLocalizationTest, applied to our spec")
    @Test
    fun fi_h2_every_hub_text_has_en_and_es_resource_under_hub_key() {
        // TODO(findit): same rule as HubLocalizationTest, applied to our spec
    }

    @Ignore("FI-H3: none, all, p1..p4")
    @Test
    fun fi_h3_option_defaults_resolve_and_handicap_choices_cover_four_seats() {
        // TODO(findit): none, all, p1..p4
    }

    @Ignore("FI-H4: values and values-es key sets are equal")
    @Test
    fun fi_h4_strings_have_es_parity_and_no_todo_translate_in_values() {
        // TODO(findit): values and values-es key sets are equal
    }

    @Ignore("FI-H5: buildHubLaunch round trip")
    @Test
    fun fi_h5_launch_options_reach_extras_for_card_size_length_handicap_relaxed_and_switch() {
        // TODO(findit): buildHubLaunch round trip
    }
}
