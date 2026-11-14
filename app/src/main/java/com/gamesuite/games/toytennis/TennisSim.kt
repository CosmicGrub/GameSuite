package com.gamesuite.games.toytennis

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Toy Tennis's court physics and rules. Pure and deterministic: no clock, no random numbers. A match
 * is a [TennisState] advanced by [TennisSim.step] at a fixed [TennisRules.STEPS] per second.
 *
 * Metres. x runs across the court (the net is at y = 0), y along it; side 0 plays from the positive-y
 * end (the bottom of the screen), side 1 from the negative-y end. [TennisState.bz] is the ball's
 * height. A shot is solved exactly: the swing picks a landing spot and a speed, the launch velocity
 * follows from ballistics, and only the swing's TIMING can spoil it (a late swing lands long, an early
 * one short and wide; a very early or late one misses completely). Nothing is random.
 */
object TennisRules {
    const val HW = 4.115
    const val HL = 11.885
    const val NET_H = 0.95
    const val SERVICE = 6.4
    const val G = 9.8
    const val STEPS = 120
    const val DT = 1.0 / STEPS
    const val BASE_Y = 12.7
    const val HIT_Z = 1.0

    /** Where the receiver's reach ends: a ball that gets this far back is hit there, high or low. */
    const val PLANE_Y = 13.8
    const val REACH = 1.9
    const val RESTITUTION = 0.74
    const val SKID = 0.88
    const val WINDOW = 0.26
    const val PERFECT = 0.04
    const val GOOD = 0.09
    const val OK = 0.17
    const val SWING_COOL = 48
    const val PREP_TICKS = 110
    const val RETRY_TICKS = 80
    const val POINT_TICKS = 170
    const val TOSS_VZ = 5.6
    const val TOSS_Z = 1.2
    const val MAX_REWIND = 36
    const val RALLY_LIMIT_TICKS = STEPS * 40
}

enum class TPhase { PREP, TOSS, RALLY, POINT, OVER }
enum class PointReason { NONE, OUT, NET, DOUBLE_FAULT, MISSED, ACE }
enum class Whiff { NONE, EARLY, LATE, FAR, WRONG_TIME }

/** A swing: [seat] is the side; [dirX] -1..1 is left..right of the hitter's own view; [power] 0..1. */
data class SwingCmd(val seat: Int, val dirX: Double, val power: Double, val exact: Boolean = false)

/** Who is controlled how; fixed for a match. Speeds in metres per second, reactions in ticks. */
data class TennisConfig(
    val assist: Boolean = false,
    val bot: BooleanArray = booleanArrayOf(false, true),
    val speed: DoubleArray = doubleArrayOf(8.0, 7.0),
    val react: IntArray = intArrayOf(0, 24)
)

@Serializable
data class TennisState(
    var tick: Int = 0,
    var phase: TPhase = TPhase.PREP,
    var phaseTicks: Int = 0,
    var bx: Double = 0.0, var by: Double = 0.0, var bz: Double = 0.0,
    var bvx: Double = 0.0, var bvy: Double = 0.0, var bvz: Double = 0.0,
    var bounces: Int = 0,
    var lastHitter: Int = -1,
    var lastWasServe: Boolean = false,
    var held: Boolean = false,
    var hitTick: Int = 0,
    var p0x: Double = 0.0, var p0y: Double = TennisRules.BASE_Y,
    var p1x: Double = 0.0, var p1y: Double = -TennisRules.BASE_Y,
    var move0: Int = 0, var move1: Int = 0,
    var cTick: Int = -1, var cX: Double = 0.0, var cY: Double = 0.0, var cZ: Double = 0.0,
    var server: Int = 0,
    var boxSign: Int = -1,
    var faults: Int = 0,
    var cool0: Int = 0, var cool1: Int = 0,
    var score: TennisScore = TennisScore(TennisFormat.POINTS7),
    var pointReason: PointReason = PointReason.NONE,
    var pointWinner: Int = -1,
    var hitSerial: Int = 0, var bounceSerial: Int = 0, var pointSerial: Int = 0,
    var faultSerial: Int = 0, var swingSerial: Int = 0, var netSerial: Int = 0, var whiffSerial: Int = 0,
    var lastQuality: Int = 0,
    var lastSwingSide: Int = -1,
    var lastWhiff: Whiff = Whiff.NONE
) {
    fun px(side: Int) = if (side == 0) p0x else p1x
    fun py(side: Int) = if (side == 0) p0y else p1y
    fun cool(side: Int) = if (side == 0) cool0 else cool1
    val winner: Int get() = score.winner
}

class TennisSim(val cfg: TennisConfig) {
    private fun sideSign(side: Int) = if (side == 0) 1.0 else -1.0

    fun newMatch(format: TennisFormat, firstServer: Int): TennisState {
        val s = TennisState(score = TennisScore(format, firstServer = firstServer))
        placeForServe(s)
        s.phase = TPhase.PREP
        s.phaseTicks = TennisRules.PREP_TICKS + 60
        return s
    }

    /** Ball in the server's hand, players on their serve and return spots. */
    fun placeForServe(s: TennisState) {
        val srv = s.score.server
        s.server = srv
        val ss = sideSign(srv)
        val xs = (if (s.score.serveSide == 0) 1.0 else -1.0) * ss * 1.6
        s.boxSign = if (xs > 0) -1 else 1
        s.bx = xs; s.by = ss * (TennisRules.HL + 0.3); s.bz = TennisRules.TOSS_Z
        s.bvx = 0.0; s.bvy = 0.0; s.bvz = 0.0
        s.bounces = 0; s.lastHitter = -1; s.lastWasServe = false; s.held = false
        s.cTick = -1
        val rx = s.boxSign * 2.0
        if (srv == 0) { s.p0x = xs; s.p0y = TennisRules.BASE_Y; s.p1x = rx; s.p1y = -TennisRules.BASE_Y }
        else { s.p1x = xs; s.p1y = -TennisRules.BASE_Y; s.p0x = rx; s.p0y = TennisRules.BASE_Y }
    }

    // ---- rules helpers ---------------------------------------------------------------------

    private fun legalLanding(s: TennisState, x: Double, y: Double, serve: Boolean): Boolean {
        val h = s.lastHitter
        val oppositeHalf = if (h == 0) y < 0 else y > 0
        val m = 0.07
        val inside = abs(x) <= TennisRules.HW + m && abs(y) <= TennisRules.HL + m
        if (!oppositeHalf || !inside) return false
        if (!serve) return true
        return abs(y) <= TennisRules.SERVICE + m && x * s.boxSign >= -m
    }

    private fun pointEnd(s: TennisState, winner: Int, reason: PointReason) {
        s.phase = TPhase.POINT
        s.phaseTicks = TennisRules.POINT_TICKS
        s.pointWinner = winner
        s.pointReason = reason
        s.pointSerial++
        s.score = s.score.pointWonBy(winner)
        s.cTick = -1
        s.held = false
    }

    private fun serveFault(s: TennisState) {
        s.faultSerial++
        if (s.faults == 0) {
            s.faults = 1
            placeForServe(s)
            s.phase = TPhase.PREP
            s.phaseTicks = TennisRules.RETRY_TICKS
        } else {
            pointEnd(s, 1 - s.server, PointReason.DOUBLE_FAULT)
        }
    }

    // ---- stepping --------------------------------------------------------------------------

    /**
     * Advances [s] one tick, applying [cmds] (swings for this tick) first. With [auth] false (a guest
     * carrying a host snapshot forward) only motion happens: no rule is ever decided.
     */
    fun step(s: TennisState, cmds: List<SwingCmd> = emptyList(), auth: Boolean = true) {
        if (s.phase == TPhase.OVER) { s.tick++; return }
        if (auth) for (c in cmds) applySwing(s, c)
        when (s.phase) {
            TPhase.PREP -> if (auth) {
                s.phaseTicks--
                if (s.phaseTicks <= 0) startToss(s)
            }
            TPhase.TOSS, TPhase.RALLY -> {
                val waiting = cfg.assist && s.cTick >= 0 && s.tick >= s.cTick && s.bounces <= 1 && (
                    (s.phase == TPhase.TOSS && !cfg.bot[s.server]) ||
                        (s.phase == TPhase.RALLY && s.lastHitter >= 0 && s.bounces == 1 && !cfg.bot[1 - s.lastHitter])
                    )
                s.held = waiting
                if (!waiting) ballStep(s, auth) else s.hitTick++
                if (auth && !s.held) {
                    if (s.phase == TPhase.TOSS && s.bz < 0.5 && s.bvz < 0 && s.tick > s.cTick) serveFault(s)
                    else if (s.phase == TPhase.RALLY && s.tick - s.hitTick > TennisRules.RALLY_LIMIT_TICKS) {
                        pointEnd(s, s.lastHitter, PointReason.MISSED)
                    }
                }
            }
            TPhase.POINT -> {
                ballStep(s, false)
                if (auth) {
                    s.phaseTicks--
                    if (s.phaseTicks <= 0) {
                        if (s.score.over) s.phase = TPhase.OVER
                        else {
                            s.faults = 0
                            placeForServe(s)
                            s.phase = TPhase.PREP
                            s.phaseTicks = TennisRules.PREP_TICKS
                        }
                    }
                }
            }
            TPhase.OVER -> {}
        }
        movePlayers(s)
        if (s.cool0 > 0) s.cool0--
        if (s.cool1 > 0) s.cool1--
        s.tick++
    }

    private fun startToss(s: TennisState) {
        s.phase = TPhase.TOSS
        s.bz = TennisRules.TOSS_Z
        s.bvz = TennisRules.TOSS_VZ
        val ticks = Math.ceil(TennisRules.TOSS_VZ / TennisRules.G * TennisRules.STEPS).toInt()
        s.cTick = s.tick + ticks
        s.cX = s.bx; s.cY = s.by
        s.cZ = TennisRules.TOSS_Z + TennisRules.TOSS_VZ * TennisRules.TOSS_VZ / (2 * TennisRules.G)
        s.hitTick = s.tick
    }

    private fun ballStep(s: TennisState, auth: Boolean) {
        val prevY = s.by
        s.bvz -= TennisRules.G * TennisRules.DT
        s.bx += s.bvx * TennisRules.DT
        s.by += s.bvy * TennisRules.DT
        s.bz += s.bvz * TennisRules.DT
        if (auth && s.phase == TPhase.RALLY && ((prevY > 0 && s.by <= 0) || (prevY < 0 && s.by >= 0)) && s.bz < TennisRules.NET_H) {
            s.netSerial++
            s.bvx *= 0.1; s.bvy = 0.0
            if (s.lastWasServe) serveFault(s) else pointEnd(s, 1 - s.lastHitter, PointReason.NET)
            return
        }
        if (s.bz <= 0.0 && s.bvz < 0.0) {
            s.bz = 0.0
            s.bounces++
            s.bounceSerial++
            s.bvz = -s.bvz * TennisRules.RESTITUTION
            s.bvx *= TennisRules.SKID
            s.bvy *= TennisRules.SKID
            if (!auth || s.phase == TPhase.POINT) return
            if (s.phase == TPhase.TOSS) { serveFault(s); return }
            if (s.bounces == 1) {
                if (!legalLanding(s, s.bx, s.by, s.lastWasServe)) {
                    if (s.lastWasServe) serveFault(s) else pointEnd(s, 1 - s.lastHitter, PointReason.OUT)
                }
            } else {
                pointEnd(s, s.lastHitter, if (s.lastWasServe) PointReason.ACE else PointReason.MISSED)
            }
        }
    }

    private fun movePlayers(s: TennisState) {
        for (p in 0..1) {
            val ss = sideSign(p)
            var tx = 0.0; var ty = ss * TennisRules.BASE_Y
            var speed = cfg.speed[p]
            val chasing = s.phase == TPhase.RALLY && s.cTick >= 0 && s.lastHitter >= 0 && p != s.lastHitter &&
                s.tick >= (if (p == 0) s.move0 else s.move1)
            if (s.phase == TPhase.PREP || s.phase == TPhase.TOSS || s.phase == TPhase.POINT || s.phase == TPhase.OVER) continue
            if (chasing) { tx = s.cX; ty = s.cY } else speed *= 0.6
            ty = if (p == 0) ty.coerceIn(4.0, 16.0) else ty.coerceIn(-16.0, -4.0)
            tx = tx.coerceIn(-TennisRules.HW - 3.0, TennisRules.HW + 3.0)
            val ox = s.px(p); val oy = s.py(p)
            val dx = tx - ox; val dy = ty - oy
            val d = StrictMath.hypot(dx, dy)
            val stepLen = speed * TennisRules.DT
            val nx: Double; val ny: Double
            if (d <= stepLen || d < 1e-9) { nx = tx; ny = ty } else { nx = ox + dx / d * stepLen; ny = oy + dy / d * stepLen }
            if (p == 0) { s.p0x = nx; s.p0y = ny } else { s.p1x = nx; s.p1y = ny }
        }
    }

    // ---- swings ----------------------------------------------------------------------------

    private fun whiff(s: TennisState, side: Int, why: Whiff) {
        s.whiffSerial++
        s.swingSerial++
        s.lastQuality = 0
        s.lastWhiff = why
        s.lastSwingSide = side
        if (side == 0) s.cool0 = TennisRules.SWING_COOL else s.cool1 = TennisRules.SWING_COOL
    }

    /** Whether [side] could hit the ball now (for the screen's "swing now" cue and for rejecting early swipes). */
    fun canSwing(s: TennisState, side: Int): Boolean =
        (s.phase == TPhase.TOSS && side == s.server) ||
            (s.phase == TPhase.RALLY && s.bounces == 1 && s.lastHitter >= 0 && side != s.lastHitter)

    private fun applySwing(s: TennisState, c: SwingCmd) {
        val side = c.seat
        if (side !in 0..1 || !c.dirX.isFinite() || !c.power.isFinite()) return
        if (s.phase != TPhase.TOSS && s.phase != TPhase.RALLY) return
        if (s.cool(side) > 0) return
        if (!canSwing(s, side)) { whiff(s, side, Whiff.WRONG_TIME); return }
        val relaxed = cfg.assist && !cfg.bot[side]
        val err = if (relaxed || c.exact || s.cTick < 0) 0.0 else (s.tick - s.cTick) * TennisRules.DT
        if (abs(err) > TennisRules.WINDOW) { whiff(s, side, if (err < 0) Whiff.EARLY else Whiff.LATE); return }
        if (relaxed) {
            // Relaxed timing: the player is always in place.
            if (side == 0) { s.p0x = s.bx; s.p0y = s.by.coerceIn(4.0, 16.0) } else { s.p1x = s.bx; s.p1y = s.by.coerceIn(-16.0, -4.0) }
        }
        // Reach is judged at the spot where the ball was to be hit, so a late swing is not also punished twice.
        val rx = if (s.cTick >= 0) s.cX else s.bx
        val ry = if (s.cTick >= 0) s.cY else s.by
        if (StrictMath.hypot(s.px(side) - rx, s.py(side) - ry) > TennisRules.REACH) { whiff(s, side, Whiff.FAR); return }
        val ae = abs(err)
        var q = when {
            ae <= TennisRules.PERFECT -> 1.0
            ae <= TennisRules.GOOD -> 0.85
            ae <= TennisRules.OK -> 0.6
            else -> 0.35
        }
        val quality = when {
            ae <= TennisRules.PERFECT -> 4
            ae <= TennisRules.GOOD -> 3
            ae <= TennisRules.OK -> 2
            else -> 1
        }
        if (s.bz < 0.25 || s.bz > 2.3) q *= 0.7
        val serve = s.phase == TPhase.TOSS
        val ss = sideSign(side)
        val fwdY = -ss
        val rightX = ss
        val dirX = c.dirX.coerceIn(-1.0, 1.0)
        val power = c.power.coerceIn(0.0, 1.0)
        var tx: Double; var depth: Double; val hspeed: Double
        if (serve) {
            tx = s.boxSign * TennisRules.HW / 2 + dirX * rightX * (TennisRules.HW / 2 - 0.35)
            depth = lerp(2.6, TennisRules.SERVICE - 0.35, power)
            hspeed = lerp(19.0, 34.0, power)
        } else {
            tx = dirX * rightX * (TennisRules.HW - 0.45)
            depth = lerp(3.2, TennisRules.HL - 0.5, power)
            hspeed = lerp(12.0, 27.0, power)
        }
        tx += -err * 8.0 * rightX
        depth += err * 9.0 + maxOf(0.0, power - 0.85) * 10.0   // all-out power is a gamble: it runs long
        tx = tx.coerceIn(-TennisRules.HW - 3.0, TennisRules.HW + 3.0)
        val ty = fwdY * depth.coerceAtLeast(1.0)
        launch(s, tx, ty, hspeed, q >= 0.55)
        s.lastHitter = side
        s.lastWasServe = serve
        s.bounces = 0
        s.phase = TPhase.RALLY
        s.held = false
        s.hitTick = s.tick
        s.hitSerial++
        s.swingSerial++
        s.lastQuality = quality
        s.lastWhiff = Whiff.NONE
        s.lastSwingSide = side
        if (side == 0) s.cool0 = TennisRules.SWING_COOL else s.cool1 = TennisRules.SWING_COOL
        predictContact(s)
        val other = 1 - side
        if (other == 0) s.move0 = s.tick + cfg.react[0] else s.move1 = s.tick + cfg.react[1]
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    /** Sets the ball's velocity from where it is to land at ([tx], [ty]) at horizontal [speed]; slows a net-bound help shot. */
    private fun launch(s: TennisState, tx: Double, ty: Double, speed0: Double, assistNet: Boolean) {
        var speed = speed0
        val cx = s.bx; val cy = s.by; val cz = s.bz
        val dx = tx - cx; val dy = ty - cy
        val d = StrictMath.hypot(dx, dy).coerceAtLeast(0.5)
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        for (attempt in 0 until 16) {
            val t1 = d / speed
            vx = dx / t1; vy = dy / t1
            vz = (0.5 * TennisRules.G * t1 * t1 - cz) / t1
            if (!assistNet) break
            val tn = if (vy != 0.0 && cy * (cy + dy) < 0) abs(cy / vy) else 0.0
            val zn = cz + vz * tn - 0.5 * TennisRules.G * tn * tn
            if (tn == 0.0 || zn >= TennisRules.NET_H + 0.12 || speed <= 9.0) break
            speed *= 0.92
        }
        s.bvx = vx; s.bvy = vy; s.bvz = vz
    }

    /** Predicts where and when the receiver can hit the ball (sets cTick, cX, cY, cZ); cTick = -1 when the ball never gets there. */
    fun predictContact(s: TennisState) {
        var x = s.bx; var y = s.by; var z = s.bz
        var vx = s.bvx; var vy = s.bvy; var vz = s.bvz
        var b = 0
        var t = 0
        var prevVz = vz
        s.cTick = -1
        while (t < 800) {
            val py = y
            vz -= TennisRules.G * TennisRules.DT
            x += vx * TennisRules.DT; y += vy * TennisRules.DT; z += vz * TennisRules.DT
            t++
            if (((py > 0 && y <= 0) || (py < 0 && y >= 0)) && z < TennisRules.NET_H) return
            if (z <= 0 && vz < 0) {
                z = 0.0; b++
                vz = -vz * TennisRules.RESTITUTION; vx *= TennisRules.SKID; vy *= TennisRules.SKID
                if (b == 1 && legalLanding(s, x, y, s.lastWasServe)) { prevVz = vz; continue }
                return
            }
            if (b == 1) {
                if (abs(y) >= TennisRules.PLANE_Y) {
                    s.cTick = s.tick + t; s.cX = x; s.cY = y; s.cZ = z
                    return
                }
                val apexLow = prevVz > 0 && vz <= 0 && z <= TennisRules.HIT_Z
                val falling = vz < 0 && z <= TennisRules.HIT_Z
                if (apexLow || falling) {
                    s.cTick = s.tick + t; s.cX = x; s.cY = y; s.cZ = z
                    return
                }
            }
            prevVz = vz
        }
    }
}
