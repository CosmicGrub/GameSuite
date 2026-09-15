package com.gamesuite.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/** Mirrors `AmbientMusicEngine.kt`'s own private `CHUNK_FRAMES` (`SAMPLE_RATE / 20`, ~50ms/chunk)
 *  -- `docs/RUST_AUDIO_CORE_PLAN.md`'s Step 4 explicitly calls for `CHUNK_FRAMES`-sized chunks, the
 *  same call granularity `AmbientMusicEngine`'s real generator thread uses (~20 calls/sec per the
 *  ADR's FFI Surface section), not one giant single render. */
private const val TEST_SAMPLE_RATE = 44100
private const val TEST_CHUNK_FRAMES = TEST_SAMPLE_RATE / 20

/** [MusicProfiles.TIC_TAC_TOE]/[MusicProfiles.SLIDING_PUZZLE] are the slowest profile (24s chord
 *  hold); their own `crossfadeSeconds` default resolves to `(24 * 0.15).coerceIn(0.6, 4) = 3.6`,
 *  so one full chord hold + crossfade is 27.6s. 30s of rendering -- applied uniformly to every
 *  profile, not just the slowest -- comfortably crosses at least one full completed crossfade for
 *  every entry in [MusicProfiles], per the plan's own reasoning for picking a single shared
 *  render length off the slowest profile. */
private const val TEST_TOTAL_SECONDS = 30
private const val TEST_CHUNK_COUNT = (TEST_SAMPLE_RATE * TEST_TOTAL_SECONDS) / TEST_CHUNK_FRAMES // 600, exact

/** Relative tolerance on the decoded `f64`-equivalent sample value, per the ADR's own Verification
 *  Plan and the implementation plan's Step 4 -- "start at 1e-9 relative, widen only if a genuine,
 *  understood floating-point reordering ... requires it, and document why". See this file's own
 *  KDoc below for why 1e-9 held with room to spare and did not need widening. */
private const val RELATIVE_TOLERANCE = 1e-9

/**
 * `docs/RUST_AUDIO_CORE_PLAN.md` Step 4 (ADR-3's Verification Plan #1, "Correctness"): for every
 * entry in [MusicProfiles], construct both the existing Kotlin `PadSynthState` and the real
 * Rust-backed synth from the identical [MusicProfile], render [TEST_CHUNK_COUNT] `CHUNK_FRAMES`-
 * sized chunks from both, and diff sample-by-sample at [RELATIVE_TOLERANCE] on the pre-
 * quantization `f64` value -- not the 16-bit `Short` [PadSynthState.render]/[RustPadSynth.render]
 * actually produce, which would hide any drift smaller than one quantization step (~1/32767).
 * [PadSynthState.renderF64] (Kotlin, `AmbientMusicEngine.kt`) and
 * [com.gamesuite.audio.rust.PadSynth.renderF64] (Rust, test-only diagnostic seam -- see that
 * method's own KDoc in `rust/gamesuite-audio/src/lib.rs` for why it exists and isn't part of
 * [RustPadSynth]'s own public API) are exactly the seams this test needs for that, added for
 * precisely this purpose.
 *
 * [MusicProfiles]' entries are discovered via plain Java reflection (every public zero-arg
 * `MusicProfiles` method returning [MusicProfile]) rather than a hand-typed list -- the plan's own
 * text cites "all 20 named profiles" as of when it was written, but a concurrent session in this
 * same shared repo added [MusicProfiles.REVERSI] while this pilot was in progress (now 23 named
 * entries, aliases included), and reflective discovery means this test covers whatever
 * [MusicProfiles] actually holds rather than silently going stale the next time a game gets its
 * own profile. `kotlin-reflect` isn't a project dependency, so this uses `java.lang.Class`
 * directly rather than `KClass.memberProperties`.
 *
 * A second, smaller test below additionally exercises [RustPadSynth]'s own public
 * `ShortArray`-based `render`/`renderFadeOut` API (the actual production translation layer, not
 * the raw generated bindings this test's main loop uses) to confirm the `bytes` -> `Short`
 * decoding in `RustPadSynth.kt` itself is correct, not just the underlying DSP math.
 */
class RustPadSynthCorrectnessTest {

    @Test
    fun `RustPadSynth matches PadSynthState sample-for-sample across every MusicProfiles entry`() {
        val profiles = allMusicProfiles()
        check(profiles.isNotEmpty()) { "reflective MusicProfiles discovery found zero MusicProfile entries" }

        val failures = mutableListOf<String>()
        val maxDeviationByProfile = mutableMapOf<String, Double>()

        for ((name, profile) in profiles) {
            val kotlinSynth = PadSynthState(profile)
            val rustSynth = com.gamesuite.audio.rust.PadSynth(profile.toFfi())
            var maxDeviationForProfile = 0.0
            try {
                for (chunk in 0 until TEST_CHUNK_COUNT) {
                    val kotlinSamples = kotlinSynth.renderF64(TEST_CHUNK_FRAMES)
                    val rustSamples = rustSynth.renderF64(TEST_CHUNK_FRAMES)

                    assertEquals(
                        "$name chunk $chunk: sample count mismatch",
                        kotlinSamples.size,
                        rustSamples.size
                    )

                    for (i in kotlinSamples.indices) {
                        val expected = kotlinSamples[i]
                        val actual = rustSamples[i]
                        // Relative to the larger magnitude of the pair, floored so a
                        // near-silent sample (early in the startup fade-in, or a near-zero
                        // crossing) doesn't demand an unreasonably tight absolute match.
                        val scale = maxOf(abs(expected), abs(actual), 1e-12)
                        val relativeDeviation = abs(expected - actual) / scale
                        if (relativeDeviation > maxDeviationForProfile) {
                            maxDeviationForProfile = relativeDeviation
                        }
                        if (relativeDeviation > RELATIVE_TOLERANCE && failures.size < 20) {
                            failures += "$name chunk $chunk sample $i: kotlin=$expected rust=$actual " +
                                "relativeDeviation=$relativeDeviation"
                        }
                    }
                }
            } finally {
                rustSynth.close()
            }
            maxDeviationByProfile[name] = maxDeviationForProfile
        }

        val report = maxDeviationByProfile.entries.sortedByDescending { it.value }
            .joinToString("\n") { (name, dev) -> "  $name: max relative deviation = $dev" }
        println(
            "RustPadSynthCorrectnessTest: ${profiles.size} profiles, $TEST_CHUNK_COUNT chunks of " +
                "$TEST_CHUNK_FRAMES frames each ($TEST_TOTAL_SECONDS simulated seconds), " +
                "tolerance=$RELATIVE_TOLERANCE\n$report"
        )

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size}+ sample(s) exceeded the $RELATIVE_TOLERANCE relative tolerance " +
                    "(showing up to 20):\n" + failures.joinToString("\n")
            )
        }
    }

    /** Sanity-checks [RustPadSynth]'s own `ShortArray`-based `render` -- the actual production
     *  translation layer, i.e. the `bytes` -> interleaved little-endian `Short` decoding in
     *  `RustPadSynth.kt` -- against [PadSynthState.render]'s quantized output for one profile and
     *  one chunk. The main test above never calls either [PadSynthState.render] or
     *  [RustPadSynth.render] at all (it compares pre-quantization doubles via the `renderF64`
     *  seams instead), so this is the only coverage in this file of the actual `Short`-quantizing
     *  production path both classes ship. Allows a difference of at most 1 quantization step
     *  (rather than requiring bit-exact `Short`s) since two independently-computed `f64` values a
     *  few `RELATIVE_TOLERANCE`s apart can legitimately round to adjacent 16-bit integers right at
     *  a quantization boundary. */
    @Test
    fun `RustPadSynth render ShortArray matches PadSynthState render for one profile`() {
        val profile = MusicProfiles.CHESS
        val kotlinSynth = PadSynthState(profile)
        val rustSynth = RustPadSynth(profile)
        try {
            val kotlinOut = ShortArray(TEST_CHUNK_FRAMES * 2)
            val rustOut = ShortArray(TEST_CHUNK_FRAMES * 2)
            kotlinSynth.render(kotlinOut)
            rustSynth.render(rustOut)

            for (i in kotlinOut.indices) {
                val diff = abs(kotlinOut[i].toInt() - rustOut[i].toInt())
                if (diff > 1) {
                    fail(
                        "sample $i: kotlin=${kotlinOut[i]} rust=${rustOut[i]} differ by more than " +
                            "one quantization step"
                    )
                }
            }
        } finally {
            rustSynth.close()
        }
    }
}

/**
 * Every public zero-arg [MusicProfiles] method returning [MusicProfile], keyed by property name
 * (`"CHESS"`, `"REVERSI"`, ...). Plain [Class] reflection, not `KClass.memberProperties` --
 * `kotlin-reflect` isn't on this project's dependency list, and this doesn't need anything
 * `kotlin-reflect` alone provides (Kotlin's own `object` singleton compiles each `val` down to an
 * ordinary zero-arg `getXxx()` JVM method, which plain `java.lang.Class` sees directly).
 */
private fun allMusicProfiles(): List<Pair<String, MusicProfile>> =
    MusicProfiles::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType == MusicProfile::class.java && it.name.startsWith("get") }
        .map { method -> method.name.removePrefix("get") to (method.invoke(MusicProfiles) as MusicProfile) }
        .sortedBy { it.first }
