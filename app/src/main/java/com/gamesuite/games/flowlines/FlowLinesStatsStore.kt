package com.gamesuite.games.flowlines

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.flowLinesStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "flow_lines_stats")

/** One tier's all-time best; either half can improve independently of the other. */
@Serializable
data class FlowLinesRecord(
    val bestMoves: Int? = null,
    val bestTimeMillis: Long? = null
)

/** Which half(s) of a tier's record [FlowLinesStatsStore.recordSolve] actually improved. */
data class FlowLinesSolveResult(
    val isNewBestMoves: Boolean,
    val isNewBestTimeMillis: Boolean
)

/**
 * Best-moves / best-time persistence for Flow Lines, keyed by [FlowTier] name. Only UNASSISTED
 * solves are ever passed in (the screen skips the call after a hint). The comparison happens inside
 * the same `DataStore.edit` transaction that persists the update.
 */
class FlowLinesStatsStore(private val context: Context) {

    private object Keys {
        val RECORDS_JSON = stringPreferencesKey("records_json")
    }

    /** All-tier records keyed by [FlowTier.name]; a tier with no entry has never been solved unassisted. */
    val records: Flow<Map<String, FlowLinesRecord>> = context.flowLinesStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.RECORDS_JSON])
    }

    suspend fun recordSolve(tier: FlowTier, moves: Int, timeMillis: Long): FlowLinesSolveResult {
        var isNewBestMoves = false
        var isNewBestTimeMillis = false
        context.flowLinesStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.RECORDS_JSON])
            val existing = current[tier.name] ?: FlowLinesRecord()
            isNewBestMoves = existing.bestMoves == null || moves < existing.bestMoves
            isNewBestTimeMillis = existing.bestTimeMillis == null || timeMillis < existing.bestTimeMillis
            val updated = existing.copy(
                bestMoves = if (isNewBestMoves) moves else existing.bestMoves,
                bestTimeMillis = if (isNewBestTimeMillis) timeMillis else existing.bestTimeMillis
            )
            prefs[Keys.RECORDS_JSON] = Json.encodeToString(current + (tier.name to updated))
        }
        return FlowLinesSolveResult(isNewBestMoves, isNewBestTimeMillis)
    }

    private fun decode(raw: String?): Map<String, FlowLinesRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, FlowLinesRecord>>(raw) }.getOrDefault(emptyMap())
    }
}
