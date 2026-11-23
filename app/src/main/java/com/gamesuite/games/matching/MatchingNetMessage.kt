package com.gamesuite.games.matching

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Matching over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE), two to four seats. Host-authoritative: the host
 * (seat 0) holds the only engine and therefore the only copy of where every picture is. A guest can
 * only ask to flip a card ([FlipRequest]); the host checks that the asker is the player on turn,
 * applies it and sends every guest a [StateSync] in which a face-down card's picture is hidden.
 * A guest therefore learns a card's face only when it is turned up.
 */
@Serializable
sealed class MatchingNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: MatchingNetState) : MatchingNetMessage()

    /** "Turn card [index] over." Honoured only from the seat whose turn it is. */
    @Serializable
    data class FlipRequest(val index: Int) : MatchingNetMessage()

    @Serializable
    data object RequestState : MatchingNetMessage()

    @Serializable
    data object RequestPlayAgain : MatchingNetMessage()
}

@Serializable
data class MatchingNetPlayer(val playerId: String, val displayName: String)

@Serializable
data class MatchingNetState(
    val players: List<MatchingNetPlayer>,
    val cardCount: Int,
    /** The picture on each card, or [HIDDEN] for a face-down card (the guest must not know it). */
    val faces: List<Int>,
    val status: List<Int>,
    val owner: List<Int>,
    val currentPlayerIndex: Int,
    val scores: List<Int>,
    val moves: List<Int>,
    val resolving: Boolean,
    val event: String,
    val eventCards: List<Int>,
    val eventSeat: Int?,
    val flipSerial: Int,
    val resolveSerial: Int,
    val dealSerial: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val tie: Boolean,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
) {
    companion object {
        const val HIDDEN = -1
    }
}
