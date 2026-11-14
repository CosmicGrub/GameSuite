package com.gamesuite.games.sevens

import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.president.PsDeck

/** Structural checks on a [SevensState] before it is trusted (a save being restored, a StateSync a guest received). */
object SevensValidator {
    fun isValid(s: SevensState, roster: List<PlayerInfo>, allowHidden: Boolean): Boolean {
        val n = roster.size
        if (!SevensRules.validSeatCount(n) || s.seats.size != n) return false
        if (!s.seats.indices.all { s.seats[it].playerId == roster[it].playerId }) return false
        if (!allowHidden && !s.seats.indices.all { s.seats[it].isBot == roster[it].isBot }) return false
        if (s.currentSeat !in 0 until n || s.roundNumber < 1) return false
        if (s.event !in SvEvent.ALL) return false
        if (s.eventSeat != null && s.eventSeat !in 0 until n) return false
        if (s.eventCard != null && s.eventCard !in 0 until PsDeck.SIZE) return false
        if (s.winnerSeat != null && s.winnerSeat !in 0 until n) return false
        if (s.wins.any { (id, w) -> w < 0 || roster.none { it.playerId == id } }) return false
        if (s.seats.any { it.passes < 0 || it.roundScore < 0 || it.total < 0 }) return false
        if (s.low.size != 4 || s.high.size != 4) return false
        for (suit in 0 until 4) {
            val lo = s.low[suit]
            val hi = s.high[suit]
            if (lo == 0 && hi == 0) continue
            if (lo !in 1..SevensRules.SEVEN || hi !in SevensRules.SEVEN..13) return false
        }
        // a suit may only be started once the seven of diamonds has opened the round
        if (!s.started && (0 until 4).any { s.low[it] != 0 }) return false
        val table = (0 until 4).flatMap { SevensRules.rowCards(it, s.low, s.high) }
        val real = ArrayList<Int>()
        var hidden = 0
        for (seat in s.seats) for (c in seat.hand) when {
            c in 0 until PsDeck.SIZE -> real += c
            allowHidden && c == SV_HIDDEN_CARD -> hidden++
            else -> return false
        }
        if (real.toSet().size != real.size || real.any { it in table }) return false
        if (real.size + hidden + table.size != PsDeck.SIZE) return false
        when (s.phase) {
            SvPhase.PLAY -> {
                if (s.winnerSeat != null) return false
                if (s.seats.any { it.hand.isEmpty() }) return false
            }
            SvPhase.ROUND_OVER -> {
                val w = s.winnerSeat ?: return false
                if (s.seats[w].hand.isNotEmpty()) return false
                if (s.seats.indices.any { it != w && s.seats[it].hand.isEmpty() }) return false
            }
        }
        return true
    }
}
