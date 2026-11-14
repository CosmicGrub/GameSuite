package com.gamesuite.games.sevens

import com.gamesuite.games.president.PsDeck
import com.gamesuite.games.president.cid
import com.gamesuite.games.president.cids
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SevensRulesTest {
    private fun table(vararg cards: String): Pair<List<Int>, List<Int>> {
        val low = MutableList(4) { 0 }
        val high = MutableList(4) { 0 }
        for (c in cards) {
            val id = cid(c)
            val s = SevensRules.suitIndex(id)
            val r = SevensRules.rankIndex(id)
            if (low[s] == 0) { low[s] = r; high[s] = r } else { low[s] = minOf(low[s], r); high[s] = maxOf(high[s], r) }
        }
        return low to high
    }

    @Test fun aceIsLowAndKingIsHigh() {
        assertEquals(1, SevensRules.rankIndex(cid("AS")))
        assertEquals(13, SevensRules.rankIndex(cid("KS")))
        assertEquals(7, SevensRules.rankIndex(cid("7H")))
        assertEquals(cid("AS"), SevensRules.cardId(3, 1))
        assertEquals(cid("KC"), SevensRules.cardId(0, 13))
        assertEquals(cid("7D"), SevensRules.SEVEN_OF_DIAMONDS)
    }

    @Test fun onlyTheSevenOfDiamondsOpensTheRound() {
        val (low, high) = table()
        assertTrue(SevensRules.isPlayable(cid("7D"), low, high))
        assertFalse(SevensRules.isPlayable(cid("7H"), low, high))
        assertFalse(SevensRules.isPlayable(cid("6D"), low, high))
        assertEquals(SevensReject.MUST_START_WITH_SEVEN_OF_DIAMONDS, SevensRules.explainPlay(cid("7H"), listOf(cid("7H")), low, high))
    }

    @Test fun anySevenStartsItsSuitAfterTheOpening() {
        val (low, high) = table("7D")
        assertTrue(SevensRules.isPlayable(cid("7H"), low, high))
        assertTrue(SevensRules.isPlayable(cid("7S"), low, high))
        assertTrue(SevensRules.isPlayable(cid("6D"), low, high))
        assertTrue(SevensRules.isPlayable(cid("8D"), low, high))
        assertFalse(SevensRules.isPlayable(cid("6H"), low, high))   // hearts not started
        assertFalse(SevensRules.isPlayable(cid("9D"), low, high))   // not adjacent
        assertFalse(SevensRules.isPlayable(cid("7D"), low, high))   // already down
    }

    @Test fun rowsGrowUpToTheKingAndDownToTheAce() {
        val (low, high) = table("7D", "8D", "9D", "6D", "5D")
        assertTrue(SevensRules.isPlayable(cid("10D"), low, high))
        assertTrue(SevensRules.isPlayable(cid("4D"), low, high))
        assertFalse(SevensRules.isPlayable(cid("JD"), low, high))
        val (l2, h2) = table("7D")
        assertEquals(0, SevensRules.openEnds(0, l2, h2).first)
        val full = table("7D", "6D", "5D", "4D", "3D", "2D", "AD", "8D", "9D", "10D", "JD", "QD", "KD")
        val (ln, hn) = SevensRules.openEnds(1, full.first, full.second)
        assertEquals(0 to 0, ln to hn)
    }

    @Test fun pointsAreAceOneToKingThirteen() {
        assertEquals(1, SevensRules.points(cid("AS")))
        assertEquals(10, SevensRules.points(cid("10C")))
        assertEquals(11, SevensRules.points(cid("JH")))
        assertEquals(13, SevensRules.points(cid("KD")))
        assertEquals(91, (0 until 13).sumOf { SevensRules.points(SevensRules.cardId(0, it + 1)) })
    }

    @Test fun handsSortBySuitThenRank() {
        val sorted = SevensRules.sortHand(cids("KS", "AC", "7D", "2C", "3S"))
        assertEquals(cids("AC", "2C", "7D", "3S", "KS"), sorted)
    }

    // ---- flow ----------------------------------------------------------------------------------

    private fun seats(vararg hands: List<String>) = hands.mapIndexed { i, h ->
        SvSeat("p${i + 1}", "P${i + 1}", false, SevensRules.sortHand(cids(*h.toTypedArray())))
    }

    private fun state(vararg hands: List<String>, tbl: Pair<List<Int>, List<Int>> = table("7D"), cur: Int = 0, rules: SevensRuleSet = SevensRuleSet()) =
        SevensState(seats(*hands), rules, currentSeat = cur, low = tbl.first, high = tbl.second)

    @Test fun theDealGivesEverySeatCardsAndTheSevenOfDiamondsStarts() {
        for (n in 3..6) repeat(20) { seed ->
            val s = SevensFlow.newTable(List(n) { SvSeat("p$it", "P$it", false) }, SevensRuleSet(), Random(seed))
            assertEquals(52, s.seats.sumOf { it.hand.size })
            assertTrue(SevensRules.SEVEN_OF_DIAMONDS in s.seats[s.currentSeat].hand)
            assertTrue(SevensFlow.conserved(s))
            assertTrue(s.seats.all { it.hand.size == 52 / n || it.hand.size == 52 / n + 1 })
        }
    }

    @Test fun playingExtendsTheRowAndPassesTheTurn() {
        var s = state(listOf("8D", "2H"), listOf("6D", "9S"), listOf("7H", "KC"))
        s = SevensFlow.play(s, 0, cid("8D"))!!
        assertEquals(8, s.high[1])
        assertEquals(1, s.currentSeat)
        s = SevensFlow.play(s, 1, cid("6D"))!!
        assertEquals(6, s.low[1])
        s = SevensFlow.play(s, 2, cid("7H"))!!
        assertEquals(7, s.low[2])
        assertEquals(SvEvent.PLAY, s.event)
        assertEquals(cid("7H"), s.eventCard)
    }

    @Test fun anIllegalPlayOrWrongTurnChangesNothing() {
        val s = state(listOf("8D", "2H"), listOf("6D", "9S"), listOf("7H", "KC"))
        assertNull(SevensFlow.play(s, 0, cid("2H")))   // not next to anything
        assertNull(SevensFlow.play(s, 1, cid("6D")))   // not their turn
        assertNull(SevensFlow.play(s, 0, cid("6D")))   // not in hand
    }

    @Test fun youMayPassOnlyWithNothingToPlay() {
        val s = state(listOf("8D", "2H"), listOf("2C", "9S"), listOf("7H", "KC"))
        assertNull(SevensFlow.pass(s, 0))               // 8D is playable
        val stuck = s.copy(currentSeat = 1)
        val after = SevensFlow.pass(stuck, 1)!!
        assertEquals(1, after.seats[1].passes)
        assertEquals(2, after.currentSeat)
        assertEquals(SvEvent.PASS, after.event)
    }

    @Test fun theLastCardWinsAndTheOthersScoreTheirRemainingPoints() {
        var s = state(listOf("8D"), listOf("KC", "2H"), listOf("QS", "AS"))
        s = SevensFlow.play(s, 0, cid("8D"))!!
        assertEquals(SvPhase.ROUND_OVER, s.phase)
        assertEquals(0, s.winnerSeat)
        assertEquals(listOf(0, 15, 13), s.seats.map { it.roundScore })
        assertEquals(listOf(0, 15, 13), s.seats.map { it.total })
        assertEquals(mapOf("p1" to 1), s.wins)
        assertNull(SevensFlow.play(s, 1, cid("KC")))
    }

    @Test fun thePassPenaltyAddsFivePointsPerPassWhenOn() {
        var s = state(listOf("8D"), listOf("KC", "2H"), listOf("QS", "AS"), rules = SevensRuleSet(passPenalty = true))
        s = s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 1) x.copy(passes = 2) else x })
        s = SevensFlow.play(s, 0, cid("8D"))!!
        assertEquals(listOf(0, 25, 13), s.seats.map { it.roundScore })
        var t = state(listOf("8D"), listOf("KC", "2H"), listOf("QS", "AS"), rules = SevensRuleSet(passPenalty = false))
        t = t.copy(seats = t.seats.mapIndexed { i, x -> if (i == 1) x.copy(passes = 2) else x })
        t = SevensFlow.play(t, 0, cid("8D"))!!
        assertEquals(listOf(0, 15, 13), t.seats.map { it.roundScore })
    }

    @Test fun nextRoundKeepsTotalsAndWins() {
        var s = state(listOf("8D"), listOf("KC", "2H"), listOf("QS", "AS"))
        s = SevensFlow.play(s, 0, cid("8D"))!!
        val n = SevensFlow.nextRound(s, Random(2))!!
        assertEquals(2, n.roundNumber)
        assertEquals(listOf(0, 15, 13), n.seats.map { it.total })
        assertEquals(mapOf("p1" to 1), n.wins)
        assertTrue(n.seats.all { it.passes == 0 && it.roundScore == 0 })
        assertTrue(SevensFlow.conserved(n))
        assertNull(SevensFlow.nextRound(n, Random(2)))
    }

    // ---- computers ------------------------------------------------------------------------------

    @Test fun hardPlaysAnAceOrKingThatOpensNothingBeforeAnOpeningCard() {
        // table: hearts 7..13 laid, so KH is not available; spades 7..8 -> options: 9S opens 10S for others
        val s = state(listOf("2C", "9S", "AD", "5H"), listOf("3C", "10S", "4H", "6D"), listOf("QD", "KC", "JS", "8H"),
            tbl = table("7D", "6D", "5D", "4D", "3D", "2D", "7S", "8S"))
        // playable for seat 0: AD (extends diamonds to ace: opens nothing), 9S (opens 10S)
        val choice = SevensBot.choose(s, 0, CpuDifficulty.HARD, Random(1))
        assertEquals(cid("AD"), choice)
        assertEquals(cid("AD"), SevensBot.choose(s, 0, CpuDifficulty.MEDIUM, Random(1)))
    }

    @Test fun everyLevelPassesOnlyWhenNothingIsPlayable() {
        for (seed in 0 until 100) {
            val n = 3 + seed % 4
            var s = SevensFlow.newTable(List(n) { SvSeat("p$it", "P$it", false) }, SevensRuleSet(), Random(seed))
            var steps = 0
            val rnd = Random(seed)
            while (s.phase == SvPhase.PLAY && steps++ < 500) {
                val seat = s.currentSeat
                for (level in CpuDifficulty.values()) {
                    val choice = SevensBot.choose(s, seat, level, Random(steps))
                    val playable = SevensRules.playableCards(s.seats[seat].hand, s.low, s.high)
                    if (choice == null) assertTrue(playable.isEmpty()) else assertTrue(choice in playable)
                }
                val c = SevensBot.choose(s, seat, CpuDifficulty.values()[rnd.nextInt(3)], rnd)
                s = (if (c == null) SevensFlow.pass(s, seat) else SevensFlow.play(s, seat, c))!!
                assertTrue(SevensFlow.conserved(s))
            }
            assertEquals(SvPhase.ROUND_OVER, s.phase)
        }
    }

    /** Points the others hold at the end of a round, minus the points the lone [levelA] seat holds (positive = A is better). */
    private fun edge(levelA: CpuDifficulty, levelB: CpuDifficulty, rounds: Int, seats: Int = 4): Double {
        var d = 0.0
        for (seed in 0 until rounds) {
            val rot = seed % seats
            val ids = List(seats) { (it + rot) % seats }
            var s = SevensFlow.newTable(ids.map { SvSeat(if (it == 0) "a" else "b$it", "x", true) }, SevensRuleSet(), Random(seed))
            val rnd = Random(seed + 7777)
            var steps = 0
            while (s.phase == SvPhase.PLAY && steps++ < 2000) {
                val seat = s.currentSeat
                val level = if (s.seats[seat].playerId == "a") levelA else levelB
                val c = SevensBot.choose(s, seat, level, rnd)
                s = (if (c == null) SevensFlow.pass(s, seat) else SevensFlow.play(s, seat, c))!!
            }
            assertEquals(SvPhase.ROUND_OVER, s.phase)
            d += s.seats.filter { it.playerId != "a" }.sumOf { it.roundScore } / (seats - 1.0) - s.seats.first { it.playerId == "a" }.roundScore
        }
        return d / rounds
    }

    @Test fun harderComputersLeaveFewerPointsInTheirHands() {
        // fixed seeds, so reproducible. Measured: hard-v-medium about +2.5 points a round, hard-v-easy about +4.4, medium-v-easy about +1.
        val hardVsEasy = edge(CpuDifficulty.HARD, CpuDifficulty.EASY, 4000)
        val mediumVsEasy = edge(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 4000)
        val hardVsMedium = edge(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 8000)
        assertTrue("hard v easy $hardVsEasy", hardVsEasy > 2.5)
        assertTrue("medium v easy $mediumVsEasy", mediumVsEasy > 0.4)
        assertTrue("hard v medium $hardVsMedium", hardVsMedium > 1.2)
        assertTrue(edge(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 4000, seats = 6) > 0.5)
        assertTrue(edge(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 4000, seats = 3) > 0.5)
    }

    @Test fun deckAndTableAlwaysAddUpToFiftyTwo() {
        assertEquals(52, PsDeck.SIZE)
    }
}
