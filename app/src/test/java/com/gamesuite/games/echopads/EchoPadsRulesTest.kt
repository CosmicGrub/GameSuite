package com.gamesuite.games.echopads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EchoPadsRulesTest {
    // ---- speed levels and the game speed ----

    @Test fun speedLevelsAreStrictlyOrderedAtNormalGameSpeed() {
        val slow = EchoRules.timing(EchoSpeed.SLOW, 1f, 1, false)
        val normal = EchoRules.timing(EchoSpeed.NORMAL, 1f, 1, false)
        val fast = EchoRules.timing(EchoSpeed.FAST, 1f, 1, false)
        assertTrue(slow.litMs > normal.litMs && normal.litMs > fast.litMs)
        assertTrue(slow.gapMs > normal.gapMs && normal.gapMs > fast.gapMs)
        assertTrue(slow.stepMs > normal.stepMs && normal.stepMs > fast.stepMs)
    }

    @Test fun theGameSpeedSettingScalesEverySpeedLevel() {
        for (s in EchoSpeed.entries) {
            val base = EchoRules.timing(s, 1f, 1, false)
            val slower = EchoRules.timing(s, 2.4f, 1, false)
            val quicker = EchoRules.timing(s, 0.55f, 1, false)
            assertTrue("$s slower", slower.litMs > base.litMs && slower.gapMs > base.gapMs)
            assertTrue("$s quicker", quicker.litMs < base.litMs && quicker.gapMs < base.gapMs)
            assertEquals("$s doubles", base.litMs * 2, EchoRules.timing(s, 2f, 1, false).litMs)
        }
    }

    @Test fun noSettingMakesAPadFlashTooFastToSee() {
        for (s in EchoSpeed.entries) for (scale in listOf(0.001f, 0.25f, Float.NaN, -3f, 0f)) {
            val t = EchoRules.timing(s, scale, 40, false)
            assertTrue(t.litMs >= EchoRules.MIN_LIT_MS)
            assertTrue(t.gapMs >= EchoRules.MIN_GAP_MS)
            assertTrue(t.leadInMs > 0 && t.feedbackMs > 0 && t.missFeedbackMs >= t.feedbackMs)
        }
    }

    @Test fun anAbsurdlySlowSettingIsCapped() {
        val t = EchoRules.timing(EchoSpeed.SLOW, 1000f, 1, false)
        assertEquals(EchoRules.timing(EchoSpeed.SLOW, 4f, 1, false), t)
    }

    @Test fun longPatternsSpeedUpButNeverForgivingOnes() {
        assertEquals(1f, EchoRules.pace(1, false), 0f)
        assertEquals(1f, EchoRules.pace(5, false), 0f)
        assertTrue(EchoRules.pace(10, false) < 1f)
        assertTrue(EchoRules.pace(EchoRules.MAX_LENGTH, false) >= 0.7f)
        assertEquals(0.7f, EchoRules.pace(EchoRules.MAX_LENGTH, false), 0.0001f)
        assertEquals(1f, EchoRules.pace(30, true), 0f)
        val short = EchoRules.timing(EchoSpeed.NORMAL, 1f, 3, false)
        val long = EchoRules.timing(EchoSpeed.NORMAL, 1f, 20, false)
        assertTrue(long.stepMs < short.stepMs)
        assertEquals(short, EchoRules.timing(EchoSpeed.NORMAL, 1f, 20, true))
    }

    @Test fun speedIdsRoundTripAndUnknownMeansNormal() {
        for (s in EchoSpeed.entries) assertEquals(s, EchoSpeed.fromId(s.id))
        assertEquals(EchoSpeed.NORMAL, EchoSpeed.fromId("warp"))
        assertEquals(EchoSpeed.NORMAL, EchoSpeed.fromId(null))
        for (m in EchoMode.entries) assertEquals(m, EchoMode.fromId(m.id))
        assertEquals(null, EchoMode.fromId("chaos"))
    }

    @Test fun seatRangesMatchTheModes() {
        assertEquals(1..1, EchoRules.seatRange(EchoMode.SOLO))
        assertEquals(2..6, EchoRules.seatRange(EchoMode.PASS_AND_PLAY))
        assertEquals(2..2, EchoRules.seatRange(EchoMode.DUEL))
    }

    // ---- the pads ----

    @Test fun everyPadHasItsOwnShapeColourAndPitch() {
        assertEquals(4, EchoPads.COUNT)
        assertEquals(4, EchoPads.colours.toSet().size)
        assertEquals(setOf(EchoPads.CIRCLE, EchoPads.TRIANGLE, EchoPads.SQUARE, EchoPads.STAR), setOf(0, 1, 2, 3))
        assertEquals(4, EchoPads.frequencies.toSet().size)
        assertEquals(EchoPads.frequencies.sorted(), EchoPads.frequencies.toList())
    }

    @Test fun padColoursAreTheColourBlindSafeSet() {
        // Okabe-Ito blue, vermillion, yellow and bluish green.
        assertEquals(listOf(0xFF0072B2L, 0xFFD55E00L, 0xFFF0E442L, 0xFF009E73L), EchoPads.colours.toList())
    }

    @Test fun padPitchesAreFarEnoughApartToHear() {
        val f = EchoPads.frequencies
        for (i in 1 until f.size) assertTrue(f[i] / f[i - 1] > 1.2)
    }

    // ---- the pad grid ----

    @Test fun padsFillATwoByTwoGridInsideTheAreaWithoutOverlap() {
        for ((w, h) in listOf(400f to 400f, 300f to 500f, 700f to 240f)) {
            val rects = (0 until 4).map { EchoPadGrid.rect(it, w, h) }
            for (r in rects) {
                assertTrue(r[0] >= 0f && r[1] >= 0f && r[2] <= w + 0.01f && r[3] <= h + 0.01f)
                assertTrue(r[2] > r[0] && r[3] > r[1])
            }
            for (i in 0 until 4) for (j in i + 1 until 4) {
                val a = rects[i]; val b = rects[j]
                val overlap = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
                assertFalse("pads $i and $j overlap in ${w}x$h", overlap)
            }
            assertTrue(rects[0][0] < rects[1][0] && rects[0][1] < rects[2][1])
        }
    }

    @Test fun aTouchInAPadsMiddleFindsThatPadAndTheGapFindsNone() {
        val w = 360f; val h = 360f
        for (i in 0 until 4) {
            val r = EchoPadGrid.rect(i, w, h)
            assertEquals(i, EchoPadGrid.padAt((r[0] + r[2]) / 2, (r[1] + r[3]) / 2, w, h))
        }
        val gapX = (EchoPadGrid.rect(0, w, h)[2] + EchoPadGrid.rect(1, w, h)[0]) / 2
        assertEquals(-1, EchoPadGrid.padAt(gapX, 50f, w, h))
        assertEquals(-1, EchoPadGrid.padAt(-1f, 10f, w, h))
        assertEquals(-1, EchoPadGrid.padAt(10f, h + 5f, w, h))
    }

    @Test fun everyPadMeetsTheSeventyTwoDpTargetOnTheSmallestBoardTheScreenDraws() {
        // The solo board is a square the size of the smaller free side; a phone in portrait leaves well over 320dp.
        assertTrue(EchoPadGrid.padSide(320f, 320f) >= 72f)
        // A duel half on a small phone: about 300dp wide by 200dp tall after its header.
        assertTrue(EchoPadGrid.padSide(300f, 200f) >= 72f)
        // The size where the 72dp floor is exactly met.
        assertTrue(EchoPadGrid.padSide(160f, 160f) >= 72f)
        assertFalse(EchoPadGrid.padSide(140f, 140f) >= 72f)
    }

    // ---- the tones ----

    private fun risingZeroCrossings(pcm: ShortArray, samples: Int): Int {
        var n = 0
        for (i in 1 until samples) if (pcm[i - 1] < 0 && pcm[i] >= 0) n++
        return n
    }

    @Test fun eachPadToneIsAudibleAndAtItsOwnPitch() {
        var last = 0
        for (pad in 0 until EchoPads.COUNT) {
            val pcm = EchoPadTones.renderPcm(pad)
            assertTrue(pcm.size > 10_000)
            assertTrue("pad $pad is silent", pcm.maxOf { abs(it.toInt()) } > 3_000)
            // The note decays: the last tenth is much quieter than the first tenth.
            val tenth = pcm.size / 10
            assertTrue(pcm.takeLast(tenth).maxOf { abs(it.toInt()) } < pcm.take(tenth).maxOf { abs(it.toInt()) } / 4)
            // Rising zero crossings in 0.1 s are the pitch times 0.1.
            val window = EchoPadTones.SAMPLE_RATE / 10
            val crossings = risingZeroCrossings(pcm, window)
            assertEquals("pad $pad pitch", EchoPads.frequencies[pad] * 0.1, crossings.toDouble(), 1.5)
            assertTrue("pitches must rise with the pad number", crossings > last)
            last = crossings
        }
    }

    @Test fun aToneNeverClipsAndTheSameToneRendersIdentically() {
        for (pad in 0 until EchoPads.COUNT) {
            val a = EchoPadTones.renderPcm(pad)
            assertTrue(a.all { it > Short.MIN_VALUE && it < Short.MAX_VALUE })
            assertTrue(a.contentEquals(EchoPadTones.renderPcm(pad)))
        }
        assertFalse(EchoPadTones.renderPcm(0).contentEquals(EchoPadTones.renderPcm(1)))
    }

    @Test fun aPadNumberOutOfRangeIsClampedNotACrash() {
        assertTrue(EchoPadTones.renderPcm(-4).contentEquals(EchoPadTones.renderPcm(0)))
        assertTrue(EchoPadTones.renderPcm(99).contentEquals(EchoPadTones.renderPcm(3)))
    }

    // ---- the personal best ----

    @Test fun eachSpeedAndEasyModeKeepTheirOwnRecord() {
        val keys = EchoSpeed.entries.flatMap { s -> listOf(EchoPadsBest.key(s, false), EchoPadsBest.key(s, true)) }
        assertEquals(6, keys.toSet().size)
    }

    @Test fun aBestOnlyRisesAndSaysWhenItDid() {
        var table = emptyMap<String, Int>()
        val k = EchoPadsBest.key(EchoSpeed.NORMAL, false)
        var (t, better) = EchoPadsBest.record(table, k, 5)
        assertTrue(better); table = t
        assertEquals(5, table[k])
        EchoPadsBest.record(table, k, 5).let { assertFalse(it.second); assertEquals(table, it.first) }
        EchoPadsBest.record(table, k, 3).let { assertFalse(it.second); assertEquals(table, it.first) }
        EchoPadsBest.record(table, k, 9).let { assertTrue(it.second); assertEquals(9, it.first[k]) }
        // A zero is never a record, and another record is untouched.
        assertFalse(EchoPadsBest.record(emptyMap(), k, 0).second)
        val other = EchoPadsBest.key(EchoSpeed.FAST, true)
        assertEquals(5, EchoPadsBest.record(table, other, 2).first[k])
    }

    @Test fun anAbsurdScoreIsCappedAtTheLongestPattern() {
        val k = EchoPadsBest.key(EchoSpeed.SLOW, false)
        assertEquals(EchoRules.MAX_LENGTH, EchoPadsBest.record(emptyMap(), k, 5_000).first[k])
    }

    @Test fun theBestTableRoundTripsAndACorruptOneIsJustEmpty() {
        val table = mapOf("normal" to 7, "fast+easy" to 3)
        assertEquals(table, EchoPadsBest.decode(EchoPadsBest.encode(table)))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode(null))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode(""))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode("{not json"))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode("[1,2,3]"))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode("{\"normal\":\"seven\"}"))
        // Impossible values are dropped, the good ones kept.
        assertEquals(mapOf("normal" to 5), EchoPadsBest.decode("{\"slow\":-4,\"normal\":5,\"fast\":9999}"))
        assertNotEquals(table, EchoPadsBest.decode("{}"))
    }
}
