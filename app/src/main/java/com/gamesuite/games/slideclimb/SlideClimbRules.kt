package com.gamesuite.games.slideclimb

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The rules of one move, with no state and no randomness (the roll is an argument).
 *
 * A piece on square [from] rolls [roll] and walks that many squares. Past square 100:
 *  - default (bounce back): it walks to 100 and back down with the spare steps, so 98 + 5 lands on 97;
 *  - exact finish: the piece does not move at all (it needs the exact number).
 * Landing on a rope or slide foot takes the piece to the other end, once (jump ends are never feet).
 */
object SlideClimbRules {
    const val DIE_FACES = 6
    const val HANDICAP_REROLLS = 1

    /**
     * [landed] is the square walked to, [to] where the piece ends after any rope or slide.
     * [bounced] is true when the walk turned around at 100, [stuck] when an exact finish kept it still.
     */
    data class Step(
        val from: Int,
        val roll: Int,
        val landed: Int,
        val to: Int,
        val jump: JumpKind,
        val bounced: Boolean,
        val stuck: Boolean
    ) {
        val won: Boolean get() = to == SlideClimbBoard.FINISH
    }

    fun step(from: Int, roll: Int, layout: SlideClimbLayout, exactFinish: Boolean): Step {
        require(roll in 1..DIE_FACES) { "a die shows 1 to 6" }
        require(from in SlideClimbBoard.START until SlideClimbBoard.FINISH) { "a piece on 100 has already won" }
        val raw = from + roll
        if (raw <= SlideClimbBoard.FINISH) {
            val to = layout.destination(raw)
            return Step(from, roll, raw, to, jumpOf(raw, to), bounced = false, stuck = false)
        }
        if (exactFinish) {
            return Step(from, roll, from, from, JumpKind.NONE, bounced = false, stuck = true)
        }
        val landed = 2 * SlideClimbBoard.FINISH - raw
        val to = layout.destination(landed)
        return Step(from, roll, landed, to, jumpOf(landed, to), bounced = true, stuck = false)
    }

    private fun jumpOf(landed: Int, to: Int): JumpKind = when {
        to > landed -> JumpKind.CLIMB
        to < landed -> JumpKind.SLIDE
        else -> JumpKind.NONE
    }

    /**
     * The squares a piece hops over one by one for a roll, ending on [Step.landed] (empty when stuck).
     * The screen animates this, then the rope or slide.
     */
    fun hopPath(from: Int, roll: Int, exactFinish: Boolean): List<Int> {
        val raw = from + roll
        if (raw <= SlideClimbBoard.FINISH) return ((from + 1)..raw).toList()
        if (exactFinish) return emptyList()
        val up = ((from + 1)..SlideClimbBoard.FINISH).toList()
        val down = (SlideClimbBoard.FINISH - 1 downTo (2 * SlideClimbBoard.FINISH - raw)).toList()
        return up + down
    }
}

/**
 * The computer's only decision in this game: whether to spend a spare roll on the roll it just made.
 * Kept pure (the coin for Easy is passed in) so it is testable and never touches the dice stream.
 */
object SlideClimbBot {
    fun wantsSpareRoll(
        position: Int, roll: Int, layout: SlideClimbLayout, exactFinish: Boolean,
        difficulty: CpuDifficulty, coin: Random
    ): Boolean {
        val step = SlideClimbRules.step(position, roll, layout, exactFinish)
        val gain = step.to - position
        return when (difficulty) {
            CpuDifficulty.EASY -> coin.nextBoolean()
            CpuDifficulty.MEDIUM -> !step.won && gain <= 0
            CpuDifficulty.HARD -> !step.won && gain < 2
        }
    }
}
