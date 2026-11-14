package com.gamesuite.core

/**
 * Process-wide switches consulted by [GameSessionManager.launchGame] and the Hub launcher.
 *
 *  - [networkAllowed]: when false (KID profile, an adult who turned "Online play" off, OR the profile / parent
 *    controls have not been read yet) no Nearby / Online match can be launched, whatever route led there.
 *    FAIL-CLOSED: the default is false; the profile bootstrap (or the shell observer once access resolved)
 *    switches it on only for an adult with Online play enabled.
 *  - [timeUp]: set by the shell for a KID profile whose daily limit or quiet hours have begun; no NEW game can
 *    be started (Hub Play, Continue, Resume, a lobby launch). A round already in progress is untouched.
 */
object LaunchPolicy {
    @Volatile var networkAllowed: Boolean = false

    @Volatile var timeUp: Boolean = false

    fun allows(mode: PlayMode): Boolean =
        networkAllowed || (mode != PlayMode.LOCAL_AD_HOC && mode != PlayMode.ONLINE)

    /** True when a new game may be started now (the kid's time is not up). */
    fun allowsNewGame(): Boolean = !timeUp

    private val _blockedStarts = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Counts refused in-place restarts ("Play again" / result-panel buttons); the shell reacts to a change by ending the round. */
    val blockedStarts: kotlinx.coroutines.flow.StateFlow<Int> = _blockedStarts

    /**
     * The one check an in-place restart ("Play again", "Next round", a result-panel button that begins a NEW round
     * without going through [GameSessionManager.launchGame]) must make first. False when a kid's time is up: the
     * caller does nothing, and [blockedStarts] ticks so the shell can show the time-up screen at once instead of
     * waiting out the grace period.
     */
    fun requestNewRound(): Boolean {
        if (allowsNewGame()) return true
        _blockedStarts.value = _blockedStarts.value + 1
        return false
    }

    /** Whether launching [mode] as a new game is allowed right now. */
    fun allowsLaunch(mode: PlayMode): Boolean = allows(mode) && allowsNewGame()

    /** Back to the fail-closed defaults (used by tests and by a profile switch before access resolves). */
    fun resetToFailClosed() {
        networkAllowed = false
        timeUp = false
    }
}
