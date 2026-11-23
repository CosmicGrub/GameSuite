package com.gamesuite.games.curling

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.carrom.CrHub
import com.gamesuite.games.carrom.CrFakeTransport
import com.gamesuite.games.carrom.carromContext
import com.gamesuite.games.carrom.carromPlayers
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CurlingGameTest {
    private fun newGame(
        seed: Int = 1, level: CpuDifficulty = CpuDifficulty.MEDIUM, humans: Int = 1, bots: Int = 1, ends: Int = 2, stones: Int = 2,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): CurlingGame {
        val g = CurlingGame(Random(seed), clock = { 77L })
        g.difficulty = level
        g.totalEnds = ends
        g.stonesPerTeam = stones
        g.init(carromContext(carromPlayers(humans, bots), mode))
        g.startMatch()
        return g
    }

    private fun draw() = CurlingPhysics.solveDraw(0.0, CurlingSheet.TEE_Y, 1)!!

    @Test fun aNewMatchStartsWithTheOpenerThrowingFirst() {
        val g = newGame()
        val s = g.state.value!!
        assertEquals(1, s.end)
        assertEquals(0, s.teamToThrow)
        assertEquals(1, s.hammer)
        assertTrue(g.isMyTurn())
        val opener = newGame(humans = 1, bots = 1).also { }
        val second = CurlingGame(Random(1)).also { it.firstOpenerIndex = 1; it.init(carromContext(carromPlayers(1, 1))); it.startMatch() }
        assertEquals(1, second.state.value!!.teamToThrow)
        assertNotNull(opener)
    }

    @Test fun aThrowIsRecordedWithItsReplay() {
        val g = newGame()
        val before = g.state.value!!
        assertTrue(g.throwStone(draw()))
        val s = g.state.value!!
        assertEquals(1, s.throwSerial)
        assertEquals(before.stones, s.last!!.before)
        assertEquals(1, s.stones.size)
        assertNotNull(g.replayOf(s))
        assertEquals(77L, s.shotAtMillis)
    }

    @Test fun badAndOutOfTurnThrowsAreRefused() {
        val g = newGame()
        assertFalse(g.throwStone(CurlingThrow(0, 5000, 1)))
        assertEquals(CurlingReject.BAD_THROW, g.explainThrow(CurlingThrow(0, 5000, 1)))
        g.throwStone(draw())
        // now the computer is up, so the person may not throw
        assertEquals(CurlingReject.NOT_YOUR_TURN, g.explainThrow(draw()))
    }

    @Test fun theComputerPlaysLegalDeliveriesAtEveryLevelThroughAWholeMatch() {
        for (level in CpuDifficulty.values()) {
            val g = newGame(seed = 5, level = level, humans = 0, bots = 2, ends = 2, stones = 2)
            var guard = 0
            while (g.state.value!!.phase != CurlingPhase.MATCH_OVER && guard++ < 60) {
                val s = g.state.value!!
                if (s.phase == CurlingPhase.END_SCORED) { g.nextEnd(); continue }
                val t = CurlingBot.choose(s, level, Random(guard))
                assertEquals("$level threw an illegal stone", CurlingReject.NONE, CurlingRules.explain(s, s.seatToThrow, t))
                g.playBotStep()
                assertEquals(s.throwSerial + 1, g.state.value!!.throwSerial)
            }
            assertEquals("$level match did not finish", CurlingPhase.MATCH_OVER, g.state.value!!.phase)
        }
    }

    @Test fun hardPlaysBetterThanMediumThanEasyOnTheSameIce() {
        fun positions(): List<CurlingState> {
            val out = ArrayList<CurlingState>()
            for (seed in 0 until 6) {
                val g = newGame(seed = 40 + seed, level = CpuDifficulty.EASY, humans = 0, bots = 2, ends = 2, stones = 4)
                repeat(2 + seed % 4) { if (g.state.value!!.phase == CurlingPhase.THROWING) g.playBotStep() }
                if (g.state.value!!.phase == CurlingPhase.THROWING) out += g.state.value!!
            }
            return out
        }
        val ps = positions()
        fun avg(level: CpuDifficulty) = ps.mapIndexed { i, s ->
            CurlingBot.value(s, CurlingBot.choose(s, level, Random(i + 1)), s.teamToThrow)
        }.average()
        val easy = avg(CpuDifficulty.EASY)
        val medium = avg(CpuDifficulty.MEDIUM)
        val hard = avg(CpuDifficulty.HARD)
        println("curling levels easy=$easy medium=$medium hard=$hard on ${ps.size} positions")
        assertTrue("hard $hard should beat medium $medium", hard >= medium)
        assertTrue("medium $medium should beat easy $easy", medium >= easy)
        assertTrue("hard $hard should clearly beat easy $easy", hard > easy)
    }

    @Test fun hardTakesOutAnExposedRivalStoneOrDrawsToTheButton() {
        val s = CurlingRules.newMatch(
            listOf(CurlingPlayerState("a", "A", false, 0), CurlingPlayerState("b", "B", true, 1)), 4, 4, 0, 0
        ).copy(stones = listOf(CurlingStone(8, 1, 5.0, CurlingSheet.TEE_Y + 3)), thrown = listOf(0, 1))
        val (t, v) = CurlingBot.search(s, Random(1))!!
        val sim = CurlingPhysics.simulate(s.stones, 0, 0, t)
        assertTrue("expected to beat a rival sitting on the button, value $v", CurlingRules.evaluate(sim.stones, 0) > CurlingRules.evaluate(s.stones, 0))
    }

    @Test fun snapshotRoundTripsAndRejectsGarbage() {
        val g = newGame(seed = 8, stones = 3)
        g.throwStone(draw()); while (g.botToMove()) g.playBotStep()
        val snap = g.snapshot()!!
        val h = CurlingGame(Random(1)).also { it.init(carromContext(carromPlayers(1, 1))) }
        assertTrue(h.restore(snap))
        assertEquals(g.state.value!!.stones, h.state.value!!.stones)
        assertEquals(g.state.value!!.thrown, h.state.value!!.thrown)
        assertEquals(snap, h.snapshot())
        assertFalse(h.restore("{}"))
        assertFalse(h.restore(snap.replace("\"phase\":\"THROWING\"", "\"phase\":\"END_SCORED\"")))
        assertFalse(h.restore(snap.replace("\"hammer\":1", "\"hammer\":5")))
        val other = CurlingGame(Random(1)).also { it.init(carromContext(listOf(PlayerInfo("x", "X"), PlayerInfo("bot9", "CPU", true)))) }
        assertFalse(other.restore(snap))
    }

    @Test fun fuzzSnapshotRoundTripOverWholeMatches() {
        repeat(4) { seed ->
            val g = newGame(seed = 60 + seed, level = CpuDifficulty.EASY, humans = 0, bots = 2, ends = 2, stones = 3, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
            var n = 0
            while (g.state.value!!.phase != CurlingPhase.MATCH_OVER && n++ < 40) {
                if (g.state.value!!.phase == CurlingPhase.END_SCORED) g.nextEnd() else g.playBotStep()
                val snap = g.snapshot() ?: break
                val h = CurlingGame(Random(1)).also { it.init(carromContext(carromPlayers(0, 2), PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)) }
                assertTrue("restore failed at step $n", h.restore(snap))
                assertEquals(g.state.value!!.stones, h.state.value!!.stones)
            }
        }
    }

    @Test fun undoReturnsToBeforeYourThrowOnlyAgainstTheComputer() {
        val g = newGame()
        val before = g.state.value!!
        assertFalse(g.canUndo)
        g.throwStone(draw())
        assertTrue(g.canUndo)
        while (g.botToMove()) g.playBotStep()
        g.undo()
        assertEquals(before.stones, g.state.value!!.stones)
        assertEquals(0, g.state.value!!.throwSerial)
        val pass = newGame(humans = 2, bots = 0, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        pass.throwStone(draw())
        assertFalse(pass.canUndo)
    }

    @Test fun sweepsInAHumanThrowAreReplayedExactly() {
        val g = newGame()
        val swept = CurlingThrow(0, 360, 1, listOf(0, 400))
        g.throwStone(swept)
        val s = g.state.value!!
        val again = CurlingPhysics.simulate(emptyList(), 0, 0, swept)
        assertEquals(again.stones, s.stones)
        assertEquals(swept, s.last!!.throwing)
    }

    // ---- networking ----------------------------------------------------------------------

    private class Node(val game: CurlingGame, val transport: CrFakeTransport)

    private fun table(seats: Int = 2): Pair<CrHub, List<Node>> {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = CrHub()
        val nodes = ids.indices.map { i ->
            val t = hub.endpoint(ids[i])
            val g = CurlingGame(Random(i + 1), clock = { 5L })
            g.totalEnds = 2; g.stonesPerTeam = 2
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, i, t))
            if (i == 0) g.startMatch()
            Node(g, t)
        }
        return hub to nodes
    }

    /** The wire carries no wall-clock stamp, so compare without it. */
    private fun norm(s: CurlingState?) = s?.copy(shotAtMillis = 0L)

    private fun encode(m: CurlingNetMessage) = Json.encodeToString(CurlingNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    @Test fun guestsSeeTheHostsIceAndAGuestThrowGoesThroughTheHost() {
        val (hub, nodes) = table()
        assertEquals(norm(nodes[0].game.state.value), norm(nodes[1].game.state.value))
        assertTrue(nodes[0].game.throwStone(draw()))
        assertEquals(norm(nodes[0].game.state.value), norm(nodes[1].game.state.value))
        assertTrue(nodes[1].game.isMyTurn())
        assertTrue(nodes[1].game.throwStone(CurlingThrow(0, 360, -1, listOf(10, 200))))
        assertEquals(2, nodes[0].game.state.value!!.throwSerial)
        assertEquals(norm(nodes[0].game.state.value), norm(nodes[1].game.state.value))
        assertTrue(hub.senderLies.isEmpty())
        assertNotNull(nodes[1].game.replayOf(nodes[1].game.state.value!!))
    }

    @Test fun forgedOutOfTurnAndInvalidRequestsAreIgnored() {
        val (_, nodes) = table(4)
        val host = nodes[0].game
        val before = host.state.value
        // seat 3 and a stranger ask while seat 0 is up
        nodes[0].transport.inject("g3", encode(CurlingNetMessage.ThrowRequest(draw())))
        nodes[0].transport.inject("stranger", encode(CurlingNetMessage.ThrowRequest(draw())))
        nodes[0].transport.inject("g1", encode(CurlingNetMessage.RequestNextEnd))
        nodes[0].transport.inject("g1", encode(CurlingNetMessage.RequestPlayAgain))
        assertEquals(before, host.state.value)
        // the host's own seat asks for something illegal on a later turn
        host.throwStone(draw())
        val afterOne = host.state.value
        nodes[0].transport.inject("g1", encode(CurlingNetMessage.ThrowRequest(CurlingThrow(0, 9999, 1))))
        nodes[0].transport.inject("g1", encode(CurlingNetMessage.ThrowRequest(CurlingThrow(0, 400, 1, listOf(9, 3)))))
        assertEquals(afterOne, host.state.value)
        // only the host can push state to a guest
        val guestBefore = nodes[1].game.state.value
        nodes[1].transport.inject("g2", encode(CurlingNetMessage.RequestState))
        nodes[1].transport.inject("g2", "junk".toByteArray())
        assertEquals(guestBefore, nodes[1].game.state.value)
    }

    @Test fun aGuestRejectsAnInvalidStateEvenFromTheHost() {
        val (_, nodes) = table()
        val good = nodes[1].game.state.value
        val bad = CurlingNetState(
            players = listOf(CurlingNetPlayer("h", "N0"), CurlingNetPlayer("g1", "N1")),
            stones = listOf(CurlingNetStone(0, 0, 9999.0, 1300.0)), end = 1, totalEnds = 2, stonesPerTeam = 2, thrown = listOf(1, 0), hammer = 1,
            scores = listOf(0, 0), endScores = emptyList(), phase = "THROWING", extraEnds = 0, flags = 0, lastSeat = null, lastTeam = null,
            lastStoneId = null, lastThrow = null, before = emptyList(), throwSerial = 1, endScorer = -1, endPoints = 0, winnerTeam = -1, sessionWins = emptyMap()
        )
        nodes[1].transport.inject("h", encode(CurlingNetMessage.StateSync(50, bad)))
        assertEquals(good, nodes[1].game.state.value)
    }

    @Test fun aLeavingGuestIsReplacedByTheComputerAndNetworkedMatchesAreNotSaved() {
        val (_, nodes) = table()
        nodes[0].transport.leftListeners.forEach { it("g1") }
        assertTrue(nodes[0].game.state.value!!.players[1].isBot)
        assertNull(nodes[0].game.snapshot())
        assertFalse(nodes[0].game.canUndo)
    }
}
