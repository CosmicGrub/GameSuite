package com.gamesuite.games.carrom

import kotlinx.serialization.Serializable

/** A flick: where the striker stands on the baseline, which way it goes and how hard. Plain integers so it travels and replays exactly. */
@Serializable
data class CarromShot(
    /** Placement along the shooter's baseline, 0 (left) .. 1000 (right) as that player sees the board. */
    val t: Int,
    /** Direction in hundredths of a degree to the right of straight ahead, -8000..8000. */
    val aim: Int,
    /** Flick strength 1..1000. */
    val power: Int
)

/** What a resolved shot did, as bit flags the screen words and announces. */
object CarromFlag {
    const val OWN = 1
    const val OPPONENT = 2
    const val QUEEN = 4
    const val COVERED = 8
    const val QUEEN_BACK = 16
    const val FOUL = 32
    const val LAST_COIN_BACK = 64
    const val LIMIT = 128
}

enum class CarromReject { NONE, BOARD_OVER, NOT_YOUR_TURN, BAD_SHOT, BLOCKED }

data class CarromPlayerState(val playerId: String, val displayName: String, val isBot: Boolean, val team: Int, val side: Int)

/** The shot that was just played, with the board it was played on, so every device can replay it. */
data class CarromShotRecord(val seat: Int, val shot: CarromShot, val before: List<CarromCoin>)

data class CarromState(
    val players: List<CarromPlayerState>,
    /** The coins on the board, in id order. */
    val coins: List<CarromCoin>,
    val currentPlayerIndex: Int,
    /** The team that has pocketed and covered the queen, or -1. */
    val queenOwner: Int = -1,
    /** The team that pocketed the queen and still has to cover her with one of its own coins, or -1. */
    val queenPending: Int = -1,
    val shotsTaken: Int = 0,
    val flags: Int = 0,
    val eventSeat: Int? = null,
    val pottedThisShot: List<Int> = emptyList(),
    val last: CarromShotRecord? = null,
    val shotSerial: Int = 0,
    val boardOver: Boolean = false,
    /** -1 for a draw. */
    val winnerTeam: Int = -1,
    val winPoints: Int = 0,
    /** Points per team across boards of a match to a target. */
    val teamPoints: List<Int> = listOf(0, 0),
    val targetPoints: Int = 0,
    val shotAtMillis: Long = 0L
) {
    val teamCount: Int get() = 2
    fun teamOf(seat: Int) = players[seat].team
    fun onBoard(team: Int) = coins.count { CarromBoard.colourOf(it.id) == team }
    fun pocketed(team: Int) = CarromBoard.COINS_PER_TEAM - onBoard(team)
}

class CarromResolution(val state: CarromState, val sim: CarromSimResult, val score: Double)

object CarromRules {
    const val WIN_QUEEN_POINTS = 3

    /** Seat s sits on side [sideFor] of the board: two players face each other, four sit round it anticlockwise. */
    fun sideFor(seat: Int, seats: Int): Int = if (seats == 2) (if (seat == 0) 0 else 2) else seat % 4

    fun validSeatCount(n: Int) = n in 2..4

    fun newBoard(
        players: List<CarromPlayerState>, starter: Int, teamPoints: List<Int>, target: Int, millis: Long
    ): CarromState = CarromState(
        players = players, coins = CarromBoard.openingCoins(), currentPlayerIndex = starter,
        teamPoints = teamPoints, targetPoints = target, shotAtMillis = millis
    )

    fun shotInRange(s: CarromShot) = s.t in 0..1000 && s.aim in -8000..8000 && s.power in 1..1000

    /** The striker's starting centre and velocity for [shot] by [side]. */
    fun launch(side: Int, shot: CarromShot): DoubleArray {
        val (sx, sy) = CarromBoard.strikerStart(side, shot.t)
        val (dx, dy) = CarromBoard.shotDirection(side, shot.aim)
        val v = CarromBoard.speedOf(shot.power)
        return doubleArrayOf(sx, sy, dx * v, dy * v)
    }

    /** True when the striker would overlap a coin where it stands. */
    fun standBlocked(coins: List<CarromCoin>, side: Int, t: Int): Boolean {
        val (sx, sy) = CarromBoard.strikerStart(side, t)
        val min = CarromBoard.STRIKER_R + CarromBoard.COIN_R
        return coins.any { (it.x - sx) * (it.x - sx) + (it.y - sy) * (it.y - sy) < min * min }
    }

    fun explain(s: CarromState, seat: Int, shot: CarromShot): CarromReject {
        if (s.boardOver) return CarromReject.BOARD_OVER
        if (seat != s.currentPlayerIndex) return CarromReject.NOT_YOUR_TURN
        if (!shotInRange(shot)) return CarromReject.BAD_SHOT
        if (standBlocked(s.coins, s.players[seat].side, shot.t)) return CarromReject.BLOCKED
        return CarromReject.NONE
    }

    /** The placements (0..1000 step [step]) where the striker can stand. */
    fun freeStands(s: CarromState, step: Int = 50): List<Int> {
        val side = s.players[s.currentPlayerIndex].side
        return (0..1000 step step).filter { !standBlocked(s.coins, side, it) }
    }

    /**
     * Plays [shot] for the seat on turn and returns the new state. The caller has already checked [explain].
     * [score] is a rough value of the outcome for the shooter's team, which the computer uses to compare shots.
     */
    fun play(s: CarromState, shot: CarromShot, millis: Long, record: Boolean = false): CarromResolution {
        val seat = s.currentPlayerIndex
        val me = s.players[seat]
        val team = me.team
        val l = launch(me.side, shot)
        val sim = CarromPhysics.simulate(s.coins, l[0], l[1], l[2], l[3], record)
        return resolve(s, shot, sim, millis)
    }

    fun resolve(s: CarromState, shot: CarromShot, sim: CarromSimResult, millis: Long): CarromResolution {
        val seat = s.currentPlayerIndex
        val team = s.players[seat].team
        val other = 1 - team
        var coins = sim.coins
        val potted = sim.pocketed
        val ownPotted = potted.filter { CarromBoard.colourOf(it) == team }
        val oppPotted = potted.filter { CarromBoard.colourOf(it) == other }
        val queenPotted = CarromBoard.QUEEN_ID in potted
        var flags = 0
        var queenOwner = s.queenOwner
        var queenPending = s.queenPending
        val returned = ArrayList<Int>()
        var foul = false

        if (ownPotted.isNotEmpty()) flags = flags or CarromFlag.OWN
        if (oppPotted.isNotEmpty()) flags = flags or CarromFlag.OPPONENT
        if (queenPotted) flags = flags or CarromFlag.QUEEN

        if (sim.strikerPocketed) {
            foul = true
            flags = flags or CarromFlag.FOUL
            // coins of the shooter's own colour and the queen go back, plus one earlier coin as the penalty
            returned += ownPotted
            if (queenPotted) returned += CarromBoard.QUEEN_ID
            val earlier = CarromBoard.idsOf(team).filter { id -> coins.none { it.id == id } && id !in ownPotted }
            earlier.firstOrNull()?.let { returned += it }
            if (queenPending == team) { returned += CarromBoard.QUEEN_ID; queenPending = -1; flags = flags or CarromFlag.QUEEN_BACK }
        } else {
            val hadPending = queenPending == team
            if (queenPotted) queenPending = team
            if (queenPending == team) {
                if (ownPotted.isNotEmpty()) {
                    queenOwner = team; queenPending = -1; flags = flags or CarromFlag.COVERED
                } else if (hadPending) {
                    returned += CarromBoard.QUEEN_ID; queenPending = -1; flags = flags or CarromFlag.QUEEN_BACK
                }
            }
            // the last coin of a colour cannot go in before the queen is won
            for (t in 0..1) {
                val all = CarromBoard.idsOf(t).all { id -> coins.none { it.id == id } && id !in returned }
                if (all && queenOwner == -1) {
                    val lastId = potted.lastOrNull { CarromBoard.colourOf(it) == t } ?: CarromBoard.idsOf(t).last()
                    returned += lastId
                    flags = flags or CarromFlag.LAST_COIN_BACK
                }
            }
        }

        if (returned.isNotEmpty()) {
            val placed = ArrayList(coins)
            for (id in returned.distinct()) {
                val (px, py) = CarromBoard.freeSpot(placed)
                placed += CarromCoin(id, px, py)
            }
            coins = placed.sortedBy { it.id }
        }
        val pottedKept = potted.filter { id -> id !in returned }

        var next = seat
        var boardOver = false
        var winner = -1
        var points = 0
        val shots = s.shotsTaken + 1

        // a team wins when all its coins are in and the queen is settled
        fun cleared(t: Int) = CarromBoard.idsOf(t).none { id -> coins.any { it.id == id } } && queenOwner != -1
        val winTeam = when {
            cleared(team) -> team
            cleared(other) -> other
            else -> -1
        }
        if (winTeam != -1) {
            boardOver = true
            winner = winTeam
            val left = coins.count { CarromBoard.colourOf(it.id) == 1 - winTeam }
            points = maxOf(1, left + if (queenOwner == winTeam) WIN_QUEEN_POINTS else 0)
        } else if (shots >= CarromBoard.MAX_SHOTS) {
            boardOver = true
            flags = flags or CarromFlag.LIMIT
            val a = CarromBoard.COINS_PER_TEAM - coins.count { CarromBoard.colourOf(it.id) == 0 }
            val b = CarromBoard.COINS_PER_TEAM - coins.count { CarromBoard.colourOf(it.id) == 1 }
            winner = if (a > b) 0 else if (b > a) 1 else -1
            points = kotlin.math.abs(a - b)
        }

        if (!boardOver) {
            val keepTurn = !foul && (ownPotted.isNotEmpty() || queenPending == team)
            if (!keepTurn) next = (seat + 1) % s.players.size
        }

        val teamPoints = if (boardOver && winner != -1) s.teamPoints.mapIndexed { i, p -> if (i == winner) p + points else p } else s.teamPoints
        val ns = s.copy(
            coins = coins, currentPlayerIndex = next, queenOwner = queenOwner, queenPending = queenPending,
            shotsTaken = shots, flags = flags, eventSeat = seat, pottedThisShot = pottedKept,
            last = CarromShotRecord(seat, shot, s.coins), shotSerial = s.shotSerial + 1,
            boardOver = boardOver, winnerTeam = winner, winPoints = points, teamPoints = teamPoints, shotAtMillis = millis
        )

        // value for the shooter's team, used to rank candidate shots
        var score = 0.0
        score += 12.0 * ownPotted.count { it !in returned }
        score -= 9.0 * oppPotted.count { it !in returned }
        if (foul) score -= 30.0
        if (flags and CarromFlag.COVERED != 0) score += 20.0
        else if (queenPending == team) score += 6.0
        if (flags and CarromFlag.QUEEN_BACK != 0) score -= 6.0
        if (flags and CarromFlag.LAST_COIN_BACK != 0) score -= 8.0
        if (boardOver) score += if (winner == team) 200.0 + points else if (winner == -1) 0.0 else -200.0 - points
        if (!boardOver && next == seat) score += 3.0
        return CarromResolution(ns, sim, score)
    }
}
