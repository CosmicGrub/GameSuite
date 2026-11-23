package com.gamesuite.style

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The three user axes plus per-game art overrides. Defaults: 2.5D art, Standard motion, Auto quality. */
data class StylePrefs(
    val art: ArtTier = StyleLimits.DEFAULT_ART,
    val motion: MotionLevel = StyleLimits.DEFAULT_MOTION,
    val quality: QualityLevel = StyleLimits.DEFAULT_QUALITY,
    val overrides: Map<String, ArtTier> = emptyMap()
) {
    fun artFor(gameId: String): ArtTier = overrides[gameId] ?: art
}

/**
 * Persists [StylePrefs] in a Preferences DataStore. The app creates this one with `gameDataStore("style_prefs")`,
 * which is PER PROFILE (it is not in ProfileScope.deviceLevelStores), so every adult and kid profile keeps its own look.
 * Unknown or removed values fall back to the defaults silently.
 */
class StylePrefsStore(private val store: DataStore<Preferences>) {
    private object Keys {
        val ART = stringPreferencesKey("style_art")
        val MOTION = stringPreferencesKey("style_motion")
        val QUALITY = stringPreferencesKey("style_quality")
        const val OVERRIDE_PREFIX = "style_override_"
    }

    val prefs: Flow<StylePrefs> = store.data.map { p ->
        val overrides = HashMap<String, ArtTier>()
        for ((k, v) in p.asMap()) {
            if (k.name.startsWith(Keys.OVERRIDE_PREFIX) && v is String) {
                parse<ArtTier>(v)?.let { overrides[k.name.removePrefix(Keys.OVERRIDE_PREFIX)] = it }
            }
        }
        StylePrefs(
            art = p[Keys.ART]?.let { parse<ArtTier>(it) } ?: StyleLimits.DEFAULT_ART,
            motion = p[Keys.MOTION]?.let { parse<MotionLevel>(it) } ?: StyleLimits.DEFAULT_MOTION,
            quality = p[Keys.QUALITY]?.let { parse<QualityLevel>(it) } ?: StyleLimits.DEFAULT_QUALITY,
            overrides = overrides
        )
    }

    suspend fun setArt(t: ArtTier) { store.edit { it[Keys.ART] = t.name } }
    suspend fun setMotion(m: MotionLevel) { store.edit { it[Keys.MOTION] = m.name } }
    suspend fun setQuality(q: QualityLevel) { store.edit { it[Keys.QUALITY] = q.name } }
    /** null clears the override (the game follows the global choice again). */
    suspend fun setOverride(gameId: String, t: ArtTier?) {
        val key = stringPreferencesKey(Keys.OVERRIDE_PREFIX + gameId)
        store.edit { if (t == null) it.remove(key) else it[key] = t.name }
    }
    suspend fun reset() { store.edit { it.clear() } }

    private inline fun <reified E : Enum<E>> parse(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
