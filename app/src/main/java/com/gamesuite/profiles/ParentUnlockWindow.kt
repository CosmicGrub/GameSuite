package com.gamesuite.profiles

/**
 * P13: how long a passed parent gate stays open. The window closes (a) when the app goes to the background
 * (ON_STOP) and (b) after [idleMillis] without any touch inside the parent area, so a parent who hands the device back
 * with the dashboard still open does not leave the kid in the parent area. Pure: every method takes the clock reading
 * (any monotonic millisecond clock) so it can be tested without waiting.
 */
class ParentUnlockWindow(val idleMillis: Long = DEFAULT_IDLE_MILLIS) {
    private var open = false
    private var lastActivity = 0L

    /** The gate was just passed. */
    fun unlock(now: Long) { open = true; lastActivity = now }

    /** A touch in the parent area: keeps the window open. Ignored while closed (a touch can never re-open it). */
    fun touch(now: Long) { if (open) lastActivity = now }

    /** App went to the background, a profile was switched, or the kid is back on Kids home. */
    fun close() { open = false }

    /** True while the window is open and not idle at [now]. Closes itself as a side effect once idle. */
    fun isOpen(now: Long): Boolean {
        if (open && now - lastActivity >= idleMillis) open = false
        return open
    }

    companion object {
        const val DEFAULT_IDLE_MILLIS: Long = 2 * 60 * 1000L
    }
}
