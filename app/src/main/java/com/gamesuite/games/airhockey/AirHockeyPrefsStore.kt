package com.gamesuite.games.airhockey

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.airHockeyPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "air_hockey_prefs")

/**
 * Air Hockey's own tiny persisted preference: [AirHockeyGame.AirHockeyMotionTier]
 * (Standard/Maximum juice) — see that enum's KDoc for what each tier actually changes and why
 * this game specifically earns a real tier split where most other games in this "premium 2026
 * vision" pass deliberately did not get one (Air Hockey can stack more independent compounding
 * effects — trail length, camera-shake, particle burst — than most games in the suite).
 *
 * Deliberately its own DataStore rather than a new AppSettings field, same "one tiny dedicated
 * store per single-game feature" shape as `games/solitaire/SolitairePrefsStore.kt` (draw-1/3)
 * and `games/slidingpuzzle/SlidingPuzzleStatsStore.kt` — see either file's own KDoc for why
 * coupling a single game's own preference onto a shared/global store is the wrong shape.
 *
 * Never read/write DataStore directly from a @Composable — AirHockeyScreen collects
 * [motionTier] as state and calls [setMotionTier] from its Standard/Maximum control.
 */
class AirHockeyPrefsStore(private val context: Context) {

    private object Keys {
        val MOTION_TIER = stringPreferencesKey("motion_tier")
    }

    /** [AirHockeyGame.AirHockeyMotionTier.STANDARD] until the player opts into Maximum. */
    val motionTier: Flow<AirHockeyGame.AirHockeyMotionTier> = context.airHockeyPrefsDataStore.data.map { prefs ->
        // A corrupted or (hypothetically) future-format value should never crash app start —
        // same defensive stance StatsRepository/SettingsRepository take on bad stored data.
        runCatching {
            AirHockeyGame.AirHockeyMotionTier.valueOf(prefs[Keys.MOTION_TIER] ?: "")
        }.getOrDefault(AirHockeyGame.AirHockeyMotionTier.STANDARD)
    }

    suspend fun setMotionTier(value: AirHockeyGame.AirHockeyMotionTier) {
        context.airHockeyPrefsDataStore.edit { prefs -> prefs[Keys.MOTION_TIER] = value.name }
    }
}
