package com.gamesuite.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Source of "today" as a local calendar day number (days since 1970-01-01). Injected so tests can move time. */
fun interface DayClock {
    fun today(): Long
}

/** The real clock: the device's current local calendar day, re-resolving the zone on every call. */
object SystemDayClock : DayClock {
    override fun today(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
}

/** The local calendar day that contains [epochMillis] in [zone]. */
fun epochDayOf(epochMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()

/**
 * Consecutive-days-played streak. [lastDay] is the most recent local day with a match (null = never).
 * Stored as plain numbers so old / future JSON decodes tolerantly.
 */
@Serializable
data class DayStreak(
    val lastDay: Long? = null,
    val current: Int = 0,
    val best: Int = 0
)

/**
 * Records a match played on local day [today].
 *  - first ever play or a gap of 2+ days: the streak restarts at 1;
 *  - the next calendar day: +1;
 *  - the same day: unchanged;
 *  - [today] is BEFORE [DayStreak.lastDay] (the clock or time zone moved backwards): treated as the
 *    same day, so travelling west or fixing a clock never breaks (or inflates) a streak.
 */
fun DayStreak.recordPlay(today: Long): DayStreak {
    val last = lastDay ?: return DayStreak(lastDay = today, current = 1, best = maxOf(best, 1))
    val gap = today - last
    return when {
        gap <= 0L -> this
        gap == 1L -> {
            val next = current + 1
            DayStreak(lastDay = today, current = next, best = maxOf(best, next))
        }
        else -> DayStreak(lastDay = today, current = 1, best = maxOf(best, 1))
    }
}

/**
 * The streak to DISPLAY on [today]: a streak whose last play was before yesterday is already broken
 * (0), even though nothing has been written yet. Playing today still extends it from yesterday.
 */
fun DayStreak.currentOn(today: Long): Int {
    val last = lastDay ?: return 0
    return if (today - last <= 1L) current else 0
}

/** True when the streak is alive but has not been extended today (a nudge to play). */
fun DayStreak.atRiskOn(today: Long): Boolean {
    val last = lastDay ?: return false
    return current > 0 && today - last == 1L
}

/** The win-streak update for one finished match: a win extends it, a loss resets it, a draw or an unscored match leaves it. */
fun nextWinStreak(current: Int, outcome: LocalOutcome?): Int = when (outcome) {
    LocalOutcome.WIN -> current + 1
    LocalOutcome.LOSS -> 0
    LocalOutcome.DRAW, null -> current
}
