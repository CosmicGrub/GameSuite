package com.gamesuite.games.shogi

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Shogi / Mini Shogi over a networked MultiplayerTransport (Nearby + Online).
 * Host-authoritative, same shape as ReversiNetMessage / ChessNetMessage: the host (seat 0) owns the only
 * real engine and broadcasts [StateSync]; the guest forwards a tap as [PlayMove] / [DeclareImpasse] and
 * the host validates it against its own legal-move list and the guest's real seat. Shogi has no hidden
 * information, so the full state goes to both seats.
 */
@Serializable
sealed class ShogiNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: ShogiNetState) : ShogiNetMessage()

    /** A guest's move in [ShogiMove] encoding (board move, promotion flag or drop). */
    @Serializable
    data class PlayMove(val move: Int) : ShogiNetMessage()

    @Serializable
    data object DeclareImpasse : ShogiNetMessage()

    @Serializable
    data object RequestState : ShogiNetMessage()

    @Serializable
    data object RequestPlayAgain : ShogiNetMessage()
}

@Serializable
data class ShogiNetPlayer(val playerId: String, val displayName: String)

/** Everything a guest needs to draw the table; legal moves and check are derived from [cells] and [hands]. */
@Serializable
data class ShogiNetState(
    val n: Int,
    val cells: List<Int>,
    val hands: List<Int>,
    val sideToMove: Int,
    val senteSeat: Int,
    val players: List<ShogiNetPlayer>,
    val lastFrom: Int,
    val lastTo: Int,
    val lastCaptured: Int,
    val ply: Int,
    val gameOver: Boolean,
    val winnerPlayerId: String?,
    val endReason: String,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)
