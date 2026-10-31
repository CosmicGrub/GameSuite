package com.gamesuite.games.mahjong

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Play, tap, timer, hint, undo, shuffle, records, session scoring and Resume for MahjongGame. */
class MahjongGameTest {

    private var clock = 0L
    private val roster = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        players: List<PlayerInfo> = roster,
        seed: Long = 77L
    ): MahjongGame {
        val game = MahjongGame(nowMillis = { clock }, randomProvider = { Random(seed) })
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(layout: MahjongLayoutId = MahjongLayoutId.PYRAMID, seed: Long = 5L): MahjongGame =
        newGame().also { it.startMatchWith(layout, seed) }

    private fun MahjongGame.st() = state.value!!

    private fun solutionOf(layout: MahjongLayoutId, seed: Long) = newGame().dealSolution(layout, seed)

    private fun playOut(game: MahjongGame, order: List<Pair<Int, Int>>, count: Int = order.size) {
        for ((a, b) in order.take(count)) {
            clock += 100
            assertTrue("solution move $a,$b rejected", game.tryMatch(a, b))
        }
    }

    private fun json(
        types: List<Int>,
        moves: Int,
        layout: String = "cross",
        selected: Int? = null,
        timer: Boolean = moves > 0,
        players: String = "\"p1\"",
        hints: Int = 0
    ) = "{\"playerIds\":[$players],\"layout\":\"$layout\",\"types\":[${types.joinToString(",")}]," +
        "\"selected\":${selected ?: "null"},\"moves\":$moves,\"hintsUsed\":$hints,\"undoCount\":0," +
        "\"shuffleCount\":0,\"gamesSolved\":0,\"timerStarted\":$timer,\"elapsedMillis\":0,\"finishedMillis\":null}"

    /** A coherent full cross board: slots 2k and 2k+1 share a type. */
    private fun crossTypes(): MutableList<Int> = MutableList(60) { (it / 2) % 34 }

    // --- Module facts -----------------------------------------------------------------------

    @Test
    fun `module facts`() {
        val game = newGame()
        assertEquals("mahjong-solitaire", game.gameId)
        assertEquals("Mahjong Solitaire", game.displayName)
        assertEquals(1, game.minPlayers)
        assertEquals(1, game.maxPlayers)
        assertEquals(listOf(PlayMode.SINGLE_PLAYER_VS_BOT), game.supportedModes)
    }

    @Test
    fun `a new match deals the chosen layout`() {
        val game = newGame()
        game.startMatchWith(MahjongLayoutId.CROSS, 1L)
        assertEquals(MahjongLayoutId.CROSS, game.st().layout)
        assertEquals(60, game.st().types.size)
        assertEquals(60, game.st().remaining)
        assertEquals(0, game.st().moves)
    }

    @Test
    fun `startMatch deals the layout property and uses the injected random`() {
        val a = newGame(seed = 9L)
        a.layout = MahjongLayoutId.PYRAMID
        a.startMatch()
        val b = newGame(seed = 9L)
        b.layout = MahjongLayoutId.PYRAMID
        b.startMatch()
        assertEquals(MahjongLayoutId.PYRAMID, a.st().layout)
        assertEquals(a.st().types, b.st().types)
    }

    @Test
    fun `the started board is the seeded deal`() {
        val game = started(MahjongLayoutId.PYRAMID, 5L)
        assertEquals(solutionOf(MahjongLayoutId.PYRAMID, 5L).first.toList(), game.st().types)
    }

    // --- Playing ----------------------------------------------------------------------------

    @Test
    fun `playing the dealt solution wins the board`() {
        for (id in MahjongLayoutId.values()) {
            val game = started(id, 3L)
            val order = solutionOf(id, 3L).second
            playOut(game, order)
            assertTrue("$id", game.st().won)
            assertEquals(0, game.st().remaining)
            assertEquals(order.size, game.st().moves)
            assertEquals(1, game.gamesSolved.value)
            assertNotNull(game.finishedElapsedMillis.value)
            assertFalse(game.matchOver.value)
        }
    }

    @Test
    fun `a won board accepts no more input and cannot be undone`() {
        val game = started(MahjongLayoutId.CROSS, 4L)
        playOut(game, solutionOf(MahjongLayoutId.CROSS, 4L).second)
        assertFalse(game.canUndo)
        assertEquals(MahjongTap.IGNORED, game.tapTile(0))
        assertNull(game.snapshot())
        assertNull(game.hint())
        assertFalse(game.canShuffle)
    }

    @Test
    fun `tap selects deselects and matches`() {
        val game = started()
        val (a, b) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        assertEquals(MahjongTap.SELECTED, game.tapTile(a))
        assertEquals(a, game.st().selected)
        assertEquals(MahjongTap.DESELECTED, game.tapTile(a))
        assertNull(game.st().selected)
        assertEquals(MahjongTap.SELECTED, game.tapTile(a))
        assertEquals(MahjongTap.MATCHED, game.tapTile(b))
        assertEquals(1, game.st().moves)
        assertEquals(game.st().types.size - 2, game.st().remaining)
        assertEquals(-1, game.st().types[a])
        assertEquals(-1, game.st().types[b])
        assertNull(game.st().selected)
    }

    @Test
    fun `tapping a non matching free tile moves the selection`() {
        val game = started()
        val (a, _) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        val mask = game.freeMask(game.st())
        val other = mask.indices.first { mask[it] && !MahjongTiles.matches(game.st().types[a], game.st().types[it]) }
        game.tapTile(a)
        assertEquals(MahjongTap.MISMATCH, game.tapTile(other))
        assertEquals(other, game.st().selected)
        assertEquals(0, game.st().moves)
    }

    @Test
    fun `a blocked tile cannot be selected and does not start the clock`() {
        val game = started()
        val mask = game.freeMask(game.st())
        val blocked = mask.indices.first { !mask[it] }
        assertEquals(MahjongTap.BLOCKED, game.tapTile(blocked))
        assertNull(game.st().selected)
        assertNull(game.timerStartElapsedRealtime.value)
        assertNull(game.activeElapsedMillis())
    }

    @Test
    fun `tapping a removed slot or an unknown slot is ignored`() {
        val game = started()
        val (a, b) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        assertTrue(game.tryMatch(a, b))
        assertEquals(MahjongTap.IGNORED, game.tapTile(a))
        assertEquals(MahjongTap.IGNORED, game.tapTile(-1))
        assertEquals(MahjongTap.IGNORED, game.tapTile(10_000))
    }

    @Test
    fun `tryMatch rejects blocked tiles mismatches and the same tile`() {
        val game = started()
        val (a, b) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        assertFalse(game.tryMatch(a, a))
        val mask = game.freeMask(game.st())
        val blocked = mask.indices.first { !mask[it] }
        assertFalse(game.tryMatch(a, blocked))
        val nonMatching = mask.indices.first { mask[it] && !MahjongTiles.matches(game.st().types[a], game.st().types[it]) }
        assertFalse(game.tryMatch(a, nonMatching))
        assertEquals(0, game.st().moves)
        assertTrue(game.tryMatch(a, b))
        assertEquals(1, game.st().moves)
    }

    @Test
    fun `flowers match each other through the game`() {
        val game = newGame()
        // Two adjacent free tiles: a flower and a different flower.
        game.setBoardForTest(MahjongLayoutId.CROSS, MutableList(60) { -1 }.also { it[56] = 34; it[57] = 37 })
        // Slots 56/57 are the two z2 tiles at (6,6) and (8,6): side by side, nothing on top.
        assertTrue(game.tryMatch(56, 57))
        assertTrue(game.st().won)
    }

    @Test
    fun `a flower and a season do not match through the game`() {
        val game = newGame()
        game.setBoardForTest(MahjongLayoutId.CROSS, MutableList(60) { -1 }.also { it[56] = 34; it[57] = 38 })
        assertFalse(game.tryMatch(56, 57))
        assertEquals(MahjongTap.SELECTED, game.tapTile(56))
        assertEquals(MahjongTap.MISMATCH, game.tapTile(57))
    }

    // --- Clock ------------------------------------------------------------------------------

    @Test
    fun `the clock starts on the first free tap and excludes paused time`() {
        val game = started()
        clock = 1_000
        assertNull(game.activeElapsedMillis())
        val (a, _) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        game.tapTile(a)
        clock = 3_000
        assertEquals(2_000L, game.activeElapsedMillis())
        game.pause()
        clock = 8_000
        assertEquals(2_000L, game.activeElapsedMillis())
        game.resume()
        clock = 9_000
        assertEquals(3_000L, game.activeElapsedMillis())
    }

    @Test
    fun `the solve time freezes at the last match`() {
        val game = started(MahjongLayoutId.CROSS, 8L)
        clock = 0
        val order = solutionOf(MahjongLayoutId.CROSS, 8L).second
        playOut(game, order)
        val frozen = game.finishedElapsedMillis.value
        clock += 50_000
        assertEquals(frozen, game.activeElapsedMillis())
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `a hint is a legal free matching pair and marks the game assisted`() {
        for (seed in 1L..5L) {
            val game = started(MahjongLayoutId.PYRAMID, seed)
            assertTrue(game.isUnassisted)
            val pick = game.hint()
            assertNotNull(pick)
            val (a, b) = pick!!
            assertTrue(game.isFree(a))
            assertTrue(game.isFree(b))
            assertTrue(MahjongTiles.matches(game.st().types[a], game.st().types[b]))
            assertEquals(pick, game.hintPair.value)
            assertEquals(1, game.hintsUsed.value)
            assertFalse(game.isUnassisted)
        }
    }

    @Test
    fun `playing the hinted pair removes it and clears the highlight`() {
        val game = started()
        val (a, b) = game.hint()!!
        assertEquals(MahjongTap.SELECTED, game.tapTile(a))
        assertNull(game.hintPair.value)
        assertEquals(MahjongTap.MATCHED, game.tapTile(b))
        assertEquals(1, game.hintsUsed.value)
    }

    @Test
    fun `a hint on a stuck board gives nothing`() {
        val game = newGame()
        game.setBoardForTest(MahjongLayoutId.CROSS, stuckBoard())
        assertNull(game.hint())
        assertEquals(0, game.hintsUsed.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo restores the board exactly and marks nothing`() {
        val game = started()
        assertFalse(game.canUndo)
        val before = game.st()
        val (a, b) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        game.tapTile(a)
        game.tapTile(b)
        assertTrue(game.canUndo)
        game.undo()
        assertEquals(before.types, game.st().types)
        assertEquals(0, game.st().moves)
        assertNull(game.st().selected)
        assertEquals(1, game.undoCount.value)
        assertTrue(game.isUnassisted)
        assertEquals(0, game.hintsUsed.value)
    }

    @Test
    fun `undo is unlimited back to the first deal`() {
        val game = started(MahjongLayoutId.TURTLE, 2L)
        val initial = game.st().types
        val order = solutionOf(MahjongLayoutId.TURTLE, 2L).second
        playOut(game, order, 60)
        repeat(60) {
            assertTrue(game.canUndo)
            game.undo()
        }
        assertEquals(initial, game.st().types)
        assertEquals(0, game.st().moves)
        assertFalse(game.canUndo)
        assertEquals(60, game.undoCount.value)
    }

    @Test
    fun `undo never forgives a hint`() {
        val game = started()
        val (a, b) = game.hint()!!
        game.tapTile(a)
        game.tapTile(b)
        game.undo()
        assertEquals(1, game.hintsUsed.value)
        assertFalse(game.isUnassisted)
    }

    @Test
    fun `a new board starts a new undo history and fresh counters`() {
        val game = started()
        val (a, b) = game.hint()!!
        game.tryMatch(a, b)
        game.startMatchWith(MahjongLayoutId.CROSS, 9L)
        assertFalse(game.canUndo)
        assertEquals(0, game.hintsUsed.value)
        assertEquals(0, game.undoCount.value)
        assertTrue(game.isUnassisted)
    }

    // --- Shuffle ----------------------------------------------------------------------------

    /** Two separate two-tile stacks whose free tops differ: no move, yet the board is re-dealable. */
    private fun stuckBoard(): List<Int> {
        val pos = MahjongLayouts.positions(MahjongLayoutId.CROSS)
        val types = MutableList(60) { -1 }
        types[pos.indexOf(MahjongPos(4, 6, 0))] = 9
        types[pos.indexOf(MahjongPos(4, 6, 1))] = 0
        types[pos.indexOf(MahjongPos(10, 8, 0))] = 0
        types[pos.indexOf(MahjongPos(10, 8, 1))] = 9
        return types
    }

    @Test
    fun `shuffle is not offered while a move exists`() {
        val game = started()
        assertTrue(game.hasMoves)
        assertFalse(game.isStuck)
        assertFalse(game.canShuffle)
        assertFalse(game.shuffle())
        assertEquals(0, game.shuffleCount.value)
    }

    @Test
    fun `a stuck board can be shuffled and the tiles stay the same`() {
        val game = newGame()
        val types = stuckBoard()
        game.setBoardForTest(MahjongLayoutId.CROSS, types, moves = 28)
        assertTrue(game.isStuck)
        assertTrue(game.canShuffle)
        assertTrue(game.shuffle())
        assertEquals(types.filter { it >= 0 }.sorted(), game.st().types.filter { it >= 0 }.sorted())
        assertEquals(types.map { it >= 0 }, game.st().types.map { it >= 0 })
        assertTrue(game.hasMoves)
        assertFalse(game.isStuck)
        assertEquals(1, game.shuffleCount.value)
        assertFalse(game.isUnassisted)
        assertEquals(0, game.hintsUsed.value)
    }

    @Test
    fun `a shuffled board is still winnable`() {
        val game = newGame()
        game.setBoardForTest(MahjongLayoutId.CROSS, stuckBoard(), moves = 28)
        assertTrue(game.shuffle())
        val result = MahjongSolver.solve(game.geometry, game.st().types.toIntArray())
        assertEquals(MahjongSolve.SOLVABLE, result.outcome)
    }

    @Test
    fun `a shuffle can be undone`() {
        val game = newGame()
        val types = stuckBoard()
        game.setBoardForTest(MahjongLayoutId.CROSS, types, moves = 28)
        game.shuffle()
        assertTrue(game.canUndo)
        game.undo()
        assertEquals(types, game.st().types)
        assertEquals(1, game.shuffleCount.value) // the shuffle still counts
    }

    @Test
    fun `a dead end cannot be shuffled`() {
        val game = newGame()
        val pos = MahjongLayouts.positions(MahjongLayoutId.CROSS)
        val types = MutableList(60) { -1 }
        types[pos.indexOf(MahjongPos(4, 6, 0))] = 5
        types[pos.indexOf(MahjongPos(4, 6, 1))] = 5
        game.setBoardForTest(MahjongLayoutId.CROSS, types, moves = 29)
        assertTrue(game.isStuck)
        assertFalse(game.shuffle())
        assertEquals(types, game.st().types)
        assertEquals(0, game.shuffleCount.value)
    }

    // --- Records ----------------------------------------------------------------------------

    @Test
    fun `a clean solve is record eligible and a hinted one is not`() {
        val clean = started(MahjongLayoutId.CROSS, 6L)
        playOut(clean, solutionOf(MahjongLayoutId.CROSS, 6L).second)
        assertTrue(clean.st().won)
        assertTrue(clean.isUnassisted)

        val helped = started(MahjongLayoutId.CROSS, 6L)
        helped.hint()
        playOut(helped, solutionOf(MahjongLayoutId.CROSS, 6L).second)
        assertTrue(helped.st().won)
        assertFalse(helped.isUnassisted)
    }

    // --- Session ----------------------------------------------------------------------------

    @Test
    fun `leaving scores the boards cleared`() {
        val game = started(MahjongLayoutId.CROSS, 6L)
        var result: GameResult? = null
        game.setOnMatchEnd { result = it }
        playOut(game, solutionOf(MahjongLayoutId.CROSS, 6L).second)
        game.leaveSession()
        val r = result!!
        assertFalse(r.wasAborted)
        assertEquals(1, r.scores.single().score)
        assertTrue(r.scores.single().isWinner)
        assertTrue(game.matchOver.value)
    }

    @Test
    fun `leaving with nothing cleared is not a win and only ends once`() {
        val game = started()
        var ends = 0
        var result: GameResult? = null
        game.setOnMatchEnd { ends++; result = it }
        game.leaveSession()
        game.leaveSession()
        assertEquals(1, ends)
        assertEquals(0, result!!.scores.single().score)
        assertFalse(result!!.scores.single().isWinner)
    }

    @Test
    fun `aborting reports an aborted match`() {
        val game = started()
        var result: GameResult? = null
        game.setOnMatchEnd { result = it }
        game.abortMatch()
        assertTrue(result!!.wasAborted)
    }

    @Test
    fun `play again keeps the session score and deals a fresh board`() {
        val game = started(MahjongLayoutId.CROSS, 6L)
        playOut(game, solutionOf(MahjongLayoutId.CROSS, 6L).second)
        game.playAgain()
        assertFalse(game.st().won)
        assertEquals(60, game.st().remaining)
        assertEquals(1, game.gamesSolved.value)
        assertEquals(MahjongLayoutId.CROSS, game.st().layout)
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `nothing to snapshot before the first board after the session ends or online`() {
        val game = newGame()
        assertNull(game.snapshot())
        game.startMatchWith(MahjongLayoutId.CROSS, 1L)
        assertNotNull(game.snapshot())
        game.leaveSession()
        assertNull(game.snapshot())
        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatchWith(MahjongLayoutId.CROSS, 1L) }
        assertNull(online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on`() {
        val a = started(MahjongLayoutId.PYRAMID, 5L)
        clock = 1_000
        val order = solutionOf(MahjongLayoutId.PYRAMID, 5L).second
        playOut(a, order, 7)
        a.hint()
        a.undo()
        assertEquals(6, a.st().moves)
        assertTrue(a.tryMatch(order[6].first, order[6].second))
        assertEquals(MahjongTap.SELECTED, a.tapTile(order[7].first))
        clock += 4_000
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(a.st(), b.st())
        assertEquals(a.hintsUsed.value, b.hintsUsed.value)
        assertEquals(a.undoCount.value, b.undoCount.value)
        assertEquals(a.shuffleCount.value, b.shuffleCount.value)
        assertEquals(a.activeElapsedMillis(), b.activeElapsedMillis())
        assertEquals(a.st().layout, b.layout)
        // Finish from the restored copy.
        for ((x, y) in order.drop(7)) assertTrue(b.tryMatch(x, y))
        assertTrue(b.st().won)
    }

    @Test
    fun `resume keeps counting from the active time and not the time away`() {
        val a = started()
        clock = 10_000
        val (x, _) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        a.tapTile(x)
        clock = 17_000
        val snapshot = a.snapshot()!!
        clock = 900_000
        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(7_000L, b.activeElapsedMillis())
        clock = 903_000
        assertEquals(10_000L, b.activeElapsedMillis())
    }

    @Test
    fun `restore replaces undo history`() {
        val a = started()
        val (x, y) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        a.tryMatch(x, y)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse(b.canUndo)
    }

    @Test
    fun `garbage and foreign snapshots are rejected and change nothing`() {
        val game = started()
        val (x, y) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        game.tryMatch(x, y)
        val before = game.st()
        val bad = listOf(
            "",
            "not json",
            "{}",
            "[]",
            json(crossTypes(), 0, players = "\"someone-else\""),
            json(crossTypes(), 0, layout = "spiral"),
            json(crossTypes().dropLast(2), 0),
            json(crossTypes().also { it[3] = 99 }, 0),
            json(crossTypes().also { it[3] = -2 }, 0)
        )
        for (text in bad) {
            assertFalse("accepted: $text", game.restore(text))
            assertEquals(before, game.st())
        }
        assertEquals(1, game.st().moves)
    }

    @Test
    fun `a crafted coherent snapshot is accepted`() {
        val game = newGame()
        assertTrue(game.restore(json(crossTypes(), 0)))
        assertEquals(60, game.st().remaining)
        // Remove the two z2 tiles of one pair (slots 56 and 57 share a type and nothing lies on them).
        val types = crossTypes().also { it[56] = -1; it[57] = -1 }
        assertTrue(game.restore(json(types, 1)))
        assertEquals(58, game.st().remaining)
        assertEquals(1, game.st().moves)
    }

    @Test
    fun `incoherent tile multisets are rejected`() {
        val game = newGame()
        // An odd group count.
        assertFalse(game.restore(json(crossTypes().also { it[0] = 7 }, 0)))
        // Six of one type: even, but the set only holds four.
        assertFalse(game.restore(json(crossTypes().also { for (i in 0..5) it[i] = 0 }, 0)))
        // Two flowers is fine (a legal group); a lone flower is odd.
        assertTrue(game.restore(json(crossTypes().also { it[0] = 34; it[1] = 35 }, 0)))
        assertFalse(game.restore(json(crossTypes().also { it[0] = 34; it[1] = 38 }, 0)))
    }

    @Test
    fun `inconsistent progress counters are rejected`() {
        val game = newGame()
        val removedPair = crossTypes().also { it[56] = -1; it[57] = -1 }
        assertFalse("moves do not add up", game.restore(json(removedPair, 0)))
        assertFalse("moves do not add up", game.restore(json(removedPair, 2)))
        assertFalse("negative moves", game.restore(json(crossTypes(), -1)))
        assertFalse("moves without a clock", game.restore(json(removedPair, 1, timer = false)))
        assertFalse("negative hints", game.restore(json(crossTypes(), 0, hints = -1)))
        assertTrue(game.restore(json(removedPair, 1)))
    }

    @Test
    fun `a selection on a removed tile is rejected`() {
        val game = newGame()
        val removedPair = crossTypes().also { it[56] = -1; it[57] = -1 }
        assertFalse(game.restore(json(removedPair, 1, selected = 56)))
        assertFalse(game.restore(json(crossTypes(), 0, selected = 60)))
        assertTrue(game.restore(json(removedPair, 1, selected = 0)))
        assertEquals(0, game.st().selected)
    }

    @Test
    fun `a removed tile with a live tile still on top is rejected`() {
        val game = newGame()
        // Slots 18 and 19 are the z0 tiles at (4,6) and (6,6), both under live z1 tiles.
        val impossible = crossTypes().also { it[18] = -1; it[19] = -1 }
        assertFalse(game.restore(json(impossible, 1)))
    }

    @Test
    fun `an emptied board without a clock is rejected`() {
        val game = newGame()
        assertFalse(game.restore(json(MutableList(60) { -1 }, 30, timer = false)))
    }

    // --- Layout selection from the Hub ------------------------------------------------------

    @Test
    fun `a restored pyramid game keeps its layout for the next board`() {
        val a = started(MahjongLayoutId.PYRAMID, 5L)
        val (x, y) = solutionOf(MahjongLayoutId.PYRAMID, 5L).second.first()
        a.tryMatch(x, y)
        val b = newGame()
        b.layout = MahjongLayoutId.TURTLE
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(MahjongLayoutId.PYRAMID, b.layout)
        b.playAgain()
        assertEquals(MahjongLayoutId.PYRAMID, b.st().layout)
        assertEquals(92, b.st().types.size)
    }
}
