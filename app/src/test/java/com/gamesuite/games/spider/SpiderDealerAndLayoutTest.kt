package com.gamesuite.games.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Winnable deals (proven by construction and replay), the solver, hints and the table geometry. */
class SpiderDealerAndLayoutTest {

    // ---- winnable deals -----------------------------------------------------------------------------

    private fun replay(start: SpiderState, line: List<SpiderMove?>): SpiderState {
        var s = start
        for ((i, step) in line.withIndex()) {
            val out = (if (step == null) SpiderRules.applyDeal(s) else SpiderRules.applyMove(s, step))
            assertNotNull("step $i ($step) was illegal", out)
            s = out!!.state
            assertTrue("inconsistent after step $i", SpiderRules.isConsistent(s))
        }
        return s
    }

    @Test fun constructedDealsAreWonByTheirOwnLineUsingOnlyLegalMoves() {
        for (suits in listOf(1, 2, 4)) {
            var built = 0
            for (seed in 1..20) {
                val bias = if (suits == 1) 0.5 else if (suits == 2) 0.65 else 0.8
                val c = SpiderDealer.constructWithLine(Random(seed * 17 + suits), suits, bias) ?: continue
                built++
                assertTrue(SpiderRules.isConsistent(c.state))
                assertEquals(suits, c.state.suits)
                assertEquals(0, c.state.moves)
                val end = replay(c.state, c.line)
                assertTrue("suits=$suits seed=$seed: line did not win", end.won)
                assertEquals(SPIDER_RUNS_TO_WIN, end.completedRuns)
            }
            assertTrue("suits=$suits: no deal could be constructed in 20 tries", built >= 1)
        }
    }

    @Test fun theWinnableDealerAlwaysReturnsAProvenAndDeterministicDeal() {
        for (suits in listOf(1, 2, 4)) {
            for (seed in 1L..6L) {
                val a = SpiderDealer.winnable(seed, suits)
                val b = SpiderDealer.winnable(seed, suits)
                assertTrue("suits=$suits seed=$seed not proven", a.provenWinnable)
                assertEquals(a.state, b.state)
                assertTrue(SpiderRules.isConsistent(a.state))
                assertEquals(suits, a.state.suits)
                // Independent proof: the stated line wins with the real rules, move by move.
                assertTrue("suits=$suits seed=$seed: line is empty", a.line.isNotEmpty())
                assertTrue(replay(a.state, a.line).won)
            }
            assertNotEquals(SpiderDealer.winnable(1L, suits).state, SpiderDealer.winnable(2L, suits).state)
        }
    }

    @Test fun aWinnableDealStillLooksLikeAnOrdinaryOpening() {
        val s = SpiderDealer.winnable(3L, 2).state
        assertEquals(listOf(6, 6, 6, 6, 5, 5, 5, 5, 5, 5), s.columns.map { it.size })
        assertEquals(50, s.stock.size)
        assertEquals(listOf(5, 5, 5, 5, 4, 4, 4, 4, 4, 4), s.faceDown)
    }

    @Test fun plainDealsAreAPermutationOfTheDeck() {
        for (suits in listOf(1, 2, 4)) {
            val s = SpiderDealer.plain(Random(5), suits)
            assertTrue(SpiderRules.isConsistent(s))
        }
        assertNotEquals(SpiderDealer.plain(Random(1), 1), SpiderDealer.plain(Random(2), 1))
    }

    @Test fun seededRandomDependsOnSeedSuitsAndAttempt() {
        val a = SpiderDealer.seededRandom(5, 1).nextLong()
        assertEquals(a, SpiderDealer.seededRandom(5, 1).nextLong())
        assertNotEquals(a, SpiderDealer.seededRandom(6, 1).nextLong())
        assertNotEquals(a, SpiderDealer.seededRandom(5, 2).nextLong())
        assertNotEquals(a, SpiderDealer.seededRandom(5, 1, 1).nextLong())
    }

    // ---- solver ---------------------------------------------------------------------------------------

    @Test fun theSolverWinsOneSuitDealsAndReportsAFirstStep() {
        var solved = 0
        for (seed in 1..10) {
            val s = SpiderDealer.plain(Random(seed), 1)
            val r = SpiderSolver(30_000).solve(s)
            if (r.solved) {
                solved++
                assertEquals(SPIDER_RUNS_TO_WIN, r.bestRuns)
                assertNotNull(r.firstStep)
                // The first step it reports is legal.
                when (val step = r.firstStep!!) {
                    is SpiderHint.Move -> assertNotNull(SpiderRules.applyMove(s, step.move))
                    SpiderHint.Deal -> assertNotNull(SpiderRules.applyDeal(s))
                }
            }
        }
        assertTrue("solved only $solved/10 one-suit deals", solved >= 8)
    }

    @Test fun theSolverRespectsItsNodeBudget() {
        val s = SpiderDealer.plain(Random(4), 4)
        val r = SpiderSolver(500, restarts = 2).solve(s)
        assertTrue(r.nodes <= 520)
        assertFalse(r.solved && r.nodes > 500)
    }

    @Test fun anAlreadyWonTableIsSolvedWithNoSearch() {
        val won = SpiderState(1, List(10) { emptyList<Int>() }, List(10) { 0 }, emptyList(), (0 until 104).toList())
        val r = SpiderSolver(10).solve(won)
        assertTrue(r.solved)
        assertEquals(0, r.nodes)
    }

    @Test fun hintsFromTheEngineAreAlwaysLegalOnRandomPositions() {
        val rng = Random(77)
        repeat(30) { g ->
            var s = SpiderDealer.plain(Random(g), listOf(1, 2, 4)[g % 3])
            repeat(60) {
                val h = SpiderHints.suggest(s) ?: return@repeat
                val out = when (h) {
                    is SpiderHint.Move -> SpiderRules.applyMove(s, h.move)
                    SpiderHint.Deal -> SpiderRules.applyDeal(s)
                }
                assertNotNull("hint $h illegal", out)
                s = out!!.state
                // Throw in a random legal move too, so the positions are not only hint-shaped.
                val legal = SpiderRules.legalMoves(s)
                if (legal.isNotEmpty() && rng.nextInt(3) == 0) s = SpiderRules.applyMove(s, legal.random(rng))!!.state
            }
        }
    }

    // ---- fuzz of random legal play ---------------------------------------------------------------------

    @Test fun randomLegalPlayKeepsEveryCardExactlyOnceAndScoreAndMovesMonotone() {
        val rng = Random(2025)
        for (g in 0 until 60) {
            val suits = listOf(1, 2, 4)[g % 3]
            var s = SpiderDealer.plain(Random(g), suits)
            var lastMoves = 0
            repeat(500) {
                val legal = SpiderRules.legalMoves(s)
                val dealOk = SpiderRules.canDeal(s)
                if (legal.isEmpty() && !dealOk) return@repeat
                val out = if (dealOk && (legal.isEmpty() || rng.nextInt(8) == 0)) SpiderRules.applyDeal(s)!! else SpiderRules.applyMove(s, legal.random(rng))!!
                s = out.state
                assertTrue("game $g inconsistent", SpiderRules.isConsistent(s))
                assertEquals(lastMoves + 1, s.moves)
                lastMoves = s.moves
                assertTrue(s.score >= 0)
                assertTrue(s.completedRuns <= SPIDER_RUNS_TO_WIN)
            }
        }
    }

    @Test fun everyCompletedRunIsAFullKingToAceInOneSuitAfterRandomPlay() {
        val rng = Random(9)
        var completedSomething = false
        for (g in 0 until 40) {
            var s = SpiderDealer.plain(Random(500 + g), 1)
            repeat(400) {
                val m = SpiderMoveRanking.candidates(s).firstOrNull()?.move
                val out = if (m != null) SpiderRules.applyMove(s, m) else if (SpiderRules.canDeal(s)) SpiderRules.applyDeal(s) else null
                if (out != null) s = out.state
                else {
                    val legal = SpiderRules.legalMoves(s)
                    if (legal.isNotEmpty()) s = SpiderRules.applyMove(s, legal.random(rng))!!.state
                }
            }
            if (s.completedRuns > 0) completedSomething = true
            for (r in 0 until s.completedRuns) {
                val run = s.completed.subList(r * 13, r * 13 + 13)
                assertEquals((13 downTo 1).toList(), run.map { SpiderRules.rankOf(it) })
                assertEquals(1, run.map { SpiderRules.suitOf(it, s.suits) }.toSet().size)
            }
        }
        assertTrue(completedSomething)
    }

    // ---- layout -------------------------------------------------------------------------------------------

    @Test fun lanesFitAPhoneWidthWithoutScrolling() {
        val m = SpiderLayout.metrics(360f, 520f)
        assertFalse(m.scrollsHorizontally)
        assertTrue(m.boardW <= 360f + 0.01f)
        assertEquals(36f, m.lane, 0.01f)
        assertTrue(m.cardW < m.lane)
    }

    @Test fun narrowerThanTheMinimumLaneScrollsInsteadOfOverflowingTheLanes() {
        val m = SpiderLayout.metrics(312f, 400f)
        assertTrue(m.scrollsHorizontally)
        assertEquals(SpiderLayout.MIN_LANE, m.lane, 0.01f)
        assertEquals(SpiderLayout.MIN_LANE * 10, m.boardW, 0.01f)
    }

    @Test fun wideScreensCapTheCardSizeAndKeepTheAspect() {
        val m = SpiderLayout.metrics(1200f, 700f)
        assertEquals(SpiderLayout.MAX_LANE, m.lane, 0.01f)
        assertEquals(m.cardW * SpiderLayout.CARD_ASPECT, m.cardH, 0.01f)
    }

    @Test fun everyCardHasExactlyOnePlacementInsideTheBoard() {
        val s = SpiderDealer.plain(Random(1), 4)
        val m = SpiderLayout.metrics(360f, 600f, SpiderLayout.tallestMinimum(s, SpiderLayout.metrics(360f, 600f).cardH))
        val p = SpiderLayout.placements(s, m)
        assertEquals(SPIDER_DECK_SIZE, p.size)
        for ((id, place) in p) {
            assertTrue("card $id left of board", place.x >= -0.01f)
            assertTrue("card $id right of board", place.x + m.cardW <= m.boardW + 0.5f)
            assertTrue("card $id above board", place.y >= -0.01f)
            assertTrue("card $id below board", place.y + m.cardH <= m.boardH + 0.5f)
        }
    }

    @Test fun faceDownCardsOverlapMoreThanFaceUpAndAColumnKeepsItsOrder() {
        val s = SpiderDealer.plain(Random(2), 2)
        val m = SpiderLayout.metrics(360f, 700f)
        val ys = (0 until s.columns[0].size).map { SpiderLayout.cardY(s, 0, it, m) }
        assertEquals(ys.sorted(), ys)
        assertEquals(ys.distinct().size, ys.size)
        val down = ys[1] - ys[0]
        assertTrue(down <= m.upStep + 0.01f)
    }

    @Test fun aFreshDealOnATallPhoneKeepsRoomyNaturalSteps() {
        // QA on a 411 x ~640dp board: a face-up card must show enough of its neighbour to be a real tap target,
        // and the fresh deal must not need to compress at all.
        val s = SpiderDealer.plain(Random(4), 1)
        val m = SpiderLayout.metrics(411f, 640f)
        assertTrue("upStep ${m.upStep} too tight", m.upStep >= m.cardH * 0.4f)
        assertTrue("downStep ${m.downStep} too tight", m.downStep >= m.cardH * 0.18f)
        for (c in 0 until SPIDER_COLUMNS) {
            val (d, u) = SpiderLayout.columnSteps(s, c, m)
            assertEquals(m.downStep, d, 0.001f)
            assertEquals(m.upStep, u, 0.001f)
        }
    }

    @Test fun aTallColumnCompressesToFitTheAvailableHeight() {
        val base = SpiderDealer.plain(Random(3), 1)
        // Pile 20 face-up cards onto column 0 (the table need not be a legal position for geometry).
        val extra = base.columns[1] + base.columns[2] + base.columns[3] + base.columns[4]
        val tall = base.copy(columns = base.columns.toMutableList().also { it[0] = it[0] + extra.take(20) })
        val m = SpiderLayout.metrics(360f, 420f, SpiderLayout.tallestMinimum(tall, SpiderLayout.metrics(360f, 420f).cardH))
        val last = tall.columns[0].lastIndex
        val bottom = SpiderLayout.cardY(tall, 0, last, m) + m.cardH
        assertTrue("column overflows: $bottom > ${m.columnsH}", bottom <= m.columnsH + 0.5f)
    }

    @Test fun laneAtMapsHorizontalPositionsAndClamps() {
        val m = SpiderLayout.metrics(360f, 500f)
        assertEquals(0, SpiderLayout.laneAt(-50f, m))
        assertEquals(0, SpiderLayout.laneAt(10f, m))
        assertEquals(1, SpiderLayout.laneAt(m.lane + 1f, m))
        assertEquals(9, SpiderLayout.laneAt(10_000f, m))
        for (c in 0 until 10) assertEquals(c, SpiderLayout.laneAt(SpiderLayout.columnX(c, m) + m.cardW / 2f, m))
    }

    @Test fun stockAndCompletedRunsRestInTheBottomStrip() {
        val m = SpiderLayout.metrics(360f, 500f)
        val s = SpiderDealer.plain(Random(6), 1)
        val p = SpiderLayout.placements(s, m)
        val stockCards = s.stock
        assertTrue(stockCards.all { p.getValue(it).y >= m.stripY })
        // The next row to deal sits right-most and above the others (higher z).
        val nextRowZ = p.getValue(s.stock.last()).z
        val lastRowZ = p.getValue(s.stock.first()).z
        assertTrue(nextRowZ > lastRowZ)
    }
}
