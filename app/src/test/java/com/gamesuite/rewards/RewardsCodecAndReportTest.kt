package com.gamesuite.rewards

import com.gamesuite.core.DayStreak
import com.gamesuite.core.Skill
import com.gamesuite.stats.quarantineValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardsCodecAndReportTest {
    // ---- codec ----------------------------------------------------------------------------

    @Test fun `round trip keeps everything`() {
        val b = StickerBook(
            earned = mapOf("animals_cat" to 20000L, "space_star" to 20001L),
            pending = listOf("space_star"),
            granted = listOf("d:first:20001", "finish:first"),
            totalGames = 7,
            streak = DayStreak(20001L, 2, 3),
            plays = listOf(PlayEntry(20001L, "tap-race", true)),
            recentEvents = listOf("e1")
        )
        val d = RewardsCodec.decode(RewardsCodec.encode(b))
        assertEquals(b, d.book)
        assertNull(d.corruptRaw)
    }

    @Test fun `missing or blank decodes as empty without corruption`() {
        assertEquals(StickerBook(), RewardsCodec.decode(null).book)
        assertNull(RewardsCodec.decode("  ").corruptRaw)
    }

    @Test fun `garbage is reported as corrupt and decodes to an empty book`() {
        val d = RewardsCodec.decode("{not json")
        assertEquals(StickerBook(), d.book)
        assertEquals("{not json", d.corruptRaw)
        val wrongShape = RewardsCodec.decode("[1,2,3]")
        assertNotNull(wrongShape.corruptRaw)
    }

    @Test fun `quarantine keeps the first corrupt blob and never replaces it`() {
        assertEquals("a", quarantineValue(null, "a"))
        assertNull(quarantineValue("a", "b"))
        assertNull(quarantineValue(null, null))
    }

    @Test fun `unknown fields from a newer build are ignored`() {
        val d = RewardsCodec.decode("""{"totalGames":4,"somethingNew":{"x":1}}""")
        assertEquals(4, d.book.totalGames)
        assertNull(d.corruptRaw)
    }

    @Test fun `sanitize drops unknown stickers and orphan pending ids`() {
        val d = RewardsCodec.decode(
            """{"earned":{"animals_cat":1,"hacked_sticker":2},"pending":["hacked_sticker","animals_cat","animals_cat"],"totalGames":-5}"""
        )
        assertEquals(setOf("animals_cat"), d.book.earned.keys)
        assertEquals(listOf("animals_cat"), d.book.pending)
        assertEquals(0, d.book.totalGames)
    }

    @Test fun `oversized lists are trimmed on decode`() {
        val many = (1..500).joinToString(",") { "\"k$it\"" }
        val d = RewardsCodec.decode("""{"granted":[$many]}""")
        assertEquals(RewardEngine.MAX_GRANTED, d.book.granted.size)
        assertEquals("k500", d.book.granted.last())
    }

    // ---- weekly report -------------------------------------------------------------------

    private val today = 20_000L
    private val min = 60_000L
    private fun skills(id: String) = when (id) {
        "a" -> listOf(Skill.MEMORY, Skill.FINE_MOTOR)
        "b" -> listOf(Skill.MEMORY)
        else -> emptyList()
    }

    @Test fun `report counts only the last seven days`() {
        val book = StickerBook(
            plays = listOf(
                PlayEntry(today - 7, "a"), // outside
                PlayEntry(today - 6, "a"), PlayEntry(today - 1, "a"), PlayEntry(today, "b")
            ),
            earned = mapOf("animals_cat" to today - 8, "space_star" to today - 2, "food_apple" to today)
        )
        val r = WeeklyReportBuilder.build(book, List(7) { 10 * min }, today, ::skills)
        assertEquals(3, r.gamesPlayed)
        assertEquals(2, r.stickersEarned)
        assertEquals(70, r.totalMinutes)
        assertEquals(today - 6, r.days.first().epochDay)
        assertEquals(today, r.days.last().epochDay)
    }

    @Test fun `favourites rank by plays then id and cap at three`() {
        val plays = listOf("z", "z", "m", "m", "c", "c", "q").mapIndexed { i, g -> PlayEntry(today - (i % 3), g) }
        val r = WeeklyReportBuilder.build(StickerBook(plays = plays), emptyList(), today, ::skills)
        assertEquals(listOf("c", "m", "z"), r.favourites.map { it.gameId })
        assertEquals(3, r.favourites.size)
    }

    @Test fun `skills are summed per play and ordered by count`() {
        val plays = listOf(PlayEntry(today, "a"), PlayEntry(today, "a"), PlayEntry(today, "b"), PlayEntry(today, "unknown"))
        val r = WeeklyReportBuilder.build(StickerBook(plays = plays), emptyList(), today, ::skills)
        assertEquals(Skill.MEMORY to 3, r.skills[0].skill to r.skills[0].plays)
        assertEquals(Skill.FINE_MOTOR to 2, r.skills[1].skill to r.skills[1].plays)
        assertEquals(2, r.skills.size)
    }

    @Test fun `short or empty minutes are padded with zero days`() {
        val r = WeeklyReportBuilder.build(StickerBook(), listOf(5 * min, 6 * min), today, ::skills)
        assertEquals(7, r.days.size)
        assertEquals(listOf(0, 0, 0, 0, 0, 5, 6), r.days.map { it.minutes })
        assertEquals(11, r.totalMinutes)
        assertTrue(r.favourites.isEmpty() && r.skills.isEmpty())
        val long = WeeklyReportBuilder.build(StickerBook(), List(10) { min }, today, ::skills)
        assertEquals(7, long.days.size)
    }

    @Test fun `negative minutes never go below zero`() {
        val r = WeeklyReportBuilder.build(StickerBook(), listOf(-5L * min), today, ::skills)
        assertEquals(0, r.totalMinutes)
    }
}
