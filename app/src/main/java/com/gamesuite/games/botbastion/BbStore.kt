package com.gamesuite.games.botbastion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.botBastionDataStore: DataStore<Preferences> by com.gamesuite.core.gameDataStore("bot_bastion")

/** Where the [BbProfile] lives on the device. Never touched from composition: collect [profile], call [update] from an effect. */
class BbStore(private val context: Context) {
    private val key = stringPreferencesKey("profile_json")

    val profile: Flow<BbProfile> = context.botBastionDataStore.data.map { BbProfile.decode(it[key]) }

    suspend fun update(change: (BbProfile) -> BbProfile) {
        context.botBastionDataStore.edit { prefs ->
            prefs[key] = change(BbProfile.decode(prefs[key])).encode()
        }
    }
}

/** Loads the bundled maps (assets/bot_bastion/maps.json) and keeps only the playable ones. */
object BbMapStore {
    @Volatile
    private var cached: List<MapDef>? = null

    fun load(context: Context): List<MapDef> {
        cached?.let { return it }
        val maps = try {
            BbMaps.playable(context.applicationContext.assets.open(BbMaps.ASSET).use { BbMaps.parse(it.readBytes().toString(Charsets.UTF_8)) })
        } catch (_: Exception) {
            emptyList()
        }
        cached = maps
        return maps
    }
}
