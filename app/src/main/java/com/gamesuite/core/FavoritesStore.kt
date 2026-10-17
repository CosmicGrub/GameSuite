package com.gamesuite.core

import android.content.Context

/**
 * The player's starred games, kept in SharedPreferences "favorites" as a set of game ids.
 * Unknown ids (a game removed in a later version) are harmless: the UI only shows ids it has a spec for.
 */
class FavoritesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isFavorite(gameId: String): Boolean = gameId in all()

    /** Flips [gameId] and returns the new full set. */
    fun toggle(gameId: String): Set<String> {
        val next = all().toMutableSet()
        if (!next.add(gameId)) next.remove(gameId)
        // Always hand putStringSet a fresh set: the one SharedPreferences returned must not be mutated.
        prefs.edit().putStringSet(KEY, HashSet(next)).apply()
        return next
    }

    private companion object {
        const val KEY = "ids"
    }
}
