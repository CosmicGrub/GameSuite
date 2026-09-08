package com.gamesuite.settings

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the player has Settings -> Accessibility -> Haptics turned on.
 * Provided once at MainActivity's root (mirrors LocalReducedMotion/
 * LocalCardScale's own pattern) so any composable that fires haptic
 * feedback can read this directly instead of every call site needing its
 * own SettingsViewModel reference. This is the fix for the audited finding
 * (project roadmap audit, A3) that `hapticsEnabled` was stored and
 * toggleable in Settings but consumed nowhere — eight files fired haptics
 * unconditionally: FannedHand.kt, AirHockeyScreen.kt, DominoesScreen.kt,
 * MancalaScreen.kt, SlidingPuzzleScreen.kt, TicTacToeScreen.kt,
 * TileGameScreen.kt, UnoScreen.kt.
 *
 * Every haptics call site should read `if (LocalHapticsEnabled.current)
 * haptics.performHapticFeedback(...)` (or wrap the call in the same guard) —
 * never fire unconditionally again.
 *
 * Defaults to true so anything read before the provider is set up (e.g. a
 * preview/test context) keeps today's haptics-on behavior.
 */
val LocalHapticsEnabled = compositionLocalOf { true }
