package com.gamesuite.games.solitaire

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the low-Ace bug documented on
 * [SolitaireGame.lowAceValue]: Rank.value (games/cards/Card.kt) is a
 * high-Ace ranking (ACE=14), which is wrong for Klondike foundation/tableau
 * sequencing (A,2,3,...,K) and, before the fix, both capped every
 * foundation at one card (Two-on-Ace never matched) and let a King
 * illegally land on an exposed Ace (13 == 14-1). `canPlaceOnTableau` and
 * `canPlaceOnFoundation` are private, so every fixture here drives the same
 * checks through the real public tap sequence (`tapWaste`/`tapTableau`/
 * `tapFoundation`) a player actually uses, with `state` seeded directly
 * (it's public `mutableStateOf<SolitaireState?>`) to the exact near-endgame
 * arrangement each case needs — same "seed public state, drive public
 * taps" approach as TicTacToeGameTest.
 */
class SolitaireGameTest {

    private fun newGame(): SolitaireGame {
        val game = SolitaireGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    private fun emptyFoundations(): Map<Suit, List<Card>> = Suit.entries.associateWith { emptyList<Card>() }

    private fun emptyTableau(): List<TableauColumn> = List(7) { TableauColumn() }

    @Test
    fun `an Ace can be placed on an empty foundation`() {
        val ace = Card(Rank.ACE, Suit.HEARTS, id = 0)
        val game = newGame()
        game.state.value = SolitaireState(
            tableau = emptyTableau(),
            stock = emptyList(),
            waste = listOf(ace),
            foundations = emptyFoundations()
        )

        game.tapWaste()
        game.tapFoundation(Suit.HEARTS)

        val result = game.state.value!!
        assertEquals(listOf(ace), result.foundations[Suit.HEARTS])
        assertEquals(emptyList<Card>(), result.waste)
    }

    @Test
    fun `a Two can be placed on an Ace already on a foundation`() {
        val ace = Card(Rank.ACE, Suit.HEARTS, id = 0)
        val two = Card(Rank.TWO, Suit.HEARTS, id = 1)
        val game = newGame()
        game.state.value = SolitaireState(
            tableau = emptyTableau(),
            stock = emptyList(),
            waste = listOf(two),
            foundations = emptyFoundations() + (Suit.HEARTS to listOf(ace))
        )

        game.tapWaste()
        game.tapFoundation(Suit.HEARTS)

        val result = game.state.value!!
        assertEquals(listOf(ace, two), result.foundations[Suit.HEARTS])
        assertEquals(emptyList<Card>(), result.waste)
    }

    @Test
    fun `a King cannot be placed on an exposed Ace on a tableau column`() {
        // Colors deliberately alternate (black Ace, red King) so this isolates
        // the low-Ace RANK check from the separate alternating-color check —
        // a King-on-Ace move must fail on rank alone, regardless of color.
        val aceOfSpades = Card(Rank.ACE, Suit.SPADES, id = 0)
        val kingOfHearts = Card(Rank.KING, Suit.HEARTS, id = 1)
        val game = newGame()
        val tableau = emptyTableau().toMutableList()
        tableau[0] = TableauColumn(faceUp = listOf(aceOfSpades))
        game.state.value = SolitaireState(
            tableau = tableau,
            stock = emptyList(),
            waste = listOf(kingOfHearts),
            foundations = emptyFoundations()
        )

        game.tapWaste()
        game.tapTableau(0)

        val result = game.state.value!!
        // The illegal move must not have applied: the Ace is still the
        // column's only card and the King is still sitting in the waste.
        assertEquals(listOf(aceOfSpades), result.tableau[0].faceUp)
        assertEquals(listOf(kingOfHearts), result.waste)
    }

    // ---- draw-1 vs draw-3 (games/solitaire/SolitairePrefsStore.kt's persisted preference) ----

    private fun stockOf(vararg cards: Card): List<Card> = cards.toList()

    @Test
    fun `tapStock draws exactly one card by default`() {
        val game = newGame()
        game.state.value = SolitaireState(
            tableau = emptyTableau(),
            stock = stockOf(
                Card(Rank.TWO, Suit.CLUBS, id = 0),
                Card(Rank.THREE, Suit.CLUBS, id = 1),
                Card(Rank.FOUR, Suit.CLUBS, id = 2)
            ),
            waste = emptyList(),
            foundations = emptyFoundations()
        )

        game.tapStock()

        val result = game.state.value!!
        assertEquals(listOf(Card(Rank.FOUR, Suit.CLUBS, id = 2)), result.waste)
        assertEquals(2, result.stock.size)
    }

    @Test
    fun `tapStock draws up to three cards when drawThree is on`() {
        val game = newGame()
        game.drawThree = true
        val two = Card(Rank.TWO, Suit.CLUBS, id = 0)
        val three = Card(Rank.THREE, Suit.CLUBS, id = 1)
        val four = Card(Rank.FOUR, Suit.CLUBS, id = 2)
        val five = Card(Rank.FIVE, Suit.CLUBS, id = 3)
        game.state.value = SolitaireState(
            tableau = emptyTableau(),
            stock = stockOf(two, three, four, five),
            waste = emptyList(),
            foundations = emptyFoundations()
        )

        game.tapStock()

        val result = game.state.value!!
        // Moved in stock order, so the waste's last (only playable) card is
        // still the most-recently-drawn one, exactly as under draw-1.
        assertEquals(listOf(three, four, five), result.waste)
        assertEquals(listOf(two), result.stock)
    }

    @Test
    fun `tapStock with drawThree on moves only what's left when the stock has fewer than three`() {
        val game = newGame()
        game.drawThree = true
        val two = Card(Rank.TWO, Suit.CLUBS, id = 0)
        val three = Card(Rank.THREE, Suit.CLUBS, id = 1)
        game.state.value = SolitaireState(
            tableau = emptyTableau(),
            stock = stockOf(two, three),
            waste = emptyList(),
            foundations = emptyFoundations()
        )

        game.tapStock()

        val result = game.state.value!!
        assertEquals(listOf(two, three), result.waste)
        assertEquals(emptyList<Card>(), result.stock)
    }

    @Test
    fun `only the top of the waste is playable even with drawThree on`() {
        val game = newGame()
        game.drawThree = true
        val ace = Card(Rank.ACE, Suit.HEARTS, id = 0)
        val two = Card(Rank.TWO, Suit.CLUBS, id = 1)
        val three = Card(Rank.THREE, Suit.CLUBS, id = 2)
        game.state.value = SolitaireState(
            tableau = emptyTableau(),
            stock = stockOf(two, three, ace),
            waste = emptyList(),
            foundations = emptyFoundations()
        )

        game.tapStock()
        game.tapWaste()
        game.tapFoundation(Suit.HEARTS)

        val result = game.state.value!!
        // Only the Ace (the last of the three drawn cards) ever became
        // selectable/playable -- the Two and Three stay in the waste beneath it.
        assertEquals(listOf(ace), result.foundations[Suit.HEARTS])
        assertEquals(listOf(two, three), result.waste)
    }
}
