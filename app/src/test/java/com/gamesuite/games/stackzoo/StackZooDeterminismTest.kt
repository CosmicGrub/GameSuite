package com.gamesuite.games.stackzoo

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/stack-zoo.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class StackZooDeterminismTest {
    @Ignore("SZ-P1: compare raw Double bits of every body")
    @Test
    fun sz_p1_same_seed_and_release_log_gives_bit_identical_body_poses_after_20000_steps() {
        // TODO(stackzoo): compare raw Double bits of every body
    }

    @Ignore("SZ-P2: golden FNV-1a hash, change requires a deliberate update")
    @Test
    fun sz_p2_state_hash_matches_golden_value_for_scripted_ten_piece_tower() {
        // TODO(stackzoo): golden FNV-1a hash, change requires a deliberate update
    }

    @Ignore("SZ-P3: chunking does not matter")
    @Test
    fun sz_p3_advance_in_one_call_equals_advance_in_single_steps() {
        // TODO(stackzoo): chunking does not matter
    }

    @Ignore("SZ-P4: text scan: no System.nanoTime, Random.Default, Math.fma, Math.sin or cos")
    @Test
    fun sz_p4_engine_sources_use_no_wall_clock_default_random_or_fused_math() {
        // TODO(stackzoo): text scan: no System.nanoTime, Random.Default, Math.fma, Math.sin or cos
    }

    @Ignore("SZ-P5: Int x and angle survive encode and decode")
    @Test
    fun sz_p5_quantised_release_inputs_round_trip_through_json_exactly() {
        // TODO(stackzoo): Int x and angle survive encode and decode
    }

    @Ignore("SZ-P6: warm start cache is cleared on restore, poses agree within 1e-6")
    @Test
    fun sz_p6_restore_then_replay_matches_uninterrupted_run_within_tolerance() {
        // TODO(stackzoo): warm start cache is cleared on restore, poses agree within 1e-6
    }

    @Ignore("SZ-P7: bodies and contacts use arrays sorted by id")
    @Test
    fun sz_p7_step_order_independent_of_hash_map_iteration() {
        // TODO(stackzoo): bodies and contacts use arrays sorted by id
    }
}
