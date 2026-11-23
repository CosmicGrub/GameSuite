package com.gamesuite.games.backgammon

import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game engine: opening roll, turn flow, undo, scoring, session tally, resume, hint and the CPU. */
class BackgammonGameTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    /** A started game; [first] forces the opening seat (null = the opening roll decides), [script] feeds the dice. */
    private fun game(
        script: List<Int> = emptyList(),
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        first: Int? = 0,
        difficulty: CpuDifficulty? = null
    ): BackgammonGame {
        val g = BackgammonGame(BgScriptedRandom(script))
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        g.firstOpenerIndex = first
        if (difficulty != null) g.difficulty = difficulty
        g.startMatch()
        return g
    }

    private fun BackgammonGame.s(): BackgammonState = state.value!!

    // --- Module metadata -----------------------------------------------------------------------

    @Test
    fun `module metadata matches the registration`() {
        val g = BackgammonGame()
        assertEquals("backgammon", g.gameId)
        assertEquals("Backgammon", g.displayName)
        assertEquals(GameCategory.BOARD, g.category)
        assertEquals(2, g.minPlayers)
        assertEquals(2, g.maxPlayers)
        assertEquals(setOf(PlayMode.SINGLE_PLAYER_VS_BOT, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY), g.supportedModes.toSet())
    }

    // --- Opening roll --------------------------------------------------------------------------

    @Test
    fun `without a forced opener the game waits for the opening roll`() {
        val g = game(first = null)
        assertEquals(BackgammonPhase.OPENING, g.s().phase)
        assertEquals(BackgammonBoard.initial(), g.s().board)
        assertTrue(g.s().dice.isEmpty())
    }

    @Test
    fun `the higher opening die starts and plays both numbers`() {
        val g = game(script = listOf(5, 2), first = null)
        g.rollDice()
        val s = g.s()
        assertEquals(BackgammonPhase.MOVING, s.phase)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(listOf(5, 2), s.dice)
        assertEquals(listOf(5, 2), s.remaining)
        assertEquals(listOf(5, 2), s.openingDice)
        assertTrue(s.legalMoves.isNotEmpty())
    }

    @Test
    fun `the second seat starts when its opening die is higher`() {
        val g = game(script = listOf(2, 5), first = null)
        g.rollDice()
        assertEquals(1, g.s().currentPlayerIndex)
        assertEquals(BackgammonPhase.MOVING, g.s().phase)
    }

    @Test
    fun `an opening tie rolls again`() {
        val g = game(script = listOf(3, 3, 6, 1), first = null)
        g.rollDice()
        assertEquals(BackgammonPhase.OPENING, g.s().phase)
        assertEquals(1, g.s().openingTies)
        assertEquals(listOf(3, 3), g.s().openingDice)
        g.rollDice()
        assertEquals(BackgammonPhase.MOVING, g.s().phase)
        assertEquals(0, g.s().currentPlayerIndex)
        assertEquals(listOf(6, 1), g.s().dice)
    }

    @Test
    fun `a forced first mover skips the opening roll and rolls normally`() {
        val g = game(script = listOf(4, 2), first = 1)
        assertEquals(BackgammonPhase.TO_ROLL, g.s().phase)
        assertEquals(1, g.s().currentPlayerIndex)
        g.rollDice()
        assertEquals(BackgammonPhase.MOVING, g.s().phase)
        assertEquals(1, g.s().currentPlayerIndex)
        assertEquals(listOf(4, 2), g.s().dice)
        assertTrue(g.s().openingDice.isEmpty())
    }

    // --- Turn flow -----------------------------------------------------------------------------

    @Test
    fun `a double gives four moves`() {
        val g = game(script = listOf(2, 2))
        g.rollDice()
        assertEquals(listOf(2, 2), g.s().dice)
        assertEquals(listOf(2, 2, 2, 2), g.s().remaining)
    }

    @Test
    fun `moving a checker uses its die and an illegal move is refused`() {
        val g = game(script = listOf(3, 1))
        g.rollDice()
        assertFalse("13 -> 12 is blocked", g.moveChecker(bgIx(13), bgIx(12)))
        assertFalse("no die of 5 to play 8 -> 3", g.moveChecker(bgIx(8), bgIx(3)))
        assertTrue(g.moveChecker(bgIx(8), bgIx(5)))
        assertEquals(listOf(1), g.s().remaining)
        assertEquals(bgMove(8, 5, 3), g.s().lastMove)
        assertEquals(1, g.s().moveSeq)
        assertEquals(2, g.s().board.count(0, bgIx(8)))
    }

    @Test
    fun `nothing moves before the dice are rolled`() {
        val g = game(script = listOf(3, 1))
        assertFalse(g.moveChecker(bgIx(8), bgIx(5)))
        assertFalse(g.endTurn())
    }

    @Test
    fun `a roll is ignored while the dice are in play`() {
        val g = game(script = listOf(3, 1, 6, 6))
        g.rollDice()
        g.rollDice()
        assertEquals(listOf(3, 1), g.s().dice)
    }

    @Test
    fun `the turn can only end once every playable die is used`() {
        val g = game(script = listOf(3, 1))
        g.rollDice()
        g.moveChecker(bgIx(8), bgIx(5))
        assertFalse("a legal move remains", g.endTurn())
        assertTrue(g.moveChecker(bgIx(6), bgIx(5)))
        assertTrue(g.s().turnComplete)
        assertTrue(g.endTurn())
        assertEquals(1, g.s().currentPlayerIndex)
        assertEquals(BackgammonPhase.TO_ROLL, g.s().phase)
        assertTrue(g.s().dice.isEmpty())
    }

    @Test
    fun `the other seat plays its own checkers after the turn passes`() {
        val g = game(script = listOf(3, 1, 6, 5))
        g.rollDice()
        g.moveChecker(bgIx(8), bgIx(5))
        g.moveChecker(bgIx(6), bgIx(5))
        g.endTurn()
        g.rollDice()
        assertEquals(1, g.s().currentPlayerIndex)
        // Black moves up the board: its checkers on White's point 1 step to 7 with a six.
        assertTrue(g.moveChecker(bgIx(1), bgIx(7)))
        assertEquals(1, g.s().board.count(1, bgIx(7)))
        assertEquals(1, g.s().board.count(1, bgIx(1)))
    }

    @Test
    fun `hitting a blot puts it on the bar`() {
        val g = game()
        g.debugSetPosition(bgPos(white = mapOf(8 to 1), black = mapOf(5 to 1), whiteOff = 14, blackOff = 14), 0, listOf(3, 1))
        assertTrue(g.moveChecker(bgIx(8), bgIx(5)))
        assertEquals(1, g.s().board.bar(1))
        assertTrue(g.s().lastMove!!.hit)
    }

    @Test
    fun `a roll with no legal move completes the turn at once`() {
        val g = game()
        g.debugSetPosition(
            bgPos(white = mapOf(10 to 1), whiteBar = 1, black = mapOf(19 to 2), whiteOff = 13, blackOff = 13),
            0, listOf(6, 6)
        )
        assertTrue(g.s().legalMoves.isEmpty())
        assertTrue(g.s().turnComplete)
        assertTrue(g.endTurn())
        assertEquals(1, g.s().currentPlayerIndex)
    }

    @Test
    fun `pass and play uses the names it was given`() {
        val g = game(players = listOf(PlayerInfo("a", "Ana"), PlayerInfo("b", "Ben")))
        assertEquals(listOf("Ana", "Ben"), g.s().players.map { it.displayName })
    }

    // --- Undo ----------------------------------------------------------------------------------

    @Test
    fun `undo takes back a single checker move of the current turn`() {
        val g = game(script = listOf(3, 1))
        g.rollDice()
        assertFalse("a roll cannot be undone", g.canUndo)
        g.moveChecker(bgIx(8), bgIx(5))
        g.moveChecker(bgIx(6), bgIx(5))
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(listOf(1), g.s().remaining)
        assertEquals(2, g.s().board.count(0, bgIx(8)))
        assertEquals(1, g.s().board.count(0, bgIx(5)))
        g.undo()
        assertEquals(listOf(3, 1), g.s().remaining)
        assertEquals(BackgammonBoard.initial(), g.s().board)
        assertFalse(g.canUndo)
        // The roll itself is still there, and the same move can be played again.
        assertEquals(listOf(3, 1), g.s().dice)
        assertTrue(g.moveChecker(bgIx(8), bgIx(5)))
    }

    @Test
    fun `ending the turn clears the undo history`() {
        val g = game(script = listOf(3, 1, 4, 2))
        g.rollDice()
        g.moveChecker(bgIx(8), bgIx(5))
        g.moveChecker(bgIx(6), bgIx(5))
        g.endTurn()
        assertFalse(g.canUndo)
        g.rollDice()
        assertFalse(g.canUndo)
    }

    @Test
    fun `undoing the winning move reopens the game and takes the points back`() {
        val g = game()
        g.debugSetPosition(bgPos(white = mapOf(1 to 1), black = mapOf(20 to 15), whiteOff = 14), 0, listOf(1, 2))
        assertTrue(g.moveChecker(bgIx(1), BackgammonMove.OFF))
        assertEquals(BackgammonPhase.OVER, g.s().phase)
        assertEquals(2, g.sessionPoints.value["p1"])
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(BackgammonPhase.MOVING, g.s().phase)
        assertEquals(0, g.sessionPoints.value["p1"])
        assertEquals(14, g.s().board.off(0))
    }

    // --- Scoring and the session ---------------------------------------------------------------

    @Test
    fun `a normal win scores one point`() {
        val g = game()
        g.debugSetPosition(bgPos(white = mapOf(1 to 1), black = mapOf(20 to 14), whiteOff = 14, blackOff = 1), 0, listOf(1, 2))
        g.moveChecker(bgIx(1), BackgammonMove.OFF)
        assertEquals(BackgammonResultKind.NORMAL, g.s().resultKind)
        assertEquals(1, g.s().pointsScored)
        assertEquals("p1", g.s().winnerPlayerId)
    }

    @Test
    fun `a gammon scores two points and a backgammon three`() {
        val gammon = game()
        gammon.debugSetPosition(bgPos(white = mapOf(1 to 1), black = mapOf(20 to 15), whiteOff = 14), 0, listOf(1, 2))
        gammon.moveChecker(bgIx(1), BackgammonMove.OFF)
        assertEquals(BackgammonResultKind.GAMMON, gammon.s().resultKind)
        assertEquals(2, gammon.s().pointsScored)

        val backgammon = game()
        backgammon.debugSetPosition(
            bgPos(white = mapOf(1 to 1), black = mapOf(20 to 14), blackBar = 1, whiteOff = 14), 0, listOf(1, 2)
        )
        backgammon.moveChecker(bgIx(1), BackgammonMove.OFF)
        assertEquals(BackgammonResultKind.BACKGAMMON, backgammon.s().resultKind)
        assertEquals(3, backgammon.s().pointsScored)
    }

    @Test
    fun `the second seat can win too`() {
        val g = game()
        g.debugSetPosition(bgPos(black = mapOf(24 to 1), white = mapOf(10 to 15), blackOff = 14), 1, listOf(1, 2))
        assertTrue(g.moveChecker(bgIx(24), BackgammonMove.OFF))
        assertEquals("p2", g.s().winnerPlayerId)
        assertEquals(2, g.sessionPoints.value["p2"])
        assertEquals(1, g.sessionWins.value["p2"])
    }

    @Test
    fun `play again keeps the session tally and opens with a fresh roll`() {
        val g = game()
        g.debugSetPosition(bgPos(white = mapOf(1 to 1), black = mapOf(20 to 15), whiteOff = 14), 0, listOf(1, 2))
        g.moveChecker(bgIx(1), BackgammonMove.OFF)
        g.playAgain()
        assertEquals(BackgammonPhase.OPENING, g.s().phase)
        assertEquals(BackgammonBoard.initial(), g.s().board)
        assertEquals(2, g.sessionPoints.value["p1"])
    }

    @Test
    fun `play again is ignored while a game is in progress`() {
        val g = game(script = listOf(3, 1))
        g.rollDice()
        g.moveChecker(bgIx(8), bgIx(5))
        val before = g.s()
        g.playAgain()
        assertEquals(before, g.s())
    }

    @Test
    fun `leaving reports the session points with the leader as winner`() {
        val g = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.debugSetPosition(bgPos(white = mapOf(1 to 1), black = mapOf(20 to 15), whiteOff = 14), 0, listOf(1, 2))
        g.moveChecker(bgIx(1), BackgammonMove.OFF)
        g.playAgain()
        g.debugSetPosition(bgPos(white = mapOf(1 to 1), black = mapOf(20 to 14), whiteOff = 14, blackOff = 1), 0, listOf(1, 2))
        g.moveChecker(bgIx(1), BackgammonMove.OFF)
        g.leaveSession()
        assertTrue(g.matchOver.value)
        val scores = result!!.scores.associate { it.playerId to it }
        assertEquals(3, scores.getValue("p1").score)
        assertTrue(scores.getValue("p1").isWinner)
        assertEquals(0, scores.getValue("p2").score)
        assertFalse(scores.getValue("p2").isWinner)
    }

    @Test
    fun `an even session has no winner`() {
        val g = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.debugSetPosition(bgPos(white = mapOf(1 to 1), black = mapOf(20 to 14), whiteOff = 14, blackOff = 1), 0, listOf(1, 2))
        g.moveChecker(bgIx(1), BackgammonMove.OFF)
        g.playAgain()
        g.debugSetPosition(bgPos(black = mapOf(24 to 1), white = mapOf(10 to 14), whiteOff = 1, blackOff = 14), 1, listOf(1, 2))
        g.moveChecker(bgIx(24), BackgammonMove.OFF)
        g.leaveSession()
        assertTrue(result!!.scores.none { it.isWinner })
    }

    @Test
    fun `leaving with no game finished is not a win for anyone and abort records nothing`() {
        val g = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertTrue(result!!.scores.none { it.isWinner })

        val g2 = game()
        var aborted: GameResult? = null
        g2.setOnMatchEnd { aborted = it }
        g2.abortMatch()
        assertTrue(aborted!!.wasAborted)
        assertTrue(aborted!!.scores.isEmpty())
    }

    // --- Resume --------------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before a game, after the session ends or online`() {
        val fresh = BackgammonGame()
        fresh.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, 0, LocalPassAndPlayTransport()))
        assertNull(fresh.snapshot())

        val g = game()
        assertNotNull(g.snapshot())
        g.leaveSession()
        assertNull(g.snapshot())

        val online = game(mode = PlayMode.ONLINE)
        assertNull(online.snapshot())
        assertFalse(online.restore(game().snapshot()!!))
    }

    @Test
    fun `a restored game is the same position and plays on identically`() {
        val a = game(script = listOf(3, 1))
        a.difficulty = CpuDifficulty.HARD
        a.rollDice()
        a.moveChecker(bgIx(8), bgIx(5))
        val snapshot = a.snapshot()!!

        val b = game()
        assertTrue(b.restore(snapshot))
        assertEquals(a.s().board, b.s().board)
        assertEquals(a.s().currentPlayerIndex, b.s().currentPlayerIndex)
        assertEquals(a.s().phase, b.s().phase)
        assertEquals(a.s().dice, b.s().dice)
        assertEquals(a.s().remaining, b.s().remaining)
        assertEquals(a.s().legalMoves, b.s().legalMoves)
        assertEquals(CpuDifficulty.HARD, b.difficulty)

        assertTrue(b.moveChecker(bgIx(6), bgIx(5)))
        assertTrue(b.s().turnComplete)
        // Playing the same move on the original lands in the very same saved state.
        assertTrue(a.moveChecker(bgIx(6), bgIx(5)))
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun `the opening roll state survives a restore`() {
        val a = game(script = listOf(3, 3), first = null)
        a.rollDice()
        val b = game(first = null)
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(BackgammonPhase.OPENING, b.s().phase)
        assertEquals(listOf(3, 3), b.s().openingDice)
        assertEquals(1, b.s().openingTies)
    }

    @Test
    fun `the session tally and a finished game survive a restore`() {
        val a = game()
        a.debugSetPosition(bgPos(white = mapOf(1 to 1), black = mapOf(20 to 15), whiteOff = 14), 0, listOf(1, 2))
        a.moveChecker(bgIx(1), BackgammonMove.OFF)
        val b = game()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(BackgammonPhase.OVER, b.s().phase)
        assertEquals(BackgammonResultKind.GAMMON, b.s().resultKind)
        assertEquals("p1", b.s().winnerPlayerId)
        assertEquals(2, b.sessionPoints.value["p1"])
        assertEquals(1, b.sessionWins.value["p1"])
    }

    @Test
    fun `restoring clears the undo history`() {
        val a = game(script = listOf(3, 1))
        a.rollDice()
        a.moveChecker(bgIx(8), bgIx(5))
        val snapshot = a.snapshot()!!
        assertTrue(a.canUndo)
        assertTrue(a.restore(snapshot))
        assertFalse(a.canUndo)
    }

    @Test
    fun `garbage and tampered snapshots are rejected and leave the game untouched`() {
        val a = game(script = listOf(3, 1))
        a.rollDice()
        a.moveChecker(bgIx(8), bgIx(5))
        val good = a.snapshot()!!

        val b = game()
        val before = b.s()
        val bad = listOf(
            "",
            "garbage",
            "{}",
            "{\"points\":[]}",
            good.replace("\"off\":[0,0]", "\"off\":[3,0]"),
            good.replace("\"remaining\":[1]", "\"remaining\":[6]"),
            good.replace("\"phase\":\"MOVING\"", "\"phase\":\"NOPE\""),
            good.replace("\"playerId\":\"p1\"", "\"playerId\":\"zz\"")
        )
        for (text in bad) {
            if (text != good) assertFalse("accepted: $text", b.restore(text)) else fail("fixture did not change the snapshot")
            assertEquals(before, b.s())
        }
        assertTrue(b.restore(good))
    }

    @Test
    fun `a snapshot from another roster is rejected`() {
        val humans = game(script = listOf(3, 1))
        humans.rollDice()
        val cpu = game(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        assertFalse(cpu.restore(humans.snapshot()!!))
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    // --- Hint ----------------------------------------------------------------------------------

    @Test
    fun `there is no hint before the dice are rolled`() {
        val g = game()
        assertNull(g.hint())
        assertNull(g.hintMove.value)
    }

    @Test
    fun `a hint is a legal move and goes away when a checker moves`() {
        val g = game(script = listOf(3, 1))
        g.rollDice()
        val hint = g.hint()
        assertNotNull(hint)
        assertTrue(hint in g.s().legalMoves)
        assertEquals(hint, g.hintMove.value)
        g.moveChecker(hint!!.from, hint.to)
        assertNull(g.hintMove.value)
    }

    @Test
    fun `the async hint gives a legal move too`() {
        val g = game(script = listOf(6, 4))
        g.rollDice()
        val hint = runBlocking { g.hintAsync() }
        assertTrue(hint in g.s().legalMoves)
    }

    @Test
    fun `there is no hint on the CPU's turn`() {
        val g = game(script = listOf(3, 1), players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT, first = 1)
        g.rollDice()
        assertEquals(BackgammonPhase.MOVING, g.s().phase)
        assertNull(g.hint())
    }

    // --- The CPU -------------------------------------------------------------------------------

    @Test
    fun `the CPU rolls, plays a full legal turn and hands the dice back`() {
        for (difficulty in CpuDifficulty.values()) {
            val g = game(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT, first = 1, difficulty = difficulty)
            assertEquals(BackgammonPhase.TO_ROLL, g.s().phase)
            g.playBotTurn()
            val s = g.s()
            assertEquals("$difficulty hands over", 0, s.currentPlayerIndex)
            assertEquals(BackgammonPhase.TO_ROLL, s.phase)
            assertTrue(s.moveSeq > 0)
            assertEquals(15, s.board.total(0))
            assertEquals(15, s.board.total(1))
            assertFalse(g.canUndo)
        }
    }

    @Test
    fun `playing a bot turn on a human's turn does nothing`() {
        val g = game()
        val before = g.s()
        g.playBotTurn()
        assertEquals(before, g.s())
    }

    @Test
    fun `the async CPU turn paces itself and ends the same way`() {
        val g = game(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT, first = 1, difficulty = CpuDifficulty.MEDIUM)
        var paces = 0
        runBlocking { g.playBotTurnAsync(pace = { paces++ }) }
        assertTrue("paused before the roll, each move and the end", paces >= 3)
        assertEquals(0, g.s().currentPlayerIndex)
        assertEquals(BackgammonPhase.TO_ROLL, g.s().phase)
        assertTrue(g.s().moveSeq > 0)
    }

    @Test
    fun `an async CPU turn is dropped when the session ends while it waits`() {
        val g = game(players = humanAndCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT, first = 1, difficulty = CpuDifficulty.MEDIUM)
        runBlocking { g.playBotTurnAsync(pace = { g.abortMatch() }) }
        assertTrue(g.matchOver.value)
        assertEquals(BackgammonPhase.TO_ROLL, g.s().phase)
        assertEquals(0, g.s().moveSeq)
    }

    @Test
    fun `two CPUs can play a whole game to the end`() {
        val bots = listOf(PlayerInfo("a", "A", isBot = true), PlayerInfo("b", "B", isBot = true))
        val g = game(players = bots, mode = PlayMode.SINGLE_PLAYER_VS_BOT, first = 0, difficulty = CpuDifficulty.EASY)
        var guard = 0
        while (g.s().phase != BackgammonPhase.OVER && guard++ < 5000) g.playBotTurn()
        val s = g.s()
        assertEquals(BackgammonPhase.OVER, s.phase)
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        assertTrue(winnerSeat >= 0)
        assertEquals(15, s.board.off(winnerSeat))
        assertTrue(s.pointsScored in 1..3)
        assertEquals(s.pointsScored, g.sessionPoints.value[s.winnerPlayerId!!])
        assertNotEquals(15, s.board.off(1 - winnerSeat))
    }
}
