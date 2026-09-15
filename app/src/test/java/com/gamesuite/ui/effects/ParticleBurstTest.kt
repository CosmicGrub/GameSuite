package com.gamesuite.ui.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ParticleBurstTest {

    @Test
    fun `spawn creates exactly count particles at origin with speed and life within the requested ranges`() {
        val burst = ParticleBurst(random = Random(42))
        val origin = Offset(0.5f, 0.3f)
        burst.spawn(
            origin = origin, count = 12, colors = listOf(Color.Red),
            speedRange = 0.4f..0.6f, lifeRangeSeconds = 0.5f..0.75f, gravity = 1.4f
        )

        val particles = burst.particles.value
        assertEquals(12, particles.size)
        for (p in particles) {
            assertEquals(origin, p.pos)
            assertEquals(p.maxLife, p.life, 0f) // freshly spawned: life == maxLife
            assertTrue("life ${p.maxLife} out of range", p.maxLife in 0.5f..0.75f)
            val speed = kotlin.math.sqrt(p.vel.x * p.vel.x + p.vel.y * p.vel.y)
            assertTrue("speed $speed out of range", speed in 0.4f..0.6f)
            assertEquals(1.4f, p.gravity, 0f)
            assertEquals(Color.Red, p.color)
        }
    }

    @Test
    fun `spawn with count 0 or fewer is a no-op`() {
        val burst = ParticleBurst()
        burst.spawn(Offset.Zero, count = 0, colors = listOf(Color.White))
        assertTrue(burst.particles.value.isEmpty())
        burst.spawn(Offset.Zero, count = -3, colors = listOf(Color.White))
        assertTrue(burst.particles.value.isEmpty())
    }

    @Test
    fun `spawn picks colors only from the supplied list`() {
        val burst = ParticleBurst(random = Random(7))
        val palette = listOf(Color.Red, Color.Green, Color.Blue)
        burst.spawn(Offset.Zero, count = 30, colors = palette)
        assertTrue(burst.particles.value.all { it.color in palette })
        // With 30 draws from 3 colors, expect real variety (not every particle landing on the
        // same color by fluke) -- a meaningful sanity check on the random pick actually varying.
        assertTrue(burst.particles.value.map { it.color }.distinct().size > 1)
    }

    @Test
    fun `tick applies gravity to velocity and integrates position from that same velocity`() {
        val burst = ParticleBurst(random = Random(1))
        burst.spawn(
            origin = Offset(0f, 0f), count = 1, colors = listOf(Color.White),
            speedRange = 0.5f..0.5f, lifeRangeSeconds = 10f..10f, gravity = 2f,
            angleRangeRadians = 0f..0f // angle 0 -> vel = (speed, 0)
        )
        val before = burst.particles.value.single()
        assertEquals(0.5f, before.vel.x, 1e-5f)
        assertEquals(0f, before.vel.y, 1e-5f)

        burst.tick(0.1f)

        val after = burst.particles.value.single()
        // vel.y = old vel.y + gravity*dt = 0 + 2*0.1 = 0.2; vel.x unchanged (no horizontal force).
        assertEquals(0.2f, after.vel.y, 1e-5f)
        assertEquals(0.5f, after.vel.x, 1e-5f)
        // Position integrates from the velocity AFTER gravity is applied this tick.
        assertEquals(before.pos.x + 0.5f * 0.1f, after.pos.x, 1e-5f)
        assertEquals(before.pos.y + 0.2f * 0.1f, after.pos.y, 1e-5f)
        assertEquals(before.life - 0.1f, after.life, 1e-5f)
    }

    @Test
    fun `tick removes a particle exactly when its life reaches zero, not one frame early or late`() {
        val burst = ParticleBurst()
        burst.spawn(Offset.Zero, count = 1, colors = listOf(Color.White), lifeRangeSeconds = 0.2f..0.2f)
        assertEquals(1, burst.particles.value.size)

        burst.tick(0.1f) // life -> 0.1, still alive
        assertEquals(1, burst.particles.value.size)

        burst.tick(0.1f) // life -> 0.0 exactly, dead
        assertTrue(burst.particles.value.isEmpty())
    }

    @Test
    fun `tick is a no-op with no particles or a non-positive dt`() {
        val burst = ParticleBurst()
        burst.tick(0.05f) // nothing spawned yet -- must not throw
        assertTrue(burst.particles.value.isEmpty())

        burst.spawn(Offset.Zero, count = 1, colors = listOf(Color.White), lifeRangeSeconds = 1f..1f)
        val before = burst.particles.value
        burst.tick(0f)
        assertEquals(before, burst.particles.value)
        burst.tick(-1f)
        assertEquals(before, burst.particles.value)
    }

    @Test
    fun `clear immediately discards every live particle`() {
        val burst = ParticleBurst()
        burst.spawn(Offset.Zero, count = 5, colors = listOf(Color.White))
        assertEquals(5, burst.particles.value.size)
        burst.clear()
        assertTrue(burst.particles.value.isEmpty())
    }

    @Test
    fun `spawn requires at least one color`() {
        val burst = ParticleBurst()
        try {
            burst.spawn(Offset.Zero, count = 1, colors = emptyList())
            org.junit.Assert.fail("expected an IllegalArgumentException for an empty color list")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `BurstParticle lifeFraction is 1 at spawn, decays toward 0, and is coerced into the 0-1 range`() {
        val fresh = BurstParticle(Offset.Zero, Offset.Zero, life = 1f, maxLife = 1f, gravity = 0f, color = Color.White)
        assertEquals(1f, fresh.lifeFraction, 0f)

        val half = fresh.copy(life = 0.5f)
        assertEquals(0.5f, half.lifeFraction, 0f)

        val overMax = fresh.copy(life = 1.5f) // shouldn't happen in practice, but must stay in range
        assertEquals(1f, overMax.lifeFraction, 0f)

        val negative = fresh.copy(life = -0.2f)
        assertEquals(0f, negative.lifeFraction, 0f)
    }

    @Test
    fun `two independent bursts do not interfere with each other's state`() {
        val a = ParticleBurst(random = Random(1))
        val b = ParticleBurst(random = Random(2))
        a.spawn(Offset.Zero, count = 3, colors = listOf(Color.Red))
        assertEquals(3, a.particles.value.size)
        assertTrue(b.particles.value.isEmpty())
        assertNotEquals(a.particles.value, b.particles.value)
    }
}
