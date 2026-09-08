package com.gamesuite.games.mancala

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mancalaMotionDataStore: DataStore<Preferences> by preferencesDataStore(name = "mancala_motion_prefs")

/**
 * Mancala's own motion-intensity tier (premium 2026 vision pitch, Mancala section) --
 * a real 3-tier-style split is specifically warranted here because the real seed-pile
 * physics jostle (see MancalaScreen's `MancalaSeedPhysics`) is visually busier than
 * anything else in this game, so it's opt-in rather than always-on the way the
 * capture sweep/arc-cascade already is.
 *
 * [STANDARD] keeps exactly today's shipped look: the arc-hop cascade + capture sweep,
 * with pits/stores rendered as a plain count (no per-seed rendering, no jostle, no
 * camera shake). [MAXIMUM] additionally spawns a real per-seed physics body the
 * instant each seed's hop lands (gravity + circle-circle collision resolution against
 * every seed already resting in that pit/store) and adds a camera-shake on a big
 * capture (>= 6 stones swept). Both tiers still respect Settings -> Display ->
 * "Enhanced move animations" and Reduced Motion the same way every other motion in
 * this screen does -- MAXIMUM never overrides either.
 */
enum class MancalaMotionTier { STANDARD, MAXIMUM }

/**
 * Mancala's own tiny persisted preference for [MancalaMotionTier] -- mirrors
 * `games/solitaire/SolitairePrefsStore.kt`'s draw-1/draw-3 store exactly (a single
 * dedicated DataStore per game-specific preference, not a new global AppSettings
 * field): this is a single game's own rendering-intensity choice, not an app-wide
 * setting, so it lives in this game's own package with its own tiny store.
 *
 * Never read/write DataStore directly from a @Composable -- MancalaScreen collects
 * [tier] as state and calls [setTier] from its Standard/Maximum toggle control.
 */
class MancalaMotionPrefs(private val context: Context) {

    private object Keys {
        val TIER = stringPreferencesKey("motion_tier")
    }

    /** [MancalaMotionTier.STANDARD] (today's shipped behavior) until the player opts into Maximum. */
    val tier: Flow<MancalaMotionTier> = context.mancalaMotionDataStore.data.map { prefs ->
        when (prefs[Keys.TIER]) {
            MancalaMotionTier.MAXIMUM.name -> MancalaMotionTier.MAXIMUM
            else -> MancalaMotionTier.STANDARD
        }
    }

    suspend fun setTier(value: MancalaMotionTier) {
        context.mancalaMotionDataStore.edit { prefs -> prefs[Keys.TIER] = value.name }
    }
}
