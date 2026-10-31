package com.gamesuite.games.g2048

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

private val Context.g2048StatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "g2048_stats")

/**
 * Self-contained best-score persistence for 2048, keyed by [G2048Size.id] since a 3 x 3 score and a
 * 5 x 5 score are not comparable. Same shape as SudokuStatsStore. The screen calls [recordScore]
 * only for UNASSISTED games (no Undo, no Hint), so an assisted game can never set a record.
 */
class G2048StatsStore(private val context: Context) {

    private object Keys {
        val BEST_SCORES_JSON = stringPreferencesKey("best_scores_json")
    }

    /** Best score per size id; a size with no entry has never been scored. */
    val bestScores: Flow<Map<String, Int>> = context.g2048StatsDataStore.data.map { prefs ->
        decode(prefs[Keys.BEST_SCORES_JSON])
    }

    /** Stores [score] for [sizeId] if it beats the stored best; true when it did. The comparison runs inside the same edit transaction that writes. */
    suspend fun recordScore(sizeId: String, score: Int): Boolean {
        if (score <= 0) return false
        var isNewBest = false
        context.g2048StatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.BEST_SCORES_JSON])
            val existing = current[sizeId]
            isNewBest = existing == null || score > existing
            if (isNewBest) {
                prefs[Keys.BEST_SCORES_JSON] = Json.encodeToString(current + (sizeId to score))
            }
        }
        return isNewBest
    }

    private fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or future-format blob must never crash the screen.
        return runCatching { Json.decodeFromString<Map<String, Int>>(raw) }.getOrDefault(emptyMap())
    }
}
