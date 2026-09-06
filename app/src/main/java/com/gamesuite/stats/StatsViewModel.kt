package com.gamesuite.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamesuite.core.MatchOutcome
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-scoped match-history state, mirroring SettingsViewModel's shape exactly (one instance
 * shared across the whole NavHost — see MainActivity — backed by a repository, exposed as a
 * StateFlow; Compose screens collect via collectAsStateWithLifecycle(), never touch
 * StatsRepository/DataStore directly).
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StatsRepository(application)

    val allStats: StateFlow<Map<String, GameStats>> = repository.allStats.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap()
    )

    /** Call once per [MatchOutcome] emitted by GameSessionManager.lastMatchOutcome — see
     *  MainActivity's root LaunchedEffect for the hand-off. */
    fun recordMatch(outcome: MatchOutcome) = viewModelScope.launch {
        repository.recordMatch(
            gameId = outcome.gameId,
            displayName = outcome.gameDisplayName,
            outcome = outcome.localOutcome,
            atEpochMillis = System.currentTimeMillis()
        )
    }

    fun resetAll() = viewModelScope.launch { repository.resetAll() }
}
