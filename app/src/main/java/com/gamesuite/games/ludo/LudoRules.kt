package com.gamesuite.games.ludo

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * Ludo geometry and pure rules.
 *
 * A token's PROGRESS says where it is on its own journey: -1 in the yard, 0..50 on the shared
 * 52-square ring (progress 0 is its colour's start square, the ring square is `(start + progress) % 52`),
 * 51..55 in its private home column and 56 finished. Positions of a whole table are one IntArray,
 * `positions[seat * 4 + token]`.
 *
 * Capture: a token that lands on a ring square that is not safe sends every opposing token there
 * back to its yard. Start squares and the star squares 8 ahead of them (0, 8, 13, 21, 26, 34, 39, 47)
 * are safe: tokens of different colours share them. There is no blockade rule; same-colour tokens
 * may stack.
 */
object LudoBoard {
    const val TRACK = 52
    const val TOKENS = 4
    const val YARD = -1
    const val LAST_TRACK_PROGRESS = 50
    const val FINISHED = 56
    const val MIN_SEATS = 2
    const val MAX_SEATS = 4

    val SAFE: Set<Int> = setOf(0, 8, 13, 21, 26, 34, 39, 47)

    /** Board colour (0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left) of each seat at a table of [seats]. */
    fun colorsFor(seats: Int): List<Int> = when (seats) {
        2 -> listOf(0, 2)
        3 -> listOf(0, 1, 2)
        4 -> listOf(0, 1, 2, 3)
        else -> throw IllegalArgumentException("Ludo needs 2 to 4 seats, got $seats")
    }

    fun validSeatCount(seats: Int) = seats in MIN_SEATS..MAX_SEATS

    fun startSquare(color: Int) = color * 13

    /** The ring square of a token with [progress] (0..50) of [color], or null when it is off the ring. */
    fun ringSquare(color: Int, progress: Int): Int? =
        if (progress in 0..LAST_TRACK_PROGRESS) (startSquare(color) + progress) % TRACK else null

    fun isSafe(square: Int) = square in SAFE

    /** (row, col) on the 15x15 drawing grid of each of the 52 ring squares, clockwise from colour 0's start. */
    val RING: List<Pair<Int, Int>> = buildList {
        for (c in 1..5) add(6 to c)
        for (rr in 5 downTo 0) add(rr to 6)
        add(0 to 7); add(0 to 8)
        for (rr in 1..5) add(rr to 8)
        for (c in 9..14) add(6 to c)
        add(7 to 14); add(8 to 14)
        for (c in 13 downTo 9) add(8 to c)
        for (rr in 9..14) add(rr to 8)
        add(14 to 7); add(14 to 6)
        for (rr in 13 downTo 9) add(rr to 6)
        for (c in 5 downTo 0) add(8 to c)
        add(7 to 0); add(6 to 0)
    }

    /** Grid cell of home-column step [progress] (51..55) of [color]. */
    fun homeColumnCell(color: Int, progress: Int): Pair<Int, Int> {
        val i = progress - 51 // 0..4
        return when (color) {
            0 -> 7 to (1 + i)
            1 -> (1 + i) to 7
            2 -> 7 to (13 - i)
            else -> (13 - i) to 7
        }
    }

    /** Grid cell where finished tokens of [color] rest (the coloured triangle at the centre). */
    fun finishCell(color: Int): Pair<Int, Int> = when (color) {
        0 -> 7 to 6
        1 -> 6 to 7
        2 -> 7 to 8
        else -> 8 to 7
    }

    /** Grid cell of yard slot [token] (0..3) of [color]. */
    fun yardCell(color: Int, token: Int): Pair<Int, Int> {
        val baseR = if (color == 0 || color == 1) 1 else 10
        val baseC = if (color == 0 || color == 3) 1 else 10
        return (baseR + 1 + (token / 2) * 2) to (baseC + 1 + (token % 2) * 2)
    }

    /** Grid cell of a token at [progress] for [color]; yard tokens use their slot. */
    fun cellOf(color: Int, token: Int, progress: Int): Pair<Int, Int> = when {
        progress < 0 -> yardCell(color, token)
        progress <= LAST_TRACK_PROGRESS -> RING[ringSquare(color, progress)!!]
        progress < FINISHED -> homeColumnCell(color, progress)
        else -> finishCell(color)
    }
}

/** The outcome of moving one token. */
data class LudoMoveResult(
    val positions: IntArray,
    val from: Int,
    val to: Int,
    /** (seat, token) pairs sent back to the yard. */
    val captured: List<Pair<Int, Int>>,
    val finished: Boolean,
    val leftYard: Boolean
)

object LudoRules {
    /** Where a token at [progress] lands on [roll], or null when it cannot move. */
    fun destination(progress: Int, roll: Int, needSix: Boolean, exactFinish: Boolean): Int? {
        if (roll !in 1..6) return null
        if (progress == LudoBoard.FINISHED) return null
        if (progress == LudoBoard.YARD) return if (roll == 6 || !needSix) 0 else null
        val target = progress + roll
        if (target > LudoBoard.FINISHED) return if (exactFinish) null else LudoBoard.FINISHED
        return target
    }

    fun legalTokens(positions: IntArray, seat: Int, roll: Int, needSix: Boolean, exactFinish: Boolean): List<Int> =
        (0 until LudoBoard.TOKENS).filter {
            destination(positions[seat * LudoBoard.TOKENS + it], roll, needSix, exactFinish) != null
        }

    /** Moves [token] of [seat]; [colors] maps seats to board colours. The move must be legal. */
    fun apply(
        positions: IntArray, colors: List<Int>, seat: Int, token: Int, roll: Int,
        needSix: Boolean, exactFinish: Boolean
    ): LudoMoveResult {
        val idx = seat * LudoBoard.TOKENS + token
        val from = positions[idx]
        val to = requireNotNull(destination(from, roll, needSix, exactFinish)) { "illegal Ludo move" }
        val out = positions.copyOf()
        out[idx] = to
        val captured = ArrayList<Pair<Int, Int>>()
        val square = LudoBoard.ringSquare(colors[seat], to)
        if (square != null && !LudoBoard.isSafe(square)) {
            for (other in colors.indices) {
                if (other == seat) continue
                for (t in 0 until LudoBoard.TOKENS) {
                    val p = out[other * LudoBoard.TOKENS + t]
                    if (LudoBoard.ringSquare(colors[other], p) == square) {
                        out[other * LudoBoard.TOKENS + t] = LudoBoard.YARD
                        captured += other to t
                    }
                }
            }
        }
        return LudoMoveResult(out, from, to, captured, to == LudoBoard.FINISHED, from == LudoBoard.YARD)
    }

    fun hasWon(positions: IntArray, seat: Int): Boolean =
        (0 until LudoBoard.TOKENS).all { positions[seat * LudoBoard.TOKENS + it] == LudoBoard.FINISHED }

    fun finishedCount(positions: IntArray, seat: Int): Int =
        (0 until LudoBoard.TOKENS).count { positions[seat * LudoBoard.TOKENS + it] == LudoBoard.FINISHED }

    /** A position no legal play can reach is rejected on restore: opposing tokens never share an unsafe ring square. */
    fun isConsistent(positions: IntArray, colors: List<Int>): Boolean {
        if (positions.size != colors.size * LudoBoard.TOKENS) return false
        if (positions.any { it < LudoBoard.YARD || it > LudoBoard.FINISHED }) return false
        val owner = HashMap<Int, Int>()
        for (seat in colors.indices) for (t in 0 until LudoBoard.TOKENS) {
            val sq = LudoBoard.ringSquare(colors[seat], positions[seat * LudoBoard.TOKENS + t]) ?: continue
            if (LudoBoard.isSafe(sq)) continue
            val prev = owner.putIfAbsent(sq, seat)
            if (prev != null && prev != seat) return false
        }
        return true
    }
}

/**
 * The computer player. Every level scores the legal tokens the same way and differs in what it
 * notices: EASY picks at random half the time and otherwise only sees "leave yard / go home /
 * advance"; MEDIUM adds captures and safe squares; HARD also weighs the danger of every square
 * (an opposing token 1 to 6 squares behind it) both where it stands and where it would land.
 */
object LudoBot {
    fun choose(
        positions: IntArray, colors: List<Int>, seat: Int, roll: Int,
        needSix: Boolean, exactFinish: Boolean, difficulty: CpuDifficulty, random: Random
    ): Int? {
        val legal = LudoRules.legalTokens(positions, seat, roll, needSix, exactFinish)
        if (legal.isEmpty()) return null
        if (legal.size == 1) return legal[0]
        if (difficulty == CpuDifficulty.EASY && random.nextBoolean()) return legal[random.nextInt(legal.size)]
        val scored = legal.map { it to score(positions, colors, seat, it, roll, needSix, exactFinish, difficulty) }
        val best = scored.maxOf { it.second }
        // Break ties toward the token furthest along, then randomly.
        val top = scored.filter { it.second >= best - 1e-9 }.map { it.first }
        val furthest = top.maxOf { positions[seat * LudoBoard.TOKENS + it] }
        return top.filter { positions[seat * LudoBoard.TOKENS + it] == furthest }.random(random)
    }

    private fun score(
        positions: IntArray, colors: List<Int>, seat: Int, token: Int, roll: Int,
        needSix: Boolean, exactFinish: Boolean, difficulty: CpuDifficulty
    ): Double {
        val result = LudoRules.apply(positions, colors, seat, token, roll, needSix, exactFinish)
        var v = 0.0
        if (result.leftYard) v += 55.0
        if (result.finished) v += 70.0
        v += (result.to - maxOf(result.from, 0)) * 0.6 + result.to * 0.15
        if (result.to in 51..55) v += 12.0
        if (difficulty == CpuDifficulty.EASY) return v

        for ((os, ot) in result.captured) v += 90.0 + positions[os * LudoBoard.TOKENS + ot]
        val toSq = LudoBoard.ringSquare(colors[seat], result.to)
        if (toSq != null && LudoBoard.isSafe(toSq)) v += 22.0
        if (difficulty == CpuDifficulty.MEDIUM) return v

        val before = threat(positions, colors, seat, LudoBoard.ringSquare(colors[seat], result.from))
        val after = threat(result.positions, colors, seat, toSq)
        v += before * 28.0 // getting out of a threatened square
        v -= after * 40.0 // stepping into one
        return v
    }

    /** 1.0 when some opposing token sits 1..6 squares behind [square] on an unsafe square, else 0. */
    private fun threat(positions: IntArray, colors: List<Int>, seat: Int, square: Int?): Double {
        if (square == null || LudoBoard.isSafe(square)) return 0.0
        for (other in colors.indices) {
            if (other == seat) continue
            for (t in 0 until LudoBoard.TOKENS) {
                val sq = LudoBoard.ringSquare(colors[other], positions[other * LudoBoard.TOKENS + t]) ?: continue
                val gap = (square - sq + LudoBoard.TRACK) % LudoBoard.TRACK
                if (gap in 1..6) return 1.0
            }
        }
        return 0.0
    }
}
