package com.gamesuite.games.stackzoo

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/stack-zoo.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class StackZooGameTest {
    @Ignore("SZ-G1: GameModule wrapper with a fake context")
    @Test
    fun sz_g1_game_lifecycle_init_start_release_end_reports_scores() {
        // TODO(stackzoo): GameModule wrapper with a fake context
    }

    @Ignore("SZ-G2: house convention")
    @Test
    fun sz_g2_leave_session_in_coop_reports_all_players_as_winners_with_the_team() {
        // TODO(stackzoo): house convention
    }

    @Ignore("SZ-G3: no crash")
    @Test
    fun sz_g3_release_after_match_over_returns_not_playing() {
        // TODO(stackzoo): no crash
    }

    @Ignore("SZ-G4: metadata")
    @Test
    fun sz_g4_game_exposes_id_stack_zoo_players_1_to_4_and_local_modes_only() {
        // TODO(stackzoo): metadata
    }
}
