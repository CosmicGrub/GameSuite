package com.gamesuite.games.hanafuda

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Hanafuda (Koi-Koi) over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (PlayMode.LOCAL_AD_HOC and PlayMode.ONLINE), two seats. Host-authoritative: the host (seat 0) owns
 * the engine, the shuffle and the deck. A guest only sends INTENTS (play this card, turn up the deck,
 * pick this field card, koi-koi or shobu) and renders the last [StateSync]. Hidden information never
 * leaves the host: every StateSync is the host's table redacted for the receiving seat, so a guest
 * receives its own hand but only placeholders for the host's hand and for the deck.
 * Requests are honoured only from the transport-established sender whose seat is on turn.
 */
@Serializable
sealed class HanafudaNetMessage {
    /** The host's table after every change. [version] only goes up; a receiver drops any that is not newer. */
    @Serializable
    data class StateSync(val version: Int, val state: HanafudaState) : HanafudaNetMessage()

    /** "Play [handCard] from my hand"; [fieldCard] is the field card to take when two match, else -1. */
    @Serializable
    data class PlayHand(val handCard: Int, val fieldCard: Int = -1) : HanafudaNetMessage()

    @Serializable
    data object DrawCard : HanafudaNetMessage()

    /** "The card I turned up takes [fieldCard]." */
    @Serializable
    data class ChooseMatch(val fieldCard: Int) : HanafudaNetMessage()

    @Serializable
    data class Decide(val koiKoi: Boolean) : HanafudaNetMessage()

    @Serializable
    data object NextRound : HanafudaNetMessage()

    @Serializable
    data object RequestState : HanafudaNetMessage()

    @Serializable
    data object RequestPlayAgain : HanafudaNetMessage()
}
