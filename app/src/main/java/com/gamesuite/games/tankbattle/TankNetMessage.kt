package com.gamesuite.games.tankbattle

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Tank Battle over a networked MultiplayerTransport (Nearby / Online), 2 to 4 seats.
 * Host-authoritative: the host (seat 0) owns the engine and runs the whole shot simulation; a guest
 * can only say "fire at this angle and power with this shell" ([FireRequest]). The host answers every
 * change with a versioned [StateSync] that carries the finished shot (path, craters, new terrain), so
 * a guest never simulates anything. Nothing is hidden. A request counts only from the transport
 * established sender whose seat is on turn; no message names a seat.
 */
@Serializable
sealed class TankNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: TankState, val sessionWins: Map<String, Int>) : TankNetMessage()

    @Serializable
    data class FireRequest(val angle: Int, val power: Int, val weapon: String) : TankNetMessage()

    @Serializable
    data object RequestState : TankNetMessage()

    @Serializable
    data object RequestPlayAgain : TankNetMessage()
}
