package com.gamesuite.games.minigolf

import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.Serializable

object GolfPhase {
    const val AIM = "aim"
    const val HOLE_DONE = "hole_done"
    const val OVER = "over"
    val ALL = setOf(AIM, HOLE_DONE, OVER)
}

enum class GolfReject { NONE, OVER, NOT_YOUR_TURN, BAD_POWER, BAD_SHOT }

@Serializable
data class GolfSeat(val playerId: String, val displayName: String, val isBot: Boolean, val wins: Int = 0)

/** The last putt, kept in the state so every device can replay it with [GolfPhysics.simulate]. */
@Serializable
data class StrokeRecord(
    val seat: Int,
    val shot: GolfShot,
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    /** A [GolfEnd] value. */
    val end: Int,
    /** 1 when the ball sank in water (a penalty stroke), else 0. */
    val penalty: Int,
    /** True when this putt used up the stroke limit without holing out. */
    val hitLimit: Boolean
)

@Serializable
data class GolfState(
    val seats: List<GolfSeat>,
    val holeCount: Int,
    val limit: Int,
    /** The hole being played, 0-based. */
    val hole: Int,
    /** strokes[seat][hole]: strokes so far on that hole (a water penalty counts). */
    val strokes: List<List<Int>>,
    /** done[seat][hole]: the seat has finished that hole (in the cup, or stopped at the limit). */
    val done: List<List<Boolean>>,
    val ballX: List<Double>,
    val ballY: List<Double>,
    /** The seat to putt next. */
    val current: Int,
    val phase: String,
    val last: StrokeRecord?,
    val strokeNo: Int,
    val serial: Int
) {
    val parTotal: Int get() = (0 until holeCount).sumOf { GolfCourse.holes[it].par }

    /** Strokes on the holes finished so far, for [seat]. */
    fun total(seat: Int): Int = (0 until holeCount).sumOf { if (done[seat][it]) strokes[seat][it] else 0 }

    /** Par of the holes [seat] has finished. */
    fun parSoFar(seat: Int): Int = (0 until holeCount).sumOf { if (done[seat][it]) GolfCourse.holes[it].par else 0 }

    fun allDone(h: Int = hole): Boolean = seats.indices.all { done[it][h] }
}

/** Everything a snapshot or a message from outside must satisfy before the engine will take it. */
fun GolfState.isValid(roster: List<PlayerInfo>? = null): Boolean {
    val n = seats.size
    if (n !in 1..4 || holeCount != 3 && holeCount != 9 || limit !in 3..12) return false
    if (roster != null && (roster.size != n || seats.indices.any { seats[it].playerId != roster[it].playerId })) return false
    if (hole !in 0 until holeCount || current !in 0 until n || phase !in GolfPhase.ALL) return false
    if (strokeNo < 0 || serial < 0 || seats.any { it.wins < 0 || it.wins > 10_000 }) return false
    if (strokes.size != n || done.size != n || ballX.size != n || ballY.size != n) return false
    for (s in 0 until n) {
        if (strokes[s].size != holeCount || done[s].size != holeCount) return false
        for (h in 0 until holeCount) {
            val k = strokes[s][h]
            if (k < 0 || k > limit) return false
            if (done[s][h] && k < 1) return false
            // holes after the current one are untouched
            if (h > hole && (k != 0 || done[s][h])) return false
            // holes before the current one are all finished
            if (h < hole && !done[s][h]) return false
        }
        if (!ballX[s].isFinite() || !ballY[s].isFinite()) return false
        if (!GolfCourse.holes[hole].isPlayable(ballX[s], ballY[s]) && !(done[s][hole] && ballX[s] == GolfCourse.holes[hole].cup.x)) return false
        if (done[s][hole] && strokes[s][hole] < 1) return false
    }
    if (phase == GolfPhase.AIM && (done[current][hole] || allDone())) return false
    if (phase == GolfPhase.HOLE_DONE && !allDone()) return false
    if (phase == GolfPhase.OVER && (hole != holeCount - 1 || !allDone())) return false
    val l = last
    if (l != null) {
        if (l.seat !in 0 until n || !l.shot.isFinite || l.end !in 0..2 || l.penalty !in 0..1) return false
        if (!l.startX.isFinite() || !l.startY.isFinite() || !l.endX.isFinite() || !l.endY.isFinite()) return false
    }
    return true
}
