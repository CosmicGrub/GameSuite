package com.gamesuite.games.findit

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/find-it.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class FindItReplaySaveTest {
    @Ignore("FI-R1: scripted 3 seat race, 14 rounds")
    @Test
    fun fi_r1_replay_of_seed_config_and_tap_log_reproduces_final_scores_exactly() {
        // TODO(findit): scripted 3 seat race, 14 rounds
    }

    @Ignore("FI-R2: scores, hands, pile index, rng position")
    @Test
    fun fi_r2_snapshot_restore_round_trips_state_between_rounds() {
        // TODO(findit): scores, hands, pile index, rng position
    }

    @Ignore("FI-R3: the round is replayed fresh with the same cards")
    @Test
    fun fi_r3_snapshot_mid_round_restores_same_cards_and_restarts_round_clock() {
        // TODO(findit): the round is replayed fresh with the same cards
    }

    @Ignore("FI-R4: garbage, empty, other game json, wrong version")
    @Test
    fun fi_r4_corrupt_or_foreign_snapshot_is_rejected() {
        // TODO(findit): garbage, empty, other game json, wrong version
    }

    @Ignore("FI-R5: card index beyond deck, negative score, seat count mismatch")
    @Test
    fun fi_r5_snapshot_with_out_of_range_indices_or_scores_is_rejected() {
        // TODO(findit): card index beyond deck, negative score, seat count mismatch
    }

    @Ignore("FI-R6: ResumableGame contract")
    @Test
    fun fi_r6_snapshot_is_null_before_start_after_over_and_for_nearby_modes() {
        // TODO(findit): ResumableGame contract
    }
}
