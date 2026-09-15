package com.gamesuite.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Real-time procedural ambient-music engine — no bundled loop files, no
 * licensing story, zero APK cost. Every sample is synthesized on the fly on
 * a dedicated background thread via [AudioTrack] in `MODE_STREAM`, which is
 * the whole point of building this instead of shipping a looped clip:
 * there's no seam to hide because nothing ever repeats identically, and a
 * player can sit on one screen indefinitely without the pad ever looping.
 *
 * Mirrors [com.gamesuite.haptics.performHaptic]/`rememberHaptics`'s shape on
 * purpose: a plain data-driven config ([MusicProfile]), a named registry
 * ([MusicProfiles]) instead of one enum value per game, and a single
 * Compose-friendly entry point ([rememberAmbientMusic]) that owns the
 * platform object's lifecycle so call sites never touch [AudioTrack]
 * directly.
 *
 * STEREO: genuinely spatial, not "mono duplicated to two channels" (which
 * would sound bit-for-bit identical to the old mono output, just double the
 * data). Each of a chord's own [MusicProfile.voiceCount] voices is placed at
 * its own fixed position across a moderate stereo field via [voicePan] — see
 * that function's own KDoc — so a 3-voice chord genuinely has its root/3rd/
 * 5th sitting at different points in the stereo image, the same way a real
 * multi-instrument arrangement would, rather than one mono blob. The
 * plucked arpeggio layer stays dead center (a single melodic line, not a
 * chord, has no "spread" to give it). [equalPowerPanGains]/[voicePan] are
 * the two testable pure-math pieces of this (see [AmbientMusicEngineTest]);
 * the surrounding DSP loop itself stays untested like the rest of this
 * real-time audio engine always has been (no Robolectric/AudioTrack-capable
 * test harness in this project).
 */

private const val SAMPLE_RATE = 44100

/** ~50ms per chunk — small enough to react quickly to a stop() request while
 *  still being generous for a background thread doing floating-point DSP. */
private const val CHUNK_FRAMES = SAMPLE_RATE / 20

/** Rendered once at [AmbientMusicEngine.stop] time so playback always ends
 *  in silence rather than a hard cut, on top of every other click-avoidance
 *  measure below. */
private const val FADE_OUT_SECONDS = 0.25

/** Ramped in once at startup so the very first sample isn't a jump straight
 *  from silence to full amplitude. */
private const val STARTUP_FADE_SECONDS = 0.35

/** How far a pad's own voices spread across the stereo field, as a fraction of full hard-left/
 *  hard-right (1.0). Deliberately moderate, not 1.0 — a fully hard-panned pad can read as
 *  several disconnected mono blobs rather than one cohesive chord with real width. Shared
 *  default for [voicePan]; [ProceduralSfx]'s one-shot pans are a separate, per-call concern and
 *  don't use this constant. */
internal const val STEREO_SPREAD = 0.6

/** Left/right gain pair from an equal-power ("constant power") pan law — keeps perceived
 *  loudness constant as a sound moves from hard left ([pan] = -1) through center (0) to hard
 *  right (+1), unlike a naive linear pan which audibly dips in the middle (`left^2 + right^2`
 *  is exactly 1.0 for every pan value — see [AmbientMusicEngineTest]). Shared by this file's own
 *  per-voice spatial placement ([voicePan]) and [ProceduralSfx]'s one-shot directional playback
 *  — both are in this same `com.gamesuite.audio` package, so no cross-package API was needed for
 *  this to be reused rather than written twice.
 */
internal data class PanGains(val left: Double, val right: Double)

internal fun equalPowerPanGains(pan: Float): PanGains {
    val clamped = pan.coerceIn(-1f, 1f).toDouble()
    val angle = (clamped + 1.0) * (PI / 4.0)
    return PanGains(cos(angle), sin(angle))
}

/**
 * Where voice [voiceIndex] of [voiceCount] total voices sits across the stereo field, as a pan
 * value in `-spread..spread` (see [STEREO_SPREAD]) — evenly spaced, symmetric around center, so
 * voice 0 is always the leftmost and the last voice always the rightmost. A single voice
 * ([voiceCount] <= 1 — no shipped [MusicProfile] actually uses this, but every profile's own
 * chord-tone math stays well-defined for it) stays dead center rather than dividing by zero.
 */
internal fun voicePan(voiceIndex: Int, voiceCount: Int, spread: Double = STEREO_SPREAD): Double {
    if (voiceCount <= 1) return 0.0
    val fraction = voiceIndex.toDouble() / (voiceCount - 1) // 0..1 across the voices
    return (fraction * 2.0 - 1.0) * spread // -spread..+spread
}

/**
 * A fully declarative description of one game's ambient pad — no game-
 * specific code lives here, just numbers a synth reads. Frequencies are
 * expressed as semitone offsets from [rootNoteHz] via [scaleIntervals] so
 * the same engine code can produce a minor drone, a pentatonic wood-toned
 * bed, or a bright arcade pulse purely by swapping profiles.
 */
data class MusicProfile(
    /** Fundamental the whole progression is built relative to. */
    val rootNoteHz: Float,
    /** Semitone offsets from the root describing the scale in use, e.g.
     *  natural minor = [0,2,3,5,7,8,10]. Chord tones are picked by walking
     *  this list in "thirds" (every other entry), wrapping octaves as
     *  needed — see [chordFrequenciesFor]. */
    val scaleIntervals: List<Int>,
    /** Scale-degree (0-indexed) of each chord root in the progression, in
     *  play order; the engine loops this list indefinitely. */
    val chordProgressionDegrees: List<Int>,
    /** How long each chord holds before the next crossfade begins. */
    val chordDurationSeconds: Float,
    /** Concurrent pad oscillators (2-4 per the brief) — root/3rd/5th/... */
    val voiceCount: Int,
    /** Overall output level for this profile, pre master-volume, in 0f..1f. */
    val baseVolume: Float,
    /** Length of the equal-power crossfade between chords. Longer reads
     *  calmer; defaults to a fraction of the chord length so slow profiles
     *  automatically get slower transitions too, clamped so nothing is ever
     *  fast enough to click and nothing overstays the chord it's leaving. */
    val crossfadeSeconds: Float = (chordDurationSeconds * 0.15f).coerceIn(0.6f, 4f),
    /** Frequency of the slow amplitude "breathing" LFO. */
    val breathingRateHz: Float = 0.08f,
    /** How deep the breathing LFO dips the volume, 0f..1f of [baseVolume]. */
    val breathingDepth: Float = 0.16f,
    /** Sine/triangle blend for the pad oscillators — 0 is a pure warm sine,
     *  higher values fold in more triangle-wave harmonics for a brighter
     *  timbre (Air Hockey, UNO) vs. a softer one (Chess, the puzzle bed). */
    val waveformBrightness: Float = 0.2f,
    /** One-pole low-pass cutoff applied to the finished mix for warmth —
     *  the "nice to have" smoothing the build brief called out. Lower is
     *  warmer/duller, higher is airier. */
    val lowpassCutoffHz: Float = 3500f,
    /** When true, a light plucked-note layer walks the current chord's
     *  tones under the pad — the "wood-toned"/"playful" texture for
     *  Mancala/Dominoes/UNO/Air Hockey. Off for the sparse/contemplative
     *  profiles, which stay a pure drone. */
    val arpeggioEnabled: Boolean = false,
    /** Notes per second for the arpeggio layer, when enabled. */
    val arpeggioRateHz: Float = 2f,
    /** Arpeggio layer's own level, independent of [baseVolume]. */
    val arpeggioVolume: Float = 0.14f
)

/**
 * One named [MusicProfile] per game, per the "premium 2026 vision" pitch's
 * musical brief. Deliberately plain `val`s rather than a lookup keyed by a
 * game enum — the later wiring pass reads e.g. `MusicProfiles.CHESS`
 * directly from each screen, same spirit as `LocalCardScale`/`Local*`
 * consumers reading a fixed named thing rather than a map.
 */
object MusicProfiles {
    private val NATURAL_MINOR = listOf(0, 2, 3, 5, 7, 8, 10)
    private val MAJOR = listOf(0, 2, 4, 5, 7, 9, 11)
    private val MAJOR_PENTATONIC = listOf(0, 2, 4, 7, 9)

    /** Contemplative, sparse — long minor-leaning chords, only 3 voices. */
    val CHESS = MusicProfile(
        rootNoteHz = 110.00f, // A2
        scaleIntervals = NATURAL_MINOR,
        chordProgressionDegrees = listOf(0, 5, 3, 4), // i - VI - iv - v
        chordDurationSeconds = 20f,
        voiceCount = 3,
        baseVolume = 0.13f,
        waveformBrightness = 0.12f,
        lowpassCutoffHz = 3000f
    )

    /** Same spacious/minor mood as [CHESS], a touch sparser (2 voices) and
     *  a different root so the two don't sound identical back-to-back. */
    val CHECKERS = MusicProfile(
        rootNoteHz = 98.00f, // G2
        scaleIntervals = NATURAL_MINOR,
        chordProgressionDegrees = listOf(0, 3, 4, 0), // i - iv - v - i
        chordDurationSeconds = 18f,
        voiceCount = 2,
        baseVolume = 0.13f,
        waveformBrightness = 0.12f,
        lowpassCutoffHz = 3000f
    )

    /** Warm, wood-toned, major-pentatonic — a gentle slow arpeggio under
     *  the pad for a tactile feel. */
    val MANCALA = MusicProfile(
        rootNoteHz = 130.81f, // C3
        scaleIntervals = MAJOR_PENTATONIC,
        chordProgressionDegrees = listOf(0, 2, 3, 0),
        chordDurationSeconds = 14f,
        voiceCount = 3,
        baseVolume = 0.15f,
        waveformBrightness = 0.22f,
        lowpassCutoffHz = 3200f,
        arpeggioEnabled = true,
        arpeggioRateHz = 1.1f,
        arpeggioVolume = 0.12f
    )

    /** Same wood-toned pentatonic family as [MANCALA], different root. */
    val DOMINOES = MusicProfile(
        rootNoteHz = 146.83f, // D3
        scaleIntervals = MAJOR_PENTATONIC,
        chordProgressionDegrees = listOf(0, 3, 2, 0),
        chordDurationSeconds = 15f,
        voiceCount = 3,
        baseVolume = 0.15f,
        waveformBrightness = 0.22f,
        lowpassCutoffHz = 3200f,
        arpeggioEnabled = true,
        arpeggioRateHz = 1f,
        arpeggioVolume = 0.12f
    )

    /** A light, quick-turnaround pentatonic pad with a subtle plucked
     *  arpeggio — Dots and Boxes plays in fast alternating taps punctuated
     *  by chain-reaction bursts, so this sits between TIC_TAC_TOE's sparse
     *  stillness and UNO's playful energy rather than copying either. */
    val DOTS_AND_BOXES = MusicProfile(
        rootNoteHz = 174.61f, // F3
        scaleIntervals = MAJOR_PENTATONIC,
        chordProgressionDegrees = listOf(0, 2, 3, 2),
        chordDurationSeconds = 12f,
        voiceCount = 3,
        baseVolume = 0.13f,
        waveformBrightness = 0.2f,
        lowpassCutoffHz = 3800f,
        arpeggioEnabled = true,
        arpeggioRateHz = 1.4f,
        arpeggioVolume = 0.11f
    )

    /** Confident, tactile pentatonic pad with a moderate plucked arpeggio —
     *  Connect Four's rhythm is a steady back-and-forth of single,
     *  deliberate drops (unlike Dots and Boxes' fast alternating taps), so
     *  this sits between that game's quicker energy and Checkers/Chess's
     *  slower contemplation: same wood-toned pentatonic family as
     *  Dominoes/Mancala/Dots and Boxes, its own root, a steadier (not
     *  faster) arpeggio. */
    val CONNECT_FOUR = MusicProfile(
        rootNoteHz = 184.99f, // F#3
        scaleIntervals = MAJOR_PENTATONIC,
        chordProgressionDegrees = listOf(0, 2, 4, 3),
        chordDurationSeconds = 13f,
        voiceCount = 3,
        baseVolume = 0.14f,
        waveformBrightness = 0.21f,
        lowpassCutoffHz = 3600f,
        arpeggioEnabled = true,
        arpeggioRateHz = 1.2f,
        arpeggioVolume = 0.12f
    )

    /** Same wood-toned pentatonic family as [CONNECT_FOUR]/[DOMINOES]/[MANCALA]/[DOTS_AND_BOXES],
     *  its own root -- Reversi's rhythm is bursts of flanking captures (several discs flip at once)
     *  punctuating quieter scanning turns, so this sits closer to [CONNECT_FOUR]'s steady,
     *  deliberate energy than [DOTS_AND_BOXES]' faster alternating-tap pace, with a touch more
     *  arpeggio brightness to underline those flip moments. */
    val REVERSI = MusicProfile(
        rootNoteHz = 155.56f, // D#3/Eb3
        scaleIntervals = MAJOR_PENTATONIC,
        chordProgressionDegrees = listOf(0, 2, 3, 4),
        chordDurationSeconds = 13f,
        voiceCount = 3,
        baseVolume = 0.14f,
        waveformBrightness = 0.23f,
        lowpassCutoffHz = 3700f,
        arpeggioEnabled = true,
        arpeggioRateHz = 1.3f,
        arpeggioVolume = 0.13f
    )

    /** Gentle, airy major pad — the shared "focus" mood for every quiet
     *  solo puzzle game (Solitaire, Word Tiles, Word Search, Crossword,
     *  Hangman). One profile really is enough for all five, per the brief. */
    val PUZZLE_FOCUS = MusicProfile(
        rootNoteHz = 220.00f, // A3
        scaleIntervals = MAJOR,
        chordProgressionDegrees = listOf(0, 3, 4, 0), // I - IV - V - I
        chordDurationSeconds = 14f,
        voiceCount = 3,
        baseVolume = 0.12f,
        waveformBrightness = 0.18f,
        lowpassCutoffHz = 4000f,
        breathingDepth = 0.12f
    )
    val SOLITAIRE = PUZZLE_FOCUS
    val WORD_TILES = PUZZLE_FOCUS
    val WORD_SEARCH = PUZZLE_FOCUS
    val CROSSWORD = PUZZLE_FOCUS
    val HANGMAN = PUZZLE_FOCUS
    val MINESWEEPER = PUZZLE_FOCUS
    val SUDOKU = PUZZLE_FOCUS
    val LIGHTS_OUT = PUZZLE_FOCUS
    val COLOR_FLOOD = PUZZLE_FOCUS

    /** A touch more playful and a bit faster than everything else that
     *  isn't Air Hockey — light plucked-arpeggio texture matching UNO's
     *  own energetic-but-fun identity elsewhere in the app. */
    val UNO = MusicProfile(
        rootNoteHz = 196.00f, // G3
        scaleIntervals = MAJOR_PENTATONIC,
        chordProgressionDegrees = listOf(0, 2, 4, 2),
        chordDurationSeconds = 9f,
        voiceCount = 3,
        baseVolume = 0.15f,
        waveformBrightness = 0.3f,
        lowpassCutoffHz = 4500f,
        breathingRateHz = 0.12f,
        arpeggioEnabled = true,
        arpeggioRateHz = 2.5f,
        arpeggioVolume = 0.14f
    )

    /** The sparsest, quietest treatment — very short-session games, least
     *  intrusive: 2 voices, slow chords, no arpeggio. */
    val TIC_TAC_TOE = MusicProfile(
        rootNoteHz = 261.63f, // C4
        scaleIntervals = MAJOR_PENTATONIC,
        chordProgressionDegrees = listOf(0, 3),
        chordDurationSeconds = 24f,
        voiceCount = 2,
        baseVolume = 0.08f,
        waveformBrightness = 0.15f,
        lowpassCutoffHz = 3500f,
        breathingDepth = 0.1f
    )
    val SLIDING_PUZZLE = TIC_TAC_TOE

    /** The deliberate exception: a fast arcade game, not a calm strategy
     *  one. A brighter, more rhythmic synth-pulse bed at a much shorter
     *  chord length instead of a slow drone — but still restrained (lowest
     *  base volume of any profile) since it has to coexist with real-time
     *  paddle/goal SFX. */
    val AIR_HOCKEY = MusicProfile(
        rootNoteHz = 164.81f, // E3
        scaleIntervals = MAJOR,
        chordProgressionDegrees = listOf(0, 4), // I - V pulse
        chordDurationSeconds = 4f,
        voiceCount = 2,
        baseVolume = 0.08f,
        waveformBrightness = 0.4f,
        lowpassCutoffHz = 5500f,
        breathingRateHz = 0.22f,
        breathingDepth = 0.22f,
        arpeggioEnabled = true,
        arpeggioRateHz = 4f,
        arpeggioVolume = 0.16f
    )

    /** Aliased to AIR_HOCKEY rather than PUZZLE_FOCUS, deliberately breaking the
     *  otherwise-mechanical "solo game = PUZZLE_FOCUS" pattern every solo game in the new-games
     *  batch (Minesweeper through Edge Match) has followed so far: Breakout is real-time
     *  paddle/ball arcade action, not a calm turn-based puzzle, and AIR_HOCKEY's own KDoc above
     *  ("a fast arcade game... restrained... since it has to coexist with real-time paddle/goal
     *  SFX") describes Breakout's exact identity almost word for word. Genre/energy match wins
     *  over player-count here, not the other way around. */
    val BREAKOUT = AIR_HOCKEY

    /** A bespoke profile, not an alias of AIR_HOCKEY or PUZZLE_FOCUS — Tower Defence's own
     *  identity is neither of theirs. It isn't calm turn-based reasoning (PUZZLE_FOCUS) since
     *  waves are real-time and lives are genuinely at stake, but it also isn't AIR_HOCKEY's
     *  fast reflex-arcade energy (its own pace is set by wave timers and economy decisions, not
     *  split-second reaction). Minor-leaning like CHESS/CHECKERS for real stakes/tension, but a
     *  faster chord length and a light rhythmic arpeggio (the "wave is coming" pulse) sit it
     *  between those two families rather than copying either. */
    val TOWER_DEFENCE = MusicProfile(
        rootNoteHz = 138.59f, // C#3
        scaleIntervals = NATURAL_MINOR,
        chordProgressionDegrees = listOf(0, 3, 4, 3), // i - iv - v - iv, a rising-tension loop
        chordDurationSeconds = 8f,
        voiceCount = 3,
        baseVolume = 0.12f,
        waveformBrightness = 0.24f,
        lowpassCutoffHz = 3800f,
        breathingRateHz = 0.15f,
        breathingDepth = 0.18f,
        arpeggioEnabled = true,
        arpeggioRateHz = 1.6f,
        arpeggioVolume = 0.13f
    )
}

/**
 * The live [AudioTrack]-backed player for one [MusicProfile]. Not
 * Compose-aware itself — [rememberAmbientMusic] below is the entry point
 * every screen should actually use; this class exists so that entry point
 * has something simple to new-up/tear-down per composition.
 *
 * Lifecycle: [start] spins up the `AudioTrack` and a single daemon
 * generator thread; [stop] asks that thread to render a short silence-bound
 * fade-out tail, blocks (with a bounded timeout) until it does, and only
 * then releases the `AudioTrack` — so repeated start/stop across screen
 * navigation (which happens constantly in normal use) never leaks a thread
 * or an `AudioTrack`, and never cuts audio off with an audible pop.
 */
class AmbientMusicEngine(private val profile: MusicProfile) {

    private enum class State { STOPPED, PLAYING, STOPPING }

    @Volatile private var state = State.STOPPED
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null

    fun start() {
        if (state != State.STOPPED) return

        // Stereo now (2 shorts/frame, interleaved L/R) -- see the class KDoc's STEREO section.
        // CHUNK_FRAMES * 4 = CHUNK_FRAMES frames * 2 channels * 2 bytes/short.
        val minBufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_FRAMES * 4)
        // Generous headroom (several chunks' worth) so a slow GC pause or a
        // scheduler hiccup on the generator thread empties into slack
        // buffer rather than an underrun glitch.
        val bufferBytes = maxOf(minBufferBytes, CHUNK_FRAMES * 4 * 6)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        state = State.PLAYING
        track.play()
        thread = thread(name = "AmbientMusicEngine", isDaemon = true) {
            runGeneratorLoop(track)
        }
    }

    fun stop() {
        if (state == State.STOPPED) return
        // Let the generator thread render one last fade-out chunk before it
        // exits, instead of yanking the track silent mid-waveform.
        state = State.STOPPING
        thread?.join(1500)
        thread = null
        audioTrack?.let { t ->
            try { t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
        audioTrack = null
        state = State.STOPPED
    }

    private fun runGeneratorLoop(track: AudioTrack) {
        val synth = PadSynthState(profile)
        // * 2: interleaved stereo shorts (L,R per frame), not CHUNK_FRAMES frames of mono.
        val chunk = ShortArray(CHUNK_FRAMES * 2)
        while (state == State.PLAYING) {
            synth.render(chunk)
            val written = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return // track died under us — nothing more we can do
        }
        if (state == State.STOPPING) {
            val fadeFrames = (SAMPLE_RATE * FADE_OUT_SECONDS).toInt().coerceAtLeast(1)
            val fadeChunk = ShortArray(fadeFrames * 2)
            synth.renderFadeOut(fadeChunk)
            track.write(fadeChunk, 0, fadeChunk.size, AudioTrack.WRITE_BLOCKING)
        }
    }
}

/**
 * All the actual DSP — pure, single-threaded state owned exclusively by
 * [AmbientMusicEngine]'s generator thread. Two oscillator "banks" (current
 * chord / incoming chord) let chord changes crossfade with an equal-power
 * curve instead of yanking frequencies, which is what keeps every chord
 * change click-free without needing to time anything to a zero-crossing.
 *
 * `internal`, not `private` (file-private, unlike every other declaration in this file) --
 * `docs/RUST_AUDIO_CORE_PLAN.md`'s Step 4 correctness test
 * (`app/src/test/java/com/gamesuite/audio/RustPadSynthCorrectnessTest.kt`) constructs this
 * directly, from a different file in the same package, to diff it sample-by-sample against
 * [com.gamesuite.audio.RustPadSynth]. Kotlin's test source set is compiled as a "friend" of the
 * main source set (standard Kotlin Gradle Plugin behavior for unit tests), so `internal` here is
 * visible to that test without exposing this class outside the app module entirely.
 */
internal class PadSynthState(private val profile: MusicProfile) {

    private class VoiceBank(voiceCount: Int) {
        val phase = DoubleArray(voiceCount)
        val freqHz = DoubleArray(voiceCount)
    }

    private val sampleRateD = SAMPLE_RATE.toDouble()
    private val current = VoiceBank(profile.voiceCount)
    private val incoming = VoiceBank(profile.voiceCount)

    private var chordIndex = 0
    private val chordHoldSamples = (profile.chordDurationSeconds * SAMPLE_RATE).toLong().coerceAtLeast(1)
    private var samplesUntilChordChange = chordHoldSamples
    private val crossfadeSamples = (profile.crossfadeSeconds * SAMPLE_RATE).toLong().coerceAtLeast(1)
    private var crossfadeRemaining = 0L
    private var crossfading = false

    private var breathingPhase = 0.0
    private var envelopeGain = 0.0 // startup fade-in, 0..1
    private val envelopeStep = 1.0 / (SAMPLE_RATE * STARTUP_FADE_SECONDS)

    // Independent per-channel lowpass state -- each channel now carries genuinely different
    // content (per-voice panning below), so sharing one filter's memory between them would
    // incorrectly bleed one channel's signal into the other through the filter itself.
    private var lowpassStateLeft = 0.0
    private var lowpassStateRight = 0.0
    private val lowpassCoeff = 1.0 - exp(-2.0 * PI * profile.lowpassCutoffHz / sampleRateD)

    // Each voice's own fixed stereo position (see voicePan's own KDoc) -- computed once here,
    // not per-sample, since voice count/spread never changes for the life of this synth
    // instance. Read every sample in computeNextSample(); never allocates in the hot path.
    private val voiceLeftGain = DoubleArray(profile.voiceCount)
    private val voiceRightGain = DoubleArray(profile.voiceCount)

    private var lastLeft = 0.0
    private var lastRight = 0.0

    // Arpeggio layer (only used when profile.arpeggioEnabled).
    private val arpSamplesPerNote = if (profile.arpeggioEnabled) {
        (sampleRateD / profile.arpeggioRateHz).toLong().coerceAtLeast(1)
    } else 1L
    private var arpSamplesRemaining = 0L
    private var arpPhase = 0.0
    private var arpFreqHz = 0.0
    private var arpEnvelope = 0.0
    private var arpNoteCount = 0
    private val arpAttackSamples = (SAMPLE_RATE * 0.006).toLong().coerceAtLeast(1)
    private val arpDecayFactor = exp(kotlin.math.ln(0.02) / arpSamplesPerNote.toDouble())

    init {
        writeChordFrequencies(current, chordIndex)
        for (v in 0 until profile.voiceCount) {
            val gains = equalPowerPanGains(voicePan(v, profile.voiceCount).toFloat())
            voiceLeftGain[v] = gains.left
            voiceRightGain[v] = gains.right
        }
    }

    /** [out] is interleaved stereo (L,R per frame) — `out.size / 2` frames. */
    fun render(out: ShortArray) {
        var i = 0
        while (i < out.size) {
            computeNextSample()
            out[i] = toPcm16(lastLeft)
            out[i + 1] = toPcm16(lastRight)
            i += 2
        }
    }

    /** Renders a short linear-taper-to-silence tail, reusing whatever the
     *  live chord/breathing/arp state currently sounds like so the fade
     *  reads as a natural decay rather than an abrupt cutoff. [out] is
     *  interleaved stereo, same as [render]. */
    fun renderFadeOut(out: ShortArray) {
        val frameCount = out.size / 2
        for (frame in 0 until frameCount) {
            val taper = 1.0 - (frame.toDouble() / frameCount)
            computeNextSample()
            out[frame * 2] = toPcm16(lastLeft * taper)
            out[frame * 2 + 1] = toPcm16(lastRight * taper)
        }
    }

    /** Test-only seam (`docs/RUST_AUDIO_CORE_PLAN.md` Step 4): [render] quantizes every sample to
     *  a 16-bit `Short` on its way out, which would hide any per-sample drift between this class
     *  and [com.gamesuite.audio.RustPadSynth] smaller than one quantization step (~1/32767). This
     *  exposes the same per-frame [computeNextSample] output [render] itself writes, before that
     *  quantization, as a freshly-allocated `DoubleArray` (allocation is fine here -- unlike
     *  [render]/[renderFadeOut], nothing calls this from the real-time generator thread; see this
     *  class's own KDoc on why that thread avoids allocating at all). Interleaved stereo (L,R per
     *  frame), `frameCount * 2` doubles -- same layout as [render]'s `ShortArray`, just
     *  unquantized and returned rather than written into a caller-owned buffer. */
    internal fun renderF64(frameCount: Int): DoubleArray {
        val out = DoubleArray(frameCount * 2)
        for (frame in 0 until frameCount) {
            computeNextSample()
            out[frame * 2] = lastLeft
            out[frame * 2 + 1] = lastRight
        }
        return out
    }

    private fun toPcm16(value: Double): Short =
        (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    /** Advances every DSP stage by exactly one audio frame and leaves the result in
     *  [lastLeft]/[lastRight] — a pair of instance fields rather than a return value, so this
     *  runs allocation-free at 44.1kHz on the generator thread (no boxed `Pair<Double,Double>`
     *  or data-class instance created per sample). */
    private fun computeNextSample() {
        maybeStartChordChange()

        var leftMix = 0.0
        var rightMix = 0.0
        val voiceGain = 1.0 / profile.voiceCount
        if (crossfading) {
            val progress = 1.0 - (crossfadeRemaining.toDouble() / crossfadeSamples.toDouble())
            // Equal-power crossfade — cos/sin pair keeps perceived loudness
            // constant through the transition instead of dipping in the middle.
            val outGain = cos(progress * PI / 2.0)
            val inGain = sin(progress * PI / 2.0)
            for (v in 0 until profile.voiceCount) {
                // Both banks' contributions for voice v share voice v's own fixed stereo
                // position (a chord tone doesn't move position mid-crossfade, only its pitch/
                // gain blend does), so the pan gain is applied once to their combined value —
                // algebraically identical to applying it to each term separately and summing.
                val combined = oscillatorSample(current, v) * outGain * voiceGain +
                    oscillatorSample(incoming, v) * inGain * voiceGain
                leftMix += combined * voiceLeftGain[v]
                rightMix += combined * voiceRightGain[v]
            }
            crossfadeRemaining--
            if (crossfadeRemaining <= 0) {
                System.arraycopy(incoming.freqHz, 0, current.freqHz, 0, profile.voiceCount)
                System.arraycopy(incoming.phase, 0, current.phase, 0, profile.voiceCount)
                crossfading = false
                samplesUntilChordChange = chordHoldSamples
            }
        } else {
            for (v in 0 until profile.voiceCount) {
                val sample = oscillatorSample(current, v) * voiceGain
                leftMix += sample * voiceLeftGain[v]
                rightMix += sample * voiceRightGain[v]
            }
            samplesUntilChordChange--
        }

        // Slow amplitude "breathing" LFO for an organic, non-static feel -- one shared
        // phase/envelope for both channels, so the pad breathes as one cohesive whole rather
        // than two decorrelated channels swelling out of sync.
        breathingPhase += profile.breathingRateHz / sampleRateD
        if (breathingPhase > 1.0) breathingPhase -= floor(breathingPhase)
        val breathing = 1.0 - profile.breathingDepth + profile.breathingDepth * sin(2.0 * PI * breathingPhase)
        leftMix *= breathing
        rightMix *= breathing

        // The plucked arpeggio layer stays dead center -- see the class KDoc's STEREO section.
        if (profile.arpeggioEnabled) {
            val arp = nextArpeggioSample()
            leftMix += arp
            rightMix += arp
        }

        // One-pole low-pass "warmth" smoothing, independently per channel (see the field's own
        // KDoc for why this can't share one filter state across channels anymore).
        lowpassStateLeft += lowpassCoeff * (leftMix - lowpassStateLeft)
        lowpassStateRight += lowpassCoeff * (rightMix - lowpassStateRight)

        if (envelopeGain < 1.0) envelopeGain = (envelopeGain + envelopeStep).coerceAtMost(1.0)

        lastLeft = lowpassStateLeft * profile.baseVolume * envelopeGain
        lastRight = lowpassStateRight * profile.baseVolume * envelopeGain
    }

    private fun maybeStartChordChange() {
        if (samplesUntilChordChange > 0 || crossfading) return
        chordIndex++
        writeChordFrequencies(incoming, chordIndex)
        for (v in incoming.phase.indices) incoming.phase[v] = 0.0
        crossfading = true
        crossfadeRemaining = crossfadeSamples
    }

    private fun oscillatorSample(bank: VoiceBank, voice: Int): Double {
        val phase = bank.phase[voice]
        val angle = 2.0 * PI * phase
        val sine = sin(angle)
        val triangle = 2.0 * abs(2.0 * (phase - floor(phase + 0.5))) - 1.0
        val sample = sine * (1.0 - profile.waveformBrightness) + triangle * profile.waveformBrightness

        var next = phase + bank.freqHz[voice] / sampleRateD
        if (next > 1.0) next -= floor(next)
        bank.phase[voice] = next

        return sample
    }

    /** A gentle plucked-note layer that walks the currently-sounding
     *  chord's tones one octave up — quick attack, exponential decay,
     *  purely additive under the pad. Reads [current]'s frequencies (not
     *  [incoming]'s), so during a crossfade it simply keeps outlining the
     *  chord that's fading out until the swap completes, then picks up the
     *  new one on its next retrigger — a small, harmless simplification. */
    private fun nextArpeggioSample(): Double {
        if (arpSamplesRemaining <= 0) {
            val toneIndex = arpeggioToneOrder[arpNoteCount % arpeggioToneOrder.size] % current.freqHz.size
            arpFreqHz = current.freqHz[toneIndex] * 2.0
            arpNoteCount++
            arpPhase = 0.0
            arpEnvelope = 0.0001
            arpSamplesRemaining = arpSamplesPerNote
        }

        val angle = 2.0 * PI * arpPhase
        val raw = sin(angle)
        var next = arpPhase + arpFreqHz / sampleRateD
        if (next > 1.0) next -= floor(next)
        arpPhase = next

        val samplesIntoNote = arpSamplesPerNote - arpSamplesRemaining
        arpEnvelope = if (samplesIntoNote < arpAttackSamples) {
            arpEnvelope + (1.0 - arpEnvelope) * (1.0 / arpAttackSamples)
        } else {
            arpEnvelope * arpDecayFactor
        }
        arpSamplesRemaining--

        return raw * arpEnvelope * profile.arpeggioVolume
    }

    private fun writeChordFrequencies(bank: VoiceBank, degreeIndex: Int) {
        val degree = profile.chordProgressionDegrees[degreeIndex % profile.chordProgressionDegrees.size]
        val scale = profile.scaleIntervals
        val size = scale.size
        for (v in 0 until profile.voiceCount) {
            // Stacked-thirds chord tones: root, then every other scale step
            // (root/3rd/5th/7th/9th in scale-degree terms). Works for any
            // scale length, pentatonic included, since it's expressed
            // purely in scale-degree steps rather than fixed semitone math.
            val scaleStep = degree + v * 2
            val octave = Math.floorDiv(scaleStep, size)
            val idx = Math.floorMod(scaleStep, size)
            val semitone = scale[idx] + 12 * octave
            bank.freqHz[v] = profile.rootNoteHz * Math.pow(2.0, semitone / 12.0)
        }
    }

    companion object {
        private val arpeggioToneOrder = intArrayOf(0, 1, 2, 1)
    }
}

/**
 * The Composable entry point every game screen should use — mirrors
 * `rememberHaptics`'s shape. Starts playback in a [DisposableEffect] keyed
 * on both [profile] and [enabled] so it restarts cleanly on a profile
 * change and stops cleanly the instant `enabled` goes false, and releases
 * the underlying [AudioTrack]/thread on dispose (screen navigation away).
 *
 * `enabled` is resolved by the caller from both the ambient-music setting
 * and the master sound setting (`musicEnabled && soundEnabled`) — this
 * function deliberately doesn't read either `CompositionLocal` itself, kept
 * simple and testable exactly like `rememberHaptics` takes no such
 * responsibility for haptics.
 *
 * Usage: `rememberAmbientMusic(MusicProfiles.CHESS, enabled = musicEnabled && soundEnabled)`
 */
@Composable
fun rememberAmbientMusic(profile: MusicProfile, enabled: Boolean) {
    DisposableEffect(profile, enabled) {
        val engine = if (enabled) AmbientMusicEngine(profile).also { it.start() } else null
        onDispose { engine?.stop() }
    }
}
