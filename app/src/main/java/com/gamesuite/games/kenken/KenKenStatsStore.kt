package com.gamesuite.games.kenken

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.kenkenStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "kenken_stats")

/**
 * Self-contained best-time persistence for KenKen, keyed by [CpuDifficulty]
 * tier since board size (and therefore what's achievable) differs per tier —
 * same shape and same reasoning as SudokuStatsStore/NonogramStatsStore. No
 * secondary metric (mistake count) is tracked here either: best TIME alone
 * is the one honest record, matching the sibling stats stores' own
 * precedent rather than inventing a new shape — see KenKenGame's own KDoc,
 * SCOPING DECISION 5, for why "fewest moves" isn't a meaningful metric here
 * any more than it is for Sudoku.
 *
 * Never read/write DataStore directly from a @Composable — KenKenScreen
 * collects [bestTimesMillis] as state and calls [recordWin] from inside the
 * effect that reacts to a board becoming won.
 */
class KenKenStatsStore(private val context: Context) {

    private object Keys {
        val BEST_TIMES_JSON = stringPreferencesKey("best_times_json")
    }

    /** All-tier best times in millis, keyed by [CpuDifficulty.name]; a tier with no entry has never been won. */
    val bestTimesMillis: Flow<Map<String, Long>> = context.kenkenStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.BEST_TIMES_JSON])
    }

    /**
     * Records one solved puzzle for [difficulty] with a final time of
     * [timeMillis]. A tier with no prior record counts its first-ever win as
     * a new best. The before/after comparison happens inside the same
     * `DataStore.edit` transaction that persists the update (mirrors
     * SudokuStatsStore.recordWin), so a win that races another read/write
     * can't compare against a stale value.
     */
    suspend fun recordWin(difficulty: CpuDifficulty, timeMillis: Long): Boolean {
        var isNewBest = false
        context.kenkenStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.BEST_TIMES_JSON])
            val existing = current[difficulty.name]
            isNewBest = existing == null || timeMillis < existing
            if (isNewBest) {
                prefs[Keys.BEST_TIMES_JSON] = Json.encodeToString(current + (difficulty.name to timeMillis))
            }
        }
        return isNewBest
    }

    private fun decode(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or (hypothetically) future-format blob should never crash app start --
        // same defensive stance every other stats store here takes.
        return runCatching { Json.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
    }
}
