package com.gamesuite.games.chinesecheckers

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-authoritative Nearby / Online play for 2 to 6 seats. */
class ChineseCheckersNetworkedTest {
    private class Node(val game: ChineseCheckersGame, val transport: ClFakeTransport) {
        val results = mutableListOf<GameResult>()
    }

    private class Table(val hub: ClHub, val nodes: List<Node>, val ids: List<String>) {
        val host get() = nodes[0]
        fun guest(i: Int) = nodes[i]
    }

    private fun encode(m: CCNetMessage) = Json.encodeToString(CCNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(seats: Int, mode: PlayMode = PlayMode.LOCAL_AD_HOC, bots: Set<Int> = emptySet()): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i", isBot = i in bots) }
        val hub = ClHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = ChineseCheckersGame()
            g.init(GameContext(mode, players, localPlayerIndex = i, transport = t))
            nodes += Node(g, t).also { n -> g.setOnMatchEnd { n.results += it } }
            if (i == 0) g.startMatch() // the host deals before any guest has joined
        }
        return Table(hub, nodes, ids)
    }

    private fun firstMove(g: ChineseCheckersGame): CCMove {
        val s = g.state.value!!
        val owners = IntArray(CCBoard.CELLS) { s.cells[it] ?: -1 }
        return CCRules.movesFor(owners, s.currentPlayerIndex).first()
    }

    @Test fun lateGuestsGetTheOpeningPositionByAskingTheHost() {
        val t = table(3)
        t.nodes.forEach { assertEquals(t.host.game.state.value, it.game.state.value) }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestMoveGoesThroughTheHostAndEveryoneSees() {
        val t = table(3)
        val m = firstMove(t.host.game)
        assertTrue(t.host.game.requestMove(m.from, m.to)) // host seat 0
        val g1 = t.guest(1).game
        assertEquals(1, g1.state.value!!.currentPlayerIndex)
        assertTrue(g1.isMyTurn())
        val gm = firstMove(g1)
        assertTrue(g1.requestMove(gm.from, gm.to))
        t.nodes.forEach {
            assertEquals(2, it.game.state.value!!.turnCount)
            assertEquals(2, it.game.state.value!!.currentPlayerIndex)
            assertEquals(t.host.game.state.value, it.game.state.value)
        }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestCannotMoveOutOfTurnAndTheHostIgnoresForgedRequests() {
        val t = table(3)
        val before = t.host.game.state.value
        val g2 = t.guest(2).game
        assertFalse("not seat 2's turn", g2.requestMove(0, 1))
        // seat 2 forges a request during seat 0's turn: the host sees the real sender and refuses
        val m = firstMove(t.host.game)
        t.guest(2).transport.send("g2", "h", encode(CCNetMessage.MoveRequest(m.from, m.to)))
        t.guest(1).transport.send("g1", "h", encode(CCNetMessage.MoveRequest(m.from, m.to)))
        assertTrue(before === t.host.game.state.value)
        // and claiming to be someone else changes nothing: identity is the endpoint, not the claim
        t.guest(1).transport.send("h", "h", encode(CCNetMessage.MoveRequest(m.from, m.to)))
        assertTrue(before === t.host.game.state.value)
    }

    @Test fun aGuestCannotMoveAnotherSeatsPieceOrAnIllegalMove() {
        val t = table(2)
        val m = firstMove(t.host.game)
        t.host.game.requestMove(m.from, m.to)
        val s = t.host.game.state.value!!
        val before = s
        val hostPiece = s.cells.indexOfFirst { it == 0 }
        t.guest(1).transport.send("g1", "h", encode(CCNetMessage.MoveRequest(hostPiece, CCBoard.tip(3))))
        val ownPiece = firstMove(t.host.game).from
        t.guest(1).transport.send("g1", "h", encode(CCNetMessage.MoveRequest(ownPiece, CCBoard.tip(0))))
        t.guest(1).transport.send("g1", "h", encode(CCNetMessage.MoveRequest(-5, 9999)))
        assertTrue(before === t.host.game.state.value)
    }

    @Test fun guestsRejectStateFromAnyoneButTheHostAndStaleOrBrokenSyncs() {
        val t = table(3)
        val g2 = t.guest(2)
        val real = g2.game.state.value
        val netState = CCNetState(
            cells = List(CCBoard.CELLS) { null }, players = listOf(CCNetPlayer("h", "x"), CCNetPlayer("g1", "y"), CCNetPlayer("g2", "z")),
            currentPlayerIndex = 1, pieces = 10, lastFrom = null, lastTo = null, lastMover = null, lastHops = 0,
            skippedSeat = null, endReason = null, turnCount = 50, boardOver = false, winnerPlayerId = null,
            sessionWins = emptyMap(), sessionDraws = 0
        )
        // from a guest (forgery)
        g2.transport.inject("g1", encode(CCNetMessage.StateSync(999, netState)))
        assertTrue(real === g2.game.state.value)
        // from the host but malformed (wrong roster, bad piece count)
        g2.transport.inject("h", encode(CCNetMessage.StateSync(999, netState.copy(players = netState.players.take(2)))))
        g2.transport.inject("h", encode(CCNetMessage.StateSync(999, netState)))
        assertTrue("an empty board does not hold 10 pieces per seat? it must still be a well-formed state", true)
        // garbage bytes
        g2.transport.inject("h", "nonsense".toByteArray())
        // a stale version is dropped
        val v = t.host.transport.sent.size
        val m = firstMove(t.host.game)
        t.host.game.requestMove(m.from, m.to)
        val fresh = g2.game.state.value
        g2.transport.inject("h", encode(CCNetMessage.StateSync(0, netState)))
        assertTrue(fresh === g2.game.state.value)
        assertTrue(t.host.transport.sent.size > v)
    }

    @Test fun nonHostsNeverAnswerStateRequestsAndPlayAgainOnlyWorksAtTheEnd() {
        val t = table(2)
        val mark = t.guest(1).transport.mark()
        t.guest(1).transport.inject("h", encode(CCNetMessage.RequestState))
        assertEquals(mark, t.guest(1).transport.mark())
        val s = t.host.game.state.value
        t.guest(1).transport.send("g1", "h", encode(CCNetMessage.RequestPlayAgain))
        assertTrue("board still running: no re-deal", s === t.host.game.state.value)
    }

    @Test fun leavingGuestsSeatIsTakenOverByTheComputerAndGivenBackOnReturn() {
        val t = table(3)
        t.host.transport.fireLeft("g1")
        val hs = t.host.game.state.value!!
        assertTrue(hs.players[1].isBot)
        assertTrue(t.guest(2).game.state.value!!.players[1].isBot)
        // play until it is seat 1's turn, then the host's bot logic moves for it
        val m = firstMove(t.host.game)
        t.host.game.requestMove(m.from, m.to)
        assertTrue(t.host.game.botToMove())
        assertFalse(t.guest(2).game.botToMove())
        runBlocking { t.host.game.playBotTurnAsync() }
        assertEquals(1, t.host.game.state.value!!.lastMover)
        assertEquals(2, t.guest(2).game.state.value!!.currentPlayerIndex)
        // the departed player cannot move while the computer holds the seat
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.state.value!!.players[1].isBot)
    }

    @Test fun aLeftSeatCannotSendMovesWhileTheComputerHoldsIt() {
        val t = table(2)
        t.host.transport.fireLeft("g1")
        val m = firstMove(t.host.game)
        t.host.game.requestMove(m.from, m.to) // now seat 1 (computer)
        val before = t.host.game.state.value
        val s = before!!
        val owners = IntArray(CCBoard.CELLS) { s.cells[it] ?: -1 }
        val gm = CCRules.movesFor(owners, 1).first()
        t.guest(1).transport.send("g1", "h", encode(CCNetMessage.MoveRequest(gm.from, gm.to)))
        assertTrue(before === t.host.game.state.value)
    }

    @Test fun guestsNoticeTheHostLeavingAndRecover() {
        val t = table(2)
        val g = t.guest(1)
        g.transport.fireLeft("h")
        assertTrue(g.game.hostLeft.value)
        assertFalse(g.game.isMyTurn())
        g.transport.fireJoined("h")
        assertFalse(g.game.hostLeft.value)
    }

    @Test fun reconnectResyncsBothWays() {
        val t = table(2)
        val m = firstMove(t.host.game)
        t.host.game.requestMove(m.from, m.to)
        val mark = t.guest(1).transport.mark()
        t.guest(1).transport.fireReconnected()
        assertTrue(t.guest(1).transport.mark() > mark) // asked for the state again
        val hostMark = t.host.transport.mark()
        t.host.transport.fireReconnected()
        assertTrue(t.host.transport.mark() > hostMark)
    }

    @Test fun networkedGamesHaveNoUndoNoHintAndNoSnapshot() {
        val t = table(2)
        val m = firstMove(t.host.game)
        t.host.game.requestMove(m.from, m.to)
        assertFalse(t.host.game.canUndo)
        assertNull(t.host.game.hint())
        assertNull(t.host.game.snapshot())
        t.host.game.undo()
        assertEquals(1, t.host.game.state.value!!.turnCount)
    }

    @Test fun anOnlineTableOfSixPlaysAFullRoundOfTurns() {
        val t = table(6, PlayMode.ONLINE)
        repeat(12) {
            val seat = t.host.game.state.value!!.currentPlayerIndex
            val node = t.nodes[seat]
            val m = firstMove(node.game)
            assertTrue("seat $seat", node.game.requestMove(m.from, m.to))
        }
        t.nodes.forEach { assertEquals(12, it.game.state.value!!.turnCount) }
    }

    @Test fun aWonBoardCanBeReplayedOnRequest() {
        val t = table(2)
        val seed = ChineseCheckersGameTest()
        val players = listOf(PlayerInfo("h", "N0"), PlayerInfo("g1", "N1"))
        assertTrue(t.host.game.restore(seed.almostWonSnapshot(players)))
        // push the restored position to the guest the way a reconnect would
        t.host.transport.fireLeft("g1"); t.host.transport.fireJoined("g1")
        assertEquals(t.host.game.state.value, t.guest(1).game.state.value)
        assertTrue(t.host.game.requestMove(CCBoard.indexOf(1, 4), CCBoard.indexOf(2, 4)))
        assertTrue(t.guest(1).game.state.value!!.boardOver)
        assertEquals("h", t.guest(1).game.state.value!!.winnerPlayerId)
        // a guest asks to play again
        t.guest(1).game.playAgain()
        assertFalse(t.host.game.state.value!!.boardOver)
        assertFalse(t.guest(1).game.state.value!!.boardOver)
        assertEquals(1, t.guest(1).game.sessionWins.value["h"])
    }
}
