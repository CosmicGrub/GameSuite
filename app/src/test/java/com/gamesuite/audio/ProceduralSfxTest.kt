package com.gamesuite.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * [ProceduralSfx]'s one-shot playback itself goes through a real [android.media.AudioTrack] --
 * like [AmbientMusicEngine], this project has no Robolectric/AudioTrack-capable test harness to
 * exercise that directly. What IS fully testable without any of that is [panToStereo], the pure
 * mono-to-stereo interleaving step [playPcmOneShot] applies before handing a buffer to
 * [android.media.AudioTrack] -- this file covers exactly that, mirroring
 * [AmbientMusicEngineTest]'s own "test the pure math, not the DSP/audio-API loop" split.
 */
class ProceduralSfxTest {

    @Test
    fun `panToStereo doubles the array length, one interleaved L+R pair per mono sample`() {
        val mono = shortArrayOf(10, 20, 30)
        val stereo = panToStereo(mono, pan = 0f)
        assertEquals(mono.size * 2, stereo.size)
    }

    @Test
    fun `panToStereo on an empty buffer returns an empty buffer, not a crash`() {
        val stereo = panToStereo(ShortArray(0), pan = 0f)
        assertEquals(0, stereo.size)
    }

    @Test
    fun `panToStereo at hard left keeps every sample on the left channel and silences the right`() {
        val mono = shortArrayOf(1000, -500, 32000, 0)
        val stereo = panToStereo(mono, pan = -1f)
        for (i in mono.indices) {
            assertEquals("left channel sample $i", mono[i], stereo[i * 2])
            assertEquals("right channel sample $i must be silent", 0, stereo[i * 2 + 1].toInt())
        }
    }

    @Test
    fun `panToStereo at hard right keeps every sample on the right channel and silences the left`() {
        val mono = shortArrayOf(1000, -500, 32000, 0)
        val stereo = panToStereo(mono, pan = 1f)
        for (i in mono.indices) {
            assertEquals("left channel sample $i must be silent", 0, stereo[i * 2].toInt())
            assertEquals("right channel sample $i", mono[i], stereo[i * 2 + 1])
        }
    }

    @Test
    fun `panToStereo at dead center applies the same equal-power gain to both channels`() {
        // Same law AmbientMusicEngineTest already pins for equalPowerPanGains(0f) -- exercised
        // here through the actual interleaving path, not just the gain function in isolation.
        val centerGain = sqrt(2.0) / 2.0
        val mono = shortArrayOf(1000, -1000)
        val stereo = panToStereo(mono, pan = 0f)
        for (i in mono.indices) {
            val expected = (mono[i].toDouble() * centerGain).toInt().toShort()
            assertEquals("left channel sample $i", expected, stereo[i * 2])
            assertEquals("right channel sample $i", expected, stereo[i * 2 + 1])
        }
    }

    @Test
    fun `panToStereo interleaves in L,R,L,R order, not two separate L-block R-block runs`() {
        // Distinct, easily-distinguished sample values at hard-left/hard-right so a swapped or
        // block-ordered (all-L-then-all-R) implementation would fail this immediately.
        val mono = shortArrayOf(111, 222, 333)
        val leftOnly = panToStereo(mono, pan = -1f)
        assertEquals(shortArrayOf(111, 0, 222, 0, 333, 0).toList(), leftOnly.toList())

        val rightOnly = panToStereo(mono, pan = 1f)
        assertEquals(shortArrayOf(0, 111, 0, 222, 0, 333).toList(), rightOnly.toList())
    }

    @Test
    fun `panToStereo never lets a channel's magnitude exceed the original mono sample`() {
        // The equal-power law's gains are always within [0,1], so panning must never amplify
        // a sample beyond its original magnitude on either channel, at any pan value.
        val mono = shortArrayOf(30000, -30000, 500)
        for (pan in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val stereo = panToStereo(mono, pan)
            for (i in mono.indices) {
                val original = kotlin.math.abs(mono[i].toInt())
                assertTrue("pan=$pan left channel sample $i", kotlin.math.abs(stereo[i * 2].toInt()) <= original)
                assertTrue("pan=$pan right channel sample $i", kotlin.math.abs(stereo[i * 2 + 1].toInt()) <= original)
            }
        }
    }
}
