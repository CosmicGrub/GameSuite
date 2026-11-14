package com.gamesuite.games.wordgames.tiles

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Word Tiles over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (Nearby = PlayMode.LOCAL_AD_HOC, relay = PlayMode.ONLINE). Host-authoritative like Connect Four's,
 * but with HIDDEN INFORMATION: the host (seat 0) owns the whole state, including the tile bag and
 * every rack, and a guest only ever sees [TileNetState]: its OWN rack plus the COUNT of every other
 * rack and of the bag. The dictionary is local on both devices and never sent.
 */
@Serializable
sealed class TileNetMessage {
    /** The host's authoritative, per-recipient redacted state. A receiver drops anything whose [version] is <= what it applied. */
    @Serializable
    data class StateSync(val version: Int, val state: TileNetState) : TileNetMessage()

    /** A guest's full play: the tiles with their board squares, and the words it believes they form. The host re-checks everything. */
    @Serializable
    data class PlayRequest(val placements: List<TileNetPlacement>, val words: List<String>) : TileNetMessage()

    /** A guest's tile exchange: the instance ids of the rack tiles to swap. */
    @Serializable
    data class SwapRequest(val tileIds: List<Int>) : TileNetMessage()

    /** A guest's pass. */
    @Serializable
    data object PassRequest : TileNetMessage()

    /** Guest to host: send me the current state (startup race, reconnect). */
    @Serializable
    data object RequestState : TileNetMessage()

    /** Guest to host: deal the next game (only honoured while the current game is over). */
    @Serializable
    data object PlayAgainRequest : TileNetMessage()
}

/** One tile put on a board square. [letter] is the letter it stands for (a blank's chosen letter), one character A-Z. */
@Serializable
data class TileNetPlacement(val row: Int, val col: Int, val tileId: Int, val letter: String)

/** A tile on the wire. [letter] is one character: A-Z, or a space for a blank tile's own face. */
@Serializable
data class TileNetTile(val instanceId: Int, val letter: String, val isBlank: Boolean)

/** A committed board square: the tile plus a blank's chosen letter (null for a normal tile). */
@Serializable
data class TileNetCell(val tile: TileNetTile, val letterOverride: String? = null)

/** What the last committed play was worth, so a guest can show the same feedback the host's screen shows. */
@Serializable
data class TileNetLastPlay(val seat: Int, val words: List<String>, val points: Int, val tier: String)

/**
 * Everything one guest may know. Deliberately has NO field that can hold another player's letters
 * or the bag's contents or order: [myRack] belongs to [seat] (the recipient), the other seats are
 * only counts ([rackCounts]), and the bag is only a count ([bagCount]). The roster (names, ids)
 * comes from the guest's own GameContext. [cells] is row-major BOARD_SIZE x BOARD_SIZE.
 */
@Serializable
data class TileNetState(
    val seat: Int,
    val cells: List<TileNetCell?>,
    val scores: List<Int>,
    val rackCounts: List<Int>,
    val myRack: List<TileNetTile>,
    val bagCount: Int,
    val currentPlayerIndex: Int,
    val consecutivePasses: Int,
    val lastAction: String,
    val lastPlayedWords: List<String>,
    val matchOver: Boolean,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val playSeq: Int = 0,
    val lastPlay: TileNetLastPlay? = null
)
