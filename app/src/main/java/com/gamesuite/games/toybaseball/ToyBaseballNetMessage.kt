package com.gamesuite.games.toybaseball

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Toy Baseball over a networked MultiplayerTransport (LOCAL_AD_HOC / ONLINE), two seats,
 * turn-based per pitch. Host-authoritative: the host (seat 0) owns the engine, the dice and the pending
 * pitch. A guest sends what it WANTS to do ([PitchIntent] as the pitcher, [SwingIntent] as the batter);
 * which seat that is for is never in the payload but the seat the transport established for the sender.
 * The pitcher's pending pitch is hidden information: the host only ever puts it in the snapshot it sends to
 * the pitcher's own seat.
 */
@Serializable
sealed class ToyBaseballNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: BaseballState) : ToyBaseballNetMessage()

    /** [type] is a [PitchType] name; [row], [col] the target on the 5x5 grid. */
    @Serializable
    data class PitchIntent(val pitchType: String, val row: Int, val col: Int) : ToyBaseballNetMessage()

    /** [timing] is a [PitchType] name. */
    @Serializable
    data class SwingIntent(val take: Boolean, val row: Int, val col: Int, val timing: String, val power: Boolean) : ToyBaseballNetMessage()

    @Serializable
    data class Rejected(val reason: String) : ToyBaseballNetMessage()

    @Serializable
    data object RequestState : ToyBaseballNetMessage()

    @Serializable
    data object RequestPlayAgain : ToyBaseballNetMessage()
}
