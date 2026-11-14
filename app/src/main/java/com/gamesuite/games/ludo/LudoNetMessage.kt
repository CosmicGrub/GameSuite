package com.gamesuite.games.ludo

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Ludo over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE), 2 to 4 seats. Host-authoritative: the host (seat 0)
 * owns the engine AND the dice (its injected Random); a guest can only ask to roll or to move a
 * token, and the host answers every change with a [StateSync] to every guest. Nothing is hidden.
 * Requests are honoured only from the transport-established sender whose seat is on turn.
 */
@Serializable
sealed class LudoNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: LudoNetState) : LudoNetMessage()

    /** "Roll the die for my seat." */
    @Serializable
    data object RollRequest : LudoNetMessage()

    /** "Move my token number [token] (0..3) by the roll I was given." */
    @Serializable
    data class MoveRequest(val token: Int) : LudoNetMessage()

    @Serializable
    data object RequestState : LudoNetMessage()

    @Serializable
    data object RequestPlayAgain : LudoNetMessage()
}

@Serializable
data class LudoNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

@Serializable
data class LudoNetState(
    val tokens: List<Int>,
    val players: List<LudoNetPlayer>,
    val currentPlayerIndex: Int,
    val dice: Int,
    val awaitingMove: Boolean,
    val sixesInRow: Int,
    val needSix: Boolean,
    val exactFinish: Boolean,
    val event: String,
    val eventSeat: Int?,
    val eventToken: Int?,
    val eventFrom: Int,
    val eventTo: Int,
    val captured: Int,
    val rollSerial: Int,
    val moveSerial: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>
)
