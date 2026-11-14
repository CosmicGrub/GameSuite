package com.gamesuite.games.findit

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/find-it.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class FindItPerformanceTest {
    @Ignore("FI-P1: allocation counter, 10000 advance calls")
    @Test
    fun fi_p1_engine_advance_allocates_nothing_after_warmup() {
        // TODO(findit): allocation counter, 10000 advance calls
    }

    @Ignore("FI-P2: order 7 deck plus 40 item scene, off the main thread")
    @Test
    fun fi_p2_deck_generation_and_scene_generation_finish_under_5ms() {
        // TODO(findit): order 7 deck plus 40 item scene, off the main thread
    }
}
