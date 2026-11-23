package com.gamesuite.games.curling

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Curling over a networked MultiplayerTransport. Host-authoritative: the host (seat 0) re-simulates
 * every delivery a guest asks for (the guest's own live animation is only for show) and answers with a [StateSync]
 * carrying the ice before the throw, the throw, and the resulting state. Requests count only from the
 * transport-established sender whose seat is next to throw; no message carries a seat.
 */
@Serializable
sealed class CurlingNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: CurlingNetState) : CurlingNetMessage()

    @Serializable
    data class ThrowRequest(val throwing: CurlingThrow) : CurlingNetMessage()

    @Serializable
    data object RequestState : CurlingNetMessage()

    @Serializable
    data object RequestNextEnd : CurlingNetMessage()

    @Serializable
    data object RequestPlayAgain : CurlingNetMessage()
}

@Serializable
data class CurlingNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

@Serializable
data class CurlingNetStone(val id: Int, val team: Int, val x: Double, val y: Double)

@Serializable
data class CurlingNetState(
    val players: List<CurlingNetPlayer>,
    val stones: List<CurlingNetStone>,
    val end: Int,
    val totalEnds: Int,
    val stonesPerTeam: Int,
    val thrown: List<Int>,
    val hammer: Int,
    val scores: List<Int>,
    val endScores: List<Int>,
    val phase: String,
    val extraEnds: Int,
    val flags: Int,
    val lastSeat: Int?,
    val lastTeam: Int?,
    val lastStoneId: Int?,
    val lastThrow: CurlingThrow?,
    val before: List<CurlingNetStone>,
    val throwSerial: Int,
    val endScorer: Int,
    val endPoints: Int,
    val winnerTeam: Int,
    val sessionWins: Map<String, Int>
)
