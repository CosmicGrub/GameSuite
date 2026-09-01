package com.gamesuite.games.uno

import kotlinx.serialization.Serializable

/**
 * Wire protocol for UNO over a networked [com.gamesuite.transport.MultiplayerTransport]
 * (currently: NearbyConnectionsTransport). UNO is played **host-authoritative**: only
 * the host (always [com.gamesuite.core.GameContext.localPlayerIndex] == 0 by the lobby's
 * own convention) ever runs the real rules engine (shuffles, deals, validates, mutates
 * `UnoState`). Every other device is a pure renderer of whatever [StateSync] it last
 * received, plus a local-input forwarder that turns a tap into an [Intent] sent to the
 * host instead of mutating anything itself.
 *
 * This design is deliberate, not incidental: `dealNewRound()` shuffles a deck with each
 * device's own local RNG. If every device ran the engine independently (a "replicated
 * simulation" design), each would deal itself a *different* random hand for "the same"
 * logical game — an instant, silent desync. Host-authoritative + full-state broadcast
 * sidesteps that entirely: there is exactly one shuffle, exactly one source of truth, and
 * every non-host device is trivially consistent with it because it never computes anything,
 * only displays the last [StateSync] it has.
 */
@Serializable
sealed class UnoNetMessage {
    /**
     * The host's authoritative state, sent after every mutation (broadcast to everyone)
     * and also sent directly to one player in response to their [RequestState] (handles
     * a late/slow listener registration — see UnoGame's KDoc on the join race).
     * [version] is a monotonically increasing counter (not a UnoState field, since it's a
     * wire/ordering concern, not game data) — a receiver drops any StateSync whose version
     * is <= the last one it applied, since Nearby's payload delivery is reliable but not
     * guaranteed in-order across all mediums.
     */
    @Serializable
    data class StateSync(val version: Int, val state: UnoState) : UnoNetMessage()

    /** A non-host player's attempted move, sent to the host instead of applied locally. */
    @Serializable
    data class Intent(val intent: UnoIntentPayload) : UnoNetMessage()

    /**
     * Sent by a non-host device right after it registers its message listener, so the
     * host can (re-)send it the current state directly. Covers the startup race where a
     * guest's UI hasn't finished mounting (and registering onMessageReceived) by the time
     * the host deals and broadcasts the very first StateSync — without this, that message
     * would just be dropped with nothing to receive it, and the guest would sit on a
     * permanently-null state forever.
     */
    @Serializable
    data object RequestState : UnoNetMessage()
}

@Serializable
sealed class UnoIntentPayload {
    @Serializable
    data class PlayCard(val playerIndex: Int, val cardInstanceId: Int) : UnoIntentPayload()

    @Serializable
    data class ChooseColor(val color: UnoColor) : UnoIntentPayload()

    @Serializable
    data class ResolveChallenge(val accept: Boolean) : UnoIntentPayload()

    @Serializable
    data class JumpIn(val playerIndex: Int, val cardInstanceId: Int) : UnoIntentPayload()

    @Serializable
    data class DrawCard(val playerIndex: Int) : UnoIntentPayload()

    @Serializable
    data class CallUno(val playerIndex: Int) : UnoIntentPayload()

    @Serializable
    data class CatchUnoFailure(val accuserIndex: Int, val targetIndex: Int) : UnoIntentPayload()
}
