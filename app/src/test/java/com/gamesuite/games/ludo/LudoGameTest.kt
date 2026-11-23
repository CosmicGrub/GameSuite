package com.gamesuite.games.ludo

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

/** A die that plays back a fixed list of rolls (then repeats it): dice must only ever come from the injected Random. */
class ScriptedRandom(private vararg val rolls: Int) : Random() {
    var calls = 0
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextInt(from: Int, until: Int): Int {
        require(from == 1 && until == 7) { "the die must ask for nextInt(1, 7)" }
        return rolls[calls++ % rolls.size]
    }
}

class LudoGameTest {
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }
    private val vsCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    fun newGame(
        players: List<PlayerInfo> = humans(2), random: Random = Random(1),
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, start: Boolean = true,
        needSix: Boolean = true, exactFinish: Boolean = true
    ): LudoGame {
        val g = LudoGame(random)
        g.needSix = needSix; g.exactFinish = exactFinish
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        if (start) g.startMatch()
        return g
    }

    /** Hand-built save: [tokens] is seat*4+token progress; the seat on turn has rolled [dice] when [awaiting]. */
    fun snapshotJson(
        tokens: List<Int>, players: List<PlayerInfo>, current: Int = 0, dice: Int = 0, awaiting: Boolean = false,
        needSix: Boolean = true, exactFinish: Boolean = true, boardOver: Boolean = false, winner: String? = null,
        sixes: Int = 0, event: String = "start"
    ): String {
        val ps = players.joinToString(",") { "{\"playerId\":\"${it.playerId}\",\"displayName\":\"${it.displayName}\",\"isBot\":${it.isBot}}" }
        val wins = players.joinToString(",") { "\"${it.playerId}\":0" }
        return "{\"tokens\":[${tokens.joinToString(",")}],\"players\":[$ps],\"currentPlayerIndex\":$current,\"dice\":$dice," +
            "\"awaitingMove\":$awaiting,\"sixesInRow\":$sixes,\"needSix\":$needSix,\"exactFinish\":$exactFinish,\"event\":\"$event\"," +
            "\"boardOver\":$boardOver,\"winnerPlayerId\":${winner?.let { "\"$it\"" } ?: "null"},\"sessionWins\":{$wins}," +
            "\"nextStartingPlayerIndex\":0,\"difficulty\":\"MEDIUM\"}"
    }

    private fun yard(n: Int) = MutableList(n * 4) { -1 }

    // ---- dice come from the injected Random ---------------------------------------------------

    @Test fun everyRollIsOneNextInt1To7CallOnTheInjectedRandom() {
        val die = ScriptedRandom(3, 4, 5)
        val g = newGame(random = die)
        g.roll(); assertEquals(3, g.state.value!!.dice)
        g.roll(); assertEquals(4, g.state.value!!.dice)
        g.roll(); assertEquals(5, g.state.value!!.dice)
        assertEquals(3, die.calls)
        assertEquals(listOf(3, 4, 5), g.rollLog)
    }

    @Test fun aSeededRandomReplaysTheSameGame() {
        fun play(seed: Int): Pair<List<Int>, LudoState> {
            val g = newGame(listOf(PlayerInfo("a", "A", true), PlayerInfo("b", "B", true)), Random(seed), PlayMode.SINGLE_PLAYER_VS_BOT)
            g.difficulty = CpuDifficulty.MEDIUM
            repeat(300) { g.playBotStep() }
            return g.rollLog to g.state.value!!
        }
        val (logA, stateA) = play(77)
        val (logB, stateB) = play(77)
        assertEquals(logA, logB)
        assertEquals(stateA, stateB)
        assertNotEquals(logA, play(78).first)
    }

    // ---- turn flow ------------------------------------------------------------------------------

    @Test fun withoutASixNothingLeavesTheYardAndTheTurnPasses() {
        val g = newGame(random = ScriptedRandom(3))
        g.roll()
        val s = g.state.value!!
        assertFalse(s.awaitingMove)
        assertEquals(LudoEvent.NO_MOVE, s.event)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(3, s.dice)
    }

    @Test fun aSixBringsATokenOutAndEarnsAnotherRoll() {
        val g = newGame(random = ScriptedRandom(6, 2))
        g.roll()
        assertTrue(g.state.value!!.awaitingMove)
        assertEquals(listOf(0, 1, 2, 3), g.legalTokens())
        assertTrue(g.moveToken(0))
        var s = g.state.value!!
        assertEquals(0, s.tokens[0])
        assertEquals(LudoEvent.ENTER, s.event)
        assertEquals("a six keeps the turn", 0, s.currentPlayerIndex)
        assertFalse(s.awaitingMove)
        g.roll() // 2: token 0 is the only one that can move
        assertEquals(listOf(0), g.legalTokens())
        g.moveToken(0)
        s = g.state.value!!
        assertEquals(2, s.tokens[0])
        assertEquals(1, s.currentPlayerIndex)
    }

    @Test fun threeSixesInARowForfeitTheTurn() {
        val g = newGame(random = ScriptedRandom(6, 6, 6))
        g.roll(); g.moveToken(0)
        g.roll(); g.moveToken(0)
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        g.roll()
        val s = g.state.value!!
        assertEquals(LudoEvent.THREE_SIXES, s.event)
        assertEquals(1, s.currentPlayerIndex)
        assertFalse(s.awaitingMove)
        assertEquals(0, s.sixesInRow)
    }

    @Test fun withTheLooseStartRuleAnyRollBringsATokenOut() {
        val g = newGame(random = ScriptedRandom(2), needSix = false)
        g.roll()
        assertTrue(g.state.value!!.awaitingMove)
        g.moveToken(1)
        assertEquals(0, g.state.value!!.tokens[1])
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test fun cannotRollTwiceOrMoveBeforeRolling() {
        val g = newGame(random = ScriptedRandom(6, 1))
        assertEquals(LudoReject.ROLL_FIRST, g.explainToken(0))
        assertFalse(g.moveToken(0))
        assertTrue(g.roll())
        assertEquals(LudoReject.ALREADY_ROLLED, g.explainRoll())
        assertFalse(g.roll())
    }

    @Test fun explainTokenGivesTheRightReason() {
        val g = newGame(start = false, random = ScriptedRandom(4))
        val toks = yard(2); toks[0] = 56; toks[1] = 54; toks[2] = 10
        assertTrue(g.restore(snapshotJson(toks, humans(2), current = 0, dice = 3, awaiting = true)))
        assertEquals(LudoReject.FINISHED, g.explainToken(0))
        assertEquals(LudoReject.EXACT_ROLL, g.explainToken(1))
        assertEquals(LudoReject.NONE, g.explainToken(2))
        assertEquals(LudoReject.NEED_SIX, g.explainToken(3))
        assertEquals(LudoReject.NOT_YOUR_TOKEN, g.explainToken(7))
        assertEquals(LudoReject.ALREADY_ROLLED, g.explainRoll())
    }

    @Test fun explainsWhenItIsTheComputersTurn() {
        val g = newGame(listOf(PlayerInfo("cpu", "CPU", true), PlayerInfo("you", "You")), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        assertEquals(LudoReject.NOT_YOUR_TURN, g.explainRoll())
        assertFalse(g.roll())
    }

    // ---- captures, finishing, winning ------------------------------------------------------------

    @Test fun landingOnARivalCapturesItAndTheEventSaysSo() {
        val g = newGame(start = false)
        val toks = yard(2); toks[0] = 5; toks[4] = 35
        assertTrue(g.restore(snapshotJson(toks, humans(2), dice = 4, awaiting = true)))
        assertTrue(g.moveToken(0))
        val s = g.state.value!!
        assertEquals(LudoEvent.CAPTURE, s.event)
        assertEquals(1, s.captured)
        assertEquals(-1, s.tokens[4])
        assertEquals(9, s.tokens[0])
        assertEquals(1, s.currentPlayerIndex)
    }

    @Test fun exactFinishBlocksOvershootButTheLooseRuleAllowsIt() {
        val toks = yard(2); toks[0] = 54
        val strict = newGame(start = false)
        strict.restore(snapshotJson(toks, humans(2), dice = 4, awaiting = false))
        strict.state.value!!.let { assertFalse(it.awaitingMove) }
        val g = newGame(start = false, random = ScriptedRandom(4))
        g.restore(snapshotJson(toks, humans(2), dice = 0, awaiting = false))
        g.roll() // 54 + 4 = 58 > 56: nothing can move
        assertEquals(LudoEvent.NO_MOVE, g.state.value!!.event)
        val loose = newGame(start = false, random = ScriptedRandom(4), exactFinish = false)
        loose.restore(snapshotJson(toks, humans(2), dice = 0, awaiting = false, exactFinish = false))
        loose.roll()
        assertTrue(loose.state.value!!.awaitingMove)
        loose.moveToken(0)
        assertEquals(56, loose.state.value!!.tokens[0])
        assertEquals(LudoEvent.HOME, loose.state.value!!.event)
    }

    @Test fun gettingTheFourthTokenHomeWinsTheBoardAndTheSession() {
        val g = newGame(start = false)
        val results = ArrayList<GameResult>()
        g.setOnMatchEnd { results += it }
        val toks = yard(2); toks[0] = 56; toks[1] = 56; toks[2] = 56; toks[3] = 53
        assertTrue(g.restore(snapshotJson(toks, humans(2), dice = 3, awaiting = true)))
        assertTrue(g.moveToken(3))
        val s = g.state.value!!
        assertTrue(s.boardOver)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(LudoEvent.WIN, s.event)
        assertEquals(1, g.sessionWins.value["p1"])
        assertFalse("no more rolls", g.roll())
        g.leaveSession()
        assertTrue(results.single().scores.first { it.playerId == "p1" }.isWinner)
    }

    @Test fun aWinningMoveOnASixDoesNotGrantAnotherRoll() {
        val g = newGame(start = false)
        val toks = yard(2); toks[0] = 56; toks[1] = 56; toks[2] = 56; toks[3] = 50
        g.restore(snapshotJson(toks, humans(2), dice = 6, awaiting = true))
        g.moveToken(3)
        assertTrue(g.state.value!!.boardOver)
        assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    @Test fun playAgainStartsAFreshBoardAndKeepsTheTally() {
        val g = newGame(start = false)
        val toks = yard(2); toks[0] = 56; toks[1] = 56; toks[2] = 56; toks[3] = 53
        g.restore(snapshotJson(toks, humans(2), dice = 3, awaiting = true))
        g.moveToken(3)
        g.playAgain()
        val s = g.state.value!!
        assertFalse(s.boardOver)
        assertTrue(s.tokens.all { it == -1 })
        assertEquals(1, g.sessionWins.value["p1"])
        assertTrue(g.rollLog.isEmpty())
    }

    // ---- resume ----------------------------------------------------------------------------------

    @Test fun snapshotRestoreRoundTripsMidGameIncludingTheOpenRoll() {
        val g = newGame(humans(3), Random(4))
        var steps = 0
        while (steps++ < 60) {
            if (g.state.value!!.awaitingMove) g.moveToken(g.legalTokens().first()) else g.roll()
        }
        val snap = g.snapshot()!!
        val g2 = newGame(humans(3), Random(99), start = false)
        assertTrue(g2.restore(snap))
        assertEquals(g.state.value, g2.state.value)
        assertEquals(g.rollLog, g2.rollLog)
        assertEquals(g.sessionWins.value, g2.sessionWins.value)
    }

    @Test fun savedRulesAreRestoredWithTheGame() {
        val g = newGame(needSix = false, exactFinish = false)
        val g2 = newGame(start = false)
        assertTrue(g2.restore(g.snapshot()!!))
        assertFalse(g2.state.value!!.needSix)
        assertFalse(g2.state.value!!.exactFinish)
    }

    @Test fun impossibleOrTamperedSavesAreRefused() {
        val g = newGame(random = ScriptedRandom(6))
        g.roll()
        val good = g.snapshot()!!
        val before = g.state.value
        val bad = listOf(
            "", "garbage", "{}", good.replace("\"dice\":6", "\"dice\":9"),
            good.replace("\"event\":\"roll\"", "\"event\":\"bogus\""),
            good.replace("\"p1\"", "\"zz\""),
            good.replace("\"tokens\":[-1,", "\"tokens\":[57,"),
            snapshotJson(yard(2).also { it[0] = 9; it[4] = 35 }, humans(2)), // rivals on one unsafe square
            snapshotJson(yard(2), humans(2), dice = 3, awaiting = true),       // "must move" with no legal token
            snapshotJson(yard(2), humans(2), boardOver = true, winner = "p1"),  // won with nothing home
            snapshotJson(yard(3), humans(2))                                    // wrong token count
        )
        bad.forEach { assertFalse("accepted ${it.take(70)}", g.restore(it)) }
        assertTrue(before === g.state.value)
        assertFalse(newGame(humans(3), start = false).restore(good))
    }

    @Test fun noSnapshotWhenFinishedOrNetworked() {
        assertNull(newGame(start = false).snapshot())
        val g = newGame()
        g.leaveSession()
        assertNull(g.snapshot())
    }

    // ---- undo ---------------------------------------------------------------------------------------

    @Test fun undoReturnsToTheSameRollWithTheChoiceOpenSoItCannotRerollTheDie() {
        val g = newGame(random = ScriptedRandom(6, 5, 2))
        g.roll()
        val choosing = g.state.value
        assertFalse("nothing to undo before a move", g.canUndo)
        g.moveToken(0)
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(choosing, g.state.value)
        assertTrue(g.state.value!!.awaitingMove)
        assertEquals(6, g.state.value!!.dice)
        g.moveToken(1) // choose differently
        assertEquals(0, g.state.value!!.tokens[1])
    }

    @Test fun undoAgainstTheComputerRewindsItsWholeReplyToo() {
        val g = newGame(vsCpu, ScriptedRandom(6, 2, 1, 1, 1, 1), PlayMode.SINGLE_PLAYER_VS_BOT)
        g.roll(); g.moveToken(0)           // 6: token 0 out, human rolls again
        g.roll()                           // 2
        val before = g.state.value
        g.moveToken(0)                     // human move ends the turn
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        repeat(3) { g.playBotStep() }      // the computer rolls 1s and passes
        assertTrue(g.state.value!!.rollSerial > before!!.rollSerial)
        g.undo()
        assertEquals(before, g.state.value)
    }

    @Test fun undoIsRefusedOnceTheBoardOrSessionIsOver() {
        val g = newGame(start = false)
        val toks = yard(2); toks[0] = 56; toks[1] = 56; toks[2] = 56; toks[3] = 53
        g.restore(snapshotJson(toks, humans(2), dice = 3, awaiting = true))
        g.moveToken(3)
        g.leaveSession()
        assertFalse(g.canUndo)
        g.undo()
        assertTrue(g.state.value!!.boardOver)
    }

    // ---- hint ---------------------------------------------------------------------------------------

    @Test fun hintPointsAtALegalTokenOnlyAfterTheRoll() {
        val g = newGame(random = ScriptedRandom(6))
        assertNull(g.hint())
        g.roll()
        val h = g.hint()
        assertNotNull(h)
        assertTrue(h in g.legalTokens())
        assertEquals(h, g.hintToken.value)
        g.moveToken(h!!)
        assertNull(g.hintToken.value)
        assertEquals(h, runBlocking { newGame(random = ScriptedRandom(6)).also { it.roll() }.requestHint() })
    }

    @Test fun hintStaysOffForTheComputerAndNetworkedPlay() {
        val g = newGame(listOf(PlayerInfo("cpu", "CPU", true), PlayerInfo("you", "You")), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        assertNull(g.hint())
    }

    // ---- computer -----------------------------------------------------------------------------------

    @Test fun computerOnlyGamesFinishAtEveryLevelAndSeatCount() {
        for (seats in 2..4) for (d in CpuDifficulty.values()) {
            val players = List(seats) { PlayerInfo("b$it", "B$it", isBot = true) }
            val g = newGame(players, Random(seats * 10 + d.ordinal), PlayMode.SINGLE_PLAYER_VS_BOT)
            g.difficulty = d
            var guard = 0
            while (!g.state.value!!.boardOver && guard++ < 20000) {
                g.playBotStep()
                val s = g.state.value!!
                assertTrue(LudoRules.isConsistent(s.tokens.toIntArray(), s.players.map { it.color }))
            }
            assertTrue("$seats seats $d never finished", g.state.value!!.boardOver)
            assertNotNull(g.state.value!!.winnerPlayerId)
        }
    }

    @Test fun playBotTurnAsyncRollsThenMovesAndIgnoresHumanTurns() {
        val g = newGame(vsCpu, ScriptedRandom(6, 6, 3), PlayMode.SINGLE_PLAYER_VS_BOT)
        runBlocking { g.playBotTurnAsync() }
        assertEquals(0, g.state.value!!.rollSerial) // the human has the move
        g.roll() // 6
        g.moveToken(0)
        g.roll() // 6 again, 2nd six
        g.moveToken(0)
        g.roll() // 3, no move for the second token... token 0 moves 3
        g.moveToken(0)
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        runBlocking { g.playBotTurnAsync() } // CPU rolls
        assertTrue(g.state.value!!.rollSerial >= 4)
    }

    @Test fun hardBeatsEasyMoreOftenThanNot() {
        var hardWins = 0
        val games = 24
        for (i in 0 until games) {
            val hardFirst = i % 2 == 0
            val players = listOf(PlayerInfo("h", "H", true), PlayerInfo("e", "E", true))
            val g = LudoGame(Random(500 + i))
            g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, players, 0, LocalPassAndPlayTransport()))
            g.firstOpenerIndex = if (hardFirst) 0 else 1
            g.startMatch()
            var guard = 0
            while (!g.state.value!!.boardOver && guard++ < 20000) {
                val s = g.state.value!!
                g.difficulty = if (s.currentPlayerIndex == 0) CpuDifficulty.HARD else CpuDifficulty.EASY
                g.playBotStep()
            }
            if (g.state.value!!.winnerPlayerId == "h") hardWins++
        }
        assertTrue("Hard won only $hardWins of $games", hardWins > games / 2)
    }

    // ---- fuzz ---------------------------------------------------------------------------------------

    @Test fun randomLegalPlayNeverThrowsAndKeepsEveryInvariant() {
        val rnd = Random(31337)
        repeat(30) { gameNo ->
            val seats = 2 + gameNo % 3
            val g = newGame(humans(seats), Random(gameNo), needSix = gameNo % 2 == 0, exactFinish = gameNo % 3 != 0)
            var guard = 0
            while (!g.state.value!!.boardOver && guard++ < 4000) {
                val s = g.state.value!!
                if (!s.awaitingMove) assertTrue(g.roll()) else {
                    val legal = g.legalTokens()
                    assertTrue(legal.isNotEmpty())
                    assertTrue(g.moveToken(legal[rnd.nextInt(legal.size)]))
                }
                val a = g.state.value!!
                assertTrue(LudoRules.isConsistent(a.tokens.toIntArray(), a.players.map { it.color }))
                assertEquals(seats * 4, a.tokens.size)
                if (guard % 13 == 0) {
                    val g2 = newGame(humans(seats), start = false)
                    assertTrue(g2.restore(g.snapshot()!!))
                    assertEquals(a, g2.state.value)
                }
                if (guard % 7 == 0 && g.canUndo) {
                    g.undo()
                    val b = g.state.value!!
                    assertTrue(LudoRules.isConsistent(b.tokens.toIntArray(), b.players.map { it.color }))
                }
            }
        }
    }

    @Test fun garbageNeverCrashesRestore() {
        val g = newGame(random = ScriptedRandom(6))
        g.roll()
        val good = g.snapshot()!!
        val rnd = Random(3)
        repeat(200) {
            val cut = rnd.nextInt(good.length)
            val mutated = if (it % 2 == 0) good.substring(0, cut) else good.replaceRange(cut, minOf(good.length, cut + 2), "7\"")
            g.restore(mutated)
        }
    }

    @Test fun abortEndsWithoutScores() {
        val g = newGame()
        var r: GameResult? = null
        g.setOnMatchEnd { r = it }
        g.abortMatch()
        assertTrue(r!!.wasAborted && r!!.scores.isEmpty())
    }
}
