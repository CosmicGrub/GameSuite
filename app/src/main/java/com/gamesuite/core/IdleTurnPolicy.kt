package com.gamesuite.core

/**
 * Idle-turn limit for networked tables (review item 7 / UNBUILT-ITEMS A): a human seat that does not act
 * within [TURN_LIMIT_MS] is moved on by the host so a stalled or backgrounded phone cannot block a table
 * forever. Pure and clock-free; the host UI supplies the delay.
 */
object IdleTurnPolicy {
    const val TURN_LIMIT_MS = 60_000L

    /** True once [limitMs] has passed since [turnStartedAt]. A clock that went backwards never expires. */
    fun expired(turnStartedAt: Long, now: Long, limitMs: Long = TURN_LIMIT_MS): Boolean =
        now >= turnStartedAt && now - turnStartedAt >= limitMs
}
