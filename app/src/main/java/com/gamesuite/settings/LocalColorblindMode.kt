package com.gamesuite.settings

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the player has Settings -> Accessibility -> "Colorblind-safe mode"
 * turned on. Provided once at MainActivity's root (mirrors
 * LocalReducedMotion/LocalHapticsEnabled's own pattern) so any composable
 * that draws color-only game state can add a shape/pattern cue alongside it.
 *
 * This is the fix for the audited finding (project roadmap audit, A3) that
 * `colorblindMode` was stored and toggleable in Settings but consumed
 * nowhere — UNO's color-choice dialog already shows a text label on every
 * swatch (a good universal a11y win, kept as-is for everyone), but toggling
 * this setting changed nothing, which reads as broken support rather than
 * absent support. `UnoScreen.kt`'s `ColorPickerDialog` is the first real
 * consumer: gated on this flag, it overlays a distinct shape per color
 * (circle/triangle/square/diamond) so color is never the only signal.
 *
 * Defaults to false so anything read before the provider is set up (e.g. a
 * preview/test context) renders the plain, color-only swatch.
 */
val LocalColorblindMode = compositionLocalOf { false }
