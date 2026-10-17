package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import com.gamesuite.transport.MultiplayerTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume (snapshot/restore), Undo and Hint for Tic-Tac-Toe, written against the Connect Four reference. */
class TicTacToeResumeUndoHintTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    /** Delivers nothing; only there so an online-mode context can be built. */
    private class QuietTransport : MultiplayerTransport {
        override fun connect() {}
        override fun disconnect() {}
        override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
        override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
        override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
        override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    }

    private fun newGame(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        misere: Boolean = false,
        wild: Boolean = false
    ): TicTacToeGame {
        val game = TicTacToeGame()
        game.misere = misere
        game.wild = wild
        val transport: MultiplayerTransport =
            if (mode == PlayMode.ONLINE) QuietTransport() else LocalPassAndPlayTransport()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = transport))
        return game
    }

    private fun started(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        misere: Boolean = false,
        wild: Boolean = false
    ) = newGame(players, mode, misere, wild).also { it.startMatch() }

    private fun TicTacToeGame.cells(): List<Int> = board.value.toList()

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first round, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no round dealt yet", game.snapshot())

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
        listOf(4, 0, 8).forEach { a.cellClicked(it) }
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.cells(), b.cells())
        assertEquals(a.currentPlayer.value, b.currentPlayer.value)
        assertEquals(a.roundOver.value, b.roundOver.value)

        a.cellClicked(2)
        b.cellClicked(2)
        assertEquals("the same move must lead to the same position", a.cells(), b.cells())
        assertEquals(a.currentPlayer.value, b.currentPlayer.value)
        assertTrue("restoring must not leave the engine marked as over", !b.matchOver.value)
    }

    @Test
    fun `the session tally and the next opener survive a resume`() {
        val a = started()
        // Player 1 takes the top row.
        listOf(0, 3, 1, 4, 2).forEach { a.cellClicked(it) }
        assertEquals(1, a.scoreP1.value)
        a.playAgain()
        assertEquals("the second round opens with player 2", 2, a.currentPlayer.value)
        a.cellClicked(4)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.scoreP1.value)
        assertEquals(0, b.scoreP2.value)
        assertEquals(0, b.draws.value)
        assertEquals(2, b.roundNumber.value)
        assertEquals(a.cells(), b.cells())

        // The opener rotation continues: round 3 opens with player 1 again, on both engines.
        a.playAgain()
        b.playAgain()
        assertEquals(1, a.currentPlayer.value)
        assertEquals(1, b.currentPlayer.value)
    }

    @Test
    fun `a finished round survives a resume together with its winning line`() {
        val a = started()
        listOf(0, 3, 1, 4, 2).forEach { a.cellClicked(it) }
        assertTrue(a.roundOver.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.roundOver.value)
        assertEquals(listOf(0, 1, 2), b.winningLine.value)
        assertEquals(1, b.scoreP1.value)
    }

    @Test
    fun `wild symbol choice and cpu difficulty survive a resume`() {
        val a = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, wild = true)
        a.difficulty = CpuDifficulty.HARD
        a.chooseSymbol(2)

        val b = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, wild = true)
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(2, b.selectedSymbol.value)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
    }

    @Test
    fun `a snapshot from another rule variant is rejected`() {
        val misere = started(misere = true)
        misere.cellClicked(4)
        val snapshot = misere.snapshot()!!

        val classic = newGame()
        val before = classic.cells()
        assertFalse("classic must not accept a misere save", classic.restore(snapshot))
        assertEquals(before, classic.cells())

        val wild = newGame(wild = true)
        assertFalse("wild must not accept a misere save", wild.restore(snapshot))

        val sameVariant = newGame(misere = true)
        assertTrue(sameVariant.restore(snapshot))
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a wrong sized board and leaves the engine untouched`() {
        val source = started()
        listOf(4, 0).forEach { source.cellClicked(it) }
        val good = source.snapshot()!!

        val target = started()
        target.cellClicked(8)
        val boardBefore = target.cells()
        val playerBefore = target.currentPlayer.value

        assertFalse(target.restore("not json at all"))
        assertFalse(target.restore("{}"))
        assertFalse(target.restore(""))

        // Wrong roster: a vs-CPU engine must not accept a pass-and-play save.
        val vsCpu = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertFalse(vsCpu.restore(good))

        // Wrong ids for the same shape of roster.
        val otherIds = newGame(listOf(PlayerInfo("a", "A"), PlayerInfo("b", "B")))
        assertFalse(otherIds.restore(good))

        // Wrong board size.
        // (Player 1 played cell 4, player 2 cell 0, so the saved board is 2,0,0,0,1,0,0,0,0.)
        val shortBoard = good.replace("\"board\":[2,0,0,0,1,0,0,0,0]", "\"board\":[2,0,0,0,1,0,0,0]")
        assertTrue("the test's replace must actually change the snapshot", shortBoard != good)
        assertFalse(target.restore(shortBoard))

        // A cell holding a symbol that does not exist.
        val badCell = good.replace("\"board\":[2,0,0,0,1,0,0,0,0]", "\"board\":[2,0,0,0,7,0,0,0,0]")
        assertTrue(badCell != good)
        assertFalse(target.restore(badCell))

        assertEquals(boardBefore, target.cells())
        assertEquals(playerBefore, target.currentPlayer.value)

        // An online context is never restorable, even with a valid snapshot.
        val online = newGame(mode = PlayMode.ONLINE)
        assertFalse(online.restore(good))
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one human move at a time in pass and play`() {
        val game = started()
        assertFalse(game.canUndo)

        game.cellClicked(4)
        game.cellClicked(0)
        assertTrue(game.canUndo)

        game.undo()
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0, 0, 0), game.cells())
        assertEquals(2, game.currentPlayer.value)

        game.undo()
        assertEquals(List(9) { 0 }, game.cells())
        assertEquals(1, game.currentPlayer.value)
        assertFalse(game.canUndo)

        game.undo() // nothing left: must be a no-op
        assertEquals(List(9) { 0 }, game.cells())
    }

    @Test
    fun `a rejected tap leaves no undo step`() {
        val game = started()
        game.cellClicked(4)
        game.undo()
        assertFalse(game.canUndo)

        game.cellClicked(4)
        game.cellClicked(4) // occupied: rejected
        game.cellClicked(99) // out of range: rejected
        game.undo()
        assertEquals("only the one real move was recorded", List(9) { 0 }, game.cells())
        assertFalse(game.canUndo)
    }

    @Test
    fun `undo against the cpu takes the cpu reply back too`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.HARD
        game.cellClicked(0)
        assertEquals(2, game.currentPlayer.value)
        game.playBotTurn()
        assertEquals(1, game.currentPlayer.value)
        assertEquals(2, game.cells().count { it != 0 })

        game.undo()
        assertEquals(List(9) { 0 }, game.cells())
        assertEquals("back on the human's own turn", 1, game.currentPlayer.value)
        assertFalse("the bot's move never made an undo step of its own", game.canUndo)
    }

    @Test
    fun `undoing a winning move reopens the round and takes the point back`() {
        val game = started()
        listOf(0, 3, 1, 4).forEach { game.cellClicked(it) }
        game.cellClicked(2)
        assertTrue(game.roundOver.value)
        assertEquals(1, game.scoreP1.value)
        assertNotNull(game.winningLine.value)

        game.undo()
        assertFalse(game.roundOver.value)
        assertEquals(0, game.scoreP1.value)
        assertNull(game.winningLine.value)
        assertEquals(1, game.currentPlayer.value)

        // The round can be finished again, and the score counts once.
        game.cellClicked(2)
        assertEquals(1, game.scoreP1.value)
    }

    @Test
    fun `undo does not cross into a new round or work after the session ends`() {
        val game = started()
        listOf(0, 3, 1, 4, 2).forEach { game.cellClicked(it) }
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse("a new round starts a new history", game.canUndo)
        game.undo()
        assertEquals(1, game.scoreP1.value)
        assertEquals(List(9) { 0 }, game.cells())

        game.cellClicked(4)
        assertTrue(game.canUndo)
        game.leaveSession()
        assertFalse(game.canUndo)
        val before = game.cells()
        game.undo()
        assertEquals(before, game.cells())
    }

    @Test
    fun `restoring clears the undo history`() {
        val a = started()
        a.cellClicked(4)
        val snapshot = a.snapshot()!!

        val b = started()
        b.cellClicked(0)
        assertTrue(b.canUndo)
        assertTrue(b.restore(snapshot))
        assertFalse(b.canUndo)
    }

    @Test
    fun `network play never records undo steps`() {
        val game = started(mode = PlayMode.ONLINE)
        game.cellClicked(4)
        assertFalse(game.canUndo)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint takes the win on a hand built position`() {
        val game = started()
        game.board.value = intArrayOf(1, 1, 0, 2, 2, 0, 0, 0, 0)
        game.currentPlayer.value = 1

        val hint = game.hint()
        assertEquals(2 to 1, hint)
        assertEquals(hint, game.hintMove.value)
    }

    @Test
    fun `hint blocks a threat it cannot win through`() {
        val game = started()
        // Player 2 (O) threatens 3-4-5 at cell 5; player 1 has no win of its own, so it must block.
        game.board.value = intArrayOf(1, 0, 0, 2, 2, 0, 0, 0, 1)
        game.currentPlayer.value = 1

        assertEquals(5 to 1, game.hint())
    }

    @Test
    fun `hint in misere avoids completing a line`() {
        val game = started(misere = true)
        game.board.value = intArrayOf(1, 1, 0, 2, 2, 0, 0, 0, 0)
        game.currentPlayer.value = 1

        val hint = game.hint()!!
        assertTrue("hint must be a legal empty cell", game.board.value[hint.first] == 0)
        assertTrue("completing the top row loses in misere", hint.first != 2)
    }

    @Test
    fun `hint in wild names a symbol as well as a cell`() {
        val game = started(wild = true)
        game.board.value = intArrayOf(1, 1, 0, 0, 0, 0, 0, 0, 0)
        game.currentPlayer.value = 1

        assertEquals("the only completing move is an X at cell 2", 2 to 1, game.hint())
    }

    @Test
    fun `hint is null on the cpu's turn, after the round ends, after the session ends and online`() {
        val vsCpu = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertNotNull("the human opens", vsCpu.hint())
        vsCpu.cellClicked(4)
        assertNull("now it is the CPU's turn", vsCpu.hint())

        val finished = started()
        listOf(0, 3, 1, 4, 2).forEach { finished.cellClicked(it) }
        assertNull(finished.hint())

        val ended = started()
        ended.leaveSession()
        assertNull(ended.hint())

        val online = started(mode = PlayMode.ONLINE)
        assertNull(online.hint())

        val notStarted = newGame()
        assertNull(notStarted.hint())
    }

    @Test
    fun `a hint is cleared by the next move and by undo and never changes the board`() {
        val game = started()
        game.cellClicked(4)
        val before = game.cells()

        assertNotNull(game.hint())
        assertEquals("a hint is a suggestion, not a move", before, game.cells())

        game.cellClicked(0)
        assertNull("the next move clears the hint", game.hintMove.value)

        assertNotNull(game.hint())
        game.undo()
        assertNull("undo clears the hint", game.hintMove.value)

        assertNotNull(game.hint())
        game.playAgain()
        assertNull("a new round clears the hint", game.hintMove.value)
    }

    @Test
    fun `a restore clears the hint`() {
        val a = started()
        a.cellClicked(4)
        val snapshot = a.snapshot()!!

        val b = started()
        assertNotNull(b.hint())
        assertTrue(b.restore(snapshot))
        assertNull(b.hintMove.value)
    }
}
