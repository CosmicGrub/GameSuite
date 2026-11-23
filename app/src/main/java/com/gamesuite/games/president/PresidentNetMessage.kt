package com.gamesuite.games.president

import kotlinx.serialization.Serializable

/**
 * Wire protocol of President over a [com.gamesuite.transport.MultiplayerTransport] (Nearby and Online),
 * 3 to 6 seats. Host-authoritative: the host (seat 0) shuffles, deals, validates every move and answers
 * each change with a [StateSync] built for each recipient; hands other than the recipient's own are
 * replaced by placeholders (see [presidentRedactFor]), so a modified client cannot read them.
 *
 * An [Intent] never names a seat. The host acts for the seat whose player id the transport stamped on
 * the message, so a guest cannot move for someone else, and a watcher (no seat) cannot move at all.
 */
@Serializable
sealed class PresidentNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: PresidentState) : PresidentNetMessage()

    @Serializable
    data class Intent(val intent: PresidentIntent) : PresidentNetMessage()

    /** Sent by a guest (or a watcher) on joining, after a reconnect, or when it suspects it missed a sync. */
    @Serializable
    data object RequestState : PresidentNetMessage()
}

@Serializable
sealed class PresidentIntent {
    @Serializable
    data class Play(val cardIds: List<Int>) : PresidentIntent()

    @Serializable
    data object Pass : PresidentIntent()

    /** Hands these cards to the seat this seat owes after the exchange. */
    @Serializable
    data class Give(val cardIds: List<Int>) : PresidentIntent()

    /** Deal the next round (round over) or, once the session is over, start a new session. */
    @Serializable
    data object Continue : PresidentIntent()
}
