package com.gamesuite.settings

import androidx.compose.runtime.compositionLocalOf

/**
 * How fast the computer opponents act and how long the dramatic beats hold, app-wide.
 *
 * Opponent turns used to land after a fixed, fairly short pause, which on a quick table felt like
 * the CPU wasn't thinking and left a human no time to read the board between plays. Every
 * bot-driven pause in a game screen multiplies its base delay through [scale], so one setting
 * (Settings -> Gameplay, or the in-game menu) slows or speeds the whole suite. Purely cosmetic:
 * it never changes what a bot decides, only when the move appears.
 *
 * [delayScale] > 1 stretches pauses, < 1 shortens them.
 */
enum class GameSpeed(val label: String, val description: String, val delayScale: Float) {
    SLOW("Slow", "Long, deliberate pauses: time to study every turn.", 2.4f),
    RELAXED("Relaxed", "A little slower than the original pacing.", 1.5f),
    NORMAL("Normal", "The original pacing.", 1.0f),
    QUICK("Quick", "Opponents move fast.", 0.55f);

    /** Scales a base delay in milliseconds; a positive delay never collapses to zero. */
    fun scale(baseMs: Long): Long {
        if (baseMs <= 0L) return 0L
        return (baseMs * delayScale).toLong().coerceAtLeast(1L)
    }

    /** The next speed in the cycle Slow -> Relaxed -> Normal -> Quick -> Slow (for a one-tap menu item). */
    fun next(): GameSpeed = values()[(ordinal + 1) % values().size]

    companion object {
        /** Tolerant parse for a stored value: anything unknown falls back to [NORMAL]. */
        fun fromName(name: String?): GameSpeed =
            values().firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * The player's chosen [GameSpeed], provided once at MainActivity's root (same pattern as
 * [LocalReducedMotion]) so a game screen reads it without its own SettingsViewModel reference.
 * Defaults to [GameSpeed.NORMAL] outside a provider (previews, tests).
 */
val LocalGameSpeed = compositionLocalOf { GameSpeed.NORMAL }
