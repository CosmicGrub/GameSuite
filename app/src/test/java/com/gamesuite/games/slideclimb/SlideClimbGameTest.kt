package com.gamesuite.games.slideclimb

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SlideClimbGameTest {
    private fun SlideClimbGame.s() = state.value!!

    // ---- setup ----

    @Test fun aNewBoardStartsEveryPieceOffTheTrack() {
        val g = scNewGame(scHumans(3))
        assertEquals(listOf(0, 0, 0), g.s().positions)
        assertEquals(0, g.s().currentPlayerIndex)
        assertEquals(SlideClimbEvent.START, g.s().event)
        assertFalse(g.s().over)
        assertEquals("chutes-race", g.gameId)
        assertEquals(2, g.minPlayers); assertEquals(4, g.maxPlayers)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oneSeatIsNotAGame() {
        scNewGame(scHumans(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fiveSeatsAreTooMany() {
        scNewGame(scHumans(5))
    }

    @Test fun twoToFourSeatsAreAccepted() {
        for (n in 2..4) assertEquals(n, scNewGame(scHumans(n)).s().positions.size)
    }

    @Test fun anyMixOfPeopleAndComputersIsAccepted() {
        val g = scNewGame(listOf(PlayerInfo("p1", "You"), PlayerInfo("bot1", "CPU", isBot = true), PlayerInfo("p2", "Player 2")))
        assertEquals(listOf(false, true, false), g.s().players.map { it.isBot })
    }

    @Test fun theFirstSeatCanBeChosenAndTheNextBoardRotates() {
        val g = scNewGame(scHumans(3)) { firstOpenerIndex = 2 }
        assertEquals(2, g.s().currentPlayerIndex)
        g.playAgain()
        assertEquals(0, g.s().currentPlayerIndex)
    }

    // ---- turns ----

    @Test fun aRollMovesThePieceAndPassesTheTurn() {
        val g = scNewGame(random = SlideClimbScriptedRandom(3))
        assertTrue(g.roll())
        assertEquals(listOf(3, 0), g.s().positions)
        assertEquals(1, g.s().currentPlayerIndex)
        assertEquals(SlideClimbEvent.MOVE, g.s().event)
        assertEquals(0, g.s().eventFrom); assertEquals(3, g.s().eventLanded); assertEquals(3, g.s().eventTo)
        assertEquals(3, g.s().dice)
        assertEquals(1, g.s().rollSerial); assertEquals(1, g.s().moveSerial)
    }

    @Test fun aSixGivesNoExtraTurn() {
        val g = scNewGame(random = SlideClimbScriptedRandom(6))
        g.roll()
        assertEquals(1, g.s().currentPlayerIndex)
        assertEquals(listOf(6, 0), g.s().positions)
    }

    @Test fun turnsGoRoundTheTable() {
        val g = scNewGame(scHumans(3), SlideClimbScriptedRandom(1, 2, 3))
        val order = ArrayList<Int>()
        repeat(7) { order += g.s().currentPlayerIndex; g.roll() }
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), order)
    }

    @Test fun landingOnARopeClimbs() {
        val g = scNewGame(random = SlideClimbScriptedRandom(5))
        g.roll()
        assertEquals(16, g.s().positions[0])
        assertEquals(SlideClimbEvent.CLIMB, g.s().event)
        assertEquals(5, g.s().eventLanded); assertEquals(16, g.s().eventTo)
    }

    @Test fun landingOnASlideSlidesDown() {
        // seat 0 rolls 6, 6, 6 (1 for seat 1 in between): 6, 12, 18 -> slide to 6
        val g = scNewGame(random = SlideClimbScriptedRandom(6, 1, 6, 1, 6))
        repeat(5) { g.roll() }
        assertEquals(6, g.s().positions[0])
        assertEquals(2, g.s().positions[1])
        assertEquals(SlideClimbEvent.SLIDE, g.s().event)
        assertEquals(18, g.s().eventLanded); assertEquals(6, g.s().eventTo)
    }

    @Test fun reachingOneHundredWins() {
        val g = scNewGame(random = SlideClimbScriptedRandom(3))
        g.forcePositions(listOf(97, 0))
        g.roll()
        assertTrue(g.s().over)
        assertEquals("p1", g.s().winnerPlayerId)
        assertEquals(SlideClimbEvent.WIN, g.s().event)
        assertEquals(100, g.s().positions[0])
        assertEquals(0, g.s().currentPlayerIndex)   // the winner stays on turn: nobody else may roll
        assertEquals(mapOf("p1" to 1, "p2" to 0), g.sessionWins.value)
    }

    @Test fun nobodyMayRollOnceTheBoardIsWon() {
        val g = scNewGame(random = SlideClimbScriptedRandom(3))
        g.forcePositions(listOf(97, 0))
        g.roll()
        assertEquals(SlideClimbReject.OVER, g.explainRoll())
        assertFalse(g.roll())
        assertEquals(1, g.rollLog.size)
    }

    @Test fun overshootingBouncesBackByDefault() {
        val g = scNewGame(random = SlideClimbScriptedRandom(5))
        g.forcePositions(listOf(98, 0))
        g.roll()
        assertEquals(97, g.s().positions[0])
        assertEquals(SlideClimbEvent.BOUNCE, g.s().event)
        assertFalse(g.s().over)
        assertEquals(1, g.s().currentPlayerIndex)
    }

    @Test fun exactFinishNeedsTheExactRoll() {
        val g = scNewGame(random = SlideClimbScriptedRandom(5, 1, 2)) { exactFinish = true }
        g.forcePositions(listOf(98, 0))
        g.roll()                                       // 98 + 5 is too many: stays
        assertEquals(98, g.s().positions[0])
        assertEquals(SlideClimbEvent.STUCK, g.s().event)
        assertEquals(1, g.s().currentPlayerIndex)
        g.roll()                                       // seat 1 rolls 1
        g.roll()                                       // seat 0 rolls 2: 100
        assertTrue(g.s().over)
        assertEquals("p1", g.s().winnerPlayerId)
    }

    @Test fun theFinishingModeIsChosenPerBoard() {
        val g = scNewGame { exactFinish = true }
        assertTrue(g.s().exactFinish)
        g.exactFinish = false
        g.playAgain()
        assertFalse(g.s().exactFinish)
    }

    @Test fun diceComeOnlyFromTheInjectedRandom() {
        val dice = SlideClimbScriptedRandom(2, 4)
        val g = scNewGame(random = dice)
        g.roll(); g.roll()
        assertEquals(2, dice.calls)
        assertEquals(listOf(2, 4), g.rollLog)
    }

    @Test fun aSeededRandomReplaysAGameExactly() {
        fun play(): Pair<List<Int>, List<Int>> {
            val g = scNewGame(scBots(4), Random(2024)) { difficulty = CpuDifficulty.HARD; handicapSeats = setOf(1, 3) }
            g.playBotsToTheEnd()
            return g.rollLog to g.s().positions
        }
        assertEquals(play(), play())
    }

    @Test fun differentSeedsPlayDifferentGames() {
        val a = scNewGame(scBots(2), Random(1)).also { it.playBotsToTheEnd() }.rollLog
        val b = scNewGame(scBots(2), Random(2)).also { it.playBotsToTheEnd() }.rollLog
        assertTrue(a != b)
    }

    @Test fun aSeededLayoutIsUsedWhenAsked() {
        val g = scNewGame { layoutSeed = 7L }
        assertEquals(SlideClimbLayout.generate(7L), g.s().layout)
        val d = scNewGame()
        assertEquals(SlideClimbLayout.DEFAULT, d.s().layout)
    }

    // ---- why a tap does nothing ----

    @Test fun explainRollSaysWhy() {
        val g = scNewGame(listOf(PlayerInfo("bot1", "CPU", isBot = true), PlayerInfo("p1", "You")))
        assertEquals(SlideClimbReject.NOT_YOUR_TURN, g.explainRoll())
        assertFalse(g.roll())
        assertEquals(0, g.rollLog.size)
    }

    @Test fun keepAndSpareAreRefusedWhenNothingIsPending() {
        val g = scNewGame()
        assertEquals(SlideClimbReject.NOTHING_TO_DECIDE, g.explainDecision(spare = false))
        assertEquals(SlideClimbReject.NOTHING_TO_DECIDE, g.explainDecision(spare = true))
        assertFalse(g.keepRoll()); assertFalse(g.spareRoll())
    }

    // ---- computer seats ----

    @Test fun aComputerSeatRollsOnItsOwn() {
        val g = scNewGame(listOf(PlayerInfo("p1", "You"), PlayerInfo("bot1", "CPU", isBot = true)), SlideClimbScriptedRandom(2, 3))
        assertFalse(g.botToMove())
        g.roll()
        assertTrue(g.botToMove())
        g.playBotStep()
        assertEquals(listOf(2, 3), g.s().positions)
        assertEquals(0, g.s().currentPlayerIndex)
        assertFalse(g.botToMove())
    }

    @Test fun playBotStepDoesNothingOnAPersonsTurn() {
        val g = scNewGame()
        val before = g.s()
        g.playBotStep()
        assertSame(before, g.s())
    }

    @Test fun computersNeverStall_everyLevelEveryRuleEverySeatCount() {
        for (level in CpuDifficulty.values()) for (exact in listOf(false, true)) for (seats in 2..4) for (handicap in listOf(emptySet<Int>(), setOf(0), (0 until seats).toSet())) {
            for (seed in 1..6) {
                val g = scNewGame(scBots(seats), Random(seed * 100 + seats)) {
                    difficulty = level; exactFinish = exact; handicapSeats = handicap
                }
                val steps = g.playBotsToTheEnd()
                assertTrue(steps > 0)
                assertTrue(g.s().over)
                assertNotNull(g.s().winnerPlayerId)
            }
        }
    }

    @Test fun aComputerWithASpareRollSpendsItOnABadRoll() {
        // seat 0 (a computer, medium) is on 14 and rolls 4: that would slide 18 -> 6, so it spends the spare and rolls 6 -> 20
        val g = scNewGame(listOf(PlayerInfo("bot1", "CPU", isBot = true), PlayerInfo("p2", "Player 2")), SlideClimbScriptedRandom(4, 6)) {
            difficulty = CpuDifficulty.MEDIUM; handicapSeats = setOf(0)
        }
        g.forcePositions(listOf(14, 0))
        g.playBotStep()
        assertTrue(g.s().pending)
        assertEquals(14, g.s().positions[0])
        g.playBotStep()
        assertFalse(g.s().pending)
        assertEquals(20, g.s().positions[0])
        assertEquals(0, g.s().rerolls[0])
        assertTrue(g.s().eventRerolled)
    }

    @Test fun aComputerKeepsAGoodRollEvenWithASpare() {
        val g = scNewGame(listOf(PlayerInfo("bot1", "CPU", isBot = true), PlayerInfo("p2", "Player 2")), SlideClimbScriptedRandom(5, 6)) {
            difficulty = CpuDifficulty.HARD; handicapSeats = setOf(0)
        }
        g.playBotStep()            // rolls 5: a rope to 16
        g.playBotStep()            // keeps it
        assertEquals(16, g.s().positions[0])
        assertEquals(1, g.s().rerolls[0])
        assertEquals(1, g.rollLog.size)
    }

    // ---- handicap: the spare roll ----

    @Test fun aHandicapSeatMayKeepOrSwapItsRoll() {
        val g = scNewGame(random = SlideClimbScriptedRandom(2, 6)) { handicapSeats = setOf(0) }
        assertEquals(listOf(1, 0), g.s().rerolls)
        g.roll()
        assertTrue(g.s().pending)
        assertEquals(SlideClimbEvent.ROLL, g.s().event)
        assertEquals(listOf(0, 0), g.s().positions)        // nothing has moved yet
        assertEquals(0, g.s().currentPlayerIndex)
        assertEquals(2, g.s().dice)
        assertTrue(g.spareRoll())
        assertFalse(g.s().pending)
        assertEquals(listOf(6, 0), g.s().positions)         // the second roll counts, not the first
        assertEquals(listOf(0, 0), g.s().rerolls)
        assertEquals(listOf(2, 6), g.rollLog)
        assertTrue(g.s().eventRerolled)
        assertEquals(1, g.s().currentPlayerIndex)
    }

    @Test fun keepingTheRollMovesAndKeepsTheSpare() {
        val g = scNewGame(random = SlideClimbScriptedRandom(2)) { handicapSeats = setOf(0) }
        g.roll()
        assertTrue(g.keepRoll())
        assertEquals(listOf(2, 0), g.s().positions)
        assertEquals(listOf(1, 0), g.s().rerolls)
        assertFalse(g.s().eventRerolled)
        assertEquals(1, g.s().currentPlayerIndex)
    }

    @Test fun youMustChooseBeforeRollingAgain() {
        val g = scNewGame(random = SlideClimbScriptedRandom(2)) { handicapSeats = setOf(0) }
        g.roll()
        assertEquals(SlideClimbReject.DECIDE_FIRST, g.explainRoll())
        assertFalse(g.roll())
        assertEquals(1, g.rollLog.size)
    }

    @Test fun theSpareRollCanBeUsedOnlyOncePerBoard() {
        val g = scNewGame(random = SlideClimbScriptedRandom(2, 3, 4, 1)) { handicapSeats = setOf(0) }
        g.roll(); g.spareRoll()                    // seat 0: 2 swapped for 3
        g.roll()                                    // seat 1: 4
        g.roll()                                    // seat 0 again: 1, moves at once, no second choice
        assertFalse(g.s().pending)
        assertEquals(listOf(4, 4), g.s().positions)
        assertEquals(listOf(2, 3, 4, 1), g.rollLog)
    }

    @Test fun aSeatWithoutAHandicapMovesAtOnce() {
        val g = scNewGame(random = SlideClimbScriptedRandom(2, 3)) { handicapSeats = setOf(0) }
        g.roll(); g.keepRoll()
        g.roll()                                   // seat 1 has no spare roll
        assertFalse(g.s().pending)
        assertEquals(3, g.s().positions[1])
        assertEquals(0, g.s().currentPlayerIndex)
    }

    @Test fun noSpareRollMeansTheSpareButtonIsRefused() {
        val g = scNewGame(random = SlideClimbScriptedRandom(2)) { handicapSeats = setOf(1) }
        g.roll()                                   // seat 0 holds none: moved at once
        assertFalse(g.s().pending)
        assertEquals(1, g.s().currentPlayerIndex)
        g.roll()                                   // seat 1 holds one: pending
        assertTrue(g.s().pending)
        assertTrue(g.spareRoll())
        assertEquals(SlideClimbReject.NOTHING_TO_DECIDE, g.explainDecision(spare = true))
    }

    @Test fun everyoneCanHoldASpareRoll() {
        assertEquals(setOf(0, 1, 2), parseSlideClimbHandicap("all", 3))
        val g = scNewGame(scHumans(3)) { handicapSeats = parseSlideClimbHandicap("all", 3) }
        assertEquals(listOf(1, 1, 1), g.s().rerolls)
    }

    @Test fun theHandicapExtraIsParsedSafely() {
        assertEquals(emptySet<Int>(), parseSlideClimbHandicap(null, 4))
        assertEquals(emptySet<Int>(), parseSlideClimbHandicap("", 4))
        assertEquals(emptySet<Int>(), parseSlideClimbHandicap("none", 4))
        assertEquals(setOf(0), parseSlideClimbHandicap("1", 4))
        assertEquals(setOf(2), parseSlideClimbHandicap("3", 4))
        assertEquals(setOf(0, 3), parseSlideClimbHandicap("1, 4", 4))
        assertEquals(emptySet<Int>(), parseSlideClimbHandicap("4", 2))      // no such seat
        assertEquals(emptySet<Int>(), parseSlideClimbHandicap("0", 2))
        assertEquals(emptySet<Int>(), parseSlideClimbHandicap("banana", 2))
    }

    @Test fun aNewBoardGivesTheSpareRollBack() {
        val g = scNewGame(random = SlideClimbScriptedRandom(2, 3)) { handicapSeats = setOf(0) }
        g.roll(); g.spareRoll()
        assertEquals(listOf(0, 0), g.s().rerolls)
        g.playAgain()
        assertEquals(listOf(1, 0), g.s().rerolls)
    }

    @Test fun aWinningSpareRollWinsTheBoard() {
        val g = scNewGame(random = SlideClimbScriptedRandom(1, 3)) { handicapSeats = setOf(0) }
        g.forcePositions(listOf(97, 0))
        g.roll()                // 1: not enough
        assertTrue(g.spareRoll())   // 3: exactly 100
        assertTrue(g.s().over)
        assertEquals("p1", g.s().winnerPlayerId)
    }

    // ---- sessions ----

    @Test fun winsAreTalliedAcrossBoards() {
        val g = scNewGame(random = SlideClimbScriptedRandom(3))
        g.forcePositions(listOf(97, 0)); g.roll()
        g.playAgain()                                  // seat 1 opens the second board
        g.forcePositions(listOf(0, 97)); g.roll()
        assertEquals(mapOf("p1" to 1, "p2" to 1), g.sessionWins.value)
    }

    @Test fun leavingTheSessionRecordsTheLeader() {
        val g = scNewGame(random = SlideClimbScriptedRandom(3))
        g.forcePositions(listOf(97, 0)); g.roll()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertTrue(g.matchOver.value)
        val scores = result!!.scores.associate { it.playerId to it }
        assertTrue(scores.getValue("p1").isWinner)
        assertFalse(scores.getValue("p2").isWinner)
        assertFalse(result!!.wasAborted)
    }

    @Test fun abortingEndsWithoutScores() {
        val g = scNewGame()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }

    @Test fun noMoveAfterTheSessionEnds() {
        val g = scNewGame()
        g.abortMatch()
        assertFalse(g.roll())
        assertEquals(SlideClimbReject.OVER, g.explainRoll())
    }

    // ---- snapshot / restore ----

    private fun roster() = scHumans(3).let { it.dropLast(1) + PlayerInfo("bot1", "CPU", isBot = true) }

    private fun played(): SlideClimbGame {
        val g = scNewGame(roster(), Random(5)) { handicapSeats = setOf(0); exactFinish = true; difficulty = CpuDifficulty.HARD }
        g.roll(); g.keepRoll(); g.roll(); g.playBotStep()
        return g
    }

    @Test fun aSnapshotRestoresTheTableExactly() {
        val g = played()
        val text = g.snapshot()!!
        val twin = scNewGame(roster(), Random(99), start = false)
        assertTrue(twin.restore(text))
        assertEquals(g.s(), twin.s())
        assertEquals(g.rollLog, twin.rollLog)
        assertEquals(g.sessionWins.value, twin.sessionWins.value)
        assertEquals(CpuDifficulty.HARD, twin.difficulty)
        assertTrue(twin.exactFinish)
    }

    @Test fun aPendingRollSurvivesASnapshot() {
        val g = scNewGame(roster(), SlideClimbScriptedRandom(4)) { handicapSeats = setOf(0) }
        g.roll()
        assertTrue(g.s().pending)
        val twin = scNewGame(roster(), Random(1), start = false)
        assertTrue(twin.restore(g.snapshot()!!))
        assertTrue(twin.s().pending)
        assertEquals(g.s(), twin.s())
        assertTrue(twin.keepRoll())
        assertEquals(4, twin.s().positions[0])
    }

    @Test fun aRestoredGameKeepsPlayingFromWhereItStopped() {
        val g = played()
        val twin = scNewGame(roster(), SlideClimbScriptedRandom(3), start = false)
        assertTrue(twin.restore(g.snapshot()!!))
        assertEquals(0, twin.s().currentPlayerIndex)        // seat 0 is a person with a spare roll still in hand
        assertTrue(twin.roll())
        assertEquals(g.rollLog.size + 1, twin.rollLog.size)
        assertEquals(g.s().rollSerial + 1, twin.s().rollSerial)
        assertTrue(twin.s().pending)
    }

    @Test fun aWonBoardAndALayoutSnapshotRestoreToo() {
        val g = scNewGame(roster(), SlideClimbScriptedRandom(3)) { layoutSeed = 11L }
        g.forcePositions(listOf(97, 0, 0)); g.roll()
        val twin = scNewGame(roster(), Random(1), start = false)
        assertTrue(twin.restore(g.snapshot()!!))
        assertTrue(twin.s().over)
        assertEquals(SlideClimbLayout.generate(11L), twin.s().layout)
        assertEquals(g.s(), twin.s())
    }

    @Test fun thereIsNothingToSaveBeforeStartOrAfterTheEnd() {
        val g = scNewGame(start = false)
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.abortMatch()
        assertNull(g.snapshot())
    }

    @Test fun aSnapshotForAnotherTableIsRejected() {
        val text = played().snapshot()!!
        val otherIds = scNewGame(listOf(PlayerInfo("x1", "A"), PlayerInfo("x2", "B"), PlayerInfo("bot1", "CPU", isBot = true)), start = false)
        assertFalse(otherIds.restore(text))
        val fewer = scNewGame(scHumans(2), start = false)
        assertFalse(fewer.restore(text))
        val botsFlipped = scNewGame(scHumans(3), start = false)
        assertFalse(botsFlipped.restore(text))
    }

    // ---- corrupt snapshots ----

    private fun tamper(text: String, key: String, value: JsonElement): String =
        JsonObject(Json.parseToJsonElement(text).jsonObject + (key to value)).toString()

    private fun ints(vararg v: Int) = JsonArray(v.map { JsonPrimitive(it) })

    private fun rejected(text: String, label: String) {
        val fresh = scNewGame(roster(), start = false)
        assertFalse("accepted: $label", fresh.restore(text))
        assertNull("a rejected restore must leave the game untouched ($label)", fresh.state.value)
    }

    @Test fun corruptSnapshotsAreRejectedAndLeaveTheGameUntouched() {
        val good = played().snapshot()!!
        rejected("", "empty")
        rejected("not json", "garbage")
        rejected(good.dropLast(15), "truncated")
        rejected(tamper(good, "positions", ints(101, 0, 0)), "position past the finish")
        rejected(tamper(good, "positions", ints(-1, 0, 0)), "negative position")
        rejected(tamper(good, "positions", ints(0, 0)), "too few positions")
        rejected(tamper(good, "currentPlayerIndex", JsonPrimitive(7)), "turn out of range")
        rejected(tamper(good, "dice", JsonPrimitive(9)), "die out of range")
        rejected(tamper(good, "event", JsonPrimitive("explode")), "unknown event")
        rejected(tamper(good, "rerolls", ints(2, 0, 0)), "too many spare rolls")
        rejected(tamper(good, "rerolls", ints(1, 0)), "wrong number of spare rolls")
        rejected(tamper(good, "rollLog", ints(1, 7)), "a die of 7 in the log")
        rejected(tamper(good, "winnerPlayerId", JsonPrimitive("p1")), "a winner nobody reached 100 for")
        rejected(tamper(good, "over", JsonPrimitive(true)), "over without a winner")
        rejected(tamper(good, "eventFrom", JsonPrimitive(150)), "event square off the board")
        rejected(tamper(good, "nextStartingPlayerIndex", JsonPrimitive(-1)), "bad next starter")
        rejected(tamper(good, "sessionWins", JsonObject(mapOf("ghost" to JsonPrimitive(2)))), "wins for a stranger")
        rejected(tamper(good, "sessionWins", JsonObject(mapOf("p1" to JsonPrimitive(-2)))), "negative wins")
    }

    @Test fun aSnapshotWithABrokenLayoutIsRejected() {
        val good = played().snapshot()!!
        val twoRopesOnly = JsonArray(listOf(ints(5, 16), ints(13, 35)))
        rejected(tamper(good, "jumps", twoRopesOnly), "too few jumps")
        val chain = JsonArray(SlideClimbLayout.DEFAULT.jumps.map { ints(it.key, it.value) } + listOf(ints(16, 30)))
        rejected(tamper(good, "jumps", chain), "a chained jump")
        rejected(tamper(good, "jumps", JsonArray(listOf(JsonArray(listOf(JsonPrimitive(5)))))), "a jump with one number")
        val twice = JsonArray(SlideClimbLayout.DEFAULT.jumps.map { ints(it.key, it.value) } + listOf(ints(5, 20)))
        rejected(tamper(good, "jumps", twice), "the same foot twice")
    }

    @Test fun aMoveThatDoesNotFollowFromItsRollIsRejected() {
        val g = scNewGame(roster(), SlideClimbScriptedRandom(3))
        g.roll()
        val good = g.snapshot()!!
        rejected(tamper(good, "eventTo", JsonPrimitive(4)), "landed square does not match the roll")
        rejected(tamper(good, "event", JsonPrimitive("climb")), "event does not match the move")
        rejected(tamper(good, "positions", ints(9, 0, 0)), "piece is not where the move left it")
        rejected(tamper(good, "dice", JsonPrimitive(0)), "a move with no roll")
        rejected(tamper(good, "pending", JsonPrimitive(true)), "pending without a roll event")
    }

    @Test fun aPendingSnapshotMustHaveASpareRoll() {
        val g = scNewGame(roster(), SlideClimbScriptedRandom(4)) { handicapSeats = setOf(0) }
        g.roll()
        val good = g.snapshot()!!
        rejected(tamper(good, "rerolls", ints(0, 0, 0)), "pending with no spare roll")
        rejected(tamper(good, "dice", JsonPrimitive(0)), "pending with no roll")
    }

    @Test fun anUnknownExtraFieldIsIgnored() {
        val good = played().snapshot()!!
        val twin = scNewGame(roster(), start = false)
        assertTrue(twin.restore(tamper(good, "somethingNew", JsonPrimitive(1))))
    }

    @Test fun onlyTheLocalModesAreSupported() {
        val g = scNewGame(start = false)
        assertEquals(setOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes.toSet())
    }

    // ---- double taps, pause, snapshot turn pointer ----

    @Test fun aSecondTapBeforeTheScreenSettlesIsRefusedInsteadOfSkippingTheNextPlayersTurn() {
        val g = scNewGame(scHumans(2), SlideClimbScriptedRandom(3, 4)) { holdInputUntilSettled = true }
        assertTrue(g.roll())
        assertEquals(1, g.s().currentPlayerIndex)
        // the second finger / double activation lands before the hop animation is reported finished
        assertEquals(SlideClimbReject.BUSY, g.explainRoll())
        assertFalse(g.roll())
        assertEquals(listOf(3), g.rollLog)
        assertEquals(listOf(3, 0), g.s().positions)
        g.settled()
        assertEquals(SlideClimbReject.NONE, g.explainRoll())
        assertTrue(g.roll())
        assertEquals(listOf(3, 4), g.s().positions)
    }

    @Test fun keepAndSpareAreHeldWhileARollAnimates() {
        val g = scNewGame(random = SlideClimbScriptedRandom(2, 5)) { handicapSeats = setOf(0); holdInputUntilSettled = true }
        assertTrue(g.roll())
        assertEquals(SlideClimbReject.BUSY, g.explainDecision(spare = true))
        assertFalse(g.spareRoll()); assertFalse(g.keepRoll())
        g.settled()
        assertTrue(g.s().pending)
        assertTrue(g.keepRoll())
        assertFalse(g.spareRoll())
        assertEquals(SlideClimbReject.BUSY, g.explainRoll())
    }

    @Test fun inputIsNeverHeldUnlessTheScreenAsksForIt() {
        val g = scNewGame(random = SlideClimbScriptedRandom(3, 4))
        assertTrue(g.roll()); assertTrue(g.roll())
        assertEquals(listOf(3, 4), g.rollLog)
    }

    @Test fun aNewBoardAndARestoreClearTheHold() {
        val g = scNewGame(random = SlideClimbScriptedRandom(3)) { holdInputUntilSettled = true }
        g.roll()
        g.playAgain()
        assertEquals(SlideClimbReject.NONE, g.explainRoll())
    }

    @Test fun pausingStopsTheComputerAndResumingLetsItGoOn() {
        val g = scNewGame(listOf(PlayerInfo("p1", "You"), PlayerInfo("bot1", "CPU", isBot = true)), SlideClimbScriptedRandom(2, 3))
        g.roll()
        assertTrue(g.botToMove())
        g.pause()
        assertTrue(g.paused.value)
        val before = g.s()
        g.playBotStep()
        assertSame(before, g.s())
        g.resume()
        assertFalse(g.paused.value)
        g.playBotStep()
        assertEquals(listOf(2, 3), g.s().positions)
    }

    @Test fun aSnapshotThatHandsTheTurnToTheWrongSeatIsRejected() {
        val g = scNewGame(roster(), SlideClimbScriptedRandom(3))
        g.roll()                                          // seat 0 moved: seat 1 is next
        val good = g.snapshot()!!
        val twin = scNewGame(roster(), start = false)
        assertTrue(twin.restore(good))
        assertEquals(1, twin.s().currentPlayerIndex)
        rejected(tamper(good, "currentPlayerIndex", JsonPrimitive(0)), "the mover is on turn again")
        rejected(tamper(good, "currentPlayerIndex", JsonPrimitive(2)), "a seat is skipped")
    }

    @Test fun aWonBoardKeepsTheTurnOnTheWinnerAndNoOtherSeatIsAccepted() {
        val g = scNewGame(roster(), SlideClimbScriptedRandom(3))
        g.forcePositions(listOf(97, 0, 0)); g.roll()
        val good = g.snapshot()!!
        assertTrue(scNewGame(roster(), start = false).restore(good))
        rejected(tamper(good, "currentPlayerIndex", JsonPrimitive(1)), "turn moved on after a win")
    }

    @Test fun aPendingRollMustSitOnTheSeatsOwnSquare() {
        val g = scNewGame(roster(), SlideClimbScriptedRandom(4)) { handicapSeats = setOf(0) }
        g.roll()
        val good = g.snapshot()!!
        rejected(tamper(good, "eventTo", JsonPrimitive(9)), "pending roll event not on the seat's square")
        rejected(tamper(good, "eventFrom", JsonPrimitive(9)), "pending roll event starts elsewhere")
    }

    @Test fun serialsAndWinsAreBounded() {
        val good = played().snapshot()!!
        rejected(tamper(good, "rollSerial", JsonPrimitive(Int.MAX_VALUE)), "roll serial about to overflow")
        rejected(tamper(good, "moveSerial", JsonPrimitive(Int.MAX_VALUE)), "move serial about to overflow")
        rejected(tamper(good, "sessionWins", JsonObject(mapOf("p1" to JsonPrimitive(Int.MAX_VALUE)))), "wins about to overflow")
        val twin = scNewGame(roster(), start = false)
        assertTrue(twin.restore(tamper(good, "rollSerial", JsonPrimitive(1_000_000))))
    }

    @Test fun restoredSeatsTakeTheirNamesFromTheLiveRoster() {
        val good = played().snapshot()!!
        val renamed = listOf(PlayerInfo("p1", "Ada"), PlayerInfo("p2", "Bo"), PlayerInfo("bot1", "CPU", isBot = true))
        val twin = scNewGame(renamed, start = false)
        assertTrue(twin.restore(good))
        assertEquals(listOf("Ada", "Bo", "CPU"), twin.s().players.map { it.displayName })
        val oversized = tamper(good, "players", JsonArray(listOf("p1", "p2").map {
            JsonObject(mapOf("playerId" to JsonPrimitive(it), "displayName" to JsonPrimitive("x".repeat(5000)), "isBot" to JsonPrimitive(false)))
        } + JsonObject(mapOf("playerId" to JsonPrimitive("bot1"), "displayName" to JsonPrimitive("y"), "isBot" to JsonPrimitive(true)))))
        val twin2 = scNewGame(renamed, start = false)
        assertTrue(twin2.restore(oversized))
        assertEquals(listOf("Ada", "Bo", "CPU"), twin2.s().players.map { it.displayName })
    }
}
