//! GameSuite real-time audio DSP core — AmbientMusicEngine pilot (stub).
//!
//! Step 1 of `docs/RUST_AUDIO_CORE_PLAN.md`: crate skeleton only, no real DSP
//! math yet. `PadSynth::render`/`render_fade_out` return silent interleaved
//! stereo 16-bit LE PCM of the requested length — this proves the UniFFI
//! marshaling/toolchain path end-to-end for a *second* crate in the `rust/`
//! workspace, mirroring exactly how `rust/gamesuite-sim` itself started (see
//! that crate's `src/lib.rs` for the `#[uniffi::export]` / `uniffi::Object` /
//! `uniffi::Record` style this file follows). Step 2 (see the plan) ports the
//! real DSP — `PadSynthState` in
//! `app/src/main/java/com/gamesuite/audio/AmbientMusicEngine.kt` — into the
//! body of `render`/`render_fade_out` below; the FFI shape here is already
//! final (matches `docs/RUST_AUDIO_CORE_ADR.md`'s FFI Surface section) so
//! that port won't need a second breaking change to this file's public API.
//!
//! Exposed to Kotlin via UniFFI's proc-macro API — no `.udl` file, same as
//! `gamesuite-sim`. See `rust/uniffi-bindgen/src/main.rs` for how the Kotlin
//! bindings get generated from this (a shared, generic bindgen binary reads
//! the `#[uniffi::export]` metadata straight out of the compiled cdylib in
//! "library mode" — no new bindgen crate needed for this second cdylib).

use std::sync::Arc;

uniffi::setup_scaffolding!("gamesuite_audio");

/// Wire-format mirror of Kotlin's `MusicProfile`
/// (`app/src/main/java/com/gamesuite/audio/AmbientMusicEngine.kt`) — matches
/// `docs/RUST_AUDIO_CORE_ADR.md`'s FFI Surface section field-for-field (14
/// fields, same order). Step 1 doesn't read these fields yet (no DSP math in
/// `render`/`render_fade_out` below), but the shape is final now so Step 2's
/// real port doesn't need a second FFI-breaking change.
#[derive(uniffi::Record, Clone, Debug)]
pub struct MusicProfileFfi {
    pub root_note_hz: f32,
    pub scale_intervals: Vec<i32>,
    pub chord_progression_degrees: Vec<i32>,
    pub chord_duration_seconds: f32,
    pub voice_count: i32,
    pub base_volume: f32,
    pub crossfade_seconds: f32,
    pub breathing_rate_hz: f32,
    pub breathing_depth: f32,
    pub waveform_brightness: f32,
    pub lowpass_cutoff_hz: f32,
    pub arpeggio_enabled: bool,
    pub arpeggio_rate_hz: f32,
    pub arpeggio_volume: f32,
}

/// The FFI-facing handle Kotlin holds one of per `AmbientMusicEngine`
/// generator-thread lifetime (mirrors one `PadSynthState` instance today). A
/// UniFFI `Object` — shared behind an `Arc` across the FFI boundary, same
/// convention as `gamesuite-sim::AirHockeySim`.
///
/// Step 1 stub: holds the profile but doesn't read it yet — `render`/
/// `render_fade_out` only prove the `bytes`-returning FFI call shape works,
/// returning silence of the requested length. No `Mutex` yet either (unlike
/// `AirHockeySim`): nothing here is mutated until Step 2 adds real
/// crossfade/oscillator state.
#[derive(uniffi::Object)]
pub struct PadSynth {
    #[allow(dead_code)] // read once Step 2 ports the real DSP
    profile: MusicProfileFfi,
}

#[uniffi::export]
impl PadSynth {
    #[uniffi::constructor]
    pub fn new(profile: MusicProfileFfi) -> Arc<Self> {
        Arc::new(Self { profile })
    }

    /// Interleaved stereo, 16-bit LE PCM — `frame_count * 4` bytes (2
    /// channels × 2 bytes/sample), matching the ADR's FFI Surface exactly.
    /// Step 1 stub: silence (all-zero bytes) of the requested length — proves
    /// marshaling across the boundary, not the DSP math.
    pub fn render(&self, frame_count: i32) -> Vec<u8> {
        silent_pcm16_stereo(frame_count)
    }

    /// Same call shape as [`PadSynth::render`]; Step 2 gives this an actual
    /// fade-out envelope. Step 1 stub: silence, identical to `render`.
    pub fn render_fade_out(&self, frame_count: i32) -> Vec<u8> {
        silent_pcm16_stereo(frame_count)
    }
}

/// `frame_count * 4` zero bytes. `frame_count` is a plain FFI `i32` (per the
/// ADR's surface, not a `u32`) — a negative value is never expected from the
/// real Kotlin caller, but this clamps to 0 rather than panicking across the
/// UniFFI boundary on a malformed call.
fn silent_pcm16_stereo(frame_count: i32) -> Vec<u8> {
    let frames = frame_count.max(0) as usize;
    vec![0u8; frames * 4]
}

// ---------------------------------------------------------------------------
// Native unit tests — run via `cargo test`, no JNI/.so loading involved, same
// convention as gamesuite-sim's own #[cfg(test)] mod. Step 1 only has the
// marshaling shape to test; Step 2 adds the real DSP correctness tests listed
// in docs/RUST_AUDIO_CORE_PLAN.md's Step 2 section.
// ---------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    fn test_profile() -> MusicProfileFfi {
        MusicProfileFfi {
            root_note_hz: 220.0,
            scale_intervals: vec![0, 2, 4, 5, 7, 9, 11],
            chord_progression_degrees: vec![0, 3, 4],
            chord_duration_seconds: 4.0,
            voice_count: 4,
            base_volume: 0.5,
            crossfade_seconds: 1.0,
            breathing_rate_hz: 0.1,
            breathing_depth: 0.05,
            waveform_brightness: 0.5,
            lowpass_cutoff_hz: 2000.0,
            arpeggio_enabled: false,
            arpeggio_rate_hz: 4.0,
            arpeggio_volume: 0.2,
        }
    }

    /// Mirrors the Step 2 plan's `render_produces_correct_byte_length` test
    /// name/intent, scoped to what Step 1 actually has: byte-count shape only.
    #[test]
    fn render_produces_correct_byte_length() {
        let synth = PadSynth::new(test_profile());
        assert_eq!(synth.render(0).len(), 0);
        assert_eq!(synth.render(100).len(), 400);
        assert_eq!(synth.render_fade_out(100).len(), 400);
    }

    #[test]
    fn render_is_silent() {
        let synth = PadSynth::new(test_profile());
        assert!(synth.render(64).iter().all(|&b| b == 0));
        assert!(synth.render_fade_out(64).iter().all(|&b| b == 0));
    }

    /// A negative frame_count is never expected from the real Kotlin caller,
    /// but the FFI signature is a plain i32 — must not panic across the
    /// UniFFI boundary.
    #[test]
    fn negative_frame_count_does_not_panic() {
        let synth = PadSynth::new(test_profile());
        assert_eq!(synth.render(-5).len(), 0);
    }
}
