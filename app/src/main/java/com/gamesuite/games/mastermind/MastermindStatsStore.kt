package com.gamesuite.games.mastermind

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

private val Context.mastermindStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "mastermind_stats")

/**
 * One difficulty tier's all-time best. Either half can be set independently of the
 * other — same "not a single record run, just the best value seen so far in each
 * column" shape as EdgeMatchRecord/LightsOutRecord/ColorFloodRecord.
 */
@Serializable
data class MastermindRecord(
    val bestGuesses: Int? = null,
    val bestTimeMillis: Long? = null
)

/** Which half(s) of a tier's record [MastermindStatsStore.recordSolve] actually improved. */
data class MastermindSolveResult(
    val isNewBestGuesses: Boolean,
    val isNewBestTimeMillis: Boolean
)

/**
 * Self-contained best-guess-count / best-time persistence for Mastermind, keyed by
 * [CpuDifficulty] tier since positions/color count (and therefore what's achievable) differs
 * per tier — same shape and reasoning as EdgeMatchStatsStore/ColorFloodStatsStore/
 * LightsOutStatsStore. Guess count is a genuine, real skill dimension here (fewer guesses to
 * crack the secret), so this gets the two-metric shape rather than Minesweeper/Sudoku's
 * time-only one.
 *
 * Never read/write DataStore directly from a @Composable — MastermindScreen collects [records]
 * as state and calls [recordSolve] from inside the effect that reacts to a round becoming
 * solved. A round ending in [MastermindState.outOfGuesses] is never recorded — no achievement to
 * remember there.
 */
class MastermindStatsStore(private val context: Context) {

    private object Keys {
        val RECORDS_JSON = stringPreferencesKey("records_json")
    }

    /** All-tier records, keyed by [CpuDifficulty.name]; a tier with no entry has never been solved. */
    val records: Flow<Map<String, MastermindRecord>> = context.mastermindStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.RECORDS_JSON])
    }

    /**
     * Records one solved round for [difficulty]: [guesses] the final guess count and
     * [timeMillis] the stopwatch reading. A tier with no prior record counts its first-ever
     * solve as a new best in both columns. The before/after comparison happens inside the same
     * `DataStore.edit` transaction that persists the update, so a win that races another
     * read/write can't compare against a stale value.
     */
    suspend fun recordSolve(difficulty: CpuDifficulty, guesses: Int, timeMillis: Long): MastermindSolveResult {
        var isNewBestGuesses = false
        var isNewBestTimeMillis = false
        context.mastermindStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.RECORDS_JSON])
            val existing = current[difficulty.name] ?: MastermindRecord()
            isNewBestGuesses = existing.bestGuesses == null || guesses < existing.bestGuesses
            isNewBestTimeMillis = existing.bestTimeMillis == null || timeMillis < existing.bestTimeMillis
            val updated = existing.copy(
                bestGuesses = if (isNewBestGuesses) guesses else existing.bestGuesses,
                bestTimeMillis = if (isNewBestTimeMillis) timeMillis else existing.bestTimeMillis
            )
            prefs[Keys.RECORDS_JSON] = Json.encodeToString(current + (difficulty.name to updated))
        }
        return MastermindSolveResult(isNewBestGuesses, isNewBestTimeMillis)
    }

    private fun decode(raw: String?): Map<String, MastermindRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or (hypothetically) future-format blob should never crash app start --
        // same defensive stance every other stats store here takes.
        return runCatching { Json.decodeFromString<Map<String, MastermindRecord>>(raw) }.getOrDefault(emptyMap())
    }
}
