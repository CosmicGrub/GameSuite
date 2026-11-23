package com.gamesuite.profiles

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

enum class LimitState { OK, WARN, TIME_UP, QUIET_HOURS }

/**
 * What the shell should do right now.
 * [canStartNewGame] is false once the limit or a quiet window has begun. [mustStopNow] is true when the kid
 * is not in the middle of a round (show the time-up screen immediately), or has overrun the grace period.
 * A round in progress is never cut: [mustStopNow] stays false until it ends, up to [PlayTimeLimiter.GRACE_MILLIS].
 */
data class LimitStatus(
    val state: LimitState,
    /** Millis of play left today (min of daily budget and time to the next quiet window); null = unlimited. */
    val remainingMillis: Long?,
    val canStartNewGame: Boolean,
    val mustStopNow: Boolean
)

/**
 * Pure play-time rules with injectable clocks and zone: a daily budget per kid, an optional quiet-hours
 * window (may cross midnight), a warning at [WARN_MILLIS] left, reset at local midnight, and accumulation of
 * foreground time only (the caller adds time only while the app is resumed). No Android, no persistence.
 *
 * CLOCK TAMPERING. "Today" and the quiet window are judged on a TRUSTED time, not the raw wall clock:
 * every accrual stores an anchor (trusted wall time + monotonic reading + boot counter, see [KidControls.anchorWall]).
 * While the boot counter is unchanged the trusted time is `anchorWall + (monotonic now - anchorElapsed)`; the raw
 * wall clock is adopted only when it agrees with that within [CLOCK_TOLERANCE_MILLIS] (normal NTP drift). A wall
 * clock moved forward (fresh budget, skipped quiet hours) or backward is therefore ignored. After a reboot the
 * monotonic reference is gone: the wall clock is used but never earlier than the anchor. In addition the stored
 * day ([KidControls.usageEpochDay]) is a high-water mark: usage is only ever rolled FORWARD, so a clock set back
 * (or a flight west) neither resets nor deletes a day's usage. A wall clock that is genuinely corrected by more than
 * the tolerance without a reboot stays ignored until the next reboot (documented trade-off).
 *
 * DST / TIME ZONES. Days and quiet windows are computed in the injected zone with `java.time`, so a DST transition
 * day (23 or 25 hours) is handled by the zone rules: "minutes until quiet" is the real elapsed time to the next
 * start instant, not a wall-minute subtraction. A zone change moves the local date, but the high-water mark makes
 * a move to an earlier date a no-op for usage.
 */
class PlayTimeLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val elapsed: () -> Long = ::monotonicMillis,
    private val bootId: () -> Long = { 0L }
) {
    fun epochDay(nowMillis: Long = clock()): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone()).toLocalDate().toEpochDay()

    fun minuteOfDay(nowMillis: Long = clock()): Int {
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone()).toLocalTime()
        return t.hour * 60 + t.minute
    }

    /**
     * The time to judge "today" and quiet hours by: see the class comment. With no anchor yet (never accrued)
     * it is [wallNow] itself.
     */
    fun trustedNow(c: KidControls, wallNow: Long = clock()): Long {
        if (c.anchorWall <= 0L) return wallNow
        val e = elapsed()
        if (c.anchorBoot != bootId() || e < c.anchorElapsed) return maxOf(wallNow, c.anchorWall)
        val extrapolated = c.anchorWall + (e - c.anchorElapsed)
        return if (abs(wallNow - extrapolated) <= CLOCK_TOLERANCE_MILLIS) wallNow else extrapolated
    }

    /** [c] with usage rolled FORWARD to today (a new local day starts at zero; yesterday stays in the history). Never rolls back. */
    fun rollover(c: KidControls, nowMillis: Long = clock()): KidControls = rolloverAt(c, trustedNow(c, nowMillis))

    private fun rolloverAt(c: KidControls, trusted: Long): KidControls {
        val today = epochDay(trusted)
        if (c.usageEpochDay >= today) return c // same day, or the clock is BEHIND the high-water mark: keep everything
        val hist = if (c.usageEpochDay >= 0 && c.usageMillis > 0) c.dailyHistory + (c.usageEpochDay to c.usageMillis) else c.dailyHistory
        return c.copy(usageEpochDay = today, usageMillis = 0L, dailyHistory = prune(hist, today))
    }

    /** Adds [deltaMillis] of FOREGROUND play (clamped to [0, MAX_TICK_MILLIS] so a long pause never over-counts) and refreshes the time anchor. */
    fun accrue(c: KidControls, deltaMillis: Long, nowMillis: Long = clock()): KidControls {
        val trusted = trustedNow(c, nowMillis)
        val rolled = rolloverAt(c, trusted)
        val add = deltaMillis.coerceIn(0L, MAX_TICK_MILLIS)
        return rolled.copy(
            usageMillis = rolled.usageMillis + add,
            anchorWall = trusted,
            anchorElapsed = elapsed(),
            anchorBoot = bootId()
        )
    }

    /** Today's played millis (the stored day's usage unless the stored day is in the past). */
    fun playedToday(c: KidControls, nowMillis: Long = clock()): Long =
        if (c.usageEpochDay >= epochDay(trustedNow(c, nowMillis))) c.usageMillis else 0L

    /** Millis played per day for the last 7 local days, oldest first, ending today. */
    fun weekMillis(c: KidControls, nowMillis: Long = clock()): List<Long> {
        val today = maxOf(epochDay(trustedNow(c, nowMillis)), c.usageEpochDay)
        return (today - 6..today).map { d ->
            if (d == c.usageEpochDay) c.usageMillis else c.dailyHistory[d] ?: 0L
        }
    }

    fun isQuietNow(c: KidControls, nowMillis: Long = clock()): Boolean = quietAt(c, trustedNow(c, nowMillis))

    private fun quietAt(c: KidControls, t: Long): Boolean {
        if (!c.quietEnabled) return false
        val m = minuteOfDay(t)
        val s = c.quietStartMinute
        val e = c.quietEndMinute
        return when {
            s == e -> false
            s < e -> m in s until e
            else -> m >= s || m < e // crosses midnight
        }
    }

    /** The instant of the quiet start on the local date of [base] plus [plusDays], computed in the zone (DST-correct). */
    private fun startInstantOnDay(base: ZonedDateTime, plusDays: Long, startMinute: Int): Long =
        base.toLocalDate().plusDays(plusDays).atTime(startMinute / 60, startMinute % 60).atZone(zone()).toInstant().toEpochMilli()

    /** Real millis from [t] to the next quiet-window start (null when quiet hours are off or we are inside it). */
    private fun millisUntilQuiet(c: KidControls, t: Long): Long? {
        if (!c.quietEnabled || c.quietStartMinute == c.quietEndMinute || quietAt(c, t)) return null
        val now = Instant.ofEpochMilli(t).atZone(zone())
        var next = startInstantOnDay(now, 0, c.quietStartMinute)
        if (next <= t) next = startInstantOnDay(now, 1, c.quietStartMinute)
        return next - t
    }

    /** How long the current quiet window has been running (0 when not quiet). */
    private fun quietOverrunMillis(c: KidControls, t: Long): Long {
        if (!quietAt(c, t)) return 0L
        val now = Instant.ofEpochMilli(t).atZone(zone())
        var start = startInstantOnDay(now, 0, c.quietStartMinute)
        if (start > t) start = startInstantOnDay(now, -1, c.quietStartMinute)
        return (t - start).coerceAtLeast(0L)
    }

    /**
     * The decision for a kid with [c] now. [inRound] = a game is on screen (a round is being played): the
     * round may finish; the time-up screen then waits for it unless it overruns [GRACE_MILLIS].
     */
    fun check(c: KidControls, inRound: Boolean, nowMillis: Long = clock()): LimitStatus {
        val t = trustedNow(c, nowMillis)
        val used = if (c.usageEpochDay >= epochDay(t)) c.usageMillis else 0L
        val dailyLeft: Long? = if (c.dailyMinutes <= 0) null else c.dailyMinutes * 60_000L - used
        val untilQuiet = millisUntilQuiet(c, t)
        val quietNow = quietAt(c, t)

        val remaining: Long? = when {
            quietNow -> 0L
            dailyLeft != null && untilQuiet != null -> minOf(dailyLeft, untilQuiet)
            dailyLeft != null -> dailyLeft
            else -> untilQuiet
        }
        val over = remaining != null && remaining <= 0L
        if (!over) {
            val warn = remaining != null && remaining <= WARN_MILLIS
            return LimitStatus(if (warn) LimitState.WARN else LimitState.OK, remaining, true, false)
        }
        val overrun = when {
            quietNow -> quietOverrunMillis(c, t)
            else -> (-(dailyLeft ?: 0L)).coerceAtLeast(0L)
        }
        val stopNow = !inRound || overrun >= GRACE_MILLIS
        return LimitStatus(if (quietNow) LimitState.QUIET_HOURS else LimitState.TIME_UP, 0L, false, stopNow)
    }

    private fun prune(hist: Map<Long, Long>, today: Long): Map<Long, Long> =
        hist.filterKeys { it > today - KidControls.HISTORY_DAYS && it < today }

    companion object {
        const val WARN_MILLIS = 5 * 60_000L
        /** How long past the limit a round may keep going before the shell stops it anyway. */
        const val GRACE_MILLIS = 10 * 60_000L
        /** One foreground tick may add at most this much. */
        const val MAX_TICK_MILLIS = 60_000L
        /** The raw wall clock is trusted while it is within this of the monotonic extrapolation (NTP drift). */
        const val CLOCK_TOLERANCE_MILLIS = 5 * 60_000L
    }
}
