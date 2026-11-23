package com.gamesuite.games.liferoad

/** Every tunable number of Life Road in one place (generated from the design doc tables; section 2). Balance changes edit only this file. */
object LifeRoadRules {
    const val SPIN_MAX: Int = 10
    const val START_CASH: Int = 100
    const val JUNIOR_START_CASH: Int = 150
    const val TUITION: Int = 80
    const val LOAN_BLOCK: Int = 50
    const val LOAN_OWED: Int = 60
    const val MAX_LOANS: Int = 3
    const val COVER_PRICE: Int = 20
    const val RAISE_STEP: Int = 10
    const val MAX_RAISES: Int = 3
    const val ODD_JOBS: Int = 10
    const val TIPS_PER_SPIN: Int = 2
    const val PAY_LANDING_BONUS: Int = 10
    const val REST_COIN: Int = 5
    const val CAMPUS_COIN: Int = 8
    const val FAMILY_CAP: Int = 4
    const val MEMORY_CAP: Int = 6
    const val FAMILY_VALUE: Int = 30
    const val MEMORY_VALUE: Int = 20
    const val COMFORT_VALUE: Int = 15
    const val HOUSE_CAP: Int = 2
    const val FRESH_PENALTY: Int = 40
    const val FRESH_HELP: Int = 20
    const val QUICK_SALE_PCT: Int = 80
    const val GRAND_FEE: Int = 40
    const val GRAND_SHIFT: Int = 2
    const val BOLD_NUM: Int = 3
    const val BOLD_DEN: Int = 2
    const val MILESTONE_SPIN_GOAL: Int = 6
    const val MILESTONE_BONUS: Int = 10
    const val BIG_HEAD_COINS: Int = 50
    const val SMALL_HEAD_LUCKY: Int = 1
    const val BIG_HEAD_LUCKY: Int = 2

    /** Retirement bonus by arrival order (1st, 2nd, ...). Later arrivals and late retirees get 0. */
    val RETIRE_BONUS: List<Int> = listOf(80, 50, 30, 20, 10, 10)

    /** Market roll multiplier in tenths, indexed by spin 1..10 (index 0 = spin 1). */
    val MARKET_MULT_X10: List<Int> = listOf(7, 8, 9, 10, 11, 12, 12, 13, 14, 16)
    val MARKET_MULT_JUNIOR_X10: List<Int> = listOf(10, 10, 10, 11, 11, 12, 12, 13, 14, 16)

    /** Lucky wheel bands: spin from, spin to, coins. */
    val WHEEL: List<Triple<Int, Int, Int>> = listOf(Triple(1, 3, 10), Triple(4, 7, 20), Triple(8, 9, 40), Triple(10, 10, 80))

    /** CPU think time in milliseconds (min, max) before it acts, so a turn never looks instant. */
    const val BOT_THINK_MIN_MS: Int = 600
    const val BOT_THINK_MAX_MS: Int = 1200

    /** Sale price of a home: price x multiplier, in whole coins (rounded down). [spin] is 1..10 AFTER any lane shift (capped at 10). */
    fun marketValue(price: Int, spin: Int, junior: Boolean): Int {
        val table = if (junior) MARKET_MULT_JUNIOR_X10 else MARKET_MULT_X10
        return price * table[spin.coerceIn(1, SPIN_MAX) - 1] / 10
    }

    /** Coins paid by the lucky wheel for [spin]. */
    fun wheelCoins(spin: Int): Int = WHEEL.firstOrNull { spin in it.first..it.second }?.third ?: 0

    /** Coins scaled for a bold square: x1.5, rounded to the nearest 5 with halves up (integer maths, so it is identical everywhere). Sign is kept. */
    fun boldCoins(amount: Int): Int {
        val rounded = ((2 * kotlin.math.abs(amount) * BOLD_NUM + 5 * BOLD_DEN) / (10 * BOLD_DEN)) * 5
        return if (amount < 0) -rounded else rounded
    }
}
