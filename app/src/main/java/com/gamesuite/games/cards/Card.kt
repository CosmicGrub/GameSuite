package com.gamesuite.games.cards

/**
 * Roadmap item 13's "remaining catalog" (Solitaire, Poker, Hearts) all need a
 * standard 52-card deck — and nothing in this package has one. `CardVisual`
 * is purely presentational (a label/color/cornerIndex, no card-game concept
 * at all), and UNO's own `UnoCard`/`UnoColor`/`UnoRank` (games/uno/UnoCard.kt)
 * is a deliberately custom-suit, custom-rank 108-card deck — not reusable for
 * a standard game. This file is the shared standard-deck model every
 * standard-card game builds on, the same role `CardVisual`/`PlayingCardView`/
 * `FannedHand`/`CardScale`/`CardSounds` already play for rendering — one
 * model, not one per game.
 */
enum class Suit(val symbol: String, val isRed: Boolean) {
    CLUBS("♣", isRed = false),
    DIAMONDS("♦", isRed = true),
    HEARTS("♥", isRed = true),
    SPADES("♠", isRed = false)
}

/** [value] is the standard high-Ace ranking (2..14) used by most trick-taking/poker hand comparisons. */
enum class Rank(val label: String, val value: Int) {
    TWO("2", 2), THREE("3", 3), FOUR("4", 4), FIVE("5", 5), SIX("6", 6), SEVEN("7", 7),
    EIGHT("8", 8), NINE("9", 9), TEN("10", 10),
    JACK("J", 11), QUEEN("Q", 12), KING("K", 13), ACE("A", 14)
}

/** [id] is a stable 0..51 index (see [Deck.fresh52]) — use it as a diffing/list key, never derive identity from rank+suit equality alone. */
data class Card(val rank: Rank, val suit: Suit, val id: Int) {
    val label: String get() = "${rank.label}${suit.symbol}"

    /** Maps this card to the shared presentational type every card-rendering composable already understands. */
    fun toVisual(faceDown: Boolean = false): CardVisual = CardVisual(
        id = id,
        label = if (faceDown) "" else label,
        backgroundColor = androidx.compose.ui.graphics.Color.White,
        textColor = if (suit.isRed) androidx.compose.ui.graphics.Color(0xFFD32F2F) else androidx.compose.ui.graphics.Color(0xFF212121),
        faceDown = faceDown
    )
}

object Deck {
    /** A complete, unshuffled standard 52-card deck — ids are stable per (rank, suit) pair, 0..51. */
    fun fresh52(): List<Card> {
        var id = 0
        return Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(rank, suit, id++) } }
    }

    fun shuffled52(): List<Card> = fresh52().shuffled()
}
