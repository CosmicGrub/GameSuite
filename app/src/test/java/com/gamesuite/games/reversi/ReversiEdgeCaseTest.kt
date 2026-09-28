package com.gamesuite.games.reversi

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.core.PlayerScore
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Hand-crafted regression tests for [ReversiGame] that complement [ReversiGameTest]. Every
 * position is drawn as an 8x8 ASCII picture ('X' = the player who is about to move, 'O' = the
 * other player, '.' = empty) and every expected outcome below was derived BY HAND from the
 * Othello flanking rule -- none of it is produced by re-running the engine's own algorithm.
 *
 * Legal moves are only computed by the engine AFTER a placement, so scenarios that need to
 * observe "is this cell legal for the next player" are structured as: hand-built position ->
 * the mover places one legal disc elsewhere -> assert the engine's freshly computed legal set.
 *
 * Direction coverage uses a symmetry argument: the Othello rule is invariant under rotating the
 * whole board by 90 degrees, so ONE hand-verified base picture for an orthogonal direction (East)
 * and ONE for a diagonal direction (South-East) are rotated 0/1/2/3 quarter turns to give all 8
 * compass directions. The fixture self-checks (in the test body) that the rotated picture really
 * puts the flanked line in the direction the test is named after.
 */
class ReversiEdgeCaseTest {

    // ---------------------------------------------------------------- fixtures

    private fun newGame(a: String = "p1", b: String = "p2"): ReversiGame {
        val game = ReversiGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(
                    PlayerInfo(playerId = a, displayName = "Player ${a.removePrefix("p")}"),
                    PlayerInfo(playerId = b, displayName = "Player ${b.removePrefix("p")}")
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    private fun newVsBotGame(): ReversiGame {
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
        game.difficulty = CpuDifficulty.EASY
        return game
    }

    private fun idx(row: Int, col: Int) = row * 8 + col
    private fun idx(cell: Pair<Int, Int>) = idx(cell.first, cell.second)

    /** In every picture 'X' is the player about to move. [swap] = false: X is player 0; true: X is player 1. */
    private fun moverOf(swap: Boolean) = if (swap) 1 else 0

    private fun parse(rows: List<String>, swap: Boolean = false): List<Int?> {
        require(rows.size == 8) { "a board picture needs exactly 8 rows" }
        return rows.flatMap { row ->
            require(row.length == 8) { "row '$row' must be 8 cells wide" }
            row.map { ch ->
                when (ch) {
                    'X' -> if (swap) 1 else 0
                    'O' -> if (swap) 0 else 1
                    '.' -> null
                    else -> error("bad picture character '$ch'")
                }
            }
        }
    }

    /** Draws cells with player 0 as 'X' and player 1 as 'O'. */
    private fun render(cells: List<Int?>): List<String> =
        (0 until 8).map { r -> (0 until 8).joinToString("") { c ->
            when (cells[r * 8 + c]) { 0 -> "X"; 1 -> "O"; else -> "." }
        } }

    /** Installs [rows] into a started [game], with X (the mover) to play and [legal] as the guard set. */
    private fun loadBoard(game: ReversiGame, rows: List<String>, swap: Boolean, legal: Set<Int>): ReversiGame {
        if (game.state.value == null) game.startMatch()
        val cells = parse(rows, swap)
        game.state.value = game.state.value!!.copy(
            cells = cells,
            currentPlayerIndex = moverOf(swap),
            legalMoves = legal,
            scores = listOf(cells.count { it == 0 }, cells.count { it == 1 }),
            lastAction = "fixture",
            justPassed = false,
            boardOver = false,
            winnerPlayerId = null
        )
        return game
    }

    private fun rotateRowsCw(rows: List<String>): List<String> =
        (0 until 8).map { r2 -> (0 until 8).joinToString("") { c2 -> rows[7 - c2][r2].toString() } }

    private fun rotateCell(cell: Pair<Int, Int>) = cell.second to (7 - cell.first)
    private fun rotateDir(d: Pair<Int, Int>) = d.second to -d.first

    private fun countDiscs(cells: List<Int?>) = cells.count { it != null }

    /** A wipeout: X at (0,2) flips the only O disc on the top row; the O side has NO discs left. Not a full board. */
    private val wipeoutRows = listOf(
        "XO......",
        "........",
        "........",
        "........",
        "........",
        ".....X..",
        "..X.....",
        "........"
    )
    private val wipeoutMove = idx(0, 2)

    /** Plays the wipeout on an already-started game so that [swap]'s X player wins the board 5-0. */
    private fun winBoard(game: ReversiGame, swap: Boolean) {
        loadBoard(game, wipeoutRows, swap, setOf(wipeoutMove))
        game.placeDisc(wipeoutMove)
        assertTrue("fixture must end the board", game.state.value!!.boardOver)
    }

    // ---------------------------------------------------------------- G1: game end on a NON-full board

    /**
     * Guards "game ends only when the board is full" and "a wipeout is not game over".
     * X at (0,2) flips (0,1); O then holds ZERO discs, so neither side can flank anything even though
     * 59 cells are empty. Hand count: X = (0,0),(0,1),(0,2),(5,5),(6,2) = 5, O = 0.
     */
    @Test
    fun `a wipeout ends the board immediately with the mover as winner even though the board is nearly empty`() {
        val game = loadBoard(newGame(), wipeoutRows, swap = false, legal = setOf(wipeoutMove))
        game.placeDisc(wipeoutMove)
        val s = game.state.value!!
        assertTrue("wipeout must end the board", s.boardOver)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(listOf(5, 0), s.scores)
        assertEquals(5, countDiscs(s.cells))
        assertTrue("board must NOT be full", countDiscs(s.cells) < 64)
        assertTrue(s.legalMoves.isEmpty())
        assertFalse(s.justPassed)
        assertEquals(mapOf("p1" to 1, "p2" to 0), game.sessionWins.value)
        assertEquals(0, game.sessionDraws.value)
    }

    /**
     * Same wipeout but the mover is player index 1: the winner id and the "score-0-first" text order
     * must follow the player indexes, not "the mover first".
     */
    @Test
    fun `a wipeout by player index 1 credits player 2 and reports the score in player-index order`() {
        val game = loadBoard(newGame(), wipeoutRows, swap = true, legal = setOf(wipeoutMove))
        game.placeDisc(wipeoutMove)
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertEquals("p2", s.winnerPlayerId)
        assertEquals(listOf(0, 5), s.scores)
        assertEquals(mapOf("p1" to 0, "p2" to 1), game.sessionWins.value)
    }

    /**
     * Neither side can move, both still hold discs, X (the mover) wins 4-2 on a 6-disc board.
     * Hand check after X places (0,2), flipping (0,1) against the anchor (0,0): X = (0,0),(0,1),(0,2),(4,4) = 4;
     * O = corners (7,0),(7,7) = 2. X cannot flank a corner disc (nothing lies beyond it); O has no contiguous
     * X run with an O anchor behind it ((4,4) and (7,7) are separated by empty (5,5),(6,6); the top-row run
     * ends at the board edge).
     */
    @Test
    fun `both sides holding discs but neither able to flank ends the board on a non-full board, mover ahead`() {
        val rows = listOf(
            "XO......", "........", "........", "........",
            "....X...", "........", "........", "O......O"
        )
        val game = loadBoard(newGame(), rows, swap = false, legal = setOf(idx(0, 2)))
        game.placeDisc(idx(0, 2))
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertFalse(s.justPassed)
        assertEquals(6, countDiscs(s.cells))
        assertEquals(listOf(4, 2), s.scores)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(mapOf("p1" to 1, "p2" to 0), game.sessionWins.value)
    }

    /**
     * The MOVER can lose a board it just moved on: X flips one disc but O's bottom-row wall keeps O ahead.
     * X = (0,0),(0,1),(0,2) = 3; O = (7,0..4) = 5. Neither side can flank: the O wall hugs the bottom edge
     * (nothing to flank past it) and the X run hugs the top-left edge.
     */
    @Test
    fun `neither side able to move ends the board with the NON-mover winning when it holds more discs`() {
        val rows = listOf(
            "XO......", "........", "........", "........",
            "........", "........", "........", "OOOOO..."
        )
        val game = loadBoard(newGame(), rows, swap = false, legal = setOf(idx(0, 2)))
        game.placeDisc(idx(0, 2))
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertEquals(8, countDiscs(s.cells))
        assertEquals(listOf(3, 5), s.scores)
        assertEquals("the non-mover holds more discs and must win", "p2", s.winnerPlayerId)
        assertEquals(mapOf("p1" to 0, "p2" to 1), game.sessionWins.value)
        assertEquals(0, game.sessionDraws.value)
    }

    /**
     * A tie on a non-full board: X = 3, O = 3 (bottom-row wall of three). Must be reported as no winner,
     * count as a draw for the session and credit neither player.
     */
    @Test
    fun `an exact tie on a non-full board has no winner, counts one draw and credits nobody`() {
        val rows = listOf(
            "XO......", "........", "........", "........",
            "........", "........", "........", "OOO....."
        )
        val game = loadBoard(newGame(), rows, swap = false, legal = setOf(idx(0, 2)))
        game.placeDisc(idx(0, 2))
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertEquals(6, countDiscs(s.cells))
        assertEquals(listOf(3, 3), s.scores)
        assertNull(s.winnerPlayerId)
        assertEquals(1, game.sessionDraws.value)
        assertEquals(mapOf("p1" to 0, "p2" to 0), game.sessionWins.value)

        // A later no-op call must not double-count the draw.
        game.placeDisc(idx(3, 3))
        game.playBotTurn()
        assertEquals(1, game.sessionDraws.value)
    }

    // ---------------------------------------------------------------- G2: no wraparound across board edges

    private class WrapCase(
        val rows: List<String>,
        val moverPlace: Pair<Int, Int>,
        val wrapCell: Pair<Int, Int>,
        val truePositive: Pair<Int, Int>,
        val trueFlips: List<Pair<Int, Int>>,
        val trapMoverDisc: Pair<Int, Int>,
        val trapOtherDisc: Pair<Int, Int>
    )

    /**
     * Shared body. Layout of every wrap picture: a small cluster where X makes a genuine move (the
     * harness), a "trap" pair (X disc, then O disc) positioned so that a FLAT-INDEX walk from [WrapCase.wrapCell]
     * would cross the row edge, see X then O and report a flank -- while the real board geometry has no such line --
     * and a mirrored genuine capture (the true positive) for the same next player. After X's harness move the
     * engine's own legal set for O must be exactly {truePositive}; the wrap cell must be absent and unplayable.
     */
    private fun assertWrapCase(c: WrapCase) {
        for (swap in listOf(false, true)) {
            val mover = moverOf(swap)
            val other = 1 - mover
            val game = loadBoard(newGame(), c.rows, swap, setOf(idx(c.moverPlace)))
            assertNull("fixture: wrap cell must be empty", game.state.value!!.cells[idx(c.wrapCell)])
            game.placeDisc(idx(c.moverPlace))

            val s = game.state.value!!
            assertFalse("harness move must not end the board", s.boardOver)
            assertFalse(s.justPassed)
            assertEquals(other, s.currentPlayerIndex)
            assertEquals("the ONLY legal cell for the next player is the genuine capture", setOf(idx(c.truePositive)), s.legalMoves)
            assertFalse("wrap cell must not be legal", idx(c.wrapCell) in s.legalMoves)

            val before = game.state.value
            game.placeDisc(idx(c.wrapCell))
            assertEquals("placing on the wrap cell must be a total no-op", before, game.state.value)

            game.placeDisc(idx(c.truePositive))
            val after = game.state.value!!
            assertEquals(other, after.cells[idx(c.truePositive)])
            for (f in c.trueFlips) assertEquals("genuine edge/corner capture must flip $f", other, after.cells[idx(f)])
            assertEquals("trap disc of the mover must be untouched", mover, after.cells[idx(c.trapMoverDisc)])
            assertEquals("trap disc of the other player must be untouched", other, after.cells[idx(c.trapOtherDisc)])
        }
    }

    /**
     * LEFT-edge wrap. Trap: (2,7)=X,(2,6)=O; flat walk West from (3,0) is idx 24 -> 23=(2,7)=X -> 22=(2,6)=O.
     * Hand derivation of O's real moves after X plays (4,5) (flipping (4,4) against (4,3)):
     * O anchors are (2,6) and (6,2). (2,6)'s only adjacent X is (2,7), beyond which is off-board -> nothing.
     * (6,2)'s adjacent X is (6,1) with EMPTY (6,0) beyond -> (6,0) legal (a real left-edge capture flipping (6,1)).
     * Nothing else touches an X run. Expected legal set = {(6,0)}.
     */
    @Test
    fun `left edge - a flat-index walk would wrap from column 0 into the previous row but the engine must not`() {
        assertWrapCase(WrapCase(
            rows = listOf(
                "........",
                "........",
                "......OX",
                "........",
                "...XO...",
                "........",
                ".XO.....",
                "........"
            ),
            moverPlace = 4 to 5, wrapCell = 3 to 0, truePositive = 6 to 0,
            trueFlips = listOf(6 to 1), trapMoverDisc = 2 to 7, trapOtherDisc = 2 to 6
        ))
    }

    /**
     * RIGHT-edge wrap. Trap: (4,0)=X,(4,1)=O; flat walk East from (3,7) is idx 31 -> 32=(4,0)=X -> 33=(4,1)=O.
     * After X plays (1,5) (flipping (1,4) against (1,3)): O anchors (4,1) and (6,5).
     * (4,1)'s only adjacent X is (4,0), off-board beyond -> nothing. (6,5)'s adjacent X is (6,6) with empty
     * (6,7) beyond -> (6,7) legal (real right-edge capture flipping (6,6)). Expected = {(6,7)}.
     */
    @Test
    fun `right edge - a flat-index walk would wrap from column 7 into the next row but the engine must not`() {
        assertWrapCase(WrapCase(
            rows = listOf(
                "........",
                "...XO...",
                "........",
                "........",
                "XO......",
                "........",
                ".....OX.",
                "........"
            ),
            moverPlace = 1 to 5, wrapCell = 3 to 7, truePositive = 6 to 7,
            trueFlips = listOf(6 to 6), trapMoverDisc = 4 to 0, trapOtherDisc = 4 to 1
        ))
    }

    /**
     * DOWN-RIGHT diagonal wrap from column 7. Trap: (4,0)=X,(5,1)=O; flat +9 from (2,7)=idx 23 lands on 32=(4,0)
     * then 41=(5,1). After X plays (7,5) (flipping (7,4) against (7,3)): O anchors (2,2) and (5,1).
     * (5,1)'s adjacent X (4,0) has nothing beyond it (off-board). (2,2)'s adjacent X (1,1) has empty (0,0)
     * beyond -> (0,0) legal (real corner capture flipping (1,1)). Expected = {(0,0)}.
     */
    @Test
    fun `down-right diagonal - a flat-index walk from column 7 would wrap to column 0 two rows down but the engine must not`() {
        assertWrapCase(WrapCase(
            rows = listOf(
                "........",
                ".X......",
                "..O.....",
                "........",
                "X.......",
                ".O......",
                "........",
                "...XO..."
            ),
            moverPlace = 7 to 5, wrapCell = 2 to 7, truePositive = 0 to 0,
            trueFlips = listOf(1 to 1), trapMoverDisc = 4 to 0, trapOtherDisc = 5 to 1
        ))
    }

    /**
     * UP-LEFT diagonal wrap from column 0. Trap: (3,7)=X,(2,6)=O; flat -9 from (5,0)=idx 40 lands on 31=(3,7)
     * then 22=(2,6). After X plays (0,5) (flipping (0,4) against (0,3)): O anchors (2,6) and (5,5).
     * (2,6)'s adjacent X (3,7) has nothing beyond. (5,5)'s adjacent X (6,6) has empty (7,7) beyond ->
     * (7,7) legal (real corner capture flipping (6,6)). Expected = {(7,7)}.
     */
    @Test
    fun `up-left diagonal - a flat-index walk from column 0 would wrap to column 7 two rows up but the engine must not`() {
        assertWrapCase(WrapCase(
            rows = listOf(
                "...XO...",
                "........",
                "......O.",
                ".......X",
                "........",
                ".....O..",
                "......X.",
                "........"
            ),
            moverPlace = 0 to 5, wrapCell = 5 to 0, truePositive = 7 to 7,
            trueFlips = listOf(6 to 6), trapMoverDisc = 3 to 7, trapOtherDisc = 2 to 6
        ))
    }

    /**
     * DOWN-LEFT diagonal wrap from column 0. Trap: (3,7)=X,(4,6)=O; flat +7 from (3,0)=idx 24 lands on
     * 31=(3,7) then 38=(4,6). After X plays (7,5) (flipping (7,4) against (7,3)): O anchors (2,5) and (4,6).
     * (4,6)'s adjacent X (3,7) has nothing beyond. (2,5)'s adjacent X (1,6) has empty (0,7) beyond ->
     * (0,7) legal (real corner capture flipping (1,6)). Expected = {(0,7)}.
     */
    @Test
    fun `down-left diagonal - a flat-index walk from column 0 would wrap to column 7 of the same row but the engine must not`() {
        assertWrapCase(WrapCase(
            rows = listOf(
                "........",
                "......X.",
                ".....O..",
                ".......X",
                "......O.",
                "........",
                "........",
                "...XO..."
            ),
            moverPlace = 7 to 5, wrapCell = 3 to 0, truePositive = 0 to 7,
            trueFlips = listOf(1 to 6), trapMoverDisc = 3 to 7, trapOtherDisc = 4 to 6
        ))
    }

    /**
     * UP-RIGHT diagonal wrap from column 7. Trap: (4,0)=X,(3,1)=O; flat -7 from (4,7)=idx 39 lands on
     * 32=(4,0) then 25=(3,1). After X plays (1,5) (flipping (1,4) against (1,3)): O anchors (3,1) and (5,2).
     * (3,1)'s adjacent X (4,0) has nothing beyond. (5,2)'s adjacent X (6,1) has empty (7,0) beyond ->
     * (7,0) legal (real corner capture flipping (6,1)). Expected = {(7,0)}.
     */
    @Test
    fun `up-right diagonal - a flat-index walk from column 7 would wrap to column 0 of the same row but the engine must not`() {
        assertWrapCase(WrapCase(
            rows = listOf(
                "........",
                "...XO...",
                "........",
                ".O......",
                "X.......",
                "..O.....",
                ".X......",
                "........"
            ),
            moverPlace = 1 to 5, wrapCell = 4 to 7, truePositive = 7 to 0,
            trueFlips = listOf(6 to 1), trapMoverDisc = 4 to 0, trapOtherDisc = 3 to 1
        ))
    }

    /**
     * A flank whose anchor sits in the LAST row/column and whose origin sits in the FIRST guards
     * off-by-one bound checks (e.g. `r in 0..6`). X(3,0) + six O discs + placement on (3,7): all 6 flip.
     */
    @Test
    fun `an edge-to-edge row capture flips all six discs between the two edge cells`() {
        val rows = listOf("........", "........", "........", "XOOOOOO.", "........", "........", "........", "........")
        val game = loadBoard(newGame(), rows, swap = false, legal = setOf(idx(3, 7)))
        game.placeDisc(idx(3, 7))
        assertEquals("XXXXXXXX", render(game.state.value!!.cells)[3])
        assertEquals(listOf(8, 0), game.state.value!!.scores)
    }

    /** Same as above but a full-height column (column 7, rows 0..7), i.e. the same picture rotated a quarter turn. */
    @Test
    fun `an edge-to-edge column capture flips all six discs between the two edge cells`() {
        val rows = rotateRowsCw(listOf("........", "........", "........", "XOOOOOO.", "........", "........", "........", "........"))
        // Row 3 col c goes to (c, 4): the column is col 4, X anchor at (0,4), placement at (7,4).
        val game = loadBoard(newGame(), rows, swap = false, legal = setOf(idx(7, 4)))
        game.placeDisc(idx(7, 4))
        val cells = game.state.value!!.cells
        assertEquals(8, (0 until 8).count { cells[idx(it, 4)] == 0 })
        assertEquals(listOf(8, 0), game.state.value!!.scores)
    }

    /**
     * Mirror of the edge-to-edge row capture: the ANCHOR now sits in the last column (7) and the
     * placement in the first (0), so a walk bound of `c in 0..6` (or `r in 0..6` in the rotated column
     * variant below) would miss the anchor and report no flank.
     */
    @Test
    fun `an edge-to-edge capture anchored in the last column or last row still flips all six discs`() {
        val rowRows = listOf("........", "........", "........", ".OOOOOOX", "........", "........", "........", "........")
        val rowGame = loadBoard(newGame(), rowRows, swap = false, legal = setOf(idx(3, 0)))
        rowGame.placeDisc(idx(3, 0))
        assertEquals("XXXXXXXX", render(rowGame.state.value!!.cells)[3])

        // Rotated a quarter turn: the row becomes column 4 with the anchor at (7,4) and placement at (0,4).
        val colRows = rotateRowsCw(rowRows)
        val colGame = loadBoard(newGame(), colRows, swap = false, legal = setOf(idx(0, 4)))
        colGame.placeDisc(idx(0, 4))
        val cells = colGame.state.value!!.cells
        assertEquals(8, (0 until 8).count { cells[idx(it, 4)] == 0 })
        assertEquals(listOf(8, 0), colGame.state.value!!.scores)
    }

    /** The full main diagonal: X(0,0), O(1,1)..(6,6), placement at the far corner (7,7). */
    @Test
    fun `a corner-to-corner main diagonal capture flips the six interior discs`() {
        val rows = listOf("X.......", ".O......", "..O.....", "...O....", "....O...", ".....O..", "......O.", "........")
        val game = loadBoard(newGame(), rows, swap = false, legal = setOf(idx(7, 7)))
        game.placeDisc(idx(7, 7))
        val cells = game.state.value!!.cells
        assertTrue((0 until 8).all { cells[idx(it, it)] == 0 })
        assertEquals(listOf(8, 0), game.state.value!!.scores)
    }

    /** The full anti-diagonal: X(0,7), O(1,6)..(6,1), placement at the far corner (7,0). */
    @Test
    fun `a corner-to-corner anti-diagonal capture flips the six interior discs`() {
        val rows = listOf(".......X", "......O.", ".....O..", "....O...", "...O....", "..O.....", ".O......", "........")
        val game = loadBoard(newGame(), rows, swap = true, legal = setOf(idx(7, 0)))
        game.placeDisc(idx(7, 0))
        val cells = game.state.value!!.cells
        assertTrue((0 until 8).all { cells[idx(it, 7 - it)] == 1 })
        assertEquals(listOf(0, 8), game.state.value!!.scores)
    }

    // ---------------------------------------------------------------- G3: all 8 compass directions

    private class CaptureCase(val flips: Int, val before: List<String>, val after: List<String>)

    /**
     * Base picture for EAST (dr=0, dc=+1), origin (3,2), flips of 1/2/3 discs. Each picture also holds
     * two decoy O discs adjacent to the origin whose lines dead-end in an empty cell -- (2,2) going North
     * (empty (1,2) beyond) and (4,3) going South-East (empty (5,4) beyond) -- which must NOT flip, and
     * discs BEYOND the anchor which must not flip either.
     */
    private val eastCases = listOf(
        CaptureCase(
            1,
            listOf("........", "........", "..O.....", "...OX.OX", "...O....", "........", "........", "........"),
            listOf("........", "........", "..O.....", "..XXX.OX", "...O....", "........", "........", "........")
        ),
        CaptureCase(
            2,
            listOf("........", "........", "..O.....", "...OOXOX", "...O....", "........", "........", "........"),
            listOf("........", "........", "..O.....", "..XXXXOX", "...O....", "........", "........", "........")
        ),
        CaptureCase(
            3,
            listOf("........", "........", "..O.....", "...OOOXO", "...O....", "........", "........", "........"),
            listOf("........", "........", "..O.....", "..XXXXXO", "...O....", "........", "........", "........")
        )
    )
    private val eastOrigin = 3 to 2

    /**
     * Base picture for SOUTH-EAST (dr=+1, dc=+1), origin (2,2), flips of 1/2/3 discs, with decoys at
     * (1,3) (going North-East, empty (0,4) beyond) and (3,1) (going South-West, empty (4,0) beyond),
     * and discs beyond the anchor on the same diagonal that must stay untouched.
     */
    private val southEastCases = listOf(
        CaptureCase(
            1,
            listOf("........", "...O....", "........", ".O.O....", "....X...", "........", "......O.", ".......X"),
            listOf("........", "...O....", "..X.....", ".O.X....", "....X...", "........", "......O.", ".......X")
        ),
        CaptureCase(
            2,
            listOf("........", "...O....", "........", ".O.O....", "....O...", ".....X..", "......O.", ".......X"),
            listOf("........", "...O....", "..X.....", ".O.X....", "....X...", ".....X..", "......O.", ".......X")
        ),
        CaptureCase(
            3,
            listOf("........", "...O....", "........", ".O.O....", "....O...", ".....O..", "......X.", ".......O"),
            listOf("........", "...O....", "..X.....", ".O.X....", "....X...", ".....X..", "......X.", ".......O")
        )
    )
    private val southEastOrigin = 2 to 2

    /**
     * Places at the (rotated) origin for 1, 2 and 3 flanked discs, for either mover, and checks that the
     * board equals the hand-drawn "after" picture: the flanked line and only that line flipped.
     */
    private fun assertCaptureInDirection(
        cases: List<CaptureCase>, baseOrigin: Pair<Int, Int>, baseDir: Pair<Int, Int>,
        quarterTurns: Int, expectedDir: Pair<Int, Int>
    ) {
        var dir = baseDir
        repeat(quarterTurns) { dir = rotateDir(dir) }
        assertEquals("fixture rotation must land on the direction this test is named after", expectedDir, dir)

        for (case in cases) for (swap in listOf(false, true)) {
            var before = case.before
            var after = case.after
            var origin = baseOrigin
            repeat(quarterTurns) { before = rotateRowsCw(before); after = rotateRowsCw(after); origin = rotateCell(origin) }
            val beforeCells = parse(before, swap)
            val afterCells = parse(after, swap)
            val originIdx = idx(origin)

            // Fixture self-check: exactly the origin plus `flips` cells along `dir` change between the pictures.
            val expectedChanged = (0..case.flips).map { idx(origin.first + dir.first * it, origin.second + dir.second * it) }.toSet()
            val changed = beforeCells.indices.filter { beforeCells[it] != afterCells[it] }.toSet()
            assertEquals("fixture: changed cells must be the origin and the flanked line", expectedChanged, changed)
            assertNull("fixture: origin must be empty", beforeCells[originIdx])

            val game = loadBoard(newGame(), before, swap, setOf(originIdx))
            game.placeDisc(originIdx)
            val s = game.state.value!!
            assertEquals("dir=$expectedDir flips=${case.flips} swap=$swap", afterCells, s.cells)
            assertEquals(afterCells.count { it == 0 }, s.scores[0])
            assertEquals(afterCells.count { it == 1 }, s.scores[1])
        }
    }

    private class NegCase(
        val rows: List<String>, val origin: Pair<Int, Int>, val harness: Pair<Int, Int>,
        val truePositive: Pair<Int, Int>, val runLen: Int, val runsOffBoard: Boolean
    )

    /**
     * EAST dead-end/edge negatives, viewed from the NEXT player (O). Common furniture in both pictures:
     * harness X(0,3)+O(0,4) so X's move (0,5) is genuine (flips (0,4)); true positive: O(6,1),X(6,2) so O may
     * play (6,3). Case A: origin (3,2), X run (3,3),(3,4), then EMPTY (3,5), then an O disc at (3,6) (a
     * gap-skipping walk would wrongly find an anchor). Case B: origin (3,4), X run (3,5),(3,6),(3,7) reaching
     * the board edge with no anchor. Hand derivation for both: after X's harness move the only O anchors are
     * (6,1) [plus (3,6) in A, which touches no X]; (6,1)'s adjacent X (6,2) has empty (6,3) beyond -> {(6,3)}.
     * The origin sees only X-run-then-empty / X-run-then-edge, so it is NOT legal.
     */
    private val eastNegatives = listOf(
        NegCase(
            rows = listOf("...XO...", "........", "........", "...XX.O.", "........", "........", ".OX.....", "........"),
            origin = 3 to 2, harness = 0 to 5, truePositive = 6 to 3, runLen = 2, runsOffBoard = false
        ),
        NegCase(
            rows = listOf("...XO...", "........", "........", ".....XXX", "........", "........", ".OX.....", "........"),
            origin = 3 to 4, harness = 0 to 5, truePositive = 6 to 3, runLen = 3, runsOffBoard = true
        )
    )

    /**
     * SOUTH-EAST dead-end/edge negatives, viewed from the NEXT player (O). Harness as above (X plays (0,5)).
     * True positive: O(5,0), X(6,1) so O may play (7,2) (a diagonal capture). Case A: origin (2,2), X run
     * (3,3),(4,4), EMPTY (5,5), then O at (6,6). Case B: origin (4,4), X run (5,5),(6,6),(7,7) reaching the
     * corner with no anchor. After the harness move O's only anchors are (5,0) [and (6,6) in A, touching no X];
     * (5,0)'s adjacent X (6,1) has empty (7,2) beyond -> {(7,2)}.
     */
    private val southEastNegatives = listOf(
        NegCase(
            rows = listOf("...XO...", "........", "........", "...X....", "....X...", "O.......", ".X....O.", "........"),
            origin = 2 to 2, harness = 0 to 5, truePositive = 7 to 2, runLen = 2, runsOffBoard = false
        ),
        NegCase(
            rows = listOf("...XO...", "........", "........", "........", "........", "O....X..", ".X....X.", ".......X"),
            origin = 4 to 4, harness = 0 to 5, truePositive = 7 to 2, runLen = 3, runsOffBoard = true
        )
    )

    private fun assertNoCaptureInDirection(
        cases: List<NegCase>, baseDir: Pair<Int, Int>, quarterTurns: Int, expectedDir: Pair<Int, Int>
    ) {
        var dir = baseDir
        repeat(quarterTurns) { dir = rotateDir(dir) }
        assertEquals("fixture rotation must land on the direction this test is named after", expectedDir, dir)

        for (case in cases) for (swap in listOf(false, true)) {
            val mover = moverOf(swap)
            val other = 1 - mover
            var rows = case.rows
            var origin = case.origin
            var harness = case.harness
            var tp = case.truePositive
            repeat(quarterTurns) {
                rows = rotateRowsCw(rows); origin = rotateCell(origin); harness = rotateCell(harness); tp = rotateCell(tp)
            }
            val cells = parse(rows, swap)
            // Fixture self-check: the flanked-looking run really points along `dir` from the origin and dead-ends.
            for (i in 1..case.runLen) {
                assertEquals("fixture: run cell $i", mover, cells[idx(origin.first + dir.first * i, origin.second + dir.second * i)])
            }
            val br = origin.first + dir.first * (case.runLen + 1)
            val bc = origin.second + dir.second * (case.runLen + 1)
            if (case.runsOffBoard) assertTrue("fixture: run must reach the board edge", br !in 0..7 || bc !in 0..7)
            else assertNull("fixture: run must dead-end in an empty cell", cells[idx(br, bc)])

            val game = loadBoard(newGame(), rows, swap, setOf(idx(harness)))
            game.placeDisc(idx(harness))
            val s = game.state.value!!
            assertFalse(s.boardOver)
            assertFalse(s.justPassed)
            assertEquals(other, s.currentPlayerIndex)
            assertEquals("dir=$expectedDir edge=${case.runsOffBoard} swap=$swap", setOf(idx(tp)), s.legalMoves)
            assertFalse("origin must not be legal", idx(origin) in s.legalMoves)

            val snapshot = game.state.value
            game.placeDisc(idx(origin))
            assertEquals("placing on the dead-end origin must be a total no-op", snapshot, game.state.value)

            game.placeDisc(idx(tp))
            assertEquals("the genuine capture must still work", other, game.state.value!!.cells[idx(tp)])
        }
    }

    @Test
    fun `direction East - placing flips exactly the flanked line for 1, 2 and 3 discs for either mover`() =
        assertCaptureInDirection(eastCases, eastOrigin, 0 to 1, 0, 0 to 1)

    @Test
    fun `direction South - placing flips exactly the flanked line for 1, 2 and 3 discs for either mover`() =
        assertCaptureInDirection(eastCases, eastOrigin, 0 to 1, 1, 1 to 0)

    @Test
    fun `direction West - placing flips exactly the flanked line for 1, 2 and 3 discs for either mover`() =
        assertCaptureInDirection(eastCases, eastOrigin, 0 to 1, 2, 0 to -1)

    @Test
    fun `direction North - placing flips exactly the flanked line for 1, 2 and 3 discs for either mover`() =
        assertCaptureInDirection(eastCases, eastOrigin, 0 to 1, 3, -1 to 0)

    @Test
    fun `direction South-East - placing flips exactly the flanked line for 1, 2 and 3 discs for either mover`() =
        assertCaptureInDirection(southEastCases, southEastOrigin, 1 to 1, 0, 1 to 1)

    @Test
    fun `direction South-West - placing flips exactly the flanked line for 1, 2 and 3 discs for either mover`() =
        assertCaptureInDirection(southEastCases, southEastOrigin, 1 to 1, 1, 1 to -1)

    @Test
    fun `direction North-West - placing flips exactly the flanked line for 1, 2 and 3 discs for either mover`() =
        assertCaptureInDirection(southEastCases, southEastOrigin, 1 to 1, 2, -1 to -1)

    @Test
    fun `direction North-East - placing flips exactly the flanked line for 1, 2 and 3 discs for either mover`() =
        assertCaptureInDirection(southEastCases, southEastOrigin, 1 to 1, 3, -1 to 1)

    @Test
    fun `direction East - a line dead-ending in an empty cell or running off the board is not legal and flips nothing`() =
        assertNoCaptureInDirection(eastNegatives, 0 to 1, 0, 0 to 1)

    @Test
    fun `direction South - a line dead-ending in an empty cell or running off the board is not legal and flips nothing`() =
        assertNoCaptureInDirection(eastNegatives, 0 to 1, 1, 1 to 0)

    @Test
    fun `direction West - a line dead-ending in an empty cell or running off the board is not legal and flips nothing`() =
        assertNoCaptureInDirection(eastNegatives, 0 to 1, 2, 0 to -1)

    @Test
    fun `direction North - a line dead-ending in an empty cell or running off the board is not legal and flips nothing`() =
        assertNoCaptureInDirection(eastNegatives, 0 to 1, 3, -1 to 0)

    @Test
    fun `direction South-East - a line dead-ending in an empty cell or running off the board is not legal and flips nothing`() =
        assertNoCaptureInDirection(southEastNegatives, 1 to 1, 0, 1 to 1)

    @Test
    fun `direction South-West - a line dead-ending in an empty cell or running off the board is not legal and flips nothing`() =
        assertNoCaptureInDirection(southEastNegatives, 1 to 1, 1, 1 to -1)

    @Test
    fun `direction North-West - a line dead-ending in an empty cell or running off the board is not legal and flips nothing`() =
        assertNoCaptureInDirection(southEastNegatives, 1 to 1, 2, -1 to -1)

    @Test
    fun `direction North-East - a line dead-ending in an empty cell or running off the board is not legal and flips nothing`() =
        assertNoCaptureInDirection(southEastNegatives, 1 to 1, 3, -1 to 1)

    // ---------------------------------------------------------------- own tests: no-ops and guards

    /**
     * Guards the `boardOver` early-return in placeDisc: the state is forced to carry a non-empty legal set
     * AFTER the board ended (an empty legal set alone would already reject every index, hiding a removed
     * guard). Also: no index at all may change a finished board, and wins are counted exactly once.
     *
     * Scope note: the `boardOver` and `matchOver` checks inside playBotTurn are NOT independently guarded
     * by this test. They are masked defence-in-depth: on a finished board the legal set is empty, so
     * chooseBotMove returns null, and even with a forced non-empty set playBotTurn hands the chosen cell to
     * placeDisc, whose own boardOver/matchOver guard rejects it. Only the placeDisc guard is decisive here
     * (removing playBotTurn's copies is an equivalent mutant), so that is what the assertions pin.
     */
    @Test
    fun `placeDisc and playBotTurn are total no-ops after the board is over and wins are not recounted`() {
        val game = newVsBotGame()
        game.startMatch()
        loadBoard(game, wipeoutRows, swap = true, legal = setOf(wipeoutMove)) // the BOT (index 1) is the mover and wins
        game.placeDisc(wipeoutMove)
        val over = game.state.value!!
        assertTrue(over.boardOver)
        assertEquals("cpu", over.winnerPlayerId)
        assertEquals(1, over.currentPlayerIndex)
        assertEquals(mapOf("you" to 0, "cpu" to 1), game.sessionWins.value)

        for (i in 0 until 64) game.placeDisc(i)
        game.playBotTurn()
        assertEquals(over, game.state.value)

        // Force a "legal" empty cell onto the finished state: the boardOver guard alone must reject it.
        game.state.value = over.copy(legalMoves = setOf(idx(3, 3)))
        val forced = game.state.value
        game.placeDisc(idx(3, 3))
        game.playBotTurn()
        assertEquals(forced, game.state.value)
        assertEquals("a finished board must be credited exactly once", mapOf("you" to 0, "cpu" to 1), game.sessionWins.value)
        assertEquals(0, game.sessionDraws.value)
    }

    /** Before any startMatch the state is null; both entry points must simply return. */
    @Test
    fun `placeDisc and playBotTurn before startMatch do nothing and do not crash`() {
        val game = newVsBotGame()
        game.placeDisc(0)
        game.placeDisc(-1)
        game.playBotTurn()
        assertNull(game.state.value)
    }

    /** Guards the `isBot` check in playBotTurn: on a human turn the bot entry point must not touch anything. */
    @Test
    fun `playBotTurn on a human turn is a no-op and on a bot turn plays exactly one disc`() {
        val game = newVsBotGame()
        game.startMatch()
        val opening = game.state.value!!
        assertEquals(0, opening.currentPlayerIndex)
        game.playBotTurn()
        assertEquals("human to move: bot entry point must do nothing", opening, game.state.value)

        game.placeDisc(opening.legalMoves.min())
        val afterHuman = game.state.value!!
        assertEquals(1, afterHuman.currentPlayerIndex)
        game.playBotTurn()
        val afterBot = game.state.value!!
        assertEquals(countDiscs(afterHuman.cells) + 1, countDiscs(afterBot.cells))
        assertEquals("control returns to the human", 0, afterBot.currentPlayerIndex)
        game.playBotTurn()
        assertEquals("human to move again: still a no-op", afterBot, game.state.value)
    }

    /** With two human players playBotTurn can never act, whoever is to move. */
    @Test
    fun `playBotTurn with two human players never acts`() {
        val game = newGame()
        game.startMatch()
        val before = game.state.value
        game.playBotTurn()
        assertEquals(before, game.state.value)
    }

    /**
     * Guards the legality guard in placeDisc for every kind of bad index: negative, past the end, huge,
     * occupied, empty-but-not-flanking. Each must leave the whole state (turn, legal set, scores) untouched.
     */
    @Test
    fun `illegal, occupied and out-of-range indexes are total no-ops`() {
        val game = newGame()
        game.startMatch()
        val before = game.state.value
        val bad = listOf(
            -1, -8, -64, 64, 65, 100, 1000, Int.MAX_VALUE, Int.MIN_VALUE, // out of range
            idx(3, 3), idx(3, 4), idx(4, 3), idx(4, 4),                    // occupied centre cells
            idx(0, 0), idx(7, 7), idx(2, 2), idx(5, 5), idx(2, 4), idx(4, 2) // empty but no flank for player 0
        )
        for (i in bad) {
            game.placeDisc(i)
            assertEquals("index $i must be rejected", before, game.state.value)
        }
    }

    /**
     * A placement must publish a NEW cells list: the previous state snapshot (which Compose may still be
     * diffing) must keep its old contents. Guards an in-place mutation of the shared list.
     */
    @Test
    fun `a placement leaves the previous state snapshot's cells untouched`() {
        val game = newGame()
        game.startMatch()
        val prev = game.state.value!!
        val prevCells = prev.cells.toList()
        game.placeDisc(idx(2, 3))
        assertEquals(prevCells, prev.cells)
        assertTrue(game.state.value!!.cells != prev.cells)
    }

    // ---------------------------------------------------------------- own tests: session tallies

    /**
     * Guards the alternate-starter idiom, tally persistence and board reset across playAgain.
     * Board 1 (player 0 starts): p1 wins. Board 2 must start with player 1 to move on the mirrored opening.
     * Board 3 (player 0 starts again): p2's win on board 2 and p1's on board 3 are both retained.
     */
    @Test
    fun `playAgain keeps the tallies, resets the board and alternates who opens`() {
        val game = newGame()
        game.startMatch()
        val openingRows = listOf("........", "........", "........", "...OX...", "...XO...", "........", "........", "........")
        assertEquals(openingRows, render(game.state.value!!.cells))
        assertEquals(0, game.state.value!!.currentPlayerIndex)

        winBoard(game, swap = false)
        assertEquals(mapOf("p1" to 1, "p2" to 0), game.sessionWins.value)

        game.playAgain()
        val second = game.state.value!!
        assertFalse(second.boardOver)
        assertNull(second.winnerPlayerId)
        assertEquals("second board is opened by player index 1", 1, second.currentPlayerIndex)
        assertEquals(listOf(2, 2), second.scores)
        assertEquals("cells reset to the opening with the roles mirrored",
            listOf("........", "........", "........", "...XO...", "...OX...", "........", "........", "........"),
            render(second.cells))
        assertEquals(setOf(idx(2, 3), idx(3, 2), idx(4, 5), idx(5, 4)), second.legalMoves)
        assertEquals("tally survives playAgain", mapOf("p1" to 1, "p2" to 0), game.sessionWins.value)
        assertFalse(game.matchOver.value)

        winBoard(game, swap = true)
        assertEquals(mapOf("p1" to 1, "p2" to 1), game.sessionWins.value)

        game.playAgain()
        val third = game.state.value!!
        assertEquals("third board is opened by player 0 again", 0, third.currentPlayerIndex)
        assertEquals(openingRows, render(third.cells))
        winBoard(game, swap = false)
        assertEquals(mapOf("p1" to 2, "p2" to 1), game.sessionWins.value)
        assertEquals(0, game.sessionDraws.value)
    }

    /**
     * onMatchEnd must fire exactly once with the cumulative boards-won tally as scores; a second
     * leaveSession and a playAgain after leaving must both be inert (guards the two `matchOver` early-returns).
     */
    @Test
    fun `leaveSession reports the tally once, a second leave does not refire and playAgain afterwards is inert`() {
        val game = newGame()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()
        winBoard(game, swap = false); game.playAgain()
        winBoard(game, swap = false); game.playAgain()
        winBoard(game, swap = true)
        assertEquals(mapOf("p1" to 2, "p2" to 1), game.sessionWins.value)
        assertTrue("no callback before the session ends", results.isEmpty())

        game.leaveSession()
        assertTrue(game.matchOver.value)
        assertEquals(1, results.size)
        assertFalse(results[0].wasAborted)
        assertEquals(
            listOf(PlayerScore("p1", 2, true), PlayerScore("p2", 1, false)),
            results[0].scores
        )

        game.leaveSession()
        assertEquals("a second leaveSession must not fire the callback again", 1, results.size)

        val stateAtLeave = game.state.value
        game.playAgain()
        assertEquals("playAgain after leaveSession must not deal a new board", stateAtLeave, game.state.value)
        assertTrue(game.matchOver.value)
        assertEquals(1, results.size)
    }

    /** Equal positive win counts make BOTH players winners; zero wins for everyone makes NOBODY a winner. */
    @Test
    fun `leaveSession marks tied leaders as winners but never marks anyone when no board was won`() {
        val tiedGame = newGame()
        val tied = mutableListOf<GameResult>()
        tiedGame.setOnMatchEnd { tied += it }
        tiedGame.startMatch()
        winBoard(tiedGame, swap = false); tiedGame.playAgain()
        winBoard(tiedGame, swap = true)
        tiedGame.leaveSession()
        assertEquals(listOf(PlayerScore("p1", 1, true), PlayerScore("p2", 1, true)), tied.single().scores)

        val emptyGame = newGame()
        val none = mutableListOf<GameResult>()
        emptyGame.setOnMatchEnd { none += it }
        emptyGame.startMatch()
        emptyGame.leaveSession()
        assertEquals(listOf(PlayerScore("p1", 0, false), PlayerScore("p2", 0, false)), none.single().scores)
    }

    /**
     * init() must wipe everything session-scoped: wins keyed to the NEW players at 0, draws 0,
     * matchOver false and the alternating-starter counter back to 0 (a fresh session opens with player 0
     * even if the previous session had advanced it).
     */
    @Test
    fun `init resets wins, draws, matchOver and the starter rotation for a fresh session`() {
        val game = newGame()
        game.startMatch()
        winBoard(game, swap = false)
        game.playAgain()
        // Tie board -> one draw on the books.
        loadBoard(
            game,
            listOf("XO......", "........", "........", "........", "........", "........", "........", "OOO....."),
            swap = false, legal = setOf(idx(0, 2))
        )
        game.placeDisc(idx(0, 2))
        assertEquals(1, game.sessionDraws.value)
        // Three boards dealt so far (odd), so the starter rotation currently points at player 1.
        game.playAgain()
        game.leaveSession()
        assertTrue(game.matchOver.value)

        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(PlayerInfo("a", "Ann"), PlayerInfo("b", "Bob")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        assertFalse(game.matchOver.value)
        assertEquals(mapOf("a" to 0, "b" to 0), game.sessionWins.value)
        assertEquals(0, game.sessionDraws.value)

        game.startMatch()
        val s = game.state.value!!
        assertEquals("fresh session opens with player index 0", 0, s.currentPlayerIndex)
        assertEquals(listOf("Ann", "Bob"), s.players.map { it.displayName })
    }

    // ---------------------------------------------------------------- own tests: passing

    /**
     * Two consecutive passes, then a natural game end. Pictures: three independent left-edge clusters where
     * X (edge disc) flanks O discs from the right: row0 XO, row4 XOO, row7 XOOO. X plays (0,2). Every O disc
     * left touches only the board edge / X anchors, so O never has a move (hand-checked: the only X discs
     * adjacent to any O disc are the edge anchors, and beyond them is off-board), while X keeps one cluster
     * per turn: legal for X = {(4,3),(7,4)} then {(7,4)}. After the third X move O has zero discs: game over.
     * Discs: X after move 1 = 3 + (4,0) + (7,0) = 5, O = 5; after move 2 X = 8, O = 3; final X = 12, O = 0.
     */
    @Test
    fun `the mover moves several times in a row while the opponent has no move, each time flagged as a pass, then the board ends`() {
        val rows = listOf("XO......", "........", "........", "........", "XOO.....", "........", "........", "XOOO....")
        val game = loadBoard(newGame(), rows, swap = false, legal = setOf(idx(0, 2)))

        game.placeDisc(idx(0, 2))
        var s = game.state.value!!
        assertTrue("first pass", s.justPassed)
        assertFalse(s.boardOver)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(setOf(idx(4, 3), idx(7, 4)), s.legalMoves)
        assertEquals(listOf(5, 5), s.scores)

        game.placeDisc(idx(4, 3))
        s = game.state.value!!
        assertTrue("second pass", s.justPassed)
        assertFalse(s.boardOver)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(setOf(idx(7, 4)), s.legalMoves)
        assertEquals(listOf(8, 3), s.scores)
        assertEquals("passing does not touch the session tally", mapOf("p1" to 0, "p2" to 0), game.sessionWins.value)

        game.placeDisc(idx(7, 4))
        s = game.state.value!!
        assertTrue(s.boardOver)
        assertFalse("a finished board is not a pass", s.justPassed)
        assertEquals(listOf(12, 0), s.scores)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(mapOf("p1" to 1, "p2" to 0), game.sessionWins.value)
    }

    // ---------------------------------------------------------------- own tests: UI-facing messages

    /**
     * The ONE place that pins the exact UI-facing `lastAction` wording (placement with a flip count,
     * pass, decisive result in player-index score order for either winner, tie). Every other test asserts
     * structured fields only, so a copy tweak fails just this test. Guards a wrong flip count in the
     * placement text, a wrong passer name, scores printed in mover-first instead of player-index order, and
     * the tie wording.
     */
    @Test
    fun `lastAction carries the exact UI messages for placements, passes, wins and ties`() {
        // Placements: 1 flip, then (after three more plies of the scripted opening) a 2-flip move by player 2.
        val opening = newGame()
        opening.startMatch()
        opening.placeDisc(idx(2, 3))
        assertEquals("Player 1 places a disc, flipping 1", opening.state.value!!.lastAction)
        opening.placeDisc(idx(4, 2))
        opening.placeDisc(idx(5, 3))
        opening.placeDisc(idx(2, 4)) // O flips (3,3) and (3,4)
        assertEquals("Player 2 places a disc, flipping 2", opening.state.value!!.lastAction)

        // Pass: the opponent (Player 2) has no reply and the message names them.
        val passRows = listOf("XO......", "........", "........", "........", "XOO.....", "........", "........", "XOOO....")
        val passGame = loadBoard(newGame(), passRows, swap = false, legal = setOf(idx(0, 2)))
        passGame.placeDisc(idx(0, 2))
        assertTrue(passGame.state.value!!.justPassed)
        assertEquals("Player 2 has no legal move and passes", passGame.state.value!!.lastAction)

        // Decisive results, scores always in player-index order.
        val win0 = loadBoard(newGame(), wipeoutRows, swap = false, legal = setOf(wipeoutMove))
        win0.placeDisc(wipeoutMove)
        assertEquals("Player 1 wins 5-0!", win0.state.value!!.lastAction)

        val win1 = loadBoard(newGame(), wipeoutRows, swap = true, legal = setOf(wipeoutMove))
        win1.placeDisc(wipeoutMove)
        assertEquals("Player 2 wins 0-5!", win1.state.value!!.lastAction)

        val moverLoses = loadBoard(
            newGame(),
            listOf("XO......", "........", "........", "........", "........", "........", "........", "OOOOO..."),
            swap = false, legal = setOf(idx(0, 2))
        )
        moverLoses.placeDisc(idx(0, 2))
        assertEquals("Player 2 wins 3-5!", moverLoses.state.value!!.lastAction)

        // Tie.
        val tie = loadBoard(
            newGame(),
            listOf("XO......", "........", "........", "........", "........", "........", "........", "OOO....."),
            swap = false, legal = setOf(idx(0, 2))
        )
        tie.placeDisc(idx(0, 2))
        assertEquals("It's a tie, 3-3!", tie.state.value!!.lastAction)
    }

    // ---------------------------------------------------------------- own tests: scripted opening

    /**
     * A hand-played, hand-verified five-ply opening (player 0 = X moves first). After every ply the full
     * board picture, the live scores, the side to move and the freshly computed legal set are asserted
     * (the UI message strings are pinned separately by the dedicated messages test). Boards and legal sets were derived by scanning every empty cell against the flank rule.
     */
    @Test
    fun `a scripted five-ply opening produces exactly the hand-derived boards, scores, turns and legal sets`() {
        val game = newGame()
        game.startMatch()

        game.placeDisc(idx(2, 3)) // X flips (3,3) against (4,3)
        var s = game.state.value!!
        assertEquals(listOf("........", "........", "...X....", "...XX...", "...XO...", "........", "........", "........"), render(s.cells))
        assertEquals(listOf(4, 1), s.scores)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(setOf(idx(2, 2), idx(2, 4), idx(4, 2)), s.legalMoves)

        game.placeDisc(idx(4, 2)) // O flips (4,3) against (4,4)
        s = game.state.value!!
        assertEquals(listOf("........", "........", "...X....", "...XX...", "..OOO...", "........", "........", "........"), render(s.cells))
        assertEquals(listOf(3, 3), s.scores)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(setOf(idx(5, 1), idx(5, 2), idx(5, 3), idx(5, 4), idx(5, 5)), s.legalMoves)

        game.placeDisc(idx(5, 3)) // X flips (4,3) against (3,3)
        s = game.state.value!!
        assertEquals(listOf("........", "........", "...X....", "...XX...", "..OXO...", "...X....", "........", "........"), render(s.cells))
        assertEquals(listOf(5, 2), s.scores)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(setOf(idx(2, 2), idx(2, 4), idx(6, 2), idx(6, 4)), s.legalMoves)

        game.placeDisc(idx(2, 4)) // O flips (3,3) [diagonal to (4,2)] and (3,4) [vertical to (4,4)]
        s = game.state.value!!
        assertEquals(listOf("........", "........", "...XO...", "...OO...", "..OXO...", "...X....", "........", "........"), render(s.cells))
        assertEquals(listOf(3, 5), s.scores)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(setOf(idx(2, 5), idx(3, 1), idx(3, 5), idx(4, 1), idx(4, 5)), s.legalMoves)

        game.placeDisc(idx(2, 5)) // X flips (2,4) [horizontal] and (3,4) [diagonal to (4,3)]
        s = game.state.value!!
        assertEquals(listOf("........", "........", "...XXX..", "...OX...", "..OXO...", "...X....", "........", "........"), render(s.cells))
        assertEquals(listOf(6, 3), s.scores)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(
            setOf(idx(1, 3), idx(1, 4), idx(1, 5), idx(3, 5), idx(6, 2), idx(6, 3), idx(6, 4)),
            s.legalMoves
        )
        assertFalse(s.justPassed)
        assertFalse(s.boardOver)
    }

    // ---------------------------------------------------------------- own tests: property check over seeded games

    /**
     * Fixed-seed random self-play. Every ply must satisfy structural invariants that do not depend on the
     * engine's own implementation: exactly one new disc; the placed cell belongs to the mover; every changed
     * old disc was the opponent's and is now the mover's; changed discs lie on straight lines through the
     * placed cell, contiguous, and end in a mover anchor; every straight line that is flanked by the mover
     * WAS flipped (no missed captures); scores equal disc counts; the turn/pass/over bookkeeping is consistent.
     *
     * Overlap note: ReversiReferenceEngineTest's differential test covers the same properties over many
     * more boards. This test is kept because it uses an independent per-ray flank oracle (a plain walk on
     * the BEFORE board) rather than that file's reference engine, so the two do not share a blind spot.
     */
    @Test
    fun `seeded random games keep every structural invariant on every ply`() {
        val dirs = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
        var passesSeen = 0
        for (seed in 1L..25L) {
            val rnd = Random(seed)
            val game = newGame()
            game.startMatch()
            var plies = 0
            while (true) {
                val before = game.state.value!!
                if (before.boardOver) break
                assertTrue("seed $seed: player to move must have a legal move", before.legalMoves.isNotEmpty())
                assertTrue(before.legalMoves.all { it in 0..63 && before.cells[it] == null })
                val move = before.legalMoves.sorted()[rnd.nextInt(before.legalMoves.size)]
                val mover = before.currentPlayerIndex
                game.placeDisc(move)
                plies++
                val after = game.state.value!!

                assertEquals(countDiscs(before.cells) + 1, countDiscs(after.cells))
                assertEquals(mover, after.cells[move])
                val flipped = before.cells.indices.filter { before.cells[it] != null && after.cells[it] != before.cells[it] }
                assertTrue("seed $seed ply $plies: a move must flip something", flipped.isNotEmpty())
                for (f in flipped) {
                    assertEquals(1 - mover, before.cells[f])
                    assertEquals(mover, after.cells[f])
                }
                // Unchanged cells stay unchanged.
                for (i in 0 until 64) if (i != move && i !in flipped) assertEquals(before.cells[i], after.cells[i])

                // Independent flank oracle: walk each ray on the BEFORE board.
                val mr = move / 8
                val mc = move % 8
                val expectedFlips = mutableSetOf<Int>()
                for ((dr, dc) in dirs) {
                    val run = mutableListOf<Int>()
                    var r = mr + dr
                    var c = mc + dc
                    while (r in 0..7 && c in 0..7 && before.cells[r * 8 + c] == 1 - mover) { run += r * 8 + c; r += dr; c += dc }
                    val anchored = run.isNotEmpty() && r in 0..7 && c in 0..7 && before.cells[r * 8 + c] == mover
                    if (anchored) expectedFlips += run
                }
                assertEquals("seed $seed ply $plies move $move: flipped set must be exactly the flanked runs", expectedFlips, flipped.toSet())

                assertEquals(listOf(after.cells.count { it == 0 }, after.cells.count { it == 1 }), after.scores)

                when {
                    after.boardOver -> {
                        assertTrue(after.legalMoves.isEmpty())
                        assertFalse(after.justPassed)
                        val expectedWinner = when {
                            after.scores[0] > after.scores[1] -> "p1"
                            after.scores[1] > after.scores[0] -> "p2"
                            else -> null
                        }
                        assertEquals(expectedWinner, after.winnerPlayerId)
                    }
                    after.justPassed -> {
                        passesSeen++
                        assertEquals(mover, after.currentPlayerIndex)
                        // Structured fields only, plus the passing player's name (exact wording is pinned by the messages test).
                        assertTrue(after.lastAction.contains(before.players[1 - mover].displayName))
                    }
                    else -> {
                        assertEquals(1 - mover, after.currentPlayerIndex)
                        assertTrue(after.lastAction.contains(before.players[mover].displayName))
                    }
                }
                assertTrue("seed $seed: game must end within 60 placements", plies <= 60)
            }
            val end = game.state.value!!
            assertEquals(4 + plies, countDiscs(end.cells))
            assertEquals(
                "exactly one board outcome recorded",
                1, game.sessionWins.value.values.sum() + game.sessionDraws.value
            )
        }
        assertTrue("the seeded corpus should include at least one real pass", passesSeen > 0)
    }
}
