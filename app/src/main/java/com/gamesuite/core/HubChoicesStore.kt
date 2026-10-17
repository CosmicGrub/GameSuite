package com.gamesuite.core

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A tiny string key-value seam so the Hub's remembered choices can be unit tested without Android. */
interface KeyValues {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class PrefsKeyValues(context: Context) : KeyValues {
    private val prefs = context.applicationContext.getSharedPreferences("game_hub", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

/**
 * Remembers, per game, which mode the Play button should repeat and each mode's last options. The
 * next visit to a game's Hub opens on them, so "Play" is a one-tap rematch of the last setup.
 */
class HubChoicesStore(private val kv: KeyValues) {
    constructor(context: Context) : this(PrefsKeyValues(context))

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun lastModeId(spec: GameHubSpec): String {
        val stored = kv.get("last.${spec.gameId}")
        return if (stored != null && spec.mode(stored) != null) stored else spec.defaultModeId
    }

    fun setLastMode(gameId: String, modeId: String) = kv.put("last.$gameId", modeId)

    /** The saved options for [mode], resolved (stale or missing values fall back to defaults). Never throws. */
    fun load(gameId: String, mode: HubMode): HubChoices {
        val raw = kv.get("choices.$gameId.${mode.id}") ?: return mode.defaults()
        val map = try {
            json.decodeFromString(mapSerializer, raw)
        } catch (_: Exception) {
            return mode.defaults()
        }
        return mode.resolve(HubChoices(map))
    }

    fun save(gameId: String, modeId: String, choices: HubChoices) {
        kv.put("choices.$gameId.$modeId", json.encodeToString(mapSerializer, choices.asMap()))
    }
}
