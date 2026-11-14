package com.gamesuite.games.bridgebuilders

import com.gamesuite.core.GameResult
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BridgeBuildersGameTest {
    private class Rig(val game: BridgeBuildersGame, val clock: BridgeClock) {
        val rig = BridgeRig(game.engine, clock)
    }

    private fun game(humans: Int = 2, bots: Int = 0, seed: Int = 1): Rig {
        val clock = BridgeClock()
        val g = BridgeBuildersGame(Random(seed), clock)
        g.init(bridgeContext(humans, bots))
        return Rig(g, clock)
    }

    private fun Rig.started(): Rig { game.startMatch(); return this }

    private fun valid(humans: Int = 2, bots: Int = 0): BridgeSave = BridgeSave(
        players = bridgeSeats(humans, bots).map { BridgePlayerSave(it.playerId, it.isBot) },
        band = "medium", relaxed = false, handicap = "none", difficulty = "MEDIUM",
        lanes = List(humans + bots) { BridgeLaneSave(1, 0.9) }, water = 0.3, bridges = 1, tries = 2, eventSerial = 5
    )

    private fun restoresInto(save: BridgeSave, humans: Int = 2, bots: Int = 0): Boolean {
        val g = game(humans, bots).game
        val ok = g.restore(BridgeSaveCodec.encode(save))
        if (!ok) assertNull("a rejected save must leave the game untouched", g.state.value)
        return ok
    }

    // ---- lifecycle ----

    @Test fun theGameIsACooperativeFourSeatArcadeGameForTheSameScreen() {
        val g = BridgeBuildersGame()
        assertEquals("bridge-builders", g.gameId)
        assertEquals(2, g.minPlayers); assertEquals(4, g.maxPlayers)
        assertEquals(com.gamesuite.core.GameCategory.ARCADE, g.category)
        assertFalse(g.supportedModes.contains(com.gamesuite.core.PlayMode.ONLINE))
        assertFalse(g.supportedModes.contains(com.gamesuite.core.PlayMode.LOCAL_AD_HOC))
    }

    @Test fun startingPublishesAStateAndTicksKeepItCurrent() {
        val r = game().started()
        assertNotNull(r.game.state.value)
        assertEquals("COUNTDOWN", r.game.state.value!!.phase)
        r.rig.adv(BridgeRules.COUNTDOWN_MS + 50)
        assertTrue(r.game.tick())
        assertEquals("BUILD", r.game.state.value!!.phase)
    }

    @Test fun tappingThroughTheGameWorks() {
        val r = game().started()
        r.rig.live()
        r.game.tick()
        val res = r.rig.place(0)
        assertTrue(res.placed)
        r.game.tick()
        assertEquals(1, r.game.state.value!!.lanes[0].planks)
        assertEquals(BridgeTapResult.BAD_SEAT, r.game.tap(9))
    }

    @Test fun leavingAfterABridgeCountsEveryoneAsHavingWonTogether() {
        val r = game(humans = 2).started()
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.game.engine.setup(bridgeSeats(2, 0)) // fresh table
        r.game.startMatch()
        r.rig.live()
        repeat(r.game.state.value!!.spans) { r.rig.place(0); r.rig.place(1) }
        r.rig.adv(9000)
        r.game.tick()
        assertTrue(r.game.state.value!!.won)
        r.game.leaveSession()
        val res = result!!
        assertFalse(res.wasAborted)
        assertEquals(listOf(true, true), res.scores.map { it.isWinner })
        assertTrue(res.scores.all { it.score == 1 })
        assertTrue(r.game.matchOver.value)
    }

    @Test fun leavingWithNoBridgeBuiltIsAnAbortNobodyIsRecordedAsLosing() {
        val r = game(humans = 2).started()
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.game.leaveSession()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
        assertTrue(r.game.matchOver.value)
    }

    @Test fun playAgainStartsAFreshTryAndLeavingTwiceEndsOnce() {
        val r = game().started()
        var ends = 0
        r.game.setOnMatchEnd { ends++ }
        r.game.playAgain()
        assertEquals(2, r.game.state.value!!.tries)
        r.game.leaveSession(); r.game.leaveSession()
        assertEquals(1, ends)
        r.game.playAgain() // no effect once the match is over
        assertTrue(r.game.matchOver.value)
    }

    // ---- seats ----

    @Test fun aLoneBuilderGetsAHelperRobotInsteadOfACrash() {
        val r = game(humans = 1).started()
        val s = r.game.state.value!!
        assertEquals(2, s.lanes.size)
        assertEquals(listOf(false, true), s.lanes.map { it.isBot })
        r.rig.live()
        r.game.tick()
        assertTrue(r.rig.place(0).placed)
        // the table still saves and restores (the roster is padded the same way both times)
        val snap = r.game.snapshot()!!
        assertTrue(game(humans = 1).game.restore(snap))
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.game.leaveSession()
        assertTrue(result!!.wasAborted) // no bridge built: not recorded for the real player (nor the helper)
    }

    @Test fun moreThanFourSeatsIsRefusedAtInitNotSilentlyTruncated() {
        for (n in listOf(5, 6)) {
            val failed = try { BridgeBuildersGame(Random(1), BridgeClock()).init(bridgeContext(n, 0)); false } catch (_: IllegalArgumentException) { true }
            assertTrue("$n seats must be refused", failed)
        }
        val none = try { BridgeBuildersGame(Random(1), BridgeClock()).init(bridgeContext(0, 0)); false } catch (_: IllegalArgumentException) { true }
        assertTrue(none)
    }

    @Test fun restoreAndLeaveBeforeInitAreRefusedWithoutThrowing() {
        val g = BridgeBuildersGame(Random(1), BridgeClock())
        assertFalse(g.restore(BridgeSaveCodec.encode(valid())))
        g.leaveSession() // nothing to end, nothing thrown
        assertFalse(g.matchOver.value)
    }

    // ---- pause ----

    @Test fun pausingTheGameFreezesTheWaterAndResumeContinuesWithoutABurst() {
        val r = game().started()
        r.rig.live()
        r.rig.adv(2000)
        r.game.tick()
        r.game.pause()
        val w = r.game.state.value!!.water
        r.clock.advance(90_000)
        r.game.tick()
        assertEquals(w, r.game.state.value!!.water, 0f)
        r.game.resume()
        r.game.tick()
        assertEquals(w, r.game.state.value!!.water, 0.0005f)
        r.rig.adv(1000)
        r.game.tick()
        assertTrue(r.game.state.value!!.water > w)
        assertFalse(r.game.state.value!!.over)
    }

    // ---- snapshot ----

    @Test fun thereIsNothingToSaveBeforeStartAfterTheEndOrWhenTheTryIsDecided() {
        val r = game()
        assertNull(r.game.snapshot())
        r.game.startMatch()
        assertNotNull(r.game.snapshot())
        r.rig.live(); r.rig.adv(120_000)
        r.game.tick()
        assertTrue(r.game.state.value!!.over)
        assertNull(r.game.snapshot())
        r.game.leaveSession()
        assertNull(r.game.snapshot())
    }

    @Test fun aSnapshotRoundTripsPlanksWaterAndTheSessionTally() {
        val a = game(humans = 2, bots = 1, seed = 3)
        a.game.band = BridgeBand.LITTLE; a.game.relaxed = true; a.game.handicap = "p2"; a.game.difficulty = CpuDifficulty.HARD
        a.game.startMatch()
        a.rig.live()
        a.rig.adv(8000)
        a.rig.place(0); a.rig.place(1)
        a.game.tick()
        val before = a.game.state.value!!
        val snap = a.game.snapshot()!!

        val b = game(humans = 2, bots = 1, seed = 9)
        assertTrue(b.game.restore(snap))
        val after = b.game.state.value!!
        assertEquals(before.lanes.map { it.planks }, after.lanes.map { it.planks })
        assertEquals(before.water, after.water, 1e-9f)
        assertEquals(before.bridgesBuilt, after.bridgesBuilt)
        assertEquals(before.tries, after.tries)
        assertEquals("little", after.band); assertTrue(after.relaxed)
        assertEquals("p2", b.game.handicap); assertEquals(CpuDifficulty.HARD, b.game.difficulty)
        assertEquals("COUNTDOWN", after.phase) // a resumed try starts again from its countdown
        assertEquals(listOf(false, true, false), after.lanes.map { it.widened })
        // and it plays on: after the countdown the water continues from where it was
        b.rig.adv(BridgeRules.COUNTDOWN_MS + 2000)
        b.game.tick()
        // (a CPU lane may place a plank meanwhile, which pushes the water back a little, so only "it did not restart from zero" is certain)
        val resumed = b.game.state.value!!
        assertEquals("BUILD", resumed.phase)
        assertTrue("the water continued from ${before.water}, got ${resumed.water}", resumed.water > before.water * 0.5f)
        // a second snapshot of the restored game restores too
        assertTrue(game(2, 1).game.restore(b.game.snapshot()!!))
    }

    @Test fun aCorruptOrForeignSnapshotIsRejectedAndLeavesTheGameUntouched() {
        val g = game().game
        assertFalse(g.restore(""))
        assertFalse(g.restore("not json"))
        assertFalse(g.restore("{}"))
        assertFalse(g.restore("[1,2,3]"))
        assertFalse(g.restore("{\"version\":1,\"players\":"))
        assertNull(g.state.value)
        assertTrue(restoresInto(valid()))
    }

    @Test fun everyKindOfInvalidSaveIsRejected() {
        val ok = valid()
        assertFalse(restoresInto(ok.copy(version = 2)))
        assertFalse(restoresInto(ok.copy(band = "huge")))
        assertFalse(restoresInto(ok.copy(difficulty = "IMPOSSIBLE")))
        assertFalse(restoresInto(ok.copy(handicap = "x".repeat(41))))
        assertFalse(restoresInto(ok.copy(water = 1.0)))
        assertFalse(restoresInto(ok.copy(water = -0.1)))
        assertFalse(restoresInto(ok.copy(bridges = -1)))
        assertFalse(restoresInto(ok.copy(tries = -1)))
        assertFalse(restoresInto(ok.copy(eventSerial = -1)))
        // planks out of range (medium has 5 spans)
        assertFalse(restoresInto(ok.copy(lanes = listOf(BridgeLaneSave(6, 5.0), BridgeLaneSave(4, 3.0)))))
        assertFalse(restoresInto(ok.copy(lanes = listOf(BridgeLaneSave(-1, 0.0), BridgeLaneSave(0, 0.0)))))
        // quality impossible for the planks
        assertFalse(restoresInto(ok.copy(lanes = listOf(BridgeLaneSave(2, 2.5), BridgeLaneSave(2, 1.0)))))
        assertFalse(restoresInto(ok.copy(lanes = listOf(BridgeLaneSave(2, 0.5), BridgeLaneSave(2, 1.0)))))
        // breaks the cooperation rule: more than two planks ahead
        assertFalse(restoresInto(ok.copy(lanes = listOf(BridgeLaneSave(3, 2.5), BridgeLaneSave(0, 0.0)))))
        // wrong roster
        assertFalse(restoresInto(ok.copy(players = listOf(BridgePlayerSave("p1", false), BridgePlayerSave("zzz", false)))))
        assertFalse(restoresInto(ok.copy(players = listOf(BridgePlayerSave("p1", false), BridgePlayerSave("p2", true)))))
        assertFalse(restoresInto(ok.copy(players = ok.players.take(1))))
        assertFalse(restoresInto(ok.copy(lanes = ok.lanes.take(1))))
        assertFalse(restoresInto(valid(humans = 3), humans = 2))
        // sanity: the unmodified save is accepted, as is one with a lead of exactly two
        assertTrue(restoresInto(ok))
        assertTrue(restoresInto(ok.copy(lanes = listOf(BridgeLaneSave(2, 2.0), BridgeLaneSave(0, 0.0)))))
    }

    @Test fun aSaveFromAHardBandCannotBeRestoredWithAnotherBandsPlankCount() {
        // 6 planks is fine on Big (6 spans) but not on Medium (5)
        val big = valid().copy(band = "big", lanes = listOf(BridgeLaneSave(6, 6.0), BridgeLaneSave(5, 5.0)))
        assertTrue(restoresInto(big))
        assertFalse(restoresInto(big.copy(band = "medium")))
    }
}
