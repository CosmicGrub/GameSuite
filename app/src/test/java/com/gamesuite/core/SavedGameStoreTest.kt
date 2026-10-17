package com.gamesuite.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedGameStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var today = 20_000L
    private var now = 1_000_000_000_000L

    private fun store(maxAge: Long = SavedGameStore.DEFAULT_MAX_AGE_MILLIS) =
        SavedGameStore(File(tmp.root, "saves"), epochDay = { today }, nowMillis = { now }, maxAgeMillis = maxAge)

    private fun game(route: String, snapshot: String = "{\"x\":1}", at: Long = now, day: Long = today) =
        SavedGame(route = route, label = "Label of $route", savedAtMillis = at, savedAtEpochDay = day, snapshot = snapshot)

    @Test
    fun `a saved game round-trips exactly`() {
        val s = store()
        val g = game("sudoku", snapshot = "{\"grid\":[1,2,3],\"note\":\"quote \\\" and unicode é\"}")
        s.save(g)
        assertEquals(g, s.load("sudoku"))
    }

    @Test
    fun `load is null when nothing was saved`() {
        assertNull(store().load("chess"))
        assertFalse(store().has("chess"))
    }

    @Test
    fun `saving again replaces the earlier save for that route only`() {
        val s = store()
        s.save(game("chess", snapshot = "one"))
        s.save(game("checkers", snapshot = "other"))
        s.save(game("chess", snapshot = "two"))
        assertEquals("two", s.load("chess")?.snapshot)
        assertEquals("other", s.load("checkers")?.snapshot)
    }

    @Test
    fun `delete removes the save and is harmless when there is none`() {
        val s = store()
        s.save(game("mancala"))
        s.delete("mancala")
        assertNull(s.load("mancala"))
        s.delete("mancala")
        s.delete("never-saved")
    }

    @Test
    fun `a corrupt file is treated as no save and cleaned up`() {
        val s = store()
        s.save(game("reversi"))
        val file = File(File(tmp.root, "saves"), "reversi.save.json")
        assertTrue(file.isFile)
        file.writeText("{ this is not json")
        assertNull(s.load("reversi"))
        assertFalse("the broken file is removed so it cannot keep failing", file.exists())
    }

    @Test
    fun `a daily save from an earlier day is expired, a same-day one is kept`() {
        val s = store()
        s.save(game("sudoku-daily", day = today))
        s.save(game("sudoku", day = today - 3))
        assertNotNull(s.load("sudoku-daily"))
        today += 1
        assertNull("yesterday's daily puzzle must not be offered", s.load("sudoku-daily"))
        assertNotNull("a free-play save is not tied to the calendar day", s.load("sudoku"))
    }

    @Test
    fun `any save older than the max age is expired`() {
        val s = store(maxAge = 1_000L)
        s.save(game("checkers", at = now))
        now += 999L
        assertNotNull(s.load("checkers"))
        now += 2L
        assertNull(s.load("checkers"))
    }

    @Test
    fun `all lists live saves newest first and drops expired ones`() {
        val s = store()
        s.save(game("a", at = now - 3_000))
        s.save(game("b", at = now - 1_000))
        s.save(game("c-daily", at = now - 2_000, day = today - 1))
        assertEquals(listOf("b", "a"), s.all().map { it.route })
    }

    @Test
    fun `a route with unsafe characters still saves and loads under that route`() {
        val s = store()
        s.save(game("odd/route?x=1"))
        assertEquals("odd/route?x=1", s.load("odd/route?x=1")?.route)
    }

    @Test
    fun `all keeps a save whose route needed sanitising for its file name`() {
        val s = store()
        s.save(game("odd/route?x=1"))
        assertEquals(listOf("odd/route?x=1"), s.all().map { it.route })
        assertNotNull("listing must not delete a good save", s.load("odd/route?x=1"))
    }

    @Test
    fun `a stray temp file from an interrupted write is never read as a save`() {
        val s = store()
        File(tmp.root, "saves").mkdirs()
        File(File(tmp.root, "saves"), "chess.save.json.tmp").writeText("half written")
        assertNull(s.load("chess"))
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun `savedAgoText buckets minutes hours and days`() {
        val base = 5_000_000_000L
        assertEquals("just now", savedAgoText(base, base + 30_000))
        assertEquals("5 min ago", savedAgoText(base, base + 5 * 60_000))
        assertEquals("3 h ago", savedAgoText(base, base + 3 * 60 * 60_000))
        assertEquals("yesterday", savedAgoText(base, base + 30 * 60 * 60_000L))
        assertEquals("4 days ago", savedAgoText(base, base + 4 * 24 * 60 * 60_000L))
        assertEquals("a clock that ran backwards reads as just now", "just now", savedAgoText(base, base - 10_000))
    }
}
