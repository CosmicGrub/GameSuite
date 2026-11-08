package com.gamesuite.games.sumopush

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Sumo Push physics: a deterministic fixed-step circles simulation (62.5 steps a second, 16 ms each). World
 * units: the ring is centred on (0,0) and has radius [SumoConfig.ringStart]; x runs right and y runs DOWN, the
 * same axes as the screen, so a stick or pad vector from the screen maps straight onto the world. No clocks,
 * no Compose: a seeded Random and a list of inputs replay a round exactly.
 */

enum class SumoArena(val radius: Double) {
    SMALL(0.80), MEDIUM(1.00), LARGE(1.20);

    companion object { fun fromId(id: String?): SumoArena = entries.firstOrNull { it.name.equals(id, true) } ?: MEDIUM }
}

enum class SumoPower { HEAVY, SPRINT }

object SumoRules {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 4
    const val STEP_MS = 16
    const val DT = 0.016

    const val RADIUS = 0.115
    const val MASS = 1.0

    /** Steering acceleration and ground drag: top speed is ACCEL / DRAG (about 1.08 units a second). */
    const val ACCEL = 2.8
    const val DRAG = 2.6
    const val RESTITUTION = 0.8

    /** Hold the dash button to charge, release to dash; a full charge fires on its own. */
    const val CHARGE_MAX_MS = 700
    const val DASH_MIN_SPEED = 0.8
    const val DASH_EXTRA_SPEED = 1.6
    const val DASH_ACTIVE_MS = 380
    const val DASH_MASS_FACTOR = 2.2
    const val CHARGING_STEER = 0.45
    const val COOLDOWN_MS = 2400
    const val RELAXED_COOLDOWN_MS = 1700

    const val GRACE_MS = 9_000
    const val RELAXED_GRACE_MS = 14_000
    const val SHRINK_MS = 50_000
    const val RELAXED_SHRINK_MS = 80_000
    const val RING_MIN = 0.40
    const val ROUND_CAP_MS = 120_000

    /** The time a sumo keeps falling on screen after leaving the ring. */
    const val FALL_MS = 700

    const val ITEM_EVERY_MS = 8_000
    const val MAX_ITEMS = 2
    const val HEAVY_MS = 6_000
    const val HEAVY_MASS = 1.8
    const val SPRINT_MS = 5_000
    const val SPRINT_ACCEL = 1.45
    const val ITEM_RADIUS = 0.07

    const val COUNTDOWN_BEATS = 3
    const val COUNTDOWN_BEAT_MS = 700L
    const val ROUND_BREAK_MS = 2200L

    /** Half the world width the board shows, so the ring (even a large relaxed one) always fits with a margin. */
    const val VIEW_HALF = 1.45

    fun winsNeeded(rounds: Int): Int = rounds / 2 + 1
    fun maxRounds(rounds: Int): Int = rounds + 2
}

data class SumoConfig(
    val players: Int = 2,
    val arena: SumoArena = SumoArena.MEDIUM,
    val rounds: Int = 3,
    val powerUps: Boolean = false,
    val relaxed: Boolean = false,
    val oneSwitch: Boolean = false
) {
    val ringStart: Double get() = arena.radius * if (relaxed) 1.12 else 1.0
    val graceMs: Int get() = if (relaxed) SumoRules.RELAXED_GRACE_MS else SumoRules.GRACE_MS
    val shrinkMs: Int get() = if (relaxed) SumoRules.RELAXED_SHRINK_MS else SumoRules.SHRINK_MS
    val cooldownMs: Int get() = if (relaxed) SumoRules.RELAXED_COOLDOWN_MS else SumoRules.COOLDOWN_MS
    val accel: Double get() = SumoRules.ACCEL * if (relaxed) 0.85 else 1.0

    fun sane(): SumoConfig = copy(
        players = players.coerceIn(SumoRules.MIN_PLAYERS, SumoRules.MAX_PLAYERS),
        rounds = if (rounds == 5) 5 else 3
    )

    companion object {
        fun fromExtras(players: Int, extras: Map<String, String>): SumoConfig = SumoConfig(
            players = players,
            arena = SumoArena.fromId(extras["arena"]),
            rounds = extras["rounds"]?.toIntOrNull() ?: 3,
            powerUps = extras["powerups"] == "true",
            relaxed = extras["relaxed"] == "true",
            oneSwitch = extras["controls"] == "one"
        ).sane()
    }
}

class Sumo(val id: Int) {
    var x = 0.0
    var y = 0.0
    var vx = 0.0
    var vy = 0.0
    var alive = true
    var facingX = 0.0
    var facingY = -1.0
    var inputX = 0.0
    var inputY = 0.0
    var charging = false
    var chargeMs = 0
    var cooldownMs = 0
    var dashMs = 0
    var heavyMs = 0
    var sprintMs = 0
    var fellAtMs = -1L

    /** The one-switch control: steer by itself toward the nearest rival. */
    var autoSteer = false

    val mass: Double
        get() = SumoRules.MASS * (if (heavyMs > 0) SumoRules.HEAVY_MASS else 1.0) * (if (dashMs > 0) SumoRules.DASH_MASS_FACTOR else 1.0)

    val speed: Double get() = sqrt(vx * vx + vy * vy)
}

data class SumoItem(val x: Double, val y: Double, val kind: SumoPower)

sealed class SumoEvent {
    /** Two sumos met. [strength] is the closing speed along their line. */
    data class Hit(val a: Int, val b: Int, val strength: Double) : SumoEvent()
    data class Dash(val seat: Int, val charge: Double) : SumoEvent()
    data class Fell(val seat: Int) : SumoEvent()
    data class Pickup(val seat: Int, val kind: SumoPower) : SumoEvent()
}

class SumoEngine(val config: SumoConfig, private val random: Random = Random.Default) {
    val sumos: List<Sumo> = List(config.players) { Sumo(it) }
    val items = ArrayList<SumoItem>()

    /** Milliseconds since the round started. */
    var timeMs = 0L
        private set
    var over = false
        private set

    /** The last sumo in the ring, or -1 for a draw (or while the round is on). */
    var winner = -1
        private set

    init { reset() }

    fun alive(): Int = sumos.count { it.alive }

    fun ringRadius(): Double = ringRadiusAt(config, timeMs)

    fun reset() {
        timeMs = 0L; over = false; winner = -1
        items.clear()
        val d = config.ringStart * 0.55
        val spots = listOf(0.0 to d, 0.0 to -d, -d to 0.0, d to 0.0)
        for (s in sumos) {
            s.x = spots[s.id].first; s.y = spots[s.id].second
            s.vx = 0.0; s.vy = 0.0; s.alive = true
            val l = sqrt(s.x * s.x + s.y * s.y)
            s.facingX = -s.x / l; s.facingY = -s.y / l
            s.inputX = 0.0; s.inputY = 0.0
            s.charging = false; s.chargeMs = 0; s.cooldownMs = 0; s.dashMs = 0; s.heavyMs = 0; s.sprintMs = 0; s.fellAtMs = -1L
        }
    }

    /** Sets the steering of [seat]; the vector is clamped to length 1. */
    fun setInput(seat: Int, x: Double, y: Double) {
        val s = sumos.getOrNull(seat) ?: return
        val l = sqrt(x * x + y * y)
        if (l > 1.0) { s.inputX = x / l; s.inputY = y / l } else { s.inputX = x; s.inputY = y }
    }

    /** The dash button went down (true) or up (false). Releasing a charge fires the dash. */
    fun setCharging(seat: Int, held: Boolean) {
        val s = sumos.getOrNull(seat) ?: return
        if (!s.alive || over) { s.charging = false; return }
        if (held) {
            if (!s.charging && s.cooldownMs == 0) { s.charging = true; s.chargeMs = 0 }
        } else if (s.charging) {
            fire(s, ArrayList())
        }
    }

    private fun fire(s: Sumo, events: MutableList<SumoEvent>) {
        val c = (s.chargeMs.toDouble() / SumoRules.CHARGE_MAX_MS).coerceIn(0.0, 1.0)
        val power = SumoRules.DASH_MIN_SPEED + SumoRules.DASH_EXTRA_SPEED * c
        s.vx += s.facingX * power
        s.vy += s.facingY * power
        s.dashMs = SumoRules.DASH_ACTIVE_MS
        s.cooldownMs = config.cooldownMs
        s.charging = false; s.chargeMs = 0
        events += SumoEvent.Dash(s.id, c)
        pending += SumoEvent.Dash(s.id, c)
    }

    private val pending = ArrayList<SumoEvent>()

    /** The nearest living rival of [s], or null. */
    fun nearestRival(s: Sumo): Sumo? = sumos.filter { it.alive && it.id != s.id }.minByOrNull { sq(it.x - s.x) + sq(it.y - s.y) }

    private fun sq(v: Double) = v * v

    /** Advances the ring by one 16 ms step. A finished round ignores further steps. */
    fun step(): List<SumoEvent> {
        if (over) return emptyList()
        val events = ArrayList<SumoEvent>(pending)
        pending.clear()
        timeMs += SumoRules.STEP_MS
        val ringR = ringRadius()
        // 1. Steering, drag, dash timers, movement.
        for (s in sumos) {
            if (!s.alive) continue
            if (s.autoSteer) {
                val t = nearestRival(s)
                if (t != null) {
                    val dx = t.x - s.x; val dy = t.y - s.y
                    val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
                    s.inputX = dx / l * 0.85; s.inputY = dy / l * 0.85
                }
            }
            var ix = s.inputX; var iy = s.inputY
            val il = sqrt(ix * ix + iy * iy)
            if (il > 1e-6) { s.facingX = ix / il; s.facingY = iy / il }
            else if (s.speed > 0.15) { s.facingX = s.vx / s.speed; s.facingY = s.vy / s.speed }
            if (config.relaxed) {
                // Rope grip: the Relaxed assist stops a player from steering themselves over the edge.
                val d = sqrt(s.x * s.x + s.y * s.y)
                if (d > ringR - SumoRules.RADIUS * 0.9 && d > 1e-6 && ix * s.x + iy * s.y > 0.0) { ix *= 0.3; iy *= 0.3 }
            }
            var accel = config.accel * (if (s.sprintMs > 0) SumoRules.SPRINT_ACCEL else 1.0)
            if (s.charging) accel *= SumoRules.CHARGING_STEER
            s.vx += ix * accel * SumoRules.DT
            s.vy += iy * accel * SumoRules.DT
            val k = 1.0 - SumoRules.DRAG * SumoRules.DT
            s.vx *= k; s.vy *= k
            s.x += s.vx * SumoRules.DT
            s.y += s.vy * SumoRules.DT
            if (s.cooldownMs > 0) s.cooldownMs = (s.cooldownMs - SumoRules.STEP_MS).coerceAtLeast(0)
            if (s.dashMs > 0) s.dashMs = (s.dashMs - SumoRules.STEP_MS).coerceAtLeast(0)
            if (s.heavyMs > 0) s.heavyMs = (s.heavyMs - SumoRules.STEP_MS).coerceAtLeast(0)
            if (s.sprintMs > 0) s.sprintMs = (s.sprintMs - SumoRules.STEP_MS).coerceAtLeast(0)
            if (s.charging) {
                s.chargeMs += SumoRules.STEP_MS
                if (s.chargeMs >= SumoRules.CHARGE_MAX_MS) fire(s, events)
            }
        }
        pending.clear() // fire() above already added its events directly
        // 2. Collisions: circles with different masses and a bouncy impulse.
        for (i in sumos.indices) for (j in i + 1 until sumos.size) {
            val a = sumos[i]; val b = sumos[j]
            if (!a.alive || !b.alive) continue
            var dx = b.x - a.x; var dy = b.y - a.y
            val min = SumoRules.RADIUS * 2
            val d2 = dx * dx + dy * dy
            if (d2 >= min * min) continue
            var d = sqrt(d2)
            if (d < 1e-9) { dx = 1.0; dy = 0.0; d = 1e-9 } else { dx /= d; dy /= d }
            val ma = a.mass; val mb = b.mass
            val inv = 1.0 / ma + 1.0 / mb
            val overlap = min - d
            a.x -= dx * overlap * (1.0 / ma) / inv; a.y -= dy * overlap * (1.0 / ma) / inv
            b.x += dx * overlap * (1.0 / mb) / inv; b.y += dy * overlap * (1.0 / mb) / inv
            val vn = (a.vx - b.vx) * dx + (a.vy - b.vy) * dy
            if (vn > 0) {
                val jmp = (1.0 + SumoRules.RESTITUTION) * vn / inv
                a.vx -= dx * jmp / ma; a.vy -= dy * jmp / ma
                b.vx += dx * jmp / mb; b.vy += dy * jmp / mb
                events += SumoEvent.Hit(a.id, b.id, vn)
            }
        }
        // 3. Pickups.
        if (config.powerUps) {
            if (timeMs % SumoRules.ITEM_EVERY_MS < SumoRules.STEP_MS && timeMs > 0 && items.size < SumoRules.MAX_ITEMS) spawnItem(ringR)
            for (s in sumos) {
                if (!s.alive) continue
                val k = items.indexOfFirst { sq(it.x - s.x) + sq(it.y - s.y) < sq(SumoRules.RADIUS + SumoRules.ITEM_RADIUS) }
                if (k >= 0) {
                    val it = items.removeAt(k)
                    if (it.kind == SumoPower.HEAVY) s.heavyMs = SumoRules.HEAVY_MS else s.sprintMs = SumoRules.SPRINT_MS
                    events += SumoEvent.Pickup(s.id, it.kind)
                }
            }
        }
        // 4. Out of the ring.
        for (s in sumos) {
            if (!s.alive) continue
            if (sqrt(s.x * s.x + s.y * s.y) > ringR) {
                s.alive = false; s.charging = false; s.fellAtMs = timeMs
                events += SumoEvent.Fell(s.id)
            }
        }
        // 5. The round ends with one sumo left; the cap gives it to whoever is nearest the middle.
        val left = alive()
        if (left <= 1) {
            over = true
            winner = sumos.firstOrNull { it.alive }?.id ?: -1
        } else if (timeMs >= SumoRules.ROUND_CAP_MS) {
            over = true
            val order = sumos.filter { it.alive }.sortedBy { sq(it.x) + sq(it.y) }
            winner = if (abs(sq(order[0].x) + sq(order[0].y) - sq(order[1].x) - sq(order[1].y)) < 1e-9) -1 else order[0].id
        }
        return events
    }

    private fun spawnItem(ringR: Double) {
        repeat(20) {
            val a = random.nextDouble() * 2 * Math.PI
            val r = random.nextDouble() * (ringR - 0.25).coerceAtLeast(0.1)
            val x = cos(a) * r; val y = sin(a) * r
            if (sumos.none { it.alive && sq(it.x - x) + sq(it.y - y) < sq(0.3) }) {
                items += SumoItem(x, y, if (random.nextBoolean()) SumoPower.HEAVY else SumoPower.SPRINT)
                return
            }
        }
    }

    companion object {
        /** The ring's radius [timeMs] into a round: full size for the grace period, then shrinking to [SumoRules.RING_MIN]. */
        fun ringRadiusAt(config: SumoConfig, timeMs: Long): Double {
            val t = (timeMs - config.graceMs).coerceAtLeast(0L).toDouble() / config.shrinkMs
            val start = config.ringStart
            return (start - (start - SumoRules.RING_MIN) * t.coerceAtMost(1.0)).coerceAtLeast(SumoRules.RING_MIN)
        }
    }
}
