package com.gamesuite.games.shogi

import org.junit.Assert.assertEquals
import org.junit.Test

class ShogiPerftTest {
    private val shogi = ShogiRules.of(ShogiVariant.STANDARD)
    private val mini = ShogiRules.of(ShogiVariant.MINI)

    @Test fun shogiStartPerftMatchesPublishedValues() {
        val p = shogi.startPosition()
        assertEquals(30L, shogi.perft(p, 1))
        assertEquals(900L, shogi.perft(p, 2))
        assertEquals(25470L, shogi.perft(p, 3))
    }

    @Test fun shogiStartPerftDepth4() {
        assertEquals(719731L, shogi.perft(shogi.startPosition(), 4))
    }

    @Test fun miniStartPerft() {
        val p = mini.startPosition()
        // Self-consistent values; they also match the published minishogi perft table.
        assertEquals(14L, mini.perft(p, 1))
        assertEquals(181L, mini.perft(p, 2))
        assertEquals(2512L, mini.perft(p, 3))
        assertEquals(35401L, mini.perft(p, 4))
    }
}
