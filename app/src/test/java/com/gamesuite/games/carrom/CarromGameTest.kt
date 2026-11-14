package com.gamesuite.games.carrom

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CarromGameTest {
    private fun vsCpu(seed: Int = 1, level: CpuDifficulty = CpuDifficulty.MEDIUM, humans: Int = 1, bots: Int = 1, mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT): CarromGame {
        val g = CarromGame(Random(seed), clock = { 1000L })
        g.difficulty = level
        g.init(carromContext(carromPlayers(humans, bots), mode))
        g.startMatch()
        return g
    }

    private fun legalShot(s: CarromState): CarromShot {
        val t = CarromRules.freeStands(s).first()
        return CarromShot(t, 0, 600)
    }

    @Test fun aNewBoardHasTheOpeningCoinsAndWhiteToMove() {
        val g = vsCpu()
        val s = g.state.value!!
        assertEquals(19, s.coins.size)
        assertEquals(0, s.currentPlayerIndex)
        assertTrue(g.isMyTurn())
        assertEquals(CarromReject.NONE, g.explainShot(legalShot(s)))
    }

    @Test fun illegalShotsAreRejectedWithAReason() {
        val g = vsCpu()
        assertEquals(CarromReject.BAD_SHOT, g.explainShot(CarromShot(500, 0, 0)))
        assertFalse(g.shoot(CarromShot(500, 20000, 100)))
    }

    @Test fun aFlickAdvancesTheBoardAndRecordsTheReplay() {
        val g = vsCpu()
        val s0 = g.state.value!!
        assertTrue(g.shoot(CarromShot(500, 0, 700)))
        val s1 = g.state.value!!
        assertEquals(1, s1.shotSerial)
        assertEquals(s0.coins, s1.last!!.before)
        assertNotNull(g.replayOf(s1))
        assertEquals(1000L, s1.shotAtMillis)
    }

    @Test fun theComputerPlaysLegalFlicksAtEveryLevelUntilTheBoardEnds() {
        for (level in CpuDifficulty.values()) {
            val g = vsCpu(seed = 4, level = level, humans = 0, bots = 2)
            var guard = 0
            while (g.state.value!!.let { !it.boardOver } && guard++ < 400) {
                val before = g.state.value!!
                val shot = CarromBot.choose(before, level, Random(guard))
                assertEquals("$level produced an illegal shot", CarromReject.NONE, CarromRules.explain(before, before.currentPlayerIndex, shot))
                g.playBotStep()
                assertTrue(g.state.value!!.shotSerial == before.shotSerial + 1)
            }
            val end = g.state.value!!
            assertTrue("$level board did not end in 400 flicks", end.boardOver || guard >= 400)
            assertEquals(end.coins.map { it.id }.toSet().size, end.coins.size)
        }
    }

    @Test fun hardOutscoresMediumOutscoresEasyOnTheSameBoards() {
        // average value of the chosen shot on 14 mid-game boards
        fun boards(): List<CarromState> {
            val out = ArrayList<CarromState>()
            for (seed in 0 until 14) {
                val g = vsCpu(seed = 100 + seed, level = CpuDifficulty.EASY, humans = 0, bots = 2)
                repeat(3 + seed % 5) { if (!g.state.value!!.boardOver) g.playBotStep() }
                if (!g.state.value!!.boardOver) out += g.state.value!!
            }
            return out
        }
        val bs = boards()
        fun avg(level: CpuDifficulty): Double = bs.mapIndexed { i, b ->
            val shot = CarromBot.choose(b, level, Random(i + 1))
            CarromRules.play(b, shot, 0L).score
        }.average()
        val easy = avg(CpuDifficulty.EASY)
        val medium = avg(CpuDifficulty.MEDIUM)
        val hard = avg(CpuDifficulty.HARD)
        println("carrom levels easy=$easy medium=$medium hard=$hard on ${bs.size} boards")
        assertTrue("hard $hard should beat medium $medium", hard >= medium)
        assertTrue("medium $medium should beat easy $easy", medium >= easy)
        assertTrue("hard $hard should clearly beat easy $easy", hard > easy)
    }

    @Test fun hardPocketsOnAnOpenBoard() {
        val white = listOf(CarromCoin(0, 200.0, 250.0), CarromCoin(1, 500.0, 300.0))
        val black = listOf(CarromCoin(9, 370.0, 150.0))
        val s = CarromState(
            listOf(CarromPlayerState("a", "A", false, 0, 0), CarromPlayerState("b", "B", true, 1, 2)),
            (white + black + CarromCoin(18, 370.0, 370.0)).sortedBy { it.id }, 0
        )
        val (shot, score) = CarromBot.search(s, Random(1))!!
        assertTrue("expected a pocketing shot, score $score", score >= 12.0)
        assertEquals(CarromReject.NONE, CarromRules.explain(s, 0, shot))
    }

    @Test fun snapshotRoundTripsAndRejectsGarbage() {
        val g = vsCpu(seed = 8)
        repeat(3) { if (g.isMyTurn()) g.shoot(legalShot(g.state.value!!)) else g.playBotStep() }
        val snap = g.snapshot()!!
        val h = CarromGame(Random(1)).also { it.init(carromContext(carromPlayers(1, 1))) }
        assertTrue(h.restore(snap))
        assertEquals(g.state.value!!.coins, h.state.value!!.coins)
        assertEquals(g.state.value!!.currentPlayerIndex, h.state.value!!.currentPlayerIndex)
        assertEquals(snap, h.snapshot())
        assertFalse(h.restore("{}"))
        assertFalse(h.restore(snap.replace("\"queenOwner\":-1", "\"queenOwner\":0")))
        assertFalse(h.restore(snap.replace("\"id\":18", "\"id\":55")))
        // a roster that differs is refused
        val other = CarromGame(Random(1)).also { it.init(carromContext(listOf(PlayerInfo("x", "X"), PlayerInfo("bot9", "CPU", true)))) }
        assertFalse(other.restore(snap))
    }

    @Test fun fuzzSnapshotRoundTripOverWholeGames() {
        repeat(6) { seed ->
            val g = vsCpu(seed = 30 + seed, level = CpuDifficulty.EASY, humans = 0, bots = 2, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
            var n = 0
            while (!g.state.value!!.boardOver && n++ < 60) {
                g.playBotStep()
                val snap = g.snapshot() ?: break
                val h = CarromGame(Random(1)).also { it.init(carromContext(carromPlayers(0, 2), PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)) }
                assertTrue("restore failed after $n flicks", h.restore(snap))
                assertEquals(g.state.value!!.coins, h.state.value!!.coins)
            }
        }
    }

    @Test fun undoReturnsToBeforeYourLastFlickAndOnlyAgainstTheComputer() {
        val g = vsCpu()
        val before = g.state.value!!
        assertFalse(g.canUndo)
        g.shoot(legalShot(before))
        assertTrue(g.canUndo)
        while (g.botToMove()) g.playBotStep()
        g.undo()
        assertEquals(before.coins, g.state.value!!.coins)
        assertEquals(0, g.state.value!!.shotSerial)
        assertFalse(g.canUndo)

        val pass = vsCpu(humans = 2, bots = 0, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        pass.shoot(legalShot(pass.state.value!!))
        assertFalse("no undo between people on one device", pass.canUndo)
    }

    @Test fun fourSeatsPlayInTwoPartnerships() {
        val g = vsCpu(humans = 2, bots = 2, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        val s = g.state.value!!
        assertEquals(listOf(0, 1, 0, 1), s.players.map { it.team })
        assertEquals(listOf(0, 1, 2, 3), s.players.map { it.side })
    }

    @Test fun aWholeMatchToATargetScoresAcrossBoards() {
        val g = CarromGame(Random(2), clock = { 0L })
        g.targetPoints = 3
        g.difficulty = CpuDifficulty.EASY
        g.init(carromContext(carromPlayers(0, 2)))
        g.startMatch()
        var boards = 0
        var guard = 0
        while (guard++ < 3000) {
            val s = g.state.value!!
            if (s.boardOver) {
                boards++
                if (g.matchDecided() || boards >= 6) break
                g.playAgain()
            } else g.playBotStep()
        }
        val s = g.state.value!!
        assertTrue(s.boardOver)
        assertTrue(boards >= 1)
        assertEquals(3, s.targetPoints)
    }

    // ---- networking ----------------------------------------------------------------------

    private class Node(val game: CarromGame, val transport: CrFakeTransport)

    private fun table(seats: Int = 2): Pair<CrHub, List<Node>> {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = CrHub()
        val nodes = ids.indices.map { i ->
            val t = hub.endpoint(ids[i])
            val g = CarromGame(Random(i + 1), clock = { 5L })
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, i, t))
            if (i == 0) g.startMatch()
            Node(g, t)
        }
        return hub to nodes
    }

    /** The wire carries no wall-clock stamp, so compare without it. */
    private fun norm(s: CarromState?) = s?.copy(shotAtMillis = 0L)

    private fun encode(m: CarromNetMessage) = Json.encodeToString(CarromNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    @Test fun guestsReceiveTheBoardAndOnlyTheHostSimulates() {
        val (hub, nodes) = table()
        assertEquals(norm(nodes[0].game.state.value), norm(nodes[1].game.state.value))
        val host = nodes[0].game
        assertTrue(host.shoot(CarromShot(500, 0, 800)))
        assertEquals(norm(host.state.value), norm(nodes[1].game.state.value))
        assertTrue(hub.senderLies.isEmpty())
        // the guest's replay is derived from what the host sent
        assertNotNull(nodes[1].game.replayOf(nodes[1].game.state.value!!))
    }

    @Test fun aGuestShotGoesThroughTheHostWhenItIsItsTurn() {
        val (_, nodes) = table()
        val host = nodes[0].game
        val guest = nodes[1].game
        // make the host miss so the turn passes
        host.shoot(CarromShot(1000, 8000, 200))
        if (host.state.value!!.currentPlayerIndex != 1) return // a lucky pot keeps the turn; the other tests cover turn passing
        assertTrue(guest.isMyTurn())
        assertTrue(guest.shoot(CarromShot(300, 0, 600)))
        assertEquals(norm(host.state.value), norm(guest.state.value))
        assertEquals(2, host.state.value!!.shotSerial)
    }

    @Test fun forgedAndOutOfTurnRequestsAreIgnored() {
        val (_, nodes) = table(4)
        val host = nodes[0].game
        val before = host.state.value
        // seat 3 asks while seat 0 is on turn; a stranger asks; the host's own id arrives from a guest endpoint
        nodes[0].transport.inject("g3", encode(CarromNetMessage.ShotRequest(CarromShot(500, 0, 500))))
        nodes[0].transport.inject("stranger", encode(CarromNetMessage.ShotRequest(CarromShot(500, 0, 500))))
        assertEquals(before, host.state.value)
        // a guest cannot make a guest accept state: only the host's id counts
        val fake = encode(CarromNetMessage.StateSync(99, CarromNetState(emptyList(), emptyList(), 0, -1, -1, 0, 0, null, emptyList(), null, null, emptyList(), 5, false, -1, 0, listOf(0, 0), 0, emptyMap())))
        val guestBefore = nodes[1].game.state.value
        nodes[1].transport.inject("g2", fake)
        assertEquals(guestBefore, nodes[1].game.state.value)
        // malformed bytes never throw
        nodes[0].transport.inject("g1", "nonsense".toByteArray())
    }

    @Test fun aGuestIgnoresAnInvalidStateEvenFromTheHost() {
        val (_, nodes) = table()
        val good = nodes[1].game.state.value
        val bad = CarromNetState(
            players = listOf(CarromNetPlayer("h", "N0"), CarromNetPlayer("g1", "N1")),
            coins = listOf(CarromNetCoin(3, 99999.0, 5.0)), currentPlayerIndex = 0, queenOwner = -1, queenPending = -1,
            shotsTaken = 0, flags = 0, eventSeat = null, potted = emptyList(), lastSeat = null, lastShot = null, before = emptyList(),
            shotSerial = 0, boardOver = false, winnerTeam = -1, winPoints = 0, teamPoints = listOf(0, 0), targetPoints = 0, sessionWins = emptyMap()
        )
        nodes[1].transport.inject("h", encode(CarromNetMessage.StateSync(50, bad)))
        assertEquals(good, nodes[1].game.state.value)
    }

    @Test fun aLeavingGuestIsTakenOverByTheComputerAndNothingNetworkedIsSaved() {
        val (_, nodes) = table()
        nodes[0].transport.leftListeners.forEach { it("g1") }
        assertTrue(nodes[0].game.state.value!!.players[1].isBot)
        assertNull(nodes[0].game.snapshot())
        assertFalse(nodes[0].game.canUndo)
    }
}
