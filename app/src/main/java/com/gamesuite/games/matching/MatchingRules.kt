package com.gamesuite.games.matching

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** What a card is doing on the table. */
object MatchingStatus {
    const val DOWN = 0
    const val UP = 1
    const val MATCHED = 2
}

/** The picture sets. The engine only knows face NUMBERS (0 until [MatchingRules.FACE_POOL]); a theme draws them. */
enum class MatchingTheme(val id: String) {
    CRITTERS("critters"),
    SYMBOLS("symbols"),
    CARDS("cards");

    companion object {
        fun fromId(id: String?): MatchingTheme = entries.firstOrNull { it.id == id } ?: CRITTERS
    }
}

/**
 * The pure rules and dealing of Matching (also called Concentration, Memory or Pairs): cards lie
 * face down in a grid; on your turn flip two. A matching pair stays face up, scores for you, and
 * you go again; a mismatch turns back over and the turn passes. The board is done when every pair
 * is found; with several players the most pairs wins.
 */
object MatchingRules {
    val CARD_COUNTS: List<Int> = listOf(12, 20, 30, 40)
    const val DEFAULT_COUNT = 20
    const val MAX_PLAYERS = 4

    /** The number of distinct pictures a theme provides; the biggest board (40 cards) needs 20. */
    const val FACE_POOL = 20

    /** How long a pair stays visible before it is scored or turned back (scaled by the game speed on screen). */
    const val RESOLVE_DELAY_MS = 900L

    fun normalizeCount(count: Int?): Int = if (count != null && count in CARD_COUNTS) count else DEFAULT_COUNT

    /** The Hub's "cards" option id ("12".."40") to a card count; anything else is [DEFAULT_COUNT]. */
    fun countFromOptionId(id: String?): Int = normalizeCount(id?.toIntOrNull())

    /** (columns, rows) of the card grid; landscape swaps them. */
    fun grid(count: Int, portrait: Boolean): Pair<Int, Int> {
        val (c, r) = when (normalizeCount(count)) {
            12 -> 3 to 4
            20 -> 4 to 5
            30 -> 5 to 6
            else -> 5 to 8
        }
        return if (portrait) c to r else r to c
    }

    /** [count] cards, every face twice, in a random order: `count / 2` distinct faces drawn from the pool. */
    fun deal(count: Int, random: Random): List<Int> {
        val pairs = normalizeCount(count) / 2
        val faces = (0 until FACE_POOL).shuffled(random).take(pairs)
        return (faces + faces).shuffled(random)
    }

    /**
     * How well the computer remembers (see [MatchingBot]). [rememberChance]: a card it sees turned
     * up is stored with this chance. [forgetChance]: each stored card is lost with this chance at
     * the end of every attempt. [useKnownPair]: when it knows a pair it goes for it this often.
     * [sloppiness]: how often it picks a random card even when it knows which ones are unseen.
     */
    class BotProfile(val rememberChance: Double, val forgetChance: Double, val useKnownPair: Double, val sloppiness: Double)

    fun botProfile(difficulty: CpuDifficulty): BotProfile = when (difficulty) {
        CpuDifficulty.EASY -> BotProfile(rememberChance = 0.5, forgetChance = 0.10, useKnownPair = 0.8, sloppiness = 0.3)
        CpuDifficulty.MEDIUM -> BotProfile(rememberChance = 0.85, forgetChance = 0.03, useKnownPair = 0.95, sloppiness = 0.1)
        CpuDifficulty.HARD -> BotProfile(rememberChance = 1.0, forgetChance = 0.0, useKnownPair = 1.0, sloppiness = 0.0)
    }
}
