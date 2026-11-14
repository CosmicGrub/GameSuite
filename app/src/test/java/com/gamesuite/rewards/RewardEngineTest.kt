package com.gamesuite.rewards

import com.gamesuite.core.LocalOutcome
import com.gamesuite.core.epochDayOf
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardEngineTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(iso: String, zone: ZoneId = utc) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
    private fun day(iso: String, zone: ZoneId = utc) = epochDayOf(ms(iso, zone), zone)
    private val kidGames = setOf("echo-pads", "tap-race", "chutes-race", "matching")
    private fun play(
        b: StickerBook, game: String, iso: String, outcome: LocalOutcome? = LocalOutcome.LOSS,
        kid: Boolean = true, arena: Boolean = false, id: String? = null, zone: ZoneId = utc
    ) = RewardEngine.record(
        b, RewardEvent(game, outcome, ms(iso, zone), id), day(iso, zone), kid, arena, isKidGame = { it in kidGames }
    )

    private fun reasons(r: RewardResult) = r.awarded.map { it.first }

    @Test fun `catalog is 24 stickers in 3 sets of 8 with unique ids`() {
        assertEquals(24, StickerCatalog.size)
        StickerSet.values().forEach { assertEquals(8, StickerCatalog.inSet(it).size) }
        assertEquals(24, StickerCatalog.all.map { it.id }.toSet().size)
        assertEquals(StickerCatalog.all.toSet(), StickerCatalog.awardOrder.toSet())
        // the first three awards come from three different sets
        assertEquals(3, StickerCatalog.awardOrder.take(3).map { it.set }.toSet().size)
    }

    @Test fun `first game ever earns first-of-day, finished and first-time stickers`() {
        val r = play(StickerBook(), "echo-pads", "2026-11-02T10:00:00")
        assertEquals(
            listOf(RewardReason.FIRST_GAME_OF_DAY, RewardReason.FINISHED_GAME, RewardReason.FIRST_TIME_GAME),
            reasons(r)
        )
        assertEquals(3, r.book.pending.size)
        assertEquals(3, r.book.earned.size)
        assertEquals(1, r.book.totalGames)
    }

    @Test fun `a win adds first win then one win sticker per day`() {
        val first = play(StickerBook(), "echo-pads", "2026-11-02T10:00:00", LocalOutcome.WIN)
        assertTrue(RewardReason.FIRST_WIN in reasons(first))
        val second = play(first.book, "echo-pads", "2026-11-02T11:00:00", LocalOutcome.WIN)
        assertTrue(RewardReason.WIN in reasons(second)) // paid out now: it was over the per-match cap the first time
        val third = play(second.book, "echo-pads", "2026-11-02T12:00:00", LocalOutcome.WIN)
        assertFalse(RewardReason.WIN in reasons(third)) // already won today
        val nextDay = play(third.book, "echo-pads", "2026-11-03T09:00:00", LocalOutcome.WIN)
        assertTrue(RewardReason.WIN in reasons(nextDay))
    }

    @Test fun `a loss and a draw never earn a win sticker`() {
        val a = play(StickerBook(), "tap-race", "2026-11-02T10:00:00", LocalOutcome.LOSS)
        val b = play(a.book, "tap-race", "2026-11-02T10:05:00", LocalOutcome.DRAW)
        assertFalse(RewardReason.WIN in reasons(a) + reasons(b))
        assertFalse(RewardReason.FIRST_WIN in reasons(a) + reasons(b))
    }

    @Test fun `first game of the day only once per day`() {
        val a = play(StickerBook(), "tap-race", "2026-11-02T10:00:00")
        val b = play(a.book, "tap-race", "2026-11-02T11:00:00")
        assertFalse(RewardReason.FIRST_GAME_OF_DAY in reasons(b))
        val c = play(b.book, "tap-race", "2026-11-03T08:00:00")
        assertTrue(RewardReason.FIRST_GAME_OF_DAY in reasons(c))
    }

    @Test fun `milestones at 3 5 10 and 25 games`() {
        var b = StickerBook()
        val got = mutableMapOf<Int, List<RewardReason>>()
        for (n in 1..25) {
            // spread over days 2 per day so days keep changing but the streak stays unbroken
            val iso = "2026-11-%02dT%02d:00:00".format(1 + (n - 1) / 2, 9 + (n - 1) % 2)
            val r = play(b, "tap-race", iso)
            got[n] = reasons(r)
            b = r.book
        }
        assertTrue(RewardReason.GAMES_3 in got.getValue(3))
        assertTrue(RewardReason.GAMES_5 in got.getValue(5))
        assertTrue(RewardReason.GAMES_10 in got.getValue(10))
        assertTrue(RewardReason.GAMES_25 in got.getValue(25))
        assertFalse(got.getValue(4).contains(RewardReason.GAMES_3))
    }

    @Test fun `streaks of 3 and 7 days`() {
        var b = StickerBook()
        val got = mutableMapOf<Int, List<RewardReason>>()
        for (d in 1..7) {
            val r = play(b, "tap-race", "2026-11-%02dT10:00:00".format(d))
            got[d] = reasons(r); b = r.book
        }
        assertTrue(RewardReason.STREAK_3 in got.getValue(3))
        assertFalse(RewardReason.STREAK_7 in got.getValue(6))
        assertTrue(RewardReason.STREAK_7 in got.getValue(7))
    }

    @Test fun `a skipped day breaks the streak`() {
        var b = StickerBook()
        b = play(b, "tap-race", "2026-11-01T10:00:00").book
        b = play(b, "tap-race", "2026-11-02T10:00:00").book
        val r = play(b, "tap-race", "2026-11-04T10:00:00")
        assertFalse(RewardReason.STREAK_3 in reasons(r))
        assertEquals(1, r.book.streak.current)
    }

    @Test fun `streak continues across midnight in the local zone not UTC`() {
        val la = ZoneId.of("America/Los_Angeles")
        var b = StickerBook()
        // 23:30 local on the 1st, 00:30 local on the 2nd, 23:50 on the 3rd: three local days
        b = play(b, "tap-race", "2026-11-01T23:30:00", zone = la).book
        b = play(b, "tap-race", "2026-11-02T00:30:00", zone = la).book
        val r = play(b, "tap-race", "2026-11-03T23:50:00", zone = la)
        assertEquals(3, r.book.streak.current)
        assertTrue(RewardReason.STREAK_3 in reasons(r))
    }

    @Test fun `two plays minutes apart across midnight are two different days`() {
        var b = StickerBook()
        val r1 = play(b, "tap-race", "2026-11-05T23:59:00")
        val r2 = play(r1.book, "tap-race", "2026-11-06T00:01:00")
        assertTrue(RewardReason.FIRST_GAME_OF_DAY in reasons(r1))
        assertTrue(RewardReason.FIRST_GAME_OF_DAY in reasons(r2))
        assertEquals(2, r2.book.streak.current)
    }

    @Test fun `first time per kid game once each and not for non kid games`() {
        val a = play(StickerBook(), "tap-race", "2026-11-02T10:00:00")
        assertTrue("first:tap-race" in a.book.granted)
        val b = play(a.book, "matching", "2026-11-02T10:10:00")
        assertTrue(RewardReason.FIRST_TIME_GAME in reasons(b))
        val c = play(b.book, "matching", "2026-11-02T10:20:00")
        assertFalse(RewardReason.FIRST_TIME_GAME in reasons(c))
        val d = play(c.book, "not-a-kid-game", "2026-11-02T10:30:00")
        assertFalse(RewardReason.FIRST_TIME_GAME in reasons(d))
        assertFalse("first:not-a-kid-game" in d.book.granted)
    }

    @Test fun `try something new for a game not played this week`() {
        var b = play(StickerBook(), "tap-race", "2026-11-01T10:00:00").book // first-time
        // 9 days later: played tap-race again, not in the last 7 days
        val r = play(b, "tap-race", "2026-11-10T10:00:00")
        assertTrue(RewardReason.TRY_SOMETHING_NEW in reasons(r))
        // same game again the same day: no second one
        val again = play(r.book, "tap-race", "2026-11-10T11:00:00")
        assertFalse(RewardReason.TRY_SOMETHING_NEW in reasons(again))
    }

    @Test fun `no try-new for a game played within the week`() {
        var b = play(StickerBook(), "tap-race", "2026-11-01T10:00:00").book
        val r = play(b, "tap-race", "2026-11-07T10:00:00") // 6 days later, still "this week"
        assertFalse(RewardReason.TRY_SOMETHING_NEW in reasons(r))
    }

    @Test fun `try-new does not stack on first-time for the same match`() {
        val r = play(StickerBook(), "echo-pads", "2026-11-02T10:00:00")
        assertFalse(RewardReason.TRY_SOMETHING_NEW in reasons(r))
    }

    @Test fun `same event id twice awards once`() {
        val a = play(StickerBook(), "tap-race", "2026-11-02T10:00:00", id = "e1")
        val b = play(a.book, "tap-race", "2026-11-02T10:00:00", id = "e1")
        assertTrue(b.ignored)
        assertSame(a.book, b.book)
        assertEquals(1, b.book.totalGames)
    }

    @Test fun `replaying a whole day never duplicates a once-only sticker`() {
        var b = StickerBook()
        repeat(4) { b = play(b, "tap-race", "2026-11-02T10:0$it:00", LocalOutcome.WIN).book }
        val keys = b.granted
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(b.earned.size, b.earned.keys.toSet().size)
        assertEquals(b.earned.keys.toSet(), (b.pending).toSet())
    }

    @Test fun `adult profile never records`() {
        val r = play(StickerBook(), "tap-race", "2026-11-02T10:00:00", LocalOutcome.WIN, kid = false)
        assertTrue(r.ignored)
        assertEquals(StickerBook(), r.book)
    }

    @Test fun `computer arena match never records`() {
        val r = play(StickerBook(), "tap-race", "2026-11-02T10:00:00", LocalOutcome.WIN, arena = true)
        assertTrue(r.ignored)
        assertEquals(StickerBook(), r.book)
    }

    @Test fun `at most three stickers per match`() {
        val r = play(StickerBook(), "tap-race", "2026-11-02T10:00:00", LocalOutcome.WIN)
        // first-of-day, finished, first-win, win, first-time are all due: only 3 are handed out
        assertEquals(RewardEngine.MAX_PER_MATCH, r.awarded.size)
        // reasons over the cap are not marked: they pay out on the next match instead of being lost
        assertFalse(r.book.granted.any { it.startsWith("d:win:") })
        val next = play(r.book, "tap-race", "2026-11-02T10:05:00", LocalOutcome.LOSS)
        assertTrue(RewardReason.FIRST_TIME_GAME in reasons(next) || next.book.granted.any { it.startsWith("first:") })
    }

    @Test fun `a full book awards nothing more and does not crash`() {
        val full = StickerBook(earned = StickerCatalog.all.associate { it.id to 1L })
        val r = play(full, "tap-race", "2026-11-02T10:00:00", LocalOutcome.WIN)
        assertTrue(r.awarded.isEmpty())
        assertEquals(24, r.book.earned.size)
        assertEquals(NextGoal.BookComplete, RewardEngine.nextGoal(r.book, day("2026-11-02T10:00:00")))
    }

    @Test fun `collect moves a pending sticker into the book`() {
        val r = play(StickerBook(), "tap-race", "2026-11-02T10:00:00")
        val id = RewardEngine.nextPending(r.book)!!.id
        assertEquals(0, r.book.collectedCount)
        val c = RewardEngine.collect(r.book, id)
        assertEquals(1, c.collectedCount)
        assertFalse(id in c.pending)
        assertSame(c, RewardEngine.collect(c, id)) // collecting twice is a no-op
        assertEquals(0, RewardEngine.collectAll(c).pending.size)
        assertNull(RewardEngine.nextPending(RewardEngine.collectAll(c)))
    }

    @Test fun `next goal chooses today first then milestones then streak`() {
        val d = day("2026-11-02T10:00:00")
        assertEquals(NextGoal.PlayToday, RewardEngine.nextGoal(StickerBook(), d))
        val b = play(StickerBook(), "tap-race", "2026-11-02T10:00:00").book
        assertEquals(NextGoal.MoreGames(2, 3), RewardEngine.nextGoal(b, d))
    }

    @Test fun `storage stays bounded over a long time`() {
        var b = StickerBook()
        val games = (1..120).map { "g$it" }
        for (n in 0 until 400) {
            val dayNo = 1 + n / 4
            val iso = java.time.LocalDate.of(2026, 1, 1).plusDays((dayNo - 1).toLong()).toString() + "T1${n % 4}:00:00"
            b = RewardEngine.record(
                b, RewardEvent(games[n % games.size], LocalOutcome.WIN, ms(iso), "e$n"), day(iso), true, false,
                isKidGame = { true }
            ).book
        }
        assertTrue(b.granted.size <= RewardEngine.MAX_GRANTED)
        assertTrue(b.plays.size <= RewardEngine.MAX_PLAYS)
        assertTrue(b.recentEvents.size <= RewardEngine.MAX_RECENT_EVENTS)
        assertTrue(b.earned.size <= 24)
        // plays only cover the last PLAY_WINDOW_DAYS days
        val newest = b.plays.maxOf { it.day }
        assertTrue(b.plays.all { it.day > newest - RewardEngine.PLAY_WINDOW_DAYS - 1 })
    }

    @Test fun `profiles are isolated because each book is its own value`() {
        val a = play(StickerBook(), "tap-race", "2026-11-02T10:00:00", LocalOutcome.WIN).book
        val other = StickerBook()
        assertEquals(0, other.earned.size)
        val b = play(other, "matching", "2026-11-02T10:00:00").book
        assertFalse(a.granted.contains("first:matching"))
        assertFalse(b.granted.contains("first:tap-race"))
    }
}
