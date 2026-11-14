package com.gamesuite.games.toysoccer

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Toy Soccer over a networked transport (Nearby / Online), 2 to 4 seats.
 * Host-authoritative: the host (seat 0) owns the only simulation and the only clock. A guest sends
 * what it WANTS to do ([FlickRequest]); the seat it acts for is NEVER in the payload, it is the seat
 * the transport established for the sender. The host answers with versioned [StateSync] snapshots
 * (about 15 a second while anything moves, at once on a flick, goal or phase change). Between
 * snapshots a guest carries the same deterministic simulation forward from the last one, so a late
 * packet costs a little smoothness, never fairness. Nothing is hidden information.
 */
@Serializable
sealed class SoccerNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: SoccerNetState) : SoccerNetMessage()

    /** "Flick my figure [figure] (0 = keeper) by this pull vector." */
    @Serializable
    data class FlickRequest(val figure: Int, val pullX: Double, val pullY: Double) : SoccerNetMessage()

    /** Host to guest: the last [FlickRequest] did not happen; [reason] is a [SoccerReject] name. */
    @Serializable
    data class FlickRejected(val reason: String, val figure: Int) : SoccerNetMessage()

    @Serializable
    data object RequestState : SoccerNetMessage()

    @Serializable
    data object RequestPlayAgain : SoccerNetMessage()
}

@Serializable
data class SoccerNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

/** [bodies] is x, y, vx, vy for each body in order (ball first), so its size is 4 * (1 + 2 * figuresPerTeam). */
@Serializable
data class SoccerNetState(
    val players: List<SoccerNetPlayer>,
    val figuresPerTeam: Int,
    val bodies: List<Double>,
    val cooldowns: List<Int>,
    val phase: String,
    val phaseTicks: Int,
    val score: List<Int>,
    val target: Int,
    val ticksLeft: Int,
    val flicksLeft: Int,
    val goldenGoal: Boolean,
    val assist: Boolean,
    val turnTeam: Int,
    val turnFlicked: Boolean,
    val event: String,
    val eventTeam: Int?,
    val flickSerial: Int,
    val goalSerial: Int,
    val kickSerial: Int,
    val wallSerial: Int,
    val bumpSerial: Int,
    val hitStrength: Double,
    val hitX: Double,
    val winnerTeam: Int?,
    val sessionWins: List<Int>,
    val gone: List<Int> = emptyList()
)
