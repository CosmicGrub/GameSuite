package com.gamesuite.games.carrom

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The computer's shooting. It lines up a "ghost striker" behind each of its coins for each pocket, works out how hard
 * to flick, and then by level:
 *  - EASY picks one of the better-looking geometric shots and misses its aim and power by a visible margin; it never simulates.
 *  - MEDIUM simulates its best ten geometric candidates (with a little wobble) and plays the best result.
 *  - HARD simulates up to a hundred and fifty candidates, including break shots, and plays the best outcome it finds.
 * [search] returns the whole ranked list so tests and hints can look at it. Everything uses the injected [Random].
 */
object CarromBot {
    private class Cand(val shot: CarromShot, val rough: Double)

    fun choose(state: CarromState, level: CpuDifficulty, random: Random): CarromShot {
        val seat = state.currentPlayerIndex
        val cands = candidates(state, seat)
        if (cands.isEmpty()) return fallback(state, random)
        return when (level) {
            CpuDifficulty.EASY -> {
                val pick = cands.take(4).random(random)
                wobble(pick.shot, random, aimDeg = 4.5, powerFrac = 0.18)
            }
            CpuDifficulty.MEDIUM -> best(state, cands.take(6).map { wobble(it.shot, random, 1.2, 0.08) }, random)
            CpuDifficulty.HARD -> best(state, cands.take(150).map { it.shot }, random)
        }
    }

    /** Hard-level search: the best shot found and its value. */
    fun search(state: CarromState, random: Random, limit: Int = 150): Pair<CarromShot, Double>? {
        val cands = candidates(state, state.currentPlayerIndex).take(limit).map { it.shot }
        if (cands.isEmpty()) return null
        var bestShot = cands[0]
        var bestScore = -1e18
        for (c in cands) {
            val sc = CarromRules.play(state, c, 0L).score + random.nextDouble() * 0.01
            if (sc > bestScore) { bestScore = sc; bestShot = c }
        }
        return bestShot to bestScore
    }

    private fun best(state: CarromState, shots: List<CarromShot>, random: Random): CarromShot {
        var bestShot = shots[0]
        var bestScore = -1e18
        for (c in shots) {
            if (CarromRules.explain(state, state.currentPlayerIndex, c) != CarromReject.NONE) continue
            val sc = CarromRules.play(state, c, 0L).score + random.nextDouble() * 0.01
            if (sc > bestScore) { bestScore = sc; bestShot = c }
        }
        return bestShot
    }

    private fun wobble(s: CarromShot, random: Random, aimDeg: Double, powerFrac: Double): CarromShot = CarromShot(
        s.t,
        (s.aim + ((random.nextDouble() * 2 - 1) * aimDeg * 100).toInt()).coerceIn(-8000, 8000),
        (s.power * (1 + (random.nextDouble() * 2 - 1) * powerFrac)).toInt().coerceIn(1, 1000)
    )

    private fun fallback(state: CarromState, random: Random): CarromShot {
        val stands = CarromRules.freeStands(state, 50)
        val t = if (stands.isEmpty()) 500 else stands.random(random)
        return CarromShot(t, random.nextInt(-2000, 2001), random.nextInt(300, 800))
    }

    private fun candidates(state: CarromState, seat: Int): List<Cand> = gen(state, seat)

    private fun gen(state: CarromState, seat: Int): List<Cand> {
        val me = state.players[seat]
        val side = me.side
        val team = me.team
        val stands = (0..1000 step 125).filter { !CarromRules.standBlocked(state.coins, side, it) }
        if (stands.isEmpty()) return emptyList()
        val targets = state.coins.filter {
            val c = CarromBoard.colourOf(it.id)
            c == team || (it.id == CarromBoard.QUEEN_ID && state.queenOwner == -1)
        }
        val out = ArrayList<Cand>()
        val a = CarromPhysics.DECEL
        for (coin in targets) {
            for (p in 0 until 4) {
                val px = CarromBoard.pocketX[p]
                val py = CarromBoard.pocketY[p]
                val cdx = px - coin.x
                val cdy = py - coin.y
                val cd = sqrt(cdx * cdx + cdy * cdy)
                if (cd < 1.0) continue
                val ux = cdx / cd
                val uy = cdy / cd
                if (blocked(state.coins, coin.x, coin.y, px, py, coin.id, CarromBoard.COIN_R * 1.9)) continue
                val gx = coin.x - ux * (CarromBoard.COIN_R + CarromBoard.STRIKER_R)
                val gy = coin.y - uy * (CarromBoard.COIN_R + CarromBoard.STRIKER_R)
                for (t in stands) {
                    val (sx, sy) = CarromBoard.strikerStart(side, t)
                    val dx = gx - sx
                    val dy = gy - sy
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < 1.0) continue
                    val cos = (dx * ux + dy * uy) / dist
                    if (cos < 0.3) continue
                    if (blocked(state.coins, sx, sy, gx, gy, coin.id, CarromBoard.COIN_R + CarromBoard.STRIKER_R - 1.0)) continue
                    // speed the coin needs, and the striker speed at the ghost point to give it
                    val vCoin = sqrt(2 * a * cd) + 120.0
                    val vGhost = vCoin / (1.45 * cos) * 1.05
                    val vStart = sqrt(vGhost * vGhost + 2 * a * dist)
                    if (vStart > CarromPhysics.MAX_SPEED) continue
                    val power = ((vStart - CarromPhysics.MIN_SPEED) / (CarromPhysics.MAX_SPEED - CarromPhysics.MIN_SPEED) * 1000).toInt().coerceIn(1, 1000)
                    // angle relative to the shooter's forward direction
                    val (fx, fy) = CarromBoard.shotDirection(side, 0)
                    val (rx, ry) = CarromBoard.rotate(side, 1.0, 0.0)
                    val ang = Math.toDegrees(atan2(dx * rx + dy * ry, dx * fx + dy * fy))
                    val aim = (ang * 100).toInt()
                    if (abs(aim) > 8000) continue
                    // rough: straight, short, easy pockets first; the queen a little less eagerly
                    val rough = cos * 100 - dist * 0.05 - cd * 0.04 - (if (coin.id == CarromBoard.QUEEN_ID) 8 else 0)
                    out += Cand(CarromShot(t, aim, power), rough)
                }
            }
        }
        out.sortByDescending { it.rough }
        // a few scattering shots into the middle, for a full board
        val scatter = ArrayList<Cand>()
        for (t in stands.filterIndexed { i, _ -> i % 2 == 0 }) {
            val (sx, sy) = CarromBoard.strikerStart(side, t)
            val (fx, fy) = CarromBoard.shotDirection(side, 0)
            val (rx, ry) = CarromBoard.rotate(side, 1.0, 0.0)
            val dx = CarromBoard.CENTER - sx
            val dy = CarromBoard.CENTER - sy
            val ang = Math.toDegrees(atan2(dx * rx + dy * ry, dx * fx + dy * fy))
            for (pw in intArrayOf(450, 700, 950)) scatter += Cand(CarromShot(t, (ang * 100).toInt().coerceIn(-8000, 8000), pw), -50.0)
        }
        return if (out.size >= 6) out + scatter else out + scatter.take(6)
    }

    /** True when some other coin sits within [clearance] of the segment from (x1,y1) to (x2,y2). */
    private fun blocked(coins: List<CarromCoin>, x1: Double, y1: Double, x2: Double, y2: Double, ignoreId: Int, clearance: Double): Boolean {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9) return false
        for (c in coins) {
            if (c.id == ignoreId) continue
            var t = ((c.x - x1) * dx + (c.y - y1) * dy) / len2
            t = t.coerceIn(0.0, 1.0)
            val px = x1 + dx * t
            val py = y1 + dy * t
            val ex = c.x - px
            val ey = c.y - py
            if (ex * ex + ey * ey < clearance * clearance) return true
        }
        return false
    }
}
