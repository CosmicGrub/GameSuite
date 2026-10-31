package com.gamesuite.games.hareandhounds

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Hare and Hounds over a networked MultiplayerTransport (LOCAL_AD_HOC / ONLINE).
 * Host-authoritative like ReversiNetMessage: the host (seat 0) owns the only engine and the guest
 * (seat 1) forwards a step as [PlayMove] and renders the last [StateSync] it applied. Which seat
 * plays the hounds travels in the state. There is no hidden information.
 */
@Serializable
sealed class HoundsNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: HoundsNetState) : HoundsNetMessage()

    @Serializable
    data class PlayMove(val from: Int, val to: Int) : HoundsNetMessage()

    @Serializable
    data object RequestState : HoundsNetMessage()

    @Serializable
    data object RequestPlayAgain : HoundsNetMessage()
}

@Serializable
data class HoundsNetPlayer(val playerId: String, val displayName: String)

/** Everything a guest needs to draw the board; legal moves are derived, never sent. */
@Serializable
data class HoundsNetState(
    val cells: List<Int>,
    val players: List<HoundsNetPlayer>,
    val houndsSeat: Int,
    val currentPlayerIndex: Int,
    val houndMoves: Int,
    val ply: Int,
    val lastMove: HoundsMove?,
    val lastMover: Int,
    val lastAction: String,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val endReason: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)
