package com.gamesuite.games.shogi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Property tests: random legal play never throws and keeps every invariant (both variants). */
class ShogiFuzzTest {

    private fun materialCount(p: ShogiPosition): Int {
        var total = p.board.count { it != 0 }
        for (h in p.hands) total += h
        return total
    }

    @Test fun randomEnginePlayKeepsInvariants() {
        for (variant in ShogiVariant.values()) {
            val rules = ShogiRules.of(variant)
            for (seed in 0 until 40) {
                val rnd = Random(seed * 7919L + variant.n)
                val p = rules.startPosition()
                val total = materialCount(p)
                var ply = 0
                while (ply < 300) {
                    val moves = rules.legalMoves(p)
                    if (moves.size == 0) break
                    // every legal move leaves the mover's king safe and is exactly reversible
                    val m = moves[rnd.nextInt(moves.size)]
                    val key = p.key()
                    val side = p.side
                    val cap = rules.make(p, m)
                    assertFalse("king left in check", rules.inCheck(p, side))
                    assertEquals(total, materialCount(p)) // captures move material into hand, never create or lose it
                    assertEquals(1, p.board.count { ShogiPiece.type(it) == ShogiPiece.KING && it != 0 && ShogiPiece.side(it) == 0 })
                    assertEquals(1, p.board.count { ShogiPiece.type(it) == ShogiPiece.KING && it != 0 && ShogiPiece.side(it) == 1 })
                    val after = p.key()
                    rules.unmake(p, m, cap)
                    assertEquals(key, p.key())
                    rules.make(p, m)
                    assertEquals(after, p.key())
                    ply++
                }
            }
        }
    }

    @Test fun randomGamesRoundTripThroughSnapshotAndUndo() {
        for (variant in ShogiVariant.values()) {
            for (seed in 0 until 12) {
                val rnd = Random(seed + 100)
                val g = ShogiTestKit.local(variant)
                var ply = 0
                while (ply < 120) {
                    val s = g.state.value!!
                    if (s.gameOver) break
                    // state's legal list agrees with a fresh engine on the same board
                    val rules = ShogiRules.of(variant)
                    val p = ShogiPosition(variant.n)
                    s.cells.forEachIndexed { i, c -> p.board[i] = c }
                    s.hands.forEachIndexed { i, c -> p.hands[i] = c }
                    p.side = s.sideToMove
                    p.recomputeKings()
                    assertEquals(rules.legalMoves(p).toList().sorted(), s.legalMoves.sorted())
                    assertEquals(rules.inCheck(p, p.side), s.inCheck)

                    if (ply % 9 == 0) {
                        val snap = g.snapshot()!!
                        val h = ShogiTestKit.local(variant, start = false)
                        assertTrue(h.restore(snap))
                        assertEquals(s, h.state.value)
                        assertEquals(snap, h.snapshot())
                    }
                    val before = g.snapshot()
                    g.playMove(s.legalMoves[rnd.nextInt(s.legalMoves.size)])
                    if (ply % 11 == 0) {
                        g.undo()
                        val after = g.snapshot()
                        if (before != after) println("DBGFUZZ variant=$variant seed=$seed ply=$ply " + s.gameOver + " BEFORE=" + before + " AFTER=" + after)
                        assertEquals("undo must restore the exact state", before, after)
                        g.playMove(s.legalMoves[rnd.nextInt(s.legalMoves.size)])
                    }
                    ply++
                }
            }
        }
    }

    @Test fun malformedNetworkAndSnapshotInputNeverThrows() {
        val rnd = Random(5)
        val g = ShogiTestKit.local(start = false)
        repeat(200) {
            val junk = String(CharArray(rnd.nextInt(1, 80)) { (rnd.nextInt(32, 126)).toChar() })
            assertFalse(g.restore(junk))
        }
        val t = ShogiTestKit.table()
        repeat(100) {
            t.host.transport.inject(ShogiTestKit.GUEST, ByteArray(rnd.nextInt(0, 60)) { rnd.nextInt(256).toByte() })
            t.guest.transport.inject(ShogiTestKit.HOST, ByteArray(rnd.nextInt(0, 60)) { rnd.nextInt(256).toByte() })
        }
        assertEquals(30, t.host.game.state.value!!.legalMoves.size)
    }
}
