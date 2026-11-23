package com.gamesuite.games.pigdice

/**
 * Pig and its variants. Pure Kotlin so every rule is unit tested.
 *
 * CLASSIC: roll one die as often as you like and add the rolls to your turn total; a 1 loses the
 * turn total and ends the turn; hold to bank the turn total. First to the target (100) wins.
 * BIG_PIG: two dice. One 1 busts, two 1s ("snake eyes") score 25 and you roll on, any other double
 * scores twice its sum, anything else scores the sum.
 * HOG: one roll per turn. Pick how many dice to roll; any 1 scores nothing, otherwise you bank their sum.
 * TWENTY_ONE: Pig with one extra rule: a safe roll that makes your turn total exactly 21 is banked for
 * you at once with a 10-point bonus.
 *
 * A turn (not Hog) must contain at least one roll, and the moment banked score plus turn total reaches
 * the target the player wins (no need to press Hold).
 */
enum class PigVariant(val id: String) {
    CLASSIC("classic"), BIG_PIG("bigpig"), HOG("hog"), TWENTY_ONE("twentyone");

    val diceCount: Int get() = if (this == BIG_PIG) 2 else 1
    /** Whether a turn is a single roll of a chosen number of dice (no Hold button). */
    val singleRoll: Boolean get() = this == HOG

    companion object {
        fun fromId(id: String?): PigVariant = values().firstOrNull { it.id == id } ?: CLASSIC
    }
}

object PigRules {
    const val DEFAULT_TARGET = 100
    const val HOG_MAX_DICE = 8
    const val SNAKE_EYES_POINTS = 25
    const val EXACT_TARGET = 21
    const val EXACT_BONUS = 10

    /** What a roll did. [points] are added to the turn total unless [bust]; [autoBank] ends the turn banking everything. */
    data class Roll(val dice: List<Int>, val bust: Boolean, val points: Int, val autoBank: Boolean, val note: String)

    object Note {
        const val SAFE = "safe"
        const val BUST = "bust"
        const val SNAKE_EYES = "snake_eyes"
        const val DOUBLES = "doubles"
        const val EXACT = "exact"
        const val HOG_SAFE = "hog_safe"
        const val HOG_BUST = "hog_bust"
        val ALL = setOf(SAFE, BUST, SNAKE_EYES, DOUBLES, EXACT, HOG_SAFE, HOG_BUST)
    }

    /** Scores [dice] for [variant] given the [turnTotal] so far. */
    fun evaluate(variant: PigVariant, dice: List<Int>, turnTotal: Int): Roll = when (variant) {
        PigVariant.CLASSIC -> {
            val d = dice[0]
            if (d == 1) Roll(dice, true, 0, false, Note.BUST) else Roll(dice, false, d, false, Note.SAFE)
        }
        PigVariant.BIG_PIG -> {
            val a = dice[0]
            val b = dice[1]
            when {
                a == 1 && b == 1 -> Roll(dice, false, SNAKE_EYES_POINTS, false, Note.SNAKE_EYES)
                a == 1 || b == 1 -> Roll(dice, true, 0, false, Note.BUST)
                a == b -> Roll(dice, false, 2 * (a + b), false, Note.DOUBLES)
                else -> Roll(dice, false, a + b, false, Note.SAFE)
            }
        }
        PigVariant.HOG ->
            if (dice.any { it == 1 }) Roll(dice, true, 0, true, Note.HOG_BUST) else Roll(dice, false, dice.sum(), true, Note.HOG_SAFE)
        PigVariant.TWENTY_ONE -> {
            val d = dice[0]
            when {
                d == 1 -> Roll(dice, true, 0, false, Note.BUST)
                turnTotal + d == EXACT_TARGET -> Roll(dice, false, d + EXACT_BONUS, true, Note.EXACT)
                else -> Roll(dice, false, d, false, Note.SAFE)
            }
        }
    }

    /** One possible result of a roll, for the expected-value policy. */
    class Outcome(val probability: Double, val bust: Boolean, val points: Int, val autoBank: Boolean)

    /** Every distinct result of a (non-Hog) roll at [turnTotal], with its probability. */
    fun outcomes(variant: PigVariant, turnTotal: Int): List<Outcome> {
        require(!variant.singleRoll) { "Hog has no repeated rolls" }
        val n = variant.diceCount
        var combos = 1
        repeat(n) { combos *= 6 }
        val merged = LinkedHashMap<Triple<Boolean, Int, Boolean>, Double>()
        for (c in 0 until combos) {
            val dice = List(n) { (c / pow6(it)) % 6 + 1 }
            val r = evaluate(variant, dice, turnTotal)
            val key = Triple(r.bust, r.points, r.autoBank)
            merged[key] = (merged[key] ?: 0.0) + 1.0 / combos
        }
        return merged.map { (k, p) -> Outcome(p, k.first, k.second, k.third) }
    }

    private fun pow6(e: Int): Int { var r = 1; repeat(e) { r *= 6 }; return r }

    /** Whether [count] is a legal number of dice to roll in this variant right now. */
    fun legalDiceCount(variant: PigVariant, count: Int): Boolean =
        if (variant.singleRoll) count in 1..HOG_MAX_DICE else count == variant.diceCount
}
