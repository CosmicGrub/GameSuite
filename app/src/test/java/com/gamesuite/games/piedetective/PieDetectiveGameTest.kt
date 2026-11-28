package com.gamesuite.games.piedetective

import com.gamesuite.core.GameResult
import com.gamesuite.core.LaunchPolicy
import com.gamesuite.core.PlayMode
import com.gamesuite.settings.CpuDifficulty
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Game wrapper, resume snapshot, corruption and result rules of sections 2.3 and 3.5 (tests P27-P37). */
class PieDetectiveGameTest {
    @After fun resetPolicy() { LaunchPolicy.resetToFailClosed() }

    private class Rig(val game: PieDetectiveGame, val results: MutableList<GameResult>, val bookSeed: Long)

    private fun rig(humans: Int = 2, bots: Int = 0, band: PieBand = PieBand.MEDIUM, handicap: String = "none", seed: Int = 1, start: Boolean = true): Rig {
        val g = PieDetectiveGame(Random(seed))
        g.band = band; g.handicap = handicap; g.autoFlip = false
        val results = mutableListOf<GameResult>()
        g.init(pieContext(humans, bots))
        g.setOnMatchEnd { results += it }
        if (start) g.startMatch()
        return Rig(g, results, Random(seed).nextLong())
    }

    private fun PieDetectiveGame.ack() { val s = state.value!!; if (s.phase == PiePhase.PASS) passAcknowledged(s.currentSeat) }

    /** Everything a person does in a turn up to the Sneak, then the Sneak. */
    private fun PieDetectiveGame.turn(trait: PieTrait) {
        ack()
        val seat = state.value!!.currentSeat
        assertEquals(PieResult.OK, chooseTrait(seat, trait))
        peekHeld(seat); showEveryone(seat)
        var s = state.value!!
        if (s.phase == PiePhase.FLIP) {
            val c = s.clues.last()
            for (v in s.lineup) if (!v.flipped && PieCast.byId(v.id)!!.has(c.trait) != c.yes) flip(seat, v.id)
        }
        sneakDone()
    }

    private fun PieDetectiveGame.thief(r: Rig, band: PieBand, caseIndex: Int) = PieDealer.deal(r.bookSeed, caseIndex, band).thief

    private fun PieDetectiveGame.catchRight(thief: String) { ack(); val s = state.value!!.currentSeat; startCatch(s); catchSuspect(s, thief) }

    private fun PieDetectiveGame.loseCase(thief: String) {
        repeat(2) {
            ack(); val st = state.value!!
            if (st.phase != PiePhase.CHOOSE) return
            val seat = st.currentSeat
            startCatch(seat)
            catchSuspect(seat, state.value!!.lineup.first { !it.flipped && it.id != thief }.id)
            sneakDone()
        }
    }

    // ---- identity ----

    @Test
    fun theGameIsACooperativeSameScreenKidsPuzzle() {
        val g = PieDetectiveGame()
        assertEquals("pie-detective", g.gameId)
        assertEquals(2, g.minPlayers); assertEquals(4, g.maxPlayers)
        assertEquals(listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes)
        assertFalse(g.isPaused)
    }

    // ---- snapshots ----

    @Test
    fun snapshotIsNullBeforeStartAndAfterBookEnds() {
        val r = rig(start = false)
        assertNull(r.game.snapshot())
        r.game.startMatch()
        assertNotNull(r.game.snapshot())
        // a finished book ends the session: a leave after it is a result and the save is gone
        val g2 = rig(band = PieBand.LITTLE, humans = 0, bots = 2).game
        var guard = 0
        while (g2.state.value!!.phase != PiePhase.BOOK_DONE && guard++ < 2000) {
            when (g2.state.value!!.phase) {
                PiePhase.CASE_WON, PiePhase.CASE_LOST -> g2.nextCase()
                else -> g2.sidekickStep()
            }
        }
        assertEquals(PiePhase.BOOK_DONE, g2.state.value!!.phase)
        assertNull(g2.snapshot())
        g2.leaveSession()
        assertNull(g2.snapshot()) // match over
    }

    @Test
    fun snapshotIsNullInModesThatCannotResume() {
        val g = PieDetectiveGame(Random(1))
        val ctx = pieContext(2, 0).copy(activeMode = PlayMode.ONLINE)
        g.init(ctx); g.startMatch()
        assertNull(g.snapshot())
    }

    @Test
    fun snapshotRoundTripRestoresIdenticalState() {
        val r = rig(humans = 1, bots = 1, band = PieBand.BIG, handicap = "p1", seed = 3)
        r.game.turn(PieTrait.HAT)
        r.game.sidekickStep(); r.game.sidekickStep(); r.game.sidekickStep()
        r.game.ack()
        val before = r.game.state.value!!
        assertEquals(PiePhase.CHOOSE, before.phase)
        val snap = r.game.snapshot()!!
        assertTrue("under 2 KB", snap.length < 2048)
        val fresh = rig(humans = 1, bots = 1, band = PieBand.BIG, seed = 99, start = false)
        assertTrue(fresh.game.restore(snap))
        val after = fresh.game.state.value!!
        assertEquals(before.lineup, after.lineup)
        assertEquals(before.clues, after.clues)
        assertEquals(before.thiefHops, after.thiefHops)
        assertEquals(before.currentSeat, after.currentSeat)
        assertEquals(before.caseIndex, after.caseIndex)
        assertEquals(before.availableTraits, after.availableTraits)
        assertEquals(PieBand.BIG, fresh.game.band)
        assertEquals("p1", fresh.game.handicap)
        assertNull(after.revealedThiefId)
        // the restored case is the same case: the same Sneak card comes next
        assertEquals(snap, fresh.game.snapshot())
    }

    @Test
    fun snapshotOfAHalfPlayedTurnSettlesOnACopyAndLeavesTheLiveGameAlone() {
        val r = rig(humans = 1, bots = 1, seed = 4)
        val g = r.game
        assertEquals(PieResult.OK, g.chooseTrait(0, PieTrait.BAG))
        g.peekHeld(0); g.showEveryone(0)
        val live = g.state.value!!
        assertEquals(PiePhase.FLIP, live.phase)
        val snap = g.snapshot()!!
        assertEquals(PiePhase.FLIP, g.engine.state()!!.phase) // saving did not move the live engine
        val g2 = rig(humans = 1, bots = 1, seed = 4, start = false).game
        assertTrue(g2.restore(snap))
        // restored at a turn boundary: all flips done, Sneak resolved, and the Sidekick is up
        val s = g2.state.value!!
        assertEquals(1, s.currentSeat)
        assertEquals(1, s.clues.size)
    }

    private fun goodSnapshot(): Pair<String, PieSave> {
        val r = rig(humans = 1, bots = 1, band = PieBand.BIG, seed = 5)
        r.game.turn(PieTrait.GLASSES)
        r.game.sidekickStep(); r.game.sidekickStep()
        r.game.sidekickStep()
        val snap = r.game.snapshot()!!
        return snap to PieSaveCodec.decode(snap, pieSeats(1, 1))!!
    }

    private fun restoreInto(save: PieSave, humans: Int = 1, bots: Int = 1, band: PieBand = PieBand.BIG): Boolean {
        val g = rig(humans, bots, band, start = false).game
        val ok = g.restore(PieSaveCodec.encode(save))
        if (!ok) assertNull("a rejected save must leave the game untouched", g.state.value)
        return ok
    }

    @Test
    fun restoreRejectsWrongVersionAndRosterMismatch() {
        val (_, save) = goodSnapshot()
        assertTrue(restoreInto(save))
        assertFalse(restoreInto(save.copy(version = 2)))
        assertFalse(restoreInto(save.copy(version = 0)))
        assertFalse(restoreInto(save, humans = 2, bots = 0)) // different roster
        assertFalse(restoreInto(save, humans = 1, bots = 2))
        assertFalse(restoreInto(save.copy(players = save.players.reversed())))
        assertFalse(restoreInto(save.copy(players = save.players.map { it.copy(isBot = !it.isBot) })))
    }

    @Test
    fun restoreRejectsThiefOutsideLineupOrFlipped() {
        val (_, save) = goodSnapshot()
        assertTrue(save.flipped.isNotEmpty())
        assertFalse(restoreInto(save.copy(thief = "fox")))
        val outside = PieCast.all.map { it.id }.first { it !in save.lineup }
        assertFalse(restoreInto(save.copy(thief = outside)))
        assertFalse(restoreInto(save.copy(flipped = save.flipped + save.thief)))
        assertFalse(restoreInto(save.copy(flipped = listOf("fox"))))
        assertFalse(restoreInto(save.copy(lineup = save.lineup + save.lineup.first())))
        assertFalse(restoreInto(save.copy(lineup = save.lineup.drop(1)))) // wrong size for the band
    }

    @Test
    fun restoreRejectsClueAnswersInconsistentWithThief() {
        val (_, save) = goodSnapshot()
        val flippedAnswer = save.asked.map { if (it.endsWith(":1")) it.dropLast(1) + "0" else it.dropLast(1) + "1" }
        assertFalse(restoreInto(save.copy(asked = flippedAnswer)))
        assertFalse(restoreInto(save.copy(asked = save.asked + save.asked.first()))) // the same trait twice
        assertFalse(restoreInto(save.copy(asked = listOf("hat:7"))))
        assertFalse(restoreInto(save.copy(asked = listOf("wings:1"))))
        assertFalse(restoreInto(save.copy(asked = save.asked + "boots"))) // not "trait:answer"
        // a clue whose exclusions were never flipped
        assertFalse(restoreInto(save.copy(flipped = emptyList())))
        // the little band does not offer boots
        assertFalse(restoreInto(save.copy(band = "little", lineup = save.lineup.take(8), asked = listOf("boots:1"), flipped = emptyList()), band = PieBand.LITTLE))
    }

    @Test
    fun restoreRejectsCorruptNumbersAndGarbage() {
        val (snap, save) = goodSnapshot()
        assertFalse(restoreInto(save.copy(hops = 3)))
        assertFalse(restoreInto(save.copy(hops = -1)))
        assertFalse(restoreInto(save.copy(hops = save.hops + 1)))
        assertFalse(restoreInto(save.copy(sneakIndex = 12)))
        assertFalse(restoreInto(save.copy(sneakIndex = save.sneakIndex + 1)))
        assertFalse(restoreInto(save.copy(currentSeat = 2)))
        assertFalse(restoreInto(save.copy(currentSeat = -1)))
        assertFalse(restoreInto(save.copy(caseIndex = 3)))
        assertFalse(restoreInto(save.copy(caseIndex = -1)))
        assertFalse(restoreInto(save.copy(casesSolved = 1)))
        assertFalse(restoreInto(save.copy(stars = 5)))
        assertFalse(restoreInto(save.copy(turns = -1)))
        assertFalse(restoreInto(save.copy(luckyUsed = listOf(1, 0)))) // no handicap allows it
        assertFalse(restoreInto(save.copy(luckyUsed = listOf(0))))
        assertFalse(restoreInto(save.copy(band = "gigantic")))
        assertFalse(restoreInto(save.copy(difficulty = "IMPOSSIBLE")))
        assertFalse(restoreInto(save.copy(handicap = "p1".repeat(40))))
        val g = rig(1, 1, PieBand.BIG, start = false).game
        for (bad in listOf("", "{", "null", "[]", "{}", "not json", "{\"version\":1}", snap.take(snap.length / 2), snap.replace("\"version\":1", "\"version\":\"x\""), "x".repeat(PieSaveCodec.MAX_TEXT + 1))) {
            assertFalse("rejects '${bad.take(20)}'", g.restore(bad))
            assertNull(g.state.value)
        }
    }

    @Test
    fun restoreFailureLeavesGameUntouched() {
        val r = rig(humans = 1, bots = 1, band = PieBand.BIG, seed = 6)
        r.game.turn(PieTrait.SCARF)
        val before = r.game.state.value
        val snapBefore = r.game.snapshot()
        val bad = PieSaveCodec.decode(goodSnapshot().first, pieSeats(1, 1))!!.copy(thief = "fox")
        assertFalse(r.game.restore(PieSaveCodec.encode(bad)))
        assertFalse(r.game.restore("garbage"))
        assertEquals(before, r.game.state.value)
        assertEquals(snapBefore, r.game.snapshot())
        assertEquals(PieBand.BIG, r.game.band)
    }

    @Test
    fun resumeAfterPeekReturnsToChooseWithTraitFree() {
        val r = rig(humans = 1, bots = 1, seed = 7)
        val g = r.game
        assertEquals(PieResult.OK, g.chooseTrait(0, PieTrait.BOOTS))
        g.peekHeld(0)
        assertEquals(PiePhase.PEEK, g.state.value!!.phase)
        val snap = g.snapshot()!!
        val g2 = rig(humans = 1, bots = 1, seed = 8, start = false).game
        assertTrue(g2.restore(snap))
        val s = g2.state.value!!
        assertEquals(PiePhase.CHOOSE, s.phase)
        assertEquals(0, s.currentSeat)
        assertTrue(PieTrait.BOOTS in s.availableTraits)
        assertTrue(s.clues.isEmpty())
        assertNull(s.pendingAnswer)
    }

    @Test
    fun resumingWithTwoPeopleStartsOnThePassScreen() {
        val r = rig(humans = 2, seed = 9)
        r.game.ack()
        val snap = r.game.snapshot()!!
        val g2 = rig(humans = 2, seed = 10, start = false).game
        assertTrue(g2.restore(snap))
        assertEquals(PiePhase.PASS, g2.state.value!!.phase) // whoever picks the device up sees the pass screen first
    }

    // ---- ending ----

    @Test
    fun leavingBeforeFirstCaseEndsUsesAbortPath() {
        val r = rig()
        r.game.leaveSession()
        assertEquals(1, r.results.size)
        assertTrue(r.results[0].wasAborted)
        assertTrue(r.results[0].scores.isEmpty())
        assertTrue(r.game.matchOver.value)
        r.game.leaveSession() // a second leave does nothing
        assertEquals(1, r.results.size)
        assertEquals(PieResult.NOT_ACTIVE, r.game.chooseTrait(0, PieTrait.HAT))
    }

    @Test
    fun resultFollowsBookWinRule() {
        // left early with one case solved: team WIN, everybody scores the cases solved
        val r = rig(band = PieBand.MEDIUM, seed = 11)
        r.game.catchRight(r.game.thief(r, PieBand.MEDIUM, 0))
        assertEquals(PiePhase.CASE_WON, r.game.state.value!!.phase)
        r.game.leaveSession()
        val res = r.results.single()
        assertFalse(res.wasAborted)
        assertEquals(2, res.scores.size)
        assertTrue(res.scores.all { it.isWinner && it.score == 1 })

        // finished Little book (2 cases): one solved of 2 is half rounded up, a WIN; none solved is not
        val one = rig(band = PieBand.LITTLE, seed = 12)
        one.game.catchRight(one.game.thief(one, PieBand.LITTLE, 0))
        one.game.nextCase()
        one.game.loseCase(one.game.thief(one, PieBand.LITTLE, 1))
        assertEquals(PiePhase.CASE_LOST, one.game.state.value!!.phase)
        one.game.nextCase()
        assertEquals(PiePhase.BOOK_DONE, one.game.state.value!!.phase)
        one.game.leaveSession()
        assertTrue(one.results.single().scores.all { it.isWinner })

        val none = rig(band = PieBand.LITTLE, seed = 13)
        none.game.loseCase(none.game.thief(none, PieBand.LITTLE, 0))
        assertEquals(PiePhase.CASE_LOST, none.game.state.value!!.phase)
        none.game.nextCase()
        none.game.loseCase(none.game.thief(none, PieBand.LITTLE, 1))
        none.game.nextCase()
        none.game.leaveSession()
        val lost = none.results.single()
        assertFalse(lost.wasAborted)
        assertTrue(lost.scores.none { it.isWinner })
        assertTrue(lost.scores.all { it.score == 0 })

        // left early with nothing solved after a lost first case: counts as played but not won
        val early = rig(band = PieBand.MEDIUM, seed = 14)
        early.game.loseCase(early.game.thief(early, PieBand.MEDIUM, 0))
        early.game.leaveSession()
        assertFalse(early.results.single().scores.any { it.isWinner })
    }

    @Test
    fun playAgainStartsANewBookWithANewSeedAndRespectsTheKidsTimeLimit() {
        val r = rig(band = PieBand.LITTLE, seed = 15)
        val first = r.game.state.value!!.lineup
        r.game.playAgain()
        assertNotNull(r.game.state.value)
        assertTrue(r.game.state.value!!.lineup != first || r.game.state.value!!.clues.isEmpty())
        assertEquals(0, r.game.state.value!!.caseIndex)

        // time is up for a kid profile: the restart is refused, the counter ticks, nothing changes
        val before = r.game.state.value
        val ticks = LaunchPolicy.blockedStarts.value
        LaunchPolicy.timeUp = true
        r.game.playAgain()
        assertEquals(before, r.game.state.value)
        assertEquals(ticks + 1, LaunchPolicy.blockedStarts.value)
        LaunchPolicy.timeUp = false
        r.game.playAgain()
        assertEquals(0, r.game.state.value!!.caseIndex)
    }

    @Test
    fun resultAndStatusTextUseCooperativeWordingOnly() {
        val banned = listOf("winner", "loser", "you lose", "you lost", "game over", "wrong")
        for (loc in listOf("values", "values-es")) {
            val text = PieTestFiles.read("res/$loc/pie_detective_strings.xml")
            for ((k, v) in PieTestFiles.strings(text)) {
                if (k == "pie_detective_status_flip_wrong") continue // key name only; the text says "Look again!"
                val low = v.lowercase()
                for (b in banned) assertFalse("$loc $k contains '$b'", low.contains(b))
            }
            if (loc == "values-es") continue
            val s = PieTestFiles.strings(text)
            assertEquals("We did it!", s["pie_detective_status_case_won"])
            assertEquals("Try again together", s["pie_detective_status_case_lost"])
            assertEquals("Look again! That one still matches.", s["pie_detective_status_flip_wrong"])
        }
    }

    @Test
    fun pauseStopsSidekickAutoplayAndResumeContinues() {
        val r = rig(humans = 0, bots = 2, seed = 16)
        val g = r.game
        assertFalse(g.isPaused)
        g.pause()
        assertTrue(g.isPaused)
        assertFalse(g.sidekickStep())
        val frozen = g.state.value
        assertFalse(g.sidekickStep())
        assertEquals(frozen, g.state.value)
        g.resume()
        assertFalse(g.isPaused)
        assertTrue(g.sidekickStep())
    }

    @Test
    fun pausingAPeekThroughTheGameHidesTheAnswerFromTheNextPerson() {
        val r = rig(humans = 2, seed = 17)
        val g = r.game
        g.ack()
        g.chooseTrait(0, PieTrait.HAT); g.peekHeld(0)
        assertNotNull(g.state.value!!.pendingAnswer)
        g.pause()
        assertNull(g.state.value!!.pendingAnswer)
        assertEquals(PiePhase.PASS, g.state.value!!.phase)
        g.resume()
    }

    @Test
    fun hintIsAvailableToTheGameShellOnlyInChoose() {
        val r = rig(humans = 1, bots = 1, seed = 18)
        assertNotNull(r.game.hint())
        r.game.chooseTrait(0, PieTrait.HAT)
        assertNull(r.game.hint())
        val none = rig(start = false).game
        assertNull(none.hint())
    }

    @Test
    fun sidekickLevelsAllFinishABookWithoutErrors() {
        for (d in CpuDifficulty.entries) {
            val g = PieDetectiveGame(Random(20))
            g.difficulty = d; g.band = PieBand.BIG
            g.init(pieContext(0, 2)); g.startMatch()
            var guard = 0
            while (g.state.value!!.phase != PiePhase.BOOK_DONE && guard++ < 3000) {
                when (g.state.value!!.phase) { PiePhase.CASE_WON, PiePhase.CASE_LOST -> g.nextCase(); else -> g.sidekickStep() }
            }
            assertEquals(PiePhase.BOOK_DONE, g.state.value!!.phase)
        }
    }
}
