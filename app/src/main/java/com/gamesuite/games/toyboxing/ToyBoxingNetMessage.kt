package com.gamesuite.games.toyboxing

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Toy Boxing over a networked MultiplayerTransport (LOCAL_AD_HOC / ONLINE), two seats.
 * Host-authoritative: the host (seat 0) owns the only simulation and the only clock. A guest sends what it
 * WANTS to do ([Intent]); the host applies it at the moment it arrives and streams versioned [StateSync]
 * snapshots back. Which seat an intent acts for is never in the payload: it is the seat the transport
 * established for the sender. There is no hidden information in boxing, so the snapshot is the whole state.
 */
@Serializable
sealed class ToyBoxingNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: BoxingState) : ToyBoxingNetMessage()

    /** [action] is a [BoxingAction] name. */
    @Serializable
    data class Intent(val action: String) : ToyBoxingNetMessage()

    /** Host to guest: the last [Intent] did nothing. [reason] is a [BoxingReject] name. */
    @Serializable
    data class Rejected(val reason: String) : ToyBoxingNetMessage()

    @Serializable
    data object RequestState : ToyBoxingNetMessage()

    @Serializable
    data object RequestPlayAgain : ToyBoxingNetMessage()
}
