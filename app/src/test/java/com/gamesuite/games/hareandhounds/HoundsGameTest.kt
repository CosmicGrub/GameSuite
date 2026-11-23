package com.gamesuite.games.hareandhounds

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.morris.MorrisFakeTransport
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HoundsGameTest {
    private val humans = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("p2", "Bob"))
    private val vsBot = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("bot1", "CPU", isBot = true))

    private class Harness(val game: HareAndHoundsGame, val results: MutableList<GameResult>)

    private fun harness(
        players: List<PlayerInfo> = humans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        side: String = HoundsSides.HOUNDS,
        level: CpuDifficulty = CpuDifficulty.EASY
    ): Harness {
        val g = HareAndHoundsGame()
        g.sidePreference = side
        g.difficulty = level
        g.init(GameContext(mode, players, 0, MorrisFakeTransport()))
        val results = mutableListOf<GameResult>()
        g.setOnMatchEnd { results += it }
        g.startMatch()
        return Harness(g, results)
    }

    private fun HareAndHoundsGame.s() = state.value!!

    private fun randomMove(g: HareAndHoundsGame, rng: Random) {
        val moves = g.s().legalMoves
        g.play(moves[rng.nextInt(moves.size)])
    }

    // ---- roles ----

    @Test
    fun `seat 0 plays the hounds by default and the hounds move first`() {
        val s = harness().game.s()
        assertEquals(0, s.houndsSeat)
        assertEquals(1, s.hareSeat)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(HoundsRole.HOUNDS, s.currentRole)
        assertEquals(listOf(0, 1, 3), s.cells.indices.filter { s.cells[it] == 1 })
        assertEquals(10, s.hareAt)
        assertEquals(HoundsBoard.MAX_HOUND_MOVES, s.houndsRemaining)
    }

    @Test
    fun `choosing the hare gives the person the hare and the cpu the hounds, who open`() {
        val h = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT, side = HoundsSides.HARE)
        val s = h.game.s()
        assertEquals(1, s.houndsSeat)
        assertEquals(1, s.currentPlayerIndex) // the cpu's hounds move first
        assertTrue(s.players[s.currentPlayerIndex].isBot)
        h.game.playBotTurn(Random(1))
        assertEquals(0, h.game.s().currentPlayerIndex) // the person's hare answers
    }

    @Test
    fun `random side picks one of the two and honours the injected random`() {
        val sides = (0 until 10).map {
            val g = HareAndHoundsGame()
            g.sidePreference = HoundsSides.RANDOM
            g.random = Random(it)
            g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, vsBot, 0, MorrisFakeTransport()))
            g.startMatch()
            g.s().houndsSeat
        }.toSet()
        assertEquals(setOf(0, 1), sides)
    }

    @Test
    fun `pass and play swaps roles each board but a cpu game keeps the person's side`() {
        val pp = harness()
        pp.game.state.value = pp.game.s().copy(boardOver = true, winnerPlayerId = "p1", endReason = "trapped")
        pp.game.playAgain()
        assertEquals(1, pp.game.s().houndsSeat)
        assertEquals(1, pp.game.s().currentPlayerIndex)

        val cpu = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        cpu.game.state.value = cpu.game.s().copy(boardOver = true, winnerPlayerId = "p1", endReason = "trapped")
        cpu.game.playAgain()
        assertEquals(0, cpu.game.s().houndsSeat)
    }

    // ---- play ----

    @Test
    fun `only legal steps are accepted`() {
        val g = harness().game
        g.play(HoundsMove(0, 2)) // legal
        assertEquals(1, g.s().ply)
        g.play(HoundsMove(5, 4)) // not even a piece
        g.play(HoundsMove(10, 7)) // hare's step is legal now
        assertEquals(2, g.s().ply)
        g.play(HoundsMove(2, 0)) // a hound stepping back
        g.play(HoundsMove(1, 99))
        assertEquals(2, g.s().ply)
    }

    @Test
    fun `a full game with best play ends with the hounds winning and the tally updated`() {
        val h = harness()
        val g = h.game
        var guard = 0
        while (!g.s().boardOver && guard++ < 100) {
            g.play(HoundsEngine.bestMove(g.toPosition(g.s()))!!)
        }
        val s = g.s()
        assertTrue(s.boardOver)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals("trapped", s.endReason)
        assertEquals(1, g.sessionWins.value["p1"])
        assertTrue(s.legalMoves.isEmpty())
        g.play(HoundsMove(0, 2)) // frozen
        assertEquals(s, g.s())
        g.leaveSession()
        assertTrue(h.results[0].scores.first { it.playerId == "p1" }.isWinner)
    }

    @Test
    fun `whyIllegal names the rule behind each rejected tap`() {
        val g = harness().game
        val s = g.s()
        assertNull(s.whyIllegal(1, -1, true, false)) // a hound that can move
        assertEquals(HoundsWhy.NOT_YOUR_PIECE, s.whyIllegal(10, -1, true, false)) // the hare is not mine
        assertEquals(HoundsWhy.PICK_PIECE_FIRST, s.whyIllegal(5, -1, true, false))
        assertEquals(HoundsWhy.NOT_YOUR_TURN, s.whyIllegal(1, -1, false, false))
        assertEquals(HoundsWhy.CPU_THINKING, s.whyIllegal(1, -1, false, true))
        assertEquals(HoundsWhy.TOO_FAR, s.whyIllegal(8, 1, true, false))
        assertNull(s.whyIllegal(4, 1, true, false))
        // Hound on 5 trying to go back to 2.
        val back = s.copy(cells = List(11) { when (it) { 5 -> 1; 4 -> 1; 6 -> 1; 10 -> 2; else -> 0 } }, legalMoves = emptyList())
        assertEquals(HoundsWhy.NO_BACKWARDS, back.whyIllegal(2, 5, true, false))
        val blockedCells = List(11) { when (it) { 10 -> 2; 7 -> 1; 8 -> 1; 9 -> 1; else -> 0 } }
        val blocked = s.copy(cells = blockedCells, legalMoves = emptyList())
        assertEquals(HoundsWhy.BLOCKED_PIECE, blocked.whyIllegal(9, -1, true, false))
        assertEquals(HoundsWhy.GAME_OVER, s.copy(boardOver = true).whyIllegal(1, -1, true, false))
    }

    // ---- resume ----

    @Test
    fun `snapshot and restore round-trip`() {
        val a = harness()
        val rng = Random(2)
        repeat(9) { if (!a.game.s().boardOver) randomMove(a.game, rng) }
        val snap = a.game.snapshot()
        assertNotNull(snap)
        val b = HareAndHoundsGame()
        b.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans, 0, MorrisFakeTransport()))
        assertTrue(b.restore(snap!!))
        assertEquals(a.game.s(), b.s())
        assertEquals(snap, b.snapshot())
    }

    @Test
    fun `restore refuses bad snapshots without touching the game`() {
        val a = harness()
        a.game.play(HoundsMove(1, 4))
        val snap = a.game.snapshot()!!
        val b = harness()
        val before = b.game.s()
        assertFalse(b.game.restore("garbage"))
        assertFalse(b.game.restore("{}"))
        assertFalse(b.game.restore(snap.replace("\"p2\"", "\"zz\"")))
        assertFalse(b.game.restore(snap.replace("\"houndMoves\":1", "\"houndMoves\":77")))
        assertFalse(b.game.restore(snap.replace("\"houndsSeat\":0", "\"houndsSeat\":4")))
        assertFalse(b.game.restore(snap.replace("[1,0,0,1,1,0,0,0,0,0,2]", "[1,1,1,1,1,0,0,0,0,0,2]")))
        assertEquals(before, b.game.s())
    }

    @Test
    fun `nothing is saved for a networked game, a finished session or before start`() {
        val fresh = HareAndHoundsGame()
        fresh.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans, 0, MorrisFakeTransport()))
        assertNull(fresh.snapshot())
        assertNull(harness(mode = PlayMode.ONLINE).game.snapshot())
        val h = harness()
        assertNotNull(h.game.snapshot())
        h.game.leaveSession()
        assertNull(h.game.snapshot())
    }

    @Test
    fun `restore also brings back the roles and difficulty`() {
        val a = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT, side = HoundsSides.HARE, level = CpuDifficulty.HARD)
        val snap = a.game.snapshot()!!
        val b = HareAndHoundsGame()
        b.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, vsBot, 0, MorrisFakeTransport()))
        assertTrue(b.restore(snap))
        assertEquals(1, b.s().houndsSeat)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
    }

    // ---- undo and hint ----

    @Test
    fun `undo takes back the person's step and the cpu reply together`() {
        val h = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        val g = h.game
        assertFalse(g.canUndo)
        val start = g.s()
        g.play(HoundsMove(1, 4))
        g.playBotTurn(Random(8))
        assertEquals(2, g.s().ply)
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(start, g.s())
        assertFalse(g.canUndo)
    }

    @Test
    fun `hint is a legal step, the exact best one, and clears when a step is played`() {
        val g = harness().game
        val hint = g.hint()
        assertNotNull(hint)
        assertTrue(hint in g.s().legalMoves)
        assertEquals(HoundsEngine.bestMove(g.toPosition(g.s())), hint)
        assertEquals(hint, g.hintMove.value)
        g.play(hint!!)
        assertNull(g.hintMove.value)
        assertEquals(hint, runBlocking { harness().game.requestHint() })
    }

    @Test
    fun `no hint on the cpu's turn`() {
        val h = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT, side = HoundsSides.HARE)
        assertNull(h.game.hint())
        assertNull(runBlocking { h.game.requestHint() })
    }

    // ---- cpu ----

    @Test
    fun `the cpu plays a legal step on its own turn only, at every level`() {
        for (level in CpuDifficulty.values()) {
            val h = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT, level = level, side = HoundsSides.HARE)
            val g = h.game
            val rng = Random(level.ordinal)
            var guard = 0
            while (!g.s().boardOver && guard++ < 60) {
                val before = g.s()
                if (before.players[before.currentPlayerIndex].isBot) {
                    g.playBotTurn(rng)
                    assertEquals(before.ply + 1, g.s().ply)
                } else {
                    g.playBotTurn(rng)
                    assertEquals(before, g.s())
                    randomMove(g, rng)
                }
            }
        }
    }

    @Test
    fun `playBotTurnAsync plays once`() {
        val h = harness(players = vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT, side = HoundsSides.HARE)
        runBlocking { h.game.playBotTurnAsync() }
        assertEquals(1, h.game.s().ply)
        runBlocking { h.game.playBotTurnAsync() } // the person's turn
        assertEquals(1, h.game.s().ply)
    }

    // ---- fuzz ----

    @Test
    fun `fuzz - snapshots round-trip and undo restores at every ply`() {
        val rng = Random(404)
        repeat(30) {
            val g = harness().game
            var n = 0
            while (!g.s().boardOver) {
                val before = g.s()
                randomMove(g, rng)
                val after = g.s()
                assertEquals(before.ply + 1, after.ply)
                assertEquals(3, after.cells.count { it == 1 })
                assertEquals(1, after.cells.count { it == 2 })
                if (!after.boardOver) {
                    val copy = HareAndHoundsGame()
                    copy.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans, 0, MorrisFakeTransport()))
                    assertTrue(copy.restore(g.snapshot()!!))
                    assertEquals(after, copy.s())
                }
                if (++n % 5 == 0) {
                    val move = after.lastMove!!
                    g.undo()
                    assertEquals(before, g.s())
                    g.play(move)
                    assertEquals(after, g.s())
                }
            }
            assertNotNull(g.s().winnerPlayerId)
        }
    }

    @Test
    fun `abortMatch reports an aborted result`() {
        val h = harness()
        h.game.abortMatch()
        assertTrue(h.results[0].wasAborted)
    }
}
