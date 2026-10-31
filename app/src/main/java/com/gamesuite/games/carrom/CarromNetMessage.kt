package com.gamesuite.games.carrom

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Carrom over a networked MultiplayerTransport. Host-authoritative: the host (seat 0) simulates
 * every flick and answers with a [StateSync] that carries the board before the shot and the shot itself, so each
 * device can replay the same motion; the state in it is the truth. A guest only asks ([ShotRequest]). Requests are
 * honoured only from the transport-established sender whose seat is on turn; no message carries a seat.
 */
@Serializable
sealed class CarromNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: CarromNetState) : CarromNetMessage()

    @Serializable
    data class ShotRequest(val shot: CarromShot) : CarromNetMessage()

    @Serializable
    data object RequestState : CarromNetMessage()

    @Serializable
    data object RequestPlayAgain : CarromNetMessage()
}

@Serializable
data class CarromNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

@Serializable
data class CarromNetCoin(val id: Int, val x: Double, val y: Double)

@Serializable
data class CarromNetState(
    val players: List<CarromNetPlayer>,
    val coins: List<CarromNetCoin>,
    val currentPlayerIndex: Int,
    val queenOwner: Int,
    val queenPending: Int,
    val shotsTaken: Int,
    val flags: Int,
    val eventSeat: Int?,
    val potted: List<Int>,
    val lastSeat: Int?,
    val lastShot: CarromShot?,
    val before: List<CarromNetCoin>,
    val shotSerial: Int,
    val boardOver: Boolean,
    val winnerTeam: Int,
    val winPoints: Int,
    val teamPoints: List<Int>,
    val targetPoints: Int,
    val sessionWins: Map<String, Int>
)
