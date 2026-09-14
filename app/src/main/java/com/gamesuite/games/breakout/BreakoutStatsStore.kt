package com.gamesuite.games.breakout

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

private val Context.breakoutStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "breakout_stats")

/**
 * Best-score persistence for Breakout, keyed by [CpuDifficulty] tier — same shape as every
 * other `XStatsStore` in this app (e.g. ColorFloodStatsStore), but a single metric (best score)
 * rather than a moves/time pair: Breakout has no move count and no "time to solve" (it's not a
 * puzzle with an end state, it's an arcade high-score game), so the classic single "best score"
 * arcade convention is the honest fit here, not a two-metric shape borrowed from the puzzle
 * games in this batch just for consistency's sake.
 *
 * Never read/write DataStore directly from a @Composable — BreakoutScreen collects [bestScores]
 * as state and calls [recordScore] from inside the effect that reacts to a run ending
 * ([BreakoutGame.BreakoutState.gameOver] becoming true), same pattern every other stats store
 * here follows.
 */
class BreakoutStatsStore(private val context: Context) {

    private object Keys {
        val BEST_SCORES_JSON = stringPreferencesKey("best_scores_json")
    }

    /** All-tier best scores, keyed by [CpuDifficulty.name]; a tier with no entry has never been
     *  played to a game-over. */
    val bestScores: Flow<Map<String, Int>> = context.breakoutStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.BEST_SCORES_JSON])
    }

    /**
     * Records one finished run's [score] for [difficulty]. Returns true if it's a new best for
     * that tier. The before/after comparison happens inside the same `DataStore.edit`
     * transaction that persists the update, so a run ending mid-race with another read/write
     * can't compare against a stale value — same reasoning ColorFloodStatsStore.recordSolve
     * documents for its own transaction.
     */
    suspend fun recordScore(difficulty: CpuDifficulty, score: Int): Boolean {
        var isNewBest = false
        context.breakoutStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.BEST_SCORES_JSON])
            val existing = current[difficulty.name]
            isNewBest = existing == null || score > existing
            if (isNewBest) {
                prefs[Keys.BEST_SCORES_JSON] = Json.encodeToString(current + (difficulty.name to score))
            }
        }
        return isNewBest
    }

    private fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or (hypothetically) future-format blob should never crash app start --
        // same defensive stance every other stats store here takes.
        return runCatching { Json.decodeFromString<Map<String, Int>>(raw) }.getOrDefault(emptyMap())
    }
}
