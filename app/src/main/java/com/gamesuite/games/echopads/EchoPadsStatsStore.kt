package com.gamesuite.games.echopads

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.echoPadsStatsDataStore: DataStore<Preferences> by com.gamesuite.core.gameDataStore("echo_pads_stats")

/** The pure parts of the best-score store, so they are unit tested without Android. */
object EchoPadsBest {
    /** The record a solo run counts toward: its own per speed, and Easy (forgiving) runs apart from the rest. */
    fun key(speed: EchoSpeed, forgiving: Boolean): String = speed.id + if (forgiving) "+easy" else ""

    private val serializer = MapSerializer(String.serializer(), Int.serializer())

    /** A corrupt or foreign blob is an empty table, never a crash. Negative or absurd values are dropped. */
    fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
            .filterValues { it in 0..EchoRules.MAX_LENGTH }
    }

    fun encode(map: Map<String, Int>): String = Json.encodeToString(serializer, map)

    /** The table after a run of [score]; [improved] says whether it beat the old record. */
    fun record(current: Map<String, Int>, key: String, score: Int): Pair<Map<String, Int>, Boolean> {
        val s = score.coerceIn(0, EchoRules.MAX_LENGTH)
        val old = current[key] ?: 0
        return if (s > old) (current + (key to s)) to true else current to false
    }
}

/**
 * The solo personal best of Echo Pads (the longest pattern copied), kept per speed and per Easy / standard.
 * Never read or write DataStore from a composable: the screen collects [bests] as state and calls [recordScore]
 * from an effect when a solo run ends.
 */
class EchoPadsStatsStore(private val context: Context) {
    private object Keys {
        val BESTS_JSON = stringPreferencesKey("bests_json")
    }

    val bests: Flow<Map<String, Int>> = context.echoPadsStatsDataStore.data.map { EchoPadsBest.decode(it[Keys.BESTS_JSON]) }

    /** Records a finished solo run; true when it is a new personal best for that speed. */
    suspend fun recordScore(speed: EchoSpeed, forgiving: Boolean, score: Int): Boolean {
        var improved = false
        context.echoPadsStatsDataStore.edit { prefs ->
            val (updated, better) = EchoPadsBest.record(EchoPadsBest.decode(prefs[Keys.BESTS_JSON]), EchoPadsBest.key(speed, forgiving), score)
            improved = better
            if (better) prefs[Keys.BESTS_JSON] = EchoPadsBest.encode(updated)
        }
        return improved
    }
}
