package com.gamesuite.games.shogi

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.games.shogi.ShogiTestKit.sparse
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShogiGameTest {
    private val cc9 = 81

    private fun sq(r: Int, c: Int) = r * 9 + c

    private fun find(g: ShogiGame, from: Int, to: Int, promote: Boolean = false): Int {
        val moves = g.state.value!!.legalMoves
        return moves.first { ShogiMove.from(it) == from && ShogiMove.to(it) == to && ShogiMove.promote(it) == promote }
    }

    private fun play(g: ShogiGame, from: Int, to: Int, promote: Boolean = false) = g.playMove(find(g, from, to, promote))

    private fun drop(g: ShogiGame, type: Int, to: Int) {
        val n = g.state.value!!.n
        g.playMove(g.state.value!!.legalMoves.first { ShogiMove.from(it) == n * n + type && ShogiMove.to(it) == to })
    }

    // ---- basics ----

    @Test fun startingStateHasThirtyMovesAndSenteToMove() {
        val g = ShogiTestKit.local()
        val s = g.state.value!!
        assertEquals(30, s.legalMoves.size)
        assertEquals(0, s.sideToMove)
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.inCheck)
        assertEquals(0, s.ply)
        assertEquals("shogi", g.gameId)
        assertEquals("mini-shogi", ShogiGame(ShogiVariant.MINI).gameId)
    }

    @Test fun illegalAndOutOfRangeMovesAreIgnored() {
        val g = ShogiTestKit.local()
        val before = g.state.value
        g.playMove(ShogiMove.make(0, 1))
        g.playMove(-5)
        g.playMove(ShogiMove.make(sq(6, 0), sq(3, 0))) // pawn two squares
        assertEquals(before, g.state.value)
    }

    @Test fun captureGoesToHandAndCanBeDropped() {
        val g = ShogiTestKit.local()
        g.loadPosition(sparse(9, (8 to 4) to "K", (0 to 4) to "k", (5 to 0) to "R", (5 to 8) to "p", (1 to 0) to "p"), 0)
        play(g, sq(5, 0), sq(1, 0), promote = true) // R takes pawn on (1,0) and promotes into a dragon
        var s = g.state.value!!
        assertEquals(1, s.handCount(0, ShogiPiece.PAWN))
        assertEquals(ShogiPiece.code(0, ShogiPiece.DRAGON), s.cells[sq(1, 0)])
        assertEquals(ShogiPiece.code(1, ShogiPiece.PAWN), s.lastCaptured)
        // Gote moves the other pawn, then Sente drops the captured pawn as its own
        play(g, sq(5, 8), sq(6, 8))
        drop(g, ShogiPiece.PAWN, sq(4, 4))
        s = g.state.value!!
        assertEquals(ShogiPiece.code(0, ShogiPiece.PAWN), s.cells[sq(4, 4)])
        assertEquals(0, s.handCount(0, ShogiPiece.PAWN))
        assertEquals(-1, s.lastFrom)
    }

    @Test fun capturedPromotedPieceReturnsToHandUnpromoted() {
        val g = ShogiTestKit.local()
        g.loadPosition(sparse(9, (8 to 4) to "K", (0 to 4) to "k", (4 to 4) to "R", (4 to 1) to "+p"), 0)
        play(g, sq(4, 4), sq(4, 1))
        assertEquals(1, g.state.value!!.handCount(0, ShogiPiece.PAWN))
    }

    @Test fun matingGoldDropEndsTheGame() {
        val g = ShogiTestKit.local()
        g.loadPosition(
            sparse(9, (0 to 4) to "k", (2 to 4) to "P", (8 to 8) to "K", hands = mapOf((0 to ShogiPiece.GOLD) to 1)), 0
        )
        drop(g, ShogiPiece.GOLD, sq(1, 4))
        val s = g.state.value!!
        assertTrue(s.gameOver)
        assertEquals(ShogiEnd.CHECKMATE, s.endReason)
        assertEquals(ShogiTestKit.HOST, s.winnerPlayerId)
        assertEquals(1, g.sessionWins.value[ShogiTestKit.HOST])
        assertTrue(s.legalMoves.isEmpty())
        // no more moves accepted
        g.playMove(0)
        assertEquals(s, g.state.value)
    }

    @Test fun fourfoldRepetitionIsADraw() {
        val g = ShogiTestKit.local()
        repeat(3) {
            play(g, sq(8, 3), sq(7, 3)); play(g, sq(0, 3), sq(1, 3))
            play(g, sq(7, 3), sq(8, 3)); play(g, sq(1, 3), sq(0, 3))
        }
        val s = g.state.value!!
        assertTrue(s.gameOver)
        assertEquals(ShogiEnd.REPETITION, s.endReason)
        assertNull(s.winnerPlayerId)
        assertEquals(1, g.sessionDraws.value)
    }

    @Test fun repetitionNeedsFourOccurrencesNotThree() {
        val g = ShogiTestKit.local()
        repeat(2) {
            play(g, sq(8, 3), sq(7, 3)); play(g, sq(0, 3), sq(1, 3))
            play(g, sq(7, 3), sq(8, 3)); play(g, sq(1, 3), sq(0, 3))
        }
        assertFalse(g.state.value!!.gameOver)
    }

    @Test fun perpetualCheckLosesForTheCheckingSide() {
        val g = ShogiTestKit.local()
        g.loadPosition(sparse(9, (0 to 0) to "k", (8 to 8) to "K", (5 to 1) to "R"), 0)
        repeat(3) {
            play(g, sq(5, 1), sq(5, 0)) // check down column 0
            assertTrue(g.state.value!!.inCheck)
            play(g, sq(0, 0), sq(0, 1))
            play(g, sq(5, 0), sq(5, 1)) // check down column 1
            play(g, sq(0, 1), sq(0, 0))
        }
        val s = g.state.value!!
        assertTrue(s.gameOver)
        assertEquals(ShogiEnd.PERPETUAL_CHECK, s.endReason)
        assertEquals(ShogiTestKit.GUEST, s.winnerPlayerId) // Gote wins: Sente kept checking
    }

    @Test fun declaringImpasseWinsForTheMover() {
        val g = ShogiTestKit.local()
        val rules = ShogiRules.of(ShogiVariant.STANDARD)
        val pieces = ArrayList<Pair<Pair<Int, Int>, String>>()
        pieces += (0 to 0) to "R"; pieces += (0 to 1) to "R"; pieces += (0 to 2) to "B"; pieces += (0 to 3) to "B"
        for (c in 5..8) pieces += (0 to c) to "G"
        for (c in 0..3) pieces += (1 to c) to "S"
        pieces += (1 to 4) to "K"; pieces += (8 to 4) to "k"
        g.loadPosition(sparse(9, *pieces.toTypedArray()), 0)
        assertTrue(g.state.value!!.canDeclareImpasse)
        assertTrue(rules.canDeclareImpasse(sparse(9, *pieces.toTypedArray()), 0))
        g.declareImpasse()
        val s = g.state.value!!
        assertTrue(s.gameOver)
        assertEquals(ShogiEnd.IMPASSE, s.endReason)
        assertEquals(ShogiTestKit.HOST, s.winnerPlayerId)
    }

    @Test fun declareImpasseIsRefusedWhenNotEligible() {
        val g = ShogiTestKit.local()
        g.declareImpasse()
        assertFalse(g.state.value!!.gameOver)
    }

    @Test fun whyIllegalGivesSpecificReasons() {
        val g = ShogiTestKit.local()
        g.loadPosition(
            sparse(9, (8 to 4) to "K", (0 to 4) to "k", (6 to 2) to "P", (4 to 4) to "p", hands = mapOf((0 to ShogiPiece.PAWN) to 1)), 0
        )
        assertEquals("nifu", g.whyIllegal(-1, sq(4, 2), ShogiPiece.PAWN))
        assertEquals("occupied", g.whyIllegal(-1, sq(4, 4), ShogiPiece.PAWN))
        assertEquals("dead", g.whyIllegal(-1, sq(0, 7), ShogiPiece.PAWN))
        assertEquals("notyours", g.whyIllegal(sq(4, 4), sq(5, 4)))
        assertEquals("cannot", g.whyIllegal(sq(6, 2), sq(3, 2)))
        assertNull(g.whyIllegal(-1, sq(4, 7), ShogiPiece.PAWN))
        assertEquals("nohand", g.whyIllegal(-1, sq(4, 7), ShogiPiece.ROOK))
    }

    @Test fun whyIllegalWhenInCheck() {
        val g = ShogiTestKit.local()
        g.loadPosition(sparse(9, (8 to 4) to "K", (0 to 4) to "r", (0 to 0) to "k", (6 to 0) to "P"), 0)
        assertTrue(g.state.value!!.inCheck)
        assertEquals("incheck", g.whyIllegal(sq(6, 0), sq(5, 0)))
    }

    // ---- session ----

    @Test fun playAgainAlternatesSenteAndKeepsTally() {
        val g = ShogiTestKit.local()
        assertEquals(0, g.state.value!!.senteSeat)
        g.loadPosition(sparse(9, (0 to 4) to "k", (2 to 4) to "P", (8 to 8) to "K", hands = mapOf((0 to ShogiPiece.GOLD) to 1)), 0)
        drop(g, ShogiPiece.GOLD, sq(1, 4))
        g.playAgain()
        val s = g.state.value!!
        assertFalse(s.gameOver)
        assertEquals(1, s.senteSeat)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(1, g.sessionWins.value[ShogiTestKit.HOST])
    }

    @Test fun leavingScoresTheSession() {
        val g = ShogiTestKit.local()
        val results = mutableListOf<com.gamesuite.core.GameResult>()
        g.setOnMatchEnd { results += it }
        g.loadPosition(sparse(9, (0 to 4) to "k", (2 to 4) to "P", (8 to 8) to "K", hands = mapOf((0 to ShogiPiece.GOLD) to 1)), 0)
        drop(g, ShogiPiece.GOLD, sq(1, 4))
        g.leaveSession()
        assertEquals(1, results.size)
        val w = results[0].scores.first { it.isWinner }
        assertEquals(ShogiTestKit.HOST, w.playerId)
        assertTrue(g.matchOver.value)
        g.leaveSession() // idempotent
        assertEquals(1, results.size)
    }

    @Test fun abortIsNotRecordedAsAResult() {
        val g = ShogiTestKit.local()
        val results = mutableListOf<com.gamesuite.core.GameResult>()
        g.setOnMatchEnd { results += it }
        g.abortMatch()
        assertTrue(results.single().wasAborted)
    }

    // ---- resume ----

    @Test fun snapshotRestoreRoundTripsMidGame() {
        val g = ShogiTestKit.local()
        val rnd = Random(7)
        repeat(30) {
            val s = g.state.value!!
            if (s.gameOver) return@repeat
            g.playMove(s.legalMoves[rnd.nextInt(s.legalMoves.size)])
        }
        val snap = g.snapshot()
        assertNotNull(snap)
        val h = ShogiTestKit.local(start = false)
        assertTrue(h.restore(snap!!))
        assertEquals(g.state.value, h.state.value)
        assertEquals(snap, h.snapshot())
        // and the restored game keeps playing
        val s2 = h.state.value!!
        if (!s2.gameOver) h.playMove(s2.legalMoves.first())
    }

    @Test fun snapshotIsNullBeforeStartAndWhenNetworked() {
        assertNull(ShogiTestKit.local(start = false).snapshot())
        val t = ShogiTestKit.table()
        assertNull(t.host.game.snapshot())
    }

    @Test fun restoreRejectsGarbageAndForeignSnapshots() {
        val g = ShogiTestKit.local()
        val snap = g.snapshot()!!
        val h = ShogiTestKit.local(start = false)
        assertFalse(h.restore("not json"))
        assertFalse(h.restore("{}"))
        assertNull(h.state.value) // untouched on failure
        // a standard snapshot does not load into Mini Shogi
        val mini = ShogiTestKit.local(ShogiVariant.MINI, start = false)
        assertFalse(mini.restore(snap))
        // another roster
        val other = ShogiGame()
        other.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(com.gamesuite.core.PlayerInfo("a", "A"), com.gamesuite.core.PlayerInfo("b", "B")), 0, ShogiTestKit.FakeTransport()))
        assertFalse(other.restore(snap))
        // a tampered board (two Sente kings) is refused
        val bad = snap.replace(Regex("\"cells\":\\[([^\\]]*)\\]")) { m ->
            val cells = m.groupValues[1].split(",").toMutableList()
            cells[0] = (ShogiPiece.code(0, ShogiPiece.KING)).toString()
            "\"cells\":[" + cells.joinToString(",") + "]"
        }
        assertFalse(ShogiTestKit.local(start = false).restore(bad))
    }

    @Test fun restoreRejectsAnImpossiblePosition() {
        // the side that just moved cannot be left in check
        val g = ShogiTestKit.local()
        g.loadPosition(sparse(9, (8 to 4) to "K", (0 to 4) to "k", (5 to 4) to "R", side = 1)) // Gote to move, Gote in check: fine
        val snap = g.snapshot()!!
        assertTrue(ShogiTestKit.local(start = false).restore(snap))
        val g2 = ShogiTestKit.local()
        g2.loadPosition(sparse(9, (8 to 4) to "K", (0 to 4) to "k", (5 to 4) to "R", side = 0)) // Sente to move but Gote is in check
        assertFalse(ShogiTestKit.local(start = false).restore(g2.snapshot()!!))
    }

    // ---- undo ----

    @Test fun undoRestoresTheExactPriorState() {
        val g = ShogiTestKit.local()
        val start = g.snapshot()
        assertFalse(g.canUndo)
        play(g, sq(6, 4), sq(5, 4))
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(start, g.snapshot())
        assertFalse(g.canUndo)
    }

    @Test fun undoVersusCpuTakesBackBothMoves() {
        val g = ShogiTestKit.local(players = ShogiTestKit.vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        g.difficulty = CpuDifficulty.EASY
        g.random = Random(1)
        val start = g.snapshot()
        play(g, sq(6, 4), sq(5, 4))
        g.playBotTurn()
        assertEquals(2, g.state.value!!.ply)
        g.undo()
        assertEquals(start, g.snapshot())
    }

    @Test fun undoAfterGameEndReopensTheGameAndRevertsTheTally() {
        val g = ShogiTestKit.local()
        g.loadPosition(sparse(9, (0 to 4) to "k", (2 to 4) to "P", (8 to 8) to "K", hands = mapOf((0 to ShogiPiece.GOLD) to 1)), 0)
        // loadPosition clears history; the mating drop is the first undo step
        drop(g, ShogiPiece.GOLD, sq(1, 4))
        assertTrue(g.state.value!!.gameOver)
        g.undo()
        assertFalse(g.state.value!!.gameOver)
        assertEquals(0, g.sessionWins.value[ShogiTestKit.HOST])
    }

    // ---- hint and CPU ----

    @Test fun hintIsALegalMoveForAHumanOnly() {
        val g = ShogiTestKit.local(players = ShogiTestKit.vsBot, mode = PlayMode.SINGLE_PLAYER_VS_BOT, variant = ShogiVariant.MINI)
        val h = g.hint()
        assertNotNull(h)
        assertTrue(h!! in g.state.value!!.legalMoves)
        assertEquals(h, g.hintMove.value)
        play(g, ShogiMove.from(h), ShogiMove.to(h), ShogiMove.promote(h))
        assertNull(g.hintMove.value)
        assertNull(g.hint()) // CPU's turn
    }

    @Test fun asyncHintMatchesLegalMoves() = runBlocking {
        val g = ShogiTestKit.local(variant = ShogiVariant.MINI)
        val h = g.requestHint()
        assertNotNull(h)
        assertTrue(h!! in g.state.value!!.legalMoves)
    }

    @Test fun cpuPlaysLegalMovesAtEveryLevel() = runBlocking {
        for (level in listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            val g = ShogiTestKit.local(
                variant = ShogiVariant.MINI,
                players = listOf(com.gamesuite.core.PlayerInfo("b1", "Bot1", true), com.gamesuite.core.PlayerInfo("b2", "Bot2", true)),
                mode = PlayMode.SINGLE_PLAYER_VS_BOT
            )
            g.difficulty = level
            g.random = Random(3)
            repeat(3) {
                val before = g.state.value!!
                if (before.gameOver) return@repeat
                val legalTargets = before.legalMoves.map { ShogiMove.to(it) }.toSet()
                g.playBotTurnAsync()
                val after = g.state.value!!
                assertEquals(before.ply + 1, after.ply)
                assertTrue(after.lastTo in legalTargets)
            }
        }
    }

    @Test fun cpuLevelsAreDistinct() {
        for (v in ShogiVariant.values()) {
            val e = shogiSearchParams(v, CpuDifficulty.EASY)
            val m = shogiSearchParams(v, CpuDifficulty.MEDIUM)
            val h = shogiSearchParams(v, CpuDifficulty.HARD)
            assertTrue(e.maxDepth < m.maxDepth && m.maxDepth < h.maxDepth)
            assertTrue(e.noiseCp > 0 && m.noiseCp == 0 && h.noiseCp == 0)
        }
    }

    @Test fun searchTakesAFreeBishopAndFindsMateInOne() {
        val rules = ShogiRules.of(ShogiVariant.STANDARD)
        val free = sparse(9, (8 to 4) to "K", (0 to 4) to "k", (5 to 0) to "R", (5 to 6) to "b")
        val best = rules.chooseMove(free, 2, 2000)!!
        assertEquals(sq(5, 6), ShogiMove.to(best))
        val mate = sparse(9, (0 to 4) to "k", (2 to 4) to "P", (8 to 8) to "K", (4 to 0) to "R", hands = mapOf((0 to ShogiPiece.GOLD) to 1))
        val m = rules.chooseMove(mate, 3, 3000)!!
        val c = rules.make(mate, m)
        assertEquals(0, rules.legalMoves(mate).size)
        rules.unmake(mate, m, c)
    }

    @Test fun searchNeverMutatesItsInput() {
        val rules = ShogiRules.of(ShogiVariant.STANDARD)
        val p = rules.startPosition()
        val before = p.key()
        rules.chooseMove(p, 3, 500)
        assertEquals(before, p.key())
    }

    @Test fun easyIsNotAlwaysTheBestMove() {
        val rules = ShogiRules.of(ShogiVariant.MINI)
        val p = rules.startPosition()
        val picks = (0 until 12).map { rules.chooseMove(p, 1, 200, 140, Random(it)) }.toSet()
        assertTrue("Easy should vary its choice", picks.size > 1)
    }

    @Test fun differentSenteSeatsFlipTurnOwnership() {
        val g2 = ShogiGame()
        g2.firstOpenerIndex = 1
        g2.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, ShogiTestKit.humans, 0, ShogiTestKit.FakeTransport()))
        g2.startMatch()
        assertEquals(1, g2.state.value!!.currentPlayerIndex)
    }
}
