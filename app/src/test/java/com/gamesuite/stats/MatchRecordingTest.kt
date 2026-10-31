package com.gamesuite.stats

import com.gamesuite.core.AchievementSnapshot
import com.gamesuite.core.Achievements
import com.gamesuite.core.DayStreak
import com.gamesuite.core.LocalOutcome
import com.gamesuite.core.MatchDetails
import com.gamesuite.core.PlayMode
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRecordingTest {

    private val families = mapOf(
        "chess" to "BOARD", "uno" to "CARD", "hangman" to "WORD", "sudoku" to "ARCADE_PUZZLE"
    )
    private val familyOf: (String) -> String? = { families[it] }

    private fun record(
        stats: Map<String, GameStats> = emptyMap(),
        progress: ProgressState = ProgressState(),
        game: String = "chess",
        outcome: LocalOutcome? = LocalOutcome.WIN,
        details: MatchDetails? = null,
        day: Long = 20_000L
    ) = recordMatchInto(
        stats, progress, game, game.uppercase(), outcome, details,
        atEpochMillis = day * 86_400_000L, today = day, familyOf = familyOf, familyCount = 4
    )

    private fun cpu(difficulty: CpuDifficulty? = null) = MatchDetails(
        mode = PlayMode.SINGLE_PLAYER_VS_BOT, hasBots = true, cpuDifficulty = difficulty, daily = false, humanCount = 1
    )

    // --- recording ---

    @Test
    fun `records wins losses draws and per game streaks`() {
        var r = record(outcome = LocalOutcome.WIN)
        r = record(r.stats, r.progress, outcome = LocalOutcome.WIN)
        r = record(r.stats, r.progress, outcome = LocalOutcome.DRAW)
        var g = r.stats.getValue("chess")
        assertEquals(3, g.matchesPlayed)
        assertEquals(2, g.wins)
        assertEquals(1, g.draws)
        assertEquals("a draw leaves the streak alone", 2, g.winStreak)
        r = record(r.stats, r.progress, outcome = LocalOutcome.LOSS)
        g = r.stats.getValue("chess")
        assertEquals(0, g.winStreak)
        assertEquals(2, g.bestWinStreak)
        assertEquals(1, g.losses)
    }

    @Test
    fun `a spectator match counts as played without moving the record`() {
        val g = record(outcome = null).stats.getValue("chess")
        assertEquals(1, g.matchesPlayed)
        assertEquals(0, g.wins + g.losses + g.draws)
    }

    @Test
    fun `overall win streak spans games`() {
        var r = record(game = "chess")
        r = record(r.stats, r.progress, game = "uno")
        r = record(r.stats, r.progress, game = "hangman")
        assertEquals(3, r.progress.overallWinStreak)
        r = record(r.stats, r.progress, game = "uno", outcome = LocalOutcome.LOSS)
        assertEquals(0, r.progress.overallWinStreak)
        assertEquals(3, r.progress.bestOverallWinStreak)
    }

    @Test
    fun `hard online and daily wins are counted only on a win`() {
        val hard = record(details = cpu(CpuDifficulty.HARD)).stats.getValue("chess")
        assertEquals(1, hard.winsOnHard)
        val easy = record(details = cpu(CpuDifficulty.EASY)).stats.getValue("chess")
        assertEquals(0, easy.winsOnHard)
        val hardLoss = record(outcome = LocalOutcome.LOSS, details = cpu(CpuDifficulty.HARD)).stats.getValue("chess")
        assertEquals(0, hardLoss.winsOnHard)

        val online = MatchDetails(PlayMode.ONLINE, hasBots = false, cpuDifficulty = null, daily = false, humanCount = 2)
        assertEquals(1, record(details = online).stats.getValue("chess").onlineWins)
        assertEquals(0, record(outcome = LocalOutcome.LOSS, details = online).stats.getValue("chess").onlineWins)

        val daily = MatchDetails(PlayMode.SINGLE_PLAYER_VS_BOT, hasBots = false, cpuDifficulty = null, daily = true, humanCount = 1)
        assertEquals(1, record(game = "sudoku", details = daily).stats.getValue("sudoku").dailiesSolved)
        assertEquals(0, record(game = "sudoku", outcome = LocalOutcome.LOSS, details = daily).stats.getValue("sudoku").dailiesSolved)
    }

    @Test
    fun `playing again clears the continue-row dismissal`() {
        val dismissed = mapOf("chess" to GameStats("chess", "Chess", dismissedFromContinue = true))
        assertFalse(record(stats = dismissed).stats.getValue("chess").dismissedFromContinue)
    }

    @Test
    fun `day streak advances through recording`() {
        var r = record(day = 20_000L)
        r = record(r.stats, r.progress, day = 20_001L)
        assertEquals(2, r.progress.dayStreak.current)
        r = record(r.stats, r.progress, day = 20_004L)
        assertEquals(1, r.progress.dayStreak.current)
        assertEquals(2, r.progress.dayStreak.best)
    }

    // --- unlocking ---

    @Test
    fun `first win unlocks once and is announced once`() {
        val r1 = record()
        assertTrue(Achievements.FIRST_WIN in r1.newlyUnlocked)
        assertEquals(20_000L, r1.progress.unlocked[Achievements.FIRST_WIN])
        assertTrue(Achievements.FIRST_WIN in r1.progress.pendingAnnounce)
        val r2 = record(r1.stats, r1.progress, day = 20_001L)
        assertFalse(Achievements.FIRST_WIN in r2.newlyUnlocked)
        assertEquals("the original unlock day is kept", 20_000L, r2.progress.unlocked[Achievements.FIRST_WIN])
    }

    @Test
    fun `a loss never unlocks the win achievements`() {
        val r = record(outcome = LocalOutcome.LOSS)
        assertTrue(r.newlyUnlocked.isEmpty())
    }

    @Test
    fun `play every family unlocks on the fourth family`() {
        var r = record(game = "chess")
        r = record(r.stats, r.progress, game = "uno")
        r = record(r.stats, r.progress, game = "hangman")
        assertFalse(Achievements.ALL_FAMILIES in r.progress.unlocked)
        r = record(r.stats, r.progress, game = "sudoku", outcome = LocalOutcome.LOSS)
        assertTrue(Achievements.ALL_FAMILIES in r.newlyUnlocked)
    }

    @Test
    fun `the toolkit and unknown games do not count as a family`() {
        var r = record(game = "partytoolkit", outcome = null)
        r = record(r.stats, r.progress, game = "mystery", outcome = null)
        val snap = buildAchievementSnapshot(r.stats.values, r.progress, familyOf, 4)
        assertEquals(0, snap.familiesPlayed)
        assertEquals(2, snap.gamesPlayed)
    }

    @Test
    fun `seven days in a row unlocks the streak achievement`() {
        var r = record(day = 20_000L, outcome = LocalOutcome.LOSS)
        for (d in 1..6) r = record(r.stats, r.progress, day = 20_000L + d, outcome = LocalOutcome.LOSS)
        assertTrue(Achievements.DAYS_7 in r.progress.unlocked)
    }

    // --- predicates over fixtures ---

    private fun snap(
        wins: Int = 0, streak: Int = 0, hard: Int = 0, online: Int = 0, daily: Int = 0,
        families: Int = 0, familiesTotal: Int = 4, days: Int = 0, games: Int = 0
    ) = AchievementSnapshot(
        totalMatches = wins, totalWins = wins, gamesPlayed = games, bestOverallWinStreak = streak,
        winsOnHard = hard, onlineWins = online, dailiesSolved = daily, familiesPlayed = families,
        familiesTotal = familiesTotal, bestDayStreak = days
    )

    private fun met(id: String, s: AchievementSnapshot) = Achievements.byId.getValue(id).isMet(s)

    @Test
    fun `predicates have exact thresholds`() {
        assertFalse(met(Achievements.FIRST_WIN, snap(wins = 0)))
        assertTrue(met(Achievements.FIRST_WIN, snap(wins = 1)))
        assertFalse(met(Achievements.WINS_10, snap(wins = 9)))
        assertTrue(met(Achievements.WINS_10, snap(wins = 10)))
        assertFalse(met(Achievements.WINS_100, snap(wins = 99)))
        assertTrue(met(Achievements.WINS_100, snap(wins = 100)))
        assertFalse(met(Achievements.STREAK_5, snap(streak = 4)))
        assertTrue(met(Achievements.STREAK_5, snap(streak = 5)))
        assertTrue(met(Achievements.HARD_WIN, snap(hard = 1)))
        assertFalse(met(Achievements.HARD_WIN, snap()))
        assertTrue(met(Achievements.DAILY, snap(daily = 1)))
        assertTrue(met(Achievements.ONLINE_WIN, snap(online = 1)))
        assertFalse(met(Achievements.DAYS_7, snap(days = 6)))
        assertTrue(met(Achievements.DAYS_7, snap(days = 7)))
        assertFalse(met(Achievements.GAMES_10, snap(games = 9)))
        assertTrue(met(Achievements.GAMES_10, snap(games = 10)))
    }

    @Test
    fun `all families needs every family and a real goal`() {
        assertFalse(met(Achievements.ALL_FAMILIES, snap(families = 3, familiesTotal = 4)))
        assertTrue(met(Achievements.ALL_FAMILIES, snap(families = 4, familiesTotal = 4)))
        assertFalse("a zero goal never unlocks", met(Achievements.ALL_FAMILIES, snap(families = 0, familiesTotal = 0)))
    }

    @Test
    fun `progress is clamped to the goal`() {
        val def = Achievements.byId.getValue(Achievements.WINS_10)
        assertEquals(4 to 10, def.progress(snap(wins = 4)))
        assertEquals(10 to 10, def.progress(snap(wins = 250)))
    }

    @Test
    fun `newlyMet skips what is already unlocked and keeps display order`() {
        val s = snap(wins = 10, streak = 5)
        val ids = Achievements.newlyMet(s, setOf(Achievements.FIRST_WIN))
        assertEquals(listOf(Achievements.WINS_10, Achievements.STREAK_5), ids)
    }

    @Test
    fun `achievement ids are unique`() {
        assertEquals(Achievements.all.size, Achievements.all.map { it.id }.toSet().size)
    }

    // --- persistence tolerance ---

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an old stats blob without the new fields decodes with zeros`() {
        val old = """{"chess":{"gameId":"chess","displayName":"Chess","matchesPlayed":5,"wins":3,"losses":2}}"""
        val g = json.decodeFromString<Map<String, GameStats>>(old).getValue("chess")
        assertEquals(5, g.matchesPlayed)
        assertEquals(0, g.winStreak)
        assertEquals(0, g.bestWinStreak)
        assertEquals(0, g.winsOnHard)
        assertEquals(0, g.onlineWins)
        assertEquals(0, g.dailiesSolved)
    }

    @Test
    fun `progress round trips and tolerates unknown or missing fields`() {
        val p = ProgressState(
            dayStreak = DayStreak(20_000L, 3, 5), overallWinStreak = 2, bestOverallWinStreak = 4,
            unlocked = mapOf("first_win" to 19_999L), pendingAnnounce = listOf("first_win")
        )
        assertEquals(p, json.decodeFromString<ProgressState>(json.encodeToString(p)))
        assertEquals(ProgressState(), json.decodeFromString<ProgressState>("{}"))
        val future = json.decodeFromString<ProgressState>("""{"overallWinStreak":7,"somethingNew":[1,2]}""")
        assertEquals(7, future.overallWinStreak)
    }

    @Test
    fun `streak summary shows an expired streak as zero`() {
        val p = ProgressState(dayStreak = DayStreak(20_000L, 4, 6), overallWinStreak = 2, bestOverallWinStreak = 3)
        assertEquals(4, streakSummary(p, 20_000L).dayStreak)
        val tomorrow = streakSummary(p, 20_001L)
        assertEquals(4, tomorrow.dayStreak)
        assertTrue(tomorrow.dayStreakAtRisk)
        val later = streakSummary(p, 20_003L)
        assertEquals(0, later.dayStreak)
        assertEquals(6, later.bestDayStreak)
        assertEquals(2, later.overallWinStreak)
    }
}
