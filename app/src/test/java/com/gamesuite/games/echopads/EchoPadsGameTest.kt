package com.gamesuite.games.echopads

import com.gamesuite.core.GameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EchoPadsGameTest {
    private class Rig(humans: Int, mode: EchoMode, seed: Int = 5, forgiving: Boolean = false) {
        val clock = EchoClock()
        val game = EchoPadsGame(Random(seed), clock).also {
            it.mode = mode
            it.forgiving = forgiving
            it.init(echoContext(humans))
        }

        private fun stamp(): List<Any?> =
            game.engine?.let { listOf(it.eventSerial, it.phase, it.progress, it.currentPlayer) } ?: emptyList()

        /** Runs frames; at every one, any change of what the screen draws must have bumped the version (a missed bump freezes the UI). */
        fun frames(ms: Long) {
            var left = ms
            while (left > 0) {
                val before = stamp()
                val v0 = game.version.intValue
                clock.advance(50)
                game.tick()
                if (stamp() != before) assertTrue("state changed without a version bump: $before -> ${stamp()}", game.version.intValue != v0)
                left -= 50
            }
        }

        fun until(cond: () -> Boolean) {
            var spent = 0L
            while (!cond()) { frames(50); spent += 50; check(spent < 1_000_000L) { "never reached" } }
        }

        val e get() = game.engine!!

        fun toWaiting() {
            if (e.awaitingReady) game.ready()
            until { e.phase == EchoPhase.WAITING || e.phase == EchoPhase.OVER }
        }

        fun good() {
            toWaiting()
            for (p in e.sequence.toList()) game.tap(p)
            until { e.phase != EchoPhase.CHECKING }
        }

        fun bad() {
            toWaiting()
            game.tap((e.sequence[e.progress] + 1) % 4)
            until { e.phase != EchoPhase.CHECKING }
        }
    }

    @Test fun aLonePlayerIsAlwaysSoloWhateverTheScreenAsked() {
        val r = Rig(1, EchoMode.DUEL)
        assertEquals(EchoMode.SOLO, r.game.effectiveMode())
        r.game.startMatch()
        assertEquals(EchoMode.SOLO, r.e.config.mode)
    }

    @Test fun aDuelNeedsExactlyTwoPlayersOtherwiseItIsPassAndPlay() {
        assertEquals(EchoMode.DUEL, Rig(2, EchoMode.DUEL).game.effectiveMode())
        assertEquals(EchoMode.PASS_AND_PLAY, Rig(3, EchoMode.DUEL).game.effectiveMode())
        assertEquals(EchoMode.PASS_AND_PLAY, Rig(2, EchoMode.PASS_AND_PLAY).game.effectiveMode())
    }

    @Test fun theMatchRunsOnTheGamesClockAndBumpsTheVersionOnlyWhenSomethingChanged() {
        val r = Rig(1, EchoMode.SOLO)
        r.game.startMatch()
        val v0 = r.game.version.intValue
        assertFalse(r.game.tick())
        assertEquals(v0, r.game.version.intValue)
        r.until { r.e.phase == EchoPhase.SHOWING }
        assertTrue(r.game.version.intValue > v0)
    }

    @Test fun tapsFollowTheEnginesPhases() {
        val r = Rig(1, EchoMode.SOLO)
        r.game.startMatch()
        assertEquals(TapResult.IGNORED, r.game.tap(r.e.sequence[0]))
        r.toWaiting()
        assertEquals(TapResult.SEQUENCE_DONE, r.game.tap(r.e.sequence[0]))
    }

    @Test fun inADuelOnlyThePlayerWhoseTurnItIsCanAnswer() {
        val r = Rig(2, EchoMode.DUEL)
        r.game.startMatch()
        r.toWaiting()
        val pad = r.e.sequence[0]
        assertEquals(0, r.e.currentPlayer)
        assertEquals("the other half is not live", TapResult.IGNORED, r.game.tap(pad, seat = 1))
        assertEquals(EchoPhase.WAITING, r.e.phase)
        assertEquals(TapResult.SEQUENCE_DONE, r.game.tap(pad, seat = 0))
    }

    @Test fun aSoloMissEndsTheMatchAndRecordsTheSessionBest() {
        val r = Rig(1, EchoMode.SOLO)
        r.game.startMatch()
        repeat(3) { r.good() }
        r.bad()
        assertEquals(EchoPhase.OVER, r.e.phase)
        assertEquals(3, r.game.sessionBest.intValue)
        // Play again starts a fresh pattern and keeps the best.
        r.game.playAgain()
        assertEquals(1, r.e.round)
        assertEquals(3, r.game.sessionBest.intValue)
        r.bad()
        assertEquals(3, r.game.sessionBest.intValue)
    }

    @Test fun theWinnerOfAPassAndPlayMatchGetsASessionWin() {
        val r = Rig(2, EchoMode.PASS_AND_PLAY)
        r.game.startMatch()
        r.bad(); r.good()
        assertEquals(EchoPhase.OVER, r.e.phase)
        assertEquals(mapOf("p1" to 0, "p2" to 1), r.game.sessionWins.value)
        r.game.playAgain()
        r.good(); r.bad()
        assertEquals(mapOf("p1" to 1, "p2" to 1), r.game.sessionWins.value)
    }

    @Test fun aTieGivesNobodyASessionWin() {
        val r = Rig(2, EchoMode.DUEL)
        r.game.startMatch()
        r.bad(); r.bad()
        assertTrue(r.e.isTie)
        assertEquals(mapOf("p1" to 0, "p2" to 0), r.game.sessionWins.value)
    }

    @Test fun leavingAfterAFinishedMatchReportsTheScoresAndTheWinner() {
        val r = Rig(2, EchoMode.PASS_AND_PLAY)
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.game.startMatch()
        r.good(); r.bad()      // player 1 copies the first pattern, player 2 misses it
        r.game.leaveSession()
        val res = result!!
        assertFalse(res.wasAborted)
        assertEquals(listOf("p1", "p2"), res.scores.map { it.playerId })
        assertEquals(listOf(true, false), res.scores.map { it.isWinner })
        assertEquals(listOf(1, 0), res.scores.map { it.score })
    }

    @Test fun leavingMidMatchReportsNothingToScore() {
        val r = Rig(2, EchoMode.PASS_AND_PLAY)
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.game.startMatch()
        r.game.leaveSession()
        assertTrue(result!!.scores.isEmpty())
        // A second leave does nothing.
        result = null
        r.game.leaveSession()
        assertNull(result)
    }

    @Test fun aSoloGameThatCopiedNothingIsNotAWin() {
        val r = Rig(1, EchoMode.SOLO)
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.game.startMatch()
        r.bad()
        r.game.leaveSession()
        assertEquals(listOf(false), result!!.scores.map { it.isWinner })
    }

    @Test fun backgroundingTheAppDoesNotSkipThePattern() {
        val r = Rig(2, EchoMode.DUEL)
        r.game.startMatch()
        r.game.pause()
        r.clock.advance(120_000)
        r.game.resume()
        r.game.tick()
        assertEquals(EchoPhase.READY, r.e.phase)
        r.until { r.e.phase == EchoPhase.SHOWING }
    }

    // ---- save and resume ----

    @Test fun aPassAndPlayMatchRoundTripsThroughASnapshot() {
        val a = Rig(3, EchoMode.PASS_AND_PLAY, seed = 21, forgiving = true)
        a.game.speed = EchoSpeed.SLOW
        a.game.startMatch()
        repeat(3) { a.good() }
        // Easy mode: the first wrong pad is a free try, the second is the miss that puts player 2 out.
        a.good(); a.bad(); a.bad()
        a.toWaiting()
        val snap = a.game.snapshot()
        assertNotNull(snap)

        val b = Rig(3, EchoMode.PASS_AND_PLAY, seed = 99)
        assertTrue(b.game.restore(snap!!))
        val e = b.game.engine!!
        assertEquals(a.e.sequence.toList(), e.sequence.toList())
        assertEquals(a.e.progressSnapshot(), e.progressSnapshot())
        assertEquals(EchoPhase.READY, e.phase)
        assertEquals(2, e.currentPlayer)
        assertEquals(EchoSpeed.SLOW, e.config.speed)
        assertTrue("forgiving comes back from the save", e.config.forgiving)
        assertEquals(a.game.sessionWins.value, b.game.sessionWins.value)
        // The restored match plays on.
        b.good()
        assertEquals(0, b.e.currentPlayer)
    }

    @Test fun theSessionTallyAndSoloBestSurviveASnapshot() {
        val a = Rig(1, EchoMode.SOLO)
        a.game.startMatch()
        repeat(4) { a.good() }
        a.bad()
        a.game.playAgain()
        repeat(2) { a.good() }
        val snap = a.game.snapshot()!!
        val b = Rig(1, EchoMode.SOLO)
        assertTrue(b.game.restore(snap))
        assertEquals(4, b.game.sessionBest.intValue)
        assertEquals(3, b.e.round)
    }

    @Test fun thereIsNothingToSaveBeforeStartAfterTheEndOrWhenLeft() {
        val r = Rig(1, EchoMode.SOLO)
        assertNull(r.game.snapshot())
        r.game.startMatch()
        assertNotNull(r.game.snapshot())
        r.bad()
        assertNull("a finished match is not saved", r.game.snapshot())
    }

    @Test fun corruptOrForeignSnapshotsAreRejectedAndLeaveTheGameUntouched() {
        val a = Rig(3, EchoMode.PASS_AND_PLAY, seed = 21)
        a.game.startMatch()
        repeat(3) { a.good() }
        val good = a.game.snapshot()!!

        fun rejects(why: String, text: String, humans: Int = 3, mode: EchoMode = EchoMode.PASS_AND_PLAY) {
            val b = Rig(humans, mode)
            assertFalse("should reject: $why", b.game.restore(text))
            assertNull("untouched after: $why", b.game.engine)
        }
        rejects("not json", "{ nope")
        rejects("empty", "")
        rejects("a bad pad", good.replace("\"sequence\":[", "\"sequence\":[9,"))
        rejects("order position out of range", good.replace(Regex("\"orderPos\":\\d+"), "\"orderPos\":9"))
        rejects("unknown speed", good.replace("\"speed\":\"normal\"", "\"speed\":\"warp\""))
        rejects("a different roster size", good, humans = 4)
        rejects("a different player", good.replace("\"p1\"", "\"zz\""))
        rejects("a negative best", good.replace(Regex("\"sessionBest\":\\d+"), "\"sessionBest\":-3"))
        rejects("a win for a stranger", good.replace("\"sessionWins\":{", "\"sessionWins\":{\"ghost\":4,"))
        // A party save cannot be loaded into a duel of the same two... roster size differs, and a duel save cannot be loaded as a party.
        val duel = Rig(2, EchoMode.DUEL)
        duel.game.startMatch()
        val duelSave = duel.game.snapshot()!!
        rejects("duel save in a party game", duelSave, humans = 2, mode = EchoMode.PASS_AND_PLAY)
        assertTrue(Rig(2, EchoMode.DUEL).game.restore(duelSave))
    }

    @Test fun aFailedRestoreCanStillStartAFreshMatch() {
        val r = Rig(2, EchoMode.DUEL)
        assertFalse(r.game.restore("garbage"))
        r.game.startMatch()
        assertEquals(1, r.e.round)
    }

    @Test fun theSameSeedReplaysTheSameMatchThroughTheGame() {
        fun sequenceAfter(seed: Int): List<Int> {
            val r = Rig(1, EchoMode.SOLO, seed = seed)
            r.game.startMatch()
            repeat(5) { r.good() }
            return r.e.sequence.toList()
        }
        assertEquals(sequenceAfter(8), sequenceAfter(8))
    }

    @Test fun theVersionBumpsAtEveryChangeThroughALongPassAndPlayMatchWithEliminations() {
        // The audit's freeze: round 4 with players 0 and 2 left (1 out); player 0 finishes and the hand-off to 2
        // used to leave the packed signature unchanged, so the Ready button never appeared.
        val r = Rig(3, EchoMode.PASS_AND_PLAY)
        r.game.startMatch()
        r.good(); r.bad(); r.good()
        assertTrue(r.e.isOut(1))
        repeat(3) { r.good(); r.good() }
        assertEquals(5, r.e.round)
        assertEquals(0, r.e.currentPlayer)
        assertTrue(r.e.awaitingReady)
    }

    @Test fun theSignatureNeverCollidesAcrossTheAuditExamples() {
        val a = EchoPadsGame.Sig(serial = 10, phase = EchoPhase.CHECKING, progress = 4, player = 0)
        val b = EchoPadsGame.Sig(serial = 11, phase = EchoPhase.READY, progress = 0, player = 2)
        assertTrue(a != b)
        // Two states that differ in a single field are always different.
        assertTrue(a != a.copy(player = 1))
        assertTrue(a != a.copy(progress = 5))
        assertTrue(a != a.copy(phase = EchoPhase.WAITING))
        assertTrue(a != a.copy(serial = 11))
    }

    @Test fun pausingTwiceKeepsTheFirstMomentSoNoTimeIsLost() {
        val r = Rig(1, EchoMode.SOLO)
        r.game.startMatch()
        r.game.pause()
        r.clock.advance(1_000)
        r.game.pause()
        r.clock.advance(1_000)
        r.game.resume()
        r.game.tick()
        assertEquals("the whole 2s away is given back", EchoPhase.READY, r.e.phase)
        r.until { r.e.phase == EchoPhase.SHOWING }
    }

    @Test fun theEngineStandsStillWhilePausedAndAPatternReplaysFromItsStartOnResume() {
        val r = Rig(1, EchoMode.SOLO)
        r.game.startMatch()
        r.until { r.e.eventsSince(0).any { it.kind == EchoEventKind.PAD_SHOWN } }
        assertEquals(EchoPhase.SHOWING, r.e.phase)
        r.game.pause()
        r.clock.advance(5_000)
        assertFalse("no ticking while paused", r.game.tick())
        assertEquals(EchoPhase.SHOWING, r.e.phase)
        r.game.resume()
        val serial = r.e.eventSerial
        assertEquals(EchoPhase.SHOWING, r.e.phase)
        assertEquals("starts again from before the first pad", -1, r.e.litStep(r.clock.now))
        assertTrue(r.e.eventsSince(serial).isEmpty())
        r.until { r.e.phase == EchoPhase.WAITING }
        assertEquals("the pattern was shown again in full", 1, r.e.eventsSince(serial).count { it.kind == EchoEventKind.PAD_SHOWN })
        assertFalse("a resume is not a free retry", r.e.retryUsed)
    }
}
