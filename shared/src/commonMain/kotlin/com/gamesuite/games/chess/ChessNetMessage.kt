package com.gamesuite.games.chess

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Chess over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (currently: [com.gamesuite.transport.LanMultiplayerTransport] -- see
 * docs/ENGINE_DECISION.md's Action Item 8 follow-up on extending the proven LAN-multiplayer
 * pattern to a second game). Mirrors TicTacToeNetMessage.kt's own already-proven shape almost
 * exactly: host-authoritative play, [StateSync] broadcasts a version-stamped snapshot,
 * [Intent] carries a non-host player's attempted action to the host instead of applying it
 * locally, and [RequestState] covers the same startup/reconnect race TicTacToeNetMessage's
 * own KDoc documents (a guest's message listener registering after the host's very first
 * broadcast would otherwise just miss it forever).
 *
 * One real wrinkle beyond Tic-Tac-Toe: [ChessGame.playMove] already took an explicit
 * `playerIndex` parameter before networking existed (pass-and-play needs it, since two
 * people share one device and each tap has to say which side it's for) -- unlike
 * [com.gamesuite.games.tictactoe.TicTacToeGame.cellClicked], which infers the mover from
 * `currentPlayer` implicitly. [ChessGame]'s own `playMove`/`handleNetworkMessage` both
 * re-validate that parameter against whose turn it actually is regardless, exactly the same
 * defense Tic-Tac-Toe's own Intent handling applies to its own (implicit) mover.
 */
@Serializable
data class ChessNetState(
    val board: List<Piece?>,
    val sideToMove: PieceColor,
    val castleWK: Boolean,
    val castleWQ: Boolean,
    val castleBK: Boolean,
    val castleBQ: Boolean,
    val epSquare: Int?,
    val lastFrom: Int?,
    val lastTo: Int?,
    val lastAction: String,
    val roundOver: Boolean,
    val result: ChessResult,
    val inCheck: Boolean,
    val winnerPlayerId: String?,
    val scoreP1: Int,
    val scoreP2: Int,
    val draws: Int,
    val matchOver: Boolean
)

@Serializable
sealed class ChessNetMessage {
    /** The host's authoritative state, sent after every host-side mutation (broadcast to
     *  the other player) and also in direct reply to a [RequestState]. [version] is a
     *  monotonically increasing counter (a wire/ordering concern, not game data) -- a
     *  receiver drops any [StateSync] whose version is <= the last one it applied. */
    @Serializable
    data class StateSync(val version: Int, val state: ChessNetState) : ChessNetMessage()

    /** A non-host player's attempted action, sent to the host instead of applied locally. */
    @Serializable
    data class Intent(val intent: ChessIntentPayload) : ChessNetMessage()

    /** Sent by a non-host device right after it registers its message listener, so the
     *  host can (re-)send it the current state directly -- covers the same startup/
     *  reconnect race TicTacToeNetMessage.RequestState's own KDoc documents. */
    @Serializable
    data object RequestState : ChessNetMessage()
}

@Serializable
sealed class ChessIntentPayload {
    /** A guest's move request. [promotion] is optional: the engine only auto-queens, so null
     *  or QUEEN are honoured and any other piece is refused by the host (never silently
     *  changed into a queen). The host decides legality, whose turn it is and who sent this. */
    @Serializable
    data class PlayMove(val from: Int, val to: Int, val promotion: PieceType? = null) : ChessIntentPayload()

    /** Either player's "Play Again" tap -- deliberately routed through the host rather than
     *  applied locally, same as [PlayMove], so both players' boards stay on the same round
     *  after either one clicks it. Honoured only once the round is over; the host re-deals
     *  (host = White again) and broadcasts, which is how the other player sees it. */
    @Serializable
    data object PlayAgain : ChessIntentPayload()
}
