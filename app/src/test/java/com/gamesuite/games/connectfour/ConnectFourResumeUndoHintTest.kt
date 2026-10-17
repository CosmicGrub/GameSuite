package com.gamesuite.games.connectfour

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
 * Resume (snapshot/restore), Undo and Hint for Connect Four. This file is also the reference the
 * other games' equivalents were written against.
 */
class ConnectFourResumeUndoHintTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): ConnectFourGame {
        val game = ConnectFourGame()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(players: List<PlayerInfo> = twoHumans, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
        newGame(players, mode).also { it.startMatch() }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first board, after the session ends, or in online play`() {
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
        listOf(3, 4, 3, 2).forEach { a.dropDisc(it) }
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.state.value)
        assertEquals(a.sessionWins.value, b.sessionWins.value)
        assertEquals(a.sessionDraws.value, b.sessionDraws.value)

        a.dropDisc(3)
        b.dropDisc(3)
        assertEquals("the same move must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `the session tally and the next opener survive a resume`() {
        val a = started()
        // Player 1 stacks column 0, player 2 column 1: player 1 wins the first board vertically.
        listOf(0, 1, 0, 1, 0, 1, 0).forEach { a.dropDisc(it) }
        assertEquals(1, a.sessionWins.value["p1"])
        a.playAgain()
        assertEquals("the second board opens with player 2", 1, a.state.value!!.currentPlayerIndex)
        a.dropDisc(2)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.sessionWins.value["p1"])
        assertEquals(0, b.sessionWins.value["p2"])
        assertEquals(a.state.value, b.state.value)
        b.playAgain()
        a.playAgain()
        assertEquals("the opener keeps alternating after a resume", a.state.value!!.currentPlayerIndex, b.state.value!!.currentPlayerIndex)
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
        cpuGame.dropDisc(3)
        val snapshot = cpuGame.snapshot()!!

        val humans = newGame(twoHumans)
        assertFalse("a vs-CPU save must not load into a two-human game", humans.restore(snapshot))
        assertNull(humans.state.value)
    }

    @Test
    fun `restore rejects a snapshot whose board is the wrong size`() {
        val a = started()
        a.dropDisc(3)
        val good = a.snapshot()!!
        // 42 cells in the real thing; cut one entry out of the cells array.
        val bad = good.replaceFirst(Regex("\"cells\":\\[null,"), "\"cells\":[")
        assertTrue("the test edit must actually change the snapshot", bad != good)

        val b = newGame()
        assertFalse(b.restore(bad))
        assertNull(b.state.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one human drop at a time down to an empty board`() {
        val game = started()
        assertFalse(game.canUndo)

        game.dropDisc(3)
        game.dropDisc(4)
        assertTrue(game.canUndo)

        game.undo()
        assertEquals("player 2 is to move again", 1, game.state.value!!.currentPlayerIndex)
        assertNull("player 2's disc is gone", game.state.value!!.cells[5 * 7 + 4])
        assertNotNull("player 1's disc is still there", game.state.value!!.cells[5 * 7 + 3])

        game.undo()
        assertTrue(game.state.value!!.cells.all { it == null })
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertFalse(game.canUndo)

        game.undo() // nothing left: harmless
        assertTrue(game.state.value!!.cells.all { it == null })
    }

    @Test
    fun `against the CPU undo also takes the CPU reply back`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.EASY
        game.dropDisc(3)
        game.playBotTurn()
        assertEquals("the human disc and the CPU reply are on the board", 2, game.state.value!!.cells.count { it != null })

        game.undo()

        assertTrue("both discs are gone", game.state.value!!.cells.all { it == null })
        assertEquals("it is the human's turn again", 0, game.state.value!!.currentPlayerIndex)
        assertFalse(game.canUndo)
    }

    @Test
    fun `undoing the winning drop reopens the board and takes the win off the tally`() {
        val game = started()
        listOf(0, 1, 0, 1, 0, 1).forEach { game.dropDisc(it) }
        game.dropDisc(0) // player 1 connects four
        assertTrue(game.state.value!!.boardOver)
        assertEquals(1, game.sessionWins.value["p1"])

        game.undo()

        assertFalse(game.state.value!!.boardOver)
        assertNull(game.state.value!!.winningLine)
        assertEquals(0, game.sessionWins.value["p1"])
        assertEquals(0, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a new board starts a fresh undo history`() {
        val game = started()
        listOf(0, 1, 0, 1, 0, 1, 0).forEach { game.dropDisc(it) }
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse("undo must not reach back into the previous board", game.canUndo)
    }

    @Test
    fun `undo does nothing once the session has ended`() {
        val game = started()
        game.dropDisc(3)
        game.leaveSession()
        assertFalse(game.canUndo)
        val before = game.state.value
        game.undo()
        assertEquals(before, game.state.value)
    }

    @Test
    fun `a rejected drop leaves no empty undo step behind`() {
        val game = started()
        game.dropDisc(-1)
        game.dropDisc(7)
        assertFalse(game.canUndo)
    }

    @Test
    fun `a resumed game starts with an empty undo history`() {
        val a = started()
        a.dropDisc(3)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse("undo history is in memory only", b.canUndo)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `a hint takes an immediate win`() {
        val game = started()
        // Player 1 has three stacked in column 0; player 2 has two in column 1 and one in column 2.
        listOf(0, 1, 0, 1, 0, 2).forEach { game.dropDisc(it) }
        assertEquals(0, game.state.value!!.currentPlayerIndex)

        assertEquals(0, game.hint())
        assertEquals(0, game.hintColumn.value)
    }

    @Test
    fun `a hint blocks the opponent's immediate win`() {
        val game = started()
        // Player 2 has three stacked in column 1 and player 1 has no threat of its own.
        listOf(0, 1, 2, 1, 4, 1).forEach { game.dropDisc(it) }
        assertEquals("player 1 to move, facing a vertical threat in column 1", 0, game.state.value!!.currentPlayerIndex)

        assertEquals(1, game.hint())
    }

    @Test
    fun `a hint is always a playable column`() {
        val game = started()
        repeat(6) { game.dropDisc(3) } // fill column 3
        val col = game.hint()
        assertNotNull(col)
        assertTrue(col!! in 0..6)
        assertTrue("never suggests a full column", col != 3)
    }

    @Test
    fun `no hint on the CPU's turn or after the board is over`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.dropDisc(3)
        assertEquals("it is the CPU's turn", 1, game.state.value!!.currentPlayerIndex)
        assertNull(game.hint())
        assertNull(game.hintColumn.value)

        val over = started()
        listOf(0, 1, 0, 1, 0, 1, 0).forEach { over.dropDisc(it) }
        assertNull(over.hint())
    }

    @Test
    fun `the next drop clears the hint and so does undo`() {
        val game = started()
        game.hint()
        assertNotNull(game.hintColumn.value)
        game.dropDisc(3)
        assertNull(game.hintColumn.value)

        game.hint()
        assertNotNull(game.hintColumn.value)
        game.undo()
        assertNull(game.hintColumn.value)
    }
}
