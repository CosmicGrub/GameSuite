package com.gamesuite.games.toytennis

import kotlinx.serialization.Serializable

/**
 * How a Toy Tennis match is scored. The short mode ([sets] false) is first to [pointsTarget] points,
 * two clear (capped at target + 3 so it always ends). [sets] true is real tennis scoring: points 0,
 * 15, 30, 40 and advantage (or sudden death at deuce with [noAd]), games, sets of [gamesPerSet] games
 * with a tie-break to 7 at "all", and [setsToWin] sets to take the match.
 */
@Serializable
data class TennisFormat(
    val sets: Boolean,
    val pointsTarget: Int = 7,
    val gamesPerSet: Int = 6,
    val setsToWin: Int = 1,
    val noAd: Boolean = false
) {
    companion object {
        val POINTS7 = TennisFormat(sets = false, pointsTarget = 7)
        val POINTS11 = TennisFormat(sets = false, pointsTarget = 11)
        val SET4 = TennisFormat(sets = true, gamesPerSet = 4, setsToWin = 1, noAd = true)
        val SET6 = TennisFormat(sets = true, gamesPerSet = 6, setsToWin = 1, noAd = false)
        val BEST3 = TennisFormat(sets = true, gamesPerSet = 4, setsToWin = 2, noAd = true)

        const val TIEBREAK_TO = 7

        /** "points7", "points11", "set4", "set6", "best3"; anything else is the 7-point game. */
        fun parse(id: String?): TennisFormat = when (id) {
            "points11" -> POINTS11
            "set4" -> SET4
            "set6" -> SET6
            "best3" -> BEST3
            else -> POINTS7
        }
    }
}

/** Immutable score of a match between side 0 and side 1; [pointWonBy] returns the next score. */
@Serializable
data class TennisScore(
    val format: TennisFormat,
    val firstServer: Int = 0,
    /** Points in the current game / tie-break / (short mode) the whole match. */
    val pointsA: Int = 0,
    val pointsB: Int = 0,
    val gamesA: Int = 0,
    val gamesB: Int = 0,
    val setsA: Int = 0,
    val setsB: Int = 0,
    val finishedSetsA: List<Int> = emptyList(),
    val finishedSetsB: List<Int> = emptyList(),
    val tiebreak: Boolean = false,
    /** Who served the current game (for a tie-break: who served its first point). */
    val gameServer: Int = firstServer,
    val totalPoints: Int = 0,
    val winner: Int = -1
) {
    val over: Boolean get() = winner >= 0

    /** Who serves the next point. */
    val server: Int
        get() = if (!format.sets) {
            // Like a tie-break: the first server serves one point, then each side serves two in turn.
            firstServer xor (((totalPoints + 1) / 2) % 2)
        } else if (tiebreak) {
            gameServer xor (((pointsA + pointsB + 1) / 2) % 2)
        } else gameServer

    /** 0 serves from the right-hand (deuce) court, 1 from the left (advantage) court. */
    val serveSide: Int get() = (pointsA + pointsB) % 2

    fun points(side: Int): Int = if (side == 0) pointsA else pointsB
    fun games(side: Int): Int = if (side == 0) gamesA else gamesB
    fun sets(side: Int): Int = if (side == 0) setsA else setsB

    /** What a scoreboard shows for a side's points: "0" "15" "30" "40" "Ad", or plain numbers (short mode, tie-break). */
    fun pointText(side: Int): String {
        if (!format.sets || tiebreak) return points(side).toString()
        val me = points(side); val you = points(1 - side)
        if (me >= 3 && you >= 3 && !format.noAd) return if (me > you) "Ad" else "40"
        return when (me) { 0 -> "0"; 1 -> "15"; 2 -> "30"; else -> "40" }
    }

    /** True at 40-40 with advantage rules. */
    val isDeuce: Boolean get() = format.sets && !tiebreak && !format.noAd && pointsA >= 3 && pointsA == pointsB

    fun pointWonBy(side: Int): TennisScore {
        if (over) return this
        val a = if (side == 0) pointsA + 1 else pointsA
        val b = if (side == 1) pointsB + 1 else pointsB
        val total = totalPoints + 1
        if (!format.sets) {
            return copy(pointsA = a, pointsB = b, totalPoints = total, winner = pointsWinner(a, b, format.pointsTarget))
        }
        if (tiebreak) {
            val w = pointsWinner(a, b, TennisFormat.TIEBREAK_TO, cap = false)
            return if (w >= 0) finishGame(w, total, tieBreakWin = true) else copy(pointsA = a, pointsB = b, totalPoints = total)
        }
        val gw = if (format.noAd) {
            // Sudden death at 40-40: the point played there decides, so first to 4 wins.
            if (a >= 4) 0 else if (b >= 4) 1 else -1
        } else when {
            a >= 4 && a - b >= 2 -> 0
            b >= 4 && b - a >= 2 -> 1
            else -> -1
        }
        return if (gw >= 0) finishGame(gw, total, tieBreakWin = false) else copy(pointsA = a, pointsB = b, totalPoints = total)
    }

    private fun finishGame(gw: Int, total: Int, tieBreakWin: Boolean): TennisScore {
        val g = format.gamesPerSet
        val ga = gamesA + if (gw == 0) 1 else 0
        val gb = gamesB + if (gw == 1) 1 else 0
        val setWinner = when {
            tieBreakWin -> gw
            ga >= g && ga - gb >= 2 -> 0
            gb >= g && gb - ga >= 2 -> 1
            else -> -1
        }
        val nextServer = 1 - gameServer
        if (setWinner < 0) {
            return copy(
                pointsA = 0, pointsB = 0, gamesA = ga, gamesB = gb, tiebreak = ga == g && gb == g,
                gameServer = nextServer, totalPoints = total
            )
        }
        val sa = setsA + if (setWinner == 0) 1 else 0
        val sb = setsB + if (setWinner == 1) 1 else 0
        val matchWinner = if (sa >= format.setsToWin) 0 else if (sb >= format.setsToWin) 1 else -1
        return copy(
            pointsA = 0, pointsB = 0, gamesA = 0, gamesB = 0, setsA = sa, setsB = sb,
            finishedSetsA = finishedSetsA + ga, finishedSetsB = finishedSetsB + gb,
            tiebreak = false, gameServer = nextServer, totalPoints = total, winner = matchWinner
        )
    }

    /** The side that wins the game (or, in the short mode, the match) with the next point, or -1. */
    fun gamePointFor(): Int {
        if (over) return -1
        for (s in 0..1) {
            val n = pointWonBy(s)
            val gameDone = if (format.sets) n.pointsA + n.pointsB == 0 && n.totalPoints > totalPoints else n.over
            if (gameDone) return s
        }
        return -1
    }

    companion object {
        /** Winner of a race to [target] points, two clear; with [cap] the first to target + 3 wins regardless. */
        fun pointsWinner(a: Int, b: Int, target: Int, cap: Boolean = true): Int {
            if (a >= target && a - b >= 2) return 0
            if (b >= target && b - a >= 2) return 1
            if (cap) {
                if (a >= target + 3) return 0
                if (b >= target + 3) return 1
            }
            return -1
        }
    }
}
