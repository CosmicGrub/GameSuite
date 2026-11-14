package com.gamesuite.core

import com.gamesuite.settings.CpuDifficulty

/**
 * The shared "Help the young player" rule of the two-player games (Hub option key [KEY]).
 *
 * When the option is on, the computer opposing a helped seat plays one level GENTLER than it was set to
 * (Hard becomes Medium, Medium becomes Easy, Easy stays Easy), so a child, or anyone newer to the game, wins
 * more often without the game feeling fixed. It is the same rule in every game on purpose (same wording, same
 * meaning); each engine applies it where it picks the computer's level, and only through [cpuLevel], which
 * returns the level unchanged whenever the option is off (the default), so nothing changes for everyone else.
 *
 * Offered in "vs Computer" only: between two people there is no computer strength to adjust. In that mode the
 * person is seat 0, so "on" helps seat 0. Pure model: no Android, no state.
 */
data class ExtraHelp(val helpedSeats: Set<Int> = emptySet()) {
    /** True when at least one seat is helped. */
    val active: Boolean get() = helpedSeats.isNotEmpty()

    fun helps(seat: Int): Boolean = seat in helpedSeats

    /**
     * The level the computer at [botSeat] plays at, given the [base] level it would use without help: one
     * lower when it opposes a helped seat (any helped seat other than itself), otherwise [base].
     */
    fun cpuLevel(base: CpuDifficulty, botSeat: Int): CpuDifficulty =
        if (helpedSeats.any { it != botSeat }) lower(base) else base

    companion object {
        /** The Hub option key; its value reaches the screen in `LaunchOptions.extras`. */
        const val KEY = "extraHelp"
        const val OFF = "off"
        const val ON = "on"

        val NONE = ExtraHelp()

        /** One level gentler; Easy has nowhere lower to go. */
        fun lower(level: CpuDifficulty): CpuDifficulty = when (level) {
            CpuDifficulty.HARD -> CpuDifficulty.MEDIUM
            CpuDifficulty.MEDIUM -> CpuDifficulty.EASY
            CpuDifficulty.EASY -> CpuDifficulty.EASY
        }

        /**
         * Reads the option from the launch [extras]: "on" helps seat 0 (the person in vs Computer), "p1".."pN" help
         * that 1-based seat, anything else (absent, "off", junk) helps nobody. Seats at or beyond [seats] are dropped.
         */
        fun fromExtras(extras: Map<String, String>, seats: Int = 2): ExtraHelp {
            val seat = when (val v = extras[KEY]?.trim()?.lowercase()) {
                ON, "true" -> 0 // a Hub switch stores "true"
                null -> return NONE
                else -> if (v.length >= 2 && v[0] == 'p') (v.drop(1).toIntOrNull() ?: return NONE) - 1 else return NONE
            }
            return if (seat in 0 until seats) ExtraHelp(setOf(seat)) else NONE
        }
    }
}
