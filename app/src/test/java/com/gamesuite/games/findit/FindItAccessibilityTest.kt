package com.gamesuite.games.findit

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/find-it.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class FindItAccessibilityTest {
    @Ignore("FI-A1: scan order is stable per card")
    @Test
    fun fi_a1_one_switch_scan_order_visits_every_symbol_once_and_wraps() {
        // TODO(findit): scan order is stable per card
    }

    @Ignore("FI-A2: row then column then nearest item")
    @Test
    fun fi_a2_hunt_row_column_scan_reaches_every_scene_cell() {
        // TODO(findit): row then column then nearest item
    }

    @Ignore("FI-A3: relaxed flag effects in one assertion table")
    @Test
    fun fi_a3_relaxed_variant_removes_lockout_clock_and_penalty() {
        // TODO(findit): relaxed flag effects in one assertion table
    }
}
