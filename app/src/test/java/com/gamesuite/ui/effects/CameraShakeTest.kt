package com.gamesuite.ui.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * [CameraShake] itself wraps a real Compose [androidx.compose.animation.core.Animatable]
 * driven by `tween()` — this project has no Robolectric/Compose-test-rule setup, so its
 * `trigger()`/`Animatable.shakeSteps()` suspend functions aren't exercised here. What IS fully
 * testable without Compose's animation clock is the pure math both of them are built on:
 * [cameraShakeOffsetFor] (the formula [CameraShake.offset] calls internally) and
 * [DEFAULT_SHAKE_STEP_PATTERN]'s own decay shape.
 */
class CameraShakeTest {

    @Test
    fun `cameraShakeOffsetFor is exactly zero at rest and at peak decays to a bounded magnitude`() {
        val atRest = cameraShakeOffsetFor(shake = 0f, magnitudePx = 20f)
        assertEquals(0f, atRest.x, 0f)
        assertEquals(0f, atRest.y, 0f)

        val atPeak = cameraShakeOffsetFor(shake = 1f, magnitudePx = 20f, frequencyX = 47f, frequencyY = 39f)
        assertEquals(20f * sin(47f), atPeak.x, 1e-4f)
        assertEquals(20f * cos(39f), atPeak.y, 1e-4f)
        assertTrue("peak offset must never exceed magnitudePx on either axis", abs(atPeak.x) <= 20f + 1e-3f)
        assertTrue(abs(atPeak.y) <= 20f + 1e-3f)
    }

    @Test
    fun `cameraShakeOffsetFor scales linearly with magnitudePx for a fixed shake value`() {
        val small = cameraShakeOffsetFor(shake = 0.6f, magnitudePx = 10f)
        val big = cameraShakeOffsetFor(shake = 0.6f, magnitudePx = 30f)
        assertEquals(small.x * 3f, big.x, 1e-4f)
        assertEquals(small.y * 3f, big.y, 1e-4f)
    }

    @Test
    fun `cameraShakeOffsetFor uses independent frequencies so the two axes never move in lockstep`() {
        // A shake value where sin and cos of the SAME angle would coincide in sign/zero-crossing
        // pattern if both axes shared one frequency -- confirms frequencyX/frequencyY genuinely
        // decouple the two axes rather than one silently overriding the other.
        val offset = cameraShakeOffsetFor(shake = 0.5f, magnitudePx = 10f, frequencyX = 47f, frequencyY = 39f)
        val ifSameFrequency = cameraShakeOffsetFor(shake = 0.5f, magnitudePx = 10f, frequencyX = 47f, frequencyY = 47f)
        assertTrue(
            "distinct frequencyX/frequencyY must actually change the y axis relative to sharing frequencyX",
            abs(offset.y - ifSameFrequency.y) > 1e-3f
        )
    }

    @Test
    fun `DEFAULT_SHAKE_STEP_PATTERN decays to exactly zero and never grows in magnitude step to step`() {
        assertEquals("a shake pattern must end at rest", 0f, DEFAULT_SHAKE_STEP_PATTERN.last(), 0f)
        for (i in 1 until DEFAULT_SHAKE_STEP_PATTERN.size) {
            val previous = abs(DEFAULT_SHAKE_STEP_PATTERN[i - 1])
            val current = abs(DEFAULT_SHAKE_STEP_PATTERN[i])
            assertTrue(
                "step $i (|${DEFAULT_SHAKE_STEP_PATTERN[i]}|) must not exceed step ${i - 1} (|${DEFAULT_SHAKE_STEP_PATTERN[i - 1]}|)",
                current <= previous
            )
        }
    }
}
