package com.gamesuite.games.stackzoo

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/stack-zoo.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class StackZooPhysicsTest {
    @Ignore("SZ-S1: turtle at x 0, asleep within 3.5 s")
    @Test
    fun sz_s1_flat_wide_animal_dropped_on_centre_of_base_rests_and_sleeps() {
        // TODO(stackzoo): turtle at x 0, asleep within 3.5 s
    }

    @Ignore("SZ-S2: x 4.2 on a base 6 wide")
    @Test
    fun sz_s2_piece_released_beyond_base_edge_falls_and_is_flagged_fallen() {
        // TODO(stackzoo): x 4.2 on a base 6 wide
    }

    @Ignore("SZ-S3: period near 1.6 s, rest within 6 s")
    @Test
    fun sz_s3_base_rocks_back_to_rest_with_damping_within_settle_window() {
        // TODO(stackzoo): period near 1.6 s, rest within 6 s
    }

    @Ignore("SZ-S4: 0.35 rad plus epsilon under a heavy edge load")
    @Test
    fun sz_s4_base_never_exceeds_hard_stop_angle() {
        // TODO(stackzoo): 0.35 rad plus epsilon under a heavy edge load
    }

    @Ignore("SZ-S5: no fallen flags")
    @Test
    fun sz_s5_stack_of_five_flat_animals_stays_standing_for_sixty_seconds() {
        // TODO(stackzoo): no fallen flags
    }

    @Ignore("SZ-S6: friction table sanity at 20 degrees")
    @Test
    fun sz_s6_slippery_snake_slides_on_tilt_but_turtle_holds() {
        // TODO(stackzoo): friction table sanity at 20 degrees
    }

    @Ignore("SZ-S7: sleep timer 60 steps")
    @Test
    fun sz_s7_sleeping_bodies_wake_when_hit_and_sleep_again() {
        // TODO(stackzoo): sleep timer 60 steps
    }

    @Ignore("SZ-S8: 800 random releases")
    @Test
    fun sz_s8_sweep_down_placement_never_leaves_overlap_and_gap_is_at_most_point_one() {
        // TODO(stackzoo): 800 random releases
    }

    @Ignore("SZ-S9: contact solver quality")
    @Test
    fun sz_s9_penetration_stays_below_point_zero_five_with_ten_stacked_bodies() {
        // TODO(stackzoo): contact solver quality
    }

    @Ignore("SZ-S10: no pop to the wrong side")
    @Test
    fun sz_s10_release_overlapping_other_body_lifts_out_deterministically() {
        // TODO(stackzoo): no pop to the wrong side
    }

    @Ignore("SZ-S11: restitution 0, energy never increases beyond epsilon")
    @Test
    fun sz_s11_no_body_gains_energy_from_contacts_in_a_closed_drop_test() {
        // TODO(stackzoo): restitution 0, energy never increases beyond epsilon
    }

    @Ignore("SZ-S12: k times 2, zeta 0.6")
    @Test
    fun sz_s12_relaxed_base_is_stiffer_and_settles_faster_than_normal() {
        // TODO(stackzoo): k times 2, zeta 0.6
    }

    @Ignore("SZ-S13: ballast keel: 14 animals centred, base angle stays under 0.05 rad")
    @Test
    fun sz_s13_tall_heavy_stack_never_pulls_the_log_over_without_an_offset_load() {
        // TODO(stackzoo): ballast keel: 14 animals centred, base angle stays under 0.05 rad
    }
}
