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

        val minBufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_FRAMES * 2)
        // Generous headroom (several chunks' worth) so a slow GC pause or a
        // scheduler hiccup on the generator thread empties into slack
        // buffer rather than an underrun glitch.
        val bufferBytes = maxOf(minBufferBytes, CHUNK_FRAMES * 2 * 6)

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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
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
        val chunk = ShortArray(CHUNK_FRAMES)
        while (state == State.PLAYING) {
            synth.render(chunk)
            val written = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return // track died under us — nothing more we can do
        }
        if (state == State.STOPPING) {
            val fadeFrames = (SAMPLE_RATE * FADE_OUT_SECONDS).toInt().coerceAtLeast(1)
            val fadeChunk = ShortArray(fadeFrames)
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
 */
private class PadSynthState(private val profile: MusicProfile) {

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

    private var lowpassState = 0.0
    private val lowpassCoeff = 1.0 - exp(-2.0 * PI * profile.lowpassCutoffHz / sampleRateD)

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
    }

    fun render(out: ShortArray) {
        for (i in out.indices) out[i] = toPcm16(nextSample())
    }

    /** Renders a short linear-taper-to-silence tail, reusing whatever the
     *  live chord/breathing/arp state currently sounds like so the fade
     *  reads as a natural decay rather than an abrupt cutoff. */
    fun renderFadeOut(out: ShortArray) {
        val n = out.size
        for (i in out.indices) {
            val taper = 1.0 - (i.toDouble() / n)
            out[i] = toPcm16(nextSample() * taper)
        }
    }

    private fun toPcm16(value: Double): Short =
        (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    private fun nextSample(): Double {
        maybeStartChordChange()

        var chordMix = 0.0
        val voiceGain = 1.0 / profile.voiceCount
        if (crossfading) {
            val progress = 1.0 - (crossfadeRemaining.toDouble() / crossfadeSamples.toDouble())
            // Equal-power crossfade — cos/sin pair keeps perceived loudness
            // constant through the transition instead of dipping in the middle.
            val outGain = cos(progress * PI / 2.0)
            val inGain = sin(progress * PI / 2.0)
            for (v in 0 until profile.voiceCount) {
                chordMix += oscillatorSample(current, v) * outGain * voiceGain
                chordMix += oscillatorSample(incoming, v) * inGain * voiceGain
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
                chordMix += oscillatorSample(current, v) * voiceGain
            }
            samplesUntilChordChange--
        }

        // Slow amplitude "breathing" LFO for an organic, non-static feel.
        breathingPhase += profile.breathingRateHz / sampleRateD
        if (breathingPhase > 1.0) breathingPhase -= floor(breathingPhase)
        val breathing = 1.0 - profile.breathingDepth + profile.breathingDepth * sin(2.0 * PI * breathingPhase)

        var mix = chordMix * breathing
        if (profile.arpeggioEnabled) mix += nextArpeggioSample()

        // One-pole low-pass "warmth" smoothing on the combined signal.
        lowpassState += lowpassCoeff * (mix - lowpassState)

        if (envelopeGain < 1.0) envelopeGain = (envelopeGain + envelopeStep).coerceAtMost(1.0)

        return lowpassState * profile.baseVolume * envelopeGain
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
