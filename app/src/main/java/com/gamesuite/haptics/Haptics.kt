package com.gamesuite.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.gamesuite.settings.LocalHapticsEnabled

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
 * seven buckets; see each screen's own call sites for which bucket a given
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
    CELEBRATION
}

private fun VibrationEffect.orNull(): VibrationEffect? = this

private fun effectFor(signal: HapticSignal): VibrationEffect = when (signal) {
    HapticSignal.LIGHT_TICK ->
        VibrationEffect.createOneShot(12, 90)
    HapticSignal.NORMAL_ACTION ->
        VibrationEffect.createOneShot(25, 140)
    HapticSignal.STRONG_ACTION ->
        VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 30), intArrayOf(0, 200, 0, 220), -1)
    HapticSignal.ESCALATING ->
        VibrationEffect.createWaveform(longArrayOf(0, 15, 60, 15, 60, 20), intArrayOf(0, 120, 0, 160, 0, 200), -1)
    HapticSignal.SUCCESS ->
        VibrationEffect.createWaveform(longArrayOf(0, 18, 50, 28), intArrayOf(0, 150, 0, 220), -1)
    HapticSignal.FAILURE ->
        VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60), intArrayOf(0, 180, 0, 140), -1)
    HapticSignal.CELEBRATION ->
        VibrationEffect.createWaveform(
            longArrayOf(0, 25, 40, 25, 40, 25, 40, 60),
            intArrayOf(0, 160, 0, 190, 0, 220, 0, 255),
            -1
        )
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
    runCatching { vibrator.vibrate(effectFor(signal).orNull()) }
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
