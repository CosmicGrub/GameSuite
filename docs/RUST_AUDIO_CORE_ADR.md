# ADR-3: A Rust DSP Core for AmbientMusicEngine

**Status:** Accepted — pilot scoped, not yet implemented
**Date:** September 2026
**Deciders:** the project owner

## Context

This grew out of a broader "what else could raise this app's premium feel" pitch, of which this
was the strongest, most differentiated recommendation: a Rust core for the real-time procedural
audio engines, rather than any of the whole-app engine/language questions `docs/ENGINE_DECISION.md`
already settled (KMP/CMP adopted, iOS withdrawn entirely, Unity/Flutter/Godot/native-per-platform
all evaluated and rejected). This ADR is narrower and additive: one native language, behind one
existing seam, for one specific already-identified pain point — it does not reopen anything
`ENGINE_DECISION.md` decided.

`app/src/main/java/com/gamesuite/audio/AmbientMusicEngine.kt` (724 lines) is a genuinely real,
shipped system: continuous procedural ambient music, no bundled loop files, synthesized sample by
sample on a dedicated 44.1kHz generator thread and fed to `android.media.AudioTrack` in
`MODE_STREAM`. It already does real work — per-`MusicProfile` chord/arpeggio synthesis, equal-power
stereo panning per voice, independent per-channel lowpass filtering, click-free crossfades between
chords. `ProceduralSfx.kt` (310 lines) is its simpler sibling — five short one-shot renders, no
continuous thread.

**The concrete, already-documented pain point**: `AmbientMusicEngine.kt`'s own KDoc records a real
fix already made — the per-sample hot loop was deliberately rewritten from a `Double`-returning
`nextSample()` into a void-returning `computeNextSample()` that writes `lastLeft`/`lastRight`
instance fields, specifically to avoid a boxed `Pair<Double,Double>` allocation per sample on that
real-time thread. This is a team already fighting the JVM's memory model by hand, in exactly the
place where a stall is most audible (a click or dropout, not a dropped frame that just looks a
little late).

**Two adjacent facts that shape this ADR's actual scope, not just its motivation:**
- Desktop has no audio at all today — no `AudioTrack` equivalent exists yet in this codebase.
  `docs/ENGINE_DECISION.md`'s own Action Item 8 already flagged real-time audio as the one "premium
  2026" pillar that, unlike shaders (`DesktopShaders.kt`/Skiko SkSL, already shipped on Desktop),
  has no Desktop story yet.
- A concurrent session independently started `rust/gamesuite-sim` — a Rust+UniFFI real-time
  simulation core for an *Air Hockey physics* pilot (`cdylib`, UniFFI 0.32.1 library mode, an
  LTO/strip release profile whose own comment says "no GC pauses") — completely independently of
  this ADR, for a different subsystem. Discovered mid-brainstorm, very early (workspace + one crate
  manifest + a bindgen shim, no simulation code yet). Two independent passes converging on the same
  tool for the same class of problem (a real-time Kotlin hot loop that can't tolerate a GC pause)
  is a real, if informal, second data point in this decision's favor — and it directly shapes
  Action Item 1 below.

## Decision

**Port `PadSynthState` — `AmbientMusicEngine`'s entire pure-DSP class (oscillators, crossfade,
breathing LFO, per-channel lowpass, the arpeggio layer) — to Rust, exposed to Kotlin via UniFFI,
as a second member of the `rust/` Cargo workspace the concurrent Air Hockey pilot already created.**

Scoped deliberately narrow, on purpose, the same "honest MVP" instinct this whole batch of work has
used repeatedly (Tower Defence's one tower type, Edge Match's rotate-only, Kakuro's fixed
templates):

- **DSP only, not audio output.** Rust exposes "render me the next N frames of interleaved stereo
  PCM" — a small, infrequent FFI call (~20/sec, one per ~50ms chunk, not once per sample; the
  44.1kHz inner loop stays entirely inside Rust). `AmbientMusicEngine`'s outer shell —
  `AudioTrack`, the generator thread, start/stop lifecycle — stays exactly as it is in Kotlin.
  Rejected the alternative (Rust owns playback too, via a crate like `cpal`) specifically to keep
  the FFI surface tiny and to reuse Android's already-working audio sink untouched, rather than
  standing up a second, unproven audio-output path at the same time as the FFI question.
- **`AmbientMusicEngine` is the pilot, not `ProceduralSfx`.** It's the harder port (real
  crossfading state, multiple interacting DSP stages) but it's also where the actual documented
  pain lives — proving Rust on the file that already needed a hand-optimization workaround is
  proving the thing that motivated this ADR, not a proxy for it.
- **Android only, this pass.** Desktop's own `javax.sound.sampled` audio sink is real, needed, and
  explicitly out of scope here — bundling "does the Rust/UniFFI pipe actually work" together with
  "Desktop has never played a sound before" would stack two unproven things into one pilot, cutting
  against this project's own repeated practice of isolating one new risk at a time (see
  `docs/TOWER_DEFENCE_ADR.md`'s own Action Item 8 reasoning for the identical call). Once this pilot
  lands, the Rust core is already platform-agnostic — giving Desktop a sink that calls the same
  core becomes a small, strictly-additive follow-up.
- **UniFFI, not hand-rolled JNI.** Real, current, actively-maintained Kotlin Multiplatform bindings
  generation (targets both JVM and Native), already the concurrent Air Hockey pilot's own choice.
  Generated bindings mean far less handwritten unsafe-adjacent JNI glue — exactly the class of
  error-prone C-adjacent code most likely to hide a subtle memory-safety bug in an agent-authored
  codebase.
- **Joins the existing `rust/` workspace** (`gamesuite-audio` alongside `gamesuite-sim`) rather than
  standing up a second, parallel Cargo workspace — one toolchain version, one release profile, one
  Gradle integration task to write and maintain, not two independently-built copies of the same
  plumbing.

## FFI Surface

The concrete shape of the boundary — small and infrequent on purpose, since the 44.1kHz inner loop
never leaves Rust:

```
interface PadSynth {
    constructor(profile: MusicProfileFfi);
    bytes render(i32 frame_count);       // interleaved stereo, 16-bit LE PCM
    bytes render_fade_out(i32 frame_count);
};

dictionary MusicProfileFfi {
    f32 root_note_hz;
    sequence<i32> scale_intervals;
    sequence<i32> chord_progression_degrees;
    f32 chord_duration_seconds;
    i32 voice_count;
    f32 base_volume;
    f32 crossfade_seconds;
    f32 breathing_rate_hz;
    f32 breathing_depth;
    f32 waveform_brightness;
    f32 lowpass_cutoff_hz;
    boolean arpeggio_enabled;
    f32 arpeggio_rate_hz;
    f32 arpeggio_volume;
};
```

`render`/`render_fade_out` are called once per `CHUNK_FRAMES` (~50ms) from the same Kotlin
generator thread that owns them today — ~20 calls/sec, ~8.8KB per call — not once per sample. The
exact `bytes`-to-`ShortArray` conversion on the Kotlin side is an implementation detail for the
plan, not this ADR.

## Options Considered

### 1. Rust DSP core via UniFFI (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Medium. The DSP algorithm itself is a faithful, mechanical port (the Kotlin is already pure math with a clean boundary — `PadSynthState` has zero Android imports today); the new surface area is the Rust toolchain, Cargo workspace, and UniFFI codegen integration into Gradle, already half-proven by the concurrent Air Hockey pilot. |
| Cost | Low. Rust and UniFFI are both free, Apache/MIT-licensed, no seat cost. Real ongoing cost is a second toolchain to keep updated (`rustc`, `cargo`, UniFFI version) alongside Kotlin's. |
| Scalability | High, and not hypothetical — the concurrent `gamesuite-sim` pilot means this ADR's own workspace-sharing decision immediately benefits a second, independent use case (physics), not just audio; the same pattern is available to any future real-time-sensitive Kotlin hot loop. |
| Team familiarity (AI-agent fit) | Medium. Rust itself has deep, mainstream LLM training coverage and UniFFI's codegen removes most of the hand-written FFI risk — but this is genuinely the first Rust in this codebase, unlike every option `ENGINE_DECISION.md` evaluated, which all stayed in Kotlin. |

**Pros**
- Structural GC-pause immunity, not a hand-maintained invariant — the actual claim this ADR exists
  to test (see Verification Plan), stronger than "we were careful this one time."
- Reuses a boundary the Kotlin code already draws cleanly (`PadSynthState` vs. `AudioTrack`) — this
  is a substitution behind an existing seam, not a redesign.
- Directly shareable with Desktop later (the core itself has zero Android-specific code), and with
  `ProceduralSfx` later, without redoing the toolchain work.
- A second, independent pass (the Air Hockey pilot) already validated the same tool choice for the
  same class of problem, and this ADR's workspace-sharing decision means that work and this work
  reinforce rather than duplicate each other.

**Cons**
- A genuinely new toolchain and failure mode for this codebase — a Rust panic or a UniFFI
  marshaling bug is a new category of crash this app has never had to debug before.
- `f64` throughout (matching Kotlin's own `Double`, chosen for verification precision — see below)
  leaves real performance headroom (`f32`, SIMD) unclaimed in this first pass; a deliberate,
  named trade of raw speed for a clean correctness proof, not an oversight.
- Two toolchains to keep in sync in CI/local dev going forward, where there was one.

### 2. Keep hand-rolled Kotlin, invest further in allocation-avoidance

| Dimension | Assessment |
|---|---|
| Complexity | Low incremental effort per step, but each subsequent optimization is a smaller win than the last — `computeNextSample()`'s rewrite already claimed the easy, obvious allocation. Further headroom exists (JDK's Foreign Function & Memory / Project Panama API for off-heap scratch buffers, pinning the generator thread's priority) but is real, unfamiliar-to-this-codebase JVM-internals work in its own right, not free. |
| Cost | Lowest — zero new toolchain, zero new language. |
| Scalability | Low. Every future DSP addition (a reverb tail, more simultaneous voices, richer filters) re-opens the same allocation-discipline risk by hand, forever — there's no structural guarantee, just vigilance, and vigilance doesn't scale across an agentic build loop the way a borrow checker does. |
| Team familiarity (AI-agent fit) | Highest — zero new language, matches every prior decision in this codebase. |

**Pros**
- No new toolchain, no new crash category, no coordination with the concurrent Rust pilot needed.
- Real headroom does exist (off-heap `MemorySegment` scratch space via the FFM API is a genuine,
  modern, pure-JVM technique) — this isn't a strawman option.

**Cons**
- Doesn't solve the actual structural problem this ADR names: a zero-allocation Kotlin thread can
  still be paused by garbage generated *anywhere else in the process*, since the JVM heap is shared
  app-wide. No amount of local discipline in this one file changes that.
  Was genuinely considered and rejected for this reason, not skipped — the "harder, not different"
  alternative this ADR's own scoping brief asked to be represented, not a token entry.
- Every future contributor (human or agent) touching this file inherits the same manual-discipline
  burden with no compiler enforcement, and no test can catch "someone added an allocation" the way
  a type system can.

### 3. C++ via Oboe/AAudio

| Dimension | Assessment |
|---|---|
| Complexity | Medium — Oboe is Google's own purpose-built low-latency audio library, built specifically for exactly this problem (real-time PCM generation with minimal latency/jitter), with mature JNI integration patterns already well-documented in the wider Android community. |
| Cost | Low — Apache-2.0, no licensing cost, but adds a THIRD toolchain to this codebase (Kotlin + the concurrent pilot's Rust + this), rather than sharing tooling with `gamesuite-sim`. |
| Scalability | Medium — Android-only by construction (AAudio has no Desktop/cross-platform story at all), so this option answers today's problem but actively works against the later "share this core with Desktop" goal DSP-only Rust keeps open. |
| Team familiarity (AI-agent fit) | Lower than Rust for this specific codebase, concretely: no borrow checker (manual memory management reintroduces a real class of native-code bug this project has never had to manage), and — decisively — zero benefit from the concurrent Air Hockey pilot's own Rust/UniFFI investment, which this option can't reuse at all. |

**Pros**
- Android's own recommended, first-party answer to low-latency real-time audio specifically —
  genuinely purpose-built for this exact problem, not a general-purpose language pressed into
  service.
- Very mature, widely-documented JNI integration patterns in the broader Android dev community.

**Cons**
- No path to Desktop at all — AAudio is an Android system API with no cross-platform equivalent,
  permanently narrowing this option to a single platform in a way Rust doesn't.
- A third native toolchain in this codebase rather than a second, duplicating (not sharing)
  tooling investment with the concurrent Rust pilot.
- No compile-time memory safety the way Rust's borrow checker provides — trades one class of risk
  (a new toolchain) for another (manual native memory management) without Rust's own mitigation.

## Trade-off Analysis

The real choice is between Option 1 (Rust) and Option 2 (harden Kotlin further) — Option 3 (C++/
Oboe) is dispatched by the same fact that decides most of this ADR: it can't share anything with
the concurrent Air Hockey pilot's own Rust investment, and it permanently forecloses Desktop, which
Rust deliberately keeps open without committing to it yet. Between the two live options, Option 2's
real strength — zero new toolchain — doesn't answer the ADR's own stated problem: the claim was
never "this file allocates too much," it was "a shared-heap JVM thread can be paused by garbage it
didn't create," and no amount of local discipline in `PadSynthState` changes that structural fact.
Option 2 remains a legitimate, considered alternative — worth revisiting if the verification plan
below finds Rust *doesn't* meaningfully change real-world GC-pause behavior, which is a real
possible outcome this ADR commits to measuring honestly, not assuming.

## Consequences

**Easier, once this pilot lands:**
- `AmbientMusicEngine`'s hot loop gets structural GC-pause immunity instead of a hand-maintained
  invariant — future DSP additions (a reverb tail, more voices) no longer need the same manual
  allocation vigilance `computeNextSample()`'s own rewrite required.
- The Rust core is immediately reusable for `ProceduralSfx` and for a future Desktop audio sink,
  without redoing any toolchain work — both explicit, named, deliberately deferred follow-ups, not
  silent scope creep now.
- The `rust/` workspace gets a real second member, proving it as genuine shared infrastructure
  rather than a one-off for the Air Hockey pilot alone.

**Harder, and worth naming honestly:**
- A new toolchain (Rust + UniFFI + Cargo) to install, update, and keep working in this project's
  actual Windows/Gradle build — coordinated with, not independent of, whatever the concurrent Air
  Hockey pilot's own Gradle integration ends up looking like.
- A genuinely new crash category (a Rust panic crossing the UniFFI boundary, a marshaling bug) this
  app has never had to debug before.
- `f64` precision throughout is a deliberate near-term performance trade for verification clarity —
  a future optimization pass revisiting `f32`/SIMD is explicitly left for later, not decided here.

**What will need to be revisited later, deliberately, not silently:**
- Whether `ProceduralSfx` gets the same treatment, once `AmbientMusicEngine`'s pilot is proven.
- Whether/when Desktop gets its own `javax.sound.sampled` sink calling this same core — a real,
  named follow-up, not assumed to happen automatically.
- Whether the verification plan's GC-pause measurement actually shows a meaningful real-world
  difference — if it doesn't, Option 2 (harden Kotlin further) is back on the table honestly, not
  foreclosed by having shipped Rust once.

## Verification Plan

Two separate claims, two separate measurements — the same "measure the real thing, don't assume"
discipline `docs/TOWER_DEFENCE_ADR.md`'s own on-device stress test already established for this
project:

1. **Correctness**: an automated test feeds the same `MusicProfile` and the same starting state
   into both the existing Kotlin `PadSynthState` and the new Rust-backed synth, and diffs their PCM
   output over many chunks. A tight numerical tolerance, not literal bit-exactness — IEEE 754
   operation reordering between the JVM JIT and rustc's optimizer can introduce ULP-level drift even
   from identical math, and this ADR won't claim a stronger guarantee than that. Same
   "independently re-verify, don't trust it because it compiles" idiom this project's own puzzle
   solvers (Sudoku/Nonogram/Kakuro) and scoring algorithms (Mastermind/Word Guess) already use.
2. **The actual GC-immunity claim**: instrument both versions under sustained playback (a
   long-running screen-navigation stress loop) and capture real GC pause frequency/duration via the
   Android profiler — not assumed, measured. If this doesn't show a meaningful difference, that's a
   real finding this ADR commits to reporting honestly (see Consequences above), not burying.

## Action Items

1. **Coordinate with the concurrent Air Hockey pilot before touching `rust/`** — confirm the
   UniFFI version (0.32.1, per its own `Cargo.toml`) and the shape of whatever Gradle integration
   task it lands (the `uniffi-bindgen.rs` shim's own comment already names a
   `generateUniffiKotlinBindings` task, not yet written as of this ADR) before adding
   `gamesuite-audio` as a second workspace member, so the two crates share one convention rather
   than two.
2. **Stand up `gamesuite-audio` as a second `rust/` workspace member** — crate scaffolding only
   (manifest, UniFFI interface definition, empty `render`/`render_fade_out` stubs), proving the
   toolchain end-to-end before porting any real DSP.
3. **Port `PadSynthState` faithfully and generally** — every `MusicProfile` field (voice count,
   arpeggio on/off, waveform brightness, everything), not a single hardcoded profile. `f64`
   throughout, matching the existing Kotlin's own `Double` usage.
4. **Run both verification passes** (Correctness, GC-immunity) above before this replaces anything
   in production.
5. **On success: delete `PadSynthState`, ship `RustPadSynth` behind the same two-method interface**
   `AmbientMusicEngine` already calls. No permanent dual implementation — matches how every prior
   KMP pilot in this codebase (Tic-Tac-Toe/Air Hockey/Chess) converged onto one canonical
   implementation rather than maintaining two.
6. **`ProceduralSfx` and Desktop's `javax.sound.sampled` sink stay explicitly deferred** — real,
   named next steps once this pilot's own risk is retired, not folded in silently now.
