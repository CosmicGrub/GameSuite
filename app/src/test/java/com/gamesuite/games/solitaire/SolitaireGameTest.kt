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
}
