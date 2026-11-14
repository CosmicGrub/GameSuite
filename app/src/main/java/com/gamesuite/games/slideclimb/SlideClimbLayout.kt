package com.gamesuite.games.slideclimb

import kotlin.random.Random

/** What a square does to a piece that lands on it. */
enum class JumpKind { NONE, CLIMB, SLIDE }

/**
 * The snaking 10 x 10 track. Square 1 is the bottom-left corner, the first row runs left to right, the
 * next right to left and so on, so square 100 is the top-left corner. Square 0 is the start area, just
 * below square 1. Pure geometry: no Compose, no drawing.
 */
object SlideClimbBoard {
    const val SIDE = 10
    const val SQUARES = SIDE * SIDE
    const val START = 0
    const val FINISH = SQUARES

    /** Row of [square] counted from the bottom (0..9). */
    fun row(square: Int): Int = (square - 1) / SIDE

    /** Column of [square] counted from the left (0..9). */
    fun column(square: Int): Int {
        val index = square - 1
        val c = index % SIDE
        return if ((index / SIDE) % 2 == 0) c else SIDE - 1 - c
    }

    /** The square at ([column], [row]); the inverse of [column] / [row]. */
    fun squareAt(column: Int, row: Int): Int {
        val c = if (row % 2 == 0) column else SIDE - 1 - column
        return row * SIDE + c + 1
    }
}

/**
 * Where the ropes (up) and slides (down) are: a map from the square a piece lands on to the square it
 * ends on. A layout is only ever used when [problems] is empty, which guarantees:
 *  - exactly [ROPE_COUNT] ropes and [SLIDE_COUNT] slides, none starting on square 1 or 100 or ending on 100;
 *  - no square is both the end of one jump and the start of another, so jumps never chain and can never loop;
 *  - every jump spans between [MIN_SPAN] and [MAX_SPAN] squares and no row holds more than [MAX_PER_ROW] jumps;
 *  - the total climbed and the total slid are within [MAX_IMBALANCE] of each other (fair difficulty);
 *  - no run of six slides in a row (a wall nobody could pass);
 *  - from the start, every square a piece can rest on is reachable and square 100 is reachable from all of
 *    them, in both finishing modes.
 */
data class SlideClimbLayout(val jumps: Map<Int, Int>) {
    fun destination(square: Int): Int = jumps[square] ?: square

    fun kindAt(square: Int): JumpKind {
        val end = jumps[square] ?: return JumpKind.NONE
        return if (end > square) JumpKind.CLIMB else JumpKind.SLIDE
    }

    val ropes: Map<Int, Int> get() = jumps.filter { it.value > it.key }
    val slides: Map<Int, Int> get() = jumps.filter { it.value < it.key }

    companion object {
        const val ROPE_COUNT = 8
        const val SLIDE_COUNT = 8
        const val MIN_SPAN = 8
        const val MAX_SPAN = 40
        const val MAX_PER_ROW = 3
        const val MAX_IMBALANCE = 60

        /** The layout every game uses unless a seed asks for another: hand-placed, balanced and checked by the tests. */
        val DEFAULT = SlideClimbLayout(
            mapOf(
                // ropes
                5 to 16, 13 to 35, 24 to 44, 38 to 59, 47 to 68, 55 to 77, 62 to 82, 71 to 91,
                // slides
                18 to 6, 33 to 14, 45 to 26, 57 to 39, 66 to 48, 79 to 58, 88 to 67, 96 to 73
            )
        )

        /** Why [layout] must not be used; empty when it is fine. */
        fun problems(layout: SlideClimbLayout): List<String> {
            val out = ArrayList<String>()
            val jumps = layout.jumps
            if (layout.ropes.size != ROPE_COUNT) out += "rope count ${layout.ropes.size}"
            if (layout.slides.size != SLIDE_COUNT) out += "slide count ${layout.slides.size}"
            if (jumps.size != layout.ropes.size + layout.slides.size) out += "a jump goes nowhere"
            for ((start, end) in jumps) {
                if (start !in 2..99) out += "start $start out of range"
                if (end !in 1..99) out += "end $end out of range"
                val span = kotlin.math.abs(end - start)
                if (span < MIN_SPAN || span > MAX_SPAN) out += "span of $start to $end is $span"
                if (end in jumps.keys) out += "$end both ends a jump and starts one"
            }
            if (jumps.values.toSet().size != jumps.size) out += "two jumps end on the same square"
            val perRow = jumps.keys.groupingBy { SlideClimbBoard.row(it) }.eachCount()
            if (perRow.values.any { it > MAX_PER_ROW }) out += "a row holds more than $MAX_PER_ROW jumps"
            val rise = layout.ropes.entries.sumOf { it.value - it.key }
            val drop = layout.slides.entries.sumOf { it.key - it.value }
            if (kotlin.math.abs(rise - drop) > MAX_IMBALANCE) out += "unbalanced: climb $rise, slide $drop"
            val slideStarts = layout.slides.keys
            for (first in 1..(SlideClimbBoard.SQUARES - 5)) {
                if ((first until first + 6).all { it in slideStarts }) out += "six slides in a row from $first"
            }
            if (out.isEmpty()) out += reachabilityProblems(layout)
            return out
        }

        fun isValid(layout: SlideClimbLayout): Boolean = problems(layout).isEmpty()

        /** Squares a piece can rest on: every square that does not start a jump. */
        private fun resting(layout: SlideClimbLayout): List<Int> =
            (SlideClimbBoard.START..SlideClimbBoard.FINISH).filter { it !in layout.jumps }

        private fun reachabilityProblems(layout: SlideClimbLayout): List<String> {
            val out = ArrayList<String>()
            for (exact in listOf(false, true)) {
                val label = if (exact) "exact finish" else "bounce back"
                val next = HashMap<Int, MutableSet<Int>>()
                val seen = HashSet<Int>()
                val queue = ArrayDeque<Int>()
                seen += SlideClimbBoard.START
                queue.addLast(SlideClimbBoard.START)
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    if (at == SlideClimbBoard.FINISH) continue
                    for (roll in 1..6) {
                        val to = SlideClimbRules.step(at, roll, layout, exact).to
                        next.getOrPut(at) { HashSet() } += to
                        if (seen.add(to)) queue.addLast(to)
                    }
                }
                for (square in resting(layout)) {
                    if (square !in seen) out += "square $square unreachable ($label)"
                }
                // reverse closure from the finish
                val canFinish = HashSet<Int>()
                canFinish += SlideClimbBoard.FINISH
                var grew = true
                while (grew) {
                    grew = false
                    for ((from, tos) in next) {
                        if (from !in canFinish && tos.any { it in canFinish }) {
                            canFinish += from
                            grew = true
                        }
                    }
                }
                for (square in seen) {
                    if (square !in canFinish) out += "square $square cannot reach 100 ($label)"
                }
            }
            return out
        }

        /**
         * A fair layout drawn from [seed]: random ropes and slides, retried until [problems] is empty, and
         * [DEFAULT] if none of the attempts qualifies (so the result is always valid). Deterministic.
         */
        fun generate(seed: Long): SlideClimbLayout {
            val random = Random(seed)
            repeat(400) {
                val jumps = LinkedHashMap<Int, Int>()
                val used = HashSet<Int>()
                var placed = 0
                var guard = 0
                while (placed < ROPE_COUNT + SLIDE_COUNT && guard < 2000) {
                    guard++
                    val up = placed % 2 == 0
                    val span = random.nextInt(MIN_SPAN, 31)
                    val start = if (up) random.nextInt(2, 100 - span) else random.nextInt(1 + span, 99)
                    val end = if (up) start + span else start - span
                    if (start in used || end in used) continue
                    used += start
                    used += end
                    jumps[start] = end
                    placed++
                }
                if (placed == ROPE_COUNT + SLIDE_COUNT) {
                    val layout = SlideClimbLayout(jumps)
                    if (isValid(layout)) return layout
                }
            }
            return DEFAULT
        }
    }
}
