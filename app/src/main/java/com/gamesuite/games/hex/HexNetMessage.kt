package com.gamesuite.games.hex

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Hex over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE). Host-authoritative, same shape as ReversiNetMessage: the host
 * (seat 0) owns the only real engine; the guest (seat 1) renders the last [StateSync] and forwards its
 * taps. Hex has no hidden information.
 */
@Serializable
sealed class HexNetMessage {
    /** The host's authoritative state after every change; a receiver drops any whose [version] is not newer. */
    @Serializable
    data class StateSync(val version: Int, val state: HexNetState) : HexNetMessage()

    /** A guest's tap: "place my stone at [cell]" (row * size + col). The host validates it. */
    @Serializable
    data class PlaceStone(val cell: Int) : HexNetMessage()

    /** A guest (as the second player, pie rule on) takes over the opening stone instead of moving. */
    @Serializable
    data object RequestSwap : HexNetMessage()

    /** A guest asks the host to (re-)send the current state: startup race and reconnects. */
    @Serializable
    data object RequestState : HexNetMessage()

    /** A guest asks for a fresh board once the current one is over (the host re-deals). */
    @Serializable
    data object RequestPlayAgain : HexNetMessage()
}

@Serializable
data class HexNetPlayer(val playerId: String, val displayName: String)

/** Everything a guest needs to draw the table. The winning path is derived by the guest from [cells]. */
@Serializable
data class HexNetState(
    val cells: List<Int?>,
    val players: List<HexNetPlayer>,
    val currentPlayerIndex: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val lastMove: Int?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val size: Int = 11,
    /** Pie rule in force for this board. */
    val pieRule: Boolean = false,
    /** True while the second player may still swap (pie rule, just after the opening stone). */
    val pieOpen: Boolean = false,
    /** True once a swap happened on this board. */
    val swapped: Boolean = false
)
