package com.gamesuite.games.pigdice

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.core.UndoableGame
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PigGameTest {
    private val vsCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    @Test fun everyDieIsOneNextInt1To7CallOnTheInjectedRandom() {
        val die = PigScriptedRandom(3, 4, 5)
        val g = pigNewGame(random = die)
        g.roll(); assertEquals(listOf(3), g.state.value!!.dice)
        g.roll(); assertEquals(listOf(4), g.state.value!!.dice)
        g.roll(); assertEquals(listOf(5), g.state.value!!.dice)
        assertEquals(3, die.calls)
        assertEquals(listOf(3, 4, 5), g.rollLog)
    }

    @Test fun rollingAddsToThePotAndHoldingBanksItAndPassesTheTurn() {
        val g = pigNewGame(random = PigScriptedRandom(4, 5, 3))
        g.roll(); g.roll()
        assertEquals(9, g.state.value!!.turnTotal)
        assertEquals(0, g.state.value!!.scores[0])
        assertTrue(g.hold())
        val s = g.state.value!!
        assertEquals(listOf(9, 0), s.scores)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(0, s.turnTotal)
        assertEquals(PigEvent.HOLD, s.event)
        assertEquals(9, s.eventPoints)
    }

    @Test fun aOneLosesThePotAndTheTurn() {
        val g = pigNewGame(random = PigScriptedRandom(6, 6, 1))
        g.roll(); g.roll(); g.roll()
        val s = g.state.value!!
        assertEquals(listOf(0, 0), s.scores)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(PigEvent.BUST, s.event)
        assertEquals(12, s.eventPoints) // the pot that was lost
        assertEquals(0, s.turnTotal)
    }

    @Test fun holdWithNothingInThePotIsRefusedWithAReason() {
        val g = pigNewGame()
        assertEquals(PigReject.NOTHING_TO_HOLD, g.explainHold())
        assertFalse(g.hold())
        assertEquals(0, g.state.value!!.turnSerial)
    }

    @Test fun reachingTheGoalWinsAtOnceWithoutHolding() {
        val g = pigNewGame(random = PigScriptedRandom(6), target = 20)
        repeat(4) { g.roll() } // 24 >= 20
        val s = g.state.value!!
        assertTrue(s.over); assertEquals("p1", s.winnerPlayerId); assertEquals(PigEvent.WIN, s.event)
        assertEquals(24, s.scores[0])
        assertEquals(mapOf("p1" to 1, "p2" to 0), g.sessionWins.value)
        assertFalse(g.roll()); assertFalse(g.hold())
        assertEquals(PigReject.OVER, g.explainRoll())
    }

    @Test fun playAgainStartsAFreshGameWithTheOtherSeatFirstAndKeepsTheTally() {
        val g = pigNewGame(random = PigScriptedRandom(6), target = 20)
        repeat(4) { g.roll() }
        g.playAgain()
        val s = g.state.value!!
        assertFalse(s.over); assertEquals(listOf(0, 0), s.scores); assertEquals(1, s.currentPlayerIndex)
        assertEquals(1, g.sessionWins.value["p1"])
        assertTrue(g.rollLog.isEmpty())
    }

    @Test fun bigPigUsesTwoDiceWithSnakeEyesBustsAndDoubles() {
        val g = pigNewGame(random = PigScriptedRandom(1, 1, 4, 4, 2, 3, 1, 5), variant = PigVariant.BIG_PIG)
        assertEquals(PigReject.NONE, g.explainRoll(2))
        assertEquals(PigReject.BAD_COUNT, g.explainRoll(1))
        g.roll(); assertEquals(25, g.state.value!!.turnTotal); assertEquals(PigEvent.SNAKE_EYES, g.state.value!!.event)
        g.roll(); assertEquals(25 + 16, g.state.value!!.turnTotal); assertEquals(PigEvent.DOUBLES, g.state.value!!.event)
        g.roll(); assertEquals(25 + 16 + 5, g.state.value!!.turnTotal)
        g.roll(); // 1 and 5: bust
        assertEquals(PigEvent.BUST, g.state.value!!.event); assertEquals(1, g.state.value!!.currentPlayerIndex)
        assertEquals(listOf(0, 0), g.state.value!!.scores)
    }

    @Test fun hogIsOneRollOfTheChosenNumberOfDiceAndNeverHolds() {
        val g = pigNewGame(random = PigScriptedRandom(2, 3, 4, 1, 5), variant = PigVariant.HOG)
        assertEquals(PigReject.HOG_NO_HOLD, g.explainHold())
        assertEquals(PigReject.BAD_COUNT, g.explainRoll(0))
        assertEquals(PigReject.BAD_COUNT, g.explainRoll(PigRules.HOG_MAX_DICE + 1))
        assertTrue(g.roll(3))
        var s = g.state.value!!
        assertEquals(listOf(9, 0), s.scores); assertEquals(1, s.currentPlayerIndex); assertEquals(PigEvent.HOG, s.event)
        assertTrue(g.roll(2)) // 1 and 5: nothing
        s = g.state.value!!
        assertEquals(listOf(9, 0), s.scores); assertEquals(0, s.currentPlayerIndex); assertEquals(PigEvent.HOG_BUST, s.event)
    }

    @Test fun twentyOnePlusBanksAnExactTwentyOneWithTheBonusAndPassesTheTurn() {
        val g = pigNewGame(random = PigScriptedRandom(6, 6, 5, 4), variant = PigVariant.TWENTY_ONE)
        repeat(3) { g.roll() }
        assertEquals(17, g.state.value!!.turnTotal)
        g.roll() // 4 -> exactly 21
        val s = g.state.value!!
        assertEquals(PigEvent.EXACT, s.event)
        assertEquals(17 + 4 + PigRules.EXACT_BONUS, s.scores[0])
        assertEquals(1, s.currentPlayerIndex); assertEquals(0, s.turnTotal)
    }

    @Test fun playersTakeTurnsAndOnlyTheHumanSeatCanActAgainstTheComputer() {
        val g = pigNewGame(vsCpu, PigScriptedRandom(5, 3, 2, 1), PlayMode.SINGLE_PLAYER_VS_BOT)
        assertTrue(g.isMyTurn())
        g.roll(); g.hold()
        assertFalse(g.isMyTurn())
        assertEquals(PigReject.NOT_YOUR_TURN, g.explainRoll())
        assertFalse(g.roll()); assertFalse(g.hold())
        assertTrue(g.botToMove())
        g.difficulty = CpuDifficulty.MEDIUM
        g.playBotStep() // the computer rolls its first die
        assertEquals(2, g.state.value!!.rollSerial)
    }

    @Test fun diceAreReproducibleFromASeedAndComputerGamesAlwaysEnd() {
        fun play(seed: Int, level: CpuDifficulty, v: PigVariant): Pair<List<Int>, PigState> {
            val bots = listOf(PlayerInfo("a", "A", true), PlayerInfo("b", "B", true), PlayerInfo("c", "C", true))
            val g = pigNewGame(bots, Random(seed), PlayMode.SINGLE_PLAYER_VS_BOT, variant = v)
            g.difficulty = level
            var steps = 0
            while (!g.state.value!!.over) { g.playBotStep(); check(++steps < 20000) { "did not terminate" } }
            return g.rollLog to g.state.value!!
        }
        for (v in PigVariant.values()) for (level in CpuDifficulty.values()) {
            val a = play(11, level, v); val b = play(11, level, v)
            assertEquals("$v $level", a, b)
            assertTrue(a.second.over)
            assertTrue(a.second.scores.maxOrNull()!! >= a.second.target)
        }
        assertTrue(play(1, CpuDifficulty.EASY, PigVariant.CLASSIC).first != play(2, CpuDifficulty.EASY, PigVariant.CLASSIC).first)
    }

    @Test fun thereIsNoUndoBecauseItWouldLetAPlayerReRoll() {
        assertFalse(pigNewGame() is UndoableGame)
    }

    // ---- resume -------------------------------------------------------------------------------

    @Test fun snapshotRoundTripsAtEveryStepOfRandomPlayForEveryVariant() {
        val r = Random(21)
        for (v in PigVariant.values()) for (n in listOf(2, 3, 6)) {
            val roster = pigHumans(n)
            val g = pigNewGame(roster, Random(r.nextInt()), variant = v, target = 50)
            var steps = 0
            while (!g.state.value!!.over && steps++ < 4000) {
                val snap = g.snapshot()
                assertNotNull(snap)
                val twin = pigNewGame(roster, Random(0), start = false)
                assertTrue("restore at step $steps ($v, $n)", twin.restore(snap!!))
                assertEquals(g.state.value, twin.state.value)
                assertEquals(snap, twin.snapshot())
                assertEquals(g.rollLog, twin.rollLog)
                val s = g.state.value!!
                if (v.singleRoll) g.roll(r.nextInt(1, PigRules.HOG_MAX_DICE + 1))
                else if (s.turnTotal > 0 && r.nextInt(100) < 35) g.hold() else g.roll()
            }
            assertTrue(g.state.value!!.over)
        }
    }

    @Test fun aRestoredGameContinuesWithTheSameRulesAndTally() {
        val g = pigNewGame(random = PigScriptedRandom(6, 6, 6, 6, 6), variant = PigVariant.BIG_PIG, target = 40)
        g.roll() // (6,6) doubles = 24
        val snap = g.snapshot()!!
        val twin = pigNewGame(random = PigScriptedRandom(6), start = false)
        assertTrue(twin.restore(snap))
        assertEquals(PigVariant.BIG_PIG, twin.variant); assertEquals(40, twin.target)
        twin.roll()
        assertTrue(twin.state.value!!.over) // 24 + 24 >= 40
    }

    @Test fun tamperedOrMismatchedSavesAreRefusedAndLeaveTheGameUntouched() {
        val g = pigNewGame(random = PigScriptedRandom(4, 5))
        g.roll()
        val snap = g.snapshot()!!
        val fresh = { pigNewGame(start = false) }
        fun refuses(text: String) {
            val t = fresh()
            assertFalse(text, t.restore(text))
            assertNull(t.state.value)
        }
        refuses("")
        refuses("not json")
        refuses(snap.replace("\"target\":100", "\"target\":5"))
        refuses(snap.replace("\"target\":100", "\"target\":100000"))
        refuses(snap.replace("\"variant\":\"classic\"", "\"variant\":\"nope\""))
        refuses(snap.replace("\"p1\"", "\"zz\""))
        refuses(snap.replace(Regex("\"scores\":\\[[^]]*]"), "\"scores\":[-1,0]"))
        refuses(snap.replace(Regex("\"scores\":\\[[^]]*]"), "\"scores\":[0]"))
        refuses(snap.replace(Regex("\"scores\":\\[[^]]*]"), "\"scores\":[150,0]"))      // over the goal but not marked won
        refuses(snap.replace(Regex("\"dice\":\\[[^]]*]"), "\"dice\":[9]"))
        refuses(snap.replace(Regex("\"dice\":\\[[^]]*]"), "\"dice\":[2,3]"))             // two dice in classic
        refuses(snap.replace("\"currentPlayerIndex\":0", "\"currentPlayerIndex\":7"))
        refuses(snap.replace("\"over\":false", "\"over\":true"))                          // over with no winner
        refuses(snap.replace("\"event\":\"roll\"", "\"event\":\"explode\""))
        refuses(snap.replace(Regex("\"rollLog\":\\[[^]]*]"), "\"rollLog\":[7]"))
        // a three-seat table cannot take a two-seat save
        val three = pigNewGame(pigHumans(3), start = false)
        assertFalse(three.restore(snap))
        // a networked game never resumes
        val net = PigGame(Random(1)).also { it.init(GameContext(PlayMode.ONLINE, pigHumans(2), 0, LocalPassAndPlayTransport())) }
        assertFalse(net.restore(snap))
        assertNull(net.snapshot())
    }

    @Test fun noSnapshotBeforeTheStartOrAfterTheEnd() {
        val g = pigNewGame(start = false)
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.endMatch(GameResult())
        assertNull(g.snapshot())
    }

    // ---- fuzz ---------------------------------------------------------------------------------

    @Test fun randomLegalPlayAlwaysTerminatesAndKeepsItsInvariants() {
        val r = Random(77)
        repeat(200) { game ->
            val v = PigVariant.values()[game % 4]
            val n = r.nextInt(2, 7)
            val g = pigNewGame(pigHumans(n), Random(r.nextInt()), variant = v, target = listOf(30, 50, 100)[game % 3])
            var steps = 0
            while (!g.state.value!!.over) {
                check(++steps < 6000) { "no end" }
                val s = g.state.value!!
                // illegal requests must be refused and change nothing
                if (r.nextInt(10) == 0) {
                    val before = g.state.value
                    g.roll(if (v.singleRoll) 0 else 5)
                    assertTrue(before === g.state.value)
                    if (s.turnTotal == 0) { g.hold(); assertTrue(before === g.state.value) }
                }
                if (v.singleRoll) g.roll(r.nextInt(1, PigRules.HOG_MAX_DICE + 1))
                else if (s.turnTotal > 0 && r.nextInt(100) < 40) g.hold() else g.roll()
                val t = g.state.value!!
                if (!t.over) {
                    assertTrue(t.scores.all { it < t.target })
                    assertTrue(t.scores[t.currentPlayerIndex] + t.turnTotal < t.target)
                }
                assertTrue(t.scores.all { it >= 0 })
            }
            val end = g.state.value!!
            assertEquals(1, end.scores.count { it >= end.target })
            assertEquals(end.players[end.scores.indexOfFirst { it >= end.target }].playerId, end.winnerPlayerId)
        }
    }

    // ---- hints and results --------------------------------------------------------------------

    @Test fun theHintIsOfferedOnAHumanTurnOnlyAndNeverForTheComputer() {
        val g = pigNewGame(vsCpu, PigScriptedRandom(3), PlayMode.SINGLE_PLAYER_VS_BOT)
        val hint = runBlocking { g.requestHint() }
        assertNotNull(hint)
        assertEquals(PigMove.Roll(1), hint!!.move)
        assertTrue(hint.winChance > 0.4 && hint.winChance < 0.6)
        g.roll(); g.hold()                       // now the computer's turn
        assertNull(runBlocking { g.requestHint() })
    }

    @Test fun leavingScoresTheSessionByGamesWon() {
        var result: GameResult? = null
        val g = pigNewGame(random = PigScriptedRandom(6), target = 20)
        g.setOnMatchEnd { result = it }
        repeat(4) { g.roll() }
        g.leaveSession()
        val r = result!!
        assertFalse(r.wasAborted)
        assertEquals(listOf(true, false), r.scores.map { it.isWinner })
        assertEquals(listOf(1, 0), r.scores.map { it.score })
    }
}
