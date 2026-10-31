package com.gamesuite.games.sevens

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The computer players of Sevens.
 *
 * EASY plays any playable card at random. MEDIUM prefers cards that give the others nothing new to play
 * (the ends of a row, the ace and the king) and cards that open a card it holds itself. HARD also counts
 * the table: every card it can see neither on the table nor in its own hand is in an opponent's hand, so it
 * knows exactly which card each play hands to someone else AND how long a run of opponents' cards sits
 * behind it. It holds back the cards that keep those runs locked (making the others pass), keeps a chain of
 * its own ready to run, keeps its options open so it is never forced to pass, and takes no risk with a
 * player who is nearly out.
 *
 * A bot only ever picks from [SevensRules.playableCards], so it never plays illegally, and it passes only
 * when that list is empty (the rules force a playable card to be played).
 */
object SevensBot {
    /** The card to play, or null to pass (only when nothing is playable). */
    fun choose(s: SevensState, seat: Int, level: CpuDifficulty, random: Random): Int? {
        val hand = s.seats[seat].hand
        val options = SevensRules.playableCards(hand, s.low, s.high)
        if (options.isEmpty()) return null
        return when (level) {
            CpuDifficulty.EASY -> options[random.nextInt(options.size)]
            CpuDifficulty.MEDIUM -> options.maxByOrNull { mediumScore(it, hand, s) + random.nextDouble() * 0.01 }!!
            CpuDifficulty.HARD -> options.maxByOrNull { hardScore(it, hand, s, seat) + random.nextDouble() * 0.001 }!!
        }
    }

    /** The card that playing [card] makes playable for the others (0 = none): the next one along its row, or both neighbours of a seven. */
    private fun opened(card: Int, s: SevensState): List<Int> {
        val suit = SevensRules.suitIndex(card)
        val r = SevensRules.rankIndex(card)
        return when {
            r == SevensRules.SEVEN && s.low[suit] == 0 -> listOfNotNull(
                if (r > 1) SevensRules.cardId(suit, r - 1) else null,
                if (r < 13) SevensRules.cardId(suit, r + 1) else null
            )
            r < SevensRules.SEVEN -> if (r > 1) listOf(SevensRules.cardId(suit, r - 1)) else emptyList()
            else -> if (r < 13) listOf(SevensRules.cardId(suit, r + 1)) else emptyList()
        }
    }

    private fun mediumScore(card: Int, hand: List<Int>, s: SevensState): Double {
        val opens = opened(card, s)
        var score = 0.0
        if (opens.isEmpty()) score += 3.0
        for (o in opens) score += if (o in hand) 2.0 else -2.0
        if (SevensRules.rankIndex(card) == SevensRules.SEVEN && s.low[SevensRules.suitIndex(card)] == 0) score -= 1.0
        return score
    }

    private fun hardScore(card: Int, hand: List<Int>, s: SevensState, seat: Int): Double {
        val n = s.seats.size
        val opens = opened(card, s)
        val next = s.seats[(seat + 1) % n]
        val nearlyOut = s.seats.indices.any { it != seat && s.seats[it].hand.size <= 2 }
        val pressure = if (next.hand.size <= 3 || nearlyOut) 2.0 else 0.0
        var score = 0.0
        if (opens.isEmpty()) score += 3.5
        for (o in opens) {
            val suit = SevensRules.suitIndex(o)
            val dir = if (SevensRules.rankIndex(o) < SevensRules.SEVEN) -1 else 1
            if (o in hand) {
                // a chain of my own cards that keeps running behind it
                var chain = 0
                var r = SevensRules.rankIndex(o) + dir
                while (r in 1..13 && SevensRules.cardId(suit, r) in hand) { chain++; r += dir }
                score += 2.5 + 0.8 * chain
            } else {
                // The card I open belongs to an opponent, and so does every card behind it (up to one of mine): this card
                // was the only thing holding that whole run back. Keeping it keeps them waiting, playing it frees all of them.
                var behind = 0
                var r = SevensRules.rankIndex(o) + dir
                while (r in 1..13 && SevensRules.cardId(suit, r) !in hand) { behind++; r += dir }
                score -= 1.0 + pressure + 2.0 * behind
            }
        }
        // tempo: keep my own future plays available so I am never forced to pass
        val after = hand - card
        val low = s.low.toMutableList()
        val high = s.high.toMutableList()
        val suit = SevensRules.suitIndex(card)
        val rr = SevensRules.rankIndex(card)
        if (low[suit] == 0) { low[suit] = rr; high[suit] = rr } else if (rr < low[suit]) low[suit] = rr else high[suit] = rr
        score += 0.3 * SevensRules.playableCards(after, low, high).size
        if (after.isEmpty()) score += 100.0
        return score
    }

    /** What the Hint button suggests for a person: the HARD choice (null = pass, which is then the only legal move). */
    fun hint(s: SevensState, seat: Int): Int? = choose(s, seat, CpuDifficulty.HARD, Random(s.eventSerial.toLong()))
}
