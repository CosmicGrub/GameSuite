package com.gamesuite.games.holdem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Scripted hands: blinds, the order of play, betting rules, side pots, split pots and the end of the tournament. */
class HoldemEngineTest {
    private val engine = HoldemEngine(Random(1))
    private val fold = HoldemAction.FOLD
    private val check = HoldemAction.CHECK
    private val call = HoldemAction.CALL
    private val allIn = HoldemAction.ALL_IN

    private fun act(s: HoldemState, seat: Int, a: HoldemAction): HoldemState {
        val step = engine.act(s, seat, a)
        assertTrue("seat $seat $a rejected: ${step.reject}", step.ok)
        return step.state!!
    }

    private fun reject(s: HoldemState, seat: Int, a: HoldemAction): HoldemReject {
        val step = engine.act(s, seat, a)
        assertNull("expected a rejection for seat $seat $a", step.state)
        return step.reject
    }

    private fun totalChips(s: HoldemState) = s.seats.sumOf { it.chips + it.committed }

    // ---- blinds, button and order --------------------------------------------------------------

    @Test fun headUpTheButtonPostsTheSmallBlindAndActsFirstBeforeTheFlop() {
        val s = engine.startHand(T.match(2, engine = engine), T.deck(listOf("Ah Ad", "Kh Kd"), "2c 7d 9s Jc 3h"))
        assertEquals(0, s.button)
        assertEquals(0, s.smallBlindSeat)
        assertEquals(1, s.bigBlindSeat)
        assertEquals(10, s.seats[0].bet)
        assertEquals(20, s.seats[1].bet)
        assertEquals(0, s.toAct)
        assertEquals(20, s.currentBet)
        assertEquals(20, s.minRaise)
        assertEquals(T.cards("Kh Kd"), s.seats[0].hole)
        assertEquals(T.cards("Ah Ad"), s.seats[1].hole)
        assertTrue(HoldemRules.isConsistent(s, listOf("p1", "p2"), engine.deck, engine.deckPos))
    }

    @Test fun headUpTheBigBlindActsFirstAfterTheFlop() {
        var s = engine.startHand(T.match(2, engine = engine), T.deck(listOf("Ah Ad", "Kh Kd"), "2c 7d 9s Jc 3h"))
        s = act(s, 0, call)
        assertEquals(1, s.toAct) // the big blind still has its option
        s = act(s, 1, check)
        assertEquals(HoldemStreet.FLOP, s.street)
        assertEquals(T.cards("2c 7d 9s"), s.board)
        assertEquals(1, s.toAct)
        assertEquals(40, s.pot)
        assertEquals(0, s.seats[0].bet)
    }

    @Test fun threeHandedTheSeatAfterTheBigBlindActsFirst() {
        val s = engine.startHand(T.match(3, engine = engine), T.deck(listOf("2c 3d", "4c 5d", "6c 7d"), "As Ks Qs Js Ts"))
        assertEquals(0, s.button)
        assertEquals(1, s.smallBlindSeat)
        assertEquals(2, s.bigBlindSeat)
        assertEquals(0, s.toAct)
        // the first card goes to the seat left of the button
        assertEquals(T.cards("2c 3d"), s.seats[1].hole)
        assertEquals(T.cards("4c 5d"), s.seats[2].hole)
        assertEquals(T.cards("6c 7d"), s.seats[0].hole)
    }

    @Test fun showdownHeadUpPaysTheBestHand() {
        var s = engine.startHand(T.match(2, engine = engine), T.deck(listOf("Ah Ad", "Kh Kd"), "2c 7d 9s Jc 3h"))
        s = act(s, 0, call); s = act(s, 1, check)
        repeat(3) { s = act(s, 1, check); s = act(s, 0, check) }
        assertTrue(s.handOver)
        assertTrue(s.showdown)
        assertEquals(1020, s.seats[1].chips)
        assertEquals(980, s.seats[0].chips)
        assertEquals(listOf(0, 1), s.seatResults.map { it.seat }.sorted())
        assertTrue(s.seats.all { it.revealed })
        assertEquals(2000, totalChips(s))
        assertTrue(HoldemRules.isConsistent(s, listOf("p1", "p2"), engine.deck, engine.deckPos))
    }

    @Test fun theButtonMovesOneSeatPerHandAndSkipsBustedSeats() {
        var s = T.match(3, engine = engine)
        val buttons = ArrayList<Int>()
        repeat(4) {
            s = engine.startHand(s)
            buttons += s.button
            while (!s.handOver) s = act(s, s.toAct, fold)
        }
        assertEquals(listOf(0, 1, 2, 0), buttons)
        // seat 1 busted: the button jumps over it
        var t = T.match(3, engine = engine)
        t = engine.startHand(t)
        while (!t.handOver) t = act(t, t.toAct, fold)
        t = t.copy(seats = t.seats.mapIndexed { i, p -> if (i == 1) p.copy(chips = 0, busted = true) else p.copy(chips = p.chips + (if (i == 0) 1000 else 0)) })
        t = engine.startHand(t)
        assertEquals(2, t.button) // from 0, the next live seat is 2 (seat 1 is out)
        assertTrue(t.seats[1].hole.isEmpty())
        assertFalse(t.seats[1].inHand)
    }

    // ---- betting rules -------------------------------------------------------------------------

    @Test fun minimumRaiseFollowsTheLastFullRaise() {
        var s = engine.startHand(T.match(3, engine = engine))
        assertEquals(HoldemReject.BELOW_MIN_RAISE, reject(s, 0, HoldemAction.raiseTo(30)))
        s = act(s, 0, HoldemAction.raiseTo(40))
        assertEquals(20, s.minRaise)
        assertEquals(60, HoldemRules.options(s, 1)!!.minRaiseTo)
        s = act(s, 1, HoldemAction.raiseTo(100)) // raise by 60
        assertEquals(60, s.minRaise)
        assertEquals(160, HoldemRules.options(s, 2)!!.minRaiseTo)
        assertEquals(HoldemReject.BELOW_MIN_RAISE, reject(s, 2, HoldemAction.raiseTo(150)))
        assertEquals(HoldemReject.ABOVE_STACK, reject(s, 2, HoldemAction.raiseTo(5000)))
        s = act(s, 2, HoldemAction.raiseTo(160))
        assertEquals(160, s.currentBet)
    }

    @Test fun cannotCheckFacingABetOrCallWithNothingToCall() {
        var s = engine.startHand(T.match(3, engine = engine))
        assertEquals(HoldemReject.MUST_CALL, reject(s, 0, check))
        s = act(s, 0, call); s = act(s, 1, call)
        // the big blind may check; calling would be nothing
        assertEquals(HoldemReject.NOTHING_TO_CALL, reject(s, 2, call))
        assertEquals(HoldemReject.NOT_YOUR_TURN, reject(s, 0, check))
        s = act(s, 2, check)
        assertEquals(HoldemStreet.FLOP, s.street)
    }

    @Test fun theBigBlindGetsAnOptionWhenEveryoneJustCalls() {
        var s = engine.startHand(T.match(3, engine = engine))
        s = act(s, 0, call); s = act(s, 1, call)
        assertEquals(HoldemStreet.PREFLOP, s.street)
        assertEquals(2, s.toAct)
        s = act(s, 2, HoldemAction.raiseTo(60))
        assertEquals(0, s.toAct) // everyone else has to answer the raise
    }

    @Test fun aShortAllInRaiseDoesNotReopenTheBettingForPlayersWhoAlreadyActed() {
        var s = T.withStacks(engine.startHand(T.match(3, engine = engine)), listOf(1000, 1000, 110))
        // seat 2 posted the big blind (20) so it has 110 left: 130 in total
        s = act(s, 0, HoldemAction.raiseTo(100)) // a full raise (by 80)
        s = act(s, 1, call)
        assertEquals(80, s.minRaise)
        s = act(s, 2, allIn) // to 130: a raise of only 30
        assertTrue(s.seats[2].allIn)
        assertEquals(130, s.currentBet)
        assertEquals(80, s.minRaise) // unchanged
        // seat 0 acted before: it may only call or fold
        val o = HoldemRules.options(s, 0)!!
        assertFalse(o.canRaise)
        assertEquals(30, o.callAmount)
        assertEquals(HoldemReject.CANNOT_RAISE, reject(s, 0, HoldemAction.raiseTo(300)))
        assertEquals(HoldemReject.CANNOT_RAISE, reject(s, 0, allIn))
        s = act(s, 0, call)
        assertEquals(1, s.toAct)
        assertFalse(HoldemRules.options(s, 1)!!.canRaise)
        s = act(s, 1, call)
        assertEquals(HoldemStreet.FLOP, s.street)
    }

    @Test fun aFullAllInRaiseDoesReopenTheBetting() {
        var s = T.withStacks(engine.startHand(T.match(3, engine = engine)), listOf(1000, 1000, 300))
        s = act(s, 0, HoldemAction.raiseTo(100))
        s = act(s, 1, call)
        s = act(s, 2, allIn) // to 320... capped by the stack: 20 posted + 300 = 320, a raise by 220
        assertEquals(220, s.minRaise)
        assertTrue(HoldemRules.options(s, 0)!!.canRaise)
    }

    @Test fun everyoneFoldingToABetAwardsThePotWithoutAShowdown() {
        var s = engine.startHand(T.match(3, engine = engine), T.deck(listOf("2c 3d", "4c 5d", "6c 7d"), "As Ks Qs Js Ts"))
        s = act(s, 0, HoldemAction.raiseTo(60))
        s = act(s, 1, fold)
        s = act(s, 2, fold)
        assertTrue(s.handOver)
        assertFalse(s.showdown)
        assertEquals(HoldemEvent.UNCONTESTED, s.event)
        assertEquals(1030, s.seats[0].chips) // wins the two blinds
        assertEquals(990, s.seats[1].chips)
        assertEquals(980, s.seats[2].chips)
        assertTrue(s.seats.none { it.revealed }) // the winner's cards stay private
        assertEquals(3000, totalChips(s))
    }

    @Test fun actingAfterTheHandOrOutOfTurnIsRefused() {
        var s = engine.startHand(T.match(2, engine = engine))
        assertEquals(HoldemReject.NOT_YOUR_TURN, reject(s, 1, check))
        s = act(s, 0, fold)
        assertEquals(HoldemReject.HAND_OVER, reject(s, 0, fold))
        assertEquals(HoldemReject.NO_HAND, engine.act(T.match(2, engine = engine), 0, fold).reject)
    }

    // ---- all-in, side pots and the run-out -----------------------------------------------------

    private fun sidePotTable(raiseAllIn: Boolean): HoldemState {
        val base = T.withStacks(
            engine.startHand(
                engine.newMatch(T.players(3, 0), HoldemConfig(startingChips = 300, blindSpeed = "off"), 0),
                T.deck(listOf("Ks Kd", "As Ad", "7c 2d"), "Qc 9h 4s 5d 8h")
            ),
            // blinds are already posted: seat 1 has 3 in, seat 2 has 6 in
            listOf(200, 300 - 3, 400 - 6)
        )
        var s = act(base, 0, allIn)
        s = act(s, 1, allIn)
        s = act(s, 2, if (raiseAllIn) allIn else call)
        return s
    }

    @Test fun threeWayAllInBuildsAMainPotAndASidePot() {
        var s = sidePotTable(raiseAllIn = false)
        // only seat 2 (with chips behind) could still act, but there is nothing to bet: the board runs out
        assertTrue(s.runout)
        assertEquals(-1, s.toAct)
        assertEquals(0, s.board.size)
        assertTrue(s.seats.filter { it.live }.all { it.revealed })
        s = engine.stepRunout(s); assertEquals(3, s.board.size); assertEquals(HoldemStreet.FLOP, s.street)
        s = engine.stepRunout(s); assertEquals(4, s.board.size)
        s = engine.stepRunout(s); assertEquals(5, s.board.size); assertFalse(s.handOver)
        s = engine.stepRunout(s)
        assertTrue(s.handOver)
        // seat 2 holds the aces: main pot (3 x 200) and the side pot between seats 1 and 2 (2 x 100)
        assertEquals(2, s.results.size)
        assertEquals(600, s.results[0].amount); assertEquals(listOf(0, 1, 2), s.results[0].eligible); assertEquals(listOf(2), s.results[0].winners)
        assertEquals(200, s.results[1].amount); assertEquals(listOf(1, 2), s.results[1].eligible); assertEquals(listOf(2), s.results[1].winners)
        assertEquals(0, s.seats[0].chips); assertTrue(s.seats[0].busted)
        assertEquals(0, s.seats[1].chips); assertTrue(s.seats[1].busted)
        assertEquals(800 + 100, s.seats[2].chips)
        assertEquals(900, totalChips(s))
        assertTrue(s.matchOver)
        assertEquals("p3", s.winnerPlayerId)
        assertEquals(HoldemEvent.MATCH_OVER, s.event)
    }

    @Test fun theShortStackWinsOnlyTheMainPot() {
        // dealing starts left of the button: seat 1 gets the kings, seat 2 seven-deuce, seat 0 (the shortest stack) the aces
        var s = T.withStacks(
            engine.startHand(
                engine.newMatch(T.players(3, 0), HoldemConfig(startingChips = 300, blindSpeed = "off"), 0),
                T.deck(listOf("Ks Kd", "7c 2d", "As Ad"), "Qc 9h 4s 5d 8h")
            ),
            listOf(200, 297, 394)
        )
        s = act(s, 0, allIn); s = act(s, 1, allIn); s = act(s, 2, call)
        while (!s.handOver) s = engine.stepRunout(s)
        assertEquals(listOf(0), s.results[0].winners)
        assertEquals(600, s.results[0].amount)
        assertEquals(listOf(1), s.results[1].winners)
        assertEquals(200, s.results[1].amount)
        assertEquals(600, s.seats[0].chips)
        assertEquals(200, s.seats[1].chips)
        assertEquals(100, s.seats[2].chips) // what it kept behind
        assertEquals(900, totalChips(s))
        assertFalse(s.matchOver)
    }

    @Test fun anUncalledBetComesBackToItsOwner() {
        val s = sidePotTable(raiseAllIn = true)
        assertTrue(s.runout)
        var t = s
        while (!t.handOver) t = engine.stepRunout(t)
        // seat 2 put in all 400 but only 300 was covered: the last 100 is a refund pot of its own
        assertEquals(3, t.results.size)
        assertTrue(t.results[2].refund)
        assertEquals(100, t.results[2].amount)
        assertEquals(listOf(2), t.results[2].winners)
        assertEquals(900, totalChips(t))
        assertEquals(900, t.seats[2].chips) // 600 + 200 + 100 (the aces win both real pots)
    }

    @Test fun theBoardRunsOutWithRevealedCardsAndOneStreetPerStep() {
        var s = T.withStacks(
            engine.startHand(T.match(2, engine = engine), T.deck(listOf("Ah Ad", "Kh Kd"), "2c 7d 9s Jc 3h")),
            listOf(990, 980)
        )
        s = act(s, 0, allIn)
        s = act(s, 1, call)
        assertTrue(s.runout)
        assertTrue(s.seats.all { it.revealed })
        assertEquals(0, s.board.size)
        val sizes = ArrayList<Int>()
        while (!s.handOver) { s = engine.stepRunout(s); sizes += s.board.size }
        assertEquals(listOf(3, 4, 5, 5), sizes)
        assertEquals(HoldemStreet.RIVER, s.street)
    }

    // ---- split pots ----------------------------------------------------------------------------

    @Test fun anOddChipGoesToTheFirstWinnerLeftOfTheButton() {
        val cfg = HoldemConfig(startingChips = 500, blindSpeed = "off") // blinds 5 / 10
        var s = engine.startHand(engine.newMatch(T.players(3, 0), cfg, 0), T.deck(listOf("2c 3d", "4c 5d", "6c 7d"), "As Ks Qs Js Ts"))
        s = act(s, 0, call)
        s = act(s, 1, fold) // the small blind's 5 stays in the pot
        s = act(s, 2, check)
        repeat(3) { s = act(s, 2, check); s = act(s, 0, check) }
        assertTrue(s.handOver)
        assertEquals(25, s.results.sumOf { it.amount })
        assertEquals(listOf(2, 0), s.results[0].winners) // seat 2 is first clockwise from the button's left
        assertEquals(500 - 10 + 13, s.seats[2].chips)
        assertEquals(500 - 10 + 12, s.seats[0].chips)
        assertEquals(500 - 5, s.seats[1].chips)
        assertEquals(1500, totalChips(s))
    }

    @Test fun twoPlayersChoppingAnEvenPotGetTheirBetsBack() {
        var s = engine.startHand(T.match(2, engine = engine), T.deck(listOf("2c 3d", "4c 5d"), "As Ks Qs Js Ts"))
        s = act(s, 0, call); s = act(s, 1, check)
        repeat(3) { s = act(s, 1, check); s = act(s, 0, check) }
        assertEquals(1000, s.seats[0].chips)
        assertEquals(1000, s.seats[1].chips)
    }

    // ---- blinds rising and the end of the tournament -------------------------------------------

    @Test fun blindsRiseOnSchedule() {
        var s = T.match(2, speed = "normal", engine = engine)
        val bigBlinds = ArrayList<Int>()
        repeat(17) {
            s = engine.startHand(s)
            bigBlinds += s.bigBlind
            s = act(s, s.toAct, fold)
        }
        assertEquals(List(8) { 20 } + List(8) { 40 } + listOf(60), bigBlinds)
        assertEquals(2, s.blindLevel)
        assertEquals(30, s.smallBlind)
    }

    @Test fun blindsNeverRiseWhenSwitchedOff() {
        var s = T.match(2, speed = "off", engine = engine)
        repeat(30) { s = engine.startHand(s); assertEquals(20, s.bigBlind); s = act(s, s.toAct, fold) }
    }

    @Test fun theScheduleHelpersAgree() {
        assertEquals(20, HoldemBlinds.bigBlindAt(1000, 0))
        assertEquals(10, HoldemBlinds.bigBlindAt(500, 0))
        assertEquals(40, HoldemBlinds.bigBlindAt(2000, 0))
        assertEquals(100, HoldemBlinds.bigBlindAt(5000, 0))
        assertEquals(20 * 200, HoldemBlinds.bigBlindAt(1000, 99))
        assertEquals(0, HoldemBlinds.levelForHand(BlindSpeed.OFF, 500))
        assertEquals(1, HoldemBlinds.levelForHand(BlindSpeed.FAST, 5))
        assertEquals(8, HoldemBlinds.handsUntilRise(BlindSpeed.NORMAL, 1))
        assertEquals(1, HoldemBlinds.handsUntilRise(BlindSpeed.NORMAL, 8))
        assertEquals(8, HoldemBlinds.handsUntilRise(BlindSpeed.NORMAL, 9))
        assertNull(HoldemBlinds.handsUntilRise(BlindSpeed.OFF, 3))
    }

    @Test fun theTournamentEndsWhenOneSeatHoldsEveryChip() {
        var s = engine.startHand(T.match(2, engine = engine), T.deck(listOf("Ah Ad", "Kh Kd"), "2c 7d 9s Jc 3h"))
        s = act(s, 0, allIn); s = act(s, 1, call)
        while (!s.handOver) s = engine.stepRunout(s)
        assertTrue(s.matchOver)
        assertEquals("p2", s.winnerPlayerId)
        assertEquals(2000, s.seats[1].chips)
        assertTrue(s.seats[0].busted)
        assertEquals(HoldemEvent.MATCH_OVER, s.event)
        // nothing more can be dealt or played
        assertEquals(s, engine.startHand(s))
        assertEquals(HoldemReject.MATCH_OVER, reject(s, 1, check))
        assertTrue(HoldemRules.isConsistent(s, listOf("p1", "p2"), engine.deck, engine.deckPos))
    }

    @Test fun theTournamentAlsoEndsWhenNoHumanIsLeft() {
        val cfg = HoldemConfig(startingChips = 1000, blindSpeed = "off", endWhenHumansOut = true)
        val players = T.players(1, 2)
        var s = engine.startHand(engine.newMatch(players, cfg, 0), T.deck(listOf("Ah Ad", "Kh Kd", "Qh Qd"), "2c 7d 9s Jc 3h"))
        // seat 0 (the human) is the button; dealing starts at seat 1: bot1 AA, bot2 KK, human QQ
        s = act(s, 0, allIn); s = act(s, 1, call); s = act(s, 2, fold)
        while (!s.handOver) s = engine.stepRunout(s)
        assertTrue(s.seats[0].busted)
        assertFalse(s.seats[2].busted) // two CPUs still hold chips...
        assertTrue(s.matchOver) // ...but nobody is left to play against them
        assertEquals("bot1", s.winnerPlayerId)
    }

    @Test fun aTwoCpuMatchDoesNotEndByTheHumanRule() {
        val cfg = HoldemConfig(startingChips = 1000, blindSpeed = "off", endWhenHumansOut = true)
        var s = engine.startHand(engine.newMatch(T.players(0, 3), cfg, 0))
        while (!s.handOver) s = act(s, s.toAct, fold)
        assertFalse(s.matchOver)
    }

    // ---- pots ----------------------------------------------------------------------------------

    @Test fun potLayersForFoldedAndAllInContributions() {
        // seat 0 all-in 50, seat 1 all-in 120, seat 2 called 120 and seat 3 folded after putting in 30
        val pots = HoldemRules.buildPots(listOf(50, 120, 120, 30), listOf(true, true, true, false))
        assertEquals(2, pots.size)
        assertEquals(50 * 3 + 30, pots[0].amount) // 50 each from the three live seats and the folder's 30
        assertEquals(listOf(0, 1, 2), pots[0].eligible)
        assertEquals(70 * 2, pots[1].amount)
        assertEquals(listOf(1, 2), pots[1].eligible)
        assertEquals(320, pots.sumOf { it.amount })
        assertFalse(pots.any { it.refund })
    }

    @Test fun potLayersWithAFoldedBigBlindAndASingleBettor() {
        val pots = HoldemRules.buildPots(listOf(300, 20, 20), listOf(true, false, false))
        assertEquals(1, pots.size)
        assertEquals(340, pots[0].amount)
        assertEquals(listOf(0), pots[0].eligible)
        assertEquals(340, pots.sumOf { it.amount })
    }

    @Test fun potsAlwaysAddUpToWhatWasPutIn() {
        val rnd = Random(9)
        repeat(2000) {
            val n = rnd.nextInt(2, 7)
            val committed = List(n) { rnd.nextInt(0, 300) }
            val live = List(n) { rnd.nextBoolean() }
            if (live.none { it }) return@repeat
            // a folded seat never puts in more than the largest live one (the rules of betting)
            val cap = committed.filterIndexed { i, _ -> live[i] }.max()
            val c = committed.mapIndexed { i, v -> if (live[i]) v else minOf(v, cap) }
            val pots = HoldemRules.buildPots(c, live)
            assertEquals(c.sum(), pots.sumOf { it.amount })
            pots.forEach { assertTrue(it.eligible.all { e -> live[e] }) }
        }
    }

    @Test fun roundTripThroughJsonKeepsTheState() {
        var s = engine.startHand(T.match(4, engine = engine))
        s = act(s, s.toAct, HoldemAction.raiseTo(60))
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val text = json.encodeToString(HoldemState.serializer(), s)
        assertEquals(s, json.decodeFromString(HoldemState.serializer(), text))
        assertNotNull(text)
    }
}
