package com.gamesuite.games.chess

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChessGame.whiteSeatIndex]: which roster seat plays White (and moves first). The Hub's
 * "opponent moves first" option sets it to 1; the roster order never changes.
 * Squares are 0..63 with a1 = 0, so e2 = 12, e4 = 28.
 */
class ChessWhiteSeatTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("p2", "Ben"))
    private val humanAndCpu = listOf(PlayerInfo("p1", "You"), PlayerInfo("bot1", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo>,
        mode: PlayMode,
        whiteSeat: Int
    ): ChessGame {
        val game = ChessGame()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.whiteSeatIndex = whiteSeat
        return game
    }

    private fun started(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        whiteSeat: Int = 0
    ) = newGame(players, mode, whiteSeat).also { it.startMatch() }

    /** Plays (from, to) pairs, each by whichever roster seat currently has the move. */
    private fun play(game: ChessGame, vararg moves: Pair<Int, Int>) {
        for ((from, to) in moves) {
            game.playMove(game.seatForColor(game.state.value!!.sideToMove), from, to)
        }
    }

    private val e2e4 = 12 to 28

    /** 1. f3 e5 2. g4 Qh4#: Black wins in four plies. */
    private val foolsMate = arrayOf(13 to 21, 52 to 36, 14 to 30, 59 to 31)

    @Test
    fun `seat 0 plays White by default and the free function agrees`() {
        val game = started()
        assertEquals(0, game.whiteSeatIndex)
        assertEquals(0, game.seatForColor(PieceColor.WHITE))
        assertEquals(1, game.seatForColor(PieceColor.BLACK))
        assertEquals(PieceColor.WHITE, game.colorForSeat(0))
        assertEquals(playerIndexForColor(PieceColor.WHITE), game.seatForColor(PieceColor.WHITE))
    }

    @Test
    fun `an out of range seat is treated as seat 0`() {
        val game = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 0)
        game.whiteSeatIndex = 5
        assertEquals(0, game.whiteSeatIndex)
        game.whiteSeatIndex = -1
        assertEquals(0, game.whiteSeatIndex)
        game.whiteSeatIndex = 1
        assertEquals(1, game.whiteSeatIndex)
    }

    @Test
    fun `with white seat 1 the second roster seat opens and seat 0 cannot`() {
        val game = started(whiteSeat = 1)
        assertEquals(PieceColor.WHITE, game.state.value!!.sideToMove)
        assertEquals(1, game.seatForColor(PieceColor.WHITE))
        assertEquals(PieceColor.BLACK, game.colorForSeat(0))

        game.playMove(0, e2e4.first, e2e4.second)
        assertNull("seat 0 plays Black, it must not be able to open", game.state.value!!.lastFrom)

        game.playMove(1, e2e4.first, e2e4.second)
        assertEquals(e2e4.first, game.state.value!!.lastFrom)
        assertEquals(PieceColor.BLACK, game.state.value!!.sideToMove)

        game.playMove(1, 52, 36) // seat 1 again: not its turn
        assertEquals("the same seat cannot move twice", 28, game.state.value!!.lastTo)
        game.playMove(0, 52, 36)
        assertEquals(36, game.state.value!!.lastTo)
    }

    @Test
    fun `move text names the seat that moved, not a hard-coded colour owner`() {
        val game = started(whiteSeat = 1)
        play(game, e2e4)
        assertTrue(game.state.value!!.lastAction, game.state.value!!.lastAction.startsWith("Ben moved"))
        play(game, 52 to 36)
        assertTrue(game.state.value!!.lastAction, game.state.value!!.lastAction.startsWith("Ann moved"))
    }

    @Test
    fun `a bot on the white seat is asked to open and the human then plays Black`() {
        val game = started(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT, whiteSeat = 1)
        val s0 = game.state.value!!
        assertNull(s0.lastFrom)
        assertEquals(PieceColor.WHITE, s0.sideToMove)
        assertEquals("the CPU (seat 1) is on move", 1, game.seatForColor(s0.sideToMove))
        assertFalse("hints are for the human only", game.hint() != null)

        game.playBotTurn()
        val s1 = game.state.value!!
        assertNotNull("the bot made its opening move", s1.lastFrom)
        assertEquals(PieceColor.BLACK, s1.sideToMove)
        assertEquals("now the human (seat 0) is on move", 0, game.seatForColor(s1.sideToMove))

        game.playBotTurn() // not the bot's turn: a no-op
        assertEquals(s1, game.state.value)

        val (from, to) = game.allLegalMoves().first()
        game.playMove(0, from, to)
        assertEquals(to, game.state.value!!.lastTo)
        assertTrue("undo takes back the human move and the bot's opening stays", game.canUndo)
        game.undo()
        assertEquals(s1, game.state.value)
    }

    @Test
    fun `with the human on White a bot on seat 1 does nothing until the human moves`() {
        val game = started(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT, whiteSeat = 0)
        game.playBotTurn()
        assertNull("it is the human's move", game.state.value!!.lastFrom)
    }

    @Test
    fun `a win is attributed to the seat that played the winning colour`() {
        val game = started(whiteSeat = 1) // Ann (seat 0) is Black, Ben (seat 1) is White
        play(game, *foolsMate)
        val s = game.state.value!!
        assertEquals(ChessResult.BLACK_WINS, s.result)
        assertEquals("Ann played Black", "p1", s.winnerPlayerId)
        assertEquals(1, game.scoreP1.value)
        assertEquals(0, game.scoreP2.value)
    }

    @Test
    fun `the default seat keeps the old attribution`() {
        val game = started(whiteSeat = 0)
        play(game, *foolsMate)
        assertEquals("Ben played Black", "p2", game.state.value!!.winnerPlayerId)
        assertEquals(0, game.scoreP1.value)
        assertEquals(1, game.scoreP2.value)
    }

    @Test
    fun `the white seat survives a snapshot round trip`() {
        val a = started(whiteSeat = 1)
        play(a, e2e4, 52 to 36)
        val snapshot = a.snapshot()!!

        val b = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, whiteSeat = 0)
        assertTrue(b.restore(snapshot))
        assertEquals(1, b.whiteSeatIndex)
        assertEquals(a.state.value, b.state.value)

        play(a, 5 to 33)
        play(b, 5 to 33)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `a save written without a white seat restores as seat 0`() {
        val a = started(whiteSeat = 0)
        play(a, e2e4)
        val snapshot = a.snapshot()!!
        assertFalse("the default is not written, exactly like an old save", snapshot.contains("whiteSeat"))

        val b = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, whiteSeat = 1)
        assertTrue(b.restore(snapshot))
        assertEquals(0, b.whiteSeatIndex)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `an out of range white seat in a save is rejected`() {
        val a = started(whiteSeat = 1)
        play(a, e2e4)
        val snapshot = a.snapshot()!!
        assertTrue(snapshot.contains("\"whiteSeat\":1"))

        val b = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, whiteSeat = 0)
        assertFalse(b.restore(snapshot.replace("\"whiteSeat\":1", "\"whiteSeat\":2")))
    }

    @Test
    fun `pass and play undo takes back exactly one move`() {
        val game = started(whiteSeat = 1)
        play(game, e2e4, 52 to 36)
        assertTrue(game.canUndo)
        game.undo()
        val s = game.state.value!!
        assertEquals("only Black's reply was taken back", 28, s.lastTo)
        assertEquals(PieceColor.BLACK, s.sideToMove)
        assertEquals(0, game.seatForColor(s.sideToMove))
        game.undo()
        assertNull(game.state.value!!.lastFrom)
        assertEquals(PieceColor.WHITE, game.state.value!!.sideToMove)
    }

    @Test
    fun `no bot logic runs in pass and play`() {
        val game = started(whiteSeat = 1)
        val before = game.state.value
        game.playBotTurn()
        assertEquals(before, game.state.value)
    }

    @Test
    fun `play again keeps the white seat`() {
        val game = started(whiteSeat = 1)
        play(game, *foolsMate)
        game.playAgain()
        assertEquals(1, game.whiteSeatIndex)
        assertEquals(PieceColor.WHITE, game.state.value!!.sideToMove)
        assertEquals(1, game.seatForColor(game.state.value!!.sideToMove))
        assertNull(game.state.value!!.lastFrom)
    }
}
