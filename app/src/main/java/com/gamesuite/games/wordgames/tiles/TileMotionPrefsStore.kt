package com.gamesuite.games.wordgames.tiles

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tileMotionPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tile_motion_prefs")

/**
 * Word Tiles' own motion-intensity tier from the "premium 2026 vision"
 * pitch's per-game motion-tier finding — a real Standard/Maximum split, not
 * a tier added everywhere by default:
 *  - STANDARD: today's fixed 140ms tween settle on a placed tile, no camera
 *    micro-punch on a Bingo.
 *  - MAXIMUM: swaps that fixed tween for a real velocity-based spring settle
 *    (see TileGameScreen.kt's drag handling — a flicked tile visibly
 *    overshoots further than a gently-dropped one) and adds a small camera
 *    micro-punch on a Bingo play.
 *
 * Everything ELSE this pass added to Word Tiles — the haptic vocabulary, the
 * wood-board table identity, the tile gloss/emboss sheen, the
 * word-completion flourish (sparkle/pulse/banner escalation), and the idle
 * Submit-button pulse — is deliberately NOT gated by this tier. Each already
 * has its own individual settings gate (Haptics, 3D Perspective Mode,
 * Enhanced Move Animations, Reduced Motion) and applies whenever that gate
 * allows it, tier aside.
 */
enum class TileMotionTier { STANDARD, MAXIMUM }

/**
 * Same tiny one-DataStore-per-feature shape as
 * `games/solitaire/SolitairePrefsStore.kt` (and its own
 * `SolitaireMotionPrefsStore.kt` sibling) — a small, single-game, own-package
 * store rather than a new global AppSettings field, per this pass's shared
 * convention for a per-game motion-intensity preference.
 *
 * Never read/write DataStore directly from a @Composable — TileGameScreen
 * collects [motionTier] as state and calls [setMotionTier] from its own
 * Standard/Maximum toggle control.
 */
class TileMotionPrefsStore(private val context: Context) {

    private object Keys {
        val MOTION_TIER = stringPreferencesKey("motion_tier")
    }

    /** Standard (today's exact shipped fixed-tween settle) until the player opts into Maximum. */
    val motionTier: Flow<TileMotionTier> = context.tileMotionPrefsDataStore.data.map { prefs ->
        when (prefs[Keys.MOTION_TIER]) {
            TileMotionTier.MAXIMUM.name -> TileMotionTier.MAXIMUM
            else -> TileMotionTier.STANDARD
        }
    }

    suspend fun setMotionTier(tier: TileMotionTier) {
        context.tileMotionPrefsDataStore.edit { prefs -> prefs[Keys.MOTION_TIER] = tier.name }
    }
}
