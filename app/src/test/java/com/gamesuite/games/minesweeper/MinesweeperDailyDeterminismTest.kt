package com.gamesuite.games.minesweeper

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinesweeperDailyDeterminismTest {
    private fun game(d: CpuDifficulty): MinesweeperGame {
        var t = 0L
        val g = MinesweeperGame(nowMillis = { t++ })
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "P1")), 0, LocalPassAndPlayTransport()))
        g.difficulty = d
        return g
    }

    private fun layout(g: MinesweeperGame) = g.state.value!!.cells.map { it.isMine }

    @Test fun theDailyLayoutDoesNotDependOnTheFirstReveal() {
        for (d in CpuDifficulty.values()) for (seed in listOf(1L, 42L, 20261122L)) {
            val untouched = game(d).also { it.startMatch(seed) }
            val base = layout(untouched)
            val firstHidden = untouched.state.value!!.cells.indices.filter { untouched.state.value!!.cells[it].state == CellState.HIDDEN }
            for (probe in listOf(firstHidden.first(), firstHidden[firstHidden.size / 2], firstHidden.last())) {
                val g = game(d).also { it.startMatch(seed) }
                g.revealCell(probe)
                assertEquals("$d seed $seed first reveal $probe", base, layout(g))
            }
            assertEquals(untouched.state.value!!.mineCount, base.count { it })
        }
    }

    @Test fun theSameSeedGivesTheSameOpenedStartAndADifferentSeedDiffers() {
        val a = game(CpuDifficulty.MEDIUM).also { it.startMatch(7L) }
        val b = game(CpuDifficulty.MEDIUM).also { it.startMatch(7L) }
        assertEquals(a.state.value, b.state.value)
        val c = game(CpuDifficulty.MEDIUM).also { it.startMatch(8L) }
        assertNotEquals(layout(a), layout(c))
    }

    @Test fun theStartIsPreOpenedSafeAndDoesNotStartTheClock() {
        for (seed in 0L until 60L) {
            val g = game(CpuDifficulty.EASY).also { it.startMatch(seed) }
            val s = g.state.value!!
            val opened = s.cells.filter { it.state == CellState.REVEALED }
            assertTrue("seed $seed opened ${opened.size}", opened.isNotEmpty())
            assertTrue(opened.none { it.isMine })
            assertFalse(s.isOver)
            assertNull(g.timerStartElapsedRealtime.value)
        }
    }

    @Test fun freePlayStaysFirstClickSafeAndUnplacedUntilTheFirstReveal() {
        repeat(30) {
            val g = game(CpuDifficulty.EASY).also { it.startMatch() }
            assertTrue(g.state.value!!.cells.none { it.isMine || it.state == CellState.REVEALED })
            g.revealCell(40)
            assertFalse(g.state.value!!.exploded)
        }
    }

    @Test fun aDailyBoardSurvivesSnapshotRestoreUnchanged() {
        val a = game(CpuDifficulty.MEDIUM).also { it.startMatch(99L) }
        val b = game(CpuDifficulty.MEDIUM)
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(a.state.value, b.state.value)
        assertTrue(b.isDailyBoard)
    }
}
