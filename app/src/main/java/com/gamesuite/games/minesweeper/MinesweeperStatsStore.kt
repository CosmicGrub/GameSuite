package com.gamesuite.games.minesweeper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.minesweeperStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "minesweeper_stats")

/**
 * Self-contained best-time persistence for Minesweeper, keyed by
 * [CpuDifficulty] tier since board size/mine density (and therefore what's
 * achievable) differs per tier — same shape and same reasoning as
 * SlidingPuzzleStatsStore (its own KDoc explains why this is deliberately
 * its own DataStore rather than a layer on top of the shared, win/loss/draw-
 * shaped GameStats). Unlike Sliding Puzzle, there's no natural secondary
 * "move count" metric here worth tracking — a Minesweeper board's own
 * "efficiency" isn't move-count-driven the way a sliding-tile puzzle's is
 * (flagging every mine adds taps but no rule requires it), so best TIME
 * alone is the one honest record to keep, matching what the reference
 * material's own home-screen tile shows ("Best 3:53").
 *
 * Never read/write DataStore directly from a @Composable — MinesweeperScreen
 * collects [bestTimesMillis] as state and calls [recordWin] from inside the
 * effect that reacts to a board becoming won.
 */
class MinesweeperStatsStore(private val context: Context) {

    private object Keys {
        val BEST_TIMES_JSON = stringPreferencesKey("best_times_json")
    }

    /** All-tier best times in millis, keyed by [CpuDifficulty.name]; a tier with no entry has never been won. */
    val bestTimesMillis: Flow<Map<String, Long>> = context.minesweeperStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.BEST_TIMES_JSON])
    }

    /**
     * Records one won board for [difficulty] with a final time of [timeMillis]. A tier with no
     * prior record counts its first-ever win as a new best — there's nothing to compare against
     * yet. The before/after comparison happens inside the same `DataStore.edit` transaction that
     * persists the update (mirrors SlidingPuzzleStatsStore.recordSolve), so a win that races
     * another read/write can't compare against a stale value.
     */
    suspend fun recordWin(difficulty: CpuDifficulty, timeMillis: Long): Boolean {
        var isNewBest = false
        context.minesweeperStatsDataStore.edit { prefs ->
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
        // same defensive stance StatsRepository/SettingsRepository/SlidingPuzzleStatsStore take.
        return runCatching { Json.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
    }
}
