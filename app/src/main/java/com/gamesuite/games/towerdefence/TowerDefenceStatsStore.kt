package com.gamesuite.games.towerdefence

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

private val Context.towerDefenceStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tower_defence_stats")

/**
 * Furthest-wave-reached persistence for Tower Defence, keyed by (level, difficulty) — per
 * `docs/TOWER_DEFENCE_DESIGN.md`'s own **Stats** section: unlike every other `XStatsStore` in this
 * app (which key by [CpuDifficulty] alone), Tower Defence's levels are fixed, hand-designed maps
 * rather than a difficulty-scaled board size, so a best-wave figure on "Switchback" and "Spiral
 * Keep" are genuinely different records, not the same number filed under two names. The
 * composite key ([keyFor]) is the mechanical extension of every other store's single-dimension
 * key, not a new idiom.
 *
 * Otherwise the same "arcade high score" shape [com.gamesuite.games.breakout.BreakoutStatsStore]
 * already established (a single metric, not a moves/time pair): Tower Defence has no move count
 * and no time-to-solve, its skill signal is purely "how far did you get."
 *
 * Never read/write DataStore directly from a @Composable — TowerDefenceScreen collects
 * [bestWaves] as state and calls [recordWave] from inside the effect that reacts to a run ending
 * (`TowerDefenceState.runOver` becoming true), same pattern every other stats store here follows.
 */
class TowerDefenceStatsStore(private val context: Context) {

    private object Keys {
        val BEST_WAVES_JSON = stringPreferencesKey("best_waves_json")
    }

    private fun keyFor(levelId: String, difficulty: CpuDifficulty): String = "$levelId:${difficulty.name}"

    /** All (level, difficulty) best-wave entries, keyed by [keyFor]; a combination with no entry
     *  has never been played to a run-over. */
    val bestWaves: Flow<Map<String, Int>> = context.towerDefenceStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.BEST_WAVES_JSON])
    }

    /** Reads [bestWaves] for one specific (level, difficulty) combination — a small convenience
     *  over collecting the whole map and indexing by [keyFor] at every call site. */
    fun bestWaveFor(levelId: String, difficulty: CpuDifficulty): Flow<Int?> =
        bestWaves.map { it[keyFor(levelId, difficulty)] }

    /**
     * Records one finished run's [waveReached] for (levelId, difficulty). Returns true if it's a
     * new best for that combination. The before/after comparison happens inside the same
     * `DataStore.edit` transaction that persists the update, so a run ending mid-race with
     * another read/write can't compare against a stale value — same reasoning
     * BreakoutStatsStore.recordScore documents for its own transaction.
     */
    suspend fun recordWave(levelId: String, difficulty: CpuDifficulty, waveReached: Int): Boolean {
        val key = keyFor(levelId, difficulty)
        var isNewBest = false
        context.towerDefenceStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.BEST_WAVES_JSON])
            val existing = current[key]
            isNewBest = existing == null || waveReached > existing
            if (isNewBest) {
                prefs[Keys.BEST_WAVES_JSON] = Json.encodeToString(current + (key to waveReached))
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
