package com.gamesuite.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.gamesuite.settings.LocalHapticsEnabled
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The "premium 2026 vision" pitch's single most-repeated finding: nearly
 * every game routes every distinct event through one or two generic Compose
 * `HapticFeedbackType` constants (LongPress/TextHandleMove), or has no
 * haptics at all. `HapticFeedbackType` has no way to express "this should
 * feel different from that" — it's not a richness gap in how call sites use
 * it, it's a ceiling in the API itself. This file replaces those call sites
 * with real, distinct waveforms via the raw `Vibrator` service.
 *
 * Every [HapticSignal] here maps to a `VibrationEffect.createWaveform` (or
 * `createOneShot`) pattern — both are available unconditionally at this
 * project's actual minSdk 26, so nothing in this file needs an SDK gate.
 * (`VibrationEffect.compose()`/`createPredefined()`, API 30+/29+, would be a
 * real enhancement but are deliberately left out of this first pass — the
 * waveform-based approach already delivers the distinct-feel goal, and
 * every extra gate is another thing that can silently do nothing on an
 * unsupported device.)
 *
 * A small, deliberately generic vocabulary — not one enum value per
 * game-specific event name. Every game maps its own moments onto these
 * ten buckets (7 core + ROLL, LANDING, RIPPLE_STEP); see each screen's own call sites for which bucket a given
 * moment landed in and why.
 */
enum class HapticSignal {
    /** A selection, a hover-into-legal-target, or any other light
     *  acknowledgment that something registered but nothing consequential
     *  happened yet. */
    LIGHT_TICK,
    /** An ordinary, low-stakes action actually completing — a quiet move, a
     *  card played, a tile placed. */
    NORMAL_ACTION,
    /** A significant action — a capture, a big play, a scored point. */
    STRONG_ACTION,
    /** Rising tension short of the outcome itself — a check, a hand down to
     *  one card, a puzzle nearly solved. */
    ESCALATING,
    /** A positive outcome — a correct guess, a solve, a round won. */
    SUCCESS,
    /** A negative outcome or a rejected input — a wrong guess, an illegal
     *  drop, a lost round. */
    FAILURE,
    /** The single biggest moment a game has — checkmate, a match win, a
     *  full-grid solve. Reserved for genuinely rare events; using this for
     *  anything routine defeats the whole point of having it. */
    CELEBRATION,

    /** A short dice-rattle burst: the moment dice are thrown (Backgammon's roll, a party-toolkit
     *  die). Intended to be followed by a LIGHT_TICK when the dice settle. */
    ROLL,

    /** A soft thud for a piece or disc LANDING on the board (a Connect Four disc reaching its
     *  slot). Softer and a touch longer than NORMAL_ACTION: weight, not a click. */
    LANDING,

    /** The faintest tick, one per step of a CASCADE (each flipped Reversi disc). Meant to be
     *  chained 40-60 ms apart; on its own it is barely perceptible by design. */
    RIPPLE_STEP
}

/** Thin Android adapter: the waveform numbers live in the pure [HapticPatterns] table. */
private fun effectFor(signal: HapticSignal): VibrationEffect {
    val pattern = HapticPatterns.patternFor(signal)
    return VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
}

/**
 * Fires [signal] on the device's vibrator, or does nothing if the device has
 * none (common on tablets/foldables' secondary displays, some emulators).
 * Callers should gate this behind [LocalHapticsEnabled] themselves — this
 * function does not read that CompositionLocal itself, since plain
 * (non-Composable) call sites exist too; [rememberHaptics] below is the
 * Composable-friendly entry point that DOES read it.
 */
fun performHaptic(context: Context, signal: HapticSignal) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator == null || !vibrator.hasVibrator()) return
    // A real device (not just an emulator) enforces android.permission.VIBRATE
    // strictly -- vibrate() throws a genuine SecurityException without it,
    // confirmed by an on-device instrumented test catching a manifest gap this
    // codebase actually shipped with. That gap is fixed (see AndroidManifest.xml),
    // but a haptic signal is never worth crashing the app over for any reason
    // (permission revoked by a restrictive MDM/work profile, an OEM battery-saver
    // quirk, a future platform change) -- runCatching here mirrors the same
    // "never let a flourish crash a screen" defensive stance PremiumShaders.kt
    // already established for AGSL shader failures.
    runCatching { vibrator.vibrate(effectFor(signal)) }
}

/**
 * The Composable entry point every game screen should use going forward in
 * place of `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.X)`
 * for GAME-EVENT haptics (captures, moves, wins, losses...). Already reads
 * [LocalHapticsEnabled] internally, so call sites never need their own
 * `if (hapticsEnabled)` guard for this specific call — one fewer thing for
 * every screen to get right or forget.
 *
 * Usage: `val haptics = rememberHaptics(); ...; haptics(HapticSignal.STRONG_ACTION)`
 */
@Composable
fun rememberHaptics(): (HapticSignal) -> Unit {
    val context = LocalContext.current
    val enabled = LocalHapticsEnabled.current
    return remember(context, enabled) {
        { signal: HapticSignal -> if (enabled) performHaptic(context, signal) }
    }
}

/**
 * Plays a [HapticSequence] (several signals with delays between them) on the vibrator. Starting a
 * new sequence cancels one still in flight, leaving the composition cancels it too, and nothing is
 * scheduled at all when the haptics setting is off ([LocalHapticsEnabled]). Each signal still goes
 * through [performHaptic]'s own guards (no vibrator, missing permission).
 *
 * Callers fire it from a `LaunchedEffect` keyed on an event or serial id, never from the body of a
 * composable (that would replay on every recomposition).
 */
@Composable
fun rememberHapticSequencer(): (HapticSequence) -> Unit {
    val context = LocalContext.current
    val enabled by rememberUpdatedState(LocalHapticsEnabled.current)
    val scope = rememberCoroutineScope()
    val running = remember { arrayOfNulls<Job>(1) }
    DisposableEffect(Unit) {
        onDispose { running[0]?.cancel(); running[0] = null }
    }
    return remember(context, scope) {
        { sequence: HapticSequence ->
            running[0]?.cancel()
            running[0] = null
            if (enabled && !sequence.isEmpty) {
                running[0] = scope.launch {
                    sequence.play(fire = { signal -> if (enabled) performHaptic(context, signal) })
                }
            }
        }
    }
}
