package com.gamesuite.games.fishing

import kotlinx.serialization.Serializable

/**
 * Wire protocol for the Fishing contest over Nearby / Online, 1 to 4 seats. Host-authoritative: the
 * host (seat 0) owns the only engine. The angler on turn sends what the button is doing
 * ([Hold]) and where it aims ([Aim]); the seat is NEVER in the payload, it is the seat the
 * transport established for the sender, and the host ignores anyone who is not on turn. The host
 * answers with versioned [StateSync] snapshots (about 20 a second while a fish is on the line, at once
 * on any event). What the fish is stays hidden until it is hooked. Between snapshots a guest runs the
 * swing meter and the reel-in forward itself, which is chance-free.
 */
@Serializable
sealed class FishingNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: FishNetState) : FishingNetMessage()

    /** "My button is down / up." */
    @Serializable
    data class Hold(val down: Boolean) : FishingNetMessage()

    /** "Aim at this column (0..4)." */
    @Serializable
    data class Aim(val column: Int) : FishingNetMessage()

    @Serializable
    data object RequestState : FishingNetMessage()

    @Serializable
    data object RequestPlayAgain : FishingNetMessage()
}

@Serializable
data class FishNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

@Serializable
data class FishNetCatch(val species: String, val grams: Int)

@Serializable
data class FishNetRush(val start: Int, val len: Int, val power: Int)

@Serializable
data class FishNetState(
    val players: List<FishNetPlayer>,
    val lake: String,
    val rounds: Int,
    val relaxed: Boolean,
    val phase: String,
    val round: Int,
    val turnIndex: Int,
    val phaseTicks: Int,
    val turnTicks: Int,
    val chargeTicks: Int,
    val aimCol: Int,
    val band: Int,
    val power: Double,
    val tension: Double,
    val distance: Double,
    val reelTicks: Int,
    val slackTicks: Int,
    val hold: Boolean,
    val outcome: String,
    val casts: List<Int>,
    val grams: List<Int>,
    val catches: List<List<FishNetCatch>>,
    /** The fish on the line: only sent once it is hooked (REELING) or the turn is over (RESULT). Null before. */
    val hooked: FishNetCatch? = null,
    val rushes: List<FishNetRush> = emptyList(),
    val castSerial: Int, val nibbleSerial: Int, val biteSerial: Int, val hookSerial: Int, val resultSerial: Int, val rushSerial: Int,
    val sessionWins: List<Int>,
    val gone: List<Int> = emptyList()
)
