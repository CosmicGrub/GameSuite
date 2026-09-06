package com.gamesuite.settings

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the player has Settings -> Accessibility -> Reduced Motion turned on.
 * Provided once at MainActivity's root (mirrors games/cards/CardScale.kt's
 * LocalCardScale pattern) so any composable that plays a non-essential
 * animation -- a card-flip transition, a fan-lift spring, a capture animation
 * -- can read this directly instead of every call site needing its own
 * SettingsViewModel reference just to honor one accessibility toggle. This is
 * the fix for the audited finding that Reduced Motion was stored and
 * toggleable but consumed nowhere.
 *
 * Defaults to false so anything read before the provider is set up (e.g. a
 * preview/test context) plays its normal animation.
 */
val LocalReducedMotion = compositionLocalOf { false }
