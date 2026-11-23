package com.gamesuite.games.bowling

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

class BowlingGameTest {
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }
    private fun bots(n: Int) = List(n) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
    private val all = BowlingLane.ALL_PINS
    private val shot = BowlShot(0.0, 0.0, 7.5, 0.0)

    fun newGame(
        players: List<PlayerInfo> = humans(1), random: Random = Random(1), start: Boolean = true,
        mode: PlayMode = if (players.size > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, bumpers: Boolean = false, clock: () -> Long = { 1_000L }
    ): BowlingGame {
        val g = BowlingGame(random, clock)
        g.difficulty = difficulty; g.bumpers = bumpers
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        if (start) g.startMatch()
        return g
    }

    /** Scores a ball that knocked down [pins] of the pins standing (the lowest-numbered standing ones). */
    private fun BowlingGame.ball(pins: Int, gutter: Boolean = false) {
        val s = state.value!!
        var after = s.standing
        var left = pins
        for (i in 0 until 10) if (left > 0 && after and (1 shl i) != 0) { after = after and (1 shl i).inv(); left-- }
        assertTrue(applyOutcome(s, shot, after, gutter))
    }

    @Test fun aPerfectGameIs300() {
        val g = newGame()
        repeat(12) {
            assertEquals(all, g.state.value!!.standing)
            g.ball(10)
        }
        val s = g.state.value!!
        assertEquals(BowlingGame.PHASE_OVER, s.phase)
        assertEquals(BowlingEvent.FINISH, s.event)
        assertEquals(300, s.total(0))
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(1, g.sessionWins.value["p1"])
        assertNull(g.snapshot())
        assertEquals(BowlingReject.OVER, g.explainRoll())
        assertNull(s.position)
    }

    @Test fun twentyGuttersScoreZero() {
        val g = newGame()
        repeat(20) { g.ball(0, gutter = true) }
        assertEquals(BowlingGame.PHASE_OVER, g.state.value!!.phase)
        assertEquals(0, g.state.value!!.total(0))
        assertEquals(BowlingEvent.FINISH, g.state.value!!.event)
    }

    @Test fun aSpareLeavesPinsForTheSecondBallThenResetsTheRack() {
        val g = newGame()
        g.ball(7)
        var s = g.state.value!!
        assertEquals(3, BowlingLane.pinCount(s.standing))
        assertEquals(BowlingEvent.ROLL, s.event)
        assertEquals(0 to 1, s.position)
        g.ball(3)
        s = g.state.value!!
        assertEquals(BowlingEvent.SPARE, s.event)
        assertEquals(all, s.standing)
        assertEquals(1 to 0, s.position)
        g.ball(5)
        assertEquals(10 + 5 + 5, g.state.value!!.total(0))
    }

    @Test fun eventsForStrikeMissAndGutter() {
        val g = newGame()
        g.ball(10)
        assertEquals(BowlingEvent.STRIKE, g.state.value!!.event)
        assertEquals(10, BowlingLane.pinCount(g.state.value!!.before))
        assertEquals(all, g.state.value!!.knocked)
        g.ball(0, gutter = true)
        assertEquals(BowlingEvent.GUTTER, g.state.value!!.event)
        assertTrue(g.state.value!!.gutter)
        g.ball(0)
        assertEquals(BowlingEvent.MISS, g.state.value!!.event)
        assertFalse(g.state.value!!.gutter)
    }

    @Test fun playersAlternateByFrame() {
        val g = newGame(humans(2))
        assertEquals(0, g.state.value!!.current)
        g.ball(4)
        assertEquals(0, g.state.value!!.current)
        g.ball(3)
        assertEquals(1, g.state.value!!.current)
        assertEquals(all, g.state.value!!.standing)
        g.ball(10)
        assertEquals(0, g.state.value!!.current)
        assertEquals(1, g.state.value!!.roller)
        assertEquals(listOf(7, 10), g.state.value!!.players.map { BowlingRules.total(it.rolls) })
    }

    @Test fun theTenthFrameStrikeThenSpareFlow() {
        val g = newGame()
        repeat(9) { g.ball(10) }
        g.ball(10)                                // tenth, first ball: strike, fresh rack
        var s = g.state.value!!
        assertEquals(all, s.standing)
        assertEquals(9 to 1, s.position)
        g.ball(7)                                 // 3 left
        s = g.state.value!!
        assertEquals(3, BowlingLane.pinCount(s.standing))
        assertEquals(9 to 2, s.position)
        g.ball(3)                                 // spare with the last ball
        s = g.state.value!!
        assertEquals(BowlingEvent.FINISH, s.event)
        assertEquals(BowlingGame.PHASE_OVER, s.phase)
        // nine strikes = 8 full strike frames... 9 x 10 strikes then 10, 7, 3
        assertEquals(BowlingRules.total(s.players[0].rolls), s.total(0))
        assertEquals(30 * 7 + 30 + 27 + 20, s.total(0))
    }

    @Test fun theTenthFrameDoubleStrikeResetsTheRackTwice() {
        val g = newGame()
        repeat(9) { g.ball(10) }
        g.ball(10); g.ball(10)
        assertEquals(all, g.state.value!!.standing)
        g.ball(9)
        assertEquals(BowlingGame.PHASE_OVER, g.state.value!!.phase)
        assertEquals(299, g.state.value!!.total(0))
    }

    @Test fun anOpenTenthFrameEndsAfterTwoBalls() {
        val g = newGame()
        repeat(9) { g.ball(0) ; g.ball(0) }
        g.ball(4); g.ball(3)
        assertEquals(BowlingGame.PHASE_OVER, g.state.value!!.phase)
        assertEquals(7, g.state.value!!.total(0))
    }

    @Test fun twoPlayerGameFinishesWithAWinnerOrATie() {
        val g = newGame(humans(2))
        repeat(10) { g.ball(10); g.ball(0); g.ball(0) } // p1 strike, p2 gutter-gutter ... until tenth
        // keep scoring until the game is over
        var guard = 0
        while (g.state.value!!.phase != BowlingGame.PHASE_OVER && guard++ < 100) g.ball(0)
        val s = g.state.value!!
        assertEquals(BowlingGame.PHASE_OVER, s.phase)
        assertTrue(s.total(0) > s.total(1))
        assertEquals("p1", s.winnerPlayerId)
        assertFalse(s.tie)
        val t = newGame(humans(2))
        repeat(40) { if (t.state.value!!.phase != BowlingGame.PHASE_OVER) t.ball(0) }
        assertEquals(BowlingGame.PHASE_OVER, t.state.value!!.phase)
        assertTrue(t.state.value!!.tie)
        assertNull(t.state.value!!.winnerPlayerId)
        assertEquals(0, t.sessionWins.value.values.sum())
    }

    @Test fun aRealRollAdvancesTheSerialAndKnocksDownOnlyStandingPins() {
        val g = newGame()
        val before = g.state.value!!
        assertTrue(g.roll(BowlShot(0.0, 0.0, 7.5, 0.0)))
        val s = g.state.value!!
        assertEquals(1, s.rollSerial)
        assertEquals(before.standing, s.before)
        assertEquals(0, s.knocked and s.before.inv())
        assertNotNull(s.lastShot)
        assertTrue(BowlingRules.total(s.players[0].rolls) in 0..10)
        assertEquals(BowlingLane.pinCount(s.knocked), s.players[0].rolls.first())
    }

    @Test fun badShotsAndOutOfTurnRollsAreRefused() {
        val g = newGame()
        assertFalse(g.roll(BowlShot(Double.NaN, 0.0, 7.0, 0.0)))
        assertFalse(g.roll(BowlShot(0.0, 0.0, 50.0, 0.0)))
        assertEquals(BowlingReject.BAD_SHOT, g.explainRoll(BowlShot(0.0, 0.0, 50.0, 0.0)))
        assertEquals(0, g.state.value!!.rollSerial)
        val vs = newGame(listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true)), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        vs.ball(5); vs.ball(2)
        assertEquals(BowlingReject.NOT_YOUR_TURN, vs.explainRoll())
        assertFalse(vs.roll(shot))
    }

    @Test fun theScatterIsASeededStreamSoReplayAndResumeAgree() {
        fun play(seed: Long): BowlingState {
            val g = newGame(random = Random(seed))
            g.roll(BowlShot(0.1, -0.003, 7.5, 0.4))
            return g.state.value!!
        }
        assertEquals(play(7), play(7))
        val a = newGame(random = Random(55))
        a.roll(BowlShot(0.1, -0.003, 7.5, 0.4))
        val snap = a.snapshot()!!
        a.roll(BowlShot(0.0, 0.0, 7.5, 0.0))
        val b = newGame(random = Random(999), start = false)
        assertTrue(b.restore(snap))
        b.roll(BowlShot(0.0, 0.0, 7.5, 0.0))
        assertEquals(a.state.value, b.state.value)
    }

    @Test fun snapshotRoundTripsAndRestoreRejectsBadSaves() {
        val g = newGame(humans(2), bumpers = true)
        g.ball(4)
        val snap = g.snapshot()!!
        val g2 = newGame(humans(2), start = false)
        assertTrue(g2.restore(snap))
        assertEquals(g.state.value, g2.state.value)
        assertTrue(g2.bumpers)
        assertEquals(snap, g2.snapshot())
        assertFalse(newGame(humans(3), start = false).restore(snap))
        assertFalse(newGame(humans(2), start = false).restore("nope"))
        assertFalse(newGame(humans(2), start = false).restore(snap.replace("\"rolls\":[4]", "\"rolls\":[14]")))
        assertFalse(newGame(humans(2), start = false).restore(snap.replace("\"rolls\":[4]", "\"rolls\":[4,9]")))
        assertFalse(newGame(humans(2), start = false).restore(snap.replace("\"current\":0", "\"current\":5")))
        assertFalse(newGame(humans(2), start = false).restore(snap.replace("\"standing\":${g.state.value!!.standing}", "\"standing\":1023")))
        val untouched = newGame(humans(2), start = false)
        assertFalse(untouched.restore("{}"))
        assertNull(untouched.state.value)
    }

    @Test fun networkedMatchesNeverSnapshot() {
        assertNull(newGame(humans(2), mode = PlayMode.ONLINE).snapshot())
        assertNotNull(newGame(humans(2)).snapshot())
    }

    @Test fun activeTimeFollowsTheInjectedClock() {
        var now = 1_000L
        val g = newGame(clock = { now })
        now = 3_000L
        assertTrue(g.snapshot()!!.contains("\"activeMs\":2000"))
    }

    @Test fun leaveSessionAndAbort() {
        val g = newGame(humans(2))
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.sessionWins.value = mapOf("p1" to 1, "p2" to 0)
        g.leaveSession()
        assertEquals(listOf(true, false), result!!.scores.map { it.isWinner })
        val a = newGame(humans(2))
        var aborted: GameResult? = null
        a.setOnMatchEnd { aborted = it }
        a.abortMatch()
        assertTrue(aborted!!.wasAborted)
    }

    @Test fun playAgainRotatesTheOpener() {
        val g = newGame(humans(2))
        assertEquals(0, g.state.value!!.current)
        g.playAgain()
        assertEquals(1, g.state.value!!.current)
        assertEquals(0, g.state.value!!.rollSerial)
    }

    @Test fun hintIsAShotThatClearsTheRack() = runBlocking {
        val g = newGame()
        val h = g.requestHint()
        assertNotNull(h)
        assertTrue(h!!.isValid())
        assertEquals(h, g.hintShot.value)
        g.roll(h)
        assertNull(g.hintShot.value)
        val net = newGame(humans(2), mode = PlayMode.LOCAL_AD_HOC)
        assertNull(net.requestHint())
    }

    // ---- the computer ------------------------------------------------------------------------

    @Test fun botsBowlCompleteLegalGames() {
        for (level in CpuDifficulty.values()) {
            val g = newGame(bots(2), difficulty = level, random = Random(level.ordinal + 20L))
            var steps = 0
            while (g.state.value!!.phase != BowlingGame.PHASE_OVER && steps++ < 100) {
                g.playBotStep()
                assertTrue(BowlingGame.validState(g.state.value!!, listOf("bot1", "bot2"), listOf(true, true)))
            }
            assertEquals("$level", BowlingGame.PHASE_OVER, g.state.value!!.phase)
            assertTrue(g.state.value!!.players.all { BowlingRules.isComplete(it.rolls) })
        }
    }

    private fun averageScore(level: CpuDifficulty, games: Int): Double {
        var total = 0
        var count = 0
        for (k in 0 until games) {
            val g = newGame(bots(2), difficulty = level, random = Random(500L + k), bumpers = false)
            var steps = 0
            while (g.state.value!!.phase != BowlingGame.PHASE_OVER && steps++ < 100) g.playBotStep()
            g.state.value!!.players.forEach { total += BowlingRules.total(it.rolls); count++ }
        }
        return total.toDouble() / count
    }

    @Test fun computerLevelsAreOrderedEasyMediumHard() {
        val easy = averageScore(CpuDifficulty.EASY, 6)
        val medium = averageScore(CpuDifficulty.MEDIUM, 6)
        val hard = averageScore(CpuDifficulty.HARD, 6)
        val all3 = "easy $easy medium $medium hard $hard"
        assertTrue("easy < medium: $all3", easy < medium)
        assertTrue("medium < hard: $all3", medium < hard)
        assertTrue("hard should be a strong bowler: $all3", hard > 150.0)
        assertTrue("easy should be beatable: $all3", easy < 135.0)
    }

    @Test fun botTurnAsyncRollsOneBall() = runBlocking {
        val g = newGame(listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true)), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        g.ball(5); g.ball(2)
        assertTrue(g.botToMove())
        g.playBotTurnAsync()
        assertEquals(3, g.state.value!!.rollSerial)
        assertEquals(1, g.state.value!!.players[1].rolls.size)
    }

    // ---- fuzz --------------------------------------------------------------------------------

    @Test fun fuzzRandomLegalPlayKeepsEveryInvariantAndAlwaysTerminates() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val n = rnd.nextInt(1, 5)
            val players = List(n) { i -> if (rnd.nextInt(3) == 0) PlayerInfo("b$i", "B$i", isBot = true) else PlayerInfo("h$i", "H$i") }
            val g = newGame(players, random = Random(seed * 17L), difficulty = CpuDifficulty.values()[rnd.nextInt(3)], bumpers = rnd.nextInt(4) == 0)
            val ids = players.map { it.playerId }
            val bots = players.map { it.isBot }
            val mode = if (n > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT
            var steps = 0
            while (g.state.value!!.phase != BowlingGame.PHASE_OVER) {
                assertTrue("seed $seed did not terminate", steps++ < 21 * n + 5)
                val s = g.state.value!!
                if (g.botToMove(s)) g.playBotStep()
                else assertTrue(g.roll(BowlShot(rnd.nextDouble(-0.5, 0.5), rnd.nextDouble(-0.06, 0.06), rnd.nextDouble(5.0, 9.5), rnd.nextDouble(-1.0, 1.0))))
                val t = g.state.value!!
                assertTrue("invalid state at seed $seed step $steps", BowlingGame.validState(t, ids, bots))
                assertEquals(s.rollSerial + 1, t.rollSerial)
                assertTrue(t.players.all { it.rolls.size <= 21 })
                assertEquals(0, t.knocked and t.before.inv())
                if (t.phase != BowlingGame.PHASE_OVER && steps % 5 == 0) {
                    val twin = newGame(players, start = false, mode = mode)
                    assertTrue(twin.restore(g.snapshot()!!))
                    assertEquals(t, twin.state.value)
                }
            }
            val s = g.state.value!!
            assertTrue(s.players.all { BowlingRules.isComplete(it.rolls) && BowlingRules.total(it.rolls) in 0..300 })
            assertEquals(if (s.tie) 0 else 1, g.sessionWins.value.values.sum())
        }
    }
}
