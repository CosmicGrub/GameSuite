package com.gamesuite.games.lighttrail

import kotlin.random.Random

/*
 * Light-Trail Duel rules: a deterministic fixed-step grid simulation. Nothing here knows about clocks,
 * Compose or Android; [LightTrailEngine.step] advances the whole arena by exactly one cell per rider
 * (two for a boosted one), so a seeded Random and a list of commands replay a round exactly.
 */

enum class LtDir(val dx: Int, val dy: Int) {
    UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);

    fun cw(): LtDir = entries[(ordinal + 1) % 4]
    fun ccw(): LtDir = entries[(ordinal + 3) % 4]
}

/** A steering command. SWITCH is the one-button control: it turns toward whichever side is "armed" right now. */
enum class LtTurn { LEFT, RIGHT, SWITCH }

enum class LtPower { GAP, BOOST }

enum class LtCause { EDGE, WALL, SELF, HEAD_ON, GAP_END, TIMEOUT }

enum class LtArena(val cells: Int) { SMALL(20), MEDIUM(28), LARGE(38);
    companion object { fun fromId(id: String?): LtArena = entries.firstOrNull { it.name.equals(id, true) } ?: MEDIUM }
}

object LightTrailRules {
    const val MAX_PLAYERS = 4
    const val MIN_PLAYERS = 2

    /** Milliseconds per cell at normal speed (the game scales it by the game speed and the Relaxed assist). */
    const val BASE_STEP_MS = 135L
    const val RELAXED_STEP_FACTOR = 1.45
    const val RELAXED_EXTRA_CELLS = 6

    const val GAP_MOVES = 4
    const val BOOST_STEPS = 6
    const val ITEM_EVERY_STEPS = 36
    const val MAX_ITEMS = 2

    /** The one-button control arms the right side for this many steps, then the left, and so on. */
    const val ARM_FLIP_STEPS = 3

    /** Relaxed: the same rider is only saved from a wall once in this many steps. */
    const val AUTO_AVOID_COOLDOWN = 10

    const val COUNTDOWN_BEATS = 3
    const val COUNTDOWN_BEAT_MS = 700L
    const val ROUND_BREAK_MS = 2200L
    const val MAX_QUEUED_TURNS = 2

    /** Wins a player needs in a best-of-[rounds] match. */
    fun winsNeeded(rounds: Int): Int = rounds / 2 + 1

    /** Rounds played at most (draws replay a round, but not forever). */
    fun maxRounds(rounds: Int): Int = rounds + 2
}

data class LightTrailConfig(
    val players: Int = 2,
    val arena: LtArena = LtArena.MEDIUM,
    val rounds: Int = 3,
    val powerUps: Boolean = false,
    val relaxed: Boolean = false,
    val oneSwitch: Boolean = false
) {
    val size: Int get() = arena.cells + if (relaxed) LightTrailRules.RELAXED_EXTRA_CELLS else 0

    /** Milliseconds per step before the player's game-speed setting. */
    val stepMs: Long get() = if (relaxed) (LightTrailRules.BASE_STEP_MS * LightTrailRules.RELAXED_STEP_FACTOR).toLong() else LightTrailRules.BASE_STEP_MS

    fun sane(): LightTrailConfig = copy(
        players = players.coerceIn(LightTrailRules.MIN_PLAYERS, LightTrailRules.MAX_PLAYERS),
        rounds = if (rounds == 5) 5 else 3
    )

    companion object {
        fun fromExtras(players: Int, extras: Map<String, String>): LightTrailConfig = LightTrailConfig(
            players = players,
            arena = LtArena.fromId(extras["arena"]),
            rounds = extras["rounds"]?.toIntOrNull() ?: 3,
            powerUps = extras["powerups"] == "true",
            relaxed = extras["relaxed"] == "true",
            oneSwitch = extras["controls"] == "one"
        ).sane()
    }
}

class LtRider(val id: Int) {
    var x = 0
    var y = 0
    var dir = LtDir.UP
    var alive = true
    var cause: LtCause? = null
    var gapLeft = 0
    var boostLeft = 0
    var diedAtStep = -1
    var lastAutoAvoid = -1000
    val queue = ArrayDeque<LtTurn>()
}

data class LtItem(val x: Int, val y: Int, val kind: LtPower)

/** Something that happened during one step. */
sealed class LtEvent {
    data class Crash(val rider: Int, val x: Int, val y: Int, val cause: LtCause) : LtEvent()
    data class Pickup(val rider: Int, val kind: LtPower) : LtEvent()
    data class AutoAvoid(val rider: Int) : LtEvent()
}

class LightTrailEngine(val config: LightTrailConfig, private val random: Random = Random.Default) {
    val size: Int = config.size
    val cells = IntArray(size * size)
    val riders: List<LtRider> = List(config.players) { LtRider(it) }
    val items = ArrayList<LtItem>()
    var step = 0
        private set
    var over = false
        private set

    /** The surviving rider, or -1 for a draw (or while the round is still on). */
    var winner = -1
        private set

    private val maxSteps = size * size * 3

    init { reset() }

    fun cell(x: Int, y: Int): Int = cells[y * size + x]

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size

    fun aliveCount(): Int = riders.count { it.alive }

    /** Puts a fresh round on the board: every rider on its own edge, facing in. */
    fun reset() {
        cells.fill(0)
        items.clear()
        step = 0; over = false; winner = -1
        val c = size / 2
        val spots = listOf(
            Triple(c, size - 3, LtDir.UP),
            Triple(size - 1 - c, 2, LtDir.DOWN),
            Triple(2, c, LtDir.RIGHT),
            Triple(size - 3, size - 1 - c, LtDir.LEFT)
        )
        for (r in riders) {
            val (x, y, d) = spots[r.id]
            r.x = x; r.y = y; r.dir = d; r.alive = true; r.cause = null
            r.gapLeft = 0; r.boostLeft = 0; r.diedAtStep = -1; r.lastAutoAvoid = -1000
            r.queue.clear()
            cells[y * size + x] = r.id + 1
        }
    }

    /** Which side the one-button control would turn toward right now. */
    fun armedSide(): LtTurn = if ((step / LightTrailRules.ARM_FLIP_STEPS) % 2 == 0) LtTurn.RIGHT else LtTurn.LEFT

    /** Queues a turn for [rider]. Returns false when the rider is out, the round is over or its queue is full. */
    fun turn(rider: Int, cmd: LtTurn): Boolean {
        val r = riders.getOrNull(rider) ?: return false
        if (!r.alive || over) return false
        if (r.queue.size >= LightTrailRules.MAX_QUEUED_TURNS) return false
        r.queue.addLast(if (cmd == LtTurn.SWITCH) armedSide() else cmd)
        return true
    }

    private fun blockedAt(x: Int, y: Int, phased: Boolean): Boolean =
        !inBounds(x, y) || (!phased && cell(x, y) != 0)

    /** Free cells straight ahead from (x,y) going [d] (stops at 12; a cheap look-ahead for the Relaxed saver). */
    private fun runLength(x: Int, y: Int, d: LtDir): Int {
        var n = 0; var cx = x + d.dx; var cy = y + d.dy
        while (n < 12 && inBounds(cx, cy) && cell(cx, cy) == 0) { n++; cx += d.dx; cy += d.dy }
        return n
    }

    /** Advances the arena by one step and returns what happened. A finished round ignores further steps. */
    fun step(): List<LtEvent> {
        if (over) return emptyList()
        step++
        val events = ArrayList<LtEvent>()
        // 1. Turns (one per rider per step), then the Relaxed wall saver.
        for (r in riders) {
            if (!r.alive) continue
            val t = r.queue.removeFirstOrNull()
            if (t == LtTurn.LEFT) r.dir = r.dir.ccw() else if (t == LtTurn.RIGHT) r.dir = r.dir.cw()
            if (config.relaxed && t == null && r.gapLeft == 0 && step - r.lastAutoAvoid >= LightTrailRules.AUTO_AVOID_COOLDOWN &&
                blockedAt(r.x + r.dir.dx, r.y + r.dir.dy, false)
            ) {
                val l = r.dir.ccw(); val rt = r.dir.cw()
                val lFree = !blockedAt(r.x + l.dx, r.y + l.dy, false)
                val rFree = !blockedAt(r.x + rt.dx, r.y + rt.dy, false)
                if (lFree || rFree) {
                    r.dir = when {
                        lFree && !rFree -> l
                        rFree && !lFree -> rt
                        runLength(r.x, r.y, l) > runLength(r.x, r.y, rt) -> l
                        else -> rt
                    }
                    r.lastAutoAvoid = step
                    events += LtEvent.AutoAvoid(r.id)
                }
            }
        }
        // 2. Power-up spawning.
        if (config.powerUps && step % LightTrailRules.ITEM_EVERY_STEPS == 0 && items.size < LightTrailRules.MAX_ITEMS) spawnItem()
        // 3. Movement: everybody moves; boosted riders move a second time.
        val dead = ArrayList<Int>()
        val boosted = riders.filter { it.alive && it.boostLeft > 0 }.map { it.id }
        move(riders.filter { it.alive }.map { it.id }, events, dead)
        val second = boosted.filter { riders[it].alive }
        if (second.isNotEmpty()) move(second, events, dead)
        for (r in riders) if (r.alive && r.boostLeft > 0 && r.id in boosted) r.boostLeft--
        // 4. A crashed rider's walls vanish (after every crash of this step was judged against them).
        for (id in dead) {
            for (i in cells.indices) if (cells[i] == id + 1) cells[i] = 0
        }
        // 5. Round end.
        val alive = aliveCount()
        if (alive <= 1) {
            over = true
            winner = riders.firstOrNull { it.alive }?.id ?: -1
        } else if (step >= maxSteps) {
            over = true; winner = -1
            for (r in riders) if (r.alive) { r.alive = false; r.cause = LtCause.TIMEOUT; r.diedAtStep = step }
        }
        return events
    }

    private fun spawnItem() {
        repeat(30) {
            val x = 1 + random.nextInt(size - 2)
            val y = 1 + random.nextInt(size - 2)
            if (cell(x, y) == 0 && items.none { it.x == x && it.y == y } && riders.none { it.alive && it.x == x && it.y == y }) {
                items += LtItem(x, y, if (random.nextBoolean()) LtPower.GAP else LtPower.BOOST)
                return
            }
        }
    }

    private fun move(movers: List<Int>, events: MutableList<LtEvent>, dead: MutableList<Int>) {
        val tx = IntArray(movers.size); val ty = IntArray(movers.size)
        movers.forEachIndexed { i, id -> tx[i] = riders[id].x + riders[id].dir.dx; ty[i] = riders[id].y + riders[id].dir.dy }
        val crash = arrayOfNulls<LtCause>(movers.size)
        movers.forEachIndexed { i, id ->
            val r = riders[id]
            val phased = r.gapLeft > 0
            if (!inBounds(tx[i], ty[i])) crash[i] = LtCause.EDGE
            else if (!phased && cell(tx[i], ty[i]) != 0) crash[i] = if (cell(tx[i], ty[i]) == id + 1) LtCause.SELF else LtCause.WALL
            else if (!phased) {
                for (j in movers.indices) {
                    if (j != i && !riders[movers[j]].let { it.gapLeft > 0 } && tx[j] == tx[i] && ty[j] == ty[i]) crash[i] = LtCause.HEAD_ON
                }
            }
        }
        movers.forEachIndexed { i, id ->
            val r = riders[id]
            val c = crash[i]
            if (c != null) {
                r.alive = false; r.cause = c; r.diedAtStep = step
                events += LtEvent.Crash(id, r.x, r.y, c)
                dead += id
                return@forEachIndexed
            }
            val wasPhased = r.gapLeft > 0
            r.x = tx[i]; r.y = ty[i]
            if (wasPhased) {
                r.gapLeft--
            } else {
                cells[r.y * size + r.x] = id + 1
            }
            val k = items.indexOfFirst { it.x == r.x && it.y == r.y }
            if (k >= 0) {
                val item = items.removeAt(k)
                when (item.kind) {
                    LtPower.GAP -> r.gapLeft = LightTrailRules.GAP_MOVES
                    LtPower.BOOST -> r.boostLeft = LightTrailRules.BOOST_STEPS
                }
                events += LtEvent.Pickup(id, item.kind)
            }
        }
        // A rider whose gap just ran out must be standing on open floor.
        for ((i, id) in movers.withIndex()) {
            val r = riders[id]
            if (crash[i] == null && r.alive && r.gapLeft == 0 && cell(r.x, r.y) != 0 && cell(r.x, r.y) != id + 1) {
                r.alive = false; r.cause = LtCause.GAP_END; r.diedAtStep = step
                events += LtEvent.Crash(id, r.x, r.y, LtCause.GAP_END)
                dead += id
            }
        }
    }
}
