package com.gamesuite.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val COLORBLIND_MODE = booleanPreferencesKey("colorblind_mode")
        val DEFAULT_CPU_DIFFICULTY = stringPreferencesKey("default_cpu_difficulty")
        val CARD_SIZE_PREFERENCE = floatPreferencesKey("card_size_preference")
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
            textScale = prefs[Keys.TEXT_SCALE] ?: 1.0f,
            reducedMotion = prefs[Keys.REDUCED_MOTION] ?: false,
            colorblindMode = prefs[Keys.COLORBLIND_MODE] ?: false,
            defaultCpuDifficulty = prefs[Keys.DEFAULT_CPU_DIFFICULTY]
                ?.let { runCatching { CpuDifficulty.valueOf(it) }.getOrNull() }
                ?: CpuDifficulty.MEDIUM,
            cardSizePreference = prefs[Keys.CARD_SIZE_PREFERENCE] ?: 0.4f
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setNamedTheme(theme: NamedTheme) = edit { it[Keys.NAMED_THEME] = theme.name }
    suspend fun setSoundEnabled(enabled: Boolean) = edit { it[Keys.SOUND_ENABLED] = enabled }
    suspend fun setHapticsEnabled(enabled: Boolean) = edit { it[Keys.HAPTICS_ENABLED] = enabled }
    suspend fun setTextScale(scale: Float) = edit { it[Keys.TEXT_SCALE] = scale }
    suspend fun setReducedMotion(enabled: Boolean) = edit { it[Keys.REDUCED_MOTION] = enabled }
    suspend fun setColorblindMode(enabled: Boolean) = edit { it[Keys.COLORBLIND_MODE] = enabled }
    suspend fun setDefaultCpuDifficulty(difficulty: CpuDifficulty) =
        edit { it[Keys.DEFAULT_CPU_DIFFICULTY] = difficulty.name }
    suspend fun setCardSizePreference(preference: Float) =
        edit { it[Keys.CARD_SIZE_PREFERENCE] = preference.coerceIn(0f, 1f) }

    suspend fun resetAll() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
