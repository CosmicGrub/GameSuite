package com.gamesuite.games.darts

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Darts over a networked MultiplayerTransport (Nearby / Online), 1 to 4 seats.
 * Host-authoritative: the host (seat 0) owns the engine and the scatter of every throw (its seeded
 * stream); a guest can only say where it aims and how hard it flicked ([ThrowRequest]) or ask to
 * collect its darts. The host answers every change with a versioned [StateSync]. Nothing is hidden.
 * A request counts only from the transport-established sender whose seat is on turn; no message
 * names a seat.
 */
@Serializable
sealed class DartsNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: DartsState, val sessionWins: Map<String, Int>) : DartsNetMessage()

    /** "Throw my next dart at this point (millimetres from the bull) with this flick power (0..1)." */
    @Serializable
    data class ThrowRequest(val aimX: Double, val aimY: Double, val power: Double) : DartsNetMessage()

    /** "Pull my darts and pass the turn." */
    @Serializable
    data object CollectRequest : DartsNetMessage()

    @Serializable
    data object RequestState : DartsNetMessage()

    @Serializable
    data object RequestPlayAgain : DartsNetMessage()
}
