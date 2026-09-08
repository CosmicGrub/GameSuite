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

/**
 * Mirrors the rigor (not the line count) of
 * esp32-tictactoe/native_test/checkers_playtest.cpp's three-pronged
 * approach -- see that file's own header comment -- adapted to JUnit and to
 * CheckersGame's public API only (never reflection into its private move
 * generator/search), the same way that file's own helpers only ever go
 * through CheckersBoard's public surface:
 *   1) Hand-built positions for the specific rules that are easy to get
 *      wrong: mandatory capture, forced multi-jump chaining, king-only
 *      backward capture, and promotion ending a chain mid-jump even though a
 *      further jump exists.
 *   2) Both loss conditions (zero pieces; pieces but no legal moves) checked
 *      as distinct scenarios, since they're two separate branches in
 *      [CheckersGame]'s terminal-detection.
 *   3) Randomized self-play -- both the complete-turn bot path
 *      ([CheckersGame.playBotTurn], all three difficulty tiers) and the
 *      per-hop human path ([CheckersGame.playMove], including a caller that
 *      must recognize and respect forced continuation itself, exactly like a
 *      real touch UI) -- asserting board-integrity invariants after every
 *      hop across many games, rather than trusting the move generator not to
 *      regress.
 *
 * Every assertion reads a public field directly (state.value!!.lastMove,
 * .board, .gameOver, .winnerPlayerId, ...) rather than parsing a
 * human-readable log message -- see CheckersState's own KDoc for why that
 * was a deliberate departure from MancalaGameTest's `lastAction`-regex
 * approach.
 */
class CheckersGameTest {

    // ---- Test helpers (public-API only, mirroring checkers_playtest.cpp's own helpers) ----

    private fun newGame(p0Bot: Boolean = false, p1Bot: Boolean = false): CheckersGame {
        val game = CheckersGame()
        val players = listOf(
            PlayerInfo(playerId = "p0", displayName = "P0", isBot = p0Bot),
            PlayerInfo(playerId = "p1", displayName = "P1", isBot = p1Bot)
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = players,
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    /** Builds a 64-cell board from 8 row-strings (row 0 first): '.' empty,
     *  'a'/'A' side-0 man/king, 'h'/'H' side-1 man/king -- the same glyph
     *  convention checkers_playtest.cpp's own `glyph()` helper uses
     *  ('a'/'A' = the C++ AI side = our side 0; 'h'/'H' = its human side =
     *  our side 1), so a hand-built position here reads the same way a
     *  hand-built position reads there. */
    private fun boardFrom(rows: List<String>): List<CheckersPiece?> {
        require(rows.size == 8) { "expected 8 rows" }
        val cells = MutableList<CheckersPiece?>(64) { null }
        for (row in 0..7) {
            require(rows[row].length == 8) { "row $row must have 8 columns" }
            for (col in 0..7) {
                cells[row * 8 + col] = when (rows[row][col]) {
                    'a' -> CheckersPiece(0, PieceKind.MAN)
                    'A' -> CheckersPiece(0, PieceKind.KING)
                    'h' -> CheckersPiece(1, PieceKind.MAN)
                    'H' -> CheckersPiece(1, PieceKind.KING)
                    else -> null
                }
            }
        }
        return cells
    }

    private fun countOwner(board: List<CheckersPiece?>, owner: Int) = board.count { it?.owner == owner }

    private data class Hop(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int)

    /** Every legal (from -> to) for whichever side is currently to move, built
     *  entirely from [CheckersGame.hasLegalMoveFrom]/[CheckersGame.legalDestinationsFrom]
     *  -- the public-API analog of CheckersLogic's `allLegalMoves()`. */
    private fun allLegalHops(game: CheckersGame): List<Hop> {
        val hops = mutableListOf<Hop>()
        for (row in 0..7) for (col in 0..7) {
            if (!game.hasLegalMoveFrom(row, col)) continue
            for ((toRow, toCol) in game.legalDestinationsFrom(row, col)) hops += Hop(row, col, toRow, toCol)
        }
        return hops
    }

    private fun assertLightSquaresEmpty(board: List<CheckersPiece?>) {
        for (row in 0..7) for (col in 0..7) {
            if ((row + col) % 2 == 0) assertNull("light square ($row,$col) should never hold a piece", board[row * 8 + col])
        }
    }

    // ---- 0) Sanity: the standard starting position ----------------------------------------

    @Test
    fun `startMatch deals the standard 12-vs-12 opening position, side 1 to move`() {
        val game = newGame()
        game.startMatch()
        val s = game.state.value!!
        assertEquals(64, s.board.size)
        assertEquals(12, countOwner(s.board, 0))
        assertEquals(12, countOwner(s.board, 1))
        assertEquals(1, s.currentPlayerIndex)
        assertFalse(s.gameOver)
        assertNull(s.lastMove)
        assertLightSquaresEmpty(s.board)
    }

    @Test
    fun `opening position has exactly 7 legal moves (classic checkers trivia)`() {
        // Every row-6 and row-7 piece is boxed in by its own row-5 line; only
        // row 5's 4 men have anywhere to go, for 1+2+2+2 = 7 total -- the same
        // exact-count sanity check checkers_playtest.cpp itself cites.
        val game = newGame()
        game.startMatch()
        assertEquals(7, allLegalHops(game).size)
    }

    // ---- 1) Hand-built rule-correctness scenarios ------------------------------------------

    @Test
    fun `mandatory capture blocks a non-capturing piece from moving`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = boardFrom(
                listOf(
                    "........",
                    ".a......",
                    ".....a..",
                    "........",
                    "...a....",
                    "..h.....",
                    "........",
                    "h......."
                )
            ),
            currentPlayerIndex = 1
        )
        // Filler at (7,0) has an unobstructed simple move available in isolation,
        // but the mandatory-capture rule must block it while (5,2) can jump.
        assertFalse(game.hasLegalMoveFrom(7, 0))
        assertTrue(game.hasLegalMoveFrom(5, 2))
    }

    @Test
    fun `forced double-jump keeps the turn with the same piece until the chain runs out`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = boardFrom(
                listOf(
                    ".a......",
                    "........",
                    ".....a..",
                    "........",
                    "...a....",
                    "..h.....",
                    "........",
                    "h......."
                )
            ),
            currentPlayerIndex = 1
        )

        // Exactly one first-hop destination: the immediate landing square, not the whole chain.
        assertEquals(setOf(3 to 4), game.legalDestinationsFrom(5, 2))

        game.playMove(1, 5, 2, 3, 4)
        var s = game.state.value!!
        assertEquals(CheckersMove(5, 2, 3, 4, isCapture = true, capRow = 4, capCol = 3), s.lastMove)
        assertNull("captured piece removed", s.pieceAt(4, 3))
        assertNull("source vacated", s.pieceAt(5, 2))
        assertEquals(CheckersPiece(1, PieceKind.MAN), s.pieceAt(3, 4))
        assertTrue("forced continuation active", s.inForcedContinuation)
        assertEquals(3, s.forcedRow); assertEquals(4, s.forcedCol)
        assertEquals("turn has not passed", 1, s.currentPlayerIndex)
        assertFalse("filler still can't move mid-chain", game.hasLegalMoveFrom(7, 0))
        assertEquals(setOf(1 to 6), game.legalDestinationsFrom(3, 4))

        game.playMove(1, 3, 4, 1, 6)
        s = game.state.value!!
        assertEquals(CheckersMove(3, 4, 1, 6, isCapture = true, capRow = 2, capCol = 5), s.lastMove)
        assertNull(s.pieceAt(2, 5))
        assertEquals("landed still a man (row 1 isn't the promotion row)", CheckersPiece(1, PieceKind.MAN), s.pieceAt(1, 6))
        assertFalse(s.inForcedContinuation)
        assertEquals("turn passes once the chain ends", 0, s.currentPlayerIndex)
        assertEquals("side 0 lost exactly the 2 jumped men", 1, countOwner(s.board, 0))
    }

    // ---- lastTurnHops: additive accessor added alongside the animation/physics pitch's
    // Checkers move-animation work -- exposes a compound turn's individual hops (in play
    // order, each with its own accurate capRow/capCol) purely for a UI to key per-capture
    // fade timing off; see CheckersState.lastTurnHops' own KDoc. Adds coverage without
    // touching any existing assertion above. --------------------------------------------

    @Test
    fun `a plain single-hop move's lastTurnHops is just that one hop`() {
        val game = newGame()
        game.startMatch()
        val pick = allLegalHops(game).first()
        game.playMove(1, pick.fromRow, pick.fromCol, pick.toRow, pick.toCol)
        val s = game.state.value!!
        assertEquals(listOf(s.lastMove), s.lastTurnHops)
    }

    @Test
    fun `a bot's compound multi-jump turn reports every individual hop via lastTurnHops`() {
        // Same forced-double-jump position as the hand-played test above, but driven
        // through playBotTurn() instead of two manual playMove() calls -- at every ply
        // here mandatory capture leaves exactly one legal move (the filler at (7,0) can
        // never jump), so EASY's random pick is deterministic in practice.
        val game = newGame(p1Bot = true)
        game.difficulty = CpuDifficulty.EASY
        game.state.value = CheckersState(
            board = boardFrom(
                listOf(
                    ".a......",
                    "........",
                    ".....a..",
                    "........",
                    "...a....",
                    "..h.....",
                    "........",
                    "h......."
                )
            ),
            currentPlayerIndex = 1
        )

        game.playBotTurn()
        val s = game.state.value!!
        assertEquals(
            "the collapsed overall move still summarizes first-hop-source -> last-hop-destination, unchanged",
            CheckersMove(5, 2, 1, 6, isCapture = true),
            s.lastMove
        )
        assertEquals(
            "lastTurnHops keeps every individual hop, in order, each with its own accurate capture square",
            listOf(
                CheckersMove(5, 2, 3, 4, isCapture = true, capRow = 4, capCol = 3),
                CheckersMove(3, 4, 1, 6, isCapture = true, capRow = 2, capCol = 5)
            ),
            s.lastTurnHops
        )
    }

    @Test
    fun `an isolated king has all 4 diagonal moves, a man only its 2 forward ones`() {
        val kingGame = newGame()
        kingGame.state.value = CheckersState(board = boardFrom(List(8) { "........" }).toMutableList().also {
            it[4 * 8 + 4] = CheckersPiece(1, PieceKind.KING)
        }, currentPlayerIndex = 1)
        assertEquals(4, kingGame.legalDestinationsFrom(4, 4).size)

        val manGame = newGame()
        manGame.state.value = CheckersState(board = boardFrom(List(8) { "........" }).toMutableList().also {
            it[4 * 8 + 4] = CheckersPiece(1, PieceKind.MAN)
        }, currentPlayerIndex = 1)
        assertEquals(setOf(3 to 3, 3 to 5), manGame.legalDestinationsFrom(4, 4))
    }

    @Test
    fun `a king can capture backward, a direction no man may jump in`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = boardFrom(
                listOf(
                    ".......a",
                    "........",
                    "........",
                    "...H....",
                    "....a...",
                    "........",
                    "........",
                    "h......."
                )
            ),
            currentPlayerIndex = 1
        )
        assertFalse("mandatory capture blocks the filler", game.hasLegalMoveFrom(7, 0))
        assertEquals(
            "the king's only legal move is the backward capture",
            setOf(5 to 5),
            game.legalDestinationsFrom(3, 3)
        )

        game.playMove(1, 3, 3, 5, 5)
        val s = game.state.value!!
        assertNull(s.pieceAt(4, 4))
        assertEquals("still a king after landing", CheckersPiece(1, PieceKind.KING), s.pieceAt(5, 5))
    }

    @Test
    fun `promotion ends a chain even though a further jump exists`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = boardFrom(
                listOf(
                    ".......a",
                    "..a.a...",
                    ".....h..",
                    "........",
                    "........",
                    "........",
                    "........",
                    "h......."
                )
            ),
            currentPlayerIndex = 1
        )
        // side 1's only piece besides the filler is at (2,5); exactly one legal move overall.
        assertFalse(game.hasLegalMoveFrom(7, 0))
        assertEquals(setOf(0 to 3), game.legalDestinationsFrom(2, 5))

        game.playMove(1, 2, 5, 0, 3)
        val s = game.state.value!!
        assertEquals(CheckersMove(2, 5, 0, 3, isCapture = true, capRow = 1, capCol = 4), s.lastMove)
        assertNull("jumped man removed", s.pieceAt(1, 4))
        assertEquals("promoted to a king on reaching row 0", CheckersPiece(1, PieceKind.KING), s.pieceAt(0, 3))
        assertEquals("the untouched piece is still right where a further jump would need it",
            CheckersPiece(0, PieceKind.MAN), s.pieceAt(1, 2))
        assertFalse("promoting mid-chain ends the turn despite a further jump existing", s.inForcedContinuation)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(2, countOwner(s.board, 0))
    }

    // ---- 2) Illegal-call rejection ----------------------------------------------------------

    @Test
    fun `playMove silently rejects an illegal call and a wrong-turn call`() {
        val game = newGame()
        game.startMatch()
        val before = game.state.value

        game.playMove(0, 5, 0, 4, 1) // side 0's turn hasn't come yet (side 1 moves first)
        assertEquals("wrong-turn call left state untouched", before, game.state.value)

        game.playMove(1, 5, 0, 3, 0) // not a real diagonal step
        assertEquals("illegal geometry left state untouched", before, game.state.value)
    }

    // ---- 3) Both loss conditions, as distinct scenarios --------------------------------------

    @Test
    fun `capturing the opponent's last piece ends the game with the capturer as winner`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = boardFrom(
                listOf(
                    "........",
                    "........",
                    "........",
                    "...H....",
                    "....a...",
                    "........",
                    "........",
                    "........"
                )
            ),
            currentPlayerIndex = 1
        )
        game.playMove(1, 3, 3, 5, 5)
        val s = game.state.value!!
        assertEquals(0, countOwner(s.board, 0))
        assertTrue(s.gameOver)
        assertEquals("p1", s.winnerPlayerId)
    }

    @Test
    fun `a side with pieces but zero legal moves loses`() {
        val game = newGame()
        game.state.value = CheckersState(
            board = boardFrom(
                listOf(
                    "a.......",
                    ".h......",
                    "..h.....",
                    "........",
                    "........",
                    "........",
                    ".......h",
                    "........"
                )
            ),
            currentPlayerIndex = 1
        )
        // side 0's only piece at (0,0) is boxed in: (1,1) is an enemy (blocks the simple
        // move) and (2,2) blocks the landing square of the only possible jump.
        game.playMove(1, 6, 7, 5, 6) // an unrelated legal move for side 1
        val s = game.state.value!!
        assertTrue("side 0 still has its piece", countOwner(s.board, 0) == 1)
        assertTrue("but zero legal moves, so the game is over", s.gameOver)
        assertEquals("p1", s.winnerPlayerId)
    }

    // ---- 4) Randomized self-play: board-integrity invariants across many games/hops ----------

    private fun assertMoveInvariants(before: CheckersState, after: CheckersState) {
        assertEquals(64, after.board.size)
        assertLightSquaresEmpty(after.board)
        assertTrue("side 0 piece count never increases", countOwner(after.board, 0) <= countOwner(before.board, 0))
        assertTrue("side 1 piece count never increases", countOwner(after.board, 1) <= countOwner(before.board, 1))
        val mv = after.lastMove
        assertNotNull("a completed move must be recorded", mv)
        assertNotNull("the moved piece must actually be at its reported destination", after.pieceAt(mv!!.toRow, mv.toCol))
    }

    @Test
    fun `bot self-play never corrupts the board across many EASY-vs-EASY games`() {
        repeat(150) {
            val game = newGame(p0Bot = true, p1Bot = true)
            game.difficulty = CpuDifficulty.EASY
            game.startMatch()
            var turns = 0
            while (turns < 200) {
                val before = game.state.value!!
                if (before.gameOver) break
                game.playBotTurn()
                assertMoveInvariants(before, game.state.value!!)
                turns++
            }
        }
    }

    @Test
    fun `bot self-play never corrupts the board across MEDIUM and HARD games`() {
        for (difficulty in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            repeat(8) {
                val game = newGame(p0Bot = true, p1Bot = true)
                game.difficulty = difficulty
                game.startMatch()
                var turns = 0
                while (turns < 120) {
                    val before = game.state.value!!
                    if (before.gameOver) break
                    game.playBotTurn()
                    assertMoveInvariants(before, game.state.value!!)
                    turns++
                }
            }
        }
    }

    @Test
    fun `manual per-hop self-play via the public playMove path respects forced continuation`() {
        repeat(60) {
            val game = newGame()
            game.startMatch()
            var hops = 0
            while (hops < 250) {
                val before = game.state.value!!
                if (before.gameOver) break
                val hops_ = allLegalHops(game)
                assertTrue("a non-game-over position must always offer a legal hop", hops_.isNotEmpty())
                // A real UI must keep re-selecting the forced square itself -- this driver does
                // exactly that by simply asking the public API for legal hops each iteration,
                // which already narrows to the forced square whenever one is active.
                val pick = hops_.random()
                game.playMove(before.currentPlayerIndex, pick.fromRow, pick.fromCol, pick.toRow, pick.toCol)
                assertMoveInvariants(before, game.state.value!!)
                hops++
            }
        }
    }

    // ---- 5) HARD-tier search timing (justifies HARD_SEARCH_DEPTH; see CheckersGame's companion KDoc) ----

    @Test
    fun `HARD bot turn timing stays within a sane per-move budget on this JVM host`() {
        val game = newGame(p0Bot = true, p1Bot = true)
        game.difficulty = CpuDifficulty.HARD
        game.startMatch()
        val timingsMs = mutableListOf<Long>()
        var turns = 0
        while (turns < 60) {
            if (game.state.value!!.gameOver) break
            val start = System.nanoTime()
            game.playBotTurn()
            timingsMs += (System.nanoTime() - start) / 1_000_000
            turns++
        }
        val avg = timingsMs.average()
        val worst = timingsMs.max()
        println("HARD bot turn timing over ${timingsMs.size} turns on this JVM host: avg=${"%.2f".format(avg)}ms, worst=${worst}ms")
        // Generous regression guard (not a tight perf target) against accidentally raising the
        // search depth far enough to make HARD noticeably laggy for a UI bot-turn delay budget.
        assertTrue("HARD bot average turn time too high: ${avg}ms", avg < 3000)
        assertTrue("HARD bot worst-case turn time too high: ${worst}ms", worst < 8000)
    }
}
