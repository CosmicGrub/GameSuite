package com.gamesuite.transport

import com.gamesuite.core.PlayerInfo

/**
 * Where a guest sits in the roster a host announced in its `GameStart`, or -1 if the roster
 * can't be trusted to seat it.
 *
 * -1 is returned when [myPlayerId] is absent, or when it is seat 0: the host is seat 0 and a
 * guest is never it, and `localPlayerIndex == 0` is what makes a device run the game engine as
 * the host, so a roster that says "you are the host" must never be believed. It is also returned
 * when the roster repeats an id, since two seats with one id can't be told apart by the
 * `fromPlayerId` stamps the engines check, and when any id is blank (a roster is wire input; a blank id
 * is never something an honest host produces, and must not reach the transport's mapping validation).
 *
 * Shared by the Nearby and Online guest lobbies.
 */
fun guestSeatIndex(players: List<PlayerInfo>, myPlayerId: String): Int {
    if (players.any { it.playerId.isBlank() }) return -1
    if (players.map { it.playerId }.toSet().size != players.size) return -1
    val index = players.indexOfFirst { it.playerId == myPlayerId }
    return if (index > 0) index else -1
}
