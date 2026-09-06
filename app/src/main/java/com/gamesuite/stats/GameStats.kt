package com.gamesuite.stats

import kotlinx.serialization.Serializable

/**
 * All-time record for one game, keyed by [gameId] (GameModule.gameId — see
 * StatsRepository's KDoc for why the whole map is stored as one JSON blob
 * rather than one DataStore key per field). This is intentionally generic
 * (win/loss/draw + a play count) rather than modeling each game's own scoring
 * — a shared stats screen across 11 very different games needs one comparable
 * shape, not 11 bespoke ones; a per-game "best score" belongs in that game's
 * own save data if it wants one (Sliding Puzzle's best-time/best-moves record
 * is a separate, dedicated store for exactly this reason).
 */
@Serializable
data class GameStats(
    val gameId: String,
    val displayName: String,
    val matchesPlayed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val lastPlayedEpochMillis: Long = 0L
)
