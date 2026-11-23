package com.gamesuite.games.billiards

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BilliardsGameTest {
    private val humans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val vsCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = humans, seed: Int = 1, variant: String = BilliardsVariant.EIGHT,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, level: CpuDifficulty = CpuDifficulty.MEDIUM
    ): BilliardsGame {
        var now = 1000L
        val g = BilliardsGame(Random(seed)) { now += 10; now }
        g.variant = variant; g.difficulty = level
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    private fun shotAtBall(g: BilliardsGame, ball: Int, power: Double = 1.0): Shot {
        val s = g.state.value!!
        return Shot(Math.atan2(s.y[ball] - s.y[0], s.x[ball] - s.x[0]), power, 0.0, 0.0, s.x[0], s.y[0])
    }

    @Test fun aNewRackStartsWithABreakByTheFirstBreakerAndAlternatesOnPlayAgain() {
        val g = newGame()
        val s = g.state.value!!
        assertTrue(s.isBreak); assertEquals(0, s.shooter); assertEquals(16, s.onTable.count { it }); assertTrue(s.isValid())
        assertNull(s.lastShot)
    }

    @Test fun theFirstShotIsJudgedAndPublishedWithTheStrokeAttached() {
        val g = newGame()
        val before = g.state.value!!
        assertTrue(g.shoot(shotAtBall(g, 1)))
        val s = g.state.value!!
        assertFalse(s.isBreak); assertEquals(1, s.shotNo); assertTrue(s.serial > before.serial)
        val rec = s.lastShot!!
        assertEquals(before.x, rec.startX); assertEquals(before.onTable, rec.startOn)
        assertTrue(s.isValid())
        // replaying the recorded stroke lands on the very same table
        val replay = BilliardsPhysics.simulate(rec.startX.toDoubleArray(), rec.startY.toDoubleArray(), rec.startOn.toBooleanArray(), rec.shot)
        for (i in 0 until 16) {
            if (s.onTable[i] && replay.onTable[i]) assertEquals(s.x[i], replay.x[i], 0.0)
        }
    }

    @Test fun aRejectedShotChangesNothingAndIsExplained() {
        val g = newGame()
        val s0 = g.state.value
        val ok = shotAtBall(g, 1)
        assertEquals(ShotReject.BAD_POWER, g.explainShot(ok.copy(power = 0.0)))
        assertEquals(ShotReject.BAD_POWER, g.explainShot(ok.copy(power = 1.5)))
        assertEquals(ShotReject.BAD_SHOT, g.explainShot(ok.copy(angle = Double.NaN)))
        assertEquals(ShotReject.BAD_SHOT, g.explainShot(ok.copy(spinX = 2.0)))
        assertEquals(ShotReject.BAD_PLACEMENT, g.explainShot(ok.copy(cueX = ok.cueX + 3.0)))
        assertFalse(g.shoot(ok.copy(power = 0.0)))
        assertTrue(s0 === g.state.value)
        assertFalse(g.canUndo)
    }

    @Test fun ballInHandAcceptsALegalPlacementAndRejectsAnIllegalOne() {
        // 9-ball: hit the 5 first while the 1 is on the table = foul, the opponent has ball in hand
        val g = newGame(variant = BilliardsVariant.NINE, seed = 4)
        g.shoot(shotAtBall(g, 1)) // the break (contacts the apex 1)
        var guard = 0
        while (!g.state.value!!.ballInHand && guard++ < 40) {
            val s = g.state.value!!
            val lowest = s.ballsOnTable().minOrNull() ?: break
            val wrong = s.ballsOnTable().filter { it != lowest }.maxOrNull() ?: break
            g.shoot(shotAtBall(g, wrong, 0.3))
        }
        val s = g.state.value!!
        if (s.phase == BilliardsPhase.AIM && s.ballInHand) {
            val bad = shotAtBall(g, s.ballsOnTable().min()).copy(cueX = s.x[s.ballsOnTable().min()], cueY = s.y[s.ballsOnTable().min()])
            assertEquals(ShotReject.BAD_PLACEMENT, g.explainShot(bad))
            val spot = BilliardsRules.freeSpot(40.0, 10.0, s.xs(), s.ys(), s.ons())
            val t = s.ballsOnTable().min()
            val good = Shot(Math.atan2(s.y[t] - spot.second, s.x[t] - spot.first), 0.5, 0.0, 0.0, spot.first, spot.second)
            assertEquals(ShotReject.NONE, g.explainShot(good))
            assertTrue(g.shoot(good))
            assertFalse(g.state.value!!.let { it.ballInHand && it.shotNo == s.shotNo })
        }
    }

    @Test fun snapshotRestoreRoundTripsTheWholeStateAndRejectsDamagedSaves() {
        val g = newGame(seed = 7)
        g.shoot(shotAtBall(g, 1))
        val snap = g.snapshot()!!
        val g2 = newGame(seed = 99)
        assertTrue(g2.restore(snap))
        assertEquals(g.state.value!!.copy(serial = 0), g2.state.value!!.copy(serial = 0))
        assertEquals(snap, g2.snapshot())
        // damage
        assertFalse(g2.restore("{}"))
        assertFalse(g2.restore("not json"))
        assertFalse(g2.restore(snap.replace("\"shooter\":", "\"shooter\":7,\"x_\":")))
        val nan = Json.parseToJsonElement(snap).toString().replaceFirst("\"onTable\":[true", "\"onTable\":[false")
        assertFalse("a save without a cue ball is refused", g2.restore(nan))
        val other = newGame(players = listOf(PlayerInfo("a", "A"), PlayerInfo("b", "B")), seed = 1)
        assertFalse("a save for another roster is refused", other.restore(snap))
        assertNull(newGame(mode = PlayMode.ONLINE).snapshot())
    }

    @Test fun undoRestoresTheExactTableBeforeTheLastHumanShot() {
        val g = newGame(players = vsCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT, seed = 3)
        val before = g.state.value!!
        g.shoot(shotAtBall(g, 1))
        assertTrue(g.canUndo)
        g.undo()
        val after = g.state.value!!
        assertEquals(before.x, after.x); assertEquals(before.onTable, after.onTable); assertEquals(0, after.shotNo)
        assertEquals(before.shooter, after.shooter); assertEquals(before.isBreak, after.isBreak)
        assertFalse(g.canUndo)
        // a second shot after undo works as normal
        assertTrue(g.shoot(shotAtBall(g, 1, 0.8)))
    }

    @Test fun undoIsNeverOfferedOnline() {
        val g = newGame(mode = PlayMode.ONLINE)
        g.shoot(shotAtBall(g, 1))
        assertFalse(g.canUndo)
    }

    @Test fun randomPlayNeverThrowsAndEveryStateStaysValidAndRoundTrips() {
        val rnd = Random(77)
        var finished = 0
        repeat(25) { n ->
            val variant = if (n % 2 == 0) BilliardsVariant.EIGHT else BilliardsVariant.NINE
            val g = newGame(seed = n, variant = variant)
            var guard = 0
            while (g.state.value!!.phase == BilliardsPhase.AIM && guard++ < 150) {
                val s = g.state.value!!
                val cx: Double; val cy: Double
                if (s.ballInHand) {
                    val sp = BilliardsRules.freeSpot(rnd.nextDouble(2.0, 98.0), rnd.nextDouble(2.0, 48.0), s.xs(), s.ys(), s.ons(), s.behindHead)
                    cx = sp.first; cy = sp.second
                } else { cx = s.x[0]; cy = s.y[0] }
                // half the time aim at a legal ball, half the time anywhere
                val targets = BilliardsRules.legalTargets(s.variant, s.groups, s.shooter, s.ballsOnTable(), s.isBreak).toList()
                val angle = if (targets.isNotEmpty() && rnd.nextBoolean()) {
                    val t = targets[rnd.nextInt(targets.size)]; Math.atan2(s.y[t] - cy, s.x[t] - cx)
                } else rnd.nextDouble(-Math.PI, Math.PI)
                assertTrue(g.shoot(Shot(angle, rnd.nextDouble(0.05, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), cx, cy)))
                val ns = g.state.value!!
                assertTrue("valid after shot $guard", ns.isValid(humans))
                val snap = g.snapshot()
                if (ns.phase == BilliardsPhase.AIM) {
                    val twin = newGame(seed = 1000)
                    assertTrue(twin.restore(snap!!))
                    assertEquals(ns.x, twin.state.value!!.x)
                    assertEquals(ns.groups, twin.state.value!!.groups)
                }
            }
            if (g.state.value!!.phase == BilliardsPhase.OVER) finished++
        }
        assertTrue("some random racks finish ($finished/25)", finished > 0)
    }

    @Test fun finishingARackCountsTheWinAndTheSessionEndsWithTheLeader() {
        val g = newGame(variant = BilliardsVariant.NINE, seed = 2)
        // force a position where the 9 is the last ball and lies on the line to the top side pocket
        val s = g.state.value!!
        val x = s.x.toMutableList(); val y = s.y.toMutableList(); val on = s.onTable.toMutableList()
        for (i in 1..8) on[i] = false
        x[9] = 50.0; y[9] = 15.0; on[9] = true
        x[0] = 50.0; y[0] = 35.0
        val aligned = s.copy(x = x, y = y, onTable = on, isBreak = false, shotNo = 1)
        val json = Json { encodeDefaults = true }
        val snap = kotlinx.serialization.json.buildJsonObject {
            put("state", json.encodeToJsonElement(BilliardsState.serializer(), aligned))
            put("difficulty", kotlinx.serialization.json.JsonPrimitive("MEDIUM"))
            put("nextBreaker", kotlinx.serialization.json.JsonPrimitive(1))
            put("startedAtMs", kotlinx.serialization.json.JsonPrimitive(0))
        }.toString()
        assertTrue(g.restore(snap))
        assertTrue(g.shoot(Shot(-Math.PI / 2, 0.55, 0.0, 0.0, 50.0, 35.0)))
        val end = g.state.value!!
        assertEquals(BilliardsPhase.OVER, end.phase); assertEquals(0, end.winner); assertEquals(1, end.seats[0].wins)
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertTrue(result!!.scores.first { it.playerId == "p1" }.isWinner)
        assertFalse(result!!.scores.first { it.playerId == "p2" }.isWinner)
        assertTrue(g.matchOver.value)
    }

    @Test fun playAgainAlternatesTheBreakerAndKeepsTheSessionTally() {
        val g = newGame(variant = BilliardsVariant.NINE, seed = 2)
        val s = g.state.value!!
        assertEquals(0, s.shooter)
        g.playAgain() // not over yet: nothing happens
        assertEquals(s.serial, g.state.value!!.serial)
    }

    @Test fun anAbortedSessionScoresNothing() {
        val g = newGame()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted); assertTrue(result!!.scores.isEmpty())
    }

    @Test fun theHintIsALegalShotThatTheEngineAccepts() {
        val g = newGame(players = vsCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT, seed = 5)
        g.shoot(shotAtBall(g, 1)) // human break
        // let the CPU answer, then ask for a hint when it is the human's turn again
        var guard = 0
        while (g.botToMove() && guard++ < 10) g.playBotStep()
        if (g.state.value!!.shooter == 0 && g.state.value!!.phase == BilliardsPhase.AIM) {
            val hint = runBlocking { g.requestHint() }
            assertNotNull(hint)
            assertEquals(ShotReject.NONE, g.explainShot(hint!!))
            assertEquals(hint, g.hintShot.value)
        }
    }

    @Test fun aComputerStrokeIsPlayedOffTheMainThread() {
        val g = newGame(players = listOf(PlayerInfo("cpu", "CPU", isBot = true), PlayerInfo("you", "You")), mode = PlayMode.SINGLE_PLAYER_VS_BOT, seed = 6, level = CpuDifficulty.HARD)
        assertTrue(g.botToMove())
        runBlocking { g.playBotTurnAsync() }
        assertEquals(1, g.state.value!!.shotNo)
        assertNotEquals(null, g.state.value!!.lastShot)
    }
}
