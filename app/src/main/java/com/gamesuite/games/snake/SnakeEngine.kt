package com.gamesuite.games.snake

import kotlin.math.pow
import kotlin.random.Random

/** The four ways the snake can head. [dx]/[dy] are in grid cells (y grows downward). */
enum class SnakeDirection(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    val opposite: SnakeDirection
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }
}

/** Hub "speed" choice. [baseMillis] is the time between steps at level 1. */
enum class SnakeSpeed(val id: String, val baseMillis: Long) {
    RELAXED("slow", 230L),
    STEADY("normal", 165L),
    QUICK("fast", 115L);

    companion object {
        fun fromId(id: String?): SnakeSpeed = entries.firstOrNull { it.id == id } ?: STEADY
    }
}

/** Hub "grid" choice: a square field of [size] x [size] cells. */
enum class SnakeGridSize(val id: String, val size: Int) {
    SMALL("small", 12),
    CLASSIC("classic", 16),
    BIG("big", 20);

    companion object {
        fun fromId(id: String?): SnakeGridSize = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** Hub "walls" choice: solid walls end the run, wrap-around joins opposite edges. */
enum class SnakeWalls(val id: String) {
    SOLID("solid"),
    WRAP("wrap");

    companion object {
        fun fromId(id: String?): SnakeWalls = entries.firstOrNull { it.id == id } ?: SOLID
    }
}

/** Everything that shapes one run. */
data class SnakeConfig(
    val speed: SnakeSpeed = SnakeSpeed.STEADY,
    val grid: SnakeGridSize = SnakeGridSize.CLASSIC,
    val walls: SnakeWalls = SnakeWalls.SOLID
) {
    val cols: Int get() = grid.size
    val rows: Int get() = grid.size
    val wrap: Boolean get() = walls == SnakeWalls.WRAP

    /** The key a personal best is filed under: each combination is its own leaderboard. */
    val bestKey: String get() = "${grid.id}_${walls.id}_${speed.id}"

    companion object {
        /** Reads the Hub's resolved choices (`LaunchOptions.extras`); anything missing or unknown is the default. */
        fun fromExtras(extras: Map<String, String>): SnakeConfig = SnakeConfig(
            speed = SnakeSpeed.fromId(extras[KEY_SPEED]),
            grid = SnakeGridSize.fromId(extras[KEY_GRID]),
            walls = SnakeWalls.fromId(extras[KEY_WALLS])
        )

        const val KEY_SPEED = "speed"
        const val KEY_GRID = "grid"
        const val KEY_WALLS = "walls"
    }
}

enum class SnakeStatus { RUNNING, DEAD, CLEARED }

enum class SnakeDeathCause { WALL, SELF }

enum class SnakeStepResult { NOOP, MOVED, ATE, DIED, CLEARED }

/**
 * The pure, tick-driven Snake simulation: no clock, no Compose, no Android. The caller decides when
 * a tick happens ([step]); everything else (direction queue, growth, collisions, food placement,
 * scoring, levels) is deterministic given the injected [random].
 *
 * Cells are row-major indexes (`y * cols + x`), y = 0 is the top row.
 *
 * - **Direction queue**: [queueDirection] stores up to [MAX_QUEUED] turns, each validated against the
 *   LAST queued direction (or the current heading when the queue is empty). A fast double swipe
 *   "up" then "left" while heading right is two legal turns applied on consecutive ticks; a swipe
 *   "left" while heading right (a reversal into the neck) is ignored, and so is a reversal queued
 *   behind a turn.
 * - **Collisions**: leaving the field with solid walls, or entering a body cell, ends the run. The
 *   cell the tail is just leaving is free to enter (unless the snake is eating on that step).
 * - **Scoring**: eating gives the current level as points (foods eaten x level multiplier). The
 *   level is `1 + foods / FOODS_PER_LEVEL`, so every fifth food raises both the multiplier and the
 *   speed ([tickMillis]).
 * - **Food** is always placed on a uniformly random FREE cell. Filling the whole board clears it
 *   ([SnakeStatus.CLEARED]).
 */
class SnakeEngine(val config: SnakeConfig, private val random: Random) {

    val cols: Int get() = config.cols
    val rows: Int get() = config.rows
    val cellCount: Int get() = cols * rows

    private val body = ArrayDeque<Int>()
    private val queue = ArrayList<SnakeDirection>()

    /** The heading used by the most recent step (or the starting heading). */
    var direction: SnakeDirection = SnakeDirection.RIGHT
        private set

    /** Cell index of the food, or -1 once the board is full. */
    var food: Int = -1
        private set

    var foods: Int = 0
        private set

    var score: Int = 0
        private set

    var status: SnakeStatus = SnakeStatus.RUNNING
        private set

    var deathCause: SnakeDeathCause? = null
        private set

    /** Head first. A copy; safe to keep. */
    val snake: List<Int> get() = body.toList()

    val head: Int get() = body.first()

    val length: Int get() = body.size

    val level: Int get() = levelForFoods(foods)

    /** Turns waiting to be applied, oldest first. */
    val pendingTurns: List<SnakeDirection> get() = queue.toList()

    init {
        val x = cols / 2
        val y = rows / 2
        for (i in 0 until INITIAL_LENGTH) body.addLast(y * cols + (x - i))
        placeFood()
    }

    /**
     * Queues a turn. Returns true if it was accepted. Ignored when the run is over, when it repeats
     * or reverses the previous (queued) heading, or when [MAX_QUEUED] turns are already waiting.
     */
    fun queueDirection(dir: SnakeDirection): Boolean {
        if (status != SnakeStatus.RUNNING) return false
        val reference = queue.lastOrNull() ?: direction
        if (dir == reference || dir == reference.opposite) return false
        if (queue.size >= MAX_QUEUED) return false
        queue.add(dir)
        return true
    }

    /** Advances the simulation by exactly one tick. */
    fun step(): SnakeStepResult {
        if (status != SnakeStatus.RUNNING) return SnakeStepResult.NOOP
        if (queue.isNotEmpty()) direction = queue.removeAt(0)

        val h = body.first()
        var nx = h % cols + direction.dx
        var ny = h / cols + direction.dy
        if (config.wrap) {
            nx = (nx + cols) % cols
            ny = (ny + rows) % rows
        } else if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
            return die(SnakeDeathCause.WALL)
        }
        val next = ny * cols + nx
        val eating = next == food
        // The tail cell is vacated this step unless the snake is growing.
        val tail = body.last()
        if (body.contains(next) && !(next == tail && !eating)) return die(SnakeDeathCause.SELF)

        body.addFirst(next)
        if (!eating) {
            body.removeLast()
            return SnakeStepResult.MOVED
        }
        score += level // the level BEFORE this food counts
        foods += 1
        if (body.size >= cellCount) {
            food = -1
            status = SnakeStatus.CLEARED
            return SnakeStepResult.CLEARED
        }
        placeFood()
        return SnakeStepResult.ATE
    }

    /** Milliseconds between steps at the current level. */
    fun tickMillis(): Long = tickMillis(config.speed, level)

    private fun die(cause: SnakeDeathCause): SnakeStepResult {
        status = SnakeStatus.DEAD
        deathCause = cause
        queue.clear()
        return SnakeStepResult.DIED
    }

    private fun placeFood() {
        val free = cellCount - body.size
        if (free <= 0) {
            food = -1
            return
        }
        val occupied = BooleanArray(cellCount)
        for (c in body) occupied[c] = true
        var pick = random.nextInt(free)
        for (c in 0 until cellCount) {
            if (occupied[c]) continue
            if (pick == 0) {
                food = c
                return
            }
            pick--
        }
    }

    /** Test hook: moves the food to a specific free cell. */
    internal fun placeFoodAt(cell: Int) {
        require(cell in 0 until cellCount && cell !in body) { "food must sit on a free cell" }
        food = cell
    }

    /** Test hook: replaces the snake (head first) and its heading, clearing queued turns. */
    internal fun setSnake(cells: List<Int>, heading: SnakeDirection) {
        require(cells.isNotEmpty() && cells.toSet().size == cells.size) { "snake cells must be unique" }
        body.clear()
        body.addAll(cells)
        direction = heading
        queue.clear()
        if (food in body) placeFood()
    }

    companion object {
        const val INITIAL_LENGTH = 3
        const val MAX_QUEUED = 2
        const val FOODS_PER_LEVEL = 5

        /** Each level shortens the step by this factor ("a gentle speed-up"). */
        const val SPEED_UP_FACTOR = 0.93

        /** The fastest the game ever steps, so it stays playable. */
        const val MIN_TICK_MILLIS = 65L

        fun levelForFoods(foods: Int): Int = 1 + foods.coerceAtLeast(0) / FOODS_PER_LEVEL

        fun tickMillis(speed: SnakeSpeed, level: Int): Long {
            val scaled = speed.baseMillis * SPEED_UP_FACTOR.pow((level - 1).coerceAtLeast(0))
            return scaled.toLong().coerceAtLeast(MIN_TICK_MILLIS)
        }
    }
}
