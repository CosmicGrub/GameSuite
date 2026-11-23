package com.gamesuite.games.war

import kotlinx.serialization.Serializable

/**
 * Wire protocol for War over a networked MultiplayerTransport, 2 to 4 seats. Host-authoritative: the host
 * owns the shuffle, every pile and every secret pick. A guest can only ask to flip (classic) or to pick
 * one of its own three cards (choose-3). Each guest is sent its OWN three candidate cards and nobody
 * else's; piles are never sent (only their sizes), and a pick stays a bare "has picked" flag until the
 * host reveals the round.
 */
@Serializable
sealed class WarNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: WarNetState) : WarNetMessage()

    @Serializable
    data object FlipRequest : WarNetMessage()

    /** "Play the candidate at [index] (0..2) of my three." */
    @Serializable
    data class PickRequest(val index: Int) : WarNetMessage()

    @Serializable
    data object RequestState : WarNetMessage()

    @Serializable
    data object PlayAgainRequest : WarNetMessage()
}

@Serializable
data class WarNetSeat(
    val playerId: String, val displayName: String, val isBot: Boolean, val count: Int, val picked: Boolean, val wins: Int
)

@Serializable
data class WarNetPlay(val seat: Int, val down: Int, val up: Int)

@Serializable
data class WarNetStep(val plays: List<WarNetPlay>)

@Serializable
data class WarNetRound(val steps: List<WarNetStep>, val winnerSeat: Int, val potSize: Int)

@Serializable
data class WarNetState(
    val seats: List<WarNetSeat>, val phase: String, val round: Int, val lastRound: WarNetRound?,
    /** Card ids, only for the receiving seat; empty for every other seat. */
    val choices: List<List<Int>>, val winnerSeat: Int, val draw: Boolean, val config: WarConfig, val serial: Int
)
