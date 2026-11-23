package com.gamesuite.games.partypack

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Paddle Duel: the rules and physics, pure Kotlin (no Compose, no clock, no Android).
 *
 * The arena is a logical rectangle, [PdRules.arenaWidth] wide and [PdRules.arenaHeight] tall, y growing
 * DOWN. Seats sit on the edges chosen by PpSeatLayout.edges (2 seats: bottom + top, so the long axis is the
 * ball's flight; 3: bottom + both sides; 4: all four). Edges with no live seat are solid walls; with three
 * or four seats the corners are solid squares so two paddles never meet.
 *
 * DETERMINISM: everything advances in fixed steps of [PdRules.DT]. The only randomness is the injected
 * seed, so a seed plus the same inputs replays a match exactly. [advance] converts elapsed seconds into
 * whole steps; the caller owns the clock.
 */

enum class PdPowerKind { WIDE, SLOW, MULTI }

/** What the engine tells the screen happened (sound, haptics, particles, TalkBack). Drained with [PaddleDuelEngine.drainEvents]. */
enum class PdEventKind { SERVE_TICK, LAUNCH, PADDLE_HIT, WALL, GOAL, ELIMINATED, PICKUP_SPAWN, PICKUP_TAKEN, STALE, WIN }

/** [seat] is the seat concerned (-1 none); [a] and [b] are kind-specific numbers (see each producer); [x],[y] a logical position. */
data class PdEvent(val kind: PdEventKind, val seat: Int = -1, val a: Int = 0, val b: Int = 0, val x: Double = 0.0, val y: Double = 0.0)

/** First to N points ([points] true) or last with lives (otherwise). Ids: p5, p7, p11, l3, l5. */
data class PdFormat(val points: Boolean, val target: Int) {
    val id: String get() = (if (points) "p" else "l") + target

    companion object {
        val DEFAULT = PdFormat(points = true, target = 7)
        val ALL = listOf(PdFormat(true, 5), PdFormat(true, 7), PdFormat(true, 11), PdFormat(false, 3), PdFormat(false, 5))

        fun parse(id: String?): PdFormat = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** One seat of a match. [oneSwitch] marks a human who plays with the single-switch alternative (the paddle follows the ball by itself). */
data class PdSeat(val bot: Boolean, val level: CpuDifficulty = CpuDifficulty.MEDIUM, val oneSwitch: Boolean = false)

data class PdConfig(
    val seats: List<PdSeat>,
    val format: PdFormat = PdFormat.DEFAULT,
    val powerups: Boolean = false,
    /** The Relaxed assist: a slower ball, longer paddles, a longer serve countdown and a faster paddle. */
    val relaxed: Boolean = false,
    val seed: Long = 1L,
    /** Replaces the built-in profile of a computer level (tests and tuning only). */
    val tuning: Map<CpuDifficulty, PdBotProfile> = emptyMap()
) {
    init { require(seats.size in PpSeatLayout.MIN_SEATS..PpSeatLayout.MAX_SEATS) { "2..4 seats" } }
}

object PdRules {
    const val DT = 1.0 / 120.0
    const val BALL_R = 0.026
    const val PAD_T = 0.03
    const val PAD_GAP = 0.02
    const val PAD_LEN = 0.22
    const val WIDE_FACTOR = 1.6
    const val RELAXED_LEN_FACTOR = 1.25
    const val BASE_SPEED = 0.8
    const val RELAXED_BASE_SPEED = 0.52
    const val RALLY_GAIN = 0.07
    const val MAX_SPEED_FACTOR = 3.2
    const val MAX_ANGLE = 0.96
    const val HUMAN_PADDLE_SPEED = 2.0
    const val RELAXED_PADDLE_SPEED = 2.4
    const val BUTTON_PADDLE_SPEED = 1.5
    const val SERVE_S = 1.2
    const val RELAXED_SERVE_S = 2.4
    const val STALE_S = 20.0
    const val CORNER = 0.11
    const val SLOW_FACTOR = 0.65
    const val WIDE_S = 9.0
    const val SLOW_S = 5.0
    const val EXTRA_BALL_S = 10.0
    const val PICKUP_R = 0.045
    const val PICKUP_TTL_S = 12.0
    const val MAX_STEPS_PER_ADVANCE = 60
    const val SPIN_LIMIT = 0.5
    const val SPIN_TURN_RATE = 1.2
    const val SPIN_DECAY = 1.0

    const val ARENA_WIDTH = 1.0
    fun arenaHeight(seats: Int): Double = if (seats == 2) 1.5 else 1.0
    fun corner(seats: Int): Double = if (seats == 2) 0.0 else CORNER
}

/** A ball in flight. [extra] balls come from the multi-ball power-up and vanish after [life] seconds. */
class PdBall(var x: Double, var y: Double, var vx: Double, var vy: Double, val extra: Boolean = false, var life: Double = Double.POSITIVE_INFINITY) {
    var spin: Double = 0.0
    var age: Double = 0.0
    val speed: Double get() = hypot(vx, vy)
}

class PdPickup(val kind: PdPowerKind, val x: Double, val y: Double, var ttl: Double)

/**
 * How a computer paddle behaves. [think] is the seconds between looks at the ball (its reaction time), [error] the
 * half-width of its aiming mistake, [speed] its top paddle speed, [distract] the chance a look sends it somewhere
 * random and [lapse] the chance a look is skipped. [predicts] means it works out where the ball will cross
 * (bouncing off the side walls); otherwise it only chases the ball's current position. [aims] sends returns to
 * the side the opponent is not on (two players only).
 */
data class PdBotProfile(val think: Double, val error: Double, val speed: Double, val distract: Double, val lapse: Double, val predicts: Boolean, val aims: Boolean)

object PdBotProfiles {
    val easy = PdBotProfile(0.40, 0.12, 1.0, 0.20, 0.05, predicts = false, aims = false)
    val medium = PdBotProfile(0.26, 0.06, 1.25, 0.06, 0.04, predicts = true, aims = false)
    val hard = PdBotProfile(0.20, 0.04, 1.45, 0.04, 0.05, predicts = true, aims = true)

    /** The auto paddle of a one-switch seat: a medium reaction, no mistakes, no aiming of its own. */
    val oneSwitch = PdBotProfile(0.16, 0.0, 1.4, 0.0, 0.0, predicts = true, aims = false)

    fun of(level: CpuDifficulty): PdBotProfile = when (level) {
        CpuDifficulty.EASY -> easy
        CpuDifficulty.MEDIUM -> medium
        CpuDifficulty.HARD -> hard
    }
}

enum class PdPhase { SERVE, PLAY, OVER }

/** The score and lives of a match, the part of the engine a snapshot keeps. */
data class PdScores(val lives: List<Int>, val score: List<Int>, val alive: List<Boolean>, val rallies: Int)

class PaddleDuelEngine(val config: PdConfig, seedOffset: Long = 0L) {
    val n: Int = config.seats.size
    val edges: List<PpEdge> = PpSeatLayout.edges(n)
    val width: Double = PdRules.ARENA_WIDTH
    val height: Double = PdRules.arenaHeight(n)
    private val corner: Double = PdRules.corner(n)
    private val rng = Random(config.seed + seedOffset * 7919L)

    val lives = IntArray(n) { if (config.format.points) 0 else config.format.target }
    val score = IntArray(n)
    val alive = BooleanArray(n) { true }
    val paddlePos = DoubleArray(n)
    private val padVel = DoubleArray(n)
    private val target = DoubleArray(n) { Double.NaN }
    private val hold = IntArray(n)
    private val aim = IntArray(n)
    private val wideLeft = DoubleArray(n)
    private var slowLeft = 0.0

    val balls = mutableListOf<PdBall>()
    var pickup: PdPickup? = null
        private set
    private var nextPickupIn = 6.0
    var lastHitter = -1
        private set
    var rally = 0
        private set
    var rallies = 0
        private set
    var phase = PdPhase.SERVE
        private set
    var serveLeft = 0.0
        private set
    var winner = -1
        private set
    var simTime = 0.0
        private set
    var sinceHit = 0.0
        private set
    val over: Boolean get() = phase == PdPhase.OVER
    private var serveToward = -1
    private var lastTick = 0

    private val events = ArrayDeque<PdEvent>()
    private var acc = 0.0

    // bot memory
    private val botTarget = DoubleArray(n) { Double.NaN }
    private val botNextThink = DoubleArray(n)

    init {
        for (s in 0 until n) paddlePos[s] = extent(s) / 2.0
        startServe(-1)
    }

    // ---- geometry --------------------------------------------------------------------------

    /** Length of the edge seat [s] slides along. */
    fun extent(s: Int): Double = if (edges[s].isHorizontal) width else height

    fun paddleLength(s: Int): Double {
        var len = PdRules.PAD_LEN * (if (config.relaxed) PdRules.RELAXED_LEN_FACTOR else 1.0)
        if (wideLeft[s] > 0.0) len *= PdRules.WIDE_FACTOR
        return min(len, extent(s) - 2 * corner - 0.02)
    }

    private fun lo(s: Int) = corner + paddleLength(s) / 2.0
    private fun hi(s: Int) = extent(s) - corner - paddleLength(s) / 2.0

    /** The paddle rectangle as [minX, minY, maxX, maxY]. */
    fun paddleRect(s: Int): DoubleArray {
        val p = paddlePos[s]
        val h = paddleLength(s) / 2.0
        return when (edges[s]) {
            PpEdge.BOTTOM -> doubleArrayOf(p - h, height - PdRules.PAD_GAP - PdRules.PAD_T, p + h, height - PdRules.PAD_GAP)
            PpEdge.TOP -> doubleArrayOf(p - h, PdRules.PAD_GAP, p + h, PdRules.PAD_GAP + PdRules.PAD_T)
            PpEdge.LEFT -> doubleArrayOf(PdRules.PAD_GAP, p - h, PdRules.PAD_GAP + PdRules.PAD_T, p + h)
            PpEdge.RIGHT -> doubleArrayOf(width - PdRules.PAD_GAP - PdRules.PAD_T, p - h, width - PdRules.PAD_GAP, p + h)
        }
    }

    private fun inward(e: PpEdge): Pair<Double, Double> = when (e) {
        PpEdge.BOTTOM -> 0.0 to -1.0
        PpEdge.TOP -> 0.0 to 1.0
        PpEdge.LEFT -> 1.0 to 0.0
        PpEdge.RIGHT -> -1.0 to 0.0
    }

    private fun alongOf(b: PdBall, s: Int) = if (edges[s].isHorizontal) b.x else b.y

    /** The seat whose live edge is [e], or -1 (a wall). */
    private fun liveSeatAt(e: PpEdge): Int {
        val i = edges.indexOf(e)
        return if (i >= 0 && alive[i]) i else -1
    }

    val aliveCount: Int get() = alive.count { it }

    // ---- inputs ----------------------------------------------------------------------------

    /** Where a finger wants the paddle's centre (logical, along the seat's edge). Ignored for bots and one-switch seats. */
    fun setTarget(seat: Int, along: Double?) {
        if (seat !in 0 until n || config.seats[seat].bot || config.seats[seat].oneSwitch || !alive[seat]) return
        target[seat] = along ?: Double.NaN
        if (along != null) hold[seat] = 0
    }

    /** -1, 0 or +1: a held left/right button. Overrides a finger target while held. */
    fun setHold(seat: Int, dir: Int) {
        if (seat !in 0 until n || config.seats[seat].bot || config.seats[seat].oneSwitch || !alive[seat]) return
        hold[seat] = dir.coerceIn(-1, 1)
        if (dir != 0) target[seat] = Double.NaN
    }

    /** A one-time nudge of [dir] times a fifth of the paddle (the TalkBack / switch-access alternative to dragging). */
    fun nudge(seat: Int, dir: Int) {
        if (seat !in 0 until n || config.seats[seat].bot || config.seats[seat].oneSwitch || !alive[seat]) return
        val to = (paddlePos[seat] + dir.coerceIn(-1, 1) * paddleLength(seat) * 0.8).coerceIn(lo(seat), hi(seat))
        target[seat] = to
        hold[seat] = 0
    }

    /** The single switch of a one-switch seat: cycles where on the paddle the ball will be met (centre, one end, the other end). */
    fun pressSwitch(seat: Int) {
        if (seat !in 0 until n || !config.seats[seat].oneSwitch || !alive[seat]) return
        aim[seat] = when (aim[seat]) { 0 -> 1; 1 -> -1; else -> 0 }
    }

    fun aimOf(seat: Int): Int = aim[seat]

    fun drainEvents(): List<PdEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    private fun emit(e: PdEvent) {
        events.addLast(e)
        while (events.size > 200) events.removeFirst()
    }

    // ---- time ------------------------------------------------------------------------------

    /** Advances by [seconds] of play (at most a quarter second at a time, so a stalled frame cannot teleport anything). */
    fun advance(seconds: Double) {
        if (over || seconds <= 0.0) return
        acc += min(seconds, 0.25)
        var steps = 0
        while (acc >= PdRules.DT && steps < PdRules.MAX_STEPS_PER_ADVANCE && !over) {
            acc -= PdRules.DT
            step()
            steps++
        }
        if (steps >= PdRules.MAX_STEPS_PER_ADVANCE) acc = 0.0
    }

    /** One fixed step. Public so tests can replay exact step counts. */
    fun step() {
        if (over) return
        val dt = PdRules.DT
        simTime += dt
        for (s in 0 until n) if (alive[s]) {
            if (wideLeft[s] > 0.0) wideLeft[s] = max(0.0, wideLeft[s] - dt)
            movePaddle(s, dt)
        }
        when (phase) {
            PdPhase.SERVE -> stepServe(dt)
            PdPhase.PLAY -> stepPlay(dt)
            PdPhase.OVER -> {}
        }
    }

    private fun stepServe(dt: Double) {
        serveLeft -= dt
        val t = kotlin.math.ceil(serveLeft / (if (config.relaxed) PdRules.RELAXED_SERVE_S else PdRules.SERVE_S) * 3.0).toInt().coerceIn(0, 3)
        if (t != lastTick && t in 1..3) emit(PdEvent(PdEventKind.SERVE_TICK, a = t))
        lastTick = t
        if (serveLeft <= 0.0) launch()
    }

    private fun startServe(toward: Int) {
        balls.clear()
        balls += PdBall(width / 2, height / 2, 0.0, 0.0)
        serveToward = toward
        phase = PdPhase.SERVE
        serveLeft = if (config.relaxed) PdRules.RELAXED_SERVE_S else PdRules.SERVE_S
        lastTick = 4
        rally = 0
        sinceHit = 0.0
        lastHitter = -1
        slowLeft = 0.0
        pickup = null
        nextPickupIn = 5.0 + rng.nextDouble() * 4.0
    }

    private fun baseSpeed() = if (config.relaxed) PdRules.RELAXED_BASE_SPEED else PdRules.BASE_SPEED

    private fun launch() {
        val live = (0 until n).filter { alive[it] }
        val toward = if (serveToward in live) serveToward else live[rng.nextInt(live.size)]
        val nrm = inward(edges[toward])
        // the ball leaves the centre toward the seat that is about to receive it, within 30 degrees of straight
        val jitter = (rng.nextDouble() * 2 - 1) * 0.52
        val dx = -nrm.first
        val dy = -nrm.second
        val c = cos(jitter)
        val sn = sin(jitter)
        val b = balls.first()
        val sp = baseSpeed()
        b.vx = (dx * c - dy * sn) * sp
        b.vy = (dx * sn + dy * c) * sp
        phase = PdPhase.PLAY
        emit(PdEvent(PdEventKind.LAUNCH, seat = toward, x = b.x, y = b.y))
    }

    // ---- paddles ---------------------------------------------------------------------------

    private fun movePaddle(s: Int, dt: Double) {
        val spec = config.seats[s]
        val before = paddlePos[s]
        var speed: Double
        var goal = Double.NaN
        var dir = 0
        if (spec.bot || spec.oneSwitch) {
            botThinkIfDue(s)
            goal = botTarget[s]
            speed = botSpeed(s)
        } else {
            speed = if (config.relaxed) PdRules.RELAXED_PADDLE_SPEED else PdRules.HUMAN_PADDLE_SPEED
            if (hold[s] != 0) { dir = hold[s]; speed = PdRules.BUTTON_PADDLE_SPEED } else goal = target[s]
        }
        var p = before
        if (dir != 0) {
            p += dir * speed * dt
        } else if (!goal.isNaN()) {
            val d = goal.coerceIn(lo(s), hi(s)) - p
            val stepLen = speed * dt
            p += if (abs(d) <= stepLen) d else if (d > 0) stepLen else -stepLen
        }
        p = p.coerceIn(lo(s), hi(s))
        paddlePos[s] = p
        padVel[s] = (p - before) / dt
    }

    // ---- bots ------------------------------------------------------------------------------

    private fun profile(s: Int): PdBotProfile {
        val spec = config.seats[s]
        if (!spec.bot) return PdBotProfiles.oneSwitch
        return config.tuning[spec.level] ?: PdBotProfiles.of(spec.level)
    }

    private fun botSpeed(s: Int) = profile(s).speed

    private fun botThinkIfDue(s: Int) {
        if (simTime < botNextThink[s] && !botTarget[s].isNaN()) return
        val prof = profile(s)
        botNextThink[s] = simTime + prof.think
        if (!botTarget[s].isNaN() && prof.lapse > 0.0 && rng.nextDouble() < prof.lapse) return // a lapse of attention: the old target stands
        val threat = threatFor(s)
        var tgt: Double
        if (threat == null) {
            tgt = extent(s) / 2.0
        } else {
            tgt = if (prof.predicts) predictAlong(threat, s) else alongOf(threat, s)
            if (prof.aims) tgt -= desiredOffset(s) * paddleLength(s) / 2.0 * 0.6
            if (!config.seats[s].bot) tgt -= aim[s] * 0.7 * paddleLength(s) / 2.0
        }
        tgt += (rng.nextDouble() * 2 - 1) * prof.error
        if (rng.nextDouble() < prof.distract) tgt = lo(s) + rng.nextDouble() * (hi(s) - lo(s))
        botTarget[s] = tgt.coerceIn(lo(s), hi(s))
    }

    /** The ball that will reach seat [s]'s paddle plane first, or null when none is coming. */
    private fun threatFor(s: Int): PdBall? {
        val nrm = inward(edges[s])
        var best: PdBall? = null
        var bestT = Double.MAX_VALUE
        for (b in balls) {
            val approach = -(b.vx * nrm.first + b.vy * nrm.second)
            if (approach <= 1e-6) continue
            val t = distanceToPlane(b, s) / approach
            if (t < bestT) { bestT = t; best = b }
        }
        return best
    }

    private fun distanceToPlane(b: PdBall, s: Int): Double {
        val front = PdRules.PAD_GAP + PdRules.PAD_T + PdRules.BALL_R
        return when (edges[s]) {
            PpEdge.BOTTOM -> (height - front) - b.y
            PpEdge.TOP -> b.y - front
            PpEdge.LEFT -> b.x - front
            PpEdge.RIGHT -> (width - front) - b.x
        }.coerceAtLeast(0.0)
    }

    /** Where along its edge the ball will cross the paddle plane, folding off the side walls. */
    private fun predictAlong(b: PdBall, s: Int): Double {
        val nrm = inward(edges[s])
        val approach = -(b.vx * nrm.first + b.vy * nrm.second)
        val t = distanceToPlane(b, s) / max(approach, 1e-6)
        val along0 = alongOf(b, s)
        val vAlong = if (edges[s].isHorizontal) b.vx else b.vy
        val a = corner + PdRules.BALL_R
        val c = extent(s) - corner - PdRules.BALL_R
        return fold(along0 + vAlong * t, a, c)
    }

    private fun fold(x: Double, a: Double, b: Double): Double {
        val span = b - a
        if (span <= 0.0) return a
        val period = 2 * span
        var m = (x - a) % period
        if (m < 0) m += period
        return if (m <= span) a + m else a + (period - m)
    }

    /** Hard seats (two players only) send the ball to the side of the far edge where the opponent is not: +1 or -1 of the contact offset. */
    private fun desiredOffset(s: Int): Double {
        if (n != 2) return 0.0
        val opp = 1 - s
        if (!alive[opp]) return 0.0
        val mid = extent(opp) / 2.0
        return if (paddlePos[opp] < mid) 1.0 else -1.0
    }

    // ---- play ------------------------------------------------------------------------------

    private fun timeScale() = if (slowLeft > 0.0) PdRules.SLOW_FACTOR else 1.0

    private fun stepPlay(dt: Double) {
        sinceHit += dt
        if (slowLeft > 0.0) slowLeft = max(0.0, slowLeft - dt)
        if (config.powerups) stepPickups(dt)
        val ts = timeScale()
        var i = 0
        while (i < balls.size) {
            val b = balls[i]
            if (b.extra) {
                b.life -= dt
                if (b.life <= 0.0) { balls.removeAt(i); continue }
            }
            moveBall(b, dt * ts)
            if (over) return
            val conceder = goalSeat(b)
            if (conceder >= 0) {
                balls.removeAt(i)
                conceded(conceder, b)
                if (over || phase != PdPhase.PLAY) return // the goal ended the match or started a new serve
                continue
            }
            i++
        }
        if (sinceHit > PdRules.STALE_S && phase == PdPhase.PLAY) {
            emit(PdEvent(PdEventKind.STALE))
            startServe(serveToward)
        }
    }

    private fun moveBall(b: PdBall, dt: Double) {
        b.age += dt
        if (b.spin != 0.0) {
            val ang = b.spin * PdRules.SPIN_TURN_RATE * dt
            val c = cos(ang)
            val sn = sin(ang)
            val nx = b.vx * c - b.vy * sn
            val ny = b.vx * sn + b.vy * c
            b.vx = nx; b.vy = ny
            b.spin *= exp(-PdRules.SPIN_DECAY * dt)
            if (abs(b.spin) < 1e-3) b.spin = 0.0
        }
        b.x += b.vx * dt
        b.y += b.vy * dt
        collideWalls(b)
        collideCorners(b)
        collidePaddles(b)
    }

    private fun collideWalls(b: PdBall) {
        val r = PdRules.BALL_R
        var hit = false
        if (liveSeatAt(PpEdge.LEFT) < 0) {
            if (b.x < r) { b.x = r; if (b.vx < 0) { b.vx = -b.vx; hit = true } }
        }
        if (liveSeatAt(PpEdge.RIGHT) < 0) {
            if (b.x > width - r) { b.x = width - r; if (b.vx > 0) { b.vx = -b.vx; hit = true } }
        }
        if (liveSeatAt(PpEdge.TOP) < 0) {
            if (b.y < r) { b.y = r; if (b.vy < 0) { b.vy = -b.vy; hit = true } }
        }
        if (liveSeatAt(PpEdge.BOTTOM) < 0) {
            if (b.y > height - r) { b.y = height - r; if (b.vy > 0) { b.vy = -b.vy; hit = true } }
        }
        if (hit) {
            b.spin *= -0.5
            emit(PdEvent(PdEventKind.WALL, x = b.x, y = b.y))
        }
    }

    private fun collideCorners(b: PdBall) {
        if (corner <= 0.0) return
        val c = corner
        val boxes = arrayOf(
            doubleArrayOf(0.0, 0.0, c, c), doubleArrayOf(width - c, 0.0, width, c),
            doubleArrayOf(0.0, height - c, c, height), doubleArrayOf(width - c, height - c, width, height)
        )
        for (box in boxes) {
            val cx = b.x.coerceIn(box[0], box[2])
            val cy = b.y.coerceIn(box[1], box[3])
            val dx = b.x - cx
            val dy = b.y - cy
            val d = hypot(dx, dy)
            if (d >= PdRules.BALL_R) continue
            val nx: Double
            val ny: Double
            if (d < 1e-9) { nx = b.x - (box[0] + box[2]) / 2; ny = b.y - (box[1] + box[3]) / 2 } else { nx = dx; ny = dy }
            val len = hypot(nx, ny).let { if (it < 1e-9) 1.0 else it }
            val ux = nx / len
            val uy = ny / len
            b.x = cx + ux * (PdRules.BALL_R + 1e-6)
            b.y = cy + uy * (PdRules.BALL_R + 1e-6)
            val vn = b.vx * ux + b.vy * uy
            if (vn < 0) {
                b.vx -= 2 * vn * ux
                b.vy -= 2 * vn * uy
                b.spin *= -0.5
                emit(PdEvent(PdEventKind.WALL, x = b.x, y = b.y))
            }
        }
    }

    private fun collidePaddles(b: PdBall) {
        for (s in 0 until n) {
            if (!alive[s]) continue
            val nrm = inward(edges[s])
            if (b.vx * nrm.first + b.vy * nrm.second >= 0.0) continue // moving away from this edge
            val rect = paddleRect(s)
            val cx = b.x.coerceIn(rect[0], rect[2])
            val cy = b.y.coerceIn(rect[1], rect[3])
            if (hypot(b.x - cx, b.y - cy) >= PdRules.BALL_R) continue
            hitPaddle(b, s, rect)
            return
        }
    }

    private fun hitPaddle(b: PdBall, s: Int, rect: DoubleArray) {
        val e = edges[s]
        val nrm = inward(e)
        val len = paddleLength(s)
        val along = alongOf(b, s)
        val off = ((along - paddlePos[s]) / (len / 2.0)).coerceIn(-1.0, 1.0)
        rally++
        rallies++
        sinceHit = 0.0
        lastHitter = s
        val speed = min(baseSpeed() * (1.0 + PdRules.RALLY_GAIN * rally), baseSpeed() * PdRules.MAX_SPEED_FACTOR)
        val pv = padVel[s]
        val angle = (off * PdRules.MAX_ANGLE + (pv / PdRules.HUMAN_PADDLE_SPEED) * 0.12).coerceIn(-PdRules.MAX_ANGLE, PdRules.MAX_ANGLE)
        val tx = if (e.isHorizontal) 1.0 else 0.0
        val ty = if (e.isHorizontal) 0.0 else 1.0
        b.vx = nrm.first * cos(angle) * speed + tx * sin(angle) * speed
        b.vy = nrm.second * cos(angle) * speed + ty * sin(angle) * speed
        // put the ball just in front of the paddle
        when (e) {
            PpEdge.BOTTOM -> b.y = rect[1] - PdRules.BALL_R - 1e-6
            PpEdge.TOP -> b.y = rect[3] + PdRules.BALL_R + 1e-6
            PpEdge.LEFT -> b.x = rect[2] + PdRules.BALL_R + 1e-6
            PpEdge.RIGHT -> b.x = rect[0] - PdRules.BALL_R - 1e-6
        }
        val cross = nrm.first * ty - nrm.second * tx
        b.spin = (cross * (pv / PdRules.HUMAN_PADDLE_SPEED) * PdRules.SPIN_LIMIT).coerceIn(-PdRules.SPIN_LIMIT, PdRules.SPIN_LIMIT)
        emit(PdEvent(PdEventKind.PADDLE_HIT, seat = s, a = rally, b = (speed * 1000).toInt(), x = b.x, y = b.y))
        if (config.powerups) checkPickup(b)
    }

    // ---- power-ups -------------------------------------------------------------------------

    private fun stepPickups(dt: Double) {
        val p = pickup
        if (p != null) {
            p.ttl -= dt
            if (p.ttl <= 0.0) { pickup = null; nextPickupIn = 6.0 + rng.nextDouble() * 5.0 }
            else for (b in balls) if (checkPickup(b)) break
        } else {
            nextPickupIn -= dt
            if (nextPickupIn <= 0.0) {
                val kind = PdPowerKind.entries[rng.nextInt(PdPowerKind.entries.size)]
                val x = width * (0.3 + rng.nextDouble() * 0.4)
                val y = height * (0.3 + rng.nextDouble() * 0.4)
                pickup = PdPickup(kind, x, y, PdRules.PICKUP_TTL_S)
                emit(PdEvent(PdEventKind.PICKUP_SPAWN, a = kind.ordinal, x = x, y = y))
            }
        }
    }

    private fun checkPickup(b: PdBall): Boolean {
        val p = pickup ?: return false
        if (lastHitter < 0 || !alive[lastHitter]) return false
        if (hypot(b.x - p.x, b.y - p.y) > PdRules.BALL_R + PdRules.PICKUP_R) return false
        pickup = null
        nextPickupIn = 6.0 + rng.nextDouble() * 5.0
        when (p.kind) {
            PdPowerKind.WIDE -> wideLeft[lastHitter] = PdRules.WIDE_S
            PdPowerKind.SLOW -> slowLeft = PdRules.SLOW_S
            PdPowerKind.MULTI -> {
                val ang = rng.nextDouble() * 2 * PI
                val sp = max(b.speed, baseSpeed())
                balls += PdBall(p.x, p.y, cos(ang) * sp, sin(ang) * sp, extra = true, life = PdRules.EXTRA_BALL_S)
            }
        }
        emit(PdEvent(PdEventKind.PICKUP_TAKEN, seat = lastHitter, a = p.kind.ordinal, x = p.x, y = p.y))
        return true
    }

    // ---- goals and the end -----------------------------------------------------------------

    /** The seat whose edge [b] has crossed, or -1. */
    private fun goalSeat(b: PdBall): Int {
        for (s in 0 until n) {
            if (!alive[s]) continue
            val gone = when (edges[s]) {
                PpEdge.BOTTOM -> b.y > height
                PpEdge.TOP -> b.y < 0.0
                PpEdge.LEFT -> b.x < 0.0
                PpEdge.RIGHT -> b.x > width
            }
            if (gone) return s
        }
        return -1
    }

    private fun conceded(s: Int, b: PdBall) {
        val scorer = when {
            n == 2 -> 1 - s
            lastHitter >= 0 && lastHitter != s && alive[lastHitter] -> lastHitter
            else -> -1
        }
        if (config.format.points) {
            if (scorer >= 0) score[scorer]++
        } else {
            lives[s] = max(0, lives[s] - 1)
        }
        emit(PdEvent(PdEventKind.GOAL, seat = s, a = scorer, b = b.age.toInt(), x = b.x, y = b.y))
        var eliminated = false
        if (!config.format.points && lives[s] == 0) {
            alive[s] = false
            target[s] = Double.NaN
            hold[s] = 0
            eliminated = true
            emit(PdEvent(PdEventKind.ELIMINATED, seat = s))
        }
        checkWin()
        if (over) { balls.clear(); return }
        // a goal on the last ball in play ends the rally; extra balls only ever come with the main one
        if (balls.isEmpty()) {
            val next = if (eliminated) (0 until n).filter { alive[it] }.let { live -> live[rng.nextInt(live.size)] } else s
            startServe(next)
        }
    }

    private fun checkWin() {
        if (config.format.points) {
            val best = score.indices.filter { score[it] >= config.format.target }.maxByOrNull { score[it] }
            if (best != null) finish(best)
        } else if (aliveCount <= 1) {
            finish(alive.indexOfFirst { it })
        }
    }

    private fun finish(seat: Int) {
        winner = seat
        phase = PdPhase.OVER
        emit(PdEvent(PdEventKind.WIN, seat = seat))
    }

    // ---- snapshots -------------------------------------------------------------------------

    fun scores(): PdScores = PdScores(lives.toList(), score.toList(), alive.toList(), rallies)

    /** Puts a validated score back in place and starts a fresh serve (a round restored mid-rally restarts the rally). False leaves the engine untouched. */
    fun restoreScores(s: PdScores): Boolean {
        if (s.lives.size != n || s.score.size != n || s.alive.size != n) return false
        if (s.rallies < 0) return false
        val f = config.format
        for (i in 0 until n) {
            if (f.points) { if (s.score[i] !in 0 until f.target) return false }
            else { if (s.lives[i] !in 0..f.target) return false; if (s.alive[i] != (s.lives[i] > 0)) return false }
        }
        if (!f.points && s.alive.count { it } < 2) return false
        for (i in 0 until n) { lives[i] = s.lives[i]; score[i] = s.score[i]; alive[i] = s.alive[i] }
        rallies = s.rallies
        for (i in 0 until n) { paddlePos[i] = extent(i) / 2.0; padVel[i] = 0.0; target[i] = Double.NaN; hold[i] = 0; wideLeft[i] = 0.0; botTarget[i] = Double.NaN }
        startServe(-1)
        return true
    }
}
