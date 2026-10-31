package com.gamesuite.games.billiards

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Billiards over a networked MultiplayerTransport (Nearby / Online), 2 seats.
 * Host-authoritative: the host owns the table, validates every shot and runs the physics once; guests
 * only ask to take a stroke ([ShotRequest]) and are sent the resulting [BilliardsState] (which carries the
 * last stroke, so every device animates the same replay and lands on the same final positions).
 * The sender of a request is whoever the transport says sent it, never a seat written in the message.
 */
@Serializable
sealed class BilliardsNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: BilliardsState) : BilliardsNetMessage()

    /** "Take this stroke for me." The host honours it only if the sender's seat is the shooter. */
    @Serializable
    data class ShotRequest(val shot: Shot) : BilliardsNetMessage()

    @Serializable
    data object RequestState : BilliardsNetMessage()

    @Serializable
    data object PlayAgainRequest : BilliardsNetMessage()
}
