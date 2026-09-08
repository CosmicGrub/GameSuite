package com.gamesuite.settings

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the player has Settings -> Display -> "Enhanced move animations"
 * turned on — see AppSettings.enhancedAnimationsEnabled's KDoc for exactly
 * what this covers (weighted lift/carry/set-down motion, capture fade-outs,
 * fly-to-target tosses, tile slides) and how it's distinct from
 * [LocalCard3DMode] (motion quality vs. perspective/depth rendering — the
 * two compose independently). Provided once at MainActivity's root, same
 * pattern as every other settings CompositionLocal in this file's siblings.
 *
 * Always combine with reduced motion at the read site, same rule as
 * LocalCard3DMode: `val enhanced = LocalEnhancedAnimations.current &&
 * !LocalReducedMotion.current`.
 *
 * Defaults to true (matching AppSettings' own default) so anything read
 * before the provider is set up plays the enhanced motion, not a flat snap.
 */
val LocalEnhancedAnimations = compositionLocalOf { true }
