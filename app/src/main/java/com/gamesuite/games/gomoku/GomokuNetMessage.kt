package com.gamesuite.games.gomoku

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Gomoku over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE). Host-authoritative, same shape as ReversiNetMessage: the host
 * (seat 0) owns the only real engine; the guest (seat 1) renders the last [StateSync] it applied and
 * forwards a tap as [PlaceStone]. Gomoku has no hidden information, so the full state goes unredacted.
 */
@Serializable
sealed class GomokuNetMessage {
    /** The host's authoritative state after every change; a receiver drops any whose [version] is not newer. */
    @Serializable
    data class StateSync(val version: Int, val state: GomokuNetState) : GomokuNetMessage()

    /** A guest's tap: "place my stone at [cell]" (row * 15 + col). The host validates it. */
    @Serializable
    data class PlaceStone(val cell: Int) : GomokuNetMessage()

    /** A guest asks the host to (re-)send the current state: startup race and reconnects. */
    @Serializable
    data object RequestState : GomokuNetMessage()

    /** A guest asks for a fresh board once the current one is over (the host re-deals). */
    @Serializable
    data object RequestPlayAgain : GomokuNetMessage()
}

@Serializable
data class GomokuNetPlayer(val playerId: String, val displayName: String)

/** Everything a guest needs to draw the table. */
@Serializable
data class GomokuNetState(
    val cells: List<Int?>,
    val players: List<GomokuNetPlayer>,
    val currentPlayerIndex: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val winLine: List<Int>,
    val lastMove: Int?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    /** True when only exactly five in a row wins (an overline does not). */
    val exactFive: Boolean = false
)
