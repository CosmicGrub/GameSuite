package com.gamesuite.haptics

import com.gamesuite.haptics.HapticSignal.CELEBRATION
import com.gamesuite.haptics.HapticSignal.FAILURE
import com.gamesuite.haptics.HapticSignal.NORMAL_ACTION
import com.gamesuite.haptics.HapticSignal.STRONG_ACTION
import com.gamesuite.haptics.HapticSignal.SUCCESS

/*
 * Pure cue maps for Hex, Hare and Hounds and Snake. See docs/design/haptics-cue-sheet.md.
 */

object HexHapticCues {
    /** Delay from the placement tick to the end-of-game cue, so the tick is not cut off. */
    const val END_GAP_MS = 50L

    /** Delay from the "chain connected" SUCCESS pulse to the CELEBRATION. */
    const val CELEBRATION_AFTER_MS = 140L

    /** A stone placed (or the pie-rule swap taken). */
    fun forPlace(): HapticSequence = HapticSequence.single(NORMAL_ACTION)

    /** The board ended: a won chain pulses SUCCESS then CELEBRATION; a loss is FAILURE. */
    fun forBoardEnd(won: Boolean): HapticSequence =
        if (won) {
            HapticSequence.of(
                HapticCue(SUCCESS, END_GAP_MS), HapticCue(CELEBRATION, END_GAP_MS + CELEBRATION_AFTER_MS)
            )
        } else {
            HapticSequence.of(HapticCue(FAILURE, END_GAP_MS))
        }
}

object HaHHapticCues {
    /** Delay between the "hounds trapped the hare" pulse and the CELEBRATION. */
    const val CELEBRATION_AFTER_MS = 140L

    /** A piece moved (either side). */
    fun forMove(): HapticSequence = HapticSequence.single(NORMAL_ACTION)

    /**
     * The board ended. [localWon]: true = the local player won, false = lost, null = no local
     * winner (draw / unknown). [houndsTrapped]: the win came from trapping the hare, which earns a
     * SUCCESS pulse before the CELEBRATION; a hare that escapes just celebrates.
     */
    fun forBoardEnd(localWon: Boolean?, houndsTrapped: Boolean): HapticSequence = when {
        localWon == null -> HapticSequence.single(SUCCESS)
        !localWon -> HapticSequence.single(FAILURE)
        houndsTrapped -> HapticSequence.of(HapticCue(SUCCESS, 0L), HapticCue(CELEBRATION, CELEBRATION_AFTER_MS))
        else -> HapticSequence.single(CELEBRATION)
    }
}

object SnakeHapticCues {
    /** The snake grows every time its length crosses a multiple of this. */
    const val MILESTONE_EVERY = 10

    /** Delay between the death FAILURE and the STRONG_ACTION thud after it. */
    const val DEATH_THUD_AFTER_MS = 170L

    /** True when growing from [previousLength] to [length] crosses a milestone. */
    fun isMilestone(previousLength: Int, length: Int): Boolean =
        length > previousLength && length / MILESTONE_EVERY > previousLength / MILESTONE_EVERY

    /** Food eaten: NORMAL_ACTION, or SUCCESS when the length just reached a milestone. */
    fun forFood(previousLength: Int, length: Int): HapticSequence =
        HapticSequence.single(if (isMilestone(previousLength, length)) SUCCESS else NORMAL_ACTION)

    /** Death: a FAILURE followed by a STRONG_ACTION thud ("strong" failure). */
    fun forDeath(): HapticSequence =
        HapticSequence.of(HapticCue(FAILURE, 0L), HapticCue(STRONG_ACTION, DEATH_THUD_AFTER_MS))

    /** A new best score or a cleared board ends in celebration instead. */
    fun forRunEnd(celebrate: Boolean): HapticSequence =
        if (celebrate) HapticSequence.single(CELEBRATION) else forDeath()
}
