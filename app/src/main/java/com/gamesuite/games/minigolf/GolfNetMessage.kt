package com.gamesuite.games.minigolf

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Mini Golf over a networked MultiplayerTransport (Nearby / Online), 1 to 4 seats.
 * Host-authoritative: the host owns the course state, validates every putt and runs the physics once;
 * guests only ask to putt ([StrokeRequest]) or to move on to the next hole, and are sent the resulting
 * [GolfState] (which carries the last putt so every device replays the same stroke). The sender of a request
 * is whoever the transport says sent it, never a seat written in the message.
 */
@Serializable
sealed class GolfNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: GolfState) : GolfNetMessage()

    @Serializable
    data class StrokeRequest(val shot: GolfShot) : GolfNetMessage()

    @Serializable
    data object NextHoleRequest : GolfNetMessage()

    @Serializable
    data object RequestState : GolfNetMessage()

    @Serializable
    data object PlayAgainRequest : GolfNetMessage()
}
