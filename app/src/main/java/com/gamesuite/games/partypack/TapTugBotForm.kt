package com.gamesuite.games.partypack

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * Day-to-day form of a computer player, shared by Tap Race and Tug of War. Real players have good and bad rounds, so
 * a computer plays each ROUND at its level or, now and then, a level off: a Hard racer has an off day about a third of
 * the time, and an Easy one sometimes plays above itself. The levels stay clearly ordered over many rounds, but no level
 * is unbeatable and none is hopeless. Pure, with an injected [Random], so tests replay it.
 */
object TapTugBotForm {
    const val HARD_OFF_MEDIUM = 0.30
    const val HARD_OFF_EASY = 0.08
    const val MEDIUM_OFF_EASY = 0.15
    const val EASY_ABOVE_MEDIUM = 0.15

    /** The level a computer of [base] plays this round at. */
    fun roundLevel(base: CpuDifficulty, random: Random): CpuDifficulty {
        val r = random.nextDouble()
        return when (base) {
            CpuDifficulty.HARD -> if (r < HARD_OFF_EASY) CpuDifficulty.EASY else if (r < HARD_OFF_EASY + HARD_OFF_MEDIUM) CpuDifficulty.MEDIUM else CpuDifficulty.HARD
            CpuDifficulty.MEDIUM -> if (r < MEDIUM_OFF_EASY) CpuDifficulty.EASY else CpuDifficulty.MEDIUM
            CpuDifficulty.EASY -> if (r < EASY_ABOVE_MEDIUM) CpuDifficulty.MEDIUM else CpuDifficulty.EASY
        }
    }
}
