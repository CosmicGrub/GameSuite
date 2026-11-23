package com.gamesuite.games.stackzoo

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/stack-zoo.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class StackZooPerformanceTest {
    @Ignore("SZ-F1: manual benchmark, ignored in CI: p95 under 0.15 ms per step on the reference phone")
    @Test
    fun sz_f1_step_with_24_bodies_stays_under_budget_benchmark() {
        // TODO(stackzoo): manual benchmark, ignored in CI: p95 under 0.15 ms per step on the reference phone
    }

    @Ignore("SZ-F2: allocation counter over 20000 steps")
    @Test
    fun sz_f2_step_allocates_zero_objects_after_warmup() {
        // TODO(stackzoo): allocation counter over 20000 steps
    }

    @Ignore("SZ-F3: at most one small allocation per frame")
    @Test
    fun sz_f3_world_view_publish_reuses_buffers() {
        // TODO(stackzoo): at most one small allocation per frame
    }
}
