package com.gamesuite.games.dotsandboxes

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Dots and Boxes over a networked
 * [com.gamesuite.transport.MultiplayerTransport] (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE).
 * Two seats: the host is seat 0 and owns the only real engine; the guest is seat 1 and is a
 * renderer of the last [StateSync] plus a forwarder of its own taps as [DrawEdge] requests.
 *
 * Host-authoritative on purpose: the host validates every request (sender must be the seat whose
 * turn it is, edge undrawn, board not over), applies it through the same engine the local modes
 * use (completing a box keeps the turn) and broadcasts the result with an incrementing version.
 */
@Serializable
sealed class DotsAndBoxesNetMessage {
    /**
     * The host's authoritative state, as the engine's own snapshot JSON ([snapshot]), sent after
     * every mutation and directly to one guest in answer to [RequestState]. A receiver drops any
     * StateSync whose [version] is <= the last one it applied.
     */
    @Serializable
    data class StateSync(val version: Int, val snapshot: String) : DotsAndBoxesNetMessage()

    /** A guest's request to draw one edge, addressed exactly like DotsAndBoxesGame.drawHorizontalEdge/drawVerticalEdge. */
    @Serializable
    data class DrawEdge(val horizontal: Boolean, val row: Int, val col: Int) : DotsAndBoxesNetMessage()

    /** Sent by a guest after it registers its listener (and after a reconnect) so the host re-sends the current state. */
    @Serializable
    data object RequestState : DotsAndBoxesNetMessage()

    /** Either seat asks for the next board; honoured by the host only while the current board is over. */
    @Serializable
    data object PlayAgain : DotsAndBoxesNetMessage()
}
