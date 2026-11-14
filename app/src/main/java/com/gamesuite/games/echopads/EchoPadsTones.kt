package com.gamesuite.games.echopads

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.gamesuite.games.cards.CardSounds
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * The four pad tones. ProceduralSfx has no pitched sound (its kinds are fixed clicks and chimes), so Echo Pads
 * renders its own four short notes, using the same recipe: each note is synthesised once into a cached PCM buffer
 * and played off the main thread through a one-shot MODE_STATIC AudioTrack on a single background executor.
 * The global master-sound switch ([CardSounds.soundEnabled]) is respected. Win and miss jingles still use
 * ProceduralSfx's public API from the screen.
 */
object EchoPadTones {
    const val SAMPLE_RATE = 44100
    private const val DURATION_S = 0.32

    /** One pad's note as 16-bit mono PCM: a soft sine with a quiet octave overtone, a quick attack and a smooth decay. Pure, so it is unit tested. */
    fun renderPcm(pad: Int): ShortArray {
        val f = EchoPads.frequencies[pad.coerceIn(0, EchoPads.COUNT - 1)]
        val n = (DURATION_S * SAMPLE_RATE).toInt()
        val attack = (0.006 * SAMPLE_RATE).coerceAtLeast(1.0)
        val decay = exp(ln(0.03) / n)
        val out = ShortArray(n)
        var env = 0.0
        for (i in 0 until n) {
            env = if (i < attack) env + (1.0 - env) * (1.0 / attack) else env * decay
            val t = i.toDouble() / SAMPLE_RATE
            val s = (sin(2.0 * PI * f * t) + 0.18 * sin(4.0 * PI * f * t)) * env * 0.38
            out[i] = (s.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private val cache = ConcurrentHashMap<Int, ShortArray>()
    private val inFlight = AtomicInteger(0)
    private val queued = AtomicInteger(0)
    private const val MAX_CONCURRENT = 6
    private const val MAX_QUEUED = 12

    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "EchoPadTones").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** Renders all four notes ahead of the first tap (off the main thread), so the first tone is not late. Safe to call repeatedly. */
    fun warmUp() {
        try {
            executor.execute {
                for (pad in 0 until EchoPads.COUNT) {
                    try { cache.getOrPut(pad) { renderPcm(pad) } } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    /** Plays [pad]'s tone now, if sound is on. Never blocks and never throws: a missing sound must not stall a game. */
    fun play(pad: Int) {
        if (!CardSounds.soundEnabled || pad !in 0 until EchoPads.COUNT) return
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet()
            return
        }
        try {
            executor.execute {
                queued.decrementAndGet()
                try { playNow(pad) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            queued.decrementAndGet()
        }
    }

    private fun playNow(pad: Int) {
        val pcm = cache.getOrPut(pad) { renderPcm(pad) }
        if (inFlight.incrementAndGet() > MAX_CONCURRENT) {
            inFlight.decrementAndGet()
            return
        }
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            val toRelease = track
            executor.schedule({
                try { toRelease.stop() } catch (_: Throwable) {}
                try { toRelease.release() } catch (_: Throwable) {}
                inFlight.decrementAndGet()
            }, pcm.size * 1000L / SAMPLE_RATE + 150L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            try { track?.release() } catch (_: Throwable) {}
            inFlight.decrementAndGet()
        }
    }
}
