package com.gamesuite.games.sevens

import kotlinx.serialization.Serializable

/**
 * Wire protocol of Sevens over a [com.gamesuite.transport.MultiplayerTransport] (Nearby and Online), 3 to 6
 * seats. Host-authoritative exactly like President: the host (seat 0) shuffles, deals and validates; each
 * recipient gets a [StateSync] in which every hand but its own is placeholders ([sevensRedactFor]). An
 * [Intent] never names a seat: the host acts for the seat whose player id the transport stamped on it.
 */
@Serializable
sealed class SevensNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: SevensState) : SevensNetMessage()

    @Serializable
    data class Intent(val intent: SevensIntent) : SevensNetMessage()

    @Serializable
    data object RequestState : SevensNetMessage()
}

@Serializable
sealed class SevensIntent {
    @Serializable
    data class Play(val cardId: Int) : SevensIntent()

    @Serializable
    data object Pass : SevensIntent()

    /** Deal the next round once the round is over. */
    @Serializable
    data object Continue : SevensIntent()
}
