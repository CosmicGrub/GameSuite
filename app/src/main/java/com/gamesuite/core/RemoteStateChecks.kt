package com.gamesuite.core

/**
 * Shared guest-side check for the session tallies a host sends in a StateSync (the same rule every game's
 * snapshot decoder already applies): no negative counts and no win credited to a player who is not at the table.
 * A host state that fails it is refused whole, exactly like one with an impossible board.
 */
internal fun sessionTalliesValid(wins: Map<String, Int>, draws: Int, roster: List<PlayerInfo>): Boolean =
    draws >= 0 && wins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }

/** The decoder for every game's network messages: unknown keys are ignored so a newer host adding a field does not make an older guest drop each StateSync. */
val NetJson: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
