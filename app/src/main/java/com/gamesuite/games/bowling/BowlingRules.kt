package com.gamesuite.games.bowling

/**
 * Official ten-pin scoring. A player's game is the flat list of pins knocked down by each ball
 * ([rolls]); everything else (frames, running totals, whose ball is next) is derived from it.
 *
 *  - A strike (all ten with the first ball of a frame) scores 10 plus the next two balls.
 *  - A spare (all ten with two balls) scores 10 plus the next ball.
 *  - An open frame scores its pins.
 *  - The tenth frame gives a third ball after a strike or a spare; the bonus balls count only once.
 */
object BowlingRules {
    const val FRAMES = 10
    const val PINS = 10
    const val MAX_SCORE = 300

    /** Running total after each of the ten frames; null while a frame's bonus balls are still to come. */
    fun cumulative(rolls: List<Int>): List<Int?> {
        val out = ArrayList<Int?>(FRAMES)
        var i = 0
        var running = 0
        var known = true
        for (f in 0 until FRAMES - 1) {
            if (!known || i >= rolls.size) { out += null; known = false; continue }
            if (rolls[i] == PINS) {
                if (i + 2 < rolls.size) { running += PINS + rolls[i + 1] + rolls[i + 2]; out += running } else { out += null; known = false }
                i += 1
            } else if (i + 1 < rolls.size) {
                val sum = rolls[i] + rolls[i + 1]
                if (sum == PINS) {
                    if (i + 2 < rolls.size) { running += PINS + rolls[i + 2]; out += running } else { out += null; known = false }
                } else { running += sum; out += running }
                i += 2
            } else { out += null; known = false }
        }
        // tenth frame
        val rest = if (known && i < rolls.size) rolls.subList(i, rolls.size) else emptyList()
        val needed = when {
            rest.isEmpty() -> Int.MAX_VALUE
            rest[0] == PINS -> 3
            rest.size >= 2 && rest[0] + rest[1] == PINS -> 3
            else -> 2
        }
        out += if (known && rest.size >= needed) running + rest.take(needed).sum() else null
        return out
    }

    /** The score so far: the last known running total, plus the pins of frames still waiting for bonus balls. */
    fun total(rolls: List<Int>): Int {
        val cum = cumulative(rolls)
        val lastKnown = cum.indexOfLast { it != null }
        val base = if (lastKnown >= 0) cum[lastKnown]!! else 0
        val consumed = ballsUsedBy(rolls, lastKnown + 1)
        return base + rolls.drop(consumed).sum().let { pending ->
            // frames after the last known one count their pins for now (bonuses still to come)
            pending
        }
    }

    /** How many balls the first [frames] frames used. */
    private fun ballsUsedBy(rolls: List<Int>, frames: Int): Int {
        var i = 0
        for (f in 0 until minOf(frames, FRAMES - 1)) {
            if (i >= rolls.size) return rolls.size
            i += if (rolls[i] == PINS) 1 else 2
        }
        if (frames >= FRAMES) return rolls.size
        return minOf(i, rolls.size)
    }

    /** The rolls split by frame (the tenth holds up to three). */
    fun frames(rolls: List<Int>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var i = 0
        for (f in 0 until FRAMES - 1) {
            if (i >= rolls.size) return out
            if (rolls[i] == PINS) { out += listOf(PINS); i += 1 }
            else { out += rolls.subList(i, minOf(i + 2, rolls.size)); i += 2 }
        }
        if (i < rolls.size) out += rolls.subList(i, rolls.size)
        return out
    }

    /** Whether the player's tenth frame is done. */
    fun isComplete(rolls: List<Int>): Boolean {
        val fr = frames(rolls)
        if (fr.size < FRAMES) return false
        val t = fr[FRAMES - 1]
        return when {
            t.isEmpty() -> false
            t[0] == PINS || (t.size >= 2 && t[0] + t[1] == PINS) -> t.size == 3
            else -> t.size == 2
        }
    }

    /** The frame (0..9) and ball (0..2) the next roll belongs to; null once the game is complete. */
    fun next(rolls: List<Int>): Pair<Int, Int>? {
        if (isComplete(rolls)) return null
        val fr = frames(rolls)
        if (fr.isEmpty()) return 0 to 0
        val lastIdx = fr.size - 1
        val last = fr[lastIdx]
        if (lastIdx < FRAMES - 1) {
            val closed = last.size == 2 || last[0] == PINS
            return if (closed) (lastIdx + 1) to 0 else lastIdx to 1
        }
        return (FRAMES - 1) to last.size
    }

    /** Whether the sequence is one a real game could produce (pin counts fit what was standing). */
    fun isValid(rolls: List<Int>): Boolean {
        if (rolls.any { it !in 0..PINS }) return false
        var i = 0
        for (f in 0 until FRAMES - 1) {
            if (i >= rolls.size) return true
            if (rolls[i] == PINS) { i += 1; continue }
            if (i + 1 >= rolls.size) return true
            if (rolls[i] + rolls[i + 1] > PINS) return false
            i += 2
        }
        val t = rolls.drop(i)
        if (t.size > 3) return false
        if (t.isEmpty()) return true
        // each ball of the tenth frame faces whatever is left of the rack (a fresh rack after a strike or spare)
        var standing = PINS
        for ((k, r) in t.withIndex()) {
            if (r > standing) return false
            standing -= r
            if (standing == 0) standing = PINS
            if (k == 1 && t[0] != PINS && t[0] + t[1] != PINS) return t.size == 2
        }
        return true
    }

    /** True when [rolls]'s frame [f] (0-based) is a strike. */
    fun isStrike(rolls: List<Int>, f: Int): Boolean = frames(rolls).getOrNull(f)?.firstOrNull() == PINS

    fun isSpare(rolls: List<Int>, f: Int): Boolean =
        frames(rolls).getOrNull(f)?.let { it.size >= 2 && it[0] != PINS && it[0] + it[1] == PINS } ?: false

    /** How many pins face the next ball of a player whose game so far is [rolls]. */
    fun standingCountFor(rolls: List<Int>): Int {
        val (f, b) = next(rolls) ?: return PINS
        if (f < FRAMES - 1) return if (b == 0) PINS else PINS - rolls.last()
        var standing = PINS
        for (r in frames(rolls).getOrNull(FRAMES - 1) ?: emptyList()) {
            standing -= r
            if (standing == 0) standing = PINS
        }
        return standing
    }
}
