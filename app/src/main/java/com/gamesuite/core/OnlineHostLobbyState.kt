package com.gamesuite.core

import com.gamesuite.transport.LobbySeatLimits
import com.gamesuite.transport.OnlinePlayer
import com.gamesuite.transport.OnlineSeating
import com.gamesuite.transport.SeatKind
import com.gamesuite.transport.SeatView
import com.gamesuite.transport.cpuPlayerId
import com.gamesuite.transport.seatOnlineGuests

/**
 * Everything the Online host lobby decides, as plain immutable data so it is JVM-testable and the screen only
 * renders it: who is seated, how many computer seats the host wants, who is ready, who was removed, and when Start
 * may be pressed.
 *
 *  - **Humans first.** [cpuWanted] is what the host asked for; the seats actually given to computers are
 *    [cpus] = what still fits beside the seated humans, so a friend who joins a table full of computers simply takes
 *    a computer's seat.
 *  - **Ready check.** Start waits for every seated guest to be ready. If somebody never readies (an older build that
 *    has no Ready button, or a friend who wandered off), Start unlocks anyway [START_ANYWAY_AFTER_MS] after the
 *    table last changed ([observe]).
 *  - **Kick.** A removed guest is dropped from the table and stays out ([kicked]) for the life of this lobby; the
 *    screen tells them with a host-stamped refusal.
 */
data class OnlineHostLobbyState(
    val limits: LobbySeatLimits,
    /** Computer seats the host asked for (see [cpus] for what is actually given). */
    val cpuWanted: Int = 0,
    val ready: Set<String> = emptySet(),
    val kicked: Set<String> = emptySet(),
    /** When the set of seated guests last changed (millis); the Start-anyway timer runs from here. */
    val changedAtMs: Long = 0L,
    private val lastSeatedIds: Set<String> = emptySet(),
) {
    /** The relay's connected guests minus anyone this host removed, seated in join order up to the game's seat count. */
    fun seating(connected: List<OnlinePlayer>): OnlineSeating =
        seatOnlineGuests(connected.filterNot { it.playerId in kicked }, limits)

    /** Computer seats actually filled beside [seatedGuests] human guests. */
    fun cpus(seatedGuests: Int): Int = cpuWanted.coerceIn(0, limits.maxCpus(seatedGuests))

    fun withCpuAdded(seatedGuests: Int): OnlineHostLobbyState =
        copy(cpuWanted = (cpus(seatedGuests) + 1).coerceAtMost(limits.maxCpus(seatedGuests)))

    fun withCpuRemoved(seatedGuests: Int): OnlineHostLobbyState =
        copy(cpuWanted = (cpus(seatedGuests) - 1).coerceAtLeast(0))

    /** "Fill with computers": every empty seat. */
    fun withAllFilled(seatedGuests: Int): OnlineHostLobbyState = copy(cpuWanted = limits.maxCpus(seatedGuests))

    fun withNoCpus(): OnlineHostLobbyState = copy(cpuWanted = 0)

    /** A [playerId]'s Ready, believed only when it belongs to a currently [seated] guest. */
    fun withReady(playerId: String, isReady: Boolean, seated: List<OnlinePlayer>): OnlineHostLobbyState {
        if (seated.none { it.playerId == playerId }) return this
        return copy(ready = if (isReady) ready + playerId else ready - playerId)
    }

    fun withKicked(playerId: String): OnlineHostLobbyState =
        copy(kicked = kicked + playerId, ready = ready - playerId)

    /**
     * Notes the table as of [nowMs]: when the seated guests changed, the Start-anyway timer restarts and ready marks of
     * anyone no longer seated are dropped. Cheap and idempotent; call it whenever the connected list changes.
     */
    fun observe(seated: List<OnlinePlayer>, nowMs: Long): OnlineHostLobbyState {
        val ids = seated.mapTo(HashSet()) { it.playerId }
        if (ids == lastSeatedIds) return this
        return copy(ready = ready.intersect(ids), changedAtMs = nowMs, lastSeatedIds = ids)
    }

    fun canStart(seatedGuests: Int): Boolean = limits.canStart(seatedGuests, cpus(seatedGuests))

    fun allReady(seated: List<OnlinePlayer>): Boolean = seated.all { it.playerId in ready }

    /** Start is allowed by the seat count AND (everyone is ready OR the Start-anyway delay has passed). */
    fun canStartNow(seated: List<OnlinePlayer>, nowMs: Long): Boolean =
        canStart(seated.size) && (allReady(seated) || startAnywayAvailable(nowMs))

    fun startAnywayAvailable(nowMs: Long): Boolean = nowMs - changedAtMs >= START_ANYWAY_AFTER_MS

    /** How many seated guests have not readied yet. */
    fun notReadyCount(seated: List<OnlinePlayer>): Int = seated.count { it.playerId !in ready }

    /**
     * The table as the grid shows it and the host broadcasts it: the host, the seated guests, [cpus] computers (named
     * by [cpuName]), then empty seats, exactly [LobbySeatLimits.maxPlayers] in all. The host is always ready.
     */
    fun seatViews(hostId: String, hostName: String, seated: List<OnlinePlayer>, cpuName: (Int) -> String): List<SeatView> =
        buildSeatViews(
            limits, hostId, hostName,
            guests = seated.map { it.playerId to it.displayName },
            isReady = { it in ready },
            cpuCount = cpus(seated.size),
            cpuName = cpuName,
        )

    companion object {
        /** After this long without the table changing, a host may Start though some guests are not ready. */
        const val START_ANYWAY_AFTER_MS = 30_000L
    }
}

/**
 * The lobby table both the Online and the Nearby host lobby show: the host, the human [guests] (id to name), [cpuCount]
 * computers named by [cpuName], then empty seats, exactly [LobbySeatLimits.maxPlayers] in all. The host and computers
 * are always ready; [isReady] says whether a guest is (Nearby has no ready check and passes `{ true }`).
 */
fun buildSeatViews(
    limits: LobbySeatLimits,
    hostId: String,
    hostName: String,
    guests: List<Pair<String, String>>,
    isReady: (String) -> Boolean,
    cpuCount: Int,
    cpuName: (Int) -> String,
): List<SeatView> {
    val views = ArrayList<SeatView>(limits.maxPlayers)
    views += SeatView(SeatKind.HOST, hostId, hostName, ready = true)
    guests.forEach { (id, name) -> views += SeatView(SeatKind.HUMAN, id, name, ready = isReady(id)) }
    for (n in 1..cpuCount.coerceAtLeast(0)) views += SeatView(SeatKind.CPU, cpuPlayerId(n), cpuName(n), ready = true)
    while (views.size < limits.maxPlayers) views += SeatView(SeatKind.EMPTY)
    return views
}
