package com.gamesuite.haptics

import com.gamesuite.haptics.HapticSignal.CELEBRATION
import com.gamesuite.haptics.HapticSignal.LIGHT_TICK
import com.gamesuite.haptics.HapticSignal.NORMAL_ACTION

/*
 * Pure cue maps for the puzzle group: Lights Out and Sliding Puzzle. Both are single-player and
 * tap-driven, so the screens fire these straight from the tap handler (a tap is a human by
 * definition: no restore, bot or arena path can reach it). See docs/design/haptics-cue-sheet.md.
 */

object LightsOutHapticCues {
    /** A cell toggle (the cell plus its neighbours flip): the lightest acknowledgement. */
    fun forToggle(): HapticSequence = HapticSequence.single(LIGHT_TICK)

    /** The last light goes out. */
    fun forSolve(): HapticSequence = HapticSequence.single(CELEBRATION)

    /** The cue a tap should play: nothing extra on the solving tap, which hands over to [forSolve]. */
    fun forTap(solvedByThisTap: Boolean): HapticSequence =
        if (solvedByThisTap) HapticSequence.EMPTY else forToggle()
}

object SlidingPuzzleHapticCues {
    /** A tile slid into the gap. */
    fun forSlide(): HapticSequence = HapticSequence.single(NORMAL_ACTION)

    /**
     * A tap on a tile that cannot move. The weakest signal in the vocabulary on purpose: FAILURE
     * would punish a harmless mis-tap.
     */
    fun forBlocked(): HapticSequence = HapticSequence.single(LIGHT_TICK)

    /** The puzzle is solved. */
    fun forSolve(): HapticSequence = HapticSequence.single(CELEBRATION)

    /** The cue a tap should play: a slide that solves the puzzle plays nothing itself and lets [forSolve] take over. */
    fun forTap(moved: Boolean, solvedByThisTap: Boolean): HapticSequence = when {
        !moved -> forBlocked()
        solvedByThisTap -> HapticSequence.EMPTY
        else -> forSlide()
    }
}
