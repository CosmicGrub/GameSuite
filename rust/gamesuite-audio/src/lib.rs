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

    /// Out-of-range pans clamp to the hard-left/hard-right result instead of continuing round the
    /// circle: a pan of +-5.0 must give exactly the +-1.0 gains (left ~0/right 1 and left 1/right
    /// ~0), including +-infinity. Without the clamp `(5+1) * pi/4` wraps to a quieter, wrong
    /// position (cos = 0, sin = -1: a polarity-inverted right channel).
    #[test]
    fn equal_power_pan_gains_clamps_out_of_range_pan_to_the_hard_edges() {
        for beyond in [5.0f32, 1.0001, 1e30, f32::INFINITY] {
            assert_eq!(equal_power_pan_gains(beyond), equal_power_pan_gains(1.0), "pan +{beyond}");
            assert_eq!(equal_power_pan_gains(-beyond), equal_power_pan_gains(-1.0), "pan -{beyond}");
        }
        let (left_edge, right_edge) = (equal_power_pan_gains(-5.0), equal_power_pan_gains(5.0));
        assert!((left_edge.0 - 1.0).abs() < 1e-12 && left_edge.1.abs() < 1e-12, "hard left, got {left_edge:?}");
        assert!(right_edge.0.abs() < 1e-12 && (right_edge.1 - 1.0).abs() < 1e-12, "hard right, got {right_edge:?}");
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

    // =======================================================================
    // Spec-level tests. The tests above pin a few structural facts; the ones
    // below pin the quantizer's exact numeric contract, the byte/f64 seams'
    // agreement, block-size independence, edge configurations, and the
    // musical spec (chord-tone stacking, progression order, timing, gain
    // chain) directly on `PadSynthState` -- independent of the Kotlin twin.
    // =======================================================================

    /// Independently-written quantizer (clamp to -1..1, times 32767, truncate toward zero) used
    /// as the oracle for `render`'s bytes; deliberately not `push_pcm16`.
    fn quantize(x: f64) -> i16 {
        (x.clamp(-1.0, 1.0) * 32767.0).trunc() as i16
    }

    fn decode_pcm(bytes: &[u8]) -> Vec<i16> {
        assert_eq!(bytes.len() % 2, 0, "PCM byte stream must be a whole number of 16-bit samples");
        bytes.chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]])).collect()
    }

    fn default_crossfade(chord_duration_seconds: f32) -> f32 {
        // Mirrors MusicProfile.crossfadeSeconds's Kotlin default: (0.15 * chord).coerceIn(0.6, 4).
        (chord_duration_seconds * 0.15).clamp(0.6, 4.0)
    }

    const NATURAL_MINOR: [i32; 7] = [0, 2, 3, 5, 7, 8, 10];
    const MAJOR: [i32; 7] = [0, 2, 4, 5, 7, 9, 11];
    const MAJOR_PENTATONIC: [i32; 5] = [0, 2, 4, 7, 9];

    #[allow(clippy::too_many_arguments)]
    fn mirrored(
        root: f32,
        scale: &[i32],
        progression: &[i32],
        chord_seconds: f32,
        voices: i32,
        base_volume: f32,
        brightness: f32,
        lowpass: f32,
    ) -> MusicProfileFfi {
        MusicProfileFfi {
            root_note_hz: root,
            scale_intervals: scale.to_vec(),
            chord_progression_degrees: progression.to_vec(),
            chord_duration_seconds: chord_seconds,
            voice_count: voices,
            base_volume,
            crossfade_seconds: default_crossfade(chord_seconds),
            breathing_rate_hz: 0.08,
            breathing_depth: 0.16,
            waveform_brightness: brightness,
            lowpass_cutoff_hz: lowpass,
            arpeggio_enabled: false,
            arpeggio_rate_hz: 2.0,
            arpeggio_volume: 0.14,
        }
    }

    // The four helpers below are REFERENCE profiles: hand-copied approximations of the shipped
    // Kotlin `MusicProfiles.CHESS/MANCALA/UNO/AIR_HOCKEY` (same shape: root, scale, progression,
    // voice count, arpeggio on/off), used only to give these tests realistic configurations.
    // They are NOT the shipped data and are not kept in sync with it: parity of the Rust engine
    // with the shipped Kotlin profiles is checked by `RustPadSynthCorrectnessTest` (which renders
    // every `MusicProfiles` entry through both engines). Assertions on these are therefore about
    // engine behaviour for the given numbers, never about what ships.

    /// Reference profile shaped like `MusicProfiles.CHESS`.
    fn reference_chess() -> MusicProfileFfi {
        mirrored(110.0, &NATURAL_MINOR, &[0, 5, 3, 4], 20.0, 3, 0.13, 0.12, 3000.0)
    }

    /// Reference profile shaped like `MusicProfiles.MANCALA` (pentatonic + arpeggio).
    fn reference_mancala() -> MusicProfileFfi {
        let mut p = mirrored(130.81, &MAJOR_PENTATONIC, &[0, 2, 3, 0], 14.0, 3, 0.15, 0.22, 3200.0);
        p.arpeggio_enabled = true;
        p.arpeggio_rate_hz = 1.1;
        p.arpeggio_volume = 0.12;
        p
    }

    /// Reference profile shaped like `MusicProfiles.UNO` (custom breathing rate + arpeggio).
    fn reference_uno() -> MusicProfileFfi {
        let mut p = mirrored(196.0, &MAJOR_PENTATONIC, &[0, 2, 4, 2], 9.0, 3, 0.15, 0.3, 4500.0);
        p.breathing_rate_hz = 0.12;
        p.arpeggio_enabled = true;
        p.arpeggio_rate_hz = 2.5;
        p.arpeggio_volume = 0.14;
        p
    }

    /// Reference profile shaped like `MusicProfiles.AIR_HOCKEY` (2 voices, fast, bright, arpeggio).
    fn reference_air_hockey() -> MusicProfileFfi {
        let mut p = mirrored(164.81, &MAJOR, &[0, 4], 4.0, 2, 0.08, 0.4, 5500.0);
        p.breathing_rate_hz = 0.22;
        p.breathing_depth = 0.22;
        p.arpeggio_enabled = true;
        p.arpeggio_rate_hz = 4.0;
        p.arpeggio_volume = 0.16;
        p
    }

    fn reference_profiles() -> Vec<(&'static str, MusicProfileFfi)> {
        vec![("chess", reference_chess()), ("mancala", reference_mancala()), ("uno", reference_uno()), ("air_hockey", reference_air_hockey())]
    }

    /// Frames covering the first full hold, the first crossfade, and a little of the second chord.
    fn frames_through_first_chord_change(p: &MusicProfileFfi) -> usize {
        ((p.chord_duration_seconds + p.crossfade_seconds + 0.5) * 44100.0) as usize
    }

    fn rms(x: &[f64]) -> f64 {
        (x.iter().map(|v| v * v).sum::<f64>() / x.len() as f64).sqrt()
    }

    fn left_channel(interleaved: &[f64]) -> Vec<f64> {
        interleaved.iter().step_by(2).copied().collect()
    }

    fn right_channel(interleaved: &[f64]) -> Vec<f64> {
        interleaved.iter().skip(1).step_by(2).copied().collect()
    }

    // ---- R1: push_pcm16 exact numeric contract ----------------------------

    fn pcm_bytes(value: f64) -> Vec<u8> {
        let mut out = Vec::new();
        push_pcm16(&mut out, value);
        out
    }

    fn pcm_i16(value: f64) -> i16 {
        let b = pcm_bytes(value);
        assert_eq!(b.len(), 2, "each pushed sample must be exactly 2 bytes");
        i16::from_le_bytes([b[0], b[1]])
    }

    /// Guards the scale factor: full scale must be exactly +-32767 (Kotlin's `Short.MAX_VALUE`),
    /// not 32768 (which would overflow i16 at +1.0) and not something smaller.
    #[test]
    fn push_pcm16_full_scale_is_plus_minus_32767() {
        assert_eq!(pcm_i16(1.0), 32767);
        assert_eq!(pcm_i16(-1.0), -32767);
        assert_eq!(pcm_i16(0.0), 0);
    }

    /// Guards the clamp: without it a 1.5 input would wrap/saturate differently (or, for a
    /// hand-rolled cast, wrap negative), which is an audible full-scale click on a loud mix.
    #[test]
    fn push_pcm16_clamps_out_of_range_and_infinite_input() {
        for v in [1.5, 2.0, 1.000001, 1e300, f64::INFINITY] {
            assert_eq!(pcm_i16(v), 32767, "input {v} must clamp to +32767");
            assert_eq!(pcm_i16(-v), -32767, "input {} must clamp to -32767", -v);
        }
    }

    /// Guards truncation toward zero (Kotlin `.toInt()`): 0.5 * 32767 = 16383.5 must give 16383,
    /// and the negative twin -16383 (a floor/round-half-up implementation gives 16384 / -16384).
    #[test]
    fn push_pcm16_truncates_toward_zero_for_half_scale() {
        assert_eq!(pcm_i16(0.5), 16383);
        assert_eq!(pcm_i16(-0.5), -16383);
    }

    /// NaN must quantize to silence (Kotlin: `NaN.toInt() == 0`), never to a garbage sample.
    #[test]
    fn push_pcm16_maps_nan_to_zero() {
        assert_eq!(pcm_i16(f64::NAN), 0);
        assert_eq!(pcm_i16(-f64::NAN), 0);
    }

    /// Sub-LSB values, including negative ones, must land on 0 -- a `floor` would send -0.9 LSB
    /// to -1 and add a DC bias / asymmetry to quiet passages (fade tails, startup ramp).
    #[test]
    fn push_pcm16_sub_lsb_values_truncate_to_zero_on_both_signs() {
        for v in [1e-6, 1e-12, 0.9 / 32767.0, f64::MIN_POSITIVE] {
            assert_eq!(pcm_i16(v), 0, "+{v} is below one LSB");
            assert_eq!(pcm_i16(-v), 0, "-{v} is below one LSB");
        }
        assert_eq!(pcm_i16(1.5 / 32767.0), 1);
        assert_eq!(pcm_i16(-1.5 / 32767.0), -1);
    }

    /// Guards byte order: little-endian means low byte first. Uses values whose two bytes differ
    /// so a big-endian (or byte-swapped) writer cannot pass.
    #[test]
    fn push_pcm16_writes_little_endian_bytes() {
        assert_eq!(pcm_bytes(1.0), vec![0xFF, 0x7F]);
        assert_eq!(pcm_bytes(-1.0), vec![0x01, 0x80]); // -32767 == 0x8001
        assert_eq!(pcm_bytes(0.5), vec![0xFF, 0x3F]); // 16383 == 0x3FFF
        assert_eq!(pcm_bytes(4660.5 / 32767.0), vec![0x34, 0x12]); // 4660 == 0x1234
        assert_eq!(pcm_bytes(0.0), vec![0x00, 0x00]);
    }

    /// `push_pcm16` must append, not overwrite/clear -- `render` relies on interleaving L then R
    /// into one growing buffer.
    #[test]
    fn push_pcm16_appends_two_bytes_per_call_without_touching_existing_bytes() {
        let mut out = vec![0xAA, 0xBB];
        push_pcm16(&mut out, 1.0);
        push_pcm16(&mut out, -1.0);
        assert_eq!(out, vec![0xAA, 0xBB, 0xFF, 0x7F, 0x01, 0x80]);
    }

    /// Property sweep over -2..2: error under one LSB, sign preserved, exact odd symmetry, never
    /// the asymmetric `i16::MIN`, and monotonic non-decreasing.
    #[test]
    fn push_pcm16_sweep_is_monotonic_symmetric_and_within_one_lsb() {
        let mut prev = i16::MIN;
        for i in -2000..=2000 {
            let x = i as f64 / 1000.0;
            let q = pcm_i16(x);
            assert!(q != i16::MIN, "-32768 must never be produced (x={x})");
            assert!(q >= prev, "quantizer must be monotonic (x={x}: {q} < {prev})");
            prev = q;
            assert_eq!(q, -pcm_i16(-x), "odd symmetry violated at x={x}");
            let target = x.clamp(-1.0, 1.0) * 32767.0;
            assert!((q as f64 - target).abs() < 1.0, "x={x}: q={q} is not within one LSB of {target}");
            if x != 0.0 {
                assert!(q == 0 || (q > 0) == (x > 0.0), "sign flipped at x={x}");
            }
        }
    }

    // ---- R2: render() == quantize(render_f64()) ---------------------------

    /// The byte path and the f64 diagnostic seam must describe the same signal: for identical
    /// fresh synths, every `render` sample equals the independent quantization of the matching
    /// `render_f64` sample, across the first chord change, for reference profiles shaped like
    /// the shipped ones (including arpeggio ones), rendered in production-sized chunks. Catches a quantizer
    /// change, an L/R swap on the byte path only, or the two paths drifting apart.
    #[test]
    fn render_bytes_are_the_exact_quantization_of_render_f64() {
        const CHUNK: usize = 2205;
        for (name, profile) in reference_profiles() {
            let total = frames_through_first_chord_change(&profile);
            let bytes_synth = PadSynth::new(profile.clone());
            let f64_synth = PadSynth::new(profile.clone());
            let mut done = 0;
            let mut nonzero = 0usize;
            while done < total {
                let n = CHUNK.min(total - done);
                let pcm = decode_pcm(&bytes_synth.render(n as i32));
                let reference = f64_synth.render_f64(n as i32);
                assert_eq!(pcm.len(), n * 2, "{name}: interleaved stereo => 2 samples per frame");
                assert_eq!(reference.len(), n * 2);
                for (i, (&q, &x)) in pcm.iter().zip(reference.iter()).enumerate() {
                    assert_eq!(q, quantize(x), "{name}: frame {} ch {} value {x}", (done * 2 + i) / 2, i % 2);
                    if q != 0 {
                        nonzero += 1;
                    }
                }
                done += n;
            }
            assert!(nonzero > total, "{name}: expected substantial non-silent output, got {nonzero} nonzero samples");
        }
    }

    // ---- R3: fade-out -----------------------------------------------------

    /// `render_fade_out` must be exactly the *continued* signal times a linear taper
    /// `1 - i/N`: same state continuation as `render`, taper 1.0 on the first frame, then
    /// quantized. Catches a wrong taper shape, an off-by-one in the denominator, a fade that
    /// restarts the synth, and the taper being applied to only one channel.
    #[test]
    fn render_fade_out_is_the_continued_signal_times_a_linear_taper() {
        let profile = reference_air_hockey();
        let warm = 30_000;
        let n = 8_820usize;
        let fade_synth = PadSynth::new(profile.clone());
        let ref_synth = PadSynth::new(profile);
        fade_synth.render(warm);
        ref_synth.render_f64(warm);

        let fade = decode_pcm(&fade_synth.render_fade_out(n as i32));
        let reference = ref_synth.render_f64(n as i32);
        assert_eq!(fade.len(), n * 2);
        for i in 0..n {
            let taper = 1.0 - i as f64 / n as f64;
            assert_eq!(fade[2 * i], quantize(reference[2 * i] * taper), "left frame {i}");
            assert_eq!(fade[2 * i + 1], quantize(reference[2 * i + 1] * taper), "right frame {i}");
        }
    }

    /// The fade tail must audibly decay and end (near) silent so stop() never pops: each quarter
    /// of the tail is quieter than the previous, the last quarter is a fraction of the first,
    /// and the very last frame is within a couple of LSBs of zero.
    #[test]
    fn render_fade_out_decays_and_ends_near_silence() {
        for (name, profile) in reference_profiles() {
            let synth = PadSynth::new(profile);
            synth.render(3 * 44100);
            let n = 11_025usize; // 0.25 s, the engine's FADE_OUT_SECONDS
            let tail: Vec<f64> = decode_pcm(&synth.render_fade_out(n as i32)).iter().map(|&s| s as f64).collect();
            let q = n * 2 / 4;
            let quarter_rms: Vec<f64> = (0..4).map(|k| rms(&tail[k * q..(k + 1) * q])).collect();
            assert!(quarter_rms[0] > 100.0, "{name}: fade must start from an audible level, got {quarter_rms:?}");
            for k in 1..4 {
                assert!(quarter_rms[k] < quarter_rms[k - 1], "{name}: quarter {k} not quieter than {}: {quarter_rms:?}", k - 1);
            }
            assert!(quarter_rms[3] * 3.0 < quarter_rms[0], "{name}: tail must decay strongly: {quarter_rms:?}");
            let last_frame = &tail[tail.len() - 2..];
            assert!(last_frame.iter().all(|s| s.abs() <= 3.0), "{name}: last frame must be ~0, got {last_frame:?}");
        }
    }

    // ---- R3/R4: chunk-size invariance, persistence, determinism ----------

    fn render_in_chunks(synth: &PadSynth, total: usize, sizes: &[usize]) -> Vec<u8> {
        // Every chunk size must be non-zero, otherwise `done` could never advance.
        assert!(!sizes.is_empty() && sizes.iter().all(|&n| n > 0), "chunk sizes must be positive");
        let mut out = Vec::with_capacity(total * 4);
        let mut done = 0;
        let mut i = 0;
        while done < total {
            let n = sizes[i % sizes.len()].min(total - done);
            out.extend(synth.render(n as i32));
            done += n;
            i += 1;
        }
        out
    }

    fn first_difference(a: &[u8], b: &[u8]) -> Option<usize> {
        a.iter().zip(b.iter()).position(|(x, y)| x != y).or(if a.len() != b.len() { Some(a.len().min(b.len())) } else { None })
    }

    /// One `render(N)` must be byte-identical to many small renders of odd sizes -- including
    /// 1-frame and prime-sized chunks -- across the first chord change. Catches any per-call state
    /// reset, per-call rounding, or work done at call boundaries (the real engine calls in
    /// ~2205-frame chunks; Android may hand it different sizes).
    #[test]
    fn render_is_chunk_size_invariant_across_a_chord_change() {
        for (name, profile) in [("air_hockey", reference_air_hockey()), ("uno", reference_uno())] {
            let total = frames_through_first_chord_change(&profile);
            let whole = PadSynth::new(profile.clone()).render(total as i32);
            let pieces = render_in_chunks(&PadSynth::new(profile), total, &[1, 2, 7, 4410, 333, 1024, 50_000, 13]);
            assert_eq!(whole.len(), pieces.len());
            assert_eq!(first_difference(&whole, &pieces), None, "{name}: chunked render diverged from one-shot render");
        }
    }

    /// Same invariance for the f64 seam, compared bit-for-bit.
    #[test]
    fn render_f64_is_chunk_size_invariant_bit_for_bit() {
        let profile = reference_air_hockey();
        let total = frames_through_first_chord_change(&profile);
        let whole = PadSynth::new(profile.clone()).render_f64(total as i32);
        let synth = PadSynth::new(profile);
        let mut pieces: Vec<f64> = Vec::new();
        let mut done = 0;
        for size in [3usize, 4410, 17, 999].iter().cycle() {
            if done >= total {
                break;
            }
            let n = (*size).min(total - done);
            pieces.extend(synth.render_f64(n as i32));
            done += n;
        }
        assert_eq!(whole.len(), pieces.len());
        let mismatch = whole.iter().zip(pieces.iter()).position(|(a, b)| a.to_bits() != b.to_bits());
        assert_eq!(mismatch, None);
    }

    /// State persists across calls: the second block equals the matching slice of one long render
    /// and is NOT what a freshly-started synth would produce (which begins with the startup
    /// fade-in). Catches "render restarts the synth each call".
    #[test]
    fn a_second_render_continues_rather_than_restarts() {
        let profile = reference_mancala();
        let long = PadSynth::new(profile.clone()).render(6000);
        let synth = PadSynth::new(profile.clone());
        let first = synth.render(3000);
        let second = synth.render(3000);
        assert_eq!(first, long[..3000 * 4]);
        assert_eq!(second, long[3000 * 4..]);
        let fresh = PadSynth::new(profile).render(3000);
        assert_ne!(second, fresh, "a continuing synth must differ from a restarted one");
    }

    /// `render` and `render_f64` share one state: interleaving them must equal a pure `render`
    /// run. Catches a seam that advances a private copy of the state.
    #[test]
    fn render_and_render_f64_advance_the_same_underlying_state() {
        let profile = reference_uno();
        let reference = PadSynth::new(profile.clone()).render(4000);
        let mixed = PadSynth::new(profile);
        let head = mixed.render_f64(2000);
        let tail = mixed.render(2000);
        assert_eq!(decode_pcm(&reference)[..4000], head.iter().map(|&x| quantize(x)).collect::<Vec<_>>()[..]);
        assert_eq!(tail, reference[2000 * 4..]);
    }

    /// Determinism (no hidden RNG/clock/global state): two fresh synths give identical bytes; two
    /// different profiles do not (guards the profile being ignored).
    #[test]
    fn output_is_deterministic_and_depends_on_the_profile() {
        let a = PadSynth::new(reference_chess()).render(20_000);
        let b = PadSynth::new(reference_chess()).render(20_000);
        assert_eq!(a, b);
        let other = PadSynth::new(reference_mancala()).render(20_000);
        assert_ne!(a, other);
        // Constructing/rendering another synth in between must not perturb a running one.
        let running = PadSynth::new(reference_chess());
        let first_half = running.render(10_000);
        let _noise = PadSynth::new(reference_uno()).render(5_000);
        let second_half = running.render(10_000);
        assert_eq!([first_half, second_half].concat(), a);
    }

    // ---- R5: edge configurations -----------------------------------------

    /// Renders through several chord changes and asserts finite, bounded, non-silent output.
    fn assert_healthy(name: &str, profile: MusicProfileFfi, seconds: f64) {
        let frames = (seconds * 44100.0) as i32;
        let synth = PadSynth::new(profile);
        let samples = synth.render_f64(frames);
        assert_eq!(samples.len(), frames as usize * 2, "{name}");
        assert!(samples.iter().all(|s| s.is_finite()), "{name}: non-finite sample");
        assert!(samples.iter().all(|s| s.abs() <= 1.0), "{name}: sample outside -1..1");
        assert!(rms(&samples) > 1e-4, "{name}: output is silent (rms {})", rms(&samples));
        let bytes = synth.render(4410);
        assert_eq!(bytes.len(), 4410 * 4, "{name}");
    }

    fn quick_changes(voices: i32, scale: &[i32], progression: &[i32]) -> MusicProfileFfi {
        let mut p = mirrored(200.0, scale, progression, 0.3, voices, 0.5, 0.5, 2000.0);
        p.crossfade_seconds = 0.1;
        p
    }

    #[test]
    fn one_voice_and_eight_voice_profiles_render_finite_audio_through_chord_changes() {
        assert_healthy("1 voice", quick_changes(1, &MAJOR, &[0, 3, 4]), 1.5);
        assert_healthy("8 voices", quick_changes(8, &MAJOR, &[0, 3, 4]), 1.5);
        let mut arp8 = quick_changes(8, &MAJOR_PENTATONIC, &[0, 2]);
        arp8.arpeggio_enabled = true;
        assert_healthy("8 voices + arp on a pentatonic", arp8, 1.5);
    }

    /// A 1-note scale stacks in octaves (each scale step is a full octave: tones at 1x, 4x, 16x) and a progression of length 1
    /// keeps replaying the same chord: no div-by-zero, no index panic, exact octave tones.
    #[test]
    fn one_note_scale_and_single_chord_progression_are_well_defined() {
        let profile = quick_changes(3, &[0], &[0]);
        let state = PadSynthState::new(&profile);
        let root = 200.0_f32 as f64;
        assert_eq!(state.current.freq_hz, vec![root, root * 4.0, root * 16.0]);
        assert_healthy("1-note scale, 1-chord progression", profile, 2.0);
    }

    /// Extreme arpeggio rates: sub-Hz, faster than the sample rate, exactly Nyquist, and zero
    /// (division by zero in f64 -> infinity -> saturating cast). None may panic or go non-finite.
    #[test]
    fn arpeggio_at_extreme_rates_stays_finite() {
        for rate in [0.0f32, 0.001, 0.5, 100.0, 22_050.0, 1_000_000.0] {
            let mut p = quick_changes(3, &MAJOR_PENTATONIC, &[0, 2, 4]);
            p.arpeggio_enabled = true;
            p.arpeggio_rate_hz = rate;
            let synth = PadSynth::new(p);
            let x = synth.render_f64(30_000);
            assert!(x.iter().all(|v| v.is_finite()), "rate {rate}: non-finite output");
            assert!(x.iter().all(|v| v.abs() <= 1.0), "rate {rate}: output outside -1..1");
        }
    }

    /// Zero (and negative) frame counts return nothing AND leave the synth untouched, so the next
    /// real render equals a fresh synth's first render.
    #[test]
    fn zero_and_negative_frame_counts_return_empty_and_do_not_advance_state() {
        let profile = reference_mancala();
        let baseline = PadSynth::new(profile.clone()).render(500);
        let synth = PadSynth::new(profile);
        assert!(synth.render(0).is_empty());
        assert!(synth.render_f64(0).is_empty());
        assert!(synth.render_fade_out(0).is_empty());
        assert!(synth.render(-1).is_empty());
        assert!(synth.render_f64(-100).is_empty());
        assert!(synth.render_fade_out(-7).is_empty());
        assert_eq!(synth.render(500), baseline, "empty calls must not advance any DSP state");
    }

    // ---- Musical spec on PadSynthState -----------------------------------

    /// Extended-scale oracle for a chord tone: `degree + 2*voice` indexes a scale that is simply
    /// repeated one octave higher each time round -- a different formulation from the
    /// production floor-div/floor-mod walk.
    fn expected_tone_hz(root: f64, scale: &[i32], degree: i32, voice: i32) -> f64 {
        let extended: Vec<i32> = (0..8).flat_map(|o| scale.iter().map(move |s| s + 12 * o)).collect();
        let step = degree + 2 * voice;
        assert!(step >= 0 && (step as usize) < extended.len(), "oracle range");
        root * 2f64.powf(extended[step as usize] as f64 / 12.0)
    }

    fn close(actual: f64, expected: f64) -> bool {
        (actual - expected).abs() < 1e-9 * expected.abs().max(1.0)
    }

    /// CHESS's first two chords with hand-computed literals (A2 natural minor: chord 1 is
    /// A2/C3/E3, chord 2 -- degree 5 -- is F3/A3/C4), so a bug shared by the production formula
    /// and the oracle cannot hide.
    #[test]
    fn chess_chord_tones_match_hand_computed_frequencies() {
        let p = reference_chess();
        let mut bank = VoiceBank::new(3);
        write_chord_frequencies(&mut bank, 0, &p.chord_progression_degrees, &p.scale_intervals, 3, 110.0);
        for (got, want) in bank.freq_hz.iter().zip([110.0, 130.8127826503, 164.8137784564]) {
            assert!((got - want).abs() < 1e-6, "chord 1: got {got}, want {want}");
        }
        write_chord_frequencies(&mut bank, 1, &p.chord_progression_degrees, &p.scale_intervals, 3, 110.0);
        for (got, want) in bank.freq_hz.iter().zip([174.6141157165, 220.0, 261.6255653006]) {
            assert!((got - want).abs() < 1e-6, "chord 2: got {got}, want {want}");
        }
    }

    /// Every chord of every reference profile equals the extended-scale oracle, degrees
    /// wrapping past the scale length and the progression index wrapping past its own length.
    #[test]
    fn chord_tones_match_the_stacked_thirds_oracle_for_all_reference_shapes() {
        for (name, p) in reference_profiles() {
            let root = p.root_note_hz as f64;
            for degree_index in 0..(p.chord_progression_degrees.len() as i32 * 2 + 1) {
                let mut bank = VoiceBank::new(p.voice_count as usize);
                write_chord_frequencies(&mut bank, degree_index, &p.chord_progression_degrees, &p.scale_intervals, p.voice_count, root);
                let degree = p.chord_progression_degrees[degree_index as usize % p.chord_progression_degrees.len()];
                for v in 0..p.voice_count {
                    let want = expected_tone_hz(root, &p.scale_intervals, degree, v);
                    assert!(close(bank.freq_hz[v as usize], want), "{name} idx {degree_index} voice {v}: {} vs {want}", bank.freq_hz[v as usize]);
                }
                // Stacked, strictly rising voices.
                for v in 1..p.voice_count as usize {
                    assert!(bank.freq_hz[v] > bank.freq_hz[v - 1], "{name}: voices must rise");
                }
            }
        }
    }

    /// Degrees outside 0..scale-length wrap into higher/lower octaves like floor-div/floor-mod:
    /// degree 7 in a 7-note scale is the root an octave up, degree -1 is the 7th an octave down.
    #[test]
    fn out_of_range_degrees_wrap_octaves_like_floor_div_and_mod() {
        let mut bank = VoiceBank::new(1);
        write_chord_frequencies(&mut bank, 0, &[7], &MAJOR, 1, 100.0);
        assert!(close(bank.freq_hz[0], 200.0));
        write_chord_frequencies(&mut bank, 0, &[-1], &MAJOR, 1, 100.0);
        assert!(close(bank.freq_hz[0], 100.0 * 2f64.powf((11.0 - 12.0) / 12.0)));
        write_chord_frequencies(&mut bank, 0, &[9], &MAJOR_PENTATONIC, 1, 100.0); // 9 = octave 1, index 4
        assert!(close(bank.freq_hz[0], 100.0 * 2f64.powf(21.0 / 12.0)));
    }

    fn timing_profile() -> MusicProfileFfi {
        let mut p = mirrored(100.0, &[0, 3, 7, 10], &[0, 2, 1], 0.5, 2, 0.5, 0.0, 2000.0);
        p.crossfade_seconds = 0.25;
        p
    }

    /// The hold and crossfade lengths are converted to samples at 44.1 kHz exactly, the first
    /// change starts on the sample right after the hold, the crossfade lasts exactly
    /// `crossfade_samples`, and the next change comes one full hold after that. Catches an
    /// off-by-one, a doubled/halved hold, or the hold timer not restarting after a crossfade.
    #[test]
    fn chord_change_timing_is_exact() {
        let mut s = PadSynthState::new(&timing_profile());
        assert_eq!(s.chord_hold_samples, 22_050);
        assert_eq!(s.crossfade_samples, 11_025);
        let step = |s: &mut PadSynthState, n: i64| {
            for _ in 0..n {
                s.compute_next_sample();
            }
        };
        step(&mut s, 22_050);
        assert!(!s.crossfading && s.chord_index == 0, "still holding chord 1 after exactly one hold");
        step(&mut s, 1);
        assert!(s.crossfading && s.chord_index == 1, "crossfade starts on the sample after the hold");
        step(&mut s, 11_025 - 2);
        assert!(s.crossfading, "one sample of crossfade left");
        step(&mut s, 1);
        assert!(!s.crossfading, "crossfade finishes after exactly crossfade_samples");
        step(&mut s, 22_050 - 1);
        assert!(s.chord_index == 1 && !s.crossfading, "second hold not yet elapsed");
        step(&mut s, 1);
        assert!(s.chord_index == 1 && !s.crossfading, "hold of exactly 22050 samples elapsed, change not yet triggered");
        step(&mut s, 1);
        assert!(s.crossfading && s.chord_index == 2, "second change begins one hold after the first crossfade ended");
    }

    /// Conversion of seconds to samples for the CHESS-shaped reference numbers (20 s hold, 3 s
    /// crossfade); the shipped values are covered by `RustPadSynthCorrectnessTest`.
    #[test]
    fn reference_chess_hold_and_crossfade_sample_counts() {
        let s = PadSynthState::new(&reference_chess());
        assert_eq!(s.chord_hold_samples, 882_000);
        assert_eq!(s.crossfade_samples, 132_300);
    }

    /// Progression order: after every completed crossfade the sounding chord is the next degree in
    /// the list, wrapping around, for stacked tones of BOTH voices. A "skip one" or "advance by
    /// two" bug fails on the very first change.
    #[test]
    fn progression_plays_in_listed_order_and_wraps() {
        let profile = timing_profile(); // degrees [0, 2, 1] on a 4-note scale
        let mut s = PadSynthState::new(&profile);
        let root = 100.0;
        let degrees = [0, 2, 1, 0, 2, 1, 0];
        let want = |d: i32| [expected_tone_hz(root, &[0, 3, 7, 10], d, 0), expected_tone_hz(root, &[0, 3, 7, 10], d, 1)];
        assert!(close(s.current.freq_hz[0], want(0)[0]) && close(s.current.freq_hz[1], want(0)[1]));
        let mut idx = 1;
        let mut completed = 0;
        // Bounded by construction: six full hold+crossfade cycles plus slack. If chord changes
        // ever stop happening (a hold counter that never runs down, a crossfade that never ends)
        // this loop must run out and FAIL below, never spin forever and hang the test run.
        let budget = 6 * (s.chord_hold_samples + s.crossfade_samples + 2) + 100;
        for _ in 0..budget {
            let before = s.crossfading;
            s.compute_next_sample();
            if before && !s.crossfading {
                let w = want(degrees[idx]);
                assert!(close(s.current.freq_hz[0], w[0]) && close(s.current.freq_hz[1], w[1]),
                    "after change {idx}: got {:?}, want {:?}", s.current.freq_hz, w);
                idx += 1;
                completed += 1;
                if completed == 6 {
                    break;
                }
            }
        }
        assert_eq!(completed, 6, "expected six completed chord changes within {budget} frames");
    }

    /// Arpeggio note order is the spec's 0,1,2,1 walk over the chord's tones, one octave up,
    /// wrapping tone indices modulo the voice count for 2-voice chords, one note every
    /// `sample_rate / rate` samples starting on the first sample.
    #[test]
    fn arpeggio_walks_chord_tones_in_0_1_2_1_order_one_octave_up() {
        for voices in [3, 2] {
            let mut p = mirrored(150.0, &MAJOR_PENTATONIC, &[0], 100.0, voices, 0.3, 0.2, 3000.0);
            p.arpeggio_enabled = true;
            p.arpeggio_rate_hz = 441.0; // exactly 100 samples per note
            let mut s = PadSynthState::new(&p);
            assert_eq!(s.arp_samples_per_note, 100);
            let tones: Vec<f64> = (0..voices).map(|v| expected_tone_hz(150.0, &MAJOR_PENTATONIC, 0, v)).collect();
            let order = [0usize, 1, 2, 1];
            for note in 0..10usize {
                for _ in 0..100 {
                    s.compute_next_sample();
                }
                assert_eq!(s.arp_note_count as usize, note + 1, "one new note per 100 samples");
                let want = tones[order[note % 4] % voices as usize] * 2.0;
                assert!(close(s.arp_freq_hz, want), "voices {voices} note {note}: {} vs {want}", s.arp_freq_hz);
            }
        }
    }

    // ---- Gain chain, filter, LFO, crossfade shape -------------------------

    /// |H(e^{jw})| of the engine's one-pole `y += c (x - y)` filter, `c = 1 - exp(-2 pi fc / fs)`.
    fn one_pole_gain(cutoff: f64, freq: f64) -> f64 {
        let c = 1.0 - (-2.0 * PI * cutoff / 44100.0).exp();
        let w = 2.0 * PI * freq / 44100.0;
        let re = 1.0 - (1.0 - c) * w.cos();
        let im = (1.0 - c) * w.sin();
        c / (re * re + im * im).sqrt()
    }

    /// Single 1 kHz sine voice, no breathing/arp, long hold.
    fn tone_profile(cutoff: f32, base_volume: f32) -> MusicProfileFfi {
        let mut p = mirrored(1000.0, &[0], &[0], 100.0, 1, base_volume, 0.0, cutoff);
        p.breathing_depth = 0.0;
        p.crossfade_seconds = 1.0;
        p
    }

    /// The whole gain chain in one number: steady-state amplitude of a single centered sine
    /// voice = base_volume x (1/voices) x cos(pi/4) (equal-power center) x |H_lowpass(f)| x 1
    /// (startup fade finished) x 1 (breathing off). Checked for several cutoffs against the
    /// analytic one-pole response, on both channels. Catches a wrong voice normalisation, pan
    /// center gain, base-volume use, lowpass coefficient, or per-channel filter mix-up.
    #[test]
    fn steady_state_amplitude_equals_the_analytic_gain_chain() {
        for cutoff in [300.0f32, 1500.0, 6000.0] {
            let synth = PadSynth::new(tone_profile(cutoff, 0.4));
            let x = synth.render_f64(44_100);
            // 0.5 s..1.0 s: past the 0.35 s startup fade, exactly 500 whole 1 kHz periods.
            let window = &x[22_050 * 2..];
            let expected_rms = 0.4 * (PI / 4.0).cos() * one_pole_gain(cutoff as f64, 1000.0) / 2f64.sqrt();
            for (label, ch) in [("left", left_channel(window)), ("right", right_channel(window))] {
                let got = rms(&ch);
                assert!((got / expected_rms - 1.0).abs() < 0.01, "cutoff {cutoff} {label}: rms {got} vs analytic {expected_rms}");
            }
        }
    }

    /// The startup fade-in ramps linearly from silence over 0.35 s, and by 0.35 s the envelope is
    /// complete. (The "no click on the very first sample" claim lives in
    /// `startup_first_sample_is_scaled_down_by_the_envelope`, which needs a non-zero-phase
    /// waveform to be meaningful.)
    #[test]
    fn startup_ramps_in_from_silence_over_about_a_third_of_a_second() {
        let synth = PadSynth::new(tone_profile(20_000.0, 0.4));
        let x = synth.render_f64(44_100);
        let l = left_channel(&x);
        let early = rms(&l[..4410]); // first 0.1 s (envelope 0..0.29)
        let settled = rms(&l[30_000..]); // 0.68 s onward
        assert!(early < settled * 0.45, "startup must be well below steady level: {early} vs {settled}");
        let just_after = rms(&l[17_640..22_050]); // 0.40 s..0.50 s, envelope already 1.0
        assert!((just_after / settled - 1.0).abs() < 0.03, "envelope must be complete by 0.4 s: {just_after} vs {settled}");
    }

    /// No click at start: the very first output sample is scaled by the startup envelope (one
    /// ramp step, 1 / (0.35 s * 44100)). A pure sine is exactly 0 at phase 0 whatever the
    /// envelope does, so this profile uses brightness 1.0: the triangle starts at -1, giving a
    /// first pre-envelope sample of about -0.4 * cos(pi/4) * (one-pole step ~0.94) ~= -0.27. With
    /// the envelope the first sample must be below (steady peak / ramp length) * 2 (~1e-5); with
    /// the envelope removed it would be ~0.27, four orders of magnitude over the bound.
    #[test]
    fn startup_first_sample_is_scaled_down_by_the_envelope() {
        let mut p = tone_profile(20_000.0, 0.4);
        p.waveform_brightness = 1.0;
        let synth = PadSynth::new(p);
        let x = left_channel(&synth.render_f64(44_100));
        let ramp_len = 0.35 * 44_100.0;
        let peak = x[30_000..].iter().fold(0.0f64, |m, v| m.max(v.abs()));
        assert!(peak > 0.1, "sanity: the tone must reach an audible steady peak, got {peak}");
        let bound = peak / ramp_len * 2.0;
        assert!(x[0].abs() > 0.0, "the triangle starts at -1, so the first sample cannot be exactly zero");
        assert!(x[0].abs() < bound, "first sample {} must be below {bound} (no click at start)", x[0]);
        // The ramp then rises linearly: sample 1000 sits near 1000/ramp of the un-enveloped level.
        assert!(x[1000].abs() < peak * 1000.0 / ramp_len * 2.0, "ramp too steep at frame 1000: {}", x[1000]);
    }

    /// Breathing LFO: with depth d the level swings between (1-2d) and 1, so max/min window RMS
    /// over a full LFO period is ~1/(1-2d) (2.0 at d=0.25); depth 0 is flat.
    #[test]
    fn breathing_depth_modulates_level_and_zero_depth_is_flat() {
        let window_rms_ratio = |depth: f32| {
            let mut p = tone_profile(20_000.0, 0.4);
            p.breathing_rate_hz = 2.0;
            p.breathing_depth = depth;
            let x = left_channel(&PadSynth::new(p).render_f64(3 * 44_100));
            // 25 ms windows over 0.5 s..2.5 s (four LFO periods).
            let rmss: Vec<f64> = x[22_050..110_250].chunks(1100).filter(|c| c.len() == 1100).map(rms).collect();
            let max = rmss.iter().cloned().fold(f64::MIN, f64::max);
            let min = rmss.iter().cloned().fold(f64::MAX, f64::min);
            max / min
        };
        let deep = window_rms_ratio(0.25);
        assert!(deep > 1.8 && deep < 2.1, "depth 0.25 should swing ~2x, got {deep}");
        let flat = window_rms_ratio(0.0);
        assert!(flat < 1.02, "depth 0 must be flat, got {flat}");
    }

    /// Each channel owns its own low-pass memory. With the oscillator at phase 0 (pure sine = 0
    /// input on the first sample) and only the LEFT filter state pre-charged, the right channel
    /// must stay exactly silent and the left must decay by `(1 - c)` (times base volume, in f32
    /// precision, hence the 1e-7 tolerance); then the mirror case.
    /// Guards a right-channel filter that reads/writes the left channel's state (a channel bleed
    /// the tone-profile tests cannot see, because a single centred voice makes L == R).
    #[test]
    fn each_channel_has_its_own_lowpass_memory() {
        let charged = |left: f64, right: f64| {
            let mut s = PadSynthState::new(&tone_profile(1500.0, 0.4));
            s.envelope_gain = 1.0;
            s.lowpass_state_left = left;
            s.lowpass_state_right = right;
            s.compute_next_sample();
            (s.last_left, s.last_right, s.lowpass_coeff)
        };
        let (l, r, c) = charged(0.8, 0.0);
        assert!(c > 0.05 && c < 0.95, "coefficient must be strictly between 0 and 1, got {c}");
        assert_eq!(r, 0.0, "right channel must not see the left channel's filter memory");
        assert!((l - 0.8 * (1.0 - c) * 0.4).abs() < 1e-7, "left decays by (1-c): got {l}");
        let (l2, r2, _) = charged(0.0, 0.8);
        assert_eq!(l2, 0.0, "left channel must not see the right channel's filter memory");
        assert!((r2 - 0.8 * (1.0 - c) * 0.4).abs() < 1e-7, "right decays by (1-c): got {r2}");
    }

    fn crossfade_probe() -> MusicProfileFfi {
        // One centered voice; chord 1 = root 500 Hz, chord 2 = a fifth up (749 Hz).
        let mut p = mirrored(500.0, &[0, 7], &[0, 1], 2.0, 1, 0.4, 0.0, 20_000.0);
        p.breathing_depth = 0.0;
        p.crossfade_seconds = 2.0;
        p
    }

    /// Equal-power crossfade: mid-crossfade loudness equals steady loudness (a *linear* fade
    /// would dip to ~0.707), and no sample-to-sample step during/after the crossfade exceeds the
    /// steady-state maximum (a hard switch would jump by up to a full amplitude; the bound allows the ~1.2x of two summed tones). The two chords are 249 Hz apart so cross
    /// terms average out over the 0.5 s measuring window.
    #[test]
    fn crossfade_is_equal_power_and_click_free() {
        let x = left_channel(&PadSynth::new(crossfade_probe()).render_f64(6 * 44_100));
        let sr = 44_100usize;
        let steady_before = rms(&x[sr..sr + sr / 2]); // 1.0..1.5 s (chord 1)
        let mid = rms(&x[3 * sr - sr / 4..3 * sr + sr / 4]); // centered on t=3 s (hold 2 + xfade/2)
        let steady_after = rms(&x[5 * sr..5 * sr + sr / 2]); // 5.0..5.5 s (chord 2)
        assert!((mid / steady_before - 1.0).abs() < 0.06, "mid-crossfade RMS {mid} vs steady {steady_before}");
        assert!((steady_after / steady_before - 1.0).abs() < 0.06, "chord 2 must be as loud as chord 1: {steady_after} vs {steady_before}");

        let max_step = |seg: &[f64]| seg.windows(2).map(|w| (w[1] - w[0]).abs()).fold(0.0, f64::max);
        let steady_max = max_step(&x[sr..sr + sr / 2]).max(max_step(&x[5 * sr..5 * sr + sr / 2]));
        let around = max_step(&x[2 * sr - 500..4 * sr + 500]);
        assert!(around <= steady_max * 1.35, "click during chord change: {around} vs steady {steady_max}");
    }

    // ---- Stereo placement, waveform blend, arpeggio shape (state level) ----

    /// Each voice's fixed stereo position, straight from the constructed synth: the lowest voice
    /// is LEFT of center, the highest RIGHT, a middle voice exactly centered, the field is
    /// mirror-symmetric, every voice is equal-power, and for 2 voices the literal gains are
    /// `cos/sin(0.1 pi)` = 0.951/0.309 (pan -0.6 => theta = 0.4 pi/4). Hard-coded 0.6 spread, so
    /// a widened/narrowed field, a mirrored field or a linear pan law all fail.
    #[test]
    fn voices_are_placed_left_to_right_with_equal_power_at_the_spec_spread() {
        for voices in [2, 3, 4, 5, 8] {
            let mut p = test_profile();
            p.voice_count = voices;
            let s = PadSynthState::new(&p);
            let n = voices as usize;
            assert!(s.voice_left_gain[0] > s.voice_right_gain[0], "{voices} voices: lowest must be left");
            assert!(s.voice_right_gain[n - 1] > s.voice_left_gain[n - 1], "{voices} voices: highest must be right");
            for v in 0..n {
                let (l, r) = (s.voice_left_gain[v], s.voice_right_gain[v]);
                assert!((l * l + r * r - 1.0).abs() < 1e-12, "voice {v}/{voices} must be equal-power");
                assert!((l - s.voice_right_gain[n - 1 - v]).abs() < 1e-6, "mirror symmetry at voice {v}/{voices}");
                if v > 0 {
                    assert!(l < s.voice_left_gain[v - 1] && r > s.voice_right_gain[v - 1], "positions must move rightwards");
                }
            }
            if n % 2 == 1 {
                let mid = n / 2;
                assert!((s.voice_left_gain[mid] - s.voice_right_gain[mid]).abs() < 1e-12, "middle voice must be centered");
            }
        }
        let mut p = test_profile();
        p.voice_count = 2;
        let s = PadSynthState::new(&p);
        assert!((s.voice_left_gain[0] - 0.9510565163).abs() < 1e-6);
        assert!((s.voice_right_gain[0] - 0.3090169944).abs() < 1e-6);
        p.voice_count = 1;
        let single = PadSynthState::new(&p);
        assert!((single.voice_left_gain[0] - 0.5f64.sqrt()).abs() < 1e-12 && (single.voice_right_gain[0] - 0.5f64.sqrt()).abs() < 1e-12);
    }

    /// The oscillator is `sine*(1-b) + triangle*b` with a phase-0 triangle starting at -1 and
    /// peaking (+1) at phase 0.5; checked at hand-computable phases, so the blend weights, the
    /// triangle's shape/phase and b=0 / b=1 endpoints are all pinned.
    #[test]
    fn oscillator_blends_sine_and_triangle_by_brightness() {
        let sample_at = |phase: f64, b: f64| {
            let mut bank = VoiceBank::new(1);
            bank.phase[0] = phase;
            bank.freq_hz[0] = 100.0;
            oscillator_sample(&mut bank, 0, b, 44100.0)
        };
        // phase 0.25: sine = 1, triangle = 0.  phase 0.5: sine = 0, triangle = 1.
        // phase 0: sine = 0, triangle = -1.   phase 0.75: sine = -1, triangle = 0.
        for (phase, sine, tri) in [(0.25, 1.0, 0.0), (0.5, 0.0, 1.0), (0.0, 0.0, -1.0), (0.75, -1.0, 0.0), (0.125, 0.5f64.sqrt(), -0.5)] {
            for b in [0.0, 0.2, 0.5, 1.0] {
                let want = sine * (1.0 - b) + tri * b;
                let got = sample_at(phase, b);
                assert!((got - want).abs() < 1e-9, "phase {phase} b {b}: got {got}, want {want}");
            }
        }
        // The phase advances by freq/sample_rate and wraps back below 1.
        let mut bank = VoiceBank::new(1);
        bank.phase[0] = 0.995;
        bank.freq_hz[0] = 441.0; // +0.01 per sample
        oscillator_sample(&mut bank, 0, 0.0, 44100.0);
        assert!((bank.phase[0] - 0.005).abs() < 1e-9, "phase must wrap, got {}", bank.phase[0]);
    }

    /// The plucked arpeggio is one melodic line, dead center: with vs without it, the difference
    /// is identical on both channels, while the pad itself is not (voices are panned).
    #[test]
    fn arpeggio_layer_is_identical_on_both_channels_while_the_pad_is_stereo() {
        let with_arp = reference_mancala();
        let mut no_arp = with_arp.clone();
        no_arp.arpeggio_enabled = false;
        let frames = 44_100;
        let a = PadSynth::new(with_arp).render_f64(frames);
        let b = PadSynth::new(no_arp).render_f64(frames);
        let mut max_arp = 0.0f64;
        let mut max_asym = 0.0f64;
        let mut max_pad_lr = 0.0f64;
        for i in 0..frames as usize {
            let (dl, dr) = (a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1]);
            max_arp = max_arp.max(dl.abs()).max(dr.abs());
            max_asym = max_asym.max((dl - dr).abs());
            max_pad_lr = max_pad_lr.max((b[2 * i] - b[2 * i + 1]).abs());
        }
        assert!(max_arp > 1e-3, "arpeggio must be audible, got {max_arp}");
        assert!(max_asym < 1e-9, "arpeggio must be dead center, L-R asymmetry {max_asym}");
        assert!(max_pad_lr > 5e-3, "pad must be genuinely stereo, max |L-R| {max_pad_lr}");
    }

    /// One pluck's envelope: a 6 ms exponential attack from ~0 (no click at note start), then an
    /// exponential decay ending near -34 dB (0.02 of the peak-ish) by the time the next note
    /// starts -- the arpeggio's click-avoidance contract.
    #[test]
    fn arpeggio_pluck_has_a_six_ms_attack_then_decays_to_near_silence() {
        let mut p = mirrored(150.0, &MAJOR_PENTATONIC, &[0], 100.0, 3, 0.3, 0.2, 3000.0);
        p.arpeggio_enabled = true;
        p.arpeggio_rate_hz = 10.0; // 4410 samples per note
        let mut s = PadSynthState::new(&p);
        assert_eq!((s.arp_samples_per_note, s.arp_attack_samples), (4410, 264));
        let mut env = Vec::new();
        for _ in 0..4410 {
            s.compute_next_sample();
            env.push(s.arp_envelope);
        }
        assert!(env[0] < 0.01, "note must start from ~silence, got {}", env[0]);
        assert!(env[..264].windows(2).all(|w| w[1] > w[0]), "attack must rise monotonically");
        assert!(env[263] > 0.55 && env[263] < 0.70, "1 - e^-1 ~ 0.63 after one attack time constant, got {}", env[263]);
        assert!(env[264..].windows(2).all(|w| w[1] < w[0]), "decay must fall monotonically");
        assert!(env[4409] < 0.05, "must have decayed to near silence by note end, got {}", env[4409]);
    }
}
