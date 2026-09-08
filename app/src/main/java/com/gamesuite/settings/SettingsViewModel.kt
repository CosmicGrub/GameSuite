package com.gamesuite.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-scoped settings state. One instance shared across the whole NavHost
 * (see MainActivity), so any screen — the Settings screen itself, or
 * AppTheme at the root — reads the same live StateFlow. Compose screens
 * should collect via collectAsStateWithLifecycle(), never touch
 * SettingsRepository/DataStore directly.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repository.setDynamicColor(enabled) }
    fun setNamedTheme(theme: NamedTheme) = viewModelScope.launch { repository.setNamedTheme(theme) }
    fun setSoundEnabled(enabled: Boolean) = viewModelScope.launch { repository.setSoundEnabled(enabled) }
    fun setHapticsEnabled(enabled: Boolean) = viewModelScope.launch { repository.setHapticsEnabled(enabled) }
    fun setMusicEnabled(enabled: Boolean) = viewModelScope.launch { repository.setMusicEnabled(enabled) }
    fun setTextScale(scale: Float) = viewModelScope.launch { repository.setTextScale(scale) }
    fun setReducedMotion(enabled: Boolean) = viewModelScope.launch { repository.setReducedMotion(enabled) }
    fun setColorblindMode(enabled: Boolean) = viewModelScope.launch { repository.setColorblindMode(enabled) }
    fun setCard3DEnabled(enabled: Boolean) = viewModelScope.launch { repository.setCard3DEnabled(enabled) }
    fun setEnhancedAnimationsEnabled(enabled: Boolean) = viewModelScope.launch { repository.setEnhancedAnimationsEnabled(enabled) }
    fun setDefaultCpuDifficulty(difficulty: CpuDifficulty) =
        viewModelScope.launch { repository.setDefaultCpuDifficulty(difficulty) }
    fun setCardSizePreference(preference: Float) =
        viewModelScope.launch { repository.setCardSizePreference(preference) }
    fun setOnlineServerUrl(url: String) = viewModelScope.launch { repository.setOnlineServerUrl(url) }
    fun setHasSeenOnboarding(seen: Boolean) = viewModelScope.launch { repository.setHasSeenOnboarding(seen) }

    fun resetAll() = viewModelScope.launch { repository.resetAll() }
}
