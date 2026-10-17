package com.gamesuite.games.checkers

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Hub's "who moves first" option: [CheckersGame.openingSeatIndex] names the roster seat that
 * plays Light (side 1) and so opens. Roster order never changes; only the seat <-> colour mapping.
 */
class CheckersOpeningSeatTest {

    private fun newGame(
        p0Bot: Boolean = false,
        p1Bot: Boolean = false,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): CheckersGame {
        val game = CheckersGame()
        game.init(
            GameContext(
                activeMode = mode,
                players = listOf(
                    PlayerInfo(playerId = "p0", displayName = "P0", isBot = p0Bot),
                    PlayerInfo(playerId = "p1", displayName = "P1", isBot = p1Bot)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    private fun firstLegalHop(game: CheckersGame): CheckersMove {
        for (row in 0..7) for (col in 0..7) {
            val dest = game.legalDestinationsFrom(row, col).firstOrNull() ?: continue
            return CheckersMove(row, col, dest.first, dest.second, isCapture = kotlin.math.abs(dest.first - row) == 2)
        }
        error("no legal move")
    }

    private fun play(game: CheckersGame): CheckersMove {
        val seat = game.state.value!!.currentPlayerIndex
        val hop = firstLegalHop(game)
        game.playMove(seat, hop.fromRow, hop.fromCol, hop.toRow, hop.toCol)
        return hop
    }

    @Test
    fun `default mapping is unchanged - seat 1 plays Light and opens`() {
        val game = newGame()
        assertEquals(1, game.openingSeatIndex)
        assertEquals(1, game.sideOfSeat(1))
        assertEquals(0, game.sideOfSeat(0))
        game.startMatch()
        assertEquals(1, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `opening seat 0 makes seat 0 play Light and open`() {
        val game = newGame()
        game.openingSeatIndex = 0
        assertEquals(1, game.sideOfSeat(0))
        assertEquals(0, game.sideOfSeat(1))
        assertEquals(0, game.seatOfSide(1))
        assertEquals(1, game.seatOfSide(0))
        game.startMatch()
        val s = game.state.value!!
        assertEquals(0, s.currentPlayerIndex)
        // Only Light (owner 1) pieces may move, and they belong to seat 0.
        for (sq in 0 until 64) {
            if (game.hasLegalMoveFrom(sq / 8, sq % 8)) assertEquals(1, s.board[sq]!!.owner)
        }
    }

    @Test
    fun `out-of-range opening seat is clamped`() {
        val game = newGame()
        game.openingSeatIndex = 7
        assertEquals(1, game.openingSeatIndex)
        game.openingSeatIndex = -3
        assertEquals(0, game.openingSeatIndex)
    }

    @Test
    fun `the wrong seat cannot open`() {
        val game = newGame()
        game.openingSeatIndex = 0
        game.startMatch()
        val before = game.state.value
        val hop = firstLegalHop(game)
        game.playMove(1, hop.fromRow, hop.fromCol, hop.toRow, hop.toCol)
        assertEquals(before, game.state.value)
        game.playMove(0, hop.fromRow, hop.fromCol, hop.toRow, hop.toCol)
        assertEquals(1, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a bot in seat 0 that opens plays Light and passes the turn to seat 1`() {
        val game = newGame(p0Bot = true)
        game.openingSeatIndex = 0
        game.startMatch()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        game.playBotTurn()
        val s = game.state.value!!
        assertEquals(1, s.currentPlayerIndex)
        val move = s.lastMove!!
        assertEquals(1, s.pieceAt(move.toRow, move.toCol)!!.owner)
    }

    @Test
    fun `the human in seat 0 can be second - bot in seat 1 opens as Light`() {
        val game = newGame(p1Bot = true)
        game.openingSeatIndex = 1
        game.startMatch()
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        game.playBotTurn()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a round won by Light is credited to the opening seat`() {
        val game = newGame()
        game.openingSeatIndex = 0
        val cells = MutableList<CheckersPiece?>(64) { null }
        cells[2 * 8 + 1] = CheckersPiece(1, PieceKind.MAN) // Light man jumps up-right over (1,2) to (0,3)
        cells[1 * 8 + 2] = CheckersPiece(0, PieceKind.MAN) // the last Dark piece
        game.state.value = CheckersState(board = cells, currentPlayerIndex = 0)
        game.playMove(0, 2, 1, 0, 3)
        val s = game.state.value!!
        assertTrue(s.gameOver)
        assertEquals("p0", s.winnerPlayerId)
        assertEquals(1, game.scoreP1.value)
        assertEquals(0, game.scoreP2.value)
    }

    @Test
    fun `the same position with the default mapping credits seat 1`() {
        val game = newGame()
        val cells = MutableList<CheckersPiece?>(64) { null }
        cells[2 * 8 + 1] = CheckersPiece(1, PieceKind.MAN)
        cells[1 * 8 + 2] = CheckersPiece(0, PieceKind.MAN)
        game.state.value = CheckersState(board = cells, currentPlayerIndex = 1)
        game.playMove(1, 2, 1, 0, 3)
        assertEquals("p1", game.state.value!!.winnerPlayerId)
        assertEquals(1, game.scoreP2.value)
    }

    @Test
    fun `the opening seat survives a snapshot round trip`() {
        val a = newGame()
        a.openingSeatIndex = 0
        a.startMatch()
        play(a)
        val b = newGame()
        assertEquals(1, b.openingSeatIndex)
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(0, b.openingSeatIndex)
        assertEquals(a.state.value!!.board, b.state.value!!.board)
        assertEquals(1, b.state.value!!.currentPlayerIndex)
        // And it plays on under the restored mapping: seat 1 is Dark and moves downward.
        val hop = play(b)
        assertEquals(0, b.state.value!!.currentPlayerIndex)
        assertEquals(0, b.state.value!!.pieceAt(hop.toRow, hop.toCol)!!.owner)
    }

    @Test
    fun `a legacy snapshot without the field restores with seat 1 opening`() {
        val a = newGame()
        a.startMatch() // default mapping: the field is not even written
        play(a)
        val snapshot = a.snapshot()!!
        assertFalse(snapshot.contains("openingSeatIndex"))
        val b = newGame()
        b.openingSeatIndex = 0
        assertTrue(b.restore(snapshot))
        assertEquals(1, b.openingSeatIndex)
        assertEquals(a.state.value!!.currentPlayerIndex, b.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a snapshot with an invalid opening seat is rejected`() {
        val a = newGame()
        a.openingSeatIndex = 0
        a.startMatch()
        val bad = a.snapshot()!!.replace("\"openingSeatIndex\":0", "\"openingSeatIndex\":5")
        assertFalse(newGame().restore(bad))
    }

    @Test
    fun `pass and play with two humans - alternate seats, hint works, undo takes back one move`() {
        for (opening in 0..1) {
            val game = newGame(mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
            game.openingSeatIndex = opening
            game.startMatch()
            assertEquals(opening, game.state.value!!.currentPlayerIndex)
            val start = game.state.value!!.board
            assertNotNull(game.hint())
            assertFalse(game.canUndo)

            play(game)
            assertEquals(1 - opening, game.state.value!!.currentPlayerIndex)
            val afterFirst = game.state.value!!.board
            assertNotNull(game.hint(), "second human gets a hint too")
            play(game)
            assertEquals(opening, game.state.value!!.currentPlayerIndex)

            assertTrue(game.canUndo)
            game.undo()
            assertEquals(1 - opening, game.state.value!!.currentPlayerIndex)
            assertEquals(afterFirst, game.state.value!!.board)
            game.undo()
            assertEquals(opening, game.state.value!!.currentPlayerIndex)
            assertEquals(start, game.state.value!!.board)
            assertFalse(game.canUndo)
        }
    }

    @Test
    fun `playBotTurn does nothing in pass and play`() {
        val game = newGame(mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        game.startMatch()
        val before = game.state.value
        game.playBotTurn()
        assertEquals(before, game.state.value)
    }

    @Test
    fun `undo against the CPU with the CPU opening returns to the human's first turn`() {
        val game = newGame(p1Bot = true)
        game.openingSeatIndex = 1
        game.startMatch()
        game.playBotTurn() // CPU opens
        val humanTurnBoard = game.state.value!!.board
        play(game) // human replies
        game.playBotTurn()
        game.undo()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertEquals(humanTurnBoard, game.state.value!!.board)
    }
}
