package com.gamesuite.games.yahtzee

/**
 * The 13 Yahtzee score boxes, in scorecard order. [face] is the die face an upper-section box
 * counts (0 for the lower section); [maxScore] is the most that box can ever hold (the Yahtzee
 * bonus is tracked separately, outside the boxes).
 */
enum class YahtzeeCategory(val face: Int, val maxScore: Int) {
    ACES(1, 5),
    TWOS(2, 10),
    THREES(3, 15),
    FOURS(4, 20),
    FIVES(5, 25),
    SIXES(6, 30),
    THREE_OF_A_KIND(0, 30),
    FOUR_OF_A_KIND(0, 30),
    FULL_HOUSE(0, 25),
    SMALL_STRAIGHT(0, 30),
    LARGE_STRAIGHT(0, 40),
    YAHTZEE(0, 50),
    CHANCE(0, 30);

    val isUpper: Boolean get() = face > 0

    companion object {
        val ALL: List<YahtzeeCategory> = values().toList()
        val UPPER: List<YahtzeeCategory> = ALL.filter { it.isUpper }
        val LOWER: List<YahtzeeCategory> = ALL.filter { !it.isUpper }
        const val COUNT = 13
    }
}

/** One player's scorecard: a score (or null while open) for each of the 13 boxes plus the Yahtzee bonus count. */
data class YahtzeeScorecard(
    val scores: List<Int?> = List(YahtzeeCategory.COUNT) { null },
    /** Extra Yahtzees rolled after a 50 was already scored in the Yahtzee box; each is worth 100. */
    val yahtzeeBonuses: Int = 0
) {
    operator fun get(category: YahtzeeCategory): Int? = scores[category.ordinal]

    fun isOpen(category: YahtzeeCategory): Boolean = scores[category.ordinal] == null

    /** A copy with [category] filled with [points]. */
    fun filled(category: YahtzeeCategory, points: Int): YahtzeeScorecard =
        copy(scores = scores.toMutableList().also { it[category.ordinal] = points })

    val upperSum: Int get() = YahtzeeCategory.UPPER.sumOf { this[it] ?: 0 }
    val upperBonus: Int
        get() = if (upperSum >= YahtzeeScoring.UPPER_BONUS_THRESHOLD) YahtzeeScoring.UPPER_BONUS else 0
    val lowerSum: Int get() = YahtzeeCategory.LOWER.sumOf { this[it] ?: 0 }
    val bonusPoints: Int get() = yahtzeeBonuses * YahtzeeScoring.YAHTZEE_BONUS
    val total: Int get() = upperSum + upperBonus + lowerSum + bonusPoints
    val filledCount: Int get() = scores.count { it != null }
    val isComplete: Boolean get() = filledCount == YahtzeeCategory.COUNT
}

/** Pure Yahtzee scoring rules (no state, no randomness). */
object YahtzeeScoring {
    const val DICE = 5
    const val UPPER_BONUS_THRESHOLD = 63
    const val UPPER_BONUS = 35
    const val YAHTZEE_BONUS = 100
    const val FULL_HOUSE_POINTS = 25
    const val SMALL_STRAIGHT_POINTS = 30
    const val LARGE_STRAIGHT_POINTS = 40
    const val YAHTZEE_POINTS = 50

    private fun isValid(dice: List<Int>): Boolean = dice.size == DICE && dice.all { it in 1..6 }

    private fun counts(dice: List<Int>): IntArray {
        val c = IntArray(7)
        for (d in dice) c[d]++
        return c
    }

    /** Five dice, all the same face. */
    fun isYahtzee(dice: List<Int>): Boolean = isValid(dice) && dice.all { it == dice[0] }

    fun hasSmallStraight(dice: List<Int>): Boolean {
        if (!isValid(dice)) return false
        val c = counts(dice)
        return (1..3).any { start -> (start..start + 3).all { c[it] > 0 } }
    }

    fun hasLargeStraight(dice: List<Int>): Boolean {
        if (!isValid(dice)) return false
        val c = counts(dice)
        return (1..2).any { start -> (start..start + 4).all { c[it] == 1 } }
    }

    /** Exactly a pair plus three of a kind (five of a kind is NOT a natural full house). */
    fun isFullHouse(dice: List<Int>): Boolean {
        if (!isValid(dice)) return false
        val c = counts(dice).filter { it > 0 }.sorted()
        return c == listOf(2, 3)
    }

    /**
     * Points for putting [dice] in [category]. [joker] applies the Joker rule's full values for the
     * fixed-score boxes (full house 25, straights 30 / 40) when the dice are a second Yahtzee.
     * Invalid dice (an unrolled hand) score 0.
     */
    fun score(category: YahtzeeCategory, dice: List<Int>, joker: Boolean = false): Int {
        if (!isValid(dice)) return 0
        val c = counts(dice)
        val sum = dice.sum()
        return when (category) {
            YahtzeeCategory.ACES, YahtzeeCategory.TWOS, YahtzeeCategory.THREES,
            YahtzeeCategory.FOURS, YahtzeeCategory.FIVES, YahtzeeCategory.SIXES ->
                c[category.face] * category.face
            YahtzeeCategory.THREE_OF_A_KIND -> if (c.any { it >= 3 }) sum else 0
            YahtzeeCategory.FOUR_OF_A_KIND -> if (c.any { it >= 4 }) sum else 0
            YahtzeeCategory.FULL_HOUSE -> if (joker || isFullHouse(dice)) FULL_HOUSE_POINTS else 0
            YahtzeeCategory.SMALL_STRAIGHT ->
                if (joker || hasSmallStraight(dice)) SMALL_STRAIGHT_POINTS else 0
            YahtzeeCategory.LARGE_STRAIGHT ->
                if (joker || hasLargeStraight(dice)) LARGE_STRAIGHT_POINTS else 0
            YahtzeeCategory.YAHTZEE -> if (isYahtzee(dice)) YAHTZEE_POINTS else 0
            YahtzeeCategory.CHANCE -> sum
        }
    }

    /** True when these dice are a Yahtzee and the Yahtzee box is already filled (with 50 or a 0): the Joker rule is in force. */
    fun jokerApplies(dice: List<Int>, card: YahtzeeScorecard): Boolean =
        isYahtzee(dice) && !card.isOpen(YahtzeeCategory.YAHTZEE)

    /** Points for [category] with the Joker rule applied automatically when it is in force. */
    fun scoreFor(category: YahtzeeCategory, dice: List<Int>, card: YahtzeeScorecard): Int =
        score(category, dice, jokerApplies(dice, card))

    /** The 100-point bonus these dice earn when scored now (a Yahtzee on top of a 50 in the Yahtzee box). */
    fun bonusFor(dice: List<Int>, card: YahtzeeScorecard): Int =
        if (isYahtzee(dice) && card[YahtzeeCategory.YAHTZEE] == YAHTZEE_POINTS) YAHTZEE_BONUS else 0

    /**
     * The boxes the player may fill with [dice]. Normally every open box. Under the (forced) Joker
     * rule: the matching upper box if open; otherwise any open lower box; otherwise any open upper
     * box (a forced zero or a few points).
     */
    fun legalCategories(dice: List<Int>, card: YahtzeeScorecard): List<YahtzeeCategory> {
        val open = YahtzeeCategory.ALL.filter { card.isOpen(it) }
        if (!jokerApplies(dice, card)) return open
        val matching = YahtzeeCategory.UPPER.first { it.face == dice[0] }
        if (card.isOpen(matching)) return listOf(matching)
        val lower = open.filter { !it.isUpper }
        if (lower.isNotEmpty()) return lower
        return open
    }
}
