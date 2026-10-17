package com.gamesuite.audio

import com.gamesuite.audio.MusicProfilesTestSupport.discoverProfiles
import com.gamesuite.audio.MusicProfilesTestSupport.distinctProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Data-validity tests for every [MusicProfile] in [MusicProfiles], discovered reflectively through
 * [MusicProfilesTestSupport] (every public zero-arg `MusicProfiles` getter returning a
 * [MusicProfile], so a profile added by a later game is covered automatically with no test edit).
 * A profile is just numbers, and the synth trusts them completely: a cutoff at or above Nyquist
 * makes the one-pole filter meaningless, a zero voice count divides by zero, a crossfade longer
 * than its chord hold never lets the chord settle, a scale that is not strictly increasing makes
 * "stacked thirds" jump around. Nothing else in the suite would notice a typo like that until
 * someone listened.
 *
 * Aliases (`SOLITAIRE = PUZZLE_FOCUS`, ...) are the same object, so validity is checked once per
 * distinct instance, reported under all of its names.
 */
class MusicProfilesValidityTest {

    /**
     * Guards the alias contract callers rely on: every solo-puzzle game name resolves to the ONE
     * shared PUZZLE_FOCUS pad, SLIDING_PUZZLE to TIC_TAC_TOE's, BREAKOUT to AIR_HOCKEY's (the
     * KDoc'd deliberate exception to "solo game = PUZZLE_FOCUS"). Identity (`===`), not equality,
     * because the point is one preset object; a copy-pasted look-alike would drift apart on the
     * next tuning edit. Direct property references also make renaming/removing an alias a compile
     * error here rather than a silent change. The canonical presets must in turn be distinct
     * objects, and TOWER_DEFENCE is the documented bespoke profile, not an alias of either.
     */
    @Test
    fun `aliases resolve to their canonical profile and canonical profiles are distinct objects`() {
        for (alias in listOf(
            MusicProfiles.SOLITAIRE, MusicProfiles.WORD_TILES, MusicProfiles.WORD_SEARCH,
            MusicProfiles.CROSSWORD, MusicProfiles.HANGMAN, MusicProfiles.MINESWEEPER,
            MusicProfiles.SUDOKU, MusicProfiles.LIGHTS_OUT, MusicProfiles.COLOR_FLOOD
        )) assertSame(MusicProfiles.PUZZLE_FOCUS, alias)
        assertSame(MusicProfiles.TIC_TAC_TOE, MusicProfiles.SLIDING_PUZZLE)
        assertSame(MusicProfiles.AIR_HOCKEY, MusicProfiles.BREAKOUT)

        assertNotSame(MusicProfiles.TOWER_DEFENCE, MusicProfiles.AIR_HOCKEY)
        assertNotSame(MusicProfiles.TOWER_DEFENCE, MusicProfiles.PUZZLE_FOCUS)

        // One canonical check that reflective discovery (used by every other test in this class and
        // in PadSynthSignalQualityTest) sees the shipped instances and has not silently gone stale:
        // at least the 23 named entries known at the time of writing (12 distinct + 11 aliases).
        val byName = discoverProfiles().toMap()
        assertTrue("reflective discovery found only ${byName.size} entries", byName.size >= 23)
        assertSame(MusicProfiles.CHESS, byName.getValue("CHESS"))
        assertSame(byName.getValue("PUZZLE_FOCUS"), byName.getValue("SOLITAIRE"))
    }

    /**
     * Guards against copy-paste profiles: two DIFFERENT objects must never be equal (`MusicProfile`
     * is a data class, so a duplicate of CHESS created by pasting its numbers under a new name
     * compares equal), because two games would then play the identical pad while the code looks
     * like they have distinct identities. Aliases are the same object and correctly exempt. Also
     * checks that each pair differs in something a listener could hear, not only in a field that
     * is irrelevant to the sound.
     */
    @Test
    fun `no two distinct profiles are equal`() {
        val distinct = distinctProfiles()
        for (i in distinct.indices) for (j in i + 1 until distinct.size) {
            val (na, a) = distinct[i]
            val (nb, b) = distinct[j]
            assertNotEquals("$na and $nb are separate objects with identical values", a, b)
            val audibleDifference = a.rootNoteHz != b.rootNoteHz || a.scaleIntervals != b.scaleIntervals ||
                a.chordProgressionDegrees != b.chordProgressionDegrees || a.voiceCount != b.voiceCount ||
                a.chordDurationSeconds != b.chordDurationSeconds || a.baseVolume != b.baseVolume ||
                a.waveformBrightness != b.waveformBrightness || a.lowpassCutoffHz != b.lowpassCutoffHz ||
                a.arpeggioEnabled != b.arpeggioEnabled
            assertTrue("$na and $nb differ only in barely-audible fields", audibleDifference)
        }
    }

    /**
     * Guards the scalar ranges every synth stage silently depends on, one profile at a time, each
     * with its reason. `voiceCount` 1..8: the code divides the mix by it (0 would be Infinity/NaN
     * output), the brief says 2-4 and the pan/chord math is tested up to 8. `baseVolume` in
     * (0, 1] and `baseVolume * (1 + arpeggioVolume) <= 1` so a full-scale sum could never clip
     * (the real ceiling is much lower; the 0.5 cap keeps headroom to coexist with SFX).
     * `breathingDepth` in [0, 1) keeps `1 - depth + depth*sin` positive; the LFO rate must be
     * a slow (0, 1) Hz swell. `lowpassCutoffHz` strictly inside (0, 22050) or the filter
     * coefficient `1 - exp(-2 pi fc / fs)` degenerates, and above the root pitch so the
     * fundamental itself is not filtered away. Brightness (a triangle blend weight) in [0, 1],
     * arpeggio volume in [0, 1] and, when the arpeggio is on, a positive rate whose note lasts at
     * least the 6 ms attack (a shorter note would retrigger before its ramp finished).
     */
    @Test
    fun `every profile has scalar parameters in their valid ranges`() {
        for ((name, p) in distinctProfiles()) {
            assertTrue("$name: voiceCount ${p.voiceCount} outside 1..8", p.voiceCount in 1..8)
            assertTrue("$name: rootNoteHz ${p.rootNoteHz} not positive finite", p.rootNoteHz > 0f && p.rootNoteHz.isFinite())
            assertTrue("$name: baseVolume ${p.baseVolume} outside (0, 1]", p.baseVolume > 0f && p.baseVolume <= 1f)
            assertTrue("$name: arpeggioVolume ${p.arpeggioVolume} outside [0, 1]", p.arpeggioVolume in 0f..1f)
            val ceiling = p.baseVolume * (1f + if (p.arpeggioEnabled) p.arpeggioVolume else 0f)
            assertTrue("$name: worst-case sum $ceiling would clip", ceiling <= 1f)
            assertTrue("$name: worst-case sum $ceiling leaves no headroom for SFX", ceiling <= 0.5f)
            assertTrue("$name: breathingDepth ${p.breathingDepth} outside [0, 1)", p.breathingDepth >= 0f && p.breathingDepth < 1f)
            assertTrue("$name: breathingRateHz ${p.breathingRateHz} outside (0, 1)", p.breathingRateHz > 0f && p.breathingRateHz < 1f)
            assertTrue("$name: waveformBrightness ${p.waveformBrightness} outside [0, 1]", p.waveformBrightness in 0f..1f)
            assertTrue(
                "$name: lowpassCutoffHz ${p.lowpassCutoffHz} not strictly inside (0, Nyquist 22050)",
                p.lowpassCutoffHz > 0f && p.lowpassCutoffHz < 22050f
            )
            assertTrue(
                "$name: lowpass cutoff ${p.lowpassCutoffHz} is below the root pitch ${p.rootNoteHz}",
                p.lowpassCutoffHz >= p.rootNoteHz
            )
            if (p.arpeggioEnabled) {
                assertTrue("$name: arpeggioRateHz ${p.arpeggioRateHz} not positive", p.arpeggioRateHz > 0f)
                val noteSeconds = 1.0 / p.arpeggioRateHz
                assertTrue("$name: arpeggio note ${noteSeconds}s is shorter than the 6 ms attack", noteSeconds >= 0.006)
            }
        }
    }

    /**
     * Guards the timing relationships. The equal-power crossfade must be long enough not to click
     * (>= 0.25 s; the shipped default clamps at 0.6 s), must be strictly shorter than the chord
     * hold with real margin (hold - crossfade >= 1 s and crossfade at most half the hold), or the
     * chord never settles before it is replaced (with `crossfade >= hold` the pad is permanently
     * mid-blend).
     */
    @Test
    fun `chord hold and crossfade durations leave a settled stretch between changes`() {
        for ((name, p) in distinctProfiles()) {
            assertTrue("$name: crossfadeSeconds ${p.crossfadeSeconds} too short to be click-free", p.crossfadeSeconds >= 0.25f)
            assertTrue(
                "$name: chord hold ${p.chordDurationSeconds}s does not exceed crossfade ${p.crossfadeSeconds}s",
                p.chordDurationSeconds > p.crossfadeSeconds
            )
            assertTrue(
                "$name: hold ${p.chordDurationSeconds}s leaves under 1 s after a ${p.crossfadeSeconds}s crossfade",
                p.chordDurationSeconds - p.crossfadeSeconds >= 1f
            )
            assertTrue(
                "$name: crossfade ${p.crossfadeSeconds}s is more than half the hold ${p.chordDurationSeconds}s",
                p.crossfadeSeconds <= 0.5f * p.chordDurationSeconds
            )
        }
    }

    /**
     * Guards the default-crossfade formula documented on the field (`hold * 0.15` clamped to
     * 0.6..4 s, "slow profiles automatically get slower transitions"): the parity test's 27.6 s
     * claim for TIC_TAC_TOE depends on it. Checked at both clamps and in the linear region.
     */
    @Test
    fun `default crossfade is 15 percent of the hold clamped to 0_6 through 4 seconds`() {
        fun crossfadeFor(hold: Float) = MusicProfile(
            rootNoteHz = 100f, scaleIntervals = listOf(0), chordProgressionDegrees = listOf(0),
            chordDurationSeconds = hold, voiceCount = 1, baseVolume = 0.1f
        ).crossfadeSeconds
        assertEquals(0.6f, crossfadeFor(2f), 1e-6f)     // 0.3 -> clamped up
        assertEquals(3.0f, crossfadeFor(20f), 1e-5f)    // linear region
        assertEquals(3.6f, crossfadeFor(24f), 1e-5f)    // TIC_TAC_TOE, the parity test's slowest
        assertEquals(4.0f, crossfadeFor(100f), 1e-6f)   // clamped down
        // The shipped slowest profile really resolves to that default.
        assertEquals(3.6f, MusicProfiles.TIC_TAC_TOE.crossfadeSeconds, 1e-5f)
    }

    /**
     * Guards the musical data: a scale is a strictly increasing list of semitone offsets starting
     * at the root (0) and staying within one octave (0..11; the stacked-thirds walk adds octaves
     * itself, so an entry >= 12 would double-count). Chord progressions must be non-empty (the
     * engine takes `index % size`) and, so a profile ever actually changes chord, hold at least two
     * chords; each degree is a non-negative 0-indexed scale degree below the scale length (a larger
     * degree is a typo for an octave-shifted chord, which the stacked thirds already provide).
     */
    @Test
    fun `scales are strictly increasing within one octave and progressions use valid degrees`() {
        for ((name, p) in distinctProfiles()) {
            val scale = p.scaleIntervals
            assertTrue("$name: scale is empty", scale.isNotEmpty())
            assertEquals("$name: scale must start on the root", 0, scale.first())
            for (i in 1 until scale.size) {
                assertTrue("$name: scale $scale is not strictly increasing at index $i", scale[i] > scale[i - 1])
            }
            for (s in scale) assertTrue("$name: scale entry $s outside 0..11", s in 0..11)

            val degrees = p.chordProgressionDegrees
            assertTrue("$name: chord progression is empty", degrees.isNotEmpty())
            assertTrue("$name: progression $degrees has fewer than 2 chords, so it never changes", degrees.size >= 2)
            for (d in degrees) {
                assertTrue("$name: chord degree $d is negative", d >= 0)
                assertTrue("$name: chord degree $d is not below the scale length ${scale.size}", d < scale.size)
            }
        }
    }

    /**
     * Guards against a profile that would put a chord tone (or its arpeggio octave-up, one octave
     * above the voices it walks) somewhere inaudible or aliasing. Every tone of every chord in the
     * progression is recomputed independently of the synth
     * (`root * 2^((scale[idx] + 12*octave) / 12)` with `scaleStep = degree + 2*voice`, floor
     * division/modulo so negative steps would still wrap correctly) and must lie between 20 Hz
     * (audible) and 11025 Hz (half of Nyquist, leaving room for the triangle wave's harmonics
     * below the folding frequency). The arpeggio plays tone indexes 0, 1, 2, 1 (mod voiceCount) one
     * octave up (x2), so those doubled frequencies get the same limits. Also pins the arithmetic
     * on one hand-computed chord so the helper itself is trusted (CHESS's VI chord is F, A, C).
     */
    @Test
    fun `every chord tone and arpeggio note lies between 20 Hz and half Nyquist`() {
        // Hand-computed reference: CHESS degree 5 over natural minor [0,2,3,5,7,8,10]:
        //   v0: step 5 -> idx 5 (8 st) -> 110 * 2^(8/12) = 174.61 Hz  (F3)
        //   v1: step 7 -> octave 1, idx 0 -> 12 st -> 220 Hz          (A3)
        //   v2: step 9 -> octave 1, idx 2 -> 15 st -> 261.63 Hz       (C4)
        assertEquals(110.0 * 2.0.pow(8 / 12.0), toneHz(MusicProfiles.CHESS, 5, 0), 1e-9)
        assertEquals(220.0, toneHz(MusicProfiles.CHESS, 5, 1), 1e-9)
        assertEquals(110.0 * 2.0.pow(15 / 12.0), toneHz(MusicProfiles.CHESS, 5, 2), 1e-9)
        assertEquals(174.61, toneHz(MusicProfiles.CHESS, 5, 0), 0.01)
        assertEquals(261.63, toneHz(MusicProfiles.CHESS, 5, 2), 0.01)

        val arpToneOrder = intArrayOf(0, 1, 2, 1)
        for ((name, p) in distinctProfiles()) {
            for (degree in p.chordProgressionDegrees) {
                for (v in 0 until p.voiceCount) {
                    val hz = toneHz(p, degree, v)
                    assertTrue("$name: chord degree $degree voice $v tone $hz Hz outside 20..11025", hz in 20.0..11025.0)
                }
                if (p.arpeggioEnabled) {
                    for (t in arpToneOrder) {
                        val hz = 2.0 * toneHz(p, degree, t % p.voiceCount)
                        assertTrue("$name: arpeggio note of chord degree $degree tone $t at $hz Hz outside 20..11025", hz in 20.0..11025.0)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Independent chord-tone math: stacked thirds over the scale, octave-wrapped. */
    private fun toneHz(p: MusicProfile, degree: Int, voice: Int): Double {
        val step = degree + 2 * voice
        val octave = Math.floorDiv(step, p.scaleIntervals.size)
        val idx = Math.floorMod(step, p.scaleIntervals.size)
        return p.rootNoteHz.toDouble() * 2.0.pow((p.scaleIntervals[idx] + 12 * octave) / 12.0)
    }
}
