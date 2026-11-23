package com.gamesuite.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a host's StateSync version counter starts.
 *
 * Every game's guest drops a StateSync whose `version` is not above the last one it applied. A host whose game object
 * is recreated (leave and re-host, a restarted match object) used to restart that counter at 0, so a guest that kept
 * its own counter ignored every later state. Each new host game object now starts its counter at the base of a fresh
 * epoch: the top bits are an epoch number that rises with every game object created in this process (seeded from the
 * wall clock so a new process also tends upward), the low 20 bits are the ordinary per-object counter. Plain Int
 * comparison then orders epochs first, so guests need no change and a peer that predates this still works: an old
 * host's small versions simply sit in epoch 0, below any new host's.
 *
 * Limits, deliberately accepted: 2^20 (about a million) syncs per object before it spills into the next epoch's range
 * (harmless, it only keeps increasing), and the epoch number saturates at 2047, after which new objects share the top
 * epoch and the old restart behaviour returns.
 */
object SyncEpoch {
    private const val WINDOW_BITS = 20
    private const val MAX_EPOCH = 2047

    /** Off only under the project's JVM unit tests (system property `gamesuite.syncEpoch=off`, set in app/build.gradle.kts),
     *  whose many hand-written StateSyncs use small literal versions; epoch tests flip this back on. */
    @Volatile internal var enabled: Boolean = System.getProperty("gamesuite.syncEpoch") != "off"

    private val epoch = AtomicInteger(((System.currentTimeMillis() / 60_000L) % 1024L).toInt())

    /** The first version a new host game object should use; strictly greater than any base handed out before it. */
    fun base(): Int = if (!enabled) 0 else epoch.updateAndGet { if (it < MAX_EPOCH) it + 1 else it }.coerceAtMost(MAX_EPOCH) shl WINDOW_BITS

    /** The epoch number carried in the top bits of a version (0 = a peer that predates epochs). */
    fun epochOf(version: Int): Int = version ushr WINDOW_BITS
}
