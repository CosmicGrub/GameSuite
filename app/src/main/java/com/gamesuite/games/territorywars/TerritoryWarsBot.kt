package com.gamesuite.games.territorywars

import com.gamesuite.core.CpuLevel
import kotlin.random.Random

/**
 * How long a bot may think. [maxComputeMillis] is the hard ceiling for ONE decision (the search checks the clock
 * and coroutine cancellation); [minVisibleMillis] is the least time the screen waits before showing the move so it
 * feels natural (design doc section 2.6). Both per [CpuLevel].
 */
data class ThinkBudget(val maxComputeMillis: Long, val minVisibleMillis: Long)

object ThinkBudgets {
    /** Easy 20 ms / 450 ms, Medium 80 ms / 600 ms, Hard (and Expert, unused) 250 ms / 800 ms. */
    fun forLevel(level: CpuLevel): ThinkBudget = when (level) {
        CpuLevel.EASY -> ThinkBudget(20L, level.minThinkMillis)
        CpuLevel.MEDIUM -> ThinkBudget(80L, level.minThinkMillis)
        CpuLevel.HARD, CpuLevel.EXPERT -> ThinkBudget(250L, level.minThinkMillis)
    }
}

/**
 * A computer player. [chooseAction] is pure and runs OFF the main thread (the game wraps it in
 * `withContext(Dispatchers.Default)`); it must honour [budget], check coroutine cancellation if it loops, and use
 * only the supplied [random] (seeded by the game from the war seed and round) so a war with bots replays exactly.
 * It returns the next single action for the seat on turn, or null when it has nothing to do (the game then
 * advances the phase itself so a bot can never stall the table).
 */
interface TerritoryWarsBot {
    fun chooseAction(
        state: TerritoryWarsState,
        level: CpuLevel,
        budget: ThinkBudget,
        random: Random
    ): TerritoryWarsAction?
}

/** SCAFFOLD stub: always null (no opinion). The three real bots are specified in the design doc section 2.6. */
class StubTerritoryWarsBot : TerritoryWarsBot {
    override fun chooseAction(
        state: TerritoryWarsState,
        level: CpuLevel,
        budget: ThinkBudget,
        random: Random
    ): TerritoryWarsAction? = null
}
