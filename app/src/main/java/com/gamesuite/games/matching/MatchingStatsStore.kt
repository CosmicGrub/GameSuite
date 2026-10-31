package com.gamesuite.games.matching

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

private val Context.matchingStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "matching_stats")

/** The solo personal best for one board size: fewest moves and fastest time are kept independently. */
@Serializable
data class MatchingRecord(val bestMoves: Int? = null, val bestTimeMillis: Long? = null)

/** Which halves of a record a finished solo board improved. */
data class MatchingRecordResult(val isNewBestMoves: Boolean, val isNewBestTime: Boolean)

/** The pure comparison, kept apart from DataStore so it is unit tested. */
object MatchingRecords {
    fun improve(existing: MatchingRecord?, moves: Int, timeMillis: Long): Pair<MatchingRecord, MatchingRecordResult> {
        val old = existing ?: MatchingRecord()
        val newMoves = old.bestMoves == null || moves < old.bestMoves
        val newTime = old.bestTimeMillis == null || timeMillis < old.bestTimeMillis
        return MatchingRecord(
            bestMoves = if (newMoves) moves else old.bestMoves,
            bestTimeMillis = if (newTime) timeMillis else old.bestTimeMillis
        ) to MatchingRecordResult(newMoves, newTime)
    }

    fun decode(raw: String?): Map<String, MatchingRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            Json.decodeFromString<Map<String, MatchingRecord>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encode(map: Map<String, MatchingRecord>): String = Json.encodeToString(map)
}

/** Solo personal bests, keyed by card count ("12".."40"). Only clean runs (no hint, no undo) are recorded. */
class MatchingStatsStore(private val context: Context) {
    private object Keys {
        val RECORDS_JSON = stringPreferencesKey("records_json")
    }

    val records: Flow<Map<String, MatchingRecord>> =
        context.matchingStatsDataStore.data.map { MatchingRecords.decode(it[Keys.RECORDS_JSON]) }

    suspend fun recordSolve(cardCount: Int, moves: Int, timeMillis: Long): MatchingRecordResult {
        var result = MatchingRecordResult(false, false)
        context.matchingStatsDataStore.edit { prefs ->
            val current = MatchingRecords.decode(prefs[Keys.RECORDS_JSON])
            val (updated, r) = MatchingRecords.improve(current[cardCount.toString()], moves, timeMillis)
            result = r
            prefs[Keys.RECORDS_JSON] = MatchingRecords.encode(current + (cardCount.toString() to updated))
        }
        return result
    }
}
