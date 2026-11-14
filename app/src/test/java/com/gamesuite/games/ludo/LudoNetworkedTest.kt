package com.gamesuite.games.ludo

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.chinesecheckers.ClFakeTransport
import com.gamesuite.games.chinesecheckers.ClHub
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Ludo: the host owns the die and the engine. */
class LudoNetworkedTest {
    private class Node(val game: LudoGame, val transport: ClFakeTransport)
    private class Table(val hub: ClHub, val nodes: List<Node>) {
        val host get() = nodes[0]
    }

    private fun encode(m: LudoNetMessage) = Json.encodeToString(LudoNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(seats: Int, die: Random = ScriptedRandom(6, 6, 2, 3, 4, 5, 1), mode: PlayMode = PlayMode.LOCAL_AD_HOC): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = ClHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = LudoGame(if (i == 0) die else Random(1000 + i)) // guests' own dice must never be used
            g.init(GameContext(mode, players, localPlayerIndex = i, transport = t))
            nodes += Node(g, t)
            if (i == 0) g.startMatch()
        }
        return Table(hub, nodes)
    }

    @Test fun guestsReceiveTheOpeningTableFromTheHost() {
        val t = table(3)
        t.nodes.forEach { assertEquals(t.host.game.state.value, it.game.state.value) }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun onlyTheHostEverRollsAndGuestsOnlyAsk() {
        val die = ScriptedRandom(6, 4)
        val t = table(2, die)
        t.host.game.roll()            // seat 0: 6
        assertEquals(1, die.calls)
        t.host.game.moveToken(0)
        t.host.game.roll()            // seat 0 again: 4
        t.host.game.moveToken(0)
        val g1 = t.nodes[1].game
        assertTrue(g1.isMyTurn())
        val calls = die.calls
        val ok = g1.roll()            // a request: the host rolls with its own die
        assertTrue(ok)
        assertEquals(calls + 1, die.calls)
        assertEquals(t.host.game.state.value, g1.state.value)
        assertEquals(die.calls, g1.state.value!!.rollSerial)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestMovesATokenThroughTheHost() {
        val t = table(2, ScriptedRandom(3, 6, 2))
        t.host.game.roll()            // 3: no move, turn to g1
        val g1 = t.nodes[1].game
        assertEquals(1, g1.state.value!!.currentPlayerIndex)
        g1.roll()                     // 6
        assertTrue(g1.state.value!!.awaitingMove)
        assertTrue(g1.moveToken(2))
        assertEquals(0, t.host.game.state.value!!.tokens[1 * 4 + 2])
        assertEquals(t.host.game.state.value, g1.state.value)
    }

    @Test fun forgedAndOutOfTurnRequestsAreIgnored() {
        val t = table(3, ScriptedRandom(6, 6))
        val before = t.host.game.state.value
        // seats 1 and 2 ask to roll on seat 0's turn
        t.nodes[1].transport.send("g1", "h", encode(LudoNetMessage.RollRequest))
        t.nodes[2].transport.send("g2", "h", encode(LudoNetMessage.RollRequest))
        assertTrue(before === t.host.game.state.value)
        // claiming to be the host changes nothing: identity is the endpoint
        t.nodes[1].transport.send("h", "h", encode(LudoNetMessage.RollRequest))
        assertTrue(before === t.host.game.state.value)
        t.host.game.roll()
        val rolled = t.host.game.state.value
        t.nodes[2].transport.send("g2", "h", encode(LudoNetMessage.MoveRequest(0)))  // not g2's turn
        t.nodes[1].transport.send("g1", "h", encode(LudoNetMessage.MoveRequest(0)))  // not g1's turn
        assertTrue(rolled === t.host.game.state.value)
        t.nodes[1].transport.send("g1", "h", "xx".toByteArray())
        assertTrue(rolled === t.host.game.state.value)
    }

    @Test fun badTokenIndicesAndMovesWithoutARollAreRefusedByTheHost() {
        val t = table(2, ScriptedRandom(3, 6))
        t.host.game.roll()            // 3 -> g1's turn, nothing rolled yet
        val s = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(LudoNetMessage.MoveRequest(0)))   // not rolled
        assertTrue(s === t.host.game.state.value)
        t.nodes[1].game.roll()
        val rolled = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(LudoNetMessage.MoveRequest(9)))
        t.nodes[1].transport.send("g1", "h", encode(LudoNetMessage.MoveRequest(-1)))
        t.nodes[1].transport.send("g1", "h", encode(LudoNetMessage.RollRequest))      // already rolled
        assertTrue(rolled === t.host.game.state.value)
    }

    @Test fun guestsRejectForgedStaleAndMalformedSyncs() {
        val t = table(2)
        val g = t.nodes[1]
        val real = g.game.state.value
        val bogus = LudoNetState(
            tokens = List(8) { 57 }, players = listOf(LudoNetPlayer("h", "a"), LudoNetPlayer("g1", "b")),
            currentPlayerIndex = 0, dice = 0, awaitingMove = false, sixesInRow = 0, needSix = true, exactFinish = true,
            event = "start", eventSeat = null, eventToken = null, eventFrom = -1, eventTo = -1, captured = 0,
            rollSerial = 0, moveSerial = 0, boardOver = false, winnerPlayerId = null, sessionWins = emptyMap()
        )
        g.transport.inject("h", encode(LudoNetMessage.StateSync(500, bogus)))                       // out-of-range tokens
        g.transport.inject("g1", encode(LudoNetMessage.StateSync(501, bogus.copy(tokens = List(8) { -1 })))) // not from the host
        g.transport.inject("h", "###".toByteArray())
        assertTrue(real === g.game.state.value)
        t.host.game.roll()
        val fresh = g.game.state.value
        g.transport.inject("h", encode(LudoNetMessage.StateSync(0, bogus.copy(tokens = List(8) { -1 }))))   // stale version
        assertTrue(fresh === g.game.state.value)
    }

    @Test fun aDepartedSeatIsPlayedByTheComputerAndReturnedOnReconnect() {
        val t = table(3, ScriptedRandom(3, 3, 3, 3))
        t.host.transport.fireLeft("g1")
        assertTrue(t.host.game.state.value!!.players[1].isBot)
        assertTrue(t.nodes[2].game.state.value!!.players[1].isBot)
        t.host.game.roll()            // 3: pass to seat 1 (computer)
        assertTrue(t.host.game.botToMove())
        assertFalse(t.nodes[2].game.botToMove())
        runBlocking { t.host.game.playBotTurnAsync() }
        assertEquals(2, t.host.game.state.value!!.currentPlayerIndex)
        // the departed player's late requests are ignored while the computer holds the seat
        t.nodes[1].transport.send("g1", "h", encode(LudoNetMessage.RollRequest))
        assertEquals(2, t.host.game.state.value!!.currentPlayerIndex)
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.state.value!!.players[1].isBot)
    }

    @Test fun guestsHandleTheHostLeavingAndReturning() {
        val t = table(2)
        val g = t.nodes[1]
        g.transport.fireLeft("h")
        assertTrue(g.game.hostLeft.value)
        assertFalse(g.game.isMyTurn())
        g.transport.fireJoined("h")
        assertFalse(g.game.hostLeft.value)
        val mark = g.transport.mark()
        g.transport.fireReconnected()
        assertTrue(g.transport.mark() > mark)
    }

    @Test fun networkedLudoHasNoUndoNoHintAndNoSnapshot() {
        val t = table(2, ScriptedRandom(6, 6))
        t.host.game.roll(); t.host.game.moveToken(0)
        assertFalse(t.host.game.canUndo)
        t.host.game.undo()
        assertEquals(0, t.host.game.state.value!!.tokens[0])
        t.host.game.roll()
        assertNull(t.host.game.hint())
        assertNull(t.host.game.snapshot())
    }

    @Test fun aFinishedBoardCanBeReplayedAtAGuestsRequest() {
        val t = table(2, ScriptedRandom(3))
        val h = LudoGameTest()
        val players = listOf(PlayerInfo("h", "N0"), PlayerInfo("g1", "N1"))
        val toks = MutableList(8) { -1 }; toks[0] = 56; toks[1] = 56; toks[2] = 56; toks[3] = 53
        assertTrue(t.host.game.restore(h.snapshotJson(toks, players, dice = 3, awaiting = true)))
        t.host.transport.fireLeft("g1"); t.host.transport.fireJoined("g1") // re-sync the guest
        assertEquals(t.host.game.state.value, t.nodes[1].game.state.value)
        assertTrue(t.host.game.moveToken(3))
        assertTrue(t.nodes[1].game.state.value!!.boardOver)
        t.nodes[1].game.playAgain()
        assertFalse(t.host.game.state.value!!.boardOver)
        assertFalse(t.nodes[1].game.state.value!!.boardOver)
        assertEquals(1, t.nodes[1].game.sessionWins.value["h"])
    }

    @Test fun fourSeatsPlayAFullRoundOverTheRelay() {
        val t = table(4, ScriptedRandom(6, 1, 6, 2, 3, 4, 5, 1), PlayMode.ONLINE)
        var steps = 0
        while (steps++ < 40) {
            val s = t.host.game.state.value!!
            val node = t.nodes[s.currentPlayerIndex]
            if (s.awaitingMove) node.game.moveToken(node.game.legalTokens().first()) else node.game.roll()
        }
        t.nodes.forEach { assertEquals(t.host.game.state.value, it.game.state.value) }
        assertTrue(t.hub.senderLies.isEmpty())
    }
}
