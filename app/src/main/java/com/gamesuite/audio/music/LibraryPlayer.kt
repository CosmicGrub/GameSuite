package com.gamesuite.audio.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Two-[MediaPlayer] crossfading player with polite audio focus. Main-thread only (MediaPlayer
 * listeners fire on the looper of the creating thread). Holds no playlist logic: the owner
 * ([MusicManager]) decides what to play and reacts to [Callbacks].
 *
 * Audio focus: requested (AUDIOFOCUS_GAIN, USAGE_GAME / CONTENT_TYPE_MUSIC) only when playback
 * actually starts or resumes, and abandoned on every pause/stop so other apps get it back. A
 * transient loss (phone call) pauses and resumes on regain; can-duck lowers the volume; a
 * permanent loss pauses and reports through [Callbacks.onFocusLostPermanently].
 */
class LibraryPlayer(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onTrackStarted(trackId: String, durationMs: Long)
        fun onTrackFailed(trackId: String)
        /** The active track ended ([nearEnd] false) or is within the crossfade window ([nearEnd] true). */
        fun onWantsNext(nearEnd: Boolean)
        fun onPlayingChanged()
        fun onFocusDenied()
        fun onFocusLostPermanently()
        /** A prepared-but-not-yet-started track was dropped (pause/stop/focus loss), not replaced. */
        fun onPendingCancelled() {}
    }

    private class Slot(val player: MediaPlayer, val trackId: String) {
        var durationMs = 0L
        var fadeStartAt = 0L
        var fadeMs = 0L
        var fadingIn = true
        var nearEndFired = false

        fun startFade(inbound: Boolean, ms: Long) {
            fadingIn = inbound
            fadeMs = ms
            fadeStartAt = SystemClock.uptimeMillis()
        }

        fun gain(now: Long): Float {
            if (fadeMs <= 0L) return if (fadingIn) 1f else 0f
            val (inGain, outGain) = Crossfade.equalPower(Crossfade.progress(now - fadeStartAt, fadeMs))
            return if (fadingIn) inGain else outGain
        }

        fun fadeDone(now: Long): Boolean = fadeMs <= 0L || now - fadeStartAt >= fadeMs

        fun release() {
            try { player.setOnPreparedListener(null) } catch (_: Exception) {}
            try { player.setOnCompletionListener(null) } catch (_: Exception) {}
            try { player.setOnErrorListener(null) } catch (_: Exception) {}
            try { player.reset() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var active: Slot? = null
    private var outgoing: Slot? = null
    private var pending: Slot? = null

    private var playing = false
    private var pausedByFocus = false
    private var duck = 1f
    private var focusRequest: AudioFocusRequest? = null
    private var tickScheduled = false

    /** 0..1 user volume. */
    var volume: Float = 0.6f
        set(v) { field = v.coerceIn(0f, 1f); applyVolumes() }

    /** 0..8 seconds; drives the "start the next track early" window. */
    var crossfadeSeconds: Int = 3

    val isPlaying: Boolean get() = playing
    val hasActiveTrack: Boolean get() = active != null
    val isPaused: Boolean get() = active != null && !playing

    // ---- focus ----------------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        try {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    duck = 1f
                    if (pausedByFocus) {
                        pausedByFocus = false
                        startActive()
                    } else {
                        applyVolumes()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (playing) {
                        pauseInternal(abandon = false)
                        pausedByFocus = true
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    duck = DUCK_FACTOR
                    applyVolumes()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    pausedByFocus = false
                    pauseInternal(abandon = true)
                    callbacks.onFocusLostPermanently()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        return try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = req
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        duck = 1f
        try { audioManager.abandonAudioFocusRequest(req) } catch (_: Exception) {}
    }

    // ---- transport ------------------------------------------------------------------------

    /** Start [track]; if something is playing it fades out over [fadeMs] while this fades in. */
    fun play(track: MusicTrack, fadeMs: Long) {
        cancelPending()
        if (!requestFocus()) {
            callbacks.onFocusDenied()
            return
        }
        pausedByFocus = false
        val mp = MediaPlayer()
        val slot = Slot(mp, track.id)
        slot.fadeMs = fadeMs.coerceAtLeast(0L)
        pending = slot
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(appContext, Uri.parse(track.uri))
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { onPrepared(slot) }
            mp.setOnCompletionListener { onCompleted(slot) }
            mp.setOnErrorListener { _, _, _ -> onError(slot); true }
            mp.prepareAsync()
        } catch (_: Exception) {
            if (pending === slot) pending = null
            slot.release()
            failLater(track.id)
        }
    }

    private fun failLater(trackId: String) {
        handler.post { callbacks.onTrackFailed(trackId) }
    }

    private fun onPrepared(slot: Slot) {
        if (slot !== pending) {
            slot.release()
            return
        }
        slot.durationMs = try { slot.player.duration.toLong().coerceAtLeast(0L) } catch (_: Exception) { 0L }
        // A near-end hand-over is requested a full outgoing window early, but its fade can be shorter
        // (a short incoming track). Hold the prepared track until exactly that many ms remain so the
        // outgoing track's tail is not cut. It stays `pending`, so pause/stop/a new play cancel it.
        val current = active
        if (current != null && slot.fadeMs > 0L && current.durationMs > 0L) {
            val remaining = current.durationMs - (try { current.player.currentPosition.toLong() } catch (_: Exception) { 0L })
            val wait = Crossfade.handoffDelayMs(remaining, slot.fadeMs)
            if (wait > 0L) {
                handler.postDelayed({ if (slot === pending) startPrepared(slot) }, wait)
                return
            }
        }
        startPrepared(slot)
    }

    private fun startPrepared(slot: Slot) {
        if (slot !== pending) return
        pending = null
        val old = active
        outgoing?.release()
        outgoing = null
        if (old != null) {
            if (slot.fadeMs > 0L) {
                old.startFade(false, slot.fadeMs)
                outgoing = old
            } else {
                old.release()
            }
        }
        active = slot
        slot.startFade(true, slot.fadeMs)
        slot.nearEndFired = false
        applyVolumes()
        try {
            slot.player.start()
        } catch (_: Exception) {
            active = null
            slot.release()
            playing = false
            callbacks.onPlayingChanged()
            failLater(slot.trackId)
            return
        }
        playing = true
        callbacks.onTrackStarted(slot.trackId, slot.durationMs)
        callbacks.onPlayingChanged()
        scheduleTick()
    }

    private fun onCompleted(slot: Slot) {
        when {
            slot === active -> {
                // If a crossfade target is already preparing, it takes over; otherwise ask for one.
                if (pending == null) callbacks.onWantsNext(false)
            }
            slot === outgoing -> {
                outgoing = null
                slot.release()
            }
        }
    }

    private fun onError(slot: Slot) {
        when {
            slot === pending -> {
                pending = null
                slot.release()
                failLater(slot.trackId)
            }
            slot === active -> {
                active = null
                slot.release()
                playing = false
                callbacks.onPlayingChanged()
                failLater(slot.trackId)
            }
            slot === outgoing -> {
                outgoing = null
                slot.release()
            }
        }
    }

    /** @param notify true when the pending track is abandoned rather than replaced by [play]. */
    private fun cancelPending(notify: Boolean = false) {
        val p = pending ?: return
        p.release()
        pending = null
        if (notify) callbacks.onPendingCancelled()
    }

    fun pause() = pauseInternal(abandon = true)

    private fun pauseInternal(abandon: Boolean) {
        cancelPending(notify = true)
        outgoing?.release()
        outgoing = null
        val a = active
        if (a != null) {
            try { if (playing) a.player.pause() } catch (_: Exception) {}
            a.fadeMs = 0L
            a.fadingIn = true
            a.nearEndFired = false
        }
        playing = false
        if (abandon) abandonFocus()
        applyVolumes()
        callbacks.onPlayingChanged()
    }

    /** @return false when there is no active track to resume (the owner should [play] one). */
    fun resume(): Boolean {
        val a = active ?: return false
        if (playing) return true
        if (!requestFocus()) {
            callbacks.onFocusDenied()
            return true
        }
        pausedByFocus = false
        startActive()
        return true
    }

    private fun startActive() {
        val a = active ?: return
        try {
            a.player.start()
            playing = true
        } catch (_: Exception) {
            playing = false
        }
        applyVolumes()
        callbacks.onPlayingChanged()
        scheduleTick()
    }

    /** Fade the current track out over [fadeMs] and leave nothing playing. */
    fun fadeOutAndStop(fadeMs: Long) {
        val a = active
        if (a == null || fadeMs <= 0L || !playing) {
            stop()
            return
        }
        cancelPending(notify = true)
        outgoing?.release()
        a.startFade(false, fadeMs)
        outgoing = a
        active = null
        playing = false
        pausedByFocus = false
        callbacks.onPlayingChanged()
        scheduleTick()
    }

    fun stop() {
        cancelPending(notify = true)
        outgoing?.release()
        outgoing = null
        active?.release()
        active = null
        playing = false
        pausedByFocus = false
        abandonFocus()
        handler.removeCallbacks(tick)
        tickScheduled = false
        callbacks.onPlayingChanged()
    }

    // ---- volume + tick --------------------------------------------------------------------

    private fun setSlotVolume(slot: Slot, gain: Float) {
        val v = (volume * duck * gain).coerceIn(0f, 1f)
        try { slot.player.setVolume(v, v) } catch (_: Exception) {}
    }

    private fun applyVolumes() {
        val now = SystemClock.uptimeMillis()
        active?.let { setSlotVolume(it, it.gain(now)) }
        outgoing?.let { o ->
            if (o.fadeDone(now)) {
                outgoing = null
                o.release()
            } else {
                setSlotVolume(o, o.gain(now))
            }
        }
    }

    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            try {
                applyVolumes()
                checkNearEnd()
            } catch (_: Exception) {
            }
            val keepGoing = (active != null && playing) || outgoing != null
            if (keepGoing) {
                scheduleTick()
            } else if (active == null && pending == null) {
                // A fade-out finished: nothing left, give focus back.
                abandonFocus()
            }
        }
    }

    private fun checkNearEnd() {
        val a = active ?: return
        if (!playing || a.nearEndFired || pending != null) return
        val windowMs = Crossfade.effectiveMs(crossfadeSeconds, a.durationMs)
        if (windowMs <= 0L) return
        val pos = try { a.player.currentPosition.toLong() } catch (_: Exception) { return }
        if (Crossfade.shouldStartNext(pos, a.durationMs, windowMs)) {
            a.nearEndFired = true
            callbacks.onWantsNext(true)
        }
    }

    companion object {
        private const val TICK_MS = 100L
        private const val DUCK_FACTOR = 0.25f
    }
}
