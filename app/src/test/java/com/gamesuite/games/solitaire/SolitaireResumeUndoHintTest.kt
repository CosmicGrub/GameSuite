package com.gamesuite.games.solitaire

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume (snapshot/restore), Undo and Hint for Klondike Solitaire, against the Connect Four reference. */
class SolitaireResumeUndoHintTest {

    private fun newGame(playerId: String = "p1", mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT): SolitaireGame {
        val game = SolitaireGame()
        game.init(
            GameContext(
                activeMode = mode,
                players = listOf(PlayerInfo(playerId = playerId, displayName = "Player")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    private fun started(): SolitaireGame = newGame().also { it.startMatch() }

    /** The board without the status line, so two states compare equal even when only the message differs. */
    private fun board(game: SolitaireGame): SolitaireState = game.state.value!!.copy(lastAction = "")

    private fun emptyFoundations(): Map<Suit, List<Card>> = Suit.entries.associateWith { emptyList<Card>() }
    private fun emptyTableau(): List<TableauColumn> = List(7) { TableauColumn() }

    private fun seed(
        game: SolitaireGame,
        tableau: List<TableauColumn> = emptyTableau(),
        stock: List<Card> = emptyList(),
        waste: List<Card> = emptyList()
    ) {
        game.state.value = SolitaireState(tableau = tableau, stock = stock, waste = waste, foundations = emptyFoundations())
    }

    /** Plays a hint the way a player would: tap the source, then the destination (or the stock). */
    private fun play(game: SolitaireGame, hint: SolitaireHint) {
        if (hint.isStockDraw) {
            game.tapStock()
            return
        }
        when (val source = hint.source!!) {
            SelectionSource.Waste -> game.tapWaste()
            is SelectionSource.Tableau -> game.tapTableau(source.index)
        }
        when (val destination = hint.destination!!) {
            is MoveDestination.Tableau -> game.tapTableau(destination.index)
            is MoveDestination.Foundation -> game.tapFoundation(destination.suit)
        }
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first deal, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no deal yet", game.snapshot())

        game.startMatch()
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatch() }
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = started()
        a.tapStock()
        a.tapStock()
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(board(a), board(b))

        a.tapStock()
        b.tapStock()
        assertEquals("the same draw must lead to the same position", board(a), board(b))
    }

    @Test
    fun `the order of the stock and every hidden card survives a resume`() {
        val a = started()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        val before = a.state.value!!
        val after = b.state.value!!
        assertEquals(before.stock, after.stock)
        assertEquals(before.tableau, after.tableau)
        assertEquals(24, after.stock.size)
        assertEquals(6, after.tableau[6].faceDown.size)
    }

    @Test
    fun `a pending selection, the score and the session tally survive a resume`() {
        val a = started()
        a.tapStock()
        a.tapWaste()
        a.gamesWon.value = 2
        a.state.value = a.state.value!!.copy(score = 40)
        assertEquals(SelectionSource.Waste, a.state.value!!.selected)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(SelectionSource.Waste, b.state.value!!.selected)
        assertEquals(40, b.state.value!!.score)
        assertEquals(2, b.gamesWon.value)
        assertEquals(board(a), board(b))
    }

    @Test
    fun `a resume does not replay the deal-in or leave an undo history behind`() {
        val a = started()
        a.tapStock()
        val b = newGame()
        assertEquals(0, b.dealSeq.value)
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals("restore is not a fresh deal", 0, b.dealSeq.value)
        assertFalse("undo never reaches before the resume point", b.canUndo)
        assertEquals("Game resumed", b.state.value!!.lastAction)
    }

    @Test
    fun `restore rejects garbage and leaves the engine untouched`() {
        val game = started()
        game.tapStock()
        val before = game.state.value
        val won = game.gamesWon.value

        assertFalse(game.restore("not json"))
        assertFalse(game.restore("{}"))
        assertFalse(game.restore(""))
        assertEquals(before, game.state.value)
        assertEquals(won, game.gamesWon.value)
        assertTrue("undo history is untouched by a failed restore", game.canUndo)
    }

    @Test
    fun `restore rejects a snapshot from a different roster`() {
        val snapshot = started().snapshot()!!
        val other = newGame(playerId = "someone-else")
        other.startMatch()
        val before = other.state.value
        assertFalse(other.restore(snapshot))
        assertEquals(before, other.state.value)
    }

    @Test
    fun `restore rejects a snapshot with a missing or duplicated card or a wrong shape`() {
        val snapshot = started().snapshot()!!
        val obj = Json.parseToJsonElement(snapshot).jsonObject

        fun tampered(key: String, value: kotlinx.serialization.json.JsonElement): String =
            JsonObject(obj + (key to value)).toString()

        val target = started()
        val before = target.state.value

        // The stock is gone: 24 cards are missing.
        assertFalse(target.restore(tampered("stock", JsonArray(emptyList()))))
        // Only six tableau columns.
        assertFalse(target.restore(tampered("tableauUp", JsonArray(obj["tableauUp"]!!.jsonArray.drop(1)))))
        // The first stock card duplicated into the waste.
        val stock = obj["stock"]!!.jsonArray
        assertFalse(target.restore(tampered("waste", JsonArray(listOf(stock[0])))))
        // A selection that points at an empty waste pile.
        assertFalse(target.restore(tampered("selected", Json.parseToJsonElement("-2"))))
        // A negative score.
        assertFalse(target.restore(tampered("score", Json.parseToJsonElement("-1"))))
        assertEquals(before, target.state.value)

        // The untouched snapshot is still accepted.
        assertTrue(target.restore(snapshot))
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one draw at a time`() {
        val game = started()
        assertFalse(game.canUndo)
        val deal = board(game)
        game.tapStock()
        val afterOne = board(game)
        game.tapStock()
        assertTrue(game.canUndo)

        game.undo()
        assertEquals(afterOne, board(game))
        game.undo()
        assertEquals(deal, board(game))
        assertFalse(game.canUndo)
        game.undo() // nothing left: a no-op, never a crash
        assertEquals(deal, board(game))
    }

    @Test
    fun `undo takes back a hinted move and clears the selection`() {
        val game = started()
        val before = board(game)
        val hint = game.hint()!!
        play(game, hint)
        assertNotEquals("playing the hint changed the position", before, board(game))
        assertTrue(game.canUndo)

        game.undo()
        assertEquals(before, board(game))
        assertNull(game.state.value!!.selected)
        assertEquals("Undid last move", game.state.value!!.lastAction)
    }

    @Test
    fun `selecting a card is not an undo step`() {
        val game = started()
        game.tapStock()
        val afterDraw = board(game)
        game.tapWaste() // select
        game.tapWaste() // deselect
        game.undo()
        assertNotEquals("one undo took back the draw", afterDraw, board(game))
        assertFalse(game.canUndo)
    }

    @Test
    fun `undo does not cross into a previous deal`() {
        val game = started()
        game.tapStock()
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse(game.canUndo)
        val fresh = board(game)
        game.undo()
        assertEquals(fresh, board(game))
    }

    @Test
    fun `undo does nothing once the session has ended`() {
        val game = started()
        game.tapStock()
        game.leaveSession()
        val frozen = game.state.value
        assertFalse(game.canUndo)
        game.undo()
        assertEquals(frozen, game.state.value)
    }

    @Test
    fun `a solved deal cannot be undone, so one deal cannot be won twice`() {
        val game = started()
        game.tapStock()
        assertTrue(game.canUndo)
        game.state.value = game.state.value!!.copy(won = true)
        assertFalse(game.canUndo)
        val frozen = game.state.value
        game.undo()
        assertEquals(frozen, game.state.value)
    }

    @Test
    fun `undo never lowers the session tally`() {
        val game = started()
        game.tapStock()
        game.gamesWon.value = 3
        game.undo()
        assertEquals(3, game.gamesWon.value)
    }

    @Test
    fun `a new deal bumps the deal counter but undo does not`() {
        val game = newGame()
        assertEquals(0, game.dealSeq.value)
        game.startMatch()
        assertEquals(1, game.dealSeq.value)
        game.tapStock()
        game.undo()
        assertEquals("undo is not a deal", 1, game.dealSeq.value)
        game.playAgain()
        assertEquals(2, game.dealSeq.value)
    }

    @Test
    fun `taps after the session ends change nothing`() {
        val game = started()
        game.leaveSession()
        val frozen = game.state.value
        game.tapStock()
        game.tapWaste()
        game.tapTableau(0)
        game.tapFoundation(Suit.HEARTS)
        assertEquals(frozen, game.state.value)
    }

    @Test
    fun `a rejected placement is counted`() {
        val game = newGame()
        val seven = Card(Rank.SEVEN, Suit.SPADES, id = 0)
        seed(game, waste = listOf(seven))
        game.tapWaste()
        assertEquals(0, game.rejectedCount.value)
        game.tapFoundation(Suit.SPADES) // a Seven cannot start a foundation
        assertEquals(1, game.rejectedCount.value)
        assertEquals("Can't place ${seven.label} there", game.state.value!!.lastAction)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint suggests an Ace for its foundation first`() {
        val game = newGame()
        val ace = Card(Rank.ACE, Suit.HEARTS, id = 0)
        seed(game, waste = listOf(ace), stock = listOf(Card(Rank.TWO, Suit.CLUBS, id = 1)))

        val hint = game.hint()!!
        assertEquals(SelectionSource.Waste, hint.source)
        assertEquals(MoveDestination.Foundation(Suit.HEARTS), hint.destination)
        assertEquals(hint, game.hintMove.value)
    }

    @Test
    fun `hint prefers a move that turns a face-down card over`() {
        val game = newGame()
        val tableau = emptyTableau().toMutableList()
        tableau[0] = TableauColumn(
            faceDown = listOf(Card(Rank.THREE, Suit.CLUBS, id = 10)),
            faceUp = listOf(Card(Rank.QUEEN, Suit.HEARTS, id = 11))
        )
        tableau[1] = TableauColumn(faceUp = listOf(Card(Rank.KING, Suit.SPADES, id = 12)))
        seed(game, tableau = tableau)

        val hint = game.hint()!!
        assertEquals(SelectionSource.Tableau(0), hint.source)
        assertEquals(MoveDestination.Tableau(1), hint.destination)
    }

    @Test
    fun `hint suggests a waste card that has a tableau home`() {
        val game = newGame()
        val tableau = emptyTableau().toMutableList()
        tableau[2] = TableauColumn(faceUp = listOf(Card(Rank.SEVEN, Suit.SPADES, id = 20)))
        seed(game, tableau = tableau, waste = listOf(Card(Rank.SIX, Suit.HEARTS, id = 21)))

        val hint = game.hint()!!
        assertEquals(SelectionSource.Waste, hint.source)
        assertEquals(MoveDestination.Tableau(2), hint.destination)
    }

    @Test
    fun `hint falls back to drawing from the stock`() {
        val game = newGame()
        seed(game, stock = listOf(Card(Rank.NINE, Suit.CLUBS, id = 30)))

        val hint = game.hint()!!
        assertTrue(hint.isStockDraw)
        assertNull(hint.source)
        assertNull(hint.destination)
    }

    @Test
    fun `hint is null and says so when there is nothing to play or draw`() {
        val game = newGame()
        seed(game)
        assertNull(game.hint())
        assertNull(game.hintMove.value)
        assertTrue(game.state.value!!.lastAction.startsWith("No moves left"))
    }

    @Test
    fun `hint is null before the first deal, once solved, and after the session ends`() {
        val fresh = newGame()
        assertNull(fresh.hint())

        val solved = started()
        solved.state.value = solved.state.value!!.copy(won = true)
        assertNull(solved.hint())

        val over = started()
        over.leaveSession()
        assertNull(over.hint())
    }

    @Test
    fun `a hint is a legal move and is cleared by the next tap, by undo and by a new deal`() {
        val game = started()
        val hint = game.hint()!!
        assertNotNull(game.hintMove.value)

        // Legal: playing it as a player would changes the board and is one undo step.
        val before = board(game)
        play(game, hint)
        assertNull("the next tap clears the hint", game.hintMove.value)
        assertNotEquals(before, board(game))

        game.hint()
        assertNotNull(game.hintMove.value)
        game.undo()
        assertNull("undo clears the hint", game.hintMove.value)

        game.hint()
        game.playAgain()
        assertNull("a new deal clears the hint", game.hintMove.value)
    }

    @Test
    fun `a hint does not alter the board or the undo history`() {
        val game = started()
        val before = board(game)
        game.hint()
        assertEquals(before, board(game))
        assertFalse(game.canUndo)
    }
}
