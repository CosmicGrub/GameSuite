package com.gamesuite.games.chinesecheckers

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Rules, win, resume, undo, hint, computer play and fuzzing for the Chinese Checkers session engine. */
class ChineseCheckersGameTest {
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }
    private val vsCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun cell(q: Int, r: Int) = CCBoard.indexOf(q, r)

    fun newGame(
        players: List<PlayerInfo> = humans(2),
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        seed: Int = 1,
        pieces: Int = 10,
        start: Boolean = true
    ): ChineseCheckersGame {
        val g = ChineseCheckersGame(Random(seed))
        g.piecesPerSeat = pieces
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        if (start) g.startMatch()
        return g
    }

    private fun firstMove(g: ChineseCheckersGame): CCMove {
        val s = g.state.value!!
        val owners = IntArray(CCBoard.CELLS) { s.cells[it] ?: -1 }
        return CCRules.movesFor(owners, s.currentPlayerIndex).first()
    }

    /** A hand-built snapshot in the engine's own format. */
    fun snapshotJson(
        cells: Map<Int, Int>, players: List<PlayerInfo>, current: Int, pieces: Int,
        boardOver: Boolean = false, winner: String? = null, wins: Map<String, Int>? = null, next: Int = 0
    ): String {
        val cellList = (0 until CCBoard.CELLS).joinToString(",") { cells[it]?.toString() ?: "null" }
        val ps = players.joinToString(",") { "{\"playerId\":\"${it.playerId}\",\"displayName\":\"${it.displayName}\",\"isBot\":${it.isBot}}" }
        val w = (wins ?: players.associate { it.playerId to 0 }).entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        return "{\"cells\":[$cellList],\"players\":[$ps],\"currentPlayerIndex\":$current,\"pieces\":$pieces," +
            "\"boardOver\":$boardOver,\"winnerPlayerId\":${winner?.let { "\"$it\"" } ?: "null"}," +
            "\"sessionWins\":{$w},\"sessionDraws\":0,\"nextStartingPlayerIndex\":$next,\"difficulty\":\"MEDIUM\"}"
    }

    /** Short game, 2 seats: seat 0 needs one step ((1,4) -> (2,4)) to fill its goal; seat 1 sits in the middle. */
    fun almostWonSnapshot(players: List<PlayerInfo> = humans(2)): String {
        val cells = HashMap<Int, Int>()
        listOf(4 to 4, 3 to 4, 4 to 3, 3 to 3, 4 to 2).forEach { (q, r) -> cells[cell(q, r)] = 0 }
        cells[cell(1, 4)] = 0
        listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to -1).forEach { (q, r) -> cells[cell(q, r)] = 1 }
        return snapshotJson(cells, players, current = 0, pieces = 6)
    }

    // ---- start and turns ----------------------------------------------------------------

    @Test fun startDealsEveryoneTheirCorner() {
        for (n in listOf(2, 3, 4, 6)) {
            val g = newGame(humans(n))
            val s = g.state.value!!
            assertEquals(n, s.players.size)
            for (seat in 0 until n) assertEquals(10, s.cells.count { it == seat })
            assertEquals(0, s.currentPlayerIndex)
            assertFalse(s.boardOver)
        }
    }

    @Test fun shortGameDealsSixEach() {
        val g = newGame(pieces = 6)
        assertEquals(6, g.state.value!!.pieces)
        assertEquals(6, g.state.value!!.cells.count { it == 0 })
    }

    @Test fun aLegalMoveMovesThePieceAndPassesTheTurn() {
        val g = newGame(humans(3))
        val m = firstMove(g)
        assertTrue(g.requestMove(m.from, m.to))
        val s = g.state.value!!
        assertNull(s.cells[m.from]); assertEquals(0, s.cells[m.to])
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(m.from, s.lastFrom); assertEquals(m.to, s.lastTo); assertEquals(0, s.lastMover)
        assertEquals(1, s.turnCount)
    }

    @Test fun turnsRotateThroughEverySeat() {
        val g = newGame(humans(4))
        val order = ArrayList<Int>()
        repeat(8) {
            order += g.state.value!!.currentPlayerIndex
            val m = firstMove(g)
            assertTrue(g.requestMove(m.from, m.to))
        }
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), order)
    }

    @Test fun illegalRequestsChangeNothing() {
        val g = newGame()
        val before = g.state.value
        val opp = g.state.value!!.cells.indexOfFirst { it == 1 }
        assertFalse("opponent piece", g.requestMove(opp, firstMove(g).to))
        val own = firstMove(g).from
        assertFalse("not a reachable hole", g.requestMove(own, CCBoard.tip(3)))
        assertFalse("empty source", g.requestMove(cell(0, 0), cell(1, 0)))
        assertFalse("out of range", g.requestMove(-1, 500))
        assertTrue(before === g.state.value)
    }

    @Test fun explainTapGivesTheRightReason() {
        val g = newGame()
        val s = g.state.value!!
        val own = firstMove(g).from
        val opp = s.cells.indexOfFirst { it == 1 }
        assertEquals(CCReject.NONE, g.explainTap(null, own))
        assertEquals(CCReject.OPPONENT_PIECE, g.explainTap(null, opp))
        assertEquals(CCReject.PICK_A_PIECE, g.explainTap(null, cell(0, 0)))
        assertEquals(CCReject.UNREACHABLE, g.explainTap(own, cell(0, 0)))
        assertEquals(CCReject.NONE, g.explainTap(own, firstMove(g).to))
        // a piece buried behind its own neighbours has no move
        val buried = s.cells.indices.first { s.cells[it] == 0 && g.destinationsFrom(it).isEmpty() }
        assertEquals(CCReject.BOXED_IN, g.explainTap(null, buried))
    }

    @Test fun explainTapWhenItIsTheComputersTurn() {
        val g = newGame(listOf(PlayerInfo("cpu", "CPU", isBot = true), PlayerInfo("you", "You")), PlayMode.SINGLE_PLAYER_VS_BOT)
        assertEquals(CCReject.NOT_YOUR_TURN, g.explainTap(null, firstMove(g).from))
        assertFalse(g.isMyTurn())
    }

    // ---- winning -------------------------------------------------------------------------

    @Test fun fillingTheOppositeCornerWinsTheBoardAndTheSession() {
        val g = newGame(start = false)
        assertTrue(g.restore(almostWonSnapshot()))
        val results = ArrayList<GameResult>()
        g.setOnMatchEnd { results += it }
        assertTrue(g.requestMove(cell(1, 4), cell(2, 4)))
        val s = g.state.value!!
        assertTrue(s.boardOver)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(CCEnd.GOAL, s.endReason)
        assertEquals(1, g.sessionWins.value["p1"])
        assertFalse("no moves after the board ends", g.requestMove(cell(0, 0), cell(1, 0)))
        g.leaveSession()
        assertTrue(g.matchOver.value)
        assertEquals(1, results.size)
        assertTrue(results[0].scores.first { it.playerId == "p1" }.isWinner)
        assertFalse(results[0].scores.first { it.playerId == "p2" }.isWinner)
    }

    @Test fun playAgainDealsAFreshBoardAndAlternatesTheOpener() {
        val g = newGame(start = false)
        assertTrue(g.restore(almostWonSnapshot()))
        g.requestMove(cell(1, 4), cell(2, 4))
        g.playAgain()
        val s = g.state.value!!
        assertFalse(s.boardOver)
        assertEquals(0, s.turnCount)
        assertEquals("the tally survives", 1, g.sessionWins.value["p1"])
    }

    @Test fun theMoveLimitEndsABlockedBoardOnProgress() {
        // A snapshot already at the last allowed move (turnCount edited into the JSON)
        val json = almostWonSnapshot().replace("\"difficulty\"", "\"turnCount\":${ChineseCheckersGame.TURNS_PER_SEAT * 2 - 1},\"difficulty\"")
        val g2 = newGame(start = false)
        assertTrue(g2.restore(json))
        // a non-winning move (a step sideways) trips the limit; seat 0 is far ahead so it leads
        val s0 = g2.state.value!!
        val owners = IntArray(CCBoard.CELLS) { s0.cells[it] ?: -1 }
        val sideways = CCRules.movesFor(owners, 0).first { it.from != cell(1, 4) }
        g2.requestMove(sideways.from, sideways.to)
        val s = g2.state.value!!
        assertTrue(s.boardOver)
        assertEquals(CCEnd.LIMIT, s.endReason)
        assertEquals("p1", s.winnerPlayerId)
    }

    // ---- resume ---------------------------------------------------------------------------

    @Test fun snapshotRestoreRoundTripsAnyPosition() {
        val g = newGame(humans(3))
        repeat(15) { val m = firstMove(g); g.requestMove(m.from, m.to) }
        val snap = g.snapshot()
        assertNotNull(snap)
        val g2 = newGame(humans(3), start = false)
        assertTrue(g2.restore(snap!!))
        assertEquals(g.state.value, g2.state.value)
        assertEquals(g.sessionWins.value, g2.sessionWins.value)
        assertEquals("the restored game keeps playing", true, g2.requestMove(firstMove(g2).from, firstMove(g2).to))
    }

    @Test fun snapshotsKeepDifficultyAndShortPieces() {
        val g = newGame(pieces = 6)
        g.difficulty = CpuDifficulty.HARD
        val g2 = newGame(start = false)
        assertTrue(g2.restore(g.snapshot()!!))
        assertEquals(CpuDifficulty.HARD, g2.difficulty)
        assertEquals(6, g2.state.value!!.pieces)
    }

    @Test fun badSnapshotsAreRefusedAndLeaveTheGameUntouched() {
        val g = newGame()
        val good = g.snapshot()!!
        val before = g.state.value
        val bad = listOf(
            "", "not json", "{}", good.replace("\"pieces\":10", "\"pieces\":7"),
            good.replace("\"p1\"", "\"zzz\""), good.replace("\"currentPlayerIndex\":0", "\"currentPlayerIndex\":9"),
            good.replaceFirst("0,", "1,"), // piece counts no longer match
            good.replace("\"cells\":[", "\"cells\":[null,")
        )
        bad.forEach { assertFalse("accepted: ${it.take(60)}", g.restore(it)) }
        assertTrue(before === g.state.value)
        // wrong roster size
        assertFalse(newGame(humans(3), start = false).restore(good))
    }

    @Test fun noSnapshotWhenNothingToSaveOrNetworked() {
        assertNull(newGame(start = false).snapshot())
        val g = newGame()
        g.leaveSession()
        assertNull(g.snapshot())
    }

    // ---- undo ------------------------------------------------------------------------------

    @Test fun undoTakesBackTheLastHumanMoveExactly() {
        val g = newGame()
        val start = g.state.value
        assertFalse(g.canUndo)
        val m = firstMove(g)
        g.requestMove(m.from, m.to)
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(start, g.state.value)
        assertFalse(g.canUndo)
    }

    @Test fun undoAgainstTheComputerAlsoTakesBackItsReply() {
        val g = newGame(vsCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        val start = g.state.value
        val m = firstMove(g)
        g.requestMove(m.from, m.to)
        runBlocking { g.playBotTurnAsync() }
        assertEquals(2, g.state.value!!.turnCount)
        g.undo()
        assertEquals(start, g.state.value)
    }

    @Test fun undoStacksAndUndoingTheWinningMoveReopensTheBoard() {
        val g = newGame(start = false)
        g.restore(almostWonSnapshot())
        g.requestMove(cell(1, 4), cell(2, 4))
        assertTrue(g.state.value!!.boardOver)
        g.undo()
        val s = g.state.value!!
        assertFalse(s.boardOver)
        assertEquals(0, g.sessionWins.value["p1"] ?: 0)
        assertEquals(0, s.cells[cell(1, 4)])
        assertNull(s.cells[cell(2, 4)])
    }

    @Test fun manyMovesCanBeUndoneOneByOne() {
        val g = newGame(humans(2))
        val states = ArrayList<CCState>()
        repeat(6) { states += g.state.value!!; val m = firstMove(g); g.requestMove(m.from, m.to) }
        for (i in 5 downTo 0) { g.undo(); assertEquals(states[i], g.state.value) }
        assertFalse(g.canUndo)
    }

    // ---- hint ------------------------------------------------------------------------------

    @Test fun hintSuggestsALegalMoveForThePlayerToMove() {
        val g = newGame()
        val h = g.hint()
        assertNotNull(h)
        val s = g.state.value!!
        val owners = IntArray(CCBoard.CELLS) { s.cells[it] ?: -1 }
        val hm = h!!
        assertTrue(hm.to in CCRules.destinations(owners, hm.from))
        assertEquals(0, s.cells[hm.from])
        assertEquals(hm, g.hintMove.value)
        val m = firstMove(g)
        g.requestMove(m.from, m.to)
        assertNull("a move clears the hint", g.hintMove.value)
    }

    @Test fun hintIsRefusedOnTheComputersTurnAndAfterTheBoardEnds() {
        val g = newGame(listOf(PlayerInfo("cpu", "CPU", isBot = true), PlayerInfo("you", "You")), PlayMode.SINGLE_PLAYER_VS_BOT)
        assertNull(g.hint())
        val h = newGame(start = false)
        h.restore(almostWonSnapshot())
        h.requestMove(cell(1, 4), cell(2, 4))
        assertNull(h.hint())
        assertNull(runBlocking { h.requestHint() })
    }

    @Test fun asyncHintMatchesTheSyncOne() {
        val g = newGame()
        val a = runBlocking { g.requestHint() }
        val b = g.hint()
        assertEquals(a, b)
    }

    // ---- computer --------------------------------------------------------------------------

    @Test fun playBotTurnAsyncPlaysOnlyWhenItIsABotsTurn() {
        val g = newGame(vsCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        runBlocking { g.playBotTurnAsync() }
        assertEquals(0, g.state.value!!.turnCount) // human's turn: nothing happens
        val m = firstMove(g)
        g.requestMove(m.from, m.to)
        runBlocking { g.playBotTurnAsync() }
        assertEquals(2, g.state.value!!.turnCount)
        assertEquals(1, g.state.value!!.lastMover)
    }

    @Test fun fullComputerGamesThroughTheSessionEngineAlwaysFinishLegally() {
        for (d in CpuDifficulty.values()) {
            val players = listOf(PlayerInfo("a", "A", isBot = true), PlayerInfo("b", "B", isBot = true))
            val g = newGame(players, PlayMode.SINGLE_PLAYER_VS_BOT, seed = 5)
            g.difficulty = d
            var guard = 0
            while (!g.state.value!!.boardOver && guard++ < 1000) {
                val before = g.state.value!!
                g.playBotTurn()
                val after = g.state.value!!
                assertEquals(before.turnCount + 1, after.turnCount)
                for (seat in 0..1) assertEquals(10, after.cells.count { it == seat })
            }
            val s = g.state.value!!
            assertTrue("$d should finish", s.boardOver)
            assertNotNull("$d should have a winner", s.winnerPlayerId)
        }
    }

    // ---- fuzz --------------------------------------------------------------------------------

    @Test fun randomLegalPlayNeverThrowsAndKeepsEveryInvariant() {
        val rnd = Random(2024)
        repeat(30) { gameNo ->
            val seats = listOf(2, 3, 4, 6)[gameNo % 4]
            val pieces = if (gameNo % 3 == 0) 6 else 10
            val g = newGame(humans(seats), pieces = pieces)
            repeat(250) { step ->
                val s = g.state.value!!
                if (s.boardOver) return@repeat
                val owners = IntArray(CCBoard.CELLS) { s.cells[it] ?: -1 }
                val moves = CCRules.movesFor(owners, s.currentPlayerIndex)
                val m = moves[rnd.nextInt(moves.size)]
                assertTrue(g.requestMove(m.from, m.to))
                val a = g.state.value!!
                for (seat in 0 until seats) assertEquals(pieces, a.cells.count { it == seat })
                if (step % 17 == 0) {
                    val snap = g.snapshot()!!
                    val g2 = newGame(humans(seats), start = false)
                    assertTrue(g2.restore(snap))
                    assertEquals(a, g2.state.value)
                }
                if (step % 29 == 0 && g.canUndo) {
                    g.undo()
                    val b = g.state.value!!
                    for (seat in 0 until seats) assertEquals(pieces, b.cells.count { it == seat })
                }
            }
        }
    }

    @Test fun garbageNeverCrashesRestore() {
        val g = newGame()
        val rnd = Random(9)
        val good = g.snapshot()!!
        repeat(200) {
            val cut = rnd.nextInt(good.length)
            val mutated = if (it % 2 == 0) good.substring(0, cut) else good.replaceRange(cut, minOf(good.length, cut + 3), "x9\"")
            g.restore(mutated) // either accepted (valid by luck) or refused; never throws
        }
    }

    @Test fun abortMatchEndsWithoutScores() {
        val g = newGame()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }
}
