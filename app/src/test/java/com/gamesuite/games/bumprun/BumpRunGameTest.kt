package com.gamesuite.games.bumprun

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BumpRunGameTest {
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }

    @Test fun drawThenPlayMovesTheChosenPawn() {
        val g = brGame { fixedSeed = 7 }
        // draw until a card with a move arrives for the person on turn
        var guard = 0
        while (g.legalMoves().isEmpty() && guard++ < 200) {
            if (g.state.value!!.phase == BumpPhase.AWAIT_DRAW) assertTrue(g.draw()) else assertTrue(g.pass())
        }
        val move = g.legalMoves().first()
        val seat = g.state.value!!.currentPlayerIndex
        assertTrue(g.playMove(move))
        assertEquals((seat + 1) % 2, g.state.value!!.currentPlayerIndex)
    }

    @Test fun playingAnIllegalMoveIsRejectedWithAReason() {
        val g = brGame()
        assertEquals(BumpRunReject.DRAW_FIRST, g.explainMove(BumpMove(0, BumpMoveKind.STEP, 3)))
        assertFalse(g.playMove(BumpMove(0, BumpMoveKind.STEP, 3)))
    }

    @Test fun drawingTwiceIsRejected() {
        val g = brGame { fixedSeed = 3 }
        // find a seed whose first card has a move: loop seeds
        var seed = 0L
        while (true) {
            val probe = brGame { fixedSeed = seed }
            probe.draw()
            if (probe.state.value!!.phase == BumpPhase.AWAIT_CHOICE) {
                assertEquals(BumpRunReject.DECIDE_FIRST, probe.explainDraw())
                assertFalse(probe.draw())
                break
            }
            seed++
            check(seed < 200)
        }
        assertNotNull(g.state.value)
    }

    @Test fun chanceComesOnlyFromTheInjectedRandom() {
        fun run(): List<Int> {
            val g = brGame(random = Random(99))
            return g.state.value!!.pile
        }
        assertEquals(run(), run())
        assertTrue(run() != brGame(random = Random(100)).state.value!!.pile)
    }

    @Test fun firstOpenerIsHonouredAndPlayAgainRotatesTheStarter() {
        val g = brGame { firstOpenerIndex = 1 }
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        g.playAgain()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        g.playAgain()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test fun playAgainIsRefusedWhenAKidsTimeIsUp() {
        val g = brGame { fixedSeed = 1 }
        val before = g.state.value
        com.gamesuite.core.LaunchPolicy.timeUp = true
        try {
            g.playAgain()
            assertTrue(before === g.state.value)
        } finally {
            com.gamesuite.core.LaunchPolicy.resetToFailClosed()
        }
    }

    @Test fun leaveSessionRecordsTheBoardsWon() {
        val bots = List(2) { PlayerInfo("b${it + 1}", "CPU ${it + 1}", isBot = true) }
        val g = brGame(players = bots)
        var result: com.gamesuite.core.GameResult? = null
        g.setOnMatchEnd { result = it }
        var steps = 0
        while (g.state.value?.over != true && steps++ < 10_000) g.playBotStep()
        assertTrue(g.state.value!!.over)
        g.leaveSession()
        assertTrue(g.matchOver.value)
        val scores = result!!.scores
        assertEquals(1, scores.sumOf { it.score.toInt() })
        assertEquals(1, scores.count { it.isWinner })
    }

    @Test fun computerSeatNeverGetsAHumanTurn() {
        val g = brGame(players = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("b1", "CPU", isBot = true)), start = false) { firstOpenerIndex = 1 }
        g.startMatch()
        assertTrue(g.botToMove())
        assertFalse(g.isMyTurn())
        assertEquals(BumpRunReject.NOT_YOUR_TURN, g.explainDraw())
        assertFalse(g.draw())
        g.playBotStep()
        assertNotNull(g.state.value)
    }

    @Test fun sessionWinsAccumulateAcrossBoards() {
        val bots = List(2) { PlayerInfo("b${it + 1}", "CPU ${it + 1}", isBot = true) }
        val g = brGame(players = bots)
        repeat(3) {
            var steps = 0
            while (g.state.value?.over != true && steps++ < 10_000) g.playBotStep()
            g.playAgain()
        }
        assertEquals(3, g.sessionWins.value.values.sum())
    }

    @Test fun passIsOnlyAllowedWithNoLegalMove() {
        var seed = 0L
        while (seed < 300) {
            val g = brGame { fixedSeed = seed }
            g.draw()
            val s = g.state.value!!
            if (s.phase == BumpPhase.AWAIT_CHOICE && g.legalMoves().isNotEmpty()) {
                assertFalse(g.pass())
                return
            }
            seed++
        }
        error("no seed offered a move")
    }

    @Test fun pauseIsReportedAndResumeClearsIt() {
        val g = brGame()
        assertFalse(g.isPaused)
        g.pause()
        assertTrue(g.isPaused)
        g.resume()
        assertFalse(g.isPaused)
    }

    @Test fun anAcceptedActionBlocksTheNextTapUntilTheScreenSettles() {
        val g = brGame { fixedSeed = 11 }
        g.holdInputUntilSettled = true
        assertTrue(g.draw())
        assertEquals(BumpRunReject.BUSY, g.explainDraw())
        g.settled()
        assertNotEquals(BumpRunReject.BUSY, g.explainDraw())
    }

    private fun assertNotEquals(a: Any, b: Any) = assertTrue(a != b)

    @Test fun helperPackSeatsStartWithAPawnOutAndThreeDoOvers() {
        val g = brGame(players = humans(3)) { helperSeats = setOf(1) }
        val s = g.state.value!!
        assertEquals(0, s.pawns[1 * s.rules.pawnsPerSeat])
        assertEquals(listOf(0, 3, 0), s.doOvers)
        assertEquals(setOf(0, 2), parseBumpRunHelper("1,3", 3))
        assertEquals(setOf(0, 1, 2), parseBumpRunHelper("all", 3))
        assertEquals(emptySet<Int>(), parseBumpRunHelper("none", 3))
        assertEquals(emptySet<Int>(), parseBumpRunHelper("9", 3))
        assertNull(null)
    }
}
