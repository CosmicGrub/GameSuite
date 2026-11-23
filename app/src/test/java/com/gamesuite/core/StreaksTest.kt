package com.gamesuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class StreaksTest {

    private val day = 20_000L

    @Test
    fun `first play starts a streak of one`() {
        val s = DayStreak().recordPlay(day)
        assertEquals(DayStreak(lastDay = day, current = 1, best = 1), s)
    }

    @Test
    fun `playing again the same day changes nothing`() {
        val s = DayStreak().recordPlay(day).recordPlay(day)
        assertEquals(1, s.current)
    }

    @Test
    fun `consecutive days extend and a gap resets while best is kept`() {
        var s = DayStreak()
        for (d in day..day + 3) s = s.recordPlay(d)
        assertEquals(4, s.current)
        assertEquals(4, s.best)
        s = s.recordPlay(day + 6) // skipped two days
        assertEquals(1, s.current)
        assertEquals(4, s.best)
        assertEquals(day + 6, s.lastDay)
    }

    @Test
    fun `a clock or zone that moves backwards neither breaks nor inflates the streak`() {
        var s = DayStreak().recordPlay(day).recordPlay(day + 1)
        val before = s
        s = s.recordPlay(day) // travelled west, local day is "yesterday" again
        assertEquals(before, s)
        s = s.recordPlay(day + 2) // next real day still continues from lastDay
        assertEquals(3, s.current)
    }

    @Test
    fun `displayed streak expires after a missed day without any write`() {
        val s = DayStreak().recordPlay(day).recordPlay(day + 1)
        assertEquals(2, s.currentOn(day + 1))
        assertEquals("yesterday's streak is still alive today", 2, s.currentOn(day + 2))
        assertEquals("a full missed day breaks it", 0, s.currentOn(day + 3))
        assertEquals(0, DayStreak().currentOn(day))
    }

    @Test
    fun `at risk only when last play was yesterday`() {
        val s = DayStreak().recordPlay(day)
        assertFalse(s.atRiskOn(day))
        assertTrue(s.atRiskOn(day + 1))
        assertFalse(s.atRiskOn(day + 2))
        assertFalse(DayStreak().atRiskOn(day))
    }

    @Test
    fun `midnight rollover in the local zone moves the streak a day`() {
        val zone = ZoneId.of("America/New_York")
        fun millis(h: Int, m: Int, dayOfMonth: Int) =
            LocalDateTime.of(2026, 3, dayOfMonth, h, m).atZone(zone).toInstant().toEpochMilli()

        val late = epochDayOf(millis(23, 59, 10), zone)
        val justAfter = epochDayOf(millis(0, 1, 11), zone)
        assertEquals("two minutes apart but different local days", late + 1, justAfter)

        val s = DayStreak().recordPlay(late).recordPlay(justAfter)
        assertEquals(2, s.current)
    }

    @Test
    fun `the same instant is a different day in different zones`() {
        val instant = LocalDateTime.of(2026, 6, 1, 23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val utc = epochDayOf(instant, ZoneId.of("UTC"))
        val tokyo = epochDayOf(instant, ZoneId.of("Asia/Tokyo"))
        val hawaii = epochDayOf(instant, ZoneId.of("Pacific/Honolulu"))
        assertEquals(utc + 1, tokyo)
        assertEquals(utc, hawaii)
    }

    @Test
    fun `a time zone change across midnight does not break a streak`() {
        // Played 22:00 in New York on day D (= 02:00Z D+1), then flew to Los Angeles and played
        // at 22:00 local on D+1: consecutive local days in each zone's own terms.
        val ny = ZoneId.of("America/New_York")
        val la = ZoneId.of("America/Los_Angeles")
        val first = LocalDateTime.of(2026, 3, 10, 22, 0).atZone(ny).toInstant().toEpochMilli()
        val second = LocalDateTime.of(2026, 3, 11, 22, 0).atZone(la).toInstant().toEpochMilli()
        val s = DayStreak().recordPlay(epochDayOf(first, ny)).recordPlay(epochDayOf(second, la))
        assertEquals(2, s.current)
    }

    @Test
    fun `injected clock drives today`() {
        var now = day
        val clock = DayClock { now }
        var s = DayStreak().recordPlay(clock.today())
        now += 1
        s = s.recordPlay(clock.today())
        assertEquals(2, s.current)
        now += 5
        assertEquals(0, s.currentOn(clock.today()))
    }

    @Test
    fun `win streak extends on wins resets on losses and survives draws and unscored matches`() {
        var w = 0
        w = nextWinStreak(w, LocalOutcome.WIN)
        w = nextWinStreak(w, LocalOutcome.WIN)
        assertEquals(2, w)
        assertEquals(2, nextWinStreak(w, LocalOutcome.DRAW))
        assertEquals(2, nextWinStreak(w, null))
        assertEquals(0, nextWinStreak(w, LocalOutcome.LOSS))
    }

    @Test
    fun `fresh state has no last day`() {
        assertNull(DayStreak().lastDay)
    }
}
