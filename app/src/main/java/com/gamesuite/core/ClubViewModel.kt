package com.gamesuite.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The one shared Club profile for the whole nav graph (Home card, Hub coach, profile editor).
 * [profile] is null until DataStore has delivered its first value, so a screen can wait for it and
 * never flash a default avatar or auto-open a coach card against an unloaded profile. It is collected
 * for the life of the ViewModel, so navigating between screens never goes back to null.
 */
class ClubViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ClubProfileStore(app)
    private val playerNames = PlayerNameStore(app)

    private val _profile = MutableStateFlow<ClubProfile?>(null)
    val profile: StateFlow<ClubProfile?> = _profile

    init {
        viewModelScope.launch {
            try {
                store.profile.collect { _profile.value = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A corrupt store reads as the default profile rather than leaving the app waiting.
                if (_profile.value == null) _profile.value = ClubProfile()
            }
        }
    }

    /** Ids of the games a favourite may point at. */
    private fun knownGames(): Set<String> = GameHubSpecs.all.map { it.gameId }.toSet()

    /** Persist the edited profile; the Club name is mirrored into the lobby name ([PlayerNameStore]). */
    fun save(edited: ClubProfile) {
        viewModelScope.launch {
            try { store.save(edited, knownGames(), playerNames) } catch (_: Exception) { }
        }
    }

    fun markCoachSeen(gameId: String) {
        // Optimistic, so the same visit cannot reopen it before DataStore round-trips.
        _profile.value = _profile.value?.markCoachSeen(gameId)
        viewModelScope.launch {
            try { store.markCoachSeen(gameId) } catch (_: Exception) { }
        }
    }
}
