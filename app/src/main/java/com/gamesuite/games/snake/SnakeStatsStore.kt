package com.gamesuite.games.snake

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.snakeStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "snake_stats")

/**
 * Personal bests for Snake, one per setup ([SnakeConfig.bestKey]: grid, walls and speed), because a
 * 12x12 wrap run and a 20x20 solid-wall run are not comparable. Same shape as BreakoutStatsStore;
 * never touched from composition directly: the screen collects [bestScores] and calls
 * [recordScore] from the effect that reacts to a run ending.
 */
class SnakeStatsStore(private val context: Context) {

    private object Keys {
        val BEST_SCORES_JSON = stringPreferencesKey("best_scores_json")
    }

    val bestScores: Flow<Map<String, Int>> = context.snakeStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.BEST_SCORES_JSON])
    }

    /** Records a finished run; returns true when it is a new best for [config]. Compare-and-write in one transaction. */
    suspend fun recordScore(config: SnakeConfig, score: Int): Boolean {
        var isNewBest = false
        context.snakeStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.BEST_SCORES_JSON])
            isNewBest = SnakeGame.isNewBestScore(current[config.bestKey], score)
            if (isNewBest) {
                prefs[Keys.BEST_SCORES_JSON] = Json.encodeToString(current + (config.bestKey to score))
            }
        }
        return isNewBest
    }

    private fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, Int>>(raw) }.getOrDefault(emptyMap())
    }
}
