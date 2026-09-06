package com.gamesuite.games.slidingpuzzle

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

private val Context.slidingPuzzleStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sliding_puzzle_stats")

/**
 * One difficulty tier's all-time best. Either half can be set independently of the other —
 * a fast-but-wasteful solve can set a new best time without beating the move-count record,
 * and vice versa — so this is not "the record run", just the best value seen so far in each
 * column.
 */
@Serializable
data class SlidingPuzzleRecord(
    val bestMoves: Int? = null,
    val bestTimeMillis: Long? = null
)

/** Which half(s) of a tier's record [SlidingPuzzleStatsStore.recordSolve] actually improved. */
data class SlidingPuzzleSolveResult(
    val isNewBestMoves: Boolean,
    val isNewBestTimeMillis: Boolean
)

/**
 * Self-contained best-move-count / best-time persistence for Sliding Puzzle, keyed by
 * [CpuDifficulty] tier since grid size (and therefore what's achievable) differs per tier.
 *
 * Deliberately its own DataStore rather than a layer on top of `stats/StatsRepository` +
 * `GameStats` — see GameStats' own KDoc, which already calls this out: the shared stats
 * screen's win/loss/draw shape is intentionally generic across all 11 games, has no room for
 * a per-tile-puzzle "fewest moves"/"fastest time" record, and coupling this single-game
 * feature onto the app-wide stats blob would mean a corrupt or racing write here risks every
 * other game's stats too. Same "one JSON-encoded map under one DataStore key" shape
 * StatsRepository uses (see its KDoc), just keyed by difficulty name instead of gameId — a
 * String key rather than the [CpuDifficulty] enum itself so this file doesn't need to make
 * that shared settings enum `@Serializable` just to satisfy this one feature.
 *
 * Never read/write DataStore directly from a @Composable — SlidingPuzzleScreen collects
 * [records] as state and calls [recordSolve] from inside the effect that reacts to a puzzle
 * becoming solved.
 */
class SlidingPuzzleStatsStore(private val context: Context) {

    private object Keys {
        val RECORDS_JSON = stringPreferencesKey("records_json")
    }

    /** All-tier records, keyed by [CpuDifficulty.name]; a tier with no entry has never been solved. */
    val records: Flow<Map<String, SlidingPuzzleRecord>> = context.slidingPuzzleStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.RECORDS_JSON])
    }

    /**
     * Records one completed puzzle for [difficulty]: [moves] the final move count and
     * [timeMillis] the stopwatch reading (see `SlidingPuzzleGame.solvedElapsedMillis`).
     * A tier with no prior record counts its first-ever solve as a new best in both columns
     * — there's nothing to compare against yet, and celebrating a player's very first solve
     * is the right call anyway.
     *
     * The before/after comparison happens inside the same `DataStore.edit` transaction that
     * persists the update (mirrors StatsRepository.recordMatch), so a solve that races
     * another read/write can't compare against a stale value.
     */
    suspend fun recordSolve(difficulty: CpuDifficulty, moves: Int, timeMillis: Long): SlidingPuzzleSolveResult {
        var isNewBestMoves = false
        var isNewBestTimeMillis = false
        context.slidingPuzzleStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.RECORDS_JSON])
            val existing = current[difficulty.name] ?: SlidingPuzzleRecord()
            isNewBestMoves = existing.bestMoves == null || moves < existing.bestMoves
            isNewBestTimeMillis = existing.bestTimeMillis == null || timeMillis < existing.bestTimeMillis
            val updated = existing.copy(
                bestMoves = if (isNewBestMoves) moves else existing.bestMoves,
                bestTimeMillis = if (isNewBestTimeMillis) timeMillis else existing.bestTimeMillis
            )
            prefs[Keys.RECORDS_JSON] = Json.encodeToString(current + (difficulty.name to updated))
        }
        return SlidingPuzzleSolveResult(isNewBestMoves, isNewBestTimeMillis)
    }

    private fun decode(raw: String?): Map<String, SlidingPuzzleRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or (hypothetically) future-format blob should never crash app start —
        // same defensive stance StatsRepository/SettingsRepository take on bad stored data.
        return runCatching { Json.decodeFromString<Map<String, SlidingPuzzleRecord>>(raw) }.getOrDefault(emptyMap())
    }
}
