package com.gamesuite.games.cards

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.gamesuite.R

/**
 * Shared card SFX — procedurally generated tones (card_place/draw/shuffle.wav
 * under res/raw), loaded once and reused by every card game. Cheap SoundPool
 * usage: short clips, low latency, fine for this many simultaneous plays.
 */
class CardSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // SoundPool.load() only queues an async decode and returns a sample id
    // immediately — the sample isn't actually playable until decoding
    // finishes. A play() call that races the decode (e.g. the very first
    // sound played after this singleton is created) would otherwise
    // silently no-op. Track completion explicitly and replay any play()
    // request that arrived before its sample finished loading.
    private val loadedIds = mutableSetOf<Int>()
    private val pendingPlays = mutableListOf<PendingPlay>()

    private data class PendingPlay(val sampleId: Int, val volume: Float, val rate: Float)

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedIds += sampleId
                val ready = pendingPlays.filter { it.sampleId == sampleId }
                pendingPlays.removeAll(ready)
                ready.forEach { playLoaded(it.sampleId, it.volume, it.rate) }
            }
        }
    }

    private val placeId = pool.load(context, R.raw.card_place, 1)
    private val drawId = pool.load(context, R.raw.card_draw, 1)
    private val shuffleId = pool.load(context, R.raw.card_shuffle, 1)
    private val tapId = pool.load(context, R.raw.ui_tap, 1)

    private fun playLoaded(sampleId: Int, volume: Float, rate: Float) {
        pool.play(sampleId, volume, volume, 0, 0, rate)
    }

    private fun play(sampleId: Int, volume: Float = 1f, rate: Float = 1f) {
        if (!soundEnabled) return
        if (sampleId in loadedIds) {
            playLoaded(sampleId, volume, rate)
        } else {
            // Decode for this sample hasn't finished yet — queue it and let
            // the load-complete listener fire it once it's ready, instead
            // of silently dropping the sound.
            pendingPlays += PendingPlay(sampleId, volume, rate)
        }
    }

    fun playPlace() = play(placeId)
    fun playDraw() = play(drawId)
    fun playShuffle() = play(shuffleId, volume = 0.8f)
    /** Generic short feedback tap — reused by any board game placing a piece (Tic-Tac-Toe, Dominoes, Mancala). */
    fun playTap() = play(tapId, volume = 0.9f)

    fun release() = pool.release()

    companion object {
        private var instance: CardSounds? = null

        /**
         * Global master-sound switch, mirrored from AppSettings.soundEnabled
         * (see MainActivity, which keeps this in sync with the live setting).
         * A plain top-level var rather than something read through DI/Compose
         * state because CardSounds is a singleton with no Compose awareness
         * of its own — every play() call reads this directly, so flipping it
         * takes effect on the very next sound, no screen recomposition needed.
         */
        @Volatile
        var soundEnabled: Boolean = true

        /** Shared instance across all card games in the process — avoids reloading clips per screen. */
        fun get(context: Context): CardSounds {
            return instance ?: CardSounds(context.applicationContext).also { instance = it }
        }
    }
}
