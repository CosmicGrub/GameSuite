package com.gamesuite.stats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamesuite.core.LocalOutcome
import com.gamesuite.core.MatchDetails
import com.gamesuite.core.epochDayOf
import com.gamesuite.core.gameDataStore
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.statsDataStore: DataStore<Preferences> by com.gamesuite.core.gameDataStore("game_stats")

/**
 * DataStore-backed persistence for per-game [GameStats] — this is the fix for the audited
 * "everything gets computed correctly, once, and thrown away" finding: every GameModule
 * already produces a correct GameResult at match end (see GameSessionManager.endActiveGame),
 * but until this pass nothing kept it past the current session.
 *
 * The whole map is stored as one serialized JSON string under a single key, not one
 * DataStore key per game/field — mirrors the reasoning in SettingsRepository.kt's KDoc
 * (simpler to manage than a proliferating key set) and additionally lets the map's shape
 * evolve (new GameStats fields, new games) without a migration, since kotlinx.serialization
 * just defaults any field missing from old stored JSON.
 *
 * Never read/write DataStore directly from a @Composable — go through [StatsViewModel]'s
 * StateFlow instead.
 */
class StatsRepository(private val context: Context) {

    private object Keys {
        val ALL_STATS_JSON = stringPreferencesKey("all_stats_json")
        val PROGRESS_JSON = stringPreferencesKey("progress_json")
        // First unreadable copy of each blob, preserved so a failed decode never destroys data.
        val ALL_STATS_CORRUPT = stringPreferencesKey("all_stats_json_corrupt")
        val PROGRESS_CORRUPT = stringPreferencesKey("progress_json_corrupt")
    }

    private val safeData: Flow<Preferences> = context.statsDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    /** Preserves any blob that failed to decode before a write replaces it. */
    private fun MutablePreferences.quarantine(stats: Decoded<*>, progress: Decoded<*>) {
        quarantineValue(this[Keys.ALL_STATS_CORRUPT], stats.corruptRaw)?.let { this[Keys.ALL_STATS_CORRUPT] = it }
        quarantineValue(this[Keys.PROGRESS_CORRUPT], progress.corruptRaw)?.let { this[Keys.PROGRESS_CORRUPT] = it }
    }

    val allStats: Flow<Map<String, GameStats>> = safeData.map { prefs ->
        decode(prefs[Keys.ALL_STATS_JSON])
    }

    /** Day streak, win streak and unlocked achievements. */
    val progress: Flow<ProgressState> = safeData.map { prefs ->
        decodeProgress(prefs[Keys.PROGRESS_JSON])
    }

    /** Increments [gameId]'s record by one match with the given [outcome] (null counts the
     *  match played without moving wins/losses/draws — e.g. a spectator). [atEpochMillis] is
     *  passed in rather than read here so this class stays trivially unit-testable. */
    suspend fun recordMatch(
        gameId: String,
        displayName: String,
        outcome: LocalOutcome?,
        atEpochMillis: Long,
        details: MatchDetails? = null,
        today: Long = epochDayOf(atEpochMillis, ZoneId.systemDefault())
    ): List<String> {
        var unlocked: List<String> = emptyList()
        context.statsDataStore.edit { prefs ->
            val statsIn = decodeStatsBlob(prefs[Keys.ALL_STATS_JSON])
            val progressIn = decodeProgressBlob(prefs[Keys.PROGRESS_JSON])
            prefs.quarantine(statsIn, progressIn)
            val recorded = recordMatchInto(
                stats = statsIn.value,
                progress = progressIn.value,
                gameId = gameId,
                displayName = displayName,
                outcome = outcome,
                details = details,
                atEpochMillis = atEpochMillis,
                today = today,
                familyOf = { id -> achievementFamilyOf(id) },
                familyCount = ACHIEVEMENT_FAMILY_COUNT
            )
            prefs[Keys.ALL_STATS_JSON] = JSON.encodeToString(recorded.stats)
            prefs[Keys.PROGRESS_JSON] = JSON.encodeToString(recorded.progress)
            unlocked = recorded.newlyUnlocked
        }
        return unlocked
    }

    /** Marks the "Newly unlocked" achievements as announced (the Home tab shows them once). */
    suspend fun clearPendingAnnouncements() {
        context.statsDataStore.edit { prefs ->
            val progress = decodeProgress(prefs[Keys.PROGRESS_JSON])
            if (progress.pendingAnnounce.isEmpty()) return@edit
            prefs[Keys.PROGRESS_JSON] = JSON.encodeToString(progress.copy(pendingAnnounce = emptyList()))
        }
    }

    /** "Delete" from the Continue row's long-press menu — see
     *  GameStats.dismissedFromContinue's KDoc for why this doesn't touch
     *  wins/losses/matchesPlayed. A no-op if [gameId] has no stats yet
     *  (nothing to dismiss). */
    suspend fun dismissFromContinueRow(gameId: String) {
        context.statsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.ALL_STATS_JSON])
            val existing = current[gameId] ?: return@edit
            prefs[Keys.ALL_STATS_JSON] = JSON.encodeToString(current + (gameId to existing.copy(dismissedFromContinue = true)))
        }
    }

    suspend fun resetAll() = context.statsDataStore.edit { it.clear() }

    private fun decode(raw: String?): Map<String, GameStats> = decodeStatsBlob(raw).value

    private fun decodeProgress(raw: String?): ProgressState = decodeProgressBlob(raw).value

    private companion object {
        val JSON = StatsJson
    }
}
