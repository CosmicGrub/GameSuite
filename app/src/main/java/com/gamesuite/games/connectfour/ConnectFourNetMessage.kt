package com.gamesuite.games.connectfour

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Connect Four over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (Nearby = PlayMode.LOCAL_AD_HOC, relay = PlayMode.ONLINE). Host-authoritative, same shape as UNO's
 * UnoNetMessage: the host (seat 0) owns the engine; the guest (seat 1) sends requests and renders the
 * last [StateSync] it accepted.
 */
@Serializable
sealed class ConnectFourNetMessage {
    /** The host's authoritative state. [version] only ever grows; a receiver drops anything <= what it applied. */
    @Serializable
    data class StateSync(val version: Int, val state: ConnectFourNetState) : ConnectFourNetMessage()

    /** A guest's "drop a disc in [column]" request. The host validates it; the guest never applies it itself. */
    @Serializable
    data class DropRequest(val column: Int) : ConnectFourNetMessage()

    /** Guest to host: send me the current state (startup race, reconnect). */
    @Serializable
    data object RequestState : ConnectFourNetMessage()

    /** Guest to host: deal the next board (only honoured while the current board is over). */
    @Serializable
    data object PlayAgainRequest : ConnectFourNetMessage()
}

/**
 * Everything a guest needs to render: the board, whose turn, the result, and the session tally. The
 * roster (names, ids) comes from the guest's own GameContext, so it is not repeated here.
 */
@Serializable
data class ConnectFourNetState(
    val cells: List<Int?>,
    val currentPlayerIndex: Int,
    val lastAction: String,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val winningLine: List<Int>?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)
