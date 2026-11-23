package com.gamesuite.games.snake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pure-engine tests: no clock, no Compose, no Android. Cells are `y * cols + x`. */
class SnakeEngineTest {

    private fun engine(
        grid: SnakeGridSize = SnakeGridSize.CLASSIC,
        walls: SnakeWalls = SnakeWalls.SOLID,
        speed: SnakeSpeed = SnakeSpeed.STEADY,
        seed: Int = 7
    ) = SnakeEngine(SnakeConfig(speed, grid, walls), Random(seed))

    private fun SnakeEngine.cell(x: Int, y: Int) = y * cols + x

    /** Parks the food far from where a test is moving the snake. */
    private fun SnakeEngine.foodOutOfTheWay() {
        val body = snake
        placeFoodAt((0 until cellCount).last { it !in body })
    }

    // ---- start position -------------------------------------------------------------------

    @Test
    fun startsAsThreeCellsHeadingRightFromTheMiddle() {
        val e = engine()
        assertEquals(3, e.length)
        assertEquals(SnakeDirection.RIGHT, e.direction)
        assertEquals(listOf(e.cell(8, 8), e.cell(7, 8), e.cell(6, 8)), e.snake)
        assertEquals(SnakeStatus.RUNNING, e.status)
        assertEquals(0, e.score)
    }

    @Test
    fun everyGridSizeStartsInBoundsWithFreeFood() {
        for (grid in SnakeGridSize.entries) {
            val e = engine(grid = grid)
            assertEquals(grid.size * grid.size, e.cellCount)
            assertTrue(e.snake.all { it in 0 until e.cellCount })
            assertEquals(e.snake.toSet().size, e.snake.size)
            assertTrue(e.food in 0 until e.cellCount)
            assertFalse(e.food in e.snake)
        }
    }

    @Test
    fun gridSizesAre12And16And20() {
        assertEquals(12, SnakeGridSize.SMALL.size)
        assertEquals(16, SnakeGridSize.CLASSIC.size)
        assertEquals(20, SnakeGridSize.BIG.size)
    }

    // ---- movement and growth ----------------------------------------------------------------

    @Test
    fun stepMovesTheHeadOneCellAndKeepsTheLength() {
        val e = engine()
        e.foodOutOfTheWay()
        assertEquals(SnakeStepResult.MOVED, e.step())
        assertEquals(e.cell(9, 8), e.head)
        assertEquals(3, e.length)
    }

    @Test
    fun theTailCellIsReleasedWhenMoving() {
        val e = engine()
        e.foodOutOfTheWay()
        val oldTail = e.snake.last()
        e.step()
        assertFalse(oldTail in e.snake)
    }

    @Test
    fun eatingGrowsByOneAndKeepsTheOldTail() {
        val e = engine()
        e.placeFoodAt(e.cell(9, 8))
        val oldTail = e.snake.last()
        assertEquals(SnakeStepResult.ATE, e.step())
        assertEquals(4, e.length)
        assertEquals(oldTail, e.snake.last())
        assertEquals(1, e.foods)
    }

    @Test
    fun eatingPlacesNewFoodOffTheSnake() {
        val e = engine()
        e.placeFoodAt(e.cell(9, 8))
        e.step()
        assertFalse(e.food in e.snake)
        assertNotEquals(e.head, e.food)
    }

    // ---- walls ----------------------------------------------------------------------------

    @Test
    fun solidWallEndsTheRun() {
        val e = engine()
        e.setSnake(listOf(e.cell(15, 5), e.cell(14, 5), e.cell(13, 5)), SnakeDirection.RIGHT)
        e.foodOutOfTheWay()
        assertEquals(SnakeStepResult.DIED, e.step())
        assertEquals(SnakeStatus.DEAD, e.status)
        assertEquals(SnakeDeathCause.WALL, e.deathCause)
    }

    @Test
    fun solidTopWallAlsoKills() {
        val e = engine()
        e.setSnake(listOf(e.cell(3, 0), e.cell(3, 1), e.cell(3, 2)), SnakeDirection.UP)
        e.foodOutOfTheWay()
        assertEquals(SnakeStepResult.DIED, e.step())
        assertEquals(SnakeDeathCause.WALL, e.deathCause)
    }

    @Test
    fun aDeadSnakeStaysWhereItDiedAndIgnoresFurtherSteps() {
        val e = engine()
        e.setSnake(listOf(e.cell(15, 5), e.cell(14, 5), e.cell(13, 5)), SnakeDirection.RIGHT)
        e.foodOutOfTheWay()
        e.step()
        val frozen = e.snake
        assertEquals(SnakeStepResult.NOOP, e.step())
        assertEquals(frozen, e.snake)
        assertFalse(e.queueDirection(SnakeDirection.UP))
    }

    @Test
    fun wrapAroundCrossesTheRightEdge() {
        val e = engine(walls = SnakeWalls.WRAP)
        e.setSnake(listOf(e.cell(15, 5), e.cell(14, 5), e.cell(13, 5)), SnakeDirection.RIGHT)
        e.foodOutOfTheWay()
        assertEquals(SnakeStepResult.MOVED, e.step())
        assertEquals(e.cell(0, 5), e.head)
        assertEquals(SnakeStatus.RUNNING, e.status)
    }

    @Test
    fun wrapAroundCrossesTheTopEdge() {
        val e = engine(walls = SnakeWalls.WRAP)
        e.setSnake(listOf(e.cell(3, 0), e.cell(3, 1), e.cell(3, 2)), SnakeDirection.UP)
        e.foodOutOfTheWay()
        e.step()
        assertEquals(e.cell(3, 15), e.head)
    }

    @Test
    fun wrapAroundCrossesTheLeftAndBottomEdgesOnEveryGrid() {
        for (grid in SnakeGridSize.entries) {
            val e = engine(grid = grid, walls = SnakeWalls.WRAP)
            val last = grid.size - 1
            e.setSnake(listOf(e.cell(0, 2), e.cell(1, 2), e.cell(2, 2)), SnakeDirection.LEFT)
            e.foodOutOfTheWay()
            e.step()
            assertEquals(e.cell(last, 2), e.head)
            e.setSnake(listOf(e.cell(4, last), e.cell(4, last - 1), e.cell(4, last - 2)), SnakeDirection.DOWN)
            e.foodOutOfTheWay()
            e.step()
            assertEquals(e.cell(4, 0), e.head)
        }
    }

    // ---- self collision ----------------------------------------------------------------------

    @Test
    fun runningIntoYourOwnBodyEndsTheRun() {
        val e = engine()
        // Length 5, heading right; a tight U-turn bites the body.
        e.setSnake((0 until 5).map { e.cell(8 - it, 8) }, SnakeDirection.RIGHT)
        e.foodOutOfTheWay()
        e.queueDirection(SnakeDirection.DOWN); assertEquals(SnakeStepResult.MOVED, e.step())
        e.queueDirection(SnakeDirection.LEFT); assertEquals(SnakeStepResult.MOVED, e.step())
        e.queueDirection(SnakeDirection.UP); assertEquals(SnakeStepResult.DIED, e.step())
        assertEquals(SnakeDeathCause.SELF, e.deathCause)
    }

    @Test
    fun theCellTheTailIsLeavingIsSafeToEnter() {
        val e = engine()
        // A 2x2 ring: the head moves into the cell the tail vacates on the same tick.
        e.setSnake(listOf(e.cell(5, 5), e.cell(5, 6), e.cell(6, 6), e.cell(6, 5)), SnakeDirection.RIGHT)
        e.foodOutOfTheWay()
        assertEquals(SnakeStepResult.MOVED, e.step())
        assertEquals(SnakeStatus.RUNNING, e.status)
        assertEquals(e.cell(6, 5), e.head)
    }

    // ---- direction queue ---------------------------------------------------------------------

    @Test
    fun aDirectReversalIsIgnored() {
        val e = engine()
        assertFalse(e.queueDirection(SnakeDirection.LEFT))
        assertTrue(e.pendingTurns.isEmpty())
        e.foodOutOfTheWay()
        assertEquals(SnakeStepResult.MOVED, e.step())
        assertEquals(SnakeDirection.RIGHT, e.direction)
    }

    @Test
    fun repeatingTheCurrentDirectionIsIgnored() {
        val e = engine()
        assertFalse(e.queueDirection(SnakeDirection.RIGHT))
        assertTrue(e.pendingTurns.isEmpty())
    }

    @Test
    fun aFastDoubleSwipeDoesNotReverseIntoItself() {
        val e = engine()
        e.foodOutOfTheWay()
        // Heading right: "up" then "left" in one tick interval are two legal turns...
        assertTrue(e.queueDirection(SnakeDirection.UP))
        assertTrue(e.queueDirection(SnakeDirection.LEFT))
        assertEquals(SnakeStepResult.MOVED, e.step()) // goes up
        assertEquals(SnakeStepResult.MOVED, e.step()) // then left, alongside its body
        assertEquals(SnakeStatus.RUNNING, e.status)
        assertEquals(SnakeDirection.LEFT, e.direction)
    }

    @Test
    fun aReversalQueuedBehindATurnIsIgnored() {
        val e = engine()
        assertTrue(e.queueDirection(SnakeDirection.UP))
        // DOWN would reverse the queued UP and drive the head into its neck on the next tick.
        assertFalse(e.queueDirection(SnakeDirection.DOWN))
        assertEquals(listOf(SnakeDirection.UP), e.pendingTurns)
    }

    @Test
    fun theQueueHoldsAtMostTwoTurns() {
        val e = engine()
        assertTrue(e.queueDirection(SnakeDirection.UP))
        assertTrue(e.queueDirection(SnakeDirection.LEFT))
        assertFalse(e.queueDirection(SnakeDirection.DOWN))
        assertEquals(2, e.pendingTurns.size)
    }

    @Test
    fun oneQueuedTurnIsAppliedPerTick() {
        val e = engine()
        e.foodOutOfTheWay()
        e.queueDirection(SnakeDirection.UP)
        e.queueDirection(SnakeDirection.LEFT)
        e.step()
        assertEquals(SnakeDirection.UP, e.direction)
        assertEquals(listOf(SnakeDirection.LEFT), e.pendingTurns)
    }

    // ---- food --------------------------------------------------------------------------------

    @Test
    fun foodNeverSpawnsOnTheSnake() {
        for (seed in 1..40) {
            val e = engine(grid = SnakeGridSize.SMALL, walls = SnakeWalls.WRAP, seed = seed)
            val dirs = SnakeDirection.entries
            val rng = Random(seed * 31)
            repeat(400) {
                e.queueDirection(dirs[rng.nextInt(dirs.size)])
                // Steer straight at the food now and then so the snake actually grows.
                if (it % 7 == 0 && e.status == SnakeStatus.RUNNING) {
                    val fx = e.food % e.cols
                    val fy = e.food / e.cols
                    val hx = e.head % e.cols
                    e.queueDirection(if (fx != hx) (if (fx > hx) SnakeDirection.RIGHT else SnakeDirection.LEFT)
                    else (if (fy > e.head / e.cols) SnakeDirection.DOWN else SnakeDirection.UP))
                }
                e.step()
                if (e.status == SnakeStatus.RUNNING) {
                    assertFalse("seed $seed: food on the snake", e.food in e.snake)
                    assertTrue(e.food in 0 until e.cellCount)
                }
            }
        }
    }

    @Test
    fun theSameSeedGivesTheSameFoodSequence() {
        fun foods(seed: Int): List<Int> {
            val e = engine(walls = SnakeWalls.WRAP, seed = seed)
            val out = mutableListOf(e.food)
            repeat(6) {
                val target = e.head + 1
                if (target / e.cols == e.head / e.cols && target !in e.snake) {
                    e.placeFoodAt(target)
                    e.step()
                    out.add(e.food)
                }
            }
            return out
        }
        assertEquals(foods(99), foods(99))
        assertNotEquals(foods(1), foods(2))
    }

    @Test
    fun foodGoesToTheOnlyFreeCell() {
        val e = engine(grid = SnakeGridSize.SMALL)
        val free = (0 until e.cellCount).first { it != e.food }
        e.setSnake((0 until e.cellCount).filter { it != free }, SnakeDirection.RIGHT)
        assertEquals(free, e.food)
    }

    @Test
    fun fillingTheWholeBoardClearsIt() {
        val e = engine(grid = SnakeGridSize.SMALL)
        val n = e.cols
        // A boustrophedon path over every cell; the snake covers all but the last cell.
        val path = (0 until n).flatMap { y ->
            val xs = if (y % 2 == 0) (0 until n) else (n - 1 downTo 0)
            xs.map { x -> y * n + x }
        }
        e.setSnake(path.dropLast(1).reversed(), SnakeDirection.LEFT) // row 11 runs right to left
        e.placeFoodAt(path.last())
        assertEquals(SnakeStepResult.CLEARED, e.step())
        assertEquals(SnakeStatus.CLEARED, e.status)
        assertEquals(-1, e.food)
        assertEquals(e.cellCount, e.length)
        assertNull(e.deathCause)
    }

    // ---- score and level ---------------------------------------------------------------------

    @Test
    fun levelRisesEveryFiveFoods() {
        assertEquals(1, SnakeEngine.levelForFoods(0))
        assertEquals(1, SnakeEngine.levelForFoods(4))
        assertEquals(2, SnakeEngine.levelForFoods(5))
        assertEquals(2, SnakeEngine.levelForFoods(9))
        assertEquals(3, SnakeEngine.levelForFoods(10))
    }

    @Test
    fun scoreIsFoodsTimesTheLevelMultiplier() {
        val e = engine(grid = SnakeGridSize.BIG, walls = SnakeWalls.WRAP)
        e.setSnake(listOf(e.cell(4, 10), e.cell(3, 10), e.cell(2, 10)), SnakeDirection.RIGHT)
        repeat(6) {
            e.placeFoodAt(e.head + 1)
            assertEquals(SnakeStepResult.ATE, e.step())
        }
        // Foods 1-5 are worth 1 each (level 1); the sixth is eaten at level 2 and is worth 2.
        assertEquals(6, e.foods)
        assertEquals(7, e.score)
        assertEquals(2, e.level)
    }

    @Test
    fun scoreStartsAtZeroAndOnlyRisesOnFood() {
        val e = engine()
        e.foodOutOfTheWay()
        e.step()
        e.step()
        assertEquals(0, e.score)
    }

    // ---- speed -------------------------------------------------------------------------------

    @Test
    fun speedChoicesAreOrderedSlowToFast() {
        assertTrue(SnakeSpeed.RELAXED.baseMillis > SnakeSpeed.STEADY.baseMillis)
        assertTrue(SnakeSpeed.STEADY.baseMillis > SnakeSpeed.QUICK.baseMillis)
        assertEquals(SnakeSpeed.RELAXED, SnakeSpeed.fromId("slow"))
        assertEquals(SnakeSpeed.STEADY, SnakeSpeed.fromId("normal"))
        assertEquals(SnakeSpeed.QUICK, SnakeSpeed.fromId("fast"))
        assertEquals(SnakeSpeed.STEADY, SnakeSpeed.fromId("warp"))
    }

    @Test
    fun theTickShrinksGentlyEveryLevelAndNeverBelowTheFloor() {
        for (speed in SnakeSpeed.entries) {
            assertEquals(speed.baseMillis, SnakeEngine.tickMillis(speed, 1))
            var previous = SnakeEngine.tickMillis(speed, 1)
            for (level in 2..60) {
                val tick = SnakeEngine.tickMillis(speed, level)
                assertTrue(tick <= previous)
                assertTrue(tick >= SnakeEngine.MIN_TICK_MILLIS)
                previous = tick
            }
            assertTrue(SnakeEngine.tickMillis(speed, 2) < speed.baseMillis)
            assertEquals(SnakeEngine.MIN_TICK_MILLIS, SnakeEngine.tickMillis(speed, 200))
        }
    }

    @Test
    fun theEnginesTickFollowsItsLevel() {
        val e = engine(grid = SnakeGridSize.BIG, walls = SnakeWalls.WRAP)
        e.setSnake(listOf(e.cell(4, 10), e.cell(3, 10), e.cell(2, 10)), SnakeDirection.RIGHT)
        assertEquals(SnakeSpeed.STEADY.baseMillis, e.tickMillis())
        repeat(5) {
            e.placeFoodAt(e.head + 1)
            e.step()
        }
        assertEquals(SnakeEngine.tickMillis(SnakeSpeed.STEADY, 2), e.tickMillis())
        assertTrue(e.tickMillis() < SnakeSpeed.STEADY.baseMillis)
    }

    // ---- config ------------------------------------------------------------------------------

    @Test
    fun configReadsTheHubExtrasAndFallsBackToDefaults() {
        val c = SnakeConfig.fromExtras(mapOf("speed" to "fast", "grid" to "big", "walls" to "wrap"))
        assertEquals(SnakeSpeed.QUICK, c.speed)
        assertEquals(SnakeGridSize.BIG, c.grid)
        assertTrue(c.wrap)
        assertEquals(20, c.cols)
        val d = SnakeConfig.fromExtras(emptyMap())
        assertEquals(SnakeConfig(), d)
        assertEquals(SnakeGridSize.CLASSIC, SnakeConfig.fromExtras(mapOf("grid" to "huge")).grid)
        assertFalse(d.wrap)
    }

    @Test
    fun everySetupHasItsOwnBestKey() {
        val keys = SnakeSpeed.entries.flatMap { sp ->
            SnakeGridSize.entries.flatMap { g -> SnakeWalls.entries.map { w -> SnakeConfig(sp, g, w).bestKey } }
        }
        // 3 speeds x 3 grids x 2 wall modes (walls has only SOLID and WRAP): every combination distinct.
        assertEquals(SnakeSpeed.entries.size * SnakeGridSize.entries.size * SnakeWalls.entries.size, keys.size)
        assertEquals(18, keys.toSet().size)
    }
}
