package com.gamesuite.games.backgammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules: setup, movement, blocking, hitting, the bar, doubles, forced dice, bearing off, scoring. */
class BackgammonRulesTest {

    // --- Setup ---------------------------------------------------------------------------------

    @Test
    fun `each side starts with exactly fifteen checkers`() {
        val b = BackgammonBoard.initial()
        assertEquals(15, b.total(0))
        assertEquals(15, b.total(1))
        assertEquals(0, b.bar(0) + b.bar(1) + b.off(0) + b.off(1))
    }

    @Test
    fun `the standard opening setup has the right points for both sides`() {
        val b = BackgammonBoard.initial()
        // White (own numbering 24 / 13 / 8 / 6).
        assertEquals(2, b.count(0, bgIx(24)))
        assertEquals(5, b.count(0, bgIx(13)))
        assertEquals(3, b.count(0, bgIx(8)))
        assertEquals(5, b.count(0, bgIx(6)))
        // Black mirrors it; in White's numbering that is 1 / 12 / 17 / 19.
        assertEquals(2, b.count(1, bgIx(1)))
        assertEquals(5, b.count(1, bgIx(12)))
        assertEquals(3, b.count(1, bgIx(17)))
        assertEquals(5, b.count(1, bgIx(19)))
    }

    @Test
    fun `opening pip count is 167 for both sides and a bar checker counts 25`() {
        val b = BackgammonBoard.initial()
        assertEquals(167, b.pips(0))
        assertEquals(167, b.pips(1))
        assertEquals(25, bgPos(whiteBar = 1).pips(0))
    }

    @Test
    fun `the standard setup has no game over and no winner`() {
        val b = BackgammonBoard.initial()
        assertFalse(BackgammonRules.gameOver(b))
        assertNull(BackgammonRules.winner(b))
    }

    @Test
    fun `fromParts accepts a real position and rejects wrong counts and sizes`() {
        val b = BackgammonBoard.initial()
        assertEquals(b, BackgammonBoard.fromParts(b.pointsList(), b.barList(), b.offList()))
        assertNull(BackgammonBoard.fromParts(b.pointsList(), listOf(0, 0), listOf(1, 0)))
        assertNull(BackgammonBoard.fromParts(b.pointsList().drop(1), b.barList(), b.offList()))
        assertNull(BackgammonBoard.fromParts(b.pointsList(), listOf(0), b.offList()))
        assertNull(BackgammonBoard.fromParts(b.pointsList(), listOf(-1, 0), b.offList()))
        val tooMany = b.pointsList().toMutableList().also { it[bgIx(6)] = 6 }
        assertNull(BackgammonBoard.fromParts(tooMany, b.barList(), b.offList()))
    }

    @Test
    fun `applying a move leaves the original position untouched`() {
        val b = BackgammonBoard.initial()
        val after = b.apply(0, bgMove(8, 5, 3))
        assertEquals(3, b.count(0, bgIx(8)))
        assertEquals(2, after.count(0, bgIx(8)))
        assertEquals(1, after.count(0, bgIx(5)))
    }

    // --- Movement, blocking, hitting ------------------------------------------------------------

    @Test
    fun `opening roll offers the usual moves and respects a blocked point`() {
        val moves = BackgammonRules.legalMoves(BackgammonBoard.initial(), 0, listOf(3, 1))
        assertTrue(bgMove(8, 5, 3) in moves)
        assertTrue(bgMove(6, 5, 1) in moves)
        // 13 -> 12 is blocked: Black holds five checkers there.
        assertFalse(moves.any { it.from == bgIx(13) && it.die == 1 })
    }

    @Test
    fun `a point held by two enemy checkers is blocked`() {
        val b = bgPos(white = mapOf(8 to 1), black = mapOf(5 to 2))
        assertTrue(BackgammonRules.singleMoves(b, 0, 3).isEmpty())
    }

    @Test
    fun `a lone enemy checker is hit and sent to the bar`() {
        val b = bgPos(white = mapOf(8 to 1), black = mapOf(5 to 1), blackOff = 14)
        val moves = BackgammonRules.singleMoves(b, 0, 3)
        assertEquals(listOf(bgMove(8, 5, 3, hit = true)), moves)
        val after = b.apply(0, moves[0])
        assertEquals(1, after.bar(1))
        assertEquals(1, after.count(0, bgIx(5)))
        assertEquals(0, after.count(1, bgIx(5)))
    }

    @Test
    fun `black moves the other way and hits a white blot`() {
        val b = bgPos(black = mapOf(10 to 1), white = mapOf(12 to 1))
        val moves = BackgammonRules.singleMoves(b, 1, 2)
        assertEquals(listOf(bgMove(10, 12, 2, hit = true)), moves)
        assertEquals(1, b.apply(1, moves[0]).bar(0))
    }

    // --- The bar -------------------------------------------------------------------------------

    @Test
    fun `a checker on the bar must enter first and only onto an open point`() {
        // White enters with die d onto point 25 - d. Point 22 (die 3) is blocked, point 21 (die 4) is open.
        val b = bgPos(white = mapOf(10 to 1), whiteBar = 1, black = mapOf(22 to 2))
        val moves = BackgammonRules.legalMoves(b, 0, listOf(3, 4))
        assertEquals(listOf(BackgammonMove(BackgammonMove.BAR, bgIx(21), 4)), moves)
    }

    @Test
    fun `no other checker may move while one is on the bar`() {
        val b = bgPos(white = mapOf(10 to 1), whiteBar = 1, black = mapOf(19 to 2))
        assertTrue(BackgammonRules.legalMoves(b, 0, bgDice(6, 6)).isEmpty())
        assertTrue(BackgammonRules.generateTurns(b, 0, bgDice(6, 6)).isEmpty())
    }

    @Test
    fun `entering from the bar can hit a blot`() {
        val b = bgPos(whiteBar = 1, black = mapOf(21 to 1), blackOff = 14)
        assertEquals(listOf(BackgammonMove(BackgammonMove.BAR, bgIx(21), 4, hit = true)), BackgammonRules.singleMoves(b, 0, 4))
    }

    @Test
    fun `black enters from the bar onto its own side`() {
        // Black enters with die d onto White's point d; White holds point 2, so die 2 is blocked.
        val b = bgPos(blackBar = 1, white = mapOf(2 to 2))
        assertTrue(BackgammonRules.singleMoves(b, 1, 2).isEmpty())
        assertEquals(listOf(BackgammonMove(BackgammonMove.BAR, bgIx(3), 3)), BackgammonRules.singleMoves(b, 1, 3))
    }

    // --- Doubles -------------------------------------------------------------------------------

    @Test
    fun `a double gives four moves`() {
        val turns = BackgammonRules.generateTurns(BackgammonBoard.initial(), 0, bgDice(3, 3))
        assertTrue(turns.isNotEmpty())
        assertTrue(turns.all { it.moves.size == 4 })
    }

    @Test
    fun `a double stops when the path is blocked`() {
        // 10 -> 7 is fine but 7 -> 4 is blocked, so only one of the four moves can be played.
        val b = bgPos(white = mapOf(10 to 1), black = mapOf(4 to 2))
        val turns = BackgammonRules.generateTurns(b, 0, bgDice(3, 3))
        assertEquals(1, turns.size)
        assertEquals(1, turns[0].moves.size)
        assertEquals(listOf(bgMove(10, 7, 3)), BackgammonRules.legalMoves(b, 0, bgDice(3, 3)))
    }

    // --- Using the dice ------------------------------------------------------------------------

    @Test
    fun `both dice must be played when some order allows it`() {
        // Checker on 9 and one on 5, and the back checker on 24 is stuck behind points 21 and 20.
        // Black holds 2. Playing 9 -> 5 with the 4 would leave the 3 unplayable, so it is not allowed;
        // 9 -> 6 (3) and 5 -> 1 (4) both let the other die be played too.
        val b = bgPos(
            white = mapOf(9 to 1, 5 to 1, 24 to 1),
            black = mapOf(2 to 2, 21 to 2, 20 to 2),
            whiteOff = 12, blackOff = 9
        )
        val moves = BackgammonRules.legalMoves(b, 0, listOf(3, 4))
        assertEquals(setOf(bgMove(9, 6, 3), bgMove(5, 1, 4)), moves.toSet())
        assertFalse(moves.any { it.from == bgIx(9) && it.to == bgIx(5) })
    }

    @Test
    fun `when only one die can be played it must be the larger`() {
        // 24 is stuck. The checker on 8 can play the 6 (to 2) or the 5 (to 3) but never both.
        val b = bgPos(
            white = mapOf(8 to 1, 24 to 1),
            black = mapOf(18 to 2, 19 to 2),
            whiteOff = 13, blackOff = 11
        )
        assertEquals(listOf(bgMove(8, 2, 6)), BackgammonRules.legalMoves(b, 0, listOf(5, 6)))
        assertEquals(listOf(bgMove(8, 2, 6)), BackgammonRules.legalMoves(b, 0, listOf(6, 5)))
    }

    @Test
    fun `the smaller die is allowed when the larger cannot be played`() {
        val b = bgPos(
            white = mapOf(8 to 1, 24 to 1),
            black = mapOf(2 to 2, 18 to 2, 19 to 2),
            whiteOff = 13, blackOff = 9
        )
        assertEquals(listOf(bgMove(8, 3, 5)), BackgammonRules.legalMoves(b, 0, listOf(6, 5)))
    }

    @Test
    fun `the larger die is forced even when playing the smaller first would also work alone`() {
        // A lone checker on 9 with Black holding 2: the 4 (to 5) and the 3 (to 6) each play, never both.
        val b = bgPos(white = mapOf(9 to 1), black = mapOf(2 to 2), whiteOff = 14, blackOff = 13)
        assertEquals(listOf(bgMove(9, 5, 4)), BackgammonRules.legalMoves(b, 0, listOf(4, 3)))
    }

    @Test
    fun `a roll with no legal move at all gives an empty turn list`() {
        val b = bgPos(white = mapOf(8 to 1), black = mapOf(5 to 2, 4 to 2), whiteOff = 14, blackOff = 11)
        assertTrue(BackgammonRules.legalMoves(b, 0, listOf(3, 4)).isEmpty())
        assertTrue(BackgammonRules.generateTurns(b, 0, listOf(3, 4)).isEmpty())
    }

    // --- Bearing off ---------------------------------------------------------------------------

    @Test
    fun `bearing off with the exact die`() {
        val b = bgPos(white = mapOf(6 to 2, 3 to 1), whiteOff = 12)
        val three = BackgammonRules.singleMoves(b, 0, 3)
        assertTrue(BackgammonMove(bgIx(3), BackgammonMove.OFF, 3) in three)
        assertTrue(bgMove(6, 3, 3) in three)
        val six = BackgammonRules.singleMoves(b, 0, 6)
        assertTrue(BackgammonMove(bgIx(6), BackgammonMove.OFF, 6) in six)
    }

    @Test
    fun `a higher die bears off only the farthest checker`() {
        val b = bgPos(white = mapOf(4 to 1, 2 to 1), whiteOff = 13)
        assertEquals(listOf(BackgammonMove(bgIx(4), BackgammonMove.OFF, 6)), BackgammonRules.singleMoves(b, 0, 6))
    }

    @Test
    fun `a higher die may not bear off while a checker is farther back`() {
        val b = bgPos(white = mapOf(5 to 1, 2 to 1), whiteOff = 13)
        // The 3 moves 5 -> 2; it may not bear the checker on 2 off because the 5 is farther back.
        assertEquals(listOf(bgMove(5, 2, 3)), BackgammonRules.singleMoves(b, 0, 3))
    }

    @Test
    fun `no bearing off while a checker is outside the home board`() {
        val b = bgPos(white = mapOf(7 to 1, 3 to 1), whiteOff = 13)
        assertTrue(BackgammonRules.singleMoves(b, 0, 3).none { it.bearsOff })
    }

    @Test
    fun `no bearing off with a checker on the bar`() {
        val b = bgPos(white = mapOf(3 to 1), whiteBar = 1, whiteOff = 13)
        assertTrue(BackgammonRules.singleMoves(b, 0, 3).none { it.bearsOff })
        assertFalse(BackgammonRules.allHome(b, 0))
    }

    @Test
    fun `black bears off past the far end`() {
        // Black's home board is White's points 19..24; White's point 24 is Black's 1-point.
        val b = bgPos(black = mapOf(24 to 1, 22 to 1), blackOff = 13)
        val moves = BackgammonRules.singleMoves(b, 1, 1)
        assertTrue(BackgammonMove(bgIx(24), BackgammonMove.OFF, 1) in moves)
        assertTrue(bgMove(22, 23, 1) in moves)
    }

    // --- Winning and scoring -------------------------------------------------------------------

    @Test
    fun `bearing off all fifteen wins the game`() {
        val b = bgPos(black = mapOf(20 to 15), whiteOff = 15)
        assertEquals(0, BackgammonRules.winner(b))
        assertTrue(BackgammonRules.gameOver(b))
    }

    @Test
    fun `a normal win scores one point`() {
        val b = bgPos(black = mapOf(20 to 14), whiteOff = 15, blackOff = 1)
        assertEquals(BackgammonResultKind.NORMAL, BackgammonRules.resultKind(b, 0))
        assertEquals(1, BackgammonResultKind.NORMAL.points)
    }

    @Test
    fun `a gammon scores two points`() {
        val b = bgPos(black = mapOf(20 to 15), whiteOff = 15)
        assertEquals(BackgammonResultKind.GAMMON, BackgammonRules.resultKind(b, 0))
        assertEquals(2, BackgammonResultKind.GAMMON.points)
    }

    @Test
    fun `a backgammon with a checker on the bar scores three points`() {
        val b = bgPos(black = mapOf(20 to 14), blackBar = 1, whiteOff = 15)
        assertEquals(BackgammonResultKind.BACKGAMMON, BackgammonRules.resultKind(b, 0))
        assertEquals(3, BackgammonResultKind.BACKGAMMON.points)
    }

    @Test
    fun `a backgammon with a checker in the winner's home board scores three points`() {
        val b = bgPos(black = mapOf(3 to 1, 20 to 14), whiteOff = 15)
        assertEquals(BackgammonResultKind.BACKGAMMON, BackgammonRules.resultKind(b, 0))
    }

    @Test
    fun `black winning is scored the same way from its own side`() {
        val gammon = bgPos(white = mapOf(10 to 15), blackOff = 15)
        assertEquals(1, BackgammonRules.winner(gammon))
        assertEquals(BackgammonResultKind.GAMMON, BackgammonRules.resultKind(gammon, 1))
        // Black's home board is White's 19..24: a white checker on 22 is deep in it.
        val backgammon = bgPos(white = mapOf(22 to 1, 10 to 14), blackOff = 15)
        assertEquals(BackgammonResultKind.BACKGAMMON, BackgammonRules.resultKind(backgammon, 1))
    }

    // --- Turn generation -----------------------------------------------------------------------

    @Test
    fun `generated turns are unique, complete and include making the five point`() {
        val turns = BackgammonRules.generateTurns(BackgammonBoard.initial(), 0, listOf(3, 1))
        assertTrue(turns.all { it.moves.size == 2 })
        assertEquals(turns.size, turns.map { it.result.key() }.toSet().size)
        assertTrue(turns.any { it.result.count(0, bgIx(5)) == 2 })
    }

    @Test
    fun `every generated turn replays as a legal sequence`() {
        val rolls = listOf(listOf(6, 5), listOf(4, 2), bgDice(1, 1), bgDice(5, 5), listOf(3, 6))
        for (remaining in rolls) {
            val turns = BackgammonRules.generateTurns(BackgammonBoard.initial(), 0, remaining)
            assertTrue(turns.isNotEmpty())
            for (t in turns) assertTrue("turn $t.moves for $remaining", bgReplayIsLegal(BackgammonBoard.initial(), 0, remaining, t.moves))
        }
    }

    @Test
    fun `random positions always yield replayable turns for both sides`() {
        var checked = 0
        for ((board, side) in bgRandomPositions(seed = 11, plies = 120, every = 6)) {
            for (roll in listOf(listOf(6, 1), listOf(2, 4), bgDice(3, 3))) {
                val turns = BackgammonRules.generateTurns(board, side, roll)
                for (t in turns.take(25)) {
                    assertTrue(bgReplayIsLegal(board, side, roll, t.moves))
                    checked++
                }
                // The next-move generator agrees with the turn generator.
                if (turns.isEmpty()) assertTrue(BackgammonRules.legalMoves(board, side, roll).isEmpty())
                else assertNotNull(turns.firstOrNull { it.moves.firstOrNull() in BackgammonRules.legalMoves(board, side, roll) })
            }
        }
        assertTrue(checked > 50)
    }
}
