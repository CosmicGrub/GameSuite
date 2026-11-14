package com.gamesuite.games.stackzoo

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/stack-zoo.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class StackZooBotTest {
    @Ignore("SZ-B1: tolerance 4 points")
    @Test
    fun sz_b1_easy_bot_blunder_rate_is_about_twenty_percent_and_hard_about_three_over_200_games() {
        // TODO(stackzoo): tolerance 4 points
    }

    @Ignore("SZ-B2: same inputs same release")
    @Test
    fun sz_b2_bot_placement_is_deterministic_for_seed_and_state() {
        // TODO(stackzoo): same inputs same release
    }

    @Ignore("SZ-B3: at most 12 candidates, 150 steps each, off the main thread")
    @Test
    fun sz_b3_hard_bot_rollout_stays_within_time_budget() {
        // TODO(stackzoo): at most 12 candidates, 150 steps each, off the main thread
    }

    @Ignore("SZ-B4: no instant drops")
    @Test
    fun sz_b4_bot_think_time_is_at_least_800ms_and_drag_is_animated() {
        // TODO(stackzoo): no instant drops
    }
}
