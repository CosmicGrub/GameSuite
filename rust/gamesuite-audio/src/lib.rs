//! GameSuite real-time audio DSP core — AmbientMusicEngine pilot.
//!
//! Step 2 of `docs/RUST_AUDIO_CORE_PLAN.md`: a faithful, field-for-field port of
//! `PadSynthState` (the private DSP class in
//! `app/src/main/java/com/gamesuite/audio/AmbientMusicEngine.kt:469-701`) plus
//! its two free pure-math helpers `equalPowerPanGains`/`voicePan`
//! (`AmbientMusicEngine.kt:78-95`). `f64` throughout, matching Kotlin's
//! `Double` — this is a correctness pilot, not an `f32`/SIMD perf pass (see
//! `docs/RUST_AUDIO_CORE_ADR.md`'s own named trade-off).
//!
//! **Porting note on Kotlin numeric promotion**: Kotlin's `PadSynthState`
//! stores several `MusicProfile` fields as `Float` but every arithmetic use
//! site pairs them with a `Double` operand, which implicitly (and losslessly)
//! widens the `Float` to `Double` before the operation runs. This crate
//! widens each such field to `f64` exactly once, at construction
//! ([ResolvedProfile]) — bit-identical to Kotlin's per-use-site widening,
//! since `f32`→`f64` widening is always exact (every `f32` value is exactly
//! representable in `f64`). The two exceptions, ported with matching care:
//! `chord_hold_samples`/`crossfade_samples` are derived via `Float * Int`
//! Kotlin arithmetic (staying in `Float` precision until the final
//! truncating conversion to samples — see `chordHoldSamples`/
//! `crossfadeSamples` in the Kotlin source, which multiply by the raw `Int`
//! `SAMPLE_RATE`, not `sampleRateD`), so this port does the same multiply in
//! `f32` before truncating — see `SAMPLE_RATE_F32` below and its two call
//! sites, the one place this crate deliberately does NOT pre-widen to `f64`.
//!
//! Exposed to Kotlin via UniFFI's proc-macro API — no `.udl` file, same as
//! `gamesuite-sim`. See `rust/uniffi-bindgen/src/main.rs` for how the Kotlin
//! bindings get generated from this.
//!
//! **`render_f64` is a test-only diagnostic seam, not part of ADR-3's FFI
//! Surface.** The ADR's FFI Surface section names exactly two methods
//! (`render`/`render_fade_out`, both returning quantized 16-bit PCM) as the
//! production contract `AmbientMusicEngine` will eventually call, and
//! `app/src/main/java/com/gamesuite/audio/RustPadSynth.kt` (Step 3) exposes
//! only those same two methods to the rest of the app — that contract is
//! unchanged here. But `docs/RUST_AUDIO_CORE_PLAN.md`'s Step 4 explicitly
//! requires diffing the *pre-quantization* `f64` sample value against Kotlin
//! ("not the quantized `Short`, which would hide real per-sample drift behind
//! 16-bit rounding") and explicitly authorizes exactly this kind of seam on
//! the Kotlin side (`PadSynthState.renderF64`, see `AmbientMusicEngineTest`'s
//! sibling correctness test). A JVM unit test can only ever observe Rust
//! state through a real UniFFI-exported call — there is no `cfg(test)`-only
//! escape hatch that a separately-loaded native library can still expose to
//! an external JVM process — so this method has to be a real, always-compiled
//! export for the Kotlin-side correctness test to call it at all. Kept
//! deliberately absent from `RustPadSynth.kt`'s own public API so production
//! code never has a reason to call it.

use std::f64::consts::PI;
use std::sync::{Arc, Mutex};

uniffi::setup_scaffolding!("gamesuite_audio");

const SAMPLE_RATE_F32: f32 = 44100.0;
const SAMPLE_RATE_F64: f64 = 44100.0;

/// Matches `AmbientMusicEngine.kt`'s private `STARTUP_FADE_SECONDS` constant
/// (an unsuffixed `0.35` literal — `Double` in Kotlin).
const STARTUP_FADE_SECONDS: f64 = 0.35;

/// Matches `AmbientMusicEngine.kt`'s internal `STEREO_SPREAD` constant.
const STEREO_SPREAD: f64 = 0.6;

/// Matches `PadSynthState.arpeggioToneOrder` (`intArrayOf(0, 1, 2, 1)`).
const ARPEGGIO_TONE_ORDER: [i32; 4] = [0, 1, 2, 1];

/// Wire-format mirror of Kotlin's `MusicProfile`
/// (`app/src/main/java/com/gamesuite/audio/AmbientMusicEngine.kt`) — matches
/// `docs/RUST_AUDIO_CORE_ADR.md`'s FFI Surface section field-for-field (14
/// fields, same order).
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

// ---------------------------------------------------------------------------
// equalPowerPanGains / voicePan — ported 1:1 from AmbientMusicEngine.kt:78-95.
// ---------------------------------------------------------------------------

/// Left/right gain pair from an equal-power pan law. Mirrors
/// `equalPowerPanGains(pan: Float): PanGains` exactly, including the
/// Double->Float->Double round-trip its caller ([voice_pan]'s result) goes
/// through in Kotlin before reaching here — see [PadSynthState::new], which
/// narrows a `voice_pan` `f64` result to `f32` before calling this, matching
/// `voicePan(v, profile.voiceCount).toFloat()` in the Kotlin `init` block.
fn equal_power_pan_gains(pan: f32) -> (f64, f64) {
    let clamped = pan.clamp(-1.0f32, 1.0f32) as f64;
    let angle = (clamped + 1.0) * (PI / 4.0);
    (angle.cos(), angle.sin())
}

/// Mirrors `voicePan(voiceIndex: Int, voiceCount: Int, spread: Double):
/// Double` exactly (Kotlin's `spread` default parameter, `STEREO_SPREAD`, is
/// passed explicitly by every call site in this crate since Rust has no
/// default-argument sugar).
fn voice_pan(voice_index: i32, voice_count: i32, spread: f64) -> f64 {
    if voice_count <= 1 {
        return 0.0;
    }
    let fraction = voice_index as f64 / (voice_count - 1) as f64;
    (fraction * 2.0 - 1.0) * spread
}

// ---------------------------------------------------------------------------
// PadSynthState — ported from AmbientMusicEngine.kt:469-701.
// ---------------------------------------------------------------------------

struct VoiceBank {
    phase: Vec<f64>,
    freq_hz: Vec<f64>,
}

impl VoiceBank {
    fn new(voice_count: usize) -> Self {
        Self { phase: vec![0.0; voice_count], freq_hz: vec![0.0; voice_count] }
    }
}

/// Stacked-thirds chord-tone walk — mirrors `writeChordFrequencies` exactly.
/// A free function (not a `PadSynthState` method) so callers can pass exactly
/// the fields they need without fighting the borrow checker over a `&mut
/// VoiceBank` field alongside other `&self` reads (see [oscillator_sample]'s
/// doc for the same reasoning).
fn write_chord_frequencies(
    bank: &mut VoiceBank,
    degree_index: i32,
    chord_progression_degrees: &[i32],
    scale_intervals: &[i32],
    voice_count: i32,
    root_note_hz: f64,
) {
    let degree = chord_progression_degrees[(degree_index as usize) % chord_progression_degrees.len()];
    let size = scale_intervals.len() as i32;
    for v in 0..voice_count {
        // Stacked-thirds chord tones: root, then every other scale step.
        let scale_step = degree + v * 2;
        // size is always > 0 for any real MusicProfile (non-empty scale) --
        // div_euclid/rem_euclid with a positive divisor are exactly
        // Kotlin's Math.floorDiv/Math.floorMod for that case.
        let octave = scale_step.div_euclid(size);
        let idx = scale_step.rem_euclid(size);
        let semitone = scale_intervals[idx as usize] + 12 * octave;
        // Math.pow(2.0, x), not exp2(x) -- same function Kotlin's
        // Math.pow(2.0, semitone / 12.0) calls, for matching ULP behavior.
        bank.freq_hz[v as usize] = root_note_hz * 2.0_f64.powf(semitone as f64 / 12.0);
    }
}

/// One oscillator sample plus its own phase advance. Mirrors
/// `oscillatorSample(bank: VoiceBank, voice: Int): Double` exactly. A free
/// function taking `&mut VoiceBank` directly (rather than a `PadSynthState`
/// method) so `PadSynthState::compute_next_sample` can call it while also
/// reading sibling fields (`self.waveform_brightness`, etc.) in the same
/// expression -- Rust's borrow checker accepts disjoint direct field
/// projections (`&mut self.current` alongside `self.waveform_brightness`)
/// but not the same split through an opaque `&mut self` method call.
fn oscillator_sample(bank: &mut VoiceBank, voice: usize, waveform_brightness: f64, sample_rate: f64) -> f64 {
    let phase = bank.phase[voice];
    let angle = 2.0 * PI * phase;
    let sine = angle.sin();
    let triangle = 2.0 * (2.0 * (phase - (phase + 0.5).floor())).abs() - 1.0;
    let sample = sine * (1.0 - waveform_brightness) + triangle * waveform_brightness;

    let mut next = phase + bank.freq_hz[voice] / sample_rate;
    if next > 1.0 {
        next -= next.floor();
    }
    bank.phase[voice] = next;

    sample
}

/// Every `MusicProfileFfi` field `PadSynthState` reads during per-sample
/// DSP, widened to `f64` exactly once at construction time (see this
/// module's own top-of-file doc for why that's bit-identical to Kotlin's
/// per-use-site implicit widening). `chord_duration_seconds`/
/// `crossfade_seconds` are deliberately NOT kept here — Kotlin only ever
/// uses them once, to derive `chord_hold_samples`/`crossfade_samples` (in
/// `Float` precision, not `Double` -- see this module's top doc), so this
/// crate derives those once in [PadSynthState::new] and never stores the
/// raw seconds values at all.
struct PadSynthState {
    root_note_hz: f64,
    scale_intervals: Vec<i32>,
    chord_progression_degrees: Vec<i32>,
    voice_count: i32,
    base_volume: f64,
    breathing_rate_hz: f64,
    breathing_depth: f64,
    waveform_brightness: f64,
    arpeggio_enabled: bool,
    arpeggio_volume: f64,

    current: VoiceBank,
    incoming: VoiceBank,

    chord_index: i32,
    chord_hold_samples: i64,
    samples_until_chord_change: i64,
    crossfade_samples: i64,
    crossfade_remaining: i64,
    crossfading: bool,

    breathing_phase: f64,
    envelope_gain: f64,
    envelope_step: f64,

    // Independent per-channel lowpass state -- see the Kotlin field's own
    // KDoc for why these can't share one filter's memory across channels.
    lowpass_state_left: f64,
    lowpass_state_right: f64,
    lowpass_coeff: f64,

    // Each voice's own fixed stereo position, computed once in `new()`, not
    // per-sample -- see the Kotlin fields' own KDoc.
    voice_left_gain: Vec<f64>,
    voice_right_gain: Vec<f64>,

    last_left: f64,
    last_right: f64,

    // Arpeggio layer (only read when arpeggio_enabled).
    arp_samples_per_note: i64,
    arp_samples_remaining: i64,
    arp_phase: f64,
    arp_freq_hz: f64,
    arp_envelope: f64,
    arp_note_count: i32,
    arp_attack_samples: i64,
    arp_decay_factor: f64,
}

impl PadSynthState {
    fn new(profile: &MusicProfileFfi) -> Self {
        let voice_count = profile.voice_count;
        let voice_count_usize = voice_count.max(0) as usize;

        // Float arithmetic, staying in f32 precision until the truncating
        // conversion to samples -- matches Kotlin's `(profile.xSeconds *
        // SAMPLE_RATE).toLong()`, where SAMPLE_RATE is the raw Int (paired
        // with a Float, Kotlin promotes the Int to Float, NOT Double). See
        // this file's top-of-file doc for why this is the one place that
        // does NOT pre-widen to f64.
        let chord_hold_samples = ((profile.chord_duration_seconds * SAMPLE_RATE_F32) as i64).max(1);
        let crossfade_samples = ((profile.crossfade_seconds * SAMPLE_RATE_F32) as i64).max(1);

        // Double arithmetic -- matches Kotlin's `(sampleRateD /
        // profile.arpeggioRateHz).toLong()`, where sampleRateD is already
        // Double, so the Float operand promotes to Double this time (unlike
        // the two conversions just above, which pair with the raw Int).
        let arp_samples_per_note = if profile.arpeggio_enabled {
            ((SAMPLE_RATE_F64 / profile.arpeggio_rate_hz as f64) as i64).max(1)
        } else {
            1i64
        };
        // `(SAMPLE_RATE * 0.006).toLong()` -- Int paired with an unsuffixed
        // (Double) literal promotes to Double.
        let arp_attack_samples = ((SAMPLE_RATE_F64 * 0.006) as i64).max(1);
        let arp_decay_factor = (0.02_f64.ln() / arp_samples_per_note as f64).exp();
        let envelope_step = 1.0 / (SAMPLE_RATE_F64 * STARTUP_FADE_SECONDS);
        let lowpass_coeff =
            1.0 - (-2.0 * PI * profile.lowpass_cutoff_hz as f64 / SAMPLE_RATE_F64).exp();

        let root_note_hz = profile.root_note_hz as f64;
        let mut current = VoiceBank::new(voice_count_usize);
        let incoming = VoiceBank::new(voice_count_usize);
        write_chord_frequencies(
            &mut current,
            0,
            &profile.chord_progression_degrees,
            &profile.scale_intervals,
            voice_count,
            root_note_hz,
        );

        let mut voice_left_gain = vec![0.0; voice_count_usize];
        let mut voice_right_gain = vec![0.0; voice_count_usize];
        for v in 0..voice_count_usize {
            // voice_pan's f64 result narrows to f32 here, matching Kotlin's
            // `voicePan(v, profile.voiceCount).toFloat()` -- see
            // equal_power_pan_gains's own doc for why this round-trip
            // matters for exact-match correctness.
            let pan = voice_pan(v as i32, voice_count, STEREO_SPREAD) as f32;
            let (left, right) = equal_power_pan_gains(pan);
            voice_left_gain[v] = left;
            voice_right_gain[v] = right;
        }

        Self {
            root_note_hz,
            scale_intervals: profile.scale_intervals.clone(),
            chord_progression_degrees: profile.chord_progression_degrees.clone(),
            voice_count,
            base_volume: profile.base_volume as f64,
            breathing_rate_hz: profile.breathing_rate_hz as f64,
            breathing_depth: profile.breathing_depth as f64,
            waveform_brightness: profile.waveform_brightness as f64,
            arpeggio_enabled: profile.arpeggio_enabled,
            arpeggio_volume: profile.arpeggio_volume as f64,

            current,
            incoming,

            chord_index: 0,
            chord_hold_samples,
            samples_until_chord_change: chord_hold_samples,
            crossfade_samples,
            crossfade_remaining: 0,
            crossfading: false,

            breathing_phase: 0.0,
            envelope_gain: 0.0,
            envelope_step,

            lowpass_state_left: 0.0,
            lowpass_state_right: 0.0,
            lowpass_coeff,

            voice_left_gain,
            voice_right_gain,

            last_left: 0.0,
            last_right: 0.0,

            arp_samples_per_note,
            arp_samples_remaining: 0,
            arp_phase: 0.0,
            arp_freq_hz: 0.0,
            arp_envelope: 0.0,
            arp_note_count: 0,
            arp_attack_samples,
            arp_decay_factor,
        }
    }

    /// Mirrors `maybeStartChordChange()` exactly.
    fn maybe_start_chord_change(&mut self) {
        if self.samples_until_chord_change > 0 || self.crossfading {
            return;
        }
        self.chord_index += 1;
        write_chord_frequencies(
            &mut self.incoming,
            self.chord_index,
            &self.chord_progression_degrees,
            &self.scale_intervals,
            self.voice_count,
            self.root_note_hz,
        );
        for p in self.incoming.phase.iter_mut() {
            *p = 0.0;
        }
        self.crossfading = true;
        self.crossfade_remaining = self.crossfade_samples;
    }

    /// Mirrors `nextArpeggioSample(): Double` exactly.
    fn next_arpeggio_sample(&mut self) -> f64 {
        if self.arp_samples_remaining <= 0 {
            let tone_index = (ARPEGGIO_TONE_ORDER[(self.arp_note_count as usize) % ARPEGGIO_TONE_ORDER.len()]
                as usize)
                % self.current.freq_hz.len();
            self.arp_freq_hz = self.current.freq_hz[tone_index] * 2.0;
            self.arp_note_count += 1;
            self.arp_phase = 0.0;
            self.arp_envelope = 0.0001;
            self.arp_samples_remaining = self.arp_samples_per_note;
        }

        let angle = 2.0 * PI * self.arp_phase;
        let raw = angle.sin();
        let mut next = self.arp_phase + self.arp_freq_hz / SAMPLE_RATE_F64;
        if next > 1.0 {
            next -= next.floor();
        }
        self.arp_phase = next;

        let samples_into_note = self.arp_samples_per_note - self.arp_samples_remaining;
        self.arp_envelope = if samples_into_note < self.arp_attack_samples {
            self.arp_envelope + (1.0 - self.arp_envelope) * (1.0 / self.arp_attack_samples as f64)
        } else {
            self.arp_envelope * self.arp_decay_factor
        };
        self.arp_samples_remaining -= 1;

        raw * self.arp_envelope * self.arpeggio_volume
    }

    /// Mirrors `computeNextSample()` exactly, stage order included: chord-
    /// change trigger -> crossfade mix (or plain mix) -> breathing LFO ->
    /// arpeggio layer -> independent per-channel one-pole lowpass ->
    /// envelope gain -> `last_left`/`last_right`.
    fn compute_next_sample(&mut self) {
        self.maybe_start_chord_change();

        let mut left_mix = 0.0_f64;
        let mut right_mix = 0.0_f64;
        let voice_gain = 1.0 / self.voice_count as f64;

        if self.crossfading {
            let progress = 1.0 - (self.crossfade_remaining as f64 / self.crossfade_samples as f64);
            // Equal-power crossfade -- cos/sin pair keeps perceived loudness
            // constant through the transition.
            let out_gain = (progress * PI / 2.0).cos();
            let in_gain = (progress * PI / 2.0).sin();
            for v in 0..self.voice_count as usize {
                let combined = oscillator_sample(&mut self.current, v, self.waveform_brightness, SAMPLE_RATE_F64)
                    * out_gain
                    * voice_gain
                    + oscillator_sample(&mut self.incoming, v, self.waveform_brightness, SAMPLE_RATE_F64)
                        * in_gain
                        * voice_gain;
                left_mix += combined * self.voice_left_gain[v];
                right_mix += combined * self.voice_right_gain[v];
            }
            self.crossfade_remaining -= 1;
            if self.crossfade_remaining <= 0 {
                self.current.freq_hz.copy_from_slice(&self.incoming.freq_hz);
                self.current.phase.copy_from_slice(&self.incoming.phase);
                self.crossfading = false;
                self.samples_until_chord_change = self.chord_hold_samples;
            }
        } else {
            for v in 0..self.voice_count as usize {
                let sample = oscillator_sample(&mut self.current, v, self.waveform_brightness, SAMPLE_RATE_F64)
                    * voice_gain;
                left_mix += sample * self.voice_left_gain[v];
                right_mix += sample * self.voice_right_gain[v];
            }
            self.samples_until_chord_change -= 1;
        }

        // Slow amplitude "breathing" LFO -- one shared phase/envelope for
        // both channels.
        self.breathing_phase += self.breathing_rate_hz / SAMPLE_RATE_F64;
        if self.breathing_phase > 1.0 {
            self.breathing_phase -= self.breathing_phase.floor();
        }
        let breathing =
            1.0 - self.breathing_depth + self.breathing_depth * (2.0 * PI * self.breathing_phase).sin();
        left_mix *= breathing;
        right_mix *= breathing;

        // The plucked arpeggio layer stays dead center.
        if self.arpeggio_enabled {
            let arp = self.next_arpeggio_sample();
            left_mix += arp;
            right_mix += arp;
        }

        // One-pole low-pass "warmth" smoothing, independently per channel.
        self.lowpass_state_left += self.lowpass_coeff * (left_mix - self.lowpass_state_left);
        self.lowpass_state_right += self.lowpass_coeff * (right_mix - self.lowpass_state_right);

        if self.envelope_gain < 1.0 {
            self.envelope_gain = (self.envelope_gain + self.envelope_step).min(1.0);
        }

        self.last_left = self.lowpass_state_left * self.base_volume * self.envelope_gain;
        self.last_right = self.lowpass_state_right * self.base_volume * self.envelope_gain;
    }
}

/// `(value.clamp(-1.0, 1.0) * i16::MAX as f64) as i16`, little-endian bytes
/// appended -- matches `PadSynthState.toPcm16` (see this crate's top-of-file
/// doc / `docs/RUST_AUDIO_CORE_PLAN.md`'s Step 2 section for why the direct
/// f64->i16 cast here is a verified-equivalent simplification of Kotlin's
/// two-step Double->Int->Short narrowing, not a behavioral difference: the
/// value is always within [-32767, 32767], well inside i16's range, so
/// neither narrowing step ever actually saturates or wraps).
fn push_pcm16(out: &mut Vec<u8>, value: f64) {
    let clamped = value.clamp(-1.0, 1.0);
    let sample = (clamped * i16::MAX as f64) as i16;
    out.extend_from_slice(&sample.to_le_bytes());
}

/// The FFI-facing handle Kotlin holds one of per `AmbientMusicEngine`
/// generator-thread lifetime (mirrors one `PadSynthState` instance today). A
/// UniFFI `Object` — shared behind an `Arc` across the FFI boundary, wrapping
/// a `Mutex<PadSynthState>` for interior mutability, same convention as
/// `gamesuite-sim::AirHockeySim`.
#[derive(uniffi::Object)]
pub struct PadSynth {
    inner: Mutex<PadSynthState>,
}

#[uniffi::export]
impl PadSynth {
    #[uniffi::constructor]
    pub fn new(profile: MusicProfileFfi) -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(PadSynthState::new(&profile)) })
    }

    /// Interleaved stereo, 16-bit LE PCM — `frame_count * 4` bytes, matching
    /// the ADR's FFI Surface exactly.
    pub fn render(&self, frame_count: i32) -> Vec<u8> {
        let frames = frame_count.max(0) as usize;
        let mut state = self.inner.lock().unwrap();
        let mut out = Vec::with_capacity(frames * 4);
        for _ in 0..frames {
            state.compute_next_sample();
            push_pcm16(&mut out, state.last_left);
            push_pcm16(&mut out, state.last_right);
        }
        out
    }

    /// Mirrors `PadSynthState.renderFadeOut`: a linear taper-to-silence tail
    /// over the live chord/breathing/arp state, same call shape as
    /// [`PadSynth::render`].
    pub fn render_fade_out(&self, frame_count: i32) -> Vec<u8> {
        let frames = frame_count.max(0) as usize;
        let mut state = self.inner.lock().unwrap();
        let mut out = Vec::with_capacity(frames * 4);
        for frame in 0..frames {
            let taper = 1.0 - (frame as f64 / frames as f64);
            state.compute_next_sample();
            push_pcm16(&mut out, state.last_left * taper);
            push_pcm16(&mut out, state.last_right * taper);
        }
        out
    }

    /// Test-only diagnostic seam — see this module's top-of-file doc for why
    /// this exists and why it's deliberately absent from `RustPadSynth.kt`'s
    /// public API. Interleaved stereo `f64` (pre-quantization), matching the
    /// Kotlin-side `PadSynthState.renderF64` test seam's shape.
    pub fn render_f64(&self, frame_count: i32) -> Vec<f64> {
        let frames = frame_count.max(0) as usize;
        let mut state = self.inner.lock().unwrap();
        let mut out = Vec::with_capacity(frames * 2);
        for _ in 0..frames {
            state.compute_next_sample();
            out.push(state.last_left);
            out.push(state.last_right);
        }
        out
    }
}

// ---------------------------------------------------------------------------
// Native unit tests — run via `cargo test`, no JNI/.so loading involved. The
// exact 5 tests docs/RUST_AUDIO_CORE_PLAN.md's Step 2 section names.
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

    /// Ports `AmbientMusicEngineTest`'s `equalPowerPanGains keeps constant
    /// power across the whole pan range` assertion: `left^2 + right^2` must
    /// be exactly 1.0 for any pan.
    #[test]
    fn equal_power_pan_gains_sums_to_one() {
        for pan in [-1.0f32, -0.75, -0.4, -0.1, 0.0, 0.1, 0.4, 0.75, 1.0] {
            let (left, right) = equal_power_pan_gains(pan);
            let power = left * left + right * right;
            assert!((power - 1.0).abs() < 1e-9, "pan={pan} must have unit power, got {power}");
        }
    }

    /// Ports `AmbientMusicEngineTest`'s voice-count 1-8 structural checks:
    /// voice 0 leftmost, last voice rightmost, symmetric around center, and
    /// the single-voice case stays centered instead of dividing by zero.
    #[test]
    fn voice_pan_is_symmetric_and_endpoints_are_correct() {
        let spread = STEREO_SPREAD;

        assert_eq!(voice_pan(0, 1, spread), 0.0);
        assert_eq!(voice_pan(0, 0, spread), 0.0);

        for voice_count in 2..=8 {
            let pans: Vec<f64> = (0..voice_count).map(|v| voice_pan(v, voice_count, spread)).collect();

            assert!((pans[0] - (-spread)).abs() < 1e-9, "voice_count={voice_count} leftmost voice");
            assert!(
                (pans[pans.len() - 1] - spread).abs() < 1e-9,
                "voice_count={voice_count} rightmost voice"
            );

            for i in 1..pans.len() {
                assert!(pans[i] > pans[i - 1], "voice_count={voice_count}: voice_pan must be strictly increasing");
            }

            for i in 0..pans.len() {
                let mirror = pans[pans.len() - 1 - i];
                assert!((mirror - (-pans[i])).abs() < 1e-9, "voice_count={voice_count} voice {i} vs its mirror");
            }
        }
    }

    /// A single-voice profile must not divide by zero anywhere in
    /// construction or rendering (`voice_pan`'s own `voice_count <= 1` guard,
    /// plus `1.0 / voice_count` in `compute_next_sample`, which is exactly
    /// 1.0 and well-defined at `voice_count == 1`).
    #[test]
    fn single_voice_profile_does_not_divide_by_zero() {
        let mut profile = test_profile();
        profile.voice_count = 1;
        profile.scale_intervals = vec![0, 2, 4, 5, 7, 9, 11];
        profile.chord_progression_degrees = vec![0, 3, 4];

        let synth = PadSynth::new(profile);
        let bytes = synth.render(256);
        assert_eq!(bytes.len(), 256 * 4);
        assert!(
            bytes.iter().any(|&b| b != 0),
            "expected real (non-silent) audio output for a single-voice profile"
        );
    }

    #[test]
    fn render_produces_correct_byte_length() {
        let synth = PadSynth::new(test_profile());
        assert_eq!(synth.render(0).len(), 0);
        assert_eq!(synth.render(100).len(), 400);
        assert_eq!(synth.render_fade_out(100).len(), 400);
    }

    /// After `crossfade_samples` frames of an in-progress crossfade, `current`
    /// must reflect the values `incoming` held right before the swap.
    #[test]
    fn crossfade_completes_and_swaps_current_bank() {
        let profile = test_profile();
        let mut state = PadSynthState::new(&profile);

        // Drive past the initial chord hold so a crossfade begins.
        for _ in 0..(state.chord_hold_samples + 1) {
            state.compute_next_sample();
        }
        assert!(state.crossfading, "expected a crossfade to have started after the chord hold elapsed");
        // freq_hz is written once by write_chord_frequencies when the crossfade starts and never
        // touched again until the swap -- unlike phase (continuously advanced by oscillator_sample
        // every sample of the crossfade), so "the values incoming held before the swap" is
        // meaningfully captured here for freq_hz specifically.
        let incoming_freq_before_swap = state.incoming.freq_hz.clone();

        // Drive generously past the crossfade's own length so it completes.
        for _ in 0..(state.crossfade_samples + 5) {
            state.compute_next_sample();
        }

        assert!(!state.crossfading, "crossfade should have completed");
        assert_eq!(state.current.freq_hz, incoming_freq_before_swap);
    }

    /// A negative frame_count is never expected from the real Kotlin caller,
    /// but the FFI signature is a plain i32 — must not panic across the
    /// UniFFI boundary. Kept from Step 1's stub; not one of the plan's named
    /// 5 but still a real, still-valid regression check now real DSP runs.
    #[test]
    fn negative_frame_count_does_not_panic() {
        let synth = PadSynth::new(test_profile());
        assert_eq!(synth.render(-5).len(), 0);
    }
}
