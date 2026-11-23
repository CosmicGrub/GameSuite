package com.gamesuite.haptics

import com.gamesuite.haptics.HapticSignal.CELEBRATION
import com.gamesuite.haptics.HapticSignal.ESCALATING
import com.gamesuite.haptics.HapticSignal.FAILURE
import com.gamesuite.haptics.HapticSignal.LANDING
import com.gamesuite.haptics.HapticSignal.LIGHT_TICK
import com.gamesuite.haptics.HapticSignal.NORMAL_ACTION
import com.gamesuite.haptics.HapticSignal.RIPPLE_STEP
import com.gamesuite.haptics.HapticSignal.ROLL
import com.gamesuite.haptics.HapticSignal.STRONG_ACTION
import com.gamesuite.haptics.HapticSignal.SUCCESS

/*
 * Pure per-game cue maps for the board group: Reversi, Connect Four, Backgammon. No Android and no
 * Compose in here; the screens detect an event (a board diff, a serial id) and ask these objects
 * which gesture to play. Every number a designer might want to tune is a named constant. The
 * human-readable table lives in docs/design/haptics-cue-sheet.md.
 */

// ---------------------------------------------------------------------------------------------
// REVERSI
// ---------------------------------------------------------------------------------------------
object ReversiHapticCues {
    /** Gap between ripple ticks, one tick per flipped disc. */
    const val RIPPLE_STAGGER_MS = 60L

    /** Most ticks a ripple ever plays (a 12-disc flip still ends quickly). */
    const val MAX_RIPPLE_TICKS = 6

    /** When a STRONG finisher follows, one tick fewer so the whole gesture stays under 450 ms. */
    const val MAX_RIPPLE_TICKS_WITH_FINISHER = 5

    /** Most ticks for a bot / remote move (lighter cue). */
    const val MAX_RIPPLE_TICKS_LIGHT = 4

    /** A move flipping at least this many discs earns the strong finisher. */
    const val BIG_FLIP_COUNT = 5

    /** Gap between the last ripple tick and the finisher. */
    const val FINISHER_GAP_MS = 40L

    /** Gap between a move gesture and a pass / end-of-board gesture that follows it. */
    const val FOLLOW_UP_GAP_MS = 60L

    /** A decoded move: the single disc that appeared, and how many others changed owner. */
    data class Move(val placedIndex: Int, val mover: Int, val flipped: Int, val size: Int) {
        val row: Int get() = placedIndex / size
        val col: Int get() = placedIndex % size
        val isCorner: Boolean get() = (row == 0 || row == size - 1) && (col == 0 || col == size - 1)
        val isEdge: Boolean get() = row == 0 || row == size - 1 || col == 0 || col == size - 1
        val isBig: Boolean get() = flipped >= BIG_FLIP_COUNT || isEdge
    }

    /**
     * What move turned [prev] into [next]: exactly one new disc and at least one changed owner.
     * Null for the first board ever seen (a resume), an undo, a restart or a different board size,
     * so none of those vibrate.
     */
    fun detectMove(prev: List<Int?>?, next: List<Int?>, size: Int): Move? {
        if (prev == null || prev.size != next.size || next.size != size * size) return null
        var placed = -1
        var flipped = 0
        for (i in next.indices) {
            val before = prev[i]
            val after = next[i]
            if (before == null && after != null) {
                if (placed >= 0) return null
                placed = i
            } else if (before != null && after != before) {
                flipped++
            }
        }
        if (placed < 0 || flipped < 1) return null
        return Move(placed, next[placed]!!, flipped, size)
    }

    /**
     * The gesture for [move]. Human: NORMAL_ACTION at the tap, then a RIPPLE_STEP per flipped disc
     * every [RIPPLE_STAGGER_MS], then (big flip, corner or edge) a STRONG_ACTION finisher.
     * [lighter] (a bot or the remote player): LIGHT_TICK instead of the tap, at most
     * [MAX_RIPPLE_TICKS_LIGHT] ticks, and only a NORMAL_ACTION finisher.
     */
    fun forMove(move: Move, lighter: Boolean): HapticSequence {
        val finisher = move.isBig
        val maxTicks = when {
            lighter -> MAX_RIPPLE_TICKS_LIGHT
            finisher -> MAX_RIPPLE_TICKS_WITH_FINISHER
            else -> MAX_RIPPLE_TICKS
        }
        val ticks = minOf(move.flipped, maxTicks)
        val cues = mutableListOf(HapticCue(if (lighter) LIGHT_TICK else NORMAL_ACTION, 0L))
        for (i in 1..ticks) cues += HapticCue(RIPPLE_STEP, i * RIPPLE_STAGGER_MS)
        if (finisher) {
            cues += HapticCue(if (lighter) NORMAL_ACTION else STRONG_ACTION, ticks * RIPPLE_STAGGER_MS + FINISHER_GAP_MS)
        }
        return HapticSequence(cues)
    }

    /** A forced pass: one rising-tension pulse, played with the pass announcement. */
    fun forPass(): HapticSequence = HapticSequence.single(ESCALATING)

    /** The end of a board: win, loss, draw (a draw is SUCCESS-length, not a celebration). */
    fun forBoardEnd(outcome: HapticOutcome): HapticSequence = HapticSequence.single(
        when (outcome) {
            HapticOutcome.WIN -> CELEBRATION
            HapticOutcome.LOSS -> FAILURE
            HapticOutcome.DRAW -> SUCCESS
        }
    )

    /**
     * Everything one state change can trigger, in order: the move gesture, a forced-pass pulse, the
     * end-of-board cue. Each part follows the previous one by [FOLLOW_UP_GAP_MS].
     */
    fun compose(move: Move?, lighter: Boolean, passed: Boolean, end: HapticOutcome?): HapticSequence {
        var seq = if (move != null) forMove(move, lighter) else HapticSequence.EMPTY
        if (passed) seq = seq.then(forPass(), FOLLOW_UP_GAP_MS)
        if (end != null) seq = seq.then(forBoardEnd(end), FOLLOW_UP_GAP_MS)
        return seq
    }
}

// ---------------------------------------------------------------------------------------------
// CONNECT FOUR
// ---------------------------------------------------------------------------------------------
object ConnectFourHapticCues {
    // The fall animation. ConnectFourScreen's disc tween calls fallMs() so the buzz and the picture
    // can never drift apart: change the numbers here and both follow.
    const val FALL_BASE_MS = 140L
    const val FALL_PER_ROW_MS = 50L
    const val FALL_MAX_MS = 350L

    /** Delay from the landing thud to the smaller second "bounce" tick. */
    const val BOUNCE_DELAY_MS = 70L

    /** Delay from the landing to the follow-up pulse (threat / win line / loss). */
    const val FOLLOW_UP_MS = 90L

    /** Delay from the win-line SUCCESS pulse to the CELEBRATION. */
    const val CELEBRATION_AFTER_MS = 150L

    /** Duration of the disc's fall into 0-based [row] (counted from the top), 0 with reduced motion. */
    fun fallMs(row: Int, reducedMotion: Boolean): Long =
        if (reducedMotion) 0L else (FALL_BASE_MS + FALL_PER_ROW_MS * (row + 1)).coerceAtMost(FALL_MAX_MS)

    /** The one cell filled between [prev] and [next], or null (first board, restart, undo, several cells). */
    fun detectDrop(prev: List<Int?>?, next: List<Int?>): Int? {
        if (prev == null || prev.size != next.size) return null
        var found: Int? = null
        for (i in next.indices) {
            if (prev[i] == null && next[i] != null) {
                if (found != null) return null
                found = i
            } else if (prev[i] != null && next[i] != prev[i]) {
                return null
            }
        }
        return found
    }

    /**
     * How many windows of [winLength] cells hold exactly winLength-1 of [player]'s discs, one empty
     * cell and no opponent disc: an "open three" with a fourth still available. Row-major cells.
     */
    fun openThreatCount(cells: List<Int?>, rows: Int, cols: Int, winLength: Int, player: Int): Int {
        if (cells.size != rows * cols) return 0
        var count = 0
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for (r in 0 until rows) for (c in 0 until cols) for ((dr, dc) in dirs) {
            val endR = r + dr * (winLength - 1)
            val endC = c + dc * (winLength - 1)
            if (endR !in 0 until rows || endC !in 0 until cols) continue
            var mine = 0
            var empty = 0
            var other = 0
            for (k in 0 until winLength) {
                when (cells[(r + dr * k) * cols + (c + dc * k)]) {
                    null -> empty++
                    player -> mine++
                    else -> other++
                }
            }
            if (other == 0 && mine == winLength - 1 && empty == 1) count++
        }
        return count
    }

    /** True when the drop that turned [before] into [after] created a new open three for its mover. */
    fun createdThreat(before: List<Int?>, after: List<Int?>, rows: Int, cols: Int, winLength: Int, mover: Int): Boolean =
        openThreatCount(after, rows, cols, winLength, mover) > openThreatCount(before, rows, cols, winLength, mover)

    /**
     * The gesture for one drop. Every cue is offset by [fallMs] (the disc is still falling) and the
     * sequence says so via leadInMs, so the spans stay bounded independent of the animation.
     *  - landing: LANDING (human) or LIGHT_TICK (bot / remote), then a RIPPLE_STEP bounce;
     *  - [threat] (new open three, board not over): ESCALATING after the bounce;
     *  - [end]: WIN = SUCCESS pulse on the winning line then CELEBRATION; LOSS = FAILURE; DRAW = NORMAL_ACTION.
     */
    fun forDrop(fallMs: Long, lighter: Boolean, threat: Boolean, end: HapticOutcome?): HapticSequence {
        val cues = mutableListOf(
            HapticCue(if (lighter) LIGHT_TICK else LANDING, fallMs),
            HapticCue(RIPPLE_STEP, fallMs + BOUNCE_DELAY_MS)
        )
        val followUp = fallMs + FOLLOW_UP_MS
        when (end) {
            HapticOutcome.WIN -> {
                cues += HapticCue(SUCCESS, followUp)
                cues += HapticCue(CELEBRATION, followUp + CELEBRATION_AFTER_MS)
            }
            HapticOutcome.LOSS -> cues += HapticCue(FAILURE, followUp)
            HapticOutcome.DRAW -> cues += HapticCue(NORMAL_ACTION, followUp)
            null -> if (threat) cues += HapticCue(ESCALATING, followUp)
        }
        return HapticSequence(cues, leadInMs = fallMs)
    }
}

// ---------------------------------------------------------------------------------------------
// BACKGAMMON
// ---------------------------------------------------------------------------------------------
object BackgammonHapticCues {
    /** Delay from the start of the roll rattle to the LIGHT_TICK of the dice settling (the rattle is ~118 ms long). */
    const val SETTLE_DELAY_MS = 150L

    /** Gap between a final checker move and the end-of-game cue. */
    const val END_GAP_MS = 60L

    /** What a roll looks like to the detector: any change means dice were just thrown. */
    data class RollKey(val dice: List<Int>, val openingDice: List<Int>, val openingTies: Int, val player: Int)

    /** True when [next] is a new roll relative to [prev] (null = first state seen = a resume: false). */
    fun isNewRoll(prev: RollKey?, next: RollKey): Boolean {
        if (prev == null) return false
        val regular = next.dice.isNotEmpty() && (prev.dice != next.dice || prev.player != next.player)
        val opening = next.openingDice.size == 2 &&
            (prev.openingDice != next.openingDice || prev.openingTies != next.openingTies)
        return regular || opening
    }

    /** Dice thrown: the ROLL rattle, and a LIGHT_TICK when they come to rest. */
    fun forRoll(): HapticSequence = HapticSequence.of(
        HapticCue(ROLL, 0L), HapticCue(LIGHT_TICK, SETTLE_DELAY_MS)
    )

    /** A checker move: a hit is STRONG_ACTION, a bear-off LIGHT_TICK, everything else (bar entry included) NORMAL_ACTION. */
    fun forMove(hit: Boolean, bearsOff: Boolean): HapticSequence = HapticSequence.single(
        when {
            hit -> STRONG_ACTION
            bearsOff -> LIGHT_TICK
            else -> NORMAL_ACTION
        }
    )

    /** Game over: win = CELEBRATION, loss = FAILURE; starts [END_GAP_MS] in so a final bear-off tick is not cut. */
    fun forGameEnd(outcome: HapticOutcome): HapticSequence =
        HapticSequence.single(if (outcome == HapticOutcome.WIN) CELEBRATION else FAILURE).shifted(END_GAP_MS)
}
