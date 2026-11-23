package com.gamesuite.games.sixball

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SixBallGameTest {
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }

    private fun newGame(
        players: List<PlayerInfo> = humans(1), level: String = "c1", start: Boolean = true, clock: () -> Long = { 0L }
    ): SixBallGame {
        val mode = if (players.size == 1) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
        val g = SixBallGame(clock)
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        g.levelId = level
        if (start) g.startMatch()
        return g
    }

    private fun solveWith(g: SixBallGame) {
        val b = g.board.value!!
        for (m in SixBallRules.solve(b.level, b.positions.toIntArray())!!) assertTrue(g.tilt(m))
    }

    @Test fun startingLoadsTheLevelAtItsStartPosition() {
        val g = newGame(level = "c3")
        val b = g.board.value!!
        assertEquals("c3", b.level.id)
        assertEquals(b.level.start, b.positions)
        assertTrue(b.moves.isEmpty()); assertFalse(b.solved)
        assertFalse(g.canUndo)
    }

    @Test fun solvingTheTrayWithTheShortestPathEarnsThreeStars() {
        val g = newGame()
        solveWith(g)
        val b = g.board.value!!
        assertTrue(b.solved)
        assertEquals(b.level.par, b.moveCount)
        assertEquals(3, g.starsNow())
        assertEquals(setOf("c1"), g.solvedLevels.value)
        assertFalse("a solved tray takes no more tilts", g.tilt(Tilt.UP))
        assertEquals(SixBallReject.SOLVED, g.explainTilt(Tilt.LEFT))
        assertFalse("undo is closed once solved", g.canUndo)
    }

    @Test fun aTiltThatMovesNothingIsRefusedWithAReasonAndCostsNothing() {
        val g = newGame(level = "c2")
        val dir = Tilt.values().first { g.explainTilt(it) == SixBallReject.NONE }
        assertTrue(g.tilt(dir))
        val count = g.board.value!!.moveCount
        assertEquals(SixBallReject.NO_MOVE, g.explainTilt(dir))
        assertFalse(g.tilt(dir))
        assertEquals(count, g.board.value!!.moveCount)
    }

    @Test fun undoIsUnlimitedAndRestoresTheExactBoard() {
        val g = newGame(level = "c40")
        val r = Random(2)
        val seen = ArrayList<SixBallBoard>()
        var n = 0
        while (n < 60) {
            val dir = Tilt.values()[r.nextInt(4)]
            if (g.board.value!!.solved) break
            val before = g.board.value!!
            if (g.tilt(dir)) { seen += before; n++ }
        }
        assertTrue(n >= 30)
        while (seen.isNotEmpty()) {
            assertTrue(g.canUndo)
            g.undo()
            val want = seen.removeAt(seen.size - 1)
            assertEquals(want.moves, g.board.value!!.moves)
            assertEquals(want.positions, g.board.value!!.positions)
        }
        assertFalse(g.canUndo)
        assertEquals(g.board.value!!.level.start, g.board.value!!.positions)
    }

    @Test fun restartGoesBackToTheStartAndReplayAfterASolveWorks() {
        val g = newGame()
        solveWith(g)
        assertTrue(g.restartLevel())
        assertTrue(g.board.value!!.moves.isEmpty()); assertFalse(g.board.value!!.solved)
        assertEquals(SixBallBoard.KIND_RESTART, g.board.value!!.kind)
        assertFalse(g.restartLevel()) // already at the start
    }

    @Test fun theHintIsTheFirstTiltOfAShortestSolutionAndCapsStarsAtTwo() {
        val g = newGame(level = "c4")
        val first = SixBallRules.solve(g.board.value!!.level)!!.first()
        assertEquals(first, runBlocking { g.requestHint() })
        assertEquals(first, g.hintMove.value)
        assertEquals(1, g.hintsUsed.value)
        g.tilt(first); assertNull(g.hintMove.value)
        solveWith(g)
        assertEquals(2, g.starsNow())
    }

    @Test fun nextLevelFollowsTheCampaignAndTheLastOneHasNoNext() {
        val g = newGame(level = "c1")
        assertEquals("c2", g.nextLevelId())
        assertTrue(g.startLevel("c2")); assertEquals("c2", g.board.value!!.level.id)
        assertFalse(g.startLevel("c999"))
        assertEquals("c2", g.board.value!!.level.id)
        g.startLevel(SixBallLevels.campaignId(SixBallLevels.CAMPAIGN_COUNT))
        assertNull(g.nextLevelId())
        g.startLevel(SixBallLevels.dailyId(20_000))
        assertNull(g.nextLevelId())
    }

    @Test fun leavingScoresTheLevelsSolvedAndAbortsWhenNoneWere() {
        var result: GameResult? = null
        val g = newGame(); g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertTrue(result!!.wasAborted)
        val g2 = newGame(); g2.setOnMatchEnd { result = it }
        solveWith(g2); g2.leaveSession()
        assertFalse(result!!.wasAborted)
        assertEquals(listOf(1), result!!.scores.map { it.score }); assertTrue(result!!.scores[0].isWinner)
    }

    // ---- resume ----------------------------------------------------------------------------

    @Test fun snapshotsRoundTripAtEveryStepOfARandomPlaythrough() {
        val r = Random(8)
        for (level in listOf("c1", "c15", "c33", SixBallLevels.dailyId(20_003))) {
            val g = newGame(level = level)
            var steps = 0
            while (steps++ < 60 && !g.board.value!!.solved) {
                val snap = g.snapshot()
                assertNotNull(snap)
                val twin = newGame(level = "c1", start = false)
                assertTrue("restore at $level step $steps", twin.restore(snap!!))
                assertEquals(g.board.value!!.moves, twin.board.value!!.moves)
                assertEquals(g.board.value!!.positions, twin.board.value!!.positions)
                assertEquals(snap, twin.snapshot())
                g.tilt(Tilt.values()[r.nextInt(4)])
                if (r.nextInt(10) == 0) g.undo()
            }
        }
    }

    @Test fun aRestoredGameKeepsItsUndoHistoryAndItsTally() {
        val g = newGame(level = "c6")
        val dir = Tilt.values().first { g.explainTilt(it) == SixBallReject.NONE }
        g.tilt(dir)
        runBlocking { g.requestHint() }
        val twin = newGame(level = "c1", start = false)
        assertTrue(twin.restore(g.snapshot()!!))
        assertEquals(1, twin.hintsUsed.value)
        assertTrue(twin.canUndo)
        twin.undo()
        assertTrue(twin.board.value!!.moves.isEmpty())
        assertEquals("c6", twin.levelId)
    }

    @Test fun tamperedOrMismatchedSavesAreRefusedAndLeaveTheGameUntouched() {
        val g = newGame(level = "c2")
        val dir = Tilt.values().first { g.explainTilt(it) == SixBallReject.NONE }
        g.tilt(dir)
        val snap = g.snapshot()!!
        fun refuses(text: String, game: SixBallGame = newGame(start = false)) {
            assertFalse(text, game.restore(text))
            assertNull(game.board.value)
        }
        refuses("")
        refuses("{}")
        refuses("garbage")
        refuses(snap.replace("\"levelId\":\"c2\"", "\"levelId\":\"c99\""))
        refuses(snap.replace("\"levelId\":\"c2\"", "\"levelId\":\"nope\""))
        refuses(snap.replace(Regex("\"moves\":\\[[^]]*]"), "\"moves\":[9]"))                // not a tilt code
        refuses(snap.replace(Regex("\"moves\":\\[[^]]*]"), "\"moves\":[${dir.ordinal},${dir.ordinal}]")) // the second moves nothing
        refuses(snap.replace(Regex("\"moves\":\\[[^]]*]"), "\"moves\":[" + (1..2000).joinToString(",") { "0" } + "]"))
        refuses(snap.replace("\"hintsUsed\":0", "\"hintsUsed\":-4").takeIf { it != snap } ?: "x")
        refuses(snap.replace("\"p1\"", "\"someone\""))
        refuses(snap, newGame(humans(2), start = false))                                  // a different roster
        val net = SixBallGame().also { it.init(GameContext(PlayMode.ONLINE, humans(2), 0, LocalPassAndPlayTransport())) }
        assertFalse(net.restore(snap)); assertNull(net.snapshot())
    }

    @Test fun noSnapshotBeforeStartOrAfterTheEnd() {
        val g = newGame(start = false)
        assertNull(g.snapshot())
        g.startMatch(); assertNotNull(g.snapshot())
        g.endMatch(GameResult()); assertNull(g.snapshot())
    }

    // ---- pass and play race ----------------------------------------------------------------------

    @Test fun inPassAndPlayEachRacerPlaysTheSameTrayInTurnAndTheFewestTiltsWins() {
        val g = newGame(humans(2), level = "c3")
        val r0 = g.race.value!!
        assertEquals(listOf(SixBallStatus.PLAYING, SixBallStatus.WAITING), r0.racers.map { it.status })
        solveWith(g)                                    // player 1 solves in par tilts
        var r = g.race.value!!
        assertTrue(r.awaitingHandoff); assertFalse(r.raceOver)
        assertEquals(SixBallReject.HANDOFF, g.explainTilt(Tilt.UP))
        assertFalse(g.tilt(Tilt.UP))
        assertTrue(g.beginNextRacer())
        assertTrue("the next racer starts from the start", g.board.value!!.moves.isEmpty())
        assertEquals(1, g.race.value!!.currentPlayerIndex)
        // player 2 takes a detour: a tilt that is not the first step of a shortest path, then solves
        val level = g.board.value!!.level
        val best = SixBallRules.solve(level)!!
        val detour = Tilt.values().firstOrNull { d ->
            d != best.first() && g.explainTilt(d) == SixBallReject.NONE &&
                SixBallRules.solve(level, SixBallRules.tilt(level, level.start.toIntArray(), d)) != null
        }
        if (detour != null) { assertTrue(g.tilt(detour)); solveWith(g) } else g.giveUp()
        r = g.race.value!!
        assertTrue(r.raceOver)
        val p1 = r.racers[0]; val p2 = r.racers[1]
        assertEquals(level.par, p1.moves)
        assertTrue(p2.status == SixBallStatus.GAVE_UP || p2.moves > p1.moves || p2.moves == p1.moves)
        val expected = if (p2.status == SixBallStatus.SOLVED && p2.moves == p1.moves) listOf("p1", "p2") else listOf("p1")
        assertEquals(expected, r.winnerIds)
    }

    @Test fun equalTiltCountsShareTheWinAndGivingUpLosesToAnySolve() {
        val g = newGame(humans(3), level = "c2")
        solveWith(g); g.beginNextRacer()
        solveWith(g); g.beginNextRacer()
        assertTrue(g.giveUp())
        val r = g.race.value!!
        assertTrue(r.raceOver)
        assertEquals(listOf("p1", "p2"), r.winnerIds)
        assertEquals(SixBallStatus.GAVE_UP, r.racers[2].status)
        assertEquals(listOf("p1", "p2", "p3"), SixBallRaceRules.ranking(r.racers).map { it.playerId })
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertEquals(listOf(true, true, false), result!!.scores.map { it.isWinner })
    }

    @Test fun everyoneGivingUpMeansNoWinnerAndLeavingMidRaceIsAnAbort() {
        val g = newGame(humans(2), level = "c1")
        g.giveUp(); g.beginNextRacer(); g.giveUp()
        assertTrue(g.race.value!!.raceOver); assertTrue(g.race.value!!.winnerIds.isEmpty())
        val g2 = newGame(humans(2), level = "c1")
        var result: GameResult? = null
        g2.setOnMatchEnd { result = it }
        g2.leaveSession()
        assertTrue(result!!.wasAborted)
    }

    @Test fun racesHaveNoHintsAndPassAndPlayResumesMidRace() {
        val g = newGame(humans(2), level = "c2")
        assertNull(runBlocking { g.requestHint() })
        solveWith(g)
        for (stage in 0..2) {
            val snap = g.snapshot()!!
            val twin = newGame(humans(2), start = false)
            assertTrue(twin.restore(snap))
            assertEquals(g.race.value, twin.race.value)
            assertEquals(g.board.value!!.moves, twin.board.value!!.moves)
            assertEquals(snap, twin.snapshot())
            when (stage) {
                0 -> g.beginNextRacer()
                1 -> g.tilt(Tilt.values().first { g.explainTilt(it) == SixBallReject.NONE })
            }
        }
    }

    @Test fun playAgainInARaceMovesOnToTheNextCampaignLevel() {
        val g = newGame(humans(2), level = "c5")
        g.giveUp(); g.beginNextRacer(); g.giveUp()
        g.playAgain()
        assertEquals("c6", g.board.value!!.level.id)
        assertEquals(2, g.race.value!!.round)
        assertFalse(g.race.value!!.raceOver)
    }
}
