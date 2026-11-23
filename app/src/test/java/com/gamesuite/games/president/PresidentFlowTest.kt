package com.gamesuite.games.president

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Scripted rounds: each test builds the hands it needs and plays them move by move. */
class PresidentFlowTest {
    private fun st(
        hands: List<List<String>>, current: Int = 0, rules: PresidentRuleSet = PresidentRuleSet(),
        trick: List<PsTrickPlay> = emptyList(), revolution: Boolean = false, mustInclude: Int? = null,
        passed: List<Int> = emptyList(), roundNumber: Int = 1, scores: List<Int>? = null
    ) = PresidentState(
        seats = hands.mapIndexed { i, h ->
            PsSeat("p${i + 1}", "P${i + 1}", false, PresidentRules.sortHand(cids(*h.toTypedArray())), score = scores?.get(i) ?: 0)
        },
        rules = rules, currentSeat = current, trick = trick, revolution = revolution, mustInclude = mustInclude,
        passed = passed, roundNumber = roundNumber, startSeat = current
    )

    private fun PresidentState.play(seat: Int, vararg cards: String): PresidentState =
        PresidentFlow.play(this, seat, cids(*cards)) ?: error("illegal play ${cards.toList()} by $seat")

    private fun PresidentState.pass(seat: Int): PresidentState =
        PresidentFlow.pass(this, seat) ?: error("illegal pass by $seat")

    @Test fun aHigherSingleWinsTheTurnAndPlayMovesOn() {
        var s = st(listOf(listOf("5C", "9D"), listOf("7H", "KD"), listOf("4S", "AS")))
        s = s.play(0, "5C")
        assertEquals(1, s.currentSeat)
        assertEquals(cids("5C"), s.top!!.cards)
        s = s.play(1, "7H")
        assertEquals(2, s.currentSeat)
        assertEquals(1, s.top!!.seat)
    }

    @Test fun aLowerOrWrongSizePlayChangesNothing() {
        val s = st(listOf(listOf("9C", "9D"), listOf("5H", "KD", "KS"), listOf("4S", "AS")), current = 1,
            trick = listOf(PsTrickPlay(0, cids("9C", "9D"))))
        assertNull(PresidentFlow.play(s, 1, cids("5H")))          // wrong count
        assertNull(PresidentFlow.play(s, 2, cids("AS")))          // not their turn
        assertNull(PresidentFlow.play(s, 1, cids("KD", "AS")))    // not in hand
        assertNotNull(PresidentFlow.play(s, 1, cids("KD", "KS"))) // a higher pair
    }

    @Test fun everyonePassingGivesTheTrickToTheLastPlayer() {
        var s = st(listOf(listOf("5C", "9D"), listOf("4H", "KD"), listOf("4S", "AS")))
        s = s.play(0, "5C")
        s = s.pass(1)
        assertEquals(2, s.currentSeat)
        s = s.pass(2)
        assertTrue(s.trick.isEmpty())
        assertEquals(0, s.currentSeat)
        assertTrue(s.passed.isEmpty())
        assertEquals(PsEvent.PASS, s.event)
        assertTrue(s.eventCleared)
    }

    @Test fun theLeaderCannotPass() {
        val s = st(listOf(listOf("5C"), listOf("4H"), listOf("4S")))
        assertNull(PresidentFlow.pass(s, 0))
    }

    @Test fun aPassDoesNotLockYouOutOfTheTrickAfterANewPlay() {
        var s = st(listOf(listOf("5C", "9D", "QS"), listOf("6H", "KD"), listOf("7S", "AS", "3D")))
        s = s.play(0, "5C").pass(1)   // 1 passes
        s = s.play(2, "7S")           // 2 beats; 1 may play again
        assertEquals(0, s.currentSeat)
        s = s.pass(0)
        assertEquals(1, s.currentSeat)
        s = s.play(1, "KD")
        assertEquals(1, s.top!!.seat)
    }

    @Test fun aTwoWinsTheTrickAtOnceAndTheSamePlayerLeadsAgain() {
        var s = st(listOf(listOf("5C", "9D"), listOf("2H", "KD"), listOf("4S", "AS")))
        s = s.play(0, "5C").play(1, "2H")
        assertTrue(s.trick.isEmpty())
        assertEquals(1, s.currentSeat)
        assertTrue(s.eventCleared)
        assertEquals(cids("2H"), s.eventCards)
    }

    @Test fun aTieSkipsTheNextPlayer() {
        var s = st(listOf(listOf("5C", "9D", "QS"), listOf("5H", "KD"), listOf("7S", "AS")))
        s = s.play(0, "5C")
        s = s.play(1, "5H")
        assertEquals(2, s.eventSkipped)
        assertEquals(0, s.currentSeat) // seat 2 was skipped
        assertEquals(listOf(2), s.passed)
        s = s.pass(0)
        // seat 0 and seat 2 have both passed: seat 1 takes the trick
        assertTrue(s.trick.isEmpty())
        assertEquals(1, s.currentSeat)
    }

    @Test fun withTiesOffAnEqualRankIsRefused() {
        val s = st(listOf(listOf("5C", "9D"), listOf("5H", "KD"), listOf("7S", "AS")), rules = PresidentRuleSet(ties = false),
            current = 1, trick = listOf(PsTrickPlay(0, cids("5C"))))
        assertNull(PresidentFlow.play(s, 1, cids("5H")))
    }

    @Test fun aTieAmongTwoRemainingPlayersWinsTheTrick() {
        // seats 0 and 1 still hold cards; seat 2 is already out
        var s = st(listOf(listOf("5C", "9D"), listOf("5H", "KD"), emptyList()))
        s = s.copy(finishOrder = listOf(2), seats = s.seats.mapIndexed { i, x -> if (i == 2) x.copy(outPosition = 0) else x })
        s = s.play(0, "5C").play(1, "5H")
        assertTrue(s.trick.isEmpty())
        assertEquals(1, s.currentSeat)
    }

    @Test fun aFourOfAKindStartsARevolutionWhichAnotherEndsAgain() {
        var s = st(listOf(listOf("7C", "7D", "7H", "7S", "2C"), listOf("8C", "8D", "8H", "8S", "3C"), listOf("4S", "AS", "9C")))
        s = s.play(0, "7C", "7D", "7H", "7S")
        assertTrue(s.revolution)
        assertTrue(s.eventRevolution)
        // four 8s are higher than four 7s normally, but under the revolution they are lower
        assertNull(PresidentFlow.play(s, 1, cids("8C", "8D", "8H", "8S")))
        s = s.pass(1).pass(2)
        assertEquals(0, s.currentSeat)
        // under the revolution the 3 is the top rank: it wins the trick at once
        s = s.copy(currentSeat = 1)
        s = s.play(1, "3C")
        assertTrue(s.trick.isEmpty())
    }

    @Test fun revolutionsOffMeansAFourIsJustAFour() {
        var s = st(listOf(listOf("7C", "7D", "7H", "7S", "2C"), listOf("8C", "8D", "8H", "8S", "3C"), listOf("4S", "AS", "9C")),
            rules = PresidentRuleSet(revolutions = false))
        s = s.play(0, "7C", "7D", "7H", "7S")
        assertFalse(s.revolution)
        assertNotNull(PresidentFlow.play(s, 1, cids("8C", "8D", "8H", "8S")))
    }

    @Test fun twoFoursFlipTheOrderBackAgain() {
        var s = st(listOf(listOf("7C", "7D", "7H", "7S", "2C"), listOf("5C", "5D", "5H", "5S", "3C"), listOf("4S", "AS", "9C")))
        s = s.play(0, "7C", "7D", "7H", "7S")
        assertTrue(s.revolution)
        // under the revolution fives beat sevens; playing them flips back
        s = s.play(1, "5C", "5D", "5H", "5S")
        assertFalse(s.revolution)
    }

    @Test fun goingOutRecordsThePlaceAndTheRoundEndsWithOneLeft() {
        var s = st(listOf(listOf("5C"), listOf("6H", "KD"), listOf("7S", "AS")))
        s = s.play(0, "5C")
        assertEquals(0, s.seats[0].outPosition)
        assertEquals(listOf(0), s.finishOrder)
        assertEquals(PsPhase.PLAY, s.phase)
        s = s.play(1, "6H").play(2, "7S")
        s = s.play(1, "KD") // 1 goes out second, 2 is last
        assertEquals(PsPhase.ROUND_OVER, s.phase)
        assertEquals(listOf(0, 1, 2), s.finishOrder)
        assertEquals(listOf(2, 1, 0), s.seats.map { it.roundPoints })
        assertEquals(2, s.seats[2].outPosition)
        assertEquals(PsEvent.ROUND_END, s.event)
    }

    @Test fun whenTheWinnerOfATrickWentOutTheNextPlayerLeads() {
        var s = st(listOf(listOf("5C"), listOf("4H", "KD"), listOf("4S", "AS", "9C")))
        s = s.play(0, "5C")   // 0 is out; trick top is 0's 5
        s = s.pass(1)
        s = s.pass(2)
        assertTrue(s.trick.isEmpty())
        assertEquals(1, s.currentSeat) // the next seat with cards, not the seat that went out
    }

    @Test fun goingOutOnATrickKillerPassesTheLeadOn() {
        var s = st(listOf(listOf("2C"), listOf("4H", "KD"), listOf("4S", "AS", "9C")))
        s = s.play(0, "2C")
        assertEquals(1, s.currentSeat)
        assertTrue(s.trick.isEmpty())
    }

    @Test fun roundPointsAddUpOverTheSession() {
        var s = st(listOf(listOf("5C"), listOf("6H"), listOf("7S", "AS")), scores = listOf(1, 5, 0))
        s = s.play(0, "5C").play(1, "6H")
        assertEquals(PsPhase.ROUND_OVER, s.phase)
        assertEquals(listOf(3, 6, 0), s.seats.map { it.score })
    }

    @Test fun theLastRoundEndsTheSessionAndCreditsTheWinner() {
        var s = st(listOf(listOf("5C"), listOf("6H"), listOf("7S", "AS")), roundNumber = 5, scores = listOf(8, 2, 3))
        s = s.play(0, "5C").play(1, "6H")
        assertEquals(PsPhase.SESSION_OVER, s.phase)
        assertEquals(PsEvent.SESSION_END, s.event)
        assertEquals(mapOf("p1" to 1), s.sessionWins)
    }

    @Test fun aSharedTopScoreCreditsNobody() {
        var s = st(listOf(listOf("5C"), listOf("6H"), listOf("7S", "AS")), roundNumber = 5, scores = listOf(5, 6, 4))
        s = s.play(0, "5C").play(1, "6H") // +2, +1, +0 -> 7, 7, 4: tie at the top
        assertEquals(PsPhase.SESSION_OVER, s.phase)
        assertTrue(s.sessionWins.isEmpty())
        var t = st(listOf(listOf("5C"), listOf("6H"), listOf("7S", "AS")), roundNumber = 5, scores = listOf(5, 8, 4))
        t = t.play(0, "5C").play(1, "6H") // 7, 9, 4
        assertEquals(mapOf("p2" to 1), t.sessionWins)
    }

    // ---- dealing and exchange -----------------------------------------------------------------

    private fun roster(n: Int, botFrom: Int = n) = List(n) { PsSeat("p${it + 1}", "P${it + 1}", it >= botFrom) }

    @Test fun theFirstRoundIsLedByTheThreeOfClubsHolder() {
        repeat(30) { i ->
            val s = PresidentFlow.newSession(roster(4), PresidentRuleSet(), Random(i), CpuDifficulty.MEDIUM)
            assertTrue(PsDeck.THREE_OF_CLUBS in s.seats[s.currentSeat].hand)
            assertEquals(PsDeck.THREE_OF_CLUBS, s.mustInclude)
            assertEquals(PsPhase.PLAY, s.phase)
            assertTrue(PresidentFlow.conserved(s))
        }
    }

    @Test fun theFirstPlayMustContainTheThreeOfClubs() {
        val s = PresidentFlow.newSession(roster(3), PresidentRuleSet(), Random(1), CpuDifficulty.MEDIUM)
        val seat = s.currentSeat
        val other = s.seats[seat].hand.first { PresidentRules.rankOf(it) != PsDeck.card(PsDeck.THREE_OF_CLUBS).rank }
        assertNull(PresidentFlow.play(s, seat, listOf(other)))
        val after = PresidentFlow.play(s, seat, listOf(PsDeck.THREE_OF_CLUBS))
        assertNotNull(after)
        assertNull(after!!.mustInclude)
    }

    @Test fun cardsAreDealtRoundRobinWithTheRemainderToTheFirstSeats() {
        assertEquals(listOf(17, 17, 18), PresidentFlow.newSession(roster(3), PresidentRuleSet(), Random(1), CpuDifficulty.EASY).seats.map { it.hand.size }.sorted())
        assertEquals(listOf(8, 8, 9, 9, 9, 9), PresidentFlow.newSession(roster(6), PresidentRuleSet(), Random(1), CpuDifficulty.EASY).seats.map { it.hand.size }.sorted())
        assertEquals(52, PresidentFlow.newSession(roster(5), PresidentRuleSet(), Random(1), CpuDifficulty.EASY).seats.sumOf { it.hand.size })
    }

    @Test fun theScumHandsItsBestTwoCardsToThePresidentWhoGivesTwoBack() {
        // a finished 4-seat round: seat 2 President, 0 Vice, 3 Vice-Scum, 1 Scum
        val order = listOf(2, 0, 3, 1)
        val s = PresidentFlow.deal(roster(4), PresidentRuleSet(), 2, order, emptyMap(), Random(7), CpuDifficulty.MEDIUM, 0)
        assertEquals(PsPhase.EXCHANGE, s.phase)
        assertEquals(2, s.pendingExchanges.size)
        assertEquals(PresidentRole.PRESIDENT.name, s.seats[2].role)
        assertEquals(PresidentRole.SCUM.name, s.seats[1].role)
        // the scum's two best cards moved to the president automatically
        val auto = s.exchanges.first { it.auto && it.to == 2 }
        assertEquals(1, auto.from)
        assertEquals(2, auto.cards.size)
        assertTrue(auto.cards.all { it in s.seats[2].hand })
        assertTrue(auto.cards.none { it in s.seats[1].hand })
        // the scum kept nothing better than what it gave (compared on base power)
        val minGiven = auto.cards.minOf { PresidentRules.basePower(PresidentRules.rankOf(it)) }
        assertTrue(s.seats[1].hand.all { PresidentRules.basePower(PresidentRules.rankOf(it)) <= minGiven })
        // the president (a person) picks two cards to give
        val task = s.pendingExchanges.first { it.from == 2 }
        assertEquals(2, task.count)
        val gift = s.seats[2].hand.take(2)
        val after = PresidentFlow.give(s, 2, gift)!!
        assertTrue(gift.all { it in after.seats[1].hand })
        assertEquals(1, after.pendingExchanges.size)
        // the vice pair: seat 0 (Vice) gets one card from seat 3 (Vice-Scum), gives one back
        val done = PresidentFlow.give(after, 0, listOf(after.seats[0].hand.first()))!!
        assertEquals(PsPhase.PLAY, done.phase)
        assertEquals(2, done.currentSeat) // the President leads
        assertTrue(PresidentFlow.conserved(done))
        assertEquals(listOf(13, 13, 13, 13), done.seats.map { it.hand.size })
    }

    @Test fun aGiftOfTheWrongSizeOrCardsNotHeldIsRefused() {
        val s = PresidentFlow.deal(roster(3), PresidentRuleSet(), 2, listOf(0, 1, 2), emptyMap(), Random(3), CpuDifficulty.MEDIUM, 0)
        assertEquals(PsPhase.EXCHANGE, s.phase)
        assertEquals(1, s.pendingExchanges.single().count) // three seats swap one card
        val hand = s.seats[0].hand
        assertNull(PresidentFlow.give(s, 0, hand.take(2)))
        assertNull(PresidentFlow.give(s, 0, emptyList()))
        assertNull(PresidentFlow.give(s, 0, listOf(s.seats[2].hand.first { it !in hand })))
        assertNull(PresidentFlow.give(s, 1, listOf(s.seats[1].hand.first())))
        assertNotNull(PresidentFlow.give(s, 0, hand.take(1)))
    }

    @Test fun computersMakeTheirOwnGiftsAtOnce() {
        val r = roster(4, botFrom = 0) // everyone is a computer
        val s = PresidentFlow.deal(r, PresidentRuleSet(), 2, listOf(0, 1, 2, 3), emptyMap(), Random(9), CpuDifficulty.HARD, 0)
        assertEquals(PsPhase.PLAY, s.phase)
        assertTrue(s.pendingExchanges.isEmpty())
        assertEquals(4, s.exchanges.size)
        assertEquals(0, s.currentSeat)
        assertTrue(PresidentFlow.conserved(s))
    }

    @Test fun withExchangeOffNobodySwaps() {
        val s = PresidentFlow.deal(roster(4), PresidentRuleSet(exchange = false), 2, listOf(3, 2, 1, 0), emptyMap(), Random(2), CpuDifficulty.MEDIUM, 0)
        assertEquals(PsPhase.PLAY, s.phase)
        assertTrue(s.exchanges.isEmpty())
        assertEquals(3, s.currentSeat) // still the previous President who leads
        assertNull(s.mustInclude)
        assertEquals(PresidentRole.PRESIDENT.name, s.seats[3].role)
    }

    @Test fun nextRoundKeepsScoresAndRolesAndRestartClearsThem() {
        var s = st(listOf(listOf("5C"), listOf("6H"), listOf("7S", "AS")), roundNumber = 1, scores = listOf(0, 0, 0))
        s = s.play(0, "5C").play(1, "6H")
        val n = PresidentFlow.nextRound(s, Random(4), CpuDifficulty.MEDIUM)!!
        assertEquals(2, n.roundNumber)
        assertEquals(listOf(2, 1, 0), n.seats.map { it.score })
        assertEquals(PresidentRole.PRESIDENT.name, n.seats[0].role)
        assertEquals(PresidentRole.SCUM.name, n.seats[2].role)
        assertNull(PresidentFlow.nextRound(n, Random(4), CpuDifficulty.MEDIUM)) // an unfinished round cannot be skipped
        val over = n.copy(phase = PsPhase.SESSION_OVER, roundNumber = n.rules.rounds, finishOrder = listOf(0, 1, 2))
        val fresh = PresidentFlow.restartSession(over, Random(5), CpuDifficulty.MEDIUM)!!
        assertEquals(1, fresh.roundNumber)
        assertEquals(listOf(0, 0, 0), fresh.seats.map { it.score })
        assertEquals(PsDeck.THREE_OF_CLUBS, fresh.mustInclude)
    }
}
