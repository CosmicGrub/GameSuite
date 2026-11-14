package com.gamesuite.games.chinesecheckers

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Chinese Checkers over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE). Host-authoritative, 2 to 6 seats: the host (seat 0,
 * localPlayerIndex 0) owns the only real engine and sends every other seat the same [StateSync];
 * a guest forwards a tap pair as [MoveRequest] and renders the last sync it accepted. There is no
 * hidden information, so nothing is redacted. The host accepts a [MoveRequest] only from the
 * transport-established sender whose seat is the one on turn.
 */
@Serializable
sealed class CCNetMessage {
    /** The host's authoritative state after every change. [version] only goes up; stale ones are dropped. */
    @Serializable
    data class StateSync(val version: Int, val state: CCNetState) : CCNetMessage()

    /** A guest asks to move the piece on [from] to [to] (cell indices). The host validates it. */
    @Serializable
    data class MoveRequest(val from: Int, val to: Int) : CCNetMessage()

    /** A guest asks the host to (re-)send the current state: startup race and reconnects. */
    @Serializable
    data object RequestState : CCNetMessage()

    /** A guest asks for a fresh board once the current one is over. */
    @Serializable
    data object RequestPlayAgain : CCNetMessage()
}

@Serializable
data class CCNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

/** Everything a guest needs to draw the table. Move lists are derived from [cells], never sent. */
@Serializable
data class CCNetState(
    val cells: List<Int?>,
    val players: List<CCNetPlayer>,
    val currentPlayerIndex: Int,
    val pieces: Int,
    val lastFrom: Int?,
    val lastTo: Int?,
    val lastMover: Int?,
    val lastHops: Int,
    val skippedSeat: Int?,
    val endReason: String?,
    val turnCount: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    /** Each seat's last move (from * 121 + to, -1 = none): the host's anti-dithering memory, kept so a guest's copy equals the host's. */
    val previous: List<Int> = emptyList(),
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)
