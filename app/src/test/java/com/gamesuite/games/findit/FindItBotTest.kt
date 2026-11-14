package com.gamesuite.games.findit

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/find-it.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class FindItBotTest {
    @Ignore("FI-B1: easy 7000, medium 4000, hard 2200 ms mean times 0.7 tiny, 1.0 little, 1.3 standard")
    @Test
    fun fi_b1_bot_reaction_means_match_level_table_within_tolerance_over_1000_rounds() {
        // TODO(findit): easy 7000, medium 4000, hard 2200 ms mean times 0.7 tiny, 1.0 little, 1.3 standard
    }

    @Ignore("FI-B2: easy 15 percent, medium 7 percent, hard 2 percent")
    @Test
    fun fi_b2_bot_error_rate_matches_level_table() {
        // TODO(findit): easy 15 percent, medium 7 percent, hard 2 percent
    }

    @Ignore("FI-B3: same inputs give the same tap time and the same wrong or right choice")
    @Test
    fun fi_b3_bot_schedule_is_deterministic_for_seed_round_and_seat() {
        // TODO(findit): same inputs give the same tap time and the same wrong or right choice
    }

    @Ignore("FI-B4: 12 s, 8 s, 5 s")
    @Test
    fun fi_b4_helper_hunt_find_interval_matches_level_table() {
        // TODO(findit): 12 s, 8 s, 5 s
    }
}
