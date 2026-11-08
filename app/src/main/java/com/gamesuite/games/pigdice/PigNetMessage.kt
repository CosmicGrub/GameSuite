package com.gamesuite.games.pigdice

import kotlinx.serialization.Serializable

/**
 * Wire protocol of Pig over a networked MultiplayerTransport (Nearby / Online), 2 to 6 seats.
 * Host-authoritative: the host (seat 0) owns the engine AND the dice (its injected Random). A guest can
 * only ASK to roll (with the number of dice, which only matters in Hog) or to hold; the host answers every
 * change with a [StateSync]. Requests are honoured only from the transport-established sender whose seat
 * is on turn. Nothing in Pig is hidden, so every guest gets the same state.
 */
@Serializable
sealed class PigNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: PigNetState) : PigNetMessage()

    @Serializable
    data class RollRequest(val count: Int = 1) : PigNetMessage()

    @Serializable
    data object HoldRequest : PigNetMessage()

    @Serializable
    data object RequestState : PigNetMessage()

    @Serializable
    data object RequestPlayAgain : PigNetMessage()
}

@Serializable
data class PigNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

@Serializable
data class PigNetState(
    val variant: String,
    val target: Int,
    val players: List<PigNetPlayer>,
    val scores: List<Int>,
    val currentPlayerIndex: Int,
    val turnTotal: Int,
    val turnRolls: Int,
    val dice: List<Int>,
    val event: String,
    val eventSeat: Int,
    val eventPoints: Int,
    val rollSerial: Int,
    val turnSerial: Int,
    val over: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>
)
