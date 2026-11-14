package com.gamesuite.games.stackzoo

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/stack-zoo.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class StackZooSaveTest {
    @Ignore("SZ-V1: poses, velocities, bag, hands, turn")
    @Test
    fun sz_v1_snapshot_restore_round_trips_all_body_state_at_turn_boundary() {
        // TODO(stackzoo): poses, velocities, bag, hands, turn
    }

    @Ignore("SZ-V2: ResumableGame contract")
    @Test
    fun sz_v2_snapshot_is_null_while_dragging_or_match_over_or_before_start() {
        // TODO(stackzoo): ResumableGame contract
    }

    @Ignore("SZ-V3: NaN, Infinity, 1e9, negative counts")
    @Test
    fun sz_v3_corrupt_snapshot_with_nan_infinity_or_out_of_range_values_is_rejected() {
        // TODO(stackzoo): NaN, Infinity, 1e9, negative counts
    }

    @Ignore("SZ-V4: validation")
    @Test
    fun sz_v4_snapshot_with_unknown_animal_id_or_duplicate_piece_id_is_rejected() {
        // TODO(stackzoo): validation
    }
}
