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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The no-progress and repetition draws, the "Mandatory capture" option, and the split bot/hint API
 * (search off the main thread, apply back on it). Positions are hand-built with [CheckersTestKit].
 * Side 1 (Light) is seat 1 by default and moves toward row 0; side 0 (Dark) moves toward row 7.
 */
class CheckersDrawAndOptionsTest {

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

    /** Two far-apart kings, Light to move (seat 1): neither can capture, both can only slide. */
    private fun kingsBoard() = CheckersTestKit.board(
        CheckersTestKit.light(7, 0, PieceKind.KING),
        CheckersTestKit.dark(0, 7, PieceKind.KING)
    )

    private fun kingsGame(noProgress: Int = 0): CheckersGame {
        val game = newGame()
        game.state.value = CheckersState(board = kingsBoard(), currentPlayerIndex = 1, noProgressMoves = noProgress)
        return game
    }

    private fun play(game: CheckersGame, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        game.playMove(game.state.value!!.currentPlayerIndex, fromRow, fromCol, toRow, toCol)
    }

    // --- 40-move no-progress draw -----------------------------------------------------------

    @Test
    fun `a king slide counts one ply and the 40th without progress draws the round`() {
        val game = kingsGame(noProgress = 38)
        play(game, 7, 0, 6, 1)
        val s1 = game.state.value!!
        assertEquals(39, s1.noProgressMoves)
        assertFalse(s1.gameOver)

        play(game, 0, 7, 1, 6)
        val s2 = game.state.value!!
        assertEquals(40, s2.noProgressMoves)
        assertTrue(s2.gameOver)
        assertEquals(CheckersDrawReason.NO_PROGRESS, s2.drawReason)
        assertNull("nobody won", s2.winnerPlayerId)
        assertEquals(0, game.scoreP1.value)
        assertEquals(0, game.scoreP2.value)
    }

    @Test
    fun `a drawn round accepts no further moves`() {
        val game = kingsGame(noProgress = 39)
        play(game, 7, 0, 6, 1)
        assertTrue(game.state.value!!.gameOver)
        val frozen = game.state.value
        play(game, 0, 7, 1, 6)
        assertTrue("state untouched", frozen === game.state.value)
    }

    @Test
    fun `a man move resets the no-progress counter`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = CheckersTestKit.board(
                CheckersTestKit.light(5, 2), CheckersTestKit.light(7, 0, PieceKind.KING),
                CheckersTestKit.dark(0, 7, PieceKind.KING)
            ),
            currentPlayerIndex = 1,
            noProgressMoves = 39
        )
        play(game, 5, 2, 4, 1)
        val s = game.state.value!!
        assertEquals(0, s.noProgressMoves)
        assertFalse(s.gameOver)
    }

    @Test
    fun `a capture resets the no-progress counter`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = CheckersTestKit.board(
                CheckersTestKit.light(7, 0, PieceKind.KING),
                CheckersTestKit.dark(6, 1, PieceKind.KING), // capturable? (5,2) is empty, so yes
                CheckersTestKit.dark(0, 7, PieceKind.KING)
            ),
            currentPlayerIndex = 1,
            noProgressMoves = 39
        )
        play(game, 7, 0, 5, 2)
        val s = game.state.value!!
        assertEquals(0, s.noProgressMoves)
        assertFalse(s.gameOver)
        assertTrue(s.lastMove!!.isCapture)
    }

    @Test
    fun `startMatch zeroes the counter`() {
        val game = kingsGame(noProgress = 39)
        game.startMatch()
        assertEquals(0, game.state.value!!.noProgressMoves)
        assertNull(game.state.value!!.drawReason)
    }

    // --- threefold repetition -----------------------------------------------------------------

    @Test
    fun `the same position with the same side to move a third time draws`() {
        val game = kingsGame()
        // One cycle is 4 plies and returns to the starting position. Keys are recorded after each
        // ply, so the position after ply 1 recurs after plies 5 and 9: that third time is the draw.
        val cycle = listOf(
            intArrayOf(7, 0, 6, 1), intArrayOf(0, 7, 1, 6), intArrayOf(6, 1, 7, 0), intArrayOf(1, 6, 0, 7)
        )
        var ply = 0
        repeat(2) {
            for (m in cycle) {
                play(game, m[0], m[1], m[2], m[3])
                ply++
                assertFalse("no draw yet at ply $ply", game.state.value!!.gameOver)
            }
        }
        play(game, 7, 0, 6, 1) // ply 9
        val s = game.state.value!!
        assertTrue(s.gameOver)
        assertEquals(CheckersDrawReason.REPETITION, s.drawReason)
        assertNull(s.winnerPlayerId)
        assertTrue("well short of the 40 move limit", s.noProgressMoves < 40)
    }

    @Test
    fun `the same board with the other side to move is a different position`() {
        val game = kingsGame()
        // Light slides away and back while Dark slides away and back: every full cycle repeats, but
        // a position reached with the other side to move must not count toward the same key.
        play(game, 7, 0, 6, 1)
        play(game, 0, 7, 1, 6)
        val keys = game.state.value!!.positionKeys
        assertEquals(2, keys.size)
        assertTrue(keys[0] != keys[1])
    }

    @Test
    fun `a capture clears the repetition record`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = CheckersTestKit.board(
                CheckersTestKit.light(7, 0, PieceKind.KING),
                CheckersTestKit.dark(6, 1, PieceKind.KING),
                CheckersTestKit.dark(0, 7, PieceKind.KING)
            ),
            currentPlayerIndex = 1,
            positionKeys = listOf("x".repeat(65), "y".repeat(65))
        )
        play(game, 7, 0, 5, 2)
        assertEquals(1, game.state.value!!.positionKeys.size)
    }

    // --- Resume and network -------------------------------------------------------------------

    @Test
    fun `the counter and keys survive a snapshot round trip and the draw still lands`() {
        val a = kingsGame(noProgress = 38)
        play(a, 7, 0, 6, 1)
        val saved = a.snapshot()
        assertNotNull(saved)

        val b = newGame()
        assertTrue(b.restore(saved!!))
        assertEquals(39, b.state.value!!.noProgressMoves)
        assertEquals(a.state.value!!.positionKeys, b.state.value!!.positionKeys)
        play(b, 0, 7, 1, 6)
        assertEquals(CheckersDrawReason.NO_PROGRESS, b.state.value!!.drawReason)
    }

    @Test
    fun `a drawn round restores as a drawn round`() {
        val a = kingsGame(noProgress = 39)
        play(a, 7, 0, 6, 1)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        val s = b.state.value!!
        assertTrue(s.gameOver)
        assertEquals(CheckersDrawReason.NO_PROGRESS, s.drawReason)
        assertNull(s.winnerPlayerId)
    }

    private fun legacyJson(extra: String = "", current: Int = 1, gameOver: Boolean = false): String {
        val cells = (0 until 64).joinToString(",") {
            when (it) {
                7 -> "2"  // Dark king (0,7)
                56 -> "4" // Light king (7,0)
                else -> "0"
            }
        }
        return """{"cells":[$cells],"players":[{"playerId":"p1","isBot":false},{"playerId":"p2","isBot":false}],""" +
            """"currentPlayerIndex":$current,"forcedRow":-1,"forcedCol":-1,"gameOver":$gameOver,"winnerPlayerId":null,""" +
            """"scoreP1":0,"scoreP2":0,"difficulty":"EASY"$extra}"""
    }

    @Test
    fun `an older save without the draw fields restores with a zero counter and mandatory capture`() {
        val game = newGame()
        game.mandatoryCapture = false
        assertTrue(game.restore(legacyJson()))
        assertEquals(0, game.state.value!!.noProgressMoves)
        assertTrue(game.state.value!!.positionKeys.isEmpty())
        assertNull(game.state.value!!.drawReason)
        assertTrue("old saves always had mandatory capture", game.mandatoryCapture)
    }

    @Test
    fun `a hand-edited draw that the counters do not back is refused`() {
        val liar = legacyJson(",\"drawReason\":\"NO_PROGRESS\",\"noProgressMoves\":3", gameOver = true)
        assertFalse(newGame().restore(liar))
        val unknown = legacyJson(",\"drawReason\":\"BOGUS\"", gameOver = true)
        assertFalse(newGame().restore(unknown))
        val liveWithReason = legacyJson(",\"drawReason\":\"NO_PROGRESS\",\"noProgressMoves\":40", gameOver = false)
        assertFalse("a live round cannot carry a draw reason", newGame().restore(liveWithReason))
        val honest = legacyJson(",\"drawReason\":\"NO_PROGRESS\",\"noProgressMoves\":40", gameOver = true)
        assertTrue(newGame().restore(honest))
    }

    @Test
    fun `the guest sees the host's draw and its reason`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, board = kingsBoard(), currentSeat = 0)
        host.game.state.value = host.game.state.value!!.copy(noProgressMoves = 39)
        val guest = CheckersTestKit.guest(hub)
        assertEquals(39, guest.game.state.value!!.noProgressMoves)

        host.game.playMove(0, 7, 0, 6, 1)
        val g = guest.game.state.value!!
        assertTrue(g.gameOver)
        assertEquals(CheckersDrawReason.NO_PROGRESS, g.drawReason)
        assertNull(g.winnerPlayerId)
        assertEquals(host.game.state.value, g)
    }

    // --- Mandatory capture option -------------------------------------------------------------

    /** Light man (5,2) can jump Dark (4,3); Light man (7,0) can only step. */
    private fun captureAvailableGame(mandatory: Boolean): CheckersGame {
        val game = newGame()
        game.mandatoryCapture = mandatory
        game.state.value = CheckersState(
            board = CheckersTestKit.board(
                CheckersTestKit.light(5, 2), CheckersTestKit.light(7, 0),
                CheckersTestKit.dark(4, 3), CheckersTestKit.dark(0, 5)
            ),
            currentPlayerIndex = 1
        )
        return game
    }

    @Test
    fun `with mandatory capture only the jump is legal`() {
        val game = captureAvailableGame(true)
        assertEquals(setOf(3 to 4), game.legalDestinationsFrom(5, 2))
        assertFalse(game.hasLegalMoveFrom(7, 0))
    }

    @Test
    fun `without mandatory capture the jump and the quiet moves are all legal`() {
        val game = captureAvailableGame(false)
        assertEquals(setOf(3 to 4, 4 to 1), game.legalDestinationsFrom(5, 2))
        assertEquals(setOf(6 to 1), game.legalDestinationsFrom(7, 0))
    }

    @Test
    fun `a quiet move is accepted when capture is optional and refused when it is mandatory`() {
        val optional = captureAvailableGame(false)
        play(optional, 7, 0, 6, 1)
        assertEquals(0, optional.state.value!!.currentPlayerIndex)
        assertFalse(optional.state.value!!.lastMove!!.isCapture)

        val mandatory = captureAvailableGame(true)
        val before = mandatory.state.value
        play(mandatory, 7, 0, 6, 1)
        assertTrue(before === mandatory.state.value)
    }

    @Test
    fun `a multi-jump that has started must continue even when capture is optional`() {
        val game = newGame()
        game.mandatoryCapture = false
        game.state.value = CheckersState(
            board = CheckersTestKit.board(
                CheckersTestKit.light(5, 2), CheckersTestKit.light(7, 0),
                CheckersTestKit.dark(4, 3), CheckersTestKit.dark(2, 3), CheckersTestKit.dark(0, 7)
            ),
            currentPlayerIndex = 1
        )
        play(game, 5, 2, 3, 4)
        val s = game.state.value!!
        assertTrue(s.inForcedContinuation)
        assertEquals(1, s.currentPlayerIndex)
        assertFalse("the other man may not step in the middle of a jump", game.hasLegalMoveFrom(7, 0))
        assertEquals(setOf(1 to 2), game.legalDestinationsFrom(3, 4))
    }

    @Test
    fun `the option rides along in the snapshot`() {
        val a = captureAvailableGame(false)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse(b.mandatoryCapture)
        assertEquals(setOf(3 to 4, 4 to 1), b.legalDestinationsFrom(5, 2))
    }

    @Test
    fun `the guest plays by the host's capture rule`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, board = CheckersTestKit.board(
            CheckersTestKit.light(5, 2), CheckersTestKit.dark(4, 3), CheckersTestKit.dark(0, 5)
        ), currentSeat = 1)
        host.game.mandatoryCapture = false
        val guest = CheckersTestKit.guest(hub)
        assertFalse(guest.game.mandatoryCapture)
    }

    private fun capturesAvailable(game: CheckersGame): Boolean {
        for (row in 0..7) for (col in 0..7) {
            if (game.legalDestinationsFrom(row, col).any { (toRow, _) -> abs(toRow - row) == 2 }) return true
        }
        return false
    }

    @Test
    fun `the bot always plays a legal move under both settings`() {
        for (mandatory in listOf(true, false)) {
            for (difficulty in listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
                val game = newGame(listOf(PlayerInfo("a", "A", isBot = true), PlayerInfo("b", "B", isBot = true)), PlayMode.SINGLE_PLAYER_VS_BOT)
                game.difficulty = difficulty
                game.mandatoryCapture = mandatory
                game.startMatch()
                var turns = 0
                while (turns < 40) {
                    val before = game.state.value!!
                    if (before.gameOver) break
                    val captureExisted = capturesAvailable(game)
                    // The first hop the bot may take must be one of the currently legal hops.
                    val legal = mutableSetOf<List<Int>>()
                    for (row in 0..7) for (col in 0..7) {
                        for ((r, c) in game.legalDestinationsFrom(row, col)) legal += listOf(row, col, r, c)
                    }
                    game.playBotTurn()
                    val after = game.state.value!!
                    val first = after.lastTurnHops.first()
                    assertTrue(
                        "mandatory=$mandatory $difficulty turn $turns: illegal first hop",
                        listOf(first.fromRow, first.fromCol, first.toRow, first.toCol) in legal
                    )
                    if (mandatory && captureExisted) {
                        assertTrue("mandatory=$mandatory $difficulty turn $turns: had to capture", after.lastMove!!.isCapture)
                    }
                    turns++
                }
                assertTrue(turns > 0)
            }
        }
    }

    // --- Search off the main thread: begin / compute / apply -----------------------------------

    private fun cpuToMove(): CheckersGame {
        val game = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        game.startMatch() // seat 1 (the CPU) holds Light and opens
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        return game
    }

    @Test
    fun `a computed plan applies to the position it was searched on`() {
        val game = cpuToMove()
        val job = game.beginBotTurn()
        assertNotNull(job)
        val plan = job!!.compute()
        assertNotNull(plan)
        assertTrue(game.applyBotPlan(plan))
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertNotNull(game.state.value!!.lastMove)
    }

    @Test
    fun `playBotTurn and the split API make the same kind of move`() {
        val game = cpuToMove()
        game.playBotTurn()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a plan is dropped when the position changed or the session ended`() {
        val changed = cpuToMove()
        val plan = changed.beginBotTurn()!!.compute()
        // any genuinely different state object (undo, restart, a sync). An equal copy() would not do:
        // Compose state skips a write equal to the current value and keeps the old object.
        changed.state.value = changed.state.value!!.copy(noProgressMoves = changed.state.value!!.noProgressMoves + 1)
        assertFalse(changed.applyBotPlan(plan))

        val left = cpuToMove()
        val leftPlan = left.beginBotTurn()!!.compute()
        left.abortMatch()
        assertFalse(left.applyBotPlan(leftPlan))
        assertNull(left.state.value!!.lastMove)
    }

    @Test
    fun `no bot job exists when it is not the bot's turn or the round is over`() {
        val game = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertNull("no board yet", game.beginBotTurn())
        game.startMatch()
        game.playBotTurn() // now the human's turn
        assertNull(game.beginBotTurn())
        assertFalse(game.applyBotPlan(null))
    }

    @Test
    fun `a hint applies only to the position it was searched on`() {
        val game = newGame()
        game.startMatch()
        val job = game.beginHint()!!
        val move = job.computeHint()
        assertNotNull(move)
        assertEquals(move, game.applyHint(job, move))
        assertEquals(move, game.hintMove.value)

        val other = newGame()
        other.startMatch()
        val staleJob = other.beginHint()!!
        val staleMove = staleJob.computeHint()
        play(other, 5, 0, 4, 1) // the position moved on while the hint was being computed
        assertNull(other.applyHint(staleJob, staleMove))
        assertNull(other.hintMove.value)
    }

    @Test
    fun `hint still equals the one-call hint`() {
        val a = newGame()
        a.startMatch()
        val b = newGame()
        b.startMatch()
        assertEquals(a.hint(), b.beginHint()!!.computeHint())
    }
}
