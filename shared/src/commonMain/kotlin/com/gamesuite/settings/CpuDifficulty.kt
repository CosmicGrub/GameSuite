package com.gamesuite.settings

/**
 * Extracted verbatim from the Android app's settings/AppSettings.kt, where
 * this enum sits alongside real DataStore-backed settings fields (not
 * portable). The enum itself has no platform dependency at all, so it moves
 * here unchanged rather than dragging the whole (Android-only) settings file
 * along with it just for one symbol TicTacToeGame.kt needs.
 */
enum class CpuDifficulty { EASY, MEDIUM, HARD }
