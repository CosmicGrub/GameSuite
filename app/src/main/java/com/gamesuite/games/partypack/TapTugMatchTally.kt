package com.gamesuite.games.partypack

/**
 * Best-of-N bookkeeping shared by the Tap Race and Tug of War engines (pure, so it is tested without a game).
 * A "side" is a seat (Tap Race, free-for-all Tug) or a team (Tug teams). The first side to win more than half the
 * rounds takes the match; when all N rounds have been played the strict leader wins; a tie goes to up to
 * [MAX_TIEBREAKS] extra rounds and then is a draw. A drawn round (nobody won it) counts as played. So a match
 * ALWAYS ends within `rounds + MAX_TIEBREAKS` rounds.
 */
class TapTugMatchTally(val sides: Int, val rounds: Int, wins: List<Int>? = null, played: Int = 0) {
    sealed interface Outcome {
        data object Continue : Outcome
        data class Won(val side: Int) : Outcome
        data object Drawn : Outcome
    }

    val wins = IntArray(sides).also { arr -> wins?.forEachIndexed { i, w -> if (i < sides) arr[i] = w } }
    var played: Int = played
        private set

    /** Round wins needed to take the match outright. */
    val needed: Int get() = rounds / 2 + 1

    /** Rounds played beyond [rounds]. */
    val tiebreaks: Int get() = (played - rounds).coerceAtLeast(0)

    /** Record a finished round won by [winnerSide], or -1 for a drawn round. */
    fun record(winnerSide: Int): Outcome {
        played++
        if (winnerSide in 0 until sides) wins[winnerSide]++
        if (winnerSide in 0 until sides && wins[winnerSide] >= needed) return Outcome.Won(winnerSide)
        if (played >= rounds) {
            val best = wins.max()
            val leaders = wins.indices.filter { wins[it] == best }
            if (leaders.size == 1) return Outcome.Won(leaders[0])
            if (played >= rounds + MAX_TIEBREAKS) return Outcome.Drawn
        }
        return Outcome.Continue
    }

    /** True when a saved tally is possible: sizes, ranges, and a match that has not already been decided. */
    fun isValidUnfinished(): Boolean {
        if (sides < 2 || rounds !in 1..9 || played < 0 || played >= rounds + MAX_TIEBREAKS) return false
        if (wins.any { it < 0 || it > played } || wins.sum() > played) return false
        if (wins.any { it >= needed }) return false
        if (played >= rounds) {
            val best = wins.max()
            if (wins.count { it == best } == 1) return false // already decided: nothing to resume
        }
        return true
    }

    companion object {
        const val MAX_TIEBREAKS = 3
    }
}
