package com.gamesuite.games.slideclimb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideClimbLayoutTest {
    private val default = SlideClimbLayout.DEFAULT

    // ---- geometry ----

    @Test fun theTrackSnakesFromTheBottomLeftToTheTopLeft() {
        assertEquals(0, SlideClimbBoard.column(1)); assertEquals(0, SlideClimbBoard.row(1))
        assertEquals(9, SlideClimbBoard.column(10)); assertEquals(0, SlideClimbBoard.row(10))
        assertEquals(9, SlideClimbBoard.column(11)); assertEquals(1, SlideClimbBoard.row(11))
        assertEquals(0, SlideClimbBoard.column(20)); assertEquals(1, SlideClimbBoard.row(20))
        assertEquals(0, SlideClimbBoard.column(21)); assertEquals(2, SlideClimbBoard.row(21))
        assertEquals(0, SlideClimbBoard.column(100)); assertEquals(9, SlideClimbBoard.row(100))
    }

    @Test fun everySquareHasItsOwnCellAndSquareAtIsTheInverse() {
        val cells = HashSet<Pair<Int, Int>>()
        for (sq in 1..SlideClimbBoard.SQUARES) {
            val c = SlideClimbBoard.column(sq)
            val r = SlideClimbBoard.row(sq)
            assertTrue(c in 0..9 && r in 0..9)
            assertTrue("two squares share a cell", cells.add(c to r))
            assertEquals(sq, SlideClimbBoard.squareAt(c, r))
        }
        assertEquals(100, cells.size)
    }

    @Test fun neighbouringSquaresAreNeighbouringCells() {
        for (sq in 1 until SlideClimbBoard.SQUARES) {
            val dc = kotlin.math.abs(SlideClimbBoard.column(sq + 1) - SlideClimbBoard.column(sq))
            val dr = kotlin.math.abs(SlideClimbBoard.row(sq + 1) - SlideClimbBoard.row(sq))
            assertEquals("square $sq to ${sq + 1} must be one step", 1, dc + dr)
        }
    }

    // ---- the default layout ----

    @Test fun theDefaultLayoutPassesEveryCheck() {
        assertEquals(emptyList<String>(), SlideClimbLayout.problems(default))
        assertTrue(SlideClimbLayout.isValid(default))
    }

    @Test fun theDefaultLayoutHasEightRopesAndEightSlides() {
        assertEquals(8, default.ropes.size)
        assertEquals(8, default.slides.size)
        assertEquals(16, default.jumps.size)
        default.ropes.forEach { (foot, top) -> assertTrue(top > foot) }
        default.slides.forEach { (head, tail) -> assertTrue(tail < head) }
    }

    @Test fun noJumpEndsWhereAnotherBegins_soNothingChainsOrLoops() {
        assertTrue(default.jumps.values.none { it in default.jumps.keys })
        for (sq in 0..SlideClimbBoard.FINISH) {
            val once = default.destination(sq)
            assertEquals("a jump from $sq chains", once, default.destination(once))
        }
    }

    @Test fun jumpsStayOnTheBoardAndOffTheFirstAndLastSquares() {
        for ((start, end) in default.jumps) {
            assertTrue(start in 2..99)
            assertTrue(end in 1..99)
            assertTrue(kotlin.math.abs(end - start) in SlideClimbLayout.MIN_SPAN..SlideClimbLayout.MAX_SPAN)
        }
    }

    @Test fun climbingAndSlidingAreBalanced() {
        val rise = default.ropes.entries.sumOf { it.value - it.key }
        val drop = default.slides.entries.sumOf { it.key - it.value }
        assertTrue("rise $rise vs drop $drop", kotlin.math.abs(rise - drop) <= SlideClimbLayout.MAX_IMBALANCE)
    }

    @Test fun noRowHoldsMoreThanThreeJumps() {
        val perRow = default.jumps.keys.groupingBy { SlideClimbBoard.row(it) }.eachCount()
        assertTrue(perRow.values.all { it <= SlideClimbLayout.MAX_PER_ROW })
    }

    @Test fun everyRestingSquareIsReachableInBothFinishingModes() {
        for (exact in listOf(false, true)) {
            val seen = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            seen += 0; queue.addLast(0)
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                if (at == SlideClimbBoard.FINISH) continue
                for (roll in 1..6) {
                    val to = SlideClimbRules.step(at, roll, default, exact).to
                    if (seen.add(to)) queue.addLast(to)
                }
            }
            for (sq in 0..SlideClimbBoard.FINISH) {
                if (sq !in default.jumps) assertTrue("square $sq unreachable (exact=$exact)", sq in seen)
            }
            // a piece never rests on the foot of a jump
            assertTrue(seen.none { it in default.jumps.keys })
        }
    }

    @Test fun squareOneHundredIsReachableFromEveryRestingSquare() {
        for (exact in listOf(false, true)) {
            // walk forward with the best case roll until it ends: never more than 100 steps from any square
            for (from in 0 until SlideClimbBoard.FINISH) {
                if (from in default.jumps) continue
                var at = from
                var turns = 0
                val random = kotlin.random.Random(from * 2L + if (exact) 1 else 0)
                while (at != SlideClimbBoard.FINISH) {
                    at = SlideClimbRules.step(at, random.nextInt(1, 7), default, exact).to
                    assertTrue("a piece from $from never finishes", ++turns < 5000)
                }
            }
        }
    }

    // ---- the checks themselves ----

    @Test fun anEmptyLayoutIsRejected() {
        assertFalse(SlideClimbLayout.isValid(SlideClimbLayout(emptyMap())))
    }

    @Test fun aChainOfJumpsIsRejected() {
        val chained = SlideClimbLayout(default.jumps + (16 to 30))
        assertTrue(SlideClimbLayout.problems(chained).any { "both ends a jump and starts one" in it })
    }

    @Test fun aLoopIsRejected() {
        val loop = SlideClimbLayout(default.jumps.filterKeys { it != 5 && it != 18 } + (10 to 30) + (30 to 10))
        assertTrue(SlideClimbLayout.problems(loop).any { "both ends a jump and starts one" in it })
    }

    @Test fun anUnbalancedLayoutIsRejected() {
        val ropes = listOf(2, 3, 4, 12, 13, 14, 22, 23).associateWith { it + 40 }
        val slides = listOf(98, 95, 92, 88, 85, 82, 78, 75).associateWith { it - 8 }
        val problems = SlideClimbLayout.problems(SlideClimbLayout(ropes + slides))
        assertTrue(problems.toString(), problems.any { it.startsWith("unbalanced") })
    }

    @Test fun aWallOfSixSlidesIsRejected() {
        val slides = (80..87).associateWith { it - 10 }
        val ropes = listOf(3, 10, 20, 30, 40, 50, 60, 65).associateWith { it + 12 }
        val problems = SlideClimbLayout.problems(SlideClimbLayout(ropes + slides))
        assertTrue(problems.toString(), problems.any { it.startsWith("six slides in a row") })
    }

    @Test fun aJumpOffTheBoardIsRejected() {
        val bad = SlideClimbLayout(default.jumps + (96 to 105))
        assertFalse(SlideClimbLayout.isValid(bad))
        assertFalse(SlideClimbLayout.isValid(SlideClimbLayout(default.jumps + (100 to 70))))
    }

    // ---- seeded layouts ----

    @Test fun everySeededLayoutIsValid() {
        for (seed in 0L until 100L) {
            val layout = SlideClimbLayout.generate(seed)
            assertEquals("seed $seed", emptyList<String>(), SlideClimbLayout.problems(layout))
        }
    }

    @Test fun aSeedAlwaysMakesTheSameLayout() {
        for (seed in listOf(0L, 1L, 42L, 99999L)) {
            assertEquals(SlideClimbLayout.generate(seed), SlideClimbLayout.generate(seed))
        }
    }

    @Test fun differentSeedsMakeDifferentLayouts() {
        val distinct = (0L until 100L).map { SlideClimbLayout.generate(it).jumps }.toSet()
        assertTrue("only ${distinct.size} distinct layouts in 100 seeds", distinct.size >= 20)
    }
}
