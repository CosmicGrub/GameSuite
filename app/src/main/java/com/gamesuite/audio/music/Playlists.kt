package com.gamesuite.audio.music

import kotlin.random.Random

/** Pure playlist operations; every function returns a new list. */
object Playlists {

    /**
     * Align [existing] with [allTrackIds]: drop entries whose track is gone, keep order and
     * enabled flag of survivors, append new tracks (enabled) at the end. A never-seen context
     * therefore defaults to every track, enabled, in library order.
     */
    fun reconcile(existing: List<PlaylistEntry>, allTrackIds: List<String>): List<PlaylistEntry> {
        val valid = allTrackIds.toHashSet()
        val kept = existing.filter { it.trackId in valid }.distinctBy { it.trackId }
        val have = kept.map { it.trackId }.toHashSet()
        val added = allTrackIds.distinct().filter { it !in have }.map { PlaylistEntry(it, true) }
        return kept + added
    }

    fun toggle(list: List<PlaylistEntry>, trackId: String, enabled: Boolean): List<PlaylistEntry> =
        list.map { if (it.trackId == trackId) it.copy(enabled = enabled) else it }

    /** Move the entry at [index] by [delta] (-1 up, +1 down); out-of-range moves are no-ops. */
    fun move(list: List<PlaylistEntry>, index: Int, delta: Int): List<PlaylistEntry> {
        val target = index + delta
        if (delta == 0 || index !in list.indices || target !in list.indices) return list
        val out = list.toMutableList()
        val item = out.removeAt(index)
        out.add(target, item)
        return out
    }

    /** "Use all tracks": everything enabled, order untouched. */
    fun enableAll(list: List<PlaylistEntry>): List<PlaylistEntry> = list.map { it.copy(enabled = true) }

    fun enabledIds(list: List<PlaylistEntry>): List<String> = list.filter { it.enabled }.map { it.trackId }
}

/**
 * Decides what plays next and what "previous" means. Pure (inject [rng] for tests).
 * Shuffle never repeats a track until every track in the current list has been played, and a new
 * round never starts with the track that just finished (unless it is the only one).
 */
class PlaybackQueue(private val rng: Random = Random.Default) {
    private var ids: List<String> = emptyList()
    private val played = LinkedHashSet<String>()
    private val history = ArrayList<String>()

    var current: String? = null
        private set

    fun setList(newIds: List<String>) {
        ids = newIds.distinct()
        val keep = ids.toHashSet()
        played.retainAll(keep)
        history.removeAll { it !in keep }
    }

    fun contains(id: String?): Boolean = id != null && id in ids

    /** Make [id] the current track (counts as played). */
    fun markCurrent(id: String) {
        val prev = current
        if (prev != null && prev != id) {
            history.add(prev)
            if (history.size > 50) history.removeAt(0)
        }
        current = id
        played.add(id)
    }

    /** Pick, mark and return the next track id; null when the list is empty. */
    fun next(shuffle: Boolean): String? {
        if (ids.isEmpty()) return null
        val cur = current
        val pick: String
        if (!shuffle) {
            val i = if (cur == null) -1 else ids.indexOf(cur)
            pick = ids[(i + 1) % ids.size]
        } else {
            if (ids.all { it in played }) {
                played.clear()
                if (cur != null && cur in ids) played.add(cur)
            }
            var pool = ids.filter { it !in played }
            if (pool.isEmpty()) pool = ids
            pick = pool[rng.nextInt(pool.size)]
        }
        markCurrent(pick)
        return pick
    }

    /** Step back through history; null when there is nothing to go back to. */
    fun previous(): String? {
        while (history.isNotEmpty()) {
            val p = history.removeAt(history.size - 1)
            if (p in ids) {
                current = p
                played.add(p)
                return p
            }
        }
        return null
    }
}

/** Crossfade math, all pure. */
object Crossfade {
    /** Crossfade can't exceed half the track, or a short track would be all fade. */
    fun effectiveMs(crossfadeSeconds: Int, trackDurationMs: Long): Long {
        val wanted = crossfadeSeconds.coerceIn(0, 8) * 1000L
        if (trackDurationMs <= 0L) return wanted
        return wanted.coerceAtMost(trackDurationMs / 2)
    }

    /** 0..1 progress of a fade; instant (1.0) when [durationMs] is not positive. */
    fun progress(elapsedMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 1f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Equal-power gains (incoming, outgoing) at [progress]; incoming^2 + outgoing^2 == 1. */
    fun equalPower(progress: Float): Pair<Float, Float> {
        val a = progress.coerceIn(0f, 1f) * Math.PI / 2.0
        return Pair(Math.sin(a).toFloat(), Math.cos(a).toFloat())
    }

    /** Should the next track start now so the fade overlaps the end of the current one? */
    fun shouldStartNext(positionMs: Long, durationMs: Long, crossfadeMs: Long): Boolean =
        crossfadeMs > 0L && durationMs > 0L && durationMs - positionMs <= crossfadeMs
}
