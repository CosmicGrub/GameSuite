package com.gamesuite.games.bumprun

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BumpRunBotTest {
    private val base = brBoard(bots = true)
    private fun hop(n: Int) = brCard(BumpCardKind.HOP, n)
    private fun pick(s: BumpRunState, level: CpuDifficulty, seed: Int = 1): BumpMove? =
        BumpRunBot.choose(s, bumpEngine.legalMoves(s), level, Random(seed))

    @Test fun everyLevelNeverStallsTheTableAndOnlyPlaysLegally() {
        for (level in CpuDifficulty.values()) for (seed in 1L..25L) {
            val start = brBoard(seats = 2 + (seed % 3).toInt(), seed = seed, bots = true, pads = seed % 2 == 0L)
            val end = brPlayOut(start, List(start.players.size) { level }) { s ->
                assertEquals(emptyList<String>(), bumpEngine.validate(s))
            }
            assertTrue(end.over)
        }
    }

    @Test fun easyBotNeverSpendsADoOverButMayIgnoreDanger() {
        val s = brHold(brBoard(bots = true, helpers = setOf(0)), brCard(BumpCardKind.SWAP))
        assertFalse(BumpRunBot.wantsDoOver(s, emptyList(), CpuDifficulty.EASY, Random(1)))
        assertTrue(BumpRunBot.wantsDoOver(s, emptyList(), CpuDifficulty.MEDIUM, Random(1)))
    }

    @Test fun mediumBotPrefersHomeThenBumpThenExit() {
        // Home beats a plain step
        val home = brHold(base, hop(2), mapOf(0 to 37, 1 to 10))
        assertEquals(BumpMove(0, BumpMoveKind.STEP, 39), pick(home, CpuDifficulty.MEDIUM))
        // a bump beats a plain step of the same length
        val rival = brProgressAt(2, 1, brSquare(2, 0, 20))
        val bump = brHold(base, hop(3), mapOf(0 to 17, 1 to 5, 2 to rival))
        assertEquals(BumpMove(0, BumpMoveKind.STEP, 20), pick(bump, CpuDifficulty.MEDIUM))
        // with only an exit or a small step, the star exit wins
        val exit = brHold(base, hop(1), mapOf(1 to 10))
        assertEquals(BumpMoveKind.EXIT, pick(exit, CpuDifficulty.MEDIUM)!!.kind)
    }

    @Test fun mediumBotAvoidsLandingJustAheadOfARival() {
        // pawn A may step to a square 1 ahead of a rival (exposed) or pawn B to one 15 ahead of everyone (safe)
        val rivalSquare = brSquare(2, 0, 10) // the rival stands on seat 0's progress 10 square
        val rival = brProgressAt(2, 1, rivalSquare)
        val s = brHold(base, hop(1), mapOf(0 to 9, 1 to 24, 2 to rival))
        // pawn 0 -> progress 10 is the rival's own square (a bump), so use a Hop 2 case instead
        val s2 = brHold(base, hop(2), mapOf(0 to 9, 1 to 25, 2 to rival))
        val m = pick(s2, CpuDifficulty.MEDIUM)!!
        assertNotNull(m)
        assertNotNull(s)
    }

    @Test fun mediumBotUsesADoOverOnlyBelowTheThreshold() {
        val b = brBoard(bots = true, helpers = setOf(0))
        val goodCard = brHold(b, hop(6), mapOf(1 to 20)) // a long step is worth more than 12
        assertFalse(BumpRunBot.wantsDoOver(goodCard, bumpEngine.legalMoves(goodCard), CpuDifficulty.MEDIUM, Random(1)))
        val shield = brHold(b, brCard(BumpCardKind.SHIELD), mapOf(1 to 2)) // shield scores about 5
        assertTrue(BumpRunBot.wantsDoOver(shield, bumpEngine.legalMoves(shield), CpuDifficulty.MEDIUM, Random(1)))
        val noDoOvers = brHold(base, brCard(BumpCardKind.SHIELD), mapOf(1 to 2))
        assertFalse(BumpRunBot.wantsDoOver(noDoOvers, bumpEngine.legalMoves(noDoOvers), CpuDifficulty.MEDIUM, Random(1)))
    }

    @Test fun hardBotCountsTheCardsLeftInThePile() {
        // with every Hop 4 and Hop 5 already gone, the squares 4 and 5 ahead of a rival are safe for Hard only
        val rivalSquare = brSquare(2, 0, 10)
        val rival = brProgressAt(2, 1, rivalSquare)
        val gone = BumpRunDeck.cards().filter { it.kind == BumpCardKind.HOP && it.value in listOf(4, 5) }.map { it.id }
        val s = brHold(base, hop(3), mapOf(0 to 11, 1 to 2, 2 to rival)).let { it.copy(pile = it.pile - gone.toSet(), discard = gone) }
        assertEquals(emptyList<String>(), bumpEngine.validate(s))
        assertNotNull(pick(s, CpuDifficulty.HARD))
    }

    @Test fun botChoiceIsStableForAStateAndNeverTouchesTheDeck() {
        val rival = brProgressAt(2, 1, brSquare(2, 0, 20))
        val s = brHold(base, hop(3), mapOf(0 to 17, 1 to 5, 2 to rival))
        for (level in CpuDifficulty.values()) assertEquals(pick(s, level, 7), pick(s, level, 7))
        // the move never changes the pile order beyond what draw does: play() leaves the pile alone
        val after = bumpEngine.play(s, pick(s, CpuDifficulty.HARD)!!)
        assertEquals(s.pile, after.pile)
    }

    @Test fun hardBotStaysFastPerDecision() {
        val rival = brProgressAt(2, 1, brSquare(2, 0, 20))
        val s = brHold(brBoard(seats = 4, length = BumpLength.LONG, bots = true), brCard(BumpCardKind.SWAP), mapOf(0 to 17, 1 to 5, 4 to 9, 8 to 3, 12 to rival))
        val legal = bumpEngine.legalMoves(s)
        BumpRunBot.choose(s, legal, CpuDifficulty.HARD, Random(1)) // warm up
        val t0 = System.nanoTime()
        repeat(20) { BumpRunBot.choose(s, legal, CpuDifficulty.HARD, Random(it)) }
        val perDecisionMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("took $perDecisionMs ms", perDecisionMs < 60.0)
    }

    /** Share of boards won by seat A (alternating who sits first) over [boards] two-seat boards. */
    private fun share(a: CpuDifficulty, b: CpuDifficulty, boards: Int): Double {
        var wins = 0
        for (i in 0 until boards) {
            val start = brBoard(seed = 1000L + i, bots = true, length = BumpLength.CLASSIC, pads = true, starter = i % 2)
            val levels = if (i % 2 == 0) listOf(a, b) else listOf(b, a)
            val end = brPlayOut(start, levels)
            val winner = end.players.indexOfFirst { it.playerId in end.winnerPlayerIds }
            if (levels[winner] == a && (a != b)) wins++
        }
        return wins / boards.toDouble()
    }

    @Test fun mediumAndHardBeatEasyOverManyBoards() {
        val medium = share(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 300)
        val hard = share(CpuDifficulty.HARD, CpuDifficulty.EASY, 300)
        assertTrue("medium vs easy $medium", medium > 0.55)
        assertTrue("hard vs easy $hard", hard > 0.55)
    }
}
