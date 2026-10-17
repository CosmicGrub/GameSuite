package com.gamesuite.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * DataStore-backed persistence for [AppSettings]. One flat Preferences file
 * for the whole app (not one file per game) — per
 * docs/SETTINGS_THEMING_ACCESSIBILITY.md §5, a single file with prefixed
 * keys is simpler to manage than many for a suite this size; per-game
 * settings added later (e.g. `uno_stacking_enabled`) belong in this same
 * store with their own key prefix, not a new DataStore instance.
 *
 * Never read/write DataStore directly from a @Composable — go through
 * [SettingsViewModel]'s StateFlow instead.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val NAMED_THEME = stringPreferencesKey("named_theme")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val MUSIC_ENABLED = booleanPreferencesKey("music_enabled")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val COLORBLIND_MODE = booleanPreferencesKey("colorblind_mode")
        val CARD_3D_ENABLED = booleanPreferencesKey("card_3d_enabled")
        val ENHANCED_ANIMATIONS_ENABLED = booleanPreferencesKey("enhanced_animations_enabled")
        val DEFAULT_CPU_DIFFICULTY = stringPreferencesKey("default_cpu_difficulty")
        val CARD_SIZE_PREFERENCE = floatPreferencesKey("card_size_preference")
        val GAME_SPEED = stringPreferencesKey("game_speed")
        val ONLINE_SERVER_URL = stringPreferencesKey("online_server_url")
        val HAS_SEEN_ONBOARDING = booleanPreferencesKey("has_seen_onboarding")
        val MUSIC_SOURCE = stringPreferencesKey("music_source")
        val MUSIC_VOLUME = floatPreferencesKey("music_volume")
        val MUSIC_SHUFFLE = booleanPreferencesKey("music_shuffle")
        val MUSIC_CROSSFADE_SECONDS = intPreferencesKey("music_crossfade_seconds")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            namedTheme = prefs[Keys.NAMED_THEME]?.let { runCatching { NamedTheme.valueOf(it) }.getOrNull() }
                ?: NamedTheme.CLASSIC,
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
            musicEnabled = prefs[Keys.MUSIC_ENABLED] ?: true,
            textScale = prefs[Keys.TEXT_SCALE] ?: 1.0f,
            reducedMotion = prefs[Keys.REDUCED_MOTION] ?: false,
            colorblindMode = prefs[Keys.COLORBLIND_MODE] ?: false,
            card3DEnabled = prefs[Keys.CARD_3D_ENABLED] ?: false,
            enhancedAnimationsEnabled = prefs[Keys.ENHANCED_ANIMATIONS_ENABLED] ?: true,
            defaultCpuDifficulty = prefs[Keys.DEFAULT_CPU_DIFFICULTY]
                ?.let { runCatching { CpuDifficulty.valueOf(it) }.getOrNull() }
                ?: CpuDifficulty.MEDIUM,
            cardSizePreference = prefs[Keys.CARD_SIZE_PREFERENCE] ?: 0.4f,
            gameSpeed = GameSpeed.fromName(prefs[Keys.GAME_SPEED]),
            onlineServerUrl = prefs[Keys.ONLINE_SERVER_URL] ?: "",
            hasSeenOnboarding = prefs[Keys.HAS_SEEN_ONBOARDING] ?: false,
            // The source is the single truth for music. Before it existed there was only the on/off
            // switch, so a stored "off" migrates to OFF (not to a silent "Built-in ambience").
            musicSource = prefs[Keys.MUSIC_SOURCE]?.let { runCatching { MusicSource.valueOf(it) }.getOrNull() }
                ?: if (prefs[Keys.MUSIC_ENABLED] == false) MusicSource.OFF else MusicSource.AMBIENCE,
            musicVolume = (prefs[Keys.MUSIC_VOLUME] ?: 0.6f).coerceIn(0f, 1f),
            musicShuffle = prefs[Keys.MUSIC_SHUFFLE] ?: true,
            musicCrossfadeSeconds = (prefs[Keys.MUSIC_CROSSFADE_SECONDS] ?: 3).coerceIn(0, 8)
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setNamedTheme(theme: NamedTheme) = edit { it[Keys.NAMED_THEME] = theme.name }
    suspend fun setSoundEnabled(enabled: Boolean) = edit { it[Keys.SOUND_ENABLED] = enabled }
    suspend fun setHapticsEnabled(enabled: Boolean) = edit { it[Keys.HAPTICS_ENABLED] = enabled }
    suspend fun setMusicEnabled(enabled: Boolean) = edit { it[Keys.MUSIC_ENABLED] = enabled }
    suspend fun setTextScale(scale: Float) = edit { it[Keys.TEXT_SCALE] = scale }
    suspend fun setReducedMotion(enabled: Boolean) = edit { it[Keys.REDUCED_MOTION] = enabled }
    suspend fun setColorblindMode(enabled: Boolean) = edit { it[Keys.COLORBLIND_MODE] = enabled }
    suspend fun setCard3DEnabled(enabled: Boolean) = edit { it[Keys.CARD_3D_ENABLED] = enabled }
    suspend fun setEnhancedAnimationsEnabled(enabled: Boolean) = edit { it[Keys.ENHANCED_ANIMATIONS_ENABLED] = enabled }
    suspend fun setDefaultCpuDifficulty(difficulty: CpuDifficulty) =
        edit { it[Keys.DEFAULT_CPU_DIFFICULTY] = difficulty.name }
    suspend fun setGameSpeed(speed: GameSpeed) = edit { it[Keys.GAME_SPEED] = speed.name }
    suspend fun setCardSizePreference(preference: Float) =
        edit { it[Keys.CARD_SIZE_PREFERENCE] = preference.coerceIn(0f, 1f) }
    suspend fun setOnlineServerUrl(url: String) = edit { it[Keys.ONLINE_SERVER_URL] = url.trim() }
    suspend fun setHasSeenOnboarding(seen: Boolean) = edit { it[Keys.HAS_SEEN_ONBOARDING] = seen }

    suspend fun setMusicSource(source: MusicSource) = edit { it[Keys.MUSIC_SOURCE] = source.name }
    suspend fun setMusicVolume(volume: Float) = edit { it[Keys.MUSIC_VOLUME] = volume.coerceIn(0f, 1f) }
    suspend fun setMusicShuffle(enabled: Boolean) = edit { it[Keys.MUSIC_SHUFFLE] = enabled }
    suspend fun setMusicCrossfadeSeconds(seconds: Int) = edit { it[Keys.MUSIC_CROSSFADE_SECONDS] = seconds.coerceIn(0, 8) }

    suspend fun resetAll() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
