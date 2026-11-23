package com.gamesuite.games.holdem

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Texas Hold'em over a networked MultiplayerTransport. Host-authoritative: only the host
 * (roster seat 0) shuffles, deals and applies actions; everyone else renders the last [StateSync] it got and
 * turns a tap into an [Act] sent to the host. Each [StateSync] is built per recipient with every hole card the
 * recipient may not see replaced by a placeholder (see holdemRedactFor), and the deck order never leaves the
 * host. An [Act] names no seat: the host uses the transport-stamped sender, so nobody can act for another seat.
 */
@Serializable
sealed class HoldemNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: HoldemState) : HoldemNetMessage()

    @Serializable
    data class Act(val action: HoldemAction) : HoldemNetMessage()

    /** Sent after a device registers its listener, after a reconnect, and by a watcher to join. */
    @Serializable
    data object RequestState : HoldemNetMessage()

    /** Deal the next hand (or, once the tournament is over, start a new one). */
    @Serializable
    data object RequestNextHand : HoldemNetMessage()
}
