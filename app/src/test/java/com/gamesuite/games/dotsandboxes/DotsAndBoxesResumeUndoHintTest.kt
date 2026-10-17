package com.gamesuite.games.dotsandboxes

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume (snapshot/restore), Undo and Hint for Dots and Boxes. */
class DotsAndBoxesResumeUndoHintTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): DotsAndBoxesGame {
        val game = DotsAndBoxesGame()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(players: List<PlayerInfo> = twoHumans, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
        newGame(players, mode).also { it.startMatch() }

    /** Three sides of box (0,0) drawn (p1, p2, p1); it is now player 2's turn and V(0,1) completes the box. */
    private fun threeSidedBox(): DotsAndBoxesGame {
        val g = started()
        g.drawHorizontalEdge(0, 0)
        g.drawHorizontalEdge(1, 0)
        g.drawVerticalEdge(0, 0)
        return g
    }

    /** Draws every edge in scan order until the board is over. */
    private fun playOutBoard(g: DotsAndBoxesGame) {
        for (r in 0..5) for (c in 0 until 5) g.drawHorizontalEdge(r, c)
        for (r in 0 until 5) for (c in 0..5) g.drawVerticalEdge(r, c)
    }

    private fun maxSides(s: DotsAndBoxesState): Int {
        var best = 0
        for (r in 0 until s.boxRows) for (c in 0 until s.boxCols) {
            if (s.boxOwner[r * s.boxCols + c] != null) continue
            var n = 0
            if (s.horizontalEdges[r * s.boxCols + c]) n++
            if (s.horizontalEdges[(r + 1) * s.boxCols + c]) n++
            if (s.verticalEdges[r * (s.boxCols + 1) + c]) n++
            if (s.verticalEdges[r * (s.boxCols + 1) + c + 1]) n++
            if (n > best) best = n
        }
        return best
    }

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
        val a = threeSidedBox()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))

        assertEquals(a.state.value, b.state.value)
        assertEquals(a.sessionWins.value, b.sessionWins.value)
        assertEquals(a.sessionDraws.value, b.sessionDraws.value)

        a.drawVerticalEdge(0, 1)
        b.drawVerticalEdge(0, 1)
        assertEquals("the same move must lead to the same position", a.state.value, b.state.value)
        assertEquals("completing the box keeps the turn", 1, b.state.value!!.currentPlayerIndex)
        assertEquals(1, b.state.value!!.scores[1])
    }

    @Test
    fun `a pending go-again turn survives a resume`() {
        val a = threeSidedBox()
        a.drawVerticalEdge(0, 1) // player 2 claims box 0 and must draw again
        assertEquals(1, a.state.value!!.currentPlayerIndex)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.state.value!!.currentPlayerIndex)
        assertEquals(listOf(0, 1), b.state.value!!.scores)
        assertEquals(1, b.state.value!!.boxOwner[0])
    }

    @Test
    fun `the session tally and the next opener survive a resume`() {
        val a = started()
        playOutBoard(a)
        assertTrue(a.state.value!!.boardOver)
        assertEquals(1, a.sessionWins.value.values.sum())
        a.playAgain()
        a.drawHorizontalEdge(0, 0)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(a.sessionWins.value, b.sessionWins.value)
        assertEquals(a.state.value, b.state.value)
        a.playAgain()
        b.playAgain()
        assertEquals("the opener keeps alternating after a resume", a.state.value!!.currentPlayerIndex, b.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a finished board can be resumed and shows its result`() {
        val a = started()
        playOutBoard(a)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.boardOver)
        assertEquals(a.state.value!!.winnerPlayerId, b.state.value!!.winnerPlayerId)
    }

    @Test
    fun `the difficulty survives a resume against the CPU`() {
        val a = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        a.difficulty = CpuDifficulty.HARD
        val b = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(CpuDifficulty.HARD, b.difficulty)
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a wrong sized board and leaves the engine untouched`() {
        val source = threeSidedBox()
        val good = source.snapshot()!!

        val target = started()
        target.drawHorizontalEdge(2, 2)
        val before = target.state.value
        val depthBefore = target.canUndo

        assertFalse(target.restore("not json at all"))
        assertFalse(target.restore("{}"))
        assertFalse("wrong board size", target.restore(good.replace("\"boxRows\":5", "\"boxRows\":4")))

        val vsBot = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        vsBot.startMatch()
        val botBefore = vsBot.state.value
        assertFalse("different roster", vsBot.restore(good))

        assertEquals(before, target.state.value)
        assertEquals(depthBefore, target.canUndo)
        assertEquals(botBefore, vsBot.state.value)
    }

    @Test
    fun `restore clears undo history and any hint`() {
        val a = threeSidedBox()
        a.hint()
        assertNotNull(a.hintEdge.value)
        assertTrue(a.canUndo)
        assertTrue(a.restore(a.snapshot()!!))
        assertFalse(a.canUndo)
        assertNull(a.hintEdge.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo goes back one human edge at a time`() {
        val g = started()
        val fresh = g.state.value
        assertFalse(g.canUndo)
        g.drawHorizontalEdge(0, 0)
        val afterOne = g.state.value
        g.drawHorizontalEdge(3, 3)
        assertTrue(g.canUndo)

        g.undo()
        assertEquals(afterOne, g.state.value)
        g.undo()
        assertEquals(fresh, g.state.value)
        assertFalse(g.canUndo)
        g.undo() // nothing left: a no-op
        assertEquals(fresh, g.state.value)
    }

    @Test
    fun `a rejected tap leaves no undo step`() {
        val g = started()
        g.drawHorizontalEdge(0, 0)
        g.drawHorizontalEdge(0, 0) // already drawn
        g.drawHorizontalEdge(9, 9) // out of range
        g.undo()
        assertFalse(g.canUndo)
        assertTrue(g.state.value!!.horizontalEdges.none { it })
    }

    @Test
    fun `undoing a box-completing edge takes the box and the go-again turn back`() {
        val g = threeSidedBox()
        g.drawVerticalEdge(0, 1)
        assertEquals(1, g.state.value!!.boxOwner[0])
        assertEquals(1, g.state.value!!.currentPlayerIndex)

        g.undo()
        val s = g.state.value!!
        assertNull(s.boxOwner[0])
        assertEquals(listOf(0, 0), s.scores)
        assertFalse(s.verticalEdges[1])
        assertEquals("it is player 2's turn again", 1, s.currentPlayerIndex)
        assertNull("no stale claim effect after an undo", s.lastBoxCompleted)
    }

    @Test
    fun `against the CPU one undo takes back the human edge and the whole CPU reply`() {
        val g = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        g.difficulty = CpuDifficulty.EASY
        val fresh = g.state.value
        g.drawHorizontalEdge(0, 0)
        assertEquals("a line that claims nothing hands the turn to the CPU", 1, g.state.value!!.currentPlayerIndex)
        g.playBotTurn()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        assertTrue(g.state.value!!.horizontalEdges.count { it } + g.state.value!!.verticalEdges.count { it } >= 2)

        g.undo()
        assertEquals(fresh, g.state.value)
        assertFalse(g.canUndo)
    }

    @Test
    fun `the CPU's own moves leave no undo step`() {
        val g = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        g.drawHorizontalEdge(0, 0)
        g.playBotTurn()
        g.undo()
        assertFalse("only the human's edge was a step", g.canUndo)
    }

    @Test
    fun `undoing the board-finishing edge reopens the board and takes its win off the tally`() {
        val g = started()
        playOutBoard(g)
        assertTrue(g.state.value!!.boardOver)
        assertEquals(1, g.sessionWins.value.values.sum())

        g.undo()
        assertFalse(g.state.value!!.boardOver)
        assertNull(g.state.value!!.winnerPlayerId)
        assertEquals(0, g.sessionWins.value.values.sum())
        assertEquals(0, g.sessionDraws.value)
    }

    @Test
    fun `undo does not cross into a new board and does nothing after the session ends`() {
        val g = started()
        g.drawHorizontalEdge(0, 0)
        assertTrue(g.canUndo)
        g.playAgain()
        assertFalse("a new board starts a new history", g.canUndo)

        g.drawHorizontalEdge(1, 1)
        g.leaveSession()
        assertFalse(g.canUndo)
        val after = g.state.value
        g.undo()
        assertEquals(after, g.state.value)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint is null before the first board, on the CPU's turn, and once the board or session is over`() {
        val g = newGame()
        assertNull(g.hint())

        val vsBot = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        vsBot.drawHorizontalEdge(0, 0)
        assertNull("the CPU is to move", vsBot.hint())
        assertNull(vsBot.hintEdge.value)

        val done = started()
        playOutBoard(done)
        assertNull("the board is over", done.hint())

        val left = started()
        left.leaveSession()
        assertNull(left.hint())
    }

    @Test
    fun `hint takes a box when one can be completed`() {
        val g = threeSidedBox()
        val before = g.state.value
        val hint = g.hint()
        assertEquals(DotsAndBoxesHintEdge(horizontal = false, row = 0, col = 1), hint)
        assertEquals(hint, g.hintEdge.value)
        assertEquals("a hint is a suggestion, not a move", before, g.state.value)
        g.undo()
        // The hint added no undo step: the one undo takes back the last REAL edge, V(0,0).
        assertFalse(g.state.value!!.verticalEdges[0])
        assertTrue(g.state.value!!.horizontalEdges[0] && g.state.value!!.horizontalEdges[5])
    }

    @Test
    fun `hint avoids creating a three-sided box when it can`() {
        val g = started()
        g.drawHorizontalEdge(0, 0) // box (0,0) now has one side
        g.drawHorizontalEdge(1, 0) // and two
        val hint = g.hint()!!
        assertFalse(
            "must not be one of box (0,0)'s two remaining sides",
            (!hint.horizontal && hint.row == 0 && (hint.col == 0 || hint.col == 1))
        )
        if (hint.horizontal) g.drawHorizontalEdge(hint.row, hint.col) else g.drawVerticalEdge(hint.row, hint.col)
        assertTrue("the suggested line hands over no three-sided box", maxSides(g.state.value!!) < 3)
    }

    @Test
    fun `hint on a fresh board is a legal undrawn edge`() {
        val g = started()
        val hint = g.hint()!!
        val s = g.state.value!!
        val drawn = if (hint.horizontal) s.horizontalEdges[hint.row * s.boxCols + hint.col]
        else s.verticalEdges[hint.row * (s.boxCols + 1) + hint.col]
        assertFalse(drawn)
    }

    @Test
    fun `the next move and undo clear the hint, and a new board does too`() {
        val g = threeSidedBox()
        g.hint()
        assertNotNull(g.hintEdge.value)
        g.drawHorizontalEdge(4, 4)
        assertNull("the next edge clears it", g.hintEdge.value)

        g.hint()
        assertNotNull(g.hintEdge.value)
        g.undo()
        assertNull("undo clears it", g.hintEdge.value)

        g.hint()
        assertNotNull(g.hintEdge.value)
        g.playAgain()
        assertNull("a new board clears it", g.hintEdge.value)
        assertNotEquals(null, g.state.value)
    }
}
