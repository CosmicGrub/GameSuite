package com.gamesuite.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * [AmbientMusicEngine]'s own generator loop is a continuous [android.media.AudioTrack] producer
 * on a dedicated thread — like [ProceduralSfx], this project has no Robolectric/AudioTrack-
 * capable test harness to exercise it directly. What IS fully testable without any of that is
 * the pure math the stereo mix is built on: [equalPowerPanGains] (the constant-power pan law)
 * and [voicePan] (where each chord voice sits across the stereo field).
 */
class AmbientMusicEngineTest {

    @Test
    fun `equalPowerPanGains is equal on both channels at dead center`() {
        val gains = equalPowerPanGains(0f)
        val expected = sqrt(2.0) / 2.0
        assertEquals(expected, gains.left, 1e-9)
        assertEquals(expected, gains.right, 1e-9)
    }

    @Test
    fun `equalPowerPanGains is fully left at pan -1 and fully right at pan +1`() {
        val hardLeft = equalPowerPanGains(-1f)
        assertEquals(1.0, hardLeft.left, 1e-9)
        assertEquals(0.0, hardLeft.right, 1e-9)

        val hardRight = equalPowerPanGains(1f)
        assertEquals(0.0, hardRight.left, 1e-9)
        assertEquals(1.0, hardRight.right, 1e-9)
    }

    @Test
    fun `equalPowerPanGains keeps constant power across the whole pan range`() {
        // left^2 + right^2 must equal exactly 1.0 for any pan -- this is what "equal-power"
        // means and is what keeps perceived loudness from dipping in the middle the way a
        // naive linear pan (left = 1-t, right = t) audibly does.
        for (pan in listOf(-1f, -0.75f, -0.4f, -0.1f, 0f, 0.1f, 0.4f, 0.75f, 1f)) {
            val gains = equalPowerPanGains(pan)
            val power = gains.left * gains.left + gains.right * gains.right
            assertEquals("pan=$pan must have unit power", 1.0, power, 1e-9)
        }
    }

    @Test
    fun `equalPowerPanGains clamps out-of-range pan instead of extrapolating`() {
        val belowRange = equalPowerPanGains(-5f)
        val atHardLeft = equalPowerPanGains(-1f)
        assertEquals(atHardLeft.left, belowRange.left, 1e-9)
        assertEquals(atHardLeft.right, belowRange.right, 1e-9)

        val aboveRange = equalPowerPanGains(5f)
        val atHardRight = equalPowerPanGains(1f)
        assertEquals(atHardRight.left, aboveRange.left, 1e-9)
        assertEquals(atHardRight.right, aboveRange.right, 1e-9)
    }

    @Test
    fun `voicePan keeps a single voice dead center rather than dividing by zero`() {
        assertEquals(0.0, voicePan(voiceIndex = 0, voiceCount = 1), 1e-9)
        assertEquals(0.0, voicePan(voiceIndex = 0, voiceCount = 0), 1e-9)
    }

    @Test
    fun `voicePan spreads two voices symmetrically to exactly plus and minus spread`() {
        val spread = STEREO_SPREAD
        assertEquals(-spread, voicePan(voiceIndex = 0, voiceCount = 2), 1e-9)
        assertEquals(spread, voicePan(voiceIndex = 1, voiceCount = 2), 1e-9)
    }

    @Test
    fun `voicePan puts the middle of three voices exactly at center`() {
        val spread = STEREO_SPREAD
        assertEquals(-spread, voicePan(voiceIndex = 0, voiceCount = 3), 1e-9)
        assertEquals(0.0, voicePan(voiceIndex = 1, voiceCount = 3), 1e-9)
        assertEquals(spread, voicePan(voiceIndex = 2, voiceCount = 3), 1e-9)
    }

    @Test
    fun `voicePan is evenly spaced, symmetric, and monotonically increasing across four voices`() {
        val spread = STEREO_SPREAD
        val pans = (0 until 4).map { voicePan(voiceIndex = it, voiceCount = 4) }

        assertEquals(-spread, pans.first(), 1e-9)
        assertEquals(spread, pans.last(), 1e-9)

        for (i in 1 until pans.size) {
            assertTrue("voicePan must be non-decreasing by voice index", pans[i] > pans[i - 1])
        }

        // Symmetric around center: voice i and its mirror (count-1-i) are exact opposites.
        for (i in pans.indices) {
            val mirror = pans[pans.size - 1 - i]
            assertEquals(-pans[i], mirror, 1e-9)
        }

        // Evenly spaced -- consecutive gaps are all equal.
        val gaps = (1 until pans.size).map { pans[it] - pans[it - 1] }
        for (i in 1 until gaps.size) {
            assertEquals(gaps[0], gaps[i], 1e-9)
        }
    }

    @Test
    fun `voicePan respects a custom spread argument instead of hardcoding STEREO_SPREAD`() {
        val customSpread = 0.3
        assertEquals(-customSpread, voicePan(voiceIndex = 0, voiceCount = 2, spread = customSpread), 1e-9)
        assertEquals(customSpread, voicePan(voiceIndex = 1, voiceCount = 2, spread = customSpread), 1e-9)
    }

    /**
     * The four-voice test above pins exact endpoint values plus symmetry/monotonicity/even-
     * spacing structurally rather than as hardcoded literals for the interior voices -- the same
     * structural check, applied here to every voice count from 5 through 8 (the real ceiling this
     * app's chords use), closing the gap where only [voicePan never exceeds the requested spread
     * in magnitude] (a much weaker bound-only check) covered that range before.
     */
    @Test
    fun `voicePan is evenly spaced, symmetric, and monotonically increasing for every voice count from five to eight`() {
        val spread = STEREO_SPREAD
        for (voiceCount in 5..8) {
            val pans = (0 until voiceCount).map { voicePan(voiceIndex = it, voiceCount = voiceCount) }

            assertEquals("voiceCount=$voiceCount leftmost voice", -spread, pans.first(), 1e-9)
            assertEquals("voiceCount=$voiceCount rightmost voice", spread, pans.last(), 1e-9)

            for (i in 1 until pans.size) {
                assertTrue(
                    "voiceCount=$voiceCount: voicePan must be strictly increasing by voice index",
                    pans[i] > pans[i - 1]
                )
            }

            // Symmetric around center: voice i and its mirror (count-1-i) are exact opposites.
            for (i in pans.indices) {
                val mirror = pans[pans.size - 1 - i]
                assertEquals("voiceCount=$voiceCount voice $i vs its mirror", -pans[i], mirror, 1e-9)
            }

            // Evenly spaced -- consecutive gaps are all equal.
            val gaps = (1 until pans.size).map { pans[it] - pans[it - 1] }
            for (i in 1 until gaps.size) {
                assertEquals("voiceCount=$voiceCount gap $i vs gap 0", gaps[0], gaps[i], 1e-9)
            }
        }
    }

    @Test
    fun `voicePan never exceeds the requested spread in magnitude`() {
        val spread = STEREO_SPREAD
        for (voiceCount in 1..8) {
            for (voiceIndex in 0 until voiceCount) {
                val pan = voicePan(voiceIndex, voiceCount)
                assertTrue(
                    "voice $voiceIndex of $voiceCount ($pan) must stay within +-$spread",
                    abs(pan) <= spread + 1e-9
                )
            }
        }
    }
}
