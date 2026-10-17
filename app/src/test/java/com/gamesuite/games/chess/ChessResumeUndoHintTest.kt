package com.gamesuite.games.chess

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume (snapshot/restore), Undo and Hint for Chess, written against the Connect Four reference.
 * Squares are 0..63 with a1 = 0, so e2 = 12, e4 = 28, g1 = 6, f3 = 21.
 */
class ChessResumeUndoHintTest {

    private val twoHumans = listOf(PlayerInfo("white", "White"), PlayerInfo("black", "Black"))
    private val humanAndCpu = listOf(PlayerInfo("white", "You"), PlayerInfo("black", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): ChessGame {
        val game = ChessGame()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(players: List<PlayerInfo> = twoHumans, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
        newGame(players, mode).also { it.startMatch() }

    /** Plays (from, to) pairs, each by whoever currently has the move. */
    private fun play(game: ChessGame, vararg moves: Pair<Int, Int>) {
        for ((from, to) in moves) {
            game.playMove(playerIndexForColor(game.state.value!!.sideToMove), from, to)
        }
    }

    private val e2e4 = 12 to 28
    private val e7e5 = 52 to 36
    private val g1f3 = 6 to 21
    private val b8c6 = 57 to 42

    /** Ng1-f3, Ng8-f6, Nf3-g1, Nf6-g8: four plies that return to the start position. */
    private val shuffle = arrayOf(6 to 21, 62 to 45, 21 to 6, 45 to 62)

    /** 1. f3 e5 2. g4 Qh4#: Black wins in four plies. */
    private val foolsMate = arrayOf(13 to 21, 52 to 36, 14 to 30, 59 to 31)

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first game, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no board dealt yet", game.snapshot())

        game.startMatch()
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = started(mode = PlayMode.ONLINE)
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = started()
        play(a, e2e4, e7e5, g1f3, b8c6)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.state.value)
        assertEquals(a.scoreP1.value, b.scoreP1.value)
        assertEquals(a.scoreP2.value, b.scoreP2.value)
        assertEquals(a.draws.value, b.draws.value)

        play(a, 5 to 33) // Bf1-b5
        play(b, 5 to 33)
        assertEquals("the same move must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `castling rights, the en passant square and the last move survive a resume`() {
        val a = started()
        play(a, e2e4)
        assertEquals("e3 is the en passant target after e2-e4", 20, a.state.value!!.epSquare)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(20, b.state.value!!.epSquare)
        assertEquals(12, b.state.value!!.lastFrom)
        assertEquals(28, b.state.value!!.lastTo)

        play(a, e7e5, 4 to 12) // Ke1-e2 forfeits both white castling rights
        assertFalse(a.state.value!!.castleWK)
        assertFalse(a.state.value!!.castleWQ)
        val c = newGame()
        assertTrue(c.restore(a.snapshot()!!))
        assertFalse("a lost castling right must not come back", c.state.value!!.castleWK)
        assertFalse(c.state.value!!.castleWQ)
        assertTrue(c.state.value!!.castleBK)
        assertEquals(a.state.value, c.state.value)
    }

    @Test
    fun `the repetition history survives a resume so threefold repetition still fires`() {
        val a = started()
        play(a, *shuffle) // the start position has now occurred twice
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))

        play(b, *shuffle) // third time
        assertEquals(ChessResult.DRAW_REPETITION, b.state.value!!.result)
        assertEquals(1, b.draws.value)

        play(a, *shuffle)
        assertEquals("the un-restored game agrees", ChessResult.DRAW_REPETITION, a.state.value!!.result)
    }

    @Test
    fun `the session tally and a finished game survive a resume`() {
        val a = started()
        play(a, *foolsMate)
        assertEquals(ChessResult.BLACK_WINS, a.state.value!!.result)
        assertEquals(1, a.scoreP2.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.scoreP2.value)
        assertEquals(0, b.scoreP1.value)
        assertTrue(b.state.value!!.roundOver)
        assertEquals(a.state.value, b.state.value)

        b.playAgain()
        assertFalse(b.state.value!!.roundOver)
        assertEquals("Play Again keeps the tally after a resume", 1, b.scoreP2.value)
    }

    @Test
    fun `the difficulty is restored with the game`() {
        val a = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        a.difficulty = CpuDifficulty.HARD
        val snapshot = a.snapshot()!!

        val b = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        b.difficulty = CpuDifficulty.EASY
        assertTrue(b.restore(snapshot))
        assertEquals(CpuDifficulty.HARD, b.difficulty)
    }

    @Test
    fun `a snapshot taken on the CPU's turn restores onto the CPU's turn`() {
        val a = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        play(a, e2e4)
        assertEquals(PieceColor.BLACK, a.state.value!!.sideToMove)
        val b = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(PieceColor.BLACK, b.state.value!!.sideToMove)
        b.playBotTurn()
        assertEquals("the CPU can move after the resume", PieceColor.WHITE, b.state.value!!.sideToMove)
    }

    @Test
    fun `restore rejects garbage and leaves the game untouched`() {
        val game = newGame()
        assertFalse(game.restore("not json at all"))
        assertFalse(game.restore("{}"))
        assertFalse(game.restore(""))
        assertNull("a rejected restore must not deal a board", game.state.value)
    }

    @Test
    fun `restore rejects a snapshot from a different roster`() {
        val cpuGame = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        play(cpuGame, e2e4)
        val snapshot = cpuGame.snapshot()!!

        val humans = newGame(twoHumans)
        assertFalse("a vs-CPU save must not load into a two-human game", humans.restore(snapshot))
        assertNull(humans.state.value)
    }

    @Test
    fun `restore rejects a snapshot whose board is the wrong size and leaves a live game untouched`() {
        val a = started()
        play(a, e2e4)
        val good = a.snapshot()!!
        val bad = good.replaceFirst("\"board\":[", "\"board\":[null,")
        assertTrue("the test edit must actually change the snapshot", bad != good)

        val b = started()
        play(b, g1f3)
        val before = b.state.value
        assertFalse(b.restore(bad))
        assertEquals("a rejected restore leaves the engine untouched", before, b.state.value)

        val fresh = newGame()
        assertFalse(fresh.restore(bad))
        assertNull(fresh.state.value)
    }

    @Test
    fun `restore is refused in networked play`() {
        val a = started()
        play(a, e2e4)
        val online = newGame(mode = PlayMode.ONLINE)
        assertFalse(online.restore(a.snapshot()!!))
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one move at a time down to the start position`() {
        val game = started()
        val start = game.state.value
        assertFalse(game.canUndo)

        play(game, e2e4, e7e5)
        assertTrue(game.canUndo)

        game.undo()
        assertEquals("black is to move again", PieceColor.BLACK, game.state.value!!.sideToMove)
        assertNull("black's pawn is back on e7", game.state.value!!.board[36])
        assertNotNull(game.state.value!!.board[28])

        game.undo()
        assertEquals(start, game.state.value)
        assertFalse(game.canUndo)

        game.undo() // nothing left: harmless
        assertEquals(start, game.state.value)
    }

    @Test
    fun `against the CPU undo also takes the CPU reply back`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.EASY
        val start = game.state.value
        play(game, e2e4)
        game.playBotTurn()
        assertEquals("the human move and the CPU reply were played", PieceColor.WHITE, game.state.value!!.sideToMove)
        assertTrue(game.canUndo)

        game.undo()

        assertEquals("both moves are gone", start, game.state.value)
        assertEquals("it is the human's turn again", PieceColor.WHITE, game.state.value!!.sideToMove)
        assertFalse(game.canUndo)
    }

    @Test
    fun `undo rolls the repetition history back with the position`() {
        val game = started()
        play(game, *shuffle) // start position occurred twice
        game.undo()          // take back Ng8: the start position has occurred once again
        play(game, 45 to 62) // replay Ng8: second occurrence, NOT a third
        assertEquals(
            "with the history rolled back this is only the second occurrence",
            ChessResult.IN_PROGRESS,
            game.state.value!!.result
        )
        play(game, *shuffle) // now the third occurrence
        assertEquals(ChessResult.DRAW_REPETITION, game.state.value!!.result)
    }

    @Test
    fun `undoing a mating move reopens the game and takes the win off the tally`() {
        val game = started()
        play(game, *foolsMate)
        assertTrue(game.state.value!!.roundOver)
        assertEquals(1, game.scoreP2.value)

        game.undo()

        assertFalse(game.state.value!!.roundOver)
        assertEquals(ChessResult.IN_PROGRESS, game.state.value!!.result)
        assertEquals(0, game.scoreP2.value)
        assertNull(game.state.value!!.winnerPlayerId)
        assertEquals(PieceColor.BLACK, game.state.value!!.sideToMove)
    }

    @Test
    fun `undo leaves the difficulty alone`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.HARD
        play(game, e2e4)
        game.difficulty = CpuDifficulty.EASY
        game.undo()
        assertEquals(CpuDifficulty.EASY, game.difficulty)
    }

    @Test
    fun `a new game starts a fresh undo history`() {
        val game = started()
        play(game, *foolsMate)
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse("undo must not reach back into the previous game", game.canUndo)
        val start = game.state.value
        game.undo()
        assertEquals(start, game.state.value)
    }

    @Test
    fun `undo does nothing once the session has ended`() {
        val game = started()
        play(game, e2e4)
        game.leaveSession()
        assertFalse(game.canUndo)
        val before = game.state.value
        game.undo()
        assertEquals(before, game.state.value)
    }

    @Test
    fun `a rejected move leaves no undo step behind`() {
        val game = started()
        game.playMove(0, 12, 44)   // not a legal pawn move
        game.playMove(1, 12, 28)   // black trying to move on white's turn
        game.playMove(0, 28, 36)   // empty source square
        assertFalse(game.canUndo)
    }

    @Test
    fun `a resumed game starts with an empty undo history`() {
        val a = started()
        play(a, e2e4)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse("undo history is in memory only", b.canUndo)
    }

    // --- Hint -------------------------------------------------------------------------------

    /** White: Ke1, Rd1. Black: Ke8, Qd5 (undefended). White to move. */
    private fun hangingQueenGame(sideToMove: PieceColor = PieceColor.WHITE, players: List<PlayerInfo> = twoHumans): ChessGame {
        val game = newGame(players, if (players === twoHumans) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT)
        val board = MutableList<Piece?>(64) { null }
        board[4] = Piece(PieceType.KING, PieceColor.WHITE)
        board[3] = Piece(PieceType.ROOK, PieceColor.WHITE)
        board[60] = Piece(PieceType.KING, PieceColor.BLACK)
        board[35] = Piece(PieceType.QUEEN, PieceColor.BLACK)
        game.state.value = ChessState(
            board = board,
            sideToMove = sideToMove,
            castleWK = false, castleWQ = false, castleBK = false, castleBQ = false,
            epSquare = null
        )
        return game
    }

    @Test
    fun `a hint takes the hanging queen`() {
        val game = hangingQueenGame()
        val hint = game.hint()
        assertEquals(ChessHint(3, 35), hint)
        assertEquals(hint, game.hintMove.value)
        assertTrue("the suggestion is a legal move", (3 to 35) in game.allLegalMoves())
    }

    @Test
    fun `a hint on the opening position is a legal move`() {
        val game = started()
        val hint = game.hint()
        assertNotNull(hint)
        assertTrue((hint!!.from to hint.to) in game.allLegalMoves())
    }

    @Test
    fun `a hint is bounded to the medium depth and costs no undo step`() {
        assertTrue(ChessGame.HINT_SEARCH_DEPTH <= 2)
        val game = started()
        game.hint()
        assertFalse("a hint is a suggestion, not an action", game.canUndo)
    }

    @Test
    fun `there is no hint on the CPU's turn, after the game ends, or after the session ends`() {
        val cpuTurn = hangingQueenGame(PieceColor.BLACK, humanAndCpu)
        assertNull(cpuTurn.hint())
        assertNull(cpuTurn.hintMove.value)

        val mated = started()
        play(mated, *foolsMate)
        assertNull("the game is over", mated.hint())

        val left = started()
        left.leaveSession()
        assertNull("the session is over", left.hint())

        assertNull("no board yet", newGame().hint())
    }

    @Test
    fun `the next move clears the hint`() {
        val game = started()
        assertNotNull(game.hint())
        assertNotNull(game.hintMove.value)
        play(game, e2e4)
        assertNull(game.hintMove.value)
    }

    @Test
    fun `undo clears the hint`() {
        val game = started()
        play(game, e2e4)
        assertNotNull(game.hint())
        game.undo()
        assertNull(game.hintMove.value)
    }

    @Test
    fun `restore and a new game clear the hint`() {
        val a = started()
        play(a, e2e4)
        val snapshot = a.snapshot()!!

        val b = started()
        assertNotNull(b.hint())
        assertTrue(b.restore(snapshot))
        assertNull(b.hintMove.value)

        assertNotNull(b.hint())
        b.startMatch()
        assertNull(b.hintMove.value)
    }
}
