package com.gamesuite.games.dominoes

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Dominoes over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (Nearby = PlayMode.LOCAL_AD_HOC, relay = PlayMode.ONLINE). Host-authoritative like Connect Four's,
 * with HIDDEN INFORMATION: the host (seat 0) owns the engine, including every hand and the boneyard
 * order. A guest (seat 1) sends requests and renders the last [StateSync] it accepted, and that
 * sync is built per recipient (see [DominoNetState]) so it never carries a tile the recipient
 * could not see at a real table.
 */
@Serializable
sealed class DominoNetMessage {
    /** The host's authoritative view for ONE recipient. [version] only ever grows; a receiver drops anything <= what it applied. */
    @Serializable
    data class StateSync(val version: Int, val state: DominoNetState) : DominoNetMessage()

    /**
     * A guest's "play my tile [tileId] on the left/right end" request. It names the tile by id only
     * (the host looks the pips up in the sender's own hand), so a request can never smuggle in a
     * tile the sender does not hold. The guest never applies it itself.
     */
    @Serializable
    data class PlayRequest(val tileId: Int, val attachToLeft: Boolean) : DominoNetMessage()

    /** A guest's request to draw one tile from the boneyard (only honoured when it has nothing to play). */
    @Serializable
    data object DrawRequest : DominoNetMessage()

    /** A guest's request to pass (only honoured when the boneyard is empty and it has nothing to play). */
    @Serializable
    data object PassRequest : DominoNetMessage()

    /** Guest to host: send me the current state (startup race, reconnect). */
    @Serializable
    data object RequestState : DominoNetMessage()

    /** Guest to host: deal the next hand (only honoured while the current hand is over). */
    @Serializable
    data object PlayAgainRequest : DominoNetMessage()
}

@Serializable
data class DominoNetTile(val a: Int, val b: Int, val id: Int)

@Serializable
data class DominoNetPlaced(val tile: DominoNetTile, val flipped: Boolean)

/**
 * What one recipient may know: the public table (chain, whose turn, scores), THEIR OWN hand, and
 * only the COUNT of every other hand and of the boneyard. The boneyard order and the other hands'
 * tiles are never sent. The roster (names, ids) comes from the guest's own GameContext.
 *
 * [handSizes] has one entry per roster seat (the recipient's own entry equals [myHand].size).
 * [handPips] is null during play; once the hand is over it holds every seat's pip total (needed to
 * show how the hand was scored) but still no tile.
 */
@Serializable
data class DominoNetState(
    val maxPip: Int,
    val chain: List<DominoNetPlaced>,
    val myHand: List<DominoNetTile>,
    val handSizes: List<Int>,
    val boneyardSize: Int,
    val currentPlayerIndex: Int,
    val consecutivePasses: Int,
    val lastAction: String,
    val handOver: Boolean,
    val winnerPlayerId: String?,
    val sessionScores: Map<String, Int>,
    val handsFinished: Int,
    val handPips: List<Int>? = null
)
