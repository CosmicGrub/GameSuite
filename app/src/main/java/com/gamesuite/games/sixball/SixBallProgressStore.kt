package com.gamesuite.games.sixball

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

private val Context.sixBallDataStore: DataStore<Preferences> by preferencesDataStore(name = "six_ball_progress")

/** The best result on one level: fewest tilts and most stars ever (each improves on its own). */
@Serializable
data class SixBallRecord(val bestMoves: Int, val stars: Int)

/** The pure part of progress keeping, so it is unit tested without Android. */
object SixBallProgress {
    /** Daily levels kept in the record; older ones are dropped so the blob cannot grow forever. */
    const val MAX_DAILY_KEPT = 60

    fun merge(existing: SixBallRecord?, moves: Int, stars: Int): SixBallRecord =
        if (existing == null) SixBallRecord(moves, stars)
        else SixBallRecord(minOf(existing.bestMoves, moves), maxOf(existing.stars, stars))

    fun isNewBest(existing: SixBallRecord?, moves: Int): Boolean = existing == null || moves < existing.bestMoves

    /** Keeps every campaign record and only the newest [MAX_DAILY_KEPT] daily ones. */
    fun prune(records: Map<String, SixBallRecord>): Map<String, SixBallRecord> {
        val dailies = records.keys.mapNotNull { id -> SixBallLevels.dailyDay(id)?.let { it to id } }.sortedByDescending { it.first }
        val drop = dailies.drop(MAX_DAILY_KEPT).map { it.second }.toSet()
        return records.filterKeys { it !in drop }
    }

    /** Total stars over the campaign levels, for the level picker's header. */
    fun campaignStars(records: Map<String, SixBallRecord>): Int =
        (1..SixBallLevels.CAMPAIGN_COUNT).sumOf { records[SixBallLevels.campaignId(it)]?.stars ?: 0 }

    /** The campaign level to offer first: the first one with no stars yet (the last when all are done). */
    fun nextUnsolved(records: Map<String, SixBallRecord>): Int =
        (1..SixBallLevels.CAMPAIGN_COUNT).firstOrNull { (records[SixBallLevels.campaignId(it)]?.stars ?: 0) == 0 }
            ?: SixBallLevels.CAMPAIGN_COUNT

    fun decode(raw: String?): Map<String, SixBallRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            Json.decodeFromString<Map<String, SixBallRecord>>(raw).filter { (id, r) ->
                (SixBallLevels.campaignNumber(id) != null || SixBallLevels.dailyDay(id) != null) &&
                    r.bestMoves in 0..SixBallGame.MAX_MOVES && r.stars in 0..3
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/** Stars and best tilt counts per level, in its own DataStore so a bad write here can never touch another game's stats. */
class SixBallProgressStore(private val context: Context) {
    private object Keys { val JSON = stringPreferencesKey("records_json") }

    val records: Flow<Map<String, SixBallRecord>> = context.sixBallDataStore.data.map { SixBallProgress.decode(it[Keys.JSON]) }

    /** Records a solve; returns true when it beat the level's best tilt count (or was the first). */
    suspend fun recordSolve(levelId: String, moves: Int, stars: Int): Boolean {
        var newBest = false
        context.sixBallDataStore.edit { prefs ->
            val current = SixBallProgress.decode(prefs[Keys.JSON])
            newBest = SixBallProgress.isNewBest(current[levelId], moves)
            val merged = current + (levelId to SixBallProgress.merge(current[levelId], moves, stars))
            prefs[Keys.JSON] = Json.encodeToString(SixBallProgress.prune(merged))
        }
        return newBest
    }
}
