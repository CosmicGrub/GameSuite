package com.gamesuite.games.sixball

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.chinesecheckers.ClFakeTransport
import com.gamesuite.games.chinesecheckers.ClHub
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Nearby / Online race: everyone solves the same tray at once; the host replays and orders the results. */
class SixBallNetworkedTest {
    private class Node(val game: SixBallGame, val transport: ClFakeTransport)
    private class Table(val hub: ClHub, val nodes: List<Node>, val clock: LongArray) { val host get() = nodes[0] }

    private fun encode(m: SixBallNetMessage) = Json.encodeToString(SixBallNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(seats: Int, level: String = "c2", mode: PlayMode = PlayMode.LOCAL_AD_HOC): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = ClHub()
        val clock = longArrayOf(1000L)
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = SixBallGame { clock[0] }
            g.init(GameContext(mode, players, localPlayerIndex = i, transport = t))
            nodes += Node(g, t)
            if (i == 0) { g.levelId = level; g.startMatch() }
        }
        return Table(hub, nodes, clock)
    }

    /** What the wire carries of a race (the host's own clock readings stay with the host). */
    private fun view(r: SixBallRace?) = r?.let { x -> Triple(x.racers.map { Triple(it.playerId, it.status, it.moves) }, x.winnerIds, x.raceOver) }

    private fun solve(g: SixBallGame) {
        val b = g.board.value!!
        for (m in SixBallRules.solve(b.level, b.positions.toIntArray())!!) assertTrue(g.tilt(m))
    }

    @Test fun everyoneRacesTheSameTrayFromTheHostsFirstSync() {
        val t = table(3)
        t.nodes.forEach {
            assertEquals("c2", it.game.board.value!!.level.id)
            assertEquals(listOf("playing", "playing", "playing"), it.game.race.value!!.racers.map { r -> r.status })
        }
        assertEquals(t.host.game.race.value, t.nodes[2].game.race.value)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun theHostReplaysAGuestsTiltsAndOrdersTheFinishByTiltsThenByItsOwnClock() {
        val t = table(3)
        t.clock[0] = 5000L
        solve(t.nodes[2].game)            // g2 finishes first, at par
        assertEquals("solved", t.host.game.race.value!!.racers[2].status)
        t.clock[0] = 6000L
        solve(t.nodes[1].game)            // g1 also at par, later
        assertFalse(t.host.game.race.value!!.raceOver)
        t.clock[0] = 7000L
        solve(t.host.game)
        val r = t.host.game.race.value!!
        assertTrue(r.raceOver)
        assertEquals(listOf("g2"), r.winnerIds)                     // same tilts: the earlier arrival wins
        assertEquals(listOf("g2", "g1", "h"), SixBallRaceRules.ranking(r.racers).map { it.playerId })
        t.nodes.forEach { assertEquals(view(r), view(it.game.race.value)) }       // everyone sees the host's order
    }

    @Test fun fewerTiltsBeatAnEarlierFinish() {
        val t = table(2, level = "c4")
        val g1 = t.nodes[1].game
        // g1 takes a detour (a tilt off the shortest path) and finishes first; the host solves at par later
        val level = g1.board.value!!.level
        val best = SixBallRules.solve(level)!!
        val detour = Tilt.values().firstOrNull { d ->
            d != best.first() && g1.explainTilt(d) == SixBallReject.NONE &&
                SixBallRules.solve(level, SixBallRules.tilt(level, level.start.toIntArray(), d)) != null
        } ?: return
        t.clock[0] = 10L; g1.tilt(detour); solve(g1)
        t.clock[0] = 99L; solve(t.host.game)
        val r = t.host.game.race.value!!
        assertTrue(r.raceOver)
        assertEquals(level.par, r.racers[0].moves)
        assertTrue(r.racers[1].moves >= level.par)
        if (r.racers[1].moves > level.par) assertEquals(listOf("h"), r.winnerIds)
    }

    @Test fun forgedSolutionsAreRefusedByTheHost() {
        val t = table(2)
        val level = t.host.game.board.value!!.level
        val best = SixBallRules.solve(level)!!.map { it.ordinal }
        val before = t.host.game.race.value
        fun send(moves: List<Int>) = t.nodes[1].transport.send("g1", "h", encode(SixBallNetMessage.Finish(moves)))
        send(emptyList())                                   // "I finished in no tilts"
        send(best.dropLast(1))                              // one tilt short of solving
        send(listOf(0, 0, 0, 0))                            // moves that do not solve (or repeat)
        send(listOf(9, 1, 2))                               // not tilt codes
        send(best + best.first())                           // a tilt after the tray was solved
        send(List(1001) { it % 4 })                         // longer than any honest play
        assertTrue(before === t.host.game.race.value)
        t.nodes[1].transport.send("g1", "h", "garbage".toByteArray())
        t.nodes[1].transport.send("g1", "h", ByteArray(70_000) { 'x'.code.toByte() })
        assertTrue(before === t.host.game.race.value)
        // an honest list is accepted, once
        send(best)
        assertEquals("solved", t.host.game.race.value!!.racers[1].status)
        val done = t.host.game.race.value
        send(best)
        assertTrue(done === t.host.game.race.value)
    }

    @Test fun aGuestCannotFinishOrGiveUpForAnotherSeat() {
        val t = table(3)
        val best = SixBallRules.solve(t.host.game.board.value!!.level)!!.map { it.ordinal }
        // g1 claims to be the host and then g2: the endpoint decides who is credited
        t.nodes[1].transport.send("h", "h", encode(SixBallNetMessage.Finish(best)))
        t.nodes[1].transport.send("g2", "h", encode(SixBallNetMessage.GiveUp))
        val r = t.host.game.race.value!!
        assertEquals("playing", r.racers[0].status)
        assertEquals("solved", r.racers[1].status)       // credited to g1 (the real sender) only
        assertEquals("playing", r.racers[2].status)
        assertTrue(t.hub.senderLies.size >= 2)
    }

    @Test fun givingUpAndLeavingEndTheRaceWhenNobodyIsLeftPlaying() {
        val t = table(3)
        t.nodes[1].game.giveUp()
        assertEquals("gave_up", t.host.game.race.value!!.racers[1].status)
        t.host.transport.fireLeft("g2")
        assertEquals("left", t.host.game.race.value!!.racers[2].status)
        assertFalse(t.host.game.race.value!!.raceOver)
        solve(t.host.game)
        val r = t.host.game.race.value!!
        assertTrue(r.raceOver); assertEquals(listOf("h"), r.winnerIds)
        assertEquals(view(r), view(t.nodes[1].game.race.value))
    }

    @Test fun guestsRejectForgedStaleAndImpossibleSyncs() {
        val t = table(2)
        val g = t.nodes[1]
        val real = g.game.race.value
        val level = g.game.board.value!!.level
        val good = SixBallNetRace("c2", 1, listOf(SixBallNetRacer("h", "playing", 0), SixBallNetRacer("g1", "playing", 0)), false, emptyList())
        fun sync(v: Int, r: SixBallNetRace, from: String = "h") = g.transport.inject(from, encode(SixBallNetMessage.StateSync(v, r)))
        sync(500, good.copy(racers = good.racers.map { it.copy(playerId = "x") }))                 // not this roster
        sync(501, good.copy(racers = listOf(SixBallNetRacer("h", "solved", level.par - 1), good.racers[1])))   // beats the optimum
        sync(502, good.copy(racers = listOf(SixBallNetRacer("h", "playing", 7), good.racers[1])))  // moves while playing
        sync(503, good.copy(racers = listOf(SixBallNetRacer("h", "waiting", 0), good.racers[1])))  // not a networked status
        sync(504, good.copy(racers = listOf(SixBallNetRacer("h", "solved", level.par), good.racers[1]), raceOver = true, winnerIds = listOf("h")))   // over, but g1 still plays
        sync(505, good.copy(winnerIds = listOf("h")))                                              // winner before the end
        sync(506, good.copy(levelId = "c9999"))
        sync(507, good.copy(round = 0))
        sync(508, good.copy(racers = listOf(good.racers[0])))
        sync(509, good, from = "g1")                                                               // not from the host
        g.transport.inject("h", "###".toByteArray())
        assertTrue(real === g.game.race.value)
        t.host.game.giveUp()
        val fresh = g.game.race.value
        sync(0, good)                                                                              // stale
        assertTrue(fresh === g.game.race.value)
    }

    @Test fun theHostLeavingIsNoticedAndPlayAgainStartsTheNextLevelForEveryone() {
        val t = table(2, level = "c5")
        t.nodes[1].game.giveUp(); t.host.game.giveUp()
        assertTrue(t.host.game.race.value!!.raceOver)
        // a guest may ask, only once the race is over
        t.nodes[1].game.playAgain()
        assertEquals("c6", t.host.game.board.value!!.level.id)
        assertEquals("c6", t.nodes[1].game.board.value!!.level.id)
        assertEquals(2, t.nodes[1].game.race.value!!.round)
        assertTrue(t.nodes[1].game.board.value!!.moves.isEmpty())
        t.nodes[1].transport.send("g1", "h", encode(SixBallNetMessage.RequestPlayAgain))      // race still on: ignored
        assertEquals(2, t.host.game.race.value!!.round)
        t.nodes[1].transport.fireLeft("h")
        assertTrue(t.nodes[1].game.hostLeft.value)
        assertFalse(t.nodes[1].game.canPlay())
        t.nodes[1].transport.fireJoined("h")
        assertFalse(t.nodes[1].game.hostLeft.value)
    }

    @Test fun networkedPlayNeverResumesAndOnlineBehavesLikeNearby() {
        val t = table(2, mode = PlayMode.ONLINE)
        assertEquals(null, t.host.game.snapshot())
        solve(t.nodes[1].game)
        assertEquals("solved", t.host.game.race.value!!.racers[1].status)
    }
}
