package com.gamesuite.arena

import android.content.Context

/**
 * The Arena's own persistent results log (finished series, newest first). It lives in its own preferences file
 * and is the ONLY thing the arena ever writes: never the player's stats, streaks, achievements or saved games.
 * Every read and write is guarded: unreadable storage just means an empty log.
 */
object ArenaResultsStore {
    private const val PREFS = "arena_results"
    private const val KEY = "log"

    fun load(context: Context): List<ArenaSeriesRecord> = try {
        ArenaResultsLog.decode(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    } catch (_: Exception) {
        emptyList()
    }

    fun append(context: Context, record: ArenaSeriesRecord) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val log = ArenaResultsLog.add(ArenaResultsLog.decode(prefs.getString(KEY, null)), record)
            prefs.edit().putString(KEY, ArenaResultsLog.encode(log)).apply()
        } catch (_: Exception) {
            // The results log is a convenience; losing a line is better than disturbing the series.
        }
    }
}
