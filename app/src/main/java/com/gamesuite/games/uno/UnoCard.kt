package com.gamesuite.games.uno

import kotlinx.serialization.Serializable

@Serializable
enum class UnoColor { RED, YELLOW, GREEN, BLUE, WILD }

@Serializable
enum class UnoRank {
    ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE,
    SKIP, REVERSE, DRAW_TWO,
    WILD, WILD_DRAW_FOUR
}

/** action = SKIP/REVERSE/DRAW_TWO/WILD/WILD_DRAW_FOUR ; number cards use rank ordinal 0-9 directly. */
@Serializable
data class UnoCard(
    val color: UnoColor,
    val rank: UnoRank,
    /** Unique instance id — the deck has duplicate color+rank pairs, this disambiguates them for UI/selection. */
    val instanceId: Int
) {
    val isWild: Boolean get() = rank == UnoRank.WILD || rank == UnoRank.WILD_DRAW_FOUR
    val isNumber: Boolean get() = rank.ordinal <= UnoRank.NINE.ordinal
    val numberValue: Int? get() = if (isNumber) rank.ordinal else null

    /** Score value used for end-of-round/hand scoring house rules. */
    val scoreValue: Int
        get() = when {
            isNumber -> rank.ordinal
            rank == UnoRank.SKIP || rank == UnoRank.REVERSE || rank == UnoRank.DRAW_TWO -> 20
            rank == UnoRank.WILD || rank == UnoRank.WILD_DRAW_FOUR -> 50
            else -> 0
        }

    fun displayLabel(): String = when (rank) {
        UnoRank.SKIP -> "Skip"
        UnoRank.REVERSE -> "Reverse"
        UnoRank.DRAW_TWO -> "+2"
        UnoRank.WILD -> "Wild"
        UnoRank.WILD_DRAW_FOUR -> "Wild +4"
        else -> rank.ordinal.toString()
    }
}

/**
 * Standard 108-card UNO deck: 4 colors x (one 0, two each of 1-9, two Skip,
 * two Reverse, two Draw Two) = 100, plus 4 Wild + 4 Wild Draw Four = 108.
 */
object UnoDeck {
    fun standardDeck(): MutableList<UnoCard> {
        val cards = mutableListOf<UnoCard>()
        var id = 0
        val colors = listOf(UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE)

        for (color in colors) {
            cards.add(UnoCard(color, UnoRank.ZERO, id++))
            for (rank in listOf(
                UnoRank.ONE, UnoRank.TWO, UnoRank.THREE, UnoRank.FOUR, UnoRank.FIVE,
                UnoRank.SIX, UnoRank.SEVEN, UnoRank.EIGHT, UnoRank.NINE,
                UnoRank.SKIP, UnoRank.REVERSE, UnoRank.DRAW_TWO
            )) {
                repeat(2) { cards.add(UnoCard(color, rank, id++)) }
            }
        }
        repeat(4) { cards.add(UnoCard(UnoColor.WILD, UnoRank.WILD, id++)) }
        repeat(4) { cards.add(UnoCard(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, id++)) }

        return cards
    }
}
