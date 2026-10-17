package com.gamesuite.games.reversi

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

/** Resume (snapshot/restore), Undo and Hint for Reversi, written against the Connect Four reference. */
class ReversiResumeUndoHintTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): ReversiGame {
        val game = ReversiGame()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(players: List<PlayerInfo> = twoHumans, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
        newGame(players, mode).also { it.startMatch() }

    /** Plays the lowest-numbered legal cell [n] times (stops early if the board ends). */
    private fun playFirstLegal(game: ReversiGame, n: Int) {
        repeat(n) {
            val s = game.state.value!!
            if (!s.boardOver) game.placeDisc(s.legalMoves.sorted().first())
        }
    }

    private fun playToTheEnd(game: ReversiGame) {
        var guard = 0
        while (!game.state.value!!.boardOver && guard++ < 200) playFirstLegal(game, 1)
        assertTrue("the board must finish", game.state.value!!.boardOver)
    }

    /** Hand-built snapshot: only cell 27 (player 1) and cell 28 (player 0) are occupied, player 0 to move. */
    private fun sparseSnapshot(): String {
        val cells = (0 until 64).joinToString(",") {
            when (it) {
                27 -> "1"
                28 -> "0"
                else -> "null"
            }
        }
        return "{\"cells\":[" + cells + "]," +
            "\"players\":[{\"playerId\":\"p1\",\"displayName\":\"Player 1\",\"isBot\":false}," +
            "{\"playerId\":\"p2\",\"displayName\":\"Player 2\",\"isBot\":false}]," +
            "\"currentPlayerIndex\":0,\"lastAction\":\"test\",\"justPassed\":false,\"boardOver\":false," +
            "\"winnerPlayerId\":null,\"sessionWins\":{\"p1\":0,\"p2\":0},\"sessionDraws\":0," +
            "\"nextStartingPlayerIndex\":0,\"difficulty\":\"MEDIUM\"}"
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
        val a = started()
        playFirstLegal(a, 6)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.state.value)
        assertEquals(a.sessionWins.value, b.sessionWins.value)
        assertEquals(a.sessionDraws.value, b.sessionDraws.value)

        playFirstLegal(a, 1)
        playFirstLegal(b, 1)
        assertEquals("the same move must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `the session tally and the next opener survive a resume`() {
        val a = started()
        playToTheEnd(a)
        val finished = a.sessionWins.value.values.sum() + a.sessionDraws.value
        assertEquals(1, finished)
        a.playAgain()
        playFirstLegal(a, 2)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(a.sessionWins.value, b.sessionWins.value)
        assertEquals(a.sessionDraws.value, b.sessionDraws.value)
        assertEquals(a.state.value, b.state.value)

        a.playAgain()
        b.playAgain()
        assertEquals("the opener keeps alternating after a resume", a.state.value!!.currentPlayerIndex, b.state.value!!.currentPlayerIndex)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `a finished board's result survives a resume`() {
        val a = started()
        playToTheEnd(a)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.boardOver)
        assertEquals(a.state.value, b.state.value)
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
        cpuGame.placeDisc(cpuGame.state.value!!.legalMoves.sorted().first())
        val snapshot = cpuGame.snapshot()!!

        val humans = newGame(twoHumans)
        assertFalse("a vs-CPU save must not load into a two-human game", humans.restore(snapshot))
        assertNull(humans.state.value)
    }

    @Test
    fun `restore rejects a snapshot whose board is the wrong size or holds an unknown owner`() {
        val a = started()
        playFirstLegal(a, 1)
        val good = a.snapshot()!!

        val short = good.replaceFirst(Regex("\"cells\":\\[null,"), "\"cells\":[")
        assertTrue("the test edit must actually change the snapshot", short != good)
        val b = newGame()
        assertFalse(b.restore(short))
        assertNull(b.state.value)

        val badOwner = good.replaceFirst(Regex("\"cells\":\\[null,"), "\"cells\":[7,")
        assertTrue(badOwner != good)
        assertFalse(b.restore(badOwner))
        assertNull(b.state.value)
    }

    @Test
    fun `a failed restore leaves an already running game untouched`() {
        val game = started()
        playFirstLegal(game, 2)
        val before = game.state.value
        assertFalse(game.restore("garbage"))
        assertEquals(before, game.state.value)
        assertTrue(game.canUndo)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one human move at a time down to the opening position`() {
        val game = started()
        val opening = game.state.value
        assertFalse(game.canUndo)

        playFirstLegal(game, 1)
        val afterOne = game.state.value
        playFirstLegal(game, 1)
        assertTrue(game.canUndo)

        game.undo()
        assertEquals(afterOne, game.state.value)

        game.undo()
        assertEquals(opening, game.state.value)
        assertFalse(game.canUndo)

        game.undo() // nothing left: harmless
        assertEquals(opening, game.state.value)
    }

    @Test
    fun `against the CPU undo also takes the CPU reply back`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.EASY
        val opening = game.state.value
        game.placeDisc(game.state.value!!.legalMoves.sorted().first())
        game.playBotTurn()
        assertEquals("the human's disc and the CPU reply are on the board", 6, game.state.value!!.cells.count { it != null })

        game.undo()

        assertEquals("back to the position before the human move", opening, game.state.value)
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertFalse(game.canUndo)
    }

    @Test
    fun `undoing the board-ending move reopens the board and takes the result off the tally`() {
        val game = started()
        playToTheEnd(game)
        assertEquals(1, game.sessionWins.value.values.sum() + game.sessionDraws.value)

        game.undo()

        assertFalse(game.state.value!!.boardOver)
        assertNull(game.state.value!!.winnerPlayerId)
        assertEquals(0, game.sessionWins.value.values.sum() + game.sessionDraws.value)
        assertTrue(game.state.value!!.legalMoves.isNotEmpty())
    }

    @Test
    fun `a new board starts a fresh undo history`() {
        val game = started()
        playToTheEnd(game)
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse("undo must not reach back into the previous board", game.canUndo)
    }

    @Test
    fun `undo does nothing once the session has ended`() {
        val game = started()
        playFirstLegal(game, 1)
        game.leaveSession()
        assertFalse(game.canUndo)
        val before = game.state.value
        game.undo()
        assertEquals(before, game.state.value)
    }

    @Test
    fun `a rejected move leaves no empty undo step behind`() {
        val game = started()
        game.placeDisc(0)   // not a legal opening move
        game.placeDisc(-1)
        game.placeDisc(64)
        assertFalse(game.canUndo)
    }

    @Test
    fun `a resumed game starts with an empty undo history`() {
        val a = started()
        playFirstLegal(a, 2)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse("undo history is in memory only", b.canUndo)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `a hint on the opening is a legal move and is shown`() {
        val game = started()
        val move = game.hint()
        assertNotNull(move)
        assertTrue(move!! in game.state.value!!.legalMoves)
        assertEquals(move, game.hintMove.value)
    }

    @Test
    fun `a hint on a hand-built position with one legal move suggests exactly that move`() {
        val game = newGame()
        assertTrue(game.restore(sparseSnapshot()))
        assertEquals(setOf(26), game.state.value!!.legalMoves)
        assertEquals(26, game.hint())
    }

    @Test
    fun `no hint on the CPU's turn or after the board is over`() {
        val game = started(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.placeDisc(game.state.value!!.legalMoves.sorted().first())
        assertEquals("it is the CPU's turn", 1, game.state.value!!.currentPlayerIndex)
        assertNull(game.hint())
        assertNull(game.hintMove.value)

        val over = started()
        playToTheEnd(over)
        assertNull(over.hint())
        assertNull(over.hintMove.value)
    }

    @Test
    fun `the next move clears the hint and so does undo, a new board and a restore`() {
        val game = started()
        game.hint()
        assertNotNull(game.hintMove.value)
        playFirstLegal(game, 1)
        assertNull(game.hintMove.value)

        game.hint()
        assertNotNull(game.hintMove.value)
        game.undo()
        assertNull(game.hintMove.value)

        game.hint()
        assertNotNull(game.hintMove.value)
        game.playAgain()
        assertNull(game.hintMove.value)

        game.hint()
        assertTrue(game.restore(sparseSnapshot()))
        assertNull(game.hintMove.value)
    }
}
