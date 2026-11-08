package com.gamesuite.games.pigdice

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.chinesecheckers.ClFakeTransport
import com.gamesuite.games.chinesecheckers.ClHub
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Pig: the host owns the dice and the engine. */
class PigNetworkedTest {
    private class Node(val game: PigGame, val transport: ClFakeTransport)
    private class Table(val hub: ClHub, val nodes: List<Node>) { val host get() = nodes[0] }

    private fun encode(m: PigNetMessage) = Json.encodeToString(PigNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(
        seats: Int, die: Random = PigScriptedRandom(3, 4, 5, 6, 2), mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        variant: PigVariant = PigVariant.CLASSIC, target: Int = 100
    ): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = ClHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = PigGame(if (i == 0) die else Random(1000 + i)) // a guest's own dice must never be used
            g.variant = variant; g.target = target
            g.init(GameContext(mode, players, localPlayerIndex = i, transport = t))
            nodes += Node(g, t)
            if (i == 0) g.startMatch()
        }
        return Table(hub, nodes)
    }

    @Test fun guestsReceiveTheOpeningTableFromTheHost() {
        val t = table(4)
        t.nodes.forEach { assertEquals(t.host.game.state.value, it.game.state.value) }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun onlyTheHostEverRollsAndAGuestOnlyAsks() {
        val die = PigScriptedRandom(3, 4, 6)
        val t = table(2, die)
        t.host.game.roll()       // 3
        t.host.game.hold()       // seat 1 now
        val g1 = t.nodes[1].game
        assertTrue(g1.isMyTurn())
        val calls = die.calls
        assertTrue(g1.roll())    // a request: the host rolls with its own die
        assertEquals(calls + 1, die.calls)
        assertEquals(t.host.game.state.value, g1.state.value)
        assertEquals(listOf(4), g1.state.value!!.dice)
        assertTrue(g1.hold())
        assertEquals(listOf(3, 4), t.host.game.state.value!!.scores)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun forgedAndOutOfTurnRequestsAreIgnored() {
        val t = table(3)
        val before = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(PigNetMessage.RollRequest(1)))
        t.nodes[2].transport.send("g2", "h", encode(PigNetMessage.HoldRequest))
        assertTrue(before === t.host.game.state.value)
        // claiming to be the host changes nothing: identity is the endpoint
        t.nodes[1].transport.send("h", "h", encode(PigNetMessage.RollRequest(1)))
        assertTrue(before === t.host.game.state.value)
        t.nodes[1].transport.send("g1", "h", "xx".toByteArray())
        assertTrue(before === t.host.game.state.value)
        t.nodes[2].transport.send("g2", "h", encode(PigNetMessage.RequestPlayAgain))
        assertTrue(before === t.host.game.state.value)
    }

    @Test fun aGuestCannotAskForTheWrongNumberOfDice() {
        val t = table(2, mode = PlayMode.ONLINE)
        t.host.game.roll(); t.host.game.hold()
        val s = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(PigNetMessage.RollRequest(5)))   // classic has one die
        t.nodes[1].transport.send("g1", "h", encode(PigNetMessage.RollRequest(0)))
        t.nodes[1].transport.send("g1", "h", encode(PigNetMessage.RollRequest(-3)))
        assertTrue(s === t.host.game.state.value)
        assertEquals(1, t.host.game.state.value!!.rollSerial)
    }

    @Test fun hogGuestAsksForItsDiceAndTheHostRollsThemAll() {
        val die = PigScriptedRandom(2, 6, 3, 4)
        val t = table(2, die, variant = PigVariant.HOG)
        t.host.game.roll(2)      // 2 + 6 = 8 banked
        val g1 = t.nodes[1].game
        assertTrue(g1.roll(2))   // 3 + 4
        assertEquals(listOf(8, 7), t.host.game.state.value!!.scores)
        assertEquals(listOf(3, 4), g1.state.value!!.dice)
        t.nodes[1].transport.send("g1", "h", encode(PigNetMessage.RollRequest(9)))   // more than the table allows
        assertEquals(4, die.calls)
    }

    @Test fun guestsRejectForgedStaleAndMalformedSyncs() {
        val t = table(2)
        val g = t.nodes[1]
        val real = g.game.state.value
        val good = PigNetState(
            "classic", 100, listOf(PigNetPlayer("h", "a"), PigNetPlayer("g1", "b")), listOf(0, 0), 0, 0, 0, emptyList(),
            "start", 0, 0, 0, 0, false, null, mapOf("h" to 0, "g1" to 0)
        )
        fun sync(v: Int, s: PigNetState, from: String = "h") = g.transport.inject(from, encode(PigNetMessage.StateSync(v, s)))
        sync(500, good.copy(scores = listOf(500, 0)))                        // beyond the goal, nobody marked winner
        sync(501, good.copy(scores = listOf(-5, 0)))
        sync(502, good.copy(players = listOf(PigNetPlayer("x", "a"), PigNetPlayer("y", "b"))))   // not this roster
        sync(503, good.copy(dice = listOf(7)))
        sync(504, good.copy(variant = "weird"))
        sync(505, good.copy(target = 5))
        sync(506, good.copy(currentPlayerIndex = 9))
        sync(507, good.copy(over = true, winnerPlayerId = null))
        sync(508, good.copy(event = "boom"))
        sync(509, good.copy(sessionWins = mapOf("stranger" to 3)))
        sync(510, good.copy(turnTotal = 120))
        sync(511, good.copy(scores = listOf(10, 0)), from = "g1")            // not from the host
        g.transport.inject("h", "###".toByteArray())
        assertTrue(real === g.game.state.value)
        t.host.game.roll()
        val fresh = g.game.state.value
        sync(0, good)                                                          // stale version
        assertTrue(fresh === g.game.state.value)
        sync(10_000, good.copy(scores = listOf(30, 20)))                       // a legal state is accepted
        assertEquals(listOf(30, 20), g.game.state.value!!.scores)
    }

    @Test fun aDepartedSeatIsPlayedByTheComputerAndReturnedOnReconnect() {
        val t = table(3, PigScriptedRandom(3, 3, 3, 3))
        t.host.transport.fireLeft("g1")
        assertTrue(t.host.game.state.value!!.players[1].isBot)
        assertTrue(t.nodes[2].game.state.value!!.players[1].isBot)
        t.host.game.roll(); t.host.game.hold()             // pass to seat 1 (now a computer)
        assertTrue(t.host.game.botToMove())
        assertFalse(t.nodes[2].game.botToMove())          // only the host drives the computer
        runBlocking { t.host.game.playBotTurnAsync() }
        assertEquals(2, t.host.game.state.value!!.rollSerial)          // the computer rolled once for seat 1
        assertEquals(t.host.game.state.value, t.nodes[2].game.state.value)
        // the player comes back: the seat is theirs again
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.state.value!!.players[1].isBot)
    }
}
