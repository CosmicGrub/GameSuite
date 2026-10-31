package com.gamesuite.games.chess

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic draws (fifty-move rule, insufficient material), the chess clock and the off-thread
 * bot API. Squares are a1 = 0 ... h8 = 63; the clock tests drive a fake millisecond source.
 */
class ChessDrawsAndClockTest {

    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) { now += ms }
    }

    private val twoHumans = listOf(PlayerInfo("white", "White"), PlayerInfo("black", "Black"))
    private val humanAndCpu = listOf(PlayerInfo("white", "You"), PlayerInfo("black", "CPU", isBot = true))

    private fun sq(file: Char, rank: Int): Int = (rank - 1) * 8 + (file - 'a')

    private fun newGame(
        clock: FakeClock = FakeClock(),
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): ChessGame {
        val game = ChessGame(nowMs = { clock.now })
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun timedGame(clock: FakeClock, id: String): ChessGame {
        val game = newGame(clock)
        game.configureClock(parseChessClock(id))
        game.startMatch()
        return game
    }

    /** Seats [pieces] on an otherwise empty board, no castling rights. */
    private fun seed(
        game: ChessGame,
        side: PieceColor,
        halfmoveClock: Int = 0,
        vararg pieces: Pair<Int, Piece>
    ) {
        val board = MutableList<Piece?>(64) { null }
        for ((square, piece) in pieces) board[square] = piece
        game.state.value = ChessState(
            board = board,
            sideToMove = side,
            castleWK = false, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = null,
            lastAction = "fixture",
            halfmoveClock = halfmoveClock
        )
    }

    private val wK = Piece(PieceType.KING, PieceColor.WHITE)
    private val bK = Piece(PieceType.KING, PieceColor.BLACK)
    private val wR = Piece(PieceType.ROOK, PieceColor.WHITE)
    private val bR = Piece(PieceType.ROOK, PieceColor.BLACK)
    private val wB = Piece(PieceType.BISHOP, PieceColor.WHITE)
    private val bB = Piece(PieceType.BISHOP, PieceColor.BLACK)
    private val wN = Piece(PieceType.KNIGHT, PieceColor.WHITE)
    private val bN = Piece(PieceType.KNIGHT, PieceColor.BLACK)
    private val wQ = Piece(PieceType.QUEEN, PieceColor.WHITE)
    private val wP = Piece(PieceType.PAWN, PieceColor.WHITE)
    private val bP = Piece(PieceType.PAWN, PieceColor.BLACK)

    private fun board(vararg pieces: Pair<Int, Piece>): List<Piece?> {
        val b = MutableList<Piece?>(64) { null }
        for ((s, p) in pieces) b[s] = p
        return b
    }

    // --- Fifty-move rule --------------------------------------------------------------------

    @Test
    fun `a quiet move adds one to the halfmove clock and the hundredth half-move draws`() {
        val game = newGame()
        seed(game, PieceColor.WHITE, 98, sq('a', 1) to wK, sq('d', 1) to wR, sq('h', 8) to bK)

        game.playMove(0, sq('d', 1), sq('d', 2))
        assertEquals(99, game.state.value!!.halfmoveClock)
        assertFalse(game.state.value!!.roundOver)

        game.playMove(1, sq('h', 8), sq('h', 7))
        val s = game.state.value!!
        assertEquals(100, s.halfmoveClock)
        assertTrue(s.roundOver)
        assertEquals(ChessResult.DRAW_FIFTY_MOVE, s.result)
        assertNull(s.winnerPlayerId)
        assertEquals(1, game.draws.value)
        assertTrue(s.lastAction, s.lastAction.contains("fifty"))
    }

    @Test
    fun `a capture resets the halfmove clock`() {
        val game = newGame()
        seed(game, PieceColor.WHITE, 99, sq('a', 1) to wK, sq('d', 1) to wR, sq('d', 6) to bP, sq('h', 8) to bK)
        game.playMove(0, sq('d', 1), sq('d', 6))
        assertEquals(0, game.state.value!!.halfmoveClock)
        assertEquals(ChessResult.IN_PROGRESS, game.state.value!!.result)
    }

    @Test
    fun `a pawn move resets the halfmove clock`() {
        val game = newGame()
        seed(game, PieceColor.WHITE, 99, sq('a', 1) to wK, sq('b', 2) to wP, sq('h', 8) to bK, sq('h', 6) to bR)
        game.playMove(0, sq('b', 2), sq('b', 3))
        assertEquals(0, game.state.value!!.halfmoveClock)
        assertFalse(game.state.value!!.roundOver)
    }

    @Test
    fun `a checkmate on the hundredth half-move still wins`() {
        val game = newGame()
        // Back-rank mate: Rd1-d8#. Black's king is boxed in by its own pawns.
        seed(
            game, PieceColor.WHITE, 99,
            sq('a', 1) to wK, sq('d', 1) to wR,
            sq('g', 8) to bK, sq('f', 7) to bP, sq('g', 7) to bP, sq('h', 7) to bP
        )
        game.playMove(0, sq('d', 1), sq('d', 8))
        val s = game.state.value!!
        assertEquals(ChessResult.WHITE_WINS, s.result)
        assertFalse(s.timeout)
        assertEquals(1, game.scoreP1.value)
    }

    @Test
    fun `a stalemate delivered by a move is a draw`() {
        val game = newGame()
        seed(game, PieceColor.WHITE, 0, sq('b', 6) to wK, sq('e', 7) to wQ, sq('a', 8) to bK)
        game.playMove(0, sq('e', 7), sq('c', 7))
        val s = game.state.value!!
        assertEquals(ChessResult.DRAW_STALEMATE, s.result)
        assertTrue(s.roundOver)
        assertEquals(1, game.draws.value)
    }

    @Test
    fun `the halfmove clock survives resume and the network state`() {
        val game = newGame()
        game.startMatch()
        game.playMove(0, sq('g', 1), sq('f', 3))
        game.playMove(1, sq('b', 8), sq('c', 6))
        assertEquals(2, game.state.value!!.halfmoveClock)

        val restored = newGame()
        assertTrue(restored.restore(game.snapshot()!!))
        assertEquals(2, restored.state.value!!.halfmoveClock)
        assertEquals(game.state.value, restored.state.value)

        val wire = ChessNetState(
            board = game.state.value!!.board, sideToMove = PieceColor.WHITE,
            castleWK = true, castleWQ = true, castleBK = true, castleBQ = true,
            epSquare = null, lastFrom = null, lastTo = null, lastAction = "x",
            roundOver = false, result = ChessResult.IN_PROGRESS, inCheck = false,
            winnerPlayerId = null, scoreP1 = 0, scoreP2 = 0, draws = 0, matchOver = false,
            halfmoveClock = 37
        )
        val decoded = Json.decodeFromString<ChessNetState>(Json.encodeToString(wire))
        assertEquals(37, decoded.halfmoveClock)
    }

    @Test
    fun `a network state or save without the field reads as zero`() {
        val game = newGame()
        game.startMatch()
        val snapshot = game.snapshot()!!
        assertFalse("an untimed fresh game writes none of the new fields", snapshot.contains("halfmoveClock") || snapshot.contains("clockId"))
        val restored = newGame()
        assertTrue(restored.restore(snapshot))
        assertEquals(0, restored.state.value!!.halfmoveClock)
        assertNull(restored.clockSpec.value)

        val wireJson = Json.encodeToString(
            ChessNetState(
                board = game.state.value!!.board, sideToMove = PieceColor.WHITE,
                castleWK = true, castleWQ = true, castleBK = true, castleBQ = true,
                epSquare = null, lastFrom = null, lastTo = null, lastAction = "x",
                roundOver = false, result = ChessResult.IN_PROGRESS, inCheck = false,
                winnerPlayerId = null, scoreP1 = 0, scoreP2 = 0, draws = 0, matchOver = false
            )
        )
        assertEquals(0, Json.decodeFromString<ChessNetState>(wireJson).halfmoveClock)
    }

    @Test
    fun `a networked guest receives the host's halfmove clock`() {
        val p = ChessNetKit.connected()
        p.host.game.playMove(0, sq('g', 1), sq('f', 3))
        p.guest.game.playMove(1, sq('b', 8), sq('c', 6))
        assertEquals(2, p.host.game.state.value!!.halfmoveClock)
        assertEquals(2, p.guest.game.state.value!!.halfmoveClock)
    }

    // --- Insufficient material --------------------------------------------------------------

    @Test
    fun `insufficient material is recognised for the standard cases`() {
        assertTrue("K v K", hasInsufficientMaterial(board(0 to wK, 63 to bK)))
        assertTrue("K+N v K", hasInsufficientMaterial(board(0 to wK, 63 to bK, 9 to wN)))
        assertTrue("K v K+B", hasInsufficientMaterial(board(0 to wK, 63 to bK, 54 to bB)))
        // c1 (2) and f8 (61) are both dark squares.
        assertTrue("K+B v K+B same colour", hasInsufficientMaterial(board(sq('e', 1) to wK, sq('e', 8) to bK, sq('c', 1) to wB, sq('f', 8) to bB)))
        assertFalse("K+B v K+B opposite colours", hasInsufficientMaterial(board(sq('e', 1) to wK, sq('e', 8) to bK, sq('c', 1) to wB, sq('c', 8) to bB)))
        assertFalse("K+N+N v K", hasInsufficientMaterial(board(0 to wK, 63 to bK, 9 to wN, 10 to wN)))
        assertFalse("K+B v K+N", hasInsufficientMaterial(board(0 to wK, 63 to bK, 2 to wB, 54 to bN)))
        assertFalse("a pawn", hasInsufficientMaterial(board(0 to wK, 63 to bK, 9 to wP)))
        assertFalse("a rook", hasInsufficientMaterial(board(0 to wK, 63 to bK, 9 to bR)))
        assertFalse("a queen", hasInsufficientMaterial(board(0 to wK, 63 to bK, 9 to wQ)))
        assertFalse("the start position", hasInsufficientMaterial(newGame().also { it.startMatch() }.state.value!!.board))
    }

    @Test
    fun `a capture that leaves insufficient material ends the game as a draw`() {
        val game = newGame()
        // Bc1xf4 leaves White K+B against a bare king.
        seed(game, PieceColor.WHITE, 0, sq('e', 1) to wK, sq('c', 1) to wB, sq('h', 8) to bK, sq('f', 4) to bN)
        game.playMove(0, sq('c', 1), sq('f', 4))
        val s = game.state.value!!
        assertEquals(ChessResult.DRAW_INSUFFICIENT_MATERIAL, s.result)
        assertTrue(s.roundOver)
        assertNull(s.winnerPlayerId)
        assertEquals(1, game.draws.value)
        assertEquals(0, game.scoreP1.value + game.scoreP2.value)
        assertTrue(s.lastAction, s.lastAction.contains("insufficient"))
    }

    @Test
    fun `mating material is a pawn, a major piece or two minors`() {
        assertTrue(hasMatingMaterial(board(0 to wK, 9 to wP), PieceColor.WHITE))
        assertTrue(hasMatingMaterial(board(0 to wK, 9 to wR), PieceColor.WHITE))
        assertTrue(hasMatingMaterial(board(0 to wK, 9 to wB, 10 to wN), PieceColor.WHITE))
        assertFalse(hasMatingMaterial(board(0 to wK, 9 to wB), PieceColor.WHITE))
        assertFalse(hasMatingMaterial(board(0 to wK, 9 to wN), PieceColor.WHITE))
        assertFalse(hasMatingMaterial(board(0 to wK, 63 to bK, 9 to bR), PieceColor.WHITE))
    }

    // --- Clock: parsing and display ---------------------------------------------------------

    @Test
    fun `clock ids parse to minutes and increment`() {
        assertEquals(ChessClockSpec(3, 0), parseChessClock("3+0"))
        assertEquals(ChessClockSpec(5, 0), parseChessClock("5+0"))
        assertEquals(ChessClockSpec(10, 0), parseChessClock("10+0"))
        assertEquals(ChessClockSpec(15, 10), parseChessClock("15+10"))
        assertEquals(180_000L, parseChessClock("3+0")!!.initialMs)
        assertEquals(10_000L, parseChessClock("15+10")!!.incrementMs)
        assertEquals("15+10", parseChessClock("15+10")!!.id)
    }

    @Test
    fun `no clock and malformed ids parse to null`() {
        for (bad in listOf(null, "none", "", " ", "abc", "5", "0+5", "-3+0", "5+-1", "3+0+1", "x+y", "5+"))
            assertNull("'$bad'", parseChessClock(bad))
    }

    @Test
    fun `clock text is m ss and tenths under ten seconds`() {
        assertEquals("4:32", formatChessClock(272_000))
        assertEquals("10:00", formatChessClock(600_000))
        assertEquals("1:05", formatChessClock(65_000))
        assertEquals("0:10", formatChessClock(10_000))
        assertEquals("9.9", formatChessClock(9_999))
        assertEquals("9.4", formatChessClock(9_450))
        assertEquals("0.0", formatChessClock(0))
        assertEquals("0.0", formatChessClock(-500))
    }

    // --- Clock: engine ----------------------------------------------------------------------

    @Test
    fun `the active side's clock runs and the other side's stands still`() {
        val clock = FakeClock(1_000)
        val game = timedGame(clock, "5+0")
        assertEquals(300_000L, game.remainingMs(PieceColor.WHITE))
        clock.advance(3_000)
        assertEquals(297_000L, game.remainingMs(PieceColor.WHITE))
        assertEquals(300_000L, game.remainingMs(PieceColor.BLACK))

        game.playMove(0, sq('e', 2), sq('e', 4))
        clock.advance(5_000)
        assertEquals("white stopped when it moved", 297_000L, game.remainingMs(PieceColor.WHITE))
        assertEquals(295_000L, game.remainingMs(PieceColor.BLACK))
        assertTrue(game.isClockRunning)
    }

    @Test
    fun `the increment is added after a move`() {
        val clock = FakeClock()
        val game = timedGame(clock, "15+10")
        clock.advance(2_000)
        game.playMove(0, sq('e', 2), sq('e', 4))
        assertEquals(900_000L - 2_000L + 10_000L, game.remainingMs(PieceColor.WHITE))
        clock.advance(4_000)
        game.playMove(1, sq('e', 7), sq('e', 5))
        assertEquals(900_000L - 4_000L + 10_000L, game.remainingMs(PieceColor.BLACK))
    }

    @Test
    fun `an untimed game has no clock at all`() {
        val clock = FakeClock()
        val game = newGame(clock)
        game.startMatch()
        clock.advance(10_000_000)
        assertNull(game.clockSpec.value)
        assertEquals(0L, game.remainingMs(PieceColor.WHITE))
        assertFalse(game.tickClock())
        assertFalse(game.isClockRunning)
        assertFalse(game.state.value!!.roundOver)
    }

    @Test
    fun `running out of time loses on time`() {
        val clock = FakeClock()
        val game = timedGame(clock, "3+0")
        game.playMove(0, sq('e', 2), sq('e', 4))
        clock.advance(179_999)
        assertFalse("one millisecond left", game.tickClock())
        clock.advance(1)
        assertTrue(game.tickClock())

        val s = game.state.value!!
        assertTrue(s.roundOver)
        assertTrue(s.timeout)
        assertEquals(ChessResult.WHITE_WINS, s.result)
        assertEquals("white", s.winnerPlayerId)
        assertEquals(1, game.scoreP1.value)
        assertEquals(0L, game.remainingMs(PieceColor.BLACK))
        assertFalse("the clock stops with the round", game.isClockRunning)

        val frozen = game.state.value
        game.playMove(1, sq('e', 7), sq('e', 5))
        assertEquals(frozen, game.state.value)
    }

    @Test
    fun `a move made after the clock ran out is a flag fall, not a move`() {
        val clock = FakeClock()
        val game = timedGame(clock, "3+0")
        clock.advance(181_000)
        game.playMove(0, sq('e', 2), sq('e', 4))
        val s = game.state.value!!
        assertTrue(s.timeout)
        assertEquals(ChessResult.BLACK_WINS, s.result)
        assertNull("the pawn never moved", s.board[sq('e', 4)])
    }

    @Test
    fun `a flag fall against an opponent who cannot mate is a draw`() {
        val clock = FakeClock()
        val game = timedGame(clock, "3+0")
        // White (to move, about to flag) still has a pawn; Black has a lone king.
        seed(game, PieceColor.WHITE, 0, sq('a', 1) to wK, sq('h', 8) to bK, sq('d', 4) to wP)
        clock.advance(180_000)
        assertTrue(game.tickClock())
        val s = game.state.value!!
        assertEquals(ChessResult.DRAW_INSUFFICIENT_MATERIAL, s.result)
        assertTrue(s.timeout)
        assertNull(s.winnerPlayerId)
        assertEquals(1, game.draws.value)
        assertEquals(0, game.scoreP1.value + game.scoreP2.value)
    }

    @Test
    fun `pause stops the clock and resume restarts it`() {
        val clock = FakeClock()
        val game = timedGame(clock, "5+0")
        clock.advance(1_000)
        game.pause()
        assertFalse(game.isClockRunning)
        clock.advance(50_000)
        assertEquals(299_000L, game.remainingMs(PieceColor.WHITE))
        assertFalse(game.tickClock())
        game.pause() // idempotent
        assertEquals(299_000L, game.remainingMs(PieceColor.WHITE))

        game.resume()
        assertTrue(game.isClockRunning)
        clock.advance(2_000)
        assertEquals(297_000L, game.remainingMs(PieceColor.WHITE))
        game.resume() // idempotent: must not restart the running stamp
        clock.advance(1_000)
        assertEquals(296_000L, game.remainingMs(PieceColor.WHITE))
    }

    @Test
    fun `the snapshot stores active remaining time and restores it onto any clock`() {
        val clock = FakeClock(10_000)
        val game = timedGame(clock, "10+0")
        clock.advance(4_000)
        game.playMove(0, sq('e', 2), sq('e', 4)) // white: 596 s
        clock.advance(7_000)                     // black has used 7 s
        val snapshot = game.snapshot()!!

        val laterClock = FakeClock(9_000_000) // a different process, a different time base
        val restored = newGame(laterClock)
        assertTrue(restored.restore(snapshot))
        assertEquals(parseChessClock("10+0"), restored.clockSpec.value)
        assertEquals(596_000L, restored.remainingMs(PieceColor.WHITE))
        assertEquals(593_000L, restored.remainingMs(PieceColor.BLACK))
        assertTrue("the restored clock runs for the side to move", restored.isClockRunning)

        laterClock.advance(1_000)
        assertEquals(592_000L, restored.remainingMs(PieceColor.BLACK))
        assertEquals(596_000L, restored.remainingMs(PieceColor.WHITE))
    }

    @Test
    fun `a finished timed game keeps its result through a resume`() {
        val clock = FakeClock()
        val game = timedGame(clock, "3+0")
        clock.advance(180_000)
        game.tickClock()
        val restored = newGame(FakeClock())
        assertTrue(restored.restore(game.snapshot()!!))
        assertTrue(restored.state.value!!.timeout)
        assertEquals(ChessResult.BLACK_WINS, restored.state.value!!.result)
        assertFalse(restored.isClockRunning)
    }

    @Test
    fun `play again keeps the time control and gives both sides a full clock`() {
        val clock = FakeClock()
        val game = timedGame(clock, "5+0")
        clock.advance(4_000)
        game.playMove(0, sq('e', 2), sq('e', 4))
        game.playAgain()
        assertEquals(parseChessClock("5+0"), game.clockSpec.value)
        assertEquals(300_000L, game.remainingMs(PieceColor.WHITE))
        assertEquals(300_000L, game.remainingMs(PieceColor.BLACK))
    }

    @Test
    fun `undo keeps the time used and the clock follows the side to move`() {
        val clock = FakeClock()
        val game = timedGame(clock, "5+0")
        clock.advance(2_000)
        game.playMove(0, sq('e', 2), sq('e', 4))
        clock.advance(3_000)
        game.undo()
        assertEquals(PieceColor.WHITE, game.state.value!!.sideToMove)
        assertEquals("black used 3 s thinking, not refunded", 297_000L, game.remainingMs(PieceColor.BLACK))
        clock.advance(1_000)
        assertEquals("white's clock runs again after the take-back", 297_000L, game.remainingMs(PieceColor.WHITE))
        assertEquals(297_000L, game.remainingMs(PieceColor.BLACK))
    }

    @Test
    fun `networked play never has a clock`() {
        val game = newGame(mode = PlayMode.ONLINE)
        game.configureClock(parseChessClock("5+0"))
        assertNull(game.clockSpec.value)
    }

    // --- Off-thread bot ---------------------------------------------------------------------

    @Test
    fun `the three-step bot turn plays the same kind of move as playBotTurn`() {
        val game = newGame(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.EASY
        game.startMatch()
        assertNull("not the bot's turn yet", game.botRequest())

        game.playMove(0, sq('e', 2), sq('e', 4))
        val request = game.botRequest()
        assertNotNull(request)
        val move = game.searchBotMove(request!!)
        assertNotNull(move)
        assertTrue((move!!.from to move.to) in game.allLegalMoves())
        assertEquals("searching changes nothing", PieceColor.BLACK, game.state.value!!.sideToMove)

        assertTrue(game.applyBotMove(request, move))
        assertEquals(PieceColor.WHITE, game.state.value!!.sideToMove)
        assertEquals(move.to, game.state.value!!.lastTo)
    }

    @Test
    fun `a bot move is refused when the position changed during the search`() {
        val game = newGame(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.MEDIUM
        game.startMatch()
        game.playMove(0, sq('e', 2), sq('e', 4))
        val request = game.botRequest()!!
        val move = game.searchBotMove(request)!!

        game.undo() // the player takes the move back while the bot is still "thinking"
        val afterUndo = game.state.value
        assertFalse(game.applyBotMove(request, move))
        assertEquals(afterUndo, game.state.value)

        game.playMove(0, sq('d', 2), sq('d', 4))
        val second = game.botRequest()!!
        game.startMatch() // restart in between
        assertFalse(game.applyBotMove(second, game.searchBotMove(second)!!))
        assertEquals(PieceColor.WHITE, game.state.value!!.sideToMove)
    }

    @Test
    fun `a bot move is refused once the session is over`() {
        val game = newGame(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.EASY
        game.startMatch()
        game.playMove(0, sq('e', 2), sq('e', 4))
        val request = game.botRequest()!!
        val move = game.searchBotMove(request)!!
        game.leaveSession()
        assertFalse(game.applyBotMove(request, move))
    }

    @Test
    fun `the bot's clock keeps running while it thinks`() {
        val clock = FakeClock()
        val game = newGame(clock, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.EASY
        game.configureClock(parseChessClock("5+0"))
        game.startMatch()
        game.playMove(0, sq('e', 2), sq('e', 4))
        val request = game.botRequest()!!
        clock.advance(2_500) // the think delay plus the search
        val move = game.searchBotMove(request)!!
        game.applyBotMove(request, move)
        assertEquals(300_000L - 2_500L, game.remainingMs(PieceColor.BLACK))
    }
}
