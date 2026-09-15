package com.gamesuite.games.reversi

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
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

class ReversiGameTest {

    /** Two human players — used for hand-driving both "sides" directly, same pattern as ConnectFourGameTest. */
    private fun newTwoHumanGame(): ReversiGame {
        val game = ReversiGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "Player 1"),
                    PlayerInfo(playerId = "p2", displayName = "Player 2")
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    /** One human (index 0), one bot at [difficulty] (index 1) — used for bot-behavior tests. */
    private fun newVsBotGame(difficulty: CpuDifficulty): ReversiGame {
        val game = ReversiGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "you", displayName = "You"),
                    PlayerInfo(playerId = "cpu", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    private fun index(row: Int, col: Int) = row * 8 + col

    @Test
    fun `a fresh board has exactly the standard Othello opening position and player 0 to move`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val s = game.state.value!!
        assertEquals(64, s.cells.size)

        // Standing convention this engine uses: the STARTING player (index 0 on the very first
        // board) owns index(3,4) and index(4,3); the non-starting player owns index(3,3) and
        // index(4,4) -- the real Othello D4/E4/D5/E5 opening pattern.
        assertEquals(1, s.cells[index(3, 3)]) // not-starting player
        assertEquals(0, s.cells[index(3, 4)]) // starting player
        assertEquals(0, s.cells[index(4, 3)]) // starting player
        assertEquals(1, s.cells[index(4, 4)]) // not-starting player
        assertEquals("every other cell must be empty", 60, s.cells.count { it == null })

        assertEquals(0, s.currentPlayerIndex)
        assertEquals(listOf(2, 2), s.scores)
        assertFalse(s.boardOver)
        assertFalse(s.justPassed)
        assertNull(s.winnerPlayerId)
    }

    @Test
    fun `the standard opening has exactly the 4 real legal opening moves`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val s = game.state.value!!
        // The well-known 4 opening moves available to the first player in real Othello, translated
        // to this board's row-major indexing: (2,3), (3,2), (4,5), (5,4).
        assertEquals(setOf(index(2, 3), index(3, 2), index(4, 5), index(5, 4)), s.legalMoves)
    }

    @Test
    fun `a real flanking capture flips exactly the trapped opponent discs, not neighbours beyond them`() {
        val game = newTwoHumanGame()
        game.startMatch()
        // Player 0 plays (2,3): flanks player 1's disc at (3,3) against player 0's own disc at (4,3).
        game.placeDisc(index(2, 3))
        val s = game.state.value!!
        assertEquals("the newly placed disc must belong to the mover", 0, s.cells[index(2, 3)])
        assertEquals("the single flanked disc must flip to the mover's color", 0, s.cells[index(3, 3)])
        assertEquals("the anchoring disc beyond the flip must be unchanged", 0, s.cells[index(4, 3)])
        // Every other originally-placed disc must be untouched by this single flip.
        assertEquals(0, s.cells[index(3, 4)])
        assertEquals(1, s.cells[index(4, 4)])
        assertEquals(listOf(4, 1), s.scores)
    }

    @Test
    fun `a move that flanks in multiple directions flips every flanked line, not just one`() {
        // A hand-built midgame position where a single move flanks two separate directions at once.
        // Row 3 (0-indexed): cols 2,3,4,5 = P0,P1,P1,_ ; placing P0 at (3,5) flanks P1@(3,3..4)
        // horizontally. Col 5: rows 2,3,4 = P1,_,P1 with (2,5)=P1 and (4,5)=P1 set up so the SAME
        // move also flanks vertically via (2,5)=P1 anchored by a P0 disc at (1,5).
        val game = newTwoHumanGame()
        game.startMatch()
        val cells = MutableList<Int?>(64) { null }
        cells[index(1, 5)] = 0
        cells[index(2, 5)] = 1
        cells[index(3, 2)] = 0
        cells[index(3, 3)] = 1
        cells[index(3, 4)] = 1
        game.state.value = game.state.value!!.copy(
            cells = cells,
            currentPlayerIndex = 0,
            legalMoves = setOf(index(3, 5))
        )

        game.placeDisc(index(3, 5))
        val s = game.state.value!!
        assertEquals("horizontal flank must flip", 0, s.cells[index(3, 3)])
        assertEquals("horizontal flank must flip", 0, s.cells[index(3, 4)])
        assertEquals("vertical flank must ALSO flip in the same move", 0, s.cells[index(2, 5)])
        assertEquals("the anchor disc must stay as-is", 0, s.cells[index(1, 5)])
        assertEquals("the placed disc itself", 0, s.cells[index(3, 5)])
    }

    @Test
    fun `a player with no legal move is passed over automatically and the turn returns to the mover, with justPassed set`() {
        // A position where, after player 0's move, player 1 has no legal move anywhere but player 0
        // still does. Two independent clusters, verified by hand:
        // - Corner cluster: player 1's disc at (1,1) with player 0's anchor at (2,2) -- player 0
        //   plays the corner (0,0), flanking and flipping (1,1) to player 0.
        // - Edge cluster: player 0's disc at (7,0) (a corner, so the chain can never extend past it)
        //   with player 1's disc at (7,1) sitting right next to it -- this leaves player 1 with a
        //   surviving disc (so the board isn't simply "player 1 has zero discs left", which would
        //   trivially end the whole game instead of merely passing player 1 over), but (7,1) can
        //   never itself produce a legal move for player 1 (its only player-0 neighbour, the corner,
        //   has no cell beyond it to serve as a legal empty starting square), while it DOES hand
        //   player 0 a real remaining legal move at (7,2) (flanking (7,1) back onto the (7,0) anchor).
        val game = newTwoHumanGame()
        game.startMatch()
        val cells = MutableList<Int?>(64) { null }
        cells[index(1, 1)] = 1
        cells[index(2, 2)] = 0
        cells[index(7, 0)] = 0
        cells[index(7, 1)] = 1
        game.state.value = game.state.value!!.copy(
            cells = cells,
            currentPlayerIndex = 0,
            legalMoves = setOf(index(0, 0))
        )

        game.placeDisc(index(0, 0))
        val s = game.state.value!!
        assertEquals("the corner-cluster capture must have flipped (1,1) to player 0", 0, s.cells[index(1, 1)])
        assertFalse("the board must not be over -- player 0 still has a legal move at (7,2)", s.boardOver)
        assertTrue("player 1 having no legal move anywhere must be reported as a real pass", s.justPassed)
        assertEquals("turn must return to player 0, the only one who can actually move", 0, s.currentPlayerIndex)
        assertEquals(setOf(index(7, 2)), s.legalMoves)
    }

    @Test
    fun `neither player having a legal move ends the board, with the higher disc count winning`() {
        val game = newTwoHumanGame()
        game.startMatch()
        // A fully-packed board except one empty cell, weighted toward player 0. A completely full
        // board trivially has zero legal moves for either side (every legal move needs an empty
        // destination cell), so this cleanly exercises the "neither player can move" branch without
        // depending on any particular flip outcome -- (7,7)'s only neighbours (6,6)/(6,7)/(7,6) are
        // already player 1's own color, so placing player 1's own disc there flips nothing.
        val cells = MutableList<Int?>(64) { if (it < 40) 0 else 1 }
        cells[index(7, 7)] = null
        game.state.value = game.state.value!!.copy(
            cells = cells,
            currentPlayerIndex = 1,
            legalMoves = setOf(index(7, 7))
        )

        game.placeDisc(index(7, 7))
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertFalse(s.justPassed)
        assertEquals(64, s.cells.count { it != null })
        assertEquals("player 0 (\"p1\") holds 40 discs to player 1's 24", "p1", s.winnerPlayerId)
        assertTrue(s.scores[0] > s.scores[1])
    }

    @Test
    fun `an exact tie in final disc count is reported as no winner`() {
        val game = newTwoHumanGame()
        game.startMatch()
        // Same construction as the decisive-win fixture above, but split exactly 32-32 -- (7,7)'s
        // neighbours are already player 1's own color, so the final placement flips nothing.
        val cells = MutableList<Int?>(64) { if (it < 32) 0 else 1 }
        cells[index(7, 7)] = null
        game.state.value = game.state.value!!.copy(
            cells = cells,
            currentPlayerIndex = 1,
            legalMoves = setOf(index(7, 7))
        )

        game.placeDisc(index(7, 7))
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertEquals(listOf(32, 32), s.scores)
        assertNull("an exact tie must report no winner", s.winnerPlayerId)
    }

    @Test
    fun `the starting player alternates across successive boards`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val first = game.state.value!!.currentPlayerIndex
        game.playAgain()
        val second = game.state.value!!.currentPlayerIndex
        game.playAgain()
        val third = game.state.value!!.currentPlayerIndex
        assertEquals(first, third)
        assertFalse(first == second)
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newTwoHumanGame()
        game.startMatch()
        game.endMatch(GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch()
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    @Test
    fun `placeDisc is rejected once the session has ended via leaveSession, even if the board itself was not yet over`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val legalMove = game.state.value!!.legalMoves.first()
        game.placeDisc(legalMove)
        assertFalse(game.state.value!!.boardOver)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.placeDisc(game.state.value!!.legalMoves.firstOrNull() ?: 0)
        assertEquals("a placeDisc() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `placeDisc on a cell that is not a legal move is a total no-op`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val before = game.state.value
        // (0,0) is a corner, never a legal move on the opening position.
        game.placeDisc(index(0, 0))
        assertEquals(before, game.state.value)
    }

    @Test
    fun `every difficulty bot always plays some legal move without crashing, across many full games`() {
        for (difficulty in CpuDifficulty.entries) {
            repeat(3) {
                val game = newVsBotGame(difficulty)
                game.startMatch()
                playFullGameAlternatingHumanMovesAndBotTurns(game)
                assertTrue(game.state.value!!.boardOver)
            }
        }
    }

    @Test
    fun `the bot never returns an actually illegal move, at every difficulty`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newVsBotGame(difficulty)
            game.startMatch()
            // Force the bot (player 1) to move first, off the opening position -- legalMoves must
            // be recomputed for player 1 here, NOT left as player 0's own opening move set (the two
            // are different cells entirely, by the opening's own diagonal symmetry).
            val opening = game.state.value!!
            val legalBefore = legalMovesForTest(opening.cells, 1)
            game.state.value = opening.copy(currentPlayerIndex = 1, legalMoves = legalBefore)
            game.playBotTurn()
            val s = game.state.value!!
            // The bot must have placed on a cell that WAS legal for it, i.e. some cell in
            // legalBefore that is now occupied by player 1 (it may have flipped others too, but the
            // cell it played on must itself have been a real legal destination).
            val playedSomewhereLegal = legalBefore.any { candidate -> s.cells[candidate] == 1 }
            assertTrue("difficulty=$difficulty: the bot must have played on one of its own legal cells", playedSomewhereLegal)
        }
    }

    @Test
    fun `playBotTurn is rejected once the session has ended via leaveSession, even if the board itself was not yet over`() {
        val game = newVsBotGame(CpuDifficulty.EASY)
        game.startMatch()
        val s0 = game.state.value!!
        game.state.value = s0.copy(currentPlayerIndex = 1, legalMoves = legalMovesForTest(s0.cells, 1))

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.playBotTurn()
        assertEquals("a playBotTurn() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `HARD bot moves complete quickly even from the opening position`() {
        // A coarse performance sanity check, mirroring ConnectFourGameTest's own timing test --
        // exists to catch a real regression (e.g. a pruning bug), not to assert a specific number.
        val game = newVsBotGame(CpuDifficulty.HARD)
        game.startMatch()
        game.state.value = game.state.value!!.copy(currentPlayerIndex = 1, legalMoves = legalMovesForTest(game.state.value!!.cells, 1))

        val start = System.nanoTime()
        game.playBotTurn()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("a single HARD-difficulty bot move took ${elapsedMs}ms -- unexpectedly slow for a depth-6 alpha-beta search on an 8x8 board", elapsedMs < 8000)
        assertNotNull("the bot must have actually placed a disc", game.state.value!!.cells.firstOrNull { it == 1 })
    }

    /** Drives a full vs-bot game to completion: on each turn, either let the bot move, or -- for
     *  the human -- place on the first available legal cell, purely to exercise the engine
     *  end-to-end (including real passes) without crashing or looping forever. Mirrors
     *  ConnectFourGameTest's own playFullGameAlternatingHumanDropsAndBotTurns. */
    private fun playFullGameAlternatingHumanMovesAndBotTurns(game: ReversiGame) {
        var guard = 0
        while (game.state.value?.boardOver == false) {
            guard++
            assertTrue("game did not terminate within a sane number of moves", guard < 200)
            val s = game.state.value!!
            if (s.players[s.currentPlayerIndex].isBot) {
                game.playBotTurn()
            } else {
                val move = s.legalMoves.firstOrNull() ?: break
                game.placeDisc(move)
            }
        }
    }

    /** Test-only reimplementation of the engine's own legal-move rule, used only to hand-construct
     *  a [ReversiState] fixture's `legalMoves` field for scenarios that bypass [ReversiGame.placeDisc]
     *  entirely (forcing whose turn it is directly via `game.state.value = ...copy(...)`). */
    private fun legalMovesForTest(cells: List<Int?>, player: Int): Set<Int> {
        val directions = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
        val opponent = 1 - player
        fun flanks(row: Int, col: Int, dr: Int, dc: Int): Boolean {
            var r = row + dr
            var c = col + dc
            var sawOpponent = false
            while (r in 0..7 && c in 0..7) {
                when (cells[r * 8 + c]) {
                    opponent -> { sawOpponent = true; r += dr; c += dc }
                    player -> return sawOpponent
                    else -> return false
                }
            }
            return false
        }
        return cells.indices.filterTo(mutableSetOf()) { idx ->
            cells[idx] == null && directions.any { (dr, dc) -> flanks(idx / 8, idx % 8, dr, dc) }
        }
    }
}
