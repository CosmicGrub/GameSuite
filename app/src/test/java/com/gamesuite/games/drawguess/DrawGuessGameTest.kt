package com.gamesuite.games.drawguess

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private class DgTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

private class DgClock(var now: Long = 1_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}

private fun dgContext(humans: Int, bots: Int = 0, teams: List<Int>? = null, prefix: String = "p"): GameContext {
    val players = List(humans) { PlayerInfo("$prefix${it + 1}", "Player ${it + 1}", teamId = teams?.get(it) ?: -1) } +
        List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
    return GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, players, 0, DgTransport())
}

private class Rig(
    humans: Int = 3, bots: Int = 0, teams: List<Int>? = null, cfg: DrawGuessConfig = DrawGuessConfig(), seed: Int = 1, start: Boolean = true
) {
    val clock = DgClock()
    val ctx = dgContext(humans, bots, teams)
    val g = DrawGuessGame(Random(seed), clock).also { it.config = cfg; it.init(ctx) }
    init { if (start) g.startMatch() }
    val s: DrawState get() = g.state.value!!
    val word: String get() = s.wordKey!!
    fun pick(index: Int = 0) { assertTrue(g.chooseWord(index)) }
    fun draw(index: Int = 0) { pick(index); assertTrue(g.beginDrawing()) }
    /** A wrong card for the current word. */
    fun wrongCard(): String = s.options.first { it != s.wordKey && it !in s.eliminated }
    fun finishTurnUnsolved() { draw(); assertTrue(g.giveUp()); g.nextTurn() }
}

class DrawGuessGameTest {
    // ---- turns, rounds, seats ----

    @Test fun everyoneDrawsOncePerRoundInSeatOrder() {
        val r = Rig(3, cfg = DrawGuessConfig(rounds = 2))
        assertEquals(6, r.s.totalTurns)
        val drawers = ArrayList<Int>(); val words = ArrayList<String>()
        while (r.s.phase != DrawPhase.OVER) {
            assertEquals(DrawPhase.CHOOSE, r.s.phase)
            drawers += r.s.drawerSeat
            r.draw(); words += r.word
            r.g.giveUp(); r.g.nextTurn()
        }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), drawers)
        assertEquals("a word is never drawn twice in a game", words.size, words.toSet().size)
    }

    @Test fun computersNeverTakeASeat() {
        val r = Rig(3, bots = 1)
        assertEquals(listOf(0, 1, 2), r.g.order)
        assertEquals(3, r.s.totalTurns)
        assertEquals(4, r.s.scores.size)
        assertFalse(r.g.teamMode)
        assertEquals(listOf(1, 2), r.g.eligibleGuessers(0))
    }

    @Test fun roundsAreClampedByTheLaunchChoices() {
        assertEquals(1, DrawGuessConfig.fromExtras(mapOf("rounds" to "0"), WordLang.EN).rounds)
        assertEquals(4, DrawGuessConfig.fromExtras(mapOf("rounds" to "99"), WordLang.EN).rounds)
        assertEquals(60, DrawGuessConfig.fromExtras(mapOf("timer" to "45"), WordLang.EN).timerSeconds)
        assertEquals(0, DrawGuessConfig.fromExtras(mapOf("timer" to "0"), WordLang.EN).timerSeconds)
        assertEquals(90, DrawGuessConfig.fromExtras(mapOf("timer" to "90"), WordLang.ES).timerSeconds)
        assertTrue(DrawGuessConfig.fromExtras(mapOf("guess" to "type"), WordLang.EN).typeIt)
        assertFalse(DrawGuessConfig.fromExtras(mapOf("guess" to "pick"), WordLang.EN).typeIt)
        assertFalse(DrawGuessConfig.fromExtras(emptyMap(), WordLang.EN).typeIt)
        assertEquals(WordLang.ES, DrawGuessConfig.fromExtras(emptyMap(), WordLang.ES).lang)
    }

    @Test fun aSingleHumanCannotStartAGame() {
        val r = Rig(1, start = true)
        assertNull(r.g.state.value)
    }

    // ---- phase gating ----

    @Test fun actionsOutOfPhaseDoNothing() {
        val r = Rig()
        assertFalse(r.g.beginDrawing())
        assertFalse(r.g.buzz(1))
        assertFalse(r.g.guessCard("cat"))
        assertFalse(r.g.giveUp())
        assertFalse(r.g.nextTurn())
        assertFalse(r.g.chooseWord(3))
        assertFalse(r.g.chooseWord(-1))
        r.pick()
        assertFalse("cannot pick twice", r.g.chooseWord(1))
        assertFalse("cannot draw before starting", r.g.beginStroke(0, 1, false, 0.1f, 0.1f))
        assertTrue(r.g.beginDrawing())
        assertFalse(r.g.beginDrawing())
    }

    @Test fun theDrawerSeesThreeWordsEasyFirstAndTheCardsContainTheChosenOne() {
        val r = Rig()
        assertEquals(3, r.s.offered.size)
        assertEquals(listOf(1, 2, 3), r.s.offered.map { DrawGuessWordBank.byKey(it)!!.difficulty })
        r.pick(2)
        assertEquals(r.s.offered[2], r.word)
        assertEquals(6, r.s.options.size)
        assertTrue(r.word in r.s.options)
        assertEquals(DrawPhase.READY, r.s.phase)
    }

    // ---- drawing ----

    @Test fun strokesGoOnTheBoardOnlyWhileDrawing() {
        val r = Rig()
        r.draw()
        val v0 = r.g.boardVersion.value
        assertTrue(r.g.beginStroke(1, 2, false, 0.1f, 0.1f))
        assertTrue(r.g.extendStroke(0.4f, 0.3f))
        r.g.endStroke()
        assertEquals(1, r.g.board.strokes.size)
        assertTrue(r.g.boardVersion.value > v0)
        assertTrue(r.g.undoStroke())
        assertTrue(r.g.board.strokes.isEmpty())
        r.g.beginStroke(0, 1, false, 0.2f, 0.2f); r.g.extendStroke(0.5f, 0.5f); r.g.endStroke()
        r.g.clearBoard()
        assertTrue(r.g.board.strokes.isEmpty())
        assertTrue(r.g.buzz(1))
        assertFalse("no drawing while someone guesses", r.g.beginStroke(0, 1, false, 0.1f, 0.1f))
    }

    @Test fun aNewTurnStartsOnBlankPaper() {
        val r = Rig()
        r.draw()
        r.g.beginStroke(0, 1, false, 0.1f, 0.1f); r.g.extendStroke(0.5f, 0.5f); r.g.endStroke()
        r.g.giveUp()
        assertEquals("the finished drawing stays up for the reveal", 1, r.g.board.strokes.size)
        r.g.nextTurn()
        assertTrue(r.g.board.strokes.isEmpty())
    }

    @Test fun buzzingEndsTheStrokeInProgress() {
        val r = Rig()
        r.draw()
        r.g.beginStroke(0, 1, false, 0.1f, 0.1f); r.g.extendStroke(0.5f, 0.5f)
        assertTrue(r.g.buzz(1))
        assertEquals(1, r.g.board.strokes.size)
        assertFalse(r.g.board.drawing)
    }

    // ---- buzzing, the clock ----

    @Test fun onlyAnotherPlayerCanBuzzAndTheClockStopsWhileTheyGuess() {
        val r = Rig()
        r.draw()
        assertFalse("the drawer cannot buzz", r.g.buzz(0))
        r.clock.advance(5_000)
        assertTrue(r.g.buzz(1))
        assertEquals(DrawPhase.GUESS, r.s.phase); assertEquals(1, r.s.guesserSeat)
        assertFalse(r.g.clockRunning.value)
        r.clock.advance(20_000)
        assertEquals(5_000L, r.g.elapsedMs())
        assertTrue(r.g.cancelGuess())
        assertEquals(DrawPhase.DRAW, r.s.phase); assertEquals(-1, r.s.guesserSeat)
        assertTrue(r.s.lockedOut.isEmpty())
        r.clock.advance(1_000)
        assertEquals(6_000L, r.g.elapsedMs())
        assertFalse("a second buzz while someone guesses", Rig().also { it.draw(); it.g.buzz(1) }.g.buzz(2))
    }

    @Test fun theTimeIsUpExactlyAtTheLimit() {
        val r = Rig(cfg = DrawGuessConfig(timerSeconds = 30))
        r.draw()
        assertEquals(30_000L, r.g.remainingMs())
        r.clock.advance(29_999)
        assertFalse(r.g.tick())
        assertEquals(1L, r.g.remainingMs())
        r.clock.advance(1)
        assertTrue(r.g.tick())
        val res = r.s.result!!
        assertEquals(DrawPhase.REVEAL, r.s.phase)
        assertTrue(res.timedOut); assertFalse(res.solved)
        assertEquals(List(3) { 0 }, r.s.scores)
        assertFalse(r.g.clockRunning.value)
    }

    @Test fun noTimerMeansNoTimeout() {
        val r = Rig(cfg = DrawGuessConfig(timerSeconds = 0))
        r.draw()
        assertNull(r.g.remainingMs())
        r.clock.advance(3_600_000)
        assertFalse(r.g.tick())
        assertEquals(DrawPhase.DRAW, r.s.phase)
    }

    @Test fun theClockDoesNotRunWhilePausedAndTheTimeIsNotLost() {
        val r = Rig()
        r.draw()
        r.clock.advance(10_000)
        r.g.pause()
        assertFalse(r.g.clockRunning.value)
        r.clock.advance(100_000)
        assertEquals(10_000L, r.g.elapsedMs())
        assertFalse("a stopped clock cannot time out", r.g.tick())
        r.g.pause() // twice is harmless
        r.g.resume()
        assertTrue(r.g.clockRunning.value)
        r.clock.advance(2_000)
        assertEquals(12_000L, r.g.elapsedMs())
    }

    @Test fun pauseAndResumeDoNothingOutsideDrawing() {
        val r = Rig()
        r.g.pause(); r.g.resume()
        r.draw()
        r.g.buzz(1)
        r.g.pause(); r.g.resume()
        assertEquals(DrawPhase.GUESS, r.s.phase)
        assertFalse(r.g.clockRunning.value)
    }

    // ---- scoring ----

    @Test fun theSpeedBonusFallsFromTenToZero() {
        assertEquals(10, DrawGuessScoring.speedBonus(0, 60_000))
        assertEquals(5, DrawGuessScoring.speedBonus(30_000, 60_000))
        assertEquals(0, DrawGuessScoring.speedBonus(60_000, 60_000))
        assertEquals(0, DrawGuessScoring.speedBonus(90_000, 60_000))
        assertEquals(10, DrawGuessScoring.speedBonus(-5, 60_000))
        assertEquals(5, DrawGuessScoring.speedBonus(45_000, 0))
        assertEquals(0, DrawGuessScoring.speedBonus(200_000, 0))
        var last = 11
        for (t in 0..60_000 step 3_000) { val b = DrawGuessScoring.speedBonus(t.toLong(), 60_000); assertTrue(b <= last); last = b }
    }

    @Test fun pointsRiseWithDifficultyAndTyping() {
        assertEquals(10, DrawGuessScoring.guesserPoints(1, 0, false))
        assertEquals(14, DrawGuessScoring.guesserPoints(3, 0, false))
        assertEquals(17, DrawGuessScoring.guesserPoints(3, 0, true))
        assertEquals(20, DrawGuessScoring.guesserPoints(1, 10, false))
        assertEquals(5, DrawGuessScoring.drawerPoints(1, 0))
        assertEquals(12, DrawGuessScoring.drawerPoints(3, 10) + 0)
        assertTrue(DrawGuessScoring.guesserPoints(1, 10, false) > DrawGuessScoring.drawerPoints(1, 10))
    }

    @Test fun aRightGuessScoresTheGuesserAndTheDrawer() {
        val r = Rig()
        r.draw()
        r.clock.advance(15_000)
        r.g.buzz(1)
        r.clock.advance(60_000) // thinking time while the clock is stopped is free
        val word = DrawGuessWordBank.byKey(r.word)!!
        assertTrue(r.g.guessCard(r.word))
        val bonus = DrawGuessScoring.speedBonus(15_000, 60_000)
        assertEquals(8, bonus)
        val res = r.s.result!!
        assertTrue(res.solved); assertEquals(1, res.guesserSeat)
        assertEquals(DrawGuessScoring.guesserPoints(word.difficulty, bonus, false), res.guesserPoints)
        assertEquals(DrawGuessScoring.drawerPoints(word.difficulty, bonus), res.drawerPoints)
        assertEquals(18, r.s.scores[1]); assertEquals(9, r.s.scores[0]); assertEquals(0, r.s.scores[2])
        assertEquals(DrawPhase.REVEAL, r.s.phase)
    }

    @Test fun fasterGuessesScoreMore() {
        fun scoreAt(ms: Long): Int { val r = Rig(); r.draw(); r.clock.advance(ms); r.g.buzz(1); r.g.guessCard(r.word); return r.s.scores[1] }
        assertTrue(scoreAt(5_000) > scoreAt(50_000))
        assertTrue(scoreAt(50_000) >= scoreAt(59_000))
    }

    @Test fun aHarderWordIsWorthMore() {
        fun scoreFor(index: Int): Int { val r = Rig(); r.draw(index); r.clock.advance(10_000); r.g.buzz(1); r.g.guessCard(r.word); return r.s.scores[1] }
        assertEquals(scoreFor(0) + 4, scoreFor(2))
    }

    @Test fun typingEarnsABonusAndAcceptsEitherLanguage() {
        val r = Rig(cfg = DrawGuessConfig(typeIt = true))
        r.draw()
        r.clock.advance(15_000)
        r.g.buzz(2)
        val w = DrawGuessWordBank.byKey(r.word)!!
        assertTrue(r.g.guessTyped("  " + w.es.uppercase() + " "))
        assertEquals(DrawGuessScoring.guesserPoints(w.difficulty, 8, true), r.s.scores[2])
    }

    // ---- wrong guesses ----

    @Test fun aWrongCardSitsThePlayerOutAndCrossesTheCardOutForEveryone() {
        val r = Rig(4)
        r.draw()
        r.g.buzz(1)
        val wrong = r.wrongCard()
        assertFalse(r.g.guessCard(wrong))
        assertEquals(DrawPhase.DRAW, r.s.phase)
        assertEquals(listOf(1), r.s.lockedOut)
        assertEquals(listOf(wrong), r.s.eliminated)
        assertTrue(r.g.clockRunning.value)
        assertFalse("a locked-out player cannot buzz", r.g.buzz(1))
        assertEquals(listOf(2, 3), r.g.activeGuessers())
        assertTrue(r.g.buzz(2))
        assertFalse("a crossed-out card does nothing", r.g.guessCard(wrong))
        assertEquals(DrawPhase.GUESS, r.s.phase)
        assertEquals(listOf(1), r.s.lockedOut)
        assertFalse(r.g.guessCard("not a card key"))
    }

    @Test fun whenEveryGuesserIsOutTheTurnEndsWithNoPoints() {
        val r = Rig(3)
        r.draw()
        r.g.buzz(1); r.g.guessCard(r.wrongCard())
        assertEquals(DrawPhase.DRAW, r.s.phase)
        r.g.buzz(2); r.g.guessCard(r.wrongCard())
        assertEquals(DrawPhase.REVEAL, r.s.phase)
        assertFalse(r.s.result!!.solved); assertFalse(r.s.result!!.timedOut)
        assertEquals(List(3) { 0 }, r.s.scores)
    }

    @Test fun aWrongTypedGuessSitsThePlayerOutToo() {
        val r = Rig(cfg = DrawGuessConfig(typeIt = true))
        r.draw()
        r.g.buzz(1)
        assertFalse(r.g.guessTyped("zzzzz"))
        assertEquals(listOf(1), r.s.lockedOut)
        assertTrue(r.s.eliminated.isEmpty())
        assertEquals(DrawPhase.DRAW, r.s.phase)
    }

    @Test fun theDrawerCanEndTheTurnFromDrawingOrGuessing() {
        val a = Rig(); a.draw(); assertTrue(a.g.giveUp())
        assertEquals(DrawPhase.REVEAL, a.s.phase)
        assertFalse(a.s.result!!.solved)
        val b = Rig(); b.draw(); b.g.buzz(1); assertTrue(b.g.giveUp())
        assertEquals(DrawPhase.REVEAL, b.s.phase)
        assertFalse(a.g.giveUp())
    }

    // ---- teams ----

    private fun teamRig(teams: List<Int> = listOf(0, 1, 0, 1)) = Rig(teams.size, teams = teams)

    @Test fun teamPlayAlternatesTeamsAndOnlyTeammatesGuess() {
        val r = teamRig()
        assertTrue(r.g.teamMode)
        assertEquals(listOf(0, 1, 2, 3), r.g.order)
        assertEquals(listOf(2), r.g.eligibleGuessers(0))
        assertEquals(listOf(3), r.g.eligibleGuessers(1))
        r.draw()
        assertFalse("the other team cannot buzz", r.g.buzz(1))
        assertFalse(r.g.buzz(3))
        assertTrue(r.g.buzz(2))
    }

    @Test fun aSixPlayerTeamTableLetsTwoTeammatesGuess() {
        val r = Rig(6, teams = listOf(0, 1, 0, 1, 0, 1))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), r.g.order)
        assertEquals(listOf(2, 4), r.g.eligibleGuessers(0))
        r.draw(); r.g.buzz(2); r.g.guessCard(r.wrongCard())
        assertEquals("a teammate is still in", DrawPhase.DRAW, r.s.phase)
        r.g.buzz(4); r.g.guessCard(r.wrongCard())
        assertEquals(DrawPhase.REVEAL, r.s.phase)
    }

    @Test fun teamsOnlyApplyToAnEvenBalancedTableOfFourOrMore() {
        assertFalse(Rig(4, teams = listOf(0, 0, 0, 1)).g.teamMode)
        assertFalse(Rig(5, teams = listOf(0, 1, 0, 1, 0)).g.teamMode)
        assertFalse(Rig(2, teams = listOf(0, 1)).g.teamMode)
        assertFalse(Rig(4).g.teamMode)
        assertTrue(Rig(8, teams = List(8) { it % 2 }).g.teamMode)
    }

    @Test fun teamScoresAddUpAndTheTopTeamWins() {
        val r = teamRig()
        r.draw(); r.clock.advance(10_000); r.g.buzz(2); r.g.guessCard(r.word)
        val res = r.s.result!!
        r.g.nextTurn()
        repeat(3) { r.finishTurnUnsolved() }
        assertEquals(DrawPhase.OVER, r.s.phase)
        assertEquals(listOf(res.guesserPoints + res.drawerPoints, 0), r.g.teamScores())
        assertEquals(listOf(0, 2), r.g.winners())
        assertTrue(r.g.standings().take(2).toSet() == setOf(0, 2))
    }

    @Test fun aTiedTeamTotalHasNoWinner() {
        val r = teamRig()
        repeat(4) { r.finishTurnUnsolved() }
        assertEquals(listOf(0, 0), r.g.teamScores())
        assertEquals(emptyList<Int>(), r.g.winners())
    }

    // ---- the end ----

    @Test fun theTopScorerWinsAndLeavingReportsEverySeat() {
        val r = Rig(3)
        var result: GameResult? = null
        var calls = 0
        r.g.setOnMatchEnd { result = it; calls++ }
        r.draw(); r.clock.advance(15_000); r.g.buzz(1); r.g.guessCard(r.word); r.g.nextTurn()
        repeat(2) { r.finishTurnUnsolved() }
        assertEquals(DrawPhase.OVER, r.s.phase)
        assertEquals(listOf(1), r.g.winners())
        assertEquals(1, r.g.standings().first())
        assertNull("nothing to resume after the end", r.g.snapshot())
        r.g.leaveSession()
        r.g.leaveSession()
        assertEquals(1, calls)
        assertTrue(r.g.matchOver.value)
        val scores = result!!.scores
        assertEquals(3, scores.size)
        assertEquals(listOf(false, true, false), scores.map { it.isWinner })
        assertEquals(listOf(9, 18, 0), scores.map { it.score })
        assertFalse(result!!.wasAborted)
    }

    @Test fun everyoneTiedOnZeroAllWin() {
        val r = Rig(3)
        repeat(3) { r.finishTurnUnsolved() }
        assertEquals(listOf(0, 1, 2), r.g.winners())
    }

    @Test fun playAgainDealsAFreshGame() {
        val r = Rig(3)
        repeat(3) { r.finishTurnUnsolved() }
        r.g.playAgain()
        assertEquals(DrawPhase.CHOOSE, r.s.phase)
        assertEquals(0, r.s.turn)
        assertTrue(r.s.used.isEmpty())
        assertEquals(List(3) { 0 }, r.s.scores)
    }

    @Test fun abortingReportsNothing() {
        val r = Rig(3)
        var result: GameResult? = null
        r.g.setOnMatchEnd { result = it }
        r.g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }

    // ---- save and restore ----

    private fun midDrawing(): Rig {
        val r = Rig(4, cfg = DrawGuessConfig(timerSeconds = 90, rounds = 2))
        r.finishTurnUnsolved()
        r.draw(1)
        r.g.beginStroke(1, 2, false, 0.1f, 0.1f); r.g.extendStroke(0.4f, 0.3f); r.g.extendStroke(0.6f, 0.5f); r.g.endStroke()
        r.g.beginStroke(5, 0, true, 0.7f, 0.2f); r.g.endStroke()
        r.clock.advance(5_000)
        return r
    }

    private fun fresh(ctx: GameContext, clock: DgClock = DgClock(777_000L)): DrawGuessGame =
        DrawGuessGame(Random(99), clock).also { it.init(ctx) }

    private fun strokesOf(g: DrawGuessGame) = g.board.strokes.map { listOf(it.color, it.size, if (it.eraser) 1 else 0) + it.xy.toList() }

    @Test fun aSnapshotMidDrawingRestoresEverythingAndWaitsForTheDrawerToContinue() {
        val r = midDrawing()
        val snap = r.g.snapshot()
        assertNotNull(snap)
        val g2 = fresh(r.ctx)
        assertTrue(g2.restore(snap!!))
        assertEquals(r.s, g2.state.value)
        assertEquals(strokesOf(r.g), strokesOf(g2))
        assertEquals(r.g.config, g2.config)
        assertEquals(5_000L, g2.elapsedMs())
        assertFalse("the clock waits for the drawer", g2.clockRunning.value)
        assertFalse(g2.beginStroke(0, 1, false, 0.1f, 0.1f))
        g2.resume()
        assertTrue(g2.clockRunning.value)
        assertTrue(g2.beginStroke(0, 1, false, 0.1f, 0.1f))
        assertEquals(85_000L, g2.remainingMs())
    }

    @Test fun everyPhaseRoundTrips() {
        val r = midDrawing()
        // GUESS (with a wrong card already crossed out and one player locked out)
        r.g.buzz(0 + 2)
        r.g.guessCard(r.wrongCard())
        r.g.buzz(3)
        assertEquals(DrawPhase.GUESS, r.s.phase)
        val g2 = fresh(r.ctx)
        assertTrue(g2.restore(r.g.snapshot()!!))
        assertEquals(r.s, g2.state.value)
        // REVEAL
        r.g.guessCard(r.word)
        assertEquals(DrawPhase.REVEAL, r.s.phase)
        val g3 = fresh(r.ctx)
        assertTrue(g3.restore(r.g.snapshot()!!))
        assertEquals(r.s, g3.state.value)
        // CHOOSE and READY
        r.g.nextTurn()
        val g4 = fresh(r.ctx)
        assertTrue(g4.restore(r.g.snapshot()!!))
        assertEquals(r.s, g4.state.value)
        r.pick()
        val g5 = fresh(r.ctx)
        assertTrue(g5.restore(r.g.snapshot()!!))
        assertEquals(r.s, g5.state.value)
        // and a restored game plays on
        assertTrue(g5.beginDrawing())
    }

    @Test fun aRestoredGameKeepsScoringWithTheRightTotals() {
        val r = midDrawing()
        r.g.buzz(2)
        val g2 = fresh(r.ctx)
        g2.restore(r.g.snapshot()!!)
        assertTrue(g2.guessCard(g2.state.value!!.wordKey!!))
        assertTrue(g2.state.value!!.scores[2] > 0)
        assertEquals(DrawPhase.REVEAL, g2.state.value!!.phase)
    }

    @Test fun nothingToSaveBeforeStartOrAfterTheGameEnds() {
        val r = Rig(3, start = false)
        assertNull(r.g.snapshot())
        r.g.startMatch()
        assertNotNull(r.g.snapshot())
        r.g.leaveSession()
        assertNull(r.g.snapshot())
    }

    @Test fun corruptOrMismatchedSnapshotsAreRejectedAndLeaveTheEngineUntouched() {
        val r = midDrawing()
        val snap = r.g.snapshot()!!
        val word = r.word
        val bad = listOf(
            "", "not json", "{}", "[]", snap.dropLast(20), snap.replace("\"v\":1", "\"v\":2"),
            snap.replace("\"timer\":90", "\"timer\":91"), snap.replace("\"rounds\":2", "\"rounds\":9"),
            snap.replace("\"lang\":\"EN\"", "\"lang\":\"XX\""), snap.replace("\"turn\":1", "\"turn\":99"),
            snap.replace("\"phase\":\"DRAW\"", "\"phase\":\"OVER\""), snap.replace("\"phase\":\"DRAW\"", "\"phase\":\"NOPE\""),
            snap.replace("\"phase\":\"DRAW\"", "\"phase\":\"CHOOSE\""),
            snap.replace("\"word\":\"$word\"", "\"word\":\"nonexistent\""), snap.replace("\"word\":\"$word\"", "\"word\":null"),
            snap.replace("\"locked\":[]", "\"locked\":[1]"), snap.replace("\"guesser\":-1", "\"guesser\":2"),
            snap.replace("\"elapsedMs\":5000", "\"elapsedMs\":999999"), snap.replace("\"elapsedMs\":5000", "\"elapsedMs\":-1"),
            snap.replace("\"p\":[", "\"p\":[7,"), snap.replace("\"p\":[", "\"p\":[5000,5000,"),
            snap.replace("\"c\":1", "\"c\":9"), snap.replace("\"s\":2", "\"s\":7"),
            snap.replace("\"scores\":[", "\"scores\":[-5,"), snap.replace("\"ids\":[\"p1\"", "\"ids\":[\"zz\"")
        )
        for ((i, s) in bad.withIndex()) {
            val g2 = fresh(r.ctx)
            g2.startMatch()
            val before = g2.state.value
            assertFalse("snapshot #$i should be rejected", g2.restore(s))
            assertEquals("snapshot #$i changed the state", before, g2.state.value)
            assertTrue(g2.board.strokes.isEmpty())
            assertEquals(DrawGuessConfig(), g2.config)
        }
    }

    // ---- a timed-out turn must stay resumable ----

    private fun timedOutAfterOvershoot(overshootMs: Long): Rig {
        val r = Rig(cfg = DrawGuessConfig(timerSeconds = 30))
        r.draw()
        r.clock.advance(30_000 + overshootMs) // the 250 ms ticker fires late
        assertTrue(r.g.tick())
        assertEquals(DrawPhase.REVEAL, r.s.phase)
        return r
    }

    @Test fun elapsedTimeNeverExceedsTheLimit() {
        val r = timedOutAfterOvershoot(250)
        assertEquals(30_000L, r.g.elapsedMs())
        r.clock.advance(10_000)
        assertEquals(30_000L, r.g.elapsedMs())
        assertEquals(0L, r.g.remainingMs())
    }

    @Test fun aTurnThatTimedOutBy250MsRestoresOnTheRevealScreen() {
        val r = timedOutAfterOvershoot(250)
        val g2 = fresh(r.ctx)
        assertTrue("the save of a timed-out turn must not be destroyed", g2.restore(r.g.snapshot()!!))
        assertEquals(r.s, g2.state.value)
        assertTrue(g2.state.value!!.result!!.timedOut)
    }

    @Test fun aTurnThatTimedOutByThreeSecondsRestoresOnTheRevealScreen() {
        val r = timedOutAfterOvershoot(3_000)
        val g2 = fresh(r.ctx)
        assertTrue(g2.restore(r.g.snapshot()!!))
        assertEquals(DrawPhase.REVEAL, g2.state.value!!.phase)
    }

    @Test fun backgroundingJustAfterTheLimitBeforeTheNextTickRestoresAsTimeUp() {
        val r = Rig(cfg = DrawGuessConfig(timerSeconds = 30))
        r.draw()
        r.clock.advance(30_137) // past the limit, tick() has not run yet
        r.g.pause()
        val snap = r.g.snapshot()!!
        assertTrue(snap.contains("\"elapsedMs\":30000"))
        val g2 = fresh(r.ctx)
        assertTrue(g2.restore(snap))
        assertEquals(DrawPhase.REVEAL, g2.state.value!!.phase)
        assertTrue(g2.state.value!!.result!!.timedOut)
        assertFalse(g2.clockRunning.value)
    }

    @Test fun savesThatKeptTheOvershootStillRestoreWithinTheToleranceAndNotBeyond() {
        val snap = midDrawing().g.snapshot()!!
        val ctx = midDrawing().ctx
        val within = fresh(ctx)
        assertTrue(within.restore(snap.replace("\"elapsedMs\":5000", "\"elapsedMs\":90137")))
        assertEquals(DrawPhase.REVEAL, within.state.value!!.phase)
        assertEquals(90_000L, within.elapsedMs())
        assertTrue(fresh(ctx).restore(snap.replace("\"elapsedMs\":5000", "\"elapsedMs\":${90_000 + RESTORE_OVERSHOOT_MS}")))
        assertFalse(fresh(ctx).restore(snap.replace("\"elapsedMs\":5000", "\"elapsedMs\":${90_001 + RESTORE_OVERSHOOT_MS}")))
    }

    @Test fun pausingMidStrokeClosesTheStroke() {
        val r = Rig()
        r.draw()
        r.g.beginStroke(1, 1, false, 0.1f, 0.1f); r.g.extendStroke(0.5f, 0.5f)
        assertTrue(r.g.board.drawing)
        r.g.pause()
        assertFalse(r.g.board.drawing)
        assertEquals(1, r.g.board.strokes.size)
        r.g.resume()
        assertTrue("undo works after a pause", r.g.undoStroke())
    }

    @Test fun snapshotsWithInconsistentBookkeepingAreRejected() {
        val r = midDrawing()
        val snap = r.g.snapshot()!!
        val word = r.word
        val bad = listOf(
            snap.replace(Regex("\"used\":\\[[^\\]]*\\]"), "\"used\":[]"),
            snap.replace("\"eliminated\":[]", "\"eliminated\":[\"$word\"]"),
            "x".repeat(250_000)
        )
        for ((i, s) in bad.withIndex()) assertFalse("snapshot #$i should be rejected", fresh(r.ctx).restore(s))
    }

    @Test fun aSnapshotFromADifferentRosterIsRejected() {
        val r = midDrawing()
        val snap = r.g.snapshot()!!
        assertFalse(fresh(dgContext(4, prefix = "q")).restore(snap))
        assertFalse(fresh(dgContext(3)).restore(snap))
        assertFalse(fresh(dgContext(4, bots = 1)).restore(snap))
        assertTrue(fresh(dgContext(4)).restore(snap))
    }

    @Test fun anOverlongDrawingInASnapshotIsRejected() {
        val r = midDrawing()
        val snap = r.g.snapshot()!!
        val many = (0 until DrawLimits.MAX_POINTS_PER_STROKE + 1).joinToString(",") { "${it % 500},${it % 700}" }
        val tooLong = snap.replace(Regex("\"p\":\\[[^\\]]*\\]"), "\"p\":[$many]")
        assertFalse(fresh(r.ctx).restore(tooLong))
    }
}
