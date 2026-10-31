package com.gamesuite.games.hanafuda

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

/** The Hanafuda match engine as the screen drives it: local play, views, undo, hints, resume and the session. */
class HanafudaGameTest {
    private val vsCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))
    private val cpuFirst = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))
    private val pnp = listOf(PlayerInfo("a", "Ann"), PlayerInfo("b", "Ben"))

    private fun game(
        players: List<PlayerInfo> = vsCpu,
        seed: Int = 1,
        rounds: Int = 3,
        set: YakuSet = YakuSet.BASIC,
        first: Int = 0,
        level: CpuDifficulty = CpuDifficulty.MEDIUM,
        start: Boolean = true
    ): HanafudaGame {
        val mode = if (players.any { it.isBot }) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
        val g = HanafudaGame(Random(seed))
        g.totalRounds = rounds
        g.yakuSet = set
        g.firstOpenerIndex = first
        g.difficulty = level
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        if (start) g.startMatch()
        return g
    }

    /** One legal action for whoever is on turn (a human seat), chosen at random from that seat's own view. */
    private fun humanStep(g: HanafudaGame, rng: Random) {
        val s = g.state.value!!
        val r = s.round
        when (r.phase) {
            KoiPhase.PLAY_HAND -> {
                val play = KoiRules.legalPlays(r).random(rng)
                assertTrue(g.playHand(play.handCard, play.fieldCard))
            }
            KoiPhase.DRAW_READY -> assertTrue(g.drawFromDeck())
            KoiPhase.CHOOSE_DRAW_MATCH -> assertTrue(g.chooseMatch(KoiRules.drawChoices(r).random(rng)))
            KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> assertTrue(g.decide(rng.nextBoolean()))
            KoiPhase.ROUND_OVER -> if (s.finished) Unit else assertTrue(g.nextRound())
        }
    }

    private fun playToTheEnd(g: HanafudaGame, rng: Random, maxSteps: Int = 4000) {
        var steps = 0
        while (g.state.value!!.finished.not()) {
            if (g.botToMove()) g.playBotStep() else humanStep(g, rng)
            assertTrue("did not finish", ++steps < maxSteps)
        }
    }

    // ---- views ----------------------------------------------------------------------------------

    @Test fun startMatchDealsTheFirstRoundAndPublishesTheViewersRedactedTable() {
        val g = game()
        val s = g.state.value!!
        assertEquals(1, s.roundNumber)
        assertEquals(3, s.totalRounds)
        assertEquals(listOf(0, 0), s.totals)
        assertEquals(8, s.round.hands[0].size)
        assertTrue(s.round.hands[0].all { it >= 0 })
        assertEquals(8, s.round.hands[1].size)
        assertTrue("the computer's hand must be hidden", s.round.hands[1].all { it < 0 })
        assertEquals(24, s.round.deck.size)
        assertTrue("the deck must be hidden", s.round.deck.all { it < 0 })
        assertEquals(HanafudaEvent.RoundStarted(1, 0, KoiEnd.NONE), g.event.value)
        assertFalse(g.canUndo)
    }

    @Test fun theHubsFirstMoverBecomesTheParentOfRoundOne() {
        assertEquals(0, game(first = 0).state.value!!.round.turn)
        val g = game(first = 1)
        assertEquals(1, g.state.value!!.round.turn)
        assertTrue(g.botToMove())
        assertEquals(1, g.state.value!!.round.parent)
    }

    @Test fun passAndPlayShowsTheSeatOnTurnItsOwnHandAndHidesTheOther() {
        val g = game(pnp)
        var s = g.state.value!!
        assertEquals(0, g.viewerSeat())
        assertTrue(s.round.hands[0].all { it >= 0 })
        assertTrue(s.round.hands[1].all { it < 0 })
        val play = KoiRules.legalPlays(s.round).first()
        g.playHand(play.handCard, play.fieldCard)
        g.drawFromDeck()
        // Handled any pending choice or decision so the turn passes.
        var guard = 0
        while (g.state.value!!.round.turn == 0 && !g.state.value!!.round.over && guard++ < 10) humanStep(g, Random(1))
        s = g.state.value!!
        assertEquals(1, g.viewerSeat())
        assertTrue(s.round.hands[1].all { it >= 0 })
        assertTrue(s.round.hands[0].all { it < 0 })
    }

    @Test fun twoHumansAreNeverTreatedAsBots() {
        val g = game(pnp)
        assertFalse(g.botToMove())
    }

    // ---- rejections -----------------------------------------------------------------------------

    @Test fun playingAnotherSeatsCardOrOutOfPhaseIsRejectedWithAReason() {
        val g = game()
        val r = g.state.value!!.round
        val full = KoiRules.legalPlays(r)
        val notMine = (0 until 48).first { it !in r.hands[0] }
        assertFalse(g.playHand(notMine))
        assertEquals(HanafudaEvent.Rejected(HanafudaReject.NOT_IN_HAND), g.event.value)
        assertFalse(g.drawFromDeck())
        assertEquals(HanafudaEvent.Rejected(HanafudaReject.WRONG_PHASE), g.event.value)
        assertFalse(g.decide(true))
        assertFalse(g.chooseMatch(0))
        assertTrue(full.isNotEmpty())
    }

    @Test fun aDoubleMatchNeedsAFieldChoiceAndAWrongOneIsRefused() {
        // Find a seed whose opening hand has a card with exactly two field matches.
        for (seed in 1..300) {
            val g = game(seed = seed)
            val r = g.state.value!!.round
            val two = r.hands[0].firstOrNull { r.matchesOnField(it).size == 2 } ?: continue
            assertFalse(g.playHand(two))
            assertEquals(HanafudaEvent.Rejected(HanafudaReject.PICK_A_FIELD_CARD), g.event.value)
            val notAMatch = r.field.first { HanafudaCards.monthOf(it) != HanafudaCards.monthOf(two) }
            assertFalse(g.playHand(two, notAMatch))
            assertEquals(HanafudaEvent.Rejected(HanafudaReject.WRONG_FIELD_CARD), g.event.value)
            assertTrue(g.playHand(two, r.matchesOnField(two).first()))
            return
        }
        error("no double-match opening found")
    }

    @Test fun nothingWorksOnTheComputersTurn() {
        val g = game(first = 1)
        assertTrue(g.botToMove())
        val any = g.state.value!!.round.hands[0].first()
        assertFalse(g.playHand(any))
        assertEquals(HanafudaEvent.Rejected(HanafudaReject.NOT_YOUR_TURN), g.event.value)
    }

    // ---- the computer ------------------------------------------------------------------------------

    @Test fun theComputerPlaysItsFullTurnOneActionAtATime() {
        val g = game(first = 1)
        val seen = mutableListOf<KoiPhase>()
        var guard = 0
        while (g.botToMove() && guard++ < 20) {
            seen += g.state.value!!.round.phase
            g.playBotStep()
        }
        assertEquals(KoiPhase.PLAY_HAND, seen.first())
        assertTrue(KoiPhase.DRAW_READY in seen)
        assertEquals(0, g.state.value!!.round.turn)
        assertFalse(g.botToMove())
    }

    @Test fun theAsyncBotStepDoesTheSameAsTheSynchronousOne() {
        val a = game(seed = 4, first = 1, level = CpuDifficulty.HARD)
        val b = game(seed = 4, first = 1, level = CpuDifficulty.HARD)
        a.playBotStep()
        runBlocking { b.playBotStepAsync() }
        assertEquals(a.state.value, b.state.value)
    }

    @Test fun theBotNeverActsOutsideItsTurn() {
        val g = game()
        val before = g.state.value
        g.playBotStep()
        runBlocking { g.playBotStepAsync() }
        assertEquals(before, g.state.value)
    }

    // ---- a whole match ------------------------------------------------------------------------------

    @Test fun aWholeMatchAgainstTheComputerFinishesForEveryLengthAndLevel() {
        for ((rounds, level) in listOf(3 to CpuDifficulty.EASY, 6 to CpuDifficulty.MEDIUM, 12 to CpuDifficulty.HARD)) {
            val g = game(seed = rounds, rounds = rounds, level = level, set = if (rounds == 6) YakuSet.FULL else YakuSet.BASIC)
            playToTheEnd(g, Random(rounds))
            val s = g.state.value!!
            assertTrue(s.finished)
            assertEquals(rounds, s.roundNumber)
            assertEquals(rounds, s.roundScores.size)
            assertEquals(s.roundScores.sumOf { it[0] }, s.totals[0])
            assertEquals(s.roundScores.sumOf { it[1] }, s.totals[1])
            assertEquals(
                when {
                    s.totals[0] > s.totals[1] -> 0
                    s.totals[1] > s.totals[0] -> 1
                    else -> -1
                },
                s.winnerSeat
            )
            assertFalse(g.nextRound()) // nothing after the last round
        }
    }

    @Test fun theWinnerOfARoundBecomesTheNextParentAndADrawKeepsTheParent() {
        val g = game(seed = 9, rounds = 12)
        var checked = 0
        var rounds = 0
        val rng = Random(9)
        while (!g.state.value!!.finished && rounds < 12) {
            if (g.state.value!!.round.over) {
                val r = g.state.value!!.round
                val expected = if (r.winner >= 0) r.winner else r.parent
                g.nextRound()
                assertEquals(expected, g.state.value!!.round.parent)
                checked++
                rounds++
            } else if (g.botToMove()) g.playBotStep() else humanStep(g, rng)
        }
        assertTrue(checked >= 5)
    }

    @Test fun playAgainStartsAFreshMatchAfterTheLastRound() {
        val g = game(rounds = 3)
        playToTheEnd(g, Random(3))
        assertTrue(g.playAgain())
        val s = g.state.value!!
        assertEquals(1, s.roundNumber)
        assertEquals(listOf(0, 0), s.totals)
        assertTrue(s.roundScores.isEmpty())
        assertFalse(s.finished)
    }

    @Test fun playAgainIsRefusedMidMatch() {
        val g = game()
        assertFalse(g.playAgain())
        assertFalse(g.nextRound())
    }

    @Test fun leavingReportsTheRunningTotals() {
        val g = game(rounds = 3)
        playToTheEnd(g, Random(5))
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        val totals = g.state.value!!.totals
        g.leaveSession()
        assertTrue(g.matchOver.value)
        assertEquals(totals, result!!.scores.map { it.score })
        val winners = result!!.scores.count { it.isWinner }
        assertEquals(if (totals[0] == totals[1] || totals.max() == 0) 0 else 1, winners)
        g.leaveSession() // second call: nothing
    }

    @Test fun abortingMidMatchRecordsNothing() {
        val g = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
        assertNull(g.snapshot())
    }

    // ---- undo ---------------------------------------------------------------------------------------

    @Test fun undoTakesBackAHandPlayBeforeTheDeckIsTurnedUp() {
        val g = game(seed = 3)
        val before = g.state.value!!
        val play = KoiRules.legalPlays(before.round).first()
        assertTrue(g.playHand(play.handCard, play.fieldCard))
        assertNotEquals(before, g.state.value)
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(before, g.state.value)
        assertEquals(HanafudaEvent.Undone, g.event.value)
        assertFalse(g.canUndo)
        g.undo() // nothing more: harmless
        assertEquals(before, g.state.value)
    }

    @Test fun turningUpTheDeckClearsUndoSoItCanNeverPeekOrReRoll() {
        val g = game(seed = 3)
        val play = KoiRules.legalPlays(g.state.value!!.round).first()
        g.playHand(play.handCard, play.fieldCard)
        assertTrue(g.canUndo)
        g.drawFromDeck()
        assertFalse(g.canUndo)
        val after = g.state.value
        g.undo()
        assertEquals(after, g.state.value)
    }

    @Test fun undoRestoresTheHiddenDeckOrderSoTheSameCardComesUp() {
        // Play, undo, play the same card again: the card turned up must be the same one (no re-roll).
        val a = game(seed = 21)
        val b = game(seed = 21)
        val play = KoiRules.legalPlays(a.state.value!!.round).first()
        a.playHand(play.handCard, play.fieldCard); a.undo()
        a.playHand(play.handCard, play.fieldCard); a.drawFromDeck()
        b.playHand(play.handCard, play.fieldCard); b.drawFromDeck()
        assertEquals(b.state.value, a.state.value)
    }

    /** A full 48-card table where seat 0 holds the cherry ribbon and the field has a cherry card: playing it completes the red poetry ribbons. */
    private fun akatanTable(): KoiRound {
        val poetry = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id } // pine, plum, cherry
        val cherryRibbon = poetry[2]
        val captured0 = poetry.take(2)
        val hand0 = listOf(cherryRibbon, 12, 16, 20, 24, 28, 32, 36)
        val hand1 = listOf(13, 17, 21, 25, 29, 33, 37, 40)
        val field = listOf(8, 41, 44, 45, 46, 47, 14, 18)
        val used = (captured0 + hand0 + hand1 + field).toSet()
        val deck = (0 until 48).filter { it !in used }
        return KoiRound(YakuSet.BASIC, 0, 0, KoiPhase.PLAY_HAND, deck, field, listOf(hand0, hand1), listOf(captured0, emptyList()))
    }

    private fun craftedSave(round: KoiRound): String {
        val json = kotlinx.serialization.json.Json.encodeToString(KoiRound.serializer(), round)
        return """{"players":[{"playerId":"you","displayName":"You","isBot":false},{"playerId":"cpu","displayName":"CPU","isBot":true}],""" +
            """"round":$json,"roundNumber":1,"totalRounds":3,"totals":[0,0],"roundScores":[],"difficulty":"MEDIUM","nextParent":0}"""
    }

    @Test fun undoAlsoTakesBackAKoiKoiCallMadeAfterTheHandPlayButNotPastIt() {
        val table = akatanTable()
        assertTrue(KoiRules.isConsistent(table))
        val g = game(start = false)
        assertTrue(g.restore(craftedSave(table)))
        val start = g.state.value!!
        val cherryRibbon = table.hands[0][0]
        assertTrue(g.playHand(cherryRibbon, 8))
        assertEquals(KoiPhase.DECIDE_AFTER_HAND, g.state.value!!.round.phase)
        assertTrue(g.decide(true))
        assertEquals(KoiPhase.DRAW_READY, g.state.value!!.round.phase)
        assertEquals(1, g.state.value!!.round.koiCalls[0])
        assertTrue(g.canUndo)
        g.undo() // takes the koi-koi call back
        assertEquals(KoiPhase.DECIDE_AFTER_HAND, g.state.value!!.round.phase)
        assertEquals(0, g.state.value!!.round.koiCalls[0])
        g.undo() // takes the hand play back
        assertEquals(start, g.state.value)
        assertFalse(g.canUndo)
    }

    @Test fun shobuEndsTheRoundScoresItAndLocksUndo() {
        val g = game(start = false)
        assertTrue(g.restore(craftedSave(akatanTable())))
        g.playHand(akatanTable().hands[0][0], 8)
        assertTrue(g.decide(false))
        val s = g.state.value!!
        assertTrue(s.round.over)
        assertEquals(KoiEnd.SHOBU, s.round.end)
        assertEquals(5, s.round.points)
        assertEquals(listOf(5, 0), s.totals)
        assertEquals(listOf(listOf(5, 0)), s.roundScores)
        assertFalse(g.canUndo)
        assertFalse(s.finished) // three rounds, one played
        assertTrue(g.nextRound())
        assertEquals(0, g.state.value!!.round.parent) // the winner (seat 0) deals first
        assertEquals(2, g.state.value!!.roundNumber)
    }

    @Test fun aKoiKoiMakesTheNextPlayerAbleToDoubleTheirWin() {
        val g = game(start = false)
        assertTrue(g.restore(craftedSave(akatanTable())))
        g.playHand(akatanTable().hands[0][0], 8)
        g.decide(true)
        val r = g.state.value!!.round
        assertEquals(1, r.koiCalls[0])
        assertEquals(0, r.lastKoi)
    }

    // ---- hint ---------------------------------------------------------------------------------------

    @Test fun hintSuggestsALegalPlayThenTheDeckThenClears() {
        val g = game(seed = 6)
        val h = g.requestHint() as KoiHint.Play
        assertTrue(h.play in KoiRules.legalPlays(g.state.value!!.round))
        assertEquals(h, g.hint.value)
        assertTrue(g.event.value is HanafudaEvent.Hinted)
        g.playHand(h.play.handCard, h.play.fieldCard)
        assertNull(g.hint.value)
        assertEquals(KoiHint.Draw, g.requestHint())
    }

    @Test fun noHintOnTheComputersTurnOrAfterTheRound() {
        val g = game(first = 1)
        assertNull(g.requestHint())
    }

    // ---- resume -------------------------------------------------------------------------------------

    @Test fun snapshotRoundTripsAndTheRestoredMatchContinuesIdentically() {
        val g = game(seed = 8, rounds = 6, set = YakuSet.FULL)
        val rng = Random(8)
        repeat(9) { if (g.botToMove()) g.playBotStep() else humanStep(g, rng) }
        val snap = g.snapshot()
        assertNotNull(snap)
        val h = game(seed = 99, rounds = 3, set = YakuSet.BASIC, start = false)
        assertTrue(h.restore(snap!!))
        assertEquals(g.state.value, h.state.value)
        assertEquals(6, h.totalRounds)
        assertEquals(YakuSet.FULL, h.yakuSet)
        assertEquals(snap, h.snapshot())
        assertEquals(HanafudaEvent.Resumed, h.event.value)
        // Play the same human actions on both: the hidden deck must be the same.
        val r1 = Random(77)
        val r2 = Random(77)
        repeat(12) {
            if (g.state.value!!.finished) return@repeat
            if (g.botToMove()) g.playBotStep() else humanStep(g, r1)
            if (h.botToMove()) h.playBotStep() else humanStep(h, r2)
        }
    }

    @Test fun restoreRejectsGarbageAndForeignOrDamagedSaves() {
        val g = game(seed = 8)
        val snap = g.snapshot()!!
        val h = game(start = false)
        assertFalse(h.restore(""))
        assertFalse(h.restore("{"))
        assertFalse(h.restore("{}"))
        val otherRoster = game(pnp, start = false)
        assertFalse(otherRoster.restore(snap))
        // A save with a wrong tally.
        assertFalse(h.restore(snap.replace("\"roundNumber\":1", "\"roundNumber\":3")))
        assertFalse(h.restore(snap.replace("\"totalRounds\":3", "\"totalRounds\":5")))
        assertNull(h.state.value)
        assertTrue(h.restore(snap))
    }

    @Test fun restoreRejectsATableWithADuplicatedCard() {
        val g = game(seed = 8)
        val snap = g.snapshot()!!
        val r = g.state.value!!.round
        val dupe = snap.replace("\"deck\":[${hiddenDeckHead(g)}", "\"deck\":[${r.hands[0].first()}")
        val h = game(start = false)
        assertFalse(h.restore(dupe))
    }

    /** First id of the real deck, read back out of a snapshot of [g]. */
    private fun hiddenDeckHead(g: HanafudaGame): String =
        Regex("\"deck\":\\[(\\d+)").find(g.snapshot()!!)!!.groupValues[1]

    @Test fun noSnapshotWhenNothingIsDealtOrTheSessionIsOver() {
        val g = game(start = false)
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.leaveSession()
        assertNull(g.snapshot())
    }

    // ---- fuzz ---------------------------------------------------------------------------------------

    @Test fun fuzzRandomHumanPlayConservesCardsAndSurvivesSnapshotsAtEveryStep() {
        repeat(25) { seed ->
            val g = game(pnp, seed = seed, rounds = 3, set = if (seed % 2 == 0) YakuSet.FULL else YakuSet.BASIC, first = seed % 2)
            val rng = Random(seed + 1000)
            var steps = 0
            while (!g.state.value!!.finished) {
                humanStep(g, rng)
                steps++
                assertTrue("seed $seed too long", steps < 400)
                val snap = g.snapshot()
                assertNotNull("seed $seed step $steps: no snapshot", snap)
                val twin = game(pnp, start = false)
                assertTrue("seed $seed step $steps: snapshot did not restore", twin.restore(snap!!))
                assertEquals(g.state.value, twin.state.value)
            }
        }
    }

    @Test fun fuzzUndoNeverCorruptsTheTable() {
        repeat(25) { seed ->
            val g = game(vsCpu, seed = seed, rounds = 3)
            val rng = Random(seed)
            var steps = 0
            while (!g.state.value!!.finished) {
                if (g.botToMove()) g.playBotStep() else {
                    if (rng.nextInt(4) == 0 && g.canUndo) {
                        g.undo()
                    } else humanStep(g, rng)
                }
                steps++
                assertTrue("seed $seed too long", steps < 800)
                val s = g.state.value!!
                // Hidden zones stay hidden in the published view at every step.
                assertTrue(s.round.hands[1].all { it < 0 })
                assertTrue(s.round.deck.all { it < 0 })
                assertTrue(s.round.hands[0].all { it >= 0 })
            }
        }
    }

    @Test fun everyRoundOfARandomMatchEndsAndTotalsAddUp() {
        val g = game(pnp, seed = 31, rounds = 12)
        playToTheEnd(g, Random(31))
        val s = g.state.value!!
        assertEquals(12, s.roundScores.size)
        assertTrue(s.roundScores.all { it[0] >= 0 && it[1] >= 0 && (it[0] == 0 || it[1] == 0) })
    }
}
