package com.gamesuite.games.speed

import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.games.cards.Rank
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The pure rules of Speed (also called Spit): two players, one 52-card deck, no turns.
 *
 * Each player gets 26 cards: a hand of [HAND_SIZE] (always topped up from the draw pile), a draw
 * pile of [DRAW_SIZE] and a replacement pile of [RESERVE_SIZE]. Both players flip their top
 * replacement card onto a centre pile; then everyone plays at once, putting a card from their
 * hand onto either centre pile when it is exactly one rank higher or lower (ace is next to both
 * king and two, so the ranks form a ring). When neither player can play, both flip a new card from
 * their replacement piles. The first player to play every card of their hand and draw pile wins.
 */
object SpeedRules {
    const val HAND_SIZE = 5
    const val DRAW_SIZE = 15
    const val RESERVE_SIZE = 6
    const val DECK_SIZE = 52

    /** A competing play that arrives within this long of the winning one on the same pile is a "photo finish", not a mistake. */
    const val GRACE_MS = 150L

    /** The 3-2-1 before the cards are live. */
    const val COUNTDOWN_MS = 3_000L

    /** Both players stuck for this long flips the centre cards without anyone pressing Flip. */
    const val AUTO_FLIP_MS = 1_500L

    /** This many flips in a row with nobody able to play ends the board (fewest cards left wins). */
    const val MAX_DEADLOCK_FLIPS = 30

    /** 0 for a two, up to 12 for an ace: the position on the ring of ranks. */
    fun rankIndex(rank: Rank): Int = rank.value - 2

    /** True when [a] and [b] are neighbours on the ring of ranks (two to ace, with ace next to both king and two). */
    fun adjacent(a: Rank, b: Rank): Boolean {
        val d = ((rankIndex(a) - rankIndex(b)) % 13 + 13) % 13
        return d == 1 || d == 12
    }

    /** Whether [card] may be played onto a pile whose top is [top] (an empty pile accepts nothing). */
    fun canPlay(card: Card, top: Card?): Boolean = top != null && adjacent(card.rank, top.rank)

    /** One seat's starting cards, as dealt from a shuffled deck. */
    class Deal(val hand: List<Card>, val draw: List<Card>, val reserve: List<Card>)

    /** Deals two seats from a deck shuffled by [random]. Each seat holds exactly 26 cards. */
    fun deal(random: Random): List<Deal> {
        val deck = Deck.fresh52().shuffled(random)
        return (0 until 2).map { seat ->
            val start = seat * (HAND_SIZE + DRAW_SIZE + RESERVE_SIZE)
            Deal(
                hand = deck.subList(start, start + HAND_SIZE),
                draw = deck.subList(start + HAND_SIZE, start + HAND_SIZE + DRAW_SIZE),
                reserve = deck.subList(start + HAND_SIZE + DRAW_SIZE, start + HAND_SIZE + DRAW_SIZE + RESERVE_SIZE)
            )
        }
    }

    /**
     * How the computer plays at each level. [reactionMs] is how long it takes to notice a new
     * opportunity and act on it; the actual wait is that, scaled by a random factor in
     * [JITTER_LOW]..[JITTER_HIGH]. [missChance] is the chance an opportunity is overlooked for one
     * more reaction time; [smartness] is how often it picks its best card rather than a random one.
     */
    class CpuProfile(val reactionMs: Long, val missChance: Double, val smartness: Double)

    fun profile(difficulty: CpuDifficulty): CpuProfile = when (difficulty) {
        CpuDifficulty.EASY -> CpuProfile(reactionMs = 1_800L, missChance = 0.25, smartness = 0.0)
        CpuDifficulty.MEDIUM -> CpuProfile(reactionMs = 1_000L, missChance = 0.08, smartness = 0.6)
        CpuDifficulty.HARD -> CpuProfile(reactionMs = 450L, missChance = 0.0, smartness = 1.0)
    }

    const val JITTER_LOW = 0.8
    const val JITTER_HIGH = 1.2

    /** A follow-up play within this long of the computer's own last play comes a little quicker (it already has its eye in). */
    const val CHAIN_WINDOW_MS = 300L
    const val CHAIN_FACTOR = 0.75
}
