package com.gamesuite.games.blackjack

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.blackjack.BjTest.autoRound
import com.gamesuite.games.blackjack.BjTest.dealt
import com.gamesuite.games.blackjack.BjTest.finishDealer
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlackjackGameTest {
    private val noInsurance = BjConfig(decks = 1, insurance = false)

    private fun BlackjackGame.s() = state.value!!
    private fun BlackjackGame.hand(seat: Int = 0, h: Int = 0) = s().seats[seat].hands[h]

    // ---- the deal and the natural ----------------------------------------------------------------

    @Test fun dealOrderIsSeatsUpcardSeatsHoleCard() {
        val g = dealt(listOf("2S", "3S", "4H", "5S", "6S", "7H", "8D", "9D"), seats = 2)
        // two seats: first cards 2S,3S; up 4H; second cards 5S,6S; hole 7H
        assertEquals(cds("2S", "5S"), g.hand(0).plain)
        assertEquals(cds("3S", "6S"), g.hand(1).plain)
        assertEquals(cd("4H"), g.s().upcard)
        assertEquals(cd("7H"), g.s().dealer[1].card)
        assertTrue(g.s().holeHidden)
        assertEquals(BjPhase.PLAYING, g.s().phase)
        assertEquals(0, g.s().activeSeat)
    }

    @Test fun playerBlackjackPaysThreeToTwo() {
        val g = dealt(listOf("AS", "9H", "KD", "8C"))
        assertEquals(BjPhase.DEALER, g.s().phase)       // a natural plays no further
        finishDealer(g)
        assertEquals(BjPhase.ROUND_OVER, g.s().phase)
        assertEquals(BjOutcome.BLACKJACK, g.hand().outcome)
        assertEquals(1150, g.s().seats[0].stack)
        assertEquals(150, g.s().seats[0].roundNet)
        assertEquals(1, g.s().seats[0].blackjacks)
    }

    @Test fun playerBlackjackPaysSixToFiveWhenSet() {
        val g = dealt(listOf("AS", "9H", "KD", "8C"), config = BjConfig(decks = 1, payout = "6:5"))
        finishDealer(g)
        assertEquals(1120, g.s().seats[0].stack)
    }

    @Test fun dealerNaturalEndsTheRoundAtOnceAndBeatsEverythingButANatural() {
        val g = dealt(listOf("10S", "KH", "9D", "AC"), config = noInsurance)
        assertEquals(BjPhase.ROUND_OVER, g.s().phase)
        assertFalse(g.s().holeHidden)
        assertEquals(BjOutcome.LOSE, g.hand().outcome)
        assertEquals(900, g.s().seats[0].stack)
        assertEquals(BjEvent.DEALER_BLACKJACK, g.s().event)
        // both naturals push
        val p = dealt(listOf("AS", "KH", "KD", "AC"), config = noInsurance)
        assertEquals(BjOutcome.PUSH, p.hand().outcome)
        assertEquals(1000, p.s().seats[0].stack)
    }

    // ---- insurance -------------------------------------------------------------------------------

    @Test fun insuranceIsOfferedOnAnAceAndPaysTwoToOne() {
        val g = dealt(listOf("10S", "AH", "8D", "KC"))
        assertEquals(BjPhase.INSURANCE, g.s().phase)
        assertTrue(g.canInsure(g.s()))
        assertTrue(g.insurance(true))
        // insurance 50: stack 1000 - 100 - 50 = 850; dealer has blackjack: insurance pays 150 back
        assertEquals(BjPhase.ROUND_OVER, g.s().phase)
        assertEquals(1000, g.s().seats[0].stack)
        assertEquals(0, g.s().seats[0].roundNet)
        assertEquals(150, g.s().seats[0].insuranceCredit)
    }

    @Test fun insuranceLostWhenTheDealerHasNoBlackjack() {
        val g = dealt(listOf("10S", "AH", "8D", "6C"))
        g.insurance(true)
        assertEquals(BjPhase.PLAYING, g.s().phase)
        assertEquals(850, g.s().seats[0].stack)
        g.act(BjAction.STAND)
        finishDealer(g)
        // player 18 vs dealer soft 17 (A+6): win 200 -> 1050; the 50 insurance is gone
        assertEquals(1050, g.s().seats[0].stack)
        assertEquals(50, g.s().seats[0].roundNet)
    }

    @Test fun decliningInsuranceAndTheOptionBeingOff() {
        val g = dealt(listOf("10S", "AH", "8D", "6C"))
        assertTrue(g.insurance(false))
        assertEquals(BjPhase.PLAYING, g.s().phase)
        assertEquals(900, g.s().seats[0].stack)
        val off = dealt(listOf("10S", "AH", "8D", "6C"), config = noInsurance)
        assertEquals(BjPhase.PLAYING, off.s().phase)
        assertFalse(off.insurance(true))
    }

    @Test fun aSeatThatCannotAffordInsuranceSkipsIt() {
        // all-in bet leaves 0 chips: nothing to insure with
        val g = BjTest.game()
        g.stackShoe(cds("10S", "AH", "8D", "6C"))
        assertTrue(g.bet(1000))
        assertEquals(BjPhase.PLAYING, g.s().phase)
    }

    // ---- hit, stand, bust ------------------------------------------------------------------------

    @Test fun standOnTwentyBeatsDealerNineteen() {
        val g = dealt(listOf("10S", "10H", "KD", "9C"))
        assertEquals(BjPhase.PLAYING, g.s().phase)
        g.act(BjAction.STAND)
        assertEquals(BjPhase.DEALER, g.s().phase)
        finishDealer(g)
        assertEquals(BjOutcome.WIN, g.hand().outcome)
        assertEquals(1100, g.s().seats[0].stack)
    }

    @Test fun hitBustLosesAndTheDealerDoesNotDraw() {
        val g = dealt(listOf("10S", "7H", "6D", "10C", "KD"))
        g.act(BjAction.HIT)
        assertEquals(BjEvent.BUST, g.s().event)
        assertEquals(BjPhase.DEALER, g.s().phase)
        finishDealer(g)
        assertEquals(BjOutcome.BUST, g.hand().outcome)
        assertEquals(2, g.s().dealer.size)
        assertEquals(900, g.s().seats[0].stack)
    }

    @Test fun twentyOneEndsTheHandWithoutAskingAgain() {
        val g = dealt(listOf("9S", "7H", "2D", "10C", "KD", "5H"))
        g.act(BjAction.HIT)           // 9 + 2 + K = 21
        assertEquals(BjPhase.DEALER, g.s().phase)
    }

    @Test fun dealerStandsOnSoftSeventeenUnlessTheTableHitsIt() {
        val shoe = listOf("10S", "AH", "8D", "6C", "4D")
        val s17 = dealt(shoe, config = noInsurance)
        s17.act(BjAction.STAND); finishDealer(s17)
        assertEquals(2, s17.s().dealer.size)
        assertEquals(BjOutcome.WIN, s17.hand().outcome)
        val h17 = dealt(shoe, config = BjConfig(decks = 1, insurance = false, hitSoft17 = true))
        h17.act(BjAction.STAND); finishDealer(h17)
        assertEquals(3, h17.s().dealer.size)          // A + 6 + 4 = soft 21
        assertEquals(BjOutcome.LOSE, h17.hand().outcome)
    }

    @Test fun pushReturnsTheBet() {
        val g = dealt(listOf("10S", "10H", "8D", "8C"))
        g.act(BjAction.STAND); finishDealer(g)
        assertEquals(BjOutcome.PUSH, g.hand().outcome)
        assertEquals(1000, g.s().seats[0].stack)
        assertEquals(1, g.s().seats[0].pushed)
    }

    // ---- double, split, surrender ----------------------------------------------------------------

    @Test fun doubleTakesExactlyOneCardAndDoublesTheStake() {
        val g = dealt(listOf("5S", "6H", "6D", "10C", "10S", "10D"))
        assertTrue(BjAction.DOUBLE in g.legalActions())
        g.act(BjAction.DOUBLE)
        assertEquals(3, g.hand().cards.size)
        assertEquals(200, g.hand().bet)
        assertEquals(800, g.s().seats[0].stack)
        assertEquals(BjPhase.DEALER, g.s().phase)
        finishDealer(g)                                // dealer 16 draws a ten and busts
        assertEquals(BjOutcome.WIN, g.hand().outcome)
        assertEquals(1200, g.s().seats[0].stack)
    }

    @Test fun doubleIsOnlyOnTheFirstTwoCardsAndNeedsTheChips() {
        val g = dealt(listOf("2S", "6H", "3D", "10C", "2H", "4S"))
        g.act(BjAction.HIT)
        assertFalse(BjAction.DOUBLE in g.legalActions())
        assertEquals(BjReject.NOT_FIRST_TWO, g.explainAction(BjAction.DOUBLE))
        // a 600 bet cannot be doubled from 400 chips
        val broke = BjTest.game()
        broke.stackShoe(cds("5S", "6H", "6D", "10C"))
        broke.bet(600)
        assertFalse(BjAction.DOUBLE in broke.legalActions())
        assertEquals(BjReject.CANT_AFFORD, broke.explainAction(BjAction.DOUBLE))
    }

    @Test fun splitMakesTwoHandsEachWithItsOwnStake() {
        val g = dealt(listOf("8S", "6H", "8D", "10C", "3S", "2D", "5C"))
        assertTrue(BjAction.SPLIT in g.legalActions())
        g.act(BjAction.SPLIT)
        val seat = g.s().seats[0]
        assertEquals(2, seat.hands.size)
        assertEquals(cds("8S", "3S"), seat.hands[0].plain)
        assertEquals(cds("8D", "2D"), seat.hands[1].plain)
        assertEquals(800, seat.stack)
        assertEquals(0, g.s().activeHand)
        g.act(BjAction.STAND)
        assertEquals(1, g.s().activeHand)
        g.act(BjAction.STAND)
        assertEquals(BjPhase.DEALER, g.s().phase)
    }

    @Test fun splitAcesGetOneCardEachAndTwentyOneAfterASplitIsNotANatural() {
        val g = dealt(listOf("AS", "6H", "AD", "10C", "9S", "KD", "10H"))
        g.act(BjAction.SPLIT)
        assertEquals(BjPhase.DEALER, g.s().phase)       // both hands are finished at once
        finishDealer(g)                                  // dealer 16 + 10 busts
        val hands = g.s().seats[0].hands
        assertEquals(BjOutcome.WIN, hands[0].outcome)
        assertEquals(BjOutcome.WIN, hands[1].outcome)    // A + K after a split pays 1:1
        assertEquals(200, hands[1].credit)
        assertEquals(1200, g.s().seats[0].stack)
    }

    @Test fun splitNeedsAPairOfTheSameRankAndAtMostFourHands() {
        val g = dealt(listOf("KS", "6H", "QD", "10C"))
        assertFalse(BjAction.SPLIT in g.legalActions())
        assertEquals(BjReject.NOT_PAIR, g.explainAction(BjAction.SPLIT))
        // keep splitting eights until the limit
        val e = dealt(listOf("8S", "6H", "8D", "10C", "8H", "8C", "8S", "8D", "8H", "8C", "8S", "8D", "8H", "8C", "8S", "8D", "8H", "8C"))
        var splits = 0
        while (BjAction.SPLIT in e.legalActions() && splits < 10) { e.act(BjAction.SPLIT); splits++ }
        assertTrue(e.s().seats[0].hands.size <= BjRules.MAX_HANDS)
        if (e.s().seats[0].hands.size == BjRules.MAX_HANDS && e.s().phase == BjPhase.PLAYING) {
            assertEquals(BjReject.MAX_HANDS, e.explainAction(BjAction.SPLIT))
        }
    }

    @Test fun lateSurrenderReturnsHalfAndOnlyOnTheFirstTwoCards() {
        val g = dealt(listOf("10S", "7H", "6D", "10C"))
        assertTrue(BjAction.SURRENDER in g.legalActions())
        g.act(BjAction.SURRENDER)
        finishDealer(g)
        assertEquals(BjOutcome.SURRENDER, g.hand().outcome)
        assertEquals(950, g.s().seats[0].stack)
        assertEquals(-50, g.s().seats[0].roundNet)
        val h = dealt(listOf("2S", "7H", "3D", "10C", "2H"))
        h.act(BjAction.HIT)
        assertEquals(BjReject.NOT_FIRST_TWO, h.explainAction(BjAction.SURRENDER))
        val off = dealt(listOf("10S", "7H", "6D", "10C"), config = BjConfig(decks = 1, surrender = false))
        assertFalse(BjAction.SURRENDER in off.legalActions())
        assertEquals(BjReject.SURRENDER_OFF, off.explainAction(BjAction.SURRENDER))
    }

    @Test fun surrenderIsNotOfferedOnASplitHand() {
        val g = dealt(listOf("8S", "6H", "8D", "10C", "3S", "2D"))
        g.act(BjAction.SPLIT)
        assertFalse(BjAction.SURRENDER in g.legalActions())
    }

    // ---- turn order and gating -------------------------------------------------------------------

    @Test fun twoSeatsPlayInOrderAndEachIsSettledAgainstTheSameDealer() {
        // p1: 10+10 = 20, p2: 10+6 = 16; dealer 10 + 8 = 18
        val g = dealt(listOf("10S", "10H", "9C", "KD", "6S", "8C"), seats = 2)
        // order: p1a=10S p2a=10H up=9C p1b=KD p2b=6S hole=8C
        assertEquals(0, g.s().activeSeat)
        g.act(BjAction.STAND)
        assertEquals(1, g.s().activeSeat)
        g.act(BjAction.STAND)
        finishDealer(g)
        assertEquals(BjOutcome.WIN, g.hand(0).outcome)
        assertEquals(BjOutcome.LOSE, g.hand(1).outcome)
        assertEquals(1100, g.s().seats[0].stack)
        assertEquals(900, g.s().seats[1].stack)
    }

    @Test fun actionsOutOfPhaseOrTurnAreRefusedAndChangeNothing() {
        val g = BjTest.game()
        assertFalse(g.act(BjAction.HIT))
        assertFalse(g.insurance(true))
        assertFalse(g.nextRound())
        assertFalse(g.bet(5))                // not a multiple of the minimum
        assertFalse(g.bet(15))
        assertFalse(g.bet(5000))             // more than the stack
        assertEquals(BjReject.BAD_BET, g.explainBet(15))
        assertEquals(BjReject.CANT_AFFORD, g.explainBet(2000))
        assertEquals(BjPhase.BETTING, g.s().phase)
        val before = g.s()
        assertFalse(g.dealerStep())
        assertTrue(before === g.s())
    }

    @Test fun nextRoundStartsAFreshBettingRoundAndDiscardsTheCards() {
        val g = dealt(listOf("10S", "10H", "KD", "9C"))
        g.act(BjAction.STAND); finishDealer(g)
        assertTrue(g.nextRound())
        val s = g.s()
        assertEquals(BjPhase.BETTING, s.phase)
        assertEquals(2, s.round)
        assertTrue(s.dealer.isEmpty())
        assertTrue(s.seats[0].hands.isEmpty())
        assertEquals(1100, s.seats[0].stack)
        assertNotNull(g.snapshot())
    }

    // ---- rebuy and sitting out -------------------------------------------------------------------

    @Test fun aBrokeSeatMayRebuyAndTheRebuyIsCounted() {
        val cfg = BjConfig(decks = 1, startingChips = 500, insurance = false)
        val g = BjTest.game(config = cfg)
        g.stackShoe(cds("10S", "10H", "6D", "KC", "5H"))
        assertTrue(g.bet(500))
        g.act(BjAction.STAND); finishDealer(g)
        assertEquals(0, g.s().seats[0].stack)
        g.nextRound()
        assertTrue(g.canRebuy())
        assertFalse(g.bet(10))
        assertTrue(g.rebuy())
        assertEquals(500, g.s().seats[0].stack)
        assertEquals(1, g.s().seats[0].rebuys)
        assertFalse(g.canRebuy())
        assertEquals(-500, g.netOf(g.s(), 0))
    }

    @Test fun cannotRebuyWithChipsLeft() {
        val g = BjTest.game()
        assertFalse(g.canRebuy())
        assertFalse(g.rebuy())
        assertFalse(g.sitOut())
    }

    @Test fun sittingOutAnEmptyTableStartsTheBettingAgain() {
        val cfg = BjConfig(decks = 1, startingChips = 500, insurance = false)
        val g = BjTest.game(config = cfg)
        g.stackShoe(cds("10S", "10H", "6D", "KC"))
        g.bet(500); g.act(BjAction.STAND); finishDealer(g); g.nextRound()
        assertTrue(g.sitOut())
        assertEquals(BjEvent.NO_BETS, g.s().event)
        assertEquals(BjPhase.BETTING, g.s().phase)
        assertEquals(2, g.s().round)
    }

    // ---- the shoe --------------------------------------------------------------------------------

    @Test fun theShoeIsReshuffledOnceUnderAQuarterRemainsAndEveryCardStaysAccountedFor() {
        val g = BjTest.game(config = BjConfig(decks = 1, insurance = false), seed = 5)
        var reshuffles = 0
        var rounds = 0
        while (rounds < 40) {
            autoRound(g)
            val before = g.s().shoeLeft
            g.nextRound()
            if (g.s().reshuffled) { reshuffles++; assertEquals(52, g.s().shoeLeft); assertTrue(before < 13) }
            assertNotNull("snapshot validates card accounting at round $rounds", g.snapshot())
            assertTrue(g.restore(g.snapshot()!!))
            rounds++
        }
        assertTrue("a 1-deck shoe must reshuffle within 40 rounds", reshuffles >= 1)
    }

    @Test fun sameSeedDealsTheSameGame() {
        fun run(): List<Int> {
            val g = BjTest.game(config = BjConfig(decks = 2), seed = 99)
            val trail = ArrayList<Int>()
            repeat(10) { autoRound(g); trail += g.s().seats[0].stack; g.nextRound() }
            return trail
        }
        assertEquals(run(), run())
    }

    // ---- hint ------------------------------------------------------------------------------------

    @Test fun hintIsBasicStrategyUsingOnlyTheUpcard() {
        val g = dealt(listOf("5S", "6H", "6D", "10C"), config = BjConfig(decks = 1, insurance = false))
        assertEquals(BjAction.DOUBLE, g.hint())      // 11 vs 6
        assertEquals(BjAction.DOUBLE, g.hintAction.value)
        val h = dealt(listOf("10S", "10H", "6D", "10C"), config = BjConfig(decks = 1, insurance = false, surrender = false))
        assertEquals(BjAction.HIT, h.hint())         // 16 vs 10, surrender off
        val ins = dealt(listOf("10S", "AH", "8D", "6C"))
        assertEquals(BjAction.DECLINE_INSURANCE, ins.hint())
        assertNull(BjTest.game().hint())             // nothing to advise while betting
    }

    // ---- computer seats --------------------------------------------------------------------------

    @Test fun computerSeatsBetPlayAndRebuyOnTheirOwn() {
        val players = listOf(PlayerInfo("you", "You"), PlayerInfo("b1", "CPU 1", isBot = true), PlayerInfo("b2", "CPU 2", isBot = true))
        val g = BjTest.game(players, BjConfig(decks = 2), PlayMode.SINGLE_PLAYER_VS_BOT, seed = 3)
        for (level in CpuDifficulty.entries) {
            g.difficulty = level
            repeat(15) {
                autoRound(g)
                g.s().seats.forEach { assertTrue(it.stack >= 0) }
                g.nextRound()
            }
        }
        assertTrue(g.s().seats.drop(1).sumOf { it.roundsPlayed } > 0)
    }

    @Test fun hardPlaysBasicStrategyAndNeverInsures() {
        val rnd = Random(1)
        repeat(200) {
            assertFalse(BjBot.takeInsurance(CpuDifficulty.HARD, rnd))
        }
        val hand = cds("10S", "6D")
        val up = cd("10H")
        repeat(100) {
            val a = BjBot.chooseAction(CpuDifficulty.HARD, hand, up, setOf(BjAction.HIT, BjAction.STAND, BjAction.DOUBLE), false, rnd)
            assertEquals(BjAction.HIT, a)
        }
    }

    @Test fun easyMakesMoreMistakesThanMediumAndBothBeatNothing() {
        fun deviations(level: CpuDifficulty): Int {
            val rnd = Random(7)
            var n = 0
            val legal = setOf(BjAction.HIT, BjAction.STAND, BjAction.DOUBLE)
            repeat(2000) {
                val a = BjBot.chooseAction(level, cds("10S", "6D"), cd("10H"), legal, false, rnd)
                if (a != BjAction.HIT) n++
            }
            return n
        }
        val easy = deviations(CpuDifficulty.EASY)
        val medium = deviations(CpuDifficulty.MEDIUM)
        val hard = deviations(CpuDifficulty.HARD)
        assertEquals(0, hard)
        assertTrue("easy $easy > medium $medium", easy > medium && medium > 0)
    }

    @Test fun botBetsStayInsideTheStackAndOnTheChipGrid() {
        val rnd = Random(2)
        for (level in CpuDifficulty.entries) for (stack in listOf(10, 20, 55, 100, 999, 5000)) repeat(100) {
            val b = BjBot.chooseBet(level, stack, rnd)
            assertTrue(b >= BjRules.MIN_BET && b <= stack && b % BjRules.MIN_BET == 0)
        }
    }

    @Test fun asyncBotTurnAppliesOnlyIfTheTableDidNotMove() {
        val players = listOf(PlayerInfo("b1", "CPU", isBot = true), PlayerInfo("you", "You"))
        val g = BjTest.game(players, BjConfig(decks = 1), PlayMode.SINGLE_PLAYER_VS_BOT)
        assertTrue(g.botToMove())
        runBlocking { g.playBotTurnAsync() }
        assertEquals(1, g.s().activeSeat)   // the computer bet, then it is the person's turn
        assertFalse(g.botToMove())
    }

    // ---- resume ----------------------------------------------------------------------------------

    private fun fresh(players: List<PlayerInfo> = listOf(PlayerInfo("you", "You")), mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT): BlackjackGame {
        val g = BlackjackGame(Random(2))
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        return g
    }

    @Test fun snapshotRoundTripsAtEveryPhaseAndPlayContinuesIdentically() {
        val players = listOf(PlayerInfo("you", "You"), PlayerInfo("b1", "CPU", isBot = true))
        val a = BjTest.game(players, BjConfig(decks = 2, hitSoft17 = true), seed = 11)
        var checked = 0
        var guard = 0
        val phases = HashSet<String>()
        while (guard++ < 600 && checked < 400) {
            val s = a.s()
            phases += s.phase
            val snap = a.snapshot()!!
            val b = fresh(players)
            assertTrue("restore at ${s.phase}", b.restore(snap))
            assertEquals(s.copy(), b.s().copy())
            assertEquals(snap, b.snapshot())
            checked++
            when {
                a.botToMove(s) -> a.playBotStep()
                a.dealerToMove(s) -> a.dealerStep()
                s.phase == BjPhase.BETTING -> a.bet(50)
                s.phase == BjPhase.INSURANCE -> a.insurance(guard % 2 == 0)
                s.phase == BjPhase.PLAYING -> if (!a.act(listOf(BjAction.HIT, BjAction.STAND, BjAction.DOUBLE, BjAction.SPLIT)[guard % 4])) a.act(BjAction.STAND)
                s.phase == BjPhase.ROUND_OVER -> a.nextRound()
            }
        }
        assertTrue(phases.containsAll(setOf(BjPhase.BETTING, BjPhase.PLAYING, BjPhase.DEALER, BjPhase.ROUND_OVER)))
    }

    @Test fun aRestoredGameDealsTheSameNextCardsAsTheOriginal() {
        val a = BjTest.game(config = BjConfig(decks = 1, insurance = false), seed = 21)
        a.bet(100)
        val b = fresh()
        assertTrue(b.restore(a.snapshot()!!))
        if (a.s().phase == BjPhase.PLAYING) {
            a.act(BjAction.HIT); b.act(BjAction.HIT)
            assertEquals(a.hand().plain, b.hand().plain)
        }
    }

    @Test fun restoreRejectsStaleCorruptAndForeignSaves() {
        val a = BjTest.game(config = BjConfig(decks = 1), seed = 4)
        a.bet(100)
        val good = a.snapshot()!!
        assertTrue(fresh().restore(good))
        assertFalse(fresh().restore("not json"))
        assertFalse(fresh().restore("{}"))
        // different roster
        assertFalse(fresh(listOf(PlayerInfo("other", "You"))).restore(good))
        assertFalse(fresh(listOf(PlayerInfo("you", "You"), PlayerInfo("p2", "P2"))).restore(good))
        // a card that exists twice too often
        val tampered = good.replaceFirst(Regex("\"shoe\":\\[\\d+"), "\"shoe\":[0")
        if (tampered != good) assertFalse(fresh().restore(tampered))
        // impossible phase
        assertFalse(fresh().restore(good.replace(Regex("\"phase\":\"[a-z_]+\""), "\"phase\":\"nonsense\"")))
        // negative chips
        assertFalse(fresh().restore(good.replaceFirst(Regex("\"stack\":\\d+"), "\"stack\":-5")))
        // a failed restore leaves an untouched engine
        val c = fresh()
        c.restore("garbage")
        assertNull(c.state.value)
    }

    @Test fun networkedAndFinishedGamesDoNotSnapshot() {
        val g = BjTest.game(mode = PlayMode.LOCAL_AD_HOC, start = false)
        g.startMatch()
        assertNull(g.snapshot())
        val h = BjTest.game()
        h.leaveSession()
        assertNull(h.snapshot())
    }

    // ---- ending the session ----------------------------------------------------------------------

    @Test fun leavingScoresTheSessionByChipsWonBeyondTheStake() {
        val g = dealt(listOf("10S", "10H", "KD", "9C"))
        g.act(BjAction.STAND); finishDealer(g)
        var result: com.gamesuite.core.GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        val r = result!!
        assertEquals(100, r.scores[0].score)
        assertTrue(r.scores[0].isWinner)
        assertTrue(g.matchOver.value)
        assertFalse(g.bet(10))
    }

    @Test fun aLosingOrEvenSessionHasNoWinnerAndRebuysCountAgainstYou() {
        val g = dealt(listOf("10S", "10H", "6D", "KC"), config = BjConfig(decks = 1, insurance = false))
        g.act(BjAction.STAND); finishDealer(g)
        var result: com.gamesuite.core.GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertEquals(-100, result!!.scores[0].score)
        assertFalse(result!!.scores[0].isWinner)
    }

    @Test fun leavingMidRoundDoesNotScoreTheWageredChipsAsLost() {
        val g = dealt(listOf("10S", "10H", "6D", "KC"), config = BjConfig(decks = 1, insurance = false))
        // the bet is on the felt and the round is undecided
        assertEquals(0, g.netOf(g.s(), 0))
        var result: com.gamesuite.core.GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertEquals(0, result!!.scores[0].score)
    }

    @Test fun twoSeatsTheUniqueLeaderWinsATieDoesNot() {
        val g = dealt(listOf("10S", "10H", "9C", "KD", "6S", "8C"), seats = 2)
        g.act(BjAction.STAND); g.act(BjAction.STAND); finishDealer(g)
        var result: com.gamesuite.core.GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertEquals(listOf(true, false), result!!.scores.map { it.isWinner })
        val t = BjTest.game(BjTest.humans(2), mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        var tie: com.gamesuite.core.GameResult? = null
        t.setOnMatchEnd { tie = it }
        t.leaveSession()
        assertEquals(listOf(false, false), tie!!.scores.map { it.isWinner })
    }

    // ---- fuzz ------------------------------------------------------------------------------------

    @Test fun fuzzRandomLegalPlayNeverBreaksTheTableOrTheChipsOrTheCards() {
        var rounds = 0
        for (seed in 0 until 60) {
            val rnd = Random(seed)
            val seats = 1 + rnd.nextInt(4)
            val players = List(seats) { i ->
                if (i > 0 && rnd.nextBoolean()) PlayerInfo("b$i", "CPU $i", isBot = true) else PlayerInfo("p$i", "P$i")
            }
            val cfg = BjConfig(
                decks = listOf(1, 2, 4, 6)[rnd.nextInt(4)], startingChips = listOf(500, 1000)[rnd.nextInt(2)],
                payout = if (rnd.nextBoolean()) "3:2" else "6:5", hitSoft17 = rnd.nextBoolean(),
                insurance = rnd.nextBoolean(), surrender = rnd.nextBoolean()
            )
            val mode = if (players.count { !it.isBot } > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT
            val g = BjTest.game(players, cfg, mode, seed)
            g.difficulty = CpuDifficulty.entries[seed % 3]
            var steps = 0
            var seedRounds = 0
            while (steps++ < 3000) {
                val s = g.s()
                when {
                    g.botToMove(s) -> g.playBotStep()
                    g.dealerToMove(s) -> g.dealerStep()
                    s.phase == BjPhase.BETTING -> {
                        val seat = s.seats[s.activeSeat]
                        when {
                            seat.stack < BjRules.MIN_BET -> if (rnd.nextInt(4) == 0) g.sitOut() else g.rebuy()
                            else -> g.bet(BjRules.MIN_BET * (1 + rnd.nextInt((seat.stack / BjRules.MIN_BET).coerceAtMost(30))))
                        }
                    }
                    s.phase == BjPhase.INSURANCE -> g.insurance(rnd.nextBoolean())
                    s.phase == BjPhase.PLAYING -> {
                        val legal = g.legalActions().toList()
                        assertTrue(BjAction.HIT in legal && BjAction.STAND in legal)
                        assertTrue(g.act(legal[rnd.nextInt(legal.size)]))
                    }
                    s.phase == BjPhase.ROUND_OVER -> {
                        // chip accounting: each seat's round net equals the sum of its hand credits and insurance minus what it staked
                        s.seats.forEach { seat ->
                            if (seat.hands.isNotEmpty()) {
                                val staked = seat.hands.sumOf { it.bet } + seat.insurance
                                assertEquals(seat.hands.sumOf { it.credit } + seat.insuranceCredit - staked, seat.roundNet)
                            }
                        }
                        rounds++
                        seedRounds++
                        g.nextRound()
                    }
                }
                val cur = g.s()
                cur.seats.forEach { assertTrue("negative stack", it.stack >= 0) }
                assertTrue(cur.seats.all { it.hands.size <= BjRules.MAX_HANDS })
                if (steps % 7 == 0) {
                    val snap = g.snapshot()!!      // card accounting is part of validation
                    val copy = fresh(players, mode)
                    assertTrue("seed $seed step $steps phase ${cur.phase}", copy.restore(snap))
                }
                if (seedRounds >= 20) break
            }
        }
        assertTrue("played $rounds rounds", rounds >= 300)
    }
}
