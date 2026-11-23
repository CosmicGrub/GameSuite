package com.gamesuite.games.war

import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Rank
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** The two ways to play. */
object WarVariant {
    /** Everyone flips the top card; high card wins. */
    const val CLASSIC = "classic"
    /** Everyone sees their top three cards and secretly picks one to play. */
    const val CHOOSE3 = "choose3"
    val ALL = setOf(CLASSIC, CHOOSE3)
}

@Serializable
data class WarConfig(
    val variant: String = WarVariant.CLASSIC,
    /** Face-down cards each tied player puts down in a war before the deciding face-up card (1 or 3). */
    val warDown: Int = 3,
    /** The game is called after this many rounds; the bigger pile wins. */
    val maxRounds: Int = 300
) {
    val isValid: Boolean
        get() = variant in WarVariant.ALL && warDown in setOf(1, 3) && maxRounds in 10..5000

    companion object {
        fun fromExtras(extras: Map<String, String>): WarConfig {
            val d = WarConfig()
            return WarConfig(
                variant = extras["variant"]?.takeIf { it in WarVariant.ALL } ?: d.variant,
                warDown = extras["warDown"]?.toIntOrNull()?.takeIf { it == 1 || it == 3 } ?: d.warDown,
                maxRounds = extras["maxRounds"]?.toIntOrNull()?.takeIf { it in 10..5000 } ?: d.maxRounds
            )
        }
    }
}

/** One seat's cards on the table in one flip: [down] face-down cards and the deciding face-up [up] card. */
data class WarPlay(val seat: Int, val down: Int, val up: Card)

/** One flip of the table: the opening flip, or one war. */
data class WarStep(val plays: List<WarPlay>)

/** A resolved round. [winnerSeat] is -1 when the pot was split because every tied player ran out of cards. */
data class WarRound(val steps: List<WarStep>, val winnerSeat: Int, val potSize: Int) {
    val wars: Int get() = steps.size - 1
}

object WarRules {
    const val DECK = 52
    const val CHOICES = 3

    fun seatCountOk(n: Int) = n in 2..4

    /** Deals every card round-robin (so with 3 seats the first gets 18). Index 0 of a pile is its top. */
    fun deal(deck: List<Card>, seats: Int): List<ArrayDeque<Card>> {
        require(seatCountOk(seats)) { "War needs 2 to 4 seats" }
        val piles = List(seats) { ArrayDeque<Card>() }
        deck.forEachIndexed { i, c -> piles[i % seats].addLast(c) }
        return piles
    }

    /**
     * Resolves one round. [firsts] maps each contending seat to the face-up card it already played (the
     * caller removed it from the pile). Wars among tied top cards repeat until one seat wins; a tied seat
     * with no card left drops out, and if every tied seat is out of cards the pot is split. The winner
     * takes the whole pot, shuffled with [random], onto the bottom of the pile. Mutates [piles].
     */
    fun resolve(piles: List<ArrayDeque<Card>>, firsts: Map<Int, Card>, warDown: Int, random: Random): WarRound {
        require(firsts.isNotEmpty())
        val pot = ArrayList<Card>()
        val steps = ArrayList<WarStep>()
        var plays = firsts.entries.sortedBy { it.key }.map { WarPlay(it.key, 0, it.value) }
        pot += plays.map { it.up }
        var winner: Int
        var tied: List<Int> = emptyList()
        while (true) {
            steps += WarStep(plays)
            val max = plays.maxOf { it.up.rank.value }
            val top = plays.filter { it.up.rank.value == max }.map { it.seat }
            if (top.size == 1) { winner = top[0]; break }
            val ready = top.filter { piles[it].isNotEmpty() }
            if (ready.isEmpty()) { winner = -1; tied = top; break }
            if (ready.size == 1) { winner = ready[0]; break }
            plays = ready.map { s ->
                val pile = piles[s]
                val down = minOf(warDown, pile.size - 1)
                repeat(down) { pot += pile.removeFirst() }
                val up = pile.removeFirst()
                pot += up
                WarPlay(s, down, up)
            }
        }
        val shuffled = pot.shuffled(random)
        if (winner >= 0) {
            piles[winner].addAll(shuffled)
        } else {
            shuffled.forEachIndexed { i, c -> piles[tied[i % tied.size]].addLast(c) }
        }
        return WarRound(steps, winner, pot.size)
    }

    /**
     * Which of up to three candidate cards to play, by expected card swing against one unseen opponent card
     * (all ranks equally likely, minus the ranks I can see): playing rank r wins the opponent's card when
     * it is lower and loses mine when it is higher. Ties are a wash. Returns the index into [candidates].
     */
    fun bestChoice(candidates: List<Card>): Int {
        if (candidates.size <= 1) return 0
        fun ev(r: Int): Double {
            var total = 0.0
            var weight = 0.0
            for (o in 2..14) {
                val seen = candidates.count { it.rank.value == o }
                val w = (4 - seen).coerceAtLeast(0).toDouble()
                weight += w
                total += w * when {
                    o < r -> o.toDouble()
                    o > r -> -r.toDouble()
                    else -> 0.0
                }
            }
            return if (weight == 0.0) 0.0 else total / weight
        }
        var best = 0
        var bestEv = ev(candidates[0].rank.value)
        for (i in 1 until candidates.size) {
            val e = ev(candidates[i].rank.value)
            if (e > bestEv) { best = i; bestEv = e }
        }
        return best
    }
}
