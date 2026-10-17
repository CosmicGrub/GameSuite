package com.gamesuite.games.reversi

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Reversi over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE). Host-authoritative, same shape as UnoNetMessage:
 * the host (seat 0, dark discs, localPlayerIndex 0) owns the only real engine; the guest (seat 1,
 * light discs) renders the last [StateSync] it applied and forwards a tap as [PlaceDisc].
 * Reversi has no hidden information, so the full state goes to everyone unredacted.
 */
@Serializable
sealed class ReversiNetMessage {
    /** The host's authoritative state after every change. [version] only goes up; a receiver drops
     *  any StateSync whose version is not newer than the last one it applied. */
    @Serializable
    data class StateSync(val version: Int, val state: ReversiNetState) : ReversiNetMessage()

    /** A guest's tap: "place my disc at [cell]" (row * 8 + col). The host validates it. */
    @Serializable
    data class PlaceDisc(val cell: Int) : ReversiNetMessage()

    /** A guest asks the host to (re-)send the current state: startup race and reconnects. */
    @Serializable
    data object RequestState : ReversiNetMessage()

    /** A guest asks for a fresh board once the current one is over (the host re-deals). */
    @Serializable
    data object RequestPlayAgain : ReversiNetMessage()
}

@Serializable
data class ReversiNetPlayer(val playerId: String, val displayName: String)

/**
 * Everything a guest needs to draw the table. The legal-move set and the disc counts are NOT sent:
 * the guest derives them from [cells], so they cannot disagree with the board.
 */
@Serializable
data class ReversiNetState(
    val cells: List<Int?>,
    val players: List<ReversiNetPlayer>,
    val currentPlayerIndex: Int,
    val lastAction: String,
    val justPassed: Boolean,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)
