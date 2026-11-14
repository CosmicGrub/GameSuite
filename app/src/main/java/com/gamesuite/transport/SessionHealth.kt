package com.gamesuite.transport

import kotlinx.coroutines.flow.StateFlow

/** Why a networked match can no longer continue, as seen by this device. */
enum class SessionEnd {
    /** The room's host seat was vacated for good (left, or its reconnect grace window ran out). */
    HOST_LEFT,

    /** This device lost its own connection and every reconnect attempt failed. */
    CONNECTION_LOST,
}

/**
 * What a transport can tell the shell about the health of the match it carries, independent of the game
 * on top: a game registers its own single `onPlayerLeft` listener (a setter, so the shell cannot add a
 * second), so these are plain observable state the transport keeps itself. Implemented by
 * [OnlineTransport]; transports with nothing to report (pass-and-play, Nearby for now) simply do not
 * implement it and the game chrome shows nothing.
 */
interface SessionHealthSource {
    /** Non-null once the match is over for good (see [SessionEnd]); never reset to null for this transport. */
    val sessionEnd: StateFlow<SessionEnd?>

    /** Seats the relay reports as temporarily disconnected (their seat is held open for a grace period). */
    val awayPlayers: StateFlow<Set<String>>

    /** True while THIS device is retrying its own dropped connection. */
    val isReconnecting: StateFlow<Boolean>

    /** The room host's seat id, or null while unknown. */
    val sessionHostId: String?
}

/** What the game chrome shows for the current health of a networked match; see [sessionNoticeFor]. */
enum class SessionNotice {
    NONE,

    /** This device dropped and is retrying: the match is paused for us only. */
    RECONNECTING,

    /** The host dropped and may come back within the grace window. */
    HOST_AWAY,

    /** Another player dropped and may come back. */
    PLAYER_AWAY,

    /** Final: the host left. The chrome aborts the match (no result recorded). */
    HOST_LEFT,

    /** Final: we could not get our own connection back. The chrome aborts the match. */
    CONNECTION_LOST;

    val isFinal: Boolean get() = this == HOST_LEFT || this == CONNECTION_LOST
}

/**
 * The single place the notice is decided, so the banner/dialog and the tests agree: an ended session beats
 * everything; our own reconnect beats someone else's absence (we may simply not know yet); the host being
 * away beats another player being away; spectators and the host itself never report themselves.
 */
fun sessionNoticeFor(
    sessionEnd: SessionEnd?,
    isReconnecting: Boolean,
    awayPlayers: Set<String>,
    hostPlayerId: String?,
    localPlayerId: String?,
): SessionNotice = when {
    sessionEnd == SessionEnd.HOST_LEFT -> SessionNotice.HOST_LEFT
    sessionEnd == SessionEnd.CONNECTION_LOST -> SessionNotice.CONNECTION_LOST
    isReconnecting -> SessionNotice.RECONNECTING
    hostPlayerId != null && hostPlayerId in awayPlayers && hostPlayerId != localPlayerId -> SessionNotice.HOST_AWAY
    awayPlayers.any { it != localPlayerId } -> SessionNotice.PLAYER_AWAY
    else -> SessionNotice.NONE
}
