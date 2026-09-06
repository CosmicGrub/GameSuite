package com.gamesuite.stats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gamesuite.core.LocalOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.statsDataStore: DataStore<Preferences> by preferencesDataStore(name = "game_stats")

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
    }

    val allStats: Flow<Map<String, GameStats>> = context.statsDataStore.data.map { prefs ->
        decode(prefs[Keys.ALL_STATS_JSON])
    }

    /** Increments [gameId]'s record by one match with the given [outcome] (null counts the
     *  match played without moving wins/losses/draws — e.g. a spectator). [atEpochMillis] is
     *  passed in rather than read here so this class stays trivially unit-testable. */
    suspend fun recordMatch(gameId: String, displayName: String, outcome: LocalOutcome?, atEpochMillis: Long) {
        context.statsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.ALL_STATS_JSON])
            val existing = current[gameId] ?: GameStats(gameId = gameId, displayName = displayName)
            val updated = existing.copy(
                displayName = displayName,
                matchesPlayed = existing.matchesPlayed + 1,
                wins = existing.wins + if (outcome == LocalOutcome.WIN) 1 else 0,
                losses = existing.losses + if (outcome == LocalOutcome.LOSS) 1 else 0,
                draws = existing.draws + if (outcome == LocalOutcome.DRAW) 1 else 0,
                lastPlayedEpochMillis = atEpochMillis
            )
            prefs[Keys.ALL_STATS_JSON] = Json.encodeToString(current + (gameId to updated))
        }
    }

    suspend fun resetAll() = context.statsDataStore.edit { it.clear() }

    private fun decode(raw: String?): Map<String, GameStats> {
        if (raw.isNullOrBlank()) return emptyMap()
        // A corrupted or (hypothetically) future-format blob should never crash app start —
        // same defensive stance SettingsRepository takes on a bad enum value.
        return runCatching { Json.decodeFromString<Map<String, GameStats>>(raw) }.getOrDefault(emptyMap())
    }
}
