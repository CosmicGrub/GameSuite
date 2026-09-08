package com.gamesuite.games.solitaire

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.solitairePrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "solitaire_prefs")

/**
 * Solitaire's own tiny persisted preference: draw-1 vs draw-3 (see
 * [SolitaireGame.drawThree]'s KDoc for why this is deliberately its own
 * store rather than a field on AppSettings.kt — that file is documented
 * app-wide-only, and draw count is a single game's own rule choice).
 *
 * Same one-DataStore-per-feature shape as
 * `games/slidingpuzzle/SlidingPuzzleStatsStore.kt` — that file's own KDoc
 * makes the same "don't couple a single-game feature onto a shared store"
 * case for stats, and the reasoning carries over identically to settings.
 * A single boolean doesn't need that file's JSON-blob-of-records
 * complexity, just one preference key.
 *
 * Never read/write DataStore directly from a @Composable — SolitaireScreen
 * collects [drawThree] as state and calls [setDrawThree] from its draw-count
 * toggle control.
 */
class SolitairePrefsStore(private val context: Context) {

    private object Keys {
        val DRAW_THREE = booleanPreferencesKey("draw_three")
    }

    /** False (draw-1, the classic default) until the player opts into draw-3. */
    val drawThree: Flow<Boolean> = context.solitairePrefsDataStore.data.map { prefs -> prefs[Keys.DRAW_THREE] ?: false }

    suspend fun setDrawThree(value: Boolean) {
        context.solitairePrefsDataStore.edit { prefs -> prefs[Keys.DRAW_THREE] = value }
    }
}
