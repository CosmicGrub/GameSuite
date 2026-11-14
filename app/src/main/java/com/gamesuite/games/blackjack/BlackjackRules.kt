package com.gamesuite.games.blackjack

import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Rank
import kotlinx.serialization.Serializable

/**
 * Pure Blackjack rules: hand totals, payouts and basic strategy. Nothing here touches Android, Compose or
 * randomness, so every rule is unit tested against known hands. The game uses PLAY CHIPS only: there is no
 * money, no purchase and no cash-out anywhere in this feature.
 */
object BjRules {
    const val MIN_BET = 10
    const val MAX_HANDS = 4

    /** The chips on the rail. All are multiples of [MIN_BET], so every payout below is a whole number of chips. */
    val CHIP_VALUES: List<Int> = listOf(10, 50, 100, 500)
    val START_STACKS: List<Int> = listOf(500, 1000, 2500, 5000)
    val DECK_COUNTS: List<Int> = listOf(1, 2, 4, 6)

    /** Ace counts 11 here; [total] lowers it to 1 when the hand would otherwise bust. */
    fun cardValue(card: Card): Int = when {
        card.rank == Rank.ACE -> 11
        card.rank.value >= 10 -> 10
        else -> card.rank.value
    }

    data class Total(val value: Int, val soft: Boolean) {
        val bust: Boolean get() = value > 21
    }

    fun total(cards: List<Card>): Total {
        var sum = 0
        var aces = 0
        for (c in cards) {
            sum += cardValue(c)
            if (c.rank == Rank.ACE) aces++
        }
        while (sum > 21 && aces > 0) {
            sum -= 10
            aces--
        }
        return Total(sum, soft = aces > 0)
    }

    /** A two-card 21. A 21 made after a split is NOT a blackjack: the caller passes only unsplit hands. */
    fun isBlackjack(cards: List<Card>): Boolean = cards.size == 2 && total(cards).value == 21

    /** Same rank, as the table rule for a split (a king and a queen are not a pair). */
    fun isPair(cards: List<Card>): Boolean = cards.size == 2 && cards[0].rank == cards[1].rank

    /** Does the dealer draw another card? */
    fun dealerMustHit(cards: List<Card>, hitSoft17: Boolean): Boolean {
        val t = total(cards)
        return t.value < 17 || (t.value == 17 && t.soft && hitSoft17)
    }

    /** Profit (not counting the returned stake) on a natural for [bet]. Integer chips: bets are multiples of 10, so it is exact. */
    fun blackjackProfit(bet: Int, payout: BjPayout): Int = bet * payout.num / payout.den

    /** Total chips returned to the player (stake plus winnings) for an outcome; 0 for a loss. */
    fun credit(outcome: String, bet: Int, payout: BjPayout): Int = when (outcome) {
        BjOutcome.BLACKJACK -> bet + blackjackProfit(bet, payout)
        BjOutcome.WIN -> bet * 2
        BjOutcome.PUSH -> bet
        BjOutcome.SURRENDER -> bet / 2
        else -> 0
    }

    /**
     * One finished hand against the dealer's final hand. [natural] is true for an unsplit two-card 21.
     * Order matters: surrender and bust are decided before the dealer is looked at (a busted player loses
     * even if the dealer busts afterwards).
     */
    fun outcome(
        player: List<Card>, natural: Boolean, surrendered: Boolean, dealer: List<Card>
    ): String {
        if (surrendered) return BjOutcome.SURRENDER
        val p = total(player)
        if (p.bust) return BjOutcome.BUST
        val dealerNatural = isBlackjack(dealer)
        if (natural) return if (dealerNatural) BjOutcome.PUSH else BjOutcome.BLACKJACK
        if (dealerNatural) return BjOutcome.LOSE
        val d = total(dealer)
        return when {
            d.bust -> BjOutcome.WIN
            p.value > d.value -> BjOutcome.WIN
            p.value < d.value -> BjOutcome.LOSE
            else -> BjOutcome.PUSH
        }
    }
}

enum class BjPayout(val id: String, val num: Int, val den: Int) {
    THREE_TO_TWO("3:2", 3, 2),
    SIX_TO_FIVE("6:5", 6, 5);

    companion object {
        fun fromId(id: String?): BjPayout = entries.firstOrNull { it.id == id } ?: THREE_TO_TWO
    }
}

object BjOutcome {
    const val BLACKJACK = "blackjack"
    const val WIN = "win"
    const val PUSH = "push"
    const val LOSE = "lose"
    const val BUST = "bust"
    const val SURRENDER = "surrender"
    val ALL: Set<String> = setOf(BLACKJACK, WIN, PUSH, LOSE, BUST, SURRENDER)
}

enum class BjAction { HIT, STAND, DOUBLE, SPLIT, SURRENDER, TAKE_INSURANCE, DECLINE_INSURANCE }

/** The table's rules, fixed for a session (the Hub's options). */
@Serializable
data class BjConfig(
    val decks: Int = 6,
    val startingChips: Int = 1000,
    val payout: String = BjPayout.THREE_TO_TWO.id,
    val hitSoft17: Boolean = false,
    val insurance: Boolean = true,
    val surrender: Boolean = true
) {
    val payoutRule: BjPayout get() = BjPayout.fromId(payout)

    val isValid: Boolean
        get() = decks in BjRules.DECK_COUNTS && startingChips in BjRules.MIN_BET..1_000_000 &&
            startingChips % BjRules.MIN_BET == 0 && BjPayout.entries.any { it.id == payout }

    companion object {
        /** Launch extras from the Hub (strings) to a valid config; anything missing or out of range falls back to the default. */
        fun fromExtras(extras: Map<String, String>): BjConfig {
            val d = BjConfig()
            val decks = extras["decks"]?.toIntOrNull()?.takeIf { it in BjRules.DECK_COUNTS } ?: d.decks
            val chips = extras["chips"]?.toIntOrNull()?.takeIf { it in BjRules.START_STACKS } ?: d.startingChips
            return BjConfig(
                decks = decks,
                startingChips = chips,
                payout = BjPayout.fromId(extras["payout"]).id,
                hitSoft17 = extras["hitSoft17"] == "true",
                insurance = extras["insurance"] != "false",
                surrender = extras["surrender"] != "false"
            )
        }
    }
}

/**
 * Multi-deck basic strategy (double after split allowed, split by rank, late surrender when offered).
 * Returns HIT, STAND, DOUBLE, SPLIT or SURRENDER, always one of the actions the caller says is legal.
 */
object BjStrategy {
    fun best(
        hand: List<Card>, upcard: Card, canDouble: Boolean, canSplit: Boolean, canSurrender: Boolean, hitSoft17: Boolean
    ): BjAction {
        val up = BjRules.cardValue(upcard)
        val t = BjRules.total(hand)
        val pair = canSplit && BjRules.isPair(hand)
        val pairIsEights = BjRules.isPair(hand) && hand[0].rank == Rank.EIGHT

        if (canSurrender && hand.size == 2 && !t.soft) {
            val surrender = when (t.value) {
                16 -> (!pairIsEights && up in 9..11) || (pairIsEights && hitSoft17 && up == 11)
                15 -> up == 10 || (hitSoft17 && up == 11)
                17 -> hitSoft17 && up == 11
                else -> false
            }
            if (surrender) return BjAction.SURRENDER
        }

        if (pair) {
            val r = hand[0].rank
            val split = when {
                r == Rank.ACE || r == Rank.EIGHT -> true
                r.value >= 10 -> false
                r == Rank.NINE -> up in 2..6 || up == 8 || up == 9
                r == Rank.SEVEN -> up in 2..7
                r == Rank.SIX -> up in 2..6
                r == Rank.FIVE -> false
                r == Rank.FOUR -> up in 5..6
                else -> up in 2..7 // threes and twos
            }
            if (split) return BjAction.SPLIT
        }

        fun doubleElse(otherwise: BjAction) = if (canDouble) BjAction.DOUBLE else otherwise

        if (t.soft) {
            return when (t.value) {
                13, 14 -> if (up in 5..6) doubleElse(BjAction.HIT) else BjAction.HIT
                15, 16 -> if (up in 4..6) doubleElse(BjAction.HIT) else BjAction.HIT
                17 -> if (up in 3..6) doubleElse(BjAction.HIT) else BjAction.HIT
                18 -> when {
                    up in 3..6 || (hitSoft17 && up == 2) -> doubleElse(BjAction.STAND)
                    up in 2..8 -> BjAction.STAND
                    else -> BjAction.HIT
                }
                19 -> if (hitSoft17 && up == 6) doubleElse(BjAction.STAND) else BjAction.STAND
                else -> if (t.value >= 20) BjAction.STAND else BjAction.HIT
            }
        }
        return when {
            t.value <= 8 -> BjAction.HIT
            t.value == 9 -> if (up in 3..6) doubleElse(BjAction.HIT) else BjAction.HIT
            t.value == 10 -> if (up in 2..9) doubleElse(BjAction.HIT) else BjAction.HIT
            t.value == 11 -> if (up in 2..10 || (hitSoft17 && up == 11)) doubleElse(BjAction.HIT) else BjAction.HIT
            t.value == 12 -> if (up in 4..6) BjAction.STAND else BjAction.HIT
            t.value in 13..16 -> if (up in 2..6) BjAction.STAND else BjAction.HIT
            else -> BjAction.STAND
        }
    }
}
