package com.gamesuite.games.gemcascade

import kotlin.math.abs

/** Pure input logic for the screen: tap-tap selection, drag direction and one-switch scanning. Unit tested. */
object GemInput {
    sealed interface TapOutcome {
        /** The tap picked (or re-picked) a first gem. */
        data class Select(val pos: Pos) : TapOutcome

        /** The tap unselected the selected gem. */
        object Deselect : TapOutcome

        /** The tap named a neighbour of the selected gem: try this swap. */
        data class Swap(val a: Pos, val b: Pos) : TapOutcome
    }

    fun adjacent(a: Pos, b: Pos): Boolean = abs(a.r - b.r) + abs(a.c - b.c) == 1

    /** tap-tap: first tap selects, tapping the same gem unselects, a neighbour swaps, a far gem moves the selection. */
    fun onTap(selected: Pos?, tap: Pos): TapOutcome = when {
        selected == null -> TapOutcome.Select(tap)
        selected == tap -> TapOutcome.Deselect
        adjacent(selected, tap) -> TapOutcome.Swap(selected, tap)
        else -> TapOutcome.Select(tap)
    }

    /** The neighbour a drag from [from] points at, or null for a drag that is too short or leaves the board. */
    fun dragTarget(from: Pos, dxPx: Float, dyPx: Float, cellPx: Float, rows: Int, cols: Int): Pos? {
        if (cellPx <= 0f) return null
        val threshold = cellPx * 0.3f
        if (abs(dxPx) < threshold && abs(dyPx) < threshold) return null
        val to = if (abs(dxPx) >= abs(dyPx)) Pos(from.r, from.c + if (dxPx > 0) 1 else -1)
        else Pos(from.r + if (dyPx > 0) 1 else -1, from.c)
        return if (to.r in 0 until rows && to.c in 0 until cols) to else null
    }

    /** Twist: the 2 x 2 block anchored at a tapped cell, clamped so it stays on the board. */
    fun blockFor(tap: Pos, rows: Int, cols: Int): Pos = Pos(tap.r.coerceIn(0, rows - 2), tap.c.coerceIn(0, cols - 2))

    /** Which cell a touch at ([x], [y]) inside the board hits, or null outside. */
    fun cellAt(x: Float, y: Float, cellPx: Float, rows: Int, cols: Int): Pos? {
        if (cellPx <= 0f || x < 0f || y < 0f) return null
        val r = (y / cellPx).toInt()
        val c = (x / cellPx).toInt()
        return if (r in 0 until rows && c in 0 until cols) Pos(r, c) else null
    }
}

/**
 * One-switch scanning: a single button plays the whole game. [step] is called on a timer and moves the
 * highlight; [select] is the button. Phase 1 scans every cell row by row; phase 2 scans the directions the chosen
 * gem can be swapped in (right, down, left, up) and then a "cancel" slot. In Twist ([blocks] = true) one selection
 * is enough: it names the 2 x 2 block.
 */
class GemScan(val rows: Int, val cols: Int, val blocks: Boolean = false) {
    enum class Phase { CELL, DIRECTION }

    sealed interface Result {
        object None : Result
        data class Chose(val pos: Pos) : Result
        data class Swap(val a: Pos, val b: Pos) : Result
        data class Block(val topLeft: Pos) : Result
        object Cancelled : Result
    }

    var phase: Phase = Phase.CELL
        private set
    var cursor: Int = 0
        private set
    var chosen: Pos? = null
        private set

    private fun cellCount() = if (blocks) (rows - 1) * (cols - 1) else rows * cols
    private fun cellOf(i: Int): Pos = if (blocks) Pos(i / (cols - 1), i % (cols - 1)) else Pos(i / cols, i % cols)

    /** The highlighted cell (phase 1) or the neighbour a swap would use (phase 2; null on the cancel slot). */
    fun highlight(): Pos? = when (phase) {
        Phase.CELL -> cellOf(cursor)
        Phase.DIRECTION -> directions().getOrNull(cursor)
    }

    private fun directions(): List<Pos> {
        val p = chosen ?: return emptyList()
        return listOf(Pos(p.r, p.c + 1), Pos(p.r + 1, p.c), Pos(p.r, p.c - 1), Pos(p.r - 1, p.c))
            .filter { it.r in 0 until rows && it.c in 0 until cols }
    }

    fun step() {
        when (phase) {
            Phase.CELL -> cursor = (cursor + 1) % cellCount()
            Phase.DIRECTION -> cursor = (cursor + 1) % (directions().size + 1)
        }
    }

    fun select(): Result = when (phase) {
        Phase.CELL -> {
            val p = cellOf(cursor)
            if (blocks) {
                cursor = 0
                Result.Block(p)
            } else {
                chosen = p
                phase = Phase.DIRECTION
                cursor = 0
                Result.Chose(p)
            }
        }
        Phase.DIRECTION -> {
            val a = chosen
            val b = directions().getOrNull(cursor)
            reset()
            if (a != null && b != null) Result.Swap(a, b) else Result.Cancelled
        }
    }

    fun reset() {
        phase = Phase.CELL
        cursor = 0
        chosen = null
    }
}

/** The facts of one batch of events, for words (TalkBack lines, chain pop text). Pure. */
data class GemBatchSummary(
    val gemsMatched: Int,
    val bestChain: Int,
    val scoreGained: Int,
    val specialsMade: List<SpecialKind>,
    val specialsFired: List<SpecialKind>,
    val combos: Int,
    val swappedBack: Boolean,
    val rotatedBack: Boolean,
    val shuffled: Boolean,
    val butterfliesEscaped: Int,
    val treasures: Int,
    val bombsDefused: Int,
    val obstaclesCleared: Int,
    val levelUp: Int?,
    val completeStars: Int?,
    val over: OverReason?
) {
    val quiet: Boolean
        get() = gemsMatched == 0 && !swappedBack && !rotatedBack && !shuffled && completeStars == null && over == null &&
            butterfliesEscaped == 0 && treasures == 0

    companion object {
        fun of(events: List<GemEvent>): GemBatchSummary {
            var gems = 0; var chain = 0; var score = 0; var combos = 0; var obstacles = 0
            var butterflies = 0; var treasures = 0; var defused = 0
            val made = ArrayList<SpecialKind>(); val fired = ArrayList<SpecialKind>()
            var back = false; var rotBack = false; var shuffled = false
            var levelUp: Int? = null; var stars: Int? = null; var over: OverReason? = null
            for (e in events) when (e) {
                is GemEvent.Clear -> gems += e.cells.size
                is GemEvent.ChainStep -> chain = maxOf(chain, e.n)
                is GemEvent.ScoreDelta -> score += e.points
                is GemEvent.SpecialCreated -> made += e.special
                is GemEvent.SpecialActivated -> fired += e.special
                is GemEvent.ComboActivated -> combos++
                is GemEvent.SwapBack -> back = true
                is GemEvent.RotateBack -> rotBack = true
                is GemEvent.NoMovesShuffle -> shuffled = true
                is GemEvent.ObstacleDamaged -> if (e.hpLeft == 0) obstacles++
                is GemEvent.Escaped -> when (e.piece) {
                    PieceType.BUTTERFLY -> butterflies++
                    PieceType.TREASURE -> treasures++
                    else -> Unit
                }
                is GemEvent.BombDefused -> defused++
                is GemEvent.LevelUp -> levelUp = e.level
                is GemEvent.LevelComplete -> stars = e.stars
                is GemEvent.GameOver -> over = e.reason
                else -> Unit
            }
            return GemBatchSummary(gems, chain, score, made, fired, combos, back, rotBack, shuffled, butterflies, treasures, defused, obstacles, levelUp, stars, over)
        }
    }
}
