package com.gamesuite.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Checks the ambient pad against the MUSICAL SPEC, not against its own twin.
 *
 * [RustPadSynthCorrectnessTest] proves Rust == Kotlin sample for sample, so a bug shared by both
 * (chord-tone stacking, progression order, chord-change timing, pan direction/law, arpeggio
 * octave) is invisible to it. Every test here renders BOTH [PadSynthState] and the real Rust
 * `PadSynth` (through one shared helper, [forEachImpl], so a Rust-only regression is caught by the
 * spec and not merely by diffing) and inspects the signal's spectrum with a Hann-windowed
 * single-frequency DFT ([ampAt]).
 *
 * Expected frequencies are derived here from music theory (`scaleStep = degree + 2 * voice`,
 * indexing a scale repeated one octave higher each time round, `f = root * 2^(semitone / 12)`) --
 * never via an engine helper -- and a self-check ([oracle matches hand computed literals]) pins the
 * oracle itself to hand-computed numbers. Analysis windows are chosen inside steady holds
 * (>= 0.35 s startup fade, clear of crossfades), and every threshold leaves a wide margin below
 * what the real signal measures (see [K_ON_OVER_OFF]) while a one-semitone pitch error would put
 * an off-grid tone at full level.
 *
 * Needs the host Rust library (run with `GW_HOST=1`, like [RustPadSynthCorrectnessTest]).
 *
 * Ownership of claims (so the suites do not silently triple the maintenance surface):
 *  - `PadSynthSignalQualityTest` (a1) owns signal quality -- click-freeness, boundedness, profile
 *    validity and the general DSP-property checks on the Kotlin engine.
 *  - THIS suite owns the musical spec -- chord pitches, chord-change timing, progression order,
 *    stereo placement/pan law, arpeggio pitch/order/centring and the absolute gain chain -- and
 *    checks each against BOTH engines, plus the production translation layer
 *    ([RustPadSynth.render]/[RustPadSynth.renderFadeOut]) against the Kotlin twin.
 *  - `RustPadSynthCorrectnessTest` owns Rust == Kotlin parity on every shipped profile.
 * Where a Kotlin-only DFT test here overlaps an a1 one (pan ratio, crossfade curve), it is kept
 * deliberately: it runs through [forEachImpl] so the same spec assertion also guards the Rust
 * engine, which the a1 tests do not see.
 */

private const val SR = 44100

/** Long window (~1.49 s): finest frequency resolution, used for steady-state pitch/pan/level. */
private const val LONG_N = 65536

/** Short window (~0.74 s): used where the hold or crossfade is short. */
private const val SHORT_N = 32768

/** Required dB-ish separation (linear amplitude ratio) between the weakest expected chord tone and
 *  the strongest non-chord semitone in the analysis band. Real signal measures far above this
 *  (see the calibration in each test's failure message); a wrong-by-one-semitone pitch set gives
 *  a ratio around or below 1. */
private const val K_ON_OVER_OFF = 40.0

private enum class Impl { KOTLIN, RUST }

private class Stereo(val left: FloatArray, val right: FloatArray)

/** Interleaved stereo doubles straight from the chosen implementation's pre-quantization seam. */
private fun renderRaw(impl: Impl, profile: MusicProfile, frames: Int): DoubleArray = when (impl) {
    Impl.KOTLIN -> PadSynthState(profile).renderF64(frames)
    Impl.RUST -> {
        val synth = com.gamesuite.audio.rust.PadSynth(profile.toFfi())
        try {
            val out = DoubleArray(frames * 2)
            var done = 0
            while (done < frames) {
                val n = min(22050, frames - done)
                val chunk = synth.renderF64(n)
                for (i in chunk.indices) out[done * 2 + i] = chunk[i]
                done += n
            }
            out
        } finally {
            synth.close()
        }
    }
}

private val stereoCache = HashMap<Triple<Impl, MusicProfile, Int>, Stereo>()

/** Rendered once per (implementation, profile, length) and shared across tests (floats keep the
 *  cache small; single-precision is ~1e-7 relative, far below every threshold here). */
private fun stereo(impl: Impl, profile: MusicProfile, frames: Int): Stereo =
    synchronized(stereoCache) {
        stereoCache.getOrPut(Triple(impl, profile, frames)) {
            val raw = renderRaw(impl, profile, frames)
            Stereo(
                FloatArray(frames) { raw[it * 2].toFloat() },
                FloatArray(frames) { raw[it * 2 + 1].toFloat() }
            )
        }
    }

/** Runs [body] once per implementation with a label suitable for assertion messages. */
private fun forEachImpl(body: (impl: Impl, tag: (String) -> String) -> Unit) {
    for (impl in Impl.values()) body(impl) { msg -> "[$impl] $msg" }
}

// ---------------------------------------------------------------------------------------------
// Signal analysis
// ---------------------------------------------------------------------------------------------

private val hannCache = HashMap<Int, DoubleArray>()

private fun hann(n: Int): DoubleArray = synchronized(hannCache) {
    hannCache.getOrPut(n) { DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) } }
}

/** Amplitude of the sinusoid at [hz] in `x[start, start+n)`, Hann-windowed (an on-bin pure tone of
 *  amplitude A returns A). Rotating-phasor single-bin DFT. */
private fun ampAt(x: FloatArray, start: Int, n: Int, hz: Double): Double {
    val w = hann(n)
    val d = 2.0 * PI * hz / SR
    val cd = cos(d)
    val sd = sin(d)
    var c = 1.0
    var s = 0.0
    var re = 0.0
    var im = 0.0
    var wsum = 0.0
    for (i in 0 until n) {
        val v = x[start + i] * w[i]
        re += v * c
        im += v * s
        wsum += w[i]
        val nc = c * cd - s * sd
        s = s * cd + c * sd
        c = nc
    }
    return 2.0 * hypot(re, im) / wsum
}

/** Same as [ampAt] on a raw double array (used for the un-cached, double-precision arpeggio work). */
private fun ampAt(x: DoubleArray, start: Int, n: Int, hz: Double): Double =
    ampAt(FloatArray(n) { x[start + it].toFloat() }, 0, n, hz)

/** Total (L+R power) amplitude of the tone at [hz]: pan-independent for an equal-power pan. */
private fun bothAmp(sig: Stereo, start: Int, n: Int, hz: Double): Double =
    hypot(ampAt(sig.left, start, n, hz), ampAt(sig.right, start, n, hz))

// ---------------------------------------------------------------------------------------------
// Music-theory oracle (independent of the engine's own helpers)
// ---------------------------------------------------------------------------------------------

private class Spec(val p: MusicProfile) {
    val root: Double = p.rootNoteHz.toDouble()

    /** Scale-degree of the chord at progression position [chord] (the progression loops). */
    fun degree(chord: Int): Int = p.chordProgressionDegrees[chord % p.chordProgressionDegrees.size]

    /** Semitone offsets above the root of each voice of chord [chord]: the scale repeated one
     *  octave up per repeat, indexed by `degree + 2 * voice` (stacked "thirds"). */
    fun semis(chord: Int): List<Int> {
        val extended = (0 until 8).flatMap { o -> p.scaleIntervals.map { it + 12 * o } }
        return (0 until p.voiceCount).map { extended[degree(chord) + 2 * it] }
    }

    fun hz(semitones: Int): Double = root * 2.0.pow(semitones / 12.0)
    fun hz(semitones: Double): Double = root * 2.0.pow(semitones / 12.0)
}

/** Fundamental amplitude of `sine*(1-b) + triangle*b`: the phase-0 triangle is in quadrature with
 *  the sine, so the fundamentals add as vectors; the triangle fundamental is 8/pi^2. */
private fun waveFundamental(brightness: Double): Double = hypot(1.0 - brightness, brightness * 8.0 / (PI * PI))

/** |H| of the engine's one-pole low-pass `y += c (x - y)`, `c = 1 - exp(-2 pi fc / fs)`. */
private fun onePoleGain(cutoffHz: Double, hz: Double): Double {
    val c = 1.0 - exp(-2.0 * PI * cutoffHz / SR)
    val w = 2.0 * PI * hz / SR
    val re = 1.0 - (1.0 - c) * cos(w)
    val im = (1.0 - c) * sin(w)
    return c / sqrt(re * re + im * im)
}

/** Expected L/R POWER ratio for pan [p] under an equal-power law: cos^2(theta)/sin^2(theta),
 *  theta = (p + 1) * pi / 4. */
private fun expectedLrPowerRatio(p: Double): Double {
    val theta = (p + 1.0) * PI / 4.0
    return cos(theta).pow(2) / sin(theta).pow(2)
}

/** Pan of voice [v] of [n] voices for a 0.6 spread: evenly spaced -0.6..+0.6 (center for 1). */
private fun expectedPan(v: Int, n: Int): Double = if (n <= 1) 0.0 else (v.toDouble() / (n - 1) * 2.0 - 1.0) * 0.6

private val NAMED = listOf(
    "CHESS" to MusicProfiles.CHESS,
    "CHECKERS" to MusicProfiles.CHECKERS,
    "MANCALA" to MusicProfiles.MANCALA,
    "UNO" to MusicProfiles.UNO,
    "AIR_HOCKEY" to MusicProfiles.AIR_HOCKEY,
    "PUZZLE_FOCUS" to MusicProfiles.PUZZLE_FOCUS,
    "TOWER_DEFENCE" to MusicProfiles.TOWER_DEFENCE
)

private val NON_ARP = NAMED.filter { !it.second.arpeggioEnabled }

/** The shipped cutoffs (3-4 kHz) sit decades above the chord tones, so the low-pass is ~unity
 *  there; these darker variants pull the cutoff down among the tones/harmonics so the filter's
 *  effect is actually measurable (|H| ~ 0.5-0.8). */
private val NON_ARP_WITH_DARK = NON_ARP + listOf(
    "CHESS_DARK" to MusicProfiles.CHESS.copy(lowpassCutoffHz = 150f),
    "PUZZLE_DARK" to MusicProfiles.PUZZLE_FOCUS.copy(lowpassCutoffHz = 250f)
)

private class Windows(p: MusicProfile) {
    val hold = (p.chordDurationSeconds.toDouble() * SR).toInt()
    val xfade = (p.crossfadeSeconds.toDouble() * SR).toInt()
    val margin = (0.05 * hold).toInt()
    val beforeStart = hold - margin - SHORT_N
    val afterStart = hold + xfade + margin
    val midStart get() = hold + xfade / 2

    /** Enough frames for every window this test class reads for this profile. */
    val frames = max(afterStart + SHORT_N, hold + xfade / 2 + SHORT_N / 2) + 4000
}

/** One steady chord's on-grid measurements: amplitude at each expected tone vs. at every other
 *  semitone of the surrounding band (which stops short of the arpeggio octave and the 3rd
 *  harmonic, so nothing legitimate lives there). */
private class ChordReport(val on: Map<Int, Double>, val off: Map<Int, Double>) {
    val minOn get() = on.values.min()
    val maxOff get() = off.values.maxOrNull() ?: 0.0
    override fun toString(): String =
        "on=${on.mapValues { "%.5f".format(it.value) }} offMax=${"%.6f".format(maxOff)} (ratio ${"%.0f".format(minOn / max(maxOff, 1e-12))})"
}

private fun measureChord(sig: Stereo, start: Int, n: Int, spec: Spec, semis: List<Int>): ChordReport {
    val lo = semis.min() - 3
    val hi = min(semis.max() + 2, semis.min() + 11) // < +12: the arpeggio's octave-up tones live above
    val on = HashMap<Int, Double>()
    val off = HashMap<Int, Double>()
    for (k in lo..hi) {
        val a = bothAmp(sig, start, n, spec.hz(k))
        if (k in semis) on[k] = a else off[k] = a
    }
    return ChordReport(on, off)
}

class PadSynthSpectralBehaviorTest {

    // -------------------------------------------------------------------------------------
    // Oracle self-check
    // -------------------------------------------------------------------------------------

    /**
     * Pins the test's own theory oracle to hand-computed numbers so a mistake shared by the oracle
     * and the engine cannot cancel out: CHESS chord 1 = A2/C3/E3 = 110/130.81/164.81 Hz, chord 2
     * (degree 5) = F3/A3/C4 = 174.61/220/261.63 Hz; AIR_HOCKEY chord 1 = E3/G#3 (root + major
     * third), chord 2 (degree 4) = B3/D#4; pentatonic MANCALA chord 2 wraps the scale octave.
     */
    @Test
    fun `oracle matches hand computed literals`() {
        val chess = Spec(MusicProfiles.CHESS)
        assertEquals(listOf(0, 3, 7), chess.semis(0))
        assertEquals(listOf(8, 12, 15), chess.semis(1))
        val c1 = chess.semis(0).map { chess.hz(it) }
        val c2 = chess.semis(1).map { chess.hz(it) }
        listOf(110.0, 130.8128, 164.8138).forEachIndexed { i, hz -> assertEquals(hz, c1[i], 1e-3) }
        listOf(174.6141, 220.0, 261.6256).forEachIndexed { i, hz -> assertEquals(hz, c2[i], 1e-3) }

        val hockey = Spec(MusicProfiles.AIR_HOCKEY)
        assertEquals(listOf(0, 4), hockey.semis(0))
        assertEquals(listOf(7, 11), hockey.semis(1))
        assertEquals(listOf(0, 4), hockey.semis(2)) // the progression loops

        val mancala = Spec(MusicProfiles.MANCALA)
        assertEquals(listOf(0, 4, 9), mancala.semis(0))
        assertEquals(listOf(4, 9, 14), mancala.semis(1)) // degree 2: steps 2,4,6 -> 4, 9, 0+12+2
        assertEquals(listOf(7, 12, 16), mancala.semis(2)) // degree 3
        assertEquals(9.4721, expectedLrPowerRatio(-0.6), 1e-3)
        assertEquals(1.0, expectedLrPowerRatio(0.0), 1e-12)
        assertEquals(1.0 / 9.4721, expectedLrPowerRatio(0.6), 1e-4)
    }

    // -------------------------------------------------------------------------------------
    // P1: chord pitches
    // -------------------------------------------------------------------------------------

    /**
     * During the first hold the spectrum contains exactly the stacked-thirds chord tones. Guards
     * the tone-stacking rule (`degree + 2 * voice`: a step of 3 or 1 puts energy on the wrong
     * semitones), the root/scale wiring, and octave wrapping for pentatonic scales -- for both
     * implementations. Two-sided: each expected tone must exceed EVERY other semitone in the band
     * (including each expected tone's own semitone neighbours) by [K_ON_OVER_OFF], so a pitch set
     * that is wrong by even one semitone cannot pass.
     */
    @Test
    fun `first chord sounds exactly the stacked thirds tones and nothing else in band`() {
        for ((name, profile) in NAMED) {
            val spec = Spec(profile)
            val semis = spec.semis(0)
            forEachImpl { impl, tag ->
                val sig = stereo(impl, profile, SR + LONG_N + 4000)
                val report = measureChord(sig, SR, LONG_N, spec, semis)
                assertTrue(
                    tag("$name chord 1 $semis: expected tones must each beat every other semitone by ${K_ON_OVER_OFF}x -- $report"),
                    report.minOn >= K_ON_OVER_OFF * report.maxOff
                )
                for (k in semis) {
                    for (neighbour in listOf(k - 1, k + 1)) {
                        if (neighbour in semis) continue
                        val nb = bothAmp(sig, SR, LONG_N, spec.hz(neighbour))
                        assertTrue(
                            tag("$name: tone at semitone $k must dwarf its semitone neighbour $neighbour (${report.on[k]} vs $nb)"),
                            report.on.getValue(k) >= K_ON_OVER_OFF * nb
                        )
                    }
                }
            }
        }
    }

    /**
     * Absolute level of every chord tone: `baseVolume / voices * |fundamental of sine+triangle| *
     * |lowpass response| * (Hann-weighted breathing)` -- checked for the profiles without an
     * arpeggio layer. Guards the voice normalisation, base-volume use, waveform blend and low-pass
     * cutoff, and (through the equal-power pan) that the L+R power of a voice does not depend on
     * where it sits in the stereo field.
     */
    @Test
    fun `tone level equals volume times voice share times waveform fundamental times filter response`() {
        for ((name, profile) in NON_ARP_WITH_DARK) {
            val spec = Spec(profile)
            val n = LONG_N
            val start = SR
            val w = hann(n)
            // Breathing is sampled one step ahead of the frame counter in the engine.
            var wsum = 0.0
            var breathSum = 0.0
            for (i in 0 until n) {
                val t = (start + i + 1).toDouble() / SR
                val breathing = 1.0 - profile.breathingDepth +
                    profile.breathingDepth * sin(2.0 * PI * profile.breathingRateHz * t)
                breathSum += w[i] * breathing
                wsum += w[i]
            }
            val breathMean = breathSum / wsum
            forEachImpl { impl, tag ->
                val sig = stereo(impl, profile, SR + LONG_N + 4000)
                for (k in spec.semis(0)) {
                    val f = spec.hz(k)
                    val expected = profile.baseVolume.toDouble() / profile.voiceCount *
                        waveFundamental(profile.waveformBrightness.toDouble()) *
                        onePoleGain(profile.lowpassCutoffHz.toDouble(), f) * breathMean
                    val got = bothAmp(sig, start, n, f)
                    assertEquals(tag("$name tone ${"%.1f".format(f)} Hz level"), 1.0, got / expected, 0.02)
                }
            }
        }
    }

    /**
     * Waveform brightness folds in triangle harmonics -- ODD multiples only. Predicts the 3rd
     * harmonic (`b * 8/(9 pi^2)`, low-passed) and asserts the 2nd/4th are absent. Guards the
     * blend law and against a wrong waveform (a saw-like series would light up the even
     * harmonics). Profiles without an arpeggio only: its octave tone would sit on 2f.
     */
    @Test
    fun `brightness adds odd triangle harmonics only`() {
        for ((name, profile) in NON_ARP_WITH_DARK) {
            val spec = Spec(profile)
            val b = profile.waveformBrightness.toDouble()
            forEachImpl { impl, tag ->
                val sig = stereo(impl, profile, SR + LONG_N + 4000)
                for (k in spec.semis(0)) {
                    val f = spec.hz(k)
                    val h1 = bothAmp(sig, SR, LONG_N, f)
                    val h3 = bothAmp(sig, SR, LONG_N, 3 * f)
                    val h2 = bothAmp(sig, SR, LONG_N, 2 * f)
                    val h4 = bothAmp(sig, SR, LONG_N, 4 * f)
                    // The 3rd harmonic of the triangle is in phase with its fundamental, so it
                    // also sits in quadrature with the sine: amplitude b * 8/(9 pi^2) exactly.
                    val expected3 = profile.baseVolume.toDouble() / profile.voiceCount *
                        (b * 8.0 / (9.0 * PI * PI)) * onePoleGain(profile.lowpassCutoffHz.toDouble(), 3 * f)
                    val fundamental = profile.baseVolume.toDouble() / profile.voiceCount *
                        waveFundamental(b) * onePoleGain(profile.lowpassCutoffHz.toDouble(), f)
                    // Relative to the measured fundamental, so the shared breathing factor cancels.
                    assertEquals(tag("$name ${"%.1f".format(f)} Hz: 3rd/1st harmonic ratio (h1=$h1 h3=$h3)"),
                        expected3 / fundamental, h3 / h1, expected3 / fundamental * 0.03)
                    // 2f can legitimately coincide with ANOTHER voice's 3rd harmonic (e.g. a
                    // 164.8 Hz voice's 2f = 329.6 Hz ~ 3 x 110 Hz); skip those bins.
                    val others = spec.semis(0).filter { it != k }.map { spec.hz(it) }
                    if (others.none { abs(2 * f - 3 * it) < 3.0 }) {
                        assertTrue(tag("$name ${"%.1f".format(f)} Hz: 2nd harmonic must be absent (h2=$h2 vs fundamental $fundamental)"),
                            h2 < 0.001 * fundamental)
                    }
                    assertTrue(tag("$name ${"%.1f".format(f)} Hz: 4th harmonic must be absent (h4=$h4 vs fundamental $fundamental)"),
                        h4 < 0.001 * fundamental)
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // P2: timing of the chord change
    // -------------------------------------------------------------------------------------

    private fun uniqueTones(a: List<Int>, b: List<Int>, arp: Boolean): List<Int> =
        // Tones in [a] not in [b]; with an arpeggio, also drop ones that coincide with an
        // arpeggio octave (b tone + 12) of the other chord, which legitimately lands there.
        a.filter { it !in b && !(arp && (it - 12) in b) }

    /**
     * The new chord's pitches are absent right before `chordDurationSeconds` and dominant right
     * after `chordDurationSeconds + crossfadeSeconds`, and the old chord's unique tones are gone
     * by then. Windows keep a 5%-of-hold margin either side of the hold/crossfade edges, so a hold
     * that is doubled (or halved, or off by more than ~5%), a crossfade that never completes, or
     * a progression that skips ahead all fail. Both implementations.
     */
    @Test
    fun `chord change is absent before the hold and dominant after hold plus crossfade`() {
        for ((name, profile) in NAMED) {
            val spec = Spec(profile)
            val win = Windows(profile)
            val old = uniqueTones(spec.semis(0), spec.semis(1), profile.arpeggioEnabled)
            val new = uniqueTones(spec.semis(1), spec.semis(0), profile.arpeggioEnabled)
            assertTrue("$name: test needs chord-unique tones", old.isNotEmpty() && new.isNotEmpty())
            forEachImpl { impl, tag ->
                val sig = stereo(impl, profile, win.frames)
                val oldBefore = old.map { bothAmp(sig, win.beforeStart, SHORT_N, spec.hz(it)) }
                val newBefore = new.map { bothAmp(sig, win.beforeStart, SHORT_N, spec.hz(it)) }
                val oldAfter = old.map { bothAmp(sig, win.afterStart, SHORT_N, spec.hz(it)) }
                val newAfter = new.map { bothAmp(sig, win.afterStart, SHORT_N, spec.hz(it)) }
                val ctx = "old=$old new=$new before(old=$oldBefore new=$newBefore) after(old=$oldAfter new=$newAfter)"
                assertTrue(tag("$name: new chord must be inaudible before the hold ends -- $ctx"),
                    oldBefore.min() >= K_ON_OVER_OFF * newBefore.max())
                assertTrue(tag("$name: new chord must dominate after hold+crossfade -- $ctx"),
                    newAfter.min() >= K_ON_OVER_OFF * oldAfter.max())
                // And it is really the same loudness class, not a residual leak.
                assertTrue(tag("$name: new chord after the change is as strong as the old chord before it -- $ctx"),
                    newAfter.min() >= 0.4 * oldBefore.min() && newAfter.min() <= 2.5 * oldBefore.max())
            }
        }
    }

    /**
     * Mid-crossfade both chords sound, at the level an EQUAL-POWER blend predicts: the outgoing
     * chord's unique tones follow `cos(pi/2 * progress)` and the incoming ones `sin(pi/2 *
     * progress)`. The expected window level is integrated numerically (Hann-weighted gain curve x
     * breathing) relative to the same tone's steady level, so a hard switch at the hold edge, a
     * crossfade that never blends, or a LINEAR blend (`1 - p` / `p`, 0.5 instead of 0.707 at the
     * midpoint, ~10% lower once windowed) all miss the 4% tolerance.
     */
    @Test
    fun `mid crossfade follows the equal power blend curve`() {
        for ((name, profile) in NAMED) {
            val spec = Spec(profile)
            val win = Windows(profile)
            val old = uniqueTones(spec.semis(0), spec.semis(1), profile.arpeggioEnabled)
            val new = uniqueTones(spec.semis(1), spec.semis(0), profile.arpeggioEnabled)
            val n = min(SHORT_N, (win.xfade * 0.8).toInt())
            val start = win.midStart - n / 2

            fun breathing(index: Int): Double = 1.0 - profile.breathingDepth +
                profile.breathingDepth * sin(2.0 * PI * profile.breathingRateHz * (index + 1).toDouble() / SR)
            fun weightedMean(from: Int, len: Int, f: (Int) -> Double): Double {
                val w = hann(len)
                var num = 0.0
                var den = 0.0
                for (i in 0 until len) { num += w[i] * f(from + i); den += w[i] }
                return num / den
            }
            fun progress(index: Int) = (index - win.hold).toDouble() / win.xfade
            val outMid = weightedMean(start, n) { cos(PI / 2.0 * progress(it)) * breathing(it) }
            val inMid = weightedMean(start, n) { sin(PI / 2.0 * progress(it)) * breathing(it) }
            val steadyBefore = weightedMean(win.beforeStart, SHORT_N) { breathing(it) }
            val steadyAfter = weightedMean(win.afterStart, SHORT_N) { breathing(it) }

            forEachImpl { impl, tag ->
                val sig = stereo(impl, profile, win.frames)
                for (k in old) {
                    val measured = bothAmp(sig, start, n, spec.hz(k)) / bothAmp(sig, win.beforeStart, SHORT_N, spec.hz(k))
                    assertEquals(tag("$name: outgoing tone $k mid-crossfade level relative to steady"), outMid / steadyBefore, measured, 0.04 * outMid / steadyBefore)
                }
                for (k in new) {
                    val measured = bothAmp(sig, start, n, spec.hz(k)) / bothAmp(sig, win.afterStart, SHORT_N, spec.hz(k))
                    assertEquals(tag("$name: incoming tone $k mid-crossfade level relative to steady"), inMid / steadyAfter, measured, 0.04 * inMid / steadyAfter)
                }
            }
        }
    }

    /**
     * Walks a whole progression (CHESS's degrees 0,5,3,4 then wrapping to 0,5 -- shortened to 3 s
     * holds so the test stays fast) and checks each chord in its steady window sounds exactly ITS
     * tones. Guards progression ORDER, advance-by-one, and wrap-around, which no single
     * first-change test can: e.g. "advance by 2" is right at chord 1 only by luck of the list.
     */
    @Test
    fun `progression plays every chord in listed order and wraps around`() {
        val profile = MusicProfiles.CHESS.copy(chordDurationSeconds = 3f, crossfadeSeconds = 0.6f)
        val spec = Spec(profile)
        val cycle = 3.6 // hold + crossfade
        val frames = ((5 * cycle + 0.9) * SR).toInt() + SHORT_N + 4000
        forEachImpl { impl, tag ->
            val sig = stereo(impl, profile, frames)
            for (chord in 0..5) {
                // Chord k>=1 is fully in from k*3.6 s and holds 3 s; chord 0 holds from ~0.35 s.
                val start = if (chord == 0) SR else ((chord * cycle + 0.9) * SR).toInt()
                val semis = spec.semis(chord)
                val report = measureChord(sig, start, SHORT_N, spec, semis)
                assertTrue(
                    tag("chord #$chord should be degree ${spec.degree(chord)} tones $semis -- $report"),
                    report.minOn >= K_ON_OVER_OFF * report.maxOff
                )
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // P3: stereo placement
    // -------------------------------------------------------------------------------------

    /**
     * Stereo placement per voice: the lowest voice is LEFT of center, the highest RIGHT, a middle
     * voice centered -- and the L/R power ratio at each chord-tone frequency follows the equal-power
     * law `cos^2(theta)/sin^2(theta)`, `theta = (pan + 1) pi / 4`, pan = -0.6..0.6 (9.47, 1, 0.106
     * for three voices). The L+R power of each voice is also unaffected by its position (equal
     * power) after removing the low-pass tilt. Guards a mirrored field (pan sign flip), a linear
     * pan law (16 / 0.0625 instead of 9.47 / 0.106 at the edges, and a dip in level away from the
     * edges), a wrong spread, and per-voice pan being applied to the wrong voice.
     */
    @Test
    fun `voices are placed left to right at the equal power pan positions`() {
        for ((name, profile) in NAMED) {
            val spec = Spec(profile)
            val semis = spec.semis(0)
            val nV = profile.voiceCount
            forEachImpl { impl, tag ->
                val sig = stereo(impl, profile, SR + LONG_N + 4000)
                val levels = ArrayList<Double>()
                for ((v, k) in semis.withIndex()) {
                    val f = spec.hz(k)
                    val l = ampAt(sig.left, SR, LONG_N, f)
                    val r = ampAt(sig.right, SR, LONG_N, f)
                    val ratio = (l * l) / (r * r)
                    val expected = expectedLrPowerRatio(expectedPan(v, nV))
                    assertEquals(tag("$name voice $v (${"%.1f".format(f)} Hz) L/R power ratio, expected $expected"), 1.0, ratio / expected, 0.10)
                    when (v) {
                        0 -> assertTrue(tag("$name: lowest voice must sit LEFT (L=$l R=$r)"), l > 2.0 * r)
                        nV - 1 -> assertTrue(tag("$name: highest voice must sit RIGHT (L=$l R=$r)"), r > 2.0 * l)
                        else -> assertEquals(tag("$name: middle voice must be centered"), 1.0, l / r, 0.05)
                    }
                    // Level after undoing the filter tilt is the same for every voice.
                    levels += hypot(l, r) / onePoleGain(profile.lowpassCutoffHz.toDouble(), f)
                }
                for (v in 1 until nV) {
                    assertEquals(tag("$name: equal power => voice $v total level equals voice 0's"), 1.0, levels[v] / levels[0], 0.08)
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Arpeggio layer
    // -------------------------------------------------------------------------------------

    /**
     * Isolates the arpeggio layer exactly by subtracting the same profile rendered without it (the
     * pad never depends on the arpeggio, and the filter is linear), then checks each note's pitch:
     * note n plays chord tone `[0,1,2,1][n % 4] % voices`, ONE OCTAVE UP (2x), one note every
     * `44100 / rate` samples. The expected tone must beat the same tone an octave low (x1), the
     * x1.5 / x3 / x4 multiples, and the other chord tones' octaves by 10x. Guards the walk order,
     * the octave (a x4 or x1 arpeggio), the voice-count wrap and note timing.
     */
    @Test
    fun `arpeggio plays chord tones in 0 1 2 1 order one octave up`() {
        val order = intArrayOf(0, 1, 2, 1)
        for ((name, profile) in NAMED.filter { it.second.arpeggioEnabled }) {
            val spec = Spec(profile)
            val noteLen = (SR / profile.arpeggioRateHz.toDouble()).toLong().toInt()
            val notes = 8
            val frames = noteLen * notes + 10
            val tones = spec.semis(0).map { spec.hz(it) }
            forEachImpl { impl, tag ->
                val withArp = renderRaw(impl, profile, frames)
                val padOnly = renderRaw(impl, profile.copy(arpeggioEnabled = false), frames)
                val arp = DoubleArray(frames * 2) { withArp[it] - padOnly[it] }
                for (ch in 0..1) {
                    val x = FloatArray(frames) { arp[it * 2 + ch].toFloat() }
                    for (note in 0 until notes) {
                        val which = order[note % 4] % profile.voiceCount
                        val f = tones[which]
                        val start = note * noteLen
                        val expected = ampAt(x, start, noteLen, 2.0 * f)
                        val rivals = LinkedHashMap<String, Double>()
                        for (m in listOf(0.5, 1.0, 1.5, 3.0, 4.0)) rivals["${m}x tone $which"] = ampAt(x, start, noteLen, m * f)
                        for (j in tones.indices) if (j != which) rivals["2x tone $j"] = ampAt(x, start, noteLen, 2.0 * tones[j])
                        val worst = rivals.maxByOrNull { it.value }!!
                        assertTrue(
                            tag("$name ch$ch note $note should be tone $which x2 (${"%.1f".format(2 * f)} Hz, amp $expected) but ${worst.key} has ${worst.value}"),
                            expected >= 10.0 * worst.value && expected > 1e-4
                        )
                    }
                }
            }
        }
    }

    /**
     * The plucked arpeggio is a single melodic line and stays dead center: the arpeggio-only
     * component is identical on both channels, while the pad itself is genuinely stereo (L != R).
     * Guards an arpeggio accidentally routed through the per-voice pan or into one channel only.
     */
    @Test
    fun `arpeggio layer is dead center while the pad is not`() {
        val profile = MusicProfiles.MANCALA
        val frames = 3 * SR
        forEachImpl { impl, tag ->
            val withArp = renderRaw(impl, profile, frames)
            val padOnly = renderRaw(impl, profile.copy(arpeggioEnabled = false), frames)
            var maxArp = 0.0
            var maxAsym = 0.0
            var maxPadDiff = 0.0
            for (i in 0 until frames) {
                val al = withArp[2 * i] - padOnly[2 * i]
                val ar = withArp[2 * i + 1] - padOnly[2 * i + 1]
                maxArp = max(maxArp, max(abs(al), abs(ar)))
                maxAsym = max(maxAsym, abs(al - ar))
                maxPadDiff = max(maxPadDiff, abs(padOnly[2 * i] - padOnly[2 * i + 1]))
            }
            assertTrue(tag("arpeggio must be audible (max $maxArp)"), maxArp > 1e-3)
            assertTrue(tag("arpeggio must be identical on both channels (asymmetry $maxAsym)"), maxAsym < 1e-9)
            assertTrue(tag("pad must be genuinely stereo (max |L-R| = $maxPadDiff)"), maxPadDiff > 5e-3)
        }
    }

    // -------------------------------------------------------------------------------------
    // Production translation layer (RustPadSynth.render / renderFadeOut)
    // -------------------------------------------------------------------------------------

    /**
     * Renders through the PRODUCTION Rust wrapper ([RustPadSynth.render] /
     * [RustPadSynth.renderFadeOut], i.e. generated `bytes` -> little-endian `Short` decoding) and
     * compares with [PadSynthState.render] / [PadSynthState.renderFadeOut] within 1 LSB (the two
     * engines agree to ~1e-9 before quantization, so only a rare boundary sample may differ by 1).
     * Three profiles (2-voice + arpeggio, plain 3-voice, 3-voice + arpeggio) are rendered in
     * uneven consecutive chunks (1, 3, 777, 1024, 2205, 4410 frames) across the first chord
     * change -- at least one chunk provably spans the hold -> crossfade edge -- then a 0.25 s
     * fade-out tail must match the twin, overwrite EVERY element of the caller's buffer (buffers
     * are pre-filled with `Short.MIN_VALUE`, which a quantizer never emits), end silent, and
     * decay. Guards Rust-only regressions the f64-seam tests cannot see: a byte-path channel
     * bleed/swap, a dropped fade taper, a wrong byte order or short read on the stop path.
     */
    @Test
    fun `production RustPadSynth render and fade out match the Kotlin twin within one LSB`() {
        val profiles = listOf(
            "AIR_HOCKEY" to MusicProfiles.AIR_HOCKEY,
            "CHESS_FAST" to MusicProfiles.CHESS.copy(chordDurationSeconds = 1f, crossfadeSeconds = 0.6f),
            "UNO_FAST" to MusicProfiles.UNO.copy(chordDurationSeconds = 1.5f, crossfadeSeconds = 0.5f)
        )
        val sizes = intArrayOf(2205, 1, 777, 4410, 3, 1024)
        val unwritten = Short.MIN_VALUE
        for ((name, profile) in profiles) {
            val twin = PadSynthState(profile)
            val rust = RustPadSynth(profile)
            try {
                val holdFrames = (profile.chordDurationSeconds * SR).toLong()
                val total = holdFrames.toInt() + (profile.crossfadeSeconds * SR).toInt() + SR / 2
                var done = 0
                var chunkIndex = 0
                var spannedChange = false
                var maxDiff = 0
                var nonSilent = 0L
                while (done < total) {
                    val n = min(sizes[chunkIndex % sizes.size], total - done)
                    val a = ShortArray(n * 2) { unwritten }
                    val b = ShortArray(n * 2) { unwritten }
                    twin.render(a)
                    rust.render(b)
                    if (done <= holdFrames && done + n > holdFrames) spannedChange = true
                    for (i in a.indices) {
                        assertTrue("$name chunk $chunkIndex sample $i was not written by RustPadSynth.render", b[i] != unwritten)
                        maxDiff = max(maxDiff, abs(a[i].toInt() - b[i].toInt()))
                        if (b[i].toInt() != 0) nonSilent++
                    }
                    done += n
                    chunkIndex++
                }
                assertTrue("$name: some chunk must span the first chord change", spannedChange)
                assertTrue("$name: RustPadSynth.render differs from PadSynthState.render by $maxDiff LSB", maxDiff <= 1)
                assertTrue("$name: rendered audio must be substantially non-silent ($nonSilent)", nonSilent > total)

                // Stop path: the 0.25 s fade-out tail.
                val tailFrames = SR / 4
                val tailTwin = ShortArray(tailFrames * 2) { unwritten }
                val tailRust = ShortArray(tailFrames * 2) { unwritten }
                twin.renderFadeOut(tailTwin)
                rust.renderFadeOut(tailRust)
                assertEquals("$name: fade-out tail length", tailFrames * 2, tailRust.size)
                var tailDiff = 0
                for (i in tailRust.indices) {
                    assertTrue("$name fade-out sample $i was not written by RustPadSynth.renderFadeOut", tailRust[i] != unwritten)
                    tailDiff = max(tailDiff, abs(tailTwin[i].toInt() - tailRust[i].toInt()))
                }
                assertTrue("$name: RustPadSynth.renderFadeOut differs from PadSynthState.renderFadeOut by $tailDiff LSB", tailDiff <= 1)
                val q = tailFrames * 2 / 4
                fun quarterRms(k: Int): Double =
                    sqrt((k * q until (k + 1) * q).sumOf { tailRust[it].toDouble() * tailRust[it] } / q)
                assertTrue("$name: fade-out must start audibly (rms ${quarterRms(0)})", quarterRms(0) > 100.0)
                assertTrue("$name: fade-out must decay (first ${quarterRms(0)}, last ${quarterRms(3)})", quarterRms(3) * 3.0 < quarterRms(0))
                assertTrue(
                    "$name: fade-out must end silent, last frame = ${tailRust[tailRust.size - 2]}, ${tailRust[tailRust.size - 1]}",
                    abs(tailRust[tailRust.size - 2].toInt()) <= 3 && abs(tailRust[tailRust.size - 1].toInt()) <= 3
                )

                // Degenerate stop-path buffers behave like the twin: empty is a no-op, one frame is
                // the continued signal at full taper.
                rust.renderFadeOut(ShortArray(0))
                twin.renderFadeOut(ShortArray(0))
                val oneTwin = ShortArray(2) { unwritten }
                val oneRust = ShortArray(2) { unwritten }
                twin.renderFadeOut(oneTwin)
                rust.renderFadeOut(oneRust)
                for (i in 0..1) {
                    assertTrue(
                        "$name one-frame fade-out sample $i: rust ${oneRust[i]} vs twin ${oneTwin[i]}",
                        abs(oneTwin[i].toInt() - oneRust[i].toInt()) <= 1
                    )
                }
            } finally {
                rust.close()
            }
        }
    }

    /**
     * With the arpeggio disabled the layer contributes exactly nothing: not merely "quiet" -- a
     * disabled flag that still leaked the layer would show up as a difference from a profile whose
     * arpeggio volume is zero.
     */
    @Test
    fun `disabled arpeggio equals an enabled arpeggio at zero volume`() {
        val profile = MusicProfiles.AIR_HOCKEY
        forEachImpl { impl, tag ->
            val off = renderRaw(impl, profile.copy(arpeggioEnabled = false), SR)
            val zero = renderRaw(impl, profile.copy(arpeggioVolume = 0f), SR)
            var maxDiff = 0.0
            for (i in off.indices) maxDiff = max(maxDiff, abs(off[i] - zero[i]))
            assertTrue(tag("disabled arpeggio must add nothing (max diff $maxDiff)"), maxDiff < 1e-12)
        }
    }
}
