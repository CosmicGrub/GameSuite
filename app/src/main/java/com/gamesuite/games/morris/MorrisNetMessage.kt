package com.gamesuite.games.morris

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Nine Men's Morris over a networked MultiplayerTransport (PlayMode.LOCAL_AD_HOC
 * and PlayMode.ONLINE). Host-authoritative, same shape as ReversiNetMessage: the host (seat 0) owns
 * the only real engine; the guest (seat 1) renders the last [StateSync] it applied and forwards a
 * whole turn as [PlayMove]. There is no hidden information, so the full state goes to everyone.
 */
@Serializable
sealed class MorrisNetMessage {
    /** The host's authoritative state after every change; receivers drop any version not newer than the last applied. */
    @Serializable
    data class StateSync(val version: Int, val state: MorrisNetState) : MorrisNetMessage()

    /** A guest's turn: place at [to] (from = -1), or slide/fly, with the [remove] pick when it closes a mill. The host validates it. */
    @Serializable
    data class PlayMove(val from: Int, val to: Int, val remove: Int = -1) : MorrisNetMessage()

    @Serializable
    data object RequestState : MorrisNetMessage()

    @Serializable
    data object RequestPlayAgain : MorrisNetMessage()
}

@Serializable
data class MorrisNetPlayer(val playerId: String, val displayName: String)

/** Everything a guest needs to draw the table; legal moves are derived from the rest, never sent. */
@Serializable
data class MorrisNetState(
    val points: List<Int?>,
    val players: List<MorrisNetPlayer>,
    val currentPlayerIndex: Int,
    val hand: List<Int>,
    val quietPlies: Int,
    val ply: Int,
    val men: Int,
    val flying: Boolean,
    val lastMove: MorrisMove?,
    val lastMover: Int,
    val lastAction: String,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val endReason: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)
