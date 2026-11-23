package com.gamesuite.games.echopads

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The audit findings an earlier pass skipped: F7 (a miss caught by a save) and the smaller F10 items. */
class EchoPadsAuditLeftoversTest {
    private fun engine(cfg: EchoConfig, seed: Int = 3) = EchoPadsEngine(cfg, Random(seed)).also { it.startMatch(1_000) }

    // ---- F7: a miss in CHECKING survives a save ----

    @Test fun aMissCaughtByASnapshotIsStillAMissAfterRestoreAndEliminatesThePlayer() {
        val d = EchoDriver(engine(EchoConfig(EchoMode.PASS_AND_PLAY, 3)))
        d.goodTurn()                        // player 0 copies it
        d.toWaiting()
        assertEquals(TapResult.MISS, d.miss())   // player 1 misses; the feedback pause has not run out
        val saved = d.e.progressSnapshot()
        assertTrue(saved.pendingMiss)
        assertFalse("the elimination is applied by the pause, not the save", saved.out[1])
        assertEquals(1, saved.order[saved.orderPos])

        val e2 = EchoPadsEngine(d.e.config, Random(9))
        assertTrue(e2.resume(50_000, saved))
        assertEquals(EchoPhase.CHECKING, e2.phase)
        assertEquals(EchoOutcome.MISS, e2.outcome)
        assertEquals(1, e2.currentPlayer)
        val d2 = EchoDriver(e2, 50_000)
        d2.settle()
        assertTrue("player 1 is out, not handed a free retry", e2.isOut(1))
        assertEquals(listOf(1), e2.eliminationOrder)
        assertEquals(2, e2.currentPlayer)
        assertEquals(0, e2.roundResult(1))
    }

    @Test fun aSoloMissCaughtByASnapshotStillEndsTheMatch() {
        val d = EchoDriver(engine(EchoConfig(EchoMode.SOLO, 1)))
        d.goodTurn(); d.goodTurn()
        d.toWaiting(); d.miss()
        val saved = d.e.progressSnapshot()
        val e2 = EchoPadsEngine(d.e.config, Random(1))
        assertTrue(e2.resume(0, saved))
        EchoDriver(e2, 0).settle()
        assertEquals(EchoPhase.OVER, e2.phase)
        assertEquals(2, e2.completedBy(0))
    }

    @Test fun anOrdinaryTurnIsNotSavedAsAMiss() {
        val d = EchoDriver(engine(EchoConfig(EchoMode.PASS_AND_PLAY, 3)))
        d.toWaiting()
        assertFalse(d.e.progressSnapshot().pendingMiss)
        d.copyAll()
        assertFalse("a copied pattern is not a miss", d.e.progressSnapshot().pendingMiss)
    }

    @Test fun aSpentFreeTryIsNotRenewedByARestore() {
        val cfg = EchoConfig(EchoMode.SOLO, 1, forgiving = true)
        val d = EchoDriver(engine(cfg))
        d.toWaiting()
        assertEquals(TapResult.RETRY, d.miss())
        val saved = d.e.progressSnapshot()
        assertTrue(saved.retryUsed)
        assertFalse(saved.pendingMiss)
        val e2 = EchoPadsEngine(cfg, Random(1))
        assertTrue(e2.resume(0, saved))
        assertTrue(e2.retryUsed)
        val d2 = EchoDriver(e2, 0)
        d2.toWaiting()
        assertEquals("the second wrong pad is the miss", TapResult.MISS, d2.miss())
    }

    @Test fun aFreshTurnAfterARestoreStillHasItsFreeTry() {
        val cfg = EchoConfig(EchoMode.SOLO, 1, forgiving = true)
        val d = EchoDriver(engine(cfg))
        d.toWaiting()
        val e2 = EchoPadsEngine(cfg, Random(1))
        assertTrue(e2.resume(0, d.e.progressSnapshot()))
        val d2 = EchoDriver(e2, 0)
        d2.toWaiting()
        assertEquals(TapResult.RETRY, d2.miss())
    }

    @Test fun aSpentFreeTryIsRejectedWhenTheMatchGivesNone() {
        val d = EchoDriver(engine(EchoConfig(EchoMode.PASS_AND_PLAY, 3)))
        d.toWaiting()
        val p = d.e.progressSnapshot()
        assertFalse(EchoPadsEngine(d.e.config, Random(1)).resume(0, p.copy(retryUsed = true)))
        assertTrue(EchoPadsEngine(d.e.config, Random(1)).resume(0, p))
    }

    @Test fun anOldSaveWithoutTheNewFieldsStillLoads() {
        val d = EchoDriver(engine(EchoConfig(EchoMode.PASS_AND_PLAY, 3)))
        d.toWaiting()
        val p = d.e.progressSnapshot()
        val old = EchoProgress(p.sequence, p.order, p.orderPos, p.results, p.out, p.elimination, p.completed)
        assertFalse(old.pendingMiss)
        assertFalse(old.retryUsed)
        assertTrue(EchoPadsEngine(d.e.config, Random(1)).resume(0, old))
    }

    @Test fun theGameSnapshotCarriesTheMiss() {
        val clock = EchoClock()
        val a = EchoPadsGame(Random(4), clock).also { it.mode = EchoMode.SOLO; it.init(echoContext(1)); it.startMatch() }
        var left = 10_000L
        while (left > 0) { clock.advance(50); a.tick(); left -= 50 }
        val e = a.engine!!
        assertEquals(EchoPhase.WAITING, e.phase)
        a.tap((e.sequence[0] + 1) % 4)
        assertEquals(EchoOutcome.MISS, e.outcome)
        val snap = a.snapshot()
        assertNotNull(snap)
        assertTrue(snap!!.contains("\"pendingMiss\":true"))
        val b = EchoPadsGame(Random(4), EchoClock()).also { it.mode = EchoMode.SOLO; it.init(echoContext(1)) }
        assertTrue(b.restore(snap))
        assertEquals(EchoOutcome.MISS, b.engine!!.outcome)
        assertEquals(EchoPhase.CHECKING, b.engine!!.phase)
    }

    // ---- F10: rosters over six ----

    private fun bigContext(n: Int) = GameContext(
        activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        players = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") },
        localPlayerIndex = 0, transport = EchoNoTransport()
    )

    @Test fun aRosterOverSixIsCutToTheSeatsTheGameHasSoEveryIndexAgrees() {
        val g = EchoPadsGame(Random(2), EchoClock()).also { it.mode = EchoMode.PASS_AND_PLAY; it.init(bigContext(9)) }
        assertEquals(EchoRules.MAX_PLAYERS, g.players.size)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "p6"), g.players.map { it.playerId })
        assertEquals(EchoRules.MAX_PLAYERS, g.sessionWins.value.size)
        g.startMatch()
        assertEquals(g.players.size, g.engine!!.config.players)
        // A cut roster still saves and restores.
        val snap = g.snapshot()
        assertNotNull(snap)
        val h = EchoPadsGame(Random(2), EchoClock()).also { it.mode = EchoMode.PASS_AND_PLAY; it.init(bigContext(9)) }
        assertTrue(h.restore(snap!!))
    }

    @Test fun rosterCuttingKeepsShortRostersWhole() {
        val two = List(2) { PlayerInfo("p$it", "P$it") }
        assertEquals(two, EchoPadsGame.rosterFor(two))
        assertEquals(6, EchoPadsGame.rosterFor(List(40) { PlayerInfo("p$it", "P$it") }).size)
    }

    @Test fun aDuelNeedsExactlyTwoEvenWithABigRoster() {
        val g = EchoPadsGame(Random(2), EchoClock()).also { it.mode = EchoMode.DUEL; it.init(bigContext(8)) }
        assertEquals(EchoMode.PASS_AND_PLAY, g.effectiveMode())
    }

    // ---- F10: best-score table decode, entry by entry ----

    @Test fun oneBadBestEntryDoesNotTakeTheGoodOnesWithIt() {
        val raw = """{"normal":12,"slow":"oops","fast":-4,"normal+easy":7,"x":1.5,"y":999,"z":null,"w":[1],"v":{"a":1}}"""
        assertEquals(mapOf("normal" to 12, "normal+easy" to 7), EchoPadsBest.decode(raw))
    }

    @Test fun aWholeBlobThatIsNotATableIsEmpty() {
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode("[1,2]"))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode("{ nope"))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode("42"))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode(null))
        assertEquals(emptyMap<String, Int>(), EchoPadsBest.decode("  "))
    }

    @Test fun theBestTableRoundTripsAndKeepsUnknownKeys() {
        val t = mapOf("normal" to 9, "future-speed+easy" to 4)
        assertEquals(t, EchoPadsBest.decode(EchoPadsBest.encode(t)))
        assertEquals(mapOf("normal" to 40, "slow" to 0), EchoPadsBest.decode("""{"normal":40,"slow":0,"fast":41}"""))
    }

    // ---- F10: audio cues ----

    @Test fun aFreeRetryHasItsOwnSoftCueDistinctFromTheMissBuzz() {
        val retry = EchoCues.soundFor(EchoEventKind.RETRY, multi = false)
        val miss = EchoCues.soundFor(EchoEventKind.MISS, multi = false)
        assertEquals(EchoCues.Sound.SOFT_RETRY, retry)
        assertEquals(EchoCues.Sound.MISS_BUZZ, miss)
        assertNotEquals(retry, miss)
    }

    @Test fun everyEventKindHasADefinedCue() {
        for (k in EchoEventKind.entries) EchoCues.soundFor(k, true)
        assertEquals(EchoCues.Sound.WIN, EchoCues.soundFor(EchoEventKind.MATCH_OVER, true))
        assertEquals(EchoCues.Sound.NONE, EchoCues.soundFor(EchoEventKind.MATCH_OVER, false))
        assertEquals(EchoCues.Sound.TONE_PAD, EchoCues.soundFor(EchoEventKind.PAD_SHOWN, false))
    }

    // ---- F10: gap snapping ----

    @Test fun aTouchInTheGapSnapsToTheNearestPad() {
        val w = 300f; val h = 300f
        val r0 = EchoPadGrid.rect(0, w, h); val r1 = EchoPadGrid.rect(1, w, h)
        val y = (r0[1] + r0[3]) / 2
        assertEquals(-1, EchoPadGrid.padAt(r0[2] + 1f, y, w, h))
        assertEquals(0, EchoPadGrid.nearestPad(r0[2] + 1f, y, w, h))
        assertEquals(1, EchoPadGrid.nearestPad(r1[0] - 1f, y, w, h))
        for (i in 0 until 4) {
            val r = EchoPadGrid.rect(i, w, h)
            assertEquals(i, EchoPadGrid.nearestPad((r[0] + r[2]) / 2, (r[1] + r[3]) / 2, w, h))
        }
    }

    @Test fun theMiddleOfTheCrossroadsSnapsSomewhereAndFarOutsideNowhere() {
        val w = 300f; val h = 300f
        val r0 = EchoPadGrid.rect(0, w, h); val r3 = EchoPadGrid.rect(3, w, h)
        val cx = (r0[2] + r3[0]) / 2; val cy = (r0[3] + r3[1]) / 2
        assertTrue(EchoPadGrid.nearestPad(cx, cy, w, h) in 0..3)
        assertEquals(-1, EchoPadGrid.nearestPad(-1f, 10f, w, h))
        assertEquals(-1, EchoPadGrid.nearestPad(10f, h + 5f, w, h))
    }

    @Test fun snappingWorksInAOneRowLayoutToo() {
        val w = 800f; val h = 120f
        val r0 = EchoPadGrid.rect(0, w, h, 4); val r1 = EchoPadGrid.rect(1, w, h, 4)
        val mid = (r0[2] + r1[0]) / 2
        val y = (r0[1] + r0[3]) / 2
        assertTrue(EchoPadGrid.nearestPad(mid, y, w, h, 4) in 0..1)
        assertEquals(2, EchoPadGrid.nearestPad(EchoPadGrid.rect(2, w, h, 4)[0] + 3f, y, w, h, 4))
    }
}
