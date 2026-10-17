package com.gamesuite.games.dominoes

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

/** Resume (snapshot/restore) and Undo for Dominoes. No hint: non-matching tiles are already dimmed. */
class DominoResumeUndoTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): DominoGame {
        val game = DominoGame()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(players: List<PlayerInfo> = twoHumans, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
        newGame(players, mode).also { it.startMatch() }

    /** Exactly ONE engine action for the player to move: play the first legal tile, else draw, else pass. */
    private fun step(g: DominoGame) {
        val s = g.state.value!!
        val i = s.currentPlayerIndex
        val tile = s.players[i].hand.firstOrNull { g.canPlace(it, true) || g.canPlace(it, false) }
        when {
            tile != null -> g.playDomino(i, tile, g.canPlace(tile, true))
            s.boneyardSize > 0 -> g.drawFromBoneyard(i)
            else -> g.pass(i)
        }
    }

    /** Steps until the turn passes to somebody else (or the hand ends). */
    private fun takeTurn(g: DominoGame) {
        val i = g.state.value!!.currentPlayerIndex
        repeat(40) {
            val cur = g.state.value!!
            if (cur.currentPlayerIndex != i || cur.handOver) return
            step(g)
        }
    }

    private fun playHandToTheEnd(g: DominoGame) {
        repeat(500) {
            if (g.state.value!!.handOver) return
            takeTurn(g)
        }
        assertTrue("the hand must finish", g.state.value!!.handOver)
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first hand, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no hand dealt yet", game.snapshot())

        game.startMatch()
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = started(mode = PlayMode.ONLINE)
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically including draws from the boneyard`() {
        val a = started()
        repeat(3) { step(a) }
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(a.state.value, b.state.value)
        assertEquals(a.sessionScores.value, b.sessionScores.value)
        assertEquals(a.handsFinished.value, b.handsFinished.value)

        // The same actions (which draw the same hidden boneyard tiles) must lead to the same positions.
        repeat(25) {
            if (a.state.value!!.handOver) return@repeat
            step(a)
            step(b)
            assertEquals("the same move must lead to the same position", a.state.value, b.state.value)
        }
    }

    @Test
    fun `the session scores, finished-hand count and a finished hand survive a resume`() {
        val a = started()
        playHandToTheEnd(a)
        assertEquals(1, a.handsFinished.value)
        val winner = a.state.value!!.winnerPlayerId

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(a.state.value, b.state.value)
        assertTrue(b.state.value!!.handOver)
        assertEquals(winner, b.state.value!!.winnerPlayerId)
        assertEquals(a.sessionScores.value, b.sessionScores.value)
        assertEquals(1, b.handsFinished.value)

        a.playAgain()
        b.playAgain()
        assertEquals("a resumed session still deals a fresh hand", 7, b.state.value!!.players[0].hand.size)
        assertEquals(1, b.handsFinished.value)
        assertEquals(a.sessionScores.value, b.sessionScores.value)
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
        while (!a.state.value!!.players[a.state.value!!.currentPlayerIndex].isBot) takeTurn(a)
        val b = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.players[b.state.value!!.currentPlayerIndex].isBot)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `restore rejects garbage and leaves the game untouched`() {
        val game = newGame()
        assertFalse(game.restore("not json at all"))
        assertFalse(game.restore("{}"))
        assertFalse(game.restore(""))
        assertNull("a rejected restore must not deal a hand", game.state.value)
    }

    @Test
    fun `restore rejects a snapshot from a different roster`() {
        val cpuGame = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        val snapshot = cpuGame.snapshot()!!

        val humans = newGame(twoHumans)
        assertFalse("a vs-CPU save must not load into a two-human game", humans.restore(snapshot))
        assertNull(humans.state.value)
    }

    @Test
    fun `restore rejects a snapshot with a missing tile and leaves a running game untouched`() {
        val a = started()
        val good = a.snapshot()!!
        // Cut the first boneyard tile out: 27 tiles are left, which is not the full set.
        val bad = good.replaceFirst(Regex("\"boneyard\":\\[\\{[^}]*\\},"), "\"boneyard\":[")
        assertTrue("the test edit must actually change the snapshot", bad != good)

        val b = started()
        val before = b.state.value
        assertFalse(b.restore(bad))
        assertEquals("a rejected restore must leave the running hand alone", before, b.state.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one human action at a time, draws included, and replays identically`() {
        val game = started()
        assertFalse(game.canUndo)

        val states = mutableListOf(game.state.value!!)
        var guard = 0
        while (!game.state.value!!.handOver && states.size < 12 && guard++ < 60) {
            step(game)
            if (game.state.value != states.last()) states.add(game.state.value!!)
        }
        assertTrue(game.canUndo)

        for (k in states.size - 2 downTo 0) {
            game.undo()
            assertEquals("undo must return to the position before action $k+1", states[k], game.state.value)
        }
        assertFalse(game.canUndo)
        game.undo() // nothing left: harmless
        assertEquals(states[0], game.state.value)

        // Replaying the same actions must reproduce the same hidden boneyard draws.
        for (k in 1 until states.size) {
            step(game)
            assertEquals(states[k], game.state.value)
        }
    }

    @Test
    fun `an undone draw puts the tile back on top of the boneyard`() {
        val game = started()
        // Play until somebody has to draw.
        var guard = 0
        while (guard++ < 80 && !game.state.value!!.handOver) {
            val s = game.state.value!!
            val i = s.currentPlayerIndex
            if (!game.canPlay(i) && s.boneyardSize > 0) break
            step(game)
        }
        val s = game.state.value!!
        if (s.handOver || game.canPlay(s.currentPlayerIndex) || s.boneyardSize == 0) return // no draw arose; nothing to test
        val before = s
        game.drawFromBoneyard(s.currentPlayerIndex)
        assertEquals(before.boneyardSize - 1, game.state.value!!.boneyardSize)
        game.undo()
        assertEquals(before, game.state.value)
        game.drawFromBoneyard(s.currentPlayerIndex)
        val again = game.state.value!!.players[s.currentPlayerIndex].hand.last()
        game.undo()
        game.drawFromBoneyard(s.currentPlayerIndex)
        assertEquals("the same tile is drawn again after an undo", again, game.state.value!!.players[s.currentPlayerIndex].hand.last())
    }

    @Test
    fun `against the CPU undo also takes the CPU reply back and is unavailable while the CPU moves`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.EASY
        // If the CPU opens, let it; then it is the human's turn.
        if (game.state.value!!.players[game.state.value!!.currentPlayerIndex].isBot) game.playBotTurn()
        assertFalse("the CPU's own opening is not an undo step", game.canUndo)
        val s = game.state.value!!
        assertEquals("the human is to move", 0, s.currentPlayerIndex)

        // The human acts until the turn passes to the CPU, counting the human actions.
        var humanActions = 0
        while (game.state.value!!.currentPlayerIndex == 0 && !game.state.value!!.handOver && humanActions < 40) {
            step(game)
            humanActions++
        }
        assertEquals("it is the CPU's turn", 1, game.state.value!!.currentPlayerIndex)
        assertFalse("undo is unavailable while the CPU is moving", game.canUndo)
        val frozen = game.state.value
        game.undo()
        assertEquals(frozen, game.state.value)

        game.playBotTurn()
        assertTrue(game.canUndo)
        repeat(humanActions) { game.undo() }

        assertEquals("the human's hand, the chain and the boneyard are as before", s, game.state.value)
        assertFalse(game.canUndo)
    }

    @Test
    fun `undoing the winning tile re-opens the hand and takes its points back`() {
        val game = started()
        playHandToTheEnd(game)
        assertEquals(1, game.handsFinished.value)
        assertTrue(game.canUndo)

        game.undo()

        assertFalse(game.state.value!!.handOver)
        assertNull(game.state.value!!.winnerPlayerId)
        assertEquals(0, game.handsFinished.value)
        assertTrue(game.sessionScores.value.values.all { it == 0 })
    }

    @Test
    fun `a new hand starts a fresh undo history`() {
        val game = started()
        playHandToTheEnd(game)
        assertTrue(game.canUndo)
        val scoresBefore = game.sessionScores.value
        game.playAgain()
        assertFalse("undo must not reach back into the previous hand", game.canUndo)
        assertEquals("the session score is kept", scoresBefore, game.sessionScores.value)
        assertEquals(1, game.handsFinished.value)
    }

    @Test
    fun `undo does nothing once the session has ended`() {
        val game = started()
        step(game)
        game.leaveSession()
        assertFalse(game.canUndo)
        val before = game.state.value
        game.undo()
        assertEquals(before, game.state.value)
    }

    @Test
    fun `a rejected action leaves no empty undo step behind`() {
        val game = started()
        val s = game.state.value!!
        val i = s.currentPlayerIndex
        val other = 1 - i
        game.playDomino(other, s.players[other].hand.first(), true) // not that player's turn
        game.playDomino(i, s.players[other].hand.first(), true) // not in the mover's hand
        game.drawFromBoneyard(i) // an empty chain always has a legal opening, so drawing is refused
        game.pass(i) // the boneyard is not empty
        assertFalse(game.canUndo)
    }

    @Test
    fun `a resumed game starts with an empty undo history`() {
        val a = started()
        step(a)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse("undo history is in memory only", b.canUndo)
    }
}
