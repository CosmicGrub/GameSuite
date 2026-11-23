package com.gamesuite.games.gemcascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemInputTest {
    @Test fun tapTapSelectsDeselectsSwapsAndMoves() {
        assertEquals(GemInput.TapOutcome.Select(Pos(1, 1)), GemInput.onTap(null, Pos(1, 1)))
        assertEquals(GemInput.TapOutcome.Deselect, GemInput.onTap(Pos(1, 1), Pos(1, 1)))
        assertEquals(GemInput.TapOutcome.Swap(Pos(1, 1), Pos(1, 2)), GemInput.onTap(Pos(1, 1), Pos(1, 2)))
        assertEquals(GemInput.TapOutcome.Swap(Pos(1, 1), Pos(0, 1)), GemInput.onTap(Pos(1, 1), Pos(0, 1)))
        assertEquals(GemInput.TapOutcome.Select(Pos(3, 3)), GemInput.onTap(Pos(1, 1), Pos(3, 3)))
        assertEquals(GemInput.TapOutcome.Select(Pos(2, 2)), GemInput.onTap(Pos(1, 1), Pos(2, 2)))
    }

    @Test fun dragPicksTheDominantDirectionAndStaysOnTheBoard() {
        assertEquals(Pos(2, 3), GemInput.dragTarget(Pos(2, 2), 40f, 5f, 48f, 8, 8))
        assertEquals(Pos(2, 1), GemInput.dragTarget(Pos(2, 2), -40f, 5f, 48f, 8, 8))
        assertEquals(Pos(3, 2), GemInput.dragTarget(Pos(2, 2), 5f, 40f, 48f, 8, 8))
        assertEquals(Pos(1, 2), GemInput.dragTarget(Pos(2, 2), 5f, -40f, 48f, 8, 8))
        assertNull(GemInput.dragTarget(Pos(2, 2), 4f, 4f, 48f, 8, 8))
        assertNull(GemInput.dragTarget(Pos(0, 0), -40f, 0f, 48f, 8, 8))
        assertNull(GemInput.dragTarget(Pos(7, 7), 0f, 40f, 48f, 8, 8))
        assertNull(GemInput.dragTarget(Pos(2, 2), 40f, 0f, 0f, 8, 8))
    }

    @Test fun cellAtMapsTouchesToCells() {
        assertEquals(Pos(0, 0), GemInput.cellAt(1f, 1f, 48f, 8, 8))
        assertEquals(Pos(1, 2), GemInput.cellAt(100f, 60f, 48f, 8, 8))
        assertNull(GemInput.cellAt(-1f, 5f, 48f, 8, 8))
        assertNull(GemInput.cellAt(48f * 8, 5f, 48f, 8, 8))
    }

    @Test fun twistBlocksStayOnTheBoard() {
        assertEquals(Pos(6, 6), GemInput.blockFor(Pos(7, 7), 8, 8))
        assertEquals(Pos(0, 0), GemInput.blockFor(Pos(0, 0), 8, 8))
        assertEquals(Pos(3, 4), GemInput.blockFor(Pos(3, 4), 8, 8))
    }

    @Test fun oneSwitchScanChoosesACellThenADirection() {
        val s = GemScan(8, 8)
        assertEquals(Pos(0, 0), s.highlight())
        s.step(); s.step()
        assertEquals(Pos(0, 2), s.highlight())
        assertTrue(s.select() is GemScan.Result.Chose)
        assertEquals(GemScan.Phase.DIRECTION, s.phase)
        assertEquals(Pos(0, 3), s.highlight()) // right first
        s.step()
        assertEquals(Pos(1, 2), s.highlight()) // then down
        assertEquals(GemScan.Result.Swap(Pos(0, 2), Pos(1, 2)), s.select())
        assertEquals(GemScan.Phase.CELL, s.phase)
        assertEquals(Pos(0, 0), s.highlight())
    }

    @Test fun oneSwitchScanCanCancelAndSkipsOffBoardDirections() {
        val s = GemScan(6, 6)
        s.select() // corner (0,0): only right and down exist
        s.step(); s.step()
        assertNull(s.highlight()) // the cancel slot
        assertEquals(GemScan.Result.Cancelled, s.select())
        assertEquals(GemScan.Phase.CELL, s.phase)
    }

    @Test fun oneSwitchScanWrapsAroundTheBoard() {
        val s = GemScan(6, 6)
        repeat(36) { s.step() }
        assertEquals(Pos(0, 0), s.highlight())
    }

    @Test fun twistScanNamesABlockInOneSelection() {
        val s = GemScan(6, 6, blocks = true)
        repeat(6) { s.step() }
        assertEquals(Pos(1, 1), s.highlight())
        assertEquals(GemScan.Result.Block(Pos(1, 1)), s.select())
        repeat(25) { s.step() }
        assertEquals(Pos(0, 0), s.highlight())
    }

    @Test fun aBatchSummaryCountsWhatHappened() {
        val e = engineOn(baseBoard().put(6, 2 to 0, 2 to 1, 2 to 3, 1 to 2))
        val s = GemBatchSummary.of(e.swap(Pos(1, 2), Pos(2, 2)).events)
        assertTrue(s.gemsMatched >= 3)
        assertTrue(s.scoreGained > 0)
        assertEquals(listOf(SpecialKind.FLARE_COL), s.specialsMade.take(1))
        assertFalse(s.quiet)
        val back = GemBatchSummary.of(engineOn(baseBoard()).swap(Pos(0, 0), Pos(0, 1)).events)
        assertTrue(back.swappedBack)
        assertEquals(0, back.gemsMatched)
        assertFalse(back.quiet)
        assertTrue(GemBatchSummary.of(emptyList()).quiet)
    }
}
