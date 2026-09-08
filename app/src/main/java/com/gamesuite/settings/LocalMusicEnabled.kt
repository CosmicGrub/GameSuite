package com.gamesuite.settings

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the player has Settings -> Sound & feedback -> Ambient music
 * turned on. Provided once at MainActivity's root (mirrors
 * [LocalHapticsEnabled]'s own pattern exactly) so any composable that would
 * start [com.gamesuite.audio.rememberAmbientMusic] can read this directly
 * instead of every call site needing its own SettingsViewModel reference.
 *
 * This does NOT by itself decide whether music plays — the master
 * `soundEnabled` toggle must still hard-gate music too (a player who's
 * turned off all sound shouldn't hear ambient music regardless of this
 * setting). Each call site is expected to combine the two, e.g.
 * `rememberAmbientMusic(profile, enabled = LocalMusicEnabled.current && settings.soundEnabled)`,
 * same spirit as every `LocalHapticsEnabled` call site guarding its own
 * haptic call.
 *
 * Defaults to true so anything read before the provider is set up (e.g. a
 * preview/test context) keeps today's music-on behavior.
 */
val LocalMusicEnabled = compositionLocalOf { true }
