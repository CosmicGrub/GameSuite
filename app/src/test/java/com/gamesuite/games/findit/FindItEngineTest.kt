package com.gamesuite.games.findit

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/find-it.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class FindItEngineTest {
    @Ignore("FI-E1: winner takes the centre card as its own card, draws a new centre")
    @Test
    fun fi_e1_match_race_first_correct_tap_wins_centre_card_and_scores_one() {
        // TODO(findit): winner takes the centre card as its own card, draws a new centre
    }

    @Ignore("FI-E2: locked seat taps are ignored until the clock passes the lock")
    @Test
    fun fi_e2_wrong_tap_locks_seat_for_1500ms_and_relaxed_does_not_lock() {
        // TODO(findit): locked seat taps are ignored until the clock passes the lock
    }

    @Ignore("FI-E3: same atMs resolves by (seat + round) mod seats")
    @Test
    fun fi_e3_simultaneous_taps_resolve_by_timestamp_then_rotated_seat_order() {
        // TODO(findit): same atMs resolves by (seat + round) mod seats
    }

    @Ignore("FI-E4: tiny deck, 4 seats, 36 rounds: the pile is reshuffled from the discards")
    @Test
    fun fi_e4_reshuffle_never_deals_a_centre_card_equal_to_any_held_card() {
        // TODO(findit): tiny deck, 4 seats, 36 rounds: the pile is reshuffled from the discards
    }

    @Ignore("FI-E5: 20 s (30 s little) pulse on every card")
    @Test
    fun fi_e5_stall_help_pulses_the_answer_after_stall_time_and_tap_still_counts() {
        // TODO(findit): 20 s (30 s little) pulse on every card
    }

    @Ignore("FI-E6: 5 s narrow, 9 s pulse; other seats unaffected")
    @Test
    fun fi_e6_hint_levels_narrow_to_three_then_pulse_for_handicap_seat_only() {
        // TODO(findit): 5 s narrow, 9 s pulse; other seats unaffected
    }

    @Ignore("FI-E7: fastest, sharp eyes, collector badges, joint winners")
    @Test
    fun fi_e7_match_over_ranks_winner_ties_shared_and_badges_awarded() {
        // TODO(findit): fastest, sharp eyes, collector badges, joint winners
    }

    @Ignore("FI-E8: targets appear once, decoys at most twice, no overlaps")
    @Test
    fun fi_e8_group_hunt_scene_has_each_target_exactly_once_and_min_separation() {
        // TODO(findit): targets appear once, decoys at most twice, no overlaps
    }

    @Ignore("FI-E9: we did it result after the last scene")
    @Test
    fun fi_e9_group_hunt_all_targets_found_clears_scene_and_all_scenes_win() {
        // TODO(findit): we did it result after the last scene
    }

    @Ignore("FI-E10: try again together, same seed gives same scene")
    @Test
    fun fi_e10_group_hunt_sunset_timeout_offers_retry_of_the_same_scene() {
        // TODO(findit): try again together, same seed gives same scene
    }

    @Ignore("FI-E11: relaxed has no clock and no penalty")
    @Test
    fun fi_e11_solo_dash_counts_cards_applies_two_second_penalty_and_ends_at_zero() {
        // TODO(findit): relaxed has no clock and no penalty
    }

    @Ignore("FI-E12: advance while paused changes nothing")
    @Test
    fun fi_e12_pause_stops_the_clock_and_resume_continues() {
        // TODO(findit): advance while paused changes nothing
    }

    @Ignore("FI-E13: NOT_PLAYING result and no state change")
    @Test
    fun fi_e13_taps_before_go_or_after_over_are_ignored() {
        // TODO(findit): NOT_PLAYING result and no state change
    }

    @Ignore("FI-E14: the last find always goes to a person")
    @Test
    fun fi_e14_helper_never_takes_final_hunt_target_when_a_human_is_present() {
        // TODO(findit): the last find always goes to a person
    }

    @Ignore("FI-E15: negative, too large, and symbol not on the seat card")
    @Test
    fun fi_e15_tap_with_out_of_range_seat_or_symbol_is_rejected() {
        // TODO(findit): negative, too large, and symbol not on the seat card
    }
}
