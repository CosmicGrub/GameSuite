package com.gamesuite.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamesuite.core.MatchDetails
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

    /** Day streak, win streak and achievements. */
    val progress: StateFlow<ProgressState> = repository.progress.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProgressState()
    )

    /** Call once per [MatchOutcome] emitted by GameSessionManager.lastMatchOutcome — see
     *  MainActivity's root LaunchedEffect for the hand-off. [details] (mode, difficulty, daily)
     *  feeds the achievements; null is fine and just skips those. */
    fun recordMatch(outcome: MatchOutcome, details: MatchDetails? = null) = viewModelScope.launch {
        repository.recordMatch(
            gameId = outcome.gameId,
            displayName = outcome.gameDisplayName,
            outcome = outcome.localOutcome,
            atEpochMillis = System.currentTimeMillis(),
            details = details
        )
    }

    /** The Home tab announced the "Newly unlocked" achievements; do not show them again. */
    fun clearPendingAnnouncements() = viewModelScope.launch { repository.clearPendingAnnouncements() }

    /** "Delete" from the Continue row's long-press menu — see
     *  StatsRepository.dismissFromContinueRow's KDoc. */
    fun dismissFromContinueRow(gameId: String) = viewModelScope.launch { repository.dismissFromContinueRow(gameId) }

    fun resetAll() = viewModelScope.launch { repository.resetAll() }
}
