package com.gamesuite.games.morris

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MorrisGameTest {

    private val humans = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("p2", "Bob"))
    private val vsBot = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("bot1", "CPU", isBot = true))

    private class Harness(val game: NineMensMorrisGame, val results: MutableList<GameResult>)

    private fun harness(
        players: List<PlayerInfo> = humans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        men: Int = 9,
        flying: Boolean = true,
        first: Int = 0,
        level: CpuDifficulty = CpuDifficulty.EASY
    ): Harness {
        val game = NineMensMorrisGame()
        game.men = men
        game.flying = flying
        game.firstOpenerIndex = first
        game.difficulty = level
        game.init(GameContext(mode, players, 0, MorrisFakeTransport()))
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()
        return Harness(game, results)
    }

    private fun NineMensMorrisGame.s() = state.value!!

    /** Plays the first legal move whose destination is [to] (and the first removal, if any). */
    private fun NineMensMorrisGame.place(to: Int, remove: Int? = null) {
        val m = s().legalMoves.first { it.to == to && (remove == null || it.remove == remove) }
        play(m)
    }

    private fun randomMove(g: NineMensMorrisGame, rng: Random) {
        val moves = g.s().legalMoves
        g.play(moves[rng.nextInt(moves.size)])
    }

    // ---- start and rules plumbing ----

    @Test
    fun `a new board is empty with a full hand and the first opener to move`() {
        val h = harness(first = 1)
        val s = h.game.s()
        assertTrue(s.points.all { it == null })
        assertEquals(listOf(9, 9), s.hand)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(MorrisPhase.PLACING, s.phase)
        assertEquals(24, s.legalMoves.size)
        assertFalse(s.boardOver)
    }

    @Test
    fun `six men and no flying carry through`() {
        val h = harness(men = 6, flying = false)
        assertEquals(listOf(6, 6), h.game.s().hand)
        assertFalse(h.game.s().flying)
        assertEquals(6, h.game.s().men)
    }

    @Test
    fun `illegal plays are ignored`() {
        val h = harness()
        val g = h.game
        g.play(MorrisMove(-1, 24)) // off the board
        g.play(MorrisMove(-1, -1))
        g.play(MorrisMove(0, 1)) // sliding while men remain to place
        g.play(MorrisMove(-1, 3, 5)) // a removal that closes no mill
        assertEquals(0, g.s().ply)
        g.place(3)
        g.play(MorrisMove(-1, 3)) // occupied
        assertEquals(1, g.s().ply)
    }

    @Test
    fun `closing a mill takes a man and the turn passes`() {
        val h = harness()
        val g = h.game
        g.place(0); g.place(16); g.place(1); g.place(18)
        assertTrue(g.s().removals(-1, 2).toSet() == setOf(16, 18))
        g.place(2, remove = 16)
        val s = g.s()
        assertNull(s.points[16])
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(listOf(6, 7), s.hand)
        assertEquals(listOf(3, 1), s.onBoard)
        assertEquals(MorrisMove(-1, 2, 16), s.lastMove)
    }

    @Test
    fun `whyIllegal explains each kind of useless tap`() {
        val h = harness()
        val g = h.game
        g.place(0)
        // Player 2 to place: taking an occupied point is explained.
        assertEquals(MorrisWhy.POINT_TAKEN, g.s().whyIllegal(0, -1, mine = true, botTurn = false))
        assertNull(g.s().whyIllegal(5, -1, mine = true, botTurn = false))
        assertEquals(MorrisWhy.NOT_YOUR_TURN, g.s().whyIllegal(5, -1, mine = false, botTurn = false))
        assertEquals(MorrisWhy.CPU_THINKING, g.s().whyIllegal(5, -1, mine = false, botTurn = true))

        // Movement phase checks, on a hand-built resumed position.
        val moving = harness(men = 6)
        val rng = Random(3)
        while (moving.game.s().phase == MorrisPhase.PLACING && !moving.game.s().boardOver) randomMove(moving.game, rng)
        val s = moving.game.s()
        if (!s.boardOver && s.phase == MorrisPhase.MOVING) {
            val me = s.currentPlayerIndex
            val enemy = s.points.indexOfFirst { it == 1 - me }
            assertEquals(MorrisWhy.ENEMY_MAN, s.whyIllegal(enemy, -1, true, false))
            val movable = s.movablePoints().first()
            assertNull(s.whyIllegal(movable, -1, true, false))
            val far = s.points.indices.first { s.points[it] == null && it !in s.destinations(movable) }
            assertEquals(MorrisWhy.TOO_FAR, s.whyIllegal(far, movable, true, false))
            val empty = s.points.indices.first { s.points[it] == null }
            assertEquals(MorrisWhy.PICK_OWN_MAN, s.whyIllegal(empty, -1, true, false))
        }
    }

    @Test
    fun `whyNotRemovable names protected and own men`() {
        val h = harness()
        val g = h.game
        g.place(0); g.place(16); g.place(1); g.place(17); g.place(5); g.place(18, remove = 5) // light closes 16-17-18 and takes dark's 5
        // Dark to move with 0,1,5 placed: dark closes 0-1-2; light's men 16,17,18 form a mill so are protected (none free).
        val s = g.s()
        assertEquals(setOf(16, 17, 18), s.removals(-1, 2).toSet())
        assertEquals(MorrisWhy.REMOVE_OWN_MAN, s.whyNotRemovable(0, -1, 2))
        assertEquals(MorrisWhy.NOTHING_THERE, s.whyNotRemovable(10, -1, 2))
        assertNull(s.whyNotRemovable(16, -1, 2))
    }

    // ---- snapshot / restore ----

    @Test
    fun `snapshot and restore round-trip a mid-game position`() {
        val a = harness()
        val rng = Random(11)
        repeat(25) { if (!a.game.s().boardOver) randomMove(a.game, rng) }
        val snap = a.game.snapshot()
        assertNotNull(snap)
        val b = NineMensMorrisGame()
        b.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans, 0, MorrisFakeTransport()))
        assertTrue(b.restore(snap!!))
        assertEquals(a.game.s(), b.s())
        assertEquals(snap, b.snapshot())
    }

    @Test
    fun `restore rejects corrupt, foreign and inconsistent snapshots and leaves the game alone`() {
        val a = harness()
        a.game.place(4)
        val snap = a.game.snapshot()!!
        val b = harness()
        val before = b.game.s()
        assertFalse(b.game.restore("not json"))
        assertFalse(b.game.restore("{}"))
        assertFalse(b.game.restore(snap.replace("\"men\":9", "\"men\":7")))
        assertFalse(b.game.restore(snap.replace("\"hand\":[8,9]", "\"hand\":[9,9]")))
        assertFalse(b.game.restore(snap.replace("\"hand\":[8,9]", "\"hand\":[0,9]")))
        assertFalse(b.game.restore(snap.replace("\"p2\"", "\"zz\"")))
        assertFalse(b.game.restore(snap.replace("\"currentPlayerIndex\":1", "\"currentPlayerIndex\":5")))
        assertEquals(before, b.game.s())
        // A roster with a CPU in a seat the save had as a human is refused too.
        val c = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        assertFalse(c.game.restore(snap))
    }

    @Test
    fun `nothing to save before start, after the session ends, or when networked`() {
        val fresh = NineMensMorrisGame()
        fresh.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans, 0, MorrisFakeTransport()))
        assertNull(fresh.snapshot())
        val h = harness()
        assertNotNull(h.game.snapshot())
        h.game.leaveSession()
        assertNull(h.game.snapshot())
        val net = harness(mode = PlayMode.LOCAL_AD_HOC)
        assertNull(net.game.snapshot())
    }

    @Test
    fun `restore keeps the session tally and difficulty`() {
        val a = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT, level = CpuDifficulty.HARD)
        a.game.sessionWins.value = mapOf("p1" to 2, "bot1" to 1)
        a.game.sessionDraws.value = 3
        a.game.place(7)
        val snap = a.game.snapshot()!!
        val b = NineMensMorrisGame()
        b.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, vsBot, 0, MorrisFakeTransport()))
        assertTrue(b.restore(snap))
        assertEquals(mapOf("p1" to 2, "bot1" to 1), b.sessionWins.value)
        assertEquals(3, b.sessionDraws.value)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
    }

    // ---- undo ----

    @Test
    fun `undo restores the exact previous state, also across the cpu reply`() {
        val h = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        val g = h.game
        assertFalse(g.canUndo)
        val start = g.s()
        g.place(10)
        g.playBotTurn(Random(4))
        assertEquals(2, g.s().ply)
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(start, g.s())
        assertFalse(g.canUndo)
    }

    @Test
    fun `undo in pass and play steps back one move at a time, even a mill`() {
        val h = harness()
        val g = h.game
        g.place(0); g.place(16); g.place(1); g.place(18)
        val beforeMill = g.s()
        g.place(2, remove = 16)
        g.undo()
        assertEquals(beforeMill, g.s())
        g.undo()
        assertEquals(3, g.s().ply)
    }

    @Test
    fun `undo is refused once the session is over`() {
        val h = harness()
        h.game.place(0)
        h.game.leaveSession()
        h.game.undo()
        assertEquals(1, h.game.s().ply)
    }

    // ---- hint ----

    @Test
    fun `hint returns a legal turn, shows it and clears on the next move`() {
        val h = harness()
        val g = h.game
        val hint = g.hint()
        assertNotNull(hint)
        assertTrue(hint in g.s().legalMoves)
        assertEquals(hint, g.hintMove.value)
        g.place(3)
        assertNull(g.hintMove.value)
    }

    @Test
    fun `hint is off during the cpu turn and after the board ends`() {
        val h = harness(players = listOf(PlayerInfo("bot1", "CPU", isBot = true), PlayerInfo("p1", "Ann")), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        assertNull(h.game.hint())
        assertNull(runBlocking { h.game.requestHint() })
    }

    @Test
    fun `requestHint runs off the main thread and applies when nothing changed`() {
        val h = harness()
        val hint = runBlocking { h.game.requestHint() }
        assertNotNull(hint)
        assertEquals(hint, h.game.hintMove.value)
    }

    // ---- cpu ----

    @Test
    fun `the cpu plays legal moves at every level and only on its own turn`() {
        for (level in CpuDifficulty.values()) {
            val h = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT, level = level, men = 6)
            val g = h.game
            val rng = Random(level.ordinal + 1)
            var guard = 0
            while (!g.s().boardOver && guard++ < 8) {
                val before = g.s()
                if (before.players[before.currentPlayerIndex].isBot) {
                    g.playBotTurn(rng)
                    assertEquals(before.ply + 1, g.s().ply)
                } else {
                    g.playBotTurn(rng) // not the cpu's turn: a no-op
                    assertEquals(before, g.s())
                    randomMove(g, rng)
                }
            }
        }
    }

    @Test
    fun `playBotTurnAsync plays once and a stale answer is dropped`() {
        val h = harness(players = listOf(PlayerInfo("bot1", "CPU", isBot = true), PlayerInfo("p1", "Ann")), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        runBlocking { h.game.playBotTurnAsync() }
        assertEquals(1, h.game.s().ply)
        runBlocking { h.game.playBotTurnAsync() } // human's turn now
        assertEquals(1, h.game.s().ply)
    }

    // ---- session ----

    @Test
    fun `a finished board records the winner, offers a rematch with the other opener, and the session scores on leave`() {
        val h = harness()
        val g = h.game
        val rng = Random(9)
        var guard = 0
        while (!g.s().boardOver && guard++ < 5000) randomMove(g, rng)
        assertTrue(g.s().boardOver)
        val s = g.s()
        if (s.winnerPlayerId != null) {
            assertEquals(1, g.sessionWins.value[s.winnerPlayerId])
            assertTrue(s.endReason == "reduced" || s.endReason == "blocked")
        } else {
            assertEquals("drawn", s.endReason)
            assertEquals(1, g.sessionDraws.value)
        }
        g.play(MorrisMove(-1, 0)) // frozen
        assertEquals(s, g.s())
        g.playAgain()
        assertEquals(1, g.s().currentPlayerIndex) // opener alternates
        assertEquals(0, g.s().ply)
        g.leaveSession()
        assertEquals(1, h.results.size)
        assertTrue(g.matchOver.value)
    }

    @Test
    fun `leaving a tied session names no winner`() {
        val h = harness()
        h.game.sessionWins.value = mapOf("p1" to 1, "p2" to 1)
        h.game.leaveSession()
        assertTrue(h.results[0].scores.none { it.isWinner })
    }

    @Test
    fun `abortMatch reports an aborted result`() {
        val h = harness()
        h.game.abortMatch()
        assertTrue(h.results[0].wasAborted)
    }

    // ---- fuzz ----

    @Test
    fun `fuzz - snapshots round-trip and undo restores at every ply`() {
        val rng = Random(777)
        repeat(12) { gameNo ->
            val h = harness(men = if (gameNo % 2 == 0) 9 else 6, flying = gameNo % 3 != 0)
            val g = h.game
            var guard = 0
            while (!g.s().boardOver && guard++ < 4000) {
                val before = g.s()
                randomMove(g, rng)
                val after = g.s()
                assertTrue(after.ply == before.ply + 1)
                assertTrue(after.onBoard[0] + after.hand[0] <= after.men)
                assertTrue(after.onBoard[1] + after.hand[1] <= after.men)
                if (!after.boardOver) {
                    val copy = NineMensMorrisGame()
                    copy.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans, 0, MorrisFakeTransport()))
                    assertTrue(copy.restore(g.snapshot()!!))
                    assertEquals(after, copy.s())
                }
                if (guard % 7 == 0) { // undo then redo the same move
                    val move = after.lastMove!!
                    g.undo()
                    assertEquals(before, g.s())
                    g.play(move)
                    assertEquals(after.copy(lastAction = after.lastAction), g.s())
                }
            }
            assertTrue(g.s().boardOver)
        }
    }
}
