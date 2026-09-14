package com.gamesuite.games.edgematch

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

private val Context.edgeMatchStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "edge_match_stats")

/**
 * One difficulty tier's all-time best. Either half can be set independently of the
 * other — same "not a single record run, just the best value seen so far in each
 * column" shape as ColorFloodRecord/LightsOutRecord/SlidingPuzzleRecord.
 */
@Serializable
data class EdgeMatchRecord(
    val bestMoves: Int? = null,
    val bestTimeMillis: Long? = null
)

/** Which half(s) of a tier's record [EdgeMatchStatsStore.recordSolve] actually improved. */
data class EdgeMatchSolveResult(
    val isNewBestMoves: Boolean,
    val isNewBestTimeMillis: Boolean
)

/**
 * Self-contained best-move-count / best-time persistence for Edge Match, keyed by
 * [CpuDifficulty] tier since grid size (and therefore what's achievable) differs per
 * tier — same shape and reasoning as ColorFloodStatsStore/LightsOutStatsStore/
 * SlidingPuzzleStatsStore. Move count is a genuine, real skill dimension here (fewer
 * rotations to solve), so this gets the two-metric shape rather than
 * Minesweeper/Sudoku's time-only one.
 *
 * Never read/write DataStore directly from a @Composable — EdgeMatchScreen collects
 * [records] as state and calls [recordSolve] from inside the effect that reacts to a
 * puzzle becoming solved.
 */
class EdgeMatchStatsStore(private val context: Context) {

    private object Keys {
        val RECORDS_JSON = stringPreferencesKey("records_json")
    }

    /** All-tier records, keyed by [CpuDifficulty.name]; a tier with no entry has never been solved. */
    val records: Flow<Map<String, EdgeMatchRecord>> = context.edgeMatchStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.RECORDS_JSON])
    }

    /**
     * Records one solved puzzle for [difficulty]: [moves] the final move count and
     * [timeMillis] the stopwatch reading. A tier with no prior record counts its
     * first-ever solve as a new best in both columns. The before/after comparison
     * happens inside the same `DataStore.edit` transaction that persists the update,
     * so a win that races another read/write can't compare against a stale value.
     */
    suspend fun recordSolve(difficulty: CpuDifficulty, moves: Int, timeMillis: Long): EdgeMatchSolveResult {
        var isNewBestMoves = false
        var isNewBestTimeMillis = false
        context.edgeMatchStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.RECORDS_JSON])
            val existing = current[difficulty.name] ?: EdgeMatchRecord()
            isNewBestMoves = existing.bestMoves == null || moves < existing.bestMoves
            isNewBestTimeMillis = existing.bestTimeMillis == null || timeMillis < existing.bestTimeMillis
            val updated = existing.copy(
                bestMoves = if (isNewBestMoves) moves else existing.bestMoves,
                bestTimeMillis = if (isNewBestTimeMillis) timeMillis else existing.bestTimeMillis
            )
            prefs[Keys.RECORDS_JSON] = Json.encodeToString(current + (difficulty.name to updated))
        }
        return EdgeMatchSolveResult(isNewBestMoves, isNewBestTimeMillis)
    }

    private fun decode(raw: String?): Map<String, EdgeMatchRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or (hypothetically) future-format blob should never crash app start --
        // same defensive stance every other stats store here takes.
        return runCatching { Json.decodeFromString<Map<String, EdgeMatchRecord>>(raw) }.getOrDefault(emptyMap())
    }
}
