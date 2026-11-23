package com.gamesuite.profiles

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTimeLimiterTest {
    private val zone = ZoneId.of("UTC")
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private val noon = "2026-01-05T10:00:00Z"
    // The legacy tests pass explicit "now" values that jump around; a boot id that changes on every call makes the
    // limiter treat each call as "monotonic reference unavailable" and trust the supplied wall time (the clock
    // tampering behaviour has its own tests at the bottom with injected monotonic clocks).
    private var bootCounter = 0L
    private val limiter = PlayTimeLimiter({ at(noon) }, { zone }, bootId = { bootCounter++ })
    private val min = 60_000L

    private fun played(minutes: Int, now: String = noon, base: KidControls = KidControls()): KidControls {
        var c = limiter.rollover(base, at(now))
        repeat(minutes) { c = limiter.accrue(c, min, at(now)) }
        return c
    }

    @Test fun `under budget is ok`() {
        val s = limiter.check(played(10), inRound = false, nowMillis = at(noon))
        assertEquals(LimitState.OK, s.state)
        assertTrue(s.canStartNewGame)
        assertFalse(s.mustStopNow)
        assertEquals(50 * min, s.remainingMillis)
    }

    @Test fun `warns at five minutes left`() {
        val now = at(noon)
        assertEquals(LimitState.OK, limiter.check(played(54), false, now).state)
        assertEquals(LimitState.WARN, limiter.check(played(55), false, now).state)
        assertEquals(LimitState.WARN, limiter.check(played(59), false, now).state)
    }

    @Test fun `limit reached stops at once when idle`() {
        val s = limiter.check(played(60), inRound = false, nowMillis = at(noon))
        assertEquals(LimitState.TIME_UP, s.state)
        assertFalse(s.canStartNewGame)
        assertTrue(s.mustStopNow)
    }

    @Test fun `finish the round never cuts a game until it ends or grace runs out`() {
        val now = at(noon)
        val s = limiter.check(played(62), inRound = true, nowMillis = now)
        assertEquals(LimitState.TIME_UP, s.state)
        assertFalse(s.canStartNewGame)
        assertFalse(s.mustStopNow)
        assertTrue(limiter.check(played(62), inRound = false, nowMillis = now).mustStopNow)
        assertTrue(limiter.check(played(70), inRound = true, nowMillis = now).mustStopNow)
    }

    @Test fun `no daily limit means unlimited`() {
        val c = played(500, base = KidControls(dailyMinutes = 0))
        val s = limiter.check(c, false, at(noon))
        assertEquals(LimitState.OK, s.state)
        assertNull(s.remainingMillis)
    }

    @Test fun `usage resets at local midnight and yesterday goes to history`() {
        val c = played(45)
        val next = limiter.rollover(c, at("2026-01-06T00:00:00Z"))
        assertEquals(0L, next.usageMillis)
        assertEquals(45 * min, next.dailyHistory[limiter.epochDay(at(noon))])
        assertEquals(LimitState.OK, limiter.check(next, false, at("2026-01-06T00:00:00Z")).state)
    }

    @Test fun `midnight is local not utc`() {
        var b = 0L
        val tokyo = PlayTimeLimiter({ 0L }, { ZoneId.of("Asia/Tokyo") }, bootId = { b++ })
        val c = tokyo.accrue(KidControls(), min, at("2026-01-05T14:59:00Z"))
        val d = tokyo.rollover(c, at("2026-01-05T15:00:00Z"))
        assertEquals(0L, d.usageMillis)
        val same = tokyo.rollover(c, at("2026-01-05T14:59:30Z"))
        assertEquals(min, same.usageMillis)
    }

    @Test fun `accrual is clamped per tick and ignores negatives`() {
        val now = at(noon)
        assertEquals(min, limiter.accrue(KidControls(), 10 * min, now).usageMillis)
        assertEquals(0L, limiter.accrue(KidControls(), -5, now).usageMillis)
    }

    @Test fun `quiet hours same day window`() {
        val c = KidControls(quietEnabled = true, quietStartMinute = 12 * 60, quietEndMinute = 13 * 60, dailyMinutes = 0)
        assertFalse(limiter.isQuietNow(c, at("2026-01-05T11:59:00Z")))
        assertTrue(limiter.isQuietNow(c, at("2026-01-05T12:00:00Z")))
        assertFalse(limiter.isQuietNow(c, at("2026-01-05T13:00:00Z")))
    }

    @Test fun `quiet hours crossing midnight`() {
        val c = KidControls(quietEnabled = true, quietStartMinute = 20 * 60, quietEndMinute = 7 * 60, dailyMinutes = 0)
        assertTrue(limiter.isQuietNow(c, at("2026-01-05T21:00:00Z")))
        assertTrue(limiter.isQuietNow(c, at("2026-01-06T06:59:00Z")))
        assertFalse(limiter.isQuietNow(c, at("2026-01-06T07:00:00Z")))
        assertFalse(limiter.isQuietNow(c, at("2026-01-05T15:00:00Z")))
    }

    @Test fun `quiet hours off never blocks`() {
        val c = KidControls(quietEnabled = false, dailyMinutes = 0)
        assertFalse(limiter.isQuietNow(c, at("2026-01-05T23:00:00Z")))
    }

    @Test fun `quiet hours stop idle play but let a round finish`() {
        val c = KidControls(quietEnabled = true, quietStartMinute = 20 * 60, quietEndMinute = 7 * 60, dailyMinutes = 0)
        val now = at("2026-01-05T20:03:00Z")
        val idle = limiter.check(c, false, now)
        assertEquals(LimitState.QUIET_HOURS, idle.state)
        assertTrue(idle.mustStopNow)
        assertFalse(idle.canStartNewGame)
        assertFalse(limiter.check(c, true, now).mustStopNow)
        assertTrue(limiter.check(c, true, at("2026-01-05T20:11:00Z")).mustStopNow)
    }

    @Test fun `quiet start shortens the remaining time and warns`() {
        val c = KidControls(quietEnabled = true, quietStartMinute = 10 * 60 + 3, quietEndMinute = 12 * 60, dailyMinutes = 120)
        val s = limiter.check(c, false, at(noon))
        assertEquals(LimitState.WARN, s.state)
        assertEquals(3 * min, s.remainingMillis)
    }

    @Test fun `week bars cover seven days ending today`() {
        val now = at("2026-01-07T10:00:00Z")
        val today = limiter.epochDay(now)
        val c = KidControls(
            usageEpochDay = today, usageMillis = 20 * min,
            dailyHistory = mapOf(today - 1 to 5 * min, today - 6 to 9 * min)
        )
        val w = limiter.weekMillis(c, now)
        assertEquals(7, w.size)
        assertEquals(9 * min, w[0])
        assertEquals(5 * min, w[5])
        assertEquals(20 * min, w[6])
        assertEquals(0L, w[3])
    }

    // ---- clock / time zone tampering (injected wall + monotonic clocks) ----

    private class Clocks(var wall: Long, var mono: Long = 10_000L, var boot: Long = 1L)

    private fun tamperLimiter(k: Clocks, z: ZoneId = ZoneId.of("UTC")) =
        PlayTimeLimiter({ k.wall }, { z }, elapsed = { k.mono }, bootId = { k.boot })

    /** Plays [minutes] minutes honestly: wall and monotonic advance together, one accrual per minute. */
    private fun tamperPlay(l: PlayTimeLimiter, k: Clocks, c0: KidControls, minutes: Int): KidControls {
        var c = c0
        repeat(minutes) {
            k.wall += min
            k.mono += min
            c = l.accrue(c, min, k.wall)
        }
        return c
    }

    @Test fun `clock set forward a day does not give a fresh budget`() {
        val k = Clocks(at(noon))
        val l = tamperLimiter(k)
        val c = tamperPlay(l, k, KidControls(), 40)
        k.wall += 24 * 60 * min // tamper: date +1 day, the monotonic clock barely moves
        k.mono += 1_000L
        assertEquals(40 * min, l.playedToday(c, k.wall))
        assertEquals(20 * min, l.check(c, false, k.wall).remainingMillis)
        val c2 = l.accrue(c, min, k.wall)
        assertEquals(41 * min, c2.usageMillis)
        assertEquals(c.usageEpochDay, c2.usageEpochDay)
    }

    @Test fun `clock set back never resets or deletes usage`() {
        val k = Clocks(at(noon))
        val l = tamperLimiter(k)
        val c = tamperPlay(l, k, KidControls(), 30)
        k.wall -= 48 * 60 * min
        assertEquals(30 * min, l.playedToday(c, k.wall))
        val c2 = l.accrue(c, min, k.wall)
        assertEquals(31 * min, c2.usageMillis)
        assertEquals(c.usageEpochDay, c2.usageEpochDay)
        assertEquals(c.dailyHistory, c2.dailyHistory)
    }

    @Test fun `without any anchor a clock behind the stored day keeps the usage (high-water mark)`() {
        val l = PlayTimeLimiter({ 0L }, { zone }, bootId = { 0L })
        val today = l.epochDay(at(noon))
        val c = KidControls(usageEpochDay = today, usageMillis = 25 * min, dailyHistory = mapOf(today - 1 to 7 * min))
        val back = at("2026-01-03T10:00:00Z")
        assertEquals(c, l.rollover(c, back))
        assertEquals(25 * min, l.playedToday(c, back))
        assertEquals(7 * min, l.accrue(c, 0, back).dailyHistory[today - 1])
    }

    @Test fun `a real midnight on the monotonic clock still rolls over`() {
        val k = Clocks(at("2026-01-05T23:50:00Z"))
        val l = tamperLimiter(k)
        val c = tamperPlay(l, k, KidControls(), 5)
        k.wall += 20 * min // honest 20 minutes later: 00:15 on the 6th
        k.mono += 20 * min
        assertEquals(0L, l.playedToday(c, k.wall))
        val c2 = l.accrue(c, min, k.wall)
        assertEquals(min, c2.usageMillis)
        assertEquals(5 * min, c2.dailyHistory[c.usageEpochDay])
    }

    @Test fun `small wall clock drift is trusted but a large jump is ignored`() {
        val k = Clocks(at(noon))
        val l = tamperLimiter(k)
        val c = tamperPlay(l, k, KidControls(), 3)
        val real = l.trustedNow(c, k.wall)
        k.wall += 2 * min // an NTP nudge, inside the tolerance
        assertEquals(k.wall, l.trustedNow(c, k.wall))
        k.wall += 3 * 60 * min // a 3 hour jump
        assertEquals(real, l.trustedNow(c, k.wall))
    }

    @Test fun `quiet hours cannot be skipped by moving the clock`() {
        val k = Clocks(at("2026-01-05T19:55:00Z"))
        val l = tamperLimiter(k)
        val q = KidControls(quietEnabled = true, quietStartMinute = 20 * 60, quietEndMinute = 7 * 60, dailyMinutes = 0)
        val c = l.accrue(q, min, k.wall)
        k.wall += 10 * min // 20:05 honest
        k.mono += 10 * min
        assertTrue(l.isQuietNow(c, k.wall))
        k.wall = at("2026-01-05T12:00:00Z") // tamper: pretend it is noon (the monotonic clock only moved 10 minutes)
        assertTrue(l.isQuietNow(c, k.wall))
        assertEquals(LimitState.QUIET_HOURS, l.check(c, false, k.wall).state)
    }

    @Test fun `after a reboot the wall clock is used but never earlier than the anchor`() {
        val k = Clocks(at(noon))
        val l = tamperLimiter(k)
        val c = tamperPlay(l, k, KidControls(), 10)
        val anchor = c.anchorWall
        k.boot += 1
        k.mono = 500L // rebooted
        k.wall = anchor - 3 * 24 * 60 * min // and the clock was set back
        assertEquals(anchor, l.trustedNow(c, k.wall))
        k.wall = anchor + 9 * 60 * min // the next morning after a normal reboot
        assertEquals(k.wall, l.trustedNow(c, k.wall))
    }

    @Test fun `a time zone change cannot reset the day when the local date goes back`() {
        val k = Clocks(at("2026-01-05T23:30:00Z"))
        var tz = ZoneId.of("Asia/Tokyo") // 08:30 on the 6th
        val l = PlayTimeLimiter({ k.wall }, { tz }, elapsed = { k.mono }, bootId = { k.boot })
        val c = tamperPlay(l, k, KidControls(), 20)
        tz = ZoneId.of("America/Los_Angeles") // flew west: the local date is now the 5th
        assertEquals(20 * min, l.playedToday(c, k.wall))
        val c2 = l.accrue(c, min, k.wall)
        assertEquals(21 * min, c2.usageMillis)
    }

    @Test fun `minutes until quiet is real time on a DST transition day`() {
        val ny = ZoneId.of("America/New_York")
        val l = PlayTimeLimiter({ 0L }, { ny }, bootId = { 0L })
        val c = KidControls(quietEnabled = true, quietStartMinute = 3 * 60 + 30, quietEndMinute = 7 * 60, dailyMinutes = 0)
        // Spring forward 2026-03-08 (02:00 -> 03:00): 00:30 local to 03:30 local is 2 real hours.
        val spring = java.time.ZonedDateTime.of(2026, 3, 8, 0, 30, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(2 * 60 * min, l.check(c, false, spring).remainingMillis)
        // Fall back 2026-11-01 (02:00 -> 01:00): 00:30 local to 03:30 local is 4 real hours.
        val fall = java.time.ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(4 * 60 * min, l.check(c, false, fall).remainingMillis)
    }

    @Test fun `quiet overrun is real elapsed time across midnight`() {
        val c = KidControls(quietEnabled = true, quietStartMinute = 23 * 60, quietEndMinute = 7 * 60, dailyMinutes = 0)
        val l = PlayTimeLimiter({ 0L }, { zone }, bootId = { 0L })
        // 00:20 is 80 minutes into a window that began at 23:00 the day before: past the 10 minute grace.
        assertTrue(l.check(c, true, at("2026-01-06T00:20:00Z")).mustStopNow)
        assertFalse(l.check(c, true, at("2026-01-05T23:05:00Z")).mustStopNow)
    }

    @Test fun `canStartNewGame is false past the limit even while a round may finish`() {
        val s = limiter.check(played(60), inRound = true, nowMillis = at(noon))
        assertFalse(s.canStartNewGame) // the shell turns this into LaunchPolicy.timeUp
        assertFalse(s.mustStopNow)
    }
}
