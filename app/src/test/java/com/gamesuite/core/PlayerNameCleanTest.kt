package com.gamesuite.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerNameCleanTest {
    @Test fun trimsEnds() = assertEquals("Ann", cleanPlayerName("  Ann  ", "Player 1"))

    @Test fun collapsesInternalWhitespace() =
        assertEquals("Mary Jane Doe", cleanPlayerName("Mary   Jane \t Doe", "Player 1"))

    @Test fun capsAtSixteen() {
        val out = cleanPlayerName("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "Player 1")
        assertEquals("ABCDEFGHIJKLMNOP", out)
        assertEquals(16, out.length)
    }

    @Test fun capDoesNotLeaveTrailingSpace() =
        assertEquals("ABCDEFGHIJKLMNO", cleanPlayerName("ABCDEFGHIJKLMNO PQR", "Player 1"))

    @Test fun blankFallsBackToDefault() {
        assertEquals("Player 2", cleanPlayerName("", "Player 2"))
        assertEquals("Player 2", cleanPlayerName("   \t ", "Player 2"))
    }
}
