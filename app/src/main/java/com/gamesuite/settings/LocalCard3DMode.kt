package com.gamesuite.settings

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the player has Settings -> Display -> "3D Perspective Mode" turned
 * on — see AppSettings.card3DEnabled's KDoc for what this actually changes
 * (originally labeled "3D card mode" until board games started reading it
 * too, per the animation/physics pitch's cross-cutting finding). Provided
 * once at MainActivity's root (mirrors LocalReducedMotion/LocalCardScale) so
 * any game screen or shared component — card, board, or table — can read it
 * directly instead of threading a SettingsViewModel reference everywhere.
 *
 * This is a pure rendering-style switch, not an accessibility one — always
 * check LocalReducedMotion too and let reduced motion win: a perspective
 * flip or tilt is still motion, so `card3D && !reducedMotion` is the correct
 * guard everywhere this is read, never this flag alone.
 *
 * Defaults to false so anything read before the provider is set up (e.g. a
 * preview/test context) renders the original flat card look.
 */
val LocalCard3DMode = compositionLocalOf { false }
