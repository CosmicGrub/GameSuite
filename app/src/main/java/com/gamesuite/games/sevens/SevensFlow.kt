package com.gamesuite.games.sevens

import com.gamesuite.games.president.PsDeck
import kotlin.random.Random

/** The pure transitions of Sevens: deal, play, pass, next round. [SevensGame] adds undo, networking and bots. */
object SevensFlow {
    fun newTable(roster: List<SvSeat>, rules: SevensRuleSet, random: Random): SevensState =
        deal(roster.map { it.copy(hand = emptyList(), passes = 0, roundScore = 0, total = 0) }, rules, 1, emptyMap(), random, 0)

    fun deal(base: List<SvSeat>, rules: SevensRuleSet, roundNumber: Int, wins: Map<String, Int>, random: Random, serial: Int): SevensState {
        val n = base.size
        require(SevensRules.validSeatCount(n)) { "Sevens needs 3 to 6 seats" }
        val deck = (0 until PsDeck.SIZE).toMutableList().also { it.shuffle(random) }
        val hands = List(n) { mutableListOf<Int>() }
        deck.forEachIndexed { i, id -> hands[i % n] += id }
        val seats = base.mapIndexed { i, s -> s.copy(hand = SevensRules.sortHand(hands[i]), passes = 0, roundScore = 0) }
        val starter = seats.indexOfFirst { SevensRules.SEVEN_OF_DIAMONDS in it.hand }
        return SevensState(
            seats = seats, rules = rules, currentSeat = starter, roundNumber = roundNumber, wins = wins,
            event = SvEvent.DEAL, eventSerial = serial + 1
        )
    }

    fun play(s: SevensState, seat: Int, cardId: Int): SevensState? {
        if (s.phase != SvPhase.PLAY || seat != s.currentSeat) return null
        val me = s.seats[seat]
        if (SevensRules.explainPlay(cardId, me.hand, s.low, s.high) != SevensReject.NONE) return null
        val suit = SevensRules.suitIndex(cardId)
        val r = SevensRules.rankIndex(cardId)
        val low = s.low.toMutableList()
        val high = s.high.toMutableList()
        if (low[suit] == 0) { low[suit] = r; high[suit] = r } else if (r < low[suit]) low[suit] = r else high[suit] = r
        val hand = me.hand - cardId
        val seats = s.seats.mapIndexed { i, st -> if (i == seat) st.copy(hand = hand) else st }
        val base = s.copy(
            seats = seats, low = low, high = high, event = SvEvent.PLAY, eventSeat = seat, eventCard = cardId,
            eventSerial = s.eventSerial + 1
        )
        if (hand.isNotEmpty()) return base.copy(currentSeat = (seat + 1) % s.seats.size)
        // the last card: the round is won, the rest count their remaining cards
        val scored = seats.map { st ->
            val penalty = if (s.rules.passPenalty) st.passes * SevensRules.PASS_PENALTY else 0
            val pts = st.hand.sumOf { SevensRules.points(it) } + penalty
            st.copy(roundScore = pts, total = st.total + pts)
        }
        val winner = seats[seat]
        return base.copy(
            seats = scored, phase = SvPhase.ROUND_OVER, winnerSeat = seat,
            wins = s.wins + (winner.playerId to ((s.wins[winner.playerId] ?: 0) + 1)),
            event = SvEvent.ROUND_END
        )
    }

    fun pass(s: SevensState, seat: Int): SevensState? {
        if (s.phase != SvPhase.PLAY || seat != s.currentSeat) return null
        val me = s.seats[seat]
        if (SevensRules.playableCards(me.hand, s.low, s.high).isNotEmpty()) return null // a playable card must be played
        val seats = s.seats.mapIndexed { i, st -> if (i == seat) st.copy(passes = st.passes + 1) else st }
        return s.copy(
            seats = seats, currentSeat = (seat + 1) % s.seats.size, event = SvEvent.PASS, eventSeat = seat, eventCard = null,
            eventSerial = s.eventSerial + 1
        )
    }

    fun nextRound(s: SevensState, random: Random): SevensState? {
        if (s.phase != SvPhase.ROUND_OVER) return null
        return deal(s.seats, s.rules, s.roundNumber + 1, s.wins, random, s.eventSerial)
    }

    fun conserved(s: SevensState): Boolean {
        val table = (0 until 4).flatMap { SevensRules.rowCards(it, s.low, s.high) }
        val all = s.seats.flatMap { it.hand } + table
        return all.size == PsDeck.SIZE && all.toSet() == (0 until PsDeck.SIZE).toSet()
    }
}
