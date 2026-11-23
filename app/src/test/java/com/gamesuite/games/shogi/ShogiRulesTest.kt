package com.gamesuite.games.shogi

import com.gamesuite.games.shogi.ShogiTestKit.sparse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShogiRulesTest {
    private val shogi = ShogiRules.of(ShogiVariant.STANDARD)
    private val mini = ShogiRules.of(ShogiVariant.MINI)
    private val cc9 = 81
    private val cc5 = 25

    private fun sq(r: Int, c: Int) = r * 9 + c
    private fun moves(p: ShogiPosition, r: ShogiRules = shogi) = ShogiTestKit.movesOf(r, p)

    // ---- start positions ----

    @Test fun shogiStartPositionLayout() {
        val p = shogi.startPosition()
        assertEquals(ShogiPiece.code(0, ShogiPiece.KING), p.board[sq(8, 4)])
        assertEquals(ShogiPiece.code(1, ShogiPiece.KING), p.board[sq(0, 4)])
        assertEquals(ShogiPiece.code(0, ShogiPiece.ROOK), p.board[sq(7, 7)])
        assertEquals(ShogiPiece.code(0, ShogiPiece.BISHOP), p.board[sq(7, 1)])
        assertEquals(ShogiPiece.code(1, ShogiPiece.ROOK), p.board[sq(1, 1)])
        assertEquals(40, p.board.count { it != 0 })
        assertEquals(sq(8, 4), p.kingSq[0])
        assertEquals(sq(0, 4), p.kingSq[1])
    }

    @Test fun miniStartPositionLayout() {
        val p = mini.startPosition()
        assertEquals(12, p.board.count { it != 0 })
        assertEquals(ShogiPiece.code(0, ShogiPiece.KING), p.board[4 * 5 + 0])
        assertEquals(ShogiPiece.code(1, ShogiPiece.KING), p.board[0 * 5 + 4])
        assertEquals(ShogiPiece.code(0, ShogiPiece.PAWN), p.board[3 * 5 + 0])
        assertEquals(ShogiPiece.code(1, ShogiPiece.PAWN), p.board[1 * 5 + 4])
        // point symmetry: the rules are the same for both sides
        for (i in 0 until 25) {
            val a = p.board[i]
            val b = p.board[24 - i]
            assertEquals(a == 0, b == 0)
            if (a != 0) {
                assertEquals(ShogiPiece.type(a), ShogiPiece.type(b))
                assertEquals(1 - ShogiPiece.side(a), ShogiPiece.side(b))
            }
        }
    }

    // ---- drops ----

    @Test fun nifuBlocksOnlyUnpromotedPawnsOfTheSameSide() {
        val p = sparse(
            9, (8 to 4) to "K", (0 to 4) to "k", (6 to 4) to "P", (6 to 2) to "+P", (5 to 6) to "p",
            hands = mapOf((0 to ShogiPiece.PAWN) to 1)
        )
        val drops = moves(p).filter { ShogiMove.from(it) == cc9 + ShogiPiece.PAWN }.map { ShogiMove.to(it) % 9 }.toSet()
        assertFalse("column 4 holds Sente's own pawn", 4 in drops)
        assertTrue("a promoted pawn does not block (column 2)", 2 in drops)
        assertTrue("the enemy pawn in column 6 does not block", 6 in drops)
        assertEquals("nifu", shogi.dropRefusal(p, ShogiPiece.PAWN, sq(4, 4)))
    }

    @Test fun deadDropsAreRefused() {
        val p = sparse(9, (8 to 4) to "K", (0 to 4) to "k", hands = mapOf(
            (0 to ShogiPiece.PAWN) to 1, (0 to ShogiPiece.LANCE) to 1, (0 to ShogiPiece.KNIGHT) to 1, (0 to ShogiPiece.GOLD) to 1
        ))
        val ms = moves(p)
        fun dropsOf(t: Int) = ms.filter { ShogiMove.from(it) == cc9 + t }.map { ShogiMove.to(it) / 9 }.toSet()
        assertFalse(0 in dropsOf(ShogiPiece.PAWN))
        assertTrue(1 in dropsOf(ShogiPiece.PAWN))
        assertFalse(0 in dropsOf(ShogiPiece.LANCE))
        assertFalse(0 in dropsOf(ShogiPiece.KNIGHT))
        assertFalse(1 in dropsOf(ShogiPiece.KNIGHT))
        assertTrue(2 in dropsOf(ShogiPiece.KNIGHT))
        assertTrue(0 in dropsOf(ShogiPiece.GOLD))
        assertEquals("dead", shogi.dropRefusal(p, ShogiPiece.KNIGHT, sq(1, 0)))
    }

    /** Gote king boxed in by its own pawns; a pawn drop that gives mate is illegal, a non-mating one is fine. */
    @Test fun uchifuzumeIsIllegalButAMatingGoldDropIsFine() {
        val boxed = sparse(
            9, (0 to 0) to "k", (0 to 1) to "p", (1 to 1) to "p", (2 to 1) to "G", (8 to 8) to "K",
            hands = mapOf((0 to ShogiPiece.PAWN) to 1)
        )
        val pawnDrop = ShogiMove.make(cc9 + ShogiPiece.PAWN, sq(1, 0))
        assertFalse(pawnDrop in moves(boxed))
        assertEquals("uchifuzume", shogi.dropRefusal(boxed, ShogiPiece.PAWN, sq(1, 0)))

        val roomy = sparse(
            9, (0 to 0) to "k", (0 to 1) to "p", (8 to 8) to "K",
            hands = mapOf((0 to ShogiPiece.PAWN) to 1)
        )
        assertTrue("king can step to (1,1), so it is just a check", pawnDrop in moves(roomy))
    }

    @Test fun droppedPieceMustAnswerCheck() {
        val p = sparse(9, (8 to 4) to "K", (0 to 4) to "r", (0 to 0) to "k", hands = mapOf((0 to ShogiPiece.PAWN) to 1, (0 to ShogiPiece.GOLD) to 1))
        assertTrue(shogi.inCheck(p, 0))
        val ms = moves(p)
        // The only drops are blocks on column 4 between the rook and the king.
        val drops = ms.filter { ShogiMove.from(it) >= cc9 }
        assertTrue(drops.isNotEmpty())
        assertTrue(drops.all { ShogiMove.to(it) % 9 == 4 })
        assertEquals("check", shogi.dropRefusal(p, ShogiPiece.GOLD, sq(4, 2)))
    }

    // ---- promotion ----

    @Test fun promotionOffersBothChoicesWhereAllowed() {
        val p = sparse(9, (8 to 8) to "K", (0 to 0) to "k", (3 to 4) to "P", (1 to 6) to "P", (2 to 2) to "S", (4 to 7) to "N", (5 to 1) to "G")
        val ms = moves(p)
        fun of(r: Int, c: Int) = ms.filter { ShogiMove.from(it) == sq(r, c) }
        // pawn (3,4) -> (2,4): promote or not
        assertEquals(setOf(true, false), of(3, 4).map { ShogiMove.promote(it) }.toSet())
        // pawn (1,6) -> (0,6): must promote (a pawn on the last row could never move)
        assertEquals(listOf(true), of(1, 6).map { ShogiMove.promote(it) })
        // silver inside the zone may promote on any move, including backwards out of it
        assertTrue(of(2, 2).any { ShogiMove.to(it) == sq(3, 1) && ShogiMove.promote(it) })
        assertTrue(of(2, 2).any { ShogiMove.to(it) == sq(3, 1) && !ShogiMove.promote(it) })
        // knight (4,7) lands on row 2 (zone, but it still has a move from there): optional
        assertEquals(setOf(true, false), of(4, 7).map { ShogiMove.promote(it) }.toSet())
        // gold never promotes
        assertTrue(of(5, 1).none { ShogiMove.promote(it) })
    }

    @Test fun knightAndLanceForcedPromotionOnDeadRows() {
        val p = sparse(9, (8 to 8) to "K", (0 to 0) to "k", (2 to 4) to "N", (1 to 7) to "L")
        val ms = moves(p)
        // knight from row 2 lands on row 0: forced
        assertTrue(ms.filter { ShogiMove.from(it) == sq(2, 4) }.all { ShogiMove.promote(it) })
        // lance sliding up column 7 to row 0 must promote; stopping short is not possible (adjacent)
        assertEquals(listOf(true), ms.filter { ShogiMove.from(it) == sq(1, 7) }.map { ShogiMove.promote(it) })
    }

    @Test fun promotedPiecesMoveAsDocumented() {
        // tokin moves like gold; horse = bishop + orthogonal steps; dragon = rook + diagonal steps
        val p = sparse(9, (8 to 8) to "K", (0 to 8) to "k", (4 to 4) to "+B")
        val dests = moves(p).filter { ShogiMove.from(it) == sq(4, 4) }.map { ShogiMove.to(it) }.toSet()
        assertTrue(sq(3, 4) in dests && sq(5, 4) in dests && sq(4, 3) in dests && sq(4, 5) in dests)
        assertTrue(sq(0, 0) in dests && sq(8, 8) !in dests)
        assertFalse("horse does not slide orthogonally", sq(2, 4) in dests)
        val d = sparse(9, (8 to 8) to "K", (0 to 8) to "k", (4 to 4) to "+R")
        val dd = moves(d).filter { ShogiMove.from(it) == sq(4, 4) }.map { ShogiMove.to(it) }.toSet()
        assertTrue(sq(0, 4) in dd && sq(3, 3) in dd)
        assertFalse(sq(2, 2) in dd)
    }

    // ---- check, pins ----

    @Test fun pinnedPieceCannotLeaveTheLine() {
        val p = sparse(9, (8 to 4) to "K", (0 to 4) to "r", (6 to 4) to "G", (0 to 0) to "k")
        assertFalse(shogi.inCheck(p, 0))
        val gold = moves(p).filter { ShogiMove.from(it) == sq(6, 4) }
        assertTrue(gold.isNotEmpty())
        assertTrue(gold.all { ShogiMove.to(it) % 9 == 4 })
        assertEquals("exposes", ShogiGame().let {
            it.init(com.gamesuite.core.GameContext(com.gamesuite.core.PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, ShogiTestKit.humans, 0, ShogiTestKit.FakeTransport()))
            it.loadPosition(p)
            it.whyIllegal(sq(6, 4), sq(6, 5))
        })
    }

    @Test fun knightChecksOverPieces() {
        val p = sparse(9, (8 to 4) to "K", (6 to 3) to "n", (7 to 4) to "P", (0 to 0) to "k", side = 0)
        assertTrue("gote knight at (6,3) attacks (8,4)", shogi.inCheck(p, 0))
    }

    // ---- impasse ----

    private fun campPosition(side: Int, drop: Pair<Int, Int>? = null): ShogiPosition {
        // Sente camp = rows 0..2; Gote camp = rows 6..8
        val rowsFor = if (side == 0) listOf(0, 1) else listOf(8, 7)
        val up = if (side == 0) "" else ""
        fun t(ch: Char) = if (side == 0) ch.uppercaseChar().toString() else ch.lowercaseChar().toString()
        val pieces = ArrayList<Pair<Pair<Int, Int>, String>>()
        val r0 = rowsFor[0]
        val r1 = rowsFor[1]
        pieces += (r0 to 0) to t('r'); pieces += (r0 to 1) to t('r')
        pieces += (r0 to 2) to t('b'); pieces += (r0 to 3) to t('b')
        for (c in 5..8) pieces += (r0 to c) to t('g')
        for (c in 0..3) pieces += (r1 to c) to t('s')
        pieces += (r1 to 4) to t('k')
        // enemy king far away at the other end
        pieces += (if (side == 0) 8 to 4 else 0 to 4) to (if (side == 0) "k" else "K")
        if (drop != null) pieces.removeAll { it.first == drop }
        return sparse(9, *pieces.toTypedArray(), side = side)
    }

    @Test fun impasseNeedsThePointsAndTheCamp() {
        val sente = campPosition(0)
        assertTrue(shogi.canDeclareImpasse(sente, 0)) // 12 pieces in camp, 10 + 10 + 4 + 4 = 28
        val short = campPosition(0, drop = 1 to 3) // 27 points: not enough for Sente
        assertFalse(shogi.canDeclareImpasse(short, 0))
        val gote = campPosition(1)
        assertTrue(shogi.canDeclareImpasse(gote, 1))
        val goteShort = campPosition(1, drop = 7 to 3) // 27 points: enough for Gote
        assertTrue(shogi.canDeclareImpasse(goteShort, 1))
        // wrong side to move
        assertFalse(shogi.canDeclareImpasse(sente, 1))
        // never on the mini board
        assertFalse(mini.canDeclareImpasse(mini.startPosition(), 0))
    }

    @Test fun impasseNotAllowedWhileInCheck() {
        val sente = campPosition(0)
        // a Gote rook gives check down column 4 (Sente king is at (1,4))
        sente.board[sq(5, 4)] = ShogiPiece.code(1, ShogiPiece.ROOK)
        assertTrue(shogi.inCheck(sente, 0))
        assertFalse(shogi.canDeclareImpasse(sente, 0))
    }

    // ---- art matches rules ----

    @Test fun pieceArtMatchesTheEngine() {
        val types = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15)
        for (t in types) {
            val code = (if (ShogiPiece.isPromoted(t)) "+" + letterOf(ShogiPiece.base(t)) else letterOf(t))
            val p = sparse(9, (4 to 4) to code, (8 to 2) to "K", (0 to 6) to "k")
            val dests = moves(p).filter { ShogiMove.from(it) == sq(4, 4) }.map { ShogiMove.to(it) }.toSet()
            val art = ShogiPieceArt.moves(t)
            val expect = HashSet<Int>()
            for ((dr, dc) in art.steps) expect += sq(4 + dr, 4 + dc)
            for ((dr, dc) in art.slides) {
                var r = 4 + dr
                var c = 4 + dc
                while (r in 0..8 && c in 0..8) { expect += sq(r, c); r += dr; c += dc }
            }
            if (art.knight) { expect += sq(2, 3); expect += sq(2, 5) }
            assertEquals("piece type $t", expect, dests)
        }
    }

    private fun letterOf(t: Int) = when (t) {
        ShogiPiece.PAWN -> "P"; ShogiPiece.LANCE -> "L"; ShogiPiece.KNIGHT -> "N"; ShogiPiece.SILVER -> "S"
        ShogiPiece.GOLD -> "G"; ShogiPiece.BISHOP -> "B"; ShogiPiece.ROOK -> "R"; else -> "K"
    }

    @Test fun everyPieceHasKanjiAndALetter() {
        for (t in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15)) {
            assertTrue(ShogiPieceArt.kanji(t, 0).isNotEmpty() && ShogiPieceArt.kanji(t, 0) != "?")
            assertTrue(ShogiPieceArt.letter(t).length == 1)
        }
        assertNotNull(ShogiPieceArt.kanji(ShogiPiece.KING, 1))
        assertTrue(ShogiPieceArt.kanji(ShogiPiece.KING, 0) != ShogiPieceArt.kanji(ShogiPiece.KING, 1))
    }

    // ---- mini shogi specifics ----

    @Test fun miniPromotesOnTheLastRowOnly() {
        val p = sparse(5, (4 to 4) to "K", (0 to 0) to "k", (2 to 2) to "P", (1 to 3) to "S")
        val ms = ShogiTestKit.movesOf(mini, p)
        // pawn (2,2) -> (1,2): not the zone (row 0), so no promotion option
        assertTrue(ms.filter { ShogiMove.from(it) == 2 * 5 + 2 }.none { ShogiMove.promote(it) })
        // silver (1,3) -> (0,3) may promote or not
        val s = ms.filter { ShogiMove.from(it) == 1 * 5 + 3 && ShogiMove.to(it) == 3 }
        assertEquals(setOf(true, false), s.map { ShogiMove.promote(it) }.toSet())
    }

    @Test fun miniPawnDropRulesAndNifu() {
        val p = sparse(5, (4 to 4) to "K", (0 to 0) to "k", (3 to 2) to "P", hands = mapOf((0 to ShogiPiece.PAWN) to 1))
        val drops = ShogiTestKit.movesOf(mini, p).filter { ShogiMove.from(it) >= cc5 }.map { ShogiMove.to(it) }
        assertTrue(drops.none { it % 5 == 2 }) // nifu in column 2
        assertTrue(drops.none { it / 5 == 0 }) // last row is dead for a pawn
        assertTrue(drops.isNotEmpty())
    }

    @Test fun makeAndUnmakeAreExactInverses() {
        for (rules in listOf(shogi, mini)) {
            val p = rules.startPosition()
            val before = p.key()
            var depth = 0
            fun walk(d: Int) {
                if (d == 0) return
                val ms = rules.legalMoves(p)
                for (i in 0 until ms.size) {
                    val k = p.key()
                    val kings = p.kingSq.toList()
                    val cap = rules.make(p, ms[i])
                    walk(d - 1)
                    rules.unmake(p, ms[i], cap)
                    assertEquals(k, p.key())
                    assertEquals(kings, p.kingSq.toList())
                    depth++
                }
            }
            walk(2)
            assertEquals(before, p.key())
            assertTrue(depth > 0)
        }
    }

    @Test fun noLegalMovesMeansMateOrStalemateLoss() {
        val p = sparse(9, (0 to 4) to "k", (1 to 4) to "G", (8 to 4) to "L", (8 to 8) to "K", side = 1)
        assertTrue(shogi.inCheck(p, 1))
        assertEquals(0, shogi.legalMoves(p).size)
    }
}
