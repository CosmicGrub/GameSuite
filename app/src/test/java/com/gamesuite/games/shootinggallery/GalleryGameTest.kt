package com.gamesuite.games.shootinggallery

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.darts.TbFakeTransport
import com.gamesuite.games.darts.TbHub
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GalleryGameTest {
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }

    private fun newGame(
        players: List<PlayerInfo> = humans(2), seed: Long = 1, format: String = GalleryGame.FORMAT_TURNS, rounds: Int = 2,
        relaxed: Boolean = false, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
        first: Int = 0
    ): GalleryGame {
        val g = GalleryGame(Random(seed), { 1_000L })
        g.format = format; g.rounds = rounds; g.relaxed = relaxed; g.difficulty = difficulty; g.firstOpenerIndex = first
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    private val GalleryGame.s get() = state.value!!

    /** A log that scores: one centre shot at every duck, spaced so the magazine never runs dry. */
    private fun goodLog(c: RangeConfig): List<GalleryShot> {
        val run = RangeRun(c)
        val out = ArrayList<GalleryShot>()
        for (tg in run.targets.filter { it.kind == TargetKind.DUCK || it.kind == TargetKind.RING }) {
            val t = (tg.spawnMs + tg.exitMs) / 2
            if (out.isNotEmpty() && t < out.last().t) continue
            val x = tg.xAt(t).toInt(); val y = tg.yAt(t).toInt()
            val o = run.shoot(t, x, y)
            if (o.kind == ShotKind.HIT || o.kind == ShotKind.MISS) out += GalleryShot(t, x, y)
        }
        return out
    }

    @Test fun everyPlayerGetsTheSameRangeEachRound() {
        val g = newGame(humans(3))
        val c0 = g.s.config
        assertEquals(GalleryRange.targets(c0), GalleryRange.targets(RangeConfig(g.s.seed, 0, false)))
        assertEquals(c0, g.rangeFor(g.s))
    }

    @Test fun turnsPassInOrderAndRoundsAdvance() {
        val g = newGame(humans(3), rounds = 2)
        assertEquals(0, g.s.current)
        assertEquals(GalleryReject.NONE, g.explainSubmit())
        assertTrue(g.submitTurn(0, goodLog(g.s.config)))
        assertEquals(1, g.s.current)
        assertTrue(g.s.players[0].total > 0)
        assertFalse(g.submitTurn(0, emptyList())) // not seat 0's turn any more
        assertTrue(g.submitTurn(1, emptyList()))
        assertTrue(g.submitTurn(2, emptyList()))
        assertEquals(1, g.s.round)
        assertEquals(0, g.s.current)
        assertEquals(GalleryEvent.ROUND_END, g.s.event)
        assertTrue(g.s.players.none { it.done })
        assertEquals(1, g.s.players[0].roundScores.size)
        // the second round has a different range
        assertTrue(GalleryRange.targets(g.s.config) != GalleryRange.targets(RangeConfig(g.s.seed, 0, false)))
    }

    @Test fun theHighestTotalWinsAfterTheLastRoundAndTheSessionCountsIt() {
        val g = newGame(humans(2), rounds = 1)
        g.submitTurn(0, emptyList())
        g.submitTurn(1, goodLog(g.s.config))
        val s = g.s
        assertEquals(GalleryGame.PHASE_OVER, s.phase)
        assertEquals("p2", s.winnerPlayerId)
        assertEquals(GalleryEvent.WIN, s.event)
        assertEquals(1, g.sessionWins.value["p2"])
        assertFalse(g.submitTurn(0, emptyList()))
        assertEquals(GalleryReject.OVER, g.explainSubmit())
        assertNull(g.snapshot())
    }

    @Test fun tiesGoToMoreHitsThenAreADraw() {
        val a = GalleryPlayer("a", "A", false, total = 100, hits = 5)
        val b = GalleryPlayer("b", "B", false, total = 100, hits = 7)
        assertEquals("b", GalleryGame.winnerOf(listOf(a, b)))
        assertNull(GalleryGame.winnerOf(listOf(a, b.copy(hits = 5))))
        assertEquals("a", GalleryGame.winnerOf(listOf(a.copy(total = 101), b)))
        val g = newGame(humans(2), rounds = 1)
        g.submitTurn(0, emptyList()); g.submitTurn(1, emptyList())
        assertNull(g.s.winnerPlayerId)
        assertEquals(GalleryEvent.DRAW, g.s.event)
        assertTrue(GalleryGame.validState(g.s, listOf("p1", "p2"), null))
    }

    @Test fun theHostComputesTheScoreNotTheSubmitter() {
        val g = newGame(humans(2))
        val log = goodLog(g.s.config)
        g.submitTurn(0, log)
        assertEquals(GalleryRange.play(g.s.config.copy(round = 0), log)!!.score, g.s.players[0].total)
        assertEquals(log, g.s.lastTurn!!.shots)
    }

    @Test fun aRaceTakesBothLogsInAnyOrderThenAdvances() {
        val g = newGame(humans(2), format = GalleryGame.FORMAT_RACE, rounds = 2)
        assertEquals(GalleryGame.FORMAT_RACE, g.s.format)
        assertEquals(listOf(0, 1), g.raceSeatsDue(g.s))
        assertTrue(g.submitTurn(1, goodLog(g.s.config)))
        assertEquals(listOf(0), g.raceSeatsDue(g.s))
        assertFalse(g.submitTurn(1, emptyList()))
        assertTrue(g.submitTurn(0, emptyList()))
        assertEquals(1, g.s.round)
        assertEquals(listOf(0, 1), g.raceSeatsDue(g.s))
    }

    @Test fun aRaceNeedsExactlyTwoSeatsOtherwiseItFallsBackToTurns() {
        assertEquals(GalleryGame.FORMAT_TURNS, newGame(humans(3), format = GalleryGame.FORMAT_RACE).s.format)
        assertEquals(GalleryGame.FORMAT_TURNS, newGame(humans(1), format = GalleryGame.FORMAT_RACE).s.format)
    }

    @Test fun theFirstShooterOptionIsHonouredInPassAndPlay() {
        val g = newGame(humans(3), first = 2)
        assertEquals(2, g.s.current)
        g.submitTurn(2, emptyList())
        assertEquals(0, g.s.current)
        g.submitTurn(0, emptyList()); g.submitTurn(1, emptyList())
        assertEquals(1, g.s.round)
        assertEquals(2, g.s.current)
    }

    @Test fun computerSeatsPlayTheirOwnTurnWithADeterministicLog() {
        val players = listOf(PlayerInfo("me", "You"), PlayerInfo("cpu", "CPU", isBot = true))
        val g = newGame(players, mode = PlayMode.SINGLE_PLAYER_VS_BOT, rounds = 1)
        assertFalse(g.botToMove())
        g.submitTurn(0, goodLog(g.s.config))
        assertTrue(g.botToMove())
        val expected = g.botLogFor(g.s, 1)
        g.playBotStep()
        assertEquals(expected, g.s.lastTurn!!.shots)
        assertEquals(GalleryGame.PHASE_OVER, g.s.phase)
        assertTrue(g.s.players[1].total > 0)
    }

    @Test fun snapshotRoundTripsAndRefusesTamperedOrMismatchedData() {
        val g = newGame(humans(3), seed = 9, rounds = 3)
        g.submitTurn(0, goodLog(g.s.config)); g.submitTurn(1, emptyList())
        val snap = g.snapshot()!!
        val h = GalleryGame(Random(0), { 1_000L })
        h.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans(3), 0, LocalPassAndPlayTransport()))
        assertTrue(h.restore(snap))
        assertEquals(g.s, h.s)
        val k = GalleryGame(Random(0), { 1_000L })
        k.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans(2), 0, LocalPassAndPlayTransport()))
        assertFalse(k.restore(snap))
        assertNull(k.state.value)
        assertFalse(h.restore("{}"))
        // a doctored score no longer matches its own tap log
        val total = g.s.players[0].total
        assertFalse(h.restore(snap.replace("\"total\":$total", "\"total\":${total + 500}")))
        assertFalse(h.restore(snap.replace("\"result\":{\"score\":0", "\"result\":{\"score\":500")))
    }

    @Test fun fuzzRandomLogsKeepEveryInvariant() {
        for (seed in 1L..30L) {
            val rnd = Random(seed)
            val n = 1 + rnd.nextInt(4)
            val g = newGame(humans(n), seed, rounds = 1 + rnd.nextInt(3), relaxed = rnd.nextBoolean())
            var turns = 0
            while (g.s.phase == GalleryGame.PHASE_PLAY && turns < 40) {
                val s = g.s
                assertTrue(GalleryGame.validState(s, s.players.map { it.playerId }, null))
                val c = s.config
                var t = 0
                val log = ArrayList<GalleryShot>()
                repeat(rnd.nextInt(0, 60)) {
                    t = (t + rnd.nextInt(0, 900)).coerceAtMost(c.durationMs)
                    log += if (rnd.nextInt(10) == 0) GalleryShot(t, GalleryWorld.RELOAD_X, 0) else GalleryShot(t, rnd.nextInt(0, 1001), rnd.nextInt(0, 601))
                }
                assertTrue(g.submitTurn(s.current, log))
                g.snapshot()?.let { snap ->
                    val h = GalleryGame(Random(1), { 1_000L })
                    h.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans(n), 0, LocalPassAndPlayTransport()))
                    assertTrue(h.restore(snap))
                    assertEquals(g.s, h.s)
                }
                turns++
            }
            assertEquals(GalleryGame.PHASE_OVER, g.s.phase)
            val total = g.s.players.sumOf { it.total }
            assertEquals(total, g.s.players.sumOf { it.roundScores.sum() })
        }
    }

    // ---- networking ----------------------------------------------------------------------

    private class Node(val game: GalleryGame, val transport: TbFakeTransport)
    private class Table(val hub: TbHub, val nodes: List<Node>) { val host get() = nodes[0] }

    private fun encode(m: GalleryNetMessage) = Json.encodeToString(GalleryNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(seats: Int, hostSeed: Long = 5L): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = TbHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = GalleryGame(if (i == 0) Random(hostSeed) else Random(9000 + i))
            g.rounds = 2
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, i, t))
            nodes += Node(g, t)
            if (i == 0) g.startMatch()
        }
        return Table(hub, nodes)
    }

    @Test fun guestsReceiveTheSameSeededRangeFromTheHost() {
        val t = table(3)
        t.nodes.forEach { assertEquals(t.host.game.state.value, it.game.state.value) }
        val c = t.nodes[2].game.state.value!!.config
        assertEquals(GalleryRange.targets(t.host.game.state.value!!.config), GalleryRange.targets(c))
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestSendsOnlyItsTapLogAndTheHostScoresIt() {
        val t = table(2)
        t.host.game.submitTurn(0, emptyList())
        val g1 = t.nodes[1].game
        assertTrue(g1.isMyTurn())
        val log = goodLog(g1.state.value!!.config)
        assertTrue(g1.submitTurn(1, log))
        val s = t.host.game.state.value!!
        assertEquals(GalleryRange.play(s.config.copy(round = 0), log)!!.score, s.players[1].total)
        assertEquals(s, g1.state.value)
        assertEquals(1, s.round)
    }

    @Test fun aGuestCannotSubmitOutOfTurnOrForgeOtherSeatsOrBadLogs() {
        val t = table(3)
        val before = t.host.game.state.value
        val g1 = t.nodes[1].game
        assertFalse(g1.submitTurn(1, emptyList())) // seat 0 is on turn
        t.nodes[1].transport.send("g1", "h", encode(GalleryNetMessage.ShotsRequest(emptyList())))
        t.nodes[2].transport.send("h", "h", encode(GalleryNetMessage.ShotsRequest(emptyList())))
        assertTrue(before === t.host.game.state.value)
        t.host.game.submitTurn(0, emptyList())
        val s = t.host.game.state.value
        // seat 1 is up: a bad log (unsorted, out of range) is refused, and seat 2 cannot jump the queue
        t.nodes[1].transport.send("g1", "h", encode(GalleryNetMessage.ShotsRequest(listOf(GalleryShot(900, 5, 5), GalleryShot(100, 5, 5)))))
        t.nodes[1].transport.send("g1", "h", encode(GalleryNetMessage.ShotsRequest(listOf(GalleryShot(10, 5000, 5)))))
        t.nodes[2].transport.send("g2", "h", encode(GalleryNetMessage.ShotsRequest(emptyList())))
        t.nodes[1].transport.send("g1", "h", "garbage".toByteArray())
        assertTrue(s === t.host.game.state.value)
    }

    @Test fun guestsRejectForgedStaleAndImpossibleSyncs() {
        val t = table(2)
        val g = t.nodes[1]
        val real = g.game.state.value!!
        g.transport.inject("g1", encode(GalleryNetMessage.StateSync(50, real.copy(round = 1), emptyMap())))
        assertTrue(real === g.game.state.value)
        val bad = real.copy(players = real.players.mapIndexed { i, p -> if (i == 0) p.copy(total = 99999) else p })
        g.transport.inject("h", encode(GalleryNetMessage.StateSync(51, bad, emptyMap())))
        assertTrue(real === g.game.state.value)
        g.transport.inject("h", encode(GalleryNetMessage.StateSync(52, real.copy(players = real.players.map { it.copy(playerId = "evil") }), emptyMap())))
        assertTrue(real === g.game.state.value)
        // a sync whose last turn claims more points than its tap log earns
        t.host.game.submitTurn(0, goodLog(real.config))
        val genuine = t.host.game.state.value!!
        val lie = genuine.copy(lastTurn = genuine.lastTurn!!.copy(result = genuine.lastTurn!!.result.copy(score = 12345)))
        val after = g.game.state.value
        g.transport.inject("h", encode(GalleryNetMessage.StateSync(60, lie, emptyMap())))
        assertTrue(after === g.game.state.value)
        assertEquals(genuine, after)
        g.transport.inject("h", encode(GalleryNetMessage.StateSync(1, real, emptyMap())))
        assertTrue(after === g.game.state.value)
    }

    @Test fun reconnectLeaveAndNeverSnapshot() {
        val t = table(3)
        assertNull(t.host.game.snapshot())
        t.host.game.submitTurn(0, emptyList())
        t.nodes[1].transport.reconnectedListeners.forEach { it() }
        assertEquals(t.host.game.state.value, t.nodes[1].game.state.value)
        t.host.transport.fireLeft("g1")
        assertTrue(t.host.game.state.value!!.players[1].isBot)
        assertTrue(t.host.game.botToMove())
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.state.value!!.players[1].isBot)
        t.nodes[2].transport.fireLeft("h")
        assertTrue(t.nodes[2].game.hostLeft.value)
        assertFalse(t.nodes[2].game.isMyTurn())
        assertNotNull(t.nodes[2].game.state.value)
    }
}
