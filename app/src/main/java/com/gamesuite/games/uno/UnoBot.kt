package com.gamesuite.games.uno

/**
 * Simple heuristic CPU opponent — not unbeatable, just plausible:
 * prefers action cards (to disrupt whoever has the fewest cards), otherwise
 * plays the highest-value legal card to shed points, and picks the color
 * it holds the most of when choosing after a Wild.
 */
object UnoBot {

    /** Returns the card to play, or null to draw. */
    fun chooseMove(hand: List<UnoCard>, state: UnoState, rules: UnoRules): UnoCard? {
        val legal = hand.filter { isLegalPlay(it, state, rules) }
        if (legal.isEmpty()) return null

        val actionCards = legal.filter { !it.isNumber }
        val pool = actionCards.ifEmpty { legal }

        // Prefer Wild Draw Four / Draw Two late, number cards for early sheds — simple priority order.
        return pool.maxByOrNull { it.scoreValue }
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
}
