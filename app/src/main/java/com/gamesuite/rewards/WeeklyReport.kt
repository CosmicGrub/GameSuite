package com.gamesuite.rewards

import com.gamesuite.core.GameTeaches
import com.gamesuite.core.Skill

/** Minutes played on one local day (from the play-time limiter's own history, never re-counted here). */
data class DayMinutes(val epochDay: Long, val minutes: Int)

data class GameCount(val gameId: String, val plays: Int)
data class SkillCount(val skill: Skill, val plays: Int)

/** A child's last seven days, ending [today]. All pure data for the parent dashboard card. */
data class WeeklyReport(
    val days: List<DayMinutes>,
    val totalMinutes: Int,
    val gamesPlayed: Int,
    val favourites: List<GameCount>,
    val skills: List<SkillCount>,
    val stickersEarned: Int
)

object WeeklyReportBuilder {
    const val DAYS = 7
    const val MAX_FAVOURITES = 3

    /**
     * @param weekMillis play time per day for the 7 days ending today, oldest first (PlayTimeLimiter.weekMillis);
     *   fewer or more than 7 values are normalised to the last 7 (missing days count as 0)
     * @param today the local epoch day of the last entry
     * Favourites are the most-played games (ties: alphabetical id, so the order is stable); skills count one play
     * per skill of each game played, most practised first (ties: enum order).
     */
    fun build(
        book: StickerBook,
        weekMillis: List<Long>,
        today: Long,
        skillsFor: (String) -> List<Skill> = GameTeaches::skillsFor
    ): WeeklyReport {
        val padded = (List(DAYS) { 0L } + weekMillis).takeLast(DAYS)
        val days = padded.mapIndexed { i, ms ->
            DayMinutes(today - (DAYS - 1) + i, (ms.coerceAtLeast(0L) / 60_000L).toInt())
        }
        val from = today - (DAYS - 1)
        val plays = book.plays.filter { it.day in from..today }
        val favourites = plays.groupingBy { it.gameId }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_FAVOURITES).map { GameCount(it.key, it.value) }
        val skillCounts = HashMap<Skill, Int>()
        plays.forEach { p -> skillsFor(p.gameId).forEach { skillCounts[it] = (skillCounts[it] ?: 0) + 1 } }
        val skills = skillCounts.entries
            .sortedWith(compareByDescending<Map.Entry<Skill, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { SkillCount(it.key, it.value) }
        val stickers = book.earned.values.count { it in from..today }
        return WeeklyReport(days, days.sumOf { it.minutes }, plays.size, favourites, skills, stickers)
    }
}
