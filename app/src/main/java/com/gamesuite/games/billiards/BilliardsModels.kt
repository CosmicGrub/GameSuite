package com.gamesuite.games.billiards

import kotlinx.serialization.Serializable

object BilliardsPhase {
    const val AIM = "aim"
    const val OVER = "over"
    val ALL = setOf(AIM, OVER)
}

enum class ShotReject { NONE, OVER, NOT_YOUR_TURN, BAD_POWER, BAD_PLACEMENT, BAD_SHOT }

@Serializable
data class BilliardsSeat(val playerId: String, val displayName: String, val isBot: Boolean, val wins: Int = 0)

/**
 * The last stroke, kept in the state so every device can replay it: [startX]/[startY]/[startOn] are the
 * positions before the shot, [shot] the stroke. Replaying it with [BilliardsPhysics.simulate] gives the
 * very same end positions as the state that follows (the physics is deterministic).
 */
@Serializable
data class ShotRecord(
    val shooter: Int,
    val shot: Shot,
    val startX: List<Double>,
    val startY: List<Double>,
    val startOn: List<Boolean>,
    val pocketed: List<Int>,
    val firstContact: Int,
    val fouls: List<String>
)

/**
 * The whole table. Nothing in billiards is hidden, so this is also what the host sends to guests.
 * [x]/[y]/[onTable] have one entry per ball id 0..15 (the cue ball first); balls a variant does not use
 * simply are not on the table.
 */
@Serializable
data class BilliardsState(
    val variant: String,
    val seats: List<BilliardsSeat>,
    val x: List<Double>,
    val y: List<Double>,
    val onTable: List<Boolean>,
    /** The seat to shoot next. */
    val shooter: Int,
    /** Per seat: 0 open, 1 solids, 2 stripes. */
    val groups: List<Int>,
    val ballInHand: Boolean,
    val behindHead: Boolean,
    val isBreak: Boolean,
    val phase: String,
    val winner: Int,
    val winReason: String,
    /** Consecutive fouls per seat (the 9-ball three-foul rule). */
    val foulStreak: List<Int>,
    /** Fouls of the last shot, for the status line. */
    val lastFouls: List<String>,
    val lastShot: ShotRecord?,
    val shotNo: Int,
    val serial: Int
) {
    fun ballsOnTable(): Set<Int> = (1 until BilliardsTable.BALLS).filter { onTable[it] }.toSet()
    fun xs() = x.toDoubleArray()
    fun ys() = y.toDoubleArray()
    fun ons() = onTable.toBooleanArray()
}

/** Everything [BilliardsState.isValid] checks, so a snapshot or a message from outside cannot put the engine in an impossible state. */
fun BilliardsState.isValid(roster: List<com.gamesuite.core.PlayerInfo>? = null): Boolean {
    val n = BilliardsTable.BALLS
    if (variant !in BilliardsVariant.ALL) return false
    if (seats.size != 2 || x.size != n || y.size != n || onTable.size != n) return false
    if (roster != null && (roster.size != 2 || seats.indices.any { seats[it].playerId != roster[it].playerId })) return false
    if (shooter !in 0..1 || groups.size != 2 || groups.any { it !in 0..2 } || foulStreak.size != 2 || foulStreak.any { it < 0 || it > 3 }) return false
    if (phase !in BilliardsPhase.ALL || winReason !in WinReason.ALL || winner !in -1..1 || shotNo < 0 || serial < 0) return false
    if (seats.any { it.wins < 0 || it.wins > 10_000 }) return false
    if (lastFouls.any { it !in FoulKind.ALL }) return false
    if ((phase == BilliardsPhase.OVER) != (winner >= 0)) return false
    if (variant == BilliardsVariant.NINE && groups.any { it != 0 }) return false
    val maxId = BilliardsVariant.ballCount(variant)
    for (i in 0 until n) {
        if (!x[i].isFinite() || !y[i].isFinite()) return false
        if (i > maxId && onTable[i]) return false
        if (onTable[i]) {
            val r = BilliardsTable.R - 1e-6
            if (x[i] < r || x[i] > BilliardsTable.W - r || y[i] < r || y[i] > BilliardsTable.H - r) return false
        }
    }
    if (!onTable[0]) return false
    val ls = lastShot
    if (ls != null) {
        if (ls.shooter !in 0..1 || !ls.shot.isFinite || ls.startX.size != n || ls.startY.size != n || ls.startOn.size != n) return false
        if (ls.startX.any { !it.isFinite() } || ls.startY.any { !it.isFinite() }) return false
        if (ls.pocketed.any { it !in 0 until n } || ls.firstContact !in -1 until n || ls.fouls.any { it !in FoulKind.ALL }) return false
    }
    return true
}
