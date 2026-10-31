package com.gamesuite.games.holdem

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HoldemEquityBotTest {

    // ---- equity --------------------------------------------------------------------------------

    @Test fun preflopEquityMatchesTheKnownFigures() {
        val rnd = Random(1)
        // pocket aces and the worst starting hand against one random hand
        assertEquals(0.852, HoldemEquity.estimate(T.cards("As Ah"), emptyList(), 1, 30_000, rnd), 0.015)
        assertEquals(0.346, HoldemEquity.estimate(T.cards("7h 2d"), emptyList(), 1, 30_000, rnd), 0.015)
        assertEquals(0.670, HoldemEquity.estimate(T.cards("As Ks"), emptyList(), 1, 30_000, rnd), 0.02)
    }

    @Test fun moreOpponentsMeansLessEquity() {
        val rnd = Random(2)
        val one = HoldemEquity.estimate(T.cards("As Ah"), emptyList(), 1, 8_000, rnd)
        val three = HoldemEquity.estimate(T.cards("As Ah"), emptyList(), 3, 8_000, rnd)
        val five = HoldemEquity.estimate(T.cards("As Ah"), emptyList(), 5, 8_000, rnd)
        assertTrue(one > three && three > five)
        assertEquals(0.49, five, 0.03) // the well-known figure for aces against five random hands
    }

    @Test fun theNutsOnTheRiverWinEverything() {
        val e = HoldemEquity.estimate(T.cards("Ah Kh"), T.cards("Qh Jh Th 2c 3d"), 2, 200, Random(3))
        assertEquals(1.0, e, 0.0)
    }

    @Test fun aDeadHandOnTheRiverHasLittleEquity() {
        val e = HoldemEquity.estimate(T.cards("2c 3d"), T.cards("Ac Kd 9h 8s 4c"), 1, 4_000, Random(4))
        assertTrue("equity $e", e < 0.3)
    }

    @Test fun equityKnowsAboutTheBoardAndKnownCards() {
        val rnd = Random(5)
        // top set on the flop is a big favourite against one random hand
        val set = HoldemEquity.estimate(T.cards("9h 9d"), T.cards("9c 4s 2d"), 1, 8_000, rnd)
        assertTrue(set > 0.9)
        // a flush draw alone is far from a lock
        val draw = HoldemEquity.estimate(T.cards("Ah 5h"), T.cards("Kh 9h 2c"), 1, 8_000, rnd)
        assertTrue(draw in 0.5..0.8)
    }

    @Test fun chenScoresMatchThePublishedFormula() {
        assertEquals(20.0, HoldemEquity.chen(T.cards("As Ah")), 0.0)
        assertEquals(16.0, HoldemEquity.chen(T.cards("Ks Kh")), 0.0)
        assertEquals(12.0, HoldemEquity.chen(T.cards("As Ks")), 0.0)
        assertEquals(10.0, HoldemEquity.chen(T.cards("As Kh")), 0.0)
        assertEquals(9.0, HoldemEquity.chen(T.cards("Js Ts")), 0.0)
        assertEquals(5.0, HoldemEquity.chen(T.cards("2s 2h")), 0.0)
        assertEquals(-1.0, HoldemEquity.chen(T.cards("7h 2d")), 0.0)
    }

    @Test fun madeStrengthOrdersHandsSensibly() {
        val board = T.cards("Kh 7d 2c")
        val set = HoldemEquity.madeStrength(T.cards("Kd Kc"), board)
        val topPair = HoldemEquity.madeStrength(T.cards("Ks Qc"), board)
        val air = HoldemEquity.madeStrength(T.cards("5s 4c"), board)
        assertTrue(set > topPair && topPair > air)
        assertTrue(HoldemEquity.madeStrength(T.cards("Ah Qh"), T.cards("Kh 7h 2c")) > HoldemEquity.madeStrength(T.cards("As Qd"), T.cards("Kh 7h 2c")))
    }

    // ---- the computer players ------------------------------------------------------------------

    private fun options(toCall: Int, chips: Int = 1000, currentBet: Int = toCall, canRaise: Boolean = true) = HoldemOptions(
        canCheck = toCall == 0, canCall = toCall > 0, callAmount = minOf(toCall, chips), canRaise = canRaise,
        minRaiseTo = minOf(currentBet + 20, chips), maxRaiseTo = chips, canAllIn = chips > 0, allInTo = chips, toCall = toCall
    )

    private fun view(
        hole: String, board: String = "", pot: Int = 30, toCall: Int = 20, currentBet: Int = toCall,
        opponents: Int = 2, position: Double = 0.5, chips: Int = 1000, street: HoldemStreet = if (board.isBlank()) HoldemStreet.PREFLOP else HoldemStreet.FLOP
    ) = BotView(
        hole = T.cards(hole), board = T.cards(board), street = street, pot = pot, chips = chips, bet = 0,
        currentBet = currentBet, options = options(toCall, chips, currentBet), bigBlind = 20, opponents = opponents,
        position = position, limpers = 0
    )

    private fun decisions(v: BotView, level: CpuDifficulty, equity: Double?, n: Int = 200, style: BotStyle = BotStyle.BALANCED): List<HoldemAction> =
        List(n) { HoldemBot.decide(v, level, style, Random(it), equity) }

    @Test fun hardRaisesPremiumsAndFoldsTrashBeforeTheFlop() {
        val aces = decisions(view("As Ad", opponents = 3, position = 0.8), CpuDifficulty.HARD, 0.73)
        assertTrue(aces.all { it.kind == HoldemActionKind.RAISE_TO || it.kind == HoldemActionKind.ALL_IN })
        val trash = decisions(view("7h 2d", opponents = 3, position = 0.1), CpuDifficulty.HARD, 0.2)
        assertTrue(trash.all { it.kind == HoldemActionKind.FOLD })
    }

    @Test fun nobodyCallsABigRaiseWithTrash() {
        val v = view("7h 2d", pot = 280, toCall = 200, currentBet = 220)
        for (level in CpuDifficulty.values()) {
            val eq = if (level == CpuDifficulty.EASY) null else 0.25
            assertTrue("$level", decisions(v, level, eq, 100).count { it.kind == HoldemActionKind.FOLD } >= 80)
        }
    }

    @Test fun mediumAndHardCallWhenThePriceIsRight() {
        // a strong draw getting 5-to-1: equity 0.35 against pot odds of 1/6
        val v = view("Ah 5h", board = "Kh 9h 2c", pot = 100, toCall = 20, opponents = 1)
        for (level in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            val calls = decisions(v, level, 0.5, 100)
            assertTrue("$level folded a profitable call", calls.none { it.kind == HoldemActionKind.FOLD })
        }
    }

    @Test fun hardFoldsWhenTheOddsAreBad() {
        // equity 0.2 needing 0.45
        val v = view("9s 8s", board = "Kh 2d 2c", pot = 100, toCall = 80, opponents = 1)
        val d = decisions(v, CpuDifficulty.HARD, 0.2, 100)
        assertTrue(d.count { it.kind == HoldemActionKind.FOLD } >= 95)
    }

    @Test fun hardBetsItsStrongHandsAndBluffsSometimes() {
        val nuts = view("Ah Kh", board = "Qh Jh Th", pot = 100, toCall = 0, currentBet = 0, opponents = 1)
        val value = decisions(nuts, CpuDifficulty.HARD, 1.0, 200)
        assertTrue(value.count { it.kind != HoldemActionKind.CHECK } >= 150)
        val air = view("7s 2c", board = "Kh Jd 9c", pot = 100, toCall = 0, currentBet = 0, opponents = 1, position = 1.0)
        val bluffs = decisions(air, CpuDifficulty.HARD, 0.08, 400).count { it.kind != HoldemActionKind.CHECK }
        assertTrue("bluff frequency $bluffs/400", bluffs in 20..140)
        // Medium bluffs far less
        val mediumBluffs = decisions(air, CpuDifficulty.MEDIUM, 0.08, 400).count { it.kind != HoldemActionKind.CHECK }
        assertTrue(mediumBluffs < bluffs)
    }

    @Test fun easyMakesMistakesBothWays() {
        // facing a bet with a monster: it still sometimes folds
        val monster = view("8h 8d", board = "8c 8s 2d", pot = 100, toCall = 50, opponents = 1)
        val d1 = decisions(monster, CpuDifficulty.EASY, null, 600)
        assertTrue(d1.any { it.kind == HoldemActionKind.FOLD })
        assertTrue(d1.count { it.kind == HoldemActionKind.CALL || it.kind == HoldemActionKind.RAISE_TO || it.kind == HoldemActionKind.ALL_IN } > 450)
        // facing a bet with nothing: it sometimes pays anyway
        val air = view("7s 2c", board = "Kh Jd 9c", pot = 100, toCall = 50, opponents = 1)
        val d2 = decisions(air, CpuDifficulty.EASY, null, 600)
        assertTrue(d2.any { it.kind == HoldemActionKind.CALL })
        assertTrue(d2.count { it.kind == HoldemActionKind.FOLD } > 300)
        // and it never bluffs when checked to
        val checked = decisions(view("7s 2c", board = "Kh Jd 9c", toCall = 0, currentBet = 0), CpuDifficulty.EASY, null, 400)
        assertTrue(checked.all { it.kind == HoldemActionKind.CHECK })
    }

    @Test fun styleShiftsHowManyHandsArePlayed() {
        val v = view("Ts 8d", opponents = 3, position = 0.9)
        val tight = decisions(v, CpuDifficulty.HARD, 0.3, 100, BotStyle.TIGHT).count { it.kind != HoldemActionKind.FOLD }
        val loose = decisions(v, CpuDifficulty.HARD, 0.3, 100, BotStyle.LOOSE).count { it.kind != HoldemActionKind.FOLD }
        assertTrue("tight $tight loose $loose", loose > tight)
        assertEquals(BotStyle.BALANCED, BotStyle.forSeat("balanced", 4))
        assertEquals(BotStyle.TIGHT, BotStyle.forSeat("tight", 0))
        assertEquals(setOf(BotStyle.BALANCED, BotStyle.TIGHT, BotStyle.LOOSE), (0..2).map { BotStyle.forSeat("mixed", it) }.toSet())
    }

    @Test fun shortStacksShoveOrFold() {
        val v = view("As Qd", chips = 160, toCall = 20, opponents = 2, position = 0.7)
        val d = decisions(v, CpuDifficulty.HARD, 0.5, 50)
        assertTrue(d.all { it.kind == HoldemActionKind.ALL_IN })
        val weak = view("8s 3d", chips = 160, toCall = 20, opponents = 2, position = 0.7)
        assertTrue(decisions(weak, CpuDifficulty.HARD, 0.2, 50).all { it.kind == HoldemActionKind.FOLD })
    }

    @Test fun legalizeTurnsWishesIntoLegalActions() {
        val facing = options(toCall = 40, currentBet = 40)
        assertEquals(HoldemAction.CALL, HoldemBot.legalize(HoldemAction.CHECK, facing))
        assertEquals(HoldemAction.raiseTo(60), HoldemBot.legalize(HoldemAction.raiseTo(5), facing))
        assertEquals(HoldemAction.ALL_IN, HoldemBot.legalize(HoldemAction.raiseTo(99_999), facing))
        val free = options(toCall = 0, currentBet = 0)
        assertEquals(HoldemAction.CHECK, HoldemBot.legalize(HoldemAction.FOLD, free))
        assertEquals(HoldemAction.CHECK, HoldemBot.legalize(HoldemAction.CALL, free))
        val capped = options(toCall = 40, currentBet = 40, canRaise = false).copy(canAllIn = false)
        assertEquals(HoldemAction.CALL, HoldemBot.legalize(HoldemAction.raiseTo(200), capped))
        assertEquals(HoldemAction.CALL, HoldemBot.legalize(HoldemAction.ALL_IN, capped))
    }

    @Test fun theViewHidesNothingTheSeatShouldKnowAndNeedsRealCards() {
        val engine = HoldemEngine(Random(8))
        val s = engine.startHand(T.match(3, engine = engine))
        val v = HoldemBot.view(s, s.toAct)!!
        assertEquals(2, v.opponents)
        assertEquals(s.seats[s.toAct].hole, v.hole)
        assertEquals(30, v.pot)
        assertEquals(20, v.toCall)
        assertNull(HoldemBot.view(s, (s.toAct + 1) % 3)) // not that seat's turn
        assertNull(HoldemBot.view(holdemRedactFor(s, "p9"), s.toAct)) // hidden cards cannot be played
        // the button acts last after the flop
        val headsUp = engine.startHand(T.match(2, engine = engine))
        assertEquals(1.0, HoldemBot.view(headsUp, headsUp.toAct)!!.position, 0.0) // the button is also the small blind
    }

    @Test fun hintAdviceFollowsThePotOdds() {
        val bad = HoldemBot.hint(view("9s 8s", board = "Kh 2d 2c", pot = 100, toCall = 80, opponents = 1), 0.15)
        assertEquals(HintAdvice.FOLD, bad.advice)
        assertEquals(80.0 / 180.0, bad.potOdds, 1e-9)
        assertEquals(80, bad.toCall)
        val fine = HoldemBot.hint(view("Ah 5h", board = "Kh 9h 2c", pot = 100, toCall = 20, opponents = 1), 0.45)
        assertEquals(HintAdvice.CALL, fine.advice)
        val monster = HoldemBot.hint(view("Ah Kh", board = "Qh Jh Th", pot = 100, toCall = 0, currentBet = 0, opponents = 1), 0.99)
        assertEquals(HintAdvice.RAISE, monster.advice)
        assertTrue(monster.raiseTo >= 20)
        val checkIt = HoldemBot.hint(view("7s 2c", board = "Kh Jd 9c", pot = 100, toCall = 0, currentBet = 0, opponents = 1), 0.3)
        assertEquals(HintAdvice.CHECK, checkIt.advice)
        assertNotEquals(HintAdvice.FOLD, checkIt.advice)
    }
}
