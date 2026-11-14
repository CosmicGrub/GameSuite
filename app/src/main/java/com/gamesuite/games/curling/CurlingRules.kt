package com.gamesuite.games.curling

import kotlinx.serialization.Serializable

data class CurlingPlayerState(val playerId: String, val displayName: String, val isBot: Boolean, val team: Int)

/** The delivery just played, with the ice it was played on, so every device can replay it. */
data class CurlingThrowRecord(val seat: Int, val team: Int, val stoneId: Int, val throwing: CurlingThrow, val before: List<CurlingStone>)

enum class CurlingPhase { THROWING, END_SCORED, MATCH_OVER }

/** Bit flags describing the last delivery. */
object CurlingFlag {
    const val HOGGED = 1
    const val OUT = 2
    const val HIT = 4
    const val TAKEOUT = 8
}

enum class CurlingReject { NONE, NOT_YOUR_TURN, BAD_THROW, NOT_THROWING }

/**
 * [thrown] counts the stones each team has thrown this end. [endScores] is the points of every finished end as a
 * flat list: team 0's then team 1's for end 1, and so on. [hammer] is the team with the last stone of the end.
 */
data class CurlingState(
    val players: List<CurlingPlayerState>,
    val stones: List<CurlingStone>,
    val end: Int,
    val totalEnds: Int,
    val stonesPerTeam: Int,
    val thrown: List<Int>,
    val hammer: Int,
    val scores: List<Int>,
    val endScores: List<Int> = emptyList(),
    val phase: CurlingPhase = CurlingPhase.THROWING,
    val extraEnds: Int = 0,
    val flags: Int = 0,
    val last: CurlingThrowRecord? = null,
    val throwSerial: Int = 0,
    /** The team that scored the end just finished (-1 blank) and its points. */
    val endScorer: Int = -1,
    val endPoints: Int = 0,
    /** -1 draw / not decided. */
    val winnerTeam: Int = -1,
    val shotAtMillis: Long = 0L
) {
    val seats: Int get() = players.size
    val throwsDone: Int get() = thrown[0] + thrown[1]
    val endComplete: Boolean get() = thrown[0] >= stonesPerTeam && thrown[1] >= stonesPerTeam
    val nonHammer: Int get() = 1 - hammer

    /** The team delivering next: the team without the hammer first, then alternately; the hammer throws the last stone. */
    val teamToThrow: Int get() = if (throwsDone % 2 == 0) nonHammer else hammer

    /** The seat delivering next: a team of two shares its stones, two each in turn. */
    val seatToThrow: Int get() {
        val members = players.indices.filter { players[it].team == teamToThrow }
        return members[(thrown[teamToThrow] / 2) % members.size]
    }

    fun stoneNumber(team: Int) = thrown[team] + 1
}

object CurlingRules {
    const val MAX_EXTRA_ENDS = 3

    fun validSeatCount(n: Int) = n in 2..4

    fun teamOf(seat: Int) = seat % 2

    fun newMatch(
        players: List<CurlingPlayerState>, totalEnds: Int, stonesPerTeam: Int, firstThrowerTeam: Int, millis: Long
    ) = CurlingState(
        players = players, stones = emptyList(), end = 1, totalEnds = totalEnds, stonesPerTeam = stonesPerTeam,
        thrown = listOf(0, 0), hammer = 1 - firstThrowerTeam, scores = listOf(0, 0), shotAtMillis = millis
    )

    fun explain(s: CurlingState, seat: Int, t: CurlingThrow): CurlingReject {
        if (s.phase != CurlingPhase.THROWING) return CurlingReject.NOT_THROWING
        if (seat != s.seatToThrow) return CurlingReject.NOT_YOUR_TURN
        if (!CurlingSheet.inRange(t)) return CurlingReject.BAD_THROW
        return CurlingReject.NONE
    }

    /** Points a team would score if the end finished with these [stones]; the other team scores 0. Returns (team, points) or (-1, 0). */
    fun scoreEnd(stones: List<CurlingStone>): Pair<Int, Int> {
        val inHouse = stones.filter { CurlingSheet.inHouse(it.x, it.y) }
        if (inHouse.isEmpty()) return -1 to 0
        fun d(s: CurlingStone) = CurlingSheet.distanceToButton(s.x, s.y)
        val sorted = inHouse.sortedBy { d(it) }
        val first = sorted[0]
        val bestOther = sorted.firstOrNull { it.team != first.team }
        if (bestOther != null && kotlin.math.abs(d(bestOther) - d(first)) < 1e-6) return -1 to 0
        val limit = bestOther?.let { d(it) } ?: Double.MAX_VALUE
        val pts = inHouse.count { it.team == first.team && d(it) < limit }
        return first.team to pts
    }

    class Outcome(val state: CurlingState, val sim: CurlingSimResult)

    fun play(s: CurlingState, t: CurlingThrow, millis: Long, record: Boolean = false): Outcome {
        val team = s.teamToThrow
        val id = team * 8 + s.thrown[team]
        val sim = CurlingPhysics.simulate(s.stones, team, id, t, record)
        return Outcome(resolve(s, t, sim, millis), sim)
    }

    fun resolve(s: CurlingState, t: CurlingThrow, sim: CurlingSimResult, millis: Long): CurlingState {
        val team = s.teamToThrow
        val id = team * 8 + s.thrown[team]
        var flags = 0
        if (sim.hogged) flags = flags or CurlingFlag.HOGGED
        else if (!sim.thrownInPlay) flags = flags or CurlingFlag.OUT
        if (sim.hitSomething) flags = flags or CurlingFlag.HIT
        if (sim.removedIds.any { rid -> s.stones.firstOrNull { it.id == rid }?.team == 1 - team }) flags = flags or CurlingFlag.TAKEOUT
        val thrown = s.thrown.toMutableList().also { it[team] = it[team] + 1 }
        var next = s.copy(
            stones = sim.stones, thrown = thrown, flags = flags, throwSerial = s.throwSerial + 1, shotAtMillis = millis,
            last = CurlingThrowRecord(s.seatToThrow, team, id, t, s.stones), endScorer = -1, endPoints = 0
        )
        if (next.endComplete) {
            val (scorer, points) = scoreEnd(next.stones)
            val scores = next.scores.toMutableList()
            if (scorer >= 0) scores[scorer] += points
            val ended = next.endScores + listOf(if (scorer == 0) points else 0, if (scorer == 1) points else 0)
            val lastScheduled = next.end >= next.totalEnds
            val tied = scores[0] == scores[1]
            val over = lastScheduled && (!tied || next.extraEnds >= MAX_EXTRA_ENDS)
            next = next.copy(
                scores = scores, endScores = ended, endScorer = scorer, endPoints = points,
                phase = if (over) CurlingPhase.MATCH_OVER else CurlingPhase.END_SCORED,
                winnerTeam = if (over) (if (scores[0] > scores[1]) 0 else if (scores[1] > scores[0]) 1 else -1) else -1
            )
        }
        return next
    }

    /** Starts the next end after a scored one: the team that did not score gets the hammer; a blank end keeps it. */
    fun nextEnd(s: CurlingState): CurlingState {
        if (s.phase != CurlingPhase.END_SCORED) return s
        val hammer = if (s.endScorer == -1) s.hammer else 1 - s.endScorer
        val extra = if (s.end >= s.totalEnds) s.extraEnds + 1 else s.extraEnds
        return s.copy(
            stones = emptyList(), end = s.end + 1, thrown = listOf(0, 0), hammer = hammer, phase = CurlingPhase.THROWING,
            extraEnds = extra, flags = 0, last = null, endScorer = -1, endPoints = 0
        )
    }

    /** A value for [team] of how the ice stands: end-score margin if it ended now, plus how close each side is to the button. */
    fun evaluate(stones: List<CurlingStone>, team: Int): Double {
        val (scorer, pts) = scoreEnd(stones)
        var v = if (scorer == team) 10.0 * pts else if (scorer >= 0) -10.0 * pts else 0.0
        fun best(tm: Int) = stones.filter { it.team == tm }.minOfOrNull { CurlingSheet.distanceToButton(it.x, it.y) } ?: 400.0
        v += (best(1 - team) - best(team)) / 25.0
        // stones in the house are worth something even when not counting, guards a little
        v += 0.6 * stones.count { it.team == team && CurlingSheet.inHouse(it.x, it.y) }
        v -= 0.6 * stones.count { it.team != team && CurlingSheet.inHouse(it.x, it.y) }
        v += 0.25 * stones.count { it.team == team && it.y < CurlingSheet.TEE_Y - CurlingSheet.HOUSE_R && kotlin.math.abs(it.x) < 90 }
        return v
    }
}
