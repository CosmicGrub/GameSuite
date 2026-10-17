package com.gamesuite.games.mancala

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
 * Resume (snapshot/restore), Undo and Hint for Mancala. Board index reminder: 0-5 are player 0's
 * pits, 6 is player 0's store, 7-12 are player 1's pits, 13 is player 1's store; 48 stones in all.
 */
class MancalaResumeUndoHintTest {

    private fun newGame(botIndex: Int? = null, mode: PlayMode? = null, ids: Pair<String, String> = "p0" to "p1"): MancalaGame {
        val game = MancalaGame()
        game.init(
            GameContext(
                activeMode = mode ?: if (botIndex == null) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = ids.first, displayName = "P0", isBot = botIndex == 0),
                    PlayerInfo(playerId = ids.second, displayName = "P1", isBot = botIndex == 1)
                ),
                localPlayerIndex = if (botIndex == 0) 1 else 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.MEDIUM
        return game
    }

    private fun started(botIndex: Int? = null, mode: PlayMode? = null) =
        newGame(botIndex, mode).also { it.startMatch() }

    private fun seed(game: MancalaGame, pits: List<Int>, current: Int = 0) {
        assertEquals(48, pits.sum())
        game.state.value = MancalaState(pits = pits, currentPlayerIndex = current, lastAction = "Fixture seeded")
    }

    /** Player 0 wins this game with the single sow of pit 5 (31 stones to 17 once the sides are swept). */
    private val almostWon = listOf(0, 0, 0, 0, 0, 1, 30, 0, 0, 0, 0, 0, 1, 16)

    private fun json(pits: String, p0: String = "p0", p1: String = "p1") =
        """{"pits":[$pits],"currentPlayerIndex":0,"lastAction":"x","roundOver":false,"winnerPlayerId":null,""" +
            """"lastSowPath":[],"lastCapture":null,"players":[{"playerId":"$p0","isBot":false},""" +
            """{"playerId":"$p1","isBot":false}],"scoreP1":0,"scoreP2":0,"draws":0,"difficulty":"MEDIUM"}"""

    private val openingPits = "4,4,4,4,4,4,0,4,4,4,4,4,4,0"

    /** Board state with the "restored" marker cleared, for comparing a restored game with a played one. */
    private fun MancalaGame.plain() = state.value!!.copy(restored = false)

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
    fun `a restored game is identical, owes the same extra turn, and plays on identically`() {
        val a = started()
        a.sow(0, 2) // four stones from pit 2 end exactly in store 6: an extra turn
        assertEquals(0, a.state.value!!.currentPlayerIndex)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.plain())
        assertEquals("the extra turn is still owed", 0, b.state.value!!.currentPlayerIndex)
        assertEquals(a.state.value!!.lastSowPath, b.state.value!!.lastSowPath)

        a.sow(0, 3)
        b.sow(0, 3)
        assertEquals("the same move must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `a restored position is marked restored so the screen skips the hop animation, and the next sow is not`() {
        val a = started()
        a.sow(0, 2)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.restored)
        b.sow(0, 3)
        assertFalse(b.state.value!!.restored)
    }

    @Test
    fun `the session tally, a finished board and the difficulty survive a resume`() {
        val a = started()
        a.difficulty = CpuDifficulty.HARD
        seed(a, almostWon)
        a.sow(0, 5)
        assertEquals(1, a.scoreP1.value)
        assertTrue(a.state.value!!.roundOver)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.scoreP1.value)
        assertEquals(0, b.scoreP2.value)
        assertEquals(0, b.draws.value)
        assertTrue(b.state.value!!.roundOver)
        assertEquals("p0", b.state.value!!.winnerPlayerId)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
    }

    @Test
    fun `a resume that lands on the bot's turn keeps the bot on move`() {
        val a = started(botIndex = 1)
        a.sow(0, 0) // ends in pit 4: the turn passes to the CPU
        assertEquals(1, a.state.value!!.currentPlayerIndex)
        val b = newGame(botIndex = 1)
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.state.value!!.currentPlayerIndex)
        b.playBotTurn()
        a.playBotTurn()
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `restore rejects garbage, a wrong roster, a wrong-sized board and a wrong stone count, leaving the engine untouched`() {
        val game = started()
        game.sow(0, 0)
        val before = game.state.value
        val beforeScore = game.scoreP1.value

        assertFalse(game.restore("not json at all"))
        assertFalse(game.restore(""))
        assertFalse(game.restore("{}"))
        assertFalse("different player ids", game.restore(json(openingPits, p0 = "x", p1 = "y")))
        assertFalse("13 pits", game.restore(json("4,4,4,4,4,4,0,4,4,4,4,4,4")))
        assertFalse("15 pits", game.restore(json("$openingPits,0")))
        assertFalse("49 stones", game.restore(json("5,4,4,4,4,4,0,4,4,4,4,4,4,0")))
        assertFalse("negative pit", game.restore(json("-1,5,4,4,4,4,4,4,4,4,4,4,4,0")))

        assertEquals(before, game.state.value)
        assertEquals(beforeScore, game.scoreP1.value)
        assertTrue("a failed restore must not drop the undo history", game.canUndo)

        // Positive control: the hand-built JSON is itself valid.
        assertTrue(game.restore(json(openingPits)))
        assertEquals(List(14) { if (it == 6 || it == 13) 0 else 4 }, game.state.value!!.pits)
    }

    @Test
    fun `restore rejects a snapshot taken for a different roster of bots`() {
        val vsCpu = started(botIndex = 1)
        vsCpu.sow(0, 0)
        val twoHumans = newGame()
        assertFalse(twoHumans.restore(vsCpu.snapshot()!!))
    }

    @Test
    fun `restore clears the undo history and any hint`() {
        val a = started()
        a.sow(0, 0)
        val snapshot = a.snapshot()!!
        assertTrue(a.canUndo)
        a.sow(1, 7)
        a.hint()
        assertTrue(a.restore(snapshot))
        assertFalse(a.canUndo)
        assertNull(a.hintPit.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo goes back one human sowing at a time, including across an extra turn`() {
        val g = started()
        val start = g.plain()
        g.sow(0, 2) // extra turn: player 0 is still on move
        val afterFirst = g.plain()
        g.sow(0, 1) // ends in pit 5: turn passes
        assertEquals(1, g.state.value!!.currentPlayerIndex)

        g.undo()
        assertEquals(afterFirst, g.plain())
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        assertTrue(g.canUndo)

        g.undo()
        assertEquals(start, g.plain())
        assertFalse(g.canUndo)
        g.undo() // nothing left: harmless
        assertEquals(start, g.plain())
    }

    @Test
    fun `against the CPU one undo takes back the human sowing and the whole CPU reply`() {
        val g = started(botIndex = 1)
        val start = g.plain()
        g.sow(0, 0)
        // The CPU may chain extra turns; let it play until the move comes back to the human.
        var guard = 0
        while (g.state.value!!.currentPlayerIndex == 1 && !g.state.value!!.roundOver && guard++ < 20) g.playBotTurn()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        assertTrue(g.canUndo)

        g.undo()
        assertEquals(start, g.plain())
        assertFalse(g.canUndo)
    }

    @Test
    fun `the CPU's own moves leave no undo step`() {
        val g = started(botIndex = 0)
        g.playBotTurn()
        assertFalse(g.canUndo)
    }

    @Test
    fun `a rejected move leaves no undo step`() {
        val g = started()
        g.sow(1, 7) // not player 1's turn
        g.sow(0, 6) // a store is not a pit
        g.sow(0, 8) // someone else's pit
        assertFalse(g.canUndo)
        seed(g, listOf(0, 4, 4, 4, 4, 4, 0, 4, 4, 4, 4, 4, 4, 4))
        g.sow(0, 0) // empty pit
        assertFalse(g.canUndo)
    }

    @Test
    fun `undoing a game-ending sowing re-opens the board and takes the win off the tally`() {
        val g = started()
        seed(g, almostWon)
        g.sow(0, 5)
        assertEquals(1, g.scoreP1.value)
        assertTrue(g.state.value!!.roundOver)

        g.undo()
        assertEquals(0, g.scoreP1.value)
        assertFalse(g.state.value!!.roundOver)
        assertNull(g.state.value!!.winnerPlayerId)
        assertEquals(almostWon, g.state.value!!.pits)

        g.sow(0, 5) // and it can be played again
        assertEquals(1, g.scoreP1.value)
    }

    @Test
    fun `undo does not touch the difficulty`() {
        val g = started(botIndex = 1)
        g.sow(0, 0)
        g.difficulty = CpuDifficulty.HARD
        g.undo()
        assertEquals(CpuDifficulty.HARD, g.difficulty)
    }

    @Test
    fun `undo never reaches into the previous board`() {
        val g = started()
        seed(g, almostWon)
        g.sow(0, 5)
        assertTrue(g.canUndo)
        g.playAgain()
        assertFalse(g.canUndo)
        val fresh = g.plain()
        g.undo()
        assertEquals(fresh, g.plain())
        assertEquals("the session tally carries over", 1, g.scoreP1.value)
    }

    @Test
    fun `undo does nothing once the session has ended`() {
        val g = started()
        g.sow(0, 0)
        assertTrue(g.canUndo)
        g.leaveSession()
        assertFalse(g.canUndo)
        val frozen = g.state.value
        g.undo()
        assertEquals(frozen, g.state.value)
    }

    @Test
    fun `an undone position is marked restored`() {
        val g = started()
        g.sow(0, 0)
        assertFalse(g.state.value!!.restored)
        g.undo()
        assertTrue(g.state.value!!.restored)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint suggests the only legal pit and exposes it`() {
        val g = started()
        seed(g, listOf(0, 0, 0, 2, 0, 0, 10, 0, 0, 0, 0, 0, 5, 31))
        assertEquals(3, g.hint())
        assertEquals(3, g.hintPit.value)
    }

    @Test
    fun `hint is the pit the strongest CPU would play from the same position`() {
        val position = listOf(3, 0, 5, 2, 6, 1, 4, 2, 5, 0, 3, 4, 1, 12)
        val human = started()
        seed(human, position)
        val suggested = human.hint()!!
        assertTrue(suggested in 0..5 && position[suggested] > 0)

        val cpu = started(botIndex = 0)
        cpu.difficulty = CpuDifficulty.HARD
        seed(cpu, position)
        cpu.playBotTurn()
        assertEquals(cpu.state.value!!.lastSowPath.first(), suggested)
    }

    @Test
    fun `hint names a pit on player one's side when it is player one's turn`() {
        val g = started()
        seed(g, listOf(0, 0, 0, 2, 0, 0, 10, 0, 0, 5, 0, 0, 0, 31), current = 1)
        assertEquals(9, g.hint())
    }

    @Test
    fun `hint is null before the first board, on the CPU's turn, when the board is over and after the session`() {
        val g = newGame(botIndex = 1)
        assertNull(g.hint())

        g.startMatch()
        g.sow(0, 0)
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        assertNull("the CPU is thinking", g.hint())
        assertNull(g.hintPit.value)

        val h = started()
        seed(h, almostWon)
        h.sow(0, 5)
        assertNull("board over", h.hint())

        val s = started()
        s.leaveSession()
        assertNull(s.hint())
    }

    @Test
    fun `a hint is cleared by the next sowing, by undo, and by a new board`() {
        val g = started()
        g.sow(0, 0) // gives undo something to take back, and passes the turn to player 1
        assertNotNull(g.hint())
        assertNotNull(g.hintPit.value)
        g.sow(1, 7)
        assertNull(g.hintPit.value)

        assertNotNull(g.hint())
        g.undo()
        assertNull(g.hintPit.value)

        assertNotNull(g.hint())
        g.startMatch()
        assertNull(g.hintPit.value)
    }

    @Test
    fun `a hint is only a suggestion and changes nothing`() {
        val g = started()
        val before = g.state.value
        g.hint()
        assertEquals(before, g.state.value)
        assertFalse(g.canUndo)
    }
}
