package com.gamesuite.games.gemcascade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** A 6 x 6 board with no matches: kind = (r + 2c) % 5 (kinds 0..4), so kinds 5 and 6 are free for crafted shapes. */
fun baseBoard(rows: Int = 6, cols: Int = 6): GemBoard {
    val b = GemBoard(rows, cols)
    for (r in 0 until rows) for (c in 0 until cols) b[r, c] = Cell.gem((r + 2 * c) % 5)
    return b
}

fun GemBoard.put(kind: Int, vararg cells: Pair<Int, Int>): GemBoard {
    for ((r, c) in cells) this[r, c] = Cell.gem(kind)
    return this
}

fun GemBoard.putCell(r: Int, c: Int, cell: Cell): GemBoard {
    this[r, c] = cell
    return this
}

fun engineOn(board: GemBoard, mode: GemMode = GemMode.ZEN, seed: Long = 7L, kinds: Int = 7): GemEngine {
    val rules = ModeRules.of(mode).copy(kinds = kinds)
    val e = GemEngine(rules, 0, emptyList(), board, GemRng(seed), kinds)
    e.movesLeft = rules.moveLimit
    e.timeLeftMs = rules.timeLimitMs
    return e
}

fun assertSettled(b: GemBoard) {
    for (cell in b.cells) assertFalse("empty cell left on the board", cell.type == PieceType.EMPTY)
    assertFalse("a match is pending", GemMatcher.hasAny(b))
}

/** Applies [events] to a copy of [before] and checks it equals [after]: the step log must be complete. */
fun assertLogReproduces(before: GemBoard, events: List<GemEvent>, after: GemBoard, context: String) {
    val b = before.copy()
    GemEventApplier.applyAll(b, events)
    if (!b.sameAs(after)) {
        throw AssertionError("event log does not reproduce the board ($context)\nreplayed: ${b.encode()}\nactual:   ${after.encode()}\nevents: $events")
    }
}

fun anyBoardLegal(e: GemEngine) = assertTrue(e.legalActions().isNotEmpty())
