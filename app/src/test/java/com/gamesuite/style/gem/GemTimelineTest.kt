package com.gamesuite.style.gem

import com.gamesuite.games.gemcascade.GemAction
import com.gamesuite.games.gemcascade.GemBoard
import com.gamesuite.games.gemcascade.GemEngine
import com.gamesuite.games.gemcascade.GemEvent
import com.gamesuite.games.gemcascade.GemEventApplier
import com.gamesuite.games.gemcascade.GemMode
import com.gamesuite.games.gemcascade.Pos
import com.gamesuite.style.ArtTier
import com.gamesuite.style.GameStyleSupport
import com.gamesuite.style.MotionLevel
import com.gamesuite.style.StyleContext
import com.gamesuite.style.StylePrefs
import com.gamesuite.style.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemTimelineTest {
    /** Plays [moves] legal moves from a seed and returns each move's events. */
    private fun play(seed: Long, mode: GemMode, moves: Int): List<List<GemEvent>> {
        val e = GemEngine.start(mode, 8, 8, seed, null)
        val out = ArrayList<List<GemEvent>>()
        repeat(moves) {
            val a: GemAction = e.legalActions().firstOrNull() ?: return out
            val r = e.apply(a)
            if (r.accepted) out += r.events
        }
        return out
    }

    private val seeds = listOf(1L, 2L, 7L, 42L, 99L)

    @Test fun timelineIsDeterministic() {
        for (seed in seeds) {
            val a = play(seed, GemMode.ZEN, 12).map { GemTimeline.build(it, MotionSpec.of(MotionLevel.STANDARD, false)) }
            val b = play(seed, GemMode.ZEN, 12).map { GemTimeline.build(it, MotionSpec.of(MotionLevel.STANDARD, false)) }
            assertEquals(a, b)
        }
    }

    @Test fun timelineDoesNotDependOnTheArtStyle() {
        // The timeline takes no style input; the resolved styles differ but the steps are identical.
        val caps = com.gamesuite.style.DeviceCaps.CAPABLE
        val atlas = setOf(com.gamesuite.style.ASSET_GEM_ATLAS)
        val steps = ArrayList<List<GemStep>>()
        for (tier in ArtTier.values()) {
            val style = StyleResolver.resolve(StylePrefs(tier), GameStyleSupport.GEM_CASCADE, StyleContext(caps, assetsReady = atlas))
            val spec = MotionSpec.of(style.motion, style.reducedMotion)
            steps += play(5L, GemMode.CLASSIC, 10).flatMap { GemTimeline.build(it, spec) }
        }
        assertEquals(steps[0], steps[1]); assertEquals(steps[1], steps[2])
    }

    @Test fun everyBoardChangingEventIsCoveredByExactlyOneStep() {
        for (seed in seeds) for (events in play(seed, GemMode.ZEN, 10)) {
            val steps = GemTimeline.build(events, MotionSpec.of(MotionLevel.STANDARD, false))
            val covered = BooleanArray(events.size)
            for (s in steps) for (i in s.firstIndex until s.endIndex) { assertFalse("event $i covered twice", covered[i]); covered[i] = true }
            for ((i, e) in events.withIndex()) {
                val changesBoard = e is GemEvent.Swap || e is GemEvent.Clear || e is GemEvent.Fall || e is GemEvent.Spawn ||
                    e is GemEvent.SpecialCreated || e is GemEvent.ObstacleDamaged || e is GemEvent.NoMovesShuffle || e is GemEvent.PieceMoved
                if (changesBoard) assertTrue("event $e not covered", covered[i])
            }
        }
    }

    /** Replaying the steps' events in order on a copy of the board reaches the engine's board (the player's playback relies on this). */
    @Test fun playbackOrderReproducesTheEnginesBoard() {
        for (seed in seeds) {
            val e = GemEngine.start(GemMode.ZEN, 8, 8, seed, null)
            repeat(8) {
                val a = e.legalActions().firstOrNull() ?: return@repeat
                val before = e.board.copy()
                val r = e.apply(a)
                val b: GemBoard = before.copy()
                for (s in GemTimeline.build(r.events, MotionSpec.of(MotionLevel.STANDARD, false))) {
                    for (i in s.firstIndex until s.endIndex) GemEventApplier.apply(b, r.events[i])
                }
                assertTrue("seed $seed", b.sameAs(e.board))
            }
        }
    }

    @Test fun reducedMotionIsInstantWithNoBursts() {
        for (events in play(3L, GemMode.ZEN, 10)) {
            val steps = GemTimeline.build(events, MotionSpec.of(MotionLevel.STANDARD, true))
            assertEquals(0L, GemTimeline.totalMs(steps))
            assertTrue(steps.all { it.burst == BurstKind.NONE })
        }
    }

    @Test fun calmIsShorterThanStandardAndStandardHasBursts() {
        val all = play(11L, GemMode.ZEN, 12)
        val std = all.sumOf { GemTimeline.totalMs(GemTimeline.build(it, MotionSpec.of(MotionLevel.STANDARD, false))) }
        val calm = all.sumOf { GemTimeline.totalMs(GemTimeline.build(it, MotionSpec.of(MotionLevel.CALM, false))) }
        assertTrue(calm in 1 until std)
        assertTrue(all.flatMap { GemTimeline.build(it, MotionSpec.of(MotionLevel.STANDARD, false)) }.any { it.burst == BurstKind.CLEAR })
    }

    @Test fun rejectedSwapIsSwapThenSwapBack() {
        val ev = listOf(GemEvent.Swap(Pos(0, 0), Pos(0, 1)), GemEvent.SwapBack(Pos(0, 0), Pos(0, 1)))
        val steps = GemTimeline.build(ev, MotionSpec.of(MotionLevel.STANDARD, false))
        assertEquals(listOf(StepKind.SWAP, StepKind.SWAP_BACK), steps.map { it.kind })
        assertEquals(GemSfxCue.INVALID, steps[1].sfx)
    }

    @Test fun chainStepCarriesTheGlowLevelAndBigChainsBurst() {
        val ev = listOf(GemEvent.ChainStep(2, 2), GemEvent.ChainStep(3, 3))
        val steps = GemTimeline.build(ev, MotionSpec.of(MotionLevel.STANDARD, false))
        assertEquals(listOf(2, 3), steps.map { it.chain })
        assertEquals(BurstKind.NONE, steps[0].burst); assertEquals(BurstKind.CHAIN, steps[1].burst)
        assertEquals(GemHapticCue.ESCALATING, steps[1].haptic)
    }

    // ---- particles -------------------------------------------------------------------------------------------
    @Test fun particlesAreDeterministicAndBounded() {
        for (i in 0 until 50) {
            val a = GemParticles.state(123, i, 0.4f, 1f); val b = GemParticles.state(123, i, 0.4f, 1f)
            assertEquals(a, b)
            assertTrue(a.alpha in 0f..1f); assertTrue(Math.abs(a.dx) <= 1.01f); assertTrue(a.size > 0f)
        }
        assertEquals(0f, GemParticles.state(1, 1, 1f, 1f).alpha, 0f)
        assertTrue(GemParticles.state(1, 1, 0f, 1f).dx == 0f)
    }

    @Test fun burstPlanNeverExceedsTheBudget() {
        val cells = (0 until 20).map { Pos(it / 8, it % 8) }
        for (budget in listOf(0, 1, 5, 20, 40, 150, 1000)) for (n in listOf(1, 3, 20)) {
            val plan = GemParticles.plan(cells.take(n), budget)
            assertTrue(plan.sumOf { it.second } <= budget)
            if (budget == 0) assertTrue(plan.isEmpty())
            if (budget > 0) assertTrue(plan.isNotEmpty())
        }
    }

    @Test fun particleShapeFollowsTheTier() {
        assertEquals(ParticleShape.SQUARE, particleShapeFor(ArtTier.FLAT))
        assertEquals(ParticleShape.BLOB, particleShapeFor(ArtTier.SOFT_25D))
        assertEquals(ParticleShape.SHARD, particleShapeFor(ArtTier.FULL_3D))
    }
}
