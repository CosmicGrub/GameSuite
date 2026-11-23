package com.gamesuite.games.darts

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DartsGameTest {
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }
    private fun bots(n: Int) = List(n) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }

    fun newGame(
        players: List<PlayerInfo> = humans(1), type: String = DartsGame.G301, doubleOut: Boolean = true, doubleIn: Boolean = false,
        random: Random = Random(1), mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, start: Boolean = true,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, clock: () -> Long = { 1_000L }
    ): DartsGame {
        val g = DartsGame(random, clock)
        g.gameType = type; g.doubleOut = doubleOut; g.doubleIn = doubleIn; g.difficulty = difficulty
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        if (start) g.startMatch()
        return g
    }

    /** Lands a dart exactly on the centre of a ring/number, bypassing the scatter (the rules, not the wobble, are under test). */
    private fun DartsGame.land(ring: Ring, n: Int = 0) {
        val (x, y) = if (ring == Ring.MISS) 300.0 to 300.0 else if (ring == Ring.OUTER_BULL) 0.0 to -11.0 else DartsBoard.center(ring, n)
        assertTrue(applyHit(state.value!!, x, y))
    }

    private fun DartsGame.visit(vararg darts: Pair<Ring, Int>) {
        darts.forEach { if (state.value!!.phase == DartsGame.PHASE_THROW) land(it.first, it.second) }
        if (state.value!!.phase == DartsGame.PHASE_COLLECT) assertTrue(collect())
    }

    private val T20 = Ring.TREBLE to 20
    private val MISS = Ring.MISS to 0

    @Test fun aThreeDartVisitEndsInCollectThenNextPlayer() {
        val g = newGame(humans(2))
        g.land(Ring.TREBLE, 20)
        g.land(Ring.TREBLE, 20)
        assertEquals(DartsGame.PHASE_THROW, g.state.value!!.phase)
        g.land(Ring.TREBLE, 20)
        val s = g.state.value!!
        assertEquals(DartsGame.PHASE_COLLECT, s.phase)
        assertEquals(DartsEvent.ONE_EIGHTY, s.event)
        assertEquals(121, s.players[0].remaining)
        assertEquals(0, s.current)
        assertEquals(DartsReject.COLLECT_FIRST, g.explainThrow())
        assertFalse(g.throwDart(0.0, 0.0, 0.7))
        assertTrue(g.collect())
        val n = g.state.value!!
        assertEquals(1, n.current); assertEquals(DartsGame.PHASE_THROW, n.phase); assertTrue(n.visit.isEmpty())
        assertEquals(301, n.visitStart)
        assertEquals(DartsReject.THROW_FIRST, g.explainCollect())
    }

    @Test fun aSoloMatchCountsDownAndWinsOnADouble() {
        val g = newGame(humans(1))
        g.visit(T20, T20, T20)                       // 301 -> 121
        g.visit(T20, Ring.SINGLE to 19, Ring.SINGLE to 2) // 121 -> 40
        assertEquals(40, g.state.value!!.players[0].remaining)
        g.land(Ring.DOUBLE, 20)
        val s = g.state.value!!
        assertEquals(DartsGame.PHASE_OVER, s.phase)
        assertEquals(DartsEvent.WIN, s.event)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(0, s.players[0].remaining)
        assertEquals(7, s.players[0].darts)
        assertEquals(3, s.players[0].visits)
        assertEquals(180, s.players[0].bestVisit)
        assertEquals(301, s.players[0].totalScored)
        assertEquals(1, g.sessionWins.value["p1"])
        assertNull(g.snapshot())
        assertEquals(DartsReject.OVER, g.explainThrow())
    }

    @Test fun aBustPutsTheScoreBackAndEndsTheVisit() {
        val g = newGame(humans(2))
        g.visit(T20, T20, T20)  // p1 121
        g.visit(MISS, MISS, MISS)
        g.land(Ring.TREBLE, 20)  // 61
        g.land(Ring.TREBLE, 20)  // 1 left with double out is a bust
        val s = g.state.value!!
        assertEquals(DartsEvent.BUST, s.event)
        assertEquals(DartsGame.PHASE_COLLECT, s.phase)
        assertEquals(2, s.visit.size)
        assertTrue(s.visit.last().bust)
        val p1 = s.players[0]
        assertEquals("bust returns to the visit start", 121, p1.remaining)
        assertEquals(2, p1.visits)
        assertEquals(180, p1.bestVisit)
        assertEquals("a bust scores nothing", 180, p1.totalScored)
        assertEquals(DartsReject.COLLECT_FIRST, g.explainThrow())
        assertTrue(g.collect())
        assertEquals(1, g.state.value!!.current)
    }

    @Test fun doubleInKeepsTheScoreAtStartUntilADouble() {
        val g = newGame(humans(1), type = DartsGame.G501, doubleIn = true)
        g.land(Ring.TREBLE, 20)
        assertEquals(501, g.state.value!!.players[0].remaining)
        assertFalse(g.state.value!!.players[0].started)
        g.land(Ring.DOUBLE, 10)
        assertEquals(481, g.state.value!!.players[0].remaining)
        assertTrue(g.state.value!!.players[0].started)
        g.land(Ring.TREBLE, 20)
        assertEquals(421, g.state.value!!.players[0].remaining)
    }

    @Test fun straightOutLetsASingleFinish() {
        val g = newGame(humans(1), type = DartsGame.G301, doubleOut = false)
        g.visit(T20, T20, T20)                                   // 121
        g.visit(T20, T20, Ring.SINGLE to 1)                      // 61, 1, then the single 1 finishes
        assertEquals(DartsGame.PHASE_OVER, g.state.value!!.phase)
        assertEquals("p1", g.state.value!!.winnerPlayerId)
    }

    @Test fun cricketClosesNumbersScoresAndWins() {
        val g = newGame(humans(2), type = DartsGame.CRICKET)
        g.visit(T20, T20, Ring.TREBLE to 19)        // p1: 20 closed, 60 points, 19 closed
        var s = g.state.value!!
        assertEquals(60, s.players[0].points)
        assertEquals(3, s.players[0].marks[0]); assertEquals(3, s.players[0].marks[1])
        g.visit(MISS, MISS, MISS)                   // p2
        g.visit(Ring.TREBLE to 18, Ring.TREBLE to 17, Ring.TREBLE to 16)
        g.visit(MISS, MISS, MISS)
        g.visit(Ring.TREBLE to 15, Ring.OUTER_BULL to 25, Ring.BULL to 25) // 15 closed, bull: 1 + 2 marks -> closed
        s = g.state.value!!
        assertEquals(DartsGame.PHASE_OVER, s.phase)
        assertEquals("p1", s.winnerPlayerId)
        assertTrue(Cricket.allClosed(s.players[0].marks))
        assertEquals(60, s.players[0].points)
        assertEquals(1, g.sessionWins.value["p1"])
    }

    @Test fun cricketDoesNotEndWhileTheLeaderIsBehindOnPoints() {
        val g = newGame(humans(2), type = DartsGame.CRICKET)
        g.visit(MISS, MISS, MISS)                                  // p1
        g.visit(T20, T20, T20)                                     // p2: 20 closed, 120 points
        g.visit(T20, Ring.TREBLE to 19, Ring.TREBLE to 18)         // p1 closes 20, 19, 18 (20 closed by p2 too: no points)
        g.visit(MISS, MISS, MISS)
        g.visit(Ring.TREBLE to 17, Ring.TREBLE to 16, Ring.TREBLE to 15)
        g.visit(MISS, MISS, MISS)
        g.visit(Ring.BULL to 25, Ring.BULL to 25, MISS)            // p1 closes the bull; 0 points vs 120
        val s = g.state.value!!
        assertTrue(Cricket.allClosed(s.players[0].marks))
        assertEquals(25, s.players[0].points)
        assertEquals(120, s.players[1].points)
        assertNull("p1 is closed but behind on points, so the match goes on", s.winnerPlayerId)
        assertNotEquals(DartsGame.PHASE_OVER, s.phase)
    }

    @Test fun throwsAreRefusedOutOfTurnAndWithBadAims() {
        val g = newGame(humans(1))
        assertFalse(g.throwDart(Double.NaN, 0.0, 0.5))
        assertFalse(g.throwDart(0.0, Double.POSITIVE_INFINITY, 0.5))
        assertFalse(g.throwDart(0.0, 0.0, 1.5))
        assertFalse(g.throwDart(0.0, 0.0, -0.1))
        assertFalse(g.throwDart(900.0, 0.0, 0.5))
        assertEquals(0, g.state.value!!.throwSerial)
        assertTrue(g.throwDart(0.0, -103.0, 0.68))
        assertEquals(1, g.state.value!!.throwSerial)
        val vs = newGame(listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true)), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        val first = vs.state.value!!.current
        assertEquals(0, first)
        vs.visit(MISS, MISS, MISS)
        assertEquals(DartsReject.NOT_YOUR_TURN, vs.explainThrow())
        assertFalse(vs.throwDart(0.0, 0.0, 0.5))
    }

    @Test fun theScatterIsASeededStreamSoReplayAndResumeAgree() {
        fun play(seed: Long): DartsState {
            val g = newGame(humans(1), random = Random(seed))
            repeat(2) { g.throwDart(0.0, -103.0, 0.68) }
            return g.state.value!!
        }
        assertEquals(play(11), play(11))
        assertNotEquals(play(11).visit, play(12).visit)
        // resuming a saved match continues the same stream: no re-throwing a dart by quitting
        val a = newGame(humans(1), random = Random(99))
        a.throwDart(10.0, -90.0, 0.7)
        val snap = a.snapshot()!!
        a.throwDart(0.0, -103.0, 0.68)
        val b = newGame(humans(1), random = Random(1234), start = false)
        assertTrue(b.restore(snap))
        b.throwDart(0.0, -103.0, 0.68)
        assertEquals(a.state.value, b.state.value)
    }

    @Test fun snapshotRoundTripsAndRestoreRejectsBadSaves() {
        val g = newGame(humans(2), type = DartsGame.CRICKET, clock = { 5_000L })
        g.throwDart(0.0, -103.0, 0.68)
        val snap = g.snapshot()!!
        val g2 = newGame(humans(2), start = false)
        assertTrue(g2.restore(snap))
        assertEquals(g.state.value, g2.state.value)
        assertEquals(DartsGame.CRICKET, g2.gameType)
        assertEquals(snap, g2.snapshot())
        assertFalse(newGame(humans(3), start = false).restore(snap))               // other roster
        assertFalse(newGame(humans(1), start = false).restore(snap))
        assertFalse(newGame(humans(2), start = false).restore("not json"))
        assertFalse(newGame(humans(2), start = false).restore(snap.replace("\"current\":0", "\"current\":7")))
        assertFalse(newGame(humans(2), start = false).restore(snap.replace("\"phase\":\"throw\"", "\"phase\":\"over\"")))
        val x01 = newGame(humans(2)).also { it.throwDart(0.0, -103.0, 0.68) }.snapshot()!!
        assertFalse(newGame(humans(2), start = false).restore(x01.replace("\"remaining\":301", "\"remaining\":9999")))
        assertFalse(newGame(humans(2), start = false).restore(x01.replace("\"remaining\":301", "\"remaining\":-4")))
        // a failed restore leaves the engine untouched
        val untouched = newGame(humans(2), start = false)
        assertFalse(untouched.restore("{}"))
        assertNull(untouched.state.value)
    }

    @Test fun snapshotsAreOnlyForOfflineModes() {
        val net = newGame(humans(2), mode = PlayMode.LOCAL_AD_HOC)
        assertNull(net.snapshot())
        assertNotNull(newGame(humans(2)).snapshot())
    }

    @Test fun activeTimeFollowsTheInjectedClock() {
        var now = 1_000L
        val g = newGame(humans(1), clock = { now })
        now = 4_500L
        val snap = g.snapshot()!!
        assertTrue(snap.contains("\"activeMs\":3500"))
        now = 10_000L
        val g2 = newGame(humans(1), start = false, clock = { now })
        assertTrue(g2.restore(snap))
        now = 12_000L
        assertTrue(g2.snapshot()!!.contains("\"activeMs\":5500"))
    }

    @Test fun leaveSessionScoresTheSessionWinsAndAbortRecordsNothing() {
        val g = newGame(humans(2))
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.sessionWins.value = mapOf("p1" to 2, "p2" to 1)
        g.leaveSession()
        assertTrue(g.matchOver.value)
        assertEquals(listOf(true, false), result!!.scores.map { it.isWinner })
        val a = newGame(humans(2))
        var aborted: GameResult? = null
        a.setOnMatchEnd { aborted = it }
        a.abortMatch()
        assertTrue(aborted!!.wasAborted)
        assertTrue(aborted!!.scores.isEmpty())
    }

    @Test fun playAgainStartsAFreshMatchAndRotatesTheOpener() {
        val g = newGame(humans(2), type = DartsGame.G301)
        assertEquals(0, g.state.value!!.current)
        g.playAgain()
        assertEquals(1, g.state.value!!.current)
        assertEquals(0, g.state.value!!.throwSerial)
    }

    @Test fun hintsAreOnlyForAPersonOffline() {
        val g = newGame(humans(1), type = DartsGame.G501)
        val h = g.hint()
        assertNotNull(h)
        assertTrue(abs(h!!.aim.x) < 250 && abs(h.aim.y) < 250)
        assertNotNull(g.hintAim.value)
        g.throwDart(0.0, -103.0, 0.68)
        assertNull("the hint is cleared by a throw", g.hintAim.value)
        val net = newGame(humans(2), mode = PlayMode.LOCAL_AD_HOC)
        assertNull(net.hint())
        val vs = newGame(listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true)), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        assertNotNull(vs.hint())
        val async = runBlocking { vs.requestHint() }
        assertNotNull(async)
    }

    @Test fun hintOnAFinishNamesTheCheckoutRoute() {
        val g = newGame(humans(1), type = DartsGame.G301)
        g.visit(T20, T20, T20)
        val h = g.hint()!!
        assertEquals(DartsCheckout.route(121)!!.map { it.code }, h.route)
        assertEquals(3, h.route.size)
        assertEquals(h.route, g.hintRoute.value)
    }

    private fun abs(v: Double) = kotlin.math.abs(v)

    // ---- the computer ------------------------------------------------------------------------

    @Test fun botsAlwaysThrowLegalDartsAndFinishTheMatch() {
        for (level in CpuDifficulty.values()) {
            val g = newGame(bots(2), type = DartsGame.G301, doubleOut = false, difficulty = level, random = Random(level.ordinal + 3L))
            var steps = 0
            while (g.state.value!!.phase != DartsGame.PHASE_OVER && steps++ < 3000) g.playBotStep()
            assertEquals("$level should finish a 301 straight-out", DartsGame.PHASE_OVER, g.state.value!!.phase)
            assertTrue(DartsGame.validState(g.state.value!!, listOf("bot1", "bot2"), listOf(true, true)))
        }
    }

    @Test fun botAimsAreInsideTheLegalRange() {
        val g = newGame(bots(2), type = DartsGame.CRICKET)
        val s = g.state.value!!
        for (level in CpuDifficulty.values()) {
            val a = DartsBot.aim(s, 0, level, Random(1))
            assertTrue(a.x.isFinite() && a.y.isFinite())
            assertTrue(abs(a.x) <= DartsBoard.MAX_AIM && abs(a.y) <= DartsBoard.MAX_AIM)
            assertTrue(a.power in 0.0..1.0)
        }
    }

    private fun averagePerVisit(level: CpuDifficulty, games: Int): Double {
        var scored = 0L; var darts = 0L
        for (k in 0 until games) {
            val g = newGame(bots(2), type = DartsGame.G301, doubleOut = false, difficulty = level, random = Random(100L + k))
            var steps = 0
            while (g.state.value!!.phase != DartsGame.PHASE_OVER && steps++ < 4000) g.playBotStep()
            g.state.value!!.players.forEach { scored += it.totalScored; darts += it.darts }
        }
        return scored * 3.0 / darts
    }

    @Test fun computerLevelsAreOrderedEasyMediumHard() {
        val easy = averagePerVisit(CpuDifficulty.EASY, 8)
        val medium = averagePerVisit(CpuDifficulty.MEDIUM, 8)
        val hard = averagePerVisit(CpuDifficulty.HARD, 8)
        assertTrue("easy $easy < medium $medium", easy < medium)
        assertTrue("medium $medium < hard $hard", medium < hard)
        assertTrue("hard should be a strong thrower: $hard", hard > 45.0)
        assertTrue("easy should be beatable: $easy", easy < 40.0)
    }

    @Test fun botTurnAsyncPlaysOffTheCallerThread() = runBlocking {
        val g = newGame(listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true)), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        g.visit(MISS, MISS, MISS)
        assertTrue(g.botToMove())
        g.playBotTurnAsync()
        assertEquals(1, g.state.value!!.visit.size)
        repeat(3) { g.playBotTurnAsync() }
        assertEquals(0, g.state.value!!.current)
    }

    // ---- fuzz --------------------------------------------------------------------------------

    @Test fun fuzzRandomLegalPlayKeepsEveryInvariant() {
        var finished = 0
        for (seed in 1..40) {
            val rnd = Random(seed)
            val n = rnd.nextInt(1, 5)
            val players = List(n) { i -> if (rnd.nextInt(3) == 0) PlayerInfo("b$i", "B$i", isBot = true) else PlayerInfo("h$i", "H$i") }
            val type = listOf(DartsGame.G301, DartsGame.G501, DartsGame.CRICKET)[rnd.nextInt(3)]
            val g = newGame(players, type = type, doubleOut = rnd.nextBoolean(), doubleIn = rnd.nextBoolean(),
                random = Random(seed * 31L), difficulty = CpuDifficulty.values()[rnd.nextInt(3)],
                mode = if (n > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT)
            val ids = players.map { it.playerId }
            val isBot = players.map { it.isBot }
            var steps = 0
            while (g.state.value!!.phase != DartsGame.PHASE_OVER && steps++ < 1500) {
                val s = g.state.value!!
                if (g.botToMove(s)) g.playBotStep()
                else if (s.phase == DartsGame.PHASE_COLLECT) assertTrue(g.collect())
                else assertTrue(g.throwDart(rnd.nextDouble(-220.0, 220.0), rnd.nextDouble(-220.0, 220.0), rnd.nextDouble()))
                val t = g.state.value!!
                assertTrue("invalid state at seed $seed step $steps: $t", DartsGame.validState(t, ids, isBot))
                for (p in t.players) {
                    assertTrue(p.remaining in 0..DartsGame.startScore(t.game))
                    assertTrue(p.marks.all { it in 0..3 })
                }
                if (steps % 17 == 0) {
                    val snap = g.snapshot()
                    if (snap != null) {
                        val twin = newGame(players, start = false, mode = if (n > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT)
                        assertTrue(twin.restore(snap))
                        assertEquals(t, twin.state.value)
                    }
                }
            }
            if (g.state.value!!.phase == DartsGame.PHASE_OVER) {
                finished++
                val s = g.state.value!!
                val w = s.players.first { it.playerId == s.winnerPlayerId }
                if (s.game == DartsGame.CRICKET) assertTrue(Cricket.allClosed(w.marks)) else assertEquals(0, w.remaining)
                assertEquals(1, g.sessionWins.value.values.sum())
            }
        }
        assertTrue("a fair share of the random matches should end ($finished of 40)", finished >= 3)
    }
}
