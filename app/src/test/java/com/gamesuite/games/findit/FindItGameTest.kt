package com.gamesuite.games.findit

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/find-it.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class FindItGameTest {
    @Ignore("FI-G1: GameModule wrapper end to end with a fake context")
    @Test
    fun fi_g1_game_lifecycle_init_start_tap_end_reports_scores() {
        // TODO(findit): GameModule wrapper end to end with a fake context
    }

    @Ignore("FI-G2: house convention for co-op results")
    @Test
    fun fi_g2_leave_session_in_hunt_reports_all_players_as_winners_with_the_team() {
        // TODO(findit): house convention for co-op results
    }

    @Ignore("FI-G3: no crash, no state change")
    @Test
    fun fi_g3_tap_after_match_over_returns_not_playing() {
        // TODO(findit): no crash, no state change
    }

    @Ignore("FI-G4: metadata")
    @Test
    fun fi_g4_game_exposes_id_find_it_players_1_to_4_and_local_modes_only() {
        // TODO(findit): metadata
    }
}
