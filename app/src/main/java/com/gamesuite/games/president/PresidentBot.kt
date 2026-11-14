package com.gamesuite.games.president

import com.gamesuite.games.cards.Rank
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The computer players of President.
 *
 * EASY plays any legal set at random and sometimes passes although it could play, so it is beatable.
 * MEDIUM sheds its weakest set that still wins, keeps pairs together and saves its 2s.
 * HARD also counts the cards: it knows which ranks are still out there (everything played this round plus its
 * own hand), so it can tell a set nobody can beat from one that will be covered. It manages tempo: it sheds
 * its lowest sets first without splitting pairs, goes out the moment it can, never wastes a pass it does not
 * have to take, leads sets too big for a nearly-out opponent to answer (or sets nobody can beat), uses a tie to
 * skip a player who is close to going out, and only starts a revolution when the flipped order favours its hand.
 *
 * Every choice is one of [PresidentRules.candidatePlays] (or a pass, when passing is legal), so a bot
 * never makes an illegal move.
 */
object PresidentBot {

    /** The play to make, or null to pass. Never null when leading (a leader cannot pass). */
    fun choose(s: PresidentState, seat: Int, level: CpuDifficulty, random: Random): List<Int>? {
        val hand = s.seats[seat].hand
        val top = s.top?.cards
        val options = PresidentRules.candidatePlays(hand, top, s.revolution, s.rules.ties, s.mustInclude)
        if (options.isEmpty()) return null
        return when (level) {
            CpuDifficulty.EASY -> easy(options, top == null, random)
            CpuDifficulty.MEDIUM -> medium(s, seat, options, random)
            CpuDifficulty.HARD -> hard(s, seat, options)
        }
    }

    // ---- EASY -------------------------------------------------------------------------------

    private fun easy(options: List<List<Int>>, leading: Boolean, random: Random): List<Int>? {
        if (!leading && random.nextInt(100) < 18) return null
        return options[random.nextInt(options.size)]
    }

    // ---- shared hand analysis -----------------------------------------------------------------

    private fun groupSize(hand: List<Int>, play: List<Int>): Int = hand.count { PresidentRules.rankOf(it) == PresidentRules.rankOf(play[0]) }

    private fun fewestCardsAmongOthers(s: PresidentState, seat: Int): Int =
        s.seats.indices.filter { it != seat && s.seats[it].hand.isNotEmpty() }.minOfOrNull { s.seats[it].hand.size } ?: 99

    // ---- MEDIUM -----------------------------------------------------------------------------

    private fun medium(s: PresidentState, seat: Int, options: List<List<Int>>, random: Random): List<Int>? {
        val hand = s.seats[seat].hand
        val leading = s.top == null
        val danger = fewestCardsAmongOthers(s, seat) <= 2
        fun cost(p: List<Int>): Int {
            val power = PresidentRules.power(p[0], s.revolution)
            val breaks = groupSize(hand, p) - p.size
            var c = power * 10 + breaks * 12
            if (p.size == hand.size) c -= 200 // goes out
            if (PresidentRules.isTrickKiller(p, s.revolution) && p.size != hand.size && !danger) c += 40
            if (leading && breaks == 0) c -= p.size * 2 // shed whole groups when leading
            return c
        }
        val best = options.minByOrNull(::cost)!!
        if (!leading && !danger && best.size != hand.size && cost(best) >= 130 && random.nextInt(100) < 55) return null
        return best
    }

    // ---- HARD -------------------------------------------------------------------------------

    /** How many cards of each rank nobody has shown yet and this seat does not hold. */
    private fun unseenByRank(s: PresidentState, hand: List<Int>): Map<Rank, Int> {
        val seen = (s.playedCards + hand).groupingBy { PresidentRules.rankOf(it) }.eachCount()
        return Rank.values().associateWith { 4 - (seen[it] ?: 0) }
    }

    /** True when no opponent can hold a higher set of the same size (so this play wins the trick for sure). */
    private fun isSureWinner(play: List<Int>, unseen: Map<Rank, Int>, s: PresidentState): Boolean {
        val myPower = PresidentRules.power(play[0], s.revolution)
        for ((rank, count) in unseen) {
            if (count < play.size) continue
            val p = PresidentRules.power(rank, s.revolution)
            if (p > myPower) return false
            if (p == myPower && s.rules.ties && rank != PresidentRules.rankOf(play[0])) return false
        }
        return true
    }

    /** Average strength of [cards] under the order in force (higher = stronger hand). */
    private fun averagePower(cards: List<Int>, revolution: Boolean): Double =
        if (cards.isEmpty()) 0.0 else cards.sumOf { PresidentRules.power(it, revolution) }.toDouble() / cards.size

    private fun hard(s: PresidentState, seat: Int, options: List<List<Int>>): List<Int>? {
        val hand = s.seats[seat].hand
        val leading = s.top == null
        // the move that empties the hand always wins the race
        options.firstOrNull { it.size == hand.size }?.let { return it }
        val unseen = unseenByRank(s, hand)
        val minOpp = fewestCardsAmongOthers(s, seat)
        val nextSeat = (1 until s.seats.size).map { (seat + it) % s.seats.size }.firstOrNull { s.seats[it].hand.isNotEmpty() }
        val nextCards = nextSeat?.let { s.seats[it].hand.size } ?: 99

        fun cost(p: List<Int>): Int {
            val power = PresidentRules.power(p[0], s.revolution)
            val breaks = groupSize(hand, p) - p.size
            var c = power * 10 + breaks * 14 // the weakest set that wins, without splitting a pair or triple
            if (p.size == 4 && s.rules.revolutions) {
                // a revolution turns every low card into a high one: worth it only when the rest of the hand gains
                val rest = hand - p.toSet()
                val gain = averagePower(rest, !s.revolution) - averagePower(rest, s.revolution)
                c += if (gain > 1.5) -90 else if (gain < -0.5) 200 else 40
            }
            // matching the last play skips the next player: best when that player is nearly out
            if (s.rules.ties && s.top != null && power == PresidentRules.power(s.top!!.cards[0], s.revolution) && nextCards <= 3) c -= 12
            if (leading) {
                c -= p.size * 2 // shed whole groups of low cards first
                // somebody holds only a few cards: a set at least as small as their hand lets them answer and perhaps go out,
                // so lead bigger sets, or ones nobody can beat (counted from the cards already played)
                if (minOpp <= 3 && p.size <= minOpp && !isSureWinner(p, unseen, s)) c += 60
            }
            return c
        }
        return options.minByOrNull(::cost)
    }

    // ---- exchange ---------------------------------------------------------------------------

    /** The [count] cards a computer hands back after an exchange: its least useful ones. */
    fun chooseGive(hand: List<Int>, count: Int, level: CpuDifficulty): List<Int> {
        if (level == CpuDifficulty.EASY) return hand.take(count)
        val sizes = hand.groupingBy { PresidentRules.rankOf(it) }.eachCount()
        return hand.sortedWith(
            compareBy({ PresidentRules.basePower(PresidentRules.rankOf(it)) * 10 + ((sizes[PresidentRules.rankOf(it)] ?: 1) - 1) * 6 }, { it })
        ).take(count)
    }

    /** What the Hint button shows a person: the HARD choice for their seat (null = pass; empty = nothing to suggest). */
    fun hint(s: PresidentState, seat: Int, random: Random = Random(s.eventSerial.toLong())): PsHint? {
        if (s.phase == PsPhase.EXCHANGE) {
            val task = s.pendingExchanges.firstOrNull { it.from == seat } ?: return null
            return PsHint(chooseGive(s.seats[seat].hand, task.count, CpuDifficulty.HARD), pass = false)
        }
        if (s.phase != PsPhase.PLAY || s.currentSeat != seat) return null
        val choice = choose(s, seat, CpuDifficulty.HARD, random)
        return if (choice == null) PsHint(emptyList(), pass = true) else PsHint(choice, pass = false)
    }
}

/** A suggestion: the cards to select (play, or give in the exchange) or to pass. */
data class PsHint(val cards: List<Int>, val pass: Boolean)
