package com.gamesuite.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gamesuite.games.cards.CardSounds
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * Short-form procedural one-shot SFX — the [com.gamesuite.audio.AmbientMusicEngine]'s
 * sibling for punctual game events rather than a continuous bed. Each
 * [SfxKind] renders fully into a short in-memory 16-bit PCM buffer (no
 * streaming needed at this length) and plays it through a one-shot
 * `MODE_STATIC` [AudioTrack] — much simpler than the music engine's
 * continuous generator thread.
 *
 * Mirrors [com.gamesuite.haptics.HapticSignal]/`performHaptic`'s shape: a
 * small enum vocabulary plus a plain `play(context, kind)` function.
 *
 * STEREO: every [SfxKind] is still synthesized as a single mono signal (a one-shot click/chime/
 * buzz has no internal voices to spatially spread the way [AmbientMusicEngine]'s own pad chords
 * do) — [playProceduralSfx]'s optional [pan] parameter places that finished mono signal at a
 * point across the stereo field via the same [equalPowerPanGains] law `AmbientMusicEngine.kt`
 * already established, defaulting to dead center (0f) so every one of this app's existing call
 * sites keeps compiling and sounding exactly as before. [rememberPannedProceduralSfx] is a
 * separate opt-in entry point for the few screens that actually have positional data worth
 * panning toward (e.g. where on a board an event happened) — [rememberProceduralSfx]'s own
 * returned lambda type is untouched rather than widened, so no existing call site needed to
 * change.
 */

private const val SFX_SAMPLE_RATE = 44100

/**
 * A small, deliberately generic vocabulary of distinct one-shot sounds —
 * see [com.gamesuite.haptics.HapticSignal] for the same design choice made
 * for haptics. Every game maps its own moments onto these.
 */
enum class SfxKind {
    /** A very short, high, quiet click — a selection or light acknowledgment. */
    LIGHT_TICK,
    /** A bright, short ascending arpeggio — a correct guess, a solve, a win. */
    SUCCESS_CHIME,
    /** A short descending/dissonant tone — a wrong guess or an illegal move. */
    INVALID_BUZZ,
    /** A warm low tone plus a very short noise transient — a satisfying
     *  physical placement (a piece set down, a tile landing). */
    SOLID_THUNK,
    /** A short filtered noise sweep — a card or tile in flight. */
    WHOOSH
}

/**
 * Renders and plays [kind] immediately. Mirrors
 * [com.gamesuite.haptics.performHaptic]'s shape, including reusing
 * [CardSounds.soundEnabled] as its gate — the exact same global mute flag
 * every other SFX call site in the app already respects (see that
 * property's own KDoc), so this needs no CompositionLocal or new plumbing
 * of its own to obey the existing master sound toggle.
 *
 * [pan] places the finished mono signal across the stereo field via the
 * shared [equalPowerPanGains] law, from -1 (hard left) through 0 (center,
 * the default — identical output on both channels) to +1 (hard right).
 */
fun playProceduralSfx(context: Context, kind: SfxKind, pan: Float = 0f) {
    if (!CardSounds.soundEnabled) return
    playPcmOneShot(renderPcm(kind), pan)
}

/**
 * The Composable convenience wrapper — mirrors `rememberHaptics`'s shape,
 * but deliberately stays simpler: there's no `LocalMusicEnabled`-style
 * CompositionLocal to read here (the gate already lives in
 * [CardSounds.soundEnabled], read inside [playProceduralSfx] itself), so
 * this just hands back a stable `(SfxKind) -> Unit` bound to the current
 * context.
 *
 * Usage: `val playSfx = rememberProceduralSfx(); ...; playSfx(SfxKind.SUCCESS_CHIME)`
 *
 * Always plays dead-center — see [rememberPannedProceduralSfx] for the
 * opt-in directional variant. Kept as its own unchanged `(SfxKind) -> Unit`
 * shape rather than widened to take a pan, so none of this app's ~25
 * existing call sites needed to change for stereo to land.
 */
@Composable
fun rememberProceduralSfx(): (SfxKind) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        { kind: SfxKind -> playProceduralSfx(context, kind) }
    }
}

/**
 * The directional sibling of [rememberProceduralSfx] — for the few screens
 * that have real positional data worth panning toward (e.g. which side of
 * a board an event happened on). Returns a stable `(SfxKind, Float) -> Unit`
 * where the pan argument follows [playProceduralSfx]'s own -1..+1 convention.
 *
 * Usage: `val playSfx = rememberPannedProceduralSfx(); ...; playSfx(SfxKind.SOLID_THUNK, -0.6f)`
 */
@Composable
fun rememberPannedProceduralSfx(): (SfxKind, Float) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        { kind: SfxKind, pan: Float -> playProceduralSfx(context, kind, pan) }
    }
}

private fun toPcm16(value: Double): Short =
    (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

/** The pure mono->stereo step [playPcmOneShot] applies, pulled out so it can be unit-tested
 *  directly -- [AudioTrack] itself has no JVM test harness in this project (the same
 *  limitation `AmbientMusicEngine`'s own DSP loop has, per its own KDoc). Each mono sample
 *  becomes one interleaved L+R frame pair via the shared [equalPowerPanGains] law -- the
 *  identical constant-power pan law `AmbientMusicEngine.kt` uses for its own voices, applied
 *  here as a simple per-channel gain multiply on an already-rendered buffer rather than at
 *  synthesis time. */
internal fun panToStereo(monoPcm: ShortArray, pan: Float): ShortArray {
    val gains = equalPowerPanGains(pan)
    val stereo = ShortArray(monoPcm.size * 2)
    for (i in monoPcm.indices) {
        val sample = monoPcm[i].toDouble()
        stereo[i * 2] = (sample * gains.left).toInt().toShort()
        stereo[i * 2 + 1] = (sample * gains.right).toInt().toShort()
    }
    return stereo
}

/** Builds and fires a one-shot `MODE_STATIC` [AudioTrack] for an already-
 *  rendered MONO buffer, panning it across a stereo output via [panToStereo]
 *  and releasing the track on a short-lived daemon thread once playback has
 *  had time to finish — avoids leaking an `AudioTrack` (or a Handler/Looper
 *  callback) per play, which matters since these fire on nearly every move
 *  in normal play. */
private fun playPcmOneShot(monoPcm: ShortArray, pan: Float = 0f) {
    if (monoPcm.isEmpty()) return
    val stereo = panToStereo(monoPcm, pan)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SFX_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        )
        .setBufferSizeInBytes(stereo.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    track.write(stereo, 0, stereo.size)
    track.play()

    // Duration is driven by the mono frame count, not the doubled stereo
    // array size — each mono sample became one L+R frame pair, not two
    // sequential frames, so playback time is unchanged by panning.
    val durationMillis = (monoPcm.size * 1000L) / SFX_SAMPLE_RATE
    thread(name = "ProceduralSfx", isDaemon = true) {
        try {
            Thread.sleep(durationMillis + 150L)
        } catch (_: InterruptedException) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }
}

private fun renderPcm(kind: SfxKind): ShortArray = when (kind) {
    SfxKind.LIGHT_TICK -> renderLightTick()
    SfxKind.SUCCESS_CHIME -> renderSuccessChime()
    SfxKind.INVALID_BUZZ -> renderInvalidBuzz()
    SfxKind.SOLID_THUNK -> renderSolidThunk()
    SfxKind.WHOOSH -> renderWhoosh()
}

/** A single enveloped sine "note" — the shared building block for the
 *  chime and the thunk's tonal component. Quick attack, exponential decay. */
private fun renderNote(freqHz: Double, durationSeconds: Double, amplitude: Double, attackSeconds: Double = 0.004): ShortArray {
    val n = (durationSeconds * SFX_SAMPLE_RATE).toInt().coerceAtLeast(1)
    val attackSamples = (attackSeconds * SFX_SAMPLE_RATE).coerceAtLeast(1.0)
    // Decay factor chosen so the envelope reaches ~2% by the end of the note.
    val decayFactor = exp(ln(0.02) / n)
    val buf = ShortArray(n)
    var envelope = 0.0
    for (i in 0 until n) {
        envelope = if (i < attackSamples) {
            envelope + (1.0 - envelope) * (1.0 / attackSamples)
        } else {
            envelope * decayFactor
        }
        val sample = sin(2.0 * PI * freqHz * i / SFX_SAMPLE_RATE) * envelope * amplitude
        buf[i] = toPcm16(sample)
    }
    return buf
}

private fun concat(vararg parts: ShortArray): ShortArray {
    val total = parts.sumOf { it.size }
    val out = ShortArray(total)
    var offset = 0
    for (part in parts) {
        System.arraycopy(part, 0, out, offset, part.size)
        offset += part.size
    }
    return out
}

private fun renderLightTick(): ShortArray {
    // Very short, high, quiet click.
    return renderNote(freqHz = 2200.0, durationSeconds = 0.035, amplitude = 0.22, attackSeconds = 0.001)
}

private fun renderSuccessChime(): ShortArray {
    // Bright ascending three-note arpeggio: C5 - E5 - G5.
    val noteDuration = 0.09
    val gap = ShortArray((0.01 * SFX_SAMPLE_RATE).toInt())
    return concat(
        renderNote(523.25, noteDuration, 0.28),
        gap,
        renderNote(659.25, noteDuration, 0.28),
        gap,
        renderNote(783.99, noteDuration, 0.3)
    )
}

private fun renderInvalidBuzz(): ShortArray {
    // Short descending, slightly detuned pair for a dissonant "buzz",
    // sliding from a mid tone down to a lower one.
    val durationSeconds = 0.22
    val n = (durationSeconds * SFX_SAMPLE_RATE).toInt()
    val startFreq = 320.0
    val endFreq = 170.0
    val detuneHz = 18.0
    val attackSamples = (0.006 * SFX_SAMPLE_RATE).coerceAtLeast(1.0)
    val decayFactor = exp(ln(0.03) / n)
    val buf = ShortArray(n)
    var envelope = 0.0
    var phaseA = 0.0
    var phaseB = 0.0
    for (i in 0 until n) {
        val t = i.toDouble() / n
        val freq = startFreq + (endFreq - startFreq) * t
        phaseA += freq / SFX_SAMPLE_RATE
        phaseB += (freq + detuneHz) / SFX_SAMPLE_RATE
        if (phaseA > 1.0) phaseA -= floor(phaseA)
        if (phaseB > 1.0) phaseB -= floor(phaseB)
        envelope = if (i < attackSamples) {
            envelope + (1.0 - envelope) * (1.0 / attackSamples)
        } else {
            envelope * decayFactor
        }
        val raw = 0.5 * sin(2.0 * PI * phaseA) + 0.5 * sin(2.0 * PI * phaseB)
        // Mild soft-clip for a slightly harsher "buzz" timbre.
        val shaped = kotlin.math.tanh(raw * 1.6)
        buf[i] = toPcm16(shaped * envelope * 0.26)
    }
    return buf
}

private fun renderSolidThunk(): ShortArray {
    // Warm low sine body plus a very short noise transient at onset, for a
    // satisfying physical-placement feel.
    val bodyDuration = 0.16
    val n = (bodyDuration * SFX_SAMPLE_RATE).toInt()
    val freq = 110.0
    val decayFactor = exp(ln(0.02) / n)
    val transientSamples = (0.012 * SFX_SAMPLE_RATE).toInt().coerceAtLeast(1)
    val buf = ShortArray(n)
    var envelope = 1.0
    var phase = 0.0
    for (i in 0 until n) {
        phase += freq / SFX_SAMPLE_RATE
        if (phase > 1.0) phase -= floor(phase)
        val body = sin(2.0 * PI * phase) * envelope * 0.32
        val transient = if (i < transientSamples) {
            val transientEnvelope = 1.0 - (i.toDouble() / transientSamples)
            (Random.nextDouble(-1.0, 1.0)) * transientEnvelope * 0.28
        } else 0.0
        buf[i] = toPcm16(body + transient)
        envelope *= decayFactor
    }
    return buf
}

private fun renderWhoosh(): ShortArray {
    // Filtered white-noise sweep: a one-pole low-pass whose cutoff opens up
    // across the duration, shaped by a rise-then-fall amplitude envelope —
    // a cheap, click-free "swoosh" for a card/tile in flight.
    val durationSeconds = 0.22
    val n = (durationSeconds * SFX_SAMPLE_RATE).toInt()
    val lowCutoff = 400.0
    val highCutoff = 6000.0
    val buf = ShortArray(n)
    var lowpassState = 0.0
    for (i in 0 until n) {
        val t = i.toDouble() / n
        val cutoff = lowCutoff + (highCutoff - lowCutoff) * t
        val coeff = 1.0 - exp(-2.0 * PI * cutoff / SFX_SAMPLE_RATE)
        val noise = Random.nextDouble(-1.0, 1.0)
        lowpassState += coeff * (noise - lowpassState)
        val amplitudeEnvelope = sin(PI * t) // rises then falls across the whole duration
        buf[i] = toPcm16(lowpassState * amplitudeEnvelope * 0.3)
    }
    return buf
}
