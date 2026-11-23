package com.gamesuite.games.connectfour

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectFourSnapshotValidationTest {
    private val ids = listOf("p1", "p2")
    private val rows = 6
    private val cols = 7

    /** Builds a board from bottom-up column stacks, e.g. col 3 -> [0, 1] puts P1 then P2 at the bottom of column 3. */
    private fun board(vararg stacks: Pair<Int, List<Int>>): List<Int?> {
        val cells = MutableList<Int?>(rows * cols) { null }
        for ((c, stack) in stacks) stack.forEachIndexed { i, who -> cells[(rows - 1 - i) * cols + c] = who }
        return cells
    }

    private fun ok(
        cells: List<Int?>, current: Int, over: Boolean = false, winner: String? = null, line: List<Int>? = null
    ) = ConnectFourPositionCheck.isValid(cells, rows, cols, 4, ids, current, over, winner, line)

    private val empty = board()

    @Test fun legalPositionsPass() {
        assertTrue(ok(empty, 0))
        assertTrue(ok(empty, 1))
        assertTrue(ok(board(3 to listOf(0)), 1))
        assertTrue(ok(board(3 to listOf(1)), 0))
        assertTrue(ok(board(3 to listOf(0, 1), 4 to listOf(0)), 1))
        // P1 wins on the bottom row: columns 0..3.
        val won = board(0 to listOf(0), 1 to listOf(0), 2 to listOf(0), 3 to listOf(0), 4 to listOf(1), 5 to listOf(1), 6 to listOf(1))
        assertTrue(ok(won, 0, true, "p1", (0..3).map { (rows - 1) * cols + it }))
    }

    private fun corrupt(): Map<String, Boolean> {
        val bottom = { c: Int -> (rows - 1) * cols + c }
        val fourBottom = (0..3).map(bottom)
        val p1WinBoard = board(0 to listOf(0), 1 to listOf(0), 2 to listOf(0), 3 to listOf(0), 4 to listOf(1), 5 to listOf(1), 6 to listOf(1))
        val floating = MutableList<Int?>(rows * cols) { null }.also { it[2 * cols + 3] = 0 }
        val floatingAbove = board(3 to listOf(0)).toMutableList().also { it[(rows - 3) * cols + 3] = 1 }
        val full = MutableList<Int?>(rows * cols) { i -> ((i / cols) / 2 + (i % cols)) % 2 }
        return linkedMapOf(
            "floating disc" to ok(floating, 1),
            "disc above a gap" to ok(floatingAbove, 0),
            "three discs of one colour only" to ok(board(0 to listOf(0), 1 to listOf(0), 2 to listOf(0)), 1),
            "P2 has two more discs" to ok(board(0 to listOf(1), 1 to listOf(1)), 0),
            "turn is wrong when P1 is one up" to ok(board(3 to listOf(0)), 0),
            "turn is wrong when P2 is one up" to ok(board(3 to listOf(1)), 1),
            "owner out of range" to ok(MutableList<Int?>(rows * cols) { null }.also { it[bottom(0)] = 2 }, 0),
            "negative owner" to ok(MutableList<Int?>(rows * cols) { null }.also { it[bottom(0)] = -1 }, 0),
            "wrong cell count" to ok(List(10) { null }, 0),
            "current player out of range" to ok(empty, 2),
            "four in a row but the game is not over" to ok(p1WinBoard, 1),
            "over with a winner but no line on the board" to ok(empty, 0, true, "p1", fourBottom),
            "over, winner named, line missing" to ok(p1WinBoard, 0, true, "p1", null),
            "winner id does not match the line owner" to ok(p1WinBoard, 0, true, "p2", fourBottom),
            "winning line cells not the winner's" to ok(p1WinBoard, 0, true, "p1", (3..6).map(bottom)),
            "winning line too short" to ok(p1WinBoard, 0, true, "p1", (0..2).map(bottom)),
            "current player is not the winner" to ok(p1WinBoard, 1, true, "p1", fourBottom),
            "both players hold a line" to ok(board(0 to listOf(0, 1), 1 to listOf(0, 1), 2 to listOf(0, 1), 3 to listOf(0, 1)), 0),
            "draw declared on a board that is not full" to ok(board(3 to listOf(0)), 1, true, null, null),
            "winner set while the game is in progress" to ok(board(3 to listOf(0)), 1, false, "p1", null),
            "line set while the game is in progress" to ok(board(3 to listOf(0)), 1, false, null, listOf(1, 2, 3, 4)),
            "full board that is not over" to ok(full, 0),
            "duplicate cells in the winning line" to ok(p1WinBoard, 0, true, "p1", fourBottom.take(3) + fourBottom.first())
        )
    }

    @Test fun atLeastFifteenCorruptPositionsAreRejected() {
        val cases = corrupt()
        assertTrue(cases.size >= 15)
        for ((name, accepted) in cases) assertFalse("accepted: $name", accepted)
    }

    // ---- the same through the real restore() path ----

    private fun game(): ConnectFourGame {
        val g = ConnectFourGame()
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(PlayerInfo("p1", "P1"), PlayerInfo("p2", "P2")), 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    private fun withCells(json: String, cells: List<Int?>, current: Int? = null): String {
        val o = Json.parseToJsonElement(json) as JsonObject
        val m = o.toMutableMap()
        m["cells"] = JsonArray(cells.map { if (it == null) JsonNull else JsonPrimitive(it) })
        if (current != null) m["currentPlayerIndex"] = JsonPrimitive(current)
        return JsonObject(m).toString()
    }

    @Test fun restoreRejectsFloatingDiscsAndImpossibleTurnCountsButAcceptsRealPlay() {
        val a = game()
        listOf(3, 4, 3).forEach { a.dropDisc(it) }
        val snap = a.snapshot()!!
        assertTrue(game().restore(snap))
        val floating = MutableList<Int?>(rows * cols) { null }.also { it[1 * cols + 2] = 0 }
        assertFalse(game().restore(withCells(snap, floating, 1)))
        assertFalse(game().restore(withCells(snap, board(3 to listOf(0, 0, 0)), 1)))
        assertFalse(game().restore(withCells(snap, board(3 to listOf(0)), 0)))
    }
}
