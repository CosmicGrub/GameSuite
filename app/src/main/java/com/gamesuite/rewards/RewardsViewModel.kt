package com.gamesuite.rewards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamesuite.core.MatchOutcome
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Glue between the shell and [RewardsStore]. [book] is the ACTIVE profile's sticker book (it follows profile
 * switches). [onMatch] is called once per finished match from MainActivity's outcome observer.
 */
class RewardsViewModel(app: Application) : AndroidViewModel(app) {
    val store = RewardsStore(app)

    val book: StateFlow<StickerBook> = store.book.stateIn(viewModelScope, SharingStarted.Eagerly, StickerBook())

    // Identity of the last outcome handled: a recomposition or recreation that re-delivers the same object is a no-op.
    private var lastHandled: Any? = null

    /**
     * Records [outcome] for the active profile when it is a KID profile and no Computer Arena series is running.
     * An adult profile, an arena match, an unscored/aborted match and a repeated delivery do nothing.
     */
    fun onMatch(outcome: MatchOutcome, isKidProfile: Boolean, arenaActive: Boolean) {
        if (!isKidProfile || arenaActive || outcome.result.wasAborted) return
        synchronized(this) {
            if (outcome === lastHandled) return
            lastHandled = outcome
        }
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            store.record(
                RewardEvent(outcome.gameId, outcome.localOutcome, now, eventId = "${outcome.gameId}@$now"),
                isKidProfile = true,
                arena = false
            )
        }
    }

    fun collect(stickerId: String) {
        viewModelScope.launch { store.collect(stickerId) }
    }
}
