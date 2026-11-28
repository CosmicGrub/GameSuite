package com.gamesuite.games.towerdefence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.towerDefenceStatsDataStore: DataStore<Preferences> by com.gamesuite.core.gameDataStore("tower_defence_stats")

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

    private fun keyFor(levelId: String, difficulty: CpuDifficulty, gentle: Boolean = false): String =
        TdStatsKeys.key(levelId, difficulty, gentle)

    /** All (level, difficulty) best-wave entries, keyed by [keyFor]; a combination with no entry
     *  has never been played to a run-over. */
    val bestWaves: Flow<Map<String, Int>> = context.towerDefenceStatsDataStore.data.map { prefs ->
        decode(prefs[Keys.BEST_WAVES_JSON])
    }

    /** Reads [bestWaves] for one specific (level, difficulty) combination — a small convenience
     *  over collecting the whole map and indexing by [keyFor] at every call site. */
    fun bestWaveFor(levelId: String, difficulty: CpuDifficulty, gentle: Boolean = false): Flow<Int?> =
        bestWaves.map { it[keyFor(levelId, difficulty, gentle)] }

    /**
     * Records one finished run's [waveReached] for (levelId, difficulty). Returns true if it's a
     * new best for that combination. The before/after comparison happens inside the same
     * `DataStore.edit` transaction that persists the update, so a run ending mid-race with
     * another read/write can't compare against a stale value — same reasoning
     * BreakoutStatsStore.recordScore documents for its own transaction.
     */
    suspend fun recordWave(levelId: String, difficulty: CpuDifficulty, waveReached: Int, gentle: Boolean = false): Boolean =
        recordBest(keyFor(levelId, difficulty, gentle), waveReached)

    /** Records [value] under any [TdStatsKeys] key (Endless wave, Daily score); true if it beats the stored best. */
    suspend fun recordBest(key: String, waveReached: Int): Boolean {
        var isNewBest = false
        context.towerDefenceStatsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.BEST_WAVES_JSON])
            val existing = current[key]
            isNewBest = existing == null || waveReached > existing
            if (isNewBest) {
                prefs[Keys.BEST_WAVES_JSON] = Json.encodeToString(TdStatsKeys.pruneDaily(current + (key to waveReached), com.gamesuite.core.DailySeed.todayEpochDay()))
            }
        }
        return isNewBest
    }

    private fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or (hypothetically) future-format blob should never crash app start --
        // same defensive stance every other stats store here takes.
        return TdStatsKeys.sanitize(runCatching { Json.decodeFromString<Map<String, Int>>(raw) }.getOrDefault(emptyMap()))
    }
}

/**
 * The stats key format, unchanged since the three Classic maps shipped: "$mapId:DIFFICULTY". Open maps
 * follow the same format, and the Gentle tier files under "$mapId:GENTLE". The theme is deliberately
 * NOT part of the key (a map's best wave is the same whichever skin is shown). [sanitize] drops
 * malformed or impossible entries from a stored blob and keeps everything else, so an old install's
 * records survive the upgrade untouched.
 */
object TdStatsKeys {
    private val KEY = Regex("^[a-z0-9][a-z0-9-]*:(EASY|MEDIUM|HARD|GENTLE)$")
    private val ENDLESS_KEY = Regex("^[a-z0-9][a-z0-9-]*:ENDLESS$")
    private val DAILY_KEY = Regex("^daily-[0-9]{1,7}:DAILY$")

    fun key(levelId: String, difficulty: CpuDifficulty, gentle: Boolean = false): String =
        if (gentle) "$levelId:GENTLE" else "$levelId:${difficulty.name}"

    /** Endless best (the wave reached) per map, whatever the tier. */
    fun endlessKey(levelId: String): String = "$levelId:ENDLESS"

    /** One day's Daily best score ([TdDaily.score]). */
    fun dailyKey(epochDay: Long): String = "${TdDaily.statsId(epochDay)}:DAILY"

    /** The day number inside a [dailyKey], or null for any other key. */
    fun dailyDayOf(key: String): Long? = if (DAILY_KEY.matches(key)) key.removePrefix("daily-").substringBefore(':').toLongOrNull() else null

    /** The key a finished run files under, by mode. */
    fun keyFor(mode: TdMode, levelId: String, difficulty: CpuDifficulty, gentle: Boolean, dailyDay: Long): String = when (mode) {
        TdMode.CAMPAIGN -> key(levelId, difficulty, gentle)
        TdMode.ENDLESS -> endlessKey(levelId)
        TdMode.DAILY -> dailyKey(dailyDay)
    }

    fun sanitize(raw: Map<String, Int>): Map<String, Int> =
        raw.filter { (k, v) ->
            when {
                KEY.matches(k) -> v in 1..TowerDefenceGame.TOTAL_WAVES
                ENDLESS_KEY.matches(k) -> v in 1..TdSnapshot.MAX_ENDLESS_WAVE
                DAILY_KEY.matches(k) -> v in 100..(TowerDefenceGame.TOTAL_WAVES * 100 + 99)
                else -> false
            }
        }

    /** Drops Daily entries more than [keepDays] before [today], so the blob never grows without bound. */
    fun pruneDaily(raw: Map<String, Int>, today: Long, keepDays: Int = 30): Map<String, Int> =
        raw.filter { (k, _) -> dailyDayOf(k)?.let { it >= today - keepDays } ?: true }
}
