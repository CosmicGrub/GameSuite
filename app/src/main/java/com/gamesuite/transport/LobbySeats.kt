package com.gamesuite.transport

import com.gamesuite.core.HubModeKind
import com.gamesuite.core.PlayerInfo

/** Seat ids of computer players start with this; no guest may hold such an id (see [seatOnlineGuests], [guestSeatIndex]). */
const val CPU_PLAYER_ID_PREFIX = "cpu-"

fun isCpuPlayerId(playerId: String): Boolean = playerId.startsWith(CPU_PLAYER_ID_PREFIX, ignoreCase = true)

/** The id of the [n]th computer seat (1-based): "cpu-1", "cpu-2". */
fun cpuPlayerId(n: Int): String = "$CPU_PLAYER_ID_PREFIX$n"

/** [count] computer seats as announced in a GameStart roster, named by [nameFor] (a localized "Computer N"). */
fun cpuSeatsFor(count: Int, nameFor: (Int) -> String): List<PlayerInfo> =
    (1..count).map { PlayerInfo(playerId = cpuPlayerId(it), displayName = nameFor(it), isBot = true) }

/**
 * Which networked games can have computer seats filled in by the lobby host. The list is the result of the
 * wave-5 S4 audit (`NetworkedSeatAuditTest`, recorded in docs/roadmap/WAVE-5-MULTIPLAYER-DESIGN.md): for each of
 * these the host's engine drives the computer seats in ONLINE/NEARBY mode and the guests follow its state. A game
 * not listed (Six-Ball Puzzle races on every device with no computer seat, Dominoes' networked table has none) shows
 * no fill controls. A two-seat game never needs it either: host plus one guest is already full.
 */
object OnlineCpuFill {
    val supportedGames: Set<String> = setOf(
        "uno", "texas-holdem", "president", "sevens", "chinese-checkers", "pig-dice", "ludo", "blackjack", "war",
        "carrom", "curling", "darts", "bowling", "mini-golf", "tank-battle", "shooting-gallery", "matching",
        "toy-soccer", "slot-cars", "fishing",
    )

    /** Whether [gameId]'s lobby offers computer seats in [kind] (NEARBY or ONLINE) mode. */
    fun isSupported(gameId: String, kind: HubModeKind = HubModeKind.ONLINE): Boolean =
        gameId in supportedGames && LobbySeatLimits.forGame(gameId, kind).maxPlayers > 2
}
