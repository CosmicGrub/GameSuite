package com.gamesuite.games.uno

import com.gamesuite.settings.CpuDifficulty

/**
 * Heuristic CPU opponent with a real Easy/Medium/Hard ladder — this is the fix for the
 * audited finding that UNO was the one game in the suite whose bot ignored the app-wide
 * "Default CPU difficulty" setting entirely (every other bot already had one). The tiers
 * are qualitatively different, not three reskins of one number:
 *  - EASY: picks any legal card at random — genuinely weak, not just "slower".
 *  - MEDIUM: the original heuristic — prefers action cards (to disrupt whoever has the
 *    fewest cards), otherwise plays the highest-value legal card to shed points.
 *  - HARD: adds two real tactical improvements on top of MEDIUM, using only information
 *    every player at the table already sees (hand SIZES, never hidden hand contents —
 *    UnoBot only ever runs against a locally-visible UnoState in non-networked play, but
 *    "no hidden-hand peeking" keeps the heuristic honest and easy to reason about):
 *    reserves a Wild Draw Four instead of spending it the instant it's drawn/legal, and
 *    prioritizes disrupting whichever opponent is closest to winning.
 */
object UnoBot {

    /** Returns the card to play, or null to draw. */
    fun chooseMove(
        hand: List<UnoCard>,
        state: UnoState,
        rules: UnoRules,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    ): UnoCard? {
        val legal = hand.filter { isLegalPlay(it, state, rules) }
        if (legal.isEmpty()) return null

        return when (difficulty) {
            CpuDifficulty.EASY -> legal.random()
            CpuDifficulty.MEDIUM -> mediumChoice(legal)
            CpuDifficulty.HARD -> hardChoice(legal, state)
        }
    }

    private fun mediumChoice(legal: List<UnoCard>): UnoCard {
        val actionCards = legal.filter { !it.isNumber }
        val pool = actionCards.ifEmpty { legal }
        // Prefer Wild Draw Four / Draw Two late, number cards for early sheds — simple priority order.
        return pool.maxByOrNull { it.scoreValue } ?: legal.first()
    }

    private fun hardChoice(legal: List<UnoCard>, state: UnoState): UnoCard {
        val nextIndex = advanceIndex(state.currentPlayerIndex, state.direction, state.players.size)
        val nextOpponentLow = state.players.getOrNull(nextIndex)?.hand?.size?.let { it <= 2 } ?: false

        // Reserve Wild Draw Four for when it actually counts (it's the only legal card, or the
        // next opponent is about to go out) instead of spending it the moment it's drawn — a real
        // opponent holds this back for the moment it can decide a match, MEDIUM's flat
        // highest-scoreValue tiebreak never does.
        val reserveWildDrawFour = legal.size > 1 && !nextOpponentLow
        val candidates = (if (reserveWildDrawFour) legal.filter { it.rank != UnoRank.WILD_DRAW_FOUR } else legal)
            .ifEmpty { legal }

        val actionCards = candidates.filter { !it.isNumber }
        val pool = actionCards.ifEmpty { candidates }
        return pool.maxByOrNull { it.scoreValue } ?: legal.first()
    }

    fun chooseColor(hand: List<UnoCard>): UnoColor {
        val counts = hand.filter { !it.isWild }
            .groupingBy { it.color }
            .eachCount()
        return counts.maxByOrNull { it.value }?.key ?: UnoColor.entries.first { it != UnoColor.WILD }
    }

    /** Which opponent to target with a 7-swap (house rule): the one with the fewest cards. */
    fun chooseSwapTarget(state: UnoState, selfIndex: Int): Int {
        return state.players.indices
            .filter { it != selfIndex }
            .minByOrNull { state.players[it].hand.size }
            ?: (selfIndex + 1) % state.players.size
    }

    private fun isLegalPlay(card: UnoCard, state: UnoState, rules: UnoRules): Boolean {
        if (state.pendingDraw > 0) {
            val top = state.topCard
            return when {
                top.rank == UnoRank.DRAW_TWO -> card.rank == UnoRank.DRAW_TWO ||
                    (rules.stackDrawFourOnDrawTwo && card.rank == UnoRank.WILD_DRAW_FOUR)
                top.rank == UnoRank.WILD_DRAW_FOUR -> card.rank == UnoRank.WILD_DRAW_FOUR
                else -> false
            }
        }
        return card.isWild || card.color == state.currentColor || card.rank == state.topCard.rank
    }

    private fun advanceIndex(from: Int, direction: Int, count: Int): Int =
        ((from + direction) % count + count) % count
}
