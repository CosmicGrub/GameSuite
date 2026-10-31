package com.gamesuite.games.speed

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Speed over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE), two seats. Host-authoritative: the host (seat 0)
 * owns the only engine and the only clock. A guest sends what it WANTS to do ([PlayRequest],
 * [FlipRequest]); the host orders every request, its own included, by the time it arrived, and
 * answers with a [StateSync] (and, for a play that did not happen, a [PlayRejected]). Which seat a
 * request acts for is never in the payload: it is the seat the transport established for the sender.
 *
 * The guest's view is redacted: it never receives the host's hand cards or either draw/replacement
 * pile's contents, only counts.
 */
@Serializable
sealed class SpeedNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: SpeedNetState) : SpeedNetMessage()

    /** "Put card [cardId] (0..51) from my hand on centre pile [pile] (0 left, 1 right)." */
    @Serializable
    data class PlayRequest(val cardId: Int, val pile: Int) : SpeedNetMessage()

    /** "I am stuck: flip the centre cards" (or, in turn-based assist, "I pass"). */
    @Serializable
    data object FlipRequest : SpeedNetMessage()

    /** Host to guest: the last [PlayRequest] did not happen. [reason] is a [SpeedReject] name. */
    @Serializable
    data class PlayRejected(val reason: String, val cardId: Int) : SpeedNetMessage()

    @Serializable
    data object RequestState : SpeedNetMessage()

    @Serializable
    data object RequestPlayAgain : SpeedNetMessage()
}

@Serializable
data class SpeedNetPlayer(val playerId: String, val displayName: String)

/**
 * Cards are their stable 0..51 id. In [hands] a slot is [EMPTY] (no card), [HIDDEN] (a card the
 * receiver may not see: the other seat's hand) or the card id.
 */
@Serializable
data class SpeedNetState(
    val players: List<SpeedNetPlayer>,
    val hands: List<List<Int>>,
    val drawCounts: List<Int>,
    val reserveCounts: List<Int>,
    val pileTops: List<Int>,
    val pileSizes: List<Int>,
    val phase: String,
    val assist: Boolean,
    val assistTurn: Int,
    val flipReady: List<Boolean>,
    val event: String,
    val eventSeat: Int?,
    val eventPile: Int?,
    val eventCardId: Int?,
    val playSerial: Int,
    val flipSerial: Int,
    val dealSerial: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
) {
    companion object {
        const val EMPTY = -1
        const val HIDDEN = -2
    }
}
