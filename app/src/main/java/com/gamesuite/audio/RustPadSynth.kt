package com.gamesuite.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Step 3 of `docs/RUST_AUDIO_CORE_PLAN.md`: the Kotlin-side translation layer around the
 * UniFFI-generated [com.gamesuite.audio.rust.PadSynth] — mirrors
 * `com.gamesuite.games.airhockey.AirHockeyRustGame`'s own translation-boundary role (see that
 * class's own `toAirHockeyState()` for the equivalent pattern on the Air Hockey pilot).
 *
 * Exposes the **exact same method signatures** [PadSynthState][render]/[renderFadeOut] already
 * has in the private `PadSynthState` class this ports (`AmbientMusicEngine.kt:529-551`) — a
 * `ShortArray`-writing `render`/`renderFadeOut` pair, not the raw `bytes`-returning generated
 * `PadSynth.render`/`renderFadeOut`. All `bytes` -> interleaved little-endian `Short` conversion
 * lives here, once, rather than scattered into `AmbientMusicEngine` — the whole reason this file
 * exists as its own translation boundary instead of having `AmbientMusicEngine` call the
 * generated bindings directly.
 *
 * `AmbientMusicEngine.start()`/`runGeneratorLoop()`/`stop()` are deliberately NOT touched by this
 * step (per the plan) — they keep constructing `PadSynthState` today. Step 6 (cutover, explicitly
 * out of scope for this pilot until Step 5's GC-pause measurement lands) is the only step that
 * flips `runGeneratorLoop`'s single `PadSynthState(profile)` construction to
 * `RustPadSynth(profile)` — keeping that swap to one line in one place, deliberately, the same
 * "single seam" discipline `AirHockeyRustGame`'s own KDoc calls out.
 */
internal class RustPadSynth(profile: MusicProfile) {

    /** One native synth per instance, mirroring one `PadSynthState` instance today. Freed via
     *  UniFFI's registered Cleaner when this object becomes unreachable — same "no explicit
     *  dispose" lifecycle [com.gamesuite.games.airhockey.AirHockeyRustGame] already has for
     *  [com.gamesuite.sim.AirHockeySim]; [close] is available for a caller that wants
     *  deterministic cleanup (this pilot's own correctness test uses it), but nothing in the real
     *  generator-loop path calls it today (matches `AirHockeyRustGame`'s own precedent). */
    private val synth = com.gamesuite.audio.rust.PadSynth(profile.toFfi())

    /** Same call shape as `PadSynthState.render(out: ShortArray)` -- [out] is interleaved stereo
     *  (L,R per frame), `out.size / 2` frames. */
    fun render(out: ShortArray) {
        val frameCount = out.size / 2
        val bytes = synth.render(frameCount)
        bytesToShorts(bytes, out)
    }

    /** Same call shape as `PadSynthState.renderFadeOut(out: ShortArray)`. */
    fun renderFadeOut(out: ShortArray) {
        val frameCount = out.size / 2
        val bytes = synth.renderFadeOut(frameCount)
        bytesToShorts(bytes, out)
    }

    /** Releases the native Rust object deterministically -- see this class's own KDoc on why
     *  nothing in the real generator-loop path calls this today. */
    fun close() {
        synth.close()
    }
}

/**
 * `bytes` (interleaved stereo, 16-bit LE PCM straight off the UniFFI boundary) -> [out]
 * (interleaved stereo `Short`s) -- the one place this byte-decoding logic lives, per this file's
 * own KDoc. `bytes.size` is always `out.size * 2` (4 bytes/frame vs. 2 shorts/frame) for a
 * well-formed call; only ever converts as many shorts as `out` has room for.
 */
private fun bytesToShorts(bytes: ByteArray, out: ShortArray) {
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
}

/**
 * [MusicProfile] (Kotlin) -> [com.gamesuite.audio.rust.MusicProfileFfi] (UniFFI-generated wire
 * type) -- a straight field-for-field copy, same field names/order as
 * `docs/RUST_AUDIO_CORE_ADR.md`'s FFI Surface section. Computed once at [RustPadSynth]
 * construction, not per-render-call.
 *
 * `internal`, not `private`: `RustPadSynthCorrectnessTest.kt` (`docs/RUST_AUDIO_CORE_PLAN.md` Step
 * 4) reuses this directly to construct a raw `com.gamesuite.audio.rust.PadSynth` for its
 * sample-by-sample diff, rather than duplicating this mapping a second time in the test.
 */
internal fun MusicProfile.toFfi(): com.gamesuite.audio.rust.MusicProfileFfi =
    com.gamesuite.audio.rust.MusicProfileFfi(
        rootNoteHz = rootNoteHz,
        scaleIntervals = scaleIntervals,
        chordProgressionDegrees = chordProgressionDegrees,
        chordDurationSeconds = chordDurationSeconds,
        voiceCount = voiceCount,
        baseVolume = baseVolume,
        crossfadeSeconds = crossfadeSeconds,
        breathingRateHz = breathingRateHz,
        breathingDepth = breathingDepth,
        waveformBrightness = waveformBrightness,
        lowpassCutoffHz = lowpassCutoffHz,
        arpeggioEnabled = arpeggioEnabled,
        arpeggioRateHz = arpeggioRateHz,
        arpeggioVolume = arpeggioVolume
    )
