package com.gamesuite.games.checkers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Checkers' own 3-tier motion-intensity setting (premium-2026-vision pitch:
 * unlike Tic-Tac-Toe's deliberately-left-alone single scale-in, Checkers'
 * gap between plain instant moves and the full sheen + hit-stop + camera-
 * shake treatment is wide enough that a real 3-way split earns its keep;
 * the wood/felt board art itself is drawn at every tier).
 * Composes with, rather than replaces, the existing global gates
 * ([com.gamesuite.settings.LocalCard3DMode]/[com.gamesuite.settings.LocalEnhancedAnimations]) —
 * see CheckersScreen's own `effectiveTier`/`card3D`/`enhanced`/`maximum` for
 * exactly how the two layers combine.
 *
 *  - [OFF]: instant snaps everywhere — mirrors how
 *    [com.gamesuite.settings.LocalReducedMotion] already collapses every
 *    other screen's motion in this project (and IS forced whenever that
 *    system-wide setting is on, regardless of this preference — see
 *    CheckersScreen's `effectiveTier`). No lift/hop, no capture-fade, no
 *    promotion flip, no board sheen shader, no hit-stop/camera-shake.
 *  - [STANDARD]: today's already-shipped motion only — the lift/hop glide,
 *    promotion [com.gamesuite.games.cards.card3DFlip], and per-hop
 *    capture-fade this screen already had before this pass (still gated by
 *    the existing global settings exactly as before). No board sheen shader,
 *    no hit-stop/camera-shake.
 *  - [MAXIMUM]: everything [STANDARD] has, plus the single shared specular
 *    sheen drifting across the wood board and pieces, and capture hit-stop +
 *    camera shake.
 *
 * Haptics are NOT part of this tier: CheckersScreen fires its haptic
 * vocabulary at every tier, gated only by the master haptics setting
 * (`LocalHapticsEnabled` via `rememberHaptics`).
 */
enum class CheckersMotionTier { OFF, STANDARD, MAXIMUM }

private val Context.checkersPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "checkers_prefs")

/**
 * Checkers' own tiny persisted preference: which [CheckersMotionTier] the
 * player wants. Same one-DataStore-per-feature shape as
 * games/solitaire/SolitairePrefsStore.kt (see that file's own KDoc for why
 * this lives here rather than on the shared, app-wide-only AppSettings.kt)
 * — a single-game rule/presentation choice gets a single-game store.
 *
 * Never read/write DataStore directly from a @Composable — CheckersScreen
 * collects [motionTier] as state and calls [setMotionTier] from its own
 * tier-picker control.
 */
class CheckersPrefsStore(private val context: Context) {

    private object Keys {
        val MOTION_TIER = stringPreferencesKey("motion_tier")
    }

    /** Defaults to STANDARD (today's already-shipped motion, unchanged) until the player opts up to MAXIMUM or down to OFF. */
    val motionTier: Flow<CheckersMotionTier> = context.checkersPrefsDataStore.data.map { prefs ->
        prefs[Keys.MOTION_TIER]?.let { name -> runCatching { CheckersMotionTier.valueOf(name) }.getOrNull() }
            ?: CheckersMotionTier.STANDARD
    }

    suspend fun setMotionTier(value: CheckersMotionTier) {
        context.checkersPrefsDataStore.edit { prefs -> prefs[Keys.MOTION_TIER] = value.name }
    }
}
