package com.gamesuite.games.bowling

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Bowling over a networked MultiplayerTransport (Nearby / Online), 1 to 4 seats.
 * Host-authoritative: the host (seat 0) owns the engine, the physics and the scatter of every
 * roll. A guest can only describe the roll it wants ([RollRequest]); the host clamps it, scatters it
 * with its own seeded stream, runs the lane and answers with a versioned [StateSync]. Nothing is
 * hidden. A request counts only from the transport-established sender whose seat is on turn.
 */
@Serializable
sealed class BowlingNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: BowlingState, val sessionWins: Map<String, Int>) : BowlingNetMessage()

    @Serializable
    data class RollRequest(val shot: BowlShot) : BowlingNetMessage()

    @Serializable
    data object RequestState : BowlingNetMessage()

    @Serializable
    data object RequestPlayAgain : BowlingNetMessage()
}
