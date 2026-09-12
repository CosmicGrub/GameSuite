package com.gamesuite.games.tictactoe

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Tic-Tac-Toe over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (currently: [com.gamesuite.transport.LanMultiplayerTransport] -- see
 * docs/ENGINE_DECISION.md's Action Item 8 follow-up on wiring the LAN transport into an
 * actual playable session). Mirrors UnoNetMessage.kt's own already-proven shape almost
 * exactly: host-authoritative play, [StateSync] broadcasts a version-stamped snapshot,
 * [Intent] carries a non-host player's attempted move to the host instead of applying it
 * locally, and [RequestState] covers the same startup/reconnect race UnoNetMessage's own
 * KDoc documents (a guest's message listener registering after the host's very first
 * broadcast would otherwise just miss it forever).
 *
 * Tic-Tac-Toe's own [init][TicTacToeGame.init] is fully deterministic (an empty board,
 * player 1 to move, all scores zero -- no shuffle, no hidden information at all), unlike
 * UNO's shuffled deck, so a naive "replicated simulation" (each device runs the same
 * deterministic logic locally in lock-step) would actually stay consistent for this game
 * specifically. Host-authoritative was still the deliberate choice here anyway, not because
 * this game needs it to avoid desync, but for consistency with the one other already-proven
 * real-networked-play pattern in this codebase (UNO's), and because it's strictly more
 * defensive: a stray or buggy peer message can never move the wrong seat, since
 * [TicTacToeGame]'s own network-message handling checks the actual sender's `playerId`
 * against whose turn it currently is before ever applying an [Intent].
 */
@Serializable
data class TicTacToeNetState(
    val board: List<Int>,
    val currentPlayer: Int,
    val roundOver: Boolean,
    val winningLine: List<Int>?,
    val scoreP1: Int,
    val scoreP2: Int,
    val draws: Int,
    val roundNumber: Int,
    val matchOver: Boolean,
    val selectedSymbol: Int
)

@Serializable
sealed class TicTacToeNetMessage {
    /** The host's authoritative state, sent after every host-side mutation (broadcast to
     *  the other player) and also in direct reply to a [RequestState]. [version] is a
     *  monotonically increasing counter (a wire/ordering concern, not game data) -- a
     *  receiver drops any [StateSync] whose version is <= the last one it applied. */
    @Serializable
    data class StateSync(val version: Int, val state: TicTacToeNetState) : TicTacToeNetMessage()

    /** A non-host player's attempted action, sent to the host instead of applied locally. */
    @Serializable
    data class Intent(val intent: TicTacToeIntentPayload) : TicTacToeNetMessage()

    /** Sent by a non-host device right after it registers its message listener, so the
     *  host can (re-)send it the current state directly -- covers the same startup/
     *  reconnect race UnoNetMessage.RequestState's own KDoc documents. */
    @Serializable
    data object RequestState : TicTacToeNetMessage()
}

@Serializable
sealed class TicTacToeIntentPayload {
    @Serializable
    data class CellClicked(val index: Int) : TicTacToeIntentPayload()

    /** A guest's own "Play Again" tap -- deliberately routed through the host rather than
     *  applied locally, same as [CellClicked], so both players' boards stay on the same
     *  round after either one clicks it. */
    @Serializable
    data object PlayAgain : TicTacToeIntentPayload()
}
