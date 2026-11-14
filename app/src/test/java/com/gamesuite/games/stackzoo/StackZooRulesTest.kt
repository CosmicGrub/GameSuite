package com.gamesuite.games.stackzoo

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/stack-zoo.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class StackZooRulesTest {
    @Ignore("SZ-R1: height measured from the base top")
    @Test
    fun sz_r1_coop_goal_height_reached_and_held_for_1500ms_wins_with_we_did_it() {
        // TODO(stackzoo): height measured from the base top
    }

    @Ignore("SZ-R2: no player is marked as a loser")
    @Test
    fun sz_r2_coop_fallen_pieces_are_lost_and_empty_bag_means_try_again_together() {
        // TODO(stackzoo): no player is marked as a loser
    }

    @Ignore("SZ-R3: 1, 2 and 3 stars")
    @Test
    fun sz_r3_coop_star_thresholds_are_zero_25_and_50_percent_of_bag_left() {
        // TODO(stackzoo): 1, 2 and 3 stars
    }

    @Ignore("SZ-R4: extras go back to the box")
    @Test
    fun sz_r4_take_turns_fallen_pieces_return_to_causer_capped_at_three() {
        // TODO(stackzoo): extras go back to the box
    }

    @Ignore("SZ-R5: ties share the rank")
    @Test
    fun sz_r5_take_turns_first_empty_hand_wins_and_others_ranked_by_pieces_left() {
        // TODO(stackzoo): ties share the rank
    }

    @Ignore("SZ-R6: cap is three times the hand size")
    @Test
    fun sz_r6_take_turns_round_cap_ends_game_with_fewest_pieces_winning() {
        // TODO(stackzoo): cap is three times the hand size
    }

    @Ignore("SZ-R7: score is animals standing")
    @Test
    fun sz_r7_solo_balance_three_lives_end_the_run_and_relaxed_has_none() {
        // TODO(stackzoo): score is animals standing
    }

    @Ignore("SZ-R8: 40 piece bag")
    @Test
    fun sz_r8_bag_deal_is_deterministic_for_seed_and_uses_at_most_two_copies() {
        // TODO(stackzoo): 40 piece bag
    }

    @Ignore("SZ-R9: bots included")
    @Test
    fun sz_r9_coop_turn_order_rotates_through_all_seats_without_skipping() {
        // TODO(stackzoo): bots included
    }

    @Ignore("SZ-R10: radius 1.4, pull 0.85")
    @Test
    fun sz_r10_large_assist_moves_release_toward_balance_spot_only_for_the_handicap_seat() {
        // TODO(stackzoo): radius 1.4, pull 0.85
    }

    @Ignore("SZ-R11: flat side down")
    @Test
    fun sz_r11_snap_rotation_assist_aligns_lowest_edge_within_25_degrees() {
        // TODO(stackzoo): flat side down
    }

    @Ignore("SZ-R12: co-op and solo")
    @Test
    fun sz_r12_pick_of_three_offers_three_distinct_pieces_and_returns_the_others() {
        // TODO(stackzoo): co-op and solo
    }

    @Ignore("SZ-R13: state machine guard")
    @Test
    fun sz_r13_release_while_not_dragging_or_during_settle_is_ignored() {
        // TODO(stackzoo): state machine guard
    }
}
