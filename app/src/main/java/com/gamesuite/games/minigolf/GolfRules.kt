package com.gamesuite.games.minigolf

/** What a hole score is called. The screens map each name to text. */
enum class GolfScoreName { HOLE_IN_ONE, ALBATROSS, EAGLE, BIRDIE, PAR, BOGEY, DOUBLE_BOGEY, TRIPLE_BOGEY, OVER }

/** Scoring, turn order and the stroke limit, as pure functions so they are tested with known values. */
object GolfRules {
    /** Name of a finished hole: [strokes] taken on a hole of par [par]. */
    fun scoreName(strokes: Int, par: Int): GolfScoreName {
        if (strokes == 1) return GolfScoreName.HOLE_IN_ONE
        return when (strokes - par) {
            -3 -> GolfScoreName.ALBATROSS
            -2 -> GolfScoreName.EAGLE
            -1 -> GolfScoreName.BIRDIE
            0 -> GolfScoreName.PAR
            1 -> GolfScoreName.BOGEY
            2 -> GolfScoreName.DOUBLE_BOGEY
            3 -> GolfScoreName.TRIPLE_BOGEY
            else -> if (strokes - par < -3) GolfScoreName.ALBATROSS else GolfScoreName.OVER
        }
    }

    /** "+2", "-1" or "E" for level par. */
    fun relativeText(strokes: Int, par: Int): String {
        val d = strokes - par
        return if (d == 0) "E" else if (d > 0) "+$d" else "$d"
    }

    /**
     * The seats that win a round: the unique lowest total. A tie for the lowest is a draw (empty list); a
     * solo round wins at or under par plus one stroke a hole (bogey golf).
     */
    fun winners(totals: List<Int>, parTotal: Int, holeCount: Int): List<Int> {
        if (totals.size == 1) return if (totals[0] <= parTotal + holeCount) listOf(0) else emptyList()
        val best = totals.min()
        val leaders = totals.indices.filter { totals[it] == best }
        return if (leaders.size == 1) leaders else emptyList()
    }

    /** The seat to putt after [seat], in rotation, skipping seats that have finished the hole; -1 when everyone has. */
    fun nextToPutt(seat: Int, done: List<Boolean>): Int {
        val n = done.size
        for (k in 1..n) {
            val c = (seat + k) % n
            if (!done[c]) return c
        }
        return -1
    }

    /** Who putts first on the next hole: the lowest score on the hole just finished (the first seat among ties). */
    fun honor(strokesOnHole: List<Int>): Int = strokesOnHole.indices.minByOrNull { strokesOnHole[it] } ?: 0

    /** Strokes after one more putt (+1 more for a water penalty), never beyond [limit]. */
    fun addStroke(current: Int, penalty: Int, limit: Int): Int = minOf(limit, current + 1 + penalty)
}
