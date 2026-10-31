package com.gamesuite.games.mahjong

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

private val Context.mahjongStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "mahjong_stats")

/**
 * Best-time persistence for Mahjong Solitaire, keyed by [MahjongLayoutId.key] (each layout has its
 * own tile count, so times are only comparable within one). Same shape as SudokuStatsStore: only
 * unassisted, un-shuffled solves are ever recorded (the screen decides; this store just keeps the
 * minimum). Never touch it from a composable body; collect [bestTimesMillis] and call [recordWin]
 * from an effect.
 */
class MahjongStatsStore(private val context: Context) {

    private object Keys {
        val BEST_TIMES_JSON = stringPreferencesKey("best_times_json")
    }

    /** Best times in millis keyed by layout key; a layout with no entry has never been solved cleanly. */
    val bestTimesMillis: Flow<Map<String, Long>> = context.mahjongStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.BEST_TIMES_JSON])
    }

    /** Records a clean solve of [layout]; true when it is a new best (the first clean solve counts). */
    suspend fun recordWin(layout: MahjongLayoutId, timeMillis: Long): Boolean {
        var isNewBest = false
        context.mahjongStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.BEST_TIMES_JSON])
            val existing = current[layout.key]
            isNewBest = existing == null || timeMillis < existing
            if (isNewBest) {
                prefs[Keys.BEST_TIMES_JSON] = Json.encodeToString(current + (layout.key to timeMillis))
            }
        }
        return isNewBest
    }

    private fun decode(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
    }
}
