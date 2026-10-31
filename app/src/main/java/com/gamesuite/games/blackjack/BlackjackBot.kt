package com.gamesuite.games.blackjack

import com.gamesuite.games.cards.Card
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The computer players at a Blackjack table. Hard plays perfect basic strategy and never insures; Medium
 * slips now and then; Easy plays like a casual player (hits and stands on feel, takes insurance, bets big).
 * The dealer is NOT a bot: it follows the fixed table rule in [BjRules.dealerMustHit].
 */
object BjBot {
    private fun errorChance(d: CpuDifficulty) = when (d) {
        CpuDifficulty.EASY -> 0.25
        CpuDifficulty.MEDIUM -> 0.07
        CpuDifficulty.HARD -> 0.0
    }

    fun chooseBet(d: CpuDifficulty, stack: Int, random: Random): Int {
        val fraction = when (d) {
            CpuDifficulty.EASY -> 0.05 + random.nextDouble() * 0.20
            CpuDifficulty.MEDIUM -> 0.05 + random.nextDouble() * 0.07
            CpuDifficulty.HARD -> 0.05
        }
        val raw = (stack * fraction).toInt() / BjRules.MIN_BET * BjRules.MIN_BET
        return raw.coerceIn(BjRules.MIN_BET, (stack / BjRules.MIN_BET * BjRules.MIN_BET).coerceAtLeast(BjRules.MIN_BET))
    }

    fun takeInsurance(d: CpuDifficulty, random: Random): Boolean = when (d) {
        CpuDifficulty.EASY -> random.nextDouble() < 0.4
        CpuDifficulty.MEDIUM -> random.nextDouble() < 0.15
        CpuDifficulty.HARD -> false
    }

    /** [legal] is the set of actions the engine allows (HIT and STAND are always in it). */
    fun chooseAction(
        d: CpuDifficulty, hand: List<Card>, upcard: Card, legal: Set<BjAction>, hitSoft17: Boolean, random: Random
    ): BjAction {
        val best = BjStrategy.best(
            hand, upcard, BjAction.DOUBLE in legal, BjAction.SPLIT in legal, BjAction.SURRENDER in legal, hitSoft17
        )
        var pick = if (best in legal) best else BjAction.STAND
        if (random.nextDouble() < errorChance(d)) {
            val total = BjRules.total(hand).value
            pick = when (pick) {
                BjAction.HIT -> if (total >= 12) BjAction.STAND else pick
                BjAction.STAND -> if (total <= 16) BjAction.HIT else pick
                BjAction.DOUBLE, BjAction.SPLIT, BjAction.SURRENDER -> BjAction.HIT
                else -> pick
            }
        }
        return pick
    }
}
