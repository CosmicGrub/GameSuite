package com.gamesuite.games.chinesecheckers

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChineseCheckersRulesTest {
    private fun cell(q: Int, r: Int) = CCBoard.indexOf(q, r).also { assertTrue("($q,$r) is not on the star", it >= 0) }
    private fun empty() = IntArray(CCBoard.CELLS) { -1 }

    // ---- geometry ----------------------------------------------------------------------

    @Test fun starHas121HolesSixTrianglesOfTenAndAHexagonOf61() {
        assertEquals(121, CCBoard.CELLS)
        for (corner in 0..5) {
            assertEquals(10, CCBoard.cornerCells(corner, 4).size)
            assertEquals(6, CCBoard.cornerCells(corner, 3).size)
            assertEquals(1, CCBoard.cornerCells(corner, 1).size)
        }
        assertEquals(61, (0 until CCBoard.CELLS).count { CCBoard.cornerOf(it) == -1 })
    }

    @Test fun cornersAreDisjointAndOppositesAreThreeApart() {
        val all = (0..5).flatMap { CCBoard.cornerCells(it, 4).toList() }
        assertEquals(60, all.toSet().size)
        for (c in 0..5) assertEquals(c, CCBoard.opposite(CCBoard.opposite(c)))
        for (c in 0..5) {
            val tip = CCBoard.tip(c)
            val far = CCBoard.tip(CCBoard.opposite(c))
            assertEquals("tips of opposite corners are 16 holes apart", 16, CCBoard.distance(tip, far))
        }
    }

    @Test fun everyHoleHasAtLeastTwoNeighboursAndInteriorHolesHaveSix() {
        for (c in 0 until CCBoard.CELLS) {
            val n = (0..5).count { CCBoard.neighbors[c * 6 + it] >= 0 }
            assertTrue(n >= 2)
            if (CCBoard.cornerOf(c) == -1 && (0..5).all { CCBoard.neighbors[c * 6 + it] >= 0 }) assertEquals(6, n)
        }
        // the hexagon's centre
        assertEquals(6, (0..5).count { CCBoard.neighbors[cell(0, 0) * 6 + it] >= 0 })
    }

    @Test fun jumpsLandExactlyTwoStepsAlongTheNeighbourDirection() {
        for (c in 0 until CCBoard.CELLS) for (d in 0..5) {
            val over = CCBoard.neighbors[c * 6 + d]
            val land = CCBoard.jumps[c * 6 + d]
            if (land >= 0) {
                assertTrue(over >= 0)
                assertEquals(land, CCBoard.neighbors[over * 6 + d])
            }
        }
    }

    @Test fun layoutsPutSeatsInDistinctCornersAndOpposingPairsFaceEachOther() {
        for (seats in listOf(2, 3, 4, 5, 6)) {
            val l = CCBoard.layout(seats)
            assertEquals(seats, l.size)
            assertEquals(seats, l.toSet().size)
        }
        assertEquals(listOf(0, 3), CCBoard.layout(2))
        assertEquals(listOf(0, 2, 4), CCBoard.layout(3))
        assertEquals(listOf(1, 2, 4, 5), CCBoard.layout(4))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), CCBoard.layout(6))
        // 4-seat table: two opposite pairs
        val l4 = CCBoard.layout(4)
        assertTrue(CCBoard.opposite(l4[0]) == l4[2] || CCBoard.opposite(l4[0]) == l4[3])
    }

    @Test fun unsupportedSeatCountsAreRejected() {
        for (bad in listOf(0, 1, 7)) {
            var threw = false
            try { CCBoard.layout(bad) } catch (_: IllegalArgumentException) { threw = true }
            assertTrue(threw)
        }
    }

    @Test fun initialPositionHasTenOrSixPiecesPerSeatInTheirOwnCorner() {
        for (seats in listOf(2, 3, 4, 6)) for (pieces in listOf(10, 6)) {
            val homes = CCBoard.layout(seats)
            val owners = CCRules.initialOwners(homes, pieces)
            for (seat in 0 until seats) {
                assertEquals(pieces, owners.count { it == seat })
                assertTrue((0 until CCBoard.CELLS).filter { owners[it] == seat }.all { CCBoard.cornerOf(it) == homes[seat] })
            }
            assertEquals(seats * pieces, owners.count { it >= 0 })
        }
    }

    // ---- moves --------------------------------------------------------------------------

    @Test fun aLonePieceStepsToEveryEmptyNeighbour() {
        val o = empty()
        val c = cell(0, 0)
        o[c] = 0
        assertEquals(6, CCRules.destinations(o, c).size)
    }

    @Test fun aPieceHopsOverAnyPieceIntoTheEmptyHoleBehind() {
        val o = empty()
        o[cell(0, 0)] = 0
        o[cell(1, 0)] = 1 // an opponent
        val d = CCRules.destinations(o, cell(0, 0))
        assertTrue(cell(2, 0) in d)
        assertFalse("cannot step onto an occupied hole", cell(1, 0) in d)
        o[cell(1, 0)] = 0 // its own piece is hoppable too
        assertTrue(cell(2, 0) in CCRules.destinations(o, cell(0, 0)))
    }

    @Test fun hopsChainAndEveryLandingIsAFinishingHole() {
        val o = empty()
        o[cell(0, 0)] = 0
        o[cell(1, 0)] = 1; o[cell(3, 0)] = 1 // two stepping stones in a row
        val d = CCRules.destinations(o, cell(0, 0))
        assertTrue(cell(2, 0) in d)
        assertTrue("second hop of the chain", cell(4, 0) in d)
    }

    @Test fun noHopOverAnEmptyHoleOntoAnOccupiedHoleOrOffTheBoard() {
        val o = empty()
        o[cell(0, 0)] = 0
        // nothing adjacent: no jump destinations beyond the six steps
        assertEquals(6, CCRules.destinations(o, cell(0, 0)).size)
        o[cell(1, 0)] = 1; o[cell(2, 0)] = 1 // landing occupied
        assertFalse(cell(2, 0) in CCRules.destinations(o, cell(0, 0)))
        assertFalse(CCRules.destinations(o, cell(0, 0)).any { it == cell(2, 0) })
        // a piece at a tip cannot hop off the star
        val tip = CCBoard.tip(0)
        val o2 = empty()
        o2[tip] = 0
        for (d in 0..5) {
            val n = CCBoard.neighbors[tip * 6 + d]
            if (n >= 0) o2[n] = 1
        }
        CCRules.destinations(o2, tip).forEach { assertTrue(it in 0 until CCBoard.CELLS) }
    }

    @Test fun aPieceCannotHopOverItsOwnVacatedStartingHole() {
        // a loop of three stones around the start: the piece must never "land" back on its origin
        val o = empty()
        val start = cell(0, 0)
        o[start] = 0
        o[cell(1, 0)] = 1; o[cell(1, -1)] = 1; o[cell(0, -1)] = 1
        assertFalse(start in CCRules.destinations(o, start))
    }

    @Test fun movesAreReversibleAndEveryDestinationHasAValidRoute() {
        val rnd = Random(7)
        repeat(60) {
            val o = empty()
            repeat(14) { o[rnd.nextInt(CCBoard.CELLS)] = rnd.nextInt(2) }
            val pieces = (0 until CCBoard.CELLS).filter { o[it] == 0 }
            for (p in pieces) for (d in CCRules.destinations(o, p)) {
                val after = CCRules.apply(o, CCMove(p, d))
                assertEquals(-1, after[p]); assertEquals(0, after[d])
                assertTrue("reverse move exists", p in CCRules.destinations(after, d))
                val route = CCRules.path(after.copyOf().also { it[d] = -1 }, p, d)
                assertEquals(p, route.first()); assertEquals(d, route.last())
                for (i in 0 until route.size - 1) {
                    val a = route[i]; val b = route[i + 1]
                    val step = (0..5).any { CCBoard.neighbors[a * 6 + it] == b }
                    val hop = (0..5).any { CCBoard.jumps[a * 6 + it] == b && o[CCBoard.neighbors[a * 6 + it]] != -1 }
                    assertTrue("leg $a->$b must be a step or a hop", step || hop)
                }
            }
        }
    }

    @Test fun movesNeverChangeThePieceCount() {
        val homes = CCBoard.layout(4)
        var o = CCRules.initialOwners(homes, 10)
        val rnd = Random(3)
        repeat(300) {
            val seat = it % 4
            val moves = CCRules.movesFor(o, seat)
            if (moves.isEmpty()) return@repeat
            o = CCRules.apply(o, moves[rnd.nextInt(moves.size)])
            for (s in 0 until 4) assertEquals(10, o.count { v -> v == s })
        }
    }

    // ---- winning ------------------------------------------------------------------------

    @Test fun winRequiresEveryPieceInTheOppositeCorner() {
        val homes = CCBoard.layout(2)
        val o = empty()
        val goal = CCRules.goalCells(homes[0], 10)
        goal.take(9).forEach { o[it] = 0 }
        assertFalse(CCRules.hasWon(o, 0, homes[0], 10))
        o[goal[9]] = 0
        assertTrue(CCRules.hasWon(o, 0, homes[0], 10))
        assertFalse(CCRules.hasWon(o, 1, homes[1], 10))
    }

    @Test fun shortGameGoalIsTheSixHoleTriangle() {
        val goal = CCRules.goalCells(0, 6)
        assertEquals(6, goal.size)
        val o = empty()
        goal.forEach { o[it] = 0 }
        assertTrue(CCRules.hasWon(o, 0, 0, 6))
    }

    @Test fun remainingDistanceShrinksAsPiecesAdvance() {
        val homes = CCBoard.layout(2)
        val o = CCRules.initialOwners(homes, 10)
        val before = CCRules.remaining(o, 0, homes[0])
        val goal = CCRules.goalCells(homes[0], 10)
        val o2 = empty(); goal.forEach { o2[it] = 0 }
        assertTrue(CCRules.remaining(o2, 0, homes[0]) < before)
    }

    // ---- the computer ------------------------------------------------------------------

    @Test fun everyLevelPicksALegalMoveOnEveryTableSize() {
        for (seats in listOf(2, 3, 4, 6)) {
            val homes = CCBoard.layout(seats)
            val o = CCRules.initialOwners(homes, 10)
            for (d in CpuDifficulty.values()) for (seat in 0 until seats) {
                val m = CCBot.choose(o, seat, homes[seat], d, Random(seat * 31 + seats))
                assertTrue("$d seat $seat", m != null && m.to in CCRules.destinations(o, m.from) && o[m.from] == seat)
            }
        }
    }

    @Test fun botReturnsNullWhenBoxedIn() {
        val o = empty()
        val tip = CCBoard.tip(0)
        o[tip] = 0
        for (d in 0..5) {
            val n = CCBoard.neighbors[tip * 6 + d]
            if (n >= 0) {
                o[n] = 1
                val behind = CCBoard.jumps[tip * 6 + d]
                if (behind >= 0) o[behind] = 1
            }
        }
        assertEquals(null, CCBot.choose(o, 0, 0, CpuDifficulty.HARD, Random(1)))
    }

    private fun playBots(seats: Int, levels: List<CpuDifficulty>, seed: Int, pieces: Int = 10): Triple<Int?, Int, IntArray> {
        val homes = CCBoard.layout(seats)
        var o = CCRules.initialOwners(homes, pieces)
        val rnd = Random(seed)
        val prev = arrayOfNulls<CCMove>(seats)
        var turn = 0
        val limit = ChineseCheckersGame.TURNS_PER_SEAT * seats
        while (turn < limit) {
            val seat = turn % seats
            val m = CCBot.choose(o, seat, homes[seat], levels[seat], rnd, prev[seat])
            if (m != null) {
                assertTrue(m.to in CCRules.destinations(o, m.from))
                o = CCRules.apply(o, m)
                prev[seat] = m
                if (CCRules.hasWon(o, seat, homes[seat], pieces)) return Triple(seat, turn, o)
            }
            turn++
        }
        return Triple(null, turn, o)
    }

    @Test fun computerOnlyGamesFinishWithAWinnerAtEveryLevel() {
        for (d in CpuDifficulty.values()) {
            val (winner, turns, _) = playBots(2, listOf(d, d), seed = 11)
            assertTrue("$d: no winner after $turns moves", winner != null)
        }
    }

    @Test fun threeFourAndSixSeatComputerGamesAlsoFinish() {
        for (seats in listOf(3, 4, 6)) {
            val (winner, turns, _) = playBots(seats, List(seats) { CpuDifficulty.MEDIUM }, seed = seats)
            assertTrue("$seats seats: no winner after $turns moves", winner != null)
        }
    }

    @Test fun hardBeatsEasyOverSeveralGamesFromBothSides() {
        var hardWins = 0
        var games = 0
        for (seed in 1..4) {
            val a = playBots(2, listOf(CpuDifficulty.HARD, CpuDifficulty.EASY), seed)
            val b = playBots(2, listOf(CpuDifficulty.EASY, CpuDifficulty.HARD), seed + 100)
            games += 2
            if (a.first == 0) hardWins++
            if (b.first == 1) hardWins++
        }
        assertTrue("Hard won only $hardWins of $games", hardWins >= games * 3 / 4)
    }

    @Test fun differentLevelsDoNotPlayTheSameOpening() {
        val homes = CCBoard.layout(2)
        val o = CCRules.initialOwners(homes, 10)
        val picks = CpuDifficulty.values().map { d ->
            (1..30).map { CCBot.choose(o, 0, homes[0], d, Random(it)) }.toSet().size
        }
        // Easy is the most varied, Medium/Hard are far steadier.
        assertTrue(picks[CpuDifficulty.EASY.ordinal] > picks[CpuDifficulty.HARD.ordinal])
        assertNotEquals(0, picks.sum())
    }
}
