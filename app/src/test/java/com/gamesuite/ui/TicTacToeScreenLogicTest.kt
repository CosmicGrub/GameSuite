package com.gamesuite.ui

import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure decisions TicTacToeScreen makes from game state: which seat this device plays, who
 * won a finished round (Misere inverts it), and the result headline. They drive both the text
 * and the win/loss feedback, so a wrong seat or a missed Misere inversion would celebrate the
 * wrong player.
 */
class TicTacToeScreenLogicTest {

    private val human = PlayerInfo(playerId = "p1", displayName = "You")
    private val cpu = PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
    private val sam = PlayerInfo(playerId = "p2", displayName = "Sam")
    private val line = listOf(0, 1, 2)

    // ---- localSeatFor ----

    @Test
    fun vsCpuSeatIsTheHumansSeat() {
        assertEquals(1, localSeatFor(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(human, cpu), 0))
        assertEquals(2, localSeatFor(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(cpu, human), 1))
    }

    @Test
    fun passAndPlayHasNoSingleLocalSeat() {
        assertNull(localSeatFor(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(human, sam), 0))
    }

    @Test
    fun networkedSeatFollowsLocalPlayerIndex() {
        for (mode in listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)) {
            assertEquals(1, localSeatFor(mode, listOf(human, sam), 0))
            assertEquals(2, localSeatFor(mode, listOf(human, sam), 1))
        }
    }

    @Test
    fun networkedSpectatorHasNoSeat() {
        assertNull(localSeatFor(PlayMode.ONLINE, listOf(human, sam), -1))
        assertNull(localSeatFor(PlayMode.LOCAL_AD_HOC, listOf(human, sam), 2))
    }

    @Test
    fun noContextHasNoSeat() {
        assertNull(localSeatFor(null, emptyList(), -1))
    }

    // ---- roundWinnerSeat ----

    @Test
    fun aDrawHasNoWinner() {
        assertNull(roundWinnerSeat(null, moverSeat = 1, misere = false))
        assertNull(roundWinnerSeat(null, moverSeat = 2, misere = true))
    }

    @Test
    fun standardWinnerIsTheMover() {
        assertEquals(1, roundWinnerSeat(line, moverSeat = 1, misere = false))
        assertEquals(2, roundWinnerSeat(line, moverSeat = 2, misere = false))
    }

    @Test
    fun misereWinnerIsTheOtherSeat() {
        assertEquals(2, roundWinnerSeat(line, moverSeat = 1, misere = true))
        assertEquals(1, roundWinnerSeat(line, moverSeat = 2, misere = true))
    }

    // ---- roundResultText ----

    @Test
    fun aDrawIsAlwaysWordedAsADraw() {
        assertEquals("It's a draw!", roundResultText(null, 1, misere = false, localSeat = 1, p1Name = "You", p2Name = "CPU"))
        assertEquals("It's a draw!", roundResultText(null, 2, misere = true, localSeat = null, p1Name = "You", p2Name = "Sam"))
    }

    @Test
    fun standardVsCpuIsWordedFromTheLocalSeat() {
        assertEquals("You win!", roundResultText(line, 1, misere = false, localSeat = 1, p1Name = "You", p2Name = "CPU"))
        assertEquals("CPU wins!", roundResultText(line, 2, misere = false, localSeat = 1, p1Name = "You", p2Name = "CPU"))
    }

    @Test
    fun misereVsCpuDescribesTheMoversSelfInflictedLoss() {
        assertEquals(
            "You made a line - you lose!",
            roundResultText(line, 1, misere = true, localSeat = 1, p1Name = "You", p2Name = "CPU")
        )
        assertEquals(
            "CPU made a line - you win!",
            roundResultText(line, 2, misere = true, localSeat = 1, p1Name = "You", p2Name = "CPU")
        )
    }

    @Test
    fun passAndPlayIsWordedByName() {
        assertEquals("Sam wins!", roundResultText(line, 2, misere = false, localSeat = null, p1Name = "Ann", p2Name = "Sam"))
        assertEquals(
            "Sam made a line and loses!",
            roundResultText(line, 2, misere = true, localSeat = null, p1Name = "Ann", p2Name = "Sam")
        )
    }
}
