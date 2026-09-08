package com.gamesuite.games.solitaire

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.solitaireMotionPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "solitaire_motion_prefs")

/**
 * Solitaire's own motion-intensity tier from the "premium 2026 vision"
 * pitch's per-game motion-tier finding — a real Standard/Maximum split, not
 * a tier added everywhere by default:
 *  - STANDARD: exactly what this app already shipped before this pass —
 *    [SolitaireGame.autoCompleteStep] driven one move at a time, no camera
 *    hit-stop/shake, no idle waste-card sheen.
 *  - MAXIMUM: unlocks the concurrent-flight auto-complete finale (several
 *    cards flying at once with a mild acceleration curve instead of a
 *    one-at-a-time metronome), a hit-stop + small camera-shake on the final
 *    King landing, and a very subtle sheen crossing the waste card after
 *    ~15s of no input.
 *
 * Everything ELSE this pass added to Solitaire — the haptic vocabulary, the
 * felt table identity (+ its own slow idle-sheen), the per-suit foundation
 * shimmer, the combined-finale shimmer/fan flourish, and the face-card foil
 * sheen — is deliberately NOT gated by this tier. Each of those already has
 * its own individual settings gate (Haptics, 3D Perspective Mode, Enhanced
 * Move Animations, Reduced Motion) and applies whenever that gate allows it,
 * tier aside — see SolitaireScreen.kt's own KDoc for exactly which three
 * things this tier controls and why nothing else needed to.
 */
enum class SolitaireMotionTier { STANDARD, MAXIMUM }

/**
 * Same tiny one-DataStore-per-feature shape as [SolitairePrefsStore] right
 * next to it in this package — kept as its own file/store rather than a
 * second key bolted onto that one, since motion intensity and draw count are
 * genuinely unrelated preferences; see [SolitairePrefsStore]'s own KDoc for
 * why a single-game feature gets its own small store instead of a shared or
 * global one (AppSettings.kt) in the first place — the same reasoning
 * carries over unchanged here.
 *
 * Never read/write DataStore directly from a @Composable — SolitaireScreen
 * collects [motionTier] as state and calls [setMotionTier] from its own
 * Standard/Maximum toggle control.
 */
class SolitaireMotionPrefsStore(private val context: Context) {

    private object Keys {
        val MOTION_TIER = stringPreferencesKey("motion_tier")
    }

    /** Standard (today's exact shipped behavior) until the player opts into Maximum. */
    val motionTier: Flow<SolitaireMotionTier> = context.solitaireMotionPrefsDataStore.data.map { prefs ->
        when (prefs[Keys.MOTION_TIER]) {
            SolitaireMotionTier.MAXIMUM.name -> SolitaireMotionTier.MAXIMUM
            else -> SolitaireMotionTier.STANDARD
        }
    }

    suspend fun setMotionTier(tier: SolitaireMotionTier) {
        context.solitaireMotionPrefsDataStore.edit { prefs -> prefs[Keys.MOTION_TIER] = tier.name }
    }
}
