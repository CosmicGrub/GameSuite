package com.gamesuite.games.colorflood

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

private val Context.colorFloodStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "color_flood_stats")

/**
 * One difficulty tier's all-time best. Either half can be set independently of the
 * other — same "not a single record run, just the best value seen so far in each
 * column" shape as SlidingPuzzleRecord/LightsOutRecord.
 */
@Serializable
data class ColorFloodRecord(
    val bestMoves: Int? = null,
    val bestTimeMillis: Long? = null
)

/** Which half(s) of a tier's record [ColorFloodStatsStore.recordSolve] actually improved. */
data class ColorFloodSolveResult(
    val isNewBestMoves: Boolean,
    val isNewBestTimeMillis: Boolean
)

/**
 * Self-contained best-move-count / best-time persistence for Color Flood, keyed by
 * [CpuDifficulty] tier since board size/color count (and therefore what's achievable)
 * differs per tier — same shape and reasoning as LightsOutStatsStore/
 * SlidingPuzzleStatsStore. Move count is a genuine, real skill dimension here (the
 * entire point of the puzzle is flooding the board in as few picks as possible), so
 * this gets the two-metric shape rather than Minesweeper/Sudoku's time-only one.
 *
 * Never read/write DataStore directly from a @Composable — ColorFloodScreen collects
 * [records] as state and calls [recordSolve] from inside the effect that reacts to a
 * board becoming won.
 */
class ColorFloodStatsStore(private val context: Context) {

    private object Keys {
        val RECORDS_JSON = stringPreferencesKey("records_json")
    }

    /** All-tier records, keyed by [CpuDifficulty.name]; a tier with no entry has never been solved. */
    val records: Flow<Map<String, ColorFloodRecord>> = context.colorFloodStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.RECORDS_JSON])
    }

    /**
     * Records one solved board for [difficulty]: [moves] the final move count and
     * [timeMillis] the stopwatch reading. A tier with no prior record counts its
     * first-ever solve as a new best in both columns. The before/after comparison
     * happens inside the same `DataStore.edit` transaction that persists the update,
     * so a win that races another read/write can't compare against a stale value.
     */
    suspend fun recordSolve(difficulty: CpuDifficulty, moves: Int, timeMillis: Long): ColorFloodSolveResult {
        var isNewBestMoves = false
        var isNewBestTimeMillis = false
        context.colorFloodStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.RECORDS_JSON])
            val existing = current[difficulty.name] ?: ColorFloodRecord()
            isNewBestMoves = existing.bestMoves == null || moves < existing.bestMoves
            isNewBestTimeMillis = existing.bestTimeMillis == null || timeMillis < existing.bestTimeMillis
            val updated = existing.copy(
                bestMoves = if (isNewBestMoves) moves else existing.bestMoves,
                bestTimeMillis = if (isNewBestTimeMillis) timeMillis else existing.bestTimeMillis
            )
            prefs[Keys.RECORDS_JSON] = Json.encodeToString(current + (difficulty.name to updated))
        }
        return ColorFloodSolveResult(isNewBestMoves, isNewBestTimeMillis)
    }

    private fun decode(raw: String?): Map<String, ColorFloodRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or (hypothetically) future-format blob should never crash app start --
        // same defensive stance every other stats store here takes.
        return runCatching { Json.decodeFromString<Map<String, ColorFloodRecord>>(raw) }.getOrDefault(emptyMap())
    }
}
