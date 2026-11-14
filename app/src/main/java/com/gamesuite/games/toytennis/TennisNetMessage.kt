package com.gamesuite.games.toytennis

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Toy Tennis over a networked transport (Nearby / Online), two seats.
 * Host-authoritative: the host (seat 0) owns the only simulation. A guest sends its swing as an INTENT
 * ([SwingRequest]); the seat it acts for is never in the payload, it is the seat the transport
 * established for the sender.
 *
 * Timing is not left to network luck: the request carries the SIMULATION TICK the guest was showing
 * when it swung (the host's tick as of its last snapshot plus the guest's own extrapolation). The host
 * keeps the last [TennisRules.MAX_REWIND] ticks, puts the swing at that tick (never earlier than the
 * window and never in the future) and replays forward, so a swing is judged against the ball the
 * player SAW. A guest can gain at most that window, never more.
 */
@Serializable
sealed class TennisNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: TennisNetState) : TennisNetMessage()

    /** "Swing: [dirX] -1 (my left) to 1 (my right), [power] 0 to 1, as of simulation tick [tick]." */
    @Serializable
    data class SwingRequest(val tick: Int, val dirX: Double, val power: Double) : TennisNetMessage()

    @Serializable
    data class SwingRejected(val reason: String) : TennisNetMessage()

    @Serializable
    data object RequestState : TennisNetMessage()

    @Serializable
    data object RequestPlayAgain : TennisNetMessage()
}

@Serializable
data class TennisNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

@Serializable
data class TennisNetState(
    val players: List<TennisNetPlayer>,
    val court: TennisState,
    val assist: Boolean,
    val sessionWins: List<Int>
)
