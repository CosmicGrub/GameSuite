package com.gamesuite.games.gemcascade

import android.content.Context

/** Loads the 30 bundled quest levels (assets/gem_cascade/levels.json). A missing or broken file yields no levels. */
object GemLevelStore {
    const val ASSET = "gem_cascade/levels.json"

    @Volatile
    private var cached: List<GemLevel>? = null

    fun load(context: Context): List<GemLevel> {
        cached?.let { return it }
        val levels = try {
            context.applicationContext.assets.open(ASSET).use { GemLevels.parse(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (_: Exception) {
            emptyList()
        }
        cached = levels
        return levels
    }
}
