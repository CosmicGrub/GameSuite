package com.gamesuite.audio

import com.gamesuite.audio.MusicProfilesTestSupport.distinctProfiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.ref.SoftReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Signal-quality tests for the pure-Kotlin [PadSynthState] pad synthesizer. The engine's own KDoc
 * claims a click-free startup, click-free chord crossfades and a click-free fade-out tail, and
 * until now nothing measured any of that: [AmbientMusicEngineTest] covers only the pan math and
 * `RustPadSynthCorrectnessTest` only diffs Kotlin against Rust (a shared bug passes it). Everything
 * here observes only the audio that comes out of `render`/`renderFadeOut`/`renderF64`, never
 * private state, so each test survives internal refactors and fails only if the SOUND regresses.
 *
 * No native library is needed. Nothing is random; every profile is deterministic by construction.
 *
 * Two kinds of check, kept apart on purpose because profiles are auto-discovered and a legitimate
 * new profile must not be rejected by a calibration it never promised to meet:
 *  - RIGOROUS checks (finite, in range, analytic peak ceiling, first/second-difference click
 *    bounds, startup envelope, exact timing, determinism, chunking, 16-bit exactness) run on EVERY
 *    discovered profile; they follow from the DSP alone, not from today's profile shapes.
 *  - CALIBRATED checks (marked "calibrated" in their KDoc: the audibility fractions of baseVolume,
 *    the windowed startup-RMS ramp shape, the equal-loudness band during a crossfade, the injected
 *    click and hard-switch self-tests, the stereo depth of 15%) rest on thresholds measured on the
 *    twelve shipped profile shapes, so they run only on [REFERENCE], a named list of those
 *    profiles (a rename or removal is a compile error here). Every other profile still gets a
 *    weaker, shape-independent version where one exists (non-mute, not mono).
 *
 * Analytic bounds used below (derived from the DSP in `computeNextSample`, not fitted to output;
 * the per-test KDocs say where each one is used):
 *  - Output peak: every oscillator sample is in [-1, 1], voice gains are 1/N with pan gains <= 1,
 *    breathing <= 1, the arpeggio adds at most `arpeggioVolume`, and a one-pole low-pass with
 *    coefficient in (0, 1) is a convex average so it cannot exceed its input. During a crossfade
 *    the two banks are weighted cos + sin, which reaches sqrt(2), so over the whole signal
 *    `|x| <= baseVolume * (sqrt(2) + arpeggioVolume)` ([peakCeiling]). Outside a crossfade the
 *    tighter `baseVolume * (1 + arpeggioVolume)` ([startupCeiling]) holds, and is used only for the
 *    first 0.5 s, which precedes every profile's first chord change. Real signals sit far below
 *    both (about 0.5 to 0.7 of baseVolume), so these are ceilings, not predictions.
 *  - Sample-to-sample step: the same convex-average argument shows a one-pole low-pass cannot
 *    increase the Lipschitz constant of its input, so the output step is bounded by the input's
 *    (see [firstDifferenceBound]). The second difference is bounded the same way (see
 *    [secondDifferenceBound]) and is ~10x more sensitive to a discontinuity, because a smooth
 *    band-limited signal has a tiny second difference while a step keeps its full height.
 */
class PadSynthSignalQualityTest {

    // ---------------------------------------------------------------------------------------
    // S1: sanity of every profile over the same 30 s window RustPadSynthCorrectnessTest uses
    // ---------------------------------------------------------------------------------------

    /**
     * Guards against NaN/Inf leaking out of the DSP (e.g. a divide by `voiceCount`, a phase that
     * runs away, a filter coefficient that makes the one-pole unstable), against a gain bug that
     * pushes samples past full scale (which `toPcm16` would then have to hard-clip, an audible
     * distortion), against a silent output (a zeroed gain stage), and against a DC offset (a
     * triangle/sine mix that is not zero-mean would thump on start/stop and eat headroom). For
     * every profile: finite, inside [-1, 1], under the analytic [peakCeiling], not mute (peak at
     * least 5% and RMS at least 1% of baseVolume), and DC-free. Calibrated, on [REFERENCE] only:
     * the real peak must reach 30% of baseVolume and the RMS must sit in 8%..50% of it (measured
     * peaks are 0.53..0.71 of baseVolume), which fails a 3x attenuation or a 3x gain error that
     * the wide analytic ceiling alone would let through.
     */
    @Test
    fun `every profile renders finite in-range audible DC-free audio for 30 seconds`() {
        for ((name, p) in distinctProfiles()) {
            val x = signal30(name, p)
            var peak = 0.0
            var sq = 0.0
            val sum = DoubleArray(2)
            for (i in x.indices) {
                val v = x[i].toDouble()
                // Plain ifs, not assertTrue: 63 million eager message strings would dominate the runtime.
                if (!v.isFinite()) fail("$name: sample $i is not finite ($v)")
                val a = abs(v)
                if (a > 1.0) fail("$name: sample $i out of [-1, 1] ($v)")
                if (a > peak) peak = a
                sq += v * v
                sum[i and 1] += v
            }
            val ceiling = peakCeiling(p)
            assertTrue("$name: peak $peak exceeds analytic ceiling $ceiling", peak <= ceiling * (1 + 1e-9))
            val rms = sqrt(sq / x.size)
            assertTrue("$name: peak $peak is implausibly quiet for baseVolume ${p.baseVolume}", peak >= 0.05 * p.baseVolume)
            assertTrue("$name: rms $rms is (nearly) silent", rms >= 0.01 * p.baseVolume)
            if (isReference(p)) {
                assertTrue("$name: peak $peak is below 0.3 of baseVolume ${p.baseVolume}", peak >= 0.3 * p.baseVolume)
                assertTrue("$name: rms $rms too low", rms >= 0.08 * p.baseVolume)
                assertTrue("$name: rms $rms too high", rms <= 0.5 * p.baseVolume)
            }
            val dcLimit = 0.002 * p.baseVolume
            for (ch in 0..1) {
                val mean = sum[ch] / WINDOW_FRAMES
                assertTrue("$name ch$ch: DC offset $mean beyond $dcLimit", abs(mean) <= dcLimit)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // S2: startup fade-in
    // ---------------------------------------------------------------------------------------

    /**
     * Guards the "very first sample isn't a jump straight from silence to full amplitude" claim.
     * The startup envelope is a linear 0 -> 1 ramp over [STARTUP_SECONDS] (the engine's documented
     * `STARTUP_FADE_SECONDS`), and everything downstream (low-pass, base volume) is bounded by
     * [startupCeiling] (no crossfade can have started yet, because every profile holds its first
     * chord for well over 0.5 s), so sample `n` must satisfy
     * `|x[n]| <= startupCeiling * (n + 1) / rampFrames`.
     * That is a rigorous per-sample envelope, so it fails if the ramp is removed (envelope starts
     * at 1), is too short, or restarts, while the first-frame check pins the very first sample
     * (the audible "pop" if the ramp were skipped) to a fraction of a percent of full scale.
     */
    @Test
    fun `startup is silent at frame zero and never exceeds the linear fade-in envelope`() {
        val rampFrames = STARTUP_SECONDS * SR
        for ((name, p) in distinctProfiles()) {
            val x = renderChunked(p, (STARTUP_SECONDS * SR).toInt() + SR / 10)
            val ceiling = startupCeiling(p)
            assertTrue("$name: first chord hold must exceed the 0.5 s startup window", holdFrames(p) > SR / 2)
            val firstFrameLimit = 2.0 * ceiling / rampFrames
            assertTrue("$name: first L frame ${x[0]} not tiny (limit $firstFrameLimit)", abs(x[0]) <= firstFrameLimit)
            assertTrue("$name: first R frame ${x[1]} not tiny (limit $firstFrameLimit)", abs(x[1]) <= firstFrameLimit)
            val frames = x.size / 2
            for (n in 0 until frames) {
                val env = minOf(1.0, (n + 1) / rampFrames)
                val limit = ceiling * env * (1 + 1e-9) + 1e-12
                for (ch in 0..1) {
                    val v = abs(x[2 * n + ch])
                    assertTrue("$name frame $n ch$ch: |x|=$v above fade-in envelope limit $limit", v <= limit)
                }
            }
        }
    }

    /**
     * Guards the *shape* of the startup fade, complementing the upper-envelope test above (which
     * a silent or over-slow ramp would still satisfy). Calibrated: runs on [REFERENCE] only, since
     * the tolerances below (5% oscillation allowance, 0.6..1.4 post-ramp band) assume the breathing
     * depth and chord beating of the shipped profiles. Windowed RMS over five 70 ms windows across
     * the 0.35 s ramp must rise (a linear ramp makes the expected window RMS ratios about
     * 0.12 : 0.31 : 0.50 : 0.70 : 0.90 of steady, so each window is far larger than the last and a
     * 5% oscillation tolerance is safe), the first 35 ms must be under 12% of steady RMS (a linear
     * ramp gives ~6%), and shortly after the ramp the level must have reached steady state.
     */
    @Test
    fun `startup RMS ramps up monotonically to steady state over about 0_35 seconds`() {
        val win = (0.070 * SR).toInt()
        for ((name, p) in referenceProfiles()) {
            val x = renderChunked(p, SR * 3)
            val steady = rms(x, SR, SR * 3) // 1.0 .. 3.0 s, well past the ramp
            assertTrue("$name: steady RMS is zero", steady > 0.0)

            val early = rms(x, 0, (0.035 * SR).toInt())
            assertTrue("$name: first 35 ms RMS $early not << steady $steady", early < 0.12 * steady)

            val windows = (0 until 5).map { rms(x, it * win, (it + 1) * win) }
            for (k in 1 until windows.size) {
                assertTrue(
                    "$name: ramp window $k RMS ${windows[k]} not above previous ${windows[k - 1]} (windows=$windows)",
                    windows[k] >= 0.95 * windows[k - 1]
                )
            }
            assertTrue("$name: last ramp window ${windows.last()} should still be under steady $steady", windows.last() < 1.15 * steady)

            // 0.42 .. 0.56 s: the ramp finished at 0.35 s, so this must look like steady state
            // (breathing/chord beating move it a little, hence the generous band).
            val after = rms(x, (0.42 * SR).toInt(), (0.56 * SR).toInt())
            assertTrue("$name: post-ramp RMS $after vs steady $steady", after > 0.6 * steady && after < 1.4 * steady)
        }
    }

    // ---------------------------------------------------------------------------------------
    // S3: click-freeness across startup, arpeggio retriggers and a chord change + crossfade
    // ---------------------------------------------------------------------------------------

    /**
     * The click detector proper. Guards "click-free chord crossfades" (the 30 s window contains at
     * least one full hold + crossfade for every profile, see the S4 test) plus the crossfade-end
     * bank swap, arpeggio retriggers and the startup ramp. A click is a discontinuity, and a
     * discontinuity of height `h` shows up as a second difference of height `h` while the smooth
     * signal's own second difference is bounded by [secondDifferenceBound] (frequency^2 for the
     * sine part, 8 f / fs at a triangle corner, plus the arpeggio's 2%-residual retrigger step).
     * The bound is rigorous (a one-pole low-pass cannot raise it), so a failure here is a real
     * discontinuity, not noise. A hard-switch crossfade or a chord change that jumps frequencies
     * violates it (mutation-checked).
     */
    @Test
    fun `no click across 30 seconds - second difference stays below the bandwidth bound`() {
        for ((name, p) in distinctProfiles()) {
            val x = signal30(name, p)
            for (ch in 0..1) {
                val bound = secondDifferenceBound(p, ch)
                val (worst, at) = maxSecondDifference(x, ch)
                assertTrue(
                    "$name ch$ch: max |d2x| $worst at frame $at (${at / FS} s) exceeds bound $bound",
                    worst <= bound
                )
            }
        }
    }

    /**
     * The sample-to-sample delta metric requested for click-freeness: the maximum absolute step per
     * channel over the whole 30 s. The bound is the Lipschitz constant of the signal, i.e. the
     * steepest slope its own bandwidth allows ([firstDifferenceBound]: sine slope `2 pi f / fs`,
     * triangle slope `4 f / fs`, arpeggio slope and retrigger step, x sqrt(2) for two overlapping
     * banks). Coarser than the second-difference test (natural slopes are already ~5% of peak) but
     * it needs no assumption about smoothness, and catches gross jumps such as a hard switch.
     */
    @Test
    fun `no click across 30 seconds - first difference stays below the Lipschitz bound`() {
        for ((name, p) in distinctProfiles()) {
            val x = signal30(name, p)
            for (ch in 0..1) {
                val bound = firstDifferenceBound(p, ch)
                val (worst, at) = maxFirstDifference(x, ch)
                assertTrue("$name ch$ch: max |dx| $worst at frame $at exceeds bound $bound", worst <= bound)
            }
        }
    }

    /**
     * Proves the click detector is not vacuous (a detector that can never fire would make the two
     * tests above pass forever). Calibrated, so it runs on [REFERENCE] only: on a copy of each
     * profile's real 30 s signal, inject a level step of 10% of that channel's peak at t = 10 s: the
     * second-difference metric must exceed its bound and localize the spike to the injection frame;
     * the same metric on the untouched signal stays below it. Also asserts the detector has the
     * resolution to see a 10% step at all (bound < 10% of peak), which is what "sensitive enough"
     * means for these profile shapes. The first-difference metric is coarser (natural slopes are
     * already ~5% of peak), so its self-test uses a 25% step. The samples are the shared cached
     * signal (single precision, error ~1e-7, far below every bound), never modified in place.
     */
    @Test
    fun `click detector flags an injected step and localizes it - and stays quiet on the clean signal`() {
        val injectAt = 10 * SR
        for ((name, p) in referenceProfiles()) {
            val clean = signal30(name, p)
            val glitched = clean.copyOf()
            for (ch in 0..1) {
                val peak = peak(clean, ch)
                val bound2 = secondDifferenceBound(p, ch)
                val bound1 = firstDifferenceBound(p, ch)
                assertTrue("$name ch$ch: detector resolution $bound2 is not below a 10% step (${0.1 * peak})", bound2 < 0.1 * peak)
                assertTrue("$name ch$ch: first-difference resolution $bound1 not below a 25% step", bound1 < 0.25 * peak)
                assertTrue("$name ch$ch: clean signal must be quiet", maxSecondDifference(clean, ch).first <= bound2)

                System.arraycopy(clean, 0, glitched, 0, clean.size)
                for (f in injectAt until WINDOW_FRAMES) glitched[2 * f + ch] += (0.1 * peak).toFloat()
                val (worst, at) = maxSecondDifference(glitched, ch)
                assertTrue("$name ch$ch: 10% step not flagged (max d2 $worst <= $bound2)", worst > bound2)
                assertTrue("$name ch$ch: spike at frame $at, injected at $injectAt", abs(at - injectAt) <= 2)

                System.arraycopy(clean, 0, glitched, 0, clean.size)
                for (f in injectAt until WINDOW_FRAMES) glitched[2 * f + ch] += (0.25 * peak).toFloat()
                val worst1 = maxFirstDifference(glitched, ch).first
                assertTrue("$name ch$ch: 25% step not flagged by first difference ($worst1 <= $bound1)", worst1 > bound1)
            }
        }
    }

    /**
     * Second self-test: the detector must also flag what a broken crossfade actually produces, a
     * hard switch between two chords. Build one from public output only: chord A (the profile
     * pinned to its first chord degree) plays until T, then chord B (pinned to its second) takes
     * over, spliced at 40 different times T spread across the pre-crossfade part of the hold. Two
     * free-running phases meet at the splice, so the jump height varies with T (occasionally the
     * two waves nearly coincide); the detector must flag at least 90% of splices, i.e. no profile
     * may be blind to a hard switch. Calibrated (the 90% figure and the precondition that the first
     * two chords differ are properties of the shipped profiles), so [REFERENCE] only.
     */
    @Test
    fun `click detector flags a hard-switch pseudo-crossfade between two chords`() {
        for ((name, p) in referenceProfiles()) {
            val d0 = p.chordProgressionDegrees[0]
            val d1 = p.chordProgressionDegrees[1]
            assertNotEquals("$name: precondition, first two chords must differ", d0, d1)
            val a = renderChunked(p.copy(chordProgressionDegrees = listOf(d0)), WINDOW_FRAMES / 3)
            val b = renderChunked(p.copy(chordProgressionDegrees = listOf(d1)), WINDOW_FRAMES / 3)
            val lo = SR
            val hi = minOf((0.9 * holdFrames(p)).toInt(), 9 * SR) // stay before the first crossfade and inside 10 s
            val bound = maxOf(secondDifferenceBound(p, 0), secondDifferenceBound(p, 1))
            var flagged = 0
            val splices = 40
            for (k in 0 until splices) {
                val t = lo + (hi - lo) * k / splices
                var worst = 0.0
                for (ch in 0..1) {
                    val spliced = { f: Int -> if (f < t) a[2 * f + ch] else b[2 * f + ch] }
                    for (f in (t - 2)..(t + 2)) {
                        val d2 = abs(spliced(f) - 2 * spliced(f - 1) + spliced(f - 2))
                        if (d2 > worst) worst = d2
                    }
                }
                if (worst > bound) flagged++
            }
            assertTrue("$name: only $flagged/$splices hard-switch splices flagged", flagged >= (0.9 * splices).toInt())
        }
    }

    // ---------------------------------------------------------------------------------------
    // S4: the shared 30 s window really contains a completed crossfade for every profile
    // ---------------------------------------------------------------------------------------

    /**
     * `RustPadSynthCorrectnessTest` hardcodes 30 s on the strength of a hand-computed comment
     * (slowest: TIC_TAC_TOE, 24 s hold + 3.6 s crossfade = 27.6 s). This turns that comment into an
     * assertion for every profile (deliberately NOT pinning which profile is slowest, so a valid
     * catalogue change that adds or retunes a slow profile is accepted while it stays inside the
     * window; TIC_TAC_TOE's 3.6 s default crossfade is pinned by `MusicProfilesValidityTest`),
     * recomputing the two lengths independently of `PadSynthState`
     * (`Float` seconds times 44100, truncated to `Long`, minimum 1, exactly what the synth does),
     * and requires at least 1 s of slack so a future slower profile fails HERE with a clear message
     * instead of silently un-testing the crossfade in the Rust parity test and in the click tests
     * above. Also checks the truncation against exact double arithmetic to within one sample.
     */
    @Test
    fun `every profile completes a chord hold plus crossfade inside the 30 second window with 1 second margin`() {
        for ((name, p) in distinctProfiles()) {
            val hold = holdFrames(p).toLong()
            val fade = crossfadeFrames(p).toLong()
            val exactHold = p.chordDurationSeconds.toDouble() * SR
            val exactFade = p.crossfadeSeconds.toDouble() * SR
            assertTrue("$name: hold frames $hold vs exact $exactHold", abs(hold - exactHold) < 1.0 + 1e-6)
            assertTrue("$name: crossfade frames $fade vs exact $exactFade", abs(fade - exactFade) < 1.0 + 1e-6)
            val needed = hold + fade
            assertTrue(
                "$name: hold+crossfade = ${needed / FS} s leaves less than 1 s inside the 30 s window",
                needed + SR <= WINDOW_FRAMES.toLong()
            )
        }
    }

    /**
     * Behavioral proof that the chord really changes at the documented time, using only output.
     * Two synths are identical except the second's progression is pinned to the first chord, so
     * they must be BIT-identical until the crossfade begins and diverge immediately after (the
     * incoming bank contributes `sin(progress)` from the first crossfade frame). Fails if the
     * change is delayed, early, or never happens (`samplesUntilChordChange` never reaching zero:
     * the two outputs would then be identical for the whole render), and pins the hold length to
     * the sample, independent of the 27.6 s arithmetic above. Uses a shortened-hold copy of each
     * profile so it stays cheap.
     */
    @Test
    fun `chord change begins exactly at the hold time and never earlier`() {
        for ((name, p0) in distinctProfiles()) {
            val p = p0.copy(chordDurationSeconds = 2f, crossfadeSeconds = 0.7f)
            val hold = holdFrames(p)
            val pinned = p.copy(chordProgressionDegrees = listOf(p.chordProgressionDegrees[0]))
            val total = hold + crossfadeFrames(p) + SR
            val a = renderChunked(p, total)
            val b = renderChunked(pinned, total)
            var first = -1
            for (i in a.indices) if (a[i] != b[i]) { first = i / 2; break }
            assertTrue("$name: chord never changed within ${total / FS} s", first >= 0)
            assertTrue("$name: outputs diverged at frame $first, before hold $hold", first >= hold)
            assertTrue("$name: divergence at frame $first is not within 5 frames of hold $hold", first <= hold + 5)
        }
    }

    /**
     * Behavioral proof of the crossfade itself and of the progression looping, with a
     * frequency-resolved measurement: a single-voice pad whose two chords are the well-separated
     * pitches 200 Hz and ~299.7 Hz (progression [0, 1] over scale [0, 7]). A single-frequency DFT
     * amplitude reads how much of each chord is present: only chord 0 before the change, an
     * EQUAL-POWER blend at the crossfade midpoint (each at cos/sin(pi/4) = 0.707 of full; a linear
     * fade would read 0.5 each, the 3 dB loudness dip the engine comments promise to avoid), only
     * chord 1 once the crossfade completes, and chord 0 again after the progression wraps.
     */
    @Test
    fun `crossfade blends two chords equal-power then completes and the progression wraps`() {
        val p = tonePad(root = 200f, scale = listOf(0, 7), degrees = listOf(0, 1), voices = 1, hold = 3f, crossfade = 1f)
        val f0 = chordToneHz(p, 0, 0)
        val f1 = chordToneHz(p, 1, 0)
        assertEquals(200.0, f0, 1e-9)
        assertEquals(200.0 * 2.0.pow(7 / 12.0), f1, 1e-9)
        val x = renderChunked(p, (10.8 * SR).toInt())

        fun amp(t0: Double, t1: Double, f: Double) = toneAmplitude(x, 0, (t0 * SR).toInt(), (t1 * SR).toInt(), f)
        val full = amp(1.0, 2.0, f0)
        assertTrue("reference tone missing: $full", full > 0.2) // 0.5 base * centered pan 0.707 ~= 0.35
        assertTrue("chord 1 tone leaking before change", amp(1.0, 2.0, f1) < 0.03 * full)

        // Crossfade is [3.0, 4.0); window [3.45, 3.55] spans progress 0.45..0.55.
        val midOut = amp(3.45, 3.55, f0) / full
        val midIn = amp(3.45, 3.55, f1) / full
        assertEquals("outgoing chord level at midpoint", 0.7071, midOut, 0.05)
        assertEquals("incoming chord level at midpoint", 0.7071, midIn, 0.05)

        assertTrue("outgoing chord still present after crossfade", amp(4.5, 6.5, f0) < 0.03 * full)
        assertEquals("incoming chord level after crossfade", 1.0, amp(4.5, 6.5, f1) / full, 0.05)

        // Second crossfade is [7.0, 8.0); wrapped back to chord 0 afterwards.
        assertTrue("chord 1 still present after wrap", amp(8.5, 10.5, f1) < 0.03 * full)
        assertEquals("chord 0 level after wrap", 1.0, amp(8.5, 10.5, f0) / full, 0.05)
    }

    /**
     * Guards the equal-power claim on every shipped profile, not just the synthetic tone pad: with
     * breathing and the arpeggio switched off (they modulate level independently of the
     * crossfade), the RMS in 100 ms windows across the whole first crossfade must stay within
     * 12% of the pre-change level. Chords that share no frequency are uncorrelated, so an
     * equal-power fade keeps constant total power (`cos^2 + sin^2 = 1`) whereas a linear fade dips
     * to 71% (-3 dB) at the midpoint. Profiles whose first two chords SHARE a pitch (e.g. UNO's
     * pentatonic I -> III) are skipped: the shared tone is coherent, so its two banks add
     * amplitudes rather than powers and the level legitimately wanders (clean UNO dips to 0.76),
     * which is not a defect and would make the bound meaningless for them; the tone-pad crossfade
     * test above covers those profiles' fade law with unshared tones. Calibrated (the 0.88..1.12
     * band was measured on the shipped chord shapes), so [REFERENCE] only.
     */
    @Test
    fun `crossfade keeps loudness constant on every profile whose chords share no pitch`() {
        var checked = 0
        for ((name, p0) in referenceProfiles()) {
            val d0 = p0.chordProgressionDegrees[0]
            val d1 = p0.chordProgressionDegrees[1]
            val shared = (0 until p0.voiceCount).any { a ->
                (0 until p0.voiceCount).any { b -> abs(chordToneHz(p0, d0, a) - chordToneHz(p0, d1, b)) < 1.0 }
            }
            if (shared) continue
            checked++
            val p = p0.copy(breathingDepth = 0f, arpeggioEnabled = false)
            val hold = holdFrames(p)
            val fade = crossfadeFrames(p)
            val x = renderChunked(p, hold + fade + SR / 2)
            val reference = rms(x, SR, hold - SR / 2)
            assertTrue("$name: reference level zero", reference > 0.0)
            val win = SR / 10
            var start = hold
            var lo = 1.0
            var hi = 1.0
            while (start + win <= hold + fade) {
                val r = rms(x, start, start + win) / reference
                lo = minOf(lo, r); hi = maxOf(hi, r)
                start += win
            }
            assertTrue("$name: crossfade loudness dips to $lo of the pre-change level", lo >= 0.88)
            assertTrue("$name: crossfade loudness swells to $hi of the pre-change level", hi <= 1.12)
        }
        assertTrue("too few profiles qualified for the loudness check ($checked)", checked >= 5)
    }

    // ---------------------------------------------------------------------------------------
    // S5: fade-out tail
    // ---------------------------------------------------------------------------------------

    /**
     * Guards the "playback always ends in silence" claim of `renderFadeOut` (the 0.25 s tail the
     * engine renders on stop). For each profile, stopped at three moments (immediately, after 1 s,
     * and in the MIDDLE of a chord crossfade, the worst case), the tail must: be exactly
     * `2 * frames` shorts; start where the running stream left off (taper 1.0, so its first
     * frame equals the frame plain playback would have produced: no step at the seam); end on a
     * frame smaller than one 16-bit LSB step (linear taper reaches 1/frames at the last frame);
     * and follow a LINEAR taper, checked against the un-tapered signal from an identical twin
     * synth in ten windows (the ratio of tail to un-tapered RMS must match the analytic RMS of a
     * linear ramp weighted by the un-tapered signal's power in that window, so the taper itself is
     * measured, not just "gets quieter"). The tolerance is
     * RELATIVE (`5% of expected + 0.005`), because the expected gain falls from about 0.95 to 0.058
     * across the windows and an absolute 0.05 would let the last windows, where the tail is
     * nearly silent, be 1.7x too loud unnoticed. Windowed RMS over five windows must also not rise
     * (15% allowance for oscillation).
     */
    @Test
    fun `fade-out tail has the exact length, starts seamlessly, tapers linearly and ends silent`() {
        val frames = FADE_FRAMES
        for ((name, p0) in distinctProfiles()) {
            val p = p0.copy(chordDurationSeconds = 2f, crossfadeSeconds = 0.8f)
            val hold = holdFrames(p)
            val midCrossfade = hold + crossfadeFrames(p) / 2
            for (warm in listOf(0, SR, midCrossfade)) {
                val live = PadSynthState(p)
                val twin = PadSynthState(p)
                if (warm > 0) { live.renderF64(warm); twin.renderF64(warm) }
                val tail = ShortArray(frames * 2)
                live.renderFadeOut(tail)
                assertEquals(frames * 2, tail.size)
                val reference = twin.renderF64(frames) // same signal, no taper

                // Seamless start: taper(0) == 1 so frame 0 equals the untapered frame's PCM.
                assertEquals("$name warm=$warm: first L", pcm(reference[0]).toInt(), tail[0].toInt())
                assertEquals("$name warm=$warm: first R", pcm(reference[1]).toInt(), tail[1].toInt())

                // Ends silent: last frame is < 1 LSB apart from rounding toward zero.
                assertTrue("$name warm=$warm: last L ${tail[2 * frames - 2]}", abs(tail[2 * frames - 2].toInt()) <= 1)
                assertTrue("$name warm=$warm: last R ${tail[2 * frames - 1]}", abs(tail[2 * frames - 1].toInt()) <= 1)

                // Linear taper, measured: tail RMS / untapered RMS per window vs analytic ramp RMS.
                val win = frames / 10
                for (w in 0 until 10) {
                    val a = w * win
                    val b = a + win
                    var tailSq = 0.0
                    var refSq = 0.0
                    var expectedSq = 0.0 // sum of (analytic linear taper * untapered sample)^2
                    for (f in a until b) for (ch in 0..1) {
                        val t = tail[2 * f + ch] / 32767.0
                        val r = reference[2 * f + ch]
                        val linear = 1.0 - f.toDouble() / frames
                        tailSq += t * t
                        refSq += r * r
                        expectedSq += (linear * r) * (linear * r)
                    }
                    if (refSq < 1e-9) continue // near-silent stretch (startup): ratio meaningless
                    // Weighted by the untapered signal's own power in the window, so a source that is
                    // still ramping up (warm=0) or breathing does not bias the analytic gain.
                    val expected = sqrt(expectedSq / refSq)
                    val measured = sqrt(tailSq / refSq)
                    assertEquals("$name warm=$warm window $w taper gain", expected, measured, 0.05 * expected + 0.005)
                }

                // Monotone-ish decay in 5 coarse windows (waveform oscillates, so allow 15%).
                val coarse = frames / 5
                val rmsTail = (0 until 5).map { w ->
                    var s = 0.0
                    for (f in w * coarse until (w + 1) * coarse) for (ch in 0..1) { val t = tail[2 * f + ch] / 32767.0; s += t * t }
                    sqrt(s / (coarse * 2))
                }
                // Not asserted for warm=0: there the SOURCE is itself still inside its 0.35 s startup
                // ramp, so the tapered tail legitimately rises before it falls.
                if (warm > 0) {
                    for (w in 1 until 5) assertTrue("$name warm=$warm: tail RMS rose $rmsTail", rmsTail[w] <= 1.15 * rmsTail[w - 1] + 1e-9)
                    assertTrue("$name warm=$warm: tail must decay overall $rmsTail", rmsTail[4] < 0.35 * rmsTail[0])
                }
            }
        }
    }

    /**
     * Guards the degenerate calls the generator loop can make: `renderFadeOut` and `render` with a
     * zero-length array must be harmless no-ops that do NOT advance the synth (a stray call would
     * skip a chunk of audio). Odd-length arrays are deliberately not pinned: the engine only ever
     * passes even sizes, and `render` and `renderFadeOut` currently disagree about them, so there
     * is no contract to guard.
     */
    @Test
    fun `zero-length renders are no-ops that do not advance state`() {
        val p = MusicProfiles.MANCALA
        val reference = PadSynthState(p)
        val subject = PadSynthState(p)
        subject.renderFadeOut(ShortArray(0))
        subject.render(ShortArray(0))
        assertArrayEquals(reference.renderF64(1000), subject.renderF64(1000), 0.0)
    }

    // ---------------------------------------------------------------------------------------
    // S6: determinism, streaming invariance, quantization
    // ---------------------------------------------------------------------------------------

    /**
     * Guards determinism: two independently constructed synths for the same profile must produce
     * bit-identical output (any hidden global state, uninitialized field, `System.nanoTime` or
     * `Random` use would break this), across a short 2 s window and across a 3 s window of a
     * shortened-hold copy (1 s hold, 0.5 s crossfade) that straddles the first chord change and
     * its crossfade, with different chunking on the second synth. Profiles that differ in their
     * musical content (`rootNoteHz`, `scaleIntervals`, `chordProgressionDegrees`) must give clearly
     * different audio (a relative L2 distance above 5% over the first 3 s), so a wrong-preset
     * mix-up cannot go unheard; profiles sharing that tuple (deliberate variants of one preset)
     * are exempt, and literal duplicates are the business of `MusicProfilesValidityTest`.
     */
    @Test
    fun `synthesis is bit-for-bit deterministic and different profiles sound different`() {
        val profiles = distinctProfiles()
        for ((name, p) in profiles) {
            val shortHold = p.copy(chordDurationSeconds = 1f, crossfadeSeconds = 0.5f)
            for ((label, q, frames) in listOf(
                Triple("first 2 s", p, SR * 2),
                Triple("across the first chord change", shortHold, SR * 3)
            )) {
                val a = renderChunked(q, frames)
                val b = renderChunked(q, frames, chunkFrames = 977) // also different chunking
                assertTrue("$name ($label): two instances differ", a.contentEquals(b))
            }
        }
        val heads = profiles.map { (n, p) -> Triple(n, p, renderChunked(p, SR * 3)) }
        var comparedPairs = 0
        for (i in heads.indices) for (j in i + 1 until heads.size) {
            val (na, pa, xa) = heads[i]
            val (nb, pb, xb) = heads[j]
            val sameMusic = pa.rootNoteHz == pb.rootNoteHz && pa.scaleIntervals == pb.scaleIntervals &&
                pa.chordProgressionDegrees == pb.chordProgressionDegrees
            if (sameMusic) continue
            comparedPairs++
            var diff = 0.0
            var norm = 0.0
            for (k in xa.indices) { diff += (xa[k] - xb[k]).pow(2); norm += xa[k] * xa[k] }
            val rel = sqrt(diff / norm)
            assertTrue("$na vs $nb: profiles are not audibly different (relative distance $rel)", rel > 0.05)
        }
        assertTrue("too few profile pairs compared ($comparedPairs)", comparedPairs >= 10)
    }

    /**
     * Guards streaming: the engine renders 50 ms chunks on a thread, and any state that is reset or
     * lost at a call boundary (a phase or envelope local instead of a field, an off-by-one when a
     * chunk ends mid-crossfade) would only show up as a chunk-boundary artifact. One 44100-frame
     * `render` must equal 441 renders of 100 frames EXACTLY, for the 16-bit output, and the double
     * output must be identical for chunk sizes 1, 7 and 4410, including across a chord change
     * (short-hold copy of every profile, so a crossfade start and end fall inside the window).
     */
    @Test
    fun `output is identical regardless of how it is chunked`() {
        for ((name, p0) in distinctProfiles()) {
            val p = p0.copy(chordDurationSeconds = 0.5f, crossfadeSeconds = 0.3f)
            val whole = ShortArray(SR * 2)
            PadSynthState(p).render(whole)
            val pieces = ShortArray(SR * 2)
            val s = PadSynthState(p)
            val piece = ShortArray(200)
            for (k in 0 until SR / 100) {
                s.render(piece)
                System.arraycopy(piece, 0, pieces, k * 200, 200)
            }
            assertTrue("$name: 1x44100 != 441x100 (16-bit)", whole.contentEquals(pieces))

            val reference = renderChunked(p, SR, chunkFrames = SR)
            for (chunk in listOf(1, 7, 4410)) {
                val chunked = renderChunked(p, SR, chunkFrames = chunk)
                assertTrue("$name: f64 output differs for chunk size $chunk", reference.contentEquals(chunked))
            }
        }
    }

    /**
     * Guards the 16-bit conversion, where a scale, sign or channel-order bug could hide: the
     * `ShortArray` from `render` must be exactly the double signal scaled by 32767 and truncated,
     * L then R (so L/R were not swapped relative to the double path), for every profile through a
     * chord change.
     */
    @Test
    fun `render 16-bit output is the double signal scaled by 32767 with L then R interleaving`() {
        for ((name, p0) in distinctProfiles()) {
            val p = p0.copy(chordDurationSeconds = 0.6f, crossfadeSeconds = 0.3f)
            val shorts = ShortArray(SR * 2)
            PadSynthState(p).render(shorts)
            val doubles = renderChunked(p, SR)
            for (i in doubles.indices) {
                assertEquals("$name sample $i", (doubles[i] * 32767).toInt(), shorts[i].toInt())
            }
        }
    }

    /**
     * Guards clipping: with in-spec profiles the signal can never reach full scale (see the
     * analytic ceiling), so `coerceIn` in the 16-bit conversion is invisible on them and its
     * removal would go unnoticed until someone raises a profile's volume, at which point the
     * `Int -> Short` wrap-around turns a merely loud peak into a violent sign flip. A deliberately
     * over-driven pad (`baseVolume` 6) must saturate at exactly +-32767 with the correct sign,
     * never wrap.
     */
    @Test
    fun `an over-driven profile saturates at full scale instead of wrapping around`() {
        val p = tonePad(root = 200f, scale = listOf(0), degrees = listOf(0), voices = 1, base = 6f)
        val shorts = ShortArray(SR * 2)
        PadSynthState(p).render(shorts)
        val reference = renderChunked(p, SR)
        var maxS = Int.MIN_VALUE
        var minS = Int.MAX_VALUE
        for (i in shorts.indices) {
            maxS = maxOf(maxS, shorts[i].toInt())
            minS = minOf(minS, shorts[i].toInt())
            if (abs(reference[i]) > 2.0 / 32767) {
                assertEquals("sample $i sign", reference[i] > 0, shorts[i] > 0)
            }
        }
        assertEquals(32767, maxS)
        assertEquals(-32767, minS)
    }

    // ---------------------------------------------------------------------------------------
    // S7: stereo
    // ---------------------------------------------------------------------------------------

    /**
     * Guards the "genuinely stereo, not mono duplicated" promise. From the code: voice `v` of `N`
     * sits at `voicePan(v, N)` and only `N <= 1` is centered, and the arpeggio is centered, so a
     * pad is mono only when it has a single voice. Every profile with two or more voices must
     * therefore have real inter-channel difference and no silent channel: the RMS of `L - R` must
     * be at least 1% of the RMS of `L` (calibrated, [REFERENCE] profiles only: at least 15%; the
     * shipped pads measure far above that). Profiles with fewer than two voices are skipped here
     * (the engine supports one voice), with a guard that at least as many profiles were checked
     * as [REFERENCE] has multi-voice members. A single-voice pad, by contrast, must be identical
     * on both channels (equal-power center gains), even with the arpeggio on.
     */
    @Test
    fun `multi-voice profiles are genuinely stereo and a single voice is centered`() {
        var checked = 0
        for ((name, p) in distinctProfiles()) {
            if (p.voiceCount < 2) continue
            checked++
            val x = renderChunked(p, SR * 5)
            val frames = x.size / 2
            var l2 = 0.0
            var r2 = 0.0
            var d2 = 0.0
            for (f in 0 until frames) {
                val l = x[2 * f]
                val r = x[2 * f + 1]
                l2 += l * l
                r2 += r * r
                d2 += (l - r) * (l - r)
            }
            assertTrue("$name: silent channel", l2 > 0 && r2 > 0)
            val minRelDiff = if (isReference(p)) 0.15 else 0.01
            assertTrue("$name: channels are (nearly) identical, rel diff ${sqrt(d2 / l2)}", sqrt(d2 / l2) >= minRelDiff)
        }
        val knownMultiVoice = REFERENCE.count { it.voiceCount >= 2 }
        assertTrue("only $checked multi-voice profiles checked, expected at least $knownMultiVoice", checked >= knownMultiVoice)

        val mono = tonePad(root = 220f, scale = listOf(0, 4, 7), degrees = listOf(0), voices = 1, arp = true)
        val y = renderChunked(mono, SR)
        var worst = 0.0
        for (f in 0 until SR) worst = maxOf(worst, abs(y[2 * f] - y[2 * f + 1]))
        assertTrue("single-voice pad (with centered arp) must be L == R, worst gap $worst", worst < 1e-12)
    }

    /**
     * Guards the spatial layout, not merely "L differs from R": voice 0 (the lowest, root) must sit
     * LEFT, the last voice RIGHT, a middle voice in the center, with the level ratios the
     * documented equal-power law implies for pans of -0.6/0/+0.6 (`cos/sin` of `(pan+1) pi/4`,
     * recomputed here independently). Measured with a single-frequency DFT at each voice's own
     * pitch on a pure-sine three-voice pad, where the low-pass affects both channels equally and
     * cancels out of the ratio. A mirrored pan (voice 0 on the right) inverts every ratio.
     */
    @Test
    fun `voices are panned left to right with the equal-power level ratios`() {
        val p = tonePad(root = 200f, scale = listOf(0, 3, 7), degrees = listOf(0), voices = 3)
        val x = renderChunked(p, SR * 2)
        val pans = doubleArrayOf(-0.6, 0.0, 0.6)
        val ls = DoubleArray(3)
        val rs = DoubleArray(3)
        for (v in 0 until 3) {
            val f = chordToneHz(p, 0, v)
            ls[v] = toneAmplitude(x, 0, SR, 2 * SR, f)
            rs[v] = toneAmplitude(x, 1, SR, 2 * SR, f)
            assertTrue("voice $v tone missing (L=${ls[v]} R=${rs[v]})", ls[v] > 1e-3 && rs[v] > 1e-3)
            val angle = (pans[v] + 1.0) * PI / 4.0
            val expectedRatio = cos(angle) / sin(angle)
            assertEquals("voice $v at ${f.toInt()} Hz: L/R amplitude ratio", expectedRatio, ls[v] / rs[v], 0.03 * expectedRatio)
        }
        assertTrue("lowest voice must be left-heavy", ls[0] > rs[0])
        assertTrue("highest voice must be right-heavy", rs[2] > ls[2])
    }

    // ---------------------------------------------------------------------------------------
    // Additional DSP-stage tests: arpeggio layer, low-pass, breathing
    // ---------------------------------------------------------------------------------------

    /**
     * Isolates the plucked arpeggio layer and checks its envelope. The arpeggio adds linearly after
     * breathing and before the low-pass, and neither depends on it, so `render(arp on) - render(arp
     * off)` of otherwise identical profiles IS the arpeggio (through the same low-pass and gain),
     * with no need to peek at private state. Guards: it is dead center (identical on both
     * channels); every note restarts on the `44100 / rate` grid and ATTACKS (the first 0.5 ms of a
     * note stays under 25% of that note's peak, as a 6 ms ramp makes it ~18%, where an instant
     * onset reaches ~80%: the click the attack exists to prevent); its peak is the attack level
     * (63% of full: the attack is a one-pole approach to 1 over 6 ms) times
     * `arpeggioVolume * baseVolume`; and it decays (last 10% of the note under 12% of the peak:
     * the envelope aims for 2% at note end).
     */
    @Test
    fun `arpeggio layer is centered attacks softly peaks at its documented level and decays`() {
        val arpProfiles = distinctProfiles().filter { (_, p) -> p.arpeggioEnabled }
        assertTrue("expected at least one arpeggio profile", arpProfiles.isNotEmpty())
        for ((name, p) in arpProfiles) {
            val frames = SR * 8
            val on = renderChunked(p, frames)
            val off = renderChunked(p.copy(arpeggioEnabled = false), frames)
            val arp = DoubleArray(on.size) { on[it] - off[it] }
            var worstGap = 0.0
            for (f in 0 until frames) worstGap = maxOf(worstGap, abs(arp[2 * f] - arp[2 * f + 1]))
            assertTrue("$name: arpeggio must be dead center (L-R gap $worstGap)", worstGap < 1e-9)

            val note = (FS / p.arpeggioRateHz).toLong().toInt()
            val expectedPeak = p.arpeggioVolume * p.baseVolume * 0.632
            var checked = 0
            var k = SR / note + 1 // first note starting after the 1 s mark (startup ramp is over)
            while ((k + 1) * note < frames) {
                val a = k * note
                val b = a + note
                var notePeak = 0.0
                for (f in a until b) notePeak = maxOf(notePeak, abs(arp[2 * f]))
                assertTrue(
                    "$name note $k: peak $notePeak not within 20% of documented $expectedPeak",
                    abs(notePeak / expectedPeak - 1.0) <= 0.20
                )
                var onset = 0.0
                for (f in a until a + (0.0005 * SR).toInt()) onset = maxOf(onset, abs(arp[2 * f]))
                assertTrue("$name note $k: onset $onset is not soft vs peak $notePeak", onset < 0.25 * notePeak)
                var tail = 0.0
                for (f in (b - note / 10) until b) tail = maxOf(tail, abs(arp[2 * f]))
                assertTrue("$name note $k: tail $tail has not decayed vs peak $notePeak", tail < 0.12 * notePeak)
                checked++
                k++
            }
            assertTrue("$name: checked too few notes ($checked)", checked >= 3)
        }
    }

    /**
     * Guards the one-pole low-pass ("warmth") stage in physical terms. A single centered sine at
     * frequency `f` through a pad with cutoff `fc` must come out with the textbook one-pole
     * magnitude response `|H| = c / sqrt(1 - 2(1-c)cos(w) + (1-c)^2)` with `c = 1 - exp(-2 pi fc / fs)`,
     * measured as RMS against the known input amplitude (centered pan gain cos(pi/4) divided out).
     * Three tones per cutoff (fc/4, fc, 4 fc) and two cutoffs pin both the corner frequency and the
     * slope, and the -3 dB point at `f == fc` is asserted as a plain physical fact (gain in
     * 0.69..0.75), so a wrong coefficient (missing `2`, missing exp, halved, or no filter at all)
     * fails independent of how the formula is written.
     */
    @Test
    fun `low-pass has a minus 3 dB corner at the cutoff and the one-pole slope`() {
        for (fc in listOf(500.0, 2000.0)) {
            for (ratio in listOf(0.25, 1.0, 4.0)) {
                val f = fc * ratio
                val p = tonePad(root = f.toFloat(), scale = listOf(0), degrees = listOf(0), voices = 1, base = 1f, cutoff = fc.toFloat())
                val x = renderChunked(p, SR * 2)
                val measured = rms(x, SR / 2, SR * 2) * sqrt(2.0) / cos(PI / 4) // recover sine amplitude / pan gain
                val c = 1.0 - exp(-2.0 * PI * fc / FS)
                val w = 2.0 * PI * f / FS
                val expected = c / sqrt(1.0 - 2.0 * (1 - c) * cos(w) + (1 - c) * (1 - c))
                assertEquals("fc=$fc f=$f: gain vs one-pole response", expected, measured, 0.015 * expected)
                if (ratio == 1.0) assertTrue("gain at cutoff should be about -3 dB, was $measured", measured in 0.69..0.75)
            }
        }
    }

    /**
     * Guards the breathing LFO stage: the pad's level must follow `1 - depth + depth * sin(2 pi rate t)`
     * (range `1 - 2 depth .. 1`), so it never gets louder than the un-breathed level (a term with
     * the wrong sign or `1 + depth*sin` would push above it) and dips by exactly the profile's
     * `breathingDepth`. Measured on a pure 1 kHz sine at a fast 0.5 Hz rate and depth 0.3 by 20 ms
     * windowed amplitude across two full periods, compared window by window to the formula (3%).
     */
    @Test
    fun `breathing follows one minus depth plus depth sine and never exceeds unity`() {
        val depth = 0.3
        val rate = 0.5
        val p = tonePad(root = 1000f, scale = listOf(0), degrees = listOf(0), voices = 1, base = 1f,
            cutoff = 21000f, breathingRate = rate.toFloat(), breathingDepth = depth.toFloat())
        val x = renderChunked(p, SR * 5)
        val win = SR / 50
        val amplitudes = ArrayList<Pair<Double, Double>>()
        var f = (0.5 * SR).toInt()
        while (f + win <= SR * 5) {
            val mid = (f + win / 2) / FS
            amplitudes += mid to rms(x, f, f + win) * sqrt(2.0)
            f += win
        }
        val maxAmp = amplitudes.maxOf { it.second }
        val minAmp = amplitudes.minOf { it.second }
        // Un-breathed amplitude = max of formula = 1.0; ratio min/max = 1 - 2 depth = 0.4.
        assertEquals("dip depth (min/max)", 1.0 - 2.0 * depth, minAmp / maxAmp, 0.03)
        for ((t, a) in amplitudes) {
            val expected = 1.0 - depth + depth * sin(2.0 * PI * rate * t)
            assertEquals("breathing at t=$t", expected, a / maxAmp, 0.03)
        }
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private companion object {
        const val SR = 44100
        const val FS = 44100.0
        const val CHUNK_FRAMES = SR / 20
        const val WINDOW_FRAMES = SR * 30
        const val STARTUP_SECONDS = 0.35
        const val FADE_FRAMES = (SR * 0.25).toInt() // 11025, same as the engine's FADE_OUT_SECONDS tail

        /**
         * The twelve shipped profile shapes the calibrated thresholds were measured on (see the
         * class KDoc). Direct property references, so removing or renaming one is a compile error
         * here rather than a silent change of what the calibration covers.
         */
        val REFERENCE: List<MusicProfile> = listOf(
            MusicProfiles.CHESS, MusicProfiles.CHECKERS, MusicProfiles.MANCALA, MusicProfiles.DOMINOES,
            MusicProfiles.DOTS_AND_BOXES, MusicProfiles.CONNECT_FOUR, MusicProfiles.REVERSI,
            MusicProfiles.PUZZLE_FOCUS, MusicProfiles.UNO, MusicProfiles.TIC_TAC_TOE,
            MusicProfiles.AIR_HOCKEY, MusicProfiles.TOWER_DEFENCE
        )

        fun isReference(p: MusicProfile): Boolean = REFERENCE.any { it === p }

        /** The discovered distinct profiles that are [REFERENCE] members (all of them, checked). */
        fun referenceProfiles(): List<Pair<String, MusicProfile>> {
            val out = distinctProfiles().filter { isReference(it.second) }
            check(out.size == REFERENCE.size) { "reflective discovery lost reference profiles: ${out.map { it.first }}" }
            return out
        }

        /**
         * The 30 s stereo signal of a profile as single-precision floats (10.6 MB, half of the
         * double array), rendered once and shared by the S1 sanity test and the click tests. Held by
         * SoftReference so 12 of them can never push the small default test heap into an
         * OutOfMemoryError: under pressure the GC drops them and they are simply re-rendered.
         * Callers must not modify the returned array.
         */
        private val signal30Cache = HashMap<String, SoftReference<FloatArray>>()

        @Synchronized
        fun signal30(name: String, p: MusicProfile): FloatArray {
            signal30Cache[name]?.get()?.let { return it }
            val synth = PadSynthState(p)
            val out = FloatArray(WINDOW_FRAMES * 2)
            var done = 0
            while (done < WINDOW_FRAMES) {
                val n = minOf(CHUNK_FRAMES, WINDOW_FRAMES - done)
                val c = synth.renderF64(n)
                for (k in c.indices) out[done * 2 + k] = c[k].toFloat()
                done += n
            }
            signal30Cache[name] = SoftReference(out)
            return out
        }

        fun renderChunked(p: MusicProfile, frames: Int, chunkFrames: Int = CHUNK_FRAMES): DoubleArray {
            val synth = PadSynthState(p)
            val out = DoubleArray(frames * 2)
            var done = 0
            while (done < frames) {
                val n = minOf(chunkFrames, frames - done)
                val c = synth.renderF64(n)
                System.arraycopy(c, 0, out, done * 2, c.size)
                done += n
            }
            return out
        }

        /** Independent recomputation of PadSynthState's Float-seconds * 44100 -> Long, min 1. */
        fun holdFrames(p: MusicProfile): Int = (p.chordDurationSeconds * SR).toLong().coerceAtLeast(1).toInt()
        fun crossfadeFrames(p: MusicProfile): Int = (p.crossfadeSeconds * SR).toLong().coerceAtLeast(1).toInt()

        fun pcm(v: Double): Short = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()

        private fun arpVolume(p: MusicProfile): Double = if (p.arpeggioEnabled) p.arpeggioVolume.toDouble() else 0.0

        /** Whole-signal analytic ceiling; sqrt(2) is the cos + sin weighting of two crossfading banks. */
        fun peakCeiling(p: MusicProfile): Double = p.baseVolume * (sqrt(2.0) + arpVolume(p))

        /** Ceiling valid while no crossfade is running (the first chord hold). */
        fun startupCeiling(p: MusicProfile): Double = p.baseVolume * (1.0 + arpVolume(p))

        fun rms(x: DoubleArray, fromFrame: Int, toFrame: Int): Double {
            var s = 0.0
            for (f in fromFrame until toFrame) { s += x[2 * f] * x[2 * f] + x[2 * f + 1] * x[2 * f + 1] }
            return sqrt(s / ((toFrame - fromFrame) * 2))
        }

        fun peak(x: FloatArray, ch: Int): Double {
            var m = 0.0
            for (f in 0 until x.size / 2) m = maxOf(m, abs(x[2 * f + ch].toDouble()))
            return m
        }

        fun maxFirstDifference(x: FloatArray, ch: Int): Pair<Double, Int> {
            var worst = 0.0
            var at = 0
            for (f in 1 until x.size / 2) {
                val d = abs(x[2 * f + ch].toDouble() - x[2 * (f - 1) + ch].toDouble())
                if (d > worst) { worst = d; at = f }
            }
            return worst to at
        }

        fun maxSecondDifference(x: FloatArray, ch: Int): Pair<Double, Int> {
            var worst = 0.0
            var at = 0
            for (f in 2 until x.size / 2) {
                val d = abs(x[2 * f + ch].toDouble() - 2.0 * x[2 * (f - 1) + ch] + x[2 * (f - 2) + ch])
                if (d > worst) { worst = d; at = f }
            }
            return worst to at
        }

        /** Amplitude of the sinusoid at [freq] in frames [from, to) of channel [ch] (single-bin DFT). */
        fun toneAmplitude(x: DoubleArray, ch: Int, from: Int, to: Int, freq: Double): Double {
            var re = 0.0
            var im = 0.0
            val w = 2.0 * PI * freq / FS
            for (f in from until to) {
                val v = x[2 * f + ch]
                re += v * cos(w * f)
                im += v * sin(w * f)
            }
            return 2.0 * sqrt(re * re + im * im) / (to - from)
        }

        /** Independent chord-tone math: root * 2^((scale[idx] + 12*octave)/12), stacked thirds. */
        fun chordToneHz(p: MusicProfile, degree: Int, voice: Int): Double {
            val step = degree + 2 * voice
            val octave = Math.floorDiv(step, p.scaleIntervals.size)
            val idx = Math.floorMod(step, p.scaleIntervals.size)
            return p.rootNoteHz.toDouble() * 2.0.pow((p.scaleIntervals[idx] + 12 * octave) / 12.0)
        }

        /** Highest frequency each voice ever plays over the whole progression. */
        private fun peakVoiceHz(p: MusicProfile): DoubleArray = DoubleArray(p.voiceCount) { v ->
            p.chordProgressionDegrees.maxOf { chordToneHz(p, it, v) }
        }

        private fun panGain(p: MusicProfile, v: Int, ch: Int): Double {
            val pan = if (p.voiceCount <= 1) 0.0 else (v.toDouble() / (p.voiceCount - 1) * 2.0 - 1.0) * 0.6
            val angle = (pan + 1.0) * PI / 4.0
            return if (ch == 0) cos(angle) else sin(angle)
        }

        /**
         * Rigorous upper bound on |x[n] - x[n-1]| (channel [ch]). Per voice: a sine has slope
         * (1-b) * 2 pi f / fs, a triangle 4 f / fs; each voice is scaled by 1/N and its pan gain;
         * during a crossfade two banks overlap (cos*A + sin*B <= sqrt(2) max) hence sqrt(2).
         * Arpeggio: slope 2 pi f_arp / fs (its notes are one octave up, so at most twice the
         * highest chord tone), plus the 2%-residual retrigger step (0.03) and the attack ramp
         * increment (1/264). Breathing/startup/crossfade-gain derivatives are ~1e-5 and covered by
         * the 1e-3 slack. Everything is scaled by baseVolume; the low-pass cannot raise it.
         */
        fun firstDifferenceBound(p: MusicProfile, ch: Int): Double {
            val fMax = peakVoiceHz(p)
            val b = p.waveformBrightness.toDouble()
            var pad = 0.0
            for (v in 0 until p.voiceCount) {
                pad += (1.0 / p.voiceCount) * panGain(p, v, ch) * ((1 - b) * 2 * PI * fMax[v] / FS + b * 4 * fMax[v] / FS)
            }
            val arp = if (p.arpeggioEnabled) p.arpeggioVolume * (2 * PI * 2 * fMax.max() / FS + 0.03 + 0.004) else 0.0
            return p.baseVolume * (sqrt(2.0) * pad + arp + 1e-3)
        }

        /**
         * Rigorous upper bound on |x[n] - 2x[n-1] + x[n-2]|. Sine: (1-b) (2 pi f / fs)^2;
         * triangle corner (slope flips sign): b * 2 * (4 f / fs) = 8 b f / fs; arpeggio: sine
         * curvature plus the retrigger step (0.03) and twice the attack increment (0.008);
         * 5e-4 slack covers breathing/startup cross terms.
         */
        fun secondDifferenceBound(p: MusicProfile, ch: Int): Double {
            val fMax = peakVoiceHz(p)
            val b = p.waveformBrightness.toDouble()
            val w = 2 * PI / FS
            var pad = 0.0
            for (v in 0 until p.voiceCount) {
                pad += (1.0 / p.voiceCount) * panGain(p, v, ch) * ((1 - b) * (w * fMax[v]).pow(2) + b * 8 * fMax[v] / FS)
            }
            val arp = if (p.arpeggioEnabled) p.arpeggioVolume * ((w * 2 * fMax.max()).pow(2) + 0.03 + 0.008) else 0.0
            return p.baseVolume * (sqrt(2.0) * pad + arp + 5e-4)
        }

        /** A synthetic pad with everything not under test switched off (pure sines, no breathing,
         *  no arpeggio, very long hold so no chord change unless a test wants one). */
        fun tonePad(
            root: Float, scale: List<Int>, degrees: List<Int>, voices: Int,
            base: Float = 0.5f, hold: Float = 100f, crossfade: Float = 1f, cutoff: Float = 20000f,
            breathingRate: Float = 0.08f, breathingDepth: Float = 0f, arp: Boolean = false
        ) = MusicProfile(
            rootNoteHz = root, scaleIntervals = scale, chordProgressionDegrees = degrees,
            chordDurationSeconds = hold, voiceCount = voices, baseVolume = base, crossfadeSeconds = crossfade,
            breathingRateHz = breathingRate, breathingDepth = breathingDepth, waveformBrightness = 0f,
            lowpassCutoffHz = cutoff, arpeggioEnabled = arp
        )
    }
}
