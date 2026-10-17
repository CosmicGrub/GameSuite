package com.gamesuite.games.checkers

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume (snapshot/restore), Undo and Hint for Checkers. Positions are hand-built through
 * [restore] with a JSON snapshot (cell codes: 0 empty, 1 side-0 man, 2 side-0 king, 3 side-1 man,
 * 4 side-1 king; index = row * 8 + col; side 0 moves toward row 7, side 1 toward row 0).
 */
class CheckersResumeUndoHintTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): CheckersGame {
        val game = CheckersGame()
        game.difficulty = CpuDifficulty.EASY
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(players: List<PlayerInfo> = twoHumans, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
        newGame(players, mode).also { it.startMatch() }

    private fun sq(row: Int, col: Int) = row * 8 + col

    /** A hand-built snapshot. [players] is (playerId, isBot) per seat. */
    private fun positionJson(
        cells: Map<Int, Int>,
        current: Int = 0,
        forcedRow: Int = -1,
        forcedCol: Int = -1,
        gameOver: Boolean = false,
        players: List<Pair<String, Boolean>> = listOf("p1" to false, "p2" to false),
        size: Int = 64,
        difficulty: String = "EASY",
        scoreP1: Int = 0,
        scoreP2: Int = 0
    ): String {
        val cellList = (0 until size).joinToString(",") { (cells[it] ?: 0).toString() }
        val playerList = players.joinToString(",") { """{"playerId":"${it.first}","isBot":${it.second}}""" }
        return """{"cells":[$cellList],"players":[$playerList],"currentPlayerIndex":$current,""" +
            """"forcedRow":$forcedRow,"forcedCol":$forcedCol,"gameOver":$gameOver,"winnerPlayerId":null,""" +
            """"scoreP1":$scoreP1,"scoreP2":$scoreP2,"difficulty":"$difficulty"}"""
    }

    /** Side 0 man at (2,1); side 1 men at (3,2), (5,4) and (7,0): side 0 has a two-hop chain. */
    private val chainCells = mapOf(sq(2, 1) to 1, sq(3, 2) to 3, sq(5, 4) to 3, sq(7, 0) to 3)

    private fun chainGame(): CheckersGame {
        val game = newGame()
        assertTrue(game.restore(positionJson(chainCells, current = 0)))
        return game
    }

    private data class Hop(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int)

    private fun firstLegalHop(game: CheckersGame): Hop {
        for (row in 0..7) for (col in 0..7) {
            val dest = game.legalDestinationsFrom(row, col).firstOrNull() ?: continue
            return Hop(row, col, dest.first, dest.second)
        }
        error("no legal hop")
    }

    private fun playFirstLegal(game: CheckersGame) {
        val hop = firstLegalHop(game)
        game.playMove(game.state.value!!.currentPlayerIndex, hop.fromRow, hop.fromCol, hop.toRow, hop.toCol)
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first round, after the session ends, or in online play`() {
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
        repeat(4) { playFirstLegal(a) }
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value!!.board, b.state.value!!.board)
        assertEquals(a.state.value!!.currentPlayerIndex, b.state.value!!.currentPlayerIndex)
        assertEquals(a.scoreP1.value, b.scoreP1.value)
        assertEquals(a.scoreP2.value, b.scoreP2.value)

        val hop = firstLegalHop(a)
        assertEquals(firstLegalHop(a), firstLegalHop(b))
        a.playMove(a.state.value!!.currentPlayerIndex, hop.fromRow, hop.fromCol, hop.toRow, hop.toCol)
        b.playMove(b.state.value!!.currentPlayerIndex, hop.fromRow, hop.fromCol, hop.toRow, hop.toCol)
        assertEquals("the same move must lead to the same position", a.state.value!!.board, b.state.value!!.board)
        assertEquals(a.state.value!!.currentPlayerIndex, b.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a half-finished multi-jump survives a resume and must still be finished by the same piece`() {
        val a = chainGame()
        a.playMove(0, 2, 1, 4, 3)
        val mid = a.state.value!!
        assertTrue("the chain is pinned to the jumping piece", mid.inForcedContinuation)
        assertEquals(0, mid.currentPlayerIndex)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        val restored = b.state.value!!
        assertTrue(restored.inForcedContinuation)
        assertEquals(4, restored.forcedRow)
        assertEquals(3, restored.forcedCol)
        assertEquals(setOf(6 to 5), b.legalDestinationsFrom(4, 3))
        assertFalse("only the pinned piece may move", b.hasLegalMoveFrom(7, 0))

        b.playMove(0, 4, 3, 6, 5)
        assertFalse(b.state.value!!.inForcedContinuation)
        assertEquals("the turn passes after the chain", 1, b.state.value!!.currentPlayerIndex)
        assertNull("both captured men are gone", b.state.value!!.pieceAt(3, 2))
        assertNull(b.state.value!!.pieceAt(5, 4))
    }

    @Test
    fun `the session score, kings and difficulty survive a resume`() {
        val cells = mapOf(sq(2, 1) to 2, sq(5, 4) to 3, sq(6, 1) to 4)
        val json = positionJson(cells, current = 1, difficulty = "HARD", scoreP1 = 3, scoreP2 = 2)
        val a = newGame()
        assertTrue(a.restore(json))
        assertEquals(CpuDifficulty.HARD, a.difficulty)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(3, b.scoreP1.value)
        assertEquals(2, b.scoreP2.value)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals(PieceKind.KING, b.state.value!!.pieceAt(2, 1)!!.kind)
        assertEquals(PieceKind.KING, b.state.value!!.pieceAt(6, 1)!!.kind)
        assertEquals(1, b.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a finished round can be snapshotted and resumes as finished`() {
        val a = newGame()
        assertTrue(a.restore(positionJson(mapOf(sq(2, 1) to 1, sq(3, 2) to 3))))
        a.playMove(0, 2, 1, 4, 3)
        assertTrue(a.state.value!!.gameOver)
        assertEquals(1, a.scoreP1.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.gameOver)
        assertEquals("p1", b.state.value!!.winnerPlayerId)
        assertEquals("resuming must not award the win a second time", 1, b.scoreP1.value)
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a wrong-sized board and leaves the engine untouched`() {
        val game = started()
        playFirstLegal(game)
        val before = game.state.value
        val beforeP1 = game.scoreP1.value
        val depth = game.canUndo

        assertFalse(game.restore("not json at all"))
        assertFalse(game.restore("{}"))
        assertFalse(
            "wrong roster ids",
            game.restore(positionJson(mapOf(sq(2, 1) to 1, sq(5, 4) to 3), players = listOf("x" to false, "y" to false)))
        )
        assertFalse(
            "wrong bot flags",
            game.restore(positionJson(mapOf(sq(2, 1) to 1, sq(5, 4) to 3), players = listOf("p1" to false, "p2" to true)))
        )
        assertFalse("wrong board size", game.restore(positionJson(mapOf(sq(2, 1) to 1), size = 63)))
        assertFalse("a piece on a light square", game.restore(positionJson(mapOf(sq(0, 0) to 1, sq(5, 4) to 3))))
        assertFalse("an unknown piece code", game.restore(positionJson(mapOf(sq(2, 1) to 9))))
        assertFalse("a turn index out of range", game.restore(positionJson(mapOf(sq(2, 1) to 1), current = 2)))
        assertFalse(
            "a pinned square that is empty",
            game.restore(positionJson(mapOf(sq(2, 1) to 1, sq(5, 4) to 3), forcedRow = 4, forcedCol = 3))
        )
        assertFalse(
            "a pinned piece with no jump left",
            game.restore(positionJson(mapOf(sq(2, 1) to 1, sq(5, 4) to 3), forcedRow = 2, forcedCol = 1))
        )
        assertFalse("negative score", game.restore(positionJson(mapOf(sq(2, 1) to 1), scoreP1 = -1)))
        assertFalse(
            "a live round in which the side to move cannot move",
            game.restore(positionJson(mapOf(sq(0, 1) to 3, sq(5, 4) to 1), current = 1))
        )
        assertFalse(
            "a finished flag on a position that is not finished",
            game.restore(positionJson(mapOf(sq(2, 1) to 1, sq(5, 4) to 3), current = 0, gameOver = true))
        )

        assertSame("a rejected restore must not replace the state", before, game.state.value)
        assertEquals(beforeP1, game.scoreP1.value)
        assertEquals("a rejected restore must not clear the undo history", depth, game.canUndo)
    }

    @Test
    fun `a snapshot from a CPU game is rejected by a pass-and-play game`() {
        val cpu = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        val snapshot = cpu.snapshot()!!
        assertFalse(newGame().restore(snapshot))
        val sameRoster = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertTrue(sameRoster.restore(snapshot))
        assertEquals("the CPU still opens, so a resume lands on the bot's turn", 1, sameRoster.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `restore reopens a session whose flag was set and clears history and hint`() {
        val a = chainGame()
        a.playMove(0, 2, 1, 4, 3)
        assertTrue(a.canUndo)
        a.hint()
        assertNotNull(a.hintMove.value)
        val snapshot = a.snapshot()!!

        a.leaveSession()
        assertTrue(a.matchOver.value)
        assertTrue(a.restore(snapshot))
        assertFalse(a.matchOver.value)
        assertFalse("undo never reaches before a restore", a.canUndo)
        assertNull(a.hintMove.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one human turn at a time in pass-and-play`() {
        val game = started()
        assertFalse(game.canUndo)
        val start = game.state.value!!.board

        playFirstLegal(game)
        val afterOne = game.state.value!!.board
        playFirstLegal(game)
        assertTrue(game.canUndo)

        game.undo()
        assertEquals(afterOne, game.state.value!!.board)
        assertTrue(game.canUndo)
        game.undo()
        assertEquals(start, game.state.value!!.board)
        assertEquals("back on the opening side's turn", 1, game.state.value!!.currentPlayerIndex)
        assertFalse(game.canUndo)
        game.undo()
        assertEquals(start, game.state.value!!.board)
    }

    @Test
    fun `a whole multi-jump is one undo step`() {
        val game = chainGame()
        val before = game.state.value!!.board

        game.playMove(0, 2, 1, 4, 3)
        game.playMove(0, 4, 3, 6, 5)
        assertEquals(1, game.state.value!!.currentPlayerIndex)

        game.undo()
        assertEquals("both hops and both captures come back together", before, game.state.value!!.board)
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertFalse(game.state.value!!.inForcedContinuation)
        assertFalse(game.canUndo)
    }

    @Test
    fun `undo in the middle of a chain takes the whole chain back`() {
        val game = chainGame()
        val before = game.state.value!!.board
        game.playMove(0, 2, 1, 4, 3)
        assertTrue(game.state.value!!.inForcedContinuation)

        game.undo()
        assertEquals(before, game.state.value!!.board)
        assertFalse(game.state.value!!.inForcedContinuation)
        assertFalse(game.canUndo)
    }

    @Test
    fun `against the CPU undo takes the CPU reply back too`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertEquals("the CPU opens", 1, game.state.value!!.currentPlayerIndex)
        game.playBotTurn()
        val humanToMove = game.state.value!!.board
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertFalse("the CPU's opening move is not an undo step", game.canUndo)

        playFirstLegal(game)
        game.playBotTurn()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertTrue(game.canUndo)

        game.undo()
        assertEquals(humanToMove, game.state.value!!.board)
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertFalse(game.canUndo)
    }

    @Test
    fun `a rejected move leaves no undo step and a bot move is never one`() {
        val game = started()
        game.playMove(1, 0, 0, 7, 7)
        game.playMove(0, 2, 1, 3, 2)
        assertFalse(game.canUndo)

        val cpu = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        cpu.playBotTurn()
        assertFalse(cpu.canUndo)
    }

    @Test
    fun `undoing a round-winning move reopens the round and takes the win off the score`() {
        val game = newGame()
        assertTrue(game.restore(positionJson(mapOf(sq(2, 1) to 1, sq(3, 2) to 3))))
        game.playMove(0, 2, 1, 4, 3)
        assertTrue(game.state.value!!.gameOver)
        assertEquals(1, game.scoreP1.value)

        game.undo()
        assertFalse(game.state.value!!.gameOver)
        assertEquals(0, game.scoreP1.value)
        assertNotNull(game.state.value!!.pieceAt(3, 2))
    }

    @Test
    fun `undo does not cross into a new round and does nothing after the session ends`() {
        val game = started()
        playFirstLegal(game)
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse("a new round starts with an empty history", game.canUndo)
        val fresh = game.state.value
        game.undo()
        assertSame(fresh, game.state.value)

        playFirstLegal(game)
        val afterMove = game.state.value
        game.leaveSession()
        assertFalse(game.canUndo)
        game.undo()
        assertSame("a finished session cannot be undone", afterMove, game.state.value)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint suggests a legal hop on the opening board and changes nothing`() {
        val game = started()
        val before = game.state.value
        val hint = game.hint()
        assertNotNull(hint)
        assertTrue(game.legalDestinationsFrom(hint!!.fromRow, hint.fromCol).contains(hint.toRow to hint.toCol))
        assertEquals(hint, game.hintMove.value)
        assertSame("a hint is a suggestion, not a move", before, game.state.value)
        assertFalse("a hint is not an undo step", game.canUndo)
    }

    @Test
    fun `hint takes the mandatory capture`() {
        val game = chainGame()
        val hint = game.hint()
        assertEquals(CheckersMove(2, 1, 4, 3, isCapture = true, capRow = 3, capCol = 2), hint)
    }

    @Test
    fun `hint during a multi-jump suggests the next jump of the pinned piece`() {
        val game = chainGame()
        game.playMove(0, 2, 1, 4, 3)
        val hint = game.hint()
        assertNotNull(hint)
        assertEquals(4, hint!!.fromRow)
        assertEquals(3, hint.fromCol)
        assertEquals(6, hint.toRow)
        assertEquals(5, hint.toCol)
    }

    @Test
    fun `hint is null when it makes no sense`() {
        val fresh = newGame()
        assertNull("no board yet", fresh.hint())
        assertNull(fresh.hintMove.value)

        val cpu = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertNull("the CPU is to move", cpu.hint())
        assertNull(cpu.hintMove.value)

        val over = newGame()
        assertTrue(over.restore(positionJson(mapOf(sq(2, 1) to 1, sq(3, 2) to 3))))
        over.playMove(0, 2, 1, 4, 3)
        assertNull("the round is over", over.hint())

        val ended = chainGame()
        ended.leaveSession()
        assertNull("the session is over", ended.hint())
    }

    @Test
    fun `the next move, undo, a new round and a restore all clear the hint`() {
        val a = started()
        a.hint()
        assertNotNull(a.hintMove.value)
        playFirstLegal(a)
        assertNull("a move clears the hint", a.hintMove.value)

        a.hint()
        assertNotNull(a.hintMove.value)
        a.undo()
        assertNull("undo clears the hint", a.hintMove.value)

        a.hint()
        a.playAgain()
        assertNull("a new round clears the hint", a.hintMove.value)

        a.hint()
        val snapshot = a.snapshot()!!
        assertTrue(a.restore(snapshot))
        assertNull("a restore clears the hint", a.hintMove.value)
    }

    @Test
    fun `a hint does not depend on the CPU difficulty`() {
        val easy = chainGame()
        easy.difficulty = CpuDifficulty.EASY
        val hard = chainGame()
        hard.difficulty = CpuDifficulty.HARD
        assertEquals(easy.hint(), hard.hint())
    }
}
