# Rust Audio Core — Implementation Plan

Implements `docs/RUST_AUDIO_CORE_ADR.md` (ADR-3, accepted 057188d). Follows the same
Cargo-workspace/cargo-ndk/UniFFI pattern the concurrent `rust/gamesuite-sim` Air Hockey pilot
already proved end-to-end (commit `fa3c69f`, hardened by `d69c584`) — this plan exists specifically
because that pattern is now known-working, not theoretical.

## 0. Coordination check (ADR-3 Action Item 1 — now satisfied)

Confirmed by the Air Hockey audit that produced this plan, not assumed:
- **UniFFI version**: `0.32.1` (`rust/gamesuite-sim/Cargo.toml`) — reuse exactly, don't drift.
- **Workspace shape**: `rust/Cargo.toml` is a 2-member workspace (`gamesuite-sim`,
  `uniffi-bindgen`) with one shared `[profile.release]` (`lto = true`, `codegen-units = 1`). The
  new crate joins as a third member; the release profile applies automatically, no per-crate
  profile needed.
- **Bindgen crate is shared, not per-crate**: `rust/uniffi-bindgen` (its own package, deliberately
  excluding UniFFI's `cli` feature from the cdylib crates — see that crate's own `src/main.rs`
  KDoc for the real OOM incident that motivated the split) already runs `uniffi::uniffi_bindgen_main()`
  generically against whatever `--library <path>` it's pointed at. **No new bindgen crate needed** —
  the new crate's Gradle task points the existing binary at the new crate's own compiled `.so`.
- **Gradle task naming convention**: `cargoNdkBuild<X>` / `generate<X>UniffiBindings`, wired into
  `preBuild`, every `compile*Kotlin` task, and every `merge*JniLibFolders` task. Mirror exactly.
- **cargo-ndk invocation shape**: 3 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`), platform level 26
  (matches `minSdk`), `-o <jniLibs>`. `Cargo.lock` and the command's own args/ABI-list/platform
  **must** be declared Gradle task inputs (`d69c584` fixed exactly this gap for the sim crate —
  don't reintroduce it here).

## 1. Scaffold `rust/gamesuite-audio` (crate skeleton only, no real DSP yet)

Goal: prove the toolchain end-to-end for a *second* crate before porting any math — mirrors how
`gamesuite-sim` itself started.

- `rust/Cargo.toml`: add `"gamesuite-audio"` to `members`.
- `rust/gamesuite-audio/Cargo.toml`:
  ```toml
  [package]
  name = "gamesuite-audio"
  version = "0.1.0"
  edition = "2021"
  publish = false
  description = "GameSuite real-time audio DSP core (AmbientMusicEngine pilot) — allocation-free synth, exposed to Kotlin via UniFFI."

  [lib]
  name = "gamesuite_audio"
  crate-type = ["cdylib"]

  [dependencies]
  uniffi = "0.32.1"
  ```
  (No `rand` equivalent needed — `PadSynthState` has no randomness, unlike the sim crate's
  serve-direction RNG.)
- `rust/gamesuite-audio/uniffi.toml`:
  ```toml
  [bindings.kotlin]
  package_name = "com.gamesuite.audio.rust"
  cdylib_name = "gamesuite_audio"
  ```
  (`com.gamesuite.audio.rust`, not bare `com.gamesuite.audio` — that Kotlin package already holds
  `AmbientMusicEngine.kt`/`ProceduralSfx.kt`; a distinct sub-package avoids any generated-vs-
  handwritten name collision, matching how `com.gamesuite.sim` stayed disjoint from
  `com.gamesuite.games.airhockey`.)
- `rust/gamesuite-audio/src/lib.rs`: empty-stub `PadSynth` object + `MusicProfileFfi` record
  matching the ADR's FFI Surface section exactly (`render`/`render_fade_out` returning a fixed
  silent buffer of the requested length — proves marshaling, not math, first).
- Gradle (`app/build.gradle.kts`): add `cargoNdkBuildAmbientAudio` / `generateAmbientAudioUniffiBindings`
  Exec tasks, copied structurally from `cargoNdkBuildAirHockeySim` /
  `generateAirHockeySimUniffiBindings` (same `inputs.dir`/`inputs.file`/`inputs.property` pattern,
  including `Cargo.lock` as an input from day one this time — no fix-later gap). New
  `uniffiAudioBindingsOutDir` alongside the existing `uniffiBindingsOutDir`, both added to
  `android.sourceSets.getByName("main").java.srcDir(...)`. Wire into the same `preBuild`/
  `compile*Kotlin`/`merge*JniLibFolders` task-matching blocks (they already match by task-name
  pattern, so both crates' tasks attach automatically — no duplicate wiring logic needed there).
- **Verify**: `cargo build --release` in `rust/gamesuite-audio` succeeds standalone; `cargo ndk`
  cross-compile succeeds for all 3 ABIs; `./gradlew :app:compileDebugKotlin` picks up generated
  `com.gamesuite.audio.rust.PadSynth`/`MusicProfileFfi` Kotlin bindings with no errors. Nothing in
  the app calls the new bindings yet — this step only proves the pipe is open.

## 2. Port `PadSynthState`'s pure DSP to Rust

Source of truth: `app/src/main/java/com/gamesuite/audio/AmbientMusicEngine.kt:469-701` (the private
`PadSynthState` class) plus its two free functions `equalPowerPanGains`/`voicePan`
(`AmbientMusicEngine.kt:78-95`, both already pure/testable and already covered by
`AmbientMusicEngineTest`). `f64` throughout, matching Kotlin's `Double` — this is a correctness
pilot, not a `f32`/SIMD perf pass (ADR-3's own named, deliberate trade-off).

Port faithfully, field for field:
- `VoiceBank` (phase/freqHz arrays) → a small Rust struct, `Vec<f64>` sized by `voice_count`.
- `equalPowerPanGains`/`voicePan` → free functions, ported 1:1 (they're already the two functions
  this project's own Kotlin tests treat as the load-bearing pure math — port their *tests* too, see
  Rust test list below, not just the implementation).
- `computeNextSample()`'s full stage order, unchanged: chord-change trigger → crossfade mix (or
  plain mix) → breathing LFO → arpeggio layer → independent per-channel one-pole lowpass → envelope
  gain → `last_left`/`last_right`. Keep the same "write to two fields, don't return a tuple"
  allocation-avoidance shape Kotlin already uses — the whole point of this port is to *keep* that
  discipline while removing the GC risk around it, not to relax it now that Rust is memory-safe.
- `writeChordFrequencies` (stacked-thirds scale-degree walk), `oscillatorSample` (sine/triangle
  blend), `nextArpeggioSample` (attack/decay envelope) — direct ports, same formulas.
- `render(frame_count) -> Vec<u8>` / `render_fade_out(frame_count) -> Vec<u8>`: interleaved stereo
  16-bit LE PCM, `frame_count * 4` bytes — matches the ADR's FFI Surface exactly. Internally calls
  `compute_next_sample()` per frame and writes `to_pcm16(value)` (`(value.clamp(-1.0, 1.0) *
  i16::MAX as f64) as i16`, little-endian bytes) — same clamp-then-scale Kotlin's `toPcm16` does.
- `MusicProfileFfi` → `MusicProfile` (Rust-internal struct with defaults applied at construction
  time, mirroring `MusicProfile`'s Kotlin default-parameter values) — the FFI dictionary is the
  wire type; keep an internal Rust type distinct from it if construction-time derived fields
  (`chord_hold_samples`, `crossfade_samples`, `lowpass_coeff`, etc.) read more clearly that way,
  matching how Kotlin's own `PadSynthState` derives those once in its own field initializers rather
  than recomputing per sample.

**Rust unit tests** (in `rust/gamesuite-audio/src/lib.rs`'s own `#[cfg(test)] mod tests`, mirroring
`gamesuite-sim`'s existing test-per-ported-behavior convention):
- `equal_power_pan_gains_sums_to_one` — `left² + right² == 1.0` across several pan values (ports
  `AmbientMusicEngineTest`'s existing equivalent Kotlin assertion).
- `voice_pan_is_symmetric_and_endpoints_are_correct` — voice 0 leftmost, last voice rightmost,
  single-voice case stays centered (ports the existing Kotlin `voicePan` test cases at voice counts
  5-8 added in this session's own Phase 7 pass).
- `single_voice_profile_does_not_divide_by_zero` — `voice_count <= 1` guard.
- `render_produces_correct_byte_length` — `render(n).len() == n * 4`.
- `crossfade_completes_and_swaps_current_bank` — after `crossfade_samples` frames, `current`
  reflects the values `incoming` held before the swap.

## 3. UniFFI surface + Kotlin wiring

- Finalize `#[uniffi::export]`/`#[derive(uniffi::Object)]`/`#[derive(uniffi::Record)]` annotations
  on the real (non-stub) `PadSynth`/`MusicProfileFfi` from Step 1's skeleton.
- New Kotlin file `app/src/main/java/com/gamesuite/audio/RustPadSynth.kt` (mirrors
  `AirHockeyRustGame.kt`'s translation-layer role): converts `MusicProfile` (Kotlin) →
  `MusicProfileFfi` (generated) once at construction, wraps the generated `PadSynth`, and exposes
  `render(chunk: ShortArray)` / `renderFadeOut(chunk: ShortArray)` methods with the **exact same
  signature** `PadSynthState.render`/`renderFadeOut` already have — the byte-array-to-`ShortArray`
  conversion (`bytes` → interleaved `Short` little-endian pairs) lives here, once, not scattered
  into `AmbientMusicEngine`. This mirrors `AirHockeyRustGame.kt`'s own `toAirHockeyState()`
  translation-boundary pattern.
- **Do not touch `AmbientMusicEngine.start()`/`runGeneratorLoop()`/`stop()` yet** — those keep
  calling whatever `PadSynthState`-shaped object `runGeneratorLoop` is holding; Step 5 is the only
  step that flips which implementation that is. Keeping the swap to one line in one place is
  deliberate, same "single seam" discipline `AirHockeyRustGame`'s own KDoc calls out for why the
  Kotlin `AirHockeyGame` was left completely untouched during that port.

## 4. Correctness verification (ADR-3 Verification Plan #1)

New test: `app/src/test/java/com/gamesuite/audio/RustPadSynthCorrectnessTest.kt`.
- For every entry in `MusicProfiles` (all 20 named profiles — the real breadth this ADR's own
  Decision section commits to, not one hardcoded profile), construct both a Kotlin `PadSynthState`
  and a `RustPadSynth` from the identical `MusicProfile`, render N chunks (`CHUNK_FRAMES`-sized,
  enough to cross at least one full chord crossfade for every profile — the slowest profile,
  `TIC_TAC_TOE`, holds 24s; render enough chunks to cover that) from both, and diff sample-by-sample.
- **Tight tolerance, not bit-exact** — per the ADR, JVM JIT vs. rustc optimizer reordering can
  introduce ULP-level drift from identical math. Use a small fixed epsilon on the decoded `f64`-
  equivalent sample value (not the quantized `Short`, which would hide real per-sample drift behind
  16-bit rounding) — start at `1e-9` relative, widen only if a genuine, understood floating-point
  reordering (not a real logic bug) requires it, and document why in the test if so.
- `PadSynthState` currently has no public accessor for its internal per-sample `Double` output —
  add one test-only seam if needed (an internal `renderF64(frameCount): DoubleArray` alongside the
  existing `Short`-quantizing `render`, called by both the real engine and this test) rather than
  reverse-engineering doubles out of quantized `Short`s, which would fail to catch a bug smaller
  than one quantization step.
- This test is the actual gate for Step 5 — do not proceed until it passes for all 20 profiles.

## 5. GC-pause verification (ADR-3 Verification Plan #2)

- Instrumented on-device run (mirrors `docs/TOWER_DEFENCE_ADR.md`'s own precedent for "measure the
  real thing, don't assume"): a sustained-playback stress loop (long-running screen navigation with
  `rememberAmbientMusic` active throughout, several minutes, on a real device — not an emulator),
  captured via the Android Studio profiler / `adb shell dumpsys meminfo` GC-event log, once against
  the existing Kotlin `PadSynthState` path and once against `RustPadSynth`.
- Report the real GC pause frequency/duration for both, honestly, even if the result is "no
  measurable difference" — the ADR explicitly commits to this outcome being reported, not buried,
  and to reopening Option 2 (harden Kotlin further) if so.
- Only proceed to Step 6 if this shows a genuine, measurable improvement (or is judged neutral-but-
  worth-shipping-anyway for the structural-immunity argument alone — a call for whoever reviews
  this step's actual measured numbers, not decided in advance here).

## 6. Cutover

- Flip `AmbientMusicEngine.runGeneratorLoop()`'s single `PadSynthState(profile)` construction to
  `RustPadSynth(profile)` — the one-line seam Step 3 deliberately preserved.
- Delete `PadSynthState` (the private class) and the Kotlin-side `computeNextSample`/
  `oscillatorSample`/`nextArpeggioSample`/`writeChordFrequencies` internals — matches this
  project's own established practice of not maintaining two parallel implementations once a KMP/
  Rust pilot is proven (Tic-Tac-Toe/Air Hockey/Chess all converged to one canonical implementation;
  see ADR-3's own Action Item 5).
- **Keep** `equalPowerPanGains`/`voicePan` in Kotlin exactly as they are — `ProceduralSfx.kt`'s
  `panToStereo` still calls `equalPowerPanGains` directly for its own one-shot stereo placement,
  which is explicitly out of scope for this pilot (ADR-3 Action Item 6). Only `PadSynthState`'s
  internals move to Rust; the two shared pan functions stay Kotlin-side, dual-purpose, unchanged.
- Full regression pass: `./gradlew :app:testDebugUnitTest :app:assembleDebug`, on-device smoke test
  of every game screen's ambient music (all 20 `MusicProfiles` entries reachable through real
  screens) to confirm no audible regression — this is real-time audio, where a unit-test-green
  build can still ship an audible click or dropout a diff-based test wouldn't catch.

## Explicitly deferred (ADR-3 Action Item 6 — do not fold into this plan)

- `ProceduralSfx`'s own five one-shot renders staying Kotlin.
- Desktop's `javax.sound.sampled` audio sink (a separate, real, currently-nonexistent piece of
  work — Desktop has no audio output at all today, Rust or otherwise).
