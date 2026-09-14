package com.gamesuite.games.partytoolkit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.partyToolkitDataStore: DataStore<Preferences> by preferencesDataStore(name = "party_toolkit")

/** One Scoreboard entry. [score] can go negative (some party games genuinely do). */
@Serializable
data class ScorePlayer(val name: String, val score: Int = 0)

/** One Life Points entry. */
@Serializable
data class LifePlayer(val name: String, val life: Int)

/**
 * Persistence for the two Party Toolkit tools that are genuine running state
 * across a whole game night rather than a one-shot random pick (Dice/Coin
 * Toss/Random Letter/First Player/Teams need none) — same
 * `preferencesDataStore` + JSON-blob shape every other stats store in this
 * app already uses (e.g. EdgeMatchStatsStore), reused here for player
 * rosters/counters instead of best-score records. Both lists persist across
 * app restarts, same rationale docs/NEW_GAMES_BRAINSTORM.md's own Party
 * Toolkit entry gives: "remembering player names/running totals across a
 * session" — a board-game-night session can span backing out of the app
 * and back in, not just one continuous screen visit.
 *
 * Never read/write DataStore directly from a @Composable — PartyToolkitScreen
 * collects [scoreboard]/[lifePlayers] as state and calls the mutating
 * functions from plain button `onClick`s.
 */
class PartyToolkitStore(private val context: Context) {

    private object Keys {
        val SCOREBOARD_JSON = stringPreferencesKey("scoreboard_json")
        val LIFE_PLAYERS_JSON = stringPreferencesKey("life_players_json")
        val LIFE_STARTING_VALUE = intPreferencesKey("life_starting_value")
    }

    val scoreboard: Flow<List<ScorePlayer>> = context.partyToolkitDataStore.data.map { prefs ->
        decode<List<ScorePlayer>>(prefs[Keys.SCOREBOARD_JSON]) ?: emptyList()
    }

    suspend fun saveScoreboard(players: List<ScorePlayer>) {
        context.partyToolkitDataStore.edit { prefs -> prefs[Keys.SCOREBOARD_JSON] = Json.encodeToString(players) }
    }

    val lifePlayers: Flow<List<LifePlayer>> = context.partyToolkitDataStore.data.map { prefs ->
        decode<List<LifePlayer>>(prefs[Keys.LIFE_PLAYERS_JSON]) ?: emptyList()
    }

    suspend fun saveLifePlayers(players: List<LifePlayer>) {
        context.partyToolkitDataStore.edit { prefs -> prefs[Keys.LIFE_PLAYERS_JSON] = Json.encodeToString(players) }
    }

    /** The starting life total new players are added at, and every player is reset to — defaults to 20 (the most common tabletop starting total) until the player picks a different one. */
    val lifeStartingValue: Flow<Int> = context.partyToolkitDataStore.data.map { prefs ->
        prefs[Keys.LIFE_STARTING_VALUE] ?: DEFAULT_LIFE_STARTING_VALUE
    }

    suspend fun saveLifeStartingValue(value: Int) {
        context.partyToolkitDataStore.edit { prefs -> prefs[Keys.LIFE_STARTING_VALUE] = value }
    }

    private inline fun <reified T> decode(raw: String?): T? {
        if (raw.isNullOrBlank()) return null
        // A corrupted or (hypothetically) future-format blob should never crash app start --
        // same defensive stance every other stats store here takes.
        return runCatching { Json.decodeFromString<T>(raw) }.getOrNull()
    }

    companion object {
        const val DEFAULT_LIFE_STARTING_VALUE = 20
    }
}
