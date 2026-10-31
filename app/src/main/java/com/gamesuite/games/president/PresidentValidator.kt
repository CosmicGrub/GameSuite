package com.gamesuite.games.president

import com.gamesuite.core.PlayerInfo

/**
 * Structural checks on a [PresidentState] before it is trusted: a saved game being restored and a
 * StateSync a guest received. [allowHidden] is true for a state a guest holds, where other seats' hands
 * are placeholders; the checks then use hand SIZES (which stay real) for the card count.
 */
object PresidentValidator {
    fun isValid(s: PresidentState, roster: List<PlayerInfo>, allowHidden: Boolean): Boolean {
        val n = roster.size
        if (!PresidentRules.validSeatCount(n) || s.seats.size != n) return false
        if (!s.seats.indices.all { s.seats[it].playerId == roster[it].playerId }) return false
        if (!allowHidden && !s.seats.indices.all { s.seats[it].isBot == roster[it].isBot }) return false
        if (s.currentSeat !in 0 until n || s.startSeat !in 0 until n) return false
        if (s.rules.rounds !in PresidentRuleSet.ROUND_CHOICES || s.roundNumber !in 1..s.rules.rounds) return false
        if (s.event !in PsEvent.ALL) return false
        if (s.eventSeat != null && s.eventSeat !in 0 until n) return false
        if (s.eventSkipped != null && s.eventSkipped !in 0 until n) return false
        if (s.mustInclude != null && s.mustInclude !in 0 until PsDeck.SIZE) return false
        val roleNames = PresidentRole.values().map { it.name }.toSet() + ""
        if (s.seats.any { it.role !in roleNames || it.score < 0 || it.roundPoints < 0 }) return false
        if (s.sessionWins.any { (id, w) -> w < 0 || roster.none { it.playerId == id } }) return false

        // cards: every one of the 52 in exactly one place
        val played = s.playedCards
        if (played.any { it !in 0 until PsDeck.SIZE } || played.toSet().size != played.size) return false
        val handCards = ArrayList<Int>()
        var hidden = 0
        for (seat in s.seats) for (c in seat.hand) {
            when {
                c in 0 until PsDeck.SIZE -> handCards += c
                allowHidden && c == PS_HIDDEN_CARD -> hidden++
                else -> return false
            }
        }
        if (handCards.toSet().size != handCards.size) return false
        if (handCards.any { it in played }) return false
        if (handCards.size + hidden + played.size != PsDeck.SIZE) return false

        // the trick lies on the table: part of the played cards, one rank, one size
        for (play in s.trick) {
            if (play.seat !in 0 until n || play.cards.isEmpty() || play.cards.any { it !in played }) return false
            if (play.cards.map { PresidentRules.rankOf(it) }.toSet().size != 1) return false
        }
        if (s.trick.map { it.cards.size }.toSet().size > 1) return false
        if (s.trick.flatMap { it.cards }.toSet().size != s.trick.sumOf { it.cards.size }) return false
        if (s.passed.any { it !in 0 until n } || s.passed.toSet().size != s.passed.size) return false
        if (s.finishOrder.any { it !in 0 until n } || s.finishOrder.toSet().size != s.finishOrder.size) return false

        for (i in 0 until n) {
            val out = s.seats[i].outPosition
            if (out < -1 || out >= n) return false
            if (out >= 0 && s.finishOrder.getOrNull(out) != i) return false
        }
        when (s.phase) {
            PsPhase.PLAY -> {
                if (s.pendingExchanges.isNotEmpty()) return false
                if (s.seats[s.currentSeat].hand.isEmpty()) return false
                if (s.seats.count { it.hand.isEmpty() } != s.finishOrder.size) return false
                if (s.finishOrder.size >= n - 1) return false
                if (s.trick.isNotEmpty() && s.seats[s.trick.last().seat].let { it.hand.isEmpty() && it.outPosition < 0 }) return false
            }
            PsPhase.EXCHANGE -> {
                if (s.pendingExchanges.isEmpty() || s.trick.isNotEmpty() || s.finishOrder.isNotEmpty()) return false
                for (t in s.pendingExchanges) {
                    if (t.from !in 0 until n || t.to !in 0 until n || t.from == t.to || t.count !in 1..2) return false
                    if (s.seats[t.from].hand.size < t.count) return false
                }
                if (s.pendingExchanges.none { it.from == s.currentSeat }) return false
            }
            PsPhase.ROUND_OVER, PsPhase.SESSION_OVER -> {
                if (s.finishOrder.size != n) return false
                if (s.phase == PsPhase.ROUND_OVER && s.roundNumber >= s.rules.rounds) return false
                if (s.phase == PsPhase.SESSION_OVER && s.roundNumber != s.rules.rounds) return false
            }
        }
        for (e in s.exchanges) {
            if (e.from !in 0 until n || e.to !in 0 until n) return false
            if (e.cards.any { it != PS_HIDDEN_CARD && it !in 0 until PsDeck.SIZE }) return false
        }
        return true
    }
}
