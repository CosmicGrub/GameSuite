package com.gamesuite.games.backgammon

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The CPU's evaluation and its three levels. */
class BackgammonBotTest {

    // --- Evaluation ----------------------------------------------------------------------------

    @Test
    fun `evaluation is zero-sum between the two perspectives`() {
        for ((board, _) in bgRandomPositions(seed = 3, plies = 60, every = 5)) {
            val a = BackgammonEvaluator.evaluate(board, 0, 1)
            val b = BackgammonEvaluator.evaluate(board, 1, 1)
            assertEquals(0.0, a + b, 1e-9)
        }
    }

    @Test
    fun `the opening position is dead even`() {
        assertEquals(0.0, BackgammonEvaluator.evaluate(BackgammonBoard.initial(), 0, 0), 1e-9)
    }

    @Test
    fun `a smaller pip count is better once the armies have passed each other`() {
        val ahead = bgPos(white = mapOf(2 to 1), black = mapOf(20 to 1), whiteOff = 14, blackOff = 14)
        val behind = bgPos(white = mapOf(3 to 1), black = mapOf(20 to 1), whiteOff = 14, blackOff = 14)
        assertTrue(!BackgammonEvaluator.hasContact(ahead))
        assertTrue(BackgammonEvaluator.evaluate(ahead, 0, 1) > BackgammonEvaluator.evaluate(behind, 0, 1))
    }

    @Test
    fun `a made point beats two exposed blots with the same pip count`() {
        val black = mapOf(1 to 2)
        val made = bgPos(white = mapOf(8 to 2), black = black, whiteOff = 13, blackOff = 13)
        val split = bgPos(white = mapOf(9 to 1, 7 to 1), black = black, whiteOff = 13, blackOff = 13)
        assertEquals(made.pips(0), split.pips(0))
        assertTrue(BackgammonEvaluator.evaluate(made, 0, 1) > BackgammonEvaluator.evaluate(split, 0, 1))
    }

    @Test
    fun `a checker on the bar is worth less than the same checker on the board`() {
        val black = mapOf(1 to 2)
        val onBoard = bgPos(white = mapOf(8 to 2, 20 to 1), black = black, whiteOff = 12, blackOff = 13)
        val onBar = bgPos(white = mapOf(8 to 2), whiteBar = 1, black = black, whiteOff = 12, blackOff = 13)
        assertTrue(BackgammonEvaluator.evaluate(onBoard, 0, 1) > BackgammonEvaluator.evaluate(onBar, 0, 1))
    }

    @Test
    fun `a gammon win is worth more than a normal win`() {
        val normal = bgPos(black = mapOf(20 to 14), whiteOff = 15, blackOff = 1)
        val gammon = bgPos(black = mapOf(20 to 15), whiteOff = 15)
        val v1 = BackgammonEvaluator.evaluate(normal, 0, 1)
        val v2 = BackgammonEvaluator.evaluate(gammon, 0, 1)
        assertTrue(v1 > 50_000.0)
        assertTrue(v2 > v1)
        assertEquals(-v2, BackgammonEvaluator.evaluate(gammon, 1, 1), 1e-9)
    }

    // --- Legality at every level ----------------------------------------------------------------

    private fun assertBotIsLegal(difficulty: CpuDifficulty) {
        var checked = 0
        for (seed in 1..3) {
            val rnd = Random(seed * 101)
            for ((board, side) in bgRandomPositions(seed = seed, plies = 150, every = 3)) {
                val remaining = bgDice(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                val moves = BackgammonBot.chooseTurn(board, side, remaining, difficulty, Random(seed), budgetMs = 0)
                if (BackgammonRules.generateTurns(board, side, remaining).isEmpty()) {
                    assertTrue(moves.isEmpty())
                } else {
                    assertTrue("$difficulty chose an illegal turn $moves for $remaining", bgReplayIsLegal(board, side, remaining, moves))
                }
                checked++
            }
        }
        assertTrue(checked >= 30)
    }

    @Test
    fun `EASY always plays a legal complete turn`() = assertBotIsLegal(CpuDifficulty.EASY)

    @Test
    fun `MEDIUM always plays a legal complete turn`() = assertBotIsLegal(CpuDifficulty.MEDIUM)

    @Test
    fun `HARD always plays a legal complete turn`() = assertBotIsLegal(CpuDifficulty.HARD)

    @Test
    fun `every level passes when nothing can be played`() {
        val stuck = bgPos(white = mapOf(8 to 1), black = mapOf(5 to 2, 4 to 2), whiteOff = 14, blackOff = 11)
        for (d in CpuDifficulty.values()) {
            assertTrue(BackgammonBot.chooseTurn(stuck, 0, listOf(3, 4), d, Random(1)).isEmpty())
        }
    }

    @Test
    fun `EASY is random while MEDIUM is repeatable`() {
        val opening = BackgammonBoard.initial()
        val easyChoices = (1..40).map { BackgammonBot.chooseTurn(opening, 0, listOf(3, 1), CpuDifficulty.EASY, Random(it)).toString() }.toSet()
        assertTrue("EASY should vary with the seed", easyChoices.size > 1)
        val mediumA = BackgammonBot.chooseTurn(opening, 0, listOf(3, 1), CpuDifficulty.MEDIUM, Random(1))
        val mediumB = BackgammonBot.chooseTurn(opening, 0, listOf(3, 1), CpuDifficulty.MEDIUM, Random(1))
        assertEquals(mediumA, mediumB)
    }

    // --- Tactics -------------------------------------------------------------------------------

    /** White to play 3-1 with a Black blot on its 5 point: 8 -> 5 and 6 -> 5 hits it and makes the point. */
    private val hitFixture = bgPos(
        white = mapOf(8 to 2, 6 to 2),
        black = mapOf(1 to 2, 5 to 1),
        whiteOff = 11, blackOff = 12
    )

    @Test
    fun `MEDIUM hits a blot when it can`() {
        val moves = BackgammonBot.chooseTurn(hitFixture, 0, listOf(3, 1), CpuDifficulty.MEDIUM, Random(1))
        val result = moves.fold(hitFixture) { b, m -> b.apply(0, m) }
        assertEquals("the blot should be on the bar", 1, result.bar(1))
    }

    @Test
    fun `HARD hits a blot when it can`() {
        val moves = BackgammonBot.chooseTurn(hitFixture, 0, listOf(3, 1), CpuDifficulty.HARD, Random(1))
        val result = moves.fold(hitFixture) { b, m -> b.apply(0, m) }
        assertEquals("the blot should be on the bar", 1, result.bar(1))
    }

    /** White to play 6-2: 7 -> 5 makes the 5 point; the 6 must be played from 20 (7 -> 1 is blocked). */
    private val pointFixture = bgPos(
        white = mapOf(7 to 1, 5 to 1, 20 to 2),
        black = mapOf(1 to 2),
        whiteOff = 11, blackOff = 13
    )

    @Test
    fun `MEDIUM makes a point instead of leaving blots in the firing line`() {
        val moves = BackgammonBot.chooseTurn(pointFixture, 0, listOf(6, 2), CpuDifficulty.MEDIUM, Random(1))
        val result = moves.fold(pointFixture) { b, m -> b.apply(0, m) }
        assertEquals(2, result.count(0, bgIx(5)))
    }

    @Test
    fun `HARD makes a point instead of leaving blots in the firing line`() {
        val moves = BackgammonBot.chooseTurn(pointFixture, 0, listOf(6, 2), CpuDifficulty.HARD, Random(1))
        val result = moves.fold(pointFixture) { b, m -> b.apply(0, m) }
        assertEquals(2, result.count(0, bgIx(5)))
    }

    @Test
    fun `HARD bears off a checker whenever it can in a pure race`() {
        val race = bgPos(white = mapOf(6 to 1, 5 to 1, 2 to 1), black = mapOf(20 to 3), whiteOff = 12, blackOff = 12)
        val moves = BackgammonBot.chooseTurn(race, 0, listOf(6, 5), CpuDifficulty.HARD, Random(1))
        val result = moves.fold(race) { b, m -> b.apply(0, m) }
        assertEquals(14, result.off(0))
    }

    @Test
    fun `the hint is the first move of a legal turn`() {
        val first = BackgammonBot.hintMove(hitFixture, 0, listOf(3, 1), Random(1))
        assertTrue(first in BackgammonRules.legalMoves(hitFixture, 0, listOf(3, 1)))
    }

    @Test
    fun `a search with no time left still returns a complete legal turn`() {
        var now = 0L
        // The clock jumps a second on every read, so the budget is gone after the first candidate.
        val moves = BackgammonBot.chooseTurn(
            BackgammonBoard.initial(), 0, bgDice(6, 4), CpuDifficulty.HARD, Random(1),
            nanoClock = { now += 1_000_000_000L; now }, budgetMs = 1
        )
        assertTrue(bgReplayIsLegal(BackgammonBoard.initial(), 0, bgDice(6, 4), moves))
    }
}
